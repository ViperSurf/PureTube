package com.puretube.app

import android.app.Activity
import android.app.PendingIntent
import android.app.PictureInPictureParams
import android.app.RemoteAction
import android.content.ComponentName
import android.content.Intent
import android.graphics.Rect
import android.graphics.drawable.Icon
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.util.Rational
import android.view.KeyEvent
import android.view.TextureView
import android.content.Context
import androidx.fragment.app.FragmentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import com.puretube.app.data.local.datastore.DefaultStartTab
import com.puretube.app.data.remote.extractor.StreamExtractorRepository
import com.puretube.app.haramblur.system.SystemBlurOverlayService
import com.puretube.app.playback.PlaybackService
import com.puretube.app.playback.PipActionReceiver
import com.puretube.app.playback.PocketVolumeSkipDetector
import com.puretube.app.ui.components.FloatingMiniWindow
import com.puretube.app.ui.components.MiniPlayerDock
import com.puretube.app.ui.screens.channel.ChannelScreen
import com.puretube.app.ui.screens.library.LibraryScreen
import com.puretube.app.ui.screens.playlist.LocalPlaylistScreen
import com.puretube.app.ui.screens.playlist.RemotePlaylistScreen
import com.puretube.app.ui.screens.player.VideoPlayerScreen
import com.puretube.app.ui.screens.queue.QueueScreen
import com.puretube.app.ui.screens.search.SearchScreen
import com.puretube.app.ui.screens.settings.FlatSettingsScreen
import com.puretube.app.ui.screens.subscriptions.SubscriptionsScreen
import com.puretube.app.ui.theme.PureOledTheme

/**
 * Native PiP params with Play/Pause, Mute, and +10s RemoteActions.
 * Auto-enter is enabled on Android 12+ when requested and playback is running.
 *
 * v1.5: the Mute action uses two CUSTOM drawables with unmistakably
 * different silhouettes (speaker + waves vs speaker + X + slash) — the
 * old ic_lock_silent_mode pair looked identical at PiP size. The params
 * are also re-applied live via setPictureInPictureParams whenever play
 * or mute state changes while already in PiP, so the icon flips in
 * place. [sourceRect] (the on-screen video view's window bounds) is set
 * as the sourceRectHint so the PiP window animates out of the video.
 */
fun FragmentActivity.buildAndEnterPipParams(
    isPlaying: Boolean,
    isMuted: Boolean,
    autoEnterEnabled: Boolean,
    sourceRect: Rect? = null
): PictureInPictureParams {
    val muteIconRes = when (
        com.puretube.app.domain.PipActionIcons.muteIconFor(isMuted)
    ) {
        com.puretube.app.domain.PipActionIcons.MuteIcon.MUTED -> R.drawable.ic_pip_volume_off
        com.puretube.app.domain.PipActionIcons.MuteIcon.UNMUTED -> R.drawable.ic_pip_volume_on
    }
    val actions = listOf(
        RemoteAction(
            Icon.createWithResource(
                this,
                if (isPlaying) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play
            ),
            if (isPlaying) "Pause" else "Play",
            "Toggle Playback",
            PendingIntent.getBroadcast(
                this,
                101,
                Intent(this, PipActionReceiver::class.java)
                    .setAction(PipActionReceiver.ACTION_TOGGLE_PLAY),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
        ),
        RemoteAction(
            Icon.createWithResource(this, muteIconRes),
            if (isMuted) "Unmute" else "Mute",
            "Toggle Mute",
            PendingIntent.getBroadcast(
                this,
                102,
                Intent(this, PipActionReceiver::class.java)
                    .setAction(PipActionReceiver.ACTION_TOGGLE_MUTE),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
        ),
        RemoteAction(
            Icon.createWithResource(this, android.R.drawable.ic_media_ff),
            "+10s",
            "Skip Forward 10 Seconds",
            PendingIntent.getBroadcast(
                this,
                103,
                Intent(this, PipActionReceiver::class.java)
                    .setAction(PipActionReceiver.ACTION_SEEK_FORWARD),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
        )
    )
    val builder = PictureInPictureParams.Builder()
        .setAspectRatio(Rational(16, 9))
        .setActions(actions)
    if (sourceRect != null && !sourceRect.isEmpty) {
        runCatching { builder.setSourceRectHint(sourceRect) }
    }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        builder.setAutoEnterEnabled(autoEnterEnabled && isPlaying)
        builder.setSeamlessResizeEnabled(true)
    }
    return builder.build()
}

/**
 * The PiP-only video surface (v1.5): while the activity is in
 * picture-in-picture it renders ONLY this — a TextureView bound to the
 * same session player — filling the PiP window, whatever nav route is
 * underneath. That fixes PiP entered from the minimized state showing
 * the Home/Search tab instead of the video. The surface is re-bound,
 * not a second player: the player's own TextureView clears on release
 * and this one takes over (and vice versa when PiP exits).
 */
@Composable
private fun PipVideoSurface(controller: MediaController?) {
    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        if (controller != null) {
            AndroidView(
                factory = { ctx ->
                    TextureView(ctx).also { tv ->
                        runCatching { controller.setVideoTextureView(tv) }
                    }
                },
                update = { view ->
                    runCatching { controller.setVideoTextureView(view) }
                },
                onRelease = { view ->
                    runCatching { controller.clearVideoTextureView(view) }
                },
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}

class MainActivity : FragmentActivity() {

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.wrap(newBase))
    }

    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var mediaController by mutableStateOf<MediaController?>(null)
    private var inPictureInPicture by mutableStateOf(false)

    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        newConfig: android.content.res.Configuration
    ) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        inPictureInPicture = isInPictureInPictureMode
    }
    private var pendingVideoId by mutableStateOf<String?>(null)

    private val pocketSkipDetector by lazy {
        PocketVolumeSkipDetector { deltaMs ->
            val player = PlaybackService.instance?.player ?: return@PocketVolumeSkipDetector
            val target = (player.currentPosition + deltaMs)
                .coerceIn(0L, maxOf(player.duration, 0L))
            player.seekTo(target)
        }
    }

    private val mediaProjectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val data = result.data
        if (result.resultCode == Activity.RESULT_OK && data != null) {
            // Token handshake complete: only NOW may the mediaProjection
            // foreground service start (Android 14+ requirement).
            ContextCompat.startForegroundService(
                this,
                SystemBlurOverlayService.startIntent(this, result.resultCode, data)
            )
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as PureTubeApp

        val sessionToken = SessionToken(this, ComponentName(this, PlaybackService::class.java))
        val future = MediaController.Builder(this, sessionToken).buildAsync()
        controllerFuture = future
        future.addListener(
            {
                val controller = runCatching { future.get() }.getOrNull()
                mediaController = controller
                if (controller != null) {
                    app.playerController.attach(controller)
                    // v1.4: unmute-raises-volume wiring.
                    app.playerController.audioManager =
                        getSystemService(android.media.AudioManager::class.java)
                    app.playerController.unmuteRaisesVolumeProvider =
                        { app.currentPrefs.unmuteRaisesVolume }
                    app.playerController.setSpeed(app.currentPrefs.defaultPlaybackSpeed)
                    app.playerController.setMono(app.currentPrefs.monoAudioMergeEnabled)
                    app.playerController.setNormalization(app.currentPrefs.audioNormalizationEnabled)
                    if (app.currentPrefs.audioOnlyMode) {
                        app.playerController.setAudioOnly(true)
                    }
                }
            },
            MoreExecutors.directExecutor()
        )

        handleIntent(intent)

        setContent {
            PureOledTheme {
                PureTubeNav(
                    app = app,
                    controller = mediaController,
                    pendingVideoId = pendingVideoId,
                    onPendingVideoConsumed = { pendingVideoId = null },
                    inPictureInPicture = inPictureInPicture
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent == null) return
        when (intent.action) {
            ACTION_TOGGLE_SYSTEM_SHIELD -> requestSystemShieldConsent()
            Intent.ACTION_VIEW -> {
                val data = intent.data?.toString() ?: return
                pendingVideoId = StreamExtractorRepository.extractVideoId(data)
            }
        }
    }

    private fun requestSystemShieldConsent() {
        val manager = getSystemService(MediaProjectionManager::class.java) ?: return
        mediaProjectionLauncher.launch(manager.createScreenCaptureIntent())
    }

    fun enterPipNow() {
        val app = application as PureTubeApp
        val controller = mediaController ?: return
        val params = buildAndEnterPipParams(
            isPlaying = controller.isPlaying,
            isMuted = app.playerController.state.value.isMuted,
            autoEnterEnabled = app.currentPrefs.autoEnterPipOnHome,
            sourceRect = app.pipSourceRectHint
        )
        runCatching { enterPictureInPictureMode(params) }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        val app = application as PureTubeApp
        val controller = mediaController ?: return
        // Auto-enter only when something is actually playing.
        if (app.currentPrefs.autoEnterPipOnHome && controller.isPlaying) {
            val params = buildAndEnterPipParams(
                isPlaying = true,
                isMuted = app.playerController.state.value.isMuted,
                autoEnterEnabled = true,
                sourceRect = app.pipSourceRectHint
            )
            runCatching { enterPictureInPictureMode(params) }
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val app = application as PureTubeApp
        pocketSkipDetector.isEnabled = app.currentPrefs.pocketVolumeLongPressSkip
        if (event.keyCode == KeyEvent.KEYCODE_VOLUME_UP ||
            event.keyCode == KeyEvent.KEYCODE_VOLUME_DOWN
        ) {
            val consumed = when (event.action) {
                KeyEvent.ACTION_DOWN -> pocketSkipDetector.onKeyDown(event.keyCode)
                KeyEvent.ACTION_UP -> pocketSkipDetector.onKeyUp(event.keyCode)
                else -> false
            }
            if (consumed) return true
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onDestroy() {
        // Queue lives in PlayerController; clear it on exit when the
        // resetQueueOnExit preference is on (this is the documented place:
        // activity teardown, mirrored by the service releasing its player).
        val app = application as? PureTubeApp
        if (app != null && app.currentPrefs.resetQueueOnExit) {
            app.playerController.clearQueue()
        }
        controllerFuture?.let { MediaController.releaseFuture(it) }
        controllerFuture = null
        super.onDestroy()
    }

    companion object {
        const val ACTION_TOGGLE_SYSTEM_SHIELD = "com.puretube.app.TOGGLE_SYSTEM_SHIELD"
    }
}

@Composable
private fun PureTubeNav(
    app: PureTubeApp,
    controller: MediaController?,
    pendingVideoId: String?,
    onPendingVideoConsumed: () -> Unit,
    inPictureInPicture: Boolean = false
) {
    val navController = rememberNavController()
    val startRoute = remember {
        when (app.currentPrefs.defaultStartTab) {
            DefaultStartTab.SUBSCRIPTIONS -> "subscriptions"
            DefaultStartTab.SEARCH -> "search"
            DefaultStartTab.LIBRARY -> "library"
        }
    }
    val backStack by navController.currentBackStackEntryAsState()
    val currentRoute = backStack?.destination?.route
    val playerState by app.playerController.state.collectAsState()
    val queueItems by app.playerController.queue.collectAsState()
    val notice by app.playerController.notice.collectAsState()
    val prefs by app.preferences.state.collectAsState(initial = app.currentPrefs)
    var floatingOpen by remember { mutableStateOf(false) }
    var dockProgress by remember { mutableFloatStateOf(0f) }
    var dockPositionMs by remember { mutableStateOf(0L) }
    var dockDurationMs by remember { mutableStateOf(0L) }

    // v1.4 rotation: on the player route, Auto allows full-sensor
    // rotation and Locked pins portrait; leaving the player restores
    // UNSPECIFIED so the rest of the app behaves normally.
    val activity = androidx.compose.ui.platform.LocalContext.current as? android.app.Activity
    LaunchedEffect(currentRoute, prefs.autoRotatePlayer) {
        val act = activity ?: return@LaunchedEffect
        act.requestedOrientation = when {
            currentRoute?.startsWith("player") == true && prefs.autoRotatePlayer ->
                android.content.pm.ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR
            currentRoute?.startsWith("player") == true ->
                android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            else -> android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }

    LaunchedEffect(controller, currentRoute) {
        while (true) {
            if (controller != null && currentRoute?.startsWith("player") != true) {
                val duration = runCatching { controller.duration }.getOrDefault(0L)
                val position = runCatching { controller.currentPosition }.getOrDefault(0L)
                dockPositionMs = position.coerceAtLeast(0L)
                dockDurationMs = duration.coerceAtLeast(0L)
                dockProgress = if (duration > 0) {
                    (position.toFloat() / duration.toFloat()).coerceIn(0f, 1f)
                } else 0f
            }
            kotlinx.coroutines.delay(700)
        }
    }

    LaunchedEffect(pendingVideoId) {
        val videoId = pendingVideoId ?: return@LaunchedEffect
        navController.navigate("player/$videoId") {
            launchSingleTop = true
        }
        onPendingVideoConsumed()
    }

    // v1.5: live PiP action refresh — while in PiP, any play/pause or
    // mute change re-applies the params so the remote-action icons
    // (especially the custom mute/unmute pair) flip in place instead of
    // staying frozen at whatever they were on entry.
    LaunchedEffect(inPictureInPicture, playerState.isPlaying, playerState.isMuted) {
        if (inPictureInPicture) {
            val act = activity as? FragmentActivity ?: return@LaunchedEffect
            runCatching {
                act.setPictureInPictureParams(
                    act.buildAndEnterPipParams(
                        isPlaying = playerState.isPlaying,
                        isMuted = playerState.isMuted,
                        autoEnterEnabled = prefs.autoEnterPipOnHome,
                        sourceRect = app.pipSourceRectHint
                    )
                )
            }
        }
    }

    // v1.5 PiP-only layout: in PiP the activity renders ONLY the video
    // surface, whatever the nav route underneath (fixes PiP entered from
    // the minimized/dock state showing the Home tab). The nav state is
    // untouched, so exiting PiP restores the previous route/UI exactly.
    if (inPictureInPicture) {
        PipVideoSurface(controller = controller)
        return
    }

    // v1.5: landscape on the player route = fullscreen player (the
    // bottom nav / dock / floating window hide, video fills the screen).
    val configuration = LocalConfiguration.current
    val isLandscape =
        configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
    val playerFullscreen = isLandscape && currentRoute?.startsWith("player") == true

    Scaffold(
        containerColor = Color.Black,
        bottomBar = { if (!playerFullscreen) {
            Column(modifier = Modifier.background(Color.Black)) {
                if (currentRoute?.startsWith("player") != true && playerState.title.isNotBlank()) {
                    Box(modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
                        MiniPlayerDock(
                            state = playerState,
                            onTogglePlayPause = { app.playerController.togglePlayPause() },
                            onToggleMute = { app.playerController.toggleMute() },
                            queueCount = queueItems.size,
                            progressFraction = dockProgress,
                            positionMs = dockPositionMs,
                            durationMs = dockDurationMs,
                            onSeek = { target ->
                                runCatching { controller?.seekTo(target) }
                            },
                            onQueueClick = { navController.navigate("queue") },
                            floatingVisible = floatingOpen,
                            onToggleFloating = { floatingOpen = !floatingOpen },
                            onOpenPlayer = {
                                val videoId = controller?.currentMediaItem?.mediaId
                                if (!videoId.isNullOrBlank()) {
                                    navController.navigate("player/$videoId") { launchSingleTop = true }
                                }
                            },
                            onClose = {
                                floatingOpen = false
                                controller?.stop()
                                controller?.clearMediaItems()
                            }
                        )
                    }
                }
                NavigationBar(containerColor = Color.Black) {
                    NavigationBarItem(
                        selected = currentRoute == "subscriptions",
                        onClick = {
                            navController.navigate("subscriptions") {
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = { Icon(Icons.Filled.Home, contentDescription = "Subscriptions") },
                        label = { Text("Home") }
                    )
                    NavigationBarItem(
                        selected = currentRoute == "search",
                        onClick = {
                            // v1.4: tapping Search while ALREADY on Search
                            // resets the session (pref-gated) instead of
                            // navigating nowhere.
                            if (com.puretube.app.domain.SearchResetPolicy.shouldReset(
                                    currentRoute, prefs.doubleTapSearchResets
                                )
                            ) {
                                app.searchSession.reset()
                            } else {
                                navController.navigate("search") {
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            }
                        },
                        icon = { Icon(Icons.Filled.Search, contentDescription = "Search") },
                        label = { Text("Search") }
                    )
                    NavigationBarItem(
                        selected = currentRoute == "library",
                        onClick = {
                            navController.navigate("library") {
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = { Icon(Icons.Filled.VideoLibrary, contentDescription = "Library") },
                        label = { Text("Library") }
                    )
                    NavigationBarItem(
                        selected = currentRoute == "settings",
                        onClick = {
                            navController.navigate("settings") {
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = { Icon(Icons.Filled.Settings, contentDescription = "Settings") },
                        label = { Text("Settings") }
                    )
                }
            }
        } }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
                .padding(padding)
        ) {
            NavHost(navController = navController, startDestination = startRoute) {
                composable("subscriptions") {
                    SubscriptionsScreen(
                        onVideoClick = { videoId -> navController.navigate("player/$videoId") },
                        onChannelClick = { channelId ->
                            navController.navigate("channel/${android.net.Uri.encode(channelId)}")
                        }
                    )
                }
                composable("search") {
                    SearchScreen(
                        onVideoClick = { videoId -> navController.navigate("player/$videoId") },
                        onChannelClick = { channelId ->
                            navController.navigate("channel/${android.net.Uri.encode(channelId)}")
                        },
                        onPlaylistClick = { url ->
                            navController.navigate("remotePlaylist/${android.net.Uri.encode(url)}")
                        }
                    )
                }
                composable("library") {
                    LibraryScreen(
                        onVideoClick = { videoId -> navController.navigate("player/$videoId") },
                        onChannelClick = { channelId ->
                            navController.navigate("channel/${android.net.Uri.encode(channelId)}")
                        },
                        onLocalPlaylistClick = { playlistId ->
                            navController.navigate("playlist/$playlistId")
                        },
                        onRemotePlaylistClick = { url ->
                            navController.navigate("remotePlaylist/${android.net.Uri.encode(url)}")
                        },
                        onQueueClick = { navController.navigate("queue") }
                    )
                }
                composable("queue") {
                    QueueScreen(
                        onVideoClick = { videoId -> navController.navigate("player/$videoId") },
                        onBack = { navController.popBackStack() }
                    )
                }
                composable(
                    route = "channel/{channelId}",
                    arguments = listOf(navArgument("channelId") { type = NavType.StringType })
                ) { entry ->
                    ChannelScreen(
                        channelIdOrUrl = entry.arguments?.getString("channelId").orEmpty(),
                        onVideoClick = { videoId -> navController.navigate("player/$videoId") },
                        onBack = { navController.popBackStack() }
                    )
                }
                composable(
                    route = "playlist/{playlistId}",
                    arguments = listOf(navArgument("playlistId") { type = NavType.LongType })
                ) { entry ->
                    LocalPlaylistScreen(
                        playlistId = entry.arguments?.getLong("playlistId") ?: 0L,
                        onVideoClick = { videoId -> navController.navigate("player/$videoId") },
                        onChannelClick = { channelId ->
                            navController.navigate("channel/${android.net.Uri.encode(channelId)}")
                        },
                        onBack = { navController.popBackStack() }
                    )
                }
                composable(
                    route = "remotePlaylist/{url}",
                    arguments = listOf(navArgument("url") { type = NavType.StringType })
                ) { entry ->
                    RemotePlaylistScreen(
                        playlistUrl = entry.arguments?.getString("url").orEmpty(),
                        onVideoClick = { videoId -> navController.navigate("player/$videoId") },
                        onChannelClick = { channelId ->
                            navController.navigate("channel/${android.net.Uri.encode(channelId)}")
                        },
                        onBack = { navController.popBackStack() }
                    )
                }
                composable("settings") {
                    FlatSettingsScreen()
                }
                composable(
                    route = "player/{videoId}",
                    arguments = listOf(navArgument("videoId") { type = NavType.StringType })
                ) { entry ->
                    val videoId = entry.arguments?.getString("videoId").orEmpty()
                    VideoPlayerScreen(
                        videoId = videoId,
                        controller = controller,
                        fullscreenLandscape = playerFullscreen,
                        onBack = { navController.popBackStack() },
                        onMinimize = {
                            floatingOpen = true
                            navController.popBackStack()
                        },
                        onQueueClick = { navController.navigate("queue") },
                        onVideoClick = { nextId -> navController.navigate("player/$nextId") },
                        onChannelClick = { channelId ->
                            navController.navigate("channel/${android.net.Uri.encode(channelId)}")
                        }
                    )
                }
            }
            if (!inPictureInPicture) notice?.let { text ->
                Box(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 10.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(0xFF1E1E1E))
                        .padding(horizontal = 14.dp, vertical = 8.dp)
                ) {
                    Text(text, color = Color.White, fontSize = 13.sp)
                }
            }
            if (!inPictureInPicture && !playerFullscreen && floatingOpen && currentRoute?.startsWith("player") != true) {
                FloatingMiniWindow(
                    controller = controller,
                    state = playerState,
                    onExpand = {
                        floatingOpen = false
                        val videoId = controller?.currentMediaItem?.mediaId
                        if (!videoId.isNullOrBlank()) {
                            navController.navigate("player/$videoId") { launchSingleTop = true }
                        }
                    }
                )
            }
            if (prefs.nightOledDimmerAlpha > 0f) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = prefs.nightOledDimmerAlpha))
                )
            }
        }
    }
}
