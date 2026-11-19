package com.puretube.app.domain

import com.puretube.app.data.local.datastore.VideoAspectMode
import com.puretube.app.data.remote.extractor.FeedItem
import com.puretube.app.haramblur.engine.NormalizedRect
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.security.MessageDigest

/**
 * v2.0 pure logic: displayed-video rect / aspect modes + blur overlay
 * mapping, the volume-zero pause/stop state machine, SAPISIDHASH auth,
 * cookie parsing, related-list dedupe, and Innertube response parsing.
 * All JVM-testable, no Android types.
 */

// ---------------------------------------------------------------------------
// Displayed-video rect + aspect modes (Part A.2 / A.4)
//
// The player's TextureView is laid out at EXACTLY the rect the video
// picture occupies inside its container, so the sampler bitmap is always
// in video-frame space and detections map to the screen through this
// rect — never by assuming the picture fills the container.
// ---------------------------------------------------------------------------

/** A rectangle in container pixels (floats; may extend past the container for ZOOM). */
data class PixelRect(
    val left: Float,
    val top: Float,
    val width: Float,
    val height: Float
) {
    val right: Float get() = left + width
    val bottom: Float get() = top + height
}

object VideoRectMath {

    /**
     * The rect the video picture occupies inside a [containerW]x[containerH]
     * box for the given aspect [mode]:
     * - FIT: letterboxed, whole picture visible, centred.
     * - ZOOM: centre-cropped fill — the rect covers the container and
     *   extends past it on one axis.
     * - STRETCH: the container itself (picture distorted to fill).
     * Unknown video size (<= 0) falls back to the container.
     */
    fun displayedVideoRect(
        containerW: Float,
        containerH: Float,
        videoW: Int,
        videoH: Int,
        mode: VideoAspectMode
    ): PixelRect {
        if (containerW <= 0f || containerH <= 0f) return PixelRect(0f, 0f, 0f, 0f)
        if (mode == VideoAspectMode.STRETCH || videoW <= 0 || videoH <= 0) {
            return PixelRect(0f, 0f, containerW, containerH)
        }
        val videoAspect = videoW.toFloat() / videoH.toFloat()
        val containerAspect = containerW / containerH
        val w: Float
        val h: Float
        when (mode) {
            VideoAspectMode.FIT -> {
                if (videoAspect > containerAspect) {
                    w = containerW; h = containerW / videoAspect
                } else {
                    h = containerH; w = containerH * videoAspect
                }
            }
            VideoAspectMode.ZOOM -> {
                if (videoAspect > containerAspect) {
                    h = containerH; w = containerH * videoAspect
                } else {
                    w = containerW; h = containerW / videoAspect
                }
            }
            VideoAspectMode.STRETCH -> {
                w = containerW; h = containerH
            }
        }
        return PixelRect(
            left = (containerW - w) / 2f,
            top = (containerH - h) / 2f,
            width = w,
            height = h
        )
    }

    /** Maps a region in video-frame [0,1] space into container pixels via [videoRect]. */
    fun mapRegion(region: NormalizedRect, videoRect: PixelRect): PixelRect =
        PixelRect(
            left = videoRect.left + region.left * videoRect.width,
            top = videoRect.top + region.top * videoRect.height,
            width = region.width * videoRect.width,
            height = region.height * videoRect.height
        )
}

// ---------------------------------------------------------------------------
// Volume-zero rule (Part A.1)
//
// STREAM_MUSIC at 0 while a video plays -> PAUSE. Still 0 after the
// configured delay -> STOP. Volume rising cancels the pending stop but
// NEVER resumes playback: there is deliberately no RESUME action.
// ---------------------------------------------------------------------------

object VolumeZeroRule {
    /** stopDelayMs sentinel: pause only, never stop. */
    const val NEVER_STOP_MS = 0L

    enum class Phase { INACTIVE, ZERO_PENDING_STOP, STOPPED }
    enum class Action { NONE, PAUSE, STOP }

    data class State(
        val phase: Phase = Phase.INACTIVE,
        val zeroSinceMs: Long = 0L
    )

    fun reset(): State = State()

    /**
     * One sample of the world. [hasMedia] = a video is loaded (player not
     * idle). STOPPED is terminal for the current media item; the caller
     * resets the state when a new item loads.
     */
    fun onSample(
        state: State,
        volumeIsZero: Boolean,
        isPlaying: Boolean,
        hasMedia: Boolean,
        enabled: Boolean,
        stopDelayMs: Long,
        nowMs: Long
    ): Pair<State, Action> {
        if (state.phase == Phase.STOPPED) return state to Action.NONE
        if (!enabled || !hasMedia) return State() to Action.NONE
        if (!volumeIsZero) {
            // Timer cancels; playback is NEVER auto-resumed by this rule.
            return State() to Action.NONE
        }
        return when (state.phase) {
            Phase.INACTIVE ->
                State(Phase.ZERO_PENDING_STOP, nowMs) to
                    (if (isPlaying) Action.PAUSE else Action.NONE)
            Phase.ZERO_PENDING_STOP -> {
                if (stopDelayMs > NEVER_STOP_MS && nowMs - state.zeroSinceMs >= stopDelayMs) {
                    State(Phase.STOPPED, state.zeroSinceMs) to Action.STOP
                } else {
                    state to Action.NONE
                }
            }
            Phase.STOPPED -> state to Action.NONE
        }
    }
}

// ---------------------------------------------------------------------------
// SAPISIDHASH authorization (Part B)
//
// Authorization: SAPISIDHASH <unixSeconds>_<sha1("<unixSeconds> <SAPISID> <origin>")>
// ---------------------------------------------------------------------------

object SapisidHash {
    const val ORIGIN = "https://www.youtube.com"

    fun sha1Hex(input: String): String {
        val digest = MessageDigest.getInstance("SHA-1")
            .digest(input.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    fun authorizationHeader(
        sapisid: String,
        timestampSeconds: Long,
        origin: String = ORIGIN
    ): String = "SAPISIDHASH ${timestampSeconds}_${sha1Hex("$timestampSeconds $sapisid $origin")}"
}

/** Parses a Cookie header into name -> value. Garbage parts are skipped. */
object CookieHeader {
    fun parse(header: String): Map<String, String> {
        if (header.isBlank()) return emptyMap()
        val result = LinkedHashMap<String, String>()
        for (part in header.split(';')) {
            val eq = part.indexOf('=')
            if (eq <= 0) continue
            val name = part.substring(0, eq).trim()
            val value = part.substring(eq + 1).trim()
            if (name.isNotEmpty()) result[name] = value
        }
        return result
    }

    /** The SAPISID cookie value, or null when missing/blank (header rejected). */
    fun sapisid(header: String): String? =
        parse(header)["SAPISID"]?.takeIf { it.isNotBlank() }

    fun isUsable(header: String): Boolean = sapisid(header) != null
}

// ---------------------------------------------------------------------------
// Related / Up Next dedupe (Part B)
// ---------------------------------------------------------------------------

object RelatedDedupe {
    /** Drops the current video, anything in [excludeIds], blanks and duplicates. */
    fun dedupe(
        items: List<FeedItem>,
        currentVideoId: String,
        excludeIds: Set<String> = emptySet()
    ): List<FeedItem> {
        val seen = HashSet<String>()
        return items.filter { item ->
            item.videoId.isNotBlank() &&
                item.videoId != currentVideoId &&
                item.videoId !in excludeIds &&
                seen.add(item.videoId)
        }
    }
}

// ---------------------------------------------------------------------------
// Innertube response parsing (Part B). Lenient recursive walkers over the
// JSON tree — YouTube reshuffles these payloads often, so nothing here
// depends on an exact path.
// ---------------------------------------------------------------------------

data class ParsedVideo(
    val videoId: String,
    val title: String,
    val channelName: String,
    val channelId: String,
    val thumbnailUrl: String,
    val durationSeconds: Long
)

data class ParsedChannel(
    val channelId: String,
    val name: String,
    val avatarUrl: String
)

data class ParsedPlaylistRef(
    val title: String,
    /** Full browse id, e.g. "VLPL..." for a playlist, "VLLL" for Liked videos. */
    val browseId: String
)

data class ParsedAccount(
    val name: String,
    val avatarUrl: String,
    val handle: String
)

object InnertubeParse {

    private fun JsonElement.asObject(): JsonObject? = this as? JsonObject
    private fun JsonElement.asArray(): JsonArray? = this as? JsonArray
    private fun JsonElement.asString(): String? =
        (this as? JsonPrimitive)?.takeIf { it.isString }?.content

    /** Text of a YouTube text node: {"simpleText": "..."} or {"runs": [{"text": ...}]}. */
    fun textOf(node: JsonElement?): String {
        val obj = node?.asObject() ?: return ""
        obj["simpleText"]?.asString()?.let { return it }
        val runs = obj["runs"]?.asArray() ?: return ""
        return runs.joinToString("") { run ->
            run.asObject()?.get("text")?.asString().orEmpty()
        }
    }

    /** All objects stored under [key] anywhere in the tree (depth-first). */
    fun findObjects(root: JsonElement?, key: String): List<JsonObject> {
        val out = mutableListOf<JsonObject>()
        fun walk(node: JsonElement?) {
            when (node) {
                is JsonObject -> {
                    node[key]?.asObject()?.let { out.add(it) }
                    node.values.forEach { walk(it) }
                }
                is JsonArray -> node.forEach { walk(it) }
                else -> Unit
            }
        }
        walk(root)
        return out
    }

    /** First string value stored under [key] anywhere in the tree. */
    fun findString(root: JsonElement?, key: String): String? {
        var found: String? = null
        fun walk(node: JsonElement?) {
            if (found != null) return
            when (node) {
                is JsonObject -> {
                    node[key]?.asString()?.let { found = it; return }
                    node.values.forEach { walk(it) }
                }
                is JsonArray -> node.forEach { walk(it) }
                else -> Unit
            }
        }
        walk(root)
        return found
    }

    private fun lastThumbnailUrl(thumbnailsNode: JsonElement?): String {
        val arr = thumbnailsNode?.asObject()?.get("thumbnails")?.asArray() ?: return ""
        return arr.lastOrNull()?.asObject()?.get("url")?.asString().orEmpty()
    }

    /** "12:34" -> 754, "1:02:03" -> 3723, anything else -> 0. */
    fun parseDurationText(text: String?): Long {
        val parts = text?.trim()?.split(":")?.map { it.toLongOrNull() } ?: return 0L
        if (parts.any { it == null }) return 0L
        return when (parts.size) {
            1 -> parts[0]!!
            2 -> parts[0]!! * 60 + parts[1]!!
            3 -> parts[0]!! * 3600 + parts[1]!! * 60 + parts[2]!!
            else -> 0L
        }
    }

    private fun videoRendererToParsed(renderer: JsonObject): ParsedVideo? {
        val videoId = renderer["videoId"]?.asString()?.takeIf { it.isNotBlank() } ?: return null
        val byline = renderer["longBylineText"]?.asObject()
            ?: renderer["shortBylineText"]?.asObject()
        val bylineRun = byline?.get("runs")?.asArray()?.firstOrNull()?.asObject()
        val channelId = bylineRun?.get("navigationEndpoint")?.asObject()
            ?.get("browseEndpoint")?.asObject()
            ?.get("browseId")?.asString().orEmpty()
        return ParsedVideo(
            videoId = videoId,
            title = textOf(renderer["title"]),
            channelName = bylineRun?.get("text")?.asString()
                ?: textOf(renderer["shortBylineText"]),
            channelId = channelId,
            thumbnailUrl = lastThumbnailUrl(renderer["thumbnail"]),
            durationSeconds = parseDurationText(
                renderer["lengthText"]?.asObject()?.get("simpleText")?.asString()
            )
        )
    }

    /** Every videoRenderer / compactVideoRenderer in the payload, deduped by id. */
    fun parseVideos(root: JsonElement?): List<ParsedVideo> {
        val renderers = findObjects(root, "videoRenderer") + findObjects(root, "compactVideoRenderer")
        val seen = HashSet<String>()
        return renderers.mapNotNull { videoRendererToParsed(it) }
            .filter { seen.add(it.videoId) }
    }

    /** Every channelRenderer in the payload (subscriptions browse), deduped. */
    fun parseChannels(root: JsonElement?): List<ParsedChannel> {
        val seen = HashSet<String>()
        return findObjects(root, "channelRenderer").mapNotNull { renderer ->
            val channelId = renderer["channelId"]?.asString()?.takeIf { it.isNotBlank() }
                ?: return@mapNotNull null
            if (!seen.add(channelId)) return@mapNotNull null
            ParsedChannel(
                channelId = channelId,
                name = textOf(renderer["title"]),
                avatarUrl = lastThumbnailUrl(renderer["thumbnail"])
            )
        }
    }

    /**
     * Playlist entries from the guide response: guide entries whose browse
     * id is a playlist ("VLPL...", "VLLL", "VLWL"...). Titles come from the
     * entry's formattedTitle.
     */
    fun parseGuidePlaylists(root: JsonElement?): List<ParsedPlaylistRef> {
        val seen = HashSet<String>()
        val out = mutableListOf<ParsedPlaylistRef>()
        for (entry in findObjects(root, "guideEntryRenderer")) {
            val browseId = entry["navigationEndpoint"]?.asObject()
                ?.get("browseEndpoint")?.asObject()
                ?.get("browseId")?.asString() ?: continue
            if (!browseId.startsWith("VL")) continue
            if (!seen.add(browseId)) continue
            out.add(
                ParsedPlaylistRef(
                    title = textOf(entry["formattedTitle"]),
                    browseId = browseId
                )
            )
        }
        return out
    }

    /** Account identity from the account_menu response. Null when unreadable. */
    fun parseAccount(root: JsonElement?): ParsedAccount? {
        val name = findObjects(root, "accountName")
            .firstOrNull()?.let { textOf(it) }
            ?.takeIf { it.isNotBlank() } ?: return null
        val avatar = findObjects(root, "accountPhoto")
            .firstOrNull()?.let { lastThumbnailUrl(it) }.orEmpty()
        val handle = findObjects(root, "channelHandle")
            .firstOrNull()?.let { textOf(it) }.orEmpty()
        return ParsedAccount(name = name, avatarUrl = avatar, handle = handle)
    }

    /** The createCommentParams token buried in a comments continuation response. */
    fun findCreateCommentParams(root: JsonElement?): String? =
        findString(root, "createCommentParams")?.takeIf { it.isNotBlank() }

    /**
     * The comments-section continuation token from a watch `next` response:
     * the continuationItemRenderer inside the comments engagement panel.
     * Falls back to the first continuation token inside any engagement panel.
     */
    fun findCommentsContinuation(root: JsonElement?): String? {
        val panels = findObjects(root, "engagementPanelSectionListRenderer")
        for (panel in panels) {
            val token = findObjects(panel, "continuationItemRenderer")
                .firstOrNull()
                ?.get("continuationEndpoint")?.asObject()
                ?.get("continuationCommand")?.asObject()
                ?.get("token")?.asString()
            if (!token.isNullOrBlank()) return token
        }
        return null
    }
}
