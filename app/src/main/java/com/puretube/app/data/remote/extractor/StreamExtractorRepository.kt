package com.puretube.app.data.remote.extractor

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.search.SearchInfo
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamInfoItem

data class ResolvedVideoStream(
    val videoId: String,
    val title: String,
    val uploaderName: String,
    val uploaderId: String,
    val uploaderAvatarUrl: String,
    val durationSeconds: Long,
    val viewCount: Long,
    val likeCount: Long,
    val dashManifestUrl: String?,
    val hlsUrl: String?,
    val videoStreamUrl: String?,
    val audioOnlyStreamUrl: String,
    val thumbnailUrl: String
)

class StreamExtractorRepository(
    private val okHttpClient: OkHttpClient,
    private val pipedFallbackBaseUrlProvider: () -> String
) {
    companion object {
        private val VIDEO_ID_REGEX = Regex("(?:youtube\.com/shorts/|youtu\.be/|v=|youtube\.com/embed/)([a-zA-Z0-9_-]{11})")
        private val BARE_ID_REGEX = Regex("^[a-zA-Z0-9_-]{11}$")

        fun extractVideoId(rawUrlOrId: String): String {
            val trimmed = rawUrlOrId.trim()
            VIDEO_ID_REGEX.find(trimmed)?.let { return it.groupValues[1] }
            if (BARE_ID_REGEX.matches(trimmed)) return trimmed
            return trimmed.take(11)
        }

        fun normalizeAndStripShortsUrl(rawUrlOrId: String): String =
            "https://www.youtube.com/watch?v=${extractVideoId(rawUrlOrId)}"
    }

    fun normalizeAndStripShortsUrl(rawUrlOrId: String): String =
        Companion.normalizeAndStripShortsUrl(rawUrlOrId)

    fun extractVideoId(rawUrlOrId: String): String = Companion.extractVideoId(rawUrlOrId)

    /**
     * NewPipeExtractor first; on failure (403 / bot guard / cipher rotation)
     * silently fails over to the configured Piped API. No user-facing popups.
     */
    suspend fun extractStreamWithFailover(
        rawUrlOrId: String,
        preferredMaxHeight: Int,
        preferAv1Vp9: Boolean
    ): Result<ResolvedVideoStream> = withContext(Dispatchers.IO) {
        val canonicalUrl = normalizeAndStripShortsUrl(rawUrlOrId)
        val videoId = extractVideoId(rawUrlOrId)

        val primaryAttempt = runCatching {
            val info = StreamInfo.getInfo(ServiceList.YouTube, canonicalUrl)
            val bestAudio = info.audioStreams
                .maxByOrNull { it.averageBitrate }
                ?.content ?: error("No audio stream found")

            val bestVideo = info.videoOnlyStreams
                .filter { parseHeight(it.resolution) <= preferredMaxHeight || preferredMaxHeight <= 0 }
                .sortedWith(
                    compareByDescending<org.schabi.newpipe.extractor.stream.VideoStream> {
                        if (preferAv1Vp9) {
                            val name = it.format?.name.orEmpty()
                            name.contains("AV1") || name.contains("VP9")
                        } else {
                            false
                        }
                    }.thenByDescending { parseHeight(it.resolution) }
                )
                .firstOrNull()?.content
                ?: info.videoStreams.firstOrNull()?.content

            ResolvedVideoStream(
                videoId = videoId,
                title = info.name,
                uploaderName = info.uploaderName,
                uploaderId = info.uploaderUrl.substringAfterLast("/"),
                uploaderAvatarUrl = info.uploaderAvatars.firstOrNull()?.url.orEmpty(),
                durationSeconds = info.duration,
                viewCount = info.viewCount,
                likeCount = info.likeCount,
                dashManifestUrl = info.dashMpdUrl.takeIf { it.isNotBlank() },
                hlsUrl = info.hlsUrl.takeIf { it.isNotBlank() },
                videoStreamUrl = bestVideo,
                audioOnlyStreamUrl = bestAudio,
                thumbnailUrl = info.thumbnails.maxByOrNull { it.height }?.url.orEmpty()
            )
        }
        if (primaryAttempt.isSuccess) return@withContext primaryAttempt

        runCatching {
            val baseUrl = pipedFallbackBaseUrlProvider().trimEnd('/')
            val req = Request.Builder().url("$baseUrl/streams/$videoId").build()
            okHttpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) error("Failover HTTP ${resp.code}")
                val root = Json.parseToJsonElement(resp.body!!.string()).jsonObject
                val audioUrl = root["audioStreams"]!!.jsonArray
                    .first().jsonObject["url"]!!.jsonPrimitive.content
                val videoUrl = root["videoStreams"]!!.jsonArray
                    .firstOrNull()?.jsonObject?.get("url")?.jsonPrimitive?.content
                ResolvedVideoStream(
                    videoId = videoId,
                    title = root["title"]?.jsonPrimitive?.content.orEmpty(),
                    uploaderName = root["uploader"]?.jsonPrimitive?.content.orEmpty(),
                    uploaderId = root["uploaderUrl"]?.jsonPrimitive?.content?.substringAfterLast("/").orEmpty(),
                    uploaderAvatarUrl = root["uploaderAvatar"]?.jsonPrimitive?.content.orEmpty(),
                    durationSeconds = root["duration"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0L,
                    viewCount = root["views"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0L,
                    likeCount = root["likes"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0L,
                    dashManifestUrl = root["dash"]?.jsonPrimitive?.content,
                    hlsUrl = root["hls"]?.jsonPrimitive?.content,
                    videoStreamUrl = videoUrl,
                    audioOnlyStreamUrl = audioUrl,
                    thumbnailUrl = root["thumbnailUrl"]?.jsonPrimitive?.content.orEmpty()
                )
            }
        }
    }

    /** Instant search via NewPipe; Shorts are stripped by the caller/UI layer. */
    suspend fun searchVideos(query: String): Result<List<FeedItem>> = withContext(Dispatchers.IO) {
        runCatching {
            val service = ServiceList.YouTube
            val handler = service.searchQHFactory.fromQuery(
                query,
                listOf(
                    org.schabi.newpipe.extractor.services.youtube.search.filter
                        .YoutubeSearchQueryHandlerFactory.VIDEOS
                ),
                ""
            )
            val info = SearchInfo.getInfo(service, handler)
            info.relatedItems.filterIsInstance<StreamInfoItem>().map { item ->
                FeedItem(
                    videoId = extractVideoId(item.url),
                    title = item.name,
                    channelName = item.uploaderName,
                    channelId = item.uploaderUrl?.substringAfterLast("/").orEmpty(),
                    thumbnailUrl = item.thumbnails.maxByOrNull { it.height }?.url.orEmpty(),
                    durationSeconds = item.duration,
                    url = item.url,
                    isShort = item.url.contains("/shorts/")
                )
            }
        }
    }

    private fun parseHeight(resolution: String?): Int =
        resolution?.filter { it.isDigit() }?.toIntOrNull() ?: 0
}
