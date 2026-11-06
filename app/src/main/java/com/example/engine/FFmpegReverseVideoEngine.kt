package com.example.engine

import android.content.Context
import android.net.Uri
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import com.example.data.model.Layer
import com.example.data.model.LayerType
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.max
import kotlin.math.min

/**
 * Optimized FFmpegKit Reverse Video & Audio Transcode Proxy Engine (`FFmpegReverseVideoEngine`):
 * - Uses `com.arthenica.ffmpegkit.FFmpegKit` to transcode reversed video clips (or the active trimmed
 *   source window of long clips) into fast-decode, short-GOP (`-g 10`) reversed MP4 proxies on disk.
 * - Once cached, reversed video layers stream **strictly forward** at 60fps via `ExoPlayer -> GL_TEXTURE_EXTERNAL_OES`
 *   in live preview AND via `GlSequentialVideoSession` (`MediaExtractor + MediaCodec`) during Pre-Render & Export —
 *   completely eliminating backward GOP keyframe thrashing!
 * - Deduplicates concurrent FFmpeg transcode jobs per cache key and enforces a bounded disk LRU cache.
 */
object FFmpegReverseVideoEngine {

    data class ReversedProxyMedia(
        val cacheKey: String,
        val filePath: String,
        val fileUri: String,
        val sourceDurationMs: Long,
        val windowStartSourceMs: Long,
        val windowEndSourceMs: Long,
        val proxyDurationMs: Long,
        val maxEdgePx: Int,
        val hasReversedAudio: Boolean
    )

    private const val CACHE_DIR_NAME = "ffmpeg_reversed_proxies"
    private const val TEMP_INPUT_DIR_NAME = "ffmpeg_temp_inputs"
    private const val FULL_CLIP_THRESHOLD_MS = 22_000L
    private const val MAX_DISK_CACHE_BYTES = 180L * 1024L * 1024L

    private val engineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val readyProxiesByKey = ConcurrentHashMap<String, ReversedProxyMedia>()
    private val readyProxyKeyByLayerId = ConcurrentHashMap<String, String>()
    private val inflightJobs = ConcurrentHashMap<String, CompletableDeferred<ReversedProxyMedia?>>()
    private val failedKeys = ConcurrentHashMap.newKeySet<String>()

    /**
     * Computes the source window `[windowStartSourceMs, windowEndSourceMs]` needed for reversing [layer].
     * - For clips `<= 22s`, reverses the full `[0 .. sourceDurationMs]` clip once so re-trimming reuses the same proxy!
     * - For long videos (`> 22s`), trims only the active reversed source span (+/- 500ms safety margin) before FFmpeg `reverse`,
     *   keeping memory low and transcoding in a fraction of a second!
     */
    fun computeOptimalReverseWindow(layer: Layer): Pair<Long, Long> {
        val safeDur = layer.sourceDurationMs.coerceAtLeast(200L)
        if (safeDur <= FULL_CLIP_THRESHOLD_MS) {
            return 0L to safeDur
        }
        val clipTimelineSpanMs = (layer.endTimeMs - layer.startTimeMs).coerceAtLeast(100L)
        val spd = layer.speedMultiplier.coerceIn(0.05f, 8.0f)
        // At playhead = startTimeMs: wrapped = sourceOffsetMs -> forwardSource = safeDur - sourceOffsetMs
        // At playhead = endTimeMs: wrapped = sourceOffsetMs + clipSpan * spd -> forwardSource = safeDur - wrapped
        val maxWrapped = (layer.sourceOffsetMs + (clipTimelineSpanMs * spd).toLong()).coerceIn(0L, safeDur)
        val minWrapped = layer.sourceOffsetMs.coerceIn(0L, safeDur)
        val earliestForwardMs = (safeDur - maxWrapped - 500L).coerceIn(0L, safeDur)
        val latestForwardMs = (safeDur - minWrapped + 500L).coerceIn(
            (earliestForwardMs + 100L).coerceAtMost(safeDur),
            safeDur
        )
        return earliestForwardMs to latestForwardMs
    }

    /**
     *Deterministic cache key incorporating source URI, duration, reversed source window, and target resolution tier.
     */
    fun buildProxyCacheKey(
        mediaUri: String,
        sourceDurationMs: Long,
        windowStartSourceMs: Long,
        windowEndSourceMs: Long,
        maxEdgePx: Int
    ): String {
        val tierEdge = normalizeQualityEdge(maxEdgePx)
        val raw = "$mediaUri|$sourceDurationMs|$windowStartSourceMs|$windowEndSourceMs|$tierEdge|v1"
        val digest = MessageDigest.getInstance("SHA-256").digest(raw.toByteArray(Charsets.UTF_8))
        return digest.take(12).joinToString("") { "%02x".format(it) }
    }

    /**
     * Maps a forward source timestamp (`forwardSourceMs` returned by `AnimationEvaluator.evaluateSourceMediaTimeMs`)
     * into the corresponding **forward** playback timestamp inside the FFmpeg-reversed proxy MP4!
     *
     * Proof:
     * - FFmpeg trimmed `[W_start .. W_end]` and reversed it, so:
     *   - proxy `t = 0` contains original frame `W_end`
     *   - proxy `t = (W_end - W_start)` contains original frame `W_start`
     * - Therefore original frame `forwardSourceMs` sits at `proxyPosMs = W_end - forwardSourceMs`,
     *   which increases monotonically as `playheadMs` moves forward!
     */
    fun mapForwardSourceMsToReversedProxyMs(forwardSourceMs: Long, proxy: ReversedProxyMedia): Long {
        val clampedForward = forwardSourceMs.coerceIn(proxy.windowStartSourceMs, proxy.windowEndSourceMs)
        return (proxy.windowEndSourceMs - clampedForward).coerceIn(0L, proxy.proxyDurationMs)
    }

    /**
     * Inverse mapping from the reversed proxy's hardware `ExoPlayer.currentPosition` (`proxyPosMs`)
     * back to the original forward source timestamp (`forwardSourceMs`) for master clock lip-sync!
     */
    fun mapReversedProxyMsToForwardSourceMs(proxyPosMs: Long, proxy: ReversedProxyMedia): Long {
        val clampedProxy = proxyPosMs.coerceIn(0L, proxy.proxyDurationMs)
        return (proxy.windowEndSourceMs - clampedProxy).coerceIn(proxy.windowStartSourceMs, proxy.windowEndSourceMs)
    }

    /**
     * Non-blocking O(1) lookup used by `HardwareVideoPreviewEngine`, `TimelineAudioController`, and `FramePollutionGlRenderer`.
     */
    fun getReadyReversedProxy(layer: Layer): ReversedProxyMedia? {
        if (layer.type != LayerType.VIDEO || !layer.reversed || layer.mediaUri.isBlank()) return null
        val (winStart, winEnd) = computeOptimalReverseWindow(layer)
        // Check exact layer-bound proxy first if it covers the required source window
        readyProxyKeyByLayerId[layer.id]?.let { key ->
            val cached = readyProxiesByKey[key]
            if (cached != null &&
                cached.windowStartSourceMs <= winStart &&
                cached.windowEndSourceMs >= winEnd &&
                File(cached.filePath).exists()
            ) {
                return cached
            }
        }
        // Check any resolution tier in memory (`720` or `1080`) that covers `[winStart .. winEnd]`
        for (edge in intArrayOf(1080, 720, 540)) {
            val key = buildProxyCacheKey(layer.mediaUri, layer.sourceDurationMs.coerceAtLeast(200L), winStart, winEnd, edge)
            val hit = readyProxiesByKey[key]
            if (hit != null && File(hit.filePath).exists()) {
                readyProxyKeyByLayerId[layer.id] = key
                return hit
            }
        }
        return null
    }

    /**
     * Launches background FFmpegKit reverse proxy generation without blocking UI or resetting playhead!
     */
    fun prepareReversedProxyAsync(
        context: Context,
        layer: Layer,
        maxEdgePx: Int = 720,
        onReady: ((ReversedProxyMedia) -> Unit)? = null
    ) {
        if (layer.type != LayerType.VIDEO || !layer.reversed || layer.mediaUri.isBlank()) return
        if (MediaEngine.isUriUnreachable(layer.mediaUri)) return
        val existing = getReadyReversedProxy(layer)
        if (existing != null && existing.maxEdgePx >= normalizeQualityEdge(maxEdgePx)) {
            onReady?.invoke(existing)
            return
        }
        val appCtx = context.applicationContext
        engineScope.launch {
            val built = ensureReversedProxy(appCtx, layer, maxEdgePx)
            if (built != null) {
                onReady?.invoke(built)
            }
        }
    }

    /**
     * Suspending (and thread-safe deduplicated) FFmpegKit reverse transcode entry point used by:
     * 1. Background live-preview preparation (`prepareReversedProxyAsync`)
     * 2. `OffscreenGlPreRenderEngine` (so Pre-Render decodes reversed video in 1 forward hardware pass!)
     * 3. `ExportPipelineEngine` (so MP4 export decodes reversed video in 1 forward hardware pass!)
     */
    suspend fun ensureReversedProxy(
        context: Context,
        layer: Layer,
        maxEdgePx: Int = 720
    ): ReversedProxyMedia? = withContext(Dispatchers.IO) {
        ensureReversedProxyBlocking(context.applicationContext, layer, maxEdgePx)
    }

    fun ensureReversedProxyBlocking(
        context: Context,
        layer: Layer,
        maxEdgePx: Int = 720
    ): ReversedProxyMedia? {
        if (layer.type != LayerType.VIDEO || !layer.reversed || layer.mediaUri.isBlank()) return null
        if (MediaEngine.isUriUnreachable(layer.mediaUri)) return null

        val appCtx = context.applicationContext
        val safeDur = layer.sourceDurationMs.coerceAtLeast(200L)
        val (winStart, winEnd) = computeOptimalReverseWindow(layer)
        val tierEdge = normalizeQualityEdge(maxEdgePx)
        val cacheKey = buildProxyCacheKey(layer.mediaUri, safeDur, winStart, winEnd, tierEdge)
        if (cacheKey in failedKeys) return null

        val cacheDir = File(appCtx.cacheDir, CACHE_DIR_NAME).apply { mkdirs() }
        val targetFile = File(cacheDir, "rev_$cacheKey.mp4")

        readyProxiesByKey[cacheKey]?.let { memHit ->
            if (File(memHit.filePath).let { it.exists() && it.length() > 512L }) {
                readyProxyKeyByLayerId[layer.id] = cacheKey
                return memHit
            }
        }

        if (targetFile.exists() && targetFile.length() > 512L) {
            targetFile.setLastModified(System.currentTimeMillis())
            val diskHit = ReversedProxyMedia(
                cacheKey = cacheKey,
                filePath = targetFile.absolutePath,
                fileUri = Uri.fromFile(targetFile).toString(),
                sourceDurationMs = safeDur,
                windowStartSourceMs = winStart,
                windowEndSourceMs = winEnd,
                proxyDurationMs = (winEnd - winStart).coerceAtLeast(100L),
                maxEdgePx = tierEdge,
                hasReversedAudio = true
            )
            readyProxiesByKey[cacheKey] = diskHit
            readyProxyKeyByLayerId[layer.id] = cacheKey
            return diskHit
        }

        val ownDeferred = CompletableDeferred<ReversedProxyMedia?>()
        val existingDeferred = inflightJobs.putIfAbsent(cacheKey, ownDeferred)
        if (existingDeferred != null) {
            return runCatching {
                kotlinx.coroutines.runBlocking { existingDeferred.await() }
            }.getOrNull()
        }

        var tempInputFile: File? = null
        try {
            val resolvedInput = resolveLocalInputPath(appCtx, layer.mediaUri).also { pair ->
                if (pair?.second == true) tempInputFile = File(pair.first)
            } ?: run {
                ownDeferred.complete(null)
                return null
            }

            val inputPath = resolvedInput.first
            val tempOutFile = File(cacheDir, "rev_${cacheKey}_tmp.mp4")
            if (tempOutFile.exists()) tempOutFile.delete()

            val trimArgs = if (winStart > 0L || winEnd < safeDur) {
                val ssSec = String.format(Locale.US, "%.3f", winStart / 1000.0)
                val durSec = String.format(Locale.US, "%.3f", max(0.1, (winEnd - winStart) / 1000.0))
                "-ss $ssSec -t $durSec "
            } else {
                ""
            }

            // Scale down to bounded even dimensions while preserving aspect ratio, then reverse frames in RAM
            val vfChain = "scale='trunc(min(iw,iw*min($tierEdge/iw,$tierEdge/ih))/2)*2':'trunc(min(ih,ih*min($tierEdge/iw,$tierEdge/ih))/2)*2',reverse"

            val escapedIn = escapeFFmpegPath(inputPath)
            val escapedOut = escapeFFmpegPath(tempOutFile.absolutePath)

            // Attempt 1: Reverse both video and audio (`-vf ... -af areverse`) using universal ultra-fast short-GOP MP4
            val cmdWithAudio = "-y -hide_banner -loglevel error $trimArgs-i \"$escapedIn\" " +
                "-vf \"$vfChain\" -af \"areverse\" " +
                "-c:v mpeg4 -q:v 3 -g 10 -pix_fmt yuv420p " +
                "-c:a aac -b:a 160k -movflags +faststart \"$escapedOut\""

            var hasAudio = true
            var succeeded = executeFFmpegCommandSafe(cmdWithAudio) &&
                tempOutFile.exists() && tempOutFile.length() > 512L

            if (!succeeded) {
                if (tempOutFile.exists()) tempOutFile.delete()
                // Attempt 2: Video-only reverse (`-an`) for clips without an audio stream or unsupported audio tracks
                hasAudio = false
                val cmdVideoOnly = "-y -hide_banner -loglevel error $trimArgs-i \"$escapedIn\" " +
                    "-vf \"$vfChain\" -an " +
                    "-c:v mpeg4 -q:v 3 -g 10 -pix_fmt yuv420p " +
                    "-movflags +faststart \"$escapedOut\""
                succeeded = executeFFmpegCommandSafe(cmdVideoOnly) &&
                    tempOutFile.exists() && tempOutFile.length() > 512L
            }

            if (!succeeded) {
                if (tempOutFile.exists()) tempOutFile.delete()
                failedKeys.add(cacheKey)
                ownDeferred.complete(null)
                return null
            }

            if (targetFile.exists()) targetFile.delete()
            val moved = tempOutFile.renameTo(targetFile)
            val finalFile = if (moved) targetFile else tempOutFile
            trimDiskCacheIfNeeded(cacheDir, keepFile = finalFile)

            val proxy = ReversedProxyMedia(
                cacheKey = cacheKey,
                filePath = finalFile.absolutePath,
                fileUri = Uri.fromFile(finalFile).toString(),
                sourceDurationMs = safeDur,
                windowStartSourceMs = winStart,
                windowEndSourceMs = winEnd,
                proxyDurationMs = (winEnd - winStart).coerceAtLeast(100L),
                maxEdgePx = tierEdge,
                hasReversedAudio = hasAudio
            )
            readyProxiesByKey[cacheKey] = proxy
            readyProxyKeyByLayerId[layer.id] = cacheKey
            ownDeferred.complete(proxy)
            return proxy
        } catch (_: Throwable) {
            ownDeferred.complete(null)
            return null
        } finally {
            inflightJobs.remove(cacheKey)
            tempInputFile?.let { runCatching { it.delete() } }
        }
    }

    fun clearAllCaches(context: Context? = null) {
        readyProxiesByKey.clear()
        readyProxyKeyByLayerId.clear()
        failedKeys.clear()
        context?.applicationContext?.let { appCtx ->
            runCatching {
                File(appCtx.cacheDir, CACHE_DIR_NAME).deleteRecursively()
                File(appCtx.cacheDir, TEMP_INPUT_DIR_NAME).deleteRecursively()
            }
        }
    }

    private fun normalizeQualityEdge(maxEdgePx: Int): Int = when {
        maxEdgePx >= 960 -> 1080
        maxEdgePx >= 640 -> 720
        else -> 540
    }

    private fun escapeFFmpegPath(path: String): String = path.replace("\"", "\\\"")

    /**
     * Executes FFmpegKit synchronously on IO worker, catching `UnsatisfiedLinkError` / `NoClassDefFoundError`
     * cleanly on headless JVM Robolectric environments where Android `.so` binaries are absent.
     */
    private fun executeFFmpegCommandSafe(command: String): Boolean {
        return try {
            val session = FFmpegKit.execute(command)
            ReturnCode.isSuccess(session.returnCode)
        } catch (_: UnsatisfiedLinkError) {
            false
        } catch (_: NoClassDefFoundError) {
            false
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * Resolves `(localFilePath, ownsTempCopy)` from `file://`, raw path, or `content://` URI.
     */
    private fun resolveLocalInputPath(context: Context, rawMediaUri: String): Pair<String, Boolean>? {
        val playableStr = MediaEngine.resolvePlayableUriString(rawMediaUri)
        val parsed = runCatching { Uri.parse(playableStr) }.getOrNull()
        if (parsed != null && (parsed.scheme == "file" || parsed.scheme.isNullOrBlank())) {
            val p = parsed.path ?: playableStr
            val f = File(p)
            if (f.exists() && f.length() > 0L) {
                return f.absolutePath to false
            }
        }
        val directFile = File(playableStr)
        if (directFile.exists() && directFile.length() > 0L) {
            return directFile.absolutePath to false
        }

        // Copy `content://` stream once to a temporary input file for FFmpeg native demuxer
        val uriObj = parsed ?: Uri.parse(rawMediaUri)
        val tempDir = File(context.cacheDir, TEMP_INPUT_DIR_NAME).apply { mkdirs() }
        val tempFile = File(tempDir, "in_${System.nanoTime()}.mp4")
        val copied = runCatching {
            context.contentResolver.openInputStream(uriObj)?.use { input ->
                FileOutputStream(tempFile).use { output ->
                    input.copyTo(output, bufferSize = 256 * 1024)
                }
            } != null && tempFile.exists() && tempFile.length() > 0L
        }.getOrDefault(false)

        return if (copied) {
            tempFile.absolutePath to true
        } else {
            runCatching { tempFile.delete() }
            null
        }
    }

    private fun trimDiskCacheIfNeeded(cacheDir: File, keepFile: File) {
        runCatching {
            val files = cacheDir.listFiles()?.filter { it.isFile && it.name.endsWith(".mp4") } ?: return
            var totalBytes = files.sumOf { it.length() }
            if (totalBytes <= MAX_DISK_CACHE_BYTES) return
            val evictOrder = files
                .filter { it.absolutePath != keepFile.absolutePath }
                .sortedBy { it.lastModified() }
            for (f in evictOrder) {
                if (totalBytes <= (MAX_DISK_CACHE_BYTES * 3L) / 4L) break
                val len = f.length()
                if (f.delete()) {
                    totalBytes = min(totalBytes, max(0L, totalBytes - len))
                }
            }
        }
    }
}
