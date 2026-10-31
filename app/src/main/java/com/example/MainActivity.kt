package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.credentials.CredentialManager
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.data.model.LayerType
import com.example.data.repository.ProjectRepository
import com.example.ui.auth.AuthScreen
import com.example.ui.auth.authStateFlow
import com.example.ui.auth.signOutUser
import com.example.ui.screens.AudioReactorScreen
import com.example.ui.screens.EditorWorkspaceScreen
import com.example.ui.screens.EffectsBrowserScreen
import com.example.ui.screens.ExportPipelineScreen
import com.example.ui.screens.GraphEditorScreen
import com.example.ui.screens.HomeScreen
import com.example.ui.screens.Workspace3DScreen
import com.example.ui.theme.FramePollutionTheme
import com.example.viewmodel.StudioDestination
import com.example.viewmodel.StudioViewModel
import com.google.firebase.Firebase
import com.google.firebase.auth.auth
import com.google.firebase.firestore.FirebaseFirestore

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            FramePollutionTheme {
                FramePollutionAppRoot()
            }
        }
    }
}

@Composable
fun FramePollutionAppRoot() {
    val auth = Firebase.auth
    val currentUser by auth.authStateFlow().collectAsStateWithLifecycle(initialValue = auth.currentUser)
    val user = currentUser

    if (user == null) {
        AuthScreen()
    } else {
        AuthenticatedStudioShell(currentUserId = user.uid)
    }
}

@Composable
private fun AuthenticatedStudioShell(currentUserId: String) {
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
    val cloudProjects by studioViewModel.cloudProjects.collectAsStateWithLifecycle()
    val cloudPresets by studioViewModel.cloudPresets.collectAsStateWithLifecycle()
    val spectrum by studioViewModel.liveSpectrum.collectAsStateWithLifecycle()
    val isLiveMic by studioViewModel.isLiveMicActive.collectAsStateWithLifecycle()

    val waveformBins = remember(
        wsState.activeProject.layers,
        wsState.activeProject.durationMs,
        wsState.activeProject.bpm
    ) {
        wsState.activeProject.layers
            .firstOrNull { it.extractedWaveform.isNotEmpty() }
            ?.extractedWaveform
            ?: studioViewModel.audioEngine.buildWaveformEnvelope(
                wsState.activeProject.durationMs,
                wsState.activeProject.bpm
            )
    }
    val selectedLayer = wsState.activeProject.layers.firstOrNull { it.id == wsState.selectedLayerId }

    if (wsState.destination != StudioDestination.HOME) {
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
                    signOutUser(
                        credentialManager = credentialManager,
                        onSignOutComplete = {},
                        scope = scope
                    )
                }
            )
        }
        StudioDestination.EDITOR -> {
            EditorWorkspaceScreen(
                state = wsState,
                audioSpectrum = spectrum,
                onBack = { studioViewModel.navigateTo(StudioDestination.HOME) },
                onSaveCloud = { studioViewModel.saveCurrentProjectToCloud(silent = false) },
                onUndo = studioViewModel::undo,
                onRedo = studioViewModel::redo,
                onNavigateTool = studioViewModel::navigateTo,
                onSetInspectorTab = studioViewModel::setInspectorTab,
                onCollapseToTimeline = studioViewModel::collapseLayerSheetToTimeline,
                onSetTimelineZoom = studioViewModel::setTimelineZoom,
                onTogglePlay = studioViewModel::togglePlayback,
                onStepFrame = studioViewModel::stepFrame,
                onScrubPlayhead = studioViewModel::scrubPlayhead,
                onToggleBeatBookmark = studioViewModel::toggleBeatBookmarkAtPlayhead,
                onJumpBookmark = studioViewModel::jumpToBookmark,
                onJumpKeyframe = studioViewModel::jumpToAdjacentKeyframe,
                onSelectLayer = studioViewModel::selectLayer,
                onSelectProperty = studioViewModel::selectProperty,
                onUpdatePropertyValue = studioViewModel::updateSelectedPropertyValue,
                onUpdateExpression = studioViewModel::updatePropertyExpression,
                onDragLayerOnCanvas = studioViewModel::dragSelectedLayerByDelta,
                onResetTransform = studioViewModel::resetSelectedLayerTransform,
                onToggleKeyframe = { studioViewModel.toggleKeyframeAtPlayhead() },
                onUpdateBezierHandles = studioViewModel::updateBezierHandlesForActiveSegment,
                onImportMediaUri = { uri, type -> studioViewModel.importMediaLayer(context, uri, type) },
                onExtractAudioFromVideo = { studioViewModel.extractAudioFromSelectedVideo(context) },
                onAddLayer = studioViewModel::addLayer,
                onAddShapeKind = studioViewModel::addShapeLayerWithKind,
                onCopyLayer = studioViewModel::copySelectedLayer,
                onPasteLayer = studioViewModel::pasteClipboardLayer,
                onCopyEffects = studioViewModel::copySelectedLayerEffects,
                onPasteEffects = studioViewModel::pasteEffectsToSelectedLayer,
                onDuplicateLayer = studioViewModel::duplicateSelectedLayer,
                onMoveLayerZOrder = studioViewModel::moveLayerZOrder,
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
                onUpdateShapeKind = studioViewModel::updateLayerShapeKind,
                onUpdateBlendMode = studioViewModel::updateLayerBlendMode,
                onUpdateBorderShadow = studioViewModel::updateLayerBorderAndShadow,
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
                onUpdateProjectSettings = studioViewModel::updateProjectTimingAndResolution,
                onViewportGesture = studioViewModel::updateCanvasViewportGesture,
                onResetViewport = studioViewModel::resetCanvasViewport
            )
        }
        StudioDestination.EFFECTS_BROWSER -> {
            EffectsBrowserScreen(
                selectedLayer = selectedLayer,
                searchQuery = wsState.effectSearchQuery,
                selectedCategory = wsState.selectedEffectCategory,
                cloudPresetsState = cloudPresets,
                onSearchChange = studioViewModel::setEffectSearchQuery,
                onCategoryChange = studioViewModel::setSelectedEffectCategory,
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
                onBack = { studioViewModel.navigateTo(StudioDestination.EDITOR) }
            )
        }
        StudioDestination.GRAPH_EDITOR -> {
            GraphEditorScreen(
                selectedLayer = selectedLayer,
                selectedProperty = wsState.selectedProperty,
                playheadMs = wsState.playheadMs,
                onSelectProperty = studioViewModel::selectProperty,
                onUpdateBezierHandles = studioViewModel::updateBezierHandlesForActiveSegment,
                onUpdateExpression = studioViewModel::updatePropertyExpression,
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
                onBack = { studioViewModel.navigateTo(StudioDestination.EDITOR) }
            )
        }
        StudioDestination.EXPORT_PIPELINE -> {
            ExportPipelineScreen(
                project = wsState.activeProject,
                exportState = wsState.exportState,
                onUpdateExportConfig = studioViewModel::updateExportConfiguration,
                onStartExport = studioViewModel::runHardwareMp4Export,
                onBack = { studioViewModel.navigateTo(StudioDestination.EDITOR) }
            )
        }
    }
}
