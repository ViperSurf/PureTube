package com.puretube.app

import com.puretube.app.data.backup.SubscriptionParsers
import com.puretube.app.data.local.entity.WatchProgressEntity
import com.puretube.app.domain.ChannelInput
import com.puretube.app.domain.ErrorText
import com.puretube.app.domain.FailoverLog
import com.puretube.app.domain.HistoryDayGroups
import com.puretube.app.domain.PageMerger
import com.puretube.app.domain.PlaylistUrl
import com.puretube.app.domain.StageResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

private const val UC1 = "UCabc123def456ghi789jkl0" // 24 chars total
private const val UC2 = "UCxyz987wvu654tsr321qpo0"

class V12LogicTest {

    // ---------- ErrorText ----------

    @Test
    fun errorTextIncludesCauseChainAndNeverTrailsBlank() {
        val e = RuntimeException("Got error: ", IllegalStateException(""))
        val text = ErrorText.describe(e)
        assertTrue(text.contains("RuntimeException"))
        assertTrue(text.contains("Got error:"))
        assertTrue(text.contains("IllegalStateException"))
        assertFalse(text.trimEnd().endsWith(":"))
    }

    @Test
    fun errorTextForNullMessageUsesClassName() {
        val text = ErrorText.describe(RuntimeException(null as String?))
        assertEquals("RuntimeException", text)
    }

    // ---------- FailoverLog ----------

    @Test
    fun failoverSummaryPreservesStageOrder() {
        val stages = listOf(
            StageResult("NewPipe", false, "ExtractionException: sign-in required"),
            StageResult("Piped https://example.org", false, "HTTP 403"),
            StageResult("Invidious https://example.net", true, "streams found")
        )
        val summary = FailoverLog.summary(stages)
        val lines = summary.lines()
        assertEquals(3, lines.size)
        assertTrue(lines[0].startsWith("FAIL NewPipe"))
        assertTrue(lines[1].startsWith("FAIL Piped"))
        assertTrue(lines[2].startsWith("OK Invidious"))
        val final = FailoverLog.finalMessage("O_uBIWUBlN4", stages)
        assertTrue(final.contains("O_uBIWUBlN4"))
        assertTrue(final.indexOf("NewPipe") < final.indexOf("Invidious"))
    }

    // ---------- ChannelInput ----------

    @Test
    fun channelInputClassification() {
        assertEquals(ChannelInput.Kind.CHANNEL_ID, ChannelInput.classify(UC1))
        assertEquals(ChannelInput.Kind.HANDLE, ChannelInput.classify("@SSundee"))
        assertEquals(
            ChannelInput.Kind.URL,
            ChannelInput.classify("https://www.youtube.com/@SSundee")
        )
        assertEquals(ChannelInput.Kind.NAME, ChannelInput.classify("ssundee"))
        assertEquals(ChannelInput.Kind.NAME, ChannelInput.classify("Some Channel Name"))
        assertEquals("https://www.youtube.com/channel/$UC1", ChannelInput.toUrl(UC1))
        assertEquals("https://www.youtube.com/@SSundee", ChannelInput.toUrl("@SSundee"))
        assertEquals(null, ChannelInput.toUrl("ssundee"))
    }

    // ---------- PageMerger ----------

    @Test
    fun pageMergerDedupesByKeyKeepingOrder() {
        val merged = PageMerger.merge(listOf("a", "b"), listOf("b", "c", "a", "d")) { it }
        assertEquals(listOf("a", "b", "c", "d"), merged)
    }

    // ---------- PlaylistUrl ----------

    @Test
    fun playlistUrlCanonicalisesWatchAndShareUrls() {
        assertEquals(
            "https://www.youtube.com/playlist?list=PLabc123",
            PlaylistUrl.canonical("https://www.youtube.com/watch?v=dQw4w9WgXcQ&list=PLabc123")
        )
        assertEquals(
            "https://www.youtube.com/playlist?list=PLabc123",
            PlaylistUrl.canonical("https://www.youtube.com/playlist?list=PLabc123&si=xyz")
        )
        assertEquals(
            "https://example.com/custom",
            PlaylistUrl.canonical("https://example.com/custom")
        )
    }

    // ---------- HistoryDayGroups ----------

    private fun progress(videoId: String, epochMs: Long) = WatchProgressEntity(
        videoId = videoId, title = videoId, channelName = "c", channelId = "UC",
        thumbnailUrl = "", positionMs = 0, durationMs = 1000,
        isCompleted = false, lastUpdatedEpochMs = epochMs
    )

    @Test
    fun historyGroupsPerDay() {
        val now = Calendar.getInstance().apply {
            set(2026, Calendar.NOVEMBER, 18, 12, 0, 0)
        }.timeInMillis
        val dayMs = 86_400_000L
        val groups = HistoryDayGroups.group(
            listOf(
                progress("today", now - 3_600_000L),
                progress("yesterday", now - dayMs),
                progress("threeDaysAgo", now - 3 * dayMs),
                progress("alsoToday", now - 60_000L)
            ),
            now
        )
        assertEquals("Today", groups[0].first)
        assertEquals(2, groups[0].second.size)
        assertEquals("Yesterday", groups[1].first)
        assertEquals(1, groups[1].second.size)
        assertEquals(3, groups.size)
        assertFalse(groups[2].first == "Today" || groups[2].first == "Yesterday")
    }

    // ---------- Tolerant subscription parsers ----------

    @Test
    fun parsesNewPipeFixture() {
        val json = """{"app_version":"1","subscriptions":[
            {"service_id":0,"url":"https://www.youtube.com/channel/$UC1","name":"Chan One"},
            {"service_id":0,"url":"https://www.youtube.com/channel/$UC2","name":"Chan Two"}]}"""
        val report = SubscriptionParsers.parseAnyWithReport(json)
        assertEquals(2, report.imported)
        assertEquals("Chan One", report.subscriptions[0].channelName)
    }

    @Test
    fun parsesTakeoutCsvFixture() {
        val csv = "Channel Id,Channel Url,Channel Title\n" +
            "$UC1,https://www.youtube.com/channel/$UC1,Chan One\n" +
            "$UC2,https://www.youtube.com/channel/$UC2,Chan Two\n"
        val report = SubscriptionParsers.parseAnyWithReport(csv)
        assertEquals(2, report.imported)
        assertEquals("CSV", report.format)
    }

    @Test
    fun parsesGenericCsvWithDifferentHeadersAndOrder() {
        val csv = "Title,Link\n" +
            "Chan One,https://www.youtube.com/channel/$UC1\n" +
            "Chan Two,https://www.youtube.com/@SomeHandle\n" +
            "Junk Row,not-a-url\n"
        val report = SubscriptionParsers.parseAnyWithReport(csv)
        assertEquals(2, report.imported)
        assertEquals(1, report.skipped)
        assertEquals("@SomeHandle", report.subscriptions[1].channelId)
    }

    @Test
    fun parsesTakeoutJsonFixture() {
        // Google Takeout subscriptions.json shape (snippet.resourceId.channelId)
        val json = """[{"snippet":{"resourceId":{"channelId":"$UC1"},"title":"Chan One"}},
                       {"snippet":{"resourceId":{"channelId":"$UC2"},"title":"Chan Two"}}]"""
        val report = SubscriptionParsers.parseAnyWithReport(json)
        assertEquals(2, report.imported)
        assertEquals("Chan Two", report.subscriptions[1].channelName)
    }

    @Test
    fun parsesTakeoutOpmlFixture() {
        val opml = """<?xml version="1.0"?><opml><body>
            <outline text="Chan One" title="Chan One" type="rss"
              xmlUrl="https://www.youtube.com/feeds/videos.xml?channel_id=$UC1"
              htmlUrl="https://www.youtube.com/channel/$UC1"/>
            <outline text="Chan Two" title="Chan Two" type="rss"
              xmlUrl="https://www.youtube.com/feeds/videos.xml?channel_id=$UC2"/>
            </body></opml>"""
        val report = SubscriptionParsers.parseAnyWithReport(opml)
        assertEquals(2, report.imported)
        assertEquals(UC1, report.subscriptions[0].channelId)
    }

    @Test
    fun parsesSkyTubeStyleJsonFixture() {
        // SkyTube exports subscriptions as JSON objects with channel_id /
        // channel_name style keys (older builds used channelId / name).
        val json = """{"subscriptions":[
            {"channel_id":"$UC1","channel_name":"Chan One"},
            {"channelId":"$UC2","name":"Chan Two"}]}"""
        val report = SubscriptionParsers.parseAnyWithReport(json)
        assertEquals(2, report.imported)
        assertEquals("Chan One", report.subscriptions[0].channelName)
    }

    @Test
    fun parsesMorpheStyleJsonFixture() {
        // Morphe/ReVanced-style exports: nested lists of ids under varying keys.
        val json = """{"settings":{"subscribed_channels":["$UC1","$UC2"],
            "blocked":["some-video-id"]}}"""
        val report = SubscriptionParsers.parseAnyWithReport(json)
        assertEquals(2, report.imported)
    }

    @Test
    fun parsesPlainTextUrlListFixture() {
        val txt = "https://www.youtube.com/channel/$UC1\n" +
            "@SomeHandle\n" +
            "$UC2\n" +
            "this line is junk\n"
        val report = SubscriptionParsers.parseAnyWithReport(txt)
        assertEquals(3, report.imported)
        assertEquals(1, report.skipped)
    }

    @Test
    fun tolerantParserNeverFailsWholeFileOnOneBadRow() {
        val json = """{"subscriptions":[
            {"service_id":0,"url":"https://www.youtube.com/channel/$UC1","name":"Good"},
            {"unexpected":true},
            {"service_id":0,"url":"https://www.youtube.com/channel/$UC2","name":"Also Good"}]}"""
        val report = SubscriptionParsers.parseAnyWithReport(json)
        assertEquals(2, report.imported)
    }
}
