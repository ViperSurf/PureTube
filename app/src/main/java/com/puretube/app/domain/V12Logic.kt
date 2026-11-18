package com.puretube.app.domain

import com.puretube.app.data.local.entity.WatchProgressEntity
import com.puretube.app.data.remote.extractor.FeedItem
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/**
 * Full, non-swallowed error text: class + message for the exception and each
 * cause in the chain. Blank messages are replaced with the class name so a
 * failure never renders as a trailing empty string (the v1.1 "Got error: "
 * bug). Pure + unit-tested.
 */
object ErrorText {
    fun describe(t: Throwable?, maxChars: Int = 600): String {
        if (t == null) return "unknown error"
        val parts = mutableListOf<String>()
        var current: Throwable? = t
        var depth = 0
        while (current != null && depth < 6) {
            val cls = current::class.simpleName ?: "Throwable"
            val msg = current.message?.trim().orEmpty()
            parts.add(if (msg.isBlank()) cls else "$cls: ${msg.take(220)}")
            current = current.cause
            depth++
        }
        return parts.joinToString(" <- caused by: ").take(maxChars)
    }
}

/** One extraction stage's outcome, kept in order for diagnostics. Pure + tested. */
data class StageResult(val stage: String, val ok: Boolean, val detail: String)

object FailoverLog {
    fun summary(stages: List<StageResult>): String =
        stages.joinToString("\n") { s ->
            "${if (s.ok) "OK" else "FAIL"} ${s.stage}: ${s.detail.take(220)}"
        }

    fun finalMessage(videoId: String, stages: List<StageResult>): String =
        "Could not load video $videoId — every extraction stage failed:\n" + summary(stages)
}

/** How the user typed a channel reference in Add Channel. Pure + tested. */
object ChannelInput {
    enum class Kind { CHANNEL_ID, HANDLE, URL, NAME }

    fun classify(raw: String): Kind {
        val input = raw.trim()
        return when {
            input.matches(Regex("^UC[A-Za-z0-9_-]{22}$")) -> Kind.CHANNEL_ID
            input.startsWith("@") && !input.contains(" ") -> Kind.HANDLE
            input.contains("youtube.com") || input.startsWith("http") -> Kind.URL
            else -> Kind.NAME
        }
    }

    /** URL for ID/handle/URL inputs; null for NAME (caller must search). */
    fun toUrl(raw: String): String? {
        val input = raw.trim()
        return when (classify(input)) {
            Kind.CHANNEL_ID -> "https://www.youtube.com/channel/$input"
            Kind.HANDLE -> "https://www.youtube.com/$input"
            Kind.URL -> input
            Kind.NAME -> null
        }
    }
}

/** Search pagination merge: appends new items, dropping duplicates by key. Pure + tested. */
object PageMerger {
    fun <T> merge(existing: List<T>, newItems: List<T>, key: (T) -> String): List<T> {
        val seen = existing.map(key).toMutableSet()
        val out = existing.toMutableList()
        for (item in newItems) {
            if (seen.add(key(item))) out.add(item)
        }
        return out
    }

    fun dedupe(items: List<FeedItem>): List<FeedItem> = merge(emptyList(), items) { it.videoId }
}

/**
 * Per-day history grouping (v1.2): "Today", "Yesterday", then one section per
 * calendar day ("Mon, Nov 16, 2026"), newest first. Pure + unit-tested.
 */
object HistoryDayGroups {
    private fun sameDay(a: Long, b: Long): Boolean {
        val ca = Calendar.getInstance().apply { timeInMillis = a }
        val cb = Calendar.getInstance().apply { timeInMillis = b }
        return ca.get(Calendar.YEAR) == cb.get(Calendar.YEAR) &&
            ca.get(Calendar.DAY_OF_YEAR) == cb.get(Calendar.DAY_OF_YEAR)
    }

    fun labelFor(epochMs: Long, nowMs: Long): String {
        if (sameDay(epochMs, nowMs)) return "Today"
        val yesterday = Calendar.getInstance().apply {
            timeInMillis = nowMs
            add(Calendar.DAY_OF_YEAR, -1)
        }.timeInMillis
        if (sameDay(epochMs, yesterday)) return "Yesterday"
        return SimpleDateFormat("EEE, MMM d, yyyy", Locale.US)
            .format(java.util.Date(epochMs))
    }

    fun group(
        items: List<WatchProgressEntity>,
        nowMs: Long
    ): List<Pair<String, List<WatchProgressEntity>>> {
        val sorted = items.sortedByDescending { it.lastUpdatedEpochMs }
        val out = mutableListOf<Pair<String, MutableList<WatchProgressEntity>>>()
        for (item in sorted) {
            val label = labelFor(item.lastUpdatedEpochMs, nowMs)
            val bucket = out.firstOrNull { it.first == label }
            if (bucket == null) out.add(label to mutableListOf(item)) else bucket.second.add(item)
        }
        return out.map { it.first to it.second.toList() }
    }
}

/** Canonical playlist URL: pulls the list id out of watch/share URLs. Pure + tested. */
object PlaylistUrl {
    private val LIST_REGEX = Regex("[?&]list=([A-Za-z0-9_-]+)")

    fun canonical(raw: String): String {
        val trimmed = raw.trim()
        val id = LIST_REGEX.find(trimmed)?.groupValues?.get(1)
        return if (id != null) "https://www.youtube.com/playlist?list=$id" else trimmed
    }

    fun listId(raw: String): String? = LIST_REGEX.find(raw.trim())?.groupValues?.get(1)
}
