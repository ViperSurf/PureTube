package com.puretube.app.data.local.datastore

import android.content.Context
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.puretube.app.playback.PlaybackService
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable

enum class ProtectionScope { OFF, IN_APP_ONLY, SYSTEM_WIDE_ONLY, BOTH }
enum class BlurTargetGender { FEMALE_ONLY, MALE_ONLY, BOTH_ALL_PEOPLE }
enum class ProtectedMediaType { IMAGES_ONLY, VIDEOS_ONLY, BOTH_IMAGES_AND_VIDEOS }
enum class BlurVisualStyle { FROSTED_GAUSSIAN, PIXELATE_MOSAIC, SOLID_DIM_CURTAIN }
enum class DefaultStartTab { SUBSCRIPTIONS, SEARCH, LIBRARY }
enum class FeedLayout { LIST, GRID, THUMBNAIL_ONLY, TITLE_ONLY, BOTH }

/** History display (v1.2): one section per calendar day, or one flat list. */
enum class HistoryGroupMode { GROUPED_BY_DAY, CONTINUOUS }

/** Feed thumbnail scale (v1.2). */
enum class ThumbnailScale { COMPACT, NORMAL, LARGE }

/** App language; SYSTEM follows the phone. Applies on restart. */
enum class AppLanguage(val code: String) {
    SYSTEM("SYSTEM"), ENGLISH("en"), ARABIC("ar"), SPANISH("es"),
    FRENCH("fr"), GERMAN("de"), URDU("ur"), TURKISH("tr")
}

/** Content country passed to the extractor localization, best-effort. */
enum class ContentCountry(val code: String) {
    SYSTEM("SYSTEM"), US("US"), GB("GB"), DE("DE"), FR("FR"),
    EG("EG"), SA("SA"), IN("IN"), PK("PK"), TR("TR"), ID("ID")
}

/** Preferred audio track language; ORIGINAL keeps the uploader's track. */
enum class PreferredAudioLanguage(val code: String?) {
    ORIGINAL(null), ENGLISH("en"), ARABIC("ar"), SPANISH("es"),
    FRENCH("fr"), GERMAN("de"), URDU("ur")
}

@Serializable
data class AppPreferencesState(
    // Section 1: HaramBlur Protection
    val protectionScope: ProtectionScope = ProtectionScope.IN_APP_ONLY,
    val blurTargetGender: BlurTargetGender = BlurTargetGender.FEMALE_ONLY,
    val protectedMediaType: ProtectedMediaType = ProtectedMediaType.BOTH_IMAGES_AND_VIDEOS,
    val blurVisualStyle: BlurVisualStyle = BlurVisualStyle.FROSTED_GAUSSIAN,
    val detectionSensitivity: Float = 0.65f,
    val blurIntensityRadius: Float = 28f,
    val zeroFlashSafePreBlur: Boolean = true,
    val videoFrameSamplingEnabled: Boolean = true,
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
    val autoplayEnabled: Boolean = true,
    val resumePlaybackEnabled: Boolean = true,
    val alwaysStartFromBeginning: Boolean = false,
    val resumeAfterInterruption: Boolean = false,
    val requireAudioFocus: Boolean = true,
    val disableAndroidAuto: Boolean = true,
    val sleepTimerDefaultMinutes: Int = 15,
    // Section 3: Focus, Shorts & Feed Filters
    val stripShortsEverywhere: Boolean = true,
    val defaultStartTab: DefaultStartTab = DefaultStartTab.SUBSCRIPTIONS,
    val collapseCommentsByDefault: Boolean = true,
    val collapseRelatedByDefault: Boolean = true,
    val showUnwatchedOnlyInSubs: Boolean = false,
    val nightOledDimmerAlpha: Float = 0.0f,
    val feedLayout: FeedLayout = FeedLayout.LIST,
    val feedThumbnailScale: ThumbnailScale = ThumbnailScale.NORMAL,
    val gridColumns: Int = 2,
    val contentMinLikes: Int? = null,
    val contentMaxLikes: Int? = null,
    val commentMinLikes: Int? = null,
    val commentMaxLikes: Int? = null,
    // Section 4: SponsorBlock, DeArrow & RYD
    val sponsorBlockEnabled: Boolean = true,
    val sponsorCategories: Set<String> = setOf("sponsor", "selfpromo", "intro", "outro", "interaction"),
    val sponsorBlockNotifyOnSkip: Boolean = true,
    val sponsorBlockPauseSkipOnRewind: Boolean = true,
    val deArrowTitlesEnabled: Boolean = false,
    val deArrowThumbnailsEnabled: Boolean = false,
    val returnYouTubeDislikeEnabled: Boolean = true,
    val fallbackPipedApiUrl: String = "https://pipedapi.kavin.rocks",
    // Section 5: History, Queue & Library (v1.1)
    val watchHistoryEnabled: Boolean = true,
    val historyGroupMode: HistoryGroupMode = HistoryGroupMode.GROUPED_BY_DAY,
    val searchHistoryEnabled: Boolean = true,
    val suggestionsEnabled: Boolean = false,
    val historyVisibleDays: Int = 0,
    val resetQueueOnExit: Boolean = true,
    val autoQueueShortOnly: Boolean = false,
    val autoQueueMaxMinutes: Int = 6,
    val feedUpdateThresholdMinutes: Int = 60,
    // Section 6: Language & Region (v1.1)
    val appLanguage: AppLanguage = AppLanguage.SYSTEM,
    val contentCountry: ContentCountry = ContentCountry.SYSTEM,
    val preferredAudioLanguage: PreferredAudioLanguage = PreferredAudioLanguage.ORIGINAL
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
        val videoFrameSamplingEnabled = booleanPreferencesKey("video_frame_sampling_enabled")
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
        val autoplayEnabled = booleanPreferencesKey("autoplay_enabled")
        val resumePlaybackEnabled = booleanPreferencesKey("resume_playback_enabled")
        val alwaysStartFromBeginning = booleanPreferencesKey("always_start_from_beginning")
        val resumeAfterInterruption = booleanPreferencesKey("resume_after_interruption")
        val requireAudioFocus = booleanPreferencesKey("require_audio_focus")
        val disableAndroidAuto = booleanPreferencesKey("disable_android_auto")
        val sleepTimerDefaultMinutes = intPreferencesKey("sleep_timer_default_minutes")
        val stripShortsEverywhere = booleanPreferencesKey("strip_shorts_everywhere")
        val defaultStartTab = stringPreferencesKey("default_start_tab")
        val collapseCommentsByDefault = booleanPreferencesKey("collapse_comments_by_default")
        val collapseRelatedByDefault = booleanPreferencesKey("collapse_related_by_default")
        val showUnwatchedOnlyInSubs = booleanPreferencesKey("show_unwatched_only_in_subs")
        val nightOledDimmerAlpha = floatPreferencesKey("night_oled_dimmer_alpha")
        val feedLayout = stringPreferencesKey("feed_layout")
        val feedThumbnailScale = stringPreferencesKey("feed_thumbnail_scale")
        val gridColumns = intPreferencesKey("grid_columns")
        val historyGroupMode = stringPreferencesKey("history_group_mode")
        val contentMinLikes = intPreferencesKey("content_min_likes")
        val contentMaxLikes = intPreferencesKey("content_max_likes")
        val commentMinLikes = intPreferencesKey("comment_min_likes")
        val commentMaxLikes = intPreferencesKey("comment_max_likes")
        val sponsorBlockEnabled = booleanPreferencesKey("sponsor_block_enabled")
        val sponsorCategories = stringSetPreferencesKey("sponsor_categories")
        val sponsorBlockNotifyOnSkip = booleanPreferencesKey("sponsorblock_notify_on_skip")
        val sponsorBlockPauseSkipOnRewind = booleanPreferencesKey("sponsorblock_pause_skip_on_rewind")
        val deArrowTitlesEnabled = booleanPreferencesKey("dearrow_titles_enabled")
        val deArrowThumbnailsEnabled = booleanPreferencesKey("dearrow_thumbnails_enabled")
        val returnYouTubeDislikeEnabled = booleanPreferencesKey("ryd_enabled")
        val fallbackPipedApiUrl = stringPreferencesKey("fallback_piped_api_url")
        val watchHistoryEnabled = booleanPreferencesKey("watch_history_enabled")
        val searchHistoryEnabled = booleanPreferencesKey("search_history_enabled")
        val suggestionsEnabled = booleanPreferencesKey("suggestions_enabled")
        val historyVisibleDays = intPreferencesKey("history_visible_days")
        val resetQueueOnExit = booleanPreferencesKey("reset_queue_on_exit")
        val autoQueueShortOnly = booleanPreferencesKey("auto_queue_short_only")
        val autoQueueMaxMinutes = intPreferencesKey("auto_queue_max_minutes")
        val feedUpdateThresholdMinutes = intPreferencesKey("feed_update_threshold_minutes")
        val appLanguage = stringPreferencesKey("app_language")
        val contentCountry = stringPreferencesKey("content_country")
        val preferredAudioLanguage = stringPreferencesKey("preferred_audio_language")
        val feedLastFetch = stringPreferencesKey("feed_last_fetch_map")
    }

    val state: Flow<AppPreferencesState> = context.dataStore.data.map { p -> p.toState() }

    private fun Preferences.toState(): AppPreferencesState {
        val d = AppPreferencesState()
        fun <E : Enum<E>> enumOr(key: Preferences.Key<String>, values: Array<E>, fallback: E): E =
            this[key]?.let { name -> values.firstOrNull { it.name == name } } ?: fallback
        fun nullableInt(key: Preferences.Key<Int>): Int? =
            this[key]?.takeIf { it >= 0 }
        return AppPreferencesState(
            protectionScope = enumOr(Keys.protectionScope, ProtectionScope.entries.toTypedArray(), d.protectionScope),
            blurTargetGender = enumOr(Keys.blurTargetGender, BlurTargetGender.entries.toTypedArray(), d.blurTargetGender),
            protectedMediaType = enumOr(Keys.protectedMediaType, ProtectedMediaType.entries.toTypedArray(), d.protectedMediaType),
            blurVisualStyle = enumOr(Keys.blurVisualStyle, BlurVisualStyle.entries.toTypedArray(), d.blurVisualStyle),
            detectionSensitivity = this[Keys.detectionSensitivity] ?: d.detectionSensitivity,
            blurIntensityRadius = this[Keys.blurIntensityRadius] ?: d.blurIntensityRadius,
            zeroFlashSafePreBlur = this[Keys.zeroFlashSafePreBlur] ?: d.zeroFlashSafePreBlur,
            videoFrameSamplingEnabled = this[Keys.videoFrameSamplingEnabled] ?: d.videoFrameSamplingEnabled,
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
            autoplayEnabled = this[Keys.autoplayEnabled] ?: d.autoplayEnabled,
            resumePlaybackEnabled = this[Keys.resumePlaybackEnabled] ?: d.resumePlaybackEnabled,
            alwaysStartFromBeginning = this[Keys.alwaysStartFromBeginning] ?: d.alwaysStartFromBeginning,
            resumeAfterInterruption = this[Keys.resumeAfterInterruption] ?: d.resumeAfterInterruption,
            requireAudioFocus = this[Keys.requireAudioFocus] ?: d.requireAudioFocus,
            disableAndroidAuto = this[Keys.disableAndroidAuto] ?: d.disableAndroidAuto,
            sleepTimerDefaultMinutes = this[Keys.sleepTimerDefaultMinutes] ?: d.sleepTimerDefaultMinutes,
            stripShortsEverywhere = this[Keys.stripShortsEverywhere] ?: d.stripShortsEverywhere,
            defaultStartTab = enumOr(Keys.defaultStartTab, DefaultStartTab.entries.toTypedArray(), d.defaultStartTab),
            collapseCommentsByDefault = this[Keys.collapseCommentsByDefault] ?: d.collapseCommentsByDefault,
            collapseRelatedByDefault = this[Keys.collapseRelatedByDefault] ?: d.collapseRelatedByDefault,
            showUnwatchedOnlyInSubs = this[Keys.showUnwatchedOnlyInSubs] ?: d.showUnwatchedOnlyInSubs,
            nightOledDimmerAlpha = this[Keys.nightOledDimmerAlpha] ?: d.nightOledDimmerAlpha,
            feedLayout = enumOr(Keys.feedLayout, FeedLayout.entries.toTypedArray(), d.feedLayout),
            feedThumbnailScale = enumOr(Keys.feedThumbnailScale, ThumbnailScale.entries.toTypedArray(), d.feedThumbnailScale),
            gridColumns = this[Keys.gridColumns] ?: d.gridColumns,
            historyGroupMode = enumOr(Keys.historyGroupMode, HistoryGroupMode.entries.toTypedArray(), d.historyGroupMode),
            contentMinLikes = nullableInt(Keys.contentMinLikes),
            contentMaxLikes = nullableInt(Keys.contentMaxLikes),
            commentMinLikes = nullableInt(Keys.commentMinLikes),
            commentMaxLikes = nullableInt(Keys.commentMaxLikes),
            sponsorBlockEnabled = this[Keys.sponsorBlockEnabled] ?: d.sponsorBlockEnabled,
            sponsorCategories = this[Keys.sponsorCategories] ?: d.sponsorCategories,
            sponsorBlockNotifyOnSkip = this[Keys.sponsorBlockNotifyOnSkip] ?: d.sponsorBlockNotifyOnSkip,
            sponsorBlockPauseSkipOnRewind = this[Keys.sponsorBlockPauseSkipOnRewind] ?: d.sponsorBlockPauseSkipOnRewind,
            deArrowTitlesEnabled = this[Keys.deArrowTitlesEnabled] ?: d.deArrowTitlesEnabled,
            deArrowThumbnailsEnabled = this[Keys.deArrowThumbnailsEnabled] ?: d.deArrowThumbnailsEnabled,
            returnYouTubeDislikeEnabled = this[Keys.returnYouTubeDislikeEnabled] ?: d.returnYouTubeDislikeEnabled,
            fallbackPipedApiUrl = this[Keys.fallbackPipedApiUrl] ?: d.fallbackPipedApiUrl,
            watchHistoryEnabled = this[Keys.watchHistoryEnabled] ?: d.watchHistoryEnabled,
            searchHistoryEnabled = this[Keys.searchHistoryEnabled] ?: d.searchHistoryEnabled,
            suggestionsEnabled = this[Keys.suggestionsEnabled] ?: d.suggestionsEnabled,
            historyVisibleDays = this[Keys.historyVisibleDays] ?: d.historyVisibleDays,
            resetQueueOnExit = this[Keys.resetQueueOnExit] ?: d.resetQueueOnExit,
            autoQueueShortOnly = this[Keys.autoQueueShortOnly] ?: d.autoQueueShortOnly,
            autoQueueMaxMinutes = this[Keys.autoQueueMaxMinutes] ?: d.autoQueueMaxMinutes,
            feedUpdateThresholdMinutes = this[Keys.feedUpdateThresholdMinutes] ?: d.feedUpdateThresholdMinutes,
            appLanguage = enumOr(Keys.appLanguage, AppLanguage.entries.toTypedArray(), d.appLanguage),
            contentCountry = enumOr(Keys.contentCountry, ContentCountry.entries.toTypedArray(), d.contentCountry),
            preferredAudioLanguage = enumOr(Keys.preferredAudioLanguage, PreferredAudioLanguage.entries.toTypedArray(), d.preferredAudioLanguage)
        )
    }

    private fun MutablePreferences.writeState(next: AppPreferencesState) {
        this[Keys.protectionScope] = next.protectionScope.name
        this[Keys.blurTargetGender] = next.blurTargetGender.name
        this[Keys.protectedMediaType] = next.protectedMediaType.name
        this[Keys.blurVisualStyle] = next.blurVisualStyle.name
        this[Keys.detectionSensitivity] = next.detectionSensitivity
        this[Keys.blurIntensityRadius] = next.blurIntensityRadius
        this[Keys.zeroFlashSafePreBlur] = next.zeroFlashSafePreBlur
        this[Keys.videoFrameSamplingEnabled] = next.videoFrameSamplingEnabled
        this[Keys.flagSecureAppSwitcherMask] = next.flagSecureAppSwitcherMask
        this[Keys.systemShieldExcludedPackages] = next.systemShieldExcludedPackages
        this[Keys.autoEnterPipOnHome] = next.autoEnterPipOnHome
        this[Keys.backgroundPlaybackEnabled] = next.backgroundPlaybackEnabled
        this[Keys.defaultWifiQualityP] = next.defaultWifiQualityP
        this[Keys.defaultMobileQualityP] = next.defaultMobileQualityP
        this[Keys.preferAv1Vp9] = next.preferAv1Vp9HardwareCodec
        this[Keys.defaultPlaybackSpeed] = next.defaultPlaybackSpeed
        this[Keys.doubleTapSeekSeconds] = next.doubleTapSeekSeconds
        this[Keys.pocketVolumeLongPressSkip] = next.pocketVolumeLongPressSkip
        this[Keys.audioNormalizationEnabled] = next.audioNormalizationEnabled
        this[Keys.monoAudioMergeEnabled] = next.monoAudioMergeEnabled
        this[Keys.keepScreenDimmedAwakeLock] = next.keepScreenDimmedAwakeLock
        this[Keys.audioOnlyMode] = next.audioOnlyMode
        this[Keys.autoplayEnabled] = next.autoplayEnabled
        this[Keys.resumePlaybackEnabled] = next.resumePlaybackEnabled
        this[Keys.alwaysStartFromBeginning] = next.alwaysStartFromBeginning
        this[Keys.resumeAfterInterruption] = next.resumeAfterInterruption
        this[Keys.requireAudioFocus] = next.requireAudioFocus
        this[Keys.disableAndroidAuto] = next.disableAndroidAuto
        this[Keys.sleepTimerDefaultMinutes] = next.sleepTimerDefaultMinutes
        this[Keys.stripShortsEverywhere] = next.stripShortsEverywhere
        this[Keys.defaultStartTab] = next.defaultStartTab.name
        this[Keys.collapseCommentsByDefault] = next.collapseCommentsByDefault
        this[Keys.collapseRelatedByDefault] = next.collapseRelatedByDefault
        this[Keys.showUnwatchedOnlyInSubs] = next.showUnwatchedOnlyInSubs
        this[Keys.nightOledDimmerAlpha] = next.nightOledDimmerAlpha
        this[Keys.feedLayout] = next.feedLayout.name
        this[Keys.feedThumbnailScale] = next.feedThumbnailScale.name
        this[Keys.gridColumns] = next.gridColumns
        this[Keys.historyGroupMode] = next.historyGroupMode.name
        this[Keys.contentMinLikes] = next.contentMinLikes ?: -1
        this[Keys.contentMaxLikes] = next.contentMaxLikes ?: -1
        this[Keys.commentMinLikes] = next.commentMinLikes ?: -1
        this[Keys.commentMaxLikes] = next.commentMaxLikes ?: -1
        this[Keys.sponsorBlockEnabled] = next.sponsorBlockEnabled
        this[Keys.sponsorCategories] = next.sponsorCategories
        this[Keys.sponsorBlockNotifyOnSkip] = next.sponsorBlockNotifyOnSkip
        this[Keys.sponsorBlockPauseSkipOnRewind] = next.sponsorBlockPauseSkipOnRewind
        this[Keys.deArrowTitlesEnabled] = next.deArrowTitlesEnabled
        this[Keys.deArrowThumbnailsEnabled] = next.deArrowThumbnailsEnabled
        this[Keys.returnYouTubeDislikeEnabled] = next.returnYouTubeDislikeEnabled
        this[Keys.fallbackPipedApiUrl] = next.fallbackPipedApiUrl
        this[Keys.watchHistoryEnabled] = next.watchHistoryEnabled
        this[Keys.searchHistoryEnabled] = next.searchHistoryEnabled
        this[Keys.suggestionsEnabled] = next.suggestionsEnabled
        this[Keys.historyVisibleDays] = next.historyVisibleDays
        this[Keys.resetQueueOnExit] = next.resetQueueOnExit
        this[Keys.autoQueueShortOnly] = next.autoQueueShortOnly
        this[Keys.autoQueueMaxMinutes] = next.autoQueueMaxMinutes
        this[Keys.feedUpdateThresholdMinutes] = next.feedUpdateThresholdMinutes
        this[Keys.appLanguage] = next.appLanguage.name
        this[Keys.contentCountry] = next.contentCountry.name
        this[Keys.preferredAudioLanguage] = next.preferredAudioLanguage.name
    }

    /** Synchronous mirror for early readers (locale, playback service). */
    private fun mirror(next: AppPreferencesState) {
        runCatching {
            context.getSharedPreferences(PlaybackService.MIRROR_PREFS, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(PlaybackService.KEY_REQUIRE_AUDIO_FOCUS, next.requireAudioFocus)
                .putBoolean(PlaybackService.KEY_DISABLE_ANDROID_AUTO, next.disableAndroidAuto)
                .putString(PlaybackService.KEY_APP_LANGUAGE, next.appLanguage.code)
                .apply()
        }
    }

    suspend fun update(transform: (AppPreferencesState) -> AppPreferencesState) {
        var applied: AppPreferencesState? = null
        context.dataStore.edit { prefs ->
            val next = transform(prefs.toState())
            prefs.writeState(next)
            applied = next
        }
        applied?.let { mirror(it) }
    }

    /** Replaces all preferences at once (settings import). */
    suspend fun replaceAll(next: AppPreferencesState) {
        context.dataStore.edit { prefs -> prefs.writeState(next) }
        mirror(next)
    }

    // ---- Per-channel feed fetch timestamps (threshold support) ----

    suspend fun feedLastFetchMap(): Map<String, Long> {
        var raw: String? = null
        context.dataStore.edit { /* no-op read barrier */ }
        // Read through the flow-free path: use edit-free snapshot via data.first in caller;
        // here we do a lightweight direct read using the same store file through edit capture.
        // Simpler: capture inside edit without changes.
        context.dataStore.edit { prefs -> raw = prefs[Keys.feedLastFetch] }
        return FeedFetchMap.decode(raw)
    }

    suspend fun setFeedLastFetch(channelId: String, epochMs: Long) {
        context.dataStore.edit { prefs ->
            val map = FeedFetchMap.decode(prefs[Keys.feedLastFetch]).toMutableMap()
            map[channelId] = epochMs
            prefs[Keys.feedLastFetch] = FeedFetchMap.encode(map)
        }
    }
}

/** Compact "channelId:epochMs;..." encoding for per-channel feed fetch times. Pure + tested. */
object FeedFetchMap {
    fun encode(map: Map<String, Long>): String =
        map.entries.joinToString(";") { "${it.key}:${it.value}" }

    fun decode(raw: String?): Map<String, Long> {
        if (raw.isNullOrBlank()) return emptyMap()
        return raw.split(";").mapNotNull { part ->
            val idx = part.lastIndexOf(':')
            if (idx <= 0) null
            else part.substring(0, idx) to (part.substring(idx + 1).toLongOrNull() ?: return@mapNotNull null)
        }.toMap()
    }

    fun isStale(lastFetchMs: Long?, nowMs: Long, thresholdMinutes: Int): Boolean {
        if (lastFetchMs == null) return true
        return nowMs - lastFetchMs >= thresholdMinutes.coerceAtLeast(0) * 60_000L
    }
}
