package com.puretube.app.haramblur.video

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import com.puretube.app.data.local.datastore.BlurVisualStyle
import com.puretube.app.domain.PixelRect
import com.puretube.app.domain.VideoRectMath
import com.puretube.app.haramblur.engine.DetectedBlurRegion

/**
 * Overlay drawing the HaramBlur masks over the playing video (v2.0 rewrite).
 *
 * AUDIT FINDING (why in-video blur "did nothing" through v1.5): this
 * overlay never drew any part of the actual video frame. Every style was
 * a flat translucent dark box (FROSTED = solid #2A2A2A at 80% alpha;
 * PIXELATE = the same box plus decorative grid lines in another flat
 * colour). Against dark scenes the box was nearly invisible, and it
 * never read as "blur" at all. It also mapped regions by multiplying
 * normalized coordinates with the CONTAINER size, assuming the sampled
 * bitmap's picture filled the container edge-to-edge.
 *
 * v2.0:
 * - Regions arrive in video-frame [0,1] space (the sampler's TextureView
 *   is laid out at exactly the displayed-video rect, so its bitmap IS
 *   the video frame) and are mapped through [videoRect] — the actual
 *   displayed picture rect for the active aspect mode — via
 *   [VideoRectMath.mapRegion].
 * - PIXELATE and FROSTED draw REAL patches of the sampled frame: the
 *   sampler publishes a 128x72 pixel copy of each frame; the region's
 *   slice of it is upscaled into place (filtering OFF for mosaic blocks,
 *   LOW + a frost tint for the frosted style). SOLID keeps the curtain.
 * - [frameVersion] changes with every published frame so the Canvas
 *   redraws in step with sampling; [onRegionsDrawn] reports the drawn
 *   count back into sampler diagnostics.
 *
 * While [initialFrameCleared] is false and [zeroFlashEnabled] is true,
 * an opaque curtain covers the whole surface so no un-scanned frame can
 * flash through.
 */
@Composable
fun VideoBlurMaskOverlay(
    regions: List<DetectedBlurRegion>,
    visualStyle: BlurVisualStyle,
    initialFrameCleared: Boolean,
    zeroFlashEnabled: Boolean,
    modifier: Modifier = Modifier,
    /** Displayed-video rect in container pixels; defaults to the full container. */
    videoRect: PixelRect? = null,
    /** Latest low-res sampled frame for real pixel patches (may be null early). */
    pixelFrameProvider: () -> Bitmap? = { null },
    /** Increments with each published frame; collecting it drives redraws. */
    frameVersion: Long = 0L,
    /** Diagnostics callback: how many regions were drawn. */
    onRegionsDrawn: (Int) -> Unit = {}
) {
    SideEffect { onRegionsDrawn(regions.size) }
    Box(modifier = modifier.fillMaxSize()) {
        if (zeroFlashEnabled && !initialFrameCleared) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black)
            )
        }
        Canvas(modifier = Modifier.fillMaxSize()) {
            // Note: [frameVersion] is a parameter of the composable, so a
            // new sampled frame recomposes us and this Canvas redraws.
            val rect = videoRect ?: PixelRect(0f, 0f, size.width, size.height)
            val frame = pixelFrameProvider()
            val frameImage = frame?.let { runCatching { it.asImageBitmap() }.getOrNull() }
            for (region in regions) {
                val dst = VideoRectMath.mapRegion(region.normalizedRect, rect)
                if (dst.width <= 0f || dst.height <= 0f) continue
                when (visualStyle) {
                    BlurVisualStyle.SOLID_DIM_CURTAIN -> drawRoundRect(
                        color = Color(0xF2121212),
                        topLeft = Offset(dst.left, dst.top),
                        size = Size(dst.width, dst.height),
                        cornerRadius = CornerRadius(20f, 20f)
                    )
                    BlurVisualStyle.PIXELATE_MOSAIC,
                    BlurVisualStyle.FROSTED_GAUSSIAN -> {
                        if (frameImage != null && frame != null) {
                            val srcLeft = (region.normalizedRect.left * frame.width)
                                .toInt().coerceIn(0, frame.width - 1)
                            val srcTop = (region.normalizedRect.top * frame.height)
                                .toInt().coerceIn(0, frame.height - 1)
                            val srcRight = (region.normalizedRect.right * frame.width)
                                .toInt().coerceIn(srcLeft + 1, frame.width)
                            val srcBottom = (region.normalizedRect.bottom * frame.height)
                                .toInt().coerceIn(srcTop + 1, frame.height)
                            drawImage(
                                image = frameImage,
                                srcOffset = IntOffset(srcLeft, srcTop),
                                srcSize = IntSize(srcRight - srcLeft, srcBottom - srcTop),
                                dstOffset = IntOffset(dst.left.toInt(), dst.top.toInt()),
                                dstSize = IntSize(
                                    dst.width.toInt().coerceAtLeast(1),
                                    dst.height.toInt().coerceAtLeast(1)
                                ),
                                filterQuality = if (visualStyle == BlurVisualStyle.PIXELATE_MOSAIC) {
                                    FilterQuality.None
                                } else {
                                    FilterQuality.Low
                                }
                            )
                            if (visualStyle == BlurVisualStyle.FROSTED_GAUSSIAN) {
                                // Frost tint over the blurred patch.
                                drawRect(
                                    color = Color(0x4D9E9E9E),
                                    topLeft = Offset(dst.left, dst.top),
                                    size = Size(dst.width, dst.height)
                                )
                            }
                        } else {
                            // No frame published yet — solid fallback, never a leak.
                            drawRoundRect(
                                color = Color(0xF2121212),
                                topLeft = Offset(dst.left, dst.top),
                                size = Size(dst.width, dst.height),
                                cornerRadius = CornerRadius(20f, 20f)
                            )
                        }
                    }
                }
            }
        }
    }
}
