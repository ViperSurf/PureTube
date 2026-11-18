package com.puretube.app

import com.puretube.app.data.remote.extractor.StreamExtractorRepository
import org.junit.Assert.assertEquals
import org.junit.Test

class StreamExtractorRepositoryTest {

    @Test
    fun shortsUrlNormalizesToWatchUrl() {
        assertEquals(
            "https://www.youtube.com/watch?v=dQw4w9WgXcQ",
            StreamExtractorRepository.normalizeAndStripShortsUrl("https://www.youtube.com/shorts/dQw4w9WgXcQ")
        )
    }

    @Test
    fun shortShareUrlNormalizesToWatchUrl() {
        assertEquals(
            "https://www.youtube.com/watch?v=dQw4w9WgXcQ",
            StreamExtractorRepository.normalizeAndStripShortsUrl("https://youtu.be/dQw4w9WgXcQ?si=abc")
        )
    }

    @Test
    fun extractVideoIdFromWatchUrl() {
        assertEquals(
            "dQw4w9WgXcQ",
            StreamExtractorRepository.extractVideoId("https://www.youtube.com/watch?v=dQw4w9WgXcQ&t=42s")
        )
    }

    @Test
    fun extractVideoIdFromBareId() {
        assertEquals("dQw4w9WgXcQ", StreamExtractorRepository.extractVideoId("dQw4w9WgXcQ"))
    }
}
