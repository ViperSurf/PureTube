package com.puretube.app

import com.puretube.app.data.local.entity.WatchProgressEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WatchProgressTest {

    @Test
    fun completionThresholdIs92Percent() {
        assertFalse(WatchProgressEntity.computeCompleted(919L, 1000L))
        assertTrue(WatchProgressEntity.computeCompleted(920L, 1000L))
        assertTrue(WatchProgressEntity.computeCompleted(1000L, 1000L))
        assertFalse(WatchProgressEntity.computeCompleted(100L, 0L))
    }

    @Test
    fun progressFractionClamps() {
        val entity = WatchProgressEntity(
            videoId = "v", title = "t", channelName = "c", channelId = "cid",
            thumbnailUrl = "", positionMs = 1500L, durationMs = 1000L,
            isCompleted = true, lastUpdatedEpochMs = 0L
        )
        assertEquals(1.0f, entity.progressFraction, 0.0001f)
    }
}
