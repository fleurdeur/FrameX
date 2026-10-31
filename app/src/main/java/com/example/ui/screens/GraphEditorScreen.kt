@file:OptIn(ExperimentalMaterial3Api::class)

package com.example.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ShowChart
import androidx.compose.material.icons.automirrored.filled.TrendingUp
import androidx.compose.material.icons.filled.Functions
import androidx.compose.material.icons.filled.Timeline
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import com.example.ui.components.AmSegmentChip
import com.example.ui.components.AmSubToolRailButton
import com.example.ui.theme.AmBgDark
import com.example.ui.theme.AmTeal
import com.example.ui.theme.AmTealBright
import com.example.ui.theme.DeepBlack
import com.example.ui.theme.NeonPink
import com.example.ui.theme.OutlineSubtle
import com.example.ui.theme.PlayheadWhite
import com.example.ui.theme.SurfaceDark
import com.example.ui.theme.TextSecondary
import kotlin.math.abs
import kotlin.math.hypot

@Composable
fun GraphEditorScreen(
    selectedLayer: Layer?,
    selectedProperty: PropertyTarget,
    playheadMs: Long,
    onSelectProperty: (PropertyTarget) -> Unit,
    onUpdateBezierHandles: (outX: Float, outY: Float, inX: Float, inY: Float) -> Unit,
    onUpdateExpression: (String) -> Unit,
    onBack: () -> Unit
) {
    val prop = selectedLayer?.propertyFor(selectedProperty)
    val sortedKfs = prop?.keyframes?.sortedBy { it.timeMs }.orEmpty()
    val segIdx = (sortedKfs.indexOfLast { it.timeMs <= playheadMs }.coerceAtLeast(0))
        .coerceAtMost((sortedKfs.size - 2).coerceAtLeast(0))
    val activeOut = sortedKfs.getOrNull(segIdx)
    val activeIn = sortedKfs.getOrNull(segIdx + 1)

    var p1x by remember(selectedProperty, segIdx, activeOut?.outTangentX) {
        mutableFloatStateOf(activeOut?.outTangentX ?: 0.25f)
    }
    var p1y by remember(selectedProperty, segIdx, activeOut?.outTangentY) {
        mutableFloatStateOf(activeOut?.outTangentY ?: 0.1f)
    }
    var p2x by remember(selectedProperty, segIdx, activeIn?.inTangentX) {
        mutableFloatStateOf(activeIn?.inTangentX ?: 0.25f)
    }
    var p2y by remember(selectedProperty, segIdx, activeIn?.inTangentY) {
        mutableFloatStateOf(activeIn?.inTangentY ?: 1.0f)
    }
    var expressionText by remember(selectedLayer?.id, selectedProperty) {
        mutableStateOf(prop?.expression ?: "")
    }
    var curveMode by remember { mutableIntStateOf(1) } // 0=Linear, 1=Bezier, 2=Overshoot, 3=Expr

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
                            text = "Easing & Bezier Graph",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "${selectedProperty.label} • cubic-bezier(${String.format("%.2f", p1x)}, ${String.format("%.2f", p1y)}, ${String.format("%.2f", p2x)}, ${String.format("%.2f", p2y)})",
                            style = MaterialTheme.typography.labelSmall,
                            color = AmTealBright
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
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Property Selector Strip
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                PropertyTarget.entries.forEach { target ->
                    AmSegmentChip(
                        label = target.label,
                        selected = selectedProperty == target,
                        onClick = { onSelectProperty(target) }
                    )
                }
            }

            // Alight Motion Split Graph Workspace: Left 4-Mode Curve Rail + Square Bezier Grid Box
            Row(
                modifier = Modifier
                    .widthIn(max = 460.dp)
                    .fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Left Vertical Curve Mode Icon Column (Exact Alight Motion Easing Rail)
                Column(
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    AmSubToolRailButton(
                        icon = Icons.AutoMirrored.Filled.TrendingUp,
                        label = "Linear",
                        selected = curveMode == 0,
                        onClick = {
                            curveMode = 0
                            p1x = 0f; p1y = 0f; p2x = 1f; p2y = 1f
                            onUpdateBezierHandles(p1x, p1y, p2x, p2y)
                        }
                    )
                    AmSubToolRailButton(
                        icon = Icons.AutoMirrored.Filled.ShowChart,
                        label = "Bezier",
                        selected = curveMode == 1,
                        onClick = {
                            curveMode = 1
                            p1x = 0.42f; p1y = 0f; p2x = 0.18f; p2y = 1f
                            onUpdateBezierHandles(p1x, p1y, p2x, p2y)
                        }
                    )
                    AmSubToolRailButton(
                        icon = Icons.Default.Timeline,
                        label = "Bounce",
                        selected = curveMode == 2,
                        onClick = {
                            curveMode = 2
                            p1x = 0.34f; p1y = 1.32f; p2x = 0.64f; p2y = 1f
                            onUpdateBezierHandles(p1x, p1y, p2x, p2y)
                        }
                    )
                    AmSubToolRailButton(
                        icon = Icons.Default.Functions,
                        label = "Expr",
                        selected = curveMode == 3,
                        onClick = { curveMode = 3 }
                    )
                }

                // Square Alight Motion Dark Bezier Curve Box
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .aspectRatio(1f)
                        .clip(RoundedCornerShape(14.dp))
                        .background(DeepBlack)
                        .border(1.2.dp, OutlineSubtle, RoundedCornerShape(14.dp))
                        .padding(22.dp)
                ) {
                    var draggingHandle by remember { mutableIntStateOf(0) }

                    Canvas(
                        modifier = Modifier
                            .fillMaxSize()
                            .pointerInput(Unit) {
                                detectDragGestures(
                                    onDragStart = { start ->
                                        val w = size.width.toFloat().coerceAtLeast(1f)
                                        val h = size.height.toFloat().coerceAtLeast(1f)
                                        val h1 = Offset(p1x * w, (1f - p1y) * h)
                                        val h2 = Offset(p2x * w, (1f - p2y) * h)
                                        val d1 = hypot(start.x - h1.x, start.y - h1.y)
                                        val d2 = hypot(start.x - h2.x, start.y - h2.y)
                                        draggingHandle = if (d1 <= d2) 1 else 2
                                        curveMode = 1
                                    },
                                    onDrag = { change, _ ->
                                        change.consume()
                                        val nx = (change.position.x / size.width.coerceAtLeast(1)).coerceIn(0f, 1f)
                                        val ny = (1f - (change.position.y / size.height.coerceAtLeast(1))).coerceIn(-0.35f, 1.35f)
                                        if (draggingHandle == 1) {
                                            p1x = nx; p1y = ny
                                        } else {
                                            p2x = nx; p2y = ny
                                        }
                                        onUpdateBezierHandles(p1x, p1y, p2x, p2y)
                                    }
                                )
                            }
                            .testTag("bezier_curve_canvas")
                    ) {
                        val w = size.width
                        val h = size.height

                        // Hairline 4x4 Grid
                        for (g in 0..4) {
                            val f = g / 4f
                            drawLine(Color(0xFF232836), Offset(w * f, 0f), Offset(w * f, h), strokeWidth = 1.2f)
                            drawLine(Color(0xFF232836), Offset(0f, h * f), Offset(w, h * f), strokeWidth = 1.2f)
                        }

                        // Diagonal linear baseline
                        drawLine(Color(0x44FFFFFF), Offset(0f, h), Offset(w, 0f), strokeWidth = 1.6f)

                        val p0 = Offset(0f, h)
                        val p3 = Offset(w, 0f)
                        val c1 = Offset(p1x * w, (1f - p1y) * h)
                        val c2 = Offset(p2x * w, (1f - p2y) * h)

                        // Speed / Velocity Bars
                        for (s in 1..20) {
                            val t0 = (s - 1) / 20f
                            val t1 = s / 20f
                            val y0 = AnimationEvaluator.evaluateCubicBezier(t0, p1x, p1y, p2x, p2y)
                            val y1 = AnimationEvaluator.evaluateCubicBezier(t1, p1x, p1y, p2x, p2y)
                            val vel = abs(y1 - y0) * 20f
                            val barH = (vel * h * 0.18f).coerceAtMost(h * 0.45f)
                            drawLine(
                                color = AmTeal.copy(alpha = 0.28f),
                                start = Offset(t1 * w, h),
                                end = Offset(t1 * w, h - barH),
                                strokeWidth = 5f
                            )
                        }

                        // Alight Motion Tangent Stems
                        drawLine(NeonPink, p0, c1, strokeWidth = 2.8f)
                        drawLine(AmTealBright, p3, c2, strokeWidth = 2.8f)

                        // Crisp White-Teal Spline Curve
                        val curve = Path().apply {
                            moveTo(p0.x, p0.y)
                            cubicTo(c1.x, c1.y, c2.x, c2.y, p3.x, p3.y)
                        }
                        drawPath(curve, color = PlayheadWhite, style = Stroke(width = 4.5f))

                        // Alight Motion Ringed Circular Tangent Handles
                        drawCircle(DeepBlack, radius = 13f, center = c1)
                        drawCircle(NeonPink, radius = 13f, center = c1, style = Stroke(width = 3.5f))
                        drawCircle(Color.White, radius = 4f, center = c1)

                        drawCircle(DeepBlack, radius = 13f, center = c2)
                        drawCircle(AmTealBright, radius = 13f, center = c2, style = Stroke(width = 3.5f))
                        drawCircle(Color.White, radius = 4f, center = c2)
                    }
                }
            }

            // Alight Motion Easing Preset Strip
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                val presets = listOf(
                    "Linear" to floatArrayOf(0f, 0f, 1f, 1f),
                    "Ease Out" to floatArrayOf(0.16f, 1f, 0.30f, 1f),
                    "Ease InOut" to floatArrayOf(0.83f, 0f, 0.17f, 1f),
                    "Overshoot" to floatArrayOf(0.34f, 1.32f, 0.64f, 1f),
                    "Velocity Whip" to floatArrayOf(0.08f, 0.96f, 0.18f, 1f)
                )
                presets.forEach { (name, pts) ->
                    AmSegmentChip(
                        label = name,
                        selected = false,
                        onClick = {
                            p1x = pts[0]; p1y = pts[1]; p2x = pts[2]; p2y = pts[3]
                            onUpdateBezierHandles(p1x, p1y, p2x, p2y)
                        }
                    )
                }
            }

            // Procedural Math Expression Card
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = SurfaceDark,
                modifier = Modifier
                    .widthIn(max = 460.dp)
                    .fillMaxWidth()
                    .border(1.dp, OutlineSubtle, RoundedCornerShape(12.dp))
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "fx Procedural Expression Engine",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                    OutlinedTextField(
                        value = expressionText,
                        onValueChange = {
                            expressionText = it
                            onUpdateExpression(it)
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
                        listOf(
                            "Clear" to "",
                            "KeyFlow Spring" to "spring(3, 4, 65)",
                            "KeyFlow Pendulum" to "pendulum(2.2, 35)",
                            "Sine Wave" to "sin(time * 4) * 50",
                            "Oscillate / Wiggle" to "wiggle(5, 30)",
                            "BPM Beat Pulse" to "pulse(128)"
                        ).forEach { (label, expr) ->
                            AmSegmentChip(
                                label = label,
                                selected = expressionText == expr && expr.isNotEmpty(),
                                onClick = {
                                    expressionText = expr
                                    onUpdateExpression(expr)
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}
