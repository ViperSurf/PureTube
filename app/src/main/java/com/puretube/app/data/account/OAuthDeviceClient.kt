package com.puretube.app.data.account

import com.puretube.app.domain.DeviceCodeParse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Google OAuth 2.0 device-code flow (v2.1) — the sign-in route TV
 * clients use, and the replacement for the WebView route Google blocks
 * ("This browser or app may not be secure").
 *
 * The app requests a device + user code, shows the user code in the
 * app with a "youtube.com/activate" instruction (the user can open it
 * in ANY browser — this phone's browser is fine, no computer or
 * DevTools needed), then polls the token endpoint until the user
 * approves. The REFRESH token is what gets stored (Keystore-encrypted,
 * see CookieStore); access tokens are short-lived and refreshed.
 *
 * Uses the public YouTube TV client identity — the same public client
 * id every open-source TV client ships. No secret is involved in the
 * device flow.
 */
class OAuthDeviceClient(private val okHttpClient: OkHttpClient) {

    private val json = Json { ignoreUnknownKeys = true }

    companion object {
        /** Public YouTube TV OAuth client id (device flow, no secret). */
        const val CLIENT_ID =
            "861826182401-0kou6gcktp8q0m0hgrqiif25p1oqvj9o.apps.googleusercontent.com"
        private const val DEVICE_CODE_URL = "https://oauth2.googleapis.com/device/code"
        private const val TOKEN_URL = "https://oauth2.googleapis.com/token"
        private const val SCOPE = "https://www.googleapis.com/auth/youtube"
    }

    private fun postForm(url: String, fields: Map<String, String>): Pair<Int, String> {
        val body = FormBody.Builder().apply {
            fields.forEach { (k, v) -> add(k, v) }
        }.build()
        val request = Request.Builder().url(url).post(body).build()
        okHttpClient.newCall(request).execute().use { resp ->
            return resp.code to (resp.body?.string().orEmpty())
        }
    }

    /** Step 1: request a device/user code pair to show the user. */
    suspend fun requestDeviceCode(): Result<com.puretube.app.domain.DeviceCodeGrant> =
        withContext(Dispatchers.IO) {
            runCatching {
                val (_, text) = postForm(
                    DEVICE_CODE_URL,
                    mapOf("client_id" to CLIENT_ID, "scope" to SCOPE)
                )
                DeviceCodeParse.parseDeviceCodeResponse(
                    json.parseToJsonElement(text).jsonObject
                ) ?: throw RuntimeException(
                    "Google did not return a sign-in code — check the connection and try again."
                )
            }
        }

    /** Step 2 (repeated): poll once for the user's approval. */
    suspend fun pollOnce(deviceCode: String): Result<com.puretube.app.domain.DevicePollResult> =
        withContext(Dispatchers.IO) {
            runCatching {
                val (code, text) = postForm(
                    TOKEN_URL,
                    mapOf(
                        "client_id" to CLIENT_ID,
                        "device_code" to deviceCode,
                        "grant_type" to "urn:ietf:params:oauth:grant-type:device_code"
                    )
                )
                DeviceCodeParse.parseTokenResponse(
                    json.parseToJsonElement(text).jsonObject, code
                )
            }
        }

    /** Exchanges the stored refresh token for a fresh access token. */
    suspend fun refresh(refreshToken: String): Result<com.puretube.app.domain.DevicePollResult> =
        withContext(Dispatchers.IO) {
            runCatching {
                val (code, text) = postForm(
                    TOKEN_URL,
                    mapOf(
                        "client_id" to CLIENT_ID,
                        "refresh_token" to refreshToken,
                        "grant_type" to "refresh_token"
                    )
                )
                DeviceCodeParse.parseTokenResponse(
                    json.parseToJsonElement(text).jsonObject, code
                )
            }
        }
}
