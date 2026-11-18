package com.puretube.app

import com.puretube.app.data.backup.PlaylistImportParsers
import com.puretube.app.data.local.datastore.AppPreferencesState
import com.puretube.app.data.local.datastore.FeedLayout
import com.puretube.app.data.local.entity.WatchProgressEntity
import com.puretube.app.data.remote.extractor.FeedItem
import com.puretube.app.domain.CommentFilters
import com.puretube.app.domain.CommentItem
import com.puretube.app.domain.HistoryBuckets
import com.puretube.app.domain.LikeFilters
import com.puretube.app.domain.SettingsJson
import com.puretube.app.domain.SponsorRewindSuppression
import com.puretube.app.playback.QueueLogic
import com.puretube.app.playback.SleepTimerMapping
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

class V11LogicTest {

    private fun item(id: String, durationSeconds: Long = 300, likeCount: Long = -1) = FeedItem(
        videoId = id, title = "t-$id", channelName = "chan", channelId = "cid",
        thumbnailUrl = "", durationSeconds = durationSeconds,
        url = "https://www.youtube.com/watch?v=$id", likeCount = likeCount
    )

    private fun progress(id: String, updatedMs: Long) = WatchProgressEntity(
        videoId = id, title = "t", channelName = "c", channelId = "cid",
        thumbnailUrl = "", positionMs = 0, durationMs = 1000,
        isCompleted = false, lastUpdatedEpochMs = updatedMs
    )

    private fun nowCalendar(): Calendar = Calendar.getInstance().apply {
        set(2026, Calendar.NOVEMBER, 18, 12, 0, 0)
        set(Calendar.MILLISECOND, 0)
    }

    // ---- Queue duration filter ----

    @Test
    fun autoQueueRespectsMaxMinutes() {
        val items = listOf(item("a", 300), item("b", 900), item("c", 360))
        val filtered = QueueLogic.filterAutoQueueCandidates(items, shortOnly = false, maxMinutes = 6)
        assertEquals(listOf("a", "c"), filtered.map { it.videoId })
    }

    @Test
    fun autoQueueShortOnlyDropsLongItems() {
        val items = listOf(item("a", 60), item("b", 300), item("c", 45))
        val filtered = QueueLogic.filterAutoQueueCandidates(items, shortOnly = true, maxMinutes = 60)
        assertEquals(listOf("a", "c"), filtered.map { it.videoId })
    }

    // ---- History buckets ----

    @Test
    fun historyBucketsGroupInOrder() {
        val now = nowCalendar().timeInMillis
        val day = 86_400_000L
        val items = listOf(
            progress("today", now - 3_600_000L),
            progress("yesterday", now - day),
            progress("week", now - 3 * day),
            progress("month", now - 10 * day),
            progress("older", now - 90 * day)
        )
        val groups = HistoryBuckets.group(items, now)
        assertEquals(
            listOf("Today", "Yesterday", "This week", "This month", "Older"),
            groups.map { it.first }
        )
        assertEquals("today", groups[0].second.first().videoId)
        // 10 days ago (Nov 8) is the same month but outside the 7-day window.
        val monthGroup = groups.firstOrNull { it.first == "This month" }
        assertNotNull(monthGroup)
        assertEquals("month", monthGroup!!.second.first().videoId)
    }

    @Test
    fun historyVisibilityHidesOlderWithoutDeleting() {
        val now = nowCalendar().timeInMillis
        val items = listOf(progress("new", now), progress("old", now - 40 * 86_400_000L))
        assertEquals(1, HistoryBuckets.visibleItems(items, now, visibleDays = 30).size)
        assertEquals(2, HistoryBuckets.visibleItems(items, now, visibleDays = 0).size)
    }

    // ---- Playlist import parsers ----

    @Test
    fun playlistTxtParserReadsUrlsAndIds() {
        val text = """
            # my list
            https://www.youtube.com/watch?v=aaaaaaaaaaa
            bbbbbbbbbbb

            https://youtu.be/ccccccccccc
        """.trimIndent()
        val parsed = PlaylistImportParsers.parseTxt(text)
        assertEquals(listOf("aaaaaaaaaaa", "bbbbbbbbbbb", "ccccccccccc"), parsed.map { it.videoId })
    }

    @Test
    fun playlistCsvParserReadsUrlAndTitle() {
        val text = "https://www.youtube.com/watch?v=aaaaaaaaaaa,First video\nbbbbbbbbbbb,Second"
        val parsed = PlaylistImportParsers.parseCsv(text)
        assertEquals(2, parsed.size)
        assertEquals("First video", parsed[0].title)
        assertEquals("bbbbbbbbbbb", parsed[1].videoId)
    }

    @Test
    fun playlistJsonParserReadsArrayAndObjectForms() {
        val arrayForm = """["https://www.youtube.com/watch?v=aaaaaaaaaaa", {"videoId":"bbbbbbbbbbb","title":"Bee"}]"""
        val parsed = PlaylistImportParsers.parseJson(arrayForm)
        assertEquals(listOf("aaaaaaaaaaa", "bbbbbbbbbbb"), parsed.map { it.videoId })
        val objectForm = """{"videos":[{"url":"https://youtu.be/ccccccccccc","title":"Cee"}]}"""
        val parsedObj = PlaylistImportParsers.parseJson(objectForm)
        assertEquals(listOf("ccccccccccc"), parsedObj.map { it.videoId })
        assertEquals("Cee", parsedObj[0].title)
    }

    // ---- Settings JSON round-trip ----

    @Test
    fun settingsJsonRoundTrips() {
        val state = AppPreferencesState(
            autoplayEnabled = false,
            feedLayout = FeedLayout.GRID,
            contentMinLikes = 500,
            commentMaxLikes = null,
            sleepTimerDefaultMinutes = 30,
            autoQueueMaxMinutes = 10
        )
        val json = SettingsJson.export(state)
        val back = SettingsJson.import(json)
        assertNotNull(back)
        assertEquals(state, back)
        assertEquals(null, SettingsJson.import("not json"))
    }

    // ---- Like filters ----

    @Test
    fun contentLikeFilterPassesUnknownAndAppliesRange() {
        val items = listOf(
            item("unknown", likeCount = -1),
            item("low", likeCount = 5),
            item("mid", likeCount = 500),
            item("high", likeCount = 50_000)
        )
        val filtered = LikeFilters.filterContent(items, minLikes = 100, maxLikes = 10_000)
        assertEquals(listOf("unknown", "mid"), filtered.map { it.videoId })
    }

    @Test
    fun commentFiltersApplyBlocksAndLikes() {
        val comments = listOf(
            CommentItem("1", "Alice", "chanA", "Great video", 50),
            CommentItem("2", "Bob", "chanB", "spammy keyword here", 10),
            CommentItem("3", "Carol", "chanC", "Nice", 3),
            CommentItem("4", "Dave", "chanBlocked", "Hello", 99)
        )
        val filtered = CommentFilters.filter(
            comments,
            blockedKeywords = listOf("keyword"),
            blockedUsernames = listOf("carol"),
            blockedChannelIds = listOf("chanBlocked"),
            minLikes = null, maxLikes = null
        )
        assertEquals(listOf("1"), filtered.map { it.id })
        assertTrue(LikeFilters.passesComment(10, minLikes = 5, maxLikes = 20))
        assertFalse(LikeFilters.passesComment(25, minLikes = 5, maxLikes = 20))
        assertTrue(LikeFilters.passesComment(-1, minLikes = 5, maxLikes = 20))
    }

    // ---- Sleep timer mapping ----

    @Test
    fun sleepTimerMappingCoercesDefaults() {
        assertEquals(15, SleepTimerMapping.coerceDefault(15))
        assertEquals(15, SleepTimerMapping.coerceDefault(7))
        assertEquals("Off", SleepTimerMapping.labelFor(0))
        assertEquals("30 min", SleepTimerMapping.labelFor(30))
        assertEquals(listOf(0, 5, 10, 15, 30, 45, 60), SleepTimerMapping.optionsMinutes)
    }

    // ---- SponsorBlock rewind suppression ----

    @Test
    fun rewindIntoSkippedSegmentSuppressesReskip() {
        val segments = listOf("u1" to (10_000L..40_000L), "u2" to (100_000L..120_000L))
        val suppressed = SponsorRewindSuppression.onManualSeek(
            skippedUuids = setOf("u1"),
            segments = segments,
            seekFromMs = 50_000L,
            seekToMs = 20_000L,
            pauseSkipOnRewind = true
        )
        assertEquals(setOf("u1"), suppressed)
        assertTrue(SponsorRewindSuppression.shouldSuppress("u1", suppressed, true))
        assertFalse(SponsorRewindSuppression.shouldSuppress("u1", suppressed, false))
        // Forward seeks never suppress.
        val forward = SponsorRewindSuppression.onManualSeek(
            skippedUuids = setOf("u1"), segments = segments,
            seekFromMs = 5_000L, seekToMs = 20_000L, pauseSkipOnRewind = true
        )
        assertTrue(forward.isEmpty())
        // Preference off: nothing is ever suppressed.
        val off = SponsorRewindSuppression.onManualSeek(
            skippedUuids = setOf("u1"), segments = segments,
            seekFromMs = 50_000L, seekToMs = 20_000L, pauseSkipOnRewind = false
        )
        assertTrue(off.isEmpty())
    }
}
