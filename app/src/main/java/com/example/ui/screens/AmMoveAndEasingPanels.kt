package com.example.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ShowChart
import androidx.compose.material.icons.filled.Adjust
import androidx.compose.material.icons.filled.CropFree
import androidx.compose.material.icons.filled.OpenWith
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.RotateRight
import androidx.compose.material.icons.filled.Timeline
import androidx.compose.material.icons.filled.ViewInAr
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.data.model.Layer
import com.example.data.model.PropertyTarget
import com.example.engine.AnimationEvaluator
import com.example.engine.AudioSpectrumSnapshot
import com.example.ui.components.AmKeyframeCluster
import com.example.ui.components.AmSegmentChip
import com.example.ui.components.AmSubToolRailButton
import com.example.ui.components.AmTickWheelScrubber
import com.example.ui.theme.AmTeal
import com.example.ui.theme.AmTealBright
import com.example.ui.theme.AmTealContainer
import com.example.ui.theme.DeepBlack
import com.example.ui.theme.KeyframeAmber
import com.example.ui.theme.NeonPink
import com.example.ui.theme.OutlineSubtle
import com.example.ui.theme.PanelDark
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import com.example.viewmodel.AmInspectorTab
import com.example.viewmodel.StudioDestination
import kotlin.math.abs
import kotlin.math.hypot

/**
 * 1:1 Alight Motion + KeyFlow Balanced 3-Column Move & Transform Panel:
 * - Left Column (104.dp): Compact ⟨ ◆+ ⟩ Keyframe Cluster + Bezier Easing + Full Graph + Reset
 * - Spacious Center Workspace: Axis Chips + 'Swipe here to move' 2D Touch Pad + Tick-Wheel + KeyFlow Strip
 * - Right Column (48.dp): 5-icon Vertical Sub-Tool Rail (Move ✢, Pivot ⊕, Rotate ↻, Scale ⤢, 3D/Z ▱)
 */
@Composable
fun AmMoveTransformPanel(
    selectedLayer: Layer,
    selectedProperty: PropertyTarget,
    playheadMs: Long,
    audioSpectrum: AudioSpectrumSnapshot,
    onSelectProperty: (PropertyTarget) -> Unit,
    onUpdatePropertyValue: (Float) -> Unit,
    onUpdateExpression: (String) -> Unit,
    onDragLayerOnCanvas: (dxProj: Float, dyProj: Float) -> Unit,
    onResetTransform: () -> Unit,
    onToggleKeyframe: () -> Unit,
    onJumpKeyframe: (Boolean) -> Unit,
    onSetInspectorTab: (AmInspectorTab) -> Unit,
    onNavigateTool: (StudioDestination) -> Unit,
    modifier: Modifier = Modifier
) {
    val activeProp = selectedLayer.propertyFor(selectedProperty)
    val evalVal = AnimationEvaluator.evaluateProperty(activeProp, playheadMs, audioSpectrum)
    val evalX = AnimationEvaluator.evaluateProperty(selectedLayer.posX, playheadMs, audioSpectrum)
    val evalY = AnimationEvaluator.evaluateProperty(selectedLayer.posY, playheadMs, audioSpectrum)
    val evalPivotX = AnimationEvaluator.evaluateProperty(selectedLayer.pivotX, playheadMs, audioSpectrum)
    val evalPivotY = AnimationEvaluator.evaluateProperty(selectedLayer.pivotY, playheadMs, audioSpectrum)
    val hasKfAtPlayhead = activeProp.keyframes.any { abs(it.timeMs - playheadMs) <= 25L }

    val isMoveMode = selectedProperty == PropertyTarget.POS_X || selectedProperty == PropertyTarget.POS_Y
    val isPivotMode = selectedProperty == PropertyTarget.PIVOT_X || selectedProperty == PropertyTarget.PIVOT_Y
    val isRotateMode = selectedProperty == PropertyTarget.ROTATION_Z
    val isScaleMode = selectedProperty == PropertyTarget.SCALE
    val is3DMode = selectedProperty == PropertyTarget.POS_Z ||
        selectedProperty == PropertyTarget.ROTATION_X ||
        selectedProperty == PropertyTarget.ROTATION_Y

    val currentDragCallback by rememberUpdatedState(onDragLayerOnCanvas)

    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.Top
    ) {
        // 1. COMPACT LEFT COLUMN (104.dp)
        Column(
            modifier = Modifier.width(104.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            AmKeyframeCluster(
                hasKeyframeAtPlayhead = hasKfAtPlayhead,
                keyframeCount = activeProp.keyframes.size,
                onPrevKeyframe = { onJumpKeyframe(false) },
                onToggleKeyframe = onToggleKeyframe,
                onNextKeyframe = { onJumpKeyframe(true) }
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(36.dp)
                    .clip(RoundedCornerShape(9.dp))
                    .background(PanelDark)
                    .border(1.dp, OutlineSubtle, RoundedCornerShape(9.dp))
                    .clickable { onSetInspectorTab(AmInspectorTab.EASING_INLINE) }
                    .padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ShowChart,
                    contentDescription = "Inline Easing Curve",
                    tint = AmTealBright,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = "Easing",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextPrimary,
                    fontWeight = FontWeight.SemiBold
                )
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(32.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(AmTealContainer.copy(alpha = 0.55f))
                    .border(1.dp, AmTeal.copy(alpha = 0.55f), RoundedCornerShape(8.dp))
                    .clickable { onNavigateTool(StudioDestination.GRAPH_EDITOR) }
                    .testTag("tool_graph_button")
                    .padding(horizontal = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Timeline,
                    contentDescription = "Full Graph Editor",
                    tint = AmTealBright,
                    modifier = Modifier.size(14.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = "Graph",
                    style = MaterialTheme.typography.labelSmall,
                    color = AmTealBright,
                    fontWeight = FontWeight.Bold
                )
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(28.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(PanelDark)
                    .border(1.dp, OutlineSubtle, RoundedCornerShape(8.dp))
                    .clickable(onClick = onResetTransform)
                    .padding(horizontal = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                Icon(Icons.Default.Refresh, contentDescription = null, tint = TextSecondary, modifier = Modifier.size(13.dp))
                Spacer(modifier = Modifier.width(3.dp))
                Text("Reset", style = MaterialTheme.typography.labelSmall, color = TextSecondary)
            }
        }

        // 2. SPACIOUS CENTER WORKSPACE
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                val visibleTargets = when {
                    isMoveMode -> listOf(PropertyTarget.POS_X, PropertyTarget.POS_Y)
                    isPivotMode -> listOf(PropertyTarget.PIVOT_X, PropertyTarget.PIVOT_Y)
                    isRotateMode -> listOf(PropertyTarget.ROTATION_Z)
                    isScaleMode -> listOf(PropertyTarget.SCALE)
                    else -> listOf(PropertyTarget.POS_Z, PropertyTarget.ROTATION_X, PropertyTarget.ROTATION_Y)
                }
                visibleTargets.forEach { target ->
                    val kfCount = selectedLayer.propertyFor(target).keyframes.size
                    AmSegmentChip(
                        label = if (kfCount > 0) "${target.label} ◆$kfCount" else target.label,
                        selected = selectedProperty == target,
                        compact = true,
                        onClick = { onSelectProperty(target) },
                        testTag = "prop_chip_${target.name}"
                    )
                }
            }

            if (isMoveMode || isPivotMode) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(58.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(DeepBlack)
                        .border(1.dp, OutlineSubtle, RoundedCornerShape(10.dp))
                        .pointerInput(isPivotMode) {
                            detectDragGestures { change, dragAmount ->
                                change.consume()
                                currentDragCallback(dragAmount.x * 1.45f, dragAmount.y * 1.45f)
                            }
                        }
                        .testTag("am_swipe_to_move_pad"),
                    contentAlignment = Alignment.Center
                ) {
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        val step = 18f
                        var x = -size.height
                        while (x < size.width + size.height) {
                            drawLine(
                                color = OutlineSubtle.copy(alpha = 0.28f),
                                start = Offset(x, 0f),
                                end = Offset(x + size.height, size.height),
                                strokeWidth = 1f
                            )
                            drawLine(
                                color = OutlineSubtle.copy(alpha = 0.18f),
                                start = Offset(x + size.height, 0f),
                                end = Offset(x, size.height),
                                strokeWidth = 1f
                            )
                            x += step
                        }
                        drawLine(
                            color = AmTeal.copy(alpha = 0.35f),
                            start = Offset(size.width * 0.5f, 6f),
                            end = Offset(size.width * 0.5f, size.height - 6f),
                            strokeWidth = 1.2f
                        )
                        drawLine(
                            color = AmTeal.copy(alpha = 0.35f),
                            start = Offset(12f, size.height * 0.5f),
                            end = Offset(size.width - 12f, size.height * 0.5f),
                            strokeWidth = 1.2f
                        )
                    }
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = if (isPivotMode) "Swipe to offset Pivot" else "Swipe here to move",
                            style = MaterialTheme.typography.labelSmall,
                            color = TextSecondary
                        )
                        Text(
                            text = if (isPivotMode) {
                                "${evalPivotX.toInt()}, ${evalPivotY.toInt()} px"
                            } else {
                                "${evalX.toInt()}, ${evalY.toInt()} px"
                            },
                            style = MaterialTheme.typography.labelMedium,
                            color = AmTealBright,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            AmTickWheelScrubber(
                value = evalVal,
                minValue = selectedProperty.minVal,
                maxValue = selectedProperty.maxVal,
                unit = selectedProperty.unit,
                label = selectedProperty.label,
                onValueChange = onUpdatePropertyValue,
                testTag = "property_value_slider"
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(5.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("KeyFlow:", style = MaterialTheme.typography.labelSmall, color = TextMuted)
                listOf(
                    "Off" to "",
                    "Spring" to "spring(3, 4, 55)",
                    "Swing" to "pendulum(2.2, 30)",
                    "Shake" to "wiggle(7, 24)",
                    "Pulse" to "pulse(128)"
                ).forEach { (label, expr) ->
                    AmSegmentChip(
                        label = label,
                        selected = activeProp.expression == expr && (expr.isNotEmpty() || activeProp.expression.isEmpty()),
                        compact = true,
                        onClick = { onUpdateExpression(expr) }
                    )
                }
            }
        }

        // 3. COMPACT RIGHT VERTICAL 5-ICON SUB-TOOL RAIL (48.dp)
        Column(
            verticalArrangement = Arrangement.spacedBy(4.dp),
            horizontalAlignment = Alignment.End
        ) {
            AmSubToolRailButton(
                icon = Icons.Default.OpenWith,
                label = "Move",
                selected = isMoveMode,
                onClick = { onSelectProperty(PropertyTarget.POS_X) },
                testTag = "subtool_move"
            )
            AmSubToolRailButton(
                icon = Icons.Default.Adjust,
                label = "Pivot",
                selected = isPivotMode,
                onClick = { onSelectProperty(PropertyTarget.PIVOT_X) },
                testTag = "subtool_pivot"
            )
            AmSubToolRailButton(
                icon = Icons.Default.RotateRight,
                label = "Rotate",
                selected = isRotateMode,
                onClick = { onSelectProperty(PropertyTarget.ROTATION_Z) },
                testTag = "subtool_rotate"
            )
            AmSubToolRailButton(
                icon = Icons.Default.CropFree,
                label = "Scale",
                selected = isScaleMode,
                onClick = { onSelectProperty(PropertyTarget.SCALE) },
                testTag = "subtool_scale"
            )
            AmSubToolRailButton(
                icon = Icons.Default.ViewInAr,
                label = "3D/Z",
                selected = is3DMode,
                onClick = { onSelectProperty(PropertyTarget.POS_Z) },
                testTag = "subtool_3d"
            )
        }
    }
}

/**
 * 1:1 Alight Motion Inline Bezier Easing Panel right inside the clicked-layer bottom sheet!
 */
@Composable
fun AmInlineEasingPanel(
    selectedLayer: Layer,
    selectedProperty: PropertyTarget,
    playheadMs: Long,
    onUpdateBezierHandles: (outX: Float, outY: Float, inX: Float, inY: Float) -> Unit,
    onReturnToTransform: () -> Unit,
    onOpenFullGraph: () -> Unit,
    modifier: Modifier = Modifier
) {
    val prop = selectedLayer.propertyFor(selectedProperty)
    val sorted = prop.keyframes.sortedBy { it.timeMs }
    val activeKf = sorted.lastOrNull { it.timeMs <= playheadMs } ?: sorted.firstOrNull()
    val nextKf = sorted.firstOrNull { it.timeMs > (activeKf?.timeMs ?: -1L) } ?: sorted.lastOrNull()

    var x1 by remember(selectedLayer.id, selectedProperty, activeKf?.id) {
        mutableFloatStateOf(activeKf?.outTangentX ?: 0.42f)
    }
    var y1 by remember(selectedLayer.id, selectedProperty, activeKf?.id) {
        mutableFloatStateOf(activeKf?.outTangentY ?: 0.0f)
    }
    var x2 by remember(selectedLayer.id, selectedProperty, nextKf?.id) {
        mutableFloatStateOf(nextKf?.inTangentX ?: 0.58f)
    }
    var y2 by remember(selectedLayer.id, selectedProperty, nextKf?.id) {
        mutableFloatStateOf(nextKf?.inTangentY ?: 1.0f)
    }

    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(
            modifier = Modifier.width(98.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(32.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(PanelDark)
                    .border(1.dp, OutlineSubtle, RoundedCornerShape(8.dp))
                    .clickable(onClick = onReturnToTransform)
                    .padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null, tint = AmTealBright, modifier = Modifier.size(15.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text("Back", style = MaterialTheme.typography.labelSmall, color = TextPrimary)
            }

            listOf(
                "Linear" to floatArrayOf(0.25f, 0.25f, 0.75f, 0.75f),
                "Ease In/Out" to floatArrayOf(0.42f, 0.0f, 0.58f, 1.0f),
                "Ease Out" to floatArrayOf(0.16f, 1.0f, 0.30f, 1.0f),
                "Overshoot" to floatArrayOf(0.34f, 1.38f, 0.64f, 1.0f)
            ).forEach { (label, pts) ->
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(29.dp)
                        .clip(RoundedCornerShape(7.dp))
                        .background(PanelDark)
                        .border(1.dp, OutlineSubtle, RoundedCornerShape(7.dp))
                        .clickable {
                            x1 = pts[0]; y1 = pts[1]; x2 = pts[2]; y2 = pts[3]
                            onUpdateBezierHandles(x1, y1, x2, y2)
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Text(label, style = MaterialTheme.typography.labelSmall, color = TextSecondary)
                }
            }
        }

        Box(
            modifier = Modifier
                .weight(1f)
                .height(148.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(DeepBlack)
                .border(1.dp, OutlineSubtle, RoundedCornerShape(12.dp))
                .pointerInput(selectedLayer.id, selectedProperty) {
                    var draggingHandle = 1
                    detectDragGestures(
                        onDragStart = { start ->
                            val pad = 18f
                            val gw = (size.width - pad * 2f).coerceAtLeast(1f)
                            val gh = (size.height - pad * 2f).coerceAtLeast(1f)
                            val p1 = Offset(pad + x1 * gw, size.height - pad - y1.coerceIn(-0.3f, 1.3f) * gh)
                            val p2 = Offset(pad + x2 * gw, size.height - pad - y2.coerceIn(-0.3f, 1.3f) * gh)
                            val d1 = hypot(start.x - p1.x, start.y - p1.y)
                            val d2 = hypot(start.x - p2.x, start.y - p2.y)
                            draggingHandle = if (d1 <= d2) 1 else 2
                        },
                        onDrag = { change, drag ->
                            change.consume()
                            val gw = (size.width - 36f).coerceAtLeast(1f)
                            val gh = (size.height - 36f).coerceAtLeast(1f)
                            if (draggingHandle == 1) {
                                x1 = (x1 + drag.x / gw).coerceIn(0f, 1f)
                                y1 = (y1 - drag.y / gh).coerceIn(-0.35f, 1.4f)
                            } else {
                                x2 = (x2 + drag.x / gw).coerceIn(0f, 1f)
                                y2 = (y2 - drag.y / gh).coerceIn(-0.35f, 1.4f)
                            }
                            onUpdateBezierHandles(x1, y1, x2, y2)
                        }
                    )
                }
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val pad = 18f
                val gw = (size.width - pad * 2f).coerceAtLeast(1f)
                val gh = (size.height - pad * 2f).coerceAtLeast(1f)
                val p0 = Offset(pad, size.height - pad)
                val p3 = Offset(pad + gw, pad)
                val p1 = Offset(pad + x1 * gw, size.height - pad - y1.coerceIn(-0.3f, 1.3f) * gh)
                val p2 = Offset(pad + x2 * gw, size.height - pad - y2.coerceIn(-0.3f, 1.3f) * gh)

                drawLine(OutlineSubtle, p0, p3, strokeWidth = 1.2f)
                drawLine(AmTeal, p0, p1, strokeWidth = 2f)
                drawLine(NeonPink, p3, p2, strokeWidth = 2f)

                val curve = Path().apply {
                    moveTo(p0.x, p0.y)
                    cubicTo(p1.x, p1.y, p2.x, p2.y, p3.x, p3.y)
                }
                drawPath(curve, Color.White, style = Stroke(width = 3f))
                drawCircle(AmTealBright, radius = 7.5f, center = p1)
                drawCircle(NeonPink, radius = 7.5f, center = p2)
                drawCircle(KeyframeAmber, radius = 4.5f, center = p0)
                drawCircle(KeyframeAmber, radius = 4.5f, center = p3)
            }
        }

        Column(
            modifier = Modifier.width(52.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            AmSubToolRailButton(
                icon = Icons.Default.Timeline,
                label = "Graph",
                selected = true,
                onClick = onOpenFullGraph
            )
            Text(
                text = "${String.format("%.2f", x1)}\n${String.format("%.2f", y1)}",
                style = MaterialTheme.typography.labelSmall,
                color = TextMuted
            )
        }
    }
}
