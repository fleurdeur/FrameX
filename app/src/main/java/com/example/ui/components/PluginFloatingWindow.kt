@file:OptIn(ExperimentalMaterial3Api::class)

package com.example.ui.components

import android.annotation.SuppressLint
import android.util.Base64
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AspectRatio
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DragIndicator
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.FitScreen
import androidx.compose.material.icons.filled.OpenInFull
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SecondaryScrollableTabRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.example.engine.PluginHostRepository
import com.example.engine.isFileNetworkAllowed
import com.example.scriptengine.InstalledPlugin
import com.example.scriptengine.PluginArchiveImporter
import com.example.scriptengine.PluginType
import com.example.scriptengine.ScriptUiDrawOp
import com.example.scriptengine.ScriptUiWidgetKind
import com.example.scriptengine.ScriptUiWidgetSnapshot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.File
import java.net.URLConnection
import java.security.SecureRandom
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Floating draggable & resizable window (or ModalBottomSheet when `asBottomSheet = true`)
 * hosting either an Adobe CEP HTML/JS panel (`window.__adobe_cep__` + sandboxed Node `fs`/`path`/`os`/`crypto`)
 * or a native Jetpack Compose ScriptUI tree (`.jsx` / `.jsxbin`).
 * Uses `MaterialTheme.colorScheme` exclusively and keeps the underlying editor interactive.
 */
@Composable
fun PluginFloatingWindowHost(
    plugin: InstalledPlugin,
    pluginRepository: PluginHostRepository,
    asBottomSheet: Boolean = false,
    onStatusMessage: (String) -> Unit = {},
    onClose: () -> Unit
) {
    if (asBottomSheet) {
        ModalBottomSheet(
            onDismissRequest = onClose,
            containerColor = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.onSurface
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(plugin.geometry.defaultHeight.coerceIn(320, 620).dp)
                    .padding(horizontal = 12.dp, vertical = 8.dp)
                    .testTag("plugin_bottom_sheet_${plugin.bundleId}")
            ) {
                PluginPanelBody(
                    plugin = plugin,
                    pluginRepository = pluginRepository,
                    onStatusMessage = onStatusMessage
                )
            }
        }
        return
    }

    val safeInsets = WindowInsets.safeDrawing.asPaddingValues()
    val density = LocalDensity.current

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .padding(safeInsets)
    ) {
        val maxContainerW = maxWidth
        val maxContainerH = maxHeight

        val initW = plugin.geometry.defaultWidth.coerceIn(220, 420).toFloat()
        val initH = plugin.geometry.defaultHeight.coerceIn(300, 560).toFloat()

        var isMinimized by remember(plugin.bundleId) { mutableStateOf(false) }
        var showSizingMenu by remember(plugin.bundleId) { mutableStateOf(false) }

        var winWidthDp by remember(plugin.bundleId) {
            mutableFloatStateOf(initW.coerceAtMost((maxContainerW.value - 24f).coerceAtLeast(220f)))
        }
        var winHeightDp by remember(plugin.bundleId) {
            mutableFloatStateOf(initH.coerceAtMost((maxContainerH.value - 32f).coerceAtLeast(260f)))
        }
        var offsetXPx by remember(plugin.bundleId) {
            mutableFloatStateOf(with(density) { 12.dp.toPx() })
        }
        var offsetYPx by remember(plugin.bundleId) {
            mutableFloatStateOf(with(density) { 48.dp.toPx() })
        }

        val effectiveHeightDp = if (isMinimized) 48.dp else winHeightDp.dp
        val maxXPx = with(density) { (maxContainerW - winWidthDp.dp).coerceAtLeast(0.dp).toPx() }
        val maxYPx = with(density) { (maxContainerH - effectiveHeightDp).coerceAtLeast(0.dp).toPx() }
        val clampedX = offsetXPx.coerceIn(0f, maxXPx)
        val clampedY = offsetYPx.coerceIn(0f, maxYPx)

        Surface(
            modifier = Modifier
                .offset { IntOffset(clampedX.roundToInt(), clampedY.roundToInt()) }
                .size(width = winWidthDp.dp, height = effectiveHeightDp)
                .shadow(14.dp, RoundedCornerShape(16.dp))
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(16.dp))
                .testTag("plugin_floating_window_${plugin.bundleId}"),
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            contentColor = MaterialTheme.colorScheme.onSurface
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                // Draggable Header Bar (48dp touch targets)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                        .pointerInput(plugin.bundleId, maxXPx, maxYPx) {
                            detectDragGestures { change, dragAmount ->
                                change.consume()
                                offsetXPx = (offsetXPx + dragAmount.x).coerceIn(0f, maxXPx)
                                offsetYPx = (offsetYPx + dragAmount.y).coerceIn(0f, maxYPx)
                            }
                        }
                        .padding(start = 10.dp, end = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.DragIndicator,
                        contentDescription = "Drag plugin window",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = plugin.menuName.ifBlank { plugin.name },
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = "${plugin.type.label} • v${plugin.version}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1
                        )
                    }

                    // Sizing options button with dropdown menu
                    Box {
                        IconButton(
                            onClick = { showSizingMenu = !showSizingMenu },
                            modifier = Modifier
                                .size(40.dp)
                                .testTag("plugin_window_sizing_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.AspectRatio,
                                contentDescription = "Window sizing options",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(18.dp)
                            )
                        }

                        DropdownMenu(
                            expanded = showSizingMenu,
                            onDismissRequest = { showSizingMenu = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text("Compact (260 × 360)") },
                                onClick = {
                                    winWidthDp = 260f.coerceAtMost(maxContainerW.value - 16f)
                                    winHeightDp = 360f.coerceAtMost(maxContainerH.value - 24f)
                                    isMinimized = false
                                    showSizingMenu = false
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Default (340 × 480)") },
                                onClick = {
                                    winWidthDp = 340f.coerceAtMost(maxContainerW.value - 16f)
                                    winHeightDp = 480f.coerceAtMost(maxContainerH.value - 24f)
                                    isMinimized = false
                                    showSizingMenu = false
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Wide (440 × 540)") },
                                onClick = {
                                    winWidthDp = 440f.coerceAtMost(maxContainerW.value - 16f)
                                    winHeightDp = 540f.coerceAtMost(maxContainerH.value - 24f)
                                    isMinimized = false
                                    showSizingMenu = false
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Maximize") },
                                onClick = {
                                    winWidthDp = (maxContainerW.value - 16f).coerceAtLeast(240f)
                                    winHeightDp = (maxContainerH.value - 24f).coerceAtLeast(320f)
                                    offsetXPx = 0f
                                    offsetYPx = 0f
                                    isMinimized = false
                                    showSizingMenu = false
                                }
                            )
                        }
                    }

                    // Minimize / Restore Toggle Button
                    IconButton(
                        onClick = { isMinimized = !isMinimized },
                        modifier = Modifier
                            .size(40.dp)
                            .testTag("plugin_window_minimize_button")
                    ) {
                        Icon(
                            imageVector = if (isMinimized) Icons.Default.ExpandMore else Icons.Default.Remove,
                            contentDescription = if (isMinimized) "Expand plugin window" else "Minimize plugin window",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp)
                        )
                    }

                    IconButton(
                        onClick = onClose,
                        modifier = Modifier
                            .size(44.dp)
                            .testTag("plugin_window_close_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Close ${plugin.name}"
                        )
                    }
                }

                if (!isMinimized) {
                    Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                        PluginPanelBody(
                            plugin = plugin,
                            pluginRepository = pluginRepository,
                            onStatusMessage = onStatusMessage,
                            onRequestResize = { reqW, reqH ->
                                winWidthDp = reqW.toFloat().coerceIn(220f, (maxContainerW.value - 16f).coerceAtLeast(220f))
                                winHeightDp = reqH.toFloat().coerceIn(260f, (maxContainerH.value - 24f).coerceAtLeast(260f))
                            }
                        )

                        // Right edge resize handle (drag edge to resize width)
                        Box(
                            modifier = Modifier
                                .align(Alignment.CenterEnd)
                                .fillMaxHeight()
                                .width(12.dp)
                                .pointerInput(plugin.bundleId) {
                                    detectDragGestures { change, dragAmount ->
                                        change.consume()
                                        val dw = with(density) { dragAmount.x.toDp().value }
                                        winWidthDp = (winWidthDp + dw).coerceIn(
                                            plugin.geometry.minWidth.coerceAtLeast(210).toFloat(),
                                            (maxContainerW.value - 12f).coerceAtLeast(220f)
                                        )
                                    }
                                }
                                .testTag("plugin_window_edge_resize_right")
                        )

                        // Bottom edge resize handle (drag edge to resize height)
                        Box(
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .fillMaxWidth()
                                .height(12.dp)
                                .pointerInput(plugin.bundleId) {
                                    detectDragGestures { change, dragAmount ->
                                        change.consume()
                                        val dh = with(density) { dragAmount.y.toDp().value }
                                        winHeightDp = (winHeightDp + dh).coerceIn(
                                            plugin.geometry.minHeight.coerceAtLeast(250).toFloat(),
                                            (maxContainerH.value - 16f).coerceAtLeast(260f)
                                        )
                                    }
                                }
                                .testTag("plugin_window_edge_resize_bottom")
                        )

                        // Bottom-right Resize Corner Handle
                        Box(
                            modifier = Modifier
                                .align(Alignment.BottomEnd)
                                .size(48.dp)
                                .pointerInput(plugin.bundleId) {
                                    detectDragGestures { change, dragAmount ->
                                        change.consume()
                                        val dw = with(density) { dragAmount.x.toDp().value }
                                        val dh = with(density) { dragAmount.y.toDp().value }
                                        winWidthDp = (winWidthDp + dw).coerceIn(
                                            plugin.geometry.minWidth.coerceAtLeast(210).toFloat(),
                                            (maxContainerW.value - 12f).coerceAtLeast(220f)
                                        )
                                        winHeightDp = (winHeightDp + dh).coerceIn(
                                            plugin.geometry.minHeight.coerceAtLeast(250).toFloat(),
                                            (maxContainerH.value - 16f).coerceAtLeast(260f)
                                        )
                                    }
                                }
                                .testTag("plugin_window_resize_handle"),
                            contentAlignment = Alignment.BottomEnd
                        ) {
                            Icon(
                                imageVector = Icons.Default.OpenInFull,
                                contentDescription = "Resize plugin window",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f),
                                modifier = Modifier
                                    .padding(6.dp)
                                    .size(14.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PluginPanelBody(
    plugin: InstalledPlugin,
    pluginRepository: PluginHostRepository,
    onStatusMessage: (String) -> Unit,
    onRequestResize: ((Int, Int) -> Unit)? = null
) {
    when (plugin.type) {
        PluginType.CEP_PANEL -> {
            CepPanelWebView(
                plugin = plugin,
                pluginRepository = pluginRepository,
                onStatusMessage = onStatusMessage,
                onRequestResize = onRequestResize
            )
        }
        PluginType.SCRIPT_UI, PluginType.STANDALONE_JSX -> {
            ScriptUiPanelContainer(
                plugin = plugin,
                pluginRepository = pluginRepository,
                onStatusMessage = onStatusMessage
            )
        }
    }
}

@Composable
private fun ScriptUiPanelContainer(
    plugin: InstalledPlugin,
    pluginRepository: PluginHostRepository,
    onStatusMessage: (String) -> Unit
) {
    val scope = rememberCoroutineScope()
    var snapshot by remember(plugin.bundleId) { mutableStateOf<ScriptUiWidgetSnapshot?>(null) }
    var loading by remember(plugin.bundleId) { mutableStateOf(true) }
    var loadError by remember(plugin.bundleId) { mutableStateOf<String?>(null) }

    LaunchedEffect(plugin.bundleId) {
        loading = true
        loadError = null
        val res = pluginRepository.ensureHostLoaded(plugin.bundleId)
        res.fold(
            onSuccess = { tree ->
                snapshot = tree
                loading = false
            },
            onFailure = { err ->
                loadError = err.message ?: "Failed to load ScriptUI panel"
                loading = false
            }
        )
    }

    when {
        loading -> {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        }
        loadError != null -> {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = loadError ?: "Unsupported script",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error
                )
                Spacer(Modifier.height(12.dp))
                OutlinedButton(
                    onClick = {
                        scope.launch {
                            loading = true
                            loadError = null
                            pluginRepository.ensureHostLoaded(plugin.bundleId).fold(
                                onSuccess = {
                                    snapshot = it
                                    loading = false
                                },
                                onFailure = {
                                    loadError = it.message
                                    loading = false
                                }
                            )
                        }
                    }
                ) {
                    Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Retry")
                }
            }
        }
        snapshot != null -> {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(12.dp)
                    .testTag("scriptui_panel_root_${plugin.bundleId}"),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                val root = snapshot!!
                if (root.children.isEmpty() && plugin.type == PluginType.STANDALONE_JSX) {
                    Text(
                        text = "${plugin.name} is a command script without a persistent panel.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Button(
                        onClick = {
                            scope.launch {
                                val r = pluginRepository.ensureHostLoaded(plugin.bundleId)
                                if (r.isSuccess) onStatusMessage("Executed ${plugin.name}")
                                else onStatusMessage("Script error: ${r.exceptionOrNull()?.message}")
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Run Script")
                    }
                } else {
                    root.children.forEach { child ->
                        ScriptUiNodeComposable(
                            node = child,
                            onInteract = { id, txt, v, chk, selIdx, click ->
                                scope.launch {
                                    pluginRepository.dispatchScriptUiEvent(
                                        bundleId = plugin.bundleId,
                                        widgetId = id,
                                        newText = txt,
                                        newValue = v,
                                        newChecked = chk,
                                        newSelectedIndex = selIdx,
                                        triggerClick = click
                                    ).onSuccess { updated ->
                                        snapshot = updated
                                        if (click) {
                                            onStatusMessage("Applied ${plugin.name}")
                                        }
                                    }.onFailure { err ->
                                        onStatusMessage("Plugin error: ${err.message}")
                                    }
                                }
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ScriptUiNodeComposable(
    node: ScriptUiWidgetSnapshot,
    onInteract: (
        widgetId: String,
        newText: String?,
        newValue: Float?,
        newChecked: Boolean?,
        newSelectedIndex: Int?,
        triggerClick: Boolean
    ) -> Unit
) {
    if (!node.visible) return

    when (node.kind) {
        ScriptUiWidgetKind.PANEL -> {
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = MaterialTheme.colorScheme.surfaceContainer,
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(10.dp))
            ) {
                Column(
                    modifier = Modifier.padding(10.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (node.text.isNotBlank()) {
                        Text(
                            text = node.text,
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    ScriptUiChildrenLayout(node, onInteract)
                }
            }
        }

        ScriptUiWidgetKind.GROUP, ScriptUiWidgetKind.TAB -> {
            ScriptUiChildrenLayout(node, onInteract)
        }

        ScriptUiWidgetKind.TABBED_PANEL -> {
            val tabs = node.children.filter { it.visible }
            var selectedTab by remember(node.id, tabs.size) { mutableIntStateOf(0) }
            if (tabs.isNotEmpty()) {
                val clampedTab = selectedTab.coerceIn(0, tabs.lastIndex)
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SecondaryScrollableTabRow(
                        selectedTabIndex = clampedTab,
                        edgePadding = 0.dp,
                        containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                    ) {
                        tabs.forEachIndexed { idx, tabNode ->
                            Tab(
                                selected = idx == clampedTab,
                                onClick = { selectedTab = idx },
                                text = { Text(tabNode.text.ifBlank { "Tab ${idx + 1}" }) }
                            )
                        }
                    }
                    ScriptUiNodeComposable(tabs[clampedTab], onInteract)
                }
            }
        }

        ScriptUiWidgetKind.STATIC_TEXT -> {
            Text(
                text = node.text,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface
            )
        }

        ScriptUiWidgetKind.EDIT_TEXT -> {
            var localText by remember(node.id, node.text) { mutableStateOf(node.text) }
            OutlinedTextField(
                value = localText,
                onValueChange = { next ->
                    localText = next
                    onInteract(node.id, next, null, null, null, false)
                },
                enabled = node.enabled,
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("scriptui_edit_${node.id}")
            )
        }

        ScriptUiWidgetKind.BUTTON, ScriptUiWidgetKind.ICON_BUTTON -> {
            Button(
                onClick = { onInteract(node.id, null, null, null, null, true) },
                enabled = node.enabled,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .testTag("scriptui_btn_${node.id}")
            ) {
                Text(node.text.ifBlank { "Apply" })
            }
        }

        ScriptUiWidgetKind.CHECKBOX -> {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .clickable(enabled = node.enabled) {
                        onInteract(node.id, null, null, !node.checked, null, false)
                    },
                verticalAlignment = Alignment.CenterVertically
            ) {
                Checkbox(
                    checked = node.checked,
                    onCheckedChange = { onInteract(node.id, null, null, it, null, false) },
                    enabled = node.enabled
                )
                Spacer(Modifier.width(6.dp))
                Text(node.text, style = MaterialTheme.typography.bodySmall)
            }
        }

        ScriptUiWidgetKind.RADIO_BUTTON -> {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .clickable(enabled = node.enabled) {
                        onInteract(node.id, null, null, true, null, false)
                    },
                verticalAlignment = Alignment.CenterVertically
            ) {
                RadioButton(
                    selected = node.checked,
                    onClick = { onInteract(node.id, null, null, true, null, false) },
                    enabled = node.enabled
                )
                Spacer(Modifier.width(6.dp))
                Text(node.text, style = MaterialTheme.typography.bodySmall)
            }
        }

        ScriptUiWidgetKind.SLIDER -> {
            Column(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = node.text.ifBlank { "Value" },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = String.format(Locale.US, "%.1f", node.value),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                val safeRange = if (node.maxValue > node.minValue) {
                    node.minValue..node.maxValue
                } else 0f..100f
                Slider(
                    value = node.value.coerceIn(safeRange.start, safeRange.endInclusive),
                    onValueChange = { v -> onInteract(node.id, null, v, null, null, false) },
                    valueRange = safeRange,
                    enabled = node.enabled,
                    modifier = Modifier.testTag("scriptui_slider_${node.id}")
                )
            }
        }

        ScriptUiWidgetKind.DROPDOWN_LIST, ScriptUiWidgetKind.LIST_BOX -> {
            var expanded by remember(node.id) { mutableStateOf(false) }
            val currentLabel = node.items.getOrNull(node.selectedIndex)
                ?: node.items.firstOrNull()
                ?: node.text.ifBlank { "Select" }
            Box(modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(
                    onClick = { expanded = true },
                    enabled = node.enabled,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .testTag("scriptui_dropdown_${node.id}")
                ) {
                    Text(currentLabel, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                DropdownMenu(
                    expanded = expanded,
                    onDismissRequest = { expanded = false }
                ) {
                    node.items.forEachIndexed { idx, label ->
                        DropdownMenuItem(
                            text = { Text(label) },
                            onClick = {
                                expanded = false
                                onInteract(node.id, null, null, null, idx, false)
                            }
                        )
                    }
                }
            }
        }

        ScriptUiWidgetKind.PROGRESS_BAR -> {
            val denom = (node.maxValue - node.minValue).coerceAtLeast(0.001f)
            val frac = ((node.value - node.minValue) / denom).coerceIn(0f, 1f)
            LinearProgressIndicator(
                progress = { frac },
                modifier = Modifier.fillMaxWidth()
            )
        }

        ScriptUiWidgetKind.CUSTOM -> {
            val hDp = node.preferredHeight.coerceIn(60, 240).dp
            val primaryColor = MaterialTheme.colorScheme.primary
            val surfaceLow = MaterialTheme.colorScheme.surfaceContainerLow
            val secondaryColor = MaterialTheme.colorScheme.secondary

            var cp1X by remember(node.id) { mutableFloatStateOf(0.35f) }
            var cp1Y by remember(node.id) { mutableFloatStateOf(0.82f) }
            var cp2X by remember(node.id) { mutableFloatStateOf(0.45f) }
            var cp2Y by remember(node.id) { mutableFloatStateOf(0.16f) }
            var activeHandle by remember(node.id) { mutableIntStateOf(0) }

            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(hDp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(surfaceLow)
                    .pointerInput(node.id, node.enabled) {
                        if (!node.enabled) return@pointerInput
                        detectDragGestures(
                            onDragStart = { offset ->
                                val w = size.width.toFloat().coerceAtLeast(1f)
                                val h = size.height.toFloat().coerceAtLeast(1f)
                                val p1 = Offset(cp1X * w, cp1Y * h)
                                val p2 = Offset(cp2X * w, cp2Y * h)
                                val d1 = (offset - p1).getDistance()
                                val d2 = (offset - p2).getDistance()
                                activeHandle = if (d1 <= d2) 1 else 2
                            },
                            onDrag = { change, dragAmount ->
                                change.consume()
                                val w = size.width.toFloat().coerceAtLeast(1f)
                                val h = size.height.toFloat().coerceAtLeast(1f)
                                if (activeHandle == 1) {
                                    cp1X = (cp1X + dragAmount.x / w).coerceIn(0f, 1f)
                                    cp1Y = (cp1Y + dragAmount.y / h).coerceIn(0f, 1f)
                                } else {
                                    cp2X = (cp2X + dragAmount.x / w).coerceIn(0f, 1f)
                                    cp2Y = (cp2Y + dragAmount.y / h).coerceIn(0f, 1f)
                                }
                                onInteract(node.id, null, (cp1X * 100f), null, null, false)
                            }
                        )
                    }
                    .clickable(enabled = node.enabled) {
                        onInteract(node.id, null, null, null, null, true)
                    }
                    .testTag("scriptui_custom_canvas_${node.id}")
            ) {
                if (node.drawOps.isEmpty()) {
                    val p0 = Offset(size.width * 0.08f, size.height * 0.90f)
                    val p3 = Offset(size.width * 0.92f, size.height * 0.10f)
                    val h1 = Offset(cp1X * size.width, cp1Y * size.height)
                    val h2 = Offset(cp2X * size.width, cp2Y * size.height)

                    // Tangent handle lines
                    drawLine(color = primaryColor.copy(alpha = 0.45f), start = p0, end = h1, strokeWidth = 1.8f)
                    drawLine(color = secondaryColor.copy(alpha = 0.45f), start = p3, end = h2, strokeWidth = 1.8f)

                    // Cubic Bezier curve
                    val p = Path().apply {
                        moveTo(p0.x, p0.y)
                        cubicTo(h1.x, h1.y, h2.x, h2.y, p3.x, p3.y)
                    }
                    drawPath(p, color = primaryColor, style = Stroke(width = 3.2f))

                    // Tangent handle circular knobs
                    drawCircle(color = primaryColor, radius = 7.dp.toPx(), center = h1)
                    drawCircle(color = secondaryColor, radius = 7.dp.toPx(), center = h2)
                    drawCircle(color = Color.White, radius = 3.dp.toPx(), center = h1)
                    drawCircle(color = Color.White, radius = 3.dp.toPx(), center = h2)
                } else {
                    node.drawOps.forEach { op ->
                        when (op) {
                            is ScriptUiDrawOp.FillRect -> {
                                drawRect(
                                    color = Color(op.r, op.g, op.b, op.a),
                                    topLeft = Offset(op.x, op.y),
                                    size = Size(
                                        if (op.width > 0f) op.width else size.width,
                                        if (op.height > 0f) op.height else size.height
                                    )
                                )
                            }
                            is ScriptUiDrawOp.StrokePath -> {
                                if (op.points.size >= 2) {
                                    val path = Path().apply {
                                        op.points.forEachIndexed { idx, (px, py) ->
                                            if (idx == 0) moveTo(px, py) else lineTo(px, py)
                                        }
                                        if (op.closed) close()
                                    }
                                    drawPath(
                                        path = path,
                                        color = Color(op.r, op.g, op.b, op.a),
                                        style = Stroke(width = op.strokeWidth.coerceAtLeast(1f))
                                    )
                                }
                            }
                            is ScriptUiDrawOp.FillPath -> {
                                if (op.points.size >= 3) {
                                    val path = Path().apply {
                                        op.points.forEachIndexed { idx, (px, py) ->
                                            if (idx == 0) moveTo(px, py) else lineTo(px, py)
                                        }
                                        close()
                                    }
                                    drawPath(path = path, color = Color(op.r, op.g, op.b, op.a))
                                }
                            }
                            is ScriptUiDrawOp.DrawText -> Unit
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ScriptUiChildrenLayout(
    node: ScriptUiWidgetSnapshot,
    onInteract: (String, String?, Float?, Boolean?, Int?, Boolean) -> Unit
) {
    if (node.orientation.equals("row", ignoreCase = true)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            node.children.forEach { child ->
                Box(modifier = Modifier.weight(1f, fill = false)) {
                    ScriptUiNodeComposable(child, onInteract)
                }
            }
        }
    } else {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            node.children.forEach { child ->
                ScriptUiNodeComposable(child, onInteract)
            }
        }
    }
    if (node.kind == ScriptUiWidgetKind.PANEL && node.children.isEmpty()) {
        HorizontalDivider()
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun CepPanelWebView(
    plugin: InstalledPlugin,
    pluginRepository: PluginHostRepository,
    onStatusMessage: (String) -> Unit,
    onRequestResize: ((Int, Int) -> Unit)?
) {
    val scope = rememberCoroutineScope()
    val bgArgb = MaterialTheme.colorScheme.surfaceContainerHigh.toArgb()
    val safeHost = remember(plugin.bundleId) {
        "${PluginArchiveImporter.sanitizeSegment(plugin.bundleId).lowercase(Locale.US).replace('_', '-')}.plugin.local"
    }
    val entryRel = plugin.entryHtmlRelativePath.trimStart('/', '\\').ifBlank { "index.html" }
    val entryUrl = "https://$safeHost/$entryRel"

    LaunchedEffect(plugin.bundleId) {
        pluginRepository.ensureHostLoaded(plugin.bundleId)
    }

    val webViewRef = remember { arrayOfNulls<WebView>(1) }

    DisposableEffect(plugin.bundleId) {
        onDispose {
            webViewRef[0]?.let { wv ->
                runCatching {
                    wv.stopLoading()
                    wv.removeJavascriptInterface("__fpCepNative")
                    wv.destroy()
                }
            }
            webViewRef[0] = null
        }
    }

    AndroidView(
        modifier = Modifier
            .fillMaxSize()
            .testTag("cep_webview_${plugin.bundleId}"),
        factory = { ctx ->
            WebView(ctx).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
                setBackgroundColor(bgArgb)
                settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    allowFileAccess = false
                    allowContentAccess = false
                    @Suppress("DEPRECATION")
                    allowFileAccessFromFileURLs = false
                    @Suppress("DEPRECATION")
                    allowUniversalAccessFromFileURLs = false
                    mediaPlaybackRequiresUserGesture = false
                    mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                }

                val jsBridge = CepSandboxNativeBridge(
                    plugin = plugin,
                    pluginRepository = pluginRepository,
                    scope = scope,
                    webViewProvider = { this },
                    panelBgArgb = bgArgb,
                    onStatusMessage = onStatusMessage,
                    onRequestResize = onRequestResize
                )
                addJavascriptInterface(jsBridge, "__fpCepNative")

                val installRoot = File(plugin.installDirPath).canonicalFile
                val shimBytesProvider = { buildCepBootstrapShimJs(plugin, bgArgb) }

                webViewClient = object : WebViewClient() {
                    override fun shouldInterceptRequest(
                        view: WebView?,
                        request: WebResourceRequest?
                    ): WebResourceResponse? {
                        val reqUri = request?.url ?: return null
                        val host = reqUri.host?.lowercase(Locale.US).orEmpty()
                        if (host != safeHost) {
                            return if (plugin.isFileNetworkAllowed()) {
                                super.shouldInterceptRequest(view, request)
                            } else {
                                WebResourceResponse(
                                    "text/plain",
                                    "UTF-8",
                                    403,
                                    "Blocked by plugin sandbox",
                                    emptyMap(),
                                    ByteArrayInputStream(ByteArray(0))
                                )
                            }
                        }

                        val relPath = (reqUri.path ?: "/").trimStart('/')
                        val target = File(installRoot, relPath).canonicalFile
                        if (!target.path.startsWith(installRoot.path + File.separator) && target != installRoot) {
                            return WebResourceResponse(
                                "text/plain",
                                "UTF-8",
                                403,
                                "Forbidden path",
                                emptyMap(),
                                ByteArrayInputStream(ByteArray(0))
                            )
                        }
                        if (!target.exists() || !target.isFile) {
                            return WebResourceResponse(
                                "text/plain",
                                "UTF-8",
                                404,
                                "Not Found",
                                emptyMap(),
                                ByteArrayInputStream(ByteArray(0))
                            )
                        }

                        val mime = guessMimeType(target.name)
                        if (mime == "text/html") {
                            val rawHtml = target.readText(Charsets.UTF_8)
                            val injectedHtml = injectCepShimBeforeDocumentScripts(rawHtml, shimBytesProvider())
                            return WebResourceResponse(
                                "text/html",
                                "UTF-8",
                                ByteArrayInputStream(injectedHtml.toByteArray(Charsets.UTF_8))
                            )
                        }
                        return WebResourceResponse(mime, "UTF-8", target.inputStream().buffered())
                    }
                }

                webViewRef[0] = this
                loadUrl(entryUrl)
            }
        }
    )
}

private class CepSandboxNativeBridge(
    private val plugin: InstalledPlugin,
    private val pluginRepository: PluginHostRepository,
    private val scope: CoroutineScope,
    private val webViewProvider: () -> WebView?,
    private val panelBgArgb: Int,
    private val onStatusMessage: (String) -> Unit,
    private val onRequestResize: ((Int, Int) -> Unit)?
) {
    private val sandboxRoot: File = File(plugin.dataDirPath).canonicalFile.apply { mkdirs() }
    private val installRoot: File = File(plugin.installDirPath).canonicalFile
    private val rng = SecureRandom()

    private fun resolveSandboxFile(rawPath: String): File? {
        val cleaned = rawPath.replace('\\', '/').trim()
        val candidate = when {
            cleaned.startsWith(sandboxRoot.path) -> File(cleaned)
            cleaned.startsWith(installRoot.path) -> File(cleaned)
            else -> File(sandboxRoot, cleaned.trimStart('/'))
        }.canonicalFile
        val inSandbox = candidate.path == sandboxRoot.path || candidate.path.startsWith(sandboxRoot.path + File.separator)
        val inInstall = candidate.path == installRoot.path || candidate.path.startsWith(installRoot.path + File.separator)
        return if (inSandbox || inInstall) candidate else null
    }

    private fun resolveWritableSandboxFile(rawPath: String): File? {
        val cleaned = rawPath.replace('\\', '/').trim()
        val rel = if (cleaned.startsWith(sandboxRoot.path)) {
            cleaned.removePrefix(sandboxRoot.path).trimStart('/')
        } else {
            cleaned.substringAfterLast("plugin-data/", cleaned).trimStart('/')
        }
        val candidate = File(sandboxRoot, rel).canonicalFile
        val ok = candidate.path == sandboxRoot.path || candidate.path.startsWith(sandboxRoot.path + File.separator)
        return if (ok) candidate else null
    }

    @JavascriptInterface
    fun evalScriptAsync(callId: String, script: String) {
        scope.launch(Dispatchers.Default) {
            val result = pluginRepository.evalPluginScript(plugin.bundleId, script)
            val responseText = result.getOrElse { "EvalScript error: ${it.message}" }
            val escaped = JSONObject.quote(responseText)
            val safeId = JSONObject.quote(callId)
            webViewProvider()?.post {
                webViewProvider()?.evaluateJavascript(
                    "window.__fpResolveEvalScript && window.__fpResolveEvalScript($safeId, $escaped);",
                    null
                )
            }
            if (result.isSuccess && script.contains("apply", ignoreCase = true)) {
                onStatusMessage("Applied ${plugin.menuName.ifBlank { plugin.name }}")
            }
        }
    }

    @JavascriptInterface
    fun getSystemPath(pathType: String): String {
        return when (pathType.lowercase(Locale.US)) {
            "extension" -> installRoot.path
            else -> sandboxRoot.path
        }
    }

    @JavascriptInterface
    fun resizeContent(width: Int, height: Int) {
        if (width > 0 && height > 0) {
            webViewProvider()?.post {
                onRequestResize?.invoke(width, height)
            }
        }
    }

    @JavascriptInterface
    fun fsExists(path: String): Boolean = resolveSandboxFile(path)?.exists() == true

    @JavascriptInterface
    fun fsReadFile(path: String): String {
        val f = resolveSandboxFile(path) ?: return JSONObject().put("err", 3).toString()
        if (!f.exists() || !f.isFile) return JSONObject().put("err", 2).toString()
        return runCatching {
            JSONObject().put("err", 0).put("data", f.readText(Charsets.UTF_8)).toString()
        }.getOrElse { JSONObject().put("err", 1).toString() }
    }

    @JavascriptInterface
    fun fsWriteFile(path: String, data: String, encoding: String): Int {
        val f = resolveWritableSandboxFile(path) ?: return 3
        return runCatching {
            f.parentFile?.mkdirs()
            if (encoding.equals("Base64", ignoreCase = true)) {
                f.writeBytes(Base64.decode(data, Base64.DEFAULT))
            } else {
                f.writeText(data, Charsets.UTF_8)
            }
            0
        }.getOrDefault(1)
    }

    @JavascriptInterface
    fun fsMkdir(path: String): Int {
        val dir = resolveWritableSandboxFile(path) ?: return 3
        return if (dir.exists() || dir.mkdirs()) 0 else 1
    }

    @JavascriptInterface
    fun fsReaddir(path: String): String {
        val dir = resolveSandboxFile(path) ?: return JSONObject().put("err", 3).toString()
        if (!dir.exists() || !dir.isDirectory) return JSONObject().put("err", 2).toString()
        val arr = org.json.JSONArray()
        dir.list()?.sorted()?.forEach { arr.put(it) }
        return JSONObject().put("err", 0).put("data", arr).toString()
    }

    @JavascriptInterface
    fun fsDeleteFile(path: String): Int {
        val f = resolveWritableSandboxFile(path) ?: return 3
        return if (!f.exists() || f.deleteRecursively()) 0 else 1
    }

    @JavascriptInterface
    fun randomBytesHex(count: Int): String {
        val buf = ByteArray(count.coerceIn(1, 256))
        rng.nextBytes(buf)
        return buf.joinToString("") { "%02x".format(it) }
    }
}

private fun buildCepBootstrapShimJs(plugin: InstalledPlugin, panelBgArgb: Int): String {
    val r = (panelBgArgb shr 16) and 0xFF
    val g = (panelBgArgb shr 8) and 0xFF
    val b = panelBgArgb and 0xFF
    val hostEnvJson = JSONObject().apply {
        put("appName", "AEFT")
        put("appVersion", "24.0.0")
        put("appLocale", "en_US")
        put("appUILocale", "en_US")
        put("appId", "AEFT")
        put("isAppOnline", plugin.isFileNetworkAllowed())
        put("appSkinInfo", JSONObject().apply {
            put("baseFontFamily", "sans-serif")
            put("baseFontSize", 12)
            put("panelBackgroundColor", JSONObject().apply {
                put("type", 1)
                put("color", JSONObject().apply {
                    put("red", r)
                    put("green", g)
                    put("blue", b)
                    put("alpha", 255)
                })
            })
        })
    }.toString()

    val sandboxPath = JSONObject.quote(plugin.dataDirPath)
    val extPath = JSONObject.quote(plugin.installDirPath)
    val extId = JSONObject.quote(plugin.bundleId)

    return """
(function() {
  if (window.__adobe_cep__) return;
  var _evalCallbacks = {};
  var _listeners = {};
  var _seq = 1;
  window.__fpResolveEvalScript = function(id, res) {
    var cb = _evalCallbacks[id];
    delete _evalCallbacks[id];
    if (typeof cb === 'function') {
      try { cb(res); } catch (e) { console.error(e); }
    }
  };
  window.__adobe_cep__ = {
    evalScript: function(script, callback) {
      var id = 'es_' + (_seq++);
      if (typeof callback === 'function') _evalCallbacks[id] = callback;
      window.__fpCepNative.evalScriptAsync(id, String(script || ''));
    },
    getHostEnvironment: function() { return JSON.stringify($hostEnvJson); },
    getSystemPath: function(kind) { return String(window.__fpCepNative.getSystemPath(String(kind || 'userData'))); },
    getExtensionId: function() { return $extId; },
    getCurrentApiVersion: function() { return JSON.stringify({ major: 11, minor: 0, micro: 0 }); },
    addEventListener: function(type, fn) {
      if (!_listeners[type]) _listeners[type] = [];
      _listeners[type].push(fn);
    },
    removeEventListener: function(type, fn) {
      if (!_listeners[type]) return;
      _listeners[type] = _listeners[type].filter(function(f) { return f !== fn; });
    },
    dispatchEvent: function(evt) {
      var arr = _listeners[evt && evt.type] || [];
      arr.forEach(function(fn) { try { fn(evt); } catch (e) {} });
    },
    resizeContent: function(w, h) { window.__fpCepNative.resizeContent(w|0, h|0); },
    registerInvalidCertificateCallback: function() {},
    registerKeyEventsInterest: function() {},
    setWindowTitle: function() {},
    getWindowTitle: function() { return ${JSONObject.quote(plugin.name)}; },
    getScaleFactor: function() { return window.devicePixelRatio || 1; },
    invokeSync: function() { return ''; }
  };
  window.cep = {
    encoding: { UTF8: 'UTF8', Base64: 'Base64' },
    fs: {
      NO_ERROR: 0, ERR_UNKNOWN: 1, ERR_NOT_FOUND: 2, ERR_CANT_READ: 3,
      readFile: function(p) { return JSON.parse(window.__fpCepNative.fsReadFile(String(p))); },
      writeFile: function(p, d, enc) { return { err: window.__fpCepNative.fsWriteFile(String(p), String(d || ''), String(enc || 'UTF8')) }; },
      makedir: function(p) { return { err: window.__fpCepNative.fsMkdir(String(p)) }; },
      readdir: function(p) { return JSON.parse(window.__fpCepNative.fsReaddir(String(p))); },
      deleteFile: function(p) { return { err: window.__fpCepNative.fsDeleteFile(String(p)) }; }
    },
    util: { openURLInDefaultBrowser: function() {} },
    process: { createProcess: function() { return { err: 1 }; } }
  };
  window.cep_node = {
    require: function(mod) { return window.require(mod); }
  };
  window.process = {
    platform: 'linux',
    env: { HOME: $sandboxPath, APPDATA: $sandboxPath },
    cwd: function() { return $extPath; }
  };
  window.require = function(mod) {
    var m = String(mod || '').replace(/^node:/, '');
    if (m === 'path') {
      return {
        sep: '/',
        join: function() { return Array.prototype.slice.call(arguments).filter(Boolean).join('/').replace(/\/+/g, '/'); },
        dirname: function(p) { var s = String(p||''); var i = s.lastIndexOf('/'); return i <= 0 ? '/' : s.slice(0, i); },
        basename: function(p) { var s = String(p||''); return s.slice(s.lastIndexOf('/') + 1); },
        extname: function(p) { var b = this.basename(p); var i = b.lastIndexOf('.'); return i <= 0 ? '' : b.slice(i); },
        resolve: function() { return Array.prototype.slice.call(arguments).filter(Boolean).join('/').replace(/\/+/g, '/'); }
      };
    }
    if (m === 'os') {
      return {
        homedir: function() { return $sandboxPath; },
        tmpdir: function() { return $sandboxPath + '/tmp'; },
        platform: function() { return 'android'; },
        EOL: '\n'
      };
    }
    if (m === 'fs') {
      return {
        existsSync: function(p) { return !!window.__fpCepNative.fsExists(String(p)); },
        readFileSync: function(p) {
          var r = JSON.parse(window.__fpCepNative.fsReadFile(String(p)));
          if (r.err) throw new Error('ENOENT: ' + p);
          return r.data;
        },
        writeFileSync: function(p, data) {
          var err = window.__fpCepNative.fsWriteFile(String(p), typeof data === 'string' ? data : JSON.stringify(data), 'UTF8');
          if (err) throw new Error('EIO: ' + p);
        },
        mkdirSync: function(p) { window.__fpCepNative.fsMkdir(String(p)); },
        readdirSync: function(p) {
          var r = JSON.parse(window.__fpCepNative.fsReaddir(String(p)));
          return r.data || [];
        },
        unlinkSync: function(p) { window.__fpCepNative.fsDeleteFile(String(p)); }
      };
    }
    if (m === 'crypto') {
      return {
        randomUUID: function() {
          var h = window.__fpCepNative.randomBytesHex(16);
          return h.slice(0,8)+'-'+h.slice(8,12)+'-4'+h.slice(13,16)+'-a'+h.slice(17,20)+'-'+h.slice(20,32);
        },
        randomBytes: function(n) { return window.__fpCepNative.randomBytesHex(n || 16); }
      };
    }
    throw new Error("Module '" + mod + "' is blocked in mobile CEP sandbox");
  };
  // Touch -> Mouse event bridge so desktop <canvas> bezier curve handles drag smoothly on mobile!
  function forwardTouch(touchEvt, mouseType) {
    var t = (touchEvt.touches && touchEvt.touches[0]) || (touchEvt.changedTouches && touchEvt.changedTouches[0]);
    if (!t || !touchEvt.target) return;
    if (touchEvt.target.tagName === 'CANVAS') {
      touchEvt.preventDefault();
    }
    var mEvt = new MouseEvent(mouseType, {
      bubbles: true, cancelable: true, view: window,
      clientX: t.clientX, clientY: t.clientY, screenX: t.screenX, screenY: t.screenY, button: 0, buttons: mouseType === 'mouseup' ? 0 : 1
    });
    touchEvt.target.dispatchEvent(mEvt);
  }
  document.addEventListener('touchstart', function(e) { forwardTouch(e, 'mousedown'); }, { passive: false });
  document.addEventListener('touchmove', function(e) { forwardTouch(e, 'mousemove'); }, { passive: false });
  document.addEventListener('touchend', function(e) { forwardTouch(e, 'mouseup'); }, { passive: false });
})();
""".trimIndent()
}

private fun injectCepShimBeforeDocumentScripts(rawHtml: String, shimJs: String): String {
    val scriptTag = "<script>$shimJs</script>"
    val headIdx = rawHtml.indexOf("<head>", ignoreCase = true)
    return if (headIdx >= 0) {
        val insertPos = headIdx + "<head>".length
        rawHtml.substring(0, insertPos) + "\n" + scriptTag + "\n" + rawHtml.substring(insertPos)
    } else {
        "$scriptTag\n$rawHtml"
    }
}

private fun guessMimeType(fileName: String): String {
    val lower = fileName.lowercase(Locale.US)
    return when {
        lower.endsWith(".html") || lower.endsWith(".htm") -> "text/html"
        lower.endsWith(".js") || lower.endsWith(".mjs") -> "application/javascript"
        lower.endsWith(".css") -> "text/css"
        lower.endsWith(".json") -> "application/json"
        lower.endsWith(".svg") -> "image/svg+xml"
        lower.endsWith(".png") -> "image/png"
        lower.endsWith(".jpg") || lower.endsWith(".jpeg") -> "image/jpeg"
        lower.endsWith(".webp") -> "image/webp"
        lower.endsWith(".woff2") -> "font/woff2"
        lower.endsWith(".woff") -> "font/woff"
        lower.endsWith(".ttf") -> "font/ttf"
        else -> URLConnection.guessContentTypeFromName(fileName) ?: "application/octet-stream"
    }
}
