package com.puretube.app.ui.screens.subscriptions

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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.puretube.app.PureTubeApp
import com.puretube.app.data.remote.extractor.FeedItem
import com.puretube.app.ui.components.VideoCardItem
import com.puretube.app.ui.theme.PureBorder
import com.puretube.app.ui.theme.PureTextSecondary
import kotlinx.coroutines.launch

/**
 * Subscriptions home: local feed aggregation over watch history plus the
 * channel list. The "Unwatched only" pill hides completed (92%+) videos.
 */
@Composable
fun SubscriptionsScreen(onVideoClick: (String) -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as PureTubeApp
    val scope = rememberCoroutineScope()
    val prefs by app.preferences.state.collectAsState(initial = app.currentPrefs)
    val subscriptions by app.database.subscriptionDao().observeAll()
        .collectAsState(initial = emptyList())
    val history by app.database.watchProgressDao().observeHistory()
        .collectAsState(initial = emptyList())

    val visibleHistory = if (prefs.showUnwatchedOnlyInSubs) {
        history.filter { !it.isCompleted }
    } else {
        history
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
                Row {
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
                }
            }
        }
        if (subscriptions.isNotEmpty()) {
            item {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(subscriptions, key = { it.channelId }) { sub ->
                        Row(
                            modifier = Modifier
                                .border(1.dp, PureBorder, RoundedCornerShape(10.dp))
                                .padding(horizontal = 10.dp, vertical = 6.dp)
                        ) {
                            Text(sub.channelName, color = Color.White, fontSize = 14.sp)
                            TextButton(onClick = {
                                scope.launch { app.database.subscriptionDao().delete(sub.channelId) }
                            }) {
                                Text("Remove", fontSize = 12.sp)
                            }
                        }
                    }
                }
            }
        }
        if (visibleHistory.isEmpty()) {
            item {
                Text(
                    text = if (subscriptions.isEmpty()) {
                        "No subscriptions yet. Import a NewPipe JSON or YouTube Takeout CSV from the Library tab, then videos you watch will collect here."
                    } else {
                        "Nothing to show yet. Open a video and it will appear here with its progress saved."
                    },
                    color = PureTextSecondary,
                    fontSize = 14.sp,
                    lineHeight = 20.sp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, PureBorder, RoundedCornerShape(10.dp))
                        .padding(14.dp)
                )
            }
        }
        items(visibleHistory, key = { it.videoId }) { progress ->
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
