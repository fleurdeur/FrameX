package com.example.scriptengine

enum class PluginType(val label: String) {
    CEP_PANEL("CEP Panel"),
    SCRIPT_UI("ScriptUI Panel"),
    STANDALONE_JSX("ExtendScript")
}

enum class PluginSignatureStatus(val label: String) {
    SIGNED_NOT_VERIFIED("Signed (not verified)"),
    UNSIGNED("Unsigned")
}

enum class PluginInstallStatus(val label: String) {
    WORKS("Works"),
    PARTIAL("Partial"),
    UNSUPPORTED("Unsupported")
}

enum class CompatibilityCategory(val key: String, val title: String) {
    DOM("DOM", "AE DOM APIs"),
    SCRIPT_UI("ScriptUI", "ScriptUI Controls & Graphics"),
    EXPRESSIONS("expressions", "Expressions"),
    EFFECTS("effects", "Effects & Animators"),
    FILE_AND_NETWORK("fileAndNetwork", "File & Network Sandbox"),
    JSXBIN("jsxbin", "Binary ExtendScript (.jsxbin)")
}

data class CompatibilityIssue(
    val category: CompatibilityCategory,
    val item: String,
    val detail: String,
    val fatal: Boolean = false
)

data class CompatibilityReport(
    val pluginName: String,
    val bundleId: String,
    val version: String,
    val detectedApis: Map<CompatibilityCategory, List<String>> = emptyMap(),
    val issues: List<CompatibilityIssue> = emptyList(),
    val probeExecuted: Boolean = false
) {
    val hasFatalIssue: Boolean
        get() = issues.any { it.fatal }

    val nonFatalIssueCount: Int
        get() = issues.count { !it.fatal }

    val status: PluginInstallStatus
        get() = when {
            hasFatalIssue -> PluginInstallStatus.UNSUPPORTED
            issues.isNotEmpty() -> PluginInstallStatus.PARTIAL
            else -> PluginInstallStatus.WORKS
        }

    val statusBadgeText: String
        get() = when (status) {
            PluginInstallStatus.WORKS -> "Works"
            PluginInstallStatus.PARTIAL -> "Partial (${issues.size} issue${if (issues.size == 1) "" else "s"})"
            PluginInstallStatus.UNSUPPORTED -> {
                val fatalDetail = issues.firstOrNull { it.fatal }?.detail
                if (!fatalDetail.isNullOrBlank() && fatalDetail.startsWith("Unsupported")) {
                    fatalDetail
                } else {
                    "Unsupported"
                }
            }
        }

    val summary: String
        get() = statusBadgeText

    fun issuesByCategory(): Map<CompatibilityCategory, List<CompatibilityIssue>> =
        CompatibilityCategory.entries.associateWith { cat ->
            issues.filter { it.category == cat }
        }

    fun toPlainTextReport(): String = buildString {
        appendLine("Plugin Compatibility Report")
        appendLine("===========================")
        appendLine("Name: $pluginName")
        appendLine("Bundle ID: $bundleId")
        appendLine("Version: $version")
        appendLine("Status: $statusBadgeText")
        appendLine("Dry-Run Probe Executed: ${if (probeExecuted) "Yes" else "No"}")
        appendLine()
        CompatibilityCategory.entries.forEach { cat ->
            appendLine("[${cat.key}] ${cat.title}")
            val detected = detectedApis[cat].orEmpty()
            if (detected.isNotEmpty()) {
                appendLine("  Detected: ${detected.joinToString(", ")}")
            }
            val catIssues = issues.filter { it.category == cat }
            if (catIssues.isEmpty()) {
                appendLine("  Issues: None")
            } else {
                catIssues.forEach { issue ->
                    val sev = if (issue.fatal) "FATAL" else "WARN"
                    appendLine("  - [$sev] ${issue.item}: ${issue.detail}")
                }
            }
            appendLine()
        }
    }
}

data class PluginUiState(
    val enabled: Boolean = true,
    val showInTimeline: Boolean = true,
    val pinToAddMenu: Boolean = true,
    val order: Int = 0
)

data class PluginGeometry(
    val defaultWidth: Int = 280,
    val defaultHeight: Int = 460,
    val minWidth: Int = 220,
    val minHeight: Int = 320,
    val maxWidth: Int = 640,
    val maxHeight: Int = 820
)

data class InstalledPlugin(
    val bundleId: String,
    val name: String,
    val version: String,
    val type: PluginType,
    val author: String = "",
    val menuName: String = "",
    val entryHtmlRelativePath: String = "",
    val hostScriptRelativePath: String = "",
    val readmeText: String = "",
    val signatureStatus: PluginSignatureStatus = PluginSignatureStatus.UNSIGNED,
    val installDirPath: String = "",
    val dataDirPath: String = "",
    val geometry: PluginGeometry = PluginGeometry(),
    val csxsVersion: String = "",
    val hostVersionRange: String = "",
    val cefParams: List<String> = emptyList(),
    val rawAttributes: Map<String, String> = emptyMap(),
    val report: CompatibilityReport = CompatibilityReport(name, bundleId, version),
    val uiState: PluginUiState = PluginUiState()
) {
    val displayName: String
        get() = menuName.ifBlank { name }
}

/**
 * Host editor bridge contract implemented by `:app` so `:script-engine` manipulates the app's
 * real composition, layers, properties, keyframes, cubic-Bezier curves, text animators, markers,
 * and undo stack directly without duplicating any project model.
 */
data class HostKeyframeDto(
    val index1Based: Int,
    val timeSec: Double,
    val value: DoubleArray,
    val outX: Double = 0.25,
    val outY: Double = 0.25,
    val inX: Double = 0.75,
    val inY: Double = 0.75,
    val holdStep: Boolean = false,
    val selected: Boolean = true
)

data class HostPropertyDto(
    val layerId: String,
    val propertyKey: String,
    val matchName: String,
    val name: String,
    val isSpatial: Boolean,
    val dimensions: Int,
    val baseValue: DoubleArray,
    val expression: String,
    val keyframes: List<HostKeyframeDto>,
    val selectedKeyIndices1Based: List<Int>
)

data class HostLayerDto(
    val id: String,
    val index1Based: Int,
    val name: String,
    val kind: String, // "AV", "TEXT", "SHAPE", "NULL", "CAMERA", "GROUP"
    val inPointSec: Double,
    val outPointSec: Double,
    val startTimeSec: Double,
    val selected: Boolean,
    val textValue: String = "",
    val fontFamily: String = "",
    val fontSize: Double = 48.0,
    val fillColorRgb: DoubleArray = doubleArrayOf(1.0, 1.0, 1.0),
    val tracking: Double = 0.0
)

data class HostCompDto(
    val id: String,
    val name: String,
    val width: Int,
    val height: Int,
    val durationSec: Double,
    val frameRate: Double,
    val playheadSec: Double,
    val layers: List<HostLayerDto>,
    val selectedProperties: List<HostPropertyDto>
)

interface AeDomBridge {
    fun getActiveComp(): HostCompDto?
    fun beginUndoGroup(label: String)
    fun endUndoGroup()
    fun updatePropertyKeyframes(
        layerId: String,
        propertyKey: String,
        updatedKeyframes: List<HostKeyframeDto>,
        updatedBaseValue: DoubleArray? = null,
        updatedExpression: String? = null
    )
    fun updateTextDocument(
        layerId: String,
        text: String,
        fontFamily: String,
        fontSize: Double,
        fillColorRgb: DoubleArray,
        tracking: Double
    )
    fun applyTextAnimator(
        layerId: String,
        animatorName: String,
        selectorValues: Map<String, Double>,
        propertyValues: Map<String, DoubleArray>,
        propertyKeyframes: Map<String, List<HostKeyframeDto>>
    )
    fun addLayerOrCompMarker(
        layerId: String?,
        timeSec: Double,
        comment: String,
        durationSec: Double
    )
}
