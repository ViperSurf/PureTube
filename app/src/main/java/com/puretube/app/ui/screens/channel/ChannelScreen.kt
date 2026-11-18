package com.puretube.app.ui.screens.channel

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.puretube.app.PureTubeApp
import com.puretube.app.data.local.entity.SubscriptionEntity
import com.puretube.app.data.remote.extractor.ChannelPayload
import com.puretube.app.data.remote.extractor.ShortsFilterEngine
import com.puretube.app.domain.LikeFilters
import com.puretube.app.ui.components.FeedListStandalone
import com.puretube.app.ui.theme.PureTextSecondary
import kotlinx.coroutines.launch

/**
 * Channel page: name/avatar, Subscribe toggle, and the channel's videos.
 * Every failure is an in-screen message — this screen never crashes.
 */
@Composable
fun ChannelScreen(
    channelIdOrUrl: String,
    onVideoClick: (String) -> Unit,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val app = context.applicationContext as PureTubeApp
    val scope = rememberCoroutineScope()
    val prefs by app.preferences.state.collectAsState(initial = app.currentPrefs)
    var payload by remember(channelIdOrUrl) { mutableStateOf<ChannelPayload?>(null) }
    var status by remember(channelIdOrUrl) { mutableStateOf("Loading channel…") }
    val subscriptions by app.database.subscriptionDao().observeAll()
        .collectAsState(initial = emptyList())

    LaunchedEffect(channelIdOrUrl) {
        app.streamRepository.fetchChannel(channelIdOrUrl)
            .onSuccess { loaded ->
                payload = loaded
                status = ""
            }
            .onFailure { e ->
                status = "Could not load this channel: ${e.message ?: "load failed"}"
            }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .padding(12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("Back") }
            payload?.let { channel ->
                if (channel.avatarUrl.isNotBlank()) {
                    AsyncImage(
                        model = channel.avatarUrl,
                        contentDescription = channel.channelName,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .size(44.dp)
                            .clip(CircleShape)
                    )
                }
                Column(modifier = Modifier.padding(start = 8.dp)) {
                    Text(channel.channelName, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    if (channel.subscriberCount >= 0) {
                        Text("${channel.subscriberCount} subscribers", color = PureTextSecondary, fontSize = 12.sp)
                    }
                }
                Spacer(modifier = Modifier.weight(1f))
                val isSubscribed = subscriptions.any { it.channelId == channel.channelId }
                Button(onClick = {
                    scope.launch {
                        runCatching {
                            if (isSubscribed) {
                                app.database.subscriptionDao().delete(channel.channelId)
                            } else {
                                app.database.subscriptionDao().upsert(
                                    SubscriptionEntity(
                                        channelId = channel.channelId,
                                        channelName = channel.channelName,
                                        avatarUrl = channel.avatarUrl,
                                        subscriberCountText = null,
                                        addedAtEpochMs = System.currentTimeMillis()
                                    )
                                )
                            }
                        }
                    }
                }) { Text(if (isSubscribed) "Unsubscribe" else "Subscribe", fontSize = 12.sp) }
            } ?: Text("Channel", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        }
        if (status.isNotBlank()) {
            Spacer(modifier = Modifier.height(10.dp))
            if (payload == null) CircularProgressIndicator()
            Text(status, color = PureTextSecondary, fontSize = 14.sp, lineHeight = 20.sp)
        }
        payload?.let { channel ->
            val videos = LikeFilters.filterContent(
                ShortsFilterEngine.filterItems(channel.videos, prefs.stripShortsEverywhere),
                prefs.contentMinLikes, prefs.contentMaxLikes
            )
            if (videos.isEmpty()) {
                Text(
                    "No videos to show for this channel right now.",
                    color = PureTextSecondary, fontSize = 14.sp,
                    modifier = Modifier.padding(top = 12.dp)
                )
            } else {
                FeedListStandalone(
                    items = videos,
                    layout = prefs.feedLayout,
                    onVideoClick = onVideoClick,
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
    }
}
