package com.example.engine

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import com.example.data.model.AnimatableProperty
import com.example.data.model.EffectNode
import com.example.data.model.Layer
import com.example.data.model.LayerType
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Shared high-precision rasterizer and shader sub-mode resolver for:
 * 1. KeyFlow Text Animator & Alight Motion Text Transform / Text Spacing (Characters / Words / Lines)
 * 2. Shape & Text Stroke (`OUTSIDE`, `CENTER`, `INSIDE`) + Drop Shadow (`Blur`, `Offset X`, `Offset Y`)
 * 3. Media (`IMAGE` / `VIDEO`) Stroke & Drop Shadow frame overlays
 * 4. Per-Effect GLSL Sub-Mode dispatch so every effect in EffectsCatalog executes authentic math
 */
object KeyflowTextAndBorderRenderer {

    const val SUBMODE_DEFAULT = 0
    const val SUBMODE_EXPOSURE_GAMMA = 101
    const val SUBMODE_VIGNETTE = 102
    const val SUBMODE_HUE_SHIFT = 103
    const val SUBMODE_SATURATION_VIBRANCE = 104
    const val SUBMODE_INVERT = 105
    const val SUBMODE_POSTERIZE = 106
    const val SUBMODE_FLICKER_STROBE = 107
    const val SUBMODE_COLOR_TEMP = 108
    const val SUBMODE_LUMA_FADE = 109

    const val SUBMODE_PINCH_BULGE = 110
    const val SUBMODE_SWIRL = 111
    const val SUBMODE_SQUEEZE = 112
    const val SUBMODE_RIPPLE = 113

    const val SUBMODE_OSCILLATE = 114
    const val SUBMODE_SWING = 115
    const val SUBMODE_BEAT_PULSE = 116
    const val SUBMODE_SPIN_TRANSITION = 117

    const val SUBMODE_RADIAL_CLOCK_WIPE = 118
    const val SUBMODE_VENETIAN_BLINDS = 119
    const val SUBMODE_LUMA_KEY = 120
    const val SUBMODE_POLAR_COORDS = 121
    const val SUBMODE_MIRROR_HORIZ = 122
    const val SUBMODE_MIRROR_QUAD = 123
    const val SUBMODE_GAUSSIAN_BLUR = 124
    const val SUBMODE_THRESHOLD = 125
    const val SUBMODE_HALFTONE = 126

    fun resolveEffectSubMode(effectId: String): Int = when (effectId) {
        "Exposure_Gamma", "Highlights_Shadows", "Color_Balance_Wheels" -> SUBMODE_EXPOSURE_GAMMA
        "S_Vignette", "Vignette_Cinema" -> SUBMODE_VIGNETTE
        "Hue_Shift" -> SUBMODE_HUE_SHIFT
        "Saturation_Vibrance", "Duotone_Shadow" -> SUBMODE_SATURATION_VIBRANCE
        "Invert" -> SUBMODE_INVERT
        "Posterize_Levels", "Oil_Paint_Poster" -> SUBMODE_POSTERIZE
        "S_Flicker", "Blink_Strobe" -> SUBMODE_FLICKER_STROBE
        "Color_Temperature", "Vintage_Sepia_LUT", "Kodak_2383_LUT" -> SUBMODE_COLOR_TEMP
        "Luma_Fade" -> SUBMODE_LUMA_FADE
        "Pinch_Bulge", "Fisheye_180", "Lens_Distortion", "CRT_Barrel", "Vertigo_Dolly" -> SUBMODE_PINCH_BULGE
        "Swirl_Twirl" -> SUBMODE_SWIRL
        "Squeeze", "Bend_Arc", "Page_Curl_2D" -> SUBMODE_SQUEEZE
        "Ripple_Pond" -> SUBMODE_RIPPLE
        "Oscillate", "Horiz_Twitch" -> SUBMODE_OSCILLATE
        "Swing", "Pendulum_Roll" -> SUBMODE_SWING
        "Beat_Sync_Pulse", "Spring_Bounce" -> SUBMODE_BEAT_PULSE
        "Spin_Transition" -> SUBMODE_SPIN_TRANSITION
        "Radial_Clock_Wipe" -> SUBMODE_RADIAL_CLOCK_WIPE
        "Venetian_Blinds" -> SUBMODE_VENETIAN_BLINDS
        "Luma_Key" -> SUBMODE_LUMA_KEY
        "Polar_Coordinates", "Iris_Reveal" -> SUBMODE_POLAR_COORDS
        "Mirror_Horizontal" -> SUBMODE_MIRROR_HORIZ
        "Mirror_Quad" -> SUBMODE_MIRROR_QUAD
        "Gaussian_Blur", "Box_Fast_Blur", "Smart_Surface_Blur", "BCC_LensBlur", "Cross_Blur_Dissolve", "Tilt_Shift_Miniature" -> SUBMODE_GAUSSIAN_BLUR
        "Threshold_Binary", "Bayer_Dither" -> SUBMODE_THRESHOLD
        "Comic_Halftone", "ASCII_Matrix" -> SUBMODE_HALFTONE
        else -> SUBMODE_DEFAULT
    }

    data class EvaluatedTextAnimator(
        val effectId: String,
        val startPct: Float,
        val endPct: Float,
        val phasePct: Float,
        val smoothPct: Float,
        val unitMode: Int, // 0 = Chars, 1 = Words, 2 = Lines
        val posX: Float,
        val posY: Float,
        val scalePct: Float,
        val rotDeg: Float,
        val opacityPct: Float,
        val trackingPx: Float,
        val lineSkewDeg: Float,
        val mixFraction: Float,
        val tintColorHex: String
    )

    fun evaluateTextAnimators(
        layer: Layer,
        timeMs: Long,
        audio: AudioSpectrumSnapshot
    ): List<EvaluatedTextAnimator> {
        if (layer.type != LayerType.TEXT) return emptyList()
        val matching = layer.effects.filter {
            it.enabled && EffectsCatalog.isTextOnlyEffect(it.effectId)
        }
        if (matching.isEmpty()) return emptyList()

        return matching.mapNotNull { fx ->
            val eval = AnimationEvaluator.evaluateEffectNode(fx, timeMs, audio)
            if (eval.mixFraction <= 0.002f) return@mapNotNull null

            if (fx.effectId.equals("Text_Spacing", ignoreCase = true)) {
                EvaluatedTextAnimator(
                    effectId = fx.effectId,
                    startPct = eval.primary.coerceIn(0f, 100f),
                    endPct = eval.secondary.coerceIn(0f, 100f),
                    phasePct = 0f,
                    smoothPct = 20f,
                    unitMode = 0,
                    posX = 0f,
                    posY = 0f,
                    scalePct = 100f,
                    rotDeg = 0f,
                    opacityPct = 100f,
                    trackingPx = eval.frequency.coerceIn(-30f, 120f),
                    lineSkewDeg = eval.angleDeg.coerceIn(-45f, 45f),
                    mixFraction = eval.mixFraction,
                    tintColorHex = fx.tintColorHex
                )
            } else if (fx.effectId.equals("Keyflow_Text_Animator", ignoreCase = true)) {
                fun evalCustom(key: String, altKey: String? = null, fallback: Float): Float {
                    val prop: AnimatableProperty? = fx.customParams[key] ?: altKey?.let { fx.customParams[it] }
                    return if (prop != null) AnimationEvaluator.evaluateProperty(prop, timeMs, audio) else fallback
                }
                EvaluatedTextAnimator(
                    effectId = fx.effectId,
                    startPct = evalCustom("kf_range_start", fallback = eval.primary).coerceIn(0f, 100f),
                    endPct = evalCustom("kf_range_end", fallback = eval.secondary).coerceIn(0f, 100f),
                    phasePct = evalCustom("kf_phase", fallback = eval.frequency).coerceIn(-100f, 100f),
                    smoothPct = evalCustom("kf_smooth", fallback = 35f).coerceIn(0f, 100f),
                    unitMode = evalCustom("kf_unit_mode", "kf_unit", 0f).roundToInt().coerceIn(0, 2),
                    posX = evalCustom("kf_offset_x", "kf_posX", 0f).coerceIn(-10000f, 10000f),
                    posY = evalCustom("kf_offset_y", "kf_posY", -48f).coerceIn(-10000f, 10000f),
                    scalePct = evalCustom("kf_scale", fallback = 100f).coerceIn(0f, 1000f),
                    rotDeg = evalCustom("kf_rotation", "kf_rot", 0f).coerceIn(-3600f, 3600f),
                    opacityPct = evalCustom("kf_opacity", fallback = 0f).coerceIn(0f, 100f),
                    trackingPx = evalCustom("kf_tracking", fallback = 0f).coerceIn(-200f, 400f),
                    lineSkewDeg = 0f,
                    mixFraction = 1f,
                    tintColorHex = fx.tintColorHex
                )
            } else {
                // Text_Transform: 5-slot range + angle/fade + Based On unit mode (Characters / Words / Lines)
                val unitProp = fx.customParams["kf_unit_mode"] ?: fx.customParams["kf_unit"]
                val unitMode = if (unitProp != null) {
                    AnimationEvaluator.evaluateProperty(unitProp, timeMs, audio).roundToInt().coerceIn(0, 2)
                } else 0
                val fadeOrScale = (eval.mixFraction * 100f).coerceIn(0f, 100f)
                EvaluatedTextAnimator(
                    effectId = fx.effectId,
                    startPct = eval.primary.coerceIn(0f, 100f),
                    endPct = eval.secondary.coerceIn(0f, 100f),
                    phasePct = eval.frequency.coerceIn(-100f, 100f),
                    smoothPct = 28f,
                    unitMode = unitMode,
                    posX = 0f,
                    posY = (eval.angleDeg.takeIf { abs(it) > 0.01f } ?: -36f) * 1.1f,
                    scalePct = (35f + fadeOrScale * 0.65f).coerceIn(10f, 200f),
                    rotDeg = eval.angleDeg * 0.35f,
                    opacityPct = (fadeOrScale * 0.25f).coerceIn(0f, 100f),
                    trackingPx = 4f,
                    lineSkewDeg = 0f,
                    mixFraction = eval.mixFraction.coerceAtLeast(0.85f),
                    tintColorHex = fx.tintColorHex
                )
            }
        }
    }

    fun buildTextCacheSignature(
        layer: Layer,
        animators: List<EvaluatedTextAnimator>
    ): String {
        val sb = StringBuilder(160)
        sb.append("text2|").append(layer.textContent).append('|')
            .append(layer.fontFamilyName).append('|')
            .append(layer.colorHex).append('|')
            .append(layer.fillMode).append('|')
            .append(layer.gradientEndColorHex).append('|')
            .append(layer.gradientAngleDeg.toInt()).append('|')
            .append(layer.strokeEnabled).append(':')
            .append((layer.strokeWidth * 2f).toInt()).append(':')
            .append(layer.strokeColorHex).append(':')
            .append(layer.strokePosition).append('|')
            .append(layer.shadowEnabled).append(':')
            .append((layer.shadowBlur * 2f).toInt()).append(':')
            .append(layer.shadowOffsetX.toInt()).append(':')
            .append(layer.shadowOffsetY.toInt()).append(':')
            .append(layer.shadowColorHex)
        animators.forEach { a ->
            sb.append("|ta:").append(a.effectId).append(':')
                .append((a.startPct * 2f).toInt()).append(':')
                .append((a.endPct * 2f).toInt()).append(':')
                .append((a.phasePct * 2f).toInt()).append(':')
                .append(a.smoothPct.toInt()).append(':')
                .append(a.unitMode).append(':')
                .append(a.posX.toInt()).append(':')
                .append(a.posY.toInt()).append(':')
                .append(a.scalePct.toInt()).append(':')
                .append(a.rotDeg.toInt()).append(':')
                .append(a.opacityPct.toInt()).append(':')
                .append((a.trackingPx * 2f).toInt()).append(':')
                .append(a.lineSkewDeg.toInt()).append(':')
                .append((a.mixFraction * 100f).toInt()).append(':')
                .append(a.tintColorHex)
        }
        return sb.toString()
    }

    private fun computeRangeWeight(
        unitIndex: Int,
        unitCount: Int,
        startPct: Float,
        endPct: Float,
        phasePct: Float,
        smoothPct: Float,
        mixFraction: Float
    ): Float {
        if (unitCount <= 0 || mixFraction <= 0.001f) return 0f
        val lo = min(startPct, endPct) / 100f
        val hi = max(startPct, endPct) / 100f
        if (hi - lo <= 0.002f) return 0f

        val u = if (unitCount <= 1) 0.5f else (unitIndex + 0.5f) / unitCount.toFloat()
        val phase = phasePct / 100f
        val shifted = ((u - phase) % 1f + 1f) % 1f
        val ramp = (smoothPct / 100f * 0.35f).coerceAtLeast(0.004f)

        val raw = when {
            shifted >= lo && shifted <= hi -> 1f
            shifted < lo -> (1f - (lo - shifted) / ramp).coerceIn(0f, 1f)
            else -> (1f - (shifted - hi) / ramp).coerceIn(0f, 1f)
        }
        // Smoothstep easing
        val eased = raw * raw * (3f - 2f * raw)
        return (eased * mixFraction).coerceIn(0f, 1f)
    }

    fun rasterizeTextLayerBitmap(
        context: Context,
        layer: Layer,
        animators: List<EvaluatedTextAnimator>
    ): Bitmap {
        val rawText = layer.textContent.ifBlank { "Text" }
        val lines = rawText.split('\n')
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 76f
            typeface = CustomFontRegistry.resolveTypeface(context, layer.fontFamilyName)
            textAlign = Paint.Align.LEFT
        }

        val maxTrackingAbs = animators.sumOf { abs(it.trackingPx).toDouble() }.toFloat()
        val maxOffsetX = animators.sumOf { abs(it.posX).toDouble() }.toFloat()
        val maxOffsetY = animators.sumOf { abs(it.posY).toDouble() }.toFloat()
        val maxScaleMult = animators.fold(1f) { acc, a -> max(acc, a.scalePct / 100f) }.coerceIn(1f, 3.2f)

        val borderMargin = (if (layer.strokeEnabled) layer.strokeWidth * 2.2f else 0f) +
            (if (layer.shadowEnabled) layer.shadowBlur * 1.6f + max(abs(layer.shadowOffsetX), abs(layer.shadowOffsetY)) else 0f)

        val lineHeight = 92f
        val widestLinePx = lines.maxOfOrNull { ln ->
            paint.measureText(ln) + maxTrackingAbs * (ln.length + 1)
        } ?: 180f

        val padX = (64f + borderMargin + maxOffsetX * 0.85f + 40f * (maxScaleMult - 1f)).coerceIn(56f, 480f)
        val padY = (56f + borderMargin + maxOffsetY * 0.85f + 44f * (maxScaleMult - 1f)).coerceIn(48f, 380f)

        val bmpW = (widestLinePx + padX * 2f).roundToInt().coerceIn(192, 1792)
        val bmpH = (lines.size * lineHeight + padY * 2f).roundToInt().coerceIn(180, 1280)
        val bmp = Bitmap.createBitmap(bmpW, bmpH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)

        val fillCol = runCatching { Color.parseColor(layer.colorHex) }.getOrDefault(Color.WHITE)
        val endCol = runCatching { Color.parseColor(layer.gradientEndColorHex) }.getOrDefault(Color.rgb(79, 124, 255))
        val strokeCol = runCatching { Color.parseColor(layer.strokeColorHex) }.getOrDefault(Color.WHITE)
        val shadowCol = runCatching { Color.parseColor(layer.shadowColorHex) }.getOrDefault(Color.BLACK)

        val fillShader: Shader? = when (layer.fillMode) {
            "LINEAR_GRADIENT" -> {
                val rad = Math.toRadians(layer.gradientAngleDeg.toDouble())
                val cx = bmpW * 0.5f
                val cy = bmpH * 0.5f
                val r = bmpW * 0.42f
                LinearGradient(
                    cx - (cos(rad) * r).toFloat(),
                    cy - (sin(rad) * r).toFloat(),
                    cx + (cos(rad) * r).toFloat(),
                    cy + (sin(rad) * r).toFloat(),
                    fillCol,
                    endCol,
                    Shader.TileMode.CLAMP
                )
            }
            "RADIAL_GRADIENT" -> RadialGradient(
                bmpW * 0.5f,
                bmpH * 0.5f,
                (bmpW * 0.45f).coerceAtLeast(24f),
                fillCol,
                endCol,
                Shader.TileMode.CLAMP
            )
            else -> null
        }

        // Pre-index character & word global indices across all lines
        data class GlyphToken(
            val ch: String,
            val lineIdx: Int,
            val charGlobalIdx: Int,
            val wordGlobalIdx: Int,
            val baseAdvance: Float
        )

        val allTokens = ArrayList<GlyphToken>(rawText.length)
        var charCounter = 0
        var wordCounter = 0
        lines.forEachIndexed { lIdx, ln ->
            var inWord = false
            for (i in ln.indices) {
                val c = ln[i]
                val isSpace = c.isWhitespace()
                if (!isSpace && !inWord) {
                    inWord = true
                } else if (isSpace && inWord) {
                    wordCounter++
                    inWord = false
                }
                val adv = paint.measureText(ln, i, i + 1)
                allTokens.add(
                    GlyphToken(
                        ch = c.toString(),
                        lineIdx = lIdx,
                        charGlobalIdx = charCounter++,
                        wordGlobalIdx = wordCounter,
                        baseAdvance = adv
                    )
                )
            }
            if (inWord) wordCounter++
        }
        val totalChars = charCounter.coerceAtLeast(1)
        val totalWords = wordCounter.coerceAtLeast(1)
        val totalLines = lines.size.coerceAtLeast(1)

        val firstBaselineY = (bmpH - (lines.size - 1) * lineHeight) * 0.5f + 24f

        lines.indices.forEach { lIdx ->
            val lineTokens = allTokens.filter { it.lineIdx == lIdx }
            if (lineTokens.isEmpty()) return@forEach

            // Compute per-token effective tracking & transform
            val tokenTracking = FloatArray(lineTokens.size)
            val tokenDx = FloatArray(lineTokens.size)
            val tokenDy = FloatArray(lineTokens.size)
            val tokenScale = FloatArray(lineTokens.size) { 1f }
            val tokenRot = FloatArray(lineTokens.size)
            val tokenSkew = FloatArray(lineTokens.size)
            val tokenAlpha = FloatArray(lineTokens.size) { 1f }

            for (tIdx in lineTokens.indices) {
                val tok = lineTokens[tIdx]
                animators.forEach { anim ->
                    val (uIdx, uTotal) = when (anim.unitMode) {
                        1 -> tok.wordGlobalIdx to totalWords
                        2 -> tok.lineIdx to totalLines
                        else -> tok.charGlobalIdx to totalChars
                    }
                    val w = computeRangeWeight(
                        unitIndex = uIdx,
                        unitCount = uTotal,
                        startPct = anim.startPct,
                        endPct = anim.endPct,
                        phasePct = anim.phasePct,
                        smoothPct = anim.smoothPct,
                        mixFraction = anim.mixFraction
                    )
                    if (w > 0.001f) {
                        tokenTracking[tIdx] += anim.trackingPx * w
                        tokenDx[tIdx] += anim.posX * w
                        tokenDy[tIdx] += anim.posY * w
                        tokenScale[tIdx] *= (1f + ((anim.scalePct / 100f) - 1f) * w).coerceIn(0f, 5f)
                        tokenRot[tIdx] += anim.rotDeg * w
                        tokenSkew[tIdx] += anim.lineSkewDeg * w
                        tokenAlpha[tIdx] *= (1f + ((anim.opacityPct / 100f) - 1f) * w).coerceIn(0f, 1f)
                    }
                }
            }

            val totalLineW = lineTokens.indices.sumOf { i ->
                (lineTokens[i].baseAdvance + (if (i < lineTokens.lastIndex) tokenTracking[i] else 0f)).toDouble()
            }.toFloat().coerceAtLeast(1f)

            var cursorX = (bmpW - totalLineW) * 0.5f
            val baselineY = firstBaselineY + lIdx * lineHeight

            for (tIdx in lineTokens.indices) {
                val tok = lineTokens[tIdx]
                val alphaInt = (tokenAlpha[tIdx] * 255f).roundToInt().coerceIn(0, 255)
                if (tok.ch.isNotBlank() && alphaInt > 1 && tokenScale[tIdx] > 0.005f) {
                    val glyphCenterX = cursorX + tok.baseAdvance * 0.5f + tokenDx[tIdx]
                    val glyphCenterY = baselineY - 24f + tokenDy[tIdx]

                    canvas.save()
                    canvas.translate(glyphCenterX, glyphCenterY)
                    if (abs(tokenRot[tIdx]) > 0.05f) {
                        canvas.rotate(tokenRot[tIdx])
                    }
                    if (abs(tokenSkew[tIdx]) > 0.05f) {
                        canvas.skew(tanDeg(tokenSkew[tIdx]) * 0.45f, 0f)
                    }
                    if (abs(tokenScale[tIdx] - 1f) > 0.005f) {
                        canvas.scale(tokenScale[tIdx], tokenScale[tIdx])
                    }

                    val localX = -tok.baseAdvance * 0.5f
                    val localBaselineY = 24f

                    drawStyledGlyphOrText(
                        canvas = canvas,
                        text = tok.ch,
                        x = localX,
                        y = localBaselineY,
                        paint = paint,
                        fillCol = fillCol,
                        fillShader = fillShader,
                        alphaInt = alphaInt,
                        layer = layer,
                        strokeCol = strokeCol,
                        shadowCol = shadowCol
                    )
                    canvas.restore()
                }
                cursorX += tok.baseAdvance + tokenTracking[tIdx]
            }
        }

        paint.shader = null
        paint.clearShadowLayer()
        return bmp
    }

    private fun drawStyledGlyphOrText(
        canvas: Canvas,
        text: String,
        x: Float,
        y: Float,
        paint: Paint,
        fillCol: Int,
        fillShader: Shader?,
        alphaInt: Int,
        layer: Layer,
        strokeCol: Int,
        shadowCol: Int
    ) {
        val hasStroke = layer.strokeEnabled && layer.strokeWidth > 0.4f
        val strokePos = layer.strokePosition.uppercase()
        val sw = layer.strokeWidth.coerceIn(0.5f, 48f)

        // 1. Optional Drop Shadow Pass (drawn first so stroke & fill composite cleanly above shadow)
        if (layer.shadowEnabled) {
            paint.shader = null
            paint.style = if (hasStroke && strokePos == "OUTSIDE") Paint.Style.FILL_AND_STROKE else Paint.Style.FILL
            paint.strokeWidth = if (hasStroke && strokePos == "OUTSIDE") sw * 2f else 0f
            paint.color = fillCol
            paint.alpha = alphaInt
            val effShadowCol = Color.argb(
                ((Color.alpha(shadowCol) / 255f) * (alphaInt / 255f) * 255f).roundToInt().coerceIn(0, 255),
                Color.red(shadowCol),
                Color.green(shadowCol),
                Color.blue(shadowCol)
            )
            paint.setShadowLayer(
                layer.shadowBlur.coerceIn(0.5f, 56f),
                layer.shadowOffsetX,
                layer.shadowOffsetY,
                effShadowCol
            )
            canvas.drawText(text, x, y, paint)
            paint.clearShadowLayer()
        }

        // 2. Outside Stroke Pass (drawn behind fill at 2x strokeWidth so outer edge = sw)
        if (hasStroke && strokePos == "OUTSIDE") {
            paint.shader = null
            paint.style = Paint.Style.STROKE
            paint.strokeJoin = Paint.Join.ROUND
            paint.strokeCap = Paint.Cap.ROUND
            paint.strokeWidth = sw * 2f
            paint.color = strokeCol
            paint.alpha = alphaInt
            canvas.drawText(text, x, y, paint)
        }

        // 3. Main Fill Pass
        paint.style = Paint.Style.FILL
        paint.color = fillCol
        paint.shader = fillShader
        paint.alpha = alphaInt
        canvas.drawText(text, x, y, paint)
        paint.shader = null

        // 4. Center or Inside Stroke Pass
        if (hasStroke && strokePos != "OUTSIDE") {
            if (strokePos == "INSIDE") {
                val glyphPath = Path()
                paint.getTextPath(text, 0, text.length, x, y, glyphPath)
                canvas.save()
                canvas.clipPath(glyphPath)
                paint.style = Paint.Style.STROKE
                paint.strokeJoin = Paint.Join.ROUND
                paint.strokeWidth = sw * 2f
                paint.color = strokeCol
                paint.alpha = alphaInt
                canvas.drawPath(glyphPath, paint)
                canvas.restore()
            } else {
                paint.style = Paint.Style.STROKE
                paint.strokeJoin = Paint.Join.ROUND
                paint.strokeWidth = sw
                paint.color = strokeCol
                paint.alpha = alphaInt
                canvas.drawText(text, x, y, paint)
            }
        }
    }

    fun buildShapeCacheSignature(layer: Layer): String =
        "shape2|${layer.shapeKind}|${layer.colorHex}|${layer.fillMode}|" +
            "${layer.gradientEndColorHex}|${layer.gradientAngleDeg.toInt()}|" +
            "${layer.strokeEnabled}|${(layer.strokeWidth * 2f).toInt()}|${layer.strokeColorHex}|${layer.strokePosition}|" +
            "${layer.shadowEnabled}|${(layer.shadowBlur * 2f).toInt()}|${layer.shadowOffsetX.toInt()}|${layer.shadowOffsetY.toInt()}|${layer.shadowColorHex}"

    fun rasterizeShapeLayerBitmap(layer: Layer): Bitmap {
        val sz = 448
        val bmp = Bitmap.createBitmap(sz, sz, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.translate(sz * 0.5f, sz * 0.5f)
        val half = sz * 0.31f

        val path = buildShapePath(layer.shapeKind, half)
        val fillCol = runCatching { Color.parseColor(layer.colorHex) }.getOrDefault(Color.rgb(79, 124, 255))
        val endCol = runCatching { Color.parseColor(layer.gradientEndColorHex) }.getOrDefault(Color.rgb(56, 189, 248))
        val strokeCol = runCatching { Color.parseColor(layer.strokeColorHex) }.getOrDefault(Color.WHITE)
        val shadowCol = runCatching { Color.parseColor(layer.shadowColorHex) }.getOrDefault(Color.BLACK)

        val paintFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = fillCol
            shader = when (layer.fillMode) {
                "LINEAR_GRADIENT" -> {
                    val rad = Math.toRadians(layer.gradientAngleDeg.toDouble())
                    val dx = (cos(rad) * half).toFloat()
                    val dy = (sin(rad) * half).toFloat()
                    LinearGradient(-dx, -dy, dx, dy, fillCol, endCol, Shader.TileMode.CLAMP)
                }
                "RADIAL_GRADIENT" -> RadialGradient(0f, 0f, half.coerceAtLeast(8f), fillCol, endCol, Shader.TileMode.CLAMP)
                else -> null
            }
        }
        val paintStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeJoin = Paint.Join.ROUND
            strokeCap = Paint.Cap.ROUND
            color = strokeCol
        }

        val hasStroke = layer.strokeEnabled && layer.strokeWidth > 0.4f
        val sw = (layer.strokeWidth * 1.8f).coerceIn(1.5f, 64f)
        val strokePos = layer.strokePosition.uppercase()

        if (layer.shadowEnabled) {
            val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = if (hasStroke && strokePos == "OUTSIDE") Paint.Style.FILL_AND_STROKE else Paint.Style.FILL
                strokeWidth = if (hasStroke && strokePos == "OUTSIDE") sw * 2f else 0f
                color = fillCol
                setShadowLayer(
                    layer.shadowBlur.coerceIn(1f, 56f),
                    layer.shadowOffsetX,
                    layer.shadowOffsetY,
                    shadowCol
                )
            }
            c.drawPath(path, shadowPaint)
        }

        if (hasStroke && strokePos == "OUTSIDE") {
            paintStroke.strokeWidth = sw * 2f
            c.drawPath(path, paintStroke)
        }

        c.drawPath(path, paintFill)

        if (hasStroke && strokePos != "OUTSIDE") {
            if (strokePos == "INSIDE") {
                c.save()
                c.clipPath(path)
                paintStroke.strokeWidth = sw * 2f
                c.drawPath(path, paintStroke)
                c.restore()
            } else {
                paintStroke.strokeWidth = sw
                c.drawPath(path, paintStroke)
            }
        }

        return bmp
    }

    fun buildMediaBorderSignature(layer: Layer, aspectWOverH: Float): String {
        val aspBucket = (aspectWOverH * 100f).roundToInt()
        return "mborder|$aspBucket|${layer.strokeEnabled}:${(layer.strokeWidth * 2f).toInt()}:${layer.strokeColorHex}:${layer.strokePosition}|" +
            "${layer.shadowEnabled}:${(layer.shadowBlur * 2f).toInt()}:${layer.shadowOffsetX.toInt()}:${layer.shadowOffsetY.toInt()}:${layer.shadowColorHex}"
    }

    /**
     * Generates a cached frame bitmap containing Drop Shadow (for underlay) or Stroke Border (for overlay)
     * around rectangular `IMAGE` / `VIDEO` layers, padded by `padRatio = 0.25f` on each side so outer
     * strokes and offset shadows never clip!
     * Quad half-size multiplier when drawing this texture = `1.25f`.
     */
    const val MEDIA_BORDER_PAD_SCALE = 1.25f

    fun rasterizeMediaShadowUnderlayBitmap(layer: Layer, aspectWOverH: Float): Bitmap {
        val safeAspect = aspectWOverH.coerceIn(0.25f, 4.0f)
        val innerW = if (safeAspect >= 1f) 360f else 360f * safeAspect
        val innerH = if (safeAspect >= 1f) 360f / safeAspect else 360f
        val bmpW = (innerW * MEDIA_BORDER_PAD_SCALE).roundToInt().coerceAtLeast(64)
        val bmpH = (innerH * MEDIA_BORDER_PAD_SCALE).roundToInt().coerceAtLeast(64)

        val bmp = Bitmap.createBitmap(bmpW, bmpH, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.translate(bmpW * 0.5f, bmpH * 0.5f)
        val rect = RectF(-innerW * 0.5f, -innerH * 0.5f, innerW * 0.5f, innerH * 0.5f)

        if (layer.shadowEnabled) {
            val shadowCol = runCatching { Color.parseColor(layer.shadowColorHex) }.getOrDefault(Color.BLACK)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.FILL
                color = shadowCol
                setShadowLayer(
                    (layer.shadowBlur * 0.85f).coerceIn(1f, 44f),
                    layer.shadowOffsetX * 0.55f,
                    layer.shadowOffsetY * 0.55f,
                    shadowCol
                )
            }
            c.drawRect(rect, paint)
        }
        return bmp
    }

    fun rasterizeMediaStrokeOverlayBitmap(layer: Layer, aspectWOverH: Float): Bitmap {
        val safeAspect = aspectWOverH.coerceIn(0.25f, 4.0f)
        val innerW = if (safeAspect >= 1f) 420f else 420f * safeAspect
        val innerH = if (safeAspect >= 1f) 420f / safeAspect else 420f
        val bmpW = (innerW * MEDIA_BORDER_PAD_SCALE).roundToInt().coerceAtLeast(64)
        val bmpH = (innerH * MEDIA_BORDER_PAD_SCALE).roundToInt().coerceAtLeast(64)

        val bmp = Bitmap.createBitmap(bmpW, bmpH, Bitmap.Config.ARGB_8888)
        if (!layer.strokeEnabled || layer.strokeWidth <= 0.4f) return bmp

        val c = Canvas(bmp)
        c.translate(bmpW * 0.5f, bmpH * 0.5f)
        val strokeCol = runCatching { Color.parseColor(layer.strokeColorHex) }.getOrDefault(Color.WHITE)
        val sw = (layer.strokeWidth * 1.15f).coerceIn(1.5f, 44f)
        val inset = when (layer.strokePosition.uppercase()) {
            "OUTSIDE" -> -sw * 0.5f
            "INSIDE" -> sw * 0.5f
            else -> 0f
        }
        val rect = RectF(
            -innerW * 0.5f + inset,
            -innerH * 0.5f + inset,
            innerW * 0.5f - inset,
            innerH * 0.5f - inset
        )
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeJoin = Paint.Join.MITER
            strokeWidth = sw
            color = strokeCol
        }
        c.drawRect(rect, paint)
        return bmp
    }

    fun buildShapePath(shapeKind: String, half: Float): Path {
        val path = Path()
        when (shapeKind) {
            "Circle" -> path.addCircle(0f, 0f, half, Path.Direction.CW)
            "Rectangle" -> path.addRect(RectF(-half, -half * 0.75f, half, half * 0.75f), Path.Direction.CW)
            "Star" -> return FramePollutionGlRenderer.buildPolygonOrStarPath(5, half, half * 0.44f)
            "Triangle" -> return FramePollutionGlRenderer.buildPolygonOrStarPath(3, half, null)
            "Hexagon", "Polygon" -> return FramePollutionGlRenderer.buildPolygonOrStarPath(6, half, null)
            "Diamond" -> return FramePollutionGlRenderer.buildPolygonOrStarPath(4, half, null)
            "Heart" -> {
                path.moveTo(0f, half * 0.82f)
                path.cubicTo(-half * 1.15f, half * 0.15f, -half * 0.95f, -half * 0.78f, 0f, -half * 0.28f)
                path.cubicTo(half * 0.95f, -half * 0.78f, half * 1.15f, half * 0.15f, 0f, half * 0.82f)
                path.close()
            }
            else -> path.addRoundRect(RectF(-half, -half * 0.75f, half, half * 0.75f), 34f, 34f, Path.Direction.CW)
        }
        return path
    }

    fun buildDrawingLayerSignature(layer: Layer, startPct: Float, endPct: Float, maskSig: String = ""): String {
        val ptsHash = layer.drawingPoints.hashCode()
        val sBucket = (startPct * 5f).roundToInt()
        val eBucket = (endPct * 5f).roundToInt()
        return "draw|$ptsHash|$sBucket:$eBucket|${layer.colorHex}|${layer.strokeEnabled}:${(layer.strokeWidth * 2f).toInt()}:${layer.strokeColorHex}|" +
            "${layer.shadowEnabled}:${layer.shadowBlur.toInt()}:$maskSig"
    }

    fun evaluateDrawingProgressRange(layer: Layer, timeMs: Long, audio: AudioSpectrumSnapshot): Pair<Float, Float> {
        val progFx = layer.effects.firstOrNull {
            it.enabled && (it.effectId.equals("com.alightcreative.effects.drawing.progress", ignoreCase = true) ||
                it.effectId.equals("Drawing_Progress", ignoreCase = true))
        } ?: return 0f to 100f
        val startProp = progFx.customParams["start"] ?: progFx.primaryProp
        val endProp = progFx.customParams["end"] ?: progFx.secondaryProp
        val startVal = AnimationEvaluator.evaluateProperty(startProp, timeMs, audio)
        val endVal = AnimationEvaluator.evaluateProperty(endProp, timeMs, audio)
        val startPct = if (startVal in 0f..1.001f && endVal in 0f..1.001f && (startProp.keyframes.isNotEmpty() || endProp.keyframes.isNotEmpty())) {
            startVal * 100f
        } else {
            startVal
        }
        val endPct = if (startVal in 0f..1.001f && endVal in 0f..1.001f && (startProp.keyframes.isNotEmpty() || endProp.keyframes.isNotEmpty())) {
            endVal * 100f
        } else {
            endVal
        }
        return startPct.coerceIn(0f, 100f) to endPct.coerceIn(0f, 100f)
    }

    fun rasterizeDrawingLayerBitmap(
        layer: Layer,
        startPct: Float = 0f,
        endPct: Float = 100f,
        canvasSize: Int = 512
    ): Bitmap {
        val sz = canvasSize.coerceIn(128, 1024)
        val bmp = Bitmap.createBitmap(sz, sz, Bitmap.Config.ARGB_8888)
        val pts = layer.drawingPoints
        if (pts.isEmpty()) return bmp

        val c = Canvas(bmp)
        c.translate(sz * 0.5f, sz * 0.5f)
        val scale = sz / 1080f

        val rawPath = Path()
        pts.forEachIndexed { idx, pt ->
            val px = pt.x * scale
            val py = pt.y * scale
            if (idx == 0) {
                rawPath.moveTo(px, py)
            } else {
                val prev = pts[idx - 1]
                if (prev.outX != 0f || prev.outY != 0f || pt.inX != 0f || pt.inY != 0f) {
                    rawPath.cubicTo(
                        (prev.x + prev.outX) * scale,
                        (prev.y + prev.outY) * scale,
                        (pt.x + pt.inX) * scale,
                        (pt.y + pt.inY) * scale,
                        px,
                        py
                    )
                } else {
                    rawPath.lineTo(px, py)
                }
            }
        }
        if (layer.drawingClosed && pts.size >= 3) {
            rawPath.close()
        }

        val drawPath = if (startPct > 0.05f || endPct < 99.95f) {
            val out = Path()
            val pm = android.graphics.PathMeasure(rawPath, false)
            var totalLen = 0f
            do {
                totalLen += pm.length
            } while (pm.nextContour())
            if (totalLen > 0.5f) {
                val lo = (min(startPct, endPct) / 100f) * totalLen
                val hi = (max(startPct, endPct) / 100f) * totalLen
                val pm2 = android.graphics.PathMeasure(rawPath, false)
                var acc = 0f
                do {
                    val segLen = pm2.length
                    val segStart = (lo - acc).coerceIn(0f, segLen)
                    val segEnd = (hi - acc).coerceIn(0f, segLen)
                    if (segEnd > segStart + 0.1f) {
                        pm2.getSegment(segStart, segEnd, out, true)
                    }
                    acc += segLen
                } while (pm2.nextContour())
            }
            out
        } else {
            rawPath
        }

        val strokeCol = runCatching {
            Color.parseColor(if (layer.strokeEnabled) layer.strokeColorHex else layer.colorHex)
        }.getOrDefault(Color.WHITE)

        if (layer.drawingClosed && layer.fillMode != "NONE") {
            val fillCol = runCatching { Color.parseColor(layer.colorHex) }.getOrDefault(strokeCol)
            val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.FILL
                color = fillCol
            }
            c.drawPath(drawPath, fillPaint)
        }

        val sw = ((if (layer.strokeWidth > 0.2f) layer.strokeWidth else 10f) * scale * 2f).coerceIn(2f, 72f)
        val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            strokeWidth = sw
            color = strokeCol
            if (layer.shadowEnabled) {
                val shad = runCatching { Color.parseColor(layer.shadowColorHex) }.getOrDefault(Color.BLACK)
                setShadowLayer(
                    (layer.shadowBlur * scale).coerceIn(1f, 36f),
                    layer.shadowOffsetX * scale,
                    layer.shadowOffsetY * scale,
                    shad
                )
            }
        }
        c.drawPath(drawPath, strokePaint)
        return bmp
    }

    fun buildMaskSignature(layer: Layer, timeMs: Long): String {
        if (layer.masks.isEmpty()) return ""
        val sb = StringBuilder(64)
        layer.masks.forEach { m ->
            if (!m.enabled) return@forEach
            val frameBucket = if (m.rotoFrameMasksPngBase64.isNotEmpty()) (timeMs / 33L) else 0L
            sb.append("|mk:").append(m.id).append(':').append(m.type.name).append(':')
                .append(m.inverted).append(':').append(m.feather.toInt()).append(':')
                .append(m.expansion.toInt()).append(':').append((m.opacity * 100f).toInt()).append(':')
                .append(m.points.hashCode()).append(':').append(frameBucket)
        }
        return sb.toString()
    }

    fun applyLayerMasksInPlace(
        bmp: Bitmap,
        layer: Layer,
        timeMs: Long,
        audio: AudioSpectrumSnapshot
    ) {
        val activeMasks = layer.masks.filter { it.enabled }
        if (activeMasks.isEmpty() || bmp.isRecycled) return
        val w = bmp.width.toFloat().coerceAtLeast(1f)
        val h = bmp.height.toFloat().coerceAtLeast(1f)
        val maskBmp = Bitmap.createBitmap(bmp.width, bmp.height, Bitmap.Config.ARGB_8888)
        val mc = Canvas(maskBmp)

        activeMasks.forEach { mask ->
            val featherVal = AnimationEvaluator.evaluateProperty(mask.featherProp, timeMs, audio)
            val expansionVal = AnimationEvaluator.evaluateProperty(mask.expansionProp, timeMs, audio)
            val opacityVal = (AnimationEvaluator.evaluateProperty(mask.opacityProp, timeMs, audio) / 100f)
                .coerceIn(0f, 1f)

            val rotoBase64 = if (mask.type == com.example.data.model.MaskShapeType.ROTO && mask.rotoFrameMasksPngBase64.isNotEmpty()) {
                mask.rotoFrameMasksPngBase64[timeMs]
                    ?: mask.rotoFrameMasksPngBase64.entries.minByOrNull { kotlin.math.abs(it.key - timeMs) }?.value
            } else null

            if (rotoBase64 != null) {
                val decodedBytes = runCatching { android.util.Base64.decode(rotoBase64, android.util.Base64.DEFAULT) }.getOrNull()
                val rotoBmp = decodedBytes?.let { bytes ->
                    runCatching { android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size) }.getOrNull()
                }
                if (rotoBmp != null) {
                    val p = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
                        alpha = (opacityVal * 255f).roundToInt().coerceIn(0, 255)
                        if (featherVal > 0.5f) {
                            maskFilter = android.graphics.BlurMaskFilter(featherVal.coerceIn(0.5f, 64f), android.graphics.BlurMaskFilter.Blur.NORMAL)
                        }
                    }
                    mc.drawBitmap(rotoBmp, null, RectF(0f, 0f, w, h), p)
                    rotoBmp.recycle()
                }
            } else {
                val expPx = (expansionVal / 100f) * min(w, h) * 0.25f
                val path = Path()
                when (mask.type) {
                    com.example.data.model.MaskShapeType.RECTANGLE -> {
                        val insetX = w * 0.12f - expPx
                        val insetY = h * 0.12f - expPx
                        path.addRect(
                            RectF(
                                insetX.coerceAtMost(w * 0.48f),
                                insetY.coerceAtMost(h * 0.48f),
                                (w - insetX).coerceAtLeast(w * 0.52f),
                                (h - insetY).coerceAtLeast(h * 0.52f)
                            ),
                            Path.Direction.CW
                        )
                    }
                    com.example.data.model.MaskShapeType.ELLIPSE -> {
                        val insetX = w * 0.12f - expPx
                        val insetY = h * 0.12f - expPx
                        path.addOval(
                            RectF(
                                insetX.coerceAtMost(w * 0.48f),
                                insetY.coerceAtMost(h * 0.48f),
                                (w - insetX).coerceAtLeast(w * 0.52f),
                                (h - insetY).coerceAtLeast(h * 0.52f)
                            ),
                            Path.Direction.CW
                        )
                    }
                    com.example.data.model.MaskShapeType.PEN,
                    com.example.data.model.MaskShapeType.ROTO -> {
                        val frameContour = if (mask.type == com.example.data.model.MaskShapeType.ROTO && mask.rotoContourByFrameMs.isNotEmpty()) {
                            mask.rotoContourByFrameMs[timeMs]
                                ?: mask.rotoContourByFrameMs.entries.minByOrNull { kotlin.math.abs(it.key - timeMs) }?.value
                        } else null
                        if (frameContour != null && frameContour.size >= 3) {
                            frameContour.forEachIndexed { idx, (nx, ny) ->
                                val px = nx * w
                                val py = ny * h
                                if (idx == 0) path.moveTo(px, py) else path.lineTo(px, py)
                            }
                            path.close()
                        } else if (mask.points.size >= 3) {
                            mask.points.forEachIndexed { idx, pt ->
                                val nx = if (pt.x in -1.5f..1.5f && pt.y in -1.5f..1.5f) pt.x else (pt.x / 1080f + 0.5f)
                                val ny = if (pt.x in -1.5f..1.5f && pt.y in -1.5f..1.5f) pt.y else (pt.y / 1080f + 0.5f)
                                val px = nx * w
                                val py = ny * h
                                if (idx == 0) path.moveTo(px, py) else path.lineTo(px, py)
                            }
                            if (mask.closed) path.close()
                        } else {
                            path.addRect(RectF(0f, 0f, w, h), Path.Direction.CW)
                        }
                    }
                }
                val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    style = Paint.Style.FILL
                    color = Color.argb((opacityVal * 255f).roundToInt().coerceIn(0, 255), 255, 255, 255)
                    if (featherVal > 0.5f) {
                        maskFilter = android.graphics.BlurMaskFilter(
                            (featherVal * (min(w, h) / 360f)).coerceIn(0.5f, 72f),
                            android.graphics.BlurMaskFilter.Blur.NORMAL
                        )
                    }
                }
                mc.drawPath(path, p)
            }

            val dstCanvas = Canvas(bmp)
            val xferPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                xfermode = android.graphics.PorterDuffXfermode(
                    if (mask.inverted) android.graphics.PorterDuff.Mode.DST_OUT
                    else android.graphics.PorterDuff.Mode.DST_IN
                )
            }
            dstCanvas.drawBitmap(maskBmp, 0f, 0f, xferPaint)
            mc.drawColor(Color.TRANSPARENT, android.graphics.PorterDuff.Mode.CLEAR)
        }
        maskBmp.recycle()
    }

    private fun tanDeg(deg: Float): Float =
        kotlin.math.tan(Math.toRadians(deg.toDouble())).toFloat()
}
