package com.puretube.app

import android.app.Application
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import com.puretube.app.data.backup.SubscriptionImportExportManager
import com.puretube.app.data.local.AppDatabase
import com.puretube.app.data.local.LastCrashStore
import com.puretube.app.data.local.datastore.AppPreferences
import com.puretube.app.data.local.datastore.AppPreferencesState
import com.puretube.app.data.remote.dearrow.DeArrowRepository
import com.puretube.app.data.remote.extractor.OkHttpDownloaderImpl
import com.puretube.app.data.remote.extractor.StreamExtractorRepository
import com.puretube.app.data.remote.ryd.ReturnYouTubeDislikeRepo
import com.puretube.app.data.remote.sponsorblock.SponsorBlockRepository
import com.puretube.app.data.account.AccountRepository
import com.puretube.app.data.account.CookieStore
import com.puretube.app.data.account.YouTubeSyncManager
import com.puretube.app.data.local.entity.WatchProgressEntity
import com.puretube.app.data.remote.innertube.InnertubeClient
import com.puretube.app.haramblur.engine.HaramBlurEngine
import com.puretube.app.playback.PlaybackService
import com.puretube.app.playback.PlayerController
import com.puretube.app.playback.VolumeZeroMonitor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import org.schabi.newpipe.extractor.NewPipe
import java.util.concurrent.TimeUnit

class PureTubeApp : Application() {

    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    lateinit var database: AppDatabase
        private set
    lateinit var preferences: AppPreferences
        private set
    lateinit var okHttpClient: OkHttpClient
        private set
    lateinit var streamRepository: StreamExtractorRepository
        private set
    lateinit var sponsorBlockRepository: SponsorBlockRepository
        private set
    lateinit var deArrowRepository: DeArrowRepository
        private set
    lateinit var dislikeRepository: ReturnYouTubeDislikeRepo
        private set
    lateinit var importExportManager: SubscriptionImportExportManager
        private set
    lateinit var haramBlurEngine: HaramBlurEngine
        private set
    val playerController = PlayerController()

    // ---- v2.0: optional YouTube account (token/cookie sign-in) ----
    lateinit var cookieStore: CookieStore
        private set
    lateinit var innertubeClient: InnertubeClient
        private set
    lateinit var accountRepository: AccountRepository
        private set
    lateinit var syncManager: YouTubeSyncManager
        private set
    lateinit var volumeZeroMonitor: VolumeZeroMonitor
        private set

    /** Search state that survives navigation (v1.2). */
    val searchSession = com.puretube.app.ui.screens.search.SearchSession()

    /** Live-video sampler diagnostics (v1.4), read by Settings. */
    val samplerDiagnostics = kotlinx.coroutines.flow.MutableStateFlow(
        com.puretube.app.haramblur.video.SamplerDiagnostics()
    )

    /**
     * HaramBlur-exempt channel keys (v1.4): EXEMPT_CHANNEL blocklist rows,
     * pattern = channelId (channel name also kept for the name fallback).
     * Read in app scope from the blocklist DAO; crash-safe (any failure
     * leaves the previous set in place).
     */
    val exemptChannelKeys = kotlinx.coroutines.flow.MutableStateFlow<Set<String>>(emptySet())

    /** Latest preferences snapshot for synchronous readers (image pipeline, sampler). */
    @Volatile
    var currentPrefs: AppPreferencesState = AppPreferencesState()
        private set

    /**
     * Window bounds of the currently visible video view (player
     * TextureView or floating window), updated on layout by whichever
     * surface is on screen (v1.5). MainActivity passes it as the PiP
     * sourceRectHint so PiP animates out of the actual video.
     */
    @Volatile
    var pipSourceRectHint: android.graphics.Rect? = null

    override fun onCreate() {
        super.onCreate()
        // Record uncaught exceptions for Settings > Diagnostics, then delegate.
        LastCrashStore.install(this)
        database = AppDatabase.get(this)
        preferences = AppPreferences(this)
        okHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()

        runCatching { NewPipe.init(OkHttpDownloaderImpl(okHttpClient)) }

        streamRepository = StreamExtractorRepository(okHttpClient) {
            currentPrefs.fallbackPipedApiUrl
        }
        sponsorBlockRepository = SponsorBlockRepository(okHttpClient)
        deArrowRepository = DeArrowRepository(okHttpClient)
        dislikeRepository = ReturnYouTubeDislikeRepo(okHttpClient)
        importExportManager = SubscriptionImportExportManager()
        haramBlurEngine = HaramBlurEngine(this)

        // Account stack: the Innertube client reads the cookie header
        // straight from encrypted storage (present only while signed in).
        cookieStore = CookieStore(this)
        innertubeClient = InnertubeClient(okHttpClient) { cookieStore.load()?.cookieHeader }
        accountRepository = AccountRepository(cookieStore, innertubeClient)
        syncManager = YouTubeSyncManager(database, innertubeClient)

        // Volume-zero rule monitor (pause at zero, stop if it stays zero).
        volumeZeroMonitor = VolumeZeroMonitor(
            context = this,
            scope = appScope,
            prefsProvider = { currentPrefs },
            callbacks = object : VolumeZeroMonitor.Callbacks {
                override fun isPlaying(): Boolean = playerController.isPlaying()
                override fun hasMedia(): Boolean =
                    PlaybackService.instance?.player?.currentMediaItem != null
                override fun currentMediaId(): String? =
                    playerController.state.value.currentVideoId.takeIf { it.isNotBlank() }
                override fun pause() = playerController.pause()
                override fun seekBack(ms: Long) = playerController.seekBy(-ms)
                override fun resume() = playerController.resumePlayback()
                override fun stopPlayback() {
                    // Persist the position first, then stop (Bara's rule).
                    val servicePlayer = PlaybackService.instance?.player
                    val videoId = playerController.state.value.currentVideoId
                    val pos = runCatching { servicePlayer?.currentPosition ?: 0L }
                        .getOrDefault(0L)
                    val dur = runCatching {
                        (servicePlayer?.duration ?: 0L).coerceAtLeast(0L)
                    }.getOrDefault(0L)
                    if (videoId.isNotBlank() && currentPrefs.watchHistoryEnabled && dur > 0) {
                        appScope.launch {
                            runCatching {
                                val stream = playerController.sessionStream
                                val existing = database.watchProgressDao().get(videoId)
                                database.watchProgressDao().upsert(
                                    WatchProgressEntity(
                                        videoId = videoId,
                                        title = stream?.title ?: existing?.title ?: "",
                                        channelName = stream?.uploaderName
                                            ?: existing?.channelName ?: "",
                                        channelId = stream?.uploaderId
                                            ?: existing?.channelId ?: "",
                                        thumbnailUrl = stream?.thumbnailUrl
                                            ?: existing?.thumbnailUrl ?: "",
                                        positionMs = if (currentPrefs.resumePlaybackEnabled) {
                                            pos
                                        } else {
                                            0L
                                        },
                                        durationMs = dur,
                                        isCompleted = WatchProgressEntity
                                            .computeCompleted(pos, dur),
                                        lastUpdatedEpochMs = System.currentTimeMillis()
                                    )
                                )
                            }
                        }
                    }
                    playerController.stopPlayback()
                }
            }
        )
        volumeZeroMonitor.start()

        SingletonImageLoader.setSafe { context ->
            ImageLoader.Builder(context)
                .components { add(OkHttpNetworkFetcherFactory(callFactory = { okHttpClient })) }
                .build()
        }

        appScope.launch {
            currentPrefs = preferences.state.first()
            preferences.state.collect { currentPrefs = it }
        }
        appScope.launch {
            runCatching {
                database.blocklistDao().observeAll().collect { rows ->
                    exemptChannelKeys.value = rows
                        .filter { it.filterType == com.puretube.app.data.local.entity.FilterType.EXEMPT_CHANNEL }
                        .map { it.pattern }
                        .filter { it.isNotBlank() }
                        .toSet()
                }
            }
        }
    }
}
