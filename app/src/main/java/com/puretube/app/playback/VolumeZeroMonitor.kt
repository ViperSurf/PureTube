package com.puretube.app.playback

import android.content.Context
import android.database.ContentObserver
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import com.puretube.app.data.local.datastore.AppPreferencesState
import com.puretube.app.domain.VolumeZeroRule
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Volume-zero rule (v2.0, reworked v2.1): when the device STREAM_MUSIC
 * volume reaches 0 while a video is playing, the configured action
 * fires — PAUSE only (default), a 5/10/15s REWIND, or REWIND + PAUSE.
 * If the volume stays at 0 continuously for the configured delay,
 * playback STOPS entirely (position is persisted first by the owner).
 * When the volume rises again, playback resumes ONLY when the user has
 * enabled "Resume when volume returns" AND this rule did the pausing —
 * the default remains Bara's original rule: never auto-resume.
 *
 * The decision logic is the pure [VolumeZeroRule] state machine; this
 * class only observes the volume (ContentObserver for immediacy + a
 * 500 ms poll for the stop timer), feeds it samples, and performs the
 * resulting actions through [Callbacks].
 */
class VolumeZeroMonitor(
    private val context: Context,
    private val scope: CoroutineScope,
    private val prefsProvider: () -> AppPreferencesState,
    private val callbacks: Callbacks
) {
    interface Callbacks {
        fun isPlaying(): Boolean
        fun hasMedia(): Boolean
        fun currentMediaId(): String?
        fun pause()
        /** Persist the position, then stop the player. */
        fun stopPlayback()
        /** v2.1: seek backwards by [ms] (volume-zero rewind actions). */
        fun seekBack(ms: Long)
        /** v2.1: resume after a rule pause (only when the option is on). */
        fun resume()
    }

    /** One-line in-player notice ("Paused — volume is zero" / "Stopped — volume stayed zero"). */
    private val _notice = MutableStateFlow<String?>(null)
    val notice: StateFlow<String?> = _notice

    private var ruleState = VolumeZeroRule.State()
    private var lastMediaId: String? = null
    private var pollJob: Job? = null
    private var started = false

    private val volumeObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) {
            sample()
        }
    }

    fun start() {
        if (started) return
        started = true
        runCatching {
            context.contentResolver.registerContentObserver(
                Settings.System.CONTENT_URI, true, volumeObserver
            )
        }
        pollJob = scope.launch {
            while (isActive) {
                sample()
                delay(500L)
            }
        }
    }

    fun stop() {
        if (!started) return
        started = false
        runCatching { context.contentResolver.unregisterContentObserver(volumeObserver) }
        pollJob?.cancel()
        pollJob = null
    }

    private fun sample() {
        val prefs = prefsProvider()
        // A new media item re-arms the rule (STOPPED is per-item).
        val mediaId = runCatching { callbacks.currentMediaId() }.getOrNull()
        if (mediaId != lastMediaId) {
            lastMediaId = mediaId
            ruleState = VolumeZeroRule.reset()
            if (mediaId != null) _notice.value = null
        }
        val volume = runCatching {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            am?.getStreamVolume(AudioManager.STREAM_MUSIC) ?: -1
        }.getOrDefault(-1)
        if (volume < 0) return
        val previousPhase = ruleState.phase
        val (next, action) = VolumeZeroRule.onSample(
            state = ruleState,
            volumeIsZero = volume == 0,
            isPlaying = runCatching { callbacks.isPlaying() }.getOrDefault(false),
            hasMedia = runCatching { callbacks.hasMedia() }.getOrDefault(false),
            enabled = prefs.pauseOnVolumeZero,
            stopDelayMs = prefs.volumeZeroStopSeconds.toLong() * 1000L,
            nowMs = System.currentTimeMillis(),
            zeroAction = prefs.volumeZeroAction,
            resumeOnReturn = prefs.resumeOnVolumeReturn
        )
        ruleState = next
        when (action) {
            VolumeZeroRule.Action.PAUSE -> {
                runCatching { callbacks.pause() }
                _notice.value = "Paused — volume is zero"
            }
            VolumeZeroRule.Action.REWIND -> {
                val ms = next.lastRewindMs
                runCatching { callbacks.seekBack(ms) }
                _notice.value = "Rewound ${ms / 1000}s — volume is zero"
            }
            VolumeZeroRule.Action.REWIND_AND_PAUSE -> {
                val ms = next.lastRewindMs
                runCatching { callbacks.seekBack(ms) }
                runCatching { callbacks.pause() }
                _notice.value = "Rewound ${ms / 1000}s & paused — volume is zero"
            }
            VolumeZeroRule.Action.STOP -> {
                runCatching { callbacks.stopPlayback() }
                _notice.value = "Stopped — volume stayed zero"
            }
            VolumeZeroRule.Action.RESUME -> {
                runCatching { callbacks.resume() }
                _notice.value = null
            }
            VolumeZeroRule.Action.NONE -> {
                // Volume returned while we were only pending (no stop, no
                // resume): the "paused/rewound" note no longer applies.
                if (volume > 0 && previousPhase == VolumeZeroRule.Phase.ZERO_PENDING_STOP &&
                    next.phase == VolumeZeroRule.Phase.INACTIVE
                ) {
                    _notice.value = null
                }
            }
        }
    }
}
