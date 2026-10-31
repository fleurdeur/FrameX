package com.example.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ShowChart
import androidx.compose.material.icons.automirrored.filled.TrendingUp
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.Timeline
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.data.model.AnimatableProperty
import com.example.engine.AnimationEvaluator
import com.example.ui.theme.AmTeal
import com.example.ui.theme.AmTealBright
import com.example.ui.theme.AmTealContainer
import com.example.ui.theme.AudioCyan
import com.example.ui.theme.DeepBlack
import com.example.ui.theme.KeyframeAmber
import com.example.ui.theme.NeonPink
import com.example.ui.theme.OutlineSubtle
import com.example.ui.theme.PanelDark
import com.example.ui.theme.PlayheadRed
import com.example.ui.theme.PlayheadWhite
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import kotlin.math.abs
import kotlin.math.hypot

/**
 * 1:1 Alight Motion Controllable Curve & Value Graph Editor:
 * - Un-clipped Y domain (-0.42 .. +1.42) so Overshoot/Bounce handles above 1.0 and Anticipation handles below 0.0
 *   remain 100% visible and draggable inside the canvas!
 * - Zero-drift 1:1 finger tracking via awaitEachGesture + rememberUpdatedState (no stale closures).
 * - Supports both Segment Bezier Handle Control (P1 Out / P2 In + Step/Hold staircase) AND
 *   Multi-Keyframe Value Graph Mode (drag keyframe diamonds directly in Value Y & Time X!).
 * - Includes Copy Curve, Paste Curve, Mirror Curve, Presets, and Fine Handle Nudgers.
 */
@Composable
fun AmControllableCurveEditor(
    property: AnimatableProperty,
    minValue: Float,
    maxValue: Float,
    playheadMs: Long,
    clipStartMs: Long,
    clipEndMs: Long,
    hasCopiedCurve: Boolean,
    onUpdateBezierHandles: (outX: Float, outY: Float, inX: Float, inY: Float, holdStep: Boolean) -> Unit,
    onCopyCurve: () -> Unit,
    onPasteCurve: () -> Unit,
    onDragKeyframeNode: ((kfId: String, newTimeMs: Long, newValue: Float) -> Unit)? = null,
    onScrubPlayhead: ((Long) -> Unit)? = null,
    canvasHeight: Dp = 186.dp,
    compactMode: Boolean = false,
    canvasTestTag: String = "bezier_curve_canvas",
    modifier: Modifier = Modifier
) {
    val sortedKfs = remember(property.keyframes) { property.keyframes.sortedBy { it.timeMs } }
    val segIdx = remember(sortedKfs, playheadMs) {
        if (sortedKfs.size < 2) 0
        else sortedKfs.indexOfLast { it.timeMs <= playheadMs }
            .coerceAtLeast(0)
            .coerceAtMost(sortedKfs.size - 2)
    }
    val kfOut = sortedKfs.getOrNull(segIdx)
    val kfIn = sortedKfs.getOrNull(segIdx + 1)

    var p1x by remember { mutableFloatStateOf(kfOut?.outTangentX ?: 0.42f) }
    var p1y by remember { mutableFloatStateOf(kfOut?.outTangentY ?: 0.0f) }
    var p2x by remember { mutableFloatStateOf(kfIn?.inTangentX ?: 0.58f) }
    var p2y by remember { mutableFloatStateOf(kfIn?.inTangentY ?: 1.0f) }
    var holdStep by remember { mutableStateOf(kfOut?.holdStep ?: false) }

    // Sync local handle state whenever external keyframe segment changes
    LaunchedEffect(kfOut?.id, kfIn?.id, kfOut?.outTangentX, kfOut?.outTangentY, kfIn?.inTangentX, kfIn?.inTangentY, kfOut?.holdStep) {
        p1x = kfOut?.outTangentX ?: 0.42f
        p1y = kfOut?.outTangentY ?: 0.0f
        p2x = kfIn?.inTangentX ?: 0.58f
        p2y = kfIn?.inTangentY ?: 1.0f
        holdStep = kfOut?.holdStep ?: false
    }

    var selectedHandle by remember { mutableIntStateOf(1) } // 1 = P1 (Pink Out), 2 = P2 (Teal In)
    var showValueGraph by remember { mutableStateOf(false) }

    val latestOnUpdateHandles by rememberUpdatedState(onUpdateBezierHandles)
    val latestOnDragNode by rememberUpdatedState(onDragKeyframeNode)
    val latestOnScrub by rememberUpdatedState(onScrubPlayhead)
    val latestSortedKfs by rememberUpdatedState(sortedKfs)

    val yMinDomain = -0.42f
    val yMaxDomain = 1.42f
    val ySpanDomain = yMaxDomain - yMinDomain

    // Compute normalized segment playhead progress [0..1] for live dot on Bezier curve
    val segProgress = remember(kfOut, kfIn, playheadMs) {
        if (kfOut != null && kfIn != null && kfIn.timeMs > kfOut.timeMs) {
            ((playheadMs - kfOut.timeMs).toFloat() / (kfIn.timeMs - kfOut.timeMs).toFloat()).coerceIn(0f, 1f)
        } else {
            -1f
        }
    }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(if (compactMode) 6.dp else 8.dp)
    ) {
        // 1. TOP CURVE MODE & CLIPBOARD TOOLBAR (Bezier | Value Graph | Step Hold | Mirror | Copy | Paste)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            AmSegmentChip(
                label = "Easing Curve",
                selected = !showValueGraph && !holdStep,
                compact = true,
                onClick = {
                    showValueGraph = false
                    if (holdStep) {
                        holdStep = false
                        latestOnUpdateHandles(p1x, p1y, p2x, p2y, false)
                    }
                }
            )
            AmSegmentChip(
                label = "Step Hold",
                selected = holdStep && !showValueGraph,
                compact = true,
                onClick = {
                    showValueGraph = false
                    holdStep = !holdStep
                    latestOnUpdateHandles(p1x, p1y, p2x, p2y, holdStep)
                },
                testTag = "curve_step_hold_chip"
            )
            if (onDragKeyframeNode != null) {
                AmSegmentChip(
                    label = "Value Graph (${sortedKfs.size}◆)",
                    selected = showValueGraph,
                    compact = true,
                    onClick = { showValueGraph = true },
                    testTag = "curve_value_graph_chip"
                )
            }

            // Mirror Curve Button (flips easing ease-in <-> ease-out just like Alight Motion!)
            CurveMiniIconAction(
                icon = Icons.Default.SwapHoriz,
                label = "Mirror",
                active = false,
                onClick = {
                    val nx1 = (1f - p2x).coerceIn(0f, 1f)
                    val ny1 = (1f - p2y).coerceIn(-0.42f, 1.42f)
                    val nx2 = (1f - p1x).coerceIn(0f, 1f)
                    val ny2 = (1f - p1y).coerceIn(-0.42f, 1.42f)
                    p1x = nx1; p1y = ny1; p2x = nx2; p2y = ny2
                    holdStep = false
                    latestOnUpdateHandles(p1x, p1y, p2x, p2y, false)
                }
            )
            CurveMiniIconAction(
                icon = Icons.Default.ContentCopy,
                label = "Copy",
                active = false,
                onClick = onCopyCurve,
                testTag = "copy_curve_button"
            )
            CurveMiniIconAction(
                icon = Icons.Default.ContentPaste,
                label = "Paste",
                active = hasCopiedCurve,
                onClick = onPasteCurve,
                testTag = "paste_curve_button"
            )
        }

        // 2. INTERACTIVE CANVAS (100% Un-clipped Overshoot Bezier OR Multi-Keyframe Value Graph)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(canvasHeight)
                .clip(RoundedCornerShape(12.dp))
                .background(DeepBlack)
                .border(1.2.dp, OutlineSubtle, RoundedCornerShape(12.dp))
        ) {
            if (!showValueGraph) {
                // === MODE A: 1:1 ALIGHT MOTION CONTROLLABLE CUBIC BEZIER CANVAS ===
                Canvas(
                    modifier = Modifier
                        .fillMaxSize()
                        .pointerInput(Unit) {
                            awaitEachGesture {
                                val down = awaitFirstDown(requireUnconsumed = false)
                                val padX = 24f
                                val padY = 14f
                                val gw = (size.width - padX * 2f).coerceAtLeast(1f)
                                val gh = (size.height - padY * 2f).coerceAtLeast(1f)

                                fun toScreen(nx: Float, ny: Float): Offset {
                                    val sx = padX + nx.coerceIn(0f, 1f) * gw
                                    val normY = ((ny - yMinDomain) / ySpanDomain).coerceIn(0f, 1f)
                                    val sy = size.height - padY - normY * gh
                                    return Offset(sx, sy)
                                }

                                val h1 = toScreen(p1x, p1y)
                                val h2 = toScreen(p2x, p2y)
                                val d1 = hypot(down.position.x - h1.x, down.position.y - h1.y)
                                val d2 = hypot(down.position.x - h2.x, down.position.y - h2.y)
                                val activeHandle = if (d1 <= d2) 1 else 2
                                selectedHandle = activeHandle

                                fun applyPos(pos: Offset) {
                                    val nx = ((pos.x - padX) / gw).coerceIn(0f, 1f)
                                    val normY = ((size.height - padY - pos.y) / gh).coerceIn(0f, 1f)
                                    val ny = (yMinDomain + normY * ySpanDomain).coerceIn(-0.42f, 1.42f)
                                    if (activeHandle == 1) {
                                        p1x = nx
                                        p1y = ny
                                    } else {
                                        p2x = nx
                                        p2y = ny
                                    }
                                    holdStep = false
                                    latestOnUpdateHandles(p1x, p1y, p2x, p2y, false)
                                }

                                // If user tapped near a handle or dragged immediately, update smoothly
                                if (minOf(d1, d2) <= 72f) {
                                    down.consume()
                                }
                                do {
                                    val event = awaitPointerEvent()
                                    val change = event.changes.firstOrNull { it.id == down.id } ?: event.changes.firstOrNull()
                                    if (change != null && change.pressed) {
                                        if (change.positionChanged()) {
                                            change.consume()
                                            applyPos(change.position)
                                        }
                                    }
                                } while (event.changes.any { it.pressed })
                            }
                        }
                        .testTag(canvasTestTag)
                ) {
                    val padX = 24f
                    val padY = 14f
                    val gw = (size.width - padX * 2f).coerceAtLeast(1f)
                    val gh = (size.height - padY * 2f).coerceAtLeast(1f)

                    fun mapPt(nx: Float, ny: Float): Offset {
                        val sx = padX + nx * gw
                        val normY = (ny - yMinDomain) / ySpanDomain
                        val sy = size.height - padY - normY * gh
                        return Offset(sx, sy)
                    }

                    val p0 = mapPt(0f, 0f)
                    val p3 = mapPt(1f, 1f)
                    val c1 = mapPt(p1x.coerceIn(0f, 1f), p1y.coerceIn(yMinDomain, yMaxDomain))
                    val c2 = mapPt(p2x.coerceIn(0f, 1f), p2y.coerceIn(yMinDomain, yMaxDomain))

                    // Overshoot (>1.0) & Anticipation (<0.0) subtle zone shading
                    val yTop1 = p3.y
                    val yBot0 = p0.y
                    val dash = PathEffect.dashPathEffect(floatArrayOf(8f, 6f), 0f)

                    // Vertical & Horizontal 0..1 Core Box Grid
                    for (g in 0..4) {
                        val frac = g / 4f
                        val gx = padX + gw * frac
                        val gy = yBot0 - (yBot0 - yTop1) * frac
                        drawLine(Color(0xFF1F2432), Offset(gx, padY), Offset(gx, size.height - padY), strokeWidth = 1f)
                        drawLine(
                            color = if (g == 0 || g == 4) OutlineSubtle else Color(0xFF1F2432),
                            start = Offset(padX, gy),
                            end = Offset(padX + gw, gy),
                            strokeWidth = if (g == 0 || g == 4) 1.5f else 1f,
                            pathEffect = if (g == 0 || g == 4) dash else null
                        )
                    }

                    // Diagonal linear reference line
                    drawLine(Color(0x38FFFFFF), p0, p3, strokeWidth = 1.4f)

                    // Speed / Velocity Bars along [0..1] baseline
                    val boxH = (yBot0 - yTop1).coerceAtLeast(1f)
                    for (s in 1..20) {
                        val t0 = (s - 1) / 20f
                        val t1 = s / 20f
                        val y0 = if (holdStep) 0f else AnimationEvaluator.evaluateCubicBezier(t0, p1x, p1y, p2x, p2y)
                        val y1 = if (holdStep) 0f else AnimationEvaluator.evaluateCubicBezier(t1, p1x, p1y, p2x, p2y)
                        val vel = abs(y1 - y0) * 20f
                        val barH = (vel * boxH * 0.16f).coerceAtMost(boxH * 0.45f)
                        val bx = padX + t1 * gw
                        drawLine(
                            color = AmTeal.copy(alpha = 0.24f),
                            start = Offset(bx, yBot0),
                            end = Offset(bx, yBot0 - barH),
                            strokeWidth = (gw / 28f).coerceAtLeast(2.5f)
                        )
                    }

                    if (holdStep) {
                        // Alight Motion Step / Hold Staircase Curve
                        val stepPath = Path().apply {
                            moveTo(p0.x, p0.y)
                            lineTo(p3.x, p0.y)
                            lineTo(p3.x, p3.y)
                        }
                        drawPath(stepPath, PlayheadWhite, style = Stroke(width = 3.8f))
                    } else {
                        // Alight Motion Tangent Stems
                        drawLine(NeonPink, p0, c1, strokeWidth = 2.6f)
                        drawLine(AmTealBright, p3, c2, strokeWidth = 2.6f)

                        // Smooth Sampled Cubic Bezier Curve (accurate to Newton-Raphson evaluator!)
                        val curvePath = Path().apply {
                            moveTo(p0.x, p0.y)
                            val steps = 48
                            for (i in 1..steps) {
                                val t = i.toFloat() / steps
                                val ey = AnimationEvaluator.evaluateCubicBezier(t, p1x, p1y, p2x, p2y)
                                val pt = mapPt(t, ey)
                                lineTo(pt.x, pt.y)
                            }
                        }
                        drawPath(curvePath, PlayheadWhite, style = Stroke(width = 3.8f))

                        // Anchor Keyframe Diamonds at (0,0) and (1,1)
                        drawCircle(KeyframeAmber, radius = 5f, center = p0)
                        drawCircle(KeyframeAmber, radius = 5f, center = p3)

                        // Live Playhead Dot riding the curve!
                        if (segProgress in 0f..1f) {
                            val evalY = AnimationEvaluator.evaluateCubicBezier(segProgress, p1x, p1y, p2x, p2y)
                            val dotPt = mapPt(segProgress, evalY)
                            drawCircle(PlayheadRed.copy(alpha = 0.32f), radius = 10f, center = dotPt)
                            drawCircle(PlayheadRed, radius = 4.8f, center = dotPt)
                        }

                        // P1 Out Handle (Pink Ringed Knob)
                        val r1 = if (selectedHandle == 1) 13.5f else 11f
                        drawCircle(DeepBlack, radius = r1, center = c1)
                        drawCircle(NeonPink, radius = r1, center = c1, style = Stroke(width = if (selectedHandle == 1) 3.8f else 2.8f))
                        drawCircle(Color.White, radius = 3.8f, center = c1)

                        // P2 In Handle (Teal Ringed Knob)
                        val r2 = if (selectedHandle == 2) 13.5f else 11f
                        drawCircle(DeepBlack, radius = r2, center = c2)
                        drawCircle(AmTealBright, radius = r2, center = c2, style = Stroke(width = if (selectedHandle == 2) 3.8f else 2.8f))
                        drawCircle(Color.White, radius = 3.8f, center = c2)
                    }
                }

                // Subtle Corner Readout Overlay
                Text(
                    text = if (holdStep) "HOLD STEP"
                    else "P1(${String.format("%.2f", p1x)}, ${String.format("%.2f", p1y)}) • P2(${String.format("%.2f", p2x)}, ${String.format("%.2f", p2y)})",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextSecondary,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                )
            } else {
                // === MODE B: MULTI-KEYFRAME VALUE GRAPH (Drag Keyframe Diamonds in Value Y & Time X!) ===
                val tStart = clipStartMs.coerceAtLeast(0L)
                val tEnd = clipEndMs.coerceAtLeast(tStart + 200L)
                val tSpan = (tEnd - tStart).coerceAtLeast(1L).toFloat()
                val vSpan = (maxValue - minValue).coerceAtLeast(0.01f)

                Canvas(
                    modifier = Modifier
                        .fillMaxSize()
                        .pointerInput(tStart, tEnd, minValue, maxValue) {
                            awaitEachGesture {
                                val down = awaitFirstDown(requireUnconsumed = false)
                                val padX = 20f
                                val padY = 16f
                                val gw = (size.width - padX * 2f).coerceAtLeast(1f)
                                val gh = (size.height - padY * 2f).coerceAtLeast(1f)
                                val kfs = latestSortedKfs

                                var hitId: String? = null
                                var bestDist = 46f
                                kfs.forEach { kf ->
                                    val kx = padX + ((kf.timeMs - tStart) / tSpan).coerceIn(0f, 1f) * gw
                                    val ky = size.height - padY - ((kf.value - minValue) / vSpan).coerceIn(0f, 1f) * gh
                                    val dist = hypot(down.position.x - kx, down.position.y - ky)
                                    if (dist < bestDist) {
                                        bestDist = dist
                                        hitId = kf.id
                                    }
                                }

                                if (hitId == null) {
                                    // Scrub playhead along graph timeline
                                    val frac = ((down.position.x - padX) / gw).coerceIn(0f, 1f)
                                    latestOnScrub?.invoke(tStart + (frac * tSpan).toLong())
                                } else {
                                    down.consume()
                                }

                                do {
                                    val event = awaitPointerEvent()
                                    val change = event.changes.firstOrNull { it.id == down.id } ?: event.changes.firstOrNull()
                                    if (change != null && change.pressed && change.positionChanged()) {
                                        change.consume()
                                        val fracX = ((change.position.x - padX) / gw).coerceIn(0f, 1f)
                                        val fracY = ((size.height - padY - change.position.y) / gh).coerceIn(0f, 1f)
                                        val newT = tStart + (fracX * tSpan).toLong()
                                        val newV = (minValue + fracY * vSpan).coerceIn(minValue, maxValue)
                                        val activeId = hitId
                                        if (activeId != null) {
                                            latestOnDragNode?.invoke(activeId, newT, newV)
                                        } else {
                                            latestOnScrub?.invoke(newT)
                                        }
                                    }
                                } while (event.changes.any { it.pressed })
                            }
                        }
                        .testTag(canvasTestTag)
                ) {
                    val padX = 20f
                    val padY = 16f
                    val gw = (size.width - padX * 2f).coerceAtLeast(1f)
                    val gh = (size.height - padY * 2f).coerceAtLeast(1f)

                    for (g in 0..4) {
                        val f = g / 4f
                        drawLine(Color(0xFF1F2432), Offset(padX + gw * f, padY), Offset(padX + gw * f, size.height - padY), strokeWidth = 1f)
                        drawLine(Color(0xFF1F2432), Offset(padX, padY + gh * f), Offset(padX + gw, padY + gh * f), strokeWidth = 1f)
                    }

                    // Sample full evaluated property value curve across [tStart .. tEnd]
                    val dummyAudio = com.example.engine.AudioSpectrumSnapshot()
                    val valuePath = Path()
                    val samples = 64
                    for (i in 0..samples) {
                        val frac = i.toFloat() / samples
                        val sampleT = tStart + (frac * tSpan).toLong()
                        val v = AnimationEvaluator.evaluateProperty(
                            property.copy(expression = "", audioReactorBand = com.example.data.model.AudioBand.NONE),
                            sampleT,
                            dummyAudio
                        )
                        val sx = padX + frac * gw
                        val sy = size.height - padY - ((v - minValue) / vSpan).coerceIn(0f, 1f) * gh
                        if (i == 0) valuePath.moveTo(sx, sy) else valuePath.lineTo(sx, sy)
                    }
                    drawPath(valuePath, AudioCyan, style = Stroke(width = 3f))

                    // Playhead vertical line
                    val px = padX + ((playheadMs - tStart) / tSpan).coerceIn(0f, 1f) * gw
                    drawLine(PlayheadRed, Offset(px, 0f), Offset(px, size.height), strokeWidth = 2f)

                    // Draggable Keyframe Diamonds
                    sortedKfs.forEach { kf ->
                        val kx = padX + ((kf.timeMs - tStart) / tSpan).coerceIn(0f, 1f) * gw
                        val ky = size.height - padY - ((kf.value - minValue) / vSpan).coerceIn(0f, 1f) * gh
                        val diamond = Path().apply {
                            moveTo(kx, ky - 8f)
                            lineTo(kx + 8f, ky)
                            lineTo(kx, ky + 8f)
                            lineTo(kx - 8f, ky)
                            close()
                        }
                        drawPath(diamond, KeyframeAmber)
                        drawPath(diamond, Color.White, style = Stroke(width = 1.8f))
                    }
                }

                Text(
                    text = if (sortedKfs.isEmpty()) "Add ◆ keyframes to drag points on Value Graph"
                    else "Drag ◆ keyframe points directly in Time & Value",
                    style = MaterialTheme.typography.labelSmall,
                    color = AudioCyan,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                )
            }
        }

        // 3. FINE HANDLE NUDGER BAR (Select Handle P1 vs P2 + Precision X / Y Steppers)
        if (!showValueGraph && !holdStep) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                AmSegmentChip(
                    label = "P1 Out",
                    selected = selectedHandle == 1,
                    compact = true,
                    onClick = { selectedHandle = 1 }
                )
                AmSegmentChip(
                    label = "P2 In",
                    selected = selectedHandle == 2,
                    compact = true,
                    onClick = { selectedHandle = 2 }
                )
                val curHx = if (selectedHandle == 1) p1x else p2x
                val curHy = if (selectedHandle == 1) p1y else p2y
                HandleNudgePill(
                    axis = "X",
                    value = curHx,
                    onDelta = { dx ->
                        if (selectedHandle == 1) p1x = (p1x + dx).coerceIn(0f, 1f)
                        else p2x = (p2x + dx).coerceIn(0f, 1f)
                        latestOnUpdateHandles(p1x, p1y, p2x, p2y, false)
                    }
                )
                HandleNudgePill(
                    axis = "Y",
                    value = curHy,
                    onDelta = { dy ->
                        if (selectedHandle == 1) p1y = (p1y + dy).coerceIn(-0.42f, 1.42f)
                        else p2y = (p2y + dy).coerceIn(-0.42f, 1.42f)
                        latestOnUpdateHandles(p1x, p1y, p2x, p2y, false)
                    }
                )
            }
        }

        // 4. ALIGHT MOTION EASING PRESET CHIPS
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            val presets = listOf(
                "Linear" to floatArrayOf(0.25f, 0.25f, 0.75f, 0.75f),
                "Ease Out" to floatArrayOf(0.16f, 1.0f, 0.30f, 1.0f),
                "Ease In" to floatArrayOf(0.70f, 0.0f, 0.84f, 0.0f),
                "Ease In/Out" to floatArrayOf(0.65f, 0.0f, 0.35f, 1.0f),
                "Overshoot" to floatArrayOf(0.34f, 1.36f, 0.64f, 1.0f),
                "Anticipate" to floatArrayOf(0.36f, -0.32f, 0.66f, 1.0f),
                "Bounce" to floatArrayOf(0.22f, 1.38f, 0.36f, 0.92f),
                "Velocity Whip" to floatArrayOf(0.08f, 0.96f, 0.18f, 1.0f)
            )
            presets.forEach { (name, pts) ->
                AmSegmentChip(
                    label = name,
                    selected = false,
                    compact = true,
                    onClick = {
                        showValueGraph = false
                        holdStep = false
                        p1x = pts[0]; p1y = pts[1]; p2x = pts[2]; p2y = pts[3]
                        latestOnUpdateHandles(p1x, p1y, p2x, p2y, false)
                    }
                )
            }
        }
    }
}

@Composable
private fun CurveMiniIconAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    active: Boolean,
    onClick: () -> Unit,
    testTag: String? = null
) {
    Row(
        modifier = Modifier
            .height(28.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(if (active) AmTealContainer else PanelDark)
            .border(1.dp, if (active) AmTeal else OutlineSubtle, RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .then(if (testTag != null) Modifier.testTag(testTag) else Modifier)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = if (active) AmTealBright else TextSecondary,
            modifier = Modifier.size(13.dp)
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = if (active) AmTealBright else TextPrimary
        )
    }
}

@Composable
private fun HandleNudgePill(
    axis: String,
    value: Float,
    onDelta: (Float) -> Unit
) {
    Row(
        modifier = Modifier
            .height(28.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(PanelDark)
            .border(1.dp, OutlineSubtle, RoundedCornerShape(8.dp))
            .padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        Box(
            modifier = Modifier
                .size(20.dp)
                .clip(CircleShape)
                .background(DeepBlack)
                .clickable { onDelta(-0.04f) },
            contentAlignment = Alignment.Center
        ) {
            Text("−", style = MaterialTheme.typography.labelSmall, color = TextPrimary, fontWeight = FontWeight.Bold)
        }
        Text(
            text = "$axis:${String.format("%.2f", value)}",
            style = MaterialTheme.typography.labelSmall,
            color = AmTealBright,
            fontWeight = FontWeight.SemiBold
        )
        Box(
            modifier = Modifier
                .size(20.dp)
                .clip(CircleShape)
                .background(DeepBlack)
                .clickable { onDelta(0.04f) },
            contentAlignment = Alignment.Center
        ) {
            Text("+", style = MaterialTheme.typography.labelSmall, color = TextPrimary, fontWeight = FontWeight.Bold)
        }
    }
}
