package com.puretube.app.data.account

import com.puretube.app.data.remote.innertube.InnertubeClient
import com.puretube.app.domain.CookieHeader
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** UI-facing account state. Signed out is the default and the fallback. */
data class AccountState(
    val signedIn: Boolean = false,
    val name: String = "",
    val avatarUrl: String = "",
    val handle: String = ""
)

/**
 * Owns the signed-in state (v2.0). The cookie header itself never
 * leaves [CookieStore] except through [cookieHeader] for the
 * Innertube client's provider — and from there only into request
 * headers to www.youtube.com.
 */
class AccountRepository(
    private val cookieStore: CookieStore,
    private val innertubeClient: InnertubeClient,
    private val oauthClient: OAuthDeviceClient? = null
) {
    private val _state = MutableStateFlow(AccountState())
    val state: StateFlow<AccountState> = _state

    init {
        cookieStore.load()?.let { stored ->
            _state.value = AccountState(
                signedIn = true,
                name = stored.name,
                avatarUrl = stored.avatarUrl,
                handle = stored.handle
            )
        }
    }

    val isSignedIn: Boolean get() = _state.value.signedIn

    /** Current cookie header for authenticated requests, or null. */
    fun cookieHeader(): String? =
        if (_state.value.signedIn) cookieStore.load()?.cookieHeader else null

    /**
     * Validates a pasted (or WebView-captured) cookie header against
     * YouTube, then persists it encrypted. Nothing is stored when
     * validation fails.
     */
    suspend fun signInWithCookies(cookieHeader: String): Result<AccountState> {
        val trimmed = cookieHeader.trim()
        if (!CookieHeader.isUsable(trimmed)) {
            return Result.failure(
                IllegalArgumentException(
                    "That text has no SAPISID cookie — paste the full Cookie header."
                )
            )
        }
        if (!cookieStore.isAvailable) {
            return Result.failure(
                IllegalStateException(
                    "Encrypted storage is unavailable on this device, so the session cannot be stored safely."
                )
            )
        }
        return innertubeClient.whoami(trimmed).map { account ->
            cookieStore.save(
                StoredAccount(
                    cookieHeader = trimmed,
                    name = account.name,
                    avatarUrl = account.avatarUrl,
                    handle = account.handle
                )
            )
            val next = AccountState(
                signedIn = true,
                name = account.name,
                avatarUrl = account.avatarUrl,
                handle = account.handle
            )
            _state.value = next
            next
        }
    }

    /** Wipes the stored session completely. */
    fun signOut() {
        cookieStore.clear()
        _state.value = AccountState()
    }

    // ------------------------------------------------------------------
    // v2.1: device-code sign-in (no computer, no DevTools, no WebView)
    // ------------------------------------------------------------------

    /** Step 1: ask Google for a user code to show in the app. */
    suspend fun beginDeviceSignIn():
        Result<com.puretube.app.domain.DeviceCodeParse.DeviceCode> {
        val client = oauthClient ?: return Result.failure(
            IllegalStateException("Device sign-in is unavailable.")
        )
        return client.requestDeviceCode()
    }

    /**
     * Step 2: one poll. On approval the token is validated against
     * YouTube (account_menu with Bearer), the REFRESH token is stored
     * encrypted, and the account state flips to signed in. All other
     * outcomes pass through for the UI's waiting/denied/expired states.
     */
    suspend fun pollDeviceSignIn(
        code: com.puretube.app.domain.DeviceCodeParse.DeviceCode
    ): Result<com.puretube.app.domain.DeviceCodeParse.TokenOutcome> {
        val client = oauthClient ?: return Result.failure(
            IllegalStateException("Device sign-in is unavailable.")
        )
        val outcome = client.pollOnce(code.deviceCode)
            .getOrElse { return Result.failure(it) }
        if (outcome is com.puretube.app.domain.DeviceCodeParse.TokenOutcome.Approved) {
            if (!cookieStore.isAvailable) {
                return Result.failure(
                    IllegalStateException(
                        "Encrypted storage is unavailable on this device, so the session cannot be stored safely."
                    )
                )
            }
            val account = innertubeClient.whoamiWithToken(outcome.accessToken)
                .getOrElse { return Result.failure(it) }
            cookieStore.save(
                StoredAccount(
                    cookieHeader = "",
                    name = account.name,
                    avatarUrl = account.avatarUrl,
                    handle = account.handle,
                    refreshToken = outcome.refreshToken.orEmpty(),
                    accessToken = outcome.accessToken,
                    accessTokenExpiresAtMs = outcome.expiresAtMs
                )
            )
            _state.value = AccountState(
                signedIn = true,
                name = account.name,
                avatarUrl = account.avatarUrl,
                handle = account.handle
            )
        }
        return Result.success(outcome)
    }

    /**
     * A currently-valid OAuth access token for the Innertube client,
     * refreshing from the stored refresh token when the cached one is
     * expired (or about to be). Null when signed out / cookie-only.
     */
    suspend fun freshAccessToken(): String? {
        val stored = cookieStore.load() ?: return null
        if (stored.accessToken.isNotBlank() &&
            stored.accessTokenExpiresAtMs > System.currentTimeMillis() + 30_000L
        ) {
            return stored.accessToken
        }
        if (stored.refreshToken.isBlank()) return null
        val client = oauthClient ?: return null
        val outcome = client.refresh(stored.refreshToken).getOrNull()
        if (outcome is com.puretube.app.domain.DeviceCodeParse.TokenOutcome.Approved) {
            cookieStore.save(
                stored.copy(
                    accessToken = outcome.accessToken,
                    accessTokenExpiresAtMs = outcome.expiresAtMs,
                    refreshToken = outcome.refreshToken ?: stored.refreshToken
                )
            )
            return outcome.accessToken
        }
        return null
    }
}
