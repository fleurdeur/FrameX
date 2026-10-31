package com.example.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp

private val FramePollutionColorScheme = darkColorScheme(
    primary = AmTeal,
    onPrimary = DeepBlack,
    primaryContainer = AmTealContainer,
    onPrimaryContainer = OnAmTealContainer,
    secondary = ElectricBlue,
    onSecondary = TextPrimary,
    secondaryContainer = ElectricBlueContainer,
    onSecondaryContainer = OnElectricBlueContainer,
    tertiary = NeonPink,
    onTertiary = TextPrimary,
    tertiaryContainer = NeonPinkContainer,
    onTertiaryContainer = OnNeonPinkContainer,
    background = AmBgDark,
    onBackground = TextPrimary,
    surface = SurfaceDark,
    onSurface = TextPrimary,
    surfaceVariant = PanelDark,
    onSurfaceVariant = TextSecondary,
    surfaceContainerHigh = PanelElevated,
    outline = OutlineSubtle,
    error = PlayheadRed,
    onError = TextPrimary
)

val FramePollutionShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(10.dp),
    large = RoundedCornerShape(14.dp),
    extraLarge = RoundedCornerShape(20.dp)
)

@Composable
fun FramePollutionTheme(
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = FramePollutionColorScheme,
        typography = Typography,
        shapes = FramePollutionShapes,
        content = content
    )
}
