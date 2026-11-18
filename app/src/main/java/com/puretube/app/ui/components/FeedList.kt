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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.puretube.app.data.local.datastore.FeedLayout
import com.puretube.app.data.remote.extractor.FeedItem
import com.puretube.app.ui.theme.PureTextSecondary

/**
 * Shared feed list honouring the feedLayout preference across Search,
 * Subscriptions, Library history, Channel and Playlist screens:
 * LIST = full cards, GRID = 2-column cards, THUMBNAIL_ONLY = grid of
 * thumbnails + duration, TITLE_ONLY = compact text rows.
 * Renders inside the caller's scroll container via items callbacks — use
 * [FeedListStandalone] when the screen has no other scroll container.
 */
@Composable
fun FeedListStandalone(
    items: List<FeedItem>,
    layout: FeedLayout,
    onVideoClick: (String) -> Unit,
    onChannelClick: ((String) -> Unit)? = null,
    progressFor: (FeedItem) -> Float = { 0f },
    modifier: Modifier = Modifier
) {
    when (layout) {
        FeedLayout.GRID -> LazyVerticalGrid(
            columns = GridCells.Fixed(2),
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
            columns = GridCells.Fixed(2),
            modifier = modifier,
            contentPadding = PaddingValues(4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            gridItems(items, key = { it.videoId }) { item ->
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(16f / 9f)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(0xFF111111))
                        .clickable { onVideoClick(item.videoId) }
                ) {
                    AsyncImage(
                        model = item.thumbnailUrl,
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
        }
        FeedLayout.TITLE_ONLY -> LazyColumn(
            modifier = modifier,
            contentPadding = PaddingValues(vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            items(items, key = { it.videoId }) { item ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onVideoClick(item.videoId) }
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
                }
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
