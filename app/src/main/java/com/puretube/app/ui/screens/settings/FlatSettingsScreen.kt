package com.puretube.app.ui.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import com.puretube.app.PureTubeApp
import com.puretube.app.data.local.LastCrashStore
import com.puretube.app.data.local.datastore.AppLanguage
import com.puretube.app.data.local.datastore.BlurTargetGender
import com.puretube.app.data.local.datastore.ContentCountry
import com.puretube.app.data.local.datastore.FeedLayout
import com.puretube.app.data.local.datastore.PreferredAudioLanguage
import com.puretube.app.data.local.datastore.BlurVisualStyle
import com.puretube.app.data.local.datastore.DefaultStartTab
import com.puretube.app.data.local.datastore.ProtectedMediaType
import com.puretube.app.data.local.datastore.ProtectionScope
import com.puretube.app.data.local.datastore.AppPreferencesState
import com.puretube.app.data.local.datastore.HistoryGroupMode
import com.puretube.app.data.local.datastore.ThumbnailScale
import com.puretube.app.domain.SectionResets
import com.puretube.app.domain.SettingsJson
import androidx.compose.ui.res.stringResource
import com.puretube.app.ui.components.SettingRowWithTooltip
import com.puretube.app.ui.theme.PureTextSecondary
import kotlinx.coroutines.launch

private data class SettingRowDef(
    val section: String,
    val title: String,
    val tooltip: String,
    val content: @Composable () -> Unit
)

/**
 * v2.1: section headers render from string resources so the chosen app
 * language actually changes them. The internal section KEYS stay the
 * English literals (grouping, reset and search all key off them).
 */
@Composable
private fun localizedSectionTitle(section: String): String = when (section) {
    "1. HaramBlur Protection" -> stringResource(com.puretube.app.R.string.section_haramblur)
    "2. Playback, PiP & Audio" -> stringResource(com.puretube.app.R.string.section_playback)
    "3. Focus & Feed Filters" -> stringResource(com.puretube.app.R.string.section_focus)
    "4. SponsorBlock & Backup" -> stringResource(com.puretube.app.R.string.section_sponsorblock)
    "5. History, Queue & Data" -> stringResource(com.puretube.app.R.string.section_history)
    "6. Language & Region" -> stringResource(com.puretube.app.R.string.section_language)
    "7. Sign-in & Sync" -> stringResource(com.puretube.app.R.string.section_signin)
    "8. Captions" -> stringResource(com.puretube.app.R.string.section_captions)
    else -> section
}

/** Friendly display name for the language picker (native names). */
private fun appLanguageLabel(language: AppLanguage): String = when (language) {
    AppLanguage.SYSTEM -> "System default"
    AppLanguage.ENGLISH -> "English"
    AppLanguage.ARABIC -> "العربية"
    AppLanguage.SPANISH -> "Español"
    AppLanguage.FRENCH -> "Français"
    AppLanguage.TURKISH -> "Türkçe"
    AppLanguage.URDU -> "اردو"
    AppLanguage.GERMAN -> "English"
}

@Composable
private fun CycleButton(label: String, onCycle: () -> Unit) {
    Button(onClick = onCycle) { Text(label, fontSize = 13.sp) }
}

/**
 * Flat settings: one screen, instant search filter, four collapsible sections.
 * Every control carries its inline (?) explanation.
 */
@Composable
fun FlatSettingsScreen() {
    val context = LocalContext.current
    val app = context.applicationContext as PureTubeApp
    val scope = rememberCoroutineScope()
    val prefs by app.preferences.state.collectAsState(initial = app.currentPrefs)
    var query by remember { mutableStateOf("") }
    var crashText by remember { mutableStateOf(LastCrashStore.read(context) ?: "") }
    var dataStatus by remember { mutableStateOf("") }
    var confirmResetAll by remember { mutableStateOf(false) }
    val subscriptionsForExport by app.database.subscriptionDao().observeAll()
        .collectAsState(initial = emptyList())

    fun readUriText(uri: android.net.Uri): String? = runCatching {
        context.contentResolver.openInputStream(uri)?.bufferedReader()?.readText()
    }.getOrNull()
    fun writeUriText(uri: android.net.Uri, text: String) {
        runCatching {
            context.contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { it.write(text) }
        }
    }
    val importSettingsLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            val imported = readUriText(uri)?.let { SettingsJson.import(it) }
            if (imported != null) {
                scope.launch { app.preferences.replaceAll(imported) }
                dataStatus = "Settings imported from file."
            } else {
                dataStatus = "That file was not a PureTube settings export."
            }
        }
    }
    val exportSettingsLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri != null) {
            writeUriText(uri, SettingsJson.export(prefs))
            dataStatus = "Settings exported to file."
        }
    }
    val importSubsLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            val text = readUriText(uri)
            scope.launch {
                val report = text?.let {
                    runCatching { app.importExportManager.parseAnyWithReport(it) }.getOrNull()
                }
                val parsed = report?.subscriptions.orEmpty()
                if (parsed.isNotEmpty()) app.database.subscriptionDao().upsertAll(parsed)
                dataStatus = when {
                    report == null || parsed.isEmpty() -> "No subscriptions found in that file."
                    report.skipped > 0 ->
                        "Imported ${parsed.size} subscriptions (${report.format}), skipped ${report.skipped} unrecognised rows."
                    else -> "Imported ${parsed.size} subscriptions (${report.format}) from file."
                }
            }
        }
    }
    val exportSubsLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri != null) {
            writeUriText(uri, app.importExportManager.exportNewPipeJson(subscriptionsForExport))
            dataStatus = "Subscriptions exported to file."
        }
    }
    var pipedUrlDraft by remember(prefs.fallbackPipedApiUrl) { mutableStateOf(prefs.fallbackPipedApiUrl) }
    val expanded = remember {
        mutableStateOf(
            mapOf(
                "1. HaramBlur Protection" to false,
                "2. Playback, PiP & Audio" to false,
                "3. Focus & Feed Filters" to false,
                "4. SponsorBlock & Backup" to false,
                "5. History, Queue & Data" to false,
                "6. Language & Region" to false,
                "7. Diagnostics & Data Actions" to false
            )
        )
    }

    fun cycleScope() {
        val values = ProtectionScope.entries
        val next = values[(prefs.protectionScope.ordinal + 1) % values.size]
        scope.launch { app.preferences.update { it.copy(protectionScope = next) } }
    }
    fun cycleGender() {
        val values = BlurTargetGender.entries
        val next = values[(prefs.blurTargetGender.ordinal + 1) % values.size]
        scope.launch { app.preferences.update { it.copy(blurTargetGender = next) } }
    }
    fun cycleMedia() {
        val values = ProtectedMediaType.entries
        val next = values[(prefs.protectedMediaType.ordinal + 1) % values.size]
        scope.launch { app.preferences.update { it.copy(protectedMediaType = next) } }
    }
    fun cycleStyle() {
        val values = BlurVisualStyle.entries
        val next = values[(prefs.blurVisualStyle.ordinal + 1) % values.size]
        scope.launch { app.preferences.update { it.copy(blurVisualStyle = next) } }
    }
    fun cycleBlurScope() {
        val values = com.puretube.app.data.local.datastore.BlurScope.entries
        val next = values[(prefs.blurScope.ordinal + 1) % values.size]
        scope.launch { app.preferences.update { it.copy(blurScope = next) } }
    }
    fun cycleBlurMode() {
        val values = com.puretube.app.data.local.datastore.BlurDetectionMode.entries
        val next = values[(prefs.blurDetectionMode.ordinal + 1) % values.size]
        scope.launch { app.preferences.update { it.copy(blurDetectionMode = next) } }
    }
    fun cycleBlurStrength() {
        val values = com.puretube.app.data.local.datastore.BlurStrength.entries
        val next = values[(prefs.blurStrength.ordinal + 1) % values.size]
        scope.launch { app.preferences.update { it.copy(blurStrength = next) } }
    }
    fun cycleFeedLayout() {
        val values = FeedLayout.entries
        val next = values[(prefs.feedLayout.ordinal + 1) % values.size]
        scope.launch { app.preferences.update { it.copy(feedLayout = next) } }
    }
    fun cycleThumbnailScale() {
        val values = ThumbnailScale.entries
        val next = values[(prefs.feedThumbnailScale.ordinal + 1) % values.size]
        scope.launch { app.preferences.update { it.copy(feedThumbnailScale = next) } }
    }
    fun cycleGridColumns() {
        val options = listOf(2, 3, 4)
        val idx = options.indexOf(prefs.gridColumns).let { if (it < 0) 0 else it }
        val next = options[(idx + 1) % options.size]
        scope.launch { app.preferences.update { it.copy(gridColumns = next) } }
    }
    fun cycleHistoryGroupMode() {
        val values = HistoryGroupMode.entries
        val next = values[(prefs.historyGroupMode.ordinal + 1) % values.size]
        scope.launch { app.preferences.update { it.copy(historyGroupMode = next) } }
    }
    fun cycleAppLanguage() {
        // v2.1: cycle only the OFFERED languages (German has no shipped
        // strings and is no longer offered), apply the locale for real,
        // and recreate the activity on pre-API 33 devices so the change
        // is visible immediately (API 33+ recreates via LocaleManager).
        val offered = com.puretube.app.domain.LanguageSupport.offeredLanguages
        val current = offered.indexOf(prefs.appLanguage).let { if (it < 0) 0 else it }
        val next = offered[(current + 1) % offered.size]
        scope.launch {
            app.preferences.update { it.copy(appLanguage = next) }
            com.puretube.app.ui.LocaleHelper.apply(context, next)
            if (android.os.Build.VERSION.SDK_INT <
                android.os.Build.VERSION_CODES.TIRAMISU
            ) {
                (context as? android.app.Activity)?.recreate()
            }
        }
    }
    fun cycleContentCountry() {
        val values = ContentCountry.entries
        val next = values[(prefs.contentCountry.ordinal + 1) % values.size]
        scope.launch { app.preferences.update { it.copy(contentCountry = next) } }
    }
    fun cycleAudioLanguage() {
        val values = PreferredAudioLanguage.entries
        val next = values[(prefs.preferredAudioLanguage.ordinal + 1) % values.size]
        scope.launch { app.preferences.update { it.copy(preferredAudioLanguage = next) } }
    }
    fun cycleSleepDefault() {
        val options = listOf(0, 5, 10, 15, 30, 45, 60)
        val idx = options.indexOf(prefs.sleepTimerDefaultMinutes).let { if (it < 0) 3 else it }
        val next = options[(idx + 1) % options.size]
        scope.launch { app.preferences.update { it.copy(sleepTimerDefaultMinutes = next) } }
    }
    fun cycleLikes(value: Int?, apply: (Int?) -> Unit) {
        val options = listOf<Int?>(null, 0, 100, 1000, 10000, 100000)
        val idx = options.indexOf(value).let { if (it < 0) 0 else it }
        apply(options[(idx + 1) % options.size])
    }
    fun likesLabel(value: Int?): String = value?.toString() ?: "Any"

    fun cycleControlsVisibility() {
        val values = com.puretube.app.data.local.datastore.ControlsVisibility.entries
        val next = values[(prefs.controlsVisibility.ordinal + 1) % values.size]
        scope.launch { app.preferences.update { it.copy(controlsVisibility = next) } }
    }
    fun cycleLikesDisplay() {
        val values = com.puretube.app.data.local.datastore.LikesDisplay.entries
        val next = values[(prefs.likesDisplay.ordinal + 1) % values.size]
        scope.launch { app.preferences.update { it.copy(likesDisplay = next) } }
    }
    fun cycleStartTab() {
        val values = DefaultStartTab.entries
        val next = values[(prefs.defaultStartTab.ordinal + 1) % values.size]
        scope.launch { app.preferences.update { it.copy(defaultStartTab = next) } }
    }
    fun cycleAspectMode() {
        val values = com.puretube.app.data.local.datastore.VideoAspectMode.entries
        val next = values[(prefs.videoAspectMode.ordinal + 1) % values.size]
        scope.launch { app.preferences.update { it.copy(videoAspectMode = next) } }
    }

    val rows = listOf(
        SettingRowDef("1. HaramBlur Protection", "Protection Scope",
            "Choose whether HaramBlur protects only inside PureTube, across all other apps on your phone (System Shield), or both simultaneously. All scanning runs 100% on your device.") {
            CycleButton(prefs.protectionScope.name) { cycleScope() }
        },
        SettingRowDef("1. HaramBlur Protection", "Who to Blur",
            "Select whether the on-device vision model blurs detected females only (default), males only, or all people in thumbnails and videos. A small gender model is now bundled (v1.5), so this is a real classification of each detected face — see the HaramBlur status block at the top of Settings for the live mode. If the gender model ever fails to load, ALL detected faces are blurred instead (all-faces fallback).") {
            CycleButton(prefs.blurTargetGender.name) { cycleGender() }
        },
        SettingRowDef("1. HaramBlur Protection", "What to Protect",
            "Choose to blur static thumbnails and channel avatars only, live playing video streams only, or both images and videos.") {
            CycleButton(prefs.protectedMediaType.name) { cycleMedia() }
        },
        SettingRowDef("1. HaramBlur Protection", "Blur Visual Style",
            "Choose between a smooth Frosted Gaussian Blur, Pixelated Mosaic blocks, or a solid dark curtain over detected areas.") {
            CycleButton(prefs.blurVisualStyle.name) { cycleStyle() }
        },
        SettingRowDef("1. HaramBlur Protection", "Blur Scope",
            "FACE_ONLY (default) blurs just the face/head box. FACE_AND_BODY widens that box into an approximated person region (about 1.6x wider, extending about 2.5 face-heights down) — it is a geometric estimate from the face box, not a separate body detection, so it can over- or under-cover. Applies to thumbnails, video and the System Shield.") {
            CycleButton(prefs.blurScope.name) { cycleBlurScope() }
        },
        SettingRowDef("1. HaramBlur Protection", "Detection Mode",
            "SMART (default) blurs only the detected regions. FULL SCREEN pixelates the ENTIRE video frame whenever anything is detected, and keeps it covered until the frame has been clear for a moment — use it if targeted boxes ever miss or lag. Applies to video and the System Shield; thumbnails stay targeted.") {
            CycleButton(prefs.blurDetectionMode.name) { cycleBlurMode() }
        },
        SettingRowDef("1. HaramBlur Protection", "Blur Strength",
            "How hard the blur hides what's under it: mosaic block size for Pixelate, tint opacity for Frosted, mask opacity for the Shield. STRONG (default) makes faces unrecognisable; the old fixed rendering was about LIGHT, which is why faces showed through. Applies to thumbnails, video and the Shield.") {
            CycleButton(prefs.blurStrength.name) { cycleBlurStrength() }
        },
        SettingRowDef("1. HaramBlur Protection", "Detection Sensitivity",
            "Higher (Strict) sensitivity blurs even partially visible or uncertain faces; lower (Relaxed) sensitivity only blurs high-confidence matches.") {
            Column(horizontalAlignment = Alignment.End) {
                Text("${"%.2f".format(prefs.detectionSensitivity)}", color = Color.White, fontSize = 12.sp)
                Slider(
                    value = prefs.detectionSensitivity,
                    onValueChange = { v ->
                        scope.launch { app.preferences.update { it.copy(detectionSensitivity = v) } }
                    },
                    valueRange = 0.35f..0.85f,
                    modifier = Modifier.fillMaxWidth(0.55f)
                )
            }
        },
        SettingRowDef("1. HaramBlur Protection", "Blur Intensity",
            "Controls how strong the blur or mosaic effect is over detected areas. Higher values hide more detail.") {
            Column(horizontalAlignment = Alignment.End) {
                Text("${prefs.blurIntensityRadius.toInt()} px", color = Color.White, fontSize = 12.sp)
                Slider(
                    value = prefs.blurIntensityRadius,
                    onValueChange = { v ->
                        scope.launch { app.preferences.update { it.copy(blurIntensityRadius = v) } }
                    },
                    valueRange = 10f..60f,
                    modifier = Modifier.fillMaxWidth(0.55f)
                )
            }
        },
        SettingRowDef("1. HaramBlur Protection", "Zero-Flash Safe Guard",
            "Keeps thumbnails and newly opened video frames pre-blurred for a few milliseconds until the local scanner confirms they are clear, preventing 1-frame leaks.") {
            Switch(
                checked = prefs.zeroFlashSafePreBlur,
                onCheckedChange = { v ->
                    scope.launch { app.preferences.update { it.copy(zeroFlashSafePreBlur = v) } }
                }
            )
        },
        SettingRowDef("2. Playback, PiP & Audio", "1-Tap Audio-Only Mode",
            "Drops the video stream completely while keeping audio playing in the background—saving up to 85% mobile data and battery for lectures and podcasts.") {
            Switch(
                checked = prefs.audioOnlyMode,
                onCheckedChange = { v ->
                    scope.launch { app.preferences.update { it.copy(audioOnlyMode = v) } }
                    app.playerController.setAudioOnly(v)
                }
            )
        },
        SettingRowDef("2. Playback, PiP & Audio", "Launch Videos in Audio-Only",
            "New videos open audio-only: you see the thumbnail and title with an Audio Only label instead of the video picture, which saves data. Tap the Video + Audio chip in the player any time to bring the picture back in place.") {
            Switch(
                checked = prefs.launchInAudioOnly,
                onCheckedChange = { v ->
                    scope.launch { app.preferences.update { it.copy(launchInAudioOnly = v) } }
                }
            )
        },
        SettingRowDef("2. Playback, PiP & Audio", "Auto Picture-in-Picture",
            "Automatically shrinks the video into a floating movable window with Play, Mute, and +10s buttons whenever you press Home or switch apps.") {
            Switch(
                checked = prefs.autoEnterPipOnHome,
                onCheckedChange = { v ->
                    scope.launch { app.preferences.update { it.copy(autoEnterPipOnHome = v) } }
                }
            )
        },
        SettingRowDef("2. Playback, PiP & Audio", "Background Playback",
            "Keeps audio playing when the screen is off or you switch to another app, using the background media service.") {
            Switch(
                checked = prefs.backgroundPlaybackEnabled,
                onCheckedChange = { v ->
                    scope.launch { app.preferences.update { it.copy(backgroundPlaybackEnabled = v) } }
                }
            )
        },
        SettingRowDef("2. Playback, PiP & Audio", "Pocket Volume-Button Skip",
            "Holding Volume Up or Volume Down for 0.6 seconds while your screen is off skips forward or backward by 10 seconds hands-free.") {
            Switch(
                checked = prefs.pocketVolumeLongPressSkip,
                onCheckedChange = { v ->
                    scope.launch { app.preferences.update { it.copy(pocketVolumeLongPressSkip = v) } }
                }
            )
        },
        SettingRowDef("2. Playback, PiP & Audio", "Audio Normalization",
            "Balances quiet speech and sudden loud intros across different channels so you don't have to constantly adjust your volume.") {
            Switch(
                checked = prefs.audioNormalizationEnabled,
                onCheckedChange = { v ->
                    scope.launch { app.preferences.update { it.copy(audioNormalizationEnabled = v) } }
                    app.playerController.setNormalization(v)
                }
            )
        },
        SettingRowDef("2. Playback, PiP & Audio", "Mono Audio Merge",
            "Combines left and right stereo channels into both speakers—ideal when listening with a single earbud.") {
            Switch(
                checked = prefs.monoAudioMergeEnabled,
                onCheckedChange = { v ->
                    scope.launch { app.preferences.update { it.copy(monoAudioMergeEnabled = v) } }
                    app.playerController.setMono(v)
                }
            )
        },
        SettingRowDef("3. Focus & Feed Filters", "Zero Shorts Engine",
            "Completely removes the Shorts tab and Shorts shelves from search and feeds, and converts any opened /shorts/ link into the regular video player.") {
            Switch(
                checked = prefs.stripShortsEverywhere,
                onCheckedChange = { v ->
                    scope.launch { app.preferences.update { it.copy(stripShortsEverywhere = v) } }
                }
            )
        },
        SettingRowDef("3. Focus & Feed Filters", "Collapse Comments & Related",
            "Keeps comments and related video recommendations tucked behind clean 1-tap headers so you aren't pulled into endless scrolling.") {
            Switch(
                checked = prefs.collapseCommentsByDefault && prefs.collapseRelatedByDefault,
                onCheckedChange = { v ->
                    scope.launch {
                        app.preferences.update {
                            it.copy(collapseCommentsByDefault = v, collapseRelatedByDefault = v)
                        }
                    }
                }
            )
        },
        SettingRowDef("3. Focus & Feed Filters", "Comments",
            "Shows the comments section under the player. When this is off, comments are never fetched or shown at all.") {
            Switch(
                checked = prefs.enableComments,
                onCheckedChange = { v ->
                    scope.launch { app.preferences.update { it.copy(enableComments = v) } }
                }
            )
        },
        SettingRowDef("3. Focus & Feed Filters", "Description",
            "Shows the video description section under the player (collapsed by default). When this is off, the section is never shown.") {
            Switch(
                checked = prefs.enableDescription,
                onCheckedChange = { v ->
                    scope.launch { app.preferences.update { it.copy(enableDescription = v) } }
                }
            )
        },
        SettingRowDef("3. Focus & Feed Filters", "Start Tab",
            "Chooses which tab PureTube opens to on launch: Subscriptions, Search, or Library.") {
            CycleButton(prefs.defaultStartTab.name) { cycleStartTab() }
        },
        SettingRowDef("3. Focus & Feed Filters", "Night OLED Dimmer",
            "Adds an extra black dimming layer over the whole app for late-night viewing on OLED screens.") {
            Slider(
                value = prefs.nightOledDimmerAlpha,
                onValueChange = { v ->
                    scope.launch { app.preferences.update { it.copy(nightOledDimmerAlpha = v) } }
                },
                valueRange = 0f..0.75f,
                modifier = Modifier.fillMaxWidth(0.55f)
            )
        },
        SettingRowDef("4. SponsorBlock & Backup", "SponsorBlock Auto-Skip",
            "Automatically skips paid sponsor reads, intros, outros, and like/subscribe reminders using privacy-preserving hash lookups.") {
            Switch(
                checked = prefs.sponsorBlockEnabled,
                onCheckedChange = { v ->
                    scope.launch { app.preferences.update { it.copy(sponsorBlockEnabled = v) } }
                }
            )
        },
        SettingRowDef("4. SponsorBlock & Backup", "DeArrow Anti-Clickbait",
            "Replaces sensational ALL-CAPS clickbait titles and exaggerated thumbnails with neutral, community-submitted titles and video frames.") {
            Switch(
                checked = prefs.deArrowTitlesEnabled || prefs.deArrowThumbnailsEnabled,
                onCheckedChange = { v ->
                    scope.launch {
                        app.preferences.update {
                            it.copy(deArrowTitlesEnabled = v, deArrowThumbnailsEnabled = v)
                        }
                    }
                }
            )
        },
        SettingRowDef("4. SponsorBlock & Backup", "Return YouTube Dislike",
            "Shows community-estimated dislike counts next to the like count on the player screen.") {
            Switch(
                checked = prefs.returnYouTubeDislikeEnabled,
                onCheckedChange = { v ->
                    scope.launch { app.preferences.update { it.copy(returnYouTubeDislikeEnabled = v) } }
                }
            )
        },
        SettingRowDef("4. SponsorBlock & Backup", "Backup Piped API URL",
            "Fallback extraction endpoint used only when the primary on-device extractor cannot resolve a stream.") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = pipedUrlDraft,
                    onValueChange = { pipedUrlDraft = it },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(0.6f)
                )
                Button(onClick = {
                    scope.launch {
                        app.preferences.update { it.copy(fallbackPipedApiUrl = pipedUrlDraft.trim()) }
                    }
                }) { Text("Save", fontSize = 12.sp) }
            }
        },
        SettingRowDef("2. Playback, PiP & Audio", "Autoplay Next",
            "When a video ends, PureTube automatically plays the next queued video, or a short-enough video from the same channel when the queue is empty.") {
            Switch(
                checked = prefs.autoplayEnabled,
                onCheckedChange = { v -> scope.launch { app.preferences.update { it.copy(autoplayEnabled = v) } } }
            )
        },
        SettingRowDef("2. Playback, PiP & Audio", "Resume Playback",
            "Remembers where you stopped each video and picks up from there next time. Turn this off to always start videos from the beginning.") {
            Switch(
                checked = prefs.resumePlaybackEnabled,
                onCheckedChange = { v -> scope.launch { app.preferences.update { it.copy(resumePlaybackEnabled = v) } } }
            )
        },
        SettingRowDef("2. Playback, PiP & Audio", "Always Start From Beginning",
            "Ignores saved positions and starts every video at 0:00, even when Resume Playback is on.") {
            Switch(
                checked = prefs.alwaysStartFromBeginning,
                onCheckedChange = { v -> scope.launch { app.preferences.update { it.copy(alwaysStartFromBeginning = v) } } }
            )
        },
        SettingRowDef("2. Playback, PiP & Audio", "Resume After Interruption",
            "When a call or another app interrupts playback, PureTube tries to resume playing on its own afterwards instead of staying paused.") {
            Switch(
                checked = prefs.resumeAfterInterruption,
                onCheckedChange = { v -> scope.launch { app.preferences.update { it.copy(resumeAfterInterruption = v) } } }
            )
        },
        SettingRowDef("2. Playback, PiP & Audio", "Require Audio Focus",
            "Pauses PureTube when another app starts playing sound, and lets other apps pause PureTube. Recommended on; turning it off can cause overlapping audio.") {
            Switch(
                checked = prefs.requireAudioFocus,
                onCheckedChange = { v ->
                    scope.launch { app.preferences.update { it.copy(requireAudioFocus = v) } }
                    app.playerController.setRequireAudioFocus(v)
                }
            )
        },
        SettingRowDef("2. Playback, PiP & Audio", "Disable Android Auto",
            "Keeps PureTube out of Android Auto so the car display never offers it. On by default; turn off only if you want to control playback from the car.") {
            Switch(
                checked = prefs.disableAndroidAuto,
                onCheckedChange = { v -> scope.launch { app.preferences.update { it.copy(disableAndroidAuto = v) } } }
            )
        },
        SettingRowDef("2. Playback, PiP & Audio", "Sleep Timer Default",
            "The sleep timer length preselected when you open a video. When it runs out, playback pauses and shows Sleep timer ended.") {
            CycleButton(if (prefs.sleepTimerDefaultMinutes <= 0) "Off" else "${prefs.sleepTimerDefaultMinutes} min") { cycleSleepDefault() }
        },
        SettingRowDef("2. Playback, PiP & Audio", "Player Controls Visibility",
            "Auto-hide fades the player controls (chips + seek bar) after about 3 seconds while a video is playing; a tap brings them back. Always visible keeps them on screen.") {
            CycleButton(prefs.controlsVisibility.name) { cycleControlsVisibility() }
        },
        SettingRowDef("2. Playback, PiP & Audio", "Auto-Rotate Player",
            "When on, the player screen rotates freely with your phone (including landscape). When off, the player stays locked to portrait. Other screens are never affected. In landscape the player is fullscreen: the bottom navigation hides until you rotate back.") {
            Switch(
                checked = prefs.autoRotatePlayer,
                onCheckedChange = { v -> scope.launch { app.preferences.update { it.copy(autoRotatePlayer = v) } } }
            )
        },
        SettingRowDef("2. Playback, PiP & Audio", "Swipe Brightness/Volume Controls",
            if (prefs.swipeBrightnessVolumeControls)
                "ON: on the full player, drag up/down on the LEFT third of the video for brightness and the RIGHT third for volume; drags in the CENTRE third (or on the pill handle above the title) minimize the player. Drag up on the mini player to expand it again."
            else
                "OFF (default): vertical drags on the video do NOT change brightness or volume. Instead, drag DOWN anywhere on the video to minimize it to the mini player, and drag UP on the mini player to expand it back. Turn this on to get left-side brightness / right-side volume swipe controls (minimize drags then start from the centre third or the pill handle).") {
            Switch(
                checked = prefs.swipeBrightnessVolumeControls,
                onCheckedChange = { v -> scope.launch { app.preferences.update { it.copy(swipeBrightnessVolumeControls = v) } } }
            )
        },
        SettingRowDef("2. Playback, PiP & Audio", "Unmute Raises Volume",
            "If your phone's media volume is all the way down, tapping Unmute also raises it to about 40% so unmuting actually makes sound. The player's own mute state is unchanged either way.") {
            Switch(
                checked = prefs.unmuteRaisesVolume,
                onCheckedChange = { v -> scope.launch { app.preferences.update { it.copy(unmuteRaisesVolume = v) } } }
            )
        },
        SettingRowDef("2. Playback, PiP & Audio", "Keep Video Pinned While Scrolling",
            "The video, seek bar and control chips stay fixed at the top while the title, description and comments scroll underneath. Turn off for the classic single scrolling page.") {
            Switch(
                checked = prefs.keepVideoPinnedWhileScrolling,
                onCheckedChange = { v -> scope.launch { app.preferences.update { it.copy(keepVideoPinnedWhileScrolling = v) } } }
            )
        },
        SettingRowDef("2. Playback, PiP & Audio", "Pause When Volume Hits Zero",
            "Master switch for the volume-zero rule: when your phone's media volume reaches zero while a video is playing, PureTube reacts (see the next setting). Turn this off to disable the rule completely.") {
            Switch(
                checked = prefs.pauseOnVolumeZero,
                onCheckedChange = { v -> scope.launch { app.preferences.update { it.copy(pauseOnVolumeZero = v) } } }
            )
        },
        SettingRowDef("2. Playback, PiP & Audio", "When Volume Hits Zero",
            "What the volume-zero rule does first: Pause only (default), jump back 5, 10 or 15 seconds and keep playing silently, or jump back 10 seconds AND pause. The stop rule below still applies while the volume stays at zero.") {
            val labels = mapOf(
                com.puretube.app.data.local.datastore.VolumeZeroAction.PAUSE_ONLY to "Pause only",
                com.puretube.app.data.local.datastore.VolumeZeroAction.REWIND_5 to "Rewind 5s",
                com.puretube.app.data.local.datastore.VolumeZeroAction.REWIND_10 to "Rewind 10s",
                com.puretube.app.data.local.datastore.VolumeZeroAction.REWIND_15 to "Rewind 15s",
                com.puretube.app.data.local.datastore.VolumeZeroAction.REWIND_AND_PAUSE to "Rewind + pause"
            )
            CycleButton(labels[prefs.volumeZeroAction] ?: "Pause only") {
                val values = com.puretube.app.data.local.datastore.VolumeZeroAction.entries
                val next = values[(prefs.volumeZeroAction.ordinal + 1) % values.size]
                scope.launch { app.preferences.update { it.copy(volumeZeroAction = next) } }
            }
        },
        SettingRowDef("2. Playback, PiP & Audio", "Resume When Volume Returns",
            "OFF (default): after a volume-zero pause, raising the volume never restarts playback — press Play yourself. ON: if the volume-zero rule paused the video, playback continues by itself when the volume comes back. This never resumes a video you paused yourself, and never restarts a video the stop rule already stopped.") {
            Switch(
                checked = prefs.resumeOnVolumeReturn,
                onCheckedChange = { v -> scope.launch { app.preferences.update { it.copy(resumeOnVolumeReturn = v) } } }
            )
        },
        SettingRowDef("2. Playback, PiP & Audio", "Stop If Volume Stays Zero",
            "If the media volume stays at zero continuously for this long after the volume-zero pause, playback stops completely (your position is saved first). Restarting is always manual. Never = pause only, never stop.") {
            val options = listOf(0, 15, 30, 60, 300)
            val labels = mapOf(0 to "Never", 15 to "15 sec", 30 to "30 sec", 60 to "1 min", 300 to "5 min")
            CycleButton(labels[prefs.volumeZeroStopSeconds] ?: "${prefs.volumeZeroStopSeconds} sec") {
                val idx = options.indexOf(prefs.volumeZeroStopSeconds).let { if (it < 0) 2 else it }
                val next = options[(idx + 1) % options.size]
                scope.launch { app.preferences.update { it.copy(volumeZeroStopSeconds = next) } }
            }
        },
        SettingRowDef("2. Playback, PiP & Audio", "Video Aspect Mode",
            "How the video picture fits the player. Fit (default) shows the whole picture undistorted with black bars where needed. Zoom fills the screen by centre-cropping the edges. Stretch fills the screen by distorting the picture. The HaramBlur overlay follows whichever rect the picture actually occupies.") {
            CycleButton(
                prefs.videoAspectMode.name.lowercase().replaceFirstChar { it.uppercase() }
            ) { cycleAspectMode() }
        },
        SettingRowDef("2. Playback, PiP & Audio", "Show Title in Fullscreen",
            "ON (default): the video title and channel name appear at the top of the fullscreen player when the controls are visible. OFF: no title overlay in fullscreen — just the video and controls. Either way the title respects your phone's curved edges and camera cutout.") {
            Switch(
                checked = prefs.showTitleInFullscreen,
                onCheckedChange = { v -> scope.launch { app.preferences.update { it.copy(showTitleInFullscreen = v) } } }
            )
        },
        SettingRowDef("3. Focus & Feed Filters", "Double-Tap Search Resets",
            "Tapping the Search tab while you are already on Search clears the query, results, filter and scroll position for a fresh start.") {
            Switch(
                checked = prefs.doubleTapSearchResets,
                onCheckedChange = { v -> scope.launch { app.preferences.update { it.copy(doubleTapSearchResets = v) } } }
            )
        },
        SettingRowDef("4. SponsorBlock & Backup", "Likes Display",
            "How the player shows like and dislike counts from Return YouTube Dislike: raw numbers, a percentage liked, or both.") {
            CycleButton(prefs.likesDisplay.name) { cycleLikesDisplay() }
        },
        SettingRowDef("2. Playback, PiP & Audio", "Video Frame Sampling",
            "The live-video scanner that checks playing frames 12 times a second. Turning this off disables live-video blur scanning (thumbnails still scan) and can help if the player ever feels heavy.") {
            Switch(
                checked = prefs.videoFrameSamplingEnabled,
                onCheckedChange = { v -> scope.launch { app.preferences.update { it.copy(videoFrameSamplingEnabled = v) } } }
            )
        },
        SettingRowDef("3. Focus & Feed Filters", "Feed Layout",
            "How video lists look everywhere: full cards, a 2-column grid, thumbnails only, or compact title-only rows.") {
            CycleButton(prefs.feedLayout.name) { cycleFeedLayout() }
        },
        SettingRowDef("3. Focus & Feed Filters", "Minimum Likes (videos)",
            "Hides videos with fewer likes than this from search and feeds, when the like count is known. Any means no minimum.") {
            CycleButton(likesLabel(prefs.contentMinLikes)) {
                cycleLikes(prefs.contentMinLikes) { v -> scope.launch { app.preferences.update { it.copy(contentMinLikes = v) } } }
            }
        },
        SettingRowDef("3. Focus & Feed Filters", "Maximum Likes (videos)",
            "Hides videos with more likes than this from search and feeds, when the like count is known. Any means no maximum.") {
            CycleButton(likesLabel(prefs.contentMaxLikes)) {
                cycleLikes(prefs.contentMaxLikes) { v -> scope.launch { app.preferences.update { it.copy(contentMaxLikes = v) } } }
            }
        },
        SettingRowDef("3. Focus & Feed Filters", "Minimum Likes (comments)",
            "Hides comments with fewer likes than this under the player. Any means no minimum.") {
            CycleButton(likesLabel(prefs.commentMinLikes)) {
                cycleLikes(prefs.commentMinLikes) { v -> scope.launch { app.preferences.update { it.copy(commentMinLikes = v) } } }
            }
        },
        SettingRowDef("3. Focus & Feed Filters", "Maximum Likes (comments)",
            "Hides comments with more likes than this under the player. Any means no maximum.") {
            CycleButton(likesLabel(prefs.commentMaxLikes)) {
                cycleLikes(prefs.commentMaxLikes) { v -> scope.launch { app.preferences.update { it.copy(commentMaxLikes = v) } } }
            }
        },
        SettingRowDef("4. SponsorBlock & Backup", "Notify When Skipping",
            "Shows the small Skipped pill with an Undo button each time SponsorBlock jumps over a segment. The skip still happens when this is off; only the note is hidden.") {
            Switch(
                checked = prefs.sponsorBlockNotifyOnSkip,
                onCheckedChange = { v -> scope.launch { app.preferences.update { it.copy(sponsorBlockNotifyOnSkip = v) } } }
            )
        },
        SettingRowDef("4. SponsorBlock & Backup", "Don't Re-Skip After Rewind",
            "If you manually rewind back into a segment that was already skipped, PureTube leaves it alone for the rest of that video instead of skipping it again.") {
            Switch(
                checked = prefs.sponsorBlockPauseSkipOnRewind,
                onCheckedChange = { v -> scope.launch { app.preferences.update { it.copy(sponsorBlockPauseSkipOnRewind = v) } } }
            )
        },
        SettingRowDef("5. History, Queue & Data", "Watch History",
            "Saves the videos you watch into Library history with resume positions. Turn off to watch without leaving a history list.") {
            Switch(
                checked = prefs.watchHistoryEnabled,
                onCheckedChange = { v -> scope.launch { app.preferences.update { it.copy(watchHistoryEnabled = v) } } }
            )
        },
        SettingRowDef("5. History, Queue & Data", "Search History",
            "Remembers your searches on this device so they can be offered as suggestions. Nothing leaves the phone.") {
            Switch(
                checked = prefs.searchHistoryEnabled,
                onCheckedChange = { v -> scope.launch { app.preferences.update { it.copy(searchHistoryEnabled = v) } } }
            )
        },
        SettingRowDef("5. History, Queue & Data", "Search Suggestions",
            "Shows your recent searches on the empty Search screen. Off by default, which keeps the empty search screen completely clean.") {
            Switch(
                checked = prefs.suggestionsEnabled,
                onCheckedChange = { v -> scope.launch { app.preferences.update { it.copy(suggestionsEnabled = v) } } }
            )
        },
        SettingRowDef("5. History, Queue & Data", "History Visible Days",
            "Only shows history from the last N days in Library. Older entries are hidden, not deleted. 0 shows everything.") {
            CycleButton(if (prefs.historyVisibleDays <= 0) "All" else "${prefs.historyVisibleDays} days") {
                val options = listOf(0, 7, 30, 90, 365)
                val idx = options.indexOf(prefs.historyVisibleDays).let { if (it < 0) 0 else it }
                val next = options[(idx + 1) % options.size]
                scope.launch { app.preferences.update { it.copy(historyVisibleDays = next) } }
            }
        },
        SettingRowDef("5. History, Queue & Data", "Clear Queue on Exit",
            "Empties the Up Next queue when you close PureTube. Turn off to keep the queue between sessions.") {
            Switch(
                checked = prefs.resetQueueOnExit,
                onCheckedChange = { v -> scope.launch { app.preferences.update { it.copy(resetQueueOnExit = v) } } }
            )
        },
        SettingRowDef("5. History, Queue & Data", "Auto-Queue Short Videos Only",
            "When autoplay picks the next video from the same channel, only short videos are considered.") {
            Switch(
                checked = prefs.autoQueueShortOnly,
                onCheckedChange = { v -> scope.launch { app.preferences.update { it.copy(autoQueueShortOnly = v) } } }
            )
        },
        SettingRowDef("5. History, Queue & Data", "Auto-Queue Max Minutes",
            "Autoplay never queues a video longer than this many minutes when it picks from the same channel.") {
            CycleButton("${prefs.autoQueueMaxMinutes} min") {
                val options = listOf(3, 6, 10, 20, 60)
                val idx = options.indexOf(prefs.autoQueueMaxMinutes).let { if (it < 0) 1 else it }
                val next = options[(idx + 1) % options.size]
                scope.launch { app.preferences.update { it.copy(autoQueueMaxMinutes = next) } }
            }
        },
        SettingRowDef("5. History, Queue & Data", "Feed Refresh Interval",
            "The Subscriptions feed reuses each channel's last loaded videos until this many minutes pass, so opening the app stays fast. Refresh always fetches fresh videos.") {
            CycleButton("${prefs.feedUpdateThresholdMinutes} min") {
                val options = listOf(15, 30, 60, 120, 360)
                val idx = options.indexOf(prefs.feedUpdateThresholdMinutes).let { if (it < 0) 2 else it }
                val next = options[(idx + 1) % options.size]
                scope.launch { app.preferences.update { it.copy(feedUpdateThresholdMinutes = next) } }
            }
        },
        SettingRowDef("6. Language & Region", "App Language",
            "The language for PureTube's main screens — the bottom navigation, Settings headings and player buttons change immediately. Longer help text, tooltips and diagnostics stay in English. System default follows your phone's language.") {
            CycleButton(appLanguageLabel(prefs.appLanguage)) { cycleAppLanguage() }
        },
        SettingRowDef("6. Language & Region", "Content Country",
            "The country PureTube asks the video service to localize results for, where the service supports it.") {
            CycleButton(prefs.contentCountry.name) { cycleContentCountry() }
        },
        SettingRowDef("6. Language & Region", "Preferred Audio Language",
            "When a video has several audio tracks, PureTube prefers this language. Original keeps the uploader's main track at the best quality.") {
            CycleButton(prefs.preferredAudioLanguage.name) { cycleAudioLanguage() }
        },
        SettingRowDef("8. Captions", "Caption Text Size",
            "How large caption text appears over the video: Small, Medium, Large or Extra large. Pick your caption language per video from the Captions button in the player; PureTube remembers it for the next video.") {
            val labels = mapOf(
                com.puretube.app.data.local.datastore.CaptionTextSize.SMALL to "Small",
                com.puretube.app.data.local.datastore.CaptionTextSize.MEDIUM to "Medium",
                com.puretube.app.data.local.datastore.CaptionTextSize.LARGE to "Large",
                com.puretube.app.data.local.datastore.CaptionTextSize.EXTRA_LARGE to "Extra large"
            )
            CycleButton(labels[prefs.captionTextSize] ?: "Medium") {
                val values = com.puretube.app.data.local.datastore.CaptionTextSize.entries
                val next = values[(prefs.captionTextSize.ordinal + 1) % values.size]
                scope.launch { app.preferences.update { it.copy(captionTextSize = next) } }
            }
        },
        SettingRowDef("8. Captions", "Caption Text Colour",
            "The colour of caption text over the video: White (default), Yellow or Cyan.") {
            val labels = mapOf(
                com.puretube.app.data.local.datastore.CaptionTextColor.WHITE to "White",
                com.puretube.app.data.local.datastore.CaptionTextColor.YELLOW to "Yellow",
                com.puretube.app.data.local.datastore.CaptionTextColor.CYAN to "Cyan"
            )
            CycleButton(labels[prefs.captionTextColor] ?: "White") {
                val values = com.puretube.app.data.local.datastore.CaptionTextColor.entries
                val next = values[(prefs.captionTextColor.ordinal + 1) % values.size]
                scope.launch { app.preferences.update { it.copy(captionTextColor = next) } }
            }
        },
        SettingRowDef("8. Captions", "Caption Background",
            "What sits behind caption text: Nothing (text only), a Translucent dark band (default), or a solid Black band for maximum readability.") {
            val labels = mapOf(
                com.puretube.app.data.local.datastore.CaptionBackground.NONE to "None",
                com.puretube.app.data.local.datastore.CaptionBackground.TRANSLUCENT to "Translucent",
                com.puretube.app.data.local.datastore.CaptionBackground.BLACK to "Black"
            )
            CycleButton(labels[prefs.captionBackground] ?: "Translucent") {
                val values = com.puretube.app.data.local.datastore.CaptionBackground.entries
                val next = values[(prefs.captionBackground.ordinal + 1) % values.size]
                scope.launch { app.preferences.update { it.copy(captionBackground = next) } }
            }
        },
        SettingRowDef("8. Captions", "Bold Caption Text",
            "Renders caption text in bold, which some people find easier to read over bright video.") {
            Switch(
                checked = prefs.captionBold,
                onCheckedChange = { v -> scope.launch { app.preferences.update { it.copy(captionBold = v) } } }
            )
        },
        SettingRowDef("3. Focus & Feed Filters", "Thumbnail Scale",
            "How large video thumbnails and cards are in search and feeds: Compact, Normal, or Large.") {
            CycleButton(prefs.feedThumbnailScale.name) { cycleThumbnailScale() }
        },
        SettingRowDef("3. Focus & Feed Filters", "Grid Columns",
            "How many columns the Grid layout uses. More columns means smaller thumbnails.") {
            CycleButton("${prefs.gridColumns} columns") { cycleGridColumns() }
        },
        SettingRowDef("5. History, Queue & Data", "History Display",
            "Grouped by day shows one section per calendar day (Today, Yesterday, then each date). Continuous shows history as one flat newest-first list.") {
            CycleButton(
                if (prefs.historyGroupMode == HistoryGroupMode.GROUPED_BY_DAY) "Grouped by day" else "Continuous"
            ) { cycleHistoryGroupMode() }
        }
    )

    val filtered = if (query.isBlank()) rows else rows.filter {
        it.title.contains(query, ignoreCase = true) || it.section.contains(query, ignoreCase = true)
    }
    val sections = filtered.groupBy { it.section }.toSortedMap(
        compareBy { section ->
            val idx = SectionResets.sectionsInOrder.indexOf(section)
            if (idx < 0) 99 else idx
        }
    )

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
        contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            Column {
                Text(stringResource(com.puretube.app.R.string.settings_title), color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = { Text(stringResource(com.puretube.app.R.string.settings_filter_hint)) },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 10.dp)
                )
                Text(
                    "To run the System Shield, set Protection Scope to include it, then use the HaramBlur Shield quick-settings tile or reopen the app - Android asks for screen-capture consent first, every time.",
                    color = PureTextSecondary,
                    fontSize = 12.sp,
                    lineHeight = 17.sp,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
        }
        item(key = "haramblur-status") {
            val hbStatus by app.haramBlurEngine.statusFlow.collectAsState()
            var hbTestResult by remember { mutableStateOf("") }
            androidx.compose.runtime.LaunchedEffect(Unit) {
                runCatching { app.haramBlurEngine.ensureInitialized() }
            }
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, com.puretube.app.ui.theme.PureBorder, RoundedCornerShape(10.dp))
                    .padding(12.dp)
            ) {
                Text("HaramBlur status", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    "Face model: ${if (hbStatus.faceModelLoaded) "Loaded" else "Not loaded"}",
                    color = PureTextSecondary, fontSize = 13.sp
                )
                Text(
                    "Mode: ${hbStatus.mode}",
                    color = PureTextSecondary, fontSize = 13.sp
                )
                hbStatus.lastError?.let { err ->
                    Text("Last load error: $err", color = Color(0xFFFF8080), fontSize = 12.sp)
                }
                // v1.4 sampler diagnostics (TextureView capture path).
                val samplerDiag by app.samplerDiagnostics.collectAsState()
                val playerDiagState by app.playerController.state.collectAsState()
                Spacer(modifier = Modifier.height(6.dp))
                Text("Video sampler", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                Text(
                    "Sampler running: ${if (samplerDiag.samplerRunning) "Yes" else "No"}",
                    color = PureTextSecondary, fontSize = 13.sp
                )
                Text(
                    "Surface size: ${samplerDiag.surfaceWidth} x ${samplerDiag.surfaceHeight}",
                    color = PureTextSecondary, fontSize = 13.sp
                )
                Text(
                    "Video size: ${playerDiagState.videoWidth} x ${playerDiagState.videoHeight}",
                    color = PureTextSecondary, fontSize = 13.sp
                )
                Text(
                    "Frames sampled: ${samplerDiag.framesSampled}",
                    color = PureTextSecondary, fontSize = 13.sp
                )
                Text(
                    "Last frame brightness: ${if (samplerDiag.lastFrameBrightness >= 0) "${samplerDiag.lastFrameBrightness} / 255" else "—"}",
                    color = PureTextSecondary, fontSize = 13.sp
                )
                Text(
                    "Last detection count: ${if (samplerDiag.lastDetectionCount >= 0) samplerDiag.lastDetectionCount.toString() else "—"}",
                    color = PureTextSecondary, fontSize = 13.sp
                )
                Text(
                    "Last detection pass: ${samplerDiag.lastPassType}",
                    color = PureTextSecondary, fontSize = 13.sp
                )
                Text(
                    "Total detections since play start: ${samplerDiag.totalDetections}",
                    color = PureTextSecondary, fontSize = 13.sp
                )
                Text(
                    "Regions drawn by overlay: ${samplerDiag.overlayRegionsDrawn}",
                    color = PureTextSecondary, fontSize = 13.sp
                )
                Text(
                    "Sample sequence: sampled ${samplerDiag.sampledSequence} / drawn ${samplerDiag.drawnSequence}",
                    color = PureTextSecondary, fontSize = 13.sp
                )
                Text(
                    "While a video plays, 'sampled' should keep climbing and 'drawn' should track it. If sampled climbs but drawn stays stuck, the overlay is frozen — that is the v2.0 bug v2.1 fixed.",
                    color = PureTextSecondary, fontSize = 12.sp
                )
                Text(
                    "Last sample duration: ${samplerDiag.lastSampleDurationMs} ms",
                    color = PureTextSecondary, fontSize = 13.sp
                )
                samplerDiag.lastError?.let { err ->
                    Text("Last sampler error: $err", color = Color(0xFFFF8080), fontSize = 12.sp)
                }
                Spacer(modifier = Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = {
                        scope.launch {
                            hbTestResult = "Testing…"
                            hbTestResult = app.haramBlurEngine.runTestDetection()
                        }
                    }) { Text("Test detection", fontSize = 12.sp) }
                }
                if (hbTestResult.isNotBlank()) {
                    Text(hbTestResult, color = PureTextSecondary, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp))
                }
            }
        }
        item(key = "haramblur-exemptions") {
            val allFilters by app.database.blocklistDao().observeAll()
                .collectAsState(initial = emptyList())
            val exemptions = allFilters.filter {
                it.filterType == com.puretube.app.data.local.entity.FilterType.EXEMPT_CHANNEL
            }
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, com.puretube.app.ui.theme.PureBorder, RoundedCornerShape(10.dp))
                    .padding(12.dp)
            ) {
                Text(
                    "HaramBlur channel exemptions",
                    color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Medium
                )
                Text(
                    "These channels are never blurred (thumbnails or video). Add one from a video's \"Don't blur this channel\" button or the channel screen.",
                    color = PureTextSecondary, fontSize = 12.sp, lineHeight = 17.sp,
                    modifier = Modifier.padding(top = 4.dp)
                )
                if (exemptions.isEmpty()) {
                    Text("No exemptions yet.", color = PureTextSecondary, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp))
                }
                exemptions.forEach { entry ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 6.dp)
                    ) {
                        Text(
                            entry.pattern, color = Color.White, fontSize = 13.sp,
                            modifier = Modifier.weight(1f)
                        )
                        androidx.compose.material3.TextButton(onClick = {
                            scope.launch { runCatching { app.database.blocklistDao().delete(entry.id) } }
                        }) { Text("Remove", fontSize = 12.sp) }
                    }
                }
            }
        }
        item(key = "account-block") {
            AccountSectionBlock(app = app)
        }
        sections.forEach { (section, sectionRows) ->
            item(key = "header-$section") {
                val isOpen = expanded.value[section] ?: false
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            expanded.value = expanded.value + (section to !isOpen)
                        }
                        .padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        localizedSectionTitle(section),
                        color = Color.White,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f)
                    )
                    Text(if (isOpen) "Hide" else "Show", color = PureTextSecondary, fontSize = 13.sp)
                    androidx.compose.material3.TextButton(onClick = {
                        scope.launch {
                            app.preferences.update { SectionResets.reset(it, section) }
                        }
                        dataStatus = "Reset $section to defaults."
                    }) { Text("Reset section", fontSize = 12.sp) }
                }
            }
            if (expanded.value[section] ?: false) {
                items(sectionRows.size, key = { idx -> "$section-${sectionRows[idx].title}" }) { idx ->
                    val row = sectionRows[idx]
                    SettingRowWithTooltip(
                        title = row.title,
                        tooltipExplanation = row.tooltip
                    ) { row.content() }
                }
            }
        }
        item(key = "diagnostics-block") {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, com.puretube.app.ui.theme.PureBorder, RoundedCornerShape(10.dp))
                    .padding(12.dp)
            ) {
                Text("7. " + stringResource(com.puretube.app.R.string.section_diagnostics), color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(6.dp))
                if (crashText.isBlank()) {
                    Text("No crash has been recorded on this device.", color = PureTextSecondary, fontSize = 13.sp)
                } else {
                    Text("Last crash:", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                    Text(
                        crashText.take(3000),
                        color = PureTextSecondary, fontSize = 11.sp, lineHeight = 15.sp,
                        modifier = Modifier.padding(vertical = 6.dp)
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = {
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            clipboard.setPrimaryClip(ClipData.newPlainText("PureTube last crash", crashText))
                            dataStatus = "Crash report copied."
                        }) { Text("Copy", fontSize = 12.sp) }
                        Button(onClick = {
                            LastCrashStore.clear(context)
                            crashText = ""
                            dataStatus = "Crash report cleared."
                        }) { Text("Clear", fontSize = 12.sp) }
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { importSettingsLauncher.launch(arrayOf("application/json", "*/*")) }) {
                        Text("Import settings", fontSize = 12.sp)
                    }
                    Button(onClick = { exportSettingsLauncher.launch("puretube-settings.json") }) {
                        Text("Export settings", fontSize = 12.sp)
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { importSubsLauncher.launch(arrayOf("application/json", "text/csv", "text/plain", "*/*")) }) {
                        Text("Import subs", fontSize = 12.sp)
                    }
                    Button(onClick = { exportSubsLauncher.launch("puretube-subscriptions.json") }) {
                        Text("Export subs", fontSize = 12.sp)
                    }
                    Button(onClick = {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setPrimaryClip(
                            ClipData.newPlainText("PureTube settings", SettingsJson.export(prefs))
                        )
                        dataStatus = "Settings copied to clipboard as JSON."
                    }) { Text("Copy settings", fontSize = 12.sp) }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = {
                        scope.launch { app.database.subscriptionDao().clearAll() }
                        dataStatus = "Subscriptions cleared."
                    }) { Text("Clear subscriptions", fontSize = 12.sp) }
                    Button(onClick = {
                        scope.launch { app.database.searchHistoryDao().clearAll() }
                        dataStatus = "Search history cleared."
                    }) { Text("Clear search history", fontSize = 12.sp) }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = {
                        scope.launch { app.database.watchProgressDao().resetAllPositions() }
                        dataStatus = "All playback positions deleted."
                    }) { Text("Delete all playback positions", fontSize = 12.sp) }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { confirmResetAll = true }) {
                        Text("Reset all settings", fontSize = 12.sp)
                    }
                }
                if (dataStatus.isNotBlank()) {
                    Text(dataStatus, color = PureTextSecondary, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp))
                }
            }
        }
    }

    if (confirmResetAll) {
        AlertDialog(
            onDismissRequest = { confirmResetAll = false },
            title = { Text("Reset all settings?") },
            text = { Text("Every PureTube setting returns to its default. Subscriptions, playlists and history are not touched.") },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = {
                    confirmResetAll = false
                    scope.launch { app.preferences.replaceAll(AppPreferencesState()) }
                    dataStatus = "All settings reset to defaults."
                }) { Text("Reset") }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { confirmResetAll = false }) { Text("Cancel") }
            }
        )
    }
}
