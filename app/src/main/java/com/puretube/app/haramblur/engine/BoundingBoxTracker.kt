package com.puretube.app.haramblur.engine

/**
 * Temporal tracker: EMA-smooths incoming detections against the previous frame
 * and holds the last valid regions for [holdMs] (450ms) after detections stop,
 * so a single-frame head turn never flickers the blur off.
 * Pure Kotlin with an injectable clock — fully unit-testable on the JVM.
 */
class BoundingBoxTracker(
    private val holdMs: Long = 450L,
    private val alpha: Float = 0.65f,
    private val clock: () -> Long = { System.currentTimeMillis() }
) {
    private var lastValidRegions: List<DetectedBlurRegion> = emptyList()
    private var lastDetectionTimestampMs: Long = Long.MIN_VALUE / 2

    fun onDetections(detected: List<DetectedBlurRegion>): List<DetectedBlurRegion> {
        val now = clock()
        return if (detected.isNotEmpty()) {
            val smoothed = smoothBoxes(lastValidRegions, detected)
            lastValidRegions = smoothed
            lastDetectionTimestampMs = now
            smoothed
        } else if (now - lastDetectionTimestampMs <= holdMs) {
            lastValidRegions
        } else {
            lastValidRegions = emptyList()
            emptyList()
        }
    }

    fun reset() {
        lastValidRegions = emptyList()
        lastDetectionTimestampMs = Long.MIN_VALUE / 2
    }

    fun smoothBoxes(
        previous: List<DetectedBlurRegion>,
        current: List<DetectedBlurRegion>
    ): List<DetectedBlurRegion> {
        if (previous.isEmpty()) return current
        return current.map { curr ->
            val match = previous.minByOrNull { prev ->
                val dx = prev.normalizedRect.centerX - curr.normalizedRect.centerX
                val dy = prev.normalizedRect.centerY - curr.normalizedRect.centerY
                dx * dx + dy * dy
            }
            if (match != null) {
                curr.copy(normalizedRect = BlurBoxMath.smooth(match.normalizedRect, curr.normalizedRect, alpha))
            } else {
                curr
            }
        }
    }
}
