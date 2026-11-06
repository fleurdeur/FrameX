package com.example.ui.screens

import android.content.Intent
import android.os.SystemClock
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.example.data.model.LayerType
import com.example.data.preferences.PreviewQuality
import com.example.engine.AepMediaResolver
import com.example.engine.AssetMatchStatus
import com.example.engine.AudioSpectrumSnapshot
import com.example.engine.EffectsCatalog
import com.example.engine.HardwareVideoPreviewEngine
import com.example.engine.ImportEffectStatus
import com.example.engine.ImportMediaAssetSlot
import com.example.engine.ImportedEffectRecord
import com.example.engine.PluginPackManager
import com.example.engine.StudioPlaybackClock
import com.example.ui.components.GlPreviewCanvas
import com.example.ui.theme.AmTeal
import com.example.ui.theme.AmTealBright
import com.example.ui.theme.OutlineSubtle
import com.example.ui.theme.SurfaceCard
import com.example.ui.theme.SurfaceDark
import com.example.ui.theme.SurfaceElevated
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import com.example.viewmodel.StudioViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import java.util.Locale

private val SelectedMediaBorderYellow = Color(0xFFFFD60A)
private val MissingWarningAmber = Color(0xFFFF9F0A)
private val MissingWarningRed = Color(0xFFFF453A)
private val PortedSuccessGreen = Color(0xFF32D74B)

/**
 * Import Preview Screen ("Template editor" flow before entering the Project):
 *
 * - Top bar: Back button on top-left (`import_preview_back_button`), "Next" button on top-right (`import_preview_next_button`).
 * - Short "Allow access to all files" (`MANAGE_EXTERNAL_STORAGE`) explanation card if missing assets need storage scan,
 *   with clean fallback to manual relink picker if denied.
 * - Live OpenGL ES 2.0 Preview Player with scrubber and start / current / duration time labels.
 * - "Replace media" horizontal row of thumbnail cards:
 *   - Media type icon (Video / Image / Audio) + duration badge (`02.5s`)
 *   - Normal look when auto-matched, warning state when missing, and a `#FFD60A` yellow border on the selected card
 *   - Tapping a card selects it and opens the system picker to swap/relink that asset
 * - Effects List showing every imported effect as `Ported`, `Replaced`, or `Unsupported`,
 *   with an inline "Replace" picker to assign a ported `.kfpack` or built-in effect.
 * - Clicking "Next" commits the project with all relinked assets and replacement effects applied.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImportPreviewScreen(
    viewModel: StudioViewModel
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val installedSuites by viewModel.installedPluginSuites.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    val session = state.pendingImportSession
    BackHandler {
        viewModel.cancelPendingImportSession()
    }

    if (session == null) {
        LaunchedEffect(Unit) {
            viewModel.cancelPendingImportSession()
        }
        return
    }

    // Re-check MANAGE_EXTERNAL_STORAGE automatically when returning from Android Settings
    DisposableEffect(lifecycleOwner, session.id) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME && AepMediaResolver.hasAllFilesAccess(context)) {
                if (session.mediaSlots.any { it.status == AssetMatchStatus.MISSING }) {
                    viewModel.onAllFilesPermissionReturned(context)
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    val previewProj = session.previewProject
    val totalDurMs = previewProj.effectiveEditDurationMs().coerceAtLeast(500L)

    var isPreviewPlaying by remember(session.id) { mutableStateOf(false) }
    var previewPlayheadMs by remember(session.id) { mutableLongStateOf(0L) }
    var previewRevision by remember(session.previewProject) { mutableLongStateOf(System.nanoTime()) }
    LaunchedEffect(session.previewProject) {
        previewRevision++
    }

    var selectedSlotId by remember(session.id) {
        mutableStateOf(
            session.mediaSlots.firstOrNull { it.status == AssetMatchStatus.MISSING }?.id
                ?: session.mediaSlots.firstOrNull()?.id
        )
    }
    var pendingRelinkSlotId by remember { mutableStateOf<String?>(null) }
    var effectRecordToReplace by remember { mutableStateOf<ImportedEffectRecord?>(null) }

    val mediaRelinkLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        val slotId = pendingRelinkSlotId
        if (uri != null && slotId != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }
            viewModel.relinkImportMediaSlot(context, slotId, uri)
        }
    }

    // Isolated preview clock loop (cleanly stopped on dispose or when clicking Next/Back)
    DisposableEffect(Unit) {
        onDispose {
            StudioPlaybackClock.pause(0L)
            HardwareVideoPreviewEngine.pauseAll()
        }
    }

    LaunchedEffect(isPreviewPlaying, totalDurMs, previewProj) {
        if (!isPreviewPlaying) {
            StudioPlaybackClock.pause(previewPlayheadMs)
            HardwareVideoPreviewEngine.pauseAll()
            return@LaunchedEffect
        }
        val originWall = SystemClock.elapsedRealtime()
        val originPlayhead = if (previewPlayheadMs >= totalDurMs - 30L) 0L else previewPlayheadMs
        StudioPlaybackClock.start(originPlayhead, totalDurMs, isLooping = true)
        HardwareVideoPreviewEngine.syncProjectVideos(
            context = context,
            project = previewProj,
            playheadMs = originPlayhead,
            isPlaying = true,
            rapidScrubbing = false,
            forceExactSeek = true
        )
        while (isActive && isPreviewPlaying) {
            val elapsed = originPlayhead + (SystemClock.elapsedRealtime() - originWall)
            val looped = if (totalDurMs > 0L) elapsed % totalDurMs else 0L
            previewPlayheadMs = looped
            delay(33L)
        }
    }

    val missingCount = session.mediaSlots.count { it.status == AssetMatchStatus.MISSING }
    val unsupportedFxCount = session.effectRecords.count { it.status == ImportEffectStatus.UNSUPPORTED }
    val groupLayerCount = previewProj.layers.count { it.type == LayerType.GROUP }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(SurfaceDark)
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .navigationBarsPadding()
    ) {
        // 1. Top Bar: Back on top-left, Title in center, "Next" on top-right
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = {
                    isPreviewPlaying = false
                    viewModel.cancelPendingImportSession()
                },
                modifier = Modifier
                    .size(48.dp)
                    .testTag("import_preview_back_button")
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back",
                    tint = TextPrimary
                )
            }

            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 8.dp)
            ) {
                Text(
                    text = "Template editor",
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "${session.fileKind.label} • ${previewProj.width}×${previewProj.height} @ ${previewProj.fps}fps" +
                        if (groupLayerCount > 0) " • $groupLayerCount Group(s)" else "",
                    style = MaterialTheme.typography.labelSmall.copy(color = TextSecondary),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Button(
                onClick = {
                    isPreviewPlaying = false
                    viewModel.confirmPendingImportSession()
                },
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = AmTeal,
                    contentColor = Color.White
                ),
                contentPadding = PaddingValues(horizontal = 18.dp, vertical = 10.dp),
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .testTag("import_preview_next_button")
            ) {
                Text(
                    text = "Next",
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 14.sp
                )
            }
        }

        // Background asset scan progress bar (never blocks UI)
        if (state.isImportingFile) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(SurfaceCard)
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = state.importProgressLabel.ifBlank { "Resolving media assets..." },
                        style = MaterialTheme.typography.labelSmall,
                        color = AmTealBright
                    )
                    Text(
                        text = "${(state.importProgressFraction * 100).toInt()}%",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextSecondary
                    )
                }
                Spacer(modifier = Modifier.height(6.dp))
                LinearProgressIndicator(
                    progress = { state.importProgressFraction.coerceIn(0.05f, 1f) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp)),
                    color = AmTeal,
                    trackColor = OutlineSubtle
                )
            }
        }

        // Main scrollable content
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .weight(1f),
            contentPadding = PaddingValues(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // Optional "Allow access to all files" (MANAGE_EXTERNAL_STORAGE) short explanation card
            if (state.showAllFilesPermissionSheet && !AepMediaResolver.hasAllFilesAccess(context)) {
                item(key = "storage_permission_card") {
                    AllFilesAccessExplanationCard(
                        missingCount = missingCount,
                        onGrantAccess = {
                            runCatching {
                                context.startActivity(AepMediaResolver.buildManageAllFilesSettingsIntent(context))
                            }.onFailure {
                                runCatching {
                                    context.startActivity(AepMediaResolver.buildFallbackManageAllFilesIntent())
                                }
                            }
                        },
                        onUseManualPicker = {
                            viewModel.dismissAllFilesPermissionPrompt()
                        }
                    )
                }
            }

            // 2. Preview Player + Scrubber + Start / Current / Duration Time Labels
            item(key = "preview_player_card") {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    color = SurfaceCard,
                    shape = RoundedCornerShape(18.dp),
                    tonalElevation = 2.dp
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(228.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(Color(0xFF05070C))
                                .border(1.dp, OutlineSubtle, RoundedCornerShape(12.dp)),
                            contentAlignment = Alignment.Center
                        ) {
                            GlPreviewCanvas(
                                project = previewProj,
                                playheadMs = previewPlayheadMs,
                                isPlaying = isPreviewPlaying,
                                previewQuality = PreviewQuality.HALF,
                                previewFpsCap = 60,
                                isLooping = true,
                                projectRevision = previewRevision,
                                usePlaybackCache = false,
                                audioSnapshot = AudioSpectrumSnapshot(),
                                canvasZoom = 1f,
                                canvasPanX = 0f,
                                canvasPanY = 0f,
                                cameraYawDeg = 26f,
                                cameraPitchDeg = 18f,
                                cameraDistance = 4.2f,
                                selectedLayerId = null,
                                show3DGridOverlay = false,
                                activeCameraPreviewEnabled = true,
                                onViewportGesture = { _, _, _ -> },
                                modifier = Modifier.fillMaxSize()
                            )
                        }

                        Spacer(modifier = Modifier.height(10.dp))

                        // Scrubber + Play/Pause + Start / Current / Total Duration Time Labels
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            IconButton(
                                onClick = { isPreviewPlaying = !isPreviewPlaying },
                                modifier = Modifier
                                    .size(48.dp)
                                    .clip(CircleShape)
                                    .background(AmTeal.copy(alpha = 0.16f))
                                    .testTag("import_preview_play_button")
                            ) {
                                Icon(
                                    imageVector = if (isPreviewPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                                    contentDescription = if (isPreviewPlaying) "Pause preview" else "Play preview",
                                    tint = AmTealBright
                                )
                            }

                            Spacer(modifier = Modifier.width(10.dp))

                            Column(modifier = Modifier.weight(1f)) {
                                Slider(
                                    value = previewPlayheadMs.toFloat().coerceIn(0f, totalDurMs.toFloat()),
                                    onValueChange = { nextVal ->
                                        isPreviewPlaying = false
                                        val nextMs = nextVal.toLong().coerceIn(0L, totalDurMs)
                                        previewPlayheadMs = nextMs
                                        StudioPlaybackClock.setPausedPlayhead(nextMs)
                                        HardwareVideoPreviewEngine.syncProjectVideos(
                                            context = context,
                                            project = previewProj,
                                            playheadMs = nextMs,
                                            isPlaying = false,
                                            rapidScrubbing = true
                                        )
                                    },
                                    valueRange = 0f..totalDurMs.toFloat().coerceAtLeast(1f),
                                    colors = SliderDefaults.colors(
                                        thumbColor = AmTealBright,
                                        activeTrackColor = AmTeal,
                                        inactiveTrackColor = OutlineSubtle
                                    ),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .testTag("import_preview_scrubber")
                                )

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(
                                        text = "00:00",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = TextMuted
                                    )
                                    Text(
                                        text = formatImportClockLabel(previewPlayheadMs),
                                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                                        color = AmTealBright
                                    )
                                    Text(
                                        text = formatImportClockLabel(totalDurMs),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = TextSecondary
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // 3. "Replace media" Horizontal Row of Thumbnail Cards
            item(key = "replace_media_section") {
                Column(
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = "Replace media",
                                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                                color = TextPrimary
                            )
                            Surface(
                                color = if (missingCount > 0) MissingWarningAmber.copy(alpha = 0.18f) else AmTeal.copy(alpha = 0.16f),
                                shape = RoundedCornerShape(999.dp)
                            ) {
                                Text(
                                    text = if (missingCount > 0) {
                                        "$missingCount missing • ${session.mediaSlots.size} total"
                                    } else {
                                        "${session.mediaSlots.size} matched"
                                    },
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (missingCount > 0) MissingWarningAmber else AmTealBright,
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp)
                                )
                            }
                        }

                        if (session.mediaSlots.isNotEmpty()) {
                            TextButton(
                                onClick = { viewModel.onAllFilesPermissionReturned(context) },
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.Refresh,
                                    contentDescription = "Rescan media folders",
                                    tint = AmTealBright,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = "Rescan",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = AmTealBright
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    if (session.mediaSlots.isEmpty()) {
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp),
                            color = SurfaceCard,
                            shape = RoundedCornerShape(14.dp)
                        ) {
                            Text(
                                text = "No external footage assets required for this file.",
                                style = MaterialTheme.typography.bodySmall,
                                color = TextSecondary,
                                modifier = Modifier.padding(14.dp)
                            )
                        }
                    } else {
                        LazyRow(
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("replace_media_row"),
                            contentPadding = PaddingValues(horizontal = 16.dp),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            items(
                                items = session.mediaSlots,
                                key = { it.id }
                            ) { slot ->
                                val isSelected = slot.id == selectedSlotId
                                ImportMediaThumbnailCard(
                                    slot = slot,
                                    isSelected = isSelected,
                                    onClick = {
                                        selectedSlotId = slot.id
                                        pendingRelinkSlotId = slot.id
                                        val mimeFilters = when (slot.mediaType) {
                                            LayerType.VIDEO -> arrayOf("video/*", "image/*")
                                            LayerType.IMAGE -> arrayOf("image/*", "video/*")
                                            LayerType.AUDIO -> arrayOf("audio/*", "video/*")
                                            else -> arrayOf("*/*")
                                        }
                                        mediaRelinkLauncher.launch(mimeFilters)
                                    }
                                )
                            }
                        }
                    }
                }
            }

            // 4. Effects Porting & Replacement List (Ported / Replaced / Unsupported)
            item(key = "effects_header") {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Imported effects",
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                        color = TextPrimary
                    )
                    Surface(
                        color = if (unsupportedFxCount > 0) MissingWarningAmber.copy(alpha = 0.18f) else PortedSuccessGreen.copy(alpha = 0.16f),
                        shape = RoundedCornerShape(999.dp)
                    ) {
                        Text(
                            text = if (session.effectRecords.isEmpty()) {
                                "0 effects"
                            } else if (unsupportedFxCount > 0) {
                                "$unsupportedFxCount unsupported • ${session.effectRecords.size} total"
                            } else {
                                "All ${session.effectRecords.size} ported"
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = if (unsupportedFxCount > 0) MissingWarningAmber else PortedSuccessGreen,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp)
                        )
                    }
                }
            }

            if (session.effectRecords.isEmpty()) {
                item(key = "empty_effects_card") {
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp),
                        color = SurfaceCard,
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        Text(
                            text = "No layer effects found in this import.",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary,
                            modifier = Modifier.padding(14.dp)
                        )
                    }
                }
            } else {
                items(
                    items = session.effectRecords,
                    key = { it.id }
                ) { record ->
                    ImportedEffectStatusCard(
                        record = record,
                        onPickReplacement = {
                            effectRecordToReplace = record
                        }
                    )
                }
            }
        }
    }

    // Replacement Effect Picker Sheet
    val activeRecord = effectRecordToReplace
    if (activeRecord != null) {
        ReplacementEffectPickerSheet(
            record = activeRecord,
            installedSuitesCount = installedSuites.size,
            onDismiss = { effectRecordToReplace = null },
            onSelectEffect = { replacementId ->
                viewModel.replaceImportedEffect(activeRecord.id, replacementId)
                effectRecordToReplace = null
            }
        )
    }
}

@Composable
private fun AllFilesAccessExplanationCard(
    missingCount: Int,
    onGrantAccess: () -> Unit,
    onUseManualPicker: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .testTag("all_files_permission_card"),
        color = SurfaceElevated,
        shape = RoundedCornerShape(16.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, AmTeal.copy(alpha = 0.45f))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Filled.FolderOpen,
                    contentDescription = "All files access",
                    tint = AmTealBright,
                    modifier = Modifier.size(20.dp)
                )
                Text(
                    text = "Auto-locate original project media",
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                    color = TextPrimary
                )
            }
            Text(
                text = "After Effects (.aep) and desktop project files store original Windows/Mac paths instead of embedded clips" +
                    (if (missingCount > 0) " ($missingCount asset(s) currently unlinked)." else ".") +
                    " Allow \"All files access\" so FramePollution can automatically match filenames in the project folder, Downloads, DCIM, Movies, Pictures, and Music — or relink clips manually below.",
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End)
            ) {
                OutlinedButton(
                    onClick = onUseManualPicker,
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .testTag("relink_manually_fallback_button")
                ) {
                    Text("Relink Manually", color = TextSecondary, fontSize = 12.sp)
                }
                Button(
                    onClick = onGrantAccess,
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = AmTeal, contentColor = Color.White),
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .testTag("grant_all_files_button")
                ) {
                    Text("Allow all files", fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
                }
            }
        }
    }
}

@Composable
private fun ImportMediaThumbnailCard(
    slot: ImportMediaAssetSlot,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    val isMissing = slot.status == AssetMatchStatus.MISSING
    val borderColor = when {
        isSelected -> SelectedMediaBorderYellow
        isMissing -> MissingWarningRed.copy(alpha = 0.85f)
        else -> OutlineSubtle
    }
    val borderWidth = if (isSelected) 2.5.dp else 1.2.dp

    Column(
        modifier = Modifier
            .width(104.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(SurfaceCard)
            .border(borderWidth, borderColor, RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .testTag("media_slot_card_${slot.id}"),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(84.dp)
                .clip(RoundedCornerShape(topStart = 13.dp, topEnd = 13.dp))
                .background(
                    if (isMissing) {
                        Brush.linearGradient(
                            listOf(Color(0xFF2B1618), Color(0xFF171219))
                        )
                    } else {
                        Brush.linearGradient(
                            listOf(Color(0xFF162238), Color(0xFF0E1422))
                        )
                    }
                )
        ) {
            if (!isMissing && slot.resolvedUri.isNotBlank() && slot.mediaType != LayerType.AUDIO) {
                AsyncImage(
                    model = slot.resolvedUri,
                    contentDescription = slot.displayName,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Icon(
                    imageVector = when {
                        isMissing -> Icons.Filled.WarningAmber
                        slot.mediaType == LayerType.VIDEO -> Icons.Filled.Videocam
                        slot.mediaType == LayerType.AUDIO -> Icons.Filled.AudioFile
                        else -> Icons.Filled.Image
                    },
                    contentDescription = slot.displayName,
                    tint = if (isMissing) MissingWarningAmber else AmTealBright.copy(alpha = 0.85f),
                    modifier = Modifier
                        .size(28.dp)
                        .align(Alignment.Center)
                )
            }

            // Top-left media type badge icon
            Box(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(6.dp)
                    .size(22.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color.Black.copy(alpha = 0.68f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = when (slot.mediaType) {
                        LayerType.VIDEO -> Icons.Filled.Videocam
                        LayerType.AUDIO -> Icons.Filled.AudioFile
                        else -> Icons.Filled.Image
                    },
                    contentDescription = slot.mediaType.label,
                    tint = Color.White,
                    modifier = Modifier.size(13.dp)
                )
            }

            // Top-right Missing / Relinked status indicator
            if (isMissing) {
                Surface(
                    color = MissingWarningRed.copy(alpha = 0.90f),
                    shape = RoundedCornerShape(6.dp),
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(6.dp)
                ) {
                    Text(
                        text = "Missing",
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp)
                    )
                }
            } else if (slot.status == AssetMatchStatus.MANUAL_RELINKED) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(6.dp)
                        .size(18.dp)
                        .clip(CircleShape)
                        .background(PortedSuccessGreen.copy(alpha = 0.88f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Filled.CheckCircle,
                        contentDescription = "Relinked",
                        tint = Color.Black,
                        modifier = Modifier.size(12.dp)
                    )
                }
            }

            // Bottom-right duration badge (`02.5s`)
            Surface(
                color = Color.Black.copy(alpha = 0.72f),
                shape = RoundedCornerShape(6.dp),
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(6.dp)
            ) {
                Text(
                    text = String.format(Locale.US, "%04.1fs", (slot.durationMs.coerceAtLeast(100L)) / 1000f),
                    fontSize = 10.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White,
                    modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp)
                )
            }
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 7.dp)
        ) {
            Text(
                text = slot.displayName,
                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Medium),
                color = if (isMissing) MissingWarningAmber else TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = if (isMissing) "Tap to relink" else "Tap to swap",
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                color = TextMuted,
                maxLines = 1
            )
        }
    }
}

@Composable
private fun ImportedEffectStatusCard(
    record: ImportedEffectRecord,
    onPickReplacement: () -> Unit
) {
    val (badgeColor, statusText) = when (record.status) {
        ImportEffectStatus.PORTED -> PortedSuccessGreen to "Ported"
        ImportEffectStatus.REPLACED -> AmTealBright to "Replaced"
        ImportEffectStatus.UNSUPPORTED -> MissingWarningAmber to "Unsupported"
    }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .testTag("imported_effect_row_${record.id}"),
        color = SurfaceCard,
        shape = RoundedCornerShape(14.dp),
        border = androidx.compose.foundation.BorderStroke(
            width = 1.dp,
            color = if (record.status == ImportEffectStatus.UNSUPPORTED) {
                MissingWarningAmber.copy(alpha = 0.45f)
            } else {
                OutlineSubtle
            }
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(badgeColor.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = if (record.status == ImportEffectStatus.UNSUPPORTED) {
                        Icons.Filled.WarningAmber
                    } else {
                        Icons.Filled.AutoFixHigh
                    },
                    contentDescription = statusText,
                    tint = badgeColor,
                    modifier = Modifier.size(18.dp)
                )
            }

            Column(modifier = Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = record.originalDisplayName,
                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                        color = TextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Surface(
                        color = badgeColor.copy(alpha = 0.16f),
                        shape = RoundedCornerShape(6.dp)
                    ) {
                        Text(
                            text = statusText,
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold, fontSize = 10.sp),
                            color = badgeColor,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }
                val subtitle = when (record.status) {
                    ImportEffectStatus.PORTED -> "Layer: ${record.layerName} → ${record.mappedEffectName ?: record.mappedEffectId}"
                    ImportEffectStatus.REPLACED -> "Layer: ${record.layerName} → Replaced with ${record.mappedEffectName}"
                    ImportEffectStatus.UNSUPPORTED -> "Layer: ${record.layerName} • Skipped (layer kept intact)"
                }
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = TextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            OutlinedButton(
                onClick = onPickReplacement,
                shape = RoundedCornerShape(10.dp),
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .testTag("replace_effect_button_${record.id}")
            ) {
                Icon(
                    imageVector = Icons.Filled.SwapHoriz,
                    contentDescription = "Choose replacement effect",
                    tint = AmTealBright,
                    modifier = Modifier.size(15.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = if (record.status == ImportEffectStatus.UNSUPPORTED) "Replace" else "Change",
                    fontSize = 12.sp,
                    color = AmTealBright
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReplacementEffectPickerSheet(
    record: ImportedEffectRecord,
    installedSuitesCount: Int,
    onDismiss: () -> Unit,
    onSelectEffect: (String) -> Unit
) {
    var search by remember { mutableStateOf("") }
    val allChoices = remember(installedSuitesCount) {
        val pluginChoices = PluginPackManager.activePluginEffects().map { p ->
            Triple(p.id, p.name, "${p.category} • ${p.suiteName} v${p.version}")
        }
        val builtInChoices = EffectsCatalog.allEffects.map { d ->
            Triple(d.id, d.name, "${d.category.title} • Built-in")
        }
        pluginChoices + builtInChoices
    }
    val filtered = remember(search, allChoices) {
        val q = search.trim()
        if (q.isEmpty()) allChoices
        else allChoices.filter { (_, name, sub) ->
            name.contains(q, ignoreCase = true) || sub.contains(q, ignoreCase = true)
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = SurfaceElevated
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 520.dp)
                .padding(horizontal = 16.dp)
                .padding(bottom = 20.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Replace \"${record.originalDisplayName}\"",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        color = TextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "Target layer: ${record.layerName}",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextSecondary
                    )
                }
                IconButton(onClick = onDismiss, modifier = Modifier.size(48.dp)) {
                    Icon(Icons.Filled.Close, contentDescription = "Close", tint = TextSecondary)
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            OutlinedTextField(
                value = search,
                onValueChange = { search = it },
                placeholder = { Text("Search ported .kfpack or built-in effects...", color = TextMuted, fontSize = 13.sp) },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = "Search", tint = TextSecondary) },
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = AmTeal,
                    unfocusedBorderColor = OutlineSubtle,
                    focusedTextColor = TextPrimary,
                    unfocusedTextColor = TextPrimary
                ),
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(10.dp))
            HorizontalDivider(color = OutlineSubtle)

            LazyColumn(
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                items(
                    items = filtered,
                    key = { it.first }
                ) { (fxId, fxName, fxMeta) ->
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .clickable { onSelectEffect(fxId) },
                        color = SurfaceCard,
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Extension,
                                contentDescription = fxName,
                                tint = AmTealBright,
                                modifier = Modifier.size(18.dp)
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = fxName,
                                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                                    color = TextPrimary
                                )
                                Text(
                                    text = fxMeta,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = TextSecondary
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun formatImportClockLabel(ms: Long): String {
    val totalSec = (ms.coerceAtLeast(0L)) / 1000L
    val mins = totalSec / 60L
    val secs = totalSec % 60L
    val tenths = ((ms.coerceAtLeast(0L)) % 1000L) / 100L
    return String.format(Locale.US, "%02d:%02d.%d", mins, secs, tenths)
}
