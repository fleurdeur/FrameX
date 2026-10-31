@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)

package com.example.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.ShowChart
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.Category
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.HighQuality
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.IosShare
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Loop
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.ViewInAr
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
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
import com.example.engine.PlaybackCacheEngine
import com.example.engine.PreRenderUiState
import com.example.ui.components.AmSquareIconButton
import com.example.ui.components.GlPreviewCanvas
import com.example.ui.theme.AmBgDark
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
import com.example.ui.theme.PlayheadRed
import com.example.ui.theme.PlayheadWhite
import com.example.ui.theme.RigGreen
import com.example.ui.theme.SurfaceDark
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import com.example.viewmodel.AmInspectorTab
import com.example.viewmodel.StudioDestination
import com.example.viewmodel.StudioWorkspaceState
import kotlin.math.abs

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
    onUpdateEffectSlotValue: (Float) -> Unit = {},
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
    val project = state.activeProject
    val selectedLayer = project.layers.firstOrNull { it.id == state.selectedLayerId }
    val isLayerSheetOpen = state.inspectorTab != AmInspectorTab.TIMELINE && selectedLayer != null
    val density = LocalDensity.current

    var showAddDrawer by remember { mutableStateOf(false) }
    var showProjectSettings by remember { mutableStateOf(false) }
    var showClipboardMenu by remember { mutableStateOf(false) }
    var showQualityMenu by remember { mutableStateOf(false) }
    var showPlayHoldMenu by remember { mutableStateOf(false) }

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

    if (showProjectSettings) {
        AmProjectSettingsDialog(
            project = project,
            onSaveSettings = onUpdateProjectSettings,
            onDismiss = { showProjectSettings = false }
        )
    }

    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing,
        containerColor = AmBgDark,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(containerColor = SurfaceDark),
                navigationIcon = {
                    IconButton(
                        onClick = onBack,
                        modifier = Modifier.testTag("editor_back_button")
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back to Home",
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
                            .padding(horizontal = 4.dp, vertical = 2.dp)
                    ) {
                        Column(modifier = Modifier.weight(1f, fill = false)) {
                            Text(
                                text = project.name,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = TextPrimary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = "${project.width}×${project.height} • ${project.fps}fps",
                                style = MaterialTheme.typography.labelSmall,
                                color = TextSecondary,
                                maxLines = 1
                            )
                        }
                        Icon(
                            imageVector = Icons.Default.Settings,
                            contentDescription = "Project settings",
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
                            contentDescription = "Sync project to cloud",
                            active = state.isSavingCloud,
                            onClick = onSaveCloud,
                            sizeDp = 36.dp,
                            testTag = "editor_cloud_save_button"
                        )

                        // Alight Motion ⊞+ Layer Actions & Composition Menu
                        Box {
                            AmSquareIconButton(
                                icon = Icons.Default.ContentCopy,
                                contentDescription = "Layer actions menu",
                                active = state.hasCopiedLayer || state.hasCopiedEffects,
                                onClick = { showClipboardMenu = true },
                                sizeDp = 36.dp,
                                testTag = if (isLayerSheetOpen) "topbar_clipboard_button" else "duplicate_layer_button"
                            )
                            DropdownMenu(
                                expanded = showClipboardMenu,
                                onDismissRequest = { showClipboardMenu = false }
                            ) {
                                DropdownMenuItem(
                                    text = { Text("Copy Layer") },
                                    enabled = selectedLayer != null,
                                    onClick = {
                                        onCopyLayer()
                                        showClipboardMenu = false
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("Paste Layer") },
                                    enabled = state.hasCopiedLayer,
                                    onClick = {
                                        onPasteLayer()
                                        showClipboardMenu = false
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("Duplicate Layer") },
                                    enabled = selectedLayer != null,
                                    onClick = {
                                        onDuplicateLayer()
                                        showClipboardMenu = false
                                    }
                                )
                                if (selectedLayer != null) {
                                    HorizontalDivider(color = OutlineSubtle)
                                    DropdownMenuItem(
                                        text = { Text("Bring Layer Forward") },
                                        onClick = {
                                            onMoveLayerZOrder(selectedLayer.id, false)
                                            showClipboardMenu = false
                                        }
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Send Layer Backward") },
                                        onClick = {
                                            onMoveLayerZOrder(selectedLayer.id, true)
                                            showClipboardMenu = false
                                        }
                                    )
                                    DropdownMenuItem(
                                        text = {
                                            Text(
                                                if (state.soloLayerId == selectedLayer.id) "Disable Solo Layer"
                                                else "Solo Layer"
                                            )
                                        },
                                        onClick = {
                                            onToggleSoloLayer(selectedLayer.id)
                                            showClipboardMenu = false
                                        }
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Expand Clip to Composition") },
                                        onClick = {
                                            onExpandLayerToComp()
                                            showClipboardMenu = false
                                        }
                                    )
                                }
                                if (project.layers.size >= 2) {
                                    DropdownMenuItem(
                                        text = { Text("Group into Null Rig") },
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
                                    text = { Text("Fit to Composition Area") },
                                    enabled = selectedLayer != null && selectedLayer.type != LayerType.AUDIO,
                                    onClick = {
                                        onFitOrFillComposition(false)
                                        showClipboardMenu = false
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("Fill Composition Area") },
                                    enabled = selectedLayer != null && selectedLayer.type != LayerType.AUDIO,
                                    onClick = {
                                        onFitOrFillComposition(true)
                                        showClipboardMenu = false
                                    }
                                )
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            if (selectedLayer?.isClippingMask == true) "Disable Clipping Mask"
                                            else "Enable Clipping Mask"
                                        )
                                    },
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
                                    text = { Text("Flip Horizontally") },
                                    enabled = selectedLayer != null,
                                    onClick = {
                                        onFlipLayer(true)
                                        showClipboardMenu = false
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("Flip Vertically") },
                                    enabled = selectedLayer != null,
                                    onClick = {
                                        onFlipLayer(false)
                                        showClipboardMenu = false
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("Reset Transform") },
                                    enabled = selectedLayer != null,
                                    onClick = {
                                        onResetTransform()
                                        showClipboardMenu = false
                                    }
                                )
                            }
                        }

                        if (selectedLayer != null) {
                            AmSquareIconButton(
                                icon = Icons.Default.DeleteOutline,
                                contentDescription = "Delete selected layer",
                                tint = NeonPink,
                                onClick = onDeleteSelectedLayer,
                                sizeDp = 36.dp,
                                testTag = if (isLayerSheetOpen) "topbar_delete_layer_button" else "delete_layer_button"
                            )
                        }

                        Spacer(modifier = Modifier.width(2.dp))

                        AmSquareIconButton(
                            icon = Icons.Default.IosShare,
                            contentDescription = "Export video",
                            active = true,
                            onClick = { onNavigateTool(StudioDestination.EXPORT_PIPELINE) },
                            sizeDp = 36.dp,
                            testTag = "editor_export_button"
                        )
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            // 2. TOP HALF: UNOBSTRUCTED ALIGHT MOTION PREVIEW STAGE + QUALITY SWITCHER & CACHE HUD
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(0.45f)
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
                    onViewportGesture = onViewportGesture,
                    onDragSelectedLayer = onDragLayerOnCanvas,
                    onScaleAndRotateSelectedLayer = onScaleAndRotateLayerOnCanvas,
                    onSelectLayerOnCanvas = onSelectLayer,
                    modifier = Modifier.fillMaxSize()
                )

                // Top-Left: Preview Quality Switcher (Full / Half / Quarter / Low) + Pre-render Status + Performance HUD
                Column(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(8.dp),
                    verticalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box {
                            Row(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(SurfaceDark.copy(alpha = 0.90f))
                                    .border(0.8.dp, OutlineSubtle, RoundedCornerShape(8.dp))
                                    .clickable { showQualityMenu = true }
                                    .testTag("preview_quality_button")
                                    .padding(horizontal = 9.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(5.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.HighQuality,
                                    contentDescription = "Preview quality",
                                    tint = AmTealBright,
                                    modifier = Modifier.size(14.dp)
                                )
                                Text(
                                    text = "${state.previewQuality.badge} ${state.previewQuality.title}",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = TextPrimary
                                )
                                if (preRenderState.cachedFramesCount > 0) {
                                    Box(
                                        modifier = Modifier
                                            .size(6.dp)
                                            .clip(CircleShape)
                                            .background(RigGreen)
                                    )
                                }
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
                                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                                            ) {
                                                Text(
                                                    text = "${q.title} (${q.badge})",
                                                    fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal,
                                                    color = if (isCurrent) AmTealBright else TextPrimary
                                                )
                                                if (isCurrent) {
                                                    Icon(
                                                        imageVector = Icons.Default.Check,
                                                        contentDescription = null,
                                                        tint = AmTealBright,
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
                                            text = if (preRenderState.isPreRendering) "Stop Pre-render" else "Pre-render Playback Cache",
                                            color = AudioCyan,
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
                                            text = if (state.isLoopPlayback) "Loop Playback: ON" else "Loop Playback: OFF",
                                            color = if (state.isLoopPlayback) AmTealBright else TextSecondary
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
                                        text = { Text("Clear Playback Cache", color = NeonPink) },
                                        onClick = {
                                            onClearPlaybackCache()
                                            showQualityMenu = false
                                        },
                                        modifier = Modifier.testTag("preview_menu_clear_cache")
                                    )
                                }
                            }
                        }

                        if (preRenderState.isPreRendering) {
                            Row(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(SurfaceDark.copy(alpha = 0.92f))
                                    .border(0.8.dp, AudioCyan.copy(alpha = 0.8f), RoundedCornerShape(8.dp))
                                    .clickable(onClick = onTriggerOrCancelPreRender)
                                    .testTag("prerender_progress_pill")
                                    .padding(horizontal = 8.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(5.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Bolt,
                                    contentDescription = "Pre-rendering",
                                    tint = AudioCyan,
                                    modifier = Modifier.size(13.dp)
                                )
                                val fpsSuffix = if (preRenderState.throughputFps > 0) " • ${preRenderState.throughputFps} fps" else ""
                                Text(
                                    text = "Pre-render ${(preRenderState.progress * 100).toInt()}%$fpsSuffix",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = AudioCyan
                                )
                            }
                        }
                    }

                    if (preferences.showPerformanceHud) {
                        Row(
                            modifier = Modifier
                                .clip(RoundedCornerShape(7.dp))
                                .background(DeepBlack.copy(alpha = 0.84f))
                                .border(0.6.dp, OutlineSubtle, RoundedCornerShape(7.dp))
                                .testTag("preview_performance_hud")
                                .padding(horizontal = 8.dp, vertical = 3.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                text = "${state.livePreviewFps} FPS • ${String.format("%.1f", state.liveFrameTimeMs)}ms • Cache ${PlaybackCacheEngine.getCacheHitRatePercent()}%",
                                style = MaterialTheme.typography.labelSmall,
                                color = RigGreen
                            )
                        }
                    }
                }

                if (state.canvasZoom != 1f || state.canvasPanX != 0f || state.canvasPanY != 0f) {
                    Text(
                        text = "Fit View",
                        style = MaterialTheme.typography.labelSmall,
                        color = AmTealBright,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(8.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(SurfaceDark.copy(alpha = 0.90f))
                            .border(0.8.dp, OutlineSubtle, RoundedCornerShape(8.dp))
                            .clickable(onClick = onResetViewport)
                            .padding(horizontal = 9.dp, vertical = 4.dp)
                    )
                }

                if (!state.statusBanner.isNullOrBlank()) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .padding(8.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(SurfaceDark.copy(alpha = 0.90f))
                            .border(0.8.dp, AmTeal.copy(alpha = 0.55f), RoundedCornerShape(8.dp))
                            .padding(horizontal = 10.dp, vertical = 4.dp)
                    ) {
                        Text(
                            text = state.statusBanner,
                            style = MaterialTheme.typography.labelSmall,
                            color = AmTealBright
                        )
                    }
                }
            }

            // 3. EXPRESSIVE ALIGHT MOTION TRANSPORT STRIP (Clean spacing + Pure Icon Bookmarks!)
            val hasBookmarkHere = project.beatMarkers.any { abs(it.timeMs - state.playheadMs) <= 45L }
            Surface(
                color = SurfaceDark,
                modifier = Modifier
                    .fillMaxWidth()
                    .border(0.6.dp, OutlineSubtle)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(46.dp)
                        .padding(horizontal = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    // Left: Clean Undo / Redo Ghost Icons
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        AmSquareIconButton(
                            icon = Icons.AutoMirrored.Filled.Undo,
                            contentDescription = "Undo",
                            enabled = state.canUndo,
                            onClick = onUndo,
                            sizeDp = 36.dp,
                            testTag = "editor_undo_button"
                        )
                        AmSquareIconButton(
                            icon = Icons.AutoMirrored.Filled.Redo,
                            contentDescription = "Redo",
                            enabled = state.canRedo,
                            onClick = onRedo,
                            sizeDp = 36.dp,
                            testTag = "editor_redo_button"
                        )
                    }

                    // Center: 1:1 Alight Motion Smart Bookmark/Boundary Jump (|< & >|) + Frame Step (‹ & ›) + Play/Pause!
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Previous button beside Play: jumps to earlier bookmark if present, or 00:00:00 if none!
                        AmSquareIconButton(
                            icon = Icons.Default.SkipPrevious,
                            contentDescription = "Previous bookmark or start (0)",
                            tint = TextPrimary,
                            onClick = { onJumpBookmark(false) },
                            sizeDp = 34.dp,
                            testTag = "transport_prev_bookmark_or_start_button"
                        )

                        AmSquareIconButton(
                            icon = Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                            contentDescription = "Step back 1 frame",
                            tint = TextSecondary,
                            onClick = { onStepFrame(-1) },
                            sizeDp = 30.dp,
                            testTag = "step_prev_frame_button"
                        )

                        // Play / Pause with Click-and-Hold Upper Buttons (Loop + Pre-render)!
                        Box(contentAlignment = Alignment.Center) {
                            if (showPlayHoldMenu) {
                                Popup(
                                    alignment = Alignment.TopCenter,
                                    offset = IntOffset(0, -with(density) { 46.dp.roundToPx() }),
                                    onDismissRequest = { showPlayHoldMenu = false },
                                    properties = PopupProperties(focusable = true)
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .shadow(8.dp, RoundedCornerShape(12.dp))
                                            .clip(RoundedCornerShape(12.dp))
                                            .background(SurfaceDark)
                                            .border(1.dp, AmTeal.copy(alpha = 0.65f), RoundedCornerShape(12.dp))
                                            .testTag("play_hold_upper_menu")
                                            .padding(horizontal = 6.dp, vertical = 5.dp),
                                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        // 1. LOOP UPPER BUTTON
                                        Row(
                                            modifier = Modifier
                                                .clip(RoundedCornerShape(8.dp))
                                                .background(if (state.isLoopPlayback) AmTealContainer else PanelDark)
                                                .border(
                                                    0.9.dp,
                                                    if (state.isLoopPlayback) AmTealBright else OutlineSubtle,
                                                    RoundedCornerShape(8.dp)
                                                )
                                                .clickable {
                                                    onToggleLoop()
                                                    showPlayHoldMenu = false
                                                }
                                                .testTag("play_hold_loop_button")
                                                .padding(horizontal = 9.dp, vertical = 5.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.Loop,
                                                contentDescription = "Loop playback",
                                                tint = if (state.isLoopPlayback) AmTealBright else TextSecondary,
                                                modifier = Modifier.size(14.dp)
                                            )
                                            Text(
                                                text = if (state.isLoopPlayback) "Loop ON" else "Loop OFF",
                                                style = MaterialTheme.typography.labelSmall,
                                                fontWeight = FontWeight.Bold,
                                                color = if (state.isLoopPlayback) AmTealBright else TextPrimary
                                            )
                                        }

                                        // 2. PRE-RENDER UPPER BUTTON
                                        Row(
                                            modifier = Modifier
                                                .clip(RoundedCornerShape(8.dp))
                                                .background(
                                                    if (preRenderState.isPreRendering || preRenderState.cachedFramesCount > 0) {
                                                        AudioCyan.copy(alpha = 0.16f)
                                                    } else PanelDark
                                                )
                                                .border(
                                                    0.9.dp,
                                                    if (preRenderState.isPreRendering) AudioCyan else OutlineSubtle,
                                                    RoundedCornerShape(8.dp)
                                                )
                                                .clickable {
                                                    onTriggerOrCancelPreRender()
                                                    showPlayHoldMenu = false
                                                }
                                                .testTag("play_hold_prerender_button")
                                                .padding(horizontal = 9.dp, vertical = 5.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.Bolt,
                                                contentDescription = "Pre-render playback",
                                                tint = AudioCyan,
                                                modifier = Modifier.size(14.dp)
                                            )
                                            Text(
                                                text = if (preRenderState.isPreRendering) {
                                                    "Stop ${(preRenderState.progress * 100).toInt()}%"
                                                } else {
                                                    "Pre-render"
                                                },
                                                style = MaterialTheme.typography.labelSmall,
                                                fontWeight = FontWeight.Bold,
                                                color = AudioCyan
                                            )
                                        }
                                    }
                                }
                            }

                            Box(
                                modifier = Modifier
                                    .size(38.dp)
                                    .clip(CircleShape)
                                    .background(
                                        if (state.isPlaying) {
                                            Brush.linearGradient(listOf(AmTealBright, AmTeal))
                                        } else {
                                            Brush.linearGradient(listOf(PanelElevated, PanelDark))
                                        }
                                    )
                                    .border(
                                        1.dp,
                                        if (state.isPlaying) AmTealBright else OutlineSubtle,
                                        CircleShape
                                    )
                                    .combinedClickable(
                                        onClick = onTogglePlay,
                                        onLongClick = { showPlayHoldMenu = true }
                                    )
                                    .testTag("timeline_play_pause_button"),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = if (state.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                    contentDescription = if (state.isPlaying) "Pause (hold for Loop & Pre-render)" else "Play (hold for Loop & Pre-render)",
                                    tint = if (state.isPlaying) DeepBlack else TextPrimary,
                                    modifier = Modifier.size(21.dp)
                                )
                            }

                            if (state.isLoopPlayback) {
                                Box(
                                    modifier = Modifier
                                        .align(Alignment.TopEnd)
                                        .size(7.dp)
                                        .clip(CircleShape)
                                        .background(AmTealBright)
                                        .border(1.dp, SurfaceDark, CircleShape)
                                )
                            }
                        }

                        AmSquareIconButton(
                            icon = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                            contentDescription = "Step forward 1 frame",
                            tint = TextSecondary,
                            onClick = { onStepFrame(1) },
                            sizeDp = 30.dp,
                            testTag = "step_next_frame_button"
                        )

                        // Next/Forward button beside Play: jumps to forward bookmark if present, or timeline end if none!
                        AmSquareIconButton(
                            icon = Icons.Default.SkipNext,
                            contentDescription = "Next bookmark or timeline end",
                            tint = TextPrimary,
                            onClick = { onJumpBookmark(true) },
                            sizeDp = 34.dp,
                            testTag = "transport_next_bookmark_or_end_button"
                        )
                    }

                    // Right: Pure Icon Bookmark Navigation Cluster (‹ 🔖 › — always active: jumps to bookmark or 0/end!)
                    Row(
                        modifier = Modifier
                            .height(34.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(if (hasBookmarkHere) NeonPink.copy(alpha = 0.16f) else PanelDark)
                            .border(
                                1.dp,
                                if (hasBookmarkHere) NeonPink.copy(alpha = 0.85f) else OutlineSubtle,
                                RoundedCornerShape(10.dp)
                            ),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(28.dp, 34.dp)
                                .clickable { onJumpBookmark(false) }
                                .testTag("bookmark_cluster_prev_button"),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                                contentDescription = "Previous bookmark or start",
                                tint = if (project.beatMarkers.isNotEmpty()) NeonPink else TextSecondary,
                                modifier = Modifier.size(17.dp)
                            )
                        }

                        Box(
                            modifier = Modifier
                                .size(32.dp, 34.dp)
                                .clickable(onClick = onToggleBeatBookmark)
                                .testTag("timecode_bookmark_button"),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = if (hasBookmarkHere) Icons.Default.Bookmark else Icons.Default.BookmarkBorder,
                                contentDescription = "Toggle bookmark at playhead",
                                tint = if (hasBookmarkHere) NeonPink else AmTealBright,
                                modifier = Modifier.size(17.dp)
                            )
                        }

                        Box(
                            modifier = Modifier
                                .size(28.dp, 34.dp)
                                .clickable { onJumpBookmark(true) }
                                .testTag("bookmark_cluster_next_button"),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                                contentDescription = "Next bookmark or end",
                                tint = if (project.beatMarkers.isNotEmpty()) NeonPink else TextSecondary,
                                modifier = Modifier.size(17.dp)
                            )
                        }
                    }
                }
            }

            // 4. BOTTOM HALF: MULTITRACK TIMELINE + SCALED-DOWN 1:1 ALIGHT MOTION '+' FAB OR CLICKED-LAYER SHEET
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(0.55f)
            ) {
                if (isLayerSheetOpen && selectedLayer != null && !showAddDrawer) {
                    val selIdx = project.layers.indexOfFirst { it.id == selectedLayer.id }
                    AmClickedLayerSheet(
                        activeTab = state.inspectorTab,
                        selectedLayer = selectedLayer,
                        allLayers = project.layers,
                        projectDurationMs = project.durationMs,
                        selectedProperty = state.selectedProperty,
                        selectedEffectId = state.selectedEffectId,
                        selectedEffectSlot = state.selectedEffectSlot,
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
                        onUpdateEffectSlotValue = onUpdateEffectSlotValue,
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
                } else {
                    AmMultitrackTimelineArea(
                        state = state,
                        cachedSpanFractions = preRenderState.cachedSpanFractions,
                        isAddDrawerOpen = showAddDrawer,
                        onScrubPlayhead = onScrubPlayhead,
                        onSetTimelineZoom = onSetTimelineZoom,
                        onSelectLayer = onSelectLayer,
                        onToggleLayerVisibility = onToggleLayerVisibility,
                        onNavigateTool = onNavigateTool,
                        onAddNullRig = { onAddLayer(LayerType.NULL_OBJECT, "") },
                        onOpenAddDrawer = { showAddDrawer = true }
                    )
                }

                // Scaled-down floating '+' Popover Card — Timeline Ruler & Upper Layers remain visible behind it!
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

/**
 * 1:1 Alight Motion Multitrack Timeline Area:
 * - Row A: Time Ruler with Playhead Timestamp in left header cell + Zoom toggle + Playhead Scrubbing
 * - Row B: Full-height scrollable Layer Stack with generous 76.dp bottom scroll clearance
 * - Floating Bottom-Left: Scaled-down Icon-Only Quick Studio Pill
 * - Floating Bottom-Right: Scaled-down Circular Alight Motion Teal '+' FAB (44.dp visual / 48.dp touch target)
 */
@Composable
private fun AmMultitrackTimelineArea(
    state: StudioWorkspaceState,
    cachedSpanFractions: List<Pair<Float, Float>> = emptyList(),
    isAddDrawerOpen: Boolean,
    onScrubPlayhead: (Long) -> Unit,
    onSetTimelineZoom: (Float) -> Unit,
    onSelectLayer: (String) -> Unit,
    onToggleLayerVisibility: (String) -> Unit,
    onNavigateTool: (StudioDestination) -> Unit,
    onAddNullRig: () -> Unit,
    onOpenAddDrawer: () -> Unit
) {
    val project = state.activeProject
    val durFloat = project.durationMs.coerceAtLeast(1L).toFloat()

    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            // A. 1:1 ALIGHT MOTION TIME RULER ROW (Timecode cleanly placed above Layer Headers!)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(32.dp)
                    .background(DeepBlack)
                    .border(0.6.dp, OutlineSubtle),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    modifier = Modifier
                        .width(136.dp)
                        .fillMaxHeight()
                        .padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = AnimationEvaluator.formatSmpteTimecode(state.playheadMs, project.fps).drop(3),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = AmTealBright,
                        maxLines = 1
                    )
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(PanelDark)
                            .clickable {
                                val nextZoom = when {
                                    state.timelineZoom < 0.9f -> 1.0f
                                    state.timelineZoom < 1.4f -> 1.5f
                                    state.timelineZoom < 2.2f -> 2.5f
                                    else -> 0.5f
                                }
                                onSetTimelineZoom(nextZoom)
                            }
                            .padding(horizontal = 5.dp, vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.ZoomIn,
                            contentDescription = "Cycle timeline zoom",
                            tint = TextSecondary,
                            modifier = Modifier.size(12.dp)
                        )
                        Spacer(modifier = Modifier.width(2.dp))
                        Text(
                            text = "${String.format("%.1f", state.timelineZoom)}x",
                            style = MaterialTheme.typography.labelSmall,
                            color = TextSecondary
                        )
                    }
                }

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .pointerInput(project.durationMs) {
                            detectTapGestures { offset ->
                                val frac = (offset.x / size.width.coerceAtLeast(1)).coerceIn(0f, 1f)
                                onScrubPlayhead((frac * project.durationMs).toLong())
                            }
                        }
                        .pointerInput(project.durationMs) {
                            detectHorizontalDragGestures { change, _ ->
                                change.consume()
                                val frac = (change.position.x / size.width.coerceAtLeast(1)).coerceIn(0f, 1f)
                                onScrubPlayhead((frac * project.durationMs).toLong())
                            }
                        }
                        .testTag("timeline_ruler_scrubber")
                ) {
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        val w = size.width
                        val h = size.height

                        // Green RAM Pre-Render Cache Span Indicator Bar along top of ruler!
                        cachedSpanFractions.forEach { (startF, endF) ->
                            val sx = (startF.coerceIn(0f, 1f)) * w
                            val ex = (endF.coerceIn(0f, 1f)) * w
                            drawRect(
                                color = RigGreen.copy(alpha = 0.85f),
                                topLeft = Offset(sx, 0f),
                                size = Size((ex - sx).coerceAtLeast(3f), 3.2f)
                            )
                        }

                        val tickDensity = (28 * state.timelineZoom).toInt().coerceIn(16, 96)
                        for (i in 0..tickDensity) {
                            val x = (i.toFloat() / tickDensity) * w
                            val isMajor = i % 4 == 0
                            drawLine(
                                color = if (isMajor) TextSecondary else OutlineSubtle,
                                start = Offset(x, if (isMajor) h * 0.34f else h * 0.64f),
                                end = Offset(x, h),
                                strokeWidth = if (isMajor) 1.5f else 1f
                            )
                        }

                        project.beatMarkers.forEach { bm ->
                            val bx = (bm.timeMs / durFloat).coerceIn(0f, 1f) * w
                            val flag = Path().apply {
                                moveTo(bx, 1f)
                                lineTo(bx + 7f, 5.5f)
                                lineTo(bx, 10f)
                                close()
                            }
                            drawPath(flag, NeonPink)
                        }

                        val px = (state.playheadMs / durFloat).coerceIn(0f, 1f) * w
                        val crown = Path().apply {
                            moveTo(px - 6f, 0f)
                            lineTo(px + 6f, 0f)
                            lineTo(px + 6f, 7f)
                            lineTo(px, 12f)
                            lineTo(px - 6f, 7f)
                            close()
                        }
                        drawPath(crown, PlayheadRed)
                        drawLine(PlayheadRed, Offset(px, 11f), Offset(px, h), strokeWidth = 2.2f)
                    }
                }
            }

            // B. MULTITRACK LAYER STACK (Generous bottom padding so layers scroll completely clear of the scaled-down FAB!)
            if (project.layers.isEmpty()) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .background(AmBgDark)
                        .padding(start = 24.dp, end = 24.dp, bottom = 54.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Layers,
                            contentDescription = null,
                            tint = AmTeal.copy(alpha = 0.75f),
                            modifier = Modifier.size(30.dp)
                        )
                        Text(
                            text = "Empty Composition",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = TextPrimary
                        )
                        Text(
                            text = "Tap the '+' button in the corner to add Video, Image, Audio, Shape, Text, or Camera.",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentPadding = PaddingValues(top = 4.dp, bottom = 74.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    val reversedLayers = project.layers.asReversed()
                    itemsIndexed(reversedLayers, key = { _, l -> l.id }) { _, layer ->
                        val isSelected = layer.id == state.selectedLayerId
                        AmTimelineLayerRow(
                            layer = layer,
                            isSelected = isSelected,
                            playheadMs = state.playheadMs,
                            durationMs = project.durationMs,
                            beatMarkers = project.beatMarkers.map { it.timeMs },
                            onClickLayer = { onSelectLayer(layer.id) },
                            onScrubPlayhead = onScrubPlayhead,
                            onToggleVisibility = { onToggleLayerVisibility(layer.id) }
                        )
                    }
                }
            }
        }

        // C. SCALED-DOWN FLOATING STUDIO ICON PILL (Bottom-Left) + 1:1 ALIGHT MOTION '+' FAB (Bottom-Right)
        if (!isAddDrawerOpen) {
            Row(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .navigationBarsPadding()
                    .padding(start = 10.dp, bottom = 10.dp)
                    .height(38.dp)
                    .shadow(6.dp, CircleShape)
                    .clip(CircleShape)
                    .background(SurfaceDark.copy(alpha = 0.90f))
                    .border(1.dp, OutlineSubtle, CircleShape)
                    .padding(horizontal = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                AmMiniStudioIcon(
                    icon = Icons.Default.AutoFixHigh,
                    contentDescription = "Effects Browser",
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
                    contentDescription = "Audio Reactor",
                    onClick = { onNavigateTool(StudioDestination.AUDIO_REACTOR) },
                    testTag = "tool_audio_button"
                )
                AmMiniStudioIcon(
                    icon = Icons.Default.ViewInAr,
                    contentDescription = "3D Workspace",
                    onClick = { onNavigateTool(StudioDestination.WORKSPACE_3D) },
                    testTag = "tool_3d_button"
                )
                AmMiniStudioIcon(
                    icon = Icons.Default.AccountTree,
                    contentDescription = "Add Null Rig",
                    onClick = onAddNullRig,
                    testTag = "tool_rig_button"
                )
            }

            // Scaled-Down 1:1 Alight Motion Circular Emerald-Teal '+' FAB!
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
                        .size(44.dp)
                        .shadow(8.dp, CircleShape)
                        .clip(CircleShape)
                        .background(Brush.linearGradient(listOf(AmTealBright, AmTeal))),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = "Add layer or element",
                        tint = DeepBlack,
                        modifier = Modifier.size(22.dp)
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
    testTag: String
) {
    Box(
        modifier = Modifier
            .size(34.dp)
            .clip(CircleShape)
            .clickable(onClick = onClick)
            .testTag(testTag),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = AmTealBright,
            modifier = Modifier.size(17.dp)
        )
    }
}

@Composable
private fun AmTimelineLayerRow(
    layer: Layer,
    isSelected: Boolean,
    playheadMs: Long,
    durationMs: Long,
    beatMarkers: List<Long>,
    onClickLayer: () -> Unit,
    onScrubPlayhead: (Long) -> Unit,
    onToggleVisibility: () -> Unit
) {
    val swatch = remember(layer.colorHex, layer.type) {
        when (layer.type) {
            LayerType.NULL_OBJECT -> RigGreen
            LayerType.AUDIO -> AudioCyan
            LayerType.CAMERA -> KeyframeAmber
            else -> runCatching { Color(android.graphics.Color.parseColor(layer.colorHex)) }.getOrDefault(AmTeal)
        }
    }
    val typeIcon = when (layer.type) {
        LayerType.SHAPE -> Icons.Default.Category
        LayerType.TEXT -> Icons.Default.TextFields
        LayerType.VIDEO -> Icons.Default.Movie
        LayerType.IMAGE -> Icons.Default.Image
        LayerType.AUDIO -> Icons.Default.MusicNote
        LayerType.CAMERA -> Icons.Default.Videocam
        LayerType.MODEL_3D -> Icons.Default.ViewInAr
        LayerType.NULL_OBJECT -> Icons.Default.AccountTree
    }
    val kfTimes = remember(layer) {
        layer.allKeyframeTimes()
    }
    val durFloat = durationMs.coerceAtLeast(1L).toFloat()

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(50.dp)
            .background(if (isSelected) AmTealContainer.copy(alpha = 0.34f) else SurfaceDark)
            .border(0.5.dp, if (isSelected) AmTeal else OutlineSubtle),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Clean, Un-Squeezed Left Track Header (136.dp)
        Row(
            modifier = Modifier
                .width(136.dp)
                .fillMaxHeight()
                .clickable(onClick = onClickLayer)
                .testTag("layer_track_${layer.id}")
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Icon(
                imageVector = if (layer.visible) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                contentDescription = "Toggle layer visibility",
                tint = if (layer.visible) TextSecondary else TextMuted.copy(alpha = 0.45f),
                modifier = Modifier
                    .size(16.dp)
                    .clickable(onClick = onToggleVisibility)
            )

            Box(
                modifier = Modifier
                    .size(26.dp)
                    .clip(RoundedCornerShape(7.dp))
                    .background(swatch.copy(alpha = 0.20f))
                    .border(1.dp, swatch.copy(alpha = 0.85f), RoundedCornerShape(7.dp)),
                contentAlignment = Alignment.Center
            ) {
                Icon(imageVector = typeIcon, contentDescription = null, tint = swatch, modifier = Modifier.size(14.dp))
            }

            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(
                        text = layer.name,
                        style = MaterialTheme.typography.labelMedium,
                        color = if (isSelected) AmTealBright else TextPrimary,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    if (layer.locked) {
                        Icon(Icons.Default.Lock, contentDescription = null, tint = KeyframeAmber, modifier = Modifier.size(10.dp))
                    }
                }
                val metaParts = buildList {
                    if (layer.isClippingMask) add("MASK")
                    if (layer.timeRemapEnabled) add("REMAP")
                    if (layer.reversed) add("REV")
                    if (layer.freezeFrameMs >= 0L) add("FREEZE")
                    if (layer.audioMuted && (layer.type == LayerType.VIDEO || layer.type == LayerType.AUDIO)) add("MUTE")
                    if (layer.parentId != null) add("↳ Rig")
                    if (layer.effects.isNotEmpty()) add("${layer.effects.size} FX")
                    if (layer.speedMultiplier != 1.0f && !layer.timeRemapEnabled) {
                        add("${String.format("%.2f", layer.speedMultiplier).trimEnd('0').trimEnd('.')}x")
                    }
                }
                if (metaParts.isNotEmpty()) {
                    Text(
                        text = metaParts.joinToString(" • "),
                        style = MaterialTheme.typography.labelSmall,
                        color = TextMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }

        // Right Clip Bar Lane (renders live extracted/imported waveform for AUDIO & VIDEO clips!)
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .background(AmBgDark)
                .pointerInput(layer.id, durationMs) {
                    detectTapGestures { offset ->
                        val frac = (offset.x / size.width.coerceAtLeast(1)).coerceIn(0f, 1f)
                        val tappedMs = (frac * durationMs).toLong()
                        if (tappedMs in layer.startTimeMs..layer.endTimeMs) {
                            onClickLayer()
                        } else {
                            onScrubPlayhead(tappedMs)
                        }
                    }
                }
                .pointerInput(durationMs) {
                    detectHorizontalDragGestures { change, _ ->
                        change.consume()
                        val frac = (change.position.x / size.width.coerceAtLeast(1)).coerceIn(0f, 1f)
                        onScrubPlayhead((frac * durationMs).toLong())
                    }
                }
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val w = size.width
                val h = size.height

                beatMarkers.forEach { bm ->
                    val bx = (bm / durFloat).coerceIn(0f, 1f) * w
                    drawLine(OutlineSubtle.copy(alpha = 0.35f), Offset(bx, 0f), Offset(bx, h), strokeWidth = 1f)
                }

                val sX = (layer.startTimeMs / durFloat).coerceIn(0f, 1f) * w
                val eX = (layer.endTimeMs / durFloat).coerceIn(0f, 1f) * w
                val clipW = (eX - sX).coerceAtLeast(10f)
                val barTop = 6f
                val barH = h - 12f

                drawRoundRect(
                    color = swatch.copy(alpha = if (isSelected) 0.38f else 0.22f),
                    topLeft = Offset(sX, barTop),
                    size = Size(clipW, barH),
                    cornerRadius = CornerRadius(8f, 8f)
                )

                if (layer.extractedWaveform.isNotEmpty() && (layer.type == LayerType.AUDIO || (layer.type == LayerType.VIDEO && !layer.audioMuted))) {
                    val bins = layer.extractedWaveform
                    val stepW = clipW / bins.size.coerceAtLeast(1)
                    val wfColor = if (layer.type == LayerType.AUDIO) AudioCyan.copy(alpha = 0.85f) else ElectricBlue.copy(alpha = 0.50f)
                    for (i in bins.indices) {
                        val amp = bins[i].coerceIn(0.08f, 1f)
                        val bh = amp * (barH - 6f)
                        val bx = sX + i * stepW + stepW * 0.5f
                        drawLine(
                            color = wfColor,
                            start = Offset(bx, h * 0.5f - bh * 0.5f),
                            end = Offset(bx, h * 0.5f + bh * 0.5f),
                            strokeWidth = (stepW * 0.62f).coerceIn(1.2f, 4f)
                        )
                    }
                }

                drawRoundRect(
                    color = if (isSelected) AmTealBright else swatch.copy(alpha = 0.82f),
                    topLeft = Offset(sX, barTop),
                    size = Size(clipW, barH),
                    cornerRadius = CornerRadius(8f, 8f),
                    style = Stroke(width = if (isSelected) 2f else 1.2f)
                )

                if (isSelected) {
                    drawRoundRect(PlayheadWhite, Offset(sX, barTop), Size(5f, barH), CornerRadius(3f, 3f))
                    drawRoundRect(PlayheadWhite, Offset((eX - 5f).coerceAtLeast(0f), barTop), Size(5f, barH), CornerRadius(3f, 3f))
                }

                kfTimes.forEach { kt ->
                    val kx = (kt / durFloat).coerceIn(0f, 1f) * w
                    val cy = h * 0.5f
                    val diamond = Path().apply {
                        moveTo(kx, cy - 5f)
                        lineTo(kx + 5f, cy)
                        lineTo(kx, cy + 5f)
                        lineTo(kx - 5f, cy)
                        close()
                    }
                    drawPath(diamond, KeyframeAmber)
                    drawPath(diamond, DeepBlack, style = Stroke(width = 1f))
                }

                val px = (playheadMs / durFloat).coerceIn(0f, 1f) * w
                drawLine(PlayheadRed, Offset(px, 0f), Offset(px, h), strokeWidth = 2.2f)
            }
        }
    }
}
