package com.puretube.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.puretube.app.playback.PlayerUiState
import com.puretube.app.ui.theme.PureBorder

/** Swipe-down style docked mini player shown above the tab bar. */
@Composable
fun MiniPlayerDock(
    state: PlayerUiState,
    onTogglePlayPause: () -> Unit,
    onOpenPlayer: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (state.title.isBlank()) return
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .background(Color(0xFF0D0D0D))
            .border(1.dp, PureBorder, RoundedCornerShape(10.dp))
            .clickable { onOpenPlayer() }
            .padding(horizontal = 12.dp, vertical = 6.dp)
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
        Spacer(modifier = Modifier.width(4.dp))
        IconButton(onClick = onClose) {
            Icon(
                imageVector = Icons.Filled.Close,
                contentDescription = "Close player",
                tint = Color.White
            )
        }
    }
}
