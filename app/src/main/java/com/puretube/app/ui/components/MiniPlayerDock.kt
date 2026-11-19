package com.puretube.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PictureInPictureAlt
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.puretube.app.playback.PlayerUiState
import com.puretube.app.ui.theme.PureAccent
import com.puretube.app.ui.theme.PureBorder

/**
 * Docked mini player shown above the tab bar (v1.2): play/pause, title,
 * live progress, mute (RED when muted), Queue (N) badge button, a
 * float-to-window button, and close. Tapping the title reopens the player.
 */
private fun fmtDockMs(ms: Long): String {
    val totalSec = (ms / 1000).coerceAtLeast(0)
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val sec = totalSec % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
}

@Composable
fun MiniPlayerDock(
    state: PlayerUiState,
    onTogglePlayPause: () -> Unit,
    onOpenPlayer: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    onToggleMute: (() -> Unit)? = null,
    onQueueClick: (() -> Unit)? = null,
    queueCount: Int = 0,
    onFloatClick: (() -> Unit)? = null,
    progressFraction: Float = 0f,
    positionMs: Long = 0L,
    durationMs: Long = 0L,
    onSeek: ((Long) -> Unit)? = null
) {
    if (state.title.isBlank()) return
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(Color(0xFF0D0D0D))
            .border(1.dp, PureBorder, RoundedCornerShape(10.dp))
            .padding(horizontal = 8.dp, vertical = 2.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onOpenPlayer() }
                .padding(vertical = 2.dp)
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
                fontSize = 14.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            if (state.audioOnly) {
                Text(
                    text = "Audio Only",
                    color = Color(0xFFFFB300),
                    fontSize = 11.sp,
                    modifier = Modifier.padding(horizontal = 4.dp)
                )
            }
            if (onToggleMute != null) {
                IconButton(onClick = onToggleMute) {
                    Icon(
                        imageVector = if (state.isMuted) Icons.Filled.VolumeOff else Icons.Filled.VolumeUp,
                        contentDescription = if (state.isMuted) "Unmute" else "Mute",
                        tint = if (state.isMuted) Color(0xFFFF5252) else Color.White
                    )
                }
            }
            if (onQueueClick != null) {
                TextButton(onClick = onQueueClick) {
                    Text("Queue ($queueCount)", fontSize = 12.sp, color = PureAccent)
                }
            }
            if (onFloatClick != null) {
                IconButton(onClick = onFloatClick) {
                    Icon(
                        imageVector = Icons.Filled.PictureInPictureAlt,
                        contentDescription = "Floating window",
                        tint = Color.White
                    )
                }
            }
            IconButton(onClick = onClose) {
                Icon(
                    imageVector = Icons.Filled.Close,
                    contentDescription = "Close player",
                    tint = Color.White
                )
            }
        }
        if (onSeek != null && durationMs > 0) {
            // v1.4: live seek slider with position / duration.
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(fmtDockMs(positionMs), color = Color(0xFFAAAAAA), fontSize = 10.sp)
                androidx.compose.material3.Slider(
                    value = (positionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f),
                    onValueChange = { frac -> onSeek((frac * durationMs).toLong()) },
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 8.dp)
                )
                Text(fmtDockMs(durationMs), color = Color(0xFFAAAAAA), fontSize = 10.sp)
            }
        } else if (progressFraction > 0f) {
            LinearProgressIndicator(
                progress = { progressFraction.coerceIn(0f, 1f) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(3.dp),
                color = PureAccent
            )
            Spacer(modifier = Modifier.height(2.dp))
        }
        Box(modifier = Modifier.width(0.dp))
    }
}
