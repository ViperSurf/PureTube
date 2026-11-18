package com.puretube.app

import com.puretube.app.playback.MonoMath
import org.junit.Assert.assertArrayEquals
import org.junit.Test

class MonoMathTest {

    @Test
    fun downmixAveragesPairsIntoBothChannels() {
        val input = shortArrayOf(100, 300, -200, 200, 32767, 32767)
        val expected = shortArrayOf(200, 200, 0, 0, 32767, 32767)
        assertArrayEquals(expected, MonoMath.downmixStereoToMono(input))
    }

    @Test
    fun downmixHandlesOddTrailingSample() {
        val input = shortArrayOf(10, 20, 7)
        val expected = shortArrayOf(15, 15, 7)
        assertArrayEquals(expected, MonoMath.downmixStereoToMono(input))
    }
}
