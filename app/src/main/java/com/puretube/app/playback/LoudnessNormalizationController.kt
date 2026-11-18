package com.puretube.app.playback

import android.media.audiofx.LoudnessEnhancer
import androidx.media3.common.C

/** Wraps LoudnessEnhancer: gentle +350 mB target gain for quiet speech. */
class LoudnessNormalizationController {
    private var enhancer: LoudnessEnhancer? = null

    var isEnabled: Boolean = false
        set(value) {
            field = value
            runCatching { enhancer?.enabled = value }
        }

    fun onAudioSessionIdChanged(audioSessionId: Int) {
        if (audioSessionId == C.AUDIO_SESSION_ID_UNSET) return
        runCatching {
            enhancer?.release()
            enhancer = LoudnessEnhancer(audioSessionId).apply {
                setTargetGain(TARGET_GAIN_MB)
                enabled = isEnabled
            }
        }
    }

    fun release() {
        runCatching { enhancer?.release() }
        enhancer = null
    }

    companion object {
        const val TARGET_GAIN_MB = 350
    }
}
