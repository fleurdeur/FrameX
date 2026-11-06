package com.example.engine

import android.content.Context
import android.net.Uri
import android.util.Xml
import com.example.data.model.AnimatableProperty
import com.example.data.model.AspectRatioPreset
import com.example.data.model.AudioBand
import com.example.data.model.BeatMarker
import com.example.data.model.DrawingPoint
import com.example.data.model.EffectNode
import com.example.data.model.EffectParamSlot
import com.example.data.model.Keyframe
import com.example.data.model.Layer
import com.example.data.model.LayerType
import com.example.data.model.MaskNode
import com.example.data.model.Primitive3DType
import com.example.data.model.Project
import com.example.data.model.PropertyTarget
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.InputStream
import java.io.StringReader
import java.io.StringWriter
import java.util.Locale
import java.util.UUID
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.roundToLong

enum class ProjectXmlFormat(val extension: String, val label: String) {
    FRAME_POLLUTION_NATIVE(".fpxml", "FramePollution Engine XML (.fpxml)"),
    ALIGHT_MOTION(".xml", "Alight Motion XML (.xml)")
}

data class XmlImportResult(
    val project: Project,
    val detectedFormat: ProjectXmlFormat,
    val importedPluginSuitesCount: Int = 0,
    val flattenedGroupsCount: Int = 0,
    val mappedEffectsCount: Int = 0
)

/**
 * Full-Fidelity XML Interchange Engine (`ProjectXmlEngine`):
 *
 * 1. **Alight Motion XML (`.xml`) Import & Export**:
 *    - Parses `<scene title width height fps totalTime bgcolor>`, `<bookmark t="..." />`,
 *      `<audio>`, `<shape fillType="color|media" fillImage="..." blending="mask|screen|multiply|overlay|difference" s=".rect|.circle|.roundrect|.star|.poly">`,
 *      `<text>`, `<nullobj>`, `<camera>`, and nested `<embedScene>` pre-compositions (creating parent `NULL_OBJECT` rigs,
 *      shifting child `startTime`/`endTime` + `<kf>` times by `+embedStartTime`, and handling group crop masks).
 *    - Converts Alight Motion top-left coordinates & `cubicBezier x1 y1 x2 y2` / `step` keyframes (sorting out-of-order `<kf>` tags)
 *      and maps `com.alightcreative.effects.*` (`lift` -> `Copy_Background`, `tile` -> `Tiles`, `oscillate3` -> `Oscillate`,
 *      `motionblur4` -> `Motion_Blur_Tile` + native motion blur, `stretch2` -> `Squeeze`, `circularripple2` -> `Ripple_Pond`,
 *      `transform` -> `Tiles`, `satvib` -> `Saturation_Vibrance`, `rgbsep` -> `Chromatic_Aberration`, `zoomblur3` -> `Radial_Zoom_Blur`,
 *      `dblur` -> `Directional_Blur`, `vignette` -> `S_Vignette`, `offset` -> `Tiles`, `gaussianblur` -> `Gaussian_Blur`,
 *      `fade` -> `Luma_Fade` + layer opacity envelope, `blink2` -> `S_Flicker`, `exposure` -> `Exposure_Gamma`,
 *      `sharpen` -> `Unsharp_Mask`, `texttransform` -> `Text_Transform`, `textspacing` -> `Text_Spacing`, etc.).
 *
 * 2. **FramePollution Native Engine XML (`.fpxml`) Export & Round-Trip Import**:
 *    - Structured cleanly like Alight Motion XML (`<fpProject>`), preserving **100% of FramePollution engine properties**:
 *      resolution (`width`, `height`, `aspectRatioPreset`), `fps`, `durationMs`, `bpm`, `backgroundColor`, `codec`,
 *      `<bookmark>` beat markers, all 8 layer types, `parentId` hierarchy, 3D transforms (`posX/Y/Z`, `pivotX/Y`, `scale`, `rotX/Y/Z`, `opacity`, `timeRemap`),
 *      Bezier tangents, `holdStep`, expressions, audio reactor bindings, fill/gradients, stroke (`OUTSIDE`/`CENTER`/`INSIDE`) & shadow,
 *      speed/reverse/freeze, audio DSP (`reverb`, `echo`, `bass`, `filter`, `pitch`), AND **embedded custom `.kfpack` / `.fppack` GLSL plugin suites**
 *      inside `<pluginSuites>` so importing `.fpxml` on any device automatically installs and compiles every custom shader effect!
 */
object ProjectXmlEngine {

    const val EXT_FPXML = ".fpxml"
    const val EXT_AMXML = ".xml"

    fun importFromUri(
        context: Context,
        uri: Uri,
        fallbackName: String = "Imported Project"
    ): Result<XmlImportResult> {
        return try {
            val stream = context.contentResolver.openInputStream(uri)
                ?: return Result.failure(IllegalArgumentException("Unable to read XML file"))
            stream.use { input ->
                importFromStream(context, input, fallbackName)
            }
        } catch (t: Throwable) {
            Result.failure(t)
        }
    }

    fun importFromStream(
        context: Context?,
        inputStream: InputStream,
        fallbackName: String = "Imported Project"
    ): Result<XmlImportResult> {
        return try {
            val rawXml = inputStream.bufferedReader(Charsets.UTF_8).readText()
            importProjectFromXml(context, rawXml, fallbackName)
        } catch (t: Throwable) {
            Result.failure(t)
        }
    }

    fun exportToUri(
        context: Context,
        project: Project,
        targetUri: Uri,
        format: ProjectXmlFormat
    ): Result<String> {
        return try {
            val xmlText = when (format) {
                ProjectXmlFormat.FRAME_POLLUTION_NATIVE -> exportFramePollutionXml(project)
                ProjectXmlFormat.ALIGHT_MOTION -> exportAlightMotionXml(project)
            }
            val out = context.contentResolver.openOutputStream(targetUri)
                ?: return Result.failure(IllegalStateException("Unable to open target XML file for writing"))
            out.use { stream ->
                stream.write(xmlText.toByteArray(Charsets.UTF_8))
                stream.flush()
            }
            val cleanBase = project.name.trim().ifBlank { "project" }
                .replace(Regex("[^a-zA-Z0-9_\\- ]"), "")
                .trim()
                .replace(' ', '_')
            Result.success("$cleanBase${format.extension}")
        } catch (t: Throwable) {
            Result.failure(t)
        }
    }

    fun importProjectFromXml(
        context: Context?,
        xmlContent: String,
        fallbackName: String = "Imported Project"
    ): Result<XmlImportResult> {
        val cleaned = xmlContent.trimStart('\uFEFF', ' ', '\t', '\r', '\n')
        if (cleaned.isEmpty()) {
            return Result.failure(IllegalArgumentException("XML file is empty"))
        }
        return try {
            val factory = XmlPullParserFactory.newInstance().apply {
                isNamespaceAware = false
            }
            val parser = factory.newPullParser()
            parser.setInput(StringReader(cleaned))

            var rootTag: String? = null
            var event = parser.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                if (event == XmlPullParser.START_TAG) {
                    rootTag = parser.name
                    break
                }
                event = parser.next()
            }

            when {
                rootTag.equals("fpProject", ignoreCase = true) -> {
                    parseFramePollutionNativeXml(context, cleaned, fallbackName)
                }
                rootTag.equals("scene", ignoreCase = true) -> {
                    parseAlightMotionSceneXml(context, cleaned, fallbackName)
                }
                else -> {
                    Result.failure(IllegalArgumentException("Unsupported XML root element: <${rootTag ?: "unknown"}>"))
                }
            }
        } catch (t: Throwable) {
            Result.failure(t)
        }
    }

    // =========================================================================================
    // 1. NATIVE ENGINE XML (.fpxml) SERIALIZER & PARSER (100% Engine Property + Plugin Support)
    // =========================================================================================

    fun exportFramePollutionXml(project: Project): String {
        val writer = StringWriter(32 * 1024)
        val xml = Xml.newSerializer()
        xml.setOutput(writer)
        xml.startDocument("UTF-8", true)
        try {
            xml.setFeature("http://xmlpull.org/v1/doc/features.html#indent-output", true)
        } catch (_: Throwable) {
        }

        xml.startTag(null, "fpProject")
        xml.attribute(null, "formatVersion", "2.0")
        xml.attribute(null, "engine", "FramePollution")
        xml.attribute(null, "title", project.name)
        xml.attribute(null, "width", project.width.toString())
        xml.attribute(null, "height", project.height.toString())
        xml.attribute(null, "aspectPreset", project.aspectRatioPreset)
        xml.attribute(null, "fps", project.fps.toString())
        xml.attribute(null, "durationMs", project.durationMs.toString())
        xml.attribute(null, "bpm", project.bpm.toString())
        xml.attribute(null, "backgroundColor", project.backgroundColor)
        xml.attribute(null, "codec", project.codec)

        // 1. Bookmarks / Beat Markers
        if (project.beatMarkers.isNotEmpty()) {
            xml.startTag(null, "bookmarks")
            project.beatMarkers.sortedBy { it.timeMs }.forEach { bm ->
                xml.startTag(null, "bookmark")
                xml.attribute(null, "t", bm.timeMs.toString())
                xml.attribute(null, "strength", fmtFloat(bm.strength))
                xml.attribute(null, "band", bm.band.name)
                xml.endTag(null, "bookmark")
            }
            xml.endTag(null, "bookmarks")
        }

        // 2. Embedded Custom Plugin Suites (so imported .kfpack/.fppack effects work standalone anywhere!)
        val referencedPluginIds = project.layers
            .flatMap { it.effects }
            .map { it.effectId }
            .distinct()
        val suitesToEmbed = LinkedHashMap<String, InstalledPluginSuite>()
        referencedPluginIds.forEach { fxId ->
            val pluginFx = PluginPackManager.findPluginEffect(fxId) ?: return@forEach
            val existingSuite = PluginPackManager.findSuiteContainingEffect(fxId)
            if (existingSuite != null) {
                suitesToEmbed.putIfAbsent(existingSuite.id, existingSuite)
            } else {
                val syntheticSuite = InstalledPluginSuite(
                    id = pluginFx.suiteId.ifBlank { "${pluginFx.id}_suite" },
                    name = pluginFx.suiteName.ifBlank { pluginFx.name },
                    category = pluginFx.category,
                    author = pluginFx.author,
                    version = pluginFx.version,
                    archiveExtension = PluginPackManager.EXT_KFPACK,
                    enabled = true,
                    isBuiltInStarter = false,
                    effects = listOf(pluginFx)
                )
                suitesToEmbed.putIfAbsent(syntheticSuite.id, syntheticSuite)
            }
        }

        if (suitesToEmbed.isNotEmpty()) {
            xml.startTag(null, "pluginSuites")
            suitesToEmbed.values.forEach { suite ->
                xml.startTag(null, "pluginSuite")
                xml.attribute(null, "id", suite.id)
                xml.attribute(null, "name", suite.name)
                xml.attribute(null, "category", suite.category)
                xml.attribute(null, "author", suite.author)
                xml.attribute(null, "version", suite.version)
                xml.attribute(null, "minAppVersion", suite.minAppVersion)
                xml.attribute(null, "description", suite.description)
                xml.attribute(null, "ext", suite.archiveExtension)

                suite.effects.forEach { pfx ->
                    xml.startTag(null, "pluginEffect")
                    xml.attribute(null, "id", pfx.id)
                    xml.attribute(null, "name", pfx.name)
                    xml.attribute(null, "category", pfx.category)
                    xml.attribute(null, "author", pfx.author)
                    xml.attribute(null, "version", pfx.version)
                    xml.attribute(null, "expandBounds", fmtFloat(pfx.expandBounds))
                    xml.attribute(null, "usesNativeMotionBlur", pfx.usesNativeMotionBlur.toString())

                    pfx.parameters.forEach { param ->
                        xml.startTag(null, "paramDef")
                        xml.attribute(null, "key", param.key)
                        xml.attribute(null, "label", param.label)
                        xml.attribute(null, "type", param.type.name)
                        xml.attribute(null, "defaultFloat", fmtFloat(param.defaultFloat))
                        xml.attribute(null, "defaultColor", param.defaultColorHex)
                        xml.attribute(null, "min", fmtFloat(param.min))
                        xml.attribute(null, "max", fmtFloat(param.max))
                        xml.attribute(null, "step", fmtFloat(param.step))
                        xml.endTag(null, "paramDef")
                    }

                    pfx.passes.forEach { pass ->
                        xml.startTag(null, "shaderPass")
                        xml.attribute(null, "file", pass.fileName)
                        xml.cdsect(pass.glslSource.replace("]]>", "]]]]><![CDATA[>"))
                        xml.endTag(null, "shaderPass")
                    }

                    xml.endTag(null, "pluginEffect")
                }

                xml.endTag(null, "pluginSuite")
            }
            xml.endTag(null, "pluginSuites")
        }

        // 3. Layers
        xml.startTag(null, "layers")
        project.layers.forEach { layer ->
            xml.startTag(null, "layer")
            xml.attribute(null, "id", layer.id)
            xml.attribute(null, "name", layer.name)
            xml.attribute(null, "type", layer.type.name)
            xml.attribute(null, "visible", layer.visible.toString())
            xml.attribute(null, "locked", layer.locked.toString())
            xml.attribute(null, "startTimeMs", layer.startTimeMs.toString())
            xml.attribute(null, "endTimeMs", layer.endTimeMs.toString())
            xml.attribute(null, "sourceOffsetMs", layer.sourceOffsetMs.toString())
            xml.attribute(null, "sourceDurationMs", layer.sourceDurationMs.toString())
            xml.attribute(null, "mediaWidth", layer.mediaWidth.toString())
            xml.attribute(null, "mediaHeight", layer.mediaHeight.toString())
            if (!layer.parentId.isNullOrBlank()) {
                xml.attribute(null, "parentId", layer.parentId)
            }
            xml.attribute(null, "blendMode", layer.blendMode)
            xml.attribute(null, "isClippingMask", layer.isClippingMask.toString())
            xml.attribute(null, "flipX", layer.flipX.toString())
            xml.attribute(null, "flipY", layer.flipY.toString())
            xml.attribute(null, "motionBlurEnabled", layer.motionBlurEnabled.toString())
            xml.attribute(null, "shutterAngle", fmtFloat(layer.shutterAngle))

            // Visual Style / Media / Text / 3D attributes
            xml.startTag(null, "style")
            xml.attribute(null, "colorHex", layer.colorHex)
            xml.attribute(null, "fillMode", layer.fillMode)
            xml.attribute(null, "gradientEndColorHex", layer.gradientEndColorHex)
            xml.attribute(null, "gradientAngleDeg", fmtFloat(layer.gradientAngleDeg))
            xml.attribute(null, "shapeKind", layer.shapeKind)
            xml.attribute(null, "strokeEnabled", layer.strokeEnabled.toString())
            xml.attribute(null, "strokeWidth", fmtFloat(layer.strokeWidth))
            xml.attribute(null, "strokeColorHex", layer.strokeColorHex)
            xml.attribute(null, "strokePosition", layer.strokePosition)
            xml.attribute(null, "shadowEnabled", layer.shadowEnabled.toString())
            xml.attribute(null, "shadowBlur", fmtFloat(layer.shadowBlur))
            xml.attribute(null, "shadowColorHex", layer.shadowColorHex)
            xml.attribute(null, "shadowOffsetX", fmtFloat(layer.shadowOffsetX))
            xml.attribute(null, "shadowOffsetY", fmtFloat(layer.shadowOffsetY))
            xml.attribute(null, "fontFamilyName", layer.fontFamilyName)
            xml.attribute(null, "primitive3D", layer.primitive3D.name)
            if (layer.mediaUri.isNotBlank()) {
                xml.attribute(null, "mediaUri", layer.mediaUri)
            }
            if (layer.type == LayerType.TEXT || layer.textContent != "TEXT") {
                xml.startTag(null, "textContent")
                xml.text(layer.textContent)
                xml.endTag(null, "textContent")
            }
            xml.endTag(null, "style")

            // Timing & Audio DSP
            xml.startTag(null, "timingAudio")
            xml.attribute(null, "speedMultiplier", fmtFloat(layer.speedMultiplier))
            xml.attribute(null, "reversed", layer.reversed.toString())
            xml.attribute(null, "timeRemapEnabled", layer.timeRemapEnabled.toString())
            xml.attribute(null, "freezeFrameMs", layer.freezeFrameMs.toString())
            xml.attribute(null, "audioMuted", layer.audioMuted.toString())
            xml.attribute(null, "audioVolume", fmtFloat(layer.audioVolume))
            xml.attribute(null, "audioPreset", layer.audioPreset)
            xml.attribute(null, "audioReverbMix", fmtFloat(layer.audioReverbMix))
            xml.attribute(null, "audioRoomSize", fmtFloat(layer.audioRoomSize))
            xml.attribute(null, "audioBassBoost", fmtFloat(layer.audioBassBoost))
            xml.attribute(null, "audioEchoMix", fmtFloat(layer.audioEchoMix))
            xml.attribute(null, "audioEchoDelayMs", fmtFloat(layer.audioEchoDelayMs))
            xml.attribute(null, "audioFilterCutoff", fmtFloat(layer.audioFilterCutoff))
            xml.attribute(null, "audioPitchSemitones", fmtFloat(layer.audioPitchSemitones))
            if (layer.extractedWaveform.isNotEmpty()) {
                xml.attribute(
                    null,
                    "waveform",
                    layer.extractedWaveform.take(96).joinToString(",") { fmtFloat(it) }
                )
            }
            xml.endTag(null, "timingAudio")

            // Transform & Animatable Properties
            xml.startTag(null, "transform")
            writeAnimProperty(xml, "posX", layer.posX)
            writeAnimProperty(xml, "posY", layer.posY)
            writeAnimProperty(xml, "posZ", layer.posZ)
            writeAnimProperty(xml, "pivotX", layer.pivotX)
            writeAnimProperty(xml, "pivotY", layer.pivotY)
            writeAnimProperty(xml, "scale", layer.scale)
            writeAnimProperty(xml, "rotationX", layer.rotationX)
            writeAnimProperty(xml, "rotationY", layer.rotationY)
            writeAnimProperty(xml, "rotationZ", layer.rotationZ)
            writeAnimProperty(xml, "opacity", layer.opacity)
            writeAnimProperty(xml, "timeRemap", layer.timeRemap)
            xml.endTag(null, "transform")

            // Masks
            if (layer.masks.isNotEmpty()) {
                xml.startTag(null, "masks")
                layer.masks.forEach { mask ->
                    xml.startTag(null, "mask")
                    xml.attribute(null, "id", mask.id)
                    xml.attribute(null, "shape", mask.shape)
                    xml.attribute(null, "feather", fmtFloat(mask.feather))
                    xml.attribute(null, "inverted", mask.inverted.toString())
                    xml.endTag(null, "mask")
                }
                xml.endTag(null, "masks")
            }

            // Effects (Built-in + Custom Plugins)
            if (layer.effects.isNotEmpty()) {
                xml.startTag(null, "effects")
                layer.effects.forEach { fx ->
                    xml.startTag(null, "effect")
                    xml.attribute(null, "id", fx.id)
                    xml.attribute(null, "effectId", fx.effectId)
                    xml.attribute(null, "displayName", fx.displayName)
                    xml.attribute(null, "category", fx.category)
                    xml.attribute(null, "enabled", fx.enabled.toString())
                    xml.attribute(null, "tintColorHex", fx.tintColorHex)
                    xml.attribute(null, "audioBoundBand", fx.audioBoundBand.name)
                    xml.attribute(null, "audioSensitivity", fmtFloat(fx.audioSensitivity))

                    writeAnimProperty(xml, "primary", fx.primaryProp)
                    writeAnimProperty(xml, "secondary", fx.secondaryProp)
                    writeAnimProperty(xml, "frequency", fx.frequencyProp)
                    writeAnimProperty(xml, "angle", fx.angleProp)
                    writeAnimProperty(xml, "mix", fx.mixProp)

                    fx.customParams.forEach { (paramKey, animProp) ->
                        writeAnimProperty(xml, paramKey, animProp, tagName = "customParam")
                    }
                    fx.customColors.forEach { (colorKey, hexVal) ->
                        xml.startTag(null, "customColor")
                        xml.attribute(null, "key", colorKey)
                        xml.attribute(null, "hex", hexVal)
                        xml.endTag(null, "customColor")
                    }
                    xml.endTag(null, "effect")
                }
                xml.endTag(null, "effects")
            }

            xml.endTag(null, "layer")
        }
        xml.endTag(null, "layers")

        xml.endTag(null, "fpProject")
        xml.endDocument()
        xml.flush()
        return writer.toString()
    }

    private fun writeAnimProperty(
        xml: org.xmlpull.v1.XmlSerializer,
        name: String,
        prop: AnimatableProperty,
        tagName: String = "prop"
    ) {
        xml.startTag(null, tagName)
        xml.attribute(null, "name", name)
        xml.attribute(null, "v", fmtFloat(prop.baseValue))
        if (prop.expression.isNotBlank()) {
            xml.attribute(null, "expr", prop.expression)
        }
        if (prop.audioReactorBand != AudioBand.NONE) {
            xml.attribute(null, "audioBand", prop.audioReactorBand.name)
            xml.attribute(null, "audioGain", fmtFloat(prop.audioReactorGain))
        }
        prop.keyframes.sortedBy { it.timeMs }.forEach { kf ->
            xml.startTag(null, "kf")
            xml.attribute(null, "id", kf.id)
            xml.attribute(null, "t", kf.timeMs.toString())
            xml.attribute(null, "v", fmtFloat(kf.value))
            xml.attribute(
                null,
                "e",
                if (kf.holdStep) {
                    "step"
                } else {
                    "cubicBezier ${fmtFloat(kf.outTangentX)} ${fmtFloat(kf.outTangentY)} ${fmtFloat(kf.inTangentX)} ${fmtFloat(kf.inTangentY)}"
                }
            )
            xml.endTag(null, "kf")
        }
        xml.endTag(null, tagName)
    }

    private fun parseFramePollutionNativeXml(
        context: Context?,
        xmlText: String,
        fallbackName: String
    ): Result<XmlImportResult> {
        val factory = XmlPullParserFactory.newInstance().apply { isNamespaceAware = false }
        val parser = factory.newPullParser()
        parser.setInput(StringReader(xmlText))

        var title = fallbackName
        var width = 1920
        var height = 1080
        var aspectPreset = "16:9"
        var fps = 60
        var durationMs = 10_000L
        var bpm = 128
        var bgColor = "Black"
        var codec = "H.264"

        val bookmarks = ArrayList<BeatMarker>()
        val layers = ArrayList<Layer>()
        var installedSuitesCount = 0

        var ev = parser.eventType
        while (ev != XmlPullParser.END_DOCUMENT) {
            if (ev == XmlPullParser.START_TAG) {
                when (parser.name) {
                    "fpProject" -> {
                        title = parser.getAttributeValue(null, "title")?.ifBlank { fallbackName } ?: fallbackName
                        width = parser.getAttributeValue(null, "width")?.toIntOrNull()?.coerceIn(64, 7680) ?: 1920
                        height = parser.getAttributeValue(null, "height")?.toIntOrNull()?.coerceIn(64, 7680) ?: 1080
                        aspectPreset = parser.getAttributeValue(null, "aspectPreset")
                            ?: inferAspectPresetLabel(width, height)
                        fps = parser.getAttributeValue(null, "fps")?.toIntOrNull()?.coerceIn(1, 240) ?: 60
                        durationMs = parser.getAttributeValue(null, "durationMs")?.toLongOrNull()?.coerceAtLeast(200L) ?: 10_000L
                        bpm = parser.getAttributeValue(null, "bpm")?.toIntOrNull()?.coerceIn(40, 240) ?: 128
                        bgColor = parser.getAttributeValue(null, "backgroundColor") ?: "Black"
                        codec = parser.getAttributeValue(null, "codec") ?: "H.264"
                    }
                    "bookmark" -> {
                        val t = parser.getAttributeValue(null, "t")?.toLongOrNull()?.coerceAtLeast(0L) ?: 0L
                        val str = parser.getAttributeValue(null, "strength")?.toFloatOrNull() ?: 0.9f
                        val band = parser.getAttributeValue(null, "band")?.let {
                            runCatching { AudioBand.valueOf(it) }.getOrNull()
                        } ?: AudioBand.BASS
                        bookmarks.add(BeatMarker(timeMs = t, strength = str, band = band))
                    }
                    "pluginSuite" -> {
                        val suite = parseEmbeddedPluginSuite(parser)
                        if (suite != null && suite.effects.isNotEmpty()) {
                            PluginPackManager.installEmbeddedSuite(context, suite)
                            installedSuitesCount++
                        }
                    }
                    "layer" -> {
                        layers.add(parseNativeFpLayer(parser))
                    }
                }
            }
            ev = parser.next()
        }

        val maxEnd = layers.maxOfOrNull { it.endTimeMs } ?: durationMs
        val project = Project(
            name = title,
            aspectRatioPreset = aspectPreset,
            width = width,
            height = height,
            fps = fps,
            durationMs = max(durationMs, maxEnd),
            codec = codec,
            bpm = bpm,
            backgroundColor = bgColor,
            layers = layers,
            beatMarkers = bookmarks.sortedBy { it.timeMs }
        )
        return Result.success(
            XmlImportResult(
                project = project,
                detectedFormat = ProjectXmlFormat.FRAME_POLLUTION_NATIVE,
                importedPluginSuitesCount = installedSuitesCount,
                flattenedGroupsCount = 0,
                mappedEffectsCount = layers.sumOf { it.effects.size }
            )
        )
    }

    private fun parseEmbeddedPluginSuite(parser: XmlPullParser): InstalledPluginSuite? {
        val suiteId = parser.getAttributeValue(null, "id")?.ifBlank { "suite_${UUID.randomUUID().toString().take(6)}" }
            ?: "suite_${UUID.randomUUID().toString().take(6)}"
        val suiteName = parser.getAttributeValue(null, "name") ?: "Embedded Suite"
        val suiteCat = PluginPackManager.normalizePluginCategory(parser.getAttributeValue(null, "category") ?: "Custom")
        val suiteAuthor = parser.getAttributeValue(null, "author") ?: "Creator"
        val suiteVer = parser.getAttributeValue(null, "version") ?: "1.0.0"
        val minAppVer = parser.getAttributeValue(null, "minAppVersion") ?: "1.4.9"
        val desc = parser.getAttributeValue(null, "description") ?: ""
        val ext = parser.getAttributeValue(null, "ext") ?: PluginPackManager.EXT_KFPACK

        val effects = ArrayList<InstalledPluginEffect>()
        val startDepth = parser.depth
        var ev = parser.next()
        while (!(ev == XmlPullParser.END_TAG && parser.depth == startDepth && parser.name == "pluginSuite") &&
            ev != XmlPullParser.END_DOCUMENT
        ) {
            if (ev == XmlPullParser.START_TAG && parser.name == "pluginEffect") {
                val fxId = parser.getAttributeValue(null, "id") ?: "fx_${UUID.randomUUID().toString().take(6)}"
                val fxName = parser.getAttributeValue(null, "name") ?: fxId
                val fxCat = PluginPackManager.normalizePluginCategory(parser.getAttributeValue(null, "category") ?: suiteCat)
                val fxAuthor = parser.getAttributeValue(null, "author") ?: suiteAuthor
                val fxVer = parser.getAttributeValue(null, "version") ?: suiteVer
                val expandBounds = parser.getAttributeValue(null, "expandBounds")?.toFloatOrNull() ?: 0f
                val usesMb = parser.getAttributeValue(null, "usesNativeMotionBlur")?.toBooleanStrictOrNull() ?: false

                val params = ArrayList<PluginParamDef>()
                val passes = ArrayList<PluginShaderPass>()
                val fxDepth = parser.depth
                var subEv = parser.next()
                while (!(subEv == XmlPullParser.END_TAG && parser.depth == fxDepth && parser.name == "pluginEffect") &&
                    subEv != XmlPullParser.END_DOCUMENT
                ) {
                    if (subEv == XmlPullParser.START_TAG) {
                        when (parser.name) {
                            "paramDef" -> {
                                val pKey = parser.getAttributeValue(null, "key") ?: "amount"
                                val pLabel = parser.getAttributeValue(null, "label") ?: pKey
                                val pType = parser.getAttributeValue(null, "type")?.let {
                                    runCatching { PluginParamType.valueOf(it) }.getOrNull()
                                } ?: PluginParamType.FLOAT
                                val defF = parser.getAttributeValue(null, "defaultFloat")?.toFloatOrNull() ?: 0.5f
                                val defC = parser.getAttributeValue(null, "defaultColor") ?: "#FFFFFF"
                                val pMin = parser.getAttributeValue(null, "min")?.toFloatOrNull() ?: 0f
                                val pMax = parser.getAttributeValue(null, "max")?.toFloatOrNull() ?: 1f
                                val pStep = parser.getAttributeValue(null, "step")?.toFloatOrNull() ?: 0.01f
                                params.add(PluginParamDef(pKey, pLabel, pType, defF, defC, pMin, pMax, pStep))
                            }
                            "shaderPass" -> {
                                val fName = parser.getAttributeValue(null, "file") ?: "shader.glsl"
                                val src = parser.nextText()
                                passes.add(PluginShaderPass(fName, src))
                            }
                        }
                    }
                    subEv = parser.next()
                }
                if (passes.isNotEmpty()) {
                    effects.add(
                        InstalledPluginEffect(
                            id = fxId,
                            suiteId = suiteId,
                            suiteName = suiteName,
                            name = fxName,
                            category = fxCat,
                            author = fxAuthor,
                            version = fxVer,
                            expandBounds = expandBounds,
                            usesNativeMotionBlur = usesMb,
                            passes = passes,
                            parameters = params
                        )
                    )
                }
            }
            ev = parser.next()
        }

        return InstalledPluginSuite(
            id = suiteId,
            name = suiteName,
            category = suiteCat,
            author = suiteAuthor,
            version = suiteVer,
            minAppVersion = minAppVer,
            description = desc,
            archiveExtension = ext,
            enabled = true,
            isBuiltInStarter = false,
            effects = effects
        )
    }

    private fun parseNativeFpLayer(parser: XmlPullParser): Layer {
        val id = parser.getAttributeValue(null, "id") ?: UUID.randomUUID().toString()
        val name = parser.getAttributeValue(null, "name") ?: "Layer"
        val type = parser.getAttributeValue(null, "type")?.let {
            runCatching { LayerType.valueOf(it) }.getOrNull()
        } ?: LayerType.SHAPE
        val visible = parser.getAttributeValue(null, "visible")?.toBooleanStrictOrNull() ?: true
        val locked = parser.getAttributeValue(null, "locked")?.toBooleanStrictOrNull() ?: false
        val startTimeMs = parser.getAttributeValue(null, "startTimeMs")?.toLongOrNull() ?: 0L
        val endTimeMs = parser.getAttributeValue(null, "endTimeMs")?.toLongOrNull() ?: 10_000L
        val sourceOffsetMs = parser.getAttributeValue(null, "sourceOffsetMs")?.toLongOrNull() ?: 0L
        val sourceDurationMs = parser.getAttributeValue(null, "sourceDurationMs")?.toLongOrNull() ?: (endTimeMs - startTimeMs)
        val mediaWidth = parser.getAttributeValue(null, "mediaWidth")?.toIntOrNull() ?: 0
        val mediaHeight = parser.getAttributeValue(null, "mediaHeight")?.toIntOrNull() ?: 0
        val parentId = parser.getAttributeValue(null, "parentId")?.takeIf { it.isNotBlank() }
        val blendMode = parser.getAttributeValue(null, "blendMode") ?: "Normal"
        val isClippingMask = parser.getAttributeValue(null, "isClippingMask")?.toBooleanStrictOrNull() ?: false
        val flipX = parser.getAttributeValue(null, "flipX")?.toBooleanStrictOrNull() ?: false
        val flipY = parser.getAttributeValue(null, "flipY")?.toBooleanStrictOrNull() ?: false
        val motionBlurEnabled = parser.getAttributeValue(null, "motionBlurEnabled")?.toBooleanStrictOrNull() ?: false
        val shutterAngle = parser.getAttributeValue(null, "shutterAngle")?.toFloatOrNull() ?: 180f

        var colorHex = "#4F7CFF"
        var fillMode = "SOLID"
        var gradientEndColorHex = "#38BDF8"
        var gradientAngleDeg = 45f
        var shapeKind = "Rounded Rect"
        var strokeEnabled = false
        var strokeWidth = 4f
        var strokeColorHex = "#FFFFFF"
        var strokePosition = "CENTER"
        var shadowEnabled = false
        var shadowBlur = 16f
        var shadowColorHex = "#000000"
        var shadowOffsetX = 0f
        var shadowOffsetY = 6f
        var fontFamilyName = "Space Grotesk"
        var primitive3D = Primitive3DType.CUBE
        var mediaUri = ""
        var textContent = "TEXT"

        var speedMultiplier = 1f
        var reversed = false
        var timeRemapEnabled = false
        var freezeFrameMs = -1L
        var audioMuted = false
        var audioVolume = 1f
        var audioPreset = "Clean"
        var audioReverbMix = 0f
        var audioRoomSize = 0.55f
        var audioBassBoost = 0f
        var audioEchoMix = 0f
        var audioEchoDelayMs = 240f
        var audioFilterCutoff = 1f
        var audioPitchSemitones = 0f
        var waveform: List<Float> = emptyList()

        val props = HashMap<String, AnimatableProperty>()
        val masks = ArrayList<MaskNode>()
        val effects = ArrayList<EffectNode>()

        val layerDepth = parser.depth
        var ev = parser.next()
        while (!(ev == XmlPullParser.END_TAG && parser.depth == layerDepth && parser.name == "layer") &&
            ev != XmlPullParser.END_DOCUMENT
        ) {
            if (ev == XmlPullParser.START_TAG) {
                when (parser.name) {
                    "style" -> {
                        colorHex = parser.getAttributeValue(null, "colorHex") ?: colorHex
                        fillMode = parser.getAttributeValue(null, "fillMode") ?: fillMode
                        gradientEndColorHex = parser.getAttributeValue(null, "gradientEndColorHex") ?: gradientEndColorHex
                        gradientAngleDeg = parser.getAttributeValue(null, "gradientAngleDeg")?.toFloatOrNull() ?: gradientAngleDeg
                        shapeKind = parser.getAttributeValue(null, "shapeKind") ?: shapeKind
                        strokeEnabled = parser.getAttributeValue(null, "strokeEnabled")?.toBooleanStrictOrNull() ?: false
                        strokeWidth = parser.getAttributeValue(null, "strokeWidth")?.toFloatOrNull() ?: strokeWidth
                        strokeColorHex = parser.getAttributeValue(null, "strokeColorHex") ?: strokeColorHex
                        strokePosition = parser.getAttributeValue(null, "strokePosition") ?: strokePosition
                        shadowEnabled = parser.getAttributeValue(null, "shadowEnabled")?.toBooleanStrictOrNull() ?: false
                        shadowBlur = parser.getAttributeValue(null, "shadowBlur")?.toFloatOrNull() ?: shadowBlur
                        shadowColorHex = parser.getAttributeValue(null, "shadowColorHex") ?: shadowColorHex
                        shadowOffsetX = parser.getAttributeValue(null, "shadowOffsetX")?.toFloatOrNull() ?: shadowOffsetX
                        shadowOffsetY = parser.getAttributeValue(null, "shadowOffsetY")?.toFloatOrNull() ?: shadowOffsetY
                        fontFamilyName = parser.getAttributeValue(null, "fontFamilyName") ?: fontFamilyName
                        primitive3D = parser.getAttributeValue(null, "primitive3D")?.let {
                            runCatching { Primitive3DType.valueOf(it) }.getOrNull()
                        } ?: Primitive3DType.CUBE
                        mediaUri = parser.getAttributeValue(null, "mediaUri") ?: ""
                    }
                    "textContent" -> {
                        textContent = parser.nextText()
                    }
                    "timingAudio" -> {
                        speedMultiplier = parser.getAttributeValue(null, "speedMultiplier")?.toFloatOrNull() ?: 1f
                        reversed = parser.getAttributeValue(null, "reversed")?.toBooleanStrictOrNull() ?: false
                        timeRemapEnabled = parser.getAttributeValue(null, "timeRemapEnabled")?.toBooleanStrictOrNull() ?: false
                        freezeFrameMs = parser.getAttributeValue(null, "freezeFrameMs")?.toLongOrNull() ?: -1L
                        audioMuted = parser.getAttributeValue(null, "audioMuted")?.toBooleanStrictOrNull() ?: false
                        audioVolume = parser.getAttributeValue(null, "audioVolume")?.toFloatOrNull() ?: 1f
                        audioPreset = parser.getAttributeValue(null, "audioPreset") ?: "Clean"
                        audioReverbMix = parser.getAttributeValue(null, "audioReverbMix")?.toFloatOrNull() ?: 0f
                        audioRoomSize = parser.getAttributeValue(null, "audioRoomSize")?.toFloatOrNull() ?: 0.55f
                        audioBassBoost = parser.getAttributeValue(null, "audioBassBoost")?.toFloatOrNull() ?: 0f
                        audioEchoMix = parser.getAttributeValue(null, "audioEchoMix")?.toFloatOrNull() ?: 0f
                        audioEchoDelayMs = parser.getAttributeValue(null, "audioEchoDelayMs")?.toFloatOrNull() ?: 240f
                        audioFilterCutoff = parser.getAttributeValue(null, "audioFilterCutoff")?.toFloatOrNull() ?: 1f
                        audioPitchSemitones = parser.getAttributeValue(null, "audioPitchSemitones")?.toFloatOrNull() ?: 0f
                        val wfStr = parser.getAttributeValue(null, "waveform")
                        if (!wfStr.isNullOrBlank()) {
                            waveform = wfStr.split(',').mapNotNull { it.trim().toFloatOrNull() }
                        }
                    }
                    "prop" -> {
                        val pName = parser.getAttributeValue(null, "name") ?: ""
                        val parsedProp = parseNativeAnimProperty(parser, "prop")
                        if (pName.isNotEmpty()) props[pName] = parsedProp
                    }
                    "mask" -> {
                        masks.add(
                            MaskNode(
                                id = parser.getAttributeValue(null, "id") ?: UUID.randomUUID().toString(),
                                shapeType = com.example.data.model.MaskShapeType.fromLabel(
                                    parser.getAttributeValue(null, "shape") ?: "Rectangle"
                                ),
                                featherPx = AnimatableProperty(
                                    parser.getAttributeValue(null, "feather")?.toFloatOrNull() ?: 12f
                                ),
                                inverted = parser.getAttributeValue(null, "inverted")?.toBooleanStrictOrNull() ?: false
                            )
                        )
                    }
                    "effect" -> {
                        effects.add(parseNativeFpEffect(parser))
                    }
                }
            }
            ev = parser.next()
        }

        return Layer(
            id = id,
            name = name,
            type = type,
            visible = visible,
            startTimeMs = startTimeMs,
            endTimeMs = endTimeMs,
            sourceOffsetMs = sourceOffsetMs,
            sourceDurationMs = sourceDurationMs,
            mediaWidth = mediaWidth,
            mediaHeight = mediaHeight,
            parentId = parentId,
            colorHex = colorHex,
            fillMode = fillMode,
            gradientEndColorHex = gradientEndColorHex,
            gradientAngleDeg = gradientAngleDeg,
            shapeKind = shapeKind,
            blendMode = blendMode,
            strokeEnabled = strokeEnabled,
            strokeWidth = strokeWidth,
            strokeColorHex = strokeColorHex,
            strokePosition = strokePosition,
            shadowEnabled = shadowEnabled,
            shadowBlur = shadowBlur,
            shadowColorHex = shadowColorHex,
            shadowOffsetX = shadowOffsetX,
            shadowOffsetY = shadowOffsetY,
            speedMultiplier = speedMultiplier,
            reversed = reversed,
            timeRemapEnabled = timeRemapEnabled,
            freezeFrameMs = freezeFrameMs,
            audioMuted = audioMuted,
            audioVolume = audioVolume,
            audioPreset = audioPreset,
            audioReverbMix = audioReverbMix,
            audioRoomSize = audioRoomSize,
            audioBassBoost = audioBassBoost,
            audioEchoMix = audioEchoMix,
            audioEchoDelayMs = audioEchoDelayMs,
            audioFilterCutoff = audioFilterCutoff,
            audioPitchSemitones = audioPitchSemitones,
            extractedWaveform = waveform,
            motionBlurEnabled = motionBlurEnabled,
            shutterAngle = shutterAngle,
            isClippingMask = isClippingMask,
            flipX = flipX,
            flipY = flipY,
            locked = locked,
            textContent = textContent,
            fontFamilyName = fontFamilyName,
            mediaUri = mediaUri,
            primitive3D = primitive3D,
            posX = props["posX"] ?: AnimatableProperty(0f),
            posY = props["posY"] ?: AnimatableProperty(0f),
            posZ = props["posZ"] ?: AnimatableProperty(0f),
            pivotX = props["pivotX"] ?: AnimatableProperty(0f),
            pivotY = props["pivotY"] ?: AnimatableProperty(0f),
            scale = props["scale"] ?: AnimatableProperty(100f),
            rotationX = props["rotationX"] ?: AnimatableProperty(0f),
            rotationY = props["rotationY"] ?: AnimatableProperty(0f),
            rotationZ = props["rotationZ"] ?: AnimatableProperty(0f),
            opacity = props["opacity"] ?: AnimatableProperty(100f),
            timeRemap = props["timeRemap"] ?: AnimatableProperty(0f),
            effects = effects,
            masks = masks
        )
    }

    private fun parseNativeFpEffect(parser: XmlPullParser): EffectNode {
        val id = parser.getAttributeValue(null, "id") ?: UUID.randomUUID().toString()
        val effectId = parser.getAttributeValue(null, "effectId") ?: "S_Glow"
        val displayName = parser.getAttributeValue(null, "displayName") ?: effectId
        val category = parser.getAttributeValue(null, "category") ?: "Plugins"
        val enabled = parser.getAttributeValue(null, "enabled")?.toBooleanStrictOrNull() ?: true
        val tintColorHex = parser.getAttributeValue(null, "tintColorHex") ?: "#FFFFFF"
        val audioBand = parser.getAttributeValue(null, "audioBoundBand")?.let {
            runCatching { AudioBand.valueOf(it) }.getOrNull()
        } ?: AudioBand.NONE
        val audioSens = parser.getAttributeValue(null, "audioSensitivity")?.toFloatOrNull() ?: 1f

        val slotProps = HashMap<String, AnimatableProperty>()
        val customParams = LinkedHashMap<String, AnimatableProperty>()
        val customColors = LinkedHashMap<String, String>()

        val fxDepth = parser.depth
        var ev = parser.next()
        while (!(ev == XmlPullParser.END_TAG && parser.depth == fxDepth && parser.name == "effect") &&
            ev != XmlPullParser.END_DOCUMENT
        ) {
            if (ev == XmlPullParser.START_TAG) {
                when (parser.name) {
                    "prop" -> {
                        val pName = parser.getAttributeValue(null, "name") ?: ""
                        val parsed = parseNativeAnimProperty(parser, "prop")
                        if (pName.isNotEmpty()) slotProps[pName] = parsed
                    }
                    "customParam" -> {
                        val pKey = parser.getAttributeValue(null, "name") ?: ""
                        val parsed = parseNativeAnimProperty(parser, "customParam")
                        if (pKey.isNotEmpty()) customParams[pKey] = parsed
                    }
                    "customColor" -> {
                        val cKey = parser.getAttributeValue(null, "key") ?: ""
                        val cHex = parser.getAttributeValue(null, "hex") ?: "#FFFFFF"
                        if (cKey.isNotEmpty()) customColors[cKey] = cHex
                    }
                }
            }
            ev = parser.next()
        }

        val pProp = slotProps["primary"] ?: AnimatableProperty(0.65f)
        val sProp = slotProps["secondary"] ?: AnimatableProperty(0.50f)
        val fProp = slotProps["frequency"] ?: AnimatableProperty(0.35f)
        val aProp = slotProps["angle"] ?: AnimatableProperty(0f)
        val mProp = slotProps["mix"] ?: AnimatableProperty(100f)

        return EffectNode(
            id = id,
            effectId = effectId,
            displayName = displayName,
            category = category,
            enabled = enabled,
            intensity = pProp.baseValue,
            secondaryParam = sProp.baseValue,
            frequencyParam = fProp.baseValue,
            angleParam = aProp.baseValue,
            mixParam = mProp.baseValue,
            tintColorHex = tintColorHex,
            primaryProp = pProp,
            secondaryProp = sProp,
            frequencyProp = fProp,
            angleProp = aProp,
            mixProp = mProp,
            audioBoundBand = audioBand,
            audioSensitivity = audioSens,
            customParams = customParams,
            customColors = customColors
        )
    }

    private fun parseNativeAnimProperty(parser: XmlPullParser, endTagName: String): AnimatableProperty {
        val baseV = parser.getAttributeValue(null, "v")?.toFloatOrNull() ?: 0f
        val expr = parser.getAttributeValue(null, "expr") ?: ""
        val aBand = parser.getAttributeValue(null, "audioBand")?.let {
            runCatching { AudioBand.valueOf(it) }.getOrNull()
        } ?: AudioBand.NONE
        val aGain = parser.getAttributeValue(null, "audioGain")?.toFloatOrNull() ?: 1f

        val kfs = ArrayList<Keyframe>()
        val startDepth = parser.depth
        var ev = parser.next()
        while (!(ev == XmlPullParser.END_TAG && parser.depth == startDepth && parser.name == endTagName) &&
            ev != XmlPullParser.END_DOCUMENT
        ) {
            if (ev == XmlPullParser.START_TAG && parser.name == "kf") {
                val kfId = parser.getAttributeValue(null, "id") ?: UUID.randomUUID().toString()
                val t = parser.getAttributeValue(null, "t")?.toLongOrNull() ?: 0L
                val v = parser.getAttributeValue(null, "v")?.toFloatOrNull() ?: baseV
                val ease = parseAmEasingAttr(parser.getAttributeValue(null, "e"))
                kfs.add(
                    Keyframe(
                        id = kfId,
                        timeMs = t.coerceAtLeast(0L),
                        value = v,
                        outTangentX = ease.x1,
                        outTangentY = ease.y1,
                        inTangentX = ease.x2,
                        inTangentY = ease.y2,
                        holdStep = ease.holdStep
                    )
                )
            }
            ev = parser.next()
        }
        return AnimatableProperty(
            baseValue = baseV,
            keyframes = kfs.sortedBy { it.timeMs },
            expression = expr,
            audioReactorBand = aBand,
            audioReactorGain = aGain
        )
    }

    // =========================================================================================
    // 2. ALIGHT MOTION XML (.xml) IMPORTER & EXPORTER
    // =========================================================================================

    private data class ParsedEasing(
        val x1: Float = 0.333333f,
        val y1: Float = 0.333333f,
        val x2: Float = 0.666667f,
        val y2: Float = 0.666667f,
        val holdStep: Boolean = false,
        val rawEasing: String = ""
    )

    private data class RawAmKeyframe(
        val normT: Float,
        val rawVal: String,
        val easing: ParsedEasing
    )

    private data class RawAmProperty(
        val name: String,
        val type: String,
        val staticValue: String?,
        val keyframes: List<RawAmKeyframe>
    )

    private data class RawAmEffect(
        val id: String,
        val label: String?,
        val locallyApplied: Boolean,
        val rawAttributes: Map<String, String>,
        val properties: Map<String, RawAmProperty>
    )

    private fun collectParserAttributes(parser: XmlPullParser): LinkedHashMap<String, String> {
        val map = LinkedHashMap<String, String>()
        val count = parser.attributeCount
        for (i in 0 until count) {
            val k = parser.getAttributeName(i) ?: continue
            val v = parser.getAttributeValue(i) ?: ""
            map[k] = v
        }
        return map
    }

    private fun isMediaUriResolvable(context: Context?, mediaUri: String): Boolean {
        if (mediaUri.isBlank()) return true
        if (mediaUri.startsWith("/")) return java.io.File(mediaUri).exists()
        if (mediaUri.startsWith("file://")) {
            val path = runCatching { Uri.parse(mediaUri).path }.getOrNull() ?: return false
            return java.io.File(path).exists()
        }
        if (context == null) return false
        return runCatching {
            context.contentResolver.openAssetFileDescriptor(Uri.parse(mediaUri), "r")?.use { true } ?: false
        }.getOrDefault(false)
    }

    private fun parseAlightMotionSceneXml(
        context: Context?,
        xmlText: String,
        fallbackName: String
    ): Result<XmlImportResult> {
        val factory = XmlPullParserFactory.newInstance().apply { isNamespaceAware = false }
        val parser = factory.newPullParser()
        parser.setInput(StringReader(xmlText))

        var ev = parser.eventType
        while (ev != XmlPullParser.END_DOCUMENT && !(ev == XmlPullParser.START_TAG && parser.name == "scene")) {
            ev = parser.next()
        }
        if (ev == XmlPullParser.END_DOCUMENT) {
            return Result.failure(IllegalArgumentException("No <scene> element found in Alight Motion XML"))
        }

        val sceneAttrs = collectParserAttributes(parser)
        val title = sceneAttrs["title"]?.trim()?.ifBlank { fallbackName } ?: fallbackName
        val sceneW = sceneAttrs["width"]?.toIntOrNull()?.coerceIn(64, 7680) ?: 1080
        val sceneH = sceneAttrs["height"]?.toIntOrNull()?.coerceIn(64, 7680) ?: 1080
        val exportW = sceneAttrs["exportWidth"]?.toIntOrNull()?.coerceIn(0, 7680) ?: sceneW
        val exportH = sceneAttrs["exportHeight"]?.toIntOrNull()?.coerceIn(0, 7680) ?: sceneH
        val fps = sceneAttrs["fps"]?.toIntOrNull()?.coerceIn(1, 240) ?: 60
        // Keep exact scene totalTime in milliseconds without frame rounding
        val totalTime = sceneAttrs["totalTime"]?.toLongOrNull()?.coerceAtLeast(1L) ?: 10_000L
        val bgRaw = sceneAttrs["bgcolor"] ?: "#ff000000"
        val bgColor = normalizeAmSceneBgColor(bgRaw)
        val explicitBgHex = splitAmColorAndAlpha(bgRaw, "#000000").first
        sceneAttrs["__exactSceneTotalTime"] = "true"

        val bookmarks = ArrayList<BeatMarker>()
        val outLayers = ArrayList<Layer>()
        var flattenedGroups = 0
        var mappedFxTotal = 0
        val xmlKeyframeCounter = intArrayOf(0)

        parseAmSceneBody(
            context = context,
            parser = parser,
            sceneW = sceneW.toFloat(),
            sceneH = sceneH.toFloat(),
            timeShiftMs = 0L,
            clipWindowStartMs = 0L,
            clipWindowEndMs = Long.MAX_VALUE,
            parentNullId = null,
            inheritedEffects = emptyList(),
            bookmarksOut = bookmarks,
            layersOut = outLayers,
            onGroupFlattened = { flattenedGroups++ },
            onEffectsMapped = { count -> mappedFxTotal += count },
            onXmlKeyframesCounted = { count -> xmlKeyframeCounter[0] += count }
        )

        sceneAttrs["__xmlKeyframeCount"] = xmlKeyframeCounter[0].toString()

        val inferredBpm = inferBpmFromBookmarks(bookmarks)
        val project = Project(
            name = title,
            aspectRatioPreset = inferAspectPresetLabel(sceneW, sceneH),
            width = sceneW,
            height = sceneH,
            exportWidth = exportW,
            exportHeight = exportH,
            fps = fps,
            durationMs = totalTime,
            codec = "H.264",
            bpm = inferredBpm,
            backgroundColor = bgColor,
            explicitSceneBgHex = explicitBgHex,
            rawAttributes = sceneAttrs,
            layers = outLayers,
            beatMarkers = bookmarks.distinctBy { it.timeMs }.sortedBy { it.timeMs }
        )

        return Result.success(
            XmlImportResult(
                project = project,
                detectedFormat = ProjectXmlFormat.ALIGHT_MOTION,
                importedPluginSuitesCount = 0,
                flattenedGroupsCount = flattenedGroups,
                mappedEffectsCount = mappedFxTotal
            )
        )
    }

    private fun parseAmSceneBody(
        context: Context?,
        parser: XmlPullParser,
        sceneW: Float,
        sceneH: Float,
        timeShiftMs: Long,
        clipWindowStartMs: Long,
        clipWindowEndMs: Long,
        parentNullId: String?,
        inheritedEffects: List<EffectNode>,
        bookmarksOut: MutableList<BeatMarker>?,
        layersOut: MutableList<Layer>,
        onGroupFlattened: () -> Unit,
        onEffectsMapped: (Int) -> Unit,
        onXmlKeyframesCounted: (Int) -> Unit
    ) {
        val localSceneLayers = ArrayList<Layer>()
        val sceneDepth = parser.depth
        var ev = parser.next()
        while (!(ev == XmlPullParser.END_TAG && parser.depth == sceneDepth && parser.name == "scene") &&
            ev != XmlPullParser.END_DOCUMENT
        ) {
            if (ev == XmlPullParser.START_TAG) {
                when (parser.name) {
                    "bookmark" -> {
                        val t = parser.getAttributeValue(null, "t")?.toLongOrNull()
                        if (t != null && bookmarksOut != null) {
                            bookmarksOut.add(
                                BeatMarker(
                                    timeMs = (t + timeShiftMs).coerceAtLeast(0L),
                                    strength = 0.92f,
                                    band = AudioBand.BASS
                                )
                            )
                        }
                        skipCurrentElement(parser)
                    }

                    "audio" -> {
                        val audioLayer = parseAmAudioLayer(
                            context = context,
                            parser = parser,
                            timeShiftMs = timeShiftMs,
                            clipWindowStartMs = clipWindowStartMs,
                            clipWindowEndMs = clipWindowEndMs,
                            onXmlKeyframesCounted = onXmlKeyframesCounted
                        )
                        if (audioLayer != null) {
                            localSceneLayers.add(audioLayer)
                        }
                    }

                    "shape" -> {
                        val parsed = parseAmShapeOrMediaLayer(
                            context = context,
                            parser = parser,
                            sceneW = sceneW,
                            sceneH = sceneH,
                            timeShiftMs = timeShiftMs,
                            clipWindowStartMs = clipWindowStartMs,
                            clipWindowEndMs = clipWindowEndMs,
                            parentNullId = parentNullId,
                            inheritedEffects = inheritedEffects,
                            onEffectsMapped = onEffectsMapped,
                            onXmlKeyframesCounted = onXmlKeyframesCounted
                        )
                        if (parsed != null) {
                            localSceneLayers.add(parsed)
                        }
                    }

                    "text" -> {
                        val parsed = parseAmTextLayer(
                            parser = parser,
                            sceneW = sceneW,
                            sceneH = sceneH,
                            timeShiftMs = timeShiftMs,
                            clipWindowStartMs = clipWindowStartMs,
                            clipWindowEndMs = clipWindowEndMs,
                            parentNullId = parentNullId,
                            inheritedEffects = inheritedEffects,
                            onEffectsMapped = onEffectsMapped,
                            onXmlKeyframesCounted = onXmlKeyframesCounted
                        )
                        localSceneLayers.add(parsed)
                    }

                    "drawing" -> {
                        val parsed = parseAmDrawingLayer(
                            parser = parser,
                            sceneW = sceneW,
                            sceneH = sceneH,
                            timeShiftMs = timeShiftMs,
                            clipWindowStartMs = clipWindowStartMs,
                            clipWindowEndMs = clipWindowEndMs,
                            parentNullId = parentNullId,
                            inheritedEffects = inheritedEffects,
                            onEffectsMapped = onEffectsMapped,
                            onXmlKeyframesCounted = onXmlKeyframesCounted
                        )
                        localSceneLayers.add(parsed)
                    }

                    "nullobj", "camera" -> {
                        val isCam = parser.name == "camera"
                        val parsed = parseAmNullOrCameraLayer(
                            parser = parser,
                            isCamera = isCam,
                            sceneW = sceneW,
                            sceneH = sceneH,
                            timeShiftMs = timeShiftMs,
                            clipWindowStartMs = clipWindowStartMs,
                            clipWindowEndMs = clipWindowEndMs,
                            parentNullId = parentNullId,
                            onEffectsMapped = onEffectsMapped,
                            onXmlKeyframesCounted = onXmlKeyframesCounted
                        )
                        localSceneLayers.add(parsed)
                    }

                    "embedScene" -> {
                        parseAmEmbedScene(
                            context = context,
                            parser = parser,
                            outerSceneW = sceneW,
                            outerSceneH = sceneH,
                            outerTimeShiftMs = timeShiftMs,
                            outerParentNullId = parentNullId,
                            layersOut = localSceneLayers,
                            onGroupFlattened = onGroupFlattened,
                            onEffectsMapped = onEffectsMapped,
                            onXmlKeyframesCounted = onXmlKeyframesCounted
                        )
                    }

                    "media", "resources", "metadata" -> {
                        skipCurrentElement(parser)
                    }

                    else -> {
                        // Unknown element tag: preserve generically as a raw layer instead of dropping silently
                        val genericLayer = parseAmUnknownGenericLayer(
                            parser = parser,
                            sceneW = sceneW,
                            sceneH = sceneH,
                            timeShiftMs = timeShiftMs,
                            clipWindowStartMs = clipWindowStartMs,
                            clipWindowEndMs = clipWindowEndMs,
                            parentNullId = parentNullId,
                            onEffectsMapped = onEffectsMapped,
                            onXmlKeyframesCounted = onXmlKeyframesCounted
                        )
                        localSceneLayers.add(genericLayer)
                    }
                }
            }
            ev = parser.next()
        }

        // Two-pass parent resolution: children in Alight Motion XML frequently appear BEFORE their parent layer!
        val rawIdToLayerId = HashMap<String, String>(localSceneLayers.size)
        for (lyr in localSceneLayers) {
            val rawId = lyr.rawAttributes["__amRawId"]
            if (!rawId.isNullOrBlank()) {
                rawIdToLayerId[rawId] = lyr.id
            }
        }
        for (lyr in localSceneLayers) {
            val rawParent = lyr.rawAttributes["__amParentRawId"]
            val resolvedParentId = if (!rawParent.isNullOrBlank()) {
                rawIdToLayerId[rawParent] ?: lyr.parentId
            } else {
                lyr.parentId
            }
            layersOut.add(
                if (resolvedParentId != lyr.parentId) lyr.copy(parentId = resolvedParentId) else lyr
            )
        }
    }

    private fun parseAmAudioLayer(
        context: Context?,
        parser: XmlPullParser,
        timeShiftMs: Long,
        clipWindowStartMs: Long,
        clipWindowEndMs: Long,
        onXmlKeyframesCounted: (Int) -> Unit
    ): Layer? {
        val rawAttrs = collectParserAttributes(parser)
        val rawId = rawAttrs["id"] ?: UUID.randomUUID().toString()
        val labelAttr = rawAttrs["label"]
        val src = rawAttrs["src"] ?: ""
        val rawStart = (rawAttrs["startTime"]?.toLongOrNull() ?: 0L) + timeShiftMs
        val rawEnd = (rawAttrs["endTime"]?.toLongOrNull() ?: 5_000L) + timeShiftMs
        val inTime = rawAttrs["inTime"]?.toLongOrNull()?.coerceAtLeast(0L) ?: 0L
        val outTime = rawAttrs["outTime"]?.toLongOrNull() ?: (rawEnd - rawStart)
        val hidden = rawAttrs["hidden"]?.toBooleanStrictOrNull() ?: false
        val mute = rawAttrs["mute"]?.toBooleanStrictOrNull() ?: false
        val speed = rawAttrs["speed"]?.toFloatOrNull()?.coerceIn(0.25f, 4f) ?: 1f
        rawAttrs["__amRawId"] = rawId
        rawAttrs["__amTag"] = "audio"

        var volume = 1f
        val elemDepth = parser.depth
        var ev = parser.next()
        while (!(ev == XmlPullParser.END_TAG && parser.depth == elemDepth && parser.name == "audio") &&
            ev != XmlPullParser.END_DOCUMENT
        ) {
            if (ev == XmlPullParser.START_TAG && parser.name == "property") {
                val pName = parser.getAttributeValue(null, "name")
                val prop = parseRawAmProperty(parser)
                onXmlKeyframesCounted(prop.keyframes.size)
                if (pName == "volume") {
                    volume = prop.staticValue?.toFloatOrNull()
                        ?: prop.keyframes.firstOrNull()?.rawVal?.toFloatOrNull()
                        ?: 1f
                }
            }
            ev = parser.next()
        }

        val startClamped = rawStart.coerceAtLeast(clipWindowStartMs).coerceAtLeast(0L)
        val endClamped = rawEnd.coerceAtMost(clipWindowEndMs)
        if (endClamped <= startClamped) return null

        val cleanLabel = labelAttr?.ifBlank { null }
            ?: src.substringAfterLast('/').substringAfterLast(':').ifBlank { "Audio $rawId" }

        return Layer(
            id = "am_aud_${rawId}_${timeShiftMs}",
            name = cleanLabel,
            type = LayerType.AUDIO,
            visible = !hidden,
            startTimeMs = startClamped,
            endTimeMs = endClamped,
            sourceOffsetMs = inTime,
            mediaTrimOutMs = outTime,
            isMissingMedia = src.isNotBlank() && !isMediaUriResolvable(context, src),
            sourceDurationMs = max(outTime, endClamped - startClamped),
            mediaUri = src,
            speedMultiplier = speed,
            audioMuted = mute,
            audioVolume = volume.coerceIn(0f, 1.5f),
            colorHex = "#38BDF8",
            rawAttributes = rawAttrs
        )
    }

    private fun parseAmShapeOrMediaLayer(
        context: Context?,
        parser: XmlPullParser,
        sceneW: Float,
        sceneH: Float,
        timeShiftMs: Long,
        clipWindowStartMs: Long,
        clipWindowEndMs: Long,
        parentNullId: String?,
        inheritedEffects: List<EffectNode>,
        onEffectsMapped: (Int) -> Unit,
        onXmlKeyframesCounted: (Int) -> Unit
    ): Layer? {
        val rawAttrs = collectParserAttributes(parser)
        val rawId = rawAttrs["id"] ?: UUID.randomUUID().toString()
        val labelAttr = rawAttrs["label"]
        val rawParentAttr = rawAttrs["parent"]
        val rawStart = (rawAttrs["startTime"]?.toLongOrNull() ?: 0L) + timeShiftMs
        val rawEnd = (rawAttrs["endTime"]?.toLongOrNull() ?: 5_000L) + timeShiftMs
        val fillType = rawAttrs["fillType"] ?: "color"
        val fillImage = rawAttrs["fillImage"]
        val fillVideo = rawAttrs["fillVideo"]
        val mediaSrc = fillVideo ?: fillImage ?: rawAttrs["media"] ?: ""
        val shapeToken = rawAttrs["s"] ?: ".rect"
        val blendingRaw = rawAttrs["blending"] ?: "normal"
        val hidden = rawAttrs["hidden"]?.toBooleanStrictOrNull() ?: false
        val speed = rawAttrs["speed"]?.toFloatOrNull()?.coerceIn(0.25f, 4f) ?: 1f
        val inTime = rawAttrs["inTime"]?.toLongOrNull()?.coerceAtLeast(0L) ?: 0L
        val outTime = rawAttrs["outTime"]?.toLongOrNull() ?: 0L
        rawAttrs["__amRawId"] = rawId
        rawAttrs["__amTag"] = "shape"
        if (!rawParentAttr.isNullOrBlank()) {
            rawAttrs["__amParentRawId"] = rawParentAttr
        }

        val transformProps = HashMap<String, RawAmProperty>()
        val shapeProps = LinkedHashMap<String, RawAmProperty>()
        val rawEffects = ArrayList<RawAmEffect>()
        var fillColorHex = "#FFFFFF"
        var fillAlphaFactor = 1f
        var strokeEnabled = false
        var strokeWidth = 4f
        var strokeColorHex = "#FFFFFF"
        var strokePos = "CENTER"
        var shadowEnabled = false
        var shadowBlur = 16f
        var shadowColorHex = "#000000"
        val shadowOffX = 0f
        val shadowOffY = 6f

        val elemDepth = parser.depth
        var ev = parser.next()
        while (!(ev == XmlPullParser.END_TAG && parser.depth == elemDepth && parser.name == "shape") &&
            ev != XmlPullParser.END_DOCUMENT
        ) {
            if (ev == XmlPullParser.START_TAG) {
                when (parser.name) {
                    "transform" -> parseAmTransformBlock(parser, transformProps, onXmlKeyframesCounted)
                    "fillColor" -> {
                        val v = parser.getAttributeValue(null, "value")
                        rawAttrs["__fillColorRaw"] = v.orEmpty()
                        val (hex, alphaF) = splitAmColorAndAlpha(v, "#FFFFFF")
                        fillColorHex = hex
                        fillAlphaFactor = alphaF
                        skipCurrentElement(parser)
                    }
                    "stroke", "border" -> {
                        val dir = parser.getAttributeValue(null, "direction") ?: "centered"
                        strokePos = when (dir.lowercase(Locale.US)) {
                            "outside" -> "OUTSIDE"
                            "inside" -> "INSIDE"
                            else -> "CENTER"
                        }
                        val colAttr = parser.getAttributeValue(null, "color")
                        if (!colAttr.isNullOrBlank()) {
                            strokeColorHex = normalizeHexColor(colAttr, "#FFFFFF")
                        }
                        val wAttr = parser.getAttributeValue(null, "width")?.toFloatOrNull()
                        if (wAttr != null && wAttr > 0.2f) {
                            strokeEnabled = true
                            strokeWidth = wAttr
                        }
                        val stDepth = parser.depth
                        var stEv = parser.next()
                        while (!(stEv == XmlPullParser.END_TAG && parser.depth == stDepth) &&
                            stEv != XmlPullParser.END_DOCUMENT
                        ) {
                            if (stEv == XmlPullParser.START_TAG && parser.name == "property") {
                                val p = parseRawAmProperty(parser)
                                onXmlKeyframesCounted(p.keyframes.size)
                                if (p.name == "size" || p.name == "width") {
                                    val wVal = p.staticValue?.toFloatOrNull()
                                        ?: p.keyframes.firstOrNull()?.rawVal?.toFloatOrNull() ?: 0f
                                    if (wVal > 0.2f) {
                                        strokeEnabled = true
                                        strokeWidth = wVal
                                    }
                                } else if (p.name == "color" && !p.staticValue.isNullOrBlank()) {
                                    strokeColorHex = normalizeHexColor(p.staticValue, strokeColorHex)
                                }
                            }
                            stEv = parser.next()
                        }
                    }
                    "shadow" -> {
                        shadowEnabled = true
                        val shCol = parser.getAttributeValue(null, "color")
                        if (!shCol.isNullOrBlank()) shadowColorHex = normalizeHexColor(shCol, "#000000")
                        shadowBlur = parser.getAttributeValue(null, "size")?.toFloatOrNull() ?: shadowBlur
                        skipCurrentElement(parser)
                    }
                    "property" -> {
                        val prop = parseRawAmProperty(parser)
                        onXmlKeyframesCounted(prop.keyframes.size)
                        shapeProps[prop.name] = prop
                        rawAttrs["__prop_${prop.name}"] = prop.staticValue ?: prop.keyframes.firstOrNull()?.rawVal.orEmpty()
                    }
                    "effect" -> {
                        rawEffects.add(parseRawAmEffect(parser, onXmlKeyframesCounted))
                    }
                    else -> {
                        rawAttrs["__child_${parser.name}"] = collectParserAttributes(parser).toString()
                        skipCurrentElement(parser)
                    }
                }
            }
            ev = parser.next()
        }

        val clipDurMs = (rawEnd - rawStart).coerceAtLeast(1L)
        val startClamped = rawStart.coerceAtLeast(clipWindowStartMs).coerceAtLeast(0L)
        val endClamped = rawEnd.coerceAtMost(clipWindowEndMs)
        if (endClamped <= startClamped) return null

        val sizeVec = shapeProps["size"]?.let { prop ->
            val raw = prop.staticValue ?: prop.keyframes.firstOrNull()?.rawVal ?: "100,100"
            parseVecFloats(raw)
        } ?: floatArrayOf(100f, 100f)
        val amHalfW = abs(sizeVec.getOrElse(0) { 100f }).coerceAtLeast(1f)
        val amHalfH = abs(sizeVec.getOrElse(1) { amHalfW }).coerceAtLeast(1f)

        val isMediaFill = fillType.equals("media", ignoreCase = true) && mediaSrc.isNotBlank()
        val isVideoUri = fillVideo != null ||
            mediaSrc.contains("/video/", ignoreCase = true) ||
            mediaSrc.endsWith(".mp4", ignoreCase = true) ||
            mediaSrc.endsWith(".mov", ignoreCase = true) ||
            mediaSrc.endsWith(".webm", ignoreCase = true)

        val amTagName = rawAttrs["__amTag"] ?: "shape"
        val layerType = when {
            amTagName == "media" && isVideoUri -> LayerType.VIDEO
            amTagName == "media" -> LayerType.IMAGE
            else -> LayerType.SHAPE
        }

        val minSceneDim = min(sceneW, sceneH).coerceAtLeast(64f)
        val baseHalfRef = if (layerType == LayerType.SHAPE) minSceneDim * 0.22f else minSceneDim * 0.50f
        val scaleNormFactorX = (amHalfW / baseHalfRef) * 100f
        val scaleNormFactorY = (amHalfH / baseHalfRef) * 100f
        val hasXmlParent = !rawParentAttr.isNullOrBlank() || parentNullId != null

        val convertedTr = buildFpTransformsFromAm(
            transformProps = transformProps,
            sceneW = sceneW,
            sceneH = sceneH,
            clipStartMs = rawStart,
            clipDurMs = clipDurMs,
            scaleFactorPctX = scaleNormFactorX,
            scaleFactorPctY = scaleNormFactorY,
            hasParent = hasXmlParent,
            extraOpacityMultiplier = fillAlphaFactor
        )

        val isMaskBlend = blendingRaw.equals("mask", ignoreCase = true) ||
            blendingRaw.equals("exclude", ignoreCase = true)

        val fxBundle = mapAmEffectsToFp(
            rawEffects = rawEffects,
            clipStartMs = rawStart,
            clipDurMs = clipDurMs
        )
        onEffectsMapped(fxBundle.effects.size)

        val allEffects = if (inheritedEffects.isNotEmpty() && !fxBundle.hasCopyBackground) {
            fxBundle.effects + inheritedEffects
        } else {
            fxBundle.effects
        }

        val shapeKind = when {
            shapeToken.contains("circle", ignoreCase = true) -> "Circle"
            shapeToken.contains("star", ignoreCase = true) -> "Star"
            shapeToken.contains("tri", ignoreCase = true) -> "Triangle"
            shapeToken.contains("poly", ignoreCase = true) || shapeToken.contains("hex", ignoreCase = true) -> "Hexagon"
            shapeToken.contains("round", ignoreCase = true) -> "Rounded Rect"
            else -> "Rectangle"
        }

        val resolvedName = when {
            !labelAttr.isNullOrBlank() -> labelAttr
            fxBundle.hasCopyBackground -> "CC / Adjustment $rawId"
            isMediaFill -> {
                val tail = mediaSrc.substringAfterLast('/').substringAfterLast(':')
                if (tail.isNotBlank()) tail else "Media $rawId"
            }
            else -> "$shapeKind $rawId"
        }

        val mediaMissing = isMediaFill && !isMediaUriResolvable(context, mediaSrc)

        return Layer(
            id = "am_${rawId}_${timeShiftMs}",
            name = resolvedName,
            type = layerType,
            visible = !hidden,
            startTimeMs = startClamped,
            endTimeMs = endClamped,
            sourceOffsetMs = inTime,
            mediaTrimOutMs = outTime,
            isMissingMedia = mediaMissing,
            sourceDurationMs = max(if (outTime > 0L) outTime else clipDurMs, clipDurMs),
            mediaWidth = (amHalfW * 2f).roundToInt().coerceAtLeast(16),
            mediaHeight = (amHalfH * 2f).roundToInt().coerceAtLeast(16),
            parentId = parentNullId,
            colorHex = if (isMediaFill) "#2B3548" else fillColorHex,
            shapeKind = shapeKind,
            blendMode = mapAmBlendMode(blendingRaw),
            isClippingMask = isMaskBlend,
            strokeEnabled = strokeEnabled,
            strokeWidth = strokeWidth,
            strokeColorHex = strokeColorHex,
            strokePosition = strokePos,
            shadowEnabled = shadowEnabled,
            shadowBlur = shadowBlur,
            shadowColorHex = shadowColorHex,
            shadowOffsetX = shadowOffX,
            shadowOffsetY = shadowOffY,
            speedMultiplier = speed,
            motionBlurEnabled = fxBundle.enableNativeMotionBlur,
            shutterAngle = fxBundle.shutterAngleDeg,
            isAdjustmentLayer = fxBundle.hasCopyBackground,
            mediaUri = mediaSrc,
            posX = convertedTr.posX,
            posY = convertedTr.posY,
            posZ = convertedTr.posZ,
            pivotX = convertedTr.pivotX,
            pivotY = convertedTr.pivotY,
            scale = convertedTr.scale,
            hasSeparateScaleY = convertedTr.hasSeparateScaleY,
            scaleY = convertedTr.scaleY,
            rotationZ = convertedTr.rotZ,
            opacity = convertedTr.opacity,
            effects = allEffects,
            rawAttributes = rawAttrs
        )
    }

    private fun parseAmTextLayer(
        parser: XmlPullParser,
        sceneW: Float,
        sceneH: Float,
        timeShiftMs: Long,
        clipWindowStartMs: Long,
        clipWindowEndMs: Long,
        parentNullId: String?,
        inheritedEffects: List<EffectNode>,
        onEffectsMapped: (Int) -> Unit,
        onXmlKeyframesCounted: (Int) -> Unit
    ): Layer {
        val rawAttrs = collectParserAttributes(parser)
        val rawId = rawAttrs["id"] ?: UUID.randomUUID().toString()
        val labelAttr = rawAttrs["label"]
        val rawParentAttr = rawAttrs["parent"]
        val rawStart = (rawAttrs["startTime"]?.toLongOrNull() ?: 0L) + timeShiftMs
        val rawEnd = (rawAttrs["endTime"]?.toLongOrNull() ?: 5_000L) + timeShiftMs
        val blendingRaw = rawAttrs["blending"] ?: "normal"
        val hidden = rawAttrs["hidden"]?.toBooleanStrictOrNull() ?: false
        val fontAttr = rawAttrs["font"] ?: ""
        val sizeSpAttr = rawAttrs["size"]?.toFloatOrNull() ?: 18f
        val alignAttr = rawAttrs["align"] ?: "center"
        rawAttrs["__amRawId"] = rawId
        rawAttrs["__amTag"] = "text"
        if (!rawParentAttr.isNullOrBlank()) {
            rawAttrs["__amParentRawId"] = rawParentAttr
        }

        val transformProps = HashMap<String, RawAmProperty>()
        val rawEffects = ArrayList<RawAmEffect>()
        var textBody = ""
        var fillColorHex = "#FFFFFF"
        var fillAlpha = 1f

        val elemDepth = parser.depth
        var ev = parser.next()
        while (!(ev == XmlPullParser.END_TAG && parser.depth == elemDepth && parser.name == "text") &&
            ev != XmlPullParser.END_DOCUMENT
        ) {
            if (ev == XmlPullParser.START_TAG) {
                when (parser.name) {
                    "transform" -> parseAmTransformBlock(parser, transformProps, onXmlKeyframesCounted)
                    "fillColor" -> {
                        val v = parser.getAttributeValue(null, "value")
                        rawAttrs["__fillColorRaw"] = v.orEmpty()
                        val (hex, a) = splitAmColorAndAlpha(v, "#FFFFFF")
                        fillColorHex = hex
                        fillAlpha = a
                        skipCurrentElement(parser)
                    }
                    "content" -> {
                        textBody = parser.nextText()
                    }
                    "effect" -> {
                        rawEffects.add(parseRawAmEffect(parser, onXmlKeyframesCounted))
                    }
                    else -> {
                        rawAttrs["__child_${parser.name}"] = collectParserAttributes(parser).toString()
                        skipCurrentElement(parser)
                    }
                }
            } else if (ev == XmlPullParser.TEXT && parser.depth == elemDepth + 1 && textBody.isBlank()) {
                val txt = parser.text?.trim().orEmpty()
                if (txt.isNotEmpty()) textBody = txt
            }
            ev = parser.next()
        }

        val clipDurMs = (rawEnd - rawStart).coerceAtLeast(1L)
        val startClamped = rawStart.coerceAtLeast(clipWindowStartMs).coerceAtLeast(0L)
        val endClamped = rawEnd.coerceAtMost(clipWindowEndMs).coerceAtLeast(startClamped + 1L)
        val hasXmlParent = !rawParentAttr.isNullOrBlank() || parentNullId != null

        val convertedTr = buildFpTransformsFromAm(
            transformProps = transformProps,
            sceneW = sceneW,
            sceneH = sceneH,
            clipStartMs = rawStart,
            clipDurMs = clipDurMs,
            scaleFactorPctX = 100f,
            scaleFactorPctY = 100f,
            hasParent = hasXmlParent,
            extraOpacityMultiplier = fillAlpha
        )
        val fxBundle = mapAmEffectsToFp(rawEffects, rawStart, clipDurMs)
        onEffectsMapped(fxBundle.effects.size)

        val cleanFont = fontAttr.substringAfter("?name=", missingDelimiterValue = fontAttr)
            .substringBefore('&')
            .ifBlank { "Space Grotesk" }

        val mappedAlign = when (alignAttr.lowercase(Locale.US)) {
            "left" -> "LEFT"
            "right" -> "RIGHT"
            else -> "CENTER"
        }

        val finalText = textBody.ifEmpty { labelAttr ?: "" }
        return Layer(
            id = "am_txt_${rawId}_${timeShiftMs}",
            name = labelAttr?.ifBlank { null } ?: finalText.trim().take(24).ifBlank { "Text $rawId" },
            type = LayerType.TEXT,
            visible = !hidden,
            startTimeMs = startClamped,
            endTimeMs = endClamped,
            parentId = parentNullId,
            colorHex = fillColorHex,
            blendMode = mapAmBlendMode(blendingRaw),
            motionBlurEnabled = fxBundle.enableNativeMotionBlur,
            shutterAngle = fxBundle.shutterAngleDeg,
            textContent = finalText,
            fontFamilyName = cleanFont,
            textSizeSp = sizeSpAttr,
            textAlign = mappedAlign,
            posX = convertedTr.posX,
            posY = convertedTr.posY,
            posZ = convertedTr.posZ,
            pivotX = convertedTr.pivotX,
            pivotY = convertedTr.pivotY,
            scale = convertedTr.scale,
            hasSeparateScaleY = convertedTr.hasSeparateScaleY,
            scaleY = convertedTr.scaleY,
            rotationZ = convertedTr.rotZ,
            opacity = convertedTr.opacity,
            effects = fxBundle.effects + inheritedEffects,
            rawAttributes = rawAttrs
        )
    }

    private fun parseAmDrawingLayer(
        parser: XmlPullParser,
        sceneW: Float,
        sceneH: Float,
        timeShiftMs: Long,
        clipWindowStartMs: Long,
        clipWindowEndMs: Long,
        parentNullId: String?,
        inheritedEffects: List<EffectNode>,
        onEffectsMapped: (Int) -> Unit,
        onXmlKeyframesCounted: (Int) -> Unit
    ): Layer {
        val rawAttrs = collectParserAttributes(parser)
        val rawId = rawAttrs["id"] ?: UUID.randomUUID().toString()
        val labelAttr = rawAttrs["label"] ?: "Drawing $rawId"
        val rawParentAttr = rawAttrs["parent"]
        val rawStart = (rawAttrs["startTime"]?.toLongOrNull() ?: 0L) + timeShiftMs
        val rawEnd = (rawAttrs["endTime"]?.toLongOrNull() ?: 5_000L) + timeShiftMs
        val blendingRaw = rawAttrs["blending"] ?: "normal"
        val hidden = rawAttrs["hidden"]?.toBooleanStrictOrNull() ?: false
        rawAttrs["__amRawId"] = rawId
        rawAttrs["__amTag"] = "drawing"
        if (!rawParentAttr.isNullOrBlank()) {
            rawAttrs["__amParentRawId"] = rawParentAttr
        }

        val transformProps = HashMap<String, RawAmProperty>()
        val rawEffects = ArrayList<RawAmEffect>()
        val parsedPoints = ArrayList<DrawingPoint>()
        var strokeColorHex = "#FF4444"
        var strokeWidth = 12f
        var strokeAlpha = 1f

        val elemDepth = parser.depth
        var ev = parser.next()
        while (!(ev == XmlPullParser.END_TAG && parser.depth == elemDepth && parser.name == "drawing") &&
            ev != XmlPullParser.END_DOCUMENT
        ) {
            if (ev == XmlPullParser.START_TAG) {
                when (parser.name) {
                    "transform" -> parseAmTransformBlock(parser, transformProps, onXmlKeyframesCounted)
                    "effect" -> rawEffects.add(parseRawAmEffect(parser, onXmlKeyframesCounted))
                    "stroke" -> {
                        val sAttrs = collectParserAttributes(parser)
                        val (hex, a) = splitAmColorAndAlpha(sAttrs["color"], "#FF4444")
                        strokeColorHex = hex
                        strokeAlpha = a
                        strokeWidth = sAttrs["width"]?.toFloatOrNull()?.coerceAtLeast(0.5f) ?: 12f
                        rawAttrs["__strokeType"] = sAttrs["type"] ?: "pen"
                        val ptsStr = sAttrs["points"].orEmpty()
                        if (ptsStr.isNotBlank()) {
                            ptsStr.split(';').forEach { token ->
                                val parts = token.trim().split(',')
                                if (parts.size >= 2) {
                                    val px = parts[0].trim().toFloatOrNull() ?: 0f
                                    val py = parts[1].trim().toFloatOrNull() ?: 0f
                                    val pr = parts.getOrNull(2)?.trim()?.toFloatOrNull() ?: 1f
                                    parsedPoints.add(DrawingPoint(px, py, pr))
                                }
                            }
                        }
                        skipCurrentElement(parser)
                    }
                    else -> {
                        rawAttrs["__child_${parser.name}"] = collectParserAttributes(parser).toString()
                        skipCurrentElement(parser)
                    }
                }
            }
            ev = parser.next()
        }

        val clipDurMs = (rawEnd - rawStart).coerceAtLeast(1L)
        val startClamped = rawStart.coerceAtLeast(clipWindowStartMs).coerceAtLeast(0L)
        val endClamped = rawEnd.coerceAtMost(clipWindowEndMs).coerceAtLeast(startClamped + 1L)
        val hasXmlParent = !rawParentAttr.isNullOrBlank() || parentNullId != null

        val convertedTr = buildFpTransformsFromAm(
            transformProps = transformProps,
            sceneW = sceneW,
            sceneH = sceneH,
            clipStartMs = rawStart,
            clipDurMs = clipDurMs,
            scaleFactorPctX = 100f,
            scaleFactorPctY = 100f,
            hasParent = hasXmlParent,
            extraOpacityMultiplier = strokeAlpha
        )
        val fxBundle = mapAmEffectsToFp(rawEffects, rawStart, clipDurMs)
        onEffectsMapped(fxBundle.effects.size)

        return Layer(
            id = "am_drw_${rawId}_${timeShiftMs}",
            name = labelAttr,
            type = LayerType.DRAWING,
            visible = !hidden,
            startTimeMs = startClamped,
            endTimeMs = endClamped,
            parentId = parentNullId,
            colorHex = strokeColorHex,
            blendMode = mapAmBlendMode(blendingRaw),
            drawingPoints = parsedPoints,
            drawingStrokeWidth = strokeWidth,
            drawingStrokeColorHex = strokeColorHex,
            posX = convertedTr.posX,
            posY = convertedTr.posY,
            posZ = convertedTr.posZ,
            pivotX = convertedTr.pivotX,
            pivotY = convertedTr.pivotY,
            scale = convertedTr.scale,
            hasSeparateScaleY = convertedTr.hasSeparateScaleY,
            scaleY = convertedTr.scaleY,
            rotationZ = convertedTr.rotZ,
            opacity = convertedTr.opacity,
            effects = fxBundle.effects + inheritedEffects,
            rawAttributes = rawAttrs
        )
    }

    private fun parseAmUnknownGenericLayer(
        parser: XmlPullParser,
        sceneW: Float,
        sceneH: Float,
        timeShiftMs: Long,
        clipWindowStartMs: Long,
        clipWindowEndMs: Long,
        parentNullId: String?,
        onEffectsMapped: (Int) -> Unit,
        onXmlKeyframesCounted: (Int) -> Unit
    ): Layer {
        val tagName = parser.name ?: "unknown"
        val rawAttrs = collectParserAttributes(parser)
        val rawId = rawAttrs["id"] ?: UUID.randomUUID().toString()
        val labelAttr = rawAttrs["label"] ?: "$tagName $rawId"
        val rawParentAttr = rawAttrs["parent"]
        val rawStart = (rawAttrs["startTime"]?.toLongOrNull() ?: 0L) + timeShiftMs
        val rawEnd = (rawAttrs["endTime"]?.toLongOrNull() ?: 5_000L) + timeShiftMs
        val hidden = rawAttrs["hidden"]?.toBooleanStrictOrNull() ?: false
        rawAttrs["__amRawId"] = rawId
        rawAttrs["__amTag"] = tagName
        rawAttrs["__unknownElementTag"] = tagName
        if (!rawParentAttr.isNullOrBlank()) {
            rawAttrs["__amParentRawId"] = rawParentAttr
        }

        val transformProps = HashMap<String, RawAmProperty>()
        val rawEffects = ArrayList<RawAmEffect>()
        val elemDepth = parser.depth
        var ev = parser.next()
        while (!(ev == XmlPullParser.END_TAG && parser.depth == elemDepth && parser.name == tagName) &&
            ev != XmlPullParser.END_DOCUMENT
        ) {
            if (ev == XmlPullParser.START_TAG) {
                when (parser.name) {
                    "transform" -> parseAmTransformBlock(parser, transformProps, onXmlKeyframesCounted)
                    "effect" -> rawEffects.add(parseRawAmEffect(parser, onXmlKeyframesCounted))
                    else -> {
                        rawAttrs["__child_${parser.name}"] = collectParserAttributes(parser).toString()
                        skipCurrentElement(parser)
                    }
                }
            }
            ev = parser.next()
        }

        val clipDurMs = (rawEnd - rawStart).coerceAtLeast(1L)
        val startClamped = rawStart.coerceAtLeast(clipWindowStartMs).coerceAtLeast(0L)
        val endClamped = rawEnd.coerceAtMost(clipWindowEndMs).coerceAtLeast(startClamped + 1L)
        val hasXmlParent = !rawParentAttr.isNullOrBlank() || parentNullId != null
        val convertedTr = buildFpTransformsFromAm(
            transformProps = transformProps,
            sceneW = sceneW,
            sceneH = sceneH,
            clipStartMs = rawStart,
            clipDurMs = clipDurMs,
            scaleFactorPctX = 100f,
            scaleFactorPctY = 100f,
            hasParent = hasXmlParent
        )
        val fxBundle = mapAmEffectsToFp(rawEffects, rawStart, clipDurMs)
        onEffectsMapped(fxBundle.effects.size)

        return Layer(
            id = "am_unk_${rawId}_${timeShiftMs}",
            name = labelAttr,
            type = LayerType.SHAPE,
            visible = !hidden,
            startTimeMs = startClamped,
            endTimeMs = endClamped,
            parentId = parentNullId,
            posX = convertedTr.posX,
            posY = convertedTr.posY,
            posZ = convertedTr.posZ,
            scale = convertedTr.scale,
            hasSeparateScaleY = convertedTr.hasSeparateScaleY,
            scaleY = convertedTr.scaleY,
            rotationZ = convertedTr.rotZ,
            opacity = convertedTr.opacity,
            effects = fxBundle.effects,
            rawAttributes = rawAttrs
        )
    }

    private fun parseAmNullOrCameraLayer(
        parser: XmlPullParser,
        isCamera: Boolean,
        sceneW: Float,
        sceneH: Float,
        timeShiftMs: Long,
        clipWindowStartMs: Long,
        clipWindowEndMs: Long,
        parentNullId: String?,
        onEffectsMapped: (Int) -> Unit,
        onXmlKeyframesCounted: (Int) -> Unit
    ): Layer {
        val tagName = parser.name
        val rawAttrs = collectParserAttributes(parser)
        val rawId = rawAttrs["id"] ?: UUID.randomUUID().toString()
        val labelAttr = rawAttrs["label"]
        val rawParentAttr = rawAttrs["parent"]
        val rawStart = (rawAttrs["startTime"]?.toLongOrNull() ?: 0L) + timeShiftMs
        val rawEnd = (rawAttrs["endTime"]?.toLongOrNull() ?: 5_000L) + timeShiftMs
        val hidden = rawAttrs["hidden"]?.toBooleanStrictOrNull() ?: false
        rawAttrs["__amRawId"] = rawId
        rawAttrs["__amTag"] = tagName
        if (!rawParentAttr.isNullOrBlank()) {
            rawAttrs["__amParentRawId"] = rawParentAttr
        }

        val transformProps = HashMap<String, RawAmProperty>()
        val rawEffects = ArrayList<RawAmEffect>()

        val elemDepth = parser.depth
        var ev = parser.next()
        while (!(ev == XmlPullParser.END_TAG && parser.depth == elemDepth && parser.name == tagName) &&
            ev != XmlPullParser.END_DOCUMENT
        ) {
            if (ev == XmlPullParser.START_TAG) {
                when (parser.name) {
                    "transform" -> parseAmTransformBlock(parser, transformProps, onXmlKeyframesCounted)
                    "effect" -> rawEffects.add(parseRawAmEffect(parser, onXmlKeyframesCounted))
                    else -> {
                        rawAttrs["__child_${parser.name}"] = collectParserAttributes(parser).toString()
                        skipCurrentElement(parser)
                    }
                }
            }
            ev = parser.next()
        }

        val clipDurMs = (rawEnd - rawStart).coerceAtLeast(1L)
        val startClamped = rawStart.coerceAtLeast(clipWindowStartMs).coerceAtLeast(0L)
        val endClamped = rawEnd.coerceAtMost(clipWindowEndMs).coerceAtLeast(startClamped + 1L)
        val hasXmlParent = !rawParentAttr.isNullOrBlank() || parentNullId != null
        val convertedTr = buildFpTransformsFromAm(
            transformProps = transformProps,
            sceneW = sceneW,
            sceneH = sceneH,
            clipStartMs = rawStart,
            clipDurMs = clipDurMs,
            scaleFactorPctX = 100f,
            scaleFactorPctY = 100f,
            hasParent = hasXmlParent
        )
        val fxBundle = mapAmEffectsToFp(rawEffects, rawStart, clipDurMs)
        onEffectsMapped(fxBundle.effects.size)

        return Layer(
            id = "am_${if (isCamera) "cam" else "null"}_${rawId}_${timeShiftMs}",
            name = labelAttr?.ifBlank { null } ?: if (isCamera) "Camera $rawId" else "Null $rawId",
            type = if (isCamera) LayerType.CAMERA else LayerType.NULL_OBJECT,
            visible = !hidden,
            startTimeMs = startClamped,
            endTimeMs = endClamped,
            parentId = parentNullId,
            colorHex = if (isCamera) "#6BA6FF" else "#EF4444",
            posX = convertedTr.posX,
            posY = convertedTr.posY,
            posZ = convertedTr.posZ,
            pivotX = convertedTr.pivotX,
            pivotY = convertedTr.pivotY,
            scale = convertedTr.scale,
            hasSeparateScaleY = convertedTr.hasSeparateScaleY,
            scaleY = convertedTr.scaleY,
            rotationZ = convertedTr.rotZ,
            opacity = convertedTr.opacity,
            effects = fxBundle.effects,
            rawAttributes = rawAttrs
        )
    }

    private fun parseAmEmbedScene(
        context: Context?,
        parser: XmlPullParser,
        outerSceneW: Float,
        outerSceneH: Float,
        outerTimeShiftMs: Long,
        outerParentNullId: String?,
        layersOut: MutableList<Layer>,
        onGroupFlattened: () -> Unit,
        onEffectsMapped: (Int) -> Unit,
        onXmlKeyframesCounted: (Int) -> Unit
    ) {
        val rawAttrs = collectParserAttributes(parser)
        val rawId = rawAttrs["id"] ?: UUID.randomUUID().toString()
        val labelAttr = rawAttrs["label"]?.ifBlank { null } ?: "Group $rawId"
        val rawParentAttr = rawAttrs["parent"]
        val embedStartMs = (rawAttrs["startTime"]?.toLongOrNull() ?: 0L) + outerTimeShiftMs
        val embedEndMs = (rawAttrs["endTime"]?.toLongOrNull() ?: 5_000L) + outerTimeShiftMs
        val inTimeMs = rawAttrs["inTime"]?.toLongOrNull() ?: 0L
        val hidden = rawAttrs["hidden"]?.toBooleanStrictOrNull() ?: false
        rawAttrs["__amRawId"] = rawId
        rawAttrs["__amTag"] = "embedScene"
        if (!rawParentAttr.isNullOrBlank()) {
            rawAttrs["__amParentRawId"] = rawParentAttr
        }

        val outerTransformProps = HashMap<String, RawAmProperty>()
        val outerRawEffects = ArrayList<RawAmEffect>()
        val groupNullId = "am_grp_${rawId}_${embedStartMs}"
        val innerChildLayers = ArrayList<Layer>()

        val embedDurMs = (embedEndMs - embedStartMs).coerceAtLeast(1L)
        val childShiftMs = embedStartMs - inTimeMs

        val embedDepth = parser.depth
        var ev = parser.next()
        while (!(ev == XmlPullParser.END_TAG && parser.depth == embedDepth && parser.name == "embedScene") &&
            ev != XmlPullParser.END_DOCUMENT
        ) {
            if (ev == XmlPullParser.START_TAG) {
                when (parser.name) {
                    "transform" -> parseAmTransformBlock(parser, outerTransformProps, onXmlKeyframesCounted)
                    "effect" -> outerRawEffects.add(parseRawAmEffect(parser, onXmlKeyframesCounted))
                    "scene" -> {
                        val innerW = parser.getAttributeValue(null, "width")?.toFloatOrNull() ?: outerSceneW
                        val innerH = parser.getAttributeValue(null, "height")?.toFloatOrNull() ?: outerSceneH
                        parseAmSceneBody(
                            context = context,
                            parser = parser,
                            sceneW = innerW,
                            sceneH = innerH,
                            timeShiftMs = childShiftMs,
                            clipWindowStartMs = embedStartMs.coerceAtLeast(0L),
                            clipWindowEndMs = embedEndMs,
                            parentNullId = groupNullId,
                            inheritedEffects = emptyList(),
                            bookmarksOut = null,
                            layersOut = innerChildLayers,
                            onGroupFlattened = onGroupFlattened,
                            onEffectsMapped = onEffectsMapped,
                            onXmlKeyframesCounted = onXmlKeyframesCounted
                        )
                    }
                    else -> skipCurrentElement(parser)
                }
            }
            ev = parser.next()
        }

        val hasXmlParent = !rawParentAttr.isNullOrBlank() || outerParentNullId != null
        val outerTr = buildFpTransformsFromAm(
            transformProps = outerTransformProps,
            sceneW = outerSceneW,
            sceneH = outerSceneH,
            clipStartMs = embedStartMs,
            clipDurMs = embedDurMs,
            scaleFactorPctX = 100f,
            scaleFactorPctY = 100f,
            hasParent = hasXmlParent
        )
        val outerFxBundle = mapAmEffectsToFp(outerRawEffects, embedStartMs, embedDurMs)
        onEffectsMapped(outerFxBundle.effects.size)

        onGroupFlattened()

        val groupRig = Layer(
            id = groupNullId,
            name = labelAttr,
            type = LayerType.GROUP,
            visible = !hidden,
            groupExpanded = false,
            startTimeMs = embedStartMs.coerceAtLeast(0L),
            endTimeMs = embedEndMs.coerceAtLeast(embedStartMs + 1L),
            parentId = outerParentNullId,
            colorHex = "#8B5CF6",
            posX = outerTr.posX,
            posY = outerTr.posY,
            posZ = outerTr.posZ,
            pivotX = outerTr.pivotX,
            pivotY = outerTr.pivotY,
            scale = outerTr.scale,
            hasSeparateScaleY = outerTr.hasSeparateScaleY,
            scaleY = outerTr.scaleY,
            rotationZ = outerTr.rotZ,
            opacity = outerTr.opacity,
            effects = outerFxBundle.effects,
            rawAttributes = rawAttrs
        )
        layersOut.add(groupRig)

        innerChildLayers.forEach { child ->
            layersOut.add(if (hidden) child.copy(visible = false) else child)
        }
    }

    private data class ConvertedFpTransforms(
        val posX: AnimatableProperty,
        val posY: AnimatableProperty,
        val posZ: AnimatableProperty,
        val pivotX: AnimatableProperty,
        val pivotY: AnimatableProperty,
        val scale: AnimatableProperty,
        val hasSeparateScaleY: Boolean,
        val scaleY: AnimatableProperty,
        val rotZ: AnimatableProperty,
        val opacity: AnimatableProperty,
        val rawAmBaseScale: Float
    )

    /**
     * Converts Alight Motion `<transform>` properties into FramePollution's composition coordinate system:
     * - Top-level layers (`!hasParent`): AM `(0,0)` is top-left and `(sceneW/2, sceneH/2)` is canvas center -> subtract `(halfW, halfH)`.
     * - Child layers (`hasParent`): AM `<location>` is already parent-relative (`(0,0)` = parent origin) -> keep `(0,0)` origin!
     * - Keyframes are sorted by `normT` WITHOUT clamping `normT` outside `0..1`, and `e="..."` on arrival key `kfs[i+1]`
     *   governs segment `kfs[i] -> kfs[i+1]` (`outTangent` on `kfs[i]`, `inTangent` + `rawEasing` on `kfs[i+1]`).
     */
    private fun buildFpTransformsFromAm(
        transformProps: Map<String, RawAmProperty>,
        sceneW: Float,
        sceneH: Float,
        clipStartMs: Long,
        clipDurMs: Long,
        scaleFactorPctX: Float,
        scaleFactorPctY: Float = scaleFactorPctX,
        hasParent: Boolean = false,
        extraOpacityMultiplier: Float = 1f
    ): ConvertedFpTransforms {
        val originOffsetX = if (hasParent) 0f else sceneW * 0.5f
        val originOffsetY = if (hasParent) 0f else sceneH * 0.5f

        // 1. Location (x, y, z)
        val locProp = transformProps["location"]
        val locKfsSorted = locProp?.keyframes?.sortedBy { it.normT }.orEmpty()
        val baseLocVec = locProp?.staticValue?.let { parseVecFloats(it) }
            ?: locKfsSorted.firstOrNull()?.rawVal?.let { parseVecFloats(it) }
            ?: floatArrayOf(originOffsetX, originOffsetY, 0f)

        val kfsX = buildSegmentAlignedKeyframes(locKfsSorted, clipStartMs, clipDurMs) { rawVal ->
            val v = parseVecFloats(rawVal)
            v.getOrElse(0) { originOffsetX } - originOffsetX
        }
        val kfsY = buildSegmentAlignedKeyframes(locKfsSorted, clipStartMs, clipDurMs) { rawVal ->
            val v = parseVecFloats(rawVal)
            v.getOrElse(1) { originOffsetY } - originOffsetY
        }
        val kfsZ = buildSegmentAlignedKeyframes(locKfsSorted, clipStartMs, clipDurMs) { rawVal ->
            val v = parseVecFloats(rawVal)
            v.getOrElse(2) { 0f }
        }.let { list ->
            if (list.any { abs(it.value) > 0.01f }) list else emptyList()
        }

        // 2. Pivot (dx, dy)
        val pivProp = transformProps["pivot"]
        val pivKfsSorted = pivProp?.keyframes?.sortedBy { it.normT }.orEmpty()
        val basePivVec = pivProp?.staticValue?.let { parseVecFloats(it) }
            ?: pivKfsSorted.firstOrNull()?.rawVal?.let { parseVecFloats(it) }
            ?: floatArrayOf(0f, 0f)
        val kfsPivX = buildSegmentAlignedKeyframes(pivKfsSorted, clipStartMs, clipDurMs) { rawVal ->
            parseVecFloats(rawVal).getOrElse(0) { 0f }
        }
        val kfsPivY = buildSegmentAlignedKeyframes(pivKfsSorted, clipStartMs, clipDurMs) { rawVal ->
            parseVecFloats(rawVal).getOrElse(1) { 0f }
        }

        // 3. Scale (sx, sy) -> preserve both uniform and anisotropic scaleY
        val sclProp = transformProps["scale"]
        val sclKfsSorted = sclProp?.keyframes?.sortedBy { it.normT }.orEmpty()
        val baseSclVec = sclProp?.staticValue?.let { parseVecFloats(it) }
            ?: sclKfsSorted.firstOrNull()?.rawVal?.let { parseVecFloats(it) }
            ?: floatArrayOf(1f, 1f)
        val baseSxRaw = baseSclVec.getOrElse(0) { 1f }
        val baseSyRaw = baseSclVec.getOrElse(1) { baseSxRaw }
        val rawBaseScale = max(abs(baseSxRaw), abs(baseSyRaw))
        val sclKfsX = buildSegmentAlignedKeyframes(sclKfsSorted, clipStartMs, clipDurMs) { rawVal ->
            val v = parseVecFloats(rawVal)
            (abs(v.getOrElse(0) { 1f }) * scaleFactorPctX).coerceAtLeast(0f)
        }
        val sclKfsY = buildSegmentAlignedKeyframes(sclKfsSorted, clipStartMs, clipDurMs) { rawVal ->
            val v = parseVecFloats(rawVal)
            val sx = v.getOrElse(0) { 1f }
            val sy = v.getOrElse(1) { sx }
            (abs(sy) * scaleFactorPctY).coerceAtLeast(0f)
        }
        val separateScaleY = abs(baseSxRaw * scaleFactorPctX - baseSyRaw * scaleFactorPctY) > 0.05f ||
            sclKfsX.indices.any { idx -> abs(sclKfsX[idx].value - sclKfsY[idx].value) > 0.05f }

        // 4. Rotation (degrees)
        val rotProp = transformProps["rotation"]
        val rotKfsSorted = rotProp?.keyframes?.sortedBy { it.normT }.orEmpty()
        val baseRot = rotProp?.staticValue?.toFloatOrNull()
            ?: rotKfsSorted.firstOrNull()?.rawVal?.toFloatOrNull()
            ?: 0f
        val rotKfs = buildSegmentAlignedKeyframes(rotKfsSorted, clipStartMs, clipDurMs) { rawVal ->
            rawVal.toFloatOrNull() ?: 0f
        }

        // 5. Opacity (0..1 in AM -> 0..100% in FP)
        val opProp = transformProps["opacity"]
        val opKfsSorted = opProp?.keyframes?.sortedBy { it.normT }.orEmpty()
        val baseOp01 = opProp?.staticValue?.toFloatOrNull()
            ?: opKfsSorted.firstOrNull()?.rawVal?.toFloatOrNull()
            ?: 1f
        val opMult = extraOpacityMultiplier.coerceIn(0f, 1f)
        val opKfs = buildSegmentAlignedKeyframes(opKfsSorted, clipStartMs, clipDurMs) { rawVal ->
            val raw01 = (rawVal.toFloatOrNull() ?: 1f).coerceIn(0f, 1f)
            (raw01 * opMult * 100f).coerceIn(0f, 100f)
        }

        val finalBaseScaleX = (abs(baseSxRaw) * scaleFactorPctX).coerceAtLeast(0f)
        val finalBaseScaleY = (abs(baseSyRaw) * scaleFactorPctY).coerceAtLeast(0f)

        return ConvertedFpTransforms(
            posX = AnimatableProperty(baseLocVec.getOrElse(0) { originOffsetX } - originOffsetX, deduplicateKeyframes(kfsX)),
            posY = AnimatableProperty(baseLocVec.getOrElse(1) { originOffsetY } - originOffsetY, deduplicateKeyframes(kfsY)),
            posZ = AnimatableProperty(baseLocVec.getOrElse(2) { 0f }, deduplicateKeyframes(kfsZ)),
            pivotX = AnimatableProperty(basePivVec.getOrElse(0) { 0f }, deduplicateKeyframes(kfsPivX)),
            pivotY = AnimatableProperty(basePivVec.getOrElse(1) { 0f }, deduplicateKeyframes(kfsPivY)),
            scale = AnimatableProperty(finalBaseScaleX, deduplicateKeyframes(sclKfsX)),
            hasSeparateScaleY = separateScaleY,
            scaleY = AnimatableProperty(finalBaseScaleY, deduplicateKeyframes(sclKfsY)),
            rotZ = AnimatableProperty(baseRot, deduplicateKeyframes(rotKfs)),
            opacity = AnimatableProperty((baseOp01.coerceIn(0f, 1f) * opMult * 100f).coerceIn(0f, 100f), deduplicateKeyframes(opKfs)),
            rawAmBaseScale = rawBaseScale
        )
    }

    private data class MappedEffectsBundle(
        val effects: List<EffectNode>,
        val hasCopyBackground: Boolean,
        val enableNativeMotionBlur: Boolean,
        val shutterAngleDeg: Float
    )

    private fun populateGenericXmlProperties(
        rawFx: RawAmEffect,
        clipStartMs: Long,
        clipDurMs: Long
    ): Triple<LinkedHashMap<String, AnimatableProperty>, LinkedHashMap<String, String>, LinkedHashMap<String, String>> {
        val customParams = LinkedHashMap<String, AnimatableProperty>()
        val customColors = LinkedHashMap<String, String>()
        val paramTypes = LinkedHashMap<String, String>()
        for ((pName, rawProp) in rawFx.properties) {
            val pType = rawProp.type.lowercase(Locale.US)
            paramTypes[pName] = pType
            when (pType) {
                "color" -> {
                    val colHex = normalizeHexColor(
                        rawProp.staticValue ?: rawProp.keyframes.firstOrNull()?.rawVal.orEmpty(),
                        "#FFFFFF"
                    )
                    customColors[pName] = colHex
                }
                "vec2", "vec3" -> {
                    val sortedKfs = rawProp.keyframes.sortedBy { it.normT }
                    val baseVec = rawProp.staticValue?.let { parseVecFloats(it) }
                        ?: sortedKfs.firstOrNull()?.rawVal?.let { parseVecFloats(it) }
                        ?: floatArrayOf(0f, 0f)
                    val kfsX = buildSegmentAlignedKeyframes(sortedKfs, clipStartMs, clipDurMs) { rv ->
                        parseVecFloats(rv).getOrElse(0) { 0f }
                    }
                    val kfsY = buildSegmentAlignedKeyframes(sortedKfs, clipStartMs, clipDurMs) { rv ->
                        parseVecFloats(rv).getOrElse(1) { 0f }
                    }
                    customParams["${pName}_x"] = AnimatableProperty(baseVec.getOrElse(0) { 0f }, deduplicateKeyframes(kfsX))
                    customParams["${pName}_y"] = AnimatableProperty(baseVec.getOrElse(1) { 0f }, deduplicateKeyframes(kfsY))
                }
                "bool" -> {
                    val bVal = if (rawProp.staticValue.equals("true", ignoreCase = true) || rawProp.staticValue == "1") 1f else 0f
                    customParams[pName] = AnimatableProperty(bVal)
                }
                else -> {
                    customParams[pName] = convertScalarProp(rawProp, clipStartMs, clipDurMs, 0f)
                }
            }
        }
        return Triple(customParams, customColors, paramTypes)
    }

    private fun mapAmEffectsToFp(
        rawEffects: List<RawAmEffect>,
        clipStartMs: Long,
        clipDurMs: Long
    ): MappedEffectsBundle {
        val mapped = ArrayList<EffectNode>()
        var hasCopyBg = false
        var nativeMb = false
        var shutterDeg = 180f

        for (rawFx in rawEffects) {
            val lowerFullId = rawFx.id.lowercase(Locale.US)
            val shortId = rawFx.id.substringAfterLast('.').lowercase(Locale.US)
            val (genericParams, genericColors, genericTypes) = populateGenericXmlProperties(rawFx, clipStartMs, clipDurMs)

            when {
                lowerFullId.endsWith("drawing.progress") || shortId == "progress" -> {
                    val startProp = convertScalarProp(rawFx.properties["start"], clipStartMs, clipDurMs, 0f)
                    val endProp = convertScalarProp(rawFx.properties["end"], clipStartMs, clipDurMs, 100f)
                    mapped.add(
                        EffectNode(
                            effectId = rawFx.id.ifBlank { "com.alightcreative.effects.drawing.progress" },
                            displayName = rawFx.label ?: "Drawing Progress",
                            category = "Drawing & Edge",
                            intensity = startProp.baseValue,
                            primaryProp = startProp,
                            secondaryParam = endProp.baseValue,
                            secondaryProp = endProp,
                            isGenericXmlEffect = true,
                            genericParamTypes = genericTypes,
                            customParams = genericParams,
                            customColors = genericColors,
                            genericStrings = rawFx.rawAttributes
                        )
                    )
                }

                shortId == "fade" -> {
                    val inProp = convertScalarProp(rawFx.properties["inTime"], clipStartMs, clipDurMs, 0f)
                    val outProp = convertScalarProp(rawFx.properties["outTime"], clipStartMs, clipDurMs, 0f)
                    mapped.add(
                        EffectNode(
                            effectId = rawFx.id.ifBlank { "com.alightcreative.effects.fade" },
                            displayName = rawFx.label ?: "Fade In / Out",
                            category = "Opacity / Visibility",
                            intensity = inProp.baseValue,
                            primaryProp = inProp,
                            secondaryParam = outProp.baseValue,
                            secondaryProp = outProp,
                            isGenericXmlEffect = true,
                            genericParamTypes = genericTypes,
                            customParams = genericParams,
                            customColors = genericColors,
                            genericStrings = rawFx.rawAttributes
                        )
                    )
                }

                shortId == "lift" || shortId == "copybackground" -> {
                    hasCopyBg = true
                    val def = EffectsCatalog.findById("Copy_Background")
                    mapped.add(def.toNode().copy(customParams = genericParams, customColors = genericColors, genericStrings = rawFx.rawAttributes))
                }

                shortId == "tile" || shortId == "tiles" -> {
                    val def = EffectsCatalog.findById("Tiles")
                    val scaleProp = convertScalarProp(rawFx.properties["scale"], clipStartMs, clipDurMs, 1.0f) { v ->
                        v.coerceIn(0.25f, 4f)
                    }
                    val phaseProp = convertScalarProp(rawFx.properties["phase"], clipStartMs, clipDurMs, 0f) { v ->
                        v.coerceIn(-2f, 2f)
                    }
                    val angleProp = convertScalarProp(rawFx.properties["angle"], clipStartMs, clipDurMs, 0f)
                    val mirrorBool = rawFx.properties["mirror"]?.staticValue?.toBooleanStrictOrNull() ?: true
                    val offsProp = convertVecMagnitudeProp(rawFx.properties["offs"], clipStartMs, clipDurMs, 0f, 1f / 1000f)
                    mapped.add(
                        def.toNode(scaleProp.baseValue).copy(
                            primaryProp = scaleProp,
                            secondaryParam = offsProp.baseValue,
                            secondaryProp = offsProp,
                            frequencyParam = phaseProp.baseValue,
                            frequencyProp = phaseProp,
                            angleParam = angleProp.baseValue,
                            angleProp = angleProp,
                            mixParam = if (mirrorBool) 100f else 85f,
                            mixProp = AnimatableProperty(if (mirrorBool) 100f else 85f),
                            customParams = genericParams,
                            customColors = genericColors,
                            genericStrings = rawFx.rawAttributes
                        )
                    )
                }

                shortId in setOf("oscillate3", "oscillate2", "oscillate") -> {
                    val def = EffectsCatalog.findById("Oscillate")
                    val magProp = convertScalarProp(rawFx.properties["mag"], clipStartMs, clipDurMs, 25f) { v ->
                        v.coerceIn(0f, 400f)
                    }
                    val freqProp = convertScalarProp(rawFx.properties["freq"], clipStartMs, clipDurMs, 2.0f) { v ->
                        v.coerceIn(0.05f, 20f)
                    }
                    val angleProp = convertScalarProp(rawFx.properties["angle"], clipStartMs, clipDurMs, 45f)
                    val typeVal = rawFx.properties["type"]?.staticValue?.toFloatOrNull() ?: 0f
                    mapped.add(
                        def.toNode(magProp.baseValue).copy(
                            primaryProp = magProp,
                            secondaryParam = if (typeVal >= 1f) 0.85f else 0.5f,
                            secondaryProp = AnimatableProperty(if (typeVal >= 1f) 0.85f else 0.5f),
                            frequencyParam = freqProp.baseValue,
                            frequencyProp = freqProp,
                            angleParam = angleProp.baseValue,
                            angleProp = angleProp,
                            customParams = genericParams,
                            customColors = genericColors,
                            genericStrings = rawFx.rawAttributes
                        )
                    )
                }

                shortId == "swing2" || shortId == "swing" -> {
                    val def = EffectsCatalog.findById("Swing")
                    val a1 = convertScalarProp(rawFx.properties["a1"], clipStartMs, clipDurMs, -15f)
                    val a2 = convertScalarProp(rawFx.properties["a2"], clipStartMs, clipDurMs, 15f)
                    val freq = convertScalarProp(rawFx.properties["freq"], clipStartMs, clipDurMs, 1.5f)
                    val phase = convertScalarProp(rawFx.properties["phase"], clipStartMs, clipDurMs, 0f) { it * 360f }
                    mapped.add(
                        def.toNode(a1.baseValue).copy(
                            primaryProp = a1,
                            secondaryParam = a2.baseValue,
                            secondaryProp = a2,
                            frequencyParam = freq.baseValue,
                            frequencyProp = freq,
                            angleParam = phase.baseValue,
                            angleProp = phase,
                            customParams = genericParams,
                            customColors = genericColors,
                            genericStrings = rawFx.rawAttributes
                        )
                    )
                }

                shortId in setOf("motionblur4", "motionblur3", "motionblur2", "motionblur") -> {
                    nativeMb = true
                    val tune = rawFx.properties["tune"]?.staticValue?.toFloatOrNull() ?: 1.0f
                    shutterDeg = (tune * 180f).coerceIn(45f, 360f)
                    val def = EffectsCatalog.findById("Motion_Blur_Tile")
                    val tuneProp = convertScalarProp(rawFx.properties["tune"], clipStartMs, clipDurMs, tune) { v ->
                        (v * 0.45f).coerceIn(0f, 1.5f)
                    }
                    mapped.add(
                        def.toNode(tuneProp.baseValue).copy(
                            primaryProp = tuneProp,
                            mixParam = 65f,
                            mixProp = AnimatableProperty(65f),
                            customParams = genericParams,
                            customColors = genericColors,
                            genericStrings = rawFx.rawAttributes
                        )
                    )
                }

                shortId in setOf("stretch2", "stretch", "squeeze") -> {
                    val def = EffectsCatalog.findById("Squeeze")
                    val scaleProp = convertScalarProp(rawFx.properties["scale"], clipStartMs, clipDurMs, 1.0f) { v ->
                        (v - 1.0f).coerceIn(-1.5f, 1.5f)
                    }
                    val angleProp = convertScalarProp(rawFx.properties["angle"], clipStartMs, clipDurMs, 0f)
                    mapped.add(
                        def.toNode(scaleProp.baseValue).copy(
                            primaryProp = scaleProp,
                            angleParam = angleProp.baseValue,
                            angleProp = angleProp,
                            customParams = genericParams,
                            customColors = genericColors,
                            genericStrings = rawFx.rawAttributes
                        )
                    )
                }

                shortId in setOf("circularripple2", "circularripple", "ripple") -> {
                    val def = EffectsCatalog.findById("Ripple_Pond")
                    val strengthProp = convertScalarProp(rawFx.properties["strength"], clipStartMs, clipDurMs, 0.15f) { v ->
                        (v * 2.5f).coerceIn(-1.5f, 1.5f)
                    }
                    val freqProp = convertScalarProp(rawFx.properties["frequency"], clipStartMs, clipDurMs, 12f) { v ->
                        v.coerceIn(2f, 60f)
                    }
                    val phaseProp = convertScalarProp(rawFx.properties["phase"], clipStartMs, clipDurMs, 0f)
                    val radiusProp = convertScalarProp(rawFx.properties["radius"], clipStartMs, clipDurMs, 0.6f) { v ->
                        (v * 100f).coerceIn(5f, 100f)
                    }
                    mapped.add(
                        def.toNode(strengthProp.baseValue).copy(
                            primaryProp = strengthProp,
                            secondaryParam = freqProp.baseValue,
                            secondaryProp = freqProp,
                            frequencyParam = phaseProp.baseValue,
                            frequencyProp = phaseProp,
                            mixParam = radiusProp.baseValue,
                            mixProp = radiusProp,
                            customParams = genericParams,
                            customColors = genericColors,
                            genericStrings = rawFx.rawAttributes
                        )
                    )
                }

                shortId in setOf("transform", "rastertransform", "offset") -> {
                    val def = EffectsCatalog.findById("Tiles")
                    val zoomProp = convertScalarProp(rawFx.properties["zoom"], clipStartMs, clipDurMs, 1f) { z ->
                        val denom = (1f + z / 500f).coerceIn(0.25f, 4f)
                        (1f / denom).coerceIn(0.25f, 4f)
                    }
                    val offsRaw = rawFx.properties["offs"] ?: rawFx.properties["pos"]
                    val offsProp = convertVecMagnitudeProp(offsRaw, clipStartMs, clipDurMs, 0f, 1f / 1080f)
                    val rotProp = convertScalarProp(rawFx.properties["rot"] ?: rawFx.properties["angle"], clipStartMs, clipDurMs, 0f)
                    val alphaProp = convertScalarProp(rawFx.properties["alpha"], clipStartMs, clipDurMs, 100f) { a ->
                        (a * 100f).coerceIn(0f, 100f)
                    }
                    mapped.add(
                        def.toNode(zoomProp.baseValue).copy(
                            displayName = if (shortId == "offset") "Offset (AM)" else "Raster Transform (AM)",
                            primaryProp = zoomProp,
                            secondaryParam = offsProp.baseValue,
                            secondaryProp = offsProp,
                            angleParam = rotProp.baseValue,
                            angleProp = rotProp,
                            mixParam = alphaProp.baseValue,
                            mixProp = alphaProp,
                            customParams = genericParams,
                            customColors = genericColors,
                            genericStrings = rawFx.rawAttributes
                        )
                    )
                }

                shortId == "satvib" || shortId == "saturation" -> {
                    val def = EffectsCatalog.findById("Saturation_Vibrance")
                    val satProp = convertScalarProp(rawFx.properties["sat"], clipStartMs, clipDurMs, 0f) { s ->
                        s.coerceIn(-1f, 2f)
                    }
                    val vibProp = convertScalarProp(rawFx.properties["vib"], clipStartMs, clipDurMs, 1f) { v ->
                        (2f - v.coerceIn(0.3f, 2.2f)).coerceIn(0.35f, 2.2f)
                    }
                    mapped.add(
                        def.toNode(satProp.baseValue).copy(
                            primaryProp = satProp,
                            secondaryParam = vibProp.baseValue,
                            secondaryProp = vibProp,
                            customParams = genericParams,
                            customColors = genericColors,
                            genericStrings = rawFx.rawAttributes
                        )
                    )
                }

                shortId == "rgbsep" || shortId == "chromakeyshift" -> {
                    val def = EffectsCatalog.findById("Chromatic_Aberration")
                    val strProp = convertScalarProp(rawFx.properties["strength"], clipStartMs, clipDurMs, 0.15f) { s ->
                        abs(s * 2.2f).coerceIn(0f, 1.5f)
                    }
                    val angleProp = convertScalarProp(rawFx.properties["angle"], clipStartMs, clipDurMs, 0f)
                    mapped.add(
                        def.toNode(strProp.baseValue).copy(
                            primaryProp = strProp,
                            angleParam = angleProp.baseValue,
                            angleProp = angleProp,
                            customParams = genericParams,
                            customColors = genericColors,
                            genericStrings = rawFx.rawAttributes
                        )
                    )
                }

                shortId in setOf("zoomblur3", "zoomblur2", "zoomblur") -> {
                    val def = EffectsCatalog.findById("Radial_Zoom_Blur")
                    val strProp = convertScalarProp(rawFx.properties["strength"], clipStartMs, clipDurMs, 0.25f) { s ->
                        abs(s * 1.6f).coerceIn(0f, 2f)
                    }
                    mapped.add(
                        def.toNode(strProp.baseValue).copy(
                            primaryProp = strProp,
                            customParams = genericParams,
                            customColors = genericColors,
                            genericStrings = rawFx.rawAttributes
                        )
                    )
                }

                shortId == "dblur" || shortId == "directionalblur" -> {
                    val def = EffectsCatalog.findById("Directional_Blur")
                    val strProp = convertScalarProp(rawFx.properties["strength"], clipStartMs, clipDurMs, 0.15f) { s ->
                        abs(s * 2.0f).coerceIn(0f, 2f)
                    }
                    val angleProp = convertScalarProp(rawFx.properties["angle"], clipStartMs, clipDurMs, 0f)
                    mapped.add(
                        def.toNode(strProp.baseValue).copy(
                            primaryProp = strProp,
                            angleParam = angleProp.baseValue,
                            angleProp = angleProp,
                            customParams = genericParams,
                            customColors = genericColors,
                            genericStrings = rawFx.rawAttributes
                        )
                    )
                }

                shortId in setOf("gaussianblur", "boxblur", "lensblur") -> {
                    val def = EffectsCatalog.findById("Gaussian_Blur")
                    val strProp = convertScalarProp(rawFx.properties["strength"], clipStartMs, clipDurMs, 0.25f) { s ->
                        abs(s * 1.4f).coerceIn(0f, 2f)
                    }
                    mapped.add(
                        def.toNode(strProp.baseValue).copy(
                            primaryProp = strProp,
                            customParams = genericParams,
                            customColors = genericColors,
                            genericStrings = rawFx.rawAttributes
                        )
                    )
                }

                shortId == "vignette" -> {
                    val def = EffectsCatalog.findById("S_Vignette")
                    val strProp = convertScalarProp(rawFx.properties["strength"], clipStartMs, clipDurMs, 0.5f) { s ->
                        (-abs(s)).coerceIn(-1.5f, 1.5f)
                    }
                    val sizeProp = convertScalarProp(rawFx.properties["size"], clipStartMs, clipDurMs, 1.0f) { sz ->
                        sz.coerceIn(0.2f, 2.0f)
                    }
                    val featherProp = convertScalarProp(rawFx.properties["feather"], clipStartMs, clipDurMs, 0.5f) { f ->
                        f.coerceIn(0f, 1.5f)
                    }
                    val tintHex = rawFx.properties["color"]?.staticValue?.let { normalizeHexColor(it, "#000000") } ?: "#000000"
                    mapped.add(
                        def.toNode(strProp.baseValue).copy(
                            primaryProp = strProp,
                            secondaryParam = sizeProp.baseValue,
                            secondaryProp = sizeProp,
                            frequencyParam = featherProp.baseValue,
                            frequencyProp = featherProp,
                            tintColorHex = tintHex,
                            customParams = genericParams,
                            customColors = genericColors,
                            genericStrings = rawFx.rawAttributes
                        )
                    )
                }

                shortId == "blink2" || shortId == "blink" -> {
                    val def = EffectsCatalog.findById("S_Flicker")
                    val freqProp = convertScalarProp(rawFx.properties["freq"], clipStartMs, clipDurMs, 8f) { f ->
                        f.coerceIn(0.5f, 16f)
                    }
                    val dutyProp = convertScalarProp(rawFx.properties["duty"], clipStartMs, clipDurMs, 0.5f) { d ->
                        d.coerceIn(0.05f, 0.95f)
                    }
                    mapped.add(
                        def.toNode(0.85f).copy(
                            secondaryParam = dutyProp.baseValue,
                            secondaryProp = dutyProp,
                            frequencyParam = freqProp.baseValue,
                            frequencyProp = freqProp,
                            customParams = genericParams,
                            customColors = genericColors,
                            genericStrings = rawFx.rawAttributes
                        )
                    )
                }

                shortId == "exposure" || shortId == "exposuregamma" -> {
                    val def = EffectsCatalog.findById("Exposure_Gamma")
                    val expProp = convertScalarProp(rawFx.properties["exposure"], clipStartMs, clipDurMs, 0f) { e ->
                        e.coerceIn(-2f, 2f)
                    }
                    val gamProp = convertScalarProp(rawFx.properties["gamma"], clipStartMs, clipDurMs, 1f) { g ->
                        g.coerceIn(0.1f, 4f)
                    }
                    val offProp = convertScalarProp(rawFx.properties["offset"], clipStartMs, clipDurMs, 0f) { o ->
                        o.coerceIn(-0.8f, 0.8f)
                    }
                    mapped.add(
                        def.toNode(expProp.baseValue).copy(
                            primaryProp = expProp,
                            secondaryParam = gamProp.baseValue,
                            secondaryProp = gamProp,
                            frequencyParam = offProp.baseValue,
                            frequencyProp = offProp,
                            customParams = genericParams,
                            customColors = genericColors,
                            genericStrings = rawFx.rawAttributes
                        )
                    )
                }

                shortId == "sharpen" -> {
                    val def = EffectsCatalog.findById("Unsharp_Mask")
                    val strProp = convertScalarProp(rawFx.properties["strength"], clipStartMs, clipDurMs, 0.5f) { s ->
                        (s * 0.65f).coerceIn(0f, 2f)
                    }
                    val radProp = convertScalarProp(rawFx.properties["radius"], clipStartMs, clipDurMs, 1.0f) { r ->
                        (r * 0.35f).coerceIn(0.05f, 2f)
                    }
                    mapped.add(
                        def.toNode(strProp.baseValue).copy(
                            primaryProp = strProp,
                            secondaryParam = radProp.baseValue,
                            secondaryProp = radProp,
                            customParams = genericParams,
                            customColors = genericColors,
                            genericStrings = rawFx.rawAttributes
                        )
                    )
                }

                shortId == "hueshift" || shortId == "hue" -> {
                    val def = EffectsCatalog.findById("Hue_Shift")
                    val hueProp = convertScalarProp(rawFx.properties["hue"] ?: rawFx.properties["shift"], clipStartMs, clipDurMs, 0f)
                    mapped.add(
                        def.toNode(0f).copy(
                            primaryProp = AnimatableProperty(0f),
                            angleParam = hueProp.baseValue,
                            angleProp = hueProp,
                            customParams = genericParams,
                            customColors = genericColors,
                            genericStrings = rawFx.rawAttributes
                        )
                    )
                }

                shortId == "wavewarp2" || shortId == "wavewarp" -> {
                    val def = EffectsCatalog.findById("Wave_Warp")
                    val magProp = convertScalarProp(rawFx.properties["mag"] ?: rawFx.properties["m2"], clipStartMs, clipDurMs, 0.35f)
                    val spcProp = convertScalarProp(rawFx.properties["spacing"] ?: rawFx.properties["m1"], clipStartMs, clipDurMs, 20f)
                    val phProp = convertScalarProp(rawFx.properties["phase"], clipStartMs, clipDurMs, 0f)
                    val angProp = convertScalarProp(rawFx.properties["angle"] ?: rawFx.properties["a1"], clipStartMs, clipDurMs, 0f)
                    mapped.add(
                        def.toNode(magProp.baseValue).copy(
                            primaryProp = magProp,
                            secondaryParam = spcProp.baseValue,
                            secondaryProp = spcProp,
                            frequencyParam = phProp.baseValue,
                            frequencyProp = phProp,
                            angleParam = angProp.baseValue,
                            angleProp = angProp,
                            customParams = genericParams,
                            customColors = genericColors,
                            genericStrings = rawFx.rawAttributes
                        )
                    )
                }

                shortId == "pinchbulge" || shortId == "bulge" -> {
                    val def = EffectsCatalog.findById("Pinch_Bulge")
                    val strProp = convertScalarProp(rawFx.properties["strength"], clipStartMs, clipDurMs, 0.45f)
                    val radProp = convertScalarProp(rawFx.properties["radius"], clipStartMs, clipDurMs, 0.65f)
                    mapped.add(
                        def.toNode(strProp.baseValue).copy(
                            primaryProp = strProp,
                            secondaryParam = radProp.baseValue,
                            secondaryProp = radProp,
                            customParams = genericParams,
                            customColors = genericColors,
                            genericStrings = rawFx.rawAttributes
                        )
                    )
                }

                shortId == "swirl" -> {
                    val def = EffectsCatalog.findById("Swirl_Twirl")
                    val strProp = convertScalarProp(rawFx.properties["strength"], clipStartMs, clipDurMs, 0.45f)
                    mapped.add(
                        def.toNode(strProp.baseValue).copy(
                            primaryProp = strProp,
                            customParams = genericParams,
                            customColors = genericColors,
                            genericStrings = rawFx.rawAttributes
                        )
                    )
                }

                shortId == "glow" || shortId == "innerglow" -> {
                    val def = EffectsCatalog.findById("S_Glow")
                    val alphaProp = convertScalarProp(rawFx.properties["alpha"], clipStartMs, clipDurMs, 0.75f)
                    val radProp = convertScalarProp(rawFx.properties["radius"], clipStartMs, clipDurMs, 0.55f)
                    val colHex = rawFx.properties["color"]?.staticValue?.let { normalizeHexColor(it, "#FFFFFF") } ?: "#FFFFFF"
                    mapped.add(
                        def.toNode(alphaProp.baseValue).copy(
                            primaryProp = alphaProp,
                            secondaryParam = radProp.baseValue,
                            secondaryProp = radProp,
                            tintColorHex = colHex,
                            customParams = genericParams,
                            customColors = genericColors,
                            genericStrings = rawFx.rawAttributes
                        )
                    )
                }

                shortId == "chromakey" -> {
                    val def = EffectsCatalog.findById("Chroma_Key")
                    val thresh = convertScalarProp(rawFx.properties["threshold"], clipStartMs, clipDurMs, 24f) { (it * 100f).coerceIn(0f, 100f) }
                    val feath = convertScalarProp(rawFx.properties["feather"], clipStartMs, clipDurMs, 12f) { (it * 100f).coerceIn(0f, 100f) }
                    val keyCol = rawFx.properties["keyColor"]?.staticValue?.let { normalizeHexColor(it, "#00FF00") } ?: "#00FF00"
                    mapped.add(
                        def.toNode(thresh.baseValue).copy(
                            primaryProp = thresh,
                            secondaryParam = feath.baseValue,
                            secondaryProp = feath,
                            tintColorHex = keyCol,
                            customParams = genericParams,
                            customColors = genericColors,
                            genericStrings = rawFx.rawAttributes
                        )
                    )
                }

                shortId == "texttransform" || shortId == "textprogress" -> {
                    val def = EffectsCatalog.findById("Text_Transform")
                    val startP = convertScalarProp(rawFx.properties["start"], clipStartMs, clipDurMs, 0f) { (it * 100f).coerceIn(0f, 100f) }
                    val endP = convertScalarProp(rawFx.properties["end"], clipStartMs, clipDurMs, 100f) { (it * 100f).coerceIn(0f, 100f) }
                    val phaseP = convertScalarProp(rawFx.properties["phase"], clipStartMs, clipDurMs, 0f) { (it * 100f).coerceIn(-100f, 100f) }
                    val fillCol = genericColors["fillColor"] ?: def.defaultTintHex
                    mapped.add(
                        def.toNode(startP.baseValue).copy(
                            primaryProp = startP,
                            secondaryParam = endP.baseValue,
                            secondaryProp = endP,
                            frequencyParam = phaseP.baseValue,
                            frequencyProp = phaseP,
                            tintColorHex = fillCol,
                            genericParamTypes = genericTypes,
                            customParams = genericParams,
                            customColors = genericColors,
                            genericStrings = rawFx.rawAttributes
                        )
                    )
                }

                shortId == "textspacing" -> {
                    val def = EffectsCatalog.findById("Text_Spacing")
                    val spc = convertScalarProp(rawFx.properties["letterspacing"] ?: rawFx.properties["spacing"], clipStartMs, clipDurMs, 12f)
                    mapped.add(
                        def.toNode(0f).copy(
                            frequencyParam = spc.baseValue,
                            frequencyProp = spc,
                            customParams = genericParams,
                            customColors = genericColors,
                            genericStrings = rawFx.rawAttributes
                        )
                    )
                }

                else -> {
                    val directMatch = PluginPackManager.findPluginEffect(rawFx.id)?.let { PluginPackManager.toEffectDefinition(it) }
                        ?: EffectsCatalog.findByIdOrNull(shortId)
                    if (directMatch != null) {
                        mapped.add(
                            directMatch.toNode().copy(
                                genericParamTypes = genericTypes,
                                customParams = genericParams,
                                customColors = genericColors,
                                genericStrings = rawFx.rawAttributes
                            )
                        )
                    } else {
                        // Auto-create a generic lossless XML effect with typed controls & preserved keyframes (pass-through renderer)
                        mapped.add(
                            EffectNode(
                                effectId = rawFx.id.ifBlank { "com.alightcreative.effects.$shortId" },
                                displayName = rawFx.label ?: rawFx.id.substringAfterLast('.').ifBlank { "Imported Effect" },
                                category = "Imported XML Effects",
                                isGenericXmlEffect = true,
                                genericParamTypes = genericTypes,
                                customParams = genericParams,
                                customColors = genericColors,
                                genericStrings = rawFx.rawAttributes
                            )
                        )
                    }
                }
            }
        }

        return MappedEffectsBundle(
            effects = mapped,
            hasCopyBackground = hasCopyBg,
            enableNativeMotionBlur = nativeMb,
            shutterAngleDeg = shutterDeg
        )
    }

    /**
     * Exports a standard Alight Motion `<scene>` XML document for cross-app compatibility.
     */
    fun exportAlightMotionXml(project: Project): String {
        val writer = StringWriter(24 * 1024)
        val xml = Xml.newSerializer()
        xml.setOutput(writer)
        xml.startDocument("UTF-8", true)
        try {
            xml.setFeature("http://xmlpull.org/v1/doc/features.html#indent-output", true)
        } catch (_: Throwable) {
        }

        val totalMs = project.effectiveEditDurationMs().coerceAtLeast(project.durationMs)
        xml.startTag(null, "scene")
        xml.attribute(null, "title", project.name)
        xml.attribute(null, "width", project.width.toString())
        xml.attribute(null, "height", project.height.toString())
        xml.attribute(null, "exportWidth", (if (project.exportWidth > 0) project.exportWidth else project.width).toString())
        xml.attribute(null, "exportHeight", (if (project.exportHeight > 0) project.exportHeight else project.height).toString())
        xml.attribute(null, "fps", project.fps.toString())
        xml.attribute(null, "totalTime", totalMs.toString())
        val bgHex = if (project.explicitSceneBgHex.isNotBlank()) {
            toAmArgbHex(project.explicitSceneBgHex)
        } else {
            toAmBgColorHex(project.backgroundColor)
        }
        xml.attribute(null, "bgcolor", bgHex)
        xml.attribute(null, "amver", project.rawAttributes["amver"] ?: "1028417")
        xml.attribute(null, "ffver", project.rawAttributes["ffver"] ?: "106")
        project.rawAttributes.forEach { (k, v) ->
            if (!k.startsWith("__") && k !in setOf(
                    "title", "width", "height", "exportWidth", "exportHeight",
                    "fps", "totalTime", "bgcolor", "amver", "ffver"
                )
            ) {
                xml.attribute(null, k, v)
            }
        }

        project.beatMarkers.sortedBy { it.timeMs }.forEach { bm ->
            xml.startTag(null, "bookmark")
            xml.attribute(null, "t", bm.timeMs.toString())
            xml.endTag(null, "bookmark")
        }

        val halfW = project.width * 0.5f
        val halfH = project.height * 0.5f
        val minDim = min(project.width, project.height).toFloat().coerceAtLeast(64f)

        val layerIdToNumId = HashMap<String, String>(project.layers.size)
        project.layers.forEachIndexed { idx, layer ->
            layerIdToNumId[layer.id] = layer.rawAttributes["__amRawId"] ?: (1000000L + idx).toString()
        }

        project.layers.forEachIndexed { idx, layer ->
            val clipDur = (layer.endTimeMs - layer.startTimeMs).coerceAtLeast(1L).toFloat()
            val numId = layerIdToNumId[layer.id] ?: (1000000L + idx).toString()
            val parentNumId = layer.parentId?.let { layerIdToNumId[it] }
            val hasParent = parentNumId != null
            val originW = if (hasParent) 0f else halfW
            val originH = if (hasParent) 0f else halfH

            when (layer.type) {
                LayerType.AUDIO -> {
                    xml.startTag(null, "audio")
                    xml.attribute(null, "id", numId)
                    xml.attribute(null, "label", layer.name)
                    xml.attribute(null, "startTime", layer.startTimeMs.toString())
                    xml.attribute(null, "endTime", layer.endTimeMs.toString())
                    xml.attribute(null, "src", layer.mediaUri)
                    xml.attribute(null, "inTime", layer.sourceOffsetMs.toString())
                    val outT = if (layer.mediaTrimOutMs > 0L) layer.mediaTrimOutMs else (layer.sourceOffsetMs + (layer.endTimeMs - layer.startTimeMs))
                    xml.attribute(null, "outTime", outT.toString())
                    xml.attribute(null, "speed", fmtFloat(layer.speedMultiplier))
                    if (!layer.visible) xml.attribute(null, "hidden", "true")
                    if (layer.audioMuted) xml.attribute(null, "mute", "true")
                    xml.startTag(null, "property")
                    xml.attribute(null, "name", "volume")
                    xml.attribute(null, "type", "float")
                    xml.attribute(null, "value", fmtFloat(layer.audioVolume))
                    xml.endTag(null, "property")
                    xml.endTag(null, "audio")
                }

                LayerType.DRAWING -> {
                    xml.startTag(null, "drawing")
                    xml.attribute(null, "id", numId)
                    xml.attribute(null, "label", layer.name)
                    xml.attribute(null, "startTime", layer.startTimeMs.toString())
                    xml.attribute(null, "endTime", layer.endTimeMs.toString())
                    xml.attribute(null, "fillType", layer.rawAttributes["fillType"] ?: "intrinsic")
                    if (parentNumId != null) xml.attribute(null, "parent", parentNumId)
                    if (!layer.visible) xml.attribute(null, "hidden", "true")
                    writeAmLayerTransform(xml, layer, originW, originH, clipDur, scaleDivisor = 100f)
                    writeAmEffectsForLayer(xml, layer, clipDur)
                    xml.startTag(null, "stroke")
                    xml.attribute(null, "color", toAmArgbHex(layer.drawingStrokeColorHex))
                    xml.attribute(null, "width", fmtFloat(layer.drawingStrokeWidth))
                    xml.attribute(null, "type", layer.rawAttributes["__strokeType"] ?: "pen")
                    val pts = layer.drawingPoints.joinToString(";") { pt ->
                        "${fmtFloat(pt.x)},${fmtFloat(pt.y)},${fmtFloat(pt.pressure)}"
                    }
                    xml.attribute(null, "points", pts)
                    xml.endTag(null, "stroke")
                    xml.endTag(null, "drawing")
                }

                LayerType.TEXT -> {
                    xml.startTag(null, "text")
                    xml.attribute(null, "id", numId)
                    xml.attribute(null, "label", layer.name)
                    xml.attribute(null, "startTime", layer.startTimeMs.toString())
                    xml.attribute(null, "endTime", layer.endTimeMs.toString())
                    if (parentNumId != null) xml.attribute(null, "parent", parentNumId)
                    xml.attribute(null, "size", fmtFloat(layer.textSizeSp))
                    val rawFont = layer.rawAttributes["font"] ?: "imported?name=${layer.fontFamilyName}"
                    xml.attribute(null, "font", rawFont)
                    xml.attribute(null, "align", layer.textAlign.lowercase(Locale.US))
                    xml.attribute(null, "blending", toAmBlendMode(layer.blendMode, layer.isClippingMask))
                    if (!layer.visible) xml.attribute(null, "hidden", "true")
                    writeAmLayerTransform(xml, layer, originW, originH, clipDur, scaleDivisor = 100f)
                    xml.startTag(null, "fillColor")
                    xml.attribute(null, "type", "color")
                    xml.attribute(null, "value", toAmArgbHex(layer.colorHex))
                    xml.endTag(null, "fillColor")
                    writeAmEffectsForLayer(xml, layer, clipDur)
                    xml.startTag(null, "content")
                    xml.text(layer.textContent)
                    xml.endTag(null, "content")
                    xml.endTag(null, "text")
                }

                LayerType.NULL_OBJECT, LayerType.GROUP, LayerType.CAMERA -> {
                    val tag = if (layer.type == LayerType.CAMERA) "camera" else "nullobj"
                    xml.startTag(null, tag)
                    xml.attribute(null, "id", numId)
                    xml.attribute(null, "label", layer.name)
                    xml.attribute(null, "startTime", layer.startTimeMs.toString())
                    xml.attribute(null, "endTime", layer.endTimeMs.toString())
                    if (parentNumId != null) xml.attribute(null, "parent", parentNumId)
                    if (layer.type == LayerType.NULL_OBJECT) xml.attribute(null, "type", "perspective")
                    if (!layer.visible) xml.attribute(null, "hidden", "true")
                    writeAmLayerTransform(xml, layer, originW, originH, clipDur, scaleDivisor = 100f)
                    writeAmEffectsForLayer(xml, layer, clipDur)
                    xml.endTag(null, tag)
                }

                else -> {
                    xml.startTag(null, "shape")
                    xml.attribute(null, "id", numId)
                    xml.attribute(null, "label", layer.name)
                    xml.attribute(null, "startTime", layer.startTimeMs.toString())
                    xml.attribute(null, "endTime", layer.endTimeMs.toString())
                    if (parentNumId != null) xml.attribute(null, "parent", parentNumId)
                    val isMedia = (layer.type == LayerType.VIDEO || layer.type == LayerType.IMAGE) && layer.mediaUri.isNotBlank()
                    xml.attribute(null, "fillType", if (isMedia) "media" else "color")
                    if (isMedia) {
                        if (layer.type == LayerType.VIDEO) {
                            xml.attribute(null, "fillVideo", layer.mediaUri)
                        } else {
                            xml.attribute(null, "fillImage", layer.mediaUri)
                        }
                        if (layer.mediaTrimOutMs > 0L) {
                            xml.attribute(null, "outTime", layer.mediaTrimOutMs.toString())
                        }
                    }
                    xml.attribute(null, "blending", toAmBlendMode(layer.blendMode, layer.isClippingMask))
                    xml.attribute(null, "s", toAmShapeToken(layer.shapeKind))
                    if (!layer.visible) xml.attribute(null, "hidden", "true")

                    val amSizeHalfX = if (layer.mediaWidth > 0) layer.mediaWidth * 0.5f else if (isMedia) minDim * 0.5f else 100f
                    val amSizeHalfY = if (layer.mediaHeight > 0) layer.mediaHeight * 0.5f else amSizeHalfX
                    val fpBaseHalf = if (isMedia) minDim * 0.5f else minDim * 0.22f
                    val scaleDivisor = (amSizeHalfX / fpBaseHalf) * 100f

                    writeAmLayerTransform(xml, layer, originW, originH, clipDur, scaleDivisor = scaleDivisor)

                    if (!isMedia) {
                        xml.startTag(null, "fillColor")
                        xml.attribute(null, "type", "color")
                        xml.attribute(null, "value", toAmArgbHex(layer.colorHex))
                        xml.endTag(null, "fillColor")
                    }

                    writeAmEffectsForLayer(xml, layer, clipDur)

                    xml.startTag(null, "property")
                    xml.attribute(null, "name", "size")
                    xml.attribute(null, "type", "vec2")
                    xml.attribute(null, "value", "${fmtFloat(amSizeHalfX)},${fmtFloat(amSizeHalfY)}")
                    xml.endTag(null, "property")

                    xml.endTag(null, "shape")
                }
            }
        }

        xml.endTag(null, "scene")
        xml.endDocument()
        xml.flush()
        return writer.toString()
    }

    private fun writeAmLayerTransform(
        xml: org.xmlpull.v1.XmlSerializer,
        layer: Layer,
        halfW: Float,
        halfH: Float,
        clipDur: Float,
        scaleDivisor: Float
    ) {
        xml.startTag(null, "transform")

        // location
        xml.startTag(null, "location")
        val locTimes = (layer.posX.keyframes.map { it.timeMs } +
            layer.posY.keyframes.map { it.timeMs } +
            layer.posZ.keyframes.map { it.timeMs }).distinct().sorted()
        if (locTimes.isEmpty()) {
            xml.attribute(
                null,
                "value",
                "${fmtFloat(layer.posX.baseValue + halfW)},${fmtFloat(layer.posY.baseValue + halfH)},${fmtFloat(layer.posZ.baseValue)}"
            )
        } else {
            val aud = AudioSpectrumSnapshot()
            val sortedKfsX = layer.posX.keyframes.sortedBy { it.timeMs }
            locTimes.forEachIndexed { idx, tMs ->
                val x = AnimationEvaluator.evaluateProperty(layer.posX, tMs, aud) + halfW
                val y = AnimationEvaluator.evaluateProperty(layer.posY, tMs, aud) + halfH
                val z = AnimationEvaluator.evaluateProperty(layer.posZ, tMs, aud)
                val kfRef = sortedKfsX.firstOrNull { it.timeMs == tMs }
                    ?: layer.posY.keyframes.firstOrNull { it.timeMs == tMs }
                val prevKf = if (idx > 0) sortedKfsX.getOrNull(idx - 1) else null
                xml.startTag(null, "kf")
                xml.attribute(null, "t", fmtFloat((tMs - layer.startTimeMs) / clipDur))
                xml.attribute(null, "v", "${fmtFloat(x)},${fmtFloat(y)},${fmtFloat(z)}")
                val eStr = formatAmSegmentEasing(prevKf, kfRef)
                if (eStr.isNotEmpty() && idx > 0) {
                    xml.attribute(null, "e", eStr)
                }
                xml.endTag(null, "kf")
            }
        }
        xml.endTag(null, "location")

        // scale
        xml.startTag(null, "scale")
        val safeDiv = scaleDivisor.coerceAtLeast(1f)
        if (layer.scale.keyframes.isEmpty()) {
            val sx = layer.scale.baseValue / safeDiv
            val sy = (if (layer.hasSeparateScaleY) layer.scaleY.baseValue else layer.scale.baseValue) / safeDiv
            xml.attribute(null, "value", "${fmtFloat(sx)},${fmtFloat(sy)}")
        } else {
            val sortedScl = layer.scale.keyframes.sortedBy { it.timeMs }
            sortedScl.forEachIndexed { idx, kf ->
                val sx = kf.value / safeDiv
                val sy = if (layer.hasSeparateScaleY) {
                    AnimationEvaluator.evaluateProperty(layer.scaleY, kf.timeMs) / safeDiv
                } else {
                    sx
                }
                val prevKf = if (idx > 0) sortedScl.getOrNull(idx - 1) else null
                xml.startTag(null, "kf")
                xml.attribute(null, "t", fmtFloat((kf.timeMs - layer.startTimeMs) / clipDur))
                xml.attribute(null, "v", "${fmtFloat(sx)},${fmtFloat(sy)}")
                val eStr = formatAmSegmentEasing(prevKf, kf)
                if (eStr.isNotEmpty() && idx > 0) {
                    xml.attribute(null, "e", eStr)
                }
                xml.endTag(null, "kf")
            }
        }
        xml.endTag(null, "scale")

        // rotation
        xml.startTag(null, "rotation")
        if (layer.rotationZ.keyframes.isEmpty()) {
            xml.attribute(null, "value", fmtFloat(layer.rotationZ.baseValue))
        } else {
            val sortedRot = layer.rotationZ.keyframes.sortedBy { it.timeMs }
            sortedRot.forEachIndexed { idx, kf ->
                val prevKf = if (idx > 0) sortedRot.getOrNull(idx - 1) else null
                xml.startTag(null, "kf")
                xml.attribute(null, "t", fmtFloat((kf.timeMs - layer.startTimeMs) / clipDur))
                xml.attribute(null, "v", fmtFloat(kf.value))
                val eStr = formatAmSegmentEasing(prevKf, kf)
                if (eStr.isNotEmpty() && idx > 0) {
                    xml.attribute(null, "e", eStr)
                }
                xml.endTag(null, "kf")
            }
        }
        xml.endTag(null, "rotation")

        // opacity
        xml.startTag(null, "opacity")
        if (layer.opacity.keyframes.isEmpty()) {
            xml.attribute(null, "value", fmtFloat((layer.opacity.baseValue / 100f).coerceIn(0f, 1f)))
        } else {
            val sortedOp = layer.opacity.keyframes.sortedBy { it.timeMs }
            sortedOp.forEachIndexed { idx, kf ->
                val prevKf = if (idx > 0) sortedOp.getOrNull(idx - 1) else null
                xml.startTag(null, "kf")
                xml.attribute(null, "t", fmtFloat((kf.timeMs - layer.startTimeMs) / clipDur))
                xml.attribute(null, "v", fmtFloat((kf.value / 100f).coerceIn(0f, 1f)))
                val eStr = formatAmSegmentEasing(prevKf, kf)
                if (eStr.isNotEmpty() && idx > 0) {
                    xml.attribute(null, "e", eStr)
                }
                xml.endTag(null, "kf")
            }
        }
        xml.endTag(null, "opacity")

        xml.endTag(null, "transform")
    }

    private fun writeAmEffectsForLayer(
        xml: org.xmlpull.v1.XmlSerializer,
        layer: Layer,
        clipDur: Float
    ) {
        if (layer.motionBlurEnabled && layer.effects.none { it.effectId == "Motion_Blur_Tile" }) {
            xml.startTag(null, "effect")
            xml.attribute(null, "id", "com.alightcreative.effects.motionblur4")
            xml.startTag(null, "property")
            xml.attribute(null, "name", "tune")
            xml.attribute(null, "type", "float")
            xml.attribute(null, "value", fmtFloat(layer.shutterAngle / 180f))
            xml.endTag(null, "property")
            xml.endTag(null, "effect")
        }

        layer.effects.filter { it.enabled }.forEach { fx ->
            val amId = if (fx.isGenericXmlEffect || fx.effectId.startsWith("com.")) {
                fx.effectId
            } else {
                toAmEffectId(fx.effectId)
            }
            xml.startTag(null, "effect")
            xml.attribute(null, "id", amId)
            fx.genericStrings.forEach { (k, v) ->
                if (k != "id") xml.attribute(null, k, v)
            }
            if (fx.isGenericXmlEffect && fx.genericParamTypes.isNotEmpty()) {
                fx.genericParamTypes.forEach { (pName, pType) ->
                    when (pType) {
                        "color" -> {
                            xml.startTag(null, "property")
                            xml.attribute(null, "name", pName)
                            xml.attribute(null, "type", "color")
                            xml.attribute(null, "value", toAmArgbHex(fx.customColors[pName] ?: "#FFFFFF"))
                            xml.endTag(null, "property")
                        }
                        "bool" -> {
                            val bVal = (fx.customParams[pName]?.baseValue ?: 0f) >= 0.5f
                            xml.startTag(null, "property")
                            xml.attribute(null, "name", pName)
                            xml.attribute(null, "type", "bool")
                            xml.attribute(null, "value", bVal.toString())
                            xml.endTag(null, "property")
                        }
                        "vec2" -> {
                            val px = fx.customParams["${pName}_x"]?.baseValue ?: 0f
                            val py = fx.customParams["${pName}_y"]?.baseValue ?: 0f
                            xml.startTag(null, "property")
                            xml.attribute(null, "name", pName)
                            xml.attribute(null, "type", "vec2")
                            xml.attribute(null, "value", "${fmtFloat(px)},${fmtFloat(py)}")
                            xml.endTag(null, "property")
                        }
                        else -> {
                            val prop = fx.customParams[pName] ?: AnimatableProperty(0f)
                            writeAmEffectParam(xml, pName, prop, layer.startTimeMs, clipDur, pType)
                        }
                    }
                }
            } else {
                writeAmEffectParam(xml, "strength", fx.primaryProp, layer.startTimeMs, clipDur, "float")
                writeAmEffectParam(xml, "secondary", fx.secondaryProp, layer.startTimeMs, clipDur, "float")
                writeAmEffectParam(xml, "freq", fx.frequencyProp, layer.startTimeMs, clipDur, "float")
                writeAmEffectParam(xml, "angle", fx.angleProp, layer.startTimeMs, clipDur, "float")
            }
            xml.endTag(null, "effect")
        }
    }

    private fun writeAmEffectParam(
        xml: org.xmlpull.v1.XmlSerializer,
        propName: String,
        prop: AnimatableProperty,
        clipStartMs: Long,
        clipDur: Float,
        propType: String = "float"
    ) {
        xml.startTag(null, "property")
        xml.attribute(null, "name", propName)
        xml.attribute(null, "type", propType)
        if (prop.keyframes.isEmpty()) {
            val strVal = if (propType == "int") prop.baseValue.roundToInt().toString() else fmtFloat(prop.baseValue)
            xml.attribute(null, "value", strVal)
        } else {
            val sorted = prop.keyframes.sortedBy { it.timeMs }
            sorted.forEachIndexed { idx, kf ->
                val prevKf = if (idx > 0) sorted.getOrNull(idx - 1) else null
                xml.startTag(null, "kf")
                xml.attribute(null, "t", fmtFloat((kf.timeMs - clipStartMs) / clipDur))
                xml.attribute(null, "v", fmtFloat(kf.value))
                val eStr = formatAmSegmentEasing(prevKf, kf)
                if (eStr.isNotEmpty() && idx > 0) {
                    xml.attribute(null, "e", eStr)
                }
                xml.endTag(null, "kf")
            }
        }
        xml.endTag(null, "property")
    }

    // =========================================================================================
    // 3. LOW-LEVEL XML HELPERS & CONVERTERS
    // =========================================================================================

    private fun parseAmTransformBlock(
        parser: XmlPullParser,
        outMap: MutableMap<String, RawAmProperty>,
        onXmlKeyframesCounted: (Int) -> Unit
    ) {
        val trDepth = parser.depth
        var ev = parser.next()
        while (!(ev == XmlPullParser.END_TAG && parser.depth == trDepth && parser.name == "transform") &&
            ev != XmlPullParser.END_DOCUMENT
        ) {
            if (ev == XmlPullParser.START_TAG) {
                val propName = parser.name
                val staticVal = parser.getAttributeValue(null, "value")
                val kfs = ArrayList<RawAmKeyframe>()
                val pDepth = parser.depth
                var subEv = parser.next()
                while (!(subEv == XmlPullParser.END_TAG && parser.depth == pDepth && parser.name == propName) &&
                    subEv != XmlPullParser.END_DOCUMENT
                ) {
                    if (subEv == XmlPullParser.START_TAG && parser.name == "kf") {
                        val t = parser.getAttributeValue(null, "t")?.toFloatOrNull() ?: 0f
                        val v = parser.getAttributeValue(null, "v") ?: "0"
                        val e = parseAmEasingAttr(parser.getAttributeValue(null, "e"))
                        kfs.add(RawAmKeyframe(t, v, e))
                    }
                    subEv = parser.next()
                }
                onXmlKeyframesCounted(kfs.size)
                outMap[propName] = RawAmProperty(propName, "vec", staticVal, kfs)
            }
            ev = parser.next()
        }
    }

    private fun parseRawAmProperty(parser: XmlPullParser): RawAmProperty {
        val name = parser.getAttributeValue(null, "name") ?: ""
        val type = parser.getAttributeValue(null, "type") ?: "float"
        val staticVal = parser.getAttributeValue(null, "value")
        val kfs = ArrayList<RawAmKeyframe>()
        val pDepth = parser.depth
        var ev = parser.next()
        while (!(ev == XmlPullParser.END_TAG && parser.depth == pDepth && parser.name == "property") &&
            ev != XmlPullParser.END_DOCUMENT
        ) {
            if (ev == XmlPullParser.START_TAG && parser.name == "kf") {
                val t = parser.getAttributeValue(null, "t")?.toFloatOrNull() ?: 0f
                val v = parser.getAttributeValue(null, "v") ?: "0"
                val e = parseAmEasingAttr(parser.getAttributeValue(null, "e"))
                kfs.add(RawAmKeyframe(t, v, e))
            }
            ev = parser.next()
        }
        return RawAmProperty(name, type, staticVal, kfs)
    }

    private fun parseRawAmEffect(
        parser: XmlPullParser,
        onXmlKeyframesCounted: (Int) -> Unit
    ): RawAmEffect {
        val attrs = collectParserAttributes(parser)
        val fxId = attrs["id"] ?: ""
        val label = attrs["label"]
        val locallyApplied = attrs["locallyApplied"]?.toBooleanStrictOrNull() ?: true
        val props = LinkedHashMap<String, RawAmProperty>()
        val fxDepth = parser.depth
        var ev = parser.next()
        while (!(ev == XmlPullParser.END_TAG && parser.depth == fxDepth && parser.name == "effect") &&
            ev != XmlPullParser.END_DOCUMENT
        ) {
            if (ev == XmlPullParser.START_TAG && parser.name == "property") {
                val prop = parseRawAmProperty(parser)
                onXmlKeyframesCounted(prop.keyframes.size)
                if (prop.name.isNotEmpty()) {
                    props[prop.name] = prop
                }
            }
            ev = parser.next()
        }
        return RawAmEffect(fxId, label, locallyApplied, attrs, props)
    }

    private fun skipCurrentElement(parser: XmlPullParser) {
        if (parser.eventType != XmlPullParser.START_TAG) return
        var depth = 1
        while (depth > 0) {
            when (parser.next()) {
                XmlPullParser.START_TAG -> depth++
                XmlPullParser.END_TAG -> depth--
                XmlPullParser.END_DOCUMENT -> return
            }
        }
    }

    /**
     * Converts sorted `RawAmKeyframe` list into `Keyframe` list where `sorted[i+1].easing` (arrival keyframe `e`)
     * governs segment `i -> i+1`:
     * - `outTangent` on `Keyframe[i]` = `(x1, y1)` of `sorted[i+1].easing`
     * - `inTangent` + `rawEasing` on `Keyframe[i+1]` = `(x2, y2)` + `rawEasing` of `sorted[i+1].easing`
     * - Unclamped `normT` (`t < 0` and `t > 1` are preserved in exact world milliseconds).
     */
    private fun buildSegmentAlignedKeyframes(
        sortedRawKfs: List<RawAmKeyframe>,
        clipStartMs: Long,
        clipDurMs: Long,
        valueMapper: (String) -> Float
    ): List<Keyframe> {
        if (sortedRawKfs.isEmpty()) return emptyList()
        return sortedRawKfs.mapIndexed { idx, curr ->
            val worldTimeMs = clipStartMs + (curr.normT.toDouble() * clipDurMs.toDouble()).roundToLong()
            val next = sortedRawKfs.getOrNull(idx + 1)
            val outX = next?.easing?.x1 ?: 0.333333f
            val outY = next?.easing?.y1 ?: 0.333333f
            val hold = next?.easing?.holdStep ?: false
            val inX = if (idx > 0) curr.easing.x2 else 0.666667f
            val inY = if (idx > 0) curr.easing.y2 else 0.666667f
            Keyframe(
                timeMs = worldTimeMs,
                value = valueMapper(curr.rawVal),
                inTangentX = inX,
                inTangentY = inY,
                outTangentX = outX,
                outTangentY = outY,
                holdStep = hold,
                rawEasing = curr.easing.rawEasing
            )
        }
    }

    private fun convertScalarProp(
        raw: RawAmProperty?,
        clipStartMs: Long,
        clipDurMs: Long,
        fallback: Float,
        mapper: (Float) -> Float = { it }
    ): AnimatableProperty {
        if (raw == null) return AnimatableProperty(mapper(fallback))
        val sortedKfs = raw.keyframes.sortedBy { it.normT }
        val baseRaw = raw.staticValue?.toFloatOrNull()
            ?: sortedKfs.firstOrNull()?.rawVal?.toFloatOrNull()
            ?: fallback
        val kfs = buildSegmentAlignedKeyframes(sortedKfs, clipStartMs, clipDurMs) { rv ->
            mapper(rv.toFloatOrNull() ?: baseRaw)
        }
        return AnimatableProperty(mapper(baseRaw), deduplicateKeyframes(kfs))
    }

    private fun convertVecMagnitudeProp(
        raw: RawAmProperty?,
        clipStartMs: Long,
        clipDurMs: Long,
        fallback: Float,
        scale: Float
    ): AnimatableProperty {
        if (raw == null) return AnimatableProperty(fallback)
        val sortedKfs = raw.keyframes.sortedBy { it.normT }
        val baseVec = raw.staticValue?.let { parseVecFloats(it) }
            ?: sortedKfs.firstOrNull()?.rawVal?.let { parseVecFloats(it) }
            ?: floatArrayOf(0f, 0f)
        val baseMag = (hypot(baseVec.getOrElse(0) { 0f }, baseVec.getOrElse(1) { 0f }) * scale).coerceIn(0f, 2f)
        val kfs = buildSegmentAlignedKeyframes(sortedKfs, clipStartMs, clipDurMs) { rv ->
            val vec = parseVecFloats(rv)
            (hypot(vec.getOrElse(0) { 0f }, vec.getOrElse(1) { 0f }) * scale).coerceIn(0f, 2f)
        }
        return AnimatableProperty(baseMag, deduplicateKeyframes(kfs))
    }

    private fun deduplicateKeyframes(kfs: List<Keyframe>): List<Keyframe> {
        if (kfs.size <= 1) return kfs
        val out = ArrayList<Keyframe>(kfs.size)
        for (kf in kfs.sortedBy { it.timeMs }) {
            if (out.isNotEmpty() && out.last().timeMs == kf.timeMs) {
                out[out.lastIndex] = kf
            } else {
                out.add(kf)
            }
        }
        return out
    }

    private fun parseAmEasingAttr(raw: String?): ParsedEasing {
        if (raw.isNullOrBlank()) {
            return ParsedEasing(0.333333f, 0.333333f, 0.666667f, 0.666667f, holdStep = false, rawEasing = "")
        }
        val trimmed = raw.trim()
        if (trimmed.startsWith("step", ignoreCase = true) || trimmed.startsWith("hold", ignoreCase = true)) {
            return ParsedEasing(0.42f, 0f, 0.58f, 1f, holdStep = true, rawEasing = trimmed)
        }
        if (trimmed.startsWith("cubicBezier", ignoreCase = true)) {
            val parts = trimmed.split(Regex("\\s+")).drop(1).mapNotNull { it.toFloatOrNull() }
            if (parts.size >= 4) {
                return ParsedEasing(
                    x1 = parts[0].coerceIn(0f, 1f),
                    y1 = parts[1].coerceIn(-3f, 4f),
                    x2 = parts[2].coerceIn(0f, 1f),
                    y2 = parts[3].coerceIn(-3f, 4f),
                    holdStep = false,
                    rawEasing = trimmed
                )
            }
        }
        // Preserve bounce, cyclic, elastic, or any future easing verbatim in rawEasing
        return ParsedEasing(0.333333f, 0.333333f, 0.666667f, 0.666667f, holdStep = false, rawEasing = trimmed)
    }

    private fun formatAmSegmentEasing(prevKf: Keyframe?, arrivalKf: Keyframe?): String {
        if (arrivalKf == null) return ""
        if (arrivalKf.rawEasing.isNotBlank()) return arrivalKf.rawEasing
        if (prevKf?.holdStep == true) return "step 1.0 0.0"
        val x1 = prevKf?.outTangentX ?: 0.333333f
        val y1 = prevKf?.outTangentY ?: 0.333333f
        val x2 = arrivalKf.inTangentX
        val y2 = arrivalKf.inTangentY
        return "cubicBezier ${fmtFloat(x1)} ${fmtFloat(y1)} ${fmtFloat(x2)} ${fmtFloat(y2)}"
    }

    private fun parseVecFloats(raw: String): FloatArray {
        val parts = raw.split(',')
        return FloatArray(parts.size) { idx ->
            parts[idx].trim().toFloatOrNull() ?: 0f
        }
    }

    private fun splitAmColorAndAlpha(raw: String?, fallbackHex: String): Pair<String, Float> {
        if (raw.isNullOrBlank()) return fallbackHex to 1f
        val clean = raw.trim().removePrefix("#")
        return when (clean.length) {
            8 -> {
                val aInt = clean.substring(0, 2).toIntOrNull(16) ?: 255
                "#${clean.substring(2).uppercase(Locale.US)}" to (aInt / 255f).coerceIn(0f, 1f)
            }
            6 -> "#${clean.uppercase(Locale.US)}" to 1f
            else -> fallbackHex to 1f
        }
    }

    private fun normalizeHexColor(raw: String, fallbackHex: String): String =
        splitAmColorAndAlpha(raw, fallbackHex).first

    private fun normalizeAmSceneBgColor(raw: String): String {
        val lower = raw.trim().lowercase(Locale.US)
        return when {
            lower == "#00000000" || lower == "transparent" -> "Transparent"
            lower == "#ff000000" || lower == "#000000" -> "Black"
            lower == "#ffffffff" || lower == "#ffffff" -> "White"
            raw.startsWith("#") -> normalizeHexColor(raw, "#000000")
            else -> "Black"
        }
    }

    private fun toAmBgColorHex(bg: String): String = when {
        bg.equals("White", ignoreCase = true) -> "#ffffffff"
        bg.equals("Transparent", ignoreCase = true) -> "#00000000"
        bg.equals("Dark Gray", ignoreCase = true) -> "#ff1c2028"
        bg.equals("Light Gray", ignoreCase = true) -> "#ffd1d6de"
        bg.startsWith("#") -> toAmArgbHex(bg).lowercase(Locale.US)
        else -> "#ff000000"
    }

    private fun toAmArgbHex(rgbOrArgb: String): String {
        val clean = rgbOrArgb.trim().removePrefix("#")
        return when (clean.length) {
            6 -> "#ff${clean.lowercase(Locale.US)}"
            8 -> "#${clean.lowercase(Locale.US)}"
            else -> "#ffffffff"
        }
    }

    private fun mapAmBlendMode(amBlend: String): String = when (amBlend.lowercase(Locale.US)) {
        "screen", "lighten", "add", "linear-dodge" -> "Screen"
        "multiply", "darken", "color-burn" -> "Multiply"
        "overlay", "soft-light", "hard-light" -> "Overlay"
        "difference", "exclusion", "subtract" -> "Difference"
        else -> "Normal"
    }

    private fun toAmBlendMode(fpBlend: String, isMask: Boolean): String {
        if (isMask) return "mask"
        return when (fpBlend) {
            "Screen" -> "screen"
            "Multiply" -> "multiply"
            "Overlay" -> "overlay"
            "Difference" -> "difference"
            else -> "normal"
        }
    }

    private fun toAmShapeToken(shapeKind: String): String = when (shapeKind) {
        "Circle" -> ".circle"
        "Star" -> ".star"
        "Triangle", "Hexagon", "Polygon" -> ".poly"
        "Rounded Rect" -> ".roundrect"
        else -> ".rect"
    }

    private fun toAmEffectId(fpEffectId: String): String = when (fpEffectId) {
        "Copy_Background" -> "com.alightcreative.effects.lift"
        "Tiles" -> "com.alightcreative.effects.tile"
        "Oscillate" -> "com.alightcreative.effects.oscillate3"
        "Swing" -> "com.alightcreative.effects.swing2"
        "Motion_Blur_Tile" -> "com.alightcreative.effects.motionblur4"
        "Squeeze" -> "com.alightcreative.effects.stretch2"
        "Ripple_Pond" -> "com.alightcreative.effects.circularripple2"
        "Saturation_Vibrance" -> "com.alightcreative.effects.satvib"
        "Chromatic_Aberration", "RGB_Split", "S_WarpChroma" -> "com.alightcreative.effects.rgbsep"
        "Radial_Zoom_Blur" -> "com.alightcreative.effects.zoomblur3"
        "Directional_Blur" -> "com.alightcreative.effects.dblur"
        "Gaussian_Blur" -> "com.alightcreative.effects.gaussianblur"
        "S_Vignette", "Vignette_Cinema" -> "com.alightcreative.effects.vignette"
        "S_Flicker", "Blink_Strobe" -> "com.alightcreative.effects.blink2"
        "Exposure_Gamma" -> "com.alightcreative.effects.exposure"
        "Unsharp_Mask" -> "com.alightcreative.effects.sharpen"
        "Wave_Warp" -> "com.alightcreative.effects.wavewarp2"
        "Pinch_Bulge" -> "com.alightcreative.effects.pinchbulge"
        "Swirl_Twirl" -> "com.alightcreative.effects.swirl"
        "Chroma_Key" -> "com.alightcreative.effects.chromakey"
        "Text_Transform", "Keyflow_Text_Animator" -> "com.alightcreative.effects.texttransform"
        "Text_Spacing" -> "com.alightcreative.effects.textspacing"
        else -> "com.framepollution.effects.$fpEffectId"
    }

    private fun inferAspectPresetLabel(w: Int, h: Int): String {
        val ratio = w.toFloat() / h.toFloat().coerceAtLeast(1f)
        return when {
            abs(ratio - 16f / 9f) < 0.04f -> AspectRatioPreset.LANDSCAPE_16_9.label
            abs(ratio - 9f / 16f) < 0.04f -> AspectRatioPreset.PORTRAIT_9_16.label
            abs(ratio - 1f) < 0.04f -> AspectRatioPreset.SQUARE_1_1.label
            abs(ratio - 4f / 5f) < 0.04f -> AspectRatioPreset.PORTRAIT_4_5.label
            abs(ratio - 4f / 3f) < 0.04f -> AspectRatioPreset.CLASSIC_4_3.label
            abs(ratio - 21f / 9f) < 0.06f -> AspectRatioPreset.CINEMATIC_21_9.label
            else -> AspectRatioPreset.CUSTOM.label
        }
    }

    private fun inferBpmFromBookmarks(bookmarks: List<BeatMarker>): Int {
        if (bookmarks.size < 3) return 128
        val sorted = bookmarks.map { it.timeMs }.distinct().sorted()
        val deltas = ArrayList<Long>()
        for (i in 1 until sorted.size) {
            val dt = sorted[i] - sorted[i - 1]
            if (dt in 220L..1500L) deltas.add(dt)
        }
        if (deltas.isEmpty()) return 128
        val medianDelta = deltas.sorted()[deltas.size / 2].coerceAtLeast(250L)
        var bpm = (60_000.0 / medianDelta.toDouble()).roundToInt()
        while (bpm < 72) bpm *= 2
        while (bpm > 190) bpm /= 2
        return bpm.coerceIn(60, 200)
    }

    private fun fmtFloat(v: Float): String {
        if (!v.isFinite()) return "0"
        val roundedInt = v.roundToInt()
        return if (abs(v - roundedInt) < 0.00005f) {
            roundedInt.toString()
        } else {
            String.format(Locale.US, "%.6f", v).trimEnd('0').trimEnd('.')
        }
    }
}
