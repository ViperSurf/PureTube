package com.puretube.app.haramblur.system

import java.nio.ByteBuffer
import kotlin.math.abs

/**
 * Sparse-grid luma checksum over a captured frame. When the screen is static
 * the checksum barely moves and ML inference is skipped entirely (0% idle drain).
 */
class MotionDeltaDetector(private val threshold: Long = 350L) {
    private var previousLumaChecksum: Long = Long.MIN_VALUE / 2

    fun checksum(buffer: ByteBuffer, step: Int = 256): Long {
        var sum = 0L
        val limit = buffer.limit()
        var idx = 0
        while (idx < limit) {
            sum += (buffer.get(idx).toInt() and 0xFF)
            idx += step
        }
        return sum
    }

    /** Returns true when the frame changed enough to be worth scanning. */
    fun hasMotion(buffer: ByteBuffer): Boolean {
        val sum = checksum(buffer)
        val changed = abs(sum - previousLumaChecksum) > threshold
        if (changed) previousLumaChecksum = sum
        return changed
    }

    fun reset() {
        previousLumaChecksum = Long.MIN_VALUE / 2
    }
}
