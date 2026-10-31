@file:OptIn(ExperimentalMaterial3Api::class)

package com.example.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CleaningServices
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.HighQuality
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.MovieCreation
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.data.model.AspectRatioPreset
import com.example.data.preferences.BitrateProfile
import com.example.data.preferences.HardwareCodecCapabilities
import com.example.data.preferences.PreviewQuality
import com.example.data.preferences.StudioPreferences
import com.example.engine.EffectThumbnailPreviewer
import com.example.engine.MediaEngine
import com.example.engine.PlaybackCacheEngine
import com.example.ui.components.AmSegmentChip
import com.example.ui.components.AmSquareIconButton
import com.example.ui.theme.AmBgDark
import com.example.ui.theme.AmTeal
import com.example.ui.theme.AmTealBright
import com.example.ui.theme.ElectricBlue
import com.example.ui.theme.NeonPink
import com.example.ui.theme.OutlineSubtle
import com.example.ui.theme.PanelDark
import com.example.ui.theme.PanelElevated
import com.example.ui.theme.RigGreen
import com.example.ui.theme.SurfaceDark
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary

@Composable
fun AppSettingsScreen(
    preferences: StudioPreferences,
    codecCapabilities: HardwareCodecCapabilities,
    onUpdatePreferences: ((StudioPreferences) -> StudioPreferences) -> Unit,
    onApplyQualityNow: (PreviewQuality) -> Unit,
    onClearCaches: () -> Unit,
    onResetDefaults: () -> Unit,
    onBack: () -> Unit
) {
    BackHandler(onBack = onBack)

    var cacheRefreshTick by remember { mutableIntStateOf(0) }
    val usedRamMb = remember(cacheRefreshTick, preferences.playbackCacheLimitMb) {
        PlaybackCacheEngine.getUsedRamMb() + MediaEngine.getUsedCacheMb()
    }
    val ramProgress = (usedRamMb / preferences.playbackCacheLimitMb.coerceAtLeast(1).toFloat())
        .coerceIn(0f, 1f)

    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing,
        containerColor = AmBgDark,
        topBar = {
            TopAppBar(
                navigationIcon = {
                    AmSquareIconButton(
                        icon = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back to Home",
                        onClick = onBack,
                        testTag = "settings_back_button",
                        modifier = Modifier.padding(start = 8.dp)
                    )
                },
                title = {
                    Column {
                        Text(
                            text = stringResource(id = R.string.settings_title),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = TextPrimary
                        )
                        Text(
                            text = "Playback Cache • Video Codecs • Engine Defaults",
                            style = MaterialTheme.typography.labelSmall,
                            color = TextSecondary
                        )
                    }
                },
                actions = {
                    TextButton(
                        onClick = {
                            onResetDefaults()
                            cacheRefreshTick++
                        },
                        modifier = Modifier.testTag("settings_reset_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.RestartAlt,
                            contentDescription = "Reset defaults",
                            tint = AmTealBright,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Reset", color = AmTealBright, style = MaterialTheme.typography.labelLarge)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = SurfaceDark)
            )
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentAlignment = Alignment.TopCenter
        ) {
            LazyColumn(
                modifier = Modifier
                    .widthIn(max = 680.dp)
                    .fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                // SECTION 1: PREVIEW & PLAYBACK CACHE OPTIMIZATIONS
                item {
                    SettingsSectionCard(
                        icon = Icons.Default.Speed,
                        title = "Preview & Playback Cache",
                        accentColor = AmTealBright
                    ) {
                        Text(
                            text = "Default Preview Quality",
                            style = MaterialTheme.typography.labelLarge,
                            color = TextPrimary
                        )
                        Row(
                            modifier = Modifier.horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            PreviewQuality.entries.forEach { q ->
                                AmSegmentChip(
                                    label = "${q.title} (${q.badge})",
                                    selected = preferences.defaultPreviewQuality == q,
                                    onClick = {
                                        onUpdatePreferences { it.copy(defaultPreviewQuality = q) }
                                        onApplyQualityNow(q)
                                    },
                                    testTag = "settings_quality_${q.name}"
                                )
                            }
                        }

                        Text(
                            text = "RAM Playback Cache Budget",
                            style = MaterialTheme.typography.labelLarge,
                            color = TextPrimary
                        )
                        Row(
                            modifier = Modifier.horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            listOf(64, 128, 192, 256, 512).forEach { mb ->
                                AmSegmentChip(
                                    label = "$mb MB",
                                    selected = preferences.playbackCacheLimitMb == mb,
                                    onClick = {
                                        onUpdatePreferences { it.copy(playbackCacheLimitMb = mb) }
                                        cacheRefreshTick++
                                    },
                                    testTag = "settings_cache_limit_$mb"
                                )
                            }
                        }

                        // Live RAM Cache Usage Meter + Clear Cache Button
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(PanelElevated)
                                .border(1.dp, OutlineSubtle, RoundedCornerShape(10.dp))
                                .padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        imageVector = Icons.Default.Memory,
                                        contentDescription = null,
                                        tint = RigGreen,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = "Active RAM Cache",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = TextPrimary
                                    )
                                }
                                Text(
                                    text = "${"%.1f".format(usedRamMb)} / ${preferences.playbackCacheLimitMb} MB • Hit ${PlaybackCacheEngine.getCacheHitRatePercent()}%",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontFamily = FontFamily.Monospace,
                                    color = AmTealBright
                                )
                            }
                            LinearProgressIndicator(
                                progress = { ramProgress },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(6.dp)
                                    .clip(RoundedCornerShape(3.dp)),
                                color = RigGreen,
                                trackColor = SurfaceDark
                            )
                            Button(
                                onClick = {
                                    EffectThumbnailPreviewer.clearThumbnails()
                                    onClearCaches()
                                    cacheRefreshTick++
                                },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = AmTeal.copy(alpha = 0.18f),
                                    contentColor = AmTealBright
                                ),
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("settings_clear_cache_button")
                            ) {
                                Icon(
                                    imageVector = Icons.Default.CleaningServices,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = stringResource(id = R.string.clear_cache_label),
                                    style = MaterialTheme.typography.labelLarge,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }

                        SettingsToggleRow(
                            title = "Auto-Cache Pre-Rendered Frames",
                            subtitle = "Serve cached RAM frames via 1-draw-call GPU blit during playback",
                            checked = preferences.autoCachePlaybackFrames,
                            onCheckedChange = { enabled ->
                                onUpdatePreferences { it.copy(autoCachePlaybackFrames = enabled) }
                            },
                            testTag = "settings_toggle_auto_cache"
                        )

                        SettingsToggleRow(
                            title = "Fast Keyframe Video Scrubbing",
                            subtitle = "Use nearest hardware I-frame (OPTION_CLOSEST_SYNC) while dragging ruler",
                            checked = preferences.fastKeyframeScrub,
                            onCheckedChange = { enabled ->
                                onUpdatePreferences { it.copy(fastKeyframeScrub = enabled) }
                            },
                            testTag = "settings_toggle_fast_scrub"
                        )

                        Text(
                            text = "Preview Frame Rate Cap",
                            style = MaterialTheme.typography.labelLarge,
                            color = TextPrimary
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf(30 to "30 FPS (Battery Saver)", 60 to "60 FPS (Smooth)").forEach { (fps, label) ->
                                AmSegmentChip(
                                    label = label,
                                    selected = preferences.previewFpsCap == fps,
                                    onClick = { onUpdatePreferences { it.copy(previewFpsCap = fps) } },
                                    testTag = "settings_fps_cap_$fps"
                                )
                            }
                        }

                        SettingsToggleRow(
                            title = "Show Live Performance HUD",
                            subtitle = "Overlay real-time FPS, GPU frame ms, and RAM cache hit % on canvas",
                            checked = preferences.showPerformanceHud,
                            onCheckedChange = { enabled ->
                                onUpdatePreferences { it.copy(showPerformanceHud = enabled) }
                            },
                            testTag = "settings_toggle_hud"
                        )
                    }
                }

                // SECTION 2: VIDEO CODEC & HARDWARE PIPELINE OPTIMIZATIONS
                item {
                    SettingsSectionCard(
                        icon = Icons.Default.HighQuality,
                        title = "Video Codec & Hardware Pipeline",
                        accentColor = ElectricBlue
                    ) {
                        Text(
                            text = "Preferred Video Export Codec",
                            style = MaterialTheme.typography.labelLarge,
                            color = TextPrimary
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            AmSegmentChip(
                                label = "H.264 (AVC • Max Compatibility)",
                                selected = preferences.preferredVideoCodec == "H.264",
                                onClick = { onUpdatePreferences { it.copy(preferredVideoCodec = "H.264") } },
                                testTag = "settings_codec_H264"
                            )
                            AmSegmentChip(
                                label = "H.265 (HEVC • High Efficiency)",
                                selected = preferences.preferredVideoCodec == "H.265",
                                onClick = { onUpdatePreferences { it.copy(preferredVideoCodec = "H.265") } },
                                testTag = "settings_codec_H265"
                            )
                        }

                        // Hardware Encoder Detection Readout
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(SurfaceDark)
                                .border(1.dp, OutlineSubtle, RoundedCornerShape(8.dp))
                                .padding(horizontal = 10.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            val isHevcSelected = preferences.preferredVideoCodec == "H.265"
                            val hwAvailable = if (isHevcSelected) {
                                codecCapabilities.hasHardwareHevcEncoder
                            } else {
                                codecCapabilities.hasHardwareAvcEncoder
                            }
                            val encoderName = if (isHevcSelected) {
                                codecCapabilities.primaryHevcEncoderName
                            } else {
                                codecCapabilities.primaryAvcEncoderName
                            }
                            Text(
                                text = "Detected Encoder: $encoderName",
                                style = MaterialTheme.typography.labelSmall,
                                fontFamily = FontFamily.Monospace,
                                color = TextSecondary,
                                modifier = Modifier.weight(1f)
                            )
                            Text(
                                text = if (hwAvailable) "HW ACCEL" else "SW FALLBACK",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = if (hwAvailable) RigGreen else NeonPink
                            )
                        }

                        SettingsToggleRow(
                            title = "Hardware Accelerated Video Pipeline",
                            subtitle = "Prefer dedicated MediaCodec Silicon for decode & MP4 muxing",
                            checked = preferences.hardwareAcceleratedCodecs,
                            onCheckedChange = { enabled ->
                                onUpdatePreferences { it.copy(hardwareAcceleratedCodecs = enabled) }
                            },
                            testTag = "settings_toggle_hw_codec"
                        )

                        Text(
                            text = "Export Bitrate Profile",
                            style = MaterialTheme.typography.labelLarge,
                            color = TextPrimary
                        )
                        Row(
                            modifier = Modifier.horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            BitrateProfile.entries.forEach { profile ->
                                AmSegmentChip(
                                    label = profile.label,
                                    selected = preferences.exportBitrateProfile == profile,
                                    onClick = { onUpdatePreferences { it.copy(exportBitrateProfile = profile) } },
                                    testTag = "settings_bitrate_${profile.name}"
                                )
                            }
                        }

                        Text(
                            text = "Keyframe (I-Frame GOP) Interval",
                            style = MaterialTheme.typography.labelLarge,
                            color = TextPrimary
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf(1 to "1s (Edit Friendly)", 2 to "2s (Balanced)", 5 to "5s (Compact)").forEach { (sec, label) ->
                                AmSegmentChip(
                                    label = label,
                                    selected = preferences.keyframeIntervalSec == sec,
                                    onClick = { onUpdatePreferences { it.copy(keyframeIntervalSec = sec) } },
                                    testTag = "settings_gop_$sec"
                                )
                            }
                        }

                        Text(
                            text = "Audio Export Sample Rate",
                            style = MaterialTheme.typography.labelLarge,
                            color = TextPrimary
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf(44100 to "44.1 kHz", 48000 to "48 kHz Studio").forEach { (hz, label) ->
                                AmSegmentChip(
                                    label = label,
                                    selected = preferences.exportAudioSampleRateHz == hz,
                                    onClick = { onUpdatePreferences { it.copy(exportAudioSampleRateHz = hz) } },
                                    testTag = "settings_sample_rate_$hz"
                                )
                            }
                        }
                    }
                }

                // SECTION 3: AUDIO & TIMELINE WORKFLOW
                item {
                    SettingsSectionCard(
                        icon = Icons.Default.GraphicEq,
                        title = "Audio Sync & Timeline Workflow",
                        accentColor = NeonPink
                    ) {
                        SettingsToggleRow(
                            title = "Loop Playback by Default",
                            subtitle = "Wrap playhead seamlessly to 00:00 (also accessible by holding Play)",
                            checked = preferences.defaultLoopPlayback,
                            onCheckedChange = { enabled ->
                                onUpdatePreferences { it.copy(defaultLoopPlayback = enabled) }
                            },
                            testTag = "settings_toggle_default_loop"
                        )

                        Text(
                            text = "Audio Drift Resync Tolerance",
                            style = MaterialTheme.typography.labelLarge,
                            color = TextPrimary
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf(100 to "100 ms (Tight)", 160 to "160 ms (Smooth)", 260 to "260 ms (Relaxed)").forEach { (ms, label) ->
                                AmSegmentChip(
                                    label = label,
                                    selected = preferences.audioSyncThresholdMs == ms,
                                    onClick = { onUpdatePreferences { it.copy(audioSyncThresholdMs = ms) } },
                                    testTag = "settings_audio_sync_$ms"
                                )
                            }
                        }

                        SettingsToggleRow(
                            title = "Mute Audio During Rapid Scrub",
                            subtitle = "Prevent audio crackle and MediaPlayer seek thrashing while dragging",
                            checked = preferences.muteAudioDuringFastScrub,
                            onCheckedChange = { enabled ->
                                onUpdatePreferences { it.copy(muteAudioDuringFastScrub = enabled) }
                            },
                            testTag = "settings_toggle_mute_scrub"
                        )

                        SettingsToggleRow(
                            title = "Magnetic Snap to Bookmarks & Keyframes",
                            subtitle = "Snap timeline playhead onto nearby beat bookmarks and diamonds",
                            checked = preferences.snapToBookmarksAndKeys,
                            onCheckedChange = { enabled ->
                                onUpdatePreferences { it.copy(snapToBookmarksAndKeys = enabled) }
                            },
                            testTag = "settings_toggle_snap"
                        )
                    }
                }

                // SECTION 4: NEW PROJECT DEFAULTS
                item {
                    SettingsSectionCard(
                        icon = Icons.Default.MovieCreation,
                        title = "New Project Defaults",
                        accentColor = RigGreen
                    ) {
                        Text(
                            text = "Default Aspect Ratio",
                            style = MaterialTheme.typography.labelLarge,
                            color = TextPrimary
                        )
                        Row(
                            modifier = Modifier.horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            listOf(
                                AspectRatioPreset.LANDSCAPE_16_9,
                                AspectRatioPreset.PORTRAIT_9_16,
                                AspectRatioPreset.SQUARE_1_1,
                                AspectRatioPreset.CLASSIC_4_3,
                                AspectRatioPreset.CINEMATIC_21_9
                            ).forEach { preset ->
                                AmSegmentChip(
                                    label = preset.label,
                                    selected = preferences.defaultAspectPreset == preset,
                                    onClick = { onUpdatePreferences { it.copy(defaultAspectPreset = preset) } },
                                    testTag = "settings_default_aspect_${preset.name}"
                                )
                            }
                        }

                        Text(
                            text = "Default Frame Rate & Length",
                            style = MaterialTheme.typography.labelLarge,
                            color = TextPrimary
                        )
                        Row(
                            modifier = Modifier.horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            listOf(24, 30, 60).forEach { fps ->
                                AmSegmentChip(
                                    label = "${fps}fps",
                                    selected = preferences.defaultProjectFps == fps,
                                    onClick = { onUpdatePreferences { it.copy(defaultProjectFps = fps) } },
                                    testTag = "settings_default_fps_$fps"
                                )
                            }
                            listOf(6, 10, 15, 30).forEach { sec ->
                                AmSegmentChip(
                                    label = "${sec}s",
                                    selected = preferences.defaultProjectDurationSec == sec,
                                    onClick = { onUpdatePreferences { it.copy(defaultProjectDurationSec = sec) } },
                                    testTag = "settings_default_dur_$sec"
                                )
                            }
                        }

                        Text(
                            text = "Default Canvas Background",
                            style = MaterialTheme.typography.labelLarge,
                            color = TextPrimary
                        )
                        Row(
                            modifier = Modifier.horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            listOf("Black", "Dark Gray", "White", "Transparent").forEach { bg ->
                                AmSegmentChip(
                                    label = bg,
                                    selected = preferences.defaultCanvasBackground == bg,
                                    onClick = { onUpdatePreferences { it.copy(defaultCanvasBackground = bg) } },
                                    testTag = "settings_default_bg_$bg"
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsSectionCard(
    icon: ImageVector,
    title: String,
    accentColor: Color,
    content: @Composable () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, OutlineSubtle, RoundedCornerShape(14.dp)),
        shape = RoundedCornerShape(14.dp),
        color = PanelDark
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = accentColor,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary
                )
            }
            content()
        }
    }
}

@Composable
private fun SettingsToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    testTag: String
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable { onCheckedChange(!checked) }
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = TextPrimary
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = TextMuted
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = AmTeal,
                uncheckedThumbColor = TextSecondary,
                uncheckedTrackColor = SurfaceDark
            ),
            modifier = Modifier.testTag(testTag)
        )
    }
}
