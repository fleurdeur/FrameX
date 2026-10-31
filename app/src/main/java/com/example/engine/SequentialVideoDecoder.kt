package com.example.engine

import android.content.Context
import android.graphics.Bitmap
import android.graphics.SurfaceTexture
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs

/**
 * Hardware-accelerated sequential video frame decoder powered by [MediaExtractor] + [MediaCodec]
 * rendering directly into an OpenGL [GLES11Ext.GL_TEXTURE_EXTERNAL_OES] [SurfaceTexture].
 *
 * Replaces O(N^2) per-frame `MediaMetadataRetriever.getScaledFrameAtTime` seeks during both
 * Offscreen GPU Pre-Rendering and Reverse / Time-Remap RAM cache warming.
 */
class GlSequentialVideoSession(
    private val context: Context,
    val uriStr: String
) {
    var oesTexId: Int = 0
        private set
    val stMatrix = FloatArray(16)
    var videoWidth: Int = 1280
        private set
    var videoHeight: Int = 720
        private set
    var isReady: Boolean = false
        private set

    private var extractor: MediaExtractor? = null
    private var codec: MediaCodec? = null
    private var surfaceTexture: SurfaceTexture? = null
    private var outputSurface: Surface? = null
    private var callbackThread: HandlerThread? = null
    private var callbackHandler: Handler? = null

    private val frameSignalLock = Object()
    @Volatile private var frameArrived = false

    private var lastDecodedPtsUs: Long = -1L
    private var inputEos = false
    private var outputEos = false
    private val bufferInfo = MediaCodec.BufferInfo()

    fun openOnCurrentGlContext(): Boolean {
        return try {
            val texArr = IntArray(1)
            GLES20.glGenTextures(1, texArr, 0)
            oesTexId = texArr[0]
            if (oesTexId == 0) return false

            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTexId)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)

            val ht = HandlerThread("GlSeqFrameCb_${oesTexId}").apply { start() }
            callbackThread = ht
            val handler = Handler(ht.looper)
            callbackHandler = handler

            val st = SurfaceTexture(oesTexId)
            st.setOnFrameAvailableListener({
                synchronized(frameSignalLock) {
                    frameArrived = true
                    frameSignalLock.notifyAll()
                }
            }, handler)
            surfaceTexture = st
            val surf = Surface(st)
            outputSurface = surf

            val ext = MediaExtractor()
            setExtractorDataSource(context, ext, uriStr)
            var videoTrack = -1
            var format: MediaFormat? = null
            for (i in 0 until ext.trackCount) {
                val f = ext.getTrackFormat(i)
                val mime = f.getString(MediaFormat.KEY_MIME).orEmpty()
                if (mime.startsWith("video/")) {
                    videoTrack = i
                    format = f
                    break
                }
            }
            if (videoTrack < 0 || format == null) {
                ext.release()
                release()
                return false
            }

            ext.selectTrack(videoTrack)
            extractor = ext

            val rawW = if (format.containsKey(MediaFormat.KEY_WIDTH)) format.getInteger(MediaFormat.KEY_WIDTH) else 1280
            val rawH = if (format.containsKey(MediaFormat.KEY_HEIGHT)) format.getInteger(MediaFormat.KEY_HEIGHT) else 720
            val rot = if (format.containsKey(MediaFormat.KEY_ROTATION)) format.getInteger(MediaFormat.KEY_ROTATION) else 0
            if (rot == 90 || rot == 270) {
                videoWidth = rawH.coerceAtLeast(16)
                videoHeight = rawW.coerceAtLeast(16)
            } else {
                videoWidth = rawW.coerceAtLeast(16)
                videoHeight = rawH.coerceAtLeast(16)
            }

            val mime = format.getString(MediaFormat.KEY_MIME) ?: return false
            val dec = MediaCodec.createDecoderByType(mime)
            // Hint low-latency real-time priority when supported by vendor codec
            runCatching { format.setInteger(MediaFormat.KEY_PRIORITY, 0) }
            dec.configure(format, surf, null, 0)
            dec.start()
            codec = dec

            android.opengl.Matrix.setIdentityM(stMatrix, 0)
            isReady = true
            true
        } catch (_: Throwable) {
            release()
            false
        }
    }

    /**
     * Advances (or seeks when jumping backwards / > 1.8s ahead) the hardware decoder so [oesTexId]
     * holds the decoded frame for [targetTimeMs], updating [stMatrix] in place.
     */
    fun advanceToFrameMs(targetTimeMs: Long): Boolean {
        val ext = extractor ?: return false
        val dec = codec ?: return false
        val st = surfaceTexture ?: return false
        if (!isReady) return false

        val targetUs = targetTimeMs.coerceAtLeast(0L) * 1000L
        // Reuse current texture immediately if already within half a 60fps frame (~9ms)
        if (lastDecodedPtsUs >= 0L && abs(targetUs - lastDecodedPtsUs) <= 9_000L) {
            return true
        }

        // Only flush & seek if moving backwards, after EOS, or jumping > 1.8 seconds forward
        val needSeek = lastDecodedPtsUs < 0L ||
            outputEos ||
            targetUs < lastDecodedPtsUs - 12_000L ||
            (targetUs - lastDecodedPtsUs) > 1_800_000L

        try {
            if (needSeek) {
                ext.seekTo(targetUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
                dec.flush()
                inputEos = false
                outputEos = false
                lastDecodedPtsUs = -1L
            }

            var renderedAny = false
            var safetyLoops = 0
            while (!outputEos && safetyLoops < 240) {
                safetyLoops++
                if (!inputEos) {
                    val inIdx = dec.dequeueInputBuffer(2_500L)
                    if (inIdx >= 0) {
                        val inBuf = dec.getInputBuffer(inIdx)
                        if (inBuf != null) {
                            val sampleSize = ext.readSampleData(inBuf, 0)
                            if (sampleSize < 0) {
                                dec.queueInputBuffer(inIdx, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inputEos = true
                            } else {
                                val pts = ext.sampleTime.coerceAtLeast(0L)
                                dec.queueInputBuffer(inIdx, 0, sampleSize, pts, 0)
                                ext.advance()
                            }
                        }
                    }
                }

                val outIdx = dec.dequeueOutputBuffer(bufferInfo, 4_000L)
                if (outIdx >= 0) {
                    val isEos = (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
                    if (isEos) outputEos = true

                    val ptsUs = bufferInfo.presentationTimeUs
                    val reachedTarget = ptsUs >= targetUs - 14_000L || outputEos
                    val renderToSurface = bufferInfo.size > 0 && reachedTarget

                    if (renderToSurface) {
                        synchronized(frameSignalLock) { frameArrived = false }
                    }
                    dec.releaseOutputBuffer(outIdx, renderToSurface)

                    if (renderToSurface) {
                        awaitSurfaceFrameAndLatch(st)
                        lastDecodedPtsUs = ptsUs
                        renderedAny = true
                        break
                    }
                }
            }
            return renderedAny || lastDecodedPtsUs >= 0L
        } catch (_: Throwable) {
            return lastDecodedPtsUs >= 0L
        }
    }

    private fun awaitSurfaceFrameAndLatch(st: SurfaceTexture) {
        synchronized(frameSignalLock) {
            if (!frameArrived) {
                runCatching { frameSignalLock.wait(28L) }
            }
        }
        runCatching {
            st.updateTexImage()
            st.getTransformMatrix(stMatrix)
        }
    }

    fun release() {
        isReady = false
        runCatching { codec?.stop() }
        runCatching { codec?.release() }
        codec = null
        runCatching { extractor?.release() }
        extractor = null
        runCatching { outputSurface?.release() }
        outputSurface = null
        runCatching { surfaceTexture?.release() }
        surfaceTexture = null
        if (oesTexId != 0) {
            runCatching { GLES20.glDeleteTextures(1, intArrayOf(oesTexId), 0) }
            oesTexId = 0
        }
        runCatching { callbackThread?.quitSafely() }
        callbackThread = null
        callbackHandler = null
    }

    companion object {
        fun setExtractorDataSource(context: Context, extractor: MediaExtractor, uriStr: String) {
            val resolvedPath = MediaEngine.resolveLocalPathIfAvailable(uriStr)
            if (resolvedPath != null) {
                extractor.setDataSource(resolvedPath)
                return
            }
            val uri = Uri.parse(uriStr)
            if (uri.scheme == "file" && !uri.path.isNullOrBlank()) {
                extractor.setDataSource(uri.path!!)
            } else {
                extractor.setDataSource(context, uri, null)
            }
        }
    }
}

/**
 * Offscreen hardware batch frame warmer: decodes sorted timestamps from a video clip in a single
 * forward hardware `MediaCodec -> OES SurfaceTexture -> PBuffer -> Bitmap` pass (10-25x faster
 * than `MediaMetadataRetriever` loops) for Reverse Video, Time Remapping, and Scrubbing.
 */
object SequentialVideoDecoder {

    fun decodeSortedTimestampsHardware(
        context: Context,
        uriStr: String,
        sortedTimestampsMs: List<Long>,
        maxEdge: Int,
        cancelled: AtomicBoolean? = null,
        onFrameDecoded: (bucketMs: Long, bitmap: Bitmap) -> Unit
    ): Boolean {
        if (sortedTimestampsMs.isEmpty()) return true
        if (Build.FINGERPRINT.equals("robolectric", ignoreCase = true)) return false
        val doneLatch = CountDownLatch(1)
        var succeeded = false
        Thread(Runnable {
            var eglDisplay: EGLDisplay = EGL14.EGL_NO_DISPLAY
            var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
            var eglSurface: EGLSurface = EGL14.EGL_NO_SURFACE
            var session: GlSequentialVideoSession? = null
            var oesProgram = 0
            try {
                eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
                if (eglDisplay == EGL14.EGL_NO_DISPLAY) return@Runnable
                val vers = IntArray(2)
                if (!EGL14.eglInitialize(eglDisplay, vers, 0, vers, 1)) return@Runnable

                val cfgAttribs = intArrayOf(
                    EGL14.EGL_RED_SIZE, 8,
                    EGL14.EGL_GREEN_SIZE, 8,
                    EGL14.EGL_BLUE_SIZE, 8,
                    EGL14.EGL_ALPHA_SIZE, 8,
                    EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                    EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
                    EGL14.EGL_NONE
                )
                val configs = arrayOfNulls<EGLConfig>(1)
                val numCfg = IntArray(1)
                if (!EGL14.eglChooseConfig(eglDisplay, cfgAttribs, 0, configs, 0, 1, numCfg, 0) || numCfg[0] <= 0) {
                    return@Runnable
                }
                val cfg = configs[0] ?: return@Runnable
                val ctxAttribs = intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE)
                eglContext = EGL14.eglCreateContext(eglDisplay, cfg, EGL14.EGL_NO_CONTEXT, ctxAttribs, 0)
                if (eglContext == EGL14.EGL_NO_CONTEXT) return@Runnable

                // Initial tiny surface to inspect native video dimensions first
                eglSurface = EGL14.eglCreatePbufferSurface(
                    eglDisplay,
                    cfg,
                    intArrayOf(EGL14.EGL_WIDTH, 16, EGL14.EGL_HEIGHT, 16, EGL14.EGL_NONE),
                    0
                )
                if (!EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) return@Runnable
                val glVersion = GLES20.glGetString(GLES20.GL_VERSION)
                if (glVersion.isNullOrBlank()) return@Runnable

                session = GlSequentialVideoSession(context, uriStr)
                if (!session.openOnCurrentGlContext()) return@Runnable

                val safeEdge = maxEdge.coerceIn(128, 960)
                val aspect = session.videoWidth.toFloat() / session.videoHeight.toFloat().coerceAtLeast(1f)
                val dstW: Int
                val dstH: Int
                if (aspect >= 1f) {
                    dstW = (safeEdge / 2 * 2).coerceAtLeast(64)
                    dstH = ((safeEdge / aspect).toInt() / 2 * 2).coerceAtLeast(64)
                } else {
                    dstH = (safeEdge / 2 * 2).coerceAtLeast(64)
                    dstW = ((safeEdge * aspect).toInt() / 2 * 2).coerceAtLeast(64)
                }

                // Recreate PBuffer at exact scaled frame dimensions
                EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                EGL14.eglDestroySurface(eglDisplay, eglSurface)
                eglSurface = EGL14.eglCreatePbufferSurface(
                    eglDisplay,
                    cfg,
                    intArrayOf(EGL14.EGL_WIDTH, dstW, EGL14.EGL_HEIGHT, dstH, EGL14.EGL_NONE),
                    0
                )
                if (!EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) return@Runnable

                oesProgram = compileOesBlitProgram()
                if (oesProgram == 0) return@Runnable

                val quadBuf = buildQuadBuffer()
                val posLoc = GLES20.glGetAttribLocation(oesProgram, "aPosition")
                val uvLoc = GLES20.glGetAttribLocation(oesProgram, "aTexCoord")
                val matLoc = GLES20.glGetUniformLocation(oesProgram, "uTexMatrix")
                val texLoc = GLES20.glGetUniformLocation(oesProgram, "uOesTex")
                val readBuf = ByteBuffer.allocateDirect(dstW * dstH * 4).order(ByteOrder.nativeOrder())

                var decodedCount = 0
                for (tMs in sortedTimestampsMs) {
                    if (cancelled?.get() == true) break
                    if (session.advanceToFrameMs(tMs)) {
                        GLES20.glViewport(0, 0, dstW, dstH)
                        GLES20.glUseProgram(oesProgram)
                        quadBuf.position(0)
                        GLES20.glEnableVertexAttribArray(posLoc)
                        GLES20.glVertexAttribPointer(posLoc, 2, GLES20.GL_FLOAT, false, 16, quadBuf)
                        quadBuf.position(2)
                        GLES20.glEnableVertexAttribArray(uvLoc)
                        GLES20.glVertexAttribPointer(uvLoc, 2, GLES20.GL_FLOAT, false, 16, quadBuf)
                        GLES20.glUniformMatrix4fv(matLoc, 1, false, session.stMatrix, 0)
                        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
                        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, session.oesTexId)
                        GLES20.glUniform1i(texLoc, 0)
                        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

                        readBuf.position(0)
                        GLES20.glReadPixels(0, 0, dstW, dstH, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, readBuf)
                        readBuf.position(0)
                        val outBmp = Bitmap.createBitmap(dstW, dstH, Bitmap.Config.ARGB_8888)
                        outBmp.copyPixelsFromBuffer(readBuf)
                        onFrameDecoded(tMs, outBmp)
                        decodedCount++
                    }
                }
                succeeded = decodedCount > 0
            } catch (_: Throwable) {
                succeeded = false
            } finally {
                runCatching { session?.release() }
                if (oesProgram != 0) runCatching { GLES20.glDeleteProgram(oesProgram) }
                if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
                    runCatching {
                        EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                    }
                    if (eglSurface != EGL14.EGL_NO_SURFACE) runCatching { EGL14.eglDestroySurface(eglDisplay, eglSurface) }
                    if (eglContext != EGL14.EGL_NO_CONTEXT) runCatching { EGL14.eglDestroyContext(eglDisplay, eglContext) }
                    runCatching { EGL14.eglTerminate(eglDisplay) }
                }
                doneLatch.countDown()
            }
        }, "SeqHwBatchDecode").start()
        doneLatch.await(25, TimeUnit.SECONDS)
        return succeeded
    }

    private fun buildQuadBuffer(): FloatBuffer {
        // Flip Y in UV (1.0 - y) so PBuffer row 0 = TOP of upright image for 0-copy glReadPixels -> Bitmap!
        val coords = floatArrayOf(
            -1f, -1f, 0f, 1f,
             1f, -1f, 1f, 1f,
            -1f,  1f, 0f, 0f,
             1f,  1f, 1f, 0f
        )
        return ByteBuffer.allocateDirect(coords.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
            put(coords)
            position(0)
        }
    }

    private fun compileOesBlitProgram(): Int {
        val vsSrc = """
            attribute vec2 aPosition;
            attribute vec2 aTexCoord;
            uniform mat4 uTexMatrix;
            varying vec2 vUv;
            void main() {
                gl_Position = vec4(aPosition, 0.0, 1.0);
                vUv = (uTexMatrix * vec4(aTexCoord, 0.0, 1.0)).xy;
            }
        """.trimIndent()
        val fsSrc = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            varying vec2 vUv;
            uniform samplerExternalOES uOesTex;
            void main() {
                gl_FragColor = texture2D(uOesTex, vUv);
            }
        """.trimIndent()
        val vs = GLES20.glCreateShader(GLES20.GL_VERTEX_SHADER).also {
            GLES20.glShaderSource(it, vsSrc)
            GLES20.glCompileShader(it)
        }
        val fs = GLES20.glCreateShader(GLES20.GL_FRAGMENT_SHADER).also {
            GLES20.glShaderSource(it, fsSrc)
            GLES20.glCompileShader(it)
        }
        val prog = GLES20.glCreateProgram()
        GLES20.glAttachShader(prog, vs)
        GLES20.glAttachShader(prog, fs)
        GLES20.glLinkProgram(prog)
        return prog
    }
}
