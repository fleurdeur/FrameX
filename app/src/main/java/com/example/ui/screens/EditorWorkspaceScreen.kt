@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)

package com.example.ui.screens

import android.graphics.Bitmap
import android.graphics.Paint
import android.graphics.Typeface
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.ShowChart
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.BorderStyle
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.FirstPage
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.GridOn
import androidx.compose.material.icons.filled.IosShare
import androidx.compose.material.icons.filled.LastPage
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Loop
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.OpenWith
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.ViewInAr
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
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
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.example.data.model.AudioBand
import com.example.data.model.EffectNode
import com.example.data.model.EffectParamSlot
import com.example.data.model.Layer
import com.example.data.model.LayerType
import com.example.data.model.Primitive3DType
import com.example.data.model.PropertyTarget
import com.example.data.preferences.PreviewQuality
import com.example.data.preferences.StudioPreferences
import com.example.engine.AnimationEvaluator
import com.example.engine.AudioSpectrumSnapshot
import com.example.engine.PreRenderUiState
import com.example.ui.components.AmInteractiveEyedropperOverlay
import com.example.ui.components.AmSegmentChip
import com.example.ui.components.AmSquareIconButton
import com.example.ui.components.EyedropperColorSlot
import com.example.ui.components.GlPreviewCanvas
import com.example.ui.components.PreviewFrameSampler
import com.example.ui.theme.AmTeal
import com.example.ui.theme.AmTealBright
import com.example.ui.theme.AmTealContainer
import com.example.ui.theme.AudioCyan
import com.example.ui.theme.DeepBlack
import com.example.ui.theme.KeyflowBlue
import com.example.ui.theme.KeyflowBlueBright
import com.example.ui.theme.KeyflowCardBorder
import com.example.ui.theme.KeyflowCardSurface
import com.example.ui.theme.KeyflowDangerRed
import com.example.ui.theme.KeyflowDrawerBg
import com.example.ui.theme.KeyflowRailDivider
import com.example.ui.theme.KeyframeAmber
import com.example.ui.theme.NeonPink
import com.example.ui.theme.OutlineSubtle
import com.example.ui.theme.PanelDark
import com.example.ui.theme.SurfaceDark
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import com.example.viewmodel.AmInspectorTab
import com.example.viewmodel.StudioDestination
import com.example.viewmodel.StudioWorkspaceState
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToLong

private val TimelinePitchBlack = Color(0xFF000000)
private val TimelineRulerBg = Color(0xFF060709)
private val TimelineTransportBg = Color(0xFF08090C)
private val TimelineHairline = Color(0xFF20242C)
private val PlayheadEmerald = Color(0xFF1DB954)
private val BookmarkSkyBlue = Color(0xFF5CC2F2)
private val ClipMediaBlue = Color(0xFF0D8BFF)
private val ClipRigRed = Color(0xFFF0484E)

@Composable
fun EditorWorkspaceScreen(
    state: StudioWorkspaceState,
    audioSpectrum: AudioSpectrumSnapshot,
    preferences: StudioPreferences = StudioPreferences(),
    preRenderState: PreRenderUiState = PreRenderUiState(),
    onBack: () -> Unit,
    onSaveCloud: () -> Unit,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onNavigateTool: (StudioDestination) -> Unit,
    onSetInspectorTab: (AmInspectorTab) -> Unit,
    onCollapseToTimeline: () -> Unit,
    onSetTimelineZoom: (Float) -> Unit,
    onTogglePlay: () -> Unit,
    onToggleLoop: () -> Unit = {},
    onTriggerOrCancelPreRender: () -> Unit = {},
    onSetPreviewQuality: (PreviewQuality) -> Unit = {},
    onClearPlaybackCache: () -> Unit = {},
    onRenderTelemetryUpdate: ((Int, Float) -> Unit)? = null,
    onStepFrame: (Int) -> Unit,
    onScrubPlayhead: (Long) -> Unit,
    onToggleBeatBookmark: () -> Unit,
    onJumpBookmark: (Boolean) -> Unit,
    onJumpKeyframe: (Boolean) -> Unit,
    onSelectLayer: (String) -> Unit,
    onSelectProperty: (PropertyTarget) -> Unit,
    onUpdatePropertyValue: (Float) -> Unit,
    onUpdateExpression: (String) -> Unit,
    onDragLayerOnCanvas: (Float, Float) -> Unit,
    onScaleAndRotateLayerOnCanvas: (Float, Float) -> Unit = { _, _ -> },
    onResetTransform: () -> Unit,
    onToggleKeyframe: () -> Unit,
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
    onImportMediaUri: (Uri, LayerType) -> Unit,
    onExtractAudioFromVideo: () -> Unit,
    onAddLayer: (LayerType, String) -> Unit,
    onAddShapeKind: (String) -> Unit,
    onCopyLayer: () -> Unit,
    onPasteLayer: () -> Unit,
    onCopyEffects: () -> Unit,
    onPasteEffects: () -> Unit,
    onDuplicateLayer: () -> Unit,
    onMoveLayerZOrder: (String, Boolean) -> Unit,
    onRenameLayer: (String, String) -> Unit = { _, _ -> },
    onToggleSoloLayer: (String) -> Unit = {},
    onGroupIntoNullRig: () -> Unit = {},
    onExpandLayerToComp: () -> Unit = {},
    onPullStart: () -> Unit,
    onTrimStart: () -> Unit,
    onUpdateSpeed: (Float) -> Unit,
    onToggleReverse: () -> Unit,
    onToggleFreezeFrame: () -> Unit,
    onToggleTimeRemap: (Boolean) -> Unit,
    onApplyVelocityPreset: (String) -> Unit,
    onUpdateAudioVolume: (String, Float, Boolean) -> Unit,
    onToggleMotionBlur: (String, Boolean, Float) -> Unit,
    onToggleClippingMask: (String) -> Unit,
    onFitOrFillComposition: (Boolean) -> Unit,
    onSplitLayer: () -> Unit,
    onTrimEnd: () -> Unit,
    onPullEnd: () -> Unit,
    onFlipLayer: (Boolean) -> Unit,
    onDeleteSelectedLayer: () -> Unit,
    onToggleLayerVisibility: (String) -> Unit,
    onToggleLayerLock: (String) -> Unit,
    onSetLayerParent: (String, String?) -> Unit,
    onUpdateTextLayer: (String, String, String) -> Unit,
    onUpdateLayerColor: (String, String) -> Unit,
    onUpdateLayerGradientFill: (String, String, String, String, Float) -> Unit = { _, _, _, _, _ -> },
    onUpdateAudioEffects: (String, String, Float, Float, Float, Float, Float, Float, Float, Float?) -> Unit = { _, _, _, _, _, _, _, _, _, _ -> },
    onImportCustomFont: (Uri) -> Unit = {},
    onMoveLayerTimeRange: (String, Long, Boolean) -> Unit = { _, _, _ -> },
    onAutoDetectBassBeats: (Float) -> Unit = {},
    onClearAllBeatMarkers: () -> Unit = {},
    onBindAudioToProperty: (AudioBand, Float) -> Unit = { _, _ -> },
    onUpdateShapeKind: (String, String) -> Unit,
    onUpdateBlendMode: (String, String) -> Unit,
    onUpdateBorderShadow: (String, Boolean, Float, String, Boolean, Float, String) -> Unit,
    onUpdate3DPrimitive: (String, Primitive3DType) -> Unit,
    onUpdateEffectNode: (EffectNode) -> Unit,
    onRemoveEffectNode: (String) -> Unit,
    onClearAllEffects: (() -> Unit)? = null,
    onUpdateProjectSettings: (String, Int, Int, Int, Int, String) -> Unit,
    onViewportGesture: (Float, Float, Float) -> Unit,
    onResetViewport: () -> Unit
) {
    val context = LocalContext.current
    val project = state.activeProject
    val selectedLayer = project.layers.firstOrNull { it.id == state.selectedLayerId }
    val isLayerSheetOpen = state.inspectorTab != AmInspectorTab.TIMELINE && selectedLayer != null
    val density = LocalDensity.current

    val previewFrameSampler = remember { PreviewFrameSampler() }
    var activeEyedropperSlot by remember { mutableStateOf<EyedropperColorSlot?>(null) }
    var pendingPluginEyedropperColorKey by remember { mutableStateOf<String?>(null) }
    var eyedropperBitmap by remember { mutableStateOf<Bitmap?>(null) }

    var showAddDrawer by remember { mutableStateOf(false) }
    var showProjectSettings by remember { mutableStateOf(false) }
    var showClipboardMenu by remember { mutableStateOf(false) }
    var showQualityMenu by remember { mutableStateOf(false) }
    var showPlayHoldMenu by remember { mutableStateOf(false) }
    var showFloatingBeatReactor by remember { mutableStateOf(false) }
    var showCanvasGrid by remember { mutableStateOf(false) }

    val launchScreenEyedropper: (EyedropperColorSlot) -> Unit = { slot ->
        pendingPluginEyedropperColorKey = null
        previewFrameSampler.captureScreenBitmap(
            context = context,
            project = project,
            playheadMs = state.playheadMs,
            audio = audioSpectrum
        ) { bmp ->
            eyedropperBitmap = bmp
            activeEyedropperSlot = slot
        }
    }
    val launchPluginColorEyedropper: (String) -> Unit = { colorKey ->
        pendingPluginEyedropperColorKey = colorKey
        previewFrameSampler.captureScreenBitmap(
            context = context,
            project = project,
            playheadMs = state.playheadMs,
            audio = audioSpectrum
        ) { bmp ->
            eyedropperBitmap = bmp
            activeEyedropperSlot = EyedropperColorSlot.EFFECT_TINT
        }
    }

    val videoPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) onImportMediaUri(uri, LayerType.VIDEO)
    }
    val imagePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) onImportMediaUri(uri, LayerType.IMAGE)
    }
    val audioPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) onImportMediaUri(uri, LayerType.AUDIO)
    }

    var showTopRenameDialog by remember { mutableStateOf(false) }
    var topRenameDraft by remember(selectedLayer?.id, selectedLayer?.name) {
        mutableStateOf(selectedLayer?.name.orEmpty())
    }
    var showTopLayerOverflow by remember { mutableStateOf(false) }

    BackHandler(enabled = selectedLayer != null || showAddDrawer) {
        when {
            showAddDrawer -> showAddDrawer = false
            selectedLayer != null && state.inspectorTab != AmInspectorTab.LAYER_OVERVIEW && state.inspectorTab != AmInspectorTab.TIMELINE -> {
                onSetInspectorTab(AmInspectorTab.LAYER_OVERVIEW)
            }
            selectedLayer != null -> onCollapseToTimeline()
        }
    }

    if (showTopRenameDialog && selectedLayer != null) {
        AlertDialog(
            onDismissRequest = { showTopRenameDialog = false },
            containerColor = SurfaceDark,
            title = { Text("Rename Layer", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold) },
            text = {
                OutlinedTextField(
                    value = topRenameDraft,
                    onValueChange = { topRenameDraft = it },
                    label = { Text("Name") },
                    singleLine = true,
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onRenameLayer(selectedLayer.id, topRenameDraft)
                        showTopRenameDialog = false
                    }
                ) {
                    Text("Save", color = KeyflowBlueBright, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showTopRenameDialog = false }) {
                    Text("Cancel", color = TextSecondary)
                }
            }
        )
    }

    if (showProjectSettings) {
        AmProjectSettingsDialog(
            project = project,
            onSaveSettings = onUpdateProjectSettings,
            onRequestEyedropperForBg = { launchScreenEyedropper(EyedropperColorSlot.PROJECT_BG) },
            onDismiss = { showProjectSettings = false }
        )
    }

    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing,
        containerColor = TimelinePitchBlack,
        topBar = {
            if (selectedLayer != null) {
                TopAppBar(
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = TimelineTransportBg),
                    navigationIcon = {
                        IconButton(
                            onClick = onCollapseToTimeline,
                            modifier = Modifier.testTag("top_collapse_layer_button")
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                                contentDescription = "Deselect Layer",
                                tint = Color.White,
                                modifier = Modifier.size(26.dp)
                            )
                        }
                    },
                    title = {
                        Text(
                            text = selectedLayer.name,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = Color.White,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .clickable {
                                    topRenameDraft = selectedLayer.name
                                    showTopRenameDialog = true
                                }
                                .testTag("rename_layer_button")
                                .padding(horizontal = 4.dp, vertical = 4.dp)
                        )
                    },
                    actions = {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(end = 4.dp)
                        ) {
                            IconButton(
                                onClick = { onNavigateTool(StudioDestination.WORKSPACE_3D) },
                                modifier = Modifier.size(38.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.ViewInAr,
                                    contentDescription = "3D View",
                                    tint = Color.White,
                                    modifier = Modifier.size(21.dp)
                                )
                            }

                            // KeyFlow Dual-Circle Clipping Mask Toggle
                            Box(
                                modifier = Modifier
                                    .size(width = 40.dp, height = 38.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .clickable { onToggleClippingMask(selectedLayer.id) }
                                    .testTag("topbar_mask_toggle_button"),
                                contentAlignment = Alignment.Center
                            ) {
                                Canvas(modifier = Modifier.size(width = 30.dp, height = 18.dp)) {
                                    val r = size.height * 0.48f
                                    val cy = size.height * 0.5f
                                    drawCircle(
                                        color = if (selectedLayer.isClippingMask) KeyflowBlue else Color(0xFF454954),
                                        radius = r,
                                        center = Offset(r + 1.dp.toPx(), cy)
                                    )
                                    drawCircle(
                                        color = Color.White,
                                        radius = r,
                                        center = Offset(size.width - r - 1.dp.toPx(), cy)
                                    )
                                }
                            }

                            IconButton(
                                onClick = onDuplicateLayer,
                                modifier = Modifier
                                    .size(38.dp)
                                    .testTag("duplicate_layer_button")
                            ) {
                                Icon(
                                    imageVector = Icons.Default.ContentCopy,
                                    contentDescription = "Duplicate Layer",
                                    tint = Color.White,
                                    modifier = Modifier.size(20.dp)
                                )
                            }

                            Box {
                                IconButton(
                                    onClick = { showTopLayerOverflow = true },
                                    modifier = Modifier
                                        .size(38.dp)
                                        .testTag("selected_layer_overflow_button")
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.MoreVert,
                                        contentDescription = "More Options",
                                        tint = Color.White,
                                        modifier = Modifier.size(21.dp)
                                    )
                                }
                                DropdownMenu(
                                    expanded = showTopLayerOverflow,
                                    onDismissRequest = { showTopLayerOverflow = false }
                                ) {
                                    DropdownMenuItem(
                                        text = { Text("Export Video", color = KeyflowBlueBright, fontWeight = FontWeight.SemiBold) },
                                        leadingIcon = { Icon(Icons.Default.IosShare, contentDescription = null, tint = KeyflowBlueBright) },
                                        onClick = {
                                            showTopLayerOverflow = false
                                            onNavigateTool(StudioDestination.EXPORT_PIPELINE)
                                        },
                                        modifier = Modifier.testTag("editor_export_button")
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Save to Cloud") },
                                        leadingIcon = { Icon(Icons.Default.CloudUpload, contentDescription = null) },
                                        onClick = {
                                            showTopLayerOverflow = false
                                            onSaveCloud()
                                        },
                                        modifier = Modifier.testTag("editor_cloud_save_button")
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Plugin Packs (.kfpack)") },
                                        leadingIcon = { Icon(Icons.Default.Extension, contentDescription = null, tint = KeyflowBlueBright) },
                                        onClick = {
                                            showTopLayerOverflow = false
                                            onNavigateTool(StudioDestination.PLUGIN_MANAGER)
                                        }
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Project Settings") },
                                        leadingIcon = { Icon(Icons.Default.Settings, contentDescription = null) },
                                        onClick = {
                                            showTopLayerOverflow = false
                                            showProjectSettings = true
                                        }
                                    )
                                    if (selectedLayer.type == LayerType.VIDEO) {
                                        DropdownMenuItem(
                                            text = { Text("Extract Audio") },
                                            leadingIcon = { Icon(Icons.Default.GraphicEq, contentDescription = null) },
                                            onClick = {
                                                showTopLayerOverflow = false
                                                onExtractAudioFromVideo()
                                            }
                                        )
                                    }
                                    DropdownMenuItem(
                                        text = { Text(if (selectedLayer.locked) "Unlock Layer" else "Lock Layer") },
                                        onClick = {
                                            showTopLayerOverflow = false
                                            onToggleLayerLock(selectedLayer.id)
                                        }
                                    )
                                    HorizontalDivider(color = OutlineSubtle)
                                    DropdownMenuItem(
                                        text = { Text("Delete Layer", color = KeyflowDangerRed) },
                                        leadingIcon = { Icon(Icons.Default.DeleteOutline, contentDescription = null, tint = KeyflowDangerRed) },
                                        onClick = {
                                            showTopLayerOverflow = false
                                            onDeleteSelectedLayer()
                                        },
                                        modifier = Modifier.testTag("topbar_delete_layer_button")
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Exit to Projects") },
                                        leadingIcon = { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null) },
                                        onClick = {
                                            showTopLayerOverflow = false
                                            onBack()
                                        },
                                        modifier = Modifier.testTag("editor_back_button")
                                    )
                                }
                            }
                        }
                    }
                )
            } else {
                TopAppBar(
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = TimelineTransportBg),
                    navigationIcon = {
                        IconButton(
                            onClick = onBack,
                            modifier = Modifier.testTag("editor_back_button")
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "Back",
                                tint = TextPrimary
                            )
                        }
                    },
                    title = {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .clickable { showProjectSettings = true }
                                .testTag("project_settings_gear_button")
                                .padding(horizontal = 4.dp, vertical = 4.dp)
                        ) {
                            Text(
                                text = project.name,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = TextPrimary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f, fill = false)
                            )
                            Icon(
                                imageVector = Icons.Default.Settings,
                                contentDescription = "Settings",
                                tint = TextSecondary,
                                modifier = Modifier.size(15.dp)
                            )
                        }
                    },
                    actions = {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(end = 6.dp)
                        ) {
                            AmSquareIconButton(
                                icon = Icons.Default.CloudUpload,
                                contentDescription = "Save",
                                active = state.isSavingCloud,
                                onClick = onSaveCloud,
                                sizeDp = 36.dp,
                                testTag = "editor_cloud_save_button"
                            )

                            AmSquareIconButton(
                                icon = Icons.Default.IosShare,
                                contentDescription = "Export",
                                active = true,
                                onClick = { onNavigateTool(StudioDestination.EXPORT_PIPELINE) },
                                sizeDp = 36.dp,
                                testTag = "editor_export_button"
                            )
                        }
                    }
                )
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(TimelinePitchBlack)
                .padding(innerPadding)
        ) {
            // 1. TOP HALF: UNOBSTRUCTED PREVIEW STAGE
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(0.45f)
                    .background(TimelinePitchBlack)
            ) {
                GlPreviewCanvas(
                    project = project,
                    playheadMs = state.playheadMs,
                    isPlaying = state.isPlaying,
                    previewQuality = state.previewQuality,
                    previewFpsCap = preferences.previewFpsCap,
                    isLooping = state.isLoopPlayback,
                    projectRevision = state.projectRevision,
                    usePlaybackCache = preferences.autoCachePlaybackFrames || preRenderState.cachedFramesCount > 0,
                    onRenderStatsUpdate = if (preferences.showPerformanceHud) onRenderTelemetryUpdate else null,
                    audioSnapshot = audioSpectrum,
                    canvasZoom = state.canvasZoom,
                    canvasPanX = state.canvasPanX,
                    canvasPanY = state.canvasPanY,
                    cameraYawDeg = state.cameraYawDeg,
                    cameraPitchDeg = state.cameraPitchDeg,
                    cameraDistance = state.cameraDistance,
                    selectedLayerId = state.selectedLayerId,
                    show3DGridOverlay = showCanvasGrid,
                    onViewportGesture = onViewportGesture,
                    onDragSelectedLayer = onDragLayerOnCanvas,
                    onScaleAndRotateSelectedLayer = onScaleAndRotateLayerOnCanvas,
                    onSelectLayerOnCanvas = onSelectLayer,
                    previewFrameSampler = previewFrameSampler,
                    modifier = Modifier.fillMaxSize()
                )

                val isCustomViewport = state.canvasZoom != 1f || state.canvasPanX != 0f || state.canvasPanY != 0f || showCanvasGrid
                Column(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(8.dp),
                    verticalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    if (isCustomViewport) {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(Color(0xCC06080B))
                                .clickable {
                                    onResetViewport()
                                    showCanvasGrid = false
                                }
                                .padding(horizontal = 8.dp, vertical = 3.dp)
                        ) {
                            Text(
                                text = "Custom View",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.SemiBold,
                                color = PlayheadEmerald
                            )
                        }
                    }

                    if (preRenderState.isPreRendering) {
                        Row(
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(Color(0xDD080B10))
                                .border(0.8.dp, PlayheadEmerald.copy(alpha = 0.75f), RoundedCornerShape(6.dp))
                                .clickable(onClick = onTriggerOrCancelPreRender)
                                .testTag("prerender_progress_pill")
                                .padding(horizontal = 8.dp, vertical = 3.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Bolt,
                                contentDescription = "Pre-render",
                                tint = PlayheadEmerald,
                                modifier = Modifier.size(12.dp)
                            )
                            Text(
                                text = "${(preRenderState.progress * 100).toInt()}%",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = PlayheadEmerald
                            )
                        }
                    }

                    if (preferences.showPerformanceHud) {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(Color(0xCC06080B))
                                .testTag("preview_performance_hud")
                                .padding(horizontal = 7.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = "${state.livePreviewFps} fps",
                                style = MaterialTheme.typography.labelSmall,
                                color = PlayheadEmerald
                            )
                        }
                    }
                }

                if (!state.statusBanner.isNullOrBlank() && !showFloatingBeatReactor && activeEyedropperSlot == null) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .padding(8.dp)
                            .clip(RoundedCornerShape(7.dp))
                            .background(Color(0xDD0A0C10))
                            .border(0.7.dp, TimelineHairline, RoundedCornerShape(7.dp))
                            .padding(horizontal = 9.dp, vertical = 3.dp)
                    ) {
                        Text(
                            text = state.statusBanner,
                            style = MaterialTheme.typography.labelSmall,
                            color = TextPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                androidx.compose.animation.AnimatedVisibility(
                    visible = showFloatingBeatReactor && activeEyedropperSlot == null,
                    enter = fadeIn() + slideInVertically { it / 2 },
                    exit = fadeOut() + slideOutVertically { it / 2 },
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                ) {
                    AmFloatingBeatReactorPopover(
                        beatCount = project.beatMarkers.size,
                        bassLevel = audioSpectrum.bass,
                        sensitivity = state.audioSensitivity,
                        selectedLayer = selectedLayer,
                        selectedProperty = state.selectedProperty,
                        onAutoDetectBass = { sens -> onAutoDetectBassBeats(sens) },
                        onToggleBeatAtPlayhead = onToggleBeatBookmark,
                        onClearBeats = onClearAllBeatMarkers,
                        onBindAudioToProperty = onBindAudioToProperty,
                        onOpenFullAudioStudio = {
                            showFloatingBeatReactor = false
                            onNavigateTool(StudioDestination.AUDIO_REACTOR)
                        },
                        onDismiss = { showFloatingBeatReactor = false }
                    )
                }

                val curEyedropperSlot = activeEyedropperSlot
                val curEyedropperBmp = eyedropperBitmap
                if (curEyedropperSlot != null && curEyedropperBmp != null) {
                    AmInteractiveEyedropperOverlay(
                        capturedBitmap = curEyedropperBmp,
                        targetLabel = curEyedropperSlot.label,
                        onColorPicked = { pickedHex ->
                            when (curEyedropperSlot) {
                                EyedropperColorSlot.LAYER_PRIMARY -> {
                                    if (selectedLayer != null) {
                                        if (selectedLayer.fillMode == "LINEAR_GRADIENT" || selectedLayer.fillMode == "RADIAL_GRADIENT") {
                                            onUpdateLayerGradientFill(
                                                selectedLayer.id,
                                                selectedLayer.fillMode,
                                                pickedHex,
                                                selectedLayer.gradientEndColorHex,
                                                selectedLayer.gradientAngleDeg
                                            )
                                        } else {
                                            onUpdateLayerColor(selectedLayer.id, pickedHex)
                                        }
                                    }
                                }
                                EyedropperColorSlot.LAYER_GRADIENT_END -> {
                                    if (selectedLayer != null) {
                                        onUpdateLayerGradientFill(
                                            selectedLayer.id,
                                            selectedLayer.fillMode,
                                            selectedLayer.colorHex,
                                            pickedHex,
                                            selectedLayer.gradientAngleDeg
                                        )
                                    }
                                }
                                EyedropperColorSlot.LAYER_STROKE -> {
                                    if (selectedLayer != null) {
                                        onUpdateBorderShadow(
                                            selectedLayer.id,
                                            true,
                                            selectedLayer.strokeWidth,
                                            pickedHex,
                                            selectedLayer.shadowEnabled,
                                            selectedLayer.shadowBlur,
                                            selectedLayer.shadowColorHex
                                        )
                                    }
                                }
                                EyedropperColorSlot.LAYER_SHADOW -> {
                                    if (selectedLayer != null) {
                                        onUpdateBorderShadow(
                                            selectedLayer.id,
                                            selectedLayer.strokeEnabled,
                                            selectedLayer.strokeWidth,
                                            selectedLayer.strokeColorHex,
                                            true,
                                            selectedLayer.shadowBlur,
                                            pickedHex
                                        )
                                    }
                                }
                                EyedropperColorSlot.EFFECT_TINT -> {
                                    val customColorKey = pendingPluginEyedropperColorKey
                                    if (customColorKey != null) {
                                        onUpdateCustomPluginColor(customColorKey, pickedHex)
                                        pendingPluginEyedropperColorKey = null
                                    } else if (selectedLayer != null) {
                                        val fx = selectedLayer.effects.firstOrNull { it.id == state.selectedEffectId }
                                            ?: selectedLayer.effects.lastOrNull()
                                        if (fx != null) {
                                            onUpdateEffectNode(fx.copy(tintColorHex = pickedHex))
                                        }
                                    }
                                }
                                EyedropperColorSlot.PROJECT_BG -> {
                                    onUpdateProjectSettings(
                                        project.name,
                                        project.width,
                                        project.height,
                                        project.fps,
                                        (project.durationMs / 1000L).coerceAtLeast(1L).toInt(),
                                        pickedHex
                                    )
                                }
                            }
                            activeEyedropperSlot = null
                        },
                        onCancel = { activeEyedropperSlot = null }
                    )
                }
            }

            // 2. REFERENCE-DESIGN PITCH-BLACK TRANSPORT STRIP:
            // [Grid] [Full] [Bookmark]     |<   Play/Pause   >|     [Layers] [Undo] [Redo]
            val hasBookmarkHere = project.beatMarkers.any { abs(it.timeMs - state.playheadMs) <= 45L }
            Column(modifier = Modifier.fillMaxWidth()) {
                HorizontalDivider(thickness = 0.7.dp, color = TimelineHairline)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(42.dp)
                        .background(TimelineTransportBg)
                        .padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    // Left Cluster: Grid | [Full] Quality Pill | Bookmark
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(34.dp)
                                .clip(RoundedCornerShape(6.dp))
                                .combinedClickable(
                                    onClick = { showCanvasGrid = !showCanvasGrid },
                                    onLongClick = onResetViewport
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.GridOn,
                                contentDescription = "Grid",
                                tint = if (showCanvasGrid) PlayheadEmerald else Color.White,
                                modifier = Modifier.size(19.dp)
                            )
                        }

                        Box {
                            Box(
                                modifier = Modifier
                                    .height(26.dp)
                                    .clip(RoundedCornerShape(5.dp))
                                    .background(Color(0xFF0E1015))
                                    .border(
                                        1.dp,
                                        if (preRenderState.cachedFramesCount > 0) PlayheadEmerald.copy(alpha = 0.75f)
                                        else Color(0xFF3E434E),
                                        RoundedCornerShape(5.dp)
                                    )
                                    .clickable { showQualityMenu = true }
                                    .testTag("preview_quality_button")
                                    .padding(horizontal = 9.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = state.previewQuality.badge,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = Color(0xFFD6DBE4)
                                )
                            }

                            DropdownMenu(
                                expanded = showQualityMenu,
                                onDismissRequest = { showQualityMenu = false }
                            ) {
                                PreviewQuality.entries.forEach { q ->
                                    val isCurrent = state.previewQuality == q
                                    DropdownMenuItem(
                                        text = {
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                                            ) {
                                                Text(
                                                    text = q.badge,
                                                    fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal,
                                                    color = if (isCurrent) PlayheadEmerald else TextPrimary
                                                )
                                                if (isCurrent) {
                                                    Icon(
                                                        imageVector = Icons.Default.Check,
                                                        contentDescription = null,
                                                        tint = PlayheadEmerald,
                                                        modifier = Modifier.size(14.dp)
                                                    )
                                                }
                                            }
                                        },
                                        onClick = {
                                            onSetPreviewQuality(q)
                                            showQualityMenu = false
                                        },
                                        modifier = Modifier.testTag("preview_quality_option_${q.name}")
                                    )
                                }
                                HorizontalDivider(color = OutlineSubtle)
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            text = if (preRenderState.isPreRendering) "Stop" else "Pre-render",
                                            color = PlayheadEmerald,
                                            fontWeight = FontWeight.SemiBold
                                        )
                                    },
                                    onClick = {
                                        onTriggerOrCancelPreRender()
                                        showQualityMenu = false
                                    },
                                    modifier = Modifier.testTag("preview_menu_prerender")
                                )
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            text = "Loop",
                                            color = if (state.isLoopPlayback) PlayheadEmerald else TextSecondary
                                        )
                                    },
                                    onClick = {
                                        onToggleLoop()
                                        showQualityMenu = false
                                    },
                                    modifier = Modifier.testTag("preview_menu_loop")
                                )
                                if (preRenderState.cachedFramesCount > 0) {
                                    DropdownMenuItem(
                                        text = { Text("Clear Cache", color = NeonPink) },
                                        onClick = {
                                            onClearPlaybackCache()
                                            showQualityMenu = false
                                        },
                                        modifier = Modifier.testTag("preview_menu_clear_cache")
                                    )
                                }
                            }
                        }

                        Box(
                            modifier = Modifier
                                .size(34.dp)
                                .clip(RoundedCornerShape(6.dp))
                                .combinedClickable(
                                    onClick = onToggleBeatBookmark,
                                    onLongClick = { showFloatingBeatReactor = !showFloatingBeatReactor }
                                )
                                .testTag("timecode_bookmark_button"),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = if (hasBookmarkHere) Icons.Default.Bookmark else Icons.Default.BookmarkBorder,
                                contentDescription = "Bookmark",
                                tint = if (hasBookmarkHere) BookmarkSkyBlue else Color.White,
                                modifier = Modifier.size(19.dp)
                            )
                        }
                    }

                    // Center Cluster: |<   Play/Pause   >|
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(CircleShape)
                                .combinedClickable(
                                    onClick = { onJumpBookmark(false) },
                                    onLongClick = { onStepFrame(-1) }
                                )
                                .testTag("transport_prev_bookmark_or_start_button"),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.SkipPrevious,
                                contentDescription = "Previous",
                                tint = Color.White,
                                modifier = Modifier.size(22.dp)
                            )
                        }

                        Box(contentAlignment = Alignment.Center) {
                            if (showPlayHoldMenu) {
                                Popup(
                                    alignment = Alignment.TopCenter,
                                    offset = IntOffset(0, -with(density) { 44.dp.roundToPx() }),
                                    onDismissRequest = { showPlayHoldMenu = false },
                                    properties = PopupProperties(focusable = true)
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .shadow(8.dp, RoundedCornerShape(10.dp))
                                            .clip(RoundedCornerShape(10.dp))
                                            .background(Color(0xFF101218))
                                            .border(1.dp, TimelineHairline, RoundedCornerShape(10.dp))
                                            .testTag("play_hold_upper_menu")
                                            .padding(horizontal = 6.dp, vertical = 5.dp),
                                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Row(
                                            modifier = Modifier
                                                .clip(RoundedCornerShape(7.dp))
                                                .background(if (state.isLoopPlayback) AmTealContainer else PanelDark)
                                                .clickable {
                                                    onToggleLoop()
                                                    showPlayHoldMenu = false
                                                }
                                                .testTag("play_hold_loop_button")
                                                .padding(horizontal = 8.dp, vertical = 5.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.Loop,
                                                contentDescription = "Loop",
                                                tint = if (state.isLoopPlayback) PlayheadEmerald else TextSecondary,
                                                modifier = Modifier.size(14.dp)
                                            )
                                            Text(
                                                text = "Loop",
                                                style = MaterialTheme.typography.labelSmall,
                                                fontWeight = FontWeight.Bold,
                                                color = if (state.isLoopPlayback) PlayheadEmerald else TextPrimary
                                            )
                                        }

                                        Row(
                                            modifier = Modifier
                                                .clip(RoundedCornerShape(7.dp))
                                                .background(PanelDark)
                                                .clickable {
                                                    onTriggerOrCancelPreRender()
                                                    showPlayHoldMenu = false
                                                }
                                                .testTag("play_hold_prerender_button")
                                                .padding(horizontal = 8.dp, vertical = 5.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.Bolt,
                                                contentDescription = "Pre-render",
                                                tint = PlayheadEmerald,
                                                modifier = Modifier.size(14.dp)
                                            )
                                            Text(
                                                text = if (preRenderState.isPreRendering) "Stop" else "Pre-render",
                                                style = MaterialTheme.typography.labelSmall,
                                                fontWeight = FontWeight.Bold,
                                                color = PlayheadEmerald
                                            )
                                        }
                                    }
                                }
                            }

                            Box(
                                modifier = Modifier
                                    .size(38.dp)
                                    .clip(CircleShape)
                                    .combinedClickable(
                                        onClick = onTogglePlay,
                                        onLongClick = { showPlayHoldMenu = true }
                                    )
                                    .testTag("timeline_play_pause_button"),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = if (state.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                    contentDescription = if (state.isPlaying) "Pause" else "Play",
                                    tint = Color.White,
                                    modifier = Modifier.size(25.dp)
                                )
                            }
                        }

                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(CircleShape)
                                .combinedClickable(
                                    onClick = { onJumpBookmark(true) },
                                    onLongClick = { onStepFrame(1) }
                                )
                                .testTag("transport_next_bookmark_or_end_button"),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.SkipNext,
                                contentDescription = "Next",
                                tint = Color.White,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                    }

                    // Right Cluster: Layers Menu | Undo | Redo
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box {
                            Box(
                                modifier = Modifier
                                    .size(34.dp)
                                    .clip(RoundedCornerShape(6.dp))
                                    .clickable { showClipboardMenu = true }
                                    .testTag(if (isLayerSheetOpen) "topbar_clipboard_button" else "duplicate_layer_button"),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Layers,
                                    contentDescription = "Layer menu",
                                    tint = if (state.hasCopiedLayer || state.hasCopiedEffects) PlayheadEmerald else Color.White,
                                    modifier = Modifier.size(19.dp)
                                )
                            }

                            DropdownMenu(
                                expanded = showClipboardMenu,
                                onDismissRequest = { showClipboardMenu = false }
                            ) {
                                DropdownMenuItem(
                                    text = { Text("Copy") },
                                    enabled = selectedLayer != null,
                                    onClick = {
                                        onCopyLayer()
                                        showClipboardMenu = false
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("Paste") },
                                    enabled = state.hasCopiedLayer,
                                    onClick = {
                                        onPasteLayer()
                                        showClipboardMenu = false
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("Duplicate") },
                                    enabled = selectedLayer != null,
                                    onClick = {
                                        onDuplicateLayer()
                                        showClipboardMenu = false
                                    }
                                )
                                if (selectedLayer != null) {
                                    HorizontalDivider(color = OutlineSubtle)
                                    DropdownMenuItem(
                                        text = { Text("Bring Forward") },
                                        onClick = {
                                            onMoveLayerZOrder(selectedLayer.id, false)
                                            showClipboardMenu = false
                                        }
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Send Backward") },
                                        onClick = {
                                            onMoveLayerZOrder(selectedLayer.id, true)
                                            showClipboardMenu = false
                                        }
                                    )
                                    DropdownMenuItem(
                                        text = { Text(if (state.soloLayerId == selectedLayer.id) "Un-solo" else "Solo") },
                                        onClick = {
                                            onToggleSoloLayer(selectedLayer.id)
                                            showClipboardMenu = false
                                        }
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Expand to End") },
                                        onClick = {
                                            onExpandLayerToComp()
                                            showClipboardMenu = false
                                        }
                                    )
                                }
                                if (project.layers.size >= 2) {
                                    DropdownMenuItem(
                                        text = { Text("Group Rig") },
                                        onClick = {
                                            onGroupIntoNullRig()
                                            showClipboardMenu = false
                                        }
                                    )
                                }
                                if (selectedLayer?.type == LayerType.VIDEO) {
                                    HorizontalDivider(color = OutlineSubtle)
                                    DropdownMenuItem(
                                        text = { Text("Extract Audio", color = AudioCyan, fontWeight = FontWeight.Bold) },
                                        onClick = {
                                            onExtractAudioFromVideo()
                                            showClipboardMenu = false
                                        }
                                    )
                                }
                                HorizontalDivider(color = OutlineSubtle)
                                DropdownMenuItem(
                                    text = { Text("Fit") },
                                    enabled = selectedLayer != null && selectedLayer.type != LayerType.AUDIO,
                                    onClick = {
                                        onFitOrFillComposition(false)
                                        showClipboardMenu = false
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("Fill") },
                                    enabled = selectedLayer != null && selectedLayer.type != LayerType.AUDIO,
                                    onClick = {
                                        onFitOrFillComposition(true)
                                        showClipboardMenu = false
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text(if (selectedLayer?.isClippingMask == true) "Unmask" else "Mask") },
                                    enabled = selectedLayer != null && selectedLayer.type != LayerType.AUDIO,
                                    onClick = {
                                        if (selectedLayer != null) onToggleClippingMask(selectedLayer.id)
                                        showClipboardMenu = false
                                    }
                                )
                                HorizontalDivider(color = OutlineSubtle)
                                DropdownMenuItem(
                                    text = { Text("Copy Effects") },
                                    enabled = selectedLayer?.effects?.isNotEmpty() == true,
                                    onClick = {
                                        onCopyEffects()
                                        showClipboardMenu = false
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("Paste Effects") },
                                    enabled = state.hasCopiedEffects && selectedLayer != null,
                                    onClick = {
                                        onPasteEffects()
                                        showClipboardMenu = false
                                    }
                                )
                                HorizontalDivider(color = OutlineSubtle)
                                DropdownMenuItem(
                                    text = { Text("Flip H") },
                                    enabled = selectedLayer != null,
                                    onClick = {
                                        onFlipLayer(true)
                                        showClipboardMenu = false
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("Flip V") },
                                    enabled = selectedLayer != null,
                                    onClick = {
                                        onFlipLayer(false)
                                        showClipboardMenu = false
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("Reset") },
                                    enabled = selectedLayer != null,
                                    onClick = {
                                        onResetTransform()
                                        showClipboardMenu = false
                                    }
                                )
                            }
                        }

                        Box(
                            modifier = Modifier
                                .size(34.dp)
                                .clip(RoundedCornerShape(6.dp))
                                .clickable(enabled = state.canUndo, onClick = onUndo)
                                .testTag("editor_undo_button"),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.Undo,
                                contentDescription = "Undo",
                                tint = if (state.canUndo) Color.White else Color(0xFF505664),
                                modifier = Modifier.size(19.dp)
                            )
                        }

                        Box(
                            modifier = Modifier
                                .size(34.dp)
                                .clip(RoundedCornerShape(6.dp))
                                .clickable(enabled = state.canRedo, onClick = onRedo)
                                .testTag("editor_redo_button"),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.Redo,
                                contentDescription = "Redo",
                                tint = if (state.canRedo) Color.White else Color(0xFF505664),
                                modifier = Modifier.size(19.dp)
                            )
                        }
                    }
                }
                HorizontalDivider(thickness = 0.7.dp, color = TimelineHairline)
            }

            // 3. BOTTOM HALF: KEYFLOW TIMELINE + SELECTED-LAYER DOCK OR SPLIT SUB-INSPECTOR DRAWER
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(0.55f)
                    .background(TimelinePitchBlack)
            ) {
                val isSubInspectorOpen = selectedLayer != null &&
                    !showAddDrawer &&
                    state.inspectorTab != AmInspectorTab.TIMELINE &&
                    state.inspectorTab != AmInspectorTab.LAYER_OVERVIEW

                if (isSubInspectorOpen && selectedLayer != null) {
                    val selIdx = project.layers.indexOfFirst { it.id == selectedLayer.id }
                    Column(modifier = Modifier.fillMaxSize()) {
                        // Compact Timeline Strip (Time Ruler + Selected Layer Track) matching KeyFlow Effects / Sub-Panel screenshots
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(74.dp)
                                .background(TimelinePitchBlack)
                        ) {
                            AmMultitrackTimelineArea(
                                state = state,
                                cachedSpanFractions = preRenderState.cachedSpanFractions,
                                isAddDrawerOpen = false,
                                isBeatPopoverOpen = showFloatingBeatReactor,
                                showFloatingFabs = false,
                                compactSelectedOnly = true,
                                onScrubPlayhead = onScrubPlayhead,
                                onSetTimelineZoom = onSetTimelineZoom,
                                onSelectLayer = onSelectLayer,
                                onMoveLayerTimeRange = onMoveLayerTimeRange,
                                onToggleLayerVisibility = onToggleLayerVisibility,
                                onToggleFloatingBeatReactor = { showFloatingBeatReactor = !showFloatingBeatReactor },
                                onNavigateTool = onNavigateTool,
                                onAddNullRig = { onAddLayer(LayerType.NULL_OBJECT, "") },
                                onOpenAddDrawer = { showAddDrawer = true }
                            )
                        }

                        // KeyFlow Split Sub-Inspector Drawer (Left 50dp Tool Rail + Right Content)
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth()
                        ) {
                            AmClickedLayerSheet(
                                activeTab = state.inspectorTab,
                                selectedLayer = selectedLayer,
                                allLayers = project.layers,
                                projectDurationMs = project.durationMs,
                                selectedProperty = state.selectedProperty,
                                selectedEffectId = state.selectedEffectId,
                                selectedEffectSlot = state.selectedEffectSlot,
                                selectedPluginParamKey = state.selectedPluginParamKey,
                                playheadMs = state.playheadMs,
                                audioSpectrum = audioSpectrum,
                                hasCopiedEffects = state.hasCopiedEffects,
                                hasCopiedCurve = state.hasCopiedCurve,
                                canMoveForward = selIdx in 0 until project.layers.lastIndex,
                                canMoveBackward = selIdx > 0,
                                onMoveLayerZOrder = { sendBack -> onMoveLayerZOrder(selectedLayer.id, sendBack) },
                                onRenameLayer = onRenameLayer,
                                onSetInspectorTab = onSetInspectorTab,
                                onCollapseToTimeline = onCollapseToTimeline,
                                onScrubPlayhead = onScrubPlayhead,
                                onSelectProperty = onSelectProperty,
                                onUpdatePropertyValue = onUpdatePropertyValue,
                                onUpdateExpression = onUpdateExpression,
                                onDragLayerOnCanvas = onDragLayerOnCanvas,
                                onResetTransform = onResetTransform,
                                onToggleKeyframe = onToggleKeyframe,
                                onJumpKeyframe = onJumpKeyframe,
                                onUpdateBezierHandles = onUpdateBezierHandles,
                                onSelectEffectForInspection = onSelectEffectForInspection,
                                onSelectEffectSlot = onSelectEffectSlot,
                                onSelectPluginParamKey = onSelectPluginParamKey,
                                onUpdateEffectSlotValue = onUpdateEffectSlotValue,
                                onUpdateCustomPluginParamValue = onUpdateCustomPluginParamValue,
                                onUpdateCustomPluginColor = onUpdateCustomPluginColor,
                                onToggleEffectKeyframe = onToggleEffectKeyframe,
                                onJumpEffectKeyframe = onJumpEffectKeyframe,
                                onUpdateEffectBezierHandles = onUpdateEffectBezierHandles,
                                onUpdateEffectExpression = onUpdateEffectExpression,
                                onMoveEffectOrder = onMoveEffectOrder,
                                onDuplicateEffect = onDuplicateEffect,
                                onResetEffect = onResetEffect,
                                onCopyCurve = onCopyCurve,
                                onPasteCurve = onPasteCurve,
                                onDragKeyframePoint = onDragKeyframePoint,
                                onOpenGraphEditor = onOpenGraphEditor,
                                onPullStart = onPullStart,
                                onTrimStart = onTrimStart,
                                onUpdateSpeed = onUpdateSpeed,
                                onToggleReverse = onToggleReverse,
                                onToggleFreezeFrame = onToggleFreezeFrame,
                                onToggleTimeRemap = onToggleTimeRemap,
                                onApplyVelocityPreset = onApplyVelocityPreset,
                                onExtractAudio = onExtractAudioFromVideo,
                                onUpdateAudioVolume = { vol, muted ->
                                    onUpdateAudioVolume(selectedLayer.id, vol, muted)
                                },
                                onToggleMotionBlur = { en, shutter ->
                                    onToggleMotionBlur(selectedLayer.id, en, shutter)
                                },
                                onToggleClippingMask = {
                                    onToggleClippingMask(selectedLayer.id)
                                },
                                onFitOrFillComposition = onFitOrFillComposition,
                                onSplitLayer = onSplitLayer,
                                onTrimEnd = onTrimEnd,
                                onPullEnd = onPullEnd,
                                onFlipLayer = onFlipLayer,
                                onDuplicateLayer = onDuplicateLayer,
                                onDeleteLayer = onDeleteSelectedLayer,
                                onToggleVisibility = onToggleLayerVisibility,
                                onToggleLock = onToggleLayerLock,
                                onSetLayerParent = onSetLayerParent,
                                onUpdateLayerColor = onUpdateLayerColor,
                                onUpdateLayerGradientFill = onUpdateLayerGradientFill,
                                onUpdateAudioEffects = { preset, rev, room, bass, echo, delay, cutoff, pitch, spd ->
                                    onUpdateAudioEffects(selectedLayer.id, preset, rev, room, bass, echo, delay, cutoff, pitch, spd)
                                },
                                onImportCustomFont = onImportCustomFont,
                                onRequestEyedropper = launchScreenEyedropper,
                                onRequestPluginColorEyedropper = launchPluginColorEyedropper,
                                onUpdateBorderShadow = onUpdateBorderShadow,
                                onUpdateBlendMode = onUpdateBlendMode,
                                onUpdateShapeKind = onUpdateShapeKind,
                                onUpdateTextLayer = onUpdateTextLayer,
                                onUpdate3DPrimitive = onUpdate3DPrimitive,
                                onUpdateEffectNode = onUpdateEffectNode,
                                onRemoveEffectNode = onRemoveEffectNode,
                                onClearAllEffects = onClearAllEffects,
                                onCopyEffects = onCopyEffects,
                                onPasteEffects = onPasteEffects,
                                onNavigateTool = onNavigateTool
                            )
                        }
                    }
                } else if (isLayerSheetOpen && selectedLayer != null && !showAddDrawer) {
                    // KeyFlow Layer Clicked Overview: Full Multitrack Timeline + 4-Segment Trim/Split/Delete Pill + Bottom Nav Dock
                    Column(modifier = Modifier.fillMaxSize()) {
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth()
                        ) {
                            AmMultitrackTimelineArea(
                                state = state,
                                cachedSpanFractions = preRenderState.cachedSpanFractions,
                                isAddDrawerOpen = false,
                                isBeatPopoverOpen = showFloatingBeatReactor,
                                showFloatingFabs = false,
                                compactSelectedOnly = false,
                                onScrubPlayhead = onScrubPlayhead,
                                onSetTimelineZoom = onSetTimelineZoom,
                                onSelectLayer = onSelectLayer,
                                onMoveLayerTimeRange = onMoveLayerTimeRange,
                                onToggleLayerVisibility = onToggleLayerVisibility,
                                onToggleFloatingBeatReactor = { showFloatingBeatReactor = !showFloatingBeatReactor },
                                onNavigateTool = onNavigateTool,
                                onAddNullRig = { onAddLayer(LayerType.NULL_OBJECT, "") },
                                onOpenAddDrawer = { showAddDrawer = true }
                            )
                        }

                        KeyflowSelectedLayerBottomDock(
                            selectedLayer = selectedLayer,
                            onCollapseToTimeline = onCollapseToTimeline,
                            onTrimStart = onTrimStart,
                            onPullStart = onPullStart,
                            onSplitLayer = onSplitLayer,
                            onTrimEnd = onTrimEnd,
                            onPullEnd = onPullEnd,
                            onDeleteLayer = onDeleteSelectedLayer,
                            onToggleMask = { onToggleClippingMask(selectedLayer.id) },
                            onOpenInspectorTab = onSetInspectorTab
                        )
                    }
                } else {
                    AmMultitrackTimelineArea(
                        state = state,
                        cachedSpanFractions = preRenderState.cachedSpanFractions,
                        isAddDrawerOpen = showAddDrawer,
                        isBeatPopoverOpen = showFloatingBeatReactor,
                        showFloatingFabs = true,
                        compactSelectedOnly = false,
                        onScrubPlayhead = onScrubPlayhead,
                        onSetTimelineZoom = onSetTimelineZoom,
                        onSelectLayer = onSelectLayer,
                        onMoveLayerTimeRange = onMoveLayerTimeRange,
                        onToggleLayerVisibility = onToggleLayerVisibility,
                        onToggleFloatingBeatReactor = { showFloatingBeatReactor = !showFloatingBeatReactor },
                        onNavigateTool = onNavigateTool,
                        onAddNullRig = { onAddLayer(LayerType.NULL_OBJECT, "") },
                        onOpenAddDrawer = { showAddDrawer = true }
                    )
                }

                androidx.compose.animation.AnimatedVisibility(
                    visible = showAddDrawer,
                    enter = fadeIn() + slideInVertically { it / 2 },
                    exit = fadeOut() + slideOutVertically { it / 2 }
                ) {
                    AmAddElementBottomSheet(
                        hasCopiedLayer = state.hasCopiedLayer,
                        onAddShapeKind = onAddShapeKind,
                        onAddLayerType = { type -> onAddLayer(type, "") },
                        onPickVideo = {
                            videoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly))
                        },
                        onPickImage = {
                            imagePicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                        },
                        onPickAudio = {
                            audioPicker.launch("audio/*")
                        },
                        onPasteLayer = onPasteLayer,
                        onAddBeatBookmark = onToggleBeatBookmark,
                        onNavigateTool = onNavigateTool,
                        onDismiss = { showAddDrawer = false }
                    )
                }
            }
        }
    }
}

@Composable
private fun AmFloatingBeatReactorPopover(
    beatCount: Int,
    bassLevel: Float,
    sensitivity: Float,
    selectedLayer: Layer?,
    selectedProperty: PropertyTarget,
    onAutoDetectBass: (Float) -> Unit,
    onToggleBeatAtPlayhead: () -> Unit,
    onClearBeats: () -> Unit,
    onBindAudioToProperty: (AudioBand, Float) -> Unit,
    onOpenFullAudioStudio: () -> Unit,
    onDismiss: () -> Unit
) {
    var localSens by remember(sensitivity) { mutableFloatStateOf(sensitivity) }
    val activeProp = selectedLayer?.propertyFor(selectedProperty)

    Surface(
        color = SurfaceDark.copy(alpha = 0.96f),
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier
            .fillMaxWidth()
            .widthIn(max = 420.dp)
            .shadow(10.dp, RoundedCornerShape(14.dp))
            .border(1.dp, TimelineHairline, RoundedCornerShape(14.dp))
            .testTag("floating_beat_reactor_popover")
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
            verticalArrangement = Arrangement.spacedBy(7.dp)
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
                    Icon(Icons.Default.GraphicEq, contentDescription = null, tint = BookmarkSkyBlue, modifier = Modifier.size(16.dp))
                    Text(
                        text = "Beats ($beatCount)",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary
                    )
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(7.dp))
                            .background(PanelDark)
                            .border(0.8.dp, OutlineSubtle, RoundedCornerShape(7.dp))
                            .clickable(onClick = onOpenFullAudioStudio)
                            .testTag("open_full_audio_reactor_button")
                            .padding(horizontal = 7.dp, vertical = 3.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(3.dp)
                    ) {
                        Text("Audio", style = MaterialTheme.typography.labelSmall, color = AudioCyan, fontWeight = FontWeight.SemiBold)
                        Icon(Icons.Default.OpenInNew, contentDescription = "Audio", tint = AudioCyan, modifier = Modifier.size(11.dp))
                    }
                    Box(
                        modifier = Modifier
                            .size(24.dp)
                            .clip(CircleShape)
                            .background(PanelDark)
                            .clickable(onClick = onDismiss),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Default.Close, contentDescription = "Close", tint = TextSecondary, modifier = Modifier.size(14.dp))
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text("Bass", style = MaterialTheme.typography.labelSmall, color = BookmarkSkyBlue, fontWeight = FontWeight.Bold)
                LinearProgressIndicator(
                    progress = { bassLevel.coerceIn(0f, 1f) },
                    color = BookmarkSkyBlue,
                    trackColor = PanelDark,
                    modifier = Modifier
                        .width(52.dp)
                        .height(6.dp)
                        .clip(RoundedCornerShape(3.dp))
                )
                Text(
                    text = "${(localSens * 100).toInt()}%",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextSecondary
                )
                Slider(
                    value = localSens,
                    onValueChange = { localSens = it.coerceIn(0.15f, 1f) },
                    valueRange = 0.15f..1f,
                    colors = SliderDefaults.colors(thumbColor = AmTealBright, activeTrackColor = AmTeal),
                    modifier = Modifier
                        .weight(1f)
                        .height(24.dp)
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .height(32.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Brush.linearGradient(listOf(AmTealBright, AmTeal)))
                        .clickable { onAutoDetectBass(localSens) }
                        .testTag("floating_auto_detect_bass_button")
                        .padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    Icon(Icons.Default.Bolt, contentDescription = null, tint = DeepBlack, modifier = Modifier.size(14.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "Detect",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = DeepBlack
                    )
                }

                Row(
                    modifier = Modifier
                        .height(32.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(PanelDark)
                        .border(0.8.dp, OutlineSubtle, RoundedCornerShape(8.dp))
                        .clickable(onClick = onToggleBeatAtPlayhead)
                        .padding(horizontal = 9.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.Bookmark, contentDescription = null, tint = BookmarkSkyBlue, modifier = Modifier.size(13.dp))
                    Spacer(modifier = Modifier.width(3.dp))
                    Text("Add", style = MaterialTheme.typography.labelSmall, color = TextPrimary, fontWeight = FontWeight.SemiBold)
                }

                if (beatCount > 0) {
                    Row(
                        modifier = Modifier
                            .height(32.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(PanelDark)
                            .border(0.8.dp, NeonPink.copy(alpha = 0.55f), RoundedCornerShape(8.dp))
                            .clickable(onClick = onClearBeats)
                            .testTag("floating_clear_beats_button")
                            .padding(horizontal = 9.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Clear", style = MaterialTheme.typography.labelSmall, color = NeonPink, fontWeight = FontWeight.Bold)
                    }
                }
            }

            if (selectedLayer != null && activeProp != null) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = selectedProperty.label,
                        style = MaterialTheme.typography.labelSmall,
                        color = TextSecondary
                    )
                    AudioBand.entries.forEach { band ->
                        AmSegmentChip(
                            label = if (band == AudioBand.NONE) "Off" else band.label,
                            selected = activeProp.audioReactorBand == band,
                            compact = true,
                            onClick = { onBindAudioToProperty(band, activeProp.audioReactorGain) }
                        )
                    }
                }
            }
        }
    }
}

private val TIMELINE_LEFT_GUTTER = 34.dp
private val TIMELINE_RIGHT_GUTTER = 34.dp
private val TIMELINE_ROW_HEIGHT = 34.dp

/**
 * Full-width Pitch-Black Alight Motion Timeline Area matching the reference UI layout:
 * - Time Ruler with top emerald cache line, clean integer second labels (0, 1, 2...),
 *   sky-blue bookmark pins, and a bright green MM:SS:FF capsule connected to a 1.5dp vertical green playhead line.
 * - Full-width pitch-black layer rows: [Eye Gutter] | [Colored Bordered Clip Pills + Inline Name & Diamonds] | [≡ Handle].
 * - Supports 2-finger pinch zoom (0.5x..5.0x), synchronized horizontal scroll, and hold-to-drag clip shifting.
 */
@Composable
private fun KeyflowSelectedLayerBottomDock(
    selectedLayer: Layer,
    onCollapseToTimeline: () -> Unit,
    onTrimStart: () -> Unit,
    onPullStart: () -> Unit,
    onSplitLayer: () -> Unit,
    onTrimEnd: () -> Unit,
    onPullEnd: () -> Unit,
    onDeleteLayer: () -> Unit,
    onToggleMask: () -> Unit,
    onOpenInspectorTab: (AmInspectorTab) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(TimelinePitchBlack)
            .navigationBarsPadding()
    ) {
        // 1. KeyFlow 4-Segment Dark Pill Bar: [ |< ] | [ >|< ] | [ >| ] | [ Trash ]
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 6.dp)
                .height(42.dp)
                .clip(RoundedCornerShape(21.dp))
                .background(KeyflowCardSurface)
                .border(0.8.dp, KeyflowCardBorder, RoundedCornerShape(21.dp)),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .combinedClickable(onClick = onTrimStart, onLongClick = onPullStart)
                    .testTag("trim_start_button"),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.FirstPage,
                    contentDescription = "Trim Start (Hold to Pull Start)",
                    tint = KeyflowBlueBright,
                    modifier = Modifier.size(21.dp)
                )
            }

            Box(
                modifier = Modifier
                    .width(0.8.dp)
                    .height(22.dp)
                    .background(KeyflowRailDivider)
            )

            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .clickable(onClick = onSplitLayer)
                    .testTag("split_layer_button"),
                contentAlignment = Alignment.Center
            ) {
                // KeyFlow signature |>|<| split icon
                Canvas(modifier = Modifier.size(20.dp)) {
                    val c = KeyflowBlueBright
                    val cy = size.height * 0.5f
                    val cx = size.width * 0.5f
                    val sw = 1.8.dp.toPx()
                    drawLine(c, Offset(cx, 2.dp.toPx()), Offset(cx, size.height - 2.dp.toPx()), strokeWidth = sw)
                    // Left arrow ->|
                    drawLine(c, Offset(2.dp.toPx(), cy), Offset(cx - 2.5.dp.toPx(), cy), strokeWidth = sw)
                    drawLine(c, Offset(cx - 5.5.dp.toPx(), cy - 3.2.dp.toPx()), Offset(cx - 2.2.dp.toPx(), cy), strokeWidth = sw)
                    drawLine(c, Offset(cx - 5.5.dp.toPx(), cy + 3.2.dp.toPx()), Offset(cx - 2.2.dp.toPx(), cy), strokeWidth = sw)
                    // Right arrow |<-
                    drawLine(c, Offset(size.width - 2.dp.toPx(), cy), Offset(cx + 2.5.dp.toPx(), cy), strokeWidth = sw)
                    drawLine(c, Offset(cx + 5.5.dp.toPx(), cy - 3.2.dp.toPx()), Offset(cx + 2.2.dp.toPx(), cy), strokeWidth = sw)
                    drawLine(c, Offset(cx + 5.5.dp.toPx(), cy + 3.2.dp.toPx()), Offset(cx + 2.2.dp.toPx(), cy), strokeWidth = sw)
                }
            }

            Box(
                modifier = Modifier
                    .width(0.8.dp)
                    .height(22.dp)
                    .background(KeyflowRailDivider)
            )

            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .combinedClickable(onClick = onTrimEnd, onLongClick = onPullEnd)
                    .testTag("trim_end_button"),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.LastPage,
                    contentDescription = "Trim End (Hold to Pull End)",
                    tint = KeyflowBlueBright,
                    modifier = Modifier.size(21.dp)
                )
            }

            Box(
                modifier = Modifier
                    .width(0.8.dp)
                    .height(22.dp)
                    .background(KeyflowRailDivider)
            )

            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .clickable(onClick = onDeleteLayer)
                    .testTag("delete_layer_button"),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Delete,
                    contentDescription = "Delete Layer",
                    tint = KeyflowDangerRed,
                    modifier = Modifier.size(20.dp)
                )
            }
        }

        HorizontalDivider(thickness = 0.7.dp, color = KeyflowRailDivider)

        // 2. KeyFlow Bottom Navigation Bar: [ < ] | [ Edit | Move | Mask | Effects | Blending | Speed | Color | Border ]
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
                .background(KeyflowDrawerBg),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .width(44.dp)
                    .fillMaxHeight()
                    .clickable(onClick = onCollapseToTimeline)
                    .testTag("collapse_layer_sheet_button"),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                    contentDescription = "Collapse Layer Bar",
                    tint = KeyflowBlueBright,
                    modifier = Modifier.size(24.dp)
                )
            }

            Box(
                modifier = Modifier
                    .width(0.8.dp)
                    .height(26.dp)
                    .background(KeyflowRailDivider)
            )

            Row(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                KeyflowBottomNavTab(
                    label = "Edit",
                    testTag = "tile_edit_layer",
                    onClick = {
                        if (selectedLayer.type == LayerType.VIDEO || selectedLayer.type == LayerType.AUDIO) {
                            onOpenInspectorTab(AmInspectorTab.SPEED_TIMING)
                        } else {
                            onOpenInspectorTab(AmInspectorTab.EDIT_LAYER)
                        }
                    }
                ) {
                    Icon(Icons.Default.Edit, contentDescription = null, tint = Color.White, modifier = Modifier.size(20.dp))
                }

                KeyflowBottomNavTab(
                    label = "Move",
                    testTag = "tile_move_transform",
                    onClick = { onOpenInspectorTab(AmInspectorTab.MOVE_TRANSFORM) }
                ) {
                    Icon(Icons.Default.OpenWith, contentDescription = null, tint = Color.White, modifier = Modifier.size(20.dp))
                }

                KeyflowBottomNavTab(
                    label = "Mask",
                    active = selectedLayer.isClippingMask,
                    testTag = "tile_mask_toggle",
                    onClick = onToggleMask
                ) {
                    val tint = if (selectedLayer.isClippingMask) KeyflowBlueBright else Color.White
                    Canvas(modifier = Modifier.size(20.dp)) {
                        drawRoundRect(
                            color = tint,
                            topLeft = Offset(1.dp.toPx(), 3.dp.toPx()),
                            size = Size(size.width - 2.dp.toPx(), size.height - 6.dp.toPx()),
                            cornerRadius = CornerRadius(3.dp.toPx(), 3.dp.toPx())
                        )
                        drawOval(
                            color = KeyflowDrawerBg,
                            topLeft = Offset(4.dp.toPx(), 5.5.dp.toPx()),
                            size = Size(size.width - 8.dp.toPx(), size.height - 11.dp.toPx())
                        )
                    }
                }

                KeyflowBottomNavTab(
                    label = "Effects",
                    active = selectedLayer.effects.isNotEmpty(),
                    testTag = "tile_effects",
                    onClick = { onOpenInspectorTab(AmInspectorTab.EFFECTS) }
                ) {
                    Icon(
                        imageVector = Icons.Default.AutoAwesome,
                        contentDescription = null,
                        tint = if (selectedLayer.effects.isNotEmpty()) KeyflowBlueBright else Color.White,
                        modifier = Modifier.size(20.dp)
                    )
                }

                KeyflowBottomNavTab(
                    label = "Blending",
                    testTag = "tile_blending_opacity",
                    onClick = { onOpenInspectorTab(AmInspectorTab.BLENDING_OPACITY) }
                ) {
                    Canvas(modifier = Modifier.size(20.dp)) {
                        val drop = Path().apply {
                            moveTo(size.width * 0.5f, 2.dp.toPx())
                            cubicTo(
                                size.width * 0.88f, size.height * 0.45f,
                                size.width * 0.86f, size.height * 0.86f,
                                size.width * 0.5f, size.height - 2.dp.toPx()
                            )
                            cubicTo(
                                size.width * 0.14f, size.height * 0.86f,
                                size.width * 0.12f, size.height * 0.45f,
                                size.width * 0.5f, 2.dp.toPx()
                            )
                            close()
                        }
                        drawPath(drop, Color.White, style = Stroke(width = 1.7.dp.toPx()))
                    }
                }

                KeyflowBottomNavTab(
                    label = "Speed",
                    active = selectedLayer.speedMultiplier != 1.0f || selectedLayer.reversed || selectedLayer.timeRemapEnabled,
                    testTag = "tile_speed_timing",
                    onClick = { onOpenInspectorTab(AmInspectorTab.SPEED_TIMING) }
                ) {
                    Icon(
                        imageVector = Icons.Default.Speed,
                        contentDescription = null,
                        tint = if (selectedLayer.reversed || selectedLayer.speedMultiplier != 1.0f) KeyflowBlueBright else Color.White,
                        modifier = Modifier.size(20.dp)
                    )
                }

                if (selectedLayer.type == LayerType.SHAPE ||
                    selectedLayer.type == LayerType.TEXT ||
                    selectedLayer.type == LayerType.MODEL_3D
                ) {
                    KeyflowBottomNavTab(
                        label = "Color",
                        testTag = "tile_color_fill",
                        onClick = { onOpenInspectorTab(AmInspectorTab.COLOR_FILL) }
                    ) {
                        Icon(Icons.Default.Palette, contentDescription = null, tint = Color.White, modifier = Modifier.size(20.dp))
                    }
                }

                KeyflowBottomNavTab(
                    label = "Border",
                    active = selectedLayer.strokeEnabled || selectedLayer.shadowEnabled,
                    testTag = "tile_border_shadow",
                    onClick = { onOpenInspectorTab(AmInspectorTab.BORDER_SHADOW) }
                ) {
                    Icon(Icons.Default.BorderStyle, contentDescription = null, tint = Color.White, modifier = Modifier.size(20.dp))
                }
            }
        }
    }
}

@Composable
private fun KeyflowBottomNavTab(
    label: String,
    testTag: String,
    active: Boolean = false,
    onClick: () -> Unit,
    icon: @Composable () -> Unit
) {
    Column(
        modifier = Modifier
            .widthIn(min = 62.dp)
            .fillMaxHeight()
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .testTag(testTag)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        icon()
        Spacer(modifier = Modifier.height(3.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            fontSize = 11.sp,
            color = if (active) KeyflowBlueBright else Color(0xFFB8C0CE),
            maxLines = 1
        )
    }
}

@Composable
private fun AmMultitrackTimelineArea(
    state: StudioWorkspaceState,
    cachedSpanFractions: List<Pair<Float, Float>> = emptyList(),
    isAddDrawerOpen: Boolean,
    isBeatPopoverOpen: Boolean = false,
    showFloatingFabs: Boolean = true,
    compactSelectedOnly: Boolean = false,
    onScrubPlayhead: (Long) -> Unit,
    onSetTimelineZoom: (Float) -> Unit,
    onSelectLayer: (String) -> Unit,
    onMoveLayerTimeRange: (String, Long, Boolean) -> Unit,
    onToggleLayerVisibility: (String) -> Unit,
    onToggleFloatingBeatReactor: () -> Unit,
    onNavigateTool: (StudioDestination) -> Unit,
    onAddNullRig: () -> Unit,
    onOpenAddDrawer: () -> Unit
) {
    val project = state.activeProject
    val durFloat = project.durationMs.coerceAtLeast(1L).toFloat()
    val density = LocalDensity.current
    val timelineHScroll = rememberScrollState()
    val currentZoomState = rememberUpdatedState(state.timelineZoom)
    val onZoomUpdated = rememberUpdatedState(onSetTimelineZoom)
    val isLayerSelectedMode = state.selectedLayerId != null
    val activePlayheadColor = if (isLayerSelectedMode) KeyflowBlue else PlayheadEmerald

    val rulerLabelPaint = remember(density) {
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.rgb(176, 184, 198)
            textSize = with(density) { 10.sp.toPx() }
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
        }
    }
    val playheadPillTextPaint = remember(density, isLayerSelectedMode) {
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = if (isLayerSelectedMode) android.graphics.Color.rgb(6, 10, 22) else android.graphics.Color.rgb(4, 18, 8)
            textSize = with(density) { 10.sp.toPx() }
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
        }
    }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(TimelinePitchBlack)
            .pointerInput(Unit) {
                awaitEachGesture {
                    do {
                        val event = awaitPointerEvent()
                        val pressedCount = event.changes.count { it.pressed }
                        if (pressedCount >= 2) {
                            val zoomFactor = event.calculateZoom()
                            if (zoomFactor.isFinite() && abs(zoomFactor - 1f) > 0.004f) {
                                val nextZoom = (currentZoomState.value * zoomFactor).coerceIn(0.5f, 5.0f)
                                onZoomUpdated.value(nextZoom)
                                event.changes.forEach { ch ->
                                    if (ch.positionChanged()) ch.consume()
                                }
                            }
                        }
                    } while (event.changes.any { it.pressed })
                }
            }
            .testTag("timeline_multitrack_container")
    ) {
        val viewportLaneWidthDp = (maxWidth - TIMELINE_LEFT_GUTTER - TIMELINE_RIGHT_GUTTER).coerceAtLeast(140.dp)
        val zoom = state.timelineZoom.coerceIn(0.5f, 5.0f)
        val contentCanvasWidthDp = max(viewportLaneWidthDp.value, viewportLaneWidthDp.value * zoom).dp
        val compSpanWidthPx = with(density) { (viewportLaneWidthDp * zoom).toPx().coerceAtLeast(48f) }
        val timecodeShort = remember(state.playheadMs, project.fps) {
            AnimationEvaluator.formatSmpteTimecode(state.playheadMs, project.fps).drop(3)
        }

        Column(modifier = Modifier.fillMaxSize()) {
            // A. REFERENCE-DESIGN TIME RULER STRIP
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(28.dp)
                    .background(TimelineRulerBg),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .width(TIMELINE_LEFT_GUTTER)
                        .fillMaxHeight()
                        .clickable {
                            val nextZoom = when {
                                state.timelineZoom < 0.85f -> 1.0f
                                state.timelineZoom < 1.35f -> 1.8f
                                state.timelineZoom < 2.4f -> 3.0f
                                else -> 0.6f
                            }
                            onSetTimelineZoom(nextZoom)
                        }
                        .testTag("timeline_zoom_badge"),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.ZoomIn,
                        contentDescription = "Zoom",
                        tint = Color(0xFF666E7D),
                        modifier = Modifier.size(13.dp)
                    )
                }

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .horizontalScroll(timelineHScroll, enabled = zoom > 1.02f)
                ) {
                    Box(
                        modifier = Modifier
                            .width(contentCanvasWidthDp)
                            .fillMaxHeight()
                            .pointerInput(project.durationMs, compSpanWidthPx) {
                                detectTapGestures { offset ->
                                    val frac = (offset.x / compSpanWidthPx).coerceIn(0f, 1f)
                                    onScrubPlayhead((frac * project.durationMs).toLong())
                                }
                            }
                            .pointerInput(project.durationMs, compSpanWidthPx) {
                                detectHorizontalDragGestures { change, _ ->
                                    change.consume()
                                    val frac = (change.position.x / compSpanWidthPx).coerceIn(0f, 1f)
                                    onScrubPlayhead((frac * project.durationMs).toLong())
                                }
                            }
                            .testTag("timeline_ruler_scrubber")
                    ) {
                        Canvas(modifier = Modifier.fillMaxSize()) {
                            val w = compSpanWidthPx.coerceAtMost(size.width)
                            val h = size.height
                            val px = (state.playheadMs / durFloat).coerceIn(0f, 1f) * w

                            drawRect(
                                color = activePlayheadColor.copy(alpha = 0.55f),
                                topLeft = Offset(0f, 0f),
                                size = Size(px.coerceAtLeast(2f), 2.5.dp.toPx())
                            )
                            cachedSpanFractions.forEach { (startF, endF) ->
                                val sx = (startF.coerceIn(0f, 1f)) * w
                                val ex = (endF.coerceIn(0f, 1f)) * w
                                drawRect(
                                    color = activePlayheadColor,
                                    topLeft = Offset(sx, 0f),
                                    size = Size((ex - sx).coerceAtLeast(3f), 2.5.dp.toPx())
                                )
                            }

                            drawLine(
                                color = Color(0xFF1A1D24),
                                start = Offset(0f, h - 1f),
                                end = Offset(size.width, h - 1f),
                                strokeWidth = 1f
                            )

                            val totalSec = (project.durationMs / 1000f).coerceAtLeast(1f)
                            val pxPerSec = w / totalSec
                            val secStep = when {
                                pxPerSec >= 38f -> 1
                                pxPerSec >= 19f -> 2
                                else -> 5
                            }
                            val subTicksPerStep = 10
                            val maxSecInt = totalSec.toInt()

                            for (s in 0..maxSecInt step secStep) {
                                val secX = (s / totalSec) * w
                                if (secX <= w + 1f) {
                                    drawLine(
                                        color = Color(0xFF4B5260),
                                        start = Offset(secX, h * 0.58f),
                                        end = Offset(secX, h),
                                        strokeWidth = 1.2f
                                    )
                                    drawContext.canvas.nativeCanvas.drawText(
                                        s.toString(),
                                        secX + 3.dp.toPx(),
                                        12.dp.toPx(),
                                        rulerLabelPaint
                                    )
                                }
                                for (sub in 1 until subTicksPerStep) {
                                    val subSec = s + (sub.toFloat() / subTicksPerStep) * secStep
                                    if (subSec <= totalSec) {
                                        val tx = (subSec / totalSec) * w
                                        val isHalf = sub == subTicksPerStep / 2
                                        drawLine(
                                            color = if (isHalf) Color(0xFF343944) else Color(0xFF22262E),
                                            start = Offset(tx, if (isHalf) h * 0.70f else h * 0.80f),
                                            end = Offset(tx, h),
                                            strokeWidth = 1f
                                        )
                                    }
                                }
                            }

                            project.beatMarkers.forEach { bm ->
                                val bx = (bm.timeMs / durFloat).coerceIn(0f, 1f) * w
                                val pinTop = Path().apply {
                                    moveTo(bx - 5.dp.toPx(), 1.5.dp.toPx())
                                    lineTo(bx + 5.dp.toPx(), 1.5.dp.toPx())
                                    lineTo(bx + 5.dp.toPx(), 7.dp.toPx())
                                    lineTo(bx, 11.5.dp.toPx())
                                    lineTo(bx - 5.dp.toPx(), 7.dp.toPx())
                                    close()
                                }
                                drawPath(pinTop, BookmarkSkyBlue)
                                drawLine(
                                    color = BookmarkSkyBlue,
                                    start = Offset(bx, 11.dp.toPx()),
                                    end = Offset(bx, h),
                                    strokeWidth = 1.4.dp.toPx()
                                )
                            }

                            val pillW = 54.dp.toPx()
                            val pillH = 16.5.dp.toPx()
                            val pillLeft = (px - pillW * 0.5f).coerceIn(0f, (size.width - pillW).coerceAtLeast(0f))
                            val pillTop = 4.dp.toPx()

                            drawLine(
                                color = activePlayheadColor,
                                start = Offset(px, pillTop + pillH),
                                end = Offset(px, h),
                                strokeWidth = 1.6.dp.toPx()
                            )
                            drawRoundRect(
                                color = activePlayheadColor,
                                topLeft = Offset(pillLeft, pillTop),
                                size = Size(pillW, pillH),
                                cornerRadius = CornerRadius(pillH * 0.5f, pillH * 0.5f)
                            )
                            val textBaselineY = pillTop + pillH * 0.5f - (playheadPillTextPaint.descent() + playheadPillTextPaint.ascent()) * 0.5f
                            drawContext.canvas.nativeCanvas.drawText(
                                timecodeShort,
                                pillLeft + pillW * 0.5f,
                                textBaselineY,
                                playheadPillTextPaint
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.width(TIMELINE_RIGHT_GUTTER))
            }

            // B. FULL-WIDTH PITCH-BLACK LAYER STACK
            if (project.layers.isEmpty()) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .background(TimelinePitchBlack)
                        .padding(bottom = if (showFloatingFabs) 52.dp else 4.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Layers,
                            contentDescription = null,
                            tint = Color(0xFF3E4552),
                            modifier = Modifier.size(26.dp)
                        )
                        Text(
                            text = "No layers",
                            style = MaterialTheme.typography.labelMedium,
                            color = TextSecondary
                        )
                    }
                }
            } else {
                val visibleLayers = remember(project.layers, state.selectedLayerId, compactSelectedOnly) {
                    val rev = project.layers.asReversed()
                    if (compactSelectedOnly && state.selectedLayerId != null) {
                        rev.filter { it.id == state.selectedLayerId }.ifEmpty { rev.take(1) }
                    } else {
                        rev
                    }
                }
                LazyColumn(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .background(TimelinePitchBlack),
                    contentPadding = PaddingValues(
                        top = 4.dp,
                        bottom = if (showFloatingFabs) 68.dp else 6.dp
                    ),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    itemsIndexed(visibleLayers, key = { _, l -> l.id }) { _, layer ->
                        val isSelected = layer.id == state.selectedLayerId
                        AmTimelineLayerRow(
                            layer = layer,
                            isSelected = isSelected,
                            anyLayerSelected = isLayerSelectedMode,
                            playheadMs = state.playheadMs,
                            durationMs = project.durationMs,
                            timelineHScroll = timelineHScroll,
                            enableHorizontalScroll = zoom > 1.02f,
                            contentCanvasWidthDp = contentCanvasWidthDp,
                            compSpanWidthPx = compSpanWidthPx,
                            onClickLayer = { onSelectLayer(layer.id) },
                            onMoveLayerTimeRange = { newStart, recordUndo ->
                                onMoveLayerTimeRange(layer.id, newStart, recordUndo)
                            },
                            onScrubPlayhead = onScrubPlayhead,
                            onToggleVisibility = { onToggleLayerVisibility(layer.id) }
                        )
                    }
                }
            }
        }

        // C. SCALED-DOWN FLOATING STUDIO ICON PILL (Bottom-Left) + CIRCULAR TEAL '+' FAB (Bottom-Right)
        if (showFloatingFabs && !isAddDrawerOpen) {
            Row(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .navigationBarsPadding()
                    .padding(start = 10.dp, bottom = 8.dp)
                    .height(36.dp)
                    .shadow(6.dp, CircleShape)
                    .clip(CircleShape)
                    .background(Color(0xE6101217))
                    .border(1.dp, TimelineHairline, CircleShape)
                    .padding(horizontal = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                AmMiniStudioIcon(
                    icon = Icons.Default.AutoFixHigh,
                    contentDescription = "Effects",
                    onClick = { onNavigateTool(StudioDestination.EFFECTS_BROWSER) },
                    testTag = "tool_effects_button"
                )
                AmMiniStudioIcon(
                    icon = Icons.AutoMirrored.Filled.ShowChart,
                    contentDescription = "Graph",
                    onClick = { onNavigateTool(StudioDestination.GRAPH_EDITOR) },
                    testTag = "tool_graph_button"
                )
                AmMiniStudioIcon(
                    icon = Icons.Default.GraphicEq,
                    contentDescription = "Audio",
                    active = isBeatPopoverOpen,
                    onClick = onToggleFloatingBeatReactor,
                    testTag = "tool_audio_button"
                )
                AmMiniStudioIcon(
                    icon = Icons.Default.ViewInAr,
                    contentDescription = "3D",
                    onClick = { onNavigateTool(StudioDestination.WORKSPACE_3D) },
                    testTag = "tool_3d_button"
                )
                AmMiniStudioIcon(
                    icon = Icons.Default.AccountTree,
                    contentDescription = "Null",
                    onClick = onAddNullRig,
                    testTag = "tool_rig_button"
                )
            }

            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .navigationBarsPadding()
                    .padding(end = 12.dp, bottom = 8.dp)
                    .size(48.dp)
                    .clickable(onClick = onOpenAddDrawer)
                    .testTag("add_layer_button"),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .size(42.dp)
                        .shadow(8.dp, CircleShape)
                        .clip(CircleShape)
                        .background(Brush.linearGradient(listOf(AmTealBright, AmTeal))),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = "Add",
                        tint = DeepBlack,
                        modifier = Modifier.size(21.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun AmMiniStudioIcon(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    testTag: String,
    active: Boolean = false
) {
    Box(
        modifier = Modifier
            .size(32.dp)
            .clip(CircleShape)
            .background(if (active) AmTealContainer else Color.Transparent)
            .clickable(onClick = onClick)
            .testTag(testTag),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = if (active) BookmarkSkyBlue else AmTealBright,
            modifier = Modifier.size(16.dp)
        )
    }
}

@Composable
private fun AmTimelineLayerRow(
    layer: Layer,
    isSelected: Boolean,
    anyLayerSelected: Boolean = false,
    playheadMs: Long,
    durationMs: Long,
    timelineHScroll: ScrollState,
    enableHorizontalScroll: Boolean,
    contentCanvasWidthDp: androidx.compose.ui.unit.Dp,
    compSpanWidthPx: Float,
    onClickLayer: () -> Unit,
    onMoveLayerTimeRange: (rawNewStartMs: Long, recordUndo: Boolean) -> Unit,
    onScrubPlayhead: (Long) -> Unit,
    onToggleVisibility: () -> Unit
) {
    val density = LocalDensity.current
    val clipFillColor = remember(layer.colorHex, layer.type) {
        when (layer.type) {
            LayerType.IMAGE, LayerType.VIDEO -> ClipMediaBlue
            LayerType.NULL_OBJECT, LayerType.CAMERA -> ClipRigRed
            LayerType.AUDIO -> AudioCyan
            else -> runCatching { Color(android.graphics.Color.parseColor(layer.colorHex)) }.getOrDefault(ClipMediaBlue)
        }
    }
    val clipLabelPaint = remember(density) {
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.WHITE
            textSize = with(density) { 11.sp.toPx() }
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            setShadowLayer(3f, 0f, 1f, android.graphics.Color.argb(150, 0, 0, 0))
        }
    }
    val kfTimes = remember(layer) { layer.allKeyframeTimes() }
    val durFloat = durationMs.coerceAtLeast(1L).toFloat()

    var isHoldingClip by remember { mutableStateOf(false) }
    var dragOriginStartMs by remember { mutableLongStateOf(0L) }
    var dragAccumPx by remember { mutableFloatStateOf(0f) }

    val latestLayer by rememberUpdatedState(layer)
    val latestDurationMs by rememberUpdatedState(durationMs)
    val latestCompSpanPx by rememberUpdatedState(compSpanWidthPx)
    val latestMoveCallback by rememberUpdatedState(onMoveLayerTimeRange)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(TIMELINE_ROW_HEIGHT)
            .background(
                when {
                    isHoldingClip -> Color(0xFF141820)
                    isSelected -> Color(0xFF06080D)
                    else -> TimelinePitchBlack
                }
            ),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .width(TIMELINE_LEFT_GUTTER)
                .fillMaxHeight()
                .clickable(onClick = onToggleVisibility),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = if (layer.visible) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                contentDescription = "Visibility",
                tint = if (layer.visible) Color(0xFFD5DCE8) else Color(0xFF3E4452),
                modifier = Modifier.size(15.dp)
            )
        }

        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .background(TimelinePitchBlack)
                .horizontalScroll(timelineHScroll, enabled = enableHorizontalScroll && !isHoldingClip)
        ) {
            Box(
                modifier = Modifier
                    .width(contentCanvasWidthDp)
                    .fillMaxHeight()
                    .pointerInput(layer.id) {
                        detectTapGestures { offset ->
                            val curL = latestLayer
                            val curDur = latestDurationMs.coerceAtLeast(1L)
                            val w = latestCompSpanPx.coerceAtLeast(1f)
                            val tappedMs = ((offset.x / w).coerceIn(0f, 1f) * curDur).toLong()
                            if (tappedMs in curL.startTimeMs..curL.endTimeMs) {
                                onClickLayer()
                            } else {
                                onScrubPlayhead(tappedMs)
                            }
                        }
                    }
                    .pointerInput(layer.id) {
                        detectDragGesturesAfterLongPress(
                            onDragStart = {
                                if (!latestLayer.locked) {
                                    isHoldingClip = true
                                    dragOriginStartMs = latestLayer.startTimeMs
                                    dragAccumPx = 0f
                                }
                            },
                            onDrag = { change, dragAmount ->
                                if (isHoldingClip && !latestLayer.locked) {
                                    change.consume()
                                    dragAccumPx += dragAmount.x
                                    val deltaMs = ((dragAccumPx / latestCompSpanPx.coerceAtLeast(1f)) * latestDurationMs).roundToLong()
                                    latestMoveCallback(dragOriginStartMs + deltaMs, false)
                                }
                            },
                            onDragEnd = {
                                if (isHoldingClip) {
                                    isHoldingClip = false
                                    latestMoveCallback(latestLayer.startTimeMs, true)
                                }
                            },
                            onDragCancel = {
                                isHoldingClip = false
                            }
                        )
                    }
                    .testTag("layer_clip_lane_${layer.id}")
            ) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val w = compSpanWidthPx.coerceAtMost(size.width)
                    val h = size.height

                    val rawSX = (layer.startTimeMs / durFloat).coerceIn(0f, 1f) * w
                    val earW = if (isSelected) 11.dp.toPx() else 0f
                    val sX = if (isSelected && rawSX < earW) earW else rawSX
                    val eX = ((layer.endTimeMs / durFloat).coerceIn(0f, 1f) * w).coerceAtLeast(sX + 6.dp.toPx())
                    val rawClipW = eX - sX
                    val minPillW = 6.dp.toPx()
                    val clipW = rawClipW.coerceAtLeast(minPillW)
                    val barTop = 2.5.dp.toPx()
                    val barH = (h - 5.dp.toPx()).coerceAtLeast(10f)
                    val cornerPx = 5.5.dp.toPx()

                    if (clipW < 18.dp.toPx()) {
                        val capsuleColor = if (clipW < 9.dp.toPx()) Color.White else clipFillColor
                        drawRoundRect(
                            color = capsuleColor,
                            topLeft = Offset(sX, barTop),
                            size = Size(clipW, barH),
                            cornerRadius = CornerRadius(clipW * 0.5f, clipW * 0.5f)
                        )
                        drawRoundRect(
                            color = Color.White,
                            topLeft = Offset(sX, barTop),
                            size = Size(clipW, barH),
                            cornerRadius = CornerRadius(clipW * 0.5f, clipW * 0.5f),
                            style = Stroke(width = 1.1.dp.toPx())
                        )
                    } else {
                        // KeyFlow Signature White Left '<' Trim Ear Cap when selected
                        if (isSelected) {
                            val earLeft = (sX - earW).coerceAtLeast(0f)
                            drawRoundRect(
                                color = Color.White,
                                topLeft = Offset(earLeft, barTop),
                                size = Size(earW + cornerPx, barH),
                                cornerRadius = CornerRadius(cornerPx, cornerPx)
                            )
                            val triCx = earLeft + earW * 0.46f
                            val triCy = h * 0.5f
                            val triHalf = 2.8.dp.toPx()
                            val arrowPath = Path().apply {
                                moveTo(triCx + triHalf * 0.55f, triCy - triHalf)
                                lineTo(triCx - triHalf * 0.65f, triCy)
                                lineTo(triCx + triHalf * 0.55f, triCy + triHalf)
                                close()
                            }
                            drawPath(arrowPath, Color(0xFF0A0C10))
                        }

                        drawRoundRect(
                            color = when {
                                isHoldingClip -> KeyframeAmber.copy(alpha = 0.85f)
                                isSelected -> clipFillColor
                                else -> clipFillColor.copy(alpha = 0.88f)
                            },
                            topLeft = Offset(sX, barTop),
                            size = Size(clipW, barH),
                            cornerRadius = CornerRadius(if (isSelected) 2.dp.toPx() else cornerPx, if (isSelected) 2.dp.toPx() else cornerPx)
                        )

                        // Subtle vertical frame ticks inside selected media clip matching KeyFlow
                        if (isSelected) {
                            val tickGap = 5.dp.toPx()
                            var tx = sX + tickGap
                            while (tx < sX + clipW - 4.dp.toPx()) {
                                drawLine(
                                    color = Color.White.copy(alpha = 0.14f),
                                    start = Offset(tx, barTop + 1.dp.toPx()),
                                    end = Offset(tx, barTop + barH - 1.dp.toPx()),
                                    strokeWidth = 1f
                                )
                                tx += tickGap
                            }
                        }

                        if (layer.extractedWaveform.isNotEmpty() && (layer.type == LayerType.AUDIO || (layer.type == LayerType.VIDEO && !layer.audioMuted))) {
                            val bins = layer.extractedWaveform
                            val stepW = clipW / bins.size.coerceAtLeast(1)
                            for (i in bins.indices) {
                                val amp = bins[i].coerceIn(0.08f, 1f)
                                val bh = amp * (barH - 6f)
                                val bx = sX + i * stepW + stepW * 0.5f
                                drawLine(
                                    color = Color.White.copy(alpha = 0.28f),
                                    start = Offset(bx, h * 0.5f - bh * 0.5f),
                                    end = Offset(bx, h * 0.5f + bh * 0.5f),
                                    strokeWidth = (stepW * 0.58f).coerceIn(1f, 3f)
                                )
                            }
                        }

                        clipRect(
                            left = sX + 4.dp.toPx(),
                            top = barTop,
                            right = sX + clipW - 4.dp.toPx(),
                            bottom = barTop + barH
                        ) {
                            if (clipW >= 34.dp.toPx()) {
                                val textY = h * 0.5f - (clipLabelPaint.descent() + clipLabelPaint.ascent()) * 0.5f
                                drawContext.canvas.nativeCanvas.drawText(
                                    layer.name,
                                    sX + 7.dp.toPx(),
                                    textY,
                                    clipLabelPaint
                                )
                            }

                            kfTimes.forEach { kt ->
                                val kx = (kt / durFloat).coerceIn(0f, 1f) * w
                                val cy = h * 0.5f
                                val r = 4.2.dp.toPx()
                                val diamond = Path().apply {
                                    moveTo(kx, cy - r)
                                    lineTo(kx + r, cy)
                                    lineTo(kx, cy + r)
                                    lineTo(kx - r, cy)
                                    close()
                                }
                                val isAtPlayhead = abs(kt - playheadMs) <= 35L
                                if (isAtPlayhead) {
                                    drawPath(diamond, Color.White)
                                } else {
                                    drawPath(diamond, Color.Black.copy(alpha = 0.28f))
                                    drawPath(diamond, Color.White.copy(alpha = 0.90f), style = Stroke(width = 1.2.dp.toPx()))
                                }
                            }
                        }

                        drawRoundRect(
                            color = when {
                                isHoldingClip -> KeyframeAmber
                                isSelected -> Color.White
                                else -> Color.White.copy(alpha = 0.82f)
                            },
                            topLeft = Offset(sX, barTop),
                            size = Size(clipW, barH),
                            cornerRadius = CornerRadius(if (isSelected) 2.dp.toPx() else cornerPx, if (isSelected) 2.dp.toPx() else cornerPx),
                            style = Stroke(width = if (isSelected || isHoldingClip) 1.6.dp.toPx() else 1.1.dp.toPx())
                        )
                    }

                    // Continuous unbroken vertical playhead line (KeyFlow Blue when layer selected, Emerald otherwise)
                    val px = (playheadMs / durFloat).coerceIn(0f, 1f) * w
                    drawLine(
                        color = if (anyLayerSelected) KeyflowBlue else PlayheadEmerald,
                        start = Offset(px, 0f),
                        end = Offset(px, h),
                        strokeWidth = 1.6.dp.toPx()
                    )
                }
            }
        }

        // Right 34dp Gutter: Clean 3-Line Hamburger Handle (Selects/Opens Layer Sheet)
        Box(
            modifier = Modifier
                .width(TIMELINE_RIGHT_GUTTER)
                .fillMaxHeight()
                .clickable(onClick = onClickLayer)
                .testTag("layer_track_${layer.id}"),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.Menu,
                contentDescription = layer.name,
                tint = if (isSelected) Color.White else Color(0xFF7D8594),
                modifier = Modifier.size(17.dp)
            )
        }
    }
}
