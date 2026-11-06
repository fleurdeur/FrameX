@file:OptIn(ExperimentalMaterial3Api::class)

package com.example.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CleaningServices
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Science
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.engine.TimelinePluginUiStatus
import com.example.engine.hasPanelUi
import com.example.engine.isFileNetworkAllowed
import com.example.engine.timelineStatus
import com.example.scriptengine.CompatibilityCategory
import com.example.scriptengine.InstalledPlugin
import com.example.scriptengine.PluginInstallStatus

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AePluginsBrowserScreen(
    installedPlugins: List<InstalledPlugin>,
    onImportPluginUri: (Uri) -> Unit,
    onToggleEnabled: (String, Boolean) -> Unit,
    onToggleShowInTimeline: (String, Boolean) -> Unit,
    onTogglePinToAddMenu: (String, Boolean) -> Unit,
    onToggleFileNetworkAllowed: (String, Boolean) -> Unit,
    onUsePlugin: (InstalledPlugin) -> Unit,
    onRunProbe: (String) -> Unit,
    onClearData: (String) -> Unit,
    onUninstallPlugin: (String) -> Unit,
    onStatusMessage: (String) -> Unit = {},
    bottomBar: @Composable (() -> Unit)? = null,
    onBack: (() -> Unit)? = null
) {
    if (onBack != null) {
        BackHandler(onBack = onBack)
    }

    val context = LocalContext.current
    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            onImportPluginUri(uri)
        }
    }

    var inspectingPlugin by remember { mutableStateOf<InstalledPlugin?>(null) }
    var confirmingUninstall by remember { mutableStateOf<InstalledPlugin?>(null) }

    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            TopAppBar(
                navigationIcon = {
                    if (onBack != null) {
                        IconButton(
                            onClick = onBack,
                            modifier = Modifier.testTag("plugins_back_button")
                        ) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    }
                },
                title = {
                    Column {
                        Text(
                            text = "Plugins & Extensions",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "CEP (.zip/.zxp) & ScriptUI (.jsx/.jsxbin) • On-device sandbox",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                actions = {
                    Button(
                        onClick = { importLauncher.launch(arrayOf("*/*")) },
                        modifier = Modifier
                            .padding(end = 8.dp)
                            .testTag("plugins_import_button")
                    ) {
                        Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Import")
                    }
                }
            )
        },
        bottomBar = { bottomBar?.invoke() }
    ) { innerPadding ->
        if (installedPlugins.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(24.dp),
                contentAlignment = Alignment.Center
            ) {
                Card(
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainer
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Extension,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(44.dp)
                        )
                        Text(
                            text = "No plugins installed yet",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "Import After Effects CEP panels (.zip, .zxp) like KeyEase or ScriptUI scripts (.jsx, .jsxbin) like Textevo directly on-device — no PC or LAN server required.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Button(
                            onClick = { importLauncher.launch(arrayOf("*/*")) },
                            modifier = Modifier
                                .height(48.dp)
                                .testTag("plugins_empty_import_button")
                        ) {
                            Icon(Icons.Default.Add, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text("Import Plugin (.zip / .zxp / .jsx / .jsxbin)")
                        }
                    }
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .testTag("plugins_list"),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(installedPlugins, key = { it.bundleId }) { plugin ->
                    PluginManagementCard(
                        plugin = plugin,
                        onToggleEnabled = { onToggleEnabled(plugin.bundleId, it) },
                        onToggleShowInTimeline = { onToggleShowInTimeline(plugin.bundleId, it) },
                        onTogglePinToAddMenu = { onTogglePinToAddMenu(plugin.bundleId, it) },
                        onToggleFileNetwork = { onToggleFileNetworkAllowed(plugin.bundleId, it) },
                        onUse = { onUsePlugin(plugin) },
                        onRunProbe = { onRunProbe(plugin.bundleId) },
                        onInspectReport = { inspectingPlugin = plugin },
                        onClearData = { onClearData(plugin.bundleId) },
                        onUninstall = { confirmingUninstall = plugin }
                    )
                }
            }
        }
    }

    confirmingUninstall?.let { target ->
        AlertDialog(
            onDismissRequest = { confirmingUninstall = null },
            title = { Text("Uninstall ${target.name}?") },
            text = {
                Text("This removes ${target.name} (v${target.version}) and its sandboxed plugin data from your device.")
            },
            confirmButton = {
                Button(
                    onClick = {
                        val id = target.bundleId
                        confirmingUninstall = null
                        onUninstallPlugin(id)
                    },
                    modifier = Modifier.testTag("confirm_uninstall_${target.bundleId}")
                ) {
                    Text("Uninstall")
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmingUninstall = null }) {
                    Text("Cancel")
                }
            }
        )
    }

    inspectingPlugin?.let { plugin ->
        ModalBottomSheet(
            onDismissRequest = { inspectingPlugin = null }
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "${plugin.name} • Compatibility Report",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "${plugin.bundleId} • v${plugin.version} • ${plugin.signatureStatus.label}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    OutlinedButton(
                        onClick = {
                            val cb = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                            cb?.setPrimaryClip(
                                ClipData.newPlainText(
                                    "Plugin Compatibility Report",
                                    plugin.report.toPlainTextReport()
                                )
                            )
                            onStatusMessage("Copied compatibility report to clipboard")
                        },
                        modifier = Modifier.testTag("copy_compat_report_button")
                    ) {
                        Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Copy")
                    }
                }

                CompatibilityCategory.entries.forEach { cat ->
                    val detected = plugin.report.detectedApis[cat].orEmpty()
                    val catIssues = plugin.report.issues.filter { it.category == cat }
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surfaceContainer,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text(
                                text = cat.title,
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.SemiBold
                            )
                            if (detected.isNotEmpty()) {
                                Text(
                                    text = "Detected: ${detected.joinToString(", ")}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            if (catIssues.isEmpty()) {
                                Text(
                                    text = "Works cleanly",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            } else {
                                catIssues.forEach { issue ->
                                    Text(
                                        text = "• ${issue.item}: ${issue.detail}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = if (issue.fatal) MaterialTheme.colorScheme.error
                                        else MaterialTheme.colorScheme.tertiary
                                    )
                                }
                            }
                        }
                    }
                }

                if (plugin.readmeText.isNotBlank()) {
                    HorizontalDivider()
                    Text(
                        text = "Bundled README",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerLowest,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = plugin.readmeText,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.padding(12.dp)
                        )
                    }
                }
                Spacer(Modifier.height(16.dp))
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PluginManagementCard(
    plugin: InstalledPlugin,
    onToggleEnabled: (Boolean) -> Unit,
    onToggleShowInTimeline: (Boolean) -> Unit,
    onTogglePinToAddMenu: (Boolean) -> Unit,
    onToggleFileNetwork: (Boolean) -> Unit,
    onUse: () -> Unit,
    onRunProbe: () -> Unit,
    onInspectReport: () -> Unit,
    onClearData: () -> Unit,
    onUninstall: () -> Unit
) {
    val tlStatus = plugin.timelineStatus()
    val supported = plugin.report.status != PluginInstallStatus.UNSUPPORTED

    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer
        ),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("plugin_card_${plugin.bundleId}")
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.primaryContainer,
                    modifier = Modifier.size(44.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Default.Extension,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = plugin.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    val authorPart = if (plugin.author.isNotBlank()) "by ${plugin.author} • " else ""
                    Text(
                        text = "${authorPart}v${plugin.version} • ${plugin.type.label} • ${plugin.signatureStatus.label}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // Status Chips Row: Compatibility Badge + Timeline UI Status Chip
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                AssistChip(
                    onClick = onInspectReport,
                    leadingIcon = {
                        val icon = when (plugin.report.status) {
                            PluginInstallStatus.WORKS -> Icons.Default.CheckCircle
                            PluginInstallStatus.PARTIAL -> Icons.Default.WarningAmber
                            PluginInstallStatus.UNSUPPORTED -> Icons.Default.ErrorOutline
                        }
                        Icon(icon, contentDescription = null, modifier = Modifier.size(16.dp))
                    },
                    label = { Text(plugin.report.statusBadgeText) },
                    modifier = Modifier.testTag("plugin_compat_chip_${plugin.bundleId}")
                )

                AssistChip(
                    onClick = {},
                    label = { Text("Timeline UI: ${tlStatus.label}") },
                    modifier = Modifier.testTag("plugin_tl_status_${plugin.bundleId}")
                )
            }

            HorizontalDivider()

            // Switches per Section 1.5
            PluginToggleRow(
                label = "Enabled",
                subtitle = "Allow this plugin to run inside compositions",
                checked = plugin.uiState.enabled,
                enabled = supported,
                testTag = "plugin_switch_enabled_${plugin.bundleId}",
                onCheckedChange = onToggleEnabled
            )

            PluginToggleRow(
                label = "Show in timeline",
                subtitle = if (plugin.hasPanelUi()) {
                    "Display floating panel button in editor + menu"
                } else {
                    "Command-only script (no panel UI)"
                },
                checked = plugin.uiState.showInTimeline && plugin.hasPanelUi(),
                enabled = supported && plugin.uiState.enabled && plugin.hasPanelUi(),
                testTag = "plugin_switch_timeline_${plugin.bundleId}",
                onCheckedChange = onToggleShowInTimeline
            )

            PluginToggleRow(
                label = "Pin to + menu",
                subtitle = "Include in editor speed-dial quick actions",
                checked = plugin.uiState.pinToAddMenu,
                enabled = supported && plugin.uiState.enabled && plugin.hasPanelUi(),
                testTag = "plugin_switch_pin_${plugin.bundleId}",
                onCheckedChange = onTogglePinToAddMenu
            )

            PluginToggleRow(
                label = "Allow Scripts to Write Files & Network",
                subtitle = "Sandboxed to plugin-data/${plugin.bundleId}",
                checked = plugin.isFileNetworkAllowed(),
                enabled = supported,
                testTag = "plugin_switch_filenet_${plugin.bundleId}",
                onCheckedChange = onToggleFileNetwork
            )

            HorizontalDivider()

            // Action Buttons Row
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = onUse,
                    enabled = supported && plugin.uiState.enabled,
                    modifier = Modifier
                        .height(48.dp)
                        .testTag("plugin_use_btn_${plugin.bundleId}")
                ) {
                    Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Use")
                }

                FilledTonalButton(
                    onClick = onRunProbe,
                    modifier = Modifier
                        .height(48.dp)
                        .testTag("plugin_probe_btn_${plugin.bundleId}")
                ) {
                    Icon(Icons.Default.Science, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Run probe")
                }

                OutlinedButton(
                    onClick = onInspectReport,
                    modifier = Modifier
                        .height(48.dp)
                        .testTag("plugin_report_btn_${plugin.bundleId}")
                ) {
                    Icon(Icons.Default.Description, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Report")
                }

                OutlinedButton(
                    onClick = onClearData,
                    modifier = Modifier
                        .height(48.dp)
                        .testTag("plugin_clear_data_btn_${plugin.bundleId}")
                ) {
                    Icon(Icons.Default.CleaningServices, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Clear data")
                }

                TextButton(
                    onClick = onUninstall,
                    modifier = Modifier
                        .height(48.dp)
                        .testTag("plugin_uninstall_btn_${plugin.bundleId}")
                ) {
                    Icon(
                        Icons.Default.DeleteOutline,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(4.dp))
                    Text("Uninstall", color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}

@Composable
private fun PluginToggleRow(
    label: String,
    subtitle: String,
    checked: Boolean,
    enabled: Boolean,
    testTag: String,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            enabled = enabled,
            modifier = Modifier.testTag(testTag)
        )
    }
}
