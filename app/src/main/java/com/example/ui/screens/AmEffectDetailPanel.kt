package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ShowChart
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Timeline
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.data.model.AudioBand
import com.example.data.model.EffectNode
import com.example.data.model.EffectParamSlot
import com.example.data.model.Layer
import com.example.engine.AnimationEvaluator
import com.example.engine.AudioSpectrumSnapshot
import com.example.engine.EffectsCatalog
import com.example.ui.components.AmControllableCurveEditor
import com.example.ui.components.AmKeyframeCluster
import com.example.ui.components.AmSegmentChip
import com.example.ui.components.AmSquareIconButton
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
import kotlin.math.abs

private val FX_TINT_SWATCHES = listOf(
    "#FFFFFF", "#00D2A8", "#0094FF", "#FF3366",
    "#FFC400", "#9B6BFF", "#00E5FF", "#FF6D00"
)

/**
 * 1:1 Alight Motion Clickable Effect Detail Inspector!
 * Opens immediately when an Effect is added OR clicked in the Layer Effects stack.
 * Exposes ALL 5 domain-specific shader parameters (Strength, Radius, Frequency/Phase, Angle, Mix),
 * Tint Color swatches, Audio Reactor band binding, KeyFlow expressions, Keyframe Diamonds ⟨ ◆+ ⟩,
 * and a dedicated per-effect Curve Editor!
 */
@Composable
fun AmEffectDetailInspectorPanel(
    layer: Layer,
    effect: EffectNode,
    effectIndex: Int,
    totalEffects: Int,
    selectedSlot: EffectParamSlot,
    playheadMs: Long,
    audioSpectrum: AudioSpectrumSnapshot,
    onBackToEffectsList: () -> Unit,
    onSelectSlot: (EffectParamSlot) -> Unit,
    onUpdateSlotValue: (Float) -> Unit,
    onToggleSlotKeyframe: () -> Unit,
    onJumpSlotKeyframe: (Boolean) -> Unit,
    onOpenEffectCurveEditor: () -> Unit,
    onOpenFullGraphForEffect: () -> Unit,
    onUpdateSlotExpression: (String) -> Unit,
    onUpdateEffectNode: (EffectNode) -> Unit,
    onMoveEffectOrder: (moveUp: Boolean) -> Unit,
    onDuplicateEffect: () -> Unit,
    onResetEffect: () -> Unit,
    onRemoveEffect: () -> Unit,
    modifier: Modifier = Modifier
) {
    val definition = remember(effect.effectId) { EffectsCatalog.findById(effect.effectId) }
    val specs = remember(definition) { definition.paramSpecs() }
    val activeSpec = specs.firstOrNull { it.slot == selectedSlot } ?: specs.first()
    val activeProp = effect.propertyFor(selectedSlot)
    val evalVal = AnimationEvaluator.evaluateProperty(activeProp, playheadMs, audioSpectrum)
    val hasKfAtPlayhead = activeProp.keyframes.any { abs(it.timeMs - playheadMs) <= 25L }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .testTag("effect_detail_inspector"),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // 1. EFFECT HEADER BAR (← Back | Effect Name + Category | Reorder ↑↓ | Vis | Dup | Reset | Delete)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(PanelDark)
                .border(1.dp, OutlineSubtle, RoundedCornerShape(10.dp))
                .padding(horizontal = 8.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                AmSquareIconButton(
                    icon = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back to effects stack",
                    tint = AmTealBright,
                    onClick = onBackToEffectsList,
                    sizeDp = 32.dp,
                    testTag = "effect_detail_back_button"
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = effect.displayName,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "${definition.category.title} • ${effect.allKeyframeTimes().size} ◆",
                        style = MaterialTheme.typography.labelSmall,
                        color = AmTealBright,
                        maxLines = 1
                    )
                }
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                AmSquareIconButton(
                    icon = Icons.Default.KeyboardArrowUp,
                    contentDescription = "Move effect up",
                    enabled = effectIndex > 0,
                    onClick = { onMoveEffectOrder(true) },
                    sizeDp = 30.dp
                )
                AmSquareIconButton(
                    icon = Icons.Default.KeyboardArrowDown,
                    contentDescription = "Move effect down",
                    enabled = effectIndex < totalEffects - 1,
                    onClick = { onMoveEffectOrder(false) },
                    sizeDp = 30.dp
                )
                AmSquareIconButton(
                    icon = if (effect.enabled) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                    contentDescription = "Toggle effect visibility",
                    active = effect.enabled,
                    onClick = { onUpdateEffectNode(effect.copy(enabled = !effect.enabled)) },
                    sizeDp = 30.dp
                )
                AmSquareIconButton(
                    icon = Icons.Default.ContentCopy,
                    contentDescription = "Duplicate effect",
                    onClick = onDuplicateEffect,
                    sizeDp = 30.dp,
                    testTag = "duplicate_effect_button"
                )
                AmSquareIconButton(
                    icon = Icons.Default.Refresh,
                    contentDescription = "Reset effect to standard settings",
                    onClick = onResetEffect,
                    sizeDp = 30.dp,
                    testTag = "reset_effect_button"
                )
                AmSquareIconButton(
                    icon = Icons.Default.DeleteOutline,
                    contentDescription = "Remove effect",
                    tint = NeonPink,
                    onClick = onRemoveEffect,
                    sizeDp = 30.dp,
                    testTag = "remove_effect_button"
                )
            }
        }

        // 2. ALL 5 CONFIGURABLE EFFECT PARAMETERS SELECTOR STRIP (1:1 Alight Motion Parameter Cards!)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            specs.forEach { spec ->
                val p = effect.propertyFor(spec.slot)
                val v = AnimationEvaluator.evaluateProperty(p, playheadMs, audioSpectrum)
                val kfCount = p.keyframes.size
                val isSelected = spec.slot == selectedSlot
                val formatted = if ((spec.maxVal - spec.minVal) <= 6f) {
                    String.format("%.2f", v)
                } else {
                    String.format("%.0f", v)
                }

                Column(
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .background(if (isSelected) AmTealContainer else PanelDark)
                        .border(
                            1.2.dp,
                            if (isSelected) AmTealBright else OutlineSubtle,
                            RoundedCornerShape(10.dp)
                        )
                        .clickable { onSelectSlot(spec.slot) }
                        .testTag("effect_param_chip_${spec.slot.name}")
                        .padding(horizontal = 10.dp, vertical = 5.dp),
                    horizontalAlignment = Alignment.Start
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text = spec.label,
                            style = MaterialTheme.typography.labelSmall,
                            color = if (isSelected) AmTealBright else TextSecondary,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                        )
                        if (kfCount > 0) {
                            Text(
                                text = "◆$kfCount",
                                style = MaterialTheme.typography.labelSmall,
                                color = KeyframeAmber,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                    Text(
                        text = "$formatted${spec.unit}",
                        style = MaterialTheme.typography.labelMedium,
                        color = TextPrimary,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }

        // 3. ACTIVE PARAMETER KEYFRAME CLUSTER ⟨ ◆+ ⟩ + DEDICATED CURVE EDITOR BUTTON + TICK WHEEL SCRUBBER
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.Top
        ) {
            Column(
                modifier = Modifier.width(104.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                AmKeyframeCluster(
                    hasKeyframeAtPlayhead = hasKfAtPlayhead,
                    keyframeCount = activeProp.keyframes.size,
                    onPrevKeyframe = { onJumpSlotKeyframe(false) },
                    onToggleKeyframe = onToggleSlotKeyframe,
                    onNextKeyframe = { onJumpSlotKeyframe(true) },
                    diamondTestTag = "effect_keyframe_diamond_toggle"
                )

                // Dedicated Per-Effect Inline Curve Editor Button!
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(34.dp)
                        .clip(RoundedCornerShape(9.dp))
                        .background(PanelDark)
                        .border(1.dp, AmTeal.copy(alpha = 0.65f), RoundedCornerShape(9.dp))
                        .clickable(onClick = onOpenEffectCurveEditor)
                        .testTag("effect_curve_editor_button")
                        .padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ShowChart,
                        contentDescription = "Effect Parameter Curve Editor",
                        tint = AmTealBright,
                        modifier = Modifier.size(15.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "Curve",
                        style = MaterialTheme.typography.labelSmall,
                        color = AmTealBright,
                        fontWeight = FontWeight.Bold
                    )
                }

                // Full-Screen Graph Editor Shortcut for this Effect Parameter
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(30.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(AmTealContainer.copy(alpha = 0.45f))
                        .border(1.dp, OutlineSubtle, RoundedCornerShape(8.dp))
                        .clickable(onClick = onOpenFullGraphForEffect)
                        .padding(horizontal = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    Icon(Icons.Default.Timeline, contentDescription = null, tint = TextPrimary, modifier = Modifier.size(13.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Full Graph", style = MaterialTheme.typography.labelSmall, color = TextPrimary)
                }
            }

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                AmTickWheelScrubber(
                    value = evalVal,
                    minValue = activeSpec.minVal,
                    maxValue = activeSpec.maxVal,
                    unit = activeSpec.unit,
                    label = "${effect.displayName} • ${activeSpec.label}",
                    fineStep = activeSpec.fineStep,
                    onValueChange = onUpdateSlotValue,
                    testTag = "effect_property_value_slider"
                )

                // KeyFlow Procedural Expression Chips on Active Effect Parameter!
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Expr:", style = MaterialTheme.typography.labelSmall, color = TextMuted)
                    val amp = if ((activeSpec.maxVal - activeSpec.minVal) <= 6f) "0.35" else "30"
                    listOf(
                        "Off" to "",
                        "Pulse" to "pulse(128) * $amp",
                        "Sine" to "sin(time * 4) * $amp",
                        "Flicker" to "wiggle(9, $amp)"
                    ).forEach { (lbl, expr) ->
                        AmSegmentChip(
                            label = lbl,
                            selected = activeProp.expression == expr && (expr.isNotEmpty() || activeProp.expression.isEmpty()),
                            compact = true,
                            onClick = { onUpdateSlotExpression(expr) }
                        )
                    }
                }
            }
        }

        // 4. EFFECT COLOR / GLOW TINT SWATCHES + AUDIO REACTOR BAND BINDING
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Color:", style = MaterialTheme.typography.labelSmall, color = TextSecondary)
            FX_TINT_SWATCHES.forEach { hex ->
                val c = remember(hex) {
                    runCatching { Color(android.graphics.Color.parseColor(hex)) }.getOrDefault(Color.White)
                }
                val isSel = effect.tintColorHex.equals(hex, ignoreCase = true)
                Box(
                    modifier = Modifier
                        .size(24.dp)
                        .clip(CircleShape)
                        .background(c)
                        .border(if (isSel) 2.5.dp else 1.dp, if (isSel) AmTealBright else OutlineSubtle, CircleShape)
                        .clickable { onUpdateEffectNode(effect.copy(tintColorHex = hex)) }
                )
            }

            Spacer(modifier = Modifier.width(6.dp))
            Text("Audio:", style = MaterialTheme.typography.labelSmall, color = TextSecondary)
            AudioBand.entries.forEach { band ->
                AmSegmentChip(
                    label = if (band == AudioBand.NONE) "Manual" else band.name,
                    selected = effect.audioBoundBand == band,
                    compact = true,
                    onClick = { onUpdateEffectNode(effect.copy(audioBoundBand = band)) }
                )
            }
        }

        // 5. EXPLICIT DELETE EFFECT & RESET FOOTER BAR
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                modifier = Modifier
                    .weight(1f)
                    .height(34.dp)
                    .clip(RoundedCornerShape(9.dp))
                    .background(NeonPink.copy(alpha = 0.14f))
                    .border(1.dp, NeonPink.copy(alpha = 0.65f), RoundedCornerShape(9.dp))
                    .clickable(onClick = onRemoveEffect)
                    .testTag("delete_effect_detail_button")
                    .padding(horizontal = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                Icon(
                    imageVector = Icons.Default.DeleteOutline,
                    contentDescription = "Delete Effect",
                    tint = NeonPink,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "Delete Effect",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = NeonPink
                )
            }

            Row(
                modifier = Modifier
                    .height(34.dp)
                    .clip(RoundedCornerShape(9.dp))
                    .background(PanelDark)
                    .border(1.dp, OutlineSubtle, RoundedCornerShape(9.dp))
                    .clickable(onClick = onDuplicateEffect)
                    .padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                Icon(
                    imageVector = Icons.Default.ContentCopy,
                    contentDescription = "Duplicate Effect",
                    tint = TextPrimary,
                    modifier = Modifier.size(14.dp)
                )
                Spacer(modifier = Modifier.width(5.dp))
                Text(
                    text = "Duplicate",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = TextPrimary
                )
            }
        }
    }
}

/**
 * Dedicated Inline Curve Editor for the currently selected Effect Parameter (`AmInspectorTab.EFFECT_EASING`)!
 */
@Composable
fun AmEffectInlineCurvePanel(
    layer: Layer,
    effect: EffectNode,
    selectedSlot: EffectParamSlot,
    playheadMs: Long,
    hasCopiedCurve: Boolean,
    onSelectSlot: (EffectParamSlot) -> Unit,
    onUpdateEffectBezierHandles: (outX: Float, outY: Float, inX: Float, inY: Float, holdStep: Boolean) -> Unit,
    onCopyCurve: () -> Unit,
    onPasteCurve: () -> Unit,
    onDragEffectKeyframeNode: (kfId: String, newTimeMs: Long, newValue: Float) -> Unit,
    onScrubPlayhead: (Long) -> Unit,
    onReturnToEffectDetail: () -> Unit,
    onOpenFullGraph: () -> Unit,
    modifier: Modifier = Modifier
) {
    val definition = remember(effect.effectId) { EffectsCatalog.findById(effect.effectId) }
    val specs = remember(definition) { definition.paramSpecs() }
    val activeSpec = specs.firstOrNull { it.slot == selectedSlot } ?: specs.first()
    val activeProp = effect.propertyFor(selectedSlot)

    Column(
        modifier = modifier
            .fillMaxWidth()
            .testTag("effect_inline_curve_panel"),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                AmSquareIconButton(
                    icon = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back to effect properties",
                    tint = AmTealBright,
                    onClick = onReturnToEffectDetail,
                    sizeDp = 30.dp,
                    testTag = "effect_curve_back_button"
                )
                Text(
                    text = "${effect.displayName} • ${activeSpec.label} Curve",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            AmTimingBarButton(
                icon = Icons.Default.Timeline,
                label = "Full Screen",
                active = true,
                onClick = onOpenFullGraph
            )
        }

        // Quick switch between the 5 parameters of this Effect right inside its Curve Editor!
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            specs.forEach { spec ->
                val kfs = effect.propertyFor(spec.slot).keyframes.size
                AmSegmentChip(
                    label = if (kfs > 0) "${spec.label} ◆$kfs" else spec.label,
                    selected = spec.slot == selectedSlot,
                    compact = true,
                    onClick = { onSelectSlot(spec.slot) }
                )
            }
        }

        AmControllableCurveEditor(
            property = activeProp,
            minValue = activeSpec.minVal,
            maxValue = activeSpec.maxVal,
            playheadMs = playheadMs,
            clipStartMs = layer.startTimeMs,
            clipEndMs = layer.endTimeMs,
            hasCopiedCurve = hasCopiedCurve,
            onUpdateBezierHandles = onUpdateEffectBezierHandles,
            onCopyCurve = onCopyCurve,
            onPasteCurve = onPasteCurve,
            onDragKeyframeNode = onDragEffectKeyframeNode,
            onScrubPlayhead = onScrubPlayhead,
            canvasHeight = 164.dp,
            compactMode = true,
            canvasTestTag = "effect_bezier_curve_canvas"
        )
    }
}
