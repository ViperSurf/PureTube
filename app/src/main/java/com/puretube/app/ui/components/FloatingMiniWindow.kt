package com.puretube.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.session.MediaController
import androidx.media3.ui.PlayerView
import com.puretube.app.PureTubeApp
import com.puretube.app.domain.FloatingWindowMath
import com.puretube.app.playback.PlayerUiState
import com.puretube.app.ui.theme.PureBorder
import kotlin.math.roundToInt

/**
 * In-app floating mini window (v1.5 redesign): JUST the video.
 *
 * No overlays at all — no pause button, no S/M/L sizes, no Expand, no X.
 * Every control lives in the bottom dock (play/pause, mute, seek, queue),
 * and the dock's "Mini window" state button is the show/hide control:
 * hiding the window keeps playback running in the dock.
 *
 * The window itself:
 * - Drag the video to MOVE it.
 * - Drag UP on it (past ~90 dp) to expand back to the full player; a
 *   simple tap expands too.
 * - Resize DYNAMICALLY, PiP-style: pinch with two fingers, or drag the
 *   subtle grip dot at the bottom-right corner. Width is clamped via
 *   [FloatingWindowMath]; the height follows from the fixed 16:9 ratio.
 *
 * Bound to the SAME MediaController as the full player — the surface
 * moves, no second player is created. In audio-only mode it shows the
 * audio placeholder, never video.
 */
@Composable
fun FloatingMiniWindow(
    controller: MediaController?,
    state: PlayerUiState,
    onExpand: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (state.title.isBlank() || controller == null) return
    val context = LocalContext.current
    val app = context.applicationContext as PureTubeApp
    val density = LocalDensity.current
    val screenWidthDp = LocalConfiguration.current.screenWidthDp.toFloat()

    var offsetX by remember { mutableFloatStateOf(0f) }
    var offsetY by remember { mutableFloatStateOf(0f) }
    var widthDp by remember {
        mutableStateOf(FloatingWindowMath.clampWidthDp(200f, screenWidthDp))
    }
    var totalUpDragPx by remember { mutableFloatStateOf(0f) }

    // Move + pinch-resize + drag-up-to-expand on the video itself.
    val moveAndResize = Modifier
        .pointerInput(Unit) {
            detectTapGestures(onTap = { onExpand() })
        }
        .pointerInput(screenWidthDp) {
            val expandThresholdPx = 90.dp.toPx()
            detectTransformGestures(
                onGesture = { _, pan, zoom, _ ->
                    if (zoom != 1f) {
                        widthDp = FloatingWindowMath.resizedWidthDp(
                            currentDp = widthDp,
                            zoom = zoom,
                            screenWidthDp = screenWidthDp
                        )
                    } else {
                        offsetX += pan.x
                        offsetY += pan.y
                        totalUpDragPx += pan.y
                    }
                },
                onGestureEnd = {
                    if (totalUpDragPx < -expandThresholdPx) onExpand()
                    totalUpDragPx = 0f
                }
            )
        }

    Box(modifier = modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .offset { IntOffset(offsetX.roundToInt(), offsetY.roundToInt()) }
                .padding(12.dp)
                .width(widthDp.dp)
                .aspectRatio(16f / 9f)
                .clip(RoundedCornerShape(10.dp))
                .background(Color.Black)
                .border(1.dp, PureBorder, RoundedCornerShape(10.dp))
                .onGloballyPositioned { coords ->
                    // PiP sourceRectHint tracks the visible video view.
                    runCatching {
                        val pos = coords.positionInWindow()
                        app.pipSourceRectHint = android.graphics.Rect(
                            pos.x.toInt(), pos.y.toInt(),
                            (pos.x + coords.size.width).toInt(),
                            (pos.y + coords.size.height).toInt()
                        )
                    }
                }
                .then(moveAndResize)
        ) {
            if (state.audioOnly) {
                // Audio-only: placeholder, NEVER a video surface.
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color(0xFF0D0D0D))
                ) {
                    Text(
                        text = "Audio Only",
                        color = Color(0xFFFFB300),
                        fontSize = 12.sp,
                        modifier = Modifier.align(Alignment.Center)
                    )
                }
            } else {
                AndroidView(
                    factory = { ctx ->
                        PlayerView(ctx).apply {
                            useController = false
                            setBackgroundColor(android.graphics.Color.BLACK)
                        }
                    },
                    update = { view ->
                        if (view.player !== controller) view.player = controller
                    },
                    onRelease = { view -> view.player = null },
                    modifier = Modifier.fillMaxSize()
                )
            }
            // Resize grip: a subtle dot at the bottom-right corner.
            // Dragging it resizes the window (it is a grip, not a button).
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(5.dp)
                    .size(14.dp)
                    .clip(CircleShape)
                    .background(Color(0x66FFFFFF))
                    .border(1.dp, Color(0xAAFFFFFF), CircleShape)
                    .pointerInput(screenWidthDp) {
                        detectDragGestures { change, dragAmount ->
                            change.consume()
                            widthDp = FloatingWindowMath.resizedWidthDp(
                                currentDp = widthDp,
                                dragDeltaDp = dragAmount.x / density.density,
                                screenWidthDp = screenWidthDp
                            )
                        }
                    }
            )
        }
    }
}
