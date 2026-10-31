@file:OptIn(ExperimentalMaterial3Api::class)

package com.example.ui.screens

import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.BookmarkAdd
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.data.model.AudioBand
import com.example.data.model.CustomEffectPresetDocument
import com.example.data.model.EffectNode
import com.example.data.model.Layer
import com.example.engine.EffectCategory
import com.example.engine.EffectDefinition
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
import com.example.ui.theme.PanelDark
import com.example.ui.theme.SurfaceDark
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
                            text = if (selectedLayer != null) "${selectedLayer.name} • ${selectedLayer.effects.size} stacked"
                            else "Select a layer to stack GPU shaders",
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

            // Alight Motion Active Effect Strip Stack
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
                            onUpdate = onUpdateEffectNode,
                            onRemove = { onRemoveEffectNode(node.id) }
                        )
                    }
                }
            }

            // Alight Motion Featured "+ STANDARD SETTINGS" Quick-Apply Bar
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
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
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
                    Spacer(modifier = Modifier.width(10.dp))
                    AmPrimaryCtaButton(
                        text = "+ STANDARD",
                        icon = Icons.Default.Add,
                        onClick = { onAddEffect(highlightedEffect) },
                        compact = true
                    )
                }
            }

            // Alight Motion Adaptive Rounded-Square Effect Icon Grid
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 86.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .testTag("effects_4col_grid"),
                contentPadding = PaddingValues(vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(filteredEffects, key = { it.id }) { fx ->
                    AmEffectGridTile(
                        effect = fx,
                        isHighlighted = highlightedEffect.id == fx.id,
                        onTap = {
                            highlightedEffect = fx
                            onAddEffect(fx)
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun AmEffectGridTile(
    effect: EffectDefinition,
    isHighlighted: Boolean,
    onTap: () -> Unit
) {
    val accent = when (effect.category) {
        EffectCategory.DESKTOP_HOMAGES -> ElectricBlue
        EffectCategory.NODE_PROCEDURAL -> AudioCyan
        EffectCategory.ALIGHT_STAPLES -> AmTeal
        EffectCategory.VIRAL_SOCIAL -> NeonPink
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .height(102.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(if (isHighlighted) AmTeal.copy(alpha = 0.14f) else SurfaceDark)
            .border(
                width = 1.dp,
                color = if (isHighlighted) AmTeal else OutlineSubtle,
                shape = RoundedCornerShape(10.dp)
            )
            .clickable(onClick = onTap)
            .padding(6.dp)
            .testTag("effect_tile_${effect.id}"),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        // Alight Motion Square Vector Shader Preview Box
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(RoundedCornerShape(9.dp))
                .background(DeepBlack)
                .border(0.8.dp, accent.copy(alpha = 0.55f), RoundedCornerShape(9.dp)),
            contentAlignment = Alignment.Center
        ) {
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
}

@Composable
private fun AmActiveEffectStripCard(
    node: EffectNode,
    onUpdate: (EffectNode) -> Unit,
    onRemove: () -> Unit
) {
    Card(
        modifier = Modifier
            .width(256.dp)
            .border(1.dp, OutlineSubtle, RoundedCornerShape(10.dp)),
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(containerColor = SurfaceDark)
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                    Icon(
                        imageVector = Icons.Default.DragHandle,
                        contentDescription = null,
                        tint = TextSecondary,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = node.displayName,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                IconButton(
                    onClick = { onUpdate(node.copy(enabled = !node.enabled)) },
                    modifier = Modifier.size(28.dp)
                ) {
                    Icon(
                        imageVector = if (node.enabled) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                        contentDescription = "Toggle effect visibility",
                        tint = if (node.enabled) AmTeal else TextSecondary,
                        modifier = Modifier.size(16.dp)
                    )
                }
                IconButton(onClick = onRemove, modifier = Modifier.size(28.dp)) {
                    Icon(
                        imageVector = Icons.Default.DeleteOutline,
                        contentDescription = "Delete effect",
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(16.dp)
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
