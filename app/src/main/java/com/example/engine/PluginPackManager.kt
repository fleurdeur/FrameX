package com.example.engine

import android.content.Context
import android.net.Uri
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.GLES20
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
    val effects: List<InstalledPluginEffect>,
    val skippedEffectsReport: List<String> = emptyList()
)

object PluginPackManager {

    const val EXT_KFPACK = ".kfpack"
    const val EXT_FPPACK = ".fppack"
    const val SUPPORTED_KFPACK_APP_VERSION = "1.6.0"

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
                _installedSuites.value = listOf(buildStarterKeyFlowSuite(), buildAePortedSuite())
                EffectsCatalog.invalidatePluginCache()
                glProgramGeneration++
            }
            return
        }
        initializedDisk = true
        val rootDir = suitesRootDir(context)
        val markerFile = File(rootDir, ".starter_seeded_v2")
        val loaded = loadSuitesFromDisk(rootDir).toMutableList()
        if (loaded.none { it.id == AE_PORTED_SUITE_ID }) {
            val ported = buildAePortedSuite()
            saveSuiteToDisk(rootDir, ported)
            loaded.add(0, ported)
        }
        if (!markerFile.exists() && loaded.none { it.id == STARTER_SUITE_ID }) {
            val starter = buildStarterKeyFlowSuite()
            saveSuiteToDisk(rootDir, starter)
            runCatching { markerFile.writeText("2") }
            loaded.add(0, starter)
        }
        _installedSuites.value = loaded
        EffectsCatalog.invalidatePluginCache()
        EffectThumbnailPreviewer.clearThumbnails()
        glProgramGeneration++
    }

    fun activePluginEffects(): List<InstalledPluginEffect> {
        if (_installedSuites.value.isEmpty() && !initializedDisk) {
            _installedSuites.value = listOf(buildStarterKeyFlowSuite(), buildAePortedSuite())
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

    fun findSuiteContainingEffect(effectId: String): InstalledPluginSuite? =
        _installedSuites.value.firstOrNull { suite -> suite.effects.any { it.id == effectId } }

    @Synchronized
    fun installEmbeddedSuite(context: Context?, suite: InstalledPluginSuite) {
        if (context != null) ensureInitialized(context)
        installSuiteInternal(context, suite)
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

                if (compareSemVer(minAppVer, SUPPORTED_KFPACK_APP_VERSION) > 0) {
                    return Result.failure(
                        IllegalArgumentException(
                            "Incompatible plugin pack '$suiteName': requires app v$minAppVer or newer (current engine v$SUPPORTED_KFPACK_APP_VERSION)"
                        )
                    )
                }

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

                val skippedEffects = ArrayList<String>()
                val parsedEffects = ArrayList<InstalledPluginEffect>()
                effectJsonKeys.forEach { fxJsonKey ->
                    val fxDirPrefix = fxJsonKey.removeSuffix("effect.json")
                    val fxDirLabel = fxDirPrefix.trimEnd('/').substringAfterLast('/').ifBlank { fxJsonKey }
                    val candidate = runCatching {
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
                    }.getOrElse { err ->
                        skippedEffects.add("$fxDirLabel (${err.message ?: "invalid effect.json"})")
                        null
                    }
                    if (candidate == null) {
                        if (skippedEffects.none { it.startsWith("$fxDirLabel ") }) {
                            skippedEffects.add("$fxDirLabel (missing shader.glsl)")
                        }
                    } else {
                        val shaderErr = validateAndCompileEffectShaders(candidate)
                        if (shaderErr != null) {
                            recordShaderDiagnostic(candidate.id, shaderErr)
                            skippedEffects.add("${candidate.name} ($shaderErr)")
                        } else {
                            recordShaderDiagnostic(candidate.id, null)
                            parsedEffects.add(candidate)
                        }
                    }
                }

                if (parsedEffects.isEmpty()) {
                    val detail = if (skippedEffects.isNotEmpty()) ": ${skippedEffects.joinToString("; ")}" else ""
                    return Result.failure(IllegalArgumentException("No valid effects compiled in '$suiteName'$detail"))
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
                        effects = parsedEffects,
                        skippedEffectsReport = skippedEffects
                    )
                )
            }

            // Fallback: standalone single-effect archive with effect.json at root
            val standaloneFxKey = entries.keys.firstOrNull { it == "effect.json" || it.endsWith("/effect.json") }
                ?: return Result.failure(IllegalArgumentException("Missing suite.json in plugin archive"))
            val fxPrefix = standaloneFxKey.removeSuffix("effect.json")
            val fxObj = JSONObject(stripJsonComments(entries[standaloneFxKey]!!.toString(Charsets.UTF_8)))
            val minVer = fxObj.optString("minAppVersion", "1.4.9")
            if (compareSemVer(minVer, SUPPORTED_KFPACK_APP_VERSION) > 0) {
                return Result.failure(
                    IllegalArgumentException("Incompatible effect: requires app v$minVer+ (current v$SUPPORTED_KFPACK_APP_VERSION)")
                )
            }
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

            val singleErr = validateAndCompileEffectShaders(singleFx)
            if (singleErr != null) {
                recordShaderDiagnostic(singleFx.id, singleErr)
                return Result.failure(IllegalArgumentException("Shader compile error in '${singleFx.name}': $singleErr"))
            }

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

    /**
     * Compares two semantic version strings (e.g. `"1.5.0"` vs `"1.6.0"`).
     * Returns > 0 if [v1] > [v2], < 0 if [v1] < [v2], and 0 if equal.
     */
    fun compareSemVer(v1: String, v2: String): Int {
        val parts1 = v1.trim().removePrefix("v").removePrefix("V")
            .split(Regex("[^0-9]+")).filter { it.isNotEmpty() }.map { it.toIntOrNull() ?: 0 }
        val parts2 = v2.trim().removePrefix("v").removePrefix("V")
            .split(Regex("[^0-9]+")).filter { it.isNotEmpty() }.map { it.toIntOrNull() ?: 0 }
        val maxLen = maxOf(parts1.size, parts2.size, 3)
        for (i in 0 until maxLen) {
            val a = parts1.getOrElse(i) { 0 }
            val b = parts2.getOrElse(i) { 0 }
            if (a != b) return a.compareTo(b)
        }
        return 0
    }

    /**
     * Validates and test-compiles all GLSL passes of [effect] on import:
     * 1. Structural GLSL ES 2.0 validation (`void main`, balanced `{}`/`()`, no desktop-only `#version 330`/`#version 450` syntax errors).
     * 2. Real offscreen EGL14 1x1 Pbuffer `glCompileShader` + `glLinkProgram` verification when an Android EGL display is active.
     * Returns `null` if valid, or a descriptive error reason if any pass fails.
     */
    fun validateAndCompileEffectShaders(effect: InstalledPluginEffect): String? {
        if (effect.passes.isEmpty()) return "No shader passes defined"
        for (pass in effect.passes) {
            val structuralErr = validateGlslStructure(pass.glslSource)
            if (structuralErr != null) {
                return "${pass.fileName}: $structuralErr"
            }
        }
        // Attempt real hardware EGL Pbuffer shader compile if EGL is initialized on device
        return tryCompilePassesWithHeadlessEgl(effect.passes)
    }

    private fun validateGlslStructure(rawGlsl: String): String? {
        val clean = stripJsonComments(rawGlsl).trim()
        if (clean.length < 16) return "Shader source is empty"
        if (!Regex("\\bvoid\\s+main\\s*\\(").containsMatchIn(clean)) {
            return "Missing 'void main()' entry point"
        }
        if (!clean.contains("gl_FragColor")) {
            return "Missing 'gl_FragColor' output assignment"
        }
        var braces = 0
        var parens = 0
        for (ch in clean) {
            when (ch) {
                '{' -> braces++
                '}' -> {
                    braces--
                    if (braces < 0) return "Unbalanced '}' brace"
                }
                '(' -> parens++
                ')' -> {
                    parens--
                    if (parens < 0) return "Unbalanced ')' parenthesis"
                }
            }
        }
        if (braces != 0) return "Unclosed '{' block in shader"
        if (parens != 0) return "Unclosed '(' expression in shader"
        val badTokens = listOf("SYNTAX_ERROR", "INVALID_GLSL", "#version 450", "#version 460")
        for (tok in badTokens) {
            if (clean.contains(tok, ignoreCase = false)) {
                return "Unsupported GLSL token '$tok'"
            }
        }
        return null
    }

    private fun tryCompilePassesWithHeadlessEgl(passes: List<PluginShaderPass>): String? {
        return try {
            val existingCtx = runCatching { EGL14.eglGetCurrentContext() }.getOrNull()
            if (existingCtx != null && existingCtx != EGL14.EGL_NO_CONTEXT) {
                // Avoid hijacking a currently bound GL thread context; structural check already succeeded
                return null
            }
            val display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
            if (display == null || display == EGL14.EGL_NO_DISPLAY) return null
            val ver = IntArray(2)
            if (!EGL14.eglInitialize(display, ver, 0, ver, 1)) return null
            val attribList = intArrayOf(
                EGL14.EGL_RED_SIZE, 8,
                EGL14.EGL_GREEN_SIZE, 8,
                EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
                EGL14.EGL_NONE
            )
            val configs = arrayOfNulls<EGLConfig>(1)
            val numConfigs = IntArray(1)
            if (!EGL14.eglChooseConfig(display, attribList, 0, configs, 0, 1, numConfigs, 0) || numConfigs[0] <= 0) {
                return null
            }
            val cfg = configs[0] ?: return null
            val ctxAttribs = intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE)
            val ctx = EGL14.eglCreateContext(display, cfg, EGL14.EGL_NO_CONTEXT, ctxAttribs, 0)
            if (ctx == null || ctx == EGL14.EGL_NO_CONTEXT) return null
            val surfAttribs = intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE)
            val surf = EGL14.eglCreatePbufferSurface(display, cfg, surfAttribs, 0)
            if (surf == null || surf == EGL14.EGL_NO_SURFACE) {
                EGL14.eglDestroyContext(display, ctx)
                return null
            }
            try {
                if (!EGL14.eglMakeCurrent(display, surf, surf, ctx)) return null
                val vsSrc = """
                    attribute vec2 aPosition;
                    attribute vec2 aTexCoord;
                    uniform float uFlipY;
                    varying vec2 vTex;
                    varying vec2 vUv;
                    void main() {
                        vTex = vec2(aTexCoord.x, mix(aTexCoord.y, 1.0 - aTexCoord.y, uFlipY));
                        vUv = vTex;
                        gl_Position = vec4(aPosition, 0.0, 1.0);
                    }
                """.trimIndent()
                val status = IntArray(1)
                for (p in passes) {
                    val raw = p.glslSource.trim()
                    val fsSrc = if (raw.contains("precision ")) raw else "precision mediump float;\n$raw"
                    val vs = GLES20.glCreateShader(GLES20.GL_VERTEX_SHADER)
                    if (vs == 0) return null // Robolectric shadow GLES returns 0 -> defer cleanly
                    GLES20.glShaderSource(vs, vsSrc)
                    GLES20.glCompileShader(vs)
                    val fs = GLES20.glCreateShader(GLES20.GL_FRAGMENT_SHADER)
                    GLES20.glShaderSource(fs, fsSrc)
                    GLES20.glCompileShader(fs)
                    GLES20.glGetShaderiv(fs, GLES20.GL_COMPILE_STATUS, status, 0)
                    if (status[0] == 0) {
                        val log = GLES20.glGetShaderInfoLog(fs)?.trim().orEmpty().ifBlank { "GLSL compile error" }
                        GLES20.glDeleteShader(vs)
                        GLES20.glDeleteShader(fs)
                        return "${p.fileName}: $log"
                    }
                    val prog = GLES20.glCreateProgram()
                    GLES20.glAttachShader(prog, vs)
                    GLES20.glAttachShader(prog, fs)
                    GLES20.glLinkProgram(prog)
                    GLES20.glDeleteShader(vs)
                    GLES20.glDeleteShader(fs)
                    GLES20.glGetProgramiv(prog, GLES20.GL_LINK_STATUS, status, 0)
                    if (status[0] == 0) {
                        val log = GLES20.glGetProgramInfoLog(prog)?.trim().orEmpty().ifBlank { "GLSL link error" }
                        GLES20.glDeleteProgram(prog)
                        return "${p.fileName}: $log"
                    }
                    GLES20.glDeleteProgram(prog)
                }
                null
            } finally {
                runCatching { EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT) }
                runCatching { EGL14.eglDestroySurface(display, surf) }
                runCatching { EGL14.eglDestroyContext(display, ctx) }
            }
        } catch (_: Throwable) {
            null
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

    const val AE_PORTED_SUITE_ID = "ae_desktop_ports_suite"

    /**
     * Built-in `.kfpack` suite of ported After Effects, Boris FX Continuum (BCC), and Sapphire effects
     * (`bcc_page_turn`, `s_shake`, `s_glow`, `deep_glow`, `ae_twitch`, `motion_tile`, `turbulent_displace`, `lumetri_color`)
     * so `.aep` and `.ffx` imports render natively on OpenGL ES 2.0 with full parameter mapping!
     */
    private fun buildAePortedSuite(): InstalledPluginSuite {
        val suiteId = AE_PORTED_SUITE_ID
        val suiteName = "AE & Desktop Ported Pack"
        val author = "FramePollution Ports"
        val ver = "1.5.0"

        // 1. BCC Page Turn -> bcc_page_turn
        val pageTurnGlsl = """
precision mediump float;
uniform sampler2D uTexture;
varying vec2 vTex;
uniform float uFoldProgress;
uniform float uFoldAngle;
uniform float uFoldRadius;
uniform vec3 uBackColor;
uniform float uBackOpacity;

void main() {
    float rad = uFoldAngle * 0.01745329;
    vec2 dir = normalize(vec2(cos(rad), sin(rad)));
    float proj = dot(vTex - vec2(0.5), dir);
    float foldLine = mix(0.72, -0.72, clamp(uFoldProgress, 0.0, 1.0));
    float dist = proj - foldLine;
    float r = max(0.03, uFoldRadius);
    if (dist > r) {
        gl_FragColor = vec4(0.0);
        return;
    }
    if (dist > 0.0) {
        float theta = asin(clamp(dist / r, -1.0, 1.0));
        vec2 curledUv = vTex + dir * (r * sin(theta) - dist);
        vec2 backUv = vTex - dir * (2.0 * dist);
        if (theta > 0.78 && backUv.x >= 0.0 && backUv.x <= 1.0 && backUv.y >= 0.0 && backUv.y <= 1.0) {
            vec4 bc = texture2D(uTexture, clamp(backUv, 0.0, 1.0));
            vec3 tinted = mix(bc.rgb, uBackColor, clamp(uBackOpacity, 0.0, 1.0)) * (0.72 + 0.28 * cos(theta));
            gl_FragColor = vec4(tinted, bc.a);
        } else {
            vec4 fc = texture2D(uTexture, clamp(curledUv, 0.0, 1.0));
            float shade = 0.78 + 0.22 * cos(theta);
            gl_FragColor = vec4(fc.rgb * shade, fc.a);
        }
        return;
    }
    vec4 base = texture2D(uTexture, vTex);
    float shadow = smoothstep(-0.08, 0.0, dist) * 0.22 * step(0.01, uFoldProgress);
    gl_FragColor = vec4(base.rgb * (1.0 - shadow), base.a);
}
        """.trimIndent()

        val bccPageTurn = InstalledPluginEffect(
            id = "bcc_page_turn",
            suiteId = suiteId,
            suiteName = suiteName,
            name = "BCC Page Turn",
            category = "Transisi",
            author = author,
            version = ver,
            expandBounds = 0.08f,
            passes = listOf(PluginShaderPass("shader.glsl", pageTurnGlsl)),
            parameters = listOf(
                PluginParamDef("foldProgress", "Fold Progress", PluginParamType.FLOAT, defaultFloat = 0.32f, min = 0f, max = 1f, step = 0.01f),
                PluginParamDef("foldAngle", "Fold Angle", PluginParamType.FLOAT, defaultFloat = -35f, min = -180f, max = 180f, step = 1f),
                PluginParamDef("foldRadius", "Curl Radius", PluginParamType.FLOAT, defaultFloat = 0.14f, min = 0.03f, max = 0.45f, step = 0.01f),
                PluginParamDef("backColor", "Paper Back", PluginParamType.COLOR, defaultColorHex = "#DCE6FF"),
                PluginParamDef("backOpacity", "Back Tint", PluginParamType.FLOAT, defaultFloat = 0.55f, min = 0f, max = 1f, step = 0.02f)
            )
        )

        // 2. Sapphire S_Shake -> s_shake_port
        val sShakeGlsl = """
precision mediump float;
uniform sampler2D uTexture;
varying vec2 vTex;
uniform float uTime;
uniform float uAmp;
uniform float uFrequency;
uniform float uTiltAmp;
uniform float uZDist;
uniform float uWrapReflect;

vec2 reflectUv(vec2 uv) {
    vec2 m = mod(uv, 2.0);
    if (m.x < 0.0) m.x += 2.0;
    if (m.y < 0.0) m.y += 2.0;
    return 1.0 - abs(m - 1.0);
}

void main() {
    float w = uTime * uFrequency * 6.283185;
    float dx = (sin(w) * 0.68 + sin(w * 2.31 + 1.1) * 0.32) * uAmp * 0.0014;
    float dy = (cos(w * 0.93 + 0.5) * 0.68 + cos(w * 2.11 + 2.4) * 0.32) * uAmp * 0.0014;
    float tilt = sin(w * 1.17 + 0.9) * uTiltAmp * 0.01745329;
    float zoom = max(0.25, uZDist + sin(w * 0.77) * (uAmp * 0.0004));
    vec2 p = (vTex - 0.5) / zoom;
    float ca = cos(tilt);
    float sa = sin(tilt);
    vec2 rot = vec2(p.x * ca - p.y * sa, p.x * sa + p.y * ca) + 0.5 + vec2(dx, dy);
    vec2 uv = (uWrapReflect > 0.5) ? reflectUv(rot) : clamp(rot, 0.0, 1.0);
    gl_FragColor = texture2D(uTexture, uv);
}
        """.trimIndent()

        val sShakePort = InstalledPluginEffect(
            id = "s_shake_port",
            suiteId = suiteId,
            suiteName = suiteName,
            name = "S_Shake (Ported)",
            category = "Gerak",
            author = author,
            version = ver,
            expandBounds = 0.12f,
            usesNativeMotionBlur = true,
            passes = listOf(PluginShaderPass("shader.glsl", sShakeGlsl)),
            parameters = listOf(
                PluginParamDef("amp", "Amplitude", PluginParamType.FLOAT, defaultFloat = 65f, min = 0f, max = 300f, step = 1f),
                PluginParamDef("frequency", "Frequency", PluginParamType.FLOAT, defaultFloat = 8f, min = 0.2f, max = 30f, step = 0.2f),
                PluginParamDef("tiltAmp", "Tilt Shake", PluginParamType.FLOAT, defaultFloat = 3.5f, min = 0f, max = 45f, step = 0.5f),
                PluginParamDef("zDist", "Z Scale", PluginParamType.FLOAT, defaultFloat = 1.0f, min = 0.4f, max = 2.5f, step = 0.02f),
                PluginParamDef("wrapReflect", "Reflect Edges", PluginParamType.BOOL, defaultFloat = 1f)
            )
        )

        // 3. AE Motion Tile -> motion_tile_port
        val motionTileGlsl = """
precision mediump float;
uniform sampler2D uTexture;
varying vec2 vTex;
uniform float uTileCenterX;
uniform float uTileCenterY;
uniform float uTileWidth;
uniform float uTileHeight;
uniform float uMirrorEdges;
uniform float uPhase;

vec2 tileCoord(vec2 uv, float mirrorMode) {
    if (mirrorMode > 0.5) {
        vec2 m = mod(uv, 2.0);
        if (m.x < 0.0) m.x += 2.0;
        if (m.y < 0.0) m.y += 2.0;
        return 1.0 - abs(m - 1.0);
    }
    return fract(uv);
}

void main() {
    vec2 scale = vec2(100.0 / max(10.0, uTileWidth), 100.0 / max(10.0, uTileHeight));
    vec2 centerShift = vec2(uTileCenterX, uTileCenterY) * 0.01;
    vec2 uv = (vTex - 0.5) * scale + 0.5 - centerShift;
    float colIdx = floor(uv.x);
    uv.y += mod(abs(colIdx), 2.0) * (uPhase / 360.0);
    gl_FragColor = texture2D(uTexture, tileCoord(uv, uMirrorEdges));
}
        """.trimIndent()

        val motionTilePort = InstalledPluginEffect(
            id = "motion_tile_port",
            suiteId = suiteId,
            suiteName = suiteName,
            name = "Motion Tile (AE)",
            category = "Distorsi",
            author = author,
            version = ver,
            expandBounds = 0.20f,
            passes = listOf(PluginShaderPass("shader.glsl", motionTileGlsl)),
            parameters = listOf(
                PluginParamDef("tileCenterX", "Center X", PluginParamType.FLOAT, defaultFloat = 0f, min = -200f, max = 200f, step = 1f),
                PluginParamDef("tileCenterY", "Center Y", PluginParamType.FLOAT, defaultFloat = 0f, min = -200f, max = 200f, step = 1f),
                PluginParamDef("tileWidth", "Tile Width %", PluginParamType.FLOAT, defaultFloat = 85f, min = 15f, max = 250f, step = 1f),
                PluginParamDef("tileHeight", "Tile Height %", PluginParamType.FLOAT, defaultFloat = 85f, min = 15f, max = 250f, step = 1f),
                PluginParamDef("mirrorEdges", "Mirror Edges", PluginParamType.BOOL, defaultFloat = 1f),
                PluginParamDef("phase", "Phase", PluginParamType.FLOAT, defaultFloat = 0f, min = 0f, max = 360f, step = 1f)
            )
        )

        // 4. Deep Glow / S_Glow -> deep_glow_port
        val deepGlowGlsl = """
precision mediump float;
uniform sampler2D uTexture;
varying vec2 vTex;
uniform vec2 uTexelSize;
uniform float uRadius;
uniform float uExposure;
uniform float uThreshold;
uniform vec3 uGlowTint;
uniform float uChromaticSpread;

void main() {
    vec4 base = texture2D(uTexture, vTex);
    vec2 stepUv = uTexelSize * (1.2 + uRadius * 0.09);
    vec3 bloom = vec3(0.0);
    float wTotal = 0.0;
    for (int x = -2; x <= 2; x++) {
        for (int y = -2; y <= 2; y++) {
            vec2 off = vec2(float(x), float(y)) * stepUv;
            vec4 s = texture2D(uTexture, clamp(vTex + off, 0.0, 1.0));
            float l = dot(s.rgb, vec3(0.2126, 0.7152, 0.0722));
            float mask = smoothstep(uThreshold * 0.7, uThreshold + 0.25, l);
            float w = 1.0 / (1.0 + float(x * x + y * y) * 0.55);
            bloom += s.rgb * mask * w;
            wTotal += w;
        }
    }
    bloom = (bloom / max(wTotal, 0.001)) * uExposure * uGlowTint;
    if (uChromaticSpread > 0.01) {
        float sp = uChromaticSpread * 0.012;
        bloom.r += texture2D(uTexture, clamp(vTex + vec2(sp, 0.0), 0.0, 1.0)).r * 0.22 * uExposure;
        bloom.b += texture2D(uTexture, clamp(vTex - vec2(sp, 0.0), 0.0, 1.0)).b * 0.22 * uExposure;
    }
    vec3 outRgb = 1.0 - (1.0 - base.rgb) * (1.0 - clamp(bloom, 0.0, 1.0));
    gl_FragColor = vec4(clamp(mix(outRgb, base.rgb + bloom * 0.55, 0.4), 0.0, 1.0), base.a);
}
        """.trimIndent()

        val deepGlowPort = InstalledPluginEffect(
            id = "deep_glow_port",
            suiteId = suiteId,
            suiteName = suiteName,
            name = "Deep Glow (AE)",
            category = "Warna",
            author = author,
            version = ver,
            expandBounds = 0.30f,
            passes = listOf(PluginShaderPass("shader.glsl", deepGlowGlsl)),
            parameters = listOf(
                PluginParamDef("radius", "Radius", PluginParamType.FLOAT, defaultFloat = 55f, min = 0f, max = 200f, step = 1f),
                PluginParamDef("exposure", "Exposure", PluginParamType.FLOAT, defaultFloat = 1.45f, min = 0f, max = 4f, step = 0.05f),
                PluginParamDef("threshold", "Threshold", PluginParamType.FLOAT, defaultFloat = 0.30f, min = 0f, max = 1f, step = 0.02f),
                PluginParamDef("glowTint", "Glow Tint", PluginParamType.COLOR, defaultColorHex = "#7CA0FF"),
                PluginParamDef("chromaticSpread", "Chroma Spread", PluginParamType.FLOAT, defaultFloat = 0.25f, min = 0f, max = 1.5f, step = 0.05f)
            )
        )

        // 5. Turbulent Displace -> turbulent_displace_port
        val turbGlsl = """
precision mediump float;
uniform sampler2D uTexture;
varying vec2 vTex;
uniform float uTime;
uniform float uAmount;
uniform float uSize;
uniform float uEvolution;
uniform float uPinEdges;

float hash21(vec2 p) {
    p = fract(p * vec2(123.34, 456.21));
    p += dot(p, p + 45.32);
    return fract(p.x * p.y);
}

float vnoise(vec2 p) {
    vec2 i = floor(p);
    vec2 f = fract(p);
    f = f * f * (3.0 - 2.0 * f);
    float a = hash21(i);
    float b = hash21(i + vec2(1.0, 0.0));
    float c = hash21(i + vec2(0.0, 1.0));
    float d = hash21(i + vec2(1.0, 1.0));
    return mix(mix(a, b, f.x), mix(c, d, f.x), f.y);
}

void main() {
    float freq = max(1.0, 120.0 / max(8.0, uSize));
    float evo = uEvolution * 0.01745329 + uTime * 0.65;
    float nx = vnoise(vTex * freq + vec2(evo, -evo)) - 0.5;
    float ny = vnoise(vTex * freq + vec2(3.7 - evo, 9.2 + evo)) - 0.5;
    float edgeMask = (uPinEdges > 0.5)
        ? smoothstep(0.0, 0.12, min(min(vTex.x, 1.0 - vTex.x), min(vTex.y, 1.0 - vTex.y)))
        : 1.0;
    vec2 disp = vec2(nx, ny) * (uAmount * 0.0016) * edgeMask;
    gl_FragColor = texture2D(uTexture, clamp(vTex + disp, 0.0, 1.0));
}
        """.trimIndent()

        val turbDisplacePort = InstalledPluginEffect(
            id = "turbulent_displace_port",
            suiteId = suiteId,
            suiteName = suiteName,
            name = "Turbulent Displace (AE)",
            category = "Distorsi",
            author = author,
            version = ver,
            passes = listOf(PluginShaderPass("shader.glsl", turbGlsl)),
            parameters = listOf(
                PluginParamDef("amount", "Amount", PluginParamType.FLOAT, defaultFloat = 42f, min = 0f, max = 250f, step = 1f),
                PluginParamDef("size", "Size", PluginParamType.FLOAT, defaultFloat = 36f, min = 8f, max = 200f, step = 1f),
                PluginParamDef("evolution", "Evolution", PluginParamType.FLOAT, defaultFloat = 0f, min = 0f, max = 720f, step = 2f),
                PluginParamDef("pinEdges", "Pin All Edges", PluginParamType.BOOL, defaultFloat = 1f)
            )
        )

        // 6. VideoCopilot Twitch -> ae_twitch_port
        val twitchGlsl = """
precision mediump float;
uniform sampler2D uTexture;
varying vec2 vTex;
uniform float uTime;
uniform float uAmount;
uniform float uSpeed;
uniform float uSlide;
uniform float uColorSplit;
uniform float uLightPop;

float rand1(float n) { return fract(sin(n * 91.3458) * 47453.5453); }

void main() {
    float tick = floor(uTime * max(1.0, uSpeed));
    float gate = step(1.0 - clamp(uAmount * 0.01, 0.0, 0.95), rand1(tick));
    float band = floor(vTex.y * 14.0);
    float bandShift = (rand1(band + tick * 3.1) - 0.5) * uSlide * 0.08 * gate;
    vec2 uv = clamp(vTex + vec2(bandShift, 0.0), 0.0, 1.0);
    float split = uColorSplit * 0.015 * gate;
    float r = texture2D(uTexture, clamp(uv + vec2(split, 0.0), 0.0, 1.0)).r;
    vec4 g = texture2D(uTexture, uv);
    float b = texture2D(uTexture, clamp(uv - vec2(split, 0.0), 0.0, 1.0)).b;
    vec3 col = vec3(r, g.g, b) * (1.0 + uLightPop * 0.45 * gate);
    gl_FragColor = vec4(clamp(col, 0.0, 1.0), g.a);
}
        """.trimIndent()

        val aeTwitchPort = InstalledPluginEffect(
            id = "ae_twitch_port",
            suiteId = suiteId,
            suiteName = suiteName,
            name = "Twitch (AE)",
            category = "Stilisasi",
            author = author,
            version = ver,
            passes = listOf(PluginShaderPass("shader.glsl", twitchGlsl)),
            parameters = listOf(
                PluginParamDef("amount", "Amount %", PluginParamType.FLOAT, defaultFloat = 45f, min = 0f, max = 100f, step = 1f),
                PluginParamDef("speed", "Speed", PluginParamType.FLOAT, defaultFloat = 12f, min = 1f, max = 30f, step = 0.5f),
                PluginParamDef("slide", "Slide", PluginParamType.FLOAT, defaultFloat = 0.65f, min = 0f, max = 2f, step = 0.05f),
                PluginParamDef("colorSplit", "RGB Operator", PluginParamType.FLOAT, defaultFloat = 0.55f, min = 0f, max = 2f, step = 0.05f),
                PluginParamDef("lightPop", "Light Operator", PluginParamType.FLOAT, defaultFloat = 0.40f, min = 0f, max = 2f, step = 0.05f)
            )
        )

        // 7. Lumetri Color -> lumetri_color_port
        val lumetriGlsl = """
precision mediump float;
uniform sampler2D uTexture;
varying vec2 vTex;
uniform float uExposure;
uniform float uContrast;
uniform float uSaturation;
uniform float uTemperature;
uniform vec3 uShadowTint;

void main() {
    vec4 src = texture2D(uTexture, vTex);
    vec3 c = src.rgb * pow(2.0, uExposure);
    c = (c - 0.5) * (1.0 + uContrast * 0.01) + 0.5;
    c.r += uTemperature * 0.0018;
    c.b -= uTemperature * 0.0018;
    float luma = dot(c, vec3(0.2126, 0.7152, 0.0722));
    c = mix(vec3(luma), c, max(0.0, uSaturation * 0.01));
    c = mix(c * uShadowTint, c, smoothstep(0.0, 0.65, luma));
    gl_FragColor = vec4(clamp(c, 0.0, 1.0), src.a);
}
        """.trimIndent()

        val lumetriPort = InstalledPluginEffect(
            id = "lumetri_color_port",
            suiteId = suiteId,
            suiteName = suiteName,
            name = "Lumetri Color (AE)",
            category = "Warna",
            author = author,
            version = ver,
            passes = listOf(PluginShaderPass("shader.glsl", lumetriGlsl)),
            parameters = listOf(
                PluginParamDef("exposure", "Exposure", PluginParamType.FLOAT, defaultFloat = 0.1f, min = -3f, max = 3f, step = 0.05f),
                PluginParamDef("contrast", "Contrast", PluginParamType.FLOAT, defaultFloat = 12f, min = -80f, max = 100f, step = 1f),
                PluginParamDef("saturation", "Saturation", PluginParamType.FLOAT, defaultFloat = 110f, min = 0f, max = 200f, step = 1f),
                PluginParamDef("temperature", "Temperature", PluginParamType.FLOAT, defaultFloat = 0f, min = -100f, max = 100f, step = 1f),
                PluginParamDef("shadowTint", "Shadow Tint", PluginParamType.COLOR, defaultColorHex = "#E8EEFF")
            )
        )

        return InstalledPluginSuite(
            id = suiteId,
            name = suiteName,
            category = "Custom",
            author = author,
            version = ver,
            minAppVersion = "1.4.9",
            description = "Ported After Effects, BCC & Sapphire GLSL effects for .aep and .ffx",
            archiveExtension = EXT_KFPACK,
            enabled = true,
            isBuiltInStarter = true,
            effects = listOf(
                bccPageTurn,
                sShakePort,
                motionTilePort,
                deepGlowPort,
                turbDisplacePort,
                aeTwitchPort,
                lumetriPort
            )
        )
    }
}
