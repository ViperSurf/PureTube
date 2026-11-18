package com.puretube.app.domain

import com.puretube.app.data.local.datastore.AppPreferencesState

/**
 * Per-section settings resets (v1.2). Section names match the settings
 * screen's section titles exactly. Resetting a section restores only that
 * section's preferences to the shipped defaults. Pure + unit-tested.
 */
object SectionResets {
    const val SECTION_1 = "1. HaramBlur Protection"
    const val SECTION_2 = "2. Playback, PiP & Audio"
    const val SECTION_3 = "3. Focus & Feed Filters"
    const val SECTION_4 = "4. SponsorBlock & Backup"
    const val SECTION_5 = "5. History, Queue & Data"
    const val SECTION_6 = "6. Language & Region"
    const val SECTION_7 = "7. Diagnostics & Data Actions"

    val sectionsInOrder = listOf(
        SECTION_1, SECTION_2, SECTION_3, SECTION_4, SECTION_5, SECTION_6, SECTION_7
    )

    fun reset(state: AppPreferencesState, section: String): AppPreferencesState {
        val d = AppPreferencesState()
        return when (section) {
            SECTION_1 -> state.copy(
                protectionScope = d.protectionScope,
                blurTargetGender = d.blurTargetGender,
                protectedMediaType = d.protectedMediaType,
                blurVisualStyle = d.blurVisualStyle,
                detectionSensitivity = d.detectionSensitivity,
                blurIntensityRadius = d.blurIntensityRadius,
                zeroFlashSafePreBlur = d.zeroFlashSafePreBlur,
                videoFrameSamplingEnabled = d.videoFrameSamplingEnabled,
                flagSecureAppSwitcherMask = d.flagSecureAppSwitcherMask,
                systemShieldExcludedPackages = d.systemShieldExcludedPackages
            )
            SECTION_2 -> state.copy(
                autoEnterPipOnHome = d.autoEnterPipOnHome,
                backgroundPlaybackEnabled = d.backgroundPlaybackEnabled,
                defaultWifiQualityP = d.defaultWifiQualityP,
                defaultMobileQualityP = d.defaultMobileQualityP,
                preferAv1Vp9HardwareCodec = d.preferAv1Vp9HardwareCodec,
                defaultPlaybackSpeed = d.defaultPlaybackSpeed,
                doubleTapSeekSeconds = d.doubleTapSeekSeconds,
                pocketVolumeLongPressSkip = d.pocketVolumeLongPressSkip,
                audioNormalizationEnabled = d.audioNormalizationEnabled,
                monoAudioMergeEnabled = d.monoAudioMergeEnabled,
                keepScreenDimmedAwakeLock = d.keepScreenDimmedAwakeLock,
                audioOnlyMode = d.audioOnlyMode,
                autoplayEnabled = d.autoplayEnabled,
                resumePlaybackEnabled = d.resumePlaybackEnabled,
                alwaysStartFromBeginning = d.alwaysStartFromBeginning,
                resumeAfterInterruption = d.resumeAfterInterruption,
                requireAudioFocus = d.requireAudioFocus,
                disableAndroidAuto = d.disableAndroidAuto,
                sleepTimerDefaultMinutes = d.sleepTimerDefaultMinutes
            )
            SECTION_3 -> state.copy(
                stripShortsEverywhere = d.stripShortsEverywhere,
                defaultStartTab = d.defaultStartTab,
                collapseCommentsByDefault = d.collapseCommentsByDefault,
                collapseRelatedByDefault = d.collapseRelatedByDefault,
                showUnwatchedOnlyInSubs = d.showUnwatchedOnlyInSubs,
                nightOledDimmerAlpha = d.nightOledDimmerAlpha,
                feedLayout = d.feedLayout,
                feedThumbnailScale = d.feedThumbnailScale,
                gridColumns = d.gridColumns,
                contentMinLikes = d.contentMinLikes,
                contentMaxLikes = d.contentMaxLikes,
                commentMinLikes = d.commentMinLikes,
                commentMaxLikes = d.commentMaxLikes
            )
            SECTION_4 -> state.copy(
                sponsorBlockEnabled = d.sponsorBlockEnabled,
                sponsorCategories = d.sponsorCategories,
                sponsorBlockNotifyOnSkip = d.sponsorBlockNotifyOnSkip,
                sponsorBlockPauseSkipOnRewind = d.sponsorBlockPauseSkipOnRewind,
                deArrowTitlesEnabled = d.deArrowTitlesEnabled,
                deArrowThumbnailsEnabled = d.deArrowThumbnailsEnabled,
                returnYouTubeDislikeEnabled = d.returnYouTubeDislikeEnabled,
                fallbackPipedApiUrl = d.fallbackPipedApiUrl
            )
            SECTION_5 -> state.copy(
                watchHistoryEnabled = d.watchHistoryEnabled,
                historyGroupMode = d.historyGroupMode,
                searchHistoryEnabled = d.searchHistoryEnabled,
                suggestionsEnabled = d.suggestionsEnabled,
                historyVisibleDays = d.historyVisibleDays,
                resetQueueOnExit = d.resetQueueOnExit,
                autoQueueShortOnly = d.autoQueueShortOnly,
                autoQueueMaxMinutes = d.autoQueueMaxMinutes,
                feedUpdateThresholdMinutes = d.feedUpdateThresholdMinutes
            )
            SECTION_6 -> state.copy(
                appLanguage = d.appLanguage,
                contentCountry = d.contentCountry,
                preferredAudioLanguage = d.preferredAudioLanguage
            )
            else -> state // Section 7 holds actions/diagnostics, no preferences.
        }
    }
}
