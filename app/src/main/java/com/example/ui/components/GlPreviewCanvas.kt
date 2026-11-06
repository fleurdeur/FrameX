package com.example.ui.components

import android.opengl.GLSurfaceView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.example.data.model.Layer
import com.example.data.model.LayerType
import com.example.data.model.Project
import com.example.data.preferences.PreviewQuality
import com.example.engine.AnimationEvaluator
import com.example.engine.AudioSpectrumSnapshot
import com.example.engine.FramePollutionGlRenderer
import com.example.engine.HardwareVideoPreviewEngine
import com.example.engine.StudioPlaybackClock
import com.example.ui.theme.AmTeal
import com.example.ui.theme.AmTealBright
import com.example.ui.theme.KeyflowBlue
import com.example.ui.theme.KeyframeAmber
import com.example.ui.theme.OutlineSubtle
import com.example.ui.theme.PlayheadRed
import com.example.ui.theme.SurfaceDark
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Zero-Lag 60FPS Alight Motion Preview Stage:
 * - Hardware OpenGL ES 2.0 per-layer quad compositor with VSync-locked continuous playback
 * - RAM Pre-render frame cache 1-draw-call fast-path + PreviewQuality resolution scaling
 * - Real imported VIDEO & IMAGE aspect-accurate rendering + async frame decode wakeup
 * - Interactive Alight Motion Selected Layer Bounding Box Gizmo:
 *   8 White/Teal resize handles, rotation stem, custom Pivot crosshair (pivotX, pivotY),
 *   Camera viewfinder reticle, and red center-snap guides.
 */
@Composable
fun GlPreviewCanvas(
    project: Project,
    playheadMs: Long,
    isPlaying: Boolean = false,
    previewQuality: PreviewQuality = PreviewQuality.HALF,
    previewFpsCap: Int = 60,
    isLooping: Boolean = true,
    projectRevision: Long = 0L,
    usePlaybackCache: Boolean = true,
    onRenderStatsUpdate: ((fps: Int, frameMs: Float) -> Unit)? = null,
    audioSnapshot: AudioSpectrumSnapshot,
    canvasZoom: Float,
    canvasPanX: Float,
    canvasPanY: Float,
    cameraYawDeg: Float,
    cameraPitchDeg: Float,
    cameraDistance: Float,
    selectedLayerId: String? = null,
    show3DGridOverlay: Boolean = false,
    activeCameraPreviewEnabled: Boolean = true,
    onViewportGesture: (zoom: Float, panX: Float, panY: Float) -> Unit,
    onDragSelectedLayer: ((dxProjPx: Float, dyProjPx: Float) -> Unit)? = null,
    onScaleAndRotateSelectedLayer: ((scalePctDelta: Float, rotDegDelta: Float) -> Unit)? = null,
    onSelectLayerOnCanvas: ((layerId: String) -> Unit)? = null,
    onDeselectLayer: (() -> Unit)? = null,
    previewFrameSampler: PreviewFrameSampler? = null,
    rotoBrushActive: Boolean = false,
    rotoBrushRadiusDp: Float = 18f,
    rotoPreviewStrokesNormalized: List<List<Offset>> = emptyList(),
    onRotoStrokeCompleted: ((List<Offset>) -> Unit)? = null,
    penMaskEditActive: Boolean = false,
    penMaskPointsNormalized: List<Offset> = emptyList(),
    onPenMaskPointAdded: ((Offset) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val density = LocalDensity.current

    val glViewRef = remember { arrayOfNulls<GLSurfaceView>(1) }
    val renderer = remember(context) {
        val initialLiveMs = if (isPlaying && StudioPlaybackClock.isPlaying()) {
            StudioPlaybackClock.samplePlayheadMs()
        } else {
            playheadMs
        }
        FramePollutionGlRenderer(
            appContext = context.applicationContext,
            onAsyncMediaReady = {
                glViewRef[0]?.requestRender()
            }
        ).apply {
            this.currentProject = project
            this.playheadMs = initialLiveMs
            this.playbackAnchorMs = initialLiveMs
            this.playbackAnchorNano = System.nanoTime()
            this.isPlaying = isPlaying
            this.isLooping = isLooping
            this.previewQuality = previewQuality
            this.previewFpsCap = previewFpsCap
            this.projectRevision = projectRevision
            this.usePlaybackCache = usePlaybackCache
            this.audioSnapshot = audioSnapshot
            this.cameraYawDeg = cameraYawDeg
            this.cameraPitchDeg = cameraPitchDeg
            this.cameraDistance = cameraDistance
            this.show3DGridOverlay = show3DGridOverlay
            this.applyCameraInPreview = activeCameraPreviewEnabled
        }
    }
    val glView = remember(context) {
        GLSurfaceView(context).apply {
            setEGLContextClientVersion(2)
            setEGLConfigChooser(8, 8, 8, 8, 16, 0)
            setRenderer(renderer)
            renderMode = if (isPlaying) {
                GLSurfaceView.RENDERMODE_CONTINUOUSLY
            } else {
                GLSurfaceView.RENDERMODE_WHEN_DIRTY
            }
            glViewRef[0] = this
        }
    }

    // Lock VSync playback anchor whenever play/pause state transitions without ever resetting to 0
    LaunchedEffect(isPlaying) {
        if (isPlaying) {
            val liveMs = if (StudioPlaybackClock.isPlaying()) {
                StudioPlaybackClock.samplePlayheadMs()
            } else {
                playheadMs
            }
            renderer.playbackAnchorMs = liveMs
            renderer.playbackAnchorNano = System.nanoTime()
            renderer.isPlaying = true
            glView.renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
        } else {
            renderer.isPlaying = false
            renderer.playheadMs = playheadMs
            glView.renderMode = GLSurfaceView.RENDERMODE_WHEN_DIRTY
            glView.requestRender()
        }
    }

    // Request immediate dirty render when PreviewQuality changes while paused
    LaunchedEffect(previewQuality, projectRevision) {
        renderer.previewQuality = previewQuality
        renderer.projectRevision = projectRevision
        if (!isPlaying) {
            glView.requestRender()
        }
    }

    // Lightweight telemetry poll when HUD callback is attached
    LaunchedEffect(onRenderStatsUpdate, isPlaying) {
        if (onRenderStatsUpdate != null) {
            while (true) {
                onRenderStatsUpdate(renderer.liveFps, renderer.lastFrameRenderMs)
                delay(500L)
            }
        }
    }

    SideEffect {
        renderer.currentProject = project
        renderer.previewQuality = previewQuality
        renderer.previewFpsCap = previewFpsCap
        renderer.isLooping = isLooping
        renderer.projectRevision = projectRevision
        renderer.usePlaybackCache = usePlaybackCache
        renderer.audioSnapshot = audioSnapshot
        renderer.cameraYawDeg = cameraYawDeg
        renderer.cameraPitchDeg = cameraPitchDeg
        renderer.cameraDistance = cameraDistance
        renderer.show3DGridOverlay = show3DGridOverlay
        renderer.applyCameraInPreview = activeCameraPreviewEnabled
        previewFrameSampler?.glViewProvider = { glViewRef[0] }
        if (!isPlaying) {
            renderer.playheadMs = playheadMs
            glView.requestRender()
        }
    }

    DisposableEffect(glView) {
        glView.onResume()
        onDispose {
            HardwareVideoPreviewEngine.detachRendererSurfaces(renderer.rendererToken)
            glView.queueEvent {
                renderer.releaseGlResources()
            }
            glView.onPause()
        }
    }

    val aspect = (project.width.toFloat().coerceAtLeast(64f) / project.height.toFloat().coerceAtLeast(64f))
        .coerceIn(0.2f, 5.0f)

    val selectedLayer = remember(project.layers, selectedLayerId) {
        project.layers.firstOrNull { it.id == selectedLayerId }
    }
    val currentProject by rememberUpdatedState(project)
    val currentPlayheadMs by rememberUpdatedState(playheadMs)
    val currentAudioSnapshot by rememberUpdatedState(audioSnapshot)
    val currentSelected by rememberUpdatedState(selectedLayer)
    val currentDragCallback by rememberUpdatedState(onDragSelectedLayer)
    val currentScaleRotateCallback by rememberUpdatedState(onScaleAndRotateSelectedLayer)
    val currentSelectLayerCallback by rememberUpdatedState(onSelectLayerOnCanvas)
    val currentDeselectCallback by rememberUpdatedState(onDeselectLayer)
    val currentActiveCamPreview by rememberUpdatedState(activeCameraPreviewEnabled)
    val currentViewportCallback by rememberUpdatedState(onViewportGesture)
    val currentProjectWidth by rememberUpdatedState(project.width.coerceAtLeast(1))
    val currentZoom by rememberUpdatedState(canvasZoom)

    BoxWithConstraints(
        modifier = modifier
            .background(Color(0xFF12151C))
            .testTag("gl_preview_canvas"),
        contentAlignment = Alignment.Center
    ) {
        val availW = maxWidth
        val availH = maxHeight
        val containerAspect = (availW.value / availH.value.coerceAtLeast(1f))

        val rawW = if (containerAspect > aspect) availH * aspect else availW
        val rawH = if (containerAspect > aspect) availH else availW / aspect
        val frameW = (rawW - 2.dp).coerceAtLeast(48.dp)
        val frameH = (rawH - 2.dp).coerceAtLeast(48.dp)
        val frameWidthPx = with(density) { frameW.toPx().coerceAtLeast(1f) }
        val frameHeightPx = with(density) { frameH.toPx().coerceAtLeast(1f) }

        Box(
            modifier = Modifier
                .size(frameW, frameH)
                .offset { IntOffset(canvasPanX.roundToInt(), canvasPanY.roundToInt()) }
                .graphicsLayer {
                    scaleX = canvasZoom
                    scaleY = canvasZoom
                }
                .shadow(8.dp, RoundedCornerShape(2.dp))
                .clip(RoundedCornerShape(2.dp))
                .border(0.8.dp, Color(0xFF2B3140), RoundedCornerShape(2.dp))
                .pointerInput(Unit) {
                    awaitEachGesture {
                        var gestureMode = 0 // 0=move, 1=scaleCorner, 2=rotateKnob
                        var totalMoved = 0f
                        var dragStarted = false
                        val dragSlopPx = 12f
                        var initialDownPos = Offset.Zero
                        var downRecorded = false

                        do {
                            val event = awaitPointerEvent()
                            val pressed = event.changes.filter { it.pressed }
                            if (!downRecorded && pressed.size == 1) {
                                downRecorded = true
                                initialDownPos = pressed[0].position
                                val sel = currentSelected
                                if (sel != null && sel.visible && sel.type != LayerType.AUDIO &&
                                    currentPlayheadMs in sel.startTimeMs..sel.endTimeMs
                                ) {
                                    val projW = currentProject.width.coerceAtLeast(1).toFloat()
                                    val projH = currentProject.height.coerceAtLeast(1).toFloat()
                                    val scaleToCanvas = frameWidthPx / projW
                                    val tr = AnimationEvaluator.evaluateWorldTransform(
                                        sel, currentProject.layers, currentPlayheadMs, currentAudioSnapshot,
                                        applyActiveCamera = currentActiveCamPreview && sel.type != LayerType.CAMERA
                                    )
                                    val cx = frameWidthPx * 0.5f + tr.posX * scaleToCanvas
                                    val cy = frameHeightPx * 0.5f + tr.posY * scaleToCanvas
                                    val (bw, bh) = computeLayerProjBounds(sel, projW, projH)
                                    val foreshortenX = abs(kotlin.math.cos(Math.toRadians(tr.rotY.toDouble()))).toFloat().coerceAtLeast(0.10f)
                                    val foreshortenY = abs(kotlin.math.cos(Math.toRadians(tr.rotX.toDouble()))).toFloat().coerceAtLeast(0.10f)
                                    val hw = (bw * 0.5f * scaleToCanvas * tr.scale * foreshortenX).coerceAtLeast(16f)
                                    val hh = (bh * 0.5f * scaleToCanvas * tr.scale * foreshortenY).coerceAtLeast(14f)
                                    // Inverse-rotate touch point around layer center to check gizmo handles
                                    val rad = -Math.toRadians(tr.rotZ.toDouble())
                                    val dx = initialDownPos.x - cx
                                    val dy = initialDownPos.y - cy
                                    val lx = (dx * kotlin.math.cos(rad) - dy * kotlin.math.sin(rad)).toFloat()
                                    val ly = (dx * kotlin.math.sin(rad) + dy * kotlin.math.cos(rad)).toFloat()

                                    val rotDist = kotlin.math.hypot(lx, ly - (-hh - 22f))
                                    val cornerDist = minOf(
                                        kotlin.math.hypot(abs(lx) - hw, abs(ly) - hh),
                                        kotlin.math.hypot(abs(lx) - hw, ly),
                                        kotlin.math.hypot(lx, abs(ly) - hh)
                                    )
                                    if (rotDist <= 26f && currentScaleRotateCallback != null) {
                                        gestureMode = 2
                                    } else if (cornerDist <= 24f && currentScaleRotateCallback != null) {
                                        gestureMode = 1
                                    }
                                }
                            }

                            if (pressed.size >= 2) {
                                val zoom = event.calculateZoom()
                                val pan = event.calculatePan()
                                if (zoom != 1f || pan != Offset.Zero) {
                                    totalMoved += pan.getDistance() + abs(zoom - 1f) * 100f
                                    dragStarted = true
                                    currentViewportCallback(zoom, pan.x, pan.y)
                                    event.changes.forEach { if (it.positionChanged()) it.consume() }
                                }
                            } else if (pressed.size == 1) {
                                val pan = event.calculatePan()
                                if (pan != Offset.Zero) {
                                    totalMoved += pan.getDistance()
                                    if (!dragStarted && totalMoved >= dragSlopPx) {
                                        dragStarted = true
                                    }
                                    val sel = currentSelected
                                    if (dragStarted && sel != null && sel.type != LayerType.AUDIO && !sel.locked) {
                                        when (gestureMode) {
                                            2 -> {
                                                // Rotate around layer center
                                                val projW = currentProject.width.coerceAtLeast(1).toFloat()
                                                val scaleToCanvas = frameWidthPx / projW
                                                val tr = AnimationEvaluator.evaluateWorldTransform(
                                                    sel, currentProject.layers, currentPlayheadMs, currentAudioSnapshot,
                                                    applyActiveCamera = currentActiveCamPreview && sel.type != LayerType.CAMERA
                                                )
                                                val cx = frameWidthPx * 0.5f + tr.posX * scaleToCanvas
                                                val cy = frameHeightPx * 0.5f + tr.posY * scaleToCanvas
                                                val curPos = pressed[0].position
                                                val prevPos = curPos - pan
                                                val a1 = kotlin.math.atan2(prevPos.y - cy, prevPos.x - cx)
                                                val a2 = kotlin.math.atan2(curPos.y - cy, curPos.x - cx)
                                                var degDelta = Math.toDegrees((a2 - a1).toDouble()).toFloat()
                                                if (degDelta > 180f) degDelta -= 360f
                                                if (degDelta < -180f) degDelta += 360f
                                                currentScaleRotateCallback?.invoke(0f, degDelta)
                                            }
                                            1 -> {
                                                // Scale outward/inward from layer center
                                                val projW = currentProject.width.coerceAtLeast(1).toFloat()
                                                val scaleToCanvas = frameWidthPx / projW
                                                val tr = AnimationEvaluator.evaluateWorldTransform(
                                                    sel, currentProject.layers, currentPlayheadMs, currentAudioSnapshot,
                                                    applyActiveCamera = currentActiveCamPreview && sel.type != LayerType.CAMERA
                                                )
                                                val cx = frameWidthPx * 0.5f + tr.posX * scaleToCanvas
                                                val cy = frameHeightPx * 0.5f + tr.posY * scaleToCanvas
                                                val curPos = pressed[0].position
                                                val prevPos = curPos - pan
                                                val dPrev = kotlin.math.hypot(prevPos.x - cx, prevPos.y - cy).coerceAtLeast(8f)
                                                val dCur = kotlin.math.hypot(curPos.x - cx, curPos.y - cy).coerceAtLeast(8f)
                                                val pctDelta = ((dCur - dPrev) / 1.4f)
                                                currentScaleRotateCallback?.invoke(pctDelta, 0f)
                                            }
                                            else -> {
                                                val pxToProj = currentProjectWidth.toFloat() / (frameWidthPx * currentZoom.coerceAtLeast(0.2f))
                                                currentDragCallback?.invoke(pan.x * pxToProj, pan.y * pxToProj)
                                            }
                                        }
                                        event.changes.forEach { if (it.positionChanged()) it.consume() }
                                    }
                                }
                            }
                        } while (event.changes.any { it.pressed })

                        // Tap-to-Select / Tap-Empty-Space-to-Deselect Layer directly on Canvas!
                        if (downRecorded && !dragStarted) {
                            val projW = currentProject.width.coerceAtLeast(1).toFloat()
                            val projH = currentProject.height.coerceAtLeast(1).toFloat()
                            val scaleToCanvas = frameWidthPx / projW
                            val hitLayer = currentProject.layers.asReversed().firstOrNull { cand ->
                                if (!cand.visible || cand.type == LayerType.AUDIO) return@firstOrNull false
                                if (cand.type == LayerType.CAMERA && currentActiveCamPreview) return@firstOrNull false
                                if (currentPlayheadMs !in cand.startTimeMs..cand.endTimeMs) return@firstOrNull false
                                val tr = AnimationEvaluator.evaluateWorldTransform(
                                    cand,
                                    currentProject.layers,
                                    currentPlayheadMs,
                                    currentAudioSnapshot,
                                    applyActiveCamera = currentActiveCamPreview && cand.type != LayerType.CAMERA
                                )
                                val cx = frameWidthPx * 0.5f + tr.posX * scaleToCanvas
                                val cy = frameHeightPx * 0.5f + tr.posY * scaleToCanvas
                                val (bw, bh) = computeLayerProjBounds(cand, projW, projH)
                                val hw = (bw * 0.5f * scaleToCanvas * tr.scale).coerceAtLeast(18f)
                                val hh = (bh * 0.5f * scaleToCanvas * tr.scale).coerceAtLeast(18f)
                                abs(initialDownPos.x - cx) <= hw && abs(initialDownPos.y - cy) <= hh
                            }
                            if (hitLayer != null) {
                                if (currentSelected?.id == hitLayer.id) {
                                    // Unclicking the already-selected layer unloads all white gizmo handles & bottom dock!
                                    currentDeselectCallback?.invoke() ?: currentSelectLayerCallback?.invoke(hitLayer.id)
                                } else {
                                    currentSelectLayerCallback?.invoke(hitLayer.id)
                                }
                            } else if (currentSelected != null) {
                                // Tapping empty canvas space unloads selection completely!
                                currentDeselectCallback?.invoke()
                            }
                        }
                    }
                }
        ) {
            AndroidView(
                factory = { glView },
                modifier = Modifier.fillMaxSize(),
                update = { view ->
                    if (!isPlaying) view.requestRender()
                }
            )

            // Custom View (Camera Off) 3D Camera Frustum Wireframe Overlay (matches KeyFlow Reference 1)
            val activeCamLayer = remember(project.layers, playheadMs, activeCameraPreviewEnabled) {
                if (!activeCameraPreviewEnabled) {
                    project.layers.lastOrNull {
                        it.visible && it.type == LayerType.CAMERA && playheadMs in it.startTimeMs..it.endTimeMs
                    }
                } else null
            }
            if (activeCamLayer != null) {
                val camTr = AnimationEvaluator.evaluateWorldTransform(
                    layer = activeCamLayer,
                    allLayers = project.layers,
                    timeMs = playheadMs,
                    audio = audioSnapshot,
                    applyActiveCamera = false
                )
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val projW = project.width.coerceAtLeast(1).toFloat()
                    val projH = project.height.coerceAtLeast(1).toFloat()
                    val scaleToCanvas = size.width / projW
                    val cx = size.width * 0.5f + camTr.posX * scaleToCanvas
                    val cy = size.height * 0.5f + camTr.posY * scaleToCanvas
                    val depthFactor = (1f - camTr.posZ / 900f).coerceIn(0.25f, 3.5f)
                    val fw = (projW * 0.45f * scaleToCanvas * camTr.scale * depthFactor).coerceAtLeast(24f)
                    val fh = (projH * 0.45f * scaleToCanvas * camTr.scale * depthFactor).coerceAtLeast(24f)
                    val frustumColor = Color(0xFF6BA6FF)

                    withTransform({
                        translate(left = cx, top = cy)
                        rotate(degrees = camTr.rotZ, pivot = Offset.Zero)
                    }) {
                        val tl = Offset(-fw, -fh)
                        val trPt = Offset(fw, -fh)
                        val br = Offset(fw, fh)
                        val bl = Offset(-fw, fh)
                        val apex = Offset(fw * 1.15f, fh * 1.45f)

                        // Camera viewport plane fill + border
                        drawRect(
                            color = frustumColor.copy(alpha = 0.08f),
                            topLeft = tl,
                            size = Size(fw * 2f, fh * 2f)
                        )
                        drawRect(
                            color = frustumColor,
                            topLeft = tl,
                            size = Size(fw * 2f, fh * 2f),
                            style = Stroke(width = 2.0f)
                        )
                        // 4 Frustum rays to 3D camera cube apex
                        drawLine(frustumColor, tl, apex, strokeWidth = 1.6f)
                        drawLine(frustumColor, trPt, apex, strokeWidth = 1.6f)
                        drawLine(frustumColor, br, apex, strokeWidth = 1.6f)
                        drawLine(frustumColor, bl, apex, strokeWidth = 1.6f)

                        // 3D Isometric Wireframe Camera Cube at apex
                        val cs = 12.dp.toPx()
                        val frontOff = Offset(-cs * 0.5f, -cs * 0.5f)
                        val backOff = Offset(-cs * 0.1f, -cs * 0.85f)
                        drawRect(
                            color = frustumColor,
                            topLeft = apex + frontOff,
                            size = Size(cs, cs),
                            style = Stroke(width = 1.7f)
                        )
                        drawRect(
                            color = frustumColor.copy(alpha = 0.85f),
                            topLeft = apex + backOff,
                            size = Size(cs, cs),
                            style = Stroke(width = 1.5f)
                        )
                        drawLine(frustumColor, apex + frontOff, apex + backOff, strokeWidth = 1.4f)
                        drawLine(frustumColor, apex + frontOff + Offset(cs, 0f), apex + backOff + Offset(cs, 0f), strokeWidth = 1.4f)
                        drawLine(frustumColor, apex + frontOff + Offset(0f, cs), apex + backOff + Offset(0f, cs), strokeWidth = 1.4f)
                        drawLine(frustumColor, apex + frontOff + Offset(cs, cs), apex + backOff + Offset(cs, cs), strokeWidth = 1.4f)
                    }
                }
            }

            // 1:1 KeyFlow Selected Layer Transform Bounding Box & Snap Guides Overlay (unloaded immediately when deselected!)
            if (!isPlaying && selectedLayer != null && selectedLayer.visible &&
                selectedLayer.type != LayerType.AUDIO &&
                playheadMs in selectedLayer.startTimeMs..selectedLayer.endTimeMs
            ) {
                val tr = AnimationEvaluator.evaluateWorldTransform(
                    layer = selectedLayer,
                    allLayers = project.layers,
                    timeMs = playheadMs,
                    audio = audioSnapshot,
                    applyActiveCamera = activeCameraPreviewEnabled && selectedLayer.type != LayerType.CAMERA
                )

                Canvas(modifier = Modifier.fillMaxSize()) {
                    val projW = project.width.coerceAtLeast(1).toFloat()
                    val projH = project.height.coerceAtLeast(1).toFloat()
                    val scaleToCanvas = size.width / projW
                    val cx = size.width * 0.5f + tr.posX * scaleToCanvas
                    val cy = size.height * 0.5f + tr.posY * scaleToCanvas

                    // Alight Motion Red Alignment Snap Guides when near center X or Y
                    val dash = PathEffect.dashPathEffect(floatArrayOf(10f, 8f), 0f)
                    if (abs(tr.posX) <= 6f) {
                        drawLine(
                            color = PlayheadRed,
                            start = Offset(size.width * 0.5f, 0f),
                            end = Offset(size.width * 0.5f, size.height),
                            strokeWidth = 1.6f,
                            pathEffect = dash
                        )
                    }
                    if (abs(tr.posY) <= 6f) {
                        drawLine(
                            color = PlayheadRed,
                            start = Offset(0f, size.height * 0.5f),
                            end = Offset(size.width, size.height * 0.5f),
                            strokeWidth = 1.6f,
                            pathEffect = dash
                        )
                    }

                    val (baseProjW, baseProjH) = computeLayerProjBounds(selectedLayer, projW, projH)
                    val foreshortenX = abs(kotlin.math.cos(Math.toRadians(tr.rotY.toDouble()))).toFloat().coerceAtLeast(0.10f)
                    val foreshortenY = abs(kotlin.math.cos(Math.toRadians(tr.rotX.toDouble()))).toFloat().coerceAtLeast(0.10f)
                    val hw = (baseProjW * 0.5f * scaleToCanvas * tr.scale * foreshortenX).coerceAtLeast(16f)
                    val hh = (baseProjH * 0.5f * scaleToCanvas * tr.scaleY * foreshortenY).coerceAtLeast(14f)
                    val gizmoColor = if (selectedLayer.type == LayerType.CAMERA) KeyframeAmber else KeyflowBlue
                    val boxDash = PathEffect.dashPathEffect(floatArrayOf(13f, 10f), 0f)
                    val pivotCanvasOffset = Offset(
                        x = tr.pivotX * scaleToCanvas * tr.scale,
                        y = tr.pivotY * scaleToCanvas * tr.scaleY
                    )

                    withTransform({
                        translate(left = cx, top = cy)
                        rotate(degrees = tr.rotZ, pivot = pivotCanvasOffset)
                    }) {
                        // KeyFlow Dashed Royal-Blue Bounding Box Outline
                        drawRect(
                            color = gizmoColor,
                            topLeft = Offset(-hw, -hh),
                            size = Size(hw * 2f, hh * 2f),
                            style = Stroke(width = 2.4f, pathEffect = boxDash)
                        )

                        // Top Rotation Stalk + Circular Knob with Rotation Arc
                        val rotKnobCenter = Offset(0f, -hh - 26f)
                        drawLine(
                            color = gizmoColor,
                            start = Offset(0f, -hh),
                            end = rotKnobCenter,
                            strokeWidth = 2.2f
                        )
                        drawCircle(Color.White, radius = 7.5f, center = rotKnobCenter)
                        drawCircle(gizmoColor, radius = 7.5f, center = rotKnobCenter, style = Stroke(width = 2.2f))
                        drawArc(
                            color = gizmoColor,
                            startAngle = 200f,
                            sweepAngle = 140f,
                            useCenter = false,
                            topLeft = Offset(rotKnobCenter.x - 4.2f, rotKnobCenter.y - 4.2f),
                            size = Size(8.4f, 8.4f),
                            style = Stroke(width = 1.6f)
                        )

                        // Center Bullseye Pivot Reticle (Blue Halo + Ring + White Center Dot)
                        drawCircle(gizmoColor.copy(alpha = 0.24f), radius = 10f, center = pivotCanvasOffset)
                        drawCircle(gizmoColor, radius = 7.5f, center = pivotCanvasOffset, style = Stroke(width = 2.2f))
                        drawCircle(Color.White, radius = 2.8f, center = pivotCanvasOffset)

                        // 8 Crisp White Square Resize Handles (4 Corners + 4 Midpoints)
                        val handlePts = listOf(
                            Offset(-hw, -hh), Offset(0f, -hh), Offset(hw, -hh),
                            Offset(-hw, 0f),                    Offset(hw, 0f),
                            Offset(-hw, hh),  Offset(0f, hh),  Offset(hw, hh)
                        )
                        val hs = 9.5f
                        handlePts.forEach { pt ->
                            drawRect(
                                color = Color.White,
                                topLeft = Offset(pt.x - hs / 2f, pt.y - hs / 2f),
                                size = Size(hs, hs)
                            )
                            drawRect(
                                color = gizmoColor.copy(alpha = 0.85f),
                                topLeft = Offset(pt.x - hs / 2f, pt.y - hs / 2f),
                                size = Size(hs, hs),
                                style = Stroke(width = 1.2f)
                            )
                        }
                    }
                }
            }

            // Preview-ONLY Red Roto Brush & Pen Mask Interactive Overlay (never rendered into export!)
            if (rotoBrushActive || penMaskEditActive || rotoPreviewStrokesNormalized.isNotEmpty() || penMaskPointsNormalized.isNotEmpty()) {
                val activeStrokePts = remember { androidx.compose.runtime.mutableStateListOf<Offset>() }
                val currentOnRotoDone by rememberUpdatedState(onRotoStrokeCompleted)
                val currentOnPenTap by rememberUpdatedState(onPenMaskPointAdded)
                Canvas(
                    modifier = Modifier
                        .fillMaxSize()
                        .testTag("roto_pen_preview_overlay")
                        .then(
                            if (rotoBrushActive || penMaskEditActive) {
                                Modifier.pointerInput(rotoBrushActive, penMaskEditActive) {
                                    awaitEachGesture {
                                        val down = awaitPointerEvent().changes.firstOrNull { it.pressed } ?: return@awaitEachGesture
                                        down.consume()
                                        val wPx = size.width.toFloat().coerceAtLeast(1f)
                                        val hPx = size.height.toFloat().coerceAtLeast(1f)
                                        val normDown = Offset(
                                            (down.position.x / wPx).coerceIn(0f, 1f),
                                            (down.position.y / hPx).coerceIn(0f, 1f)
                                        )
                                        if (penMaskEditActive && !rotoBrushActive) {
                                            currentOnPenTap?.invoke(normDown)
                                            return@awaitEachGesture
                                        }
                                        activeStrokePts.clear()
                                        activeStrokePts.add(normDown)
                                        while (true) {
                                            val ev = awaitPointerEvent()
                                            val pressed = ev.changes.firstOrNull { it.pressed }
                                            if (pressed == null) break
                                            if (pressed.positionChanged()) {
                                                pressed.consume()
                                                activeStrokePts.add(
                                                    Offset(
                                                        (pressed.position.x / wPx).coerceIn(0f, 1f),
                                                        (pressed.position.y / hPx).coerceIn(0f, 1f)
                                                    )
                                                )
                                            }
                                        }
                                        if (activeStrokePts.isNotEmpty()) {
                                            currentOnRotoDone?.invoke(activeStrokePts.toList())
                                            activeStrokePts.clear()
                                        }
                                    }
                                }
                            } else Modifier
                        )
                ) {
                    val strokePx = with(density) { rotoBrushRadiusDp.dp.toPx() * 2f }.coerceAtLeast(8f)
                    val redBrushColor = Color(0x8CFF2A4B)
                    val drawNormStroke = { pts: List<Offset> ->
                        if (pts.size == 1) {
                            drawCircle(
                                color = redBrushColor,
                                radius = strokePx * 0.5f,
                                center = Offset(pts[0].x * size.width, pts[0].y * size.height)
                            )
                        } else if (pts.size >= 2) {
                            val p = androidx.compose.ui.graphics.Path().apply {
                                pts.forEachIndexed { idx, pt ->
                                    val x = pt.x * size.width
                                    val y = pt.y * size.height
                                    if (idx == 0) moveTo(x, y) else lineTo(x, y)
                                }
                            }
                            drawPath(
                                path = p,
                                color = redBrushColor,
                                style = Stroke(
                                    width = strokePx,
                                    cap = androidx.compose.ui.graphics.StrokeCap.Round,
                                    join = androidx.compose.ui.graphics.StrokeJoin.Round
                                )
                            )
                        }
                    }
                    rotoPreviewStrokesNormalized.forEach { drawNormStroke(it) }
                    if (activeStrokePts.isNotEmpty()) {
                        drawNormStroke(activeStrokePts)
                    }
                    if (penMaskPointsNormalized.isNotEmpty()) {
                        val penPath = androidx.compose.ui.graphics.Path()
                        penMaskPointsNormalized.forEachIndexed { idx, pt ->
                            val px = pt.x * size.width
                            val py = pt.y * size.height
                            if (idx == 0) penPath.moveTo(px, py) else penPath.lineTo(px, py)
                        }
                        if (penMaskPointsNormalized.size >= 3) {
                            penPath.close()
                        }
                        drawPath(penPath, color = AmTealBright, style = Stroke(width = 2.4f))
                        penMaskPointsNormalized.forEach { pt ->
                            val center = Offset(pt.x * size.width, pt.y * size.height)
                            drawCircle(Color.White, radius = 6f, center = center)
                            drawCircle(KeyflowBlue, radius = 6f, center = center, style = Stroke(width = 2f))
                        }
                    }
                }
            }
        }

        if (!activeCameraPreviewEnabled) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(8.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color(0xD9090B0F))
                    .border(0.8.dp, KeyflowBlue.copy(alpha = 0.55f), RoundedCornerShape(6.dp))
                    .padding(horizontal = 8.dp, vertical = 3.dp)
            ) {
                Text(
                    text = "Custom View · not the render",
                    style = MaterialTheme.typography.labelSmall,
                    color = AmTealBright
                )
            }
        }

        if (canvasZoom != 1f || canvasPanX != 0f || canvasPanY != 0f) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(6.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(SurfaceDark.copy(alpha = 0.92f))
                    .border(0.8.dp, OutlineSubtle, RoundedCornerShape(6.dp))
                    .padding(horizontal = 7.dp, vertical = 2.dp)
            ) {
                Text(
                    text = "${(canvasZoom * 100).roundToInt()}%",
                    style = MaterialTheme.typography.labelSmall,
                    color = AmTealBright
                )
            }
        }
    }
}

private fun computeLayerProjBounds(layer: Layer, projW: Float, projH: Float): Pair<Float, Float> {
    val minDim = min(projW, projH)
    return when (layer.type) {
        LayerType.VIDEO, LayerType.IMAGE -> {
            val mw = layer.mediaWidth.takeIf { it > 0 }?.toFloat() ?: projW
            val mh = layer.mediaHeight.takeIf { it > 0 }?.toFloat() ?: projH
            val fitScale = min(projW / mw, projH / mh)
            Pair(mw * fitScale, mh * fitScale)
        }
        LayerType.SHAPE -> {
            val s = minDim * 0.42f
            if (layer.shapeKind == "Rectangle" || layer.shapeKind == "Rounded Rect") {
                Pair(s * 1.25f, s * 0.85f)
            } else {
                Pair(s, s)
            }
        }
        LayerType.DRAWING -> Pair(projW * 0.75f, projH * 0.75f)
        LayerType.TEXT -> Pair(projW * 0.72f, minDim * 0.20f)
        LayerType.CAMERA -> Pair(projW * 0.90f, projH * 0.90f)
        LayerType.NULL_OBJECT -> Pair(minDim * 0.16f, minDim * 0.16f)
        LayerType.GROUP -> Pair(projW * 0.65f, projH * 0.65f)
        LayerType.MODEL_3D -> Pair(minDim * 0.44f, minDim * 0.44f)
        LayerType.AUDIO -> Pair(0f, 0f)
    }
}
