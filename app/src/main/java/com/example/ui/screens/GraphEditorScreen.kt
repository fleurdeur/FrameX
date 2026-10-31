@file:OptIn(ExperimentalMaterial3Api::class)

package com.example.ui.screens

import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.data.model.AnimatableProperty
import com.example.data.model.EffectParamSlot
import com.example.data.model.Layer
import com.example.data.model.PropertyTarget
import com.example.engine.EffectsCatalog
import com.example.ui.components.AmControllableCurveEditor
import com.example.ui.components.AmKeyframeCluster
import com.example.ui.components.AmSegmentChip
import com.example.ui.theme.AmBgDark
import com.example.ui.theme.AmTealBright
import com.example.ui.theme.OutlineSubtle
import com.example.ui.theme.SurfaceDark
import com.example.ui.theme.TextSecondary
import kotlin.math.abs

/**
 * Full-Screen Alight Motion + KeyFlow Easing & Value Graph Editor!
 * Supports BOTH Layer Transform Properties AND Added Effect Parameters (all 5 slots per effect),
 * with 100% controllable overshoot Bezier handles, Step/Hold curves, Copy/Paste Curve,
 * Multi-Keyframe Value Graph dragging, and Procedural Math Expressions!
 */
@Composable
fun GraphEditorScreen(
    selectedLayer: Layer?,
    selectedProperty: PropertyTarget,
    selectedEffectId: String? = null,
    selectedEffectSlot: EffectParamSlot = EffectParamSlot.PRIMARY,
    initialTargetsEffect: Boolean = false,
    hasCopiedCurve: Boolean = false,
    playheadMs: Long,
    onSelectProperty: (PropertyTarget) -> Unit,
    onSelectEffect: (String) -> Unit = {},
    onSelectEffectSlot: (EffectParamSlot) -> Unit = {},
    onToggleKeyframe: (isEffect: Boolean) -> Unit = {},
    onJumpKeyframe: (forward: Boolean, isEffect: Boolean) -> Unit = { _, _ -> },
    onUpdateBezierHandles: (outX: Float, outY: Float, inX: Float, inY: Float, holdStep: Boolean, isEffect: Boolean) -> Unit,
    onCopyCurve: (isEffect: Boolean) -> Unit = {},
    onPasteCurve: (isEffect: Boolean) -> Unit = {},
    onDragKeyframeNode: (kfId: String, newTimeMs: Long, newValue: Float, isEffect: Boolean) -> Unit = { _, _, _, _ -> },
    onScrubPlayhead: (Long) -> Unit = {},
    onUpdateExpression: (String, isEffect: Boolean) -> Unit,
    onBack: () -> Unit
) {
    val layerEffects = selectedLayer?.effects.orEmpty()
    var editingEffectMode by remember(initialTargetsEffect, layerEffects.size) {
        mutableStateOf(initialTargetsEffect && layerEffects.isNotEmpty())
    }
    val activeFx = remember(layerEffects, selectedEffectId) {
        layerEffects.firstOrNull { it.id == selectedEffectId } ?: layerEffects.firstOrNull()
    }
    val activeFxSpecs = remember(activeFx?.effectId) {
        activeFx?.let { EffectsCatalog.findById(it.effectId).paramSpecs() }.orEmpty()
    }
    val activeFxSpec = activeFxSpecs.firstOrNull { it.slot == selectedEffectSlot } ?: activeFxSpecs.firstOrNull()

    val activeProp: AnimatableProperty = when {
        editingEffectMode && activeFx != null -> activeFx.propertyFor(selectedEffectSlot)
        selectedLayer != null -> selectedLayer.propertyFor(selectedProperty)
        else -> AnimatableProperty(0f)
    }
    val minDomain = if (editingEffectMode && activeFxSpec != null) activeFxSpec.minVal else selectedProperty.minVal
    val maxDomain = if (editingEffectMode && activeFxSpec != null) activeFxSpec.maxVal else selectedProperty.maxVal
    val activeTitle = if (editingEffectMode && activeFx != null && activeFxSpec != null) {
        "${activeFx.displayName} • ${activeFxSpec.label}"
    } else {
        selectedProperty.label
    }

    var expressionText by remember { mutableStateOf(activeProp.expression) }
    LaunchedEffect(selectedLayer?.id, selectedProperty, activeFx?.id, selectedEffectSlot, editingEffectMode, activeProp.expression) {
        expressionText = activeProp.expression
    }

    val hasKfAtPlayhead = activeProp.keyframes.any { abs(it.timeMs - playheadMs) <= 25L }

    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing,
        containerColor = AmBgDark,
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack, modifier = Modifier.testTag("graph_back_button")) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back to Editor")
                    }
                },
                title = {
                    Column {
                        Text(
                            text = "Curve & Value Graph Editor",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "${selectedLayer?.name ?: "No Layer"} • $activeTitle (${activeProp.keyframes.size} ◆)",
                            style = MaterialTheme.typography.labelSmall,
                            color = AmTealBright
                        )
                    }
                },
                actions = {
                    if (selectedLayer != null) {
                        AmKeyframeCluster(
                            hasKeyframeAtPlayhead = hasKfAtPlayhead,
                            keyframeCount = activeProp.keyframes.size,
                            onPrevKeyframe = { onJumpKeyframe(false, editingEffectMode) },
                            onToggleKeyframe = { onToggleKeyframe(editingEffectMode) },
                            onNextKeyframe = { onJumpKeyframe(true, editingEffectMode) },
                            modifier = Modifier.padding(end = 8.dp),
                            diamondTestTag = "graph_keyframe_diamond_toggle"
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = SurfaceDark)
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // 1. TARGET SOURCE SWITCHER: LAYER TRANSFORM vs ADDED EFFECTS
            if (layerEffects.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .widthIn(max = 520.dp)
                        .fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    AmSegmentChip(
                        label = "Layer Transform",
                        selected = !editingEffectMode,
                        onClick = { editingEffectMode = false }
                    )
                    AmSegmentChip(
                        label = "Effect Properties (${layerEffects.size})",
                        selected = editingEffectMode,
                        onClick = {
                            editingEffectMode = true
                            if (activeFx != null && selectedEffectId == null) {
                                onSelectEffect(activeFx.id)
                            }
                        },
                        testTag = "graph_mode_effects_chip"
                    )
                }
            }

            // 2. CHANNEL SELECTOR STRIP
            if (!editingEffectMode) {
                Row(
                    modifier = Modifier
                        .widthIn(max = 520.dp)
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    PropertyTarget.entries.forEach { target ->
                        val kfs = selectedLayer?.propertyFor(target)?.keyframes?.size ?: 0
                        AmSegmentChip(
                            label = if (kfs > 0) "${target.label} ◆$kfs" else target.label,
                            selected = selectedProperty == target,
                            compact = true,
                            onClick = { onSelectProperty(target) }
                        )
                    }
                }
            } else {
                // Effect Node Selector + All 5 Effect Parameter Slots
                Row(
                    modifier = Modifier
                        .widthIn(max = 520.dp)
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Effect:", style = MaterialTheme.typography.labelSmall, color = TextSecondary)
                    layerEffects.forEach { fx ->
                        AmSegmentChip(
                            label = fx.displayName,
                            selected = activeFx?.id == fx.id,
                            compact = true,
                            onClick = { onSelectEffect(fx.id) }
                        )
                    }
                }
                Row(
                    modifier = Modifier
                        .widthIn(max = 520.dp)
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Param:", style = MaterialTheme.typography.labelSmall, color = TextSecondary)
                    activeFxSpecs.forEach { spec ->
                        val kfs = activeFx?.propertyFor(spec.slot)?.keyframes?.size ?: 0
                        AmSegmentChip(
                            label = if (kfs > 0) "${spec.label} ◆$kfs" else spec.label,
                            selected = selectedEffectSlot == spec.slot,
                            compact = true,
                            onClick = { onSelectEffectSlot(spec.slot) }
                        )
                    }
                }
            }

            // 3. 100% CONTROLLABLE BEZIER & MULTI-KEYFRAME VALUE GRAPH CANVAS
            AmControllableCurveEditor(
                property = activeProp,
                minValue = minDomain,
                maxValue = maxDomain,
                playheadMs = playheadMs,
                clipStartMs = selectedLayer?.startTimeMs ?: 0L,
                clipEndMs = selectedLayer?.endTimeMs ?: 10000L,
                hasCopiedCurve = hasCopiedCurve,
                onUpdateBezierHandles = { ox, oy, ix, iy, hold ->
                    onUpdateBezierHandles(ox, oy, ix, iy, hold, editingEffectMode)
                },
                onCopyCurve = { onCopyCurve(editingEffectMode) },
                onPasteCurve = { onPasteCurve(editingEffectMode) },
                onDragKeyframeNode = { id, t, v ->
                    onDragKeyframeNode(id, t, v, editingEffectMode)
                },
                onScrubPlayhead = onScrubPlayhead,
                canvasHeight = 260.dp,
                compactMode = false,
                canvasTestTag = "bezier_curve_canvas",
                modifier = Modifier.widthIn(max = 520.dp)
            )

            // 4. PROCEDURAL KEYFLOW MATH EXPRESSION ENGINE CARD
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = SurfaceDark,
                modifier = Modifier
                    .widthIn(max = 520.dp)
                    .fillMaxWidth()
                    .border(1.dp, OutlineSubtle, RoundedCornerShape(12.dp))
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "fx Procedural Expression Engine ($activeTitle)",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                    OutlinedTextField(
                        value = expressionText,
                        onValueChange = {
                            expressionText = it
                            onUpdateExpression(it, editingEffectMode)
                        },
                        label = { Text("Expression (e.g. sin(time * 4) * 50)") },
                        singleLine = true,
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("expression_input")
                    )
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        val amp = if ((maxDomain - minDomain) <= 6f) "0.35" else "50"
                        listOf(
                            "Clear" to "",
                            "KeyFlow Spring" to if (amp == "0.35") "spring(3, 4, 0.45)" else "spring(3, 4, 65)",
                            "KeyFlow Pendulum" to if (amp == "0.35") "pendulum(2.2, 0.35)" else "pendulum(2.2, 35)",
                            "Sine Wave" to "sin(time * 4) * $amp",
                            "Oscillate / Wiggle" to "wiggle(5, $amp)",
                            "BPM Beat Pulse" to "pulse(128) * $amp"
                        ).forEach { (label, expr) ->
                            AmSegmentChip(
                                label = label,
                                selected = expressionText == expr && expr.isNotEmpty(),
                                compact = true,
                                onClick = {
                                    expressionText = expr
                                    onUpdateExpression(expr, editingEffectMode)
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}
