package com.puretube.app.ui.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
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
import com.puretube.app.PureTubeApp
import com.puretube.app.data.local.datastore.BlurTargetGender
import com.puretube.app.data.local.datastore.BlurVisualStyle
import com.puretube.app.data.local.datastore.DefaultStartTab
import com.puretube.app.data.local.datastore.ProtectedMediaType
import com.puretube.app.data.local.datastore.ProtectionScope
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
    var pipedUrlDraft by remember(prefs.fallbackPipedApiUrl) { mutableStateOf(prefs.fallbackPipedApiUrl) }
    val expanded = remember {
        mutableStateOf(
            mapOf(
                "1. HaramBlur Protection" to true,
                "2. Playback, PiP & Audio" to true,
                "3. Focus & Feed Filters" to true,
                "4. SponsorBlock & Backup" to true
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
        }
    )

    val filtered = if (query.isBlank()) rows else rows.filter {
        it.title.contains(query, ignoreCase = true) || it.section.contains(query, ignoreCase = true)
    }
    val sections = filtered.groupBy { it.section }

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
                val isOpen = expanded.value[section] ?: true
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
                }
            }
            if (expanded.value[section] ?: true) {
                items(sectionRows.size, key = { idx -> "$section-${sectionRows[idx].title}" }) { idx ->
                    val row = sectionRows[idx]
                    SettingRowWithTooltip(
                        title = row.title,
                        tooltipExplanation = row.tooltip
                    ) { row.content() }
                }
            }
        }
    }
}
