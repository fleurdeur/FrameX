package com.example.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
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
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Home
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
 * Alight Motion's Horizontal Graduated Tick-Wheel Scrubber Pad!
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
    testTag: String = "property_value_slider"
) {
    val currentValState by rememberUpdatedState(value)
    val currentOnValueChange by rememberUpdatedState(onValueChange)
    var visualPhasePx by remember { mutableFloatStateOf(0f) }
    val span = (maxValue - minValue).coerceAtLeast(0.01f)
    val formattedVal = if (span <= 6f) {
        String.format("%.2f", value)
    } else {
        String.format("%.1f", value)
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
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                if (fineStep > 0f) {
                    Box(
                        modifier = Modifier
                            .size(22.dp)
                            .clip(CircleShape)
                            .background(PanelDark)
                            .border(0.8.dp, OutlineSubtle, CircleShape)
                            .clickable {
                                currentOnValueChange((currentValState - fineStep).coerceIn(minValue, maxValue))
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Text("−", style = MaterialTheme.typography.labelSmall, color = TextPrimary, fontWeight = FontWeight.Bold)
                    }
                }
                Text(
                    text = "$formattedVal$unit",
                    style = MaterialTheme.typography.labelMedium,
                    color = AmTealBright,
                    fontWeight = FontWeight.Bold
                )
                if (fineStep > 0f) {
                    Box(
                        modifier = Modifier
                            .size(22.dp)
                            .clip(CircleShape)
                            .background(PanelDark)
                            .border(0.8.dp, OutlineSubtle, CircleShape)
                            .clickable {
                                currentOnValueChange((currentValState + fineStep).coerceIn(minValue, maxValue))
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Text("+", style = MaterialTheme.typography.labelSmall, color = TextPrimary, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(4.dp))

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(42.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(DeepBlack)
                .border(1.dp, OutlineSubtle, RoundedCornerShape(10.dp))
                .pointerInput(minValue, maxValue) {
                    detectHorizontalDragGestures { change, dragAmount ->
                        change.consume()
                        visualPhasePx = (visualPhasePx + dragAmount) % 240f
                        val sensitivity = span / 520f
                        val next = (currentValState + dragAmount * sensitivity).coerceIn(minValue, maxValue)
                        currentOnValueChange(next)
                    }
                }
                .testTag(testTag)
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val w = size.width
                val h = size.height
                val spacing = 12f
                val offsetMod = ((visualPhasePx % spacing) + spacing) % spacing
                val totalTicks = (w / spacing).roundToInt() + 2

                for (i in -1..totalTicks) {
                    val x = i * spacing + offsetMod
                    if (x < 0f || x > w) continue
                    val distFromCenter = kotlin.math.abs(x - w * 0.5f) / (w * 0.5f).coerceAtLeast(1f)
                    val alpha = (1f - distFromCenter * 0.72f).coerceIn(0.15f, 1f)
                    val isMajor = ((i - (visualPhasePx / spacing).roundToInt()) % 5 == 0)
                    val tickTop = if (isMajor) h * 0.20f else h * 0.36f
                    val tickBot = if (isMajor) h * 0.80f else h * 0.64f
                    drawLine(
                        color = (if (isMajor) TextPrimary else TextSecondary).copy(alpha = alpha),
                        start = Offset(x, tickTop),
                        end = Offset(x, tickBot),
                        strokeWidth = if (isMajor) 2.2f else 1.2f
                    )
                }

                drawRect(
                    brush = Brush.horizontalGradient(
                        0.0f to DeepBlack,
                        0.16f to Color.Transparent,
                        0.84f to Color.Transparent,
                        1.0f to DeepBlack
                    )
                )

                val norm = ((value - minValue) / span).coerceIn(0f, 1f)
                drawLine(
                    color = AmTeal.copy(alpha = 0.45f),
                    start = Offset(0f, h - 2f),
                    end = Offset(w * norm, h - 2f),
                    strokeWidth = 3f
                )

                val cx = w * 0.5f
                drawLine(
                    color = AmTealBright,
                    start = Offset(cx, 3f),
                    end = Offset(cx, h - 3f),
                    strokeWidth = 2.8f
                )
                drawCircle(color = AmTealBright, radius = 3.2f, center = Offset(cx, 5f))
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
    TEMPLATES("Templates", Icons.Default.AutoAwesomeMotion),
    PROJECTS("Projects", Icons.Default.Folder),
    ELEMENTS("Elements", Icons.Default.Widgets)
}

/**
 * Alight Motion Home Screen Bottom Navigation Bar with balanced, cleanly proportioned
 * centered circular Teal '+' Create Project FAB!
 */
@Composable
fun AmBottomNavigationBar(
    selectedTab: AmHomeTab,
    onSelectTab: (AmHomeTab) -> Unit,
    onCreateProjectClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        color = SurfaceDark,
        tonalElevation = 8.dp,
        modifier = modifier
            .fillMaxWidth()
            .border(0.6.dp, OutlineSubtle)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .height(64.dp)
                .padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            AmBottomNavItem(
                tab = AmHomeTab.HOME,
                selected = selectedTab == AmHomeTab.HOME,
                onClick = { onSelectTab(AmHomeTab.HOME) }
            )
            AmBottomNavItem(
                tab = AmHomeTab.TEMPLATES,
                selected = selectedTab == AmHomeTab.TEMPLATES,
                onClick = { onSelectTab(AmHomeTab.TEMPLATES) }
            )

            Box(
                modifier = Modifier
                    .size(48.dp)
                    .shadow(6.dp, CircleShape)
                    .clip(CircleShape)
                    .background(Brush.linearGradient(colors = listOf(AmTealBright, AmTeal)))
                    .clickable(onClick = onCreateProjectClick)
                    .testTag("new_project_fab"),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = "New Project",
                    tint = DeepBlack,
                    modifier = Modifier.size(26.dp)
                )
            }

            AmBottomNavItem(
                tab = AmHomeTab.PROJECTS,
                selected = selectedTab == AmHomeTab.PROJECTS,
                onClick = { onSelectTab(AmHomeTab.PROJECTS) }
            )
            AmBottomNavItem(
                tab = AmHomeTab.ELEMENTS,
                selected = selectedTab == AmHomeTab.ELEMENTS,
                onClick = { onSelectTab(AmHomeTab.ELEMENTS) }
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
                .background(if (selected) AmTeal.copy(alpha = 0.16f) else Color.Transparent)
                .padding(horizontal = 12.dp, vertical = 2.dp),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = tab.icon,
                contentDescription = tab.label,
                tint = if (selected) AmTealBright else TextSecondary,
                modifier = Modifier.size(20.dp)
            )
        }
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = tab.label,
            style = MaterialTheme.typography.labelSmall,
            color = if (selected) AmTealBright else TextSecondary,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            maxLines = 1
        )
    }
}

/**
 * KeyFlow Signature Property Ruler Card (Screenshot 4):
 * - Sleek dark `#1A1C22` rounded card (`12.dp` radius)
 * - Left: Property label (`KeyflowBlue` when selected, crisp white when unselected, tappable to bind Keyframe/Curve rail)
 * - Right: Centered `KeyflowBlue` rounded value pill (`0.030`, `0`, `0.50`, tappable for exact numeric entry)
 *   directly above a numbered horizontal graduated tick-ruler scrubber!
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
    labelTestTag: String? = null,
    scrubberTestTag: String? = null
) {
    val currentValState by rememberUpdatedState(value)
    val currentOnSelect by rememberUpdatedState(onSelect)
    val currentOnValueChange by rememberUpdatedState(onValueChange)
    val density = LocalDensity.current

    val span = (maxValue - minValue).coerceAtLeast(0.001f)
    val formattedCapsuleValue = remember(value, span, unit) {
        val base = when {
            abs(value) < 0.0005f -> "0"
            span <= 1.2f -> String.format("%.3f", value)
            span <= 12f -> String.format("%.2f", value)
            span <= 120f -> String.format("%.2f", value).trimEnd('0').trimEnd('.')
            else -> String.format("%.1f", value).trimEnd('0').trimEnd('.')
        }
        if (unit.isEmpty() || unit == "%") base else "$base$unit"
    }

    // Compute clean major tick step for the graduated ruler
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

    val tickTextPaint = remember(density) {
        android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.argb(175, 168, 178, 196)
            textSize = with(density) { 8.5.sp.toPx() }
            textAlign = android.graphics.Paint.Align.CENTER
            typeface = android.graphics.Typeface.create(android.graphics.Typeface.SANS_SERIF, android.graphics.Typeface.NORMAL)
        }
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
                    label = { Text("${formatRulerTick(minValue, majorStep)} .. ${formatRulerTick(maxValue, majorStep)}") },
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        exactDraft.trim().toFloatOrNull()?.let { parsed ->
                            currentOnSelect()
                            currentOnValueChange(parsed.coerceIn(minValue, maxValue))
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

    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(64.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(KeyflowCardSurface)
            .border(
                width = if (selected) 1.1.dp else 0.8.dp,
                color = if (selected) KeyflowBlue.copy(alpha = 0.55f) else KeyflowCardBorder,
                shape = RoundedCornerShape(12.dp)
            )
            .clickable { currentOnSelect() }
            .padding(start = 14.dp, end = 10.dp, top = 6.dp, bottom = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // Left: Property Label (KeyFlow Blue when selected, White when unselected)
        Row(
            modifier = Modifier
                .weight(0.39f)
                .then(if (labelTestTag != null) Modifier.testTag(labelTestTag) else Modifier),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
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

        // Right: Centered Blue Value Pill + Graduated Numbered Tick-Ruler Scrubber
        Column(
            modifier = Modifier
                .weight(0.61f)
                .fillMaxHeight()
                .pointerInput(minValue, maxValue, majorStep) {
                    detectHorizontalDragGestures(
                        onDragStart = { currentOnSelect() },
                        onHorizontalDrag = { change, dragAmount ->
                            change.consume()
                            val pxPerMajor = 56.dp.toPx().coerceAtLeast(24f)
                            // Dragging left increases value (pulls higher ruler numbers under center needle),
                            // or dragging right increases value? Standard KeyFlow/AM ruler: dragging left advances
                            // ruler numbers to the right, dragging right decreases; wait—let's make dragging RIGHT
                            // increase value when moving thumb right OR let ruler scroll naturally with value!
                            // Making dragAmount > 0 increase value feels intuitive for sliders while ruler ticks track `value`.
                            val delta = (dragAmount / pxPerMajor) * majorStep
                            val next = (currentValState + delta).coerceIn(minValue, maxValue)
                            currentOnValueChange(next)
                        }
                    )
                }
                .then(if (scrubberTestTag != null) Modifier.testTag(scrubberTestTag) else Modifier),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // KeyFlow Blue Value Capsule
            Box(
                modifier = Modifier
                    .height(20.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(KeyflowBlue)
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
                    color = Color(0xFF060912),
                    maxLines = 1
                )
            }

            // Graduated Numbered Tick-Ruler Canvas
            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(30.dp)
            ) {
                val w = size.width
                val h = size.height
                val cx = w * 0.5f
                val pxPerMajor = 56.dp.toPx().coerceAtLeast(28f)
                val minorStep = majorStep / 10f
                val pxPerMinor = pxPerMajor / 10f

                val halfVisibleMinors = ((w * 0.5f) / pxPerMinor).toInt() + 2
                val centerMinorIdx = (value / minorStep).roundToInt()

                for (offsetIdx in -halfVisibleMinors..halfVisibleMinors) {
                    val mIdx = centerMinorIdx + offsetIdx
                    val tickVal = mIdx * minorStep
                    if (tickVal < minValue - minorStep * 0.25f || tickVal > maxValue + minorStep * 0.25f) continue

                    val x = cx + ((tickVal - value) / minorStep) * pxPerMinor
                    if (x < 1f || x > w - 1f) continue

                    val edgeFade = (1f - (abs(x - cx) / (w * 0.52f))).coerceIn(0.14f, 1f)
                    val isMajor = (mIdx % 10 == 0)
                    val isMid = (abs(mIdx % 10) == 5)

                    val tickTop = when {
                        isMajor -> h * 0.42f
                        isMid -> h * 0.56f
                        else -> h * 0.68f
                    }
                    val tickColor = when {
                        isMajor -> Color(0xFFB8C2D4).copy(alpha = 0.85f * edgeFade)
                        isMid -> Color(0xFF7D8799).copy(alpha = 0.72f * edgeFade)
                        else -> Color(0xFF555E6E).copy(alpha = 0.58f * edgeFade)
                    }
                    drawLine(
                        color = tickColor,
                        start = Offset(x, tickTop),
                        end = Offset(x, h - 1.dp.toPx()),
                        strokeWidth = if (isMajor) 1.35f else 1f
                    )

                    if (isMajor && abs(x - cx) > 6.dp.toPx()) {
                        tickTextPaint.alpha = (195f * edgeFade).toInt().coerceIn(30, 215)
                        drawContext.canvas.nativeCanvas.drawText(
                            formatRulerTick(tickVal, majorStep),
                            x,
                            h * 0.33f,
                            tickTextPaint
                        )
                    }
                }

                // Crisp Center Vertical Needle pointing from capsule down through ticks
                drawLine(
                    color = if (selected) KeyflowBlueBright else Color.White.copy(alpha = 0.90f),
                    start = Offset(cx, 1.dp.toPx()),
                    end = Offset(cx, h),
                    strokeWidth = 1.9.dp.toPx()
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

