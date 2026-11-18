package com.puretube.app.data.remote.dearrow

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.security.MessageDigest

data class DeArrowResult(
    val neutralTitle: String?,
    val thumbnailTimestampSeconds: Double?
)

/** DeArrow: community-submitted neutral titles and honest thumbnail timestamps. */
class DeArrowRepository(private val okHttpClient: OkHttpClient) {

    suspend fun fetch(videoId: String): DeArrowResult? = withContext(Dispatchers.IO) {
        runCatching {
            val hash = MessageDigest.getInstance("SHA-256")
                .digest(videoId.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
                .take(4)
            val req = Request.Builder()
                .url("https://sponsor.ajay.app/api/branding/$hash")
                .build()
            okHttpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@use null
                parse(resp.body!!.string(), videoId)
            }
        }.getOrNull()
    }

    companion object {
        fun parse(payload: String, videoId: String): DeArrowResult? {
            val root = runCatching { Json.parseToJsonElement(payload).jsonObject }.getOrNull()
                ?: return null
            val entry = root[videoId]?.jsonObject ?: return null
            val titles = entry["titles"]?.jsonArray
            val title = titles?.firstOrNull()?.jsonObject
                ?.get("title")?.jsonPrimitive?.content
            val thumbs = entry["thumbnails"]?.jsonArray
            val timestamp = thumbs?.firstOrNull()?.jsonObject
                ?.get("timestamp")?.jsonPrimitive?.content?.toDoubleOrNull()
            if (title == null && timestamp == null) return null
            return DeArrowResult(title, timestamp)
        }
    }
}
