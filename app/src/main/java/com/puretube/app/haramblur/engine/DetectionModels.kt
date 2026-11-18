package com.puretube.app.haramblur.engine

import android.graphics.RectF
import kotlin.math.max
import kotlin.math.min

/** Normalized rectangle in [0..1] coordinates. Pure Kotlin so the math is JVM-testable. */
data class NormalizedRect(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float
) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
    val centerX: Float get() = (left + right) / 2f
    val centerY: Float get() = (top + bottom) / 2f

    fun toRectF(): RectF = RectF(left, top, right, bottom)

    companion object {
        fun fromRectF(r: RectF): NormalizedRect = NormalizedRect(r.left, r.top, r.right, r.bottom)
    }
}

enum class ClassifiedSubject { FEMALE, MALE, UNKNOWN_PERSON }

data class DetectedBlurRegion(
    val id: Int,
    val normalizedRect: NormalizedRect,
    val classifiedGender: ClassifiedSubject,
    val confidence: Float,
    val timestampMs: Long
) {
    /** Android RectF view of the normalized rect, for Canvas / overlay drawing. */
    val rectF: RectF get() = normalizedRect.toRectF()
}

/** Pure bounding-box math: expansion, IoU and non-max suppression. No Android types. */
object BlurBoxMath {

    /** Expands a face box to cover hair (top) and shoulders (bottom). */
    fun expandForHairAndShoulders(faceRect: NormalizedRect): NormalizedRect {
        val widthPad = faceRect.width * 0.45f
        val topPad = faceRect.height * 0.40f
        val bottomPad = faceRect.height * 0.85f
        return NormalizedRect(
            (faceRect.left - widthPad).coerceAtLeast(0f),
            (faceRect.top - topPad).coerceAtLeast(0f),
            (faceRect.right + widthPad).coerceAtMost(1f),
            (faceRect.bottom + bottomPad).coerceAtMost(1f)
        )
    }

    fun computeIoU(a: NormalizedRect, b: NormalizedRect): Float {
        val interLeft = max(a.left, b.left)
        val interTop = max(a.top, b.top)
        val interRight = min(a.right, b.right)
        val interBottom = min(a.bottom, b.bottom)
        if (interRight <= interLeft || interBottom <= interTop) return 0f
        val interArea = (interRight - interLeft) * (interBottom - interTop)
        val unionArea = a.width * a.height + b.width * b.height - interArea
        return if (unionArea > 0f) interArea / unionArea else 0f
    }

    fun nonMaxSuppression(boxes: List<NormalizedRect>, iouThreshold: Float = 0.35f): List<NormalizedRect> {
        if (boxes.size <= 1) return boxes
        val kept = mutableListOf<NormalizedRect>()
        for (candidate in boxes) {
            val overlaps = kept.any { existing -> computeIoU(existing, candidate) > iouThreshold }
            if (!overlaps) kept.add(candidate)
        }
        return kept
    }

    /** Exponential moving average between two rects. alpha = weight of [current]. */
    fun smooth(previous: NormalizedRect, current: NormalizedRect, alpha: Float = 0.65f): NormalizedRect =
        NormalizedRect(
            previous.left * (1 - alpha) + current.left * alpha,
            previous.top * (1 - alpha) + current.top * alpha,
            previous.right * (1 - alpha) + current.right * alpha,
            previous.bottom * (1 - alpha) + current.bottom * alpha
        )
}
