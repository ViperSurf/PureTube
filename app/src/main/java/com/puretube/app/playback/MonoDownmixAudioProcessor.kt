package com.puretube.app.playback

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Pure PCM16 stereo -> mono math, unit-testable without Media3. */
object MonoMath {
    /** Averages each (L,R) sample pair and writes the mono value to both channels. */
    fun downmixStereoToMono(samples: ShortArray): ShortArray {
        val out = ShortArray(samples.size)
        var i = 0
        while (i + 1 < samples.size) {
            val mono = ((samples[i].toInt() + samples[i + 1].toInt()) / 2).toShort()
            out[i] = mono
            out[i + 1] = mono
            i += 2
        }
        if (i < samples.size) out[i] = samples[i]
        return out
    }
}

/**
 * Media3 AudioProcessor that merges stereo into mono (both channels carry the
 * average) when enabled — ideal for single-earbud listening. Passthrough otherwise.
 */
class MonoDownmixAudioProcessor : BaseAudioProcessor() {

    @Volatile
    var isMonoEnabled: Boolean = false

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        return if (inputAudioFormat.encoding == C.ENCODING_PCM_16BIT && inputAudioFormat.channelCount == 2) {
            inputAudioFormat
        } else {
            AudioProcessor.AudioFormat.NOT_SET
        }
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val remaining = inputBuffer.remaining()
        if (remaining == 0) return
        val outputBuffer = replaceOutputBuffer(remaining)
        if (!isMonoEnabled) {
            outputBuffer.put(inputBuffer)
            outputBuffer.flip()
            return
        }
        val ordered = inputBuffer.order(ByteOrder.LITTLE_ENDIAN)
        val outOrdered = outputBuffer.order(ByteOrder.LITTLE_ENDIAN)
        while (ordered.remaining() >= 4) {
            val left = ordered.short.toInt()
            val right = ordered.short.toInt()
            val mono = ((left + right) / 2).toShort()
            outOrdered.putShort(mono)
            outOrdered.putShort(mono)
        }
        outputBuffer.flip()
    }
}
