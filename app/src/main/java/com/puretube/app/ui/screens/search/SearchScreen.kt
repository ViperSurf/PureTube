package com.puretube.app.ui.screens.search

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.puretube.app.PureTubeApp
import com.puretube.app.data.local.datastore.FeedLayout
import com.puretube.app.data.local.datastore.ThumbnailScale
import com.puretube.app.data.local.entity.BookmarkedPlaylistEntity
import com.puretube.app.data.local.entity.FilterType
import com.puretube.app.data.local.entity.SearchHistoryEntity
import com.puretube.app.data.remote.extractor.SearchHit
import com.puretube.app.data.remote.extractor.SearchHitType
import com.puretube.app.data.remote.extractor.SearchTypeFilter
import com.puretube.app.data.remote.extractor.ShortsFilterEngine
import com.puretube.app.domain.ErrorText
import com.puretube.app.domain.LikeFilters
import com.puretube.app.domain.PageMerger
import com.puretube.app.ui.components.BothRow
import com.puretube.app.ui.components.ThumbnailCell
import com.puretube.app.ui.components.TitleRow
import com.puretube.app.ui.components.VideoCardItem
import com.puretube.app.ui.theme.PureAccent
import com.puretube.app.ui.theme.PureTextSecondary
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch

/**
 * Search (v1.2): typed results (All / Videos / Playlists / Channels) with
 * pagination, recent searches (last 20) on the empty screen, quick layout /
 * scale / columns controls, and state held in the app-scoped
 * [SearchSession] so opening a video never destroys the search.
 */
@Composable
fun SearchScreen(
    onVideoClick: (String) -> Unit,
    onChannelClick: (String) -> Unit = {},
    onPlaylistClick: (String) -> Unit = {}
) {
    val context = LocalContext.current
    val app = context.applicationContext as PureTubeApp
    val scope = rememberCoroutineScope()
    val prefs by app.preferences.state.collectAsState(initial = app.currentPrefs)
    val session = app.searchSession
    val searchHistory by app.database.searchHistoryDao().observeAll()
        .collectAsState(initial = emptyList())

    suspend fun blockedLists(): Triple<List<String>, List<String>, List<String>> {
        val blocked = runCatching { app.database.blocklistDao().getAll() }.getOrDefault(emptyList())
        return Triple(
            blocked.filter { it.filterType == FilterType.KEYWORD }.map { it.pattern },
            blocked.filter { it.filterType == FilterType.CHANNEL_ID }.map { it.pattern },
            blocked.filter { it.filterType == FilterType.CHANNEL_NAME }.map { it.pattern }
        )
    }

    fun filtered(
        hits: List<SearchHit>,
        blocked: Triple<List<String>, List<String>, List<String>>
    ): List<SearchHit> {
        val (keywords, channels, names) = blocked
        return hits.filter { hit ->
            val item = hit.video ?: return@filter true
            ShortsFilterEngine.filterItems(listOf(item), prefs.stripShortsEverywhere).isNotEmpty() &&
                ShortsFilterEngine.filterByBlockedKeywords(listOf(item), keywords, channels, names).isNotEmpty() &&
                LikeFilters.passesContent(item, prefs.contentMinLikes, prefs.contentMaxLikes)
        }
    }

    fun countStatus() {
        session.status = if (session.hits.isEmpty()) {
            "No results"
        } else {
            "${session.hits.size} results${if (session.hasMore) "+" else ""}"
        }
    }

    fun runSearch(searchText: String = session.query) {
        val q = searchText.trim()
        if (q.isBlank() || session.loading) return
        session.loading = true
        session.status = "Searching..."
        scope.launch {
            if (prefs.searchHistoryEnabled) {
                runCatching {
                    app.database.searchHistoryDao().upsert(
                        SearchHistoryEntity(query = q, searchedAtEpochMs = System.currentTimeMillis())
                    )
                }
            }
            val blocked = blockedLists()
            session.errorDetail = ""
            app.streamRepository.search(q, session.filter)
                .onSuccess { page ->
                    session.hits = filtered(page.hits, blocked)
                    session.hasMore = page.hasMore
                    session.hasSearched = true
                    session.scrollIndex = 0
                    session.scrollOffset = 0
                    countStatus()
                }
                .onFailure { error ->
                    session.hits = emptyList()
                    session.hasMore = false
                    session.hasSearched = true
                    // A failure shows its class + top stack frames so a
                    // pasted report identifies the exact line; a genuinely
                    // empty result is "No results" above, never this.
                    session.errorDetail =
                        com.puretube.app.domain.ErrorDetail.withStack(error, frames = 5)
                    session.status = "Search failed: ${ErrorText.describe(error, 300)}"
                }
            session.loading = false
        }
    }

    fun loadMore() {
        if (!session.hasMore || session.loadingMore || session.loading) return
        session.loadingMore = true
        scope.launch {
            val blocked = blockedLists()
            app.streamRepository.searchMore()
                .onSuccess { page ->
                    session.hits = PageMerger.merge(session.hits, filtered(page.hits, blocked)) { it.stableKey }
                    session.hasMore = page.hasMore
                    countStatus()
                }
                .onFailure { session.hasMore = false }
            session.loadingMore = false
        }
    }

    val listState = rememberLazyListState(
        initialFirstVisibleItemIndex = session.scrollIndex,
        initialFirstVisibleItemScrollOffset = session.scrollOffset
    )
    val gridState = rememberLazyGridState(
        initialFirstVisibleItemIndex = session.scrollIndex,
        initialFirstVisibleItemScrollOffset = session.scrollOffset
    )

    LaunchedEffect(listState) {
        snapshotFlow {
            listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset
        }.distinctUntilChanged().collect { (index, offset) ->
            session.scrollIndex = index
            session.scrollOffset = offset
        }
    }
    LaunchedEffect(listState) {
        snapshotFlow {
            val info = listState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()?.index ?: -1
            last >= info.totalItemsCount - 3 && info.totalItemsCount > 0
        }.distinctUntilChanged().filter { it }.collect { loadMore() }
    }
    LaunchedEffect(gridState) {
        snapshotFlow {
            val info = gridState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()?.index ?: -1
            last >= info.totalItemsCount - 4 && info.totalItemsCount > 0
        }.distinctUntilChanged().filter { it }.collect { loadMore() }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .padding(12.dp)
    ) {
        Text("Search", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 10.dp, bottom = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedTextField(
                value = session.query,
                onValueChange = { session.query = it },
                placeholder = { Text("Search YouTube") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { runSearch() }),
                modifier = Modifier.weight(1f)
            )
            Button(onClick = { runSearch() }) { Text("Go") }
        }

        // Type filter chips
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            SearchTypeFilter.entries.forEach { type ->
                FilterChip(
                    selected = session.filter == type,
                    onClick = {
                        if (session.filter != type) {
                            session.filter = type
                            if (session.hasSearched) runSearch()
                        }
                    },
                    label = { Text(type.name.lowercase().replaceFirstChar { it.uppercase() }, fontSize = 12.sp) }
                )
            }
        }

        // Quick layout / scale / columns controls
        Row(
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

        if (session.loading) {
            CircularProgressIndicator(modifier = Modifier.padding(vertical = 12.dp))
        }
        Text(session.status, color = PureTextSecondary, fontSize = 13.sp)
        if (session.errorDetail.isNotBlank()) {
            Text(
                session.errorDetail, color = PureTextSecondary, fontSize = 11.sp,
                lineHeight = 15.sp, modifier = Modifier.padding(top = 4.dp)
            )
            TextButton(onClick = {
                runCatching {
                    val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                        as android.content.ClipboardManager
                    clipboard.setPrimaryClip(
                        android.content.ClipData.newPlainText(
                            "PureTube search diagnostics",
                            "Query: ${session.query}\nFilter: ${session.filter}\n${session.errorDetail}"
                        )
                    )
                    session.status = "Diagnostics copied"
                }
            }) { Text("Copy diagnostics", fontSize = 12.sp) }
        }

        val showRecent = !session.hasSearched && session.query.isBlank() &&
            prefs.searchHistoryEnabled && searchHistory.isNotEmpty()
        if (showRecent) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "Recent searches", color = Color.White, fontSize = 15.sp,
                    fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f)
                )
                TextButton(onClick = {
                    scope.launch { app.database.searchHistoryDao().clearAll() }
                }) { Text("Clear all", fontSize = 12.sp) }
            }
            LazyColumn(
                contentPadding = PaddingValues(vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                items(searchHistory.take(20), key = { it.query }) { entry ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                session.query = entry.query
                                runSearch(entry.query)
                            }
                            .padding(horizontal = 8.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(entry.query, color = Color.White, fontSize = 14.sp, modifier = Modifier.weight(1f))
                        IconButton(onClick = {
                            scope.launch { app.database.searchHistoryDao().delete(entry.query) }
                        }) {
                            Icon(Icons.Filled.Close, contentDescription = "Remove search", tint = PureTextSecondary)
                        }
                    }
                }
            }
            return@Column
        }

        val hits = session.hits
        val isGrid = prefs.feedLayout == FeedLayout.GRID || prefs.feedLayout == FeedLayout.THUMBNAIL_ONLY
        if (isGrid) {
            LazyVerticalGrid(
                columns = GridCells.Fixed(prefs.gridColumns.coerceIn(2, 4)),
                state = gridState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                gridItems(hits, key = { it.stableKey }) { hit ->
                    when (hit.type) {
                        SearchHitType.VIDEO -> hit.video?.let { item ->
                            if (prefs.feedLayout == FeedLayout.THUMBNAIL_ONLY) {
                                ThumbnailCell(item = item, onClick = { onVideoClick(item.videoId) })
                            } else {
                                VideoCardItem(item = item, onClick = { onVideoClick(item.videoId) }, onChannelClick = onChannelClick)
                            }
                        }
                        SearchHitType.PLAYLIST -> hit.playlist?.let { pl ->
                            PlaylistHitCard(
                                title = pl.title, channelName = pl.channelName,
                                thumbnailUrl = pl.thumbnailUrl, streamCount = pl.streamCount,
                                onClick = { onPlaylistClick(pl.url) }
                            )
                        }
                        SearchHitType.CHANNEL -> hit.channel?.let { ch ->
                            ChannelHitCard(
                                name = ch.name, avatarUrl = ch.avatarUrl,
                                subscriberCount = ch.subscriberCount,
                                onClick = { onChannelClick(ch.channelId) }
                            )
                        }
                    }
                }
                if (session.hasMore) {
                    item {
                        TextButton(onClick = { loadMore() }) {
                            Text(if (session.loadingMore) "Loading…" else "Load more", fontSize = 13.sp)
                        }
                    }
                }
            }
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(hits, key = { it.stableKey }) { hit ->
                    when (hit.type) {
                        SearchHitType.VIDEO -> hit.video?.let { item ->
                            when (prefs.feedLayout) {
                                FeedLayout.TITLE_ONLY -> TitleRow(item = item, onClick = { onVideoClick(item.videoId) })
                                FeedLayout.BOTH -> BothRow(
                                    item = item, scale = prefs.feedThumbnailScale,
                                    onClick = { onVideoClick(item.videoId) }, onChannelClick = onChannelClick
                                )
                                else -> VideoCardItem(
                                    item = item,
                                    onClick = { onVideoClick(item.videoId) },
                                    onChannelClick = onChannelClick
                                )
                            }
                        }
                        SearchHitType.PLAYLIST -> hit.playlist?.let { pl ->
                            PlaylistHitRow(
                                title = pl.title, channelName = pl.channelName,
                                thumbnailUrl = pl.thumbnailUrl, streamCount = pl.streamCount,
                                onClick = { onPlaylistClick(pl.url) },
                                onBookmark = {
                                    scope.launch {
                                        runCatching {
                                            app.database.bookmarkedPlaylistDao().upsert(
                                                BookmarkedPlaylistEntity(
                                                    url = pl.url, title = pl.title,
                                                    channelName = pl.channelName,
                                                    thumbnailUrl = pl.thumbnailUrl,
                                                    savedAtEpochMs = System.currentTimeMillis()
                                                )
                                            )
                                        }
                                        app.playerController.postNotice("Playlist bookmarked")
                                    }
                                }
                            )
                        }
                        SearchHitType.CHANNEL -> hit.channel?.let { ch ->
                            ChannelHitRow(
                                name = ch.name, avatarUrl = ch.avatarUrl,
                                subscriberCount = ch.subscriberCount,
                                description = ch.description,
                                onClick = { onChannelClick(ch.channelId) }
                            )
                        }
                    }
                }
                if (session.hasMore) {
                    item {
                        TextButton(onClick = { loadMore() }) {
                            Text(if (session.loadingMore) "Loading…" else "Load more results", fontSize = 13.sp)
                        }
                    }
                }
                if (session.loadingMore) {
                    item { CircularProgressIndicator(modifier = Modifier.padding(8.dp)) }
                }
            }
        }
    }
}

@Composable
private fun TypeBadge(text: String) {
    Text(
        text, color = Color.Black, fontSize = 10.sp, fontWeight = FontWeight.Bold,
        modifier = Modifier
            .background(PureAccent, RoundedCornerShape(4.dp))
            .padding(horizontal = 5.dp, vertical = 2.dp)
    )
}

@Composable
private fun PlaylistHitRow(
    title: String,
    channelName: String,
    thumbnailUrl: String,
    streamCount: Long,
    onClick: () -> Unit,
    onBookmark: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(horizontal = 4.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(width = 128.dp, height = 72.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(Color(0xFF111111))
        ) {
            AsyncImage(
                model = thumbnailUrl, contentDescription = title,
                contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()
            )
            Box(modifier = Modifier.align(Alignment.BottomStart).padding(4.dp)) { TypeBadge("PLAYLIST") }
        }
        Column(modifier = Modifier.weight(1f).padding(start = 10.dp)) {
            Text(title, color = Color.White, fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(channelName, color = PureTextSecondary, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (streamCount > 0) {
                Text("$streamCount videos", color = PureTextSecondary, fontSize = 12.sp)
            }
        }
        TextButton(onClick = onBookmark) { Text("Bookmark", fontSize = 12.sp) }
    }
}

@Composable
private fun PlaylistHitCard(
    title: String,
    channelName: String,
    thumbnailUrl: String,
    streamCount: Long,
    onClick: () -> Unit
) {
    Column(modifier = Modifier.clickable { onClick() }) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .clip(RoundedCornerShape(8.dp))
                .background(Color(0xFF111111))
        ) {
            AsyncImage(
                model = thumbnailUrl, contentDescription = title,
                contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()
            )
            Box(modifier = Modifier.align(Alignment.BottomStart).padding(4.dp)) { TypeBadge("PLAYLIST") }
        }
        Text(title, color = Color.White, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(channelName, color = PureTextSecondary, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun ChannelHitRow(
    name: String,
    avatarUrl: String,
    subscriberCount: Long,
    description: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(horizontal = 4.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        AsyncImage(
            model = avatarUrl, contentDescription = name,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(52.dp)
                .clip(CircleShape)
                .background(Color(0xFF111111))
        )
        Column(modifier = Modifier.weight(1f).padding(start = 12.dp)) {
            Text(name, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (subscriberCount >= 0) {
                Text("$subscriberCount subscribers", color = PureTextSecondary, fontSize = 12.sp)
            }
            if (description.isNotBlank()) {
                Text(description, color = PureTextSecondary, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        TypeBadge("CHANNEL")
    }
}

@Composable
private fun ChannelHitCard(
    name: String,
    avatarUrl: String,
    subscriberCount: Long,
    onClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .clickable { onClick() }
            .padding(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        AsyncImage(
            model = avatarUrl, contentDescription = name,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(64.dp)
                .clip(CircleShape)
                .background(Color(0xFF111111))
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(name, color = Color.White, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (subscriberCount >= 0) {
            Text("$subscriberCount subs", color = PureTextSecondary, fontSize = 11.sp)
        }
        TypeBadge("CHANNEL")
    }
}
