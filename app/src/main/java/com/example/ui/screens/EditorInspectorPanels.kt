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
import androidx.compose.material.icons.filled.Info
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
import androidx.compose.material.icons.filled.Refresh
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
import com.example.data.model.LayerMask
import com.example.data.model.LayerType
import com.example.data.model.MaskShapeType
import com.example.data.model.Primitive3DType
import com.example.data.model.PropertyTarget
import com.example.engine.AnimationEvaluator
import com.example.engine.AudioSpectrumSnapshot
import com.example.engine.CustomFontRegistry
import com.example.engine.EffectsCatalog
import com.example.engine.PluginPackManager
import com.example.rotoengine.RotoModelState
import com.example.rotoengine.RotoTargetMode
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Brush
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
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
    onShowMediaInfo: () -> Unit = {},
    onSetInspectorTab: (AmInspectorTab) -> Unit,
    onCollapseToTimeline: () -> Unit,
    onScrubPlayhead: (Long) -> Unit,
    onSelectProperty: (PropertyTarget) -> Unit,
    onUpdatePropertyValue: (Float) -> Unit,
    onUpdateExpression: (String) -> Unit,
    onDragLayerOnCanvas: (Float, Float) -> Unit,
    onResetTransform: () -> Unit,
    onResetPropertyTarget: (PropertyTarget) -> Unit = {},
    onResetMoveSubMode: (Int) -> Unit = {},
    onResetStroke: () -> Unit = {},
    onResetShadow: () -> Unit = {},
    onResetSingleEffectParam: (String, EffectParamSlot?, String?) -> Unit = { _, _, _ -> },
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
    onUpdateBorderShadow: (String, Boolean, Float, String, String, Boolean, Float, String, Float, Float) -> Unit,
    onUpdateBlendMode: (String, String) -> Unit,
    onUpdateShapeKind: (String, String) -> Unit,
    onUpdateTextLayer: (String, String, String) -> Unit,
    onUpdate3DPrimitive: (String, Primitive3DType) -> Unit,
    onUpdateEffectNode: (EffectNode) -> Unit,
    onRemoveEffectNode: (String) -> Unit,
    onClearAllEffects: (() -> Unit)? = null,
    onCopyEffects: () -> Unit,
    onPasteEffects: () -> Unit,
    onPreRenderLayerOrGroup: () -> Unit = {},
    activeMaskId: String? = null,
    penMaskEditActive: Boolean = false,
    rotoBrushActive: Boolean = false,
    rotoTargetMode: RotoTargetMode = RotoTargetMode.REMOVE_BACKGROUND,
    rotoBrushRadiusDp: Float = 18f,
    rotoStrokeCount: Int = 0,
    isRotoPropagating: Boolean = false,
    rotoPropagateProgress: Float = 0f,
    showRotoModelDownloadDialog: Boolean = false,
    rotoModelState: RotoModelState = RotoModelState(),
    onAddMask: (MaskShapeType) -> Unit = {},
    onSelectMask: (String?) -> Unit = {},
    onSetPenMaskEditMode: (Boolean) -> Unit = {},
    onUpdateMask: (String, (LayerMask) -> LayerMask) -> Unit = { _, _ -> },
    onRemoveMask: (String) -> Unit = {},
    onActivateRotoBrush: () -> Unit = {},
    onDismissRotoDownloadDialog: () -> Unit = {},
    onConfirmDownloadRotoModels: () -> Unit = {},
    onSetRotoTargetMode: (RotoTargetMode) -> Unit = {},
    onSetRotoBrushRadiusDp: (Float) -> Unit = {},
    onRunAutoRoto: () -> Unit = {},
    onUndoRotoStroke: () -> Unit = {},
    onRedoRotoStroke: () -> Unit = {},
    onClearRotoStrokes: () -> Unit = {},
    onPropagateRoto: () -> Unit = {},
    onCancelRotoPropagation: () -> Unit = {},
    onRelinkMissingMedia: (String, Uri) -> Unit = { _, _ -> },
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
    val relinkMediaLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) onRelinkMissingMedia(selectedLayer.id, uri)
    }
    var parentMenuOpen by remember { mutableStateOf(false) }
    var renameDialogOpen by remember { mutableStateOf(false) }
    var renameDraft by remember(selectedLayer.id, selectedLayer.name) { mutableStateOf(selectedLayer.name) }
    val nullCandidates = remember(allLayers, selectedLayer.id) {
        allLayers.filter {
            (it.type == LayerType.NULL_OBJECT ||
                it.type == LayerType.GROUP ||
                it.type == LayerType.SHAPE ||
                it.type == LayerType.CAMERA) &&
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
                    Text("Save", color = KeyflowBlueBright, fontWeight = FontWeight.Bold)
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
                onResetSingleEffectParam = onResetSingleEffectParam,
                onShowMediaInfo = onShowMediaInfo,
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
                                AmInspectorTab.MASK_ROTO to "Mask & Roto",
                                AmInspectorTab.BLENDING_OPACITY to "Blending & Opacity",
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
                                text = { Text("Media Info", color = KeyflowBlueBright, fontWeight = FontWeight.SemiBold) },
                                leadingIcon = { Icon(Icons.Default.Info, contentDescription = null, tint = KeyflowBlueBright) },
                                onClick = {
                                    subRailMenuOpen = false
                                    onShowMediaInfo()
                                },
                                modifier = Modifier.testTag("inspector_media_info_menu_item")
                            )
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
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = 12.dp, vertical = 10.dp)
                    ) {
                when (activeTab) {
                    AmInspectorTab.LAYER_OVERVIEW, AmInspectorTab.TIMELINE -> {
                        val childCount = remember(allLayers, selectedLayer.id) {
                            allLayers.count { it.parentId == selectedLayer.id }
                        }
                        val editTileTitle = when (selectedLayer.type) {
                            LayerType.TEXT -> "Edit Text"
                            LayerType.SHAPE -> "Edit Shape"
                            LayerType.MODEL_3D -> "3D Mesh"
                            LayerType.VIDEO -> "Video"
                            LayerType.IMAGE -> "Image"
                            LayerType.AUDIO -> "Audio"
                            LayerType.CAMERA -> "Camera"
                            LayerType.NULL_OBJECT -> "Null"
                            LayerType.GROUP -> "Group"
                            LayerType.DRAWING -> "Drawing"
                        }
                        val editTileSubtitle = when (selectedLayer.type) {
                            LayerType.TEXT -> selectedLayer.fontFamilyName
                            LayerType.SHAPE -> selectedLayer.shapeKind
                            LayerType.MODEL_3D -> selectedLayer.primitive3D.label
                            LayerType.VIDEO -> "${selectedLayer.mediaWidth}×${selectedLayer.mediaHeight}"
                            LayerType.IMAGE -> "${selectedLayer.mediaWidth}×${selectedLayer.mediaHeight}"
                            LayerType.AUDIO -> if (selectedLayer.audioMuted) "Muted" else "${(selectedLayer.audioVolume * 100).toInt()}%"
                            LayerType.GROUP -> "$childCount child layer(s)"
                            LayerType.DRAWING -> "${selectedLayer.drawingPoints.size} pts"
                            else -> parentName ?: "None"
                        }
                        val opacityEval = AnimationEvaluator.evaluateProperty(
                            selectedLayer.opacity,
                            playheadMs,
                            audioSpectrum
                        ).toInt()

                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (selectedLayer.isMissingMedia) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(MaterialTheme.colorScheme.errorContainer)
                                        .padding(horizontal = 12.dp, vertical = 8.dp)
                                        .testTag("missing_media_banner"),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Row(
                                        modifier = Modifier.weight(1f),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.WarningAmber,
                                            contentDescription = "Missing Media",
                                            tint = MaterialTheme.colorScheme.onErrorContainer,
                                            modifier = Modifier.size(18.dp)
                                        )
                                        Text(
                                            text = "Missing media: ${selectedLayer.mediaUri}",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onErrorContainer,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                    OutlinedButton(
                                        onClick = { relinkMediaLauncher.launch(arrayOf("*/*")) },
                                        modifier = Modifier.testTag("relink_missing_media_button")
                                    ) {
                                        Icon(Icons.Default.Link, contentDescription = null, modifier = Modifier.size(15.dp))
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text("Relink")
                                    }
                                }
                            }
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
                                if (selectedLayer.type != LayerType.CAMERA) {
                                    AmOverviewWideTile(
                                        icon = Icons.Default.ContentCut,
                                        title = "Mask & Roto",
                                        subtitle = if (selectedLayer.masks.isNotEmpty()) "${selectedLayer.masks.size} mask(s)" else "Rectangle • Pen • AI Roto",
                                        accent = KeyflowBlueBright,
                                        badge = selectedLayer.masks.size.takeIf { it > 0 }?.toString(),
                                        onClick = { onSetInspectorTab(AmInspectorTab.MASK_ROTO) },
                                        modifier = Modifier.fillMaxWidth(),
                                        testTag = "tile_mask_roto"
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
                            onResetPropertyTarget = onResetPropertyTarget,
                            onResetMoveSubMode = onResetMoveSubMode,
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
                        AmBorderAndShadowSubPanel(
                            selectedLayer = selectedLayer,
                            onUpdateBorderShadow = onUpdateBorderShadow,
                            onRequestEyedropper = onRequestEyedropper,
                            onResetStroke = onResetStroke,
                            onResetShadow = onResetShadow
                        )
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
                                    onReset = { onResetPropertyTarget(PropertyTarget.OPACITY) },
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
                        }
                    }

                    AmInspectorTab.EDIT_LAYER -> {
                        val groupChildren = remember(allLayers, selectedLayer.id) {
                            allLayers.filter { it.parentId == selectedLayer.id }
                        }
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            when (selectedLayer.type) {
                                LayerType.SHAPE -> {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text("Shape Primitive", style = MaterialTheme.typography.labelMedium, color = TextSecondary)
                                        AmSegmentChip(
                                            label = "Color & Fill →",
                                            selected = false,
                                            compact = true,
                                            onClick = { onSetInspectorTab(AmInspectorTab.COLOR_FILL) }
                                        )
                                    }
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
                                            text = "Font Family",
                                            style = MaterialTheme.typography.labelMedium,
                                            color = TextSecondary
                                        )
                                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                            AmTimingBarButton(
                                                icon = Icons.Default.Add,
                                                label = "Import .ttf",
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
                                            AmTimingBarButton(
                                                icon = Icons.Default.Palette,
                                                label = "Text Color",
                                                onClick = { onSetInspectorTab(AmInspectorTab.COLOR_FILL) }
                                            )
                                        }
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

                                LayerType.GROUP -> {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = "Group Container (${groupChildren.size} member layer(s))",
                                                style = MaterialTheme.typography.labelMedium,
                                                color = TextPrimary,
                                                fontWeight = FontWeight.Bold
                                            )
                                            Text(
                                                text = if (groupChildren.isEmpty()) "Parent layers to this Group to transform & pre-render together"
                                                else groupChildren.joinToString(", ") { it.name },
                                                style = MaterialTheme.typography.labelSmall,
                                                color = TextSecondary,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }
                                    }
                                }

                                else -> Unit
                            }

                            // Balanced 2x2 Canvas Fit / Fill / Mirror Grid filling horizontal space cleanly
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                AmTimingBarButton(
                                    icon = Icons.Default.AspectRatio,
                                    label = "Fit Canvas",
                                    onClick = { onFitOrFillComposition(false) },
                                    modifier = Modifier.weight(1f)
                                )
                                AmTimingBarButton(
                                    icon = Icons.Default.AspectRatio,
                                    label = "Fill Canvas",
                                    onClick = { onFitOrFillComposition(true) },
                                    modifier = Modifier.weight(1f)
                                )
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                AmSegmentChip(
                                    label = if (selectedLayer.flipX) "Mirror H ✓" else "Mirror H",
                                    selected = selectedLayer.flipX,
                                    compact = true,
                                    onClick = { onFlipLayer(true) },
                                    modifier = Modifier.weight(1f)
                                )
                                AmSegmentChip(
                                    label = if (selectedLayer.flipY) "Mirror V ✓" else "Mirror V",
                                    selected = selectedLayer.flipY,
                                    compact = true,
                                    onClick = { onFlipLayer(false) },
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }
                    }

                    AmInspectorTab.MASK_ROTO -> {
                        AmMaskAndRotoSubPanel(
                            selectedLayer = selectedLayer,
                            playheadMs = playheadMs,
                            audioSpectrum = audioSpectrum,
                            activeMaskId = activeMaskId,
                            penMaskEditActive = penMaskEditActive,
                            rotoBrushActive = rotoBrushActive,
                            rotoTargetMode = rotoTargetMode,
                            rotoBrushRadiusDp = rotoBrushRadiusDp,
                            rotoStrokeCount = rotoStrokeCount,
                            isRotoPropagating = isRotoPropagating,
                            rotoPropagateProgress = rotoPropagateProgress,
                            showRotoModelDownloadDialog = showRotoModelDownloadDialog,
                            rotoModelState = rotoModelState,
                            onAddMask = onAddMask,
                            onSelectMask = onSelectMask,
                            onSetPenMaskEditMode = onSetPenMaskEditMode,
                            onUpdateMask = onUpdateMask,
                            onRemoveMask = onRemoveMask,
                            onToggleClippingMask = onToggleClippingMask,
                            onActivateRotoBrush = onActivateRotoBrush,
                            onDismissRotoDownloadDialog = onDismissRotoDownloadDialog,
                            onConfirmDownloadRotoModels = onConfirmDownloadRotoModels,
                            onSetRotoTargetMode = onSetRotoTargetMode,
                            onSetRotoBrushRadiusDp = onSetRotoBrushRadiusDp,
                            onRunAutoRoto = onRunAutoRoto,
                            onUndoRotoStroke = onUndoRotoStroke,
                            onRedoRotoStroke = onRedoRotoStroke,
                            onClearRotoStrokes = onClearRotoStrokes,
                            onPropagateRoto = onPropagateRoto,
                            onCancelRotoPropagation = onCancelRotoPropagation
                        )
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
            .border(1.dp, if (active) KeyflowBlue else OutlineSubtle, RoundedCornerShape(9.dp))
            .clickable(onClick = onClick)
            .then(if (testTag != null) Modifier.testTag(testTag) else Modifier)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = if (active) KeyflowBlueBright else TextPrimary,
            modifier = Modifier.size(15.dp)
        )
        Spacer(modifier = Modifier.width(4.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = if (active) KeyflowBlueBright else TextPrimary,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun AmBorderAndShadowSubPanel(
    selectedLayer: Layer,
    onUpdateBorderShadow: (String, Boolean, Float, String, String, Boolean, Float, String, Float, Float) -> Unit,
    onRequestEyedropper: (EyedropperColorSlot) -> Unit,
    onResetStroke: () -> Unit,
    onResetShadow: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("border_shadow_panel"),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // 1. STROKE SECTION (Works on Shape, Text, Image, and Video layers)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(PanelDark)
                .border(
                    1.dp,
                    if (selectedLayer.strokeEnabled) KeyflowBlue.copy(alpha = 0.65f) else OutlineSubtle,
                    RoundedCornerShape(12.dp)
                )
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.BorderStyle,
                        contentDescription = null,
                        tint = if (selectedLayer.strokeEnabled) KeyflowBlueBright else TextSecondary,
                        modifier = Modifier.size(18.dp)
                    )
                    Text(
                        text = "Stroke",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary
                    )
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (selectedLayer.strokeEnabled) {
                        Row(
                            modifier = Modifier
                                .clip(RoundedCornerShape(7.dp))
                                .background(KeyflowBlue.copy(alpha = 0.14f))
                                .clickable(onClick = onResetStroke)
                                .testTag("reset_stroke_button")
                                .padding(horizontal = 8.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Icon(Icons.Default.Refresh, contentDescription = "Reset Stroke", tint = KeyflowBlueBright, modifier = Modifier.size(13.dp))
                            Text("Reset", style = MaterialTheme.typography.labelSmall, color = KeyflowBlueBright, fontWeight = FontWeight.SemiBold)
                        }
                    }
                    Switch(
                        checked = selectedLayer.strokeEnabled,
                        onCheckedChange = { enabled ->
                            val nextWidth = if (enabled && selectedLayer.strokeWidth <= 0.1f) 4f else selectedLayer.strokeWidth
                            onUpdateBorderShadow(
                                selectedLayer.id,
                                enabled,
                                nextWidth,
                                selectedLayer.strokeColorHex,
                                selectedLayer.strokePosition,
                                selectedLayer.shadowEnabled,
                                selectedLayer.shadowBlur,
                                selectedLayer.shadowColorHex,
                                selectedLayer.shadowOffsetX,
                                selectedLayer.shadowOffsetY
                            )
                        },
                        colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = KeyflowBlue),
                        modifier = Modifier.testTag("stroke_enable_switch")
                    )
                }
            }

            if (selectedLayer.strokeEnabled) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Position",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextSecondary,
                        modifier = Modifier.padding(end = 4.dp)
                    )
                    listOf("OUTSIDE" to "Outside", "CENTER" to "Center", "INSIDE" to "Inside").forEach { (posKey, posLabel) ->
                        AmSegmentChip(
                            label = posLabel,
                            selected = selectedLayer.strokePosition.equals(posKey, ignoreCase = true),
                            compact = true,
                            onClick = {
                                onUpdateBorderShadow(
                                    selectedLayer.id,
                                    true,
                                    selectedLayer.strokeWidth,
                                    selectedLayer.strokeColorHex,
                                    posKey,
                                    selectedLayer.shadowEnabled,
                                    selectedLayer.shadowBlur,
                                    selectedLayer.shadowColorHex,
                                    selectedLayer.shadowOffsetX,
                                    selectedLayer.shadowOffsetY
                                )
                            },
                            testTag = "stroke_pos_$posKey"
                        )
                    }
                }

                AmTickWheelScrubber(
                    value = selectedLayer.strokeWidth,
                    minValue = 0f,
                    maxValue = 64f,
                    unit = "px",
                    label = "Size",
                    onValueChange = { w ->
                        onUpdateBorderShadow(
                            selectedLayer.id,
                            true,
                            w,
                            selectedLayer.strokeColorHex,
                            selectedLayer.strokePosition,
                            selectedLayer.shadowEnabled,
                            selectedLayer.shadowBlur,
                            selectedLayer.shadowColorHex,
                            selectedLayer.shadowOffsetX,
                            selectedLayer.shadowOffsetY
                        )
                    },
                    onReset = {
                        onUpdateBorderShadow(
                            selectedLayer.id,
                            true,
                            4f,
                            selectedLayer.strokeColorHex,
                            selectedLayer.strokePosition,
                            selectedLayer.shadowEnabled,
                            selectedLayer.shadowBlur,
                            selectedLayer.shadowColorHex,
                            selectedLayer.shadowOffsetX,
                            selectedLayer.shadowOffsetY
                        )
                    },
                    testTag = "stroke_width_slider"
                )

                AmCustomColorAndGradientPicker(
                    primaryHex = selectedLayer.strokeColorHex,
                    onPrimaryHexChange = { hex ->
                        onUpdateBorderShadow(
                            selectedLayer.id,
                            true,
                            selectedLayer.strokeWidth,
                            hex,
                            selectedLayer.strokePosition,
                            selectedLayer.shadowEnabled,
                            selectedLayer.shadowBlur,
                            selectedLayer.shadowColorHex,
                            selectedLayer.shadowOffsetX,
                            selectedLayer.shadowOffsetY
                        )
                    },
                    onRequestEyedropper = onRequestEyedropper,
                    primaryEyedropperSlot = EyedropperColorSlot.LAYER_STROKE,
                    supportsGradient = false,
                    compact = true,
                    testTagPrefix = "stroke_color_picker"
                )
            }
        }

        // 2. BORDER / DROP SHADOW SECTION
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(PanelDark)
                .border(
                    1.dp,
                    if (selectedLayer.shadowEnabled) KeyflowBlue.copy(alpha = 0.65f) else OutlineSubtle,
                    RoundedCornerShape(12.dp)
                )
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.BlurLinear,
                        contentDescription = null,
                        tint = if (selectedLayer.shadowEnabled) KeyflowBlueBright else TextSecondary,
                        modifier = Modifier.size(18.dp)
                    )
                    Text(
                        text = "Shadow",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary
                    )
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (selectedLayer.shadowEnabled) {
                        Row(
                            modifier = Modifier
                                .clip(RoundedCornerShape(7.dp))
                                .background(KeyflowBlue.copy(alpha = 0.14f))
                                .clickable(onClick = onResetShadow)
                                .testTag("reset_shadow_button")
                                .padding(horizontal = 8.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Icon(Icons.Default.Refresh, contentDescription = "Reset Shadow", tint = KeyflowBlueBright, modifier = Modifier.size(13.dp))
                            Text("Reset", style = MaterialTheme.typography.labelSmall, color = KeyflowBlueBright, fontWeight = FontWeight.SemiBold)
                        }
                    }
                    Switch(
                        checked = selectedLayer.shadowEnabled,
                        onCheckedChange = { sh ->
                            onUpdateBorderShadow(
                                selectedLayer.id,
                                selectedLayer.strokeEnabled,
                                selectedLayer.strokeWidth,
                                selectedLayer.strokeColorHex,
                                selectedLayer.strokePosition,
                                sh,
                                selectedLayer.shadowBlur,
                                selectedLayer.shadowColorHex,
                                selectedLayer.shadowOffsetX,
                                selectedLayer.shadowOffsetY
                            )
                        },
                        colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = KeyflowBlue),
                        modifier = Modifier.testTag("shadow_enable_switch")
                    )
                }
            }

            if (selectedLayer.shadowEnabled) {
                AmTickWheelScrubber(
                    value = selectedLayer.shadowBlur,
                    minValue = 0f,
                    maxValue = 80f,
                    unit = "px",
                    label = "Blur",
                    onValueChange = { blur ->
                        onUpdateBorderShadow(
                            selectedLayer.id,
                            selectedLayer.strokeEnabled,
                            selectedLayer.strokeWidth,
                            selectedLayer.strokeColorHex,
                            selectedLayer.strokePosition,
                            true,
                            blur,
                            selectedLayer.shadowColorHex,
                            selectedLayer.shadowOffsetX,
                            selectedLayer.shadowOffsetY
                        )
                    },
                    onReset = {
                        onUpdateBorderShadow(
                            selectedLayer.id,
                            selectedLayer.strokeEnabled,
                            selectedLayer.strokeWidth,
                            selectedLayer.strokeColorHex,
                            selectedLayer.strokePosition,
                            true,
                            18f,
                            selectedLayer.shadowColorHex,
                            selectedLayer.shadowOffsetX,
                            selectedLayer.shadowOffsetY
                        )
                    },
                    testTag = "shadow_blur_slider"
                )

                AmTickWheelScrubber(
                    value = selectedLayer.shadowOffsetX,
                    minValue = -80f,
                    maxValue = 80f,
                    unit = "px",
                    label = "Offset X",
                    onValueChange = { ox ->
                        onUpdateBorderShadow(
                            selectedLayer.id,
                            selectedLayer.strokeEnabled,
                            selectedLayer.strokeWidth,
                            selectedLayer.strokeColorHex,
                            selectedLayer.strokePosition,
                            true,
                            selectedLayer.shadowBlur,
                            selectedLayer.shadowColorHex,
                            ox,
                            selectedLayer.shadowOffsetY
                        )
                    },
                    onReset = {
                        onUpdateBorderShadow(
                            selectedLayer.id,
                            selectedLayer.strokeEnabled,
                            selectedLayer.strokeWidth,
                            selectedLayer.strokeColorHex,
                            selectedLayer.strokePosition,
                            true,
                            selectedLayer.shadowBlur,
                            selectedLayer.shadowColorHex,
                            0f,
                            selectedLayer.shadowOffsetY
                        )
                    },
                    testTag = "shadow_offset_x_slider"
                )

                AmTickWheelScrubber(
                    value = selectedLayer.shadowOffsetY,
                    minValue = -80f,
                    maxValue = 80f,
                    unit = "px",
                    label = "Offset Y",
                    onValueChange = { oy ->
                        onUpdateBorderShadow(
                            selectedLayer.id,
                            selectedLayer.strokeEnabled,
                            selectedLayer.strokeWidth,
                            selectedLayer.strokeColorHex,
                            selectedLayer.strokePosition,
                            true,
                            selectedLayer.shadowBlur,
                            selectedLayer.shadowColorHex,
                            selectedLayer.shadowOffsetX,
                            oy
                        )
                    },
                    onReset = {
                        onUpdateBorderShadow(
                            selectedLayer.id,
                            selectedLayer.strokeEnabled,
                            selectedLayer.strokeWidth,
                            selectedLayer.strokeColorHex,
                            selectedLayer.strokePosition,
                            true,
                            selectedLayer.shadowBlur,
                            selectedLayer.shadowColorHex,
                            selectedLayer.shadowOffsetX,
                            8f
                        )
                    },
                    testTag = "shadow_offset_y_slider"
                )

                AmCustomColorAndGradientPicker(
                    primaryHex = selectedLayer.shadowColorHex,
                    onPrimaryHexChange = { hex ->
                        onUpdateBorderShadow(
                            selectedLayer.id,
                            selectedLayer.strokeEnabled,
                            selectedLayer.strokeWidth,
                            selectedLayer.strokeColorHex,
                            selectedLayer.strokePosition,
                            true,
                            selectedLayer.shadowBlur,
                            hex,
                            selectedLayer.shadowOffsetX,
                            selectedLayer.shadowOffsetY
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
}

@Composable
private fun AmMaskAndRotoSubPanel(
    selectedLayer: Layer,
    playheadMs: Long,
    audioSpectrum: AudioSpectrumSnapshot,
    activeMaskId: String?,
    penMaskEditActive: Boolean,
    rotoBrushActive: Boolean,
    rotoTargetMode: RotoTargetMode,
    rotoBrushRadiusDp: Float,
    rotoStrokeCount: Int,
    isRotoPropagating: Boolean,
    rotoPropagateProgress: Float,
    showRotoModelDownloadDialog: Boolean,
    rotoModelState: RotoModelState,
    onAddMask: (MaskShapeType) -> Unit,
    onSelectMask: (String?) -> Unit,
    onSetPenMaskEditMode: (Boolean) -> Unit,
    onUpdateMask: (String, (LayerMask) -> LayerMask) -> Unit,
    onRemoveMask: (String) -> Unit,
    onToggleClippingMask: () -> Unit,
    onActivateRotoBrush: () -> Unit,
    onDismissRotoDownloadDialog: () -> Unit,
    onConfirmDownloadRotoModels: () -> Unit,
    onSetRotoTargetMode: (RotoTargetMode) -> Unit,
    onSetRotoBrushRadiusDp: (Float) -> Unit,
    onRunAutoRoto: () -> Unit,
    onUndoRotoStroke: () -> Unit,
    onRedoRotoStroke: () -> Unit,
    onClearRotoStrokes: () -> Unit,
    onPropagateRoto: () -> Unit,
    onCancelRotoPropagation: () -> Unit
) {
    val activeMask = selectedLayer.masks.firstOrNull { it.id == activeMaskId }
        ?: selectedLayer.masks.lastOrNull()

    if (showRotoModelDownloadDialog) {
        val isDownloading = rotoModelState.isDownloading
        AlertDialog(
            onDismissRequest = { if (!isDownloading) onDismissRotoDownloadDialog() },
            containerColor = MaterialTheme.colorScheme.surface,
            title = {
                Text(
                    text = "Download Roto AI Model",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "First-use on-device segmentation + optical flow weights (~14 MB, Apache-2.0 / MIT). Cached locally after download.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (isDownloading) {
                        LinearProgressIndicator(
                            progress = { rotoModelState.progress.coerceIn(0f, 1f) },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = onConfirmDownloadRotoModels,
                    enabled = !isDownloading,
                    modifier = Modifier.testTag("confirm_roto_model_download_button")
                ) {
                    Text(if (isDownloading) "Downloading..." else "Download")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = onDismissRotoDownloadDialog,
                    modifier = Modifier.testTag("cancel_roto_model_download_button")
                ) {
                    Text("Cancel")
                }
            }
        )
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("mask_roto_inspector_panel"),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // 1. Add Mask Shape / Pen / Roto Row
        Text(
            text = "Mask & Roto Tools",
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            AmSegmentChip(
                label = "+ Rectangle",
                selected = false,
                compact = true,
                onClick = { onAddMask(MaskShapeType.RECTANGLE) },
                testTag = "add_mask_rectangle_button"
            )
            AmSegmentChip(
                label = "+ Ellipse",
                selected = false,
                compact = true,
                onClick = { onAddMask(MaskShapeType.ELLIPSE) },
                testTag = "add_mask_ellipse_button"
            )
            AmSegmentChip(
                label = if (penMaskEditActive) "Pen (Active)" else "+ Pen",
                selected = penMaskEditActive,
                compact = true,
                onClick = {
                    if (penMaskEditActive) onSetPenMaskEditMode(false)
                    else onAddMask(MaskShapeType.PEN)
                },
                testTag = "add_mask_pen_button"
            )
            AmSegmentChip(
                label = if (rotoBrushActive) "Roto (Painting)" else "Roto AI",
                selected = rotoBrushActive,
                compact = true,
                onClick = onActivateRotoBrush,
                testTag = "activate_roto_brush_button"
            )
            AmSegmentChip(
                label = if (selectedLayer.isClippingMask) "Clip Layer ✓" else "Clip Layer",
                selected = selectedLayer.isClippingMask,
                compact = true,
                onClick = onToggleClippingMask,
                testTag = "toggle_layer_clipping_mask_chip"
            )
        }

        // 2. Interactive Rotoscoping Controls (Section 5.2)
        if (rotoBrushActive || selectedLayer.masks.any { it.shapeType == MaskShapeType.ROTO }) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainer)
                    .border(0.8.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(12.dp))
                    .padding(10.dp)
                    .testTag("roto_controls_card"),
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
                            imageVector = Icons.Default.Brush,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(16.dp)
                        )
                        Text(
                            text = "Roto Brush ($rotoStrokeCount stroke(s))",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        AmSquareIconButton(
                            icon = Icons.AutoMirrored.Filled.Undo,
                            contentDescription = "Undo Roto Stroke",
                            onClick = onUndoRotoStroke,
                            sizeDp = 32.dp,
                            testTag = "roto_undo_stroke_button"
                        )
                        AmSquareIconButton(
                            icon = Icons.AutoMirrored.Filled.Redo,
                            contentDescription = "Redo Roto Stroke",
                            onClick = onRedoRotoStroke,
                            sizeDp = 32.dp,
                            testTag = "roto_redo_stroke_button"
                        )
                        AmSquareIconButton(
                            icon = Icons.Default.DeleteOutline,
                            contentDescription = "Clear Roto Strokes",
                            onClick = onClearRotoStrokes,
                            sizeDp = 32.dp,
                            testTag = "roto_clear_strokes_button"
                        )
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    RotoTargetMode.entries.forEach { mode ->
                        AmSegmentChip(
                            label = mode.label,
                            selected = rotoTargetMode == mode,
                            compact = true,
                            onClick = { onSetRotoTargetMode(mode) },
                            modifier = Modifier.weight(1f),
                            testTag = "roto_mode_${mode.name.lowercase()}"
                        )
                    }
                }

                AmTickWheelScrubber(
                    value = rotoBrushRadiusDp,
                    minValue = 6f,
                    maxValue = 56f,
                    unit = "dp",
                    label = "Brush Size",
                    onValueChange = onSetRotoBrushRadiusDp,
                    onReset = { onSetRotoBrushRadiusDp(18f) },
                    testTag = "roto_brush_size_slider"
                )

                if (isRotoPropagating) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        LinearProgressIndicator(
                            progress = { rotoPropagateProgress.coerceIn(0f, 1f) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("roto_propagate_progress")
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Propagating frames (${(rotoPropagateProgress * 100).toInt()}%)...",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            OutlinedButton(
                                onClick = onCancelRotoPropagation,
                                modifier = Modifier.testTag("roto_cancel_propagate_button")
                            ) {
                                Text("Cancel")
                            }
                        }
                    }
                } else {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = {
                                onSetRotoTargetMode(RotoTargetMode.REMOVE_OBJECT)
                                onRunAutoRoto()
                            },
                            modifier = Modifier
                                .weight(1f)
                                .testTag("roto_auto_remove_subject_button")
                        ) {
                            Text("Auto Remove Subject")
                        }
                        Button(
                            onClick = onPropagateRoto,
                            modifier = Modifier
                                .weight(1f)
                                .testTag("roto_propagate_button")
                        ) {
                            Text("Propagate Clip")
                        }
                    }
                }
            }
        }

        // 3. Mask List + Feather / Expansion / Opacity / Invert
        // CRITICAL (Section 5.1): Feather, Expansion, and Opacity are STRICTLY HIDDEN until a mask exists!
        if (selectedLayer.masks.isEmpty()) {
            Text(
                text = "No masks on this layer yet. Add a Rectangle, Ellipse, Pen, or Roto mask above to reveal Feather, Expansion, and Opacity controls.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag("mask_empty_hint")
            )
        } else if (activeMask != null) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                selectedLayer.masks.forEach { mask ->
                    AmSegmentChip(
                        label = mask.name,
                        selected = mask.id == activeMask.id,
                        compact = true,
                        onClick = { onSelectMask(mask.id) },
                        testTag = "select_mask_chip_${mask.id}"
                    )
                }
                AmSquareIconButton(
                    icon = Icons.Default.DeleteOutline,
                    contentDescription = "Delete Active Mask",
                    tint = NeonPink,
                    onClick = { onRemoveMask(activeMask.id) },
                    sizeDp = 32.dp,
                    testTag = "delete_active_mask_button"
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Invert Mask (${activeMask.shapeType.label})",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Switch(
                    checked = activeMask.inverted,
                    onCheckedChange = { inv ->
                        onUpdateMask(activeMask.id) { it.copy(inverted = inv) }
                    },
                    modifier = Modifier.testTag("mask_invert_switch")
                )
            }

            val featherEval = AnimationEvaluator.evaluateProperty(activeMask.featherPx, playheadMs, audioSpectrum)
            val expansionEval = AnimationEvaluator.evaluateProperty(activeMask.expansionPx, playheadMs, audioSpectrum)
            val maskOpacityEval = AnimationEvaluator.evaluateProperty(activeMask.opacityPct, playheadMs, audioSpectrum)

            AmTickWheelScrubber(
                value = featherEval,
                minValue = 0f,
                maxValue = 200f,
                unit = "px",
                label = "Mask Feather",
                onValueChange = { next ->
                    onUpdateMask(activeMask.id) { m -> m.copy(featherPx = m.featherPx.copy(baseValue = next)) }
                },
                onReset = {
                    onUpdateMask(activeMask.id) { m -> m.copy(featherPx = m.featherPx.copy(baseValue = 0f)) }
                },
                testTag = "mask_feather_slider"
            )

            AmTickWheelScrubber(
                value = expansionEval,
                minValue = -200f,
                maxValue = 200f,
                unit = "px",
                label = "Mask Expansion",
                onValueChange = { next ->
                    onUpdateMask(activeMask.id) { m -> m.copy(expansionPx = m.expansionPx.copy(baseValue = next)) }
                },
                onReset = {
                    onUpdateMask(activeMask.id) { m -> m.copy(expansionPx = m.expansionPx.copy(baseValue = 0f)) }
                },
                testTag = "mask_expansion_slider"
            )

            AmTickWheelScrubber(
                value = maskOpacityEval,
                minValue = 0f,
                maxValue = 100f,
                unit = "%",
                label = "Mask Opacity",
                onValueChange = { next ->
                    onUpdateMask(activeMask.id) { m -> m.copy(opacityPct = m.opacityPct.copy(baseValue = next)) }
                },
                onReset = {
                    onUpdateMask(activeMask.id) { m -> m.copy(opacityPct = m.opacityPct.copy(baseValue = 100f)) }
                },
                testTag = "mask_opacity_slider"
            )
        }
    }
}

