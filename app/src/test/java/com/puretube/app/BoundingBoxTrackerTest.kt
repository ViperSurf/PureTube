package com.puretube.app

import com.puretube.app.haramblur.engine.BoundingBoxTracker
import com.puretube.app.haramblur.engine.ClassifiedSubject
import com.puretube.app.haramblur.engine.DetectedBlurRegion
import com.puretube.app.haramblur.engine.NormalizedRect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BoundingBoxTrackerTest {

    private var nowMs = 0L
    private val tracker = BoundingBoxTracker(holdMs = 450L, alpha = 0.65f, clock = { nowMs })

    private fun region(left: Float) = DetectedBlurRegion(
        id = 0,
        normalizedRect = NormalizedRect(left, 0.2f, left + 0.2f, 0.5f),
        classifiedGender = ClassifiedSubject.FEMALE,
        confidence = 0.9f,
        timestampMs = nowMs
    )

    @Test
    fun hysteresisHoldsRegionsFor450msAfterDetectionsStop() {
        nowMs = 1_000L
        val first = tracker.onDetections(listOf(region(0.3f)))
        assertEquals(1, first.size)

        nowMs = 1_400L // 400ms later: still inside the hold window
        val held = tracker.onDetections(emptyList())
        assertEquals(1, held.size)

        nowMs = 1_451L // past 450ms: hold expires
        val expired = tracker.onDetections(emptyList())
        assertTrue(expired.isEmpty())
    }

    @Test
    fun smoothingMovesBoxPartwayTowardNewDetection() {
        nowMs = 0L
        tracker.onDetections(listOf(region(0.0f)))
        nowMs = 83L
        val smoothed = tracker.onDetections(listOf(region(0.4f)))
        // EMA alpha 0.65: 0.0 * 0.35 + 0.4 * 0.65 = 0.26
        assertEquals(0.26f, smoothed.first().normalizedRect.left, 0.001f)
    }
}
