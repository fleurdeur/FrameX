package com.example.engine

import android.content.Context
import android.graphics.Bitmap
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES20
import android.os.Build
import com.example.data.model.LayerType
import com.example.data.model.Project
import com.example.data.preferences.PreviewQuality
import kotlinx.coroutines.suspendCancellableCoroutine
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

/**
 * Dedicated Offscreen EGL 1.4 PBuffer + Hardware `MediaCodec` GPU Pre-Render Engine:
 * - Executes the full [FramePollutionGlRenderer] multi-pass GLSL shader compositor offscreen on a
 *   pinned background GL thread (~0.8-2.0ms per frame instead of 60-200ms on CPU).
 * - Feeds active VIDEO layers via [GlSequentialVideoSession] (`MediaExtractor` + `MediaCodec` ->
 *   `GL_TEXTURE_EXTERNAL_OES`) so sequential frames decode in hardware with zero `MediaMetadataRetriever` seeks.
 * - Automatically returns `false` on headless JVM environments (such as Robolectric unit tests)
 *   so [PlaybackCacheEngine] seamlessly falls back to its multi-core parallel CPU renderer.
 */
object OffscreenGlPreRenderEngine {

    suspend fun preRenderOnGpu(
        context: Context,
        project: Project,
        quality: PreviewQuality,
        targetW: Int,
        targetH: Int,
        frameTimestampsMs: List<Long>,
        audioEngine: AudioReactorEngine,
        isAlreadyCached: (Long) -> Boolean,
        isStillValid: () -> Boolean,
        onFrameRendered: (index: Int, bucketMs: Long, bitmap: Bitmap) -> Unit
    ): Boolean {
        if (frameTimestampsMs.isEmpty()) return true
        if (Build.FINGERPRINT.equals("robolectric", ignoreCase = true)) return false
        val appCtx = context.applicationContext

        // 1. Transcode/reuse FFmpegKit short-GOP reversed MP4 proxies for reversed video layers so they
        //    stream strictly forward via GlSequentialVideoSession (`MediaExtractor + MediaCodec -> OES`)!
        val proxyEdge = maxOf(targetW, targetH).coerceIn(540, 1080)
        val reversedProxies = HashMap<String, FFmpegReverseVideoEngine.ReversedProxyMedia>()
        project.layers.forEach { layer ->
            if (layer.visible && layer.type == LayerType.VIDEO && layer.mediaUri.isNotBlank() &&
                layer.reversed && !layer.timeRemapEnabled
            ) {
                FFmpegReverseVideoEngine.ensureReversedProxy(appCtx, layer, proxyEdge)?.let { proxy ->
                    reversedProxies[layer.id] = proxy
                }
            }
        }

        // Only Time-Remapped video layers (or reversed layers whose FFmpeg proxy fell back) need nonlinear frame windowing
        val nonlinearVideoLayers = project.layers.filter {
            it.visible && it.type == LayerType.VIDEO && it.mediaUri.isNotBlank() &&
                (it.timeRemapEnabled || (it.reversed && it.id !in reversedProxies))
        }
        val cancelFlag = AtomicBoolean(false)

        return suspendCancellableCoroutine { cont ->
            cont.invokeOnCancellation {
                cancelFlag.set(true)
            }

            Thread({
                var eglDisplay: EGLDisplay = EGL14.EGL_NO_DISPLAY
                var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
                var eglSurface: EGLSurface = EGL14.EGL_NO_SURFACE
                var renderer: FramePollutionGlRenderer? = null
                val videoSessions = HashMap<String, GlSequentialVideoSession>()
                var gpuSucceeded = false

                try {
                    eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
                    if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
                        val version = IntArray(2)
                        if (EGL14.eglInitialize(eglDisplay, version, 0, version, 1)) {
                            val configAttribs = intArrayOf(
                                EGL14.EGL_RED_SIZE, 8,
                                EGL14.EGL_GREEN_SIZE, 8,
                                EGL14.EGL_BLUE_SIZE, 8,
                                EGL14.EGL_ALPHA_SIZE, 8,
                                EGL14.EGL_DEPTH_SIZE, 16,
                                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                                EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
                                EGL14.EGL_NONE
                            )
                            val configs = arrayOfNulls<EGLConfig>(1)
                            val numConfigs = IntArray(1)
                            if (EGL14.eglChooseConfig(eglDisplay, configAttribs, 0, configs, 0, 1, numConfigs, 0) &&
                                numConfigs[0] > 0 && configs[0] != null
                            ) {
                                val cfg = configs[0]!!
                                val ctxAttribs = intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE)
                                eglContext = EGL14.eglCreateContext(eglDisplay, cfg, EGL14.EGL_NO_CONTEXT, ctxAttribs, 0)
                                val surfAttribs = intArrayOf(
                                    EGL14.EGL_WIDTH, targetW,
                                    EGL14.EGL_HEIGHT, targetH,
                                    EGL14.EGL_NONE
                                )
                                eglSurface = EGL14.eglCreatePbufferSurface(eglDisplay, cfg, surfAttribs, 0)

                                if (eglContext != EGL14.EGL_NO_CONTEXT &&
                                    eglSurface != EGL14.EGL_NO_SURFACE &&
                                    EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)
                                ) {
                                    // Verify real GLES2 driver (null/blank in Robolectric shadow EGL)
                                    val glVer = GLES20.glGetString(GLES20.GL_VERSION)
                                    if (!glVer.isNullOrBlank()) {
                                        val glRenderer = FramePollutionGlRenderer(
                                            appContext = appCtx,
                                            offscreenMode = true
                                        )
                                        renderer = glRenderer
                                        glRenderer.previewQuality = quality
                                        glRenderer.usePlaybackCache = false
                                        glRenderer.onSurfaceCreated(null, null)
                                        glRenderer.onSurfaceChanged(null, targetW, targetH)

                                        // Attach sequential hardware OES decoders for forward/freeze AND FFmpeg-reversed video layers!
                                        project.layers.forEach { layer ->
                                            if (layer.visible &&
                                                layer.type == LayerType.VIDEO &&
                                                layer.mediaUri.isNotBlank() &&
                                                !layer.timeRemapEnabled
                                            ) {
                                                val revProxy = reversedProxies[layer.id]
                                                val sessionUri = when {
                                                    !layer.reversed -> layer.mediaUri
                                                    revProxy != null -> revProxy.fileUri
                                                    else -> null
                                                }
                                                if (sessionUri != null) {
                                                    val seq = GlSequentialVideoSession(appCtx, sessionUri)
                                                    if (seq.openOnCurrentGlContext()) {
                                                        videoSessions[layer.id] = seq
                                                    }
                                                }
                                            }
                                        }

                                        val rgbaBuf = ByteBuffer.allocateDirect(targetW * targetH * 4)
                                            .order(ByteOrder.nativeOrder())
                                        val warmEdge = maxOf(targetW, targetH).coerceIn(192, 640)

                                        var renderedAny = false
                                        for (idx in frameTimestampsMs.indices) {
                                            if (cancelFlag.get() || !isStillValid()) {
                                                gpuSucceeded = true
                                                break
                                            }
                                            val bucketMs = frameTimestampsMs[idx]
                                            if (isAlreadyCached(bucketMs)) {
                                                renderedAny = true
                                                continue
                                            }

                                            val audio = audioEngine.sampleTimelineSpectrumPure(
                                                bucketMs,
                                                project.bpm,
                                                project.beatMarkers
                                            )

                                            // Warm upcoming nonlinear/reversed video frames in bounded 100-frame windows
                                            // so earlier frames are never evicted before they are composited!
                                            if (nonlinearVideoLayers.isNotEmpty()) {
                                                for (nlLayer in nonlinearVideoLayers) {
                                                    if (bucketMs !in nlLayer.startTimeMs..nlLayer.endTimeMs) continue
                                                    val curSrc = AnimationEvaluator.evaluateSourceMediaTimeMs(nlLayer, bucketMs, audio)
                                                    val clampedSrc = if (nlLayer.sourceDurationMs > 50L) {
                                                        curSrc.coerceIn(0L, nlLayer.sourceDurationMs - 36L)
                                                    } else curSrc.coerceAtLeast(0L)
                                                    val curVidBucket = MediaEngine.quantizeVideoBucketMs(clampedSrc)
                                                    if (!MediaEngine.hasCachedVideoBucket(nlLayer.mediaUri, curVidBucket)) {
                                                        val endIdx = minOf(frameTimestampsMs.size, idx + 100)
                                                        val windowNeeded = frameTimestampsMs.subList(idx, endIdx)
                                                            .filter { it in nlLayer.startTimeMs..nlLayer.endTimeMs }
                                                            .map { tMs ->
                                                                val a = audioEngine.sampleTimelineSpectrum(tMs, project.bpm, project.beatMarkers)
                                                                val rawS = AnimationEvaluator.evaluateSourceMediaTimeMs(nlLayer, tMs, a)
                                                                val safeS = if (nlLayer.sourceDurationMs > 50L) {
                                                                    rawS.coerceIn(0L, nlLayer.sourceDurationMs - 36L)
                                                                } else rawS.coerceAtLeast(0L)
                                                                MediaEngine.quantizeVideoBucketMs(safeS)
                                                            }
                                                            .distinct()
                                                            .filter { !MediaEngine.hasCachedVideoBucket(nlLayer.mediaUri, it) }
                                                            .sorted()
                                                        if (windowNeeded.isNotEmpty()) {
                                                            SequentialVideoDecoder.decodeSortedTimestampsHardware(
                                                                context = appCtx,
                                                                uriStr = nlLayer.mediaUri,
                                                                sortedTimestampsMs = windowNeeded,
                                                                maxEdge = warmEdge,
                                                                cancelled = cancelFlag
                                                            ) { vBucket, bmp ->
                                                                MediaEngine.putPreDecodedVideoFrame(nlLayer.mediaUri, vBucket, bmp)
                                                            }
                                                            // Restore our PBuffer context on this thread after helper thread finishes
                                                            EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)
                                                        }
                                                    }
                                                }
                                            }

                                            // Advance active sequential hardware video decoders (including FFmpeg-reversed proxies!)
                                            videoSessions.forEach { (layerId, seq) ->
                                                val layer = project.layers.firstOrNull { it.id == layerId }
                                                if (layer != null && bucketMs in layer.startTimeMs..layer.endTimeMs) {
                                                    val rawSrcMs = AnimationEvaluator.evaluateSourceMediaTimeMs(
                                                        layer,
                                                        bucketMs,
                                                        audio
                                                    )
                                                    val revProxy = reversedProxies[layerId]
                                                    val seqTargetMs = if (layer.reversed && revProxy != null) {
                                                        FFmpegReverseVideoEngine.mapForwardSourceMsToReversedProxyMs(
                                                            rawSrcMs,
                                                            revProxy
                                                        )
                                                    } else {
                                                        rawSrcMs
                                                    }
                                                    if (seq.advanceToFrameMs(seqTargetMs)) {
                                                        val aspect = seq.videoWidth.toFloat() /
                                                            seq.videoHeight.toFloat().coerceAtLeast(1f)
                                                        glRenderer.externalOesOverrides[layerId] =
                                                            FramePollutionGlRenderer.ExternalOesOverride(
                                                                oesTexId = seq.oesTexId,
                                                                stMatrix = seq.stMatrix,
                                                                aspectWOverH = aspect
                                                            )
                                                    }
                                                }
                                            }

                                            val ok = glRenderer.renderOffscreenFrameToBuffer(
                                                project = project,
                                                timeMs = bucketMs,
                                                audio = audio,
                                                quality = quality,
                                                outRgbaBuffer = rgbaBuf
                                            )
                                            if (!ok) {
                                                gpuSucceeded = false
                                                break
                                            }

                                            rgbaBuf.position(0)
                                            val finalBmp = Bitmap.createBitmap(
                                                targetW,
                                                targetH,
                                                Bitmap.Config.ARGB_8888
                                            )
                                            finalBmp.copyPixelsFromBuffer(rgbaBuf)
                                            onFrameRendered(idx, bucketMs, finalBmp)
                                            renderedAny = true
                                        }
                                        if (renderedAny) gpuSucceeded = true
                                    }
                                }
                            }
                        }
                    }
                } catch (_: Throwable) {
                    gpuSucceeded = false
                } finally {
                    videoSessions.values.forEach { runCatching { it.release() } }
                    videoSessions.clear()
                    runCatching { renderer?.releaseGlResources() }
                    if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
                        runCatching {
                            EGL14.eglMakeCurrent(
                                eglDisplay,
                                EGL14.EGL_NO_SURFACE,
                                EGL14.EGL_NO_SURFACE,
                                EGL14.EGL_NO_CONTEXT
                            )
                        }
                        if (eglSurface != EGL14.EGL_NO_SURFACE) {
                            runCatching { EGL14.eglDestroySurface(eglDisplay, eglSurface) }
                        }
                        if (eglContext != EGL14.EGL_NO_CONTEXT) {
                            runCatching { EGL14.eglDestroyContext(eglDisplay, eglContext) }
                        }
                        runCatching { EGL14.eglTerminate(eglDisplay) }
                    }
                    if (cont.isActive) {
                        cont.resume(gpuSucceeded)
                    }
                }
            }, "OffscreenGpuPreRender").start()
        }
    }
}
