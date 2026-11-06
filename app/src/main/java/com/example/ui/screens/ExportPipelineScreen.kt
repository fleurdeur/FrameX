@file:OptIn(ExperimentalMaterial3Api::class)

package com.example.ui.screens

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.DataObject
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.HighQuality
import androidx.compose.material.icons.filled.IosShare
import androidx.compose.material.icons.filled.MovieCreation
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.example.data.model.AspectRatioPreset
import com.example.data.model.Project
import com.example.data.preferences.BitrateProfile
import com.example.data.preferences.StudioPreferences
import com.example.engine.ProjectXmlFormat
import com.example.ui.components.AmRatioOutlineCard
import com.example.ui.components.AmSegmentChip
import com.example.ui.components.AmTickWheelScrubber
import com.example.ui.theme.AmBgDark
import com.example.ui.theme.KeyflowBlue
import com.example.ui.theme.KeyflowBlueBright
import com.example.ui.theme.KeyflowBlueContainer
import com.example.ui.theme.OutlineSubtle
import com.example.ui.theme.PanelDark
import com.example.ui.theme.SurfaceDark
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import com.example.viewmodel.ExportUiState
import kotlin.math.roundToInt

private fun formatExportDuration(ms: Long): String {
    val safeMs = ms.coerceAtLeast(0L)
    val totalSec = safeMs / 1000L
    val min = totalSec / 60L
    val sec = totalSec % 60L
    val centi = (safeMs % 1000L) / 10L
    return String.format("%02d:%02d.%02d", min, sec, centi)
}

@Composable
fun ExportPipelineScreen(
    project: Project,
    exportState: ExportUiState,
    preferences: StudioPreferences = StudioPreferences(),
    onUpdatePreferences: ((StudioPreferences) -> StudioPreferences) -> Unit = {},
    onUpdateExportConfig: (preset: String, width: Int, height: Int, fps: Int, codec: String) -> Unit,
    onStartExport: (Context) -> Unit,
    onExportProjectXml: (Context, Uri, ProjectXmlFormat) -> Unit = { _, _, _ -> },
    onImportProjectXml: (Context, Uri) -> Unit = { _, _ -> },
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val safeBaseFileName = remember(project.name) {
        project.name
            .trim()
            .replace(Regex("[^a-zA-Z0-9_\\- ]"), "")
            .replace(Regex("\\s+"), "_")
            .ifBlank { "project" }
    }
    val createFpXmlLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/xml")
    ) { uri ->
        if (uri != null) {
            onExportProjectXml(context, uri, ProjectXmlFormat.FRAME_POLLUTION_NATIVE)
        }
    }
    val createAmXmlLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("text/xml")
    ) { uri ->
        if (uri != null) {
            onExportProjectXml(context, uri, ProjectXmlFormat.ALIGHT_MOTION)
        }
    }
    val importXmlLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            onImportProjectXml(context, uri)
        }
    }
    var presetLabel by remember(project.aspectRatioPreset) { mutableStateOf(project.aspectRatioPreset) }
    var widthStr by remember(project.width) { mutableStateOf(project.width.toString()) }
    var heightStr by remember(project.height) { mutableStateOf(project.height.toString()) }
    var fpsStr by remember(project.fps) { mutableStateOf(project.fps.toString()) }
    var codec by remember(project.codec) { mutableStateOf(project.codec) }

    val resolvedW = (widthStr.toIntOrNull() ?: project.width).coerceAtLeast(16)
    val resolvedH = (heightStr.toIntOrNull() ?: project.height).coerceAtLeast(16)
    val resolvedFps = (fpsStr.toIntOrNull() ?: project.fps).coerceIn(12, 120)
    val strictEditDurationMs = remember(project.layers, project.durationMs) {
        project.effectiveEditDurationMs()
    }
    val strictTotalFrames = remember(strictEditDurationMs, resolvedFps) {
        ((strictEditDurationMs * resolvedFps) / 1000L).coerceAtLeast(1L)
    }
    val effectiveMbps = preferences.effectiveExportMbps
    val estimatedSizeMb = remember(strictEditDurationMs, effectiveMbps) {
        ((strictEditDurationMs / 1000.0) * (effectiveMbps + 0.192) / 8.0)
    }

    fun pushConfig(
        p: String = presetLabel,
        w: String = widthStr,
        h: String = heightStr,
        f: String = fpsStr,
        c: String = codec
    ) {
        onUpdateExportConfig(
            p,
            w.toIntOrNull() ?: project.width,
            h.toIntOrNull() ?: project.height,
            f.toIntOrNull() ?: project.fps,
            c
        )
    }

    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing,
        containerColor = AmBgDark,
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack, modifier = Modifier.testTag("export_back_button")) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back to Editor")
                    }
                },
                title = {
                    Column {
                        Text(
                            text = "Export",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = TextPrimary
                        )
                        Text(
                            text = project.name,
                            style = MaterialTheme.typography.labelSmall,
                            color = KeyflowBlueBright
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
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Strict Edit Length & Hardware Single-Pass Telemetry Summary Banner
            Row(
                modifier = Modifier
                    .widthIn(max = 520.dp)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(KeyflowBlueContainer.copy(alpha = 0.55f))
                    .border(1.dp, KeyflowBlue.copy(alpha = 0.55f), RoundedCornerShape(12.dp))
                    .padding(horizontal = 14.dp, vertical = 10.dp)
                    .testTag("export_strict_duration_banner"),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        imageVector = Icons.Default.Bolt,
                        contentDescription = null,
                        tint = KeyflowBlueBright,
                        modifier = Modifier.size(18.dp)
                    )
                    Column {
                        Text(
                            text = "Edit Length: ${formatExportDuration(strictEditDurationMs)} ($strictTotalFrames frames)",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold,
                            color = TextPrimary
                        )
                        Text(
                            text = "${resolvedW}×${resolvedH} • ${resolvedFps}fps • ${effectiveMbps} Mbps • ~${"%.1f".format(estimatedSizeMb)} MB",
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = FontFamily.Monospace,
                            color = KeyflowBlueBright
                        )
                    }
                }
            }

            // Hardware Codec Cards (Single Source of Truth — no duplicate bottom Codec row)
            Column(
                modifier = Modifier
                    .widthIn(max = 520.dp)
                    .fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                AmExportFormatCard(
                    icon = Icons.Default.MovieCreation,
                    title = "MP4 (H.264 / AVC)",
                    subtitle = "Fastest hardware compatibility • ${resolvedW}×${resolvedH} @ ${resolvedFps}fps",
                    selected = codec != "H.265",
                    onClick = {
                        codec = "H.264"
                        onUpdatePreferences { it.copy(preferredVideoCodec = "H.264") }
                        pushConfig(c = "H.264")
                    }
                )
                AmExportFormatCard(
                    icon = Icons.Default.HighQuality,
                    title = "MP4 (H.265 / HEVC)",
                    subtitle = "Higher efficiency master • ${resolvedW}×${resolvedH} @ ${resolvedFps}fps",
                    selected = codec == "H.265",
                    onClick = {
                        codec = "H.265"
                        onUpdatePreferences { it.copy(preferredVideoCodec = "H.265") }
                        pushConfig(c = "H.265")
                    }
                )
            }

            // Quality & Bitrate Picker Card (Low -> Medium -> High -> Ultra -> Custom Mbps)
            Card(
                colors = CardDefaults.cardColors(containerColor = SurfaceDark),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier
                    .widthIn(max = 520.dp)
                    .fillMaxWidth()
                    .border(1.dp, OutlineSubtle, RoundedCornerShape(14.dp))
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Quality & Bitrate",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = TextPrimary
                        )
                        Text(
                            text = "$effectiveMbps Mbps",
                            style = MaterialTheme.typography.labelMedium,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            color = KeyflowBlueBright
                        )
                    }

                    val qualityTiers = listOf(
                        "Low" to BitrateProfile.LOW_6M,
                        "Economy" to BitrateProfile.ECONOMY_8M,
                        "Medium" to BitrateProfile.STANDARD_16M,
                        "High" to BitrateProfile.HIGH_32M,
                        "Ultra" to BitrateProfile.MASTER_50M,
                        "Custom" to BitrateProfile.CUSTOM
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        qualityTiers.forEach { (label, profile) ->
                            AmSegmentChip(
                                label = label,
                                selected = preferences.exportBitrateProfile == profile,
                                onClick = {
                                    onUpdatePreferences { it.copy(exportBitrateProfile = profile) }
                                },
                                testTag = "export_quality_${label.lowercase()}"
                            )
                        }
                    }

                    if (preferences.exportBitrateProfile == BitrateProfile.CUSTOM) {
                        AmTickWheelScrubber(
                            value = preferences.customExportMbps.toFloat(),
                            minValue = 1f,
                            maxValue = 120f,
                            unit = " Mbps",
                            label = "Custom Bitrate",
                            fineStep = 1f,
                            onValueChange = { next ->
                                val mbps = next.roundToInt().coerceIn(1, 120)
                                onUpdatePreferences {
                                    it.copy(
                                        exportBitrateProfile = BitrateProfile.CUSTOM,
                                        customExportMbps = mbps
                                    )
                                }
                            },
                            onReset = {
                                onUpdatePreferences { it.copy(customExportMbps = 24) }
                            },
                            testTag = "export_custom_mbps_slider"
                        )
                    }
                }
            }

            // Resolution & Frame Rate Card
            Card(
                colors = CardDefaults.cardColors(containerColor = SurfaceDark),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier
                    .widthIn(max = 520.dp)
                    .fillMaxWidth()
                    .border(1.dp, OutlineSubtle, RoundedCornerShape(14.dp))
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        text = "Resolution & Frame Rate",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary
                    )

                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        AspectRatioPreset.entries.forEach { preset ->
                            AmRatioOutlineCard(
                                preset = preset,
                                selected = presetLabel == preset.label,
                                onClick = {
                                    presetLabel = preset.label
                                    if (preset != AspectRatioPreset.CUSTOM) {
                                        widthStr = preset.defaultWidth.toString()
                                        heightStr = preset.defaultHeight.toString()
                                    }
                                    pushConfig(p = preset.label, w = widthStr, h = heightStr)
                                }
                            )
                        }
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = widthStr,
                            onValueChange = {
                                widthStr = it.filter(Char::isDigit)
                                presetLabel = "Custom"
                                pushConfig(p = "Custom", w = widthStr)
                            },
                            label = { Text("Width") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            singleLine = true,
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.weight(1f)
                        )
                        OutlinedTextField(
                            value = heightStr,
                            onValueChange = {
                                heightStr = it.filter(Char::isDigit)
                                presetLabel = "Custom"
                                pushConfig(p = "Custom", h = heightStr)
                            },
                            label = { Text("Height") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            singleLine = true,
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.weight(1f)
                        )
                    }

                    Text("Frame Rate", style = MaterialTheme.typography.labelLarge, color = TextSecondary)
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        listOf(24, 25, 30, 48, 60, 120).forEach { fpsOpt ->
                            AmSegmentChip(
                                label = "${fpsOpt}fps",
                                selected = resolvedFps == fpsOpt,
                                onClick = {
                                    fpsStr = fpsOpt.toString()
                                    pushConfig(f = fpsStr)
                                }
                            )
                        }
                    }

                    AmTickWheelScrubber(
                        value = resolvedFps.toFloat(),
                        minValue = 12f,
                        maxValue = 120f,
                        unit = " fps",
                        label = "Exact FPS",
                        fineStep = 1f,
                        onValueChange = { next ->
                            fpsStr = next.roundToInt().coerceIn(12, 120).toString()
                            pushConfig(f = fpsStr)
                        },
                        onReset = {
                            fpsStr = "60"
                            pushConfig(f = "60")
                        },
                        testTag = "export_fps_slider"
                    )
                }
            }

            // Export Progress & Primary KeyFlow Royal Blue Action Card
            Card(
                colors = CardDefaults.cardColors(containerColor = SurfaceDark),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier
                    .widthIn(max = 520.dp)
                    .fillMaxWidth()
                    .border(1.dp, OutlineSubtle, RoundedCornerShape(14.dp))
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    if (exportState.statusText.isNotBlank()) {
                        Text(
                            text = exportState.statusText,
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary
                        )
                    }

                    if (exportState.isExporting) {
                        LinearProgressIndicator(
                            progress = { exportState.progress },
                            color = KeyflowBlue,
                            trackColor = PanelDark,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(8.dp)
                                .clip(RoundedCornerShape(4.dp))
                        )
                    }

                    if (exportState.errorText != null) {
                        Text(
                            text = exportState.errorText,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }

                    if (exportState.lastOutcome != null) {
                        val out = exportState.lastOutcome
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.CheckCircle,
                                contentDescription = null,
                                tint = KeyflowBlueBright
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Saved (${out.width}×${out.height} • ${out.fps}fps • ${formatExportDuration(out.durationMs)})",
                                style = MaterialTheme.typography.labelMedium,
                                color = KeyflowBlueBright
                            )
                        }
                    }

                    Button(
                        onClick = { onStartExport(context) },
                        enabled = !exportState.isExporting,
                        shape = RoundedCornerShape(11.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = KeyflowBlue,
                            contentColor = Color.White
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(50.dp)
                            .testTag("start_mp4_export_button")
                    ) {
                        if (exportState.isExporting) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                color = Color.White,
                                strokeWidth = 2.2.dp
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(
                                text = "Exporting ${(exportState.progress * 100).toInt()}%",
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                        } else {
                            Icon(
                                Icons.Default.IosShare,
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(19.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Export Video (${formatExportDuration(strictEditDurationMs)})",
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                        }
                    }
                }
            }

            // Project File Export (.fpxml Native Engine + Plugins & Alight Motion .xml)
            Card(
                colors = CardDefaults.cardColors(containerColor = SurfaceDark),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier
                    .widthIn(max = 520.dp)
                    .fillMaxWidth()
                    .border(1.dp, OutlineSubtle, RoundedCornerShape(14.dp))
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Project File Export (XML)",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = TextPrimary
                            )
                            Text(
                                text = "Resolution, FPS, transforms, curves & custom plugins",
                                style = MaterialTheme.typography.labelSmall,
                                color = TextSecondary
                            )
                        }
                        OutlinedButton(
                            onClick = {
                                importXmlLauncher.launch(arrayOf("text/xml", "application/xml", "*/*"))
                            },
                            shape = RoundedCornerShape(9.dp),
                            modifier = Modifier
                                .height(48.dp)
                                .testTag("import_xml_from_export_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.FileOpen,
                                contentDescription = "Import XML",
                                tint = KeyflowBlueBright,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "Import XML",
                                style = MaterialTheme.typography.labelMedium,
                                color = KeyflowBlueBright
                            )
                        }
                    }

                    AmExportFormatCard(
                        icon = Icons.Default.DataObject,
                        title = "Export Engine File (.fpxml)",
                        subtitle = "${resolvedW}×${resolvedH} @ ${resolvedFps}fps • 3D transforms, curves & imported GLSL plugins",
                        selected = true,
                        testTag = "export_fpxml_button",
                        onClick = {
                            pushConfig()
                            createFpXmlLauncher.launch("${safeBaseFileName}.fpxml")
                        }
                    )

                    AmExportFormatCard(
                        icon = Icons.Default.Code,
                        title = "Export Alight Motion (.xml)",
                        subtitle = "Compatible <scene> XML • bookmarks, keyframes, blends & mapped effects",
                        selected = false,
                        testTag = "export_am_xml_button",
                        onClick = {
                            pushConfig()
                            createAmXmlLauncher.launch("${safeBaseFileName}.xml")
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun AmExportFormatCard(
    icon: ImageVector,
    title: String,
    subtitle: String,
    selected: Boolean,
    testTag: String? = null,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (testTag != null) Modifier.testTag(testTag) else Modifier)
            .clip(RoundedCornerShape(12.dp))
            .background(if (selected) KeyflowBlueContainer.copy(alpha = 0.65f) else SurfaceDark)
            .border(
                width = if (selected) 1.4.dp else 1.dp,
                color = if (selected) KeyflowBlue else OutlineSubtle,
                shape = RoundedCornerShape(12.dp)
            )
            .clickable(onClick = onClick)
            .padding(13.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
            Box(
                modifier = Modifier
                    .size(38.dp)
                    .clip(RoundedCornerShape(9.dp))
                    .background(PanelDark),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = if (selected) KeyflowBlueBright else TextSecondary,
                    modifier = Modifier.size(20.dp)
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = if (selected) KeyflowBlueBright else MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = TextSecondary
                )
            }
        }
        Icon(
            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = if (selected) KeyflowBlue else TextSecondary
        )
    }
}
