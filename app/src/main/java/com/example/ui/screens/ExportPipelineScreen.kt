@file:OptIn(ExperimentalMaterial3Api::class)

package com.example.ui.screens

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
import androidx.compose.material.icons.filled.CheckCircle
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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.example.data.model.AspectRatioPreset
import com.example.data.model.Project
import com.example.ui.components.AmRatioOutlineCard
import com.example.ui.components.AmSegmentChip
import com.example.ui.theme.AmBgDark
import com.example.ui.theme.AmTeal
import com.example.ui.theme.AmTealBright
import com.example.ui.theme.DeepBlack
import com.example.ui.theme.OutlineSubtle
import com.example.ui.theme.PanelDark
import com.example.ui.theme.SurfaceDark
import com.example.ui.theme.TextSecondary
import com.example.viewmodel.ExportUiState

@Composable
fun ExportPipelineScreen(
    project: Project,
    exportState: ExportUiState,
    onUpdateExportConfig: (preset: String, width: Int, height: Int, fps: Int, codec: String) -> Unit,
    onStartExport: (android.content.Context) -> Unit,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    var presetLabel by remember(project.aspectRatioPreset) { mutableStateOf(project.aspectRatioPreset) }
    var widthStr by remember(project.width) { mutableStateOf(project.width.toString()) }
    var heightStr by remember(project.height) { mutableStateOf(project.height.toString()) }
    var fpsStr by remember(project.fps) { mutableStateOf(project.fps.toString()) }
    var codec by remember(project.codec) { mutableStateOf(project.codec) }
    var selectedExportMode by remember { mutableIntStateOf(0) } // 0 = Video MP4, 1 = High-Bitrate Master

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
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "${project.name} • ${project.layers.size} Layers",
                            style = MaterialTheme.typography.labelSmall,
                            color = TextSecondary
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
            // Alight Motion Export Format Selector Rows ("Video >", "High-Bitrate Master >")
            Column(
                modifier = Modifier
                    .widthIn(max = 520.dp)
                    .fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                AmExportFormatCard(
                    icon = Icons.Default.MovieCreation,
                    title = "Video (MP4)",
                    subtitle = "${widthStr}×${heightStr} • ${fpsStr}fps • $codec",
                    selected = selectedExportMode == 0,
                    onClick = {
                        selectedExportMode = 0
                        codec = "H.264"
                        pushConfig(c = "H.264")
                    }
                )
                AmExportFormatCard(
                    icon = Icons.Default.HighQuality,
                    title = "Cinema Master (HEVC / H.265)",
                    subtitle = "10-bit High Efficiency Hardware Pipeline",
                    selected = selectedExportMode == 1,
                    onClick = {
                        selectedExportMode = 1
                        codec = "H.265"
                        pushConfig(c = "H.265")
                    }
                )
            }

            // Alight Motion Expandable Export Settings Panel
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
                    Text(
                        text = "Export Settings",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )

                    // Aspect Ratio Wireframe Boxes
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

                    // Custom Width × Height
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedTextField(
                            value = widthStr,
                            onValueChange = {
                                widthStr = it.filter(Char::isDigit)
                                presetLabel = "Custom"
                                pushConfig(p = "Custom", w = widthStr)
                            },
                            label = { Text("Width (px)") },
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
                            label = { Text("Height (px)") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            singleLine = true,
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.weight(1f)
                        )
                    }

                    // Frame Rate Pills
                    Text("Frame Rate", style = MaterialTheme.typography.labelLarge, color = TextSecondary)
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        listOf(24, 25, 30, 48, 60, 120).forEach { fpsOpt ->
                            AmSegmentChip(
                                label = "${fpsOpt}fps",
                                selected = fpsStr == fpsOpt.toString(),
                                onClick = {
                                    fpsStr = fpsOpt.toString()
                                    pushConfig(f = fpsStr)
                                }
                            )
                        }
                    }

                    OutlinedTextField(
                        value = fpsStr,
                        onValueChange = {
                            fpsStr = it.filter(Char::isDigit)
                            pushConfig(f = fpsStr)
                        },
                        label = { Text("Custom Frame Rate (FPS)") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.fillMaxWidth()
                    )

                    // Encoder Codec Pills
                    Text("Codec", style = MaterialTheme.typography.labelLarge, color = TextSecondary)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("H.264" to "H.264 (AVC)", "H.265" to "H.265 (HEVC)").forEach { (id, label) ->
                            AmSegmentChip(
                                label = label,
                                selected = codec == id,
                                onClick = {
                                    codec = id
                                    pushConfig(c = id)
                                }
                            )
                        }
                    }
                }
            }

            // Alight Motion Bottom Export Progress & Full-Width Teal EXPORT Button
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
                    Text(
                        text = exportState.statusText,
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary
                    )

                    if (exportState.isExporting) {
                        LinearProgressIndicator(
                            progress = { exportState.progress },
                            color = AmTeal,
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
                                tint = AmTealBright
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Saved MP4 (${out.width}×${out.height} @ ${out.fps}fps) to Movies/FramePollution",
                                style = MaterialTheme.typography.labelMedium,
                                color = AmTealBright
                            )
                        }
                    }

                    // Signature Full-Width Alight Motion Teal "EXPORT" Button
                    Button(
                        onClick = { onStartExport(context) },
                        enabled = !exportState.isExporting,
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = AmTeal,
                            contentColor = DeepBlack
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(52.dp)
                            .testTag("start_mp4_export_button")
                    ) {
                        if (exportState.isExporting) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                color = DeepBlack,
                                strokeWidth = 2.2.dp
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(
                                text = "EXPORTING ${(exportState.progress * 100).toInt()}%...",
                                fontWeight = FontWeight.Bold
                            )
                        } else {
                            Icon(Icons.Default.IosShare, contentDescription = null, modifier = Modifier.size(19.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "EXPORT (${widthStr}×${heightStr} • ${fpsStr}FPS)",
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
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
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(if (selected) AmTeal.copy(alpha = 0.14f) else SurfaceDark)
            .border(
                width = if (selected) 1.5.dp else 1.dp,
                color = if (selected) AmTeal else OutlineSubtle,
                shape = RoundedCornerShape(12.dp)
            )
            .clickable(onClick = onClick)
            .padding(14.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(9.dp))
                    .background(PanelDark),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = if (selected) AmTealBright else TextSecondary,
                    modifier = Modifier.size(20.dp)
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = if (selected) AmTealBright else MaterialTheme.colorScheme.onSurface
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
            tint = if (selected) AmTeal else TextSecondary
        )
    }
}
