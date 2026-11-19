package com.puretube.app.domain

import com.puretube.app.data.local.datastore.ControlsVisibility
import com.puretube.app.data.local.datastore.LikesDisplay
import kotlin.math.roundToInt

/**
 * v1.4 pure logic: likes percentage display, description formatting
 * (links + timestamps), HaramBlur channel exemptions, player
 * controls-visibility, search double-tap reset, and swipe-control math.
 * All JVM-testable, no Android types.
 */

// ---------------------------------------------------------------------------
// Likes display
// ---------------------------------------------------------------------------

object LikesDisplayMath {
    /**
     * Like percentage (0..100, rounded), or null when it cannot be known:
     * either count unknown/negative, or the total is zero.
     */
    fun likePercent(likes: Long, dislikes: Long): Int? {
        if (likes < 0 || dislikes < 0) return null
        val total = likes + dislikes
        if (total <= 0L) return null
        return (likes.toDouble() * 100.0 / total.toDouble()).roundToInt().coerceIn(0, 100)
    }

    /** The player dislike line for the chosen display mode. */
    fun format(likes: Long, dislikes: Long, mode: LikesDisplay): String {
        val percent = likePercent(likes, dislikes)
        return when (mode) {
            LikesDisplay.NUMBERS -> "Likes $likes - Dislikes $dislikes"
            LikesDisplay.PERCENTAGE ->
                if (percent != null) "$percent% liked ($likes likes)"
                else "Likes $likes - Dislikes $dislikes"
            LikesDisplay.BOTH ->
                if (percent != null) "Likes $likes - Dislikes $dislikes ($percent% liked)"
                else "Likes $likes - Dislikes $dislikes"
        }
    }
}

// ---------------------------------------------------------------------------
// Description formatting
// ---------------------------------------------------------------------------

/** One piece of a formatted description: plain text, a web link, or a timestamp. */
data class DescSegment(
    val text: String,
    val url: String? = null,
    val timestampMs: Long? = null
) {
    val isLink: Boolean get() = url != null
    val isTimestamp: Boolean get() = timestampMs != null
}

object DescriptionFormatter {

    private val anchorRegex = Regex(
        "<a\\s+[^>]*href\\s*=\\s*\"([^\"]*)\"[^>]*>(.*?)</a>",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
    )
    private val anchorSingleQuoteRegex = Regex(
        "<a\\s+[^>]*href\\s*=\\s*'([^']*)'[^>]*>(.*?)</a>",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
    )
    private val tagRegex = Regex("<[^>]+>")
    // H:MM:SS or M:SS at a word boundary (not glued to digits/colons on either side).
    private val timestampRegex = Regex(
        "(?<![0-9:])(?:(\\d{1,2}):)?([0-5]?\\d):([0-5]\\d)(?![0-9:])"
    )

    /** Decodes the named entities PureTube promises plus numeric &#NNN; / &#xHH;. */
    fun decodeEntities(text: String): String {
        var out = text
            .replace("&nbsp;", " ")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&apos;", "'")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
        // Numeric entities (decimal + hex) before &amp; so &#38; survives too.
        out = Regex("&#(\\d+);").replace(out) { m ->
            m.groupValues[1].toIntOrNull()?.takeIf { it in 0..0x10FFFF }
                ?.let { Character.toChars(it).concatToString() } ?: m.value
        }
        out = Regex("&#[xX]([0-9a-fA-F]+);").replace(out) { m ->
            m.groupValues[1].toIntOrNull(16)?.takeIf { it in 0..0x10FFFF }
                ?.let { Character.toChars(it).concatToString() } ?: m.value
        }
        return out.replace("&amp;", "&")
    }

    /** Timestamp token -> milliseconds, or null when [token] is not a timestamp. */
    fun timestampMs(token: String): Long? {
        val m = timestampRegex.matchEntire(token.trim()) ?: return null
        val hours = m.groupValues[1].toLongOrNull() ?: 0L
        val minutes = m.groupValues[2].toLongOrNull() ?: return null
        val seconds = m.groupValues[3].toLongOrNull() ?: return null
        return ((hours * 60 + minutes) * 60 + seconds) * 1000L
    }

    /**
     * Parses an HTML-ish description into segments:
     * <br> and </p> become newlines, <a href> becomes a link segment
     * (a link whose text is a timestamp becomes a timestamp segment),
     * every other tag is stripped, entities are decoded, runs of 3+
     * newlines collapse to one blank line, and timestamps in plain text
     * become seek segments.
     */
    fun parse(raw: String): List<DescSegment> {
        if (raw.isBlank()) return emptyList()
        var text = raw
            .replace(Regex("<br\\s*/?>", RegexOption.IGNORE_CASE), "\n")
            .replace(Regex("</p\\s*>", RegexOption.IGNORE_CASE), "\n")
            .replace(Regex("<p\\s*[^>]*>", RegexOption.IGNORE_CASE), "")

        val segments = mutableListOf<DescSegment>()
        fun addPlain(chunk: String) {
            val cleaned = decodeEntities(tagRegex.replace(chunk, ""))
            if (cleaned.isEmpty()) return
            // Split out timestamps.
            var last = 0
            for (m in timestampRegex.findAll(cleaned)) {
                if (m.range.first > last) {
                    segments.add(DescSegment(cleaned.substring(last, m.range.first)))
                }
                val ms = timestampMs(m.value)
                if (ms != null) segments.add(DescSegment(m.value, timestampMs = ms))
                else segments.add(DescSegment(m.value))
                last = m.range.last + 1
            }
            if (last < cleaned.length) segments.add(DescSegment(cleaned.substring(last)))
        }

        var cursor = 0
        val anchors = (anchorRegex.findAll(text).toList() + anchorSingleQuoteRegex.findAll(text).toList())
            .sortedBy { it.range.first }
        for (anchor in anchors) {
            if (anchor.range.first < cursor) continue // overlapping duplicate from the other regex
            if (anchor.range.first > cursor) addPlain(text.substring(cursor, anchor.range.first))
            val url = decodeEntities(anchor.groupValues[1])
            val label = decodeEntities(tagRegex.replace(anchor.groupValues[2], ""))
            val ts = timestampMs(label)
            when {
                ts != null -> segments.add(DescSegment(label, timestampMs = ts))
                label.isNotBlank() -> segments.add(DescSegment(label, url = url))
            }
            cursor = anchor.range.last + 1
        }
        if (cursor < text.length) addPlain(text.substring(cursor))

        // Collapse 3+ newlines to 2 across the joined text of plain segments:
        // apply per plain segment, then merge adjacent plain segments.
        val merged = mutableListOf<DescSegment>()
        for (seg in segments) {
            val cleaned = if (!seg.isLink && !seg.isTimestamp) {
                seg.copy(text = seg.text.replace(Regex("\n{3,}"), "\n\n"))
            } else seg
            val prev = merged.lastOrNull()
            if (prev != null && !prev.isLink && !prev.isTimestamp &&
                !cleaned.isLink && !cleaned.isTimestamp
            ) {
                merged[merged.lastIndex] = prev.copy(text = prev.text + cleaned.text)
            } else {
                merged.add(cleaned)
            }
        }
        // A second collapse pass after merging (boundaries can create runs).
        return merged.map { seg ->
            if (!seg.isLink && !seg.isTimestamp) {
                seg.copy(text = seg.text.replace(Regex("\n{3,}"), "\n\n"))
            } else seg
        }.filter { it.text.isNotEmpty() || it.isLink }
    }

    /** Plain-text rendering (tests / clipboard): segments joined verbatim. */
    fun toPlainText(raw: String): String = parse(raw).joinToString("") { it.text }
}

// ---------------------------------------------------------------------------
// HaramBlur channel exemptions
// ---------------------------------------------------------------------------

object HaramBlurExemptions {
    /** Normalises a channel id or channel URL to the bare UC… id when possible. */
    fun normalizeChannelId(raw: String?): String =
        raw?.trim()?.substringAfterLast("/").orEmpty()

    /**
     * True when this channel should be blurred. A channel is exempt when its
     * id (or, as a fallback, its name) appears in [exemptKeys]; exempt keys
     * are stored normalised (bare ids) but names are compared as-is,
     * case-insensitively.
     */
    fun shouldBlurForChannel(
        exemptKeys: Set<String>,
        channelId: String?,
        channelName: String? = null
    ): Boolean {
        if (exemptKeys.isEmpty()) return true
        val id = normalizeChannelId(channelId)
        if (id.isNotBlank() && exemptKeys.any { normalizeChannelId(it) == id }) return false
        val name = channelName?.trim().orEmpty()
        if (name.isNotBlank() && exemptKeys.any { it.equals(name, ignoreCase = true) }) return false
        return true
    }
}

// ---------------------------------------------------------------------------
// Player controls visibility + search reset + swipe controls
// ---------------------------------------------------------------------------

object PlayerControlsPolicy {
    fun shouldAutoHide(mode: ControlsVisibility, isPlaying: Boolean): Boolean =
        mode == ControlsVisibility.AUTO_HIDE && isPlaying
}

object SearchResetPolicy {
    /** Double-tapping the Search tab while already on Search resets the session. */
    fun shouldReset(currentRoute: String?, prefEnabled: Boolean): Boolean =
        prefEnabled && currentRoute == "search"
}

object SwipeControlMath {
    /**
     * New fraction after a vertical drag. Dragging UP (negative [dragDeltaPx])
     * increases the value; one full surface height sweeps 0..1.
     */
    fun applyDrag(currentFraction: Float, dragDeltaPx: Float, heightPx: Float): Float {
        if (heightPx <= 0f) return currentFraction.coerceIn(0f, 1f)
        return (currentFraction - dragDeltaPx / heightPx).coerceIn(0f, 1f)
    }

    fun fractionToVolume(fraction: Float, maxVolume: Int): Int =
        (fraction.coerceIn(0f, 1f) * maxVolume).roundToInt().coerceIn(0, maxVolume)

    /** The ~40%-of-max stream volume used when unmuting a silent device. */
    fun unmuteVolume(maxVolume: Int): Int =
        (maxVolume * 0.4f).roundToInt().coerceAtLeast(if (maxVolume > 0) 1 else 0)
}
