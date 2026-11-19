package com.puretube.app.domain

import com.puretube.app.data.local.datastore.BlurStrength
import com.puretube.app.data.local.datastore.CaptionBackground
import com.puretube.app.data.local.datastore.CaptionTextColor
import com.puretube.app.data.local.datastore.CaptionTextSize
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs

/**
 * v2.1 pure logic: blur strength mapping, player gesture arbitration,
 * caption parsing, audio-track choice building, OAuth device-code
 * response parsing, playlist push planning, and video info formatting.
 * All JVM-testable, no Android types.
 */

// ---------------------------------------------------------------------------
// Blur strength (Part A)
//
// One strength setting drives every surface:
// - Video: the sampler publishes its pixel frame AT the strength's
//   resolution — upscaling a smaller frame = bigger mosaic blocks.
// - Frosted: tint alpha grows with strength (the downscale is the same
//   low-res frame, so stronger also means blurrier + more opaque).
// - Thumbnails (Coil): mosaic/frost divisors shrink with strength.
// STRONG is the default: the old fixed values were LIGHT-equivalent and
// faces showed through.
// ---------------------------------------------------------------------------

object BlurStrengthMath {

    /** Pixel-frame (width, height) the video sampler publishes per strength. */
    fun pixelFrameSize(strength: BlurStrength): Pair<Int, Int> = when (strength) {
        BlurStrength.LIGHT -> 160 to 90
        BlurStrength.MEDIUM -> 128 to 72
        BlurStrength.STRONG -> 80 to 45
        BlurStrength.MAXIMUM -> 48 to 27
    }

    /** Frost tint alpha (0..255) drawn over the smoothed patch. */
    fun frostTintAlpha(strength: BlurStrength): Int = when (strength) {
        BlurStrength.LIGHT -> 0x33
        BlurStrength.MEDIUM -> 0x4D
        BlurStrength.STRONG -> 0x73
        BlurStrength.MAXIMUM -> 0xA6
    }

    /** Thumbnail mosaic: region width / this = block count across (smaller = coarser). */
    fun thumbnailMosaicDivisor(strength: BlurStrength): Int = when (strength) {
        BlurStrength.LIGHT -> 22
        BlurStrength.MEDIUM -> 16
        BlurStrength.STRONG -> 10
        BlurStrength.MAXIMUM -> 6
    }

    /** Thumbnail frost: region width / this = downscale divisor (bigger = blurrier). */
    fun thumbnailFrostDivisor(strength: BlurStrength): Int = when (strength) {
        BlurStrength.LIGHT -> 10
        BlurStrength.MEDIUM -> 14
        BlurStrength.STRONG -> 20
        BlurStrength.MAXIMUM -> 28
    }

    /** Shield mask alpha (0..255): the shield draws flat masks; strength = opacity. */
    fun shieldMaskAlpha(strength: BlurStrength): Int = when (strength) {
        BlurStrength.LIGHT -> 0xD9
        BlurStrength.MEDIUM -> 0xE6
        BlurStrength.STRONG -> 0xF4
        BlurStrength.MAXIMUM -> 0xFF
    }
}

/**
 * FULL_SCREEN blur-mode hysteresis (Part A): once anything is detected,
 * the whole frame stays pixelated until the frame has been clear for
 * [holdMs] — the same anti-flicker idea as the box tracker, one level up.
 */
object FullScreenBlurHold {
    const val HOLD_MS = 600L

    fun isActive(lastDetectionMs: Long, nowMs: Long, hasDetectionsNow: Boolean): Boolean {
        if (hasDetectionsNow) return true
        if (lastDetectionMs <= 0L) return false
        return nowMs - lastDetectionMs <= HOLD_MS
    }
}

// ---------------------------------------------------------------------------
// Gesture arbitration (Part B)
//
// v2.0 BUG: the player ran detectTapGestures (tap / double-tap seek /
// press-hold 2x) and detectVerticalDragGestures as two INDEPENDENT
// pointerInput layers. A vertical volume/brightness drag is also a
// "press" to the tap detector: hold 220ms -> 2x speed; two quick drags
// near each other -> a "double tap" -> +/-10s seek. One physical gesture
// fired two or three actions.
//
// v2.1: ONE classifier owns every pointer event on the video. The first
// classification wins and is final for that gesture:
// - movement past touch slop before anything else -> DRAG (tap,
//   double-tap and hold are cancelled for this gesture, permanently);
// - a press that stays within slop past the hold threshold -> HOLD
//   (2x while held; release ends it; it can never become a seek);
// - a clean tap (within slop, released before hold threshold) is only a
//   TAP once the double-tap window expires with no second tap; a second
//   clean tap inside the window -> DOUBLE_TAP. A dirty second gesture
//   (drag/hold) does NOT consume the pending tap as a double tap — the
//   pending tap resolves as a single TAP and the new gesture classifies
//   on its own.
// ---------------------------------------------------------------------------

class GestureClassifier {
    companion object {
        const val TOUCH_SLOP_PX = 24f
        const val HOLD_THRESHOLD_MS = 220L
        const val DOUBLE_TAP_TIMEOUT_MS = 300L
        const val TAP_MAX_DURATION_MS = 250L
    }

    enum class Action {
        TAP, DOUBLE_TAP, HOLD_START, HOLD_END,
        DRAG_START, DRAG_DELTA, DRAG_END
    }

    data class Event(
        val action: Action,
        /** Down-position x (TAP/DOUBLE_TAP/DRAG_START) — side/zone decisions use it. */
        val x: Float = 0f,
        val y: Float = 0f,
        /** Cumulative vertical drag distance for DRAG_DELTA/DRAG_END. */
        val dragDy: Float = 0f
    )

    private enum class Phase {
        IDLE, PRESSING, DRAGGING, HOLDING,
        /** First clean tap released; waiting out the double-tap window. */
        AWAITING_SECOND, SECOND_PRESSING, SECOND_DRAGGING, SECOND_HOLDING
    }

    private var phase = Phase.IDLE
    private var downX = 0f
    private var downY = 0f
    private var downTimeMs = 0L
    private var firstTapX = 0f
    private var firstTapUpTimeMs = 0L
    private var holdFired = false

    fun reset() {
        phase = Phase.IDLE
        holdFired = false
    }

    /** True while a clean first tap is waiting out the double-tap window. */
    fun isAwaitingSecondTap(): Boolean = phase == Phase.AWAITING_SECOND

    private fun beyondSlop(x: Float, y: Float): Boolean =
        abs(x - downX) > TOUCH_SLOP_PX || abs(y - downY) > TOUCH_SLOP_PX

    /** Pointer down. Returns events to execute immediately (usually none). */
    fun onDown(x: Float, y: Float, nowMs: Long): List<Event> {
        return when (phase) {
            Phase.AWAITING_SECOND -> {
                if (nowMs - firstTapUpTimeMs <= DOUBLE_TAP_TIMEOUT_MS) {
                    phase = Phase.SECOND_PRESSING
                    downX = x; downY = y; downTimeMs = nowMs; holdFired = false
                    emptyList()
                } else {
                    // Window already expired (the UI polls onTimeout too):
                    // resolve the pending tap, treat this as a fresh press.
                    val tap = Event(Action.TAP, firstTapX)
                    phase = Phase.PRESSING
                    downX = x; downY = y; downTimeMs = nowMs; holdFired = false
                    listOf(tap)
                }
            }
            else -> {
                phase = Phase.PRESSING
                downX = x; downY = y; downTimeMs = nowMs; holdFired = false
                emptyList()
            }
        }
    }

    /** Pointer move (total offset from down is derived from [x]/[y]). */
    fun onMove(x: Float, y: Float, nowMs: Long): List<Event> {
        return when (phase) {
            Phase.PRESSING -> {
                if (beyondSlop(x, y)) {
                    phase = Phase.DRAGGING
                    listOf(
                        Event(Action.DRAG_START, downX, downY),
                        Event(Action.DRAG_DELTA, downX, downY, dragDy = y - downY)
                    )
                } else {
                    maybeHold(nowMs)
                }
            }
            Phase.SECOND_PRESSING -> {
                if (beyondSlop(x, y)) {
                    // Dirty second gesture: the pending tap resolves as a
                    // single TAP; this gesture is a drag of its own.
                    phase = Phase.SECOND_DRAGGING
                    listOf(
                        Event(Action.TAP, firstTapX),
                        Event(Action.DRAG_START, downX, downY),
                        Event(Action.DRAG_DELTA, downX, downY, dragDy = y - downY)
                    )
                } else {
                    maybeHold(nowMs, second = true)
                }
            }
            Phase.DRAGGING, Phase.SECOND_DRAGGING ->
                listOf(Event(Action.DRAG_DELTA, downX, downY, dragDy = y - downY))
            else -> emptyList()
        }
    }

    private fun maybeHold(nowMs: Long, second: Boolean = false): List<Event> {
        if (!holdFired && nowMs - downTimeMs >= HOLD_THRESHOLD_MS) {
            holdFired = true
            phase = if (second) Phase.SECOND_HOLDING else Phase.HOLDING
            return if (second) {
                listOf(Event(Action.TAP, firstTapX), Event(Action.HOLD_START, downX, downY))
            } else {
                listOf(Event(Action.HOLD_START, downX, downY))
            }
        }
        return emptyList()
    }

    /** Periodic tick from the UI so HOLD can fire while the finger is still. */
    fun onTick(nowMs: Long): List<Event> = when (phase) {
        Phase.PRESSING -> maybeHold(nowMs)
        Phase.SECOND_PRESSING -> maybeHold(nowMs, second = true)
        else -> emptyList()
    }

    /** Pointer up. */
    fun onUp(nowMs: Long): List<Event> {
        return when (phase) {
            Phase.DRAGGING -> {
                phase = Phase.IDLE
                listOf(Event(Action.DRAG_END))
            }
            Phase.SECOND_DRAGGING -> {
                phase = Phase.IDLE
                listOf(Event(Action.DRAG_END))
            }
            Phase.HOLDING -> {
                phase = Phase.IDLE
                listOf(Event(Action.HOLD_END))
            }
            Phase.SECOND_HOLDING -> {
                phase = Phase.IDLE
                listOf(Event(Action.HOLD_END))
            }
            Phase.PRESSING -> {
                val duration = nowMs - downTimeMs
                if (duration <= TAP_MAX_DURATION_MS) {
                    phase = Phase.AWAITING_SECOND
                    firstTapX = downX
                    firstTapUpTimeMs = nowMs
                    emptyList() // resolves via onTimeout or a second tap
                } else {
                    phase = Phase.IDLE
                    emptyList() // long press that never hit hold threshold: nothing
                }
            }
            Phase.SECOND_PRESSING -> {
                val duration = nowMs - downTimeMs
                phase = Phase.IDLE
                if (duration <= TAP_MAX_DURATION_MS) {
                    listOf(Event(Action.DOUBLE_TAP, downX, downY))
                } else {
                    // Second press was long but sub-hold: just the first tap.
                    listOf(Event(Action.TAP, firstTapX))
                }
            }
            else -> emptyList()
        }
    }

    /** The double-tap window expired with no second tap: pending tap fires. */
    fun onTimeout(nowMs: Long): List<Event> {
        if (phase == Phase.AWAITING_SECOND &&
            nowMs - firstTapUpTimeMs > DOUBLE_TAP_TIMEOUT_MS
        ) {
            phase = Phase.IDLE
            return listOf(Event(Action.TAP, firstTapX))
        }
        return emptyList()
    }

    /** Gesture cancelled by the framework: resolve anything pending safely. */
    fun onCancel(): List<Event> {
        val events = when (phase) {
            Phase.HOLDING, Phase.SECOND_HOLDING -> listOf(Event(Action.HOLD_END))
            Phase.DRAGGING, Phase.SECOND_DRAGGING -> listOf(Event(Action.DRAG_END))
            else -> emptyList()
        }
        phase = Phase.IDLE
        holdFired = false
        return events
    }
}

// ---------------------------------------------------------------------------
// Captions (Part E): WebVTT / SRT / TTML parsing + cue lookup.
// YouTube serves caption tracks as timedtext files; NewPipe hands us the
// URL + format. We parse locally and render cues in Compose, so caption
// styling (size/colour/background/bold) is fully ours.
// ---------------------------------------------------------------------------

data class CaptionCue(
    val startMs: Long,
    val endMs: Long,
    val text: String
)

object CaptionParser {

    /** Parses caption file content, sniffing the format. Never throws. */
    fun parse(content: String): List<CaptionCue> {
        val trimmed = content.trimStart()
        return when {
            trimmed.startsWith("WEBVTT") -> parseVtt(content, srt = false)
            trimmed.startsWith("<") && (trimmed.contains("<tt") || trimmed.contains("<p ")) ->
                parseTtml(content)
            trimmed.firstOrNull()?.isDigit() == true -> parseVtt(content, srt = true)
            else -> parseVtt(content, srt = false)
        }
    }

    /** The cue visible at [positionMs], or null. Cues are start-sorted. */
    fun cueAt(cues: List<CaptionCue>, positionMs: Long): CaptionCue? =
        cues.firstOrNull { positionMs >= it.startMs && positionMs < it.endMs }

    private val vttTimeRegex =
        Regex("""(\d+)[:.](\d{2})[:.](\d{2})[.,](\d{3})|(\d{2}):(\d{2})[.,](\d{3})""")

    fun parseTimestamp(text: String): Long? {
        val m = vttTimeRegex.find(text.trim()) ?: return null
        return if (m.groupValues[1].isNotEmpty()) {
            val h = m.groupValues[1].toLong()
            val min = m.groupValues[2].toLong()
            val sec = m.groupValues[3].toLong()
            val ms = m.groupValues[4].toLong()
            ((h * 60 + min) * 60 + sec) * 1000 + ms
        } else {
            val min = m.groupValues[5].toLong()
            val sec = m.groupValues[6].toLong()
            val ms = m.groupValues[7].toLong()
            (min * 60 + sec) * 1000 + ms
        }
    }

    private fun parseVtt(content: String, srt: Boolean): List<CaptionCue> {
        val cues = mutableListOf<CaptionCue>()
        val blocks = content.replace("\r\n", "\n").split(Regex("\n\\s*\n"))
        for (block in blocks) {
            val lines = block.lines().map { it.trim() }.filter { it.isNotEmpty() }
            val timingIdx = lines.indexOfFirst { it.contains("-->") }
            if (timingIdx < 0) continue
            val parts = lines[timingIdx].split("-->")
            if (parts.size < 2) continue
            val start = parseTimestamp(parts[0]) ?: continue
            val end = parseTimestamp(parts[1]) ?: continue
            val textLines = lines.drop(timingIdx + 1)
            if (textLines.isEmpty()) continue
            val text = cleanCueText(textLines.joinToString("\n"))
            if (text.isNotBlank()) cues.add(CaptionCue(start, end, text))
        }
        return cues.sortedBy { it.startMs }
    }

    private val ttmlCueRegex =
        Regex("""<p[^>]*begin="([^"]+)"[^>]*end="([^"]+)"[^>]*>(.*?)</p>""", RegexOption.DOT_MATCHES_ALL)
    private val ttmlTickRegex = Regex("""([\d.]+)(h|m|s|ms|t)""")

    fun parseTtmlTimestamp(text: String, tickRate: Double = 1.0): Long? {
        // Clock time first (H:MM:SS.mmm / MM:SS.mmm — parseTimestamp's
        // regex accepts both '.' and ',' before the millisecond part).
        parseTimestamp(text)?.let { return it }
        // Offset time: "12.5s", "1m30s", "500ms", "75t".
        var totalMs = 0.0
        var matched = false
        for (m in ttmlTickRegex.findAll(text.trim())) {
            matched = true
            val value = m.groupValues[1].toDoubleOrNull() ?: return null
            totalMs += when (m.groupValues[2]) {
                "h" -> value * 3_600_000
                "m" -> value * 60_000
                "s" -> value * 1_000
                "ms" -> value
                "t" -> value * 1000.0 / tickRate
                else -> 0.0
            }
        }
        return if (matched) totalMs.toLong() else null
    }

    private fun parseTtml(content: String): List<CaptionCue> {
        val cues = mutableListOf<CaptionCue>()
        for (m in ttmlCueRegex.findAll(content)) {
            val start = parseTtmlTimestamp(m.groupValues[1]) ?: continue
            val end = parseTtmlTimestamp(m.groupValues[2]) ?: continue
            val text = cleanCueText(
                m.groupValues[3].replace(Regex("<br\\s*/?>"), "\n")
            )
            if (text.isNotBlank()) cues.add(CaptionCue(start, end, text))
        }
        return cues.sortedBy { it.startMs }
    }

    private val tagRegex = Regex("<[^>]+>")

    /** Strips tags/entities from cue text (VTT voice tags, TTML spans, roll-up dupes). */
    fun cleanCueText(raw: String): String {
        var text = raw.replace(tagRegex, "")
        text = text
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&apos;", "'")
            .replace("&nbsp;", " ")
        // YouTube auto-captions repeat the previous line (roll-up): drop
        // consecutive duplicate lines inside one cue.
        val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
        val deduped = mutableListOf<String>()
        for (line in lines) {
            if (deduped.lastOrNull() != line) deduped.add(line)
        }
        return deduped.joinToString("\n").trim()
    }
}

/** Caption style derived from the caption settings (Part E). Pure values for Compose. */
object CaptionStyleMath {
    fun fontSizeSp(size: CaptionTextSize): Float = when (size) {
        CaptionTextSize.SMALL -> 12f
        CaptionTextSize.MEDIUM -> 15f
        CaptionTextSize.LARGE -> 19f
        CaptionTextSize.EXTRA_LARGE -> 24f
    }

    /** ARGB text colour. */
    fun textColorArgb(color: CaptionTextColor): Long = when (color) {
        CaptionTextColor.WHITE -> 0xFFFFFFFF
        CaptionTextColor.YELLOW -> 0xFFFFFF00
        CaptionTextColor.CYAN -> 0xFF00E5FF
    }

    /** ARGB background colour (0 = fully transparent). */
    fun backgroundArgb(background: CaptionBackground): Long = when (background) {
        CaptionBackground.NONE -> 0x00000000
        CaptionBackground.TRANSLUCENT -> 0x99000000
        CaptionBackground.BLACK -> 0xFF000000
    }
}

/** One selectable caption track (Part E). */
data class CaptionTrackOption(
    val languageCode: String,
    val label: String,
    val url: String,
    val autoGenerated: Boolean
)

/** One selectable audio track (Part E), as seen on the live player. */
data class AudioTrackChoice(
    val groupIndex: Int,
    val trackIndex: Int,
    val language: String,
    val label: String
)

object AudioTrackLogic {

    /**
     * Builds the audio menu from raw player track data (group/track
     * indices + format language/label). One entry per audio track;
     * duplicates by language keep the first (highest-quality group order).
     */
    fun buildChoices(
        raw: List<AudioTrackChoice>
    ): List<AudioTrackChoice> {
        val seenLanguages = HashSet<String>()
        return raw.filter { choice ->
            val key = choice.language.lowercase().ifBlank { "track-${choice.groupIndex}-${choice.trackIndex}" }
            seenLanguages.add(key)
        }
    }

    /** Display label for a track: explicit label, else language name, else "Track N". */
    fun displayLabel(choice: AudioTrackChoice): String {
        if (choice.label.isNotBlank()) return choice.label
        val lang = choice.language.trim()
        if (lang.isNotBlank() && lang != "und") {
            return runCatching { Locale.forLanguageTag(lang).displayLanguage }
                .getOrDefault(lang)
                .replaceFirstChar { it.uppercase() } + " ($lang)"
        }
        return "Audio track ${choice.trackIndex + 1}"
    }

    /** The choice matching [languageCode] (exact, then primary-subtag), or null. */
    fun choiceForLanguage(
        choices: List<AudioTrackChoice>,
        languageCode: String?
    ): AudioTrackChoice? {
        if (languageCode.isNullOrBlank()) return null
        val wanted = languageCode.lowercase()
        choices.firstOrNull { it.language.lowercase() == wanted }?.let { return it }
        val primary = wanted.substringBefore('-')
        return choices.firstOrNull {
            it.language.lowercase().substringBefore('-') == primary
        }
    }
}

/** Caption track list building from raw extractor data (Part E). */
object CaptionTrackLogic {

    /**
     * Dedupes raw tracks by language (manual beats auto-generated for the
     * same language) and sorts: manual first, then by label.
     */
    fun buildOptions(raw: List<CaptionTrackOption>): List<CaptionTrackOption> {
        val byLanguage = LinkedHashMap<String, CaptionTrackOption>()
        for (track in raw) {
            if (track.url.isBlank() || track.languageCode.isBlank()) continue
            val key = track.languageCode.lowercase()
            val existing = byLanguage[key]
            if (existing == null || (existing.autoGenerated && !track.autoGenerated)) {
                byLanguage[key] = track
            }
        }
        return byLanguage.values.sortedWith(
            compareBy<CaptionTrackOption> { it.autoGenerated }.thenBy { it.label.lowercase() }
        )
    }

    /** Menu label: "English", "Arabic (auto-generated)", ... */
    fun displayLabel(track: CaptionTrackOption): String {
        val base = track.label.ifBlank {
            runCatching {
                Locale.forLanguageTag(track.languageCode).displayLanguage
            }.getOrDefault(track.languageCode)
        }
        return if (track.autoGenerated) "$base (auto)" else base
    }
}

// ---------------------------------------------------------------------------
// OAuth device-code flow (Part F). Google's limited-input-device flow
// with the public YouTube TV client identity — the same pattern TV
// clients use: the app shows a short code, the user approves it on ANY
// device at the verification URL, the app polls for the token. No
// computer, no DevTools, no embedded-browser sign-in.
// ---------------------------------------------------------------------------

data class DeviceCodeGrant(
    val deviceCode: String,
    val userCode: String,
    val verificationUrl: String,
    val expiresInSeconds: Long,
    val intervalSeconds: Long
)

sealed interface DevicePollResult {
    data object Pending : DevicePollResult
    data object SlowDown : DevicePollResult
    data class Approved(
        val accessToken: String,
        val refreshToken: String,
        val expiresInSeconds: Long
    ) : DevicePollResult
    data object Denied : DevicePollResult
    data object Expired : DevicePollResult
    data class Failed(val message: String) : DevicePollResult
}

object DeviceCodeParse {

    fun parseDeviceCodeResponse(root: JsonElement): DeviceCodeGrant? {
        val obj = root as? JsonObject ?: return null
        fun str(key: String): String? =
            (obj[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
        fun long(key: String): Long? =
            (obj[key] as? JsonPrimitive)?.content?.toLongOrNull()
        val deviceCode = str("device_code")?.takeIf { it.isNotBlank() } ?: return null
        val userCode = str("user_code")?.takeIf { it.isNotBlank() } ?: return null
        return DeviceCodeGrant(
            deviceCode = deviceCode,
            userCode = userCode,
            verificationUrl = str("verification_url") ?: "https://www.google.com/device",
            expiresInSeconds = long("expires_in") ?: 1800L,
            intervalSeconds = (long("interval") ?: 5L).coerceAtLeast(1L)
        )
    }

    /** Token-endpoint response (poll or refresh) -> typed result. */
    fun parseTokenResponse(root: JsonElement, httpCode: Int): DevicePollResult {
        val obj = root as? JsonObject
            ?: return DevicePollResult.Failed("Unexpected response from Google (HTTP $httpCode)")
        fun str(key: String): String? =
            (obj[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
        val error = str("error")
        if (error != null) {
            return when (error) {
                "authorization_pending" -> DevicePollResult.Pending
                "slow_down" -> DevicePollResult.SlowDown
                "access_denied" -> DevicePollResult.Denied
                "expired_token" -> DevicePollResult.Expired
                "invalid_client" -> DevicePollResult.Failed(
                    "Google rejected the TV client identity (invalid_client) — " +
                        "device sign-in is unavailable in this build."
                )
                else -> DevicePollResult.Failed("Google sign-in error: $error")
            }
        }
        val accessToken = str("access_token")?.takeIf { it.isNotBlank() }
            ?: return DevicePollResult.Failed("Google returned no access token.")
        val expires = (obj["expires_in"] as? JsonPrimitive)?.content?.toLongOrNull() ?: 3600L
        return DevicePollResult.Approved(
            accessToken = accessToken,
            // Refresh responses omit refresh_token; the caller keeps the old one.
            refreshToken = str("refresh_token").orEmpty(),
            expiresInSeconds = expires
        )
    }
}

// ---------------------------------------------------------------------------
// Playlist push planning (Part F)
// ---------------------------------------------------------------------------

object PlaylistPushPlanner {

    /** YouTube ids that cannot be edited via playlist edit (Liked / Watch Later). */
    fun isEditablePlaylist(youtubePlaylistId: String?): Boolean {
        if (youtubePlaylistId.isNullOrBlank()) return true // not created yet
        val bare = youtubePlaylistId.removePrefix("VL")
        return bare != "LL" && bare != "WL"
    }

    /**
     * Videos to add remotely: local videos (in local order, deduped)
     * whose ids are not already on YouTube. Pushing twice adds nothing.
     */
    fun missingVideos(localVideoIds: List<String>, remoteVideoIds: Set<String>): List<String> {
        val seen = HashSet<String>()
        return localVideoIds.filter { id ->
            id.isNotBlank() && id !in remoteVideoIds && seen.add(id)
        }
    }
}

// ---------------------------------------------------------------------------
// Video info line (Part C): "12 Nov 2026 · 1.2M views" pieces.
// ---------------------------------------------------------------------------

object VideoInfoFormat {

    private val absoluteFormatter: DateTimeFormatter =
        DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH)

    /** Absolute upload date ("12 Nov 2026"), or "" when unknown. */
    fun absoluteDate(uploadEpochMs: Long, zone: ZoneId = ZoneId.systemDefault()): String {
        if (uploadEpochMs <= 0L) return ""
        return runCatching {
            absoluteFormatter.format(Instant.ofEpochMilli(uploadEpochMs).atZone(zone))
        }.getOrDefault("")
    }

    /** Relative upload age ("3 days ago", "2 months ago"), or "" when unknown/future. */
    fun relativeDate(uploadEpochMs: Long, nowMs: Long): String {
        if (uploadEpochMs <= 0L) return ""
        val diffMs = nowMs - uploadEpochMs
        if (diffMs < 0L) return ""
        val minutes = diffMs / 60_000L
        val hours = diffMs / 3_600_000L
        val days = diffMs / 86_400_000L
        return when {
            minutes < 1 -> "just now"
            minutes < 60 -> "$minutes min ago"
            hours < 24 -> "$hours hours ago"
            days < 30 -> "$days days ago"
            days < 365 -> "${days / 30} months ago"
            else -> "${days / 365} years ago"
        }
    }

    /** Compact view count: 950 -> "950", 12_300 -> "12.3K", 4_500_000 -> "4.5M". */
    fun compactCount(count: Long): String {
        if (count < 0) return ""
        return when {
            count < 1_000 -> count.toString()
            count < 1_000_000 -> trimZero(count / 1_000.0) + "K"
            count < 1_000_000_000 -> trimZero(count / 1_000_000.0) + "M"
            else -> trimZero(count / 1_000_000_000.0) + "B"
        }
    }

    private fun trimZero(value: Double): String {
        val text = String.format(Locale.US, "%.1f", value)
        return text.removeSuffix(".0")
    }
}

// ---------------------------------------------------------------------------
// Language support sanity (Part D)
// ---------------------------------------------------------------------------

object LanguageSupport {
    /** Languages with bundled string resources (plus SYSTEM). */
    val translatedCodes: Set<String> = setOf("en", "ar", "es", "fr", "tr", "ur")

    fun hasTranslation(code: String): Boolean = code in translatedCodes
}
