package com.example.engine

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.example.data.model.AnimatableProperty
import com.example.data.model.BeatMarker
import com.example.data.model.EffectNode
import com.example.data.model.Keyframe
import com.example.data.model.Layer
import com.example.data.model.LayerType
import com.example.data.model.Project
import com.example.data.model.ProjectPayloadSerializer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.StringReader
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import java.util.UUID
import java.util.zip.ZipInputStream
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

enum class ImportedFileKind(val label: String) {
    AEP("After Effects (.aep)"),
    KFPROJECT("Keyflow Project (.kfproject)"),
    XML("Project XML (.xml / .fpxml)"),
    KFPACK("Effect Pack (.kfpack)"),
    FFX("AE Preset (.ffx)")
}

data class ImportMediaAssetSlot(
    val id: String = UUID.randomUUID().toString(),
    val storedPath: String,
    val displayName: String,
    val mediaType: LayerType,
    val durationMs: Long,
    val expectedSizeBytes: Long = 0L,
    val status: AssetMatchStatus,
    val resolvedUri: String = "",
    val layerIds: List<String> = emptyList()
)

data class PendingImportSession(
    val id: String = UUID.randomUUID().toString(),
    val sourceUri: Uri?,
    val fileKind: ImportedFileKind,
    val sourceFileName: String,
    val aepParentFolder: File? = null,
    val previewProject: Project,
    val mediaSlots: List<ImportMediaAssetSlot>,
    val effectRecords: List<ImportedEffectRecord>,
    val needsStoragePermissionHint: Boolean = false,
    val isPresetForActiveProject: Boolean = false,
    val targetPresetLayerId: String? = null,
    val summaryNote: String = ""
)

sealed class UniversalImportOutcome {
    data class OpenImportPreview(val session: PendingImportSession) : UniversalImportOutcome()
    data class InstalledPluginPack(val suite: InstalledPluginSuite) : UniversalImportOutcome()
}

/**
 * Multi-Format Importer (`UniversalProjectImporter`):
 *
 * Supports:
 * 1. **`.aep` (After Effects RIFX `Egg!` binary & `.aepx` XML)**:
 *    - Parses RIFX Big-Endian chunk hierarchy (`Fold`/`LIST`, `cdta` comp dimensions/fps/duration,
 *      `ldta` layer timing/parenting/pre-comp linkage, `Als2`/`alas` JSON or UTF-8 Windows `C:\...` footage paths,
 *      `tdmn`/`tdsn` effect matchNames & display names).
 *    - Maps nested compositions (pre-comps) directly to `LayerType.GROUP` layers!
 *    - Resolves media paths on `Dispatchers.IO` via [AepMediaResolver] (own folder -> Downloads -> DCIM -> Movies -> Pictures -> Music).
 *    - Maps effects via [AeEffectPortRegistry] (`Ported` vs `Unsupported`).
 * 2. **`.kfproject` (Keyflow by Angkin ZIP archive or JSON project)**:
 *    - Extracts bundled media assets (`assets/`, `media/`) into app storage, installs embedded `.kfpack` suites,
 *      maps nested comps/groups to `LayerType.GROUP` layers, and resolves any external media references.
 * 3. **`.xml` / `.fpxml` (Alight Motion XML & FramePollution Native XML)**:
 *    - Parses via [ProjectXmlEngine] (mapping `<embedScene>` to `LayerType.GROUP`), resolves external media URIs/paths,
 *      and builds a [PendingImportSession] for the Import Preview screen.
 * 4. **`.kfpack` / `.fppack` (Effect Packs)**:
 *    - Installs via [PluginPackManager] with `minAppVersion` check and per-effect GLSL compilation.
 * 5. **`.ffx` (After Effects RIFX `FXTC` / `Egg!` Effect Presets)**:
 *    - Parses `tdmn` / `tdsn` / parameter chunks from the `.ffx` binary, ports effects via [AeEffectPortRegistry],
 *      and stages a [PendingImportSession] so the user can preview/replace unsupported preset effects before applying!
 */
object UniversalProjectImporter {

    fun detectFileKind(fileName: String?, headerBytes: ByteArray): ImportedFileKind {
        val lower = fileName?.trim()?.lowercase(Locale.US).orEmpty()
        when {
            lower.endsWith(".kfpack") || lower.endsWith(".fppack") -> return ImportedFileKind.KFPACK
            lower.endsWith(".ffx") -> return ImportedFileKind.FFX
            lower.endsWith(".aep") || lower.endsWith(".aepx") -> return ImportedFileKind.AEP
            lower.endsWith(".kfproject") -> return ImportedFileKind.KFPROJECT
            lower.endsWith(".fpxml") || lower.endsWith(".xml") -> return ImportedFileKind.XML
        }
        if (headerBytes.size >= 12) {
            val magic = String(headerBytes, 0, 4, Charsets.ISO_8859_1)
            val formType = String(headerBytes, 8, 4, Charsets.ISO_8859_1)
            if (magic == "RIFX" || magic == "RIFF") {
                return if (formType == "FXTC") ImportedFileKind.FFX else ImportedFileKind.AEP
            }
            if (headerBytes[0] == 'P'.code.toByte() && headerBytes[1] == 'K'.code.toByte()) {
                return ImportedFileKind.KFPROJECT
            }
            val headStr = String(headerBytes, 0, min(headerBytes.size, 256), Charsets.UTF_8).trimStart()
            if (headStr.startsWith("<")) {
                return if (headStr.contains("AfterEffectsProject", ignoreCase = true)) {
                    ImportedFileKind.AEP
                } else {
                    ImportedFileKind.XML
                }
            }
            if (headStr.startsWith("{")) {
                return ImportedFileKind.KFPROJECT
            }
        }
        return ImportedFileKind.XML
    }

    suspend fun importFromUri(
        context: Context,
        uri: Uri,
        activeProjectForPreset: Project? = null,
        selectedLayerIdForPreset: String? = null,
        extraSearchRoots: List<File> = emptyList(),
        onProgress: (Float, String) -> Unit = { _, _ -> }
    ): Result<UniversalImportOutcome> = withContext(Dispatchers.IO) {
        try {
            PluginPackManager.ensureInitialized(context)
            val fileName = resolveFileName(context, uri)
            onProgress(0.08f, "Reading $fileName")

            val rawBytes = context.contentResolver.openInputStream(uri)?.use { stream ->
                val bos = ByteArrayOutputStream()
                val buf = ByteArray(16 * 1024)
                var total = 0
                var r = stream.read(buf)
                while (r > 0) {
                    total += r
                    if (total > 96 * 1024 * 1024) {
                        throw IllegalArgumentException("Import file exceeds 96 MB safety limit")
                    }
                    bos.write(buf, 0, r)
                    r = stream.read(buf)
                }
                bos.toByteArray()
            } ?: return@withContext Result.failure(IllegalArgumentException("Unable to open selected file"))

            val parentFolder = AepMediaResolver.resolveParentFolderIfFileUri(uri)
            importFromBytes(
                context = context,
                rawBytes = rawBytes,
                fileName = fileName,
                sourceUri = uri,
                aepParentFolder = parentFolder,
                activeProjectForPreset = activeProjectForPreset,
                selectedLayerIdForPreset = selectedLayerIdForPreset,
                extraSearchRoots = extraSearchRoots,
                onProgress = onProgress
            )
        } catch (t: Throwable) {
            Result.failure(t)
        }
    }

    suspend fun importFromBytes(
        context: Context?,
        rawBytes: ByteArray,
        fileName: String,
        sourceUri: Uri? = null,
        aepParentFolder: File? = null,
        activeProjectForPreset: Project? = null,
        selectedLayerIdForPreset: String? = null,
        extraSearchRoots: List<File> = emptyList(),
        onProgress: (Float, String) -> Unit = { _, _ -> }
    ): Result<UniversalImportOutcome> = withContext(Dispatchers.IO) {
        try {
            PluginPackManager.ensureInitialized(context)
            if (rawBytes.isEmpty()) {
                return@withContext Result.failure(IllegalArgumentException("Selected file is empty"))
            }
            val detected = detectFileKind(fileName, rawBytes)
            when (detected) {
                ImportedFileKind.KFPACK -> {
                    val ext = if (fileName.endsWith(".fppack", ignoreCase = true)) ".fppack" else ".kfpack"
                    val suite = PluginPackManager.importPackFromBytes(context, rawBytes, ext).getOrThrow()
                    Result.success(UniversalImportOutcome.InstalledPluginPack(suite))
                }

                ImportedFileKind.KFPROJECT -> {
                    // Check if a .zip is actually a .kfpack disguised as .zip (has suite.json at root)
                    if (looksLikeKfpackZip(rawBytes)) {
                        val suite = PluginPackManager.importPackFromBytes(context, rawBytes, ".kfpack").getOrThrow()
                        return@withContext Result.success(UniversalImportOutcome.InstalledPluginPack(suite))
                    }
                    val session = parseKfProjectBytes(
                        context = context,
                        rawBytes = rawBytes,
                        fileName = fileName,
                        sourceUri = sourceUri,
                        parentFolder = aepParentFolder,
                        extraSearchRoots = extraSearchRoots,
                        onProgress = onProgress
                    )
                    Result.success(UniversalImportOutcome.OpenImportPreview(session))
                }

                ImportedFileKind.AEP -> {
                    val session = parseAepBytes(
                        context = context,
                        rawBytes = rawBytes,
                        fileName = fileName,
                        sourceUri = sourceUri,
                        aepParentFolder = aepParentFolder,
                        extraSearchRoots = extraSearchRoots,
                        onProgress = onProgress
                    )
                    Result.success(UniversalImportOutcome.OpenImportPreview(session))
                }

                ImportedFileKind.FFX -> {
                    val session = parseFfxPresetBytes(
                        rawBytes = rawBytes,
                        fileName = fileName,
                        sourceUri = sourceUri,
                        activeProject = activeProjectForPreset,
                        selectedLayerId = selectedLayerIdForPreset
                    )
                    Result.success(UniversalImportOutcome.OpenImportPreview(session))
                }

                ImportedFileKind.XML -> {
                    val session = parseXmlProjectBytes(
                        context = context,
                        rawBytes = rawBytes,
                        fileName = fileName,
                        sourceUri = sourceUri,
                        parentFolder = aepParentFolder,
                        extraSearchRoots = extraSearchRoots,
                        onProgress = onProgress
                    )
                    Result.success(UniversalImportOutcome.OpenImportPreview(session))
                }
            }
        } catch (t: Throwable) {
            Result.failure(t)
        }
    }

    /**
     * Re-runs background asset resolution on an existing [PendingImportSession] (e.g. right after
     * the user grants `MANAGE_EXTERNAL_STORAGE` in Android Settings).
     */
    suspend fun rescanMissingAssets(
        context: Context,
        session: PendingImportSession,
        extraSearchRoots: List<File> = emptyList(),
        onProgress: (Float, String) -> Unit = { _, _ -> }
    ): PendingImportSession = withContext(Dispatchers.IO) {
        if (session.mediaSlots.isEmpty()) return@withContext session
        val requests = session.mediaSlots.map { it.storedPath to it.expectedSizeBytes }
        val resolved = AepMediaResolver.resolveAssetPaths(
            context = context,
            requests = requests,
            aepOwnFolder = session.aepParentFolder,
            extraSearchRoots = extraSearchRoots,
            forceRescan = true,
            onProgress = onProgress
        )

        val uriUpdatesByLayerId = HashMap<String, String>()
        val updatedSlots = session.mediaSlots.mapIndexed { idx, slot ->
            if (slot.status == AssetMatchStatus.MANUAL_RELINKED && slot.resolvedUri.isNotBlank()) {
                slot
            } else {
                val res = resolved.getOrNull(idx)
                if (res != null && res.status == AssetMatchStatus.MATCHED && res.matchedUri.isNotBlank()) {
                    slot.layerIds.forEach { lid -> uriUpdatesByLayerId[lid] = res.matchedUri }
                    slot.copy(status = AssetMatchStatus.MATCHED, resolvedUri = res.matchedUri)
                } else {
                    slot
                }
            }
        }

        val updatedLayers = session.previewProject.layers.map { lyr ->
            val newUri = uriUpdatesByLayerId[lyr.id]
            if (newUri != null) lyr.copy(mediaUri = newUri) else lyr
        }
        val stillMissing = updatedSlots.any { it.status == AssetMatchStatus.MISSING }
        session.copy(
            previewProject = session.previewProject.copy(layers = updatedLayers),
            mediaSlots = updatedSlots,
            needsStoragePermissionHint = stillMissing && !AepMediaResolver.hasAllFilesAccess(context)
        )
    }

    // =============================================================================================
    // 1. AFTER EFFECTS (.aep RIFX Binary & .aepx XML) IMPORTER + NESTED COMP -> GROUP LAYER
    // =============================================================================================

    private data class AepItemNode(
        val id: Int,
        var name: String,
        val kind: Int, // 1 = Folder, 2 = Comp, 4 = Footage/Solid
        var width: Int = 1920,
        var height: Int = 1080,
        var fps: Int = 60,
        var durationMs: Long = 10_000L,
        var footagePath: String = "",
        var expectedBytes: Long = 0L,
        val layers: MutableList<AepLayerNode> = ArrayList()
    )

    private data class AepLayerNode(
        val index: Int,
        var name: String,
        val sourceItemId: Int,
        val parentIndex: Int = 0,
        val startTimeMs: Long = 0L,
        val endTimeMs: Long = 10_000L,
        val inPointMs: Long = 0L,
        var isNullOrAdj: Boolean = false,
        var textContent: String? = null,
        val posX: Float = 0f,
        val posY: Float = 0f,
        val scalePct: Float = 100f,
        val rotZ: Float = 0f,
        val opacityPct: Float = 100f,
        val effects: MutableList<RawDesktopEffectSpec> = ArrayList()
    )

    /**
     * Helper to build a minimal valid RIFX `Egg!` `.aep` binary buffer for unit tests and round-trip diagnostics.
     */
    fun buildSyntheticAepBinary(
        compName: String = "Main Edit",
        width: Int = 1920,
        height: Int = 1080,
        fps: Int = 60,
        durationMs: Long = 8000L,
        footageWindowsPaths: List<String>,
        nestedCompName: String? = "Nested Precomp",
        effectSpecs: List<Pair<String, String>> = emptyList() // (matchName, displayName)
    ): ByteArray {
        fun chunk(fourCc: String, payload: ByteArray): ByteArray {
            val pad = if (payload.size % 2 == 1) 1 else 0
            val out = ByteBuffer.allocate(8 + payload.size + pad).order(ByteOrder.BIG_ENDIAN)
            out.put(fourCc.take(4).padEnd(4, ' ').toByteArray(Charsets.ISO_8859_1))
            out.putInt(payload.size)
            out.put(payload)
            if (pad == 1) out.put(0)
            return out.array()
        }
        fun listChunk(listTag: String, formType: String, children: List<ByteArray>): ByteArray {
            val totalChild = children.sumOf { it.size }
            val payload = ByteBuffer.allocate(4 + totalChild).order(ByteOrder.BIG_ENDIAN)
            payload.put(formType.take(4).padEnd(4, ' ').toByteArray(Charsets.ISO_8859_1))
            children.forEach { payload.put(it) }
            return chunk(listTag, payload.array())
        }
        fun utf8Chunk(text: String): ByteArray = chunk("Utf8", text.toByteArray(Charsets.UTF_8))

        val itemChunks = ArrayList<ByteArray>()

        // Item 1..N: Footage items with Windows C:\ paths
        footageWindowsPaths.forEachIndexed { idx, winPath ->
            val idta = ByteBuffer.allocate(16).order(ByteOrder.BIG_ENDIAN).apply {
                putShort(4) // footage
                putShort(0)
                putInt(idx + 1) // itemId
            }.array()
            val alasJson = JSONObject().put("fullpath", winPath).toString()
            val pinList = listChunk("LIST", "Pin ", listOf(listChunk("LIST", "Als2", listOf(chunk("alas", alasJson.toByteArray(Charsets.UTF_8))))))
            itemChunks.add(
                listChunk(
                    "LIST",
                    "Item",
                    listOf(
                        utf8Chunk(AepMediaResolver.extractCleanFileName(winPath).ifBlank { "Footage ${idx + 1}" }),
                        chunk("idta", idta),
                        pinList
                    )
                )
            )
        }

        val nestedCompId = footageWindowsPaths.size + 1
        if (!nestedCompName.isNullOrBlank() && footageWindowsPaths.isNotEmpty()) {
            val idtaNested = ByteBuffer.allocate(16).order(ByteOrder.BIG_ENDIAN).apply {
                putShort(2) // comp
                putShort(0)
                putInt(nestedCompId)
            }.array()
            val cdtaNested = ByteBuffer.allocate(64).order(ByteOrder.BIG_ENDIAN).apply {
                putShort(1) // res
                putInt(1000) // timeScale = 1000 ticks/sec
                putLong(0L)
                putInt(durationMs.toInt())
                putInt(1000)
                putInt(0)
                putShort(width.toShort())
                putShort(height.toShort())
                putShort(fps.toShort())
            }.array()
            val ldtaChild = ByteBuffer.allocate(40).order(ByteOrder.BIG_ENDIAN).apply {
                putInt(1) // layerIdx
                putShort(1) // quality
                putShort(0)
                putInt(0) // startTimeMs
                putInt(durationMs.toInt()) // durMs
                putInt(0) // inPoint
                putInt(1) // sourceItemId = first footage
                putInt(0) // parentIndex
            }.array()
            val childLayr = listChunk("LIST", "Layr", listOf(utf8Chunk("Precomp Media 1"), chunk("ldta", ldtaNestedOr(ldtaChild))))
            itemChunks.add(
                listChunk("LIST", "Item", listOf(utf8Chunk(nestedCompName), chunk("idta", idtaNested), chunk("cdta", cdtaNested), childLayr))
            )
        }

        // Root Main Comp
        val mainCompId = nestedCompId + 1
        val idtaMain = ByteBuffer.allocate(16).order(ByteOrder.BIG_ENDIAN).apply {
            putShort(2)
            putShort(0)
            putInt(mainCompId)
        }.array()
        val cdtaMain = ByteBuffer.allocate(64).order(ByteOrder.BIG_ENDIAN).apply {
            putShort(1)
            putInt(1000)
            putLong(0L)
            putInt(durationMs.toInt())
            putInt(1000)
            putInt(0)
            putShort(width.toShort())
            putShort(height.toShort())
            putShort(fps.toShort())
        }.array()

        val mainCompChildren = mutableListOf(utf8Chunk(compName), chunk("idta", idtaMain), chunk("cdta", cdtaMain))

        // Add each footage layer + optional nested comp layer
        footageWindowsPaths.forEachIndexed { idx, winPath ->
            val ldta = ByteBuffer.allocate(40).order(ByteOrder.BIG_ENDIAN).apply {
                putInt(idx + 1)
                putShort(1)
                putShort(0)
                putInt(0)
                putInt(durationMs.toInt())
                putInt(0)
                putInt(idx + 1) // sourceItemId
                putInt(0)
            }.array()
            val fxChunks = if (idx == 0) {
                effectSpecs.map { (matchN, dispN) ->
                    listChunk(
                        "LIST",
                        "sspc",
                        listOf(
                            chunk("tdmn", (matchN + "\u0000").toByteArray(Charsets.UTF_8)),
                            utf8Chunk(dispN)
                        )
                    )
                }
            } else emptyList()
            val layrChildren = mutableListOf(
                utf8Chunk(AepMediaResolver.extractCleanFileName(winPath).ifBlank { "Layer ${idx + 1}" }),
                chunk("ldta", ldta)
            )
            if (fxChunks.isNotEmpty()) {
                layrChildren.add(listChunk("LIST", "tdgp", fxChunks))
            }
            mainCompChildren.add(listChunk("LIST", "Layr", layrChildren))
        }

        if (!nestedCompName.isNullOrBlank() && footageWindowsPaths.isNotEmpty()) {
            val ldtaPre = ByteBuffer.allocate(40).order(ByteOrder.BIG_ENDIAN).apply {
                putInt(footageWindowsPaths.size + 1)
                putShort(1)
                putShort(0)
                putInt(0)
                putInt(durationMs.toInt())
                putInt(0)
                putInt(nestedCompId) // points to nested comp -> maps to GROUP layer!
                putInt(0)
            }.array()
            mainCompChildren.add(listChunk("LIST", "Layr", listOf(utf8Chunk(nestedCompName), chunk("ldta", ldtaPre))))
        }

        itemChunks.add(listChunk("LIST", "Item", mainCompChildren))
        val fold = listChunk("Fold", "ukwn", itemChunks)
        return listChunk("RIFX", "Egg!", listOf(fold))
    }

    private fun ldtaNestedOr(b: ByteArray): ByteArray = b

    private suspend fun parseAepBytes(
        context: Context?,
        rawBytes: ByteArray,
        fileName: String,
        sourceUri: Uri?,
        aepParentFolder: File?,
        extraSearchRoots: List<File>,
        onProgress: (Float, String) -> Unit
    ): PendingImportSession {
        val cleanProjectTitle = fileName.substringBeforeLast('.').ifBlank { "After Effects Comp" }

        // Support XML-formatted .aepx transparently if XML header detected
        val head = String(rawBytes, 0, min(rawBytes.size, 128), Charsets.ISO_8859_1).trimStart()
        val items = if (head.startsWith("<")) {
            parseAepxXmlItems(String(rawBytes, Charsets.UTF_8), cleanProjectTitle)
        } else {
            parseRifxAepItems(rawBytes, cleanProjectTitle)
        }

        // Choose primary composition (prefer comp without incoming references, or largest layer count)
        val comps = items.values.filter { it.kind == 2 && it.layers.isNotEmpty() }
        val referencedCompIds = comps.flatMap { c -> c.layers.map { it.sourceItemId } }.toSet()
        val rootComp = comps.firstOrNull { it.id !in referencedCompIds }
            ?: comps.maxByOrNull { it.layers.size }

        // Collect all discovered footage paths (from structured Item chunks + deep binary Windows path scan fallback)
        val allFootageItems = LinkedHashMap<Int, AepItemNode>()
        items.values.filter { it.kind == 4 && it.footagePath.isNotBlank() }.forEach {
            allFootageItems[it.id] = it
        }
        if (allFootageItems.isEmpty()) {
            val scannedPaths = scanBinaryForMediaPaths(rawBytes)
            scannedPaths.forEachIndexed { idx, p ->
                val synthId = 9000 + idx
                allFootageItems[synthId] = AepItemNode(
                    id = synthId,
                    name = AepMediaResolver.extractCleanFileName(p),
                    kind = 4,
                    footagePath = p
                )
            }
        }

        onProgress(0.25f, "Scanning media folders...")
        val pathRequests = allFootageItems.values.map { it.footagePath to it.expectedBytes }.distinctBy { it.first }
        val resolvedAssets = AepMediaResolver.resolveAssetPaths(
            context = context,
            requests = pathRequests,
            aepOwnFolder = aepParentFolder,
            extraSearchRoots = extraSearchRoots,
            forceRescan = false,
            onProgress = { p, lbl -> onProgress(0.25f + p * 0.60f, "Searching $lbl") }
        )
        val resolvedByPath = resolvedAssets.associateBy { it.storedPath }

        val compW = rootComp?.width?.coerceIn(128, 7680) ?: 1920
        val compH = rootComp?.height?.coerceIn(128, 7680) ?: 1080
        val compFps = rootComp?.fps?.coerceIn(12, 120) ?: 60
        val compDurMs = rootComp?.durationMs?.coerceIn(500L, 600_000L) ?: 10_000L

        val outLayers = ArrayList<Layer>()
        val effectRecords = ArrayList<ImportedEffectRecord>()
        val layerIdsByStoredPath = LinkedHashMap<String, MutableList<String>>()

        fun convertLayerList(
            compNode: AepItemNode,
            parentGroupId: String?,
            timeShiftMs: Long,
            visitedComps: Set<Int>
        ) {
            val indexToLayerId = HashMap<Int, String>()
            val pendingParentLinks = ArrayList<Pair<Int, Int>>() // childIdx -> parentIdx

            compNode.layers.forEach { aepLyr ->
                val lid = "aep_lyr_${compNode.id}_${aepLyr.index}_${UUID.randomUUID().toString().take(5)}"
                indexToLayerId[aepLyr.index] = lid
                if (aepLyr.parentIndex > 0) {
                    pendingParentLinks.add(aepLyr.index to aepLyr.parentIndex)
                }

                val startMs = (aepLyr.startTimeMs + timeShiftMs).coerceAtLeast(0L)
                val endMs = (aepLyr.endTimeMs + timeShiftMs).coerceAtLeast(startMs + 200L)
                val srcItem = items[aepLyr.sourceItemId] ?: allFootageItems[aepLyr.sourceItemId]

                // Convert AE effects on this layer using AeEffectPortRegistry
                val portedNodes = ArrayList<EffectNode>()
                aepLyr.effects.forEach { fxSpec ->
                    val portRes = AeEffectPortRegistry.portDesktopEffect(fxSpec)
                    if (portRes.effectNode != null) {
                        portedNodes.add(portRes.effectNode)
                    }
                    effectRecords.add(
                        ImportedEffectRecord(
                            layerId = lid,
                            layerName = aepLyr.name,
                            originalDisplayName = fxSpec.displayName.ifBlank { fxSpec.matchName },
                            originalMatchName = fxSpec.matchName,
                            status = portRes.status,
                            mappedEffectId = portRes.mappedEffectId,
                            mappedEffectName = portRes.mappedEffectName,
                            effectNodeId = portRes.effectNode?.id
                        )
                    )
                }

                when {
                    // Nested Comp -> Map directly to Group Layer (`LayerType.GROUP`)!
                    srcItem != null && srcItem.kind == 2 && srcItem.id !in visitedComps -> {
                        val groupLayer = Layer(
                            id = lid,
                            name = aepLyr.name.ifBlank { srcItem.name },
                            type = LayerType.GROUP,
                            visible = true,
                            startTimeMs = startMs,
                            endTimeMs = endMs,
                            parentId = parentGroupId,
                            colorHex = "#8B5CF6",
                            posX = AnimatableProperty(aepLyr.posX),
                            posY = AnimatableProperty(aepLyr.posY),
                            scale = AnimatableProperty(aepLyr.scalePct),
                            rotationZ = AnimatableProperty(aepLyr.rotZ),
                            opacity = AnimatableProperty(aepLyr.opacityPct),
                            effects = portedNodes
                        )
                        outLayers.add(groupLayer)
                        convertLayerList(
                            compNode = srcItem,
                            parentGroupId = lid,
                            timeShiftMs = startMs - aepLyr.inPointMs,
                            visitedComps = visitedComps + srcItem.id
                        )
                    }

                    // Footage Item (Video / Image / Audio)
                    srcItem != null && srcItem.footagePath.isNotBlank() -> {
                        val res = resolvedByPath[srcItem.footagePath]
                        val cleanName = res?.extractedFileName?.ifBlank { aepLyr.name } ?: aepLyr.name
                        val mediaType = AepMediaResolver.inferLayerTypeFromFileName(cleanName)
                        layerIdsByStoredPath.getOrPut(srcItem.footagePath) { ArrayList() }.add(lid)
                        outLayers.add(
                            Layer(
                                id = lid,
                                name = aepLyr.name.ifBlank { cleanName },
                                type = mediaType,
                                visible = true,
                                startTimeMs = startMs,
                                endTimeMs = endMs,
                                sourceOffsetMs = aepLyr.inPointMs.coerceAtLeast(0L),
                                sourceDurationMs = (endMs - startMs).coerceAtLeast(1000L),
                                parentId = parentGroupId,
                                mediaUri = res?.matchedUri.orEmpty(),
                                colorHex = if (mediaType == LayerType.AUDIO) "#38BDF8" else "#4F7CFF",
                                posX = AnimatableProperty(aepLyr.posX),
                                posY = AnimatableProperty(aepLyr.posY),
                                scale = AnimatableProperty(aepLyr.scalePct),
                                rotationZ = AnimatableProperty(aepLyr.rotZ),
                                opacity = AnimatableProperty(aepLyr.opacityPct),
                                effects = portedNodes
                            )
                        )
                    }

                    // Text Layer
                    !aepLyr.textContent.isNullOrBlank() -> {
                        outLayers.add(
                            Layer(
                                id = lid,
                                name = aepLyr.name.ifBlank { aepLyr.textContent!!.take(18) },
                                type = LayerType.TEXT,
                                visible = true,
                                startTimeMs = startMs,
                                endTimeMs = endMs,
                                parentId = parentGroupId,
                                textContent = aepLyr.textContent!!,
                                colorHex = "#FFFFFF",
                                posX = AnimatableProperty(aepLyr.posX),
                                posY = AnimatableProperty(aepLyr.posY),
                                scale = AnimatableProperty(aepLyr.scalePct),
                                rotationZ = AnimatableProperty(aepLyr.rotZ),
                                opacity = AnimatableProperty(aepLyr.opacityPct),
                                effects = portedNodes
                            )
                        )
                    }

                    // Null / Adjustment / Solid Layer
                    else -> {
                        val isAdj = aepLyr.isNullOrAdj && portedNodes.isNotEmpty()
                        val adjFx = if (isAdj) {
                            listOf(
                                EffectNode(
                                    effectId = "Copy_Background",
                                    displayName = "Copy Background",
                                    category = "Distortion / Warp",
                                    mixParam = 100f
                                )
                            ) + portedNodes
                        } else portedNodes
                        outLayers.add(
                            Layer(
                                id = lid,
                                name = aepLyr.name.ifBlank { "Solid ${aepLyr.index}" },
                                type = if (aepLyr.isNullOrAdj && portedNodes.isEmpty()) LayerType.NULL_OBJECT else LayerType.SHAPE,
                                visible = true,
                                startTimeMs = startMs,
                                endTimeMs = endMs,
                                parentId = parentGroupId,
                                colorHex = if (aepLyr.isNullOrAdj) "#EF4444" else "#23304A",
                                posX = AnimatableProperty(aepLyr.posX),
                                posY = AnimatableProperty(aepLyr.posY),
                                scale = AnimatableProperty(if (isAdj) 240f else aepLyr.scalePct),
                                rotationZ = AnimatableProperty(aepLyr.rotZ),
                                opacity = AnimatableProperty(aepLyr.opacityPct),
                                effects = adjFx
                            )
                        )
                    }
                }
            }

            // Wire intra-comp parent indices if child did not already get a parentGroupId override
            if (pendingParentLinks.isNotEmpty()) {
                val idLookup = outLayers.associateBy { it.id }
                for ((childIdx, parIdx) in pendingParentLinks) {
                    val childId = indexToLayerId[childIdx] ?: continue
                    val parId = indexToLayerId[parIdx] ?: continue
                    if (childId != parId && idLookup.containsKey(parId)) {
                        val pos = outLayers.indexOfFirst { it.id == childId }
                        if (pos >= 0) {
                            outLayers[pos] = outLayers[pos].copy(parentId = parId)
                        }
                    }
                }
            }
        }

        if (rootComp != null) {
            convertLayerList(rootComp, parentGroupId = null, timeShiftMs = 0L, visitedComps = setOf(rootComp.id))
        } else {
            // Fallback when an AEP binary uses compressed/proprietary chunk variants: build timeline from discovered assets & effects
            val fallbackEffects = scanBinaryForDesktopEffects(rawBytes)
            allFootageItems.values.forEachIndexed { idx, ft ->
                val lid = "aep_ft_${idx + 1}"
                val res = resolvedByPath[ft.footagePath]
                val cleanName = res?.extractedFileName?.ifBlank { ft.name } ?: ft.name
                val mType = AepMediaResolver.inferLayerTypeFromFileName(cleanName)
                layerIdsByStoredPath.getOrPut(ft.footagePath) { ArrayList() }.add(lid)
                val fxNodes = ArrayList<EffectNode>()
                if (idx == 0) {
                    fallbackEffects.forEach { spec ->
                        val p = AeEffectPortRegistry.portDesktopEffect(spec)
                        if (p.effectNode != null) fxNodes.add(p.effectNode)
                        effectRecords.add(
                            ImportedEffectRecord(
                                layerId = lid,
                                layerName = cleanName,
                                originalDisplayName = spec.displayName,
                                originalMatchName = spec.matchName,
                                status = p.status,
                                mappedEffectId = p.mappedEffectId,
                                mappedEffectName = p.mappedEffectName,
                                effectNodeId = p.effectNode?.id
                            )
                        )
                    }
                }
                outLayers.add(
                    Layer(
                        id = lid,
                        name = cleanName,
                        type = mType,
                        startTimeMs = 0L,
                        endTimeMs = compDurMs,
                        sourceDurationMs = compDurMs,
                        mediaUri = res?.matchedUri.orEmpty(),
                        effects = fxNodes
                    )
                )
            }
            if (outLayers.isEmpty()) {
                val lid = "aep_solid_1"
                val fxNodes = ArrayList<EffectNode>()
                fallbackEffects.forEach { spec ->
                    val p = AeEffectPortRegistry.portDesktopEffect(spec)
                    if (p.effectNode != null) fxNodes.add(p.effectNode)
                    effectRecords.add(
                        ImportedEffectRecord(
                            layerId = lid,
                            layerName = cleanProjectTitle,
                            originalDisplayName = spec.displayName,
                            originalMatchName = spec.matchName,
                            status = p.status,
                            mappedEffectId = p.mappedEffectId,
                            mappedEffectName = p.mappedEffectName,
                            effectNodeId = p.effectNode?.id
                        )
                    )
                }
                outLayers.add(
                    Layer(
                        id = lid,
                        name = cleanProjectTitle,
                        type = LayerType.SHAPE,
                        startTimeMs = 0L,
                        endTimeMs = compDurMs,
                        effects = fxNodes
                    )
                )
            }
        }

        val mediaSlots = pathRequests.map { (storedPath, expBytes) ->
            val res = resolvedByPath[storedPath]
            val cleanName = res?.extractedFileName?.ifBlank { AepMediaResolver.extractCleanFileName(storedPath) }
                ?: AepMediaResolver.extractCleanFileName(storedPath)
            val linkedLayerIds = layerIdsByStoredPath[storedPath].orEmpty()
            val slotDur = outLayers.filter { it.id in linkedLayerIds }
                .maxOfOrNull { (it.endTimeMs - it.startTimeMs).coerceAtLeast(500L) } ?: compDurMs
            ImportMediaAssetSlot(
                storedPath = storedPath,
                displayName = cleanName,
                mediaType = AepMediaResolver.inferLayerTypeFromFileName(cleanName),
                durationMs = slotDur,
                expectedSizeBytes = expBytes,
                status = res?.status ?: AssetMatchStatus.MISSING,
                resolvedUri = res?.matchedUri.orEmpty(),
                layerIds = linkedLayerIds
            )
        }

        val maxEnd = outLayers.maxOfOrNull { it.endTimeMs } ?: compDurMs
        val project = Project(
            name = rootComp?.name?.ifBlank { cleanProjectTitle } ?: cleanProjectTitle,
            aspectRatioPreset = inferAspectLabel(compW, compH),
            width = compW,
            height = compH,
            fps = compFps,
            durationMs = max(compDurMs, maxEnd),
            backgroundColor = "Black",
            layers = outLayers
        )

        val missingCount = mediaSlots.count { it.status == AssetMatchStatus.MISSING }
        val needsPerm = context != null && missingCount > 0 && !AepMediaResolver.hasAllFilesAccess(context)
        onProgress(1f, "Ready")

        return PendingImportSession(
            sourceUri = sourceUri,
            fileKind = ImportedFileKind.AEP,
            sourceFileName = fileName,
            aepParentFolder = aepParentFolder,
            previewProject = project,
            mediaSlots = mediaSlots,
            effectRecords = effectRecords,
            needsStoragePermissionHint = needsPerm,
            summaryNote = "${outLayers.size} layers • ${mediaSlots.size} media • ${effectRecords.size} effects"
        )
    }

    private fun parseRifxAepItems(bytes: ByteArray, fallbackCompName: String): Map<Int, AepItemNode> {
        val items = LinkedHashMap<Int, AepItemNode>()
        if (bytes.size < 12) return items
        val magic = String(bytes, 0, 4, Charsets.ISO_8859_1)
        val order = if (magic == "RIFF") ByteOrder.LITTLE_ENDIAN else ByteOrder.BIG_ENDIAN
        val buf = ByteBuffer.wrap(bytes).order(order)

        var nextSyntheticItemId = 1000

        fun readFourCc(offset: Int): String {
            if (offset + 4 > bytes.size) return ""
            return String(bytes, offset, 4, Charsets.ISO_8859_1)
        }

        fun parseEffectGroup(start: Int, end: Int, outEffects: MutableList<RawDesktopEffectSpec>) {
            var pos = start
            var pendingMatchName: String? = null
            var pendingDisplayName: String? = null
            val pendingFloats = LinkedHashMap<String, Float>()

            while (pos + 8 <= end) {
                val tag = readFourCc(pos)
                val len = buf.getInt(pos + 4)
                if (len < 0 || pos + 8 + len > end) break
                val dataStart = pos + 8
                val dataEnd = dataStart + len

                if (tag == "LIST" || tag == "Fold") {
                    if (len >= 4) {
                        val subType = readFourCc(dataStart)
                        if (subType == "sspc" || subType == "parT") {
                            val subFx = ArrayList<RawDesktopEffectSpec>()
                            var subMatch = ""
                            var subDisp = ""
                            val subFloats = LinkedHashMap<String, Float>()
                            var p = dataStart + 4
                            while (p + 8 <= dataEnd) {
                                val cTag = readFourCc(p)
                                val cLen = buf.getInt(p + 4)
                                if (cLen < 0 || p + 8 + cLen > dataEnd) break
                                val cStart = p + 8
                                when (cTag) {
                                    "tdmn" -> {
                                        val s = decodeCleanAscii(bytes, cStart, cLen)
                                        if (subMatch.isBlank() && isLikelyEffectMatchName(s)) subMatch = s
                                    }
                                    "Utf8", "tdsn" -> {
                                        val s = decodeCleanUtf8(bytes, cStart, cLen)
                                        if (s.isNotBlank() && subDisp.isBlank()) subDisp = s
                                    }
                                    "cdat" -> {
                                        if (cLen >= 8) {
                                            val v = buf.getDouble(cStart).toFloat()
                                            if (v.isFinite()) subFloats["param_${subFloats.size}"] = v
                                        }
                                    }
                                    "LIST", "Fold" -> {
                                        if (cLen >= 4) parseEffectGroup(cStart + 4, cStart + cLen, subFx)
                                    }
                                }
                                p = cStart + cLen + (cLen and 1)
                            }
                            if (subMatch.isNotBlank() || subDisp.isNotBlank()) {
                                outEffects.add(
                                    RawDesktopEffectSpec(
                                        matchName = subMatch.ifBlank { subDisp },
                                        displayName = subDisp.ifBlank { subMatch },
                                        floatParams = subFloats
                                    )
                                )
                            }
                            outEffects.addAll(subFx)
                        } else {
                            parseEffectGroup(dataStart + 4, dataEnd, outEffects)
                        }
                    }
                } else if (tag == "tdmn") {
                    val m = decodeCleanAscii(bytes, dataStart, len)
                    if (isLikelyEffectMatchName(m)) {
                        if (pendingMatchName != null) {
                            outEffects.add(
                                RawDesktopEffectSpec(
                                    matchName = pendingMatchName!!,
                                    displayName = pendingDisplayName ?: pendingMatchName!!,
                                    floatParams = HashMap(pendingFloats)
                                )
                            )
                            pendingFloats.clear()
                        }
                        pendingMatchName = m
                        pendingDisplayName = null
                    }
                } else if ((tag == "Utf8" || tag == "tdsn") && pendingMatchName != null && pendingDisplayName == null) {
                    val s = decodeCleanUtf8(bytes, dataStart, len)
                    if (s.isNotBlank()) pendingDisplayName = s
                } else if (tag == "cdat" && pendingMatchName != null && len >= 8) {
                    val v = buf.getDouble(dataStart).toFloat()
                    if (v.isFinite()) pendingFloats["param_${pendingFloats.size}"] = v
                }
                pos = dataEnd + (len and 1)
            }

            if (pendingMatchName != null) {
                outEffects.add(
                    RawDesktopEffectSpec(
                        matchName = pendingMatchName!!,
                        displayName = pendingDisplayName ?: pendingMatchName!!,
                        floatParams = pendingFloats
                    )
                )
            }
        }

        fun parseLayerList(start: Int, end: Int, compTimeScale: Int): AepLayerNode {
            var pos = start
            var lyrName = ""
            var lyrIdx = 1
            var srcId = 0
            var parentIdx = 0
            var startMs = 0L
            var endMs = 10_000L
            var inPtMs = 0L
            var isNull = false
            var textVal: String? = null
            val fxList = ArrayList<RawDesktopEffectSpec>()

            while (pos + 8 <= end) {
                val tag = readFourCc(pos)
                val len = buf.getInt(pos + 4)
                if (len < 0 || pos + 8 + len > end) break
                val dStart = pos + 8
                val dEnd = dStart + len
                when (tag) {
                    "Utf8" -> {
                        val s = decodeCleanUtf8(bytes, dStart, len)
                        if (s.isNotBlank() && lyrName.isBlank()) lyrName = s
                    }
                    "ldta" -> {
                        if (len >= 32) {
                            lyrIdx = buf.getInt(dStart).coerceAtLeast(1)
                            val scale = compTimeScale.coerceAtLeast(1)
                            val rawSt = buf.getInt(dStart + 8)
                            val rawDur = buf.getInt(dStart + 12)
                            val rawIn = buf.getInt(dStart + 16)
                            srcId = buf.getInt(dStart + 20)
                            parentIdx = if (len >= 28) buf.getInt(dStart + 24).coerceAtLeast(0) else 0
                            startMs = ((rawSt.toLong() * 1000L) / scale).coerceIn(0L, 600_000L)
                            val durMs = ((rawDur.toLong() * 1000L) / scale).coerceIn(100L, 600_000L)
                            endMs = startMs + durMs
                            inPtMs = ((rawIn.toLong() * 1000L) / scale).coerceAtLeast(0L)
                            if (srcId == 0) isNull = true
                        }
                    }
                    "LIST", "Fold" -> {
                        if (len >= 4) {
                            val sub = readFourCc(dStart)
                            if (sub == "tdgp" || sub == "efgp" || sub == "sspc") {
                                parseEffectGroup(dStart + 4, dEnd, fxList)
                            } else {
                                parseEffectGroup(dStart + 4, dEnd, fxList)
                            }
                        }
                    }
                }
                pos = dEnd + (len and 1)
            }
            if (lyrName.contains("Null", ignoreCase = true) || lyrName.contains("Adjustment", ignoreCase = true)) {
                isNull = true
            }
            return AepLayerNode(
                index = lyrIdx,
                name = lyrName.ifBlank { "Layer $lyrIdx" },
                sourceItemId = srcId,
                parentIndex = parentIdx,
                startTimeMs = startMs,
                endTimeMs = endMs,
                inPointMs = inPtMs,
                isNullOrAdj = isNull,
                textContent = textVal,
                effects = fxList
            )
        }

        fun parseItemChunk(start: Int, end: Int) {
            var pos = start
            var itemName = ""
            var itemKind = 4
            var itemId = nextSyntheticItemId++
            var compW = 1920
            var compH = 1080
            var compFps = 60
            var compDurMs = 10_000L
            var compTimeScale = 1000
            var footagePath = ""
            val compLayers = ArrayList<AepLayerNode>()

            fun scanSubPinForFootage(pStart: Int, pEnd: Int) {
                var p = pStart
                while (p + 8 <= pEnd) {
                    val t = readFourCc(p)
                    val l = buf.getInt(p + 4)
                    if (l < 0 || p + 8 + l > pEnd) break
                    val ds = p + 8
                    if (t == "alas" || t == "Utf8" || t == "opti") {
                        val txt = decodeCleanUtf8(bytes, ds, l)
                        if (txt.startsWith("{")) {
                            val fp = runCatching { JSONObject(txt).optString("fullpath", "") }.getOrDefault("")
                            if (fp.isNotBlank()) footagePath = fp
                        } else if (looksLikeMediaPath(txt) && footagePath.isBlank()) {
                            footagePath = txt
                        }
                    } else if ((t == "LIST" || t == "Fold") && l >= 4) {
                        scanSubPinForFootage(ds + 4, ds + l)
                    }
                    p = ds + l + (l and 1)
                }
            }

            while (pos + 8 <= end) {
                val tag = readFourCc(pos)
                val len = buf.getInt(pos + 4)
                if (len < 0 || pos + 8 + len > end) break
                val dStart = pos + 8
                val dEnd = dStart + len
                when (tag) {
                    "Utf8" -> {
                        val s = decodeCleanUtf8(bytes, dStart, len)
                        if (s.isNotBlank() && itemName.isBlank()) itemName = s
                    }
                    "idta" -> {
                        if (len >= 6) {
                            itemKind = buf.getShort(dStart).toInt()
                            val parsedId = if (len >= 8) buf.getInt(dStart + 4) else itemId
                            if (parsedId > 0) itemId = parsedId
                        }
                    }
                    "cdta" -> {
                        itemKind = 2
                        if (len >= 32) {
                            val ts = buf.getInt(dStart + 2).coerceAtLeast(1)
                            compTimeScale = ts
                            val rawDur = buf.getInt(dStart + 14)
                            compDurMs = ((rawDur.toLong() * 1000L) / ts).coerceIn(300L, 600_000L)
                            val w = buf.getShort(dStart + 26).toInt() and 0xFFFF
                            val h = buf.getShort(dStart + 28).toInt() and 0xFFFF
                            val f = if (len >= 32) (buf.getShort(dStart + 30).toInt() and 0xFFFF) else 60
                            if (w in 64..7680) compW = w
                            if (h in 64..7680) compH = h
                            if (f in 1..240) compFps = f
                        }
                    }
                    "LIST", "Fold" -> {
                        if (len >= 4) {
                            val subType = readFourCc(dStart)
                            when (subType) {
                                "Layr" -> compLayers.add(parseLayerList(dStart + 4, dEnd, compTimeScale))
                                "Pin ", "Als2", "Sfdr" -> scanSubPinForFootage(dStart + 4, dEnd)
                                else -> scanSubPinForFootage(dStart + 4, dEnd)
                            }
                        }
                    }
                }
                pos = dEnd + (len and 1)
            }

            items[itemId] = AepItemNode(
                id = itemId,
                name = itemName.ifBlank { if (itemKind == 2) fallbackCompName else "Asset $itemId" },
                kind = itemKind,
                width = compW,
                height = compH,
                fps = compFps,
                durationMs = compDurMs,
                footagePath = footagePath,
                layers = compLayers
            )
        }

        fun walkRifx(start: Int, end: Int) {
            var pos = start
            while (pos + 8 <= end) {
                val tag = readFourCc(pos)
                val len = buf.getInt(pos + 4)
                if (len < 0 || pos + 8 + len > end) break
                val dStart = pos + 8
                val dEnd = dStart + len
                if ((tag == "LIST" || tag == "Fold") && len >= 4) {
                    val form = readFourCc(dStart)
                    if (form == "Item") {
                        parseItemChunk(dStart + 4, dEnd)
                    } else {
                        walkRifx(dStart + 4, dEnd)
                    }
                }
                pos = dEnd + (len and 1)
            }
        }

        walkRifx(12, bytes.size)
        return items
    }

    private fun parseAepxXmlItems(xmlText: String, fallbackCompName: String): Map<Int, AepItemNode> {
        val items = LinkedHashMap<Int, AepItemNode>()
        val winPathRegex = Regex("""([a-zA-Z]:[\\/][^"<>\r\n]+\.(?:mp4|mov|mkv|webm|png|jpg|jpeg|webp|mp3|wav|m4a))""", RegexOption.IGNORE_CASE)
        val foundPaths = winPathRegex.findAll(xmlText).map { it.value }.distinct().toList()
        foundPaths.forEachIndexed { idx, p ->
            val id = idx + 1
            items[id] = AepItemNode(
                id = id,
                name = AepMediaResolver.extractCleanFileName(p),
                kind = 4,
                footagePath = p
            )
        }
        val compLayers = foundPaths.mapIndexed { idx, p ->
            AepLayerNode(
                index = idx + 1,
                name = AepMediaResolver.extractCleanFileName(p),
                sourceItemId = idx + 1,
                startTimeMs = 0L,
                endTimeMs = 8000L
            )
        }.toMutableList()
        val compId = foundPaths.size + 1
        items[compId] = AepItemNode(
            id = compId,
            name = fallbackCompName,
            kind = 2,
            layers = compLayers
        )
        return items
    }

    // =============================================================================================
    // 2. KEYFLOW (.kfproject ZIP or JSON) IMPORTER + NESTED COMP -> GROUP LAYER
    // =============================================================================================

    private suspend fun parseKfProjectBytes(
        context: Context?,
        rawBytes: ByteArray,
        fileName: String,
        sourceUri: Uri?,
        parentFolder: File?,
        extraSearchRoots: List<File>,
        onProgress: (Float, String) -> Unit
    ): PendingImportSession {
        val extractedAssetsByName = HashMap<String, String>()
        var projectJsonText = ""

        val isZip = rawBytes.size >= 4 && rawBytes[0] == 'P'.code.toByte() && rawBytes[1] == 'K'.code.toByte()
        if (isZip) {
            val extractDir = if (context != null) {
                File(context.filesDir, "imported_kfproject/${UUID.randomUUID().toString().take(8)}").apply { mkdirs() }
            } else null

            ZipInputStream(ByteArrayInputStream(rawBytes)).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    if (!entry.isDirectory) {
                        val norm = entry.name.replace('\\', '/').trimStart('/')
                        if (!norm.contains("..")) {
                            val bytes = zis.readBytes()
                            when {
                                norm.endsWith(".json", ignoreCase = true) &&
                                    (projectJsonText.isBlank() || norm.equals("project.json", ignoreCase = true) || norm.equals("manifest.json", ignoreCase = true)) -> {
                                    projectJsonText = bytes.toString(Charsets.UTF_8)
                                }
                                norm.endsWith(".kfpack", ignoreCase = true) || norm.endsWith(".fppack", ignoreCase = true) -> {
                                    PluginPackManager.importPackFromBytes(context, bytes, ".kfpack")
                                }
                                extractDir != null -> {
                                    val baseName = norm.substringAfterLast('/')
                                    if (baseName.isNotBlank()) {
                                        val outFile = File(extractDir, baseName)
                                        outFile.writeBytes(bytes)
                                        extractedAssetsByName[baseName.lowercase(Locale.US)] = Uri.fromFile(outFile).toString()
                                    }
                                }
                            }
                        }
                    }
                    zis.closeEntry()
                    entry = zis.nextEntry
                }
            }
        } else {
            projectJsonText = rawBytes.toString(Charsets.UTF_8)
        }

        if (projectJsonText.isBlank()) {
            throw IllegalArgumentException("Invalid .kfproject file: missing project JSON manifest")
        }

        val root = JSONObject(PluginPackManager.stripJsonComments(projectJsonText))
        val projTitle = root.optString("name", root.optString("title", fileName.substringBeforeLast('.')))
            .ifBlank { "Keyflow Project" }
        val width = root.optInt("width", 1080).coerceIn(128, 7680)
        val height = root.optInt("height", 1920).coerceIn(128, 7680)
        val fps = root.optInt("fps", 60).coerceIn(12, 120)
        val durMs = root.optLong("durationMs", root.optLong("duration", 10_000L)).coerceAtLeast(500L)
        val bpm = root.optInt("bpm", 128).coerceIn(40, 300)
        val bg = root.optString("backgroundColor", root.optString("bg", "Black"))

        val outLayers = ArrayList<Layer>()
        val effectRecords = ArrayList<ImportedEffectRecord>()
        val externalPathRequests = LinkedHashMap<String, MutableList<String>>() // path -> layerIds

        // Parse nested compositions table if present (`compositions` / `subComps`)
        val compsById = HashMap<String, JSONArray>()
        root.optJSONArray("compositions")?.let { compsArr ->
            for (i in 0 until compsArr.length()) {
                val co = compsArr.optJSONObject(i) ?: continue
                val cid = co.optString("id", "")
                val cl = co.optJSONArray("layers")
                if (cid.isNotBlank() && cl != null) compsById[cid] = cl
            }
        }

        fun parseKfLayerArray(arr: JSONArray, parentGroupId: String?, shiftMs: Long) {
            for (i in 0 until arr.length()) {
                val lo = arr.optJSONObject(i) ?: continue
                val lid = lo.optString("id", "kf_lyr_${UUID.randomUUID().toString().take(6)}")
                val lName = lo.optString("name", "Layer ${i + 1}")
                val rawType = lo.optString("type", "SHAPE").uppercase(Locale.US)
                val nestedLayersArr = lo.optJSONArray("layers")
                    ?: lo.optJSONArray("children")
                    ?: compsById[lo.optString("compId", "")]

                val startMs = (lo.optLong("startTimeMs", lo.optLong("inMs", 0L)) + shiftMs).coerceAtLeast(0L)
                val endMs = (lo.optLong("endTimeMs", lo.optLong("outMs", durMs)) + shiftMs).coerceAtLeast(startMs + 100L)

                val fxNodes = ArrayList<EffectNode>()
                lo.optJSONArray("effects")?.let { fxArr ->
                    val decoded = ProjectPayloadSerializer.decodeEffects(fxArr)
                    decoded.forEach { node ->
                        val isKnown = PluginPackManager.isPluginEffect(node.effectId) ||
                            EffectsCatalog.findByIdOrNull(node.effectId) != null
                        if (isKnown) {
                            fxNodes.add(node)
                            effectRecords.add(
                                ImportedEffectRecord(
                                    layerId = lid,
                                    layerName = lName,
                                    originalDisplayName = node.displayName,
                                    originalMatchName = node.effectId,
                                    status = ImportEffectStatus.PORTED,
                                    mappedEffectId = node.effectId,
                                    mappedEffectName = node.displayName,
                                    effectNodeId = node.id
                                )
                            )
                        } else {
                            val port = AeEffectPortRegistry.portDesktopEffect(
                                RawDesktopEffectSpec(matchName = node.effectId, displayName = node.displayName)
                            )
                            if (port.effectNode != null) fxNodes.add(port.effectNode)
                            effectRecords.add(
                                ImportedEffectRecord(
                                    layerId = lid,
                                    layerName = lName,
                                    originalDisplayName = node.displayName,
                                    originalMatchName = node.effectId,
                                    status = port.status,
                                    mappedEffectId = port.mappedEffectId,
                                    mappedEffectName = port.mappedEffectName,
                                    effectNodeId = port.effectNode?.id
                                )
                            )
                        }
                    }
                }

                if (rawType == "GROUP" || rawType == "COMP" || rawType == "PRECOMP" || nestedLayersArr != null) {
                    val grpLayer = Layer(
                        id = lid,
                        name = lName,
                        type = LayerType.GROUP,
                        visible = lo.optBoolean("visible", true),
                        startTimeMs = startMs,
                        endTimeMs = endMs,
                        parentId = parentGroupId ?: lo.optString("parentId", "").ifBlank { null },
                        colorHex = "#8B5CF6",
                        posX = parseKfProp(lo, "posX", 0f),
                        posY = parseKfProp(lo, "posY", 0f),
                        scale = parseKfProp(lo, "scale", 100f),
                        rotationZ = parseKfProp(lo, "rotationZ", 0f),
                        opacity = parseKfProp(lo, "opacity", 100f),
                        effects = fxNodes
                    )
                    outLayers.add(grpLayer)
                    if (nestedLayersArr != null) {
                        parseKfLayerArray(nestedLayersArr, parentGroupId = lid, shiftMs = startMs)
                    }
                } else {
                    val rawMedia = lo.optString("mediaUri", lo.optString("assetPath", lo.optString("src", "")))
                    val cleanMediaName = AepMediaResolver.extractCleanFileName(rawMedia)
                    val bundledUri = extractedAssetsByName[cleanMediaName.lowercase(Locale.US)]
                    val resolvedInitialUri = bundledUri ?: if (rawMedia.startsWith("content://") || rawMedia.startsWith("file://")) rawMedia else ""
                    if (rawMedia.isNotBlank() && bundledUri == null) {
                        externalPathRequests.getOrPut(rawMedia) { ArrayList() }.add(lid)
                    }
                    val mappedType = runCatching { LayerType.valueOf(rawType) }.getOrElse {
                        if (rawMedia.isNotBlank()) AepMediaResolver.inferLayerTypeFromFileName(cleanMediaName) else LayerType.SHAPE
                    }
                    outLayers.add(
                        Layer(
                            id = lid,
                            name = lName,
                            type = mappedType,
                            visible = lo.optBoolean("visible", true),
                            startTimeMs = startMs,
                            endTimeMs = endMs,
                            sourceOffsetMs = lo.optLong("sourceOffsetMs", 0L),
                            sourceDurationMs = lo.optLong("sourceDurationMs", endMs - startMs),
                            parentId = parentGroupId ?: lo.optString("parentId", "").ifBlank { null },
                            mediaUri = resolvedInitialUri,
                            textContent = lo.optString("textContent", lo.optString("text", "TEXT")),
                            colorHex = lo.optString("colorHex", "#4F7CFF"),
                            posX = parseKfProp(lo, "posX", 0f),
                            posY = parseKfProp(lo, "posY", 0f),
                            posZ = parseKfProp(lo, "posZ", 0f),
                            scale = parseKfProp(lo, "scale", 100f),
                            rotationZ = parseKfProp(lo, "rotationZ", 0f),
                            opacity = parseKfProp(lo, "opacity", 100f),
                            effects = fxNodes
                        )
                    )
                }
            }
        }

        val layersArr = root.optJSONArray("layers") ?: JSONArray()
        parseKfLayerArray(layersArr, parentGroupId = null, shiftMs = 0L)

        // Build media slots from both bundled ZIP assets and any external paths
        val mediaSlots = ArrayList<ImportMediaAssetSlot>()
        extractedAssetsByName.forEach { (lowerName, localUri) ->
            val matchingIds = outLayers.filter { it.mediaUri == localUri }.map { it.id }
            val slotDur = outLayers.filter { it.id in matchingIds }
                .maxOfOrNull { (it.endTimeMs - it.startTimeMs).coerceAtLeast(500L) } ?: durMs
            mediaSlots.add(
                ImportMediaAssetSlot(
                    storedPath = localUri,
                    displayName = lowerName,
                    mediaType = AepMediaResolver.inferLayerTypeFromFileName(lowerName),
                    durationMs = slotDur,
                    status = AssetMatchStatus.MATCHED,
                    resolvedUri = localUri,
                    layerIds = matchingIds
                )
            )
        }

        if (externalPathRequests.isNotEmpty()) {
            onProgress(0.55f, "Resolving external media...")
            val reqs = externalPathRequests.keys.map { it to 0L }
            val resolved = AepMediaResolver.resolveAssetPaths(
                context = context,
                requests = reqs,
                aepOwnFolder = parentFolder,
                extraSearchRoots = extraSearchRoots
            )
            resolved.forEach { res ->
                val lids = externalPathRequests[res.storedPath].orEmpty()
                if (res.status == AssetMatchStatus.MATCHED && res.matchedUri.isNotBlank()) {
                    for (idx in outLayers.indices) {
                        if (outLayers[idx].id in lids) {
                            outLayers[idx] = outLayers[idx].copy(mediaUri = res.matchedUri)
                        }
                    }
                }
                val slotDur = outLayers.filter { it.id in lids }
                    .maxOfOrNull { (it.endTimeMs - it.startTimeMs).coerceAtLeast(500L) } ?: durMs
                mediaSlots.add(
                    ImportMediaAssetSlot(
                        storedPath = res.storedPath,
                        displayName = res.extractedFileName,
                        mediaType = AepMediaResolver.inferLayerTypeFromFileName(res.extractedFileName),
                        durationMs = slotDur,
                        status = res.status,
                        resolvedUri = res.matchedUri,
                        layerIds = lids
                    )
                )
            }
        }

        val maxEnd = outLayers.maxOfOrNull { it.endTimeMs } ?: durMs
        val project = Project(
            name = projTitle,
            aspectRatioPreset = inferAspectLabel(width, height),
            width = width,
            height = height,
            fps = fps,
            durationMs = max(durMs, maxEnd),
            bpm = bpm,
            backgroundColor = bg,
            layers = outLayers
        )
        onProgress(1f, "Ready")
        return PendingImportSession(
            sourceUri = sourceUri,
            fileKind = ImportedFileKind.KFPROJECT,
            sourceFileName = fileName,
            aepParentFolder = parentFolder,
            previewProject = project,
            mediaSlots = mediaSlots,
            effectRecords = effectRecords,
            needsStoragePermissionHint = context != null &&
                mediaSlots.any { it.status == AssetMatchStatus.MISSING } &&
                !AepMediaResolver.hasAllFilesAccess(context)
        )
    }

    private fun parseKfProp(layerObj: JSONObject, key: String, defaultVal: Float): AnimatableProperty {
        val sub = layerObj.optJSONObject(key)
        if (sub != null) {
            val base = sub.optDouble("baseValue", sub.optDouble("value", defaultVal.toDouble())).toFloat()
            val kfsArr = sub.optJSONArray("keyframes")
            val kfs = if (kfsArr != null) {
                buildList {
                    for (i in 0 until kfsArr.length()) {
                        val ko = kfsArr.optJSONObject(i) ?: continue
                        add(
                            Keyframe(
                                timeMs = ko.optLong("timeMs", ko.optLong("t", 0L)),
                                value = ko.optDouble("value", ko.optDouble("v", base.toDouble())).toFloat(),
                                outTangentX = ko.optDouble("outTangentX", 0.42).toFloat(),
                                outTangentY = ko.optDouble("outTangentY", 0.0).toFloat(),
                                inTangentX = ko.optDouble("inTangentX", 0.58).toFloat(),
                                inTangentY = ko.optDouble("inTangentY", 1.0).toFloat(),
                                holdStep = ko.optBoolean("holdStep", false)
                            )
                        )
                    }
                }
            } else emptyList()
            return AnimatableProperty(baseValue = base, keyframes = kfs)
        }
        return AnimatableProperty(layerObj.optDouble(key, defaultVal.toDouble()).toFloat())
    }

    // =============================================================================================
    // 3. XML (.xml / .fpxml) IMPORTER -> PENDING IMPORT SESSION
    // =============================================================================================

    private suspend fun parseXmlProjectBytes(
        context: Context?,
        rawBytes: ByteArray,
        fileName: String,
        sourceUri: Uri?,
        parentFolder: File?,
        extraSearchRoots: List<File>,
        onProgress: (Float, String) -> Unit
    ): PendingImportSession {
        val xmlText = rawBytes.toString(Charsets.UTF_8)
        val xmlRes = ProjectXmlEngine.importProjectFromXml(
            context = context,
            xmlContent = xmlText,
            fallbackName = fileName.substringBeforeLast('.').ifBlank { "XML Project" }
        ).getOrThrow()

        var layers = xmlRes.project.layers
        val mediaLayersByUri = layers.filter {
            (it.type == LayerType.VIDEO || it.type == LayerType.IMAGE || it.type == LayerType.AUDIO) &&
                it.mediaUri.isNotBlank()
        }.groupBy { it.mediaUri }

        val mediaSlots = ArrayList<ImportMediaAssetSlot>()
        if (mediaLayersByUri.isNotEmpty()) {
            onProgress(0.50f, "Checking media assets...")
            val reqs = mediaLayersByUri.keys.map { it to 0L }
            val resolved = AepMediaResolver.resolveAssetPaths(
                context = context,
                requests = reqs,
                aepOwnFolder = parentFolder,
                extraSearchRoots = extraSearchRoots
            )
            val resolvedMap = LinkedHashMap<String, String>()
            resolved.forEach { res ->
                val grp = mediaLayersByUri[res.storedPath].orEmpty()
                val lids = grp.map { it.id }
                if (res.status == AssetMatchStatus.MATCHED && res.matchedUri.isNotBlank()) {
                    lids.forEach { resolvedMap[it] = res.matchedUri }
                }
                val dur = grp.maxOfOrNull { (it.endTimeMs - it.startTimeMs).coerceAtLeast(500L) } ?: 5000L
                val mType = grp.firstOrNull()?.type ?: AepMediaResolver.inferLayerTypeFromFileName(res.extractedFileName)
                mediaSlots.add(
                    ImportMediaAssetSlot(
                        storedPath = res.storedPath,
                        displayName = res.extractedFileName.ifBlank { grp.firstOrNull()?.name ?: "Media" },
                        mediaType = mType,
                        durationMs = dur,
                        status = res.status,
                        resolvedUri = res.matchedUri,
                        layerIds = lids
                    )
                )
            }
            if (resolvedMap.isNotEmpty()) {
                layers = layers.map { l -> resolvedMap[l.id]?.let { u -> l.copy(mediaUri = u) } ?: l }
            }
        }

        val effectRecords = ArrayList<ImportedEffectRecord>()
        layers.forEach { lyr ->
            lyr.effects.forEach { fx ->
                effectRecords.add(
                    ImportedEffectRecord(
                        layerId = lyr.id,
                        layerName = lyr.name,
                        originalDisplayName = fx.displayName,
                        originalMatchName = fx.effectId,
                        status = ImportEffectStatus.PORTED,
                        mappedEffectId = fx.effectId,
                        mappedEffectName = fx.displayName,
                        effectNodeId = fx.id
                    )
                )
            }
        }

        val updatedProj = xmlRes.project.copy(layers = layers)
        onProgress(1f, "Ready")
        return PendingImportSession(
            sourceUri = sourceUri,
            fileKind = ImportedFileKind.XML,
            sourceFileName = fileName,
            aepParentFolder = parentFolder,
            previewProject = updatedProj,
            mediaSlots = mediaSlots,
            effectRecords = effectRecords,
            needsStoragePermissionHint = context != null &&
                mediaSlots.any { it.status == AssetMatchStatus.MISSING } &&
                !AepMediaResolver.hasAllFilesAccess(context)
        )
    }

    // =============================================================================================
    // 4. AFTER EFFECTS PRESET (.ffx) IMPORTER
    // =============================================================================================

    fun buildSyntheticFfxBinary(effectSpecs: List<Pair<String, String>>): ByteArray {
        fun chunk(fourCc: String, payload: ByteArray): ByteArray {
            val pad = if (payload.size % 2 == 1) 1 else 0
            val out = ByteBuffer.allocate(8 + payload.size + pad).order(ByteOrder.BIG_ENDIAN)
            out.put(fourCc.take(4).padEnd(4, ' ').toByteArray(Charsets.ISO_8859_1))
            out.putInt(payload.size)
            out.put(payload)
            if (pad == 1) out.put(0)
            return out.array()
        }
        fun listChunk(listTag: String, formType: String, children: List<ByteArray>): ByteArray {
            val totalChild = children.sumOf { it.size }
            val payload = ByteBuffer.allocate(4 + totalChild).order(ByteOrder.BIG_ENDIAN)
            payload.put(formType.take(4).padEnd(4, ' ').toByteArray(Charsets.ISO_8859_1))
            children.forEach { payload.put(it) }
            return chunk(listTag, payload.array())
        }
        val fxChunks = effectSpecs.map { (matchN, dispN) ->
            listChunk(
                "LIST",
                "sspc",
                listOf(
                    chunk("tdmn", (matchN + "\u0000").toByteArray(Charsets.UTF_8)),
                    chunk("Utf8", dispN.toByteArray(Charsets.UTF_8))
                )
            )
        }
        return listChunk("RIFX", "FXTC", fxChunks)
    }

    private fun parseFfxPresetBytes(
        rawBytes: ByteArray,
        fileName: String,
        sourceUri: Uri?,
        activeProject: Project?,
        selectedLayerId: String?
    ): PendingImportSession {
        val specs = scanBinaryForDesktopEffects(rawBytes).ifEmpty {
            listOf(RawDesktopEffectSpec(matchName = fileName.substringBeforeLast('.'), displayName = fileName.substringBeforeLast('.')))
        }

        val baseProj = activeProject ?: Project(
            name = fileName.substringBeforeLast('.').ifBlank { "FFX Preset Preview" },
            width = 1080,
            height = 1080,
            fps = 60,
            durationMs = 6000L,
            layers = listOf(
                Layer(
                    id = "ffx_preview_layer",
                    name = "Preset Target Layer",
                    type = LayerType.SHAPE,
                    startTimeMs = 0L,
                    endTimeMs = 6000L,
                    colorHex = "#4F7CFF"
                )
            )
        )

        val targetLayer = baseProj.layers.firstOrNull { it.id == selectedLayerId }
            ?: baseProj.layers.firstOrNull()
            ?: Layer(id = "ffx_preview_layer", name = "Preset Layer", type = LayerType.SHAPE)

        val portedNodes = ArrayList<EffectNode>()
        val records = ArrayList<ImportedEffectRecord>()

        specs.forEach { spec ->
            val port = AeEffectPortRegistry.portDesktopEffect(spec)
            if (port.effectNode != null) portedNodes.add(port.effectNode)
            records.add(
                ImportedEffectRecord(
                    layerId = targetLayer.id,
                    layerName = targetLayer.name,
                    originalDisplayName = spec.displayName.ifBlank { spec.matchName },
                    originalMatchName = spec.matchName,
                    status = port.status,
                    mappedEffectId = port.mappedEffectId,
                    mappedEffectName = port.mappedEffectName,
                    effectNodeId = port.effectNode?.id
                )
            )
        }

        val updatedLayers = if (baseProj.layers.any { it.id == targetLayer.id }) {
            baseProj.layers.map { l ->
                if (l.id == targetLayer.id) l.copy(effects = l.effects + portedNodes) else l
            }
        } else {
            listOf(targetLayer.copy(effects = portedNodes))
        }

        return PendingImportSession(
            sourceUri = sourceUri,
            fileKind = ImportedFileKind.FFX,
            sourceFileName = fileName,
            previewProject = baseProj.copy(layers = updatedLayers),
            mediaSlots = emptyList(),
            effectRecords = records,
            isPresetForActiveProject = activeProject != null,
            targetPresetLayerId = targetLayer.id,
            summaryNote = "FFX Preset • ${records.count { it.status == ImportEffectStatus.PORTED }} ported, ${records.count { it.status == ImportEffectStatus.UNSUPPORTED }} unsupported"
        )
    }

    // =============================================================================================
    // BINARY SCANNING & STRING HELPERS
    // =============================================================================================

    private fun looksLikeKfpackZip(bytes: ByteArray): Boolean {
        if (bytes.size < 4 || bytes[0] != 'P'.code.toByte() || bytes[1] != 'K'.code.toByte()) return false
        return runCatching {
            ZipInputStream(ByteArrayInputStream(bytes)).use { zis ->
                var e = zis.nextEntry
                while (e != null) {
                    val n = e.name.replace('\\', '/').trimStart('/')
                    if (n == "suite.json" || n.endsWith("/suite.json") || n.endsWith("/effect.json")) {
                        return@runCatching true
                    }
                    e = zis.nextEntry
                }
                false
            }
        }.getOrDefault(false)
    }

    private fun scanBinaryForMediaPaths(bytes: ByteArray): List<String> {
        val textIso = String(bytes, Charsets.ISO_8859_1)
        val regex = Regex("""([a-zA-Z]:[\\/][^\u0000-\u001F"*?<>|\r\n]{2,180}\.(?:mp4|mov|mkv|webm|avi|png|jpg|jpeg|webp|mp3|wav|m4a|aac))""", RegexOption.IGNORE_CASE)
        return regex.findAll(textIso).map { it.value.trim() }.distinct().toList()
    }

    private fun scanBinaryForDesktopEffects(bytes: ByteArray): List<RawDesktopEffectSpec> {
        val out = ArrayList<RawDesktopEffectSpec>()
        if (bytes.size < 16) return out
        val magic = String(bytes, 0, 4, Charsets.ISO_8859_1)
        if (magic == "RIFX" || magic == "RIFF") {
            val order = if (magic == "RIFF") ByteOrder.LITTLE_ENDIAN else ByteOrder.BIG_ENDIAN
            val buf = ByteBuffer.wrap(bytes).order(order)
            fun walk(start: Int, end: Int) {
                var pos = start
                var lastTdmn: String? = null
                while (pos + 8 <= end) {
                    val tag = String(bytes, pos, 4, Charsets.ISO_8859_1)
                    val len = buf.getInt(pos + 4)
                    if (len < 0 || pos + 8 + len > end) break
                    val ds = pos + 8
                    val de = ds + len
                    if ((tag == "LIST" || tag == "Fold") && len >= 4) {
                        walk(ds + 4, de)
                    } else if (tag == "tdmn") {
                        val m = decodeCleanAscii(bytes, ds, len)
                        if (isLikelyEffectMatchName(m)) {
                            lastTdmn = m
                            out.add(RawDesktopEffectSpec(matchName = m, displayName = m))
                        }
                    } else if ((tag == "Utf8" || tag == "tdsn") && lastTdmn != null && out.isNotEmpty()) {
                        val s = decodeCleanUtf8(bytes, ds, len)
                        if (s.isNotBlank() && out.last().matchName == lastTdmn && out.last().displayName == lastTdmn) {
                            out[out.lastIndex] = out.last().copy(displayName = s)
                            lastTdmn = null
                        }
                    }
                    pos = de + (len and 1)
                }
            }
            walk(12, bytes.size)
        }
        return out.distinctBy { "${it.matchName}|${it.displayName}" }
    }

    private fun isLikelyEffectMatchName(raw: String): Boolean {
        val s = raw.trim()
        if (s.length < 3 || s.length > 64) return false
        val structural = setOf(
            "ADBE Group End", "ADBE Effect Parade", "ADBE Transform Group",
            "ADBE Layer Overrides", "ADBE Mask Parade", "ADBE Audio Group",
            "ADBE Material Options Group", "ADBE Geometry Options Group",
            "ADBE Anchor Point", "ADBE Position", "ADBE Scale", "ADBE Rotate Z", "ADBE Opacity",
            "ADBE Time Remapping"
        )
        if (s in structural) return false
        if (s.startsWith("ADBE ") && s.endsWith("-0001")) return false
        if (s.contains(Regex("-\\d{4}$"))) return false // Parameter matchNames end in -0001, -0002, etc.
        return true
    }

    private fun looksLikeMediaPath(txt: String): Boolean {
        val lower = txt.trim().lowercase(Locale.US)
        return listOf(".mp4", ".mov", ".mkv", ".webm", ".png", ".jpg", ".jpeg", ".webp", ".mp3", ".wav", ".m4a")
            .any { lower.endsWith(it) }
    }

    private fun decodeCleanAscii(bytes: ByteArray, offset: Int, len: Int): String =
        String(bytes, offset, len, Charsets.ISO_8859_1).substringBefore('\u0000').trim()

    private fun decodeCleanUtf8(bytes: ByteArray, offset: Int, len: Int): String =
        String(bytes, offset, len, Charsets.UTF_8).substringBefore('\u0000').trim()

    private fun resolveFileName(context: Context, uri: Uri): String {
        if (uri.scheme == "file") return uri.lastPathSegment ?: "import_file"
        return runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        }.getOrNull() ?: uri.lastPathSegment ?: "import_file"
    }

    private fun inferAspectLabel(w: Int, h: Int): String {
        val r = w.toFloat() / h.toFloat().coerceAtLeast(1f)
        return when {
            kotlin.math.abs(r - 16f / 9f) < 0.08f -> "16:9"
            kotlin.math.abs(r - 9f / 16f) < 0.08f -> "9:16"
            kotlin.math.abs(r - 1f) < 0.08f -> "1:1"
            kotlin.math.abs(r - 4f / 5f) < 0.08f -> "4:5"
            else -> "Custom"
        }
    }
}
