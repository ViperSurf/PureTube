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
import com.puretube.app.haramblur.engine.HaramBlurEngine
import com.puretube.app.playback.PlayerController
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

    /** Search state that survives navigation (v1.2). */
    val searchSession = com.puretube.app.ui.screens.search.SearchSession()

    /** Latest preferences snapshot for synchronous readers (image pipeline, sampler). */
    @Volatile
    var currentPrefs: AppPreferencesState = AppPreferencesState()
        private set

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

        SingletonImageLoader.setSafe { context ->
            ImageLoader.Builder(context)
                .components { add(OkHttpNetworkFetcherFactory(callFactory = { okHttpClient })) }
                .build()
        }

        appScope.launch {
            currentPrefs = preferences.state.first()
            preferences.state.collect { currentPrefs = it }
        }
    }
}
