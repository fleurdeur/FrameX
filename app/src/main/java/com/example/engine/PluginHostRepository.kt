package com.example.engine

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.example.data.model.AnimatableProperty
import com.example.data.model.BeatMarker
import com.example.data.model.EffectNode
import com.example.data.model.Keyframe
import com.example.data.model.Layer
import com.example.data.model.LayerMarker
import com.example.data.model.LayerType
import com.example.data.model.Project
import com.example.scriptengine.AeDomBridge
import com.example.scriptengine.CompatibilityCategory
import com.example.scriptengine.CompatibilityIssue
import com.example.scriptengine.CompatibilityReport
import com.example.scriptengine.ExtendScriptEngine
import com.example.scriptengine.HostCompDto
import com.example.scriptengine.HostKeyframeDto
import com.example.scriptengine.HostLayerDto
import com.example.scriptengine.HostPropertyDto
import com.example.scriptengine.InstalledPlugin
import com.example.scriptengine.PluginArchiveImporter
import com.example.scriptengine.PluginGeometry
import com.example.scriptengine.PluginInstallStatus
import com.example.scriptengine.PluginSignatureStatus
import com.example.scriptengine.PluginType
import com.example.scriptengine.PluginUiState
import com.example.scriptengine.ScriptUiWidgetSnapshot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.roundToLong

enum class TimelinePluginUiStatus(val label: String) {
    SHOWN("Shown"),
    HIDDEN_BY_YOU("Hidden by you"),
    DISABLED("Disabled"),
    NO_PANEL_UI("No panel UI"),
    UNSUPPORTED("Unsupported")
}

fun InstalledPlugin.hasPanelUi(): Boolean =
    type == PluginType.CEP_PANEL || type == PluginType.SCRIPT_UI

fun InstalledPlugin.isFileNetworkAllowed(): Boolean =
    rawAttributes["__fileNetworkAllowed"]?.toBooleanStrictOrNull() ?: false

fun InstalledPlugin.timelineStatus(): TimelinePluginUiStatus = when {
    report.status == PluginInstallStatus.UNSUPPORTED -> TimelinePluginUiStatus.UNSUPPORTED
    !uiState.enabled -> TimelinePluginUiStatus.DISABLED
    !hasPanelUi() -> TimelinePluginUiStatus.NO_PANEL_UI
    !uiState.showInTimeline -> TimelinePluginUiStatus.HIDDEN_BY_YOU
    else -> TimelinePluginUiStatus.SHOWN
}

fun InstalledPlugin.isVisibleInEditorMenu(): Boolean =
    timelineStatus() == TimelinePluginUiStatus.SHOWN && uiState.pinToAddMenu

/**
 * Host repository and AE DOM bridge connecting `:script-engine` (CEP HTML5 panels + ScriptUI `.jsx`/`.jsxbin`)
 * to FramePollution's live composition, keyframe curve evaluator, text transform animators, layer markers,
 * and single-step undo stack.
 */
class PluginHostRepository(
    context: Context,
    private val hostBridgeCallbacks: HostEditorCallbacks
) {
    interface HostEditorCallbacks {
        fun getCurrentProject(): Project
        fun getSelectedLayerIds(): Set<String>
        fun getSelectedPropertyKey(): String?
        fun getPlayheadMs(): Long
        fun pushUndoSnapshot()
        fun applyUpdatedProject(updated: Project)
    }

    private val appContext = context.applicationContext
    private val appFilesDir: File = appContext.filesDir
    private val pluginsRootDir: File = File(appFilesDir, "plugins").apply { mkdirs() }
    private val registryFile: File = File(pluginsRootDir, "registry.json")

    private val _installedPlugins = MutableStateFlow<List<InstalledPlugin>>(emptyList())
    val installedPlugins: StateFlow<List<InstalledPlugin>> = _installedPlugins.asStateFlow()

    @Volatile
    private var undoDepth = 0

    @Volatile
    private var stagedUndoProject: Project? = null

    private val domBridge = object : AeDomBridge {
        override fun getActiveComp(): HostCompDto? {
            val proj = stagedUndoProject ?: hostBridgeCallbacks.getCurrentProject()
            val selectedIds = hostBridgeCallbacks.getSelectedLayerIds()
            val effectiveSelectedIds = if (selectedIds.isNotEmpty()) {
                selectedIds
            } else {
                proj.layers.firstOrNull()?.id?.let { setOf(it) }.orEmpty()
            }
            val fps = proj.fps.coerceIn(1, 120).toDouble()
            val durSec = (proj.effectiveEditDurationMs().coerceAtLeast(100L) / 1000.0)
            val playheadSec = (hostBridgeCallbacks.getPlayheadMs().coerceAtLeast(0L) / 1000.0)

            val hostLayers = proj.layers.mapIndexed { idx, lyr ->
                val rgb = parseHexToNormalizedRgb(lyr.colorHex)
                HostLayerDto(
                    id = lyr.id,
                    index1Based = idx + 1,
                    name = lyr.name,
                    kind = when (lyr.type) {
                        LayerType.TEXT -> "TEXT"
                        LayerType.SHAPE, LayerType.DRAWING -> "SHAPE"
                        LayerType.NULL_OBJECT -> "NULL"
                        LayerType.CAMERA -> "CAMERA"
                        LayerType.GROUP -> "GROUP"
                        else -> "AV"
                    },
                    inPointSec = lyr.startTimeMs / 1000.0,
                    outPointSec = lyr.endTimeMs / 1000.0,
                    startTimeSec = lyr.startTimeMs / 1000.0,
                    selected = lyr.id in effectiveSelectedIds,
                    textValue = lyr.textContent,
                    fontFamily = lyr.fontFamilyName,
                    fontSize = 48.0,
                    fillColorRgb = rgb,
                    tracking = 0.0
                )
            }

            val selectedProps = ArrayList<HostPropertyDto>()
            val preferredPropKey = hostBridgeCallbacks.getSelectedPropertyKey()
            proj.layers.filter { it.id in effectiveSelectedIds }.forEach { lyr ->
                val candidateProps = buildLayerHostProperties(lyr)
                val withKeyframes = candidateProps.filter { it.keyframes.size >= 2 }
                when {
                    preferredPropKey != null && candidateProps.any { it.propertyKey.equals(preferredPropKey, ignoreCase = true) } -> {
                        selectedProps.addAll(candidateProps.filter { it.propertyKey.equals(preferredPropKey, ignoreCase = true) })
                    }
                    withKeyframes.isNotEmpty() -> selectedProps.addAll(withKeyframes)
                    candidateProps.isNotEmpty() -> selectedProps.add(candidateProps.first())
                }
            }

            return HostCompDto(
                id = proj.id,
                name = proj.name,
                width = proj.width,
                height = proj.height,
                durationSec = durSec,
                frameRate = fps,
                playheadSec = playheadSec,
                layers = hostLayers,
                selectedProperties = selectedProps
            )
        }

        override fun beginUndoGroup(label: String) {
            synchronized(this@PluginHostRepository) {
                if (undoDepth == 0) {
                    hostBridgeCallbacks.pushUndoSnapshot()
                    stagedUndoProject = hostBridgeCallbacks.getCurrentProject()
                }
                undoDepth++
            }
        }

        override fun endUndoGroup() {
            synchronized(this@PluginHostRepository) {
                if (undoDepth > 0) {
                    undoDepth--
                    if (undoDepth == 0) {
                        stagedUndoProject?.let { committed ->
                            hostBridgeCallbacks.applyUpdatedProject(committed)
                        }
                        stagedUndoProject = null
                    }
                }
            }
        }

        override fun updatePropertyKeyframes(
            layerId: String,
            propertyKey: String,
            updatedKeyframes: List<HostKeyframeDto>,
            updatedBaseValue: DoubleArray?,
            updatedExpression: String?
        ) {
            mutateProjectAtomically { proj ->
                val updatedLayers = proj.layers.map { lyr ->
                    if (lyr.id != layerId) lyr
                    else applyHostPropertyUpdateToLayer(
                        layer = lyr,
                        propertyKey = propertyKey,
                        updatedKeyframes = updatedKeyframes,
                        updatedBaseValue = updatedBaseValue,
                        updatedExpression = updatedExpression
                    )
                }
                proj.copy(layers = updatedLayers)
            }
        }

        override fun updateTextDocument(
            layerId: String,
            text: String,
            fontFamily: String,
            fontSize: Double,
            fillColorRgb: DoubleArray,
            tracking: Double
        ) {
            mutateProjectAtomically { proj ->
                val hex = normalizedRgbToHex(fillColorRgb)
                val updatedLayers = proj.layers.map { lyr ->
                    if (lyr.id != layerId) lyr
                    else lyr.copy(
                        textContent = text,
                        fontFamilyName = fontFamily.ifBlank { lyr.fontFamilyName },
                        colorHex = hex
                    )
                }
                proj.copy(layers = updatedLayers)
            }
        }

        override fun applyTextAnimator(
            layerId: String,
            animatorName: String,
            selectorValues: Map<String, Double>,
            propertyValues: Map<String, DoubleArray>,
            propertyKeyframes: Map<String, List<HostKeyframeDto>>
        ) {
            mutateProjectAtomically { proj ->
                val updatedLayers = proj.layers.map { lyr ->
                    if (lyr.id != layerId) lyr
                    else applyAeTextAnimatorToLayer(
                        layer = lyr,
                        animatorName = animatorName,
                        selectorValues = selectorValues,
                        propertyValues = propertyValues,
                        propertyKeyframes = propertyKeyframes
                    )
                }
                proj.copy(layers = updatedLayers)
            }
        }

        override fun addLayerOrCompMarker(
            layerId: String?,
            timeSec: Double,
            comment: String,
            durationSec: Double
        ) {
            mutateProjectAtomically { proj ->
                val timeMs = (timeSec * 1000.0).roundToLong().coerceAtLeast(0L)
                val durMs = (durationSec * 1000.0).roundToLong().coerceAtLeast(0L)
                val mergedBookmarks = (proj.beatMarkers + BeatMarker(timeMs = timeMs))
                    .distinctBy { it.timeMs }
                    .sortedBy { it.timeMs }
                if (layerId == null) {
                    proj.copy(beatMarkers = mergedBookmarks)
                } else {
                    val updatedLayers = proj.layers.map { lyr ->
                        if (lyr.id != layerId) lyr
                        else {
                            val existingArr = runCatching {
                                JSONArray(lyr.rawAttributes["__markersJson"] ?: "[]")
                            }.getOrElse { JSONArray() }
                            existingArr.put(
                                JSONObject().apply {
                                    put("timeMs", timeMs)
                                    put("comment", comment)
                                    put("durationMs", durMs)
                                }
                            )
                            lyr.copy(
                                layerMarkers = lyr.layerMarkers + LayerMarker(
                                    timeMs = timeMs,
                                    comment = comment,
                                    durationMs = durMs
                                ),
                                rawAttributes = lyr.rawAttributes + ("__markersJson" to existingArr.toString())
                            )
                        }
                    }
                    proj.copy(
                        layers = updatedLayers,
                        beatMarkers = mergedBookmarks
                    )
                }
            }
        }
    }

    val scriptEngine = ExtendScriptEngine(
        appFilesDir = appFilesDir,
        domBridgeProvider = { domBridge },
        mappingJsonProvider = {
            runCatching {
                appContext.assets.open("ae_text_animator_mapping.json").bufferedReader().use { it.readText() }
            }.getOrDefault("{}")
        }
    )

    init {
        _installedPlugins.value = loadRegistryFromDisk()
    }

    private fun mutateProjectAtomically(transform: (Project) -> Project) {
        synchronized(this) {
            if (undoDepth > 0) {
                val base = stagedUndoProject ?: hostBridgeCallbacks.getCurrentProject()
                stagedUndoProject = transform(base)
            } else {
                hostBridgeCallbacks.pushUndoSnapshot()
                val updated = transform(hostBridgeCallbacks.getCurrentProject())
                hostBridgeCallbacks.applyUpdatedProject(updated)
            }
        }
    }

    suspend fun importPluginFromUri(uri: Uri, password: String? = null): Result<InstalledPlugin> = withContext(Dispatchers.IO) {
        runCatching {
            val fileName = resolveDisplayName(uri) ?: (uri.lastPathSegment?.substringAfterLast('/') ?: "plugin.zip")
            val stream = appContext.contentResolver.openInputStream(uri)
                ?: throw IllegalArgumentException("Unable to read selected plugin file.")
            val imported = stream.use { input ->
                PluginArchiveImporter.importPluginStream(
                    inputStream = input,
                    originalFileName = fileName,
                    appFilesDir = appFilesDir,
                    password = password
                ).getOrThrow()
            }
            finalizeImportedPlugin(imported)
        }
    }

    suspend fun importPluginFromFile(file: File, password: String? = null): Result<InstalledPlugin> = withContext(Dispatchers.IO) {
        runCatching {
            val imported = PluginArchiveImporter.importPluginFile(
                sourceFile = file,
                appFilesDir = appFilesDir,
                password = password
            ).getOrThrow()
            finalizeImportedPlugin(imported)
        }
    }

    private suspend fun finalizeImportedPlugin(imported: InstalledPlugin): InstalledPlugin {
        val probedReport = scriptEngine.runDryRunProbe(imported)
        val existing = _installedPlugins.value.firstOrNull { it.bundleId == imported.bundleId }
        val supported = probedReport.status != PluginInstallStatus.UNSUPPORTED
        val defaultUi = existing?.uiState ?: PluginUiState(
            enabled = supported,
            showInTimeline = imported.hasPanelUi() && supported,
            pinToAddMenu = true,
            order = _installedPlugins.value.size
        )
        val mergedAttrs = LinkedHashMap(imported.rawAttributes).apply {
            put("__fileNetworkAllowed", (existing?.isFileNetworkAllowed() ?: false).toString())
        }
        val finalized = imported.copy(
            report = probedReport,
            uiState = defaultUi.copy(
                enabled = if (!supported) false else defaultUi.enabled,
                showInTimeline = if (!supported || !imported.hasPanelUi()) false else defaultUi.showInTimeline
            ),
            rawAttributes = mergedAttrs
        )
        scriptEngine.releasePluginSession(finalized.bundleId)
        val nextList = (_installedPlugins.value.filterNot { it.bundleId == finalized.bundleId } + finalized)
            .sortedBy { it.uiState.order }
        _installedPlugins.value = nextList
        saveRegistryToDisk(nextList)
        return finalized
    }

    fun setPluginEnabled(bundleId: String, enabled: Boolean) {
        updatePluginEntry(bundleId) { plugin ->
            val allowed = enabled && plugin.report.status != PluginInstallStatus.UNSUPPORTED
            if (!allowed) {
                scriptEngine.releasePluginSession(bundleId)
            }
            plugin.copy(uiState = plugin.uiState.copy(enabled = allowed))
        }
    }

    fun setShowInTimeline(bundleId: String, show: Boolean) {
        updatePluginEntry(bundleId) { plugin ->
            plugin.copy(uiState = plugin.uiState.copy(showInTimeline = show && plugin.hasPanelUi()))
        }
    }

    fun setPinToAddMenu(bundleId: String, pinned: Boolean) {
        updatePluginEntry(bundleId) { plugin ->
            plugin.copy(uiState = plugin.uiState.copy(pinToAddMenu = pinned))
        }
    }

    fun setFileNetworkAllowed(bundleId: String, allowed: Boolean) {
        updatePluginEntry(bundleId) { plugin ->
            plugin.copy(
                rawAttributes = plugin.rawAttributes + ("__fileNetworkAllowed" to allowed.toString())
            )
        }
    }

    fun setAllowFileNetwork(bundleId: String, allow: Boolean) = setFileNetworkAllowed(bundleId, allow)

    suspend fun runProbe(bundleId: String): Result<CompatibilityReport> = withContext(Dispatchers.Default) {
        runCatching {
            val target = _installedPlugins.value.firstOrNull { it.bundleId == bundleId }
                ?: throw IllegalArgumentException("Plugin not found: $bundleId")
            scriptEngine.releasePluginSession(bundleId)
            val refreshed = scriptEngine.runDryRunProbe(target)
            updatePluginEntry(bundleId) { it.copy(report = refreshed) }
            refreshed
        }
    }

    suspend fun runPluginProbe(bundleId: String): CompatibilityReport? = runProbe(bundleId).getOrNull()

    suspend fun openHeadlessScriptPlugin(plugin: InstalledPlugin): Result<String> {
        return ensureHostLoaded(plugin.bundleId).map { "Executed ${plugin.displayName}" }
    }

    suspend fun clearPluginData(bundleId: String) = withContext(Dispatchers.IO) {
        val target = _installedPlugins.value.firstOrNull { it.bundleId == bundleId } ?: return@withContext
        scriptEngine.releasePluginSession(bundleId)
        val dataDir = File(
            target.dataDirPath.ifBlank {
                File(appFilesDir, "plugin-data/${PluginArchiveImporter.sanitizeSegment(bundleId)}").path
            }
        )
        if (dataDir.exists()) {
            dataDir.deleteRecursively()
        }
        dataDir.mkdirs()
    }

    suspend fun uninstallPlugin(bundleId: String) = withContext(Dispatchers.IO) {
        val target = _installedPlugins.value.firstOrNull { it.bundleId == bundleId }
        scriptEngine.releasePluginSession(bundleId)
        if (target != null) {
            val bundleRoot = File(pluginsRootDir, PluginArchiveImporter.sanitizeSegment(bundleId))
            if (bundleRoot.exists()) bundleRoot.deleteRecursively()
            val dataDir = File(
                target.dataDirPath.ifBlank {
                    File(appFilesDir, "plugin-data/${PluginArchiveImporter.sanitizeSegment(bundleId)}").path
                }
            )
            if (dataDir.exists()) dataDir.deleteRecursively()
        }
        val next = _installedPlugins.value.filterNot { it.bundleId == bundleId }
        _installedPlugins.value = next
        saveRegistryToDisk(next)
    }

    suspend fun ensureHostLoaded(bundleId: String): Result<ScriptUiWidgetSnapshot?> {
        val plugin = _installedPlugins.value.firstOrNull { it.bundleId == bundleId }
            ?: return Result.failure(IllegalArgumentException("Plugin '$bundleId' not installed"))
        return scriptEngine.ensurePluginHostLoaded(plugin)
    }

    suspend fun evalPluginScript(bundleId: String, script: String): Result<String> {
        val plugin = _installedPlugins.value.firstOrNull { it.bundleId == bundleId }
            ?: return Result.failure(IllegalArgumentException("Plugin '$bundleId' not installed"))
        return scriptEngine.evalScript(plugin, script)
    }

    suspend fun dispatchScriptUiEvent(
        bundleId: String,
        widgetId: String,
        newText: String? = null,
        newValue: Float? = null,
        newChecked: Boolean? = null,
        newSelectedIndex: Int? = null,
        triggerClick: Boolean = false
    ): Result<ScriptUiWidgetSnapshot> {
        val plugin = _installedPlugins.value.firstOrNull { it.bundleId == bundleId }
            ?: return Result.failure(IllegalArgumentException("Plugin '$bundleId' not installed"))
        return scriptEngine.dispatchScriptUiInteraction(
            plugin = plugin,
            widgetId = widgetId,
            newText = newText,
            newValue = newValue,
            newChecked = newChecked,
            newSelectedIndex = newSelectedIndex,
            triggerClick = triggerClick
        )
    }

    private fun updatePluginEntry(bundleId: String, transform: (InstalledPlugin) -> InstalledPlugin) {
        val next = _installedPlugins.value.map { if (it.bundleId == bundleId) transform(it) else it }
        _installedPlugins.value = next
        saveRegistryToDisk(next)
    }

    private fun resolveDisplayName(uri: Uri): String? {
        return runCatching {
            appContext.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        }.getOrNull()
    }

    private fun buildLayerHostProperties(layer: Layer): List<HostPropertyDto> {
        val props = ArrayList<HostPropertyDto>(8)
        fun mapSingleProp(key: String, matchName: String, displayName: String, prop: AnimatableProperty): HostPropertyDto {
            val sorted = prop.keyframes.sortedBy { it.timeMs }
            val hostKfs = sorted.mapIndexed { idx, kf ->
                HostKeyframeDto(
                    index1Based = idx + 1,
                    timeSec = kf.timeMs / 1000.0,
                    value = doubleArrayOf(kf.value.toDouble()),
                    outX = kf.outTangentX.toDouble(),
                    outY = kf.outTangentY.toDouble(),
                    inX = kf.inTangentX.toDouble(),
                    inY = kf.inTangentY.toDouble(),
                    holdStep = kf.holdStep,
                    selected = true
                )
            }
            return HostPropertyDto(
                layerId = layer.id,
                propertyKey = key,
                matchName = matchName,
                name = displayName,
                isSpatial = key == "posX" || key == "posY" || key == "posZ",
                dimensions = 1,
                baseValue = doubleArrayOf(prop.baseValue.toDouble()),
                expression = prop.expression,
                keyframes = hostKfs,
                selectedKeyIndices1Based = hostKfs.map { it.index1Based }
            )
        }
        props.add(mapSingleProp("posX", "ADBE Position_0", "Position X", layer.posX))
        props.add(mapSingleProp("posY", "ADBE Position_1", "Position Y", layer.posY))
        props.add(mapSingleProp("scale", "ADBE Scale", "Scale", layer.scale))
        props.add(mapSingleProp("rotationZ", "ADBE Rotate Z", "Rotation", layer.rotationZ))
        props.add(mapSingleProp("opacity", "ADBE Opacity", "Opacity", layer.opacity))
        if (layer.scaleY.keyframes.isNotEmpty()) {
            props.add(mapSingleProp("scaleY", "ADBE Scale_1", "Scale Y", layer.scaleY))
        }
        if (layer.posZ.keyframes.isNotEmpty()) {
            props.add(mapSingleProp("posZ", "ADBE Position_2", "Position Z", layer.posZ))
        }
        return props
    }

    private fun applyHostPropertyUpdateToLayer(
        layer: Layer,
        propertyKey: String,
        updatedKeyframes: List<HostKeyframeDto>,
        updatedBaseValue: DoubleArray?,
        updatedExpression: String?
    ): Layer {
        fun convertProp(existing: AnimatableProperty): AnimatableProperty {
            val sortedDto = updatedKeyframes.sortedBy { it.timeSec }
            val convertedKfs = sortedDto.mapIndexed { idx, dto ->
                val outX = dto.outX.toFloat().coerceIn(0f, 1f)
                val outY = dto.outY.toFloat().coerceIn(-6f, 6f)
                val inX = dto.inX.toFloat().coerceIn(0f, 1f)
                val inY = dto.inY.toFloat().coerceIn(-6f, 6f)
                // Segment [idx-1 -> idx] uses departure outTangent on [idx-1] and arrival inTangent on [idx]
                val prevDto = sortedDto.getOrNull(idx - 1)
                val segX1 = prevDto?.outX?.toFloat()?.coerceIn(0f, 1f) ?: outX
                val segY1 = prevDto?.outY?.toFloat()?.coerceIn(-6f, 6f) ?: outY
                val rawEasing = when {
                    dto.holdStep -> "step 1.0 0.0"
                    prevDto != null -> String.format(
                        Locale.US,
                        "cubicBezier %.4f %.4f %.4f %.4f",
                        segX1,
                        segY1,
                        inX,
                        inY
                    )
                    else -> ""
                }
                Keyframe(
                    timeMs = (dto.timeSec * 1000.0).roundToLong(),
                    value = dto.value.firstOrNull()?.toFloat() ?: existing.baseValue,
                    outTangentX = outX,
                    outTangentY = outY,
                    inTangentX = inX,
                    inTangentY = inY,
                    holdStep = dto.holdStep,
                    rawEasing = rawEasing
                )
            }
            return existing.copy(
                baseValue = updatedBaseValue?.firstOrNull()?.toFloat() ?: existing.baseValue,
                keyframes = convertedKfs,
                expression = updatedExpression ?: existing.expression
            )
        }

        return when (propertyKey) {
            "posX", "ADBE Position", "ADBE Position_0" -> layer.copy(posX = convertProp(layer.posX))
            "posY", "ADBE Position_1" -> layer.copy(posY = convertProp(layer.posY))
            "posZ", "ADBE Position_2" -> layer.copy(posZ = convertProp(layer.posZ))
            "scale", "ADBE Scale" -> layer.copy(scale = convertProp(layer.scale))
            "scaleY", "ADBE Scale_1" -> layer.copy(scaleY = convertProp(layer.scaleY))
            "rotationZ", "ADBE Rotate Z" -> layer.copy(rotationZ = convertProp(layer.rotationZ))
            "opacity", "ADBE Opacity" -> layer.copy(opacity = convertProp(layer.opacity))
            else -> layer
        }
    }

    private fun applyAeTextAnimatorToLayer(
        layer: Layer,
        animatorName: String,
        selectorValues: Map<String, Double>,
        propertyValues: Map<String, DoubleArray>,
        propertyKeyframes: Map<String, List<HostKeyframeDto>>
    ): Layer {
        fun dtoListToProp(dtos: List<HostKeyframeDto>?, fallback: Float, scaleMult: Float = 1f): AnimatableProperty {
            if (dtos.isNullOrEmpty()) return AnimatableProperty(baseValue = fallback)
            val sorted = dtos.sortedBy { it.timeSec }
            return AnimatableProperty(
                baseValue = (sorted.first().value.firstOrNull()?.toFloat() ?: fallback) * scaleMult,
                keyframes = sorted.mapIndexed { idx, dto ->
                    val prev = sorted.getOrNull(idx - 1)
                    val outX = dto.outX.toFloat().coerceIn(0f, 1f)
                    val outY = dto.outY.toFloat().coerceIn(-6f, 6f)
                    val inX = dto.inX.toFloat().coerceIn(0f, 1f)
                    val inY = dto.inY.toFloat().coerceIn(-6f, 6f)
                    val rawE = if (prev != null) {
                        String.format(
                            Locale.US,
                            "cubicBezier %.4f %.4f %.4f %.4f",
                            prev.outX.toFloat().coerceIn(0f, 1f),
                            prev.outY.toFloat().coerceIn(-6f, 6f),
                            inX,
                            inY
                        )
                    } else ""
                    Keyframe(
                        timeMs = (dto.timeSec * 1000.0).roundToLong(),
                        value = (dto.value.firstOrNull()?.toFloat() ?: fallback) * scaleMult,
                        outTangentX = outX,
                        outTangentY = outY,
                        inTangentX = inX,
                        inTangentY = inY,
                        rawEasing = rawE
                    )
                }
            )
        }

        val startBase = (selectorValues["ADBE Text Percent Start"] ?: selectorValues["start"] ?: 0.0).toFloat()
        val endBase = (selectorValues["ADBE Text Percent End"] ?: selectorValues["end"] ?: 100.0).toFloat()
        val offsetBase = (selectorValues["ADBE Text Percent Offset"] ?: selectorValues["phase"] ?: 0.0).toFloat()
        val basedOn = (selectorValues["ADBE Text Range Units"] ?: selectorValues["basedOn"] ?: 0.0).roundToInt().coerceIn(0, 2)

        val posArr = propertyValues["ADBE Text Position 3D"] ?: propertyValues["position"]
        val posYOffset = posArr?.getOrNull(1)?.toFloat() ?: -36f
        val rotVal = propertyValues["ADBE Text Rotation"]?.firstOrNull()?.toFloat() ?: 0f
        val opacityPct = propertyValues["ADBE Text Opacity"]?.firstOrNull()?.toFloat() ?: 0f
        val trackingVal = propertyValues["ADBE Text Tracking Amount"]?.firstOrNull()?.toFloat() ?: 0f

        val startProp = dtoListToProp(
            propertyKeyframes["ADBE Text Percent Start"] ?: propertyKeyframes["start"],
            startBase
        )
        val endProp = dtoListToProp(
            propertyKeyframes["ADBE Text Percent End"] ?: propertyKeyframes["end"],
            endBase
        )
        val phaseProp = dtoListToProp(
            propertyKeyframes["ADBE Text Percent Offset"] ?: propertyKeyframes["phase"],
            offsetBase
        )

        val customMap = LinkedHashMap<String, AnimatableProperty>().apply {
            put("start", startProp)
            put("end", endProp)
            put("phase", phaseProp)
            put("kf_unit_mode", AnimatableProperty(basedOn.toFloat()))
            put("tx", AnimatableProperty(posArr?.getOrNull(0)?.toFloat() ?: 0f))
            put("ty", AnimatableProperty(posYOffset))
            put("angle", AnimatableProperty(rotVal))
            put("alpha", AnimatableProperty((opacityPct / 100f) - 1f))
            put("tracking", AnimatableProperty(trackingVal))
        }

        val animInstanceKey = selectorValues["__animatorInstanceId"]?.toLong()?.toString()
        val rawMap = LinkedHashMap<String, String>().apply {
            put("id", "com.alightcreative.effects.texttransform")
            put("__amEffectId", "com.alightcreative.effects.texttransform")
            if (animInstanceKey != null) {
                put("__aeAnimatorId", animInstanceKey)
            }
        }
        val existingIdx = if (animInstanceKey != null) {
            layer.effects.indexOfFirst { it.rawAttributes["__aeAnimatorId"] == animInstanceKey }
        } else -1
        val preservedId = if (existingIdx >= 0) layer.effects[existingIdx].id else java.util.UUID.randomUUID().toString()

        val newFx = EffectNode(
            id = preservedId,
            effectId = "Text_Transform",
            displayName = animatorName.ifBlank { "Text Transform" },
            enabled = true,
            primaryProp = startProp,
            secondaryProp = endProp,
            frequencyProp = phaseProp,
            angleProp = AnimatableProperty(if (abs(rotVal) > 0.01f) rotVal else posYOffset),
            mixProp = AnimatableProperty(100f),
            customParams = customMap,
            rawAttributes = rawMap
        )
        val nextEffects = if (existingIdx >= 0) {
            layer.effects.toMutableList().apply { set(existingIdx, newFx) }
        } else {
            layer.effects + newFx
        }
        return layer.copy(effects = nextEffects)
    }

    private fun parseHexToNormalizedRgb(hex: String): DoubleArray {
        val clean = hex.trim().removePrefix("#")
        return runCatching {
            val rgbHex = if (clean.length == 8) clean.substring(2) else clean.padStart(6, 'F')
            val r = rgbHex.substring(0, 2).toInt(16) / 255.0
            val g = rgbHex.substring(2, 4).toInt(16) / 255.0
            val b = rgbHex.substring(4, 6).toInt(16) / 255.0
            doubleArrayOf(r, g, b)
        }.getOrElse { doubleArrayOf(1.0, 1.0, 1.0) }
    }

    private fun normalizedRgbToHex(rgb: DoubleArray): String {
        val r = ((rgb.getOrNull(0) ?: 1.0) * 255.0).roundToInt().coerceIn(0, 255)
        val g = ((rgb.getOrNull(1) ?: 1.0) * 255.0).roundToInt().coerceIn(0, 255)
        val b = ((rgb.getOrNull(2) ?: 1.0) * 255.0).roundToInt().coerceIn(0, 255)
        return String.format(Locale.US, "#%02X%02X%02X", r, g, b)
    }

    private fun saveRegistryToDisk(list: List<InstalledPlugin>) {
        runCatching {
            val arr = JSONArray()
            list.forEach { p ->
                val obj = JSONObject().apply {
                    put("bundleId", p.bundleId)
                    put("name", p.name)
                    put("version", p.version)
                    put("type", p.type.name)
                    put("author", p.author)
                    put("menuName", p.menuName)
                    put("entryHtmlRelativePath", p.entryHtmlRelativePath)
                    put("hostScriptRelativePath", p.hostScriptRelativePath)
                    put("readmeText", p.readmeText)
                    put("signatureStatus", p.signatureStatus.name)
                    put("installDirPath", p.installDirPath)
                    put("dataDirPath", p.dataDirPath)
                    put("csxsVersion", p.csxsVersion)
                    put("hostVersionRange", p.hostVersionRange)
                    put("geometry", JSONObject().apply {
                        put("defaultWidth", p.geometry.defaultWidth)
                        put("defaultHeight", p.geometry.defaultHeight)
                        put("minWidth", p.geometry.minWidth)
                        put("minHeight", p.geometry.minHeight)
                        put("maxWidth", p.geometry.maxWidth)
                        put("maxHeight", p.geometry.maxHeight)
                    })
                    put("uiState", JSONObject().apply {
                        put("enabled", p.uiState.enabled)
                        put("showInTimeline", p.uiState.showInTimeline)
                        put("pinToAddMenu", p.uiState.pinToAddMenu)
                        put("order", p.uiState.order)
                    })
                    put("rawAttributes", JSONObject().apply {
                        p.rawAttributes.forEach { (k, v) -> put(k, v) }
                    })
                    put("probeExecuted", p.report.probeExecuted)
                    val issuesArr = JSONArray()
                    p.report.issues.forEach { iss ->
                        issuesArr.put(JSONObject().apply {
                            put("category", iss.category.name)
                            put("item", iss.item)
                            put("detail", iss.detail)
                            put("fatal", iss.fatal)
                        })
                    }
                    put("issues", issuesArr)
                }
                arr.put(obj)
            }
            registryFile.parentFile?.mkdirs()
            registryFile.writeText(arr.toString(2), Charsets.UTF_8)
        }
    }

    private fun loadRegistryFromDisk(): List<InstalledPlugin> {
        if (!registryFile.exists()) return emptyList()
        return runCatching {
            val arr = JSONArray(registryFile.readText(Charsets.UTF_8))
            val out = ArrayList<InstalledPlugin>(arr.length())
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val installPath = obj.optString("installDirPath")
                if (installPath.isBlank() || !File(installPath).exists()) continue
                val bundleId = obj.optString("bundleId")
                val name = obj.optString("name", bundleId)
                val version = obj.optString("version", "1.0.0")
                val geomObj = obj.optJSONObject("geometry")
                val uiObj = obj.optJSONObject("uiState")
                val rawAttrsObj = obj.optJSONObject("rawAttributes")
                val rawAttrs = LinkedHashMap<String, String>()
                if (rawAttrsObj != null) {
                    val keys = rawAttrsObj.keys()
                    while (keys.hasNext()) {
                        val k = keys.next()
                        rawAttrs[k] = rawAttrsObj.optString(k)
                    }
                }
                val issues = ArrayList<CompatibilityIssue>()
                val issuesArr = obj.optJSONArray("issues")
                if (issuesArr != null) {
                    for (j in 0 until issuesArr.length()) {
                        val io = issuesArr.optJSONObject(j) ?: continue
                        val cat = runCatching {
                            CompatibilityCategory.valueOf(io.optString("category", "DOM"))
                        }.getOrDefault(CompatibilityCategory.DOM)
                        issues.add(
                            CompatibilityIssue(
                                category = cat,
                                item = io.optString("item"),
                                detail = io.optString("detail"),
                                fatal = io.optBoolean("fatal", false)
                            )
                        )
                    }
                }
                out.add(
                    InstalledPlugin(
                        bundleId = bundleId,
                        name = name,
                        version = version,
                        type = runCatching { PluginType.valueOf(obj.optString("type")) }.getOrDefault(PluginType.STANDALONE_JSX),
                        author = obj.optString("author"),
                        menuName = obj.optString("menuName", name),
                        entryHtmlRelativePath = obj.optString("entryHtmlRelativePath"),
                        hostScriptRelativePath = obj.optString("hostScriptRelativePath"),
                        readmeText = obj.optString("readmeText"),
                        signatureStatus = runCatching {
                            PluginSignatureStatus.valueOf(obj.optString("signatureStatus"))
                        }.getOrDefault(PluginSignatureStatus.UNSIGNED),
                        installDirPath = installPath,
                        dataDirPath = obj.optString("dataDirPath"),
                        csxsVersion = obj.optString("csxsVersion"),
                        hostVersionRange = obj.optString("hostVersionRange"),
                        geometry = PluginGeometry(
                            defaultWidth = geomObj?.optInt("defaultWidth", 280) ?: 280,
                            defaultHeight = geomObj?.optInt("defaultHeight", 460) ?: 460,
                            minWidth = geomObj?.optInt("minWidth", 220) ?: 220,
                            minHeight = geomObj?.optInt("minHeight", 320) ?: 320,
                            maxWidth = geomObj?.optInt("maxWidth", 640) ?: 640,
                            maxHeight = geomObj?.optInt("maxHeight", 820) ?: 820
                        ),
                        rawAttributes = rawAttrs,
                        report = CompatibilityReport(
                            pluginName = name,
                            bundleId = bundleId,
                            version = version,
                            issues = issues,
                            probeExecuted = obj.optBoolean("probeExecuted", false)
                        ),
                        uiState = PluginUiState(
                            enabled = uiObj?.optBoolean("enabled", true) ?: true,
                            showInTimeline = uiObj?.optBoolean("showInTimeline", true) ?: true,
                            pinToAddMenu = uiObj?.optBoolean("pinToAddMenu", true) ?: true,
                            order = uiObj?.optInt("order", i) ?: i
                        )
                    )
                )
            }
            out.sortedBy { it.uiState.order }
        }.getOrDefault(emptyList())
    }
}
