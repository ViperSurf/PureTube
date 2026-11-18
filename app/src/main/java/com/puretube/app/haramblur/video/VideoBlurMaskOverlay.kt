package com.puretube.app.haramblur.video

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import com.puretube.app.data.local.datastore.BlurVisualStyle
import com.puretube.app.haramblur.engine.DetectedBlurRegion

/**
 * Hardware-accelerated Compose Canvas overlay drawing blur / mosaic / curtain
 * masks over the tracked regions. While [initialFrameCleared] is false and
 * [zeroFlashEnabled] is true, an opaque curtain covers the whole surface so no
 * un-scanned frame can flash through.
 */
@Composable
fun VideoBlurMaskOverlay(
    regions: List<DetectedBlurRegion>,
    visualStyle: BlurVisualStyle,
    initialFrameCleared: Boolean,
    zeroFlashEnabled: Boolean,
    modifier: Modifier = Modifier
) {
    Box(modifier = modifier.fillMaxSize()) {
        if (zeroFlashEnabled && !initialFrameCleared) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black)
            )
        }
        Canvas(modifier = Modifier.fillMaxSize()) {
            for (region in regions) {
                val left = region.normalizedRect.left * size.width
                val top = region.normalizedRect.top * size.height
                val w = region.normalizedRect.width * size.width
                val h = region.normalizedRect.height * size.height
                when (visualStyle) {
                    BlurVisualStyle.SOLID_DIM_CURTAIN -> drawRoundRect(
                        color = Color(0xF2121212),
                        topLeft = Offset(left, top),
                        size = Size(w, h),
                        cornerRadius = CornerRadius(20f, 20f)
                    )
                    BlurVisualStyle.PIXELATE_MOSAIC -> {
                        drawRoundRect(
                            color = Color(0xE61A1A1A),
                            topLeft = Offset(left, top),
                            size = Size(w, h),
                            cornerRadius = CornerRadius(20f, 20f)
                        )
                        val block = 18f
                        var x = left
                        while (x < left + w) {
                            var y = top
                            while (y < top + h) {
                                drawRect(
                                    color = Color(0x66222222),
                                    topLeft = Offset(x, y),
                                    size = Size(block - 2f, block - 2f)
                                )
                                y += block
                            }
                            x += block
                        }
                    }
                    BlurVisualStyle.FROSTED_GAUSSIAN -> drawRoundRect(
                        color = Color(0xCC2A2A2A),
                        topLeft = Offset(left, top),
                        size = Size(w, h),
                        cornerRadius = CornerRadius(20f, 20f)
                    )
                }
            }
        }
    }
}
