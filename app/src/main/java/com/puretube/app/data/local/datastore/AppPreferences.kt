package com.puretube.app.data.local.datastore

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

enum class ProtectionScope { OFF, IN_APP_ONLY, SYSTEM_WIDE_ONLY, BOTH }
enum class BlurTargetGender { FEMALE_ONLY, MALE_ONLY, BOTH_ALL_PEOPLE }
enum class ProtectedMediaType { IMAGES_ONLY, VIDEOS_ONLY, BOTH_IMAGES_AND_VIDEOS }
enum class BlurVisualStyle { FROSTED_GAUSSIAN, PIXELATE_MOSAIC, SOLID_DIM_CURTAIN }
enum class DefaultStartTab { SUBSCRIPTIONS, SEARCH, LIBRARY }

data class AppPreferencesState(
    // Section 1: HaramBlur Protection
    val protectionScope: ProtectionScope = ProtectionScope.IN_APP_ONLY,
    val blurTargetGender: BlurTargetGender = BlurTargetGender.FEMALE_ONLY,
    val protectedMediaType: ProtectedMediaType = ProtectedMediaType.BOTH_IMAGES_AND_VIDEOS,
    val blurVisualStyle: BlurVisualStyle = BlurVisualStyle.FROSTED_GAUSSIAN,
    val detectionSensitivity: Float = 0.65f,
    val blurIntensityRadius: Float = 28f,
    val zeroFlashSafePreBlur: Boolean = true,
    val flagSecureAppSwitcherMask: Boolean = true,
    val systemShieldExcludedPackages: Set<String> = setOf("com.android.settings", "com.android.calculator2"),
    // Section 2: Playback, PiP, Casting & Audio
    val autoEnterPipOnHome: Boolean = true,
    val backgroundPlaybackEnabled: Boolean = true,
    val defaultWifiQualityP: Int = 1080,
    val defaultMobileQualityP: Int = 480,
    val preferAv1Vp9HardwareCodec: Boolean = true,
    val defaultPlaybackSpeed: Float = 1.0f,
    val doubleTapSeekSeconds: Int = 10,
    val pocketVolumeLongPressSkip: Boolean = true,
    val audioNormalizationEnabled: Boolean = false,
    val monoAudioMergeEnabled: Boolean = false,
    val keepScreenDimmedAwakeLock: Boolean = false,
    val audioOnlyMode: Boolean = false,
    // Section 3: Focus, Shorts & Feed Filters
    val stripShortsEverywhere: Boolean = true,
    val defaultStartTab: DefaultStartTab = DefaultStartTab.SUBSCRIPTIONS,
    val collapseCommentsByDefault: Boolean = true,
    val collapseRelatedByDefault: Boolean = true,
    val showUnwatchedOnlyInSubs: Boolean = false,
    val nightOledDimmerAlpha: Float = 0.0f,
    // Section 4: SponsorBlock, DeArrow & RYD
    val sponsorBlockEnabled: Boolean = true,
    val sponsorCategories: Set<String> = setOf("sponsor", "selfpromo", "intro", "outro", "interaction"),
    val deArrowTitlesEnabled: Boolean = false,
    val deArrowThumbnailsEnabled: Boolean = false,
    val returnYouTubeDislikeEnabled: Boolean = true,
    val fallbackPipedApiUrl: String = "https://pipedapi.kavin.rocks"
)

private val Context.dataStore by preferencesDataStore(name = "puretube_preferences")

class AppPreferences(private val context: Context) {

    private object Keys {
        val protectionScope = stringPreferencesKey("protection_scope")
        val blurTargetGender = stringPreferencesKey("blur_target_gender")
        val protectedMediaType = stringPreferencesKey("protected_media_type")
        val blurVisualStyle = stringPreferencesKey("blur_visual_style")
        val detectionSensitivity = floatPreferencesKey("detection_sensitivity")
        val blurIntensityRadius = floatPreferencesKey("blur_intensity_radius")
        val zeroFlashSafePreBlur = booleanPreferencesKey("zero_flash_safe_pre_blur")
        val flagSecureAppSwitcherMask = booleanPreferencesKey("flag_secure_app_switcher_mask")
        val systemShieldExcludedPackages = stringSetPreferencesKey("system_shield_excluded_packages")
        val autoEnterPipOnHome = booleanPreferencesKey("auto_enter_pip_on_home")
        val backgroundPlaybackEnabled = booleanPreferencesKey("background_playback_enabled")
        val defaultWifiQualityP = intPreferencesKey("default_wifi_quality_p")
        val defaultMobileQualityP = intPreferencesKey("default_mobile_quality_p")
        val preferAv1Vp9 = booleanPreferencesKey("prefer_av1_vp9")
        val defaultPlaybackSpeed = floatPreferencesKey("default_playback_speed")
        val doubleTapSeekSeconds = intPreferencesKey("double_tap_seek_seconds")
        val pocketVolumeLongPressSkip = booleanPreferencesKey("pocket_volume_long_press_skip")
        val audioNormalizationEnabled = booleanPreferencesKey("audio_normalization_enabled")
        val monoAudioMergeEnabled = booleanPreferencesKey("mono_audio_merge_enabled")
        val keepScreenDimmedAwakeLock = booleanPreferencesKey("keep_screen_dimmed_awake_lock")
        val audioOnlyMode = booleanPreferencesKey("audio_only_mode")
        val stripShortsEverywhere = booleanPreferencesKey("strip_shorts_everywhere")
        val defaultStartTab = stringPreferencesKey("default_start_tab")
        val collapseCommentsByDefault = booleanPreferencesKey("collapse_comments_by_default")
        val collapseRelatedByDefault = booleanPreferencesKey("collapse_related_by_default")
        val showUnwatchedOnlyInSubs = booleanPreferencesKey("show_unwatched_only_in_subs")
        val nightOledDimmerAlpha = floatPreferencesKey("night_oled_dimmer_alpha")
        val sponsorBlockEnabled = booleanPreferencesKey("sponsor_block_enabled")
        val sponsorCategories = stringSetPreferencesKey("sponsor_categories")
        val deArrowTitlesEnabled = booleanPreferencesKey("dearrow_titles_enabled")
        val deArrowThumbnailsEnabled = booleanPreferencesKey("dearrow_thumbnails_enabled")
        val returnYouTubeDislikeEnabled = booleanPreferencesKey("ryd_enabled")
        val fallbackPipedApiUrl = stringPreferencesKey("fallback_piped_api_url")
    }

    val state: Flow<AppPreferencesState> = context.dataStore.data.map { p -> p.toState() }

    private fun Preferences.toState(): AppPreferencesState {
        val d = AppPreferencesState()
        fun <E : Enum<E>> enumOr(key: Preferences.Key<String>, values: Array<E>, fallback: E): E =
            this[key]?.let { name -> values.firstOrNull { it.name == name } } ?: fallback
        return AppPreferencesState(
            protectionScope = enumOr(Keys.protectionScope, ProtectionScope.entries.toTypedArray(), d.protectionScope),
            blurTargetGender = enumOr(Keys.blurTargetGender, BlurTargetGender.entries.toTypedArray(), d.blurTargetGender),
            protectedMediaType = enumOr(Keys.protectedMediaType, ProtectedMediaType.entries.toTypedArray(), d.protectedMediaType),
            blurVisualStyle = enumOr(Keys.blurVisualStyle, BlurVisualStyle.entries.toTypedArray(), d.blurVisualStyle),
            detectionSensitivity = this[Keys.detectionSensitivity] ?: d.detectionSensitivity,
            blurIntensityRadius = this[Keys.blurIntensityRadius] ?: d.blurIntensityRadius,
            zeroFlashSafePreBlur = this[Keys.zeroFlashSafePreBlur] ?: d.zeroFlashSafePreBlur,
            flagSecureAppSwitcherMask = this[Keys.flagSecureAppSwitcherMask] ?: d.flagSecureAppSwitcherMask,
            systemShieldExcludedPackages = this[Keys.systemShieldExcludedPackages] ?: d.systemShieldExcludedPackages,
            autoEnterPipOnHome = this[Keys.autoEnterPipOnHome] ?: d.autoEnterPipOnHome,
            backgroundPlaybackEnabled = this[Keys.backgroundPlaybackEnabled] ?: d.backgroundPlaybackEnabled,
            defaultWifiQualityP = this[Keys.defaultWifiQualityP] ?: d.defaultWifiQualityP,
            defaultMobileQualityP = this[Keys.defaultMobileQualityP] ?: d.defaultMobileQualityP,
            preferAv1Vp9HardwareCodec = this[Keys.preferAv1Vp9] ?: d.preferAv1Vp9HardwareCodec,
            defaultPlaybackSpeed = this[Keys.defaultPlaybackSpeed] ?: d.defaultPlaybackSpeed,
            doubleTapSeekSeconds = this[Keys.doubleTapSeekSeconds] ?: d.doubleTapSeekSeconds,
            pocketVolumeLongPressSkip = this[Keys.pocketVolumeLongPressSkip] ?: d.pocketVolumeLongPressSkip,
            audioNormalizationEnabled = this[Keys.audioNormalizationEnabled] ?: d.audioNormalizationEnabled,
            monoAudioMergeEnabled = this[Keys.monoAudioMergeEnabled] ?: d.monoAudioMergeEnabled,
            keepScreenDimmedAwakeLock = this[Keys.keepScreenDimmedAwakeLock] ?: d.keepScreenDimmedAwakeLock,
            audioOnlyMode = this[Keys.audioOnlyMode] ?: d.audioOnlyMode,
            stripShortsEverywhere = this[Keys.stripShortsEverywhere] ?: d.stripShortsEverywhere,
            defaultStartTab = enumOr(Keys.defaultStartTab, DefaultStartTab.entries.toTypedArray(), d.defaultStartTab),
            collapseCommentsByDefault = this[Keys.collapseCommentsByDefault] ?: d.collapseCommentsByDefault,
            collapseRelatedByDefault = this[Keys.collapseRelatedByDefault] ?: d.collapseRelatedByDefault,
            showUnwatchedOnlyInSubs = this[Keys.showUnwatchedOnlyInSubs] ?: d.showUnwatchedOnlyInSubs,
            nightOledDimmerAlpha = this[Keys.nightOledDimmerAlpha] ?: d.nightOledDimmerAlpha,
            sponsorBlockEnabled = this[Keys.sponsorBlockEnabled] ?: d.sponsorBlockEnabled,
            sponsorCategories = this[Keys.sponsorCategories] ?: d.sponsorCategories,
            deArrowTitlesEnabled = this[Keys.deArrowTitlesEnabled] ?: d.deArrowTitlesEnabled,
            deArrowThumbnailsEnabled = this[Keys.deArrowThumbnailsEnabled] ?: d.deArrowThumbnailsEnabled,
            returnYouTubeDislikeEnabled = this[Keys.returnYouTubeDislikeEnabled] ?: d.returnYouTubeDislikeEnabled,
            fallbackPipedApiUrl = this[Keys.fallbackPipedApiUrl] ?: d.fallbackPipedApiUrl
        )
    }

    suspend fun update(transform: (AppPreferencesState) -> AppPreferencesState) {
        context.dataStore.edit { prefs ->
            val next = transform(prefs.toState())
            prefs[Keys.protectionScope] = next.protectionScope.name
            prefs[Keys.blurTargetGender] = next.blurTargetGender.name
            prefs[Keys.protectedMediaType] = next.protectedMediaType.name
            prefs[Keys.blurVisualStyle] = next.blurVisualStyle.name
            prefs[Keys.detectionSensitivity] = next.detectionSensitivity
            prefs[Keys.blurIntensityRadius] = next.blurIntensityRadius
            prefs[Keys.zeroFlashSafePreBlur] = next.zeroFlashSafePreBlur
            prefs[Keys.flagSecureAppSwitcherMask] = next.flagSecureAppSwitcherMask
            prefs[Keys.systemShieldExcludedPackages] = next.systemShieldExcludedPackages
            prefs[Keys.autoEnterPipOnHome] = next.autoEnterPipOnHome
            prefs[Keys.backgroundPlaybackEnabled] = next.backgroundPlaybackEnabled
            prefs[Keys.defaultWifiQualityP] = next.defaultWifiQualityP
            prefs[Keys.defaultMobileQualityP] = next.defaultMobileQualityP
            prefs[Keys.preferAv1Vp9] = next.preferAv1Vp9HardwareCodec
            prefs[Keys.defaultPlaybackSpeed] = next.defaultPlaybackSpeed
            prefs[Keys.doubleTapSeekSeconds] = next.doubleTapSeekSeconds
            prefs[Keys.pocketVolumeLongPressSkip] = next.pocketVolumeLongPressSkip
            prefs[Keys.audioNormalizationEnabled] = next.audioNormalizationEnabled
            prefs[Keys.monoAudioMergeEnabled] = next.monoAudioMergeEnabled
            prefs[Keys.keepScreenDimmedAwakeLock] = next.keepScreenDimmedAwakeLock
            prefs[Keys.audioOnlyMode] = next.audioOnlyMode
            prefs[Keys.stripShortsEverywhere] = next.stripShortsEverywhere
            prefs[Keys.defaultStartTab] = next.defaultStartTab.name
            prefs[Keys.collapseCommentsByDefault] = next.collapseCommentsByDefault
            prefs[Keys.collapseRelatedByDefault] = next.collapseRelatedByDefault
            prefs[Keys.showUnwatchedOnlyInSubs] = next.showUnwatchedOnlyInSubs
            prefs[Keys.nightOledDimmerAlpha] = next.nightOledDimmerAlpha
            prefs[Keys.sponsorBlockEnabled] = next.sponsorBlockEnabled
            prefs[Keys.sponsorCategories] = next.sponsorCategories
            prefs[Keys.deArrowTitlesEnabled] = next.deArrowTitlesEnabled
            prefs[Keys.deArrowThumbnailsEnabled] = next.deArrowThumbnailsEnabled
            prefs[Keys.returnYouTubeDislikeEnabled] = next.returnYouTubeDislikeEnabled
            prefs[Keys.fallbackPipedApiUrl] = next.fallbackPipedApiUrl
        }
    }
}
