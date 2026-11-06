package com.example.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesomeMotion
import androidx.compose.material.icons.filled.Diamond
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Widgets
import androidx.compose.material.icons.outlined.Diamond
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.sp
import com.example.data.model.AspectRatioPreset
import com.example.ui.theme.AmTeal
import com.example.ui.theme.AmTealBright
import com.example.ui.theme.AmTealContainer
import com.example.ui.theme.DeepBlack
import com.example.ui.theme.KeyflowBlue
import com.example.ui.theme.KeyflowBlueBright
import com.example.ui.theme.KeyflowCardBorder
import com.example.ui.theme.KeyflowCardSurface
import com.example.ui.theme.KeyframeAmber
import com.example.ui.theme.OutlineSubtle
import com.example.ui.theme.PanelDark
import com.example.ui.theme.PanelElevated
import com.example.ui.theme.SurfaceDark
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Alight Motion signature rounded-square action tile button:
 * Expressive dark surface, crisp icon + clean subtitle, lighting up in Alight Motion Teal (#00D2A8).
 */
@Composable
fun AmActionTileButton(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    accentColor: Color = AmTeal,
    badgeText: String? = null,
    testTag: String? = null
) {
    val bg = if (selected) accentColor.copy(alpha = 0.16f) else PanelDark
    val border = if (selected) accentColor.copy(alpha = 0.85f) else OutlineSubtle
    val contentTint = if (selected) accentColor else TextPrimary

    Box(
        modifier = modifier
            .height(56.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(bg)
            .border(1.dp, border, RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .then(if (testTag != null) Modifier.testTag(testTag) else Modifier)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Box {
                Icon(
                    imageVector = icon,
                    contentDescription = label,
                    tint = contentTint,
                    modifier = Modifier.size(20.dp)
                )
                if (!badgeText.isNullOrBlank()) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .offset(x = 8.dp, y = (-4).dp)
                            .clip(CircleShape)
                            .background(accentColor)
                            .padding(horizontal = 4.dp, vertical = 1.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = badgeText,
                            style = MaterialTheme.typography.labelSmall,
                            color = DeepBlack,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(3.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = if (selected) accentColor else TextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center
            )
        }
    }
}

/**
 * Clean Alight Motion icon button for Top Bars, Transport Bars, and Inspector headers.
 * Uses subtle ghost styling by default so toolbars never look boxy or squeezed,
 * lighting up with a soft pill only when [active] or [outlined] is true.
 */
@Composable
fun AmSquareIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    active: Boolean = false,
    outlined: Boolean = false,
    tint: Color = TextPrimary,
    sizeDp: Dp = 38.dp,
    testTag: String? = null
) {
    val bg = when {
        active -> AmTeal.copy(alpha = 0.20f)
        outlined -> PanelDark
        else -> Color.Transparent
    }
    val stroke = when {
        active -> AmTeal.copy(alpha = 0.75f)
        outlined -> OutlineSubtle
        else -> Color.Transparent
    }
    val effectiveTint = when {
        !enabled -> TextMuted.copy(alpha = 0.38f)
        active -> AmTealBright
        else -> tint
    }

    Box(
        modifier = modifier
            .size(sizeDp.coerceAtLeast(34.dp))
            .clip(RoundedCornerShape(9.dp))
            .background(bg)
            .then(
                if (stroke != Color.Transparent) {
                    Modifier.border(1.dp, stroke, RoundedCornerShape(9.dp))
                } else {
                    Modifier
                }
            )
            .clickable(enabled = enabled, onClick = onClick)
            .then(if (testTag != null) Modifier.testTag(testTag) else Modifier),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = effectiveTint,
            modifier = Modifier.size(19.dp)
        )
    }
}

/**
 * Alight Motion Segmented Pill Button (Resolution, FPS, Codec, Presets).
 */
@Composable
fun AmSegmentChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    testTag: String? = null
) {
    val bg = if (selected) AmTeal else PanelDark
    val fg = if (selected) DeepBlack else TextSecondary
    val stroke = if (selected) AmTealBright else OutlineSubtle

    Box(
        modifier = modifier
            .height(if (compact) 30.dp else 36.dp)
            .clip(RoundedCornerShape(9.dp))
            .background(bg)
            .border(1.dp, stroke, RoundedCornerShape(9.dp))
            .clickable(onClick = onClick)
            .then(if (testTag != null) Modifier.testTag(testTag) else Modifier)
            .padding(horizontal = if (compact) 10.dp else 13.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            style = if (compact) MaterialTheme.typography.labelSmall else MaterialTheme.typography.labelMedium,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            color = fg,
            maxLines = 1
        )
    }
}

/**
 * Alight Motion Primary Teal CTA Button ("CREATE PROJECT", "EXPORT", "+ ADD EFFECT").
 */
@Composable
fun AmPrimaryCtaButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    compact: Boolean = false,
    testTag: String? = null
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(10.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = AmTeal,
            contentColor = DeepBlack,
            disabledContainerColor = PanelElevated,
            disabledContentColor = TextMuted
        ),
        contentPadding = PaddingValues(
            horizontal = if (compact) 12.dp else 18.dp,
            vertical = if (compact) 8.dp else 11.dp
        ),
        modifier = modifier
            .height(if (compact) 40.dp else 46.dp)
            .then(if (testTag != null) Modifier.testTag(testTag) else Modifier)
    ) {
        if (icon != null) {
            Icon(imageVector = icon, contentDescription = null, modifier = Modifier.size(if (compact) 16.dp else 18.dp))
            Spacer(modifier = Modifier.width(6.dp))
        }
        Text(
            text = text,
            style = if (compact) MaterialTheme.typography.labelMedium else MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
            maxLines = 1
        )
    }
}

/**
 * Alight Motion Right-Side Vertical Sub-Tool Rail Item (Move, Pivot, Rotate, Scale, 3D/Z).
 * Scaled cleanly so 5-item vertical rails fit comfortably with expressive active accent bars.
 */
@Composable
fun AmSubToolRailButton(
    icon: ImageVector,
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    testTag: String? = null
) {
    Row(
        modifier = modifier
            .width(48.dp)
            .height(40.dp)
            .clip(RoundedCornerShape(9.dp))
            .background(if (selected) AmTealContainer else PanelDark)
            .border(
                width = 1.dp,
                color = if (selected) AmTeal else OutlineSubtle,
                shape = RoundedCornerShape(9.dp)
            )
            .clickable(onClick = onClick)
            .then(if (testTag != null) Modifier.testTag(testTag) else Modifier),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .width(3.dp)
                .fillMaxHeight()
                .background(if (selected) AmTealBright else Color.Transparent)
        )
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = if (selected) AmTealBright else TextSecondary,
                modifier = Modifier.size(16.dp)
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = if (selected) AmTealBright else TextMuted,
                maxLines = 1
            )
        }
    }
}

/**
 * Alight Motion's iconic Keyframe Cluster: ⟨ Prev KF | ◆+ Toggle Keyframe | Next KF ⟩
 * Compactly proportioned (102.dp total width) so adjacent scrubbers & touchpads stay wide and airy.
 */
@Composable
fun AmKeyframeCluster(
    hasKeyframeAtPlayhead: Boolean,
    keyframeCount: Int,
    onPrevKeyframe: () -> Unit,
    onToggleKeyframe: () -> Unit,
    onNextKeyframe: () -> Unit,
    modifier: Modifier = Modifier,
    diamondTestTag: String = "keyframe_diamond_toggle"
) {
    Row(
        modifier = modifier
            .height(38.dp)
            .clip(RoundedCornerShape(9.dp))
            .background(PanelDark)
            .border(1.dp, OutlineSubtle, RoundedCornerShape(9.dp)),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(30.dp, 38.dp)
                .clickable(enabled = keyframeCount > 0, onClick = onPrevKeyframe),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                contentDescription = "Previous keyframe",
                tint = if (keyframeCount > 0) TextPrimary else TextMuted.copy(alpha = 0.35f),
                modifier = Modifier.size(18.dp)
            )
        }

        Box(
            modifier = Modifier
                .width(1.dp)
                .fillMaxHeight(0.6f)
                .background(OutlineSubtle)
        )

        Box(
            modifier = Modifier
                .size(40.dp, 38.dp)
                .background(if (hasKeyframeAtPlayhead) KeyframeAmber.copy(alpha = 0.18f) else Color.Transparent)
                .clickable(onClick = onToggleKeyframe)
                .testTag(diamondTestTag),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = if (hasKeyframeAtPlayhead) Icons.Filled.Diamond else Icons.Outlined.Diamond,
                contentDescription = "Toggle keyframe diamond",
                tint = if (hasKeyframeAtPlayhead) KeyframeAmber else AmTealBright,
                modifier = Modifier.size(18.dp)
            )
        }

        Box(
            modifier = Modifier
                .width(1.dp)
                .fillMaxHeight(0.6f)
                .background(OutlineSubtle)
        )

        Box(
            modifier = Modifier
                .size(30.dp, 38.dp)
                .clickable(enabled = keyframeCount > 0, onClick = onNextKeyframe),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = "Next keyframe",
                tint = if (keyframeCount > 0) TextPrimary else TextMuted.copy(alpha = 0.35f),
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

/**
 * Clean Left-to-Right Studio Horizontal Slider (`left = min`, `right = max`):
 * Replaces the confusing center-tick wheel with an unmistakable Left-to-Right track,
 * KeyFlow Royal Blue progress fill, tappable exact value capsule, fine +/- nudge,
 * dynamic unbounded expansion (beyond -1500 / +1500), and optional single-property reset.
 */
@Composable
fun AmTickWheelScrubber(
    value: Float,
    minValue: Float,
    maxValue: Float,
    unit: String,
    label: String,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    fineStep: Float = 0f,
    unbounded: Boolean = false,
    onReset: (() -> Unit)? = null,
    testTag: String = "property_value_slider"
) {
    val currentValState by rememberUpdatedState(value)
    val currentOnValueChange by rememberUpdatedState(onValueChange)

    val effectiveMin = remember(minValue, value, unbounded) {
        if (unbounded && value < minValue) {
            floor(value / 500f) * 500f - 500f
        } else {
            minOf(minValue, value)
        }
    }
    val effectiveMax = remember(maxValue, value, unbounded) {
        if (unbounded && value > maxValue) {
            kotlin.math.ceil(value / 500f) * 500f + 500f
        } else {
            maxOf(maxValue, value)
        }
    }
    val span = (effectiveMax - effectiveMin).coerceAtLeast(0.001f)
    val formattedVal = remember(value, span) {
        when {
            abs(value) < 0.0005f -> "0"
            span <= 6f -> String.format("%.2f", value)
            else -> String.format("%.1f", value).trimEnd('0').trimEnd('.')
        }
    }

    var showExactDialog by remember { mutableStateOf(false) }
    var exactDraft by remember(value) { mutableStateOf(formattedVal) }

    if (showExactDialog) {
        AlertDialog(
            onDismissRequest = { showExactDialog = false },
            containerColor = SurfaceDark,
            title = {
                Text(
                    text = label,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary
                )
            },
            text = {
                OutlinedTextField(
                    value = exactDraft,
                    onValueChange = { exactDraft = it },
                    singleLine = true,
                    label = {
                        Text(
                            if (unbounded) "Any value (unbounded)"
                            else "${formatRulerTick(minValue, span / 5f)} .. ${formatRulerTick(maxValue, span / 5f)}"
                        )
                    },
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        exactDraft.trim().toFloatOrNull()?.let { parsed ->
                            val clamped = if (unbounded) parsed.coerceIn(-100_000f, 100_000f)
                            else parsed.coerceIn(minValue, maxValue)
                            currentOnValueChange(clamped)
                        }
                        showExactDialog = false
                    }
                ) {
                    Text("Apply", color = KeyflowBlueBright, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showExactDialog = false }) {
                    Text("Cancel", color = TextSecondary)
                }
            }
        )
    }

    Column(modifier = modifier) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = TextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false)
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                if (fineStep > 0f) {
                    Box(
                        modifier = Modifier
                            .size(22.dp)
                            .clip(CircleShape)
                            .background(PanelDark)
                            .border(0.8.dp, OutlineSubtle, CircleShape)
                            .clickable {
                                val raw = currentValState - fineStep
                                currentOnValueChange(if (unbounded) raw else raw.coerceIn(minValue, maxValue))
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Text("−", style = MaterialTheme.typography.labelSmall, color = TextPrimary, fontWeight = FontWeight.Bold)
                    }
                }
                Box(
                    modifier = Modifier
                        .height(21.dp)
                        .clip(RoundedCornerShape(7.dp))
                        .background(KeyflowBlue.copy(alpha = 0.22f))
                        .border(0.8.dp, KeyflowBlue.copy(alpha = 0.65f), RoundedCornerShape(7.dp))
                        .clickable {
                            exactDraft = formattedVal
                            showExactDialog = true
                        }
                        .padding(horizontal = 8.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "$formattedVal$unit",
                        style = MaterialTheme.typography.labelSmall,
                        color = KeyflowBlueBright,
                        fontWeight = FontWeight.Bold
                    )
                }
                if (fineStep > 0f) {
                    Box(
                        modifier = Modifier
                            .size(22.dp)
                            .clip(CircleShape)
                            .background(PanelDark)
                            .border(0.8.dp, OutlineSubtle, CircleShape)
                            .clickable {
                                val raw = currentValState + fineStep
                                currentOnValueChange(if (unbounded) raw else raw.coerceIn(minValue, maxValue))
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Text("+", style = MaterialTheme.typography.labelSmall, color = TextPrimary, fontWeight = FontWeight.Bold)
                    }
                }
                if (onReset != null) {
                    Box(
                        modifier = Modifier
                            .size(22.dp)
                            .clip(CircleShape)
                            .background(PanelDark)
                            .border(0.8.dp, OutlineSubtle, CircleShape)
                            .clickable(onClick = onReset),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = "Reset $label",
                            tint = TextSecondary,
                            modifier = Modifier.size(13.dp)
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(5.dp))

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(34.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(KeyflowCardSurface)
                .border(0.9.dp, KeyflowCardBorder, RoundedCornerShape(10.dp))
                .pointerInput(effectiveMin, effectiveMax, unbounded) {
                    detectTapGestures { offset ->
                        val pad = 12.dp.toPx()
                        val usableW = (size.width - pad * 2f).coerceAtLeast(1f)
                        val frac = ((offset.x - pad) / usableW).coerceIn(0f, 1f)
                        val target = effectiveMin + frac * (effectiveMax - effectiveMin)
                        currentOnValueChange(if (unbounded) target else target.coerceIn(minValue, maxValue))
                    }
                }
                .pointerInput(minValue, maxValue, unbounded) {
                    var dragStartMin = effectiveMin
                    var dragStartSpan = span
                    detectHorizontalDragGestures(
                        onDragStart = {
                            dragStartMin = effectiveMin
                            dragStartSpan = (effectiveMax - effectiveMin).coerceAtLeast(0.001f)
                        },
                        onHorizontalDrag = { change, dragAmount ->
                            change.consume()
                            val pad = 12.dp.toPx()
                            val usableW = (size.width - pad * 2f).coerceAtLeast(1f)
                            if (unbounded) {
                                // Smooth continuous Left-to-Right delta so dragging beyond -1500/+1500 never hits a wall!
                                val delta = (dragAmount / usableW) * dragStartSpan
                                currentOnValueChange((currentValState + delta).coerceIn(-100_000f, 100_000f))
                            } else {
                                val frac = ((change.position.x - pad) / usableW).coerceIn(0f, 1f)
                                val next = (dragStartMin + frac * dragStartSpan).coerceIn(minValue, maxValue)
                                currentOnValueChange(next)
                            }
                        }
                    )
                }
                .testTag(testTag)
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val pad = 12.dp.toPx()
                val usableW = (size.width - pad * 2f).coerceAtLeast(1f)
                val cy = size.height * 0.5f
                val trackH = 5.dp.toPx()
                val norm = ((value - effectiveMin) / span).coerceIn(0f, 1f)
                val thumbX = pad + usableW * norm

                // Inactive track (Left to Right)
                drawRoundRect(
                    color = PanelElevated,
                    topLeft = Offset(pad, cy - trackH * 0.5f),
                    size = Size(usableW, trackH),
                    cornerRadius = CornerRadius(trackH * 0.5f, trackH * 0.5f)
                )

                // Zero reference tick when range crosses 0
                if (effectiveMin < 0f && effectiveMax > 0f) {
                    val zeroX = pad + usableW * ((0f - effectiveMin) / span).coerceIn(0f, 1f)
                    drawLine(
                        color = TextMuted.copy(alpha = 0.55f),
                        start = Offset(zeroX, cy - 6.dp.toPx()),
                        end = Offset(zeroX, cy + 6.dp.toPx()),
                        strokeWidth = 1.4.dp.toPx()
                    )
                }

                // Active Left-to-Right KeyFlow Blue Fill
                val fillW = (thumbX - pad).coerceAtLeast(0f)
                if (fillW > 0.5f) {
                    drawRoundRect(
                        brush = Brush.horizontalGradient(
                            colors = listOf(KeyflowBlue, KeyflowBlueBright),
                            startX = pad,
                            endX = thumbX
                        ),
                        topLeft = Offset(pad, cy - trackH * 0.5f),
                        size = Size(fillW, trackH),
                        cornerRadius = CornerRadius(trackH * 0.5f, trackH * 0.5f)
                    )
                }

                // Crisp circular thumb with Royal Blue halo
                drawCircle(
                    color = KeyflowBlue.copy(alpha = 0.28f),
                    radius = 9.5.dp.toPx(),
                    center = Offset(thumbX, cy)
                )
                drawCircle(
                    color = Color.White,
                    radius = 6.2.dp.toPx(),
                    center = Offset(thumbX, cy)
                )
                drawCircle(
                    color = KeyflowBlue,
                    radius = 3.2.dp.toPx(),
                    center = Offset(thumbX, cy)
                )
            }
        }
    }
}

/**
 * Alight Motion "New Project" Proportional Aspect Ratio Wireframe Card.
 */
@Composable
fun AmRatioOutlineCard(
    preset: AspectRatioPreset,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    testTag: String? = null
) {
    val strokeColor = if (selected) AmTealBright else TextSecondary
    val bg = if (selected) AmTeal.copy(alpha = 0.14f) else PanelDark
    val border = if (selected) AmTeal else OutlineSubtle

    Column(
        modifier = modifier
            .width(68.dp)
            .height(76.dp)
            .clip(RoundedCornerShape(11.dp))
            .background(bg)
            .border(1.4.dp, border, RoundedCornerShape(11.dp))
            .clickable(onClick = onClick)
            .then(if (testTag != null) Modifier.testTag(testTag) else Modifier)
            .padding(6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(38.dp),
            contentAlignment = Alignment.Center
        ) {
            Canvas(modifier = Modifier.size(34.dp)) {
                val ratio = (preset.defaultWidth.toFloat() / preset.defaultHeight.toFloat()).coerceIn(0.45f, 2.35f)
                val maxSide = size.minDimension * 0.92f
                val rw = if (ratio >= 1f) maxSide else maxSide * ratio
                val rh = if (ratio >= 1f) maxSide / ratio else maxSide
                drawRoundRect(
                    color = strokeColor,
                    topLeft = Offset((size.width - rw) / 2f, (size.height - rh) / 2f),
                    size = Size(rw, rh),
                    cornerRadius = CornerRadius(4f, 4f),
                    style = Stroke(width = if (selected) 3.0f else 2f)
                )
            }
        }
        Text(
            text = preset.label,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            color = if (selected) AmTealBright else TextSecondary,
            maxLines = 1
        )
    }
}

enum class AmHomeTab(val label: String, val icon: ImageVector) {
    HOME("Home", Icons.Default.Home),
    PROJECTS("Projects", Icons.Default.Folder),
    PLUGINS("Plugins", Icons.Default.Extension),
    DEPTH_MAP("Depth Map", Icons.Default.Layers)
}

/**
 * Studio Home Screen Bottom Navigation Bar with Home, Projects, centered '+' Create Project FAB,
 * Plugins (CEP / ScriptUI / ExtendScript), and Depth Map (Depth Anything V2).
 */
@Composable
fun AmBottomNavigationBar(
    selectedTab: AmHomeTab,
    onSelectTab: (AmHomeTab) -> Unit,
    onCreateProjectClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 8.dp,
        modifier = modifier
            .fillMaxWidth()
            .border(0.6.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .height(64.dp)
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            AmBottomNavItem(
                tab = AmHomeTab.HOME,
                selected = selectedTab == AmHomeTab.HOME,
                onClick = { onSelectTab(AmHomeTab.HOME) }
            )

            AmBottomNavItem(
                tab = AmHomeTab.PROJECTS,
                selected = selectedTab == AmHomeTab.PROJECTS,
                onClick = { onSelectTab(AmHomeTab.PROJECTS) }
            )

            Box(
                modifier = Modifier
                    .size(48.dp)
                    .shadow(6.dp, CircleShape)
                    .clip(CircleShape)
                    .background(
                        Brush.linearGradient(
                            colors = listOf(
                                MaterialTheme.colorScheme.primary,
                                MaterialTheme.colorScheme.secondary
                            )
                        )
                    )
                    .clickable(onClick = onCreateProjectClick)
                    .testTag("new_project_fab"),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = "New Project",
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.size(26.dp)
                )
            }

            AmBottomNavItem(
                tab = AmHomeTab.PLUGINS,
                selected = selectedTab == AmHomeTab.PLUGINS,
                onClick = { onSelectTab(AmHomeTab.PLUGINS) }
            )

            AmBottomNavItem(
                tab = AmHomeTab.DEPTH_MAP,
                selected = selectedTab == AmHomeTab.DEPTH_MAP,
                onClick = { onSelectTab(AmHomeTab.DEPTH_MAP) }
            )
        }
    }
}

@Composable
private fun AmBottomNavItem(
    tab: AmHomeTab,
    selected: Boolean,
    onClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .width(64.dp)
            .height(50.dp)
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .testTag("home_tab_${tab.name.lowercase()}"),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(
            modifier = Modifier
                .clip(CircleShape)
                .background(
                    if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)
                    else Color.Transparent
                )
                .padding(horizontal = 10.dp, vertical = 2.dp),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = tab.icon,
                contentDescription = tab.label,
                tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp)
            )
        }
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = tab.label,
            style = MaterialTheme.typography.labelSmall,
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/**
 * KeyFlow Signature Left-to-Right Property Slider Card (KeyFlow by Angkin Modern Style):
 * - Sleek dark `#151923` rounded card (`12.dp` radius)
 * - Top Row: Property label (`KeyflowBlueBright` when selected, crisp white when unselected, keyframe diamond badge)
 *   + Tappable `KeyflowBlue` value capsule (opens exact numeric entry) + optional single-property Reset button!
 * - Bottom Row: Unmistakable Left-to-Right (`min` -> `max`) horizontal slider track with glowing KeyFlow Blue fill,
 *   zero-origin tick mark, and smooth unbounded expansion when `unbounded = true`.
 */
@Composable
fun KeyflowPropertyRulerCard(
    label: String,
    value: Float,
    minValue: Float,
    maxValue: Float,
    unit: String = "",
    selected: Boolean = false,
    keyframeCount: Int = 0,
    hasKeyframeAtPlayhead: Boolean = false,
    onSelect: () -> Unit = {},
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    unbounded: Boolean = false,
    onReset: (() -> Unit)? = null,
    labelTestTag: String? = null,
    scrubberTestTag: String? = null
) {
    val currentValState by rememberUpdatedState(value)
    val currentOnSelect by rememberUpdatedState(onSelect)
    val currentOnValueChange by rememberUpdatedState(onValueChange)

    val effectiveMin = remember(minValue, value, unbounded) {
        if (unbounded && value < minValue) {
            floor(value / 500f) * 500f - 500f
        } else {
            minOf(minValue, value)
        }
    }
    val effectiveMax = remember(maxValue, value, unbounded) {
        if (unbounded && value > maxValue) {
            kotlin.math.ceil(value / 500f) * 500f + 500f
        } else {
            maxOf(maxValue, value)
        }
    }
    val span = (effectiveMax - effectiveMin).coerceAtLeast(0.001f)
    val formattedCapsuleValue = remember(value, span, unit) {
        val base = when {
            abs(value) < 0.0005f -> "0"
            span <= 1.2f -> String.format("%.3f", value)
            span <= 12f -> String.format("%.2f", value)
            span <= 120f -> String.format("%.2f", value).trimEnd('0').trimEnd('.')
            else -> String.format("%.1f", value).trimEnd('0').trimEnd('.')
        }
        if (unit.isEmpty()) base else "$base$unit"
    }

    val majorStep = remember(span) {
        val raw = span / 10f
        val exp = floor(log10(raw.toDouble())).toInt()
        val mag = 10.0.pow(exp.toDouble()).toFloat().coerceAtLeast(0.001f)
        val norm = raw / mag
        val nice = when {
            norm <= 1.4f -> 1f
            norm <= 3.2f -> 2f
            norm <= 7.0f -> 5f
            else -> 10f
        }
        (nice * mag).coerceAtLeast(0.01f)
    }

    var showExactDialog by remember { mutableStateOf(false) }
    var exactDraft by remember(value) { mutableStateOf(formattedCapsuleValue.removeSuffix(unit)) }

    if (showExactDialog) {
        AlertDialog(
            onDismissRequest = { showExactDialog = false },
            containerColor = SurfaceDark,
            title = {
                Text(
                    text = label,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary
                )
            },
            text = {
                OutlinedTextField(
                    value = exactDraft,
                    onValueChange = { exactDraft = it },
                    singleLine = true,
                    label = {
                        Text(
                            if (unbounded) "Any coordinate (unbounded, e.g. -3200 or 4800)"
                            else "${formatRulerTick(minValue, majorStep)} .. ${formatRulerTick(maxValue, majorStep)}"
                        )
                    },
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        exactDraft.trim().toFloatOrNull()?.let { parsed ->
                            currentOnSelect()
                            val applied = if (unbounded) parsed.coerceIn(-100_000f, 100_000f)
                            else parsed.coerceIn(minValue, maxValue)
                            currentOnValueChange(applied)
                        }
                        showExactDialog = false
                    }
                ) {
                    Text("Apply", color = KeyflowBlueBright, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showExactDialog = false }) {
                    Text("Cancel", color = TextSecondary)
                }
            }
        )
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(KeyflowCardSurface)
            .border(
                width = if (selected) 1.1.dp else 0.8.dp,
                color = if (selected) KeyflowBlue.copy(alpha = 0.65f) else KeyflowCardBorder,
                shape = RoundedCornerShape(12.dp)
            )
            .clickable { currentOnSelect() }
            .padding(horizontal = 12.dp, vertical = 7.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        // Top Header: Property Label + Keyframe Diamond + Value Capsule + Single-Property Reset
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                modifier = Modifier
                    .weight(1f)
                    .then(if (labelTestTag != null) Modifier.testTag(labelTestTag) else Modifier),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                    color = if (selected) KeyflowBlueBright else TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
                if (keyframeCount > 0) {
                    Text(
                        text = "◆",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (hasKeyframeAtPlayhead) KeyframeAmber else KeyflowBlueBright
                    )
                }
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Box(
                    modifier = Modifier
                        .height(21.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(if (selected) KeyflowBlue else KeyflowBlue.copy(alpha = 0.24f))
                        .border(
                            width = 0.8.dp,
                            color = if (selected) KeyflowBlueBright else KeyflowBlue.copy(alpha = 0.55f),
                            shape = RoundedCornerShape(10.dp)
                        )
                        .clickable {
                            currentOnSelect()
                            exactDraft = formattedCapsuleValue.removeSuffix(unit)
                            showExactDialog = true
                        }
                        .padding(horizontal = 10.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = formattedCapsuleValue,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (selected) Color(0xFF060912) else KeyflowBlueBright,
                        maxLines = 1
                    )
                }

                if (onReset != null) {
                    Box(
                        modifier = Modifier
                            .size(22.dp)
                            .clip(CircleShape)
                            .background(PanelDark)
                            .border(0.8.dp, OutlineSubtle, CircleShape)
                            .clickable {
                                currentOnSelect()
                                onReset()
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = "Reset $label",
                            tint = TextSecondary,
                            modifier = Modifier.size(13.dp)
                        )
                    }
                }
            }
        }

        // Bottom Left-to-Right Slider Track (left = min, right = max)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(24.dp)
                .pointerInput(effectiveMin, effectiveMax, unbounded) {
                    detectTapGestures { offset ->
                        currentOnSelect()
                        val pad = 10.dp.toPx()
                        val usableW = (size.width - pad * 2f).coerceAtLeast(1f)
                        val frac = ((offset.x - pad) / usableW).coerceIn(0f, 1f)
                        val target = effectiveMin + frac * (effectiveMax - effectiveMin)
                        currentOnValueChange(if (unbounded) target else target.coerceIn(minValue, maxValue))
                    }
                }
                .pointerInput(minValue, maxValue, unbounded) {
                    var dragStartMin = effectiveMin
                    var dragStartSpan = span
                    detectHorizontalDragGestures(
                        onDragStart = {
                            currentOnSelect()
                            dragStartMin = effectiveMin
                            dragStartSpan = (effectiveMax - effectiveMin).coerceAtLeast(0.001f)
                        },
                        onHorizontalDrag = { change, dragAmount ->
                            change.consume()
                            val pad = 10.dp.toPx()
                            val usableW = (size.width - pad * 2f).coerceAtLeast(1f)
                            if (unbounded) {
                                val delta = (dragAmount / usableW) * dragStartSpan
                                currentOnValueChange((currentValState + delta).coerceIn(-100_000f, 100_000f))
                            } else {
                                val frac = ((change.position.x - pad) / usableW).coerceIn(0f, 1f)
                                val next = (dragStartMin + frac * dragStartSpan).coerceIn(minValue, maxValue)
                                currentOnValueChange(next)
                            }
                        }
                    )
                }
                .then(if (scrubberTestTag != null) Modifier.testTag(scrubberTestTag) else Modifier)
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val pad = 10.dp.toPx()
                val usableW = (size.width - pad * 2f).coerceAtLeast(1f)
                val cy = size.height * 0.5f
                val trackH = 4.5.dp.toPx()
                val norm = ((value - effectiveMin) / span).coerceIn(0f, 1f)
                val thumbX = pad + usableW * norm

                // Track background
                drawRoundRect(
                    color = PanelElevated,
                    topLeft = Offset(pad, cy - trackH * 0.5f),
                    size = Size(usableW, trackH),
                    cornerRadius = CornerRadius(trackH * 0.5f, trackH * 0.5f)
                )

                // Subtle 25% / 50% / 75% guide ticks
                for (q in 1..3) {
                    val tx = pad + usableW * (q * 0.25f)
                    drawCircle(
                        color = OutlineSubtle.copy(alpha = 0.7f),
                        radius = 1.3.dp.toPx(),
                        center = Offset(tx, cy)
                    )
                }

                // Zero tick when range spans negative to positive
                if (effectiveMin < 0f && effectiveMax > 0f) {
                    val zeroX = pad + usableW * ((0f - effectiveMin) / span).coerceIn(0f, 1f)
                    drawLine(
                        color = TextSecondary.copy(alpha = 0.65f),
                        start = Offset(zeroX, cy - 5.dp.toPx()),
                        end = Offset(zeroX, cy + 5.dp.toPx()),
                        strokeWidth = 1.4.dp.toPx()
                    )
                }

                // Left-to-Right Active Fill
                val fillW = (thumbX - pad).coerceAtLeast(0f)
                if (fillW > 0.5f) {
                    drawRoundRect(
                        brush = Brush.horizontalGradient(
                            colors = listOf(KeyflowBlue, KeyflowBlueBright),
                            startX = pad,
                            endX = thumbX
                        ),
                        topLeft = Offset(pad, cy - trackH * 0.5f),
                        size = Size(fillW, trackH),
                        cornerRadius = CornerRadius(trackH * 0.5f, trackH * 0.5f)
                    )
                }

                // Thumb
                drawCircle(
                    color = KeyflowBlue.copy(alpha = if (selected) 0.34f else 0.18f),
                    radius = 8.5.dp.toPx(),
                    center = Offset(thumbX, cy)
                )
                drawCircle(
                    color = Color.White,
                    radius = 5.6.dp.toPx(),
                    center = Offset(thumbX, cy)
                )
                drawCircle(
                    color = KeyflowBlue,
                    radius = 2.8.dp.toPx(),
                    center = Offset(thumbX, cy)
                )
            }
        }
    }
}

private fun formatRulerTick(v: Float, majorStep: Float): String {
    if (abs(v) < 0.0004f) return "0"
    return when {
        majorStep < 0.09f -> String.format("%.2f", v).trimEnd('0').trimEnd('.')
        majorStep < 0.95f -> String.format("%.1f", v)
        else -> v.roundToInt().toString()
    }
}

