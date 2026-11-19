package com.puretube.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.session.MediaController
import androidx.media3.ui.PlayerView
import com.puretube.app.playback.PlayerUiState
import com.puretube.app.ui.theme.PureBorder
import kotlin.math.roundToInt

/**
 * In-app floating mini window (v1.2, controls v1.3): a small draggable
 * 16:9 video surface over the current screen, bound to the SAME
 * MediaController as the full player — the surface moves, no second
 * player is created. v1.3 adds S/M/L resize, an explicit Expand, and an
 * explicit Close that COLLAPSES to the dock (playback continues) instead
 * of stopping. In audio-only mode it shows the audio placeholder, never
 * video. The full player screen must be popped before this shows (a
 * PlayerView can only attach to one surface at a time); on expand/close
 * the view detaches in onRelease.
 */
@Composable
fun FloatingMiniWindow(
    controller: MediaController?,
    state: PlayerUiState,
    onTogglePlayPause: () -> Unit,
    onExpand: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (state.title.isBlank() || controller == null) return
    var offsetX by remember { mutableFloatStateOf(0f) }
    var offsetY by remember { mutableFloatStateOf(0f) }
    // v1.4: live position for the compact seek slider.
    var positionMs by remember { mutableStateOf(0L) }
    var durationMs by remember { mutableStateOf(0L) }
    androidx.compose.runtime.LaunchedEffect(controller) {
        while (true) {
            runCatching {
                positionMs = controller.currentPosition.coerceAtLeast(0L)
                durationMs = controller.duration.coerceAtLeast(0L)
            }
            kotlinx.coroutines.delay(500L)
        }
    }
    // S / M / L sizes (v1.3)
    var sizeIndex by remember { mutableStateOf(1) }
    val widths = listOf(140.dp, 184.dp, 264.dp)
    val sizeLabels = listOf("S", "M", "L")
    val context = LocalContext.current

    Box(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .offset { IntOffset(offsetX.roundToInt(), offsetY.roundToInt()) }
                .padding(12.dp)
                .width(widths[sizeIndex])
                .clip(RoundedCornerShape(10.dp))
                .background(Color(0xFF101010))
                .border(1.dp, PureBorder, RoundedCornerShape(10.dp))
                .pointerInput(Unit) {
                    detectDragGestures { change, dragAmount ->
                        change.consume()
                        offsetX += dragAmount.x
                        offsetY += dragAmount.y
                    }
                }
        ) {
            if (state.audioOnly) {
                // Audio-only: placeholder, NEVER a video surface.
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(16f / 9f)
                        .background(Color(0xFF0D0D0D))
                        .clickable { onExpand() }
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
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(16f / 9f)
                        .clickable { onExpand() }
                )
            }
            if (durationMs > 0) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 6.dp)
                ) {
                    Text(
                        text = "${positionMs / 1000 / 60}:${"%02d".format(positionMs / 1000 % 60)}",
                        color = Color(0xFFAAAAAA), fontSize = 9.sp
                    )
                    androidx.compose.material3.Slider(
                        value = (positionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f),
                        onValueChange = { frac ->
                            runCatching { controller.seekTo((frac * durationMs).toLong()) }
                        },
                        modifier = Modifier
                            .weight(1f)
                            .padding(horizontal = 4.dp)
                    )
                    Text(
                        text = "${durationMs / 1000 / 60}:${"%02d".format(durationMs / 1000 % 60)}",
                        color = Color(0xFFAAAAAA), fontSize = 9.sp
                    )
                }
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 2.dp)
            ) {
                IconButton(onClick = onTogglePlayPause) {
                    Icon(
                        imageVector = if (state.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        contentDescription = if (state.isPlaying) "Pause" else "Play",
                        tint = Color.White
                    )
                }
                Text(
                    text = state.title,
                    color = Color.White,
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .weight(1f)
                        .clickable { onExpand() }
                )
                IconButton(onClick = { sizeIndex = (sizeIndex + 1) % widths.size }) {
                    Text(sizeLabels[sizeIndex], color = Color.White, fontSize = 12.sp)
                }
                IconButton(onClick = onExpand) {
                    Text("Expand", color = Color.White, fontSize = 11.sp)
                }
                IconButton(onClick = onClose) {
                    Icon(
                        imageVector = Icons.Filled.Close,
                        contentDescription = "Close to dock (playback continues)",
                        tint = Color.White
                    )
                }
            }
        }
    }
}
