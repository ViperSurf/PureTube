package com.puretube.app.domain

import com.puretube.app.data.local.datastore.AppPreferencesState
import com.puretube.app.data.local.entity.WatchProgressEntity
import com.puretube.app.data.remote.extractor.FeedItem
import kotlinx.serialization.json.Json
import java.util.Calendar

/** History section buckets for the Library screen. Pure + unit-tested. */
object HistoryBuckets {
    val labels = listOf("Today", "Yesterday", "This week", "This month", "Older")

    fun bucketOf(epochMs: Long, nowMs: Long): String {
        val then = Calendar.getInstance().apply { timeInMillis = epochMs }
        val now = Calendar.getInstance().apply { timeInMillis = nowMs }
        fun sameDay(a: Calendar, b: Calendar) =
            a.get(Calendar.YEAR) == b.get(Calendar.YEAR) &&
                a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR)
        if (sameDay(then, now)) return "Today"
        val yesterday = (now.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, -1) }
        if (sameDay(then, yesterday)) return "Yesterday"
        val weekAgo = (now.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, -7) }
        if (then.after(weekAgo)) return "This week"
        if (then.get(Calendar.YEAR) == now.get(Calendar.YEAR) &&
            then.get(Calendar.MONTH) == now.get(Calendar.MONTH)
        ) return "This month"
        return "Older"
    }

    fun group(items: List<WatchProgressEntity>, nowMs: Long): List<Pair<String, List<WatchProgressEntity>>> {
        val grouped = items.groupBy { bucketOf(it.lastUpdatedEpochMs, nowMs) }
        return labels.mapNotNull { label -> grouped[label]?.let { label to it } }
    }

    /** Items older than the visibility window are hidden (never deleted). */
    fun visibleItems(
        items: List<WatchProgressEntity>,
        nowMs: Long,
        visibleDays: Int
    ): List<WatchProgressEntity> {
        if (visibleDays <= 0) return items
        val cutoff = nowMs - visibleDays * 86_400_000L
        return items.filter { it.lastUpdatedEpochMs >= cutoff }
    }
}

/** Min/max like-count filters. Unknown counts (-1) always pass. Pure + tested. */
object LikeFilters {
    fun passesContent(item: FeedItem, minLikes: Int?, maxLikes: Int?): Boolean {
        if (item.likeCount < 0) return true
        if (minLikes != null && item.likeCount < minLikes) return false
        if (maxLikes != null && item.likeCount > maxLikes) return false
        return true
    }

    fun filterContent(items: List<FeedItem>, minLikes: Int?, maxLikes: Int?): List<FeedItem> =
        items.filter { passesContent(it, minLikes, maxLikes) }

    fun passesComment(likeCount: Int, minLikes: Int?, maxLikes: Int?): Boolean {
        if (likeCount < 0) return true
        if (minLikes != null && likeCount < minLikes) return false
        if (maxLikes != null && likeCount > maxLikes) return false
        return true
    }
}

/** A comment as the UI consumes it. */
data class CommentItem(
    val id: String,
    val authorName: String,
    val authorChannelId: String,
    val text: String,
    val likeCount: Int,
    val avatarUrl: String = ""
)

/** Comment blocklist + like filters. Pure + unit-tested. */
object CommentFilters {
    fun filter(
        comments: List<CommentItem>,
        blockedKeywords: List<String>,
        blockedUsernames: List<String>,
        blockedChannelIds: List<String>,
        minLikes: Int?,
        maxLikes: Int?
    ): List<CommentItem> {
        val keywords = blockedKeywords.map { it.lowercase() }.filter { it.isNotBlank() }
        val usernames = blockedUsernames.map { it.lowercase() }.filter { it.isNotBlank() }
        return comments.filter { c ->
            val text = c.text.lowercase()
            val author = c.authorName.lowercase()
            keywords.none { text.contains(it) } &&
                usernames.none { author.contains(it) } &&
                c.authorChannelId !in blockedChannelIds &&
                LikeFilters.passesComment(c.likeCount, minLikes, maxLikes)
        }
    }
}

/**
 * SponsorBlock rewind suppression: once a segment has been auto-skipped for a
 * video, a manual seek back before/into it marks it do-not-skip-again for the
 * rest of the session (when the preference is on). Pure + unit-tested.
 */
object SponsorRewindSuppression {
    fun shouldSuppress(
        segmentUuid: String,
        suppressedUuids: Set<String>,
        pauseSkipOnRewind: Boolean
    ): Boolean = pauseSkipOnRewind && segmentUuid in suppressedUuids

    /** Returns the updated suppression set after a manual seek. */
    fun onManualSeek(
        skippedUuids: Set<String>,
        segments: List<Pair<String, LongRange>>,
        seekFromMs: Long,
        seekToMs: Long,
        pauseSkipOnRewind: Boolean
    ): Set<String> {
        if (!pauseSkipOnRewind) return emptySet()
        if (seekToMs >= seekFromMs) return emptySet() // only rewinds count
        val hit = segments.filter { (uuid, range) ->
            uuid in skippedUuids && seekToMs < range.last && seekToMs <= range.first + 1_000L
        }.map { it.first }
        return hit.toSet()
    }
}

/** Settings JSON export/import (kotlinx.serialization round-trip). Pure + tested. */
object SettingsJson {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = true
    }

    fun export(state: AppPreferencesState): String = json.encodeToString(AppPreferencesState.serializer(), state)

    fun import(text: String): AppPreferencesState? =
        runCatching { json.decodeFromString(AppPreferencesState.serializer(), text) }.getOrNull()
}
