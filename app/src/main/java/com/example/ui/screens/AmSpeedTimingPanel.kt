package com.example.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.data.model.Layer
import com.example.data.model.LayerType
import com.example.data.model.PropertyTarget
import com.example.engine.AnimationEvaluator
import com.example.engine.AudioSpectrumSnapshot
import com.example.engine.StudioDspAudioProcessor
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
    onUpdateAudioEffects: (
        preset: String,
        reverbMix: Float,
        roomSize: Float,
        bassBoost: Float,
        echoMix: Float,
        echoDelayMs: Float,
        filterCutoff: Float,
        pitchSemitones: Float,
        optSpeed: Float?
    ) -> Unit = { _, _, _, _, _, _, _, _, _ -> },
    modifier: Modifier = Modifier
) {
    val isVideo = selectedLayer.type == LayerType.VIDEO
    val isAudio = selectedLayer.type == LayerType.AUDIO

    val context = LocalContext.current
    val audioJsonImporterLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            try {
                context.contentResolver.openInputStream(uri)?.use { inputStream ->
                    val jsonText = inputStream.bufferedReader().use { it.readText() }
                    val json = org.json.JSONObject(jsonText)
                    val presetName = json.optString("name", json.optString("preset", "Imported JSON"))
                    val reverb = json.optDouble("reverbMix", json.optDouble("reverb", 0.0)).toFloat()
                    val room = json.optDouble("roomSize", json.optDouble("room", 0.5)).toFloat()
                    val bass = json.optDouble("bassBoost", json.optDouble("bass", 0.0)).toFloat()
                    val echo = json.optDouble("echoMix", json.optDouble("echo", 0.0)).toFloat()
                    val delay = json.optDouble("echoDelayMs", json.optDouble("delayMs", 240.0)).toFloat()
                    val cutoff = json.optDouble("filterCutoff", json.optDouble("cutoff", 1.0)).toFloat()
                    val pitch = json.optDouble("pitchSemitones", json.optDouble("pitch", 0.0)).toFloat()
                    val speed = if (json.has("recommendedSpeed")) json.getDouble("recommendedSpeed").toFloat() else null
                    onUpdateAudioEffects(presetName, reverb, room, bass, echo, delay, cutoff, pitch, speed)
                }
            } catch (_: Exception) {}
        }
    }

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
                label = "Speed",
                onValueChange = onUpdateSpeed,
                onReset = { onUpdateSpeed(1.0f) },
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
                    contentDescription = "Reverse",
                    tint = if (selectedLayer.reversed) NeonPink else TextPrimary,
                    modifier = Modifier.size(17.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "Reverse",
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
                        contentDescription = "Freeze",
                        tint = if (isFrozen) KeyframeAmber else TextPrimary,
                        modifier = Modifier.size(17.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "Freeze",
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
                    Text(
                        text = "Time Remap",
                        style = MaterialTheme.typography.labelLarge,
                        color = if (selectedLayer.timeRemapEnabled) AmTealBright else TextPrimary,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f)
                    )
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
                            label = "Time",
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
                            Text("Graph", style = MaterialTheme.typography.labelSmall, color = AmTealBright, fontWeight = FontWeight.Bold)
                        }

                        listOf("Slow-Mo", "Whip", "Bullet", "Bounce").zip(
                            listOf("Smooth Slow-Mo", "Velocity Whip", "Bullet Ramp", "Reverse Bounce")
                        ).forEach { (shortLabel, presetKey) ->
                            AmSegmentChip(
                                label = shortLabel,
                                selected = false,
                                onClick = { onApplyVelocityPreset(presetKey) },
                                compact = true
                            )
                        }
                    }
                }
            }

            AmPrimaryCtaButton(
                text = "Extract Audio",
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

            // 6. Real-Time Studio Audio Effects (Reverb, Sub-Bass, Slapback Echo, Low-Pass Filter, Pitch Shift)
            val hasCustomDsp = selectedLayer.audioPreset != "Clean" ||
                selectedLayer.audioReverbMix > 0.01f ||
                selectedLayer.audioBassBoost > 0.01f ||
                selectedLayer.audioEchoMix > 0.01f ||
                selectedLayer.audioFilterCutoff < 0.98f ||
                abs(selectedLayer.audioPitchSemitones) > 0.05f

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(PanelDark)
                    .border(
                        1.dp,
                        if (hasCustomDsp) AudioCyan.copy(alpha = 0.75f) else OutlineSubtle,
                        RoundedCornerShape(10.dp)
                    )
                    .padding(10.dp)
                    .testTag("audio_dsp_effects_card"),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.GraphicEq,
                            contentDescription = null,
                            tint = AudioCyan,
                            modifier = Modifier.size(17.dp)
                        )
                        Text(
                            text = "Audio Effects",
                            style = MaterialTheme.typography.labelLarge,
                            color = TextPrimary,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Import JSON",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = AudioCyan,
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(AudioCyan.copy(alpha = 0.14f))
                                .clickable {
                                    audioJsonImporterLauncher.launch(arrayOf("application/json", "*/*"))
                                }
                                .testTag("import_audio_json_button")
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                        if (hasCustomDsp) {
                            Text(
                                text = "Reset",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = AudioCyan,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(AudioCyan.copy(alpha = 0.14f))
                                    .clickable {
                                        onUpdateAudioEffects("Clean", 0f, 0.5f, 0f, 0f, 240f, 1.0f, 0f, null)
                                    }
                                    .testTag("audio_dsp_reset_button")
                                    .padding(horizontal = 8.dp, vertical = 4.dp)
                            )
                        }
                    }
                }

                // 1-Tap Audio DSP Presets Strip
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    StudioDspAudioProcessor.PRESETS.forEach { preset ->
                        AmSegmentChip(
                            label = preset.name,
                            selected = selectedLayer.audioPreset == preset.name,
                            compact = true,
                            onClick = {
                                onUpdateAudioEffects(
                                    preset.name,
                                    preset.reverbMix,
                                    preset.roomSize,
                                    preset.bassBoost,
                                    preset.echoMix,
                                    preset.echoDelayMs,
                                    preset.filterCutoff,
                                    preset.pitchSemitones,
                                    preset.recommendedSpeed
                                )
                            }
                        )
                    }
                }

                // Reverb Mix & Room Size
                AudioDspCompactSliderRow(
                    label = "Reverb",
                    valueText = "${(selectedLayer.audioReverbMix * 100).toInt()}%",
                    value = selectedLayer.audioReverbMix,
                    range = 0f..1f,
                    testTag = "audio_reverb_slider",
                    onValueChange = { v ->
                        onUpdateAudioEffects(
                            "Custom",
                            v,
                            selectedLayer.audioRoomSize,
                            selectedLayer.audioBassBoost,
                            selectedLayer.audioEchoMix,
                            selectedLayer.audioEchoDelayMs,
                            selectedLayer.audioFilterCutoff,
                            selectedLayer.audioPitchSemitones,
                            null
                        )
                    }
                )

                if (selectedLayer.audioReverbMix > 0.01f) {
                    AudioDspCompactSliderRow(
                        label = "Room",
                        valueText = "${(selectedLayer.audioRoomSize * 100).toInt()}%",
                        value = selectedLayer.audioRoomSize,
                        range = 0.1f..1f,
                        testTag = "audio_room_size_slider",
                        onValueChange = { v ->
                            onUpdateAudioEffects(
                                "Custom",
                                selectedLayer.audioReverbMix,
                                v,
                                selectedLayer.audioBassBoost,
                                selectedLayer.audioEchoMix,
                                selectedLayer.audioEchoDelayMs,
                                selectedLayer.audioFilterCutoff,
                                selectedLayer.audioPitchSemitones,
                                null
                            )
                        }
                    )
                }

                // Sub-Bass Boost
                AudioDspCompactSliderRow(
                    label = "Bass",
                    valueText = "+${(selectedLayer.audioBassBoost * 12f).toInt()} dB",
                    value = selectedLayer.audioBassBoost,
                    range = 0f..1f,
                    testTag = "audio_bass_boost_slider",
                    onValueChange = { v ->
                        onUpdateAudioEffects(
                            "Custom",
                            selectedLayer.audioReverbMix,
                            selectedLayer.audioRoomSize,
                            v,
                            selectedLayer.audioEchoMix,
                            selectedLayer.audioEchoDelayMs,
                            selectedLayer.audioFilterCutoff,
                            selectedLayer.audioPitchSemitones,
                            null
                        )
                    }
                )

                // Echo / Delay
                AudioDspCompactSliderRow(
                    label = "Echo",
                    valueText = "${(selectedLayer.audioEchoMix * 100).toInt()}%",
                    value = selectedLayer.audioEchoMix,
                    range = 0f..1f,
                    testTag = "audio_echo_mix_slider",
                    onValueChange = { v ->
                        onUpdateAudioEffects(
                            "Custom",
                            selectedLayer.audioReverbMix,
                            selectedLayer.audioRoomSize,
                            selectedLayer.audioBassBoost,
                            v,
                            selectedLayer.audioEchoDelayMs,
                            selectedLayer.audioFilterCutoff,
                            selectedLayer.audioPitchSemitones,
                            null
                        )
                    }
                )

                if (selectedLayer.audioEchoMix > 0.01f) {
                    AudioDspCompactSliderRow(
                        label = "Delay",
                        valueText = "${selectedLayer.audioEchoDelayMs.toInt()} ms",
                        value = selectedLayer.audioEchoDelayMs,
                        range = 60f..650f,
                        testTag = "audio_echo_delay_slider",
                        onValueChange = { v ->
                            onUpdateAudioEffects(
                                "Custom",
                                selectedLayer.audioReverbMix,
                                selectedLayer.audioRoomSize,
                                selectedLayer.audioBassBoost,
                                selectedLayer.audioEchoMix,
                                v,
                                selectedLayer.audioFilterCutoff,
                                selectedLayer.audioPitchSemitones,
                                null
                            )
                        }
                    )
                }

                // Low-Pass Filter (Underwater / Lo-Fi)
                AudioDspCompactSliderRow(
                    label = "Filter",
                    valueText = if (selectedLayer.audioFilterCutoff >= 0.98f) "Open" else "${(selectedLayer.audioFilterCutoff * 100).toInt()}%",
                    value = selectedLayer.audioFilterCutoff,
                    range = 0.12f..1f,
                    testTag = "audio_filter_cutoff_slider",
                    onValueChange = { v ->
                        onUpdateAudioEffects(
                            "Custom",
                            selectedLayer.audioReverbMix,
                            selectedLayer.audioRoomSize,
                            selectedLayer.audioBassBoost,
                            selectedLayer.audioEchoMix,
                            selectedLayer.audioEchoDelayMs,
                            v,
                            selectedLayer.audioPitchSemitones,
                            null
                        )
                    }
                )

                // Pitch Shift (Semitones)
                AudioDspCompactSliderRow(
                    label = "Pitch",
                    valueText = String.format("%+.1f st", selectedLayer.audioPitchSemitones),
                    value = selectedLayer.audioPitchSemitones,
                    range = -12f..12f,
                    testTag = "audio_pitch_slider",
                    onValueChange = { v ->
                        onUpdateAudioEffects(
                            "Custom",
                            selectedLayer.audioReverbMix,
                            selectedLayer.audioRoomSize,
                            selectedLayer.audioBassBoost,
                            selectedLayer.audioEchoMix,
                            selectedLayer.audioEchoDelayMs,
                            selectedLayer.audioFilterCutoff,
                            v,
                            null
                        )
                    }
                )
            }
        }
    }
}

@Composable
private fun AudioDspCompactSliderRow(
    label: String,
    valueText: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    testTag: String,
    onValueChange: (Float) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(28.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = TextSecondary,
            modifier = Modifier.width(64.dp)
        )
        Slider(
            value = value.coerceIn(range.start, range.endInclusive),
            onValueChange = onValueChange,
            valueRange = range,
            colors = SliderDefaults.colors(
                thumbColor = AudioCyan,
                activeTrackColor = AudioCyan
            ),
            modifier = Modifier
                .weight(1f)
                .testTag(testTag)
        )
        Text(
            text = valueText,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = TextPrimary,
            modifier = Modifier.width(52.dp)
        )
    }
}
