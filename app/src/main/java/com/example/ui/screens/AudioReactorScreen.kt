@file:OptIn(ExperimentalMaterial3Api::class)

package com.example.ui.screens

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.data.model.AudioBand
import com.example.data.model.BeatMarker
import com.example.data.model.Layer
import com.example.data.model.PropertyTarget
import com.example.engine.AudioSpectrumSnapshot
import com.example.ui.components.AmPrimaryCtaButton
import com.example.ui.components.AmSegmentChip
import com.example.ui.components.AmTickWheelScrubber
import com.example.ui.theme.AmBgDark
import com.example.ui.theme.AmTeal
import com.example.ui.theme.AmTealBright
import com.example.ui.theme.AudioCyan
import com.example.ui.theme.DeepBlack
import com.example.ui.theme.ElectricBlue
import com.example.ui.theme.NeonPink
import com.example.ui.theme.OutlineSubtle
import com.example.ui.theme.PanelDark
import com.example.ui.theme.SurfaceDark
import com.example.ui.theme.TextSecondary

@Composable
fun AudioReactorScreen(
    bpm: Int,
    sensitivity: Float,
    beatMarkers: List<BeatMarker>,
    spectrum: AudioSpectrumSnapshot,
    waveformBins: List<Float>,
    isLiveMicActive: Boolean,
    selectedLayer: Layer?,
    selectedProperty: PropertyTarget,
    onSelectProperty: (PropertyTarget) -> Unit,
    onBindAudioToProperty: (AudioBand, Float) -> Unit,
    onRegenerateBeats: (bpm: Int, sensitivity: Float) -> Unit,
    onToggleLiveMic: (Boolean) -> Unit,
    onBack: () -> Unit
) {
    var localBpm by remember(bpm) { mutableIntStateOf(bpm) }
    var localSensitivity by remember(sensitivity) { mutableFloatStateOf(sensitivity) }
    var micPermissionNote by remember { mutableStateOf<String?>(null) }

    val micPermLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            micPermissionNote = null
            onToggleLiveMic(true)
        } else {
            micPermissionNote = "Microphone declined — running BPM Harmonic Stem Analyzer."
        }
    }

    val activeProp = selectedLayer?.propertyFor(selectedProperty)

    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing,
        containerColor = AmBgDark,
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack, modifier = Modifier.testTag("audio_back_button")) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back to Editor")
                    }
                },
                title = {
                    Column {
                        Text(
                            text = "Audio Reactor & Beat Flags",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "${beatMarkers.size} Beat Flags • $localBpm BPM",
                            style = MaterialTheme.typography.labelSmall,
                            color = AmTealBright
                        )
                    }
                },
                actions = {
                    Row(
                        modifier = Modifier
                            .padding(end = 10.dp)
                            .height(38.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (isLiveMicActive) NeonPink else PanelDark)
                            .border(1.dp, if (isLiveMicActive) NeonPink else OutlineSubtle, RoundedCornerShape(8.dp))
                            .clickable {
                                if (isLiveMicActive) {
                                    onToggleLiveMic(false)
                                } else {
                                    micPermLauncher.launch(Manifest.permission.RECORD_AUDIO)
                                }
                            }
                            .padding(horizontal = 10.dp)
                            .testTag("live_mic_fft_button"),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = if (isLiveMicActive) Icons.Default.Mic else Icons.Default.MicOff,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = if (isLiveMicActive) "LIVE FFT" else "MIC FFT",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = SurfaceDark)
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            if (micPermissionNote != null) {
                Text(
                    text = micPermissionNote!!,
                    style = MaterialTheme.typography.bodySmall,
                    color = NeonPink
                )
            }

            // Alight Motion Dual-Channel Waveform & Beat Flag Monitor
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(138.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(DeepBlack)
                    .border(1.dp, OutlineSubtle, RoundedCornerShape(12.dp))
                    .padding(12.dp)
            ) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val count = waveformBins.size.coerceAtLeast(1)
                    val barW = size.width / count
                    waveformBins.forEachIndexed { idx, amp ->
                        val barH = (amp * size.height * 0.88f).coerceAtLeast(6f)
                        drawLine(
                            color = if (idx % 4 == 0) NeonPink else AmTeal,
                            start = Offset(idx * barW + barW * 0.5f, (size.height - barH) * 0.5f),
                            end = Offset(idx * barW + barW * 0.5f, (size.height + barH) * 0.5f),
                            strokeWidth = (barW * 0.6f).coerceAtLeast(3f)
                        )
                    }
                }
            }

            // Radix-2 FFT Frequency Isolation Card
            Card(
                colors = CardDefaults.cardColors(containerColor = SurfaceDark),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, OutlineSubtle, RoundedCornerShape(12.dp))
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        text = "Frequency Isolation (Radix-2 FFT)",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                    BandMeterRow("Sub-Bass / Kick (20–160 Hz)", spectrum.bass, NeonPink)
                    BandMeterRow("Vocals / Mids (160–2k Hz)", spectrum.mids, ElectricBlue)
                    BandMeterRow("Hi-Hats / Treble (2k–16k Hz)", spectrum.treble, AmTeal)
                }
            }

            // Alight Motion Tick-Wheel Tempo & Auto-Beat Flag Card
            Card(
                colors = CardDefaults.cardColors(containerColor = SurfaceDark),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, OutlineSubtle, RoundedCornerShape(12.dp))
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        text = "Auto-Beat Bookmark Generator",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                    AmTickWheelScrubber(
                        value = localBpm.toFloat(),
                        minValue = 60f,
                        maxValue = 200f,
                        unit = " BPM",
                        label = "Project Tempo",
                        onValueChange = { localBpm = it.toInt() },
                        testTag = "bpm_tick_scrubber"
                    )
                    AmTickWheelScrubber(
                        value = localSensitivity * 100f,
                        minValue = 20f,
                        maxValue = 100f,
                        unit = "%",
                        label = "Onset Flux Sensitivity",
                        onValueChange = { localSensitivity = (it / 100f).coerceIn(0.2f, 1f) },
                        testTag = "sensitivity_tick_scrubber"
                    )
                    AmPrimaryCtaButton(
                        text = "GENERATE AUTO-BEAT FLAGS",
                        icon = Icons.Default.Bolt,
                        onClick = { onRegenerateBeats(localBpm, localSensitivity) },
                        modifier = Modifier.fillMaxWidth(),
                        testTag = "generate_beat_markers_button"
                    )
                }
            }

            // Procedural Audio -> Property Reactor Binding
            if (selectedLayer != null && activeProp != null) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = SurfaceDark),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, OutlineSubtle, RoundedCornerShape(12.dp))
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text(
                            text = "Reactor Property Link • ${selectedLayer.name}",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )
                        Row(
                            modifier = Modifier.horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            PropertyTarget.entries.forEach { pt ->
                                AmSegmentChip(
                                    label = pt.label,
                                    selected = selectedProperty == pt,
                                    onClick = { onSelectProperty(pt) }
                                )
                            }
                        }

                        Text("Source Frequency Band", style = MaterialTheme.typography.labelSmall, color = TextSecondary)
                        Row(
                            modifier = Modifier.horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            AudioBand.entries.forEach { band ->
                                AmSegmentChip(
                                    label = band.label,
                                    selected = activeProp.audioReactorBand == band,
                                    onClick = { onBindAudioToProperty(band, activeProp.audioReactorGain) }
                                )
                            }
                        }

                        AmTickWheelScrubber(
                            value = activeProp.audioReactorGain,
                            minValue = 0.1f,
                            maxValue = 2.5f,
                            unit = "x",
                            label = "Reactor Gain Multiplier",
                            onValueChange = { onBindAudioToProperty(activeProp.audioReactorBand, it) },
                            testTag = "reactor_gain_scrubber"
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun BandMeterRow(label: String, value: Float, color: Color) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(label, style = MaterialTheme.typography.labelSmall, color = TextSecondary)
            Text(
                text = "${(value * 100).toInt()}%",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = color
            )
        }
        LinearProgressIndicator(
            progress = { value.coerceIn(0f, 1f) },
            color = color,
            trackColor = PanelDark,
            modifier = Modifier
                .fillMaxWidth()
                .height(8.dp)
                .clip(RoundedCornerShape(4.dp))
        )
    }
}
