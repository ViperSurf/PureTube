package com.puretube.app.playback

import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

data class PlayerUiState(
    val isPlaying: Boolean = false,
    val isMuted: Boolean = false,
    val playbackSpeed: Float = 1.0f,
    val audioOnly: Boolean = false,
    val monoEnabled: Boolean = false,
    val normalizationEnabled: Boolean = false,
    val currentPositionMs: Long = 0L,
    val durationMs: Long = 0L,
    val title: String = ""
)

/** StateFlow wrapper around the shared player: speed, audio-only, mono, normalization. */
class PlayerController {

    private val _state = MutableStateFlow(PlayerUiState())
    val state: StateFlow<PlayerUiState> = _state

    private var player: Player? = null
    private var previousVolume: Float = 1f

    private val listener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            _state.value = _state.value.copy(isPlaying = isPlaying)
        }

        override fun onPlaybackParametersChanged(playbackParameters: androidx.media3.common.PlaybackParameters) {
            _state.value = _state.value.copy(playbackSpeed = playbackParameters.speed)
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            _state.value = _state.value.copy(
                title = mediaItem?.mediaMetadata?.title?.toString().orEmpty(),
                durationMs = player?.duration?.coerceAtLeast(0) ?: 0L
            )
        }
    }

    fun attach(player: Player) {
        detach()
        this.player = player
        player.addListener(listener)
        _state.value = _state.value.copy(
            isPlaying = player.isPlaying,
            playbackSpeed = player.playbackParameters.speed,
            isMuted = player.volume == 0f
        )
    }

    fun detach() {
        player?.removeListener(listener)
        player = null
    }

    fun playMediaItem(item: MediaItem, startPositionMs: Long = 0L) {
        val p = player ?: return
        p.setMediaItem(item, startPositionMs)
        p.prepare()
        p.playWhenReady = true
    }

    fun togglePlayPause() {
        val p = player ?: return
        if (p.isPlaying) p.pause() else p.play()
    }

    fun seekBy(deltaMs: Long) {
        val p = player ?: return
        val target = (p.currentPosition + deltaMs).coerceIn(0, maxOf(p.duration, 0L))
        p.seekTo(target)
    }

    fun seekTo(positionMs: Long) {
        player?.seekTo(positionMs)
    }

    fun setSpeed(speed: Float) {
        player?.setPlaybackSpeed(speed)
        _state.value = _state.value.copy(playbackSpeed = speed)
    }

    /** Cycles 1.0x -> 1.25x -> 1.5x -> 2.0x -> back to 1.0x. */
    fun cycleSpeed(): Float {
        val speeds = listOf(1.0f, 1.25f, 1.5f, 2.0f)
        val current = _state.value.playbackSpeed
        val next = speeds.firstOrNull { it > current + 0.01f } ?: speeds.first()
        setSpeed(next)
        return next
    }

    fun toggleMute() {
        val p = player ?: return
        if (p.volume > 0f) {
            previousVolume = p.volume
            p.volume = 0f
            _state.value = _state.value.copy(isMuted = true)
        } else {
            p.volume = previousVolume
            _state.value = _state.value.copy(isMuted = false)
        }
    }

    fun setAudioOnly(enabled: Boolean) {
        PlaybackService.instance?.setAudioOnlyMode(enabled)
        _state.value = _state.value.copy(audioOnly = enabled)
    }

    fun setMono(enabled: Boolean) {
        PlaybackService.instance?.setMonoEnabled(enabled)
        _state.value = _state.value.copy(monoEnabled = enabled)
    }

    fun setNormalization(enabled: Boolean) {
        PlaybackService.instance?.setAudioNormalization(enabled)
        _state.value = _state.value.copy(normalizationEnabled = enabled)
    }

    fun currentPositionMs(): Long = player?.currentPosition ?: 0L
    fun durationMs(): Long = player?.duration?.coerceAtLeast(0) ?: 0L
    fun isPlaying(): Boolean = player?.isPlaying ?: false
}
