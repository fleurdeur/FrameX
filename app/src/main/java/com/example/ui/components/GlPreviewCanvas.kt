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
import com.example.ui.theme.AmTeal
import com.example.ui.theme.AmTealBright
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
    onViewportGesture: (zoom: Float, panX: Float, panY: Float) -> Unit,
    onDragSelectedLayer: ((dxProjPx: Float, dyProjPx: Float) -> Unit)? = null,
    onScaleAndRotateSelectedLayer: ((scalePctDelta: Float, rotDegDelta: Float) -> Unit)? = null,
    onSelectLayerOnCanvas: ((layerId: String) -> Unit)? = null,
    previewFrameSampler: PreviewFrameSampler? = null,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val density = LocalDensity.current

    val glViewRef = remember { arrayOfNulls<GLSurfaceView>(1) }
    val renderer = remember(context) {
        FramePollutionGlRenderer(
            appContext = context.applicationContext,
            onAsyncMediaReady = {
                glViewRef[0]?.requestRender()
            }
        )
    }
    val glView = remember(context) {
        GLSurfaceView(context).apply {
            setEGLContextClientVersion(2)
            setEGLConfigChooser(8, 8, 8, 8, 16, 0)
            setRenderer(renderer)
            renderMode = GLSurfaceView.RENDERMODE_WHEN_DIRTY
            glViewRef[0] = this
        }
    }

    // Lock VSync playback anchor whenever play/pause state transitions
    LaunchedEffect(isPlaying) {
        if (isPlaying) {
            renderer.playbackAnchorMs = playheadMs
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
        previewFrameSampler?.glViewProvider = { glViewRef[0] }
        if (!isPlaying) {
            renderer.playheadMs = playheadMs
            glView.requestRender()
        } else {
            // Keep VSync anchor drift-corrected only on genuine loop wraps or scrub jumps (> 300ms)
            val nowNano = System.nanoTime()
            val dur = project.durationMs.coerceAtLeast(1L)
            val rawExpected = renderer.playbackAnchorMs + (nowNano - renderer.playbackAnchorNano) / 1_000_000L
            val expectedMs = if (isLooping) rawExpected % dur else rawExpected.coerceAtMost(dur)
            if (abs(expectedMs - playheadMs) > 300L) {
                renderer.playbackAnchorMs = playheadMs
                renderer.playbackAnchorNano = nowNano
            }
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
                                        applyActiveCamera = sel.type != LayerType.CAMERA
                                    )
                                    val cx = frameWidthPx * 0.5f + tr.posX * scaleToCanvas
                                    val cy = frameHeightPx * 0.5f + tr.posY * scaleToCanvas
                                    val (bw, bh) = computeLayerProjBounds(sel, projW, projH)
                                    val hw = (bw * 0.5f * scaleToCanvas * tr.scale).coerceAtLeast(16f)
                                    val hh = (bh * 0.5f * scaleToCanvas * tr.scale).coerceAtLeast(14f)
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
                                    currentViewportCallback(zoom, pan.x, pan.y)
                                    event.changes.forEach { if (it.positionChanged()) it.consume() }
                                }
                            } else if (pressed.size == 1) {
                                val pan = event.calculatePan()
                                if (pan != Offset.Zero) {
                                    totalMoved += pan.getDistance()
                                    val sel = currentSelected
                                    if (sel != null && sel.type != LayerType.AUDIO) {
                                        when (gestureMode) {
                                            2 -> {
                                                // Rotate around layer center
                                                val projW = currentProject.width.coerceAtLeast(1).toFloat()
                                                val scaleToCanvas = frameWidthPx / projW
                                                val tr = AnimationEvaluator.evaluateWorldTransform(
                                                    sel, currentProject.layers, currentPlayheadMs, currentAudioSnapshot,
                                                    applyActiveCamera = sel.type != LayerType.CAMERA
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
                                                    applyActiveCamera = sel.type != LayerType.CAMERA
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

                        // Tap-to-Select Layer directly on Canvas when user taps without dragging!
                        if (downRecorded && totalMoved < 8f && currentSelectLayerCallback != null) {
                            val projW = currentProject.width.coerceAtLeast(1).toFloat()
                            val projH = currentProject.height.coerceAtLeast(1).toFloat()
                            val scaleToCanvas = frameWidthPx / projW
                            val hitLayer = currentProject.layers.asReversed().firstOrNull { cand ->
                                if (!cand.visible || cand.type == LayerType.AUDIO || cand.type == LayerType.CAMERA) return@firstOrNull false
                                if (currentPlayheadMs !in cand.startTimeMs..cand.endTimeMs) return@firstOrNull false
                                val tr = AnimationEvaluator.evaluateWorldTransform(
                                    cand, currentProject.layers, currentPlayheadMs, currentAudioSnapshot, applyActiveCamera = true
                                )
                                val cx = frameWidthPx * 0.5f + tr.posX * scaleToCanvas
                                val cy = frameHeightPx * 0.5f + tr.posY * scaleToCanvas
                                val (bw, bh) = computeLayerProjBounds(cand, projW, projH)
                                val hw = (bw * 0.5f * scaleToCanvas * tr.scale).coerceAtLeast(18f)
                                val hh = (bh * 0.5f * scaleToCanvas * tr.scale).coerceAtLeast(18f)
                                abs(initialDownPos.x - cx) <= hw && abs(initialDownPos.y - cy) <= hh
                            }
                            if (hitLayer != null) {
                                currentSelectLayerCallback?.invoke(hitLayer.id)
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

            // 1:1 Alight Motion Selected Layer Transform Bounding Box & Snap Guides Overlay (only when visible & spatial)
            if (!isPlaying && selectedLayer != null && selectedLayer.visible &&
                selectedLayer.type != LayerType.AUDIO &&
                playheadMs in selectedLayer.startTimeMs..selectedLayer.endTimeMs
            ) {
                val tr = AnimationEvaluator.evaluateWorldTransform(
                    layer = selectedLayer,
                    allLayers = project.layers,
                    timeMs = playheadMs,
                    audio = audioSnapshot,
                    applyActiveCamera = selectedLayer.type != LayerType.CAMERA
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
                    val hw = (baseProjW * 0.5f * scaleToCanvas * tr.scale).coerceAtLeast(16f)
                    val hh = (baseProjH * 0.5f * scaleToCanvas * tr.scale).coerceAtLeast(14f)
                    val gizmoColor = if (selectedLayer.type == LayerType.CAMERA) KeyframeAmber else AmTealBright
                    val pivotCanvasOffset = Offset(
                        x = tr.pivotX * scaleToCanvas * tr.scale,
                        y = tr.pivotY * scaleToCanvas * tr.scale
                    )

                    withTransform({
                        translate(left = cx, top = cy)
                        rotate(degrees = tr.rotZ, pivot = pivotCanvasOffset)
                    }) {
                        // Bounding Box Outline
                        drawRect(
                            color = gizmoColor,
                            topLeft = Offset(-hw, -hh),
                            size = Size(hw * 2f, hh * 2f),
                            style = Stroke(width = 2.2f)
                        )

                        // Top Rotation Stem + Circular Knob
                        drawLine(
                            color = gizmoColor,
                            start = Offset(0f, -hh),
                            end = Offset(0f, -hh - 20f),
                            strokeWidth = 2.2f
                        )
                        drawCircle(Color.White, radius = 5.5f, center = Offset(0f, -hh - 22f))
                        drawCircle(AmTeal, radius = 5.5f, center = Offset(0f, -hh - 22f), style = Stroke(width = 2.2f))

                        // Anchor / Pivot Crosshair ⊕ at (pivotX, pivotY)
                        drawCircle(gizmoColor, radius = 5.5f, center = pivotCanvasOffset, style = Stroke(width = 1.9f))
                        drawLine(
                            gizmoColor,
                            Offset(pivotCanvasOffset.x - 10f, pivotCanvasOffset.y),
                            Offset(pivotCanvasOffset.x + 10f, pivotCanvasOffset.y),
                            strokeWidth = 1.6f
                        )
                        drawLine(
                            gizmoColor,
                            Offset(pivotCanvasOffset.x, pivotCanvasOffset.y - 10f),
                            Offset(pivotCanvasOffset.x, pivotCanvasOffset.y + 10f),
                            strokeWidth = 1.6f
                        )

                        // 8 White/Teal Square Resize Handles (4 Corners + 4 Midpoints)
                        val handlePts = listOf(
                            Offset(-hw, -hh), Offset(0f, -hh), Offset(hw, -hh),
                            Offset(-hw, 0f),                    Offset(hw, 0f),
                            Offset(-hw, hh),  Offset(0f, hh),  Offset(hw, hh)
                        )
                        val hs = 8.5f
                        handlePts.forEach { pt ->
                            drawRect(
                                color = Color.White,
                                topLeft = Offset(pt.x - hs / 2f, pt.y - hs / 2f),
                                size = Size(hs, hs)
                            )
                            drawRect(
                                color = if (selectedLayer.type == LayerType.CAMERA) KeyframeAmber else AmTeal,
                                topLeft = Offset(pt.x - hs / 2f, pt.y - hs / 2f),
                                size = Size(hs, hs),
                                style = Stroke(width = 2f)
                            )
                        }
                    }
                }
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
        LayerType.TEXT -> Pair(projW * 0.72f, minDim * 0.20f)
        LayerType.CAMERA -> Pair(projW * 0.90f, projH * 0.90f)
        LayerType.NULL_OBJECT -> Pair(minDim * 0.16f, minDim * 0.16f)
        LayerType.MODEL_3D -> Pair(minDim * 0.44f, minDim * 0.44f)
        LayerType.AUDIO -> Pair(0f, 0f)
    }
}
