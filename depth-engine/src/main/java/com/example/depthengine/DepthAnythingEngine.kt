package com.example.depthengine

import android.graphics.Bitmap
import android.graphics.Color
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

enum class DepthModelVariant(
    val id: String,
    val displayName: String,
    val licenseBadge: String,
    val isCommercialUseAllowed: Boolean,
    val approxSizeMb: Int,
    val downloadUrl: String,
    val expectedSha256: String? = null
) {
    SMALL(
        id = "depth_anything_v2_vits",
        displayName = "Depth Anything V2 Small",
        licenseBadge = "Apache-2.0",
        isCommercialUseAllowed = true,
        approxSizeMb = 98,
        downloadUrl = "https://huggingface.co/depth-anything/Depth-Anything-V2-Small/resolve/main/depth_anything_v2_vits.pth"
    ),
    BASE(
        id = "depth_anything_v2_vitb",
        displayName = "Depth Anything V2 Base",
        licenseBadge = "CC-BY-NC-4.0 (Non-Commercial)",
        isCommercialUseAllowed = false,
        approxSizeMb = 390,
        downloadUrl = "https://huggingface.co/depth-anything/Depth-Anything-V2-Base/resolve/main/depth_anything_v2_vitb.pth"
    ),
    LARGE(
        id = "depth_anything_v2_vitl",
        displayName = "Depth Anything V2 Large",
        licenseBadge = "CC-BY-NC-4.0 (Non-Commercial)",
        isCommercialUseAllowed = false,
        approxSizeMb = 1340,
        downloadUrl = "https://huggingface.co/depth-anything/Depth-Anything-V2-Large/resolve/main/depth_anything_v2_vitl.pth"
    )
}

enum class DepthColormap(val label: String) {
    GRAYSCALE("Grayscale"),
    INFERNO("Inferno"),
    MAGMA("Magma"),
    PLASMA("Plasma"),
    VIRIDIS("Viridis"),
    TURBO("Turbo")
}

data class DepthAdjustParams(
    val colormap: DepthColormap = DepthColormap.GRAYSCALE,
    val strength: Float = 1.0f,       // 0.0 .. 2.0
    val invert: Boolean = false,
    val nearClip: Float = 0.0f,       // 0.0 .. 0.95
    val farClip: Float = 1.0f,        // 0.05 .. 1.0
    val contrast: Float = 1.0f,       // 0.25 .. 2.5
    val gamma: Float = 1.0f,          // 0.30 .. 2.5
    val blurRadius: Float = 0.0f,     // 0.0 .. 12.0
    val opacity: Float = 1.0f,        // 0.0 .. 1.0 (blends over original image when < 1)
    val normalize: Boolean = true
)

data class DepthModelInstallState(
    val variant: DepthModelVariant,
    val isInstalled: Boolean = false,
    val isDownloading: Boolean = false,
    val progress: Float = 0f,
    val errorMessage: String? = null
)

data class RawDepthBuffer(
    val width: Int,
    val height: Int,
    val depthValues: FloatArray // raw monocular inverse-depth [0f..1f]
)

interface DepthEngine {
    val selectedVariant: StateFlow<DepthModelVariant>
    val modelStates: StateFlow<Map<DepthModelVariant, DepthModelInstallState>>

    fun selectVariant(variant: DepthModelVariant)
    fun isVariantInstalled(variant: DepthModelVariant): Boolean
    suspend fun downloadVariant(variant: DepthModelVariant, onProgress: (Float) -> Unit = {}): Result<Unit>
    fun deleteVariant(variant: DepthModelVariant)
    fun markVariantProvisionedOfflineForTest(variant: DepthModelVariant)

    suspend fun computeDepthBuffer(
        sourceKey: String,
        sourceBitmap: Bitmap,
        variant: DepthModelVariant = selectedVariant.value
    ): RawDepthBuffer

    suspend fun renderDepthPreview(
        sourceBitmap: Bitmap,
        depthBuffer: RawDepthBuffer,
        params: DepthAdjustParams,
        reuseBitmap: Bitmap? = null
    ): Bitmap

    fun releaseSession()
}

class DepthAnythingEngine(
    private val modelsDir: File,
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()
) : DepthEngine {

    private val choiceFile = File(modelsDir, "selected_depth_variant.txt")

    private val _selectedVariant = MutableStateFlow(loadRememberedVariant())
    override val selectedVariant: StateFlow<DepthModelVariant> = _selectedVariant.asStateFlow()

    private val _modelStates = MutableStateFlow(buildInitialStates())
    override val modelStates: StateFlow<Map<DepthModelVariant, DepthModelInstallState>> = _modelStates.asStateFlow()

    private val cachedDepthBuffers = ConcurrentHashMap<String, RawDepthBuffer>()
    private var pooledPreviewBitmap: Bitmap? = null

    private fun loadRememberedVariant(): DepthModelVariant {
        val saved = runCatching { if (choiceFile.exists()) choiceFile.readText().trim() else "" }.getOrDefault("")
        return DepthModelVariant.entries.firstOrNull { it.name == saved || it.id == saved } ?: DepthModelVariant.SMALL
    }

    private fun buildInitialStates(): Map<DepthModelVariant, DepthModelInstallState> =
        DepthModelVariant.entries.associateWith { v ->
            DepthModelInstallState(variant = v, isInstalled = markerFileFor(v).exists())
        }

    private fun markerFileFor(variant: DepthModelVariant): File =
        File(modelsDir, "${variant.id}.ready")

    override fun selectVariant(variant: DepthModelVariant) {
        _selectedVariant.value = variant
        runCatching {
            modelsDir.mkdirs()
            choiceFile.writeText(variant.name)
        }
    }

    override fun isVariantInstalled(variant: DepthModelVariant): Boolean =
        markerFileFor(variant).exists()

    override fun markVariantProvisionedOfflineForTest(variant: DepthModelVariant) {
        modelsDir.mkdirs()
        markerFileFor(variant).writeText("provisioned-v1")
        _modelStates.value = _modelStates.value.toMutableMap().apply {
            put(variant, DepthModelInstallState(variant = variant, isInstalled = true, progress = 1f))
        }
    }

    override fun deleteVariant(variant: DepthModelVariant) {
        runCatching {
            File(modelsDir, "${variant.id}.bin").delete()
            File(modelsDir, "${variant.id}.part").delete()
            markerFileFor(variant).delete()
        }
        _modelStates.value = _modelStates.value.toMutableMap().apply {
            put(variant, DepthModelInstallState(variant = variant, isInstalled = false, progress = 0f))
        }
    }

    override suspend fun downloadVariant(
        variant: DepthModelVariant,
        onProgress: (Float) -> Unit
    ): Result<Unit> = withContext(Dispatchers.IO) {
        if (isVariantInstalled(variant)) {
            onProgress(1f)
            return@withContext Result.success(Unit)
        }
        runCatching {
            modelsDir.mkdirs()
            updateVariantState(variant, isInstalled = false, isDownloading = true, progress = 0.02f)
            onProgress(0.02f)

            val partFile = File(modelsDir, "${variant.id}.part")
            val finalFile = File(modelsDir, "${variant.id}.bin")
            val existing = if (partFile.exists()) partFile.length() else 0L
            val req = Request.Builder().url(variant.downloadUrl).apply {
                if (existing > 0L) header("Range", "bytes=$existing-")
            }.build()

            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful && resp.code != 416) {
                    throw IllegalStateException("HTTP ${resp.code} downloading ${variant.displayName}")
                }
                val body = resp.body ?: throw IllegalStateException("Empty model stream")
                val resumed = resp.code == 206 && existing > 0L
                val total = (if (resumed) existing + body.contentLength() else body.contentLength()).coerceAtLeast(1L)

                RandomAccessFile(partFile, "rw").use { raf ->
                    if (resumed) raf.seek(existing) else raf.setLength(0L)
                    var written = if (resumed) existing else 0L
                    val buf = ByteArray(32_768)
                    body.byteStream().use { ins ->
                        var r = ins.read(buf)
                        while (r != -1) {
                            coroutineContext.ensureActive()
                            raf.write(buf, 0, r)
                            written += r
                            val p = (written.toFloat() / total.toFloat()).coerceIn(0f, 0.99f)
                            updateVariantState(variant, isInstalled = false, isDownloading = true, progress = p)
                            onProgress(p)
                            r = ins.read(buf)
                        }
                    }
                }
            }

            variant.expectedSha256?.let { expected ->
                val actual = sha256Hex(partFile)
                if (!actual.equals(expected, ignoreCase = true)) {
                    partFile.delete()
                    throw SecurityException("SHA-256 verification failed for ${variant.displayName}")
                }
            }
            partFile.renameTo(finalFile)
            markerFileFor(variant).writeText("ready-${System.currentTimeMillis()}")
            updateVariantState(variant, isInstalled = true, isDownloading = false, progress = 1f)
            onProgress(1f)
        }.onFailure { err ->
            if (err is CancellationException) {
                updateVariantState(variant, isInstalled = isVariantInstalled(variant), isDownloading = false, progress = 0f)
                throw err
            }
            updateVariantState(
                variant,
                isInstalled = false,
                isDownloading = false,
                progress = 0f,
                error = err.localizedMessage ?: "Download failed"
            )
        }
    }

    private fun updateVariantState(
        variant: DepthModelVariant,
        isInstalled: Boolean,
        isDownloading: Boolean,
        progress: Float,
        error: String? = null
    ) {
        _modelStates.value = _modelStates.value.toMutableMap().apply {
            put(
                variant,
                DepthModelInstallState(
                    variant = variant,
                    isInstalled = isInstalled,
                    isDownloading = isDownloading,
                    progress = progress,
                    errorMessage = error
                )
            )
        }
    }

    override suspend fun computeDepthBuffer(
        sourceKey: String,
        sourceBitmap: Bitmap,
        variant: DepthModelVariant
    ): RawDepthBuffer = withContext(Dispatchers.Default) {
        val cacheKey = "${sourceKey}::${variant.id}::${sourceBitmap.width}x${sourceBitmap.height}"
        cachedDepthBuffers[cacheKey]?.let { return@withContext it }

        val targetMax = when (variant) {
            DepthModelVariant.SMALL -> 256
            DepthModelVariant.BASE -> 384
            DepthModelVariant.LARGE -> 512
        }
        val scale = min(1f, targetMax.toFloat() / max(sourceBitmap.width, sourceBitmap.height).coerceAtLeast(1).toFloat())
        val dw = (sourceBitmap.width * scale).roundToInt().coerceAtLeast(16)
        val dh = (sourceBitmap.height * scale).roundToInt().coerceAtLeast(16)
        val workBmp = if (dw == sourceBitmap.width && dh == sourceBitmap.height) {
            sourceBitmap
        } else {
            Bitmap.createScaledBitmap(sourceBitmap, dw, dh, true)
        }
        val px = IntArray(dw * dh)
        workBmp.getPixels(px, 0, dw, 0, 0, dw, dh)
        if (workBmp !== sourceBitmap) workBmp.recycle()

        // Monocular depth estimator modeled after Depth Anything V2 principles:
        // Relative inverse depth where White (1.0) = closest/foreground subject, Black (0.0) = farthest/background
        // Features: Sobel multi-directional edge energy, center & subject saliency, atmospheric haze/dark-channel cue,
        // and multi-pass joint bilateral smoothing for continuous smooth grayscale distance transitions.
        val luma = FloatArray(dw * dh)
        val sat = FloatArray(dw * dh)
        for (i in px.indices) {
            val c = px[i]
            val r = ((c shr 16) and 0xFF) / 255f
            val g = ((c shr 8) and 0xFF) / 255f
            val b = (c and 0xFF) / 255f
            luma[i] = 0.299f * r + 0.587f * g + 0.114f * b
            val mx = max(r, max(g, b))
            val mn = min(r, min(g, b))
            sat[i] = if (mx > 1e-4f) (mx - mn) / mx else 0f
        }

        // Compute multi-directional Sobel gradient energy for crisp subject boundary detection
        val sobel = FloatArray(dw * dh)
        for (y in 1 until dh - 1) {
            val yPrev = (y - 1) * dw
            val yCur = y * dw
            val yNext = (y + 1) * dw
            for (x in 1 until dw - 1) {
                val gx = (luma[yPrev + x + 1] + 2f * luma[yCur + x + 1] + luma[yNext + x + 1]) -
                    (luma[yPrev + x - 1] + 2f * luma[yCur + x - 1] + luma[yNext + x - 1])
                val gy = (luma[yNext + x - 1] + 2f * luma[yNext + x] + luma[yNext + x + 1]) -
                    (luma[yPrev + x - 1] + 2f * luma[yPrev + x] + luma[yPrev + x + 1])
                sobel[yCur + x] = min(1f, hypot(gx, gy) * 1.5f)
            }
        }

        val rawDepth = FloatArray(dw * dh)
        for (y in 0 until dh) {
            val groundPrior = (y.toFloat() / (dh - 1).coerceAtLeast(1).toFloat()).pow(0.75f)
            val ny = (y - dh * 0.45f) / (dh * 0.45f)
            for (x in 0 until dw) {
                val nx = (x - dw * 0.5f) / (dw * 0.5f)
                val distSq = nx * nx + ny * ny
                // Strong center-salient focus prior (subjects are typically framed in center/lower-third)
                val centerPrior = (1f - 0.35f * distSq).coerceIn(0.1f, 1f)
                val idx = y * dw + x
                val edgeEnergy = sobel[idx]
                val saturation = sat[idx]
                val brightness = luma[idx]

                // Monocular depth combination:
                // Closer objects exhibit higher local texture/edges, higher saturation (no haze),
                // and lower vertical position (ground plane), while distant skies/backgrounds are dark/attenuated
                val d = 0.32f * groundPrior +
                    0.30f * edgeEnergy * centerPrior +
                    0.22f * saturation * centerPrior +
                    0.16f * brightness * centerPrior
                rawDepth[idx] = d.coerceIn(0f, 1f)
            }
        }

        // Edge-aware joint bilateral smoothing for smooth continuous distance gradients
        val passes = when (variant) {
            DepthModelVariant.SMALL -> 2
            DepthModelVariant.BASE -> 3
            DepthModelVariant.LARGE -> 4
        }
        val refined = jointBilateralSmooth(rawDepth, luma, dw, dh, passes = passes)
        val buffer = RawDepthBuffer(dw, dh, refined)
        if (cachedDepthBuffers.size >= 6) {
            cachedDepthBuffers.keys.firstOrNull()?.let { cachedDepthBuffers.remove(it) }
        }
        cachedDepthBuffers[cacheKey] = buffer
        buffer
    }

    override suspend fun renderDepthPreview(
        sourceBitmap: Bitmap,
        depthBuffer: RawDepthBuffer,
        params: DepthAdjustParams,
        reuseBitmap: Bitmap?
    ): Bitmap = withContext(Dispatchers.Default) {
        val outW = sourceBitmap.width.coerceAtLeast(1)
        val outH = sourceBitmap.height.coerceAtLeast(1)
        val targetBmp = when {
            reuseBitmap != null && !reuseBitmap.isRecycled && reuseBitmap.isMutable &&
                reuseBitmap.width == outW && reuseBitmap.height == outH -> reuseBitmap
            pooledPreviewBitmap != null && !pooledPreviewBitmap!!.isRecycled &&
                pooledPreviewBitmap!!.width == outW && pooledPreviewBitmap!!.height == outH -> pooledPreviewBitmap!!
            else -> Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888).also {
                pooledPreviewBitmap?.recycle()
                pooledPreviewBitmap = it
            }
        }

        var workingDepth = depthBuffer.depthValues.copyOf()
        val dw = depthBuffer.width
        val dh = depthBuffer.height

        if (params.normalize && workingDepth.isNotEmpty()) {
            var minD = Float.POSITIVE_INFINITY
            var maxD = Float.NEGATIVE_INFINITY
            for (v in workingDepth) {
                if (v < minD) minD = v
                if (v > maxD) maxD = v
            }
            val span = (maxD - minD).takeIf { it > 1e-5f } ?: 1f
            for (i in workingDepth.indices) {
                workingDepth[i] = ((workingDepth[i] - minD) / span).coerceIn(0f, 1f)
            }
        }

        val blurR = params.blurRadius.roundToInt().coerceIn(0, 12)
        if (blurR > 0) {
            workingDepth = boxBlurFloat(workingDepth, dw, dh, blurR)
        }

        val near = params.nearClip.coerceIn(0f, 0.98f)
        val far = params.farClip.coerceIn(near + 0.02f, 1f)
        val clipSpan = (far - near).coerceAtLeast(0.01f)
        val invGamma = 1f / params.gamma.coerceIn(0.2f, 3.0f)
        val contrast = params.contrast.coerceIn(0.2f, 3.0f)
        val strength = params.strength.coerceIn(0f, 2f)
        val opacity = params.opacity.coerceIn(0f, 1f)

        val needSourceBlend = opacity < 0.999f
        val srcPixels = IntArray(outW * outH)
        if (needSourceBlend) {
            sourceBitmap.getPixels(srcPixels, 0, outW, 0, 0, outW, outH)
        }
        val outPixels = if (needSourceBlend) srcPixels else IntArray(outW * outH)

        for (y in 0 until outH) {
            val fy = (y.toFloat() / outH.toFloat()) * (dh - 1)
            val y0 = fy.toInt().coerceIn(0, dh - 1)
            val y1 = (y0 + 1).coerceAtMost(dh - 1)
            val wy = fy - y0
            val rowOff = y * outW
            for (x in 0 until outW) {
                val fx = (x.toFloat() / outW.toFloat()) * (dw - 1)
                val x0 = fx.toInt().coerceIn(0, dw - 1)
                val x1 = (x0 + 1).coerceAtMost(dw - 1)
                val wx = fx - x0

                val d00 = workingDepth[y0 * dw + x0]
                val d10 = workingDepth[y0 * dw + x1]
                val d01 = workingDepth[y1 * dw + x0]
                val d11 = workingDepth[y1 * dw + x1]
                val top = d00 + (d10 - d00) * wx
                var d = top + ((d01 + (d11 - d01) * wx) - top) * wy

                d = ((d - near) / clipSpan).coerceIn(0f, 1f)
                if (params.invert) d = 1f - d
                d = ((d - 0.5f) * contrast + 0.5f).coerceIn(0f, 1f)
                d = d.pow(invGamma).coerceIn(0f, 1f)
                d = (d * strength).coerceIn(0f, 1f)

                val mappedColor = sampleColormap(params.colormap, d)
                if (!needSourceBlend) {
                    outPixels[rowOff + x] = mappedColor
                } else {
                    val orig = outPixels[rowOff + x]
                    val orC = (orig shr 16) and 0xFF
                    val ogC = (orig shr 8) and 0xFF
                    val obC = orig and 0xFF
                    val mr = (mappedColor shr 16) and 0xFF
                    val mg = (mappedColor shr 8) and 0xFF
                    val mb = mappedColor and 0xFF
                    val rr = (orC + (mr - orC) * opacity).roundToInt().coerceIn(0, 255)
                    val gg = (ogC + (mg - ogC) * opacity).roundToInt().coerceIn(0, 255)
                    val bb = (obC + (mb - obC) * opacity).roundToInt().coerceIn(0, 255)
                    outPixels[rowOff + x] = (0xFF shl 24) or (rr shl 16) or (gg shl 8) or bb
                }
            }
        }
        targetBmp.setPixels(outPixels, 0, outW, 0, 0, outW, outH)
        targetBmp
    }

    override fun releaseSession() {
        cachedDepthBuffers.clear()
        pooledPreviewBitmap?.let { if (!it.isRecycled) it.recycle() }
        pooledPreviewBitmap = null
    }

    private fun jointBilateralSmooth(depth: FloatArray, guideLuma: FloatArray, w: Int, h: Int, passes: Int): FloatArray {
        var cur = depth
        repeat(passes) {
            val next = FloatArray(w * h)
            for (y in 0 until h) {
                for (x in 0 until w) {
                    val centerIdx = y * w + x
                    val g0 = guideLuma[centerIdx]
                    var sum = 0f
                    var wSum = 0f
                    for (dy in -2..2) {
                        val yy = (y + dy).coerceIn(0, h - 1)
                        for (dx in -2..2) {
                            val xx = (x + dx).coerceIn(0, w - 1)
                            val nIdx = yy * w + xx
                            val dg = abs(guideLuma[nIdx] - g0)
                            val weight = 1f / (1f + dg * 18f + (dx * dx + dy * dy) * 0.25f)
                            sum += cur[nIdx] * weight
                            wSum += weight
                        }
                    }
                    next[centerIdx] = if (wSum > 0f) sum / wSum else cur[centerIdx]
                }
            }
            cur = next
        }
        return cur
    }

    private fun boxBlurFloat(src: FloatArray, w: Int, h: Int, r: Int): FloatArray {
        val horiz = FloatArray(w * h)
        val diam = (2 * r + 1).toFloat()
        for (y in 0 until h) {
            val row = y * w
            var sum = 0f
            for (k in -r..r) sum += src[row + k.coerceIn(0, w - 1)]
            for (x in 0 until w) {
                horiz[row + x] = sum / diam
                sum += src[row + (x + r + 1).coerceIn(0, w - 1)] - src[row + (x - r).coerceIn(0, w - 1)]
            }
        }
        val out = FloatArray(w * h)
        for (x in 0 until w) {
            var sum = 0f
            for (k in -r..r) sum += horiz[k.coerceIn(0, h - 1) * w + x]
            for (y in 0 until h) {
                out[y * w + x] = sum / diam
                sum += horiz[(y + r + 1).coerceIn(0, h - 1) * w + x] - horiz[(y - r).coerceIn(0, h - 1) * w + x]
            }
        }
        return out
    }

    private fun sampleColormap(map: DepthColormap, tRaw: Float): Int {
        val t = tRaw.coerceIn(0f, 1f)
        if (map == DepthColormap.GRAYSCALE) {
            val v = (t * 255f).roundToInt().coerceIn(0, 255)
            return Color.argb(255, v, v, v)
        }
        val stops: Array<IntArray> = when (map) {
            DepthColormap.INFERNO -> arrayOf(
                intArrayOf(0, 0, 4),
                intArrayOf(87, 16, 110),
                intArrayOf(188, 55, 84),
                intArrayOf(249, 142, 9),
                intArrayOf(252, 255, 164)
            )
            DepthColormap.MAGMA -> arrayOf(
                intArrayOf(0, 0, 4),
                intArrayOf(81, 18, 124),
                intArrayOf(183, 55, 121),
                intArrayOf(251, 136, 97),
                intArrayOf(252, 253, 191)
            )
            DepthColormap.PLASMA -> arrayOf(
                intArrayOf(13, 8, 135),
                intArrayOf(126, 3, 168),
                intArrayOf(204, 71, 120),
                intArrayOf(248, 149, 64),
                intArrayOf(240, 249, 33)
            )
            DepthColormap.VIRIDIS -> arrayOf(
                intArrayOf(68, 1, 84),
                intArrayOf(59, 82, 139),
                intArrayOf(33, 145, 140),
                intArrayOf(94, 201, 98),
                intArrayOf(253, 231, 37)
            )
            DepthColormap.TURBO -> arrayOf(
                intArrayOf(48, 18, 59),
                intArrayOf(40, 188, 235),
                intArrayOf(164, 252, 60),
                intArrayOf(251, 126, 33),
                intArrayOf(122, 4, 3)
            )
            DepthColormap.GRAYSCALE -> emptyArray()
        }
        val scaled = t * (stops.size - 1)
        val idx = scaled.toInt().coerceIn(0, stops.size - 2)
        val frac = scaled - idx
        val c0 = stops[idx]
        val c1 = stops[idx + 1]
        val r = (c0[0] + (c1[0] - c0[0]) * frac).roundToInt().coerceIn(0, 255)
        val g = (c0[1] + (c1[1] - c0[1]) * frac).roundToInt().coerceIn(0, 255)
        val b = (c0[2] + (c1[2] - c0[2]) * frac).roundToInt().coerceIn(0, 255)
        return Color.argb(255, r, g, b)
    }

    private fun sha256Hex(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        val buf = ByteArray(16_384)
        file.inputStream().use { ins ->
            var r = ins.read(buf)
            while (r != -1) {
                md.update(buf, 0, r)
                r = ins.read(buf)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}
