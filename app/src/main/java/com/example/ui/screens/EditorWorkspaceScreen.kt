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
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.ScrollableDefaults
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.gestures.rememberScrollableState
import androidx.compose.foundation.gestures.scrollable
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.ShowChart
import androidx.compose.material.icons.automirrored.filled.Undo
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
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Diamond
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.FilterNone
import androidx.compose.material.icons.filled.FirstPage
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.GridOn
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.IosShare
import androidx.compose.material.icons.filled.LastPage
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MoreHoriz
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
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.VideocamOff
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
import androidx.compose.ui.draw.clipToBounds
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.AudioBand
import com.example.data.model.EffectNode
import com.example.data.model.EffectParamSlot
import com.example.data.model.Layer
import com.example.data.model.LayerMask
import com.example.data.model.LayerType
import com.example.data.model.MaskShapeType
import com.example.data.model.Primitive3DType
import com.example.data.model.Project
import com.example.data.model.PropertyTarget
import com.example.data.preferences.PreviewQuality
import com.example.data.preferences.StudioPreferences
import com.example.engine.AnimationEvaluator
import com.example.engine.AudioSpectrumSnapshot
import com.example.engine.PlaybackCacheEngine
import com.example.engine.PluginHostRepository
import com.example.engine.PreRenderUiState
import com.example.engine.isVisibleInEditorMenu
import com.example.rotoengine.RotoBrushStroke
import com.example.rotoengine.RotoModelState
import com.example.rotoengine.RotoTargetMode
import com.example.scriptengine.InstalledPlugin
import com.example.ui.components.AmInteractiveEyedropperOverlay
import com.example.ui.components.AmSegmentChip
import com.example.ui.components.AmSquareIconButton
import com.example.ui.components.EyedropperColorSlot
import com.example.ui.components.GlPreviewCanvas
import com.example.ui.components.PluginFloatingWindowHost
import com.example.ui.components.PreviewFrameSampler
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
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.roundToLong

private val TimelinePitchBlack = Color(0xFF000000)
private val TimelineRulerBg = Color(0xFF06080D)
private val TimelineTransportBg = Color(0xFF080A10)
private val TimelineToolbarSubBg = Color(0xFF0B0E16)
private val TimelineHairline = Color(0xFF1F2634)
private val PlayheadEmerald = KeyflowBlue
private val BookmarkSkyBlue = Color(0xFF7CA0FF)
private val ClipMediaBlue = Color(0xFF2E6BFF)
private val ClipRigRed = Color(0xFFF0484E)
private val ClipGroupPurple = Color(0xFF7C3AED)

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
    onToggleLayerMultiSelection: (String) -> Unit = {},
    onGroupSelectedLayers: () -> Unit = {},
    onPreRenderLayerOrGroup: () -> Unit = {},
    onImportUniversalFile: (Uri) -> Unit = {},
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
    onAddAdjustmentLayer: () -> Unit = {},
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
    onUpdateBorderShadow: (String, Boolean, Float, String, String, Boolean, Float, String, Float, Float) -> Unit,
    onUpdate3DPrimitive: (String, Primitive3DType) -> Unit,
    onUpdateEffectNode: (EffectNode) -> Unit,
    onRemoveEffectNode: (String) -> Unit,
    onClearAllEffects: (() -> Unit)? = null,
    onUpdateProjectSettings: (String, Int, Int, Int, Int, String) -> Unit,
    onViewportGesture: (Float, Float, Float) -> Unit,
    onResetViewport: () -> Unit,
    onDeselectLayer: () -> Unit = onCollapseToTimeline,
    onToggleCameraPreview: () -> Unit = {},
    onMoveLayerToIndex: (String, Int) -> Unit = { _, _ -> },
    onResetPropertyTarget: (PropertyTarget) -> Unit = {},
    onResetMoveSubMode: (Int) -> Unit = {},
    onResetStroke: () -> Unit = {},
    onResetShadow: () -> Unit = {},
    onResetSingleEffectParam: (String, EffectParamSlot?, String?) -> Unit = { _, _, _ -> },
    onBakeBeatsToKeyframes: (PropertyTarget, Float, Long) -> Unit = { _, _, _ -> },
    onSetAddFabExpanded: (Boolean) -> Unit = {},
    onToggleGroupExpanded: (String) -> Unit = {},
    installedAePlugins: List<InstalledPlugin> = emptyList(),
    pluginHostRepository: PluginHostRepository? = null,
    onOpenAePlugin: (InstalledPlugin) -> Unit = {},
    onCloseFloatingPlugin: () -> Unit = {},
    onStatusMessage: (String) -> Unit = {},
    rotoStrokes: List<RotoBrushStroke> = emptyList(),
    rotoModelState: RotoModelState = RotoModelState(),
    onAddMask: (MaskShapeType) -> Unit = {},
    onSelectMask: (String?) -> Unit = {},
    onSetPenMaskEditMode: (Boolean) -> Unit = {},
    onAddPenMaskVertex: (Float, Float) -> Unit = { _, _ -> },
    onUpdateMask: (String, (LayerMask) -> LayerMask) -> Unit = { _, _ -> },
    onRemoveMask: (String) -> Unit = {},
    onActivateRotoBrush: () -> Unit = {},
    onDismissRotoDownloadDialog: () -> Unit = {},
    onConfirmDownloadRotoModels: () -> Unit = {},
    onSetRotoTargetMode: (RotoTargetMode) -> Unit = {},
    onSetRotoBrushRadiusDp: (Float) -> Unit = {},
    onAddRotoBrushStroke: (List<Pair<Float, Float>>, Float) -> Unit = { _, _ -> },
    onRunAutoRoto: () -> Unit = {},
    onUndoRotoStroke: () -> Unit = {},
    onRedoRotoStroke: () -> Unit = {},
    onClearRotoStrokes: () -> Unit = {},
    onPropagateRoto: () -> Unit = {},
    onCancelRotoPropagation: () -> Unit = {},
    onRelinkMissingMedia: (String, Uri) -> Unit = { _, _ -> }
) {
    val context = LocalContext.current
    val project = state.activeProject
    val selectedLayer = project.layers.firstOrNull { it.id == state.selectedLayerId }
    val isLayerSheetOpen = state.inspectorTab != AmInspectorTab.TIMELINE && selectedLayer != null

    val previewFrameSampler = remember { PreviewFrameSampler() }
    var activeEyedropperSlot by remember { mutableStateOf<EyedropperColorSlot?>(null) }
    var pendingPluginEyedropperColorKey by remember { mutableStateOf<String?>(null) }
    var eyedropperBitmap by remember { mutableStateOf<Bitmap?>(null) }

    val showAddDrawer = state.isAddFabExpanded
    val setShowAddDrawer: (Boolean) -> Unit = { expanded -> onSetAddFabExpanded(expanded) }
    val pinnedAePlugins = remember(installedAePlugins) {
        installedAePlugins.filter { it.isVisibleInEditorMenu() }
    }
    val activeFloatingPlugin = remember(installedAePlugins, state.activeFloatingPluginId) {
        val targetId = state.activeFloatingPluginId
        if (targetId == null) null else installedAePlugins.firstOrNull { it.bundleId == targetId }
    }
    var showProjectSettings by remember { mutableStateOf(false) }
    var showClipboardMenu by remember { mutableStateOf(false) }
    var showQualityMenu by remember { mutableStateOf(false) }
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
    val universalImportPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) onImportUniversalFile(uri)
    }

    var showTopRenameDialog by remember { mutableStateOf(false) }
    var topRenameDraft by remember(selectedLayer?.id, selectedLayer?.name) {
        mutableStateOf(selectedLayer?.name.orEmpty())
    }
    var showTopLayerOverflow by remember { mutableStateOf(false) }
    var showMediaInfoDialog by remember { mutableStateOf(false) }

    BackHandler(enabled = selectedLayer != null || showAddDrawer || activeFloatingPlugin != null) {
        when {
            activeFloatingPlugin != null -> onCloseFloatingPlugin()
            showAddDrawer -> setShowAddDrawer(false)
            selectedLayer != null && state.inspectorTab != AmInspectorTab.LAYER_OVERVIEW && state.inspectorTab != AmInspectorTab.TIMELINE -> {
                onSetInspectorTab(AmInspectorTab.LAYER_OVERVIEW)
            }
            selectedLayer != null -> onDeselectLayer()
        }
    }

    if (showMediaInfoDialog && selectedLayer != null) {
        KeyflowMediaInfoDialog(
            layer = selectedLayer,
            project = project,
            onDismiss = { showMediaInfoDialog = false }
        )
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
                            onClick = onDeselectLayer,
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
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            if (selectedLayer.type == LayerType.GROUP) {
                                Icon(
                                    imageVector = Icons.Default.Folder,
                                    contentDescription = "Group Layer",
                                    tint = Color(0xFFA78BFA),
                                    modifier = Modifier.size(18.dp)
                                )
                            }
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
                        }
                    },
                    actions = {
                        Box(modifier = Modifier.padding(end = 4.dp)) {
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
                                    text = { Text("Media Info", color = KeyflowBlueBright, fontWeight = FontWeight.Bold) },
                                    leadingIcon = { Icon(Icons.Default.Info, contentDescription = null, tint = KeyflowBlueBright) },
                                    onClick = {
                                        showTopLayerOverflow = false
                                        showMediaInfoDialog = true
                                    },
                                    modifier = Modifier.testTag("layer_media_info_menu_item")
                                )
                                DropdownMenuItem(
                                    text = { Text(if (selectedLayer.locked) "Unlock Layer" else "Lock Layer") },
                                    onClick = {
                                        showTopLayerOverflow = false
                                        onToggleLayerLock(selectedLayer.id)
                                    }
                                )
                                HorizontalDivider(color = OutlineSubtle)
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
                                    text = { Text("Project Settings") },
                                    leadingIcon = { Icon(Icons.Default.Settings, contentDescription = null) },
                                    onClick = {
                                        showTopLayerOverflow = false
                                        showProjectSettings = true
                                    }
                                )
                                HorizontalDivider(color = OutlineSubtle)
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
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(TimelinePitchBlack)
                .padding(innerPadding)
        ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(TimelinePitchBlack)
        ) {
            // 1. TOP HALF: UNOBSTRUCTED PREVIEW STAGE
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(0.44f)
                    .background(TimelinePitchBlack)
            ) {
                val activePenOffsets: List<Offset> = remember(selectedLayer, state.activeMaskId) {
                    val m = selectedLayer?.masks?.firstOrNull { it.id == state.activeMaskId && it.shapeType == MaskShapeType.PEN }
                        ?: selectedLayer?.masks?.lastOrNull { it.shapeType == MaskShapeType.PEN }
                    m?.vertices?.map { pt -> Offset(pt.x, pt.y) } ?: emptyList()
                }
                val rotoStrokeOffsets = remember(rotoStrokes) {
                    rotoStrokes.map { st -> st.pointsNormalized.map { (x, y) -> Offset(x, y) } }
                }

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
                    activeCameraPreviewEnabled = state.activeCameraPreviewEnabled,
                    onViewportGesture = onViewportGesture,
                    onDragSelectedLayer = onDragLayerOnCanvas,
                    onScaleAndRotateSelectedLayer = onScaleAndRotateLayerOnCanvas,
                    onSelectLayerOnCanvas = onSelectLayer,
                    onDeselectLayer = onDeselectLayer,
                    previewFrameSampler = previewFrameSampler,
                    rotoBrushActive = state.rotoBrushActive,
                    rotoBrushRadiusDp = state.rotoBrushRadiusDp,
                    rotoPreviewStrokesNormalized = rotoStrokeOffsets,
                    onRotoStrokeCompleted = { pts ->
                        val normPts = pts.map { it.x.coerceIn(0f, 1f) to it.y.coerceIn(0f, 1f) }
                        val normRadius = (state.rotoBrushRadiusDp / 360f).coerceIn(0.015f, 0.2f)
                        onAddRotoBrushStroke(normPts, normRadius)
                    },
                    penMaskEditActive = state.penMaskEditActive,
                    penMaskPointsNormalized = activePenOffsets,
                    onPenMaskPointAdded = { pt ->
                        onAddPenMaskVertex(pt.x, pt.y)
                    },
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
                        onBindAudioToProperty = onBindAudioToProperty,
                        onBakeBeatsToKeyframes = onBakeBeatsToKeyframes,
                        onClearBeats = onClearAllBeatMarkers,
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
                                            selectedLayer.strokePosition,
                                            selectedLayer.shadowEnabled,
                                            selectedLayer.shadowBlur,
                                            selectedLayer.shadowColorHex,
                                            selectedLayer.shadowOffsetX,
                                            selectedLayer.shadowOffsetY
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
                                            selectedLayer.strokePosition,
                                            true,
                                            selectedLayer.shadowBlur,
                                            pickedHex,
                                            selectedLayer.shadowOffsetX,
                                            selectedLayer.shadowOffsetY
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

            // 2. MIDDLE TIMELINE TOOLBAR (Zero Duplicate Buttons):
            // Row A: [Grid] [Full/Comp Cache] [Bookmark]   |< Play/Pause >|   [Camera] [Undo] [Redo]
            // Row B: [Copy] [Paste] [Duplicate] [Group] [Pre-render Layer/Group] [More ▾]
            val hasBookmarkHere = project.beatMarkers.any { abs(it.timeMs - state.playheadMs) <= 45L }
            val hasCameraInProject = project.layers.any { it.type == LayerType.CAMERA }
            val isSelectedLayerOrGroupCached = remember(
                selectedLayer?.id,
                state.projectRevision,
                state.previewQuality,
                preRenderState.cachedFramesCount
            ) {
                selectedLayer != null && PlaybackCacheEngine.isLayerOrGroupCached(
                    projectRevision = state.projectRevision,
                    quality = state.previewQuality,
                    layerId = selectedLayer.id
                )
            }
            val multiSelectCount = state.selectedLayerIds.size

            Column(modifier = Modifier.fillMaxWidth()) {
                HorizontalDivider(thickness = 0.7.dp, color = TimelineHairline)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(35.dp)
                        .background(TimelineTransportBg)
                        .padding(horizontal = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    // Left Cluster: Grid | [Quality] | Bookmark
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(3.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(30.dp)
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
                                tint = if (showCanvasGrid) KeyflowBlueBright else Color.White,
                                modifier = Modifier.size(17.dp)
                            )
                        }

                        Box {
                            Box(
                                modifier = Modifier
                                    .height(22.dp)
                                    .clip(RoundedCornerShape(5.dp))
                                    .background(Color(0xFF0E121B))
                                    .border(
                                        0.9.dp,
                                        if (preRenderState.cachedFramesCount > 0) KeyflowBlue.copy(alpha = 0.85f)
                                        else Color(0xFF343C4E),
                                        RoundedCornerShape(5.dp)
                                    )
                                    .clickable { showQualityMenu = true }
                                    .testTag("preview_quality_button")
                                    .padding(horizontal = 7.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = state.previewQuality.badge,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = if (preRenderState.cachedFramesCount > 0) KeyflowBlueBright else Color(0xFFD6DBE4)
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
                                                    color = if (isCurrent) KeyflowBlueBright else TextPrimary
                                                )
                                                if (isCurrent) {
                                                    Icon(
                                                        imageVector = Icons.Default.Check,
                                                        contentDescription = null,
                                                        tint = KeyflowBlueBright,
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
                                            text = if (preRenderState.isPreRendering) "Stop Comp Pre-render" else "Pre-render Full Comp",
                                            color = KeyflowBlueBright,
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
                                            text = if (state.isLoopPlayback) "Loop: On" else "Loop: Off",
                                            color = if (state.isLoopPlayback) KeyflowBlueBright else TextSecondary
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
                                .size(30.dp)
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
                                modifier = Modifier.size(17.dp)
                            )
                        }
                    }

                    // Center Cluster: |<   Play/Pause   >|
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(32.dp)
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
                                modifier = Modifier.size(20.dp)
                            )
                        }

                        Box(
                            modifier = Modifier
                                .size(34.dp)
                                .clip(CircleShape)
                                .background(if (state.isPlaying) KeyflowBlue.copy(alpha = 0.22f) else Color.Transparent)
                                .clickable(onClick = onTogglePlay)
                                .testTag("timeline_play_pause_button"),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = if (state.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                contentDescription = if (state.isPlaying) "Pause" else "Play",
                                tint = if (state.isPlaying) KeyflowBlueBright else Color.White,
                                modifier = Modifier.size(23.dp)
                            )
                        }

                        Box(
                            modifier = Modifier
                                .size(32.dp)
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
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }

                    // Right Cluster: Camera View Toggle | Undo | Redo
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(30.dp)
                                .clip(RoundedCornerShape(6.dp))
                                .background(
                                    when {
                                        !state.activeCameraPreviewEnabled -> KeyflowBlue.copy(alpha = 0.20f)
                                        hasCameraInProject -> KeyflowBlue.copy(alpha = 0.12f)
                                        else -> Color.Transparent
                                    }
                                )
                                .combinedClickable(
                                    onClick = onToggleCameraPreview,
                                    onLongClick = {
                                        if (!hasCameraInProject) onAddLayer(LayerType.CAMERA, "")
                                        else onToggleCameraPreview()
                                    }
                                )
                                .testTag("toggle_camera_view_button"),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = if (state.activeCameraPreviewEnabled) Icons.Default.Videocam else Icons.Default.VideocamOff,
                                contentDescription = if (state.activeCameraPreviewEnabled) "Camera View Active" else "Custom View (Camera Off)",
                                tint = when {
                                    !state.activeCameraPreviewEnabled -> KeyflowBlueBright
                                    hasCameraInProject -> KeyflowBlueBright
                                    else -> Color(0xFFB8C0CE)
                                },
                                modifier = Modifier.size(17.dp)
                            )
                        }

                        Box(
                            modifier = Modifier
                                .size(30.dp)
                                .clip(RoundedCornerShape(6.dp))
                                .clickable(enabled = state.canUndo, onClick = onUndo)
                                .testTag("editor_undo_button"),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.Undo,
                                contentDescription = "Undo",
                                tint = if (state.canUndo) Color.White else Color(0xFF505664),
                                modifier = Modifier.size(17.dp)
                            )
                        }

                        Box(
                            modifier = Modifier
                                .size(30.dp)
                                .clip(RoundedCornerShape(6.dp))
                                .clickable(enabled = state.canRedo, onClick = onRedo)
                                .testTag("editor_redo_button"),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.Redo,
                                contentDescription = "Redo",
                                tint = if (state.canRedo) Color.White else Color(0xFF505664),
                                modifier = Modifier.size(17.dp)
                            )
                        }
                    }
                }

                // Middle Timeline Action Strip: Copy | Paste | Duplicate | Group | Pre-render Layer/Group | FX/Solo Menu
                HorizontalDivider(thickness = 0.5.dp, color = TimelineHairline.copy(alpha = 0.7f))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(32.dp)
                        .background(TimelineToolbarSubBg)
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    MiddleToolbarActionPill(
                        icon = Icons.Default.ContentCopy,
                        label = "Copy",
                        enabled = selectedLayer != null,
                        onClick = onCopyLayer,
                        testTag = "timeline_copy_button"
                    )
                    MiddleToolbarActionPill(
                        icon = Icons.Default.ContentPaste,
                        label = "Paste",
                        enabled = state.hasCopiedLayer,
                        highlighted = state.hasCopiedLayer,
                        onClick = onPasteLayer,
                        testTag = "timeline_paste_button"
                    )
                    MiddleToolbarActionPill(
                        icon = Icons.Default.FilterNone,
                        label = "Duplicate",
                        enabled = selectedLayer != null,
                        onClick = onDuplicateLayer,
                        testTag = "duplicate_layer_button"
                    )
                    MiddleToolbarActionPill(
                        icon = Icons.Default.Folder,
                        label = if (multiSelectCount > 1) "Group ($multiSelectCount)" else "Group",
                        enabled = selectedLayer != null || multiSelectCount > 0,
                        highlighted = multiSelectCount > 1,
                        accentColor = Color(0xFFA78BFA),
                        onClick = onGroupSelectedLayers,
                        testTag = "timeline_group_button"
                    )
                    MiddleToolbarActionPill(
                        icon = Icons.Default.Bolt,
                        label = when {
                            selectedLayer?.type == LayerType.GROUP && isSelectedLayerOrGroupCached -> "Group Cached"
                            selectedLayer?.type == LayerType.GROUP -> "Pre-render Group"
                            isSelectedLayerOrGroupCached -> "Layer Cached"
                            else -> "Pre-render Layer"
                        },
                        enabled = selectedLayer != null,
                        highlighted = isSelectedLayerOrGroupCached,
                        accentColor = KeyflowBlueBright,
                        onClick = onPreRenderLayerOrGroup,
                        testTag = "timeline_prerender_layer_button"
                    )

                    Box {
                        MiddleToolbarActionPill(
                            icon = Icons.Default.MoreHoriz,
                            label = "FX / Solo",
                            enabled = selectedLayer != null || state.hasCopiedEffects,
                            highlighted = state.hasCopiedEffects || state.soloLayerId != null,
                            onClick = { showClipboardMenu = true },
                            testTag = "topbar_clipboard_button"
                        )

                        DropdownMenu(
                            expanded = showClipboardMenu,
                            onDismissRequest = { showClipboardMenu = false },
                            modifier = Modifier.widthIn(min = 156.dp, max = 196.dp)
                        ) {
                            CompactMenuRow(
                                label = "Copy Effects",
                                enabled = selectedLayer?.effects?.isNotEmpty() == true,
                                onClick = {
                                    onCopyEffects()
                                    showClipboardMenu = false
                                }
                            )
                            CompactMenuRow(
                                label = "Paste Effects",
                                enabled = state.hasCopiedEffects && selectedLayer != null,
                                onClick = {
                                    onPasteEffects()
                                    showClipboardMenu = false
                                }
                            )
                            if (selectedLayer != null) {
                                HorizontalDivider(color = OutlineSubtle)
                                CompactMenuRow(
                                    label = if (state.soloLayerId == selectedLayer.id) "Un-solo Layer" else "Solo Layer",
                                    onClick = {
                                        onToggleSoloLayer(selectedLayer.id)
                                        showClipboardMenu = false
                                    }
                                )
                                if (selectedLayer.type == LayerType.VIDEO) {
                                    CompactMenuRow(
                                        label = "Extract Audio",
                                        accent = AudioCyan,
                                        onClick = {
                                            onExtractAudioFromVideo()
                                            showClipboardMenu = false
                                        }
                                    )
                                }
                            }
                        }
                    }
                }
                HorizontalDivider(thickness = 0.7.dp, color = TimelineHairline)
            }

            // 3. BOTTOM HALF: FREELY MOVABLE ALIGHT MOTION TIMELINE + SINGLE PLAYHEAD
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(0.56f)
                    .background(TimelinePitchBlack)
            ) {
                val isSubInspectorOpen = selectedLayer != null &&
                    !showAddDrawer &&
                    state.inspectorTab != AmInspectorTab.TIMELINE &&
                    state.inspectorTab != AmInspectorTab.LAYER_OVERVIEW

                if (isSubInspectorOpen && selectedLayer != null) {
                    val selIdx = project.layers.indexOfFirst { it.id == selectedLayer.id }
                    Column(modifier = Modifier.fillMaxSize()) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(72.dp)
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
                                onToggleLayerMultiSelection = onToggleLayerMultiSelection,
                                onDeselectLayer = onDeselectLayer,
                                onMoveLayerTimeRange = onMoveLayerTimeRange,
                                onMoveLayerToIndex = onMoveLayerToIndex,
                                onToggleLayerVisibility = onToggleLayerVisibility,
                                onToggleGroupExpanded = onToggleGroupExpanded,
                                onToggleFloatingBeatReactor = { showFloatingBeatReactor = !showFloatingBeatReactor },
                                onNavigateTool = onNavigateTool,
                                onOpenAddDrawer = { setShowAddDrawer(true) }
                            )
                        }

                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth()
                        ) {
                            AmClickedLayerSheet(
                                activeTab = state.inspectorTab,
                                selectedLayer = selectedLayer,
                                allLayers = project.layers,
                                projectDurationMs = project.effectiveEditDurationMs(),
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
                                onShowMediaInfo = { showMediaInfoDialog = true },
                                onSetInspectorTab = onSetInspectorTab,
                                onCollapseToTimeline = onDeselectLayer,
                                onScrubPlayhead = onScrubPlayhead,
                                onSelectProperty = onSelectProperty,
                                onUpdatePropertyValue = onUpdatePropertyValue,
                                onUpdateExpression = onUpdateExpression,
                                onDragLayerOnCanvas = onDragLayerOnCanvas,
                                onResetTransform = onResetTransform,
                                onResetPropertyTarget = onResetPropertyTarget,
                                onResetMoveSubMode = onResetMoveSubMode,
                                onResetStroke = onResetStroke,
                                onResetShadow = onResetShadow,
                                onResetSingleEffectParam = onResetSingleEffectParam,
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
                                onPreRenderLayerOrGroup = onPreRenderLayerOrGroup,
                                activeMaskId = state.activeMaskId,
                                penMaskEditActive = state.penMaskEditActive,
                                rotoBrushActive = state.rotoBrushActive,
                                rotoTargetMode = state.rotoTargetMode,
                                rotoBrushRadiusDp = state.rotoBrushRadiusDp,
                                rotoStrokeCount = rotoStrokes.size,
                                isRotoPropagating = state.isRotoPropagating,
                                rotoPropagateProgress = state.rotoPropagateProgress,
                                showRotoModelDownloadDialog = state.showRotoModelDownloadDialog,
                                rotoModelState = rotoModelState,
                                onAddMask = onAddMask,
                                onSelectMask = onSelectMask,
                                onSetPenMaskEditMode = onSetPenMaskEditMode,
                                onUpdateMask = onUpdateMask,
                                onRemoveMask = onRemoveMask,
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
                                onCancelRotoPropagation = onCancelRotoPropagation,
                                onRelinkMissingMedia = onRelinkMissingMedia,
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
                                onToggleLayerMultiSelection = onToggleLayerMultiSelection,
                                onDeselectLayer = onDeselectLayer,
                                onMoveLayerTimeRange = onMoveLayerTimeRange,
                                onMoveLayerToIndex = onMoveLayerToIndex,
                                onToggleLayerVisibility = onToggleLayerVisibility,
                                onToggleGroupExpanded = onToggleGroupExpanded,
                                onToggleFloatingBeatReactor = { showFloatingBeatReactor = !showFloatingBeatReactor },
                                onNavigateTool = onNavigateTool,
                                onOpenAddDrawer = { setShowAddDrawer(true) }
                            )
                        }

                        KeyflowSelectedLayerBottomDock(
                            selectedLayer = selectedLayer,
                            onCollapseToTimeline = onDeselectLayer,
                            onTrimStart = onTrimStart,
                            onPullStart = onPullStart,
                            onSplitLayer = onSplitLayer,
                            onTrimEnd = onTrimEnd,
                            onPullEnd = onPullEnd,
                            onDeleteLayer = onDeleteSelectedLayer,
                            onToggleMask = { onSetInspectorTab(AmInspectorTab.MASK_ROTO) },
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
                        onToggleLayerMultiSelection = onToggleLayerMultiSelection,
                        onDeselectLayer = onDeselectLayer,
                        onMoveLayerTimeRange = onMoveLayerTimeRange,
                        onMoveLayerToIndex = onMoveLayerToIndex,
                        onToggleLayerVisibility = onToggleLayerVisibility,
                        onToggleGroupExpanded = onToggleGroupExpanded,
                        onToggleFloatingBeatReactor = { showFloatingBeatReactor = !showFloatingBeatReactor },
                        onNavigateTool = onNavigateTool,
                        onOpenAddDrawer = { setShowAddDrawer(true) }
                    )
                }

                androidx.compose.animation.AnimatedVisibility(
                    visible = showAddDrawer,
                    enter = fadeIn() + slideInVertically { it / 2 },
                    exit = fadeOut() + slideOutVertically { it / 2 }
                ) {
                    AmAddElementBottomSheet(
                        hasCopiedLayer = state.hasCopiedLayer,
                        pinnedAePlugins = pinnedAePlugins,
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
                        onImportUniversalFile = {
                            universalImportPicker.launch(arrayOf("*/*"))
                        },
                        onPasteLayer = onPasteLayer,
                        onAddBeatBookmark = onToggleBeatBookmark,
                        onAddAdjustmentLayer = onAddAdjustmentLayer,
                        onOpenAePlugin = onOpenAePlugin,
                        onNavigateTool = onNavigateTool,
                        onDismiss = { setShowAddDrawer(false) }
                    )
                }
            }
        }

        // Non-modal floating AE / ScriptUI plugin panel overlay (timeline & inspector stay interactive underneath!)
        if (activeFloatingPlugin != null && pluginHostRepository != null) {
            PluginFloatingWindowHost(
                plugin = activeFloatingPlugin,
                pluginRepository = pluginHostRepository,
                asBottomSheet = state.activeFloatingPluginAsSheet,
                onStatusMessage = onStatusMessage,
                onClose = onCloseFloatingPlugin
            )
        }
        }
    }
}

@Composable
private fun MiddleToolbarActionPill(
    icon: ImageVector,
    label: String,
    enabled: Boolean,
    highlighted: Boolean = false,
    accentColor: Color = KeyflowBlueBright,
    onClick: () -> Unit,
    testTag: String
) {
    val bgColor = when {
        !enabled -> Color(0xFF0E121B).copy(alpha = 0.55f)
        highlighted -> accentColor.copy(alpha = 0.20f)
        else -> Color(0xFF121723)
    }
    val borderColor = when {
        !enabled -> Color(0xFF1C2332)
        highlighted -> accentColor.copy(alpha = 0.85f)
        else -> Color(0xFF273145)
    }
    val contentTint = when {
        !enabled -> Color(0xFF535D70)
        highlighted -> accentColor
        else -> Color(0xFFE4E9F5)
    }

    Row(
        modifier = Modifier
            .height(24.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(bgColor)
            .border(0.8.dp, borderColor, RoundedCornerShape(6.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .testTag(testTag)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = contentTint,
            modifier = Modifier.size(13.dp)
        )
        Text(
            text = label,
            fontSize = 10.5.sp,
            fontWeight = if (highlighted) FontWeight.Bold else FontWeight.Medium,
            color = contentTint,
            maxLines = 1
        )
    }
}

@Composable
private fun CompactMenuRow(
    label: String,
    enabled: Boolean = true,
    accent: Color? = null,
    onClick: () -> Unit
) {
    DropdownMenuItem(
        text = {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = if (accent != null) FontWeight.Bold else FontWeight.Medium,
                color = when {
                    !enabled -> TextSecondary.copy(alpha = 0.45f)
                    accent != null -> accent
                    else -> TextPrimary
                }
            )
        },
        enabled = enabled,
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 2.dp),
        modifier = Modifier.height(34.dp),
        onClick = onClick
    )
}

@Composable
private fun KeyflowMediaInfoDialog(
    layer: Layer,
    project: Project,
    onDismiss: () -> Unit
) {
    val activeSpanMs = (layer.endTimeMs - layer.startTimeMs).coerceAtLeast(0L)
    val resolutionLabel = if (layer.mediaWidth > 0 && layer.mediaHeight > 0) {
        "${layer.mediaWidth} × ${layer.mediaHeight} px"
    } else {
        "${project.width} × ${project.height} px (Comp)"
    }
    val sourceUriName = layer.mediaUri.substringAfterLast('/').ifBlank { "Procedural / Internal Layer" }
    val speedLabel = buildString {
        append(String.format("%.2fx", layer.speedMultiplier))
        if (layer.reversed) append(" · Reversed")
        if (layer.timeRemapEnabled) append(" · Time Remapped")
        if (layer.freezeFrameMs != null) append(" · Frozen")
    }
    val audioInfo = when (layer.type) {
        LayerType.VIDEO, LayerType.AUDIO -> {
            if (layer.audioMuted) "Muted (${(layer.audioVolume * 100).toInt()}%)"
            else "${(layer.audioVolume * 100).toInt()}% · Preset: ${layer.audioPreset}"
        }
        else -> "No Audio Track"
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = SurfaceDark,
        title = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(Icons.Default.Info, contentDescription = null, tint = KeyflowBlueBright, modifier = Modifier.size(20.dp))
                Text("Media & Layer Info", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = TextPrimary)
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                MediaInfoLine("Layer Name", layer.name)
                MediaInfoLine("Type", layer.type.label)
                MediaInfoLine("Source", sourceUriName)
                MediaInfoLine("Resolution", resolutionLabel)
                MediaInfoLine("Frame Rate", "${project.fps} FPS")
                if (layer.sourceDurationMs > 0L) {
                    MediaInfoLine("Source Length", String.format("%.2fs (%d ms)", layer.sourceDurationMs / 1000f, layer.sourceDurationMs))
                }
                MediaInfoLine(
                    "Timeline Span",
                    "${String.format("%.2fs", layer.startTimeMs / 1000f)} → ${String.format("%.2fs", layer.endTimeMs / 1000f)} (${String.format("%.2fs", activeSpanMs / 1000f)})"
                )
                if (layer.sourceOffsetMs > 0L) {
                    MediaInfoLine("Trim In Offset", "${layer.sourceOffsetMs} ms")
                }
                MediaInfoLine("Speed / Timing", speedLabel)
                MediaInfoLine("Audio", audioInfo)
                MediaInfoLine("Effects / Keyframes", "${layer.effects.size} FX · ${layer.allKeyframeTimes().size} Keyframes")
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Done", color = KeyflowBlueBright, fontWeight = FontWeight.Bold)
            }
        }
    )
}

@Composable
private fun MediaInfoLine(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = TextSecondary)
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            color = TextPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
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
    onClearBeats: () -> Unit,
    onBindAudioToProperty: (AudioBand, Float) -> Unit,
    onBakeBeatsToKeyframes: (PropertyTarget, Float, Long) -> Unit = { _, _, _ -> },
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
                    Icon(Icons.Default.GraphicEq, contentDescription = null, tint = KeyflowBlueBright, modifier = Modifier.size(16.dp))
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
                        Text("Studio", style = MaterialTheme.typography.labelSmall, color = KeyflowBlueBright, fontWeight = FontWeight.SemiBold)
                        Icon(Icons.Default.OpenInNew, contentDescription = "Audio", tint = KeyflowBlueBright, modifier = Modifier.size(11.dp))
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
                Text("Bass", style = MaterialTheme.typography.labelSmall, color = KeyflowBlueBright, fontWeight = FontWeight.Bold)
                LinearProgressIndicator(
                    progress = { bassLevel.coerceIn(0f, 1f) },
                    color = KeyflowBlueBright,
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
                    colors = SliderDefaults.colors(thumbColor = KeyflowBlueBright, activeTrackColor = KeyflowBlue),
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
                        .background(Brush.linearGradient(listOf(KeyflowBlueBright, KeyflowBlue)))
                        .clickable { onAutoDetectBass(localSens) }
                        .testTag("floating_auto_detect_bass_button")
                        .padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    Icon(Icons.Default.Bolt, contentDescription = null, tint = DeepBlack, modifier = Modifier.size(14.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "Detect Beats",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = DeepBlack
                    )
                }

                if (beatCount > 0 && selectedLayer != null) {
                    Row(
                        modifier = Modifier
                            .height(32.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(KeyflowBlue.copy(alpha = 0.20f))
                            .border(0.8.dp, KeyflowBlue, RoundedCornerShape(8.dp))
                            .clickable {
                                val pulse = when (selectedProperty) {
                                    PropertyTarget.SCALE -> 18f
                                    PropertyTarget.OPACITY -> -25f
                                    PropertyTarget.ROTATION_Z -> 12f
                                    else -> 28f
                                }
                                onBakeBeatsToKeyframes(selectedProperty, pulse, 180L)
                            }
                            .testTag("floating_bake_beat_keyframes_button")
                            .padding(horizontal = 9.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.Diamond, contentDescription = null, tint = KeyframeAmber, modifier = Modifier.size(12.dp))
                        Spacer(modifier = Modifier.width(3.dp))
                        Text("Bake KF", style = MaterialTheme.typography.labelSmall, color = KeyflowBlueBright, fontWeight = FontWeight.Bold)
                    }
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
                            .padding(horizontal = 8.dp),
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

private val TIMELINE_LEFT_GUTTER = 46.dp
private val TIMELINE_RIGHT_GUTTER = 34.dp
private val TIMELINE_ROW_HEIGHT = 34.dp
private val TIMELINE_RULER_HEIGHT = 28.dp

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
                Canvas(modifier = Modifier.size(20.dp)) {
                    val c = KeyflowBlueBright
                    val cy = size.height * 0.5f
                    val cx = size.width * 0.5f
                    val sw = 1.8.dp.toPx()
                    drawLine(c, Offset(cx, 2.dp.toPx()), Offset(cx, size.height - 2.dp.toPx()), strokeWidth = sw)
                    drawLine(c, Offset(2.dp.toPx(), cy), Offset(cx - 2.5.dp.toPx(), cy), strokeWidth = sw)
                    drawLine(c, Offset(cx - 5.5.dp.toPx(), cy - 3.2.dp.toPx()), Offset(cx - 2.2.dp.toPx(), cy), strokeWidth = sw)
                    drawLine(c, Offset(cx - 5.5.dp.toPx(), cy + 3.2.dp.toPx()), Offset(cx - 2.2.dp.toPx(), cy), strokeWidth = sw)
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

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(54.dp)
                .background(KeyflowDrawerBg),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .width(42.dp)
                    .fillMaxHeight()
                    .clickable(onClick = onCollapseToTimeline)
                    .testTag("collapse_layer_sheet_button"),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                    contentDescription = "Deselect Layer",
                    tint = KeyflowBlueBright,
                    modifier = Modifier.size(23.dp)
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
                    label = "Move",
                    testTag = "tile_move_transform",
                    onClick = { onOpenInspectorTab(AmInspectorTab.MOVE_TRANSFORM) }
                ) {
                    Icon(Icons.Default.OpenWith, contentDescription = null, tint = Color.White, modifier = Modifier.size(19.dp))
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
                        modifier = Modifier.size(19.dp)
                    )
                }

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
                    Icon(Icons.Default.Edit, contentDescription = null, tint = Color.White, modifier = Modifier.size(19.dp))
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
                        Icon(Icons.Default.Palette, contentDescription = null, tint = Color.White, modifier = Modifier.size(19.dp))
                    }
                }

                if (selectedLayer.type != LayerType.AUDIO &&
                    selectedLayer.type != LayerType.CAMERA &&
                    selectedLayer.type != LayerType.NULL_OBJECT &&
                    selectedLayer.type != LayerType.GROUP
                ) {
                    KeyflowBottomNavTab(
                        label = "Border",
                        active = selectedLayer.strokeEnabled || selectedLayer.shadowEnabled,
                        testTag = "tile_border_shadow",
                        onClick = { onOpenInspectorTab(AmInspectorTab.BORDER_SHADOW) }
                    ) {
                        Icon(
                            imageVector = Icons.Default.BorderStyle,
                            contentDescription = null,
                            tint = if (selectedLayer.strokeEnabled || selectedLayer.shadowEnabled) KeyflowBlueBright else Color.White,
                            modifier = Modifier.size(19.dp)
                        )
                    }
                }

                KeyflowBottomNavTab(
                    label = "Blending",
                    testTag = "tile_blending_opacity",
                    onClick = { onOpenInspectorTab(AmInspectorTab.BLENDING_OPACITY) }
                ) {
                    Canvas(modifier = Modifier.size(19.dp)) {
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
                        modifier = Modifier.size(19.dp)
                    )
                }

                if (selectedLayer.type != LayerType.AUDIO && selectedLayer.type != LayerType.CAMERA) {
                    val hasMaskActive = selectedLayer.masks.isNotEmpty() || selectedLayer.isClippingMask
                    KeyflowBottomNavTab(
                        label = "Mask",
                        active = hasMaskActive,
                        testTag = "tile_mask_toggle",
                        onClick = onToggleMask
                    ) {
                        val tint = if (hasMaskActive) KeyflowBlueBright else Color.White
                        Canvas(modifier = Modifier.size(19.dp)) {
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
                }

                KeyflowBottomNavTab(
                    label = "Plugins",
                    active = false,
                    testTag = "tile_plugins",
                    onClick = { onOpenInspectorTab(AmInspectorTab.EFFECTS) }
                ) {
                    Icon(
                        imageVector = Icons.Default.Extension,
                        contentDescription = "After Effects Plugins",
                        tint = Color.White,
                        modifier = Modifier.size(19.dp)
                    )
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

/**
 * Alight Motion / KeyFlow Freely Movable 2D Multitrack Timeline:
 * - ONE SINGLE FIXED VERTICAL PLAYHEAD LINE anchored dead-center horizontally, with zero duplicate lines
 *   regardless of how many layers exist or are added.
 * - Full-surface horizontal pan + momentum fling (`Orientation.Horizontal`), vertical layer scrolling
 *   (`LazyColumn`), 2-finger pinch-to-zoom (`0.25x .. 8.0x`), tap-on-ruler to scrub, tap-on-clip to
 *   select WITHOUT spawning or interrupting playback, multi-select checkboxes for Grouping, and hold-to-drag
 *   clip timing & vertical stack reordering.
 */
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
    onToggleLayerMultiSelection: (String) -> Unit = {},
    onDeselectLayer: () -> Unit = {},
    onMoveLayerTimeRange: (String, Long, Boolean) -> Unit,
    onMoveLayerToIndex: (String, Int) -> Unit = { _, _ -> },
    onToggleLayerVisibility: (String) -> Unit,
    onToggleGroupExpanded: (String) -> Unit = {},
    onToggleFloatingBeatReactor: () -> Unit,
    onNavigateTool: (StudioDestination) -> Unit,
    onOpenAddDrawer: () -> Unit
) {
    val project = state.activeProject
    val density = LocalDensity.current
    val currentZoomState = rememberUpdatedState(state.timelineZoom)
    val onZoomUpdated = rememberUpdatedState(onSetTimelineZoom)
    val latestPlayheadMs by rememberUpdatedState(state.playheadMs)
    val latestScrubCallback by rememberUpdatedState(onScrubPlayhead)
    val isLayerSelectedMode = state.selectedLayerId != null
    val activePlayheadColor = if (isLayerSelectedMode) KeyflowBlueBright else KeyflowBlue
    val verticalListState = rememberLazyListState()

    var isAnyClipBeingDragged by remember { mutableStateOf(false) }
    var panFractionMsAccum by remember { mutableFloatStateOf(0f) }

    val rulerLabelPaint = remember(density) {
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.rgb(176, 184, 198)
            textSize = with(density) { 10.sp.toPx() }
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
        }
    }
    val playheadPillTextPaint = remember(density) {
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.rgb(6, 10, 22)
            textSize = with(density) { 10.sp.toPx() }
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
        }
    }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .clipToBounds()
            .background(TimelinePitchBlack)
            .pointerInput(Unit) {
                awaitEachGesture {
                    do {
                        val event = awaitPointerEvent()
                        val pressedCount = event.changes.count { it.pressed }
                        if (pressedCount >= 2) {
                            val zoomFactor = event.calculateZoom()
                            if (zoomFactor.isFinite() && abs(zoomFactor - 1f) > 0.004f) {
                                val nextZoom = (currentZoomState.value * zoomFactor).coerceIn(0.25f, 8.0f)
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
        val leftGutterPx = with(density) { TIMELINE_LEFT_GUTTER.toPx() }
        val viewportLaneWidthDp = (maxWidth - TIMELINE_LEFT_GUTTER - TIMELINE_RIGHT_GUTTER).coerceAtLeast(120.dp)
        val viewportLaneWidthPx = with(density) { viewportLaneWidthDp.toPx().coerceAtLeast(120f) }
        val zoom = state.timelineZoom.coerceIn(0.25f, 8.0f)
        // At 1.0x zoom, 6 seconds span across the timeline lane; zooming smoothly scales pxPerSecond around center playhead.
        val pxPerSecond = (viewportLaneWidthPx / 6.0f) * zoom
        val latestPxPerSec by rememberUpdatedState(pxPerSecond)
        val centerXInLane = viewportLaneWidthPx * 0.5f

        val editEndMs = remember(project) { project.effectiveEditDurationMs() }
        val timecodeShort = remember(state.playheadMs, project.fps) {
            AnimationEvaluator.formatSmpteTimecode(state.playheadMs, project.fps).drop(3)
        }

        // Native 60fps horizontal pan + inertia fling across the ENTIRE timeline surface (ruler, tracks, and empty canvas)
        val horizontalPanState = rememberScrollableState { deltaPx ->
            if (isAnyClipBeingDragged) {
                0f
            } else {
                val pps = latestPxPerSec.coerceAtLeast(8f)
                val deltaMsFloat = -(deltaPx / pps) * 1000f + panFractionMsAccum
                val wholeMs = deltaMsFloat.toLong()
                panFractionMsAccum = deltaMsFloat - wholeMs
                if (wholeMs != 0L) {
                    val targetMs = (latestPlayheadMs + wholeMs).coerceAtLeast(0L)
                    latestScrubCallback(targetMs)
                }
                deltaPx
            }
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .scrollable(
                    state = horizontalPanState,
                    orientation = Orientation.Horizontal,
                    enabled = !isAnyClipBeingDragged,
                    flingBehavior = ScrollableDefaults.flingBehavior()
                )
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                // A. TIME RULER STRIP (Pans & Scrubs relative to Center Fixed Playhead, zero duplicate playhead line here!)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(TIMELINE_RULER_HEIGHT)
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
                                    state.timelineZoom < 1.5f -> 2.0f
                                    state.timelineZoom < 3.0f -> 4.0f
                                    else -> 0.5f
                                }
                                onSetTimelineZoom(nextZoom)
                            }
                            .testTag("timeline_zoom_badge"),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.ZoomIn,
                            contentDescription = "Zoom Timeline",
                            tint = KeyflowBlueBright,
                            modifier = Modifier.size(14.dp)
                        )
                    }

                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .clipToBounds()
                            .pointerInput(viewportLaneWidthPx) {
                                detectTapGestures { offset ->
                                    val pps = latestPxPerSec.coerceAtLeast(8f)
                                    val tappedMs = (latestPlayheadMs + (((offset.x - viewportLaneWidthPx * 0.5f) / pps) * 1000f).roundToLong())
                                        .coerceAtLeast(0L)
                                    latestScrubCallback(tappedMs)
                                }
                            }
                            .testTag("timeline_ruler_scrubber")
                    ) {
                        Canvas(modifier = Modifier.fillMaxSize()) {
                            val w = size.width
                            val h = size.height
                            val cx = w * 0.5f
                            val curSec = state.playheadMs / 1000f

                            fun secToX(sec: Float): Float = cx + (sec - curSec) * pxPerSecond

                            // Pre-rendered span highlights along the top edge of the ruler
                            val editEndSec = editEndMs / 1000f
                            cachedSpanFractions.forEach { (startF, endF) ->
                                val sx = secToX(startF.coerceIn(0f, 1f) * editEndSec).coerceIn(0f, w)
                                val ex = secToX(endF.coerceIn(0f, 1f) * editEndSec).coerceIn(0f, w)
                                if (ex > sx) {
                                    drawRect(
                                        color = KeyflowBlueBright,
                                        topLeft = Offset(sx, 0f),
                                        size = Size((ex - sx).coerceAtLeast(3f), 2.5.dp.toPx())
                                    )
                                }
                            }

                            // Subtle end-of-edit boundary marker
                            if (project.layers.isNotEmpty()) {
                                val editEndX = secToX(editEndSec)
                                if (editEndX in -2f..(w + 2f)) {
                                    drawLine(
                                        color = KeyflowBlue.copy(alpha = 0.65f),
                                        start = Offset(editEndX, 0f),
                                        end = Offset(editEndX, h),
                                        strokeWidth = 1.4.dp.toPx()
                                    )
                                }
                            }

                            drawLine(
                                color = Color(0xFF1A202D),
                                start = Offset(0f, h - 1f),
                                end = Offset(w, h - 1f),
                                strokeWidth = 1f
                            )

                            val secStep = when {
                                pxPerSecond >= 46f -> 1
                                pxPerSecond >= 22f -> 2
                                pxPerSecond >= 10f -> 5
                                else -> 10
                            }
                            val firstVisibleSec = max(0, (floor((curSec - cx / pxPerSecond) / secStep).toInt() - 1) * secStep)
                            val lastVisibleSec = max(firstVisibleSec + secStep, (floor((curSec + (w - cx) / pxPerSecond) / secStep).toInt() + 2) * secStep)
                            val subTicks = 10

                            var s = firstVisibleSec
                            while (s <= lastVisibleSec) {
                                val secX = secToX(s.toFloat())
                                if (secX in -24f..(w + 24f)) {
                                    drawLine(
                                        color = Color(0xFF4B5568),
                                        start = Offset(secX, h * 0.56f),
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
                                for (sub in 1 until subTicks) {
                                    val subSec = s + (sub.toFloat() / subTicks) * secStep
                                    val tx = secToX(subSec)
                                    if (tx in 0f..w) {
                                        val isHalf = sub == subTicks / 2
                                        drawLine(
                                            color = if (isHalf) Color(0xFF343B4A) else Color(0xFF212632),
                                            start = Offset(tx, if (isHalf) h * 0.70f else h * 0.80f),
                                            end = Offset(tx, h),
                                            strokeWidth = 1f
                                        )
                                    }
                                }
                                s += secStep
                            }

                            // Sky-blue beat bookmark pins
                            project.beatMarkers.forEach { bm ->
                                val bx = secToX(bm.timeMs / 1000f)
                                if (bx in -8f..(w + 8f)) {
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
                            }
                        }
                    }

                    Spacer(modifier = Modifier.width(TIMELINE_RIGHT_GUTTER))
                }

                // B. FREELY SCROLLABLE & CLICKABLE LAYER STACK + INTERACTIVE BACKGROUND CANVAS
                // Hide descendants of any collapsed LayerType.GROUP (!groupExpanded) while preserving render/export (Section 3.3)
                val visibleLayers = remember(project.layers, state.selectedLayerId, compactSelectedOnly) {
                    val byId = project.layers.associateBy { it.id }
                    fun isHiddenByCollapsedAncestor(layer: Layer): Boolean {
                        var cursorId = layer.parentId
                        val visited = HashSet<String>()
                        while (!cursorId.isNullOrBlank() && visited.add(cursorId)) {
                            val parent = byId[cursorId] ?: break
                            if (parent.type == LayerType.GROUP && !parent.groupExpanded) {
                                return true
                            }
                            cursorId = parent.parentId
                        }
                        return false
                    }
                    val rev = project.layers.asReversed().filterNot { isHiddenByCollapsedAncestor(it) }
                    if (compactSelectedOnly && state.selectedLayerId != null) {
                        rev.filter { it.id == state.selectedLayerId }.ifEmpty { rev.take(1) }
                    } else {
                        rev
                    }
                }

                LazyColumn(
                    state = verticalListState,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .background(TimelinePitchBlack),
                    contentPadding = PaddingValues(
                        top = 4.dp,
                        bottom = if (showFloatingFabs) 74.dp else 10.dp
                    ),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    if (visibleLayers.isEmpty()) {
                        item(key = "empty_timeline_canvas") {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(170.dp)
                                    .pointerInput(viewportLaneWidthPx) {
                                        detectTapGestures { offset ->
                                            val laneX = offset.x - leftGutterPx
                                            if (laneX in 0f..viewportLaneWidthPx) {
                                                val pps = latestPxPerSec.coerceAtLeast(8f)
                                                val tappedMs = (latestPlayheadMs + (((laneX - viewportLaneWidthPx * 0.5f) / pps) * 1000f).roundToLong())
                                                    .coerceAtLeast(0L)
                                                latestScrubCallback(tappedMs)
                                            }
                                        }
                                    },
                                contentAlignment = Alignment.Center
                            ) {
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Layers,
                                        contentDescription = null,
                                        tint = Color(0xFF3E485C),
                                        modifier = Modifier.size(26.dp)
                                    )
                                    Text(
                                        text = "Tap + to add media, text, shapes, or import .aep / .xml",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = TextSecondary
                                    )
                                }
                            }
                        }
                    } else {
                        itemsIndexed(visibleLayers, key = { _, l -> l.id }) { _, layer ->
                            val isSelected = layer.id == state.selectedLayerId
                            val isMultiChecked = state.selectedLayerIds.contains(layer.id)
                            val actualLayerIdx = project.layers.indexOfFirst { it.id == layer.id }
                            AmTimelineLayerRow(
                                layer = layer,
                                layerIndexInProject = actualLayerIdx,
                                totalLayerCount = project.layers.size,
                                isSelected = isSelected,
                                isMultiChecked = isMultiChecked,
                                playheadMs = state.playheadMs,
                                pxPerSecond = pxPerSecond,
                                onDragActiveChange = { active -> isAnyClipBeingDragged = active },
                                onClickLayer = { onSelectLayer(layer.id) },
                                onToggleMultiSelect = { onToggleLayerMultiSelection(layer.id) },
                                onClickEmptyLane = { tappedMs ->
                                    if (isLayerSelectedMode) {
                                        onDeselectLayer()
                                    } else {
                                        onScrubPlayhead(tappedMs)
                                    }
                                },
                                onMoveLayerTimeRange = { newStart, recordUndo ->
                                    onMoveLayerTimeRange(layer.id, newStart, recordUndo)
                                },
                                onMoveLayerToIndex = { targetIdx ->
                                    onMoveLayerToIndex(layer.id, targetIdx)
                                },
                                onToggleVisibility = { onToggleLayerVisibility(layer.id) },
                                onToggleGroupExpanded = { onToggleGroupExpanded(layer.id) }
                            )
                        }

                        // Extra free clickable/scrollable canvas tail so user can pan/scroll freely below tracks
                        if (!compactSelectedOnly) {
                            item(key = "timeline_free_tail_space") {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(96.dp)
                                        .pointerInput(viewportLaneWidthPx, isLayerSelectedMode) {
                                            detectTapGestures { offset ->
                                                if (isLayerSelectedMode) {
                                                    onDeselectLayer()
                                                } else {
                                                    val laneX = offset.x - leftGutterPx
                                                    if (laneX in 0f..viewportLaneWidthPx) {
                                                        val pps = latestPxPerSec.coerceAtLeast(8f)
                                                        val tappedMs = (latestPlayheadMs + (((laneX - viewportLaneWidthPx * 0.5f) / pps) * 1000f).roundToLong())
                                                            .coerceAtLeast(0L)
                                                        latestScrubCallback(tappedMs)
                                                    }
                                                }
                                            }
                                        }
                                )
                            }
                        }
                    }
                }
            }

            // C. SINGLE SHORT FIXED-LENGTH PLAYHEAD OVERLAY (Zero Duplicate Lines!)
            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(34.dp)
                    .align(Alignment.TopCenter)
                    .testTag("single_fixed_timeline_playhead")
            ) {
                val fixedPlayheadX = leftGutterPx + centerXInLane
                val pillW = 56.dp.toPx()
                val pillH = 16.5.dp.toPx()
                val pillLeft = (fixedPlayheadX - pillW * 0.5f).coerceIn(2f, (size.width - pillW - 2f).coerceAtLeast(2f))
                val pillTop = 3.5.dp.toPx()
                val stemStartY = pillTop + pillH
                // Ultra-short compact playhead needle stem (6.dp clean span right under timecode pill)
                val stemEndY = (stemStartY + 6.dp.toPx()).coerceAtMost(size.height - 2.dp.toPx())

                drawLine(
                    color = activePlayheadColor,
                    start = Offset(fixedPlayheadX, stemStartY),
                    end = Offset(fixedPlayheadX, stemEndY),
                    strokeWidth = 1.8.dp.toPx(),
                    cap = androidx.compose.ui.graphics.StrokeCap.Round
                )

                // Top timecode badge centered on the single fixed playhead
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

            // D. FLOATING STUDIO TOOL PILL (Bottom-Left: Effects, Graph, Audio — no duplicates of FAB!) + '+' FAB (Bottom-Right)
            if (showFloatingFabs && !isAddDrawerOpen) {
                Row(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .navigationBarsPadding()
                        .padding(start = 10.dp, bottom = 8.dp)
                        .height(36.dp)
                        .shadow(6.dp, CircleShape)
                        .clip(CircleShape)
                        .background(Color(0xEB0E121A))
                        .border(1.dp, TimelineHairline, CircleShape)
                        .padding(horizontal = 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    AmMiniStudioIcon(
                        icon = Icons.Default.AutoFixHigh,
                        contentDescription = "Effects Library",
                        onClick = { onNavigateTool(StudioDestination.EFFECTS_BROWSER) },
                        testTag = "tool_effects_button"
                    )
                    AmMiniStudioIcon(
                        icon = Icons.AutoMirrored.Filled.ShowChart,
                        contentDescription = "Graph Editor",
                        onClick = { onNavigateTool(StudioDestination.GRAPH_EDITOR) },
                        testTag = "tool_graph_button"
                    )
                    AmMiniStudioIcon(
                        icon = Icons.Default.GraphicEq,
                        contentDescription = "Beat Reactor",
                        active = isBeatPopoverOpen,
                        onClick = onToggleFloatingBeatReactor,
                        testTag = "tool_audio_button"
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
                            .background(Brush.linearGradient(listOf(KeyflowBlueBright, KeyflowBlue))),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Add,
                            contentDescription = "Add Element",
                            tint = DeepBlack,
                            modifier = Modifier.size(21.dp)
                        )
                    }
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
            tint = if (active) KeyflowBlueBright else Color(0xFFD0DCFF),
            modifier = Modifier.size(16.dp)
        )
    }
}

@Composable
private fun AmTimelineLayerRow(
    layer: Layer,
    layerIndexInProject: Int,
    totalLayerCount: Int,
    isSelected: Boolean,
    isMultiChecked: Boolean,
    playheadMs: Long,
    pxPerSecond: Float,
    onDragActiveChange: (Boolean) -> Unit,
    onClickLayer: () -> Unit,
    onToggleMultiSelect: () -> Unit,
    onClickEmptyLane: (Long) -> Unit,
    onMoveLayerTimeRange: (rawNewStartMs: Long, recordUndo: Boolean) -> Unit,
    onMoveLayerToIndex: (Int) -> Unit,
    onToggleVisibility: () -> Unit,
    onToggleGroupExpanded: () -> Unit = {}
) {
    val density = LocalDensity.current
    val clipFillColor = remember(layer.colorHex, layer.type) {
        when (layer.type) {
            LayerType.GROUP -> ClipGroupPurple
            LayerType.IMAGE, LayerType.VIDEO -> ClipMediaBlue
            LayerType.NULL_OBJECT, LayerType.CAMERA -> ClipRigRed
            LayerType.AUDIO -> AudioCyan
            LayerType.DRAWING -> Color(0xFF14B8A6)
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

    var isHoldingClip by remember { mutableStateOf(false) }
    var isDraggingHandle by remember { mutableStateOf(false) }
    var dragOriginStartMs by remember { mutableLongStateOf(0L) }
    var dragAccumPx by remember { mutableFloatStateOf(0f) }
    var dragAccumY by remember { mutableFloatStateOf(0f) }

    val latestLayer by rememberUpdatedState(layer)
    val latestLayerIdx by rememberUpdatedState(layerIndexInProject)
    val latestTotalLayers by rememberUpdatedState(totalLayerCount)
    val latestPlayheadMs by rememberUpdatedState(playheadMs)
    val latestPxPerSec by rememberUpdatedState(pxPerSecond)
    val latestMoveCallback by rememberUpdatedState(onMoveLayerTimeRange)
    val latestReorderCallback by rememberUpdatedState(onMoveLayerToIndex)
    val latestClickLayer by rememberUpdatedState(onClickLayer)
    val latestToggleMulti by rememberUpdatedState(onToggleMultiSelect)
    val latestClickEmpty by rememberUpdatedState(onClickEmptyLane)
    val latestToggleGroupExpanded by rememberUpdatedState(onToggleGroupExpanded)
    val latestDragActiveChange by rememberUpdatedState(onDragActiveChange)
    val rowHeightPx = with(density) { TIMELINE_ROW_HEIGHT.toPx().coerceAtLeast(24f) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(TIMELINE_ROW_HEIGHT)
            .background(
                when {
                    isHoldingClip || isDraggingHandle -> Color(0xFF141D2E)
                    isSelected -> Color(0xFF0A1120)
                    isMultiChecked -> Color(0xFF120E24)
                    else -> TimelinePitchBlack
                }
            ),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Left Gutter: [Visibility Eye] + [Multi-select Checkbox Dot for Grouping]
        Row(
            modifier = Modifier
                .width(TIMELINE_LEFT_GUTTER)
                .fillMaxHeight()
                .padding(horizontal = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            Box(
                modifier = Modifier
                    .size(21.dp)
                    .clip(CircleShape)
                    .clickable(onClick = onToggleVisibility),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = if (layer.visible) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                    contentDescription = "Visibility",
                    tint = if (layer.visible) Color(0xFFD5DCE8) else Color(0xFF3E4452),
                    modifier = Modifier.size(14.dp)
                )
            }
            Box(
                modifier = Modifier
                    .size(17.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(
                        if (isMultiChecked) Color(0xFF8B5CF6) else Color(0xFF121722)
                    )
                    .border(
                        width = 1.dp,
                        color = if (isMultiChecked) Color(0xFFC4B5FD) else Color(0xFF323C50),
                        shape = RoundedCornerShape(4.dp)
                    )
                    .clickable(onClick = latestToggleMulti)
                    .testTag("layer_multiselect_${layer.id}"),
                contentAlignment = Alignment.Center
            ) {
                if (isMultiChecked) {
                    Icon(
                        imageVector = Icons.Default.Check,
                        contentDescription = "Selected for Group",
                        tint = Color.White,
                        modifier = Modifier.size(11.dp)
                    )
                }
            }
        }

        // Center Timeline Track Lane (Positioned relative to the single center playhead; zero per-row playhead drawing!)
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .clipToBounds()
                .background(TimelinePitchBlack)
                .pointerInput(layer.id) {
                    detectTapGestures { offset ->
                        val curL = latestLayer
                        val laneW = size.width.toFloat().coerceAtLeast(1f)
                        val pps = latestPxPerSec.coerceAtLeast(8f)
                        val tappedMs = (latestPlayheadMs + (((offset.x - laneW * 0.5f) / pps) * 1000f).roundToLong())
                            .coerceAtLeast(0L)
                        // Check middle bottom-down chevron tap on Group layer bar (Section 3.3)
                        if (curL.type == LayerType.GROUP) {
                            val cx = laneW * 0.5f
                            val sX = cx + ((curL.startTimeMs - latestPlayheadMs) / 1000f) * pps
                            val eX = cx + ((curL.endTimeMs - latestPlayheadMs) / 1000f) * pps
                            val visLeft = sX.coerceIn(0f, laneW)
                            val visRight = eX.coerceIn(0f, laneW)
                            val chevronCenterX = (visLeft + visRight) * 0.5f
                            if (visRight > visLeft && abs(offset.x - chevronCenterX) <= 24.dp.toPx()) {
                                latestToggleGroupExpanded()
                                return@detectTapGestures
                            }
                        }
                        val earPaddingMs = ((14.dp.toPx() / pps) * 1000f).toLong()
                        if (tappedMs in (curL.startTimeMs - earPaddingMs)..curL.endTimeMs) {
                            // Clicking a layer ONLY selects/unclicks it — NEVER touches playback!
                            latestClickLayer()
                        } else {
                            latestClickEmpty(tappedMs)
                        }
                    }
                }
                .pointerInput(layer.id) {
                    detectDragGesturesAfterLongPress(
                        onDragStart = {
                            if (!latestLayer.locked) {
                                isHoldingClip = true
                                latestDragActiveChange(true)
                                dragOriginStartMs = latestLayer.startTimeMs
                                dragAccumPx = 0f
                                dragAccumY = 0f
                            }
                        },
                        onDrag = { change, dragAmount ->
                            if (isHoldingClip && !latestLayer.locked) {
                                change.consume()
                                val pps = latestPxPerSec.coerceAtLeast(8f)
                                dragAccumPx += dragAmount.x
                                val deltaMs = ((dragAccumPx / pps) * 1000f).roundToLong()
                                latestMoveCallback((dragOriginStartMs + deltaMs).coerceAtLeast(0L), false)

                                dragAccumY += dragAmount.y
                                if (abs(dragAccumY) >= rowHeightPx * 0.92f && latestTotalLayers > 1 && latestLayerIdx >= 0) {
                                    val stepsUp = (-dragAccumY / rowHeightPx).toInt()
                                    if (stepsUp != 0) {
                                        val nextIdx = (latestLayerIdx + stepsUp).coerceIn(0, latestTotalLayers - 1)
                                        if (nextIdx != latestLayerIdx) {
                                            latestReorderCallback(nextIdx)
                                        }
                                        dragAccumY = 0f
                                    }
                                }
                            }
                        },
                        onDragEnd = {
                            if (isHoldingClip) {
                                isHoldingClip = false
                                latestDragActiveChange(false)
                                latestMoveCallback(latestLayer.startTimeMs, true)
                            }
                        },
                        onDragCancel = {
                            isHoldingClip = false
                            latestDragActiveChange(false)
                        }
                    )
                }
                .testTag("layer_clip_lane_${layer.id}")
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val w = size.width
                val h = size.height
                val cx = w * 0.5f

                fun msToX(ms: Long): Float = cx + ((ms - playheadMs) / 1000f) * pxPerSecond

                val rawSX = msToX(layer.startTimeMs)
                val rawEX = msToX(layer.endTimeMs)
                val earW = if (isSelected) 11.dp.toPx() else 0f
                val minPillW = 6.dp.toPx()
                val rawClipW = (rawEX - rawSX).coerceAtLeast(minPillW)

                // Skip offscreen clips fast for 60fps large-project scrolling
                if (rawSX + rawClipW >= -earW - 8f && rawSX <= w + 8f) {
                    val sX = rawSX
                    val clipW = rawClipW
                    val barTop = if (layer.parentId != null) 4.dp.toPx() else 2.5.dp.toPx()
                    val barH = (h - barTop * 2f).coerceAtLeast(10f)
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
                        if (isSelected) {
                            val earLeft = sX - earW
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
                                isHoldingClip || isDraggingHandle -> KeyframeAmber.copy(alpha = 0.85f)
                                isSelected -> clipFillColor
                                else -> clipFillColor.copy(alpha = 0.88f)
                            },
                            topLeft = Offset(sX, barTop),
                            size = Size(clipW, barH),
                            cornerRadius = CornerRadius(if (isSelected) 2.dp.toPx() else cornerPx, if (isSelected) 2.dp.toPx() else cornerPx)
                        )

                        if (isSelected) {
                            val tickGap = 5.dp.toPx()
                            var tx = max(0f, sX + tickGap)
                            val endTick = (sX + clipW - 4.dp.toPx()).coerceAtMost(w)
                            while (tx < endTick) {
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
                                val bx = sX + i * stepW + stepW * 0.5f
                                if (bx in 0f..w) {
                                    val amp = bins[i].coerceIn(0.08f, 1f)
                                    val bh = amp * (barH - 6f)
                                    drawLine(
                                        color = Color.White.copy(alpha = 0.28f),
                                        start = Offset(bx, h * 0.5f - bh * 0.5f),
                                        end = Offset(bx, h * 0.5f + bh * 0.5f),
                                        strokeWidth = (stepW * 0.58f).coerceIn(1f, 3f)
                                    )
                                }
                            }
                        }

                        val clipVisLeft = (sX + 4.dp.toPx()).coerceIn(0f, w)
                        val clipVisRight = (sX + clipW - 4.dp.toPx()).coerceIn(0f, w)
                        if (clipVisRight > clipVisLeft) {
                            clipRect(
                                left = clipVisLeft,
                                top = barTop,
                                right = clipVisRight,
                                bottom = barTop + barH
                            ) {
                                if (clipW >= 34.dp.toPx()) {
                                    val textY = h * 0.5f - (clipLabelPaint.descent() + clipLabelPaint.ascent()) * 0.5f
                                    val displayLabel = buildString {
                                        if (layer.type == LayerType.GROUP) append("[GRP] ")
                                        else if (layer.parentId != null) append("↳ ")
                                        append(layer.name)
                                    }
                                    // Sticky label stays readable when clip scrolls partially past left edge
                                    val labelX = max(sX + 7.dp.toPx(), 6.dp.toPx())
                                    drawContext.canvas.nativeCanvas.drawText(
                                        displayLabel,
                                        labelX,
                                        textY,
                                        clipLabelPaint
                                    )
                                }

                                kfTimes.forEach { kt ->
                                    val kx = msToX(kt)
                                    if (kx in (clipVisLeft - 6f)..(clipVisRight + 6f)) {
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

                                // Middle bottom-down expand/collapse chevron on Group track bar (Section 3.3)
                                if (layer.type == LayerType.GROUP) {
                                    val chevX = (clipVisLeft + clipVisRight) * 0.5f
                                    val chevY = barTop + barH - 7.dp.toPx()
                                    val chevR = 4.2.dp.toPx()
                                    drawRoundRect(
                                        color = Color.Black.copy(alpha = 0.45f),
                                        topLeft = Offset(chevX - 10.dp.toPx(), chevY - 5.5.dp.toPx()),
                                        size = Size(20.dp.toPx(), 11.dp.toPx()),
                                        cornerRadius = CornerRadius(5.5.dp.toPx(), 5.5.dp.toPx())
                                    )
                                    val chevPath = Path().apply {
                                        if (layer.groupExpanded) {
                                            // Downward chevron ▾
                                            moveTo(chevX - chevR, chevY - chevR * 0.45f)
                                            lineTo(chevX, chevY + chevR * 0.55f)
                                            lineTo(chevX + chevR, chevY - chevR * 0.45f)
                                        } else {
                                            // Collapsed chevron ▸
                                            moveTo(chevX - chevR * 0.45f, chevY - chevR)
                                            lineTo(chevX + chevR * 0.55f, chevY)
                                            lineTo(chevX - chevR * 0.45f, chevY + chevR)
                                        }
                                    }
                                    drawPath(
                                        path = chevPath,
                                        color = Color.White,
                                        style = Stroke(width = 1.7.dp.toPx(), cap = androidx.compose.ui.graphics.StrokeCap.Round)
                                    )
                                }
                            }
                        }

                        drawRoundRect(
                            color = when {
                                isHoldingClip || isDraggingHandle -> KeyframeAmber
                                isMultiChecked -> Color(0xFFC4B5FD)
                                isSelected -> Color.White
                                else -> Color.White.copy(alpha = 0.82f)
                            },
                            topLeft = Offset(sX, barTop),
                            size = Size(clipW, barH),
                            cornerRadius = CornerRadius(if (isSelected) 2.dp.toPx() else cornerPx, if (isSelected) 2.dp.toPx() else cornerPx),
                            style = Stroke(width = if (isSelected || isMultiChecked || isHoldingClip || isDraggingHandle) 1.6.dp.toPx() else 1.1.dp.toPx())
                        )
                    }
                }
            }
        }

        // Right 34dp Gutter: Three-Dashes ('≡') Reorder Handle (Tap to Select, Long-Tap to Multi-Select, Drag Up/Down to Reorder!)
        Box(
            modifier = Modifier
                .width(TIMELINE_RIGHT_GUTTER)
                .fillMaxHeight()
                .combinedClickable(
                    onClick = latestClickLayer,
                    onLongClick = latestToggleMulti
                )
                .pointerInput(layer.id) {
                    var handleAccumY = 0f
                    detectVerticalDragGestures(
                        onDragStart = {
                            isDraggingHandle = true
                            latestDragActiveChange(true)
                            handleAccumY = 0f
                        },
                        onVerticalDrag = { change, dragAmount ->
                            change.consume()
                            handleAccumY += dragAmount
                            if (abs(handleAccumY) >= rowHeightPx * 0.85f && latestTotalLayers > 1 && latestLayerIdx >= 0) {
                                val stepsUp = (-handleAccumY / rowHeightPx).toInt()
                                if (stepsUp != 0) {
                                    val nextIdx = (latestLayerIdx + stepsUp).coerceIn(0, latestTotalLayers - 1)
                                    if (nextIdx != latestLayerIdx) {
                                        latestReorderCallback(nextIdx)
                                    }
                                    handleAccumY = 0f
                                }
                            }
                        },
                        onDragEnd = {
                            isDraggingHandle = false
                            latestDragActiveChange(false)
                        },
                        onDragCancel = {
                            isDraggingHandle = false
                            latestDragActiveChange(false)
                        }
                    )
                }
                .testTag("layer_track_${layer.id}"),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.Menu,
                contentDescription = "Reorder or Select ${layer.name}",
                tint = when {
                    isDraggingHandle -> KeyframeAmber
                    isMultiChecked -> Color(0xFFC4B5FD)
                    isSelected -> KeyflowBlueBright
                    else -> Color(0xFF7D889C)
                },
                modifier = Modifier.size(17.dp)
            )
        }
    }
}
