package com.puretube.app.ui.screens.playlist

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.puretube.app.PureTubeApp
import com.puretube.app.data.local.entity.PlaylistVisibility
import com.puretube.app.data.remote.extractor.FeedItem
import com.puretube.app.data.remote.extractor.RemotePlaylistPayload
import com.puretube.app.ui.components.FeedDisplayControls
import com.puretube.app.ui.components.FeedListStandalone
import com.puretube.app.ui.components.VideoCardItem
import com.puretube.app.ui.theme.PureAccent
import com.puretube.app.ui.theme.PureTextSecondary
import kotlinx.coroutines.launch

/** Local playlist detail: play, per-video menu, remove, rename, visibility, delete, export, share. */
@Composable
fun LocalPlaylistScreen(
    playlistId: Long,
    onVideoClick: (String) -> Unit,
    onChannelClick: (String) -> Unit,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val app = context.applicationContext as PureTubeApp
    val scope = rememberCoroutineScope()
    val playlists by app.database.playlistDao().observePlaylists().collectAsState(initial = emptyList())
    val playlist = playlists.firstOrNull { it.playlistId == playlistId }
    val videos by app.database.playlistDao().observeVideos(playlistId).collectAsState(initial = emptyList())
    var renameDraft by remember(playlistId) { mutableStateOf("") }
    var status by remember(playlistId) { mutableStateOf("") }
    var confirmDelete by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .padding(12.dp)
    ) {
        Row {
            TextButton(onClick = onBack) { Text("Back") }
            Text(
                playlist?.name ?: "Playlist",
                color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(top = 8.dp)
            )
            playlist?.let {
                Text(
                    " · ${it.visibility.name}",
                    color = PureAccent, fontSize = 13.sp,
                    modifier = Modifier.padding(top = 14.dp)
                )
                if (it.youtubePlaylistId != null) {
                    Text(
                        " · YouTube • synced",
                        color = PureTextSecondary, fontSize = 13.sp,
                        modifier = Modifier.padding(top = 14.dp)
                    )
                }
            }
        }
        if (status.isNotBlank()) {
            Text(status, color = PureAccent, fontSize = 13.sp)
        }
        playlist?.let { pl ->
            Row {
                OutlinedTextField(
                    value = renameDraft,
                    onValueChange = { renameDraft = it },
                    placeholder = { Text("Rename to…") },
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
                Button(onClick = {
                    val name = renameDraft.trim()
                    if (name.isNotBlank()) {
                        scope.launch {
                            runCatching { app.database.playlistDao().rename(pl.playlistId, name) }
                            renameDraft = ""
                            status = "Renamed"
                        }
                    }
                }) { Text("Rename", fontSize = 12.sp) }
            }
            Spacer(modifier = Modifier.height(6.dp))
            Row {
                PlaylistVisibility.entries.forEach { vis ->
                    TextButton(onClick = {
                        scope.launch {
                            runCatching { app.database.playlistDao().setVisibility(pl.playlistId, vis) }
                        }
                    }) {
                        Text(
                            if (vis == pl.visibility) "● ${vis.name}" else vis.name,
                            fontSize = 12.sp
                        )
                    }
                }
            }
            Row {
                TextButton(onClick = {
                    // Play: enqueue everything, open the first video.
                    val items = videos.map { it.toFeedItem() }
                    items.drop(1).forEach { app.playerController.enqueue(it) }
                    items.firstOrNull()?.let { onVideoClick(it.videoId) }
                }) { Text("Play all", fontSize = 13.sp) }
                TextButton(onClick = {
                    val text = videos.joinToString("\n") {
                        "https://www.youtube.com/watch?v=${it.videoId}"
                    }
                    runCatching {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setPrimaryClip(ClipData.newPlainText("PureTube playlist", text))
                        status = "Playlist copied as a list of links"
                    }
                }) { Text("Export (copy links)", fontSize = 13.sp) }
                if (pl.visibility != PlaylistVisibility.PRIVATE) {
                    TextButton(onClick = {
                        val text = buildString {
                            appendLine(pl.name)
                            videos.forEach { appendLine("https://www.youtube.com/watch?v=${it.videoId}") }
                        }
                        runCatching {
                            val intent = Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_TEXT, text)
                            }
                            context.startActivity(Intent.createChooser(intent, "Share playlist"))
                        }
                    }) { Text("Share", fontSize = 13.sp) }
                }
                TextButton(onClick = { confirmDelete = true }) { Text("Delete playlist", fontSize = 13.sp) }
            }
        }
        if (videos.isEmpty()) {
            Text("This playlist is empty.", color = PureTextSecondary, fontSize = 14.sp)
        }
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(12.dp)
        ) {
            item { FeedDisplayControls() }
            items(videos, key = { it.videoId }) { video ->
                Column {
                    VideoCardItem(
                        item = video.toFeedItem(),
                        onClick = { onVideoClick(video.videoId) },
                        onChannelClick = onChannelClick
                    )
                    TextButton(onClick = {
                        scope.launch {
                            runCatching { app.database.playlistDao().removeVideo(playlistId, video.videoId) }
                        }
                    }) { Text("Remove from playlist", fontSize = 12.sp) }
                }
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete playlist?") },
            text = { Text("This removes the playlist and its video list. Videos themselves are unaffected.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    scope.launch {
                        runCatching {
                            app.database.playlistDao().clearVideos(playlistId)
                            app.database.playlistDao().deletePlaylist(playlistId)
                        }
                        onBack()
                    }
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } }
        )
    }
}

/** Bookmarked remote playlist: contents via the extractor, play + per-video menu. */
@Composable
fun RemotePlaylistScreen(
    playlistUrl: String,
    onVideoClick: (String) -> Unit,
    onChannelClick: (String) -> Unit,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val app = context.applicationContext as PureTubeApp
    val prefs by app.preferences.state.collectAsState(initial = app.currentPrefs)
    var payload by remember(playlistUrl) { mutableStateOf<RemotePlaylistPayload?>(null) }
    var status by remember(playlistUrl) { mutableStateOf("Loading playlist…") }

    LaunchedEffect(playlistUrl) {
        app.streamRepository.fetchRemotePlaylist(playlistUrl)
            .onSuccess { payload = it; status = "" }
            .onFailure { e -> status = "Could not load this playlist: ${com.puretube.app.domain.ErrorText.describe(e, 400)}" }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .padding(12.dp)
    ) {
        Row {
            TextButton(onClick = onBack) { Text("Back") }
            Text(
                payload?.title ?: "Playlist",
                color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(top = 8.dp)
            )
        }
        payload?.channelName?.takeIf { it.isNotBlank() }?.let {
            Text(it, color = PureTextSecondary, fontSize = 13.sp)
        }
        if (status.isNotBlank()) {
            Spacer(modifier = Modifier.height(8.dp))
            if (payload == null) CircularProgressIndicator()
            Text(status, color = PureTextSecondary, fontSize = 14.sp, lineHeight = 20.sp)
        }
        payload?.let { loaded ->
            Text(
                "${loaded.videos.size} videos" +
                    (if (loaded.streamCount > loaded.videos.size) " of ${loaded.streamCount}" else ""),
                color = PureTextSecondary, fontSize = 13.sp
            )
            loaded.warning?.takeIf { it.isNotBlank() }?.let { warning ->
                Text(warning, color = PureTextSecondary, fontSize = 12.sp, lineHeight = 17.sp)
            }
            if (loaded.videos.isEmpty()) {
                Text(
                    "This playlist loaded but returned no videos.",
                    color = PureTextSecondary, fontSize = 14.sp
                )
            }
            Row {
                TextButton(onClick = {
                    if (loaded.videos.isEmpty()) {
                        app.playerController.postNotice("No videos to play yet")
                    } else {
                        loaded.videos.drop(1).forEach { app.playerController.enqueue(it) }
                        app.playerController.postNotice("Playing playlist (${loaded.videos.size} videos)")
                        loaded.videos.firstOrNull()?.let { onVideoClick(it.videoId) }
                    }
                }) { Text("Play all", fontSize = 13.sp) }
                TextButton(onClick = {
                    loaded.videos.forEach { app.playerController.enqueue(it) }
                    if (loaded.videos.isNotEmpty()) {
                        app.playerController.postNotice("Queued ${loaded.videos.size} videos")
                    }
                }) { Text("Queue all", fontSize = 13.sp) }
            }
            FeedDisplayControls()
            FeedListStandalone(
                items = loaded.videos,
                layout = prefs.feedLayout,
                scale = prefs.feedThumbnailScale,
                columns = prefs.gridColumns,
                onVideoClick = onVideoClick,
                onChannelClick = onChannelClick,
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}

private fun com.puretube.app.data.local.entity.PlaylistVideoCrossRef.toFeedItem() = FeedItem(
    videoId = videoId,
    title = title,
    channelName = channelName,
    channelId = "",
    thumbnailUrl = thumbnailUrl,
    durationSeconds = durationSeconds,
    url = "https://www.youtube.com/watch?v=$videoId"
)
