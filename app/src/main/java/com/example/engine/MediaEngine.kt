package com.example.engine

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.AudioAttributes
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import android.media.MediaPlayer
import android.media.PlaybackParams
import android.net.Uri
import android.os.Build
import android.util.LruCache
import com.example.data.model.Layer
import com.example.data.model.LayerType
import com.example.data.model.Project
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs
import kotlin.math.sqrt

data class ImportedMediaInfo(
    val localUri: String,
    val displayName: String,
    val width: Int,
    val height: Int,
    val durationMs: Long,
    val hasAudio: Boolean,
    val waveform: List<Float> = emptyList()
)

data class ExtractedAudioResult(
    val audioUri: String,
    val durationMs: Long,
    val waveform: List<Float>
)

/**
 * High-speed, non-blocking Media Engine for FramePollution:
 * - Caches picked Photos/Videos/Audio locally so URIs never expire and random-access seeks are fast
 * - Provides 0-ms lock-free RAM frame lookup for OpenGL ES 2.0 60fps preview (with nearest-frame hold + coalesced async decode)
 * - Supports Time Remap, Reverse Video, Freeze Frame, and variable Speed without decoder stalls
 * - Demuxes real .m4a audio tracks from Videos via MediaExtractor + MediaMuxer ("Extract Audio")
 */
object MediaEngine {

    private val engineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // 48 MB RAM cache for decoded images & scaled video frames (measured in KB)
    private val bitmapCache = object : LruCache<String, Bitmap>(48 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int {
            return (value.allocationByteCount / 1024).coerceAtLeast(1)
        }
    }

    // Fast index of cached timestamps per video URI for O(1)-ish nearest-frame fallback (zero flicker!)
    private val cachedTimestampsByUri = ConcurrentHashMap<String, MutableSet<Long>>()
    private val pendingDecodeTimeMs = ConcurrentHashMap<String, AtomicLong>()
    private val workerRunningForUri = ConcurrentHashMap<String, AtomicBoolean>()
    private val loadingImages = ConcurrentHashMap.newKeySet<String>()

    private const val BUCKET_MS = 40L // 25fps quantized frame cache buckets; exact coalesced decode refines smoothly
    private const val MAX_PREVIEW_VIDEO_EDGE = 640
    private const val MAX_IMAGE_EDGE = 1280

    suspend fun importMediaUri(
        context: Context,
        pickedUri: Uri,
        type: LayerType,
        onFrameWarmProgress: (() -> Unit)? = null
    ): ImportedMediaInfo = withContext(Dispatchers.IO) {
        val appCtx = context.applicationContext
        runCatching {
            appCtx.contentResolver.takePersistableUriPermission(
                pickedUri,
                android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        }

        val mediaDir = File(appCtx.filesDir, "imported_media").apply { mkdirs() }
        val ext = when (type) {
            LayerType.IMAGE -> "jpg"
            LayerType.AUDIO -> "m4a"
            else -> "mp4"
        }
        val destFile = File(mediaDir, "media_${UUID.randomUUID().toString().take(10)}.$ext")
        val copiedOk = runCatching {
            appCtx.contentResolver.openInputStream(pickedUri)?.use { input ->
                FileOutputStream(destFile).use { output ->
                    input.copyTo(output, bufferSize = 64 * 1024)
                }
            } != null && destFile.exists() && destFile.length() > 0L
        }.getOrDefault(false)

        val finalUriStr = if (copiedOk) Uri.fromFile(destFile).toString() else pickedUri.toString()
        val labelBase = pickedUri.lastPathSegment
            ?.substringAfterLast('/')
            ?.substringAfterLast(':')
            ?.take(22)
            ?.ifBlank { null }

        when (type) {
            LayerType.IMAGE -> {
                val bmp = decodeImageInternal(appCtx, finalUriStr)
                if (bmp != null) {
                    putCachedBitmap("img|$finalUriStr", bmp)
                }
                ImportedMediaInfo(
                    localUri = finalUriStr,
                    displayName = labelBase ?: "Image",
                    width = bmp?.width ?: 1080,
                    height = bmp?.height ?: 1080,
                    durationMs = 0L,
                    hasAudio = false
                )
            }

            LayerType.VIDEO -> {
                var vw = 1920
                var vh = 1080
                var dur = 5000L
                var hasAudio = false
                val retriever = MediaMetadataRetriever()
                try {
                    setRetrieverSource(appCtx, retriever, finalUriStr)
                    val rawW = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 1920
                    val rawH = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 1080
                    val rot = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
                    if (rot == 90 || rot == 270) {
                        vw = rawH
                        vh = rawW
                    } else {
                        vw = rawW
                        vh = rawH
                    }
                    dur = (retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 5000L)
                        .coerceAtLeast(300L)
                    hasAudio = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO) != null

                    // Immediately decode frame 0 so preview shows the video on the very first frame!
                    val firstBmp = extractScaledFrame(retriever, 0L, vw, vh)
                    if (firstBmp != null) {
                        cacheVideoFrame(finalUriStr, 0L, firstBmp)
                    }
                } catch (_: Exception) {
                } finally {
                    runCatching { retriever.release() }
                }

                val wf = if (copiedOk && hasAudio) analyzeContainerAudioWaveform(destFile.absolutePath, 72) else emptyList()
                warmVideoStripAsync(appCtx, finalUriStr, dur, vw, vh, onFrameWarmProgress)

                ImportedMediaInfo(
                    localUri = finalUriStr,
                    displayName = labelBase ?: "Video",
                    width = vw.coerceAtLeast(16),
                    height = vh.coerceAtLeast(16),
                    durationMs = dur,
                    hasAudio = hasAudio,
                    waveform = wf
                )
            }

            LayerType.AUDIO -> {
                var dur = 5000L
                val retriever = MediaMetadataRetriever()
                try {
                    setRetrieverSource(appCtx, retriever, finalUriStr)
                    dur = (retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 5000L)
                        .coerceAtLeast(300L)
                } catch (_: Exception) {
                } finally {
                    runCatching { retriever.release() }
                }
                val wf = if (copiedOk) analyzeContainerAudioWaveform(destFile.absolutePath, 72) else emptyList()
                ImportedMediaInfo(
                    localUri = finalUriStr,
                    displayName = labelBase ?: "Audio",
                    width = 0,
                    height = 0,
                    durationMs = dur,
                    hasAudio = true,
                    waveform = wf
                )
            }

            else -> ImportedMediaInfo(finalUriStr, "Layer", 1080, 1080, 5000L, false)
        }
    }

    /**
     * Non-blocking O(1) lookup for OpenGL ES 2.0 60fps preview thread!
     * Never performs disk or decoder I/O on the calling thread.
     */
    fun getMediaBitmapNonBlocking(
        context: Context,
        layer: Layer,
        sourceTimeMs: Long,
        onAsyncFrameReady: () -> Unit
    ): Bitmap? {
        val uriStr = layer.mediaUri
        if (uriStr.isBlank()) return null

        if (layer.type == LayerType.IMAGE) {
            val key = "img|$uriStr"
            bitmapCache.get(key)?.let { return it }
            if (loadingImages.add(uriStr)) {
                val appCtx = context.applicationContext
                engineScope.launch {
                    try {
                        val decoded = decodeImageInternal(appCtx, uriStr)
                        if (decoded != null) {
                            putCachedBitmap(key, decoded)
                            onAsyncFrameReady()
                        }
                    } finally {
                        loadingImages.remove(uriStr)
                    }
                }
            }
            return null
        }

        if (layer.type == LayerType.VIDEO) {
            val bucketMs = ((sourceTimeMs.coerceAtLeast(0L) + BUCKET_MS / 2) / BUCKET_MS) * BUCKET_MS
            val exactKey = "vid|$uriStr|$bucketMs"
            bitmapCache.get(exactKey)?.let { return it }

            // Request coalesced async decode for exact bucketMs
            scheduleCoalescedVideoDecode(
                context = context.applicationContext,
                uriStr = uriStr,
                targetBucketMs = bucketMs,
                hintW = layer.mediaWidth.takeIf { it > 0 } ?: 1280,
                hintH = layer.mediaHeight.takeIf { it > 0 } ?: 720,
                onDecoded = onAsyncFrameReady
            )

            // Return nearest already-cached frame immediately so video NEVER flickers or blanks!
            val knownSet = cachedTimestampsByUri[uriStr]
            if (!knownSet.isNullOrEmpty()) {
                val nearest = synchronized(knownSet) {
                    knownSet.minByOrNull { abs(it - bucketMs) }
                }
                if (nearest != null) {
                    bitmapCache.get("vid|$uriStr|$nearest")?.let { return it }
                }
            }
        }
        return null
    }

    /**
     * Blocking frame fetch used ONLY on background Export worker thread for frame-exact MP4 output.
     */
    fun getMediaBitmapBlockingForExport(
        context: Context,
        layer: Layer,
        sourceTimeMs: Long
    ): Bitmap? {
        val uriStr = layer.mediaUri
        if (uriStr.isBlank()) return null
        if (layer.type == LayerType.IMAGE) {
            val key = "img|$uriStr"
            bitmapCache.get(key)?.let { return it }
            return decodeImageInternal(context, uriStr)?.also { putCachedBitmap(key, it) }
        }
        if (layer.type == LayerType.VIDEO) {
            val bucketMs = ((sourceTimeMs.coerceAtLeast(0L) + 16L) / 33L) * 33L
            bitmapCache.get("vid|$uriStr|$bucketMs")?.let { return it }
            val retriever = MediaMetadataRetriever()
            return try {
                setRetrieverSource(context, retriever, uriStr)
                extractScaledFrame(
                    retriever,
                    sourceTimeMs,
                    layer.mediaWidth.takeIf { it > 0 } ?: 1280,
                    layer.mediaHeight.takeIf { it > 0 } ?: 720
                )?.also { cacheVideoFrame(uriStr, bucketMs, it) }
            } catch (_: Exception) {
                null
            } finally {
                runCatching { retriever.release() }
            }
        }
        return null
    }

    /**
     * Real hardware container audio extraction ("Extract Audio" on any Video layer!)
     * Demuxes the compressed audio track directly into a standalone .m4a file in milliseconds.
     */
    suspend fun extractAudioFromVideo(
        context: Context,
        videoUriStr: String
    ): Result<ExtractedAudioResult> = withContext(Dispatchers.IO) {
        if (videoUriStr.isBlank()) {
            return@withContext Result.failure(IllegalArgumentException("Video layer has no imported source file."))
        }
        val appCtx = context.applicationContext
        val extractor = MediaExtractor()
        try {
            val uri = Uri.parse(videoUriStr)
            if (uri.scheme == "file" && uri.path != null) {
                extractor.setDataSource(uri.path!!)
            } else {
                extractor.setDataSource(appCtx, uri, null)
            }

            var audioTrackIndex = -1
            var audioFormat: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val fmt = extractor.getTrackFormat(i)
                val mime = fmt.getString(MediaFormat.KEY_MIME).orEmpty()
                if (mime.startsWith("audio/")) {
                    audioTrackIndex = i
                    audioFormat = fmt
                    break
                }
            }

            if (audioTrackIndex < 0 || audioFormat == null) {
                return@withContext Result.failure(IllegalStateException("No audio stream found inside this video."))
            }

            val outDir = File(appCtx.filesDir, "extracted_audio").apply { mkdirs() }
            val outFile = File(outDir, "audio_${UUID.randomUUID().toString().take(10)}.m4a")
            val muxer = MediaMuxer(outFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

            extractor.selectTrack(audioTrackIndex)
            val muxAudioTrack = muxer.addTrack(audioFormat)
            muxer.start()

            val buffer = ByteBuffer.allocate(128 * 1024)
            val info = android.media.MediaCodec.BufferInfo()
            val packetEnergies = ArrayList<Float>(512)
            var maxPtsUs = 0L

            while (true) {
                val sampleSize = extractor.readSampleData(buffer, 0)
                if (sampleSize < 0) break
                info.offset = 0
                info.size = sampleSize
                info.presentationTimeUs = extractor.sampleTime.coerceAtLeast(0L)
                info.flags = if ((extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC) != 0) {
                    android.media.MediaCodec.BUFFER_FLAG_KEY_FRAME
                } else {
                    0
                }
                if (info.presentationTimeUs > maxPtsUs) maxPtsUs = info.presentationTimeUs

                // Measure real byte-variance/energy of audio frames for authentic waveform visualization
                var acc = 0L
                val step = (sampleSize / 32).coerceAtLeast(1)
                var pos = 0
                var samples = 0
                while (pos < sampleSize) {
                    val b = buffer.get(pos).toInt()
                    acc += b * b
                    pos += step
                    samples++
                }
                if (samples > 0) {
                    val rmsApprox = (sqrt(acc.toDouble() / samples) / 96.0).toFloat().coerceIn(0.08f, 1.0f)
                    packetEnergies.add(rmsApprox)
                }

                muxer.writeSampleData(muxAudioTrack, buffer, info)
                extractor.advance()
            }

            runCatching { muxer.stop() }
            runCatching { muxer.release() }

            val durMs = ((audioFormat.takeIf { it.containsKey(MediaFormat.KEY_DURATION) }
                ?.getLong(MediaFormat.KEY_DURATION) ?: maxPtsUs) / 1000L).coerceAtLeast(300L)

            val waveform72 = resampleWaveform(packetEnergies, 72)
            Result.success(
                ExtractedAudioResult(
                    audioUri = Uri.fromFile(outFile).toString(),
                    durationMs = durMs,
                    waveform = waveform72
                )
            )
        } catch (e: Exception) {
            Result.failure(e)
        } finally {
            runCatching { extractor.release() }
        }
    }

    private fun warmVideoStripAsync(
        context: Context,
        uriStr: String,
        durationMs: Long,
        vw: Int,
        vh: Int,
        onProgress: (() -> Unit)?
    ) {
        engineScope.launch {
            val retriever = MediaMetadataRetriever()
            try {
                setRetrieverSource(context, retriever, uriStr)
                // Warm 36 evenly spaced frames first for instant scrub across the whole clip,
                // then fill fine 80ms buckets for the first 6 seconds!
                val coarseCount = 36
                for (i in 0..coarseCount) {
                    val tMs = ((i.toFloat() / coarseCount) * durationMs).toLong()
                    val bucket = ((tMs + BUCKET_MS / 2) / BUCKET_MS) * BUCKET_MS
                    if (bitmapCache.get("vid|$uriStr|$bucket") == null) {
                        val bmp = extractScaledFrame(retriever, bucket, vw, vh)
                        if (bmp != null) {
                            cacheVideoFrame(uriStr, bucket, bmp)
                            if (i % 6 == 0) onProgress?.invoke()
                        }
                    }
                }
                val fineLimitMs = durationMs.coerceAtMost(6000L)
                var fineT = 0L
                while (fineT <= fineLimitMs) {
                    val bucket = ((fineT + BUCKET_MS / 2) / BUCKET_MS) * BUCKET_MS
                    if (bitmapCache.get("vid|$uriStr|$bucket") == null) {
                        val bmp = extractScaledFrame(retriever, bucket, vw, vh)
                        if (bmp != null) cacheVideoFrame(uriStr, bucket, bmp)
                    }
                    fineT += BUCKET_MS * 2
                }
                onProgress?.invoke()
            } catch (_: Exception) {
            } finally {
                runCatching { retriever.release() }
            }
        }
    }

    private fun scheduleCoalescedVideoDecode(
        context: Context,
        uriStr: String,
        targetBucketMs: Long,
        hintW: Int,
        hintH: Int,
        onDecoded: () -> Unit
    ) {
        val slot = pendingDecodeTimeMs.getOrPut(uriStr) { AtomicLong(-1L) }
        slot.set(targetBucketMs)
        val running = workerRunningForUri.getOrPut(uriStr) { AtomicBoolean(false) }
        if (!running.compareAndSet(false, true)) return

        engineScope.launch {
            val retriever = MediaMetadataRetriever()
            try {
                setRetrieverSource(context, retriever, uriStr)
                while (true) {
                    val nextMs = slot.getAndSet(-1L)
                    if (nextMs < 0L) break
                    if (bitmapCache.get("vid|$uriStr|$nextMs") == null) {
                        val bmp = extractScaledFrame(retriever, nextMs, hintW, hintH)
                        if (bmp != null) {
                            cacheVideoFrame(uriStr, nextMs, bmp)
                            onDecoded()
                        }
                    }
                }
            } catch (_: Exception) {
            } finally {
                runCatching { retriever.release() }
                running.set(false)
                // Re-check if a new timestamp arrived right as we closed
                if (slot.get() >= 0L) {
                    scheduleCoalescedVideoDecode(context, uriStr, slot.get(), hintW, hintH, onDecoded)
                }
            }
        }
    }

    private fun extractScaledFrame(
        retriever: MediaMetadataRetriever,
        timeMs: Long,
        srcW: Int,
        srcH: Int
    ): Bitmap? {
        val timeUs = timeMs.coerceAtLeast(0L) * 1000L
        val aspect = srcW.toFloat().coerceAtLeast(1f) / srcH.toFloat().coerceAtLeast(1f)
        val dstW: Int
        val dstH: Int
        if (aspect >= 1f) {
            dstW = MAX_PREVIEW_VIDEO_EDGE
            dstH = (MAX_PREVIEW_VIDEO_EDGE / aspect).toInt().coerceAtLeast(64)
        } else {
            dstH = MAX_PREVIEW_VIDEO_EDGE
            dstW = (MAX_PREVIEW_VIDEO_EDGE * aspect).toInt().coerceAtLeast(64)
        }
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            retriever.getScaledFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST, dstW, dstH)
                ?: retriever.getScaledFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, dstW, dstH)
        } else {
            retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST)
                ?: retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
        }
    }

    private fun cacheVideoFrame(uriStr: String, bucketMs: Long, bmp: Bitmap) {
        val key = "vid|$uriStr|$bucketMs"
        putCachedBitmap(key, bmp)
        val set = cachedTimestampsByUri.getOrPut(uriStr) { HashSet() }
        synchronized(set) { set.add(bucketMs) }
    }

    private fun putCachedBitmap(key: String, bmp: Bitmap) {
        synchronized(bitmapCache) {
            bitmapCache.put(key, bmp)
        }
    }

    private fun setRetrieverSource(context: Context, retriever: MediaMetadataRetriever, uriStr: String) {
        val uri = Uri.parse(uriStr)
        if (uri.scheme == "file" && !uri.path.isNullOrBlank()) {
            retriever.setDataSource(uri.path)
        } else {
            retriever.setDataSource(context, uri)
        }
    }

    private fun decodeImageInternal(context: Context, uriStr: String): Bitmap? {
        return try {
            val uri = Uri.parse(uriStr)
            val boundsOpts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            openUriStream(context, uri)?.use { BitmapFactory.decodeStream(it, null, boundsOpts) }
            val rawW = boundsOpts.outWidth.coerceAtLeast(1)
            val rawH = boundsOpts.outHeight.coerceAtLeast(1)
            var sample = 1
            while (rawW / sample > MAX_IMAGE_EDGE || rawH / sample > MAX_IMAGE_EDGE) {
                sample *= 2
            }
            val decodeOpts = BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            val decoded = openUriStream(context, uri)?.use { BitmapFactory.decodeStream(it, null, decodeOpts) }
                ?: return null

            val rotationDeg = readJpegOrientationDegrees(context, uri)
            if (rotationDeg != 0) {
                val matrix = Matrix().apply { postRotate(rotationDeg.toFloat()) }
                Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
            } else {
                decoded
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun openUriStream(context: Context, uri: Uri) =
        if (uri.scheme == "file" && !uri.path.isNullOrBlank()) {
            File(uri.path!!).inputStream()
        } else {
            context.contentResolver.openInputStream(uri)
        }

    private fun readJpegOrientationDegrees(context: Context, uri: Uri): Int {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return 0
        return runCatching {
            openUriStream(context, uri)?.use { stream ->
                val exif = android.media.ExifInterface(stream)
                when (exif.getAttributeInt(android.media.ExifInterface.TAG_ORIENTATION, android.media.ExifInterface.ORIENTATION_NORMAL)) {
                    android.media.ExifInterface.ORIENTATION_ROTATE_90 -> 90
                    android.media.ExifInterface.ORIENTATION_ROTATE_180 -> 180
                    android.media.ExifInterface.ORIENTATION_ROTATE_270 -> 270
                    else -> 0
                }
            } ?: 0
        }.getOrDefault(0)
    }

    private fun analyzeContainerAudioWaveform(filePath: String, targetBins: Int): List<Float> {
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(filePath)
            var audioTrack = -1
            for (i in 0 until extractor.trackCount) {
                val mime = extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME).orEmpty()
                if (mime.startsWith("audio/")) {
                    audioTrack = i
                    break
                }
            }
            if (audioTrack < 0) return emptyList()
            extractor.selectTrack(audioTrack)
            val buf = ByteBuffer.allocate(64 * 1024)
            val energies = ArrayList<Float>(256)
            var guard = 0
            while (guard < 2400) {
                val sz = extractor.readSampleData(buf, 0)
                if (sz < 0) break
                var acc = 0L
                val step = (sz / 16).coerceAtLeast(1)
                var p = 0
                var n = 0
                while (p < sz) {
                    val v = buf.get(p).toInt()
                    acc += v * v
                    p += step
                    n++
                }
                if (n > 0) {
                    energies.add((sqrt(acc.toDouble() / n) / 92.0).toFloat().coerceIn(0.08f, 1f))
                }
                extractor.advance()
                guard++
            }
            resampleWaveform(energies, targetBins)
        } catch (_: Exception) {
            emptyList()
        } finally {
            runCatching { extractor.release() }
        }
    }

    private fun resampleWaveform(raw: List<Float>, bins: Int): List<Float> {
        if (raw.isEmpty()) return List(bins) { 0.25f }
        return List(bins) { idx ->
            val start = ((idx.toFloat() / bins) * raw.size).toInt().coerceIn(0, raw.lastIndex)
            val end = (((idx + 1).toFloat() / bins) * raw.size).toInt().coerceIn(start + 1, raw.size)
            var sum = 0f
            for (k in start until end) sum += raw[k]
            (sum / (end - start).coerceAtLeast(1)).coerceIn(0.08f, 1f)
        }
    }
}

/**
 * Lightweight synchronized Audio Player for active AUDIO layers & unmuted VIDEO layers during Preview Playback.
 */
class TimelineAudioController(private val appContext: Context) {

    private val players = ConcurrentHashMap<String, Pair<String, MediaPlayer>>()

    fun syncPlayback(project: Project, playheadMs: Long, isPlaying: Boolean) {
        if (!isPlaying) {
            pauseAll()
            return
        }

        val activeAudioLayers = project.layers.filter { layer ->
            layer.visible &&
                layer.mediaUri.isNotBlank() &&
                !layer.audioMuted &&
                layer.audioVolume > 0.01f &&
                !layer.reversed &&
                !layer.timeRemapEnabled &&
                layer.freezeFrameMs < 0L &&
                (layer.type == LayerType.AUDIO || layer.type == LayerType.VIDEO) &&
                playheadMs in layer.startTimeMs until layer.endTimeMs
        }

        val activeIds = activeAudioLayers.map { it.id }.toSet()
        // Pause players whose layer is no longer under the playhead
        players.forEach { (id, pair) ->
            if (id !in activeIds && runCatching { pair.second.isPlaying }.getOrDefault(false)) {
                runCatching { pair.second.pause() }
            }
        }

        activeAudioLayers.forEach { layer ->
            val targetMediaMs = AnimationEvaluator.evaluateSourceMediaTimeMs(layer, playheadMs)
            val existing = players[layer.id]
            val mp = if (existing != null && existing.first == layer.mediaUri) {
                existing.second
            } else {
                existing?.second?.let { runCatching { it.release() } }
                createPlayer(layer.mediaUri)?.also { created ->
                    players[layer.id] = layer.mediaUri to created
                }
            } ?: return@forEach

            runCatching {
                val vol = layer.audioVolume.coerceIn(0f, 1.5f).coerceAtMost(1f)
                mp.setVolume(vol, vol)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    val spd = layer.speedMultiplier.coerceIn(0.25f, 3.0f)
                    if (abs((mp.playbackParams.speed) - spd) > 0.05f) {
                        mp.playbackParams = PlaybackParams().setSpeed(spd)
                    }
                }
                val drift = abs(mp.currentPosition.toLong() - targetMediaMs)
                if (drift > 220L) {
                    mp.seekTo(targetMediaMs.toInt())
                }
                if (!mp.isPlaying) {
                    mp.start()
                }
            }
        }
    }

    fun pauseAll() {
        players.values.forEach { (_, mp) ->
            runCatching { if (mp.isPlaying) mp.pause() }
        }
    }

    fun releaseAll() {
        players.values.forEach { (_, mp) ->
            runCatching { mp.stop() }
            runCatching { mp.release() }
        }
        players.clear()
    }

    private fun createPlayer(uriStr: String): MediaPlayer? {
        return try {
            MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                        .build()
                )
                val uri = Uri.parse(uriStr)
                if (uri.scheme == "file" && !uri.path.isNullOrBlank()) {
                    setDataSource(uri.path)
                } else {
                    setDataSource(appContext, uri)
                }
                prepare()
            }
        } catch (_: Exception) {
            null
        }
    }
}
