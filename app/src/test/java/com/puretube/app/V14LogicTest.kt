package com.puretube.app

import com.puretube.app.data.local.datastore.ControlsVisibility
import com.puretube.app.data.local.datastore.LikesDisplay
import com.puretube.app.domain.DescriptionFormatter
import com.puretube.app.domain.HaramBlurExemptions
import com.puretube.app.domain.LikesDisplayMath
import com.puretube.app.domain.PlayerControlsPolicy
import com.puretube.app.domain.SearchResetPolicy
import com.puretube.app.domain.SwipeControlMath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class V14LogicTest {

    // ---------- Likes percent ----------

    @Test
    fun likePercentBasicMath() {
        assertEquals(90, LikesDisplayMath.likePercent(90, 10))
        assertEquals(50, LikesDisplayMath.likePercent(1, 1))
        assertEquals(100, LikesDisplayMath.likePercent(5, 0))
        assertEquals(0, LikesDisplayMath.likePercent(0, 5))
        assertEquals(67, LikesDisplayMath.likePercent(2, 1))
    }

    @Test
    fun likePercentNullWhenUnknownOrEmpty() {
        assertNull(LikesDisplayMath.likePercent(-1, 10))
        assertNull(LikesDisplayMath.likePercent(10, -1))
        assertNull(LikesDisplayMath.likePercent(0, 0))
        assertNull(LikesDisplayMath.likePercent(-5, -5))
    }

    @Test
    fun likesFormatModes() {
        assertEquals(
            "Likes 90 - Dislikes 10",
            LikesDisplayMath.format(90, 10, LikesDisplay.NUMBERS)
        )
        assertEquals(
            "90% liked (90 likes)",
            LikesDisplayMath.format(90, 10, LikesDisplay.PERCENTAGE)
        )
        assertEquals(
            "Likes 90 - Dislikes 10 (90% liked)",
            LikesDisplayMath.format(90, 10, LikesDisplay.BOTH)
        )
        // Unknown percent falls back to numbers in every mode.
        assertEquals(
            "Likes 0 - Dislikes 0",
            LikesDisplayMath.format(0, 0, LikesDisplay.PERCENTAGE)
        )
    }

    // ---------- Description formatter ----------

    @Test
    fun descriptionBrAndParagraphBecomeNewlines() {
        assertEquals("a\nb", DescriptionFormatter.toPlainText("a<br>b"))
        assertEquals("a\nb", DescriptionFormatter.toPlainText("a<br/>b"))
        assertEquals("a\nb", DescriptionFormatter.toPlainText("<p>a</p><p>b</p>").trim())
    }

    @Test
    fun descriptionLinkBecomesLinkSegment() {
        val segments = DescriptionFormatter.parse("see <a href=\"https://example.com/x\">this site</a> now")
        val link = segments.first { it.isLink }
        assertEquals("this site", link.text)
        assertEquals("https://example.com/x", link.url)
        assertEquals("see this site now", segments.joinToString("") { it.text })
    }

    @Test
    fun descriptionStripsOtherTags() {
        assertEquals(
            "bold and italic",
            DescriptionFormatter.toPlainText("<b>bold</b> and <i>italic</i>")
        )
    }

    @Test
    fun descriptionDecodesEntities() {
        assertEquals(
            "Fish & Chips <3 \"hi\" 'yo' a b",
            DescriptionFormatter.toPlainText(
                "Fish &amp; Chips &lt;3 &quot;hi&quot; &#39;yo&#39; a&nbsp;b"
            )
        )
        assertEquals("A", DescriptionFormatter.toPlainText("&#65;"))
        assertEquals("©", DescriptionFormatter.toPlainText("&#169;"))
    }

    @Test
    fun descriptionCollapsesThreePlusNewlinesToOneBlankLine() {
        assertEquals("a\n\nb", DescriptionFormatter.toPlainText("a\n\n\n\nb"))
        assertEquals("a\n\nb", DescriptionFormatter.toPlainText("a<br><br><br>b"))
        assertEquals("a\nb", DescriptionFormatter.toPlainText("a\nb"))
    }

    @Test
    fun descriptionFindsTimestamps() {
        val segments = DescriptionFormatter.parse("Intro 0:00 song 1:23 end 1:02:03 done")
        val stamps = segments.filter { it.isTimestamp }
        assertEquals(3, stamps.size)
        assertEquals(0L, stamps[0].timestampMs)
        assertEquals(83_000L, stamps[1].timestampMs)
        assertEquals(3_723_000L, stamps[2].timestampMs)
    }

    @Test
    fun descriptionTimestampNotMatchedInsideNumbers() {
        // "12:345" and ratios like "16:9x" should not become timestamps:
        // 16:09 IS a valid M:SS though — use clearly invalid seconds.
        val segments = DescriptionFormatter.parse("ratio test 1:99 nope")
        assertTrue(segments.none { it.isTimestamp })
    }

    @Test
    fun descriptionTimestampLinkTextBecomesTimestamp() {
        val segments = DescriptionFormatter.parse(
            "<a href=\"https://www.youtube.com/watch?v=x&t=83s\">1:23</a> chapter"
        )
        val ts = segments.first { it.isTimestamp }
        assertEquals(83_000L, ts.timestampMs)
    }

    @Test
    fun timestampMsParsesBothForms() {
        assertEquals(83_000L, DescriptionFormatter.timestampMs("1:23"))
        assertEquals(3_723_000L, DescriptionFormatter.timestampMs("1:02:03"))
        assertNull(DescriptionFormatter.timestampMs("hello"))
        assertNull(DescriptionFormatter.timestampMs("1:99"))
    }

    // ---------- Exemptions ----------

    @Test
    fun exemptChannelIsNotBlurredByIdOrUrlOrName() {
        val exempt = setOf("UCabc123")
        assertFalse(HaramBlurExemptions.shouldBlurForChannel(exempt, "UCabc123"))
        assertFalse(
            HaramBlurExemptions.shouldBlurForChannel(
                exempt, "https://www.youtube.com/channel/UCabc123"
            )
        )
        assertFalse(
            HaramBlurExemptions.shouldBlurForChannel(
                setOf("Some Channel"), "UCother", "some channel"
            )
        )
    }

    @Test
    fun nonExemptChannelIsBlurred() {
        assertTrue(HaramBlurExemptions.shouldBlurForChannel(setOf("UCabc"), "UCxyz"))
        assertTrue(HaramBlurExemptions.shouldBlurForChannel(emptySet(), "UCxyz"))
        assertTrue(HaramBlurExemptions.shouldBlurForChannel(setOf("UCabc"), null, null))
    }

    // ---------- Policies + swipe math ----------

    @Test
    fun controlsAutoHideOnlyInAutoModeWhilePlaying() {
        assertTrue(PlayerControlsPolicy.shouldAutoHide(ControlsVisibility.AUTO_HIDE, true))
        assertFalse(PlayerControlsPolicy.shouldAutoHide(ControlsVisibility.AUTO_HIDE, false))
        assertFalse(PlayerControlsPolicy.shouldAutoHide(ControlsVisibility.ALWAYS_VISIBLE, true))
    }

    @Test
    fun searchResetOnlyOnSearchRouteWithPref() {
        assertTrue(SearchResetPolicy.shouldReset("search", true))
        assertFalse(SearchResetPolicy.shouldReset("search", false))
        assertFalse(SearchResetPolicy.shouldReset("library", true))
        assertFalse(SearchResetPolicy.shouldReset(null, true))
    }

    @Test
    fun swipeDragUpIncreasesAndClamps() {
        assertEquals(0.75f, SwipeControlMath.applyDrag(0.5f, -250f, 1000f), 0.001f)
        assertEquals(0.25f, SwipeControlMath.applyDrag(0.5f, 250f, 1000f), 0.001f)
        assertEquals(1f, SwipeControlMath.applyDrag(0.9f, -500f, 1000f), 0.001f)
        assertEquals(0f, SwipeControlMath.applyDrag(0.1f, 500f, 1000f), 0.001f)
    }

    @Test
    fun unmuteVolumeIsAboutFortyPercent() {
        assertEquals(6, SwipeControlMath.unmuteVolume(15))
        assertEquals(1, SwipeControlMath.unmuteVolume(1))
        assertEquals(0, SwipeControlMath.unmuteVolume(0))
        assertEquals(8, SwipeControlMath.fractionToVolume(0.5f, 15))
    }
}
