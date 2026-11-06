package com.example

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.credentials.CredentialManager
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.data.model.LayerType
import com.example.data.repository.ProjectRepository
import com.example.engine.PluginPackManager
import com.example.engine.UiOptimizationEngine
import com.example.ui.auth.AuthScreen
import com.example.ui.auth.authStateFlow
import com.example.ui.auth.signOutUser
import com.example.ui.screens.AePluginsBrowserScreen
import com.example.ui.screens.AppSettingsScreen
import com.example.ui.screens.AudioReactorScreen
import com.example.ui.screens.DepthMapScreen
import com.example.ui.screens.EditorWorkspaceScreen
import com.example.ui.screens.EffectsBrowserScreen
import com.example.ui.screens.ExportPipelineScreen
import com.example.ui.screens.GraphEditorScreen
import com.example.ui.screens.HomeScreen
import com.example.ui.screens.ImportPreviewScreen
import com.example.ui.screens.PluginManagerScreen
import com.example.ui.screens.Workspace3DScreen
import com.example.ui.theme.FramePollutionTheme
import com.example.viewmodel.StudioDestination
import com.example.viewmodel.StudioViewModel
import com.google.firebase.Firebase
import com.google.firebase.auth.auth
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class MainActivity : ComponentActivity() {

    private val _incomingUniversalUri = MutableStateFlow<Uri?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        PluginPackManager.ensureInitialized(applicationContext)
        handleIncomingFileIntent(intent)
        enableEdgeToEdge()
        setContent {
            FramePollutionTheme {
                FramePollutionAppRoot(
                    incomingUniversalUriFlow = _incomingUniversalUri.asStateFlow(),
                    onConsumeIncomingUniversalUri = { _incomingUniversalUri.value = null }
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingFileIntent(intent)
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        UiOptimizationEngine.onTrimMemory(level)
    }

    private fun handleIncomingFileIntent(intent: Intent?) {
        if (intent?.action != Intent.ACTION_VIEW) return
        val dataUri = intent.data ?: return
        val uriText = dataUri.toString().lowercase()
        if (uriText.endsWith(".kfpack") || uriText.endsWith(".fppack")) {
            runCatching {
                PluginPackManager.importPackFromUri(applicationContext, dataUri)
            }
        }
        _incomingUniversalUri.value = dataUri
    }
}

@Composable
fun FramePollutionAppRoot(
    incomingUniversalUriFlow: StateFlow<Uri?> = MutableStateFlow<Uri?>(null).asStateFlow(),
    onConsumeIncomingUniversalUri: () -> Unit = {}
) {
    val auth = Firebase.auth
    val user = auth.currentUser

    // Bypass auth screen and use a guest user ID
    AuthenticatedStudioShell(
        currentUserId = user?.uid ?: "guest_user",
        incomingUniversalUriFlow = incomingUniversalUriFlow,
        onConsumeIncomingUniversalUri = onConsumeIncomingUniversalUri
    )
}

@Composable
private fun AuthenticatedStudioShell(
    currentUserId: String,
    incomingUniversalUriFlow: StateFlow<Uri?>,
    onConsumeIncomingUniversalUri: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val credentialManager = remember(context) { CredentialManager.create(context) }

    val studioViewModel: StudioViewModel = viewModel(
        key = currentUserId,
        factory = viewModelFactory {
            initializer {
                val app = checkNotNull(this[APPLICATION_KEY]) {
                    "APPLICATION_KEY missing from CreationExtras"
                }
                val databaseId = app.getString(R.string.firestore_database_id)
                val db = FirebaseFirestore.getInstance(databaseId)
                StudioViewModel(ProjectRepository(db), currentUserId, app)
            }
        }
    )

    val wsState by studioViewModel.state.collectAsStateWithLifecycle()
    val preferences by studioViewModel.preferences.collectAsStateWithLifecycle()
    val preRenderState by studioViewModel.preRenderState.collectAsStateWithLifecycle()
    val cloudProjects by studioViewModel.cloudProjects.collectAsStateWithLifecycle()
    val cloudPresets by studioViewModel.cloudPresets.collectAsStateWithLifecycle()
    val spectrum by studioViewModel.liveSpectrum.collectAsStateWithLifecycle()
    val isLiveMic by studioViewModel.isLiveMicActive.collectAsStateWithLifecycle()
    val installedPluginSuites by studioViewModel.installedPluginSuites.collectAsStateWithLifecycle()
    val pluginShaderDiagnostics by studioViewModel.pluginShaderDiagnostics.collectAsStateWithLifecycle()
    val installedAePlugins by studioViewModel.installedAePlugins.collectAsStateWithLifecycle()
    val rotoStrokes by studioViewModel.rotoEngine.strokes.collectAsStateWithLifecycle()
    val rotoModelState by studioViewModel.rotoEngine.modelState.collectAsStateWithLifecycle()
    val incomingUri by incomingUniversalUriFlow.collectAsStateWithLifecycle()

    LaunchedEffect(incomingUri) {
        val uri = incomingUri ?: return@LaunchedEffect
        studioViewModel.importUniversalFile(context, uri)
        onConsumeIncomingUniversalUri()
    }

    val pluginSuitesRevision = remember(installedPluginSuites) {
        installedPluginSuites.hashCode()
    }
    val totalPluginEffectsCount = remember(installedPluginSuites) {
        installedPluginSuites.sumOf { it.effects.size }
    }

    val waveformBins = remember(
        wsState.activeProject.layers,
        wsState.activeProject.durationMs,
        wsState.activeProject.bpm
    ) {
        wsState.activeProject.layers
            .firstOrNull { it.extractedWaveform.isNotEmpty() }
            ?.extractedWaveform
            ?: studioViewModel.audioEngine.buildWaveformEnvelope(
                wsState.activeProject.effectiveEditDurationMs(),
                wsState.activeProject.bpm
            )
    }
    val selectedLayer = wsState.activeProject.layers.firstOrNull { it.id == wsState.selectedLayerId }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, studioViewModel) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) {
                studioViewModel.pausePlayback()
                studioViewModel.saveRecentEditsBeforeClosing(silent = true)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    if (wsState.destination != StudioDestination.HOME && wsState.destination != StudioDestination.IMPORT_PREVIEW) {
        BackHandler {
            studioViewModel.navigateBackToParent()
        }
    }

    when (wsState.destination) {
        StudioDestination.HOME -> {
            HomeScreen(
                cloudProjectsState = cloudProjects,
                onCreateProject = studioViewModel::createNewProject,
                onOpenProject = studioViewModel::openProject,
                onDeleteProject = studioViewModel::deleteCloudProject,
                onSignOut = {
                    studioViewModel.pausePlayback()
                    studioViewModel.saveRecentEditsBeforeClosing(silent = true)
                    signOutUser(
                        credentialManager = credentialManager,
                        onSignOutComplete = {},
                        scope = scope
                    )
                },
                defaultPreferences = preferences,
                isImportingFile = wsState.isImportingFile,
                importProgressFraction = wsState.importProgressFraction,
                importProgressLabel = wsState.importProgressLabel,
                onOpenSettings = { studioViewModel.navigateTo(StudioDestination.SETTINGS) },
                onOpenPlugins = { studioViewModel.navigateTo(StudioDestination.AE_PLUGINS) },
                onImportProjectXml = { uri -> studioViewModel.importUniversalFile(context, uri) },
                installedAePlugins = installedAePlugins,
                onImportAePluginUri = studioViewModel::importAePluginFromUri,
                onToggleAePluginEnabled = studioViewModel::toggleAePluginEnabled,
                onToggleAePluginShowInTimeline = studioViewModel::toggleAePluginShowInTimeline,
                onToggleAePluginPinToAddMenu = studioViewModel::toggleAePluginPinToAddMenu,
                onToggleAePluginFileNetwork = studioViewModel::toggleAePluginFileNetwork,
                onUseAePlugin = { plugin -> studioViewModel.openFloatingPlugin(plugin) },
                onRunAePluginProbe = studioViewModel::runAePluginProbe,
                onClearAePluginData = studioViewModel::clearAePluginData,
                onUninstallAePlugin = studioViewModel::uninstallAePlugin,
                depthEngine = studioViewModel.depthEngine,
                activeProjectId = wsState.activeProject.id,
                onImportDepthIntoProjects = studioViewModel::importDepthMapIntoProjects,
                onStatusMessage = studioViewModel::postTransientBanner
            )
        }
        StudioDestination.AE_PLUGINS -> {
            AePluginsBrowserScreen(
                installedPlugins = installedAePlugins,
                onImportPluginUri = studioViewModel::importAePluginFromUri,
                onToggleEnabled = studioViewModel::toggleAePluginEnabled,
                onToggleShowInTimeline = studioViewModel::toggleAePluginShowInTimeline,
                onTogglePinToAddMenu = studioViewModel::toggleAePluginPinToAddMenu,
                onToggleFileNetworkAllowed = studioViewModel::toggleAePluginFileNetwork,
                onUsePlugin = { plugin -> studioViewModel.openFloatingPlugin(plugin) },
                onRunProbe = studioViewModel::runAePluginProbe,
                onClearData = studioViewModel::clearAePluginData,
                onUninstallPlugin = studioViewModel::uninstallAePlugin,
                onStatusMessage = studioViewModel::postTransientBanner,
                onBack = studioViewModel::navigateBackToParent
            )
        }
        StudioDestination.DEPTH_MAP -> {
            val knownProjects = (cloudProjects as? com.example.viewmodel.UiState.Success)?.data.orEmpty()
            DepthMapScreen(
                depthEngine = studioViewModel.depthEngine,
                projects = knownProjects,
                activeProjectId = wsState.activeProject.id,
                onImportDepthIntoProjects = studioViewModel::importDepthMapIntoProjects,
                onStatusMessage = studioViewModel::postTransientBanner
            )
        }
        StudioDestination.IMPORT_PREVIEW -> {
            ImportPreviewScreen(
                viewModel = studioViewModel
            )
        }
        StudioDestination.SETTINGS -> {
            AppSettingsScreen(
                preferences = preferences,
                codecCapabilities = studioViewModel.codecCapabilities,
                installedSuitesCount = installedPluginSuites.size,
                installedPluginEffectsCount = totalPluginEffectsCount,
                installedAePluginsCount = installedAePlugins.size,
                onOpenPluginManager = { studioViewModel.navigateTo(StudioDestination.PLUGIN_MANAGER) },
                onOpenAePlugins = { studioViewModel.navigateTo(StudioDestination.AE_PLUGINS) },
                onImportPluginUri = { uri ->
                    studioViewModel.importUniversalFile(context, uri)
                },
                onUpdatePreferences = studioViewModel::updateStudioPreferences,
                onApplyQualityNow = studioViewModel::setPreviewQuality,
                onClearCaches = studioViewModel::clearAllPlaybackAndMediaCaches,
                onResetDefaults = studioViewModel::resetStudioPreferences,
                onBack = { studioViewModel.navigateTo(StudioDestination.HOME) }
            )
        }
        StudioDestination.PLUGIN_MANAGER -> {
            PluginManagerScreen(
                suites = installedPluginSuites,
                shaderDiagnostics = pluginShaderDiagnostics,
                onImportPackUri = { uri ->
                    studioViewModel.importPluginPackFromUri(context, uri, navigateToManager = false)
                },
                onExportSuiteUri = { suiteId, uri ->
                    studioViewModel.exportPluginSuiteToUri(context, suiteId, uri)
                },
                onToggleSuiteEnabled = studioViewModel::togglePluginSuiteEnabled,
                onRemoveSuite = studioViewModel::removePluginSuite,
                onRestoreStarterSuite = studioViewModel::restoreStarterPluginSuite,
                onCreateCustomPlugin = { effectName, suiteName, author, category, expandBounds, usesMotionBlur, glsl, params, ext ->
                    studioViewModel.createCustomPluginSuite(
                        effectName = effectName,
                        suiteName = suiteName,
                        author = author,
                        category = category,
                        expandBounds = expandBounds,
                        usesNativeMotionBlur = usesMotionBlur,
                        glslSource = glsl,
                        parameters = params,
                        extension = ext
                    )
                },
                onApplyPluginEffect = { definition ->
                    studioViewModel.addEffectToSelectedLayer(definition)
                },
                onBack = studioViewModel::navigateBackToParent
            )
        }
        StudioDestination.EDITOR -> {
            EditorWorkspaceScreen(
                state = wsState,
                audioSpectrum = spectrum,
                preferences = preferences,
                preRenderState = preRenderState,
                onBack = studioViewModel::closeAndSaveActiveProject,
                onSaveCloud = { studioViewModel.saveCurrentProjectToCloud(silent = false) },
                onUndo = studioViewModel::undo,
                onRedo = studioViewModel::redo,
                onNavigateTool = studioViewModel::navigateTo,
                onSetInspectorTab = studioViewModel::setInspectorTab,
                onCollapseToTimeline = studioViewModel::collapseLayerSheetToTimeline,
                onSetTimelineZoom = studioViewModel::setTimelineZoom,
                onTogglePlay = studioViewModel::togglePlayback,
                onToggleLoop = studioViewModel::toggleLoopPlayback,
                onTriggerOrCancelPreRender = { studioViewModel.triggerOrCancelPreRender(context) },
                onSetPreviewQuality = studioViewModel::setPreviewQuality,
                onClearPlaybackCache = studioViewModel::clearAllPlaybackAndMediaCaches,
                onRenderTelemetryUpdate = studioViewModel::updateRenderTelemetry,
                onStepFrame = studioViewModel::stepFrame,
                onScrubPlayhead = studioViewModel::scrubPlayheadInteractive,
                onToggleBeatBookmark = studioViewModel::toggleBeatBookmarkAtPlayhead,
                onJumpBookmark = studioViewModel::jumpToBookmark,
                onJumpKeyframe = studioViewModel::jumpToAdjacentKeyframe,
                onSelectLayer = { id -> studioViewModel.selectLayer(id) },
                onToggleLayerMultiSelection = studioViewModel::toggleLayerMultiSelection,
                onGroupSelectedLayers = studioViewModel::groupSelectedLayers,
                onPreRenderLayerOrGroup = { studioViewModel.triggerSelectedLayerOrGroupPreRender(context) },
                onImportUniversalFile = { uri -> studioViewModel.importUniversalFile(context, uri) },
                onDeselectLayer = studioViewModel::deselectLayer,
                onToggleCameraPreview = studioViewModel::toggleActiveCameraPreview,
                onSelectProperty = studioViewModel::selectProperty,
                onUpdatePropertyValue = studioViewModel::updateSelectedPropertyValue,
                onUpdateExpression = studioViewModel::updatePropertyExpression,
                onDragLayerOnCanvas = studioViewModel::dragSelectedLayerByDelta,
                onScaleAndRotateLayerOnCanvas = studioViewModel::scaleAndRotateSelectedLayerByDelta,
                onResetTransform = studioViewModel::resetSelectedLayerTransform,
                onResetPropertyTarget = studioViewModel::resetPropertyTarget,
                onResetMoveSubMode = studioViewModel::resetMoveSubMode,
                onResetStroke = studioViewModel::resetSelectedLayerStroke,
                onResetShadow = studioViewModel::resetSelectedLayerShadow,
                onResetSingleEffectParam = { fxId, slot, key ->
                    if (selectedLayer != null) {
                        studioViewModel.resetSingleEffectParameter(selectedLayer.id, fxId, slot, key)
                    }
                },
                onToggleKeyframe = { studioViewModel.toggleKeyframeAtPlayhead() },
                onUpdateBezierHandles = studioViewModel::updateBezierHandlesForActiveSegment,
                onSelectEffectForInspection = studioViewModel::selectEffectForInspection,
                onSelectEffectSlot = studioViewModel::selectEffectParamSlot,
                onSelectPluginParamKey = studioViewModel::selectPluginParamKey,
                onUpdateEffectSlotValue = studioViewModel::updateSelectedEffectParamValue,
                onUpdateCustomPluginParamValue = studioViewModel::updateCustomPluginParamValue,
                onUpdateCustomPluginColor = studioViewModel::updateCustomPluginColor,
                onToggleEffectKeyframe = studioViewModel::toggleEffectKeyframeAtPlayhead,
                onJumpEffectKeyframe = studioViewModel::jumpToAdjacentEffectKeyframe,
                onUpdateEffectBezierHandles = studioViewModel::updateEffectBezierHandles,
                onUpdateEffectExpression = studioViewModel::updateEffectParamExpression,
                onMoveEffectOrder = { fxId, up ->
                    if (selectedLayer != null) studioViewModel.moveEffectOrder(selectedLayer.id, fxId, up)
                },
                onDuplicateEffect = { fxId ->
                    if (selectedLayer != null) studioViewModel.duplicateEffectNode(selectedLayer.id, fxId)
                },
                onResetEffect = { fxId ->
                    if (selectedLayer != null) studioViewModel.resetEffectNode(selectedLayer.id, fxId)
                },
                onCopyCurve = studioViewModel::copyActiveBezierCurve,
                onPasteCurve = studioViewModel::pasteBezierCurve,
                onDragKeyframePoint = studioViewModel::dragKeyframePoint,
                onOpenGraphEditor = studioViewModel::openGraphEditor,
                onImportMediaUri = { uri, type -> studioViewModel.importMediaLayer(context, uri, type) },
                onExtractAudioFromVideo = { studioViewModel.extractAudioFromSelectedVideo(context) },
                onAddLayer = studioViewModel::addLayer,
                onAddShapeKind = studioViewModel::addShapeLayerWithKind,
                onAddAdjustmentLayer = studioViewModel::addAdjustmentLayer,
                onCopyLayer = studioViewModel::copySelectedLayer,
                onPasteLayer = studioViewModel::pasteClipboardLayer,
                onCopyEffects = studioViewModel::copySelectedLayerEffects,
                onPasteEffects = studioViewModel::pasteEffectsToSelectedLayer,
                onDuplicateLayer = studioViewModel::duplicateSelectedLayer,
                onMoveLayerZOrder = studioViewModel::moveLayerZOrder,
                onMoveLayerToIndex = studioViewModel::moveLayerToIndex,
                onRenameLayer = studioViewModel::renameLayer,
                onToggleSoloLayer = studioViewModel::toggleSoloLayer,
                onGroupIntoNullRig = studioViewModel::groupVisibleLayersIntoNullRig,
                onExpandLayerToComp = studioViewModel::expandSelectedLayerToComposition,
                onPullStart = studioViewModel::pullStartToPlayhead,
                onTrimStart = studioViewModel::trimSelectedLayerStartToPlayhead,
                onUpdateSpeed = studioViewModel::updateSelectedLayerSpeed,
                onToggleReverse = studioViewModel::toggleSelectedLayerReverse,
                onToggleFreezeFrame = studioViewModel::toggleFreezeFrameAtPlayhead,
                onToggleTimeRemap = studioViewModel::toggleTimeRemapOnSelectedVideo,
                onApplyVelocityPreset = studioViewModel::applyVelocityCurvePreset,
                onUpdateAudioVolume = studioViewModel::updateLayerAudioVolume,
                onToggleMotionBlur = studioViewModel::toggleLayerMotionBlur,
                onToggleClippingMask = studioViewModel::toggleLayerClippingMask,
                onFitOrFillComposition = studioViewModel::fitOrFillSelectedLayerToComposition,
                onSplitLayer = studioViewModel::splitSelectedLayerAtPlayhead,
                onTrimEnd = studioViewModel::trimSelectedLayerEndToPlayhead,
                onPullEnd = studioViewModel::pullEndToPlayhead,
                onFlipLayer = studioViewModel::flipSelectedLayer,
                onDeleteSelectedLayer = studioViewModel::deleteSelectedLayer,
                onToggleLayerVisibility = studioViewModel::toggleLayerVisibility,
                onToggleLayerLock = studioViewModel::toggleLayerLock,
                onSetLayerParent = studioViewModel::setLayerParent,
                onUpdateTextLayer = studioViewModel::updateLayerTextAndFont,
                onUpdateLayerColor = studioViewModel::updateLayerColor,
                onUpdateLayerGradientFill = studioViewModel::updateLayerGradientFill,
                onUpdateAudioEffects = studioViewModel::updateLayerAudioEffects,
                onImportCustomFont = { uri -> studioViewModel.importCustomFont(context, uri) },
                onMoveLayerTimeRange = studioViewModel::moveLayerTimeRange,
                onAutoDetectBassBeats = { sens -> studioViewModel.autoDetectNotableBassBeats(context, sens) },
                onBakeBeatsToKeyframes = studioViewModel::bakeBeatsToPropertyKeyframes,
                onClearAllBeatMarkers = studioViewModel::clearAllBeatMarkers,
                onBindAudioToProperty = studioViewModel::bindAudioReactorToSelectedProperty,
                onUpdateShapeKind = studioViewModel::updateLayerShapeKind,
                onUpdateBlendMode = studioViewModel::updateLayerBlendMode,
                onUpdateBorderShadow = { id, sEn, sW, sHex, sPos, shEn, shBlur, shHex, shX, shY ->
                    studioViewModel.updateLayerBorderAndShadow(
                        layerId = id,
                        strokeEnabled = sEn,
                        strokeWidth = sW,
                        strokeColorHex = sHex,
                        shadowEnabled = shEn,
                        shadowBlur = shBlur,
                        shadowColorHex = shHex,
                        strokePosition = sPos,
                        shadowOffsetX = shX,
                        shadowOffsetY = shY
                    )
                },
                onUpdate3DPrimitive = { id, prim -> studioViewModel.update3DPrimitive(id, prim) },
                onUpdateEffectNode = { updated ->
                    if (selectedLayer != null) {
                        studioViewModel.updateEffectNode(selectedLayer.id, updated)
                    }
                },
                onRemoveEffectNode = { nodeId ->
                    if (selectedLayer != null) {
                        studioViewModel.removeEffectNode(selectedLayer.id, nodeId)
                    }
                },
                onClearAllEffects = {
                    if (selectedLayer != null) {
                        studioViewModel.clearAllEffectsOnLayer(selectedLayer.id)
                    }
                },
                onUpdateProjectSettings = studioViewModel::updateProjectTimingAndResolution,
                onViewportGesture = studioViewModel::updateCanvasViewportGesture,
                onResetViewport = studioViewModel::resetCanvasViewport,
                onSetAddFabExpanded = studioViewModel::setAddFabExpanded,
                onToggleGroupExpanded = studioViewModel::toggleGroupExpanded,
                installedAePlugins = installedAePlugins,
                pluginHostRepository = studioViewModel.pluginHostRepository,
                onOpenAePlugin = { plugin -> studioViewModel.openFloatingPlugin(plugin) },
                onCloseFloatingPlugin = studioViewModel::closeFloatingPlugin,
                onStatusMessage = studioViewModel::postTransientBanner,
                rotoStrokes = rotoStrokes,
                rotoModelState = rotoModelState,
                onAddMask = studioViewModel::addLayerMask,
                onSelectMask = studioViewModel::selectLayerMask,
                onSetPenMaskEditMode = studioViewModel::setPenMaskEditMode,
                onAddPenMaskVertex = studioViewModel::addPenMaskVertex,
                onUpdateMask = studioViewModel::updateLayerMask,
                onRemoveMask = studioViewModel::removeLayerMask,
                onActivateRotoBrush = studioViewModel::activateRotoBrushTool,
                onDismissRotoDownloadDialog = studioViewModel::dismissRotoDownloadDialog,
                onConfirmDownloadRotoModels = studioViewModel::confirmDownloadRotoModels,
                onSetRotoTargetMode = studioViewModel::setRotoTargetMode,
                onSetRotoBrushRadiusDp = studioViewModel::setRotoBrushRadiusDp,
                onAddRotoBrushStroke = studioViewModel::addRotoBrushStroke,
                onRunAutoRoto = studioViewModel::runAutoRotoAtPlayhead,
                onUndoRotoStroke = studioViewModel::undoRotoBrushStroke,
                onRedoRotoStroke = studioViewModel::redoRotoBrushStroke,
                onClearRotoStrokes = studioViewModel::clearRotoBrushStrokes,
                onPropagateRoto = studioViewModel::propagateRotoAcrossLayer,
                onCancelRotoPropagation = studioViewModel::cancelRotoPropagation,
                onRelinkMissingMedia = { layerId, uri ->
                    studioViewModel.relinkMissingLayerMedia(context, layerId, uri)
                }
            )
        }
        StudioDestination.EFFECTS_BROWSER -> {
            EffectsBrowserScreen(
                selectedLayer = selectedLayer,
                playheadMs = wsState.playheadMs,
                audioSpectrum = spectrum,
                searchQuery = wsState.effectSearchQuery,
                selectedCategory = wsState.selectedEffectCategory,
                selectedPluginSubcategory = wsState.selectedPluginCategoryFilter,
                pluginSuitesRevision = pluginSuitesRevision,
                cloudPresetsState = cloudPresets,
                onSearchChange = studioViewModel::setEffectSearchQuery,
                onCategoryChange = studioViewModel::setSelectedEffectCategory,
                onPluginSubcategoryChange = studioViewModel::setSelectedPluginCategoryFilter,
                onOpenPluginManager = { studioViewModel.navigateTo(StudioDestination.PLUGIN_MANAGER) },
                onImportPluginPackUri = { uri ->
                    studioViewModel.importUniversalFile(context, uri)
                },
                onAddEffect = studioViewModel::addEffectToSelectedLayer,
                onUpdateEffectNode = { updated ->
                    if (selectedLayer != null) {
                        studioViewModel.updateEffectNode(selectedLayer.id, updated)
                    }
                },
                onRemoveEffectNode = { nodeId ->
                    if (selectedLayer != null) {
                        studioViewModel.removeEffectNode(selectedLayer.id, nodeId)
                    }
                },
                onSaveChainAsPreset = studioViewModel::saveLayerEffectChainAsPreset,
                onApplyCloudPreset = studioViewModel::applyCloudPresetToSelectedLayer,
                onOpenEffectInspector = studioViewModel::selectEffectForInspection,
                onBack = { studioViewModel.navigateTo(StudioDestination.EDITOR) }
            )
        }
        StudioDestination.GRAPH_EDITOR -> {
            GraphEditorScreen(
                selectedLayer = selectedLayer,
                selectedProperty = wsState.selectedProperty,
                selectedEffectId = wsState.selectedEffectId,
                selectedEffectSlot = wsState.selectedEffectSlot,
                selectedPluginParamKey = wsState.selectedPluginParamKey,
                initialTargetsEffect = wsState.graphTargetsEffect,
                hasCopiedCurve = wsState.hasCopiedCurve,
                playheadMs = wsState.playheadMs,
                onSelectProperty = studioViewModel::selectProperty,
                onSelectEffect = studioViewModel::selectEffectForInspection,
                onSelectEffectSlot = studioViewModel::selectEffectParamSlot,
                onSelectPluginParamKey = studioViewModel::selectPluginParamKey,
                onToggleKeyframe = { isEffect ->
                    if (isEffect) studioViewModel.toggleEffectKeyframeAtPlayhead()
                    else studioViewModel.toggleKeyframeAtPlayhead()
                },
                onJumpKeyframe = { forward, isEffect ->
                    if (isEffect) studioViewModel.jumpToAdjacentEffectKeyframe(forward)
                    else studioViewModel.jumpToAdjacentKeyframe(forward)
                },
                onUpdateBezierHandles = { ox, oy, ix, iy, hold, isEffect ->
                    if (isEffect) studioViewModel.updateEffectBezierHandles(ox, oy, ix, iy, hold)
                    else studioViewModel.updateBezierHandlesForActiveSegment(ox, oy, ix, iy, hold)
                },
                onCopyCurve = studioViewModel::copyActiveBezierCurve,
                onPasteCurve = studioViewModel::pasteBezierCurve,
                onDragKeyframeNode = studioViewModel::dragKeyframePoint,
                onScrubPlayhead = studioViewModel::scrubPlayhead,
                onUpdateExpression = { expr, isEffect ->
                    if (isEffect) studioViewModel.updateEffectParamExpression(expr)
                    else studioViewModel.updatePropertyExpression(expr)
                },
                onBack = { studioViewModel.navigateTo(StudioDestination.EDITOR) }
            )
        }
        StudioDestination.AUDIO_REACTOR -> {
            AudioReactorScreen(
                bpm = wsState.activeProject.bpm,
                sensitivity = wsState.audioSensitivity,
                beatMarkers = wsState.activeProject.beatMarkers,
                spectrum = spectrum,
                waveformBins = waveformBins,
                isLiveMicActive = isLiveMic,
                selectedLayer = selectedLayer,
                selectedProperty = wsState.selectedProperty,
                onSelectProperty = studioViewModel::selectProperty,
                onBindAudioToProperty = studioViewModel::bindAudioReactorToSelectedProperty,
                onRegenerateBeats = studioViewModel::updateProjectBpmAndRegenerateBeats,
                onBakeBeatsToKeyframes = studioViewModel::bakeBeatsToPropertyKeyframes,
                onToggleLiveMic = studioViewModel::toggleLiveMicrophone,
                onBack = { studioViewModel.navigateTo(StudioDestination.EDITOR) }
            )
        }
        StudioDestination.WORKSPACE_3D -> {
            Workspace3DScreen(
                state = wsState,
                audioSpectrum = spectrum,
                onOrbitCamera = studioViewModel::orbit3DCamera,
                onAdd3DLayer = { studioViewModel.addLayer(LayerType.MODEL_3D, "") },
                onSelectPrimitive = studioViewModel::update3DPrimitive,
                onUpdateLayer3DRotation = studioViewModel::updateLayerPropertyByTarget,
                onBack = { studioViewModel.navigateTo(StudioDestination.EDITOR) }
            )
        }
        StudioDestination.EXPORT_PIPELINE -> {
            ExportPipelineScreen(
                project = wsState.activeProject,
                exportState = wsState.exportState,
                preferences = preferences,
                onUpdatePreferences = studioViewModel::updateStudioPreferences,
                onUpdateExportConfig = studioViewModel::updateExportConfiguration,
                onStartExport = studioViewModel::runHardwareMp4Export,
                onExportProjectXml = studioViewModel::exportProjectXmlToUri,
                onImportProjectXml = studioViewModel::importUniversalFile,
                onBack = { studioViewModel.navigateTo(StudioDestination.EDITOR) }
            )
        }
    }

    val pendingPasswordUri by studioViewModel.pendingPasswordArchiveUri.collectAsStateWithLifecycle()
    if (pendingPasswordUri != null) {
        var passwordInput by remember { mutableStateOf("") }
        var passwordVisible by remember { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = { studioViewModel.pendingPasswordArchiveUri.value = null },
            title = {
                Text(
                    text = "Password Protected Archive",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "This AE plugin archive is encrypted. Enter the password to decrypt and install automatically:",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    OutlinedTextField(
                        value = passwordInput,
                        onValueChange = { passwordInput = it },
                        label = { Text("Password") },
                        singleLine = true,
                        visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                        trailingIcon = {
                            IconButton(onClick = { passwordVisible = !passwordVisible }) {
                                Icon(
                                    imageVector = if (passwordVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                    contentDescription = if (passwordVisible) "Hide password" else "Show password"
                                )
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("archive_password_input")
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val uri = pendingPasswordUri ?: return@Button
                        studioViewModel.importPluginWithPassword(context, uri, passwordInput)
                    },
                    modifier = Modifier.testTag("submit_archive_password_button")
                ) {
                    Text("Import")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { studioViewModel.pendingPasswordArchiveUri.value = null },
                    modifier = Modifier.testTag("cancel_archive_password_button")
                ) {
                    Text("Cancel")
                }
            }
        )
    }
}
