@file:OptIn(ExperimentalMaterial3Api::class)

package com.example.ui.screens

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.engine.EffectDefinition
import com.example.engine.InstalledPluginEffect
import com.example.engine.InstalledPluginSuite
import com.example.engine.PluginPackManager
import com.example.engine.PluginParamDef
import com.example.engine.PluginParamType
import com.example.ui.components.AmSegmentChip
import com.example.ui.components.AmSquareIconButton
import com.example.ui.theme.AmBgDark
import com.example.ui.theme.AmTeal
import com.example.ui.theme.AmTealBright
import com.example.ui.theme.AudioCyan
import com.example.ui.theme.DeepBlack
import com.example.ui.theme.ElectricBlue
import com.example.ui.theme.NeonPink
import com.example.ui.theme.OutlineSubtle
import com.example.ui.theme.PanelDark
import com.example.ui.theme.PanelElevated
import com.example.ui.theme.RigGreen
import com.example.ui.theme.SurfaceDark
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary

@Composable
fun PluginManagerScreen(
    suites: List<InstalledPluginSuite>,
    shaderDiagnostics: Map<String, String>,
    onImportPackUri: (android.net.Uri) -> Unit,
    onExportSuiteUri: (String, android.net.Uri) -> Unit,
    onToggleSuiteEnabled: (String, Boolean) -> Unit,
    onRemoveSuite: (String) -> Unit,
    onRestoreStarterSuite: () -> Unit,
    onCreateCustomPlugin: (
        effectName: String,
        suiteName: String,
        author: String,
        category: String,
        expandBounds: Float,
        usesNativeMotionBlur: Boolean,
        glslSource: String,
        parameters: List<PluginParamDef>,
        extension: String
    ) -> Unit,
    onApplyPluginEffect: (EffectDefinition) -> Unit,
    onBack: () -> Unit
) {
    BackHandler(onBack = onBack)

    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current

    var selectedCategoryFilter by remember { mutableStateOf<String?>(null) }
    var inspectingEffect by remember { mutableStateOf<InstalledPluginEffect?>(null) }
    var showBuilderDialog by remember { mutableStateOf(false) }
    var pendingExportSuiteId by remember { mutableStateOf<String?>(null) }
    var copiedPromptFeedback by remember { mutableStateOf(false) }

    val importPackLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            onImportPackUri(uri)
        }
    }

    val exportPackLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri ->
        val suiteId = pendingExportSuiteId
        if (uri != null && suiteId != null) {
            onExportSuiteUri(suiteId, uri)
        }
        pendingExportSuiteId = null
    }

    val filteredSuites = remember(suites, selectedCategoryFilter) {
        if (selectedCategoryFilter == null) {
            suites
        } else {
            suites.mapNotNull { suite ->
                val matchingFx = suite.effects.filter {
                    it.category.equals(selectedCategoryFilter, ignoreCase = true)
                }
                if (matchingFx.isEmpty() && !suite.category.equals(selectedCategoryFilter, ignoreCase = true)) {
                    null
                } else {
                    suite.copy(effects = if (matchingFx.isNotEmpty()) matchingFx else suite.effects)
                }
            }
        }
    }

    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing,
        containerColor = AmBgDark,
        topBar = {
            TopAppBar(
                navigationIcon = {
                    AmSquareIconButton(
                        icon = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back",
                        onClick = onBack,
                        testTag = "plugin_manager_back_button",
                        modifier = Modifier.padding(start = 8.dp)
                    )
                },
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "Effect Suites",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = TextPrimary
                        )
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(AmTeal.copy(alpha = 0.16f))
                                .border(0.8.dp, AmTeal.copy(alpha = 0.5f), RoundedCornerShape(6.dp))
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = ".kfpack • .fppack",
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 10.sp
                                ),
                                color = AmTealBright
                            )
                        }
                    }
                },
                actions = {
                    OutlinedButton(
                        onClick = { showBuilderDialog = true },
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier
                            .height(34.dp)
                            .testTag("plugin_create_button")
                    ) {
                        Icon(Icons.Default.Code, contentDescription = null, tint = AmTealBright, modifier = Modifier.size(15.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Create", color = AmTealBright, style = MaterialTheme.typography.labelMedium)
                    }
                    Spacer(modifier = Modifier.width(6.dp))
                    Button(
                        onClick = { importPackLauncher.launch(arrayOf("*/*")) },
                        colors = ButtonDefaults.buttonColors(containerColor = AmTeal, contentColor = DeepBlack),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier
                            .padding(end = 10.dp)
                            .height(34.dp)
                            .testTag("plugin_import_button")
                    ) {
                        Icon(Icons.Default.FileUpload, contentDescription = null, modifier = Modifier.size(15.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Import", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelMedium)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = SurfaceDark)
            )
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentAlignment = Alignment.TopCenter
        ) {
            LazyColumn(
                modifier = Modifier
                    .widthIn(max = 700.dp)
                    .fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                item {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        AmSegmentChip(
                            label = "All (${suites.sumOf { it.effects.size }})",
                            selected = selectedCategoryFilter == null,
                            compact = true,
                            onClick = { selectedCategoryFilter = null }
                        )
                        PluginPackManager.OFFICIAL_CATEGORIES.forEach { cat ->
                            AmSegmentChip(
                                label = cat,
                                selected = selectedCategoryFilter == cat,
                                compact = true,
                                onClick = {
                                    selectedCategoryFilter = if (selectedCategoryFilter == cat) null else cat
                                },
                                testTag = "plugin_cat_chip_$cat"
                            )
                        }
                    }
                }

                item {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = PanelElevated,
                            modifier = Modifier
                                .border(0.8.dp, OutlineSubtle, RoundedCornerShape(8.dp))
                                .clickable {
                                    clipboard.setText(AnnotatedString(PluginPackManager.buildAiPromptTemplate()))
                                    copiedPromptFeedback = true
                                }
                                .testTag("copy_ai_plugin_prompt_button")
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.ContentCopy,
                                    contentDescription = "Copy AI Prompt",
                                    tint = AudioCyan,
                                    modifier = Modifier.size(14.dp)
                                )
                                Text(
                                    text = if (copiedPromptFeedback) "Copied Prompt" else "AI Prompt",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = TextPrimary
                                )
                            }
                        }

                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = PanelElevated,
                            modifier = Modifier
                                .border(0.8.dp, OutlineSubtle, RoundedCornerShape(8.dp))
                                .clickable(onClick = onRestoreStarterSuite)
                                .testTag("restore_starter_pack_button")
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.RestartAlt,
                                    contentDescription = "Starter Pack",
                                    tint = AmTealBright,
                                    modifier = Modifier.size(14.dp)
                                )
                                Text(
                                    text = "Starter Pack",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = TextPrimary
                                )
                            }
                        }
                    }
                }

                if (filteredSuites.isEmpty()) {
                    item {
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = SurfaceDark,
                            modifier = Modifier
                                .fillMaxWidth()
                                .border(1.dp, OutlineSubtle, RoundedCornerShape(12.dp))
                        ) {
                            Column(
                                modifier = Modifier.padding(24.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Extension,
                                    contentDescription = null,
                                    tint = TextMuted,
                                    modifier = Modifier.size(28.dp)
                                )
                                Text(
                                    text = "No .kfpack suites",
                                    style = MaterialTheme.typography.titleSmall,
                                    color = TextSecondary
                                )
                            }
                        }
                    }
                } else {
                    items(filteredSuites, key = { it.id }) { suite ->
                        KeyFlowSuiteCard(
                            suite = suite,
                            shaderDiagnostics = shaderDiagnostics,
                            onToggleEnabled = { onToggleSuiteEnabled(suite.id, it) },
                            onExport = {
                                pendingExportSuiteId = suite.id
                                val ext = suite.archiveExtension.ifBlank { ".kfpack" }
                                exportPackLauncher.launch("${suite.id}$ext")
                            },
                            onDelete = { onRemoveSuite(suite.id) },
                            onInspectShader = { inspectingEffect = it },
                            onApplyEffect = { fx ->
                                onApplyPluginEffect(PluginPackManager.toEffectDefinition(fx))
                            }
                        )
                    }
                }
            }
        }
    }

    inspectingEffect?.let { fx ->
        PluginShaderInspectorDialog(
            effect = fx,
            diagnostic = shaderDiagnostics[fx.id],
            onApply = {
                inspectingEffect = null
                onApplyPluginEffect(PluginPackManager.toEffectDefinition(fx))
            },
            onDismiss = { inspectingEffect = null }
        )
    }

    if (showBuilderDialog) {
        CustomPluginBuilderDialog(
            onDismiss = { showBuilderDialog = false },
            onCreate = { fxName, sName, author, cat, bounds, mb, glsl, params, ext ->
                onCreateCustomPlugin(fxName, sName, author, cat, bounds, mb, glsl, params, ext)
                showBuilderDialog = false
            }
        )
    }
}

@Composable
private fun KeyFlowSuiteCard(
    suite: InstalledPluginSuite,
    shaderDiagnostics: Map<String, String>,
    onToggleEnabled: (Boolean) -> Unit,
    onExport: () -> Unit,
    onDelete: () -> Unit,
    onInspectShader: (InstalledPluginEffect) -> Unit,
    onApplyEffect: (InstalledPluginEffect) -> Unit
) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = SurfaceDark,
        modifier = Modifier
            .fillMaxWidth()
            .border(
                width = 1.dp,
                color = if (suite.enabled) AmTeal.copy(alpha = 0.45f) else OutlineSubtle,
                shape = RoundedCornerShape(14.dp)
            )
            .testTag("plugin_suite_card_${suite.id}")
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(AmTeal.copy(alpha = 0.16f))
                            .border(0.8.dp, AmTeal.copy(alpha = 0.5f), RoundedCornerShape(10.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Extension,
                            contentDescription = null,
                            tint = AmTealBright,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                text = suite.name,
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = TextPrimary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            PluginPillBadge("v${suite.version}", AmTealBright)
                            PluginPillBadge(suite.archiveExtension, ElectricBlue)
                        }
                        Text(
                            text = "${suite.author} • ${suite.effects.size} FX",
                            style = MaterialTheme.typography.labelSmall,
                            color = TextSecondary
                        )
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(
                        onClick = onExport,
                        modifier = Modifier
                            .size(34.dp)
                            .testTag("export_suite_${suite.id}")
                    ) {
                        Icon(
                            imageVector = Icons.Default.FileDownload,
                            contentDescription = "Export ${suite.name}",
                            tint = AudioCyan,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    IconButton(
                        onClick = onDelete,
                        modifier = Modifier
                            .size(34.dp)
                            .testTag("delete_suite_${suite.id}")
                    ) {
                        Icon(
                            imageVector = Icons.Default.DeleteOutline,
                            contentDescription = "Remove ${suite.name}",
                            tint = NeonPink,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    Switch(
                        checked = suite.enabled,
                        onCheckedChange = onToggleEnabled,
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = DeepBlack,
                            checkedTrackColor = AmTealBright
                        )
                    )
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                suite.effects.forEach { fx ->
                    val diag = shaderDiagnostics[fx.id]
                    val catAccent = categoryAccentColor(fx.category)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(PanelDark)
                            .border(0.8.dp, OutlineSubtle.copy(alpha = 0.7f), RoundedCornerShape(10.dp))
                            .clickable { onInspectShader(fx) }
                            .padding(horizontal = 10.dp, vertical = 8.dp)
                            .testTag("plugin_effect_row_${fx.id}"),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(7.dp)
                                        .clip(CircleShape)
                                        .background(if (diag == null) RigGreen else NeonPink)
                                )
                                Text(
                                    text = fx.name,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    color = TextPrimary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                PluginPillBadge(fx.category, catAccent)
                            }
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(5.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                PluginPillBadge(
                                    text = if (fx.isMultiPass) "${fx.passes.size}-Pass" else "1-Pass",
                                    accent = if (fx.isMultiPass) ElectricBlue else TextSecondary
                                )
                                if (fx.expandBounds > 0.001f) {
                                    PluginPillBadge("+${(fx.expandBounds * 100).toInt()}% Bounds", AmTealBright)
                                }
                                if (fx.usesNativeMotionBlur) {
                                    PluginPillBadge("Shutter Blur", NeonPink)
                                }
                                PluginPillBadge("${fx.parameters.size} Params", AudioCyan)
                            }
                            if (!diag.isNullOrBlank()) {
                                Text(
                                    text = diag,
                                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                                    color = NeonPink,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }

                        Button(
                            onClick = { onApplyEffect(fx) },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = AmTeal.copy(alpha = 0.2f),
                                contentColor = AmTealBright
                            ),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier
                                .height(32.dp)
                                .testTag("apply_plugin_fx_${fx.id}")
                        ) {
                            Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(14.dp))
                            Spacer(modifier = Modifier.width(3.dp))
                            Text("Add", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PluginPillBadge(text: String, accent: Color) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(5.dp))
            .background(accent.copy(alpha = 0.14f))
            .padding(horizontal = 5.dp, vertical = 1.5.dp)
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.5.sp),
            color = accent,
            fontWeight = FontWeight.SemiBold
        )
    }
}

private fun categoryAccentColor(category: String): Color = when (category) {
    "Gerak", "Transisi" -> NeonPink
    "Warna", "Keying" -> AmTealBright
    "Distorsi", "Perspective" -> ElectricBlue
    "Blur", "Simulasi", "Generator" -> AudioCyan
    else -> RigGreen
}

@Composable
private fun PluginShaderInspectorDialog(
    effect: InstalledPluginEffect,
    diagnostic: String?,
    onApply: () -> Unit,
    onDismiss: () -> Unit
) {
    var selectedPassIdx by remember { mutableStateOf(0) }
    val activePass = effect.passes.getOrNull(selectedPassIdx) ?: effect.passes.first()

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = SurfaceDark,
        title = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(effect.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                PluginPillBadge(effect.category, categoryAccentColor(effect.category))
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (effect.parameters.isNotEmpty()) {
                    Text("Uniforms", style = MaterialTheme.typography.labelMedium, color = AmTealBright)
                    effect.parameters.forEach { p ->
                        val glType = if (p.type == PluginParamType.COLOR) "vec3" else "float"
                        Text(
                            text = "uniform $glType ${p.uniformName};  // ${p.label} (${p.type.name})",
                            style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                            color = TextSecondary
                        )
                    }
                }

                if (effect.passes.size > 1) {
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        effect.passes.forEachIndexed { idx, pass ->
                            AmSegmentChip(
                                label = pass.fileName,
                                selected = selectedPassIdx == idx,
                                compact = true,
                                onClick = { selectedPassIdx = idx }
                            )
                        }
                    }
                }

                if (!diagnostic.isNullOrBlank()) {
                    Text(
                        text = diagnostic,
                        style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                        color = NeonPink
                    )
                }

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(DeepBlack)
                        .border(1.dp, OutlineSubtle, RoundedCornerShape(8.dp))
                        .padding(10.dp)
                ) {
                    Text(
                        text = activePass.glslSource,
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            lineHeight = 15.sp
                        ),
                        color = TextPrimary
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onApply,
                colors = ButtonDefaults.buttonColors(containerColor = AmTeal, contentColor = DeepBlack)
            ) {
                Text("Add to Layer", fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Close", color = TextSecondary)
            }
        }
    )
}

@Composable
private fun CustomPluginBuilderDialog(
    onDismiss: () -> Unit,
    onCreate: (
        effectName: String,
        suiteName: String,
        author: String,
        category: String,
        expandBounds: Float,
        usesNativeMotionBlur: Boolean,
        glslSource: String,
        parameters: List<PluginParamDef>,
        extension: String
    ) -> Unit
) {
    var effectName by remember { mutableStateOf("Liquid Wave") }
    var author by remember { mutableStateOf("Studio") }
    var category by remember { mutableStateOf("Distorsi") }
    var extension by remember { mutableStateOf(PluginPackManager.EXT_KFPACK) }
    var expandBounds by remember { mutableFloatStateOf(0.15f) }
    var usesMotionBlur by remember { mutableStateOf(false) }
    var glslCode by remember { mutableStateOf(PluginPackManager.DEFAULT_CUSTOM_GLSL_TEMPLATE) }

    val params = remember {
        mutableStateListOf(
            PluginParamDef("amplitude", "Amplitude", PluginParamType.FLOAT, 0.6f, min = 0f, max = 2f, step = 0.02f),
            PluginParamDef("frequency", "Frequency", PluginParamType.FLOAT, 1.2f, min = 0.1f, max = 5f, step = 0.05f),
            PluginParamDef("mirrorEdges", "Mirror Edges", PluginParamType.BOOL, 1f),
            PluginParamDef("tintColor", "Tint Color", PluginParamType.COLOR, defaultColorHex = "#FFFFFF")
        )
    }
    var newParamKey by remember { mutableStateOf("") }
    var newParamType by remember { mutableStateOf(PluginParamType.FLOAT) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = SurfaceDark,
        title = {
            Text("New Plugin Pack", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = effectName,
                        onValueChange = { effectName = it },
                        label = { Text("Effect") },
                        singleLine = true,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("builder_effect_name_input")
                    )
                    OutlinedTextField(
                        value = author,
                        onValueChange = { author = it },
                        label = { Text("Author") },
                        singleLine = true,
                        modifier = Modifier.weight(0.8f)
                    )
                }

                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(PluginPackManager.EXT_KFPACK, PluginPackManager.EXT_FPPACK).forEach { ext ->
                        AmSegmentChip(
                            label = ext,
                            selected = extension == ext,
                            compact = true,
                            onClick = { extension = ext }
                        )
                    }
                }

                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    PluginPackManager.OFFICIAL_CATEGORIES.forEach { cat ->
                        AmSegmentChip(
                            label = cat,
                            selected = category == cat,
                            compact = true,
                            onClick = { category = cat }
                        )
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "Bounds +${(expandBounds * 100).toInt()}%",
                        style = MaterialTheme.typography.labelMedium,
                        color = TextSecondary
                    )
                    Slider(
                        value = expandBounds,
                        onValueChange = { expandBounds = it },
                        valueRange = 0f..0.6f,
                        colors = SliderDefaults.colors(thumbColor = AmTeal, activeTrackColor = AmTeal),
                        modifier = Modifier.width(150.dp)
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Native Motion Blur", style = MaterialTheme.typography.labelMedium, color = TextSecondary)
                    Switch(
                        checked = usesMotionBlur,
                        onCheckedChange = { usesMotionBlur = it },
                        colors = SwitchDefaults.colors(checkedThumbColor = DeepBlack, checkedTrackColor = AmTealBright)
                    )
                }

                Text("Parameters", style = MaterialTheme.typography.labelMedium, color = AmTealBright)
                params.forEachIndexed { idx, p ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(6.dp))
                            .background(PanelDark)
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "${p.uniformName} (${p.type.name})",
                            style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                            color = TextPrimary
                        )
                        IconButton(onClick = { params.removeAt(idx) }, modifier = Modifier.size(24.dp)) {
                            Icon(Icons.Default.DeleteOutline, contentDescription = "Remove", tint = NeonPink, modifier = Modifier.size(14.dp))
                        }
                    }
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    OutlinedTextField(
                        value = newParamKey,
                        onValueChange = { newParamKey = it },
                        placeholder = { Text("key") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    PluginParamType.entries.forEach { t ->
                        AmSegmentChip(
                            label = t.name,
                            selected = newParamType == t,
                            compact = true,
                            onClick = { newParamType = t }
                        )
                    }
                    IconButton(
                        onClick = {
                            val clean = newParamKey.trim().replace(Regex("[^a-zA-Z0-9_]"), "")
                            if (clean.isNotEmpty() && params.none { it.key == clean }) {
                                params.add(
                                    PluginParamDef(
                                        key = clean,
                                        label = clean.replaceFirstChar { it.uppercaseChar() },
                                        type = newParamType
                                    )
                                )
                                newParamKey = ""
                            }
                        }
                    ) {
                        Icon(Icons.Default.Add, contentDescription = "Add parameter", tint = AmTealBright)
                    }
                }

                OutlinedTextField(
                    value = glslCode,
                    onValueChange = { glslCode = it },
                    label = { Text("shader.glsl") },
                    textStyle = MaterialTheme.typography.bodySmall.copy(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp
                    ),
                    minLines = 7,
                    maxLines = 11,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onCreate(
                        effectName,
                        "$effectName Suite",
                        author,
                        category,
                        expandBounds,
                        usesMotionBlur,
                        glslCode,
                        params.toList(),
                        extension
                    )
                },
                colors = ButtonDefaults.buttonColors(containerColor = AmTeal, contentColor = DeepBlack),
                modifier = Modifier.testTag("builder_confirm_create_button")
            ) {
                Text("Install", fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = TextSecondary)
            }
        }
    )
}
