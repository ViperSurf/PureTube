package com.puretube.app.data.remote.extractor

/** A feed/search item as the UI consumes it. */
data class FeedItem(
    val videoId: String,
    val title: String,
    val channelName: String,
    val channelId: String,
    val thumbnailUrl: String,
    val durationSeconds: Long,
    val url: String,
    val isShort: Boolean = false
)

/**
 * Zero Shorts engine: strips Shorts shelves/items from feeds and search, and
 * converts any /shorts/ link into the regular watch player. Pure and testable.
 */
object ShortsFilterEngine {

    fun isShortsUrl(url: String): Boolean = url.contains("/shorts/")

    fun isShortItem(item: FeedItem): Boolean =
        item.isShort || isShortsUrl(item.url) || (item.durationSeconds in 1..60 && isShortsUrl(item.url))

    fun filterItems(items: List<FeedItem>, stripShorts: Boolean): List<FeedItem> =
        if (!stripShorts) items else items.filterNot { isShortItem(it) || isShortsUrl(it.url) }

    fun filterByBlockedKeywords(
        items: List<FeedItem>,
        blockedKeywords: List<String>,
        blockedChannelIds: List<String>
    ): List<FeedItem> {
        val keywords = blockedKeywords.map { it.lowercase() }.filter { it.isNotBlank() }
        return items.filter { item ->
            val title = item.title.lowercase()
            keywords.none { title.contains(it) } && item.channelId !in blockedChannelIds
        }
    }
}
