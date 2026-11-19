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
    private val innertubeClient: InnertubeClient
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
}
