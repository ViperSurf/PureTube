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
import com.puretube.app.ui.components.SettingRowWithTooltip
import com.puretube.app.ui.theme.PureTextSecondary
import kotlinx.coroutines.launch

private data class SettingRowDef(
    val section: String,
    val title: String,
    val tooltip: String,
    val content: @Composable () -> Unit
)

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
                val parsed = text?.let {
                    runCatching { app.importExportManager.parseAny(it) }.getOrDefault(emptyList())
                }.orEmpty()
                if (parsed.isNotEmpty()) app.database.subscriptionDao().upsertAll(parsed)
                dataStatus = if (parsed.isEmpty()) "No subscriptions found in that file."
                else "Imported ${parsed.size} subscriptions from file."
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
        val values = AppLanguage.entries
        val next = values[(prefs.appLanguage.ordinal + 1) % values.size]
        scope.launch { app.preferences.update { it.copy(appLanguage = next) } }
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

    fun cycleStartTab() {
        val values = DefaultStartTab.entries
        val next = values[(prefs.defaultStartTab.ordinal + 1) % values.size]
        scope.launch { app.preferences.update { it.copy(defaultStartTab = next) } }
    }

    val rows = listOf(
        SettingRowDef("1. HaramBlur Protection", "Protection Scope",
            "Choose whether HaramBlur protects only inside PureTube, across all other apps on your phone (System Shield), or both simultaneously. All scanning runs 100% on your device.") {
            CycleButton(prefs.protectionScope.name) { cycleScope() }
        },
        SettingRowDef("1. HaramBlur Protection", "Who to Blur",
            "Select whether the on-device vision model blurs detected females only (default), males only, or all people in thumbnails and videos.") {
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
            "The language PureTube tries to use for its own screens. Applies after you fully close and reopen the app.") {
            CycleButton(prefs.appLanguage.name) { cycleAppLanguage() }
        },
        SettingRowDef("6. Language & Region", "Content Country",
            "The country PureTube asks the video service to localize results for, where the service supports it.") {
            CycleButton(prefs.contentCountry.name) { cycleContentCountry() }
        },
        SettingRowDef("6. Language & Region", "Preferred Audio Language",
            "When a video has several audio tracks, PureTube prefers this language. Original keeps the uploader's main track at the best quality.") {
            CycleButton(prefs.preferredAudioLanguage.name) { cycleAudioLanguage() }
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
                Text("Settings", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = { Text("Filter settings") },
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
                        section,
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
                Text("7. Diagnostics & Data Actions", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold)
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
