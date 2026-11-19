package com.puretube.app

import com.puretube.app.data.local.datastore.VideoAspectMode
import com.puretube.app.data.remote.extractor.FeedItem
import com.puretube.app.domain.CookieHeader
import com.puretube.app.domain.InnertubeParse
import com.puretube.app.domain.RelatedDedupe
import com.puretube.app.domain.SapisidHash
import com.puretube.app.domain.VideoRectMath
import com.puretube.app.domain.VolumeZeroRule
import com.puretube.app.haramblur.engine.NormalizedRect
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class V20LogicTest {

    // ---- SAPISIDHASH (known vectors, computed independently with sha1sum) ----

    @Test
    fun sapisidHashMatchesKnownVector() {
        // printf '1700000000 test_sapisid https://www.youtube.com' | sha1sum
        assertEquals(
            "SAPISIDHASH 1700000000_34562bc6921a7e4218ebf0fdb6bd88a734cd8ae8",
            SapisidHash.authorizationHeader("test_sapisid", 1700000000L)
        )
        // printf '1234567890 abcdefghijklmnop https://www.youtube.com' | sha1sum
        assertEquals(
            "SAPISIDHASH 1234567890_b7f491475126bf0f040a062526433ed609493bd0",
            SapisidHash.authorizationHeader("abcdefghijklmnop", 1234567890L)
        )
    }

    // ---- Cookie parsing ----

    @Test
    fun cookieParseExtractsSapisid() {
        val header = "VISITOR_INFO1_LIVE=abc; SAPISID=xyz123; SSID=zzz; PREF=tz=UTC"
        assertEquals("xyz123", CookieHeader.sapisid(header))
        assertTrue(CookieHeader.isUsable(header))
    }

    @Test
    fun cookieParseRejectsGarbage() {
        assertNull(CookieHeader.sapisid(""))
        assertNull(CookieHeader.sapisid("not a cookie at all"))
        assertNull(CookieHeader.sapisid("SAPISID="))
        assertNull(CookieHeader.sapisid("SAPISID=   ; OTHER=1"))
        assertFalse(CookieHeader.isUsable("HSID=only; SSID=other"))
    }

    @Test
    fun cookieParseHandlesEqualsInsideValues() {
        val header = "PREF=f1=50000000&tz=UTC; SAPISID=ab==cd"
        assertEquals("ab==cd", CookieHeader.sapisid(header))
        assertEquals("f1=50000000&tz=UTC", CookieHeader.parse(header)["PREF"])
    }

    // ---- Aspect-mode rect mapping ----

    @Test
    fun fitLetterboxesWideVideoInTallContainer() {
        // 16:9 video in a 1080x1200 container: full width, centred vertically.
        val rect = VideoRectMath.displayedVideoRect(1080f, 1200f, 1920, 1080, VideoAspectMode.FIT)
        assertEquals(0f, rect.left, 0.5f)
        assertEquals(1080f, rect.width, 0.5f)
        assertEquals(607.5f, rect.height, 0.5f)
        assertEquals((1200f - 607.5f) / 2f, rect.top, 0.5f)
    }

    @Test
    fun fitPillarboxesTallVideoInWideContainer() {
        // 9:16 vertical video in a 1920x1080 container.
        val rect = VideoRectMath.displayedVideoRect(1920f, 1080f, 1080, 1920, VideoAspectMode.FIT)
        assertEquals(1080f, rect.height, 0.5f)
        assertEquals(607.5f, rect.width, 0.5f)
        assertEquals((1920f - 607.5f) / 2f, rect.left, 0.5f)
        assertEquals(0f, rect.top, 0.5f)
    }

    @Test
    fun zoomCoversContainerAndExtendsPastIt() {
        // 16:9 video zoomed in a tall container: height fills, width overflows.
        val rect = VideoRectMath.displayedVideoRect(1080f, 1200f, 1920, 1080, VideoAspectMode.ZOOM)
        assertEquals(1200f, rect.height, 0.5f)
        assertTrue(rect.width > 1080f)
        assertTrue(rect.left < 0f)
        assertEquals(0f, rect.top, 0.5f)
    }

    @Test
    fun stretchIsExactlyTheContainer() {
        val rect = VideoRectMath.displayedVideoRect(800f, 600f, 1920, 1080, VideoAspectMode.STRETCH)
        assertEquals(0f, rect.left, 0.001f)
        assertEquals(0f, rect.top, 0.001f)
        assertEquals(800f, rect.width, 0.001f)
        assertEquals(600f, rect.height, 0.001f)
    }

    @Test
    fun unknownVideoSizeFallsBackToContainer() {
        val rect = VideoRectMath.displayedVideoRect(800f, 600f, 0, 0, VideoAspectMode.FIT)
        assertEquals(800f, rect.width, 0.001f)
        assertEquals(600f, rect.height, 0.001f)
    }

    @Test
    fun blurRegionMapsThroughDisplayedRect() {
        // FIT: 16:9 video in 1080x1200 container -> rect (0, 296.25, 1080, 607.5).
        val videoRect = VideoRectMath.displayedVideoRect(1080f, 1200f, 1920, 1080, VideoAspectMode.FIT)
        val centre = NormalizedRect(0.25f, 0.25f, 0.75f, 0.75f)
        val mapped = VideoRectMath.mapRegion(centre, videoRect)
        assertEquals(270f, mapped.left, 0.5f)
        assertEquals(296.25f + 151.875f, mapped.top, 0.5f)
        assertEquals(540f, mapped.width, 0.5f)
        assertEquals(303.75f, mapped.height, 0.5f)
        // A region at the video's top edge must NOT map to the container's
        // top edge under FIT (the letterbox bar stays unblurred).
        val topEdge = VideoRectMath.mapRegion(NormalizedRect(0f, 0f, 1f, 0.1f), videoRect)
        assertEquals(296.25f, topEdge.top, 0.5f)
    }

    @Test
    fun blurRegionMapsThroughZoomRectBeyondContainer() {
        val videoRect = VideoRectMath.displayedVideoRect(1080f, 1200f, 1920, 1080, VideoAspectMode.ZOOM)
        val full = VideoRectMath.mapRegion(NormalizedRect(0f, 0f, 1f, 1f), videoRect)
        assertEquals(videoRect.left, full.left, 0.001f)
        assertEquals(videoRect.width, full.width, 0.001f)
        assertTrue(full.left < 0f) // cropped part lives off-container, clipped by the overlay bounds
    }

    // ---- Volume-zero state machine ----

    private fun sample(
        state: VolumeZeroRule.State,
        zero: Boolean,
        playing: Boolean = true,
        hasMedia: Boolean = true,
        enabled: Boolean = true,
        delayMs: Long = 30_000L,
        now: Long
    ) = VolumeZeroRule.onSample(state, zero, playing, hasMedia, enabled, delayMs, now)

    @Test
    fun volumeZeroWhilePlayingPauses() {
        val (state, action) = sample(VolumeZeroRule.State(), zero = true, now = 1000L)
        assertEquals(VolumeZeroRule.Action.PAUSE, action)
        assertEquals(VolumeZeroRule.Phase.ZERO_PENDING_STOP, state.phase)
        assertEquals(1000L, state.zeroSinceMs)
    }

    @Test
    fun stopFiresAfterDelayAtZero() {
        val (s1, _) = sample(VolumeZeroRule.State(), zero = true, now = 0L)
        val (s2, a2) = sample(s1, zero = true, playing = false, now = 29_999L)
        assertEquals(VolumeZeroRule.Action.NONE, a2)
        assertEquals(VolumeZeroRule.Phase.ZERO_PENDING_STOP, s2.phase)
        val (s3, a3) = sample(s2, zero = true, playing = false, now = 30_000L)
        assertEquals(VolumeZeroRule.Action.STOP, a3)
        assertEquals(VolumeZeroRule.Phase.STOPPED, s3.phase)
        // Terminal: further samples do nothing.
        val (_, a4) = sample(s3, zero = true, playing = false, now = 99_000L)
        assertEquals(VolumeZeroRule.Action.NONE, a4)
    }

    @Test
    fun volumeRiseCancelsTimerAndNeverResumes() {
        val (s1, a1) = sample(VolumeZeroRule.State(), zero = true, now = 0L)
        assertEquals(VolumeZeroRule.Action.PAUSE, a1)
        // Volume back up at t=10s: no action at all — crucially no RESUME exists.
        val (s2, a2) = sample(s1, zero = false, playing = false, now = 10_000L)
        assertEquals(VolumeZeroRule.Action.NONE, a2)
        assertEquals(VolumeZeroRule.Phase.INACTIVE, s2.phase)
        // Zero again much later restarts the full delay from scratch.
        val (s3, a3) = sample(s2, zero = true, playing = false, now = 100_000L)
        assertEquals(VolumeZeroRule.Action.NONE, a3) // not playing -> nothing to pause
        val (_, a4) = sample(s3, zero = true, playing = false, now = 129_999L)
        assertEquals(VolumeZeroRule.Action.NONE, a4)
        val (_, a5) = sample(s3, zero = true, playing = false, now = 130_000L)
        assertEquals(VolumeZeroRule.Action.STOP, a5)
    }

    @Test
    fun neverStopDelayPausesOnly() {
        val (s1, a1) = sample(
            VolumeZeroRule.State(), zero = true, delayMs = VolumeZeroRule.NEVER_STOP_MS, now = 0L
        )
        assertEquals(VolumeZeroRule.Action.PAUSE, a1)
        val (_, a2) = sample(
            s1, zero = true, playing = false, delayMs = VolumeZeroRule.NEVER_STOP_MS, now = 86_400_000L
        )
        assertEquals(VolumeZeroRule.Action.NONE, a2)
    }

    @Test
    fun disabledOrNoMediaDoesNothing() {
        val (_, a1) = sample(VolumeZeroRule.State(), zero = true, enabled = false, now = 0L)
        assertEquals(VolumeZeroRule.Action.NONE, a1)
        val (_, a2) = sample(VolumeZeroRule.State(), zero = true, hasMedia = false, now = 0L)
        assertEquals(VolumeZeroRule.Action.NONE, a2)
    }

    // ---- Related dedupe ----

    private fun item(id: String) = FeedItem(
        videoId = id, title = "t$id", channelName = "c", channelId = "UC",
        thumbnailUrl = "", durationSeconds = 60, url = "https://www.youtube.com/watch?v=$id"
    )

    @Test
    fun relatedDedupeDropsCurrentQueuedAndDuplicates() {
        val items = listOf(item("a"), item("b"), item("a"), item("c"), item("d"))
        val out = RelatedDedupe.dedupe(items, currentVideoId = "b", excludeIds = setOf("d"))
        assertEquals(listOf("a", "c"), out.map { it.videoId })
    }

    // ---- Innertube parsing ----

    private fun parse(text: String) = Json.parseToJsonElement(text)

    @Test
    fun parseVideosReadsVideoRenderers() {
        val root = parse(
            """
            {"contents":{"richGridRenderer":{"contents":[
              {"richItemRenderer":{"content":{"videoRenderer":{
                "videoId":"abc12345678",
                "title":{"runs":[{"text":"Hello World"}]},
                "longBylineText":{"runs":[{"text":"Some Channel","navigationEndpoint":{"browseEndpoint":{"browseId":"UCxyz"}}}]},
                "thumbnail":{"thumbnails":[{"url":"small"},{"url":"large"}]},
                "lengthText":{"simpleText":"12:34"}
              }}}}
            ]}}}
            """.trimIndent()
        )
        val videos = InnertubeParse.parseVideos(root)
        assertEquals(1, videos.size)
        val v = videos.single()
        assertEquals("abc12345678", v.videoId)
        assertEquals("Hello World", v.title)
        assertEquals("Some Channel", v.channelName)
        assertEquals("UCxyz", v.channelId)
        assertEquals("large", v.thumbnailUrl)
        assertEquals(754L, v.durationSeconds)
    }

    @Test
    fun parseChannelsReadsChannelRenderers() {
        val root = parse(
            """
            {"contents":[{"channelRenderer":{
              "channelId":"UC111",
              "title":{"simpleText":"Chan One"},
              "thumbnail":{"thumbnails":[{"url":"a1"}]}
            }},{"channelRenderer":{
              "channelId":"UC111",
              "title":{"simpleText":"Chan One dupe"},
              "thumbnail":{"thumbnails":[{"url":"a1"}]}
            }}]}
            """.trimIndent()
        )
        val channels = InnertubeParse.parseChannels(root)
        assertEquals(1, channels.size)
        assertEquals("Chan One", channels.single().name)
    }

    @Test
    fun parseGuidePlaylistsFindsVlEntriesOnly() {
        val root = parse(
            """
            {"items":[
              {"guideEntryRenderer":{"formattedTitle":{"simpleText":"Home"},
                "navigationEndpoint":{"browseEndpoint":{"browseId":"FEwhat_to_watch"}}}},
              {"guideEntryRenderer":{"formattedTitle":{"simpleText":"My Mix"},
                "navigationEndpoint":{"browseEndpoint":{"browseId":"VLPLabc"}}}},
              {"guideEntryRenderer":{"formattedTitle":{"simpleText":"Liked videos"},
                "navigationEndpoint":{"browseEndpoint":{"browseId":"VLLL"}}}}
            ]}
            """.trimIndent()
        )
        val playlists = InnertubeParse.parseGuidePlaylists(root)
        assertEquals(listOf("VLPLabc", "VLLL"), playlists.map { it.browseId })
        assertEquals("My Mix", playlists.first().title)
    }

    @Test
    fun parseAccountReadsNameAvatarHandle() {
        val root = parse(
            """
            {"header":{"activeAccountHeaderRenderer":{
              "accountName":{"simpleText":"Bara Z"},
              "accountPhoto":{"thumbnails":[{"url":"face"}]},
              "channelHandle":{"simpleText":"@bara"}
            }}}
            """.trimIndent()
        )
        val account = InnertubeParse.parseAccount(root)
        assertEquals("Bara Z", account?.name)
        assertEquals("face", account?.avatarUrl)
        assertEquals("@bara", account?.handle)
        assertNull(InnertubeParse.parseAccount(parse("""{"nothing":true}""")))
    }

    @Test
    fun findCreateCommentParamsAndContinuation() {
        val comments = parse(
            """{"onResponseReceivedEndpoints":[{"createCommentEndpoint":{"createCommentParams":"TOKEN123"}}]}"""
        )
        assertEquals("TOKEN123", InnertubeParse.findCreateCommentParams(comments))
        val watch = parse(
            """
            {"engagementPanels":[{"engagementPanelSectionListRenderer":{
              "content":{"sectionListRenderer":{"contents":[
                {"itemSectionRenderer":{"contents":[
                  {"continuationItemRenderer":{"continuationEndpoint":{"continuationCommand":{"token":"CONT1"}}}}
                ]}}
              ]}}
            }}]}
            """.trimIndent()
        )
        assertEquals("CONT1", InnertubeParse.findCommentsContinuation(watch))
    }

    @Test
    fun durationTextParsing() {
        assertEquals(754L, InnertubeParse.parseDurationText("12:34"))
        assertEquals(3723L, InnertubeParse.parseDurationText("1:02:03"))
        assertEquals(45L, InnertubeParse.parseDurationText("45"))
        assertEquals(0L, InnertubeParse.parseDurationText("LIVE"))
        assertEquals(0L, InnertubeParse.parseDurationText(null))
    }
}
