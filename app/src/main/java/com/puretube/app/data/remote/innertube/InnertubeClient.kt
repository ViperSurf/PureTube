package com.puretube.app.data.remote.innertube

import com.puretube.app.domain.CookieHeader
import com.puretube.app.domain.InnertubeParse
import com.puretube.app.domain.ParsedAccount
import com.puretube.app.domain.ParsedVideo
import com.puretube.app.domain.SapisidHash
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/** Thrown on HTTP 401/403 from an authenticated call: the session is dead. */
class SessionExpiredException(message: String = "Session expired — sign in again") :
    Exception(message)

/**
 * Minimal authenticated Innertube client (v2.0) — the standard
 * third-party-client pattern: the user's own YouTube session cookies
 * plus a SAPISIDHASH Authorization header against youtubei/v1.
 * NewPipeExtractor has no write support, so like/subscribe/comment and
 * the account browses (history, playlists, subscriptions) go through
 * here. Signed-out calls are never made: every method fails fast when
 * no usable cookie header is available.
 *
 * The cookie header is a session credential. It is only ever read from
 * the provider (backed by encrypted storage), is sent only to
 * www.youtube.com, and is never logged.
 */
class InnertubeClient(
    private val okHttpClient: OkHttpClient,
    private val cookieProvider: () -> String?
) {
    private val json = Json { ignoreUnknownKeys = true }

    companion object {
        private const val API_KEY = "AIzaSyAO_FJ2SlqU8Q4STEHLGCilw_Y9_11qcW8"
        private const val BASE_URL = "https://www.youtube.com/youtubei/v1/"
        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"
    }

    private fun contextJson(): JsonObject = buildJsonObject {
        putJsonObject("context") {
            putJsonObject("client") {
                put("clientName", "WEB")
                put("clientVersion", "2.20241126.01.00")
                put("hl", "en")
                put("gl", "US")
                put("userAgent", USER_AGENT)
            }
        }
    }

    private fun mergedContext(extra: JsonObject): JsonObject {
        val base = contextJson()
        return JsonObject(base + extra)
    }

    /** POSTs to an Innertube endpoint with the given cookie header. Throws on failure. */
    private fun postRaw(endpoint: String, body: JsonObject, cookieHeader: String): JsonElement {
        val sapisid = CookieHeader.sapisid(cookieHeader)
            ?: throw SessionExpiredException()
        val timestamp = System.currentTimeMillis() / 1000
        val request = Request.Builder()
            .url("$BASE_URL$endpoint?key=$API_KEY&prettyPrint=false")
            .header("Content-Type", "application/json")
            .header("User-Agent", USER_AGENT)
            .header("Origin", SapisidHash.ORIGIN)
            .header("Referer", "https://www.youtube.com/")
            .header("X-Origin", SapisidHash.ORIGIN)
            .header("Cookie", cookieHeader)
            .header("Authorization", SapisidHash.authorizationHeader(sapisid, timestamp))
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        okHttpClient.newCall(request).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (resp.code == 401 || resp.code == 403) throw SessionExpiredException()
            if (!resp.isSuccessful) {
                throw RuntimeException("YouTube request failed (HTTP ${resp.code})")
            }
            if (text.isBlank()) return JsonObject(emptyMap())
            return runCatching { json.parseToJsonElement(text) }
                .getOrElse { JsonObject(emptyMap()) }
        }
    }

    private suspend fun post(endpoint: String, body: JsonObject): Result<JsonElement> =
        withContext(Dispatchers.IO) {
            runCatching {
                val header = cookieProvider()?.takeIf { CookieHeader.isUsable(it) }
                    ?: throw SessionExpiredException("Not signed in")
                postRaw(endpoint, body, header)
            }
        }

    // ------------------------------------------------------------------
    // Identity
    // ------------------------------------------------------------------

    /** Validates an arbitrary cookie header and reads the account identity. */
    suspend fun whoami(cookieHeader: String): Result<ParsedAccount> =
        withContext(Dispatchers.IO) {
            runCatching {
                if (!CookieHeader.isUsable(cookieHeader)) {
                    throw IllegalArgumentException(
                        "That cookie text has no SAPISID cookie — copy the full Cookie header."
                    )
                }
                val root = postRaw("account/account_menu", contextJson(), cookieHeader)
                InnertubeParse.parseAccount(root)
                    ?: throw RuntimeException(
                        "Signed in, but the account name could not be read — the cookies may be incomplete."
                    )
            }
        }

    // ------------------------------------------------------------------
    // Like / dislike
    // ------------------------------------------------------------------

    private fun targetBody(videoId: String): JsonObject = mergedContext(buildJsonObject {
        putJsonObject("target") { put("videoId", videoId) }
    })

    suspend fun like(videoId: String): Result<Unit> =
        post("like/like", targetBody(videoId)).map { }

    suspend fun dislike(videoId: String): Result<Unit> =
        post("like/dislike", targetBody(videoId)).map { }

    suspend fun removeLike(videoId: String): Result<Unit> =
        post("like/removelike", targetBody(videoId)).map { }

    // ------------------------------------------------------------------
    // Subscribe
    // ------------------------------------------------------------------

    private fun channelBody(channelId: String): JsonObject = mergedContext(buildJsonObject {
        putJsonArray("channelIds") { add(kotlinx.serialization.json.JsonPrimitive(channelId)) }
    })

    suspend fun subscribe(channelId: String): Result<Unit> =
        post("subscribe/subscribe", channelBody(channelId)).map { }

    suspend fun unsubscribe(channelId: String): Result<Unit> =
        post("subscribe/unsubscribe", channelBody(channelId)).map { }

    // ------------------------------------------------------------------
    // Watch next (related) + comments
    // ------------------------------------------------------------------

    /** Authenticated watch-next: the related videos YouTube itself returns. */
    suspend fun nextRelated(videoId: String): Result<List<ParsedVideo>> {
        val body = mergedContext(buildJsonObject { put("videoId", videoId) })
        return post("next", body).map { InnertubeParse.parseVideos(it) }
    }

    /**
     * Posts a comment on [videoId]. The createCommentParams token only
     * exists inside the comments continuation response, so this first
     * opens the watch page, follows the comments continuation, digs the
     * token out, then posts. Fails with a visible message when the token
     * cannot be found (comments disabled / layout changed).
     */
    suspend fun createComment(videoId: String, text: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                val header = cookieProvider()?.takeIf { CookieHeader.isUsable(it) }
                    ?: throw SessionExpiredException("Not signed in")
                val watchBody = mergedContext(buildJsonObject { put("videoId", videoId) })
                val watch = postRaw("next", watchBody, header)
                val continuation = InnertubeParse.findCommentsContinuation(watch)
                    ?: throw RuntimeException("Comments are unavailable for this video.")
                val contBody = mergedContext(buildJsonObject { put("continuation", continuation) })
                val comments = postRaw("next", contBody, header)
                val params = InnertubeParse.findCreateCommentParams(comments)
                    ?: throw RuntimeException("Comment posting is unavailable for this video.")
                val createBody = mergedContext(buildJsonObject {
                    put("createCommentParams", params)
                    put("commentText", text)
                })
                postRaw("comment/create", createBody, header)
                Unit
            }
        }

    // ------------------------------------------------------------------
    // Browses for sync
    // ------------------------------------------------------------------

    suspend fun browse(browseId: String): Result<JsonElement> {
        val body = mergedContext(buildJsonObject { put("browseId", browseId) })
        return post("browse", body)
    }

    suspend fun guide(): Result<JsonElement> = post("guide", contextJson())
}
