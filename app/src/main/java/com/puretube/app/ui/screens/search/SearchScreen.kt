package com.puretube.app.ui.screens.search

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.puretube.app.PureTubeApp
import com.puretube.app.data.local.entity.FilterType
import com.puretube.app.data.remote.extractor.FeedItem
import com.puretube.app.data.remote.extractor.ShortsFilterEngine
import com.puretube.app.ui.components.VideoCardItem
import com.puretube.app.ui.theme.PureTextSecondary
import kotlinx.coroutines.launch

/** Instant search: Shorts stripped, blocked keywords/channels filtered locally. */
@Composable
fun SearchScreen(onVideoClick: (String) -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as PureTubeApp
    val scope = rememberCoroutineScope()
    val prefs by app.preferences.state.collectAsState(initial = app.currentPrefs)
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<FeedItem>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("Search YouTube without Shorts, tracking, or recommendations.") }

    fun runSearch() {
        val q = query.trim()
        if (q.isBlank() || loading) return
        loading = true
        status = "Searching..."
        scope.launch {
            val blocked = runCatching { app.database.blocklistDao().getAll() }.getOrDefault(emptyList())
            val keywords = blocked.filter { it.filterType == FilterType.KEYWORD }.map { it.pattern }
            val channels = blocked.filter { it.filterType == FilterType.CHANNEL_ID }.map { it.pattern }
            app.streamRepository.searchVideos(q)
                .onSuccess { items ->
                    var filtered = ShortsFilterEngine.filterItems(items, prefs.stripShortsEverywhere)
                    filtered = ShortsFilterEngine.filterByBlockedKeywords(filtered, keywords, channels)
                    results = filtered
                    status = if (filtered.isEmpty()) {
                        "No results. Shorts and blocked items are filtered out."
                    } else {
                        "${filtered.size} results"
                    }
                }
                .onFailure { error ->
                    results = emptyList()
                    status = "Search failed: ${error.message ?: "network error"}"
                }
            loading = false
        }
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
                value = query,
                onValueChange = { query = it },
                placeholder = { Text("Search videos") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { runSearch() }),
                modifier = Modifier.weight(1f)
            )
            Button(onClick = { runSearch() }) { Text("Go") }
        }
        if (loading) {
            CircularProgressIndicator(modifier = Modifier.padding(vertical = 12.dp))
        }
        Text(status, color = PureTextSecondary, fontSize = 13.sp)
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(results, key = { it.videoId }) { item ->
                VideoCardItem(item = item, onClick = { onVideoClick(item.videoId) })
            }
        }
    }
}
