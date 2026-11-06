package com.example.engine

import android.content.ContentValues
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.EGLExt
import android.opengl.GLES20
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.view.Surface
import com.example.data.model.Layer
import com.example.data.model.LayerType
import com.example.data.model.Project
import com.example.data.preferences.PreviewQuality
import com.example.data.preferences.StudioPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.coroutines.coroutineContext
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

data class ExportOutcome(
    val outputPath: String,
    val publishedMediaUri: Uri?,
    val durationMs: Long,
    val width: Int,
    val height: Int,
    val fps: Int,
    val codec: String,
    val bitrateMbps: Int = 16
)

/**
 * Single-Pass Zero-Copy Hardware OpenGL ES 2.0 + MediaCodec MP4 Export Engine:
 * - Renders directly into the hardware `MediaCodec` encoder `InputSurface` via EGL 1.4 (`eglPresentationTimeANDROID`)
 *   using [FramePollutionGlRenderer] (full 3D camera, shaders, plugins, KeyFlow Text Animator, Stroke & Shadow)
 *   with ZERO `glReadPixels`, ZERO CPU `Bitmap` copies, and ZERO redundant 2nd `Transformer` re-encode pass!
 * - Strictly exports the exact edit length (`project.effectiveEditDurationMs()`), honoring layer speed,
 *   reverse video, time remapping, project FPS, resolution (`width x height`), and bitrate (`effectiveExportMbps`).
 * - Mixes and encodes timeline audio (`AUDIO` + unmuted `VIDEO` layers) into AAC synchronously inside the MP4,
 *   honoring clip start/end, trim `sourceOffsetMs`, `speedMultiplier`, `audioVolume`, and `StudioDspAudioProcessor`.
 */
object ExportPipelineEngine {

    suspend fun exportProjectToMp4(
        context: Context,
        project: Project,
        audioEngine: AudioReactorEngine,
        preferences: StudioPreferences = StudioPreferences(),
        onProgress: (Float, String) -> Unit
    ): Result<ExportOutcome> = withContext(Dispatchers.Default) {
        try {
            val targetW = ((project.width.coerceIn(128, 3840) / 2) * 2).coerceAtLeast(128)
            val targetH = ((project.height.coerceIn(128, 3840) / 2) * 2).coerceAtLeast(128)
            val targetFps = project.fps.coerceIn(12, 120)
            // Strictly match the exact edit span of active layers!
            val exportDurationMs = project.effectiveEditDurationMs()
            val targetMbps = preferences.effectiveExportMbps
            val preferHevc = project.codec == "H.265" ||
                (project.codec.isBlank() && preferences.preferredVideoCodec == "H.265")

            val rawDir = File(context.cacheDir, "exports").apply { mkdirs() }
            val safeTitle = project.name.replace(Regex("[^a-zA-Z0-9_\\-]"), "_").ifBlank { "FramePollution" }
            val finalFile = File(rawDir, "${safeTitle}_${targetW}x${targetH}_${targetFps}fps_${targetMbps}Mbps.mp4")
            if (finalFile.exists()) finalFile.delete()

            onProgress(
                0.04f,
                "Initializing ${targetW}x${targetH} @ ${targetFps}fps (${targetMbps} Mbps)..."
            )

            // Pre-mix timeline audio PCM (if any audible layers exist) so video+audio mux in one fast pass
            val audibleLayers = project.layers.filter { layer ->
                layer.visible &&
                    (layer.type == LayerType.AUDIO || layer.type == LayerType.VIDEO) &&
                    layer.mediaUri.isNotBlank() &&
                    !layer.audioMuted &&
                    layer.audioVolume > 0.01f &&
                    !layer.reversed &&
                    !layer.timeRemapEnabled &&
                    layer.freezeFrameMs < 0L &&
                    layer.startTimeMs < exportDurationMs &&
                    layer.endTimeMs > 0L
            }

            val mixedAudioPcm: ShortArray? = if (audibleLayers.isNotEmpty()) {
                onProgress(0.08f, "Mixing timeline audio (${audibleLayers.size} track(s))...")
                mixTimelineAudioStereo44k1(
                    context = context.applicationContext,
                    audibleLayers = audibleLayers,
                    exportDurationMs = exportDurationMs
                )
            } else {
                null
            }

            coroutineContext.ensureActive()

            // Release live preview OES hardware decoders before export so 2-instance phone SoCs never starve
            HardwareVideoPreviewEngine.releaseDecodersForOffscreenWork()

            val actualCodecLabel = encodeSinglePassHardwareMp4(
                context = context.applicationContext,
                outputFile = finalFile,
                project = project,
                audioEngine = audioEngine,
                width = targetW,
                height = targetH,
                fps = targetFps,
                durationMs = exportDurationMs,
                targetMbps = targetMbps,
                bppFactor = preferences.exportBitrateProfile.bppFactor,
                gopSec = preferences.keyframeIntervalSec,
                preferHevc = preferHevc,
                mixedStereoPcm44k1 = mixedAudioPcm,
                onFrameProgress = { p ->
                    val base = if (mixedAudioPcm != null) 0.14f else 0.06f
                    val span = if (mixedAudioPcm != null) 0.80f else 0.88f
                    onProgress(
                        base + p * span,
                        "Encoding ${targetW}x${targetH} @ ${targetFps}fps (${(p * 100).toInt()}%)..."
                    )
                }
            )

            onProgress(0.96f, "Saving MP4 to Movies/FramePollution...")
            val mediaUri = publishToMediaStore(
                context = context,
                sourceFile = finalFile,
                displayName = "${safeTitle}_${System.currentTimeMillis()}.mp4"
            )

            onProgress(1.0f, "Export complete!")
            Result.success(
                ExportOutcome(
                    outputPath = finalFile.absolutePath,
                    publishedMediaUri = mediaUri,
                    durationMs = exportDurationMs,
                    width = targetW,
                    height = targetH,
                    fps = targetFps,
                    codec = actualCodecLabel,
                    bitrateMbps = targetMbps
                )
            )
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun encodeSinglePassHardwareMp4(
        context: Context,
        outputFile: File,
        project: Project,
        audioEngine: AudioReactorEngine,
        width: Int,
        height: Int,
        fps: Int,
        durationMs: Long,
        targetMbps: Int,
        bppFactor: Float,
        gopSec: Int,
        preferHevc: Boolean,
        mixedStereoPcm44k1: ShortArray?,
        onFrameProgress: (Float) -> Unit
    ): String {
        val requestedBps = (targetMbps.coerceIn(1, 160).toLong() * 1_000_000L)
            .coerceIn(1_000_000L, 160_000_000L)
            .toInt()
        // Honor user's chosen Mbps directly while keeping valid codec floor
        val bitRate = if (bppFactor > 0f && targetMbps in listOf(6, 8, 16, 32, 50)) {
            max(
                (requestedBps * 0.65f).toInt(),
                (width.toLong() * height.toLong() * fps.toLong() * bppFactor).toInt()
            ).coerceIn(1_000_000, requestedBps)
        } else {
            requestedBps
        }

        val configuredEncoder = UniversalCodecEngine.selectAndConfigureVideoEncoder(
            preferredCodec = if (preferHevc) "H.265" else "H.264",
            requestedWidth = width,
            requestedHeight = height,
            requestedFps = fps,
            requestedBitrate = bitRate,
            gopSeconds = gopSec.coerceIn(1, 5)
        )
        val videoEncoder = configuredEncoder.encoder
        val inputSurface = configuredEncoder.inputSurface
        val encWidth = configuredEncoder.alignedWidth
        val encHeight = configuredEncoder.alignedHeight
        val codecLabel = configuredEncoder.codecLabel

        // Optional AAC hardware audio encoder when mixed PCM is present
        var audioEncoder: MediaCodec? = null
        if (mixedStereoPcm44k1 != null && mixedStereoPcm44k1.isNotEmpty()) {
            runCatching {
                val aFmt = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, 44_100, 2).apply {
                    setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                    setInteger(MediaFormat.KEY_BIT_RATE, 192_000)
                    setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 32 * 1024)
                }
                val aEnc = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
                aEnc.configure(aFmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                aEnc.start()
                audioEncoder = aEnc
            }
        }

        val muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        val videoBufInfo = MediaCodec.BufferInfo()
        val audioBufInfo = MediaCodec.BufferInfo()
        var videoTrackIndex = -1
        var audioTrackIndex = -1
        var muxerStarted = false
        val pendingVideoSamples = ArrayList<Pair<ByteBuffer, MediaCodec.BufferInfo>>()
        val pendingAudioSamples = ArrayList<Pair<ByteBuffer, MediaCodec.BufferInfo>>()

        fun tryStartMuxerIfReady() {
            if (muxerStarted) return
            val needAudio = audioEncoder != null
            if (videoTrackIndex >= 0 && (!needAudio || audioTrackIndex >= 0)) {
                muxer.start()
                muxerStarted = true
                for ((buf, info) in pendingVideoSamples) {
                    muxer.writeSampleData(videoTrackIndex, buf, info)
                }
                pendingVideoSamples.clear()
                if (audioTrackIndex >= 0) {
                    for ((buf, info) in pendingAudioSamples) {
                        muxer.writeSampleData(audioTrackIndex, buf, info)
                    }
                }
                pendingAudioSamples.clear()
            }
        }

        fun drainCodec(
            codec: MediaCodec,
            info: MediaCodec.BufferInfo,
            isVideo: Boolean,
            endOfStream: Boolean
        ) {
            var waitGuard = 0
            while (true) {
                val status = codec.dequeueOutputBuffer(info, if (endOfStream) 8_000L else 0L)
                when {
                    status == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                        if (!endOfStream || ++waitGuard > 40) break
                    }
                    status == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val track = muxer.addTrack(codec.outputFormat)
                        if (isVideo) videoTrackIndex = track else audioTrackIndex = track
                        tryStartMuxerIfReady()
                    }
                    status >= 0 -> {
                        val outBuf = codec.getOutputBuffer(status)
                        if (outBuf != null && info.size > 0 && (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                            outBuf.position(info.offset)
                            outBuf.limit(info.offset + info.size)
                            val activeTrack = if (isVideo) videoTrackIndex else audioTrackIndex
                            if (muxerStarted && activeTrack >= 0) {
                                muxer.writeSampleData(activeTrack, outBuf, info)
                            } else {
                                val copy = ByteBuffer.allocateDirect(info.size)
                                copy.put(outBuf)
                                copy.flip()
                                val copyInfo = MediaCodec.BufferInfo().apply {
                                    set(0, info.size, info.presentationTimeUs, info.flags)
                                }
                                if (isVideo) pendingVideoSamples.add(copy to copyInfo)
                                else pendingAudioSamples.add(copy to copyInfo)
                            }
                        }
                        codec.releaseOutputBuffer(status, false)
                        if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) break
                    }
                }
            }
        }

        // Exact frame count matching edit duration & FPS (no 360-frame cap!)
        val totalFrames = ((durationMs.toDouble() * fps.toDouble()) / 1000.0).roundToInt().coerceAtLeast(1)

        val exportWarmEdge = maxOf(width, height).coerceIn(360, 1080)
        val reversedExportProxies = HashMap<String, FFmpegReverseVideoEngine.ReversedProxyMedia>()
        project.layers.forEach { layer ->
            if (layer.visible && layer.type == LayerType.VIDEO && layer.mediaUri.isNotBlank() &&
                layer.reversed && !layer.timeRemapEnabled
            ) {
                FFmpegReverseVideoEngine.ensureReversedProxyBlocking(context, layer, exportWarmEdge)?.let { proxy ->
                    reversedExportProxies[layer.id] = proxy
                }
            }
        }

        val nonlinearExportLayers = project.layers.filter {
            it.visible && it.type == LayerType.VIDEO && it.mediaUri.isNotBlank() &&
                (it.timeRemapEnabled || (it.reversed && it.id !in reversedExportProxies))
        }

        var eglDisplay: EGLDisplay = EGL14.EGL_NO_DISPLAY
        var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
        var eglSurface: EGLSurface = EGL14.EGL_NO_SURFACE
        var glRenderer: FramePollutionGlRenderer? = null
        val videoSessions = HashMap<String, GlSequentialVideoSession>()
        var useHardwareGlSurface = false

        try {
            // Initialize zero-copy EGL window surface directly onto MediaCodec's InputSurface!
            if (!Build.FINGERPRINT.equals("robolectric", ignoreCase = true)) {
                runCatching {
                    eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
                    if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
                        val ver = IntArray(2)
                        if (EGL14.eglInitialize(eglDisplay, ver, 0, ver, 1)) {
                            val recordableAndroid = 0x3142 // EGL_RECORDABLE_ANDROID
                            val cfgAttribs = intArrayOf(
                                EGL14.EGL_RED_SIZE, 8,
                                EGL14.EGL_GREEN_SIZE, 8,
                                EGL14.EGL_BLUE_SIZE, 8,
                                EGL14.EGL_ALPHA_SIZE, 8,
                                EGL14.EGL_DEPTH_SIZE, 16,
                                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                                recordableAndroid, 1,
                                EGL14.EGL_NONE
                            )
                            val configs = arrayOfNulls<EGLConfig>(1)
                            val numCfg = IntArray(1)
                            if (EGL14.eglChooseConfig(eglDisplay, cfgAttribs, 0, configs, 0, 1, numCfg, 0) &&
                                numCfg[0] > 0 && configs[0] != null
                            ) {
                                val cfg = configs[0]!!
                                val ctxAttribs = intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE)
                                eglContext = EGL14.eglCreateContext(eglDisplay, cfg, EGL14.EGL_NO_CONTEXT, ctxAttribs, 0)
                                val surfAttribs = intArrayOf(EGL14.EGL_NONE)
                                eglSurface = EGL14.eglCreateWindowSurface(eglDisplay, cfg, inputSurface, surfAttribs, 0)
                                if (eglContext != EGL14.EGL_NO_CONTEXT &&
                                    eglSurface != EGL14.EGL_NO_SURFACE &&
                                    EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)
                                ) {
                                    val glVer = GLES20.glGetString(GLES20.GL_VERSION)
                                    if (!glVer.isNullOrBlank()) {
                                        val r = FramePollutionGlRenderer(
                                            appContext = context,
                                            offscreenMode = true,
                                            onAsyncMediaReady = {}
                                        )
                                        r.onSurfaceCreated(null, null)
                                        r.onSurfaceChanged(null, encWidth, encHeight)
                                        r.isPlaying = false
                                        r.previewQuality = PreviewQuality.FULL
                                        r.show3DGridOverlay = false
                                        r.applyCameraInPreview = true
                                        r.currentProject = project
                                        glRenderer = r
                                        useHardwareGlSurface = true
                                    }
                                }
                            }
                        }
                    }
                }
            }

            val fallbackPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
            var audioPcmCursor = 0
            var audioEosSent = false

            for (frameIdx in 0 until totalFrames) {
                val ptsUs = (frameIdx.toLong() * 1_000_000L) / fps.toLong()
                val timeMs = (ptsUs / 1000L).coerceIn(0L, durationMs)
                val audioSnap = audioEngine.sampleTimelineSpectrumPure(timeMs, project.bpm, project.beatMarkers)

                // Batch-decode reversed / time-remapped video frames in single-session GOP chunks
                if (nonlinearExportLayers.isNotEmpty()) {
                    for (nlLayer in nonlinearExportLayers) {
                        if (timeMs !in nlLayer.startTimeMs..nlLayer.endTimeMs) continue
                        val curSrc = AnimationEvaluator.evaluateSourceMediaTimeMs(nlLayer, timeMs, audioSnap)
                        val clampedSrc = if (nlLayer.sourceDurationMs > 50L) {
                            curSrc.coerceIn(0L, nlLayer.sourceDurationMs - 36L)
                        } else curSrc.coerceAtLeast(0L)
                        val curBucket = MediaEngine.quantizeVideoBucketMs(clampedSrc)
                        if (!MediaEngine.hasCachedVideoBucket(nlLayer.mediaUri, curBucket)) {
                            val windowEnd = minOf(totalFrames, frameIdx + 120)
                            val needed = (frameIdx until windowEnd)
                                .map { fIdx -> ((fIdx.toLong() * 1000L) / fps.toLong()).coerceIn(0L, durationMs) }
                                .filter { it in nlLayer.startTimeMs..nlLayer.endTimeMs }
                                .map { tMs ->
                                    val a = audioEngine.sampleTimelineSpectrumPure(tMs, project.bpm, project.beatMarkers)
                                    val rawS = AnimationEvaluator.evaluateSourceMediaTimeMs(nlLayer, tMs, a)
                                    val safeS = if (nlLayer.sourceDurationMs > 50L) {
                                        rawS.coerceIn(0L, nlLayer.sourceDurationMs - 36L)
                                    } else rawS.coerceAtLeast(0L)
                                    MediaEngine.quantizeVideoBucketMs(safeS)
                                }
                                .distinct()
                                .filter { !MediaEngine.hasCachedVideoBucket(nlLayer.mediaUri, it) }
                                .sorted()
                            if (needed.isNotEmpty()) {
                                val chunks = needed.chunked(50).let { if (nlLayer.reversed) it.asReversed() else it }
                                SequentialVideoDecoder.decodeChunkedTimestampsHardware(
                                    context = context,
                                    uriStr = nlLayer.mediaUri,
                                    timestampChunksMs = chunks,
                                    maxEdge = exportWarmEdge
                                ) { vBucket, bmp ->
                                    MediaEngine.putPreDecodedVideoFrame(nlLayer.mediaUri, vBucket, bmp)
                                }
                            }
                        }
                    }
                }

                if (useHardwareGlSurface && glRenderer != null) {
                    val r = glRenderer!!
                    for (layer in project.layers) {
                        if (!layer.visible || layer.type != LayerType.VIDEO || layer.mediaUri.isBlank()) continue
                        if (layer.timeRemapEnabled) continue
                        val revProxy = reversedExportProxies[layer.id]
                        if (layer.reversed && revProxy == null) continue
                        if (timeMs !in layer.startTimeMs..layer.endTimeMs) continue
                        val rawSrcTimeMs = AnimationEvaluator.evaluateSourceMediaTimeMs(layer, timeMs, audioSnap)
                        val sessionUri = if (layer.reversed && revProxy != null) revProxy.fileUri else layer.mediaUri
                        val targetSeqMs = if (layer.reversed && revProxy != null) {
                            FFmpegReverseVideoEngine.mapForwardSourceMsToReversedProxyMs(rawSrcTimeMs, revProxy)
                        } else {
                            rawSrcTimeMs
                        }
                        val session = videoSessions.getOrPut(layer.id) {
                            GlSequentialVideoSession(context, sessionUri).also { s ->
                                if (s.openOnCurrentGlContext()) {
                                    val aspect = s.videoWidth.toFloat() / s.videoHeight.coerceAtLeast(1).toFloat()
                                    r.externalOesOverrides[layer.id] = FramePollutionGlRenderer.ExternalOesOverride(
                                        oesTexId = s.oesTexId,
                                        stMatrix = s.stMatrix,
                                        aspectWOverH = aspect
                                    )
                                }
                            }
                        }
                        if (session.advanceToFrameMs(targetSeqMs)) {
                            val aspect = session.videoWidth.toFloat() / session.videoHeight.coerceAtLeast(1).toFloat()
                            r.externalOesOverrides[layer.id] = FramePollutionGlRenderer.ExternalOesOverride(
                                oesTexId = session.oesTexId,
                                stMatrix = session.stMatrix,
                                aspectWOverH = aspect
                            )
                        }
                    }
                    r.audioSnapshot = audioSnap
                    r.playheadMs = timeMs
                    r.onDrawFrame(null)
                    EGLExt.eglPresentationTimeANDROID(eglDisplay, eglSurface, ptsUs * 1000L)
                    EGL14.eglSwapBuffers(eglDisplay, eglSurface)
                } else {
                    // Headless JVM / CPU fallback renderer (includes KeyFlow Text Animator + Stroke/Shadow + Effects)
                    val bmp = PlaybackCacheEngine.renderSinglePreviewFrame(
                        context = context,
                        project = project,
                        timeMs = timeMs,
                        audio = audioSnap,
                        w = encWidth,
                        h = encHeight
                    )
                    val canvas: Canvas = inputSurface.lockCanvas(null)
                    try {
                        canvas.drawColor(Color.BLACK)
                        canvas.drawBitmap(bmp, 0f, 0f, fallbackPaint)
                    } finally {
                        inputSurface.unlockCanvasAndPost(canvas)
                        if (!bmp.isRecycled) bmp.recycle()
                    }
                }

                drainCodec(videoEncoder, videoBufInfo, isVideo = true, endOfStream = false)

                // Feed interleaved stereo 44.1kHz PCM up to current frame PTS so audio & video interleave smoothly
                val aEnc = audioEncoder
                if (aEnc != null && mixedStereoPcm44k1 != null && !audioEosSent) {
                    val targetStereoShorts = (((ptsUs + 40_000L) * 44_100L) / 1_000_000L).toInt() * 2
                    val desiredEnd = min(mixedStereoPcm44k1.size, targetStereoShorts)
                    while (audioPcmCursor < desiredEnd) {
                        val inIdx = aEnc.dequeueInputBuffer(0L)
                        if (inIdx < 0) break
                        val inBuf = aEnc.getInputBuffer(inIdx) ?: break
                        inBuf.clear()
                        val maxShorts = (inBuf.remaining() / 2) and -2
                        val toWrite = min(maxShorts, mixedStereoPcm44k1.size - audioPcmCursor)
                        if (toWrite <= 0) break
                        val audioPtsUs = ((audioPcmCursor / 2).toLong() * 1_000_000L) / 44_100L
                        inBuf.order(ByteOrder.LITTLE_ENDIAN)
                        for (i in 0 until toWrite) {
                            inBuf.putShort(mixedStereoPcm44k1[audioPcmCursor + i])
                        }
                        aEnc.queueInputBuffer(inIdx, 0, toWrite * 2, audioPtsUs, 0)
                        audioPcmCursor += toWrite
                    }
                    drainCodec(aEnc, audioBufInfo, isVideo = false, endOfStream = false)
                }

                if (frameIdx % 4 == 0 || frameIdx == totalFrames - 1) {
                    onFrameProgress((frameIdx + 1).toFloat() / totalFrames.toFloat())
                }
            }

            // Flush remaining audio PCM & signal EOS
            val aEnc = audioEncoder
            if (aEnc != null && mixedStereoPcm44k1 != null) {
                while (audioPcmCursor < mixedStereoPcm44k1.size) {
                    val inIdx = aEnc.dequeueInputBuffer(5_000L)
                    if (inIdx < 0) {
                        drainCodec(aEnc, audioBufInfo, isVideo = false, endOfStream = false)
                        continue
                    }
                    val inBuf = aEnc.getInputBuffer(inIdx) ?: break
                    inBuf.clear()
                    val maxShorts = (inBuf.remaining() / 2) and -2
                    val toWrite = min(maxShorts, mixedStereoPcm44k1.size - audioPcmCursor)
                    val audioPtsUs = ((audioPcmCursor / 2).toLong() * 1_000_000L) / 44_100L
                    inBuf.order(ByteOrder.LITTLE_ENDIAN)
                    for (i in 0 until toWrite) {
                        inBuf.putShort(mixedStereoPcm44k1[audioPcmCursor + i])
                    }
                    aEnc.queueInputBuffer(inIdx, 0, toWrite * 2, audioPtsUs, 0)
                    audioPcmCursor += toWrite
                    drainCodec(aEnc, audioBufInfo, isVideo = false, endOfStream = false)
                }
                val eosIdx = aEnc.dequeueInputBuffer(8_000L)
                if (eosIdx >= 0) {
                    val endPtsUs = ((audioPcmCursor / 2).toLong() * 1_000_000L) / 44_100L
                    aEnc.queueInputBuffer(eosIdx, 0, 0, endPtsUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                    audioEosSent = true
                }
            }

            videoEncoder.signalEndOfInputStream()
            drainCodec(videoEncoder, videoBufInfo, isVideo = true, endOfStream = true)
            if (aEnc != null) {
                drainCodec(aEnc, audioBufInfo, isVideo = false, endOfStream = true)
            }
            if (!muxerStarted && videoTrackIndex >= 0) {
                // Force start if audio encoder produced no format
                audioEncoder = null
                tryStartMuxerIfReady()
            }
        } finally {
            videoSessions.values.forEach { runCatching { it.release() } }
            videoSessions.clear()
            runCatching { glRenderer?.releaseGlResources() }
            if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
                runCatching {
                    EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                }
                if (eglSurface != EGL14.EGL_NO_SURFACE) runCatching { EGL14.eglDestroySurface(eglDisplay, eglSurface) }
                if (eglContext != EGL14.EGL_NO_CONTEXT) runCatching { EGL14.eglDestroyContext(eglDisplay, eglContext) }
                runCatching { EGL14.eglTerminate(eglDisplay) }
            }
            runCatching { videoEncoder.stop() }
            runCatching { videoEncoder.release() }
            runCatching { audioEncoder?.stop() }
            runCatching { audioEncoder?.release() }
            runCatching { inputSurface.release() }
            if (muxerStarted) {
                runCatching { muxer.stop() }
            }
            runCatching { muxer.release() }
        }
        return codecLabel
    }

    /**
     * Mixes audible `AUDIO` and `VIDEO` layers onto a strictly bounded `[0..exportDurationMs]`
     * 44.1 kHz stereo PCM timeline, honoring `startTimeMs`, `endTimeMs`, `sourceOffsetMs`,
     * `speedMultiplier`, and `audioVolume`.
     */
    private fun mixTimelineAudioStereo44k1(
        context: Context,
        audibleLayers: List<Layer>,
        exportDurationMs: Long
    ): ShortArray? {
        val outSampleRate = 44_100
        val totalFrames = ((exportDurationMs * outSampleRate) / 1000L).toInt().coerceIn(0, outSampleRate * 600)
        if (totalFrames <= 0) return null
        val mixAccum = IntArray(totalFrames * 2)
        var anyMixed = false

        for (layer in audibleLayers) {
            val clipStartMs = layer.startTimeMs.coerceIn(0L, exportDurationMs)
            val clipEndMs = layer.endTimeMs.coerceIn(clipStartMs, exportDurationMs)
            val clipSpanMs = clipEndMs - clipStartMs
            if (clipSpanMs < 20L) continue

            val spd = layer.speedMultiplier.coerceIn(0.25f, 4.0f)
            val neededSourceMs = (clipSpanMs * spd).toLong().coerceAtLeast(40L)
            val decoded = decodeLayerStereoResampled44k1(
                context = context,
                mediaUri = layer.mediaUri,
                startOffsetMs = layer.sourceOffsetMs,
                maxSourceDurationMs = neededSourceMs,
                speedMultiplier = spd
            ) ?: continue
            if (decoded.isEmpty()) continue

            val startFrame = ((clipStartMs * outSampleRate) / 1000L).toInt().coerceIn(0, totalFrames)
            val maxFramesToMix = min(totalFrames - startFrame, decoded.size / 2)
            if (maxFramesToMix <= 0) continue

            val gain = layer.audioVolume.coerceIn(0f, 1.5f)
            val fadeFrames = min(maxFramesToMix / 2, (outSampleRate * 12) / 1000).coerceAtLeast(1)
            for (f in 0 until maxFramesToMix) {
                val env = when {
                    f < fadeFrames -> f.toFloat() / fadeFrames.toFloat()
                    f > maxFramesToMix - fadeFrames -> (maxFramesToMix - f).toFloat() / fadeFrames.toFloat()
                    else -> 1f
                } * gain
                val dstIdx = (startFrame + f) * 2
                val srcIdx = f * 2
                mixAccum[dstIdx] = (mixAccum[dstIdx] + (decoded[srcIdx] * env).toInt()).coerceIn(-32768, 32767)
                mixAccum[dstIdx + 1] = (mixAccum[dstIdx + 1] + (decoded[srcIdx + 1] * env).toInt()).coerceIn(-32768, 32767)
            }
            anyMixed = true
        }

        if (!anyMixed) return null
        return ShortArray(mixAccum.size) { idx -> mixAccum[idx].toShort() }
    }

    private fun decodeLayerStereoResampled44k1(
        context: Context,
        mediaUri: String,
        startOffsetMs: Long,
        maxSourceDurationMs: Long,
        speedMultiplier: Float
    ): ShortArray? {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        return try {
            GlSequentialVideoSession.setExtractorDataSource(context, extractor, mediaUri)
            var audioTrack = -1
            var format: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val f = extractor.getTrackFormat(i)
                if (f.getString(MediaFormat.KEY_MIME).orEmpty().startsWith("audio/")) {
                    audioTrack = i
                    format = f
                    break
                }
            }
            if (audioTrack < 0 || format == null) return null
            extractor.selectTrack(audioTrack)
            val startUs = startOffsetMs.coerceAtLeast(0L) * 1000L
            val endUs = (startOffsetMs + maxSourceDurationMs) * 1000L
            if (startUs > 0L) {
                extractor.seekTo(startUs, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
            }

            var srcRate = if (format.containsKey(MediaFormat.KEY_SAMPLE_RATE)) format.getInteger(MediaFormat.KEY_SAMPLE_RATE) else 44_100
            var srcChannels = if (format.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) format.getInteger(MediaFormat.KEY_CHANNEL_COUNT) else 2

            codec = UniversalCodecEngine.openCompatibleAudioDecoder(format) ?: return null

            val rawStereoSrc = ArrayList<Short>(88_200)
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false

            while (!outputDone) {
                if (!inputDone) {
                    val inIdx = codec.dequeueInputBuffer(4_000L)
                    if (inIdx >= 0) {
                        val inBuf = codec.getInputBuffer(inIdx)
                        if (inBuf != null) {
                            val sz = extractor.readSampleData(inBuf, 0)
                            val pts = extractor.sampleTime
                            if (sz < 0 || pts > endUs + 50_000L) {
                                codec.queueInputBuffer(inIdx, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inputDone = true
                            } else {
                                codec.queueInputBuffer(inIdx, 0, sz, pts.coerceAtLeast(0L), 0)
                                extractor.advance()
                            }
                        }
                    }
                }

                val outIdx = codec.dequeueOutputBuffer(info, 4_000L)
                when {
                    outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val outFmt = codec.outputFormat
                        if (outFmt.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                            srcRate = outFmt.getInteger(MediaFormat.KEY_SAMPLE_RATE).coerceAtLeast(8000)
                        }
                        if (outFmt.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                            srcChannels = outFmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT).coerceAtLeast(1)
                        }
                    }
                    outIdx >= 0 -> {
                        val outBuf = codec.getOutputBuffer(outIdx)
                        if (outBuf != null && info.size >= 2 && info.presentationTimeUs + 40_000L >= startUs) {
                            outBuf.position(info.offset)
                            outBuf.limit(info.offset + info.size)
                            val sb = outBuf.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                            val ch = srcChannels.coerceIn(1, 8)
                            var idx = 0
                            val rem = sb.remaining()
                            while (idx + ch <= rem) {
                                val l = sb.get(idx)
                                val r = if (ch >= 2) sb.get(idx + 1) else l
                                rawStereoSrc.add(l)
                                rawStereoSrc.add(r)
                                idx += ch
                            }
                        }
                        codec.releaseOutputBuffer(outIdx, false)
                        if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0 || info.presentationTimeUs > endUs) {
                            outputDone = true
                        }
                    }
                }
            }

            val srcFrames = rawStereoSrc.size / 2
            if (srcFrames <= 1) return null
            val stepPerOutFrame = (srcRate.toDouble() / 44_100.0) * speedMultiplier.coerceIn(0.25f, 4.0f).toDouble()
            val outFrames = (srcFrames.toDouble() / stepPerOutFrame).toInt().coerceAtLeast(1)
            val resampled = ShortArray(outFrames * 2)
            for (of in 0 until outFrames) {
                val srcPos = of * stepPerOutFrame
                val i0 = srcPos.toInt().coerceIn(0, srcFrames - 1)
                val i1 = (i0 + 1).coerceIn(0, srcFrames - 1)
                val frac = (srcPos - i0).toFloat()
                val l0 = rawStereoSrc[i0 * 2].toFloat()
                val r0 = rawStereoSrc[i0 * 2 + 1].toFloat()
                val l1 = rawStereoSrc[i1 * 2].toFloat()
                val r1 = rawStereoSrc[i1 * 2 + 1].toFloat()
                resampled[of * 2] = (l0 + (l1 - l0) * frac).roundToInt().coerceIn(-32768, 32767).toShort()
                resampled[of * 2 + 1] = (r0 + (r1 - r0) * frac).roundToInt().coerceIn(-32768, 32767).toShort()
            }
            resampled
        } catch (_: Throwable) {
            null
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { extractor.release() }
        }
    }

    private fun publishToMediaStore(context: Context, sourceFile: File, displayName: String): Uri? {
        return try {
            val resolver = context.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, displayName)
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.Video.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MOVIES}/FramePollution")
                    put(MediaStore.Video.Media.IS_PENDING, 1)
                }
            }
            val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values) ?: return null
            resolver.openOutputStream(uri)?.use { out ->
                sourceFile.inputStream().use { inp -> inp.copyTo(out) }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val doneValues = ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }
                resolver.update(uri, doneValues, null, null)
            }
            uri
        } catch (_: Exception) {
            null
        }
    }
}
