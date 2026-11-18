package com.puretube.app

import com.puretube.app.data.remote.extractor.FeedItem
import com.puretube.app.data.remote.extractor.ShortsFilterEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ShortsFilterEngineTest {

    private fun item(id: String, url: String, isShort: Boolean = false) = FeedItem(
        videoId = id,
        title = "title-$id",
        channelName = "chan",
        channelId = "chanId",
        thumbnailUrl = "",
        durationSeconds = 300L,
        url = url,
        isShort = isShort
    )

    @Test
    fun stripsShortsItemsAndUrls() {
        val items = listOf(
            item("a", "https://www.youtube.com/watch?v=aaaaaaaaaaa"),
            item("b", "https://www.youtube.com/shorts/bbbbbbbbbbb"),
            item("c", "https://www.youtube.com/watch?v=ccccccccccc", isShort = true)
        )
        val filtered = ShortsFilterEngine.filterItems(items, stripShorts = true)
        assertEquals(listOf("a"), filtered.map { it.videoId })
    }

    @Test
    fun keepsEverythingWhenStripDisabled() {
        val items = listOf(item("b", "https://www.youtube.com/shorts/bbbbbbbbbbb"))
        assertEquals(1, ShortsFilterEngine.filterItems(items, stripShorts = false).size)
    }

    @Test
    fun blockedKeywordFilterDropsMatches() {
        val items = listOf(
            item("a", "https://www.youtube.com/watch?v=aaaaaaaaaaa").copy(title = "Calm lecture"),
            item("b", "https://www.youtube.com/watch?v=bbbbbbbbbbb").copy(title = "PRANK gone wrong")
        )
        val filtered = ShortsFilterEngine.filterByBlockedKeywords(items, listOf("prank"), emptyList())
        assertTrue(filtered.map { it.videoId } == listOf("a"))
    }
}
