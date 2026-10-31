package com.example.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AspectRatio
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.BlurLinear
import androidx.compose.material.icons.filled.BorderStyle
import androidx.compose.material.icons.filled.Category
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.FirstPage
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.LastPage
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Opacity
import androidx.compose.material.icons.filled.OpenWith
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.data.model.EffectNode
import com.example.data.model.Layer
import com.example.data.model.LayerType
import com.example.data.model.Primitive3DType
import com.example.data.model.PropertyTarget
import com.example.engine.AnimationEvaluator
import com.example.engine.AudioSpectrumSnapshot
import com.example.ui.components.AmKeyframeCluster
import com.example.ui.components.AmPrimaryCtaButton
import com.example.ui.components.AmSegmentChip
import com.example.ui.components.AmSquareIconButton
import com.example.ui.components.AmTickWheelScrubber
import com.example.ui.theme.AmTeal
import com.example.ui.theme.AmTealBright
import com.example.ui.theme.AmTealContainer
import com.example.ui.theme.AudioCyan
import com.example.ui.theme.DeepBlack
import com.example.ui.theme.ElectricBlue
import com.example.ui.theme.KeyframeAmber
import com.example.ui.theme.NeonPink
import com.example.ui.theme.OutlineSubtle
import com.example.ui.theme.PanelDark
import com.example.ui.theme.PanelElevated
import com.example.ui.theme.PlayheadWhite
import com.example.ui.theme.RigGreen
import com.example.ui.theme.ShapePurple
import com.example.ui.theme.SurfaceDark
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import com.example.viewmodel.AmInspectorTab
import com.example.viewmodel.StudioDestination
import kotlin.math.abs

private val AM_SWATCHES = listOf(
    "#00D2A8", "#1CF0C4", "#0094FF", "#FF3366",
    "#FFC400", "#24DC72", "#9B6BFF", "#FF6D00",
    "#00E5FF", "#F50057", "#FFFFFF", "#B0B8CC",
    "#3B4254", "#0C0E12"
)

private val AM_BLEND_MODES = listOf("Normal", "Multiply", "Screen", "Lighten", "Overlay", "Difference")
private val AM_SHAPE_KINDS = listOf("Rectangle", "Rounded Rect", "Circle", "Star", "Triangle", "Hexagon")

/**
 * 1:1 Alight Motion + KeyFlow Bottom Sheet displayed whenever a Layer is clicked on the Timeline!
 * Clean, airy, non-squeezed layout with pure icon trim controls and expressive tile cards.
 */
@Composable
fun AmClickedLayerSheet(
    activeTab: AmInspectorTab,
    selectedLayer: Layer,
    allLayers: List<Layer>,
    projectDurationMs: Long,
    selectedProperty: PropertyTarget,
    playheadMs: Long,
    audioSpectrum: AudioSpectrumSnapshot,
    hasCopiedEffects: Boolean,
    canMoveForward: Boolean = false,
    canMoveBackward: Boolean = false,
    onMoveLayerZOrder: (sendBackward: Boolean) -> Unit = {},
    onSetInspectorTab: (AmInspectorTab) -> Unit,
    onCollapseToTimeline: () -> Unit,
    onScrubPlayhead: (Long) -> Unit,
    onSelectProperty: (PropertyTarget) -> Unit,
    onUpdatePropertyValue: (Float) -> Unit,
    onUpdateExpression: (String) -> Unit,
    onDragLayerOnCanvas: (Float, Float) -> Unit,
    onResetTransform: () -> Unit,
    onToggleKeyframe: () -> Unit,
    onJumpKeyframe: (Boolean) -> Unit,
    onUpdateBezierHandles: (Float, Float, Float, Float) -> Unit,
    onPullStart: () -> Unit,
    onTrimStart: () -> Unit,
    onUpdateSpeed: (Float) -> Unit,
    onToggleReverse: () -> Unit,
    onToggleFreezeFrame: () -> Unit,
    onToggleTimeRemap: (Boolean) -> Unit,
    onApplyVelocityPreset: (String) -> Unit,
    onExtractAudio: () -> Unit,
    onUpdateAudioVolume: (Float, Boolean) -> Unit,
    onToggleMotionBlur: (Boolean, Float) -> Unit,
    onToggleClippingMask: () -> Unit,
    onFitOrFillComposition: (Boolean) -> Unit,
    onSplitLayer: () -> Unit,
    onTrimEnd: () -> Unit,
    onPullEnd: () -> Unit,
    onFlipLayer: (Boolean) -> Unit,
    onDuplicateLayer: () -> Unit,
    onDeleteLayer: () -> Unit,
    onToggleVisibility: (String) -> Unit,
    onToggleLock: (String) -> Unit,
    onSetLayerParent: (String, String?) -> Unit,
    onUpdateLayerColor: (String, String) -> Unit,
    onUpdateBorderShadow: (String, Boolean, Float, String, Boolean, Float, String) -> Unit,
    onUpdateBlendMode: (String, String) -> Unit,
    onUpdateShapeKind: (String, String) -> Unit,
    onUpdateTextLayer: (String, String, String) -> Unit,
    onUpdate3DPrimitive: (String, Primitive3DType) -> Unit,
    onUpdateEffectNode: (EffectNode) -> Unit,
    onRemoveEffectNode: (String) -> Unit,
    onCopyEffects: () -> Unit,
    onPasteEffects: () -> Unit,
    onNavigateTool: (StudioDestination) -> Unit,
    modifier: Modifier = Modifier
) {
    var parentMenuOpen by remember { mutableStateOf(false) }
    val nullCandidates = remember(allLayers, selectedLayer.id) {
        allLayers.filter {
            (it.type == LayerType.NULL_OBJECT || it.type == LayerType.SHAPE || it.type == LayerType.CAMERA) &&
                it.id != selectedLayer.id
        }
    }
    val parentName = allLayers.firstOrNull { it.id == selectedLayer.parentId }?.name

    Surface(
        color = SurfaceDark,
        modifier = modifier
            .fillMaxSize()
            .border(0.6.dp, OutlineSubtle)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .navigationBarsPadding()
        ) {
            // 1. CLEAN UN-SQUEEZED SELECTED LAYER HEADER BAR
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(PanelDark.copy(alpha = 0.70f))
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    AmSquareIconButton(
                        icon = Icons.Default.KeyboardArrowDown,
                        contentDescription = "Collapse to timeline",
                        onClick = onCollapseToTimeline,
                        sizeDp = 34.dp,
                        testTag = "collapse_layer_sheet_button"
                    )

                    val swatchColor = remember(selectedLayer.colorHex) {
                        runCatching { Color(android.graphics.Color.parseColor(selectedLayer.colorHex)) }
                            .getOrDefault(AmTeal)
                    }
                    Box(
                        modifier = Modifier
                            .size(11.dp)
                            .clip(CircleShape)
                            .background(swatchColor)
                            .border(1.dp, Color.White.copy(alpha = 0.65f), CircleShape)
                    )

                    Text(
                        text = selectedLayer.name,
                        style = MaterialTheme.typography.titleSmall,
                        color = TextPrimary,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )

                    // Compact Parent Rig Icon Pill
                    Box {
                        Row(
                            modifier = Modifier
                                .height(26.dp)
                                .clip(CircleShape)
                                .background(if (selectedLayer.parentId != null) RigGreen.copy(alpha = 0.18f) else PanelDark)
                                .border(
                                    1.dp,
                                    if (selectedLayer.parentId != null) RigGreen else OutlineSubtle,
                                    CircleShape
                                )
                                .clickable { parentMenuOpen = true }
                                .testTag("tool_rig_button")
                                .padding(horizontal = 7.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.AccountTree,
                                contentDescription = "Parent Null Rig",
                                tint = if (selectedLayer.parentId != null) RigGreen else TextSecondary,
                                modifier = Modifier.size(13.dp)
                            )
                            if (parentName != null) {
                                Spacer(modifier = Modifier.width(3.dp))
                                Text(
                                    text = parentName,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = RigGreen,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                        DropdownMenu(
                            expanded = parentMenuOpen,
                            onDismissRequest = { parentMenuOpen = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text("None (Unparented)") },
                                onClick = {
                                    onSetLayerParent(selectedLayer.id, null)
                                    parentMenuOpen = false
                                }
                            )
                            nullCandidates.forEach { cand ->
                                DropdownMenuItem(
                                    text = { Text("↳ ${cand.name}") },
                                    onClick = {
                                        onSetLayerParent(selectedLayer.id, cand.id)
                                        parentMenuOpen = false
                                    }
                                )
                            }
                        }
                    }
                }

                // Clean Ghost Action Icons (Reorder Up/Down, Visibility, Lock, Duplicate, Delete)
                Row(
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    AmSquareIconButton(
                        icon = Icons.Default.KeyboardArrowUp,
                        contentDescription = "Bring layer forward",
                        enabled = canMoveForward,
                        onClick = { onMoveLayerZOrder(false) },
                        sizeDp = 34.dp
                    )
                    AmSquareIconButton(
                        icon = Icons.Default.KeyboardArrowDown,
                        contentDescription = "Send layer backward",
                        enabled = canMoveBackward,
                        onClick = { onMoveLayerZOrder(true) },
                        sizeDp = 34.dp
                    )
                    AmSquareIconButton(
                        icon = if (selectedLayer.visible) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                        contentDescription = "Toggle layer visibility",
                        active = !selectedLayer.visible,
                        onClick = { onToggleVisibility(selectedLayer.id) },
                        sizeDp = 34.dp
                    )
                    AmSquareIconButton(
                        icon = if (selectedLayer.locked) Icons.Default.Lock else Icons.Default.LockOpen,
                        contentDescription = "Lock layer",
                        active = selectedLayer.locked,
                        onClick = { onToggleLock(selectedLayer.id) },
                        sizeDp = 34.dp
                    )
                    AmSquareIconButton(
                        icon = Icons.Default.ContentCopy,
                        contentDescription = "Duplicate layer",
                        onClick = onDuplicateLayer,
                        sizeDp = 34.dp,
                        testTag = "duplicate_layer_button"
                    )
                    AmSquareIconButton(
                        icon = Icons.Default.DeleteOutline,
                        contentDescription = "Delete layer",
                        tint = NeonPink,
                        onClick = onDeleteLayer,
                        sizeDp = 34.dp,
                        testTag = "delete_layer_button"
                    )
                }
            }

            // 2. SINGLE-CLIP MINI SCRUBBER + 1:1 ALIGHT MOTION ICON TIMING & SPEED STRIP
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(DeepBlack.copy(alpha = 0.65f))
                    .padding(horizontal = 10.dp, vertical = 5.dp),
                verticalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                val dur = projectDurationMs.coerceAtLeast(1L).toFloat()
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(18.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(PanelDark)
                        .pointerInput(projectDurationMs) {
                            detectTapGestures { offset ->
                                val frac = (offset.x / size.width.coerceAtLeast(1)).coerceIn(0f, 1f)
                                onScrubPlayhead((frac * projectDurationMs).toLong())
                            }
                        }
                        .pointerInput(projectDurationMs) {
                            detectHorizontalDragGestures { change, _ ->
                                change.consume()
                                val frac = (change.position.x / size.width.coerceAtLeast(1)).coerceIn(0f, 1f)
                                onScrubPlayhead((frac * projectDurationMs).toLong())
                            }
                        }
                ) {
                    val allKfTimes = remember(selectedLayer) {
                        PropertyTarget.entries.flatMap { t -> selectedLayer.propertyFor(t).keyframes.map { it.timeMs } }.distinct()
                    }
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        val sX = (selectedLayer.startTimeMs / dur).coerceIn(0f, 1f) * size.width
                        val eX = (selectedLayer.endTimeMs / dur).coerceIn(0f, 1f) * size.width
                        val clipW = (eX - sX).coerceAtLeast(8f)

                        drawRoundRect(
                            color = AmTeal.copy(alpha = 0.28f),
                            topLeft = Offset(sX, 2f),
                            size = Size(clipW, size.height - 4f),
                            cornerRadius = CornerRadius(5f, 5f)
                        )
                        drawRoundRect(
                            color = AmTealBright,
                            topLeft = Offset(sX, 2f),
                            size = Size(clipW, size.height - 4f),
                            cornerRadius = CornerRadius(5f, 5f),
                            style = Stroke(width = 1.6f)
                        )
                        drawRoundRect(PlayheadWhite, Offset(sX, 2f), Size(4.5f, size.height - 4f), CornerRadius(2f, 2f))
                        drawRoundRect(PlayheadWhite, Offset((eX - 4.5f).coerceAtLeast(0f), 2f), Size(4.5f, size.height - 4f), CornerRadius(2f, 2f))

                        allKfTimes.forEach { kt ->
                            val kx = (kt / dur).coerceIn(0f, 1f) * size.width
                            val cy = size.height * 0.5f
                            val d = Path().apply {
                                moveTo(kx, cy - 4f)
                                lineTo(kx + 4f, cy)
                                lineTo(kx, cy + 4f)
                                lineTo(kx - 4f, cy)
                                close()
                            }
                            drawPath(d, KeyframeAmber)
                        }

                        val px = (playheadMs / dur).coerceIn(0f, 1f) * size.width
                        drawLine(PlayheadWhite, Offset(px, 0f), Offset(px, size.height), strokeWidth = 2.2f)
                    }
                }

                // 1:1 Alight Motion Clean Icon Timing Bar (Icon-only trim/split buttons + Center Speed Pill!)
                val speedBadge = when {
                    selectedLayer.timeRemapEnabled -> "Remap"
                    selectedLayer.reversed -> "-${String.format("%.1f", selectedLayer.speedMultiplier)}x"
                    else -> "${String.format("%.2f", selectedLayer.speedMultiplier).trimEnd('0').trimEnd('.')}x"
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    AmTimingIconPill(
                        icon = Icons.Default.FirstPage,
                        contentDescription = "Pull clip start to playhead",
                        onClick = onPullStart,
                        modifier = Modifier.weight(1f),
                        testTag = "pull_start_button"
                    )
                    AmTimingIconPill(
                        icon = Icons.Default.SkipPrevious,
                        contentDescription = "Trim clip left of playhead",
                        onClick = onTrimStart,
                        modifier = Modifier.weight(1f),
                        testTag = "trim_start_button"
                    )
                    AmTimingBarButton(
                        icon = Icons.Default.Speed,
                        label = speedBadge,
                        active = activeTab == AmInspectorTab.SPEED_TIMING ||
                            selectedLayer.speedMultiplier != 1.0f ||
                            selectedLayer.timeRemapEnabled ||
                            selectedLayer.reversed,
                        onClick = { onSetInspectorTab(AmInspectorTab.SPEED_TIMING) },
                        modifier = Modifier.weight(1.45f),
                        testTag = "layer_speed_button"
                    )
                    AmTimingIconPill(
                        icon = Icons.Default.ContentCut,
                        contentDescription = "Split clip at playhead",
                        onClick = onSplitLayer,
                        modifier = Modifier.weight(1f),
                        testTag = "split_layer_button"
                    )
                    AmTimingIconPill(
                        icon = Icons.Default.SkipNext,
                        contentDescription = "Trim clip right of playhead",
                        onClick = onTrimEnd,
                        modifier = Modifier.weight(1f),
                        testTag = "trim_end_button"
                    )
                    AmTimingIconPill(
                        icon = Icons.Default.LastPage,
                        contentDescription = "Pull clip end to playhead",
                        onClick = onPullEnd,
                        modifier = Modifier.weight(1f),
                        testTag = "pull_end_button"
                    )
                }
            }

            // 3. EXPRESSIVE SUB-PANEL BODY
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            ) {
                when (activeTab) {
                    AmInspectorTab.LAYER_OVERVIEW, AmInspectorTab.TIMELINE -> {
                        val editTileTitle = when (selectedLayer.type) {
                            LayerType.TEXT -> "Edit Text"
                            LayerType.SHAPE -> "Edit Shape"
                            LayerType.MODEL_3D -> "Edit 3D Mesh"
                            LayerType.VIDEO -> "Video & Audio"
                            LayerType.IMAGE -> "Fit & Crop"
                            LayerType.AUDIO -> "Audio Controls"
                            LayerType.CAMERA -> "Camera Lens"
                            LayerType.NULL_OBJECT -> "Null Hierarchy"
                        }
                        val editTileSubtitle = when (selectedLayer.type) {
                            LayerType.TEXT -> selectedLayer.fontFamilyName
                            LayerType.SHAPE -> selectedLayer.shapeKind
                            LayerType.MODEL_3D -> selectedLayer.primitive3D.label
                            LayerType.VIDEO -> buildString {
                                if (selectedLayer.timeRemapEnabled) append("Time Remap • ")
                                if (selectedLayer.reversed) append("Reversed • ")
                                append("${selectedLayer.mediaWidth}×${selectedLayer.mediaHeight}")
                            }
                            LayerType.IMAGE -> "${selectedLayer.mediaWidth}×${selectedLayer.mediaHeight}"
                            LayerType.AUDIO -> if (selectedLayer.audioMuted) "Muted" else "Vol ${(selectedLayer.audioVolume * 100).toInt()}%"
                            else -> "Parent: ${parentName ?: "None"}"
                        }
                        val opacityEval = AnimationEvaluator.evaluateProperty(
                            selectedLayer.opacity,
                            playheadMs,
                            audioSpectrum
                        ).toInt()

                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (selectedLayer.type == LayerType.VIDEO || selectedLayer.type == LayerType.AUDIO) {
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    AmOverviewWideTile(
                                        icon = Icons.Default.Speed,
                                        title = if (selectedLayer.type == LayerType.VIDEO) "Speed & Remap" else "Speed & Pitch",
                                        subtitle = if (selectedLayer.timeRemapEnabled) "Velocity Curve Active"
                                        else if (selectedLayer.reversed) "Reversed • ${selectedLayer.speedMultiplier}x"
                                        else "Reverse • Freeze • Velocity",
                                        accent = KeyframeAmber,
                                        onClick = { onSetInspectorTab(AmInspectorTab.SPEED_TIMING) },
                                        modifier = Modifier.weight(1f),
                                        testTag = "tile_speed_timing"
                                    )
                                    if (selectedLayer.type == LayerType.VIDEO) {
                                        AmOverviewWideTile(
                                            icon = Icons.Default.GraphicEq,
                                            title = "Extract Audio",
                                            subtitle = if (selectedLayer.audioMuted) "Embedded Muted" else "Demux to Track",
                                            accent = AudioCyan,
                                            onClick = onExtractAudio,
                                            modifier = Modifier.weight(1f),
                                            testTag = "tile_extract_audio"
                                        )
                                    }
                                }
                            }

                            if (selectedLayer.type != LayerType.AUDIO) {
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    AmOverviewWideTile(
                                        icon = Icons.Default.Palette,
                                        title = "Color & Fill",
                                        subtitle = selectedLayer.colorHex,
                                        accent = AmTeal,
                                        onClick = { onSetInspectorTab(AmInspectorTab.COLOR_FILL) },
                                        modifier = Modifier.weight(1f),
                                        testTag = "tile_color_fill"
                                    )
                                    AmOverviewWideTile(
                                        icon = Icons.Default.BorderStyle,
                                        title = "Border & Shadow",
                                        subtitle = if (selectedLayer.shadowEnabled) "Stroke + Glow" else "${selectedLayer.strokeWidth.toInt()}px Stroke",
                                        accent = ElectricBlue,
                                        onClick = { onSetInspectorTab(AmInspectorTab.BORDER_SHADOW) },
                                        modifier = Modifier.weight(1f),
                                        testTag = "tile_border_shadow"
                                    )
                                }
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    AmOverviewWideTile(
                                        icon = Icons.Default.Opacity,
                                        title = "Blending & Mask",
                                        subtitle = if (selectedLayer.isClippingMask) "Mask • $opacityEval%" else "$opacityEval% • ${selectedLayer.blendMode}",
                                        accent = AudioCyan,
                                        onClick = { onSetInspectorTab(AmInspectorTab.BLENDING_OPACITY) },
                                        modifier = Modifier.weight(1f),
                                        testTag = "tile_blending_opacity"
                                    )
                                    AmOverviewWideTile(
                                        icon = Icons.Default.OpenWith,
                                        title = "Move & Transform",
                                        subtitle = "Pos, Pivot, Scale",
                                        accent = AmTealBright,
                                        onClick = { onSetInspectorTab(AmInspectorTab.MOVE_TRANSFORM) },
                                        modifier = Modifier.weight(1f),
                                        testTag = "tile_move_transform"
                                    )
                                }
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    AmOverviewWideTile(
                                        icon = when (selectedLayer.type) {
                                            LayerType.TEXT -> Icons.Default.TextFields
                                            LayerType.CAMERA -> Icons.Default.Videocam
                                            else -> Icons.Default.Category
                                        },
                                        title = editTileTitle,
                                        subtitle = editTileSubtitle,
                                        accent = ShapePurple,
                                        onClick = {
                                            if (selectedLayer.type == LayerType.VIDEO) {
                                                onSetInspectorTab(AmInspectorTab.SPEED_TIMING)
                                            } else {
                                                onSetInspectorTab(AmInspectorTab.EDIT_LAYER)
                                            }
                                        },
                                        modifier = Modifier.weight(1f),
                                        testTag = "tile_edit_layer"
                                    )
                                    AmOverviewWideTile(
                                        icon = Icons.Default.AutoFixHigh,
                                        title = "Effects",
                                        subtitle = buildString {
                                            if (selectedLayer.motionBlurEnabled) append("M-Blur • ")
                                            append(if (selectedLayer.effects.isEmpty()) "Add Shaders" else "${selectedLayer.effects.size} Active")
                                        },
                                        accent = NeonPink,
                                        badge = selectedLayer.effects.size.takeIf { it > 0 }?.toString(),
                                        onClick = { onSetInspectorTab(AmInspectorTab.EFFECTS) },
                                        modifier = Modifier.weight(1f),
                                        testTag = "tile_effects"
                                    )
                                }
                            } else {
                                AmOverviewWideTile(
                                    icon = Icons.Default.MusicNote,
                                    title = editTileTitle,
                                    subtitle = editTileSubtitle,
                                    accent = AudioCyan,
                                    onClick = { onSetInspectorTab(AmInspectorTab.SPEED_TIMING) },
                                    modifier = Modifier.fillMaxWidth(),
                                    testTag = "tile_edit_layer"
                                )
                            }
                        }
                    }

                    AmInspectorTab.SPEED_TIMING -> {
                        AmSpeedAndTimingPanel(
                            selectedLayer = selectedLayer,
                            playheadMs = playheadMs,
                            audioSpectrum = audioSpectrum,
                            onUpdateSpeed = onUpdateSpeed,
                            onToggleReverse = onToggleReverse,
                            onToggleFreezeFrame = onToggleFreezeFrame,
                            onToggleTimeRemap = onToggleTimeRemap,
                            onApplyVelocityPreset = onApplyVelocityPreset,
                            onSelectProperty = onSelectProperty,
                            onUpdateTimeRemapSec = onUpdatePropertyValue,
                            onToggleKeyframe = onToggleKeyframe,
                            onJumpKeyframe = onJumpKeyframe,
                            onSetInspectorTab = onSetInspectorTab,
                            onNavigateTool = onNavigateTool,
                            onExtractAudio = onExtractAudio,
                            onUpdateAudioVolume = onUpdateAudioVolume
                        )
                    }

                    AmInspectorTab.MOVE_TRANSFORM -> {
                        AmMoveTransformPanel(
                            selectedLayer = selectedLayer,
                            selectedProperty = selectedProperty,
                            playheadMs = playheadMs,
                            audioSpectrum = audioSpectrum,
                            onSelectProperty = onSelectProperty,
                            onUpdatePropertyValue = onUpdatePropertyValue,
                            onUpdateExpression = onUpdateExpression,
                            onDragLayerOnCanvas = onDragLayerOnCanvas,
                            onResetTransform = onResetTransform,
                            onToggleKeyframe = onToggleKeyframe,
                            onJumpKeyframe = onJumpKeyframe,
                            onSetInspectorTab = onSetInspectorTab,
                            onNavigateTool = onNavigateTool
                        )
                    }

                    AmInspectorTab.EASING_INLINE -> {
                        AmInlineEasingPanel(
                            selectedLayer = selectedLayer,
                            selectedProperty = selectedProperty,
                            playheadMs = playheadMs,
                            onUpdateBezierHandles = onUpdateBezierHandles,
                            onReturnToTransform = {
                                if (selectedProperty == PropertyTarget.TIME_REMAP) {
                                    onSetInspectorTab(AmInspectorTab.SPEED_TIMING)
                                } else {
                                    onSetInspectorTab(AmInspectorTab.MOVE_TRANSFORM)
                                }
                            },
                            onOpenFullGraph = { onNavigateTool(StudioDestination.GRAPH_EDITOR) }
                        )
                    }

                    AmInspectorTab.COLOR_FILL -> {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text("Fill & Tint Palette", style = MaterialTheme.typography.labelMedium, color = TextSecondary)
                            AM_SWATCHES.chunked(7).forEach { rowColors ->
                                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                    rowColors.forEach { hex ->
                                        val c = remember(hex) {
                                            runCatching { Color(android.graphics.Color.parseColor(hex)) }.getOrDefault(AmTeal)
                                        }
                                        val isSel = selectedLayer.colorHex.equals(hex, ignoreCase = true)
                                        Box(
                                            modifier = Modifier
                                                .size(34.dp)
                                                .clip(CircleShape)
                                                .background(c)
                                                .border(
                                                    width = if (isSel) 3.dp else 1.dp,
                                                    color = if (isSel) Color.White else OutlineSubtle,
                                                    shape = CircleShape
                                                )
                                                .clickable { onUpdateLayerColor(selectedLayer.id, hex) }
                                        )
                                    }
                                }
                            }
                        }
                    }

                    AmInspectorTab.BORDER_SHADOW -> {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("Stroke / Border", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                                Switch(
                                    checked = selectedLayer.strokeEnabled,
                                    onCheckedChange = { enabled ->
                                        onUpdateBorderShadow(
                                            selectedLayer.id, enabled, selectedLayer.strokeWidth,
                                            selectedLayer.strokeColorHex, selectedLayer.shadowEnabled,
                                            selectedLayer.shadowBlur, selectedLayer.shadowColorHex
                                        )
                                    },
                                    colors = SwitchDefaults.colors(checkedThumbColor = DeepBlack, checkedTrackColor = AmTeal)
                                )
                            }
                            if (selectedLayer.strokeEnabled) {
                                AmTickWheelScrubber(
                                    value = selectedLayer.strokeWidth,
                                    minValue = 0f,
                                    maxValue = 32f,
                                    unit = "px",
                                    label = "Stroke Width",
                                    onValueChange = { w ->
                                        onUpdateBorderShadow(
                                            selectedLayer.id, true, w,
                                            selectedLayer.strokeColorHex, selectedLayer.shadowEnabled,
                                            selectedLayer.shadowBlur, selectedLayer.shadowColorHex
                                        )
                                    }
                                )
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("Drop Shadow / Glow", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                                Switch(
                                    checked = selectedLayer.shadowEnabled,
                                    onCheckedChange = { sh ->
                                        onUpdateBorderShadow(
                                            selectedLayer.id, selectedLayer.strokeEnabled, selectedLayer.strokeWidth,
                                            selectedLayer.strokeColorHex, sh,
                                            selectedLayer.shadowBlur, selectedLayer.shadowColorHex
                                        )
                                    },
                                    colors = SwitchDefaults.colors(checkedThumbColor = DeepBlack, checkedTrackColor = AmTeal)
                                )
                            }
                            if (selectedLayer.shadowEnabled) {
                                AmTickWheelScrubber(
                                    value = selectedLayer.shadowBlur,
                                    minValue = 2f,
                                    maxValue = 60f,
                                    unit = "px",
                                    label = "Shadow Blur Radius",
                                    onValueChange = { blur ->
                                        onUpdateBorderShadow(
                                            selectedLayer.id, selectedLayer.strokeEnabled, selectedLayer.strokeWidth,
                                            selectedLayer.strokeColorHex, true,
                                            blur, selectedLayer.shadowColorHex
                                        )
                                    }
                                )
                            }
                        }
                    }

                    AmInspectorTab.BLENDING_OPACITY -> {
                        val opProp = selectedLayer.opacity
                        val opEval = AnimationEvaluator.evaluateProperty(opProp, playheadMs, audioSpectrum)
                        val hasKf = opProp.keyframes.any { abs(it.timeMs - playheadMs) <= 25L }

                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                AmKeyframeCluster(
                                    hasKeyframeAtPlayhead = hasKf,
                                    keyframeCount = opProp.keyframes.size,
                                    onPrevKeyframe = { onJumpKeyframe(false) },
                                    onToggleKeyframe = onToggleKeyframe,
                                    onNextKeyframe = { onJumpKeyframe(true) }
                                )
                                AmTickWheelScrubber(
                                    value = opEval,
                                    minValue = 0f,
                                    maxValue = 100f,
                                    unit = "%",
                                    label = "Layer Opacity",
                                    onValueChange = onUpdatePropertyValue,
                                    modifier = Modifier.weight(1f)
                                )
                            }
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                AM_BLEND_MODES.forEach { mode ->
                                    AmSegmentChip(
                                        label = mode,
                                        selected = selectedLayer.blendMode == mode,
                                        compact = true,
                                        onClick = { onUpdateBlendMode(selectedLayer.id, mode) }
                                    )
                                }
                            }

                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(11.dp))
                                    .background(PanelDark)
                                    .border(1.dp, if (selectedLayer.isClippingMask) AmTeal else OutlineSubtle, RoundedCornerShape(11.dp))
                                    .padding(horizontal = 12.dp, vertical = 6.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Icon(Icons.Default.Layers, contentDescription = null, tint = AmTealBright, modifier = Modifier.size(18.dp))
                                    Column {
                                        Text("Clipping Mask", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                                        Text("Mask upper layers inside alpha", style = MaterialTheme.typography.labelSmall, color = TextSecondary)
                                    }
                                }
                                Switch(
                                    checked = selectedLayer.isClippingMask,
                                    onCheckedChange = { onToggleClippingMask() },
                                    colors = SwitchDefaults.colors(checkedThumbColor = DeepBlack, checkedTrackColor = AmTeal)
                                )
                            }
                        }
                    }

                    AmInspectorTab.EDIT_LAYER -> {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            when (selectedLayer.type) {
                                LayerType.SHAPE -> {
                                    Text("Shape Primitive", style = MaterialTheme.typography.labelMedium, color = TextSecondary)
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .horizontalScroll(rememberScrollState()),
                                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        AM_SHAPE_KINDS.forEach { kind ->
                                            AmSegmentChip(
                                                label = kind,
                                                selected = selectedLayer.shapeKind == kind,
                                                compact = true,
                                                onClick = { onUpdateShapeKind(selectedLayer.id, kind) }
                                            )
                                        }
                                    }
                                }

                                LayerType.TEXT -> {
                                    OutlinedTextField(
                                        value = selectedLayer.textContent,
                                        onValueChange = { onUpdateTextLayer(selectedLayer.id, it, selectedLayer.fontFamilyName) },
                                        label = { Text("Kinetic Text Content") },
                                        singleLine = true,
                                        shape = RoundedCornerShape(10.dp),
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .horizontalScroll(rememberScrollState()),
                                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        listOf("Space Grotesk", "JetBrains Mono", "Serif", "Cursive").forEach { font ->
                                            AmSegmentChip(
                                                label = font,
                                                selected = selectedLayer.fontFamilyName == font,
                                                compact = true,
                                                onClick = { onUpdateTextLayer(selectedLayer.id, selectedLayer.textContent, font) }
                                            )
                                        }
                                    }
                                }

                                LayerType.MODEL_3D -> {
                                    Text("3D Mesh Primitive", style = MaterialTheme.typography.labelMedium, color = TextSecondary)
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .horizontalScroll(rememberScrollState()),
                                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        Primitive3DType.entries.filter { it != Primitive3DType.IMPORTED_MESH }.forEach { prim ->
                                            AmSegmentChip(
                                                label = prim.label,
                                                selected = selectedLayer.primitive3D == prim,
                                                compact = true,
                                                onClick = { onUpdate3DPrimitive(selectedLayer.id, prim) }
                                            )
                                        }
                                    }
                                }

                                else -> Unit
                            }

                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                AmTimingBarButton(
                                    icon = Icons.Default.AspectRatio,
                                    label = "Fit Comp",
                                    onClick = { onFitOrFillComposition(false) }
                                )
                                AmTimingBarButton(
                                    icon = Icons.Default.AspectRatio,
                                    label = "Fill Comp",
                                    onClick = { onFitOrFillComposition(true) }
                                )
                                AmSegmentChip(
                                    label = if (selectedLayer.flipX) "Flip H: ON" else "Flip H",
                                    selected = selectedLayer.flipX,
                                    compact = true,
                                    onClick = { onFlipLayer(true) }
                                )
                                AmSegmentChip(
                                    label = if (selectedLayer.flipY) "Flip V: ON" else "Flip V",
                                    selected = selectedLayer.flipY,
                                    compact = true,
                                    onClick = { onFlipLayer(false) }
                                )
                            }
                        }
                    }

                    AmInspectorTab.EFFECTS -> {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(PanelDark)
                                    .border(1.dp, if (selectedLayer.motionBlurEnabled) AmTeal else OutlineSubtle, RoundedCornerShape(10.dp))
                                    .padding(horizontal = 10.dp, vertical = 5.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Icon(Icons.Default.BlurLinear, contentDescription = null, tint = AmTealBright, modifier = Modifier.size(18.dp))
                                    Column {
                                        Text("Motion Blur", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                                        Text("GPU Shutter ${selectedLayer.shutterAngle.toInt()}°", style = MaterialTheme.typography.labelSmall, color = TextSecondary)
                                    }
                                }
                                Switch(
                                    checked = selectedLayer.motionBlurEnabled,
                                    onCheckedChange = { en -> onToggleMotionBlur(en, selectedLayer.shutterAngle) },
                                    colors = SwitchDefaults.colors(checkedThumbColor = DeepBlack, checkedTrackColor = AmTeal)
                                )
                            }
                            if (selectedLayer.motionBlurEnabled) {
                                AmTickWheelScrubber(
                                    value = selectedLayer.shutterAngle,
                                    minValue = 45f,
                                    maxValue = 360f,
                                    unit = "°",
                                    label = "Shutter Angle",
                                    onValueChange = { deg -> onToggleMotionBlur(true, deg) }
                                )
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                AmTimingBarButton(
                                    icon = Icons.Default.ContentCopy,
                                    label = "Copy FX",
                                    onClick = onCopyEffects,
                                    modifier = Modifier.weight(1f)
                                )
                                AmTimingBarButton(
                                    icon = Icons.Default.ContentPaste,
                                    label = "Paste FX",
                                    active = hasCopiedEffects,
                                    onClick = onPasteEffects,
                                    modifier = Modifier.weight(1f)
                                )
                            }

                            selectedLayer.effects.forEach { fx ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(PanelDark)
                                        .border(1.dp, OutlineSubtle, RoundedCornerShape(10.dp))
                                        .padding(horizontal = 10.dp, vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Icon(
                                        imageVector = if (fx.enabled) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                                        contentDescription = "Toggle effect",
                                        tint = if (fx.enabled) AmTealBright else TextMuted,
                                        modifier = Modifier
                                            .size(18.dp)
                                            .clickable { onUpdateEffectNode(fx.copy(enabled = !fx.enabled)) }
                                    )
                                    Text(
                                        text = fx.displayName,
                                        style = MaterialTheme.typography.labelMedium,
                                        color = TextPrimary,
                                        fontWeight = FontWeight.Bold,
                                        maxLines = 1,
                                        modifier = Modifier.width(96.dp),
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Slider(
                                        value = fx.intensity,
                                        onValueChange = { v -> onUpdateEffectNode(fx.copy(intensity = v)) },
                                        valueRange = 0f..1f,
                                        colors = SliderDefaults.colors(
                                            thumbColor = AmTealBright,
                                            activeTrackColor = AmTeal
                                        ),
                                        modifier = Modifier.weight(1f)
                                    )
                                    Icon(
                                        imageVector = Icons.Default.Close,
                                        contentDescription = "Remove effect",
                                        tint = NeonPink,
                                        modifier = Modifier
                                            .size(18.dp)
                                            .clickable { onRemoveEffectNode(fx.id) }
                                    )
                                }
                            }

                            AmPrimaryCtaButton(
                                text = "ADD EFFECT",
                                icon = Icons.Default.Add,
                                compact = true,
                                onClick = { onNavigateTool(StudioDestination.EFFECTS_BROWSER) },
                                modifier = Modifier.fillMaxWidth(),
                                testTag = "open_effects_browser_cta"
                            )
                        }
                    }
                }
            }

            // 4. BREATHABLE HORIZONTAL BOTTOM QUICK-SWITCH BAR
            if (activeTab != AmInspectorTab.LAYER_OVERVIEW) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(PanelDark)
                        .border(0.6.dp, OutlineSubtle)
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 8.dp, vertical = 5.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    listOf(
                        AmInspectorTab.LAYER_OVERVIEW to "Overview",
                        AmInspectorTab.SPEED_TIMING to "Speed",
                        AmInspectorTab.MOVE_TRANSFORM to "Move",
                        AmInspectorTab.BLENDING_OPACITY to "Blend & Mask",
                        AmInspectorTab.COLOR_FILL to "Color",
                        AmInspectorTab.BORDER_SHADOW to "Stroke",
                        AmInspectorTab.EDIT_LAYER to "Edit",
                        AmInspectorTab.EFFECTS to "Effects"
                    ).forEach { (tab, label) ->
                        val isSel = activeTab == tab || (activeTab == AmInspectorTab.EASING_INLINE && tab == AmInspectorTab.MOVE_TRANSFORM)
                        AmSegmentChip(
                            label = label,
                            selected = isSel,
                            compact = true,
                            onClick = { onSetInspectorTab(tab) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AmOverviewWideTile(
    icon: ImageVector,
    title: String,
    subtitle: String,
    accent: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    badge: String? = null,
    testTag: String? = null
) {
    Row(
        modifier = modifier
            .height(52.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(PanelDark)
            .border(1.dp, OutlineSubtle, RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .then(if (testTag != null) Modifier.testTag(testTag) else Modifier)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Box(
            modifier = Modifier
                .size(32.dp)
                .clip(RoundedCornerShape(9.dp))
                .background(accent.copy(alpha = 0.16f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(imageVector = icon, contentDescription = title, tint = accent, modifier = Modifier.size(18.dp))
        }
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.labelMedium,
                    color = TextPrimary,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (!badge.isNullOrBlank()) {
                    Box(
                        modifier = Modifier
                            .clip(CircleShape)
                            .background(accent)
                            .padding(horizontal = 5.dp, vertical = 1.dp)
                    ) {
                        Text(badge, style = MaterialTheme.typography.labelSmall, color = DeepBlack, fontWeight = FontWeight.Bold)
                    }
                }
            }
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

@Composable
private fun AmTimingIconPill(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    testTag: String? = null
) {
    Box(
        modifier = modifier
            .height(34.dp)
            .clip(RoundedCornerShape(9.dp))
            .background(PanelElevated)
            .border(1.dp, OutlineSubtle, RoundedCornerShape(9.dp))
            .clickable(onClick = onClick)
            .then(if (testTag != null) Modifier.testTag(testTag) else Modifier),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = TextPrimary,
            modifier = Modifier.size(17.dp)
        )
    }
}

@Composable
internal fun AmTimingBarButton(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    active: Boolean = false,
    testTag: String? = null
) {
    Row(
        modifier = modifier
            .height(34.dp)
            .clip(RoundedCornerShape(9.dp))
            .background(if (active) AmTealContainer else PanelElevated)
            .border(1.dp, if (active) AmTeal else OutlineSubtle, RoundedCornerShape(9.dp))
            .clickable(onClick = onClick)
            .then(if (testTag != null) Modifier.testTag(testTag) else Modifier)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = if (active) AmTealBright else TextPrimary,
            modifier = Modifier.size(15.dp)
        )
        Spacer(modifier = Modifier.width(4.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = if (active) AmTealBright else TextPrimary,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}
