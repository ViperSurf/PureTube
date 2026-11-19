package com.puretube.app.haramblur.video

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import com.puretube.app.data.local.datastore.BlurStrength
import com.puretube.app.data.local.datastore.BlurVisualStyle
import com.puretube.app.domain.BlurStrengthMath
import com.puretube.app.domain.PixelRect
import com.puretube.app.domain.VideoRectMath
import com.puretube.app.haramblur.engine.DetectedBlurRegion

/**
 * Overlay drawing the HaramBlur masks over the playing video (v2.1).
 *
 * REACTIVITY (the v2.0 freeze fix): this composable collects the
 * sampler's single [VideoFrameSampler.frameState] StateFlow ITSELF —
 * there is exactly one state link between sampling and drawing, and it
 * lives here, not threaded through the player screen's parameters. Each
 * snapshot carries a strictly increasing sequence; the Canvas is keyed
 * by that sequence, so every published sample re-records the draw. The
 * drawn sequence is reported back into diagnostics: sampled vs drawn
 * sequences make any future freeze visible as numbers.
 *
 * DRAWING: regions arrive in video-frame [0,1] space and are mapped
 * through [videoRect] (the displayed picture rect for the active aspect
 * mode). PIXELATE/FROSTED draw REAL patches of the sampled frame from
 * the snapshot's immutable pixel-frame copy — the pixel frame's
 * resolution IS the blur strength (smaller frame = coarser mosaic), and
 * FROSTED adds a strength-scaled tint. In FULL_SCREEN detection mode a
 * snapshot with anything detected pixelates the entire video rect.
 * SOLID keeps the opaque curtain. While the zero-flash gate is armed
 * and the first frame is uncleared, an opaque curtain covers the whole
 * surface so no un-scanned frame can flash through.
 */
@Composable
fun VideoBlurMaskOverlay(
    sampler: VideoFrameSampler,
    visualStyle: BlurVisualStyle,
    blurStrength: BlurStrength,
    initialFrameCleared: Boolean,
    zeroFlashEnabled: Boolean,
    modifier: Modifier = Modifier,
    /** Displayed-video rect in container pixels; defaults to the full container. */
    videoRect: PixelRect? = null
) {
    val snapshot by sampler.frameState.collectAsState()
    SideEffect {
        sampler.noteOverlayDrawn(
            drawnSeq = snapshot.sequence,
            regionsDrawn = when {
                snapshot.fullScreenActive -> 1
                else -> snapshot.regions.size
            }
        )
    }
    Box(modifier = modifier.fillMaxSize()) {
        if (zeroFlashEnabled && !initialFrameCleared) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black)
            )
        }
        // Keyed by the sample sequence: a new snapshot ALWAYS re-records.
        key(snapshot.sequence) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val rect = videoRect ?: PixelRect(0f, 0f, size.width, size.height)
                val frame = snapshot.pixelFrame
                val frameImage = frame?.let {
                    runCatching { it.asImageBitmap() }.getOrNull()
                }
                if (snapshot.fullScreenActive) {
                    drawRegionPatch(
                        region = FULL_FRAME_REGION,
                        videoRect = rect,
                        frame = frame,
                        frameImage = frameImage,
                        visualStyle = visualStyle,
                        blurStrength = blurStrength,
                        cornerRadius = 0f
                    )
                } else {
                    for (region in snapshot.regions) {
                        drawRegionPatch(
                            region = region,
                            videoRect = rect,
                            frame = frame,
                            frameImage = frameImage,
                            visualStyle = visualStyle,
                            blurStrength = blurStrength,
                            cornerRadius = 20f
                        )
                    }
                }
            }
        }
    }
}

/** Synthetic whole-frame region for FULL_SCREEN mode. */
private val FULL_FRAME_REGION = DetectedBlurRegion(
    id = -1,
    normalizedRect = com.puretube.app.haramblur.engine.NormalizedRect(0f, 0f, 1f, 1f),
    classifiedGender = com.puretube.app.haramblur.engine.ClassifiedSubject.UNKNOWN_PERSON,
    confidence = 1f,
    timestampMs = 0L
)

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawRegionPatch(
    region: DetectedBlurRegion,
    videoRect: PixelRect,
    frame: android.graphics.Bitmap?,
    frameImage: androidx.compose.ui.graphics.ImageBitmap?,
    visualStyle: BlurVisualStyle,
    blurStrength: BlurStrength,
    cornerRadius: Float
) {
    val dst = VideoRectMath.mapRegion(region.normalizedRect, videoRect)
    if (dst.width <= 0f || dst.height <= 0f) return
    when (visualStyle) {
        BlurVisualStyle.SOLID_DIM_CURTAIN -> drawRoundRect(
            color = Color(0xF2121212),
            topLeft = Offset(dst.left, dst.top),
            size = Size(dst.width, dst.height),
            cornerRadius = CornerRadius(cornerRadius, cornerRadius)
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
                    // Frost tint, alpha scaled by the strength setting —
                    // the v2.0 fixed 0x4D tint was LIGHT-level and faces
                    // showed through.
                    val alpha = BlurStrengthMath.frostTintAlpha(blurStrength)
                    drawRect(
                        color = Color((alpha shl 24) or 0x9E9E9E),
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
                    cornerRadius = CornerRadius(cornerRadius, cornerRadius)
                )
            }
        }
    }
}
