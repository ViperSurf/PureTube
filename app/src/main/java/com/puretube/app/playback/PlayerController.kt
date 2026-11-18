package com.puretube.app.playback

import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import com.puretube.app.data.remote.extractor.FeedItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

data class PlayerUiState(
    val isPlaying: Boolean = false,
    val isMuted: Boolean = false,
    val playbackSpeed: Float = 1.0f,
    val audioOnly: Boolean = false,
    val monoEnabled: Boolean = false,
    val normalizationEnabled: Boolean = false,
    val currentPositionMs: Long = 0L,
    val durationMs: Long = 0L,
    val title: String = "",
    /** Media id of the current item (queue highlighting, v1.2). */
    val currentVideoId: String = "",
    /** Last player error message, or null. Surfaced in-player, never thrown. */
    val playerError: String? = null,
    val sleepTimerMinutes: Int = 0,
    val sleepTimerExpired: Boolean = false
)

/**
 * Pure queue helpers — unit-testable without a player.
 */
object QueueLogic {
    /** Auto-queue candidates honour the short-only flag and the max-minutes cap. */
    fun filterAutoQueueCandidates(
        candidates: List<FeedItem>,
        shortOnly: Boolean,
        maxMinutes: Int
    ): List<FeedItem> {
        val maxSeconds = maxMinutes.coerceAtLeast(1) * 60L
        return candidates.filter { item ->
            if (item.durationSeconds <= 0) return@filter !shortOnly
            if (shortOnly && item.durationSeconds > 90L) return@filter false
            item.durationSeconds <= maxSeconds
        }
    }
}

/** StateFlow wrapper around the shared player: speed, audio-only, mono, normalization, queue, sleep timer. */
class PlayerController {

    private val _state = MutableStateFlow(PlayerUiState())
    val state: StateFlow<PlayerUiState> = _state

    private val _queue = MutableStateFlow<List<FeedItem>>(emptyList())
    val queue: StateFlow<List<FeedItem>> = _queue

    /** Transient in-app notice (e.g. "Added to queue (3)"), auto-dismissed. */
    private val _notice = MutableStateFlow<String?>(null)
    val notice: StateFlow<String?> = _notice
    private var noticeJob: Job? = null

    fun postNotice(message: String) {
        noticeJob?.cancel()
        _notice.value = message
        noticeJob = scope.launch {
            delay(2600L)
            _notice.value = null
        }
    }

    /** Invoked when playback reaches STATE_ENDED; the UI decides what plays next. */
    @Volatile var onPlaybackEnded: (() -> Unit)? = null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var sleepJob: Job? = null

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
                currentVideoId = mediaItem?.mediaId.orEmpty(),
                durationMs = player?.duration?.coerceAtLeast(0) ?: 0L,
                playerError = null
            )
        }

        override fun onPlayerError(error: PlaybackException) {
            // Record, never rethrow: the player screen turns this into an
            // in-player message with a fallback chain.
            _state.value = _state.value.copy(
                playerError = "${error.errorCodeName}: ${error.message ?: "playback error"}"
            )
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_ENDED) {
                runCatching { onPlaybackEnded?.invoke() }
            }
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
        runCatching {
            p.setMediaItem(item, startPositionMs)
            p.prepare()
            p.playWhenReady = true
        }.onFailure { e ->
            _state.value = _state.value.copy(playerError = "Could not start playback: ${e.message}")
        }
    }

    fun clearError() {
        _state.value = _state.value.copy(playerError = null)
    }

    // ---- Queue ----

    fun enqueue(item: FeedItem) {
        if (_queue.value.none { it.videoId == item.videoId }) {
            _queue.value = _queue.value + item
        }
    }

    fun enqueueAll(items: List<FeedItem>) {
        val existing = _queue.value.map { it.videoId }.toSet()
        _queue.value = _queue.value + items.filter { it.videoId !in existing }
    }

    fun removeFromQueue(videoId: String) {
        _queue.value = _queue.value.filterNot { it.videoId == videoId }
    }

    /** Pops the head of the queue, or null when empty. */
    fun popNextQueued(): FeedItem? {
        val head = _queue.value.firstOrNull() ?: return null
        _queue.value = _queue.value.drop(1)
        return head
    }

    fun clearQueue() {
        _queue.value = emptyList()
    }

    /** Moves a queued item one step up (-1) or down (+1). No-op at the edges. */
    fun moveInQueue(videoId: String, delta: Int) {
        val list = _queue.value.toMutableList()
        val idx = list.indexOfFirst { it.videoId == videoId }
        if (idx < 0) return
        val target = idx + delta
        if (target !in list.indices) return
        val item = list.removeAt(idx)
        list.add(target, item)
        _queue.value = list
    }

    /** Enqueues and posts the standard "Added to queue (N)" notice. */
    fun enqueueWithNotice(item: FeedItem) {
        enqueue(item)
        postNotice("Added to queue (${_queue.value.size})")
    }

    // ---- Sleep timer ----

    /** minutes <= 0 cancels. On expiry playback pauses and a flag is raised. */
    fun setSleepTimer(minutes: Int) {
        sleepJob?.cancel()
        sleepJob = null
        if (minutes <= 0) {
            _state.value = _state.value.copy(sleepTimerMinutes = 0, sleepTimerExpired = false)
            return
        }
        _state.value = _state.value.copy(sleepTimerMinutes = minutes, sleepTimerExpired = false)
        sleepJob = scope.launch {
            delay(minutes * 60_000L)
            runCatching { player?.pause() }
            _state.value = _state.value.copy(sleepTimerMinutes = 0, sleepTimerExpired = true)
        }
    }

    fun consumeSleepExpired() {
        _state.value = _state.value.copy(sleepTimerExpired = false)
    }

    fun togglePlayPause() {
        val p = player ?: return
        if (p.isPlaying) p.pause() else p.play()
    }

    fun pause() {
        runCatching { player?.pause() }
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

    /** Applies the requireAudioFocus preference to the live service player. */
    fun setRequireAudioFocus(enabled: Boolean) {
        PlaybackService.instance?.setRequireAudioFocus(enabled)
    }

    fun currentPositionMs(): Long = player?.currentPosition ?: 0L
    fun durationMs(): Long = player?.duration?.coerceAtLeast(0) ?: 0L
    fun isPlaying(): Boolean = player?.isPlaying ?: false
}

/** Maps a sleep-timer choice label to minutes; pure and unit-tested. */
object SleepTimerMapping {
    val optionsMinutes: List<Int> = listOf(0, 5, 10, 15, 30, 45, 60)
    fun labelFor(minutes: Int): String = if (minutes <= 0) "Off" else "$minutes min"
    fun coerceDefault(minutes: Int): Int =
        if (minutes in optionsMinutes) minutes else 15
}
