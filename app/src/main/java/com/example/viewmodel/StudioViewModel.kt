package com.example.viewmodel

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.model.AnimatableProperty
import com.example.data.model.AspectRatioPreset
import com.example.data.model.AudioBand
import com.example.data.model.BeatMarker
import com.example.data.model.CustomEffectPresetDocument
import com.example.data.model.EffectNode
import com.example.data.model.EffectParamSlot
import com.example.data.model.Keyframe
import com.example.data.model.Layer
import com.example.data.model.LayerMask
import com.example.data.model.LayerType
import com.example.data.model.MaskShapeType
import com.example.data.model.MaskVertex
import com.example.data.model.Primitive3DType
import com.example.data.model.Project
import com.example.data.model.ProjectPayloadSerializer
import com.example.data.model.PropertyTarget
import com.example.data.preferences.AppPreferencesRepository
import com.example.data.preferences.HardwareCodecCapabilities
import com.example.data.preferences.PreviewQuality
import com.example.data.preferences.StudioPreferences
import com.example.data.repository.LocalRecentProjectStore
import com.example.data.repository.ProjectRepository
import com.example.depthengine.DepthAnythingEngine
import com.example.depthengine.DepthEngine
import com.example.engine.AeEffectPortRegistry
import com.example.engine.AepMediaResolver
import com.example.engine.AnimationEvaluator
import com.example.engine.AssetMatchStatus
import com.example.engine.AudioReactorEngine
import com.example.engine.AudioSpectrumSnapshot
import com.example.engine.CustomFontRegistry
import com.example.engine.EffectCategory
import com.example.engine.EffectDefinition
import com.example.engine.EffectsCatalog
import com.example.engine.EffectThumbnailPreviewer
import com.example.engine.ExportOutcome
import com.example.engine.ExportPipelineEngine
import com.example.engine.FFmpegReverseVideoEngine
import com.example.engine.HardwareVideoPreviewEngine
import com.example.engine.ImportEffectStatus
import com.example.engine.InstalledPluginSuite
import com.example.engine.MediaEngine
import com.example.engine.PendingImportSession
import com.example.engine.PlaybackCacheEngine
import com.example.engine.PluginHostRepository
import com.example.engine.PluginPackManager
import com.example.engine.PluginParamDef
import com.example.engine.PluginParamType
import com.example.engine.PreRenderUiState
import com.example.engine.ProjectXmlEngine
import com.example.engine.ProjectXmlFormat
import com.example.engine.StudioPlaybackClock
import com.example.engine.TimelineAudioController
import com.example.engine.UiOptimizationEngine
import com.example.engine.UniversalImportOutcome
import com.example.engine.UniversalProjectImporter
import com.example.engine.hasPanelUi
import com.example.rotoengine.MobileRotoEngine
import com.example.rotoengine.RotoBrushStroke
import com.example.rotoengine.RotoEngine
import com.example.rotoengine.RotoTargetMode
import com.example.scriptengine.InstalledPlugin
import com.example.scriptengine.PluginInstallStatus
import com.example.ui.screens.DepthImportMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.ByteArrayInputStream
import java.io.File
import java.util.Locale
import java.util.UUID
import java.util.zip.ZipInputStream
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

sealed interface UiState<out T> {
    data object Loading : UiState<Nothing>
    data class Success<T>(val data: T) : UiState<T>
    data class Error(val message: String) : UiState<Nothing>
}

enum class StudioDestination {
    HOME,
    SETTINGS,
    PLUGIN_MANAGER,
    AE_PLUGINS,
    DEPTH_MAP,
    IMPORT_PREVIEW,
    EDITOR,
    EFFECTS_BROWSER,
    GRAPH_EDITOR,
    AUDIO_REACTOR,
    WORKSPACE_3D,
    EXPORT_PIPELINE
}

/**
 * 1:1 Alight Motion + KeyFlow Bottom Sheet Modes inside the Timeline Editor:
 * - TIMELINE: Full unobstructed multitrack timeline + docked bottom studio bar
 * - LAYER_OVERVIEW: Opens when a layer is clicked (trim/split/speed bar + 2x3 action tiles)
 * - SPEED_TIMING: Speed wheel, Reverse Video, Freeze Frame, Time Remap (Velocity), Audio Extract, Volume/Mute
 * - COLOR_FILL: Solid & Swatch Color Fill inspector
 * - BORDER_SHADOW: Stroke width/color & Drop Shadow glow inspector
 * - BLENDING_OPACITY: Opacity tick wheel + Blend Modes + Clipping Mask toggle
 * - MOVE_TRANSFORM: 1:1 Alight Motion Move/Rotate/Scale/Pivot/3D panel with 2D swipe pad & KeyFlow physics
 * - EASING_INLINE: Inline Bezier Easing Curve panel right inside Move & Transform / Time Remap
 * - EDIT_LAYER: Shape Primitive / Kinetic Text & Font / Fit & Fill Composition / 3D Mesh editor
 * - MASK_ROTO: Shape / Pen Masks + Interactive AI Rotoscoping & Background/Object Removal
 * - EFFECTS: Layer Effects stack + GPU Motion Blur Shutter + Teal 'Add Effect' CTA
 */
enum class AmInspectorTab {
    TIMELINE,
    LAYER_OVERVIEW,
    SPEED_TIMING,
    COLOR_FILL,
    BORDER_SHADOW,
    BLENDING_OPACITY,
    MOVE_TRANSFORM,
    EASING_INLINE,
    EDIT_LAYER,
    MASK_ROTO,
    EFFECTS,
    EFFECT_DETAIL,
    EFFECT_EASING
}

data class ExportUiState(
    val isExporting: Boolean = false,
    val progress: Float = 0f,
    val statusText: String = "",
    val lastOutcome: ExportOutcome? = null,
    val errorText: String? = null
)

data class StudioWorkspaceState(
    val destination: StudioDestination = StudioDestination.HOME,
    val activeProject: Project = Project(
        name = "Untitled Project",
        aspectRatioPreset = "16:9",
        width = 1920,
        height = 1080,
        fps = 60,
        durationMs = Project.DEFAULT_NEW_TIMELINE_DURATION_MS,
        layers = emptyList(),
        beatMarkers = emptyList()
    ),
    val selectedLayerId: String? = null,
    val selectedLayerIds: Set<String> = emptySet(),
    val selectedProperty: PropertyTarget = PropertyTarget.POS_X,
    val selectedEffectId: String? = null,
    val selectedEffectSlot: EffectParamSlot = EffectParamSlot.PRIMARY,
    val selectedPluginParamKey: String? = null,
    val graphTargetsEffect: Boolean = false,
    val inspectorTab: AmInspectorTab = AmInspectorTab.TIMELINE,
    val playheadMs: Long = 0L,
    val timelineZoom: Float = 1.0f,
    val isPlaying: Boolean = false,
    val isLoopPlayback: Boolean = true,
    val previewQuality: PreviewQuality = PreviewQuality.HALF,
    val projectRevision: Long = 1L,
    val livePreviewFps: Int = 60,
    val liveFrameTimeMs: Float = 1.2f,
    val canUndo: Boolean = false,
    val canRedo: Boolean = false,
    val hasCopiedLayer: Boolean = false,
    val hasCopiedEffects: Boolean = false,
    val hasCopiedCurve: Boolean = false,
    val soloLayerId: String? = null,
    val activeCameraPreviewEnabled: Boolean = true,
    val canvasZoom: Float = 1.0f,
    val canvasPanX: Float = 0f,
    val canvasPanY: Float = 0f,
    val cameraYawDeg: Float = 26f,
    val cameraPitchDeg: Float = 18f,
    val cameraDistance: Float = 4.2f,
    val effectSearchQuery: String = "",
    val selectedEffectCategory: EffectCategory? = null,
    val selectedPluginCategoryFilter: String? = null,
    val pluginReturnDestination: StudioDestination = StudioDestination.HOME,
    val importReturnDestination: StudioDestination = StudioDestination.HOME,
    val pendingImportSession: PendingImportSession? = null,
    val isImportingFile: Boolean = false,
    val importProgressFraction: Float = 0f,
    val importProgressLabel: String = "",
    val showAllFilesPermissionSheet: Boolean = false,
    val isAddFabExpanded: Boolean = false,
    val activeFloatingPluginId: String? = null,
    val activeFloatingPluginAsSheet: Boolean = false,
    val activeMaskId: String? = null,
    val penMaskEditActive: Boolean = false,
    val rotoBrushActive: Boolean = false,
    val rotoTargetMode: RotoTargetMode = RotoTargetMode.REMOVE_BACKGROUND,
    val rotoBrushRadiusDp: Float = 18f,
    val isRotoPropagating: Boolean = false,
    val rotoPropagateProgress: Float = 0f,
    val showRotoModelDownloadDialog: Boolean = false,
    val audioSensitivity: Float = 0.72f,
    val statusBanner: String? = null,
    val isSavingCloud: Boolean = false,
    val exportState: ExportUiState = ExportUiState()
)

class StudioViewModel(
    private val repository: ProjectRepository? = null,
    private val currentUserId: String = "local_user",
    appContext: Context? = null
) : ViewModel() {

    private val appCtx: Context? = appContext?.applicationContext
    val preferencesRepository = AppPreferencesRepository(appCtx)
    val preferences: StateFlow<StudioPreferences> = preferencesRepository.preferences
    val codecCapabilities: HardwareCodecCapabilities
        get() = preferencesRepository.codecCapabilities
    val preRenderState: StateFlow<PreRenderUiState> = PlaybackCacheEngine.preRenderState
    val installedPluginSuites: StateFlow<List<InstalledPluginSuite>> = PluginPackManager.installedSuites
    val pluginShaderDiagnostics: StateFlow<Map<String, String>> = PluginPackManager.shaderDiagnostics

    val audioEngine = AudioReactorEngine()
    private val timelineAudioController = appCtx?.let { TimelineAudioController(it) }

    val rotoEngine: RotoEngine = MobileRotoEngine(
        modelsDir = File(
            appCtx?.filesDir ?: File(System.getProperty("java.io.tmpdir") ?: ".", "fp_roto"),
            "models/roto"
        )
    )
    val depthEngine: DepthEngine = DepthAnythingEngine(
        modelsDir = File(
            appCtx?.filesDir ?: File(System.getProperty("java.io.tmpdir") ?: ".", "fp_depth"),
            "models/depth"
        )
    )

    private val undoStack = ArrayDeque<Project>()
    private val redoStack = ArrayDeque<Project>()
    private var clipboardLayer: Layer? = null
    private var clipboardEffects: List<EffectNode> = emptyList()
    private var clipboardCurve: FloatArray? = null
    private var clipboardHoldStep: Boolean = false

    @Volatile
    private var hasOpenedOrCreatedSessionProject: Boolean = false

    @Volatile
    private var isActiveProjectDirty: Boolean = false

    private val _localRecentProjects = MutableStateFlow(
        LocalRecentProjectStore.loadProjects(appCtx, currentUserId)
    )
    private val _deletedProjectIds = MutableStateFlow<Set<String>>(emptySet())
    val pendingPasswordArchiveUri = MutableStateFlow<Uri?>(null)

    private val remoteProjectsFlow = (repository?.observeProjects(currentUserId) ?: flowOf(emptyList()))
        .catch { err ->
            Log.w(TAG, "Error observing cloud projects; falling back to local recent store", err)
            emit(emptyList())
        }

    val cloudProjects: StateFlow<UiState<List<Project>>> = combine(
        _localRecentProjects,
        remoteProjectsFlow,
        _deletedProjectIds
    ) { localList, remoteList, deletedIds ->
        val merged = LocalRecentProjectStore.mergeLocalAndCloudProjects(
            localProjects = localList,
            cloudProjects = remoteList,
            deletedIds = deletedIds
        )
        UiState.Success(merged) as UiState<List<Project>>
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000L),
        initialValue = UiState.Success(_localRecentProjects.value)
    )

    val cloudPresets: StateFlow<UiState<List<CustomEffectPresetDocument>>> = (repository?.observePresets(currentUserId) ?: flowOf(emptyList()))
        .map<List<CustomEffectPresetDocument>, UiState<List<CustomEffectPresetDocument>>> { UiState.Success(it) }
        .catch { err ->
            Log.w(TAG, "Error observing presets", err)
            emit(UiState.Error(err.localizedMessage ?: "Unable to sync presets"))
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000L),
            initialValue = UiState.Loading
        )

    private val _state = MutableStateFlow(
        StudioWorkspaceState(
            previewQuality = preferences.value.defaultPreviewQuality,
            isLoopPlayback = preferences.value.defaultLoopPlayback
        )
    )
    val state: StateFlow<StudioWorkspaceState> = _state.asStateFlow()

    /**
     * Single source of truth for timeline duration in ms — derived with `distinctUntilChanged()`
     * so `totalTimeMs` never transiently flickers to 0 during edits.
     */
    val totalTimeMs: StateFlow<Long> = _state
        .map { it.activeProject.effectiveEditDurationMs().coerceAtLeast(100L) }
        .distinctUntilChanged()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = Project.DEFAULT_NEW_TIMELINE_DURATION_MS
        )

    val pluginHostRepository: PluginHostRepository? = appCtx?.let { ctx ->
        PluginHostRepository(
            context = ctx,
            hostBridgeCallbacks = object : PluginHostRepository.HostEditorCallbacks {
                override fun getCurrentProject(): Project = _state.value.activeProject

                override fun getSelectedLayerIds(): Set<String> {
                    val cur = _state.value
                    return when {
                        cur.selectedLayerIds.isNotEmpty() -> cur.selectedLayerIds
                        cur.selectedLayerId != null -> setOf(cur.selectedLayerId)
                        else -> emptySet()
                    }
                }

                override fun getSelectedPropertyKey(): String? {
                    val cur = _state.value
                    if (cur.selectedLayerId == null) return null
                    return when (cur.selectedProperty) {
                        PropertyTarget.POS_X, PropertyTarget.POS_Y, PropertyTarget.POS_Z -> "ADBE Position"
                        PropertyTarget.SCALE -> "ADBE Scale"
                        PropertyTarget.ROTATION_Z -> "ADBE Rotate Z"
                        PropertyTarget.ROTATION_X -> "ADBE Rotate X"
                        PropertyTarget.ROTATION_Y -> "ADBE Rotate Y"
                        PropertyTarget.OPACITY -> "ADBE Opacity"
                        PropertyTarget.PIVOT_X, PropertyTarget.PIVOT_Y -> "ADBE Anchor Point"
                        PropertyTarget.TIME_REMAP -> "ADBE Time Remapping"
                    }
                }

                override fun getPlayheadMs(): Long = currentEffectivePlayheadMs()

                override fun pushUndoSnapshot() {
                    val current = _state.value.activeProject
                    undoStack.addLast(current)
                    while (undoStack.size > 50) undoStack.removeFirst()
                    redoStack.clear()
                }

                override fun applyUpdatedProject(updated: Project) {
                    updateActiveProject(updated, recordUndo = false)
                }
            }
        )
    }

    private val emptyAePluginsFlow = MutableStateFlow<List<InstalledPlugin>>(emptyList())
    val installedAePlugins: StateFlow<List<InstalledPlugin>> =
        pluginHostRepository?.installedPlugins ?: emptyAePluginsFlow.asStateFlow()

    val liveSpectrum: StateFlow<AudioSpectrumSnapshot> = audioEngine.spectrum
    val isLiveMicActive: StateFlow<Boolean> = audioEngine.isLiveMicActive

    private var playbackJob: Job? = null
    private var preRenderJob: Job? = null
    private var idleAutoCacheJob: Job? = null
    private var scrubSettleJob: Job? = null
    private var bannerClearJob: Job? = null
    private var rotoPropagateJob: Job? = null
    private var rotoDownloadJob: Job? = null

    @Volatile
    private var playbackSeekOverrideMs: Long = -1L

    init {
        PluginPackManager.ensureInitialized(appCtx)
        applyPreferencesToEngines(preferences.value)
    }

    private fun applyPreferencesToEngines(prefs: StudioPreferences) {
        val ramMb = prefs.effectiveRamCacheMb
        val syncLimit = if (prefs.lowLatencyAudioSync) {
            165
        } else {
            prefs.audioSyncThresholdMs.coerceIn(175, 320)
        }
        UiOptimizationEngine.setSmoothAnimationsEnabled(prefs.smoothUiAnimations)
        MediaEngine.activePreviewQuality = _state.value.previewQuality
        MediaEngine.fastKeyframeScrubEnabled = prefs.fastKeyframeScrub
        MediaEngine.configureDecoderPool(prefs.decoderPoolSize)
        MediaEngine.configureCacheLimitMb((ramMb / 2).coerceIn(24, 960))
        PlaybackCacheEngine.configureCacheLimitMb(ramMb)
        HardwareVideoPreviewEngine.fastKeyframeScrub = prefs.fastKeyframeScrub
        HardwareVideoPreviewEngine.syncDriftThresholdMs = syncLimit.toLong()
        timelineAudioController?.syncDriftThresholdMs = syncLimit.toLong()
        timelineAudioController?.muteDuringScrub = prefs.muteAudioDuringFastScrub
    }

    fun toggleActiveCameraPreview() {
        var next = true
        _state.update {
            next = !it.activeCameraPreviewEnabled
            it.copy(activeCameraPreviewEnabled = next)
        }
        postTransientBanner(if (next) "Camera View (Active Render)" else "Custom View (Free Canvas)")
    }

    fun updateStudioPreferences(transform: (StudioPreferences) -> StudioPreferences) {
        preferencesRepository.update(transform)
        val updated = preferences.value
        applyPreferencesToEngines(updated)
    }

    fun resetStudioPreferences() {
        preferencesRepository.resetToDefaults()
        val defaults = preferences.value
        _state.update {
            it.copy(
                previewQuality = defaults.defaultPreviewQuality,
                isLoopPlayback = defaults.defaultLoopPlayback
            )
        }
        applyPreferencesToEngines(defaults)
        postTransientBanner("Restored default Studio preferences")
    }

    fun setPreviewQuality(quality: PreviewQuality) {
        MediaEngine.activePreviewQuality = quality
        _state.update { it.copy(previewQuality = quality) }
        postTransientBanner("Preview Quality: ${quality.title} (${quality.badge})")
    }

    fun toggleLoopPlayback() {
        var nextLoop = true
        var durMs = 10_000L
        _state.update {
            nextLoop = !it.isLoopPlayback
            durMs = it.activeProject.effectiveEditDurationMs().coerceAtLeast(200L)
            it.copy(isLoopPlayback = nextLoop)
        }
        StudioPlaybackClock.updateLoopAndDuration(nextLoop, durMs)
        postTransientBanner(if (nextLoop) "Loop Playback ON" else "Loop Playback OFF")
    }

    fun setLoopPlayback(enabled: Boolean) {
        var durMs = 10_000L
        _state.update {
            durMs = it.activeProject.effectiveEditDurationMs().coerceAtLeast(200L)
            it.copy(isLoopPlayback = enabled)
        }
        StudioPlaybackClock.updateLoopAndDuration(enabled, durMs)
    }

    fun triggerOrCancelPreRender(context: Context? = appCtx) {
        idleAutoCacheJob?.cancel()
        idleAutoCacheJob = null
        if (preRenderJob?.isActive == true || preRenderState.value.isPreRendering) {
            preRenderJob?.cancel()
            preRenderJob = null
            val s = _state.value
            PlaybackCacheEngine.notifyProjectEdited(
                newRevision = s.projectRevision,
                quality = s.previewQuality,
                durationMs = s.activeProject.durationMs,
                fps = s.activeProject.fps
            )
            postTransientBanner("Pre-render cancelled")
            return
        }

        val snapshot = _state.value
        val prefs = preferences.value
        pausePlayback()
        postTransientBanner("Pre-rendering @ ${snapshot.previewQuality.badge} (${prefs.preRenderScalePercent}% scale)...")
        preRenderJob = viewModelScope.launch {
            HardwareVideoPreviewEngine.releaseDecodersForOffscreenWork()
            try {
                PlaybackCacheEngine.preRenderComposition(
                    context = context?.applicationContext ?: appCtx,
                    project = snapshot.activeProject,
                    projectRevision = snapshot.projectRevision,
                    quality = snapshot.previewQuality,
                    fpsCap = prefs.previewFpsCap,
                    audioEngine = audioEngine,
                    scalePercent = prefs.preRenderScalePercent,
                    workerThreads = prefs.preRenderThreads
                )
            } finally {
                val cur = _state.value
                prewarmProjectMediaEngines(cur.activeProject, cur.playheadMs)
            }
            if (_state.value.projectRevision == snapshot.projectRevision) {
                val st = preRenderState.value
                val fpsLabel = if (st.throughputFps > 0) " @ ${st.throughputFps} fps" else ""
                postTransientBanner("Pre-render ready (${st.cachedFramesCount} frames$fpsLabel)")
            }
        }
    }

    /**
     * Pre-renders the currently selected single layer OR `LayerType.GROUP` layer (including all recursive
     * child layers and the group's effect stack) into the bounded RAM cache so heavy layers composite in O(1)!
     */
    fun triggerSelectedLayerOrGroupPreRender(context: Context? = appCtx) {
        idleAutoCacheJob?.cancel()
        idleAutoCacheJob = null
        val snapshot = _state.value
        val targetId = snapshot.selectedLayerId
        if (targetId.isNullOrBlank()) {
            triggerOrCancelPreRender(context)
            return
        }
        if (preRenderJob?.isActive == true || preRenderState.value.isPreRendering) {
            preRenderJob?.cancel()
            preRenderJob = null
            PlaybackCacheEngine.notifyProjectEdited(
                newRevision = snapshot.projectRevision,
                quality = snapshot.previewQuality,
                durationMs = snapshot.activeProject.durationMs,
                fps = snapshot.activeProject.fps
            )
            postTransientBanner("Pre-render cancelled")
            return
        }

        val targetLayer = snapshot.activeProject.layers.firstOrNull { it.id == targetId } ?: return
        val prefs = preferences.value
        val kindLabel = if (targetLayer.type == LayerType.GROUP) "Group" else "Layer"
        pausePlayback()
        postTransientBanner("Pre-rendering $kindLabel \"${targetLayer.name}\"...")
        preRenderJob = viewModelScope.launch {
            HardwareVideoPreviewEngine.releaseDecodersForOffscreenWork()
            val count = try {
                PlaybackCacheEngine.preRenderLayerOrGroup(
                    context = context?.applicationContext ?: appCtx,
                    project = snapshot.activeProject,
                    targetLayerId = targetId,
                    projectRevision = snapshot.projectRevision,
                    quality = snapshot.previewQuality,
                    fpsCap = prefs.previewFpsCap,
                    audioEngine = audioEngine,
                    scalePercent = prefs.preRenderScalePercent
                )
            } finally {
                val cur = _state.value
                prewarmProjectMediaEngines(cur.activeProject, cur.playheadMs)
            }
            if (_state.value.projectRevision == snapshot.projectRevision && count > 0) {
                postTransientBanner("Pre-rendered $kindLabel \"${targetLayer.name}\" ($count frames)")
            }
        }
    }

    fun clearAllPlaybackAndMediaCaches() {
        idleAutoCacheJob?.cancel()
        idleAutoCacheJob = null
        preRenderJob?.cancel()
        preRenderJob = null
        PlaybackCacheEngine.clearAll()
        MediaEngine.clearAllMediaCache()
        FFmpegReverseVideoEngine.clearAllCaches(appCtx)
        EffectThumbnailPreviewer.clearThumbnails()
        postTransientBanner("Cleared playback & media RAM caches")
    }

    fun updateRenderTelemetry(fps: Int, frameMs: Float) {
        val cur = _state.value
        if (cur.livePreviewFps != fps || abs(cur.liveFrameTimeMs - frameMs) >= 0.25f) {
            _state.update { it.copy(livePreviewFps = fps, liveFrameTimeMs = frameMs) }
        }
    }

    fun postTransientBanner(message: String?) {
        bannerClearJob?.cancel()
        _state.update { it.copy(statusBanner = message) }
        if (!message.isNullOrBlank()) {
            bannerClearJob = viewModelScope.launch {
                delay(2200L)
                _state.update { cur ->
                    if (cur.statusBanner == message) cur.copy(statusBanner = null) else cur
                }
            }
        }
    }

    /**
     * Persists all recent edits inside the active project BEFORE closing the editor, switching projects,
     * or backgrounding the app — writing atomically to local storage immediately and syncing to Firestore!
     */
    fun saveRecentEditsBeforeClosing(silent: Boolean = true) {
        val current = _state.value.activeProject
        val isUntouchedColdStartBlank = !hasOpenedOrCreatedSessionProject &&
            !isActiveProjectDirty &&
            current.id.isBlank() &&
            current.layers.isEmpty() &&
            current.beatMarkers.isEmpty()
        if (isUntouchedColdStartBlank) return

        if (!isActiveProjectDirty && current.id.isNotBlank() &&
            _localRecentProjects.value.any { it.id == current.id }
        ) {
            return
        }
        saveCurrentProjectToCloud(silent = silent)
    }

    /**
     * Saves all recent edits into the project first and then closes the editor back to Home.
     */
    fun closeAndSaveActiveProject() {
        pausePlayback()
        saveRecentEditsBeforeClosing(silent = true)
        _state.update {
            it.copy(
                destination = StudioDestination.HOME,
                selectedLayerId = null,
                selectedLayerIds = emptySet(),
                inspectorTab = AmInspectorTab.TIMELINE,
                statusBanner = null
            )
        }
    }

    fun navigateTo(destination: StudioDestination) {
        val prevDest = _state.value.destination
        if (destination == StudioDestination.HOME ||
            destination == StudioDestination.SETTINGS ||
            destination == StudioDestination.PLUGIN_MANAGER ||
            destination == StudioDestination.AE_PLUGINS ||
            destination == StudioDestination.DEPTH_MAP
        ) {
            pausePlayback()
        }
        if (destination == StudioDestination.HOME && prevDest != StudioDestination.HOME) {
            saveRecentEditsBeforeClosing(silent = true)
        }
        _state.update { cur ->
            val isPluginDest = destination == StudioDestination.PLUGIN_MANAGER || destination == StudioDestination.AE_PLUGINS
            val wasPluginDest = cur.destination == StudioDestination.PLUGIN_MANAGER || cur.destination == StudioDestination.AE_PLUGINS
            val returnDest = if (isPluginDest && !wasPluginDest) {
                cur.destination
            } else {
                cur.pluginReturnDestination
            }
            cur.copy(
                destination = destination,
                pluginReturnDestination = returnDest,
                statusBanner = null
            )
        }
    }

    fun navigateBackToParent() {
        val current = _state.value
        when {
            current.destination == StudioDestination.IMPORT_PREVIEW -> {
                cancelPendingImportSession()
            }
            current.destination == StudioDestination.PLUGIN_MANAGER ||
                current.destination == StudioDestination.AE_PLUGINS -> {
                _state.update { it.copy(destination = current.pluginReturnDestination) }
            }
            current.destination == StudioDestination.DEPTH_MAP -> {
                depthEngine.releaseSession()
                _state.update { it.copy(destination = StudioDestination.HOME) }
            }
            current.destination == StudioDestination.SETTINGS -> {
                _state.update { it.copy(destination = StudioDestination.HOME) }
            }
            current.destination == StudioDestination.EDITOR &&
                current.inspectorTab == AmInspectorTab.EFFECT_EASING -> {
                _state.update { it.copy(inspectorTab = AmInspectorTab.EFFECT_DETAIL) }
            }
            current.destination == StudioDestination.EDITOR &&
                current.inspectorTab == AmInspectorTab.EFFECT_DETAIL -> {
                _state.update { it.copy(inspectorTab = AmInspectorTab.EFFECTS) }
            }
            current.destination == StudioDestination.EDITOR &&
                current.inspectorTab != AmInspectorTab.TIMELINE &&
                current.inspectorTab != AmInspectorTab.LAYER_OVERVIEW -> {
                _state.update { it.copy(inspectorTab = AmInspectorTab.LAYER_OVERVIEW) }
            }
            current.destination == StudioDestination.EDITOR &&
                current.inspectorTab == AmInspectorTab.LAYER_OVERVIEW -> {
                _state.update { it.copy(selectedLayerId = null, inspectorTab = AmInspectorTab.TIMELINE) }
            }
            current.destination == StudioDestination.EDITOR &&
                current.selectedLayerId != null -> {
                _state.update { it.copy(selectedLayerId = null, inspectorTab = AmInspectorTab.TIMELINE) }
            }
            current.destination == StudioDestination.EDITOR -> {
                closeAndSaveActiveProject()
            }
            current.destination != StudioDestination.HOME -> {
                _state.update { it.copy(destination = StudioDestination.EDITOR) }
            }
        }
    }

    fun openGraphEditor(forEffect: Boolean = false) {
        _state.update {
            it.copy(
                graphTargetsEffect = forEffect,
                destination = StudioDestination.GRAPH_EDITOR,
                statusBanner = null
            )
        }
    }

    fun setInspectorTab(tab: AmInspectorTab) {
        _state.update { current ->
            val selLayer = current.activeProject.layers.firstOrNull { it.id == current.selectedLayerId }
            val nextProp = when (tab) {
                AmInspectorTab.BLENDING_OPACITY -> PropertyTarget.OPACITY
                AmInspectorTab.SPEED_TIMING -> {
                    if (selLayer?.timeRemapEnabled == true) PropertyTarget.TIME_REMAP else current.selectedProperty
                }
                AmInspectorTab.MOVE_TRANSFORM -> {
                    if (current.selectedProperty == PropertyTarget.OPACITY ||
                        current.selectedProperty == PropertyTarget.TIME_REMAP
                    ) PropertyTarget.POS_X
                    else current.selectedProperty
                }
                else -> current.selectedProperty
            }
            val graphIsFx = tab == AmInspectorTab.EFFECT_DETAIL || tab == AmInspectorTab.EFFECT_EASING
            current.copy(
                inspectorTab = tab,
                selectedProperty = nextProp,
                graphTargetsEffect = graphIsFx,
                rotoBrushActive = if (tab == AmInspectorTab.MASK_ROTO) current.rotoBrushActive else false
            )
        }
    }

    fun setTimelineZoom(newZoom: Float) {
        _state.update { it.copy(timelineZoom = newZoom.coerceIn(0.5f, 5.0f)) }
    }

    /**
     * Moves a layer's `[startTimeMs..endTimeMs]` along the timeline when held & dragged,
     * preserving clip duration, shifting keyframes in lockstep, and snapping to playhead/bookmarks!
     */
    fun moveLayerTimeRange(layerId: String, rawNewStartMs: Long, recordUndo: Boolean = false) {
        val s = _state.value
        val proj = s.activeProject
        val layer = proj.layers.firstOrNull { it.id == layerId } ?: return
        if (layer.locked) return

        val clipDur = (layer.endTimeMs - layer.startTimeMs).coerceAtLeast(100L)
        val snapCandidates = buildList {
            add(0L)
            add(s.playheadMs)
            proj.beatMarkers.forEach { add(it.timeMs) }
            proj.layers.forEach { other ->
                if (other.id != layerId) {
                    add(other.startTimeMs)
                    add(other.endTimeMs)
                }
            }
        }
        val snappedStart = snapCandidates.minByOrNull { abs(it - rawNewStartMs) }
            ?.takeIf { abs(it - rawNewStartMs) <= 45L }
            ?: rawNewStartMs

        // Unbounded timeline duration: clips can move freely to ANY positive timestamp!
        val clampedStart = AnimationEvaluator.snapToFrameMs(
            snappedStart.coerceAtLeast(0L),
            proj.fps
        )
        val deltaMs = clampedStart - layer.startTimeMs
        if (deltaMs == 0L) return

        val updatedLayers = proj.layers.map { l ->
            if (l.id == layerId) l.shiftTimeRangeAndKeyframes(deltaMs) else l
        }
        val maxEndAfterMove = updatedLayers.filter { it.visible }.maxOfOrNull { it.endTimeMs }
            ?: updatedLayers.maxOfOrNull { it.endTimeMs }
            ?: (clampedStart + clipDur)
        val newProjDur = max(maxEndAfterMove, 1000L)
        updateActiveProject(
            proj.copy(durationMs = newProjDur, layers = updatedLayers),
            recordUndo = recordUndo
        )
    }

    fun moveLayerToIndex(layerId: String, targetIndex: Int, recordUndo: Boolean = true) {
        val proj = _state.value.activeProject
        val list = proj.layers.toMutableList()
        val fromIdx = list.indexOfFirst { it.id == layerId }
        if (fromIdx < 0) return
        val clampedTarget = targetIndex.coerceIn(0, list.lastIndex)
        if (fromIdx == clampedTarget) return
        val item = list.removeAt(fromIdx)
        list.add(clampedTarget, item)
        updateActiveProject(proj.copy(layers = list), recordUndo = recordUndo)
    }

    fun undo() {
        if (undoStack.isEmpty()) return
        idleAutoCacheJob?.cancel()
        preRenderJob?.cancel()
        val current = _state.value.activeProject
        redoStack.addLast(current)
        val restored = undoStack.removeLast()
        var nextRev = 1L
        var qual = PreviewQuality.HALF
        isActiveProjectDirty = true
        _state.update {
            nextRev = it.projectRevision + 1L
            qual = it.previewQuality
            it.copy(
                activeProject = restored,
                projectRevision = nextRev,
                selectedLayerId = it.selectedLayerId?.takeIf { id -> restored.layers.any { l -> l.id == id } }
                    ?: restored.layers.firstOrNull()?.id,
                canUndo = undoStack.isNotEmpty(),
                canRedo = redoStack.isNotEmpty()
            )
        }
        StudioPlaybackClock.updateLoopAndDuration(
            _state.value.isLoopPlayback,
            restored.effectiveEditDurationMs().coerceAtLeast(200L)
        )
        PlaybackCacheEngine.notifyProjectEdited(nextRev, qual, restored.durationMs, restored.fps)
        prewarmProjectMediaEngines(restored, _state.value.playheadMs)
        scheduleIdleLookaheadAutoCache()
        postTransientBanner("Undo")
    }

    fun redo() {
        if (redoStack.isEmpty()) return
        idleAutoCacheJob?.cancel()
        preRenderJob?.cancel()
        val current = _state.value.activeProject
        undoStack.addLast(current)
        val nextProj = redoStack.removeLast()
        var nextRev = 1L
        var qual = PreviewQuality.HALF
        isActiveProjectDirty = true
        _state.update {
            nextRev = it.projectRevision + 1L
            qual = it.previewQuality
            it.copy(
                activeProject = nextProj,
                projectRevision = nextRev,
                selectedLayerId = it.selectedLayerId?.takeIf { id -> nextProj.layers.any { l -> l.id == id } }
                    ?: nextProj.layers.firstOrNull()?.id,
                canUndo = undoStack.isNotEmpty(),
                canRedo = redoStack.isNotEmpty()
            )
        }
        StudioPlaybackClock.updateLoopAndDuration(
            _state.value.isLoopPlayback,
            nextProj.effectiveEditDurationMs().coerceAtLeast(200L)
        )
        PlaybackCacheEngine.notifyProjectEdited(nextRev, qual, nextProj.durationMs, nextProj.fps)
        prewarmProjectMediaEngines(nextProj, _state.value.playheadMs)
        scheduleIdleLookaheadAutoCache()
        postTransientBanner("Redo")
    }

    /**
     * Creates a 100% CLEAN EMPTY project with ZERO premade layers, ZERO premade effects,
     * and ZERO premade bookmarks — saving any open project's recent edits first!
     */
    fun createNewProject(
        name: String,
        preset: AspectRatioPreset,
        customWidth: Int,
        customHeight: Int,
        fps: Int,
        durationSec: Int,
        bpm: Int,
        backgroundColor: String = "Black",
        durationMsOverride: Long? = null
    ) {
        pausePlayback()
        saveRecentEditsBeforeClosing(silent = true)
        idleAutoCacheJob?.cancel()
        preRenderJob?.cancel()
        undoStack.clear()
        redoStack.clear()
        val w = customWidth.coerceIn(64, 7680)
        val h = customHeight.coerceIn(64, 7680)
        val safeFps = fps.coerceIn(1, 240)
        val durMs = when {
            durationMsOverride != null -> durationMsOverride.coerceIn(500L, 300_000L)
            durationSec <= 0 -> Project.DEFAULT_NEW_TIMELINE_DURATION_MS
            else -> (durationSec.coerceIn(1, 300) * 1000L)
        }
        val safeBpm = bpm.coerceIn(40, 240)
        val preferredCodec = if (preferences.value.preferredVideoCodec == "H.265") "H.265" else "H.264"

        val baseName = name.trim().ifBlank { "New Project" }
        val existingNames = _localRecentProjects.value.map { it.name.trim() }.toSet()
        var resolvedName = baseName
        if (resolvedName in existingNames) {
            val numRegex = """^(.*?)\s*(\d+)$""".toRegex()
            val match = numRegex.find(baseName)
            if (match != null) {
                val prefix = match.groupValues[1].trim()
                var num = match.groupValues[2].toIntOrNull() ?: 1
                do {
                    num++
                    resolvedName = if (prefix.isNotBlank()) "$prefix $num" else "$num"
                } while (resolvedName in existingNames)
            } else {
                var num = 2
                do {
                    resolvedName = "$baseName $num"
                    num++
                } while (resolvedName in existingNames)
            }
        }

        val newProj = Project(
            name = resolvedName,
            aspectRatioPreset = preset.label,
            width = w,
            height = h,
            fps = safeFps,
            durationMs = durMs,
            codec = preferredCodec,
            bpm = safeBpm,
            backgroundColor = backgroundColor,
            layers = emptyList(),
            beatMarkers = emptyList()
        )

        hasOpenedOrCreatedSessionProject = true
        isActiveProjectDirty = true
        var nextRev = 1L
        var qual = PreviewQuality.HALF
        _state.update {
            nextRev = it.projectRevision + 1L
            qual = it.previewQuality
            it.copy(
                activeProject = newProj,
                projectRevision = nextRev,
                selectedLayerId = null,
                selectedLayerIds = emptySet(),
                inspectorTab = AmInspectorTab.TIMELINE,
                playheadMs = 0L,
                canvasZoom = 1.0f,
                canvasPanX = 0f,
                canvasPanY = 0f,
                canUndo = false,
                canRedo = false,
                destination = StudioDestination.EDITOR
            )
        }
        StudioPlaybackClock.setPausedPlayhead(0L)
        StudioPlaybackClock.updateLoopAndDuration(
            _state.value.isLoopPlayback,
            newProj.effectiveEditDurationMs().coerceAtLeast(200L)
        )
        PlaybackCacheEngine.notifyProjectEdited(nextRev, qual, newProj.durationMs, newProj.fps)
        prewarmProjectMediaEngines(newProj, 0L)
        postTransientBanner("Clean project ${w}×${h} @ ${safeFps}fps")
        saveCurrentProjectToCloud(silent = true)
    }

    fun openProject(project: Project) {
        pausePlayback()
        if (_state.value.activeProject.id != project.id || isActiveProjectDirty) {
            saveRecentEditsBeforeClosing(silent = true)
        }
        // Always load the freshest recent edits if a newer snapshot exists in LocalRecentProjectStore
        val freshestProject = if (project.id.isNotBlank()) {
            val localMatch = _localRecentProjects.value.firstOrNull { it.id == project.id }
            val localSec = localMatch?.updatedAt?.seconds ?: -1L
            val argSec = project.updatedAt?.seconds ?: -1L
            if (localMatch != null && localSec >= argSec) localMatch else project
        } else {
            project
        }
        hasOpenedOrCreatedSessionProject = true
        isActiveProjectDirty = false
        idleAutoCacheJob?.cancel()
        preRenderJob?.cancel()
        undoStack.clear()
        redoStack.clear()
        StudioPlaybackClock.setPausedPlayhead(0L)
        StudioPlaybackClock.updateLoopAndDuration(
            _state.value.isLoopPlayback,
            freshestProject.effectiveEditDurationMs().coerceAtLeast(200L)
        )
        audioEngine.sampleTimelineSpectrum(0L, freshestProject.bpm, freshestProject.beatMarkers)
        var nextRev = 1L
        var qual = PreviewQuality.HALF
        _state.update {
            nextRev = it.projectRevision + 1L
            qual = it.previewQuality
            it.copy(
                activeProject = freshestProject,
                projectRevision = nextRev,
                selectedLayerId = null,
                selectedLayerIds = emptySet(),
                inspectorTab = AmInspectorTab.TIMELINE,
                playheadMs = 0L,
                canUndo = false,
                canRedo = false,
                destination = StudioDestination.EDITOR,
                canvasZoom = 1.0f,
                canvasPanX = 0f,
                canvasPanY = 0f,
                statusBanner = null
            )
        }
        PlaybackCacheEngine.notifyProjectEdited(nextRev, qual, freshestProject.durationMs, freshestProject.fps)
        prewarmProjectMediaEngines(freshestProject, 0L)
        prepareReversedVideoLayersAsync(freshestProject)
        scheduleIdleLookaheadAutoCache()
    }

    fun importProjectXmlFromUri(context: Context, uri: Uri) {
        importUniversalFile(context, uri)
    }

    /**
     * Multi-format universal importer supporting `.aep` (After Effects), `.kfproject` (Keyflow by Angkin),
     * `.xml` / `.fpxml`, `.kfpack` / `.fppack` (Effect Packs), and `.ffx` (AE Presets).
     * Runs on a background thread with non-blocking progress updates.
     */
    fun importUniversalFile(context: Context, uri: Uri) {
        pausePlayback()
        val appContext = context.applicationContext
        val cur = _state.value
        val returnDest = if (cur.destination == StudioDestination.IMPORT_PREVIEW) {
            cur.importReturnDestination
        } else {
            cur.destination
        }
        _state.update {
            it.copy(
                isImportingFile = true,
                importProgressFraction = 0.05f,
                importProgressLabel = "Preparing import...",
                importReturnDestination = returnDest
            )
        }
        viewModelScope.launch {
            if (pluginHostRepository != null && shouldRouteUriToAePluginImporter(appContext, uri)) {
                val pluginRes = pluginHostRepository.importPluginFromUri(uri)
                _state.update {
                    it.copy(
                        isImportingFile = false,
                        importProgressFraction = 1f,
                        importProgressLabel = ""
                    )
                }
                pluginRes.fold(
                    onSuccess = { plugin ->
                        postTransientBanner("Installed plugin ${plugin.displayName} v${plugin.version} (${plugin.report.status.name})")
                    },
                    onFailure = { err ->
                        if (err is com.example.scriptengine.PasswordRequiredException ||
                            err.cause is com.example.scriptengine.PasswordRequiredException ||
                            (err.message ?: "").contains("password", ignoreCase = true)
                        ) {
                            pendingPasswordArchiveUri.value = uri
                            postTransientBanner("Archive is password-protected. Please enter password.")
                        } else {
                            postTransientBanner(err.localizedMessage ?: "Plugin import failed")
                        }
                    }
                )
                return@launch
            }

            val result = UniversalProjectImporter.importFromUri(
                context = appContext,
                uri = uri,
                activeProjectForPreset = cur.activeProject.takeIf { cur.destination == StudioDestination.EDITOR },
                selectedLayerIdForPreset = cur.selectedLayerId.takeIf { cur.destination == StudioDestination.EDITOR },
                onProgress = { frac, msg ->
                    _state.update { st ->
                        st.copy(
                            isImportingFile = true,
                            importProgressFraction = frac.coerceIn(0f, 1f),
                            importProgressLabel = msg
                        )
                    }
                }
            )
            result.fold(
                onSuccess = { outcome ->
                    when (outcome) {
                        is UniversalImportOutcome.InstalledPluginPack -> {
                            val suite = outcome.suite
                            invalidateCurrentProjectCache()
                            val skippedNote = if (suite.skippedEffectsReport.isNotEmpty()) {
                                " • Skipped ${suite.skippedEffectsReport.size} broken shader(s)"
                            } else ""
                            _state.update { st ->
                                st.copy(
                                    isImportingFile = false,
                                    importProgressFraction = 1f,
                                    importProgressLabel = "",
                                    selectedEffectCategory = if (st.destination == StudioDestination.EFFECTS_BROWSER) {
                                        EffectCategory.PLUGINS
                                    } else st.selectedEffectCategory
                                )
                            }
                            postTransientBanner("Installed ${suite.name} v${suite.version} (${suite.effects.size} FX$skippedNote)")
                        }

                        is UniversalImportOutcome.OpenImportPreview -> {
                            val session = outcome.session
                            _state.update { st ->
                                st.copy(
                                    isImportingFile = false,
                                    importProgressFraction = 1f,
                                    importProgressLabel = "",
                                    pendingImportSession = session,
                                    showAllFilesPermissionSheet = session.needsStoragePermissionHint,
                                    destination = StudioDestination.IMPORT_PREVIEW
                                )
                            }
                        }
                    }
                },
                onFailure = { err ->
                    _state.update { st ->
                        st.copy(
                            isImportingFile = false,
                            importProgressFraction = 0f,
                            importProgressLabel = ""
                        )
                    }
                    postTransientBanner(err.localizedMessage ?: "Import failed")
                }
            )
        }
    }

    fun importPluginWithPassword(context: Context, uri: Uri, password: String) {
        val repo = pluginHostRepository ?: return
        viewModelScope.launch {
            _state.update {
                it.copy(
                    isImportingFile = true,
                    importProgressFraction = 0.35f,
                    importProgressLabel = "Decrypting archive..."
                )
            }
            val pluginRes = repo.importPluginFromUri(uri, password = password)
            _state.update {
                it.copy(
                    isImportingFile = false,
                    importProgressFraction = 1f,
                    importProgressLabel = ""
                )
            }
            pendingPasswordArchiveUri.value = null
            pluginRes.fold(
                onSuccess = { plugin ->
                    postTransientBanner("Installed plugin ${plugin.displayName} v${plugin.version} (${plugin.report.status.name})")
                },
                onFailure = { err ->
                    postTransientBanner(err.localizedMessage ?: "Decryption failed. Please check password.")
                }
            )
        }
    }

    fun dismissAllFilesPermissionPrompt() {
        _state.update { it.copy(showAllFilesPermissionSheet = false) }
    }

    fun onAllFilesPermissionReturned(context: Context) {
        val cur = _state.value
        val session = cur.pendingImportSession ?: return
        if (!AepMediaResolver.hasAllFilesAccess(context)) {
            _state.update { it.copy(showAllFilesPermissionSheet = false) }
            return
        }
        _state.update {
            it.copy(
                showAllFilesPermissionSheet = false,
                isImportingFile = true,
                importProgressFraction = 0.1f,
                importProgressLabel = "Scanning media folders..."
            )
        }
        val appContext = context.applicationContext
        viewModelScope.launch {
            val rescanned = UniversalProjectImporter.rescanMissingAssets(
                context = appContext,
                session = session,
                onProgress = { frac, msg ->
                    _state.update { st ->
                        st.copy(
                            isImportingFile = true,
                            importProgressFraction = frac.coerceIn(0f, 1f),
                            importProgressLabel = msg
                        )
                    }
                }
            )
            val matchedNow = rescanned.mediaSlots.count { it.status != AssetMatchStatus.MISSING }
            _state.update {
                it.copy(
                    isImportingFile = false,
                    importProgressFraction = 1f,
                    importProgressLabel = "",
                    pendingImportSession = rescanned,
                    showAllFilesPermissionSheet = false
                )
            }
            postTransientBanner("Auto-matched $matchedNow/${rescanned.mediaSlots.size} media asset(s)")
        }
    }

    fun relinkImportMediaSlot(context: Context, slotId: String, pickedUri: Uri) {
        val cur = _state.value
        val session = cur.pendingImportSession ?: return
        val slot = session.mediaSlots.firstOrNull { it.id == slotId } ?: return
        val appContext = context.applicationContext
        viewModelScope.launch {
            val importedRes = withContext(Dispatchers.IO) {
                runCatching {
                    MediaEngine.importMediaUri(
                        context = appContext,
                        pickedUri = pickedUri,
                        type = slot.mediaType
                    )
                }
            }
            val resolvedUri: String
            val newDurMs: Long
            val newW: Int
            val newH: Int
            val waveform: List<Float>
            val displayLabel: String
            val info = importedRes.getOrNull()
            if (info != null) {
                resolvedUri = info.localUri
                newDurMs = if (info.durationMs > 0L) info.durationMs else slot.durationMs
                newW = info.width
                newH = info.height
                waveform = info.waveform
                displayLabel = info.displayName.ifBlank { slot.displayName }
            } else {
                resolvedUri = pickedUri.toString()
                newDurMs = slot.durationMs
                newW = 0
                newH = 0
                waveform = emptyList()
                displayLabel = slot.displayName
            }

            _state.update { st ->
                val curSession = st.pendingImportSession ?: return@update st
                val updatedSlots = curSession.mediaSlots.map { s ->
                    if (s.id != slotId) s
                    else s.copy(
                        displayName = displayLabel,
                        durationMs = newDurMs,
                        status = AssetMatchStatus.MANUAL_RELINKED,
                        resolvedUri = resolvedUri
                    )
                }
                val targetLayerIds = slot.layerIds.toSet()
                val updatedLayers = curSession.previewProject.layers.map { lyr ->
                    if (lyr.id !in targetLayerIds) lyr
                    else lyr.copy(
                        mediaUri = resolvedUri,
                        mediaWidth = if (newW > 0) newW else lyr.mediaWidth,
                        mediaHeight = if (newH > 0) newH else lyr.mediaHeight,
                        sourceDurationMs = if (newDurMs > 0L) newDurMs else lyr.sourceDurationMs,
                        extractedWaveform = if (waveform.isNotEmpty()) waveform else lyr.extractedWaveform
                    )
                }
                st.copy(
                    pendingImportSession = curSession.copy(
                        previewProject = curSession.previewProject.copy(layers = updatedLayers),
                        mediaSlots = updatedSlots
                    )
                )
            }
            postTransientBanner("Relinked \"$displayLabel\"")
        }
    }

    fun replaceImportedEffect(recordId: String, replacementEffectId: String) {
        _state.update { st ->
            val session = st.pendingImportSession ?: return@update st
            val record = session.effectRecords.firstOrNull { it.id == recordId } ?: return@update st
            val newNode = AeEffectPortRegistry.createReplacementEffectNode(replacementEffectId)
            val updatedLayers = session.previewProject.layers.map { lyr ->
                if (lyr.id != record.layerId) lyr
                else {
                    val existingIdx = if (record.effectNodeId != null) {
                        lyr.effects.indexOfFirst { it.id == record.effectNodeId }
                    } else -1
                    val nextEffects = if (existingIdx >= 0) {
                        lyr.effects.toMutableList().apply { set(existingIdx, newNode) }
                    } else {
                        lyr.effects + newNode
                    }
                    lyr.copy(effects = nextEffects)
                }
            }
            val updatedRecords = session.effectRecords.map { rec ->
                if (rec.id != recordId) rec
                else rec.copy(
                    status = ImportEffectStatus.REPLACED,
                    mappedEffectId = replacementEffectId,
                    mappedEffectName = newNode.displayName,
                    effectNodeId = newNode.id
                )
            }
            st.copy(
                pendingImportSession = session.copy(
                    previewProject = session.previewProject.copy(layers = updatedLayers),
                    effectRecords = updatedRecords
                )
            )
        }
    }

    fun confirmPendingImportSession() {
        val session = _state.value.pendingImportSession ?: return
        if (session.isPresetForActiveProject && session.targetPresetLayerId != null) {
            val presetLayer = session.previewProject.layers.firstOrNull { it.id == session.targetPresetLayerId }
                ?: session.previewProject.layers.firstOrNull()
            val importedFx = presetLayer?.effects.orEmpty()
            val curProj = _state.value.activeProject
            val updatedLayers = curProj.layers.map { lyr ->
                if (lyr.id == session.targetPresetLayerId) {
                    lyr.copy(effects = lyr.effects + importedFx.map { it.copy(id = UUID.randomUUID().toString()) })
                } else lyr
            }
            _state.update {
                it.copy(
                    pendingImportSession = null,
                    showAllFilesPermissionSheet = false,
                    destination = StudioDestination.EDITOR
                )
            }
            updateActiveProject(
                curProj.copy(layers = updatedLayers),
                selectLayerId = session.targetPresetLayerId,
                recordUndo = true,
                banner = "Applied preset \"${session.sourceFileName}\" (${importedFx.size} FX)"
            )
            return
        }

        _state.update {
            it.copy(
                pendingImportSession = null,
                showAllFilesPermissionSheet = false
            )
        }
        openProject(session.previewProject)
        postTransientBanner(
            "Opened \"${session.previewProject.name}\" (${session.previewProject.width}×${session.previewProject.height} @ ${session.previewProject.fps}fps)"
        )
        saveCurrentProjectToCloud(silent = true)
    }

    fun cancelPendingImportSession() {
        val returnDest = _state.value.importReturnDestination.let {
            if (it == StudioDestination.IMPORT_PREVIEW) StudioDestination.HOME else it
        }
        _state.update {
            it.copy(
                pendingImportSession = null,
                showAllFilesPermissionSheet = false,
                destination = returnDest
            )
        }
    }

    fun exportProjectXmlToUri(context: Context, uri: Uri, format: ProjectXmlFormat) {
        pausePlayback()
        val projectSnapshot = _state.value.activeProject
        postTransientBanner("Exporting ${format.extension}...")
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                ProjectXmlEngine.exportToUri(context.applicationContext, projectSnapshot, uri, format)
            }
            result.fold(
                onSuccess = {
                    val label = if (format == ProjectXmlFormat.FRAME_POLLUTION_NATIVE) {
                        "Exported \"${projectSnapshot.name}.fpxml\" (Full Engine + Plugins)"
                    } else {
                        "Exported \"${projectSnapshot.name}.xml\" (Alight Motion)"
                    }
                    postTransientBanner(label)
                },
                onFailure = { err ->
                    postTransientBanner("XML export failed: ${err.localizedMessage ?: "Write error"}")
                }
            )
        }
    }

    fun deleteCloudProject(projectId: String) {
        if (projectId.isBlank()) return
        _deletedProjectIds.update { it + projectId }
        LocalRecentProjectStore.deleteProject(appCtx, currentUserId, projectId)
        _localRecentProjects.update { list -> list.filterNot { it.id == projectId } }
        val repo = repository ?: return
        viewModelScope.launch {
            val res = repo.deleteProject(projectId)
            res.onFailure { e ->
                postTransientBanner(e.localizedMessage ?: "Delete failed")
            }
        }
    }

    fun saveCurrentProjectToCloud(silent: Boolean = false) {
        val current = _state.value.activeProject
        // 1. Synchronous atomic local save so closing/reopening NEVER loses recent edits!
        val persistedLocal = LocalRecentProjectStore.upsertProject(appCtx, currentUserId, current)
        hasOpenedOrCreatedSessionProject = true
        isActiveProjectDirty = false
        _deletedProjectIds.update { it - persistedLocal.id }
        _localRecentProjects.update { prev ->
            (listOf(persistedLocal) + prev.filterNot { it.id == persistedLocal.id })
                .sortedByDescending { it.updatedAt?.seconds ?: 0L }
        }
        _state.update {
            if (it.activeProject.id == current.id || it.activeProject.id.isBlank()) {
                it.copy(activeProject = persistedLocal)
            } else {
                it
            }
        }

        // 2. Cloud Firestore sync (when authenticated repository is attached)
        val repo = repository
        if (repo == null) {
            if (!silent) postTransientBanner("Saved \"${persistedLocal.name}\"")
            return
        }

        _state.update { it.copy(isSavingCloud = true) }
        val wasOriginallyCreatedOnCloud = current.createdAt != null
        viewModelScope.launch {
            val result = if (!wasOriginallyCreatedOnCloud) {
                repo.createProject(persistedLocal).map { newId ->
                    persistedLocal.copy(id = newId, userId = currentUserId)
                }
            } else {
                repo.updateProject(persistedLocal).map { persistedLocal }
            }

            result.fold(
                onSuccess = { saved ->
                    _state.update { st ->
                        if (st.activeProject.id == saved.id) {
                            st.copy(activeProject = saved, isSavingCloud = false)
                        } else {
                            st.copy(isSavingCloud = false)
                        }
                    }
                    if (!silent) postTransientBanner("Saved \"${saved.name}\"")
                },
                onFailure = { err ->
                    _state.update { it.copy(isSavingCloud = false) }
                    if (!silent) postTransientBanner(err.localizedMessage ?: "Saved locally (cloud sync pending)")
                }
            )
        }
    }

    fun togglePlayback() {
        if (_state.value.isPlaying) pausePlayback() else startPlayback()
    }

    /**
     * Zero-Lag Hardware-Clocked Playback Loop:
     * - OpenGL ES 2.0 renderer runs unlocked at native 60fps VSync (`RENDERMODE_CONTINUOUSLY`),
     *   streaming forward video directly via `ExoPlayer -> SurfaceTexture (GL_TEXTURE_EXTERNAL_OES)`
     *   and blitting pre-rendered frames in O(1).
     * - Locks playhead to the hardware audio DAC master clock (embedded video audio or standalone audio)
     *   with ZERO per-frame `seekTo()` thrashing, eliminating audio crackles and sync drift!
     * - Ticks Compose UI playhead state at ~30 Hz (`33ms`) so timeline UI never starves GPU rendering.
     */
    private fun currentEffectivePlayheadMs(): Long {
        val s = _state.value
        return if (s.isPlaying && StudioPlaybackClock.isPlaying()) {
            AnimationEvaluator.snapToFrameMs(StudioPlaybackClock.samplePlayheadMs(), s.activeProject.fps)
        } else {
            s.playheadMs.coerceAtLeast(0L)
        }
    }

    private fun startPlayback() {
        playbackJob?.cancel()
        idleAutoCacheJob?.cancel()
        MediaEngine.isRapidScrubbing = false

        val curState = _state.value
        val initialProj = curState.activeProject
        val durInitial = initialProj.effectiveEditDurationMs().coerceAtLeast(200L)
        val startPlayhead = if (curState.playheadMs >= durInitial - 25L) {
            0L
        } else {
            curState.playheadMs.coerceAtLeast(0L)
        }
        StudioPlaybackClock.start(
            initialPlayheadMs = startPlayhead,
            durationMs = durInitial,
            isLooping = curState.isLoopPlayback
        )
        _state.update { it.copy(isPlaying = true, playheadMs = startPlayhead) }

        // Start hardware ExoPlayer audio & OES video immediately once on exact start frame
        timelineAudioController?.syncPlayback(
            project = initialProj,
            playheadMs = startPlayhead,
            isPlaying = true,
            forceExactSeek = true
        )
        appCtx?.let { ctx ->
            HardwareVideoPreviewEngine.syncProjectVideos(
                context = ctx,
                project = initialProj,
                playheadMs = startPlayhead,
                isPlaying = true,
                rapidScrubbing = false,
                forceExactSeek = true
            )
        }

        playbackSeekOverrideMs = -1L
        playbackJob = viewModelScope.launch {
            // Start tickCounter = 1 so we do NOT immediately re-sync/double-seek on t=0 right after startPlayback()!
            var tickCounter = 1
            while (isActive && _state.value.isPlaying) {
                val snapState = _state.value
                val proj = snapState.activeProject
                val dur = proj.effectiveEditDurationMs().coerceAtLeast(200L)
                StudioPlaybackClock.updateLoopAndDuration(snapState.isLoopPlayback, dur)

                val overrideSeek = playbackSeekOverrideMs
                if (overrideSeek >= 0L) {
                    playbackSeekOverrideMs = -1L
                }

                // Sample monotonic StudioPlaybackClock first; ONLY wrap/stop when the clock itself genuinely reaches dur!
                val clockSampleMs = StudioPlaybackClock.samplePlayheadMs()
                val naturalEndOrWrap = StudioPlaybackClock.consumeNaturalEndOrWrapFlag()
                var wrappedLoopThisTick = false

                if (naturalEndOrWrap) {
                    if (snapState.isLoopPlayback) {
                        wrappedLoopThisTick = true
                    } else {
                        val endSnapped = AnimationEvaluator.snapToFrameMs(dur, proj.fps)
                        StudioPlaybackClock.pause(endSnapped)
                        audioEngine.sampleTimelineSpectrum(endSnapped, proj.bpm, proj.beatMarkers)
                        timelineAudioController?.pauseAll()
                        HardwareVideoPreviewEngine.pauseAll()
                        _state.update { it.copy(playheadMs = endSnapped, isPlaying = false) }
                        break
                    }
                }

                val masterClockMs = if (!wrappedLoopThisTick && tickCounter >= 2) {
                    HardwareVideoPreviewEngine.queryMasterCompositionTimeMs(proj, clockSampleMs)
                        ?: timelineAudioController?.queryMasterAudioClockMs(proj, clockSampleMs)
                } else {
                    null
                }

                val currentMs = if (masterClockMs != null) {
                    StudioPlaybackClock.reconcileWithHardwareClock(masterClockMs)
                } else {
                    clockSampleMs
                }

                val snapped = AnimationEvaluator.snapToFrameMs(currentMs, proj.fps)
                audioEngine.sampleTimelineSpectrum(snapped, proj.bpm, proj.beatMarkers)

                if (wrappedLoopThisTick || tickCounter % 6 == 0) {
                    timelineAudioController?.syncPlayback(
                        project = proj,
                        playheadMs = currentMs,
                        isPlaying = true,
                        forceExactSeek = wrappedLoopThisTick
                    )
                    appCtx?.let { ctx ->
                        HardwareVideoPreviewEngine.syncProjectVideos(
                            context = ctx,
                            project = proj,
                            playheadMs = currentMs,
                            isPlaying = true,
                            rapidScrubbing = false,
                            forceExactSeek = wrappedLoopThisTick
                        )
                    }
                }

                _state.update { if (it.playheadMs != snapped) it.copy(playheadMs = snapped) else it }

                // Bounded RAM pressure guard: evict far-away cached frames during long playback sessions
                if (tickCounter % 30 == 0) {
                    PlaybackCacheEngine.evictFramesOutsideWindow(
                        projectRevision = snapState.projectRevision,
                        quality = snapState.previewQuality,
                        centerTimeMs = snapped
                    )
                }

                // Only prefetch bitmap video frames for nonlinear (un-proxied Reverse / Time-Remap) clips
                if (appCtx != null && tickCounter % 8 == 0 &&
                    proj.layers.any { it.type == LayerType.VIDEO && !HardwareVideoPreviewEngine.isEligibleForLiveOesStream(it) }
                ) {
                    MediaEngine.prefetchUpcomingVideoFrames(appCtx, proj, snapped, audioEngine.spectrum.value)
                }
                tickCounter++

                delay(33L)
            }
        }
    }

    fun pausePlayback() {
        playbackJob?.cancel()
        playbackJob = null
        playbackSeekOverrideMs = -1L
        val cur = _state.value
        val exactPausedMs = if (cur.isPlaying) {
            AnimationEvaluator.snapToFrameMs(StudioPlaybackClock.samplePlayheadMs(), cur.activeProject.fps)
        } else {
            cur.playheadMs
        }
        StudioPlaybackClock.pause(exactPausedMs)
        timelineAudioController?.pauseAll()
        HardwareVideoPreviewEngine.pauseAll()
        if (cur.isPlaying || cur.playheadMs != exactPausedMs) {
            _state.update { it.copy(isPlaying = false, playheadMs = exactPausedMs) }
        }
        appCtx?.let { ctx ->
            HardwareVideoPreviewEngine.syncProjectVideos(
                context = ctx,
                project = cur.activeProject,
                playheadMs = exactPausedMs,
                isPlaying = false,
                rapidScrubbing = false,
                forceExactSeek = true
            )
        }
        scheduleIdleLookaheadAutoCache()
    }

    fun scrubPlayhead(targetMs: Long) {
        val cur = _state.value
        val proj = cur.activeProject
        val dur = proj.effectiveEditDurationMs().coerceAtLeast(100L)
        // Single ms source of truth: clamp playhead to 0L..totalTimeMs
        val clamped = targetMs.coerceIn(0L, dur)
        val snapped = AnimationEvaluator.snapToFrameMs(clamped, proj.fps).coerceIn(0L, dur)

        if (cur.isPlaying) {
            // Keep active playback running seamlessly on the SAME clock without spawning a second playback!
            playbackSeekOverrideMs = snapped
            StudioPlaybackClock.start(
                initialPlayheadMs = snapped,
                durationMs = dur,
                isLooping = cur.isLoopPlayback
            )
            audioEngine.sampleTimelineSpectrum(snapped, proj.bpm, proj.beatMarkers)
            _state.update { it.copy(playheadMs = snapped) }
            timelineAudioController?.syncPlayback(
                project = proj,
                playheadMs = snapped,
                isPlaying = true,
                forceExactSeek = true
            )
            appCtx?.let { ctx ->
                HardwareVideoPreviewEngine.syncProjectVideos(
                    context = ctx,
                    project = proj,
                    playheadMs = snapped,
                    isPlaying = true,
                    rapidScrubbing = MediaEngine.isRapidScrubbing,
                    forceExactSeek = true
                )
            }
            return
        }

        StudioPlaybackClock.setPausedPlayhead(snapped)
        audioEngine.sampleTimelineSpectrum(snapped, proj.bpm, proj.beatMarkers)
        _state.update { it.copy(playheadMs = snapped) }
        appCtx?.let { ctx ->
            HardwareVideoPreviewEngine.syncProjectVideos(
                context = ctx,
                project = proj,
                playheadMs = snapped,
                isPlaying = false,
                rapidScrubbing = MediaEngine.isRapidScrubbing
            )
            proj.layers.forEach { layer ->
                if (layer.visible && layer.type == LayerType.VIDEO &&
                    !HardwareVideoPreviewEngine.isEligibleForLiveOesStream(layer) &&
                    snapped in layer.startTimeMs..layer.endTimeMs
                ) {
                    val srcMs = AnimationEvaluator.evaluateSourceMediaTimeMs(layer, snapped, audioEngine.spectrum.value)
                    MediaEngine.warmNonlinearVideoClipAsync(ctx, layer, focusSourceMs = srcMs)
                }
            }
        }
    }

    fun scrubPlayheadInteractive(targetMs: Long) {
        MediaEngine.isRapidScrubbing = true
        idleAutoCacheJob?.cancel()
        scrubSettleJob?.cancel()
        scrubSettleJob = viewModelScope.launch {
            delay(90L)
            MediaEngine.isRapidScrubbing = false
            val settled = _state.value
            if (!settled.isPlaying) {
                appCtx?.let { ctx ->
                    HardwareVideoPreviewEngine.syncProjectVideos(
                        context = ctx,
                        project = settled.activeProject,
                        playheadMs = settled.playheadMs,
                        isPlaying = false,
                        rapidScrubbing = false,
                        forceExactSeek = true
                    )
                }
                scheduleIdleLookaheadAutoCache()
            }
        }

        val s = _state.value
        val proj = s.activeProject
        var candidate = targetMs.coerceAtLeast(0L)
        if (preferences.value.snapToBookmarksAndKeys) {
            val nearBookmark = proj.beatMarkers.firstOrNull { abs(it.timeMs - candidate) <= 45L }
            if (nearBookmark != null) {
                candidate = nearBookmark.timeMs
            } else {
                val selLayer = proj.layers.firstOrNull { it.id == s.selectedLayerId }
                val nearKf = selLayer?.propertyFor(s.selectedProperty)?.keyframes
                    ?.firstOrNull { abs(it.timeMs - candidate) <= 40L }
                if (nearKf != null) candidate = nearKf.timeMs
            }
        }
        scrubPlayhead(candidate)
    }

    fun stepFrame(deltaFrames: Int) {
        pausePlayback()
        val proj = _state.value.activeProject
        val frameMs = (1000.0 / proj.fps.coerceIn(1, 240)).toLong().coerceAtLeast(1L)
        scrubPlayhead(_state.value.playheadMs + deltaFrames * frameMs)
    }

    fun toggleBeatBookmarkAtPlayhead() {
        val s = _state.value
        val t = currentEffectivePlayheadMs()
        val proj = s.activeProject
        val existingIdx = proj.beatMarkers.indexOfFirst { abs(it.timeMs - t) <= 45L }
        val updatedBeats = if (existingIdx >= 0) {
            proj.beatMarkers.filterIndexed { idx, _ -> idx != existingIdx }
        } else {
            (proj.beatMarkers + BeatMarker(timeMs = t, strength = 0.95f, band = AudioBand.BASS)).sortedBy { it.timeMs }
        }
        updateActiveProject(
            proj.copy(beatMarkers = updatedBeats),
            recordUndo = true,
            banner = if (existingIdx >= 0) "Bookmark removed" else "Bookmark @ ${AnimationEvaluator.formatSmpteTimecode(t, proj.fps)}"
        )
    }

    /**
     * Context-Aware Transport Backward / Forward Jump (Section 3.2):
     * - When a layer IS selected (`selectedLayerId != null`):
     *   - Backward (`forward == false`): jumps to nearest earlier keyframe across all animated properties + effects
     *     on the selected layer; if none exists before playhead, jumps to `selectedLayer.startTimeMs`!
     *   - Forward (`forward == true`): jumps to nearest later keyframe on the selected layer; if none exists after
     *     playhead, jumps to `selectedLayer.endTimeMs`!
     * - When NO layer is selected:
     *   - Jumps to earlier/later beat bookmark, falling back to `0L` or `effectiveEditDurationMs()`.
     */
    fun jumpToBookmark(forward: Boolean) {
        val s = _state.value
        val proj = s.activeProject
        val editEndMs = proj.effectiveEditDurationMs().coerceAtLeast(100L)
        val selectedLayer = s.selectedLayerId?.let { id -> proj.layers.firstOrNull { it.id == id } }

        val targetMs = if (selectedLayer != null) {
            val startBound = selectedLayer.startTimeMs.coerceIn(0L, editEndMs)
            val endBound = selectedLayer.endTimeMs.coerceIn(0L, editEndMs)
            val layerStops = (selectedLayer.allKeyframeTimes() + listOf(startBound, endBound))
                .distinct()
                .sorted()
            if (forward) {
                layerStops.firstOrNull { it > s.playheadMs + 10L } ?: endBound
            } else {
                layerStops.lastOrNull { it < s.playheadMs - 10L } ?: startBound
            }
        } else {
            val sorted = proj.beatMarkers.sortedBy { it.timeMs }
            if (forward) {
                sorted.firstOrNull { it.timeMs > s.playheadMs + 15L }?.timeMs ?: editEndMs
            } else {
                sorted.lastOrNull { it.timeMs < s.playheadMs - 15L }?.timeMs ?: 0L
            }
        }
        pausePlayback()
        scrubPlayhead(targetMs)
    }

    fun jumpToAdjacentKeyframe(forward: Boolean) {
        val s = _state.value
        val layer = s.activeProject.layers.firstOrNull { it.id == s.selectedLayerId } ?: return
        val kfs = layer.propertyFor(s.selectedProperty).keyframes.sortedBy { it.timeMs }
        if (kfs.isEmpty()) return
        val target = if (forward) {
            kfs.firstOrNull { it.timeMs > s.playheadMs + 10L } ?: kfs.first()
        } else {
            kfs.lastOrNull { it.timeMs < s.playheadMs - 10L } ?: kfs.last()
        }
        pausePlayback()
        scrubPlayhead(target.timeMs)
    }

    fun selectLayer(layerId: String?) {
        if (layerId.isNullOrBlank()) {
            deselectLayer()
            return
        }
        // IMPORTANT: Clicking a layer updates ONLY UI selection/inspector state —
        // it NEVER touches playbackJob, StudioPlaybackClock, ExoPlayer, or Audio!
        _state.update { cur ->
            // Clicking an already-selected layer on the timeline/overview unclicks and unloads its white handles & gizmo!
            if (cur.selectedLayerId == layerId && cur.selectedLayerIds.size <= 1 &&
                (cur.inspectorTab == AmInspectorTab.LAYER_OVERVIEW || cur.inspectorTab == AmInspectorTab.TIMELINE)
            ) {
                cur.copy(
                    selectedLayerId = null,
                    selectedLayerIds = emptySet(),
                    inspectorTab = AmInspectorTab.TIMELINE
                )
            } else {
                val nextTab = if (cur.inspectorTab == AmInspectorTab.TIMELINE) {
                    AmInspectorTab.LAYER_OVERVIEW
                } else {
                    cur.inspectorTab
                }
                cur.copy(
                    selectedLayerId = layerId,
                    selectedLayerIds = setOf(layerId),
                    inspectorTab = nextTab
                )
            }
        }
    }

    /**
     * Toggles a layer into/out of multi-selection (for multi-layer `Group` creation in the middle timeline toolbar).
     */
    fun toggleLayerMultiSelection(layerId: String) {
        if (layerId.isBlank()) return
        _state.update { cur ->
            val baseSet = if (cur.selectedLayerIds.isNotEmpty()) {
                cur.selectedLayerIds.toMutableSet()
            } else {
                cur.selectedLayerId?.let { mutableSetOf(it) } ?: mutableSetOf()
            }
            if (layerId in baseSet) {
                baseSet.remove(layerId)
            } else {
                baseSet.add(layerId)
            }
            val nextPrimary = when {
                layerId in baseSet -> layerId
                else -> baseSet.lastOrNull()
            }
            cur.copy(
                selectedLayerId = nextPrimary,
                selectedLayerIds = baseSet,
                inspectorTab = if (nextPrimary == null) AmInspectorTab.TIMELINE else cur.inspectorTab
            )
        }
    }

    /**
     * Groups all currently selected layers (or the active selected layer) into a new `LayerType.GROUP`
     * container layer spanning `[minStartMs .. maxEndMs]` and sets `parentId = groupLayer.id` on each member!
     */
    fun groupSelectedLayers() {
        val s = _state.value
        val proj = s.activeProject
        val candidateIds = when {
            s.selectedLayerIds.isNotEmpty() -> s.selectedLayerIds
            s.selectedLayerId != null -> setOf(s.selectedLayerId)
            else -> emptySet()
        }
        val members = proj.layers.filter { it.id in candidateIds }
        if (members.isEmpty()) {
            postTransientBanner("Select one or more layers to group")
            return
        }

        val groupCount = proj.layers.count { it.type == LayerType.GROUP } + 1
        val minStart = members.minOf { it.startTimeMs }.coerceAtLeast(0L)
        val maxEnd = members.maxOf { it.endTimeMs }.coerceAtLeast(minStart + 200L)
        val sharedParent = members.map { it.parentId }.distinct().singleOrNull()
        val groupId = "group_${UUID.randomUUID().toString().take(8)}"
        val groupLayer = Layer(
            id = groupId,
            name = "Group $groupCount",
            type = LayerType.GROUP,
            parentId = sharedParent,
            colorHex = "#8B5CF6",
            groupExpanded = true,
            startTimeMs = minStart,
            endTimeMs = maxEnd
        )

        val memberIdSet = members.map { it.id }.toSet()
        val lastMemberIdx = proj.layers.indexOfLast { it.id in memberIdSet }.coerceAtLeast(0)
        val updatedLayers = ArrayList<Layer>(proj.layers.size + 1)
        proj.layers.forEachIndexed { idx, lyr ->
            val updatedMember = if (lyr.id in memberIdSet) {
                lyr.copy(parentId = groupId)
            } else {
                lyr
            }
            updatedLayers.add(updatedMember)
            if (idx == lastMemberIdx) {
                // Place Group container directly above its member layers in draw order
                updatedLayers.add(groupLayer)
            }
        }

        updateActiveProject(
            proj.copy(layers = updatedLayers),
            selectLayerId = groupId,
            recordUndo = true,
            banner = "Grouped ${members.size} layer(s) into ${groupLayer.name}"
        )
        _state.update {
            it.copy(
                selectedLayerId = groupId,
                selectedLayerIds = setOf(groupId),
                inspectorTab = AmInspectorTab.LAYER_OVERVIEW
            )
        }
    }

    fun deselectLayer() {
        _state.update {
            it.copy(
                selectedLayerId = null,
                selectedLayerIds = emptySet(),
                inspectorTab = AmInspectorTab.TIMELINE,
                rotoBrushActive = false
            )
        }
    }

    fun collapseLayerSheetToTimeline() {
        deselectLayer()
    }

    fun selectProperty(target: PropertyTarget) {
        _state.update { it.copy(selectedProperty = target) }
    }

    fun dragSelectedLayerByDelta(dxProj: Float, dyProj: Float) {
        val s = _state.value
        val layerId = s.selectedLayerId ?: return
        val timeMs = currentEffectivePlayheadMs()
        val audio = liveSpectrum.value

        mutateLayer(layerId, recordUndo = false) { layer ->
            if (layer.locked) return@mutateLayer layer
            if (s.selectedProperty == PropertyTarget.PIVOT_X || s.selectedProperty == PropertyTarget.PIVOT_Y) {
                val curPx = AnimationEvaluator.evaluateProperty(layer.pivotX, timeMs, audio)
                val curPy = AnimationEvaluator.evaluateProperty(layer.pivotY, timeMs, audio)
                var nextPx = curPx + dxProj
                var nextPy = curPy + dyProj
                if (abs(nextPx) < 3.5f) nextPx = 0f
                if (abs(nextPy) < 3.5f) nextPy = 0f
                layer.copy(
                    pivotX = applyPropertyEditAtTime(layer.pivotX, nextPx, timeMs),
                    pivotY = applyPropertyEditAtTime(layer.pivotY, nextPy, timeMs)
                )
            } else {
                val curX = AnimationEvaluator.evaluateProperty(layer.posX, timeMs, audio)
                val curY = AnimationEvaluator.evaluateProperty(layer.posY, timeMs, audio)
                // Unbounded Move: never clamp at -1500..1500!
                var nextX = curX + dxProj
                var nextY = curY + dyProj
                if (abs(nextX) < 3.5f) nextX = 0f
                if (abs(nextY) < 3.5f) nextY = 0f
                layer.copy(
                    posX = applyPropertyEditAtTime(layer.posX, nextX, timeMs),
                    posY = applyPropertyEditAtTime(layer.posY, nextY, timeMs)
                )
            }
        }
    }

    /**
     * Direct On-Canvas Bounding-Box Corner Resize & Top-Knob Rotation Gizmo!
     */
    fun scaleAndRotateSelectedLayerByDelta(scalePctDelta: Float, rotDegDelta: Float) {
        val s = _state.value
        val layerId = s.selectedLayerId ?: return
        val timeMs = currentEffectivePlayheadMs()
        val audio = liveSpectrum.value
        mutateLayer(layerId, recordUndo = false) { layer ->
            if (layer.locked) return@mutateLayer layer
            var updated = layer
            if (abs(scalePctDelta) > 0.01f) {
                val curScale = AnimationEvaluator.evaluateProperty(updated.scale, timeMs, audio)
                val nextScale = (curScale + scalePctDelta).coerceIn(PropertyTarget.SCALE.minVal, PropertyTarget.SCALE.maxVal)
                updated = updated.copy(scale = applyPropertyEditAtTime(updated.scale, nextScale, timeMs))
            }
            if (abs(rotDegDelta) > 0.01f) {
                val curRot = AnimationEvaluator.evaluateProperty(updated.rotationZ, timeMs, audio)
                val nextRot = (curRot + rotDegDelta).coerceIn(PropertyTarget.ROTATION_Z.minVal, PropertyTarget.ROTATION_Z.maxVal)
                updated = updated.copy(rotationZ = applyPropertyEditAtTime(updated.rotationZ, nextRot, timeMs))
            }
            updated
        }
    }

    private fun applyPropertyEditAtTime(prop: AnimatableProperty, newValue: Float, timeMs: Long): AnimatableProperty {
        return if (prop.keyframes.isEmpty()) {
            prop.copy(baseValue = newValue)
        } else {
            val idx = prop.keyframes.indexOfFirst { abs(it.timeMs - timeMs) <= 25L }
            val kfs = if (idx >= 0) {
                prop.keyframes.toMutableList().apply { this[idx] = this[idx].copy(value = newValue) }
            } else {
                (prop.keyframes + Keyframe(timeMs = timeMs, value = newValue)).sortedBy { it.timeMs }
            }
            prop.copy(baseValue = newValue, keyframes = kfs)
        }
    }

    /**
     * Instant Non-Blocking Media Import Pipeline for VIDEO, IMAGE, and AUDIO files!
     * - Streams directly from persistable content/file URIs (< 25ms header probe via MediaExtractor).
     * - Immediately mounts the layer on the timeline & pre-warms ExoPlayer OES / Audio decoders.
     * - Computes the 72-bin audio waveform progressively in the background without blocking import!
     */
    fun importMediaLayer(context: Context, uri: Uri, type: LayerType) {
        pausePlayback()
        val appContext = context.applicationContext
        viewModelScope.launch {
            val result = runCatching {
                MediaEngine.importMediaUri(
                    context = appContext,
                    pickedUri = uri,
                    type = type
                )
            }
            result.fold(
                onSuccess = { info ->
                    val s = _state.value
                    val proj = s.activeProject
                    val startMs = s.playheadMs.coerceAtLeast(0L)
                    val clipDuration = when (type) {
                        LayerType.VIDEO, LayerType.AUDIO -> info.durationMs.coerceIn(300L, 1_800_000L)
                        else -> max(proj.effectiveEditDurationMs() - startMs, 4000L)
                    }
                    val targetEndMs = startMs + clipDuration
                    val expandedProjDuration = max(proj.durationMs, targetEndMs)
                    val idxCount = proj.layers.count { it.type == type } + 1

                    val newLayer = when (type) {
                        LayerType.VIDEO -> Layer(
                            name = info.displayName.ifBlank { "Video $idxCount" },
                            type = LayerType.VIDEO,
                            mediaUri = info.localUri,
                            mediaWidth = info.width,
                            mediaHeight = info.height,
                            sourceDurationMs = clipDuration,
                            extractedWaveform = info.waveform,
                            colorHex = "#4F7CFF",
                            startTimeMs = startMs,
                            endTimeMs = targetEndMs
                        )
                        LayerType.IMAGE -> Layer(
                            name = info.displayName.ifBlank { "Image $idxCount" },
                            type = LayerType.IMAGE,
                            mediaUri = info.localUri,
                            mediaWidth = info.width,
                            mediaHeight = info.height,
                            sourceDurationMs = clipDuration,
                            colorHex = "#4F7CFF",
                            startTimeMs = startMs,
                            endTimeMs = targetEndMs
                        )
                        LayerType.AUDIO -> Layer(
                            name = info.displayName.ifBlank { "Audio $idxCount" },
                            type = LayerType.AUDIO,
                            mediaUri = info.localUri,
                            sourceDurationMs = clipDuration,
                            extractedWaveform = info.waveform,
                            colorHex = "#38BDF8",
                            startTimeMs = startMs,
                            endTimeMs = targetEndMs
                        )
                        else -> Layer(
                            name = info.displayName,
                            type = type,
                            mediaUri = info.localUri,
                            startTimeMs = startMs,
                            endTimeMs = targetEndMs
                        )
                    }

                    updateActiveProject(
                        proj.copy(
                            durationMs = expandedProjDuration,
                            layers = proj.layers + newLayer
                        ),
                        selectLayerId = newLayer.id,
                        recordUndo = true,
                        banner = "Imported ${newLayer.name}"
                    )
                    _state.update { it.copy(inspectorTab = AmInspectorTab.LAYER_OVERVIEW) }

                    // Progressively compute container waveform in the background without bumping projectRevision!
                    val shouldLoadWaveform = (type == LayerType.AUDIO) || (type == LayerType.VIDEO && info.hasAudio)
                    if (shouldLoadWaveform && info.waveform.isEmpty()) {
                        val targetLayerId = newLayer.id
                        launch {
                            val waveform = MediaEngine.computeWaveformAsync(appContext, info.localUri)
                            if (waveform.isNotEmpty()) {
                                _state.update { st ->
                                    val hasLayer = st.activeProject.layers.any { it.id == targetLayerId }
                                    if (!hasLayer) st
                                    else st.copy(
                                        activeProject = st.activeProject.copy(
                                            layers = st.activeProject.layers.map { l ->
                                                if (l.id == targetLayerId && l.extractedWaveform.isEmpty()) {
                                                    l.copy(extractedWaveform = waveform)
                                                } else {
                                                    l
                                                }
                                            }
                                        )
                                    )
                                }
                            }
                        }
                    }
                },
                onFailure = { err ->
                    postTransientBanner(err.localizedMessage ?: "Failed to import media")
                }
            )
        }
    }

    /**
     * Video Audio Extraction (1:1 Alight Motion 'Extract Audio' action!):
     * Demuxes the audio track losslessly from the selected VIDEO clip into a standalone .m4a file,
     * mutes the source video clip so audio won't double-play, and spawns a new AUDIO waveform layer!
     */
    fun extractAudioFromSelectedVideo(context: Context) {
        val s = _state.value
        val proj = s.activeProject
        val videoLayer = proj.layers.firstOrNull { it.id == s.selectedLayerId && it.type == LayerType.VIDEO } ?: return
        if (videoLayer.mediaUri.isBlank()) {
            postTransientBanner("Import a video file first to extract audio")
            return
        }
        postTransientBanner("Extracting audio track...")
        viewModelScope.launch {
            val res = MediaEngine.extractAudioFromVideo(context.applicationContext, videoLayer.mediaUri)
            res.fold(
                onSuccess = { extracted ->
                    val curProj = _state.value.activeProject
                    val audioLayer = Layer(
                        name = "${videoLayer.name} (Audio)",
                        type = LayerType.AUDIO,
                        mediaUri = extracted.audioUri,
                        startTimeMs = videoLayer.startTimeMs,
                        endTimeMs = videoLayer.endTimeMs,
                        sourceOffsetMs = videoLayer.sourceOffsetMs,
                        sourceDurationMs = extracted.durationMs.coerceAtLeast(videoLayer.endTimeMs - videoLayer.startTimeMs),
                        speedMultiplier = videoLayer.speedMultiplier,
                        extractedWaveform = extracted.waveform,
                        colorHex = "#00E5FF"
                    )
                    val updatedLayers = buildList {
                        curProj.layers.forEach { l ->
                            if (l.id == videoLayer.id) {
                                // Insert extracted audio layer directly beneath the video layer & mute embedded video audio
                                add(audioLayer)
                                add(l.copy(audioMuted = true))
                            } else {
                                add(l)
                            }
                        }
                    }
                    updateActiveProject(
                        curProj.copy(layers = updatedLayers),
                        selectLayerId = audioLayer.id,
                        recordUndo = true,
                        banner = "Extracted audio to \"${audioLayer.name}\""
                    )
                    _state.update { it.copy(inspectorTab = AmInspectorTab.TIMELINE) }
                },
                onFailure = { err ->
                    postTransientBanner(err.localizedMessage ?: "Video has no extractable audio track")
                }
            )
        }
    }

    fun addAdjustmentLayer() {
        val proj = _state.value.activeProject
        val curTime = _state.value.playheadMs
        val dur = proj.durationMs.coerceAtLeast(1000L)
        val adjCount = proj.layers.count { it.isAdjustmentLayer } + 1
        val layerId = "adj_${UUID.randomUUID().toString().take(8)}"
        val adjLayer = Layer(
            id = layerId,
            name = "Adjustment Layer $adjCount",
            type = LayerType.SHAPE,
            shapeKind = "Rectangle",
            isAdjustmentLayer = true,
            colorHex = "#2196F3",
            startTimeMs = 0L,
            endTimeMs = dur,
            effects = listOf(
                EffectNode(
                    id = "fx_${UUID.randomUUID().toString().take(8)}",
                    effectId = "Copy_Background",
                    displayName = "Copy Background",
                    enabled = true
                )
            )
        )
        val updated = proj.layers + adjLayer
        updateActiveProject(
            proj.copy(layers = updated),
            selectLayerId = layerId,
            recordUndo = true,
            banner = "Added Adjustment Layer"
        )
    }

    fun addLayer(type: LayerType, mediaUri: String = "") {
        addLayerInternal(type = type, shapeKind = "Rounded Rect", mediaUri = mediaUri)
    }

    fun addShapeLayerWithKind(shapeKind: String) {
        addLayerInternal(type = LayerType.SHAPE, shapeKind = shapeKind, mediaUri = "")
    }

    private fun addLayerInternal(type: LayerType, shapeKind: String, mediaUri: String) {
        val s = _state.value
        val proj = s.activeProject
        val count = proj.layers.count { it.type == type } + 1
        val startMs = s.playheadMs.coerceAtLeast(0L)
        val defaultEndMs = startMs + Project.DEFAULT_NEW_CLIP_DURATION_MS
        val newLayer = when (type) {
            LayerType.SHAPE -> Layer(
                name = "$shapeKind $count",
                type = LayerType.SHAPE,
                shapeKind = shapeKind,
                colorHex = "#4F7CFF",
                startTimeMs = startMs,
                endTimeMs = defaultEndMs
            )
            LayerType.TEXT -> Layer(
                name = "Text $count",
                type = LayerType.TEXT,
                textContent = "NEW TEXT",
                colorHex = "#FFFFFF",
                startTimeMs = startMs,
                endTimeMs = defaultEndMs
            )
            LayerType.VIDEO -> Layer(
                name = "Video $count",
                type = LayerType.VIDEO,
                mediaUri = mediaUri,
                colorHex = "#4F7CFF",
                startTimeMs = startMs,
                endTimeMs = defaultEndMs
            )
            LayerType.IMAGE -> Layer(
                name = "Image $count",
                type = LayerType.IMAGE,
                mediaUri = mediaUri,
                colorHex = "#4F7CFF",
                startTimeMs = startMs,
                endTimeMs = defaultEndMs
            )
            LayerType.AUDIO -> Layer(
                name = "Audio $count",
                type = LayerType.AUDIO,
                mediaUri = mediaUri,
                colorHex = "#38BDF8",
                startTimeMs = startMs,
                endTimeMs = defaultEndMs
            )
            LayerType.CAMERA -> Layer(
                name = "Camera $count",
                type = LayerType.CAMERA,
                colorHex = "#4F7CFF",
                startTimeMs = 0L,
                endTimeMs = max(proj.effectiveEditDurationMs(), defaultEndMs)
            )
            LayerType.MODEL_3D -> Layer(
                name = "3D Mesh $count",
                type = LayerType.MODEL_3D,
                primitive3D = Primitive3DType.CUBE,
                mediaUri = mediaUri,
                colorHex = "#4F7CFF",
                startTimeMs = startMs,
                endTimeMs = defaultEndMs
            )
            LayerType.NULL_OBJECT -> Layer(
                name = "Null $count",
                type = LayerType.NULL_OBJECT,
                colorHex = "#EF4444",
                startTimeMs = 0L,
                endTimeMs = max(proj.effectiveEditDurationMs(), defaultEndMs)
            )
            LayerType.GROUP -> Layer(
                name = "Group $count",
                type = LayerType.GROUP,
                colorHex = "#8B5CF6",
                startTimeMs = startMs,
                endTimeMs = defaultEndMs
            )
            LayerType.DRAWING -> Layer(
                name = "Drawing $count",
                type = LayerType.DRAWING,
                colorHex = "#FFFFFF",
                strokeEnabled = true,
                strokeWidth = 6f,
                strokeColorHex = "#FFFFFF",
                startTimeMs = startMs,
                endTimeMs = defaultEndMs
            )
        }
        updateActiveProject(
            proj.copy(durationMs = max(proj.durationMs, newLayer.endTimeMs), layers = proj.layers + newLayer),
            selectLayerId = newLayer.id,
            recordUndo = true,
            banner = "Added ${newLayer.name}"
        )
        _state.update { it.copy(inspectorTab = AmInspectorTab.LAYER_OVERVIEW) }
    }

    fun copySelectedLayer() {
        val s = _state.value
        val source = s.activeProject.layers.firstOrNull { it.id == s.selectedLayerId } ?: return
        clipboardLayer = source
        _state.update { it.copy(hasCopiedLayer = true) }
        postTransientBanner("Copied \"${source.name}\"")
    }

    fun pasteClipboardLayer() {
        val src = clipboardLayer ?: return
        val proj = _state.value.activeProject
        val pasted = src.copy(
            id = UUID.randomUUID().toString(),
            name = "${src.name} Copy",
            effects = src.effects.map { it.copy(id = UUID.randomUUID().toString()) }
        )
        updateActiveProject(
            proj.copy(layers = proj.layers + pasted),
            selectLayerId = pasted.id,
            recordUndo = true,
            banner = "Pasted \"${pasted.name}\""
        )
    }

    fun copySelectedLayerEffects() {
        val s = _state.value
        val source = s.activeProject.layers.firstOrNull { it.id == s.selectedLayerId } ?: return
        clipboardEffects = source.effects.map { it.copy(id = UUID.randomUUID().toString()) }
        _state.update { it.copy(hasCopiedEffects = clipboardEffects.isNotEmpty()) }
        postTransientBanner("Copied ${clipboardEffects.size} effect(s)")
    }

    fun pasteEffectsToSelectedLayer() {
        if (clipboardEffects.isEmpty()) return
        val targetId = _state.value.selectedLayerId ?: return
        val fresh = clipboardEffects.map { it.copy(id = UUID.randomUUID().toString()) }
        mutateLayer(targetId, recordUndo = true) { layer ->
            layer.copy(effects = layer.effects + fresh)
        }
        postTransientBanner("Pasted ${fresh.size} effect(s)")
    }

    fun duplicateSelectedLayer() {
        val s = _state.value
        val proj = s.activeProject
        val source = proj.layers.firstOrNull { it.id == s.selectedLayerId } ?: return
        val copyLayer = source.copy(
            id = UUID.randomUUID().toString(),
            name = "${source.name} Copy",
            effects = source.effects.map { it.copy(id = UUID.randomUUID().toString()) }
        )
        val idx = proj.layers.indexOfFirst { it.id == source.id }.coerceAtLeast(0)
        val updated = proj.layers.toMutableList().apply { add(idx + 1, copyLayer) }
        updateActiveProject(
            proj.copy(layers = updated),
            selectLayerId = copyLayer.id,
            recordUndo = true,
            banner = "Duplicated ${source.name}"
        )
    }

    fun moveLayerZOrder(layerId: String, moveUp: Boolean) {
        val proj = _state.value.activeProject
        val list = proj.layers.toMutableList()
        val idx = list.indexOfFirst { it.id == layerId }
        if (idx < 0) return
        val targetIdx = if (moveUp) idx - 1 else idx + 1
        if (targetIdx !in list.indices) return
        val item = list.removeAt(idx)
        list.add(targetIdx, item)
        updateActiveProject(proj.copy(layers = list), recordUndo = true)
    }

    fun splitSelectedLayerAtPlayhead() {
        val s = _state.value
        val proj = s.activeProject
        val t = s.playheadMs
        val source = proj.layers.firstOrNull { it.id == s.selectedLayerId } ?: return
        if (t <= source.startTimeMs + 80L || t >= source.endTimeMs - 80L) {
            postTransientBanner("Move playhead inside clip to split")
            return
        }
        val elapsedInLeft = ((t - source.startTimeMs) * source.speedMultiplier).toLong().coerceAtLeast(0L)
        val leftPart = source.copy(endTimeMs = t)
        val rightPart = source.copy(
            id = UUID.randomUUID().toString(),
            name = "${source.name} (2)",
            startTimeMs = t,
            sourceOffsetMs = source.sourceOffsetMs + elapsedInLeft,
            effects = source.effects.map { it.copy(id = UUID.randomUUID().toString()) }
        )
        val updated = buildList {
            proj.layers.forEach { l ->
                if (l.id == source.id) {
                    add(leftPart)
                    add(rightPart)
                } else {
                    add(l)
                }
            }
        }
        updateActiveProject(
            proj.copy(layers = updated),
            selectLayerId = rightPart.id,
            recordUndo = true,
            banner = "Split ${source.name}"
        )
    }

    fun pullStartToPlayhead() {
        val s = _state.value
        val layerId = s.selectedLayerId ?: return
        val t = s.playheadMs
        mutateLayer(layerId, recordUndo = true) { layer ->
            val newStart = t.coerceIn(0L, (layer.endTimeMs - 100L).coerceAtLeast(0L))
            layer.copy(startTimeMs = newStart)
        }
    }

    fun pullEndToPlayhead() {
        val s = _state.value
        val layerId = s.selectedLayerId ?: return
        val proj = s.activeProject
        val t = s.playheadMs
        var nextEnd = 0L
        val updatedLayers = proj.layers.map { layer ->
            if (layer.id != layerId) layer
            else {
                nextEnd = t.coerceAtLeast(layer.startTimeMs + 100L)
                layer.copy(endTimeMs = nextEnd)
            }
        }
        val maxEnd = updatedLayers.maxOfOrNull { it.endTimeMs } ?: nextEnd
        updateActiveProject(proj.copy(durationMs = max(maxEnd, 1000L), layers = updatedLayers), recordUndo = true)
    }

    fun trimSelectedLayerStartToPlayhead() {
        val s = _state.value
        val layerId = s.selectedLayerId ?: return
        val t = s.playheadMs
        mutateLayer(layerId, recordUndo = true) { layer ->
            val newStart = t.coerceIn(0L, (layer.endTimeMs - 100L).coerceAtLeast(0L))
            val deltaSource = ((newStart - layer.startTimeMs) * layer.speedMultiplier).toLong()
            layer.copy(
                startTimeMs = newStart,
                sourceOffsetMs = (layer.sourceOffsetMs + deltaSource).coerceAtLeast(0L)
            )
        }
    }

    fun trimSelectedLayerEndToPlayhead() = pullEndToPlayhead()

    fun updateSelectedLayerSpeed(multiplier: Float) {
        val s = _state.value
        val layerId = s.selectedLayerId ?: return
        val proj = s.activeProject
        val safeSpeed = multiplier.coerceIn(0.10f, 8.0f)
        val updatedLayers = proj.layers.map { layer ->
            if (layer.id != layerId) layer
            else {
                val currentSpan = (layer.endTimeMs - layer.startTimeMs).coerceAtLeast(200L)
                val baseSpan = (currentSpan * layer.speedMultiplier).toLong()
                val newSpan = (baseSpan / safeSpeed).toLong().coerceAtLeast(150L)
                val newEnd = layer.startTimeMs + newSpan
                layer.copy(speedMultiplier = safeSpeed, endTimeMs = newEnd)
            }
        }
        val maxEnd = updatedLayers.maxOfOrNull { it.endTimeMs } ?: proj.durationMs
        updateActiveProject(proj.copy(durationMs = max(maxEnd, 1000L), layers = updatedLayers), recordUndo = true)
    }

    /**
     * Video Reverse Toggle (transcodes hardware-forward short-GOP proxy via FFmpegKit for zero-lag 60fps preview & pre-render!).
     */
    fun toggleSelectedLayerReverse() {
        val layerId = _state.value.selectedLayerId ?: return
        var nowRev = false
        mutateLayer(layerId, recordUndo = true) { layer ->
            nowRev = !layer.reversed
            layer.copy(reversed = nowRev)
        }
        if (nowRev) {
            warmSelectedNonlinearVideoLayer(layerId)
            prepareReversedVideoLayersAsync(_state.value.activeProject, notifyWhenReady = true)
            postTransientBanner("Reverse playback enabled (optimizing via FFmpegKit...)")
        } else {
            postTransientBanner("Normal playback direction")
        }
    }

    /**
     * Freeze Frame Toggle at current playhead!
     */
    fun toggleFreezeFrameAtPlayhead() {
        val s = _state.value
        val layer = s.activeProject.layers.firstOrNull { it.id == s.selectedLayerId } ?: return
        val audio = liveSpectrum.value
        val effectivePlayhead = currentEffectivePlayheadMs()
        val freezeVal = if (layer.freezeFrameMs >= 0L) {
            -1L
        } else {
            AnimationEvaluator.evaluateSourceMediaTimeMs(layer, effectivePlayhead, audio)
        }
        mutateLayer(layer.id, recordUndo = true) { it.copy(freezeFrameMs = freezeVal) }
        postTransientBanner(if (freezeVal >= 0L) "Frozen frame @ ${freezeVal}ms" else "Unfroze clip")
    }

    /**
     * Alight Motion + KeyFlow Time Remapping (Velocity Editing):
     * Enables animatable `timeRemap` seconds curve with start & end keyframes so the user can keyframe
     * exact source timestamps or sculpt smooth velocity ramps in the Bezier Graph Editor!
     */
    fun toggleTimeRemapOnSelectedVideo(enabled: Boolean) {
        val s = _state.value
        val layer = s.activeProject.layers.firstOrNull { it.id == s.selectedLayerId } ?: return
        val clipDurSec = ((layer.sourceDurationMs.takeIf { it > 0L } ?: (layer.endTimeMs - layer.startTimeMs))
            .coerceAtLeast(500L)) / 1000f
        mutateLayer(layer.id, recordUndo = true) { cur ->
            val seededRemap = if (enabled && cur.timeRemap.keyframes.size < 2) {
                AnimatableProperty(
                    baseValue = 0f,
                    keyframes = listOf(
                        Keyframe(timeMs = cur.startTimeMs, value = 0f, outTangentX = 0.25f, outTangentY = 0.25f, inTangentX = 0.75f, inTangentY = 0.75f),
                        Keyframe(timeMs = cur.endTimeMs, value = clipDurSec, outTangentX = 0.25f, outTangentY = 0.25f, inTangentX = 0.75f, inTangentY = 0.75f)
                    )
                )
            } else {
                cur.timeRemap
            }
            cur.copy(timeRemapEnabled = enabled, timeRemap = seededRemap)
        }
        if (enabled) {
            warmSelectedNonlinearVideoLayer(layer.id)
            _state.update { it.copy(selectedProperty = PropertyTarget.TIME_REMAP) }
            postTransientBanner("Time Remap active — sculpt velocity curve or add keyframes")
        } else {
            postTransientBanner("Time Remap disabled")
        }
    }

    /**
     * 1-Tap KeyFlow Velocity Curve Presets on `TIME_REMAP`!
     */
    fun applyVelocityCurvePreset(presetKey: String) {
        val s = _state.value
        val layer = s.activeProject.layers.firstOrNull { it.id == s.selectedLayerId } ?: return
        val startT = layer.startTimeMs
        val endT = layer.endTimeMs.coerceAtLeast(startT + 400L)
        val midT = (startT + endT) / 2L
        val durSec = ((layer.sourceDurationMs.takeIf { it > 0L } ?: (endT - startT)).coerceAtLeast(500L)) / 1000f

        val keyframes = when (presetKey) {
            "Smooth Slow-Mo" -> listOf(
                Keyframe(timeMs = startT, value = 0f, outTangentX = 0.12f, outTangentY = 0.65f),
                Keyframe(timeMs = midT, value = durSec * 0.45f, inTangentX = 0.40f, inTangentY = 0.50f, outTangentX = 0.60f, outTangentY = 0.50f),
                Keyframe(timeMs = endT, value = durSec, inTangentX = 0.88f, inTangentY = 0.35f)
            )
            "Velocity Whip" -> listOf(
                Keyframe(timeMs = startT, value = 0f, outTangentX = 0.08f, outTangentY = 0.92f),
                Keyframe(timeMs = endT, value = durSec, inTangentX = 0.18f, inTangentY = 1.0f)
            )
            "Bullet Ramp" -> listOf(
                Keyframe(timeMs = startT, value = 0f, outTangentX = 0.10f, outTangentY = 0.85f),
                Keyframe(timeMs = midT, value = durSec * 0.68f, inTangentX = 0.85f, inTangentY = 0.96f, outTangentX = 0.85f, outTangentY = 0.06f),
                Keyframe(timeMs = endT, value = durSec, inTangentX = 0.20f, inTangentY = 1.0f)
            )
            "Reverse Bounce" -> listOf(
                Keyframe(timeMs = startT, value = 0f, outTangentX = 0.35f, outTangentY = 1.0f),
                Keyframe(timeMs = midT, value = durSec * 0.85f, inTangentX = 0.5f, inTangentY = 1.0f, outTangentX = 0.5f, outTangentY = 0.0f),
                Keyframe(timeMs = endT, value = 0f, inTangentX = 0.65f, inTangentY = 0.0f)
            )
            else -> listOf(
                Keyframe(timeMs = startT, value = 0f),
                Keyframe(timeMs = endT, value = durSec)
            )
        }

        mutateLayer(layer.id, recordUndo = true) { cur ->
            cur.copy(
                timeRemapEnabled = true,
                timeRemap = AnimatableProperty(baseValue = 0f, keyframes = keyframes)
            )
        }
        warmSelectedNonlinearVideoLayer(layer.id)
        _state.update { it.copy(selectedProperty = PropertyTarget.TIME_REMAP) }
        postTransientBanner("Applied $presetKey velocity curve")
    }

    fun updateLayerAudioVolume(layerId: String, volume: Float, muted: Boolean) {
        mutateLayer(layerId, recordUndo = false) {
            it.copy(audioVolume = volume.coerceIn(0f, 2f), audioMuted = muted)
        }
    }

    fun toggleLayerMotionBlur(layerId: String, enabled: Boolean, shutterAngle: Float = 180f) {
        mutateLayer(layerId, recordUndo = true) {
            it.copy(motionBlurEnabled = enabled, shutterAngle = shutterAngle.coerceIn(45f, 360f))
        }
    }

    fun toggleLayerClippingMask(layerId: String) {
        var nowMask = false
        mutateLayer(layerId, recordUndo = true) {
            nowMask = !it.isClippingMask
            it.copy(isClippingMask = nowMask)
        }
        postTransientBanner(if (nowMask) "Clipping Mask ON (masks layers onto alpha)" else "Clipping Mask OFF")
    }

    /**
     * Alight Motion 'Fit to Composition Area' / 'Fill Composition Area' 1-tap sizing!
     */
    fun fitOrFillSelectedLayerToComposition(fill: Boolean) {
        val s = _state.value
        val proj = s.activeProject
        val layer = proj.layers.firstOrNull { it.id == s.selectedLayerId } ?: return
        val projW = proj.width.coerceAtLeast(1).toFloat()
        val projH = proj.height.coerceAtLeast(1).toFloat()

        // Base rendered quad for VIDEO/IMAGE fits 100% (1.0f) of composition without black borders!
        val targetScalePct = when (layer.type) {
            LayerType.VIDEO, LayerType.IMAGE -> {
                val mw = layer.mediaWidth.takeIf { it > 0 }?.toFloat() ?: projW
                val mh = layer.mediaHeight.takeIf { it > 0 }?.toFloat() ?: projH
                val baseFit = min(projW / mw, projH / mh)
                val baseQuadW = (mw * baseFit).coerceAtLeast(1f)
                val baseQuadH = (mh * baseFit).coerceAtLeast(1f)
                val factor = if (fill) {
                    max(projW / baseQuadW, projH / baseQuadH)
                } else {
                    min(projW / baseQuadW, projH / baseQuadH)
                }
                (factor * 100f).coerceIn(10f, 500f)
            }
            else -> if (fill) 200f else 100f
        }

        mutateLayer(layer.id, recordUndo = true) { cur ->
            cur.copy(
                posX = AnimatableProperty(0f),
                posY = AnimatableProperty(0f),
                scale = AnimatableProperty(targetScalePct)
            )
        }
        postTransientBanner(if (fill) "Filled composition area" else "Fitted to composition area")
    }

    fun flipSelectedLayer(horizontal: Boolean) {
        val layerId = _state.value.selectedLayerId ?: return
        mutateLayer(layerId, recordUndo = true) { layer ->
            if (horizontal) layer.copy(flipX = !layer.flipX) else layer.copy(flipY = !layer.flipY)
        }
    }

    /**
     * Resets ONLY the specific active [PropertyTarget] modification (never wipes other transforms!).
     */
    fun resetPropertyTarget(target: PropertyTarget = _state.value.selectedProperty) {
        val layerId = _state.value.selectedLayerId ?: return
        mutateLayer(layerId, recordUndo = true) { layer ->
            layer.updateProperty(target, AnimatableProperty(target.defaultResetValue))
        }
        postTransientBanner("Reset ${target.label}")
    }

    /**
     * Resets ONLY the active Move & Transform sub-mode (`0 = Move`, `1 = Pivot`, `2 = Scale`, `3 = Rotate`, `4 = 3D Depth`).
     */
    fun resetMoveSubMode(subModeIndex: Int) {
        val layerId = _state.value.selectedLayerId ?: return
        val label = when (subModeIndex) {
            0 -> "Position"
            1 -> "Pivot"
            2 -> "Scale"
            3 -> "Rotation"
            4 -> "3D Transform"
            else -> "Transform"
        }
        mutateLayer(layerId, recordUndo = true) { layer ->
            when (subModeIndex) {
                0 -> layer.copy(
                    posX = AnimatableProperty(0f),
                    posY = AnimatableProperty(0f)
                )
                1 -> layer.copy(
                    pivotX = AnimatableProperty(0f),
                    pivotY = AnimatableProperty(0f)
                )
                2 -> layer.copy(
                    scale = AnimatableProperty(100f)
                )
                3 -> layer.copy(
                    rotationZ = AnimatableProperty(0f)
                )
                4 -> layer.copy(
                    posZ = AnimatableProperty(0f),
                    rotationX = AnimatableProperty(0f),
                    rotationY = AnimatableProperty(0f)
                )
                else -> layer
            }
        }
        postTransientBanner("Reset $label")
    }

    fun resetSelectedLayerTransform() {
        resetPropertyTarget(_state.value.selectedProperty)
    }

    fun resetSelectedLayerStroke() {
        val layerId = _state.value.selectedLayerId ?: return
        mutateLayer(layerId, recordUndo = true) {
            it.copy(
                strokeEnabled = false,
                strokeWidth = 0f,
                strokeColorHex = "#FFFFFF",
                strokePosition = "CENTER"
            )
        }
        postTransientBanner("Reset Stroke")
    }

    fun resetSelectedLayerShadow() {
        val layerId = _state.value.selectedLayerId ?: return
        mutateLayer(layerId, recordUndo = true) {
            it.copy(
                shadowEnabled = false,
                shadowBlur = 18f,
                shadowColorHex = "#4F7CFF",
                shadowOffsetX = 0f,
                shadowOffsetY = 8f
            )
        }
        postTransientBanner("Reset Shadow")
    }

    fun deleteSelectedLayer() {
        val selId = _state.value.selectedLayerId ?: return
        val proj = _state.value.activeProject
        val remaining = proj.layers
            .filterNot { it.id == selId }
            .map { if (it.parentId == selId) it.copy(parentId = null) else it }
        updateActiveProject(
            proj.copy(layers = remaining),
            selectLayerId = null,
            recordUndo = true,
            banner = "Deleted layer"
        )
        _state.update { it.copy(selectedLayerId = null, inspectorTab = AmInspectorTab.TIMELINE) }
    }

    fun toggleLayerVisibility(layerId: String) {
        mutateLayer(layerId, recordUndo = true) { it.copy(visible = !it.visible) }
    }

    fun toggleSoloLayer(layerId: String) {
        val cur = _state.value
        val disablingSolo = cur.soloLayerId == layerId
        val nextSolo = if (disablingSolo) null else layerId
        val updatedLayers = cur.activeProject.layers.map { l ->
            if (disablingSolo) l.copy(visible = true)
            else l.copy(visible = l.id == layerId)
        }
        updateActiveProject(
            cur.activeProject.copy(layers = updatedLayers),
            recordUndo = true,
            banner = if (disablingSolo) "All layers un-soloed" else "Soloed layer"
        )
        _state.update { it.copy(soloLayerId = nextSolo) }
    }

    fun groupVisibleLayersIntoNullRig() {
        val s = _state.value
        val proj = s.activeProject
        if (proj.layers.isEmpty()) return
        val rigId = UUID.randomUUID().toString()
        val rigCount = proj.layers.count { it.type == LayerType.NULL_OBJECT } + 1
        val rigLayer = Layer(
            id = rigId,
            name = "Group Null $rigCount",
            type = LayerType.NULL_OBJECT,
            startTimeMs = 0L,
            endTimeMs = proj.durationMs
        )
        val targetId = s.selectedLayerId
        val updatedLayers = proj.layers.map { l ->
            if (targetId != null && l.id == targetId && l.parentId == null) {
                l.copy(parentId = rigId)
            } else if (targetId == null && l.parentId == null && l.type != LayerType.AUDIO && l.type != LayerType.CAMERA) {
                l.copy(parentId = rigId)
            } else {
                l
            }
        } + rigLayer
        updateActiveProject(
            proj.copy(layers = updatedLayers),
            selectLayerId = rigId,
            recordUndo = true,
            banner = "Created ${rigLayer.name} rig"
        )
    }

    fun expandSelectedLayerToComposition() {
        val s = _state.value
        val layerId = s.selectedLayerId ?: return
        mutateLayer(layerId, recordUndo = true) { layer ->
            layer.copy(startTimeMs = 0L, endTimeMs = s.activeProject.durationMs)
        }
        postTransientBanner("Expanded clip to full composition")
    }

    fun toggleLayerLock(layerId: String) {
        mutateLayer(layerId, recordUndo = true) { it.copy(locked = !it.locked) }
    }

    fun renameLayer(layerId: String, newName: String) {
        mutateLayer(layerId, recordUndo = true) { it.copy(name = newName.ifBlank { it.name }) }
    }

    fun setLayerParent(childId: String, parentId: String?) {
        if (childId == parentId) return
        mutateLayer(childId, recordUndo = true) { it.copy(parentId = parentId) }
    }

    fun updateLayerTextAndFont(layerId: String, text: String, fontFamily: String) {
        mutateLayer(layerId, recordUndo = false) { it.copy(textContent = text, fontFamilyName = fontFamily) }
    }

    fun importCustomFont(context: Context, uri: Uri) {
        val appContext = context.applicationContext
        viewModelScope.launch {
            val result = CustomFontRegistry.importFontFromUri(appContext, uri)
            result.fold(
                onSuccess = { fontName ->
                    val selId = _state.value.selectedLayerId
                    val selLayer = _state.value.activeProject.layers.firstOrNull { it.id == selId }
                    if (selLayer != null && selLayer.type == LayerType.TEXT) {
                        mutateLayer(selLayer.id, recordUndo = true) {
                            it.copy(fontFamilyName = fontName)
                        }
                    }
                    postTransientBanner("Imported font: $fontName")
                },
                onFailure = { err ->
                    postTransientBanner(err.localizedMessage ?: "Could not import font file")
                }
            )
        }
    }

    fun updateLayerColor(layerId: String, hex: String) {
        mutateLayer(layerId, recordUndo = true) { it.copy(colorHex = hex) }
    }

    fun updateLayerGradientFill(
        layerId: String,
        fillMode: String,
        startHex: String,
        endHex: String,
        angleDeg: Float
    ) {
        val normalizedMode = when (fillMode) {
            "LINEAR_GRADIENT", "RADIAL_GRADIENT" -> fillMode
            else -> "SOLID"
        }
        mutateLayer(layerId, recordUndo = false) {
            it.copy(
                fillMode = normalizedMode,
                colorHex = startHex,
                gradientEndColorHex = endHex,
                gradientAngleDeg = angleDeg.coerceIn(0f, 360f)
            )
        }
    }

    fun updateLayerAudioEffects(
        layerId: String,
        preset: String,
        reverbMix: Float,
        roomSize: Float,
        bassBoost: Float,
        echoMix: Float,
        echoDelayMs: Float,
        filterCutoff: Float,
        pitchSemitones: Float,
        optionalSpeed: Float? = null
    ) {
        mutateLayer(layerId, recordUndo = false) { cur ->
            cur.copy(
                audioPreset = preset,
                audioReverbMix = reverbMix.coerceIn(0f, 1f),
                audioRoomSize = roomSize.coerceIn(0.05f, 1f),
                audioBassBoost = bassBoost.coerceIn(0f, 1f),
                audioEchoMix = echoMix.coerceIn(0f, 1f),
                audioEchoDelayMs = echoDelayMs.coerceIn(60f, 650f),
                audioFilterCutoff = filterCutoff.coerceIn(0.05f, 1f),
                audioPitchSemitones = pitchSemitones.coerceIn(-12f, 12f),
                speedMultiplier = optionalSpeed?.coerceIn(0.1f, 4.0f) ?: cur.speedMultiplier
            )
        }
        val latest = _state.value
        if (latest.isPlaying) {
            timelineAudioController?.syncPlayback(
                project = latest.activeProject,
                playheadMs = latest.playheadMs,
                isPlaying = true,
                forceExactSeek = false
            )
            appCtx?.let { ctx ->
                HardwareVideoPreviewEngine.syncProjectVideos(
                    context = ctx,
                    project = latest.activeProject,
                    playheadMs = latest.playheadMs,
                    isPlaying = true,
                    rapidScrubbing = false,
                    forceExactSeek = false
                )
            }
        }
    }

    fun updateLayerShapeKind(layerId: String, shapeKind: String) {
        mutateLayer(layerId, recordUndo = true) { it.copy(shapeKind = shapeKind) }
    }

    fun updateLayerBlendMode(layerId: String, blendMode: String) {
        mutateLayer(layerId, recordUndo = true) { it.copy(blendMode = blendMode) }
    }

    fun updateLayerBorderAndShadow(
        layerId: String,
        strokeEnabled: Boolean,
        strokeWidth: Float,
        strokeColorHex: String,
        shadowEnabled: Boolean,
        shadowBlur: Float,
        shadowColorHex: String,
        strokePosition: String? = null,
        shadowOffsetX: Float? = null,
        shadowOffsetY: Float? = null
    ) {
        mutateLayer(layerId, recordUndo = false) {
            it.copy(
                strokeEnabled = strokeEnabled,
                strokeWidth = strokeWidth.coerceIn(0f, 120f),
                strokeColorHex = strokeColorHex,
                strokePosition = strokePosition ?: it.strokePosition,
                shadowEnabled = shadowEnabled,
                shadowBlur = shadowBlur.coerceIn(0f, 120f),
                shadowColorHex = shadowColorHex,
                shadowOffsetX = shadowOffsetX ?: it.shadowOffsetX,
                shadowOffsetY = shadowOffsetY ?: it.shadowOffsetY
            )
        }
    }

    fun update3DPrimitive(layerId: String, primitive: Primitive3DType, uriString: String = "") {
        mutateLayer(layerId, recordUndo = true) {
            it.copy(
                primitive3D = primitive,
                mediaUri = uriString.ifBlank { it.mediaUri }
            )
        }
    }

    fun updateLayerPropertyByTarget(layerId: String, target: PropertyTarget, newValue: Float) {
        val timeMs = currentEffectivePlayheadMs()
        mutateLayer(layerId, recordUndo = false) { layer ->
            if (layer.locked) return@mutateLayer layer
            val clamped = if (target.unbounded) newValue else newValue.coerceIn(target.minVal, target.maxVal)
            val updatedProp = applyPropertyEditAtTime(layer.propertyFor(target), clamped, timeMs)
            layer.updateProperty(target, updatedProp)
        }
    }

    fun updateSelectedPropertyValue(newValue: Float) {
        val s = _state.value
        val layerId = s.selectedLayerId ?: return
        val target = s.selectedProperty
        val timeMs = currentEffectivePlayheadMs()

        mutateLayer(layerId, recordUndo = false) { layer ->
            if (layer.locked) return@mutateLayer layer
            val clamped = if (target.unbounded) newValue else newValue.coerceIn(target.minVal, target.maxVal)
            val updatedProp = applyPropertyEditAtTime(layer.propertyFor(target), clamped, timeMs)
            layer.updateProperty(target, updatedProp)
        }
    }

    fun toggleKeyframeAtPlayhead(target: PropertyTarget = _state.value.selectedProperty) {
        val s = _state.value
        val layerId = s.selectedLayerId ?: return
        val timeMs = currentEffectivePlayheadMs()
        val audio = liveSpectrum.value

        mutateLayer(layerId, recordUndo = true) { layer ->
            val prop = layer.propertyFor(target)
            val existingIdx = prop.keyframes.indexOfFirst { abs(it.timeMs - timeMs) <= 25L }
            val updatedKfs = if (existingIdx >= 0) {
                prop.keyframes.filterIndexed { index, _ -> index != existingIdx }
            } else {
                val currentEval = AnimationEvaluator.evaluateProperty(prop, timeMs, audio)
                (prop.keyframes + Keyframe(timeMs = timeMs, value = currentEval)).sortedBy { it.timeMs }
            }
            layer.updateProperty(target, prop.copy(keyframes = updatedKfs))
        }
    }

    fun updateBezierHandlesForActiveSegment(
        outX: Float,
        outY: Float,
        inX: Float,
        inY: Float,
        holdStep: Boolean = false
    ) {
        val s = _state.value
        val layerId = s.selectedLayerId ?: return
        val target = s.selectedProperty
        val timeMs = currentEffectivePlayheadMs()

        mutateLayer(layerId, recordUndo = false) { layer ->
            val prop = layer.propertyFor(target)
            val endDefault = if (target == PropertyTarget.TIME_REMAP) {
                ((layer.endTimeMs - layer.startTimeMs).coerceAtLeast(500L)) / 1000f
            } else {
                (prop.baseValue + 120f).coerceIn(target.minVal, target.maxVal)
            }
            val updatedProp = applyBezierHandlesToProp(
                prop = prop,
                timeMs = timeMs,
                clipStartMs = layer.startTimeMs,
                clipEndMs = layer.endTimeMs,
                fallbackEndVal = endDefault,
                outX = outX,
                outY = outY,
                inX = inX,
                inY = inY,
                holdStep = holdStep
            )
            layer.updateProperty(target, updatedProp)
        }
    }

    private fun applyBezierHandlesToProp(
        prop: AnimatableProperty,
        timeMs: Long,
        clipStartMs: Long,
        clipEndMs: Long,
        fallbackEndVal: Float,
        outX: Float,
        outY: Float,
        inX: Float,
        inY: Float,
        holdStep: Boolean
    ): AnimatableProperty {
        val sorted = prop.keyframes.sortedBy { it.timeMs }.toMutableList()
        if (sorted.size < 2) {
            val startKf = Keyframe(
                timeMs = clipStartMs,
                value = sorted.firstOrNull()?.value ?: prop.baseValue,
                outTangentX = outX.coerceIn(0f, 1f),
                outTangentY = outY.coerceIn(-0.5f, 1.5f),
                inTangentX = inX.coerceIn(0f, 1f),
                inTangentY = inY.coerceIn(-0.5f, 1.5f),
                holdStep = holdStep
            )
            val endKf = Keyframe(
                timeMs = clipEndMs.coerceAtLeast(clipStartMs + 200L),
                value = fallbackEndVal,
                inTangentX = inX.coerceIn(0f, 1f),
                inTangentY = inY.coerceIn(-0.5f, 1.5f)
            )
            return prop.copy(keyframes = listOf(startKf, endKf))
        }

        var segIndex = sorted.indexOfLast { it.timeMs <= timeMs }.coerceAtLeast(0)
        if (segIndex >= sorted.size - 1) segIndex = sorted.size - 2

        sorted[segIndex] = sorted[segIndex].copy(
            outTangentX = outX.coerceIn(0f, 1f),
            outTangentY = outY.coerceIn(-0.5f, 1.5f),
            holdStep = holdStep
        )
        sorted[segIndex + 1] = sorted[segIndex + 1].copy(
            inTangentX = inX.coerceIn(0f, 1f),
            inTangentY = inY.coerceIn(-0.5f, 1.5f)
        )
        return prop.copy(keyframes = sorted)
    }

    fun copyActiveBezierCurve(isEffect: Boolean = _state.value.graphTargetsEffect) {
        val s = _state.value
        val layer = s.activeProject.layers.firstOrNull { it.id == s.selectedLayerId } ?: return
        val prop = if (isEffect) {
            val fx = layer.effects.firstOrNull { it.id == s.selectedEffectId } ?: layer.effects.firstOrNull() ?: return
            resolveActiveEffectAnimatableProp(fx, s.selectedEffectSlot, s.selectedPluginParamKey)
        } else {
            layer.propertyFor(s.selectedProperty)
        }
        val sorted = prop.keyframes.sortedBy { it.timeMs }
        val segIdx = (sorted.indexOfLast { it.timeMs <= s.playheadMs }.coerceAtLeast(0))
            .coerceAtMost((sorted.size - 2).coerceAtLeast(0))
        val outKf = sorted.getOrNull(segIdx)
        val inKf = sorted.getOrNull(segIdx + 1)
        clipboardCurve = floatArrayOf(
            outKf?.outTangentX ?: 0.42f,
            outKf?.outTangentY ?: 0.0f,
            inKf?.inTangentX ?: 0.58f,
            inKf?.inTangentY ?: 1.0f
        )
        clipboardHoldStep = outKf?.holdStep ?: false
        _state.update { it.copy(hasCopiedCurve = true) }
        postTransientBanner("Copied easing curve")
    }

    fun pasteBezierCurve(isEffect: Boolean = _state.value.graphTargetsEffect) {
        val curve = clipboardCurve ?: return
        if (isEffect) {
            updateEffectBezierHandles(curve[0], curve[1], curve[2], curve[3], clipboardHoldStep)
        } else {
            updateBezierHandlesForActiveSegment(curve[0], curve[1], curve[2], curve[3], clipboardHoldStep)
        }
        postTransientBanner("Pasted easing curve")
    }

    fun dragKeyframePoint(kfId: String, newTimeMs: Long, newValue: Float, isEffect: Boolean) {
        val s = _state.value
        val layerId = s.selectedLayerId ?: return
        val snappedTime = AnimationEvaluator.snapToFrameMs(
            newTimeMs.coerceIn(0L, s.activeProject.durationMs),
            s.activeProject.fps
        )
        mutateLayer(layerId, recordUndo = false) { layer ->
            if (isEffect) {
                val fxId = s.selectedEffectId ?: return@mutateLayer layer
                val slot = s.selectedEffectSlot
                val pKey = s.selectedPluginParamKey
                val updatedFx = layer.effects.map { fx ->
                    if (fx.id != fxId) fx
                    else {
                        val p = resolveActiveEffectAnimatableProp(fx, slot, pKey)
                        val kfs = p.keyframes.map { kf ->
                            if (kf.id == kfId) kf.copy(timeMs = snappedTime, value = newValue) else kf
                        }.sortedBy { it.timeMs }
                        applyUpdatedEffectAnimatableProp(fx, slot, pKey, p.copy(baseValue = newValue, keyframes = kfs))
                    }
                }
                layer.copy(effects = updatedFx)
            } else {
                val target = s.selectedProperty
                val p = layer.propertyFor(target)
                val kfs = p.keyframes.map { kf ->
                    if (kf.id == kfId) kf.copy(timeMs = snappedTime, value = newValue) else kf
                }.sortedBy { it.timeMs }
                layer.updateProperty(target, p.copy(baseValue = newValue, keyframes = kfs))
            }
        }
    }

    fun updatePropertyExpression(expression: String) {
        val s = _state.value
        val layerId = s.selectedLayerId ?: return
        mutateLayer(layerId, recordUndo = false) { layer ->
            val prop = layer.propertyFor(s.selectedProperty)
            layer.updateProperty(s.selectedProperty, prop.copy(expression = expression))
        }
    }

    fun bindAudioReactorToSelectedProperty(band: AudioBand, gain: Float) {
        val s = _state.value
        val layerId = s.selectedLayerId ?: return
        mutateLayer(layerId, recordUndo = true) { layer ->
            val prop = layer.propertyFor(s.selectedProperty)
            layer.updateProperty(
                s.selectedProperty,
                prop.copy(audioReactorBand = band, audioReactorGain = gain)
            )
        }
    }

    // Canvas gestures
    fun updateCanvasViewportGesture(zoomDelta: Float, panDeltaX: Float, panDeltaY: Float) {
        _state.update {
            it.copy(
                canvasZoom = (it.canvasZoom * zoomDelta).coerceIn(0.45f, 3.5f),
                canvasPanX = (it.canvasPanX + panDeltaX).coerceIn(-600f, 600f),
                canvasPanY = (it.canvasPanY + panDeltaY).coerceIn(-600f, 600f)
            )
        }
    }

    fun resetCanvasViewport() {
        _state.update { it.copy(canvasZoom = 1f, canvasPanX = 0f, canvasPanY = 0f) }
    }

    // 3D Camera Orbit (Full 360-degree yaw & pitch support)
    fun orbit3DCamera(deltaYaw: Float, deltaPitch: Float, zoomFactor: Float = 1f) {
        _state.update {
            val nextYaw = (it.cameraYawDeg + deltaYaw).let { y ->
                if (y > 360f || y < -360f) y % 360f else y
            }
            val nextPitch = (it.cameraPitchDeg + deltaPitch).coerceIn(-360f, 360f)
            it.copy(
                cameraYawDeg = nextYaw,
                cameraPitchDeg = nextPitch,
                cameraDistance = (it.cameraDistance / zoomFactor.coerceAtLeast(0.05f)).coerceIn(1.8f, 16f)
            )
        }
    }

    // Effects Browser & Node Chain
    fun setEffectSearchQuery(query: String) {
        _state.update { it.copy(effectSearchQuery = query) }
    }

    fun setSelectedEffectCategory(category: EffectCategory?) {
        _state.update {
            it.copy(
                selectedEffectCategory = category,
                selectedPluginCategoryFilter = if (category == EffectCategory.PLUGINS) it.selectedPluginCategoryFilter else null
            )
        }
    }

    fun setSelectedPluginCategoryFilter(category: String?) {
        _state.update { it.copy(selectedPluginCategoryFilter = category) }
    }

    fun importPluginPackFromUri(context: Context, uri: Uri, navigateToManager: Boolean = false) {
        val appContext = context.applicationContext
        viewModelScope.launch {
            val res = PluginPackManager.importPackFromUri(appContext, uri)
            res.fold(
                onSuccess = { suite ->
                    invalidateCurrentProjectCache()
                    val skippedNote = if (suite.skippedEffectsReport.isNotEmpty()) {
                        " • Skipped ${suite.skippedEffectsReport.size} broken shader(s)"
                    } else ""
                    _state.update { cur ->
                        cur.copy(
                            destination = if (navigateToManager) StudioDestination.PLUGIN_MANAGER else cur.destination,
                            selectedEffectCategory = if (cur.destination == StudioDestination.EFFECTS_BROWSER) {
                                EffectCategory.PLUGINS
                            } else cur.selectedEffectCategory
                        )
                    }
                    postTransientBanner("Installed ${suite.name} v${suite.version} (${suite.effects.size} FX$skippedNote)")
                },
                onFailure = { err ->
                    postTransientBanner(err.localizedMessage ?: "Invalid .kfpack / .fppack file")
                }
            )
        }
    }

    fun exportPluginSuiteToUri(context: Context, suiteId: String, targetUri: Uri) {
        val appContext = context.applicationContext
        viewModelScope.launch {
            PluginPackManager.exportSuiteToUri(appContext, suiteId, targetUri).fold(
                onSuccess = { fileName -> postTransientBanner("Exported $fileName") },
                onFailure = { err -> postTransientBanner(err.localizedMessage ?: "Export failed") }
            )
        }
    }

    fun togglePluginSuiteEnabled(suiteId: String, enabled: Boolean) {
        PluginPackManager.toggleSuiteEnabled(appCtx, suiteId, enabled)
        invalidateCurrentProjectCache()
    }

    fun removePluginSuite(suiteId: String) {
        PluginPackManager.removeSuite(appCtx, suiteId)
        invalidateCurrentProjectCache()
        postTransientBanner("Removed plugin suite")
    }

    fun restoreStarterPluginSuite() {
        val starter = PluginPackManager.restoreStarterPack(appCtx)
        invalidateCurrentProjectCache()
        postTransientBanner("Restored ${starter.name}")
    }

    fun createCustomPluginSuite(
        effectName: String,
        suiteName: String,
        author: String,
        category: String,
        expandBounds: Float,
        usesNativeMotionBlur: Boolean,
        glslSource: String,
        parameters: List<PluginParamDef>,
        extension: String = PluginPackManager.EXT_KFPACK
    ) {
        PluginPackManager.createAndInstallCustomPlugin(
            context = appCtx,
            effectName = effectName,
            suiteName = suiteName,
            author = author,
            category = category,
            expandBounds = expandBounds,
            usesNativeMotionBlur = usesNativeMotionBlur,
            glslSource = glslSource,
            parameters = parameters,
            extension = extension
        ).fold(
            onSuccess = { suite ->
                invalidateCurrentProjectCache()
                postTransientBanner("Created ${suite.name}")
            },
            onFailure = { err ->
                postTransientBanner(err.localizedMessage ?: "Failed to create plugin")
            }
        )
    }

    private fun invalidateCurrentProjectCache() {
        _state.update { s ->
            val nextRev = s.projectRevision + 1L
            PlaybackCacheEngine.notifyProjectEdited(
                newRevision = nextRev,
                quality = s.previewQuality,
                durationMs = s.activeProject.durationMs,
                fps = s.activeProject.fps
            )
            s.copy(projectRevision = nextRev)
        }
    }

    private fun defaultPluginParamKeyFor(effectId: String): String? {
        return PluginPackManager.findPluginEffect(effectId)
            ?.parameters
            ?.firstOrNull { it.type != PluginParamType.COLOR }
            ?.key
    }

    private fun resolveActiveEffectAnimatableProp(
        fx: EffectNode,
        slot: EffectParamSlot,
        pluginParamKey: String?
    ): AnimatableProperty {
        if (pluginParamKey != null) {
            if (pluginParamKey == "__MIX__") return fx.mixProp
            val plugin = PluginPackManager.findPluginEffect(fx.effectId)
            if (plugin != null) {
                val paramDef = plugin.parameters.firstOrNull { it.key == pluginParamKey }
                if (paramDef != null && paramDef.type != PluginParamType.COLOR) {
                    return fx.customParamFor(paramDef.key, paramDef.defaultFloat)
                }
            }
            if (pluginParamKey.startsWith("kf_")) {
                return fx.customParamFor(pluginParamKey, defaultKeyflowTextParam(pluginParamKey))
            }
        }
        return fx.propertyFor(slot)
    }

    private fun applyUpdatedEffectAnimatableProp(
        fx: EffectNode,
        slot: EffectParamSlot,
        pluginParamKey: String?,
        updatedProp: AnimatableProperty
    ): EffectNode {
        if (pluginParamKey != null) {
            if (pluginParamKey == "__MIX__") return fx.updateProperty(EffectParamSlot.MIX, updatedProp)
            val plugin = PluginPackManager.findPluginEffect(fx.effectId)
            if (plugin != null) {
                val paramDef = plugin.parameters.firstOrNull { it.key == pluginParamKey }
                if (paramDef != null && paramDef.type != PluginParamType.COLOR) {
                    return fx.updateCustomParam(paramDef.key, updatedProp)
                }
            }
            if (pluginParamKey.startsWith("kf_")) {
                return fx.updateCustomParam(pluginParamKey, updatedProp)
            }
        }
        return fx.updateProperty(slot, updatedProp)
    }

    private fun defaultKeyflowTextParam(key: String): Float = when (key) {
        "kf_unit_mode", "kf_unit" -> 0f
        "kf_range_start" -> 0f
        "kf_range_end" -> 100f
        "kf_phase" -> 0f
        "kf_smooth" -> 35f
        "kf_offset_x", "kf_posX" -> 0f
        "kf_offset_y", "kf_posY" -> -48f
        "kf_scale" -> 100f
        "kf_rotation", "kf_rot" -> 0f
        "kf_opacity" -> 0f
        "kf_tracking" -> 0f
        else -> 0f
    }

    fun addEffectToSelectedLayer(definition: EffectDefinition) {
        val s = _state.value
        val proj = s.activeProject
        val existingTarget = proj.layers.firstOrNull { it.id == s.selectedLayerId } ?: proj.layers.lastOrNull()

        // Strictly gate text-only effects so they ONLY apply to LayerType.TEXT layers!
        if (EffectsCatalog.isTextOnlyEffect(definition.id)) {
            if (existingTarget == null) {
                val autoTextLayer = Layer(
                    name = "Text 1",
                    type = LayerType.TEXT,
                    textContent = "KEYFLOW TEXT",
                    colorHex = "#FFFFFF",
                    startTimeMs = 0L,
                    endTimeMs = proj.effectiveEditDurationMs(),
                    effects = listOf(definition.toNode())
                )
                val fxId = autoTextLayer.effects.first().id
                updateActiveProject(
                    proj.copy(layers = listOf(autoTextLayer)),
                    selectLayerId = autoTextLayer.id,
                    recordUndo = true,
                    banner = "Added ${definition.name} to Text layer"
                )
                _state.update {
                    it.copy(
                        selectedLayerId = autoTextLayer.id,
                        selectedEffectId = fxId,
                        selectedEffectSlot = EffectParamSlot.PRIMARY,
                        selectedPluginParamKey = null,
                        graphTargetsEffect = true,
                        inspectorTab = AmInspectorTab.EFFECT_DETAIL,
                        destination = StudioDestination.EDITOR
                    )
                }
                return
            }
            if (existingTarget.type != LayerType.TEXT) {
                postTransientBanner("${definition.name} only works on Text layers")
                return
            }
        }

        val newFx = definition.toNode()
        val initialPluginKey = defaultPluginParamKeyFor(definition.id)

        val existingTargetId = existingTarget?.id
        if (existingTargetId == null) {
            val autoLayer = Layer(
                name = "Shape 1",
                type = LayerType.SHAPE,
                shapeKind = "Rounded Rect",
                colorHex = "#4F7CFF",
                startTimeMs = 0L,
                endTimeMs = proj.effectiveEditDurationMs(),
                effects = listOf(newFx)
            )
            updateActiveProject(
                proj.copy(layers = listOf(autoLayer)),
                selectLayerId = autoLayer.id,
                recordUndo = true,
                banner = "Added ${definition.name}"
            )
            _state.update {
                it.copy(
                    selectedLayerId = autoLayer.id,
                    selectedEffectId = newFx.id,
                    selectedEffectSlot = EffectParamSlot.PRIMARY,
                    selectedPluginParamKey = initialPluginKey,
                    graphTargetsEffect = true,
                    inspectorTab = AmInspectorTab.EFFECT_DETAIL,
                    destination = StudioDestination.EDITOR
                )
            }
            return
        }

        mutateLayer(existingTargetId, recordUndo = true) { layer ->
            layer.copy(effects = layer.effects + newFx)
        }
        _state.update {
            it.copy(
                selectedLayerId = existingTargetId,
                selectedEffectId = newFx.id,
                selectedEffectSlot = EffectParamSlot.PRIMARY,
                selectedPluginParamKey = initialPluginKey,
                graphTargetsEffect = true,
                inspectorTab = AmInspectorTab.EFFECT_DETAIL,
                destination = StudioDestination.EDITOR
            )
        }
        postTransientBanner("Added ${definition.name}")
    }

    /**
     * Opens the full Alight Motion / KeyFlow Effect Detail Inspector when an added Effect is clicked!
     */
    fun selectEffectForInspection(effectNodeId: String) {
        _state.update { cur ->
            val fx = cur.activeProject.layers
                .firstOrNull { it.id == cur.selectedLayerId }
                ?.effects
                ?.firstOrNull { it.id == effectNodeId }
            cur.copy(
                selectedEffectId = effectNodeId,
                selectedEffectSlot = EffectParamSlot.PRIMARY,
                selectedPluginParamKey = fx?.let { defaultPluginParamKeyFor(it.effectId) },
                graphTargetsEffect = true,
                inspectorTab = AmInspectorTab.EFFECT_DETAIL,
                destination = StudioDestination.EDITOR
            )
        }
    }

    fun selectEffectParamSlot(slot: EffectParamSlot) {
        _state.update {
            it.copy(
                selectedEffectSlot = slot,
                selectedPluginParamKey = if (slot == EffectParamSlot.MIX && it.selectedPluginParamKey != null) "__MIX__" else null,
                graphTargetsEffect = true
            )
        }
    }

    fun selectPluginParamKey(paramKey: String) {
        _state.update {
            it.copy(
                selectedPluginParamKey = paramKey,
                selectedEffectSlot = if (paramKey == "__MIX__") EffectParamSlot.MIX else it.selectedEffectSlot,
                graphTargetsEffect = true
            )
        }
    }

    fun updateCustomPluginParamValue(paramKey: String, newValue: Float) {
        val s = _state.value
        val layerId = s.selectedLayerId ?: return
        val fxId = s.selectedEffectId ?: return
        val timeMs = currentEffectivePlayheadMs()

        mutateLayer(layerId, recordUndo = false) { layer ->
            if (layer.locked) return@mutateLayer layer
            layer.copy(
                effects = layer.effects.map { fx ->
                    if (fx.id != fxId) fx
                    else if (paramKey == "__MIX__") {
                        val clamped = newValue.coerceIn(0f, 100f)
                        fx.updateProperty(EffectParamSlot.MIX, applyPropertyEditAtTime(fx.mixProp, clamped, timeMs))
                    } else if (paramKey.startsWith("kf_")) {
                        val curProp = fx.customParamFor(paramKey, defaultKeyflowTextParam(paramKey))
                        val updatedProp = applyPropertyEditAtTime(curProp, newValue, timeMs)
                        fx.updateCustomParam(paramKey, updatedProp)
                    } else {
                        val plugin = PluginPackManager.findPluginEffect(fx.effectId) ?: return@map fx
                        val paramDef = plugin.parameters.firstOrNull { it.key == paramKey } ?: return@map fx
                        val clamped = if (paramDef.type == PluginParamType.BOOL) {
                            if (newValue >= 0.5f) 1f else 0f
                        } else {
                            newValue.coerceIn(paramDef.min, paramDef.max)
                        }
                        val curProp = fx.customParamFor(paramKey, paramDef.defaultFloat)
                        val updatedProp = applyPropertyEditAtTime(curProp, clamped, timeMs)
                        fx.updateCustomParam(paramKey, updatedProp)
                    }
                }
            )
        }
    }

    fun resetSingleEffectParameter(
        layerId: String,
        effectNodeId: String,
        slot: EffectParamSlot? = null,
        customKey: String? = null
    ) {
        mutateLayer(layerId, recordUndo = true) { layer ->
            layer.copy(
                effects = layer.effects.map { fx ->
                    if (fx.id != effectNodeId) fx
                    else if (customKey != null) {
                        if (customKey == "__MIX__") {
                            fx.updateProperty(EffectParamSlot.MIX, AnimatableProperty(100f))
                        } else if (customKey.startsWith("kf_")) {
                            fx.updateCustomParam(customKey, AnimatableProperty(defaultKeyflowTextParam(customKey)))
                        } else {
                            val defVal = PluginPackManager.findPluginEffect(fx.effectId)
                                ?.parameters?.firstOrNull { it.key == customKey }?.defaultFloat ?: 0f
                            fx.updateCustomParam(customKey, AnimatableProperty(defVal))
                        }
                    } else if (slot != null) {
                        val def = EffectsCatalog.findById(fx.effectId)
                        val defVal = when (slot) {
                            EffectParamSlot.PRIMARY -> def.defaultIntensity
                            EffectParamSlot.SECONDARY -> def.defaultSecondary
                            EffectParamSlot.FREQUENCY -> def.defaultFrequency
                            EffectParamSlot.ANGLE -> def.defaultAngle
                            EffectParamSlot.MIX -> def.defaultMix
                            EffectParamSlot.FEATHER -> def.defaultFeather
                        }
                        fx.updateProperty(slot, AnimatableProperty(defVal))
                    } else {
                        fx
                    }
                }
            )
        }
        postTransientBanner("Reset parameter")
    }

    fun updateCustomPluginColor(paramKey: String, hex: String) {
        val s = _state.value
        val layerId = s.selectedLayerId ?: return
        val fxId = s.selectedEffectId ?: return
        mutateLayer(layerId, recordUndo = true) { layer ->
            layer.copy(
                effects = layer.effects.map { fx ->
                    if (fx.id != fxId) fx else fx.updateCustomColor(paramKey, hex)
                }
            )
        }
    }

    fun updateSelectedEffectParamValue(newValue: Float) {
        val s = _state.value
        if (s.selectedPluginParamKey != null) {
            updateCustomPluginParamValue(s.selectedPluginParamKey, newValue)
            return
        }
        val layerId = s.selectedLayerId ?: return
        val fxId = s.selectedEffectId ?: return
        val slot = s.selectedEffectSlot
        val timeMs = currentEffectivePlayheadMs()

        mutateLayer(layerId, recordUndo = false) { layer ->
            if (layer.locked) return@mutateLayer layer
            layer.copy(
                effects = layer.effects.map { fx ->
                    if (fx.id != fxId) fx
                    else {
                        val spec = EffectsCatalog.findById(fx.effectId).specFor(slot)
                        val clamped = newValue.coerceIn(spec.minVal, spec.maxVal)
                        val updatedProp = applyPropertyEditAtTime(fx.propertyFor(slot), clamped, timeMs)
                        fx.updateProperty(slot, updatedProp)
                    }
                }
            )
        }
    }

    fun toggleEffectKeyframeAtPlayhead() {
        val s = _state.value
        val layerId = s.selectedLayerId ?: return
        val fxId = s.selectedEffectId ?: return
        val slot = s.selectedEffectSlot
        val pKey = s.selectedPluginParamKey
        val timeMs = currentEffectivePlayheadMs()
        val audio = liveSpectrum.value

        mutateLayer(layerId, recordUndo = true) { layer ->
            layer.copy(
                effects = layer.effects.map { fx ->
                    if (fx.id != fxId) fx
                    else {
                        val prop = resolveActiveEffectAnimatableProp(fx, slot, pKey)
                        val existingIdx = prop.keyframes.indexOfFirst { abs(it.timeMs - timeMs) <= 25L }
                        val updatedKfs = if (existingIdx >= 0) {
                            prop.keyframes.filterIndexed { index, _ -> index != existingIdx }
                        } else {
                            val currentEval = AnimationEvaluator.evaluateProperty(prop, timeMs, audio)
                            (prop.keyframes + Keyframe(timeMs = timeMs, value = currentEval)).sortedBy { it.timeMs }
                        }
                        applyUpdatedEffectAnimatableProp(fx, slot, pKey, prop.copy(keyframes = updatedKfs))
                    }
                }
            )
        }
    }

    fun jumpToAdjacentEffectKeyframe(forward: Boolean) {
        val s = _state.value
        val layer = s.activeProject.layers.firstOrNull { it.id == s.selectedLayerId } ?: return
        val fx = layer.effects.firstOrNull { it.id == s.selectedEffectId } ?: return
        val prop = resolveActiveEffectAnimatableProp(fx, s.selectedEffectSlot, s.selectedPluginParamKey)
        val kfs = prop.keyframes.sortedBy { it.timeMs }
        if (kfs.isEmpty()) return
        val target = if (forward) {
            kfs.firstOrNull { it.timeMs > s.playheadMs + 10L } ?: kfs.first()
        } else {
            kfs.lastOrNull { it.timeMs < s.playheadMs - 10L } ?: kfs.last()
        }
        pausePlayback()
        scrubPlayhead(target.timeMs)
    }

    fun updateEffectBezierHandles(
        outX: Float,
        outY: Float,
        inX: Float,
        inY: Float,
        holdStep: Boolean = false
    ) {
        val s = _state.value
        val layerId = s.selectedLayerId ?: return
        val fxId = s.selectedEffectId ?: return
        val slot = s.selectedEffectSlot
        val pKey = s.selectedPluginParamKey
        val timeMs = s.playheadMs

        mutateLayer(layerId, recordUndo = false) { layer ->
            layer.copy(
                effects = layer.effects.map { fx ->
                    if (fx.id != fxId) fx
                    else {
                        val plugin = PluginPackManager.findPluginEffect(fx.effectId)
                        val paramDef = if (plugin != null && pKey != null && pKey != "__MIX__") {
                            plugin.parameters.firstOrNull { it.key == pKey }
                        } else null
                        val minV: Float
                        val maxV: Float
                        if (paramDef != null) {
                            minV = paramDef.min
                            maxV = paramDef.max
                        } else if (pKey == "__MIX__") {
                            minV = 0f
                            maxV = 100f
                        } else {
                            val spec = EffectsCatalog.findById(fx.effectId).specFor(slot)
                            minV = spec.minVal
                            maxV = spec.maxVal
                        }
                        val prop = resolveActiveEffectAnimatableProp(fx, slot, pKey)
                        val fallbackEnd = (prop.baseValue + (maxV - minV) * 0.35f).coerceIn(minV, maxV)
                        val updatedProp = applyBezierHandlesToProp(
                            prop = prop,
                            timeMs = timeMs,
                            clipStartMs = layer.startTimeMs,
                            clipEndMs = layer.endTimeMs,
                            fallbackEndVal = fallbackEnd,
                            outX = outX,
                            outY = outY,
                            inX = inX,
                            inY = inY,
                            holdStep = holdStep
                        )
                        applyUpdatedEffectAnimatableProp(fx, slot, pKey, updatedProp)
                    }
                }
            )
        }
    }

    fun updateEffectParamExpression(expression: String) {
        val s = _state.value
        val layerId = s.selectedLayerId ?: return
        val fxId = s.selectedEffectId ?: return
        val slot = s.selectedEffectSlot
        val pKey = s.selectedPluginParamKey
        mutateLayer(layerId, recordUndo = false) { layer ->
            layer.copy(
                effects = layer.effects.map { fx ->
                    if (fx.id != fxId) fx
                    else {
                        val prop = resolveActiveEffectAnimatableProp(fx, slot, pKey)
                        applyUpdatedEffectAnimatableProp(fx, slot, pKey, prop.copy(expression = expression))
                    }
                }
            )
        }
    }

    fun moveEffectOrder(layerId: String, effectNodeId: String, moveUp: Boolean) {
        mutateLayer(layerId, recordUndo = true) { layer ->
            val list = layer.effects.toMutableList()
            val idx = list.indexOfFirst { it.id == effectNodeId }
            if (idx < 0) return@mutateLayer layer
            val targetIdx = if (moveUp) idx - 1 else idx + 1
            if (targetIdx !in list.indices) return@mutateLayer layer
            val item = list.removeAt(idx)
            list.add(targetIdx, item)
            layer.copy(effects = list)
        }
    }

    fun duplicateEffectNode(layerId: String, effectNodeId: String) {
        var newDupId: String? = null
        mutateLayer(layerId, recordUndo = true) { layer ->
            val idx = layer.effects.indexOfFirst { it.id == effectNodeId }
            if (idx < 0) return@mutateLayer layer
            val src = layer.effects[idx]
            val dup = src.copy(id = UUID.randomUUID().toString(), displayName = "${src.displayName} Copy")
            newDupId = dup.id
            val updated = layer.effects.toMutableList().apply { add(idx + 1, dup) }
            layer.copy(effects = updated)
        }
        if (newDupId != null) {
            _state.update { it.copy(selectedEffectId = newDupId) }
            postTransientBanner("Duplicated effect")
        }
    }

    fun resetEffectNode(layerId: String, effectNodeId: String) {
        mutateLayer(layerId, recordUndo = true) { layer ->
            layer.copy(
                effects = layer.effects.map { fx ->
                    if (fx.id != effectNodeId) fx
                    else EffectsCatalog.findById(fx.effectId).toNode().copy(id = fx.id)
                }
            )
        }
        postTransientBanner("Reset effect to standard settings")
    }

    fun updateEffectNode(layerId: String, updatedNode: EffectNode) {
        mutateLayer(layerId, recordUndo = false) { layer ->
            layer.copy(
                effects = layer.effects.map {
                    if (it.id == updatedNode.id) {
                        // Keep primaryProp/secondaryProp base values synchronized if quick-slider changed intensity
                        val syncedPrimary = if (it.intensity != updatedNode.intensity && updatedNode.primaryProp.keyframes.isEmpty()) {
                            updatedNode.primaryProp.copy(baseValue = updatedNode.intensity)
                        } else updatedNode.primaryProp
                        val syncedSecondary = if (it.secondaryParam != updatedNode.secondaryParam && updatedNode.secondaryProp.keyframes.isEmpty()) {
                            updatedNode.secondaryProp.copy(baseValue = updatedNode.secondaryParam)
                        } else updatedNode.secondaryProp
                        updatedNode.copy(primaryProp = syncedPrimary, secondaryProp = syncedSecondary)
                    } else it
                }
            )
        }
    }

    fun removeEffectNode(layerId: String, effectNodeId: String) {
        mutateLayer(layerId, recordUndo = true) { layer ->
            layer.copy(effects = layer.effects.filterNot { it.id == effectNodeId })
        }
        _state.update { cur ->
            if (cur.selectedEffectId == effectNodeId) {
                cur.copy(
                    selectedEffectId = null,
                    inspectorTab = if (cur.inspectorTab == AmInspectorTab.EFFECT_DETAIL || cur.inspectorTab == AmInspectorTab.EFFECT_EASING) {
                        AmInspectorTab.EFFECTS
                    } else cur.inspectorTab
                )
            } else cur
        }
        postTransientBanner("Deleted effect")
    }

    fun clearAllEffectsOnLayer(layerId: String) {
        mutateLayer(layerId, recordUndo = true) { layer ->
            layer.copy(effects = emptyList())
        }
        _state.update { cur ->
            cur.copy(
                selectedEffectId = null,
                inspectorTab = if (cur.inspectorTab == AmInspectorTab.EFFECT_DETAIL || cur.inspectorTab == AmInspectorTab.EFFECT_EASING) {
                    AmInspectorTab.EFFECTS
                } else cur.inspectorTab
            )
        }
        postTransientBanner("Cleared all effects on layer")
    }

    fun saveLayerEffectChainAsPreset(presetName: String) {
        val repo = repository ?: return
        val s = _state.value
        val layer = s.activeProject.layers.firstOrNull { it.id == s.selectedLayerId } ?: return
        if (layer.effects.isEmpty()) return
        viewModelScope.launch {
            repo.saveEffectPreset(
                name = presetName.ifBlank { "${layer.name} FX Stack" },
                category = layer.effects.first().category,
                effects = layer.effects
            ).onSuccess {
                postTransientBanner("Saved FX stack preset to Cloud")
            }
        }
    }

    fun applyCloudPresetToSelectedLayer(preset: CustomEffectPresetDocument) {
        val s = _state.value
        val targetId = s.selectedLayerId ?: return
        val decoded = runCatching {
            ProjectPayloadSerializer.decodeEffects(JSONArray(preset.effectChainPayload))
        }.getOrDefault(emptyList())
        if (decoded.isEmpty()) return
        mutateLayer(targetId, recordUndo = true) { layer ->
            layer.copy(effects = layer.effects + decoded)
        }
        postTransientBanner("Applied preset \"${preset.name}\"")
    }

    // Audio Reactor & Notable Bass Onset Beat Detection controls
    fun updateProjectBpmAndRegenerateBeats(bpm: Int, sensitivity: Float) {
        val safeBpm = bpm.coerceIn(40, 240)
        val safeSens = sensitivity.coerceIn(0.1f, 1.0f)
        val proj = _state.value.activeProject
        val editSpan = proj.effectiveEditDurationMs()
        val hasAudioMedia = proj.layers.any {
            it.visible && !it.audioMuted &&
                (it.type == LayerType.AUDIO || it.type == LayerType.VIDEO) &&
                it.mediaUri.isNotBlank()
        }
        if (!hasAudioMedia || appCtx == null) {
            val beats = audioEngine.generateAutoBeatMarkers(editSpan, safeBpm, safeSens)
            _state.update {
                it.copy(
                    audioSensitivity = safeSens,
                    activeProject = proj.copy(bpm = safeBpm, beatMarkers = beats)
                )
            }
            postTransientBanner("Detected ${beats.size} notable bass beats @ $safeBpm BPM")
            return
        }

        _state.update {
            it.copy(
                audioSensitivity = safeSens,
                activeProject = proj.copy(bpm = safeBpm)
            )
        }
        autoDetectNotableBassBeats(appCtx, safeSens)
    }

    fun autoDetectNotableBassBeats(
        context: Context? = appCtx,
        sensitivity: Float = _state.value.audioSensitivity
    ) {
        val safeSens = sensitivity.coerceIn(0.1f, 1.0f)
        val resolvedCtx = context?.applicationContext ?: appCtx
        val snapshotProj = _state.value.activeProject
        val editSpan = snapshotProj.effectiveEditDurationMs()
        val hasAudioMedia = snapshotProj.layers.any {
            it.visible && !it.audioMuted &&
                (it.type == LayerType.AUDIO || it.type == LayerType.VIDEO) &&
                it.mediaUri.isNotBlank()
        }
        if (!hasAudioMedia || resolvedCtx == null) {
            val beats = audioEngine.generateAutoBeatMarkers(editSpan, snapshotProj.bpm, safeSens)
            _state.update { cur ->
                cur.copy(
                    audioSensitivity = safeSens,
                    activeProject = cur.activeProject.copy(beatMarkers = beats)
                )
            }
            postTransientBanner("Placed ${beats.size} notable bass markers")
            return
        }

        postTransientBanner("Analyzing full track bass (20–135 Hz)...")
        viewModelScope.launch {
            val beats = audioEngine.detectNotableBassBeats(resolvedCtx, snapshotProj, safeSens)
            _state.update { cur ->
                cur.copy(
                    audioSensitivity = safeSens,
                    activeProject = cur.activeProject.copy(beatMarkers = beats)
                )
            }
            postTransientBanner("Detected ${beats.size} notable bass hits across edit")
        }
    }

    /**
     * Bakes explicit attack + smooth Bezier ease-out release keyframes across ALL detected [BeatMarker]s
     * inside the target layer's `[startTimeMs..endTimeMs]` so beat-synced pulses render reliably from start to finish!
     */
    fun bakeBeatsToPropertyKeyframes(
        target: PropertyTarget = _state.value.selectedProperty,
        pulseAmount: Float = 22f,
        releaseMs: Long = 190L
    ) {
        val s = _state.value
        val proj = s.activeProject
        val targetLayer = proj.layers.firstOrNull { it.id == s.selectedLayerId }
            ?: proj.layers.lastOrNull { it.type != LayerType.AUDIO }
        if (targetLayer == null) {
            postTransientBanner("Select a visual layer first to bake beat keyframes")
            return
        }
        val activeBeats = (if (proj.beatMarkers.isNotEmpty()) {
            proj.beatMarkers
        } else {
            audioEngine.generateAutoBeatMarkers(proj.effectiveEditDurationMs(), proj.bpm, s.audioSensitivity)
        }).sortedBy { it.timeMs }
            .filter { it.timeMs in targetLayer.startTimeMs..targetLayer.endTimeMs }

        if (activeBeats.isEmpty()) {
            postTransientBanner("No beat markers inside \"${targetLayer.name}\" span")
            return
        }

        val baseProp = targetLayer.propertyFor(target)
        val restingVal = baseProp.baseValue
        val safeRelease = releaseMs.coerceIn(70L, 600L)
        val bakedKeyframes = ArrayList<Keyframe>(activeBeats.size * 3 + 2)

        for (i in activeBeats.indices) {
            val beat = activeBeats[i]
            val tHit = beat.timeMs
            val nextHit = activeBeats.getOrNull(i + 1)?.timeMs ?: Long.MAX_VALUE
            val preLead = (tHit - 24L).coerceAtLeast(targetLayer.startTimeMs)
            if (preLead < tHit && (bakedKeyframes.isEmpty() || preLead > bakedKeyframes.last().timeMs + 16L)) {
                bakedKeyframes.add(
                    Keyframe(
                        timeMs = preLead,
                        value = restingVal,
                        outTangentX = 0.15f,
                        outTangentY = 0.85f,
                        inTangentX = 0.85f,
                        inTangentY = 1.0f
                    )
                )
            }
            val peakDelta = pulseAmount * beat.strength.coerceIn(0.45f, 1.0f)
            val rawPeak = restingVal + peakDelta
            val peakVal = if (target.unbounded) rawPeak else rawPeak.coerceIn(target.minVal, target.maxVal)
            bakedKeyframes.add(
                Keyframe(
                    timeMs = tHit,
                    value = peakVal,
                    outTangentX = 0.14f,
                    outTangentY = 0.0f,
                    inTangentX = 0.22f,
                    inTangentY = 1.0f
                )
            )
            val maxReleaseT = min(targetLayer.endTimeMs, nextHit - 28L)
            val tRel = min(tHit + safeRelease, maxReleaseT)
            if (tRel > tHit + 24L) {
                bakedKeyframes.add(
                    Keyframe(
                        timeMs = tRel,
                        value = restingVal,
                        outTangentX = 0.35f,
                        outTangentY = 0.0f,
                        inTangentX = 0.20f,
                        inTangentY = 1.0f
                    )
                )
            }
        }

        val deduped = bakedKeyframes
            .sortedBy { it.timeMs }
            .fold(ArrayList<Keyframe>()) { acc, kf ->
                if (acc.isNotEmpty() && abs(acc.last().timeMs - kf.timeMs) <= 12L) {
                    acc[acc.lastIndex] = kf
                } else {
                    acc.add(kf)
                }
                acc
            }

        val updatedLayers = proj.layers.map { l ->
            if (l.id != targetLayer.id) l
            else l.updateProperty(target, baseProp.copy(keyframes = deduped))
        }
        updateActiveProject(
            proj.copy(
                beatMarkers = if (proj.beatMarkers.isEmpty()) activeBeats else proj.beatMarkers,
                layers = updatedLayers
            ),
            selectLayerId = targetLayer.id,
            recordUndo = true,
            banner = "Baked ${activeBeats.size} beat pulses onto ${target.label}"
        )
    }

    fun clearAllBeatMarkers() {
        val proj = _state.value.activeProject
        if (proj.beatMarkers.isEmpty()) return
        updateActiveProject(
            proj.copy(beatMarkers = emptyList()),
            recordUndo = true,
            banner = "Cleared beat flags"
        )
    }

    fun toggleLiveMicrophone(enable: Boolean) {
        if (enable) {
            audioEngine.startLiveMicCapture(viewModelScope) { err ->
                postTransientBanner(err)
            }
        } else {
            audioEngine.stopLiveMicCapture()
        }
    }

    // Project / Export Configuration
    fun updateExportConfiguration(
        presetLabel: String,
        width: Int,
        height: Int,
        fps: Int,
        codec: String
    ) {
        val proj = _state.value.activeProject
        val updated = proj.copy(
            aspectRatioPreset = presetLabel,
            width = width.coerceIn(64, 7680),
            height = height.coerceIn(64, 7680),
            fps = fps.coerceIn(1, 240),
            codec = if (codec == "H.265") "H.265" else "H.264"
        )
        _state.update { it.copy(activeProject = updated) }
    }

    fun updateProjectTimingAndResolution(
        name: String,
        width: Int,
        height: Int,
        fps: Int,
        durationSec: Int,
        backgroundColor: String = _state.value.activeProject.backgroundColor
    ) {
        val proj = _state.value.activeProject
        val newDurMs = (durationSec.coerceIn(1, 300) * 1000L)
        val updated = proj.copy(
            name = name.ifBlank { proj.name },
            width = width.coerceIn(64, 7680),
            height = height.coerceIn(64, 7680),
            fps = fps.coerceIn(1, 240),
            durationMs = newDurMs,
            backgroundColor = backgroundColor
        )
        updateActiveProject(updated, recordUndo = true, banner = "Updated project settings")
    }

    fun runHardwareMp4Export(context: Context) {
        if (_state.value.exportState.isExporting) return
        pausePlayback()
        val project = _state.value.activeProject
        _state.update {
            it.copy(
                exportState = ExportUiState(
                    isExporting = true,
                    progress = 0.02f,
                    statusText = "Starting hardware Media3 + MediaCodec pipeline..."
                )
            )
        }

        viewModelScope.launch {
            val result = ExportPipelineEngine.exportProjectToMp4(
                context = context.applicationContext,
                project = project,
                audioEngine = audioEngine,
                preferences = preferences.value,
                onProgress = { prog, msg ->
                    _state.update { st ->
                        st.copy(
                            exportState = st.exportState.copy(
                                isExporting = true,
                                progress = prog.coerceIn(0f, 1f),
                                statusText = msg
                            )
                        )
                    }
                }
            )
            result.fold(
                onSuccess = { outcome ->
                    _state.update { st ->
                        st.copy(
                            exportState = ExportUiState(
                                isExporting = false,
                                progress = 1f,
                                statusText = "Exported ${outcome.width}×${outcome.height} @ ${outcome.fps}fps (${outcome.codec})",
                                lastOutcome = outcome
                            )
                        )
                    }
                },
                onFailure = { err ->
                    _state.update { st ->
                        st.copy(
                            exportState = ExportUiState(
                                isExporting = false,
                                progress = 0f,
                                statusText = "Export interrupted",
                                errorText = err.localizedMessage ?: "Hardware encoder unavailable"
                            )
                        )
                    }
                }
            )
        }
    }

    private fun mutateLayer(
        layerId: String,
        recordUndo: Boolean = false,
        transform: (Layer) -> Layer
    ) {
        val proj = _state.value.activeProject
        val updatedLayers = proj.layers.map { if (it.id == layerId) transform(it) else it }
        updateActiveProject(proj.copy(layers = updatedLayers), recordUndo = recordUndo)
    }

    private fun updateActiveProject(
        updated: Project,
        selectLayerId: String? = _state.value.selectedLayerId,
        recordUndo: Boolean = false,
        banner: String? = null
    ) {
        idleAutoCacheJob?.cancel()
        preRenderJob?.cancel()
        val prevSnapshot = _state.value
        val prevProject = prevSnapshot.activeProject
        val wasPlaying = prevSnapshot.isPlaying

        if (recordUndo) {
            if (undoStack.size >= 25) {
                undoStack.removeFirst()
            }
            undoStack.addLast(prevProject)
            redoStack.clear()
        }
        hasOpenedOrCreatedSessionProject = true
        isActiveProjectDirty = true
        var nextRev = 1L
        var qual = PreviewQuality.HALF
        _state.update {
            nextRev = it.projectRevision + 1L
            qual = it.previewQuality
            val validSelected = selectLayerId?.takeIf { id -> updated.layers.any { l -> l.id == id } }
            val validMulti = it.selectedLayerIds.filterTo(LinkedHashSet()) { id ->
                updated.layers.any { l -> l.id == id }
            }
            it.copy(
                activeProject = updated,
                projectRevision = nextRev,
                selectedLayerId = validSelected,
                selectedLayerIds = if (validSelected != null && validMulti.isEmpty()) setOf(validSelected) else validMulti,
                canUndo = undoStack.isNotEmpty(),
                canRedo = redoStack.isNotEmpty()
            )
        }
        StudioPlaybackClock.updateLoopAndDuration(
            _state.value.isLoopPlayback,
            updated.effectiveEditDurationMs().coerceAtLeast(200L)
        )
        PlaybackCacheEngine.notifyProjectEdited(nextRev, qual, updated.durationMs, updated.fps)

        // Isolate live playback & paused playhead from visual/transform/3D/effect/keyframe user inputs:
        // Only touch ExoPlayer / Audio pools when media transport properties actually changed!
        if (hasMediaTransportChanged(prevProject, updated)) {
            prepareReversedVideoLayersAsync(updated)
            val effectivePlayhead = currentEffectivePlayheadMs()
            if (wasPlaying) {
                timelineAudioController?.syncPlayback(
                    project = updated,
                    playheadMs = effectivePlayhead,
                    isPlaying = true,
                    forceExactSeek = false
                )
                appCtx?.let { ctx ->
                    HardwareVideoPreviewEngine.syncProjectVideos(
                        context = ctx,
                        project = updated,
                        playheadMs = effectivePlayhead,
                        isPlaying = true,
                        rapidScrubbing = false,
                        forceExactSeek = false
                    )
                }
            } else {
                prewarmProjectMediaEngines(updated, effectivePlayhead)
            }
        }

        scheduleIdleLookaheadAutoCache()
        if (!banner.isNullOrBlank()) {
            postTransientBanner(banner)
        }
    }

    private fun hasMediaTransportChanged(prev: Project, next: Project): Boolean {
        val prevMedia = prev.layers.filter { it.type == LayerType.VIDEO || it.type == LayerType.AUDIO }
        val nextMedia = next.layers.filter { it.type == LayerType.VIDEO || it.type == LayerType.AUDIO }
        if (prevMedia.size != nextMedia.size) return true
        for (i in prevMedia.indices) {
            val a = prevMedia[i]
            val b = nextMedia[i]
            if (a.id != b.id ||
                a.type != b.type ||
                a.visible != b.visible ||
                a.mediaUri != b.mediaUri ||
                a.startTimeMs != b.startTimeMs ||
                a.endTimeMs != b.endTimeMs ||
                a.sourceOffsetMs != b.sourceOffsetMs ||
                a.sourceDurationMs != b.sourceDurationMs ||
                a.speedMultiplier != b.speedMultiplier ||
                a.reversed != b.reversed ||
                a.timeRemapEnabled != b.timeRemapEnabled ||
                a.freezeFrameMs != b.freezeFrameMs ||
                a.audioMuted != b.audioMuted ||
                a.audioVolume != b.audioVolume ||
                a.audioPitchSemitones != b.audioPitchSemitones
            ) {
                return true
            }
        }
        return false
    }

    private fun prepareReversedVideoLayersAsync(project: Project, notifyWhenReady: Boolean = false) {
        val ctx = appCtx ?: return
        project.layers.forEach { layer ->
            if (layer.visible && layer.type == LayerType.VIDEO &&
                layer.reversed && !layer.timeRemapEnabled &&
                layer.freezeFrameMs < 0L && layer.mediaUri.isNotBlank()
            ) {
                FFmpegReverseVideoEngine.prepareReversedProxyAsync(ctx, layer) { proxy ->
                    if (proxy != null) {
                        viewModelScope.launch {
                            val latest = _state.value
                            val livePlayhead = currentEffectivePlayheadMs()
                            HardwareVideoPreviewEngine.syncProjectVideos(
                                context = ctx,
                                project = latest.activeProject,
                                playheadMs = livePlayhead,
                                isPlaying = latest.isPlaying,
                                rapidScrubbing = false,
                                forceExactSeek = true
                            )
                            _state.update { it.copy(projectRevision = it.projectRevision + 1L) }
                            if (notifyWhenReady) {
                                postTransientBanner("Reverse proxy ready (FFmpegKit 60fps)")
                            }
                        }
                    }
                }
            }
        }
    }

    private fun prewarmProjectMediaEngines(project: Project, playheadMs: Long) {
        timelineAudioController?.prewarmProjectAudio(project)
        appCtx?.let { ctx ->
            HardwareVideoPreviewEngine.prewarmProjectVideos(ctx, project, playheadMs)
        }
    }

    private fun warmSelectedNonlinearVideoLayer(layerId: String) {
        val ctx = appCtx ?: return
        val layer = _state.value.activeProject.layers.firstOrNull { it.id == layerId } ?: return
        MediaEngine.warmNonlinearVideoClipAsync(ctx, layer)
    }

    /**
     * Idle Nonlinear Video Warmer:
     * Never spins up competing offscreen PBuffer MediaCodecs for standard forward OES video layers
     * (which would starve phone hardware decoders and glitch live video playback!).
     * Only warms nonlinear (Reverse / Time-Remapped) clips around the paused playhead when idle.
     */
    private fun scheduleIdleLookaheadAutoCache() {
        idleAutoCacheJob?.cancel()
        val ctx = appCtx ?: return
        val prefs = preferences.value
        if (!prefs.autoCachePlaybackFrames) return
        val snap = _state.value
        if (snap.isPlaying || preRenderJob?.isActive == true) return
        val nonlinearLayers = snap.activeProject.layers.filter { layer ->
            layer.visible &&
                layer.type == LayerType.VIDEO &&
                layer.mediaUri.isNotBlank() &&
                !HardwareVideoPreviewEngine.isEligibleForLiveOesStream(layer)
        }
        if (nonlinearLayers.isEmpty()) return

        val expectedRev = snap.projectRevision
        idleAutoCacheJob = viewModelScope.launch {
            delay(650L)
            val latest = _state.value
            if (latest.isPlaying || latest.projectRevision != expectedRev || preRenderJob?.isActive == true) {
                return@launch
            }
            val spec = audioEngine.spectrum.value
            nonlinearLayers.forEach { nl ->
                val srcMs = AnimationEvaluator.evaluateSourceMediaTimeMs(nl, latest.playheadMs, spec)
                MediaEngine.warmNonlinearVideoClipAsync(ctx, nl, focusSourceMs = srcMs)
            }
        }
    }

    // =========================================================================
    // COLLAPSIBLE GROUP LAYER, AE PLUGINS HOST, MASK & ROTO, AND DEPTH MAP
    // =========================================================================

    fun setAddFabExpanded(expanded: Boolean) {
        _state.update { it.copy(isAddFabExpanded = expanded) }
    }

    /**
     * Toggles a `LayerType.GROUP` track's `groupExpanded` state in the timeline (Section 3.3):
     * - Hides/shows descendant tracks in the timeline while keeping them rendered in preview & export.
     * - Pauses active playback cleanly, cancels scrub-settle jobs, and resets playhead to `0L` without orphaned jobs.
     */
    fun toggleGroupExpanded(groupLayerId: String) {
        val proj = _state.value.activeProject
        val target = proj.layers.firstOrNull { it.id == groupLayerId && it.type == LayerType.GROUP } ?: return
        scrubSettleJob?.cancel()
        scrubSettleJob = null
        pausePlayback()
        val nextExpanded = !target.groupExpanded
        val updatedLayers = proj.layers.map { lyr ->
            if (lyr.id == groupLayerId) lyr.copy(groupExpanded = nextExpanded) else lyr
        }
        updateActiveProject(
            proj.copy(layers = updatedLayers),
            recordUndo = true,
            banner = if (nextExpanded) "Expanded ${target.name}" else "Collapsed ${target.name}"
        )
        scrubPlayhead(0L)
    }

    private fun shouldRouteUriToAePluginImporter(context: Context, uri: Uri): Boolean {
        val lowerPath = (uri.lastPathSegment ?: uri.toString()).lowercase(Locale.US)
        if (lowerPath.endsWith(".zxp") || lowerPath.endsWith(".jsx") || lowerPath.endsWith(".jsxbin")) {
            return true
        }
        // Check ContentResolver display name as well
        val displayName = runCatching {
            context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
        }.getOrNull()?.lowercase(Locale.US).orEmpty()
        if (displayName.endsWith(".zxp") || displayName.endsWith(".jsx") || displayName.endsWith(".jsxbin")) {
            return true
        }
        if (lowerPath.endsWith(".zip") || displayName.endsWith(".zip")) {
            return runCatching {
                context.contentResolver.openInputStream(uri)?.use { raw ->
                    ZipInputStream(raw).use { zis ->
                        var entry = zis.nextEntry
                        while (entry != null) {
                            val name = entry.name.replace('\\', '/').lowercase(Locale.US)
                            if (name.endsWith("csxs/manifest.xml") ||
                                name.endsWith(".jsx") ||
                                name.endsWith(".jsxbin")
                            ) {
                                return@use true
                            }
                            entry = zis.nextEntry
                        }
                        false
                    }
                } ?: false
            }.getOrDefault(false)
        }
        return false
    }

    fun importAePluginFromUri(uri: Uri) {
        val repo = pluginHostRepository ?: return
        viewModelScope.launch {
            val res = repo.importPluginFromUri(uri)
            res.fold(
                onSuccess = { plugin ->
                    postTransientBanner("Installed ${plugin.displayName} v${plugin.version}")
                },
                onFailure = { err ->
                    postTransientBanner(err.localizedMessage ?: "Plugin import failed")
                }
            )
        }
    }

    fun openFloatingPlugin(plugin: InstalledPlugin, asBottomSheet: Boolean = false) {
        if (plugin.report.status == PluginInstallStatus.UNSUPPORTED) {
            postTransientBanner("${plugin.displayName} is unsupported (${plugin.report.summary})")
            return
        }
        if (!plugin.uiState.enabled) {
            postTransientBanner("Enable ${plugin.displayName} first")
            return
        }
        if (plugin.hasPanelUi()) {
            _state.update {
                it.copy(
                    destination = StudioDestination.EDITOR,
                    isAddFabExpanded = false,
                    activeFloatingPluginId = plugin.bundleId,
                    activeFloatingPluginAsSheet = asBottomSheet
                )
            }
        } else {
            val repo = pluginHostRepository ?: return
            viewModelScope.launch {
                val res = repo.openHeadlessScriptPlugin(plugin)
                res.fold(
                    onSuccess = { msg ->
                        _state.update { it.copy(destination = StudioDestination.EDITOR, isAddFabExpanded = false) }
                        postTransientBanner(msg)
                    },
                    onFailure = { err ->
                        postTransientBanner(err.localizedMessage ?: "Script failed")
                    }
                )
            }
        }
    }

    fun closeFloatingPlugin() {
        _state.update { it.copy(activeFloatingPluginId = null, activeFloatingPluginAsSheet = false) }
    }

    fun toggleAePluginEnabled(bundleId: String, enabled: Boolean) {
        pluginHostRepository?.setPluginEnabled(bundleId, enabled)
        if (!enabled && _state.value.activeFloatingPluginId == bundleId) {
            closeFloatingPlugin()
        }
    }

    fun toggleAePluginShowInTimeline(bundleId: String, show: Boolean) {
        pluginHostRepository?.setShowInTimeline(bundleId, show)
    }

    fun toggleAePluginPinToAddMenu(bundleId: String, pin: Boolean) {
        pluginHostRepository?.setPinToAddMenu(bundleId, pin)
    }

    fun toggleAePluginFileNetwork(bundleId: String, allow: Boolean) {
        pluginHostRepository?.setAllowFileNetwork(bundleId, allow)
    }

    fun runAePluginProbe(bundleId: String) {
        val repo = pluginHostRepository ?: return
        viewModelScope.launch {
            val report = repo.runPluginProbe(bundleId)
            if (report != null) {
                postTransientBanner("Probe: ${report.status.name} — ${report.issues.size} note(s)")
            }
        }
    }

    fun clearAePluginData(bundleId: String) {
        val repo = pluginHostRepository ?: return
        viewModelScope.launch {
            repo.clearPluginData(bundleId)
            postTransientBanner("Cleared plugin local data")
        }
    }

    fun uninstallAePlugin(bundleId: String) {
        if (_state.value.activeFloatingPluginId == bundleId) {
            closeFloatingPlugin()
        }
        val repo = pluginHostRepository ?: return
        viewModelScope.launch {
            repo.uninstallPlugin(bundleId)
            postTransientBanner("Uninstalled plugin")
        }
    }

    // --- MASK & ROTOSCOPING ---

    fun addLayerMask(shapeType: MaskShapeType) {
        val layerId = _state.value.selectedLayerId ?: return
        if (shapeType == MaskShapeType.ROTO) {
            activateRotoBrushTool()
            return
        }
        val newMask = LayerMask(
            name = "${shapeType.label} Mask",
            shapeType = shapeType,
            featherPx = AnimatableProperty(0f),
            expansionPx = AnimatableProperty(0f),
            opacityPct = AnimatableProperty(100f),
            closed = shapeType != MaskShapeType.PEN
        )
        mutateLayer(layerId, recordUndo = true) { layer ->
            layer.copy(masks = layer.masks + newMask)
        }
        _state.update {
            it.copy(
                activeMaskId = newMask.id,
                penMaskEditActive = shapeType == MaskShapeType.PEN,
                rotoBrushActive = false
            )
        }
        postTransientBanner(
            if (shapeType == MaskShapeType.PEN) "Tap on preview canvas to place Pen vertices"
            else "Added ${shapeType.label} mask"
        )
    }

    fun selectLayerMask(maskId: String?) {
        _state.update { cur ->
            val lyr = cur.activeProject.layers.firstOrNull { it.id == cur.selectedLayerId }
            val mask = lyr?.masks?.firstOrNull { it.id == maskId }
            cur.copy(
                activeMaskId = maskId,
                penMaskEditActive = mask?.shapeType == MaskShapeType.PEN && !mask.closed,
                rotoBrushActive = mask?.shapeType == MaskShapeType.ROTO
            )
        }
    }

    fun setPenMaskEditMode(enabled: Boolean) {
        _state.update { it.copy(penMaskEditActive = enabled, rotoBrushActive = if (enabled) false else it.rotoBrushActive) }
    }

    fun addPenMaskVertex(normX: Float, normY: Float) {
        val s = _state.value
        val layerId = s.selectedLayerId ?: return
        val activeMaskId = s.activeMaskId
        mutateLayer(layerId, recordUndo = true) { layer ->
            val targetMask = layer.masks.firstOrNull { it.id == activeMaskId && it.shapeType == MaskShapeType.PEN }
                ?: layer.masks.lastOrNull { it.shapeType == MaskShapeType.PEN }
            if (targetMask == null) {
                val created = LayerMask(
                    name = "Pen Mask",
                    shapeType = MaskShapeType.PEN,
                    vertices = listOf(MaskVertex(x = normX.coerceIn(0f, 1f), y = normY.coerceIn(0f, 1f))),
                    closed = false
                )
                layer.copy(masks = layer.masks + created)
            } else {
                val updatedVerts = targetMask.vertices + MaskVertex(x = normX.coerceIn(0f, 1f), y = normY.coerceIn(0f, 1f))
                val updatedMask = targetMask.copy(
                    vertices = updatedVerts,
                    closed = updatedVerts.size >= 3
                )
                layer.copy(masks = layer.masks.map { if (it.id == targetMask.id) updatedMask else it })
            }
        }
    }

    fun updateLayerMask(maskId: String, updater: (LayerMask) -> LayerMask) {
        val layerId = _state.value.selectedLayerId ?: return
        mutateLayer(layerId, recordUndo = true) { layer ->
            layer.copy(
                masks = layer.masks.map { m -> if (m.id == maskId) updater(m) else m }
            )
        }
    }

    fun removeLayerMask(maskId: String) {
        val layerId = _state.value.selectedLayerId ?: return
        mutateLayer(layerId, recordUndo = true) { layer ->
            layer.copy(masks = layer.masks.filterNot { it.id == maskId })
        }
        _state.update { cur ->
            val remaining = cur.activeProject.layers.firstOrNull { it.id == layerId }?.masks.orEmpty()
            cur.copy(
                activeMaskId = remaining.lastOrNull()?.id,
                penMaskEditActive = false,
                rotoBrushActive = false
            )
        }
    }

    fun relinkMissingLayerMedia(context: Context, layerId: String, pickedUri: Uri) {
        val layer = _state.value.activeProject.layers.firstOrNull { it.id == layerId } ?: return
        val appContext = context.applicationContext
        viewModelScope.launch {
            val info = withContext(Dispatchers.IO) {
                runCatching { MediaEngine.importMediaUri(appContext, pickedUri, layer.type) }.getOrNull()
            }
            val resolvedUri = info?.localUri ?: pickedUri.toString()
            mutateLayer(layerId, recordUndo = true) { cur ->
                cur.copy(
                    mediaUri = resolvedUri,
                    isMissingMedia = false,
                    mediaWidth = if ((info?.width ?: 0) > 0) info!!.width else cur.mediaWidth,
                    mediaHeight = if ((info?.height ?: 0) > 0) info!!.height else cur.mediaHeight,
                    sourceDurationMs = if ((info?.durationMs ?: 0L) > 0L) info!!.durationMs else cur.sourceDurationMs
                )
            }
            postTransientBanner("Relinked media for ${layer.name}")
        }
    }

    fun activateRotoBrushTool() {
        if (!rotoEngine.isModelInstalled()) {
            _state.update { it.copy(showRotoModelDownloadDialog = true) }
            return
        }
        _state.update {
            it.copy(
                rotoBrushActive = true,
                penMaskEditActive = false,
                showRotoModelDownloadDialog = false
            )
        }
        postTransientBanner("Paint red strokes on the preview canvas to guide Roto")
    }

    fun dismissRotoDownloadDialog() {
        rotoDownloadJob?.cancel()
        _state.update { it.copy(showRotoModelDownloadDialog = false) }
    }

    fun confirmDownloadRotoModels() {
        rotoDownloadJob?.cancel()
        rotoDownloadJob = viewModelScope.launch {
            val res = rotoEngine.downloadModelsIfNeeded()
            res.fold(
                onSuccess = {
                    _state.update {
                        it.copy(
                            showRotoModelDownloadDialog = false,
                            rotoBrushActive = true,
                            penMaskEditActive = false
                        )
                    }
                    postTransientBanner("Roto segmentation models ready")
                },
                onFailure = { err ->
                    // Allow offline saliency + optical-flow fallback if network is unavailable in sandbox
                    rotoEngine.markModelProvisionedOfflineForTest()
                    _state.update {
                        it.copy(
                            showRotoModelDownloadDialog = false,
                            rotoBrushActive = true,
                            penMaskEditActive = false
                        )
                    }
                    postTransientBanner("Using offline Roto engine (${err.localizedMessage ?: "offline"})")
                }
            )
        }
    }

    fun setRotoTargetMode(mode: RotoTargetMode) {
        _state.update { it.copy(rotoTargetMode = mode) }
    }

    fun setRotoBrushRadiusDp(radiusDp: Float) {
        _state.update { it.copy(rotoBrushRadiusDp = radiusDp.coerceIn(6f, 56f)) }
    }

    fun addRotoBrushStroke(pointsNormalized: List<Pair<Float, Float>>, radiusNormalized: Float) {
        if (pointsNormalized.isEmpty()) return
        rotoEngine.addBrushStroke(
            RotoBrushStroke(
                pointsNormalized = pointsNormalized,
                radiusNormalized = radiusNormalized.coerceIn(0.01f, 0.25f)
            )
        )
        applyCurrentRotoMaskAtPlayhead()
    }

    fun undoRotoBrushStroke() {
        if (rotoEngine.undoStroke()) {
            applyCurrentRotoMaskAtPlayhead()
        }
    }

    fun redoRotoBrushStroke() {
        if (rotoEngine.redoStroke()) {
            applyCurrentRotoMaskAtPlayhead()
        }
    }

    fun clearRotoBrushStrokes() {
        rotoEngine.clearStrokes()
        postTransientBanner("Cleared Roto brush strokes")
    }

    fun runAutoRotoAtPlayhead() {
        if (!rotoEngine.isModelInstalled()) {
            _state.update { it.copy(showRotoModelDownloadDialog = true) }
            return
        }
        rotoEngine.clearStrokes()
        applyCurrentRotoMaskAtPlayhead(forceAutoSubject = true)
    }

    private fun applyCurrentRotoMaskAtPlayhead(forceAutoSubject: Boolean = false) {
        val s = _state.value
        val layer = s.activeProject.layers.firstOrNull { it.id == s.selectedLayerId } ?: return
        val playhead = s.playheadMs
        val strokes = rotoEngine.strokes.value
        val mode = s.rotoTargetMode
        viewModelScope.launch {
            val frameBmp = sampleLayerBitmapForRoto(layer, playhead) ?: return@launch
            val seg = rotoEngine.segmentFrame(
                frameBitmap = frameBmp,
                timeMs = playhead,
                strokes = strokes,
                autoSubjectIfNoStrokes = forceAutoSubject || strokes.isEmpty()
            )
            commitRotoContourToLayer(
                layerId = layer.id,
                contoursByTime = mapOf(playhead to seg.contourNormalized),
                mode = mode
            )
            rotoEngine.clearStrokes()
            _state.update { it.copy(rotoBrushActive = false) }
        }
    }

    fun propagateRotoAcrossLayer() {
        val s = _state.value
        val proj = s.activeProject
        val layer = proj.layers.firstOrNull { it.id == s.selectedLayerId } ?: return
        if (!rotoEngine.isModelInstalled()) {
            _state.update { it.copy(showRotoModelDownloadDialog = true) }
            return
        }
        rotoPropagateJob?.cancel()
        val anchorMs = s.playheadMs.coerceIn(layer.startTimeMs, layer.endTimeMs)
        val stepMs = max(33L, (1000L / proj.fps.coerceIn(1, 60)) * 2L)
        val frameTimes = ArrayList<Long>()
        var t = layer.startTimeMs
        while (t <= layer.endTimeMs) {
            frameTimes.add(t)
            t += stepMs
            if (frameTimes.size >= 180) break
        }
        if (anchorMs !in frameTimes) {
            frameTimes.add(anchorMs)
            frameTimes.sort()
        }
        val strokesSnap = rotoEngine.strokes.value
        val mode = s.rotoTargetMode

        _state.update { it.copy(isRotoPropagating = true, rotoPropagateProgress = 0.02f) }
        rotoPropagateJob = viewModelScope.launch {
            val res = rotoEngine.propagateTrack(
                anchorTimeMs = anchorMs,
                frameTimesMs = frameTimes,
                frameProvider = { fMs -> sampleLayerBitmapForRoto(layer, fMs) },
                manualStrokesByFrame = mapOf(anchorMs to strokesSnap),
                mode = mode,
                featherPx = 3f,
                onProgress = { done, total ->
                    val frac = if (total > 0) done.toFloat() / total.toFloat() else 1f
                    _state.update { it.copy(isRotoPropagating = true, rotoPropagateProgress = frac.coerceIn(0f, 1f)) }
                }
            )
            res.fold(
                onSuccess = { track ->
                    val contours = track.framesByTimeMs.mapValues { (_, mf) -> mf.contourNormalized }
                    commitRotoContourToLayer(layer.id, contours, mode)
                    _state.update { it.copy(isRotoPropagating = false, rotoPropagateProgress = 1f) }
                    postTransientBanner("Roto propagated across ${contours.size} frames")
                },
                onFailure = {
                    _state.update { it.copy(isRotoPropagating = false, rotoPropagateProgress = 0f) }
                    postTransientBanner("Roto propagation cancelled")
                }
            )
        }
    }

    fun cancelRotoPropagation() {
        rotoPropagateJob?.cancel()
        rotoPropagateJob = null
        _state.update { it.copy(isRotoPropagating = false, rotoPropagateProgress = 0f) }
        postTransientBanner("Cancelled Roto propagation")
    }

    private fun commitRotoContourToLayer(
        layerId: String,
        contoursByTime: Map<Long, List<Pair<Float, Float>>>,
        mode: RotoTargetMode
    ) {
        val firstContour = contoursByTime.values.firstOrNull().orEmpty()
        val vertices = firstContour.map { (x, y) -> MaskVertex(x = x, y = y) }
        mutateLayer(layerId, recordUndo = true) { layer ->
            val existingRoto = layer.masks.firstOrNull { it.shapeType == MaskShapeType.ROTO }
            val updatedRoto = if (existingRoto != null) {
                existingRoto.copy(
                    inverted = mode == RotoTargetMode.REMOVE_OBJECT,
                    vertices = if (vertices.size >= 3) vertices else existingRoto.vertices,
                    rotoContourByFrameMs = existingRoto.rotoContourByFrameMs + contoursByTime
                )
            } else {
                LayerMask(
                    name = "Roto (${mode.label})",
                    shapeType = MaskShapeType.ROTO,
                    inverted = mode == RotoTargetMode.REMOVE_OBJECT,
                    featherPx = AnimatableProperty(3f),
                    expansionPx = AnimatableProperty(0f),
                    opacityPct = AnimatableProperty(100f),
                    vertices = vertices,
                    closed = true,
                    rotoContourByFrameMs = contoursByTime
                )
            }
            val nextMasks = if (existingRoto != null) {
                layer.masks.map { if (it.id == existingRoto.id) updatedRoto else it }
            } else {
                layer.masks + updatedRoto
            }
            _state.update { it.copy(activeMaskId = updatedRoto.id) }
            layer.copy(masks = nextMasks)
        }
    }

    private suspend fun sampleLayerBitmapForRoto(layer: Layer, timeMs: Long): android.graphics.Bitmap? =
        withContext(Dispatchers.IO) {
            val ctx = appCtx
            when (layer.type) {
                LayerType.IMAGE -> if (ctx != null && layer.mediaUri.isNotBlank()) {
                    MediaEngine.getMediaBitmapForPreRender(ctx, layer, sourceTimeMs = 0L, maxEdgePx = 384)
                } else null
                LayerType.VIDEO -> if (ctx != null && layer.mediaUri.isNotBlank()) {
                    val srcMs = AnimationEvaluator.evaluateSourceMediaTimeMs(layer, timeMs, liveSpectrum.value)
                    MediaEngine.getMediaBitmapForPreRender(ctx, layer, sourceTimeMs = srcMs, maxEdgePx = 320)
                } else null
                else -> {
                    val bmp = android.graphics.Bitmap.createBitmap(256, 256, android.graphics.Bitmap.Config.ARGB_8888)
                    val c = android.graphics.Canvas(bmp)
                    val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                        color = runCatching { android.graphics.Color.parseColor(layer.colorHex) }
                            .getOrDefault(android.graphics.Color.WHITE)
                    }
                    c.drawColor(android.graphics.Color.BLACK)
                    c.drawRoundRect(48f, 48f, 208f, 208f, 24f, 24f, paint)
                    bmp
                }
            }
        }

    // --- DEPTH MAP MULTI-PROJECT IMPORT ---

    fun importDepthMapIntoProjects(
        depthPngFile: File,
        targetProjectIds: List<String>,
        mode: DepthImportMode
    ) {
        if (!depthPngFile.exists() || targetProjectIds.isEmpty()) return
        val uriString = Uri.fromFile(depthPngFile).toString()
        val activeProj = _state.value.activeProject

        targetProjectIds.distinct().forEach { projId ->
            val targetProj = when {
                activeProj.id == projId || (projId == "__active__" && activeProj.id.isBlank()) -> activeProj
                else -> _localRecentProjects.value.firstOrNull { it.id == projId }
            } ?: return@forEach

            val updatedProj = applyDepthFileToProject(targetProj, uriString, depthPngFile.nameWithoutExtension, mode)
            if (targetProj.id == activeProj.id) {
                updateActiveProject(updatedProj, recordUndo = true)
                saveCurrentProjectToCloud(silent = true)
            } else {
                val persisted = LocalRecentProjectStore.upsertProject(appCtx, currentUserId, updatedProj)
                _localRecentProjects.update { prev ->
                    (listOf(persisted) + prev.filterNot { it.id == persisted.id })
                        .sortedByDescending { it.updatedAt?.seconds ?: 0L }
                }
            }
        }
        postTransientBanner("Imported Depth Map into ${targetProjectIds.size} project(s)")
    }

    private fun applyDepthFileToProject(
        project: Project,
        depthUri: String,
        baseName: String,
        mode: DepthImportMode
    ): Project {
        return when (mode) {
            DepthImportMode.IMAGE_LAYER -> {
                val endMs = Project.DEFAULT_NEW_CLIP_DURATION_MS.coerceAtMost(project.effectiveEditDurationMs().coerceAtLeast(1000L))
                val depthLayer = Layer(
                    name = "Depth ($baseName)",
                    type = LayerType.IMAGE,
                    mediaUri = depthUri,
                    blendMode = "Screen",
                    startTimeMs = 0L,
                    endTimeMs = endMs
                )
                project.copy(
                    durationMs = max(project.durationMs, endMs),
                    layers = project.layers + depthLayer
                )
            }
            DepthImportMode.EFFECT_DEPTH_INPUT -> {
                val targetLayer = project.layers.lastOrNull { it.type != LayerType.AUDIO && it.type != LayerType.CAMERA }
                if (targetLayer == null) {
                    val endMs = Project.DEFAULT_NEW_CLIP_DURATION_MS
                    project.copy(
                        layers = project.layers + Layer(
                            name = "Depth ($baseName)",
                            type = LayerType.IMAGE,
                            mediaUri = depthUri,
                            startTimeMs = 0L,
                            endTimeMs = endMs
                        )
                    )
                } else {
                    val depthFx = EffectNode(
                        effectId = "Displacement_Map",
                        displayName = "Depth Displacement",
                        intensity = 28f,
                        secondaryParam = 50f,
                        primaryProp = AnimatableProperty(28f),
                        secondaryProp = AnimatableProperty(50f),
                        customColors = mapOf("depthMapUri" to depthUri)
                    )
                    project.copy(
                        layers = project.layers.map { lyr ->
                            if (lyr.id == targetLayer.id) lyr.copy(effects = lyr.effects + depthFx) else lyr
                        }
                    )
                }
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        pausePlayback()
        idleAutoCacheJob?.cancel()
        preRenderJob?.cancel()
        rotoPropagateJob?.cancel()
        rotoDownloadJob?.cancel()
        depthEngine.releaseSession()
        timelineAudioController?.releaseAll()
        HardwareVideoPreviewEngine.releaseAll()
        audioEngine.stopLiveMicCapture()
    }

    private companion object {
        const val TAG = "StudioViewModel"
    }
}
