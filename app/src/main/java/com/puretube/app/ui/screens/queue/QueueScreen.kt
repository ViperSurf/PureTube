package com.puretube.app.ui.screens.queue

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.puretube.app.PureTubeApp
import com.puretube.app.ui.theme.PureAccent
import com.puretube.app.ui.theme.PureTextSecondary

/**
 * The playback queue, made visible (v1.2): ordered list with the current
 * item highlighted, tap-to-play, remove, up/down reorder, clear all, and an
 * empty state that explains how items get here.
 */
@Composable
fun QueueScreen(
    onVideoClick: (String) -> Unit,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val app = context.applicationContext as PureTubeApp
    val controller = app.playerController
    val queue by controller.queue.collectAsState()
    val playerState by controller.state.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .padding(12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("Back") }
            Text(
                "Up Next — Queue (${queue.size})",
                color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.weight(1f))
            if (queue.isNotEmpty()) {
                TextButton(onClick = { controller.clearQueue() }) { Text("Clear all", fontSize = 12.sp) }
            }
        }
        if (playerState.currentVideoId.isNotBlank()) {
            Text(
                "Now playing: ${playerState.title}",
                color = PureTextSecondary, fontSize = 13.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis
            )
        }
        Spacer(modifier = Modifier.height(8.dp))
        if (queue.isEmpty()) {
            Text(
                text = "The queue is empty.\n\nAdd videos with the ⋮ menu on any video (\"Add to Queue\"), " +
                    "the Queue button on the player, or \"Queue all\" on a playlist. " +
                    "Queued videos play automatically after the current one.",
                color = PureTextSecondary, fontSize = 14.sp, lineHeight = 21.sp,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color(0xFF101010))
                    .padding(14.dp)
            )
        } else {
            LazyColumn(
                contentPadding = PaddingValues(vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                itemsIndexed(queue, key = { _, item -> item.videoId }) { index, item ->
                    val isCurrent = item.videoId == playerState.currentVideoId
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (isCurrent) Color(0xFF1D2B1D) else Color(0xFF0D0D0D))
                            .clickable { onVideoClick(item.videoId) }
                            .padding(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "${index + 1}",
                            color = if (isCurrent) PureAccent else PureTextSecondary,
                            fontSize = 13.sp,
                            modifier = Modifier.width(22.dp)
                        )
                        AsyncImage(
                            model = item.thumbnailUrl,
                            contentDescription = item.title,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .width(96.dp)
                                .height(54.dp)
                                .clip(RoundedCornerShape(6.dp))
                                .background(Color(0xFF181818))
                        )
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .padding(start = 10.dp)
                        ) {
                            Text(
                                item.title + if (isCurrent) "  • playing" else "",
                                color = Color.White, fontSize = 13.sp,
                                maxLines = 2, overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                item.channelName, color = PureTextSecondary, fontSize = 11.sp,
                                maxLines = 1, overflow = TextOverflow.Ellipsis
                            )
                        }
                        TextButton(onClick = { controller.moveInQueue(item.videoId, -1) }) {
                            Text("↑", fontSize = 14.sp)
                        }
                        TextButton(onClick = { controller.moveInQueue(item.videoId, +1) }) {
                            Text("↓", fontSize = 14.sp)
                        }
                        TextButton(onClick = { controller.removeFromQueue(item.videoId) }) {
                            Text("✕", fontSize = 13.sp)
                        }
                    }
                }
            }
        }
    }
}
