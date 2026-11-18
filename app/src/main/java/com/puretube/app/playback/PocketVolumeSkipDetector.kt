package com.puretube.app.playback

import android.view.KeyEvent

/**
 * Pocket skip: a 0.6s long-press of Volume Up / Down (screen off) seeks
 * forward / backward. Short presses keep their normal volume behavior.
 * Timing logic is pure and driven by an injectable clock for testing.
 */
class PocketVolumeSkipDetector(
    private val longPressThresholdMs: Long = 600L,
    private val seekDeltaMs: Long = 10_000L,
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val onSeek: (deltaMs: Long) -> Unit
) {
    private var volumeUpDownAtMs: Long? = null
    private var volumeDownDownAtMs: Long? = null
    private var firedForCurrentPress: Boolean = false

    var isEnabled: Boolean = true

    /** Returns true when the event was consumed as a pocket skip. */
    fun onKeyDown(keyCode: Int): Boolean {
        if (!isEnabled) return false
        when (keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP -> if (volumeUpDownAtMs == null) {
                volumeUpDownAtMs = clock(); firedForCurrentPress = false
            }
            KeyEvent.KEYCODE_VOLUME_DOWN -> if (volumeDownDownAtMs == null) {
                volumeDownDownAtMs = clock(); firedForCurrentPress = false
            }
            else -> return false
        }
        return checkLongPress(keyCode)
    }

    fun onKeyUp(keyCode: Int): Boolean {
        val consumed = checkLongPress(keyCode)
        when (keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP -> volumeUpDownAtMs = null
            KeyEvent.KEYCODE_VOLUME_DOWN -> volumeDownDownAtMs = null
        }
        return consumed
    }

    /** Call periodically (or on repeat events) while a volume key is held. */
    fun checkLongPress(keyCode: Int): Boolean {
        if (!isEnabled || firedForCurrentPress) return firedForCurrentPress && isEnabled
        val downAt = when (keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP -> volumeUpDownAtMs
            KeyEvent.KEYCODE_VOLUME_DOWN -> volumeDownDownAtMs
            else -> null
        } ?: return false
        if (clock() - downAt >= longPressThresholdMs) {
            firedForCurrentPress = true
            onSeek(if (keyCode == KeyEvent.KEYCODE_VOLUME_UP) seekDeltaMs else -seekDeltaMs)
            return true
        }
        return false
    }
}
