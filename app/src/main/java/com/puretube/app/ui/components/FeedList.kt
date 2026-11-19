package com.puretube.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.puretube.app.PureTubeApp
import com.puretube.app.data.WatchLaterHelper
import com.puretube.app.data.local.datastore.FeedLayout
import com.puretube.app.data.local.datastore.ThumbnailScale
import com.puretube.app.data.local.entity.BlockedFilterEntity
import com.puretube.app.data.local.entity.FilterType
import com.puretube.app.data.local.entity.SubscriptionEntity
import com.puretube.app.data.remote.extractor.FeedItem
import com.puretube.app.ui.theme.PureAccent
import com.puretube.app.ui.theme.PureTextSecondary
import kotlinx.coroutines.launch

/**
 * Shared feed list honouring layout + thumbnail scale + grid columns across
 * Search, Subscriptions, Library, Channel and Playlist screens (v1.2):
 * LIST = full cards, GRID = N-column cards, BOTH = thumbnail + title rows,
 * THUMBNAIL_ONLY = grid of thumbnails, TITLE_ONLY = compact text rows.
 * COMPACT scale renders LIST as BOTH rows.
 */
@Composable
fun FeedListStandalone(
    items: List<FeedItem>,
    layout: FeedLayout,
    onVideoClick: (String) -> Unit,
    onChannelClick: ((String) -> Unit)? = null,
    progressFor: (FeedItem) -> Float = { 0f },
    modifier: Modifier = Modifier,
    scale: ThumbnailScale = ThumbnailScale.NORMAL,
    columns: Int = 2
) {
    val effectiveLayout =
        if (layout == FeedLayout.LIST && scale == ThumbnailScale.COMPACT) FeedLayout.BOTH else layout
    when (effectiveLayout) {
        FeedLayout.GRID -> LazyVerticalGrid(
            columns = GridCells.Fixed(columns.coerceIn(2, 4)),
            modifier = modifier,
            contentPadding = PaddingValues(4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            gridItems(items, key = { it.videoId }) { item ->
                VideoCardItem(
                    item = item,
                    progressFraction = progressFor(item),
                    onClick = { onVideoClick(item.videoId) },
                    onChannelClick = onChannelClick
                )
            }
        }
        FeedLayout.THUMBNAIL_ONLY -> LazyVerticalGrid(
            columns = GridCells.Fixed(columns.coerceIn(2, 4)),
            modifier = modifier,
            contentPadding = PaddingValues(4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            gridItems(items, key = { it.videoId }) { item ->
                ThumbnailCell(item = item, onClick = { onVideoClick(item.videoId) })
            }
        }
        FeedLayout.BOTH -> LazyColumn(
            modifier = modifier,
            contentPadding = PaddingValues(vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            items(items, key = { it.videoId }) { item ->
                BothRow(
                    item = item,
                    scale = scale,
                    onClick = { onVideoClick(item.videoId) },
                    onChannelClick = onChannelClick
                )
            }
        }
        FeedLayout.TITLE_ONLY -> LazyColumn(
            modifier = modifier,
            contentPadding = PaddingValues(vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            items(items, key = { it.videoId }) { item ->
                TitleRow(item = item, onClick = { onVideoClick(item.videoId) })
            }
        }
        FeedLayout.LIST -> LazyColumn(
            modifier = modifier,
            contentPadding = PaddingValues(vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(items, key = { it.videoId }) { item ->
                VideoCardItem(
                    item = item,
                    progressFraction = progressFor(item),
                    onClick = { onVideoClick(item.videoId) },
                    onChannelClick = onChannelClick
                )
            }
        }
    }
}

@Composable
fun ThumbnailCell(item: FeedItem, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val app = context.applicationContext as PureTubeApp
    val thumbRequest = remember(item.thumbnailUrl, app.currentPrefs) {
        buildProtectedThumbnailRequest(context, app.haramBlurEngine, item.thumbnailUrl, app.currentPrefs, peeked = false)
    }
    Box(
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(16f / 9f)
            .clip(RoundedCornerShape(8.dp))
            .background(Color(0xFF111111))
            .clickable { onClick() }
    ) {
        AsyncImage(
            model = thumbRequest,
            contentDescription = item.title,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxWidth()
        )
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
}

/** BOTH layout row: thumbnail + title/channel + ⋮ menu. */
@Composable
fun BothRow(
    item: FeedItem,
    scale: ThumbnailScale = ThumbnailScale.NORMAL,
    onClick: () -> Unit,
    onChannelClick: ((String) -> Unit)? = null
) {
    val thumbWidth = when (scale) {
        ThumbnailScale.COMPACT -> 96.dp
        ThumbnailScale.NORMAL -> 128.dp
        ThumbnailScale.LARGE -> 176.dp
    }
    val context = LocalContext.current
    val app = context.applicationContext as PureTubeApp
    val thumbRequest = remember(item.thumbnailUrl, app.currentPrefs) {
        buildProtectedThumbnailRequest(context, app.haramBlurEngine, item.thumbnailUrl, app.currentPrefs, peeked = false)
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(horizontal = 4.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .width(thumbWidth)
                .aspectRatio(16f / 9f)
                .clip(RoundedCornerShape(8.dp))
                .background(Color(0xFF111111))
        ) {
            AsyncImage(
                model = thumbRequest,
                contentDescription = item.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxWidth()
            )
            if (item.durationSeconds > 0) {
                Text(
                    text = formatDuration(item.durationSeconds),
                    color = Color.White,
                    fontSize = 10.sp,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .background(Color(0xCC000000), RoundedCornerShape(4.dp))
                        .padding(horizontal = 4.dp, vertical = 1.dp)
                )
            }
        }
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(start = 10.dp)
        ) {
            Text(
                item.title, color = Color.White,
                fontSize = if (scale == ThumbnailScale.LARGE) 16.sp else 14.sp,
                maxLines = 2, overflow = TextOverflow.Ellipsis
            )
            Text(
                item.channelName, color = PureTextSecondary, fontSize = 12.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = if (onChannelClick != null && item.channelId.isNotBlank()) {
                    Modifier.clickable { onChannelClick(item.channelId) }
                } else Modifier
            )
        }
        VideoOptionsButton(item)
    }
}

@Composable
fun TitleRow(item: FeedItem, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                item.title, color = Color.White, fontSize = 14.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis
            )
            Text(
                item.channelName, color = PureTextSecondary, fontSize = 12.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis
            )
        }
        if (item.durationSeconds > 0) {
            Text(formatDuration(item.durationSeconds), color = PureTextSecondary, fontSize = 12.sp)
        }
        VideoOptionsButton(item)
    }
}

/** Compact ⋮ configure menu used by BOTH / TITLE_ONLY rows. */
@Composable
fun VideoOptionsButton(item: FeedItem) {
    val context = LocalContext.current
    val app = context.applicationContext as PureTubeApp
    val scope = rememberCoroutineScope()
    var menuOpen by remember { mutableStateOf(false) }
    var showPlaylistDialog by remember { mutableStateOf(false) }
    val subscriptions by app.database.subscriptionDao().observeAll()
        .collectAsState(initial = emptyList())
    val isSubscribed = subscriptions.any { it.channelId == item.channelId }

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
                        app.playerController.postNotice(
                            if (added) "Added to Watch Later" else "Removed from Watch Later"
                        )
                    }
                }
            )
            DropdownMenuItem(
                text = { Text("Add to Queue") },
                onClick = {
                    menuOpen = false
                    app.playerController.enqueueWithNotice(item)
                }
            )
            DropdownMenuItem(
                text = { Text("Add to Playlist…") },
                onClick = { menuOpen = false; showPlaylistDialog = true }
            )
            if (item.channelId.isNotBlank()) {
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
                            app.playerController.postNotice(
                                if (isSubscribed) "Unsubscribed" else "Subscribed to ${item.channelName}"
                            )
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
                                    BlockedFilterEntity(
                                        pattern = item.channelName,
                                        filterType = FilterType.CHANNEL_NAME
                                    )
                                )
                                app.database.blocklistDao().add(
                                    BlockedFilterEntity(
                                        pattern = item.channelId,
                                        filterType = FilterType.CHANNEL_ID
                                    )
                                )
                            }
                            app.playerController.postNotice("Channel blocked")
                        }
                    }
                )
            }
        }
    }
    if (showPlaylistDialog) {
        AddToPlaylistDialog(item = item, onDismiss = { showPlaylistDialog = false })
    }
}

/**
 * Shared feed display controls (v1.3): layout mode (List / Grid / Both /
 * Thumbnail only / Title only), thumbnail scale (Compact / Normal / Large)
 * and grid columns (2/3/4) — one control row, same shared prefs, used by
 * Search, Home/Subscriptions, Library, Channel and Playlist screens.
 */
@Composable
fun FeedDisplayControls(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val app = context.applicationContext as PureTubeApp
    val scope = rememberCoroutineScope()
    val prefs by app.preferences.state.collectAsState(initial = app.currentPrefs)
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        TextButton(onClick = {
            val values = FeedLayout.entries
            val next = values[(prefs.feedLayout.ordinal + 1) % values.size]
            scope.launch { app.preferences.update { it.copy(feedLayout = next) } }
        }) { Text("Layout: ${prefs.feedLayout.name}", fontSize = 12.sp) }
        TextButton(onClick = {
            val values = ThumbnailScale.entries
            val next = values[(prefs.feedThumbnailScale.ordinal + 1) % values.size]
            scope.launch { app.preferences.update { it.copy(feedThumbnailScale = next) } }
        }) { Text("Size: ${prefs.feedThumbnailScale.name}", fontSize = 12.sp) }
        if (prefs.feedLayout == FeedLayout.GRID || prefs.feedLayout == FeedLayout.THUMBNAIL_ONLY) {
            TextButton(onClick = {
                val options = listOf(2, 3, 4)
                val idx = options.indexOf(prefs.gridColumns).let { if (it < 0) 0 else it }
                scope.launch {
                    app.preferences.update { it.copy(gridColumns = options[(idx + 1) % options.size]) }
                }
            }) { Text("Columns: ${prefs.gridColumns}", fontSize = 12.sp) }
        }
    }
}
