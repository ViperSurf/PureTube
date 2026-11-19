package com.puretube.app.haramblur.engine

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import coil3.size.Size
import coil3.transform.Transformation
import com.puretube.app.data.local.datastore.BlurTargetGender
import com.puretube.app.data.local.datastore.BlurVisualStyle

/**
 * Zero-flash pre-blur: runs inside Coil's background pipeline BEFORE the bitmap
 * reaches the UI, so unblurred pixels never render for even one frame.
 */
class HaramBlurCoilTransformation(
    private val engine: HaramBlurEngine,
    private val targetGender: BlurTargetGender,
    private val visualStyle: BlurVisualStyle,
    private val sensitivity: Float,
    private val blurIntensity: Float,
    private val isManuallyPeeked: Boolean
) : Transformation() {

    // v1.3: the key carries a model revision token. Pre-v1.3 builds cached
    // UNBLURRED thumbnails under the old key (the engine had no model and
    // returned zero regions); the new token invalidates those entries so a
    // face thumbnail is never served unblurred from the old cache. The
    // effective target (all-faces fallback when no gender model is loaded)
    // is part of the key too.
    override val cacheKey: String =
        "haramblur_v13model_${com.puretube.app.domain.HaramBlurPolicy.effectiveTarget(targetGender, engine.hasGenderModel)}_${visualStyle}_${sensitivity}_${blurIntensity}_$isManuallyPeeked"

    override suspend fun transform(input: Bitmap, size: Size): Bitmap {
        if (isManuallyPeeked) return input
        val regions = runCatching {
            engine.detectRegionsToBlur(
                sourceBitmap = input,
                targetGender = targetGender,
                sensitivity = sensitivity
            )
        }.getOrDefault(emptyList())
        if (regions.isEmpty()) return input

        val output = input.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(output)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        for (region in regions) {
            val targetRect = Rect(
                (region.normalizedRect.left * output.width).toInt().coerceIn(0, output.width - 1),
                (region.normalizedRect.top * output.height).toInt().coerceIn(0, output.height - 1),
                (region.normalizedRect.right * output.width).toInt().coerceIn(1, output.width),
                (region.normalizedRect.bottom * output.height).toInt().coerceIn(1, output.height)
            )
            if (targetRect.width() <= 2 || targetRect.height() <= 2) continue

            when (visualStyle) {
                BlurVisualStyle.SOLID_DIM_CURTAIN -> {
                    paint.color = Color.parseColor("#F2121212")
                    canvas.drawRoundRect(RectF(targetRect), 12f, 12f, paint)
                }
                BlurVisualStyle.PIXELATE_MOSAIC -> {
                    val blockDivisor = (blurIntensity / 3f).toInt().coerceIn(6, 24)
                    val tinyW = (targetRect.width() / blockDivisor).coerceAtLeast(2)
                    val tinyH = (targetRect.height() / blockDivisor).coerceAtLeast(2)
                    val subCrop = Bitmap.createBitmap(
                        output, targetRect.left, targetRect.top, targetRect.width(), targetRect.height()
                    )
                    val pixelated = Bitmap.createScaledBitmap(subCrop, tinyW, tinyH, false)
                    paint.isFilterBitmap = false
                    canvas.drawBitmap(pixelated, null, targetRect, paint)
                }
                BlurVisualStyle.FROSTED_GAUSSIAN -> {
                    val scaleDown = (blurIntensity / 2.2f).toInt().coerceIn(8, 28)
                    val tinyW = (targetRect.width() / scaleDown).coerceAtLeast(2)
                    val tinyH = (targetRect.height() / scaleDown).coerceAtLeast(2)
                    val subCrop = Bitmap.createBitmap(
                        output, targetRect.left, targetRect.top, targetRect.width(), targetRect.height()
                    )
                    val downscaled = Bitmap.createScaledBitmap(subCrop, tinyW, tinyH, true)
                    paint.isFilterBitmap = true
                    canvas.drawBitmap(downscaled, null, targetRect, paint)
                }
            }
        }
        return output
    }
}
