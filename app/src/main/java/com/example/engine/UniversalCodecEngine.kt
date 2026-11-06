package com.example.engine

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build
import android.view.Surface
import androidx.annotation.OptIn
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.mediacodec.MediaCodecUtil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Universal Android Hardware + Software Codec Compatibility Engine:
 * - Bridges vendor differences across Qualcomm (`c2.qti.*` / `OMX.qcom.*`), Samsung Exynos
 *   (`c2.exynos.*` / `OMX.Exynos.*`), MediaTek (`c2.mtk.*` / `OMX.MTK.*`), Google Tensor,
 *   Unisoc, Hisilicon, and AOSP software codecs (`c2.android.*` / `OMX.google.*`).
 * - Provides automatic hardware-to-software decoder fallback for [androidx.media3.exoplayer.ExoPlayer],
 *   [GlSequentialVideoSession], [SequentialVideoDecoder], and audio mixing.
 * - Aligns export dimensions, frame rates, and bitrates to each phone encoder's
 *   [MediaCodecInfo.VideoCapabilities] (`widthAlignment`, `heightAlignment`, `supportedWidths`,
 *   `supportedHeights`, `bitrateRange`) with automatic `HEVC -> H.264 HW -> H.264 SW` fallback.
 */
object UniversalCodecEngine {

    data class ConfiguredVideoEncoder(
        val encoder: MediaCodec,
        val inputSurface: Surface,
        val alignedWidth: Int,
        val alignedHeight: Int,
        val mimeType: String,
        val codecName: String
    ) {
        val codecLabel: String
            get() = if (mimeType.equals(MimeTypes.VIDEO_H265, ignoreCase = true)) "H.265" else "H.264"
    }

    private val SOFTWARE_CODEC_PREFIXES = listOf(
        "c2.android.",
        "omx.google.",
        "omx.ffmpeg.",
        "c2.goldfish."
    )

    fun isSoftwareCodecName(name: String): Boolean {
        val lower = name.lowercase()
        return SOFTWARE_CODEC_PREFIXES.any { lower.startsWith(it) } || lower.contains(".sw.")
    }

    /**
     * Builds a Media3 [MediaCodecSelector] that ranks capable hardware decoders first and
     * keeps software decoders (`c2.android.*`, `OMX.google.*`) at the tail of the fallback chain
     * so `DefaultRenderersFactory.setEnableDecoderFallback(true)` recovers automatically whenever
     * a phone's primary hardware decoder runs out of instances or rejects high profiles/resolutions.
     */
    @OptIn(UnstableApi::class)
    fun buildUniversalMediaCodecSelector(): MediaCodecSelector {
        return MediaCodecSelector { mimeType, requiresSecureDecoder, requiresTunnelingDecoder ->
            val defaultList = try {
                MediaCodecUtil.getDecoderInfos(
                    mimeType,
                    requiresSecureDecoder,
                    requiresTunnelingDecoder
                )
            } catch (_: Throwable) {
                emptyList()
            }
            if (defaultList.size <= 1) {
                defaultList
            } else {
                // Stable partition: hardware codecs first, software fallback codecs right behind them
                val (hw, sw) = defaultList.partition { info ->
                    !isSoftwareCodecName(info.name)
                }
                hw + sw
            }
        }
    }

    /**
     * Opens and starts a compatible video [MediaCodec] decoder for [trackFormat], trying:
     * 1. Capable hardware decoders matching resolution/profile
     * 2. Remaining hardware decoders with sanitized [MediaFormat] (stripping strict profile/level tags)
     * 3. Universal AOSP software decoders (`c2.android.*` / `OMX.google.*`)
     * 4. `MediaCodec.createDecoderByType(mime)` as last-resort platform default
     */
    fun openCompatibleVideoDecoder(
        trackFormat: MediaFormat,
        outputSurface: Surface?
    ): MediaCodec? {
        val mime = trackFormat.getString(MediaFormat.KEY_MIME)?.takeIf { it.isNotBlank() }
            ?: return null
        val width = trackFormat.getIntOrDefault(MediaFormat.KEY_WIDTH, 1920).coerceAtLeast(16)
        val height = trackFormat.getIntOrDefault(MediaFormat.KEY_HEIGHT, 1080).coerceAtLeast(16)

        val candidates = queryDecoderCandidates(mime, width, height, isVideo = true)
        val sanitizedFormat = buildSanitizedDecoderFormat(trackFormat)

        for (codecName in candidates) {
            // Try with original track format first, then with stripped profile/level format
            for (fmt in listOf(trackFormat, sanitizedFormat)) {
                var codec: MediaCodec? = null
                try {
                    codec = MediaCodec.createByCodecName(codecName)
                    codec.configure(fmt, outputSurface, null, 0)
                    codec.start()
                    return codec
                } catch (_: Throwable) {
                    runCatching { codec?.release() }
                }
            }
        }

        // Final fallback via generic MIME lookup
        for (fmt in listOf(sanitizedFormat, trackFormat)) {
            var codec: MediaCodec? = null
            try {
                codec = MediaCodec.createDecoderByType(mime)
                codec.configure(fmt, outputSurface, null, 0)
                codec.start()
                return codec
            } catch (_: Throwable) {
                runCatching { codec?.release() }
            }
        }
        return null
    }

    /**
     * Opens and starts a compatible audio [MediaCodec] decoder across AAC, MP3, Opus, Vorbis, FLAC, etc.
     */
    fun openCompatibleAudioDecoder(trackFormat: MediaFormat): MediaCodec? {
        val mime = trackFormat.getString(MediaFormat.KEY_MIME)?.takeIf { it.isNotBlank() }
            ?: return null
        val candidates = queryDecoderCandidates(mime, 0, 0, isVideo = false)
        val sanitized = buildSanitizedDecoderFormat(trackFormat)

        for (codecName in candidates) {
            for (fmt in listOf(trackFormat, sanitized)) {
                var codec: MediaCodec? = null
                try {
                    codec = MediaCodec.createByCodecName(codecName)
                    codec.configure(fmt, null, null, 0)
                    codec.start()
                    return codec
                } catch (_: Throwable) {
                    runCatching { codec?.release() }
                }
            }
        }

        var fallback: MediaCodec? = null
        return try {
            fallback = MediaCodec.createDecoderByType(mime)
            fallback.configure(trackFormat, null, null, 0)
            fallback.start()
            fallback
        } catch (_: Throwable) {
            runCatching { fallback?.release() }
            null
        }
    }

    /**
     * Selects, aligns, and configures a Surface-input video encoder guaranteed to work on the current device:
     * - Aligns width/height to hardware macroblock alignment (`widthAlignment` / `heightAlignment`, e.g. 2/8/16px)
     * - Scales down proportionally if requested dimensions exceed the device encoder's max supported size (e.g. 4K on 1080p SoCs)
     * - Clamps FPS and bitrate into supported codec ranges
     * - Automatically falls back `HEVC HW -> H.264 HW -> HEVC SW -> H.264 SW`
     */
    fun selectAndConfigureVideoEncoder(
        preferredCodec: String,
        requestedWidth: Int,
        requestedHeight: Int,
        requestedFps: Int,
        requestedBitrate: Int,
        gopSeconds: Int = 1
    ): ConfiguredVideoEncoder {
        val safeGop = gopSeconds.coerceIn(1, 5)
        val mimesToTry = if (preferredCodec.equals("H.265", ignoreCase = true) ||
            preferredCodec.equals("HEVC", ignoreCase = true)
        ) {
            listOf(MimeTypes.VIDEO_H265, MimeTypes.VIDEO_H264)
        } else {
            listOf(MimeTypes.VIDEO_H264)
        }

        val codecList = runCatching {
            MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.toList()
        }.getOrDefault(emptyList())

        var lastError: Throwable? = null

        for (mime in mimesToTry) {
            val matchingEncoders = codecList
                .filter { info ->
                    info.isEncoder && info.supportedTypes.any { it.equals(mime, ignoreCase = true) } &&
                        supportsSurfaceInput(info, mime)
                }
                .sortedBy { info -> if (isSoftwareCodecName(info.name)) 1 else 0 }

            for (info in matchingEncoders) {
                val configured = tryConfigureEncoderInfo(
                    info = info,
                    mime = mime,
                    reqW = requestedWidth,
                    reqH = requestedHeight,
                    reqFps = requestedFps,
                    reqBitrate = requestedBitrate,
                    gopSec = safeGop
                )
                if (configured != null) return configured
            }

            // Generic platform fallback if codec list enumeration had restrictive capability metadata
            for ((fallbackW, fallbackH) in buildFallbackResolutions(requestedWidth, requestedHeight)) {
                var enc: MediaCodec? = null
                var surf: Surface? = null
                try {
                    val safeFps = requestedFps.coerceIn(15, 60)
                    val fmt = MediaFormat.createVideoFormat(mime, fallbackW, fallbackH).apply {
                        setInteger(
                            MediaFormat.KEY_COLOR_FORMAT,
                            MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface
                        )
                        setInteger(MediaFormat.KEY_BIT_RATE, requestedBitrate.coerceIn(1_000_000, 35_000_000))
                        setInteger(MediaFormat.KEY_FRAME_RATE, safeFps)
                        setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, safeGop)
                    }
                    enc = MediaCodec.createEncoderByType(mime)
                    enc.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                    surf = enc.createInputSurface()
                    enc.start()
                    return ConfiguredVideoEncoder(
                        encoder = enc,
                        inputSurface = surf,
                        alignedWidth = fallbackW,
                        alignedHeight = fallbackH,
                        mimeType = mime,
                        codecName = enc.name
                    )
                } catch (t: Throwable) {
                    lastError = t
                    runCatching { surf?.release() }
                    runCatching { enc?.release() }
                }
            }
        }

        throw IllegalStateException(
            "No compatible video encoder available on this device: ${lastError?.localizedMessage ?: "unknown codec error"}",
            lastError
        )
    }

    /**
     * Pure helper (also testable on JVM) that aligns and clamps requested dimensions to even/16-aligned bounds.
     */
    fun alignDimensions(
        requestedWidth: Int,
        requestedHeight: Int,
        widthAlignment: Int = 16,
        heightAlignment: Int = 16,
        maxWidth: Int = 3840,
        maxHeight: Int = 3840,
        minWidth: Int = 128,
        minHeight: Int = 128
    ): Pair<Int, Int> {
        val wAlign = max(2, widthAlignment)
        val hAlign = max(2, heightAlignment)
        var w = requestedWidth.coerceAtLeast(minWidth).toDouble()
        var h = requestedHeight.coerceAtLeast(minHeight).toDouble()

        val scaleDown = min(
            1.0,
            min(maxWidth.coerceAtLeast(minWidth) / w, maxHeight.coerceAtLeast(minHeight) / h)
        )
        w *= scaleDown
        h *= scaleDown

        val alignedW = (((w / wAlign).roundToInt() * wAlign).coerceIn(minWidth, maxWidth) / 2 * 2)
            .coerceAtLeast(wAlign)
        val alignedH = (((h / hAlign).roundToInt() * hAlign).coerceIn(minHeight, maxHeight) / 2 * 2)
            .coerceAtLeast(hAlign)
        return alignedW to alignedH
    }

    private fun tryConfigureEncoderInfo(
        info: MediaCodecInfo,
        mime: String,
        reqW: Int,
        reqH: Int,
        reqFps: Int,
        reqBitrate: Int,
        gopSec: Int = 1
    ): ConfiguredVideoEncoder? {
        val caps = runCatching { info.getCapabilitiesForType(mime)?.videoCapabilities }.getOrNull()
        val wAlign = max(2, caps?.widthAlignment ?: 16)
        val hAlign = max(2, caps?.heightAlignment ?: 16)
        val minW = caps?.supportedWidths?.lower ?: 128
        val maxW = caps?.supportedWidths?.upper ?: 3840
        val minH = caps?.supportedHeights?.lower ?: 128
        val maxH = caps?.supportedHeights?.upper ?: 3840

        val (primaryW, primaryH) = alignDimensions(reqW, reqH, wAlign, hAlign, maxW, maxH, minW, minH)
        // If portrait (e.g., 1080x1920) exceeds a landscape-oriented `supportedHeights` cap on older chipsets,
        // check `isSizeSupported` and scale into supported height range cleanly.
        val candidateSizes = LinkedHashSet<Pair<Int, Int>>()
        candidateSizes.add(primaryW to primaryH)
        if (caps != null && !runCatching { caps.isSizeSupported(primaryW, primaryH) }.getOrDefault(true)) {
            val safeHUpper = runCatching {
                caps.getSupportedHeightsFor(primaryW.coerceIn(minW, maxW)).upper
            }.getOrDefault(min(maxH, 1088))
            candidateSizes.add(
                alignDimensions(reqW, reqH, wAlign, hAlign, min(maxW, 1920), min(safeHUpper, 1920), minW, minH)
            )
            candidateSizes.add(
                alignDimensions(reqW, reqH, 16, 16, min(maxW, 1280), min(maxH, 1280), minW, minH)
            )
        } else {
            candidateSizes.add(alignDimensions(reqW, reqH, 16, 16, min(maxW, 1920), min(maxH, 1920), minW, minH))
        }

        for ((w, h) in candidateSizes) {
            if (caps != null && !runCatching { caps.isSizeSupported(w, h) }.getOrDefault(true)) continue

            val fpsUpper = runCatching {
                caps?.getSupportedFrameRatesFor(w, h)?.upper?.toInt()
            }.getOrNull() ?: caps?.supportedFrameRates?.upper?.toInt() ?: 60
            val fpsLower = runCatching {
                caps?.getSupportedFrameRatesFor(w, h)?.lower?.toInt()
            }.getOrNull() ?: 12
            val clampedFps = reqFps.coerceIn(max(12, fpsLower), max(24, min(fpsUpper, 120)))

            val bitLower = caps?.bitrateRange?.lower ?: 500_000
            val bitUpper = caps?.bitrateRange?.upper ?: 50_000_000
            val clampedBitrate = reqBitrate.coerceIn(bitLower, max(bitLower, bitUpper))

            for (includeVbrMode in listOf(true, false)) {
                var enc: MediaCodec? = null
                var surf: Surface? = null
                try {
                    val format = MediaFormat.createVideoFormat(mime, w, h).apply {
                        setInteger(
                            MediaFormat.KEY_COLOR_FORMAT,
                            MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface
                        )
                        setInteger(MediaFormat.KEY_BIT_RATE, clampedBitrate)
                        setInteger(MediaFormat.KEY_FRAME_RATE, clampedFps)
                        setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, gopSec)
                        if (includeVbrMode) {
                            setInteger(
                                MediaFormat.KEY_BITRATE_MODE,
                                MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR
                            )
                        }
                    }
                    enc = MediaCodec.createByCodecName(info.name)
                    enc.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                    surf = enc.createInputSurface()
                    enc.start()
                    return ConfiguredVideoEncoder(
                        encoder = enc,
                        inputSurface = surf,
                        alignedWidth = w,
                        alignedHeight = h,
                        mimeType = mime,
                        codecName = info.name
                    )
                } catch (_: Throwable) {
                    runCatching { surf?.release() }
                    runCatching { enc?.release() }
                }
            }
        }
        return null
    }

    private fun supportsSurfaceInput(info: MediaCodecInfo, mime: String): Boolean {
        return runCatching {
            val caps = info.getCapabilitiesForType(mime)
            caps.colorFormats.any { it == MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface }
        }.getOrDefault(true)
    }

    private fun buildFallbackResolutions(reqW: Int, reqH: Int): List<Pair<Int, Int>> {
        return listOf(
            alignDimensions(reqW, reqH, 16, 16, 3840, 3840),
            alignDimensions(reqW, reqH, 16, 16, 1920, 1920),
            alignDimensions(reqW, reqH, 16, 16, 1280, 1280)
        ).distinct()
    }

    private fun queryDecoderCandidates(
        mime: String,
        width: Int,
        height: Int,
        isVideo: Boolean
    ): List<String> {
        val list = runCatching { MediaCodecList(MediaCodecList.REGULAR_CODECS) }.getOrNull()
            ?: return emptyList()
        val infos = runCatching { list.codecInfos.toList() }.getOrDefault(emptyList())
            .filter { info ->
                !info.isEncoder && info.supportedTypes.any { it.equals(mime, ignoreCase = true) }
            }

        if (infos.isEmpty()) return emptyList()

        val capableHw = ArrayList<String>()
        val otherHw = ArrayList<String>()
        val sw = ArrayList<String>()

        for (info in infos) {
            val name = info.name
            val isSw = isSoftwareCodecName(name) ||
                (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && runCatching { info.isSoftwareOnly }.getOrDefault(false))
            if (isSw) {
                sw.add(name)
            } else if (!isVideo) {
                capableHw.add(name)
            } else {
                val sizeSupported = runCatching {
                    val vCaps = info.getCapabilitiesForType(mime)?.videoCapabilities
                    vCaps == null || vCaps.isSizeSupported(width, height) || vCaps.isSizeSupported(height, width)
                }.getOrDefault(true)
                if (sizeSupported) {
                    capableHw.add(name)
                } else {
                    otherHw.add(name)
                }
            }
        }
        return (capableHw + otherHw + sw).distinct()
    }

    private fun buildSanitizedDecoderFormat(src: MediaFormat): MediaFormat {
        val mime = src.getString(MediaFormat.KEY_MIME).orEmpty()
        val copy = if (src.containsKey(MediaFormat.KEY_WIDTH) && src.containsKey(MediaFormat.KEY_HEIGHT)) {
            MediaFormat.createVideoFormat(
                mime,
                src.getInteger(MediaFormat.KEY_WIDTH),
                src.getInteger(MediaFormat.KEY_HEIGHT)
            )
        } else if (src.containsKey(MediaFormat.KEY_SAMPLE_RATE) && src.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
            MediaFormat.createAudioFormat(
                mime,
                src.getInteger(MediaFormat.KEY_SAMPLE_RATE),
                src.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            )
        } else {
            MediaFormat().apply { setString(MediaFormat.KEY_MIME, mime) }
        }

        // Preserve codec configuration buffers (SPS/PPS/VPS/AudioSpecificConfig) & rotation
        for (csdKey in listOf("csd-0", "csd-1", "csd-2")) {
            runCatching {
                if (src.containsKey(csdKey)) {
                    src.getByteBuffer(csdKey)?.let { buf ->
                        val dup = buf.duplicate()
                        copy.setByteBuffer(csdKey, dup)
                    }
                }
            }
        }
        if (src.containsKey(MediaFormat.KEY_ROTATION)) {
            runCatching { copy.setInteger(MediaFormat.KEY_ROTATION, src.getInteger(MediaFormat.KEY_ROTATION)) }
        }
        if (src.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) {
            runCatching { copy.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, src.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE)) }
        }
        return copy
    }

    private fun MediaFormat.getIntOrDefault(key: String, default: Int): Int {
        return runCatching { if (containsKey(key)) getInteger(key) else default }.getOrDefault(default)
    }
}
