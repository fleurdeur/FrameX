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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.BookmarkAdd
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.data.model.CustomEffectPresetDocument
import com.example.data.model.EffectNode
import com.example.data.model.Layer
import com.example.data.model.LayerType
import com.example.engine.AudioSpectrumSnapshot
import com.example.engine.EffectCategory
import com.example.engine.EffectDefinition
import com.example.engine.EffectThumbnailPreviewer
import com.example.engine.EffectsCatalog
import com.example.engine.InstalledPluginEffect
import com.example.engine.InstalledPluginSuite
import com.example.engine.PluginPackManager
import com.example.engine.UiOptimizationEngine
import com.example.ui.components.AmPrimaryCtaButton
import com.example.ui.components.AmSegmentChip
import com.example.ui.components.AmSquareIconButton
import com.example.ui.theme.AmBgDark
import com.example.ui.theme.AmTeal
import com.example.ui.theme.AmTealBright
import com.example.ui.theme.AudioCyan
import com.example.ui.theme.DeepBlack
import com.example.ui.theme.ElectricBlue
import com.example.ui.theme.KeyflowBlue
import com.example.ui.theme.KeyflowBlueBright
import com.example.ui.theme.KeyflowBlueContainer
import com.example.ui.theme.KeyframeAmber
import com.example.ui.theme.NeonPink
import com.example.ui.theme.OutlineSubtle
import com.example.ui.theme.PanelDark
import com.example.ui.theme.SurfaceCard
import com.example.ui.theme.SurfaceDark
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import com.example.viewmodel.UiState
import kotlin.math.sin

private data class EffectCategoryGroup(
    val key: String,
    val title: String,
    val isPluginGroup: Boolean,
    val effects: List<EffectDefinition>
)

@Composable
fun EffectsBrowserScreen(
    selectedLayer: Layer?,
    playheadMs: Long = 0L,
    audioSpectrum: AudioSpectrumSnapshot = AudioSpectrumSnapshot(),
    searchQuery: String,
    selectedCategory: EffectCategory?,
    selectedPluginSubcategory: String? = null,
    pluginSuitesRevision: Int = 0,
    cloudPresetsState: UiState<List<CustomEffectPresetDocument>>,
    onSearchChange: (String) -> Unit,
    onCategoryChange: (EffectCategory?) -> Unit,
    onPluginSubcategoryChange: (String?) -> Unit = {},
    onOpenPluginManager: () -> Unit = {},
    onImportPluginPackUri: (Uri) -> Unit = {},
    onAddEffect: (EffectDefinition) -> Unit,
    onUpdateEffectNode: (EffectNode) -> Unit,
    onRemoveEffectNode: (String) -> Unit,
    onSaveChainAsPreset: (String) -> Unit,
    onApplyCloudPreset: (CustomEffectPresetDocument) -> Unit,
    onOpenEffectInspector: (String) -> Unit = {},
    onBack: () -> Unit
) {
    val importPluginLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            onImportPluginPackUri(uri)
        }
    }

    val installedSuites by PluginPackManager.installedSuites.collectAsStateWithLifecycle()

    val pluginMapById = remember(installedSuites, pluginSuitesRevision) {
        installedSuites.flatMap { it.effects }.associateBy { it.id }
    }

    // Dynamic `effect.json` category list from installed packs + official categories
    val availablePluginCategories = remember(installedSuites, pluginSuitesRevision) {
        val dynamicCats = installedSuites
            .filter { it.enabled }
            .flatMap { it.effects }
            .map { it.category.trim().ifBlank { "Custom" } }
            .distinct()
        (dynamicCats + PluginPackManager.OFFICIAL_CATEGORIES).distinct()
    }

    val filteredEffects by remember(
        searchQuery,
        selectedCategory,
        selectedPluginSubcategory,
        pluginSuitesRevision,
        installedSuites
    ) {
        derivedStateOf {
            EffectsCatalog.allEffects.filter { fx ->
                val matchesCat = selectedCategory == null || fx.category == selectedCategory
                val plug = pluginMapById[fx.id]
                val matchesPluginSub = if (selectedPluginSubcategory != null &&
                    (selectedCategory == null || selectedCategory == EffectCategory.PLUGINS)
                ) {
                    if (fx.category == EffectCategory.PLUGINS) {
                        plug?.category?.equals(selectedPluginSubcategory, ignoreCase = true) == true
                    } else {
                        selectedCategory == null && false
                    }
                } else {
                    true
                }
                val matchesQuery = searchQuery.isBlank() ||
                    fx.name.contains(searchQuery, ignoreCase = true) ||
                    fx.id.contains(searchQuery, ignoreCase = true) ||
                    fx.summary.contains(searchQuery, ignoreCase = true) ||
                    (plug != null && (
                        plug.suiteName.contains(searchQuery, ignoreCase = true) ||
                            plug.author.contains(searchQuery, ignoreCase = true) ||
                            plug.category.contains(searchQuery, ignoreCase = true)
                        ))
                matchesCat && matchesPluginSub && matchesQuery
            }
        }
    }

    // Group effects cleanly:
    // - Plugin effects are ALWAYS grouped by their `effect.json` `category`!
    // - Built-in effects are grouped by their `EffectCategory.title`.
    val groupedSections by remember(filteredEffects, pluginMapById) {
        derivedStateOf {
            val pluginFx = filteredEffects.filter { it.category == EffectCategory.PLUGINS }
            val builtInFx = filteredEffects.filter { it.category != EffectCategory.PLUGINS }

            val pluginGroups = pluginFx
                .groupBy { fx ->
                    pluginMapById[fx.id]?.category?.trim()?.takeIf { it.isNotEmpty() } ?: "Custom"
                }
                .entries
                .sortedBy { it.key }
                .map { (catName, list) ->
                    EffectCategoryGroup(
                        key = "plugin_cat_$catName",
                        title = catName,
                        isPluginGroup = true,
                        effects = list
                    )
                }

            val builtInGroups = builtInFx
                .groupBy { it.category }
                .entries
                .map { (cat, list) ->
                    EffectCategoryGroup(
                        key = "builtin_cat_${cat.name}",
                        title = cat.title,
                        isPluginGroup = false,
                        effects = list
                    )
                }

            pluginGroups + builtInGroups
        }
    }

    var highlightedEffect by remember(pluginSuitesRevision) {
        mutableStateOf(
            EffectsCatalog.allEffects.firstOrNull() ?: EffectsCatalog.findById("Gaussian_Blur")
        )
    }
    val gridState = rememberLazyGridState()
    LaunchedEffect(gridState.isScrollInProgress) {
        UiOptimizationEngine.setListScrolling(gridState.isScrollInProgress)
    }
    DisposableEffect(Unit) {
        onDispose { UiOptimizationEngine.setListScrolling(false) }
    }
    val layerSig = remember(selectedLayer, playheadMs / 100L) {
        EffectThumbnailPreviewer.layerSignature(selectedLayer, playheadMs, audioSpectrum)
    }
    val addedByEffectId = remember(selectedLayer?.effects) {
        selectedLayer?.effects?.associateBy { it.effectId }.orEmpty()
    }

    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing,
        containerColor = AmBgDark,
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack, modifier = Modifier.testTag("effects_back_button")) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back to Editor")
                    }
                },
                title = {
                    Column {
                        Text(
                            text = "Effects Library",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = if (selectedLayer != null) {
                                "Target: ${selectedLayer.name} • ${filteredEffects.size} effects"
                            } else {
                                "${filteredEffects.size} effects available"
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = AmTealBright
                        )
                    }
                },
                actions = {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(end = 8.dp)
                    ) {
                        AmSquareIconButton(
                            icon = Icons.Default.FileUpload,
                            contentDescription = "Import .kfpack / .ffx",
                            tint = AmTealBright,
                            onClick = { importPluginLauncher.launch(arrayOf("*/*")) },
                            sizeDp = 36.dp,
                            testTag = "effects_import_kfpack_button"
                        )
                        AmSquareIconButton(
                            icon = Icons.Default.Extension,
                            contentDescription = "Manage Effect Packs",
                            active = selectedCategory == EffectCategory.PLUGINS,
                            tint = AmTealBright,
                            onClick = onOpenPluginManager,
                            sizeDp = 36.dp,
                            testTag = "effects_open_plugins_button"
                        )
                        if (selectedLayer != null && selectedLayer.effects.isNotEmpty()) {
                            AmSquareIconButton(
                                icon = Icons.Default.BookmarkAdd,
                                contentDescription = "Save effect chain to Cloud",
                                tint = AmTeal,
                                onClick = { onSaveChainAsPreset("${selectedLayer.name} Stack") },
                                sizeDp = 36.dp,
                                testTag = "save_effect_preset_button"
                            )
                        }
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
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // 1. Sleek Search Input with quick clear
            OutlinedTextField(
                value = searchQuery,
                onValueChange = onSearchChange,
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, tint = AmTealBright) },
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = { onSearchChange("") }, modifier = Modifier.size(32.dp)) {
                            Icon(Icons.Default.Close, contentDescription = "Clear search", tint = TextSecondary, modifier = Modifier.size(16.dp))
                        }
                    }
                },
                placeholder = { Text("Search effects, .kfpack packs, or authors...", color = TextMuted) },
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = AmTeal,
                    unfocusedBorderColor = OutlineSubtle,
                    focusedContainerColor = SurfaceDark,
                    unfocusedContainerColor = SurfaceDark
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("effects_search_input")
            )

            // 2. Primary Category Filter Strip
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                AmSegmentChip(
                    label = "All",
                    selected = selectedCategory == null && selectedPluginSubcategory == null,
                    onClick = {
                        onCategoryChange(null)
                        onPluginSubcategoryChange(null)
                    },
                    compact = true
                )
                EffectCategory.entries.forEach { cat ->
                    AmSegmentChip(
                        label = cat.title,
                        selected = selectedCategory == cat,
                        onClick = { onCategoryChange(cat) },
                        testTag = "effect_cat_${cat.name}",
                        compact = true
                    )
                }
            }

            // 3. `effect.json` Sub-Category Filter Strip when browsing All or Plugins
            if (selectedCategory == EffectCategory.PLUGINS || selectedPluginSubcategory != null) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    AmSegmentChip(
                        label = "All Pack Categories",
                        selected = selectedPluginSubcategory == null,
                        compact = true,
                        onClick = { onPluginSubcategoryChange(null) }
                    )
                    availablePluginCategories.forEach { kfCat ->
                        AmSegmentChip(
                            label = kfCat,
                            selected = selectedPluginSubcategory.equals(kfCat, ignoreCase = true),
                            compact = true,
                            onClick = { onPluginSubcategoryChange(kfCat) }
                        )
                    }
                }
            }

            // 4. Installed `.kfpack` Pack Metadata Strip: shows Pack Name, Author, and Version clearly!
            if (installedSuites.isNotEmpty() && (selectedCategory == null || selectedCategory == EffectCategory.PLUGINS)) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .testTag("installed_packs_metadata_strip"),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    installedSuites.forEach { suite ->
                        KfPackMetadataBadgeCard(
                            suite = suite,
                            onClick = {
                                onCategoryChange(EffectCategory.PLUGINS)
                            }
                        )
                    }
                }
            }

            // Cloud presets strip if available
            if (cloudPresetsState is UiState.Success && cloudPresetsState.data.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    cloudPresetsState.data.forEach { preset ->
                        AmSegmentChip(
                            label = preset.name,
                            selected = false,
                            onClick = { onApplyCloudPreset(preset) }
                        )
                    }
                }
            }

            // Active Effect Stack on Selected Layer
            if (selectedLayer != null && selectedLayer.effects.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    selectedLayer.effects.forEach { node ->
                        AmActiveEffectStripCard(
                            node = node,
                            onInspect = { onOpenEffectInspector(node.id) },
                            onUpdate = onUpdateEffectNode,
                            onRemove = { onRemoveEffectNode(node.id) }
                        )
                    }
                }
            }

            // Highlighted Effect Preview Header Card (with Pack Name • Author • Version when plugin)
            val highlightedPlug = pluginMapById[highlightedEffect.id]
            val highlightedIncompatible = highlightedEffect.isTextOnly &&
                selectedLayer != null && selectedLayer.type != LayerType.TEXT
            Card(
                colors = CardDefaults.cardColors(containerColor = SurfaceCard),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, KeyflowBlue.copy(alpha = 0.35f), RoundedCornerShape(14.dp))
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(10.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    AmEffectThumbnailBox(
                        selectedLayer = selectedLayer,
                        playheadMs = playheadMs,
                        audioSpectrum = audioSpectrum,
                        layerSig = layerSig,
                        effect = highlightedEffect,
                        accent = KeyflowBlueBright,
                        sizeDp = 50.dp
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = highlightedEffect.name,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = TextPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        val detailLine = when {
                            highlightedIncompatible -> "Requires a Text layer"
                            highlightedPlug != null ->
                                "${highlightedPlug.category} • ${highlightedPlug.suiteName} • by ${highlightedPlug.author} • v${highlightedPlug.version}"
                            else -> highlightedEffect.summary
                        }
                        Text(
                            text = detailLine,
                            style = MaterialTheme.typography.labelSmall,
                            color = if (highlightedIncompatible) NeonPink else TextSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    if (highlightedIncompatible) {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(NeonPink.copy(alpha = 0.14f))
                                .border(1.dp, NeonPink.copy(alpha = 0.45f), RoundedCornerShape(8.dp))
                                .padding(horizontal = 10.dp, vertical = 6.dp)
                                .testTag("effect_text_only_locked_badge")
                        ) {
                            Text(
                                text = "Text Only",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = NeonPink
                            )
                        }
                    } else {
                        AmPrimaryCtaButton(
                            text = "Add",
                            icon = Icons.Default.Add,
                            onClick = { onAddEffect(highlightedEffect) },
                            compact = true
                        )
                    }
                }
            }

            // 5. Categorized Effect Grid (grouped by `effect.json` category for packs & built-in category)
            LazyVerticalGrid(
                state = gridState,
                columns = GridCells.Adaptive(minSize = 104.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .testTag("effects_4col_grid"),
                contentPadding = PaddingValues(vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                groupedSections.forEach { section ->
                    item(
                        key = "header_${section.key}",
                        span = { GridItemSpan(maxLineSpan) }
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 6.dp, bottom = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(width = 4.dp, height = 14.dp)
                                        .clip(RoundedCornerShape(2.dp))
                                        .background(if (section.isPluginGroup) AmTealBright else KeyflowBlue)
                                )
                                Text(
                                    text = section.title,
                                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                                    color = TextPrimary
                                )
                                if (section.isPluginGroup) {
                                    Surface(
                                        color = AmTeal.copy(alpha = 0.16f),
                                        shape = RoundedCornerShape(6.dp)
                                    ) {
                                        Text(
                                            text = "Pack Category",
                                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
                                            color = AmTealBright,
                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                        )
                                    }
                                }
                            }
                            Text(
                                text = "${section.effects.size}",
                                style = MaterialTheme.typography.labelSmall,
                                color = TextMuted
                            )
                        }
                    }

                    itemsIndexed(
                        items = section.effects,
                        key = { idx, fx -> "${section.key}_${fx.id}_$idx" }
                    ) { _, fx ->
                        val existingNode = addedByEffectId[fx.id]
                        val plugMeta = pluginMapById[fx.id]
                        val isIncompatibleTextFx = fx.isTextOnly &&
                            selectedLayer != null && selectedLayer.type != LayerType.TEXT
                        AmEffectGridTile(
                            effect = fx,
                            pluginMeta = plugMeta,
                            selectedLayer = selectedLayer,
                            playheadMs = playheadMs,
                            audioSpectrum = audioSpectrum,
                            layerSig = layerSig,
                            isHighlighted = highlightedEffect.id == fx.id,
                            isDisabledForLayer = isIncompatibleTextFx,
                            addedNode = existingNode,
                            onTap = {
                                highlightedEffect = fx
                                if (!isIncompatibleTextFx) {
                                    onAddEffect(fx)
                                }
                            },
                            onRemoveAdded = existingNode?.let { node ->
                                { onRemoveEffectNode(node.id) }
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun KfPackMetadataBadgeCard(
    suite: InstalledPluginSuite,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .testTag("pack_badge_${suite.id}"),
        color = SurfaceCard,
        shape = RoundedCornerShape(12.dp),
        border = androidx.compose.foundation.BorderStroke(
            width = 1.dp,
            color = if (suite.enabled) AmTeal.copy(alpha = 0.45f) else OutlineSubtle
        )
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(
                        Brush.linearGradient(
                            listOf(KeyflowBlue.copy(alpha = 0.28f), AmTeal.copy(alpha = 0.22f))
                        )
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Extension,
                    contentDescription = suite.name,
                    tint = AmTealBright,
                    modifier = Modifier.size(16.dp)
                )
            }
            Column {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    Text(
                        text = suite.name,
                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                        color = TextPrimary,
                        maxLines = 1
                    )
                    Surface(
                        color = KeyflowBlue.copy(alpha = 0.22f),
                        shape = RoundedCornerShape(5.dp)
                    ) {
                        Text(
                            text = "v${suite.version}",
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp, fontWeight = FontWeight.Bold),
                            color = KeyflowBlueBright,
                            modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
                        )
                    }
                    if (suite.skippedEffectsReport.isNotEmpty()) {
                        Icon(
                            imageVector = Icons.Default.WarningAmber,
                            contentDescription = "Skipped broken shader(s)",
                            tint = KeyframeAmber,
                            modifier = Modifier.size(13.dp)
                        )
                    }
                }
                Text(
                    text = "by ${suite.author} • ${suite.effects.size} FX",
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                    color = TextSecondary,
                    maxLines = 1
                )
            }
        }
    }
}

@Composable
private fun AmEffectThumbnailBox(
    selectedLayer: Layer?,
    playheadMs: Long,
    audioSpectrum: AudioSpectrumSnapshot,
    layerSig: String,
    effect: EffectDefinition,
    accent: Color,
    sizeDp: Dp
) {
    val context = LocalContext.current
    val immediateCached = remember(layerSig, effect.id) {
        EffectThumbnailPreviewer.getCachedImmediate(layerSig, effect.id)
    }
    val thumbBitmap by produceState<ImageBitmap?>(
        initialValue = immediateCached,
        key1 = layerSig,
        key2 = effect.id
    ) {
        if (value == null) {
            value = runCatching {
                EffectThumbnailPreviewer.generateLowResEffectThumbnail(
                    context = context,
                    selectedLayer = selectedLayer,
                    effect = effect,
                    playheadMs = playheadMs,
                    audio = audioSpectrum
                )
            }.getOrNull()
        }
    }

    Box(
        modifier = Modifier
            .size(sizeDp)
            .clip(RoundedCornerShape(10.dp))
            .background(DeepBlack)
            .border(0.8.dp, accent.copy(alpha = 0.65f), RoundedCornerShape(10.dp))
            .testTag("effect_thumb_${effect.id}"),
        contentAlignment = Alignment.Center
    ) {
        val bmp = thumbBitmap
        if (bmp != null) {
            Image(
                bitmap = bmp,
                contentDescription = "${effect.name} preview on ${selectedLayer?.name ?: "sample layer"}",
                contentScale = ContentScale.Crop,
                filterQuality = FilterQuality.Low,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Canvas(modifier = Modifier.size(28.dp)) {
                val cx = size.width * 0.5f
                val cy = size.height * 0.5f
                when (effect.category) {
                    EffectCategory.PLUGINS -> {
                        drawCircle(AmTealBright.copy(alpha = 0.28f), radius = size.minDimension * 0.46f)
                        drawRect(AmTealBright, style = Stroke(width = 2.2f))
                    }
                    EffectCategory.DESKTOP_HOMAGE,
                    EffectCategory.BLUR_OPTICS,
                    EffectCategory.PARTICLES_LIGHT -> {
                        drawCircle(accent.copy(alpha = 0.3f), radius = size.minDimension * 0.48f)
                        drawCircle(accent, radius = size.minDimension * 0.26f, style = Stroke(width = 2.4f))
                    }
                    EffectCategory.TRANSITIONS,
                    EffectCategory.CAMERA_SHAKE -> {
                        val wave = Path().apply {
                            moveTo(0f, cy)
                            for (x in 0..20) {
                                val fx = (x / 20f) * size.width
                                val fy = cy + sin(x * 0.6f) * (size.height * 0.28f)
                                lineTo(fx, fy)
                            }
                        }
                        drawPath(wave, accent, style = Stroke(width = 2.4f))
                    }
                    EffectCategory.ALIGHT_STAPLES,
                    EffectCategory.STYLIZE_EDGES -> {
                        drawRect(accent, style = Stroke(width = 2.2f))
                        drawLine(accent.copy(alpha = 0.6f), Offset(0f, cy), Offset(size.width, cy), strokeWidth = 1.4f)
                        drawLine(accent.copy(alpha = 0.6f), Offset(cx, 0f), Offset(cx, size.height), strokeWidth = 1.4f)
                    }
                    EffectCategory.COLOR_LUTS,
                    EffectCategory.GLITCH_VHS -> {
                        drawCircle(ElectricBlue.copy(alpha = 0.8f), radius = 7f, center = Offset(cx - 3f, cy))
                        drawCircle(NeonPink.copy(alpha = 0.8f), radius = 7f, center = Offset(cx + 3f, cy))
                    }
                }
            }
        }
    }
}

@Composable
private fun AmEffectGridTile(
    effect: EffectDefinition,
    pluginMeta: InstalledPluginEffect?,
    selectedLayer: Layer?,
    playheadMs: Long,
    audioSpectrum: AudioSpectrumSnapshot,
    layerSig: String,
    isHighlighted: Boolean,
    isDisabledForLayer: Boolean = false,
    addedNode: EffectNode?,
    onTap: () -> Unit,
    onRemoveAdded: (() -> Unit)?
) {
    val accent = when (effect.category) {
        EffectCategory.PLUGINS -> KeyflowBlueBright
        EffectCategory.DESKTOP_HOMAGE, EffectCategory.BLUR_OPTICS -> ElectricBlue
        EffectCategory.TRANSITIONS, EffectCategory.PARTICLES_LIGHT -> AudioCyan
        EffectCategory.ALIGHT_STAPLES, EffectCategory.STYLIZE_EDGES -> KeyflowBlue
        EffectCategory.COLOR_LUTS, EffectCategory.GLITCH_VHS, EffectCategory.CAMERA_SHAKE -> NeonPink
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(126.dp)
            .alpha(if (isDisabledForLayer) 0.46f else 1f)
            .clip(RoundedCornerShape(12.dp))
            .background(
                if (isHighlighted || addedNode != null) KeyflowBlueContainer.copy(alpha = 0.68f) else PanelDark
            )
            .border(
                width = 1.dp,
                color = when {
                    addedNode != null -> KeyflowBlueBright
                    isHighlighted -> KeyflowBlue
                    else -> OutlineSubtle
                },
                shape = RoundedCornerShape(12.dp)
            )
            .clickable(onClick = onTap)
            .padding(horizontal = 7.dp, vertical = 8.dp)
            .testTag("effect_tile_${effect.id}")
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            AmEffectThumbnailBox(
                selectedLayer = selectedLayer,
                playheadMs = playheadMs,
                audioSpectrum = audioSpectrum,
                layerSig = layerSig,
                effect = effect,
                accent = accent,
                sizeDp = 54.dp
            )

            Column(
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = effect.name,
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center
                )
                if (pluginMeta != null) {
                    Text(
                        text = "${pluginMeta.suiteName} • v${pluginMeta.version}",
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
                        color = AmTealBright,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center
                    )
                    Text(
                        text = pluginMeta.author,
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
                        color = TextMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center
                    )
                } else {
                    Text(
                        text = effect.category.title,
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
                        color = TextMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center
                    )
                }
            }
        }

        if (effect.isTextOnly && addedNode == null) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .clip(RoundedCornerShape(4.dp))
                    .background(
                        if (isDisabledForLayer) NeonPink.copy(alpha = 0.25f) else KeyflowBlue.copy(alpha = 0.26f)
                    )
                    .padding(horizontal = 4.dp, vertical = 1.dp)
            ) {
                Text(
                    text = "TEXT",
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
                    fontWeight = FontWeight.Bold,
                    color = if (isDisabledForLayer) NeonPink else KeyflowBlueBright
                )
            }
        }

        if (pluginMeta != null && addedNode == null) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .clip(RoundedCornerShape(4.dp))
                    .background(KeyflowBlue.copy(alpha = 0.22f))
                    .padding(horizontal = 4.dp, vertical = 1.dp)
            ) {
                Text(
                    text = if (pluginMeta.passes.size > 1) "${pluginMeta.passes.size}P" else "KF",
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
                    fontWeight = FontWeight.Bold,
                    color = KeyflowBlueBright
                )
            }
        }

        if (addedNode != null && onRemoveAdded != null) {
            Row(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .clip(CircleShape)
                    .background(NeonPink)
                    .clickable(onClick = onRemoveAdded)
                    .testTag("browser_grid_remove_${addedNode.id}")
                    .padding(4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.DeleteOutline,
                    contentDescription = "Remove ${effect.name} from layer",
                    tint = Color.White,
                    modifier = Modifier.size(13.dp)
                )
            }
            Box(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .clip(CircleShape)
                    .background(KeyflowBlue)
                    .padding(3.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = "Effect applied",
                    tint = Color.White,
                    modifier = Modifier.size(11.dp)
                )
            }
        }
    }
}

@Composable
private fun AmActiveEffectStripCard(
    node: EffectNode,
    onInspect: () -> Unit,
    onUpdate: (EffectNode) -> Unit,
    onRemove: () -> Unit
) {
    val kfCount = remember(node) { node.allKeyframeTimes().size }
    Row(
        modifier = Modifier
            .height(40.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(SurfaceDark)
            .border(1.dp, KeyflowBlue.copy(alpha = 0.50f), RoundedCornerShape(10.dp))
            .clickable(onClick = onInspect)
            .padding(start = 10.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Icon(
            imageVector = Icons.Default.DragHandle,
            contentDescription = null,
            tint = KeyflowBlueBright,
            modifier = Modifier.size(15.dp)
        )
        Text(
            text = node.displayName,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            color = TextPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        if (kfCount > 0) {
            Text(
                text = "◆$kfCount",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = KeyflowBlueBright
            )
        }
        IconButton(
            onClick = { onUpdate(node.copy(enabled = !node.enabled)) },
            modifier = Modifier.size(30.dp)
        ) {
            Icon(
                imageVector = if (node.enabled) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                contentDescription = "Toggle effect visibility",
                tint = if (node.enabled) KeyflowBlueBright else TextMuted,
                modifier = Modifier.size(16.dp)
            )
        }
        IconButton(
            onClick = onRemove,
            modifier = Modifier
                .size(30.dp)
                .testTag("browser_delete_effect_${node.id}")
        ) {
            Icon(
                imageVector = Icons.Default.DeleteOutline,
                contentDescription = "Delete ${node.displayName}",
                tint = NeonPink,
                modifier = Modifier.size(16.dp)
            )
        }
    }
}
