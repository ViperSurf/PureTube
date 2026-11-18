package com.puretube.app.data.remote.ryd

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request

data class DislikeStats(
    val likes: Long,
    val dislikes: Long,
    val likeRatio: Float
)

class ReturnYouTubeDislikeRepo(private val okHttpClient: OkHttpClient) {

    suspend fun fetchDislikeStats(videoId: String): DislikeStats? = withContext(Dispatchers.IO) {
        runCatching {
            val req = Request.Builder()
                .url("https://returnyoutubedislikeapi.com/votes?videoId=$videoId")
                .build()
            okHttpClient.newCall(req).execute().use { response ->
                if (!response.isSuccessful) return@use null
                parse(response.body!!.string())
            }
        }.getOrNull()
    }

    companion object {
        fun parse(payload: String): DislikeStats? {
            val obj = runCatching { Json.parseToJsonElement(payload).jsonObject }.getOrNull()
                ?: return null
            val likes = obj["likes"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0L
            val dislikes = obj["dislikes"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0L
            val total = likes + dislikes
            val ratio = if (total > 0) likes.toFloat() / total.toFloat() else 0f
            return DislikeStats(likes, dislikes, ratio)
        }
    }
}
