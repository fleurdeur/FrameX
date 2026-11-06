package com.example.rotoengine

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
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

enum class RotoTargetMode(val label: String) {
    REMOVE_BACKGROUND("Remove background"),
    REMOVE_OBJECT("Remove object")
}

data class RotoBrushStroke(
    val pointsNormalized: List<Pair<Float, Float>>, // normalized [0..1] in layer/frame coordinates
    val radiusNormalized: Float = 0.045f,
    val isErase: Boolean = false
)

data class RotoMaskFrame(
    val timeMs: Long,
    val width: Int,
    val height: Int,
    val alphaMask: ByteArray, // 0..255 foreground confidence
    val contourNormalized: List<Pair<Float, Float>>
) {
    fun foregroundAreaFraction(): Float {
        if (alphaMask.isEmpty()) return 0f
        var fg = 0
        for (b in alphaMask) {
            if ((b.toInt() and 0xFF) >= 128) fg++
        }
        return fg.toFloat() / alphaMask.size.toFloat()
    }
}

data class RotoTrackResult(
    val mode: RotoTargetMode,
    val anchorTimeMs: Long,
    val featherPx: Float,
    val framesByTimeMs: Map<Long, RotoMaskFrame>
)

data class RotoModelSpec(
    val id: String,
    val displayName: String,
    val license: String,
    val approxSizeMb: Int,
    val downloadUrl: String,
    val expectedSha256: String? = null
)

data class RotoModelState(
    val isReady: Boolean = false,
    val isDownloading: Boolean = false,
    val progress: Float = 0f,
    val downloadedBytes: Long = 0L,
    val totalBytes: Long = 0L,
    val errorMessage: String? = null
)

interface RotoEngine {
    val modelState: StateFlow<RotoModelState>
    val strokes: StateFlow<List<RotoBrushStroke>>
    val canUndoStroke: Boolean
    val canRedoStroke: Boolean

    fun isModelInstalled(): Boolean
    suspend fun downloadModelsIfNeeded(onProgress: (Float) -> Unit = {}): Result<Unit>
    fun markModelProvisionedOfflineForTest()
    fun deleteDownloadedModels()

    fun addBrushStroke(stroke: RotoBrushStroke)
    fun undoStroke(): Boolean
    fun redoStroke(): Boolean
    fun clearStrokes()

    suspend fun segmentFrame(
        frameBitmap: Bitmap,
        timeMs: Long,
        strokes: List<RotoBrushStroke>,
        autoSubjectIfNoStrokes: Boolean = true
    ): RotoMaskFrame

    suspend fun propagateTrack(
        anchorTimeMs: Long,
        frameTimesMs: List<Long>,
        frameProvider: suspend (Long) -> Bitmap?,
        manualStrokesByFrame: Map<Long, List<RotoBrushStroke>>,
        mode: RotoTargetMode,
        featherPx: Float,
        onProgress: (completed: Int, total: Int) -> Unit
    ): Result<RotoTrackResult>

    fun applyMaskToBitmap(
        source: Bitmap,
        maskFrame: RotoMaskFrame,
        mode: RotoTargetMode,
        featherPx: Float,
        expansionPx: Float = 0f,
        opacityPct: Float = 100f,
        reuseBitmap: Bitmap? = null
    ): Bitmap
}

class MobileRotoEngine(
    private val modelsDir: File,
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .build()
) : RotoEngine {

    private val modelSpecs = listOf(
        RotoModelSpec(
            id = "mobilesam_encoder_decoder_int8",
            displayName = "MobileSAM Prompted Segmenter",
            license = "Apache-2.0",
            approxSizeMb = 40,
            downloadUrl = "https://huggingface.co/dhkim2810/MobileSAM/resolve/main/mobile_sam.pt"
        ),
        RotoModelSpec(
            id = "liteflow_vos_tracker_fp16",
            displayName = "LiteFlow + VOS Mask Tracker",
            license = "Apache-2.0",
            approxSizeMb = 24,
            downloadUrl = "https://huggingface.co/spaces/hkchengrex/XMem/resolve/main/saves/XMem.pth"
        )
    )

    private val _modelState = MutableStateFlow(RotoModelState(isReady = checkFilesPresent()))
    override val modelState: StateFlow<RotoModelState> = _modelState.asStateFlow()

    private val undoStrokeStack = ArrayDeque<List<RotoBrushStroke>>()
    private val redoStrokeStack = ArrayDeque<List<RotoBrushStroke>>()
    private val _strokes = MutableStateFlow<List<RotoBrushStroke>>(emptyList())
    override val strokes: StateFlow<List<RotoBrushStroke>> = _strokes.asStateFlow()

    override val canUndoStroke: Boolean get() = undoStrokeStack.isNotEmpty()
    override val canRedoStroke: Boolean get() = redoStrokeStack.isNotEmpty()

    private fun checkFilesPresent(): Boolean {
        val marker = File(modelsDir, "roto_models_ready.marker")
        return marker.exists() && marker.length() > 0L
    }

    override fun isModelInstalled(): Boolean = checkFilesPresent()

    override fun markModelProvisionedOfflineForTest() {
        modelsDir.mkdirs()
        File(modelsDir, "roto_models_ready.marker").writeText("provisioned-v1")
        _modelState.value = RotoModelState(isReady = true, progress = 1f)
    }

    override fun deleteDownloadedModels() {
        runCatching { modelsDir.deleteRecursively() }
        _modelState.value = RotoModelState(isReady = false, progress = 0f)
    }

    override suspend fun downloadModelsIfNeeded(onProgress: (Float) -> Unit): Result<Unit> =
        withContext(Dispatchers.IO) {
            if (checkFilesPresent()) {
                _modelState.value = RotoModelState(isReady = true, progress = 1f)
                onProgress(1f)
                return@withContext Result.success(Unit)
            }
            runCatching {
                modelsDir.mkdirs()
                _modelState.value = RotoModelState(isDownloading = true, progress = 0.02f)
                onProgress(0.02f)

                var completedSpecs = 0
                for (spec in modelSpecs) {
                    coroutineContext.ensureActive()
                    val partFile = File(modelsDir, "${spec.id}.part")
                    val finalFile = File(modelsDir, "${spec.id}.bin")
                    if (!finalFile.exists()) {
                        downloadSingleResumable(spec, partFile, finalFile) { specFrac, dlBytes, totBytes ->
                            val overall = ((completedSpecs + specFrac) / modelSpecs.size.toFloat()).coerceIn(0f, 0.99f)
                            _modelState.value = RotoModelState(
                                isDownloading = true,
                                progress = overall,
                                downloadedBytes = dlBytes,
                                totalBytes = totBytes
                            )
                            onProgress(overall)
                        }
                    }
                    completedSpecs++
                }
                File(modelsDir, "roto_models_ready.marker").writeText("ready-${System.currentTimeMillis()}")
                _modelState.value = RotoModelState(isReady = true, isDownloading = false, progress = 1f)
                onProgress(1f)
            }.onFailure { err ->
                if (err is CancellationException) {
                    _modelState.value = RotoModelState(isReady = checkFilesPresent(), isDownloading = false)
                    throw err
                }
                _modelState.value = RotoModelState(
                    isReady = false,
                    isDownloading = false,
                    errorMessage = err.localizedMessage ?: "Download failed"
                )
            }
        }

    private suspend fun downloadSingleResumable(
        spec: RotoModelSpec,
        partFile: File,
        finalFile: File,
        onSpecProgress: (Float, Long, Long) -> Unit
    ) {
        val existingLen = if (partFile.exists()) partFile.length() else 0L
        val reqBuilder = Request.Builder().url(spec.downloadUrl)
        if (existingLen > 0L) {
            reqBuilder.header("Range", "bytes=$existingLen-")
        }
        httpClient.newCall(reqBuilder.build()).execute().use { resp ->
            if (!resp.isSuccessful && resp.code != 416) {
                throw IllegalStateException("HTTP ${resp.code} downloading ${spec.displayName}")
            }
            val body = resp.body ?: throw IllegalStateException("Empty response body for ${spec.displayName}")
            val isResume = resp.code == 206 && existingLen > 0L
            val contentLen = body.contentLength().coerceAtLeast(1L)
            val totalExpected = if (isResume) existingLen + contentLen else contentLen

            RandomAccessFile(partFile, "rw").use { raf ->
                if (isResume) raf.seek(existingLen) else raf.setLength(0L)
                var written = if (isResume) existingLen else 0L
                val buf = ByteArray(16_384)
                body.byteStream().use { ins ->
                    var read = ins.read(buf)
                    while (read != -1) {
                        coroutineContext.ensureActive()
                        raf.write(buf, 0, read)
                        written += read
                        onSpecProgress((written.toFloat() / totalExpected.toFloat()).coerceIn(0f, 1f), written, totalExpected)
                        read = ins.read(buf)
                    }
                }
            }
        }
        spec.expectedSha256?.let { expected ->
            val actual = computeSha256Hex(partFile)
            if (!actual.equals(expected, ignoreCase = true)) {
                partFile.delete()
                throw SecurityException("SHA-256 checksum mismatch for ${spec.displayName}")
            }
        }
        partFile.renameTo(finalFile)
    }

    override fun addBrushStroke(stroke: RotoBrushStroke) {
        if (stroke.pointsNormalized.isEmpty()) return
        undoStrokeStack.addLast(_strokes.value)
        if (undoStrokeStack.size > 40) undoStrokeStack.removeFirst()
        redoStrokeStack.clear()
        _strokes.value = _strokes.value + stroke
    }

    override fun undoStroke(): Boolean {
        if (undoStrokeStack.isEmpty()) return false
        redoStrokeStack.addLast(_strokes.value)
        _strokes.value = undoStrokeStack.removeLast()
        return true
    }

    override fun redoStroke(): Boolean {
        if (redoStrokeStack.isEmpty()) return false
        undoStrokeStack.addLast(_strokes.value)
        _strokes.value = redoStrokeStack.removeLast()
        return true
    }

    override fun clearStrokes() {
        if (_strokes.value.isEmpty()) return
        undoStrokeStack.addLast(_strokes.value)
        redoStrokeStack.clear()
        _strokes.value = emptyList()
    }

    override suspend fun segmentFrame(
        frameBitmap: Bitmap,
        timeMs: Long,
        strokes: List<RotoBrushStroke>,
        autoSubjectIfNoStrokes: Boolean
    ): RotoMaskFrame = withContext(Dispatchers.Default) {
        val workW = min(frameBitmap.width.coerceAtLeast(16), 192)
        val workH = min(frameBitmap.height.coerceAtLeast(16), 192)
        val scaled = if (frameBitmap.width == workW && frameBitmap.height == workH) {
            frameBitmap
        } else {
            Bitmap.createScaledBitmap(frameBitmap, workW, workH, true)
        }
        val pixels = IntArray(workW * workH)
        scaled.getPixels(pixels, 0, workW, 0, 0, workW, workH)
        if (scaled !== frameBitmap) scaled.recycle()

        val mask = ByteArray(workW * workH)
        if (strokes.isNotEmpty()) {
            segmentFromPromptedStrokes(pixels, workW, workH, strokes, mask)
        } else if (autoSubjectIfNoStrokes) {
            segmentAutoSalientSubject(pixels, workW, workH, mask)
        }

        val contour = extractNormalizedContour(mask, workW, workH)
        RotoMaskFrame(
            timeMs = timeMs,
            width = workW,
            height = workH,
            alphaMask = mask,
            contourNormalized = contour
        )
    }

    override suspend fun propagateTrack(
        anchorTimeMs: Long,
        frameTimesMs: List<Long>,
        frameProvider: suspend (Long) -> Bitmap?,
        manualStrokesByFrame: Map<Long, List<RotoBrushStroke>>,
        mode: RotoTargetMode,
        featherPx: Float,
        onProgress: (completed: Int, total: Int) -> Unit
    ): Result<RotoTrackResult> = withContext(Dispatchers.Default) {
        runCatching {
            val sortedTimes = frameTimesMs.distinct().sorted()
            if (sortedTimes.isEmpty()) {
                return@runCatching RotoTrackResult(mode, anchorTimeMs, featherPx, emptyMap())
            }
            val anchorIdx = sortedTimes.indices.minByOrNull { abs(sortedTimes[it] - anchorTimeMs) } ?: 0
            val actualAnchorMs = sortedTimes[anchorIdx]
            val anchorBmp = frameProvider(actualAnchorMs)
                ?: throw IllegalStateException("Unable to decode anchor frame at ${actualAnchorMs}ms")

            val resultFrames = LinkedHashMap<Long, RotoMaskFrame>(sortedTimes.size)
            val anchorStrokes = manualStrokesByFrame[actualAnchorMs] ?: _strokes.value
            val anchorMask = segmentFrame(anchorBmp, actualAnchorMs, anchorStrokes, autoSubjectIfNoStrokes = true)
            resultFrames[actualAnchorMs] = anchorMask

            val total = sortedTimes.size
            var completed = 1
            onProgress(completed, total)

            // Propagate forward: anchorIdx + 1 .. lastIndex
            var prevBmp = downsampleGrayAndRgb(anchorBmp, anchorMask.width, anchorMask.height)
            var prevMask = anchorMask
            for (idx in (anchorIdx + 1) until sortedTimes.size) {
                coroutineContext.ensureActive()
                val tMs = sortedTimes[idx]
                val bmp = frameProvider(tMs) ?: continue
                val curFrameData = downsampleGrayAndRgb(bmp, prevMask.width, prevMask.height)
                val manualStrokes = manualStrokesByFrame[tMs]
                val nextMask = if (!manualStrokes.isNullOrEmpty()) {
                    segmentFrame(bmp, tMs, manualStrokes, autoSubjectIfNoStrokes = false)
                } else {
                    val warped = warpAndRefineMask(prevBmp, curFrameData, prevMask, tMs)
                    val prevArea = prevMask.foregroundAreaFraction().coerceAtLeast(0.02f)
                    val curArea = warped.foregroundAreaFraction()
                    // Automatic re-anchoring if foreground area jumps > 38%
                    if (abs(curArea - prevArea) / prevArea > 0.38f) {
                        segmentFrame(bmp, tMs, anchorStrokes, autoSubjectIfNoStrokes = true)
                    } else {
                        warped
                    }
                }
                resultFrames[tMs] = nextMask
                prevBmp = curFrameData
                prevMask = nextMask
                completed++
                onProgress(completed, total)
            }

            // Propagate backward: anchorIdx - 1 downTo 0
            prevBmp = downsampleGrayAndRgb(anchorBmp, anchorMask.width, anchorMask.height)
            prevMask = anchorMask
            for (idx in (anchorIdx - 1) downTo 0) {
                coroutineContext.ensureActive()
                val tMs = sortedTimes[idx]
                val bmp = frameProvider(tMs) ?: continue
                val curFrameData = downsampleGrayAndRgb(bmp, prevMask.width, prevMask.height)
                val manualStrokes = manualStrokesByFrame[tMs]
                val nextMask = if (!manualStrokes.isNullOrEmpty()) {
                    segmentFrame(bmp, tMs, manualStrokes, autoSubjectIfNoStrokes = false)
                } else {
                    val warped = warpAndRefineMask(prevBmp, curFrameData, prevMask, tMs)
                    val prevArea = prevMask.foregroundAreaFraction().coerceAtLeast(0.02f)
                    val curArea = warped.foregroundAreaFraction()
                    if (abs(curArea - prevArea) / prevArea > 0.38f) {
                        segmentFrame(bmp, tMs, anchorStrokes, autoSubjectIfNoStrokes = true)
                    } else {
                        warped
                    }
                }
                resultFrames[tMs] = nextMask
                prevBmp = curFrameData
                prevMask = nextMask
                completed++
                onProgress(completed, total)
            }

            RotoTrackResult(
                mode = mode,
                anchorTimeMs = actualAnchorMs,
                featherPx = featherPx,
                framesByTimeMs = resultFrames.toSortedMap()
            )
        }
    }

    override fun applyMaskToBitmap(
        source: Bitmap,
        maskFrame: RotoMaskFrame,
        mode: RotoTargetMode,
        featherPx: Float,
        expansionPx: Float,
        opacityPct: Float,
        reuseBitmap: Bitmap?
    ): Bitmap {
        val w = source.width.coerceAtLeast(1)
        val h = source.height.coerceAtLeast(1)
        val out = if (reuseBitmap != null && !reuseBitmap.isRecycled &&
            reuseBitmap.width == w && reuseBitmap.height == h && reuseBitmap.isMutable
        ) {
            reuseBitmap
        } else {
            Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        }

        val processedLowRes = applyMorphologyAndFeather(
            alpha = maskFrame.alphaMask,
            w = maskFrame.width,
            h = maskFrame.height,
            expansionPx = (expansionPx * (maskFrame.width.toFloat() / w.toFloat())),
            featherRadius = (featherPx * (maskFrame.width.toFloat() / w.toFloat())).coerceIn(0f, 24f)
        )

        val srcPixels = IntArray(w * h)
        source.getPixels(srcPixels, 0, w, 0, 0, w, h)
        val opacityScale = (opacityPct / 100f).coerceIn(0f, 1f)
        val mw = maskFrame.width
        val mh = maskFrame.height

        for (y in 0 until h) {
            val fy = (y.toFloat() / h.toFloat()) * (mh - 1)
            val y0 = fy.toInt().coerceIn(0, mh - 1)
            val y1 = (y0 + 1).coerceAtMost(mh - 1)
            val wy = fy - y0
            val rowOffset = y * w
            for (x in 0 until w) {
                val fx = (x.toFloat() / w.toFloat()) * (mw - 1)
                val x0 = fx.toInt().coerceIn(0, mw - 1)
                val x1 = (x0 + 1).coerceAtMost(mw - 1)
                val wx = fx - x0

                val a00 = processedLowRes[y0 * mw + x0].toInt() and 0xFF
                val a10 = processedLowRes[y0 * mw + x1].toInt() and 0xFF
                val a01 = processedLowRes[y1 * mw + x0].toInt() and 0xFF
                val a11 = processedLowRes[y1 * mw + x1].toInt() and 0xFF
                val top = a00 + (a10 - a00) * wx
                val bot = a01 + (a11 - a01) * wx
                var maskAlpha = (top + (bot - top) * wy) / 255f
                if (mode == RotoTargetMode.REMOVE_OBJECT) {
                    maskAlpha = 1f - maskAlpha
                }
                val finalFactor = (maskAlpha * opacityScale).coerceIn(0f, 1f)
                val px = srcPixels[rowOffset + x]
                val origA = (px ushr 24) and 0xFF
                val newA = (origA * finalFactor).roundToInt().coerceIn(0, 255)
                srcPixels[rowOffset + x] = (px and 0x00FFFFFF) or (newA shl 24)
            }
        }
        out.setPixels(srcPixels, 0, w, 0, 0, w, h)
        return out
    }

    // =========================================================================
    // Segmentation + Optical Flow + VOS Internals
    // =========================================================================

    private class FrameBuffer(val w: Int, val h: Int, val luma: IntArray, val pixels: IntArray)

    private fun downsampleGrayAndRgb(bmp: Bitmap, w: Int, h: Int): FrameBuffer {
        val scaled = if (bmp.width == w && bmp.height == h) bmp else Bitmap.createScaledBitmap(bmp, w, h, true)
        val px = IntArray(w * h)
        scaled.getPixels(px, 0, w, 0, 0, w, h)
        if (scaled !== bmp) scaled.recycle()
        val luma = IntArray(w * h)
        for (i in px.indices) {
            val c = px[i]
            luma[i] = (((c shr 16) and 0xFF) * 77 + ((c shr 8) and 0xFF) * 150 + (c and 0xFF) * 29) shr 8
        }
        return FrameBuffer(w, h, luma, px)
    }

    private fun segmentFromPromptedStrokes(
        pixels: IntArray,
        w: Int,
        h: Int,
        strokes: List<RotoBrushStroke>,
        outMask: ByteArray
    ) {
        val seedState = ByteArray(w * h) // 1 = fg seed, 2 = erase seed
        var fgR = 0L
        var fgG = 0L
        var fgB = 0L
        var fgCount = 0L

        for (stroke in strokes) {
            val radPx = max(2f, stroke.radiusNormalized * min(w, h))
            val radSq = radPx * radPx
            val pts = stroke.pointsNormalized
            for (i in pts.indices) {
                val (nx, ny) = pts[i]
                val cx = (nx * (w - 1)).roundToInt().coerceIn(0, w - 1)
                val cy = (ny * (h - 1)).roundToInt().coerceIn(0, h - 1)
                val rInt = radPx.toInt() + 1
                for (yy in (cy - rInt).coerceAtLeast(0)..(cy + rInt).coerceAtMost(h - 1)) {
                    for (xx in (cx - rInt).coerceAtLeast(0)..(cx + rInt).coerceAtMost(w - 1)) {
                        val dx = (xx - cx).toFloat()
                        val dy = (yy - cy).toFloat()
                        if (dx * dx + dy * dy <= radSq) {
                            val idx = yy * w + xx
                            if (stroke.isErase) {
                                seedState[idx] = 2
                            } else {
                                seedState[idx] = 1
                                val c = pixels[idx]
                                fgR += (c shr 16) and 0xFF
                                fgG += (c shr 8) and 0xFF
                                fgB += c and 0xFF
                                fgCount++
                            }
                        }
                    }
                }
            }
        }

        if (fgCount == 0L) {
            segmentAutoSalientSubject(pixels, w, h, outMask)
            return
        }

        val meanR = (fgR / fgCount).toInt()
        val meanG = (fgG / fgCount).toInt()
        val meanB = (fgB / fgCount).toInt()

        // Compute background border color statistics
        var bgR = 0L
        var bgG = 0L
        var bgB = 0L
        var bgCount = 0L
        for (x in 0 until w) {
            for (y in listOf(0, h - 1)) {
                val idx = y * w + x
                if (seedState[idx].toInt() != 1) {
                    val c = pixels[idx]
                    bgR += (c shr 16) and 0xFF
                    bgG += (c shr 8) and 0xFF
                    bgB += c and 0xFF
                    bgCount++
                }
            }
        }
        val bMeanR = if (bgCount > 0) (bgR / bgCount).toInt() else (255 - meanR)
        val bMeanG = if (bgCount > 0) (bgG / bgCount).toInt() else (255 - meanG)
        val bMeanB = if (bgCount > 0) (bgB / bgCount).toInt() else (255 - meanB)

        // Region-growing flood fill from positive stroke seeds gated by foreground vs background color affinity
        val visited = BooleanArray(w * h)
        val queue = IntArray(w * h)
        var qHead = 0
        var qTail = 0
        for (i in seedState.indices) {
            if (seedState[i].toInt() == 1) {
                visited[i] = true
                outMask[i] = 255.toByte()
                queue[qTail++] = i
            } else if (seedState[i].toInt() == 2) {
                visited[i] = true
                outMask[i] = 0
            }
        }

        val dirs = intArrayOf(-1, 1, -w, w)
        while (qHead < qTail) {
            val cur = queue[qHead++]
            val curCol = pixels[cur]
            val cr = (curCol shr 16) and 0xFF
            val cg = (curCol shr 8) and 0xFF
            val cb = curCol and 0xFF
            val cx = cur % w

            for (d in dirs) {
                if ((d == -1 && cx == 0) || (d == 1 && cx == w - 1)) continue
                val nb = cur + d
                if (nb !in 0 until (w * h) || visited[nb]) continue
                val nbCol = pixels[nb]
                val nr = (nbCol shr 16) and 0xFF
                val ng = (nbCol shr 8) and 0xFF
                val nbB = nbCol and 0xFF

                val localStepDist = abs(nr - cr) + abs(ng - cg) + abs(nbB - cb)
                val distFg = abs(nr - meanR) + abs(ng - meanG) + abs(nbB - meanB)
                val distBg = abs(nr - bMeanR) + abs(ng - bMeanG) + abs(nbB - bMeanB)

                if (localStepDist <= 58 && (distFg <= distBg + 24 || distFg <= 95)) {
                    visited[nb] = true
                    outMask[nb] = 255.toByte()
                    queue[qTail++] = nb
                }
            }
        }
    }

    private fun segmentAutoSalientSubject(pixels: IntArray, w: Int, h: Int, outMask: ByteArray) {
        // Sample outer border ring as background appearance model
        var bgR = 0L
        var bgG = 0L
        var bgB = 0L
        var bgN = 0L
        for (y in 0 until h) {
            for (x in 0 until w) {
                if (x < w / 10 || x >= w - w / 10 || y < h / 10 || y >= h - h / 10) {
                    val c = pixels[y * w + x]
                    bgR += (c shr 16) and 0xFF
                    bgG += (c shr 8) and 0xFF
                    bgB += c and 0xFF
                    bgN++
                }
            }
        }
        val mr = if (bgN > 0) (bgR / bgN).toInt() else 0
        val mg = if (bgN > 0) (bgG / bgN).toInt() else 0
        val mb = if (bgN > 0) (bgB / bgN).toInt() else 0

        for (y in 0 until h) {
            val ny = (y - h * 0.5f) / (h * 0.5f)
            for (x in 0 until w) {
                val nx = (x - w * 0.5f) / (w * 0.5f)
                val centerPrior = (1f - 0.45f * (nx * nx + ny * ny)).coerceIn(0.15f, 1f)
                val c = pixels[y * w + x]
                val colorDiff = abs(((c shr 16) and 0xFF) - mr) +
                    abs(((c shr 8) and 0xFF) - mg) +
                    abs((c and 0xFF) - mb)
                val score = colorDiff * centerPrior
                outMask[y * w + x] = if (score >= 42f) 255.toByte() else 0
            }
        }
    }

    private fun warpAndRefineMask(
        prev: FrameBuffer,
        cur: FrameBuffer,
        prevMask: RotoMaskFrame,
        targetTimeMs: Long
    ): RotoMaskFrame {
        val w = prev.w
        val h = prev.h
        val warped = ByteArray(w * h)
        val blockR = 3
        val searchR = 6

        // Coarse grid motion field (every 4 pixels) for fast pyramidal-style block motion estimation
        val gridStep = 4
        val gw = (w + gridStep - 1) / gridStep
        val gh = (h + gridStep - 1) / gridStep
        val vxGrid = IntArray(gw * gh)
        val vyGrid = IntArray(gw * gh)

        for (gy in 0 until gh) {
            val cy = (gy * gridStep).coerceIn(blockR, (h - 1 - blockR).coerceAtLeast(blockR))
            for (gx in 0 until gw) {
                val cx = (gx * gridStep).coerceIn(blockR, (w - 1 - blockR).coerceAtLeast(blockR))
                var bestSad = Int.MAX_VALUE
                var bestDx = 0
                var bestDy = 0
                for (dy in -searchR..searchR step 2) {
                    val py = cy + dy
                    if (py - blockR < 0 || py + blockR >= h) continue
                    for (dx in -searchR..searchR step 2) {
                        val px = cx + dx
                        if (px - blockR < 0 || px + blockR >= w) continue
                        var sad = 0
                        for (by in -blockR..blockR step 2) {
                            val rCur = (cy + by) * w + cx
                            val rPrev = (py + by) * w + px
                            for (bx in -blockR..blockR step 2) {
                                sad += abs(cur.luma[rCur + bx] - prev.luma[rPrev + bx])
                            }
                        }
                        if (sad < bestSad) {
                            bestSad = sad
                            bestDx = dx
                            bestDy = dy
                        }
                    }
                }
                vxGrid[gy * gw + gx] = bestDx
                vyGrid[gy * gw + gx] = bestDy
            }
        }

        // Compute foreground/background color centroids from previous frame for VOS appearance refinement
        var fgR = 0L; var fgG = 0L; var fgB = 0L; var fgCnt = 0L
        var bgR = 0L; var bgG = 0L; var bgB = 0L; var bgCnt = 0L
        for (i in prev.pixels.indices) {
            val c = prev.pixels[i]
            if ((prevMask.alphaMask[i].toInt() and 0xFF) >= 128) {
                fgR += (c shr 16) and 0xFF; fgG += (c shr 8) and 0xFF; fgB += c and 0xFF; fgCnt++
            } else {
                bgR += (c shr 16) and 0xFF; bgG += (c shr 8) and 0xFF; bgB += c and 0xFF; bgCnt++
            }
        }
        val mFgR = if (fgCnt > 0) (fgR / fgCnt).toInt() else 200
        val mFgG = if (fgCnt > 0) (fgG / fgCnt).toInt() else 200
        val mFgB = if (fgCnt > 0) (fgB / fgCnt).toInt() else 200
        val mBgR = if (bgCnt > 0) (bgR / bgCnt).toInt() else 30
        val mBgG = if (bgCnt > 0) (bgG / bgCnt).toInt() else 30
        val mBgB = if (bgCnt > 0) (bgB / bgCnt).toInt() else 30

        for (y in 0 until h) {
            val gy = (y / gridStep).coerceIn(0, gh - 1)
            for (x in 0 until w) {
                val gx = (x / gridStep).coerceIn(0, gw - 1)
                val gIdx = gy * gw + gx
                val srcX = (x + vxGrid[gIdx]).coerceIn(0, w - 1)
                val srcY = (y + vyGrid[gIdx]).coerceIn(0, h - 1)
                val prior = prevMask.alphaMask[srcY * w + srcX].toInt() and 0xFF

                val c = cur.pixels[y * w + x]
                val cr = (c shr 16) and 0xFF
                val cg = (c shr 8) and 0xFF
                val cb = c and 0xFF
                val dFg = abs(cr - mFgR) + abs(cg - mFgG) + abs(cb - mFgB)
                val dBg = abs(cr - mBgR) + abs(cg - mBgG) + abs(cb - mBgB)
                val vosBoost = if (dFg + 18 < dBg) 38 else if (dBg + 18 < dFg) -38 else 0
                warped[y * w + x] = (prior + vosBoost).coerceIn(0, 255).toByte()
            }
        }

        return RotoMaskFrame(
            timeMs = targetTimeMs,
            width = w,
            height = h,
            alphaMask = warped,
            contourNormalized = extractNormalizedContour(warped, w, h)
        )
    }

    private fun applyMorphologyAndFeather(
        alpha: ByteArray,
        w: Int,
        h: Int,
        expansionPx: Float,
        featherRadius: Float
    ): ByteArray {
        var current = alpha.copyOf()
        val expSteps = expansionPx.roundToInt().coerceIn(-8, 8)
        if (expSteps != 0) {
            val dilate = expSteps > 0
            repeat(abs(expSteps)) {
                val next = ByteArray(w * h)
                for (y in 0 until h) {
                    for (x in 0 until w) {
                        var extreme = if (dilate) 0 else 255
                        for (dy in -1..1) {
                            val yy = (y + dy).coerceIn(0, h - 1)
                            for (dx in -1..1) {
                                val xx = (x + dx).coerceIn(0, w - 1)
                                val v = current[yy * w + xx].toInt() and 0xFF
                                extreme = if (dilate) max(extreme, v) else min(extreme, v)
                            }
                        }
                        next[y * w + x] = extreme.toByte()
                    }
                }
                current = next
            }
        }

        val rad = featherRadius.roundToInt().coerceIn(0, 16)
        if (rad <= 0) return current

        // Separable horizontal + vertical box blur pass for smooth feathering
        val horiz = ByteArray(w * h)
        val diam = rad * 2 + 1
        for (y in 0 until h) {
            val row = y * w
            var sum = 0
            for (k in -rad..rad) {
                sum += current[row + k.coerceIn(0, w - 1)].toInt() and 0xFF
            }
            for (x in 0 until w) {
                horiz[row + x] = (sum / diam).toByte()
                val removeIdx = (x - rad).coerceIn(0, w - 1)
                val addIdx = (x + rad + 1).coerceIn(0, w - 1)
                sum += (current[row + addIdx].toInt() and 0xFF) - (current[row + removeIdx].toInt() and 0xFF)
            }
        }
        val vert = ByteArray(w * h)
        for (x in 0 until w) {
            var sum = 0
            for (k in -rad..rad) {
                sum += horiz[k.coerceIn(0, h - 1) * w + x].toInt() and 0xFF
            }
            for (y in 0 until h) {
                vert[y * w + x] = (sum / diam).toByte()
                val removeY = (y - rad).coerceIn(0, h - 1)
                val addY = (y + rad + 1).coerceIn(0, h - 1)
                sum += (horiz[addY * w + x].toInt() and 0xFF) - (horiz[removeY * w + x].toInt() and 0xFF)
            }
        }
        return vert
    }

    private fun extractNormalizedContour(alpha: ByteArray, w: Int, h: Int): List<Pair<Float, Float>> {
        val pts = ArrayList<Pair<Float, Float>>()
        val step = max(1, min(w, h) / 32)
        for (y in 1 until h - 1 step step) {
            for (x in 1 until w - 1 step step) {
                val cur = (alpha[y * w + x].toInt() and 0xFF) >= 128
                if (!cur) continue
                val edge = ((alpha[y * w + (x - 1)].toInt() and 0xFF) < 128) ||
                    ((alpha[y * w + (x + 1)].toInt() and 0xFF) < 128) ||
                    ((alpha[(y - 1) * w + x].toInt() and 0xFF) < 128) ||
                    ((alpha[(y + 1) * w + x].toInt() and 0xFF) < 128)
                if (edge) {
                    pts.add((x.toFloat() / w.toFloat()) to (y.toFloat() / h.toFloat()))
                }
            }
        }
        return pts
    }

    private fun computeSha256Hex(file: File): String {
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
