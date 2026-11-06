@file:OptIn(ExperimentalMaterial3Api::class)

package com.example.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.ViewInAr
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.R
import com.example.data.model.AspectRatioPreset
import com.example.data.model.Project
import com.example.data.preferences.StudioPreferences
import com.example.depthengine.DepthEngine
import com.example.scriptengine.InstalledPlugin
import com.example.ui.components.AmActionTileButton
import com.example.ui.components.AmBottomNavigationBar
import com.example.ui.components.AmHomeTab
import com.example.ui.components.AmPrimaryCtaButton
import com.example.ui.components.AmRatioOutlineCard
import com.example.ui.components.AmSegmentChip
import com.example.ui.components.AmSquareIconButton
import java.io.File
import com.example.ui.theme.AmBgDark
import com.example.ui.theme.AmTeal
import com.example.ui.theme.AmTealBright
import com.example.ui.theme.DeepBlack
import com.example.ui.theme.ElectricBlue
import com.example.ui.theme.NeonPink
import com.example.ui.theme.OutlineSubtle
import com.example.ui.theme.PanelDark
import com.example.ui.theme.RigGreen
import com.example.ui.theme.SurfaceDark
import com.example.ui.theme.TextSecondary
import com.example.viewmodel.UiState

@Composable
fun HomeScreen(
    cloudProjectsState: UiState<List<Project>>,
    onCreateProject: (
        name: String,
        preset: AspectRatioPreset,
        width: Int,
        height: Int,
        fps: Int,
        durationSec: Int,
        bpm: Int,
        backgroundColor: String
    ) -> Unit,
    onOpenProject: (Project) -> Unit,
    onDeleteProject: (String) -> Unit,
    onSignOut: () -> Unit,
    defaultPreferences: StudioPreferences = StudioPreferences(),
    isImportingFile: Boolean = false,
    importProgressFraction: Float = 0f,
    importProgressLabel: String = "",
    onOpenSettings: () -> Unit = {},
    onOpenPlugins: () -> Unit = {},
    onImportProjectXml: (Uri) -> Unit = {},
    installedAePlugins: List<InstalledPlugin> = emptyList(),
    onImportAePluginUri: (Uri) -> Unit = {},
    onToggleAePluginEnabled: (String, Boolean) -> Unit = { _, _ -> },
    onToggleAePluginShowInTimeline: (String, Boolean) -> Unit = { _, _ -> },
    onToggleAePluginPinToAddMenu: (String, Boolean) -> Unit = { _, _ -> },
    onToggleAePluginFileNetwork: (String, Boolean) -> Unit = { _, _ -> },
    onUseAePlugin: (InstalledPlugin) -> Unit = {},
    onRunAePluginProbe: (String) -> Unit = {},
    onClearAePluginData: (String) -> Unit = {},
    onUninstallAePlugin: (String) -> Unit = {},
    depthEngine: DepthEngine? = null,
    activeProjectId: String? = null,
    onImportDepthIntoProjects: (File, List<String>, DepthImportMode) -> Unit = { _, _, _ -> },
    onStatusMessage: (String) -> Unit = {}
) {
    var showCreateDialog by remember { mutableStateOf(false) }
    var activeTab by remember { mutableStateOf(AmHomeTab.HOME) }
    val importUniversalLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            onImportProjectXml(uri)
        }
    }
    val launchUniversalPicker = {
        importUniversalLauncher.launch(arrayOf("*/*"))
    }
    val sharedBottomBar: @Composable () -> Unit = {
        AmBottomNavigationBar(
            selectedTab = activeTab,
            onSelectTab = { activeTab = it },
            onCreateProjectClick = { showCreateDialog = true }
        )
    }

    if (activeTab == AmHomeTab.PLUGINS) {
        AePluginsBrowserScreen(
            installedPlugins = installedAePlugins,
            onImportPluginUri = onImportAePluginUri,
            onToggleEnabled = onToggleAePluginEnabled,
            onToggleShowInTimeline = onToggleAePluginShowInTimeline,
            onTogglePinToAddMenu = onToggleAePluginPinToAddMenu,
            onToggleFileNetworkAllowed = onToggleAePluginFileNetwork,
            onUsePlugin = onUseAePlugin,
            onRunProbe = onRunAePluginProbe,
            onClearData = onClearAePluginData,
            onUninstallPlugin = onUninstallAePlugin,
            onStatusMessage = onStatusMessage,
            bottomBar = sharedBottomBar
        )
    } else if (activeTab == AmHomeTab.DEPTH_MAP && depthEngine != null) {
        val knownProjects = (cloudProjectsState as? UiState.Success)?.data.orEmpty()
        DepthMapScreen(
            depthEngine = depthEngine,
            projects = knownProjects,
            activeProjectId = activeProjectId,
            onImportDepthIntoProjects = onImportDepthIntoProjects,
            onStatusMessage = onStatusMessage,
            bottomBar = sharedBottomBar
        )
    } else {
        Scaffold(
            contentWindowInsets = WindowInsets.safeDrawing,
            containerColor = AmBgDark,
            topBar = {
                TopAppBar(
                    title = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(32.dp)
                                    .clip(CircleShape)
                                    .background(PanelDark)
                                    .border(1.2.dp, AmTeal, CircleShape),
                                contentAlignment = Alignment.Center
                            ) {
                                Canvas(modifier = Modifier.size(16.dp)) {
                                    val cx = size.width / 2f
                                    val cy = size.height / 2f
                                    val p = Path().apply {
                                        moveTo(cx, 1f)
                                        lineTo(size.width - 1f, cy)
                                        lineTo(cx, size.height - 1f)
                                        lineTo(1f, cy)
                                        close()
                                    }
                                    drawPath(p, AmTealBright)
                                }
                            }
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(
                                text = stringResource(id = R.string.app_name),
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    },
                    actions = {
                        AmSquareIconButton(
                            icon = Icons.Default.Settings,
                            contentDescription = stringResource(id = R.string.settings_button_desc),
                            onClick = onOpenSettings,
                            testTag = "home_settings_button",
                            modifier = Modifier.padding(end = 6.dp)
                        )
                        AmSquareIconButton(
                            icon = Icons.AutoMirrored.Filled.Logout,
                            contentDescription = stringResource(id = R.string.sign_out),
                            onClick = onSignOut,
                            testTag = "sign_out_button",
                            modifier = Modifier.padding(end = 10.dp)
                        )
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = SurfaceDark)
                )
            },
            bottomBar = sharedBottomBar
        ) { innerPadding ->
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            if (isImportingFile) {
                item(key = "background_import_progress_card") {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = PanelDark,
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(1.dp, AmTeal.copy(alpha = 0.7f), RoundedCornerShape(12.dp))
                            .testTag("home_import_progress_card")
                    ) {
                        Column(
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                text = importProgressLabel.ifBlank { "Scanning project & resolving media..." },
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = AmTealBright
                            )
                            LinearProgressIndicator(
                                progress = { importProgressFraction.coerceIn(0.05f, 1f) },
                                color = AmTealBright,
                                trackColor = SurfaceDark,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(5.dp)
                                    .clip(RoundedCornerShape(3.dp))
                            )
                        }
                    }
                }
            }

            if (activeTab == AmHomeTab.HOME) {
                item {
                    AmStudioHeroBanner(
                        onQuickCreate = { showCreateDialog = true },
                        onImportXml = launchUniversalPicker
                    )
                }
            }

            if (activeTab == AmHomeTab.HOME || activeTab == AmHomeTab.PROJECTS) {
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.CloudDone,
                                contentDescription = null,
                                tint = AmTeal,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = stringResource(id = R.string.recent_projects),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        if (activeTab == AmHomeTab.PROJECTS) {
                            AmSegmentChip(
                                label = "Import File",
                                selected = false,
                                onClick = launchUniversalPicker,
                                testTag = "projects_tab_import_xml_button"
                            )
                        }
                    }
                }

                when (cloudProjectsState) {
                    is UiState.Loading -> {
                        item {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(32.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                CircularProgressIndicator(color = AmTeal)
                            }
                        }
                    }
                    is UiState.Error -> {
                        item {
                            Card(
                                colors = CardDefaults.cardColors(containerColor = PanelDark),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    text = cloudProjectsState.message,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.padding(16.dp)
                                )
                            }
                        }
                    }
                    is UiState.Success -> {
                        if (cloudProjectsState.data.isEmpty()) {
                            item {
                                EmptyProjectsCard(onCreateClick = { showCreateDialog = true })
                            }
                        } else {
                            items(cloudProjectsState.data, key = { it.id }) { proj ->
                                AmProjectListRow(
                                    project = proj,
                                    onOpen = { onOpenProject(proj) },
                                    onDelete = { onDeleteProject(proj.id) }
                                )
                            }
                        }
                    }
                }
            }
        }
        }
    }

    if (showCreateDialog) {
        AmNewProjectScreenDialog(
            defaultPreferences = defaultPreferences,
            onDismiss = { showCreateDialog = false },
            onConfirm = { name, preset, w, h, fps, durSec, bpm, bg ->
                showCreateDialog = false
                onCreateProject(name, preset, w, h, fps, durSec, bpm, bg)
            }
        )
    }
}



@Composable
private fun AmStudioHeroBanner(
    onQuickCreate: () -> Unit,
    onImportXml: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(176.dp)
            .clip(RoundedCornerShape(14.dp))
            .border(1.dp, OutlineSubtle, RoundedCornerShape(14.dp))
    ) {
        Image(
            painter = painterResource(id = R.drawable.img_studio_hero),
            contentDescription = "FramePollution Motion Compositor Banner",
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize()
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.horizontalGradient(
                        listOf(
                            Color(0xF00C0E12),
                            Color(0xCC12151C),
                            Color(0x4412151C)
                        )
                    )
                )
        )
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(18.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = stringResource(id = R.string.app_name),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground
                )
                Text(
                    text = "60fps GPU Compositor • .aep • .kfproject • .xml • .kfpack • .ffx",
                    style = MaterialTheme.typography.labelSmall,
                    color = AmTealBright
                )
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                AmPrimaryCtaButton(
                    text = "New Project",
                    icon = Icons.Default.Add,
                    onClick = onQuickCreate,
                    testTag = "hero_create_project_button"
                )
                Surface(
                    onClick = onImportXml,
                    shape = RoundedCornerShape(12.dp),
                    color = PanelDark.copy(alpha = 0.92f),
                    modifier = Modifier
                        .height(48.dp)
                        .border(1.2.dp, AmTeal.copy(alpha = 0.72f), RoundedCornerShape(12.dp))
                        .testTag("home_import_xml_button")
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.FileOpen,
                            contentDescription = "Import Project or Pack",
                            tint = AmTealBright,
                            modifier = Modifier.size(18.dp)
                        )
                        Text(
                            text = "Import File",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold,
                            color = AmTealBright
                        )
                    }
                }
            }
        }
    }
}



@Composable
private fun EmptyProjectsCard(onCreateClick: () -> Unit) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, OutlineSubtle, RoundedCornerShape(14.dp)),
        shape = RoundedCornerShape(14.dp),
        color = SurfaceDark
    ) {
        Column(
            modifier = Modifier.padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                imageVector = Icons.Default.FolderOpen,
                contentDescription = null,
                tint = AmTeal,
                modifier = Modifier.size(36.dp)
            )
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = stringResource(id = R.string.empty_projects_tip),
                style = MaterialTheme.typography.bodyMedium,
                color = TextSecondary
            )
            Spacer(modifier = Modifier.height(14.dp))
            AmPrimaryCtaButton(
                text = "New Project",
                icon = Icons.Default.Add,
                onClick = onCreateClick
            )
        }
    }
}

/**
 * Alight Motion "Projects" Tab Row: Dark proportional aspect-ratio preview frame,
 * crisp title, resolution/FPS badge, and delete button.
 */
@Composable
private fun AmProjectListRow(
    project: Project,
    onOpen: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen)
            .border(1.dp, OutlineSubtle, RoundedCornerShape(12.dp))
            .testTag("project_item_card_${project.id}"),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = SurfaceDark)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f)
            ) {
                Box(
                    modifier = Modifier
                        .size(54.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(DeepBlack)
                        .border(1.dp, OutlineSubtle, RoundedCornerShape(10.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Canvas(modifier = Modifier.size(34.dp)) {
                        val ratio = (project.width.toFloat().coerceAtLeast(1f) / project.height.toFloat().coerceAtLeast(1f))
                            .coerceIn(0.45f, 2.3f)
                        val maxS = size.minDimension * 0.92f
                        val rw = if (ratio >= 1f) maxS else maxS * ratio
                        val rh = if (ratio >= 1f) maxS / ratio else maxS
                        drawRoundRect(
                            color = AmTeal,
                            topLeft = Offset((size.width - rw) / 2f, (size.height - rh) / 2f),
                            size = Size(rw, rh),
                            cornerRadius = CornerRadius(4f, 4f),
                            style = Stroke(width = 2.4f)
                        )
                    }
                    Icon(
                        imageVector = Icons.Default.PlayArrow,
                        contentDescription = "Open project",
                        tint = AmTealBright,
                        modifier = Modifier.size(16.dp)
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    Text(
                        text = project.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(3.dp))
                    Text(
                        text = "${project.width}×${project.height} • ${project.fps}fps",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextSecondary
                    )
                }
            }
            IconButton(
                onClick = onDelete,
                modifier = Modifier.testTag("delete_project_${project.id}")
            ) {
                Icon(
                    imageVector = Icons.Default.DeleteOutline,
                    contentDescription = "Delete project",
                    tint = TextSecondary
                )
            }
        }
    }
}

/**
 * Alight Motion "New Project" Full Configuration Sheet / Dialog:
 * - Editable Project Name at top
 * - Proportional Aspect Ratio Wireframe Boxes (16:9, 9:16, 4:5, 1:1, 4:3, 21:9, Custom)
 * - Resolution Tier Pills (720p, 1080p, 4K UHD) + Custom Width × Height px inputs
 * - Frame Rate Pills (24, 25, 30, 48, 60, 120 fps) + Custom FPS input
 * - Background selector (Black, Dark Gray, Transparent, White)
 * - Full-width Teal "CREATE PROJECT" button at bottom
 */
@Composable
private fun AmNewProjectScreenDialog(
    defaultPreferences: StudioPreferences = StudioPreferences(),
    onDismiss: () -> Unit,
    onConfirm: (
        name: String,
        preset: AspectRatioPreset,
        width: Int,
        height: Int,
        fps: Int,
        durationSec: Int,
        bpm: Int,
        backgroundColor: String
    ) -> Unit
) {
    var name by remember { mutableStateOf("New Project 1") }
    var selectedPreset by remember { mutableStateOf(defaultPreferences.defaultAspectPreset) }
    var widthText by remember { mutableStateOf(defaultPreferences.defaultAspectPreset.defaultWidth.toString()) }
    var heightText by remember { mutableStateOf(defaultPreferences.defaultAspectPreset.defaultHeight.toString()) }
    var fpsText by remember { mutableStateOf(defaultPreferences.defaultProjectFps.toString()) }
    var durationSecText by remember { mutableStateOf(defaultPreferences.defaultProjectDurationSec.toString()) }
    var bpmText by remember { mutableStateOf("128") }
    var resolutionScale by remember { mutableIntStateOf(1080) }
    var selectedBg by remember { mutableStateOf(defaultPreferences.defaultCanvasBackground) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .widthIn(max = 480.dp)
                .fillMaxWidth(0.94f)
                .border(1.dp, OutlineSubtle, RoundedCornerShape(18.dp)),
            shape = RoundedCornerShape(18.dp),
            color = SurfaceDark
        ) {
            Column(
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Header Row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "New Project",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                    AmSquareIconButton(
                        icon = Icons.Default.Close,
                        contentDescription = "Cancel",
                        onClick = onDismiss
                    )
                }

                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Name") },
                    singleLine = true,
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("project_name_input")
                )

                Text("Ratio", style = MaterialTheme.typography.labelLarge, color = TextSecondary)
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    AspectRatioPreset.entries.forEach { preset ->
                        AmRatioOutlineCard(
                            preset = preset,
                            selected = selectedPreset == preset,
                            onClick = {
                                selectedPreset = preset
                                if (preset != AspectRatioPreset.CUSTOM) {
                                    val factor = when (resolutionScale) {
                                        720 -> 0.6667f
                                        2160 -> 2.0f
                                        else -> 1.0f
                                    }
                                    widthText = ((preset.defaultWidth * factor).toInt() / 2 * 2).toString()
                                    heightText = ((preset.defaultHeight * factor).toInt() / 2 * 2).toString()
                                }
                            },
                            testTag = "aspect_chip_${preset.name}"
                        )
                    }
                }

                Text("Resolution", style = MaterialTheme.typography.labelLarge, color = TextSecondary)
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    listOf(720 to "720p", 1080 to "1080p", 2160 to "4K").forEach { (tier, label) ->
                        AmSegmentChip(
                            label = label,
                            selected = resolutionScale == tier && selectedPreset != AspectRatioPreset.CUSTOM,
                            onClick = {
                                resolutionScale = tier
                                val factor = when (tier) {
                                    720 -> 0.6667f
                                    2160 -> 2.0f
                                    else -> 1.0f
                                }
                                val base = if (selectedPreset == AspectRatioPreset.CUSTOM) AspectRatioPreset.LANDSCAPE_16_9 else selectedPreset
                                widthText = ((base.defaultWidth * factor).toInt() / 2 * 2).toString()
                                heightText = ((base.defaultHeight * factor).toInt() / 2 * 2).toString()
                            }
                        )
                    }
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = widthText,
                        onValueChange = {
                            widthText = it.filter(Char::isDigit)
                            selectedPreset = AspectRatioPreset.CUSTOM
                        },
                        label = { Text("Width") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier
                            .weight(1f)
                            .testTag("custom_width_input")
                    )
                    Icon(
                        imageVector = Icons.Default.Link,
                        contentDescription = null,
                        tint = AmTeal,
                        modifier = Modifier.size(18.dp)
                    )
                    OutlinedTextField(
                        value = heightText,
                        onValueChange = {
                            heightText = it.filter(Char::isDigit)
                            selectedPreset = AspectRatioPreset.CUSTOM
                        },
                        label = { Text("Height") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier
                            .weight(1f)
                            .testTag("custom_height_input")
                    )
                }

                Text("FPS", style = MaterialTheme.typography.labelLarge, color = TextSecondary)
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    listOf(24, 25, 30, 48, 60, 120).forEach { fpsPreset ->
                        AmSegmentChip(
                            label = "${fpsPreset}fps",
                            selected = fpsText == fpsPreset.toString(),
                            onClick = { fpsText = fpsPreset.toString() },
                            testTag = "fps_chip_$fpsPreset"
                        )
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = fpsText,
                        onValueChange = { fpsText = it.filter(Char::isDigit) },
                        label = { Text("FPS") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier
                            .weight(1f)
                            .testTag("custom_fps_input")
                    )
                    OutlinedTextField(
                        value = durationSecText,
                        onValueChange = { durationSecText = it.filter(Char::isDigit) },
                        label = { Text("Duration") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = bpmText,
                        onValueChange = { bpmText = it.filter(Char::isDigit) },
                        label = { Text("BPM") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.weight(1f)
                    )
                }

                // Background Canvas Selector (Alight Motion Row)
                Text("Background", style = MaterialTheme.typography.labelLarge, color = TextSecondary)
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    listOf("Black", "Dark Gray", "White", "Transparent").forEach { bgOpt ->
                        AmSegmentChip(
                            label = bgOpt,
                            selected = selectedBg == bgOpt,
                            onClick = { selectedBg = bgOpt }
                        )
                    }
                }

                Spacer(modifier = Modifier.height(4.dp))

                // Signature Full-Width Teal "CREATE PROJECT" Button
                AmPrimaryCtaButton(
                    text = stringResource(id = R.string.create_project),
                    onClick = {
                        val w = widthText.toIntOrNull() ?: 1920
                        val h = heightText.toIntOrNull() ?: 1080
                        val fps = fpsText.toIntOrNull() ?: 60
                        val dur = durationSecText.toIntOrNull() ?: 9
                        val bpm = bpmText.toIntOrNull() ?: 128
                        onConfirm(name, selectedPreset, w, h, fps, dur, bpm, selectedBg)
                    },
                    modifier = Modifier.fillMaxWidth(),
                    testTag = "confirm_create_project_button"
                )
            }
        }
    }
}
