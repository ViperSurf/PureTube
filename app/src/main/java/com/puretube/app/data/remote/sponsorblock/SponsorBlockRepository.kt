package com.puretube.app.data.remote.sponsorblock

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.security.MessageDigest

@Serializable
data class SponsorSegment(
    val uuid: String,
    val category: String,
    val startMs: Long,
    val endMs: Long
)

object SponsorBlockHashing {
    fun sha256Hex(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    /** First 4 hex chars of the SHA-256 — the only thing ever sent to the server. */
    fun prefixFor(videoId: String): String = sha256Hex(videoId).take(4)
}

class SponsorBlockRepository(private val okHttpClient: OkHttpClient) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun fetchSponsorSegments(videoId: String, categories: Set<String>): List<SponsorSegment> =
        withContext(Dispatchers.IO) {
            runCatching {
                val prefix = SponsorBlockHashing.prefixFor(videoId)
                val req = Request.Builder()
                    .url("https://sponsor.ajay.app/api/skipSegments/$prefix")
                    .build()
                okHttpClient.newCall(req).execute().use { response ->
                    if (!response.isSuccessful) return@use emptyList()
                    parseSkipSegments(response.body!!.string(), videoId, categories)
                }
            }.getOrDefault(emptyList())
        }

    companion object {
        /**
         * Parses the prefix-endpoint payload:
         * [{"videoID":"...","hash":"...","segments":[{"category":"sponsor","segment":[startSec,endSec],"UUID":"..."}]}]
         * Pure function — unit-testable.
         */
        fun parseSkipSegments(payload: String, videoId: String, categories: Set<String>): List<SponsorSegment> {
            val root = runCatching { Json.parseToJsonElement(payload).jsonArray }.getOrNull()
                ?: return emptyList()
            val out = mutableListOf<SponsorSegment>()
            for (entry in root) {
                val obj = runCatching { entry.jsonObject }.getOrNull() ?: continue
                if (obj["videoID"]?.jsonPrimitive?.content != videoId) continue
                val segments = obj["segments"]?.jsonArray ?: continue
                for (segEl in segments) {
                    val seg = runCatching { segEl.jsonObject }.getOrNull() ?: continue
                    val category = seg["category"]?.jsonPrimitive?.content.orEmpty()
                    if (category !in categories) continue
                    val range = seg["segment"]?.jsonArray ?: continue
                    if (range.size < 2) continue
                    val startSec = range[0].jsonPrimitive.content.toDoubleOrNull() ?: continue
                    val endSec = range[1].jsonPrimitive.content.toDoubleOrNull() ?: continue
                    out.add(
                        SponsorSegment(
                            uuid = seg["UUID"]?.jsonPrimitive?.content.orEmpty(),
                            category = category,
                            startMs = (startSec * 1000).toLong(),
                            endMs = (endSec * 1000).toLong()
                        )
                    )
                }
            }
            return out.sortedBy { it.startMs }
        }
    }
}
