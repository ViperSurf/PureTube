package com.puretube.app

import com.puretube.app.data.local.datastore.BlurTargetGender
import com.puretube.app.domain.AudioOnlySurface
import com.puretube.app.domain.BlazeFaceAnchors
import com.puretube.app.domain.ErrorDetail
import com.puretube.app.domain.ExtractorItemMapper
import com.puretube.app.domain.HaramBlurPolicy
import com.puretube.app.domain.PlaybackSessionLogic
import com.puretube.app.domain.RawChannelItem
import com.puretube.app.domain.RawCommentItem
import com.puretube.app.domain.RawPlaylistItem
import com.puretube.app.domain.RawStreamItem
import com.puretube.app.domain.RawThumb
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class V13LogicTest {

    private val idOf: (String) -> String = { url ->
        url.substringAfter("v=").take(11)
    }

    // ---------- Null-safe mapping (the search NPE fix) ----------

    @Test
    fun streamItemWithAllNullOptionalFieldsMapsWithDefaults() {
        val hit = ExtractorItemMapper.toFeedItem(
            RawStreamItem(
                url = "https://www.youtube.com/watch?v=dQw4w9WgXcQ",
                name = "Some video",
                uploaderName = null,
                uploaderUrl = null,
                thumbnails = emptyList(),
                uploaderAvatars = listOf(RawThumb(null, 0)),
                durationSeconds = 0L,
                uploadEpochMs = 0L
            ), idOf
        )
        assertNotNull(hit)
        assertEquals("dQw4w9WgXcQ", hit!!.videoId)
        assertEquals("", hit.channelName)
        assertEquals("", hit.channelId)
        assertEquals("", hit.thumbnailUrl)
        assertEquals("", hit.channelAvatarUrl)
    }

    @Test
    fun streamItemWithNullNameOrUrlIsSkippedNotThrown() {
        assertNull(ExtractorItemMapper.toFeedItem(RawStreamItem(url = "https://x", name = null), idOf))
        assertNull(ExtractorItemMapper.toFeedItem(RawStreamItem(url = null, name = "t"), idOf))
        assertNull(ExtractorItemMapper.toFeedItem(RawStreamItem(url = " ", name = "t"), idOf))
    }

    @Test
    fun streamItemPicksLargestUsableThumbnail() {
        val hit = ExtractorItemMapper.toFeedItem(
            RawStreamItem(
                url = "https://www.youtube.com/watch?v=dQw4w9WgXcQ",
                name = "t",
                thumbnails = listOf(
                    RawThumb(null, 999),
                    RawThumb("small.jpg", 90),
                    RawThumb("big.jpg", 720)
                )
            ), idOf
        )
        assertEquals("big.jpg", hit!!.thumbnailUrl)
    }

    @Test
    fun channelItemWithNullDescriptionAndThumbsMaps() {
        val hit = ExtractorItemMapper.toChannelHit(
            RawChannelItem(
                url = "https://www.youtube.com/channel/UCabc123def456ghi789jkl0",
                name = "SSundee",
                thumbnails = emptyList(),
                subscriberCount = -1L,
                description = null
            )
        )
        assertNotNull(hit)
        assertEquals("SSundee", hit!!.name)
        assertEquals("", hit.description)
        assertEquals("", hit.avatarUrl)
        assertNull(ExtractorItemMapper.toChannelHit(RawChannelItem(url = null, name = "x")))
        assertNull(ExtractorItemMapper.toChannelHit(RawChannelItem(url = "https://x", name = null)))
    }

    @Test
    fun playlistItemWithNullUploaderMaps() {
        val hit = ExtractorItemMapper.toPlaylistHit(
            RawPlaylistItem(url = "https://www.youtube.com/playlist?list=PL1", name = "Mix", uploaderName = null)
        )
        assertNotNull(hit)
        assertEquals("", hit!!.channelName)
        assertNull(ExtractorItemMapper.toPlaylistHit(RawPlaylistItem(url = "https://x", name = null)))
    }

    @Test
    fun commentItemWithNullFieldsMapsToEmptyStrings() {
        val item = ExtractorItemMapper.toCommentItem(RawCommentItem())
        assertEquals("", item.authorName)
        assertEquals("", item.text)
        assertEquals(-1, item.likeCount)
    }

    @Test
    fun errorDetailIncludesClassAndStackFrames() {
        val e = try {
            throw NullPointerException("getOrDefault (.) must not be null")
        } catch (t: NullPointerException) {
            t
        }
        val detail = ErrorDetail.withStack(e, frames = 5)
        assertTrue(detail.contains("NullPointerException"))
        assertTrue(detail.contains("Stack (top"))
        assertTrue(detail.contains("V13LogicTest"))
    }

    // ---------- BlazeFace anchors / decode ----------

    @Test
    fun anchorCountIs896WithExpectedGrids() {
        assertEquals(896, BlazeFaceAnchors.centers.size)
        // First anchor centre is the centre of the first 16x16 cell.
        assertEquals(0.5f / 16f, BlazeFaceAnchors.centers[0].first, 0.0001f)
        assertEquals(0.5f / 16f, BlazeFaceAnchors.centers[0].second, 0.0001f)
        // Anchor 512 is the first stride-16 anchor: centre of first 8x8 cell.
        assertEquals(0.5f / 8f, BlazeFaceAnchors.centers[512].first, 0.0001f)
    }

    @Test
    fun decodeFindsPlantedFaceBox() {
        val regressors = FloatArray(896 * 16)
        val logits = FloatArray(896) { -10f }
        // Plant a strong detection at anchor 100 (stride-8 layer):
        // centre exactly on the anchor, size 32x32 px of 128.
        val idx = 100
        logits[idx] = 10f
        regressors[idx * 16 + 2] = 32f
        regressors[idx * 16 + 3] = 32f
        val boxes = BlazeFaceAnchors.decode(regressors, logits, minScore = 0.5f)
        assertEquals(1, boxes.size)
        val (ax, ay) = BlazeFaceAnchors.centers[idx]
        assertEquals(ax - 0.125f, boxes[0].left, 0.001f)
        assertEquals(ay - 0.125f, boxes[0].top, 0.001f)
        assertEquals(ax + 0.125f, boxes[0].right, 0.001f)
    }

    @Test
    fun decodeBelowThresholdFindsNothing() {
        val boxes = BlazeFaceAnchors.decode(
            FloatArray(896 * 16), FloatArray(896) { -10f }, minScore = 0.5f
        )
        assertTrue(boxes.isEmpty())
    }

    // ---------- No-gender-model blur rule ----------

    @Test
    fun withoutGenderModelEveryTargetBecomesBlurAll() {
        BlurTargetGender.entries.forEach { target ->
            assertEquals(
                BlurTargetGender.BOTH_ALL_PEOPLE,
                HaramBlurPolicy.effectiveTarget(target, hasGenderModel = false)
            )
        }
        assertEquals(
            BlurTargetGender.FEMALE_ONLY,
            HaramBlurPolicy.effectiveTarget(BlurTargetGender.FEMALE_ONLY, hasGenderModel = true)
        )
        assertEquals(
            "All-faces fallback (no gender model)",
            HaramBlurPolicy.modeLabel(BlurTargetGender.FEMALE_ONLY, faceModelLoaded = true, hasGenderModel = false)
        )
    }

    // ---------- Session cache ----------

    @Test
    fun sessionReuseOnlyForSameNonBlankVideo() {
        assertTrue(PlaybackSessionLogic.shouldReuseSession("abc", "abc"))
        assertFalse(PlaybackSessionLogic.shouldReuseSession("abc", "xyz"))
        assertFalse(PlaybackSessionLogic.shouldReuseSession("abc", null))
        assertFalse(PlaybackSessionLogic.shouldReuseSession("", ""))
    }

    // ---------- Audio-only surface ----------

    @Test
    fun audioOnlySurfaceKindsAndLabels() {
        assertEquals(AudioOnlySurface.Kind.PLACEHOLDER, AudioOnlySurface.kindFor(true))
        assertEquals(AudioOnlySurface.Kind.VIDEO, AudioOnlySurface.kindFor(false))
        assertEquals("Audio Only", AudioOnlySurface.label(true))
        assertEquals("Video + Audio", AudioOnlySurface.label(false))
        assertEquals("Muted", AudioOnlySurface.soundLabel(true))
        assertEquals("Unmuted", AudioOnlySurface.soundLabel(false))
    }
}
