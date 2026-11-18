package com.puretube.app

import com.puretube.app.data.remote.sponsorblock.SponsorBlockHashing
import com.puretube.app.data.remote.sponsorblock.SponsorBlockRepository
import org.junit.Assert.assertEquals
import org.junit.Test

class SponsorBlockTest {

    @Test
    fun sha256PrefixMatchesKnownVector() {
        // SHA-256("abc") = ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad
        assertEquals("ba7816bf", SponsorBlockHashing.sha256Hex("abc").take(8))
        assertEquals("ba78", SponsorBlockHashing.prefixFor("abc"))
    }

    @Test
    fun parseSkipSegmentsFiltersVideoAndCategory() {
        val payload = """
            [
              {"videoID":"vidA","hash":"abcd","segments":[
                {"category":"sponsor","segment":[10.5,42.0],"UUID":"u1"},
                {"category":"outro","segment":[300.0,310.0],"UUID":"u2"}
              ]},
              {"videoID":"vidB","hash":"abcd","segments":[
                {"category":"sponsor","segment":[1.0,2.0],"UUID":"u3"}
              ]}
            ]
        """.trimIndent()
        val segments = SponsorBlockRepository.parseSkipSegments(
            payload, "vidA", setOf("sponsor")
        )
        assertEquals(1, segments.size)
        assertEquals(10_500L, segments.first().startMs)
        assertEquals(42_000L, segments.first().endMs)
        assertEquals("u1", segments.first().uuid)
    }
}
