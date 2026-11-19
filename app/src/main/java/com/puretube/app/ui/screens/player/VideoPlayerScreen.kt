package com.puretube.app.ui.screens.player

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.net.Uri
import android.view.TextureView
import android.view.View
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.ClickableText
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PictureInPictureAlt
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.mediarouter.app.MediaRouteButton
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.session.MediaController
import com.puretube.app.MainActivity
import com.puretube.app.PureTubeApp
import com.puretube.app.data.WatchLaterHelper
import com.puretube.app.data.local.datastore.ControlsVisibility
import com.puretube.app.data.local.datastore.VideoAspectMode
import com.puretube.app.data.local.entity.FilterType
import com.puretube.app.data.local.entity.SubscriptionEntity
import com.puretube.app.data.local.entity.WatchProgressEntity
import com.puretube.app.data.remote.extractor.FeedItem
import com.puretube.app.data.remote.extractor.ResolvedVideoStream
import com.puretube.app.data.remote.ryd.DislikeStats
import com.puretube.app.data.remote.sponsorblock.SponsorSegment
import coil3.compose.AsyncImage
import com.puretube.app.domain.AudioOnlySurface
import com.puretube.app.domain.CommentFilters
import com.puretube.app.domain.CommentItem
import com.puretube.app.domain.DescriptionFormatter
import com.puretube.app.domain.HaramBlurExemptions
import com.puretube.app.domain.LikesDisplayMath
import com.puretube.app.domain.PlayerControlsPolicy
import com.puretube.app.domain.SponsorRewindSuppression
import com.puretube.app.domain.PlayerGesturePolicy
import com.puretube.app.domain.RelatedDedupe
import com.puretube.app.domain.SwipeControlMath
import com.puretube.app.domain.VideoRectMath
import kotlin.math.roundToInt
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
 * Full player (v1.4).
 *
 * VIDEO SURFACE: a plain TextureView hosted in an AndroidView, bound with
 * MediaController.setVideoTextureView(...) — PlayerView is gone. The
 * HaramBlur sampler reads frames back with TextureView.getBitmap()
 * (PixelCopy on the old SurfaceView silently produced no frames).
 *
 * GESTURE MAP (video area, all coexisting; v1.5 rework):
 * - Single tap: toggle OUR controls (chips + seek).
 * - Double-tap left/right half: seek -/+ the configured seconds.
 * - Press-and-hold (>220 ms, away from the edges): 2x fast-forward.
 * - PRIMARY: vertical drag DOWN on the video — the WHOLE PAGE follows
 *   the finger as one unit (v2.0: the drag offset is applied to the page
 *   root, not just the video box); release past ~120 dp minimizes to
 *   dock/floating, otherwise it springs back. On the floating window /
 *   dock, drag UP expands.
 * - Drag HANDLE PILL above the title, pulled down past ~120 dp: minimize
 *   (secondary route, always available).
 * - Brightness/volume swipes are OPT-IN (Settings → "Swipe
 *   Brightness/Volume Controls", default OFF): when ON, the LEFT third
 *   drags window brightness and the RIGHT third STREAM_MUSIC volume
 *   (small centre-top % indicator), and minimize drags start from the
 *   CENTRE third or the handle. When OFF those zones do nothing.
 * See PlayerGesturePolicy for the zone logic.
 *
 * LAYOUT: with keepVideoPinnedWhileScrolling on (default), the video +
 * seek + chips are a fixed header and everything below scrolls on its
 * own; off, the whole page is one scroll column (the v1.3 layout).
 * HaramBlur status lives ONLY in Settings now — no status text here.
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
    onQueueClick: () -> Unit = {},
    /** v2.0: signed-out like/subscribe/comment taps route here (Settings → Account). */
    onOpenAccount: () -> Unit = {},
    /** v1.5: landscape on the player route — video fills the screen, chrome hidden. */
    fullscreenLandscape: Boolean = false
) {
    val context = LocalContext.current
    val app = context.applicationContext as PureTubeApp
    val scope = rememberCoroutineScope()
    val prefs by app.preferences.state.collectAsState(initial = app.currentPrefs)
    val playerState by app.playerController.state.collectAsState()
    val queue by app.playerController.queue.collectAsState()
    val exemptKeys by app.exemptChannelKeys.collectAsState()

    var status by remember(videoId) { mutableStateOf("Connecting to playback service…") }
    var connectRetries by remember(videoId) { mutableStateOf(0) }
    var resolved by remember(videoId) { mutableStateOf<ResolvedVideoStream?>(null) }
    var segments by remember(videoId) { mutableStateOf<List<SponsorSegment>>(emptyList()) }
    var skippedPill by remember(videoId) { mutableStateOf<Pair<SponsorSegment, Long>?>(null) }
    var skippedUuids by remember(videoId) { mutableStateOf<Set<String>>(emptySet()) }
    var suppressedUuids by remember(videoId) { mutableStateOf<Set<String>>(emptySet()) }
    var dislikeStats by remember(videoId) { mutableStateOf<DislikeStats?>(null) }
    var displayTitle by remember(videoId) { mutableStateOf("") }
    var showSpeedSlider by remember { mutableStateOf(false) }
    var holdingFastForward by remember { mutableStateOf(false) }
    var textureViewRef by remember { mutableStateOf<TextureView?>(null) }
    var currentKind by remember(videoId) { mutableStateOf<StreamKind?>(null) }
    var fatalError by remember(videoId) { mutableStateOf<String?>(null) }
    var comments by remember(videoId) { mutableStateOf<List<CommentItem>>(emptyList()) }
    var commentsStatus by remember(videoId) { mutableStateOf("") }
    var commentsExpanded by remember(videoId) { mutableStateOf(false) }
    var repliesById by remember(videoId) { mutableStateOf<Map<String, List<CommentItem>>>(emptyMap()) }
    var repliesStatusById by remember(videoId) { mutableStateOf<Map<String, String>>(emptyMap()) }
    var repliesOpen by remember(videoId) { mutableStateOf<Set<String>>(emptySet()) }
    var sleepMenuOpen by remember { mutableStateOf(false) }
    var showPlaylistDialog by remember { mutableStateOf(false) }
    var actionStatus by remember(videoId) { mutableStateOf("") }
    var sleepEndedNote by remember(videoId) { mutableStateOf(false) }
    // v2.0 account actions: -1 disliked, 0 none, 1 liked (optimistic, rolled back on failure).
    var likeState by remember(videoId) { mutableStateOf(0) }
    var likeBusy by remember(videoId) { mutableStateOf(false) }
    var commentDraft by remember(videoId) { mutableStateOf("") }
    var commentPosting by remember(videoId) { mutableStateOf(false) }
    var related by remember(videoId) { mutableStateOf<List<FeedItem>>(emptyList()) }
    var relatedExpanded by remember(videoId) { mutableStateOf(false) }
    // v2.0: pixel size of the video container — drives the displayed-video rect.
    var videoContainerSize by remember {
        mutableStateOf(androidx.compose.ui.unit.IntSize.Zero)
    }
    var serviceConnected by remember { mutableStateOf(false) }
    var controlsVisible by remember { mutableStateOf(true) }
    var descriptionExpanded by remember(videoId) { mutableStateOf(false) }
    var positionMs by remember(videoId) { mutableStateOf(0L) }
    var durationMsState by remember(videoId) { mutableStateOf(0L) }
    var deviceVolume by remember { mutableStateOf(-1) }
    var swipeIndicator by remember { mutableStateOf<String?>(null) }
    var brightnessFraction by remember { mutableStateOf<Float?>(null) }
    // v1.5 minimize drag: the video follows the finger down (live offset),
    // past ~120 dp it minimizes on release; below that it springs back.
    var liveDragOffsetPx by remember { mutableFloatStateOf(0f) }
    var dragActive by remember { mutableStateOf(false) }
    val renderedDragOffset by androidx.compose.animation.core.animateFloatAsState(
        targetValue = if (dragActive) liveDragOffsetPx else 0f,
        animationSpec = if (dragActive) {
            androidx.compose.animation.core.snap()
        } else {
            androidx.compose.animation.core.tween(180)
        },
        label = "minimizeDragOffset"
    )

    val resolvedState = rememberUpdatedState(resolved)
    val dislikeText = dislikeStats?.let {
        LikesDisplayMath.format(it.likes, it.dislikes, prefs.likesDisplay)
    }.orEmpty()
    val currentChannelExempt = resolved?.let {
        !HaramBlurExemptions.shouldBlurForChannel(exemptKeys, it.uploaderId, it.uploaderName)
    } ?: false

    val audioManager = remember {
        runCatching {
            context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        }.getOrNull()
    }

    // Auto-hide our controls after ~3s while playing (AUTO_HIDE only);
    // ALWAYS_VISIBLE pins them. Any tap brings them back in AUTO_HIDE.
    LaunchedEffect(controlsVisible, playerState.isPlaying, prefs.controlsVisibility) {
        if (PlayerControlsPolicy.shouldAutoHide(prefs.controlsVisibility, playerState.isPlaying) &&
            controlsVisible
        ) {
            delay(3000L)
            controlsVisible = false
        }
    }
    LaunchedEffect(prefs.controlsVisibility) {
        if (prefs.controlsVisibility == ControlsVisibility.ALWAYS_VISIBLE) controlsVisible = true
    }
    // v2.0 IMMERSIVE fullscreen: while the landscape fullscreen player is
    // active, hide the status bar AND nav bar (transient-by-swipe); any
    // exit — rotate back, navigate away, dispose — restores them.
    val view = LocalView.current
    LaunchedEffect(fullscreenLandscape) {
        val activity = context as? Activity ?: return@LaunchedEffect
        val insets = WindowInsetsControllerCompat(activity.window, view)
        if (fullscreenLandscape) {
            insets.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            insets.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            insets.show(WindowInsetsCompat.Type.systemBars())
        }
    }
    DisposableEffect(Unit) {
        onDispose {
            runCatching {
                val activity = context as? Activity
                if (activity != null) {
                    WindowInsetsControllerCompat(activity.window, view)
                        .show(WindowInsetsCompat.Type.systemBars())
                }
            }
        }
    }
    LaunchedEffect(swipeIndicator) {
        if (swipeIndicator != null) {
            delay(900L)
            swipeIndicator = null
        }
    }
    // Live position for the seek area + device volume for the Vol-0 hint.
    LaunchedEffect(controller) {
        val activeController = controller ?: return@LaunchedEffect
        while (true) {
            delay(500L)
            runCatching {
                positionMs = activeController.currentPosition.coerceAtLeast(0L)
                durationMsState = activeController.duration.coerceAtLeast(0L)
            }
            deviceVolume = runCatching {
                audioManager?.getStreamVolume(AudioManager.STREAM_MUSIC) ?: -1
            }.getOrDefault(-1)
        }
    }
    LaunchedEffect(Unit) { runCatching { app.haramBlurEngine.ensureInitialized() } }

    val sampler = remember {
        VideoFrameSampler(app.haramBlurEngine, scope, app.samplerDiagnostics)
    }
    // v2.1: the overlay collects the sampler's frame snapshots itself —
    // the screen only needs the zero-flash gate state now.
    val initialCleared by sampler.isInitialFrameCleared.collectAsState()
    val account by app.accountRepository.state.collectAsState()
    val volumeNotice by app.volumeZeroMonitor.notice.collectAsState()
    val density = LocalDensity.current
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
                        dislikeStats = app.dislikeRepository.fetchDislikeStats(stream.videoId)
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

    // In-playback fallback chain on player errors.
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

    // Start the frame sampler once the TextureView exists. Exempt
    // channels are skipped via the provider (reads the resolved stream).
    // v2.0: keyed on audioOnly too — toggling audio-only OFF recreates the
    // TextureView, and the sampler must restart with it (previously the
    // stop-on-audio-only had no matching restart path here).
    LaunchedEffect(textureViewRef, controller, playerState.audioOnly) {
        val view = textureViewRef ?: return@LaunchedEffect
        val activeController = controller ?: return@LaunchedEffect
        if (playerState.audioOnly) return@LaunchedEffect
        runCatching {
            sampler.startSampling(
                textureView = view,
                prefsProvider = { app.currentPrefs },
                isPlayingProvider = { runCatching { activeController.isPlaying }.getOrDefault(false) },
                isExemptProvider = {
                    val stream = resolvedState.value
                    stream != null && !HaramBlurExemptions.shouldBlurForChannel(
                        app.exemptChannelKeys.value, stream.uploaderId, stream.uploaderName
                    )
                }
            )
        }
    }

    // v2.0 related / up next: extractor related first; when signed in the
    // authenticated watch-next wins when it returns anything.
    LaunchedEffect(resolved, account.signedIn) {
        val stream = resolved ?: return@LaunchedEffect
        relatedExpanded = !prefs.collapseRelatedByDefault
        var items = stream.related
        if (account.signedIn) {
            app.innertubeClient.nextRelated(videoId)
                .onSuccess { parsed ->
                    if (parsed.isNotEmpty()) {
                        items = parsed.map { p ->
                            FeedItem(
                                videoId = p.videoId,
                                title = p.title,
                                channelName = p.channelName,
                                channelId = p.channelId,
                                thumbnailUrl = p.thumbnailUrl,
                                durationSeconds = p.durationSeconds,
                                url = "https://www.youtube.com/watch?v=${p.videoId}"
                            )
                        }
                    }
                }
        }
        if (items.isEmpty()) {
            items = app.streamRepository.fetchRelated(videoId).getOrDefault(emptyList())
        }
        related = items
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
            val diag = app.samplerDiagnostics.value
            appendLine(
                "Sampler: frames=${diag.framesSampled} brightness=${diag.lastFrameBrightness} " +
                    "detections=${diag.lastDetectionCount} error=${diag.lastError ?: "none"}"
            )
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

    suspend fun applyCommentFilters(list: List<CommentItem>): List<CommentItem> {
        val blocked = runCatching { app.database.blocklistDao().getAll() }
            .getOrDefault(emptyList())
        return CommentFilters.filter(
            comments = list,
            blockedKeywords = blocked.filter { it.filterType == FilterType.COMMENT_KEYWORD }.map { it.pattern },
            blockedUsernames = blocked.filter { it.filterType == FilterType.COMMENT_USERNAME }.map { it.pattern },
            blockedChannelIds = blocked.filter { it.filterType == FilterType.COMMENT_CHANNEL_ID }.map { it.pattern },
            minLikes = prefs.commentMinLikes,
            maxLikes = prefs.commentMaxLikes
        )
    }

    fun commentKey(comment: CommentItem): String =
        comment.id.ifBlank { "${comment.authorName}#${comment.text.hashCode()}" }

    // ------------------------------------------------------------------
    // Layout pieces (shared by the pinned and single-scroll layouts)
    // ------------------------------------------------------------------

    val videoBox: @Composable () -> Unit = {
        // v2.0: the displayed-video rect for the active aspect mode. The
        // TextureView is laid out at EXACTLY this rect, so the sampler
        // bitmap is in video-frame space and blur regions map through it.
        val displayedRect = if (
            videoContainerSize.width > 0 && videoContainerSize.height > 0
        ) {
            VideoRectMath.displayedVideoRect(
                containerW = videoContainerSize.width.toFloat(),
                containerH = videoContainerSize.height.toFloat(),
                videoW = playerState.videoWidth,
                videoH = playerState.videoHeight,
                mode = prefs.videoAspectMode
            )
        } else {
            null
        }
        Box(
            modifier = (if (fullscreenLandscape) {
                Modifier.fillMaxSize()
            } else {
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f)
            })
                .onSizeChanged { videoContainerSize = it }
                .clipToBounds()
                .background(Color.Black)
        ) {
            if (controller != null && playerState.audioOnly) {
                // Audio Only: the video picture is REMOVED — placeholder with
                // artwork + title + label. No TextureView exists in this
                // state, so no surface can show video.
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
                // TextureView surface (v1.4): the session player renders
                // into it via Player.setVideoTextureView; the sampler
                // reads frames back with getBitmap(). Cleared on release
                // so the floating window / dock surfaces can take over.
                // v2.0: sized/positioned to the displayed-video rect for
                // the active aspect mode (FIT letterbox / ZOOM crop /
                // STRETCH fill) instead of always filling the container —
                // the picture is no longer stretched by default.
                val textureModifier = if (displayedRect != null) {
                    Modifier
                        .offset {
                            androidx.compose.ui.unit.IntOffset(
                                displayedRect.left.roundToInt(),
                                displayedRect.top.roundToInt()
                            )
                        }
                        .size(
                            with(density) { displayedRect.width.toDp() },
                            with(density) { displayedRect.height.toDp() }
                        )
                } else {
                    Modifier.fillMaxSize()
                }
                AndroidView(
                    factory = { ctx ->
                        TextureView(ctx).also { tv ->
                            textureViewRef = tv
                            runCatching { controller?.setVideoTextureView(tv) }
                        }
                    },
                    update = { view ->
                        if (textureViewRef !== view) textureViewRef = view
                        runCatching { controller?.setVideoTextureView(view) }
                        // v1.5: keep the PiP sourceRectHint pointed at the video.
                        runCatching {
                            val rect = android.graphics.Rect()
                            if (view.getGlobalVisibleRect(rect)) {
                                app.pipSourceRectHint = rect
                            }
                        }
                    },
                    onRelease = { view ->
                        runCatching { controller?.clearVideoTextureView(view) }
                        if (textureViewRef === view) textureViewRef = null
                    },
                    modifier = textureModifier
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
            // Gesture layer: tap / double-tap / hold in one pointerInput,
            // vertical swipe controls in a second pointerInput on the SAME
            // box (Compose gesture composition — the drag detector only
            // consumes once a real vertical drag starts, taps still land).
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        val marginPx = 24.dp.toPx()
                        detectTapGestures(
                            onTap = { controlsVisible = !controlsVisible },
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
                    .pointerInput(prefs.swipeBrightnessVolumeControls) {
                        // v1.5 gesture policy: brightness/volume swipes are
                        // OPT-IN (default OFF). OFF: any vertical drag on
                        // the video is the minimize drag (video follows the
                        // finger; release past ~120 dp minimizes, otherwise
                        // it springs back). ON: LEFT third = brightness,
                        // RIGHT third = volume, CENTRE third = minimize drag.
                        val swipeOn = prefs.swipeBrightnessVolumeControls
                        var zone = 0 // -1 brightness, +1 volume, 0 none
                        var minimizeCandidate = false
                        var totalDrag = 0f
                        val minimizeThreshold = 120.dp.toPx()
                        detectVerticalDragGestures(
                            onDragStart = { offset ->
                                val fraction =
                                    if (size.width > 0) offset.x / size.width else 0.5f
                                zone = PlayerGesturePolicy.swipeZone(fraction, swipeOn)
                                minimizeCandidate =
                                    PlayerGesturePolicy.isMinimizeDragStart(fraction, swipeOn)
                                totalDrag = 0f
                                if (zone == -1 && brightnessFraction == null) {
                                    val attr = (context as? Activity)?.window?.attributes
                                    brightnessFraction =
                                        attr?.screenBrightness?.takeIf { it >= 0f } ?: 0.5f
                                }
                            },
                            onVerticalDrag = { change, dragAmount ->
                                when (zone) {
                                    -1 -> {
                                        val next = SwipeControlMath.applyDrag(
                                            brightnessFraction ?: 0.5f, dragAmount, size.height.toFloat()
                                        )
                                        brightnessFraction = next
                                        runCatching {
                                            val activity = context as? Activity
                                            val attrs = activity?.window?.attributes
                                            if (activity != null && attrs != null) {
                                                attrs.screenBrightness = next
                                                activity.window.attributes = attrs
                                            }
                                        }
                                        swipeIndicator = "Brightness ${(next * 100).toInt()}%"
                                    }
                                    1 -> {
                                        val am = audioManager
                                        if (am != null) {
                                            val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                                            val current = am.getStreamVolume(AudioManager.STREAM_MUSIC)
                                            val fraction =
                                                if (max > 0) current.toFloat() / max else 0f
                                            val nextFraction = SwipeControlMath.applyDrag(
                                                fraction, dragAmount, size.height.toFloat()
                                            )
                                            val nextVolume =
                                                SwipeControlMath.fractionToVolume(nextFraction, max)
                                            runCatching {
                                                am.setStreamVolume(AudioManager.STREAM_MUSIC, nextVolume, 0)
                                            }
                                            deviceVolume = nextVolume
                                            swipeIndicator = "Volume ${(nextFraction * 100).toInt()}%"
                                        }
                                    }
                                    else -> {
                                        if (minimizeCandidate) {
                                            change.consume()
                                            totalDrag += dragAmount
                                            dragActive = true
                                            liveDragOffsetPx = totalDrag.coerceAtLeast(0f)
                                        }
                                    }
                                }
                            },
                            onDragEnd = {
                                if (minimizeCandidate && totalDrag > minimizeThreshold) {
                                    onMinimize()
                                }
                                dragActive = false
                                liveDragOffsetPx = 0f
                                totalDrag = 0f
                                zone = 0
                                minimizeCandidate = false
                            },
                            onDragCancel = {
                                dragActive = false
                                liveDragOffsetPx = 0f
                                totalDrag = 0f
                                zone = 0
                                minimizeCandidate = false
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
                sampler = sampler,
                visualStyle = prefs.blurVisualStyle,
                blurStrength = prefs.blurStrength,
                initialFrameCleared = initialCleared,
                zeroFlashEnabled = prefs.zeroFlashSafePreBlur,
                videoRect = displayedRect
            )
            volumeNotice?.let { msg ->
                // v2.0 volume-zero rule notice: in-player text, not a popup.
                Text(
                    text = msg,
                    color = Color.White,
                    fontSize = 12.sp,
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 54.dp)
                        .background(Color(0xCC000000))
                        .padding(horizontal = 10.dp, vertical = 4.dp)
                )
            }
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
            swipeIndicator?.let { label ->
                Text(
                    text = label,
                    color = Color.White,
                    fontSize = 12.sp,
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 30.dp)
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
    }

    // v2.0: seek and chips are separate blocks so the fullscreen layout
    // can stack them in non-overlapping bands (chips ABOVE, seek slider
    // pinned to the very bottom edge); portrait keeps slider-then-chips.
    val seekBlock: @Composable () -> Unit = {
        if (controlsVisible) {
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
        }
    }

    val chipsBlock: @Composable () -> Unit = {
        if (controlsVisible) {
            FlowRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                StateChip(
                    label = if (!playerState.isMuted && deviceVolume == 0) {
                        "${AudioOnlySurface.soundLabel(false)} · Vol 0"
                    } else {
                        AudioOnlySurface.soundLabel(playerState.isMuted)
                    },
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
                Box(
                    modifier = Modifier
                        .background(Color(0xFF1E1E1E), RoundedCornerShape(16.dp))
                        .clickable { (context as? MainActivity)?.enterPipNow() }
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Filled.PictureInPictureAlt,
                            contentDescription = "Picture in picture",
                            tint = Color.White,
                            modifier = Modifier.height(14.dp)
                        )
                        Spacer(modifier = Modifier.width(5.dp))
                        Text("PiP", color = Color.White, fontSize = 12.sp)
                    }
                }
                StateChip(
                    label = "Speed ${playerState.playbackSpeed}x",
                    active = playerState.playbackSpeed != 1.0f,
                    activeColor = PureAccent,
                    onClick = { app.playerController.cycleSpeed() },
                    onLongClick = { showSpeedSlider = true }
                )
                StateChip(
                    label = if (prefs.autoRotatePlayer) "Rotate: Auto" else "Rotate: Locked",
                    active = prefs.autoRotatePlayer,
                    activeColor = PureAccent,
                    onClick = {
                        scope.launch {
                            app.preferences.update { it.copy(autoRotatePlayer = !prefs.autoRotatePlayer) }
                        }
                    }
                )
                StateChip(
                    label = "Aspect: " + prefs.videoAspectMode.name.lowercase()
                        .replaceFirstChar { it.uppercase() },
                    active = prefs.videoAspectMode != VideoAspectMode.FIT,
                    activeColor = PureAccent,
                    onClick = {
                        val values = VideoAspectMode.entries
                        val next = values[(prefs.videoAspectMode.ordinal + 1) % values.size]
                        scope.launch {
                            app.preferences.update { it.copy(videoAspectMode = next) }
                        }
                    }
                )
                StateChip(
                    label = "⌄ Minimize",
                    active = false,
                    activeColor = PureAccent,
                    onClick = { onMinimize() }
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
    }

    val controlsExtras: @Composable () -> Unit = {
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
    }

    /** Portrait order: seek, chips, extras. Fullscreen composes its own stack. */
    val controlsArea: @Composable () -> Unit = {
        seekBlock()
        chipsBlock()
        controlsExtras()
    }

    // ---- v2.0 account actions: like / dislike (optimistic + rollback) ----

    fun onLikeClick() {
        if (!account.signedIn) {
            actionStatus = "Sign in to like videos — opening Settings → Account"
            onOpenAccount()
            return
        }
        if (likeBusy) return
        val previous = likeState
        val next = if (previous == 1) 0 else 1
        likeState = next
        likeBusy = true
        scope.launch {
            val result = if (next == 1) {
                app.innertubeClient.like(videoId)
            } else {
                app.innertubeClient.removeLike(videoId)
            }
            likeBusy = false
            result
                .onSuccess {
                    actionStatus = if (next == 1) "Liked on YouTube" else "Like removed"
                }
                .onFailure { e ->
                    likeState = previous
                    actionStatus = e.message ?: "Like failed"
                }
        }
    }

    fun onDislikeClick() {
        if (!account.signedIn) {
            actionStatus = "Sign in to rate videos — opening Settings → Account"
            onOpenAccount()
            return
        }
        if (likeBusy) return
        val previous = likeState
        val next = if (previous == -1) 0 else -1
        likeState = next
        likeBusy = true
        scope.launch {
            val result = if (next == -1) {
                app.innertubeClient.dislike(videoId)
            } else {
                app.innertubeClient.removeLike(videoId)
            }
            likeBusy = false
            result
                .onSuccess {
                    actionStatus = if (next == -1) "Disliked on YouTube" else "Dislike removed"
                }
                .onFailure { e ->
                    likeState = previous
                    actionStatus = e.message ?: "Dislike failed"
                }
        }
    }

    val belowContent: @Composable () -> Unit = {
        Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)) {
            // Drag handle pill above the title: pull down ~120dp to minimize.
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 6.dp)
                    .pointerInput(Unit) {
                        var totalDrag = 0f
                        val threshold = 120.dp.toPx()
                        detectVerticalDragGestures(
                            onDragStart = { totalDrag = 0f },
                            onVerticalDrag = { change, dragAmount ->
                                change.consume()
                                totalDrag += dragAmount
                            },
                            onDragEnd = {
                                if (totalDrag > threshold) onMinimize()
                                totalDrag = 0f
                            },
                            onDragCancel = { totalDrag = 0f }
                        )
                    }
                    .padding(vertical = 8.dp),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .width(48.dp)
                        .height(5.dp)
                        .background(Color(0xFF555555), RoundedCornerShape(3.dp))
                )
            }
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
                    modifier = Modifier.padding(top = 4.dp)
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
                        // Formatted description: links open the browser,
                        // timestamps seek the player.
                        val annotated = remember(stream.description) {
                            buildAnnotatedString {
                                for (segment in DescriptionFormatter.parse(stream.description)) {
                                    when {
                                        segment.isTimestamp -> {
                                            pushStringAnnotation("TS", segment.timestampMs.toString())
                                            withStyle(
                                                SpanStyle(
                                                    color = PureAccent,
                                                    textDecoration = TextDecoration.Underline
                                                )
                                            ) { append(segment.text) }
                                            pop()
                                        }
                                        segment.isLink -> {
                                            pushStringAnnotation("URL", segment.url.orEmpty())
                                            withStyle(
                                                SpanStyle(
                                                    color = PureAccent,
                                                    textDecoration = TextDecoration.Underline
                                                )
                                            ) { append(segment.text) }
                                            pop()
                                        }
                                        else -> append(segment.text)
                                    }
                                }
                            }
                        }
                        ClickableText(
                            text = annotated,
                            style = TextStyle(color = Color.White, fontSize = 13.sp, lineHeight = 19.sp),
                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
                            onClick = { offset ->
                                annotated.getStringAnnotations("URL", offset, offset)
                                    .firstOrNull()?.let { ann ->
                                        runCatching {
                                            context.startActivity(
                                                Intent(Intent.ACTION_VIEW, Uri.parse(ann.item))
                                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                            )
                                        }
                                        return@ClickableText
                                    }
                                annotated.getStringAnnotations("TS", offset, offset)
                                    .firstOrNull()?.let { ann ->
                                        ann.item.toLongOrNull()?.let { ms ->
                                            runCatching { controller?.seekTo(ms) }
                                        }
                                    }
                            }
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
                            // v2.0: signed in -> the real YouTube subscribe
                            // happens first and wins; the local row follows
                            // it. Signed out -> local subscription only.
                            if (account.signedIn) {
                                val remote = if (isSubscribed) {
                                    app.innertubeClient.unsubscribe(stream.uploaderId)
                                } else {
                                    app.innertubeClient.subscribe(stream.uploaderId)
                                }
                                if (remote.isFailure) {
                                    actionStatus = remote.exceptionOrNull()?.message
                                        ?: "YouTube subscribe failed"
                                    return@launch
                                }
                            }
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
                // v2.0: real Like / Dislike when signed in (optimistic,
                // rolled back on failure); signed-out taps route to sign-in.
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.padding(top = 6.dp)
                ) {
                    val likeCount = dislikeStats?.likes?.takeIf { it >= 0 }
                        ?: stream.likeCount.takeIf { it >= 0 }
                    StateChip(
                        label = "👍 Like" + (likeCount?.let { " ($it)" } ?: ""),
                        active = likeState == 1,
                        activeColor = PureAccent,
                        onClick = { onLikeClick() }
                    )
                    val dislikeCount = dislikeStats?.dislikes?.takeIf { it >= 0 }
                    StateChip(
                        label = "👎 Dislike" + (dislikeCount?.let { " ($it)" } ?: ""),
                        active = likeState == -1,
                        activeColor = Color(0xFFB3261E),
                        onClick = { onDislikeClick() }
                    )
                    if (!account.signedIn) {
                        Text(
                            "Sign in to like",
                            color = PureTextSecondary, fontSize = 11.sp,
                            modifier = Modifier.padding(top = 8.dp)
                        )
                    }
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
                    TextButton(onClick = {
                        scope.launch {
                            runCatching {
                                val dao = app.database.blocklistDao()
                                if (currentChannelExempt) {
                                    // Remove the exemption rows (id + name).
                                    dao.getAll()
                                        .filter {
                                            it.filterType == FilterType.EXEMPT_CHANNEL &&
                                                (it.pattern == stream.uploaderId ||
                                                    it.pattern == stream.uploaderName)
                                        }
                                        .forEach { dao.delete(it.id) }
                                    actionStatus = "HaramBlur exemption removed for this channel"
                                } else {
                                    dao.add(
                                        com.puretube.app.data.local.entity.BlockedFilterEntity(
                                            pattern = stream.uploaderId,
                                            filterType = FilterType.EXEMPT_CHANNEL
                                        )
                                    )
                                    dao.add(
                                        com.puretube.app.data.local.entity.BlockedFilterEntity(
                                            pattern = stream.uploaderName,
                                            filterType = FilterType.EXEMPT_CHANNEL
                                        )
                                    )
                                    actionStatus = "HaramBlur will not blur this channel"
                                }
                            }
                        }
                    }) {
                        Text(
                            if (currentChannelExempt) "Don't blur: ON" else "Don't blur this channel",
                            fontSize = 12.sp
                        )
                    }
                }
                if (showPlaylistDialog) {
                    AddToPlaylistDialog(item = feedItem, onDismiss = { showPlaylistDialog = false })
                }
            }
            if (actionStatus.isNotBlank()) {
                Text(actionStatus, color = PureAccent, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
            }

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
                Spacer(modifier = Modifier.height(10.dp))
                TextButton(onClick = {
                    commentsExpanded = !commentsExpanded
                    if (commentsExpanded && comments.isEmpty()) {
                        scope.launch {
                            commentsStatus = "Loading comments…"
                            app.streamRepository.fetchComments(videoId)
                                .onSuccess { fetched ->
                                    comments = applyCommentFilters(fetched)
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
                    // v2.0: comment composer — real posting when signed in.
                    if (account.signedIn) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 6.dp)
                        ) {
                            OutlinedTextField(
                                value = commentDraft,
                                onValueChange = { commentDraft = it },
                                placeholder = { Text("Add a comment…", fontSize = 13.sp) },
                                singleLine = false,
                                modifier = Modifier.weight(1f)
                            )
                            Button(
                                enabled = commentDraft.isNotBlank() && !commentPosting,
                                onClick = {
                                    val text = commentDraft.trim()
                                    if (text.isBlank()) return@Button
                                    commentPosting = true
                                    scope.launch {
                                        app.innertubeClient.createComment(videoId, text)
                                            .onSuccess {
                                                comments = listOf(
                                                    CommentItem(
                                                        id = "",
                                                        authorName = account.name
                                                            .ifBlank { "You" },
                                                        authorChannelId = "",
                                                        text = text,
                                                        likeCount = 0
                                                    )
                                                ) + comments
                                                commentDraft = ""
                                                actionStatus = "Comment posted"
                                            }
                                            .onFailure { e ->
                                                actionStatus = e.message
                                                    ?: "Comment failed to post"
                                            }
                                        commentPosting = false
                                    }
                                }
                            ) { Text("Post", fontSize = 12.sp) }
                        }
                    } else {
                        TextButton(onClick = onOpenAccount) {
                            Text("Sign in to comment", fontSize = 12.sp)
                        }
                    }
                    if (commentsStatus.isNotBlank()) {
                        Text(commentsStatus, color = PureTextSecondary, fontSize = 13.sp)
                    }
                    comments.forEach { comment ->
                        val key = commentKey(comment)
                        Column(modifier = Modifier.padding(vertical = 6.dp)) {
                            Text(
                                "${comment.authorName} · ${if (comment.likeCount >= 0) "${comment.likeCount} likes" else ""}",
                                color = PureAccent, fontSize = 12.sp
                            )
                            Text(comment.text, color = Color.White, fontSize = 13.sp, lineHeight = 18.sp)
                            if (comment.hasReplies) {
                                val isOpen = key in repliesOpen
                                TextButton(onClick = {
                                    if (isOpen) {
                                        repliesOpen = repliesOpen - key
                                    } else {
                                        repliesOpen = repliesOpen + key
                                        if (key !in repliesById && key !in repliesStatusById) {
                                            scope.launch {
                                                app.streamRepository.fetchCommentReplies(comment)
                                                    .onSuccess { fetched ->
                                                        val filtered = applyCommentFilters(fetched)
                                                        if (filtered.isEmpty()) {
                                                            repliesStatusById = repliesStatusById +
                                                                (key to "Replies unavailable for this comment.")
                                                        } else {
                                                            repliesById = repliesById + (key to filtered)
                                                        }
                                                    }
                                                    .onFailure {
                                                        repliesStatusById = repliesStatusById +
                                                            (key to "Replies unavailable for this comment.")
                                                    }
                                            }
                                        }
                                    }
                                }) {
                                    Text(
                                        if (isOpen) "Hide replies" else "View replies (${comment.replyCount})",
                                        fontSize = 12.sp
                                    )
                                }
                                if (isOpen) {
                                    repliesStatusById[key]?.let { msg ->
                                        Text(msg, color = PureTextSecondary, fontSize = 12.sp)
                                    }
                                    repliesById[key]?.forEach { reply ->
                                        Column(
                                            modifier = Modifier
                                                .padding(start = 16.dp, top = 4.dp, bottom = 4.dp)
                                        ) {
                                            Text(
                                                "${reply.authorName} · ${if (reply.likeCount >= 0) "${reply.likeCount} likes" else ""}",
                                                color = PureAccent, fontSize = 11.sp
                                            )
                                            Text(
                                                reply.text, color = Color.White,
                                                fontSize = 12.sp, lineHeight = 17.sp
                                            )
                                        }
                                    }
                                    if (key !in repliesById && key !in repliesStatusById) {
                                        Text(
                                            "Loading replies…",
                                            color = PureTextSecondary, fontSize = 12.sp
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
            // v2.0: Up Next / Related — extractor related streams, or the
            // authenticated watch-next when signed in. Deduped against the
            // current video and everything already queued.
            val visibleRelated = RelatedDedupe.dedupe(
                related, videoId, queue.map { it.videoId }.toSet()
            )
            if (visibleRelated.isNotEmpty()) {
                Spacer(modifier = Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Up next / Related (${visibleRelated.size})",
                        color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f)
                    )
                    if (prefs.collapseRelatedByDefault) {
                        TextButton(onClick = { relatedExpanded = !relatedExpanded }) {
                            Text(if (relatedExpanded) "Hide" else "Show", fontSize = 12.sp)
                        }
                    }
                }
                if (relatedExpanded) {
                    visibleRelated.forEach { item ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            TextButton(onClick = { onVideoClick(item.videoId) }) {
                                Text(
                                    item.title, fontSize = 13.sp, maxLines = 1,
                                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                                )
                            }
                            Spacer(modifier = Modifier.weight(1f))
                            TextButton(onClick = {
                                app.playerController.enqueueWithNotice(item)
                                actionStatus = "Added to queue"
                            }) { Text("Queue", fontSize = 12.sp) }
                        }
                        if (item.channelName.isNotBlank()) {
                            Text(
                                item.channelName,
                                color = PureTextSecondary, fontSize = 11.sp,
                                modifier = Modifier.padding(start = 12.dp)
                            )
                        }
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // Layout branch: landscape fullscreen vs sticky (pinned) header vs
    // single-scroll page. The same pieces are used — no duplicated content.
    //
    // v2.0 drag: the minimize-drag offset is applied HERE, to the whole
    // page root, so video + title + chips + description/comments all
    // translate together as one rigid unit (previously only the video
    // box moved, sliding over/under the rest of the page).
    // ------------------------------------------------------------------
    val pageDragModifier = Modifier.offset {
        androidx.compose.ui.unit.IntOffset(0, renderedDragOffset.roundToInt())
    }
    if (fullscreenLandscape) {
        // v2.0 fullscreen stack, in separate bands so nothing overlaps:
        // title/author top-left, centre play (inside videoBox), chips row
        // above, seek slider pinned to the very bottom edge.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
                .then(pageDragModifier)
        ) {
            videoBox()
            if (controlsVisible) {
                Column(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(start = 16.dp, top = 12.dp, end = 16.dp)
                ) {
                    Text(
                        text = displayTitle.ifBlank { resolved?.title ?: "" },
                        color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold,
                        maxLines = 2,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                    )
                    resolved?.let { stream ->
                        Text(
                            text = stream.uploaderName,
                            color = PureAccent, fontSize = 12.sp
                        )
                    }
                }
                Column(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                ) {
                    chipsBlock()
                    controlsExtras()
                    seekBlock()
                }
            }
        }
    } else if (prefs.keepVideoPinnedWhileScrolling) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
                .then(pageDragModifier)
        ) {
            videoBox()
            controlsArea()
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
            ) {
                belowContent()
            }
        }
    } else {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
                .then(pageDragModifier)
                .verticalScroll(rememberScrollState())
        ) {
            videoBox()
            controlsArea()
            belowContent()
        }
    }
}
