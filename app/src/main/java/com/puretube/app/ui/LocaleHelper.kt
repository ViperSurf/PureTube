package com.puretube.app.ui

import android.app.LocaleManager
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import com.puretube.app.data.local.datastore.AppLanguage
import java.util.Locale

/**
 * App-language application (v2.1). Before v2.1 the language preference
 * was stored and NEVER applied — changing it did nothing. Now:
 *
 * - API 33+: the framework per-app locale service
 *   ([LocaleManager.setApplicationLocales]) — the system persists the
 *   choice and recreates activities with the new locale.
 * - Older APIs: the chosen tag is mirrored to SharedPreferences;
 *   [MainActivity.attachBaseContext] wraps the base context with a
 *   configuration carrying that locale, and the settings row calls
 *   `Activity.recreate()` so the change is visible immediately.
 *
 * Core UI strings live in the res values folders (en/ar/es/fr/tr/ur);
 * long-tail strings stay English (see README).
 */
object LocaleHelper {

    private const val PREFS_NAME = "puretube_locale"
    private const val KEY_TAG = "tag"

    /** BCP-47 tag for a language choice; null = follow the system. */
    fun tagFor(language: AppLanguage): String? = when (language) {
        AppLanguage.SYSTEM -> null
        AppLanguage.ENGLISH -> "en"
        AppLanguage.ARABIC -> "ar"
        AppLanguage.SPANISH -> "es"
        AppLanguage.FRENCH -> "fr"
        AppLanguage.TURKISH -> "tr"
        AppLanguage.URDU -> "ur"
        // German remains in the stored enum for compatibility but is no
        // longer offered (no German string resources ship).
        AppLanguage.GERMAN -> "en"
    }

    /** Applies the language NOW and persists the legacy mirror. */
    fun apply(context: Context, language: AppLanguage) {
        val tag = tagFor(language)
        runCatching {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit().putString(KEY_TAG, tag ?: "").apply()
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            runCatching {
                val manager = context.getSystemService(LocaleManager::class.java)
                manager?.applicationLocales =
                    if (tag == null) {
                        LocaleList.getEmptyLocaleList()
                    } else {
                        LocaleList.forLanguageTags(tag)
                    }
            }
        }
    }

    /** Legacy wrap used by attachBaseContext on pre-33 devices. */
    fun wrap(base: Context): Context {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return base
        val tag = runCatching {
            base.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getString(KEY_TAG, null)?.takeIf { it.isNotBlank() }
        }.getOrNull() ?: return base
        val locale = Locale.forLanguageTag(tag)
        Locale.setDefault(locale)
        val config = Configuration(base.resources.configuration)
        config.setLocale(locale)
        config.setLocales(LocaleList(locale))
        return base.createConfigurationContext(config)
    }
}
