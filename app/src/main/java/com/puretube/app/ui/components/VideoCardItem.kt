package com.puretube.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
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
import coil3.request.ImageRequest
import coil3.request.transformations
import com.puretube.app.PureTubeApp
import com.puretube.app.data.WatchLaterHelper
import com.puretube.app.data.local.datastore.ProtectedMediaType
import com.puretube.app.data.local.datastore.ProtectionScope
import com.puretube.app.data.local.entity.BlockedFilterEntity
import com.puretube.app.data.local.entity.FilterType
import com.puretube.app.data.local.entity.LocalPlaylistEntity
import com.puretube.app.data.local.entity.PlaylistVisibility
import com.puretube.app.data.local.entity.SubscriptionEntity
import com.puretube.app.data.local.entity.WatchProgressEntity
import com.puretube.app.data.remote.extractor.FeedItem
import com.puretube.app.haramblur.engine.HaramBlurCoilTransformation
import com.puretube.app.ui.theme.PureAccent
import com.puretube.app.ui.theme.PureBorder
import com.puretube.app.ui.theme.PureTextSecondary
import kotlinx.coroutines.launch

/**
 * Video card: HaramBlur-protected thumbnail with a 1-tap eye "peek" override,
 * title, channel (tap opens the channel), and a ⋮ configure menu:
 * Watch Later, Add to Queue, Mark Watched/Unwatched, Add to Playlist…,
 * Subscribe/Unsubscribe, Block channel, Block channel ID.
 */
@Composable
fun VideoCardItem(
    item: FeedItem,
    progressFraction: Float = 0f,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onChannelClick: ((String) -> Unit)? = null,
    showMenu: Boolean = true
) {
    val context = LocalContext.current
    val app = context.applicationContext as PureTubeApp
    val scope = rememberCoroutineScope()
    var peeked by remember(item.videoId) { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    var showPlaylistDialog by remember { mutableStateOf(false) }
    var menuStatus by remember { mutableStateOf("") }
    val prefs = app.currentPrefs
    val subscriptions by app.database.subscriptionDao().observeAll()
        .collectAsState(initial = emptyList())
    val isSubscribed = subscriptions.any { it.channelId == item.channelId }
    val history by app.database.watchProgressDao().observeHistory()
        .collectAsState(initial = emptyList())
    val watched = history.firstOrNull { it.videoId == item.videoId }?.isCompleted == true

    val imagesProtected = prefs.protectionScope != ProtectionScope.OFF &&
        (prefs.protectedMediaType == ProtectedMediaType.IMAGES_ONLY ||
            prefs.protectedMediaType == ProtectedMediaType.BOTH_IMAGES_AND_VIDEOS)

    val request = remember(item.thumbnailUrl, peeked, prefs.blurTargetGender, prefs.blurVisualStyle) {
        val builder = ImageRequest.Builder(context).data(item.thumbnailUrl)
        if (imagesProtected) {
            builder.transformations(
                HaramBlurCoilTransformation(
                    engine = app.haramBlurEngine,
                    targetGender = prefs.blurTargetGender,
                    visualStyle = prefs.blurVisualStyle,
                    sensitivity = prefs.detectionSensitivity,
                    blurIntensity = prefs.blurIntensityRadius,
                    isManuallyPeeked = peeked
                )
            )
        }
        builder.build()
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(Color.Black)
            .border(1.dp, PureBorder, RoundedCornerShape(10.dp))
            .clickable { onClick() }
            .padding(10.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .clip(RoundedCornerShape(8.dp))
                .background(Color(0xFF111111))
        ) {
            AsyncImage(
                model = request,
                contentDescription = item.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxWidth()
            )
            IconButton(
                onClick = { peeked = !peeked },
                modifier = Modifier.align(Alignment.TopEnd)
            ) {
                Icon(
                    imageVector = if (peeked) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                    contentDescription = if (peeked) "Hide peek" else "Peek thumbnail",
                    tint = Color.White
                )
            }
            if (item.durationSeconds > 0) {
                Text(
                    text = formatDuration(item.durationSeconds),
                    color = Color.White,
                    fontSize = 11.sp,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .background(Color(0xCC000000), RoundedCornerShape(4.dp))
                        .padding(horizontal = 5.dp, vertical = 2.dp)
                )
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.Top) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.title,
                    color = Color.White,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(top = 2.dp)
                ) {
                    if (item.channelAvatarUrl.isNotBlank()) {
                        AsyncImage(
                            model = item.channelAvatarUrl,
                            contentDescription = item.channelName,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .size(20.dp)
                                .clip(CircleShape)
                                .then(
                                    if (onChannelClick != null) {
                                        Modifier.clickable { onChannelClick(item.channelId) }
                                    } else Modifier
                                )
                        )
                        Spacer(modifier = Modifier.padding(horizontal = 3.dp))
                    }
                    Text(
                        text = item.channelName,
                        color = PureTextSecondary,
                        fontSize = 13.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = if (onChannelClick != null) {
                            Modifier.clickable { onChannelClick(item.channelId) }
                        } else Modifier
                    )
                    if (item.likeCount >= 0) {
                        Text(
                            text = " · ${item.likeCount} likes",
                            color = PureTextSecondary,
                            fontSize = 12.sp
                        )
                    }
                }
            }
            if (showMenu) {
                Box {
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(Icons.Filled.MoreVert, contentDescription = "Video options", tint = Color.White)
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text("Watch Later") },
                            onClick = {
                                menuOpen = false
                                scope.launch {
                                    val added = runCatching {
                                        WatchLaterHelper.toggle(app.database, item)
                                    }.getOrDefault(false)
                                    menuStatus = if (added) "Added to Watch Later" else "Removed from Watch Later"
                                }
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Add to Queue") },
                            onClick = {
                                menuOpen = false
                                app.playerController.enqueueWithNotice(item)
                                menuStatus = "Added to queue"
                            }
                        )
                        DropdownMenuItem(
                            text = { Text(if (watched) "Mark Unwatched" else "Mark Watched") },
                            onClick = {
                                menuOpen = false
                                scope.launch {
                                    runCatching {
                                        val dao = app.database.watchProgressDao()
                                        val existing = dao.get(item.videoId)
                                        if (watched) {
                                            if (existing != null) {
                                                dao.upsert(existing.copy(isCompleted = false, positionMs = 0))
                                            }
                                        } else {
                                            val dur = if (item.durationSeconds > 0) item.durationSeconds * 1000 else (existing?.durationMs ?: 0)
                                            dao.upsert(
                                                WatchProgressEntity(
                                                    videoId = item.videoId,
                                                    title = item.title,
                                                    channelName = item.channelName,
                                                    channelId = item.channelId,
                                                    thumbnailUrl = item.thumbnailUrl,
                                                    positionMs = dur,
                                                    durationMs = dur,
                                                    isCompleted = true,
                                                    lastUpdatedEpochMs = System.currentTimeMillis()
                                                )
                                            )
                                        }
                                    }
                                    menuStatus = if (watched) "Marked unwatched" else "Marked watched"
                                }
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Add to Playlist…") },
                            onClick = { menuOpen = false; showPlaylistDialog = true }
                        )
                        DropdownMenuItem(
                            text = { Text(if (isSubscribed) "Unsubscribe channel" else "Subscribe channel") },
                            onClick = {
                                menuOpen = false
                                scope.launch {
                                    runCatching {
                                        if (isSubscribed) {
                                            app.database.subscriptionDao().delete(item.channelId)
                                        } else {
                                            app.database.subscriptionDao().upsert(
                                                SubscriptionEntity(
                                                    channelId = item.channelId,
                                                    channelName = item.channelName,
                                                    avatarUrl = item.channelAvatarUrl,
                                                    subscriberCountText = null,
                                                    addedAtEpochMs = System.currentTimeMillis()
                                                )
                                            )
                                        }
                                    }
                                    menuStatus = if (isSubscribed) "Unsubscribed" else "Subscribed"
                                }
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Block channel") },
                            onClick = {
                                menuOpen = false
                                scope.launch {
                                    runCatching {
                                        app.database.blocklistDao().add(
                                            BlockedFilterEntity(pattern = item.channelName, filterType = FilterType.CHANNEL_NAME)
                                        )
                                    }
                                    menuStatus = "Channel blocked by name"
                                }
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Block channel ID") },
                            onClick = {
                                menuOpen = false
                                scope.launch {
                                    runCatching {
                                        app.database.blocklistDao().add(
                                            BlockedFilterEntity(pattern = item.channelId, filterType = FilterType.CHANNEL_ID)
                                        )
                                    }
                                    menuStatus = "Channel blocked by ID"
                                }
                            }
                        )
                    }
                }
            }
        }
        if (menuStatus.isNotBlank()) {
            Text(menuStatus, color = PureAccent, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
        }
        if (progressFraction > 0f) {
            Spacer(modifier = Modifier.height(6.dp))
            LinearProgressIndicator(
                progress = { progressFraction.coerceIn(0f, 1f) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(3.dp),
                color = PureAccent,
                trackColor = Color(0xFF222222)
            )
        }
    }

    if (showPlaylistDialog) {
        AddToPlaylistDialog(
            item = item,
            onDismiss = { showPlaylistDialog = false }
        )
    }
}

/** Dialog: pick an existing playlist or create a new one (with visibility). */
@Composable
fun AddToPlaylistDialog(item: FeedItem, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as PureTubeApp
    val scope = rememberCoroutineScope()
    val playlists by app.database.playlistDao().observePlaylists()
        .collectAsState(initial = emptyList())
    var newName by remember { mutableStateOf("") }
    var newVisibility by remember { mutableStateOf(PlaylistVisibility.PRIVATE) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add to Playlist") },
        text = {
            Column {
                if (playlists.isEmpty()) {
                    Text("No playlists yet — create one below.", color = PureTextSecondary, fontSize = 13.sp)
                }
                playlists.forEach { playlist ->
                    TextButton(onClick = {
                        scope.launch {
                            runCatching {
                                val dao = app.database.playlistDao()
                                dao.addVideo(
                                    item.toCrossRef(playlist.playlistId, dao.videoCount(playlist.playlistId))
                                )
                            }
                            onDismiss()
                        }
                    }) {
                        Text("${playlist.name} (${playlist.visibility.name})", fontSize = 14.sp)
                    }
                }
                OutlinedTextField(
                    value = newName,
                    onValueChange = { newName = it },
                    placeholder = { Text("New playlist name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Row {
                    PlaylistVisibility.entries.forEach { vis ->
                        TextButton(onClick = { newVisibility = vis }) {
                            Text(
                                if (vis == newVisibility) "● ${vis.name}" else "○ ${vis.name}",
                                fontSize = 12.sp
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val name = newName.trim()
                if (name.isBlank()) { onDismiss(); return@TextButton }
                scope.launch {
                    runCatching {
                        val dao = app.database.playlistDao()
                        val id = dao.createPlaylist(
                            LocalPlaylistEntity(
                                name = name,
                                createdAtEpochMs = System.currentTimeMillis(),
                                visibility = newVisibility
                            )
                        )
                        dao.addVideo(item.toCrossRef(id, 0))
                    }
                    onDismiss()
                }
            }) { Text("Create + Add") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

private fun FeedItem.toCrossRef(playlistId: Long, positionIndex: Int) =
    com.puretube.app.data.local.entity.PlaylistVideoCrossRef(
        playlistId = playlistId,
        videoId = videoId,
        title = title,
        channelName = channelName,
        thumbnailUrl = thumbnailUrl,
        durationSeconds = durationSeconds,
        positionIndex = positionIndex
    )

fun formatDuration(totalSeconds: Long): String {
    val h = totalSeconds / 3600
    val m = (totalSeconds % 3600) / 60
    val s = totalSeconds % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}
