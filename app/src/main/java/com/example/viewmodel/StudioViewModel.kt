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
import com.example.data.model.Keyframe
import com.example.data.model.Layer
import com.example.data.model.LayerType
import com.example.data.model.Primitive3DType
import com.example.data.model.Project
import com.example.data.model.ProjectPayloadSerializer
import com.example.data.model.PropertyTarget
import com.example.data.repository.ProjectRepository
import com.example.engine.AnimationEvaluator
import com.example.engine.AudioReactorEngine
import com.example.engine.AudioSpectrumSnapshot
import com.example.engine.EffectCategory
import com.example.engine.EffectDefinition
import com.example.engine.EffectsCatalog
import com.example.engine.ExportOutcome
import com.example.engine.ExportPipelineEngine
import com.example.engine.MediaEngine
import com.example.engine.TimelineAudioController
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONArray
import java.util.UUID
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
    EFFECTS
}

data class ExportUiState(
    val isExporting: Boolean = false,
    val progress: Float = 0f,
    val statusText: String = "Ready to encode hardware-accelerated MP4",
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
        durationMs = 10000L,
        layers = emptyList(),
        beatMarkers = emptyList()
    ),
    val selectedLayerId: String? = null,
    val selectedProperty: PropertyTarget = PropertyTarget.POS_X,
    val inspectorTab: AmInspectorTab = AmInspectorTab.TIMELINE,
    val playheadMs: Long = 0L,
    val timelineZoom: Float = 1.0f,
    val isPlaying: Boolean = false,
    val canUndo: Boolean = false,
    val canRedo: Boolean = false,
    val hasCopiedLayer: Boolean = false,
    val hasCopiedEffects: Boolean = false,
    val canvasZoom: Float = 1.0f,
    val canvasPanX: Float = 0f,
    val canvasPanY: Float = 0f,
    val cameraYawDeg: Float = 26f,
    val cameraPitchDeg: Float = 18f,
    val cameraDistance: Float = 4.2f,
    val effectSearchQuery: String = "",
    val selectedEffectCategory: EffectCategory? = null,
    val audioSensitivity: Float = 0.72f,
    val statusBanner: String? = null,
    val isSavingCloud: Boolean = false,
    val exportState: ExportUiState = ExportUiState()
)

object StudioTemplates {
    fun neonSynthTemplate(): Project {
        val nullRigId = "rig_${UUID.randomUUID().toString().take(8)}"
        val rigLayer = Layer(
            id = nullRigId,
            name = "Master Camera Null",
            type = LayerType.NULL_OBJECT,
            scale = AnimatableProperty(
                baseValue = 100f,
                keyframes = listOf(
                    Keyframe(timeMs = 0L, value = 92f, outTangentX = 0.25f, outTangentY = 0.1f, inTangentX = 0.25f, inTangentY = 1f),
                    Keyframe(timeMs = 3000L, value = 112f, outTangentX = 0.42f, outTangentY = 0f, inTangentX = 0.58f, inTangentY = 1f),
                    Keyframe(timeMs = 6000L, value = 100f)
                )
            )
        )
        val heroShape = Layer(
            name = "Cyber Prism Core",
            type = LayerType.SHAPE,
            shapeKind = "Star",
            parentId = nullRigId,
            colorHex = "#00D2A8",
            shadowEnabled = true,
            shadowColorHex = "#00D2A8",
            motionBlurEnabled = true,
            rotationZ = AnimatableProperty(
                baseValue = 0f,
                keyframes = listOf(
                    Keyframe(timeMs = 0L, value = -18f, outTangentX = 0.2f, outTangentY = 0.8f, inTangentX = 0.3f, inTangentY = 1f),
                    Keyframe(timeMs = 4500L, value = 180f),
                    Keyframe(timeMs = 9000L, value = 360f)
                )
            ),
            scale = AnimatableProperty(
                baseValue = 100f,
                audioReactorBand = AudioBand.BASS,
                audioReactorGain = 0.45f
            ),
            effects = listOf(
                EffectsCatalog.findById("S_Glow").toNode().copy(
                    intensity = 0.78f,
                    audioBoundBand = AudioBand.BASS,
                    audioSensitivity = 0.85f
                ),
                EffectsCatalog.findById("Chromatic_Aberration").toNode().copy(intensity = 0.42f)
            )
        )
        val titleLayer = Layer(
            name = "Kinetic Title",
            type = LayerType.TEXT,
            parentId = nullRigId,
            textContent = "FRAME POLLUTION",
            colorHex = "#FF3366",
            posY = AnimatableProperty(
                baseValue = -40f,
                expression = "sin(time * 3.2) * 24"
            ),
            effects = listOf(
                EffectsCatalog.findById("Glitch_Scanlines").toNode().copy(intensity = 0.35f)
            )
        )
        val cube3D = Layer(
            name = "3D Holo Torus",
            type = LayerType.MODEL_3D,
            primitive3D = Primitive3DType.TORUS,
            colorHex = "#0094FF",
            scale = AnimatableProperty(95f)
        )
        return Project(
            name = "Cyber Synthwave 60FPS",
            aspectRatioPreset = "16:9",
            width = 1920,
            height = 1080,
            fps = 60,
            durationMs = 10000L,
            codec = "H.264",
            bpm = 128,
            layers = listOf(rigLayer, heroShape, cube3D, titleLayer)
        )
    }

    fun reelsPhonkTemplate(): Project {
        val mainLayer = Layer(
            name = "Phonk Impact Card",
            type = LayerType.SHAPE,
            shapeKind = "Rounded Rect",
            colorHex = "#FF3366",
            motionBlurEnabled = true,
            scale = AnimatableProperty(
                baseValue = 105f,
                audioReactorBand = AudioBand.BASS,
                audioReactorGain = 0.7f
            ),
            effects = listOf(
                EffectsCatalog.findById("Beat_Sync_Pulse").toNode().copy(intensity = 0.7f, audioBoundBand = AudioBand.BASS),
                EffectsCatalog.findById("RGB_Split").toNode().copy(intensity = 0.55f),
                EffectsCatalog.findById("S_Glow").toNode().copy(intensity = 0.65f)
            )
        )
        val caption = Layer(
            name = "9:16 Viral Hook",
            type = LayerType.TEXT,
            textContent = "VELOCITY EDIT",
            colorHex = "#FFFFFF",
            posY = AnimatableProperty(
                baseValue = 180f,
                expression = "spring(3, 4, 48)"
            )
        )
        return Project(
            name = "TikTok Velocity Shake",
            aspectRatioPreset = "9:16",
            width = 1080,
            height = 1920,
            fps = 60,
            durationMs = 8000L,
            codec = "H.265",
            bpm = 145,
            layers = listOf(mainLayer, caption)
        )
    }

    fun particleCinemaTemplate(): Project {
        val meshLayer = Layer(
            name = "3D Crystal Cube",
            type = LayerType.MODEL_3D,
            primitive3D = Primitive3DType.CUBE,
            colorHex = "#00D2A8",
            scale = AnimatableProperty(115f),
            effects = listOf(
                EffectsCatalog.findById("Stardust_Particles").toNode().copy(intensity = 0.85f),
                EffectsCatalog.findById("BCC_FilmGlow").toNode().copy(intensity = 0.6f)
            )
        )
        val subtitle = Layer(
            name = "Anamorphic Title",
            type = LayerType.TEXT,
            textContent = "21:9 CINEMA",
            colorHex = "#FFC400",
            posY = AnimatableProperty(140f)
        )
        return Project(
            name = "Stardust 21:9 Anamorphic",
            aspectRatioPreset = "21:9",
            width = 2560,
            height = 1080,
            fps = 24,
            durationMs = 12000L,
            codec = "H.265",
            bpm = 110,
            layers = listOf(meshLayer, subtitle)
        )
    }
}

class StudioViewModel(
    private val repository: ProjectRepository,
    private val currentUserId: String,
    appContext: Context? = null
) : ViewModel() {

    val audioEngine = AudioReactorEngine()
    private val timelineAudioController = appContext?.applicationContext?.let { TimelineAudioController(it) }

    private val undoStack = ArrayDeque<Project>()
    private val redoStack = ArrayDeque<Project>()
    private var clipboardLayer: Layer? = null
    private var clipboardEffects: List<EffectNode> = emptyList()

    val cloudProjects: StateFlow<UiState<List<Project>>> = repository.observeProjects(currentUserId)
        .map<List<Project>, UiState<List<Project>>> { UiState.Success(it) }
        .catch { err ->
            Log.w(TAG, "Error observing projects", err)
            emit(UiState.Error(err.localizedMessage ?: "Unable to sync cloud projects"))
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000L),
            initialValue = UiState.Loading
        )

    val cloudPresets: StateFlow<UiState<List<CustomEffectPresetDocument>>> = repository.observePresets(currentUserId)
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

    private val _state = MutableStateFlow(StudioWorkspaceState())
    val state: StateFlow<StudioWorkspaceState> = _state.asStateFlow()

    val liveSpectrum: StateFlow<AudioSpectrumSnapshot> = audioEngine.spectrum
    val isLiveMicActive: StateFlow<Boolean> = audioEngine.isLiveMicActive

    private var playbackJob: Job? = null
    private var bannerClearJob: Job? = null

    private fun postTransientBanner(message: String?) {
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

    fun navigateTo(destination: StudioDestination) {
        if (destination == StudioDestination.HOME) {
            pausePlayback()
        }
        _state.update { it.copy(destination = destination, statusBanner = null) }
    }

    fun navigateBackToParent() {
        val current = _state.value
        when {
            current.destination == StudioDestination.EDITOR &&
                current.inspectorTab != AmInspectorTab.TIMELINE &&
                current.inspectorTab != AmInspectorTab.LAYER_OVERVIEW -> {
                _state.update { it.copy(inspectorTab = AmInspectorTab.LAYER_OVERVIEW) }
            }
            current.destination == StudioDestination.EDITOR &&
                current.inspectorTab == AmInspectorTab.LAYER_OVERVIEW -> {
                _state.update { it.copy(inspectorTab = AmInspectorTab.TIMELINE) }
            }
            current.destination == StudioDestination.EDITOR -> {
                pausePlayback()
                _state.update { it.copy(destination = StudioDestination.HOME) }
            }
            current.destination != StudioDestination.HOME -> {
                _state.update { it.copy(destination = StudioDestination.EDITOR) }
            }
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
            current.copy(inspectorTab = tab, selectedProperty = nextProp)
        }
    }

    fun setTimelineZoom(newZoom: Float) {
        _state.update { it.copy(timelineZoom = newZoom.coerceIn(0.6f, 4.0f)) }
    }

    fun undo() {
        if (undoStack.isEmpty()) return
        val current = _state.value.activeProject
        redoStack.addLast(current)
        val restored = undoStack.removeLast()
        _state.update {
            it.copy(
                activeProject = restored,
                selectedLayerId = it.selectedLayerId?.takeIf { id -> restored.layers.any { l -> l.id == id } }
                    ?: restored.layers.firstOrNull()?.id,
                canUndo = undoStack.isNotEmpty(),
                canRedo = redoStack.isNotEmpty()
            )
        }
        postTransientBanner("Undo")
    }

    fun redo() {
        if (redoStack.isEmpty()) return
        val current = _state.value.activeProject
        undoStack.addLast(current)
        val nextProj = redoStack.removeLast()
        _state.update {
            it.copy(
                activeProject = nextProj,
                selectedLayerId = it.selectedLayerId?.takeIf { id -> nextProj.layers.any { l -> l.id == id } }
                    ?: nextProj.layers.firstOrNull()?.id,
                canUndo = undoStack.isNotEmpty(),
                canRedo = redoStack.isNotEmpty()
            )
        }
        postTransientBanner("Redo")
    }

    /**
     * Creates a 100% CLEAN EMPTY project with ZERO premade layers, ZERO premade effects,
     * and ZERO premade bookmarks — exact Alight Motion new project behavior!
     */
    fun createNewProject(
        name: String,
        preset: AspectRatioPreset,
        customWidth: Int,
        customHeight: Int,
        fps: Int,
        durationSec: Int,
        bpm: Int,
        backgroundColor: String = "Black"
    ) {
        pausePlayback()
        undoStack.clear()
        redoStack.clear()
        val w = customWidth.coerceIn(64, 7680)
        val h = customHeight.coerceIn(64, 7680)
        val safeFps = fps.coerceIn(1, 240)
        val durMs = (durationSec.coerceIn(1, 300) * 1000L)
        val safeBpm = bpm.coerceIn(40, 240)

        val newProj = Project(
            name = name.ifBlank { "New Project" },
            aspectRatioPreset = preset.label,
            width = w,
            height = h,
            fps = safeFps,
            durationMs = durMs,
            codec = "H.264",
            bpm = safeBpm,
            backgroundColor = backgroundColor,
            layers = emptyList(),
            beatMarkers = emptyList()
        )

        _state.update {
            it.copy(
                activeProject = newProj,
                selectedLayerId = null,
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
        postTransientBanner("Clean project ${w}×${h} @ ${safeFps}fps")
        saveCurrentProjectToCloud(silent = true)
    }

    fun openProject(project: Project) {
        pausePlayback()
        undoStack.clear()
        redoStack.clear()
        audioEngine.sampleTimelineSpectrum(0L, project.bpm, project.beatMarkers)
        _state.update {
            it.copy(
                activeProject = project,
                selectedLayerId = null,
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
    }

    fun deleteCloudProject(projectId: String) {
        viewModelScope.launch {
            val res = repository.deleteProject(projectId)
            res.onFailure { e ->
                postTransientBanner(e.localizedMessage ?: "Delete failed")
            }
        }
    }

    fun saveCurrentProjectToCloud(silent: Boolean = false) {
        val current = _state.value.activeProject
        _state.update { it.copy(isSavingCloud = true) }
        viewModelScope.launch {
            val result = if (current.id.isBlank()) {
                repository.createProject(current).map { newId ->
                    current.copy(id = newId, userId = currentUserId)
                }
            } else {
                repository.updateProject(current).map { current }
            }

            result.fold(
                onSuccess = { saved ->
                    _state.update {
                        it.copy(
                            activeProject = saved,
                            isSavingCloud = false
                        )
                    }
                    if (!silent) postTransientBanner("Saved \"${saved.name}\"")
                },
                onFailure = { err ->
                    _state.update { it.copy(isSavingCloud = false) }
                    if (!silent) postTransientBanner(err.localizedMessage ?: "Cloud sync failed")
                }
            )
        }
    }

    fun togglePlayback() {
        if (_state.value.isPlaying) pausePlayback() else startPlayback()
    }

    /**
     * Zero-Lag Playback Loop:
     * - OpenGL ES 2.0 renderer runs unlocked at native 60fps VSync (`RENDERMODE_CONTINUOUSLY`).
     * - Coroutine syncs real audio tracks via `TimelineAudioController` and updates Compose UI playhead
     *   at a gentle ~25Hz cadence (`40ms`) so UI recomposition never starves GPU or video decoding!
     */
    private fun startPlayback() {
        playbackJob?.cancel()
        val startPlayhead = _state.value.playheadMs
        val startElapsedMs = SystemClock.elapsedRealtime()
        _state.update { it.copy(isPlaying = true) }

        playbackJob = viewModelScope.launch {
            var loopOriginMs = startPlayhead
            var loopWallOrigin = startElapsedMs
            while (isActive && _state.value.isPlaying) {
                val proj = _state.value.activeProject
                val dur = proj.durationMs.coerceAtLeast(200L)
                val nowWall = SystemClock.elapsedRealtime()
                val rawMs = loopOriginMs + (nowWall - loopWallOrigin)
                val currentMs = if (rawMs >= dur) {
                    loopOriginMs = 0L
                    loopWallOrigin = nowWall
                    0L
                } else {
                    rawMs
                }

                val snapped = AnimationEvaluator.snapToFrameMs(currentMs, proj.fps)
                audioEngine.sampleTimelineSpectrum(snapped, proj.bpm, proj.beatMarkers)
                timelineAudioController?.syncPlayback(proj, snapped, isPlaying = true)
                _state.update { it.copy(playheadMs = snapped) }
                delay(40L)
            }
        }
    }

    fun pausePlayback() {
        playbackJob?.cancel()
        playbackJob = null
        timelineAudioController?.pauseAll()
        _state.update { it.copy(isPlaying = false) }
    }

    fun scrubPlayhead(targetMs: Long) {
        val proj = _state.value.activeProject
        val clamped = targetMs.coerceIn(0L, proj.durationMs)
        val snapped = AnimationEvaluator.snapToFrameMs(clamped, proj.fps)
        audioEngine.sampleTimelineSpectrum(snapped, proj.bpm, proj.beatMarkers)
        _state.update { it.copy(playheadMs = snapped) }
    }

    fun stepFrame(deltaFrames: Int) {
        pausePlayback()
        val proj = _state.value.activeProject
        val frameMs = (1000.0 / proj.fps.coerceIn(1, 240)).toLong().coerceAtLeast(1L)
        scrubPlayhead(_state.value.playheadMs + deltaFrames * frameMs)
    }

    fun toggleBeatBookmarkAtPlayhead() {
        val s = _state.value
        val t = s.playheadMs
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

    fun jumpToBookmark(forward: Boolean) {
        val s = _state.value
        val sorted = s.activeProject.beatMarkers.sortedBy { it.timeMs }
        if (sorted.isEmpty()) return
        val next = if (forward) {
            sorted.firstOrNull { it.timeMs > s.playheadMs + 15L } ?: sorted.first()
        } else {
            sorted.lastOrNull { it.timeMs < s.playheadMs - 15L } ?: sorted.last()
        }
        pausePlayback()
        scrubPlayhead(next.timeMs)
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

    fun selectLayer(layerId: String) {
        _state.update { cur ->
            val nextTab = if (cur.selectedLayerId == layerId && cur.inspectorTab == AmInspectorTab.LAYER_OVERVIEW) {
                AmInspectorTab.TIMELINE
            } else if (cur.inspectorTab == AmInspectorTab.TIMELINE) {
                AmInspectorTab.LAYER_OVERVIEW
            } else {
                cur.inspectorTab
            }
            cur.copy(selectedLayerId = layerId, inspectorTab = nextTab)
        }
    }

    fun collapseLayerSheetToTimeline() {
        _state.update { it.copy(inspectorTab = AmInspectorTab.TIMELINE) }
    }

    fun selectProperty(target: PropertyTarget) {
        _state.update { it.copy(selectedProperty = target) }
    }

    fun dragSelectedLayerByDelta(dxProj: Float, dyProj: Float) {
        val s = _state.value
        val layerId = s.selectedLayerId ?: return
        val timeMs = s.playheadMs
        val audio = liveSpectrum.value

        mutateLayer(layerId, recordUndo = false) { layer ->
            if (layer.locked) return@mutateLayer layer
            if (s.selectedProperty == PropertyTarget.PIVOT_X || s.selectedProperty == PropertyTarget.PIVOT_Y) {
                val curPx = AnimationEvaluator.evaluateProperty(layer.pivotX, timeMs, audio)
                val curPy = AnimationEvaluator.evaluateProperty(layer.pivotY, timeMs, audio)
                var nextPx = (curPx + dxProj).coerceIn(PropertyTarget.PIVOT_X.minVal, PropertyTarget.PIVOT_X.maxVal)
                var nextPy = (curPy + dyProj).coerceIn(PropertyTarget.PIVOT_Y.minVal, PropertyTarget.PIVOT_Y.maxVal)
                if (abs(nextPx) < 3.5f) nextPx = 0f
                if (abs(nextPy) < 3.5f) nextPy = 0f
                layer.copy(
                    pivotX = applyPropertyEditAtTime(layer.pivotX, nextPx, timeMs),
                    pivotY = applyPropertyEditAtTime(layer.pivotY, nextPy, timeMs)
                )
            } else {
                val curX = AnimationEvaluator.evaluateProperty(layer.posX, timeMs, audio)
                val curY = AnimationEvaluator.evaluateProperty(layer.posY, timeMs, audio)
                var nextX = (curX + dxProj).coerceIn(PropertyTarget.POS_X.minVal, PropertyTarget.POS_X.maxVal)
                var nextY = (curY + dyProj).coerceIn(PropertyTarget.POS_Y.minVal, PropertyTarget.POS_Y.maxVal)
                if (abs(nextX) < 3.5f) nextX = 0f
                if (abs(nextY) < 3.5f) nextY = 0f
                layer.copy(
                    posX = applyPropertyEditAtTime(layer.posX, nextX, timeMs),
                    posY = applyPropertyEditAtTime(layer.posY, nextY, timeMs)
                )
            }
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
     * Real Media Import Pipeline for VIDEO, IMAGE, and AUDIO files!
     * Copies URI into persistent internal storage, decodes true dimensions + frame 0 immediately,
     * pre-warms video frames in RAM cache, and adds the layer cleanly onto the timeline.
     */
    fun importMediaLayer(context: Context, uri: Uri, type: LayerType) {
        pausePlayback()
        postTransientBanner("Importing ${type.name.lowercase()}...")
        viewModelScope.launch {
            val result = runCatching {
                MediaEngine.importMediaUri(
                    context = context.applicationContext,
                    pickedUri = uri,
                    type = type
                )
            }
            result.fold(
                onSuccess = { info ->
                    val s = _state.value
                    val proj = s.activeProject
                    val startMs = s.playheadMs.coerceIn(0L, (proj.durationMs - 200L).coerceAtLeast(0L))
                    val clipDuration = when (type) {
                        LayerType.VIDEO, LayerType.AUDIO -> info.durationMs.coerceIn(500L, 180_000L)
                        else -> (proj.durationMs - startMs).coerceAtLeast(3000L)
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
                            colorHex = "#0094FF",
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
                            colorHex = "#FFC400",
                            startTimeMs = startMs,
                            endTimeMs = targetEndMs
                        )
                        LayerType.AUDIO -> Layer(
                            name = info.displayName.ifBlank { "Audio $idxCount" },
                            type = LayerType.AUDIO,
                            mediaUri = info.localUri,
                            sourceDurationMs = clipDuration,
                            extractedWaveform = info.waveform,
                            colorHex = "#00E5FF",
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
                    _state.update { it.copy(inspectorTab = AmInspectorTab.TIMELINE) }
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
        val startMs = s.playheadMs.coerceIn(0L, (proj.durationMs - 500L).coerceAtLeast(0L))
        val newLayer = when (type) {
            LayerType.SHAPE -> Layer(
                name = "$shapeKind $count",
                type = LayerType.SHAPE,
                shapeKind = shapeKind,
                colorHex = "#00D2A8",
                startTimeMs = startMs,
                endTimeMs = proj.durationMs
            )
            LayerType.TEXT -> Layer(
                name = "Text $count",
                type = LayerType.TEXT,
                textContent = "NEW TEXT",
                colorHex = "#FFFFFF",
                startTimeMs = startMs,
                endTimeMs = proj.durationMs
            )
            LayerType.VIDEO -> Layer(
                name = "Video $count",
                type = LayerType.VIDEO,
                mediaUri = mediaUri,
                colorHex = "#0094FF",
                startTimeMs = startMs,
                endTimeMs = proj.durationMs
            )
            LayerType.IMAGE -> Layer(
                name = "Image $count",
                type = LayerType.IMAGE,
                mediaUri = mediaUri,
                colorHex = "#FFC400",
                startTimeMs = startMs,
                endTimeMs = proj.durationMs
            )
            LayerType.AUDIO -> Layer(
                name = "Audio $count",
                type = LayerType.AUDIO,
                mediaUri = mediaUri,
                colorHex = "#00E5FF",
                startTimeMs = startMs,
                endTimeMs = proj.durationMs
            )
            LayerType.CAMERA -> Layer(
                name = "Camera $count",
                type = LayerType.CAMERA,
                colorHex = "#FFC400",
                startTimeMs = 0L,
                endTimeMs = proj.durationMs
            )
            LayerType.MODEL_3D -> Layer(
                name = "3D Mesh $count",
                type = LayerType.MODEL_3D,
                primitive3D = Primitive3DType.CUBE,
                mediaUri = mediaUri,
                colorHex = "#00E5C4",
                startTimeMs = startMs,
                endTimeMs = proj.durationMs
            )
            LayerType.NULL_OBJECT -> Layer(
                name = "Null $count",
                type = LayerType.NULL_OBJECT,
                startTimeMs = 0L,
                endTimeMs = proj.durationMs
            )
        }
        updateActiveProject(
            proj.copy(layers = proj.layers + newLayer),
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
        val t = s.playheadMs
        mutateLayer(layerId, recordUndo = true) { layer ->
            val newEnd = t.coerceIn(layer.startTimeMs + 100L, s.activeProject.durationMs)
            layer.copy(endTimeMs = newEnd)
        }
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
        val safeSpeed = multiplier.coerceIn(0.10f, 4.0f)
        mutateLayer(layerId, recordUndo = true) { layer ->
            val currentSpan = (layer.endTimeMs - layer.startTimeMs).coerceAtLeast(200L)
            val baseSpan = (currentSpan * layer.speedMultiplier).toLong()
            val newSpan = (baseSpan / safeSpeed).toLong().coerceAtLeast(200L)
            val newEnd = (layer.startTimeMs + newSpan).coerceAtMost(max(s.activeProject.durationMs, layer.startTimeMs + newSpan))
            layer.copy(speedMultiplier = safeSpeed, endTimeMs = newEnd.coerceAtMost(s.activeProject.durationMs))
        }
    }

    /**
     * Video Reverse Toggle (plays source frames from end -> start cleanly in both preview & export!).
     */
    fun toggleSelectedLayerReverse() {
        val layerId = _state.value.selectedLayerId ?: return
        var nowRev = false
        mutateLayer(layerId, recordUndo = true) { layer ->
            nowRev = !layer.reversed
            layer.copy(reversed = nowRev)
        }
        postTransientBanner(if (nowRev) "Reverse playback enabled" else "Normal playback direction")
    }

    /**
     * Freeze Frame Toggle at current playhead!
     */
    fun toggleFreezeFrameAtPlayhead() {
        val s = _state.value
        val layer = s.activeProject.layers.firstOrNull { it.id == s.selectedLayerId } ?: return
        val audio = liveSpectrum.value
        val freezeVal = if (layer.freezeFrameMs >= 0L) {
            -1L
        } else {
            AnimationEvaluator.evaluateSourceMediaTimeMs(layer, s.playheadMs, audio)
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

        // Base rendered quad for VIDEO/IMAGE fits 0.85 of composition; compute exact scale % for 100% Fit or Fill!
        val targetScalePct = when (layer.type) {
            LayerType.VIDEO, LayerType.IMAGE -> {
                val mw = layer.mediaWidth.takeIf { it > 0 }?.toFloat() ?: projW
                val mh = layer.mediaHeight.takeIf { it > 0 }?.toFloat() ?: projH
                val baseFit = min((projW * 0.85f) / mw, (projH * 0.85f) / mh)
                val baseQuadW = mw * baseFit
                val baseQuadH = mh * baseFit
                val factor = if (fill) {
                    max(projW / baseQuadW, projH / baseQuadH)
                } else {
                    min(projW / baseQuadW, projH / baseQuadH)
                }
                (factor * 100f).coerceIn(10f, 500f)
            }
            else -> if (fill) 238f else 118f
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

    fun resetSelectedLayerTransform() {
        val layerId = _state.value.selectedLayerId ?: return
        mutateLayer(layerId, recordUndo = true) { layer ->
            layer.copy(
                posX = AnimatableProperty(0f),
                posY = AnimatableProperty(0f),
                posZ = AnimatableProperty(0f),
                pivotX = AnimatableProperty(0f),
                pivotY = AnimatableProperty(0f),
                scale = AnimatableProperty(100f),
                rotationX = AnimatableProperty(0f),
                rotationY = AnimatableProperty(0f),
                rotationZ = AnimatableProperty(0f),
                flipX = false,
                flipY = false
            )
        }
    }

    fun deleteSelectedLayer() {
        val selId = _state.value.selectedLayerId ?: return
        val proj = _state.value.activeProject
        val remaining = proj.layers
            .filterNot { it.id == selId }
            .map { if (it.parentId == selId) it.copy(parentId = null) else it }
        updateActiveProject(
            proj.copy(layers = remaining),
            selectLayerId = remaining.lastOrNull()?.id,
            recordUndo = true,
            banner = "Deleted layer"
        )
        if (remaining.isEmpty()) {
            _state.update { it.copy(selectedLayerId = null, inspectorTab = AmInspectorTab.TIMELINE) }
        }
    }

    fun toggleLayerVisibility(layerId: String) {
        mutateLayer(layerId, recordUndo = true) { it.copy(visible = !it.visible) }
    }

    fun toggleLayerLock(layerId: String) {
        mutateLayer(layerId, recordUndo = true) { it.copy(locked = !it.locked) }
    }

    fun renameLayer(layerId: String, newName: String) {
        mutateLayer(layerId, recordUndo = false) { it.copy(name = newName.ifBlank { it.name }) }
    }

    fun setLayerParent(childId: String, parentId: String?) {
        if (childId == parentId) return
        mutateLayer(childId, recordUndo = true) { it.copy(parentId = parentId) }
    }

    fun updateLayerTextAndFont(layerId: String, text: String, fontFamily: String) {
        mutateLayer(layerId, recordUndo = false) { it.copy(textContent = text, fontFamilyName = fontFamily) }
    }

    fun updateLayerColor(layerId: String, hex: String) {
        mutateLayer(layerId, recordUndo = true) { it.copy(colorHex = hex) }
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
        shadowColorHex: String
    ) {
        mutateLayer(layerId, recordUndo = false) {
            it.copy(
                strokeEnabled = strokeEnabled,
                strokeWidth = strokeWidth,
                strokeColorHex = strokeColorHex,
                shadowEnabled = shadowEnabled,
                shadowBlur = shadowBlur,
                shadowColorHex = shadowColorHex
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

    fun updateSelectedPropertyValue(newValue: Float) {
        val s = _state.value
        val layerId = s.selectedLayerId ?: return
        val target = s.selectedProperty
        val timeMs = s.playheadMs

        mutateLayer(layerId, recordUndo = false) { layer ->
            if (layer.locked) return@mutateLayer layer
            val updatedProp = applyPropertyEditAtTime(layer.propertyFor(target), newValue, timeMs)
            layer.updateProperty(target, updatedProp)
        }
    }

    fun toggleKeyframeAtPlayhead(target: PropertyTarget = _state.value.selectedProperty) {
        val s = _state.value
        val layerId = s.selectedLayerId ?: return
        val timeMs = s.playheadMs
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
        inY: Float
    ) {
        val s = _state.value
        val layerId = s.selectedLayerId ?: return
        val target = s.selectedProperty
        val timeMs = s.playheadMs

        mutateLayer(layerId, recordUndo = false) { layer ->
            val prop = layer.propertyFor(target)
            val sorted = prop.keyframes.sortedBy { it.timeMs }.toMutableList()
            if (sorted.size < 2) {
                val startKf = Keyframe(
                    timeMs = layer.startTimeMs,
                    value = prop.baseValue,
                    outTangentX = outX.coerceIn(0f, 1f),
                    outTangentY = outY.coerceIn(-0.5f, 1.5f),
                    inTangentX = inX.coerceIn(0f, 1f),
                    inTangentY = inY.coerceIn(-0.5f, 1.5f)
                )
                val endVal = if (target == PropertyTarget.TIME_REMAP) {
                    ((layer.endTimeMs - layer.startTimeMs).coerceAtLeast(500L)) / 1000f
                } else {
                    (prop.baseValue + 120f).coerceIn(target.minVal, target.maxVal)
                }
                val endKf = Keyframe(
                    timeMs = layer.endTimeMs,
                    value = endVal,
                    inTangentX = inX.coerceIn(0f, 1f),
                    inTangentY = inY.coerceIn(-0.5f, 1.5f)
                )
                return@mutateLayer layer.updateProperty(target, prop.copy(keyframes = listOf(startKf, endKf)))
            }

            var segIndex = sorted.indexOfLast { it.timeMs <= timeMs }.coerceAtLeast(0)
            if (segIndex >= sorted.size - 1) segIndex = sorted.size - 2

            sorted[segIndex] = sorted[segIndex].copy(
                outTangentX = outX.coerceIn(0f, 1f),
                outTangentY = outY.coerceIn(-0.5f, 1.5f)
            )
            sorted[segIndex + 1] = sorted[segIndex + 1].copy(
                inTangentX = inX.coerceIn(0f, 1f),
                inTangentY = inY.coerceIn(-0.5f, 1.5f)
            )
            layer.updateProperty(target, prop.copy(keyframes = sorted))
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

    // 3D Camera Orbit
    fun orbit3DCamera(deltaYaw: Float, deltaPitch: Float, zoomFactor: Float = 1f) {
        _state.update {
            it.copy(
                cameraYawDeg = (it.cameraYawDeg + deltaYaw) % 360f,
                cameraPitchDeg = (it.cameraPitchDeg + deltaPitch).coerceIn(-78f, 78f),
                cameraDistance = (it.cameraDistance / zoomFactor).coerceIn(1.8f, 16f)
            )
        }
    }

    // Effects Browser & Node Chain
    fun setEffectSearchQuery(query: String) {
        _state.update { it.copy(effectSearchQuery = query) }
    }

    fun setSelectedEffectCategory(category: EffectCategory?) {
        _state.update { it.copy(selectedEffectCategory = category) }
    }

    fun addEffectToSelectedLayer(definition: EffectDefinition) {
        val s = _state.value
        val targetId = s.selectedLayerId ?: s.activeProject.layers.firstOrNull()?.id ?: return
        mutateLayer(targetId, recordUndo = true) { layer ->
            layer.copy(effects = layer.effects + definition.toNode())
        }
        _state.update { it.copy(selectedLayerId = targetId) }
        postTransientBanner("Added ${definition.name}")
    }

    fun updateEffectNode(layerId: String, updatedNode: EffectNode) {
        mutateLayer(layerId, recordUndo = false) { layer ->
            layer.copy(effects = layer.effects.map { if (it.id == updatedNode.id) updatedNode else it })
        }
    }

    fun removeEffectNode(layerId: String, effectNodeId: String) {
        mutateLayer(layerId, recordUndo = true) { layer ->
            layer.copy(effects = layer.effects.filterNot { it.id == effectNodeId })
        }
    }

    fun saveLayerEffectChainAsPreset(presetName: String) {
        val s = _state.value
        val layer = s.activeProject.layers.firstOrNull { it.id == s.selectedLayerId } ?: return
        if (layer.effects.isEmpty()) return
        viewModelScope.launch {
            repository.saveEffectPreset(
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

    // Audio Reactor controls
    fun updateProjectBpmAndRegenerateBeats(bpm: Int, sensitivity: Float) {
        val safeBpm = bpm.coerceIn(40, 240)
        val safeSens = sensitivity.coerceIn(0.1f, 1.0f)
        val proj = _state.value.activeProject
        val beats = audioEngine.generateAutoBeatMarkers(proj.durationMs, safeBpm, safeSens)
        _state.update {
            it.copy(
                audioSensitivity = safeSens,
                activeProject = proj.copy(bpm = safeBpm, beatMarkers = beats)
            )
        }
        postTransientBanner("Generated ${beats.size} beat flags @ $safeBpm BPM")
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
        if (recordUndo) {
            if (undoStack.size >= 25) {
                undoStack.removeFirst()
            }
            undoStack.addLast(_state.value.activeProject)
            redoStack.clear()
        }
        _state.update {
            it.copy(
                activeProject = updated,
                selectedLayerId = selectLayerId?.takeIf { id -> updated.layers.any { l -> l.id == id } },
                canUndo = undoStack.isNotEmpty(),
                canRedo = redoStack.isNotEmpty()
            )
        }
        if (!banner.isNullOrBlank()) {
            postTransientBanner(banner)
        }
    }

    override fun onCleared() {
        super.onCleared()
        pausePlayback()
        timelineAudioController?.releaseAll()
        audioEngine.stopLiveMicCapture()
    }

    private companion object {
        const val TAG = "StudioViewModel"
    }
}
