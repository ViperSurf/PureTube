package com.puretube.app

import com.puretube.app.data.backup.SubscriptionParsers
import org.junit.Assert.assertEquals
import org.junit.Test

class SubscriptionParsersTest {

    @Test
    fun parsesNewPipeJson() {
        val json = """
            {"app_version":"0.27.0","subscriptions":[
              {"service_id":0,"url":"https://www.youtube.com/channel/UCabc123","name":"Channel One"},
              {"service_id":0,"url":"https://www.youtube.com/channel/UCdef456","name":"Channel Two"}
            ]}
        """.trimIndent()
        val subs = SubscriptionParsers.parseNewPipeJson(json, nowEpochMs = 5L)
        assertEquals(2, subs.size)
        assertEquals("UCabc123", subs[0].channelId)
        assertEquals("Channel One", subs[0].channelName)
        assertEquals(5L, subs[0].addedAtEpochMs)
    }

    @Test
    fun parsesTakeoutCsvWithQuotedTitle() {
        val csv = """
            Channel Id,Channel Url,Channel Title
            UCabc123,https://www.youtube.com/channel/UCabc123,"Lectures, Weekly"
            UCdef456,https://www.youtube.com/channel/UCdef456,Plain Title
        """.trimIndent()
        val subs = SubscriptionParsers.parseTakeoutCsv(csv)
        assertEquals(2, subs.size)
        assertEquals("Lectures, Weekly", subs[0].channelName)
        assertEquals("UCdef456", subs[1].channelId)
    }

    @Test
    fun exportRoundTripsThroughParser() {
        val subs = SubscriptionParsers.parseNewPipeJson(
            """{"subscriptions":[{"service_id":0,"url":"https://www.youtube.com/channel/UCx1","name":"X One"}]}"""
        )
        val exported = SubscriptionParsers.exportNewPipeJson(subs)
        val reparsed = SubscriptionParsers.parseNewPipeJson(exported)
        assertEquals(1, reparsed.size)
        assertEquals("UCx1", reparsed[0].channelId)
        assertEquals("X One", reparsed[0].channelName)
    }
}
