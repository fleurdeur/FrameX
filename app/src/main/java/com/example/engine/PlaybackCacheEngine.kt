package com.example.engine

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Camera
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.util.LruCache
import com.example.data.model.EffectNode
import com.example.data.model.Layer
import com.example.data.model.LayerType
import com.example.data.model.Project
import com.example.data.preferences.PreviewQuality
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

data class PreRenderUiState(
    val isPreRendering: Boolean = false,
    val progress: Float = 0f,
    val cachedFramesCount: Int = 0,
    val totalTargetFrames: Int = 0,
    val cachedSpanFractions: List<Pair<Float, Float>> = emptyList(),
    val cacheRamUsedMb: Float = 0f,
    val cacheRevision: Long = 0L,
    val engineModeLabel: String = "GPU + MediaCodec",
    val throughputFps: Int = 0
)

/**
 * RAM Playback Frame Cache & Composition Pre-Renderer for FramePollution:
 * - Pre-renders composition frames (2D, Text, Shapes, Video/Images, 3D projections, and multi-effect stacks)
 *   into a bounded RAM LruCache at the selected [PreviewQuality].
 * - Provides O(1) lock-free-style frame hits to [FramePollutionGlRenderer] during playback so heavy
 *   effect stacks play back at locked 60fps with zero GPU fragment stalls.
 * - Automatically invalidates stale frames whenever the project revision increments.
 */
object PlaybackCacheEngine {

    private var maxCacheKb: Int = 128 * 1024

    private var frameCache = createLru(maxCacheKb)
    private val cachedBucketsByRev = ConcurrentHashMap<String, MutableSet<Long>>()
    private val cacheHits = AtomicLong(0L)
    private val cacheLookups = AtomicLong(0L)

    @Volatile
    private var activeRevision: Long = 0L

    @Volatile
    private var fullPreRenderCompletedRev: Long = -1L

    @Volatile
    private var fullPreRenderQuality: PreviewQuality? = null

    private val cachedLayerIdsByRev = ConcurrentHashMap<String, Boolean>()

    private val _preRenderState = MutableStateFlow(PreRenderUiState())
    val preRenderState: StateFlow<PreRenderUiState> = _preRenderState.asStateFlow()

    fun isFullCompositionCached(projectRevision: Long, quality: PreviewQuality): Boolean =
        fullPreRenderCompletedRev == projectRevision && fullPreRenderQuality == quality

    fun isLayerOrGroupCached(projectRevision: Long, quality: PreviewQuality, layerId: String): Boolean =
        cachedLayerIdsByRev["$projectRevision|${quality.name}|$layerId"] == true

    private fun createLru(sizeKb: Int): LruCache<String, Bitmap> {
        return object : LruCache<String, Bitmap>(sizeKb.coerceIn(16 * 1024, 2048 * 1024)) {
            override fun sizeOf(key: String, value: Bitmap): Int {
                return (value.allocationByteCount / 1024).coerceAtLeast(1)
            }
        }
    }

    fun configureCacheLimitMb(limitMb: Int) {
        val targetKb = (limitMb.coerceIn(32, 2048)) * 1024
        if (targetKb == maxCacheKb) return
        maxCacheKb = targetKb
        synchronized(this) {
            frameCache.resize(targetKb)
        }
        refreshCacheTelemetry(activeRevision, PreviewQuality.HALF, 10000L, 60)
    }

    fun quantizeFrameMs(timeMs: Long, fps: Int): Long {
        val safeFps = fps.coerceIn(12, 60)
        val stepMs = (1000.0 / safeFps).roundToInt().coerceAtLeast(16).toLong()
        return ((timeMs.coerceAtLeast(0L) + stepMs / 2L) / stepMs) * stepMs
    }

    fun getCachedFrame(
        projectRevision: Long,
        quality: PreviewQuality,
        timeMs: Long,
        fps: Int
    ): Bitmap? {
        cacheLookups.incrementAndGet()
        val bucketMs = quantizeFrameMs(timeMs, fps)
        val key = buildKey(projectRevision, quality, bucketMs)
        val hit = synchronized(this) { frameCache.get(key) }
        if (hit != null && !hit.isRecycled) {
            cacheHits.incrementAndGet()
            return hit
        }
        return null
    }

    fun putCachedFrame(
        projectRevision: Long,
        quality: PreviewQuality,
        bucketMs: Long,
        bitmap: Bitmap
    ) {
        val key = buildKey(projectRevision, quality, bucketMs)
        synchronized(this) {
            frameCache.put(key, bitmap)
        }
        val revQualKey = "$projectRevision|${quality.name}"
        val set = cachedBucketsByRev.getOrPut(revQualKey) { HashSet() }
        synchronized(set) {
            set.add(bucketMs)
        }
    }

    fun getCachedLayerOrGroupFrame(
        projectRevision: Long,
        quality: PreviewQuality,
        layerId: String,
        timeMs: Long,
        fps: Int
    ): Bitmap? {
        cacheLookups.incrementAndGet()
        val bucketMs = quantizeFrameMs(timeMs, fps)
        val key = buildLayerKey(projectRevision, quality, layerId, bucketMs)
        val hit = synchronized(this) { frameCache.get(key) }
        if (hit != null && !hit.isRecycled) {
            cacheHits.incrementAndGet()
            return hit
        }
        return null
    }

    fun putCachedLayerOrGroupFrame(
        projectRevision: Long,
        quality: PreviewQuality,
        layerId: String,
        bucketMs: Long,
        bitmap: Bitmap
    ) {
        val key = buildLayerKey(projectRevision, quality, layerId, bucketMs)
        synchronized(this) {
            frameCache.put(key, bitmap)
        }
    }

    /**
     * Evicts cached composition frames far outside `[centerTimeMs - windowHalfMs .. centerTimeMs + windowHalfMs]`
     * when RAM cache pressure exceeds 80% of [maxCacheKb], keeping playback fast on huge projects.
     */
    fun evictFramesOutsideWindow(
        projectRevision: Long,
        quality: PreviewQuality,
        centerTimeMs: Long,
        windowHalfMs: Long = 8_000L
    ) {
        val currentSizeKb = synchronized(this) { frameCache.size() }
        if (currentSizeKb < (maxCacheKb * 4) / 5) return
        val revQualKey = "$projectRevision|${quality.name}"
        val set = cachedBucketsByRev[revQualKey] ?: return
        val staleBuckets = synchronized(set) {
            set.filter { b -> abs(b - centerTimeMs) > windowHalfMs }
        }
        if (staleBuckets.isEmpty()) return
        synchronized(this) {
            staleBuckets.forEach { b ->
                frameCache.remove(buildKey(projectRevision, quality, b))
            }
        }
        synchronized(set) {
            set.removeAll(staleBuckets.toSet())
        }
        fullPreRenderCompletedRev = -1L
    }

    fun notifyProjectEdited(newRevision: Long, quality: PreviewQuality, durationMs: Long, fps: Int) {
        activeRevision = newRevision
        fullPreRenderCompletedRev = -1L
        fullPreRenderQuality = null
        // Evict older revision bucket indexes to keep RAM tidy
        val prefix = "$newRevision|"
        val staleKeys = cachedBucketsByRev.keys.filter { !it.startsWith(prefix) }
        staleKeys.forEach { cachedBucketsByRev.remove(it) }
        val staleLayerKeys = cachedLayerIdsByRev.keys.filter { !it.startsWith(prefix) }
        staleLayerKeys.forEach { cachedLayerIdsByRev.remove(it) }
        _preRenderState.value = PreRenderUiState(
            isPreRendering = false,
            progress = 0f,
            cachedFramesCount = 0,
            totalTargetFrames = computeTotalFrames(durationMs, fps),
            cachedSpanFractions = emptyList(),
            cacheRamUsedMb = getUsedRamMb(),
            cacheRevision = newRevision
        )
    }

    fun clearAll() {
        fullPreRenderCompletedRev = -1L
        fullPreRenderQuality = null
        synchronized(this) {
            frameCache.evictAll()
        }
        cachedBucketsByRev.clear()
        cachedLayerIdsByRev.clear()
        cacheHits.set(0L)
        cacheLookups.set(0L)
        _preRenderState.value = PreRenderUiState(
            isPreRendering = false,
            progress = 0f,
            cachedFramesCount = 0,
            totalTargetFrames = 0,
            cachedSpanFractions = emptyList(),
            cacheRamUsedMb = 0f,
            cacheRevision = activeRevision
        )
    }

    fun getUsedRamMb(): Float {
        val usedKb = synchronized(this) { frameCache.size() }
        return usedKb / 1024f
    }

    fun getCacheHitRatePercent(): Int {
        val total = cacheLookups.get()
        if (total <= 0L) return 0
        return ((cacheHits.get() * 100L) / total).toInt().coerceIn(0, 100)
    }

    fun resetHitCounters() {
        cacheHits.set(0L)
        cacheLookups.set(0L)
    }

    /**
     * Executes a high-speed Pre-Render pass across [project] at [quality]:
     * 1. Primary Engine: [OffscreenGlPreRenderEngine] — renders all 130+ GLSL shaders, 2D/3D transforms,
     *    and sequential hardware `MediaCodec` video frames on an offscreen EGL 1.4 PBuffer GPU context!
     * 2. Headless / Fallback Engine: Multi-core parallel worker pool reusing per-worker scratch buffers.
     */
    suspend fun preRenderComposition(
        context: Context?,
        project: Project,
        projectRevision: Long,
        quality: PreviewQuality,
        fpsCap: Int,
        audioEngine: AudioReactorEngine,
        scalePercent: Int = 100,
        workerThreads: Int = 4
    ) = withContext(Dispatchers.Default) {
        activeRevision = projectRevision
        val renderFps = min(project.fps.coerceIn(12, 60), fpsCap.coerceIn(24, 60))
        val stepMs = (1000.0 / renderFps).roundToInt().coerceAtLeast(16).toLong()
        val durMs = project.effectiveEditDurationMs().coerceIn(200L, 120_000L)

        val frameBuckets = ArrayList<Long>()
        var cursorMs = 0L
        while (cursorMs <= durMs && frameBuckets.size < 3600) {
            frameBuckets.add(quantizeFrameMs(cursorMs, renderFps))
            cursorMs += stepMs
        }
        val distinctBuckets = frameBuckets.distinct()
        val totalFrames = distinctBuckets.size.coerceAtLeast(1)

        val (targetW, targetH) = computeTargetDimensions(project, quality, scalePercent)
        val startWallMs = System.currentTimeMillis()

        _preRenderState.value = _preRenderState.value.copy(
            isPreRendering = true,
            progress = 0.01f,
            totalTargetFrames = totalFrames,
            cacheRevision = projectRevision,
            engineModeLabel = "Offscreen EGL GPU + MediaCodec"
        )

        var usedBackendLabel = "Offscreen EGL GPU + MediaCodec"
        try {
            val gpuDone = if (context != null) {
                OffscreenGlPreRenderEngine.preRenderOnGpu(
                    context = context,
                    project = project,
                    quality = quality,
                    targetW = targetW,
                    targetH = targetH,
                    frameTimestampsMs = distinctBuckets,
                    audioEngine = audioEngine,
                    isAlreadyCached = { bMs ->
                        getCachedFrame(projectRevision, quality, bMs, renderFps) != null
                    },
                    isStillValid = { activeRevision == projectRevision },
                    onFrameRendered = { idx, bucketMs, bmp ->
                        putCachedFrame(projectRevision, quality, bucketMs, bmp)
                        if (idx % 6 == 0 || idx == totalFrames - 1) {
                            emitProgressSnapshot(
                                projectRevision = projectRevision,
                                quality = quality,
                                durMs = durMs,
                                stepMs = stepMs,
                                completed = idx + 1,
                                totalFrames = totalFrames,
                                startWallMs = startWallMs,
                                modeLabel = "Offscreen EGL GPU + MediaCodec"
                            )
                        }
                    }
                )
            } else {
                false
            }

            if (!gpuDone && activeRevision == projectRevision) {
                usedBackendLabel = "Parallel Multi-Core Engine"
                runParallelCpuPreRender(
                    context = context,
                    project = project,
                    projectRevision = projectRevision,
                    quality = quality,
                    renderFps = renderFps,
                    durMs = durMs,
                    stepMs = stepMs,
                    buckets = distinctBuckets,
                    targetW = targetW,
                    targetH = targetH,
                    audioEngine = audioEngine,
                    startWallMs = startWallMs,
                    requestedWorkers = workerThreads
                )
            }
        } finally {
            val elapsedSec = ((System.currentTimeMillis() - startWallMs).coerceAtLeast(1L)) / 1000f
            val spans = computeCachedSpans(projectRevision, quality, durMs, stepMs)
            val count = cachedCountFor(projectRevision, quality)
            val fpsRate = (count / elapsedSec).roundToInt().coerceAtLeast(1)
            if (activeRevision == projectRevision && count >= (totalFrames * 9) / 10) {
                fullPreRenderCompletedRev = projectRevision
                fullPreRenderQuality = quality
            }
            _preRenderState.value = PreRenderUiState(
                isPreRendering = false,
                progress = if (count >= totalFrames - 1) 1f else (count.toFloat() / totalFrames.coerceAtLeast(1)),
                cachedFramesCount = count,
                totalTargetFrames = totalFrames,
                cachedSpanFractions = spans,
                cacheRamUsedMb = getUsedRamMb(),
                cacheRevision = projectRevision,
                engineModeLabel = usedBackendLabel,
                throughputFps = fpsRate
            )
        }
    }

    /**
     * Pre-renders a single layer OR a `LayerType.GROUP` layer (including all recursive child layers
     * parented under [targetLayerId] and the group's own effect stack) with transparent background
     * into the bounded RAM cache so heavy individual layers/groups composite in 1 GPU draw call!
     */
    suspend fun preRenderLayerOrGroup(
        context: Context?,
        project: Project,
        targetLayerId: String,
        projectRevision: Long,
        quality: PreviewQuality,
        fpsCap: Int,
        audioEngine: AudioReactorEngine,
        scalePercent: Int = 100
    ): Int = withContext(Dispatchers.Default) {
        activeRevision = projectRevision
        val rootLayer = project.layers.firstOrNull { it.id == targetLayerId } ?: return@withContext 0
        val descendants = project.descendantLayerIdsOf(targetLayerId)
        val includedIds = descendants + targetLayerId

        // Keep ancestors (for world transform evaluation) as invisible `NULL_OBJECT` rigs, and keep included layers visible!
        val isolatedLayers = project.layers.mapNotNull { lyr ->
            if (lyr.id in includedIds) {
                lyr.copy(visible = true)
            } else {
                // Keep non-rendering parent chain only if needed for world transform
                lyr.copy(visible = false, effects = emptyList())
            }
        }
        val spanStartMs = project.layers.filter { it.id in includedIds }.minOfOrNull { it.startTimeMs }?.coerceAtLeast(0L) ?: rootLayer.startTimeMs
        val spanEndMs = project.layers.filter { it.id in includedIds }.maxOfOrNull { it.endTimeMs }?.coerceAtLeast(spanStartMs + 100L) ?: rootLayer.endTimeMs

        val isolatedProject = project.copy(
            backgroundColor = "TransparentAlpha",
            layers = isolatedLayers,
            durationMs = spanEndMs
        )

        val renderFps = min(project.fps.coerceIn(12, 60), fpsCap.coerceIn(24, 60))
        val stepMs = (1000.0 / renderFps).roundToInt().coerceAtLeast(16).toLong()
        val buckets = ArrayList<Long>()
        var cursor = spanStartMs
        while (cursor <= spanEndMs && buckets.size < 1800) {
            buckets.add(quantizeFrameMs(cursor, renderFps))
            cursor += stepMs
        }
        val distinctBuckets = buckets.distinct()
        val totalFrames = distinctBuckets.size.coerceAtLeast(1)
        val (targetW, targetH) = computeTargetDimensions(project, quality, scalePercent)
        val startWallMs = System.currentTimeMillis()
        var renderedFrames = 0

        _preRenderState.value = _preRenderState.value.copy(
            isPreRendering = true,
            progress = 0.02f,
            totalTargetFrames = totalFrames,
            cacheRevision = projectRevision,
            engineModeLabel = "Layer/Group Pre-Render (${rootLayer.name})"
        )

        try {
            val gpuDone = if (context != null) {
                OffscreenGlPreRenderEngine.preRenderOnGpu(
                    context = context,
                    project = isolatedProject,
                    quality = quality,
                    targetW = targetW,
                    targetH = targetH,
                    frameTimestampsMs = distinctBuckets,
                    audioEngine = audioEngine,
                    isAlreadyCached = { bMs ->
                        getCachedLayerOrGroupFrame(projectRevision, quality, targetLayerId, bMs, renderFps) != null
                    },
                    isStillValid = { activeRevision == projectRevision },
                    onFrameRendered = { idx, bMs, bmp ->
                        putCachedLayerOrGroupFrame(projectRevision, quality, targetLayerId, bMs, bmp)
                        renderedFrames = idx + 1
                        if (idx % 6 == 0 || idx == totalFrames - 1) {
                            _preRenderState.value = _preRenderState.value.copy(
                                isPreRendering = true,
                                progress = ((idx + 1).toFloat() / totalFrames.toFloat()).coerceIn(0f, 1f),
                                cachedFramesCount = renderedFrames,
                                totalTargetFrames = totalFrames,
                                cacheRamUsedMb = getUsedRamMb(),
                                engineModeLabel = "Layer/Group GPU Pre-Render (${rootLayer.name})"
                            )
                        }
                    }
                )
            } else false

            if (!gpuDone && activeRevision == projectRevision) {
                val paintFill = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
                val paintStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
                val paintText = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
                val scratch = Bitmap.createBitmap(targetW, targetH, Bitmap.Config.ARGB_8888)
                try {
                    distinctBuckets.forEachIndexed { idx, bMs ->
                        currentCoroutineContext().ensureActive()
                        if (activeRevision != projectRevision) return@forEachIndexed
                        val aud = audioEngine.sampleTimelineSpectrumPure(bMs, project.bpm, project.beatMarkers)
                        val bmp = renderSinglePreviewFrame(
                            context = context,
                            project = isolatedProject,
                            timeMs = bMs,
                            audio = aud,
                            w = targetW,
                            h = targetH,
                            paintFill = paintFill,
                            paintStroke = paintStroke,
                            paintText = paintText,
                            scratchBmp = scratch
                        )
                        putCachedLayerOrGroupFrame(projectRevision, quality, targetLayerId, bMs, bmp)
                        renderedFrames = idx + 1
                    }
                } finally {
                    scratch.recycle()
                }
            }
            if (activeRevision == projectRevision && renderedFrames > 0) {
                cachedLayerIdsByRev["$projectRevision|${quality.name}|$targetLayerId"] = true
            }
        } finally {
            val elapsedSec = ((System.currentTimeMillis() - startWallMs).coerceAtLeast(1L)) / 1000f
            _preRenderState.value = _preRenderState.value.copy(
                isPreRendering = false,
                progress = 1f,
                cachedFramesCount = renderedFrames,
                totalTargetFrames = totalFrames,
                cacheRamUsedMb = getUsedRamMb(),
                cacheRevision = projectRevision,
                engineModeLabel = "Pre-Rendered ${rootLayer.name}",
                throughputFps = (renderedFrames / elapsedSec).roundToInt().coerceAtLeast(1)
            )
        }
        renderedFrames
    }

    /**
     * Smart Idle Lookahead Auto-Cacher: quietly pre-renders a short window ahead of [playheadMs]
     * in the background when `autoCachePlaybackFrames` is enabled and heavy effects are present.
     */
    suspend fun preRenderLookaheadWindow(
        context: Context?,
        project: Project,
        projectRevision: Long,
        quality: PreviewQuality,
        fpsCap: Int,
        startPlayheadMs: Long,
        windowDurationMs: Long = 2_500L,
        audioEngine: AudioReactorEngine,
        scalePercent: Int = 100
    ) = withContext(Dispatchers.Default) {
        if (activeRevision != projectRevision || project.layers.isEmpty()) return@withContext
        val renderFps = min(project.fps.coerceIn(12, 60), fpsCap.coerceIn(24, 60))
        val stepMs = (1000.0 / renderFps).roundToInt().coerceAtLeast(16).toLong()
        val editDur = project.effectiveEditDurationMs()
        val endMs = (startPlayheadMs + windowDurationMs).coerceAtMost(editDur)
        val buckets = ArrayList<Long>()
        var t = startPlayheadMs.coerceAtLeast(0L)
        while (t <= endMs && buckets.size < 150) {
            val b = quantizeFrameMs(t, renderFps)
            if (getCachedFrame(projectRevision, quality, b, renderFps) == null) {
                buckets.add(b)
            }
            t += stepMs
        }
        val distinct = buckets.distinct()
        if (distinct.isEmpty()) return@withContext

        val (targetW, targetH) = computeTargetDimensions(project, quality, scalePercent)
        val gpuOk = if (context != null) {
            OffscreenGlPreRenderEngine.preRenderOnGpu(
                context = context,
                project = project,
                quality = quality,
                targetW = targetW,
                targetH = targetH,
                frameTimestampsMs = distinct,
                audioEngine = audioEngine,
                isAlreadyCached = { bMs -> getCachedFrame(projectRevision, quality, bMs, renderFps) != null },
                isStillValid = { activeRevision == projectRevision },
                onFrameRendered = { _, bucketMs, bmp ->
                    putCachedFrame(projectRevision, quality, bucketMs, bmp)
                }
            )
        } else false

        if (!gpuOk && activeRevision == projectRevision) {
            val paintFill = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
            val paintStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
            val paintText = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
            val scratch = Bitmap.createBitmap(targetW, targetH, Bitmap.Config.ARGB_8888)
            try {
                for (bMs in distinct) {
                    currentCoroutineContext().ensureActive()
                    if (activeRevision != projectRevision) break
                    val aud = audioEngine.sampleTimelineSpectrum(bMs, project.bpm, project.beatMarkers)
                    val bmp = renderSinglePreviewFrame(
                        context = context,
                        project = project,
                        timeMs = bMs,
                        audio = aud,
                        w = targetW,
                        h = targetH,
                        paintFill = paintFill,
                        paintStroke = paintStroke,
                        paintText = paintText,
                        scratchBmp = scratch
                    )
                    putCachedFrame(projectRevision, quality, bMs, bmp)
                }
            } finally {
                scratch.recycle()
            }
        }
        if (activeRevision == projectRevision) {
            refreshCacheTelemetry(projectRevision, quality, editDur, renderFps)
        }
    }

    private fun computeTargetDimensions(
        project: Project,
        quality: PreviewQuality,
        scalePercent: Int = 100
    ): Pair<Int, Int> {
        val aspect = (project.width.toFloat().coerceAtLeast(64f) / project.height.toFloat().coerceAtLeast(64f))
            .coerceIn(0.2f, 5f)
        val scale = (scalePercent.coerceIn(35, 100) / 100f)
        val maxEdge = (quality.maxGlDimension.coerceIn(160, 720) * scale).roundToInt().coerceIn(128, 720)
        return if (aspect >= 1f) {
            val w = (maxEdge / 2 * 2).coerceAtLeast(96)
            val h = ((maxEdge / aspect).toInt() / 2 * 2).coerceAtLeast(64)
            w to h
        } else {
            val h = (maxEdge / 2 * 2).coerceAtLeast(96)
            val w = ((maxEdge * aspect).toInt() / 2 * 2).coerceAtLeast(64)
            w to h
        }
    }

    private suspend fun runParallelCpuPreRender(
        context: Context?,
        project: Project,
        projectRevision: Long,
        quality: PreviewQuality,
        renderFps: Int,
        durMs: Long,
        stepMs: Long,
        buckets: List<Long>,
        targetW: Int,
        targetH: Int,
        audioEngine: AudioReactorEngine,
        startWallMs: Long,
        requestedWorkers: Int = 4
    ) = coroutineScope {
        val totalFrames = buckets.size.coerceAtLeast(1)
        val nextIndex = AtomicInteger(0)
        val completedCount = AtomicInteger(0)
        val maxHw = Runtime.getRuntime().availableProcessors().coerceIn(2, 8)
        val workerCount = requestedWorkers.coerceIn(1, maxHw).coerceAtMost(totalFrames)

        val jobs = (0 until workerCount).map {
            launch(Dispatchers.Default) {
                val paintFill = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
                val paintStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
                val paintText = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
                val scratchBmp = Bitmap.createBitmap(targetW, targetH, Bitmap.Config.ARGB_8888)
                try {
                    while (true) {
                        ensureActive()
                        if (activeRevision != projectRevision) break
                        val idx = nextIndex.getAndIncrement()
                        if (idx >= buckets.size) break

                        val bucketMs = buckets[idx]
                        if (getCachedFrame(projectRevision, quality, bucketMs, renderFps) == null) {
                            // Evaluate deterministic audio spectrum without mutating shared StateFlow race-wise
                            val aud = audioEngine.sampleTimelineSpectrumPure(bucketMs, project.bpm, project.beatMarkers)
                            val bmp = renderSinglePreviewFrame(
                                context = context,
                                project = project,
                                timeMs = bucketMs,
                                audio = aud,
                                w = targetW,
                                h = targetH,
                                paintFill = paintFill,
                                paintStroke = paintStroke,
                                paintText = paintText,
                                scratchBmp = scratchBmp
                            )
                            putCachedFrame(projectRevision, quality, bucketMs, bmp)
                        }

                        val done = completedCount.incrementAndGet()
                        if (done % 6 == 0 || done == totalFrames) {
                            emitProgressSnapshot(
                                projectRevision = projectRevision,
                                quality = quality,
                                durMs = durMs,
                                stepMs = stepMs,
                                completed = done,
                                totalFrames = totalFrames,
                                startWallMs = startWallMs,
                                modeLabel = "Parallel Multi-Core Engine"
                            )
                        }
                    }
                } finally {
                    scratchBmp.recycle()
                }
            }
        }
        jobs.forEach { it.join() }
    }

    private fun emitProgressSnapshot(
        projectRevision: Long,
        quality: PreviewQuality,
        durMs: Long,
        stepMs: Long,
        completed: Int,
        totalFrames: Int,
        startWallMs: Long,
        modeLabel: String
    ) {
        val elapsedSec = ((System.currentTimeMillis() - startWallMs).coerceAtLeast(1L)) / 1000f
        val spans = computeCachedSpans(projectRevision, quality, durMs, stepMs)
        val count = cachedCountFor(projectRevision, quality)
        _preRenderState.value = PreRenderUiState(
            isPreRendering = true,
            progress = (completed.toFloat() / totalFrames.toFloat()).coerceIn(0f, 1f),
            cachedFramesCount = count,
            totalTargetFrames = totalFrames,
            cachedSpanFractions = spans,
            cacheRamUsedMb = getUsedRamMb(),
            cacheRevision = projectRevision,
            engineModeLabel = modeLabel,
            throughputFps = (completed / elapsedSec).roundToInt().coerceAtLeast(1)
        )
    }

    fun refreshCacheTelemetry(projectRevision: Long, quality: PreviewQuality, durationMs: Long, fps: Int) {
        val stepMs = (1000.0 / fps.coerceIn(12, 60)).roundToInt().coerceAtLeast(16).toLong()
        val total = computeTotalFrames(durationMs, fps)
        val count = cachedCountFor(projectRevision, quality)
        val spans = computeCachedSpans(projectRevision, quality, durationMs, stepMs)
        _preRenderState.value = _preRenderState.value.copy(
            cachedFramesCount = count,
            totalTargetFrames = total,
            cachedSpanFractions = spans,
            cacheRamUsedMb = getUsedRamMb(),
            cacheRevision = projectRevision
        )
    }

    internal fun renderSinglePreviewFrame(
        context: Context?,
        project: Project,
        timeMs: Long,
        audio: AudioSpectrumSnapshot,
        w: Int,
        h: Int,
        paintFill: Paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG),
        paintStroke: Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE },
        paintText: Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER },
        scratchBmp: Bitmap? = null
    ): Bitmap {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)

        paintFill.shader = null
        paintFill.colorFilter = null
        paintFill.xfermode = null
        paintFill.alpha = 255
        when {
            project.backgroundColor == "White" -> canvas.drawColor(Color.WHITE)
            project.backgroundColor == "Dark Gray" -> canvas.drawColor(Color.rgb(28, 32, 40))
            project.backgroundColor == "Light Gray" -> canvas.drawColor(Color.rgb(209, 214, 222))
            project.backgroundColor.startsWith("#") -> {
                canvas.drawColor(runCatching { Color.parseColor(project.backgroundColor) }.getOrDefault(Color.BLACK))
            }
            project.backgroundColor == "TransparentAlpha" -> {
                canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
            }
            project.backgroundColor == "Transparent" -> {
                canvas.drawColor(Color.rgb(28, 31, 38))
                paintFill.color = Color.rgb(46, 51, 61)
                val cell = 18f
                var y = 0f
                var row = 0
                while (y < h) {
                    var x = if (row % 2 == 0) 0f else cell
                    while (x < w) {
                        canvas.drawRect(x, y, (x + cell).coerceAtMost(w.toFloat()), (y + cell).coerceAtMost(h.toFloat()), paintFill)
                        x += cell * 2f
                    }
                    y += cell
                    row++
                }
            }
            else -> canvas.drawColor(Color.BLACK)
        }

        val projW = project.width.coerceAtLeast(1).toFloat()
        val scaleFactor = w.toFloat() / projW
        val activeEffects = ArrayList<EffectNode>(4)

        for (i in project.layers.indices) {
            val layer = project.layers[i]
            if (!layer.visible || timeMs !in layer.startTimeMs..layer.endTimeMs) continue
            for (fx in layer.effects) {
                if (fx.enabled && !EffectsCatalog.isTextOnlyEffect(fx.effectId)) {
                    activeEffects.add(fx)
                }
            }
            if (layer.type == LayerType.NULL_OBJECT ||
                layer.type == LayerType.GROUP ||
                layer.type == LayerType.CAMERA ||
                layer.type == LayerType.AUDIO
            ) continue

            val tr = AnimationEvaluator.evaluateWorldTransform(
                layer = layer,
                allLayers = project.layers,
                timeMs = timeMs,
                audio = audio,
                applyActiveCamera = true
            )
            val alpha = (tr.opacity * 255f).toInt().coerceIn(0, 255)
            if (alpha <= 2) continue

            canvas.save()
            canvas.translate(w * 0.5f + tr.posX * scaleFactor, h * 0.5f + tr.posY * scaleFactor)
            val pivXScaled = tr.pivotX * scaleFactor
            val pivYScaled = tr.pivotY * scaleFactor
            if (abs(tr.rotX) > 0.05f || abs(tr.rotY) > 0.05f) {
                val cam3d = Camera()
                val mat3d = Matrix()
                cam3d.save()
                cam3d.setLocation(0f, 0f, -14f)
                cam3d.rotateX(tr.rotX)
                cam3d.rotateY(tr.rotY)
                cam3d.rotateZ(-tr.rotZ)
                cam3d.getMatrix(mat3d)
                cam3d.restore()
                mat3d.preTranslate(-pivXScaled, -pivYScaled)
                mat3d.postTranslate(pivXScaled, pivYScaled)
                canvas.concat(mat3d)
            } else {
                canvas.rotate(tr.rotZ, pivXScaled, pivYScaled)
            }
            val depthZoom = (1f + tr.posZ / 900f).coerceIn(0.15f, 5f)
            val sx = tr.scale * depthZoom * (if (layer.flipX) -1f else 1f)
            val sy = tr.scaleY * depthZoom * (if (layer.flipY) -1f else 1f)
            canvas.scale(sx, sy, pivXScaled, pivYScaled)

            paintFill.xfermode = when (layer.blendMode) {
                "Screen", "Lighten" -> PorterDuffXfermode(PorterDuff.Mode.SCREEN)
                "Multiply" -> PorterDuffXfermode(PorterDuff.Mode.MULTIPLY)
                "Overlay" -> PorterDuffXfermode(PorterDuff.Mode.OVERLAY)
                else -> null
            }

            val baseCol = runCatching { Color.parseColor(layer.colorHex) }.getOrDefault(Color.rgb(79, 124, 255))
            val col = Color.argb(alpha, Color.red(baseCol), Color.green(baseCol), Color.blue(baseCol))

            when (layer.type) {
                LayerType.VIDEO, LayerType.IMAGE -> {
                    val srcTimeMs = if (layer.type == LayerType.VIDEO) {
                        AnimationEvaluator.evaluateSourceMediaTimeMs(layer, timeMs, audio)
                    } else 0L
                    val mediaBmp = if (context != null) {
                        MediaEngine.getMediaBitmapForPreRender(context, layer, srcTimeMs, maxOf(w, h))
                    } else null

                    if (mediaBmp != null && !mediaBmp.isRecycled) {
                        val bmpAspect = mediaBmp.width.toFloat().coerceAtLeast(1f) / mediaBmp.height.toFloat().coerceAtLeast(1f)
                        val compAspect = w.toFloat() / h.toFloat().coerceAtLeast(1f)
                        val hw = if (bmpAspect >= compAspect) w * 0.50f else (h * 0.50f * bmpAspect)
                        val hh = if (bmpAspect >= compAspect) (w * 0.50f / bmpAspect) else h * 0.50f
                        val rect = RectF(-hw, -hh, hw, hh)
                        if (layer.shadowEnabled) {
                            val shCol = runCatching { Color.parseColor(layer.shadowColorHex) }.getOrDefault(Color.BLACK)
                            paintFill.shader = null
                            paintFill.color = shCol
                            paintFill.setShadowLayer(
                                (layer.shadowBlur * scaleFactor).coerceIn(1f, 32f),
                                layer.shadowOffsetX * scaleFactor,
                                layer.shadowOffsetY * scaleFactor,
                                shCol
                            )
                            canvas.drawRect(rect, paintFill)
                            paintFill.clearShadowLayer()
                        }
                        paintFill.shader = null
                        paintFill.alpha = alpha
                        canvas.drawBitmap(mediaBmp, null, rect, paintFill)
                        paintFill.alpha = 255
                        if (layer.strokeEnabled && layer.strokeWidth > 0.4f) {
                            val sCol = runCatching { Color.parseColor(layer.strokeColorHex) }.getOrDefault(Color.WHITE)
                            val sw = (layer.strokeWidth * scaleFactor * 1.5f).coerceIn(1.2f, 28f)
                            val inset = when (layer.strokePosition.uppercase()) {
                                "OUTSIDE" -> -sw * 0.5f
                                "INSIDE" -> sw * 0.5f
                                else -> 0f
                            }
                            paintStroke.color = Color.argb(alpha, Color.red(sCol), Color.green(sCol), Color.blue(sCol))
                            paintStroke.strokeWidth = sw
                            canvas.drawRect(RectF(-hw + inset, -hh + inset, hw - inset, hh - inset), paintStroke)
                        }
                    } else if (layer.mediaUri.isBlank()) {
                        paintFill.shader = null
                        paintFill.color = col
                        canvas.drawRoundRect(RectF(-w * 0.34f, -h * 0.24f, w * 0.34f, h * 0.24f), 14f, 14f, paintFill)
                    }
                }

                LayerType.TEXT -> {
                    if (context != null) {
                        val anims = KeyflowTextAndBorderRenderer.evaluateTextAnimators(layer, timeMs, audio)
                        val textBmp = KeyflowTextAndBorderRenderer.rasterizeTextLayerBitmap(context, layer, anims)
                        val tAspect = textBmp.width.toFloat() / textBmp.height.toFloat().coerceAtLeast(1f)
                        val hh = min(w, h) * 0.14f
                        val hw = hh * tAspect.coerceIn(1f, 6f)
                        paintFill.shader = null
                        paintFill.alpha = alpha
                        canvas.drawBitmap(textBmp, null, RectF(-hw, -hh, hw, hh), paintFill)
                        paintFill.alpha = 255
                        textBmp.recycle()
                    } else {
                        val txt = layer.textContent.ifBlank { "Text" }
                        paintText.color = col
                        paintText.textSize = (min(w, h) * 0.10f).coerceAtLeast(14f)
                        canvas.drawText(txt, 0f, paintText.textSize * 0.35f, paintText)
                    }
                }

                LayerType.MODEL_3D -> {
                    val half = min(w, h) * 0.19f
                    paintFill.shader = null
                    paintFill.color = col
                    canvas.drawRoundRect(RectF(-half, -half, half, half), half * 0.28f, half * 0.28f, paintFill)
                    paintStroke.color = Color.argb(alpha, 79, 124, 255)
                    paintStroke.strokeWidth = 2.2f
                    canvas.drawRoundRect(RectF(-half * 0.72f, -half * 0.72f, half * 0.72f, half * 0.72f), 8f, 8f, paintStroke)
                }

                LayerType.DRAWING -> {
                    val (startPct, endPct) = KeyflowTextAndBorderRenderer.evaluateDrawingProgressRange(layer, timeMs, audio)
                    val drawBmp = KeyflowTextAndBorderRenderer.rasterizeDrawingLayerBitmap(layer, startPct, endPct, 384)
                    KeyflowTextAndBorderRenderer.applyLayerMasksInPlace(drawBmp, layer, timeMs, audio)
                    val half = min(w, h) * 0.5f
                    paintFill.shader = null
                    paintFill.alpha = alpha
                    canvas.drawBitmap(drawBmp, null, RectF(-half, -half, half, half), paintFill)
                    paintFill.alpha = 255
                    drawBmp.recycle()
                }

                else -> {
                    val shapeBmp = KeyflowTextAndBorderRenderer.rasterizeShapeLayerBitmap(layer)
                    KeyflowTextAndBorderRenderer.applyLayerMasksInPlace(shapeBmp, layer, timeMs, audio)
                    val half = min(w, h) * 0.22f
                    paintFill.shader = null
                    paintFill.alpha = alpha
                    canvas.drawBitmap(shapeBmp, null, RectF(-half, -half, half, half), paintFill)
                    paintFill.alpha = 255
                    shapeBmp.recycle()
                }
            }
            paintFill.shader = null
            paintFill.xfermode = null
            canvas.restore()
        }

        // Apply lightweight composited effect passes onto the pre-rendered frame reusing scratchBmp (0 GC churn!)
        if (activeEffects.isNotEmpty()) {
            applySoftwareEffectsToFrame(bmp, canvas, activeEffects, timeMs, audio, paintFill, scratchBmp)
        }
        return bmp
    }

    private fun blitIntoScratch(src: Bitmap, scratch: Bitmap?): Pair<Bitmap, Boolean> {
        if (scratch != null && !scratch.isRecycled && scratch.width == src.width && scratch.height == src.height) {
            val sc = Canvas(scratch)
            sc.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
            sc.drawBitmap(src, 0f, 0f, null)
            return scratch to false
        }
        return src.copy(Bitmap.Config.ARGB_8888, false) to true
    }

    private fun applySoftwareEffectsToFrame(
        bmp: Bitmap,
        canvas: Canvas,
        effects: List<EffectNode>,
        timeMs: Long,
        audio: AudioSpectrumSnapshot,
        paint: Paint,
        scratchBmp: Bitmap? = null
    ) {
        val w = bmp.width.toFloat()
        val h = bmp.height.toFloat()
        val sec = timeMs / 1000f
        for (fx in effects) {
            val rawEval = AnimationEvaluator.evaluateEffectNode(fx, timeMs, audio)
            val eval = EffectsCatalog.normalizeForShader(fx.effectId, rawEval)
            if (eval.mixFraction <= 0.01f) continue
            val def = EffectsCatalog.findById(fx.effectId)
            val tint = runCatching { Color.parseColor(fx.tintColorHex) }.getOrDefault(Color.WHITE)

            when (def.shaderMode) {
                1 -> { // Optical Glow / Bloom
                    val glowAlpha = (eval.primary * eval.mixFraction * 95f).toInt().coerceIn(0, 180)
                    if (glowAlpha > 4) {
                        val (copy, ownsCopy) = blitIntoScratch(bmp, scratchBmp)
                        paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.ADD)
                        paint.alpha = glowAlpha
                        val spread = 2f + eval.secondary * 6f
                        canvas.drawBitmap(copy, null, RectF(-spread, -spread, w + spread, h + spread), paint)
                        paint.xfermode = null
                        paint.alpha = 255
                        if (ownsCopy) copy.recycle()
                    }
                }
                2 -> { // Chromatic Aberration / RGB Split
                    val shift = (eval.primary * (3f + eval.secondary * 8f) * eval.mixFraction).coerceIn(0f, 24f)
                    if (shift >= 0.8f) {
                        val (copy, ownsCopy) = blitIntoScratch(bmp, scratchBmp)
                        paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.SCREEN)
                        paint.colorFilter = ColorMatrixColorFilter(
                            floatArrayOf(
                                1f, 0f, 0f, 0f, 0f,
                                0f, 0f, 0f, 0f, 0f,
                                0f, 0f, 0f, 0f, 0f,
                                0f, 0f, 0f, 0.55f, 0f
                            )
                        )
                        canvas.drawBitmap(copy, shift, 0f, paint)
                        paint.colorFilter = ColorMatrixColorFilter(
                            floatArrayOf(
                                0f, 0f, 0f, 0f, 0f,
                                0f, 0f, 0f, 0f, 0f,
                                0f, 0f, 1f, 0f, 0f,
                                0f, 0f, 0f, 0.55f, 0f
                            )
                        )
                        canvas.drawBitmap(copy, -shift, 0f, paint)
                        paint.colorFilter = null
                        paint.xfermode = null
                        if (ownsCopy) copy.recycle()
                    }
                }
                9 -> { // Hue / Color Grade
                    val cm = ColorMatrix().apply {
                        setRotate(0, (eval.primary * 120f + eval.angleDeg) * eval.mixFraction)
                        setRotate(1, (eval.primary * 90f) * eval.mixFraction)
                    }
                    val (copy, ownsCopy) = blitIntoScratch(bmp, scratchBmp)
                    paint.colorFilter = ColorMatrixColorFilter(cm)
                    canvas.drawBitmap(copy, 0f, 0f, paint)
                    paint.colorFilter = null
                    if (ownsCopy) copy.recycle()
                }
                10 -> { // Camera Shake / Pulse
                    val amp = eval.primary * eval.mixFraction * 8f
                    if (amp > 0.5f) {
                        val dx = sin(sec * (12f + eval.frequency * 28f)) * amp
                        val dy = cos(sec * (10f + eval.frequency * 22f)) * amp * eval.secondary
                        val (copy, ownsCopy) = blitIntoScratch(bmp, scratchBmp)
                        canvas.drawColor(Color.BLACK)
                        canvas.drawBitmap(copy, dx, dy, null)
                        if (ownsCopy) copy.recycle()
                    }
                }
                else -> {
                    val overlayAlpha = (eval.primary * eval.mixFraction * 36f).toInt().coerceIn(0, 72)
                    if (overlayAlpha > 4 && tint != Color.WHITE) {
                        paint.color = Color.argb(overlayAlpha, Color.red(tint), Color.green(tint), Color.blue(tint))
                        canvas.drawRect(0f, 0f, w, h, paint)
                    }
                }
            }
        }
    }

    private fun computeTotalFrames(durationMs: Long, fps: Int): Int {
        val safeFps = fps.coerceIn(12, 60)
        val stepMs = (1000.0 / safeFps).roundToInt().coerceAtLeast(16).toLong()
        return ((durationMs.coerceAtLeast(200L) / stepMs).toInt() + 1).coerceIn(1, 3600)
    }

    private fun cachedCountFor(revision: Long, quality: PreviewQuality): Int {
        val set = cachedBucketsByRev["$revision|${quality.name}"] ?: return 0
        return synchronized(set) { set.size }
    }

    private fun computeCachedSpans(
        revision: Long,
        quality: PreviewQuality,
        durationMs: Long,
        stepMs: Long
    ): List<Pair<Float, Float>> {
        val set = cachedBucketsByRev["$revision|${quality.name}"] ?: return emptyList()
        val sorted = synchronized(set) { set.sorted() }
        if (sorted.isEmpty()) return emptyList()
        val durF = durationMs.coerceAtLeast(1L).toFloat()
        val tolerance = stepMs * 2L + 4L
        val spans = ArrayList<Pair<Float, Float>>()
        var rangeStart = sorted.first()
        var prev = rangeStart
        for (i in 1 until sorted.size) {
            val cur = sorted[i]
            if (cur - prev <= tolerance) {
                prev = cur
            } else {
                spans.add(
                    (rangeStart / durF).coerceIn(0f, 1f) to
                        ((prev + stepMs) / durF).coerceIn(0f, 1f)
                )
                rangeStart = cur
                prev = cur
            }
        }
        spans.add(
            (rangeStart / durF).coerceIn(0f, 1f) to
                ((prev + stepMs) / durF).coerceIn(0f, 1f)
        )
        return spans
    }

    private fun buildKey(revision: Long, quality: PreviewQuality, bucketMs: Long): String =
        "pre|$revision|${quality.name}|$bucketMs"

    private fun buildLayerKey(revision: Long, quality: PreviewQuality, layerId: String, bucketMs: Long): String =
        "lyr|$revision|${quality.name}|$layerId|$bucketMs"
}
