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
import com.puretube.app.domain.CommentItem

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
        private val VIDEO_ID_REGEX = Regex("(?:youtube\\.com/shorts/|youtu\\.be/|v=|youtube\\.com/embed/)([a-zA-Z0-9_-]{11})")
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
        preferAv1Vp9: Boolean,
        preferredAudioLanguageCode: String? = null
    ): Result<ResolvedVideoStream> = withContext(Dispatchers.IO) {
        val canonicalUrl = normalizeAndStripShortsUrl(rawUrlOrId)
        val videoId = extractVideoId(rawUrlOrId)

        val primaryAttempt = runCatching {
            val info = StreamInfo.getInfo(ServiceList.YouTube, canonicalUrl)
            val bestAudio = runCatching {
                val matching = preferredAudioLanguageCode?.let { code ->
                    info.audioStreams.filter {
                        runCatching { it.audioLocale?.language == code }.getOrDefault(false)
                    }
                }.orEmpty()
                (matching.maxByOrNull { it.averageBitrate }
                    ?: info.audioStreams.maxByOrNull { it.averageBitrate })
                    ?.content
            }.getOrNull() ?: info.audioStreams.maxByOrNull { it.averageBitrate }?.content
                ?: error("No audio stream found")

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
                    org.schabi.newpipe.extractor.services.youtube.linkHandler.YoutubeSearchQueryHandlerFactory.VIDEOS
                ),
                ""
            )
            val info = SearchInfo.getInfo(service, handler)
            info.relatedItems.filterIsInstance<StreamInfoItem>().map { item -> item.toFeedItem() }
        }
    }

    private fun StreamInfoItem.toFeedItem(): FeedItem = FeedItem(
        videoId = extractVideoId(url),
        title = name,
        channelName = uploaderName,
        channelId = uploaderUrl?.substringAfterLast("/") ?: "",
        thumbnailUrl = thumbnails.maxByOrNull { it.height }?.url.orEmpty(),
        durationSeconds = duration,
        url = url,
        isShort = url.contains("/shorts/") ||
            runCatching { isShortFormContent }.getOrDefault(false),
        channelAvatarUrl = runCatching {
            uploaderAvatars.maxByOrNull { it.height }?.url.orEmpty()
        }.getOrDefault(""),
        uploadEpochMs = runCatching {
            uploadDate?.offsetDateTime()?.toInstant()?.toEpochMilli() ?: 0L
        }.getOrDefault(0L)
    )

    /** Best-effort content-country localization for the extractor. */
    fun configureContentCountry(countryCode: String?) {
        runCatching {
            if (countryCode.isNullOrBlank() || countryCode == "SYSTEM") return
            org.schabi.newpipe.extractor.NewPipe.setupLocalization(
                org.schabi.newpipe.extractor.localization.Localization("en", countryCode),
                org.schabi.newpipe.extractor.localization.ContentCountry(countryCode)
            )
        }
    }

    /** Channel page: name/avatar/subscribers + its videos. Best-effort. */
    suspend fun fetchChannel(channelUrlOrId: String): Result<ChannelPayload> =
        withContext(Dispatchers.IO) {
            runCatching {
                val url = channelUrl(channelUrlOrId)
                val info = org.schabi.newpipe.extractor.channel.ChannelInfo
                    .getInfo(ServiceList.YouTube, url)
                ChannelPayload(
                    channelId = info.url.substringAfterLast("/"),
                    channelName = info.name,
                    avatarUrl = info.avatars.maxByOrNull { it.height }?.url.orEmpty(),
                    subscriberCount = runCatching { info.subscriberCount }.getOrDefault(-1L),
                    videos = info.relatedItems.filterIsInstance<StreamInfoItem>()
                        .map { it.toFeedItem() }
                )
            }
        }

    /** Resolves a channel URL / @handle / bare ID into a channel URL. */
    fun channelUrl(input: String): String {
        val trimmed = input.trim()
        return when {
            trimmed.startsWith("http") -> trimmed
            trimmed.startsWith("@") -> "https://www.youtube.com/$trimmed"
            trimmed.startsWith("UC") -> "https://www.youtube.com/channel/$trimmed"
            else -> "https://www.youtube.com/channel/$trimmed"
        }
    }

    /** Comments for a video. Best-effort; empty when disabled/unavailable. */
    suspend fun fetchComments(videoId: String): Result<List<CommentItem>> =
        withContext(Dispatchers.IO) {
            runCatching {
                val url = normalizeAndStripShortsUrl(videoId)
                val info = org.schabi.newpipe.extractor.comments.CommentsInfo
                    .getInfo(ServiceList.YouTube, url)
                if (info.isCommentsDisabled) return@runCatching emptyList()
                info.relatedItems.map { item ->
                    CommentItem(
                        id = runCatching { item.commentId }.getOrDefault(""),
                        authorName = runCatching { item.uploaderName }.getOrDefault(""),
                        authorChannelId = runCatching {
                            item.uploaderUrl?.substringAfterLast("/") ?: ""
                        }.getOrDefault(""),
                        text = runCatching { item.commentText.content }.getOrDefault(""),
                        likeCount = runCatching { item.likeCount }.getOrDefault(-1),
                        avatarUrl = runCatching {
                            item.uploaderAvatars.maxByOrNull { it.height }?.url.orEmpty()
                        }.getOrDefault("")
                    )
                }
            }
        }

    /** Remote playlist contents for bookmarked playlists. Best-effort. */
    suspend fun fetchRemotePlaylist(url: String): Result<RemotePlaylistPayload> =
        withContext(Dispatchers.IO) {
            runCatching {
                val info = org.schabi.newpipe.extractor.playlist.PlaylistInfo
                    .getInfo(ServiceList.YouTube, url)
                RemotePlaylistPayload(
                    url = url,
                    title = info.name,
                    channelName = runCatching { info.uploaderName }.getOrDefault(""),
                    thumbnailUrl = info.thumbnails.maxByOrNull { it.height }?.url.orEmpty(),
                    videos = info.relatedItems.filterIsInstance<StreamInfoItem>()
                        .map { it.toFeedItem() }
                )
            }
        }

    private fun parseHeight(resolution: String?): Int =
        resolution?.filter { it.isDigit() }?.toIntOrNull() ?: 0
}

/** Channel screen payload. */
data class ChannelPayload(
    val channelId: String,
    val channelName: String,
    val avatarUrl: String,
    val subscriberCount: Long,
    val videos: List<FeedItem>
)

/** Bookmarked (remote) playlist payload. */
data class RemotePlaylistPayload(
    val url: String,
    val title: String,
    val channelName: String,
    val thumbnailUrl: String,
    val videos: List<FeedItem>
)
