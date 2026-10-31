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

enum class LayerType(val label: String) {
    SHAPE("Shape"),
    TEXT("Text"),
    VIDEO("Video"),
    IMAGE("Image"),
    AUDIO("Audio"),
    CAMERA("Camera"),
    MODEL_3D("3D Object"),
    NULL_OBJECT("Null Rig")
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

enum class PropertyTarget(val label: String, val unit: String, val minVal: Float, val maxVal: Float) {
    POS_X("Pos X", "px", -1500f, 1500f),
    POS_Y("Pos Y", "px", -1500f, 1500f),
    POS_Z("Z Depth", "px", -1000f, 1000f),
    PIVOT_X("Pivot X", "px", -600f, 600f),
    PIVOT_Y("Pivot Y", "px", -600f, 600f),
    SCALE("Scale", "%", 5f, 500f),
    ROTATION_X("Rot X", "°", -360f, 360f),
    ROTATION_Y("Rot Y", "°", -360f, 360f),
    ROTATION_Z("Rotation", "°", -360f, 360f),
    OPACITY("Opacity", "%", 0f, 100f),
    TIME_REMAP("Time Remap", "s", 0f, 120f)
}

data class Keyframe(
    val id: String = UUID.randomUUID().toString(),
    val timeMs: Long = 0L,
    val value: Float = 0f,
    // Cubic Bezier control points in normalized [0..1] domain: P1=(x1,y1), P2=(x2,y2)
    val outTangentX: Float = 0.42f,
    val outTangentY: Float = 0.0f,
    val inTangentX: Float = 0.58f,
    val inTangentY: Float = 1.0f
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
    val audioBoundBand: AudioBand = AudioBand.NONE,
    val audioSensitivity: Float = 1.0f
)

data class MaskNode(
    val id: String = UUID.randomUUID().toString(),
    val shape: String = "Rectangle",
    val feather: Float = 12f,
    val inverted: Boolean = false
)

data class Layer(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "Layer 1",
    val type: LayerType = LayerType.SHAPE,
    val visible: Boolean = true,
    val startTimeMs: Long = 0L,
    val endTimeMs: Long = 10000L,
    val sourceOffsetMs: Long = 0L,
    val sourceDurationMs: Long = 10000L,
    val mediaWidth: Int = 0,
    val mediaHeight: Int = 0,
    val parentId: String? = null,
    val colorHex: String = "#00D2A8",
    val shapeKind: String = "Rounded Rect",
    val blendMode: String = "Normal",
    val strokeEnabled: Boolean = false,
    val strokeWidth: Float = 4f,
    val strokeColorHex: String = "#FFFFFF",
    val shadowEnabled: Boolean = false,
    val shadowBlur: Float = 16f,
    val shadowColorHex: String = "#000000",
    val speedMultiplier: Float = 1.0f,
    val reversed: Boolean = false,
    val timeRemapEnabled: Boolean = false,
    val freezeFrameMs: Long = -1L,
    val audioMuted: Boolean = false,
    val audioVolume: Float = 1.0f,
    val extractedWaveform: List<Float> = emptyList(),
    val motionBlurEnabled: Boolean = false,
    val shutterAngle: Float = 180f,
    val isClippingMask: Boolean = false,
    val flipX: Boolean = false,
    val flipY: Boolean = false,
    val locked: Boolean = false,
    val textContent: String = "TEXT",
    val fontFamilyName: String = "Space Grotesk",
    val mediaUri: String = "",
    val primitive3D: Primitive3DType = Primitive3DType.CUBE,
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
    val masks: List<MaskNode> = emptyList()
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
    val fps: Int = 60,
    val durationMs: Long = 10000L,
    val codec: String = "H.264",
    val bpm: Int = 128,
    val backgroundColor: String = "Black", // "Black", "Dark Gray", "White", "Transparent"
    val layers: List<Layer> = emptyList(),
    val beatMarkers: List<BeatMarker> = emptyList(),
    val createdAt: Timestamp? = null,
    val updatedAt: Timestamp? = null
)

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
                put("shapeKind", layer.shapeKind)
                put("blendMode", layer.blendMode)
                put("strokeEnabled", layer.strokeEnabled)
                put("strokeWidth", layer.strokeWidth.toDouble())
                put("strokeColorHex", layer.strokeColorHex)
                put("shadowEnabled", layer.shadowEnabled)
                put("shadowBlur", layer.shadowBlur.toDouble())
                put("shadowColorHex", layer.shadowColorHex)
                put("speedMultiplier", layer.speedMultiplier.toDouble())
                put("reversed", layer.reversed)
                put("timeRemapEnabled", layer.timeRemapEnabled)
                put("freezeFrameMs", layer.freezeFrameMs)
                put("audioMuted", layer.audioMuted)
                put("audioVolume", layer.audioVolume.toDouble())
                put("waveform", wfArr)
                put("motionBlurEnabled", layer.motionBlurEnabled)
                put("shutterAngle", layer.shutterAngle.toDouble())
                put("isClippingMask", layer.isClippingMask)
                put("flipX", layer.flipX)
                put("flipY", layer.flipY)
                put("locked", layer.locked)
                put("textContent", layer.textContent)
                put("fontFamilyName", layer.fontFamilyName)
                put("mediaUri", layer.mediaUri)
                put("primitive3D", layer.primitive3D.name)
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
                            colorHex = obj.optString("colorHex", "#00D2A8"),
                            shapeKind = obj.optString("shapeKind", "Rounded Rect"),
                            blendMode = obj.optString("blendMode", "Normal"),
                            strokeEnabled = obj.optBoolean("strokeEnabled", false),
                            strokeWidth = obj.optDouble("strokeWidth", 4.0).toFloat(),
                            strokeColorHex = obj.optString("strokeColorHex", "#FFFFFF"),
                            shadowEnabled = obj.optBoolean("shadowEnabled", false),
                            shadowBlur = obj.optDouble("shadowBlur", 16.0).toFloat(),
                            shadowColorHex = obj.optString("shadowColorHex", "#000000"),
                            speedMultiplier = obj.optDouble("speedMultiplier", 1.0).toFloat(),
                            reversed = obj.optBoolean("reversed", false),
                            timeRemapEnabled = obj.optBoolean("timeRemapEnabled", false),
                            freezeFrameMs = obj.optLong("freezeFrameMs", -1L),
                            audioMuted = obj.optBoolean("audioMuted", false),
                            audioVolume = obj.optDouble("audioVolume", 1.0).toFloat(),
                            extractedWaveform = wf,
                            motionBlurEnabled = obj.optBoolean("motionBlurEnabled", false),
                            shutterAngle = obj.optDouble("shutterAngle", 180.0).toFloat(),
                            isClippingMask = obj.optBoolean("isClippingMask", false),
                            flipX = obj.optBoolean("flipX", false),
                            flipY = obj.optBoolean("flipY", false),
                            locked = obj.optBoolean("locked", false),
                            textContent = obj.optString("textContent", "TEXT"),
                            fontFamilyName = obj.optString("fontFamilyName", "Space Grotesk"),
                            mediaUri = obj.optString("mediaUri", ""),
                            primitive3D = runCatching { Primitive3DType.valueOf(obj.optString("primitive3D", "CUBE")) }.getOrDefault(Primitive3DType.CUBE),
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
                            effects = decodeEffects(obj.optJSONArray("effects"))
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
                    put("intensity", fx.intensity.toDouble())
                    put("secondaryParam", fx.secondaryParam.toDouble())
                    put("frequencyParam", fx.frequencyParam.toDouble())
                    put("audioBoundBand", fx.audioBoundBand.name)
                    put("audioSensitivity", fx.audioSensitivity.toDouble())
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
                add(
                    EffectNode(
                        id = o.optString("id", UUID.randomUUID().toString()),
                        effectId = o.optString("effectId", "S_Glow"),
                        displayName = o.optString("displayName", "S_Glow"),
                        category = o.optString("category", "Desktop Suite Homages"),
                        enabled = o.optBoolean("enabled", true),
                        intensity = o.optDouble("intensity", 0.65).toFloat(),
                        secondaryParam = o.optDouble("secondaryParam", 0.50).toFloat(),
                        frequencyParam = o.optDouble("frequencyParam", 0.35).toFloat(),
                        audioBoundBand = runCatching { AudioBand.valueOf(o.optString("audioBoundBand", "NONE")) }.getOrDefault(AudioBand.NONE),
                        audioSensitivity = o.optDouble("audioSensitivity", 1.0).toFloat()
                    )
                )
            }
        }
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
                            inTangentY = k.optDouble("inTangentY", 1.0).toFloat()
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
