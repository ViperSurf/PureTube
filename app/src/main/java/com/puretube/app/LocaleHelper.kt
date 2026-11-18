package com.puretube.app

import android.content.Context
import android.content.res.Configuration
import com.puretube.app.playback.PlaybackService
import java.util.Locale

/**
 * Best-effort app language: the preference is mirrored synchronously into
 * SharedPreferences so attachBaseContext can apply it before any UI exists.
 * Changing the language applies on the next app restart.
 */
object LocaleHelper {

    fun languageCode(context: Context): String = runCatching {
        context.getSharedPreferences(PlaybackService.MIRROR_PREFS, Context.MODE_PRIVATE)
            .getString(PlaybackService.KEY_APP_LANGUAGE, "SYSTEM") ?: "SYSTEM"
    }.getOrDefault("SYSTEM")

    fun wrap(base: Context): Context {
        val code = languageCode(base)
        if (code == "SYSTEM" || code.isBlank()) return base
        val locale = Locale.forLanguageTag(code)
        Locale.setDefault(locale)
        val config = Configuration(base.resources.configuration)
        config.setLocale(locale)
        return base.createConfigurationContext(config)
    }
}
