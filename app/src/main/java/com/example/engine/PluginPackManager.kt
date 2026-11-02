package com.example.engine

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.util.Locale
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

enum class PluginParamType {
    FLOAT,
    COLOR,
    BOOL
}

data class PluginParamDef(
    val key: String,
    val label: String,
    val type: PluginParamType = PluginParamType.FLOAT,
    val defaultFloat: Float = 0.5f,
    val defaultColorHex: String = "#FFFFFF",
    val min: Float = 0f,
    val max: Float = 1f,
    val step: Float = 0.01f
) {
    val uniformName: String = PluginPackManager.toUniformName(key)
}

data class PluginShaderPass(
    val fileName: String,
    val glslSource: String
)

data class InstalledPluginEffect(
    val id: String,
    val suiteId: String,
    val suiteName: String,
    val name: String,
    val category: String,
    val author: String,
    val version: String,
    val expandBounds: Float = 0f,
    val usesNativeMotionBlur: Boolean = false,
    val passes: List<PluginShaderPass>,
    val parameters: List<PluginParamDef>
) {
    val isMultiPass: Boolean get() = passes.size > 1
}

data class InstalledPluginSuite(
    val id: String,
    val name: String,
    val category: String,
    val author: String,
    val version: String,
    val minAppVersion: String = "1.4.9",
    val description: String = "",
    val archiveExtension: String = ".kfpack",
    val enabled: Boolean = true,
    val isBuiltInStarter: Boolean = false,
    val effects: List<InstalledPluginEffect>
)

/**
 * KeyFlow / FramePollution Custom Plugin Pack Engine (`.kfpack` & `.fppack`):
 * - Unpacks, validates, persists, and exports `.kfpack` and `.fppack` effect suite archives.
 * - Supports comment-annotated `suite.json` & `effect.json`, unlimited `FLOAT` / `BOOL` / `COLOR`
 *   parameters, multi-pass `passes` pipelines, `expandBounds` padding, and `usesNativeMotionBlur`.
 */
object PluginPackManager {

    const val EXT_KFPACK = ".kfpack"
    const val EXT_FPPACK = ".fppack"

    val OFFICIAL_CATEGORIES: List<String> = listOf(
        "Gerak",
        "Warna",
        "Distorsi",
        "Stilisasi",
        "Blur",
        "Simulasi",
        "Generator",
        "Transisi",
        "Keying",
        "Perspective",
        "Custom"
    )

    private val _installedSuites = MutableStateFlow<List<InstalledPluginSuite>>(emptyList())
    val installedSuites: StateFlow<List<InstalledPluginSuite>> = _installedSuites.asStateFlow()

    private val _shaderDiagnostics = MutableStateFlow<Map<String, String>>(emptyMap())
    val shaderDiagnostics: StateFlow<Map<String, String>> = _shaderDiagnostics.asStateFlow()

    @Volatile
    private var initializedDisk = false

    @Volatile
    private var glProgramGeneration = 1L

    fun currentGlGeneration(): Long = glProgramGeneration

    /**
     * Converts a JSON parameter key into its exact OpenGL uniform name (`"u" + CapitalizedKey`),
     * e.g., `"amplitude"` -> `"uAmplitude"`, `"glowColor"` -> `"uGlowColor"`, `"tileEdges"` -> `"uTileEdges"`.
     */
    fun toUniformName(key: String): String {
        val clean = key.trim().replace(Regex("[^a-zA-Z0-9_]"), "")
        if (clean.isEmpty()) return "uParam"
        return "u" + clean.replaceFirstChar { ch ->
            if (ch.isLowerCase()) ch.titlecase(Locale.US) else ch.toString()
        }
    }

    fun isSupportedPluginFileName(name: String?): Boolean {
        if (name.isNullOrBlank()) return false
        val lower = name.lowercase(Locale.US)
        return lower.endsWith(EXT_KFPACK) || lower.endsWith(EXT_FPPACK) || lower.endsWith(".zip")
    }

    fun normalizePluginCategory(raw: String): String {
        val trimmed = raw.trim()
        val exact = OFFICIAL_CATEGORIES.firstOrNull { it.equals(trimmed, ignoreCase = true) }
        if (exact != null) return exact
        val lower = trimmed.lowercase(Locale.US)
        return when {
            lower.contains("motion") || lower.contains("shake") || lower.contains("transform") -> "Gerak"
            lower.contains("color") || lower.contains("glow") || lower.contains("lut") -> "Warna"
            lower.contains("distort") || lower.contains("warp") || lower.contains("glitch") -> "Distorsi"
            lower.contains("styl") || lower.contains("edge") || lower.contains("comic") -> "Stilisasi"
            lower.contains("blur") -> "Blur"
            lower.contains("sim") || lower.contains("particle") -> "Simulasi"
            lower.contains("gen") -> "Generator"
            lower.contains("trans") || lower.contains("wipe") -> "Transisi"
            lower.contains("key") || lower.contains("chroma") || lower.contains("matte") -> "Keying"
            lower.contains("persp") || lower.contains("3d") -> "Perspective"
            else -> "Custom"
        }
    }

    fun shaderModeApproximationForCategory(category: String): Int = when (normalizePluginCategory(category)) {
        "Warna" -> 1
        "Distorsi" -> 3
        "Stilisasi" -> 8
        "Blur" -> 6
        "Simulasi", "Generator" -> 7
        "Transisi" -> 11
        "Keying" -> 12
        "Perspective" -> 5
        "Gerak" -> 10
        else -> 2
    }

    fun recordShaderDiagnostic(effectId: String, errorOrNull: String?) {
        val current = _shaderDiagnostics.value
        if (errorOrNull.isNullOrBlank()) {
            if (current.containsKey(effectId)) {
                _shaderDiagnostics.value = current - effectId
            }
        } else if (current[effectId] != errorOrNull) {
            _shaderDiagnostics.value = current + (effectId to errorOrNull)
        }
    }

    @Synchronized
    fun ensureInitialized(context: Context?) {
        if (initializedDisk) return
        if (context == null) {
            if (_installedSuites.value.isEmpty()) {
                _installedSuites.value = listOf(buildStarterKeyFlowSuite())
                EffectsCatalog.invalidatePluginCache()
                glProgramGeneration++
            }
            return
        }
        initializedDisk = true
        val rootDir = suitesRootDir(context)
        val markerFile = File(rootDir, ".starter_seeded_v1")
        val loaded = loadSuitesFromDisk(rootDir).toMutableList()
        if (!markerFile.exists() && loaded.none { it.id == STARTER_SUITE_ID }) {
            val starter = buildStarterKeyFlowSuite()
            saveSuiteToDisk(rootDir, starter)
            runCatching { markerFile.writeText("1") }
            loaded.add(0, starter)
        }
        _installedSuites.value = loaded
        EffectsCatalog.invalidatePluginCache()
        EffectThumbnailPreviewer.clearThumbnails()
        glProgramGeneration++
    }

    fun activePluginEffects(): List<InstalledPluginEffect> {
        if (_installedSuites.value.isEmpty() && !initializedDisk) {
            _installedSuites.value = listOf(buildStarterKeyFlowSuite())
        }
        return _installedSuites.value
            .filter { it.enabled }
            .flatMap { it.effects }
    }

    fun findPluginEffect(effectId: String): InstalledPluginEffect? {
        return activePluginEffects().firstOrNull { it.id == effectId }
            ?: _installedSuites.value.flatMap { it.effects }.firstOrNull { it.id == effectId }
    }

    fun isPluginEffect(effectId: String): Boolean = findPluginEffect(effectId) != null

    fun toEffectDefinition(plugin: InstalledPluginEffect): EffectDefinition {
        val numericParams = plugin.parameters.filter { it.type != PluginParamType.COLOR }
        val p1 = numericParams.getOrNull(0)?.defaultFloat ?: 0.65f
        val p2 = numericParams.getOrNull(1)?.defaultFloat ?: 0.50f
        val p3 = numericParams.getOrNull(2)?.defaultFloat ?: 0.35f
        val p4 = numericParams.getOrNull(3)?.defaultFloat ?: 0f
        val firstColor = plugin.parameters.firstOrNull { it.type == PluginParamType.COLOR }
        val summaryParts = buildList {
            add(plugin.category)
            if (plugin.isMultiPass) add("${plugin.passes.size}-Pass")
            if (plugin.expandBounds > 0.001f) add("+${(plugin.expandBounds * 100).toInt()}% Bounds")
            if (plugin.usesNativeMotionBlur) add("Motion Blur")
        }
        return EffectDefinition(
            id = plugin.id,
            name = plugin.name,
            category = EffectCategory.PLUGINS,
            shaderMode = 99,
            defaultIntensity = p1,
            defaultSecondary = p2,
            defaultFrequency = p3,
            defaultAngle = p4,
            defaultMix = 100f,
            defaultTintHex = firstColor?.defaultColorHex ?: "#FFFFFF",
            colorLabel = firstColor?.label ?: "",
            summary = summaryParts.joinToString(" • ")
        )
    }

    fun importPackFromUri(context: Context, uri: Uri): Result<InstalledPluginSuite> {
        ensureInitialized(context)
        val displayName = resolveDisplayName(context, uri)
        val ext = when {
            displayName?.endsWith(EXT_FPPACK, ignoreCase = true) == true -> EXT_FPPACK
            else -> EXT_KFPACK
        }
        return try {
            val stream = context.contentResolver.openInputStream(uri)
                ?: return Result.failure(IllegalArgumentException("Unable to open plugin file"))
            stream.use { input ->
                val parsed = parsePluginZipStream(input, archiveExtension = ext).getOrThrow()
                installSuiteInternal(context, parsed)
                Result.success(parsed)
            }
        } catch (t: Throwable) {
            Result.failure(t)
        }
    }

    fun importPackFromBytes(
        context: Context?,
        zipBytes: ByteArray,
        archiveExtension: String = EXT_KFPACK
    ): Result<InstalledPluginSuite> {
        if (context != null) ensureInitialized(context)
        return try {
            val parsed = parsePluginZipStream(ByteArrayInputStream(zipBytes), archiveExtension).getOrThrow()
            installSuiteInternal(context, parsed)
            Result.success(parsed)
        } catch (t: Throwable) {
            Result.failure(t)
        }
    }

    @Synchronized
    private fun installSuiteInternal(context: Context?, suite: InstalledPluginSuite) {
        if (context != null) {
            saveSuiteToDisk(suitesRootDir(context), suite)
        }
        val updated = _installedSuites.value.filterNot { it.id == suite.id }.toMutableList()
        updated.add(0, suite)
        _installedSuites.value = updated
        EffectsCatalog.invalidatePluginCache()
        EffectThumbnailPreviewer.clearThumbnails()
        glProgramGeneration++
    }

    @Synchronized
    fun toggleSuiteEnabled(context: Context?, suiteId: String, enabled: Boolean) {
        val updated = _installedSuites.value.map { suite ->
            if (suite.id == suiteId) {
                val toggled = suite.copy(enabled = enabled)
                if (context != null) saveSuiteToDisk(suitesRootDir(context), toggled)
                toggled
            } else suite
        }
        _installedSuites.value = updated
        EffectsCatalog.invalidatePluginCache()
        EffectThumbnailPreviewer.clearThumbnails()
        glProgramGeneration++
    }

    @Synchronized
    fun removeSuite(context: Context?, suiteId: String) {
        if (context != null) {
            val dir = File(suitesRootDir(context), sanitizeId(suiteId))
            if (dir.exists()) dir.deleteRecursively()
        }
        _installedSuites.value = _installedSuites.value.filterNot { it.id == suiteId }
        EffectsCatalog.invalidatePluginCache()
        EffectThumbnailPreviewer.clearThumbnails()
        glProgramGeneration++
    }

    @Synchronized
    fun restoreStarterPack(context: Context?): InstalledPluginSuite {
        val starter = buildStarterKeyFlowSuite()
        installSuiteInternal(context, starter)
        return starter
    }

    /**
     * Builds and installs a user-authored custom GLSL plugin suite directly inside the app.
     */
    fun createAndInstallCustomPlugin(
        context: Context?,
        effectName: String,
        suiteName: String,
        author: String,
        category: String,
        expandBounds: Float,
        usesNativeMotionBlur: Boolean,
        glslSource: String,
        parameters: List<PluginParamDef>,
        extension: String = EXT_KFPACK
    ): Result<InstalledPluginSuite> {
        return try {
            val cleanName = effectName.trim().ifBlank { "Custom Shader" }
            val effectId = sanitizeId(cleanName.lowercase(Locale.US).replace(Regex("[^a-z0-9]+"), "_"))
                .ifBlank { "custom_fx_${UUID.randomUUID().toString().take(6)}" }
            val suiteId = "${effectId}_suite"
            val normCat = normalizePluginCategory(category)
            val safeShader = glslSource.trim().ifBlank { DEFAULT_CUSTOM_GLSL_TEMPLATE }

            val effect = InstalledPluginEffect(
                id = effectId,
                suiteId = suiteId,
                suiteName = suiteName.ifBlank { "$cleanName Pack" },
                name = cleanName,
                category = normCat,
                author = author.ifBlank { "Creator" },
                version = "1.0.0",
                expandBounds = expandBounds.coerceIn(0f, 1.0f),
                usesNativeMotionBlur = usesNativeMotionBlur,
                passes = listOf(PluginShaderPass("shader.glsl", safeShader)),
                parameters = parameters
            )
            val suite = InstalledPluginSuite(
                id = suiteId,
                name = suiteName.ifBlank { "$cleanName Pack" },
                category = normCat,
                author = author.ifBlank { "Creator" },
                version = "1.0.0",
                minAppVersion = "1.4.9",
                description = "$cleanName ($normCat)",
                archiveExtension = if (extension == EXT_FPPACK) EXT_FPPACK else EXT_KFPACK,
                enabled = true,
                isBuiltInStarter = false,
                effects = listOf(effect)
            )
            installSuiteInternal(context, suite)
            Result.success(suite)
        } catch (t: Throwable) {
            Result.failure(t)
        }
    }

    /**
     * Packages an [InstalledPluginSuite] into a standard `.kfpack` / `.fppack` ZIP byte array
     * following the exact root `suite.json` + `effects/<id>/effect.json` + `shader.glsl` / `passes` layout.
     */
    fun exportSuiteAsZipBytes(suite: InstalledPluginSuite): ByteArray {
        val bos = ByteArrayOutputStream()
        ZipOutputStream(bos).use { zos ->
            val suiteJson = buildSuiteManifestJson(suite)
            zos.putNextEntry(ZipEntry("suite.json"))
            zos.write(suiteJson.toString(2).toByteArray(Charsets.UTF_8))
            zos.closeEntry()

            suite.effects.forEach { fx ->
                val folder = "effects/${sanitizeId(fx.id)}"
                val fxJson = buildEffectManifestJson(fx)
                zos.putNextEntry(ZipEntry("$folder/effect.json"))
                zos.write(fxJson.toString(2).toByteArray(Charsets.UTF_8))
                zos.closeEntry()

                fx.passes.forEach { pass ->
                    val passName = pass.fileName.substringAfterLast('/').ifBlank { "shader.glsl" }
                    zos.putNextEntry(ZipEntry("$folder/$passName"))
                    zos.write(pass.glslSource.toByteArray(Charsets.UTF_8))
                    zos.closeEntry()
                }
            }
        }
        return bos.toByteArray()
    }

    fun exportSuiteToUri(context: Context, suiteId: String, targetUri: Uri): Result<String> {
        val suite = _installedSuites.value.firstOrNull { it.id == suiteId }
            ?: return Result.failure(IllegalArgumentException("Suite not found"))
        return try {
            val bytes = exportSuiteAsZipBytes(suite)
            val out = context.contentResolver.openOutputStream(targetUri)
                ?: return Result.failure(IllegalStateException("Cannot write to selected file"))
            out.use { it.write(bytes) }
            Result.success("${suite.name}${suite.archiveExtension}")
        } catch (t: Throwable) {
            Result.failure(t)
        }
    }

    /**
     * Strips `/* ... */` block comments, `// ...` line comments, and trailing commas outside string literals
     * so hand-authored or AI-generated `suite.json` and `effect.json` parse without error.
     */
    fun stripJsonComments(raw: String): String {
        val sb = StringBuilder(raw.length)
        var i = 0
        var inString = false
        while (i < raw.length) {
            val c = raw[i]
            if (inString) {
                sb.append(c)
                if (c == '\\' && i + 1 < raw.length) {
                    sb.append(raw[i + 1])
                    i += 2
                    continue
                }
                if (c == '"') inString = false
                i++
            } else {
                if (c == '"') {
                    inString = true
                    sb.append(c)
                    i++
                } else if (c == '/' && i + 1 < raw.length && raw[i + 1] == '*') {
                    i += 2
                    while (i + 1 < raw.length && !(raw[i] == '*' && raw[i + 1] == '/')) {
                        i++
                    }
                    i += 2
                } else if (c == '/' && i + 1 < raw.length && raw[i + 1] == '/') {
                    i += 2
                    while (i < raw.length && raw[i] != '\n') {
                        i++
                    }
                } else {
                    sb.append(c)
                    i++
                }
            }
        }
        return sb.toString().replace(Regex(",\\s*([}\\]])"), "$1")
    }

    fun parsePluginZipStream(
        inputStream: InputStream,
        archiveExtension: String = EXT_KFPACK
    ): Result<InstalledPluginSuite> {
        return try {
            val entries = LinkedHashMap<String, ByteArray>()
            ZipInputStream(inputStream).use { zis ->
                var entry: ZipEntry? = zis.nextEntry
                var totalBytes = 0
                while (entry != null) {
                    if (!entry.isDirectory) {
                        val normPath = entry.name
                            .replace('\\', '/')
                            .trimStart('/')
                        if (!normPath.contains("..") && !normPath.startsWith("__MACOSX")) {
                            val buf = ByteArrayOutputStream()
                            val chunk = ByteArray(4096)
                            var r = zis.read(chunk)
                            while (r > 0) {
                                totalBytes += r
                                if (totalBytes > 16 * 1024 * 1024) {
                                    throw IllegalArgumentException("Plugin pack exceeds 16 MB limit")
                                }
                                buf.write(chunk, 0, r)
                                r = zis.read(chunk)
                            }
                            entries[normPath] = buf.toByteArray()
                        }
                    }
                    zis.closeEntry()
                    entry = zis.nextEntry
                }
            }

            if (entries.isEmpty()) {
                return Result.failure(IllegalArgumentException("Archive is empty or not a valid .kfpack/.fppack"))
            }

            // Locate suite.json at root or inside a single top-level wrapper folder
            val suiteEntryKey = entries.keys.firstOrNull { it == "suite.json" }
                ?: entries.keys.firstOrNull { it.endsWith("/suite.json") && it.count { ch -> ch == '/' } == 1 }

            if (suiteEntryKey != null) {
                val rootPrefix = suiteEntryKey.removeSuffix("suite.json")
                val suiteText = entries[suiteEntryKey]!!.toString(Charsets.UTF_8)
                val suiteObj = JSONObject(stripJsonComments(suiteText))

                val suiteId = sanitizeId(suiteObj.optString("id", "suite_${UUID.randomUUID().toString().take(6)}"))
                val suiteName = suiteObj.optString("name", suiteId)
                val suiteCategory = normalizePluginCategory(suiteObj.optString("category", "Custom"))
                val suiteAuthor = suiteObj.optString("author", "Creator")
                val suiteVersion = suiteObj.optString("version", "1.0.0")
                val minAppVer = suiteObj.optString("minAppVersion", "1.4.9")
                val description = suiteObj.optString("description", "")

                // Discover all effect.json files under effects/<effect_id>/effect.json (canonical KeyFlow layout)
                // with resilient fallback to <effect_id>/effect.json or root effect.json
                val canonicalEffectKeys = entries.keys.filter { key ->
                    key.startsWith("${rootPrefix}effects/") && key.endsWith("/effect.json")
                }.sorted()
                val effectJsonKeys = if (canonicalEffectKeys.isNotEmpty()) {
                    canonicalEffectKeys
                } else {
                    entries.keys.filter { key ->
                        key.startsWith(rootPrefix) && (key == "${rootPrefix}effect.json" || key.endsWith("/effect.json"))
                    }.sorted()
                }

                val parsedEffects = effectJsonKeys.mapNotNull { fxJsonKey ->
                    val fxDirPrefix = fxJsonKey.removeSuffix("effect.json")
                    val fxText = entries[fxJsonKey]!!.toString(Charsets.UTF_8)
                    parseSingleEffectFolder(
                        effectJsonText = fxText,
                        folderPrefix = fxDirPrefix,
                        entries = entries,
                        suiteId = suiteId,
                        suiteName = suiteName,
                        fallbackAuthor = suiteAuthor,
                        fallbackCategory = suiteCategory
                    )
                }

                if (parsedEffects.isEmpty()) {
                    return Result.failure(IllegalArgumentException("No valid effects/<id>/effect.json found in pack"))
                }

                return Result.success(
                    InstalledPluginSuite(
                        id = suiteId,
                        name = suiteName,
                        category = suiteCategory,
                        author = suiteAuthor,
                        version = suiteVersion,
                        minAppVersion = minAppVer,
                        description = description,
                        archiveExtension = archiveExtension,
                        enabled = true,
                        isBuiltInStarter = false,
                        effects = parsedEffects
                    )
                )
            }

            // Fallback: standalone single-effect archive with effect.json at root
            val standaloneFxKey = entries.keys.firstOrNull { it == "effect.json" || it.endsWith("/effect.json") }
                ?: return Result.failure(IllegalArgumentException("Missing suite.json in plugin archive"))
            val fxPrefix = standaloneFxKey.removeSuffix("effect.json")
            val fxObj = JSONObject(stripJsonComments(entries[standaloneFxKey]!!.toString(Charsets.UTF_8)))
            val fxId = sanitizeId(fxObj.optString("id", "custom_fx"))
            val fxName = fxObj.optString("name", fxId)
            val fxAuthor = fxObj.optString("author", "Creator")
            val fxCategory = normalizePluginCategory(fxObj.optString("category", "Custom"))
            val singleFx = parseSingleEffectFolder(
                effectJsonText = entries[standaloneFxKey]!!.toString(Charsets.UTF_8),
                folderPrefix = fxPrefix,
                entries = entries,
                suiteId = "${fxId}_pack",
                suiteName = fxName,
                fallbackAuthor = fxAuthor,
                fallbackCategory = fxCategory
            ) ?: return Result.failure(IllegalArgumentException("Missing shader.glsl in effect folder"))

            Result.success(
                InstalledPluginSuite(
                    id = "${fxId}_pack",
                    name = "$fxName Pack",
                    category = fxCategory,
                    author = fxAuthor,
                    version = singleFx.version,
                    archiveExtension = archiveExtension,
                    enabled = true,
                    effects = listOf(singleFx)
                )
            )
        } catch (t: Throwable) {
            Result.failure(t)
        }
    }

    private fun parseSingleEffectFolder(
        effectJsonText: String,
        folderPrefix: String,
        entries: Map<String, ByteArray>,
        suiteId: String,
        suiteName: String,
        fallbackAuthor: String,
        fallbackCategory: String
    ): InstalledPluginEffect? {
        val obj = JSONObject(stripJsonComments(effectJsonText))
        val folderName = folderPrefix.trimEnd('/').substringAfterLast('/')
        val rawId = obj.optString("id", folderName).ifBlank { folderName }
        val fxId = sanitizeId(rawId)
        val fxName = obj.optString("name", fxId)
        val fxCategory = normalizePluginCategory(obj.optString("category", fallbackCategory))
        val fxAuthor = obj.optString("author", fallbackAuthor)
        val fxVersion = obj.optString("version", "1.0.0")
        val expandBounds = obj.optDouble("expandBounds", 0.0).toFloat().coerceIn(0f, 1.2f)
        val usesMotionBlur = obj.optBoolean("usesNativeMotionBlur", false)

        val passesArr = obj.optJSONArray("passes")
        val passes = mutableListOf<PluginShaderPass>()
        if (passesArr != null && passesArr.length() > 0) {
            for (p in 0 until passesArr.length()) {
                val passFile = passesArr.optString(p).trim().trimStart('/')
                if (passFile.isNotEmpty()) {
                    val bytes = entries["$folderPrefix$passFile"]
                        ?: entries.entries.firstOrNull { it.key.endsWith("/$passFile") }?.value
                    if (bytes != null) {
                        passes.add(PluginShaderPass(passFile, bytes.toString(Charsets.UTF_8)))
                    }
                }
            }
        } else {
            val shaderFile = obj.optString("shader", "shader.glsl").trim().ifBlank { "shader.glsl" }
            val shaderBytes = entries["$folderPrefix$shaderFile"]
                ?: entries["${folderPrefix}shader.glsl"]
                ?: entries.entries.firstOrNull {
                    it.key.startsWith(folderPrefix) && it.key.endsWith(".glsl", ignoreCase = true)
                }?.value
            if (shaderBytes != null) {
                passes.add(PluginShaderPass(shaderFile, shaderBytes.toString(Charsets.UTF_8)))
            }
        }
        if (passes.isEmpty()) return null

        val paramsArr = obj.optJSONArray("parameters") ?: JSONArray()
        val params = buildList {
            for (i in 0 until paramsArr.length()) {
                val po = paramsArr.optJSONObject(i) ?: continue
                val key = po.optString("key", "").trim()
                if (key.isEmpty()) continue
                val label = po.optString("label", key)
                val typeStr = po.optString("type", "FLOAT").uppercase(Locale.US)
                val type = when (typeStr) {
                    "COLOR" -> PluginParamType.COLOR
                    "BOOL", "BOOLEAN" -> PluginParamType.BOOL
                    else -> PluginParamType.FLOAT
                }
                when (type) {
                    PluginParamType.COLOR -> {
                        val rawHex = po.optString("default", "#FFFFFF").trim()
                        val safeHex = if (rawHex.startsWith("#")) rawHex else "#$rawHex"
                        add(
                            PluginParamDef(
                                key = key,
                                label = label,
                                type = PluginParamType.COLOR,
                                defaultColorHex = safeHex
                            )
                        )
                    }
                    PluginParamType.BOOL -> {
                        val defVal = when (val d = po.opt("default")) {
                            is Boolean -> if (d) 1f else 0f
                            is Number -> if (d.toFloat() >= 0.5f) 1f else 0f
                            else -> if (po.optString("default", "0") == "true") 1f else 0f
                        }
                        add(
                            PluginParamDef(
                                key = key,
                                label = label,
                                type = PluginParamType.BOOL,
                                defaultFloat = defVal,
                                min = 0f,
                                max = 1f,
                                step = 1f
                            )
                        )
                    }
                    PluginParamType.FLOAT -> {
                        val minV = po.optDouble("min", 0.0).toFloat()
                        val maxV = po.optDouble("max", 1.0).toFloat().coerceAtLeast(minV + 0.0001f)
                        val defV = po.optDouble("default", ((minV + maxV) * 0.5f).toDouble()).toFloat()
                            .coerceIn(minV, maxV)
                        val stepV = po.optDouble("step", ((maxV - minV) / 100f).toDouble()).toFloat()
                            .coerceAtLeast(0.0001f)
                        add(
                            PluginParamDef(
                                key = key,
                                label = label,
                                type = PluginParamType.FLOAT,
                                defaultFloat = defV,
                                min = minV,
                                max = maxV,
                                step = stepV
                            )
                        )
                    }
                }
            }
        }

        return InstalledPluginEffect(
            id = fxId,
            suiteId = suiteId,
            suiteName = suiteName,
            name = fxName,
            category = fxCategory,
            author = fxAuthor,
            version = fxVersion,
            expandBounds = expandBounds,
            usesNativeMotionBlur = usesMotionBlur,
            passes = passes,
            parameters = params
        )
    }

    private fun suitesRootDir(context: Context): File =
        File(context.filesDir, "plugin_suites").apply { mkdirs() }

    private fun saveSuiteToDisk(rootDir: File, suite: InstalledPluginSuite) {
        val suiteDir = File(rootDir, sanitizeId(suite.id))
        if (suiteDir.exists()) suiteDir.deleteRecursively()
        suiteDir.mkdirs()

        val metaJson = buildSuiteManifestJson(suite).apply {
            put("_enabled", suite.enabled)
            put("_archiveExt", suite.archiveExtension)
            put("_isStarter", suite.isBuiltInStarter)
        }
        File(suiteDir, "suite.json").writeText(metaJson.toString(2), Charsets.UTF_8)

        suite.effects.forEach { fx ->
            val fxDir = File(suiteDir, "effects/${sanitizeId(fx.id)}").apply { mkdirs() }
            File(fxDir, "effect.json").writeText(buildEffectManifestJson(fx).toString(2), Charsets.UTF_8)
            fx.passes.forEach { pass ->
                val safeName = pass.fileName.substringAfterLast('/').ifBlank { "shader.glsl" }
                File(fxDir, safeName).writeText(pass.glslSource, Charsets.UTF_8)
            }
        }
    }

    private fun loadSuitesFromDisk(rootDir: File): List<InstalledPluginSuite> {
        val dirs = rootDir.listFiles()?.filter { it.isDirectory }.orEmpty()
        return dirs.mapNotNull { dir ->
            runCatching {
                val suiteFile = File(dir, "suite.json")
                if (!suiteFile.exists()) return@runCatching null
                val suiteObj = JSONObject(stripJsonComments(suiteFile.readText(Charsets.UTF_8)))
                val suiteId = suiteObj.optString("id", dir.name)
                val suiteName = suiteObj.optString("name", suiteId)
                val suiteCat = normalizePluginCategory(suiteObj.optString("category", "Custom"))
                val suiteAuthor = suiteObj.optString("author", "Creator")
                val enabled = suiteObj.optBoolean("_enabled", true)
                val ext = suiteObj.optString("_archiveExt", EXT_KFPACK)
                val isStarter = suiteObj.optBoolean("_isStarter", false)

                val effectsRoot = File(dir, "effects")
                val fxDirs = effectsRoot.listFiles()?.filter { it.isDirectory }?.sortedBy { it.name }.orEmpty()
                val fileMap = HashMap<String, ByteArray>()
                fxDirs.forEach { fxd ->
                    fxd.listFiles()?.filter { it.isFile }?.forEach { f ->
                        fileMap["effects/${fxd.name}/${f.name}"] = f.readBytes()
                    }
                }
                val effects = fxDirs.mapNotNull { fxd ->
                    val fxJsonBytes = fileMap["effects/${fxd.name}/effect.json"] ?: return@mapNotNull null
                    parseSingleEffectFolder(
                        effectJsonText = fxJsonBytes.toString(Charsets.UTF_8),
                        folderPrefix = "effects/${fxd.name}/",
                        entries = fileMap,
                        suiteId = suiteId,
                        suiteName = suiteName,
                        fallbackAuthor = suiteAuthor,
                        fallbackCategory = suiteCat
                    )
                }
                if (effects.isEmpty()) null
                else InstalledPluginSuite(
                    id = suiteId,
                    name = suiteName,
                    category = suiteCat,
                    author = suiteAuthor,
                    version = suiteObj.optString("version", "1.0.0"),
                    minAppVersion = suiteObj.optString("minAppVersion", "1.4.9"),
                    description = suiteObj.optString("description", ""),
                    archiveExtension = ext,
                    enabled = enabled,
                    isBuiltInStarter = isStarter,
                    effects = effects
                )
            }.getOrNull()
        }
    }

    private fun buildSuiteManifestJson(suite: InstalledPluginSuite): JSONObject {
        val fxSummary = JSONArray()
        suite.effects.forEach { fx ->
            fxSummary.put(
                JSONObject().apply {
                    put("id", fx.id)
                    put("name", fx.name)
                    put("category", fx.category)
                }
            )
        }
        return JSONObject().apply {
            put("id", suite.id)
            put("name", suite.name)
            put("category", suite.category)
            put("author", suite.author)
            put("version", suite.version)
            put("minAppVersion", suite.minAppVersion)
            put("description", suite.description)
            put("effects", fxSummary)
        }
    }

    private fun buildEffectManifestJson(fx: InstalledPluginEffect): JSONObject {
        val paramsArr = JSONArray()
        fx.parameters.forEach { p ->
            paramsArr.put(
                JSONObject().apply {
                    put("key", p.key)
                    put("label", p.label)
                    when (p.type) {
                        PluginParamType.COLOR -> {
                            put("type", "COLOR")
                            put("default", p.defaultColorHex)
                        }
                        PluginParamType.BOOL -> {
                            put("type", "BOOL")
                            put("default", if (p.defaultFloat >= 0.5f) 1.0 else 0.0)
                        }
                        PluginParamType.FLOAT -> {
                            put("default", p.defaultFloat.toDouble())
                            put("min", p.min.toDouble())
                            put("max", p.max.toDouble())
                            put("step", p.step.toDouble())
                        }
                    }
                }
            )
        }
        return JSONObject().apply {
            put("id", fx.id)
            put("name", fx.name)
            put("category", fx.category)
            put("author", fx.author)
            put("version", fx.version)
            if (fx.expandBounds > 0.001f) {
                put("expandBounds", fx.expandBounds.toDouble())
            }
            if (fx.usesNativeMotionBlur) {
                put("usesNativeMotionBlur", true)
            }
            if (fx.passes.size > 1) {
                val pArr = JSONArray()
                fx.passes.forEach { pArr.put(it.fileName) }
                put("passes", pArr)
            }
            put("parameters", paramsArr)
        }
    }

    private fun resolveDisplayName(context: Context, uri: Uri): String? {
        if (uri.scheme == "file") return uri.lastPathSegment
        return runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        }.getOrNull() ?: uri.lastPathSegment
    }

    private fun sanitizeId(raw: String): String =
        raw.trim().replace(Regex("[^a-zA-Z0-9_\\-]"), "_").ifBlank { "plugin" }

    fun buildAiPromptTemplate(
        packName: String = "Cinema Pro Suite",
        authorName: String = "Creator"
    ): String = """
I want to create a custom effect plugin pack (`.kfpack`) for FramePollution / KeyFlow (v1.4.9+).
Pack Name: "$packName" by "$authorName".

STRICT ARCHITECTURE RULES:
1. Root `suite.json`:
   `id`, `name`, `category` (`Gerak`, `Warna`, `Distorsi`, `Stilisasi`, `Blur`, `Simulasi`, `Generator`, `Transisi`, `Keying`, `Perspective`, `Custom`), `author`, `version`, `minAppVersion` ("1.4.9"), `description`, `effects`.
2. Each effect in `effects/<effect_id>/` has `effect.json` and `shader.glsl` (or `"passes": ["pass1.glsl", ...]`).
3. Optional 1.4.9+ `effect.json` flags:
   - `"expandBounds": 0.35` (expands bounding box so glows/rays/shadows don't clip)
   - `"usesNativeMotionBlur": true` (shutter motion blur integration)
4. Built-in OpenGL ES 2.0 Fragment Uniforms:
   - `precision mediump float;`
   - `uniform sampler2D uTexture;`
   - `uniform sampler2D uOriginalTexture;` (original pre-pass layer texture for multi-pass composites)
   - `varying vec2 vTex;` (`0.0..1.0`)
   - `uniform float uTime;`
   - `uniform vec2 uTexelSize;`
5. Parameter Uniform Rule: `"u" + CapitalizedKey`:
   - `FLOAT` -> `uniform float u<Key>;`
   - `BOOL` -> `uniform float u<Key>;` (`1.0` or `0.0`)
   - `COLOR` -> `uniform vec3 u<Key>;` (`#RRGGBB` -> `0.0..1.0`)

Output the exact file tree and complete JSON + GLSL files ready to zip at root as `${sanitizeId(packName)}.kfpack`.
    """.trimIndent()

    private const val STARTER_SUITE_ID = "keyflow_cinema_suite"

    const val DEFAULT_CUSTOM_GLSL_TEMPLATE = """precision mediump float;
uniform sampler2D uTexture;
varying vec2 vTex;
uniform float uTime;
uniform vec2 uTexelSize;

uniform float uAmplitude;
uniform float uFrequency;
uniform float uMirrorEdges;
uniform vec3 uTintColor;

vec2 mirrorUV(vec2 uv) {
    vec2 t = mod(uv, 2.0);
    if (t.x < 0.0) t.x += 2.0;
    if (t.y < 0.0) t.y += 2.0;
    return 1.0 - abs(t - 1.0);
}

void main() {
    float wave = sin(vTex.y * uFrequency * 12.0 + uTime * 4.0) * uAmplitude * 0.04;
    vec2 target = vTex + vec2(wave, 0.0);
    vec2 uv = (uMirrorEdges > 0.5) ? mirrorUV(target) : clamp(target, 0.0, 1.0);
    vec4 col = texture2D(uTexture, uv);
    gl_FragColor = vec4(col.rgb * uTintColor, col.a);
}"""

    private fun buildStarterKeyFlowSuite(): InstalledPluginSuite {
        val suiteId = STARTER_SUITE_ID
        val suiteName = "KeyFlow Cinema 1.4.9"
        val author = "KeyFlow Studio"

        // 1. Directional Shake Pro (20-tap stratified blue-noise temporal dither + mirror tile edges)
        val shakeGlsl = """
precision mediump float;
uniform sampler2D uTexture;
varying vec2 vTex;
uniform float uTime;
uniform vec2 uTexelSize;

uniform float uAmplitude;
uniform float uFrequency;
uniform float uRollAngle;
uniform float uMirrorEdges;
uniform float uMotionBlur;
uniform float uTrailLength;

vec2 mirrorUV(vec2 uv) {
    vec2 t = mod(uv, 2.0);
    if (t.x < 0.0) t.x += 2.0;
    if (t.y < 0.0) t.y += 2.0;
    return 1.0 - abs(t - 1.0);
}

vec4 sampleWithEdges(vec2 uv) {
    if (uMirrorEdges > 0.5) {
        return texture2D(uTexture, mirrorUV(uv));
    }
    if (uv.x < 0.0 || uv.x > 1.0 || uv.y < 0.0 || uv.y > 1.0) {
        return vec4(0.0);
    }
    return texture2D(uTexture, uv);
}

vec2 shakeOffsetAt(float t) {
    float w = t * uFrequency * 6.2831853;
    float sx = sin(w) * 0.65 + sin(w * 2.17 + 1.3) * 0.35;
    float sy = cos(w * 0.89 + 0.7) * 0.65 + cos(w * 2.43 + 2.1) * 0.35;
    float rad = uRollAngle * 0.01745329;
    float ca = cos(rad);
    float sa = sin(rad);
    vec2 dir = vec2(sx * ca - sy * sa, sx * sa + sy * ca);
    return dir * (uAmplitude * 0.0012);
}

void main() {
    float dt = 0.016;
    vec2 offCurr = shakeOffsetAt(uTime);
    vec2 offPrev = shakeOffsetAt(max(0.0, uTime - dt));
    vec2 trailVec = (offCurr - offPrev) * uTrailLength;

    if (uMotionBlur < 0.5 || length(trailVec) < 0.0004) {
        gl_FragColor = sampleWithEdges(vTex + offCurr);
        return;
    }

    float jitter = fract(52.9829189 * fract(dot(gl_FragCoord.xy, vec2(0.06711056, 0.00583715))));
    vec4 acc = vec4(0.0);
    float wSum = 0.0;
    for (int i = 0; i < 20; i++) {
        float t = ((float(i) + jitter) / 20.0) - 0.5;
        float w = exp(-4.0 * t * t);
        acc += sampleWithEdges(vTex + offCurr + trailVec * t) * w;
        wSum += w;
    }
    gl_FragColor = acc / max(wSum, 0.0001);
}
        """.trimIndent()

        val directionalShake = InstalledPluginEffect(
            id = "directional_shake",
            suiteId = suiteId,
            suiteName = suiteName,
            name = "Directional Shake Pro",
            category = "Gerak",
            author = author,
            version = "1.4.9",
            expandBounds = 0.12f,
            usesNativeMotionBlur = true,
            passes = listOf(PluginShaderPass("shader.glsl", shakeGlsl)),
            parameters = listOf(
                PluginParamDef("amplitude", "Amplitude", PluginParamType.FLOAT, 55f, min = 0f, max = 200f, step = 1f),
                PluginParamDef("frequency", "Frequency", PluginParamType.FLOAT, 7.5f, min = 0.5f, max = 25f, step = 0.5f),
                PluginParamDef("rollAngle", "Angle", PluginParamType.FLOAT, 18f, min = -180f, max = 180f, step = 1f),
                PluginParamDef("mirrorEdges", "Mirror Edges", PluginParamType.BOOL, 1f),
                PluginParamDef("motionBlur", "Shutter Trail", PluginParamType.BOOL, 1f),
                PluginParamDef("trailLength", "Trail Length", PluginParamType.FLOAT, 1.35f, min = 0f, max = 3.5f, step = 0.05f)
            )
        )

        // 2. S_UltraGlow (4-Pass Separable Threshold + H-Blur + V-Blur + Composite with expandBounds = 0.35!)
        val pass1Threshold = """
precision mediump float;
uniform sampler2D uTexture;
varying vec2 vTex;
uniform float uThreshold;

void main() {
    vec4 c = texture2D(uTexture, vTex);
    float luma = dot(c.rgb, vec3(0.299, 0.587, 0.114));
    float knee = smoothstep(uThreshold * 0.65, uThreshold + 0.22, luma) * c.a;
    gl_FragColor = vec4(c.rgb * knee, knee);
}
        """.trimIndent()

        val pass2BlurH = """
precision mediump float;
uniform sampler2D uTexture;
varying vec2 vTex;
uniform vec2 uTexelSize;
uniform float uRadius;

void main() {
    float stepX = uTexelSize.x * (1.0 + uRadius * 0.085);
    vec4 sum = texture2D(uTexture, vTex) * 0.227027;
    sum += texture2D(uTexture, clamp(vTex + vec2(stepX * 1.3846, 0.0), 0.0, 1.0)) * 0.316216;
    sum += texture2D(uTexture, clamp(vTex - vec2(stepX * 1.3846, 0.0), 0.0, 1.0)) * 0.316216;
    sum += texture2D(uTexture, clamp(vTex + vec2(stepX * 3.2307, 0.0), 0.0, 1.0)) * 0.070270;
    sum += texture2D(uTexture, clamp(vTex - vec2(stepX * 3.2307, 0.0), 0.0, 1.0)) * 0.070270;
    gl_FragColor = sum;
}
        """.trimIndent()

        val pass3BlurV = """
precision mediump float;
uniform sampler2D uTexture;
varying vec2 vTex;
uniform vec2 uTexelSize;
uniform float uRadius;

void main() {
    float stepY = uTexelSize.y * (1.0 + uRadius * 0.085);
    vec4 sum = texture2D(uTexture, vTex) * 0.227027;
    sum += texture2D(uTexture, clamp(vTex + vec2(0.0, stepY * 1.3846), 0.0, 1.0)) * 0.316216;
    sum += texture2D(uTexture, clamp(vTex - vec2(0.0, stepY * 1.3846), 0.0, 1.0)) * 0.316216;
    sum += texture2D(uTexture, clamp(vTex + vec2(0.0, stepY * 3.2307), 0.0, 1.0)) * 0.070270;
    sum += texture2D(uTexture, clamp(vTex - vec2(0.0, stepY * 3.2307), 0.0, 1.0)) * 0.070270;
    gl_FragColor = sum;
}
        """.trimIndent()

        val pass4Composite = """
precision mediump float;
uniform sampler2D uTexture;
uniform sampler2D uOriginalTexture;
varying vec2 vTex;
uniform float uBrightness;
uniform vec3 uGlowColor;
uniform float uEnableTint;

void main() {
    vec4 orig = texture2D(uOriginalTexture, vTex);
    vec4 blurred = texture2D(uTexture, vTex);
    vec3 tint = (uEnableTint > 0.5) ? uGlowColor : vec3(1.0);
    vec3 glow = blurred.rgb * tint * uBrightness;
    vec3 screenRgb = 1.0 - (1.0 - orig.rgb) * (1.0 - clamp(glow, 0.0, 1.0));
    vec3 combined = clamp(mix(screenRgb, orig.rgb + glow * 0.65, 0.45), 0.0, 1.0);
    float haloAlpha = clamp(max(glow.r, max(glow.g, glow.b)) * 0.88, 0.0, 1.0);
    float outAlpha = clamp(orig.a + haloAlpha * (1.0 - orig.a), 0.0, 1.0);
    gl_FragColor = vec4(combined, outAlpha);
}
        """.trimIndent()

        val ultraGlow = InstalledPluginEffect(
            id = "s_ultraglow",
            suiteId = suiteId,
            suiteName = suiteName,
            name = "S_UltraGlow",
            category = "Warna",
            author = author,
            version = "1.4.9",
            expandBounds = 0.35f,
            usesNativeMotionBlur = false,
            passes = listOf(
                PluginShaderPass("pass1_downsample.glsl", pass1Threshold),
                PluginShaderPass("pass2_blur_h.glsl", pass2BlurH),
                PluginShaderPass("pass3_blur_v.glsl", pass3BlurV),
                PluginShaderPass("pass4_composite.glsl", pass4Composite)
            ),
            parameters = listOf(
                PluginParamDef("brightness", "Brightness", PluginParamType.FLOAT, 1.85f, min = 0f, max = 5f, step = 0.05f),
                PluginParamDef("threshold", "Threshold", PluginParamType.FLOAT, 0.28f, min = 0f, max = 1f, step = 0.01f),
                PluginParamDef("radius", "Radius", PluginParamType.FLOAT, 64f, min = 0f, max = 200f, step = 1f),
                PluginParamDef("glowColor", "Glow Color", PluginParamType.COLOR, defaultColorHex = "#00E5FF"),
                PluginParamDef("enableTint", "Enable Tint", PluginParamType.BOOL, 1f)
            )
        )

        // 3. Prism Lens Warp (Barrel optical dispersion + RGB split)
        val prismWarpGlsl = """
precision mediump float;
uniform sampler2D uTexture;
varying vec2 vTex;
uniform float uCurvature;
uniform float uDispersion;
uniform float uAngle;
uniform vec3 uFringeColor;
uniform float uMirrorEdges;

vec2 mirrorUV(vec2 uv) {
    vec2 t = mod(uv, 2.0);
    if (t.x < 0.0) t.x += 2.0;
    if (t.y < 0.0) t.y += 2.0;
    return 1.0 - abs(t - 1.0);
}

vec4 fetchSafe(vec2 uv) {
    vec2 c = (uMirrorEdges > 0.5) ? mirrorUV(uv) : clamp(uv, 0.0, 1.0);
    return texture2D(uTexture, c);
}

void main() {
    vec2 d = vTex - 0.5;
    float r2 = dot(d, d);
    vec2 warped = 0.5 + d * (1.0 + uCurvature * r2);
    float rad = uAngle * 0.01745329;
    vec2 axis = vec2(cos(rad), sin(rad)) * (uDispersion * 0.035 * (0.35 + r2 * 2.2));
    vec4 cr = fetchSafe(warped + axis);
    vec4 cg = fetchSafe(warped);
    vec4 cb = fetchSafe(warped - axis);
    vec3 col = vec3(cr.r, cg.g, cb.b);
    vec3 fringe = abs(cr.rgb - cb.rgb) * uFringeColor * 0.65;
    float outA = max(cg.a, max(cr.a, cb.a));
    gl_FragColor = vec4(clamp(col + fringe, 0.0, 1.0), outA);
}
        """.trimIndent()

        val prismWarp = InstalledPluginEffect(
            id = "prism_lens_warp",
            suiteId = suiteId,
            suiteName = suiteName,
            name = "Prism Lens Warp",
            category = "Distorsi",
            author = author,
            version = "1.4.9",
            expandBounds = 0.15f,
            usesNativeMotionBlur = false,
            passes = listOf(PluginShaderPass("shader.glsl", prismWarpGlsl)),
            parameters = listOf(
                PluginParamDef("curvature", "Curvature", PluginParamType.FLOAT, 0.45f, min = -1.5f, max = 1.5f, step = 0.02f),
                PluginParamDef("dispersion", "Dispersion", PluginParamType.FLOAT, 0.65f, min = 0f, max = 2f, step = 0.02f),
                PluginParamDef("angle", "Split Angle", PluginParamType.FLOAT, 25f, min = -180f, max = 180f, step = 1f),
                PluginParamDef("fringeColor", "Fringe Tint", PluginParamType.COLOR, defaultColorHex = "#FF3366"),
                PluginParamDef("mirrorEdges", "Mirror Edges", PluginParamType.BOOL, 1f)
            )
        )

        // 4. Cyber Halftone (Rotated dot screen stylizer)
        val halftoneGlsl = """
precision mediump float;
uniform sampler2D uTexture;
varying vec2 vTex;
uniform vec2 uTexelSize;
uniform float uDotScale;
uniform float uContrast;
uniform vec3 uInkColor;
uniform float uInvertDots;

void main() {
    vec4 src = texture2D(uTexture, vTex);
    vec2 res = 1.0 / max(uTexelSize, vec2(0.0002));
    float angle = 0.523598;
    float ca = cos(angle);
    float sa = sin(angle);
    vec2 px = vTex * res;
    vec2 rotPx = vec2(px.x * ca - px.y * sa, px.x * sa + px.y * ca);
    float cell = max(3.0, uDotScale);
    vec2 grid = fract(rotPx / cell) - 0.5;
    float dist = length(grid) * 1.4142;
    float luma = dot(src.rgb, vec3(0.299, 0.587, 0.114));
    float radius = clamp(pow(luma, max(0.3, uContrast)), 0.05, 0.98);
    float dotMask = smoothstep(radius + 0.08, radius - 0.08, dist);
    if (uInvertDots > 0.5) dotMask = 1.0 - dotMask;
    vec3 outRgb = mix(src.rgb * 0.18, src.rgb * uInkColor * 1.2, dotMask);
    gl_FragColor = vec4(clamp(outRgb, 0.0, 1.0), src.a);
}
        """.trimIndent()

        val cyberHalftone = InstalledPluginEffect(
            id = "cyber_halftone",
            suiteId = suiteId,
            suiteName = suiteName,
            name = "Cyber Halftone",
            category = "Stilisasi",
            author = author,
            version = "1.4.9",
            expandBounds = 0f,
            usesNativeMotionBlur = false,
            passes = listOf(PluginShaderPass("shader.glsl", halftoneGlsl)),
            parameters = listOf(
                PluginParamDef("dotScale", "Dot Size", PluginParamType.FLOAT, 9f, min = 3f, max = 28f, step = 0.5f),
                PluginParamDef("contrast", "Contrast", PluginParamType.FLOAT, 0.9f, min = 0.3f, max = 2.5f, step = 0.05f),
                PluginParamDef("inkColor", "Ink Color", PluginParamType.COLOR, defaultColorHex = "#00D2A8"),
                PluginParamDef("invertDots", "Invert", PluginParamType.BOOL, 0f)
            )
        )

        return InstalledPluginSuite(
            id = suiteId,
            name = suiteName,
            category = "Custom",
            author = author,
            version = "1.4.9",
            minAppVersion = "1.4.9",
            description = "Multi-pass glow, shutter shake, prism warp & halftone",
            archiveExtension = EXT_KFPACK,
            enabled = true,
            isBuiltInStarter = true,
            effects = listOf(directionalShake, ultraGlow, prismWarp, cyberHalftone)
        )
    }
}
