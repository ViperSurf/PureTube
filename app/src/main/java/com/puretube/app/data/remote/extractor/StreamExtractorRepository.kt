package com.puretube.app.data.remote.extractor

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import org.schabi.newpipe.extractor.Page
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.channel.ChannelInfoItem
import org.schabi.newpipe.extractor.linkhandler.SearchQueryHandler
import org.schabi.newpipe.extractor.playlist.PlaylistInfo
import org.schabi.newpipe.extractor.playlist.PlaylistInfoItem
import org.schabi.newpipe.extractor.search.SearchInfo
import org.schabi.newpipe.extractor.services.youtube.linkHandler.YoutubeSearchQueryHandlerFactory
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import com.puretube.app.domain.CommentItem
import com.puretube.app.domain.ExtractorItemMapper
import com.puretube.app.domain.RawChannelItem
import com.puretube.app.domain.RawCommentItem
import com.puretube.app.domain.RawPlaylistItem
import com.puretube.app.domain.RawStreamItem
import com.puretube.app.domain.RawThumb
import com.puretube.app.domain.ErrorText
import com.puretube.app.domain.FailoverLog
import com.puretube.app.domain.PlaylistUrl
import com.puretube.app.domain.StageResult

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
    val thumbnailUrl: String,
    /** Video description text (v1.3); empty when the source has none. */
    val description: String = "",
    /** Related / up-next videos from the same extraction (v2.0); empty when the stage has none. */
    val related: List<FeedItem> = emptyList(),
    /** Which failover stage produced this stream (v1.2 diagnostics). */
    val sourceStage: String = "",
    /** One line per stage tried, in order (v1.2 diagnostics). */
    val diagnosticsLog: String = ""
)

/** Search content filter (v1.2). */
enum class SearchTypeFilter(val factoryValue: String) {
    ALL(YoutubeSearchQueryHandlerFactory.ALL),
    VIDEOS(YoutubeSearchQueryHandlerFactory.VIDEOS),
    PLAYLISTS(YoutubeSearchQueryHandlerFactory.PLAYLISTS),
    CHANNELS(YoutubeSearchQueryHandlerFactory.CHANNELS)
}

enum class SearchHitType { VIDEO, PLAYLIST, CHANNEL }

data class ChannelHit(
    val channelId: String,
    val name: String,
    val avatarUrl: String,
    val subscriberCount: Long,
    val description: String,
    val url: String
)

data class PlaylistHit(
    val url: String,
    val title: String,
    val channelName: String,
    val thumbnailUrl: String,
    val streamCount: Long
)

/** One mixed search result (v1.2): exactly one payload field is non-null. */
data class SearchHit(
    val type: SearchHitType,
    val video: FeedItem? = null,
    val playlist: PlaylistHit? = null,
    val channel: ChannelHit? = null
) {
    val stableKey: String
        get() = when (type) {
            SearchHitType.VIDEO -> "v:${video?.videoId}"
            SearchHitType.PLAYLIST -> "p:${playlist?.url}"
            SearchHitType.CHANNEL -> "c:${channel?.channelId}"
        }
}

data class SearchPageResult(
    val hits: List<SearchHit>,
    val hasMore: Boolean
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

        /** Built-in Piped API instances tried after the user's own (v1.2). */
        val BUILTIN_PIPED_INSTANCES = listOf(
            "https://pipedapi.kavin.rocks",
            "https://pipedapi.adminforge.de",
            "https://pipedapi.reallyaweso.me",
            "https://api.piped.projectsegfau.lt",
            "https://pipedapi.leptons.xyz",
            "https://pipedapi-libre.kavin.rocks"
        )

        /** Built-in Invidious API instances (v1.2). */
        val BUILTIN_INVIDIOUS_INSTANCES = listOf(
            "https://inv.nadeko.net",
            "https://invidious.privacyredirect.com",
            "https://inv.us.projectsegfau.lt",
            "https://yewtu.be",
            "https://invidious.0011.lt"
        )
    }

    fun normalizeAndStripShortsUrl(rawUrlOrId: String): String =
        Companion.normalizeAndStripShortsUrl(rawUrlOrId)

    fun extractVideoId(rawUrlOrId: String): String = Companion.extractVideoId(rawUrlOrId)

    // ------------------------------------------------------------------
    // Stream extraction with multi-stage failover (v1.2)
    // ------------------------------------------------------------------

    /**
     * Stages, in order, each logged into the returned stream's diagnostics
     * (or into the failure message when all stages fail):
     * 1. NewPipeExtractor primary. 2. NewPipeExtractor retry (fresh extractor;
     *    first attempts can fail on a transient token/consent fetch).
     * 3. Piped: the user's configured instance, then built-in instances.
     * 4. Invidious: built-in instances.
     * No stage throws to the caller except the final aggregated failure,
     * whose message lists every stage's outcome.
     */
    suspend fun extractStreamWithFailover(
        rawUrlOrId: String,
        preferredMaxHeight: Int,
        preferAv1Vp9: Boolean,
        preferredAudioLanguageCode: String? = null
    ): Result<ResolvedVideoStream> = withContext(Dispatchers.IO) {
        val canonicalUrl = normalizeAndStripShortsUrl(rawUrlOrId)
        val videoId = extractVideoId(rawUrlOrId)
        val stages = mutableListOf<StageResult>()

        fun succeed(stage: String, stream: ResolvedVideoStream): Result<ResolvedVideoStream> {
            stages.add(StageResult(stage, true, "streams found"))
            return Result.success(
                stream.copy(
                    sourceStage = stage,
                    diagnosticsLog = FailoverLog.summary(stages)
                )
            )
        }

        // Stages 1-2: NewPipe primary + one retry.
        for (attempt in 1..2) {
            val stageName = if (attempt == 1) "NewPipe" else "NewPipe retry"
            val attemptResult = runCatching {
                extractViaNewPipe(canonicalUrl, videoId, preferredMaxHeight, preferAv1Vp9, preferredAudioLanguageCode)
            }
            attemptResult.getOrNull()?.let { return@withContext succeed(stageName, it) }
            stages.add(
                StageResult(stageName, false, ErrorText.describe(attemptResult.exceptionOrNull()))
            )
        }

        // Stage 3: Piped across instances (user's first, then built-ins).
        val pipedInstances = (listOf(pipedFallbackBaseUrlProvider()) + BUILTIN_PIPED_INSTANCES)
            .map { it.trim().trimEnd('/') }
            .filter { it.isNotBlank() }
            .distinct()
        for (instance in pipedInstances) {
            val stageName = "Piped $instance"
            val attemptResult = runCatching { extractViaPiped(instance, videoId) }
            attemptResult.getOrNull()?.let { return@withContext succeed(stageName, it) }
            stages.add(
                StageResult(stageName, false, ErrorText.describe(attemptResult.exceptionOrNull(), 200))
            )
        }

        // Stage 4: Invidious across instances.
        for (instance in BUILTIN_INVIDIOUS_INSTANCES) {
            val stageName = "Invidious $instance"
            val attemptResult = runCatching { extractViaInvidious(instance, videoId) }
            attemptResult.getOrNull()?.let { return@withContext succeed(stageName, it) }
            stages.add(
                StageResult(stageName, false, ErrorText.describe(attemptResult.exceptionOrNull(), 200))
            )
        }

        Result.failure(RuntimeException(FailoverLog.finalMessage(videoId, stages)))
    }

    private fun extractViaNewPipe(
        canonicalUrl: String,
        videoId: String,
        preferredMaxHeight: Int,
        preferAv1Vp9: Boolean,
        preferredAudioLanguageCode: String?
    ): ResolvedVideoStream {
        val info = StreamInfo.getInfo(ServiceList.YouTube, canonicalUrl)
        val audioStreams = safe { info.audioStreams }?.filterNotNull().orEmpty()
        val bestAudio = runCatching {
            val matching = preferredAudioLanguageCode?.let { code ->
                audioStreams.filter {
                    runCatching { it.audioLocale?.language == code }.getOrDefault(false)
                }
            }.orEmpty()
            (matching.maxByOrNull { it.averageBitrate }
                ?: audioStreams.maxByOrNull { it.averageBitrate })
                ?.content
        }.getOrNull() ?: audioStreams.maxByOrNull { it.averageBitrate }?.content
            ?: error("No audio stream found")

        val bestVideo = (safe { info.videoOnlyStreams }?.filterNotNull().orEmpty())
            .filter { parseHeight(it.resolution) <= preferredMaxHeight || preferredMaxHeight <= 0 }
            .sortedWith(
                compareByDescending<org.schabi.newpipe.extractor.stream.VideoStream> {
                    if (preferAv1Vp9) {
                        val name = runCatching { it.format?.name }.getOrNull().orEmpty()
                        name.contains("AV1") || name.contains("VP9")
                    } else {
                        false
                    }
                }.thenByDescending { parseHeight(it.resolution) }
            )
            .firstOrNull()?.content
            ?: (safe { info.videoStreams }?.filterNotNull()?.firstOrNull()?.content)

        val dashUrl = safe { info.dashMpdUrl }
        val hlsUrlValue = safe { info.hlsUrl }
        if (bestVideo == null && dashUrl.isNullOrBlank() && hlsUrlValue.isNullOrBlank()) {
            error("Extractor returned no playable streams")
        }

        return ResolvedVideoStream(
            videoId = videoId,
            title = safe { info.name }.orEmpty(),
            uploaderName = safe { info.uploaderName }.orEmpty(),
            uploaderId = safe { info.uploaderUrl }?.substringAfterLast("/").orEmpty(),
            uploaderAvatarUrl = imageThumbs(safe { info.uploaderAvatars })
                .maxByOrNull { it.height }?.url.orEmpty(),
            durationSeconds = safe { info.duration } ?: 0L,
            viewCount = safe { info.viewCount } ?: 0L,
            likeCount = safe { info.likeCount } ?: 0L,
            dashManifestUrl = dashUrl?.takeIf { it.isNotBlank() },
            hlsUrl = hlsUrlValue?.takeIf { it.isNotBlank() },
            videoStreamUrl = bestVideo,
            audioOnlyStreamUrl = bestAudio,
            thumbnailUrl = imageThumbs(safe { info.thumbnails })
                .maxByOrNull { it.height }?.url.orEmpty(),
            description = safe { info.description?.content }.orEmpty(),
            related = runCatching {
                safe { info.relatedStreams }?.filterNotNull().orEmpty()
                    .filterIsInstance<StreamInfoItem>().mapFeedSafe()
            }.getOrDefault(emptyList())
        )
    }

    /** Related videos for a video, straight from the extractor (v2.0 fallback path). */
    suspend fun fetchRelated(videoId: String): Result<List<FeedItem>> =
        withContext(Dispatchers.IO) {
            runCatching {
                val info = StreamInfo.getInfo(
                    ServiceList.YouTube, normalizeAndStripShortsUrl(videoId)
                )
                safe { info.relatedStreams }?.filterNotNull().orEmpty()
                    .filterIsInstance<StreamInfoItem>().mapFeedSafe()
            }
        }

    private fun extractViaPiped(baseUrl: String, videoId: String): ResolvedVideoStream {
        val req = Request.Builder().url("$baseUrl/streams/$videoId").build()
        okHttpClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) error("HTTP ${resp.code}")
            val root = Json.parseToJsonElement(resp.body?.string().orEmpty()).jsonObject
            if (root["error"] != null && root["videoStreams"] == null) {
                error("Piped error: ${root["error"]?.jsonPrimitive?.content}")
            }
            val audioStreams = root["audioStreams"]?.jsonArray.orEmpty()
            val audioUrl = audioStreams
                .maxByOrNull {
                    it.jsonObject["bitrate"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0L
                }?.jsonObject?.get("url")?.jsonPrimitive?.content
            val videoStreams = root["videoStreams"]?.jsonArray.orEmpty()
            val progressive = videoStreams.firstOrNull {
                it.jsonObject["videoOnly"]?.jsonPrimitive?.content == "false"
            } ?: videoStreams.firstOrNull()
            val videoUrl = progressive?.jsonObject?.get("url")?.jsonPrimitive?.content
            val dash = root["dash"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
            val hls = root["hls"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
            if (audioUrl == null && videoUrl == null && dash == null && hls == null) {
                error("Piped returned no streams")
            }
            return ResolvedVideoStream(
                videoId = videoId,
                title = root["title"]?.jsonPrimitive?.content.orEmpty(),
                uploaderName = root["uploader"]?.jsonPrimitive?.content.orEmpty(),
                uploaderId = root["uploaderUrl"]?.jsonPrimitive?.content?.substringAfterLast("/").orEmpty(),
                uploaderAvatarUrl = root["uploaderAvatar"]?.jsonPrimitive?.content.orEmpty(),
                durationSeconds = root["duration"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0L,
                viewCount = root["views"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0L,
                likeCount = root["likes"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0L,
                dashManifestUrl = dash,
                hlsUrl = hls,
                videoStreamUrl = videoUrl,
                audioOnlyStreamUrl = audioUrl.orEmpty(),
                thumbnailUrl = root["thumbnailUrl"]?.jsonPrimitive?.content.orEmpty(),
                description = root["description"]?.jsonPrimitive?.content.orEmpty()
            )
        }
    }

    private fun extractViaInvidious(baseUrl: String, videoId: String): ResolvedVideoStream {
        val req = Request.Builder().url("$baseUrl/api/v1/videos/$videoId").build()
        okHttpClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) error("HTTP ${resp.code}")
            val root = Json.parseToJsonElement(resp.body?.string().orEmpty()).jsonObject
            if (root["error"] != null && root["formatStreams"] == null) {
                error("Invidious error: ${root["error"]?.jsonPrimitive?.content}")
            }
            val formatStreams = root["formatStreams"]?.jsonArray.orEmpty()
            val progressive = formatStreams.firstOrNull()
                ?.jsonObject?.get("url")?.jsonPrimitive?.content
            val adaptive = root["adaptiveFormats"]?.jsonArray.orEmpty()
            val audioUrl = adaptive
                .filter {
                    it.jsonObject["type"]?.jsonPrimitive?.content?.startsWith("audio") == true
                }
                .maxByOrNull {
                    it.jsonObject["bitrate"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0L
                }?.jsonObject?.get("url")?.jsonPrimitive?.content
            val hls = root["hlsUrl"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
            val dash = root["dashUrl"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
            if (progressive == null && audioUrl == null && hls == null && dash == null) {
                error("Invidious returned no streams")
            }
            val thumbs = root["videoThumbnails"]?.jsonArray.orEmpty()
            return ResolvedVideoStream(
                videoId = videoId,
                title = root["title"]?.jsonPrimitive?.content.orEmpty(),
                uploaderName = root["author"]?.jsonPrimitive?.content.orEmpty(),
                uploaderId = root["authorId"]?.jsonPrimitive?.content.orEmpty(),
                uploaderAvatarUrl = "",
                durationSeconds = root["lengthSeconds"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0L,
                viewCount = root["viewCount"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0L,
                likeCount = root["likeCount"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0L,
                dashManifestUrl = dash,
                hlsUrl = hls,
                videoStreamUrl = progressive,
                audioOnlyStreamUrl = audioUrl.orEmpty(),
                thumbnailUrl = thumbs.lastOrNull()?.jsonObject?.get("url")?.jsonPrimitive?.content.orEmpty(),
                description = root["description"]?.jsonPrimitive?.content.orEmpty()
            )
        }
    }

    // ------------------------------------------------------------------
    // Search with types + pagination (v1.2)
    // ------------------------------------------------------------------

    @Volatile private var lastSearchHandler: SearchQueryHandler? = null
    @Volatile private var lastSearchNextPage: Page? = null

    private fun searchHandler(query: String, filter: SearchTypeFilter): SearchQueryHandler =
        ServiceList.YouTube.searchQHFactory.fromQuery(query, listOf(filter.factoryValue), "")

    /**
     * v1.3 NPE fix: EVERY extractor getter is treated as nullable. Java
     * platform getters on NewPipe InfoItems are annotated @Nonnull but can
     * return null at runtime for item shapes the typed searches return
     * (e.g. a channel result with no thumbnails list, a stream item with no
     * uploader avatars); Kotlin's intrinsic null-check on the platform type
     * then threw "NullPointerException: getOrDefault (.) must not be null"
     * (and sibling messages) and failed the WHOLE search. Now each field is
     * read into a nullable local via [safe], mapping happens on raw holders
     * in [ExtractorItemMapper], and each item is mapped inside its own
     * runCatching in mapNotNull — one bad item is skipped, never fatal.
     */
    private fun <T> safe(block: () -> T?): T? = runCatching(block).getOrNull()

    private fun imageThumbs(
        images: List<org.schabi.newpipe.extractor.Image>?
    ): List<RawThumb> = images.orEmpty().mapNotNull { img ->
        val url = safe { img.url } ?: return@mapNotNull null
        RawThumb(url = url, height = safe { img.height } ?: 0)
    }

    private fun StreamInfoItem.toRaw(): RawStreamItem = RawStreamItem(
        url = safe { url },
        name = safe { name },
        uploaderName = safe { uploaderName },
        uploaderUrl = safe { uploaderUrl },
        thumbnails = imageThumbs(safe { thumbnails }),
        uploaderAvatars = imageThumbs(safe { uploaderAvatars }),
        durationSeconds = safe { duration } ?: 0L,
        isShortForm = safe { isShortFormContent } ?: false,
        uploadEpochMs = safe { uploadDate?.offsetDateTime()?.toInstant()?.toEpochMilli() } ?: 0L
    )

    private fun ChannelInfoItem.toRaw(): RawChannelItem = RawChannelItem(
        url = safe { url },
        name = safe { name },
        thumbnails = imageThumbs(safe { thumbnails }),
        subscriberCount = safe { subscriberCount } ?: -1L,
        description = safe { description }
    )

    private fun PlaylistInfoItem.toRaw(): RawPlaylistItem = RawPlaylistItem(
        url = safe { url },
        name = safe { name },
        uploaderName = safe { uploaderName },
        thumbnails = imageThumbs(safe { thumbnails }),
        streamCount = safe { streamCount } ?: 0L
    )

    private fun org.schabi.newpipe.extractor.InfoItem.toHit(): SearchHit? = when (this) {
        is StreamInfoItem -> ExtractorItemMapper.toFeedItem(toRaw()) { extractVideoId(it) }
            ?.let { SearchHit(SearchHitType.VIDEO, video = it) }
        is ChannelInfoItem -> ExtractorItemMapper.toChannelHit(toRaw())
            ?.let { SearchHit(SearchHitType.CHANNEL, channel = it) }
        is PlaylistInfoItem -> ExtractorItemMapper.toPlaylistHit(toRaw())
            ?.let { SearchHit(SearchHitType.PLAYLIST, playlist = it) }
        else -> null
    }

    /** Per-item isolation: a throwing item maps to null and is skipped. */
    private fun <T : org.schabi.newpipe.extractor.InfoItem> List<T>.mapHitsSafe(): List<SearchHit> =
        mapNotNull { item -> runCatching { item.toHit() }.getOrNull() }

    private fun List<StreamInfoItem>.mapFeedSafe(): List<FeedItem> =
        mapNotNull { item ->
            runCatching {
                ExtractorItemMapper.toFeedItem(item.toRaw()) { extractVideoId(it) }
            }.getOrNull()
        }

    /** First page of a typed search. Remembers handler + next page for [searchMore]. */
    suspend fun search(
        query: String,
        filter: SearchTypeFilter
    ): Result<SearchPageResult> = withContext(Dispatchers.IO) {
        runCatching {
            val handler = searchHandler(query, filter)
            val info = SearchInfo.getInfo(ServiceList.YouTube, handler)
            lastSearchHandler = handler
            val hasMore = safe { info.hasNextPage() } ?: false
            lastSearchNextPage = if (hasMore) safe { info.nextPage } else null
            SearchPageResult(
                hits = safe { info.relatedItems }?.filterNotNull().orEmpty().mapHitsSafe(),
                hasMore = hasMore
            )
        }
    }

    /** Next page of the most recent [search] call. */
    suspend fun searchMore(): Result<SearchPageResult> = withContext(Dispatchers.IO) {
        runCatching {
            val handler = lastSearchHandler ?: error("No active search")
            val page = lastSearchNextPage ?: return@runCatching SearchPageResult(emptyList(), false)
            val more = SearchInfo.getMoreItems(ServiceList.YouTube, handler, page)
            lastSearchNextPage = safe { more.nextPage }
            SearchPageResult(
                hits = safe { more.items }?.filterNotNull().orEmpty().mapHitsSafe(),
                hasMore = lastSearchNextPage != null
            )
        }
    }

    /** Channel-only search, used by fuzzy Add Channel and the Channels tab. */
    suspend fun searchChannels(query: String): Result<List<ChannelHit>> =
        search(query, SearchTypeFilter.CHANNELS).map { page ->
            page.hits.mapNotNull { it.channel }
        }

    /** Legacy video-only search (first page), kept for existing callers. */
    suspend fun searchVideos(query: String): Result<List<FeedItem>> =
        search(query, SearchTypeFilter.VIDEOS).map { page ->
            page.hits.mapNotNull { it.video }
        }

    private fun StreamInfoItem.toFeedItem(): FeedItem? =
        ExtractorItemMapper.toFeedItem(toRaw()) { extractVideoId(it) }

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

    /** Channel page: name/avatar/subscribers + its videos (paginated, v1.2). Best-effort. */
    suspend fun fetchChannel(channelUrlOrId: String): Result<ChannelPayload> =
        withContext(Dispatchers.IO) {
            runCatching {
                val url = channelUrl(channelUrlOrId)
                val info = org.schabi.newpipe.extractor.channel.ChannelInfo
                    .getInfo(ServiceList.YouTube, url)
                val videos = runCatching {
                    val tab = safe { info.tabs }?.filterNotNull()?.firstOrNull()
                        ?: return@runCatching emptyList()
                    val tabInfo = org.schabi.newpipe.extractor.channel.tabs.ChannelTabInfo
                        .getInfo(ServiceList.YouTube, tab)
                    val items = safe { tabInfo.relatedItems }
                        ?.filterIsInstance<StreamInfoItem>().orEmpty()
                        .mapFeedSafe().toMutableList()
                    var page = if (safe { tabInfo.hasNextPage() } == true) {
                        safe { tabInfo.nextPage }
                    } else null
                    var pages = 0
                    while (page != null && pages < 2) {
                        val more = runCatching {
                            org.schabi.newpipe.extractor.channel.tabs.ChannelTabInfo
                                .getMoreItems(ServiceList.YouTube, tab, page)
                        }.getOrNull() ?: break
                        items += safe { more.items }
                            ?.filterIsInstance<StreamInfoItem>().orEmpty()
                            .mapFeedSafe()
                        page = safe { more.nextPage }
                        pages++
                    }
                    com.puretube.app.domain.PageMerger.dedupe(items)
                }.getOrDefault(emptyList())
                ChannelPayload(
                    channelId = safe { info.url }?.substringAfterLast("/").orEmpty(),
                    channelName = safe { info.name }.orEmpty(),
                    avatarUrl = imageThumbs(safe { info.avatars })
                        .maxByOrNull { it.height }?.url.orEmpty(),
                    subscriberCount = safe { info.subscriberCount } ?: -1L,
                    videos = videos
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

    private fun org.schabi.newpipe.extractor.comments.CommentsInfoItem.toCommentItemOrNull(): CommentItem? =
        runCatching {
            ExtractorItemMapper.toCommentItem(
                RawCommentItem(
                    commentId = safe { commentId },
                    uploaderName = safe { uploaderName },
                    uploaderUrl = safe { uploaderUrl },
                    text = safe { commentText?.content },
                    likeCount = safe { likeCount } ?: -1,
                    uploaderAvatars = imageThumbs(safe { uploaderAvatars }),
                    replyCount = safe { replyCount } ?: -1,
                    repliesPageUrl = safe { replies?.url },
                    repliesToken = safe { replies?.id }
                )
            )
        }.getOrNull()

    /** Comments for a video. Best-effort; empty when disabled/unavailable. */
    suspend fun fetchComments(videoId: String): Result<List<CommentItem>> =
        withContext(Dispatchers.IO) {
            runCatching {
                val url = normalizeAndStripShortsUrl(videoId)
                val info = org.schabi.newpipe.extractor.comments.CommentsInfo
                    .getInfo(ServiceList.YouTube, url)
                if (safe { info.isCommentsDisabled } == true) return@runCatching emptyList()
                safe { info.relatedItems }?.filterNotNull().orEmpty()
                    .mapNotNull { it.toCommentItemOrNull() }
            }
        }

    /**
     * Comment replies (v1.4), using the real NewPipeExtractor 0.27.1 API:
     * CommentsInfoItem.getReplyCount() says how many replies exist and
     * CommentsInfoItem.getReplies() returns the continuation
     * org.schabi.newpipe.extractor.Page (for YouTube: Page(url = video
     * url, id = continuation token)). Replies are fetched with
     * CommentsInfo.getMoreItems(service, url, repliesPage), which routes
     * to CommentsExtractor.getPage(page). The CommentItem carries the
     * page's url+token so this call can rebuild the Page exactly.
     */
    suspend fun fetchCommentReplies(comment: CommentItem): Result<List<CommentItem>> =
        withContext(Dispatchers.IO) {
            runCatching {
                if (!comment.hasReplies) error("This comment has no replies page")
                val page = Page(comment.repliesPageUrl, comment.repliesToken)
                val itemsPage = org.schabi.newpipe.extractor.comments.CommentsInfo
                    .getMoreItems(ServiceList.YouTube, comment.repliesPageUrl, page)
                safe { itemsPage.items }?.filterNotNull().orEmpty()
                    .mapNotNull { it.toCommentItemOrNull() }
            }
        }

    /**
     * Remote playlist contents for bookmarked playlists (v1.2 rewrite).
     * The URL is canonicalised to youtube.com/playlist?list=<id> first —
     * passing a watch URL with a list parameter made the old extractor path
     * return the playlist name with an empty first page. Pages are followed
     * (up to [maxPages]) and results deduped. Extractor-collected errors are
     * surfaced as a warning instead of being swallowed when the video list
     * comes back empty.
     */
    suspend fun fetchRemotePlaylist(
        url: String,
        maxPages: Int = 10
    ): Result<RemotePlaylistPayload> = withContext(Dispatchers.IO) {
        runCatching {
            val canonical = PlaylistUrl.canonical(url)
            val info = PlaylistInfo.getInfo(ServiceList.YouTube, canonical)
            val items = safe { info.relatedItems }?.filterNotNull().orEmpty()
                .filterIsInstance<StreamInfoItem>().mapFeedSafe().toMutableList()
            var page: Page? = if (safe { info.hasNextPage() } == true) safe { info.nextPage } else null
            var fetched = 0
            while (page != null && fetched < maxPages) {
                val more = runCatching {
                    PlaylistInfo.getMoreItems(ServiceList.YouTube, canonical, page)
                }.getOrNull() ?: break
                items += safe { more.items }?.filterNotNull().orEmpty()
                    .filterIsInstance<StreamInfoItem>().mapFeedSafe()
                page = safe { more.nextPage }
                fetched++
            }
            val videos = com.puretube.app.domain.PageMerger.dedupe(items)
            val infoErrors = safe { info.errors }?.filterNotNull().orEmpty()
            val warning = when {
                videos.isEmpty() && infoErrors.isNotEmpty() ->
                    "Extractor reported: " + infoErrors.joinToString("; ") { ErrorText.describe(it, 160) }
                videos.isEmpty() ->
                    "The extractor returned no videos for this playlist. It may be private, a mix, or an unsupported link type."
                else -> null
            }
            RemotePlaylistPayload(
                url = canonical,
                title = safe { info.name }.orEmpty(),
                channelName = safe { info.uploaderName }.orEmpty(),
                thumbnailUrl = imageThumbs(safe { info.thumbnails })
                    .maxByOrNull { it.height }?.url.orEmpty(),
                videos = videos,
                streamCount = safe { info.streamCount } ?: videos.size.toLong(),
                warning = warning
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
    val videos: List<FeedItem>,
    val streamCount: Long = 0,
    val warning: String? = null
)
