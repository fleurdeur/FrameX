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
    val scaleY: Float = scale,
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
    val mixFraction: Float,
    val featherFraction: Float = 0f
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
                        if ((a.holdStep || b.rawEasing.startsWith("step", ignoreCase = true) || b.rawEasing.startsWith("hold", ignoreCase = true)) && timeMs < b.timeMs) {
                            result = a.value
                            break
                        }
                        val span = (b.timeMs - a.timeMs).coerceAtLeast(1L).toFloat()
                        val rawT = (timeMs - a.timeMs).toFloat() / span
                        val easedT = evaluateSegmentEasing(
                            rawT = rawT,
                            rawEasingOnEnd = b.rawEasing,
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
        val def = EffectsCatalog.findByIdOrNull(effect.effectId)
        val p1Spec = def?.paramSpecs?.firstOrNull { it.slot == com.example.data.model.EffectParamSlot.PRIMARY }
        val p2Spec = def?.paramSpecs?.firstOrNull { it.slot == com.example.data.model.EffectParamSlot.SECONDARY }
        val p3Spec = def?.paramSpecs?.firstOrNull { it.slot == com.example.data.model.EffectParamSlot.FREQUENCY }
        val p4Spec = def?.paramSpecs?.firstOrNull { it.slot == com.example.data.model.EffectParamSlot.ANGLE }

        val p1Span = ((p1Spec?.max ?: 3f) - (p1Spec?.min ?: 0f)).coerceAtLeast(0.1f)
        val nodeAudioMod = audio.levelFor(effect.audioBoundBand) * effect.audioSensitivity * (if (p1Span > 10f) p1Span * 0.35f else 0.65f)

        val rawP1 = evaluateProperty(effect.primaryProp, timeMs, audio) + nodeAudioMod
        val rawP2 = evaluateProperty(effect.secondaryProp, timeMs, audio)
        val rawP3 = evaluateProperty(effect.frequencyProp, timeMs, audio)
        val rawP4 = evaluateProperty(effect.angleProp, timeMs, audio)

        val p1 = if (p1Spec != null) rawP1.coerceIn(p1Spec.min, p1Spec.max) else rawP1.coerceIn(-10000f, 10000f)
        val p2 = if (p2Spec != null) rawP2.coerceIn(p2Spec.min, p2Spec.max) else rawP2.coerceIn(-10000f, 10000f)
        val p3 = if (p3Spec != null) rawP3.coerceIn(p3Spec.min, p3Spec.max) else rawP3.coerceIn(-10000f, 10000f)
        val p4 = if (p4Spec != null) rawP4.coerceIn(p4Spec.min, p4Spec.max) else rawP4.coerceIn(-3600f, 3600f)
        val p5 = (evaluateProperty(effect.mixProp, timeMs, audio) / 100f).coerceIn(0f, 1f)
        val featherFrac = (evaluateProperty(effect.featherProp, timeMs, audio) / 100f).coerceIn(0f, 1f)
        return EvaluatedEffectParams(
            primary = p1,
            secondary = p2,
            frequency = p3,
            angleDeg = p4,
            mixFraction = p5,
            featherFraction = featherFrac
        )
    }

    /**
     * Evaluates Alight Motion 5.0 segment easing (`b.rawEasing` governs segment `a -> b`):
     * - `cubicBezier x1 y1 x2 y2` (with unclamped y1/y2 for overshoot/anticipation)
     * - `bounce a b`
     * - `cyclic period amp phase decay ...`
     * - `step` / `hold`
     */
    fun evaluateSegmentEasing(
        rawT: Float,
        rawEasingOnEnd: String,
        x1: Float,
        y1: Float,
        x2: Float,
        y2: Float
    ): Float {
        val t = rawT.coerceIn(0f, 1f)
        if (t <= 0.0001f) return 0f
        if (t >= 0.9999f) return 1f
        val trimmed = rawEasingOnEnd.trim()
        if (trimmed.isNotEmpty()) {
            val parts = trimmed.split(Regex("\\s+")).filter { it.isNotEmpty() }
            val kind = parts.firstOrNull()?.lowercase().orEmpty()
            when (kind) {
                "cubicbezier" -> {
                    val bx1 = parts.getOrNull(1)?.toFloatOrNull() ?: x1
                    val by1 = parts.getOrNull(2)?.toFloatOrNull() ?: y1
                    val bx2 = parts.getOrNull(3)?.toFloatOrNull() ?: x2
                    val by2 = parts.getOrNull(4)?.toFloatOrNull() ?: y2
                    return evaluateCubicBezier(t, bx1, by1, bx2, by2)
                }
                "bounce" -> {
                    val elasticity = parts.getOrNull(1)?.toFloatOrNull() ?: 1.0f
                    val gravity = parts.getOrNull(2)?.toFloatOrNull() ?: 0.0f
                    return evaluateBounceEasing(t, elasticity, gravity)
                }
                "cyclic" -> {
                    val cycles = parts.getOrNull(1)?.toFloatOrNull() ?: 1.0f
                    val amp = parts.getOrNull(2)?.toFloatOrNull() ?: 0.5f
                    val phase = parts.getOrNull(3)?.toFloatOrNull() ?: 0.0f
                    val decay = parts.getOrNull(4)?.toFloatOrNull() ?: 0.0f
                    val env = if (decay != 0f) exp(-abs(decay) * t) else 1f
                    return t + sin((t * cycles.coerceAtLeast(0.1f) + phase) * 2f * PI.toFloat()) * amp * env * (1f - t)
                }
                "step", "hold" -> return 0f
                "linear" -> return t
            }
        }
        return evaluateCubicBezier(t, x1, y1, x2, y2)
    }

    fun evaluateBounceEasing(tRaw: Float, elasticity: Float = 1.0f, gravityMod: Float = 0.0f): Float {
        val t = tRaw.coerceIn(0f, 1f)
        // Standard Robert Penner / AM bounce curve modulated by elasticity parameter
        val n1 = 7.5625f
        val d1 = 2.75f
        val baseBounce = when {
            t < 1f / d1 -> n1 * t * t
            t < 2f / d1 -> {
                val tt = t - 1.5f / d1
                n1 * tt * tt + 0.75f
            }
            t < 2.5f / d1 -> {
                val tt = t - 2.25f / d1
                n1 * tt * tt + 0.9375f
            }
            else -> {
                val tt = t - 2.625f / d1
                n1 * tt * tt + 0.984375f
            }
        }
        val boost = ((elasticity - 1f) * 0.08f - gravityMod * 0.03f).coerceIn(-0.25f, 0.35f)
        val dampedOsc = sin(t * PI.toFloat() * 3f) * (1f - t) * boost
        return (baseBounce + dampedOsc).let { if (t >= 0.999f) 1f else it }
    }

    fun evaluatePluginUniformFloats(
        plugin: InstalledPluginEffect,
        effect: EffectNode,
        timeMs: Long,
        audio: AudioSpectrumSnapshot = AudioSpectrumSnapshot()
    ): Map<String, Float> {
        val out = LinkedHashMap<String, Float>(plugin.parameters.size)
        var firstFloatBoosted = false
        val nodeAudioMod = audio.levelFor(effect.audioBoundBand) * effect.audioSensitivity
        plugin.parameters.forEach { param ->
            when (param.type) {
                PluginParamType.FLOAT -> {
                    val prop = effect.customParams[param.key] ?: AnimatableProperty(param.defaultFloat)
                    var evaluated = evaluateProperty(prop, timeMs, audio)
                    if (!firstFloatBoosted && nodeAudioMod != 0f) {
                        val span = (param.max - param.min).coerceAtLeast(0.01f)
                        evaluated += nodeAudioMod * span * 0.35f
                        firstFloatBoosted = true
                    }
                    out[param.key] = evaluated.coerceIn(param.min, param.max)
                }
                PluginParamType.BOOL -> {
                    val prop = effect.customParams[param.key] ?: AnimatableProperty(param.defaultFloat)
                    val evaluated = evaluateProperty(prop, timeMs, audio)
                    out[param.key] = if (evaluated >= 0.5f) 1f else 0f
                }
                PluginParamType.COLOR -> Unit
            }
        }
        return out
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
                clean.startsWith("linear(") || clean.startsWith("ease(") ||
                    clean.startsWith("easein(") || clean.startsWith("easeout(") -> {
                    val fn = clean.substringBefore("(")
                    val args = clean.substringAfter("(").substringBeforeLast(")").split(",")
                        .map { resolveExprAtom(it.trim(), timeSec, baseVal) }
                    val tVal = args.getOrElse(0) { timeSec }
                    val tMin = args.getOrElse(1) { 0f }
                    val tMax = args.getOrElse(2) { 1f }
                    val v1 = args.getOrElse(3) { 0f }
                    val v2 = args.getOrElse(4) { 100f }
                    val norm = if (abs(tMax - tMin) > 1e-6f) ((tVal - tMin) / (tMax - tMin)).coerceIn(0f, 1f) else 0f
                    val k = when (fn) {
                        "ease" -> evaluateCubicBezier(norm, 0.42f, 0f, 0.58f, 1f)
                        "easein" -> evaluateCubicBezier(norm, 0.42f, 0f, 1f, 1f)
                        "easeout" -> evaluateCubicBezier(norm, 0f, 0f, 0.58f, 1f)
                        else -> norm
                    }
                    (v1 + (v2 - v1) * k) - baseVal
                }
                clean.startsWith("clamp(") -> {
                    val args = clean.removePrefix("clamp(").removeSuffix(")").split(",")
                        .map { resolveExprAtom(it.trim(), timeSec, baseVal) }
                    val v = args.getOrElse(0) { baseVal }
                    val mn = args.getOrElse(1) { 0f }
                    val mx = args.getOrElse(2) { 100f }
                    v.coerceIn(mn, mx) - baseVal
                }
                else -> clean.toFloatOrNull()?.let { it - baseVal } ?: 0f
            }
        } catch (_: Exception) {
            0f
        }
    }

    private fun resolveExprAtom(token: String, timeSec: Float, baseVal: Float): Float = when (token) {
        "time" -> timeSec
        "value" -> baseVal
        else -> token.toFloatOrNull() ?: 0f
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
        // Camera Z-depth perspective zoom: negative posZ pushes camera closer (zooms in), positive posZ pulls back (zooms out)
        val focalPx = 1000f
        val relDepth = (focalPx + camTr.posZ - baseHierarchy.posZ).coerceAtLeast(80f)
        val depthPerspective = (focalPx / relDepth).coerceIn(0.05f, 16f)
        // Camera scale < 100% zooms IN (smaller viewport), OR > 100% if user scales camera up -> invert scale cleanly
        val invScaleZoom = (1f / camTr.scale.coerceAtLeast(0.04f)).coerceIn(0.04f, 25f)
        val combinedZoom = (invScaleZoom * depthPerspective).coerceIn(0.03f, 25f)

        val camRad = Math.toRadians((-camTr.rotZ).toDouble())
        val cosC = cos(camRad).toFloat()
        val sinC = sin(camRad).toFloat()
        val dx = baseHierarchy.posX - camTr.posX
        val dy = baseHierarchy.posY - camTr.posY

        return baseHierarchy.copy(
            posX = (dx * cosC - dy * sinC) * combinedZoom,
            posY = (dx * sinC + dy * cosC) * combinedZoom,
            posZ = 0f,
            scale = (baseHierarchy.scale * combinedZoom).coerceIn(0.01f, 50f),
            rotX = baseHierarchy.rotX - camTr.rotX,
            rotY = baseHierarchy.rotY - camTr.rotY,
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
        val sx = (evaluateProperty(layer.scale, timeMs, audio) / 100f).coerceIn(0f, 50f)
        val sy = if (layer.hasSeparateScaleY) {
            (evaluateProperty(layer.scaleY, timeMs, audio) / 100f).coerceIn(0f, 50f)
        } else {
            sx
        }
        var localOpacity = (evaluateProperty(layer.opacity, timeMs, audio) / 100f).coerceIn(0f, 1f)
        // Evaluate non-destructive Alight Motion .fade effect envelope if present on the layer
        layer.effects.firstOrNull {
            it.enabled && (it.effectId.equals("AM_Fade", ignoreCase = true) ||
                it.effectId.endsWith(".fade", ignoreCase = true))
        }?.let { fadeFx ->
            val inSec = evaluateProperty(fadeFx.primaryProp, timeMs, audio).coerceAtLeast(0f)
            val outSec = evaluateProperty(fadeFx.secondaryProp, timeMs, audio).coerceAtLeast(0f)
            val inDurMs = (inSec * 1000f).toLong()
            val outDurMs = (outSec * 1000f).toLong()
            var fadeMult = 1f
            if (inDurMs > 4L && timeMs < layer.startTimeMs + inDurMs) {
                fadeMult *= ((timeMs - layer.startTimeMs).toFloat() / inDurMs.toFloat()).coerceIn(0f, 1f)
            }
            if (outDurMs > 4L && timeMs > layer.endTimeMs - outDurMs) {
                fadeMult *= ((layer.endTimeMs - timeMs).toFloat() / outDurMs.toFloat()).coerceIn(0f, 1f)
            }
            localOpacity = (localOpacity * fadeMult).coerceIn(0f, 1f)
        }

        val local = EvaluatedTransform(
            posX = evaluateProperty(layer.posX, timeMs, audio),
            posY = evaluateProperty(layer.posY, timeMs, audio),
            posZ = evaluateProperty(layer.posZ, timeMs, audio),
            pivotX = evaluateProperty(layer.pivotX, timeMs, audio),
            pivotY = evaluateProperty(layer.pivotY, timeMs, audio),
            scale = sx,
            scaleY = sy,
            rotX = evaluateProperty(layer.rotationX, timeMs, audio),
            rotY = evaluateProperty(layer.rotationY, timeMs, audio),
            rotZ = evaluateProperty(layer.rotationZ, timeMs, audio),
            opacity = localOpacity
        )

        val pId = layer.parentId ?: return local
        if (pId in visitedIds || pId == layer.id) return local
        val parent = allLayers.firstOrNull { it.id == pId } ?: return local

        val parentWorld = evaluateHierarchyTransform(parent, allLayers, timeMs, audio, visitedIds + layer.id)
        val rad = Math.toRadians(parentWorld.rotZ.toDouble())
        val cosR = cos(rad).toFloat()
        val sinR = sin(rad).toFloat()

        val scaledLocalX = local.posX * parentWorld.scale
        val scaledLocalY = local.posY * parentWorld.scaleY
        val rotatedX = scaledLocalX * cosR - scaledLocalY * sinR
        val rotatedY = scaledLocalX * sinR + scaledLocalY * cosR

        // Honor com.alightcreative.effects.parenthelper if attached to child layer
        val parentHelper = layer.effects.firstOrNull {
            it.enabled && it.effectId.endsWith("parenthelper", ignoreCase = true)
        }
        val effectiveParentScaleX: Float
        val effectiveParentScaleY: Float
        val effectiveParentRotZ: Float
        if (parentHelper != null) {
            val scaleMode = parentHelper.customParams["scaleMode"]?.let { evaluateProperty(it, timeMs, audio).toInt() } ?: 0
            val rotateMode = parentHelper.customParams["rotateMode"]?.let { evaluateProperty(it, timeMs, audio).toInt() } ?: 0
            val scaleWeight = parentHelper.customParams["scaleWeight"]?.let { evaluateProperty(it, timeMs, audio) } ?: 1f
            val rotateWeight = parentHelper.customParams["rotateWeight"]?.let { evaluateProperty(it, timeMs, audio) } ?: 1f
            effectiveParentScaleX = if (scaleMode == 1) 1f else (1f + (parentWorld.scale - 1f) * scaleWeight)
            effectiveParentScaleY = if (scaleMode == 1) 1f else (1f + (parentWorld.scaleY - 1f) * scaleWeight)
            effectiveParentRotZ = if (rotateMode == 1) 0f else (parentWorld.rotZ * rotateWeight)
        } else {
            effectiveParentScaleX = parentWorld.scale
            effectiveParentScaleY = parentWorld.scaleY
            effectiveParentRotZ = parentWorld.rotZ
        }

        // Null objects are invisible transform helpers — their own opacity does not hide child layers
        val inheritedOpacity = if (parent.type == LayerType.NULL_OBJECT) 1f else parentWorld.opacity

        return EvaluatedTransform(
            posX = parentWorld.posX + rotatedX,
            posY = parentWorld.posY + rotatedY,
            posZ = parentWorld.posZ + local.posZ,
            pivotX = local.pivotX,
            pivotY = local.pivotY,
            scale = (effectiveParentScaleX * local.scale).coerceIn(0f, 50f),
            scaleY = (effectiveParentScaleY * local.scaleY).coerceIn(0f, 50f),
            rotX = parentWorld.rotX + local.rotX,
            rotY = parentWorld.rotY + local.rotY,
            rotZ = effectiveParentRotZ + local.rotZ,
            opacity = (inheritedOpacity * local.opacity).coerceIn(0f, 1f)
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
