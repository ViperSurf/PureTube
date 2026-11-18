package com.puretube.app.ui.screens.player

import android.view.SurfaceView
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.mediarouter.app.MediaRouteButton
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.session.MediaController
import androidx.media3.ui.PlayerView
import com.puretube.app.MainActivity
import com.puretube.app.PureTubeApp
import com.puretube.app.data.local.entity.SubscriptionEntity
import com.puretube.app.data.local.entity.WatchProgressEntity
import com.puretube.app.data.remote.extractor.ResolvedVideoStream
import com.puretube.app.data.remote.sponsorblock.SponsorSegment
import com.puretube.app.haramblur.video.VideoBlurMaskOverlay
import com.puretube.app.haramblur.video.VideoFrameSampler
import com.puretube.app.playback.CastRouterController
import com.puretube.app.ui.theme.PureTextSecondary
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Full player: MediaController-backed PlayerView, HaramBlur mask overlay,
 * HUD pills (Audio Only, Mute, Speed, HaramBlur, PiP, Cast), hold-to-2x,
 * edge-safe gestures, SponsorBlock auto-skip with undo.
 */
@Composable
fun VideoPlayerScreen(
    videoId: String,
    controller: MediaController?,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val app = context.applicationContext as PureTubeApp
    val scope = rememberCoroutineScope()
    val prefs by app.preferences.state.collectAsState(initial = app.currentPrefs)
    val playerState by app.playerController.state.collectAsState()

    var status by remember(videoId) { mutableStateOf("Loading stream...") }
    var resolved by remember(videoId) { mutableStateOf<ResolvedVideoStream?>(null) }
    var segments by remember(videoId) { mutableStateOf<List<SponsorSegment>>(emptyList()) }
    var skippedPill by remember(videoId) { mutableStateOf<Pair<SponsorSegment, Long>?>(null) }
    var dislikeText by remember(videoId) { mutableStateOf("") }
    var displayTitle by remember(videoId) { mutableStateOf("") }
    var showSpeedSlider by remember { mutableStateOf(false) }
    var holdingFastForward by remember { mutableStateOf(false) }
    var playerViewRef by remember { mutableStateOf<PlayerView?>(null) }

    val sampler = remember { VideoFrameSampler(app.haramBlurEngine, scope) }
    val regions by sampler.activeBlurRegions.collectAsState()
    val initialCleared by sampler.isInitialFrameCleared.collectAsState()
    val castController = remember { CastRouterController(context) }

    DisposableEffect(Unit) {
        castController.startDiscovery()
        onDispose {
            castController.stopDiscovery()
            sampler.release()
        }
    }

    // Resolve the stream and start playback.
    LaunchedEffect(controller, videoId) {
        val activeController = controller ?: return@LaunchedEffect
        status = "Resolving stream..."
        val saved = runCatching { app.database.watchProgressDao().get(videoId) }.getOrNull()
        app.streamRepository.extractStreamWithFailover(
            rawUrlOrId = videoId,
            preferredMaxHeight = prefs.defaultWifiQualityP,
            preferAv1Vp9 = prefs.preferAv1Vp9HardwareCodec
        ).onSuccess { stream ->
            resolved = stream
            displayTitle = stream.title
            status = ""
            val uri = stream.dashManifestUrl ?: stream.hlsUrl
                ?: stream.videoStreamUrl ?: stream.audioOnlyStreamUrl
            val item = MediaItem.Builder()
                .setUri(uri)
                .setMediaId(stream.videoId)
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle(stream.title)
                        .setArtist(stream.uploaderName)
                        .build()
                )
                .build()
            activeController.setMediaItem(item, saved?.positionMs ?: 0L)
            activeController.prepare()
            activeController.playWhenReady = true
            activeController.setPlaybackSpeed(prefs.defaultPlaybackSpeed)
            sampler.onVideoSeekOrSourceChange(app.currentPrefs)
            if (prefs.sponsorBlockEnabled) {
                segments = app.sponsorBlockRepository.fetchSponsorSegments(
                    stream.videoId, prefs.sponsorCategories
                )
            }
            if (prefs.returnYouTubeDislikeEnabled) {
                app.dislikeRepository.fetchDislikeStats(stream.videoId)?.let { stats ->
                    dislikeText = "Likes ${stats.likes} - Dislikes ${stats.dislikes}"
                }
            }
            if (prefs.deArrowTitlesEnabled) {
                app.deArrowRepository.fetch(stream.videoId)?.neutralTitle?.let { displayTitle = it }
            }
        }.onFailure { error ->
            status = "Could not load this video: ${error.message ?: "extraction failed"}"
        }
    }

    // SponsorBlock auto-skip loop.
    LaunchedEffect(controller, segments) {
        val activeController = controller ?: return@LaunchedEffect
        while (true) {
            delay(400L)
            if (segments.isEmpty()) continue
            val pos = activeController.currentPosition
            val hit = segments.firstOrNull { pos >= it.startMs && pos < it.endMs }
            if (hit != null) {
                activeController.seekTo(hit.endMs)
                skippedPill = hit to hit.startMs
            }
        }
    }

    // Auto-dismiss the skip pill.
    LaunchedEffect(skippedPill) {
        if (skippedPill != null) {
            delay(4000L)
            skippedPill = null
        }
    }

    // Periodic progress persistence (92% completion rule lives in the entity).
    LaunchedEffect(controller, resolved) {
        val activeController = controller ?: return@LaunchedEffect
        val stream = resolved ?: return@LaunchedEffect
        while (true) {
            delay(5000L)
            val pos = activeController.currentPosition
            val dur = activeController.duration.coerceAtLeast(0L)
            if (dur > 0) {
                runCatching {
                    app.database.watchProgressDao().upsert(
                        WatchProgressEntity(
                            videoId = stream.videoId,
                            title = stream.title,
                            channelName = stream.uploaderName,
                            channelId = stream.uploaderId,
                            thumbnailUrl = stream.thumbnailUrl,
                            positionMs = pos,
                            durationMs = dur,
                            isCompleted = WatchProgressEntity.computeCompleted(pos, dur),
                            lastUpdatedEpochMs = System.currentTimeMillis()
                        )
                    )
                }
            }
        }
    }

    // Start the frame sampler once the PlayerView surface exists.
    LaunchedEffect(playerViewRef, controller) {
        val view = playerViewRef ?: return@LaunchedEffect
        val surface = view.videoSurfaceView as? SurfaceView ?: return@LaunchedEffect
        val activeController = controller ?: return@LaunchedEffect
        sampler.startSampling(
            surfaceView = surface,
            prefsProvider = { app.currentPrefs },
            isPlayingProvider = { activeController.isPlaying }
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .verticalScroll(rememberScrollState())
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .background(Color.Black)
        ) {
            if (controller != null) {
                AndroidView(
                    factory = { ctx ->
                        PlayerView(ctx).apply {
                            player = controller
                            useController = true
                            playerViewRef = this
                        }
                    },
                    update = { view ->
                        view.player = controller
                        playerViewRef = view
                    },
                    modifier = Modifier.fillMaxSize()
                )
            }
            // Hold-to-2x gesture layer with 24dp edge-safe margins.
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        val marginPx = 24.dp.toPx()
                        detectTapGestures(
                            onPress = { offset ->
                                if (offset.x < marginPx || offset.x > size.width - marginPx) {
                                    tryAwaitRelease()
                                    return@detectTapGestures
                                }
                                val previousSpeed = controller?.playbackParameters?.speed ?: 1.0f
                                val holdJob = scope.launch {
                                    delay(220L)
                                    controller?.setPlaybackSpeed(2.0f)
                                    holdingFastForward = true
                                }
                                tryAwaitRelease()
                                holdJob.cancel()
                                if (holdingFastForward) {
                                    controller?.setPlaybackSpeed(previousSpeed)
                                    holdingFastForward = false
                                }
                            },
                            onDoubleTap = { offset ->
                                val activeController = controller ?: return@detectTapGestures
                                val seekMs = prefs.doubleTapSeekSeconds * 1000L
                                if (offset.x < size.width / 2f) {
                                    activeController.seekTo(
                                        (activeController.currentPosition - seekMs).coerceAtLeast(0L)
                                    )
                                } else {
                                    activeController.seekTo(
                                        (activeController.currentPosition + seekMs)
                                            .coerceAtMost(maxOf(activeController.duration, 0L))
                                    )
                                }
                            }
                        )
                    }
            )
            VideoBlurMaskOverlay(
                regions = regions,
                visualStyle = prefs.blurVisualStyle,
                initialFrameCleared = initialCleared,
                zeroFlashEnabled = prefs.zeroFlashSafePreBlur
            )
            if (holdingFastForward) {
                Text(
                    text = "2.0x Fast Forward",
                    color = Color.White,
                    fontSize = 12.sp,
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 8.dp)
                        .background(Color(0xCC000000))
                        .padding(horizontal = 10.dp, vertical = 4.dp)
                )
            }
            skippedPill?.let { (segment, undoPos) ->
                SponsorSkipPill(
                    category = segment.category,
                    onUndo = {
                        controller?.seekTo(undoPos)
                        skippedPill = null
                    },
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 10.dp)
                )
            }
        }

        // HUD pills: zero nested menus, everything 1 tap away.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Button(onClick = {
                val next = !playerState.audioOnly
                app.playerController.setAudioOnly(next)
                scope.launch { app.preferences.update { it.copy(audioOnlyMode = next) } }
            }) { Text(if (playerState.audioOnly) "Audio Only: ON" else "Audio Only", fontSize = 12.sp) }
            Button(onClick = { app.playerController.toggleMute() }) {
                Text(if (playerState.isMuted) "Unmute" else "Mute", fontSize = 12.sp)
            }
            Button(onClick = {
                if (showSpeedSlider) showSpeedSlider = false else app.playerController.cycleSpeed()
            }) { Text("Speed ${playerState.playbackSpeed}x", fontSize = 12.sp) }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "HaramBlur: ${if (prefs.protectionScope.name == "OFF") "OFF" else "ON"}",
                color = Color.White,
                fontSize = 12.sp
            )
            TextButton(onClick = { showSpeedSlider = !showSpeedSlider }) {
                Text("Speed slider", fontSize = 12.sp)
            }
            TextButton(onClick = { (context as? MainActivity)?.enterPipNow() }) {
                Text("PiP", fontSize = 12.sp)
            }
            AndroidView(
                factory = { ctx ->
                    MediaRouteButton(ctx).apply {
                        setRouteSelector(castController.routeSelector())
                    }
                },
                modifier = Modifier.height(36.dp)
            )
            TextButton(onClick = onBack) { Text("Back", fontSize = 12.sp) }
        }
        if (showSpeedSlider) {
            Column(modifier = Modifier.padding(horizontal = 14.dp)) {
                Text(
                    "Speed ${playerState.playbackSpeed}x (0.25x - 4.0x)",
                    color = PureTextSecondary,
                    fontSize = 12.sp
                )
                Slider(
                    value = playerState.playbackSpeed,
                    onValueChange = { app.playerController.setSpeed((it * 20).toInt() / 20f) },
                    valueRange = 0.25f..4.0f
                )
            }
        }

        Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)) {
            if (status.isNotBlank()) {
                Text(status, color = PureTextSecondary, fontSize = 14.sp, lineHeight = 20.sp)
            }
            Text(
                text = displayTitle.ifBlank { resolved?.title ?: "" },
                color = Color.White,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold
            )
            resolved?.let { stream ->
                Text(
                    text = "${stream.uploaderName} - ${stream.viewCount} views",
                    color = PureTextSecondary,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(top = 4.dp)
                )
                if (dislikeText.isNotBlank()) {
                    Text(dislikeText, color = PureTextSecondary, fontSize = 13.sp)
                }
                Spacer(modifier = Modifier.height(8.dp))
                Button(onClick = {
                    scope.launch {
                        app.database.subscriptionDao().upsert(
                            SubscriptionEntity(
                                channelId = stream.uploaderId,
                                channelName = stream.uploaderName,
                                avatarUrl = stream.uploaderAvatarUrl,
                                subscriberCountText = null,
                                addedAtEpochMs = System.currentTimeMillis()
                            )
                        )
                    }
                }) { Text("Subscribe", fontSize = 13.sp) }
            }
            if (prefs.collapseCommentsByDefault || prefs.collapseRelatedByDefault) {
                Text(
                    text = "Comments and related videos stay collapsed by your Focus settings.",
                    color = PureTextSecondary,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(top = 10.dp)
                )
            }
        }
    }
}
