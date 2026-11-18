package com.puretube.app.playback

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Handles PiP RemoteAction taps. Actions are forwarded to the running
 * PlaybackService player through the shared service instance.
 */
class PipActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val player = PlaybackService.instance?.player ?: return
        when (intent.action) {
            ACTION_TOGGLE_PLAY -> if (player.isPlaying) player.pause() else player.play()
            ACTION_TOGGLE_MUTE -> player.volume = if (player.volume > 0f) 0f else 1f
            ACTION_SEEK_FORWARD -> player.seekTo(
                (player.currentPosition + 10_000L).coerceAtMost(maxOf(player.duration, 0L))
            )
        }
    }

    companion object {
        const val ACTION_TOGGLE_PLAY = "com.puretube.app.pip.TOGGLE_PLAY"
        const val ACTION_TOGGLE_MUTE = "com.puretube.app.pip.TOGGLE_MUTE"
        const val ACTION_SEEK_FORWARD = "com.puretube.app.pip.SEEK_FORWARD"
    }
}
