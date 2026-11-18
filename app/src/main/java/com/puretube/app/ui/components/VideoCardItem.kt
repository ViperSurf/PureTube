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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import com.puretube.app.data.local.datastore.ProtectedMediaType
import com.puretube.app.data.local.datastore.ProtectionScope
import com.puretube.app.data.remote.extractor.FeedItem
import com.puretube.app.haramblur.engine.HaramBlurCoilTransformation
import com.puretube.app.ui.theme.PureAccent
import com.puretube.app.ui.theme.PureBorder
import com.puretube.app.ui.theme.PureTextSecondary

/**
 * Video card: HaramBlur-protected thumbnail with a 1-tap eye "peek" override,
 * title, channel, and a watch-progress bar when progress is known.
 */
@Composable
fun VideoCardItem(
    item: FeedItem,
    progressFraction: Float = 0f,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val app = context.applicationContext as PureTubeApp
    var peeked by remember(item.videoId) { mutableStateOf(false) }
    val prefs = app.currentPrefs

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
        Text(
            text = item.title,
            color = Color.White,
            fontSize = 15.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = item.channelName,
                color = PureTextSecondary,
                fontSize = 13.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
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
        Spacer(modifier = Modifier.width(0.dp))
    }
}

fun formatDuration(totalSeconds: Long): String {
    val h = totalSeconds / 3600
    val m = (totalSeconds % 3600) / 60
    val s = totalSeconds % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}
