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
                        val name = runCatching { it.format?.name }.getOrNull().orEmpty()
                        name.contains("AV1") || name.contains("VP9")
                    } else {
                        false
                    }
                }.thenByDescending { parseHeight(it.resolution) }
            )
            .firstOrNull()?.content
            ?: info.videoStreams.firstOrNull()?.content

        if (bestVideo == null && info.dashMpdUrl.isNullOrBlank() && info.hlsUrl.isNullOrBlank()) {
            error("Extractor returned no playable streams")
        }

        return ResolvedVideoStream(
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
                thumbnailUrl = root["thumbnailUrl"]?.jsonPrimitive?.content.orEmpty()
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
                thumbnailUrl = thumbs.lastOrNull()?.jsonObject?.get("url")?.jsonPrimitive?.content.orEmpty()
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

    private fun org.schabi.newpipe.extractor.InfoItem.toHit(): SearchHit? = when (this) {
        is StreamInfoItem -> SearchHit(SearchHitType.VIDEO, video = toFeedItem())
        is ChannelInfoItem -> SearchHit(
            SearchHitType.CHANNEL,
            channel = ChannelHit(
                channelId = url.substringAfterLast("/"),
                name = name,
                avatarUrl = thumbnails.maxByOrNull { it.height }?.url.orEmpty(),
                subscriberCount = runCatching { subscriberCount }.getOrDefault(-1L),
                description = runCatching { description }.getOrDefault(""),
                url = url
            )
        )
        is PlaylistInfoItem -> SearchHit(
            SearchHitType.PLAYLIST,
            playlist = PlaylistHit(
                url = url,
                title = name,
                channelName = runCatching { uploaderName }.getOrDefault(""),
                thumbnailUrl = thumbnails.maxByOrNull { it.height }?.url.orEmpty(),
                streamCount = runCatching { streamCount }.getOrDefault(0L)
            )
        )
        else -> null
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
            lastSearchNextPage = if (info.hasNextPage()) info.nextPage else null
            SearchPageResult(
                hits = info.relatedItems.mapNotNull { it.toHit() },
                hasMore = info.hasNextPage()
            )
        }
    }

    /** Next page of the most recent [search] call. */
    suspend fun searchMore(): Result<SearchPageResult> = withContext(Dispatchers.IO) {
        runCatching {
            val handler = lastSearchHandler ?: error("No active search")
            val page = lastSearchNextPage ?: return@runCatching SearchPageResult(emptyList(), false)
            val more = SearchInfo.getMoreItems(ServiceList.YouTube, handler, page)
            lastSearchNextPage = more.nextPage
            SearchPageResult(
                hits = more.items.mapNotNull { it.toHit() },
                hasMore = more.nextPage != null
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
        // Extractor 0.27 no longer exposes a per-item like count; -1 = unknown.
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

    /** Channel page: name/avatar/subscribers + its videos (paginated, v1.2). Best-effort. */
    suspend fun fetchChannel(channelUrlOrId: String): Result<ChannelPayload> =
        withContext(Dispatchers.IO) {
            runCatching {
                val url = channelUrl(channelUrlOrId)
                val info = org.schabi.newpipe.extractor.channel.ChannelInfo
                    .getInfo(ServiceList.YouTube, url)
                val videos = runCatching {
                    val tab = info.tabs.firstOrNull() ?: return@runCatching emptyList()
                    val tabInfo = org.schabi.newpipe.extractor.channel.tabs.ChannelTabInfo
                        .getInfo(ServiceList.YouTube, tab)
                    val items = tabInfo.relatedItems.filterIsInstance<StreamInfoItem>()
                        .map { it.toFeedItem() }.toMutableList()
                    var page = if (tabInfo.hasNextPage()) tabInfo.nextPage else null
                    var pages = 0
                    while (page != null && pages < 2) {
                        val more = runCatching {
                            org.schabi.newpipe.extractor.channel.tabs.ChannelTabInfo
                                .getMoreItems(ServiceList.YouTube, tab, page)
                        }.getOrNull() ?: break
                        items += more.items.filterIsInstance<StreamInfoItem>()
                            .map { it.toFeedItem() }
                        page = more.nextPage
                        pages++
                    }
                    com.puretube.app.domain.PageMerger.dedupe(items)
                }.getOrDefault(emptyList())
                ChannelPayload(
                    channelId = info.url.substringAfterLast("/"),
                    channelName = info.name,
                    avatarUrl = info.avatars.maxByOrNull { it.height }?.url.orEmpty(),
                    subscriberCount = runCatching { info.subscriberCount }.getOrDefault(-1L),
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
            val items = info.relatedItems.map { it.toFeedItem() }.toMutableList()
            var page: Page? = if (info.hasNextPage()) info.nextPage else null
            var fetched = 0
            while (page != null && fetched < maxPages) {
                val more = runCatching {
                    PlaylistInfo.getMoreItems(ServiceList.YouTube, canonical, page)
                }.getOrNull() ?: break
                items += more.items.map { it.toFeedItem() }
                page = more.nextPage
                fetched++
            }
            val videos = com.puretube.app.domain.PageMerger.dedupe(items)
            val warning = when {
                videos.isEmpty() && info.errors.isNotEmpty() ->
                    "Extractor reported: " + info.errors.joinToString("; ") { ErrorText.describe(it, 160) }
                videos.isEmpty() ->
                    "The extractor returned no videos for this playlist. It may be private, a mix, or an unsupported link type."
                else -> null
            }
            RemotePlaylistPayload(
                url = canonical,
                title = info.name,
                channelName = runCatching { info.uploaderName }.getOrDefault(""),
                thumbnailUrl = info.thumbnails.maxByOrNull { it.height }?.url.orEmpty(),
                videos = videos,
                streamCount = runCatching { info.streamCount }.getOrDefault(videos.size.toLong()),
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
