package com.puretube.app

import com.puretube.app.data.local.datastore.AppLanguage
import com.puretube.app.data.local.datastore.BlurStrength
import com.puretube.app.data.local.datastore.CaptionBackground
import com.puretube.app.data.local.datastore.CaptionTextColor
import com.puretube.app.data.local.datastore.CaptionTextSize
import com.puretube.app.data.local.datastore.VolumeZeroAction
import com.puretube.app.domain.AudioTrackChoice
import com.puretube.app.domain.AudioTrackLogic
import com.puretube.app.domain.BlurStrengthMath
import com.puretube.app.domain.CaptionParser
import com.puretube.app.domain.CaptionStyleMath
import com.puretube.app.domain.CaptionTrackLogic
import com.puretube.app.domain.CaptionTrackOption
import com.puretube.app.domain.DeviceCodeParse
import com.puretube.app.domain.DevicePollResult
import com.puretube.app.domain.FullScreenBlurHold
import com.puretube.app.domain.GestureClassifier
import com.puretube.app.domain.LanguageSupport
import com.puretube.app.domain.PlaylistPushPlanner
import com.puretube.app.domain.VideoInfoFormat
import com.puretube.app.domain.VolumeZeroRule
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId

class V21LogicTest {

    // ---------------- Gesture arbitration (Part B) ----------------

    private fun actions(events: List<GestureClassifier.Event>) =
        events.map { it.action }

    @Test
    fun singleTapFiresOnlyAfterDoubleTapWindow() {
        val c = GestureClassifier()
        c.onDown(100f, 100f, 0)
        // A tap must NOT fire on finger-up (it might be a double tap).
        assertTrue(c.onUp(100).isEmpty())
        assertTrue(c.isAwaitingSecondTap())
        val fired = c.onTimeout(100 + GestureClassifier.DOUBLE_TAP_TIMEOUT_MS + 1)
        assertEquals(listOf(GestureClassifier.Action.TAP), actions(fired))
    }

    @Test
    fun doubleTapFiresAndSuppressesSingleTap() {
        val c = GestureClassifier()
        c.onDown(300f, 100f, 0)
        c.onUp(80)
        c.onDown(300f, 100f, 200)
        val fired = c.onUp(280)
        assertEquals(listOf(GestureClassifier.Action.DOUBLE_TAP), actions(fired))
        assertTrue(c.onTimeout(1000).isEmpty())
    }

    @Test
    fun verticalDragCancelsTapDoubleTapAndHold() {
        val c = GestureClassifier()
        c.onDown(100f, 100f, 0)
        val dragStart = c.onMove(100f, 140f, 50) // 40px > slop
        assertEquals(
            listOf(
                GestureClassifier.Action.DRAG_START,
                GestureClassifier.Action.DRAG_DELTA
            ),
            actions(dragStart)
        )
        assertEquals(40f, dragStart[1].dragDy, 0.01f)
        val deltas = c.onMove(100f, 200f, 100)
        assertEquals(listOf(GestureClassifier.Action.DRAG_DELTA), actions(deltas))
        assertEquals(100f, deltas[0].dragDy, 0.01f)
        // No hold even though total press time exceeds the hold threshold.
        assertTrue(c.onTick(500).isEmpty())
        val end = c.onUp(600)
        assertEquals(listOf(GestureClassifier.Action.DRAG_END), actions(end))
    }

    @Test
    fun dragThenTapDoesNotBecomeDoubleTap() {
        // The volume-bug shape: a drag, then a tap — must stay a tap.
        val c = GestureClassifier()
        c.onDown(100f, 100f, 0)
        c.onMove(100f, 200f, 50)
        c.onUp(100)
        c.onDown(100f, 100f, 200)
        assertTrue(c.onUp(280).isEmpty()) // waiting: could be double tap
        val fired = c.onTimeout(280 + GestureClassifier.DOUBLE_TAP_TIMEOUT_MS + 1)
        assertEquals(listOf(GestureClassifier.Action.TAP), actions(fired))
    }

    @Test
    fun stationaryHoldFiresOnceAndEndsOnRelease() {
        val c = GestureClassifier()
        c.onDown(100f, 100f, 0)
        assertTrue(c.onTick(200).isEmpty())
        val start = c.onTick(300)
        assertEquals(listOf(GestureClassifier.Action.HOLD_START), actions(start))
        assertTrue(c.onTick(600).isEmpty()) // no repeat
        val end = c.onUp(700)
        assertEquals(listOf(GestureClassifier.Action.HOLD_END), actions(end))
    }

    @Test
    fun smallJitterDoesNotCancelHold() {
        val c = GestureClassifier()
        c.onDown(100f, 100f, 0)
        c.onMove(105f, 103f, 100) // within slop
        val start = c.onTick(300)
        assertEquals(listOf(GestureClassifier.Action.HOLD_START), actions(start))
    }

    // ---------------- Volume-zero machine v2 (Part B) ----------------

    private fun zeroSample(
        state: VolumeZeroRule.State,
        zero: Boolean,
        playing: Boolean,
        now: Long,
        action: VolumeZeroAction = VolumeZeroAction.PAUSE_ONLY,
        resume: Boolean = false,
        delayMs: Long = 30_000L
    ) = VolumeZeroRule.onSample(
        state = state,
        volumeIsZero = zero,
        isPlaying = playing,
        hasMedia = true,
        enabled = true,
        stopDelayMs = delayMs,
        nowMs = now,
        zeroAction = action,
        resumeOnReturn = resume
    )

    @Test
    fun rewindActionsSeekBackByConfiguredAmounts() {
        for ((action, expectedMs) in listOf(
            VolumeZeroAction.REWIND_5 to 5_000L,
            VolumeZeroAction.REWIND_10 to 10_000L,
            VolumeZeroAction.REWIND_15 to 15_000L
        )) {
            val (state, act) = zeroSample(
                VolumeZeroRule.State(), zero = true, playing = true, now = 1000, action = action
            )
            assertEquals(VolumeZeroRule.Action.REWIND, act)
            assertEquals(expectedMs, state.lastRewindMs)
            assertFalse(state.pausedByRule) // rewind-only keeps playing
        }
    }

    @Test
    fun rewindAndPauseRewinds10AndPauses() {
        val (state, act) = zeroSample(
            VolumeZeroRule.State(), zero = true, playing = true, now = 1000,
            action = VolumeZeroAction.REWIND_AND_PAUSE
        )
        assertEquals(VolumeZeroRule.Action.REWIND_AND_PAUSE, act)
        assertEquals(10_000L, state.lastRewindMs)
        assertTrue(state.pausedByRule)
    }

    @Test
    fun resumeIsUnreachableWhenOptionOff() {
        val (paused, _) = zeroSample(
            VolumeZeroRule.State(), zero = true, playing = true, now = 1000
        )
        val (_, act) = zeroSample(
            paused, zero = false, playing = false, now = 2000, resume = false
        )
        assertEquals(VolumeZeroRule.Action.NONE, act)
    }

    @Test
    fun resumeFiresWhenOptionOnAndRulePaused() {
        val (paused, _) = zeroSample(
            VolumeZeroRule.State(), zero = true, playing = true, now = 1000
        )
        val (after, act) = zeroSample(
            paused, zero = false, playing = false, now = 2000, resume = true
        )
        assertEquals(VolumeZeroRule.Action.RESUME, act)
        assertFalse(after.pausedByRule)
    }

    @Test
    fun resumeNeverFiresForUserPausedVideo() {
        val (armed, act1) = zeroSample(
            VolumeZeroRule.State(), zero = true, playing = false, now = 1000, resume = true
        )
        assertEquals(VolumeZeroRule.Action.NONE, act1)
        assertFalse(armed.pausedByRule)
        val (_, act2) = zeroSample(
            armed, zero = false, playing = false, now = 2000, resume = true
        )
        assertEquals(VolumeZeroRule.Action.NONE, act2)
    }

    @Test
    fun stopStillFiresAfterRewindWhenVolumeStaysZero() {
        val (rewound, _) = zeroSample(
            VolumeZeroRule.State(), zero = true, playing = true, now = 0,
            action = VolumeZeroAction.REWIND_10
        )
        val (_, act) = zeroSample(
            rewound, zero = true, playing = true, now = 31_000,
            action = VolumeZeroAction.REWIND_10
        )
        assertEquals(VolumeZeroRule.Action.STOP, act)
    }

    // ---------------- Blur strength + full-screen hold (Part A) ----------------

    @Test
    fun blurStrengthIsMonotonic() {
        val strengths = BlurStrength.entries
        val pixelWidths = strengths.map { BlurStrengthMath.pixelFrameSize(it).first }
        val frostAlphas = strengths.map { BlurStrengthMath.frostTintAlpha(it) }
        val divisors = strengths.map { BlurStrengthMath.thumbnailMosaicDivisor(it) }
        assertEquals(pixelWidths.sortedDescending(), pixelWidths) // coarser = smaller frame
        assertEquals(frostAlphas.sorted(), frostAlphas) // more opaque with strength
        assertEquals(divisors.sortedDescending(), divisors) // bigger blocks with strength
        assertTrue(
            BlurStrengthMath.pixelFrameSize(BlurStrength.STRONG).first <
                BlurStrengthMath.pixelFrameSize(BlurStrength.LIGHT).first
        )
    }

    @Test
    fun fullScreenHoldKeepsBlurBrieflyAfterClear() {
        // Detection at t=1000; the frame then clears.
        assertTrue(FullScreenBlurHold.isActive(1000, 1000, hasDetectionsNow = true))
        assertTrue(FullScreenBlurHold.isActive(1000, 1500, hasDetectionsNow = false))
        assertFalse(FullScreenBlurHold.isActive(1000, 1601, hasDetectionsNow = false))
        assertFalse(FullScreenBlurHold.isActive(0, 5000, hasDetectionsNow = false))
    }

    // ---------------- Captions (Part E) ----------------

    @Test
    fun parsesWebVttAndFindsCues() {
        val vtt = """
            WEBVTT

            00:00:01.000 --> 00:00:03.500
            Hello <b>world</b>

            00:00:04.000 --> 00:00:06.000
            Second line
            continues here
        """.trimIndent()
        val cues = CaptionParser.parse(vtt)
        assertEquals(2, cues.size)
        assertEquals("Hello world", cues[0].text)
        assertEquals("Second line\ncontinues here", cues[1].text)
        assertEquals("Hello world", CaptionParser.cueAt(cues, 2000)?.text)
        assertNull(CaptionParser.cueAt(cues, 3900))
        assertEquals("Second line\ncontinues here", CaptionParser.cueAt(cues, 5999)?.text)
    }

    @Test
    fun parsesSrtAndTtml() {
        val srt = """
            1
            00:00:01,000 --> 00:00:02,000
            SRT cue

            2
            00:00:03,000 --> 00:00:04,500
            Another
        """.trimIndent()
        val srtCues = CaptionParser.parse(srt)
        assertEquals(2, srtCues.size)
        assertEquals(1000L, srtCues[0].startMs)
        assertEquals(4500L, srtCues[1].endMs)

        val ttml = """
            <tt xmlns="http://www.w3.org/ns/ttml"><body><div>
            <p begin="00:00:01.000" end="00:00:02.500">TTML cue</p>
            </div></body></tt>
        """.trimIndent()
        val ttmlCues = CaptionParser.parse(ttml)
        assertEquals(1, ttmlCues.size)
        assertEquals("TTML cue", ttmlCues[0].text)
    }

    @Test
    fun duplicateLinesInsideCueAreDeduped() {
        val vtt = """
            WEBVTT

            00:00:01.000 --> 00:00:02.000
            Hello
            Hello
        """.trimIndent()
        val cues = CaptionParser.parse(vtt)
        assertEquals(1, cues.size)
        assertEquals("Hello", cues[0].text)
    }

    @Test
    fun captionStyleMapping() {
        assertTrue(
            CaptionStyleMath.fontSizeSp(CaptionTextSize.EXTRA_LARGE) >
                CaptionStyleMath.fontSizeSp(CaptionTextSize.SMALL)
        )
        assertEquals(0xFFFFFFFFL, CaptionStyleMath.textColorArgb(CaptionTextColor.WHITE))
        assertEquals(0xFFFFFF00L, CaptionStyleMath.textColorArgb(CaptionTextColor.YELLOW))
        assertEquals(0L, CaptionStyleMath.backgroundArgb(CaptionBackground.NONE))
        assertTrue(
            CaptionStyleMath.backgroundArgb(CaptionBackground.BLACK) !=
                CaptionStyleMath.backgroundArgb(CaptionBackground.TRANSLUCENT)
        )
    }

    @Test
    fun captionOptionsDedupeManualBeatsAuto() {
        val options = CaptionTrackLogic.buildOptions(
            listOf(
                CaptionTrackOption("en", "English", "u1", autoGenerated = true),
                CaptionTrackOption("en", "English", "u2", autoGenerated = false),
                CaptionTrackOption("ar", "Arabic", "u3", autoGenerated = false),
                CaptionTrackOption("", "Broken", "u4", autoGenerated = false)
            )
        )
        assertEquals(2, options.size)
        val en = options.first { it.languageCode == "en" }
        assertEquals("u2", en.url) // manual track wins for the language
        assertFalse(options[0].autoGenerated) // manual tracks sort first
        assertEquals("Arabic", CaptionTrackLogic.displayLabel(options.first { it.languageCode == "ar" }))
    }

    // ---------------- Audio tracks (Part E) ----------------

    @Test
    fun audioChoicesDedupeByLanguage() {
        val choices = AudioTrackLogic.buildChoices(
            listOf(
                AudioTrackChoice(0, 0, "en", ""),
                AudioTrackChoice(0, 1, "en", ""),
                AudioTrackChoice(1, 0, "es", "")
            )
        )
        assertEquals(2, choices.size)
        assertEquals(0, choices.first { it.language == "en" }.trackIndex) // first wins
        assertTrue(
            AudioTrackLogic.displayLabel(choices.first { it.language == "en" })
                .contains("English")
        )
        assertEquals(
            "es",
            AudioTrackLogic.choiceForLanguage(choices, "es-ES")?.language
        )
        assertNull(AudioTrackLogic.choiceForLanguage(choices, "de"))
    }

    // ---------------- Device code (Part F) ----------------

    private fun json(text: String) = Json.parseToJsonElement(text).jsonObject

    @Test
    fun deviceCodeResponseParses() {
        val grant = DeviceCodeParse.parseDeviceCodeResponse(
            json(
                """{"device_code":"dc1","user_code":"ABCD-EFGH","verification_url":"https://www.google.com/device","expires_in":1800,"interval":5}"""
            )
        )
        assertEquals("ABCD-EFGH", grant?.userCode)
        assertEquals(5L, grant?.intervalSeconds)
        assertEquals("dc1", grant?.deviceCode)
    }

    @Test
    fun tokenPollStates() {
        assertTrue(
            DeviceCodeParse.parseTokenResponse(
                json("""{"error":"authorization_pending"}"""), 400
            ) is DevicePollResult.Pending
        )
        assertTrue(
            DeviceCodeParse.parseTokenResponse(
                json("""{"error":"slow_down"}"""), 400
            ) is DevicePollResult.SlowDown
        )
        assertTrue(
            DeviceCodeParse.parseTokenResponse(
                json("""{"error":"access_denied"}"""), 400
            ) is DevicePollResult.Denied
        )
        assertTrue(
            DeviceCodeParse.parseTokenResponse(
                json("""{"error":"expired_token"}"""), 400
            ) is DevicePollResult.Expired
        )
        val approved = DeviceCodeParse.parseTokenResponse(
            json("""{"access_token":"at","refresh_token":"rt","expires_in":3600}"""),
            200
        ) as DevicePollResult.Approved
        assertEquals("rt", approved.refreshToken)
        assertEquals(3600L, approved.expiresInSeconds)
    }

    @Test
    fun invalidClientExplainsItself() {
        val outcome = DeviceCodeParse.parseTokenResponse(
            json("""{"error":"invalid_client","error_description":"The OAuth client was not found."}"""),
            401
        ) as DevicePollResult.Failed
        assertTrue(outcome.message.contains("client"))
    }

    // ---------------- Playlist push (Part F) ----------------

    @Test
    fun pushDiffsMissingVideosOnly() {
        assertEquals(
            listOf("c", "d"),
            PlaylistPushPlanner.missingVideos(
                listOf("a", "b", "c", "d", "c"),
                setOf("b", "a")
            )
        )
        assertTrue(
            PlaylistPushPlanner.missingVideos(listOf("a"), setOf("a")).isEmpty()
        )
    }

    @Test
    fun systemPlaylistsAreNotEditable() {
        assertFalse(PlaylistPushPlanner.isEditablePlaylist("LL"))
        assertFalse(PlaylistPushPlanner.isEditablePlaylist("WL"))
        assertFalse(PlaylistPushPlanner.isEditablePlaylist("VLLL"))
        assertTrue(PlaylistPushPlanner.isEditablePlaylist("PLabc123"))
        assertTrue(PlaylistPushPlanner.isEditablePlaylist(null))
    }

    // ---------------- Video info + language (Parts C/D) ----------------

    @Test
    fun videoInfoFormatting() {
        // 2026-11-12T00:00:00Z
        val epoch = 1794441600000L
        assertEquals("12 Nov 2026", VideoInfoFormat.absoluteDate(epoch, ZoneId.of("UTC")))
        val now = epoch + 3L * 24 * 3600 * 1000
        assertEquals("3 days ago", VideoInfoFormat.relativeDate(epoch, now))
        assertEquals("", VideoInfoFormat.absoluteDate(0, ZoneId.of("UTC")))
        assertEquals("1.2K", VideoInfoFormat.compactCount(1234))
        assertEquals("3.4M", VideoInfoFormat.compactCount(3_400_000))
        assertEquals("999", VideoInfoFormat.compactCount(999))
    }

    @Test
    fun offeredLanguagesAllHaveTranslations() {
        val offered = LocaleHelper.offeredLanguages
        assertFalse(offered.contains(AppLanguage.GERMAN))
        for (language in offered) {
            if (language == AppLanguage.SYSTEM || language == AppLanguage.ENGLISH) continue
            val tag = LocaleHelper.tagFor(language)
            assertTrue(
                "offered language $language has no translation",
                LanguageSupport.translatedCodes.contains(tag)
            )
        }
    }
}
