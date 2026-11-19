package com.puretube.app.data.account

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** The persisted account session. The cookie header is a credential. */
data class StoredAccount(
    val cookieHeader: String,
    val name: String,
    val avatarUrl: String,
    val handle: String,
    /** v2.1 device-code OAuth: refresh token (long-lived credential). */
    val refreshToken: String = "",
    /** v2.1: current OAuth access token + expiry (short-lived). */
    val accessToken: String = "",
    val accessTokenExpiresAtMs: Long = 0L
)

/**
 * Encrypted storage for the YouTube session cookies (v2.0).
 *
 * The cookie header is a live session credential, so it is stored ONLY
 * as AES-256-GCM ciphertext whose key lives in the Android Keystore
 * (created on first use, non-exportable). The preferences file holds
 * nothing but ciphertext — no plaintext cookie ever touches disk. The
 * header is never written to Room, never to the DataStore preferences,
 * never included in settings export/import, and never logged.
 *
 * (Implemented directly on the Keystore instead of pulling in an
 * alpha-stage crypto library dependency.) If the Keystore is broken,
 * [isAvailable] is false and sign-in reports an error instead of
 * falling back to plaintext — there is deliberately no plaintext path.
 */
class CookieStore(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    private val key: SecretKey? = runCatching { getOrCreateKey() }.getOrNull()

    val isAvailable: Boolean get() = key != null

    fun save(account: StoredAccount) {
        val k = key ?: return
        runCatching {
            prefs.edit()
                .putString(KEY_COOKIE, encrypt(k, account.cookieHeader))
                .putString(KEY_NAME, encrypt(k, account.name))
                .putString(KEY_AVATAR, encrypt(k, account.avatarUrl))
                .putString(KEY_HANDLE, encrypt(k, account.handle))
                .putString(KEY_REFRESH, encrypt(k, account.refreshToken))
                .putString(KEY_ACCESS, encrypt(k, account.accessToken))
                .putString(
                    KEY_ACCESS_EXP,
                    encrypt(k, account.accessTokenExpiresAtMs.toString())
                )
                .apply()
        }
    }

    fun load(): StoredAccount? {
        val k = key ?: return null
        return runCatching {
            val cookie = decrypt(k, prefs.getString(KEY_COOKIE, null)).orEmpty()
            val refresh = decrypt(k, prefs.getString(KEY_REFRESH, null)).orEmpty()
            val access = decrypt(k, prefs.getString(KEY_ACCESS, null)).orEmpty()
            // An account exists with a cookie session (v2.0) or any OAuth
            // token from the v2.1 device-code sign-in.
            if (cookie.isBlank() && refresh.isBlank() && access.isBlank()) {
                return@runCatching null
            }
            StoredAccount(
                cookieHeader = cookie,
                name = decrypt(k, prefs.getString(KEY_NAME, null)).orEmpty(),
                avatarUrl = decrypt(k, prefs.getString(KEY_AVATAR, null)).orEmpty(),
                handle = decrypt(k, prefs.getString(KEY_HANDLE, null)).orEmpty(),
                refreshToken = refresh,
                accessToken = access,
                accessTokenExpiresAtMs = decrypt(k, prefs.getString(KEY_ACCESS_EXP, null))
                    ?.toLongOrNull() ?: 0L
            )
        }.getOrNull()
    }

    fun clear() {
        runCatching { prefs.edit().clear().apply() }
    }

    // ---- Android Keystore AES-256-GCM ----

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let {
            return it.secretKey
        }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }

    /** AES-GCM encrypt; output is Base64(iv || ciphertext||tag). */
    private fun encrypt(key: SecretKey, plaintext: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val iv = cipher.iv
        val body = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(iv + body, Base64.NO_WRAP)
    }

    private fun decrypt(key: SecretKey, stored: String?): String? {
        if (stored.isNullOrBlank()) return null
        return runCatching {
            val raw = Base64.decode(stored, Base64.NO_WRAP)
            if (raw.size <= GCM_IV_BYTES) return@runCatching null
            val iv = raw.copyOfRange(0, GCM_IV_BYTES)
            val body = raw.copyOfRange(GCM_IV_BYTES, raw.size)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
            String(cipher.doFinal(body), Charsets.UTF_8)
        }.getOrNull()
    }

    companion object {
        private const val FILE_NAME = "puretube_account_secure"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val KEY_ALIAS = "puretube_account_key"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_IV_BYTES = 12
        private const val GCM_TAG_BITS = 128
        private const val KEY_COOKIE = "cookie_header"
        private const val KEY_NAME = "account_name"
        private const val KEY_AVATAR = "account_avatar"
        private const val KEY_HANDLE = "account_handle"
        private const val KEY_REFRESH = "oauth_refresh_token"
        private const val KEY_ACCESS = "oauth_access_token"
        private const val KEY_ACCESS_EXP = "oauth_access_expiry"
    }
}
