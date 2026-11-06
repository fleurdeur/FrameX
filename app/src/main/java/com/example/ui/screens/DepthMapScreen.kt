@file:OptIn(ExperimentalMaterial3Api::class)

package com.example.ui.screens

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.ArrowLeft
import androidx.compose.material.icons.filled.ArrowRight
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Compare
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.FastRewind
import androidx.compose.material.icons.filled.FileDownloadDone
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.data.model.Project
import com.example.depthengine.DepthAdjustParams
import com.example.depthengine.DepthColormap
import com.example.depthengine.DepthEngine
import com.example.depthengine.DepthModelVariant
import com.example.depthengine.RawDepthBuffer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.Locale

enum class DepthImportMode(val label: String) {
    IMAGE_LAYER("Add as Depth Image Layer"),
    EFFECT_DEPTH_INPUT("Attach as Effect Depth Input")
}

@Composable
fun DepthMapScreen(
    depthEngine: DepthEngine,
    projects: List<Project>,
    activeProjectId: String?,
    onImportDepthIntoProjects: (depthPngFile: File, targetProjectIds: List<String>, mode: DepthImportMode) -> Unit,
    onStatusMessage: (String) -> Unit = {},
    bottomBar: @Composable (() -> Unit)? = null
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val selectedVariant by depthEngine.selectedVariant.collectAsStateWithLifecycle()
    val modelStates by depthEngine.modelStates.collectAsStateWithLifecycle()

    var sourceUri by remember { mutableStateOf<Uri?>(null) }
    var sourceBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var sourceKey by remember { mutableStateOf("") }
    var rawDepthBuffer by remember { mutableStateOf<RawDepthBuffer?>(null) }
    var renderedPreview by remember { mutableStateOf<Bitmap?>(null) }
    var isComputingDepth by remember { mutableStateOf(false) }
    var showOriginalBefore by remember { mutableStateOf(false) }

    var isVideoMedia by remember { mutableStateOf(false) }
    var mediaDurationMs by remember { mutableStateOf(5000L) }
    var currentPlayheadMs by remember { mutableStateOf(0L) }
    var trimStartMs by remember { mutableStateOf(0L) }
    var trimEndMs by remember { mutableStateOf(5000L) }
    var isPlaying by remember { mutableStateOf(false) }

    var adjustParams by remember { mutableStateOf(DepthAdjustParams()) }
    var pendingNonCommercialVariant by remember { mutableStateOf<DepthModelVariant?>(null) }
    var activeDownloadJob by remember { mutableStateOf<Job?>(null) }
    var showProjectExportSheet by remember { mutableStateOf(false) }

    // Release heavy model buffers when leaving the Depth Map screen (Section 6.2)
    DisposableEffect(Unit) {
        onDispose {
            activeDownloadJob?.cancel()
            depthEngine.releaseSession()
        }
    }

    // Playback ticker for video preview
    LaunchedEffect(isPlaying, mediaDurationMs, trimStartMs, trimEndMs) {
        if (!isPlaying) return@LaunchedEffect
        while (isPlaying) {
            kotlinx.coroutines.delay(100L)
            currentPlayheadMs = (currentPlayheadMs + 100L).let { next ->
                if (next > trimEndMs) trimStartMs else next
            }
        }
    }

    // Decode frame when video playhead moves
    LaunchedEffect(sourceUri, currentPlayheadMs, isVideoMedia) {
        val uri = sourceUri ?: return@LaunchedEffect
        if (!isVideoMedia) return@LaunchedEffect
        val frameBmp = decodeMediaOrVideoFrame(context, uri, currentPlayheadMs)
        if (frameBmp != null) {
            sourceBitmap = frameBmp
            sourceKey = "${uri}_${currentPlayheadMs}_${frameBmp.width}x${frameBmp.height}"
        }
    }

    val mediaPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            scope.launch {
                sourceUri = uri
                val (loaded, duration, isVid) = decodeMediaDetails(context, uri)
                if (loaded != null) {
                    sourceBitmap = loaded
                    isVideoMedia = isVid
                    mediaDurationMs = duration.coerceAtLeast(1000L)
                    currentPlayheadMs = 0L
                    trimStartMs = 0L
                    trimEndMs = mediaDurationMs
                    sourceKey = "${uri}_0_${loaded.width}x${loaded.height}"
                    rawDepthBuffer = null
                } else {
                    onStatusMessage("Could not decode selected media")
                }
            }
        }
    }

    // Compute monocular depth buffer off the main thread whenever source or model variant changes
    LaunchedEffect(sourceKey, selectedVariant, sourceBitmap) {
        val src = sourceBitmap ?: return@LaunchedEffect
        isComputingDepth = true
        val buf = depthEngine.computeDepthBuffer(
            sourceKey = "$sourceKey|${selectedVariant.id}",
            sourceBitmap = src,
            variant = selectedVariant
        )
        rawDepthBuffer = buf
        isComputingDepth = false
    }

    // Re-render colorized/adjusted depth preview instantaneously off main thread without re-running inference!
    LaunchedEffect(rawDepthBuffer, adjustParams, sourceBitmap) {
        val src = sourceBitmap ?: return@LaunchedEffect
        val buf = rawDepthBuffer ?: return@LaunchedEffect
        val bmp = depthEngine.renderDepthPreview(
            sourceBitmap = src,
            depthBuffer = buf,
            params = adjustParams
        )
        renderedPreview = bmp
    }

    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = "Depth Map Studio",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "${selectedVariant.displayName} • ${selectedVariant.licenseBadge}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                actions = {
                    FilledTonalButton(
                        onClick = {
                            mediaPicker.launch(
                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)
                            )
                        },
                        modifier = Modifier
                            .padding(end = 8.dp)
                            .testTag("depth_pick_media_button")
                    ) {
                        Icon(Icons.Default.AddPhotoAlternate, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Pick Media")
                    }
                }
            )
        },
        bottomBar = { bottomBar?.invoke() }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // 1. Live Before / After Depth Map Stage
            Card(
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                modifier = Modifier.fillMaxWidth()
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(250.dp)
                        .background(MaterialTheme.colorScheme.surfaceContainerLowest),
                    contentAlignment = Alignment.Center
                ) {
                    val displayBmp = if (showOriginalBefore) sourceBitmap else (renderedPreview ?: sourceBitmap)
                    if (displayBmp != null && !displayBmp.isRecycled) {
                        Image(
                            bitmap = displayBmp.asImageBitmap(),
                            contentDescription = if (showOriginalBefore) "Original source frame" else "Generated depth map preview",
                            contentScale = ContentScale.Fit,
                            modifier = Modifier
                                .fillMaxSize()
                                .testTag("depth_preview_image")
                        )
                    } else {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(24.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Layers,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(40.dp)
                            )
                            Text(
                                text = "Select a photo or video clip to generate a live Depth Anything V2 map",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center
                            )
                            Button(
                                onClick = {
                                    mediaPicker.launch(
                                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)
                                    )
                                },
                                modifier = Modifier.height(48.dp)
                            ) {
                                Text("Choose Image or Video")
                            }
                        }
                    }

                    if (isComputingDepth) {
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.88f)
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.5.dp)
                                Text("Estimating monocular depth...", style = MaterialTheme.typography.labelMedium)
                            }
                        }
                    }

                    if (sourceBitmap != null) {
                        FilterChip(
                            selected = showOriginalBefore,
                            onClick = { showOriginalBefore = !showOriginalBefore },
                            leadingIcon = {
                                Icon(Icons.Default.Compare, contentDescription = null, modifier = Modifier.size(16.dp))
                            },
                            label = { Text(if (showOriginalBefore) "Before (Source)" else "After (Depth)") },
                            modifier = Modifier
                                .align(Alignment.BottomEnd)
                                .padding(10.dp)
                                .testTag("depth_before_after_toggle")
                        )
                    }
                }
            }

            // Timeline & Video Scrubbing Bar (with White Arrow Trim Handles at Start & End)
            if (sourceUri != null) {
                Card(
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                IconButton(
                                    onClick = {
                                        currentPlayheadMs = (currentPlayheadMs - 1000L).coerceAtLeast(trimStartMs)
                                    },
                                    modifier = Modifier.size(48.dp)
                                ) {
                                    Icon(Icons.Default.FastRewind, contentDescription = "Backward 1s")
                                }
                                IconButton(
                                    onClick = { isPlaying = !isPlaying },
                                    modifier = Modifier.size(48.dp)
                                ) {
                                    Icon(
                                        imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                        contentDescription = if (isPlaying) "Pause" else "Play"
                                    )
                                }
                                IconButton(
                                    onClick = {
                                        currentPlayheadMs = (currentPlayheadMs + 1000L).coerceAtMost(trimEndMs)
                                    },
                                    modifier = Modifier.size(48.dp)
                                ) {
                                    Icon(Icons.Default.FastForward, contentDescription = "Forward 1s")
                                }
                            }
                            Text(
                                text = String.format(
                                    Locale.US,
                                    "%02d:%04.1f / %02d:%04.1f",
                                    currentPlayheadMs / 60000,
                                    (currentPlayheadMs % 60000) / 1000f,
                                    mediaDurationMs / 60000,
                                    (mediaDurationMs % 60000) / 1000f
                                ),
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }

                        // Timeline Scrubbing Slider with Start/End White Arrow Trim Handles
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // Left Start Trim White Arrow Handle
                            IconButton(
                                onClick = {
                                    trimStartMs = (trimStartMs - 500L).coerceAtLeast(0L)
                                    if (currentPlayheadMs < trimStartMs) currentPlayheadMs = trimStartMs
                                },
                                modifier = Modifier
                                    .size(48.dp)
                                    .background(Color.White.copy(alpha = 0.15f), RoundedCornerShape(8.dp))
                            ) {
                                Icon(
                                    imageVector = Icons.Default.ArrowLeft,
                                    contentDescription = "Trim Start Handle",
                                    tint = Color.White
                                )
                            }
                            Spacer(Modifier.width(6.dp))
                            Slider(
                                value = currentPlayheadMs.toFloat(),
                                onValueChange = { currentPlayheadMs = it.toLong() },
                                valueRange = trimStartMs.toFloat()..trimEndMs.toFloat().coerceAtLeast(trimStartMs.toFloat() + 1f),
                                modifier = Modifier.weight(1f)
                            )
                            Spacer(Modifier.width(6.dp))
                            // Right End Trim White Arrow Handle
                            IconButton(
                                onClick = {
                                    trimEndMs = (trimEndMs + 500L).coerceAtMost(mediaDurationMs)
                                    if (currentPlayheadMs > trimEndMs) currentPlayheadMs = trimEndMs
                                },
                                modifier = Modifier
                                    .size(48.dp)
                                    .background(Color.White.copy(alpha = 0.15f), RoundedCornerShape(8.dp))
                            ) {
                                Icon(
                                    imageVector = Icons.Default.ArrowRight,
                                    contentDescription = "Trim End Handle",
                                    tint = Color.White
                                )
                            }
                        }
                    }
                }
            }

            // 2. Import / Paste into Multiple Projects CTA
            Button(
                onClick = { showProjectExportSheet = true },
                enabled = renderedPreview != null && projects.isNotEmpty(),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .testTag("depth_import_to_projects_button")
            ) {
                Icon(Icons.Default.Layers, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Import / Paste into Project(s)")
            }

            // 3. Depth Anything V2 Model Picker (Apache-2.0 Small + CC-BY-NC-4.0 Base/Large with warning)
            Text(
                text = "Depth Anything V2 Model",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold
            )
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                DepthModelVariant.entries.forEach { variant ->
                    val st = modelStates[variant]
                    val isSelected = selectedVariant == variant
                    Card(
                        shape = RoundedCornerShape(14.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = if (isSelected) {
                                MaterialTheme.colorScheme.secondaryContainer
                            } else {
                                MaterialTheme.colorScheme.surfaceContainer
                            }
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(
                                width = if (isSelected) 1.5.dp else 1.dp,
                                color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                                shape = RoundedCornerShape(14.dp)
                            )
                            .clickable {
                                if (!variant.isCommercialUseAllowed && selectedVariant != variant) {
                                    pendingNonCommercialVariant = variant
                                } else {
                                    depthEngine.selectVariant(variant)
                                }
                            }
                            .testTag("depth_model_card_${variant.id}")
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = variant.displayName,
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Text(
                                        text = "${variant.licenseBadge} • ~${variant.approxSizeMb} MB",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = if (variant.isCommercialUseAllowed) {
                                            MaterialTheme.colorScheme.primary
                                        } else {
                                            MaterialTheme.colorScheme.tertiary
                                        }
                                    )
                                }
                                if (st?.isInstalled == true) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(
                                            imageVector = Icons.Default.FileDownloadDone,
                                            contentDescription = "Installed",
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(20.dp)
                                        )
                                        IconButton(
                                            onClick = { depthEngine.deleteVariant(variant) },
                                            modifier = Modifier.size(48.dp)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.DeleteOutline,
                                                contentDescription = "Delete downloaded weights"
                                            )
                                        }
                                    }
                                } else if (st?.isDownloading == true) {
                                    TextButton(onClick = { activeDownloadJob?.cancel() }) {
                                        Text("Cancel")
                                    }
                                } else {
                                    OutlinedButton(
                                        onClick = {
                                            if (!variant.isCommercialUseAllowed) {
                                                pendingNonCommercialVariant = variant
                                            } else {
                                                activeDownloadJob?.cancel()
                                                activeDownloadJob = scope.launch {
                                                    depthEngine.downloadVariant(variant)
                                                }
                                            }
                                        },
                                        modifier = Modifier.height(48.dp)
                                    ) {
                                        Icon(
                                            imageVector = if (st?.errorMessage != null) Icons.Default.Refresh else Icons.Default.Download,
                                            contentDescription = null,
                                            modifier = Modifier.size(16.dp)
                                        )
                                        Spacer(Modifier.width(6.dp))
                                        Text(if (st?.errorMessage != null) "Retry" else "Download")
                                    }
                                }
                            }
                            if (st?.isDownloading == true) {
                                LinearProgressIndicator(
                                    progress = { st.progress },
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                            if (!st?.errorMessage.isNullOrBlank()) {
                                Text(
                                    text = st?.errorMessage.orEmpty(),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.error
                                )
                            }
                        }
                    }
                }
            }

            // 4. Live Depth Customization Controls (Section 6.3)
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        text = "Live Depth Customization",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        DepthColormap.entries.forEach { cmap ->
                            FilterChip(
                                selected = adjustParams.colormap == cmap,
                                onClick = { adjustParams = adjustParams.copy(colormap = cmap) },
                                label = { Text(cmap.label) },
                                modifier = Modifier.testTag("depth_cmap_${cmap.name}")
                            )
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Invert Near / Far", style = MaterialTheme.typography.bodySmall)
                        Switch(
                            checked = adjustParams.invert,
                            onCheckedChange = { adjustParams = adjustParams.copy(invert = it) },
                            modifier = Modifier.testTag("depth_invert_switch")
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Auto-Normalize Range", style = MaterialTheme.typography.bodySmall)
                        Switch(
                            checked = adjustParams.normalize,
                            onCheckedChange = { adjustParams = adjustParams.copy(normalize = it) },
                            modifier = Modifier.testTag("depth_normalize_switch")
                        )
                    }

                    DepthParamSlider(
                        label = "Strength",
                        value = adjustParams.strength,
                        range = 0f..2f,
                        onChange = { adjustParams = adjustParams.copy(strength = it) }
                    )
                    DepthParamSlider(
                        label = "Near Clip",
                        value = adjustParams.nearClip,
                        range = 0f..0.90f,
                        onChange = {
                            adjustParams = adjustParams.copy(
                                nearClip = it,
                                farClip = adjustParams.farClip.coerceAtLeast(it + 0.05f)
                            )
                        }
                    )
                    DepthParamSlider(
                        label = "Far Clip",
                        value = adjustParams.farClip,
                        range = 0.10f..1f,
                        onChange = {
                            adjustParams = adjustParams.copy(
                                farClip = it.coerceAtLeast(adjustParams.nearClip + 0.05f)
                            )
                        }
                    )
                    DepthParamSlider(
                        label = "Contrast",
                        value = adjustParams.contrast,
                        range = 0.25f..2.5f,
                        onChange = { adjustParams = adjustParams.copy(contrast = it) }
                    )
                    DepthParamSlider(
                        label = "Gamma",
                        value = adjustParams.gamma,
                        range = 0.30f..2.5f,
                        onChange = { adjustParams = adjustParams.copy(gamma = it) }
                    )
                    DepthParamSlider(
                        label = "Edge Blur",
                        value = adjustParams.blurRadius,
                        range = 0f..12f,
                        onChange = { adjustParams = adjustParams.copy(blurRadius = it) }
                    )
                    DepthParamSlider(
                        label = "Depth Opacity",
                        value = adjustParams.opacity,
                        range = 0f..1f,
                        onChange = { adjustParams = adjustParams.copy(opacity = it) }
                    )
                }
            }
        }
    }

    // CC-BY-NC-4.0 Non-Commercial License Warning Dialog (Section 6.2)
    pendingNonCommercialVariant?.let { variant ->
        AlertDialog(
            onDismissRequest = { pendingNonCommercialVariant = null },
            icon = { Icon(Icons.Default.WarningAmber, contentDescription = null) },
            title = { Text("Non-Commercial Model License") },
            text = {
                Text(
                    "${variant.displayName} is licensed under ${variant.licenseBadge}. " +
                        "Outputs using Base or Large weights are restricted to non-commercial research or personal projects. " +
                        "Use Depth Anything V2 Small (Apache-2.0) for unrestricted commercial projects."
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        pendingNonCommercialVariant = null
                        depthEngine.selectVariant(variant)
                        if (!depthEngine.isVariantInstalled(variant)) {
                            activeDownloadJob?.cancel()
                            activeDownloadJob = scope.launch {
                                depthEngine.downloadVariant(variant)
                            }
                        }
                    },
                    modifier = Modifier.testTag("depth_accept_nc_license_button")
                ) {
                    Text("I Understand — Enable")
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingNonCommercialVariant = null }) {
                    Text("Cancel")
                }
            }
        )
    }

    // Multi-Project Import / Paste Picker Sheet (Section 6.4)
    if (showProjectExportSheet) {
        val selectedProjIds = remember(projects, activeProjectId) {
            mutableStateListOf<String>().apply {
                val initial = activeProjectId ?: projects.firstOrNull()?.id
                if (initial != null) add(initial)
            }
        }
        var importMode by remember { mutableStateOf(DepthImportMode.IMAGE_LAYER) }

        ModalBottomSheet(onDismissRequest = { showProjectExportSheet = false }) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "Import Depth Map into Project(s)",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    DepthImportMode.entries.forEach { mode ->
                        FilterChip(
                            selected = importMode == mode,
                            onClick = { importMode = mode },
                            label = { Text(mode.label) }
                        )
                    }
                }

                HorizontalDivider()
                Text(
                    text = "Select target projects (${selectedProjIds.size} selected):",
                    style = MaterialTheme.typography.labelMedium
                )

                projects.forEach { proj ->
                    val checked = proj.id in selectedProjIds
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                            .clickable {
                                if (checked) selectedProjIds.remove(proj.id) else selectedProjIds.add(proj.id)
                            },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(
                            checked = checked,
                            onCheckedChange = {
                                if (it) {
                                    if (proj.id !in selectedProjIds) selectedProjIds.add(proj.id)
                                } else {
                                    selectedProjIds.remove(proj.id)
                                }
                            }
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = "${proj.name} (${proj.width}x${proj.height})",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f)
                        )
                        if (proj.id == activeProjectId) {
                            Icon(
                                imageVector = Icons.Default.Check,
                                contentDescription = "Active project",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }

                Button(
                    onClick = {
                        val bmp = renderedPreview ?: return@Button
                        val targetIds = selectedProjIds.toList()
                        showProjectExportSheet = false
                        scope.launch {
                            val outFile = withContext(Dispatchers.IO) {
                                val dir = File(context.filesDir, "depth_maps").apply { mkdirs() }
                                val file = File(dir, "depth_${System.currentTimeMillis()}.png")
                                FileOutputStream(file).use { out ->
                                    bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
                                }
                                file
                            }
                            onImportDepthIntoProjects(outFile, targetIds, importMode)
                        }
                    },
                    enabled = selectedProjIds.isNotEmpty() && renderedPreview != null,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .testTag("depth_confirm_multi_project_import")
                ) {
                    Text("Import into ${selectedProjIds.size} Project${if (selectedProjIds.size == 1) "" else "s"}")
                }
                Spacer(Modifier.height(16.dp))
            }
        }
    }
}

@Composable
private fun DepthParamSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onChange: (Float) -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(label, style = MaterialTheme.typography.labelMedium)
            Text(
                String.format(Locale.US, "%.2f", value),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary
            )
        }
        Slider(
            value = value.coerceIn(range.start, range.endInclusive),
            onValueChange = onChange,
            valueRange = range
        )
    }
}

private data class DecodedMediaInfo(val bitmap: Bitmap?, val durationMs: Long, val isVideo: Boolean)

private suspend fun decodeMediaDetails(context: Context, uri: Uri): DecodedMediaInfo = withContext(Dispatchers.IO) {
    val mime = context.contentResolver.getType(uri).orEmpty()
    if (mime.startsWith("video/")) {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, uri)
            val durationStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
            val durationMs = durationStr?.toLongOrNull() ?: 5000L
            val frame = retriever.getFrameAtTime(0L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            return@withContext DecodedMediaInfo(frame, durationMs, true)
        } catch (_: Throwable) {
            return@withContext DecodedMediaInfo(null, 5000L, true)
        } finally {
            runCatching { retriever.release() }
        }
    }
    val bmp = decodeMediaOrVideoFrame(context, uri, 0L)
    DecodedMediaInfo(bmp, 5000L, false)
}

private suspend fun decodeMediaOrVideoFrame(context: Context, uri: Uri, timeMs: Long = 0L): Bitmap? = withContext(Dispatchers.IO) {
    val mime = context.contentResolver.getType(uri).orEmpty()
    if (mime.startsWith("video/")) {
        val retriever = MediaMetadataRetriever()
        return@withContext try {
            retriever.setDataSource(context, uri)
            retriever.getFrameAtTime(timeMs * 1000L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
        } catch (_: Throwable) {
            null
        } finally {
            runCatching { retriever.release() }
        }
    }
    runCatching {
        context.contentResolver.openInputStream(uri)?.use { stream ->
            val raw = BitmapFactory.decodeStream(stream) ?: return@use null
            val maxDim = 768
            if (raw.width > maxDim || raw.height > maxDim) {
                val scale = maxDim.toFloat() / maxOf(raw.width, raw.height).toFloat()
                Bitmap.createScaledBitmap(
                    raw,
                    (raw.width * scale).toInt().coerceAtLeast(16),
                    (raw.height * scale).toInt().coerceAtLeast(16),
                    true
                )
            } else {
                raw
            }
        }
    }.getOrNull()
}
