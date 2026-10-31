package com.example.ui.components

import android.content.Context
import android.graphics.Bitmap
import android.opengl.GLSurfaceView
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Colorize
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.Project
import com.example.engine.AudioSpectrumSnapshot
import com.example.engine.PlaybackCacheEngine
import com.example.ui.theme.AmTeal
import com.example.ui.theme.AmTealBright
import com.example.ui.theme.AmTealContainer
import com.example.ui.theme.DeepBlack
import com.example.ui.theme.OutlineSubtle
import com.example.ui.theme.PanelDark
import com.example.ui.theme.SurfaceDark
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import kotlin.math.roundToInt

enum class EyedropperColorSlot(val label: String) {
    LAYER_PRIMARY("Layer Fill Color"),
    LAYER_GRADIENT_END("Gradient End Color"),
    LAYER_STROKE("Border / Stroke Color"),
    LAYER_SHADOW("Shadow / Glow Color"),
    EFFECT_TINT("Effect Color / Key"),
    PROJECT_BG("Canvas Background")
}

/**
 * Captures the live OpenGL preview stage (via hardware `PixelCopy` with instant software frame fallback)
 * so the Screen Eyedropper can analyze any pixel on the composition preview!
 */
class PreviewFrameSampler {
    var glViewProvider: (() -> GLSurfaceView?)? = null

    fun captureScreenBitmap(
        context: Context,
        project: Project,
        playheadMs: Long,
        audio: AudioSpectrumSnapshot,
        onCaptured: (Bitmap) -> Unit
    ) {
        val view = glViewProvider?.invoke()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            view != null &&
            view.isAttachedToWindow &&
            view.width >= 32 &&
            view.height >= 32
        ) {
            val targetW = (view.width / 2).coerceIn(64, 640)
            val targetH = (view.height / 2).coerceIn(64, 640)
            val dest = Bitmap.createBitmap(targetW, targetH, Bitmap.Config.ARGB_8888)
            try {
                PixelCopy.request(
                    view,
                    dest,
                    { copyResult ->
                        if (copyResult == PixelCopy.SUCCESS) {
                            onCaptured(dest)
                        } else {
                            dest.recycle()
                            onCaptured(renderFallbackBitmap(context, project, playheadMs, audio))
                        }
                    },
                    Handler(Looper.getMainLooper())
                )
                return
            } catch (_: Throwable) {
                dest.recycle()
            }
        }
        onCaptured(renderFallbackBitmap(context, project, playheadMs, audio))
    }

    private fun renderFallbackBitmap(
        context: Context,
        project: Project,
        playheadMs: Long,
        audio: AudioSpectrumSnapshot
    ): Bitmap {
        val aspect = (project.width.toFloat().coerceAtLeast(64f) / project.height.toFloat().coerceAtLeast(64f))
            .coerceIn(0.25f, 4f)
        val w = if (aspect >= 1f) 360 else (360 * aspect).roundToInt().coerceAtLeast(96)
        val h = if (aspect >= 1f) (360 / aspect).roundToInt().coerceAtLeast(96) else 360
        return PlaybackCacheEngine.renderSinglePreviewFrame(
            context = context,
            project = project,
            timeMs = playheadMs,
            audio = audio,
            w = w,
            h = h
        )
    }
}

private val STUDIO_QUICK_SWATCHES = listOf(
    "#FFFFFF", "#00D2A8", "#0094FF", "#FF3366", "#FFC400",
    "#9B6BFF", "#00E5FF", "#FF6D00", "#1DE9B6", "#00FF66",
    "#FF1744", "#111622", "#000000"
)

fun parseHexToArgbInt(hex: String, fallback: Int = android.graphics.Color.WHITE): Int {
    val cleaned = hex.trim().let { if (it.startsWith("#")) it else "#$it" }
    return runCatching { android.graphics.Color.parseColor(cleaned) }.getOrDefault(fallback)
}

fun formatArgbToHex6(argb: Int): String {
    val r = android.graphics.Color.red(argb).coerceIn(0, 255)
    val g = android.graphics.Color.green(argb).coerceIn(0, 255)
    val b = android.graphics.Color.blue(argb).coerceIn(0, 255)
    return String.format("#%02X%02X%02X", r, g, b)
}

/**
 * Full Custom Color + Linear/Radial Gradient + Screen Eyedropper Picker for Shapes, Text, Borders,
 * Shadows, Effects (Chroma Key / Glow), and Canvas Backgrounds!
 */
@Composable
fun AmCustomColorAndGradientPicker(
    primaryHex: String,
    onPrimaryHexChange: (String) -> Unit,
    onRequestEyedropper: (EyedropperColorSlot) -> Unit,
    primaryEyedropperSlot: EyedropperColorSlot = EyedropperColorSlot.LAYER_PRIMARY,
    supportsGradient: Boolean = false,
    fillMode: String = "SOLID",
    gradientEndHex: String = "#0094FF",
    gradientAngleDeg: Float = 45f,
    onGradientChange: ((mode: String, startHex: String, endHex: String, angleDeg: Float) -> Unit)? = null,
    compact: Boolean = false,
    testTagPrefix: String = "custom_color_picker",
    modifier: Modifier = Modifier
) {
    // Active stop: 0 = Primary/Start color, 1 = Gradient End color
    var editingEndStop by remember { mutableStateOf(false) }
    val isGradientMode = supportsGradient && (fillMode == "LINEAR_GRADIENT" || fillMode == "RADIAL_GRADIENT")
    if (!isGradientMode && editingEndStop) {
        editingEndStop = false
    }

    val activeHex = if (isGradientMode && editingEndStop) gradientEndHex else primaryHex
    val activeArgb = remember(activeHex) { parseHexToArgbInt(activeHex) }

    val initialHsv = remember(activeArgb) {
        FloatArray(3).also { android.graphics.Color.colorToHSV(activeArgb, it) }
    }
    var hue by remember { mutableFloatStateOf(initialHsv[0]) }
    var sat by remember { mutableFloatStateOf(initialHsv[1]) }
    var valueBri by remember { mutableFloatStateOf(initialHsv[2]) }
    var hexInput by remember(activeHex) { mutableStateOf(activeHex.uppercase()) }

    // Sync HSV when external hex changes (e.g., from Eyedropper or swatch tap)
    LaunchedEffect(activeHex) {
        val currentComputed = formatArgbToHex6(android.graphics.Color.HSVToColor(floatArrayOf(hue, sat, valueBri)))
        if (!currentComputed.equals(activeHex, ignoreCase = true)) {
            val hsv = FloatArray(3)
            android.graphics.Color.colorToHSV(parseHexToArgbInt(activeHex), hsv)
            hue = hsv[0]
            sat = hsv[1]
            valueBri = hsv[2]
        }
        hexInput = activeHex.uppercase()
    }

    val emitEditedColor: (String) -> Unit = { newHex ->
        if (isGradientMode && onGradientChange != null) {
            if (editingEndStop) {
                onGradientChange(fillMode, primaryHex, newHex, gradientAngleDeg)
            } else {
                onGradientChange(fillMode, newHex, gradientEndHex, gradientAngleDeg)
            }
        } else {
            onPrimaryHexChange(newHex)
        }
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .testTag(testTagPrefix),
        verticalArrangement = Arrangement.spacedBy(if (compact) 6.dp else 8.dp)
    ) {
        // 1. Optional SOLID / LINEAR GRADIENT / RADIAL GRADIENT Mode Switcher
        if (supportsGradient && onGradientChange != null) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                listOf(
                    "SOLID" to "Solid Color",
                    "LINEAR_GRADIENT" to "Linear Gradient",
                    "RADIAL_GRADIENT" to "Radial Gradient"
                ).forEach { (modeKey, modeLabel) ->
                    AmSegmentChip(
                        label = modeLabel,
                        selected = fillMode == modeKey,
                        compact = true,
                        onClick = {
                            onGradientChange(modeKey, primaryHex, gradientEndHex, gradientAngleDeg)
                        },
                        modifier = Modifier
                            .weight(1f)
                            .testTag("${testTagPrefix}_mode_$modeKey")
                    )
                }
            }
        }

        // 2. Gradient Stop Bar + Angle Control when Linear or Radial Gradient is active
        if (isGradientMode && onGradientChange != null) {
            val startCol = Color(parseHexToArgbInt(primaryHex))
            val endCol = Color(parseHexToArgbInt(gradientEndHex))
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(PanelDark)
                    .border(1.dp, OutlineSubtle, RoundedCornerShape(10.dp))
                    .padding(8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                // Live Gradient Preview Bar + Stop Handles
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(24.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(Brush.horizontalGradient(listOf(startCol, endCol)))
                        .border(1.dp, OutlineSubtle, RoundedCornerShape(6.dp))
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Start Stop Pill
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (!editingEndStop) AmTealContainer else DeepBlack)
                            .border(
                                1.2.dp,
                                if (!editingEndStop) AmTealBright else OutlineSubtle,
                                RoundedCornerShape(8.dp)
                            )
                            .clickable { editingEndStop = false }
                            .testTag("${testTagPrefix}_stop_start")
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(14.dp)
                                .clip(CircleShape)
                                .background(startCol)
                                .border(1.dp, Color.White, CircleShape)
                        )
                        Text(
                            text = "Stop 1",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (!editingEndStop) AmTealBright else TextSecondary,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    // Swap Stops Button
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(DeepBlack)
                            .border(1.dp, OutlineSubtle, RoundedCornerShape(8.dp))
                            .clickable {
                                onGradientChange(fillMode, gradientEndHex, primaryHex, gradientAngleDeg)
                            }
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.SwapHoriz,
                            contentDescription = "Swap gradient stops",
                            tint = TextPrimary,
                            modifier = Modifier.size(15.dp)
                        )
                        Spacer(modifier = Modifier.width(3.dp))
                        Text("Swap", style = MaterialTheme.typography.labelSmall, color = TextSecondary)
                    }

                    // End Stop Pill
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (editingEndStop) AmTealContainer else DeepBlack)
                            .border(
                                1.2.dp,
                                if (editingEndStop) AmTealBright else OutlineSubtle,
                                RoundedCornerShape(8.dp)
                            )
                            .clickable { editingEndStop = true }
                            .testTag("${testTagPrefix}_stop_end")
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(14.dp)
                                .clip(CircleShape)
                                .background(endCol)
                                .border(1.dp, Color.White, CircleShape)
                        )
                        Text(
                            text = "Stop 2",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (editingEndStop) AmTealBright else TextSecondary,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                if (fillMode == "LINEAR_GRADIENT") {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "Angle ${gradientAngleDeg.roundToInt()}°",
                            style = MaterialTheme.typography.labelSmall,
                            color = TextSecondary,
                            modifier = Modifier.width(66.dp)
                        )
                        Slider(
                            value = gradientAngleDeg,
                            onValueChange = { ang ->
                                onGradientChange(fillMode, primaryHex, gradientEndHex, ang)
                            },
                            valueRange = 0f..360f,
                            colors = SliderDefaults.colors(thumbColor = AmTealBright, activeTrackColor = AmTeal),
                            modifier = Modifier
                                .weight(1f)
                                .height(26.dp)
                        )
                    }
                }
            }
        }

        // 3. Top Header Row: Current Swatch + Editable HEX Input + Screen Eyedropper CTA!
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color(activeArgb))
                    .border(1.2.dp, Color.White.copy(alpha = 0.8f), RoundedCornerShape(8.dp))
            )

            // Editable #RRGGBB Hex Field
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(34.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(DeepBlack)
                    .border(1.dp, OutlineSubtle, RoundedCornerShape(8.dp))
                    .padding(horizontal = 10.dp),
                contentAlignment = Alignment.CenterStart
            ) {
                BasicTextField(
                    value = hexInput,
                    onValueChange = { raw ->
                        val filtered = raw.uppercase().filter { it == '#' || it in '0'..'9' || it in 'A'..'F' }.take(7)
                        hexInput = filtered
                        val norm = if (filtered.startsWith("#")) filtered else "#$filtered"
                        if (norm.length == 7) {
                            runCatching {
                                android.graphics.Color.parseColor(norm)
                                emitEditedColor(norm)
                            }
                        }
                    },
                    singleLine = true,
                    textStyle = TextStyle(
                        color = TextPrimary,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp
                    ),
                    cursorBrush = SolidColor(AmTealBright),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("${testTagPrefix}_hex_input")
                )
            }

            // Screen Eyedropper Button!
            val targetSlot = if (isGradientMode && editingEndStop) {
                EyedropperColorSlot.LAYER_GRADIENT_END
            } else {
                primaryEyedropperSlot
            }
            Row(
                modifier = Modifier
                    .height(34.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(AmTealContainer)
                    .border(1.dp, AmTealBright, RoundedCornerShape(8.dp))
                    .clickable { onRequestEyedropper(targetSlot) }
                    .testTag("${testTagPrefix}_eyedropper_button")
                    .padding(horizontal = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Colorize,
                    contentDescription = "Screen Eyedropper",
                    tint = AmTealBright,
                    modifier = Modifier.size(16.dp)
                )
                Text(
                    text = "Eyedropper",
                    style = MaterialTheme.typography.labelSmall,
                    color = AmTealBright,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        // 4. Interactive 2D HSV Saturation-Value Square + Rainbow Hue Bar
        val svHeight = if (compact) 92.dp else 112.dp
        val pureHueColor = remember(hue) {
            Color(android.graphics.Color.HSVToColor(floatArrayOf(hue, 1f, 1f)))
        }
        var svSize by remember { mutableStateOf(IntSize(1, 1)) }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(svHeight)
                .clip(RoundedCornerShape(10.dp))
                .border(1.dp, OutlineSubtle, RoundedCornerShape(10.dp))
                .onSizeChanged { svSize = it }
                .pointerInput(hue) {
                    awaitEachGesture {
                        do {
                            val event = awaitPointerEvent()
                            val down = event.changes.firstOrNull { it.pressed }
                            if (down != null) {
                                val nx = (down.position.x / svSize.width.coerceAtLeast(1).toFloat()).coerceIn(0f, 1f)
                                val ny = (down.position.y / svSize.height.coerceAtLeast(1).toFloat()).coerceIn(0f, 1f)
                                sat = nx
                                valueBri = (1f - ny).coerceIn(0f, 1f)
                                val newHex = formatArgbToHex6(android.graphics.Color.HSVToColor(floatArrayOf(hue, sat, valueBri)))
                                hexInput = newHex
                                emitEditedColor(newHex)
                                if (down.positionChanged()) down.consume()
                            }
                        } while (event.changes.any { it.pressed })
                    }
                }
                .testTag("${testTagPrefix}_sv_pad")
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                drawRect(brush = Brush.horizontalGradient(listOf(Color.White, pureHueColor)))
                drawRect(brush = Brush.verticalGradient(listOf(Color.Transparent, Color.Black)))
                val cx = sat * size.width
                val cy = (1f - valueBri) * size.height
                drawCircle(Color.Black, radius = 8.5f, center = Offset(cx, cy), style = Stroke(width = 3f))
                drawCircle(Color.White, radius = 7.5f, center = Offset(cx, cy), style = Stroke(width = 2.2f))
            }
        }

        // Rainbow Hue Strip
        var hueBarWidth by remember { mutableIntStateOf(1) }
        val rainbowColors = remember {
            listOf(
                Color(0xFFFF0000),
                Color(0xFFFFFF00),
                Color(0xFF00FF00),
                Color(0xFF00FFFF),
                Color(0xFF0000FF),
                Color(0xFFFF00FF),
                Color(0xFFFF0000)
            )
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(22.dp)
                .clip(RoundedCornerShape(11.dp))
                .border(1.dp, OutlineSubtle, RoundedCornerShape(11.dp))
                .onSizeChanged { hueBarWidth = it.width.coerceAtLeast(1) }
                .pointerInput(sat, valueBri) {
                    awaitEachGesture {
                        do {
                            val event = awaitPointerEvent()
                            val down = event.changes.firstOrNull { it.pressed }
                            if (down != null) {
                                val frac = (down.position.x / hueBarWidth.toFloat()).coerceIn(0f, 1f)
                                hue = frac * 360f
                                val newHex = formatArgbToHex6(android.graphics.Color.HSVToColor(floatArrayOf(hue, sat, valueBri)))
                                hexInput = newHex
                                emitEditedColor(newHex)
                                if (down.positionChanged()) down.consume()
                            }
                        } while (event.changes.any { it.pressed })
                    }
                }
                .testTag("${testTagPrefix}_hue_bar")
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                drawRect(brush = Brush.horizontalGradient(rainbowColors))
                val hx = (hue / 360f).coerceIn(0f, 1f) * size.width
                drawCircle(Color.Black, radius = size.height * 0.44f, center = Offset(hx, size.height * 0.5f), style = Stroke(width = 3f))
                drawCircle(Color.White, radius = size.height * 0.40f, center = Offset(hx, size.height * 0.5f), style = Stroke(width = 2.2f))
            }
        }

        // 5. Quick Swatches Strip
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(7.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            STUDIO_QUICK_SWATCHES.forEach { swatchHex ->
                val isSelected = activeHex.equals(swatchHex, ignoreCase = true)
                val swColor = remember(swatchHex) { Color(parseHexToArgbInt(swatchHex)) }
                Box(
                    modifier = Modifier
                        .size(24.dp)
                        .clip(CircleShape)
                        .background(swColor)
                        .border(
                            if (isSelected) 2.5.dp else 1.dp,
                            if (isSelected) AmTealBright else OutlineSubtle,
                            CircleShape
                        )
                        .clickable { emitEditedColor(swatchHex) }
                )
            }
        }
    }
}

/**
 * Interactive Screen Eyedropper Loupe Overlay rendered over the preview stage in [EditorWorkspaceScreen]:
 * Drag finger anywhere across the preview to inspect magnified pixels in real time and pick the exact color!
 */
@Composable
fun AmInteractiveEyedropperOverlay(
    capturedBitmap: Bitmap,
    targetLabel: String,
    onColorPicked: (String) -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier
) {
    val density = LocalDensity.current
    var boxSize by remember { mutableStateOf(IntSize(1, 1)) }
    var probeNormX by remember { mutableFloatStateOf(0.5f) }
    var probeNormY by remember { mutableFloatStateOf(0.5f) }

    val sampledHex = remember(capturedBitmap, probeNormX, probeNormY) {
        if (capturedBitmap.isRecycled || capturedBitmap.width <= 0 || capturedBitmap.height <= 0) {
            "#00D2A8"
        } else {
            val px = (probeNormX * (capturedBitmap.width - 1)).roundToInt().coerceIn(0, capturedBitmap.width - 1)
            val py = (probeNormY * (capturedBitmap.height - 1)).roundToInt().coerceIn(0, capturedBitmap.height - 1)
            formatArgbToHex6(capturedBitmap.getPixel(px, py))
        }
    }
    val sampledColor = remember(sampledHex) { Color(parseHexToArgbInt(sampledHex)) }

    Box(
        modifier = modifier
            .fillMaxSize()
            .onSizeChanged { boxSize = it }
            .pointerInput(capturedBitmap) {
                awaitEachGesture {
                    do {
                        val event = awaitPointerEvent()
                        val down = event.changes.firstOrNull { it.pressed }
                        if (down != null) {
                            probeNormX = (down.position.x / boxSize.width.coerceAtLeast(1).toFloat()).coerceIn(0f, 1f)
                            probeNormY = (down.position.y / boxSize.height.coerceAtLeast(1).toFloat()).coerceIn(0f, 1f)
                            if (down.positionChanged()) down.consume()
                        }
                    } while (event.changes.any { it.pressed })
                }
            }
            .testTag("eyedropper_screen_overlay")
    ) {
        // Crosshair + Magnifying Color Ring at Probe Position
        Canvas(modifier = Modifier.fillMaxSize()) {
            val cx = probeNormX * size.width
            val cy = probeNormY * size.height
            drawCircle(Color.Black.copy(alpha = 0.7f), radius = 30f, center = Offset(cx, cy), style = Stroke(width = 7f))
            drawCircle(sampledColor, radius = 28f, center = Offset(cx, cy), style = Stroke(width = 6f))
            drawCircle(Color.White, radius = 32f, center = Offset(cx, cy), style = Stroke(width = 2f))
            drawLine(Color.White, Offset(cx - 10f, cy), Offset(cx + 10f, cy), strokeWidth = 1.8f)
            drawLine(Color.White, Offset(cx, cy - 10f), Offset(cx, cy + 10f), strokeWidth = 1.8f)
        }

        // Floating Loupe Readout above finger
        val loupeOffsetX = (probeNormX * boxSize.width - with(density) { 54.dp.toPx() }).roundToInt()
            .coerceIn(8, (boxSize.width - with(density) { 116.dp.toPx() }.roundToInt()).coerceAtLeast(8))
        val loupeOffsetY = (probeNormY * boxSize.height - with(density) { 64.dp.toPx() }).roundToInt()
            .coerceAtLeast(8)

        Row(
            modifier = Modifier
                .offset { IntOffset(loupeOffsetX, loupeOffsetY) }
                .shadow(8.dp, RoundedCornerShape(18.dp))
                .clip(RoundedCornerShape(18.dp))
                .background(SurfaceDark.copy(alpha = 0.96f))
                .border(1.2.dp, AmTealBright, RoundedCornerShape(18.dp))
                .padding(horizontal = 10.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(16.dp)
                    .clip(CircleShape)
                    .background(sampledColor)
                    .border(1.dp, Color.White, CircleShape)
            )
            Text(
                text = sampledHex,
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                color = TextPrimary
            )
        }

        // Top Eyedropper Action Bar (Drag to sample + Apply / Cancel)
        Row(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 8.dp)
                .shadow(8.dp, RoundedCornerShape(12.dp))
                .clip(RoundedCornerShape(12.dp))
                .background(SurfaceDark.copy(alpha = 0.96f))
                .border(1.dp, AmTeal, RoundedCornerShape(12.dp))
                .padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Colorize,
                contentDescription = null,
                tint = AmTealBright,
                modifier = Modifier.size(16.dp)
            )
            Column {
                Text(
                    text = "Eyedropper • $targetLabel",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary
                )
                Text(
                    text = "Drag anywhere on preview, then tap Pick",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextMuted
                )
            }

            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(AmTeal)
                    .clickable { onColorPicked(sampledHex) }
                    .testTag("eyedropper_confirm_button")
                    .padding(horizontal = 10.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Icon(Icons.Default.Check, contentDescription = "Apply sampled color", tint = DeepBlack, modifier = Modifier.size(15.dp))
                Text("Pick", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = DeepBlack)
            }

            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(CircleShape)
                    .background(PanelDark)
                    .clickable(onClick = onCancel)
                    .testTag("eyedropper_cancel_button"),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Default.Close, contentDescription = "Cancel eyedropper", tint = TextSecondary, modifier = Modifier.size(15.dp))
            }
        }
    }
}
