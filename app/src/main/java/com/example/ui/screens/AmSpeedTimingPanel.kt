package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ShowChart
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.AcUnit
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.History
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.data.model.Layer
import com.example.data.model.LayerType
import com.example.data.model.PropertyTarget
import com.example.engine.AnimationEvaluator
import com.example.engine.AudioSpectrumSnapshot
import com.example.ui.components.AmKeyframeCluster
import com.example.ui.components.AmPrimaryCtaButton
import com.example.ui.components.AmSegmentChip
import com.example.ui.components.AmTickWheelScrubber
import com.example.ui.theme.AmTeal
import com.example.ui.theme.AmTealBright
import com.example.ui.theme.AudioCyan
import com.example.ui.theme.DeepBlack
import com.example.ui.theme.KeyframeAmber
import com.example.ui.theme.NeonPink
import com.example.ui.theme.OutlineSubtle
import com.example.ui.theme.PanelDark
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import com.example.viewmodel.AmInspectorTab
import com.example.viewmodel.StudioDestination
import kotlin.math.abs

/**
 * Dedicated Alight Motion + KeyFlow Video/Audio Speed, Reverse, Freeze Frame,
 * Lossless Audio Extraction, Volume/Mute, and Time Remapping (Velocity) Panel!
 */
@Composable
fun AmSpeedAndTimingPanel(
    selectedLayer: Layer,
    playheadMs: Long,
    audioSpectrum: AudioSpectrumSnapshot,
    onUpdateSpeed: (Float) -> Unit,
    onToggleReverse: () -> Unit,
    onToggleFreezeFrame: () -> Unit,
    onToggleTimeRemap: (Boolean) -> Unit,
    onApplyVelocityPreset: (String) -> Unit,
    onSelectProperty: (PropertyTarget) -> Unit,
    onUpdateTimeRemapSec: (Float) -> Unit,
    onToggleKeyframe: () -> Unit,
    onJumpKeyframe: (Boolean) -> Unit,
    onSetInspectorTab: (AmInspectorTab) -> Unit,
    onNavigateTool: (StudioDestination) -> Unit,
    onExtractAudio: () -> Unit,
    onUpdateAudioVolume: (volume: Float, muted: Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    val isVideo = selectedLayer.type == LayerType.VIDEO
    val isAudio = selectedLayer.type == LayerType.AUDIO

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // 1. Speed Multiplier Quick Presets + Graduated Tick-Wheel Scrubber
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            listOf(0.25f, 0.5f, 0.75f, 1.0f, 1.5f, 2.0f, 4.0f).forEach { spd ->
                AmSegmentChip(
                    label = "${spd}x",
                    selected = abs(selectedLayer.speedMultiplier - spd) < 0.03f && !selectedLayer.timeRemapEnabled,
                    onClick = { onUpdateSpeed(spd) },
                    compact = true
                )
            }
        }

        if (!selectedLayer.timeRemapEnabled) {
            AmTickWheelScrubber(
                value = selectedLayer.speedMultiplier,
                minValue = 0.10f,
                maxValue = 4.00f,
                unit = "x",
                label = "Playback Speed",
                onValueChange = onUpdateSpeed,
                testTag = "speed_wheel_scrubber"
            )
        }

        // 2. Reverse Playback + Freeze Frame Row (Video & Clip Timing)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier
                    .weight(1f)
                    .height(40.dp)
                    .clip(RoundedCornerShape(9.dp))
                    .background(if (selectedLayer.reversed) NeonPink.copy(alpha = 0.20f) else PanelDark)
                    .border(
                        1.dp,
                        if (selectedLayer.reversed) NeonPink else OutlineSubtle,
                        RoundedCornerShape(9.dp)
                    )
                    .clickable(onClick = onToggleReverse)
                    .testTag("toggle_reverse_video_button")
                    .padding(horizontal = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                Icon(
                    imageVector = Icons.Default.History,
                    contentDescription = "Reverse clip",
                    tint = if (selectedLayer.reversed) NeonPink else TextPrimary,
                    modifier = Modifier.size(17.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = if (selectedLayer.reversed) "Reverse: ON" else "Reverse Clip",
                    style = MaterialTheme.typography.labelMedium,
                    color = if (selectedLayer.reversed) NeonPink else TextPrimary,
                    fontWeight = FontWeight.Bold
                )
            }

            if (isVideo) {
                val isFrozen = selectedLayer.freezeFrameMs >= 0L
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .height(40.dp)
                        .clip(RoundedCornerShape(9.dp))
                        .background(if (isFrozen) KeyframeAmber.copy(alpha = 0.20f) else PanelDark)
                        .border(
                            1.dp,
                            if (isFrozen) KeyframeAmber else OutlineSubtle,
                            RoundedCornerShape(9.dp)
                        )
                        .clickable(onClick = onToggleFreezeFrame)
                        .testTag("toggle_freeze_frame_button")
                        .padding(horizontal = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.AcUnit,
                        contentDescription = "Freeze frame",
                        tint = if (isFrozen) KeyframeAmber else TextPrimary,
                        modifier = Modifier.size(17.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = if (isFrozen) "Frozen" else "Freeze Frame",
                        style = MaterialTheme.typography.labelMedium,
                        color = if (isFrozen) KeyframeAmber else TextPrimary,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }

        // 3. Video Time Remapping & KeyFlow Velocity Curve Section
        if (isVideo) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(PanelDark)
                    .border(1.dp, if (selectedLayer.timeRemapEnabled) AmTeal else OutlineSubtle, RoundedCornerShape(10.dp))
                    .padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Time Remapping (Velocity)",
                            style = MaterialTheme.typography.labelLarge,
                            color = if (selectedLayer.timeRemapEnabled) AmTealBright else TextPrimary,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "Keyframe exact video frame time & Bezier speed ramps",
                            style = MaterialTheme.typography.labelSmall,
                            color = TextSecondary
                        )
                    }
                    Switch(
                        checked = selectedLayer.timeRemapEnabled,
                        onCheckedChange = { enabled ->
                            onSelectProperty(PropertyTarget.TIME_REMAP)
                            onToggleTimeRemap(enabled)
                        },
                        colors = SwitchDefaults.colors(checkedThumbColor = DeepBlack, checkedTrackColor = AmTeal),
                        modifier = Modifier.testTag("time_remap_switch")
                    )
                }

                if (selectedLayer.timeRemapEnabled) {
                    val trProp = selectedLayer.timeRemap
                    val evalSec = AnimationEvaluator.evaluateProperty(trProp, playheadMs, audioSpectrum)
                    val maxSec = ((selectedLayer.sourceDurationMs.takeIf { it > 0L } ?: (selectedLayer.endTimeMs - selectedLayer.startTimeMs))
                        .coerceAtLeast(500L) / 1000f).coerceAtLeast(1f)
                    val hasKf = trProp.keyframes.any { abs(it.timeMs - playheadMs) <= 25L }

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        AmKeyframeCluster(
                            hasKeyframeAtPlayhead = hasKf,
                            keyframeCount = trProp.keyframes.size,
                            onPrevKeyframe = {
                                onSelectProperty(PropertyTarget.TIME_REMAP)
                                onJumpKeyframe(false)
                            },
                            onToggleKeyframe = {
                                onSelectProperty(PropertyTarget.TIME_REMAP)
                                onToggleKeyframe()
                            },
                            onNextKeyframe = {
                                onSelectProperty(PropertyTarget.TIME_REMAP)
                                onJumpKeyframe(true)
                            }
                        )
                        AmTickWheelScrubber(
                            value = evalSec,
                            minValue = 0f,
                            maxValue = maxSec,
                            unit = "s",
                            label = "Remap Source Time",
                            onValueChange = { v ->
                                onSelectProperty(PropertyTarget.TIME_REMAP)
                                onUpdateTimeRemapSec(v)
                            },
                            modifier = Modifier.weight(1f)
                        )
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            modifier = Modifier
                                .height(30.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(AmTeal.copy(alpha = 0.20f))
                                .border(1.dp, AmTeal, RoundedCornerShape(8.dp))
                                .clickable {
                                    onSelectProperty(PropertyTarget.TIME_REMAP)
                                    onNavigateTool(StudioDestination.GRAPH_EDITOR)
                                }
                                .padding(horizontal = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.AutoMirrored.Filled.ShowChart, contentDescription = null, tint = AmTealBright, modifier = Modifier.size(14.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Velocity Graph", style = MaterialTheme.typography.labelSmall, color = AmTealBright, fontWeight = FontWeight.Bold)
                        }

                        listOf("Smooth Slow-Mo", "Velocity Whip", "Bullet Ramp", "Reverse Bounce").forEach { preset ->
                            AmSegmentChip(
                                label = preset,
                                selected = false,
                                onClick = { onApplyVelocityPreset(preset) },
                                compact = true
                            )
                        }
                    }
                }
            }

            // 4. Lossless Video Audio Extraction CTA
            AmPrimaryCtaButton(
                text = "EXTRACT AUDIO TO TRACK",
                icon = Icons.Default.GraphicEq,
                onClick = onExtractAudio,
                modifier = Modifier.fillMaxWidth(),
                testTag = "extract_audio_button",
                compact = true
            )
        }

        // 5. Audio Volume & Mute Control (for VIDEO & AUDIO layers)
        if (isVideo || isAudio) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(PanelDark)
                    .border(1.dp, OutlineSubtle, RoundedCornerShape(10.dp))
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = if (selectedLayer.audioMuted) Icons.AutoMirrored.Filled.VolumeOff else Icons.AutoMirrored.Filled.VolumeUp,
                    contentDescription = "Mute audio",
                    tint = if (selectedLayer.audioMuted) NeonPink else AudioCyan,
                    modifier = Modifier
                        .size(22.dp)
                        .clickable {
                            onUpdateAudioVolume(selectedLayer.audioVolume, !selectedLayer.audioMuted)
                        }
                )
                Text(
                    text = if (selectedLayer.audioMuted) "Muted" else "${(selectedLayer.audioVolume * 100).toInt()}%",
                    style = MaterialTheme.typography.labelMedium,
                    color = if (selectedLayer.audioMuted) NeonPink else TextPrimary,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.width(52.dp)
                )
                Slider(
                    value = selectedLayer.audioVolume,
                    onValueChange = { v -> onUpdateAudioVolume(v, false) },
                    valueRange = 0f..2f,
                    colors = SliderDefaults.colors(
                        thumbColor = AudioCyan,
                        activeTrackColor = AudioCyan
                    ),
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}
