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
import androidx.compose.material.icons.filled.Colorize
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Timeline
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.data.model.AnimatableProperty
import com.example.data.model.AudioBand
import com.example.data.model.EffectNode
import com.example.data.model.EffectParamSlot
import com.example.data.model.Layer
import com.example.engine.AnimationEvaluator
import com.example.engine.AudioSpectrumSnapshot
import com.example.engine.EffectsCatalog
import com.example.engine.PluginPackManager
import com.example.engine.PluginParamDef
import com.example.engine.PluginParamType
import com.example.ui.components.AmControllableCurveEditor
import com.example.ui.components.AmCustomColorAndGradientPicker
import com.example.ui.components.AmKeyframeCluster
import com.example.ui.components.AmSegmentChip
import com.example.ui.components.AmSquareIconButton
import com.example.ui.components.AmTickWheelScrubber
import com.example.ui.components.EyedropperColorSlot
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Diamond
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.Cancel
import androidx.compose.material.icons.outlined.Diamond
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.ui.unit.sp
import com.example.ui.components.KeyflowPropertyRulerCard
import com.example.ui.theme.AmTeal
import com.example.ui.theme.AmTealBright
import com.example.ui.theme.AmTealContainer
import com.example.ui.theme.AudioCyan
import com.example.ui.theme.DeepBlack
import com.example.ui.theme.ElectricBlue
import com.example.ui.theme.KeyflowBlue
import com.example.ui.theme.KeyflowBlueBright
import com.example.ui.theme.KeyflowCardBorder
import com.example.ui.theme.KeyflowCardSurface
import com.example.ui.theme.KeyflowDangerRed
import com.example.ui.theme.KeyflowDrawerBg
import com.example.ui.theme.KeyflowRailDivider
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

private val FX_TINT_SWATCHES = listOf(
    "#FFFFFF", "#00D2A8", "#0094FF", "#FF3366",
    "#FFC400", "#9B6BFF", "#00E5FF", "#FF6D00"
)

internal const val PLUGIN_MIX_KEY = "__MIX__"

/**
 * 1:1 Alight Motion + KeyFlow Clickable Effect Detail Inspector!
 * Supports both built-in 5-slot effects AND custom `.kfpack` / `.fppack` GLSL plugins with
 * unlimited typed parameters (`FLOAT` sliders, `BOOL` switches, `COLOR` swatches + HSV + Eyedropper),
 * multi-pass / expandBounds / motion-blur badges, keyframes ⟨ ◆+ ⟩, Bezier curves, and expressions.
 */
@Composable
fun AmEffectDetailInspectorPanel(
    layer: Layer,
    effect: EffectNode,
    effectIndex: Int,
    totalEffects: Int,
    selectedSlot: EffectParamSlot,
    selectedPluginParamKey: String? = null,
    playheadMs: Long,
    audioSpectrum: AudioSpectrumSnapshot,
    onBackToEffectsList: () -> Unit,
    onSelectSlot: (EffectParamSlot) -> Unit,
    onSelectPluginParamKey: (String) -> Unit = {},
    onUpdateSlotValue: (Float) -> Unit,
    onUpdateCustomPluginParamValue: (String, Float) -> Unit = { _, _ -> },
    onUpdateCustomPluginColor: (String, String) -> Unit = { _, _ -> },
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
    onRequestEyedropper: (EyedropperColorSlot) -> Unit = {},
    onRequestPluginColorEyedropper: (String) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val definition = remember(effect.effectId) { EffectsCatalog.findById(effect.effectId) }
    val pluginDef = remember(effect.effectId) { PluginPackManager.findPluginEffect(effect.effectId) }
    val specs = remember(definition) { definition.paramSpecs() }

    val numericPluginParams = remember(pluginDef) {
        pluginDef?.parameters?.filter { it.type != PluginParamType.COLOR }.orEmpty()
    }
    val colorPluginParams = remember(pluginDef) {
        pluginDef?.parameters?.filter { it.type == PluginParamType.COLOR }.orEmpty()
    }

    val resolvedPluginKey: String? = remember(pluginDef, numericPluginParams, selectedPluginParamKey) {
        if (pluginDef == null) {
            null
        } else {
            when {
                selectedPluginParamKey == PLUGIN_MIX_KEY -> PLUGIN_MIX_KEY
                selectedPluginParamKey != null && numericPluginParams.any { it.key == selectedPluginParamKey } -> selectedPluginParamKey
                numericPluginParams.isNotEmpty() -> numericPluginParams.first().key
                else -> PLUGIN_MIX_KEY
            }
        }
    }
    val activePluginParam: PluginParamDef? = remember(numericPluginParams, resolvedPluginKey) {
        numericPluginParams.firstOrNull { it.key == resolvedPluginKey }
    }

    val activeSpec = specs.firstOrNull { it.slot == selectedSlot } ?: specs.first()
    val activeProp: AnimatableProperty = when {
        pluginDef != null && resolvedPluginKey == PLUGIN_MIX_KEY -> effect.mixProp
        pluginDef != null && activePluginParam != null -> {
            effect.customParamFor(activePluginParam.key, activePluginParam.defaultFloat)
        }
        else -> effect.propertyFor(selectedSlot)
    }

    val activeLabel = when {
        pluginDef != null && resolvedPluginKey == PLUGIN_MIX_KEY -> "Mix"
        activePluginParam != null -> activePluginParam.label
        else -> activeSpec.label
    }
    val activeMin = when {
        pluginDef != null && resolvedPluginKey == PLUGIN_MIX_KEY -> 0f
        activePluginParam != null -> activePluginParam.min
        else -> activeSpec.minVal
    }
    val activeMax = when {
        pluginDef != null && resolvedPluginKey == PLUGIN_MIX_KEY -> 100f
        activePluginParam != null -> activePluginParam.max
        else -> activeSpec.maxVal
    }
    val activeUnit = when {
        pluginDef != null && resolvedPluginKey == PLUGIN_MIX_KEY -> "%"
        activePluginParam != null -> ""
        else -> activeSpec.unit
    }
    val activeFineStep = when {
        pluginDef != null && resolvedPluginKey == PLUGIN_MIX_KEY -> 1f
        activePluginParam != null -> ((activePluginParam.max - activePluginParam.min) / 200f).coerceAtLeast(0.01f)
        else -> activeSpec.fineStep
    }

    val evalVal = AnimationEvaluator.evaluateProperty(activeProp, playheadMs, audioSpectrum)
    val hasKfAtPlayhead = activeProp.keyframes.any { abs(it.timeMs - playheadMs) <= 25L }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .testTag("effect_detail_inspector"),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // 1. EFFECT HEADER BAR
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
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(5.dp)
                    ) {
                        if (pluginDef != null) {
                            Icon(
                                imageVector = Icons.Default.Extension,
                                contentDescription = null,
                                tint = AmTealBright,
                                modifier = Modifier.size(13.dp)
                            )
                        }
                        Text(
                            text = effect.displayName,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = TextPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    val kfTotal = effect.allKeyframeTimes().size
                    val subtitle = if (pluginDef != null) {
                        buildString {
                            append(pluginDef.category)
                            append(" • ${pluginDef.passes.size}P")
                            if (pluginDef.expandBounds > 0.001f) {
                                append(" • +${(pluginDef.expandBounds * 100).toInt()}%B")
                            }
                            if (pluginDef.usesNativeMotionBlur) {
                                append(" • Blur")
                            }
                            if (kfTotal > 0) append(" • ◆$kfTotal")
                        }
                    } else {
                        if (kfTotal > 0) "${definition.category.title} • ◆$kfTotal" else definition.category.title
                    }
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.labelSmall,
                        color = AmTealBright,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
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

        // 2. PARAMETER SELECTOR STRIP (Unlimited Custom Plugin Params OR Built-In 5 Slots)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            if (pluginDef != null) {
                numericPluginParams.forEach { param ->
                    val p = effect.customParamFor(param.key, param.defaultFloat)
                    val v = AnimationEvaluator.evaluateProperty(p, playheadMs, audioSpectrum)
                    val kfCount = p.keyframes.size
                    val isSelected = resolvedPluginKey == param.key
                    val formatted = when {
                        param.type == PluginParamType.BOOL -> if (v >= 0.5f) "ON" else "OFF"
                        (param.max - param.min) <= 6f -> String.format("%.2f", v)
                        else -> String.format("%.0f", v)
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
                            .clickable { onSelectPluginParamKey(param.key) }
                            .testTag("plugin_param_chip_${param.key}")
                            .padding(horizontal = 10.dp, vertical = 5.dp),
                        horizontalAlignment = Alignment.Start
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text(
                                text = param.label,
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
                            text = formatted,
                            style = MaterialTheme.typography.labelMedium,
                            color = if (param.type == PluginParamType.BOOL && v >= 0.5f) AmTealBright else TextPrimary,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                // Always include a wet/dry Mix chip for every plugin effect
                val mixV = AnimationEvaluator.evaluateProperty(effect.mixProp, playheadMs, audioSpectrum)
                val mixKfs = effect.mixProp.keyframes.size
                val isMixSelected = resolvedPluginKey == PLUGIN_MIX_KEY
                Column(
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .background(if (isMixSelected) AmTealContainer else PanelDark)
                        .border(
                            1.2.dp,
                            if (isMixSelected) AmTealBright else OutlineSubtle,
                            RoundedCornerShape(10.dp)
                        )
                        .clickable { onSelectPluginParamKey(PLUGIN_MIX_KEY) }
                        .testTag("plugin_param_chip_mix")
                        .padding(horizontal = 10.dp, vertical = 5.dp),
                    horizontalAlignment = Alignment.Start
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text = "Mix",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (isMixSelected) AmTealBright else TextSecondary,
                            fontWeight = if (isMixSelected) FontWeight.Bold else FontWeight.Medium
                        )
                        if (mixKfs > 0) {
                            Text(
                                text = "◆$mixKfs",
                                style = MaterialTheme.typography.labelSmall,
                                color = KeyframeAmber,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                    Text(
                        text = "${mixV.toInt()}%",
                        style = MaterialTheme.typography.labelMedium,
                        color = TextPrimary,
                        fontWeight = FontWeight.Bold
                    )
                }
            } else {
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
        }

        // 3. ACTIVE PARAMETER KEYFRAME CLUSTER ⟨ ◆+ ⟩ + CURVE / GRAPH + SCRUBBER OR BOOL SWITCH
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
                    Text("Graph", style = MaterialTheme.typography.labelSmall, color = TextPrimary)
                }
            }

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                if (activePluginParam?.type == PluginParamType.BOOL) {
                    // KeyFlow BOOL Switch Control (maps to uniform float u<Key>: 1.0 when ON, 0.0 when OFF)
                    val checked = evalVal >= 0.5f
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(PanelDark)
                            .border(1.dp, OutlineSubtle, RoundedCornerShape(10.dp))
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = activePluginParam.label,
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.Bold,
                                color = TextPrimary
                            )
                            Text(
                                text = "${activePluginParam.uniformName} = ${if (checked) "1.0" else "0.0"}",
                                style = MaterialTheme.typography.labelSmall,
                                fontFamily = FontFamily.Monospace,
                                color = AudioCyan
                            )
                        }
                        Switch(
                            checked = checked,
                            onCheckedChange = { next ->
                                onUpdateCustomPluginParamValue(activePluginParam.key, if (next) 1f else 0f)
                            },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = DeepBlack,
                                checkedTrackColor = AmTealBright,
                                uncheckedThumbColor = TextSecondary,
                                uncheckedTrackColor = PanelDark
                            ),
                            modifier = Modifier.testTag("plugin_bool_switch_${activePluginParam.key}")
                        )
                    }
                } else {
                    AmTickWheelScrubber(
                        value = evalVal,
                        minValue = activeMin,
                        maxValue = activeMax,
                        unit = activeUnit,
                        label = activeLabel,
                        fineStep = activeFineStep,
                        onValueChange = { next ->
                            if (pluginDef != null && resolvedPluginKey != null) {
                                onUpdateCustomPluginParamValue(resolvedPluginKey, next)
                            } else {
                                onUpdateSlotValue(next)
                            }
                        },
                        testTag = "effect_property_value_slider"
                    )
                }

                // KeyFlow Procedural Expression Chips on Active Effect Parameter!
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Expr", style = MaterialTheme.typography.labelSmall, color = TextMuted)
                    val amp = if ((activeMax - activeMin) <= 6f) "0.35" else "30"
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

        // 4. COLOR CONTROLS (All Plugin COLOR Uniforms OR Built-in Tint Color) + AUDIO BAND BINDING
        if (pluginDef != null) {
            var expandedPluginColorKey by remember(effect.id) { mutableStateOf<String?>(null) }

            colorPluginParams.forEach { colorParam ->
                val currentHex = effect.customColorFor(colorParam.key, colorParam.defaultColorHex)
                val isWheelOpen = expandedPluginColorKey == colorParam.key
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(7.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(colorParam.label, style = MaterialTheme.typography.labelSmall, color = TextSecondary)
                    AmSquareIconButton(
                        icon = Icons.Default.Colorize,
                        contentDescription = "Sample ${colorParam.label}",
                        tint = AmTealBright,
                        onClick = { onRequestPluginColorEyedropper(colorParam.key) },
                        sizeDp = 28.dp,
                        testTag = "plugin_color_eyedropper_${colorParam.key}"
                    )
                    AmSquareIconButton(
                        icon = Icons.Default.Palette,
                        contentDescription = "Custom color wheel",
                        active = isWheelOpen,
                        tint = if (isWheelOpen) AmTealBright else TextPrimary,
                        onClick = {
                            expandedPluginColorKey = if (isWheelOpen) null else colorParam.key
                        },
                        sizeDp = 28.dp,
                        testTag = "plugin_color_wheel_toggle_${colorParam.key}"
                    )
                    FX_TINT_SWATCHES.forEach { hex ->
                        val c = remember(hex) {
                            runCatching { Color(android.graphics.Color.parseColor(hex)) }.getOrDefault(Color.White)
                        }
                        val isSel = currentHex.equals(hex, ignoreCase = true)
                        Box(
                            modifier = Modifier
                                .size(22.dp)
                                .clip(CircleShape)
                                .background(c)
                                .border(if (isSel) 2.5.dp else 1.dp, if (isSel) AmTealBright else OutlineSubtle, CircleShape)
                                .clickable { onUpdateCustomPluginColor(colorParam.key, hex) }
                        )
                    }
                }
                if (isWheelOpen) {
                    AmCustomColorAndGradientPicker(
                        primaryHex = currentHex,
                        onPrimaryHexChange = { hex -> onUpdateCustomPluginColor(colorParam.key, hex) },
                        onRequestEyedropper = { onRequestPluginColorEyedropper(colorParam.key) },
                        primaryEyedropperSlot = EyedropperColorSlot.EFFECT_TINT,
                        supportsGradient = false,
                        compact = true,
                        testTagPrefix = "plugin_color_picker_${colorParam.key}"
                    )
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Audio", style = MaterialTheme.typography.labelSmall, color = TextSecondary)
                AudioBand.entries.forEach { band ->
                    AmSegmentChip(
                        label = if (band == AudioBand.NONE) "Off" else band.name,
                        selected = effect.audioBoundBand == band,
                        compact = true,
                        onClick = { onUpdateEffectNode(effect.copy(audioBoundBand = band)) }
                    )
                }
            }
        } else {
            var showCustomFxColorPicker by remember(effect.id) {
                mutableStateOf(effect.effectId == "Chroma_Key" || effect.effectId == "Luma_Key")
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(7.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(definition.colorLabel, style = MaterialTheme.typography.labelSmall, color = TextSecondary)
                AmSquareIconButton(
                    icon = Icons.Default.Colorize,
                    contentDescription = "Sample ${definition.colorLabel} from screen",
                    tint = AmTealBright,
                    onClick = { onRequestEyedropper(EyedropperColorSlot.EFFECT_TINT) },
                    sizeDp = 28.dp,
                    testTag = "effect_eyedropper_button"
                )
                AmSquareIconButton(
                    icon = Icons.Default.Palette,
                    contentDescription = "Toggle custom color wheel",
                    active = showCustomFxColorPicker,
                    tint = if (showCustomFxColorPicker) AmTealBright else TextPrimary,
                    onClick = { showCustomFxColorPicker = !showCustomFxColorPicker },
                    sizeDp = 28.dp,
                    testTag = "effect_custom_color_toggle"
                )
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
                Text("Audio", style = MaterialTheme.typography.labelSmall, color = TextSecondary)
                AudioBand.entries.forEach { band ->
                    AmSegmentChip(
                        label = if (band == AudioBand.NONE) "Off" else band.name,
                        selected = effect.audioBoundBand == band,
                        compact = true,
                        onClick = { onUpdateEffectNode(effect.copy(audioBoundBand = band)) }
                    )
                }
            }

            if (showCustomFxColorPicker) {
                AmCustomColorAndGradientPicker(
                    primaryHex = effect.tintColorHex,
                    onPrimaryHexChange = { hex -> onUpdateEffectNode(effect.copy(tintColorHex = hex)) },
                    onRequestEyedropper = onRequestEyedropper,
                    primaryEyedropperSlot = EyedropperColorSlot.EFFECT_TINT,
                    supportsGradient = false,
                    compact = true,
                    testTagPrefix = "effect_custom_color_picker"
                )
            }
        }

        // 5. EXPLICIT DELETE EFFECT & DUPLICATE FOOTER BAR
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
                    contentDescription = "Delete",
                    tint = NeonPink,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "Delete",
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
    selectedPluginParamKey: String? = null,
    playheadMs: Long,
    hasCopiedCurve: Boolean,
    onSelectSlot: (EffectParamSlot) -> Unit,
    onSelectPluginParamKey: (String) -> Unit = {},
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
    val pluginDef = remember(effect.effectId) { PluginPackManager.findPluginEffect(effect.effectId) }
    val specs = remember(definition) { definition.paramSpecs() }
    val numericPluginParams = remember(pluginDef) {
        pluginDef?.parameters?.filter { it.type != PluginParamType.COLOR }.orEmpty()
    }
    val resolvedPluginKey: String? = remember(pluginDef, numericPluginParams, selectedPluginParamKey) {
        if (pluginDef == null) null else when {
            selectedPluginParamKey == PLUGIN_MIX_KEY -> PLUGIN_MIX_KEY
            selectedPluginParamKey != null && numericPluginParams.any { it.key == selectedPluginParamKey } -> selectedPluginParamKey
            numericPluginParams.isNotEmpty() -> numericPluginParams.first().key
            else -> PLUGIN_MIX_KEY
        }
    }
    val activePluginParam = remember(numericPluginParams, resolvedPluginKey) {
        numericPluginParams.firstOrNull { it.key == resolvedPluginKey }
    }

    val activeSpec = specs.firstOrNull { it.slot == selectedSlot } ?: specs.first()
    val activeProp = when {
        pluginDef != null && resolvedPluginKey == PLUGIN_MIX_KEY -> effect.mixProp
        pluginDef != null && activePluginParam != null -> {
            effect.customParamFor(activePluginParam.key, activePluginParam.defaultFloat)
        }
        else -> effect.propertyFor(selectedSlot)
    }
    val activeLabel = when {
        pluginDef != null && resolvedPluginKey == PLUGIN_MIX_KEY -> "Mix"
        activePluginParam != null -> activePluginParam.label
        else -> activeSpec.label
    }
    val activeMin = when {
        pluginDef != null && resolvedPluginKey == PLUGIN_MIX_KEY -> 0f
        activePluginParam != null -> activePluginParam.min
        else -> activeSpec.minVal
    }
    val activeMax = when {
        pluginDef != null && resolvedPluginKey == PLUGIN_MIX_KEY -> 100f
        activePluginParam != null -> activePluginParam.max
        else -> activeSpec.maxVal
    }

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
                    text = "${effect.displayName} • $activeLabel",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            AmTimingBarButton(
                icon = Icons.Default.Timeline,
                label = "Graph",
                active = true,
                onClick = onOpenFullGraph
            )
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            if (pluginDef != null) {
                numericPluginParams.forEach { param ->
                    val kfs = effect.customParamFor(param.key, param.defaultFloat).keyframes.size
                    AmSegmentChip(
                        label = if (kfs > 0) "${param.label} ◆$kfs" else param.label,
                        selected = resolvedPluginKey == param.key,
                        compact = true,
                        onClick = { onSelectPluginParamKey(param.key) }
                    )
                }
                val mixKfs = effect.mixProp.keyframes.size
                AmSegmentChip(
                    label = if (mixKfs > 0) "Mix ◆$mixKfs" else "Mix",
                    selected = resolvedPluginKey == PLUGIN_MIX_KEY,
                    compact = true,
                    onClick = { onSelectPluginParamKey(PLUGIN_MIX_KEY) }
                )
            } else {
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
        }

        AmControllableCurveEditor(
            property = activeProp,
            minValue = activeMin,
            maxValue = activeMax,
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

/**
 * KeyFlow Signature Effects Split Drawer (Screenshots 2, 3, and 4):
 * - Left 50.dp Vertical Rail: `(⊗)` Close -> Overview | `◇` Keyframe Diamond | `📈` Curve Graph | `⋮` Overflow Menu
 * - Right Area:
 *   - Empty State: `"No effects."`, `"Tap + to add."`, and KeyFlow Blue `+  Add Effect` CTA
 *   - Populated State: Collapsible `#1A1C22` Effect Accordion Cards (`=` | Name | Eye | `⌄`/`⌃` | Red Trash | `⋮`)
 *     that expand inline into `KeyflowPropertyRulerCard` scrubbers + KeyFlow Blue `+  Add Effect` CTA underneath!
 */
@Composable
fun KeyflowEffectsSplitDrawer(
    activeTab: AmInspectorTab,
    layer: Layer,
    selectedEffectId: String?,
    selectedSlot: EffectParamSlot,
    selectedPluginParamKey: String?,
    playheadMs: Long,
    audioSpectrum: AudioSpectrumSnapshot,
    hasCopiedEffects: Boolean,
    hasCopiedCurve: Boolean,
    onCloseDrawer: () -> Unit,
    onSetInspectorTab: (AmInspectorTab) -> Unit,
    onSelectEffectForInspection: (String) -> Unit,
    onSelectSlot: (EffectParamSlot) -> Unit,
    onSelectPluginParamKey: (String) -> Unit,
    onUpdateSlotValue: (Float) -> Unit,
    onUpdateCustomPluginParamValue: (String, Float) -> Unit,
    onUpdateCustomPluginColor: (String, String) -> Unit,
    onToggleSlotKeyframe: () -> Unit,
    onUpdateEffectBezierHandles: (Float, Float, Float, Float, Boolean) -> Unit,
    onUpdateSlotExpression: (String) -> Unit,
    onCopyCurve: () -> Unit,
    onPasteCurve: () -> Unit,
    onDragEffectKeyframeNode: (String, Long, Float) -> Unit,
    onScrubPlayhead: (Long) -> Unit,
    onOpenFullGraph: () -> Unit,
    onUpdateEffectNode: (EffectNode) -> Unit,
    onMoveEffectOrder: (String, Boolean) -> Unit,
    onDuplicateEffect: (String) -> Unit,
    onResetEffect: (String) -> Unit,
    onRemoveEffectNode: (String) -> Unit,
    onClearAllEffects: (() -> Unit)?,
    onCopyEffects: () -> Unit,
    onPasteEffects: () -> Unit,
    onToggleMotionBlur: (Boolean, Float) -> Unit,
    onNavigateTool: (StudioDestination) -> Unit,
    onRequestEyedropper: (EyedropperColorSlot) -> Unit,
    onRequestPluginColorEyedropper: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val activeExpandedFx = remember(layer.effects, selectedEffectId) {
        layer.effects.firstOrNull { it.id == selectedEffectId } ?: layer.effects.lastOrNull()
    }
    val hasActivePropertyContext = (activeTab == AmInspectorTab.EFFECT_DETAIL || activeTab == AmInspectorTab.EFFECT_EASING) &&
        activeExpandedFx != null

    val activePropForRail: AnimatableProperty? = remember(
        activeExpandedFx,
        selectedSlot,
        selectedPluginParamKey,
        hasActivePropertyContext
    ) {
        if (!hasActivePropertyContext || activeExpandedFx == null) {
            null
        } else {
            val pDef = PluginPackManager.findPluginEffect(activeExpandedFx.effectId)
            if (pDef != null) {
                val numericParams = pDef.parameters.filter { it.type != PluginParamType.COLOR }
                val key = when {
                    selectedPluginParamKey == PLUGIN_MIX_KEY -> PLUGIN_MIX_KEY
                    selectedPluginParamKey != null && numericParams.any { it.key == selectedPluginParamKey } -> selectedPluginParamKey
                    numericParams.isNotEmpty() -> numericParams.first().key
                    else -> PLUGIN_MIX_KEY
                }
                if (key == PLUGIN_MIX_KEY) {
                    activeExpandedFx.mixProp
                } else {
                    val param = numericParams.first { it.key == key }
                    activeExpandedFx.customParamFor(param.key, param.defaultFloat)
                }
            } else {
                activeExpandedFx.propertyFor(selectedSlot)
            }
        }
    }

    val hasKfAtPlayhead = activePropForRail?.keyframes?.any { abs(it.timeMs - playheadMs) <= 35L } == true
    val activeKfCount = activePropForRail?.keyframes?.size ?: 0
    var railOverflowOpen by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(KeyflowDrawerBg)
    ) {
        HorizontalDivider(thickness = 0.8.dp, color = KeyflowRailDivider)

        Row(modifier = Modifier.fillMaxSize()) {
            // LEFT 50.dp VERTICAL TOOL RAIL
            Column(
                modifier = Modifier
                    .width(50.dp)
                    .fillMaxHeight()
                    .background(KeyflowDrawerBg),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // 1. Circle-X Close Button (Returns to Selected-Layer Overview)
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .clickable(onClick = onCloseDrawer)
                        .testTag("collapse_layer_sheet_button"),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Cancel,
                        contentDescription = "Close Effects",
                        tint = Color(0xFFD5DCE8),
                        modifier = Modifier.size(22.dp)
                    )
                }
                HorizontalDivider(thickness = 0.8.dp, color = KeyflowRailDivider)

                Spacer(modifier = Modifier.height(6.dp))

                // 2. Keyframe Diamond Toggle ◇
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(
                            if (hasKfAtPlayhead) KeyflowBlue.copy(alpha = 0.18f) else Color.Transparent
                        )
                        .clickable(enabled = hasActivePropertyContext) { onToggleSlotKeyframe() }
                        .testTag("effect_keyframe_diamond_toggle"),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = if (hasKfAtPlayhead) Icons.Filled.Diamond else Icons.Outlined.Diamond,
                        contentDescription = "Toggle Keyframe",
                        tint = when {
                            !hasActivePropertyContext -> Color(0xFF4E5563)
                            hasKfAtPlayhead -> KeyflowBlueBright
                            activeKfCount > 0 -> KeyframeAmber
                            else -> Color(0xFFD5DCE8)
                        },
                        modifier = Modifier.size(21.dp)
                    )
                }

                Spacer(modifier = Modifier.height(4.dp))

                // 3. Easing / Bezier Curve Button 📈
                val isCurveActive = activeTab == AmInspectorTab.EFFECT_EASING
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(
                            if (isCurveActive) KeyflowBlue.copy(alpha = 0.18f) else Color.Transparent
                        )
                        .clickable(enabled = hasActivePropertyContext) {
                            if (isCurveActive) {
                                onSetInspectorTab(AmInspectorTab.EFFECT_DETAIL)
                            } else {
                                onSetInspectorTab(AmInspectorTab.EFFECT_EASING)
                            }
                        }
                        .testTag("effect_curve_editor_button"),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ShowChart,
                        contentDescription = "Easing Curve",
                        tint = when {
                            !hasActivePropertyContext -> Color(0xFF4E5563)
                            isCurveActive -> KeyflowBlueBright
                            else -> Color(0xFFD5DCE8)
                        },
                        modifier = Modifier.size(21.dp)
                    )
                }

                Spacer(modifier = Modifier.weight(1f))

                // 4. Bottom ⋮ Overflow Menu (Copy/Paste FX, Plugin Manager, Motion Blur, Clear All)
                Box {
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .clickable { railOverflowOpen = true },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.MoreVert,
                            contentDescription = "Effects Options",
                            tint = Color(0xFFD5DCE8),
                            modifier = Modifier.size(20.dp)
                        )
                    }

                    DropdownMenu(
                        expanded = railOverflowOpen,
                        onDismissRequest = { railOverflowOpen = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text("Plugin Packs (.kfpack)", color = KeyflowBlueBright, fontWeight = FontWeight.SemiBold) },
                            leadingIcon = { Icon(Icons.Default.Extension, contentDescription = null, tint = KeyflowBlueBright) },
                            onClick = {
                                railOverflowOpen = false
                                onNavigateTool(StudioDestination.PLUGIN_MANAGER)
                            },
                            modifier = Modifier.testTag("inspector_open_plugins_button")
                        )
                        DropdownMenuItem(
                            text = { Text("Copy Effects") },
                            leadingIcon = { Icon(Icons.Default.ContentCopy, contentDescription = null) },
                            enabled = layer.effects.isNotEmpty(),
                            onClick = {
                                railOverflowOpen = false
                                onCopyEffects()
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Paste Effects") },
                            leadingIcon = { Icon(Icons.Default.ContentPaste, contentDescription = null) },
                            enabled = hasCopiedEffects,
                            onClick = {
                                railOverflowOpen = false
                                onPasteEffects()
                            }
                        )
                        DropdownMenuItem(
                            text = { Text(if (layer.motionBlurEnabled) "Disable Motion Blur" else "Enable Motion Blur") },
                            onClick = {
                                railOverflowOpen = false
                                onToggleMotionBlur(!layer.motionBlurEnabled, layer.shutterAngle)
                            }
                        )
                        if (layer.effects.isNotEmpty() && onClearAllEffects != null) {
                            HorizontalDivider(color = OutlineSubtle)
                            DropdownMenuItem(
                                text = { Text("Clear All Effects", color = KeyflowDangerRed, fontWeight = FontWeight.Bold) },
                                leadingIcon = { Icon(Icons.Default.DeleteOutline, contentDescription = null, tint = KeyflowDangerRed) },
                                onClick = {
                                    railOverflowOpen = false
                                    onClearAllEffects()
                                },
                                modifier = Modifier.testTag("clear_all_layer_effects_button")
                            )
                        }
                    }
                }
            }

            // Vertical Rail Hairline Divider
            Box(
                modifier = Modifier
                    .width(0.8.dp)
                    .fillMaxHeight()
                    .background(KeyflowRailDivider)
            )

            // RIGHT CONTENT AREA
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .background(KeyflowDrawerBg)
            ) {
                if (activeTab == AmInspectorTab.EFFECT_EASING && activeExpandedFx != null) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = 12.dp, vertical = 8.dp)
                    ) {
                        AmEffectInlineCurvePanel(
                            layer = layer,
                            effect = activeExpandedFx,
                            selectedSlot = selectedSlot,
                            selectedPluginParamKey = selectedPluginParamKey,
                            playheadMs = playheadMs,
                            hasCopiedCurve = hasCopiedCurve,
                            onSelectSlot = onSelectSlot,
                            onSelectPluginParamKey = onSelectPluginParamKey,
                            onUpdateEffectBezierHandles = onUpdateEffectBezierHandles,
                            onCopyCurve = onCopyCurve,
                            onPasteCurve = onPasteCurve,
                            onDragEffectKeyframeNode = onDragEffectKeyframeNode,
                            onScrubPlayhead = onScrubPlayhead,
                            onReturnToEffectDetail = { onSetInspectorTab(AmInspectorTab.EFFECT_DETAIL) },
                            onOpenFullGraph = onOpenFullGraph
                        )
                    }
                } else if (layer.effects.isEmpty()) {
                    // SCREENSHOT 2: Empty Effects State
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Text(
                            text = "No effects.",
                            fontSize = 14.sp,
                            color = Color(0xFFB5BCC9),
                            fontWeight = FontWeight.Normal
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "Tap + to add.",
                            fontSize = 14.sp,
                            color = Color(0xFF9CA4B4),
                            fontWeight = FontWeight.Normal
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        KeyflowAddEffectButton(
                            onClick = { onNavigateTool(StudioDestination.EFFECTS_BROWSER) }
                        )
                    }
                } else {
                    // SCREENSHOTS 3 & 4: Collapsible Effect Accordion Cards + Inline Property Ruler Cards
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = 10.dp, vertical = 10.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        layer.effects.forEachIndexed { idx, fx ->
                            val isExpanded = activeTab == AmInspectorTab.EFFECT_DETAIL && fx.id == activeExpandedFx?.id
                            KeyflowEffectAccordionItem(
                                layer = layer,
                                fx = fx,
                                index = idx,
                                totalCount = layer.effects.size,
                                isExpanded = isExpanded,
                                selectedSlot = selectedSlot,
                                selectedPluginParamKey = selectedPluginParamKey,
                                playheadMs = playheadMs,
                                audioSpectrum = audioSpectrum,
                                onToggleExpand = {
                                    if (isExpanded) {
                                        onSetInspectorTab(AmInspectorTab.EFFECTS)
                                    } else {
                                        onSelectEffectForInspection(fx.id)
                                    }
                                },
                                onSelectSlot = { slot ->
                                    if (!isExpanded) onSelectEffectForInspection(fx.id)
                                    onSelectSlot(slot)
                                },
                                onSelectPluginParamKey = { key ->
                                    if (!isExpanded) onSelectEffectForInspection(fx.id)
                                    onSelectPluginParamKey(key)
                                },
                                onUpdateSlotValue = onUpdateSlotValue,
                                onUpdateCustomPluginParamValue = onUpdateCustomPluginParamValue,
                                onUpdateCustomPluginColor = onUpdateCustomPluginColor,
                                onUpdateSlotExpression = onUpdateSlotExpression,
                                onUpdateEffectNode = onUpdateEffectNode,
                                onMoveEffectOrder = { up -> onMoveEffectOrder(fx.id, up) },
                                onDuplicateEffect = { onDuplicateEffect(fx.id) },
                                onResetEffect = { onResetEffect(fx.id) },
                                onRemoveEffect = { onRemoveEffectNode(fx.id) },
                                onRequestEyedropper = onRequestEyedropper,
                                onRequestPluginColorEyedropper = onRequestPluginColorEyedropper
                            )
                        }

                        Spacer(modifier = Modifier.height(6.dp))
                        KeyflowAddEffectButton(
                            onClick = { onNavigateTool(StudioDestination.EFFECTS_BROWSER) }
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun KeyflowAddEffectButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .width(228.dp)
            .height(42.dp)
            .clip(RoundedCornerShape(11.dp))
            .background(KeyflowBlue)
            .clickable(onClick = onClick)
            .testTag("open_effects_browser_cta")
            .padding(horizontal = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = Icons.Default.Add,
            contentDescription = "Add Effect",
            tint = Color(0xFF060912),
            modifier = Modifier.size(20.dp)
        )
        Spacer(modifier = Modifier.width(10.dp))
        Text(
            text = "Add Effect",
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            color = Color(0xFF060912)
        )
    }
}

@Composable
private fun KeyflowEffectAccordionItem(
    layer: Layer,
    fx: EffectNode,
    index: Int,
    totalCount: Int,
    isExpanded: Boolean,
    selectedSlot: EffectParamSlot,
    selectedPluginParamKey: String?,
    playheadMs: Long,
    audioSpectrum: AudioSpectrumSnapshot,
    onToggleExpand: () -> Unit,
    onSelectSlot: (EffectParamSlot) -> Unit,
    onSelectPluginParamKey: (String) -> Unit,
    onUpdateSlotValue: (Float) -> Unit,
    onUpdateCustomPluginParamValue: (String, Float) -> Unit,
    onUpdateCustomPluginColor: (String, String) -> Unit,
    onUpdateSlotExpression: (String) -> Unit,
    onUpdateEffectNode: (EffectNode) -> Unit,
    onMoveEffectOrder: (Boolean) -> Unit,
    onDuplicateEffect: () -> Unit,
    onResetEffect: () -> Unit,
    onRemoveEffect: () -> Unit,
    onRequestEyedropper: (EyedropperColorSlot) -> Unit,
    onRequestPluginColorEyedropper: (String) -> Unit
) {
    var itemMenuOpen by remember { mutableStateOf(false) }
    val fxKfCount = remember(fx) { fx.allKeyframeTimes().size }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        // 1. KeyFlow Rounded Dark Effect Header Card (`=` | Name | Eye | `⌄`/`⌃` | Red Trash | `⋮`)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(KeyflowCardSurface)
                .border(
                    width = if (isExpanded) 1.dp else 0.8.dp,
                    color = if (isExpanded) KeyflowBlue.copy(alpha = 0.45f) else KeyflowCardBorder,
                    shape = RoundedCornerShape(14.dp)
                )
                .clickable(onClick = onToggleExpand)
                .testTag("layer_effect_item_${fx.id}")
                .padding(start = 12.dp, end = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(
                imageVector = Icons.Default.DragHandle,
                contentDescription = "Reorder Effect",
                tint = Color(0xFF7D8594),
                modifier = Modifier
                    .size(18.dp)
                    .clickable(enabled = totalCount > 1) {
                        onMoveEffectOrder(index > 0)
                    }
            )

            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    text = fx.displayName,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
                if (fxKfCount > 0) {
                    Text(
                        text = "◆$fxKfCount",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = KeyflowBlueBright
                    )
                }
            }

            // Eye Visibility Toggle (KeyFlow Royal Blue when enabled)
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .clip(CircleShape)
                    .clickable { onUpdateEffectNode(fx.copy(enabled = !fx.enabled)) },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = if (fx.enabled) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                    contentDescription = "Toggle Effect Visibility",
                    tint = if (fx.enabled) KeyflowBlue else Color(0xFF687080),
                    modifier = Modifier.size(19.dp)
                )
            }

            // Expand / Collapse Chevron
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .clickable(onClick = onToggleExpand),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = if (isExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                    contentDescription = if (isExpanded) "Collapse Effect" else "Expand Effect",
                    tint = Color(0xFFE4E8F0),
                    modifier = Modifier.size(20.dp)
                )
            }

            // Red Trash Delete Icon
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .clip(CircleShape)
                    .clickable(onClick = onRemoveEffect)
                    .testTag("delete_effect_row_${fx.id}"),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Delete,
                    contentDescription = "Delete Effect",
                    tint = KeyflowDangerRed,
                    modifier = Modifier.size(18.dp)
                )
            }

            // ⋮ Effect Card Menu (Duplicate, Reset, Move Up/Down, Audio Reactor)
            Box {
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .clickable { itemMenuOpen = true },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.MoreVert,
                        contentDescription = "Effect Menu",
                        tint = Color(0xFFD5DCE8),
                        modifier = Modifier.size(18.dp)
                    )
                }

                DropdownMenu(
                    expanded = itemMenuOpen,
                    onDismissRequest = { itemMenuOpen = false }
                ) {
                    DropdownMenuItem(
                        text = { Text("Duplicate") },
                        leadingIcon = { Icon(Icons.Default.ContentCopy, contentDescription = null) },
                        onClick = {
                            itemMenuOpen = false
                            onDuplicateEffect()
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Reset Parameters") },
                        leadingIcon = { Icon(Icons.Default.Refresh, contentDescription = null) },
                        onClick = {
                            itemMenuOpen = false
                            onResetEffect()
                        }
                    )
                    if (totalCount > 1) {
                        DropdownMenuItem(
                            text = { Text("Move Up") },
                            enabled = index > 0,
                            onClick = {
                                itemMenuOpen = false
                                onMoveEffectOrder(true)
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Move Down") },
                            enabled = index < totalCount - 1,
                            onClick = {
                                itemMenuOpen = false
                                onMoveEffectOrder(false)
                            }
                        )
                    }
                    HorizontalDivider(color = OutlineSubtle)
                    AudioBand.entries.forEach { band ->
                        val activeBand = fx.audioBoundBand == band
                        DropdownMenuItem(
                            text = {
                                Text(
                                    text = if (band == AudioBand.NONE) "Audio Reactor: Off" else "Audio Reactor: ${band.label}",
                                    color = if (activeBand) KeyflowBlueBright else TextPrimary
                                )
                            },
                            onClick = {
                                itemMenuOpen = false
                                onUpdateEffectNode(fx.copy(audioBoundBand = band))
                            }
                        )
                    }
                    HorizontalDivider(color = OutlineSubtle)
                    DropdownMenuItem(
                        text = { Text("Delete Effect", color = KeyflowDangerRed, fontWeight = FontWeight.Bold) },
                        onClick = {
                            itemMenuOpen = false
                            onRemoveEffect()
                        },
                        modifier = Modifier.testTag("delete_effect_detail_button")
                    )
                }
            }
        }

        // 2. INLINE EXPANDED PROPERTY RULER CARDS (Screenshot 4!)
        if (isExpanded) {
            val pluginDef = remember(fx.effectId) { PluginPackManager.findPluginEffect(fx.effectId) }
            val builtInDef = remember(fx.effectId) { EffectsCatalog.findById(fx.effectId) }

            if (pluginDef != null) {
                val numericParams = remember(pluginDef) {
                    pluginDef.parameters.filter { it.type != PluginParamType.COLOR }
                }
                val colorParams = remember(pluginDef) {
                    pluginDef.parameters.filter { it.type == PluginParamType.COLOR }
                }
                val resolvedKey = remember(numericParams, selectedPluginParamKey) {
                    when {
                        selectedPluginParamKey == PLUGIN_MIX_KEY -> PLUGIN_MIX_KEY
                        selectedPluginParamKey != null && numericParams.any { it.key == selectedPluginParamKey } -> selectedPluginParamKey
                        numericParams.isNotEmpty() -> numericParams.first().key
                        else -> PLUGIN_MIX_KEY
                    }
                }
                var expandedColorKey by remember(fx.id) { mutableStateOf<String?>(null) }

                pluginDef.parameters.forEach { param ->
                    when (param.type) {
                        PluginParamType.FLOAT -> {
                            val prop = fx.customParamFor(param.key, param.defaultFloat)
                            val liveVal = AnimationEvaluator.evaluateProperty(prop, playheadMs, audioSpectrum)
                            val isSel = resolvedKey == param.key
                            val hasKfHere = prop.keyframes.any { abs(it.timeMs - playheadMs) <= 35L }
                            KeyflowPropertyRulerCard(
                                label = param.label,
                                value = liveVal,
                                minValue = param.min,
                                maxValue = param.max,
                                selected = isSel,
                                keyframeCount = prop.keyframes.size,
                                hasKeyframeAtPlayhead = hasKfHere,
                                onSelect = { onSelectPluginParamKey(param.key) },
                                onValueChange = { next ->
                                    if (!isSel) onSelectPluginParamKey(param.key)
                                    onUpdateCustomPluginParamValue(param.key, next)
                                },
                                labelTestTag = "plugin_param_chip_${param.key}",
                                scrubberTestTag = "plugin_param_ruler_${param.key}"
                            )
                        }

                        PluginParamType.BOOL -> {
                            val prop = fx.customParamFor(param.key, param.defaultFloat)
                            val liveVal = AnimationEvaluator.evaluateProperty(prop, playheadMs, audioSpectrum)
                            val isSel = resolvedKey == param.key
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(52.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(KeyflowCardSurface)
                                    .border(
                                        width = if (isSel) 1.1.dp else 0.8.dp,
                                        color = if (isSel) KeyflowBlue.copy(alpha = 0.55f) else KeyflowCardBorder,
                                        shape = RoundedCornerShape(12.dp)
                                    )
                                    .clickable { onSelectPluginParamKey(param.key) }
                                    .padding(horizontal = 14.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = param.label,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = if (isSel) FontWeight.SemiBold else FontWeight.Medium,
                                    color = if (isSel) KeyflowBlueBright else TextPrimary
                                )
                                Switch(
                                    checked = liveVal >= 0.5f,
                                    onCheckedChange = { checked ->
                                        onSelectPluginParamKey(param.key)
                                        onUpdateCustomPluginParamValue(param.key, if (checked) 1f else 0f)
                                    },
                                    colors = SwitchDefaults.colors(
                                        checkedThumbColor = Color(0xFF060912),
                                        checkedTrackColor = KeyflowBlue
                                    ),
                                    modifier = Modifier.testTag("plugin_bool_switch_${param.key}")
                                )
                            }
                        }

                        PluginParamType.COLOR -> {
                            val currentHex = fx.customColorFor(param.key, param.defaultColorHex)
                            val isWheelOpen = expandedColorKey == param.key
                            KeyflowColorPropertyCard(
                                label = param.label,
                                currentHex = currentHex,
                                isWheelOpen = isWheelOpen,
                                onToggleWheel = {
                                    expandedColorKey = if (isWheelOpen) null else param.key
                                },
                                onPickSwatch = { hex -> onUpdateCustomPluginColor(param.key, hex) },
                                onEyedropper = { onRequestPluginColorEyedropper(param.key) },
                                eyedropperTag = "plugin_color_eyedropper_${param.key}",
                                wheelToggleTag = "plugin_color_wheel_toggle_${param.key}"
                            )
                            if (isWheelOpen) {
                                AmCustomColorAndGradientPicker(
                                    primaryHex = currentHex,
                                    onPrimaryHexChange = { hex -> onUpdateCustomPluginColor(param.key, hex) },
                                    onRequestEyedropper = { onRequestPluginColorEyedropper(param.key) },
                                    primaryEyedropperSlot = EyedropperColorSlot.EFFECT_TINT,
                                    supportsGradient = false,
                                    compact = true,
                                    testTagPrefix = "plugin_color_picker_${param.key}"
                                )
                            }
                        }
                    }
                }

                // Plugin Mix Property Ruler Card
                val mixVal = AnimationEvaluator.evaluateProperty(fx.mixProp, playheadMs, audioSpectrum)
                val isMixSel = resolvedKey == PLUGIN_MIX_KEY
                KeyflowPropertyRulerCard(
                    label = "Mix",
                    value = mixVal,
                    minValue = 0f,
                    maxValue = 100f,
                    unit = "%",
                    selected = isMixSel,
                    keyframeCount = fx.mixProp.keyframes.size,
                    hasKeyframeAtPlayhead = fx.mixProp.keyframes.any { abs(it.timeMs - playheadMs) <= 35L },
                    onSelect = { onSelectPluginParamKey(PLUGIN_MIX_KEY) },
                    onValueChange = { next ->
                        if (!isMixSel) onSelectPluginParamKey(PLUGIN_MIX_KEY)
                        onUpdateSlotValue(next)
                    }
                )
            } else {
                // Built-in 5-slot Effect Parameters rendered as individual KeyFlow Property Ruler Cards!
                val specs = remember(builtInDef) { builtInDef.paramSpecs() }
                var showTintWheel by remember(fx.id) {
                    mutableStateOf(fx.effectId == "Chroma_Key" || fx.effectId == "Luma_Key")
                }

                specs.forEach { spec ->
                    val prop = fx.propertyFor(spec.slot)
                    val liveVal = AnimationEvaluator.evaluateProperty(prop, playheadMs, audioSpectrum)
                    val isSel = selectedSlot == spec.slot
                    KeyflowPropertyRulerCard(
                        label = spec.label,
                        value = liveVal,
                        minValue = spec.minVal,
                        maxValue = spec.maxVal,
                        unit = spec.unit,
                        selected = isSel,
                        keyframeCount = prop.keyframes.size,
                        hasKeyframeAtPlayhead = prop.keyframes.any { abs(it.timeMs - playheadMs) <= 35L },
                        onSelect = { onSelectSlot(spec.slot) },
                        onValueChange = { next ->
                            if (!isSel) onSelectSlot(spec.slot)
                            onUpdateSlotValue(next)
                        },
                        labelTestTag = "effect_slot_chip_${spec.slot.name}",
                        scrubberTestTag = if (isSel) "effect_slot_scrubber" else "effect_slot_scrubber_${spec.slot.name}"
                    )
                }

                KeyflowColorPropertyCard(
                    label = builtInDef.colorLabel,
                    currentHex = fx.tintColorHex,
                    isWheelOpen = showTintWheel,
                    onToggleWheel = { showTintWheel = !showTintWheel },
                    onPickSwatch = { hex -> onUpdateEffectNode(fx.copy(tintColorHex = hex)) },
                    onEyedropper = { onRequestEyedropper(EyedropperColorSlot.EFFECT_TINT) },
                    eyedropperTag = "effect_eyedropper_button",
                    wheelToggleTag = "effect_custom_color_toggle"
                )
                if (showTintWheel) {
                    AmCustomColorAndGradientPicker(
                        primaryHex = fx.tintColorHex,
                        onPrimaryHexChange = { hex -> onUpdateEffectNode(fx.copy(tintColorHex = hex)) },
                        onRequestEyedropper = onRequestEyedropper,
                        primaryEyedropperSlot = EyedropperColorSlot.EFFECT_TINT,
                        supportsGradient = false,
                        compact = true,
                        testTagPrefix = "effect_custom_color_picker"
                    )
                }
            }
        }
    }
}

@Composable
private fun KeyflowColorPropertyCard(
    label: String,
    currentHex: String,
    isWheelOpen: Boolean,
    onToggleWheel: () -> Unit,
    onPickSwatch: (String) -> Unit,
    onEyedropper: () -> Unit,
    eyedropperTag: String,
    wheelToggleTag: String
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(52.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(KeyflowCardSurface)
            .border(0.8.dp, KeyflowCardBorder, RoundedCornerShape(12.dp))
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            color = TextPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(0.36f)
        )
        Row(
            modifier = Modifier
                .weight(0.64f)
                .horizontalScroll(rememberScrollState()),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            AmSquareIconButton(
                icon = Icons.Default.Colorize,
                contentDescription = "Sample $label",
                tint = KeyflowBlueBright,
                onClick = onEyedropper,
                sizeDp = 28.dp,
                testTag = eyedropperTag
            )
            AmSquareIconButton(
                icon = Icons.Default.Palette,
                contentDescription = "Color Wheel",
                active = isWheelOpen,
                tint = if (isWheelOpen) KeyflowBlueBright else TextPrimary,
                onClick = onToggleWheel,
                sizeDp = 28.dp,
                testTag = wheelToggleTag
            )
            FX_TINT_SWATCHES.forEach { hex ->
                val c = remember(hex) {
                    runCatching { Color(android.graphics.Color.parseColor(hex)) }.getOrDefault(Color.White)
                }
                val isSel = currentHex.equals(hex, ignoreCase = true)
                Box(
                    modifier = Modifier
                        .size(22.dp)
                        .clip(CircleShape)
                        .background(c)
                        .border(
                            if (isSel) 2.2.dp else 1.dp,
                            if (isSel) KeyflowBlueBright else OutlineSubtle,
                            CircleShape
                        )
                        .clickable { onPickSwatch(hex) }
                )
            }
        }
    }
}

