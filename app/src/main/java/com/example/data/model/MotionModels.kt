package com.example.data.model

import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentId
import com.google.firebase.firestore.IgnoreExtraProperties
import com.google.firebase.firestore.ServerTimestamp
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

enum class AspectRatioPreset(
    val label: String,
    val defaultWidth: Int,
    val defaultHeight: Int,
    val description: String
) {
    LANDSCAPE_16_9("16:9", 1920, 1080, "YouTube / Web HD"),
    PORTRAIT_9_16("9:16", 1080, 1920, "TikTok / Reels / Shorts"),
    PORTRAIT_4_5("4:5", 1080, 1350, "Social Portrait"),
    SQUARE_1_1("1:1", 1080, 1080, "Instagram Feed"),
    CLASSIC_4_3("4:3", 1440, 1080, "Retro / Classic TV"),
    CINEMATIC_21_9("21:9", 2560, 1080, "Widescreen / Film"),
    CUSTOM("Custom", 1440, 1440, "Custom W x H")
}

const val DEFAULT_NEW_TIMELINE_DURATION_MS: Long = 8_750L
const val DEFAULT_NEW_CLIP_DURATION_MS: Long = 3_500L

enum class LayerType(val label: String) {
    SHAPE("Shape"),
    TEXT("Text"),
    VIDEO("Video"),
    IMAGE("Image"),
    AUDIO("Audio"),
    CAMERA("Camera"),
    MODEL_3D("3D Object"),
    NULL_OBJECT("Null Rig"),
    GROUP("Group"),
    DRAWING("Drawing")
}

enum class Primitive3DType(val label: String) {
    CUBE("Cube"),
    BOX("Box"),
    PYRAMID("Pyramid"),
    TORUS("Torus"),
    IMPORTED_MESH("Imported Mesh (.glb/.obj)")
}

enum class AudioBand(val label: String) {
    NONE("Off"),
    BASS("Bass (20-160 Hz)"),
    MIDS("Vocals/Mids (160-2k Hz)"),
    TREBLE("Treble (2k-16k Hz)")
}

enum class PropertyTarget(
    val label: String,
    val unit: String,
    val minVal: Float,
    val maxVal: Float,
    val defaultSliderMin: Float = minVal,
    val defaultSliderMax: Float = maxVal,
    val unbounded: Boolean = false
) {
    POS_X("Pos X", "px", -100000f, 100000f, -1920f, 1920f, unbounded = true),
    POS_Y("Pos Y", "px", -100000f, 100000f, -1920f, 1920f, unbounded = true),
    POS_Z("Z Depth", "px", -50000f, 50000f, -1500f, 1500f, unbounded = true),
    PIVOT_X("Pivot X", "px", -50000f, 50000f, -960f, 960f, unbounded = true),
    PIVOT_Y("Pivot Y", "px", -50000f, 50000f, -960f, 960f, unbounded = true),
    SCALE("Scale", "%", 0f, 5000f, 0f, 400f, unbounded = true),
    ROTATION_X("Rot X", "°", -36000f, 36000f, -360f, 360f, unbounded = true),
    ROTATION_Y("Rot Y", "°", -36000f, 36000f, -360f, 360f, unbounded = true),
    ROTATION_Z("Rotation", "°", -36000f, 36000f, -360f, 360f, unbounded = true),
    OPACITY("Opacity", "%", 0f, 100f, 0f, 100f, unbounded = false),
    TIME_REMAP("Time Remap", "s", 0f, 3600f, 0f, 60f, unbounded = true);

    val defaultResetValue: Float
        get() = when (this) {
            SCALE, OPACITY -> 100f
            else -> 0f
        }
}

enum class EffectParamSlot {
    PRIMARY,
    SECONDARY,
    FREQUENCY,
    ANGLE,
    MIX,
    FEATHER
}

data class Keyframe(
    val id: String = UUID.randomUUID().toString(),
    val timeMs: Long = 0L,
    val value: Float = 0f,
    // Cubic Bezier control points in normalized [0..1] domain: P1=(x1,y1), P2=(x2,y2); y1/y2 may exceed [0..1] for overshoot
    val outTangentX: Float = 0.42f,
    val outTangentY: Float = 0.0f,
    val inTangentX: Float = 0.58f,
    val inTangentY: Float = 1.0f,
    val holdStep: Boolean = false,
    val rawEasing: String = ""
)

data class DrawingPoint(
    val x: Float,
    val y: Float,
    val pressure: Float = 1.0f,
    val inX: Float = 0f,
    val inY: Float = 0f,
    val outX: Float = 0f,
    val outY: Float = 0f
)

typealias MaskVertex = DrawingPoint

enum class MaskShapeType(val label: String) {
    RECTANGLE("Rectangle"),
    ELLIPSE("Ellipse"),
    PEN("Pen"),
    ROTO("Roto");

    companion object {
        fun fromLabel(raw: String): MaskShapeType =
            entries.firstOrNull { it.name.equals(raw, ignoreCase = true) || it.label.equals(raw, ignoreCase = true) }
                ?: RECTANGLE
    }
}

data class LayerMarker(
    val timeMs: Long,
    val comment: String = "",
    val durationMs: Long = 0L
)

data class AnimatableProperty(
    val baseValue: Float = 0f,
    val keyframes: List<Keyframe> = emptyList(),
    val expression: String = "",
    val audioReactorBand: AudioBand = AudioBand.NONE,
    val audioReactorGain: Float = 1.0f
)

data class EffectNode(
    val id: String = UUID.randomUUID().toString(),
    val effectId: String = "S_Glow",
    val displayName: String = "S_Glow",
    val category: String = "Desktop Suite Homages",
    val enabled: Boolean = true,
    val intensity: Float = 0.65f,
    val secondaryParam: Float = 0.50f,
    val frequencyParam: Float = 0.35f,
    val angleParam: Float = 0.0f,
    val mixParam: Float = 100.0f,
    val feather: Float = 0.0f,
    val tintColorHex: String = "#FFFFFF",
    val primaryProp: AnimatableProperty = AnimatableProperty(intensity),
    val secondaryProp: AnimatableProperty = AnimatableProperty(secondaryParam),
    val frequencyProp: AnimatableProperty = AnimatableProperty(frequencyParam),
    val angleProp: AnimatableProperty = AnimatableProperty(angleParam),
    val mixProp: AnimatableProperty = AnimatableProperty(mixParam),
    val featherProp: AnimatableProperty = AnimatableProperty(feather),
    val audioBoundBand: AudioBand = AudioBand.NONE,
    val audioSensitivity: Float = 1.0f,
    val customParams: Map<String, AnimatableProperty> = emptyMap(),
    val customColors: Map<String, String> = emptyMap(),
    val isGenericXmlEffect: Boolean = false,
    val genericParamTypes: Map<String, String> = emptyMap(),
    val genericStrings: Map<String, String> = emptyMap(),
    val rawAttributes: Map<String, String> = emptyMap()
) {
    fun propertyFor(slot: EffectParamSlot): AnimatableProperty = when (slot) {
        EffectParamSlot.PRIMARY -> primaryProp
        EffectParamSlot.SECONDARY -> secondaryProp
        EffectParamSlot.FREQUENCY -> frequencyProp
        EffectParamSlot.ANGLE -> angleProp
        EffectParamSlot.MIX -> mixProp
        EffectParamSlot.FEATHER -> featherProp
    }

    fun updateProperty(slot: EffectParamSlot, updated: AnimatableProperty): EffectNode = when (slot) {
        EffectParamSlot.PRIMARY -> copy(intensity = updated.baseValue, primaryProp = updated)
        EffectParamSlot.SECONDARY -> copy(secondaryParam = updated.baseValue, secondaryProp = updated)
        EffectParamSlot.FREQUENCY -> copy(frequencyParam = updated.baseValue, frequencyProp = updated)
        EffectParamSlot.ANGLE -> copy(angleParam = updated.baseValue, angleProp = updated)
        EffectParamSlot.MIX -> copy(mixParam = updated.baseValue, mixProp = updated)
        EffectParamSlot.FEATHER -> copy(feather = updated.baseValue, featherProp = updated)
    }

    fun customParamFor(key: String, defaultVal: Float = 0f): AnimatableProperty =
        customParams[key] ?: AnimatableProperty(defaultVal)

    fun updateCustomParam(key: String, updated: AnimatableProperty): EffectNode =
        copy(customParams = customParams + (key to updated))

    fun customColorFor(key: String, defaultHex: String = "#FFFFFF"): String =
        customColors[key] ?: defaultHex

    fun updateCustomColor(key: String, hex: String): EffectNode =
        copy(customColors = customColors + (key to hex), tintColorHex = hex)

    fun allKeyframeTimes(): List<Long> = (
        EffectParamSlot.entries.flatMap { slot -> propertyFor(slot).keyframes.map { it.timeMs } } +
            customParams.values.flatMap { prop -> prop.keyframes.map { it.timeMs } }
        )
        .distinct()
        .sorted()
}

data class LayerMask(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "Mask",
    val shapeType: MaskShapeType = MaskShapeType.RECTANGLE,
    val enabled: Boolean = true,
    val inverted: Boolean = false,
    val featherPx: AnimatableProperty = AnimatableProperty(0f),
    val expansionPx: AnimatableProperty = AnimatableProperty(0f),
    val opacityPct: AnimatableProperty = AnimatableProperty(100f),
    val vertices: List<MaskVertex> = emptyList(),
    val closed: Boolean = true,
    val rotoMode: String = "REMOVE_BACKGROUND",
    val rotoContourByFrameMs: Map<Long, List<Pair<Float, Float>>> = emptyMap(),
    val rotoFrameMasksPngBase64: Map<Long, String> = emptyMap()
) {
    val type: MaskShapeType get() = shapeType
    val shape: String get() = shapeType.label
    val featherProp: AnimatableProperty get() = featherPx
    val expansionProp: AnimatableProperty get() = expansionPx
    val opacityProp: AnimatableProperty get() = opacityPct
    val feather: Float get() = featherPx.baseValue
    val expansion: Float get() = expansionPx.baseValue
    val opacity: Float get() = opacityPct.baseValue / 100f
    val points: List<DrawingPoint> get() = vertices
    val isRotoMask: Boolean get() = shapeType == MaskShapeType.ROTO
}

typealias MaskNode = LayerMask

data class Layer(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "Layer 1",
    val type: LayerType = LayerType.SHAPE,
    val visible: Boolean = true,
    val startTimeMs: Long = 0L,
    val endTimeMs: Long = DEFAULT_NEW_TIMELINE_DURATION_MS,
    val sourceOffsetMs: Long = 0L,
    val sourceDurationMs: Long = DEFAULT_NEW_TIMELINE_DURATION_MS,
    val mediaTrimOutMs: Long = 0L,
    val isMissingMedia: Boolean = false,
    val mediaWidth: Int = 0,
    val mediaHeight: Int = 0,
    val parentId: String? = null,
    val colorHex: String = "#4F7CFF",
    val fillMode: String = "SOLID", // "SOLID", "LINEAR_GRADIENT", "RADIAL_GRADIENT"
    val gradientEndColorHex: String = "#38BDF8",
    val gradientAngleDeg: Float = 45f,
    val shapeKind: String = "Rounded Rect",
    val blendMode: String = "Normal",
    val strokeEnabled: Boolean = false,
    val strokeWidth: Float = 4f,
    val strokeColorHex: String = "#FFFFFF",
    val strokePosition: String = "CENTER", // "OUTSIDE", "CENTER", "INSIDE"
    val shadowEnabled: Boolean = false,
    val shadowBlur: Float = 16f,
    val shadowColorHex: String = "#000000",
    val shadowOffsetX: Float = 0f,
    val shadowOffsetY: Float = 6f,
    val speedMultiplier: Float = 1.0f,
    val reversed: Boolean = false,
    val timeRemapEnabled: Boolean = false,
    val freezeFrameMs: Long = -1L,
    val audioMuted: Boolean = false,
    val audioVolume: Float = 1.0f,
    val audioPreset: String = "Clean",
    val audioReverbMix: Float = 0f,
    val audioRoomSize: Float = 0.55f,
    val audioBassBoost: Float = 0f,
    val audioEchoMix: Float = 0f,
    val audioEchoDelayMs: Float = 240f,
    val audioFilterCutoff: Float = 1.0f,
    val audioPitchSemitones: Float = 0f,
    val extractedWaveform: List<Float> = emptyList(),
    val motionBlurEnabled: Boolean = false,
    val shutterAngle: Float = 180f,
    val isClippingMask: Boolean = false,
    val flipX: Boolean = false,
    val flipY: Boolean = false,
    val locked: Boolean = false,
    val textContent: String = "TEXT",
    val fontFamilyName: String = "Space Grotesk",
    val textSizeSp: Float = 48f,
    val textWrapWidth: Int = 512,
    val textAlign: String = "center",
    val mediaUri: String = "",
    val primitive3D: Primitive3DType = Primitive3DType.CUBE,
    val drawingPoints: List<DrawingPoint> = emptyList(),
    val drawingClosed: Boolean = false,
    val drawingStrokeWidth: Float = 8f,
    val drawingStrokeColorHex: String = "#FF000000",
    val drawingStartSize: Float = 1f,
    val drawingEndSize: Float = 1f,
    val groupExpanded: Boolean = true,
    val layerMarkers: List<LayerMarker> = emptyList(),
    val rawAttributes: Map<String, String> = emptyMap(),
    val hasSeparateScaleY: Boolean = false,
    val scaleY: AnimatableProperty = AnimatableProperty(100f),
    val posX: AnimatableProperty = AnimatableProperty(0f),
    val posY: AnimatableProperty = AnimatableProperty(0f),
    val posZ: AnimatableProperty = AnimatableProperty(0f),
    val pivotX: AnimatableProperty = AnimatableProperty(0f),
    val pivotY: AnimatableProperty = AnimatableProperty(0f),
    val scale: AnimatableProperty = AnimatableProperty(100f),
    val rotationX: AnimatableProperty = AnimatableProperty(0f),
    val rotationY: AnimatableProperty = AnimatableProperty(0f),
    val rotationZ: AnimatableProperty = AnimatableProperty(0f),
    val opacity: AnimatableProperty = AnimatableProperty(100f),
    val timeRemap: AnimatableProperty = AnimatableProperty(0f),
    val effects: List<EffectNode> = emptyList(),
    val masks: List<MaskNode> = emptyList(),
    val isAdjustmentLayer: Boolean = false
) {
    fun propertyFor(target: PropertyTarget): AnimatableProperty = when (target) {
        PropertyTarget.POS_X -> posX
        PropertyTarget.POS_Y -> posY
        PropertyTarget.POS_Z -> posZ
        PropertyTarget.PIVOT_X -> pivotX
        PropertyTarget.PIVOT_Y -> pivotY
        PropertyTarget.SCALE -> scale
        PropertyTarget.ROTATION_X -> rotationX
        PropertyTarget.ROTATION_Y -> rotationY
        PropertyTarget.ROTATION_Z -> rotationZ
        PropertyTarget.OPACITY -> opacity
        PropertyTarget.TIME_REMAP -> timeRemap
    }

    fun updateProperty(target: PropertyTarget, updated: AnimatableProperty): Layer = when (target) {
        PropertyTarget.POS_X -> copy(posX = updated)
        PropertyTarget.POS_Y -> copy(posY = updated)
        PropertyTarget.POS_Z -> copy(posZ = updated)
        PropertyTarget.PIVOT_X -> copy(pivotX = updated)
        PropertyTarget.PIVOT_Y -> copy(pivotY = updated)
        PropertyTarget.SCALE -> copy(scale = updated)
        PropertyTarget.ROTATION_X -> copy(rotationX = updated)
        PropertyTarget.ROTATION_Y -> copy(rotationY = updated)
        PropertyTarget.ROTATION_Z -> copy(rotationZ = updated)
        PropertyTarget.OPACITY -> copy(opacity = updated)
        PropertyTarget.TIME_REMAP -> copy(timeRemap = updated)
    }

    fun allKeyframeTimes(): List<Long> {
        val layerKfs = PropertyTarget.entries.flatMap { t -> propertyFor(t).keyframes.map { it.timeMs } }
        val fxKfs = effects.flatMap { fx -> fx.allKeyframeTimes() }
        return (layerKfs + fxKfs).distinct().sorted()
    }

    fun shiftTimeRangeAndKeyframes(deltaMs: Long): Layer {
        if (deltaMs == 0L) return this
        fun shiftProp(p: AnimatableProperty): AnimatableProperty {
            if (p.keyframes.isEmpty()) return p
            return p.copy(
                keyframes = p.keyframes.map { kf ->
                    kf.copy(timeMs = (kf.timeMs + deltaMs).coerceAtLeast(0L))
                }.sortedBy { it.timeMs }
            )
        }
        return copy(
            startTimeMs = (startTimeMs + deltaMs).coerceAtLeast(0L),
            endTimeMs = (endTimeMs + deltaMs).coerceAtLeast(100L),
            posX = shiftProp(posX),
            posY = shiftProp(posY),
            posZ = shiftProp(posZ),
            pivotX = shiftProp(pivotX),
            pivotY = shiftProp(pivotY),
            scale = shiftProp(scale),
            rotationX = shiftProp(rotationX),
            rotationY = shiftProp(rotationY),
            rotationZ = shiftProp(rotationZ),
            opacity = shiftProp(opacity),
            scaleY = shiftProp(scaleY),
            timeRemap = shiftProp(timeRemap),
            effects = effects.map { fx ->
                fx.copy(
                    primaryProp = shiftProp(fx.primaryProp),
                    secondaryProp = shiftProp(fx.secondaryProp),
                    frequencyProp = shiftProp(fx.frequencyProp),
                    angleProp = shiftProp(fx.angleProp),
                    mixProp = shiftProp(fx.mixProp),
                    featherProp = shiftProp(fx.featherProp),
                    customParams = fx.customParams.mapValues { (_, p) -> shiftProp(p) }
                )
            }
        )
    }
}

data class BeatMarker(
    val timeMs: Long = 0L,
    val strength: Float = 0.8f,
    val band: AudioBand = AudioBand.BASS
)

@IgnoreExtraProperties
data class MotionProjectDocument(
    @DocumentId val id: String = "",
    val userId: String = "",
    val name: String = "Untitled Project",
    val aspectRatioPreset: String = "16:9",
    val width: Long = 1920L,
    val height: Long = 1080L,
    val fps: Long = 60L,
    val durationMs: Long = 10000L,
    val codec: String = "H.264",
    val bpm: Long = 128L,
    val layersPayload: String = "[]",
    val beatMarkersPayload: String = "[]",
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
)

@IgnoreExtraProperties
data class CustomEffectPresetDocument(
    @DocumentId val id: String = "",
    val userId: String = "",
    val name: String = "Custom Glow Chain",
    val category: String = "Desktop Suite Homages",
    val effectChainPayload: String = "[]",
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
)

data class Project(
    val id: String = "",
    val userId: String = "",
    val name: String = "New Project",
    val aspectRatioPreset: String = "16:9",
    val width: Int = 1920,
    val height: Int = 1080,
    val exportWidth: Int = 0,
    val exportHeight: Int = 0,
    val fps: Int = 60,
    val durationMs: Long = DEFAULT_NEW_TIMELINE_DURATION_MS,
    val codec: String = "H.264",
    val bpm: Int = 128,
    val backgroundColor: String = "Black", // "Black", "Dark Gray", "White", "Transparent"
    val explicitSceneBgHex: String = "",
    val rawAttributes: Map<String, String> = emptyMap(),
    val layers: List<Layer> = emptyList(),
    val beatMarkers: List<BeatMarker> = emptyList(),
    val createdAt: Timestamp? = null,
    val updatedAt: Timestamp? = null
) {
    /**
     * Exact end timestamp of the active edit based on visible layers (or all layers if all hidden),
     * so exported MP4 videos strictly match the edit length without trailing empty frames.
     */
    fun effectiveEditDurationMs(): Long {
        if (durationMs > 0L && rawAttributes.containsKey("__exactSceneTotalTime")) {
            return durationMs.coerceAtLeast(100L)
        }
        val visibleLayersEnd = layers
            .filter { it.visible && it.type != LayerType.NULL_OBJECT && it.type != LayerType.GROUP }
            .maxOfOrNull { it.endTimeMs }
        val anyLayerEnd = layers.maxOfOrNull { it.endTimeMs }
        return when {
            visibleLayersEnd != null && visibleLayersEnd > 0L -> visibleLayersEnd.coerceAtLeast(100L)
            anyLayerEnd != null && anyLayerEnd > 0L -> anyLayerEnd.coerceAtLeast(100L)
            else -> durationMs.coerceAtLeast(500L)
        }
    }

    /**
     * Collects all recursive child/descendant layer IDs parented under [rootLayerId].
     */
    fun descendantLayerIdsOf(rootLayerId: String): Set<String> {
        val out = LinkedHashSet<String>()
        val queue = ArrayDeque<String>()
        queue.add(rootLayerId)
        while (queue.isNotEmpty()) {
            val cur = queue.removeFirst()
            layers.forEach { lyr ->
                if (lyr.parentId == cur && out.add(lyr.id)) {
                    queue.add(lyr.id)
                }
            }
        }
        return out
    }

    /**
     * Dynamic timeline span that expands freely whenever clips or playhead go past the base duration.
     */
    fun freeTimelineSpanMs(playheadMs: Long = 0L): Long {
        val maxLayerEnd = layers.maxOfOrNull { it.endTimeMs } ?: 0L
        return maxOf(durationMs, maxLayerEnd + 2000L, playheadMs + 1000L, 4000L)
    }

    companion object {
        const val DEFAULT_NEW_TIMELINE_DURATION_MS: Long = com.example.data.model.DEFAULT_NEW_TIMELINE_DURATION_MS
        const val DEFAULT_NEW_CLIP_DURATION_MS: Long = com.example.data.model.DEFAULT_NEW_CLIP_DURATION_MS
    }
}

object ProjectPayloadSerializer {

    fun encodeProjectEnvelope(layers: List<Layer>, backgroundColor: String): String {
        val root = JSONObject()
        root.put("bg", backgroundColor)
        root.put("layers", buildLayersJsonArray(layers))
        return root.toString()
    }

    fun decodeBackgroundColor(json: String): String {
        val trimmed = json.trim()
        if (!trimmed.startsWith("{")) return "Black"
        return runCatching { JSONObject(trimmed).optString("bg", "Black") }.getOrDefault("Black")
    }

    fun encodeLayers(layers: List<Layer>): String = buildLayersJsonArray(layers).toString()

    private fun buildLayersJsonArray(layers: List<Layer>): JSONArray {
        val arr = JSONArray()
        layers.forEach { layer ->
            val wfArr = JSONArray()
            layer.extractedWaveform.take(96).forEach { wfArr.put(it.toDouble()) }
            val obj = JSONObject().apply {
                put("id", layer.id)
                put("name", layer.name)
                put("type", layer.type.name)
                put("visible", layer.visible)
                put("startTimeMs", layer.startTimeMs)
                put("endTimeMs", layer.endTimeMs)
                put("sourceOffsetMs", layer.sourceOffsetMs)
                put("sourceDurationMs", layer.sourceDurationMs)
                put("mediaWidth", layer.mediaWidth)
                put("mediaHeight", layer.mediaHeight)
                put("parentId", layer.parentId ?: "")
                put("colorHex", layer.colorHex)
                put("fillMode", layer.fillMode)
                put("gradientEndColorHex", layer.gradientEndColorHex)
                put("gradientAngleDeg", layer.gradientAngleDeg.toDouble())
                put("shapeKind", layer.shapeKind)
                put("blendMode", layer.blendMode)
                put("strokeEnabled", layer.strokeEnabled)
                put("strokeWidth", layer.strokeWidth.toDouble())
                put("strokeColorHex", layer.strokeColorHex)
                put("strokePosition", layer.strokePosition)
                put("shadowEnabled", layer.shadowEnabled)
                put("shadowBlur", layer.shadowBlur.toDouble())
                put("shadowColorHex", layer.shadowColorHex)
                put("shadowOffsetX", layer.shadowOffsetX.toDouble())
                put("shadowOffsetY", layer.shadowOffsetY.toDouble())
                put("speedMultiplier", layer.speedMultiplier.toDouble())
                put("reversed", layer.reversed)
                put("timeRemapEnabled", layer.timeRemapEnabled)
                put("freezeFrameMs", layer.freezeFrameMs)
                put("audioMuted", layer.audioMuted)
                put("audioVolume", layer.audioVolume.toDouble())
                put("audioPreset", layer.audioPreset)
                put("audioReverbMix", layer.audioReverbMix.toDouble())
                put("audioRoomSize", layer.audioRoomSize.toDouble())
                put("audioBassBoost", layer.audioBassBoost.toDouble())
                put("audioEchoMix", layer.audioEchoMix.toDouble())
                put("audioEchoDelayMs", layer.audioEchoDelayMs.toDouble())
                put("audioFilterCutoff", layer.audioFilterCutoff.toDouble())
                put("audioPitchSemitones", layer.audioPitchSemitones.toDouble())
                put("waveform", wfArr)
                put("motionBlurEnabled", layer.motionBlurEnabled)
                put("shutterAngle", layer.shutterAngle.toDouble())
                put("isClippingMask", layer.isClippingMask)
                put("flipX", layer.flipX)
                put("flipY", layer.flipY)
                put("locked", layer.locked)
                put("textContent", layer.textContent)
                put("fontFamilyName", layer.fontFamilyName)
                put("textSizeSp", layer.textSizeSp.toDouble())
                put("textWrapWidth", layer.textWrapWidth)
                put("textAlign", layer.textAlign)
                put("mediaUri", layer.mediaUri)
                put("mediaTrimOutMs", layer.mediaTrimOutMs)
                put("isMissingMedia", layer.isMissingMedia)
                put("primitive3D", layer.primitive3D.name)
                put("groupExpanded", layer.groupExpanded)
                put("hasSeparateScaleY", layer.hasSeparateScaleY)
                put("scaleY", encodeProp(layer.scaleY))
                if (layer.drawingPoints.isNotEmpty()) {
                    put("drawingStrokeWidth", layer.drawingStrokeWidth.toDouble())
                    put("drawingStrokeColorHex", layer.drawingStrokeColorHex)
                    put("drawingStartSize", layer.drawingStartSize.toDouble())
                    put("drawingEndSize", layer.drawingEndSize.toDouble())
                    val dpArr = JSONArray()
                    layer.drawingPoints.forEach { pt ->
                        dpArr.put(JSONObject().apply {
                            put("x", pt.x.toDouble())
                            put("y", pt.y.toDouble())
                            put("p", pt.pressure.toDouble())
                        })
                    }
                    put("drawingPoints", dpArr)
                }
                if (layer.masks.isNotEmpty()) {
                    val mArr = JSONArray()
                    layer.masks.forEach { m ->
                        val mpArr = JSONArray()
                        m.vertices.forEach { pt ->
                            mpArr.put(JSONObject().apply {
                                put("x", pt.x.toDouble())
                                put("y", pt.y.toDouble())
                                put("p", pt.pressure.toDouble())
                                if (pt.inX != 0f || pt.inY != 0f || pt.outX != 0f || pt.outY != 0f) {
                                    put("ix", pt.inX.toDouble())
                                    put("iy", pt.inY.toDouble())
                                    put("ox", pt.outX.toDouble())
                                    put("oy", pt.outY.toDouble())
                                }
                            })
                        }
                        mArr.put(JSONObject().apply {
                            put("id", m.id)
                            put("name", m.name)
                            put("shape", m.shapeType.name)
                            put("enabled", m.enabled)
                            put("closed", m.closed)
                            put("featherProp", encodeProp(m.featherPx))
                            put("expansionProp", encodeProp(m.expansionPx))
                            put("opacityProp", encodeProp(m.opacityPct))
                            put("inverted", m.inverted)
                            put("rotoMode", m.rotoMode)
                            put("points", mpArr)
                        })
                    }
                    put("masks", mArr)
                }
                if (layer.rawAttributes.isNotEmpty()) {
                    val ra = JSONObject()
                    layer.rawAttributes.forEach { (k, v) -> ra.put(k, v) }
                    put("rawAttributes", ra)
                }
                put("posX", encodeProp(layer.posX))
                put("posY", encodeProp(layer.posY))
                put("posZ", encodeProp(layer.posZ))
                put("pivotX", encodeProp(layer.pivotX))
                put("pivotY", encodeProp(layer.pivotY))
                put("scale", encodeProp(layer.scale))
                put("rotationX", encodeProp(layer.rotationX))
                put("rotationY", encodeProp(layer.rotationY))
                put("rotationZ", encodeProp(layer.rotationZ))
                put("opacity", encodeProp(layer.opacity))
                put("timeRemap", encodeProp(layer.timeRemap))
                put("effects", encodeEffects(layer.effects))
            }
            arr.put(obj)
        }
        return arr
    }

    fun decodeLayers(json: String): List<Layer> {
        val trimmed = json.trim()
        if (trimmed.isBlank()) return emptyList()
        return try {
            val arr = if (trimmed.startsWith("{")) {
                JSONObject(trimmed).optJSONArray("layers") ?: JSONArray()
            } else {
                JSONArray(trimmed)
            }
            buildList {
                for (i in 0 until arr.length()) {
                    val obj = arr.getJSONObject(i)
                    val rawParent = obj.optString("parentId", "")
                    val wfJson = obj.optJSONArray("waveform")
                    val wf = if (wfJson != null) {
                        List(wfJson.length()) { idx -> wfJson.optDouble(idx, 0.2).toFloat() }
                    } else {
                        emptyList()
                    }
                    add(
                        Layer(
                            id = obj.optString("id", UUID.randomUUID().toString()),
                            name = obj.optString("name", "Layer ${i + 1}"),
                            type = runCatching { LayerType.valueOf(obj.optString("type", "SHAPE")) }.getOrDefault(LayerType.SHAPE),
                            visible = obj.optBoolean("visible", true),
                            startTimeMs = obj.optLong("startTimeMs", 0L),
                            endTimeMs = obj.optLong("endTimeMs", 10000L),
                            sourceOffsetMs = obj.optLong("sourceOffsetMs", 0L),
                            sourceDurationMs = obj.optLong("sourceDurationMs", 10000L),
                            mediaWidth = obj.optInt("mediaWidth", 0),
                            mediaHeight = obj.optInt("mediaHeight", 0),
                            parentId = rawParent.ifBlank { null },
                            colorHex = obj.optString("colorHex", "#4F7CFF"),
                            fillMode = obj.optString("fillMode", "SOLID"),
                            gradientEndColorHex = obj.optString("gradientEndColorHex", "#38BDF8"),
                            gradientAngleDeg = obj.optDouble("gradientAngleDeg", 45.0).toFloat(),
                            shapeKind = obj.optString("shapeKind", "Rounded Rect"),
                            blendMode = obj.optString("blendMode", "Normal"),
                            strokeEnabled = obj.optBoolean("strokeEnabled", false),
                            strokeWidth = obj.optDouble("strokeWidth", 4.0).toFloat(),
                            strokeColorHex = obj.optString("strokeColorHex", "#FFFFFF"),
                            strokePosition = obj.optString("strokePosition", "CENTER"),
                            shadowEnabled = obj.optBoolean("shadowEnabled", false),
                            shadowBlur = obj.optDouble("shadowBlur", 16.0).toFloat(),
                            shadowColorHex = obj.optString("shadowColorHex", "#000000"),
                            shadowOffsetX = obj.optDouble("shadowOffsetX", 0.0).toFloat(),
                            shadowOffsetY = obj.optDouble("shadowOffsetY", 6.0).toFloat(),
                            speedMultiplier = obj.optDouble("speedMultiplier", 1.0).toFloat(),
                            reversed = obj.optBoolean("reversed", false),
                            timeRemapEnabled = obj.optBoolean("timeRemapEnabled", false),
                            freezeFrameMs = obj.optLong("freezeFrameMs", -1L),
                            audioMuted = obj.optBoolean("audioMuted", false),
                            audioVolume = obj.optDouble("audioVolume", 1.0).toFloat(),
                            audioPreset = obj.optString("audioPreset", "Clean"),
                            audioReverbMix = obj.optDouble("audioReverbMix", 0.0).toFloat(),
                            audioRoomSize = obj.optDouble("audioRoomSize", 0.55).toFloat(),
                            audioBassBoost = obj.optDouble("audioBassBoost", 0.0).toFloat(),
                            audioEchoMix = obj.optDouble("audioEchoMix", 0.0).toFloat(),
                            audioEchoDelayMs = obj.optDouble("audioEchoDelayMs", 240.0).toFloat(),
                            audioFilterCutoff = obj.optDouble("audioFilterCutoff", 1.0).toFloat(),
                            audioPitchSemitones = obj.optDouble("audioPitchSemitones", 0.0).toFloat(),
                            extractedWaveform = wf,
                            motionBlurEnabled = obj.optBoolean("motionBlurEnabled", false),
                            shutterAngle = obj.optDouble("shutterAngle", 180.0).toFloat(),
                            isClippingMask = obj.optBoolean("isClippingMask", false),
                            flipX = obj.optBoolean("flipX", false),
                            flipY = obj.optBoolean("flipY", false),
                            locked = obj.optBoolean("locked", false),
                            textContent = obj.optString("textContent", "TEXT"),
                            fontFamilyName = obj.optString("fontFamilyName", "Space Grotesk"),
                            textSizeSp = obj.optDouble("textSizeSp", 48.0).toFloat(),
                            textWrapWidth = obj.optInt("textWrapWidth", 512),
                            textAlign = obj.optString("textAlign", "center"),
                            mediaUri = obj.optString("mediaUri", ""),
                            mediaTrimOutMs = obj.optLong("mediaTrimOutMs", 0L),
                            isMissingMedia = obj.optBoolean("isMissingMedia", false),
                            primitive3D = runCatching { Primitive3DType.valueOf(obj.optString("primitive3D", "CUBE")) }.getOrDefault(Primitive3DType.CUBE),
                            drawingPoints = decodeDrawingPoints(obj.optJSONArray("drawingPoints")),
                            drawingStrokeWidth = obj.optDouble("drawingStrokeWidth", 8.0).toFloat(),
                            drawingStrokeColorHex = obj.optString("drawingStrokeColorHex", "#FF000000"),
                            drawingStartSize = obj.optDouble("drawingStartSize", 1.0).toFloat(),
                            drawingEndSize = obj.optDouble("drawingEndSize", 1.0).toFloat(),
                            groupExpanded = obj.optBoolean("groupExpanded", false),
                            rawAttributes = decodeStringMap(obj.optJSONObject("rawAttributes")),
                            hasSeparateScaleY = obj.optBoolean("hasSeparateScaleY", false),
                            scaleY = decodeProp(obj.optJSONObject("scaleY"), 100f),
                            posX = decodeProp(obj.optJSONObject("posX"), 0f),
                            posY = decodeProp(obj.optJSONObject("posY"), 0f),
                            posZ = decodeProp(obj.optJSONObject("posZ"), 0f),
                            pivotX = decodeProp(obj.optJSONObject("pivotX"), 0f),
                            pivotY = decodeProp(obj.optJSONObject("pivotY"), 0f),
                            scale = decodeProp(obj.optJSONObject("scale"), 100f),
                            rotationX = decodeProp(obj.optJSONObject("rotationX"), 0f),
                            rotationY = decodeProp(obj.optJSONObject("rotationY"), 0f),
                            rotationZ = decodeProp(obj.optJSONObject("rotationZ"), 0f),
                            opacity = decodeProp(obj.optJSONObject("opacity"), 100f),
                            timeRemap = decodeProp(obj.optJSONObject("timeRemap"), 0f),
                            effects = decodeEffects(obj.optJSONArray("effects")),
                            masks = decodeMasks(obj.optJSONArray("masks"))
                        )
                    )
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun encodeEffects(effects: List<EffectNode>): JSONArray {
        val arr = JSONArray()
        effects.forEach { fx ->
            arr.put(
                JSONObject().apply {
                    put("id", fx.id)
                    put("effectId", fx.effectId)
                    put("displayName", fx.displayName)
                    put("category", fx.category)
                    put("enabled", fx.enabled)
                    put("intensity", fx.primaryProp.baseValue.toDouble())
                    put("secondaryParam", fx.secondaryProp.baseValue.toDouble())
                    put("frequencyParam", fx.frequencyProp.baseValue.toDouble())
                    put("angleParam", fx.angleProp.baseValue.toDouble())
                    put("mixParam", fx.mixProp.baseValue.toDouble())
                    put("feather", fx.featherProp.baseValue.toDouble())
                    put("tintColorHex", fx.tintColorHex)
                    put("primaryProp", encodeProp(fx.primaryProp))
                    put("secondaryProp", encodeProp(fx.secondaryProp))
                    put("frequencyProp", encodeProp(fx.frequencyProp))
                    put("angleProp", encodeProp(fx.angleProp))
                    put("mixProp", encodeProp(fx.mixProp))
                    put("featherProp", encodeProp(fx.featherProp))
                    put("audioBoundBand", fx.audioBoundBand.name)
                    put("audioSensitivity", fx.audioSensitivity.toDouble())
                    put("isGenericXmlEffect", fx.isGenericXmlEffect)
                    if (fx.customParams.isNotEmpty()) {
                        val cpObj = JSONObject()
                        fx.customParams.forEach { (k, prop) -> cpObj.put(k, encodeProp(prop)) }
                        put("customParams", cpObj)
                    }
                    if (fx.customColors.isNotEmpty()) {
                        val ccObj = JSONObject()
                        fx.customColors.forEach { (k, hex) -> ccObj.put(k, hex) }
                        put("customColors", ccObj)
                    }
                    if (fx.genericParamTypes.isNotEmpty()) {
                        val gt = JSONObject()
                        fx.genericParamTypes.forEach { (k, v) -> gt.put(k, v) }
                        put("genericParamTypes", gt)
                    }
                    if (fx.genericStrings.isNotEmpty()) {
                        val gs = JSONObject()
                        fx.genericStrings.forEach { (k, v) -> gs.put(k, v) }
                        put("genericStrings", gs)
                    }
                    if (fx.rawAttributes.isNotEmpty()) {
                        val ra = JSONObject()
                        fx.rawAttributes.forEach { (k, v) -> ra.put(k, v) }
                        put("rawAttributes", ra)
                    }
                }
            )
        }
        return arr
    }

    fun decodeEffects(arr: JSONArray?): List<EffectNode> {
        if (arr == null) return emptyList()
        return buildList {
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val defInt = o.optDouble("intensity", 0.65).toFloat()
                val defSec = o.optDouble("secondaryParam", 0.50).toFloat()
                val defFreq = o.optDouble("frequencyParam", 0.35).toFloat()
                val defAng = o.optDouble("angleParam", 0.0).toFloat()
                val defMix = o.optDouble("mixParam", 100.0).toFloat()
                val defFeather = o.optDouble("feather", 0.0).toFloat()
                val p1 = decodeProp(o.optJSONObject("primaryProp"), defInt)
                val p2 = decodeProp(o.optJSONObject("secondaryProp"), defSec)
                val p3 = decodeProp(o.optJSONObject("frequencyProp"), defFreq)
                val p4 = decodeProp(o.optJSONObject("angleProp"), defAng)
                val p5 = decodeProp(o.optJSONObject("mixProp"), defMix)
                val pf = decodeProp(o.optJSONObject("featherProp"), defFeather)
                val customParamsMap = LinkedHashMap<String, AnimatableProperty>()
                o.optJSONObject("customParams")?.let { cpObj ->
                    val keys = cpObj.keys()
                    while (keys.hasNext()) {
                        val k = keys.next()
                        customParamsMap[k] = decodeProp(cpObj.optJSONObject(k), 0f)
                    }
                }
                val customColorsMap = LinkedHashMap<String, String>()
                o.optJSONObject("customColors")?.let { ccObj ->
                    val keys = ccObj.keys()
                    while (keys.hasNext()) {
                        val k = keys.next()
                        customColorsMap[k] = ccObj.optString(k, "#FFFFFF")
                    }
                }
                add(
                    EffectNode(
                        id = o.optString("id", UUID.randomUUID().toString()),
                        effectId = o.optString("effectId", "S_Glow"),
                        displayName = o.optString("displayName", "S_Glow"),
                        category = o.optString("category", "Desktop Suite Homages"),
                        enabled = o.optBoolean("enabled", true),
                        intensity = p1.baseValue,
                        secondaryParam = p2.baseValue,
                        frequencyParam = p3.baseValue,
                        angleParam = p4.baseValue,
                        mixParam = p5.baseValue,
                        feather = pf.baseValue,
                        tintColorHex = o.optString("tintColorHex", "#FFFFFF"),
                        primaryProp = p1,
                        secondaryProp = p2,
                        frequencyProp = p3,
                        angleProp = p4,
                        mixProp = p5,
                        featherProp = pf,
                        audioBoundBand = runCatching { AudioBand.valueOf(o.optString("audioBoundBand", "NONE")) }.getOrDefault(AudioBand.NONE),
                        audioSensitivity = o.optDouble("audioSensitivity", 1.0).toFloat(),
                        customParams = customParamsMap,
                        customColors = customColorsMap,
                        isGenericXmlEffect = o.optBoolean("isGenericXmlEffect", false),
                        genericParamTypes = decodeStringMap(o.optJSONObject("genericParamTypes")),
                        genericStrings = decodeStringMap(o.optJSONObject("genericStrings")),
                        rawAttributes = decodeStringMap(o.optJSONObject("rawAttributes"))
                    )
                )
            }
        }
    }

    private fun decodeDrawingPoints(arr: JSONArray?): List<DrawingPoint> {
        if (arr == null) return emptyList()
        return List(arr.length()) { idx ->
            val o = arr.getJSONObject(idx)
            DrawingPoint(
                x = o.optDouble("x", 0.0).toFloat(),
                y = o.optDouble("y", 0.0).toFloat(),
                pressure = o.optDouble("p", 1.0).toFloat(),
                inX = o.optDouble("ix", 0.0).toFloat(),
                inY = o.optDouble("iy", 0.0).toFloat(),
                outX = o.optDouble("ox", 0.0).toFloat(),
                outY = o.optDouble("oy", 0.0).toFloat()
            )
        }
    }

    private fun decodeMasks(arr: JSONArray?): List<LayerMask> {
        if (arr == null) return emptyList()
        return List(arr.length()) { idx ->
            val o = arr.getJSONObject(idx)
            val shapeRaw = o.optString("shape", "RECTANGLE")
            val shapeType = MaskShapeType.fromLabel(shapeRaw)
            val featherFallback = o.optDouble("feather", 0.0).toFloat()
            val expFallback = o.optDouble("expansion", 0.0).toFloat()
            val opFallback = o.optDouble("opacity", 100.0).toFloat()
            LayerMask(
                id = o.optString("id", UUID.randomUUID().toString()),
                name = o.optString("name", "${shapeType.label} Mask"),
                shapeType = shapeType,
                enabled = o.optBoolean("enabled", true),
                inverted = o.optBoolean("inverted", false),
                featherPx = decodeProp(o.optJSONObject("featherProp"), featherFallback),
                expansionPx = decodeProp(o.optJSONObject("expansionProp"), expFallback),
                opacityPct = decodeProp(o.optJSONObject("opacityProp"), opFallback),
                vertices = decodeDrawingPoints(o.optJSONArray("points")),
                closed = o.optBoolean("closed", true),
                rotoMode = o.optString("rotoMode", "REMOVE_BACKGROUND")
            )
        }
    }

    private fun decodeStringMap(obj: JSONObject?): Map<String, String> {
        if (obj == null) return emptyMap()
        val out = LinkedHashMap<String, String>()
        val keys = obj.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            out[k] = obj.optString(k, "")
        }
        return out
    }

    fun encodeBeatMarkers(markers: List<BeatMarker>): String {
        val arr = JSONArray()
        markers.forEach { m ->
            arr.put(
                JSONObject().apply {
                    put("timeMs", m.timeMs)
                    put("strength", m.strength.toDouble())
                    put("band", m.band.name)
                }
            )
        }
        return arr.toString()
    }

    fun decodeBeatMarkers(json: String): List<BeatMarker> {
        if (json.isBlank()) return emptyList()
        return try {
            val arr = JSONArray(json)
            buildList {
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    add(
                        BeatMarker(
                            timeMs = o.optLong("timeMs", 0L),
                            strength = o.optDouble("strength", 0.8).toFloat(),
                            band = runCatching { AudioBand.valueOf(o.optString("band", "BASS")) }.getOrDefault(AudioBand.BASS)
                        )
                    )
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun encodeProp(prop: AnimatableProperty): JSONObject {
        val kfArr = JSONArray()
        prop.keyframes.forEach { kf ->
            kfArr.put(
                JSONObject().apply {
                    put("id", kf.id)
                    put("timeMs", kf.timeMs)
                    put("value", kf.value.toDouble())
                    put("outTangentX", kf.outTangentX.toDouble())
                    put("outTangentY", kf.outTangentY.toDouble())
                    put("inTangentX", kf.inTangentX.toDouble())
                    put("inTangentY", kf.inTangentY.toDouble())
                    put("holdStep", kf.holdStep)
                    if (kf.rawEasing.isNotBlank()) {
                        put("rawEasing", kf.rawEasing)
                    }
                }
            )
        }
        return JSONObject().apply {
            put("baseValue", prop.baseValue.toDouble())
            put("expression", prop.expression)
            put("audioReactorBand", prop.audioReactorBand.name)
            put("audioReactorGain", prop.audioReactorGain.toDouble())
            put("keyframes", kfArr)
        }
    }

    private fun decodeProp(obj: JSONObject?, fallback: Float): AnimatableProperty {
        if (obj == null) return AnimatableProperty(fallback)
        val kfArr = obj.optJSONArray("keyframes")
        val kfs = buildList {
            if (kfArr != null) {
                for (i in 0 until kfArr.length()) {
                    val k = kfArr.getJSONObject(i)
                    add(
                        Keyframe(
                            id = k.optString("id", UUID.randomUUID().toString()),
                            timeMs = k.optLong("timeMs", 0L),
                            value = k.optDouble("value", fallback.toDouble()).toFloat(),
                            outTangentX = k.optDouble("outTangentX", 0.42).toFloat(),
                            outTangentY = k.optDouble("outTangentY", 0.0).toFloat(),
                            inTangentX = k.optDouble("inTangentX", 0.58).toFloat(),
                            inTangentY = k.optDouble("inTangentY", 1.0).toFloat(),
                            holdStep = k.optBoolean("holdStep", false),
                            rawEasing = k.optString("rawEasing", "")
                        )
                    )
                }
            }
        }
        return AnimatableProperty(
            baseValue = obj.optDouble("baseValue", fallback.toDouble()).toFloat(),
            keyframes = kfs.sortedBy { it.timeMs },
            expression = obj.optString("expression", ""),
            audioReactorBand = runCatching { AudioBand.valueOf(obj.optString("audioReactorBand", "NONE")) }.getOrDefault(AudioBand.NONE),
            audioReactorGain = obj.optDouble("audioReactorGain", 1.0).toFloat()
        )
    }
}

fun MotionProjectDocument.toDomain(): Project = Project(
    id = id,
    userId = userId,
    name = name,
    aspectRatioPreset = aspectRatioPreset,
    width = width.toInt().coerceIn(64, 7680),
    height = height.toInt().coerceIn(64, 7680),
    fps = fps.toInt().coerceIn(1, 240),
    durationMs = durationMs.coerceIn(100L, 3600000L),
    codec = codec,
    bpm = bpm.toInt().coerceIn(30, 300),
    backgroundColor = ProjectPayloadSerializer.decodeBackgroundColor(layersPayload),
    layers = ProjectPayloadSerializer.decodeLayers(layersPayload),
    beatMarkers = ProjectPayloadSerializer.decodeBeatMarkers(beatMarkersPayload),
    createdAt = createdAt,
    updatedAt = updatedAt
)

fun Project.toDocument(ownerUid: String): MotionProjectDocument = MotionProjectDocument(
    id = id,
    userId = ownerUid,
    name = name.take(120).ifBlank { "Untitled Project" },
    aspectRatioPreset = aspectRatioPreset.take(32).ifBlank { "16:9" },
    width = width.coerceIn(64, 7680).toLong(),
    height = height.coerceIn(64, 7680).toLong(),
    fps = fps.coerceIn(1, 240).toLong(),
    durationMs = durationMs.coerceIn(100L, 3600000L),
    codec = if (codec == "H.265") "H.265" else "H.264",
    bpm = bpm.coerceIn(30, 300).toLong(),
    layersPayload = ProjectPayloadSerializer.encodeProjectEnvelope(layers, backgroundColor),
    beatMarkersPayload = ProjectPayloadSerializer.encodeBeatMarkers(beatMarkers),
    createdAt = createdAt,
    updatedAt = updatedAt
)
