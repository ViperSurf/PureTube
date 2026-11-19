package com.puretube.app.ui.screens.player

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.view.SurfaceView
import android.view.View
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.media3.common.MimeTypes
import androidx.media3.session.MediaController
import androidx.media3.ui.PlayerView
import com.puretube.app.MainActivity
import com.puretube.app.PureTubeApp
import com.puretube.app.data.WatchLaterHelper
import com.puretube.app.data.local.entity.FilterType
import com.puretube.app.data.local.entity.SubscriptionEntity
import com.puretube.app.data.local.entity.WatchProgressEntity
import com.puretube.app.data.remote.extractor.FeedItem
import com.puretube.app.data.remote.extractor.ResolvedVideoStream
import com.puretube.app.data.remote.sponsorblock.SponsorSegment
import coil3.compose.AsyncImage
import com.puretube.app.domain.AudioOnlySurface
import com.puretube.app.domain.CommentFilters
import com.puretube.app.domain.CommentItem
import com.puretube.app.domain.SponsorRewindSuppression
import com.puretube.app.haramblur.video.VideoBlurMaskOverlay
import com.puretube.app.haramblur.video.VideoFrameSampler
import com.puretube.app.playback.CastRouterController
import com.puretube.app.playback.QueueLogic
import com.puretube.app.playback.SleepTimerMapping
import com.puretube.app.ui.components.AddToPlaylistDialog
import com.puretube.app.ui.theme.PureAccent
import com.puretube.app.ui.theme.PureTextSecondary
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private enum class StreamKind { ADAPTIVE, PROGRESSIVE, AUDIO_ONLY }

/**
 * Full player (v1.1): MediaController-backed PlayerView, HaramBlur mask
 * overlay, HUD pills, hold-to-2x, SponsorBlock auto-skip with rewind
 * suppression, play queue + autoplay, sleep timer, collapsed comments.
 *
 * Crash-hardened: the player opens even when extraction fails, the media
 * controller connects late, models are absent, or a stream URL errors —
 * every failure becomes an in-player message with a fallback chain
 * (adaptive -> progressive -> audio-only) before any error is shown.
 */
private fun fmtMs(ms: Long): String {
    val totalSec = (ms / 1000).coerceAtLeast(0)
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val sec = totalSec % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
}

/** One state chip: label says the state in words; colour says it too. */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun StateChip(
    label: String,
    active: Boolean,
    activeColor: Color,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null
) {
    val bg = if (active) activeColor else Color(0xFF1E1E1E)
    val gesture = if (onLongClick != null) {
        Modifier.combinedClickable(onClick = onClick, onLongClick = onLongClick)
    } else {
        Modifier.clickable { onClick() }
    }
    Box(
        modifier = Modifier
            .background(bg, RoundedCornerShape(16.dp))
            .then(gesture)
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Text(label, color = Color.White, fontSize = 12.sp)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun VideoPlayerScreen(
    videoId: String,
    controller: MediaController?,
    onBack: () -> Unit,
    onVideoClick: (String) -> Unit = {},
    onChannelClick: (String) -> Unit = {},
    onMinimize: () -> Unit = onBack,
    onQueueClick: () -> Unit = {}
) {
    val context = LocalContext.current
    val app = context.applicationContext as PureTubeApp
    val scope = rememberCoroutineScope()
    val prefs by app.preferences.state.collectAsState(initial = app.currentPrefs)
    val playerState by app.playerController.state.collectAsState()
    val queue by app.playerController.queue.collectAsState()

    var status by remember(videoId) { mutableStateOf("Connecting to playback service…") }
    var connectRetries by remember(videoId) { mutableStateOf(0) }
    var resolved by remember(videoId) { mutableStateOf<ResolvedVideoStream?>(null) }
    var segments by remember(videoId) { mutableStateOf<List<SponsorSegment>>(emptyList()) }
    var skippedPill by remember(videoId) { mutableStateOf<Pair<SponsorSegment, Long>?>(null) }
    var skippedUuids by remember(videoId) { mutableStateOf<Set<String>>(emptySet()) }
    var suppressedUuids by remember(videoId) { mutableStateOf<Set<String>>(emptySet()) }
    var dislikeText by remember(videoId) { mutableStateOf("") }
    var displayTitle by remember(videoId) { mutableStateOf("") }
    var showSpeedSlider by remember { mutableStateOf(false) }
    var holdingFastForward by remember { mutableStateOf(false) }
    var playerViewRef by remember { mutableStateOf<PlayerView?>(null) }
    var currentKind by remember(videoId) { mutableStateOf<StreamKind?>(null) }
    var fatalError by remember(videoId) { mutableStateOf<String?>(null) }
    var comments by remember(videoId) { mutableStateOf<List<CommentItem>>(emptyList()) }
    var commentsStatus by remember(videoId) { mutableStateOf("") }
    var commentsExpanded by remember(videoId) { mutableStateOf(false) }
    var sleepMenuOpen by remember { mutableStateOf(false) }
    var showPlaylistDialog by remember { mutableStateOf(false) }
    var actionStatus by remember(videoId) { mutableStateOf("") }
    var sleepEndedNote by remember(videoId) { mutableStateOf(false) }
    var serviceConnected by remember { mutableStateOf(false) }
    // v1.3 controls: OUR HUD (chips + seek area) toggled by a single tap on
    // the video; the PlayerView's own controller is disabled.
    var controlsVisible by remember { mutableStateOf(true) }
    var descriptionExpanded by remember(videoId) { mutableStateOf(false) }
    var positionMs by remember(videoId) { mutableStateOf(0L) }
    var durationMsState by remember(videoId) { mutableStateOf(0L) }
    val hbStatus by app.haramBlurEngine.statusFlow.collectAsState()

    // Auto-hide our controls after ~3s while playing; any tap brings them back.
    LaunchedEffect(controlsVisible, playerState.isPlaying) {
        if (controlsVisible && playerState.isPlaying) {
            delay(3000L)
            controlsVisible = false
        }
    }
    // Live position for the seek area.
    LaunchedEffect(controller) {
        val activeController = controller ?: return@LaunchedEffect
        while (true) {
            delay(500L)
            runCatching {
                positionMs = activeController.currentPosition.coerceAtLeast(0L)
                durationMsState = activeController.duration.coerceAtLeast(0L)
            }
        }
    }
    LaunchedEffect(Unit) { runCatching { app.haramBlurEngine.ensureInitialized() } }

    val sampler = remember { VideoFrameSampler(app.haramBlurEngine, scope) }
    val regions by sampler.activeBlurRegions.collectAsState()
    val initialCleared by sampler.isInitialFrameCleared.collectAsState()
    val castController = remember { CastRouterController(context) }
    val subscriptions by app.database.subscriptionDao().observeAll()
        .collectAsState(initial = emptyList())

    DisposableEffect(Unit) {
        runCatching { castController.startDiscovery() }
        onDispose {
            runCatching { castController.stopDiscovery() }
            runCatching { sampler.release() }
        }
    }

    fun buildItem(stream: ResolvedVideoStream, kind: StreamKind, startMs: Long): MediaItem? {
        val uri = when (kind) {
            StreamKind.ADAPTIVE -> stream.dashManifestUrl ?: stream.hlsUrl
            StreamKind.PROGRESSIVE -> stream.videoStreamUrl
            StreamKind.AUDIO_ONLY -> stream.audioOnlyStreamUrl
        } ?: return null
        val mime = when {
            uri == stream.dashManifestUrl -> MimeTypes.APPLICATION_MPD
            uri == stream.hlsUrl -> MimeTypes.APPLICATION_M3U8
            else -> null
        }
        return MediaItem.Builder()
            .setUri(uri)
            .setMediaId(stream.videoId)
            .setMimeType(mime)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(stream.title)
                    .setArtist(stream.uploaderName)
                    .build()
            )
            .build()
    }

    fun playKind(
        activeController: MediaController,
        stream: ResolvedVideoStream,
        kind: StreamKind,
        startMs: Long
    ): Boolean {
        val item = buildItem(stream, kind, startMs) ?: return false
        return runCatching {
            activeController.setMediaItem(item, startMs)
            activeController.prepare()
            activeController.playWhenReady = true
            activeController.setPlaybackSpeed(prefs.defaultPlaybackSpeed)
            currentKind = kind
            app.playerController.markSessionLoaded(stream.videoId, stream)
            true
        }.getOrDefault(false)
    }

    // Service connection retry loop: never a silent dead end.
    LaunchedEffect(controller, videoId) {
        if (controller != null) {
            serviceConnected = true
            return@LaunchedEffect
        }
        serviceConnected = false
        var attempt = 0
        while (true) {
            delay(3000L)
            attempt++
            connectRetries = attempt
            status = "Connecting to playback service… (retry $attempt). " +
                "If this persists, force-stop and reopen PureTube."
        }
    }

    // Resolve the stream and start playback (runs when the controller arrives).
    LaunchedEffect(controller, videoId) {
        val activeController = controller ?: return@LaunchedEffect
        // v1.3 reopen freeze fix: if this videoId is ALREADY the loaded item
        // (full -> dock -> floating -> full), attach instantly from the
        // playback-session cache — no re-extraction, no setMediaItem.
        val currentMediaId = runCatching { activeController.currentMediaItem?.mediaId }.getOrNull()
        if (app.playerController.shouldReuseSession(videoId, currentMediaId)) {
            val cached = app.playerController.cachedStreamFor(videoId)
            if (cached != null) {
                resolved = cached
                displayTitle = cached.title
            }
            status = ""
            fatalError = null
            // Re-apply the single-source audio-only state on this attach.
            app.playerController.setAudioOnly(playerState.audioOnly)
            if (prefs.sponsorBlockEnabled && segments.isEmpty()) {
                segments = runCatching {
                    app.sponsorBlockRepository.fetchSponsorSegments(videoId, prefs.sponsorCategories)
                }.getOrDefault(emptyList())
            }
            return@LaunchedEffect
        }
        status = "Resolving stream..."
        fatalError = null
        runCatching { app.streamRepository.configureContentCountry(prefs.contentCountry.code) }
        val saved = runCatching { app.database.watchProgressDao().get(videoId) }.getOrNull()
        val startMs = when {
            prefs.alwaysStartFromBeginning -> 0L
            !prefs.resumePlaybackEnabled -> 0L
            else -> saved?.positionMs ?: 0L
        }
        app.streamRepository.extractStreamWithFailover(
            rawUrlOrId = videoId,
            preferredMaxHeight = prefs.defaultWifiQualityP,
            preferAv1Vp9 = prefs.preferAv1Vp9HardwareCodec,
            preferredAudioLanguageCode = prefs.preferredAudioLanguage.code
        ).onSuccess { stream ->
            resolved = stream
            displayTitle = stream.title
            val started = playKind(activeController, stream, StreamKind.ADAPTIVE, startMs) ||
                playKind(activeController, stream, StreamKind.PROGRESSIVE, startMs) ||
                playKind(activeController, stream, StreamKind.AUDIO_ONLY, startMs)
            status = if (started) "" else "No playable stream URL was returned for this video."
            if (started) {
                if (prefs.launchInAudioOnly && !playerState.audioOnly) {
                    app.playerController.setAudioOnly(true)
                    scope.launch { app.preferences.update { it.copy(audioOnlyMode = true) } }
                }
                runCatching { sampler.onVideoSeekOrSourceChange(app.currentPrefs) }
                if (prefs.sponsorBlockEnabled) {
                    segments = runCatching {
                        app.sponsorBlockRepository.fetchSponsorSegments(
                            stream.videoId, prefs.sponsorCategories
                        )
                    }.getOrDefault(emptyList())
                }
                if (prefs.returnYouTubeDislikeEnabled) {
                    runCatching {
                        app.dislikeRepository.fetchDislikeStats(stream.videoId)?.let { stats ->
                            dislikeText = "Likes ${stats.likes} - Dislikes ${stats.dislikes}"
                        }
                    }
                }
                if (prefs.deArrowTitlesEnabled) {
                    runCatching {
                        app.deArrowRepository.fetch(stream.videoId)?.neutralTitle?.let { displayTitle = it }
                    }
                }
            }
        }.onFailure { error ->
            status = ""
            fatalError = "Could not load this video: ${error.message ?: "extraction failed"}"
        }
    }

    // In-playback fallback chain on player errors:
    // chosen URL -> progressive videoStreamUrl (once) -> audioOnlyStreamUrl -> message.
    LaunchedEffect(playerState.playerError, resolved) {
        val error = playerState.playerError ?: return@LaunchedEffect
        val stream = resolved ?: run {
            fatalError = "Playback failed before the stream resolved: $error"
            return@LaunchedEffect
        }
        val activeController = controller ?: return@LaunchedEffect
        val pos = runCatching { activeController.currentPosition }.getOrDefault(0L)
        val next = when (currentKind) {
            StreamKind.ADAPTIVE -> StreamKind.PROGRESSIVE
            StreamKind.PROGRESSIVE -> StreamKind.AUDIO_ONLY
            else -> null
        }
        app.playerController.clearError()
        if (next != null && playKind(activeController, stream, next, pos)) {
            status = "Stream failed — fell back to ${next.name.lowercase().replace('_', ' ')}."
        } else {
            fatalError = "Playback error: $error"
        }
    }

    // Autoplay / queue advance on STATE_ENDED.
    DisposableEffect(videoId) {
        app.playerController.onPlaybackEnded = {
            scope.launch {
                if (!prefs.autoplayEnabled) return@launch
                val next = app.playerController.popNextQueued()
                if (next != null) {
                    onVideoClick(next.videoId)
                    return@launch
                }
                // Auto-queue from the same channel, honouring duration rules.
                val stream = resolved
                if (stream != null) {
                    val channel = runCatching {
                        app.streamRepository.fetchChannel(stream.uploaderId)
                    }.getOrNull()?.getOrNull()
                    val candidates = QueueLogic.filterAutoQueueCandidates(
                        channel?.videos.orEmpty().filter { it.videoId != videoId },
                        shortOnly = prefs.autoQueueShortOnly,
                        maxMinutes = prefs.autoQueueMaxMinutes
                    )
                    candidates.firstOrNull()?.let { onVideoClick(it.videoId) }
                }
            }
        }
        onDispose { app.playerController.onPlaybackEnded = null }
    }

    LaunchedEffect(playerState.sleepTimerExpired) {
        if (playerState.sleepTimerExpired) {
            sleepEndedNote = true
            app.playerController.consumeSleepExpired()
            delay(5000L)
            sleepEndedNote = false
        }
    }

    // Preselect the sleep-timer default once per video.
    LaunchedEffect(videoId) {
        val minutes = SleepTimerMapping.coerceDefault(prefs.sleepTimerDefaultMinutes)
        if (minutes > 0) app.playerController.setSleepTimer(minutes)
    }

    // SponsorBlock auto-skip loop with rewind suppression.
    LaunchedEffect(controller, segments, suppressedUuids) {
        val activeController = controller ?: return@LaunchedEffect
        var lastPos = -1L
        while (true) {
            delay(400L)
            if (segments.isEmpty()) continue
            val pos = runCatching { activeController.currentPosition }.getOrDefault(-1L)
            if (pos < 0) continue
            // Manual rewind back before/into an already-skipped segment
            // marks it do-not-skip-again for this session.
            if (lastPos >= 0 && pos < lastPos - 2000L) {
                suppressedUuids = suppressedUuids + SponsorRewindSuppression.onManualSeek(
                    skippedUuids = skippedUuids,
                    segments = segments.map { it.uuid to (it.startMs..it.endMs) },
                    seekFromMs = lastPos,
                    seekToMs = pos,
                    pauseSkipOnRewind = prefs.sponsorBlockPauseSkipOnRewind
                )
            }
            lastPos = pos
            val hit = segments.firstOrNull { pos >= it.startMs && pos < it.endMs }
            if (hit != null &&
                !SponsorRewindSuppression.shouldSuppress(hit.uuid, suppressedUuids, prefs.sponsorBlockPauseSkipOnRewind)
            ) {
                runCatching { activeController.seekTo(hit.endMs) }
                skippedUuids = skippedUuids + hit.uuid
                if (prefs.sponsorBlockNotifyOnSkip) {
                    skippedPill = hit to hit.startMs
                }
            }
        }
    }

    LaunchedEffect(skippedPill) {
        if (skippedPill != null) {
            delay(4000L)
            skippedPill = null
        }
    }

    // Periodic progress persistence (respecting history/resume preferences).
    LaunchedEffect(controller, resolved) {
        val activeController = controller ?: return@LaunchedEffect
        val stream = resolved ?: return@LaunchedEffect
        while (true) {
            delay(5000L)
            if (!prefs.watchHistoryEnabled) continue
            val pos = runCatching { activeController.currentPosition }.getOrDefault(0L)
            val dur = runCatching { activeController.duration.coerceAtLeast(0L) }.getOrDefault(0L)
            if (dur > 0) {
                runCatching {
                    app.database.watchProgressDao().upsert(
                        WatchProgressEntity(
                            videoId = stream.videoId,
                            title = stream.title,
                            channelName = stream.uploaderName,
                            channelId = stream.uploaderId,
                            thumbnailUrl = stream.thumbnailUrl,
                            positionMs = if (prefs.resumePlaybackEnabled) pos else 0L,
                            durationMs = dur,
                            isCompleted = WatchProgressEntity.computeCompleted(pos, dur),
                            lastUpdatedEpochMs = System.currentTimeMillis()
                        )
                    )
                }
            }
        }
    }

    LaunchedEffect(playerState.audioOnly) {
        if (playerState.audioOnly) runCatching { sampler.stopSampling() }
    }

    // Start the frame sampler once the PlayerView surface exists.
    LaunchedEffect(playerViewRef, controller) {
        val view = playerViewRef ?: return@LaunchedEffect
        val surface = view.videoSurfaceView as? SurfaceView ?: return@LaunchedEffect
        val activeController = controller ?: return@LaunchedEffect
        runCatching {
            sampler.startSampling(
                surfaceView = surface,
                prefsProvider = { app.currentPrefs },
                isPlayingProvider = { runCatching { activeController.isPlaying }.getOrDefault(false) }
            )
        }
    }

    fun copyDiagnostics() {
        val text = buildString {
            appendLine("PureTube diagnostics")
            appendLine("Video: $videoId")
            appendLine("Title: ${displayTitle.ifBlank { resolved?.title ?: "" }}")
            appendLine("Status: $status")
            appendLine("Fatal: ${fatalError ?: "none"}")
            appendLine("Player error: ${playerState.playerError ?: "none"}")
            appendLine("Stream kind: ${currentKind?.name ?: "none"}")
            appendLine("Source stage: ${resolved?.sourceStage ?: "none"}")
            appendLine("DASH: ${resolved?.dashManifestUrl != null} HLS: ${resolved?.hlsUrl != null}")
            appendLine("Progressive: ${resolved?.videoStreamUrl != null}")
            val log = resolved?.diagnosticsLog?.takeIf { it.isNotBlank() }
            if (log != null) {
                appendLine("Extraction log:")
                appendLine(log)
            }
        }
        runCatching {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("PureTube diagnostics", text))
            actionStatus = "Diagnostics copied"
        }
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
            if (controller != null && playerState.audioOnly) {
                // Audio Only: the video picture is REMOVED — placeholder with
                // artwork + title + label. No PlayerView exists in this
                // state, so no surface can show video. Turning Audio Only
                // off re-creates the PlayerView below; its player setter
                // re-binds the video surface (the renderer was already
                // re-enabled first by PlayerController.setAudioOnly, in
                // that order), so the picture returns in place.
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color(0xFF0D0D0D))
                ) {
                    AsyncImage(
                        model = resolved?.thumbnailUrl ?: "",
                        contentDescription = displayTitle,
                        modifier = Modifier.fillMaxSize()
                    )
                    Column(
                        modifier = Modifier
                            .align(Alignment.Center)
                            .background(Color(0xCC000000))
                            .padding(horizontal = 14.dp, vertical = 8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = displayTitle.ifBlank { resolved?.title ?: "" },
                            color = Color.White, fontSize = 14.sp,
                            maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                        )
                        Text("Audio Only", color = Color(0xFFFFB300), fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    }
                }
            } else if (controller != null) {
                AndroidView(
                    factory = { ctx ->
                        PlayerView(ctx).apply {
                            player = controller
                            // OUR controls are the only controls (v1.3): the
                            // PlayerView controller stays disabled so Bara is
                            // never left with a controller that eats taps.
                            useController = false
                            playerViewRef = this
                        }
                    },
                    update = { view ->
                        view.player = controller
                        playerViewRef = view
                    },
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Text(
                    text = status,
                    color = PureTextSecondary,
                    fontSize = 14.sp,
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(16.dp)
                )
            }
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        val marginPx = 24.dp.toPx()
                        detectTapGestures(
                            onTap = {
                                // Single tap toggles OUR HUD (chips + seek
                                // area). It can never strand the user: the
                                // next tap always brings the controls back.
                                controlsVisible = !controlsVisible
                            },
                            onPress = { offset ->
                                if (offset.x < marginPx || offset.x > size.width - marginPx) {
                                    tryAwaitRelease()
                                    return@detectTapGestures
                                }
                                val previousSpeed = controller?.playbackParameters?.speed ?: 1.0f
                                val holdJob = scope.launch {
                                    delay(220L)
                                    runCatching { controller?.setPlaybackSpeed(2.0f) }
                                    holdingFastForward = true
                                }
                                tryAwaitRelease()
                                holdJob.cancel()
                                if (holdingFastForward) {
                                    runCatching { controller?.setPlaybackSpeed(previousSpeed) }
                                    holdingFastForward = false
                                }
                            },
                            onDoubleTap = { offset ->
                                val activeController = controller ?: return@detectTapGestures
                                val seekMs = prefs.doubleTapSeekSeconds * 1000L
                                runCatching {
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
                            }
                        )
                    }
            )
            if (controlsVisible && controller != null) {
                Box(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .background(Color(0x99000000), androidx.compose.foundation.shape.CircleShape)
                        .clickable { app.playerController.togglePlayPause() }
                        .padding(14.dp)
                ) {
                    Text(
                        text = if (playerState.isPlaying) "❚❚" else "▶",
                        color = Color.White, fontSize = 22.sp
                    )
                }
            }
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
                        runCatching { controller?.seekTo(undoPos) }
                        skippedPill = null
                    },
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 10.dp)
                )
            }
        }

        if (controlsVisible) {
            // Seek / progress area (part of OUR HUD, toggled with the chips).
            Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 2.dp)) {
                Slider(
                    value = if (durationMsState > 0) {
                        (positionMs.toFloat() / durationMsState.toFloat()).coerceIn(0f, 1f)
                    } else 0f,
                    onValueChange = { frac ->
                        if (durationMsState > 0) {
                            runCatching { controller?.seekTo((frac * durationMsState).toLong()) }
                        }
                    }
                )
                Row(modifier = Modifier.fillMaxWidth()) {
                    Text(fmtMs(positionMs), color = PureTextSecondary, fontSize = 11.sp)
                    Spacer(modifier = Modifier.weight(1f))
                    Text(fmtMs(durationMsState), color = PureTextSecondary, fontSize = 11.sp)
                }
            }
            // ONE row of state chips (wraps on narrow screens). Each chip
            // shows its state in words + colour; tap toggles.
            FlowRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                StateChip(
                    label = AudioOnlySurface.soundLabel(playerState.isMuted),
                    active = playerState.isMuted,
                    activeColor = Color(0xFFB3261E),
                    onClick = { app.playerController.toggleMute() }
                )
                StateChip(
                    label = AudioOnlySurface.label(playerState.audioOnly),
                    active = playerState.audioOnly,
                    activeColor = Color(0xFFB3261E),
                    onClick = {
                        val next = !playerState.audioOnly
                        app.playerController.setAudioOnly(next)
                        scope.launch { app.preferences.update { it.copy(audioOnlyMode = next) } }
                    }
                )
                Box {
                    StateChip(
                        label = if (playerState.sleepTimerMinutes > 0) {
                            "Sleep ${playerState.sleepTimerMinutes}m"
                        } else "Sleep: Off",
                        active = playerState.sleepTimerMinutes > 0,
                        activeColor = PureAccent,
                        onClick = { sleepMenuOpen = true }
                    )
                    DropdownMenu(expanded = sleepMenuOpen, onDismissRequest = { sleepMenuOpen = false }) {
                        SleepTimerMapping.optionsMinutes.forEach { minutes ->
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        SleepTimerMapping.labelFor(minutes) +
                                            if (minutes == SleepTimerMapping.coerceDefault(prefs.sleepTimerDefaultMinutes) && minutes > 0) " (default)" else ""
                                    )
                                },
                                onClick = {
                                    app.playerController.setSleepTimer(minutes)
                                    sleepMenuOpen = false
                                }
                            )
                        }
                    }
                }
                // PiP sits immediately right of the Sleep Timer chip.
                StateChip(
                    label = "PiP",
                    active = false,
                    activeColor = PureAccent,
                    onClick = { (context as? MainActivity)?.enterPipNow() }
                )
                StateChip(
                    label = "Speed ${playerState.playbackSpeed}x",
                    active = playerState.playbackSpeed != 1.0f,
                    activeColor = PureAccent,
                    onClick = { app.playerController.cycleSpeed() },
                    onLongClick = { showSpeedSlider = true }
                )
                StateChip(
                    label = "⌄ Minimize",
                    active = false,
                    activeColor = PureAccent,
                    onClick = { onMinimize() }
                )
                Text(
                    text = "HaramBlur: ${hbStatus.mode}",
                    color = if (hbStatus.faceModelLoaded) PureAccent else PureTextSecondary,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp)
                )
                // Cast: MediaRouteButton needs a FragmentActivity host. Any
                // failure building it hides the button instead of crashing.
                AndroidView(
                    factory = { ctx ->
                        runCatching {
                            MediaRouteButton(ctx).apply {
                                runCatching { setRouteSelector(castController.routeSelector()) }
                            } as View
                        }.getOrElse { View(ctx) }
                    },
                    modifier = Modifier.height(36.dp)
                )
                TextButton(onClick = onBack) { Text("Back", fontSize = 12.sp) }
            }
        }
        if (sleepEndedNote) {
            Text(
                text = "Sleep timer ended",
                color = PureAccent,
                fontSize = 13.sp,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 4.dp)
            )
        }
        if (showSpeedSlider) {
            Column(modifier = Modifier.padding(horizontal = 14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Speed ${playerState.playbackSpeed}x (0.25x - 4.0x)",
                        color = PureTextSecondary,
                        fontSize = 12.sp,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = { showSpeedSlider = false }) {
                        Text("Done", fontSize = 12.sp)
                    }
                }
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
            if (queue.isNotEmpty()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Up next: ${queue.first().title}",
                        color = PureTextSecondary, fontSize = 13.sp,
                        maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = onQueueClick) {
                        Text("Queue (${queue.size})", fontSize = 12.sp)
                    }
                }
            }
            fatalError?.let { error ->
                Text(error, color = Color(0xFFFF8080), fontSize = 14.sp, lineHeight = 20.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { copyDiagnostics() }) { Text("Copy diagnostics", fontSize = 12.sp) }
                    TextButton(onClick = {
                        fatalError = null
                        status = "Retrying…"
                        scope.launch {
                            val stream = resolved
                            val activeController = controller
                            if (stream != null && activeController != null) {
                                playKind(activeController, stream, StreamKind.ADAPTIVE, 0L)
                            }
                        }
                    }) { Text("Retry", fontSize = 12.sp) }
                }
            }
            Text(
                text = displayTitle.ifBlank { resolved?.title ?: "" },
                color = Color.White,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold
            )
            resolved?.let { stream ->
                val feedItem = FeedItem(
                    videoId = stream.videoId,
                    title = stream.title,
                    channelName = stream.uploaderName,
                    channelId = stream.uploaderId,
                    thumbnailUrl = stream.thumbnailUrl,
                    durationSeconds = stream.durationSeconds,
                    url = "https://www.youtube.com/watch?v=${stream.videoId}",
                    likeCount = stream.likeCount,
                    channelAvatarUrl = stream.uploaderAvatarUrl
                )
                Text(
                    text = "${stream.uploaderName} - ${stream.viewCount} views",
                    color = PureAccent,
                    fontSize = 13.sp,
                    modifier = Modifier
                        .padding(top = 4.dp)
                        .background(Color.Transparent)
                        .padding(2.dp)
                )
                TextButton(onClick = { onChannelClick(stream.uploaderId) }) {
                    Text("Open channel: ${stream.uploaderName}", fontSize = 13.sp)
                }
                if (dislikeText.isNotBlank()) {
                    Text(dislikeText, color = PureTextSecondary, fontSize = 13.sp)
                }
                if (prefs.enableDescription && stream.description.isNotBlank()) {
                    TextButton(onClick = { descriptionExpanded = !descriptionExpanded }) {
                        Text(
                            if (descriptionExpanded) "Hide description" else "Show description",
                            fontSize = 13.sp
                        )
                    }
                    if (descriptionExpanded) {
                        Text(
                            stream.description,
                            color = Color.White, fontSize = 13.sp, lineHeight = 19.sp,
                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp)
                        )
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
                val isSubscribed = subscriptions.any { it.channelId == stream.uploaderId }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Button(onClick = {
                        scope.launch {
                            runCatching {
                                if (isSubscribed) {
                                    app.database.subscriptionDao().delete(stream.uploaderId)
                                } else {
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
                            }
                            actionStatus = if (isSubscribed) "Unsubscribed" else "Subscribed"
                        }
                    }) { Text(if (isSubscribed) "Unsubscribe" else "Subscribe", fontSize = 12.sp) }
                    Button(onClick = {
                        scope.launch {
                            val added = runCatching {
                                WatchLaterHelper.toggle(app.database, feedItem)
                            }.getOrDefault(false)
                            actionStatus = if (added) "Added to Watch Later" else "Removed from Watch Later"
                        }
                    }) { Text("Watch Later", fontSize = 12.sp) }
                    Button(onClick = {
                        app.playerController.enqueueWithNotice(feedItem)
                        actionStatus = "Added to queue"
                    }) { Text("Queue (${queue.size})", fontSize = 12.sp) }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    TextButton(onClick = { showPlaylistDialog = true }) {
                        Text("Add to Playlist…", fontSize = 12.sp)
                    }
                    TextButton(onClick = {
                        scope.launch {
                            runCatching {
                                app.database.blocklistDao().add(
                                    com.puretube.app.data.local.entity.BlockedFilterEntity(
                                        pattern = stream.uploaderName,
                                        filterType = FilterType.CHANNEL_NAME
                                    )
                                )
                                app.database.blocklistDao().add(
                                    com.puretube.app.data.local.entity.BlockedFilterEntity(
                                        pattern = stream.uploaderId,
                                        filterType = FilterType.CHANNEL_ID
                                    )
                                )
                            }
                            actionStatus = "Channel blocked"
                        }
                    }) { Text("Block channel", fontSize = 12.sp) }
                }
                if (showPlaylistDialog) {
                    AddToPlaylistDialog(item = feedItem, onDismiss = { showPlaylistDialog = false })
                }
            }
            if (actionStatus.isNotBlank()) {
                Text(actionStatus, color = PureAccent, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
            }

            // Queue
            if (queue.isNotEmpty()) {
                Spacer(modifier = Modifier.height(10.dp))
                Text("Up next (${queue.size})", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                queue.forEach { queued ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        TextButton(onClick = { onVideoClick(queued.videoId) }) {
                            Text(queued.title, fontSize = 13.sp, maxLines = 1)
                        }
                        Spacer(modifier = Modifier.weight(1f))
                        TextButton(onClick = { app.playerController.removeFromQueue(queued.videoId) }) {
                            Text("Remove", fontSize = 12.sp)
                        }
                    }
                }
                TextButton(onClick = { app.playerController.clearQueue() }) {
                    Text("Clear queue", fontSize = 12.sp)
                }
            }

            if (prefs.enableComments) {
                // Comments: collapsed by default, loaded on expand.
                Spacer(modifier = Modifier.height(10.dp))
                TextButton(onClick = {
                    commentsExpanded = !commentsExpanded
                    if (commentsExpanded && comments.isEmpty()) {
                        scope.launch {
                            commentsStatus = "Loading comments…"
                            val blocked = runCatching { app.database.blocklistDao().getAll() }
                                .getOrDefault(emptyList())
                            app.streamRepository.fetchComments(videoId)
                                .onSuccess { fetched ->
                                    comments = CommentFilters.filter(
                                        comments = fetched,
                                        blockedKeywords = blocked.filter { it.filterType == FilterType.COMMENT_KEYWORD }.map { it.pattern },
                                        blockedUsernames = blocked.filter { it.filterType == FilterType.COMMENT_USERNAME }.map { it.pattern },
                                        blockedChannelIds = blocked.filter { it.filterType == FilterType.COMMENT_CHANNEL_ID }.map { it.pattern },
                                        minLikes = prefs.commentMinLikes,
                                        maxLikes = prefs.commentMaxLikes
                                    )
                                    commentsStatus = if (comments.isEmpty()) {
                                        "No comments to show (disabled, filtered, or unavailable)."
                                    } else ""
                                }
                                .onFailure { e ->
                                    commentsStatus = "Comments unavailable: ${e.message ?: "load failed"}"
                                }
                        }
                    }
                }) {
                    Text(if (commentsExpanded) "Hide comments" else "Show comments", fontSize = 13.sp)
                }
                if (commentsExpanded) {
                    if (commentsStatus.isNotBlank()) {
                        Text(commentsStatus, color = PureTextSecondary, fontSize = 13.sp)
                    }
                    comments.forEach { comment ->
                        Column(modifier = Modifier.padding(vertical = 6.dp)) {
                            Text(
                                "${comment.authorName} · ${if (comment.likeCount >= 0) "${comment.likeCount} likes" else ""}",
                                color = PureAccent, fontSize = 12.sp
                            )
                            Text(comment.text, color = Color.White, fontSize = 13.sp, lineHeight = 18.sp)
                        }
                    }
                }
            }
            if (prefs.collapseRelatedByDefault) {
                Text(
                    text = "Related videos stay collapsed by your Focus settings.",
                    color = PureTextSecondary,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(top = 10.dp)
                )
            }
        }
    }
}
