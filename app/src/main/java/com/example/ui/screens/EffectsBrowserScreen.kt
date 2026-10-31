@file:OptIn(ExperimentalMaterial3Api::class)

package com.example.ui.screens

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
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.BookmarkAdd
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
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
import com.example.data.model.AudioBand
import com.example.data.model.CustomEffectPresetDocument
import com.example.data.model.EffectNode
import com.example.data.model.Layer
import com.example.engine.EffectCategory
import com.example.engine.EffectDefinition
import com.example.engine.EffectThumbnailPreviewer
import com.example.engine.EffectsCatalog
import com.example.ui.components.AmPrimaryCtaButton
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
import com.example.ui.theme.SurfaceDark
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import com.example.viewmodel.UiState
import kotlin.math.sin

@Composable
fun EffectsBrowserScreen(
    selectedLayer: Layer?,
    searchQuery: String,
    selectedCategory: EffectCategory?,
    cloudPresetsState: UiState<List<CustomEffectPresetDocument>>,
    onSearchChange: (String) -> Unit,
    onCategoryChange: (EffectCategory?) -> Unit,
    onAddEffect: (EffectDefinition) -> Unit,
    onUpdateEffectNode: (EffectNode) -> Unit,
    onRemoveEffectNode: (String) -> Unit,
    onSaveChainAsPreset: (String) -> Unit,
    onApplyCloudPreset: (CustomEffectPresetDocument) -> Unit,
    onOpenEffectInspector: (String) -> Unit = {},
    onBack: () -> Unit
) {
    val filteredEffects by remember(searchQuery, selectedCategory) {
        derivedStateOf {
            EffectsCatalog.allEffects.filter { fx ->
                val matchesCat = selectedCategory == null || fx.category == selectedCategory
                val matchesQuery = searchQuery.isBlank() ||
                    fx.name.contains(searchQuery, ignoreCase = true) ||
                    fx.id.contains(searchQuery, ignoreCase = true) ||
                    fx.summary.contains(searchQuery, ignoreCase = true)
                matchesCat && matchesQuery
            }
        }
    }

    var highlightedEffect by remember { mutableStateOf(EffectsCatalog.allEffects.first()) }
    val layerSig = remember(selectedLayer) { EffectThumbnailPreviewer.layerSignature(selectedLayer) }
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
                            text = "Add Effect (${EffectsCatalog.allEffects.size})",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = if (selectedLayer != null) {
                                "Live Low-Res Preview: ${selectedLayer.name} • ${selectedLayer.effects.size} stacked"
                            } else {
                                "Select a layer to preview & stack GPU shaders"
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = AmTealBright
                        )
                    }
                },
                actions = {
                    if (selectedLayer != null && selectedLayer.effects.isNotEmpty()) {
                        AmSquareIconButton(
                            icon = Icons.Default.BookmarkAdd,
                            contentDescription = "Save effect chain to Cloud",
                            tint = AmTeal,
                            onClick = { onSaveChainAsPreset("${selectedLayer.name} Stack") },
                            testTag = "save_effect_preset_button",
                            modifier = Modifier.padding(end = 10.dp)
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
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Alight Motion Dark Search Bar
            OutlinedTextField(
                value = searchQuery,
                onValueChange = onSearchChange,
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, tint = AmTeal) },
                placeholder = { Text("Search Effects (S_Glow, Wave Warp, Tiles, RGB...)") },
                singleLine = true,
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("effects_search_input")
            )

            // Alight Motion Category Tabs
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                AmSegmentChip(
                    label = "All (${EffectsCatalog.allEffects.size})",
                    selected = selectedCategory == null,
                    onClick = { onCategoryChange(null) },
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

            // Cloud Saved FX Stacks
            if (cloudPresetsState is UiState.Success && cloudPresetsState.data.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Saved Presets:", style = MaterialTheme.typography.labelSmall, color = TextSecondary)
                    cloudPresetsState.data.forEach { preset ->
                        AmSegmentChip(
                            label = preset.name,
                            selected = false,
                            onClick = { onApplyCloudPreset(preset) }
                        )
                    }
                }
            }

            // Alight Motion Active Effect Strip Stack (with direct Delete & Inspect)
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

            // Alight Motion Featured "+ STANDARD SETTINGS" Bar with Selected Layer Live Preview!
            Card(
                colors = CardDefaults.cardColors(containerColor = SurfaceDark),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, OutlineSubtle, RoundedCornerShape(12.dp))
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
                        layerSig = layerSig,
                        effect = highlightedEffect,
                        accent = AmTealBright,
                        sizeDp = 50.dp
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = highlightedEffect.name,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = AmTealBright
                        )
                        Text(
                            text = highlightedEffect.summary,
                            style = MaterialTheme.typography.labelSmall,
                            color = TextSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    AmPrimaryCtaButton(
                        text = "+ STANDARD",
                        icon = Icons.Default.Add,
                        onClick = { onAddEffect(highlightedEffect) },
                        compact = true
                    )
                }
            }

            // Alight Motion Adaptive Rounded-Square Effect Icon Grid with Low-Res Layer+Effect Thumbnails
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 90.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .testTag("effects_4col_grid"),
                contentPadding = PaddingValues(vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(filteredEffects, key = { it.id }) { fx ->
                    val existingNode = addedByEffectId[fx.id]
                    AmEffectGridTile(
                        effect = fx,
                        selectedLayer = selectedLayer,
                        layerSig = layerSig,
                        isHighlighted = highlightedEffect.id == fx.id,
                        addedNode = existingNode,
                        onTap = {
                            highlightedEffect = fx
                            onAddEffect(fx)
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

@Composable
private fun AmEffectThumbnailBox(
    selectedLayer: Layer?,
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
            value = EffectThumbnailPreviewer.generateLowResEffectThumbnail(
                context = context,
                selectedLayer = selectedLayer,
                effect = effect
            )
        }
    }

    Box(
        modifier = Modifier
            .size(sizeDp)
            .clip(RoundedCornerShape(9.dp))
            .background(DeepBlack)
            .border(0.8.dp, accent.copy(alpha = 0.65f), RoundedCornerShape(9.dp))
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
                    EffectCategory.DESKTOP_HOMAGES -> {
                        drawCircle(accent.copy(alpha = 0.3f), radius = size.minDimension * 0.48f)
                        drawCircle(accent, radius = size.minDimension * 0.26f, style = Stroke(width = 2.4f))
                    }
                    EffectCategory.NODE_PROCEDURAL -> {
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
                    EffectCategory.ALIGHT_STAPLES -> {
                        drawRect(accent, style = Stroke(width = 2.2f))
                        drawLine(accent.copy(alpha = 0.6f), Offset(0f, cy), Offset(size.width, cy), strokeWidth = 1.4f)
                        drawLine(accent.copy(alpha = 0.6f), Offset(cx, 0f), Offset(cx, size.height), strokeWidth = 1.4f)
                    }
                    EffectCategory.VIRAL_SOCIAL -> {
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
    selectedLayer: Layer?,
    layerSig: String,
    isHighlighted: Boolean,
    addedNode: EffectNode?,
    onTap: () -> Unit,
    onRemoveAdded: (() -> Unit)?
) {
    val accent = when (effect.category) {
        EffectCategory.DESKTOP_HOMAGES -> ElectricBlue
        EffectCategory.NODE_PROCEDURAL -> AudioCyan
        EffectCategory.ALIGHT_STAPLES -> AmTeal
        EffectCategory.VIRAL_SOCIAL -> NeonPink
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(114.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(if (isHighlighted || addedNode != null) AmTeal.copy(alpha = 0.14f) else SurfaceDark)
            .border(
                width = 1.dp,
                color = when {
                    addedNode != null -> AmTealBright
                    isHighlighted -> AmTeal
                    else -> OutlineSubtle
                },
                shape = RoundedCornerShape(10.dp)
            )
            .clickable(onClick = onTap)
            .padding(6.dp)
            .testTag("effect_tile_${effect.id}")
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // Live Low-Quality Layer + Effect Thumbnail Preview!
            AmEffectThumbnailBox(
                selectedLayer = selectedLayer,
                layerSig = layerSig,
                effect = effect,
                accent = accent,
                sizeDp = 54.dp
            )

            Text(
                text = effect.name,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center
            )
        }

        // Quick Delete badge when this effect is already stacked on the selected layer
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
                    .background(AmTeal)
                    .padding(3.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = "Effect applied",
                    tint = DeepBlack,
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
    Card(
        modifier = Modifier
            .width(262.dp)
            .border(1.dp, AmTeal.copy(alpha = 0.55f), RoundedCornerShape(10.dp))
            .clickable(onClick = onInspect),
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(containerColor = SurfaceDark)
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .weight(1f)
                        .clickable(onClick = onInspect)
                ) {
                    Icon(
                        imageVector = Icons.Default.DragHandle,
                        contentDescription = null,
                        tint = AmTealBright,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Column {
                        Text(
                            text = node.displayName,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = TextPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = "Tap for 5 Params • ◆ Keyframes & Curve ▸",
                            style = MaterialTheme.typography.labelSmall,
                            color = AmTealBright
                        )
                    }
                }
                IconButton(
                    onClick = { onUpdate(node.copy(enabled = !node.enabled)) },
                    modifier = Modifier.size(30.dp)
                ) {
                    Icon(
                        imageVector = if (node.enabled) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                        contentDescription = "Toggle effect visibility",
                        tint = if (node.enabled) AmTeal else TextSecondary,
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
                        modifier = Modifier.size(17.dp)
                    )
                }
            }

            Text(
                text = "Strength ${(node.intensity * 100).toInt()}%",
                style = MaterialTheme.typography.labelSmall,
                color = TextSecondary
            )
            Slider(
                value = node.intensity,
                onValueChange = { onUpdate(node.copy(intensity = it)) },
                valueRange = 0f..1.5f,
                colors = SliderDefaults.colors(thumbColor = AmTeal, activeTrackColor = AmTeal),
                modifier = Modifier.height(24.dp)
            )

            Text(
                text = "Frequency / Radius ${(node.secondaryParam * 100).toInt()}%",
                style = MaterialTheme.typography.labelSmall,
                color = TextSecondary
            )
            Slider(
                value = node.secondaryParam,
                onValueChange = { onUpdate(node.copy(secondaryParam = it)) },
                valueRange = 0f..1f,
                colors = SliderDefaults.colors(thumbColor = AmTeal, activeTrackColor = AmTeal),
                modifier = Modifier.height(24.dp)
            )

            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                AudioBand.entries.forEach { band ->
                    AmSegmentChip(
                        label = if (band == AudioBand.NONE) "Manual" else band.name,
                        selected = node.audioBoundBand == band,
                        onClick = { onUpdate(node.copy(audioBoundBand = band)) },
                        compact = true
                    )
                }
            }
        }
    }
}
