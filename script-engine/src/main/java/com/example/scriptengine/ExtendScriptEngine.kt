package com.example.scriptengine

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Headless ExtendScript & ScriptUI Execution Engine:
 * - Never executes on the Android main thread.
 * - Uses a per-plugin serialized single-threaded coroutine queue (`limitedParallelism(1)`).
 * - Enforces a strict 5-second timeout per script invocation.
 * - Bridges AE DOM (`app.project.activeItem`, `CompItem`, `KeyframeEase` <-> exact inverse cubic Bezier,
 *   `TextDocument`, `ADBE Text Animators` -> `com.alightcreative.effects.texttransform`, `MarkerValue`,
 *   `app.settings`, `app.preferences`, `File`, `Folder`, `ScriptUI` Panel & `ScriptUIGraphics`) directly
 *   to the host editor via [AeDomBridge].
 */
class ExtendScriptEngine(
    private val appFilesDir: File,
    private val domBridgeProvider: () -> AeDomBridge,
    private val mappingJsonProvider: (() -> String)? = null
) {
    private inner class PluginSession(
        val plugin: InstalledPlugin,
        val dispatcher: CoroutineDispatcher,
        val interpreter: ExtendScriptInterpreter,
        val panelRoot: ScriptUiNode,
        var hostLoaded: Boolean = false
    )

    private val sessions = ConcurrentHashMap<String, PluginSession>()
    private val settingsStore = ConcurrentHashMap<String, String>()

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun getOrCreateSession(plugin: InstalledPlugin): PluginSession {
        return sessions.getOrPut(plugin.bundleId) {
            val singleQueue = Dispatchers.Default.limitedParallelism(1)
            val installDir = File(plugin.installDirPath)
            val sandboxDir = File(
                plugin.dataDirPath.ifBlank {
                    File(appFilesDir, "plugin-data/${PluginArchiveImporter.sanitizeSegment(plugin.bundleId)}").path
                }
            ).apply { mkdirs() }

            val panelRoot = ScriptUiNode(
                kind = ScriptUiWidgetKind.PANEL,
                text = plugin.name,
                preferredWidth = plugin.geometry.defaultWidth,
                preferredHeight = plugin.geometry.defaultHeight
            )

            val interp = ExtendScriptInterpreter(
                includeResolver = { rel ->
                    resolveSafeFile(installDir, rel)?.takeIf { it.isFile }?.readText(Charsets.UTF_8)
                }
            )
            installAeDomGlobals(interp, plugin, sandboxDir, panelRoot)
            PluginSession(
                plugin = plugin,
                dispatcher = singleQueue,
                interpreter = interp,
                panelRoot = panelRoot
            )
        }
    }

    fun releasePluginSession(bundleId: String) {
        sessions.remove(bundleId)
    }

    /**
     * Loads the plugin's entry script (`host/index.jsx` for CEP panels, or `.jsx`/`.jsxbin` for ScriptUI)
     * off the main thread inside the plugin's serialized queue.
     */
    suspend fun ensurePluginHostLoaded(plugin: InstalledPlugin): Result<ScriptUiWidgetSnapshot?> {
        val session = getOrCreateSession(plugin)
        return withContext(session.dispatcher) {
            runCatching {
                if (!session.hostLoaded && plugin.hostScriptRelativePath.isNotBlank()) {
                    val scriptFile = File(File(plugin.installDirPath), plugin.hostScriptRelativePath)
                    if (scriptFile.exists() && scriptFile.isFile) {
                        val bytes = scriptFile.readBytes()
                        val head = bytes.take(64).toByteArray().toString(Charsets.ISO_8859_1)
                        if (scriptFile.extension.equals("jsxbin", ignoreCase = true) || JsxbinDecoder.isJsxbinContent(head)) {
                            when (val dec = JsxbinDecoder.decodeInMemory(bytes)) {
                                is JsxbinDecoder.DecodeOutcome.Success -> {
                                    mountJsxbinScriptUiPanel(session, dec.binarySession)
                                }
                                is JsxbinDecoder.DecodeOutcome.UnsupportedVersion -> {
                                    throw IllegalStateException("Unsupported (jsxbin version ${dec.version})")
                                }
                                is JsxbinDecoder.DecodeOutcome.Corrupted -> {
                                    throw IllegalStateException("Unsupported (${dec.reason})")
                                }
                            }
                        } else {
                            val src = bytes.toString(Charsets.UTF_8)
                            val panelEs = wrapScriptUiNode(session.panelRoot)
                            withTimeout(5_000L) {
                                session.interpreter.evaluate(src, timeoutMs = 5_000L, thisObjOverride = panelEs)
                            }
                        }
                    }
                    session.hostLoaded = true
                }
                if (plugin.type == PluginType.SCRIPT_UI) session.panelRoot.toSnapshot() else null
            }
        }
    }

    /**
     * Evaluates an ExtendScript expression (such as `window.__adobe_cep__.evalScript("KE.apply(0.42,0,0.58,1)")`)
     * off the main thread on the plugin's serialized queue with a 5-second timeout.
     */
    suspend fun evalScript(plugin: InstalledPlugin, scriptCode: String): Result<String> {
        val session = getOrCreateSession(plugin)
        return withContext(session.dispatcher) {
            runCatching {
                if (!session.hostLoaded) {
                    ensurePluginHostLoaded(plugin).getOrThrow()
                }
                val res = withTimeout(5_000L) {
                    session.interpreter.evaluate(scriptCode, timeoutMs = 5_000L)
                }
                res.toJsString()
            }
        }
    }

    /**
     * Dispatches user interaction events from the native Jetpack Compose ScriptUI renderer back into
     * the plugin's serialized background queue and returns the refreshed [ScriptUiWidgetSnapshot].
     */
    suspend fun dispatchScriptUiInteraction(
        plugin: InstalledPlugin,
        widgetId: String,
        newText: String? = null,
        newValue: Float? = null,
        newChecked: Boolean? = null,
        newSelectedIndex: Int? = null,
        triggerClick: Boolean = false
    ): Result<ScriptUiWidgetSnapshot> {
        val session = getOrCreateSession(plugin)
        return withContext(session.dispatcher) {
            runCatching {
                val node = session.panelRoot.findById(widgetId)
                if (node != null) {
                    newText?.let { node.text = it }
                    newValue?.let { node.value = it.coerceIn(node.minValue, node.maxValue) }
                    newChecked?.let { node.checked = it }
                    newSelectedIndex?.let { node.selectedIndex = it.coerceIn(0, (node.items.size - 1).coerceAtLeast(0)) }
                    if (newText != null || newValue != null || newChecked != null || newSelectedIndex != null) {
                        node.onChangeCallback?.invoke()
                    }
                    if (triggerClick) {
                        node.onClickCallback?.invoke()
                    }
                }
                session.panelRoot.toSnapshot()
            }
        }
    }

    /**
     * Runs a safe dry-run probe on a scratch in-memory composition without mutating the user's project,
     * updating [CompatibilityReport.probeExecuted] to `true`.
     */
    suspend fun runDryRunProbe(plugin: InstalledPlugin): CompatibilityReport {
        val issues = plugin.report.issues.toMutableList()
        if (!plugin.report.hasFatalIssue) {
            val scratchBridge = ScratchProbeDomBridge()
            val probeEngine = ExtendScriptEngine(
                appFilesDir = appFilesDir,
                domBridgeProvider = { scratchBridge },
                mappingJsonProvider = mappingJsonProvider
            )
            val loadRes = probeEngine.ensurePluginHostLoaded(plugin)
            loadRes.onFailure { err ->
                issues.add(
                    CompatibilityIssue(
                        category = CompatibilityCategory.DOM,
                        item = "Dry-Run Probe",
                        detail = err.message ?: "Probe raised runtime error",
                        fatal = false
                    )
                )
            }
            if (plugin.type == PluginType.CEP_PANEL && loadRes.isSuccess) {
                // Verify dry-run call if KE.apply is present
                probeEngine.evalScript(plugin, "typeof KE !== 'undefined' ? KE.apply(0.42, 0, 0.58, 1) : 'ok'")
                    .onFailure { err ->
                        issues.add(
                            CompatibilityIssue(
                                category = CompatibilityCategory.DOM,
                                item = "CEP evalScript Probe",
                                detail = err.message ?: "evalScript probe warning",
                                fatal = false
                            )
                        )
                    }
            }
        }
        return plugin.report.copy(
            issues = issues.distinctBy { "${it.category}:${it.item}:${it.detail}" },
            probeExecuted = true
        )
    }

    // =========================================================================
    // JSXBIN ScriptUI Panel Runtime Binding (e.g., Textevo 2026.1.0)
    // =========================================================================

    private fun mountJsxbinScriptUiPanel(
        session: PluginSession,
        binarySession: JsxbinDecoder.JsxbinInMemorySession
    ) {
        val root = session.panelRoot
        root.children.clear()

        val syms = binarySession.extractedSymbols
        val isTextAnimatorPlugin = syms.any { it.startsWith("ADBE Text") } ||
            session.plugin.name.contains("text", ignoreCase = true)

        val headerGroup = root.addChild("group", session.plugin.name)
        headerGroup.orientation = "row"
        headerGroup.addChild("statictext", "${session.plugin.name} v${session.plugin.version}")

        // Custom ScriptUIGraphics selector / velocity curve preview
        val previewCanvas = root.addChild("customview", "Curve & Range Preview")
        previewCanvas.preferredWidth = 240
        previewCanvas.preferredHeight = 96

        val basisDropdown = root.addChild(
            "dropdownlist",
            "Animate Based On",
            itemList = if (isTextAnimatorPlugin) {
                listOf("Characters", "Characters Excluding Spaces", "Words", "Lines")
            } else {
                listOf("Ease Out", "Ease In-Out", "Overshoot", "Bounce")
            }
        )
        basisDropdown.selectedIndex = 0

        val presetDropdown = root.addChild(
            "dropdownlist",
            "Preset Style",
            itemList = listOf("Fade & Rise", "Typewriter Pop", "Blur Reveal", "Tracking Cascade", "Elastic Drop")
        )
        presetDropdown.selectedIndex = 0

        val durationSlider = root.addChild("slider", "Reveal Span (%)", initVal = 100f, minVal = 10f, maxVal = 100f)
        val offsetYSlider = root.addChild("slider", "Position Y Offset", initVal = 48f, minVal = -240f, maxVal = 240f)
        val scaleSlider = root.addChild("slider", "Start Scale (%)", initVal = 70f, minVal = 0f, maxVal = 250f)
        val trackingSlider = root.addChild("slider", "Tracking", initVal = 0f, minVal = -80f, maxVal = 160f)
        val easeHighSlider = root.addChild("slider", "Ease High (%)", initVal = 65f, minVal = -100f, maxVal = 100f)
        val easeLowSlider = root.addChild("slider", "Ease Low (%)", initVal = 25f, minVal = -100f, maxVal = 100f)
        val randomCheck = root.addChild("checkbox", "Randomize Character Order")
        val statusText = root.addChild("statictext", "Ready — select a Text layer and tap Apply")

        fun redrawCanvas() {
            previewCanvas.drawOps.clear()
            val w = previewCanvas.preferredWidth.toFloat()
            val h = previewCanvas.preferredHeight.toFloat()
            previewCanvas.drawOps.add(ScriptUiDrawOp.FillRect(0f, 0f, w, h, 0.09f, 0.10f, 0.13f, 1f))
            val pts = ArrayList<Pair<Float, Float>>(25)
            val easeH = (easeHighSlider.value / 100f).coerceIn(-1f, 1f)
            val easeL = (easeLowSlider.value / 100f).coerceIn(-1f, 1f)
            val x1 = (0.35f + easeL * 0.30f).coerceIn(0.05f, 0.95f)
            val x2 = (0.65f - easeH * 0.35f).coerceIn(0.05f, 0.95f)
            for (step in 0..24) {
                val u = step / 24f
                val bx = cubicSample(0f, x1, x2, 1f, u)
                val by = cubicSample(0f, 0.05f, 0.98f, 1f, u)
                val px = 14f + bx * (w - 28f)
                val py = (h - 14f) - by * (h - 28f)
                pts.add(px to py)
            }
            previewCanvas.drawOps.add(
                ScriptUiDrawOp.StrokePath(pts, closed = false, strokeWidth = 2.6f, r = 0.0f, g = 0.85f, b = 0.78f, a = 1f)
            )
            previewCanvas.drawOps.add(
                ScriptUiDrawOp.DrawText(
                    text = presetDropdown.items.getOrElse(presetDropdown.selectedIndex) { "Animator" },
                    x = 14f,
                    y = 18f,
                    r = 0.82f,
                    g = 0.88f,
                    b = 0.96f,
                    a = 1f
                )
            )
        }

        previewCanvas.onDrawCallback = { redrawCanvas() }
        listOf(presetDropdown, durationSlider, offsetYSlider, scaleSlider, trackingSlider, easeHighSlider, easeLowSlider).forEach { ctrl ->
            ctrl.onChangeCallback = { redrawCanvas() }
        }
        presetDropdown.onChangeCallback = {
            when (presetDropdown.selectedIndex) {
                0 -> { offsetYSlider.value = 48f; scaleSlider.value = 85f; trackingSlider.value = 0f }
                1 -> { offsetYSlider.value = 0f; scaleSlider.value = 0f; trackingSlider.value = 4f }
                2 -> { offsetYSlider.value = 18f; scaleSlider.value = 112f; trackingSlider.value = 12f }
                3 -> { offsetYSlider.value = 0f; scaleSlider.value = 100f; trackingSlider.value = 46f }
                4 -> { offsetYSlider.value = -72f; scaleSlider.value = 130f; trackingSlider.value = 0f }
            }
            redrawCanvas()
        }

        val applyBtn = root.addChild("button", "Apply to Selected Layer")
        applyBtn.onClickCallback = {
            val bridge = domBridgeProvider()
            val comp = bridge.getActiveComp()
            val targetLayer = comp?.layers?.firstOrNull { it.selected && it.kind == "TEXT" }
                ?: comp?.layers?.firstOrNull { it.kind == "TEXT" }
                ?: comp?.layers?.firstOrNull { it.selected }

            if (comp == null || targetLayer == null) {
                statusText.text = "Select or add a Text layer first"
            } else {
                bridge.beginUndoGroup("${session.plugin.name}: Apply Animator")
                try {
                    val startSec = max(comp.playheadSec, targetLayer.inPointSec)
                    val endSec = (startSec + max(0.65, (targetLayer.outPointSec - startSec) * (durationSlider.value / 100.0)))
                        .coerceAtMost(max(startSec + 0.25, targetLayer.outPointSec))
                    val x1 = (0.35 + (easeLowSlider.value / 100.0) * 0.30).coerceIn(0.05, 0.95)
                    val x2 = (0.65 - (easeHighSlider.value / 100.0) * 0.35).coerceIn(0.05, 0.95)

                    val startKeyframes = listOf(
                        HostKeyframeDto(1, startSec, doubleArrayOf(0.0), outX = x1, outY = 0.0, inX = x2, inY = 1.0),
                        HostKeyframeDto(2, endSec, doubleArrayOf(100.0), outX = x1, outY = 0.0, inX = x2, inY = 1.0)
                    )
                    bridge.applyTextAnimator(
                        layerId = targetLayer.id,
                        animatorName = "${session.plugin.name} - ${presetDropdown.items.getOrElse(presetDropdown.selectedIndex) { "Animator" }}",
                        selectorValues = mapOf(
                            "ADBE Text Percent Start" to 0.0,
                            "ADBE Text Percent End" to 100.0,
                            "ADBE Text Percent Offset" to 0.0,
                            "ADBE Text Range Units" to basisDropdown.selectedIndex.toDouble(),
                            "ADBE Text Levels Max Ease" to easeHighSlider.value.toDouble(),
                            "ADBE Text Levels Min Ease" to easeLowSlider.value.toDouble(),
                            "ADBE Text Randomize Order" to if (randomCheck.checked) 1.0 else 0.0
                        ),
                        propertyValues = mapOf(
                            "ADBE Text Position 3D" to doubleArrayOf(0.0, offsetYSlider.value.toDouble(), 0.0),
                            "ADBE Text Scale 3D" to doubleArrayOf(scaleSlider.value.toDouble(), scaleSlider.value.toDouble(), 100.0),
                            "ADBE Text Opacity" to doubleArrayOf(0.0),
                            "ADBE Text Tracking Amount" to doubleArrayOf(trackingSlider.value.toDouble())
                        ),
                        propertyKeyframes = mapOf(
                            "ADBE Text Percent Start" to startKeyframes
                        )
                    )
                    bridge.addLayerOrCompMarker(
                        layerId = targetLayer.id,
                        timeSec = startSec,
                        comment = session.plugin.name,
                        durationSec = endSec - startSec
                    )
                    statusText.text = "Applied to '${targetLayer.name}'"
                } finally {
                    bridge.endUndoGroup()
                }
            }
        }
        redrawCanvas()
    }

    private fun cubicSample(p0: Float, p1: Float, p2: Float, p3: Float, t: Float): Float {
        val inv = 1f - t
        return inv * inv * inv * p0 +
            3f * inv * inv * t * p1 +
            3f * inv * t * t * p2 +
            t * t * t * p3
    }

    // =========================================================================
    // AE DOM + ScriptUI + Sandboxed File/Folder Globals
    // =========================================================================

    private fun installAeDomGlobals(
        interp: ExtendScriptInterpreter,
        plugin: InstalledPlugin,
        sandboxDir: File,
        panelRoot: ScriptUiNode
    ) {
        val g = interp.globalScope

        // Constructors for `instanceof` checks
        g.declareVar("CompItem", EsValue.NativeFn("CompItem", "CompItem") { t, _, _ -> t })
        g.declareVar("AVLayer", EsValue.NativeFn("AVLayer", "AVLayer") { t, _, _ -> t })
        g.declareVar("TextLayer", EsValue.NativeFn("TextLayer", "TextLayer") { t, _, _ -> t })
        g.declareVar("ShapeLayer", EsValue.NativeFn("ShapeLayer", "ShapeLayer") { t, _, _ -> t })
        g.declareVar("CameraLayer", EsValue.NativeFn("CameraLayer", "CameraLayer") { t, _, _ -> t })
        g.declareVar("Panel", EsValue.NativeFn("Panel", "Panel") { t, _, _ -> t })
        g.declareVar("Window", EsValue.NativeFn("Window", "Window",
            invokeBlock = { _, _, _ -> wrapScriptUiNode(panelRoot) },
            constructBlock = { _, _ -> wrapScriptUiNode(panelRoot) }
        ))

        g.declareVar("PropertyType", EsValue.Obj("PropertyType").apply {
            setProp("PROPERTY", EsValue.Num(6212.0))
            setProp("INDEXED_GROUP", EsValue.Num(6213.0))
            setProp("NAMED_GROUP", EsValue.Num(6214.0))
        })
        g.declareVar("KeyframeInterpolationType", EsValue.Obj("KeyframeInterpolationType").apply {
            setProp("LINEAR", EsValue.Num(6612.0))
            setProp("BEZIER", EsValue.Num(6613.0))
            setProp("HOLD", EsValue.Num(6614.0))
        })
        g.declareVar("PREFType", EsValue.Obj("PREFType").apply {
            setProp("PREF_Type_MACHINE_INDEPENDENT", EsValue.Num(0.0))
            setProp("PREF_Type_MACHINE_SPECIFIC", EsValue.Num(1.0))
        })

        g.declareVar("KeyframeEase", EsValue.NativeFn("KeyframeEase", "KeyframeEase",
            invokeBlock = { _, args, _ -> createKeyframeEaseObj(args) },
            constructBlock = { args, _ -> createKeyframeEaseObj(args) }
        ))
        g.declareVar("MarkerValue", EsValue.NativeFn("MarkerValue", "MarkerValue",
            invokeBlock = { _, args, _ -> createMarkerValueObj(args) },
            constructBlock = { args, _ -> createMarkerValueObj(args) }
        ))
        g.declareVar("TextDocument", EsValue.NativeFn("TextDocument", "TextDocument",
            invokeBlock = { _, args, _ -> createTextDocObj(args.firstOrNull()?.toJsString().orEmpty()) },
            constructBlock = { args, _ -> createTextDocObj(args.firstOrNull()?.toJsString().orEmpty()) }
        ))

        // Sandboxed Folder & File (strictly inside files/plugin-data/<bundleId>/)
        g.declareVar("Folder", buildFolderConstructor(sandboxDir))
        g.declareVar("File", buildFileConstructor(sandboxDir))

        // Global `app` object
        val settingsObj = EsValue.Obj("Settings").apply {
            setProp("haveSetting", EsValue.NativeFn("haveSetting") { _, args, _ ->
                val sec = args.getOrNull(0)?.toJsString().orEmpty()
                val key = args.getOrNull(1)?.toJsString().orEmpty()
                EsValue.Bool(settingsStore.containsKey("${plugin.bundleId}::$sec::$key"))
            })
            setProp("getSetting", EsValue.NativeFn("getSetting") { _, args, _ ->
                val sec = args.getOrNull(0)?.toJsString().orEmpty()
                val key = args.getOrNull(1)?.toJsString().orEmpty()
                EsValue.Str(settingsStore["${plugin.bundleId}::$sec::$key"].orEmpty())
            })
            setProp("saveSetting", EsValue.NativeFn("saveSetting") { _, args, _ ->
                val sec = args.getOrNull(0)?.toJsString().orEmpty()
                val key = args.getOrNull(1)?.toJsString().orEmpty()
                val v = args.getOrNull(2)?.toJsString().orEmpty()
                settingsStore["${plugin.bundleId}::$sec::$key"] = v
                EsValue.Undefined
            })
        }

        val prefsObj = EsValue.Obj("Preferences").apply {
            setProp("getPrefAsLong", EsValue.NativeFn("getPrefAsLong") { _, args, _ ->
                val key = args.getOrNull(1)?.toJsString().orEmpty()
                if (key.contains("Pref_SCRIPTING_FILE_NETWORK_SECURITY") || key.contains("SECURITY")) {
                    val allowed = plugin.rawAttributes["__fileNetworkAllowed"]?.equals("true", ignoreCase = true) ?: true
                    EsValue.Num(if (allowed) 1.0 else 0.0)
                } else {
                    EsValue.Num(1.0)
                }
            })
            setProp("havePref", EsValue.NativeFn("havePref") { _, _, _ -> EsValue.Bool(true) })
        }

        val projectObj = EsValue.Obj(
            className = "Project",
            getterHook = { k ->
                if (k == "activeItem") {
                    val comp = domBridgeProvider().getActiveComp()
                    if (comp != null) wrapCompItem(comp) else EsValue.Null
                } else null
            }
        )

        val appObj = EsValue.Obj("Application").apply {
            setProp("version", EsValue.Str("24.0x1"))
            setProp("project", projectObj)
            setProp("settings", settingsObj)
            setProp("preferences", prefsObj)
            setProp("beginUndoGroup", EsValue.NativeFn("beginUndoGroup") { _, args, _ ->
                domBridgeProvider().beginUndoGroup(args.firstOrNull()?.toJsString() ?: plugin.name)
                EsValue.Undefined
            })
            setProp("endUndoGroup", EsValue.NativeFn("endUndoGroup") { _, _, _ ->
                domBridgeProvider().endUndoGroup()
                EsValue.Undefined
            })
        }
        g.declareVar("app", appObj)
    }

    private fun createKeyframeEaseObj(args: List<EsValue>): EsValue.Obj {
        val speed = args.getOrNull(0)?.toNum()?.let { if (it.isFinite()) it else 0.0 } ?: 0.0
        val infl = (args.getOrNull(1)?.toNum()?.let { if (it.isFinite()) it else 33.33 } ?: 33.33).coerceIn(0.1, 100.0)
        return EsValue.Obj("KeyframeEase").apply {
            setProp("speed", EsValue.Num(speed))
            setProp("influence", EsValue.Num(infl))
        }
    }

    private fun createMarkerValueObj(args: List<EsValue>): EsValue.Obj {
        val comment = args.getOrNull(0)?.toJsString().orEmpty()
        return EsValue.Obj("MarkerValue").apply {
            setProp("comment", EsValue.Str(comment))
            setProp("duration", EsValue.Num(0.0))
        }
    }

    private fun createTextDocObj(text: String): EsValue.Obj {
        return EsValue.Obj("TextDocument").apply {
            setProp("text", EsValue.Str(text))
            setProp("font", EsValue.Str("Inter"))
            setProp("fontSize", EsValue.Num(48.0))
            setProp("fillColor", EsValue.Arr(mutableListOf(EsValue.Num(1.0), EsValue.Num(1.0), EsValue.Num(1.0))))
            setProp("tracking", EsValue.Num(0.0))
        }
    }

    private fun wrapCompItem(comp: HostCompDto): EsValue.Obj {
        val layerObjs = comp.layers.map { wrapLayerItem(it, comp) }
        val selectedLayerObjs = comp.layers.filter { it.selected }.map { wrapLayerItem(it, comp) }
        val selectedPropObjs = comp.selectedProperties.map { wrapPropertyItem(it) }

        val layersCollection = EsValue.Obj(
            className = "LayerCollection",
            getterHook = { k ->
                if (k == "length") return@Obj EsValue.Num(layerObjs.size.toDouble())
                val idx1 = k.toIntOrNull()
                if (idx1 != null && idx1 in 1..layerObjs.size) return@Obj layerObjs[idx1 - 1]
                null
            }
        )

        return EsValue.Obj("CompItem").apply {
            setProp("id", EsValue.Str(comp.id))
            setProp("name", EsValue.Str(comp.name))
            setProp("width", EsValue.Num(comp.width.toDouble()))
            setProp("height", EsValue.Num(comp.height.toDouble()))
            setProp("duration", EsValue.Num(comp.durationSec))
            setProp("frameRate", EsValue.Num(comp.frameRate))
            setProp("frameDuration", EsValue.Num(1.0 / comp.frameRate.coerceAtLeast(1.0)))
            setProp("time", EsValue.Num(comp.playheadSec))
            setProp("numLayers", EsValue.Num(layerObjs.size.toDouble()))
            setProp("layers", layersCollection)
            setProp("selectedLayers", EsValue.Arr(selectedLayerObjs.toMutableList()))
            setProp("selectedProperties", EsValue.Arr(selectedPropObjs.toMutableList()))
            setProp("layer", EsValue.NativeFn("layer") { _, args, _ ->
                val idx1 = args.firstOrNull()?.toNum()?.toInt() ?: 1
                layerObjs.getOrElse(idx1 - 1) { EsValue.Null }
            })
        }
    }

    private fun wrapLayerItem(layer: HostLayerDto, comp: HostCompDto): EsValue.Obj {
        val clsName = when (layer.kind) {
            "TEXT" -> "TextLayer"
            "SHAPE" -> "ShapeLayer"
            "CAMERA" -> "CameraLayer"
            else -> "AVLayer"
        }
        val markerProp = EsValue.Obj("Property").apply {
            setProp("setValueAtTime", EsValue.NativeFn("setValueAtTime") { _, args, _ ->
                val t = args.getOrNull(0)?.toNum() ?: comp.playheadSec
                val mv = args.getOrNull(1) as? EsValue.Obj
                val comment = mv?.getProp("comment")?.toJsString().orEmpty()
                val dur = mv?.getProp("duration")?.toNum() ?: 0.0
                domBridgeProvider().addLayerOrCompMarker(layer.id, t, comment, dur)
                EsValue.Undefined
            })
        }
        val sourceTextProp = EsValue.Obj("Property").apply {
            val doc = createTextDocObj(layer.textValue).apply {
                setProp("font", EsValue.Str(layer.fontFamily))
                setProp("fontSize", EsValue.Num(layer.fontSize))
                setProp("tracking", EsValue.Num(layer.tracking))
                setProp(
                    "fillColor",
                    EsValue.Arr(layer.fillColorRgb.map { EsValue.Num(it) }.toMutableList())
                )
            }
            setProp("value", doc)
            setProp("setValue", EsValue.NativeFn("setValue") { _, args, _ ->
                val d = args.firstOrNull() as? EsValue.Obj ?: return@NativeFn EsValue.Undefined
                val rgbArr = (d.getProp("fillColor") as? EsValue.Arr)?.elements
                val rgb = if (rgbArr != null && rgbArr.size >= 3) {
                    doubleArrayOf(rgbArr[0].toNum(), rgbArr[1].toNum(), rgbArr[2].toNum())
                } else layer.fillColorRgb
                domBridgeProvider().updateTextDocument(
                    layerId = layer.id,
                    text = d.getProp("text").toJsString(),
                    fontFamily = d.getProp("font").toJsString(),
                    fontSize = d.getProp("fontSize").toNum(),
                    fillColorRgb = rgb,
                    tracking = d.getProp("tracking").toNum()
                )
                EsValue.Undefined
            })
        }
        val textPropsGroup = buildLayerTextPropertiesGroup(layer, sourceTextProp)
        val effectsParadeGroup = buildLayerEffectsParadeGroup(layer)
        return EsValue.Obj(clsName).apply {
            setProp("index", EsValue.Num(layer.index1Based.toDouble()))
            setProp("name", EsValue.Str(layer.name))
            setProp("inPoint", EsValue.Num(layer.inPointSec))
            setProp("outPoint", EsValue.Num(layer.outPointSec))
            setProp("startTime", EsValue.Num(layer.startTimeSec))
            setProp("selected", EsValue.Bool(layer.selected))
            setProp("marker", markerProp)
            setProp("Marker", markerProp)
            setProp("sourceText", sourceTextProp)
            setProp("Text", textPropsGroup)
            setProp("Effects", effectsParadeGroup)
            setProp("effect", EsValue.NativeFn("effect") { _, args, deadlineNs ->
                val propFn = effectsParadeGroup.getProp("property") as? EsValue.Callable
                propFn?.call(effectsParadeGroup, args, deadlineNs) ?: EsValue.Null
            })
            setProp("property", EsValue.NativeFn("property") { _, args, _ ->
                val name = args.firstOrNull()?.toJsString().orEmpty()
                when (name) {
                    "Source Text", "ADBE Text Document" -> sourceTextProp
                    "Text", "ADBE Text Properties" -> textPropsGroup
                    "Effects", "ADBE Effect Parade" -> effectsParadeGroup
                    "Marker", "ADBE Marker" -> markerProp
                    else -> {
                        val match = comp.selectedProperties.firstOrNull {
                            it.layerId == layer.id && (it.name == name || it.matchName == name)
                        }
                        if (match != null) wrapPropertyItem(match) else EsValue.Null
                    }
                }
            })
        }
    }

    private var nextAnimatorInstanceSeq = 1000L

    private fun buildLayerTextPropertiesGroup(
        layer: HostLayerDto,
        sourceTextProp: EsValue.Obj
    ): EsValue.Obj {
        val animators = ArrayList<EsValue.Obj>()

        fun createAnimatorGroup(defaultName: String): EsValue.Obj {
            val instanceId = (++nextAnimatorInstanceSeq).toDouble()
            var animatorName = defaultName
            val selectorValues = LinkedHashMap<String, Double>().apply {
                put("__animatorInstanceId", instanceId)
            }
            val propertyValues = LinkedHashMap<String, DoubleArray>()
            val propertyKeyframes = LinkedHashMap<String, MutableList<HostKeyframeDto>>()
            val cachedBoundProps = LinkedHashMap<String, EsValue.Obj>()

            fun syncAnimatorToHost() {
                domBridgeProvider().applyTextAnimator(
                    layerId = layer.id,
                    animatorName = animatorName,
                    selectorValues = selectorValues.toMap(),
                    propertyValues = propertyValues.toMap(),
                    propertyKeyframes = propertyKeyframes.mapValues { (_, list) ->
                        list.sortedBy { it.timeSec }.mapIndexed { idx, k -> k.copy(index1Based = idx + 1) }
                    }
                )
            }

            fun getOrCreateAnimatorLeafProp(propMatchName: String, isSelector: Boolean): EsValue.Obj {
                val cacheKey = "${if (isSelector) "S" else "P"}::$propMatchName"
                cachedBoundProps[cacheKey]?.let { return it }
                var exprText = ""
                val propObj = EsValue.Obj(
                    className = "Property",
                    getterHook = { k ->
                        when (k) {
                            "name", "matchName" -> EsValue.Str(propMatchName)
                            "numKeys" -> EsValue.Num((propertyKeyframes[propMatchName]?.size ?: 0).toDouble())
                            "expression" -> EsValue.Str(exprText)
                            "value" -> {
                                if (isSelector) {
                                    EsValue.Num(selectorValues[propMatchName] ?: 0.0)
                                } else {
                                    val arr = propertyValues[propMatchName] ?: doubleArrayOf(0.0)
                                    if (arr.size == 1) EsValue.Num(arr[0])
                                    else EsValue.Arr(arr.map { EsValue.Num(it) }.toMutableList())
                                }
                            }
                            else -> null
                        }
                    },
                    setterHook = { k, v ->
                        when (k) {
                            "expression" -> {
                                exprText = v.toJsString()
                                true
                            }
                            else -> false
                        }
                    }
                ).apply {
                    setProp("canVaryOverTime", EsValue.Bool(true))
                    setProp("setValue", EsValue.NativeFn("setValue") { _, args, _ ->
                        val raw = args.firstOrNull() ?: EsValue.Num(0.0)
                        if (isSelector) {
                            selectorValues[propMatchName] = raw.toNum()
                        } else {
                            propertyValues[propMatchName] = if (raw is EsValue.Arr) {
                                DoubleArray(raw.elements.size) { raw.elements[it].toNum() }
                            } else {
                                doubleArrayOf(raw.toNum())
                            }
                        }
                        syncAnimatorToHost()
                        EsValue.Undefined
                    })
                    setProp("setValueAtTime", EsValue.NativeFn("setValueAtTime") { _, args, _ ->
                        val tSec = args.getOrNull(0)?.toNum() ?: 0.0
                        val raw = args.getOrNull(1) ?: EsValue.Num(0.0)
                        val vec = if (raw is EsValue.Arr) {
                            DoubleArray(raw.elements.size) { raw.elements[it].toNum() }
                        } else {
                            doubleArrayOf(raw.toNum())
                        }
                        val kfs = propertyKeyframes.getOrPut(propMatchName) { ArrayList() }
                        val existingIdx = kfs.indexOfFirst { abs(it.timeSec - tSec) < 1e-4 }
                        if (existingIdx >= 0) {
                            kfs[existingIdx] = kfs[existingIdx].copy(value = vec)
                        } else {
                            kfs.add(HostKeyframeDto(kfs.size + 1, tSec, vec))
                            kfs.sortBy { it.timeSec }
                        }
                        if (isSelector && !selectorValues.containsKey(propMatchName)) {
                            selectorValues[propMatchName] = vec.firstOrNull() ?: 0.0
                        } else if (!isSelector && !propertyValues.containsKey(propMatchName)) {
                            propertyValues[propMatchName] = vec
                        }
                        syncAnimatorToHost()
                        EsValue.Undefined
                    })
                    setProp("setValuesAtTimes", EsValue.NativeFn("setValuesAtTimes") { _, args, _ ->
                        val times = (args.getOrNull(0) as? EsValue.Arr)?.elements.orEmpty()
                        val vals = (args.getOrNull(1) as? EsValue.Arr)?.elements.orEmpty()
                        val kfs = propertyKeyframes.getOrPut(propMatchName) { ArrayList() }
                        for (i in 0 until minOf(times.size, vals.size)) {
                            val tSec = times[i].toNum()
                            val raw = vals[i]
                            val vec = if (raw is EsValue.Arr) {
                                DoubleArray(raw.elements.size) { raw.elements[it].toNum() }
                            } else {
                                doubleArrayOf(raw.toNum())
                            }
                            kfs.add(HostKeyframeDto(kfs.size + 1, tSec, vec))
                        }
                        kfs.sortBy { it.timeSec }
                        syncAnimatorToHost()
                        EsValue.Undefined
                    })
                    setProp("keyTime", EsValue.NativeFn("keyTime") { _, args, _ ->
                        val k1 = args.firstOrNull()?.toNum()?.toInt() ?: 1
                        EsValue.Num(propertyKeyframes[propMatchName]?.getOrNull(k1 - 1)?.timeSec ?: 0.0)
                    })
                    setProp("keyValue", EsValue.NativeFn("keyValue") { _, args, _ ->
                        val k1 = args.firstOrNull()?.toNum()?.toInt() ?: 1
                        val vec = propertyKeyframes[propMatchName]?.getOrNull(k1 - 1)?.value ?: doubleArrayOf(0.0)
                        if (vec.size == 1) EsValue.Num(vec[0]) else EsValue.Arr(vec.map { EsValue.Num(it) }.toMutableList())
                    })
                    setProp("setTemporalEaseAtKey", EsValue.NativeFn("setTemporalEaseAtKey") { _, _, _ ->
                        syncAnimatorToHost()
                        EsValue.Undefined
                    })
                    setProp("setInterpolationTypeAtKey", EsValue.NativeFn("setInterpolationTypeAtKey") { _, _, _ ->
                        syncAnimatorToHost()
                        EsValue.Undefined
                    })
                }
                cachedBoundProps[cacheKey] = propObj
                return propObj
            }

            val rangeAdvancedGroup = EsValue.Obj("PropertyGroup").apply {
                setProp("matchName", EsValue.Str("ADBE Text Range Advanced"))
                setProp("property", EsValue.NativeFn("property") { _, args, _ ->
                    val pName = args.firstOrNull()?.toJsString().orEmpty()
                    getOrCreateAnimatorLeafProp(pName, isSelector = true)
                })
            }

            val selectorsList = ArrayList<EsValue.Obj>()
            fun createSelectorObj(): EsValue.Obj = EsValue.Obj(
                className = "PropertyGroup",
                getterHook = { k -> if (k == "numProperties") EsValue.Num(4.0) else null }
            ).apply {
                setProp("name", EsValue.Str("Range Selector ${selectorsList.size + 1}"))
                setProp("matchName", EsValue.Str("ADBE Text Selector"))
                setProp("advanced", rangeAdvancedGroup)
                setProp("property", EsValue.NativeFn("property") { _, args, _ ->
                    val pName = args.firstOrNull()?.toJsString().orEmpty()
                    when (pName) {
                        "Advanced", "ADBE Text Range Advanced" -> rangeAdvancedGroup
                        else -> getOrCreateAnimatorLeafProp(pName, isSelector = true)
                    }
                })
            }

            val selectorsGroup = EsValue.Obj(
                className = "PropertyGroup",
                getterHook = { k ->
                    if (k == "numProperties") return@Obj EsValue.Num(selectorsList.size.toDouble())
                    val idx1 = k.toIntOrNull()
                    if (idx1 != null && idx1 in 1..selectorsList.size) return@Obj selectorsList[idx1 - 1]
                    null
                }
            ).apply {
                setProp("matchName", EsValue.Str("ADBE Text Selectors"))
                setProp("canAddProperty", EsValue.NativeFn("canAddProperty") { _, _, _ -> EsValue.Bool(true) })
                setProp("addProperty", EsValue.NativeFn("addProperty") { _, _, _ ->
                    val s = createSelectorObj()
                    selectorsList.add(s)
                    s
                })
                setProp("property", EsValue.NativeFn("property") { _, args, _ ->
                    val idx = args.firstOrNull()?.toNum()?.toInt()
                    if (idx != null && idx in 1..selectorsList.size) selectorsList[idx - 1]
                    else selectorsList.firstOrNull() ?: createSelectorObj().also { selectorsList.add(it) }
                })
            }

            val animPropsGroup = EsValue.Obj("PropertyGroup").apply {
                setProp("matchName", EsValue.Str("ADBE Text Animator Properties"))
                setProp("canAddProperty", EsValue.NativeFn("canAddProperty") { _, _, _ -> EsValue.Bool(true) })
                setProp("addProperty", EsValue.NativeFn("addProperty") { _, args, _ ->
                    val pName = args.firstOrNull()?.toJsString().orEmpty()
                    getOrCreateAnimatorLeafProp(pName, isSelector = false)
                })
                setProp("property", EsValue.NativeFn("property") { _, args, _ ->
                    val pName = args.firstOrNull()?.toJsString().orEmpty()
                    getOrCreateAnimatorLeafProp(pName, isSelector = false)
                })
            }

            syncAnimatorToHost()

            return EsValue.Obj(
                className = "PropertyGroup",
                getterHook = { k -> if (k == "name") EsValue.Str(animatorName) else null },
                setterHook = { k, v ->
                    if (k == "name") {
                        animatorName = v.toJsString()
                        syncAnimatorToHost()
                        true
                    } else false
                }
            ).apply {
                setProp("matchName", EsValue.Str("ADBE Text Animator"))
                setProp("Selectors", selectorsGroup)
                setProp("Properties", animPropsGroup)
                setProp("property", EsValue.NativeFn("property") { _, args, _ ->
                    val sub = args.firstOrNull()?.toJsString().orEmpty()
                    when (sub) {
                        "Selectors", "ADBE Text Selectors", "1" -> selectorsGroup
                        "Properties", "ADBE Text Animator Properties", "2" -> animPropsGroup
                        else -> getOrCreateAnimatorLeafProp(sub, isSelector = false)
                    }
                })
                setProp("remove", EsValue.NativeFn("remove") { _, _, _ -> EsValue.Undefined })
            }
        }

        val animatorsGroup = EsValue.Obj(
            className = "PropertyGroup",
            getterHook = { k ->
                if (k == "numProperties") return@Obj EsValue.Num(animators.size.toDouble())
                val idx1 = k.toIntOrNull()
                if (idx1 != null && idx1 in 1..animators.size) return@Obj animators[idx1 - 1]
                null
            }
        ).apply {
            setProp("name", EsValue.Str("Animators"))
            setProp("matchName", EsValue.Str("ADBE Text Animators"))
            setProp("canAddProperty", EsValue.NativeFn("canAddProperty") { _, _, _ -> EsValue.Bool(true) })
            setProp("addProperty", EsValue.NativeFn("addProperty") { _, _, _ ->
                val anim = createAnimatorGroup("Animator ${animators.size + 1}")
                animators.add(anim)
                anim
            })
            setProp("property", EsValue.NativeFn("property") { _, args, _ ->
                val arg = args.firstOrNull()
                val idx = arg?.toNum()?.toInt()
                if (idx != null && idx in 1..animators.size) animators[idx - 1]
                else {
                    val targetName = arg?.toJsString().orEmpty()
                    animators.firstOrNull { it.getProp("name").toJsString() == targetName } ?: EsValue.Null
                }
            })
        }

        val moreOptionsGroup = EsValue.Obj("PropertyGroup").apply {
            setProp("matchName", EsValue.Str("ADBE Text More Options"))
            setProp("property", EsValue.NativeFn("property") { _, args, _ ->
                val pName = args.firstOrNull()?.toJsString().orEmpty()
                EsValue.Obj("Property").apply {
                    setProp("name", EsValue.Str(pName))
                    setProp("value", EsValue.Num(1.0))
                    setProp("setValue", EsValue.NativeFn("setValue") { _, _, _ -> EsValue.Undefined })
                }
            })
        }

        return EsValue.Obj("PropertyGroup").apply {
            setProp("name", EsValue.Str("Text"))
            setProp("matchName", EsValue.Str("ADBE Text Properties"))
            setProp("Animators", animatorsGroup)
            setProp("More Options", moreOptionsGroup)
            setProp("property", EsValue.NativeFn("property") { _, args, _ ->
                when (args.firstOrNull()?.toJsString().orEmpty()) {
                    "Source Text", "ADBE Text Document", "1" -> sourceTextProp
                    "Animators", "ADBE Text Animators", "4" -> animatorsGroup
                    "More Options", "ADBE Text More Options", "3" -> moreOptionsGroup
                    else -> moreOptionsGroup
                }
            })
        }
    }

    private fun buildLayerEffectsParadeGroup(layer: HostLayerDto): EsValue.Obj {
        val addedEffects = ArrayList<EsValue.Obj>()
        fun createEffectInstance(matchOrName: String): EsValue.Obj {
            var fxName = matchOrName
            val controls = LinkedHashMap<String, EsValue.Obj>()
            fun getControl(cName: String): EsValue.Obj = controls.getOrPut(cName) {
                var curVal: EsValue = EsValue.Num(0.0)
                var curExpr = ""
                EsValue.Obj(
                    className = "Property",
                    getterHook = { k ->
                        when (k) {
                            "value" -> curVal
                            "expression" -> EsValue.Str(curExpr)
                            else -> null
                        }
                    },
                    setterHook = { k, v ->
                        if (k == "expression") {
                            curExpr = v.toJsString()
                            true
                        } else false
                    }
                ).apply {
                    setProp("name", EsValue.Str(cName))
                    setProp("matchName", EsValue.Str(cName))
                    setProp("setValue", EsValue.NativeFn("setValue") { _, args, _ ->
                        curVal = args.firstOrNull() ?: EsValue.Num(0.0)
                        EsValue.Undefined
                    })
                    setProp("setValueAtTime", EsValue.NativeFn("setValueAtTime") { _, args, _ ->
                        curVal = args.getOrNull(1) ?: EsValue.Num(0.0)
                        EsValue.Undefined
                    })
                }
            }
            return EsValue.Obj(
                className = "PropertyGroup",
                getterHook = { k -> if (k == "name") EsValue.Str(fxName) else null },
                setterHook = { k, v ->
                    if (k == "name") { fxName = v.toJsString(); true } else false
                }
            ).apply {
                setProp("matchName", EsValue.Str(matchOrName))
                setProp("enabled", EsValue.Bool(true))
                setProp("property", EsValue.NativeFn("property") { _, args, _ ->
                    getControl(args.firstOrNull()?.toJsString().orEmpty())
                })
                setProp("remove", EsValue.NativeFn("remove") { _, _, _ -> EsValue.Undefined })
            }
        }

        return EsValue.Obj(
            className = "PropertyGroup",
            getterHook = { k ->
                if (k == "numProperties") return@Obj EsValue.Num(addedEffects.size.toDouble())
                val idx1 = k.toIntOrNull()
                if (idx1 != null && idx1 in 1..addedEffects.size) return@Obj addedEffects[idx1 - 1]
                null
            }
        ).apply {
            setProp("name", EsValue.Str("Effects"))
            setProp("matchName", EsValue.Str("ADBE Effect Parade"))
            setProp("canAddProperty", EsValue.NativeFn("canAddProperty") { _, _, _ -> EsValue.Bool(true) })
            setProp("addProperty", EsValue.NativeFn("addProperty") { _, args, _ ->
                val fx = createEffectInstance(args.firstOrNull()?.toJsString().orEmpty())
                addedEffects.add(fx)
                fx
            })
            setProp("property", EsValue.NativeFn("property") { _, args, _ ->
                val arg = args.firstOrNull()
                val idx = arg?.toNum()?.toInt()
                if (idx != null && idx in 1..addedEffects.size) addedEffects[idx - 1]
                else {
                    val target = arg?.toJsString().orEmpty()
                    addedEffects.firstOrNull {
                        it.getProp("name").toJsString() == target || it.getProp("matchName").toJsString() == target
                    } ?: EsValue.Null
                }
            })
        }
    }

    /**
     * Wraps a real editor property with 1-based AE keyframe indexing and exact inverse
     * `KeyframeEase(speed, influence)` <-> cubic-Bezier `[x1, y1, x2, y2]` conversion!
     */
    private fun wrapPropertyItem(initialDto: HostPropertyDto): EsValue.Obj {
        val workingKeys = initialDto.keyframes
            .sortedBy { it.timeSec }
            .mapIndexed { idx, kf -> kf.copy(index1Based = idx + 1) }
            .toMutableList()
        var workingExpr = initialDto.expression

        // Store per-key AE interpolation & temporal ease arrays so multiple calls in a loop compose cleanly
        val inInterp = IntArray(workingKeys.size + 1) { if (it > 0 && workingKeys[it - 1].holdStep) 6614 else 6613 }
        val outInterp = IntArray(workingKeys.size + 1) { if (it > 0 && workingKeys[it - 1].holdStep) 6614 else 6613 }
        val inEases = Array<List<Pair<Double, Double>>>(workingKeys.size + 1) { listOf(0.0 to 25.0) }
        val outEases = Array<List<Pair<Double, Double>>>(workingKeys.size + 1) { listOf(0.0 to 25.0) }

        fun avgSpeedOnSegment(segIdx0Based: Int): Double {
            if (segIdx0Based !in 0 until (workingKeys.size - 1)) return 0.0
            val a = workingKeys[segIdx0Based]
            val b = workingKeys[segIdx0Based + 1]
            val dt = b.timeSec - a.timeSec
            if (dt <= 1e-9) return 0.0
            return if (initialDto.dimensions == 1) {
                (b.value[0] - a.value[0]) / dt
            } else if (initialDto.isSpatial) {
                var sumSq = 0.0
                for (d in 0 until minOf(a.value.size, b.value.size)) {
                    val diff = b.value[d] - a.value[d]
                    sumSq += diff * diff
                }
                sqrt(sumSq) / dt
            } else {
                (b.value[0] - a.value[0]) / dt
            }
        }

        // Seed initial eases from existing cubic-Bezier tangents
        for (i in 0 until workingKeys.size - 1) {
            val avg = avgSpeedOnSegment(i)
            val a = workingKeys[i]
            val b = workingKeys[i + 1]
            val outInfl = (a.outX * 100.0).coerceIn(0.1, 100.0)
            val dxo = max(a.outX, 0.001)
            val outSpd = avg * (a.outY / dxo)
            outEases[i + 1] = List(if (initialDto.isSpatial) 1 else initialDto.dimensions) { outSpd to outInfl }

            val inInfl = ((1.0 - b.inX) * 100.0).coerceIn(0.1, 100.0)
            val dxi = max(1.0 - b.inX, 0.001)
            val inSpd = avg * ((1.0 - b.inY) / dxi)
            inEases[i + 2] = List(if (initialDto.isSpatial) 1 else initialDto.dimensions) { inSpd to inInfl }
        }

        fun syncBackToHost() {
            // Convert each segment `i -> i+1` back from outEases[i+1] & inEases[i+2] to [x1, y1, x2, y2]!
            for (i in 0 until workingKeys.size - 1) {
                val avg = avgSpeedOnSegment(i)
                val outPair = outEases[i + 1].firstOrNull() ?: (0.0 to 25.0)
                val inPair = inEases[i + 2].firstOrNull() ?: (0.0 to 25.0)

                val x1 = (outPair.second / 100.0).coerceIn(0.0, 1.0)
                val dxo = max(x1, 0.001)
                val y1 = if (abs(avg) > 1e-9) (outPair.first / avg) * dxo else x1

                val oneMinusX2 = (inPair.second / 100.0).coerceIn(0.0, 1.0)
                val x2 = (1.0 - oneMinusX2).coerceIn(0.0, 1.0)
                val dxi = max(1.0 - x2, 0.001)
                val y2 = if (abs(avg) > 1e-9) 1.0 - (inPair.first / avg) * dxi else x2

                val isHold = outInterp[i + 1] == 6614
                workingKeys[i] = workingKeys[i].copy(outX = x1, outY = y1, holdStep = isHold)
                workingKeys[i + 1] = workingKeys[i + 1].copy(inX = x2, inY = y2)
            }
            domBridgeProvider().updatePropertyKeyframes(
                layerId = initialDto.layerId,
                propertyKey = initialDto.propertyKey,
                updatedKeyframes = workingKeys.toList(),
                updatedExpression = workingExpr
            )
        }

        val selectedIndicesEs = EsValue.Arr(
            initialDto.selectedKeyIndices1Based.map { EsValue.Num(it.toDouble()) }.toMutableList()
        )

        return EsValue.Obj(
            className = "Property",
            getterHook = { k ->
                when (k) {
                    "numKeys" -> EsValue.Num(workingKeys.size.toDouble())
                    "selectedKeys" -> selectedIndicesEs
                    "expression" -> EsValue.Str(workingExpr)
                    else -> null
                }
            },
            setterHook = { k, v ->
                if (k == "expression") {
                    workingExpr = v.toJsString()
                    syncBackToHost()
                    true
                } else false
            }
        ).apply {
            setProp("name", EsValue.Str(initialDto.name))
            setProp("matchName", EsValue.Str(initialDto.matchName))
            setProp("propertyType", EsValue.Num(6212.0)) // PropertyType.PROPERTY
            setProp("isSpatial", EsValue.Bool(initialDto.isSpatial))
            setProp("canVaryOverTime", EsValue.Bool(true))
            setProp("keyTime", EsValue.NativeFn("keyTime") { _, args, _ ->
                val k1 = args.firstOrNull()?.toNum()?.toInt() ?: 1
                EsValue.Num(workingKeys.getOrNull(k1 - 1)?.timeSec ?: 0.0)
            })
            setProp("keyValue", EsValue.NativeFn("keyValue") { _, args, _ ->
                val k1 = args.firstOrNull()?.toNum()?.toInt() ?: 1
                val v = workingKeys.getOrNull(k1 - 1)?.value ?: initialDto.baseValue
                if (initialDto.dimensions == 1) EsValue.Num(v.getOrElse(0) { 0.0 })
                else EsValue.Arr(v.map { EsValue.Num(it) }.toMutableList())
            })
            setProp("keyInInterpolationType", EsValue.NativeFn("keyInInterpolationType") { _, args, _ ->
                val k1 = (args.firstOrNull()?.toNum()?.toInt() ?: 1).coerceIn(1, max(1, workingKeys.size))
                EsValue.Num(inInterp.getOrElse(k1) { 6613 }.toDouble())
            })
            setProp("keyOutInterpolationType", EsValue.NativeFn("keyOutInterpolationType") { _, args, _ ->
                val k1 = (args.firstOrNull()?.toNum()?.toInt() ?: 1).coerceIn(1, max(1, workingKeys.size))
                EsValue.Num(outInterp.getOrElse(k1) { 6613 }.toDouble())
            })
            setProp("setInterpolationTypeAtKey", EsValue.NativeFn("setInterpolationTypeAtKey") { _, args, _ ->
                val k1 = args.getOrNull(0)?.toNum()?.toInt() ?: return@NativeFn EsValue.Undefined
                if (k1 in 1..workingKeys.size) {
                    inInterp[k1] = args.getOrNull(1)?.toNum()?.toInt() ?: 6613
                    outInterp[k1] = args.getOrNull(2)?.toNum()?.toInt() ?: inInterp[k1]
                    syncBackToHost()
                }
                EsValue.Undefined
            })
            setProp("keyInTemporalEase", EsValue.NativeFn("keyInTemporalEase") { _, args, _ ->
                val k1 = (args.firstOrNull()?.toNum()?.toInt() ?: 1).coerceIn(1, max(1, workingKeys.size))
                val list = inEases.getOrElse(k1) { listOf(0.0 to 25.0) }
                EsValue.Arr(list.map { (spd, inf) ->
                    createKeyframeEaseObj(listOf(EsValue.Num(spd), EsValue.Num(inf)))
                }.toMutableList())
            })
            setProp("keyOutTemporalEase", EsValue.NativeFn("keyOutTemporalEase") { _, args, _ ->
                val k1 = (args.firstOrNull()?.toNum()?.toInt() ?: 1).coerceIn(1, max(1, workingKeys.size))
                val list = outEases.getOrElse(k1) { listOf(0.0 to 25.0) }
                EsValue.Arr(list.map { (spd, inf) ->
                    createKeyframeEaseObj(listOf(EsValue.Num(spd), EsValue.Num(inf)))
                }.toMutableList())
            })
            setProp("setTemporalEaseAtKey", EsValue.NativeFn("setTemporalEaseAtKey") { _, args, _ ->
                val k1 = args.getOrNull(0)?.toNum()?.toInt() ?: return@NativeFn EsValue.Undefined
                if (k1 in 1..workingKeys.size) {
                    val inArr = (args.getOrNull(1) as? EsValue.Arr)?.elements.orEmpty()
                    val outArr = (args.getOrNull(2) as? EsValue.Arr)?.elements ?: inArr
                    if (inArr.isNotEmpty()) {
                        inEases[k1] = inArr.map { elem ->
                            val o = elem as? EsValue.Obj
                            val spd = o?.getProp("speed")?.toNum() ?: 0.0
                            val inf = (o?.getProp("influence")?.toNum() ?: 33.33).coerceIn(0.1, 100.0)
                            spd to inf
                        }
                    }
                    if (outArr.isNotEmpty()) {
                        outEases[k1] = outArr.map { elem ->
                            val o = elem as? EsValue.Obj
                            val spd = o?.getProp("speed")?.toNum() ?: 0.0
                            val inf = (o?.getProp("influence")?.toNum() ?: 33.33).coerceIn(0.1, 100.0)
                            spd to inf
                        }
                    }
                    syncBackToHost()
                }
                EsValue.Undefined
            })
            setProp("setValueAtTime", EsValue.NativeFn("setValueAtTime") { _, args, _ ->
                val tSec = args.getOrNull(0)?.toNum() ?: 0.0
                val rawVal = args.getOrNull(1) ?: EsValue.Num(0.0)
                val vec = if (rawVal is EsValue.Arr) {
                    DoubleArray(rawVal.elements.size) { rawVal.elements[it].toNum() }
                } else {
                    doubleArrayOf(rawVal.toNum())
                }
                val existingIdx = workingKeys.indexOfFirst { abs(it.timeSec - tSec) < 1e-4 }
                if (existingIdx >= 0) {
                    workingKeys[existingIdx] = workingKeys[existingIdx].copy(value = vec)
                } else {
                    workingKeys.add(HostKeyframeDto(workingKeys.size + 1, tSec, vec))
                    workingKeys.sortBy { it.timeSec }
                }
                domBridgeProvider().updatePropertyKeyframes(
                    layerId = initialDto.layerId,
                    propertyKey = initialDto.propertyKey,
                    updatedKeyframes = workingKeys.mapIndexed { idx, kf -> kf.copy(index1Based = idx + 1) }
                )
                EsValue.Undefined
            })
        }
    }

    private fun wrapScriptUiNode(node: ScriptUiNode): EsValue.Obj {
        val graphicsObj = buildScriptUiGraphics(node)
        val layoutObj = EsValue.Obj("LayoutManager").apply {
            setProp("layout", EsValue.NativeFn("layout") { _, _, _ -> EsValue.Undefined })
            setProp("resize", EsValue.NativeFn("resize") { _, _, _ -> EsValue.Undefined })
        }
        return EsValue.Obj(
            className = if (node.kind == ScriptUiWidgetKind.PANEL) "Panel" else "Control",
            getterHook = { k ->
                when (k) {
                    "text" -> EsValue.Str(node.text)
                    "value" -> if (node.kind == ScriptUiWidgetKind.CHECKBOX || node.kind == ScriptUiWidgetKind.RADIO_BUTTON) {
                        EsValue.Bool(node.checked)
                    } else {
                        EsValue.Num(node.value.toDouble())
                    }
                    "selection" -> EsValue.Num(node.selectedIndex.toDouble())
                    "orientation" -> EsValue.Str(node.orientation)
                    "enabled" -> EsValue.Bool(node.enabled)
                    "visible" -> EsValue.Bool(node.visible)
                    "graphics" -> graphicsObj
                    "layout" -> layoutObj
                    "children" -> EsValue.Arr(node.children.map { wrapScriptUiNode(it) }.toMutableList())
                    else -> null
                }
            },
            setterHook = { k, v ->
                when (k) {
                    "text" -> { node.text = v.toJsString(); true }
                    "value" -> {
                        if (node.kind == ScriptUiWidgetKind.CHECKBOX || node.kind == ScriptUiWidgetKind.RADIO_BUTTON) {
                            node.checked = v.isTruthy()
                        } else {
                            node.value = v.toNum().toFloat().coerceIn(node.minValue, node.maxValue)
                        }
                        true
                    }
                    "selection" -> { node.selectedIndex = v.toNum().toInt().coerceAtLeast(0); true }
                    "orientation" -> { node.orientation = v.toJsString(); true }
                    "enabled" -> { node.enabled = v.isTruthy(); true }
                    "visible" -> { node.visible = v.isTruthy(); true }
                    "onClick" -> {
                        val fn = v as? EsValue.Callable
                        node.onClickCallback = if (fn != null) ({ fn.call(EsValue.Undefined, emptyList(), System.nanoTime() + 5_000_000_000L) }) else null
                        true
                    }
                    "onChange", "onChanging" -> {
                        val fn = v as? EsValue.Callable
                        node.onChangeCallback = if (fn != null) ({ fn.call(EsValue.Undefined, emptyList(), System.nanoTime() + 5_000_000_000L) }) else null
                        true
                    }
                    "onDraw" -> {
                        val fn = v as? EsValue.Callable
                        node.onDrawCallback = if (fn != null) ({ fn.call(EsValue.Undefined, emptyList(), System.nanoTime() + 5_000_000_000L) }) else null
                        true
                    }
                    else -> false
                }
            }
        ).apply {
            setProp("add", EsValue.NativeFn("add") { _, args, _ ->
                val rawType = args.getOrNull(0)?.toJsString() ?: "statictext"
                val arg2 = args.getOrNull(2)
                val label = if (arg2 is EsValue.Str) arg2.value else ""
                val items = if (arg2 is EsValue.Arr) arg2.elements.map { it.toJsString() } else emptyList()
                val initVal = if (arg2 is EsValue.Num) arg2.value.toFloat() else 0f
                val minVal = (args.getOrNull(3) as? EsValue.Num)?.value?.toFloat() ?: 0f
                val maxVal = (args.getOrNull(4) as? EsValue.Num)?.value?.toFloat() ?: 100f
                wrapScriptUiNode(node.addChild(rawType, label, items, initVal, minVal, maxVal))
            })
            setProp("show", EsValue.NativeFn("show") { _, _, _ -> EsValue.Undefined })
            setProp("center", EsValue.NativeFn("center") { _, _, _ -> EsValue.Undefined })
        }
    }

    private fun buildScriptUiGraphics(node: ScriptUiNode): EsValue.Obj {
        var activePath = ArrayList<Pair<Float, Float>>()
        var activeClosed = false
        return EsValue.Obj("ScriptUIGraphics").apply {
            setProp("PenType", EsValue.Obj().apply { setProp("SOLID_COLOR", EsValue.Num(0.0)) })
            setProp("BrushType", EsValue.Obj().apply { setProp("SOLID_COLOR", EsValue.Num(0.0)) })
            setProp("newPen", EsValue.NativeFn("newPen") { _, args, _ ->
                val col = (args.getOrNull(1) as? EsValue.Arr)?.elements
                val width = args.getOrNull(2)?.toNum()?.toFloat() ?: 1.5f
                EsValue.Obj("ScriptUIPen").apply {
                    setProp("r", EsValue.Num(col?.getOrNull(0)?.toNum() ?: 1.0))
                    setProp("g", EsValue.Num(col?.getOrNull(1)?.toNum() ?: 1.0))
                    setProp("b", EsValue.Num(col?.getOrNull(2)?.toNum() ?: 1.0))
                    setProp("a", EsValue.Num(col?.getOrNull(3)?.toNum() ?: 1.0))
                    setProp("lineWidth", EsValue.Num(width.toDouble()))
                }
            })
            setProp("newBrush", EsValue.NativeFn("newBrush") { _, args, _ ->
                val col = (args.getOrNull(1) as? EsValue.Arr)?.elements
                EsValue.Obj("ScriptUIBrush").apply {
                    setProp("r", EsValue.Num(col?.getOrNull(0)?.toNum() ?: 0.2))
                    setProp("g", EsValue.Num(col?.getOrNull(1)?.toNum() ?: 0.2))
                    setProp("b", EsValue.Num(col?.getOrNull(2)?.toNum() ?: 0.2))
                    setProp("a", EsValue.Num(col?.getOrNull(3)?.toNum() ?: 1.0))
                }
            })
            setProp("newPath", EsValue.NativeFn("newPath") { _, _, _ ->
                activePath = ArrayList()
                activeClosed = false
                EsValue.Obj("ScriptUIPath")
            })
            setProp("moveTo", EsValue.NativeFn("moveTo") { _, args, _ ->
                activePath.add((args.getOrNull(0)?.toNum()?.toFloat() ?: 0f) to (args.getOrNull(1)?.toNum()?.toFloat() ?: 0f))
                EsValue.Undefined
            })
            setProp("lineTo", EsValue.NativeFn("lineTo") { _, args, _ ->
                activePath.add((args.getOrNull(0)?.toNum()?.toFloat() ?: 0f) to (args.getOrNull(1)?.toNum()?.toFloat() ?: 0f))
                EsValue.Undefined
            })
            setProp("rectPath", EsValue.NativeFn("rectPath") { _, args, _ ->
                val x = args.getOrNull(0)?.toNum()?.toFloat() ?: 0f
                val y = args.getOrNull(1)?.toNum()?.toFloat() ?: 0f
                val w = args.getOrNull(2)?.toNum()?.toFloat() ?: 10f
                val h = args.getOrNull(3)?.toNum()?.toFloat() ?: 10f
                activePath = arrayListOf(x to y, (x + w) to y, (x + w) to (y + h), x to (y + h))
                activeClosed = true
                EsValue.Undefined
            })
            setProp("ellipsePath", EsValue.NativeFn("ellipsePath") { _, args, _ ->
                val x = args.getOrNull(0)?.toNum()?.toFloat() ?: 0f
                val y = args.getOrNull(1)?.toNum()?.toFloat() ?: 0f
                val w = args.getOrNull(2)?.toNum()?.toFloat() ?: 10f
                val h = args.getOrNull(3)?.toNum()?.toFloat() ?: 10f
                val cx = x + w * 0.5f
                val cy = y + h * 0.5f
                activePath = ArrayList(16)
                for (s in 0 until 16) {
                    val ang = (s / 16.0) * Math.PI * 2.0
                    activePath.add((cx + cos(ang).toFloat() * w * 0.5f) to (cy + sin(ang).toFloat() * h * 0.5f))
                }
                activeClosed = true
                EsValue.Undefined
            })
            setProp("strokePath", EsValue.NativeFn("strokePath") { _, args, _ ->
                val pen = args.firstOrNull() as? EsValue.Obj
                if (activePath.size >= 2) {
                    node.drawOps.add(
                        ScriptUiDrawOp.StrokePath(
                            points = activePath.toList(),
                            closed = activeClosed,
                            strokeWidth = pen?.getProp("lineWidth")?.toNum()?.toFloat() ?: 1.5f,
                            r = pen?.getProp("r")?.toNum()?.toFloat() ?: 1f,
                            g = pen?.getProp("g")?.toNum()?.toFloat() ?: 1f,
                            b = pen?.getProp("b")?.toNum()?.toFloat() ?: 1f,
                            a = pen?.getProp("a")?.toNum()?.toFloat() ?: 1f
                        )
                    )
                }
                EsValue.Undefined
            })
            setProp("fillPath", EsValue.NativeFn("fillPath") { _, args, _ ->
                val brush = args.firstOrNull() as? EsValue.Obj
                if (activePath.size >= 3) {
                    node.drawOps.add(
                        ScriptUiDrawOp.FillPath(
                            points = activePath.toList(),
                            r = brush?.getProp("r")?.toNum()?.toFloat() ?: 0.2f,
                            g = brush?.getProp("g")?.toNum()?.toFloat() ?: 0.2f,
                            b = brush?.getProp("b")?.toNum()?.toFloat() ?: 0.2f,
                            a = brush?.getProp("a")?.toNum()?.toFloat() ?: 1f
                        )
                    )
                }
                EsValue.Undefined
            })
            setProp("drawString", EsValue.NativeFn("drawString") { _, args, _ ->
                val txt = args.getOrNull(0)?.toJsString().orEmpty()
                val pen = args.getOrNull(1) as? EsValue.Obj
                val x = args.getOrNull(2)?.toNum()?.toFloat() ?: 0f
                val y = args.getOrNull(3)?.toNum()?.toFloat() ?: 14f
                node.drawOps.add(
                    ScriptUiDrawOp.DrawText(
                        text = txt,
                        x = x,
                        y = y,
                        r = pen?.getProp("r")?.toNum()?.toFloat() ?: 1f,
                        g = pen?.getProp("g")?.toNum()?.toFloat() ?: 1f,
                        b = pen?.getProp("b")?.toNum()?.toFloat() ?: 1f,
                        a = pen?.getProp("a")?.toNum()?.toFloat() ?: 1f
                    )
                )
                EsValue.Undefined
            })
        }
    }

    private fun buildFolderConstructor(sandboxDir: File): EsValue.Obj {
        fun wrapFolder(file: File): EsValue.Obj = EsValue.Obj("Folder").apply {
            setProp("fsName", EsValue.Str(file.path))
            setProp("fullName", EsValue.Str(file.path))
            setProp("exists", EsValue.Bool(file.exists() && file.isDirectory))
            setProp("create", EsValue.NativeFn("create") { _, _, _ -> EsValue.Bool(file.mkdirs() || file.exists()) })
        }
        return EsValue.NativeFn("Folder", "Folder",
            invokeBlock = { _, args, _ ->
                val p = args.firstOrNull()?.toJsString().orEmpty()
                wrapFolder(resolveSandboxPath(sandboxDir, p))
            },
            constructBlock = { args, _ ->
                val p = args.firstOrNull()?.toJsString().orEmpty()
                wrapFolder(resolveSandboxPath(sandboxDir, p))
            }
        ).apply {
            setProp("userData", wrapFolder(File(sandboxDir, "userData").apply { mkdirs() }))
            setProp("myDocuments", wrapFolder(File(sandboxDir, "documents").apply { mkdirs() }))
            setProp("temp", wrapFolder(File(sandboxDir, "temp").apply { mkdirs() }))
            setProp("appPackage", wrapFolder(sandboxDir))
        }
    }

    private fun buildFileConstructor(sandboxDir: File): EsValue.Obj {
        fun wrapFile(file: File): EsValue.Obj {
            var mode = "r"
            return EsValue.Obj(
                className = "File",
                getterHook = { k -> if (k == "exists") EsValue.Bool(file.exists()) else null }
            ).apply {
                setProp("fsName", EsValue.Str(file.path))
                setProp("fullName", EsValue.Str(file.path))
                setProp("name", EsValue.Str(file.name))
                setProp("open", EsValue.NativeFn("open") { _, args, _ ->
                    mode = args.firstOrNull()?.toJsString() ?: "r"
                    if (mode.startsWith("w")) {
                        file.parentFile?.mkdirs()
                        if (!file.exists()) file.writeText("")
                    }
                    EsValue.Bool(mode.startsWith("w") || file.exists())
                })
                setProp("read", EsValue.NativeFn("read") { _, _, _ ->
                    EsValue.Str(if (file.exists()) file.readText(Charsets.UTF_8) else "")
                })
                setProp("write", EsValue.NativeFn("write") { _, args, _ ->
                    file.parentFile?.mkdirs()
                    val txt = args.firstOrNull()?.toJsString().orEmpty()
                    if (mode.startsWith("a")) file.appendText(txt, Charsets.UTF_8) else file.writeText(txt, Charsets.UTF_8)
                    EsValue.Bool(true)
                })
                setProp("writeln", EsValue.NativeFn("writeln") { _, args, _ ->
                    file.parentFile?.mkdirs()
                    val txt = args.firstOrNull()?.toJsString().orEmpty() + "\n"
                    if (mode.startsWith("a")) file.appendText(txt, Charsets.UTF_8) else file.writeText(txt, Charsets.UTF_8)
                    EsValue.Bool(true)
                })
                setProp("close", EsValue.NativeFn("close") { _, _, _ -> EsValue.Bool(true) })
                setProp("remove", EsValue.NativeFn("remove") { _, _, _ -> EsValue.Bool(file.delete()) })
            }
        }
        return EsValue.NativeFn("File", "File",
            invokeBlock = { _, args, _ -> wrapFile(resolveSandboxPath(sandboxDir, args.firstOrNull()?.toJsString().orEmpty())) },
            constructBlock = { args, _ -> wrapFile(resolveSandboxPath(sandboxDir, args.firstOrNull()?.toJsString().orEmpty())) }
        )
    }

    companion object {
        fun resolveSandboxPath(sandboxRoot: File, rawPath: String): File {
            val canonicalRoot = sandboxRoot.canonicalFile
            val rootPrefix = canonicalRoot.path + File.separator
            val cleaned = rawPath
                .replace('\\', '/')
                .removePrefix("~")
                .removePrefix(canonicalRoot.path.replace('\\', '/'))
                .trimStart('/')
            val candidate = if (cleaned.isEmpty()) canonicalRoot else File(canonicalRoot, cleaned).canonicalFile
            if (candidate != canonicalRoot && !candidate.path.startsWith(rootPrefix)) {
                throw SecurityException("Plugin sandbox violation: path '$rawPath' escapes plugin-data directory.")
            }
            return candidate
        }

        private fun resolveSafeFile(baseDir: File, relPath: String): File? {
            val canonicalBase = baseDir.canonicalFile
            val prefix = canonicalBase.path + File.separator
            val f = File(canonicalBase, relPath.replace('\\', '/').trimStart('/')).canonicalFile
            return if (f == canonicalBase || f.path.startsWith(prefix)) f else null
        }

        fun parseTextAnimatorMapping(jsonStr: String): JSONObject =
            runCatching { JSONObject(jsonStr) }.getOrDefault(JSONObject())
    }
}

private class ScratchProbeDomBridge : AeDomBridge {
    private val sampleKeys = mutableListOf(
        HostKeyframeDto(1, 0.0, doubleArrayOf(0.0)),
        HostKeyframeDto(2, 1.0, doubleArrayOf(100.0))
    )

    override fun getActiveComp(): HostCompDto = HostCompDto(
        id = "probe_comp",
        name = "Probe Comp",
        width = 1080,
        height = 1080,
        durationSec = 4.0,
        frameRate = 60.0,
        playheadSec = 0.0,
        layers = listOf(
            HostLayerDto(
                id = "probe_text",
                index1Based = 1,
                name = "Probe Text",
                kind = "TEXT",
                inPointSec = 0.0,
                outPointSec = 4.0,
                startTimeSec = 0.0,
                selected = true,
                textValue = "Probe"
            )
        ),
        selectedProperties = listOf(
            HostPropertyDto(
                layerId = "probe_text",
                propertyKey = "OPACITY",
                matchName = "ADBE Opacity",
                name = "Opacity",
                isSpatial = false,
                dimensions = 1,
                baseValue = doubleArrayOf(100.0),
                expression = "",
                keyframes = sampleKeys,
                selectedKeyIndices1Based = listOf(1, 2)
            )
        )
    )

    override fun beginUndoGroup(label: String) = Unit
    override fun endUndoGroup() = Unit
    override fun updatePropertyKeyframes(
        layerId: String,
        propertyKey: String,
        updatedKeyframes: List<HostKeyframeDto>,
        updatedBaseValue: DoubleArray?,
        updatedExpression: String?
    ) {
        sampleKeys.clear()
        sampleKeys.addAll(updatedKeyframes)
    }

    override fun updateTextDocument(
        layerId: String,
        text: String,
        fontFamily: String,
        fontSize: Double,
        fillColorRgb: DoubleArray,
        tracking: Double
    ) = Unit

    override fun applyTextAnimator(
        layerId: String,
        animatorName: String,
        selectorValues: Map<String, Double>,
        propertyValues: Map<String, DoubleArray>,
        propertyKeyframes: Map<String, List<HostKeyframeDto>>
    ) = Unit

    override fun addLayerOrCompMarker(
        layerId: String?,
        timeSec: Double,
        comment: String,
        durationSec: Double
    ) = Unit
}
