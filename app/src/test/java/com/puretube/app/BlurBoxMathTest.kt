package com.puretube.app

import com.puretube.app.haramblur.engine.BlurBoxMath
import com.puretube.app.haramblur.engine.ClassifiedSubject
import com.puretube.app.haramblur.engine.HaramBlurEngine
import com.puretube.app.haramblur.engine.NormalizedRect
import com.puretube.app.data.local.datastore.BlurTargetGender
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BlurBoxMathTest {

    @Test
    fun expandForHairAndShouldersPadsAndClamps() {
        // v1.4 face/head-only padding: 0.20 sides, 0.25 top, 0.20 bottom.
        val face = NormalizedRect(0.4f, 0.3f, 0.6f, 0.5f) // w=0.2 h=0.2
        val expanded = BlurBoxMath.expandForHairAndShoulders(face)
        assertEquals(0.4f - 0.04f, expanded.left, 0.001f)
        assertEquals(0.3f - 0.05f, expanded.top, 0.001f)
        assertEquals(0.6f + 0.04f, expanded.right, 0.001f)
        assertEquals(0.5f + 0.04f, expanded.bottom, 0.001f)
    }

    @Test
    fun expandFaceBoxUsesFaceOnlyPadding() {
        val face = NormalizedRect(0.4f, 0.3f, 0.6f, 0.5f)
        val expanded = BlurBoxMath.expandFaceBox(face)
        assertEquals(0.36f, expanded.left, 0.001f)
        assertEquals(0.25f, expanded.top, 0.001f)
        assertEquals(0.64f, expanded.right, 0.001f)
        assertEquals(0.54f, expanded.bottom, 0.001f)
        // No body coverage: bottom stays well above the old +0.85 pad (0.67).
        assertTrue(expanded.bottom < 0.6f)
    }

    @Test
    fun expandClampsAtFrameEdges() {
        val face = NormalizedRect(0.02f, 0.02f, 0.2f, 0.2f)
        val expanded = BlurBoxMath.expandForHairAndShoulders(face)
        assertEquals(0f, expanded.left, 0.0001f)
        assertEquals(0f, expanded.top, 0.0001f)
    }

    @Test
    fun iouOfIdenticalBoxesIsOneAndDisjointIsZero() {
        val a = NormalizedRect(0.1f, 0.1f, 0.5f, 0.5f)
        assertEquals(1f, BlurBoxMath.computeIoU(a, a), 0.001f)
        val b = NormalizedRect(0.6f, 0.6f, 0.9f, 0.9f)
        assertEquals(0f, BlurBoxMath.computeIoU(a, b), 0.001f)
    }

    @Test
    fun nmsDropsOverlappingDuplicates() {
        val boxes = listOf(
            NormalizedRect(0.10f, 0.10f, 0.50f, 0.50f),
            NormalizedRect(0.11f, 0.11f, 0.51f, 0.51f),
            NormalizedRect(0.60f, 0.60f, 0.90f, 0.90f)
        )
        assertEquals(2, BlurBoxMath.nonMaxSuppression(boxes).size)
    }

    @Test
    fun strictSensitivityBlursUnknownUnderFemaleOnly() {
        assertTrue(
            HaramBlurEngine.shouldBlur(
                BlurTargetGender.FEMALE_ONLY, ClassifiedSubject.UNKNOWN_PERSON, sensitivity = 0.80f
            )
        )
        assertFalse(
            HaramBlurEngine.shouldBlur(
                BlurTargetGender.FEMALE_ONLY, ClassifiedSubject.UNKNOWN_PERSON, sensitivity = 0.50f
            )
        )
        assertFalse(
            HaramBlurEngine.shouldBlur(
                BlurTargetGender.FEMALE_ONLY, ClassifiedSubject.MALE, sensitivity = 0.90f
            )
        )
        assertTrue(
            HaramBlurEngine.shouldBlur(
                BlurTargetGender.BOTH_ALL_PEOPLE, ClassifiedSubject.MALE, sensitivity = 0.40f
            )
        )
    }
}
