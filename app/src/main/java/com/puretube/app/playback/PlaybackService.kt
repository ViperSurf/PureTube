package com.puretube.app.playback

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.puretube.app.MainActivity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class PlaybackService : MediaSessionService() {

    private var mediaSession: MediaSession? = null
    lateinit var player: ExoPlayer
        private set
    lateinit var trackSelector: DefaultTrackSelector
        private set

    val monoProcessor = MonoDownmixAudioProcessor()
    val loudnessController = LoudnessNormalizationController()

    override fun onCreate() {
        super.onCreate()
        trackSelector = DefaultTrackSelector(this)

        val renderersFactory = object : DefaultRenderersFactory(this) {
            override fun buildAudioSink(
                context: Context,
                enableFloatOutput: Boolean,
                enableAudioTrackPlaybackParams: Boolean
            ): AudioSink {
                return DefaultAudioSink.Builder(context)
                    .setAudioProcessors(arrayOf(monoProcessor))
                    .setEnableAudioTrackPlaybackParams(true)
                    .build()
            }
        }

        player = ExoPlayer.Builder(this, renderersFactory)
            .setTrackSelector(trackSelector)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                    .build(),
                mirrorRequireAudioFocus()
            )
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()

        player.addListener(object : Player.Listener {
            override fun onAudioSessionIdChanged(audioSessionId: Int) {
                loudnessController.onAudioSessionIdChanged(audioSessionId)
            }

            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                // Record for diagnostics; the UI layer owns the fallback chain.
                _lastPlayerError.value =
                    "${error.errorCodeName}: ${error.message ?: "playback error"}"
            }
        })

        val sessionActivityPendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        mediaSession = MediaSession.Builder(this, player)
            .setSessionActivity(sessionActivityPendingIntent)
            .build()

        instance = this
    }

    /**
     * 1-tap Audio-Only: disables the video renderer and detaches the surface.
     * The MediaItem is never replaced, so audio continues with 0ms hitch.
     */
    fun setAudioOnlyMode(enabled: Boolean) {
        trackSelector.parameters = trackSelector.buildUponParameters()
            .setRendererDisabled(C.TRACK_TYPE_VIDEO, enabled)
            .build()
        if (enabled) {
            player.clearVideoSurface()
        }
    }

    fun setAudioNormalization(enabled: Boolean) {
        loudnessController.isEnabled = enabled
    }

    fun setMonoEnabled(enabled: Boolean) {
        monoProcessor.isMonoEnabled = enabled
    }

    /** Re-applies the audio-focus preference to the live player. */
    fun setRequireAudioFocus(enabled: Boolean) {
        runCatching {
            player.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                    .build(),
                enabled
            )
        }
    }

    private fun mirrorPrefs() =
        getSharedPreferences(MIRROR_PREFS, MODE_PRIVATE)

    private fun mirrorRequireAudioFocus(): Boolean =
        runCatching { mirrorPrefs().getBoolean(KEY_REQUIRE_AUDIO_FOCUS, true) }.getOrDefault(true)

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? {
        // Android Auto kill switch: refuse the car projection controller.
        val disableAuto = runCatching {
            mirrorPrefs().getBoolean(KEY_DISABLE_ANDROID_AUTO, true)
        }.getOrDefault(true)
        if (disableAuto && controllerInfo.packageName == ANDROID_AUTO_PACKAGE) {
            return null
        }
        return mediaSession
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        if (!player.playWhenReady || player.mediaItemCount == 0) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        loudnessController.release()
        mediaSession?.run {
            player.release()
            release()
            mediaSession = null
        }
        if (instance === this) instance = null
        super.onDestroy()
    }

    companion object {
        /** Live service instance so in-app UI can flip renderer-level toggles. */
        @Volatile
        var instance: PlaybackService? = null
            private set

        const val MIRROR_PREFS = "puretube_mirror"
        const val KEY_REQUIRE_AUDIO_FOCUS = "require_audio_focus"
        const val KEY_DISABLE_ANDROID_AUTO = "disable_android_auto"
        const val KEY_APP_LANGUAGE = "app_language"
        private const val ANDROID_AUTO_PACKAGE = "com.google.android.projection.gearhead"

        private val _lastPlayerError = MutableStateFlow<String?>(null)
        /** Most recent ExoPlayer error in the service, for diagnostics. */
        val lastPlayerError: StateFlow<String?> = _lastPlayerError
    }
}
