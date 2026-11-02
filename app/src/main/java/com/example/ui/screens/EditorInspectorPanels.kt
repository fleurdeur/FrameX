package com.example.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Extension
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
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
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
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.data.model.EffectNode
import com.example.data.model.EffectParamSlot
import com.example.data.model.Layer
import com.example.data.model.LayerType
import com.example.data.model.Primitive3DType
import com.example.data.model.PropertyTarget
import com.example.engine.AnimationEvaluator
import com.example.engine.AudioSpectrumSnapshot
import com.example.engine.CustomFontRegistry
import com.example.engine.EffectsCatalog
import com.example.engine.PluginPackManager
import com.example.ui.components.AmCustomColorAndGradientPicker
import com.example.ui.components.AmKeyframeCluster
import com.example.ui.components.AmPrimaryCtaButton
import com.example.ui.components.AmSegmentChip
import com.example.ui.components.AmSquareIconButton
import com.example.ui.components.AmTickWheelScrubber
import com.example.ui.components.EyedropperColorSlot
import com.example.ui.theme.AmTeal
import com.example.ui.theme.AmTealBright
import com.example.ui.theme.AmTealContainer
import com.example.ui.theme.AudioCyan
import com.example.ui.theme.DeepBlack
import com.example.ui.theme.ElectricBlue
import com.example.ui.theme.KeyframeAmber
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.material.icons.automirrored.filled.ShowChart
import androidx.compose.material.icons.filled.Diamond
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.Cancel
import androidx.compose.material.icons.outlined.Diamond
import androidx.compose.material3.HorizontalDivider
import com.example.ui.theme.KeyflowBlue
import com.example.ui.theme.KeyflowBlueBright
import com.example.ui.theme.KeyflowDrawerBg
import com.example.ui.theme.KeyflowRailDivider
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
    selectedEffectId: String? = null,
    selectedEffectSlot: EffectParamSlot = EffectParamSlot.PRIMARY,
    selectedPluginParamKey: String? = null,
    playheadMs: Long,
    audioSpectrum: AudioSpectrumSnapshot,
    hasCopiedEffects: Boolean,
    hasCopiedCurve: Boolean = false,
    canMoveForward: Boolean = false,
    canMoveBackward: Boolean = false,
    onMoveLayerZOrder: (sendBackward: Boolean) -> Unit = {},
    onRenameLayer: (String, String) -> Unit = { _, _ -> },
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
    onUpdateBezierHandles: (Float, Float, Float, Float, Boolean) -> Unit,
    onSelectEffectForInspection: (String) -> Unit = {},
    onSelectEffectSlot: (EffectParamSlot) -> Unit = {},
    onSelectPluginParamKey: (String) -> Unit = {},
    onUpdateEffectSlotValue: (Float) -> Unit = {},
    onUpdateCustomPluginParamValue: (String, Float) -> Unit = { _, _ -> },
    onUpdateCustomPluginColor: (String, String) -> Unit = { _, _ -> },
    onToggleEffectKeyframe: () -> Unit = {},
    onJumpEffectKeyframe: (Boolean) -> Unit = {},
    onUpdateEffectBezierHandles: (Float, Float, Float, Float, Boolean) -> Unit = { _, _, _, _, _ -> },
    onUpdateEffectExpression: (String) -> Unit = {},
    onMoveEffectOrder: (String, Boolean) -> Unit = { _, _ -> },
    onDuplicateEffect: (String) -> Unit = {},
    onResetEffect: (String) -> Unit = {},
    onCopyCurve: (Boolean) -> Unit = {},
    onPasteCurve: (Boolean) -> Unit = {},
    onDragKeyframePoint: (String, Long, Float, Boolean) -> Unit = { _, _, _, _ -> },
    onOpenGraphEditor: (Boolean) -> Unit = {},
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
    onUpdateLayerGradientFill: (String, String, String, String, Float) -> Unit = { _, _, _, _, _ -> },
    onUpdateAudioEffects: (String, Float, Float, Float, Float, Float, Float, Float, Float?) -> Unit = { _, _, _, _, _, _, _, _, _ -> },
    onImportCustomFont: (Uri) -> Unit = {},
    onRequestEyedropper: (EyedropperColorSlot) -> Unit = {},
    onRequestPluginColorEyedropper: (String) -> Unit = {},
    onUpdateBorderShadow: (String, Boolean, Float, String, Boolean, Float, String) -> Unit,
    onUpdateBlendMode: (String, String) -> Unit,
    onUpdateShapeKind: (String, String) -> Unit,
    onUpdateTextLayer: (String, String, String) -> Unit,
    onUpdate3DPrimitive: (String, Primitive3DType) -> Unit,
    onUpdateEffectNode: (EffectNode) -> Unit,
    onRemoveEffectNode: (String) -> Unit,
    onClearAllEffects: (() -> Unit)? = null,
    onCopyEffects: () -> Unit,
    onPasteEffects: () -> Unit,
    onNavigateTool: (StudioDestination) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val customFonts by CustomFontRegistry.customFonts.collectAsStateWithLifecycle()
    val fontPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) onImportCustomFont(uri)
    }
    var parentMenuOpen by remember { mutableStateOf(false) }
    var renameDialogOpen by remember { mutableStateOf(false) }
    var renameDraft by remember(selectedLayer.id, selectedLayer.name) { mutableStateOf(selectedLayer.name) }
    val nullCandidates = remember(allLayers, selectedLayer.id) {
        allLayers.filter {
            (it.type == LayerType.NULL_OBJECT || it.type == LayerType.SHAPE || it.type == LayerType.CAMERA) &&
                it.id != selectedLayer.id
        }
    }
    val parentName = allLayers.firstOrNull { it.id == selectedLayer.parentId }?.name

    if (renameDialogOpen) {
        AlertDialog(
            onDismissRequest = { renameDialogOpen = false },
            containerColor = SurfaceDark,
            title = { Text("Rename", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold) },
            text = {
                OutlinedTextField(
                    value = renameDraft,
                    onValueChange = { renameDraft = it },
                    label = { Text("Name") },
                    singleLine = true,
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onRenameLayer(selectedLayer.id, renameDraft)
                        renameDialogOpen = false
                    }
                ) {
                    Text("Save", color = AmTealBright, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { renameDialogOpen = false }) {
                    Text("Cancel", color = TextSecondary)
                }
            }
        )
    }

    // Route Effects, Effect Detail (inline accordion), and Effect Easing directly to KeyflowEffectsSplitDrawer
    if (activeTab == AmInspectorTab.EFFECTS ||
        activeTab == AmInspectorTab.EFFECT_DETAIL ||
        activeTab == AmInspectorTab.EFFECT_EASING
    ) {
        Surface(
            color = KeyflowDrawerBg,
            modifier = modifier
                .fillMaxSize()
                .navigationBarsPadding()
        ) {
            KeyflowEffectsSplitDrawer(
                activeTab = activeTab,
                layer = selectedLayer,
                selectedEffectId = selectedEffectId,
                selectedSlot = selectedEffectSlot,
                selectedPluginParamKey = selectedPluginParamKey,
                playheadMs = playheadMs,
                audioSpectrum = audioSpectrum,
                hasCopiedEffects = hasCopiedEffects,
                hasCopiedCurve = hasCopiedCurve,
                onCloseDrawer = { onSetInspectorTab(AmInspectorTab.LAYER_OVERVIEW) },
                onSetInspectorTab = onSetInspectorTab,
                onSelectEffectForInspection = onSelectEffectForInspection,
                onSelectSlot = onSelectEffectSlot,
                onSelectPluginParamKey = onSelectPluginParamKey,
                onUpdateSlotValue = onUpdateEffectSlotValue,
                onUpdateCustomPluginParamValue = onUpdateCustomPluginParamValue,
                onUpdateCustomPluginColor = onUpdateCustomPluginColor,
                onToggleSlotKeyframe = onToggleEffectKeyframe,
                onUpdateEffectBezierHandles = onUpdateEffectBezierHandles,
                onUpdateSlotExpression = onUpdateEffectExpression,
                onCopyCurve = { onCopyCurve(true) },
                onPasteCurve = { onPasteCurve(true) },
                onDragEffectKeyframeNode = { kfId, t, v -> onDragKeyframePoint(kfId, t, v, true) },
                onScrubPlayhead = onScrubPlayhead,
                onOpenFullGraph = { onOpenGraphEditor(true) },
                onUpdateEffectNode = onUpdateEffectNode,
                onMoveEffectOrder = onMoveEffectOrder,
                onDuplicateEffect = onDuplicateEffect,
                onResetEffect = onResetEffect,
                onRemoveEffectNode = onRemoveEffectNode,
                onClearAllEffects = onClearAllEffects,
                onCopyEffects = onCopyEffects,
                onPasteEffects = onPasteEffects,
                onToggleMotionBlur = onToggleMotionBlur,
                onNavigateTool = onNavigateTool,
                onRequestEyedropper = onRequestEyedropper,
                onRequestPluginColorEyedropper = onRequestPluginColorEyedropper
            )
        }
        return
    }

    val activeLayerProp = selectedLayer.propertyFor(selectedProperty)
    val hasLayerKfAtPlayhead = activeLayerProp.keyframes.any { abs(it.timeMs - playheadMs) <= 35L }
    val supportsLayerKeyframes = activeTab == AmInspectorTab.MOVE_TRANSFORM ||
        activeTab == AmInspectorTab.EASING_INLINE ||
        activeTab == AmInspectorTab.BLENDING_OPACITY ||
        (activeTab == AmInspectorTab.SPEED_TIMING && selectedLayer.timeRemapEnabled)
    var subRailMenuOpen by remember { mutableStateOf(false) }

    Surface(
        color = KeyflowDrawerBg,
        modifier = modifier
            .fillMaxSize()
            .navigationBarsPadding()
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            HorizontalDivider(thickness = 0.8.dp, color = KeyflowRailDivider)
            Row(modifier = Modifier.fillMaxSize()) {
                // LEFT 50.dp KEYFLOW VERTICAL TOOL RAIL
                Column(
                    modifier = Modifier
                        .width(50.dp)
                        .fillMaxHeight()
                        .background(KeyflowDrawerBg),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                            .clickable { onSetInspectorTab(AmInspectorTab.LAYER_OVERVIEW) }
                            .testTag("collapse_layer_sheet_button"),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Cancel,
                            contentDescription = "Close Inspector",
                            tint = Color(0xFFD5DCE8),
                            modifier = Modifier.size(22.dp)
                        )
                    }
                    HorizontalDivider(thickness = 0.8.dp, color = KeyflowRailDivider)

                    Spacer(modifier = Modifier.height(6.dp))

                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(if (hasLayerKfAtPlayhead && supportsLayerKeyframes) KeyflowBlue.copy(alpha = 0.18f) else Color.Transparent)
                            .clickable(enabled = supportsLayerKeyframes, onClick = onToggleKeyframe),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = if (hasLayerKfAtPlayhead) Icons.Filled.Diamond else Icons.Outlined.Diamond,
                            contentDescription = "Toggle Keyframe",
                            tint = when {
                                !supportsLayerKeyframes -> Color(0xFF4E5563)
                                hasLayerKfAtPlayhead -> KeyflowBlueBright
                                activeLayerProp.keyframes.isNotEmpty() -> KeyframeAmber
                                else -> Color(0xFFD5DCE8)
                            },
                            modifier = Modifier.size(21.dp)
                        )
                    }

                    Spacer(modifier = Modifier.height(4.dp))

                    val isInlineCurve = activeTab == AmInspectorTab.EASING_INLINE
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(if (isInlineCurve) KeyflowBlue.copy(alpha = 0.18f) else Color.Transparent)
                            .clickable(enabled = supportsLayerKeyframes) {
                                if (isInlineCurve) {
                                    onSetInspectorTab(
                                        if (selectedProperty == PropertyTarget.TIME_REMAP) AmInspectorTab.SPEED_TIMING
                                        else AmInspectorTab.MOVE_TRANSFORM
                                    )
                                } else {
                                    onSetInspectorTab(AmInspectorTab.EASING_INLINE)
                                }
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ShowChart,
                            contentDescription = "Easing Curve",
                            tint = when {
                                !supportsLayerKeyframes -> Color(0xFF4E5563)
                                isInlineCurve -> KeyflowBlueBright
                                else -> Color(0xFFD5DCE8)
                            },
                            modifier = Modifier.size(21.dp)
                        )
                    }

                    Spacer(modifier = Modifier.weight(1f))

                    Box {
                        Box(
                            modifier = Modifier
                                .size(48.dp)
                                .clickable { subRailMenuOpen = true },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.MoreVert,
                                contentDescription = "More Layer Tools",
                                tint = Color(0xFFD5DCE8),
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        DropdownMenu(
                            expanded = subRailMenuOpen,
                            onDismissRequest = { subRailMenuOpen = false }
                        ) {
                            listOf(
                                AmInspectorTab.EDIT_LAYER to "Edit Layer",
                                AmInspectorTab.MOVE_TRANSFORM to "Move & Transform",
                                AmInspectorTab.EFFECTS to "Effects",
                                AmInspectorTab.BLENDING_OPACITY to "Blending & Mask",
                                AmInspectorTab.SPEED_TIMING to "Speed & Audio",
                                AmInspectorTab.COLOR_FILL to "Color & Fill",
                                AmInspectorTab.BORDER_SHADOW to "Border & Shadow"
                            ).forEach { (tab, title) ->
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            text = title,
                                            color = if (activeTab == tab) KeyflowBlueBright else TextPrimary
                                        )
                                    },
                                    onClick = {
                                        subRailMenuOpen = false
                                        onSetInspectorTab(tab)
                                    }
                                )
                            }
                            HorizontalDivider(color = OutlineSubtle)
                            DropdownMenuItem(
                                text = { Text("Rename Layer") },
                                onClick = {
                                    subRailMenuOpen = false
                                    renameDraft = selectedLayer.name
                                    renameDialogOpen = true
                                }
                            )
                            DropdownMenuItem(
                                text = { Text(if (selectedLayer.locked) "Unlock Layer" else "Lock Layer") },
                                onClick = {
                                    subRailMenuOpen = false
                                    onToggleLock(selectedLayer.id)
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Parent Null Rig (${parentName ?: "None"})") },
                                onClick = {
                                    subRailMenuOpen = false
                                    parentMenuOpen = true
                                }
                            )
                        }
                        DropdownMenu(
                            expanded = parentMenuOpen,
                            onDismissRequest = { parentMenuOpen = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text("None") },
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

                Box(
                    modifier = Modifier
                        .width(0.8.dp)
                        .fillMaxHeight()
                        .background(KeyflowRailDivider)
                )

                // RIGHT SCROLLABLE SUB-PANEL CONTENT
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .background(KeyflowDrawerBg)
                ) {
                    if (false) {
                        Row {
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

                    Row(
                        modifier = Modifier
                            .weight(1f, fill = false)
                            .clip(RoundedCornerShape(6.dp))
                            .clickable {
                                renameDraft = selectedLayer.name
                                renameDialogOpen = true
                            }
                            .padding(horizontal = 4.dp, vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text = selectedLayer.name,
                            style = MaterialTheme.typography.titleSmall,
                            color = TextPrimary,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                        Icon(
                            imageVector = Icons.Default.Edit,
                            contentDescription = "Rename layer",
                            tint = TextMuted,
                            modifier = Modifier.size(12.dp)
                        )
                    }

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
                                text = { Text("None") },
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
                        selectedLayer.allKeyframeTimes()
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
                    } // end legacy header guard

            // 3. EXPRESSIVE SUB-PANEL BODY
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp, vertical = 10.dp)
            ) {
                when (activeTab) {
                    AmInspectorTab.LAYER_OVERVIEW, AmInspectorTab.TIMELINE -> {
                        val editTileTitle = when (selectedLayer.type) {
                            LayerType.TEXT -> "Edit Text"
                            LayerType.SHAPE -> "Edit Shape"
                            LayerType.MODEL_3D -> "3D Mesh"
                            LayerType.VIDEO -> "Video"
                            LayerType.IMAGE -> "Image"
                            LayerType.AUDIO -> "Audio"
                            LayerType.CAMERA -> "Camera"
                            LayerType.NULL_OBJECT -> "Null"
                        }
                        val editTileSubtitle = when (selectedLayer.type) {
                            LayerType.TEXT -> selectedLayer.fontFamilyName
                            LayerType.SHAPE -> selectedLayer.shapeKind
                            LayerType.MODEL_3D -> selectedLayer.primitive3D.label
                            LayerType.VIDEO -> "${selectedLayer.mediaWidth}×${selectedLayer.mediaHeight}"
                            LayerType.IMAGE -> "${selectedLayer.mediaWidth}×${selectedLayer.mediaHeight}"
                            LayerType.AUDIO -> if (selectedLayer.audioMuted) "Muted" else "${(selectedLayer.audioVolume * 100).toInt()}%"
                            else -> parentName ?: "None"
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
                                        title = "Speed",
                                        subtitle = if (selectedLayer.timeRemapEnabled) "Remap"
                                        else "${selectedLayer.speedMultiplier}x",
                                        accent = KeyframeAmber,
                                        onClick = { onSetInspectorTab(AmInspectorTab.SPEED_TIMING) },
                                        modifier = Modifier.weight(1f),
                                        testTag = "tile_speed_timing"
                                    )
                                    if (selectedLayer.type == LayerType.VIDEO) {
                                        AmOverviewWideTile(
                                            icon = Icons.Default.GraphicEq,
                                            title = "Extract Audio",
                                            subtitle = if (selectedLayer.audioMuted) "Muted" else "Audio",
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
                                        subtitle = when (selectedLayer.fillMode) {
                                            "LINEAR_GRADIENT" -> "Linear"
                                            "RADIAL_GRADIENT" -> "Radial"
                                            else -> selectedLayer.colorHex
                                        },
                                        accent = AmTeal,
                                        onClick = { onSetInspectorTab(AmInspectorTab.COLOR_FILL) },
                                        modifier = Modifier.weight(1f),
                                        testTag = "tile_color_fill"
                                    )
                                    AmOverviewWideTile(
                                        icon = Icons.Default.BorderStyle,
                                        title = "Border & Shadow",
                                        subtitle = if (selectedLayer.shadowEnabled) "Shadow" else "${selectedLayer.strokeWidth.toInt()}px",
                                        accent = ElectricBlue,
                                        onClick = { onSetInspectorTab(AmInspectorTab.BORDER_SHADOW) },
                                        modifier = Modifier.weight(1f),
                                        testTag = "tile_border_shadow"
                                    )
                                }
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    AmOverviewWideTile(
                                        icon = Icons.Default.Opacity,
                                        title = "Blending & Opacity",
                                        subtitle = if (selectedLayer.isClippingMask) "Mask • $opacityEval%" else "$opacityEval% • ${selectedLayer.blendMode}",
                                        accent = AudioCyan,
                                        onClick = { onSetInspectorTab(AmInspectorTab.BLENDING_OPACITY) },
                                        modifier = Modifier.weight(1f),
                                        testTag = "tile_blending_opacity"
                                    )
                                    AmOverviewWideTile(
                                        icon = Icons.Default.OpenWith,
                                        title = "Move & Transform",
                                        subtitle = null,
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
                                        subtitle = if (selectedLayer.motionBlurEnabled) "Motion Blur" else null,
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
                            onUpdateAudioVolume = onUpdateAudioVolume,
                            onUpdateAudioEffects = onUpdateAudioEffects
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
                            hasCopiedCurve = hasCopiedCurve,
                            onUpdateBezierHandles = onUpdateBezierHandles,
                            onCopyCurve = { onCopyCurve(false) },
                            onPasteCurve = { onPasteCurve(false) },
                            onDragKeyframeNode = { kfId, t, v -> onDragKeyframePoint(kfId, t, v, false) },
                            onScrubPlayhead = onScrubPlayhead,
                            onReturnToTransform = {
                                if (selectedProperty == PropertyTarget.TIME_REMAP) {
                                    onSetInspectorTab(AmInspectorTab.SPEED_TIMING)
                                } else {
                                    onSetInspectorTab(AmInspectorTab.MOVE_TRANSFORM)
                                }
                            },
                            onOpenFullGraph = { onOpenGraphEditor(false) }
                        )
                    }

                    AmInspectorTab.COLOR_FILL -> {
                        AmCustomColorAndGradientPicker(
                            primaryHex = selectedLayer.colorHex,
                            onPrimaryHexChange = { hex -> onUpdateLayerColor(selectedLayer.id, hex) },
                            onRequestEyedropper = onRequestEyedropper,
                            primaryEyedropperSlot = EyedropperColorSlot.LAYER_PRIMARY,
                            supportsGradient = true,
                            fillMode = selectedLayer.fillMode,
                            gradientEndHex = selectedLayer.gradientEndColorHex,
                            gradientAngleDeg = selectedLayer.gradientAngleDeg,
                            onGradientChange = { mode, startHex, endHex, angle ->
                                onUpdateLayerGradientFill(selectedLayer.id, mode, startHex, endHex, angle)
                            },
                            testTagPrefix = "layer_color_fill_picker"
                        )
                    }

                    AmInspectorTab.BORDER_SHADOW -> {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("Stroke", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
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
                                    label = "Width",
                                    onValueChange = { w ->
                                        onUpdateBorderShadow(
                                            selectedLayer.id, true, w,
                                            selectedLayer.strokeColorHex, selectedLayer.shadowEnabled,
                                            selectedLayer.shadowBlur, selectedLayer.shadowColorHex
                                        )
                                    }
                                )
                                AmCustomColorAndGradientPicker(
                                    primaryHex = selectedLayer.strokeColorHex,
                                    onPrimaryHexChange = { hex ->
                                        onUpdateBorderShadow(
                                            selectedLayer.id, true, selectedLayer.strokeWidth,
                                            hex, selectedLayer.shadowEnabled,
                                            selectedLayer.shadowBlur, selectedLayer.shadowColorHex
                                        )
                                    },
                                    onRequestEyedropper = onRequestEyedropper,
                                    primaryEyedropperSlot = EyedropperColorSlot.LAYER_STROKE,
                                    supportsGradient = false,
                                    compact = true,
                                    testTagPrefix = "stroke_color_picker"
                                )
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("Shadow", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
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
                                    label = "Blur",
                                    onValueChange = { blur ->
                                        onUpdateBorderShadow(
                                            selectedLayer.id, selectedLayer.strokeEnabled, selectedLayer.strokeWidth,
                                            selectedLayer.strokeColorHex, true,
                                            blur, selectedLayer.shadowColorHex
                                        )
                                    }
                                )
                                AmCustomColorAndGradientPicker(
                                    primaryHex = selectedLayer.shadowColorHex,
                                    onPrimaryHexChange = { hex ->
                                        onUpdateBorderShadow(
                                            selectedLayer.id, selectedLayer.strokeEnabled, selectedLayer.strokeWidth,
                                            selectedLayer.strokeColorHex, true,
                                            selectedLayer.shadowBlur, hex
                                        )
                                    },
                                    onRequestEyedropper = onRequestEyedropper,
                                    primaryEyedropperSlot = EyedropperColorSlot.LAYER_SHADOW,
                                    supportsGradient = false,
                                    compact = true,
                                    testTagPrefix = "shadow_color_picker"
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
                                    label = "Opacity",
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
                                    Text("Mask", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
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
                                    Text("Shape", style = MaterialTheme.typography.labelMedium, color = TextSecondary)
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

                                    Text("Fill", style = MaterialTheme.typography.labelMedium, color = TextSecondary)
                                    AmCustomColorAndGradientPicker(
                                        primaryHex = selectedLayer.colorHex,
                                        onPrimaryHexChange = { hex -> onUpdateLayerColor(selectedLayer.id, hex) },
                                        onRequestEyedropper = onRequestEyedropper,
                                        primaryEyedropperSlot = EyedropperColorSlot.LAYER_PRIMARY,
                                        supportsGradient = true,
                                        fillMode = selectedLayer.fillMode,
                                        gradientEndHex = selectedLayer.gradientEndColorHex,
                                        gradientAngleDeg = selectedLayer.gradientAngleDeg,
                                        onGradientChange = { mode, startHex, endHex, angle ->
                                            onUpdateLayerGradientFill(selectedLayer.id, mode, startHex, endHex, angle)
                                        },
                                        compact = true,
                                        testTagPrefix = "shape_inline_color_picker"
                                    )
                                }

                                LayerType.TEXT -> {
                                    OutlinedTextField(
                                        value = selectedLayer.textContent,
                                        onValueChange = { onUpdateTextLayer(selectedLayer.id, it, selectedLayer.fontFamilyName) },
                                        label = { Text("Text") },
                                        singleLine = true,
                                        shape = RoundedCornerShape(10.dp),
                                        modifier = Modifier.fillMaxWidth()
                                    )

                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = "Font",
                                            style = MaterialTheme.typography.labelMedium,
                                            color = TextSecondary
                                        )
                                        AmTimingBarButton(
                                            icon = Icons.Default.Add,
                                            label = "Import",
                                            active = customFonts.isNotEmpty(),
                                            onClick = {
                                                fontPickerLauncher.launch(
                                                    arrayOf(
                                                        "font/*",
                                                        "application/x-font-ttf",
                                                        "application/x-font-opentype",
                                                        "*/*"
                                                    )
                                                )
                                            },
                                            testTag = "import_custom_font_button"
                                        )
                                    }

                                    val availableFonts = remember(context, customFonts) {
                                        CustomFontRegistry.allAvailableFonts(context)
                                    }
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .horizontalScroll(rememberScrollState()),
                                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        availableFonts.forEach { opt ->
                                            val label = if (opt.isCustomImported) "★ ${opt.displayName}" else opt.displayName
                                            AmSegmentChip(
                                                label = label,
                                                selected = selectedLayer.fontFamilyName.equals(opt.displayName, ignoreCase = true),
                                                compact = true,
                                                onClick = {
                                                    onUpdateTextLayer(selectedLayer.id, selectedLayer.textContent, opt.displayName)
                                                }
                                            )
                                        }
                                    }

                                    Text("Color", style = MaterialTheme.typography.labelMedium, color = TextSecondary)
                                    AmCustomColorAndGradientPicker(
                                        primaryHex = selectedLayer.colorHex,
                                        onPrimaryHexChange = { hex -> onUpdateLayerColor(selectedLayer.id, hex) },
                                        onRequestEyedropper = onRequestEyedropper,
                                        primaryEyedropperSlot = EyedropperColorSlot.LAYER_PRIMARY,
                                        supportsGradient = true,
                                        fillMode = selectedLayer.fillMode,
                                        gradientEndHex = selectedLayer.gradientEndColorHex,
                                        gradientAngleDeg = selectedLayer.gradientAngleDeg,
                                        onGradientChange = { mode, startHex, endHex, angle ->
                                            onUpdateLayerGradientFill(selectedLayer.id, mode, startHex, endHex, angle)
                                        },
                                        compact = true,
                                        testTagPrefix = "text_inline_color_picker"
                                    )
                                }

                                LayerType.MODEL_3D -> {
                                    Text("Mesh", style = MaterialTheme.typography.labelMedium, color = TextSecondary)
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
                                    label = "Fit",
                                    onClick = { onFitOrFillComposition(false) }
                                )
                                AmTimingBarButton(
                                    icon = Icons.Default.AspectRatio,
                                    label = "Fill",
                                    onClick = { onFitOrFillComposition(true) }
                                )
                                AmSegmentChip(
                                    label = "Flip H",
                                    selected = selectedLayer.flipX,
                                    compact = true,
                                    onClick = { onFlipLayer(true) }
                                )
                                AmSegmentChip(
                                    label = "Flip V",
                                    selected = selectedLayer.flipY,
                                    compact = true,
                                    onClick = { onFlipLayer(false) }
                                )
                            }
                        }
                    }

                    AmInspectorTab.EFFECTS,
                    AmInspectorTab.EFFECT_DETAIL,
                    AmInspectorTab.EFFECT_EASING -> Unit
                }
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
    subtitle: String? = null,
    accent: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    badge: String? = null,
    testTag: String? = null
) {
    Row(
        modifier = modifier
            .height(50.dp)
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
                .size(30.dp)
                .clip(RoundedCornerShape(9.dp))
                .background(accent.copy(alpha = 0.16f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(imageVector = icon, contentDescription = title, tint = accent, modifier = Modifier.size(17.dp))
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
            if (!subtitle.isNullOrBlank()) {
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
