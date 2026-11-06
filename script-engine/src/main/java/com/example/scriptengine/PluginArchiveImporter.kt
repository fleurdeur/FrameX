package com.example.scriptengine

import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.Locale
import java.util.zip.ZipInputStream

class PasswordRequiredException(
    val archiveName: String = "",
    override val message: String = "Password required for encrypted archive: $archiveName"
) : IllegalArgumentException(message)

object PluginArchiveImporter {

    const val DEFAULT_MAX_PLUGIN_BYTES: Long = 64L * 1024L * 1024L // 64 MB per plugin quota
    private const val MAX_ZIP_ENTRIES = 2_500

    private val KNOWN_MANIFEST_ATTRS = setOf(
        "Version",
        "ExtensionBundleId",
        "ExtensionBundleVersion",
        "ExtensionBundleName",
        "xmlns:xsi",
        "xsi:noNamespaceSchemaLocation"
    )

    fun importPluginFile(
        sourceFile: File,
        appFilesDir: File,
        quotaBytes: Long = DEFAULT_MAX_PLUGIN_BYTES,
        password: String? = null
    ): Result<InstalledPlugin> {
        if (!sourceFile.exists() || !sourceFile.isFile) {
            return Result.failure(IllegalArgumentException("Plugin file not found: ${sourceFile.name}"))
        }
        return sourceFile.inputStream().buffered().use { stream ->
            importPluginStream(
                inputStream = stream,
                originalFileName = sourceFile.name,
                appFilesDir = appFilesDir,
                quotaBytes = quotaBytes,
                password = password
            )
        }
    }

    fun importPluginStream(
        inputStream: InputStream,
        originalFileName: String,
        appFilesDir: File,
        quotaBytes: Long = DEFAULT_MAX_PLUGIN_BYTES,
        password: String? = null
    ): Result<InstalledPlugin> = runCatching {
        val cleanName = originalFileName.substringAfterLast('/').substringAfterLast('\\').trim()
            .ifBlank { "plugin.zip" }
        val lower = cleanName.lowercase(Locale.US)
        val stagingRoot = File(appFilesDir, "plugin-staging/stg_${System.nanoTime()}").apply { mkdirs() }

        try {
            when {
                lower.endsWith(".zip") || lower.endsWith(".zxp") -> {
                    extractZipSafely(inputStream, stagingRoot, quotaBytes, password, cleanName)
                }
                lower.endsWith(".jsx") || lower.endsWith(".jsxbin") -> {
                    val outFile = File(stagingRoot, sanitizeFileName(cleanName))
                    copyBounded(inputStream, outFile, quotaBytes)
                }
                else -> {
                    throw IllegalArgumentException(
                        "Unsupported plugin format '$cleanName'. Expected .zip, .zxp, .jsx, or .jsxbin."
                    )
                }
            }

            val signatureStatus = detectSignatureStatus(stagingRoot)
            val manifestHit = findFileUpToDepth(stagingRoot, maxDirDepth = 2) { relPath, file ->
                file.name.equals("manifest.xml", ignoreCase = true) &&
                    relPath.replace('\\', '/').lowercase(Locale.US).let {
                        it == "csxs/manifest.xml" || it.endsWith("/csxs/manifest.xml")
                    }
            }

            val parsedDraft = if (manifestHit != null) {
                parseCepBundle(stagingRoot, manifestHit.second, signatureStatus)
            } else {
                parseScriptBundle(stagingRoot, cleanName, signatureStatus)
            }

            // Relocate from stagingRoot into canonical files/plugins/<bundleId>/<version>/
            val safeBundleDir = sanitizeSegment(parsedDraft.bundleId)
            val safeVersionDir = sanitizeSegment(parsedDraft.version.ifBlank { "1.0.0" })
            val finalInstallDir = File(appFilesDir, "plugins/$safeBundleDir/$safeVersionDir")
            if (finalInstallDir.exists()) {
                finalInstallDir.deleteRecursively()
            }
            finalInstallDir.parentFile?.mkdirs()
            val bundleBaseDir = parsedDraft.bundleRootInStaging
            copyDirectoryBounded(bundleBaseDir, finalInstallDir, quotaBytes)

            val dataDir = File(appFilesDir, "plugin-data/$safeBundleDir").apply { mkdirs() }
            val analyzedReport = analyzeInstalledDirectory(
                pluginDir = finalInstallDir,
                pluginName = parsedDraft.plugin.name,
                bundleId = parsedDraft.plugin.bundleId,
                version = parsedDraft.plugin.version,
                pluginType = parsedDraft.plugin.type,
                csxsVersion = parsedDraft.plugin.csxsVersion,
                hostScriptRelativePath = parsedDraft.plugin.hostScriptRelativePath
            )

            parsedDraft.plugin.copy(
                installDirPath = finalInstallDir.canonicalPath,
                dataDirPath = dataDir.canonicalPath,
                report = analyzedReport
            )
        } finally {
            runCatching { stagingRoot.deleteRecursively() }
        }
    }

    private data class DraftPlugin(
        val bundleRootInStaging: File,
        val plugin: InstalledPlugin,
        val bundleId: String = plugin.bundleId,
        val version: String = plugin.version
    )

    private fun parseCepBundle(
        stagingRoot: File,
        manifestFile: File,
        signatureStatus: PluginSignatureStatus
    ): DraftPlugin {
        // manifestFile is <bundleRoot>/CSXS/manifest.xml
        val bundleRoot = manifestFile.parentFile?.parentFile ?: stagingRoot
        val manifestBytes = manifestFile.readBytes()
        val factory = XmlPullParserFactory.newInstance().apply { isNamespaceAware = false }
        val parser = factory.newPullParser()
        parser.setInput(ByteArrayInputStream(manifestBytes), "UTF-8")

        var bundleId = ""
        var bundleVersion = "1.0.0"
        var bundleName = ""
        var extensionId = ""
        var extensionVersion = ""
        var csxsVersion = ""
        var hostAeftRange = ""
        var mainPath = ""
        var scriptPath = ""
        var uiType = "Panel"
        var menuName = ""
        var defW = 280
        var defH = 460
        var minW = 220
        var minH = 320
        var maxW = 640
        var maxH = 820
        val cefParams = ArrayList<String>()
        val rawAttributes = LinkedHashMap<String, String>()

        val tagStack = ArrayList<String>()
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> {
                    val tag = parser.name.orEmpty()
                    tagStack.add(tag)
                    when (tag) {
                        "ExtensionManifest" -> {
                            for (i in 0 until parser.attributeCount) {
                                val attrName = parser.getAttributeName(i).orEmpty()
                                val attrVal = parser.getAttributeValue(i).orEmpty()
                                when (attrName) {
                                    "ExtensionBundleId" -> bundleId = attrVal
                                    "ExtensionBundleVersion" -> if (attrVal.isNotBlank()) bundleVersion = attrVal
                                    "ExtensionBundleName" -> bundleName = attrVal
                                    else -> {
                                        if (attrName !in KNOWN_MANIFEST_ATTRS) {
                                            rawAttributes[attrName] = attrVal
                                        } else if (attrName == "Version") {
                                            rawAttributes["ManifestVersion"] = attrVal
                                        }
                                    }
                                }
                            }
                        }
                        "Extension" -> {
                            val id = parser.getAttributeValue(null, "Id").orEmpty()
                            val ver = parser.getAttributeValue(null, "Version").orEmpty()
                            if (extensionId.isBlank() && id.isNotBlank()) extensionId = id
                            if (extensionVersion.isBlank() && ver.isNotBlank()) extensionVersion = ver
                        }
                        "Host" -> {
                            val hostName = parser.getAttributeValue(null, "Name").orEmpty()
                            val hostVer = parser.getAttributeValue(null, "Version").orEmpty()
                            if (hostName.equals("AEFT", ignoreCase = true)) {
                                hostAeftRange = hostVer
                            } else if (hostName.isNotBlank()) {
                                rawAttributes["Host.$hostName"] = hostVer
                            }
                        }
                        "RequiredRuntime" -> {
                            val rtName = parser.getAttributeValue(null, "Name").orEmpty()
                            val rtVer = parser.getAttributeValue(null, "Version").orEmpty()
                            if (rtName.equals("CSXS", ignoreCase = true)) {
                                csxsVersion = rtVer
                            }
                        }
                    }
                }
                XmlPullParser.TEXT -> {
                    val txt = parser.text?.trim().orEmpty()
                    if (txt.isNotEmpty() && tagStack.isNotEmpty()) {
                        val leaf = tagStack.last()
                        val parent = tagStack.getOrNull(tagStack.size - 2).orEmpty()
                        when (leaf) {
                            "MainPath" -> if (mainPath.isBlank()) mainPath = normalizeRelPath(txt)
                            "ScriptPath" -> if (scriptPath.isBlank()) scriptPath = normalizeRelPath(txt)
                            "Parameter" -> if (parent == "CEFCommandLine") cefParams.add(txt)
                            "Type" -> if (parent == "UI") uiType = txt
                            "Menu" -> if (parent == "UI" && menuName.isBlank()) menuName = txt
                            "Width" -> {
                                val v = txt.toIntOrNull()
                                if (v != null) {
                                    when (parent) {
                                        "Size" -> defW = v
                                        "MinSize" -> minW = v
                                        "MaxSize" -> maxW = v
                                    }
                                }
                            }
                            "Height" -> {
                                val v = txt.toIntOrNull()
                                if (v != null) {
                                    when (parent) {
                                        "Size" -> defH = v
                                        "MinSize" -> minH = v
                                        "MaxSize" -> maxH = v
                                    }
                                }
                            }
                        }
                    }
                }
                XmlPullParser.END_TAG -> {
                    if (tagStack.isNotEmpty()) tagStack.removeAt(tagStack.lastIndex)
                }
            }
            event = parser.next()
        }

        val resolvedBundleId = bundleId.ifBlank { extensionId }.ifBlank {
            "com.cep.${sanitizeSegment(bundleRoot.name.ifBlank { "panel" })}"
        }
        val resolvedVersion = bundleVersion.ifBlank { extensionVersion }.ifBlank { "1.0.0" }
        val resolvedName = menuName.ifBlank { bundleName }.ifBlank {
            resolvedBundleId.substringAfterLast('.')
        }
        val readme = findReadmeText(bundleRoot)

        val plugin = InstalledPlugin(
            bundleId = resolvedBundleId,
            name = resolvedName,
            version = resolvedVersion,
            type = PluginType.CEP_PANEL,
            author = extractAuthorFromReadme(readme),
            menuName = menuName.ifBlank { resolvedName },
            entryHtmlRelativePath = mainPath,
            hostScriptRelativePath = scriptPath,
            readmeText = readme,
            signatureStatus = signatureStatus,
            geometry = PluginGeometry(
                defaultWidth = defW.coerceIn(180, 900),
                defaultHeight = defH.coerceIn(200, 1200),
                minWidth = minW.coerceIn(160, 800),
                minHeight = minH.coerceIn(180, 1000),
                maxWidth = maxW.coerceAtLeast(defW),
                maxHeight = maxH.coerceAtLeast(defH)
            ),
            csxsVersion = csxsVersion,
            hostVersionRange = hostAeftRange,
            cefParams = cefParams,
            rawAttributes = rawAttributes + mapOf("uiType" to uiType)
        )
        return DraftPlugin(bundleRoot, plugin)
    }

    private fun parseScriptBundle(
        stagingRoot: File,
        fallbackArchiveName: String,
        signatureStatus: PluginSignatureStatus
    ): DraftPlugin {
        val scriptHit = findFileUpToDepth(stagingRoot, maxDirDepth = 2) { _, file ->
            val ext = file.extension.lowercase(Locale.US)
            (ext == "jsx" || ext == "jsxbin") && !file.name.startsWith("._")
        } ?: throw IllegalArgumentException(
            "No CSXS/manifest.xml or .jsx / .jsxbin entry found within 2 folder levels."
        )

        val scriptFile = scriptHit.second
        // Determine bundleRoot: if script sits inside a single top-level folder (like Textevo_2026.1.0/textevo.jsxbin),
        // use that folder as bundleRoot so README.txt and sibling assets stay at relative root!
        val parentDir = scriptFile.parentFile ?: stagingRoot
        val bundleRoot = if (parentDir != stagingRoot && parentDir.parentFile == stagingRoot) {
            parentDir
        } else {
            stagingRoot
        }

        val relScriptPath = scriptFile.canonicalPath
            .removePrefix(bundleRoot.canonicalPath)
            .trimStart('/', '\\')
            .replace('\\', '/')

        val readme = findReadmeText(bundleRoot)
        val rawFolderOrArchive = if (bundleRoot != stagingRoot) bundleRoot.name else fallbackArchiveName.substringBeforeLast('.')
        val versionRegex = Regex("""(\d+\.\d+(?:\.\d+)?)""")
        val detectedVersion = versionRegex.find(rawFolderOrArchive)?.groupValues?.getOrNull(1)
            ?: versionRegex.find(readme)?.groupValues?.getOrNull(1)
            ?: "1.0.0"

        val baseTitle = rawFolderOrArchive
            .replace(versionRegex, "")
            .trim('_', '-', ' ', '.')
            .ifBlank { scriptFile.nameWithoutExtension }
        val displayTitle = baseTitle.replaceFirstChar {
            if (it.isLowerCase()) it.titlecase(Locale.US) else it.toString()
        }
        val slug = sanitizeSegment(baseTitle.lowercase(Locale.US)).ifBlank { "script" }
        val bundleId = "com.scriptui.$slug"

        val isScriptUi = detectIfScriptUiEntry(scriptFile, bundleRoot != stagingRoot, readme)
        val plugin = InstalledPlugin(
            bundleId = bundleId,
            name = displayTitle,
            version = detectedVersion,
            type = if (isScriptUi) PluginType.SCRIPT_UI else PluginType.STANDALONE_JSX,
            author = extractAuthorFromReadme(readme),
            menuName = displayTitle,
            hostScriptRelativePath = relScriptPath,
            readmeText = readme,
            signatureStatus = signatureStatus,
            rawAttributes = mapOf(
                "sourceArchive" to fallbackArchiveName,
                "entryExtension" to scriptFile.extension.lowercase(Locale.US)
            )
        )
        return DraftPlugin(bundleRoot, plugin)
    }

    private fun detectIfScriptUiEntry(scriptFile: File, fromFolderArchive: Boolean, readme: String): Boolean {
        if (readme.contains("ScriptUI Panels", ignoreCase = true) ||
            readme.contains("Window", ignoreCase = true)
        ) {
            return true
        }
        val bytes = scriptFile.readBytes()
        val head = bytes.take(64).toByteArray().toString(Charsets.ISO_8859_1)
        if (JsxbinDecoder.isJsxbinContent(head)) {
            val decoded = JsxbinDecoder.decodeInMemory(bytes)
            if (decoded is JsxbinDecoder.DecodeOutcome.Success) {
                val syms = decoded.binarySession.extractedSymbols
                if ("Panel" in syms || "Window" in syms || "ScriptUI" in syms) return true
            }
            return fromFolderArchive
        }
        val text = bytes.toString(Charsets.UTF_8)
        return text.contains("instanceof Panel") ||
            text.contains("new Window") ||
            text.contains("ScriptUI")
    }

    fun analyzeInstalledDirectory(
        pluginDir: File,
        pluginName: String,
        bundleId: String,
        version: String,
        pluginType: PluginType,
        csxsVersion: String,
        hostScriptRelativePath: String
    ): CompatibilityReport {
        val detected = LinkedHashMap<CompatibilityCategory, MutableSet<String>>()
        CompatibilityCategory.entries.forEach { detected[it] = LinkedHashSet() }
        val issues = ArrayList<CompatibilityIssue>()

        if (pluginType == PluginType.CEP_PANEL) {
            val major = csxsVersion.substringBefore('.').toIntOrNull()
            if (major != null && major !in 4..12) {
                issues.add(
                    CompatibilityIssue(
                        category = CompatibilityCategory.DOM,
                        item = "CSXS $csxsVersion",
                        detail = "Uncommon CSXS runtime version ($csxsVersion)",
                        fatal = false
                    )
                )
            }
            val htmlRel = findMainHtmlIfPresent(pluginDir)
            if (htmlRel == null) {
                issues.add(
                    CompatibilityIssue(
                        category = CompatibilityCategory.SCRIPT_UI,
                        item = "MainPath",
                        detail = "CEP MainPath HTML file missing",
                        fatal = true
                    )
                )
            }
        }

        // Ignore PC-only app-side folders (e.g. KeyEase/app-side/) per spec Section 2.6
        val filesToScan = pluginDir.walkTopDown()
            .filter { f ->
                val rel = f.relativeTo(pluginDir).path.replace('\\', '/')
                f.isFile &&
                    !rel.startsWith("app-side/") &&
                    !rel.contains("/app-side/") &&
                    f.extension.lowercase(Locale.US) in setOf("jsx", "jsxbin", "js")
            }
            .toList()

        if (pluginType != PluginType.CEP_PANEL && hostScriptRelativePath.isNotBlank()) {
            val hostFile = File(pluginDir, hostScriptRelativePath)
            if (!hostFile.exists()) {
                issues.add(
                    CompatibilityIssue(
                        category = CompatibilityCategory.DOM,
                        item = hostScriptRelativePath,
                        detail = "Entry ExtendScript file missing",
                        fatal = true
                    )
                )
            }
        }

        for (file in filesToScan) {
            val relName = file.relativeTo(pluginDir).path.replace('\\', '/')
            val bytes = file.readBytes()
            val head = bytes.take(64).toByteArray().toString(Charsets.ISO_8859_1)
            if (file.extension.equals("jsxbin", ignoreCase = true) || JsxbinDecoder.isJsxbinContent(head)) {
                when (val dec = JsxbinDecoder.decodeInMemory(bytes)) {
                    is JsxbinDecoder.DecodeOutcome.Success -> {
                        detected.getValue(CompatibilityCategory.JSXBIN).add("JSXBIN ES ${dec.version}")
                        classifySymbolsIntoReport(dec.binarySession.extractedSymbols, relName, detected, issues)
                    }
                    is JsxbinDecoder.DecodeOutcome.UnsupportedVersion -> {
                        issues.add(
                            CompatibilityIssue(
                                category = CompatibilityCategory.JSXBIN,
                                item = relName,
                                detail = "Unsupported (jsxbin version ${dec.version})",
                                fatal = true
                            )
                        )
                    }
                    is JsxbinDecoder.DecodeOutcome.Corrupted -> {
                        issues.add(
                            CompatibilityIssue(
                                category = CompatibilityCategory.JSXBIN,
                                item = relName,
                                detail = "Unsupported (${dec.reason})",
                                fatal = true
                            )
                        )
                    }
                }
            } else {
                val src = bytes.toString(Charsets.UTF_8)
                classifyPlainScriptIntoReport(src, relName, detected, issues)
            }
        }

        return CompatibilityReport(
            pluginName = pluginName,
            bundleId = bundleId,
            version = version,
            detectedApis = detected.mapValues { it.value.toList() },
            issues = issues
        )
    }

    private fun classifyPlainScriptIntoReport(
        src: String,
        relName: String,
        detected: Map<CompatibilityCategory, MutableSet<String>>,
        issues: MutableList<CompatibilityIssue>
    ) {
        val domTokens = listOf(
            "CompItem", "AVLayer", "TextLayer", "ShapeLayer", "CameraLayer",
            "KeyframeEase", "KeyframeInterpolationType", "PropertyType",
            "selectedProperties", "selectedLayers", "selectedKeys",
            "setTemporalEaseAtKey", "beginUndoGroup", "endUndoGroup",
            "TextDocument", "MarkerValue", "app.settings", "app.preferences"
        )
        domTokens.forEach { tok ->
            if (src.contains(tok)) detected.getValue(CompatibilityCategory.DOM).add(tok)
        }
        if (src.contains("instanceof Panel") || src.contains("new Window") || src.contains("ScriptUI")) {
            detected.getValue(CompatibilityCategory.SCRIPT_UI).add("ScriptUI Panel/Window")
        }
        if (src.contains(".graphics") || src.contains("newPen") || src.contains("newPath")) {
            detected.getValue(CompatibilityCategory.SCRIPT_UI).add("ScriptUIGraphics")
        }
        if (src.contains(".expression")) {
            detected.getValue(CompatibilityCategory.EXPRESSIONS).add("Property.expression")
        }
        if (src.contains("ADBE Text Animators") || src.contains("ADBE Text Properties")) {
            detected.getValue(CompatibilityCategory.EFFECTS).add("AE Text Animators -> texttransform")
        }
        if (src.contains("require('fs')") || src.contains("require(\"fs\")") ||
            src.contains("new File") || src.contains("new Folder")
        ) {
            detected.getValue(CompatibilityCategory.FILE_AND_NETWORK).add("Sandboxed File/Folder FS")
        }
        if (src.contains("system.callSystem")) {
            issues.add(
                CompatibilityIssue(
                    category = CompatibilityCategory.FILE_AND_NETWORK,
                    item = "system.callSystem ($relName)",
                    detail = "OS shell execution is blocked in mobile sandbox",
                    fatal = false
                )
            )
        }
        if (src.contains("new Socket")) {
            issues.add(
                CompatibilityIssue(
                    category = CompatibilityCategory.FILE_AND_NETWORK,
                    item = "Socket ($relName)",
                    detail = "Raw ExtendScript TCP Socket disabled offline",
                    fatal = false
                )
            )
        }
    }

    private fun classifySymbolsIntoReport(
        symbols: Set<String>,
        relName: String,
        detected: Map<CompatibilityCategory, MutableSet<String>>,
        issues: MutableList<CompatibilityIssue>
    ) {
        symbols.forEach { sym ->
            when {
                sym in setOf(
                    "CompItem", "AVLayer", "TextLayer", "ShapeLayer", "CameraLayer",
                    "KeyframeEase", "KeyframeInterpolationType", "MarkerValue",
                    "beginUndoGroup", "endUndoGroup", "activeItem", "selectedLayers",
                    "selectedProperties", "selectedKeys", "setTemporalEaseAtKey",
                    "setValueAtTime", "TextDocument", "settings", "preferences"
                ) -> detected.getValue(CompatibilityCategory.DOM).add(sym)

                sym in setOf(
                    "Panel", "Window", "ScriptUI", "graphics", "newPen", "newBrush",
                    "newPath", "customBoundedValue", "dropdownlist", "slider",
                    "checkbox", "radiobutton", "edittext", "statictext", "button",
                    "tabbedpanel", "progressbar", "listbox"
                ) -> detected.getValue(CompatibilityCategory.SCRIPT_UI).add(sym)

                sym == "expression" ->
                    detected.getValue(CompatibilityCategory.EXPRESSIONS).add("Property.expression")

                sym.startsWith("ADBE Text") ->
                    detected.getValue(CompatibilityCategory.EFFECTS).add(sym)

                sym == "File" || sym == "Folder" ->
                    detected.getValue(CompatibilityCategory.FILE_AND_NETWORK).add("ExtendScript $sym (sandboxed)")

                sym == "Socket" -> {
                    detected.getValue(CompatibilityCategory.FILE_AND_NETWORK).add("Socket")
                    issues.add(
                        CompatibilityIssue(
                            category = CompatibilityCategory.FILE_AND_NETWORK,
                            item = "Socket ($relName)",
                            detail = "ExtendScript Socket calls are stubbed offline",
                            fatal = false
                        )
                    )
                }

                sym == "system.callSystem" -> {
                    issues.add(
                        CompatibilityIssue(
                            category = CompatibilityCategory.FILE_AND_NETWORK,
                            item = "system.callSystem ($relName)",
                            detail = "Shell calls are blocked in sandbox",
                            fatal = false
                        )
                    )
                }
            }
        }
    }

    private fun findMainHtmlIfPresent(pluginDir: File): File? {
        return pluginDir.walkTopDown().firstOrNull { f ->
            val rel = f.relativeTo(pluginDir).path.replace('\\', '/')
            f.isFile &&
                !rel.startsWith("app-side/") &&
                (f.extension.equals("html", ignoreCase = true) || f.extension.equals("htm", ignoreCase = true))
        }
    }

    private fun detectSignatureStatus(root: File): PluginSignatureStatus {
        val sig = findFileUpToDepth(root, maxDirDepth = 3) { rel, f ->
            val norm = rel.replace('\\', '/').lowercase(Locale.US)
            norm.contains("meta-inf/signatures.xml") ||
                (norm.contains("meta-inf/") && f.extension.lowercase(Locale.US) in setOf("rsa", "dsa", "sf", "p7s"))
        }
        return if (sig != null) PluginSignatureStatus.SIGNED_NOT_VERIFIED else PluginSignatureStatus.UNSIGNED
    }

    private fun findReadmeText(bundleRoot: File): String {
        val hit = findFileUpToDepth(bundleRoot, maxDirDepth = 2) { _, f ->
            val lower = f.name.lowercase(Locale.US)
            lower.startsWith("readme") && (lower.endsWith(".txt") || lower.endsWith(".md"))
        } ?: return ""
        return runCatching { hit.second.readText(Charsets.UTF_8).take(4_000) }.getOrDefault("")
    }

    private fun extractAuthorFromReadme(readme: String): String {
        if (readme.isBlank()) return ""
        val match = Regex("""(?im)^(?:author|by|created by)\s*[:\-]\s*(.+)$""").find(readme)
        return match?.groupValues?.getOrNull(1)?.trim()?.take(60).orEmpty()
    }

    private fun findFileUpToDepth(
        root: File,
        maxDirDepth: Int,
        predicate: (relativePath: String, file: File) -> Boolean
    ): Pair<String, File>? {
        fun walk(dir: File, currentDepth: Int, prefix: String): Pair<String, File>? {
            val children = dir.listFiles()?.sortedBy { it.name } ?: return null
            for (child in children) {
                if (child.isFile) {
                    val rel = if (prefix.isEmpty()) child.name else "$prefix/${child.name}"
                    if (predicate(rel, child)) return rel to child
                }
            }
            if (currentDepth >= maxDirDepth) return null
            for (child in children) {
                if (child.isDirectory && !child.name.startsWith("__MACOSX")) {
                    val rel = if (prefix.isEmpty()) child.name else "$prefix/${child.name}"
                    val sub = walk(child, currentDepth + 1, rel)
                    if (sub != null) return sub
                }
            }
            return null
        }
        return walk(root, 0, "")
    }

    private class ZipCrypto(password: ByteArray) {
        private var key0 = 0x12345678
        private var key1 = 0x23456789
        private var key2 = 0x34567890

        init {
            for (b in password) updateKeys(b)
        }

        private fun crc32(key: Int, b: Byte): Int {
            val crc = java.util.zip.CRC32()
            crc.update(key and 0xFF)
            crc.update((key ushr 8) and 0xFF)
            crc.update((key ushr 16) and 0xFF)
            crc.update((key ushr 24) and 0xFF)
            crc.update(b.toInt())
            return crc.value.toInt()
        }

        fun updateKeys(b: Byte) {
            key0 = crc32(key0, b)
            key1 += (key0 and 0xFF)
            key1 = (key1 * 134775813 + 1)
            key2 = crc32(key2, ((key1 ushr 24) and 0xFF).toByte())
        }

        fun decryptByte(c: Byte): Byte {
            val temp = (key2 or 2)
            val k = ((temp * (temp xor 1)) ushr 8) and 0xFF
            val plain = (c.toInt() xor k).toByte()
            updateKeys(plain)
            return plain
        }
    }

    private fun extractZipSafely(
        inputStream: InputStream,
        targetRoot: File,
        quotaBytes: Long,
        password: String? = null,
        archiveName: String = ""
    ) {
        val canonicalRoot = targetRoot.canonicalFile
        val canonicalRootPrefix = canonicalRoot.path + File.separator
        var totalBytes = 0L
        var entryCount = 0

        val rawBytes = inputStream.readBytes()
        if (rawBytes.isEmpty()) {
            throw IllegalArgumentException("Archive is empty or not a valid ZIP/ZXP package.")
        }

        // Scan raw ZIP bytes for PK\003\004 local file headers to detect encryption bit (bit 0 of flag at offset 6)
        var scanIdx = 0
        var hasEncryptedEntries = false
        while (scanIdx + 8 < rawBytes.size) {
            if (rawBytes[scanIdx] == 0x50.toByte() &&
                rawBytes[scanIdx + 1] == 0x4B.toByte() &&
                rawBytes[scanIdx + 2] == 0x03.toByte() &&
                rawBytes[scanIdx + 3] == 0x04.toByte()
            ) {
                val flag = (rawBytes[scanIdx + 6].toInt() and 0xFF) or ((rawBytes[scanIdx + 7].toInt() and 0xFF) shl 8)
                if ((flag and 0x0001) != 0) {
                    hasEncryptedEntries = true
                    break
                }
            }
            scanIdx++
        }

        if (hasEncryptedEntries && password.isNullOrBlank()) {
            throw PasswordRequiredException(archiveName.ifBlank { "Archive" })
        }

        val inStreamToUse = ByteArrayInputStream(rawBytes)
        ZipInputStream(inStreamToUse).use { zis ->
            var entry = try {
                zis.nextEntry
            } catch (ze: Exception) {
                if (password.isNullOrBlank()) throw PasswordRequiredException(archiveName)
                else throw ze
            }
            while (entry != null) {
                entryCount++
                if (entryCount > MAX_ZIP_ENTRIES) {
                    throw SecurityException("Plugin archive exceeds maximum file count ($MAX_ZIP_ENTRIES).")
                }
                val entryName = entry.name.replace('\\', '/').trimStart('/')
                val isIgnoredEntry = entryName.startsWith("__MACOSX/") ||
                    entryName.split('/').any { it.equals("app-side", ignoreCase = true) }
                if (entryName.isNotBlank() && !isIgnoredEntry) {
                    val outFile = File(canonicalRoot, entryName).canonicalFile
                    if (outFile != canonicalRoot && !outFile.path.startsWith(canonicalRootPrefix)) {
                        throw SecurityException("Blocked zip-slip path traversal entry: ${entry.name}")
                    }
                    if (entry.isDirectory) {
                        outFile.mkdirs()
                    } else {
                        outFile.parentFile?.mkdirs()
                        val buffer = ByteArray(8_192)
                        FileOutputStream(outFile).use { fos ->
                            val crypto = if (!password.isNullOrEmpty()) ZipCrypto(password.toByteArray(Charsets.UTF_8)) else null
                            // If encrypted, skip 12-byte encryption header
                            var read = zis.read(buffer)
                            while (read != -1) {
                                totalBytes += read
                                if (totalBytes > quotaBytes) {
                                    throw IllegalStateException("Plugin exceeds storage quota (${quotaBytes / (1024 * 1024)} MB).")
                                }
                                if (crypto != null) {
                                    for (i in 0 until read) {
                                        buffer[i] = crypto.decryptByte(buffer[i])
                                    }
                                }
                                fos.write(buffer, 0, read)
                                read = zis.read(buffer)
                            }
                        }
                    }
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
        if (entryCount == 0) {
            throw IllegalArgumentException("Archive is empty or not a valid ZIP/ZXP package.")
        }
    }

    private fun copyBounded(inputStream: InputStream, outFile: File, quotaBytes: Long) {
        outFile.parentFile?.mkdirs()
        var total = 0L
        val buf = ByteArray(8_192)
        FileOutputStream(outFile).use { fos ->
            var n = inputStream.read(buf)
            while (n != -1) {
                total += n
                if (total > quotaBytes) {
                    throw IllegalStateException("Plugin file exceeds storage quota.")
                }
                fos.write(buf, 0, n)
                n = inputStream.read(buf)
            }
        }
    }

    private fun copyDirectoryBounded(srcDir: File, dstDir: File, quotaBytes: Long) {
        var total = 0L
        srcDir.walkTopDown().forEach { src ->
            val rel = src.relativeTo(srcDir).path
            val target = if (rel.isEmpty()) dstDir else File(dstDir, rel)
            if (src.isDirectory) {
                target.mkdirs()
            } else {
                total += src.length()
                if (total > quotaBytes) {
                    throw IllegalStateException("Plugin installation exceeds storage quota.")
                }
                target.parentFile?.mkdirs()
                src.copyTo(target, overwrite = true)
            }
        }
    }

    private fun normalizeRelPath(raw: String): String =
        raw.replace('\\', '/').removePrefix("./").trimStart('/')

    private fun sanitizeFileName(name: String): String =
        name.replace(Regex("""[^A-Za-z0-9._\- ]"""), "_").ifBlank { "script.jsx" }

    fun sanitizeSegment(raw: String): String =
        raw.replace(Regex("""[^A-Za-z0-9._\-]"""), "_").trim('.', '_').ifBlank { "plugin" }
}
