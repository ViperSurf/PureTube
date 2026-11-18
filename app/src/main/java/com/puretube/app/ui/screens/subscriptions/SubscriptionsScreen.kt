package com.puretube.app.ui.screens.subscriptions

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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.puretube.app.PureTubeApp
import com.puretube.app.data.local.datastore.FeedFetchMap
import com.puretube.app.data.local.entity.FilterType
import com.puretube.app.data.local.entity.SubscriptionEntity
import com.puretube.app.data.remote.extractor.FeedItem
import com.puretube.app.data.remote.extractor.ShortsFilterEngine
import com.puretube.app.domain.LikeFilters
import com.puretube.app.ui.components.VideoCardItem
import com.puretube.app.ui.theme.PureBorder
import com.puretube.app.ui.theme.PureTextSecondary
import kotlinx.coroutines.launch

/**
 * Subscriptions home (v1.1): "All" chip (default) aggregates every channel's
 * newest videos; per-channel chips filter to one channel. Per-channel fetch
 * results respect the feed-update threshold; Refresh bypasses it. Manual
 * channel add by URL / @handle / ID.
 */
@Composable
fun SubscriptionsScreen(
    onVideoClick: (String) -> Unit,
    onChannelClick: (String) -> Unit = {}
) {
    val context = LocalContext.current
    val app = context.applicationContext as PureTubeApp
    val scope = rememberCoroutineScope()
    val prefs by app.preferences.state.collectAsState(initial = app.currentPrefs)
    val subscriptions by app.database.subscriptionDao().observeAll()
        .collectAsState(initial = emptyList())
    val history by app.database.watchProgressDao().observeHistory()
        .collectAsState(initial = emptyList())

    var selectedChannelId by remember { mutableStateOf<String?>(null) }
    var feedItems by remember { mutableStateOf<List<FeedItem>>(emptyList()) }
    var feedStatus by remember { mutableStateOf("") }
    var feedCache by remember { mutableStateOf<Map<String, List<FeedItem>>>(emptyMap()) }
    var addChannelText by remember { mutableStateOf("") }
    var addStatus by remember { mutableStateOf("") }
    var channelMatches by remember { mutableStateOf<List<com.puretube.app.data.remote.extractor.ChannelHit>>(emptyList()) }

    suspend fun loadFeed(force: Boolean) {
        if (subscriptions.isEmpty()) {
            feedItems = emptyList()
            feedStatus = ""
            return
        }
        val targets = if (selectedChannelId == null) {
            subscriptions
        } else {
            subscriptions.filter { it.channelId == selectedChannelId }
        }
        val lastFetch = runCatching { app.preferences.feedLastFetchMap() }.getOrDefault(emptyMap())
        val now = System.currentTimeMillis()
        val merged = mutableListOf<FeedItem>()
        var errors = 0
        for (sub in targets) {
            val cached = feedCache[sub.channelId]
            val stale = FeedFetchMap.isStale(lastFetch[sub.channelId], now, prefs.feedUpdateThresholdMinutes)
            if (!force && cached != null && !stale) {
                merged += cached
                continue
            }
            val result = app.streamRepository.fetchChannel(sub.channelId)
            result.onSuccess { payload ->
                feedCache = feedCache + (sub.channelId to payload.videos)
                runCatching { app.preferences.setFeedLastFetch(sub.channelId, now) }
                merged += payload.videos
            }.onFailure {
                errors++
                cached?.let { merged += it }
            }
        }
        val blocked = runCatching { app.database.blocklistDao().getAll() }.getOrDefault(emptyList())
        var filtered = ShortsFilterEngine.filterItems(merged, prefs.stripShortsEverywhere)
        filtered = ShortsFilterEngine.filterByBlockedKeywords(
            filtered,
            blocked.filter { it.filterType == FilterType.KEYWORD }.map { it.pattern },
            blocked.filter { it.filterType == FilterType.CHANNEL_ID }.map { it.pattern },
            blocked.filter { it.filterType == FilterType.CHANNEL_NAME }.map { it.pattern }
        )
        filtered = LikeFilters.filterContent(filtered, prefs.contentMinLikes, prefs.contentMaxLikes)
        if (prefs.showUnwatchedOnlyInSubs) {
            val completed = history.filter { it.isCompleted }.map { it.videoId }.toSet()
            filtered = filtered.filterNot { it.videoId in completed }
        }
        feedItems = filtered
            .distinctBy { it.videoId }
            .sortedByDescending { it.uploadEpochMs }
        feedStatus = when {
            feedItems.isEmpty() && errors > 0 -> "Could not refresh the feed right now. Pull Refresh to try again."
            errors > 0 -> "Some channels could not be refreshed; showing what loaded."
            else -> ""
        }
    }

    LaunchedEffect(subscriptions, selectedChannelId) {
        runCatching { loadFeed(force = false) }
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
        contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Column {
                Text("Subscriptions", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = prefs.showUnwatchedOnlyInSubs,
                        onClick = {
                            scope.launch {
                                app.preferences.update {
                                    it.copy(showUnwatchedOnlyInSubs = !it.showUnwatchedOnlyInSubs)
                                }
                            }
                        },
                        label = { Text("Unwatched only") }
                    )
                    TextButton(onClick = { scope.launch { runCatching { loadFeed(force = true) } } }) {
                        Text("Refresh")
                    }
                }
                Spacer(modifier = Modifier.height(6.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    item {
                        FilterChip(
                            selected = selectedChannelId == null,
                            onClick = { selectedChannelId = null },
                            label = { Text("All") }
                        )
                    }
                    items(subscriptions, key = { it.channelId }) { sub ->
                        FilterChip(
                            selected = selectedChannelId == sub.channelId,
                            onClick = { selectedChannelId = sub.channelId },
                            label = { Text(sub.channelName) }
                        )
                    }
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
                Text("Add a channel", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                Spacer(modifier = Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = addChannelText,
                        onValueChange = { addChannelText = it },
                        placeholder = { Text("Channel name, @handle, URL, or ID") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    Button(onClick = {
                        val input = addChannelText.trim()
                        if (input.isBlank()) return@Button
                        scope.launch {
                            addStatus = "Resolving…"
                            channelMatches = emptyList()
                            suspend fun addByUrl(url: String) {
                                app.streamRepository.fetchChannel(url)
                                    .onSuccess { payload ->
                                        app.database.subscriptionDao().upsert(
                                            SubscriptionEntity(
                                                channelId = payload.channelId,
                                                channelName = payload.channelName,
                                                avatarUrl = payload.avatarUrl,
                                                subscriberCountText = null,
                                                addedAtEpochMs = System.currentTimeMillis()
                                            )
                                        )
                                        addChannelText = ""
                                        channelMatches = emptyList()
                                        addStatus = "Added ${payload.channelName}"
                                    }
                                    .onFailure { e ->
                                        addStatus = "Could not resolve that channel: ${com.puretube.app.domain.ErrorText.describe(e, 300)}"
                                    }
                            }
                            when (com.puretube.app.domain.ChannelInput.classify(input)) {
                                com.puretube.app.domain.ChannelInput.Kind.NAME -> {
                                    // Fuzzy: a bare name is a channel SEARCH, never a URL.
                                    app.streamRepository.searchChannels(input)
                                        .onSuccess { results ->
                                            when {
                                                results.isEmpty() ->
                                                    addStatus = "No channel found matching \"$input\". Try the @handle or channel URL."
                                                results.size == 1 -> addByUrl(results.first().url)
                                                else -> {
                                                    channelMatches = results
                                                    addStatus = "Several channels match \"$input\" — tap one to add it:"
                                                }
                                            }
                                        }
                                        .onFailure { e ->
                                            addStatus = "Channel search failed: ${com.puretube.app.domain.ErrorText.describe(e, 300)}"
                                        }
                                }
                                else -> com.puretube.app.domain.ChannelInput.toUrl(input)
                                    ?.let { addByUrl(it) }
                                    ?: run { addStatus = "Could not interpret that input." }
                            }
                        }
                    }) { Text("Add") }
                }
                if (addStatus.isNotBlank()) {
                    Text(addStatus, color = PureTextSecondary, fontSize = 13.sp)
                }
                channelMatches.forEach { match ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                scope.launch {
                                    addStatus = "Adding ${match.name}…"
                                    app.streamRepository.fetchChannel(match.url)
                                        .onSuccess { payload ->
                                            app.database.subscriptionDao().upsert(
                                                SubscriptionEntity(
                                                    channelId = payload.channelId,
                                                    channelName = payload.channelName,
                                                    avatarUrl = payload.avatarUrl,
                                                    subscriberCountText = null,
                                                    addedAtEpochMs = System.currentTimeMillis()
                                                )
                                            )
                                            addChannelText = ""
                                            channelMatches = emptyList()
                                            addStatus = "Added ${payload.channelName}"
                                        }
                                        .onFailure { e ->
                                            addStatus = "Could not add that channel: ${com.puretube.app.domain.ErrorText.describe(e, 300)}"
                                        }
                                }
                            }
                            .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        coil3.compose.AsyncImage(
                            model = match.avatarUrl, contentDescription = match.name,
                            modifier = Modifier.size(36.dp).clip(androidx.compose.foundation.shape.CircleShape)
                        )
                        Column(modifier = Modifier.padding(start = 10.dp)) {
                            Text(match.name, color = Color.White, fontSize = 14.sp)
                            if (match.subscriberCount >= 0) {
                                Text("${match.subscriberCount} subscribers", color = PureTextSecondary, fontSize = 12.sp)
                            }
                        }
                    }
                }
                if (subscriptions.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(4.dp))
                    subscriptions.forEach { sub ->
                        Row(modifier = Modifier.fillMaxWidth()) {
                            TextButton(onClick = { onChannelClick(sub.channelId) }) {
                                Text(sub.channelName, fontSize = 13.sp)
                            }
                            Spacer(modifier = Modifier.weight(1f))
                            TextButton(onClick = {
                                scope.launch { app.database.subscriptionDao().delete(sub.channelId) }
                            }) { Text("Remove", fontSize = 12.sp) }
                        }
                    }
                }
            }
        }
        if (feedStatus.isNotBlank()) {
            item { Text(feedStatus, color = PureTextSecondary, fontSize = 13.sp) }
        }
        if (subscriptions.isEmpty()) {
            item {
                Text(
                    text = "No subscriptions yet. Add a channel above, import a NewPipe JSON or YouTube Takeout CSV from the Library tab, or subscribe from any video.",
                    color = PureTextSecondary,
                    fontSize = 14.sp,
                    lineHeight = 20.sp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, PureBorder, RoundedCornerShape(10.dp))
                        .padding(14.dp)
                )
            }
        } else if (feedItems.isEmpty()) {
            item {
                Text(
                    text = "Nothing in the feed yet. Tap Refresh to load each channel's newest videos.",
                    color = PureTextSecondary, fontSize = 14.sp, lineHeight = 20.sp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, PureBorder, RoundedCornerShape(10.dp))
                        .padding(14.dp)
                )
            }
        }
        items(feedItems, key = { it.videoId }) { item ->
            val progress = history.firstOrNull { it.videoId == item.videoId }
            when (prefs.feedLayout) {
                com.puretube.app.data.local.datastore.FeedLayout.BOTH,
                com.puretube.app.data.local.datastore.FeedLayout.GRID,
                com.puretube.app.data.local.datastore.FeedLayout.THUMBNAIL_ONLY ->
                    com.puretube.app.ui.components.BothRow(
                        item = item, scale = prefs.feedThumbnailScale,
                        onClick = { onVideoClick(item.videoId) }, onChannelClick = onChannelClick
                    )
                com.puretube.app.data.local.datastore.FeedLayout.TITLE_ONLY ->
                    com.puretube.app.ui.components.TitleRow(
                        item = item, onClick = { onVideoClick(item.videoId) }
                    )
                else -> VideoCardItem(
                    item = item,
                    progressFraction = progress?.progressFraction ?: 0f,
                    onClick = { onVideoClick(item.videoId) },
                    onChannelClick = onChannelClick
                )
            }
        }
    }
}
