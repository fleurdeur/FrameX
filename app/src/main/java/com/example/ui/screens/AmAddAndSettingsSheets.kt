package com.example.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.BookmarkAdd
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.ViewInAr
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.data.model.AspectRatioPreset
import com.example.data.model.LayerType
import com.example.data.model.Project
import com.example.ui.components.AmPrimaryCtaButton
import com.example.ui.components.AmSegmentChip
import com.example.ui.theme.AmTeal
import com.example.ui.theme.AmTealBright
import com.example.ui.theme.AudioCyan
import com.example.ui.theme.DeepBlack
import com.example.ui.theme.ElectricBlue
import com.example.ui.theme.KeyframeAmber
import com.example.ui.theme.NeonPink
import com.example.ui.theme.OutlineSubtle
import com.example.ui.theme.PanelDark
import com.example.ui.theme.RigGreen
import com.example.ui.theme.SurfaceDark
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import com.example.viewmodel.StudioDestination
import kotlin.math.cos
import kotlin.math.sin

private val AM_SHAPES = listOf(
    "Rectangle",
    "Rounded Rect",
    "Circle",
    "Star",
    "Triangle",
    "Hexagon"
)

/**
 * 1:1 Alight Motion Scaled-Down Floating '+' FAB Popover Menu!
 * Floats compactly over the lower portion of the timeline so the time ruler and layers remain visible,
 * keeping all Alight Motion tabs (Shape | Media | Audio | Object | Element) and bottom quick pills
 * ([Camera] [Null] [Text] [×]) scaled down cleanly.
 */
@Composable
fun AmAddElementBottomSheet(
    hasCopiedLayer: Boolean,
    onAddShapeKind: (String) -> Unit,
    onAddLayerType: (LayerType) -> Unit,
    onPickVideo: () -> Unit,
    onPickImage: () -> Unit,
    onPickAudio: () -> Unit,
    onPasteLayer: () -> Unit,
    onAddBeatBookmark: () -> Unit,
    onNavigateTool: (StudioDestination) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    var activeTab by remember { mutableIntStateOf(0) }
    val tabs = listOf("Shape", "Media", "Audio", "Object", "Element")
    val dismissInteraction = remember { MutableInteractionSource() }
    val cardInteraction = remember { MutableInteractionSource() }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(DeepBlack.copy(alpha = 0.28f))
            .clickable(
                interactionSource = dismissInteraction,
                indication = null,
                onClick = onDismiss
            ),
        contentAlignment = Alignment.BottomCenter
    ) {
        Surface(
            color = SurfaceDark.copy(alpha = 0.97f),
            shape = RoundedCornerShape(16.dp),
            tonalElevation = 10.dp,
            modifier = Modifier
                .widthIn(max = 520.dp)
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 10.dp, vertical = 8.dp)
                .shadow(12.dp, RoundedCornerShape(16.dp))
                .border(1.dp, OutlineSubtle, RoundedCornerShape(16.dp))
                .clickable(
                    interactionSource = cardInteraction,
                    indication = null,
                    onClick = {}
                )
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // 1. Slim Alight Motion Tab Bar (Shape | Media | Audio | Object | Element)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    tabs.forEachIndexed { idx, title ->
                        val isSel = activeTab == idx
                        Column(
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (isSel) AmTeal.copy(alpha = 0.16f) else Color.Transparent)
                                .clickable { activeTab = idx }
                                .padding(horizontal = 10.dp, vertical = 5.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                text = title,
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = if (isSel) FontWeight.Bold else FontWeight.Medium,
                                color = if (isSel) AmTealBright else TextSecondary
                            )
                        }
                    }
                }

                // 2. Compact Scaled-Down Single-Row Content Area (~56dp tall so layers stay visible above!)
                when (activeTab) {
                    0 -> {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(7.dp)
                        ) {
                            AM_SHAPES.forEach { shapeName ->
                                Column(
                                    modifier = Modifier
                                        .width(68.dp)
                                        .height(54.dp)
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(PanelDark)
                                        .border(1.dp, OutlineSubtle, RoundedCornerShape(10.dp))
                                        .clickable {
                                            onAddShapeKind(shapeName)
                                            onDismiss()
                                        }
                                        .padding(horizontal = 4.dp, vertical = 5.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.Center
                                ) {
                                    AmShapePreviewGlyph(shapeKind = shapeName, sizeDp = 20.dp)
                                    Spacer(modifier = Modifier.height(3.dp))
                                    Text(
                                        text = shapeName.replace("Rounded Rect", "Round"),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = TextPrimary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }
                    }

                    1 -> {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(7.dp)
                        ) {
                            AmCompactMediaButton(
                                icon = Icons.Default.Movie,
                                title = "Video",
                                subtitle = "MP4 / Remap",
                                tint = ElectricBlue,
                                onClick = {
                                    onDismiss()
                                    onPickVideo()
                                },
                                modifier = Modifier.weight(1f)
                            )
                            AmCompactMediaButton(
                                icon = Icons.Default.Image,
                                title = "Image",
                                subtitle = "PNG / JPG",
                                tint = KeyframeAmber,
                                onClick = {
                                    onDismiss()
                                    onPickImage()
                                },
                                modifier = Modifier.weight(1f)
                            )
                            AmCompactMediaButton(
                                icon = Icons.Default.MusicNote,
                                title = "Audio",
                                subtitle = "MP3 / M4A",
                                tint = AmTealBright,
                                onClick = {
                                    onDismiss()
                                    onPickAudio()
                                },
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }

                    2 -> {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(7.dp)
                        ) {
                            AmCompactMediaButton(
                                icon = Icons.Default.MusicNote,
                                title = "Audio File",
                                subtitle = "MP3 / WAV",
                                tint = AmTealBright,
                                onClick = {
                                    onDismiss()
                                    onPickAudio()
                                },
                                modifier = Modifier.weight(1f)
                            )
                            AmCompactMediaButton(
                                icon = Icons.Default.GraphicEq,
                                title = "Reactor",
                                subtitle = "FFT Sync",
                                tint = AudioCyan,
                                onClick = {
                                    onDismiss()
                                    onNavigateTool(StudioDestination.AUDIO_REACTOR)
                                },
                                modifier = Modifier.weight(1f)
                            )
                            AmCompactMediaButton(
                                icon = Icons.Default.BookmarkAdd,
                                title = "Bookmark",
                                subtitle = "Add Flag",
                                tint = NeonPink,
                                onClick = {
                                    onAddBeatBookmark()
                                    onDismiss()
                                },
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }

                    3 -> {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(7.dp)
                        ) {
                            AmCompactMediaButton(
                                icon = Icons.Default.Videocam,
                                title = "Camera",
                                subtitle = "2D/3D Lens",
                                tint = KeyframeAmber,
                                onClick = {
                                    onAddLayerType(LayerType.CAMERA)
                                    onDismiss()
                                },
                                modifier = Modifier.weight(1f)
                            )
                            AmCompactMediaButton(
                                icon = Icons.Default.ViewInAr,
                                title = "3D Mesh",
                                subtitle = "Cube / Torus",
                                tint = AudioCyan,
                                onClick = {
                                    onAddLayerType(LayerType.MODEL_3D)
                                    onDismiss()
                                },
                                modifier = Modifier.weight(1f)
                            )
                            AmCompactMediaButton(
                                icon = Icons.Default.ViewInAr,
                                title = "3D Stage",
                                subtitle = "Orbit & .OBJ",
                                tint = AmTeal,
                                onClick = {
                                    onDismiss()
                                    onNavigateTool(StudioDestination.WORKSPACE_3D)
                                },
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }

                    else -> {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            AmCompactMediaButton(
                                icon = Icons.Default.AccountTree,
                                title = "Null Rig",
                                subtitle = "Parent Helper",
                                tint = RigGreen,
                                onClick = {
                                    onAddLayerType(LayerType.NULL_OBJECT)
                                    onDismiss()
                                },
                                modifier = Modifier.weight(1f)
                            )
                            AmCompactMediaButton(
                                icon = Icons.Default.ContentPaste,
                                title = if (hasCopiedLayer) "Paste Layer" else "Clipboard",
                                subtitle = if (hasCopiedLayer) "Insert Copy" else "Empty",
                                tint = if (hasCopiedLayer) AmTealBright else TextMuted,
                                onClick = {
                                    if (hasCopiedLayer) {
                                        onPasteLayer()
                                        onDismiss()
                                    }
                                },
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }

                // 3. Scaled-Down 1:1 Alight Motion Bottom Quick-Action Row + Circular '×' FAB!
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    AmQuickFabPill(
                        icon = Icons.Default.Videocam,
                        label = "Camera",
                        iconTint = KeyframeAmber,
                        filled = false,
                        onClick = {
                            onAddLayerType(LayerType.CAMERA)
                            onDismiss()
                        },
                        modifier = Modifier.weight(1f)
                    )
                    AmQuickFabPill(
                        icon = Icons.Default.AccountTree,
                        label = "Null",
                        iconTint = RigGreen,
                        filled = false,
                        onClick = {
                            onAddLayerType(LayerType.NULL_OBJECT)
                            onDismiss()
                        },
                        modifier = Modifier.weight(1f)
                    )
                    AmQuickFabPill(
                        icon = Icons.Default.TextFields,
                        label = "Text",
                        iconTint = DeepBlack,
                        filled = true,
                        onClick = {
                            onAddLayerType(LayerType.TEXT)
                            onDismiss()
                        },
                        modifier = Modifier.weight(1.08f)
                    )

                    // Scaled-down circular Alight Motion '×' Close FAB aligned with the '+' FAB corner!
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .background(Brush.linearGradient(listOf(AmTealBright, AmTeal)))
                            .clickable(onClick = onDismiss)
                            .testTag("close_add_drawer_fab"),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Close add menu",
                            tint = DeepBlack,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AmQuickFabPill(
    icon: ImageVector,
    label: String,
    iconTint: Color,
    filled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .height(36.dp)
            .clip(CircleShape)
            .background(if (filled) AmTeal else PanelDark)
            .then(
                if (!filled) Modifier.border(1.dp, OutlineSubtle, CircleShape) else Modifier
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        Icon(imageVector = icon, contentDescription = null, tint = iconTint, modifier = Modifier.size(15.dp))
        Spacer(modifier = Modifier.width(4.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = if (filled) DeepBlack else TextPrimary,
            fontWeight = if (filled) FontWeight.Bold else FontWeight.SemiBold,
            maxLines = 1
        )
    }
}

@Composable
fun AmShapePreviewGlyph(
    shapeKind: String,
    sizeDp: androidx.compose.ui.unit.Dp = 24.dp,
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier.size(sizeDp)) {
        val stroke = Stroke(width = 2.3f)
        val w = size.width
        val h = size.height
        val pad = 2.5f
        when (shapeKind) {
            "Circle" -> drawCircle(
                color = AmTealBright,
                radius = (size.minDimension - pad * 2f) * 0.5f,
                style = stroke
            )
            "Rectangle" -> drawRect(
                color = AmTealBright,
                topLeft = Offset(pad, pad + 2.5f),
                size = Size(w - pad * 2f, h - (pad + 2.5f) * 2f),
                style = stroke
            )
            "Star" -> {
                val path = Path()
                val cx = w * 0.5f
                val cy = h * 0.5f
                val outerR = w * 0.45f
                val innerR = outerR * 0.44f
                for (i in 0 until 10) {
                    val r = if (i % 2 == 0) outerR else innerR
                    val a = Math.toRadians((i * 36.0) - 90.0)
                    val px = cx + (r * cos(a)).toFloat()
                    val py = cy + (r * sin(a)).toFloat()
                    if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
                }
                path.close()
                drawPath(path, AmTealBright, style = stroke)
            }
            "Triangle" -> {
                val path = Path().apply {
                    moveTo(w * 0.5f, pad)
                    lineTo(w - pad, h - pad)
                    lineTo(pad, h - pad)
                    close()
                }
                drawPath(path, AmTealBright, style = stroke)
            }
            "Hexagon" -> {
                val path = Path()
                val cx = w * 0.5f
                val cy = h * 0.5f
                val r = w * 0.44f
                for (i in 0 until 6) {
                    val a = Math.toRadians((i * 60.0) - 90.0)
                    val px = cx + (r * cos(a)).toFloat()
                    val py = cy + (r * sin(a)).toFloat()
                    if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
                }
                path.close()
                drawPath(path, AmTealBright, style = stroke)
            }
            else -> drawRoundRect(
                color = AmTealBright,
                topLeft = Offset(pad, pad + 2f),
                size = Size(w - pad * 2f, h - (pad + 2f) * 2f),
                cornerRadius = CornerRadius(5f, 5f),
                style = stroke
            )
        }
    }
}

@Composable
private fun AmCompactMediaButton(
    icon: ImageVector,
    title: String,
    subtitle: String,
    tint: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .height(52.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(PanelDark)
            .border(1.dp, OutlineSubtle, RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp)
    ) {
        Box(
            modifier = Modifier
                .size(28.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(tint.copy(alpha = 0.16f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(imageVector = icon, contentDescription = title, tint = tint, modifier = Modifier.size(16.dp))
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelMedium,
                color = TextPrimary,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = TextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/**
 * 1:1 Alight Motion Project Settings Modal (opened via the ⚙ gear icon next to the Project Title!).
 */
@Composable
fun AmProjectSettingsDialog(
    project: Project,
    onSaveSettings: (name: String, width: Int, height: Int, fps: Int, durationSec: Int, backgroundColor: String) -> Unit,
    onDismiss: () -> Unit
) {
    var name by remember(project.name) { mutableStateOf(project.name) }
    var widthText by remember(project.width) { mutableStateOf(project.width.toString()) }
    var heightText by remember(project.height) { mutableStateOf(project.height.toString()) }
    var fps by remember(project.fps) { mutableIntStateOf(project.fps) }
    var durationSecText by remember(project.durationMs) { mutableStateOf((project.durationMs / 1000L).coerceAtLeast(1L).toString()) }
    var bgColor by remember(project.backgroundColor) { mutableStateOf(project.backgroundColor) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            color = SurfaceDark,
            shape = RoundedCornerShape(18.dp),
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .widthIn(max = 460.dp)
                .border(1.dp, OutlineSubtle, RoundedCornerShape(18.dp))
        ) {
            Column(
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Project Settings",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    IconButton(onClick = onDismiss, modifier = Modifier.size(36.dp)) {
                        Icon(Icons.Default.Close, contentDescription = "Close")
                    }
                }

                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Project Name") },
                    singleLine = true,
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth()
                )

                Text("Aspect Ratio Presets", style = MaterialTheme.typography.labelMedium, color = TextSecondary)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    AspectRatioPreset.entries.filter { it != AspectRatioPreset.CUSTOM }.forEach { preset ->
                        AmSegmentChip(
                            label = preset.label,
                            selected = widthText == preset.defaultWidth.toString() && heightText == preset.defaultHeight.toString(),
                            onClick = {
                                widthText = preset.defaultWidth.toString()
                                heightText = preset.defaultHeight.toString()
                            }
                        )
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = widthText,
                        onValueChange = { widthText = it.filter(Char::isDigit) },
                        label = { Text("Width (px)") },
                        singleLine = true,
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = heightText,
                        onValueChange = { heightText = it.filter(Char::isDigit) },
                        label = { Text("Height (px)") },
                        singleLine = true,
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.weight(1f)
                    )
                }

                Text("Frame Rate (FPS)", style = MaterialTheme.typography.labelMedium, color = TextSecondary)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    listOf(24, 25, 30, 48, 60, 120).forEach { rate ->
                        AmSegmentChip(
                            label = "${rate}fps",
                            selected = fps == rate,
                            onClick = { fps = rate }
                        )
                    }
                }

                OutlinedTextField(
                    value = durationSecText,
                    onValueChange = { durationSecText = it.filter(Char::isDigit) },
                    label = { Text("Composition Duration (seconds)") },
                    singleLine = true,
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth()
                )

                Text("Composition Background", style = MaterialTheme.typography.labelMedium, color = TextSecondary)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    listOf("Black", "Dark Gray", "White", "Transparent").forEach { opt ->
                        AmSegmentChip(
                            label = opt,
                            selected = bgColor == opt,
                            onClick = { bgColor = opt }
                        )
                    }
                }

                AmPrimaryCtaButton(
                    text = "APPLY SETTINGS",
                    onClick = {
                        onSaveSettings(
                            name,
                            widthText.toIntOrNull() ?: project.width,
                            heightText.toIntOrNull() ?: project.height,
                            fps,
                            durationSecText.toIntOrNull() ?: 10,
                            bgColor
                        )
                        onDismiss()
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("apply_project_settings_button")
                )
            }
        }
    }
}
