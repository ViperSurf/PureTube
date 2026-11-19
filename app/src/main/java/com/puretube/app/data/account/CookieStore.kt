package com.puretube.app.data.account

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/** The persisted account session. The cookie header is a credential. */
data class StoredAccount(
    val cookieHeader: String,
    val name: String,
    val avatarUrl: String,
    val handle: String
)

/**
 * Encrypted storage for the YouTube session cookies (v2.0).
 *
 * The cookie header is a live session credential, so it lives ONLY in
 * an Android Keystore-backed EncryptedSharedPreferences file
 * (AES256-GCM values, AES256-SIV keys). It is never written to Room,
 * never to the DataStore preferences, never included in settings
 * export/import, and never logged. If the encrypted store cannot be
 * opened (broken keystore), sign-in reports an error instead of
 * falling back to plaintext — there is deliberately no plaintext path.
 */
class CookieStore(context: Context) {

    private val prefs: SharedPreferences? = runCatching {
        val masterKey = MasterKey.Builder(context.applicationContext)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context.applicationContext,
            FILE_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }.getOrNull()

    val isAvailable: Boolean get() = prefs != null

    fun save(account: StoredAccount) {
        val p = prefs ?: return
        runCatching {
            p.edit()
                .putString(KEY_COOKIE, account.cookieHeader)
                .putString(KEY_NAME, account.name)
                .putString(KEY_AVATAR, account.avatarUrl)
                .putString(KEY_HANDLE, account.handle)
                .apply()
        }
    }

    fun load(): StoredAccount? {
        val p = prefs ?: return null
        return runCatching {
            val cookie = p.getString(KEY_COOKIE, null)?.takeIf { it.isNotBlank() }
                ?: return@runCatching null
            StoredAccount(
                cookieHeader = cookie,
                name = p.getString(KEY_NAME, "").orEmpty(),
                avatarUrl = p.getString(KEY_AVATAR, "").orEmpty(),
                handle = p.getString(KEY_HANDLE, "").orEmpty()
            )
        }.getOrNull()
    }

    fun clear() {
        runCatching { prefs?.edit()?.clear()?.apply() }
    }

    companion object {
        private const val FILE_NAME = "puretube_account_secure"
        private const val KEY_COOKIE = "cookie_header"
        private const val KEY_NAME = "account_name"
        private const val KEY_AVATAR = "account_avatar"
        private const val KEY_HANDLE = "account_handle"
    }
}
