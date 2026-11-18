package com.puretube.app.ui.screens.library

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
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
import com.puretube.app.data.backup.PlaylistImportParsers
import com.puretube.app.data.local.entity.BookmarkedPlaylistEntity
import com.puretube.app.data.local.entity.LocalPlaylistEntity
import com.puretube.app.data.local.entity.PlaylistVideoCrossRef
import com.puretube.app.data.local.entity.PlaylistVisibility
import com.puretube.app.data.remote.extractor.FeedItem
import com.puretube.app.data.local.datastore.HistoryGroupMode
import com.puretube.app.domain.HistoryBuckets
import com.puretube.app.domain.HistoryDayGroups
import com.puretube.app.ui.components.VideoCardItem
import com.puretube.app.ui.theme.PureAccent
import com.puretube.app.ui.theme.PureBorder
import com.puretube.app.ui.theme.PureTextSecondary
import kotlinx.coroutines.launch

/**
 * Library (v1.1): bookmarked playlists, my playlists with visibility,
 * Watch Later, grouped history, and file-based import/export
 * (subscriptions / playlists / settings) with paste kept as a fallback.
 */
@Composable
fun LibraryScreen(
    onVideoClick: (String) -> Unit,
    onChannelClick: (String) -> Unit = {},
    onLocalPlaylistClick: (Long) -> Unit = {},
    onRemotePlaylistClick: (String) -> Unit = {},
    onQueueClick: () -> Unit = {}
) {
    val context = LocalContext.current
    val app = context.applicationContext as PureTubeApp
    val scope = rememberCoroutineScope()
    val prefs by app.preferences.state.collectAsState(initial = app.currentPrefs)
    val history by app.database.watchProgressDao().observeHistory()
        .collectAsState(initial = emptyList())
    val playlists by app.database.playlistDao().observePlaylists()
        .collectAsState(initial = emptyList())
    val bookmarks by app.database.bookmarkedPlaylistDao().observeAll()
        .collectAsState(initial = emptyList())
    val subscriptions by app.database.subscriptionDao().observeAll()
        .collectAsState(initial = emptyList())
    var newPlaylistName by remember { mutableStateOf("") }
    var newPlaylistVisibility by remember { mutableStateOf(PlaylistVisibility.PRIVATE) }
    var bookmarkUrl by remember { mutableStateOf("") }
    var importText by remember { mutableStateOf("") }
    var fileStatus by remember { mutableStateOf("") }
    var confirmClearSubs by remember { mutableStateOf(false) }

    fun readFile(uri: Uri): String? = runCatching {
        context.contentResolver.openInputStream(uri)?.bufferedReader()?.readText()
    }.getOrNull()

    fun writeFile(uri: Uri, text: String) {
        runCatching {
            context.contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { it.write(text) }
        }
    }

    val importSubsLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            val text = readFile(uri)
            scope.launch {
                val report = text?.let {
                    runCatching { app.importExportManager.parseAnyWithReport(it) }.getOrNull()
                }
                val parsed = report?.subscriptions.orEmpty()
                if (parsed.isNotEmpty()) app.database.subscriptionDao().upsertAll(parsed)
                fileStatus = when {
                    report == null || parsed.isEmpty() -> "No subscriptions found in that file."
                    report.skipped > 0 ->
                        "Imported ${parsed.size} subscriptions (${report.format}), skipped ${report.skipped} unrecognised rows."
                    else -> "Imported ${parsed.size} subscriptions (${report.format}) from file."
                }
            }
        }
    }
    val importPlaylistLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            val text = readFile(uri)
            scope.launch {
                val videos = text?.let { PlaylistImportParsers.parseAny(it) }.orEmpty()
                if (videos.isEmpty()) {
                    fileStatus = "No videos found in that file."
                } else {
                    val id = app.database.playlistDao().createPlaylist(
                        LocalPlaylistEntity(
                            name = "Imported playlist",
                            createdAtEpochMs = System.currentTimeMillis(),
                            visibility = PlaylistVisibility.PRIVATE
                        )
                    )
                    videos.forEachIndexed { index, video ->
                        app.database.playlistDao().addVideo(
                            PlaylistVideoCrossRef(
                                playlistId = id,
                                videoId = video.videoId,
                                title = video.title,
                                channelName = "",
                                thumbnailUrl = "",
                                durationSeconds = 0,
                                positionIndex = index
                            )
                        )
                    }
                    fileStatus = "Imported ${videos.size} videos into a new playlist."
                }
            }
        }
    }
    val exportSubsLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri != null) {
            writeFile(uri, app.importExportManager.exportNewPipeJson(subscriptions))
            fileStatus = "Exported ${subscriptions.size} subscriptions to file."
        }
    }

    val now = System.currentTimeMillis()
    val visibleHistory = HistoryBuckets.visibleItems(history, now, prefs.historyVisibleDays)
    val historyDayGroups = HistoryDayGroups.group(visibleHistory, now)
    val queue by app.playerController.queue.collectAsState()

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

        // ---- Bookmarked playlists ----
        item {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, PureBorder, RoundedCornerShape(10.dp))
                    .padding(12.dp)
            ) {
                Text("Bookmarked playlists", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                Spacer(modifier = Modifier.height(6.dp))
                if (bookmarks.isEmpty()) {
                    Text("No bookmarked playlists yet. Paste a YouTube playlist link below to save one.", color = PureTextSecondary, fontSize = 13.sp)
                } else {
                    bookmarks.forEach { bookmark ->
                        Row(modifier = Modifier.fillMaxWidth()) {
                            TextButton(onClick = { onRemotePlaylistClick(bookmark.url) }) {
                                Text(bookmark.title.ifBlank { bookmark.url }, fontSize = 14.sp)
                            }
                            Spacer(modifier = Modifier.weight(1f))
                            TextButton(onClick = {
                                scope.launch { app.database.bookmarkedPlaylistDao().delete(bookmark.url) }
                            }) { Text("Remove", fontSize = 12.sp) }
                        }
                    }
                }
                Spacer(modifier = Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = bookmarkUrl,
                        onValueChange = { bookmarkUrl = it },
                        placeholder = { Text("Playlist URL") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    Button(onClick = {
                        val url = bookmarkUrl.trim()
                        if (url.isBlank()) return@Button
                        scope.launch {
                            app.streamRepository.fetchRemotePlaylist(url)
                                .onSuccess { payload ->
                                    app.database.bookmarkedPlaylistDao().upsert(
                                        BookmarkedPlaylistEntity(
                                            url = url,
                                            title = payload.title,
                                            channelName = payload.channelName,
                                            thumbnailUrl = payload.thumbnailUrl,
                                            savedAtEpochMs = System.currentTimeMillis()
                                        )
                                    )
                                    bookmarkUrl = ""
                                    fileStatus = "Bookmarked playlist saved."
                                }
                                .onFailure {
                                    // Save the raw bookmark anyway; it can load later.
                                    app.database.bookmarkedPlaylistDao().upsert(
                                        BookmarkedPlaylistEntity(
                                            url = url, title = url, channelName = "",
                                            thumbnailUrl = "", savedAtEpochMs = System.currentTimeMillis()
                                        )
                                    )
                                    bookmarkUrl = ""
                                    fileStatus = "Bookmark saved (details will load when opened)."
                                }
                        }
                    }) { Text("Save") }
                }
            }
        }

        // ---- My playlists ----
        item {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, PureBorder, RoundedCornerShape(10.dp))
                    .padding(12.dp)
            ) {
                Text("My playlists", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                Spacer(modifier = Modifier.height(6.dp))
                if (playlists.isEmpty()) {
                    Text("No playlists yet.", color = PureTextSecondary, fontSize = 13.sp)
                } else {
                    playlists.forEach { playlist ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onLocalPlaylistClick(playlist.playlistId) }
                                .padding(vertical = 4.dp)
                        ) {
                            Text(playlist.name, color = Color.White, fontSize = 14.sp)
                            Text(
                                " · ${playlist.visibility.name}",
                                color = PureAccent, fontSize = 12.sp
                            )
                        }
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
                                    LocalPlaylistEntity(
                                        name = name,
                                        createdAtEpochMs = System.currentTimeMillis(),
                                        visibility = newPlaylistVisibility
                                    )
                                )
                            }
                            newPlaylistName = ""
                        }
                    }) { Text("Create") }
                }
                Row {
                    PlaylistVisibility.entries.forEach { vis ->
                        TextButton(onClick = { newPlaylistVisibility = vis }) {
                            Text(
                                if (vis == newPlaylistVisibility) "● ${vis.name}" else vis.name,
                                fontSize = 12.sp
                            )
                        }
                    }
                }
            }
        }

        // ---- Import / export (files first, paste as fallback) ----
        item {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, PureBorder, RoundedCornerShape(10.dp))
                    .padding(12.dp)
            ) {
                Text("Import / export", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    "Files are the main route: import subscriptions (JSON/CSV) or a playlist (JSON/CSV/TXT of links) from a file, and export subscriptions back out. Paste still works below.",
                    color = PureTextSecondary, fontSize = 13.sp, lineHeight = 18.sp
                )
                Spacer(modifier = Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = {
                        importSubsLauncher.launch(arrayOf("application/json", "text/csv", "text/plain", "*/*"))
                    }) { Text("Import subs (file)", fontSize = 12.sp) }
                    Button(onClick = {
                        importPlaylistLauncher.launch(arrayOf("application/json", "text/csv", "text/plain", "*/*"))
                    }) { Text("Import playlist (file)", fontSize = 12.sp) }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { exportSubsLauncher.launch("puretube-subscriptions.json") }) {
                        Text("Export subs (file)", fontSize = 12.sp)
                    }
                    TextButton(onClick = {
                        val json = app.importExportManager.exportNewPipeJson(subscriptions)
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setPrimaryClip(ClipData.newPlainText("PureTube subscriptions", json))
                        fileStatus = "Exported ${subscriptions.size} subscriptions to the clipboard."
                    }) { Text("Export subs (clipboard)", fontSize = 12.sp) }
                }
                if (fileStatus.isNotBlank()) {
                    Text(fileStatus, color = PureAccent, fontSize = 13.sp)
                }
                Spacer(modifier = Modifier.height(6.dp))
                OutlinedTextField(
                    value = importText,
                    onValueChange = { importText = it },
                    placeholder = { Text("Or paste a subscription export (NewPipe, Takeout, SkyTube, OPML, CSV…)") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2,
                    maxLines = 5
                )
                TextButton(onClick = {
                    val report = runCatching { app.importExportManager.parseAnyWithReport(importText) }
                        .getOrNull()
                    val parsed = report?.subscriptions.orEmpty()
                    scope.launch {
                        if (parsed.isNotEmpty()) app.database.subscriptionDao().upsertAll(parsed)
                        fileStatus = when {
                            report == null || parsed.isEmpty() ->
                                "Nothing imported — the pasted text did not match a supported format."
                            report.skipped > 0 ->
                                "Imported ${parsed.size} subscriptions (${report.format}), skipped ${report.skipped} unrecognised rows."
                            else -> "Imported ${parsed.size} subscriptions (${report.format}) from paste."
                        }
                    }
                }) { Text("Import pasted subscriptions", fontSize = 12.sp) }
            }
        }

        // ---- Queue ----
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, PureBorder, RoundedCornerShape(10.dp))
                    .clickable { onQueueClick() }
                    .padding(12.dp)
            ) {
                Text("Up Next queue", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                Spacer(modifier = Modifier.weight(1f))
                Text("${queue.size} videos", color = PureTextSecondary, fontSize = 13.sp)
            }
        }

        // ---- History actions ----
        item {
            Column {
                Text("History", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = {
                        scope.launch {
                            app.preferences.update {
                                it.copy(
                                    historyGroupMode = if (prefs.historyGroupMode == HistoryGroupMode.GROUPED_BY_DAY) {
                                        HistoryGroupMode.CONTINUOUS
                                    } else {
                                        HistoryGroupMode.GROUPED_BY_DAY
                                    }
                                )
                            }
                        }
                    }) {
                        Text(
                            if (prefs.historyGroupMode == HistoryGroupMode.GROUPED_BY_DAY) {
                                "Display: Grouped by day"
                            } else {
                                "Display: Continuous"
                            },
                            fontSize = 12.sp
                        )
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = {
                        scope.launch { app.database.watchProgressDao().deleteCompleted() }
                    }) { Text("Clear watched", fontSize = 12.sp) }
                    TextButton(onClick = {
                        scope.launch { app.database.watchProgressDao().clearAll() }
                    }) { Text("Clear all history", fontSize = 12.sp) }
                    TextButton(onClick = {
                        scope.launch { app.database.searchHistoryDao().clearAll() }
                    }) { Text("Clear search history", fontSize = 12.sp) }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = {
                        scope.launch { app.database.watchProgressDao().resetAllPositions() }
                    }) { Text("Delete all playback positions", fontSize = 12.sp) }
                    TextButton(onClick = { confirmClearSubs = true }) {
                        Text("Clear subscriptions", fontSize = 12.sp)
                    }
                }
            }
        }
        if (history.isEmpty()) {
            item {
                Text("Watched videos will appear here with resume progress.", color = PureTextSecondary, fontSize = 13.sp)
            }
        }
        if (prefs.historyGroupMode == HistoryGroupMode.CONTINUOUS) {
            items(visibleHistory, key = { "hist-${it.videoId}" }) { progress ->
                HistoryRow(
                    progress = progress,
                    prefs = prefs,
                    onVideoClick = onVideoClick,
                    onChannelClick = onChannelClick
                )
            }
        }
        historyDayGroups.takeIf { prefs.historyGroupMode == HistoryGroupMode.GROUPED_BY_DAY }
            ?.forEach { (label, groupItems) ->
            item(key = "hist-$label") {
                Text(label, color = PureAccent, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            }
            items(groupItems, key = { "hist-${it.videoId}" }) { progress ->
                HistoryRow(
                    progress = progress,
                    prefs = prefs,
                    onVideoClick = onVideoClick,
                    onChannelClick = onChannelClick
                )
            }
        }
    }

    if (confirmClearSubs) {
        AlertDialog(
            onDismissRequest = { confirmClearSubs = false },
            title = { Text("Clear all subscriptions?") },
            text = { Text("This removes every subscribed channel from this device. Export first if you want a backup.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmClearSubs = false
                    scope.launch { app.database.subscriptionDao().clearAll() }
                }) { Text("Clear") }
            },
            dismissButton = { TextButton(onClick = { confirmClearSubs = false }) { Text("Cancel") } }
        )
    }
}

/** History row honouring the shared feed layout + scale prefs (v1.2). */
@Composable
private fun HistoryRow(
    progress: com.puretube.app.data.local.entity.WatchProgressEntity,
    prefs: com.puretube.app.data.local.datastore.AppPreferencesState,
    onVideoClick: (String) -> Unit,
    onChannelClick: (String) -> Unit
) {
    val item = FeedItem(
        videoId = progress.videoId,
        title = progress.title,
        channelName = progress.channelName,
        channelId = progress.channelId,
        thumbnailUrl = progress.thumbnailUrl,
        durationSeconds = if (progress.durationMs > 0) progress.durationMs / 1000 else 0L,
        url = "https://www.youtube.com/watch?v=${progress.videoId}"
    )
    when (prefs.feedLayout) {
        com.puretube.app.data.local.datastore.FeedLayout.BOTH ->
            com.puretube.app.ui.components.BothRow(
                item = item, scale = prefs.feedThumbnailScale,
                onClick = { onVideoClick(progress.videoId) }, onChannelClick = onChannelClick
            )
        com.puretube.app.data.local.datastore.FeedLayout.TITLE_ONLY ->
            com.puretube.app.ui.components.TitleRow(
                item = item, onClick = { onVideoClick(progress.videoId) }
            )
        else -> VideoCardItem(
            item = item,
            progressFraction = progress.progressFraction,
            onClick = { onVideoClick(progress.videoId) },
            onChannelClick = onChannelClick
        )
    }
}
