package com.example.engine

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.GLUtils
import com.example.data.model.EffectNode
import com.example.data.model.Layer
import com.example.data.model.LayerType
import com.example.data.model.Primitive3DType
import com.example.data.model.Project
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.ShortBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * Pure GPU Per-Layer Quad + Multi-Pass Ping-Pong OpenGL ES 2.0 Compositor:
 * - Eliminates per-frame CPU full-screen Bitmap uploads for true 60 FPS VSync playback
 * - Renders real imported Images & Videos (with Time Remap, Reverse, Freeze Frame, and Speed)
 * - Caches Shape & Text rasterizations in dedicated GPU textures (0 CPU work during keyframe playback)
 * - Supports Camera layers, Clipping Masks, Blend Modes, and multi-sample GPU Motion Blur shutter
 */
class FramePollutionGlRenderer(
    private val appContext: Context,
    private val onAsyncMediaReady: () -> Unit = {}
) : GLSurfaceView.Renderer {

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

    private var surfaceWidth = 1280
    private var surfaceHeight = 720

    private var quadProgram = 0
    private var checkerBgProgram = 0
    private var layerQuadProgram = 0
    private var mesh3DProgram = 0
    private var effectProgram = 0

    private val fboIds = IntArray(2)
    private val fboTexIds = IntArray(2)
    private var fboAllocW = 0
    private var fboAllocH = 0

    private data class GpuLayerTexture(
        val texId: Int,
        var signature: String = "",
        var bitmapIdentity: Int = 0,
        var texW: Int = 0,
        var texH: Int = 0,
        var aspectWOverH: Float = 1f
    )

    private val layerTextures = HashMap<String, GpuLayerTexture>()

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
        layerTextures.clear()
        fboAllocW = 0
        fboAllocH = 0
        fboIds[0] = 0
        fboIds[1] = 0

        quadBuffer = buildDirectFloatBuffer(fullQuadCoords)
        quadProgram = buildProgram(QUAD_VERTEX_SHADER, PASSTHROUGH_FRAGMENT_SHADER)
        checkerBgProgram = buildProgram(QUAD_VERTEX_SHADER, CHECKER_FRAGMENT_SHADER)
        layerQuadProgram = buildProgram(LAYER_QUAD_VERTEX_SHADER, LAYER_QUAD_FRAGMENT_SHADER)
        effectProgram = buildProgram(QUAD_VERTEX_SHADER, MULTIPASS_EFFECT_FRAGMENT_SHADER)
        mesh3DProgram = buildProgram(MESH_3D_VERTEX_SHADER, MESH_3D_FRAGMENT_SHADER)
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        surfaceWidth = width.coerceAtLeast(1)
        surfaceHeight = height.coerceAtLeast(1)
        GLES20.glViewport(0, 0, surfaceWidth, surfaceHeight)
    }

    override fun onDrawFrame(gl: GL10?) {
        val project = currentProject
        val durMs = project.durationMs.coerceAtLeast(100L)
        val timeMs = if (isPlaying && playbackAnchorNano > 0L) {
            val elapsedMs = ((System.nanoTime() - playbackAnchorNano) / 1_000_000L).coerceAtLeast(0L)
            (playbackAnchorMs + elapsedMs) % durMs
        } else {
            playheadMs.coerceIn(0L, durMs)
        }
        val audio = audioSnapshot

        val activeEffects = project.layers
            .asSequence()
            .filter { it.visible && timeMs in it.startTimeMs..it.endTimeMs }
            .flatMap { it.effects.asSequence() }
            .filter { it.enabled }
            .toList()

        val maxPreviewDim = if (activeEffects.size >= 3) 720 else 960
        val aspect = (project.width.toFloat().coerceAtLeast(64f) / project.height.toFloat().coerceAtLeast(64f))
            .coerceIn(0.2f, 5f)
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

        render2DLayerStackOnGpu(project, timeMs, audio, internalW.toFloat(), internalH.toFloat())
        render3DLayers(project, timeMs, audio, aspect)

        // Multi-pass Ping-Pong Shader Pipeline
        var readIndex = 0
        activeEffects.forEach { fx ->
            val writeIndex = 1 - readIndex
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fboIds[writeIndex])
            GLES20.glViewport(0, 0, internalW, internalH)
            GLES20.glClearColor(0f, 0f, 0f, 1f)
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
            drawEffectPass(
                inputTex = fboTexIds[readIndex],
                effect = fx,
                timeSec = timeMs / 1000f,
                audio = audio,
                resW = internalW.toFloat(),
                resH = internalH.toFloat()
            )
            readIndex = writeIndex
        }

        // Final Output Pass to Screen Surface
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        GLES20.glClearColor(0.055f, 0.063f, 0.082f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

        val surfaceAspect = surfaceWidth.toFloat() / surfaceHeight.toFloat()
        val vpW: Int
        val vpH: Int
        if (surfaceAspect > aspect) {
            vpH = surfaceHeight
            vpW = (surfaceHeight * aspect).toInt()
        } else {
            vpW = surfaceWidth
            vpH = (surfaceWidth / aspect).toInt()
        }
        val vpX = (surfaceWidth - vpW) / 2
        val vpY = (surfaceHeight - vpH) / 2
        GLES20.glViewport(vpX, vpY, vpW, vpH)
        drawQuadWithTexture(quadProgram, fboTexIds[readIndex], flipY = false)
    }

    private fun clearCompositionBackground(bgMode: String, w: Float, h: Float) {
        when (bgMode) {
            "White" -> GLES20.glClearColor(1f, 1f, 1f, 1f)
            "Dark Gray" -> GLES20.glClearColor(0.11f, 0.125f, 0.155f, 1f)
            "Light Gray" -> GLES20.glClearColor(0.82f, 0.84f, 0.87f, 1f)
            else -> GLES20.glClearColor(0f, 0f, 0f, 1f) // Pure Alight Motion Black
        }
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)

        if (bgMode == "Transparent" || show3DGridOverlay) {
            GLES20.glUseProgram(checkerBgProgram)
            val posLoc = GLES20.glGetAttribLocation(checkerBgProgram, "aPosition")
            val uvLoc = GLES20.glGetAttribLocation(checkerBgProgram, "aTexCoord")
            quadBuffer.position(0)
            GLES20.glEnableVertexAttribArray(posLoc)
            GLES20.glVertexAttribPointer(posLoc, 2, GLES20.GL_FLOAT, false, 16, quadBuffer)
            quadBuffer.position(2)
            GLES20.glEnableVertexAttribArray(uvLoc)
            GLES20.glVertexAttribPointer(uvLoc, 2, GLES20.GL_FLOAT, false, 16, quadBuffer)
            GLES20.glUniform1f(GLES20.glGetUniformLocation(checkerBgProgram, "uFlipY"), 0f)
            GLES20.glUniform2f(GLES20.glGetUniformLocation(checkerBgProgram, "uResolution"), w, h)
            GLES20.glUniform1i(
                GLES20.glGetUniformLocation(checkerBgProgram, "uGridMode"),
                if (bgMode == "Transparent") 0 else 1
            )
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
            GLES20.glDisableVertexAttribArray(posLoc)
            GLES20.glDisableVertexAttribArray(uvLoc)
        }
    }

    private fun render2DLayerStackOnGpu(
        project: Project,
        timeMs: Long,
        audio: AudioSpectrumSnapshot,
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

            if (layer.motionBlurEnabled) {
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
                    val samples = 4
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
                        drawTransformedLayerQuad(texEntry.texId, layer, interp, baseHalfW, baseHalfH, scaleToFbo, fboW, fboH)
                    }
                    return@forEachIndexed
                }
            }

            drawTransformedLayerQuad(texEntry.texId, layer, currTr, baseHalfW, baseHalfH, scaleToFbo, fboW, fboH)
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
                // Fit imported video/image cleanly inside 82% of composition while preserving exact native aspect ratio!
                val compAspect = fboW / fboH.coerceAtLeast(1f)
                val safeAspect = texAspect.coerceIn(0.15f, 6.0f)
                if (safeAspect >= compAspect) {
                    val hw = fboW * 0.42f
                    val hh = (hw / safeAspect).coerceAtLeast(8f)
                    hw to hh
                } else {
                    val hh = fboH * 0.42f
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

    private fun drawTransformedLayerQuad(
        texId: Int,
        layer: Layer,
        tr: EvaluatedTransform,
        baseHalfW: Float,
        baseHalfH: Float,
        scaleToFbo: Float,
        fboW: Float,
        fboH: Float
    ) {
        GLES20.glUseProgram(layerQuadProgram)
        val posLoc = GLES20.glGetAttribLocation(layerQuadProgram, "aPosition")
        val uvLoc = GLES20.glGetAttribLocation(layerQuadProgram, "aTexCoord")

        quadBuffer.position(0)
        GLES20.glEnableVertexAttribArray(posLoc)
        GLES20.glVertexAttribPointer(posLoc, 2, GLES20.GL_FLOAT, false, 16, quadBuffer)

        quadBuffer.position(2)
        GLES20.glEnableVertexAttribArray(uvLoc)
        GLES20.glVertexAttribPointer(uvLoc, 2, GLES20.GL_FLOAT, false, 16, quadBuffer)

        val cxPx = fboW * 0.5f + tr.posX * scaleToFbo
        // Note: OpenGL FBO Y=0 is bottom, while screen/canvas +Y is downward -> invert Y offset so dragging down moves down!
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

        GLES20.glUniform2f(GLES20.glGetUniformLocation(layerQuadProgram, "uCenterPx"), cxPx, cyPx)
        GLES20.glUniform2f(GLES20.glGetUniformLocation(layerQuadProgram, "uHalfSizePx"), effHalfW, effHalfH)
        GLES20.glUniform2f(GLES20.glGetUniformLocation(layerQuadProgram, "uPivotPx"), pivXPx, pivYPx)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(layerQuadProgram, "uRotRad"), rotRad)
        GLES20.glUniform2f(GLES20.glGetUniformLocation(layerQuadProgram, "uViewportPx"), fboW, fboH)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(layerQuadProgram, "uOpacity"), tr.opacity.coerceIn(0f, 1f))

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texId)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(layerQuadProgram, "uTexture"), 0)

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(posLoc)
        GLES20.glDisableVertexAttribArray(uvLoc)
    }

    private fun prepareLayerGpuTexture(
        layer: Layer,
        timeMs: Long,
        audio: AudioSpectrumSnapshot
    ): GpuLayerTexture? {
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

        when (layer.type) {
            LayerType.IMAGE, LayerType.VIDEO -> {
                val srcTimeMs = if (layer.type == LayerType.VIDEO) {
                    AnimationEvaluator.evaluateSourceMediaTimeMs(layer, timeMs, audio)
                } else 0L

                val mediaBmp = MediaEngine.getMediaBitmapNonBlocking(
                    context = appContext,
                    layer = layer,
                    sourceTimeMs = srcTimeMs,
                    onAsyncFrameReady = onAsyncMediaReady
                )

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
                val sig = "shape|${layer.shapeKind}|${layer.colorHex}|${layer.strokeEnabled}|${layer.strokeWidth}|" +
                    "${layer.strokeColorHex}|${layer.shadowEnabled}|${layer.shadowBlur}|${layer.shadowColorHex}"
                if (entry.signature != sig) {
                    val bmp = rasterizeShapeBitmap(layer)
                    uploadBitmapToEntry(entry, bmp, sig)
                }
                return entry
            }

            LayerType.TEXT -> {
                val sig = "text|${layer.textContent}|${layer.fontFamilyName}|${layer.colorHex}|" +
                    "${layer.strokeEnabled}|${layer.strokeWidth}|${layer.strokeColorHex}|" +
                    "${layer.shadowEnabled}|${layer.shadowBlur}|${layer.shadowColorHex}"
                if (entry.signature != sig) {
                    val bmp = rasterizeTextBitmap(layer)
                    uploadBitmapToEntry(entry, bmp, sig)
                }
                return entry
            }

            else -> return null
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
        val strokeCol = runCatching { Color.parseColor(layer.strokeColorHex) }.getOrDefault(Color.WHITE)
        paintFill.color = fillCol
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
        paintFill.clearShadowLayer()
        return bmp
    }

    private fun rasterizeTextBitmap(layer: Layer): Bitmap {
        val txt = layer.textContent.ifBlank { "Text" }
        paintText.textSize = 76f
        paintText.typeface = when (layer.fontFamilyName) {
            "Serif" -> Typeface.SERIF
            "Monospace", "JetBrains Mono" -> Typeface.MONOSPACE
            "Cursive" -> Typeface.create("cursive", Typeface.ITALIC)
            else -> Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        }
        val measuredW = paintText.measureText(txt).toInt().coerceIn(160, 1400)
        val bmpW = (measuredW + 96).coerceAtMost(1536)
        val bmpH = 200
        val bmp = Bitmap.createBitmap(bmpW, bmpH, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)

        val fillCol = runCatching { Color.parseColor(layer.colorHex) }.getOrDefault(Color.WHITE)
        paintText.style = Paint.Style.FILL
        paintText.color = fillCol
        if (layer.shadowEnabled) {
            val shad = runCatching { Color.parseColor(layer.shadowColorHex) }.getOrDefault(Color.BLACK)
            paintText.setShadowLayer(layer.shadowBlur.coerceIn(2f, 36f), 0f, 4f, shad)
        } else {
            paintText.clearShadowLayer()
        }
        val baseline = bmpH * 0.62f
        c.drawText(txt, bmpW * 0.5f, baseline, paintText)

        if (layer.strokeEnabled && layer.strokeWidth > 0.5f) {
            paintText.clearShadowLayer()
            paintText.style = Paint.Style.STROKE
            paintText.strokeWidth = layer.strokeWidth.coerceIn(1f, 20f)
            paintText.color = runCatching { Color.parseColor(layer.strokeColorHex) }.getOrDefault(Color.WHITE)
            c.drawText(txt, bmpW * 0.5f, baseline, paintText)
            paintText.style = Paint.Style.FILL
        }
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
        if (layerTextures.size <= project.layers.size + 4) return
        val activeIds = project.layers.map { it.id }.toHashSet()
        val iter = layerTextures.entries.iterator()
        while (iter.hasNext()) {
            val entry = iter.next()
            if (entry.key !in activeIds) {
                GLES20.glDeleteTextures(1, intArrayOf(entry.value.texId), 0)
                iter.remove()
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

        val posLoc = GLES20.glGetAttribLocation(mesh3DProgram, "aPosition")
        val normLoc = GLES20.glGetAttribLocation(mesh3DProgram, "aNormal")
        val mvpLoc = GLES20.glGetUniformLocation(mesh3DProgram, "uMVP")
        val colorLoc = GLES20.glGetUniformLocation(mesh3DProgram, "uColor")
        val timeLoc = GLES20.glGetUniformLocation(mesh3DProgram, "uTime")

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
            GLES20.glEnableVertexAttribArray(posLoc)
            GLES20.glVertexAttribPointer(posLoc, 3, GLES20.GL_FLOAT, false, stride, vBuf)

            vBuf.position(3)
            GLES20.glEnableVertexAttribArray(normLoc)
            GLES20.glVertexAttribPointer(normLoc, 3, GLES20.GL_FLOAT, false, stride, vBuf)

            val cInt = runCatching { Color.parseColor(layer.colorHex) }.getOrDefault(Color.rgb(0, 210, 168))
            GLES20.glUniformMatrix4fv(mvpLoc, 1, false, mvp, 0)
            GLES20.glUniform4f(
                colorLoc,
                Color.red(cInt) / 255f,
                Color.green(cInt) / 255f,
                Color.blue(cInt) / 255f,
                tr.opacity
            )
            GLES20.glUniform1f(timeLoc, timeMs / 1000f)

            iBuf.position(0)
            GLES20.glDrawElements(GLES20.GL_TRIANGLES, mesh.indices.size, GLES20.GL_UNSIGNED_SHORT, iBuf)
            GLES20.glDisableVertexAttribArray(posLoc)
            GLES20.glDisableVertexAttribArray(normLoc)
        }

        GLES20.glDisable(GLES20.GL_DEPTH_TEST)
    }

    private fun drawQuadWithTexture(program: Int, texId: Int, flipY: Boolean) {
        GLES20.glUseProgram(program)
        val posLoc = GLES20.glGetAttribLocation(program, "aPosition")
        val uvLoc = GLES20.glGetAttribLocation(program, "aTexCoord")
        val flipLoc = GLES20.glGetUniformLocation(program, "uFlipY")
        val texLoc = GLES20.glGetUniformLocation(program, "uTexture")

        quadBuffer.position(0)
        GLES20.glEnableVertexAttribArray(posLoc)
        GLES20.glVertexAttribPointer(posLoc, 2, GLES20.GL_FLOAT, false, 16, quadBuffer)

        quadBuffer.position(2)
        GLES20.glEnableVertexAttribArray(uvLoc)
        GLES20.glVertexAttribPointer(uvLoc, 2, GLES20.GL_FLOAT, false, 16, quadBuffer)

        GLES20.glUniform1f(flipLoc, if (flipY) 1f else 0f)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texId)
        GLES20.glUniform1i(texLoc, 0)

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(posLoc)
        GLES20.glDisableVertexAttribArray(uvLoc)
    }

    private fun drawEffectPass(
        inputTex: Int,
        effect: EffectNode,
        timeSec: Float,
        audio: AudioSpectrumSnapshot,
        resW: Float,
        resH: Float
    ) {
        val def = EffectsCatalog.findById(effect.effectId)
        val audioMod = audio.levelFor(effect.audioBoundBand) * effect.audioSensitivity
        val effectiveIntensity = (effect.intensity + audioMod * 0.65f).coerceIn(0f, 2f)

        GLES20.glUseProgram(effectProgram)
        val posLoc = GLES20.glGetAttribLocation(effectProgram, "aPosition")
        val uvLoc = GLES20.glGetAttribLocation(effectProgram, "aTexCoord")

        quadBuffer.position(0)
        GLES20.glEnableVertexAttribArray(posLoc)
        GLES20.glVertexAttribPointer(posLoc, 2, GLES20.GL_FLOAT, false, 16, quadBuffer)

        quadBuffer.position(2)
        GLES20.glEnableVertexAttribArray(uvLoc)
        GLES20.glVertexAttribPointer(uvLoc, 2, GLES20.GL_FLOAT, false, 16, quadBuffer)

        GLES20.glUniform1f(GLES20.glGetUniformLocation(effectProgram, "uFlipY"), 0f)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(effectProgram, "uMode"), def.shaderMode)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(effectProgram, "uIntensity"), effectiveIntensity)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(effectProgram, "uSecondary"), effect.secondaryParam)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(effectProgram, "uFreq"), effect.frequencyParam)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(effectProgram, "uTime"), timeSec)
        GLES20.glUniform2f(GLES20.glGetUniformLocation(effectProgram, "uResolution"), resW, resH)

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, inputTex)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(effectProgram, "uTexture"), 0)

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(posLoc)
        GLES20.glDisableVertexAttribArray(uvLoc)
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
            uniform float uTime;
            uniform vec2 uResolution;

            float hash(vec2 p) {
                return fract(sin(dot(p, vec2(127.1, 311.7))) * 43758.5453123);
            }

            void main() {
                vec2 uv = vUv;
                vec2 px = 1.0 / max(uResolution, vec2(64.0));

                if (uMode == 1) {
                    vec4 base = texture2D(uTexture, uv);
                    float spread = (1.5 + uSecondary * 7.0);
                    vec4 sum = vec4(0.0);
                    sum += texture2D(uTexture, uv + vec2(-px.x, -px.y) * spread);
                    sum += texture2D(uTexture, uv + vec2( px.x, -px.y) * spread);
                    sum += texture2D(uTexture, uv + vec2(-px.x,  px.y) * spread);
                    sum += texture2D(uTexture, uv + vec2( px.x,  px.y) * spread);
                    sum += texture2D(uTexture, uv + vec2(0.0, px.y * spread * 1.8));
                    sum += texture2D(uTexture, uv + vec2(px.x * spread * 1.8, 0.0));
                    vec3 bloom = (sum.rgb / 6.0) * uIntensity * 1.45;
                    gl_FragColor = vec4(clamp(base.rgb + bloom, 0.0, 1.0), base.a);
                } else if (uMode == 2) {
                    float shift = uIntensity * 0.032 * (0.6 + 0.4 * sin(uTime * (2.0 + uFreq * 8.0)));
                    float r = texture2D(uTexture, clamp(uv + vec2(shift, 0.0), 0.0, 1.0)).r;
                    float g = texture2D(uTexture, uv).g;
                    float b = texture2D(uTexture, clamp(uv - vec2(shift, 0.0), 0.0, 1.0)).b;
                    gl_FragColor = vec4(r, g, b, 1.0);
                } else if (uMode == 3) {
                    float freq = 6.0 + uFreq * 24.0;
                    float amp = uIntensity * 0.045;
                    vec2 warped = uv + vec2(
                        sin(uv.y * freq + uTime * 3.5) * amp,
                        cos(uv.x * freq + uTime * 2.8) * amp * uSecondary
                    );
                    gl_FragColor = texture2D(uTexture, clamp(warped, 0.0, 1.0));
                } else if (uMode == 4) {
                    float sliceY = floor(uv.y * (18.0 + uFreq * 40.0));
                    float jitter = (hash(vec2(sliceY, floor(uTime * 14.0))) - 0.5) * 0.09 * uIntensity;
                    float mask = step(0.65, hash(vec2(sliceY, floor(uTime * 9.0))));
                    vec2 guv = clamp(vec2(uv.x + jitter * mask, uv.y), 0.0, 1.0);
                    vec4 col = texture2D(uTexture, guv);
                    float scan = 0.90 + 0.10 * sin(uv.y * uResolution.y * 1.2);
                    gl_FragColor = vec4(col.rgb * scan, col.a);
                } else if (uMode == 5) {
                    float tiles = 1.0 + floor(uSecondary * 4.0);
                    vec2 tuv = fract(uv * tiles);
                    vec2 muv = mix(tuv, abs(tuv * 2.0 - 1.0), clamp(uIntensity, 0.0, 1.0));
                    gl_FragColor = texture2D(uTexture, clamp(muv, 0.0, 1.0));
                } else if (uMode == 6) {
                    vec2 dir = (vec2(0.5, 0.5) - uv) * uIntensity * 0.055;
                    vec4 acc = vec4(0.0);
                    acc += texture2D(uTexture, clamp(uv + dir * 0.0, 0.0, 1.0)) * 0.28;
                    acc += texture2D(uTexture, clamp(uv + dir * 0.35, 0.0, 1.0)) * 0.24;
                    acc += texture2D(uTexture, clamp(uv + dir * 0.70, 0.0, 1.0)) * 0.20;
                    acc += texture2D(uTexture, clamp(uv + dir * 1.05, 0.0, 1.0)) * 0.16;
                    acc += texture2D(uTexture, clamp(uv + dir * 1.40, 0.0, 1.0)) * 0.12;
                    gl_FragColor = acc;
                } else if (uMode == 7) {
                    vec4 base = texture2D(uTexture, uv);
                    vec2 grid = uv * (14.0 + uSecondary * 26.0) + vec2(0.0, uTime * (1.2 + uFreq * 3.0));
                    vec2 id = floor(grid);
                    vec2 f = fract(grid) - 0.5;
                    float rnd = hash(id);
                    float dist = length(f - vec2(rnd - 0.5, fract(rnd * 17.3) - 0.5) * 0.5);
                    float spark = smoothstep(0.14, 0.01, dist) * step(0.62, rnd) * uIntensity;
                    vec3 sparkCol = mix(vec3(0.0, 0.82, 0.66), vec3(1.0, 0.2, 0.4), fract(rnd * 9.1));
                    gl_FragColor = vec4(clamp(base.rgb + sparkCol * spark * 1.8, 0.0, 1.0), base.a);
                } else if (uMode == 8) {
                    vec3 c  = texture2D(uTexture, uv).rgb;
                    vec3 cx = texture2D(uTexture, clamp(uv + vec2(px.x * 2.0, 0.0), 0.0, 1.0)).rgb;
                    vec3 cy = texture2D(uTexture, clamp(uv + vec2(0.0, px.y * 2.0), 0.0, 1.0)).rgb;
                    float edge = length(c - cx) + length(c - cy);
                    vec3 neon = vec3(0.0, 0.82, 0.66) * smoothstep(0.08, 0.45, edge) * (uIntensity * 2.2);
                    gl_FragColor = vec4(clamp(c * (1.0 - uSecondary * 0.4) + neon, 0.0, 1.0), 1.0);
                } else if (uMode == 9) {
                    vec4 c = texture2D(uTexture, uv);
                    float angle = uIntensity * 6.28318 + uTime * uFreq * 2.0;
                    vec3 k = vec3(0.57735);
                    float cosA = cos(angle);
                    vec3 shifted = c.rgb * cosA + cross(k, c.rgb) * sin(angle) + k * dot(k, c.rgb) * (1.0 - cosA);
                    gl_FragColor = vec4(clamp(shifted, 0.0, 1.0), c.a);
                } else {
                    float sx = sin(uTime * 42.0) * 0.028 * uIntensity;
                    float sy = cos(uTime * 37.0) * 0.028 * uIntensity;
                    vec2 centered = (uv - 0.5) / (1.0 + 0.14 * uIntensity * abs(sin(uTime * 8.0))) + 0.5;
                    gl_FragColor = texture2D(uTexture, clamp(centered + vec2(sx, sy), 0.0, 1.0));
                }
            }
        """
    }
}
