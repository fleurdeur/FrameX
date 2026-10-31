package com.example.engine

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
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

    private val _preRenderState = MutableStateFlow(PreRenderUiState())
    val preRenderState: StateFlow<PreRenderUiState> = _preRenderState.asStateFlow()

    private fun createLru(sizeKb: Int): LruCache<String, Bitmap> {
        return object : LruCache<String, Bitmap>(sizeKb.coerceIn(16 * 1024, 512 * 1024)) {
            override fun sizeOf(key: String, value: Bitmap): Int {
                return (value.allocationByteCount / 1024).coerceAtLeast(1)
            }
        }
    }

    fun configureCacheLimitMb(limitMb: Int) {
        val targetKb = (limitMb.coerceIn(32, 512)) * 1024
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

    fun notifyProjectEdited(newRevision: Long, quality: PreviewQuality, durationMs: Long, fps: Int) {
        activeRevision = newRevision
        // Evict older revision bucket indexes to keep RAM tidy
        val prefix = "$newRevision|"
        val staleKeys = cachedBucketsByRev.keys.filter { !it.startsWith(prefix) }
        staleKeys.forEach { cachedBucketsByRev.remove(it) }
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
        synchronized(this) {
            frameCache.evictAll()
        }
        cachedBucketsByRev.clear()
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
        audioEngine: AudioReactorEngine
    ) = withContext(Dispatchers.Default) {
        activeRevision = projectRevision
        val renderFps = min(project.fps.coerceIn(12, 60), fpsCap.coerceIn(24, 60))
        val stepMs = (1000.0 / renderFps).roundToInt().coerceAtLeast(16).toLong()
        val durMs = project.durationMs.coerceIn(200L, 60_000L)

        val frameBuckets = ArrayList<Long>()
        var cursorMs = 0L
        while (cursorMs <= durMs && frameBuckets.size < 900) {
            frameBuckets.add(quantizeFrameMs(cursorMs, renderFps))
            cursorMs += stepMs
        }
        val distinctBuckets = frameBuckets.distinct()
        val totalFrames = distinctBuckets.size.coerceAtLeast(1)

        val (targetW, targetH) = computeTargetDimensions(project, quality)
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
                    startWallMs = startWallMs
                )
            }
        } finally {
            val elapsedSec = ((System.currentTimeMillis() - startWallMs).coerceAtLeast(1L)) / 1000f
            val spans = computeCachedSpans(projectRevision, quality, durMs, stepMs)
            val count = cachedCountFor(projectRevision, quality)
            val fpsRate = (count / elapsedSec).roundToInt().coerceAtLeast(1)
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
        audioEngine: AudioReactorEngine
    ) = withContext(Dispatchers.Default) {
        if (activeRevision != projectRevision || project.layers.isEmpty()) return@withContext
        val renderFps = min(project.fps.coerceIn(12, 60), fpsCap.coerceIn(24, 60))
        val stepMs = (1000.0 / renderFps).roundToInt().coerceAtLeast(16).toLong()
        val endMs = (startPlayheadMs + windowDurationMs).coerceAtMost(project.durationMs)
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

        val (targetW, targetH) = computeTargetDimensions(project, quality)
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
            refreshCacheTelemetry(projectRevision, quality, project.durationMs, renderFps)
        }
    }

    private fun computeTargetDimensions(project: Project, quality: PreviewQuality): Pair<Int, Int> {
        val aspect = (project.width.toFloat().coerceAtLeast(64f) / project.height.toFloat().coerceAtLeast(64f))
            .coerceIn(0.2f, 5f)
        val maxEdge = quality.maxGlDimension.coerceIn(160, 720)
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
        startWallMs: Long
    ) = coroutineScope {
        val totalFrames = buckets.size.coerceAtLeast(1)
        val nextIndex = AtomicInteger(0)
        val completedCount = AtomicInteger(0)
        val workerCount = Runtime.getRuntime().availableProcessors().coerceIn(2, 6).coerceAtMost(totalFrames)

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
        when (project.backgroundColor) {
            "White" -> canvas.drawColor(Color.WHITE)
            "Dark Gray" -> canvas.drawColor(Color.rgb(28, 32, 40))
            "Light Gray" -> canvas.drawColor(Color.rgb(209, 214, 222))
            "Transparent" -> {
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
                if (fx.enabled) activeEffects.add(fx)
            }
            if (layer.type == LayerType.NULL_OBJECT || layer.type == LayerType.CAMERA || layer.type == LayerType.AUDIO) continue

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
            canvas.rotate(tr.rotZ, tr.pivotX * scaleFactor, tr.pivotY * scaleFactor)
            val depthZoom = (1f + tr.posZ / 900f).coerceIn(0.15f, 5f)
            val sx = tr.scale * depthZoom * (if (layer.flipX) -1f else 1f)
            val sy = tr.scale * depthZoom * (if (layer.flipY) -1f else 1f)
            canvas.scale(sx, sy, tr.pivotX * scaleFactor, tr.pivotY * scaleFactor)

            paintFill.xfermode = when (layer.blendMode) {
                "Screen", "Lighten" -> PorterDuffXfermode(PorterDuff.Mode.SCREEN)
                "Multiply" -> PorterDuffXfermode(PorterDuff.Mode.MULTIPLY)
                "Overlay" -> PorterDuffXfermode(PorterDuff.Mode.OVERLAY)
                else -> null
            }

            val baseCol = runCatching { Color.parseColor(layer.colorHex) }.getOrDefault(Color.rgb(0, 210, 168))
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
                        val hw = if (bmpAspect >= compAspect) w * 0.42f else (h * 0.42f * bmpAspect)
                        val hh = if (bmpAspect >= compAspect) (w * 0.42f / bmpAspect) else h * 0.42f
                        paintFill.alpha = alpha
                        canvas.drawBitmap(mediaBmp, null, RectF(-hw, -hh, hw, hh), paintFill)
                        paintFill.alpha = 255
                    } else {
                        paintFill.color = col
                        canvas.drawRoundRect(RectF(-w * 0.34f, -h * 0.24f, w * 0.34f, h * 0.24f), 14f, 14f, paintFill)
                    }
                }

                LayerType.TEXT -> {
                    paintText.color = col
                    paintText.textSize = (min(w, h) * 0.10f).coerceAtLeast(14f)
                    paintText.typeface = when (layer.fontFamilyName) {
                        "Serif" -> Typeface.SERIF
                        "Monospace", "JetBrains Mono" -> Typeface.MONOSPACE
                        else -> Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
                    }
                    canvas.drawText(layer.textContent.ifBlank { "Text" }, 0f, paintText.textSize * 0.35f, paintText)
                }

                LayerType.MODEL_3D -> {
                    val half = min(w, h) * 0.19f
                    paintFill.color = col
                    canvas.drawRoundRect(RectF(-half, -half, half, half), half * 0.28f, half * 0.28f, paintFill)
                    paintStroke.color = Color.argb(alpha, 0, 240, 200)
                    paintStroke.strokeWidth = 2.2f
                    canvas.drawRoundRect(RectF(-half * 0.72f, -half * 0.72f, half * 0.72f, half * 0.72f), 8f, 8f, paintStroke)
                }

                else -> {
                    val half = min(w, h) * 0.22f
                    paintFill.color = col
                    when (layer.shapeKind) {
                        "Circle" -> canvas.drawCircle(0f, 0f, half, paintFill)
                        "Rectangle" -> canvas.drawRect(RectF(-half, -half * 0.75f, half, half * 0.75f), paintFill)
                        "Star" -> canvas.drawPath(FramePollutionGlRenderer.buildPolygonOrStarPath(5, half, half * 0.44f), paintFill)
                        "Triangle" -> canvas.drawPath(FramePollutionGlRenderer.buildPolygonOrStarPath(3, half, null), paintFill)
                        "Hexagon" -> canvas.drawPath(FramePollutionGlRenderer.buildPolygonOrStarPath(6, half, null), paintFill)
                        else -> canvas.drawRoundRect(RectF(-half, -half * 0.75f, half, half * 0.75f), 20f, 20f, paintFill)
                    }
                    if (layer.strokeEnabled) {
                        val sCol = runCatching { Color.parseColor(layer.strokeColorHex) }.getOrDefault(Color.WHITE)
                        paintStroke.color = Color.argb(alpha, Color.red(sCol), Color.green(sCol), Color.blue(sCol))
                        paintStroke.strokeWidth = (layer.strokeWidth * scaleFactor * 2f).coerceIn(1.5f, 18f)
                        canvas.drawRoundRect(RectF(-half, -half * 0.75f, half, half * 0.75f), 20f, 20f, paintStroke)
                    }
                }
            }
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
            val eval = AnimationEvaluator.evaluateEffectNode(fx, timeMs, audio)
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
        return ((durationMs.coerceAtLeast(200L) / stepMs).toInt() + 1).coerceIn(1, 900)
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
}
