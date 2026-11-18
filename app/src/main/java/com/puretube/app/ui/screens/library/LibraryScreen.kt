package com.puretube.app.ui.screens.library

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import com.puretube.app.data.local.entity.LocalPlaylistEntity
import com.puretube.app.data.remote.extractor.FeedItem
import com.puretube.app.ui.components.VideoCardItem
import com.puretube.app.ui.theme.PureBorder
import com.puretube.app.ui.theme.PureTextSecondary
import kotlinx.coroutines.launch

/** Library: history with progress, local playlists, and offline import/export. */
@Composable
fun LibraryScreen(onVideoClick: (String) -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as PureTubeApp
    val scope = rememberCoroutineScope()
    val history by app.database.watchProgressDao().observeHistory()
        .collectAsState(initial = emptyList())
    val playlists by app.database.playlistDao().observePlaylists()
        .collectAsState(initial = emptyList())
    val subscriptions by app.database.subscriptionDao().observeAll()
        .collectAsState(initial = emptyList())
    var newPlaylistName by remember { mutableStateOf("") }
    var importText by remember { mutableStateOf("") }
    var importStatus by remember { mutableStateOf("Paste a NewPipe JSON export or a YouTube Takeout CSV below, then tap Import. Everything is parsed on-device.") }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
        contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Text("Library", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        }
        item {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, PureBorder, RoundedCornerShape(10.dp))
                    .padding(12.dp)
            ) {
                Text("Local playlists", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                Spacer(modifier = Modifier.height(6.dp))
                if (playlists.isEmpty()) {
                    Text("No playlists yet.", color = PureTextSecondary, fontSize = 13.sp)
                } else {
                    playlists.forEach { playlist ->
                        Text("- ${playlist.name}", color = Color.White, fontSize = 14.sp)
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = newPlaylistName,
                        onValueChange = { newPlaylistName = it },
                        placeholder = { Text("Playlist name") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    Button(onClick = {
                        val name = newPlaylistName.trim()
                        if (name.isNotBlank()) {
                            scope.launch {
                                app.database.playlistDao().createPlaylist(
                                    LocalPlaylistEntity(name = name, createdAtEpochMs = System.currentTimeMillis())
                                )
                            }
                            newPlaylistName = ""
                        }
                    }) { Text("Create") }
                }
            }
        }
        item {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, PureBorder, RoundedCornerShape(10.dp))
                    .padding(12.dp)
            ) {
                Text("Import / export subscriptions", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                Spacer(modifier = Modifier.height(6.dp))
                Text(importStatus, color = PureTextSecondary, fontSize = 13.sp, lineHeight = 18.sp)
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = importText,
                    onValueChange = { importText = it },
                    placeholder = { Text("Paste NewPipe JSON or Takeout CSV") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 3,
                    maxLines = 6
                )
                Spacer(modifier = Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = {
                        val parsed = runCatching { app.importExportManager.parseAny(importText) }
                            .getOrDefault(emptyList())
                        scope.launch {
                            if (parsed.isNotEmpty()) {
                                app.database.subscriptionDao().upsertAll(parsed)
                            }
                            importStatus = if (parsed.isEmpty()) {
                                "Nothing imported - the pasted text did not match a supported format."
                            } else {
                                "Imported ${parsed.size} subscriptions."
                            }
                        }
                    }) { Text("Import") }
                    TextButton(onClick = {
                        val json = app.importExportManager.exportNewPipeJson(subscriptions)
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setPrimaryClip(ClipData.newPlainText("PureTube subscriptions", json))
                        importStatus = "Exported ${subscriptions.size} subscriptions to the clipboard as NewPipe JSON."
                    }) { Text("Export to clipboard") }
                    TextButton(onClick = {
                        scope.launch { app.database.watchProgressDao().clearAll() }
                    }) { Text("Clear history") }
                }
            }
        }
        item {
            Text("History", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Medium)
        }
        if (history.isEmpty()) {
            item {
                Text("Watched videos will appear here with resume progress.", color = PureTextSecondary, fontSize = 13.sp)
            }
        }
        items(history, key = { it.videoId }) { progress ->
            VideoCardItem(
                item = FeedItem(
                    videoId = progress.videoId,
                    title = progress.title,
                    channelName = progress.channelName,
                    channelId = progress.channelId,
                    thumbnailUrl = progress.thumbnailUrl,
                    durationSeconds = if (progress.durationMs > 0) progress.durationMs / 1000 else 0L,
                    url = "https://www.youtube.com/watch?v=${progress.videoId}"
                ),
                progressFraction = progress.progressFraction,
                onClick = { onVideoClick(progress.videoId) }
            )
        }
    }
}
