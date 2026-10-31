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
import android.graphics.Typeface
import android.graphics.SurfaceTexture
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.GLUtils
import android.os.Handler
import android.os.Looper
import android.view.Surface
import com.example.data.model.EffectNode
import com.example.data.model.Layer
import com.example.data.model.LayerType
import com.example.data.model.Primitive3DType
import com.example.data.model.Project
import com.example.data.preferences.PreviewQuality
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.ShortBuffer
import java.util.concurrent.atomic.AtomicInteger
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * Pure GPU Per-Layer Quad + Multi-Pass Ping-Pong OpenGL ES 2.0 Compositor:
 * - Zero-Copy `GL_TEXTURE_EXTERNAL_OES` SurfaceTexture pipeline for 60 FPS hardware video preview
 * - Supports Offscreen EGL PBuffer GPU Pre-Rendering (`renderOffscreenFrameToBuffer`)
 * - Caches Shape & Text rasterizations in dedicated GPU textures (0 CPU work during keyframe playback)
 * - Supports Camera layers, Clipping Masks, Blend Modes, and multi-sample GPU Motion Blur shutter
 */
class FramePollutionGlRenderer(
    private val appContext: Context,
    private val offscreenMode: Boolean = false,
    private val onAsyncMediaReady: () -> Unit = {}
) : GLSurfaceView.Renderer {

    val rendererToken: Int = System.identityHashCode(this)
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile var currentProject: Project = Project()
    @Volatile var playheadMs: Long = 0L
    @Volatile var isPlaying: Boolean = false
    @Volatile var playbackAnchorNano: Long = 0L
    @Volatile var playbackAnchorMs: Long = 0L

    @Volatile var audioSnapshot: AudioSpectrumSnapshot = AudioSpectrumSnapshot()
    @Volatile var cameraYawDeg: Float = 24f
    @Volatile var cameraPitchDeg: Float = 16f
    @Volatile var cameraDistance: Float = 4.2f
    @Volatile var show3DGridOverlay: Boolean = false

    @Volatile var previewQuality: PreviewQuality = PreviewQuality.HALF
    @Volatile var previewFpsCap: Int = 60
    @Volatile var isLooping: Boolean = true
    @Volatile var projectRevision: Long = 0L
    @Volatile var usePlaybackCache: Boolean = true

    @Volatile var liveFps: Int = 60
    @Volatile var lastFrameRenderMs: Float = 1.0f

    private var fpsWindowStartNano: Long = 0L
    private var fpsWindowFrames: Int = 0

    private var surfaceWidth = 1280
    private var surfaceHeight = 720

    private var quadProgram = 0
    private var checkerBgProgram = 0
    private var layerQuadProgram = 0
    private var oesLayerQuadProgram = 0
    private var mesh3DProgram = 0
    private var effectProgram = 0

    // Cached Uniform & Attribute Locations (0 JNI string lookups per frame!)
    private var quadPosLoc = -1
    private var quadUvLoc = -1
    private var quadFlipLoc = -1
    private var quadTexLoc = -1

    private var chkPosLoc = -1
    private var chkUvLoc = -1
    private var chkFlipLoc = -1
    private var chkResLoc = -1
    private var chkGridLoc = -1

    private var lyrPosLoc = -1
    private var lyrUvLoc = -1
    private var lyrCenterLoc = -1
    private var lyrHalfSizeLoc = -1
    private var lyrPivotLoc = -1
    private var lyrRotLoc = -1
    private var lyrViewportLoc = -1
    private var lyrOpacityLoc = -1
    private var lyrTexLoc = -1

    private var oesPosLoc = -1
    private var oesUvLoc = -1
    private var oesCenterLoc = -1
    private var oesHalfSizeLoc = -1
    private var oesPivotLoc = -1
    private var oesRotLoc = -1
    private var oesViewportLoc = -1
    private var oesOpacityLoc = -1
    private var oesTexMatLoc = -1
    private var oesTexLoc = -1

    private var m3dPosLoc = -1
    private var m3dNormLoc = -1
    private var m3dMvpLoc = -1
    private var m3dColorLoc = -1
    private var m3dTimeLoc = -1

    private var fxPosLoc = -1
    private var fxUvLoc = -1
    private var fxFlipLoc = -1
    private var fxModeLoc = -1
    private var fxIntensityLoc = -1
    private var fxSecondaryLoc = -1
    private var fxFreqLoc = -1
    private var fxAngleLoc = -1
    private var fxMixLoc = -1
    private var fxTintLoc = -1
    private var fxTimeLoc = -1
    private var fxResLoc = -1
    private var fxQualityLoc = -1
    private var fxTexLoc = -1

    private val fboIds = IntArray(2)
    private val fboTexIds = IntArray(2)
    private var fboAllocW = 0
    private var fboAllocH = 0

    private var cachedPlaybackTexId = 0
    private var cachedPlaybackTexW = 0
    private var cachedPlaybackTexH = 0
    private var cachedPlaybackIdentity = 0

    private data class GpuLayerTexture(
        val texId: Int,
        var isExternalOes: Boolean = false,
        var oesMatrix: FloatArray? = null,
        var signature: String = "",
        var bitmapIdentity: Int = 0,
        var texW: Int = 0,
        var texH: Int = 0,
        var aspectWOverH: Float = 1f
    )

    private class OesLayerSurfaceSlot(
        val oesTexId: Int,
        val surfaceTexture: SurfaceTexture,
        val surface: Surface,
        var boundUri: String
    ) {
        val stMatrix = FloatArray(16).also { android.opengl.Matrix.setIdentityM(it, 0) }
        val pendingFrames = AtomicInteger(0)
        var hasLatchedFrame: Boolean = false
    }

    data class ExternalOesOverride(
        val oesTexId: Int,
        val stMatrix: FloatArray,
        val aspectWOverH: Float
    )

    private val layerTextures = HashMap<String, GpuLayerTexture>()
    private val oesVideoSlots = HashMap<String, OesLayerSurfaceSlot>()
    val externalOesOverrides = HashMap<String, ExternalOesOverride>()

    private val fullQuadCoords = floatArrayOf(
        -1f, -1f, 0f, 0f,
         1f, -1f, 1f, 0f,
        -1f,  1f, 0f, 1f,
         1f,  1f, 1f, 1f
    )
    private lateinit var quadBuffer: FloatBuffer

    private val cubeMesh: Mesh3D = Mesh3DLoader.createCubeMesh()
    private val boxMesh: Mesh3D = Mesh3DLoader.createBoxMesh()
    private val pyramidMesh: Mesh3D = Mesh3DLoader.createPyramidMesh()
    private val torusMesh: Mesh3D = Mesh3DLoader.createTorusMesh()

    private val cubeVertBuf: FloatBuffer = buildDirectFloatBuffer(cubeMesh.vertices)
    private val cubeIdxBuf: ShortBuffer = buildDirectShortBuffer(cubeMesh.indices)
    private val boxVertBuf: FloatBuffer = buildDirectFloatBuffer(boxMesh.vertices)
    private val boxIdxBuf: ShortBuffer = buildDirectShortBuffer(boxMesh.indices)
    private val pyrVertBuf: FloatBuffer = buildDirectFloatBuffer(pyramidMesh.vertices)
    private val pyrIdxBuf: ShortBuffer = buildDirectShortBuffer(pyramidMesh.indices)
    private val torusVertBuf: FloatBuffer = buildDirectFloatBuffer(torusMesh.vertices)
    private val torusIdxBuf: ShortBuffer = buildDirectShortBuffer(torusMesh.indices)

    private val paintFill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val paintStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val paintText = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        releaseOesSlotsInternal()
        layerTextures.clear()
        fboAllocW = 0
        fboAllocH = 0
        fboIds[0] = 0
        fboIds[1] = 0
        cachedPlaybackTexId = 0
        cachedPlaybackTexW = 0
        cachedPlaybackTexH = 0
        cachedPlaybackIdentity = 0

        quadBuffer = buildDirectFloatBuffer(fullQuadCoords)
        quadProgram = buildProgram(QUAD_VERTEX_SHADER, PASSTHROUGH_FRAGMENT_SHADER)
        checkerBgProgram = buildProgram(QUAD_VERTEX_SHADER, CHECKER_FRAGMENT_SHADER)
        layerQuadProgram = buildProgram(LAYER_QUAD_VERTEX_SHADER, LAYER_QUAD_FRAGMENT_SHADER)
        oesLayerQuadProgram = buildProgram(OES_LAYER_QUAD_VERTEX_SHADER, OES_LAYER_QUAD_FRAGMENT_SHADER)
        effectProgram = buildProgram(QUAD_VERTEX_SHADER, MULTIPASS_EFFECT_FRAGMENT_SHADER)
        mesh3DProgram = buildProgram(MESH_3D_VERTEX_SHADER, MESH_3D_FRAGMENT_SHADER)

        // Cache all shader attribute/uniform locations once per GL context creation
        quadPosLoc = GLES20.glGetAttribLocation(quadProgram, "aPosition")
        quadUvLoc = GLES20.glGetAttribLocation(quadProgram, "aTexCoord")
        quadFlipLoc = GLES20.glGetUniformLocation(quadProgram, "uFlipY")
        quadTexLoc = GLES20.glGetUniformLocation(quadProgram, "uTexture")

        chkPosLoc = GLES20.glGetAttribLocation(checkerBgProgram, "aPosition")
        chkUvLoc = GLES20.glGetAttribLocation(checkerBgProgram, "aTexCoord")
        chkFlipLoc = GLES20.glGetUniformLocation(checkerBgProgram, "uFlipY")
        chkResLoc = GLES20.glGetUniformLocation(checkerBgProgram, "uResolution")
        chkGridLoc = GLES20.glGetUniformLocation(checkerBgProgram, "uGridMode")

        lyrPosLoc = GLES20.glGetAttribLocation(layerQuadProgram, "aPosition")
        lyrUvLoc = GLES20.glGetAttribLocation(layerQuadProgram, "aTexCoord")
        lyrCenterLoc = GLES20.glGetUniformLocation(layerQuadProgram, "uCenterPx")
        lyrHalfSizeLoc = GLES20.glGetUniformLocation(layerQuadProgram, "uHalfSizePx")
        lyrPivotLoc = GLES20.glGetUniformLocation(layerQuadProgram, "uPivotPx")
        lyrRotLoc = GLES20.glGetUniformLocation(layerQuadProgram, "uRotRad")
        lyrViewportLoc = GLES20.glGetUniformLocation(layerQuadProgram, "uViewportPx")
        lyrOpacityLoc = GLES20.glGetUniformLocation(layerQuadProgram, "uOpacity")
        lyrTexLoc = GLES20.glGetUniformLocation(layerQuadProgram, "uTexture")

        oesPosLoc = GLES20.glGetAttribLocation(oesLayerQuadProgram, "aPosition")
        oesUvLoc = GLES20.glGetAttribLocation(oesLayerQuadProgram, "aTexCoord")
        oesCenterLoc = GLES20.glGetUniformLocation(oesLayerQuadProgram, "uCenterPx")
        oesHalfSizeLoc = GLES20.glGetUniformLocation(oesLayerQuadProgram, "uHalfSizePx")
        oesPivotLoc = GLES20.glGetUniformLocation(oesLayerQuadProgram, "uPivotPx")
        oesRotLoc = GLES20.glGetUniformLocation(oesLayerQuadProgram, "uRotRad")
        oesViewportLoc = GLES20.glGetUniformLocation(oesLayerQuadProgram, "uViewportPx")
        oesOpacityLoc = GLES20.glGetUniformLocation(oesLayerQuadProgram, "uOpacity")
        oesTexMatLoc = GLES20.glGetUniformLocation(oesLayerQuadProgram, "uTexMatrix")
        oesTexLoc = GLES20.glGetUniformLocation(oesLayerQuadProgram, "uOesTexture")

        m3dPosLoc = GLES20.glGetAttribLocation(mesh3DProgram, "aPosition")
        m3dNormLoc = GLES20.glGetAttribLocation(mesh3DProgram, "aNormal")
        m3dMvpLoc = GLES20.glGetUniformLocation(mesh3DProgram, "uMVP")
        m3dColorLoc = GLES20.glGetUniformLocation(mesh3DProgram, "uColor")
        m3dTimeLoc = GLES20.glGetUniformLocation(mesh3DProgram, "uTime")

        fxPosLoc = GLES20.glGetAttribLocation(effectProgram, "aPosition")
        fxUvLoc = GLES20.glGetAttribLocation(effectProgram, "aTexCoord")
        fxFlipLoc = GLES20.glGetUniformLocation(effectProgram, "uFlipY")
        fxModeLoc = GLES20.glGetUniformLocation(effectProgram, "uMode")
        fxIntensityLoc = GLES20.glGetUniformLocation(effectProgram, "uIntensity")
        fxSecondaryLoc = GLES20.glGetUniformLocation(effectProgram, "uSecondary")
        fxFreqLoc = GLES20.glGetUniformLocation(effectProgram, "uFreq")
        fxAngleLoc = GLES20.glGetUniformLocation(effectProgram, "uAngle")
        fxMixLoc = GLES20.glGetUniformLocation(effectProgram, "uMix")
        fxTintLoc = GLES20.glGetUniformLocation(effectProgram, "uTint")
        fxTimeLoc = GLES20.glGetUniformLocation(effectProgram, "uTime")
        fxResLoc = GLES20.glGetUniformLocation(effectProgram, "uResolution")
        fxQualityLoc = GLES20.glGetUniformLocation(effectProgram, "uQualityLevel")
        fxTexLoc = GLES20.glGetUniformLocation(effectProgram, "uTexture")
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        surfaceWidth = width.coerceAtLeast(1)
        surfaceHeight = height.coerceAtLeast(1)
        GLES20.glViewport(0, 0, surfaceWidth, surfaceHeight)
    }

    override fun onDrawFrame(gl: GL10?) {
        val frameStartNano = System.nanoTime()
        val project = currentProject
        val quality = previewQuality
        val durMs = project.durationMs.coerceAtLeast(100L)
        val rawClockMs = if (isPlaying && playbackAnchorNano > 0L) {
            val elapsedMs = ((frameStartNano - playbackAnchorNano) / 1_000_000L).coerceAtLeast(0L)
            val wallEst = if (isLooping) {
                (playbackAnchorMs + elapsedMs) % durMs
            } else {
                (playbackAnchorMs + elapsedMs).coerceAtMost(durMs)
            }
            // Lock to hardware ExoPlayer Audio/Video Master Clock when an active media track is playing!
            HardwareVideoPreviewEngine.queryMasterCompositionTimeMs(project, wallEst) ?: wallEst
        } else {
            playheadMs.coerceIn(0L, durMs)
        }
        val timeMs = rawClockMs.coerceIn(0L, durMs)

        val aspect = (project.width.toFloat().coerceAtLeast(64f) / project.height.toFloat().coerceAtLeast(64f))
            .coerceIn(0.2f, 5f)

        // Compute Screen Viewport Once (snap within 3% dp-rounding tolerance so zero edge bars appear!)
        val surfaceAspect = surfaceWidth.toFloat() / surfaceHeight.toFloat().coerceAtLeast(1f)
        val vpW: Int
        val vpH: Int
        if (abs(surfaceAspect - aspect) / aspect <= 0.035f) {
            vpW = surfaceWidth
            vpH = surfaceHeight
        } else if (surfaceAspect > aspect) {
            vpH = surfaceHeight
            vpW = (surfaceHeight * aspect).toInt()
        } else {
            vpW = surfaceWidth
            vpH = (surfaceWidth / aspect).toInt()
        }
        val vpX = (surfaceWidth - vpW) / 2
        val vpY = (surfaceHeight - vpH) / 2

        // Fast-path 1-Draw-Call Pre-Rendered RAM Cache Blit!
        if (usePlaybackCache) {
            val effectiveFps = min(project.fps.coerceIn(12, 60), previewFpsCap.coerceIn(24, 60))
            val cachedBmp = PlaybackCacheEngine.getCachedFrame(
                projectRevision = projectRevision,
                quality = quality,
                timeMs = timeMs,
                fps = effectiveFps
            )
            if (cachedBmp != null && !cachedBmp.isRecycled) {
                uploadCachedPlaybackBitmap(cachedBmp)
                GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
                GLES20.glViewport(0, 0, surfaceWidth, surfaceHeight)
                GLES20.glClearColor(0.055f, 0.063f, 0.082f, 1f)
                GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
                GLES20.glViewport(vpX, vpY, vpW, vpH)
                drawQuadWithTexture(cachedPlaybackTexId, flipY = true)
                recordFrameTelemetry(frameStartNano)
                return
            }
        }

        val audio = audioSnapshot

        val activeEffects = project.layers
            .asSequence()
            .filter { it.visible && timeMs in it.startTimeMs..it.endTimeMs }
            .flatMap { lyr ->
                lyr.effects.asSequence().filter { fx ->
                    fx.enabled && !(lyr.type == LayerType.TEXT && (fx.effectId == "Text_Transform" || fx.effectId == "Text_Spacing"))
                }
            }
            .toList()

        val baseDim = quality.maxGlDimension.coerceIn(160, 1080)
        val maxPreviewDim = if (activeEffects.size >= 4 && baseDim > 540) {
            (baseDim * 0.8f).toInt().coerceAtLeast(240)
        } else {
            baseDim
        }
        val internalW: Int
        val internalH: Int
        if (aspect >= 1f) {
            internalW = maxPreviewDim
            internalH = (maxPreviewDim / aspect).toInt().coerceAtLeast(64)
        } else {
            internalH = maxPreviewDim
            internalW = (maxPreviewDim * aspect).toInt().coerceAtLeast(64)
        }

        ensurePingPongFramebuffers(internalW, internalH)
        evictRemovedLayerTextures(project)

        // Pass 0: Clear composition background & render all active 2D/Media/Text/Shape layers on GPU
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fboIds[0])
        GLES20.glViewport(0, 0, internalW, internalH)
        clearCompositionBackground(project.backgroundColor, internalW.toFloat(), internalH.toFloat())

        render2DLayerStackOnGpu(project, timeMs, audio, quality, internalW.toFloat(), internalH.toFloat())
        render3DLayers(project, timeMs, audio, aspect)

        // Multi-pass Ping-Pong Shader Pipeline (skips zero-mix effects & redundant clears)
        var readIndex = 0
        activeEffects.forEach { fx ->
            val evalFx = AnimationEvaluator.evaluateEffectNode(fx, timeMs, audio)
            if (evalFx.mixFraction <= 0.002f) return@forEach

            val writeIndex = 1 - readIndex
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fboIds[writeIndex])
            GLES20.glViewport(0, 0, internalW, internalH)
            drawEffectPass(
                inputTex = fboTexIds[readIndex],
                effect = fx,
                evalFx = evalFx,
                timeMs = timeMs,
                resW = internalW.toFloat(),
                resH = internalH.toFloat(),
                shaderDetailLevel = quality.shaderDetailLevel
            )
            readIndex = writeIndex
        }

        // Final Output Pass to Screen Surface
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        GLES20.glViewport(0, 0, surfaceWidth, surfaceHeight)
        GLES20.glClearColor(0.055f, 0.063f, 0.082f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

        GLES20.glViewport(vpX, vpY, vpW, vpH)
        drawQuadWithTexture(fboTexIds[readIndex], flipY = false)
        recordFrameTelemetry(frameStartNano)
    }

    /**
     * Renders the complete GPU composition + shader stack at `(surfaceWidth, surfaceHeight)` inside
     * an offscreen EGL PBuffer context, flips Y on GPU, and reads upright RGBA pixels into [outRgbaBuffer].
     */
    fun renderOffscreenFrameToBuffer(
        project: Project,
        timeMs: Long,
        audio: AudioSpectrumSnapshot,
        quality: PreviewQuality,
        outRgbaBuffer: ByteBuffer
    ): Boolean {
        val w = surfaceWidth.coerceAtLeast(16)
        val h = surfaceHeight.coerceAtLeast(16)
        val aspect = w.toFloat() / h.toFloat()

        ensurePingPongFramebuffers(w, h)
        if (GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER) != GLES20.GL_FRAMEBUFFER_COMPLETE &&
            fboIds[0] == 0
        ) {
            return false
        }
        evictRemovedLayerTextures(project)

        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fboIds[0])
        GLES20.glViewport(0, 0, w, h)
        clearCompositionBackground(project.backgroundColor, w.toFloat(), h.toFloat())

        render2DLayerStackOnGpu(project, timeMs, audio, quality, w.toFloat(), h.toFloat())
        render3DLayers(project, timeMs, audio, aspect)

        var readIndex = 0
        project.layers.forEach { layer ->
            if (!layer.visible || timeMs !in layer.startTimeMs..layer.endTimeMs) return@forEach
            layer.effects.forEach { fx ->
                if (!fx.enabled) return@forEach
                if (layer.type == LayerType.TEXT && (fx.effectId == "Text_Transform" || fx.effectId == "Text_Spacing")) return@forEach
                val evalFx = AnimationEvaluator.evaluateEffectNode(fx, timeMs, audio)
                if (evalFx.mixFraction > 0.002f) {
                    val writeIndex = 1 - readIndex
                    GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fboIds[writeIndex])
                    GLES20.glViewport(0, 0, w, h)
                    drawEffectPass(
                        inputTex = fboTexIds[readIndex],
                        effect = fx,
                        evalFx = evalFx,
                        timeMs = timeMs,
                        resW = w.toFloat(),
                        resH = h.toFloat(),
                        shaderDetailLevel = quality.shaderDetailLevel
                    )
                    readIndex = writeIndex
                }
            }
        }

        // Blit into PBuffer (FBO 0) with flipY = true so glReadPixels row 0 = TOP of upright Android Bitmap!
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        GLES20.glViewport(0, 0, w, h)
        drawQuadWithTexture(fboTexIds[readIndex], flipY = true)

        outRgbaBuffer.position(0)
        GLES20.glReadPixels(0, 0, w, h, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, outRgbaBuffer)
        outRgbaBuffer.position(0)
        return true
    }

    fun releaseGlResources() {
        HardwareVideoPreviewEngine.detachRendererSurfaces(rendererToken)
        releaseOesSlotsInternal()
        externalOesOverrides.clear()
    }

    private fun releaseOesSlotsInternal() {
        val iter = oesVideoSlots.values.iterator()
        while (iter.hasNext()) {
            val slot = iter.next()
            runCatching { slot.surfaceTexture.setOnFrameAvailableListener(null) }
            runCatching { slot.surface.release() }
            runCatching { slot.surfaceTexture.release() }
            if (slot.oesTexId != 0) {
                runCatching { GLES20.glDeleteTextures(1, intArrayOf(slot.oesTexId), 0) }
            }
            iter.remove()
        }
    }

    private fun uploadCachedPlaybackBitmap(bmp: Bitmap) {
        if (cachedPlaybackTexId == 0) {
            val arr = IntArray(1)
            GLES20.glGenTextures(1, arr, 0)
            cachedPlaybackTexId = arr[0]
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, cachedPlaybackTexId)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        } else {
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, cachedPlaybackTexId)
        }
        val id = System.identityHashCode(bmp)
        if (id == cachedPlaybackIdentity && cachedPlaybackTexW == bmp.width && cachedPlaybackTexH == bmp.height) {
            return
        }
        if (cachedPlaybackTexW == bmp.width && cachedPlaybackTexH == bmp.height && cachedPlaybackTexW > 0) {
            GLUtils.texSubImage2D(GLES20.GL_TEXTURE_2D, 0, 0, 0, bmp)
        } else {
            GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bmp, 0)
            cachedPlaybackTexW = bmp.width
            cachedPlaybackTexH = bmp.height
        }
        cachedPlaybackIdentity = id
    }

    private fun recordFrameTelemetry(frameStartNano: Long) {
        val endNano = System.nanoTime()
        lastFrameRenderMs = ((endNano - frameStartNano) / 1_000_000f).coerceIn(0.05f, 99.9f)
        if (fpsWindowStartNano == 0L) {
            fpsWindowStartNano = endNano
            fpsWindowFrames = 1
            return
        }
        fpsWindowFrames++
        val elapsedNano = endNano - fpsWindowStartNano
        if (elapsedNano >= 500_000_000L) {
            liveFps = ((fpsWindowFrames * 1_000_000_000L) / elapsedNano).toInt().coerceIn(1, 120)
            fpsWindowStartNano = endNano
            fpsWindowFrames = 0
        }
    }

    private fun clearCompositionBackground(bgMode: String, w: Float, h: Float) {
        when {
            bgMode == "White" -> GLES20.glClearColor(1f, 1f, 1f, 1f)
            bgMode == "Dark Gray" -> GLES20.glClearColor(0.11f, 0.125f, 0.155f, 1f)
            bgMode == "Light Gray" -> GLES20.glClearColor(0.82f, 0.84f, 0.87f, 1f)
            bgMode.startsWith("#") -> {
                val parsed = runCatching { Color.parseColor(bgMode) }.getOrDefault(Color.BLACK)
                GLES20.glClearColor(
                    Color.red(parsed) / 255f,
                    Color.green(parsed) / 255f,
                    Color.blue(parsed) / 255f,
                    1f
                )
            }
            else -> GLES20.glClearColor(0f, 0f, 0f, 1f) // Pure Alight Motion Black
        }
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)

        if (bgMode == "Transparent" || show3DGridOverlay) {
            GLES20.glUseProgram(checkerBgProgram)
            quadBuffer.position(0)
            GLES20.glEnableVertexAttribArray(chkPosLoc)
            GLES20.glVertexAttribPointer(chkPosLoc, 2, GLES20.GL_FLOAT, false, 16, quadBuffer)
            quadBuffer.position(2)
            GLES20.glEnableVertexAttribArray(chkUvLoc)
            GLES20.glVertexAttribPointer(chkUvLoc, 2, GLES20.GL_FLOAT, false, 16, quadBuffer)
            GLES20.glUniform1f(chkFlipLoc, 0f)
            GLES20.glUniform2f(chkResLoc, w, h)
            GLES20.glUniform1i(chkGridLoc, if (bgMode == "Transparent") 0 else 1)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
            GLES20.glDisableVertexAttribArray(chkPosLoc)
            GLES20.glDisableVertexAttribArray(chkUvLoc)
        }
    }

    private fun render2DLayerStackOnGpu(
        project: Project,
        timeMs: Long,
        audio: AudioSpectrumSnapshot,
        quality: PreviewQuality,
        fboW: Float,
        fboH: Float
    ) {
        val projW = project.width.coerceAtLeast(1).toFloat()
        val scaleToFbo = fboW / projW

        GLES20.glEnable(GLES20.GL_BLEND)

        project.layers.forEachIndexed { idx, layer ->
            if (!layer.visible || timeMs !in layer.startTimeMs..layer.endTimeMs) return@forEachIndexed
            if (layer.type == LayerType.MODEL_3D ||
                layer.type == LayerType.NULL_OBJECT ||
                layer.type == LayerType.CAMERA ||
                layer.type == LayerType.AUDIO
            ) return@forEachIndexed

            val texEntry = prepareLayerGpuTexture(layer, timeMs, audio) ?: return@forEachIndexed
            val currTr = AnimationEvaluator.evaluateWorldTransform(
                layer = layer,
                allLayers = project.layers,
                timeMs = timeMs,
                audio = audio,
                applyActiveCamera = true
            )
            if (currTr.opacity <= 0.005f) return@forEachIndexed

            // Configure GL blend mode (including Clipping Mask destination-in style when flagged)
            val prevLayerIsMask = idx > 0 && project.layers[idx - 1].isClippingMask && project.layers[idx - 1].visible
            configureLayerBlendState(layer.blendMode, layer.isClippingMask, prevLayerIsMask)

            val (baseHalfW, baseHalfH) = computeLayerBaseHalfSizeFbo(layer, texEntry.aspectWOverH, fboW, fboH)

            if (layer.motionBlurEnabled && quality.shaderDetailLevel >= 1) {
                val shutterDtMs = ((layer.shutterAngle / 360f) * 28f).toLong().coerceIn(6L, 28L)
                val prevTr = AnimationEvaluator.evaluateWorldTransform(
                    layer = layer,
                    allLayers = project.layers,
                    timeMs = (timeMs - shutterDtMs).coerceAtLeast(0L),
                    audio = audio,
                    applyActiveCamera = true
                )
                val moved = abs(currTr.posX - prevTr.posX) > 0.6f ||
                    abs(currTr.posY - prevTr.posY) > 0.6f ||
                    abs(currTr.rotZ - prevTr.rotZ) > 0.4f ||
                    abs(currTr.scale - prevTr.scale) > 0.01f

                if (moved) {
                    val samples = if (quality.shaderDetailLevel >= 2) 4 else 2
                    for (s in 0 until samples) {
                        val f = s.toFloat() / (samples - 1).toFloat()
                        val interp = EvaluatedTransform(
                            posX = prevTr.posX + (currTr.posX - prevTr.posX) * f,
                            posY = prevTr.posY + (currTr.posY - prevTr.posY) * f,
                            posZ = currTr.posZ,
                            pivotX = currTr.pivotX,
                            pivotY = currTr.pivotY,
                            scale = prevTr.scale + (currTr.scale - prevTr.scale) * f,
                            rotX = currTr.rotX,
                            rotY = currTr.rotY,
                            rotZ = prevTr.rotZ + (currTr.rotZ - prevTr.rotZ) * f,
                            opacity = (currTr.opacity / samples.toFloat()) * 1.35f
                        )
                        drawTransformedLayerEntry(texEntry, layer, interp, baseHalfW, baseHalfH, scaleToFbo, fboW, fboH)
                    }
                    return@forEachIndexed
                }
            }

            drawTransformedLayerEntry(texEntry, layer, currTr, baseHalfW, baseHalfH, scaleToFbo, fboW, fboH)
        }

        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        GLES20.glDisable(GLES20.GL_BLEND)
    }

    private fun configureLayerBlendState(blendMode: String, isMaskSource: Boolean, maskedByBelow: Boolean) {
        if (maskedByBelow) {
            // Render only where underlying Clipping Mask alpha > 0!
            GLES20.glBlendFunc(GLES20.GL_DST_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
            return
        }
        if (isMaskSource) {
            GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
            return
        }
        when (blendMode) {
            "Screen", "Lighten" -> GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_COLOR)
            "Multiply" -> GLES20.glBlendFunc(GLES20.GL_DST_COLOR, GLES20.GL_ONE_MINUS_SRC_ALPHA)
            "Overlay" -> GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE)
            "Difference" -> {
                GLES20.glBlendFunc(GLES20.GL_ONE_MINUS_DST_COLOR, GLES20.GL_ONE_MINUS_SRC_ALPHA)
            }
            else -> GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        }
    }

    private fun computeLayerBaseHalfSizeFbo(
        layer: Layer,
        texAspect: Float,
        fboW: Float,
        fboH: Float
    ): Pair<Float, Float> {
        return when (layer.type) {
            LayerType.VIDEO, LayerType.IMAGE -> {
                // Edge-to-edge 100% composition fit (0.5005 half-size eliminates subpixel black bars on edges!)
                val compAspect = fboW / fboH.coerceAtLeast(1f)
                val safeAspect = texAspect.coerceIn(0.15f, 6.0f)
                if (safeAspect >= compAspect) {
                    val hw = fboW * 0.5005f
                    val hh = (hw / safeAspect).coerceAtLeast(8f)
                    hw to hh
                } else {
                    val hh = fboH * 0.5005f
                    val hw = (hh * safeAspect).coerceAtLeast(8f)
                    hw to hh
                }
            }
            LayerType.TEXT -> {
                val hh = minOf(fboW, fboH) * 0.14f
                val hw = hh * texAspect.coerceIn(1f, 6f)
                hw to hh
            }
            else -> {
                val half = minOf(fboW, fboH) * 0.22f
                half to half
            }
        }
    }

    private fun drawTransformedLayerEntry(
        entry: GpuLayerTexture,
        layer: Layer,
        tr: EvaluatedTransform,
        baseHalfW: Float,
        baseHalfH: Float,
        scaleToFbo: Float,
        fboW: Float,
        fboH: Float
    ) {
        val cxPx = fboW * 0.5f + tr.posX * scaleToFbo
        val cyPx = fboH * 0.5f - tr.posY * scaleToFbo
        val pivXPx = tr.pivotX * scaleToFbo
        val pivYPx = -tr.pivotY * scaleToFbo

        val depthZoom = (1f + tr.posZ / 900f).coerceIn(0.15f, 5f)
        val cosTiltX = cos(Math.toRadians(tr.rotX.toDouble())).toFloat().coerceIn(0.2f, 1f)
        val cosTiltY = cos(Math.toRadians(tr.rotY.toDouble())).toFloat().coerceIn(0.2f, 1f)
        val flipSignX = if (layer.flipX) -1f else 1f
        val flipSignY = if (layer.flipY) -1f else 1f

        val effHalfW = baseHalfW * tr.scale * depthZoom * cosTiltY * flipSignX
        val effHalfH = baseHalfH * tr.scale * depthZoom * cosTiltX * flipSignY
        val rotRad = Math.toRadians((-tr.rotZ).toDouble()).toFloat()

        if (entry.isExternalOes && entry.oesMatrix != null) {
            GLES20.glUseProgram(oesLayerQuadProgram)

            quadBuffer.position(0)
            GLES20.glEnableVertexAttribArray(oesPosLoc)
            GLES20.glVertexAttribPointer(oesPosLoc, 2, GLES20.GL_FLOAT, false, 16, quadBuffer)

            quadBuffer.position(2)
            GLES20.glEnableVertexAttribArray(oesUvLoc)
            GLES20.glVertexAttribPointer(oesUvLoc, 2, GLES20.GL_FLOAT, false, 16, quadBuffer)

            GLES20.glUniform2f(oesCenterLoc, cxPx, cyPx)
            GLES20.glUniform2f(oesHalfSizeLoc, effHalfW, effHalfH)
            GLES20.glUniform2f(oesPivotLoc, pivXPx, pivYPx)
            GLES20.glUniform1f(oesRotLoc, rotRad)
            GLES20.glUniform2f(oesViewportLoc, fboW, fboH)
            GLES20.glUniform1f(oesOpacityLoc, tr.opacity.coerceIn(0f, 1f))
            GLES20.glUniformMatrix4fv(oesTexMatLoc, 1, false, entry.oesMatrix, 0)

            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, entry.texId)
            GLES20.glUniform1i(oesTexLoc, 0)

            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
            GLES20.glDisableVertexAttribArray(oesPosLoc)
            GLES20.glDisableVertexAttribArray(oesUvLoc)
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, 0)
            return
        }

        GLES20.glUseProgram(layerQuadProgram)

        quadBuffer.position(0)
        GLES20.glEnableVertexAttribArray(lyrPosLoc)
        GLES20.glVertexAttribPointer(lyrPosLoc, 2, GLES20.GL_FLOAT, false, 16, quadBuffer)

        quadBuffer.position(2)
        GLES20.glEnableVertexAttribArray(lyrUvLoc)
        GLES20.glVertexAttribPointer(lyrUvLoc, 2, GLES20.GL_FLOAT, false, 16, quadBuffer)

        GLES20.glUniform2f(lyrCenterLoc, cxPx, cyPx)
        GLES20.glUniform2f(lyrHalfSizeLoc, effHalfW, effHalfH)
        GLES20.glUniform2f(lyrPivotLoc, pivXPx, pivYPx)
        GLES20.glUniform1f(lyrRotLoc, rotRad)
        GLES20.glUniform2f(lyrViewportLoc, fboW, fboH)
        GLES20.glUniform1f(lyrOpacityLoc, tr.opacity.coerceIn(0f, 1f))

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, entry.texId)
        GLES20.glUniform1i(lyrTexLoc, 0)

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(lyrPosLoc)
        GLES20.glDisableVertexAttribArray(lyrUvLoc)
    }

    private fun prepareLayerGpuTexture(
        layer: Layer,
        timeMs: Long,
        audio: AudioSpectrumSnapshot
    ): GpuLayerTexture? {
        // 1. Offscreen Sequential Hardware Decoder OES Override (used by OffscreenGlPreRenderEngine!)
        if (layer.type == LayerType.VIDEO) {
            val extOes = externalOesOverrides[layer.id]
            if (extOes != null && extOes.oesTexId != 0) {
                return GpuLayerTexture(
                    texId = extOes.oesTexId,
                    isExternalOes = true,
                    oesMatrix = extOes.stMatrix,
                    aspectWOverH = extOes.aspectWOverH
                )
            }
        }

        // 2. Live Zero-Copy Hardware ExoPlayer OES Stream (for standard forward/speed-scaled video preview!)
        if (!offscreenMode && HardwareVideoPreviewEngine.isEligibleForLiveOesStream(layer)) {
            val oesSlot = obtainOrCreateOesVideoSlot(layer)
            if (oesSlot != null) {
                val pending = oesSlot.pendingFrames.getAndSet(0)
                if (pending > 0 || (isPlaying && oesSlot.hasLatchedFrame)) {
                    runCatching {
                        oesSlot.surfaceTexture.updateTexImage()
                        oesSlot.surfaceTexture.getTransformMatrix(oesSlot.stMatrix)
                        oesSlot.hasLatchedFrame = true
                    }
                }
                if (oesSlot.hasLatchedFrame && HardwareVideoPreviewEngine.hasReadyVideoFrame(layer.id)) {
                    val fallbackAspect = (layer.mediaWidth.takeIf { it > 0 }?.toFloat() ?: 16f) /
                        (layer.mediaHeight.takeIf { it > 0 }?.toFloat() ?: 9f)
                    val aspect = HardwareVideoPreviewEngine.getSlotAspect(layer.id) ?: fallbackAspect
                    return GpuLayerTexture(
                        texId = oesSlot.oesTexId,
                        isExternalOes = true,
                        oesMatrix = oesSlot.stMatrix,
                        aspectWOverH = aspect.coerceIn(0.15f, 6.0f)
                    )
                }
            }
        }

        val entry = layerTextures.getOrPut(layer.id) {
            val arr = IntArray(1)
            GLES20.glGenTextures(1, arr, 0)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, arr[0])
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
            GpuLayerTexture(texId = arr[0])
        }
        entry.isExternalOes = false
        entry.oesMatrix = null

        when (layer.type) {
            LayerType.IMAGE, LayerType.VIDEO -> {
                val srcTimeMs = if (layer.type == LayerType.VIDEO) {
                    AnimationEvaluator.evaluateSourceMediaTimeMs(layer, timeMs, audio)
                } else 0L

                val mediaBmp = if (offscreenMode) {
                    MediaEngine.getMediaBitmapForPreRender(
                        context = appContext,
                        layer = layer,
                        sourceTimeMs = srcTimeMs,
                        maxEdgePx = maxOf(surfaceWidth, surfaceHeight).coerceIn(192, 960)
                    )
                } else {
                    MediaEngine.getMediaBitmapNonBlocking(
                        context = appContext,
                        layer = layer,
                        sourceTimeMs = srcTimeMs,
                        allowRetrieverFallback = !HardwareVideoPreviewEngine.isEligibleForLiveOesStream(layer),
                        onAsyncFrameReady = onAsyncMediaReady
                    )
                }

                if (mediaBmp != null && !mediaBmp.isRecycled) {
                    val bmpId = System.identityHashCode(mediaBmp)
                    if (bmpId != entry.bitmapIdentity) {
                        uploadBitmapToEntry(entry, mediaBmp, "media")
                        entry.bitmapIdentity = bmpId
                    }
                    return entry
                }

                // Fallback sleek media card ONLY if mediaUri is blank (e.g., template card) or decoding first frame
                val fallbackSig = "fallback|${layer.type}|${layer.colorHex}|${layer.strokeEnabled}|${layer.strokeWidth}"
                if (entry.signature != fallbackSig) {
                    val bmp = rasterizeFallbackMediaCard(layer)
                    uploadBitmapToEntry(entry, bmp, fallbackSig)
                }
                return entry
            }

            LayerType.SHAPE -> {
                val sig = "shape|${layer.shapeKind}|${layer.colorHex}|${layer.fillMode}|" +
                    "${layer.gradientEndColorHex}|${layer.gradientAngleDeg.toInt()}|" +
                    "${layer.strokeEnabled}|${layer.strokeWidth}|${layer.strokeColorHex}|" +
                    "${layer.shadowEnabled}|${layer.shadowBlur}|${layer.shadowColorHex}"
                if (entry.signature != sig) {
                    val bmp = rasterizeShapeBitmap(layer)
                    uploadBitmapToEntry(entry, bmp, sig)
                }
                return entry
            }

            LayerType.TEXT -> {
                val textFx = layer.effects.firstOrNull {
                    it.enabled && (it.effectId == "Text_Transform" || it.effectId == "Text_Spacing")
                }
                val evalTx = textFx?.let { AnimationEvaluator.evaluateEffectNode(it, timeMs, audio) }
                val txSig = if (textFx != null && evalTx != null) {
                    "|tx:${textFx.effectId}:${(evalTx.primary * 2f).toInt()}:${(evalTx.secondary * 2f).toInt()}:" +
                        "${(evalTx.frequency * 2f).toInt()}:${evalTx.angleDeg.toInt()}:${(evalTx.mixFraction * 50f).toInt()}"
                } else ""
                val sig = "text|${layer.textContent}|${layer.fontFamilyName}|${layer.colorHex}|" +
                    "${layer.fillMode}|${layer.gradientEndColorHex}|${layer.gradientAngleDeg.toInt()}|" +
                    "${layer.strokeEnabled}|${layer.strokeWidth}|${layer.strokeColorHex}|" +
                    "${layer.shadowEnabled}|${layer.shadowBlur}|${layer.shadowColorHex}$txSig"
                if (entry.signature != sig) {
                    val bmp = rasterizeTextBitmap(layer, textFx, evalTx)
                    uploadBitmapToEntry(entry, bmp, sig)
                }
                return entry
            }

            else -> return null
        }
    }

    private fun obtainOrCreateOesVideoSlot(layer: Layer): OesLayerSurfaceSlot? {
        val existing = oesVideoSlots[layer.id]
        if (existing != null && existing.boundUri == layer.mediaUri) {
            return existing
        }
        if (existing != null) {
            runCatching { existing.surface.release() }
            runCatching { existing.surfaceTexture.release() }
            if (existing.oesTexId != 0) {
                GLES20.glDeleteTextures(1, intArrayOf(existing.oesTexId), 0)
            }
            oesVideoSlots.remove(layer.id)
        }

        return try {
            val arr = IntArray(1)
            GLES20.glGenTextures(1, arr, 0)
            val texId = arr[0]
            if (texId == 0) return null

            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texId)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, 0)

            val st = SurfaceTexture(texId)
            val surf = Surface(st)
            val slot = OesLayerSurfaceSlot(
                oesTexId = texId,
                surfaceTexture = st,
                surface = surf,
                boundUri = layer.mediaUri
            )
            st.setOnFrameAvailableListener({
                slot.pendingFrames.incrementAndGet()
                onAsyncMediaReady()
            }, mainHandler)

            oesVideoSlots[layer.id] = slot
            HardwareVideoPreviewEngine.bindLayerSurface(
                context = appContext,
                rendererToken = rendererToken,
                layerId = layer.id,
                mediaUri = layer.mediaUri,
                surface = surf
            )
            slot
        } catch (_: Throwable) {
            null
        }
    }

    private fun uploadBitmapToEntry(entry: GpuLayerTexture, bmp: Bitmap, sig: String) {
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, entry.texId)
        if (entry.texW == bmp.width && entry.texH == bmp.height && entry.texW > 0) {
            GLUtils.texSubImage2D(GLES20.GL_TEXTURE_2D, 0, 0, 0, bmp)
        } else {
            GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bmp, 0)
            entry.texW = bmp.width
            entry.texH = bmp.height
        }
        entry.aspectWOverH = bmp.width.toFloat().coerceAtLeast(1f) / bmp.height.toFloat().coerceAtLeast(1f)
        entry.signature = sig
    }

    private fun rasterizeShapeBitmap(layer: Layer): Bitmap {
        val sz = 384
        val bmp = Bitmap.createBitmap(sz, sz, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.translate(sz * 0.5f, sz * 0.5f)
        val half = sz * 0.36f

        val fillCol = runCatching { Color.parseColor(layer.colorHex) }.getOrDefault(Color.rgb(0, 210, 168))
        val endCol = runCatching { Color.parseColor(layer.gradientEndColorHex) }.getOrDefault(Color.rgb(0, 148, 255))
        val strokeCol = runCatching { Color.parseColor(layer.strokeColorHex) }.getOrDefault(Color.WHITE)
        paintFill.color = fillCol
        paintFill.shader = buildFillShader(layer.fillMode, fillCol, endCol, layer.gradientAngleDeg, half)
        if (layer.shadowEnabled) {
            val shad = runCatching { Color.parseColor(layer.shadowColorHex) }.getOrDefault(Color.BLACK)
            paintFill.setShadowLayer(layer.shadowBlur.coerceIn(2f, 42f), 0f, 5f, shad)
        } else {
            paintFill.clearShadowLayer()
        }
        paintStroke.color = strokeCol
        paintStroke.strokeWidth = (layer.strokeWidth * 2f).coerceIn(2f, 44f)

        when (layer.shapeKind) {
            "Circle" -> {
                c.drawCircle(0f, 0f, half, paintFill)
                if (layer.strokeEnabled) c.drawCircle(0f, 0f, half, paintStroke)
            }
            "Rectangle" -> {
                val r = RectF(-half, -half * 0.75f, half, half * 0.75f)
                c.drawRect(r, paintFill)
                if (layer.strokeEnabled) c.drawRect(r, paintStroke)
            }
            "Star" -> {
                val p = buildPolygonOrStarPath(5, half, half * 0.44f)
                c.drawPath(p, paintFill)
                if (layer.strokeEnabled) c.drawPath(p, paintStroke)
            }
            "Triangle" -> {
                val p = buildPolygonOrStarPath(3, half, null)
                c.drawPath(p, paintFill)
                if (layer.strokeEnabled) c.drawPath(p, paintStroke)
            }
            "Hexagon" -> {
                val p = buildPolygonOrStarPath(6, half, null)
                c.drawPath(p, paintFill)
                if (layer.strokeEnabled) c.drawPath(p, paintStroke)
            }
            else -> {
                val r = RectF(-half, -half * 0.75f, half, half * 0.75f)
                c.drawRoundRect(r, 34f, 34f, paintFill)
                if (layer.strokeEnabled) c.drawRoundRect(r, 34f, 34f, paintStroke)
            }
        }
        paintFill.shader = null
        paintFill.clearShadowLayer()
        return bmp
    }

    private fun buildFillShader(
        fillMode: String,
        startCol: Int,
        endCol: Int,
        angleDeg: Float,
        radius: Float
    ): Shader? {
        return when (fillMode) {
            "LINEAR_GRADIENT" -> {
                val rad = Math.toRadians(angleDeg.toDouble())
                val dx = (cos(rad) * radius).toFloat()
                val dy = (sin(rad) * radius).toFloat()
                LinearGradient(-dx, -dy, dx, dy, startCol, endCol, Shader.TileMode.CLAMP)
            }
            "RADIAL_GRADIENT" -> {
                RadialGradient(0f, 0f, radius.coerceAtLeast(8f), startCol, endCol, Shader.TileMode.CLAMP)
            }
            else -> null
        }
    }

    private fun rasterizeTextBitmap(
        layer: Layer,
        textFx: EffectNode? = null,
        evalTx: EvaluatedEffectParams? = null
    ): Bitmap {
        val txt = layer.textContent.ifBlank { "Text" }
        paintText.textSize = 76f
        paintText.typeface = CustomFontRegistry.resolveTypeface(appContext, layer.fontFamilyName)

        val extraTrackingPx = if (textFx?.effectId == "Text_Spacing" && evalTx != null) {
            evalTx.primary.coerceIn(-15f, 80f) * 1.4f
        } else 0f

        val rawMeasuredW = paintText.measureText(txt) + abs(extraTrackingPx) * txt.length
        val measuredW = rawMeasuredW.toInt().coerceIn(160, 1400)
        val bmpW = (measuredW + 128).coerceAtMost(1536)
        val bmpH = 230
        val bmp = Bitmap.createBitmap(bmpW, bmpH, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)

        val fillCol = runCatching { Color.parseColor(layer.colorHex) }.getOrDefault(Color.WHITE)
        val endCol = runCatching { Color.parseColor(layer.gradientEndColorHex) }.getOrDefault(Color.rgb(0, 210, 168))
        paintText.style = Paint.Style.FILL
        paintText.color = fillCol
        paintText.shader = when (layer.fillMode) {
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

        if (layer.shadowEnabled) {
            val shad = runCatching { Color.parseColor(layer.shadowColorHex) }.getOrDefault(Color.BLACK)
            paintText.setShadowLayer(layer.shadowBlur.coerceIn(2f, 36f), 0f, 4f, shad)
        } else {
            paintText.clearShadowLayer()
        }
        val baseline = bmpH * 0.62f

        if (textFx != null && evalTx != null && txt.isNotEmpty()) {
            // Alight Motion Per-Character Text Transform / Text Spacing Range Animator!
            paintText.textAlign = Paint.Align.LEFT
            val charWidths = FloatArray(txt.length) { idx ->
                paintText.measureText(txt, idx, idx + 1) + extraTrackingPx
            }
            val totalW = charWidths.sum().coerceAtLeast(1f)
            var cursorX = (bmpW - totalW) * 0.5f

            val startFrac = (evalTx.primary / 100f).coerceIn(0f, 1f)
            val endFrac = (evalTx.secondary / 100f).coerceIn(0f, 1f)
            val phaseFrac = evalTx.frequency / 100f
            val angleRad = Math.toRadians(evalTx.angleDeg.toDouble())
            val mixAmt = evalTx.mixFraction.coerceIn(0f, 1f)

            for (i in txt.indices) {
                val glyphPos = if (txt.length <= 1) 0.5f else i.toFloat() / (txt.length - 1).toFloat()
                val shifted = ((glyphPos - phaseFrac) % 1f + 1f) % 1f
                val lo = min(startFrac, endFrac)
                val hi = kotlin.math.max(startFrac, endFrac)
                val span = (hi - lo).coerceAtLeast(0.05f)
                val inRangeWeight = if (textFx.effectId == "Text_Transform") {
                    val dist = when {
                        shifted < lo -> (lo - shifted) / 0.18f
                        shifted > hi -> (shifted - hi) / 0.18f
                        else -> 0f
                    }
                    (1f - dist.coerceIn(0f, 1f)) * mixAmt
                } else 0f

                val offsetDist = inRangeWeight * 46f
                val dx = (cos(angleRad) * offsetDist).toFloat()
                val dy = -(sin(angleRad) * offsetDist).toFloat() - inRangeWeight * 22f
                val glyphScale = 1f + inRangeWeight * 0.28f
                val glyphAlpha = ((1f - inRangeWeight * 0.55f) * 255f).toInt().coerceIn(25, 255)

                c.save()
                val centerGlyphX = cursorX + charWidths[i] * 0.5f
                c.translate(centerGlyphX + dx, baseline + dy)
                c.scale(glyphScale, glyphScale)
                paintText.style = Paint.Style.FILL
                paintText.alpha = glyphAlpha
                c.drawText(txt, i, i + 1, -charWidths[i] * 0.5f, 0f, paintText)
                if (layer.strokeEnabled && layer.strokeWidth > 0.5f) {
                    val savedShader = paintText.shader
                    paintText.shader = null
                    paintText.style = Paint.Style.STROKE
                    paintText.strokeWidth = layer.strokeWidth.coerceIn(1f, 20f)
                    paintText.color = runCatching { Color.parseColor(layer.strokeColorHex) }.getOrDefault(Color.WHITE)
                    paintText.alpha = glyphAlpha
                    c.drawText(txt, i, i + 1, -charWidths[i] * 0.5f, 0f, paintText)
                    paintText.shader = savedShader
                    paintText.color = fillCol
                }
                c.restore()
                cursorX += charWidths[i]
            }
            paintText.alpha = 255
            paintText.textAlign = Paint.Align.CENTER
        } else {
            paintText.textAlign = Paint.Align.CENTER
            c.drawText(txt, bmpW * 0.5f, baseline, paintText)

            if (layer.strokeEnabled && layer.strokeWidth > 0.5f) {
                val savedShader = paintText.shader
                paintText.shader = null
                paintText.clearShadowLayer()
                paintText.style = Paint.Style.STROKE
                paintText.strokeWidth = layer.strokeWidth.coerceIn(1f, 20f)
                paintText.color = runCatching { Color.parseColor(layer.strokeColorHex) }.getOrDefault(Color.WHITE)
                c.drawText(txt, bmpW * 0.5f, baseline, paintText)
                paintText.shader = savedShader
                paintText.style = Paint.Style.FILL
            }
        }
        paintText.shader = null
        paintText.clearShadowLayer()
        return bmp
    }

    private fun rasterizeFallbackMediaCard(layer: Layer): Bitmap {
        val bmp = Bitmap.createBitmap(384, 240, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val base = runCatching { Color.parseColor(layer.colorHex) }.getOrDefault(Color.rgb(0, 148, 255))
        paintFill.color = base
        c.drawRoundRect(RectF(8f, 8f, 376f, 232f), 20f, 20f, paintFill)
        if (layer.strokeEnabled) {
            paintStroke.color = Color.WHITE
            paintStroke.strokeWidth = 6f
            c.drawRoundRect(RectF(8f, 8f, 376f, 232f), 20f, 20f, paintStroke)
        }
        return bmp
    }

    private fun evictRemovedLayerTextures(project: Project) {
        val activeIds = if (layerTextures.size > project.layers.size + 4 || oesVideoSlots.size > project.layers.size) {
            project.layers.map { it.id }.toHashSet()
        } else null
        if (activeIds != null) {
            val iter = layerTextures.entries.iterator()
            while (iter.hasNext()) {
                val entry = iter.next()
                if (entry.key !in activeIds) {
                    GLES20.glDeleteTextures(1, intArrayOf(entry.value.texId), 0)
                    iter.remove()
                }
            }
            val oesIter = oesVideoSlots.entries.iterator()
            while (oesIter.hasNext()) {
                val entry = oesIter.next()
                if (entry.key !in activeIds) {
                    runCatching { entry.value.surfaceTexture.setOnFrameAvailableListener(null) }
                    runCatching { entry.value.surface.release() }
                    runCatching { entry.value.surfaceTexture.release() }
                    if (entry.value.oesTexId != 0) {
                        GLES20.glDeleteTextures(1, intArrayOf(entry.value.oesTexId), 0)
                    }
                    oesIter.remove()
                }
            }
        }
    }

    private fun render3DLayers(
        project: Project,
        timeMs: Long,
        audio: AudioSpectrumSnapshot,
        aspect: Float
    ) {
        val layers3D = project.layers.filter {
            it.visible && it.type == LayerType.MODEL_3D && timeMs in it.startTimeMs..it.endTimeMs
        }
        if (layers3D.isEmpty()) return

        GLES20.glEnable(GLES20.GL_DEPTH_TEST)
        GLES20.glUseProgram(mesh3DProgram)

        layers3D.forEach { layer ->
            val tr = AnimationEvaluator.evaluateWorldTransform(
                layer = layer,
                allLayers = project.layers,
                timeMs = timeMs,
                audio = audio,
                applyActiveCamera = true
            )
            val (mesh, vBuf, iBuf) = when (layer.primitive3D) {
                Primitive3DType.CUBE -> Triple(cubeMesh, cubeVertBuf, cubeIdxBuf)
                Primitive3DType.BOX -> Triple(boxMesh, boxVertBuf, boxIdxBuf)
                Primitive3DType.PYRAMID -> Triple(pyramidMesh, pyrVertBuf, pyrIdxBuf)
                Primitive3DType.TORUS, Primitive3DType.IMPORTED_MESH -> Triple(torusMesh, torusVertBuf, torusIdxBuf)
            }

            val mvp = Mesh3DLoader.computeMvpMatrix(
                aspectRatio = aspect,
                camYawDeg = cameraYawDeg,
                camPitchDeg = cameraPitchDeg,
                camDistance = cameraDistance,
                layerPosX = tr.posX,
                layerPosY = tr.posY,
                layerPosZ = tr.posZ,
                layerRotX = tr.rotX,
                layerRotY = tr.rotY,
                layerRotZ = tr.rotZ,
                layerScale = tr.scale
            )

            val stride = 8 * 4
            vBuf.position(0)
            GLES20.glEnableVertexAttribArray(m3dPosLoc)
            GLES20.glVertexAttribPointer(m3dPosLoc, 3, GLES20.GL_FLOAT, false, stride, vBuf)

            vBuf.position(3)
            GLES20.glEnableVertexAttribArray(m3dNormLoc)
            GLES20.glVertexAttribPointer(m3dNormLoc, 3, GLES20.GL_FLOAT, false, stride, vBuf)

            val cInt = runCatching { Color.parseColor(layer.colorHex) }.getOrDefault(Color.rgb(0, 210, 168))
            GLES20.glUniformMatrix4fv(m3dMvpLoc, 1, false, mvp, 0)
            GLES20.glUniform4f(
                m3dColorLoc,
                Color.red(cInt) / 255f,
                Color.green(cInt) / 255f,
                Color.blue(cInt) / 255f,
                tr.opacity
            )
            GLES20.glUniform1f(m3dTimeLoc, timeMs / 1000f)

            iBuf.position(0)
            GLES20.glDrawElements(GLES20.GL_TRIANGLES, mesh.indices.size, GLES20.GL_UNSIGNED_SHORT, iBuf)
            GLES20.glDisableVertexAttribArray(m3dPosLoc)
            GLES20.glDisableVertexAttribArray(m3dNormLoc)
        }

        GLES20.glDisable(GLES20.GL_DEPTH_TEST)
    }

    private fun drawQuadWithTexture(texId: Int, flipY: Boolean) {
        GLES20.glUseProgram(quadProgram)

        quadBuffer.position(0)
        GLES20.glEnableVertexAttribArray(quadPosLoc)
        GLES20.glVertexAttribPointer(quadPosLoc, 2, GLES20.GL_FLOAT, false, 16, quadBuffer)

        quadBuffer.position(2)
        GLES20.glEnableVertexAttribArray(quadUvLoc)
        GLES20.glVertexAttribPointer(quadUvLoc, 2, GLES20.GL_FLOAT, false, 16, quadBuffer)

        GLES20.glUniform1f(quadFlipLoc, if (flipY) 1f else 0f)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texId)
        GLES20.glUniform1i(quadTexLoc, 0)

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(quadPosLoc)
        GLES20.glDisableVertexAttribArray(quadUvLoc)
    }

    private fun drawEffectPass(
        inputTex: Int,
        effect: EffectNode,
        evalFx: EvaluatedEffectParams,
        timeMs: Long,
        resW: Float,
        resH: Float,
        shaderDetailLevel: Int
    ) {
        val def = EffectsCatalog.findById(effect.effectId)
        val normFx = EffectsCatalog.normalizeForShader(effect.effectId, evalFx)
        val tintCol = runCatching { Color.parseColor(effect.tintColorHex) }.getOrDefault(Color.WHITE)

        GLES20.glUseProgram(effectProgram)

        quadBuffer.position(0)
        GLES20.glEnableVertexAttribArray(fxPosLoc)
        GLES20.glVertexAttribPointer(fxPosLoc, 2, GLES20.GL_FLOAT, false, 16, quadBuffer)

        quadBuffer.position(2)
        GLES20.glEnableVertexAttribArray(fxUvLoc)
        GLES20.glVertexAttribPointer(fxUvLoc, 2, GLES20.GL_FLOAT, false, 16, quadBuffer)

        GLES20.glUniform1f(fxFlipLoc, 0f)
        GLES20.glUniform1i(fxModeLoc, def.shaderMode)
        GLES20.glUniform1f(fxIntensityLoc, normFx.primary)
        GLES20.glUniform1f(fxSecondaryLoc, normFx.secondary)
        GLES20.glUniform1f(fxFreqLoc, normFx.frequency)
        GLES20.glUniform1f(fxAngleLoc, Math.toRadians(normFx.angleDeg.toDouble()).toFloat())
        GLES20.glUniform1f(fxMixLoc, normFx.mixFraction)
        GLES20.glUniform3f(
            fxTintLoc,
            Color.red(tintCol) / 255f,
            Color.green(tintCol) / 255f,
            Color.blue(tintCol) / 255f
        )
        GLES20.glUniform1f(fxTimeLoc, timeMs / 1000f)
        GLES20.glUniform2f(fxResLoc, resW, resH)
        GLES20.glUniform1i(fxQualityLoc, shaderDetailLevel)

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, inputTex)
        GLES20.glUniform1i(fxTexLoc, 0)

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(fxPosLoc)
        GLES20.glDisableVertexAttribArray(fxUvLoc)
    }

    private fun ensurePingPongFramebuffers(w: Int, h: Int) {
        if (fboAllocW == w && fboAllocH == h && fboIds[0] != 0) return
        if (fboIds[0] != 0) {
            GLES20.glDeleteFramebuffers(2, fboIds, 0)
            GLES20.glDeleteTextures(2, fboTexIds, 0)
        }
        GLES20.glGenFramebuffers(2, fboIds, 0)
        GLES20.glGenTextures(2, fboTexIds, 0)
        val depthBuf = IntArray(2)
        GLES20.glGenRenderbuffers(2, depthBuf, 0)

        for (i in 0..1) {
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, fboTexIds[i])
            GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, w, h, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)

            GLES20.glBindRenderbuffer(GLES20.GL_RENDERBUFFER, depthBuf[i])
            GLES20.glRenderbufferStorage(GLES20.GL_RENDERBUFFER, GLES20.GL_DEPTH_COMPONENT16, w, h)

            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fboIds[i])
            GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, fboTexIds[i], 0)
            GLES20.glFramebufferRenderbuffer(GLES20.GL_FRAMEBUFFER, GLES20.GL_DEPTH_ATTACHMENT, GLES20.GL_RENDERBUFFER, depthBuf[i])
        }
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        fboAllocW = w
        fboAllocH = h
    }

    private fun buildDirectFloatBuffer(arr: FloatArray): FloatBuffer =
        ByteBuffer.allocateDirect(arr.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
            put(arr)
            position(0)
        }

    private fun buildDirectShortBuffer(arr: ShortArray): ShortBuffer =
        ByteBuffer.allocateDirect(arr.size * 2).order(ByteOrder.nativeOrder()).asShortBuffer().apply {
            put(arr)
            position(0)
        }

    private fun buildProgram(vsSrc: String, fsSrc: String): Int {
        val vs = compileShader(GLES20.GL_VERTEX_SHADER, vsSrc)
        val fs = compileShader(GLES20.GL_FRAGMENT_SHADER, fsSrc)
        val prog = GLES20.glCreateProgram()
        GLES20.glAttachShader(prog, vs)
        GLES20.glAttachShader(prog, fs)
        GLES20.glLinkProgram(prog)
        return prog
    }

    private fun compileShader(type: Int, src: String): Int {
        val s = GLES20.glCreateShader(type)
        GLES20.glShaderSource(s, src)
        GLES20.glCompileShader(s)
        return s
    }

    companion object {
        fun buildPolygonOrStarPath(points: Int, outerR: Float, innerR: Float?): Path {
            val path = Path()
            val totalVertices = if (innerR != null) points * 2 else points
            val stepRad = (Math.PI * 2.0) / totalVertices
            val startRad = -Math.PI / 2.0
            for (i in 0 until totalVertices) {
                val r = if (innerR != null && i % 2 == 1) innerR else outerR
                val angle = startRad + i * stepRad
                val x = (cos(angle) * r).toFloat()
                val y = (sin(angle) * r).toFloat()
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            path.close()
            return path
        }

        const val QUAD_VERTEX_SHADER = """
            attribute vec2 aPosition;
            attribute vec2 aTexCoord;
            uniform float uFlipY;
            varying vec2 vUv;
            void main() {
                gl_Position = vec4(aPosition, 0.0, 1.0);
                vUv = vec2(aTexCoord.x, mix(aTexCoord.y, 1.0 - aTexCoord.y, uFlipY));
            }
        """

        const val PASSTHROUGH_FRAGMENT_SHADER = """
            precision mediump float;
            varying vec2 vUv;
            uniform sampler2D uTexture;
            void main() {
                gl_FragColor = texture2D(uTexture, vUv);
            }
        """

        const val CHECKER_FRAGMENT_SHADER = """
            precision mediump float;
            varying vec2 vUv;
            uniform vec2 uResolution;
            uniform int uGridMode;
            void main() {
                vec2 px = vUv * uResolution;
                if (uGridMode == 0) {
                    vec2 cell = floor(px / 22.0);
                    float m = mod(cell.x + cell.y, 2.0);
                    vec3 c = mix(vec3(0.11, 0.12, 0.15), vec3(0.18, 0.20, 0.24), m);
                    gl_FragColor = vec4(c, 1.0);
                } else {
                    float gx = step(0.97, fract(px.x / 64.0));
                    float gy = step(0.97, fract(px.y / 64.0));
                    float line = max(gx, gy);
                    vec3 col = mix(vec3(0.04, 0.05, 0.07), vec3(0.0, 0.55, 0.48), line * 0.45);
                    gl_FragColor = vec4(col, 1.0);
                }
            }
        """

        const val LAYER_QUAD_VERTEX_SHADER = """
            attribute vec2 aPosition;
            attribute vec2 aTexCoord;
            uniform vec2 uCenterPx;
            uniform vec2 uHalfSizePx;
            uniform vec2 uPivotPx;
            uniform float uRotRad;
            uniform vec2 uViewportPx;
            varying vec2 vUv;
            void main() {
                vec2 localPx = aPosition * uHalfSizePx - uPivotPx;
                float c = cos(uRotRad);
                float s = sin(uRotRad);
                vec2 rotatedPx = vec2(localPx.x * c - localPx.y * s, localPx.x * s + localPx.y * c) + uPivotPx;
                vec2 worldPx = uCenterPx + rotatedPx;
                vec2 ndc = (worldPx / max(uViewportPx, vec2(1.0))) * 2.0 - 1.0;
                gl_Position = vec4(ndc, 0.0, 1.0);
                vUv = vec2(aTexCoord.x, 1.0 - aTexCoord.y);
            }
        """

        const val LAYER_QUAD_FRAGMENT_SHADER = """
            precision mediump float;
            varying vec2 vUv;
            uniform sampler2D uTexture;
            uniform float uOpacity;
            void main() {
                vec4 tex = texture2D(uTexture, vUv);
                gl_FragColor = vec4(tex.rgb, tex.a * uOpacity);
            }
        """

        const val OES_LAYER_QUAD_VERTEX_SHADER = """
            attribute vec2 aPosition;
            attribute vec2 aTexCoord;
            uniform vec2 uCenterPx;
            uniform vec2 uHalfSizePx;
            uniform vec2 uPivotPx;
            uniform float uRotRad;
            uniform vec2 uViewportPx;
            uniform mat4 uTexMatrix;
            varying vec2 vUv;
            void main() {
                vec2 localPx = aPosition * uHalfSizePx - uPivotPx;
                float c = cos(uRotRad);
                float s = sin(uRotRad);
                vec2 rotatedPx = vec2(localPx.x * c - localPx.y * s, localPx.x * s + localPx.y * c) + uPivotPx;
                vec2 worldPx = uCenterPx + rotatedPx;
                vec2 ndc = (worldPx / max(uViewportPx, vec2(1.0))) * 2.0 - 1.0;
                gl_Position = vec4(ndc, 0.0, 1.0);
                vec2 safeTc = clamp(aTexCoord, 0.002, 0.998);
                vUv = (uTexMatrix * vec4(safeTc, 0.0, 1.0)).xy;
            }
        """

        const val OES_LAYER_QUAD_FRAGMENT_SHADER = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            varying vec2 vUv;
            uniform samplerExternalOES uOesTexture;
            uniform float uOpacity;
            void main() {
                vec4 tex = texture2D(uOesTexture, vUv);
                gl_FragColor = vec4(tex.rgb, tex.a * uOpacity);
            }
        """

        const val MESH_3D_VERTEX_SHADER = """
            attribute vec3 aPosition;
            attribute vec3 aNormal;
            uniform mat4 uMVP;
            varying vec3 vNormal;
            varying vec3 vPos;
            void main() {
                vNormal = aNormal;
                vPos = aPosition;
                gl_Position = uMVP * vec4(aPosition, 1.0);
            }
        """

        const val MESH_3D_FRAGMENT_SHADER = """
            precision mediump float;
            varying vec3 vNormal;
            varying vec3 vPos;
            uniform vec4 uColor;
            uniform float uTime;
            void main() {
                vec3 lightDir = normalize(vec3(0.6, 0.9, 1.0));
                vec3 rimDir = normalize(vec3(-0.8, 0.3, -0.5));
                vec3 n = normalize(vNormal);
                float diff = max(dot(n, lightDir), 0.18);
                float rim = pow(max(dot(n, rimDir), 0.0), 2.2) * 0.75;
                vec3 neonRim = vec3(0.0, 0.85, 0.68) * rim;
                vec3 col = uColor.rgb * diff + neonRim;
                gl_FragColor = vec4(col, uColor.a);
            }
        """

        const val MULTIPASS_EFFECT_FRAGMENT_SHADER = """
            precision mediump float;
            varying vec2 vUv;
            uniform sampler2D uTexture;
            uniform int uMode;
            uniform float uIntensity;
            uniform float uSecondary;
            uniform float uFreq;
            uniform float uAngle;
            uniform float uMix;
            uniform vec3 uTint;
            uniform float uTime;
            uniform vec2 uResolution;
            uniform int uQualityLevel;

            float hash(vec2 p) {
                return fract(sin(dot(p, vec2(127.1, 311.7))) * 43758.5453123);
            }

            vec2 rot2d(vec2 v, float a) {
                float ca = cos(a);
                float sa = sin(a);
                return vec2(v.x * ca - v.y * sa, v.x * sa + v.y * ca);
            }

            void main() {
                vec2 uv = vUv;
                vec2 px = 1.0 / max(uResolution, vec2(64.0));
                vec4 origBase = texture2D(uTexture, uv);
                vec4 outCol = origBase;

                if (uMode == 1) {
                    float spread = (1.5 + uSecondary * 7.5);
                    vec2 axisA = rot2d(vec2(px.x, px.y) * spread, uAngle);
                    vec2 axisB = rot2d(vec2(-px.x, px.y) * spread, uAngle);
                    vec4 sum = vec4(0.0);
                    if (uQualityLevel <= 1) {
                        sum += texture2D(uTexture, clamp(uv - axisA, 0.0, 1.0));
                        sum += texture2D(uTexture, clamp(uv + axisA, 0.0, 1.0));
                        float flicker = 0.85 + 0.15 * cos(uTime * (2.0 + uFreq * 9.0));
                        vec3 bloom = (sum.rgb * 0.5) * uTint * uIntensity * 1.45 * flicker;
                        outCol = vec4(clamp(origBase.rgb + bloom, 0.0, 1.0), origBase.a);
                    } else {
                        sum += texture2D(uTexture, clamp(uv - axisA, 0.0, 1.0));
                        sum += texture2D(uTexture, clamp(uv + axisA, 0.0, 1.0));
                        sum += texture2D(uTexture, clamp(uv - axisB, 0.0, 1.0));
                        sum += texture2D(uTexture, clamp(uv + axisB, 0.0, 1.0));
                        sum += texture2D(uTexture, clamp(uv + axisA * 1.8, 0.0, 1.0));
                        sum += texture2D(uTexture, clamp(uv - axisB * 1.8, 0.0, 1.0));
                        float flicker = 0.85 + 0.15 * cos(uTime * (2.0 + uFreq * 9.0));
                        vec3 bloom = (sum.rgb / 6.0) * uTint * uIntensity * 1.45 * flicker;
                        outCol = vec4(clamp(origBase.rgb + bloom, 0.0, 1.0), origBase.a);
                    }
                } else if (uMode == 2) {
                    float shift = uIntensity * (0.018 + uSecondary * 0.03) * (0.65 + 0.35 * sin(uTime * (2.0 + uFreq * 8.0)));
                    vec2 axis = rot2d(vec2(shift, 0.0), uAngle);
                    float r = texture2D(uTexture, clamp(uv + axis, 0.0, 1.0)).r;
                    float g = origBase.g;
                    float b = texture2D(uTexture, clamp(uv - axis, 0.0, 1.0)).b;
                    outCol = vec4(vec3(r, g, b) * uTint, origBase.a);
                } else if (uMode == 3) {
                    float freq = 5.0 + uFreq * 22.0;
                    float amp = uIntensity * 0.048;
                    vec2 dirUv = rot2d(uv - 0.5, uAngle) + 0.5;
                    vec2 delta = vec2(
                        sin(dirUv.y * freq + uTime * 3.5) * amp,
                        cos(dirUv.x * freq + uTime * 2.8) * amp * (0.2 + uSecondary)
                    );
                    outCol = texture2D(uTexture, clamp(uv + rot2d(delta, -uAngle), 0.0, 1.0));
                } else if (uMode == 4) {
                    vec2 ruv = rot2d(uv - 0.5, uAngle) + 0.5;
                    float sliceY = floor(ruv.y * (14.0 + uSecondary * 42.0));
                    float jitter = (hash(vec2(sliceY, floor(uTime * (8.0 + uFreq * 12.0)))) - 0.5) * 0.095 * uIntensity;
                    float mask = step(0.58, hash(vec2(sliceY, floor(uTime * 9.0))));
                    vec2 guv = clamp(uv + rot2d(vec2(jitter * mask, 0.0), -uAngle), 0.0, 1.0);
                    vec4 col = texture2D(uTexture, guv);
                    float scan = 0.90 + 0.10 * sin(ruv.y * uResolution.y * 1.2);
                    outCol = vec4(col.rgb * scan * uTint, col.a);
                } else if (uMode == 5) {
                    float scale = max(0.35, uIntensity * 1.45);
                    vec2 centered = rot2d((uv - 0.5) * scale, uAngle) + 0.5 + vec2(uSecondary - 0.5, uFreq * 0.35);
                    vec2 tuv = fract(centered);
                    vec2 mirrored = 1.0 - abs(fract(centered * 0.5) * 2.0 - 1.0);
                    vec2 muv = mix(tuv, mirrored, step(0.15, uSecondary));
                    outCol = texture2D(uTexture, clamp(muv, 0.0, 1.0));
                } else if (uMode == 6) {
                    vec2 radial = (vec2(0.5 + (uSecondary - 0.5) * 0.4, 0.5) - uv);
                    vec2 directional = rot2d(vec2(0.04, 0.0), uAngle);
                    vec2 dir = mix(radial, directional, step(0.05, abs(uAngle))) * uIntensity * (0.8 + uFreq * 0.5);
                    if (uQualityLevel <= 1) {
                        vec4 acc = origBase * 0.55 + texture2D(uTexture, clamp(uv + dir * 0.75, 0.0, 1.0)) * 0.45;
                        outCol = acc;
                    } else {
                        vec4 acc = vec4(0.0);
                        acc += texture2D(uTexture, clamp(uv + dir * 0.0, 0.0, 1.0)) * 0.28;
                        acc += texture2D(uTexture, clamp(uv + dir * 0.35, 0.0, 1.0)) * 0.24;
                        acc += texture2D(uTexture, clamp(uv + dir * 0.70, 0.0, 1.0)) * 0.20;
                        acc += texture2D(uTexture, clamp(uv + dir * 1.05, 0.0, 1.0)) * 0.16;
                        acc += texture2D(uTexture, clamp(uv + dir * 1.40, 0.0, 1.0)) * 0.12;
                        outCol = acc;
                    }
                } else if (uMode == 7) {
                    vec2 wind = rot2d(vec2(0.0, uTime * (1.2 + uFreq * 3.0)), uAngle);
                    vec2 grid = uv * (12.0 + uSecondary * 28.0) + wind;
                    vec2 id = floor(grid);
                    vec2 f = fract(grid) - 0.5;
                    float rnd = hash(id);
                    float dist = length(f - vec2(rnd - 0.5, fract(rnd * 17.3) - 0.5) * 0.5);
                    float spark = smoothstep(0.16, 0.01, dist) * step(0.58, rnd) * uIntensity;
                    vec3 sparkCol = mix(vec3(0.0, 0.85, 0.68), uTint, 0.65);
                    outCol = vec4(clamp(origBase.rgb + sparkCol * spark * 1.9, 0.0, 1.0), origBase.a);
                } else if (uMode == 8) {
                    float thick = 1.0 + uSecondary * 3.0;
                    vec3 c  = origBase.rgb;
                    vec3 cx = texture2D(uTexture, clamp(uv + vec2(px.x * thick, 0.0), 0.0, 1.0)).rgb;
                    vec3 cy = texture2D(uTexture, clamp(uv + vec2(0.0, px.y * thick), 0.0, 1.0)).rgb;
                    float edge = length(c - cx) + length(c - cy);
                    vec3 neon = uTint * smoothstep(0.06, 0.42, edge) * (uIntensity * 2.2);
                    outCol = vec4(clamp(c * (1.0 - uFreq * 0.25) + neon, 0.0, 1.0), origBase.a);
                } else if (uMode == 9) {
                    float angle = uIntensity * 3.14159 + uAngle + uTime * uFreq * 0.4;
                    vec3 k = vec3(0.57735);
                    float cosA = cos(angle);
                    vec3 shifted = origBase.rgb * cosA + cross(k, origBase.rgb) * sin(angle) + k * dot(k, origBase.rgb) * (1.0 - cosA);
                    float gamma = max(0.35, uSecondary);
                    vec3 graded = pow(clamp(shifted * uTint + vec3(uFreq * 0.15), 0.0, 1.0), vec3(1.0 / gamma));
                    outCol = vec4(graded, origBase.a);
                } else if (uMode == 11) {
                    // Range Wipe / Directional Slice
                    vec2 dirUv = rot2d(uv - 0.5, uAngle) + 0.5;
                    float feather = max(0.015, uFreq * 0.35 + 0.02);
                    float lo = min(uIntensity, uSecondary);
                    float hi = max(uIntensity, uSecondary);
                    float mask = smoothstep(lo - feather, lo + feather, dirUv.x) *
                        (1.0 - smoothstep(hi - feather, hi + feather, dirUv.x));
                    vec2 offsetUv = clamp(uv + rot2d(vec2(0.0, (1.0 - mask) * 0.06), -uAngle), 0.0, 1.0);
                    vec4 sampleCol = texture2D(uTexture, offsetUv);
                    outCol = vec4(sampleCol.rgb * mask, sampleCol.a * mask);
                } else if (uMode == 12) {
                    // Alight Motion Chroma Key / Luma Key with Feather & Spill Suppression
                    float chromaDist = distance(origBase.rgb, uTint);
                    float thresh = clamp(uIntensity, 0.02, 0.90);
                    float feather = max(0.015, uSecondary * 0.45);
                    float keepMask = smoothstep(thresh, thresh + feather, chromaDist);
                    float spill = (1.0 - keepMask) * clamp(uFreq, 0.0, 1.0);
                    float luma = dot(origBase.rgb, vec3(0.299, 0.587, 0.114));
                    vec3 despill = mix(origBase.rgb, vec3(luma), spill * 0.75);
                    vec3 bgChecker = vec3(0.06, 0.07, 0.09);
                    outCol = vec4(mix(bgChecker, despill, keepMask), origBase.a * keepMask);
                } else {
                    float spd = 12.0 + uFreq * 36.0;
                    vec2 shakeVec = rot2d(vec2(sin(uTime * spd), cos(uTime * spd * 0.87) * uSecondary), uAngle) * 0.032 * uIntensity;
                    vec2 centered = rot2d((uv - 0.5) / (1.0 + 0.14 * uIntensity * abs(sin(uTime * 6.0))), uAngle * 0.25 * uIntensity) + 0.5;
                    outCol = texture2D(uTexture, clamp(centered + shakeVec, 0.0, 1.0));
                }

                gl_FragColor = mix(origBase, outCol, clamp(uMix, 0.0, 1.0));
            }
        """
    }
}
