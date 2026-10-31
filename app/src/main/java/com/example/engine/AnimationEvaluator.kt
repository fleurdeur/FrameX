package com.example.engine

import com.example.data.model.AnimatableProperty
import com.example.data.model.AudioBand
import com.example.data.model.EffectNode
import com.example.data.model.Keyframe
import com.example.data.model.Layer
import com.example.data.model.LayerType
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sin

data class AudioSpectrumSnapshot(
    val bass: Float = 0f,
    val mids: Float = 0f,
    val treble: Float = 0f,
    val rms: Float = 0f
) {
    fun levelFor(band: AudioBand): Float = when (band) {
        AudioBand.NONE -> 0f
        AudioBand.BASS -> bass
        AudioBand.MIDS -> mids
        AudioBand.TREBLE -> treble
    }
}

data class EvaluatedTransform(
    val posX: Float,
    val posY: Float,
    val posZ: Float,
    val pivotX: Float = 0f,
    val pivotY: Float = 0f,
    val scale: Float,
    val rotX: Float,
    val rotY: Float,
    val rotZ: Float,
    val opacity: Float
)

data class EvaluatedEffectParams(
    val primary: Float,
    val secondary: Float,
    val frequency: Float,
    val angleDeg: Float,
    val mixFraction: Float
)

object AnimationEvaluator {

    /**
     * Solves x(u) = t for u in [0, 1] using Newton-Raphson + bisection fallback,
     * then returns y(u) on the cubic Bezier curve P0=(0,0), P1=(x1,y1), P2=(x2,y2), P3=(1,1).
     */
    fun evaluateCubicBezier(
        t: Float,
        x1: Float,
        y1: Float,
        x2: Float,
        y2: Float
    ): Float {
        val clampedT = t.coerceIn(0f, 1f)
        if (clampedT <= 0.0001f) return 0f
        if (clampedT >= 0.9999f) return 1f

        val cx1 = x1.coerceIn(0f, 1f)
        val cx2 = x2.coerceIn(0f, 1f)

        var u = clampedT
        repeat(6) {
            val currentX = sampleBezier(u, cx1, cx2) - clampedT
            if (abs(currentX) < 1e-4f) return sampleBezier(u, y1, y2)
            val dx = sampleBezierDerivative(u, cx1, cx2)
            if (abs(dx) < 1e-5f) return@repeat
            u = (u - currentX / dx).coerceIn(0f, 1f)
        }

        var low = 0f
        var high = 1f
        u = clampedT
        repeat(10) {
            val x = sampleBezier(u, cx1, cx2)
            if (abs(x - clampedT) < 1e-4f) return sampleBezier(u, y1, y2)
            if (clampedT > x) low = u else high = u
            u = (low + high) * 0.5f
        }
        return sampleBezier(u, y1, y2)
    }

    private fun sampleBezier(u: Float, p1: Float, p2: Float): Float {
        val inv = 1f - u
        return 3f * inv * inv * u * p1 + 3f * inv * u * u * p2 + u * u * u
    }

    private fun sampleBezierDerivative(u: Float, p1: Float, p2: Float): Float {
        val inv = 1f - u
        return 3f * inv * inv * p1 + 6f * inv * u * (p2 - p1) + 3f * u * u * (1f - p2)
    }

    fun evaluateProperty(
        prop: AnimatableProperty,
        timeMs: Long,
        audio: AudioSpectrumSnapshot = AudioSpectrumSnapshot()
    ): Float {
        val kfs = prop.keyframes.sortedBy { it.timeMs }
        val keyframedValue = when {
            kfs.isEmpty() -> prop.baseValue
            timeMs <= kfs.first().timeMs -> kfs.first().value
            timeMs >= kfs.last().timeMs -> kfs.last().value
            else -> {
                var result = prop.baseValue
                for (i in 0 until kfs.size - 1) {
                    val a: Keyframe = kfs[i]
                    val b: Keyframe = kfs[i + 1]
                    if (timeMs in a.timeMs..b.timeMs) {
                        if (a.holdStep && timeMs < b.timeMs) {
                            result = a.value
                            break
                        }
                        val span = (b.timeMs - a.timeMs).coerceAtLeast(1L).toFloat()
                        val rawT = (timeMs - a.timeMs).toFloat() / span
                        val easedT = evaluateCubicBezier(
                            t = rawT,
                            x1 = a.outTangentX,
                            y1 = a.outTangentY,
                            x2 = b.inTangentX,
                            y2 = b.inTangentY
                        )
                        result = a.value + (b.value - a.value) * easedT
                        break
                    }
                }
                result
            }
        }

        val exprOffset = evaluateExpressionOffset(prop.expression, timeMs / 1000f, keyframedValue, kfs, timeMs)
        val isSmallNormalizedRange = abs(prop.baseValue) <= 3.0f && kfs.all { abs(it.value) <= 3.0f }
        val audioBoost = audio.levelFor(prop.audioReactorBand) * prop.audioReactorGain * (if (isSmallNormalizedRange) 0.65f else 40f)
        return keyframedValue + exprOffset + audioBoost
    }

    fun evaluateEffectNode(
        effect: EffectNode,
        timeMs: Long,
        audio: AudioSpectrumSnapshot = AudioSpectrumSnapshot()
    ): EvaluatedEffectParams {
        val nodeAudioMod = audio.levelFor(effect.audioBoundBand) * effect.audioSensitivity * 0.65f
        val p1 = (evaluateProperty(effect.primaryProp, timeMs, audio) + nodeAudioMod).coerceIn(0f, 3f)
        val p2 = evaluateProperty(effect.secondaryProp, timeMs, audio).coerceIn(0f, 2f)
        val p3 = evaluateProperty(effect.frequencyProp, timeMs, audio).coerceIn(0f, 4f)
        val p4 = evaluateProperty(effect.angleProp, timeMs, audio).coerceIn(-720f, 720f)
        val p5 = (evaluateProperty(effect.mixProp, timeMs, audio) / 100f).coerceIn(0f, 1f)
        return EvaluatedEffectParams(
            primary = p1,
            secondary = p2,
            frequency = p3,
            angleDeg = p4,
            mixFraction = p5
        )
    }

    /**
     * Evaluates procedural expressions & KeyFlow by Angkin physics modifiers:
     * - spring(freq, decay): Inertial harmonic bounce after keyframes
     * - wiggle(freq, amp): Camera/handheld shake
     * - pendulum(freq, amp): Smooth damped or continuous swing
     * - sin(time * f) * amp / cos(time * f) * amp
     * - pulse(bpm): Beat-locked pump
     */
    fun evaluateExpressionOffset(
        expr: String,
        timeSec: Float,
        baseVal: Float,
        keyframes: List<Keyframe> = emptyList(),
        timeMs: Long = (timeSec * 1000f).toLong()
    ): Float {
        val clean = expr.trim().lowercase()
        if (clean.isEmpty()) return 0f
        return try {
            when {
                clean.startsWith("spring(") -> {
                    val args = clean.removePrefix("spring(").removeSuffix(")").split(",")
                    val freq = args.getOrNull(0)?.trim()?.toFloatOrNull() ?: 3.2f
                    val decay = args.getOrNull(1)?.trim()?.toFloatOrNull() ?: 4.5f
                    val amp = args.getOrNull(2)?.trim()?.toFloatOrNull() ?: 32f
                    val anchorSec = (keyframes.lastOrNull { it.timeMs <= timeMs }?.timeMs ?: 0L) / 1000f
                    val dt = (timeSec - anchorSec).coerceAtLeast(0f)
                    (sin(dt * freq * 2f * PI.toFloat()) * exp(-decay * dt)) * amp
                }
                clean.startsWith("pendulum(") -> {
                    val args = clean.removePrefix("pendulum(").removeSuffix(")").split(",")
                    val freq = args.getOrNull(0)?.trim()?.toFloatOrNull() ?: 1.5f
                    val amp = args.getOrNull(1)?.trim()?.toFloatOrNull() ?: 25f
                    sin(timeSec * freq * 2f * PI.toFloat()) * amp
                }
                clean.startsWith("wiggle(") -> {
                    val args = clean.removePrefix("wiggle(").removeSuffix(")").split(",")
                    val freq = args.getOrNull(0)?.trim()?.toFloatOrNull() ?: 4f
                    val amp = args.getOrNull(1)?.trim()?.toFloatOrNull() ?: 25f
                    (sin(timeSec * freq * 2f * PI.toFloat()) * 0.65f +
                        cos(timeSec * freq * 3.7f * PI.toFloat()) * 0.35f) * amp
                }
                clean.contains("sin(") -> {
                    val amp = clean.substringAfter("*", "40").trim().toFloatOrNull() ?: 40f
                    val inside = clean.substringAfter("sin(").substringBefore(")")
                    val speed = inside.substringAfter("*", "3").trim().toFloatOrNull() ?: 3f
                    sin(timeSec * speed) * amp
                }
                clean.contains("cos(") -> {
                    val amp = clean.substringAfter("*", "40").trim().toFloatOrNull() ?: 40f
                    val inside = clean.substringAfter("cos(").substringBefore(")")
                    val speed = inside.substringAfter("*", "3").trim().toFloatOrNull() ?: 3f
                    cos(timeSec * speed) * amp
                }
                clean.startsWith("pulse(") -> {
                    val bpm = clean.removePrefix("pulse(").removeSuffix(")").trim().toFloatOrNull() ?: 128f
                    val beatPhase = (timeSec * (bpm / 60f)) % 1f
                    (1f - beatPhase).pow(3f) * 28f
                }
                else -> clean.toFloatOrNull()?.let { it - baseVal } ?: 0f
            }
        } catch (_: Exception) {
            0f
        }
    }

    /**
     * Computes exact source media timestamp (in ms) for VIDEO or AUDIO layers, honoring:
     * 1. Freeze Frame (freezeFrameMs >= 0L)
     * 2. Time Remapping / Velocity Curves (timeRemapEnabled == true -> evaluates timeRemap seconds)
     * 3. Reverse Playback (reversed == true -> plays backward from sourceDurationMs)
     * 4. Speed Multiplier (0.1x..4.0x) + Trim In Offset (sourceOffsetMs)
     */
    fun evaluateSourceMediaTimeMs(
        layer: Layer,
        playheadMs: Long,
        audio: AudioSpectrumSnapshot = AudioSpectrumSnapshot()
    ): Long {
        val safeDur = layer.sourceDurationMs.coerceAtLeast(100L)
        if (layer.freezeFrameMs >= 0L) {
            return layer.freezeFrameMs.coerceIn(0L, safeDur)
        }
        if (layer.timeRemapEnabled) {
            val remappedSec = evaluateProperty(layer.timeRemap, playheadMs, audio)
            val remappedMs = (remappedSec * 1000f).toLong().coerceIn(0L, safeDur)
            return if (layer.reversed) (safeDur - remappedMs).coerceIn(0L, safeDur) else remappedMs
        }
        val localElapsed = (playheadMs - layer.startTimeMs).coerceAtLeast(0L)
        val scaledOffset = layer.sourceOffsetMs + (localElapsed * layer.speedMultiplier.coerceIn(0.05f, 8f)).toLong()
        val wrapped = scaledOffset.coerceIn(0L, safeDur)
        return if (layer.reversed) (safeDur - wrapped).coerceIn(0L, safeDur) else wrapped
    }

    /**
     * Evaluates a layer's world transform including cascading Null Object / Parent hierarchy,
     * plus optional active Camera Layer view projection when [applyActiveCamera] is true.
     */
    fun evaluateWorldTransform(
        layer: Layer,
        allLayers: List<Layer>,
        timeMs: Long,
        audio: AudioSpectrumSnapshot,
        visitedIds: Set<String> = emptySet(),
        applyActiveCamera: Boolean = false
    ): EvaluatedTransform {
        val baseHierarchy = evaluateHierarchyTransform(layer, allLayers, timeMs, audio, visitedIds)
        if (!applyActiveCamera || layer.type == LayerType.CAMERA) return baseHierarchy

        val activeCam = allLayers.lastOrNull {
            it.type == LayerType.CAMERA && it.visible && timeMs in it.startTimeMs..it.endTimeMs
        } ?: return baseHierarchy

        val camTr = evaluateHierarchyTransform(activeCam, allLayers, timeMs, audio, emptySet())
        val invZoom = (1f / camTr.scale.coerceAtLeast(0.05f)).coerceIn(0.05f, 12f)
        val camRad = Math.toRadians((-camTr.rotZ).toDouble())
        val cosC = cos(camRad).toFloat()
        val sinC = sin(camRad).toFloat()
        val dx = baseHierarchy.posX - camTr.posX
        val dy = baseHierarchy.posY - camTr.posY

        return baseHierarchy.copy(
            posX = (dx * cosC - dy * sinC) * invZoom,
            posY = (dx * sinC + dy * cosC) * invZoom,
            scale = (baseHierarchy.scale * invZoom).coerceIn(0.02f, 12f),
            rotZ = baseHierarchy.rotZ - camTr.rotZ
        )
    }

    private fun evaluateHierarchyTransform(
        layer: Layer,
        allLayers: List<Layer>,
        timeMs: Long,
        audio: AudioSpectrumSnapshot,
        visitedIds: Set<String>
    ): EvaluatedTransform {
        val local = EvaluatedTransform(
            posX = evaluateProperty(layer.posX, timeMs, audio),
            posY = evaluateProperty(layer.posY, timeMs, audio),
            posZ = evaluateProperty(layer.posZ, timeMs, audio),
            pivotX = evaluateProperty(layer.pivotX, timeMs, audio),
            pivotY = evaluateProperty(layer.pivotY, timeMs, audio),
            scale = (evaluateProperty(layer.scale, timeMs, audio) / 100f).coerceIn(0.02f, 8f),
            rotX = evaluateProperty(layer.rotationX, timeMs, audio),
            rotY = evaluateProperty(layer.rotationY, timeMs, audio),
            rotZ = evaluateProperty(layer.rotationZ, timeMs, audio),
            opacity = (evaluateProperty(layer.opacity, timeMs, audio) / 100f).coerceIn(0f, 1f)
        )

        val pId = layer.parentId ?: return local
        if (pId in visitedIds || pId == layer.id) return local
        val parent = allLayers.firstOrNull { it.id == pId } ?: return local

        val parentWorld = evaluateHierarchyTransform(parent, allLayers, timeMs, audio, visitedIds + layer.id)
        val rad = Math.toRadians(parentWorld.rotZ.toDouble())
        val cosR = cos(rad).toFloat()
        val sinR = sin(rad).toFloat()

        val rotatedX = (local.posX * cosR - local.posY * sinR) * parentWorld.scale
        val rotatedY = (local.posX * sinR + local.posY * cosR) * parentWorld.scale

        return EvaluatedTransform(
            posX = parentWorld.posX + rotatedX,
            posY = parentWorld.posY + rotatedY,
            posZ = parentWorld.posZ + local.posZ,
            pivotX = local.pivotX,
            pivotY = local.pivotY,
            scale = (parentWorld.scale * local.scale).coerceIn(0.02f, 8f),
            rotX = parentWorld.rotX + local.rotX,
            rotY = parentWorld.rotY + local.rotY,
            rotZ = parentWorld.rotZ + local.rotZ,
            opacity = (parentWorld.opacity * local.opacity).coerceIn(0f, 1f)
        )
    }

    fun formatSmpteTimecode(timeMs: Long, fps: Int): String {
        val safeFps = fps.coerceIn(1, 240)
        val clampedMs = timeMs.coerceAtLeast(0L)
        val totalSeconds = clampedMs / 1000L
        val hours = totalSeconds / 3600L
        val minutes = (totalSeconds % 3600L) / 60L
        val seconds = totalSeconds % 60L
        val remMs = clampedMs % 1000L
        val frame = ((remMs * safeFps) / 1000L).toInt().coerceIn(0, safeFps - 1)
        return String.format("%02d:%02d:%02d:%02d", hours, minutes, seconds, frame)
    }

    fun snapToFrameMs(timeMs: Long, fps: Int): Long {
        val safeFps = fps.coerceIn(1, 240)
        val frameDuration = 1000.0 / safeFps.toDouble()
        val frameIndex = kotlin.math.round(timeMs / frameDuration).toLong()
        return (frameIndex * frameDuration).toLong().coerceAtLeast(0L)
    }
}
