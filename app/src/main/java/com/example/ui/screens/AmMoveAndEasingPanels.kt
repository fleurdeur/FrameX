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
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
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
    onResetPropertyTarget: (PropertyTarget) -> Unit = {},
    onResetMoveSubMode: (Int) -> Unit = {},
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

    var prefer3DSubMode by remember {
        mutableStateOf(
            selectedProperty == PropertyTarget.POS_Z ||
                selectedProperty == PropertyTarget.ROTATION_X ||
                selectedProperty == PropertyTarget.ROTATION_Y
        )
    }
    LaunchedEffect(selectedProperty) {
        when (selectedProperty) {
            PropertyTarget.POS_Z, PropertyTarget.ROTATION_X, PropertyTarget.ROTATION_Y -> {
                prefer3DSubMode = true
            }
            PropertyTarget.POS_X, PropertyTarget.POS_Y,
            PropertyTarget.PIVOT_X, PropertyTarget.PIVOT_Y,
            PropertyTarget.SCALE -> {
                prefer3DSubMode = false
            }
            else -> Unit
        }
    }

    val isMoveMode = selectedProperty == PropertyTarget.POS_X || selectedProperty == PropertyTarget.POS_Y
    val isPivotMode = selectedProperty == PropertyTarget.PIVOT_X || selectedProperty == PropertyTarget.PIVOT_Y
    val isScaleMode = selectedProperty == PropertyTarget.SCALE
    val is3DMode = selectedProperty == PropertyTarget.POS_Z ||
        selectedProperty == PropertyTarget.ROTATION_X ||
        selectedProperty == PropertyTarget.ROTATION_Y ||
        (prefer3DSubMode && selectedProperty == PropertyTarget.ROTATION_Z)
    val isRotateMode = selectedProperty == PropertyTarget.ROTATION_Z && !is3DMode
    val isAnyRotationTarget = selectedProperty == PropertyTarget.ROTATION_X ||
        selectedProperty == PropertyTarget.ROTATION_Y ||
        selectedProperty == PropertyTarget.ROTATION_Z

    val activeSubModeIndex = when {
        isMoveMode -> 0
        isPivotMode -> 1
        isScaleMode -> 2
        isRotateMode -> 3
        else -> 4
    }
    val subModeResetLabel = when (activeSubModeIndex) {
        0 -> "Reset Pos"
        1 -> "Reset Pivot"
        2 -> "Reset Scale"
        3 -> "Reset Rot"
        else -> "Reset 3D"
    }

    val currentDragCallback by rememberUpdatedState(onDragLayerOnCanvas)

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
            // Top Action Row: Axis / Property Chips on left + Prev/Next Keyframe, Full Graph, and Reset on right
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val visibleTargets = when {
                        isMoveMode -> listOf(PropertyTarget.POS_X, PropertyTarget.POS_Y)
                        isPivotMode -> listOf(PropertyTarget.PIVOT_X, PropertyTarget.PIVOT_Y)
                        isRotateMode -> listOf(PropertyTarget.ROTATION_Z)
                        isScaleMode -> listOf(PropertyTarget.SCALE)
                        else -> listOf(
                            PropertyTarget.POS_Z,
                            PropertyTarget.ROTATION_X,
                            PropertyTarget.ROTATION_Y,
                            PropertyTarget.ROTATION_Z
                        )
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

                Spacer(modifier = Modifier.width(6.dp))

                Row(
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Prev / Next Keyframe jump pills (Toggle Keyframe diamond lives on the left KeyFlow rail — no duplicate!)
                    if (activeProp.keyframes.isNotEmpty()) {
                        Box(
                            modifier = Modifier
                                .height(30.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(PanelDark)
                                .border(1.dp, OutlineSubtle, RoundedCornerShape(8.dp))
                                .clickable { onJumpKeyframe(false) }
                                .padding(horizontal = 8.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text("⟨◆", style = MaterialTheme.typography.labelSmall, color = KeyframeAmber, fontWeight = FontWeight.Bold)
                        }
                        Box(
                            modifier = Modifier
                                .height(30.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(PanelDark)
                                .border(1.dp, OutlineSubtle, RoundedCornerShape(8.dp))
                                .clickable { onJumpKeyframe(true) }
                                .padding(horizontal = 8.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text("◆⟩", style = MaterialTheme.typography.labelSmall, color = KeyframeAmber, fontWeight = FontWeight.Bold)
                        }
                    }

                    Row(
                        modifier = Modifier
                            .height(30.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(AmTealContainer.copy(alpha = 0.65f))
                            .border(1.dp, AmTeal.copy(alpha = 0.60f), RoundedCornerShape(8.dp))
                            .clickable { onNavigateTool(StudioDestination.GRAPH_EDITOR) }
                            .testTag("tool_graph_button")
                            .padding(horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
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
                            .height(30.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(PanelDark)
                            .border(1.dp, OutlineSubtle, RoundedCornerShape(8.dp))
                            .clickable { onResetMoveSubMode(activeSubModeIndex) }
                            .testTag("reset_move_submode_button")
                            .padding(horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = subModeResetLabel,
                            tint = TextSecondary,
                            modifier = Modifier.size(13.dp)
                        )
                        Spacer(modifier = Modifier.width(3.dp))
                        Text(subModeResetLabel, style = MaterialTheme.typography.labelSmall, color = TextSecondary)
                    }
                }
            }

            // Spacious 2D Swipe Pad + Quick Snap Presets side-by-side when in Move or Pivot mode
            if (isMoveMode || isPivotMode) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(72.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(DeepBlack)
                            .border(1.dp, OutlineSubtle, RoundedCornerShape(12.dp))
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
                                text = if (isPivotMode) {
                                    "Pivot  ${evalPivotX.toInt()}, ${evalPivotY.toInt()}"
                                } else {
                                    "X ${evalX.toInt()}   •   Y ${evalY.toInt()}"
                                },
                                style = MaterialTheme.typography.labelMedium,
                                color = AmTealBright,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "Swipe anywhere to nudge 2D",
                                style = MaterialTheme.typography.labelSmall,
                                color = TextMuted
                            )
                        }
                    }

                    // Compact quick-center column beside the 2D touch pad
                    Column(
                        modifier = Modifier.width(78.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(33.dp)
                                .clip(RoundedCornerShape(9.dp))
                                .background(PanelDark)
                                .border(1.dp, OutlineSubtle, RoundedCornerShape(9.dp))
                                .clickable {
                                    onSelectProperty(if (isPivotMode) PropertyTarget.PIVOT_X else PropertyTarget.POS_X)
                                    onUpdatePropertyValue(0f)
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            Text("Center X", style = MaterialTheme.typography.labelSmall, color = TextPrimary)
                        }
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(33.dp)
                                .clip(RoundedCornerShape(9.dp))
                                .background(PanelDark)
                                .border(1.dp, OutlineSubtle, RoundedCornerShape(9.dp))
                                .clickable {
                                    onSelectProperty(if (isPivotMode) PropertyTarget.PIVOT_Y else PropertyTarget.POS_Y)
                                    onUpdatePropertyValue(0f)
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            Text("Center Y", style = MaterialTheme.typography.labelSmall, color = TextPrimary)
                        }
                    }
                }
            } else if (isScaleMode || isAnyRotationTarget) {
                // Full 360-degree angle presets (-360° .. +360°) for 2D & 3D Move rotation, or quick scale presets
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val quickPresets = if (isScaleMode) {
                        listOf("50%" to 50f, "75%" to 75f, "100%" to 100f, "125%" to 125f, "150%" to 150f, "200%" to 200f)
                    } else {
                        listOf(
                            "-360°" to -360f,
                            "-180°" to -180f,
                            "-90°" to -90f,
                            "-45°" to -45f,
                            "0°" to 0f,
                            "45°" to 45f,
                            "90°" to 90f,
                            "180°" to 180f,
                            "360°" to 360f
                        )
                    }
                    quickPresets.forEach { (lbl, targetVal) ->
                        AmSegmentChip(
                            label = lbl,
                            selected = abs(evalVal - targetVal) < 0.5f,
                            compact = true,
                            onClick = { onUpdatePropertyValue(targetVal) },
                            testTag = "preset_${lbl.replace("°", "deg").replace("%", "pct")}"
                        )
                    }
                }
            }

            AmTickWheelScrubber(
                value = evalVal,
                minValue = selectedProperty.defaultSliderMin,
                maxValue = selectedProperty.defaultSliderMax,
                unit = selectedProperty.unit,
                label = selectedProperty.label,
                unbounded = selectedProperty.unbounded,
                onValueChange = onUpdatePropertyValue,
                onReset = { onResetPropertyTarget(selectedProperty) },
                testTag = "property_value_slider"
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(5.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Motion", style = MaterialTheme.typography.labelSmall, color = TextMuted)
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

        // 2. DEDICATED BOTTOM SUB-TOOL BUTTONS BAR (Utilizes available bottom space without crowding)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(PanelDark)
                .border(1.dp, OutlineSubtle, RoundedCornerShape(10.dp))
                .padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            AmSubToolBottomButton(
                modifier = Modifier.weight(1f),
                icon = Icons.Default.OpenWith,
                label = "Move",
                selected = isMoveMode,
                onClick = {
                    prefer3DSubMode = false
                    onSelectProperty(PropertyTarget.POS_X)
                },
                testTag = "subtool_move"
            )
            AmSubToolBottomButton(
                modifier = Modifier.weight(1f),
                icon = Icons.Default.Adjust,
                label = "Pivot",
                selected = isPivotMode,
                onClick = {
                    prefer3DSubMode = false
                    onSelectProperty(PropertyTarget.PIVOT_X)
                },
                testTag = "subtool_pivot"
            )
            AmSubToolBottomButton(
                modifier = Modifier.weight(1f),
                icon = Icons.Default.RotateRight,
                label = "Rotate",
                selected = isRotateMode,
                onClick = {
                    prefer3DSubMode = false
                    onSelectProperty(PropertyTarget.ROTATION_Z)
                },
                testTag = "subtool_rotate"
            )
            AmSubToolBottomButton(
                modifier = Modifier.weight(1f),
                icon = Icons.Default.CropFree,
                label = "Scale",
                selected = isScaleMode,
                onClick = {
                    prefer3DSubMode = false
                    onSelectProperty(PropertyTarget.SCALE)
                },
                testTag = "subtool_scale"
            )
            AmSubToolBottomButton(
                modifier = Modifier.weight(1f),
                icon = Icons.Default.ViewInAr,
                label = "3D/Z",
                selected = is3DMode,
                onClick = {
                    prefer3DSubMode = true
                    onSelectProperty(PropertyTarget.POS_Z)
                },
                testTag = "subtool_3d"
            )
        }
    }

@Composable
private fun AmSubToolBottomButton(
    modifier: Modifier = Modifier,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    testTag: String
) {
    val bg = if (selected) AmTealContainer else PanelDark
    val tint = if (selected) AmTealBright else TextSecondary
    val borderCol = if (selected) AmTeal else Color.Transparent
    Column(
        modifier = modifier
            .height(48.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(bg)
            .border(1.dp, borderCol, RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .testTag(testTag)
            .padding(vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = tint,
            modifier = Modifier.size(18.dp)
        )
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            fontSize = 10.5.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            color = tint,
            maxLines = 1
        )
    }
}

/**
 * 1:1 Alight Motion Inline Bezier Easing & Value Graph Panel right inside the clicked-layer bottom sheet!
 */
@Composable
fun AmInlineEasingPanel(
    selectedLayer: Layer,
    selectedProperty: PropertyTarget,
    playheadMs: Long,
    hasCopiedCurve: Boolean = false,
    onUpdateBezierHandles: (outX: Float, outY: Float, inX: Float, inY: Float, holdStep: Boolean) -> Unit,
    onCopyCurve: () -> Unit = {},
    onPasteCurve: () -> Unit = {},
    onDragKeyframeNode: ((kfId: String, newTimeMs: Long, newValue: Float) -> Unit)? = null,
    onScrubPlayhead: ((Long) -> Unit)? = null,
    onReturnToTransform: () -> Unit,
    onOpenFullGraph: () -> Unit,
    modifier: Modifier = Modifier
) {
    val prop = selectedLayer.propertyFor(selectedProperty)

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                modifier = Modifier
                    .height(30.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(PanelDark)
                    .border(1.dp, OutlineSubtle, RoundedCornerShape(8.dp))
                    .clickable(onClick = onReturnToTransform)
                    .padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null, tint = AmTealBright, modifier = Modifier.size(14.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text(selectedProperty.label, style = MaterialTheme.typography.labelSmall, color = TextPrimary, fontWeight = FontWeight.Bold)
            }

            Row(
                modifier = Modifier
                    .height(30.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(AmTealContainer)
                    .border(1.dp, AmTeal, RoundedCornerShape(8.dp))
                    .clickable(onClick = onOpenFullGraph)
                    .padding(horizontal = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Default.Timeline, contentDescription = null, tint = AmTealBright, modifier = Modifier.size(14.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text("Graph", style = MaterialTheme.typography.labelSmall, color = AmTealBright, fontWeight = FontWeight.Bold)
            }
        }

        com.example.ui.components.AmControllableCurveEditor(
            property = prop,
            minValue = selectedProperty.defaultSliderMin,
            maxValue = selectedProperty.defaultSliderMax,
            playheadMs = playheadMs,
            clipStartMs = selectedLayer.startTimeMs,
            clipEndMs = selectedLayer.endTimeMs,
            hasCopiedCurve = hasCopiedCurve,
            onUpdateBezierHandles = onUpdateBezierHandles,
            onCopyCurve = onCopyCurve,
            onPasteCurve = onPasteCurve,
            onDragKeyframeNode = onDragKeyframeNode,
            onScrubPlayhead = onScrubPlayhead,
            canvasHeight = 160.dp,
            compactMode = true,
            canvasTestTag = "inline_bezier_curve_canvas"
        )
    }
}
