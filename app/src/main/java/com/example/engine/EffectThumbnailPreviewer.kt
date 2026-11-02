package com.example.engine

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.example.data.model.Layer
import com.example.data.model.LayerType
import com.example.data.model.Primitive3DType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Ultra-fast, low-resolution (`56 × 56` px) Effect Thumbnail Previewer for [EffectsBrowserScreen]:
 * - Renders a tiny raster swatch of the user's **currently selected Layer** (its actual Video/Image frame,
 *   Shape geometry & color, Kinetic Text, or 3D Mesh — or a crisp stylized reference prism if no layer is selected)
 * - Applies a fast, zero-GL-context 56×56 pixel kernel matching each [EffectDefinition]'s exact shader mode & ID
 * - Caches resulting [ImageBitmap]s in a tiny ~4 MB [LruCache] so scrolling 130+ effects stays locked at 60 FPS!
 */
object EffectThumbnailPreviewer {

    const val THUMB_SIZE = 56
    private const val PIXEL_COUNT = THUMB_SIZE * THUMB_SIZE

    // Bounded worker concurrency so scrolling fast never spawns excessive coroutines
    private val workerSemaphore = Semaphore(permits = 4)

    private val thumbCache = object : LruCache<String, ImageBitmap>(220) {
        override fun sizeOf(key: String, value: ImageBitmap): Int = 1
    }

    private val basePixelsCache = object : LruCache<String, IntArray>(12) {
        override fun sizeOf(key: String, value: IntArray): Int = 1
    }

    fun layerSignature(
        layer: Layer?,
        playheadMs: Long = 0L,
        audio: AudioSpectrumSnapshot = AudioSpectrumSnapshot()
    ): String {
        if (layer == null) return "default_sample_layer"
        val hasDynamicFrame = layer.type == LayerType.VIDEO ||
            layer.posX.keyframes.isNotEmpty() ||
            layer.posY.keyframes.isNotEmpty() ||
            layer.scale.keyframes.isNotEmpty() ||
            layer.rotationZ.keyframes.isNotEmpty() ||
            layer.opacity.keyframes.isNotEmpty() ||
            layer.timeRemapEnabled
        val frameBucket = if (hasDynamicFrame) {
            val srcMs = if (layer.type == LayerType.VIDEO) {
                AnimationEvaluator.evaluateSourceMediaTimeMs(layer, playheadMs, audio)
            } else playheadMs
            srcMs / 100L
        } else 0L

        return "${layer.id}|${layer.type.name}|${layer.shapeKind}|${layer.colorHex}|${layer.fillMode}|" +
            "${layer.gradientEndColorHex}|${layer.gradientAngleDeg.toInt()}|${layer.fontFamilyName}|" +
            "${layer.textContent.take(16)}|${layer.mediaUri}|${layer.primitive3D.name}|" +
            "${layer.strokeEnabled}|${layer.strokeColorHex}|f$frameBucket"
    }

    fun getCachedImmediate(layerSig: String, effectId: String): ImageBitmap? {
        val key = "$layerSig|$effectId"
        return synchronized(thumbCache) { thumbCache.get(key) }
    }

    fun clearThumbnails() {
        synchronized(thumbCache) { thumbCache.evictAll() }
        synchronized(basePixelsCache) { basePixelsCache.evictAll() }
    }

    suspend fun generateLowResEffectThumbnail(
        context: Context?,
        selectedLayer: Layer?,
        effect: EffectDefinition,
        playheadMs: Long = 0L,
        audio: AudioSpectrumSnapshot = AudioSpectrumSnapshot()
    ): ImageBitmap = withContext(Dispatchers.Default) {
        val sig = layerSignature(selectedLayer, playheadMs, audio)
        val cacheKey = "$sig|${effect.id}"
        synchronized(thumbCache) { thumbCache.get(cacheKey) }?.let { return@withContext it }

        workerSemaphore.withPermit {
            synchronized(thumbCache) { thumbCache.get(cacheKey) }?.let { return@withContext it }

            val basePixels = obtainBaseLayerPixels(context, selectedLayer, playheadMs, audio, sig)
            val outPixels = IntArray(PIXEL_COUNT)
            applyLowResEffectKernel(basePixels, outPixels, effect)

            // Compress directly into RGB_565 (2 bytes/px — 50% smaller RAM footprint, zero extra bitmap alloc!)
            val compressed565 = Bitmap.createBitmap(THUMB_SIZE, THUMB_SIZE, Bitmap.Config.RGB_565)
            compressed565.setPixels(outPixels, 0, THUMB_SIZE, 0, 0, THUMB_SIZE, THUMB_SIZE)
            val outBmp = compressed565.asImageBitmap()
            synchronized(thumbCache) {
                thumbCache.put(cacheKey, outBmp)
            }
            outBmp
        }
    }

    /**
     * Synchronous helper for unit/Robolectric tests and immediate single-card previews.
     * Returns an `RGB_565` compressed `56x56` Bitmap reflecting the layer at [playheadMs].
     */
    fun generateThumbnailSync(
        context: Context?,
        selectedLayer: Layer?,
        effect: EffectDefinition,
        playheadMs: Long = 0L,
        audio: AudioSpectrumSnapshot = AudioSpectrumSnapshot()
    ): Bitmap {
        val sig = layerSignature(selectedLayer, playheadMs, audio)
        val basePixels = obtainBaseLayerPixels(context, selectedLayer, playheadMs, audio, sig)
        val outPixels = IntArray(PIXEL_COUNT)
        applyLowResEffectKernel(basePixels, outPixels, effect)
        val compressed565 = Bitmap.createBitmap(THUMB_SIZE, THUMB_SIZE, Bitmap.Config.RGB_565)
        compressed565.setPixels(outPixels, 0, THUMB_SIZE, 0, 0, THUMB_SIZE, THUMB_SIZE)
        return compressed565
    }

    private fun obtainBaseLayerPixels(
        context: Context?,
        layer: Layer?,
        playheadMs: Long,
        audio: AudioSpectrumSnapshot,
        sig: String
    ): IntArray {
        synchronized(basePixelsCache) { basePixelsCache.get(sig) }?.let { return it }

        val bmp = Bitmap.createBitmap(THUMB_SIZE, THUMB_SIZE, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val s = THUMB_SIZE.toFloat()

        // Subtle dark studio backdrop grid so warps, glows, and chromatic shifts pop clearly
        c.drawColor(Color.rgb(14, 17, 23))
        val gridPaint = Paint().apply {
            color = Color.rgb(28, 34, 46)
            strokeWidth = 1f
        }
        c.drawLine(s * 0.5f, 0f, s * 0.5f, s, gridPaint)
        c.drawLine(0f, s * 0.5f, s, s * 0.5f, gridPaint)

        val fill = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }

        if (layer == null || layer.type == LayerType.AUDIO || layer.type == LayerType.NULL_OBJECT || layer.type == LayerType.CAMERA) {
            // Default stylized prism subject when no visual layer is selected
            fill.shader = LinearGradient(
                s * 0.2f, s * 0.2f, s * 0.8f, s * 0.8f,
                intArrayOf(Color.rgb(0, 210, 168), Color.rgb(0, 148, 255), Color.rgb(255, 51, 102)),
                null,
                Shader.TileMode.CLAMP
            )
            c.drawRoundRect(RectF(s * 0.20f, s * 0.20f, s * 0.80f, s * 0.80f), 10f, 10f, fill)
            fill.shader = null
            stroke.color = Color.WHITE
            stroke.strokeWidth = 2f
            c.drawCircle(s * 0.5f, s * 0.5f, s * 0.16f, stroke)
        } else {
            val baseCol = runCatching { Color.parseColor(layer.colorHex) }.getOrDefault(Color.rgb(0, 210, 168))
            val endCol = runCatching { Color.parseColor(layer.gradientEndColorHex) }.getOrDefault(Color.rgb(0, 148, 255))
            val tr = AnimationEvaluator.evaluateWorldTransform(
                layer = layer,
                allLayers = listOf(layer),
                timeMs = playheadMs,
                audio = audio
            )
            c.save()
            c.translate(s * 0.5f + (tr.posX / 1080f) * s * 0.45f, s * 0.5f + (tr.posY / 1080f) * s * 0.45f)
            c.rotate(tr.rotZ)
            val thumbScale = tr.scale.coerceIn(0.45f, 1.85f)
            c.scale(thumbScale, thumbScale)

            when (layer.type) {
                LayerType.VIDEO, LayerType.IMAGE -> {
                    val srcTimeMs = if (layer.type == LayerType.VIDEO) {
                        AnimationEvaluator.evaluateSourceMediaTimeMs(layer, playheadMs, audio)
                    } else 0L
                    val mediaBmp = if (context != null) {
                        MediaEngine.getMediaBitmapForPreRender(context, layer, srcTimeMs, 80)
                    } else null
                    if (mediaBmp != null && !mediaBmp.isRecycled) {
                        val aspect = mediaBmp.width.toFloat().coerceAtLeast(1f) / mediaBmp.height.toFloat().coerceAtLeast(1f)
                        val hw = if (aspect >= 1f) s * 0.50f else (s * 0.50f * aspect)
                        val hh = if (aspect >= 1f) (s * 0.50f / aspect) else s * 0.50f
                        c.drawBitmap(mediaBmp, null, RectF(-hw, -hh, hw, hh), fill)
                    } else {
                        fill.color = baseCol
                        c.drawRoundRect(RectF(-s * 0.36f, -s * 0.26f, s * 0.36f, s * 0.26f), 8f, 8f, fill)
                    }
                }
                LayerType.TEXT -> {
                    val tp = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        color = baseCol
                        textAlign = Paint.Align.CENTER
                        textSize = s * 0.30f
                        typeface = CustomFontRegistry.resolveTypeface(context, layer.fontFamilyName)
                        shader = when (layer.fillMode) {
                            "LINEAR_GRADIENT" -> LinearGradient(-s * 0.3f, 0f, s * 0.3f, 0f, baseCol, endCol, Shader.TileMode.CLAMP)
                            "RADIAL_GRADIENT" -> RadialGradient(0f, 0f, s * 0.35f, baseCol, endCol, Shader.TileMode.CLAMP)
                            else -> null
                        }
                    }
                    val snippet = layer.textContent.trim().ifBlank { "Aa" }.take(5)
                    c.drawText(snippet, 0f, s * 0.11f, tp)
                }
                LayerType.MODEL_3D -> {
                    fill.color = baseCol
                    val r = s * 0.28f
                    when (layer.primitive3D) {
                        Primitive3DType.PYRAMID -> c.drawPath(FramePollutionGlRenderer.buildPolygonOrStarPath(3, r, null), fill)
                        Primitive3DType.TORUS -> {
                            stroke.color = baseCol
                            stroke.strokeWidth = 7f
                            c.drawCircle(0f, 0f, r * 0.75f, stroke)
                        }
                        else -> c.drawRoundRect(RectF(-r, -r, r, r), 6f, 6f, fill)
                    }
                    stroke.color = Color.WHITE
                    stroke.strokeWidth = 1.4f
                    c.drawCircle(0f, 0f, r * 0.4f, stroke)
                }
                else -> {
                    val r = s * 0.30f
                    fill.color = baseCol
                    fill.shader = when (layer.fillMode) {
                        "LINEAR_GRADIENT" -> {
                            val rad = Math.toRadians(layer.gradientAngleDeg.toDouble())
                            LinearGradient(
                                -(cos(rad) * r).toFloat(),
                                -(sin(rad) * r).toFloat(),
                                (cos(rad) * r).toFloat(),
                                (sin(rad) * r).toFloat(),
                                baseCol,
                                endCol,
                                Shader.TileMode.CLAMP
                            )
                        }
                        "RADIAL_GRADIENT" -> RadialGradient(0f, 0f, r, baseCol, endCol, Shader.TileMode.CLAMP)
                        else -> null
                    }
                    when (layer.shapeKind) {
                        "Circle" -> c.drawCircle(0f, 0f, r, fill)
                        "Rectangle" -> c.drawRect(RectF(-r, -r * 0.75f, r, r * 0.75f), fill)
                        "Star" -> c.drawPath(FramePollutionGlRenderer.buildPolygonOrStarPath(5, r, r * 0.44f), fill)
                        "Triangle" -> c.drawPath(FramePollutionGlRenderer.buildPolygonOrStarPath(3, r, null), fill)
                        "Hexagon" -> c.drawPath(FramePollutionGlRenderer.buildPolygonOrStarPath(6, r, null), fill)
                        else -> c.drawRoundRect(RectF(-r, -r * 0.75f, r, r * 0.75f), 7f, 7f, fill)
                    }
                    fill.shader = null
                    if (layer.strokeEnabled) {
                        stroke.color = runCatching { Color.parseColor(layer.strokeColorHex) }.getOrDefault(Color.WHITE)
                        stroke.strokeWidth = 2f
                        c.drawCircle(0f, 0f, r * 0.9f, stroke)
                    }
                }
            }
            c.restore()
        }

        val pixels = IntArray(PIXEL_COUNT)
        bmp.getPixels(pixels, 0, THUMB_SIZE, 0, 0, THUMB_SIZE, THUMB_SIZE)
        bmp.recycle()
        synchronized(basePixelsCache) {
            basePixelsCache.put(sig, pixels)
        }
        return pixels
    }

    private fun applyLowResEffectKernel(
        src: IntArray,
        dst: IntArray,
        effect: EffectDefinition
    ) {
        val n = THUMB_SIZE
        val idHash = abs(effect.id.hashCode() % 97) / 97f
        val mode = effect.shaderMode
        val strength = effect.defaultIntensity.coerceIn(0.25f, 1.5f)

        for (y in 0 until n) {
            val v = y.toFloat() / (n - 1)
            for (x in 0 until n) {
                val u = x.toFloat() / (n - 1)
                val idx = y * n + x
                val base = src[idx]
                val br = (base ushr 16) and 0xFF
                val bg = (base ushr 8) and 0xFF
                val bb = base and 0xFF

                when (mode) {
                    1 -> { // Optical Glow / Bloom / Rays
                        val rad = (2 + (strength * 3f).toInt()).coerceIn(2, 5)
                        val c1 = sampleClamped(src, n, x - rad, y)
                        val c2 = sampleClamped(src, n, x + rad, y)
                        val c3 = sampleClamped(src, n, x, y - rad)
                        val c4 = sampleClamped(src, n, x, y + rad)
                        val glowR = (((c1 ushr 16 and 0xFF) + (c2 ushr 16 and 0xFF) + (c3 ushr 16 and 0xFF) + (c4 ushr 16 and 0xFF)) * 0.34f * strength).toInt()
                        val glowG = (((c1 ushr 8 and 0xFF) + (c2 ushr 8 and 0xFF) + (c3 ushr 8 and 0xFF) + (c4 ushr 8 and 0xFF)) * 0.36f * strength).toInt()
                        val glowB = (((c1 and 0xFF) + (c2 and 0xFF) + (c3 and 0xFF) + (c4 and 0xFF)) * 0.38f * strength).toInt()
                        dst[idx] = packRgb(br + glowR, bg + glowG, bb + glowB)
                    }
                    2 -> { // Chromatic Aberration / RGB Split
                        val shift = (3 + (strength * 4f).toInt()).coerceIn(2, 7)
                        val rSample = sampleClamped(src, n, x + shift, y)
                        val bSample = sampleClamped(src, n, x - shift, y + (if (idHash > 0.5f) 1 else 0))
                        dst[idx] = packRgb((rSample ushr 16) and 0xFF, bg, bSample and 0xFF)
                    }
                    3 -> { // Wave / Turbulent / Polar Warp
                        val freq = 8f + idHash * 10f
                        val dx = (sin(v * freq + idHash * 6f) * 5.2f * strength).roundToInt()
                        val dy = (cos(u * freq) * 4.2f * strength).roundToInt()
                        dst[idx] = sampleClamped(src, n, x + dx, y + dy)
                    }
                    4 -> { // Glitch Scanlines / Block Slice
                        val band = y / 5
                        val sliceShift = if ((band + (idHash * 10).toInt()) % 3 == 0) (6 * strength).toInt() else if (band % 4 == 1) -4 else 0
                        val c = sampleClamped(src, n, x + sliceShift, y)
                        val scan = if (y % 2 == 0) 1.0f else 0.76f
                        dst[idx] = packRgb(
                            (((c ushr 16) and 0xFF) * scan).toInt(),
                            (((c ushr 8) and 0xFF) * scan).toInt(),
                            ((c and 0xFF) * scan).toInt()
                        )
                    }
                    5 -> { // Kaleidoscope / Mirror / Tile Repeat
                        val tiles = if (effect.id.contains("Kaleid", ignoreCase = true) || idHash > 0.45f) 2f else 3f
                        val mu = abs(((u * tiles) % 2f) - 1f)
                        val mv = abs(((v * tiles) % 2f) - 1f)
                        dst[idx] = sampleClamped(src, n, (mu * (n - 1)).roundToInt(), (mv * (n - 1)).roundToInt())
                    }
                    6 -> { // Radial / Zoom / Directional Blur
                        val vx = (0.5f - u) * 0.28f * strength
                        val vy = (0.5f - v) * 0.28f * strength
                        var sr = br
                        var sg = bg
                        var sb = bb
                        for (tap in 1..3) {
                            val sx = (x + vx * tap * n).roundToInt()
                            val sy = (y + vy * tap * n).roundToInt()
                            val sc = sampleClamped(src, n, sx, sy)
                            sr += (sc ushr 16) and 0xFF
                            sg += (sc ushr 8) and 0xFF
                            sb += sc and 0xFF
                        }
                        dst[idx] = packRgb(sr / 4, sg / 4, sb / 4)
                    }
                    7 -> { // Procedural Particles / Sparks
                        val cellHash = (((x / 4) * 73856093) xor ((y / 4) * 19349663) xor effect.id.hashCode()) and 0xFF
                        val isSpark = cellHash > 228 && (x % 4 in 1..2) && (y % 4 in 1..2)
                        if (isSpark) {
                            dst[idx] = packRgb(br + 90, bg + 230, bb + 210)
                        } else {
                            dst[idx] = base
                        }
                    }
                    8 -> { // Edge / Cartoon / Find Edges
                        val cx = sampleClamped(src, n, x + 1, y)
                        val cy = sampleClamped(src, n, x, y + 1)
                        val edge = abs(br - ((cx ushr 16) and 0xFF)) +
                            abs(bg - ((cx ushr 8) and 0xFF)) +
                            abs(br - ((cy ushr 16) and 0xFF)) +
                            abs(bb - (cy and 0xFF))
                        val boost = (edge * 1.8f).toInt().coerceIn(0, 255)
                        dst[idx] = packRgb((br * 0.25f).toInt() + (boost / 3), (bg * 0.25f).toInt() + boost, (bb * 0.25f).toInt() + boost)
                    }
                    9 -> { // Hue / Invert / Color Grade
                        if (effect.id.contains("Invert", ignoreCase = true)) {
                            dst[idx] = packRgb(255 - br, 255 - bg, 255 - bb)
                        } else {
                            // Rotate RGB channels cleanly for distinct color grading preview
                            val mixW = (0.45f + idHash * 0.45f)
                            val nr = (br * (1f - mixW) + bb * mixW * 1.25f).toInt()
                            val ng = (bg * (1f - mixW * 0.5f) + br * mixW * 0.9f).toInt()
                            val nb = (bb * (1f - mixW) + bg * mixW * 1.25f).toInt()
                            dst[idx] = packRgb(nr, ng, nb)
                        }
                    }
                    11 -> { // Text Transform / Range Wipe
                        val colBand = (x * 4) / n
                        val yShift = if (colBand == 1 || colBand == 2) -6 else 0
                        val sampled = sampleClamped(src, n, x, y - yShift)
                        val boost = if (colBand == 1) 1.2f else 0.9f
                        dst[idx] = packRgb(
                            (((sampled ushr 16) and 0xFF) * boost).toInt(),
                            (((sampled ushr 8) and 0xFF) * boost).toInt(),
                            ((sampled and 0xFF) * boost).toInt()
                        )
                    }
                    12 -> { // Chroma Key / Luma Key Checkerboard Preview
                        val isGreenish = bg > (br + 24) && bg > (bb + 18)
                        val isDarkBg = br < 36 && bg < 42 && bb < 52
                        if (isGreenish || isDarkBg) {
                            val chk = (((x / 6) + (y / 6)) % 2) == 0
                            val c = if (chk) 46 else 26
                            dst[idx] = packRgb(c, c + 3, c + 8)
                        } else {
                            dst[idx] = base
                        }
                    }
                    99 -> { // Custom .kfpack / .fppack Plugin Effect Preview
                        val plug = PluginPackManager.findPluginEffect(effect.id)
                        val pCat = plug?.category?.lowercase().orEmpty()
                        val tintCol = runCatching { Color.parseColor(effect.defaultTintHex) }.getOrDefault(Color.rgb(28, 240, 196))
                        val tr = (tintCol ushr 16) and 0xFF
                        val tg = (tintCol ushr 8) and 0xFF
                        val tb = tintCol and 0xFF
                        when {
                            pCat == "warna" || pCat == "blur" -> {
                                val c1 = sampleClamped(src, n, x - 3, y)
                                val c2 = sampleClamped(src, n, x + 3, y)
                                val c3 = sampleClamped(src, n, x, y - 3)
                                val c4 = sampleClamped(src, n, x, y + 3)
                                val lum = (((c1 ushr 16 and 0xFF) + (c2 ushr 16 and 0xFF) + (c3 ushr 8 and 0xFF) + (c4 and 0xFF)) / 1020f)
                                dst[idx] = packRgb(
                                    br + (tr * lum * 0.85f).toInt(),
                                    bg + (tg * lum * 0.85f).toInt(),
                                    bb + (tb * lum * 0.85f).toInt()
                                )
                            }
                            pCat == "distorsi" || pCat == "perspective" -> {
                                val dx = ((u - 0.5f) * 8f).roundToInt()
                                val cr = sampleClamped(src, n, x + dx + 2, y)
                                val cb = sampleClamped(src, n, x - dx - 2, y)
                                dst[idx] = packRgb((cr ushr 16) and 0xFF, bg, cb and 0xFF)
                            }
                            pCat == "stilisasi" -> {
                                val dot = (((x % 5) - 2) * ((x % 5) - 2) + ((y % 5) - 2) * ((y % 5) - 2)) <= 3
                                val lum = (br * 0.3f + bg * 0.59f + bb * 0.11f) / 255f
                                if (dot && lum > 0.22f) {
                                    dst[idx] = packRgb((br * 0.5f + tr * 0.6f).toInt(), (bg * 0.5f + tg * 0.6f).toInt(), (bb * 0.5f + tb * 0.6f).toInt())
                                } else {
                                    dst[idx] = packRgb((br * 0.35f).toInt(), (bg * 0.35f).toInt(), (bb * 0.4f).toInt())
                                }
                            }
                            else -> {
                                // Gerak / Directional Motion Blur Streak
                                val c1 = sampleClamped(src, n, x - 4, y + 1)
                                val c2 = sampleClamped(src, n, x + 4, y - 1)
                                dst[idx] = packRgb(
                                    (br * 2 + ((c1 ushr 16) and 0xFF) + ((c2 ushr 16) and 0xFF)) / 4,
                                    (bg * 2 + ((c1 ushr 8) and 0xFF) + ((c2 ushr 8) and 0xFF)) / 4,
                                    (bb * 2 + (c1 and 0xFF) + (c2 and 0xFF)) / 4
                                )
                            }
                        }
                    }
                    else -> { // Shake / Squeeze / Vignette / Pinch
                        val du = u - 0.5f
                        val dv = v - 0.5f
                        val dist = hypot(du, dv)
                        val pinch = 1f + (0.35f - dist) * 0.75f * strength
                        val sx = ((0.5f + du * pinch) * (n - 1)).roundToInt() + 2
                        val sy = ((0.5f + dv * pinch) * (n - 1)).roundToInt() - 1
                        val sc = sampleClamped(src, n, sx, sy)
                        val vig = (1.12f - dist * 0.85f).coerceIn(0.35f, 1f)
                        dst[idx] = packRgb(
                            (((sc ushr 16) and 0xFF) * vig).toInt(),
                            (((sc ushr 8) and 0xFF) * vig).toInt(),
                            ((sc and 0xFF) * vig).toInt()
                        )
                    }
                }
            }
        }
    }

    private fun sampleClamped(arr: IntArray, n: Int, x: Int, y: Int): Int {
        val cx = x.coerceIn(0, n - 1)
        val cy = y.coerceIn(0, n - 1)
        return arr[cy * n + cx]
    }

    private fun packRgb(r: Int, g: Int, b: Int): Int {
        val cr = r.coerceIn(0, 255)
        val cg = g.coerceIn(0, 255)
        val cb = b.coerceIn(0, 255)
        return (0xFF shl 24) or (cr shl 16) or (cg shl 8) or cb
    }
}
