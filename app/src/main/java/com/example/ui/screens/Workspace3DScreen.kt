@file:OptIn(ExperimentalMaterial3Api::class)

package com.example.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CenterFocusStrong
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.ViewInAr
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.data.model.LayerType
import com.example.data.model.Primitive3DType
import com.example.engine.AudioSpectrumSnapshot
import com.example.ui.components.AmActionTileButton
import com.example.ui.components.AmPrimaryCtaButton
import com.example.ui.components.AmSegmentChip
import com.example.ui.components.AmSubToolRailButton
import com.example.ui.components.AmTickWheelScrubber
import com.example.ui.components.GlPreviewCanvas
import com.example.ui.theme.AmBgDark
import com.example.ui.theme.AmTeal
import com.example.ui.theme.AmTealBright
import com.example.ui.theme.OutlineSubtle
import com.example.ui.theme.SurfaceDark
import com.example.ui.theme.TextSecondary
import com.example.viewmodel.StudioWorkspaceState

@Composable
fun Workspace3DScreen(
    state: StudioWorkspaceState,
    audioSpectrum: AudioSpectrumSnapshot,
    onOrbitCamera: (yawDelta: Float, pitchDelta: Float, zoomFactor: Float) -> Unit,
    onAdd3DLayer: () -> Unit,
    onSelectPrimitive: (String, Primitive3DType, String) -> Unit,
    onBack: () -> Unit
) {
    val project = state.activeProject
    val active3DLayer = project.layers.firstOrNull {
        it.id == state.selectedLayerId && it.type == LayerType.MODEL_3D
    } ?: project.layers.firstOrNull { it.type == LayerType.MODEL_3D }

    val modelPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null && active3DLayer != null) {
            onSelectPrimitive(active3DLayer.id, Primitive3DType.IMPORTED_MESH, uri.toString())
        }
    }

    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing,
        containerColor = AmBgDark,
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack, modifier = Modifier.testTag("workspace3d_back_button")) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back to Editor")
                    }
                },
                title = {
                    Column {
                        Text(
                            text = "3D Camera & Object Rig",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "Yaw ${state.cameraYawDeg.toInt()}° • Pitch ${state.cameraPitchDeg.toInt()}° • ${String.format("%.1f", state.cameraDistance)}m",
                            style = MaterialTheme.typography.labelSmall,
                            color = AmTealBright
                        )
                    }
                },
                actions = {
                    AmPrimaryCtaButton(
                        text = "3D MESH",
                        icon = Icons.Default.Add,
                        onClick = onAdd3DLayer,
                        testTag = "add_3d_object_button",
                        modifier = Modifier.padding(end = 10.dp)
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = SurfaceDark)
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // 3D Viewport + Right Vertical Alight Motion Camera Angle Rail
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) {
                GlPreviewCanvas(
                    project = project,
                    playheadMs = state.playheadMs,
                    audioSnapshot = audioSpectrum,
                    canvasZoom = 1f,
                    canvasPanX = 0f,
                    canvasPanY = 0f,
                    cameraYawDeg = state.cameraYawDeg,
                    cameraPitchDeg = state.cameraPitchDeg,
                    cameraDistance = state.cameraDistance,
                    show3DGridOverlay = true,
                    onViewportGesture = { z, px, py ->
                        onOrbitCamera(px * 0.3f, -py * 0.3f, z)
                    },
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(end = 62.dp)
                        .pointerInput(Unit) {
                            detectTransformGestures { _, pan, zoom, _ ->
                                onOrbitCamera(pan.x * 0.35f, -pan.y * 0.35f, zoom)
                            }
                        }
                )

                Column(
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .clip(RoundedCornerShape(10.dp))
                        .background(SurfaceDark)
                        .border(1.dp, OutlineSubtle, RoundedCornerShape(10.dp))
                        .padding(4.dp),
                    verticalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    AmSubToolRailButton(
                        icon = Icons.Default.ViewInAr,
                        label = "Orbit",
                        selected = true,
                        onClick = { onOrbitCamera(25f, 0f, 1f) }
                    )
                    AmSubToolRailButton(
                        icon = Icons.Default.CenterFocusStrong,
                        label = "Front",
                        selected = false,
                        onClick = {
                            onOrbitCamera(-state.cameraYawDeg, -state.cameraPitchDeg, 1f)
                        }
                    )
                    AmSubToolRailButton(
                        icon = Icons.Default.Public,
                        label = "Iso",
                        selected = false,
                        onClick = {
                            onOrbitCamera(35f - state.cameraYawDeg, 24f - state.cameraPitchDeg, 1f)
                        }
                    )
                }
            }

            // Alight Motion Dark 3D Inspector Panel
            Card(
                colors = CardDefaults.cardColors(containerColor = SurfaceDark),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, OutlineSubtle, RoundedCornerShape(14.dp))
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        text = "3D Object Geometry & Mesh Import",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                    if (active3DLayer != null) {
                        Row(
                            modifier = Modifier.horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Primitive3DType.entries.forEach { prim ->
                                AmSegmentChip(
                                    label = prim.label,
                                    selected = active3DLayer.primitive3D == prim,
                                    onClick = { onSelectPrimitive(active3DLayer.id, prim, "") }
                                )
                            }
                        }

                        AmActionTileButton(
                            icon = Icons.Default.FileOpen,
                            label = "Import .OBJ / .GLB / .GLTF Mesh File",
                            accentColor = AmTeal,
                            selected = active3DLayer.primitive3D == Primitive3DType.IMPORTED_MESH,
                            onClick = { modelPicker.launch("*/*") },
                            modifier = Modifier.fillMaxWidth()
                        )
                    } else {
                        Text(
                            text = "No 3D layer in project yet. Tap '+ 3D MESH' above to add a hardware-accelerated 3D object.",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary
                        )
                    }

                    AmTickWheelScrubber(
                        value = state.cameraDistance,
                        minValue = 2.0f,
                        maxValue = 12.0f,
                        unit = "m",
                        label = "Camera Z Dolly Distance",
                        onValueChange = { target ->
                            val ratio = if (target > 0f) state.cameraDistance / target else 1f
                            onOrbitCamera(0f, 0f, ratio)
                        },
                        testTag = "camera_dolly_scrubber"
                    )
                }
            }
        }
    }
}
