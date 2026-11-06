package com.example.data.preferences

import android.content.Context
import android.content.SharedPreferences
import android.media.MediaCodecList
import android.media.MediaFormat
import com.example.data.model.AspectRatioPreset
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Resolution & shader multi-sample tiers for real-time OpenGL ES 2.0 + Video Decode + Pre-render Cache.
 */
enum class PreviewQuality(
    val title: String,
    val badge: String,
    val scaleFactor: Float,
    val maxGlDimension: Int,
    val maxVideoEdge: Int,
    val shaderDetailLevel: Int
) {
    FULL(
        title = "Full",
        badge = "Full",
        scaleFactor = 1.0f,
        maxGlDimension = 1080,
        maxVideoEdge = 960,
        shaderDetailLevel = 2
    ),
    HALF(
        title = "Half",
        badge = "1/2",
        scaleFactor = 0.5f,
        maxGlDimension = 640,
        maxVideoEdge = 540,
        shaderDetailLevel = 2
    ),
    QUARTER(
        title = "Quarter",
        badge = "1/4",
        scaleFactor = 0.25f,
        maxGlDimension = 360,
        maxVideoEdge = 320,
        shaderDetailLevel = 1
    ),
    EIGHTH(
        title = "Low",
        badge = "1/8",
        scaleFactor = 0.125f,
        maxGlDimension = 200,
        maxVideoEdge = 192,
        shaderDetailLevel = 0
    )
}

enum class BitrateProfile(
    val label: String,
    val qualityTitle: String,
    val targetMbps: Int,
    val bppFactor: Float
) {
    LOW_6M("6 Mbps", "Low", 6, 0.06f),
    ECONOMY_8M("8 Mbps", "Low+", 8, 0.08f),
    STANDARD_16M("16 Mbps", "Medium", 16, 0.14f),
    HIGH_32M("32 Mbps", "High", 32, 0.24f),
    MASTER_50M("50 Mbps", "Ultra", 50, 0.36f),
    CUSTOM("Custom", "Custom", 24, 0.20f)
}

data class HardwareCodecCapabilities(
    val hasHardwareAvcEncoder: Boolean = true,
    val hasHardwareHevcEncoder: Boolean = false,
    val hasHardwareAvcDecoder: Boolean = true,
    val hasHardwareHevcDecoder: Boolean = false,
    val primaryAvcEncoderName: String = "Hardware AVC",
    val primaryHevcEncoderName: String = "Software / Fallback"
)

data class StudioPreferences(
    // Preview & Playback Cache Optimizations
    val defaultPreviewQuality: PreviewQuality = PreviewQuality.HALF,
    val playbackCacheLimitMb: Int = 192,
    val useCustomRamCache: Boolean = false,
    val customRamCacheMb: Int = 256,
    val autoCachePlaybackFrames: Boolean = true,
    val fastKeyframeScrub: Boolean = false,
    val previewFpsCap: Int = 60,
    val showPerformanceHud: Boolean = false,
    val decoderPoolSize: Int = 4,
    val preRenderThreads: Int = 4,
    val preRenderScalePercent: Int = 80,
    val lowLatencyAudioSync: Boolean = true,

    // Video Codec & Hardware Pipeline Optimizations
    val preferredVideoCodec: String = "H.264", // "H.264" or "H.265"
    val hardwareAcceleratedCodecs: Boolean = true,
    val exportBitrateProfile: BitrateProfile = BitrateProfile.HIGH_32M,
    val customExportMbps: Int = 24,
    val keyframeIntervalSec: Int = 1,
    val exportAudioSampleRateHz: Int = 48000,

    // Audio & Timeline Workflow
    val defaultLoopPlayback: Boolean = true,
    val audioSyncThresholdMs: Int = 55,
    val muteAudioDuringFastScrub: Boolean = true,
    val snapToBookmarksAndKeys: Boolean = true,

    // New Project Defaults & UI Motion
    val defaultAspectPreset: AspectRatioPreset = AspectRatioPreset.LANDSCAPE_16_9,
    val defaultProjectFps: Int = 60,
    val defaultProjectDurationSec: Int = 9,
    val defaultCanvasBackground: String = "Black",
    val smoothUiAnimations: Boolean = true
) {
    val effectiveRamCacheMb: Int
        get() = if (useCustomRamCache) customRamCacheMb.coerceIn(32, 2048) else playbackCacheLimitMb.coerceIn(32, 2048)

    val effectiveExportMbps: Int
        get() = if (exportBitrateProfile == BitrateProfile.CUSTOM) {
            customExportMbps.coerceIn(1, 150)
        } else {
            exportBitrateProfile.targetMbps
        }
}

class AppPreferencesRepository(context: Context? = null) {

    private val prefs: SharedPreferences? = context?.applicationContext
        ?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _preferences = MutableStateFlow(loadFromDisk())
    val preferences: StateFlow<StudioPreferences> = _preferences.asStateFlow()

    val codecCapabilities: HardwareCodecCapabilities by lazy {
        probeDeviceHardwareCodecs()
    }

    fun update(transform: (StudioPreferences) -> StudioPreferences) {
        _preferences.update { current ->
            val updated = transform(current)
            persistToDisk(updated)
            updated
        }
    }

    fun resetToDefaults() {
        val defaults = StudioPreferences()
        prefs?.edit()?.clear()?.apply()
        _preferences.value = defaults
    }

    private fun loadFromDisk(): StudioPreferences {
        val p = prefs ?: return StudioPreferences()
        val qualityName = p.getString(KEY_PREVIEW_QUALITY, PreviewQuality.HALF.name) ?: PreviewQuality.HALF.name
        val quality = runCatching { PreviewQuality.valueOf(qualityName) }.getOrDefault(PreviewQuality.HALF)

        val bitrateName = p.getString(KEY_BITRATE_PROFILE, BitrateProfile.STANDARD_16M.name)
            ?: BitrateProfile.STANDARD_16M.name
        val bitrate = runCatching { BitrateProfile.valueOf(bitrateName) }.getOrDefault(BitrateProfile.STANDARD_16M)

        val aspectName = p.getString(KEY_DEFAULT_ASPECT, AspectRatioPreset.LANDSCAPE_16_9.name)
            ?: AspectRatioPreset.LANDSCAPE_16_9.name
        val aspect = runCatching { AspectRatioPreset.valueOf(aspectName) }
            .getOrDefault(AspectRatioPreset.LANDSCAPE_16_9)

        return StudioPreferences(
            defaultPreviewQuality = quality,
            playbackCacheLimitMb = p.getInt(KEY_CACHE_LIMIT_MB, 192).coerceIn(32, 2048),
            useCustomRamCache = p.getBoolean(KEY_USE_CUSTOM_RAM_CACHE, false),
            customRamCacheMb = p.getInt(KEY_CUSTOM_RAM_CACHE_MB, 256).coerceIn(32, 2048),
            autoCachePlaybackFrames = p.getBoolean(KEY_AUTO_CACHE_FRAMES, true),
            fastKeyframeScrub = p.getBoolean(KEY_FAST_SCRUB, false),
            previewFpsCap = p.getInt(KEY_FPS_CAP, 60).coerceIn(24, 120),
            showPerformanceHud = p.getBoolean(KEY_SHOW_HUD, false),
            decoderPoolSize = p.getInt(KEY_DECODER_POOL, 4).coerceIn(2, 8),
            preRenderThreads = p.getInt(KEY_PRERENDER_THREADS, 4).coerceIn(1, 8),
            preRenderScalePercent = p.getInt(KEY_PRERENDER_SCALE, 80).coerceIn(35, 100),
            lowLatencyAudioSync = p.getBoolean(KEY_LOW_LATENCY_AUDIO, true),
            preferredVideoCodec = if (p.getString(KEY_CODEC, "H.264") == "H.265") "H.265" else "H.264",
            hardwareAcceleratedCodecs = p.getBoolean(KEY_HW_ACCEL, true),
            exportBitrateProfile = bitrate,
            customExportMbps = p.getInt(KEY_CUSTOM_EXPORT_MBPS, 24).coerceIn(1, 150),
            keyframeIntervalSec = p.getInt(KEY_GOP_SEC, 1).coerceIn(1, 5),
            exportAudioSampleRateHz = p.getInt(KEY_AUDIO_SAMPLE_RATE, 48000).let { if (it == 44100) 44100 else 48000 },
            defaultLoopPlayback = p.getBoolean(KEY_DEFAULT_LOOP, true),
            audioSyncThresholdMs = p.getInt(KEY_AUDIO_SYNC_MS, 55).coerceIn(25, 320),
            muteAudioDuringFastScrub = p.getBoolean(KEY_MUTE_SCRUB, true),
            snapToBookmarksAndKeys = p.getBoolean(KEY_SNAP_TIMELINE, true),
            defaultAspectPreset = aspect,
            defaultProjectFps = p.getInt(KEY_DEFAULT_FPS, 60).coerceIn(12, 120),
            defaultProjectDurationSec = p.getInt(KEY_DEFAULT_DURATION, 9).coerceIn(2, 600),
            defaultCanvasBackground = p.getString(KEY_DEFAULT_BG, "Black") ?: "Black",
            smoothUiAnimations = p.getBoolean(KEY_SMOOTH_UI_ANIMATIONS, true)
        )
    }

    private fun persistToDisk(s: StudioPreferences) {
        val editor = prefs?.edit() ?: return
        editor
            .putString(KEY_PREVIEW_QUALITY, s.defaultPreviewQuality.name)
            .putInt(KEY_CACHE_LIMIT_MB, s.playbackCacheLimitMb)
            .putBoolean(KEY_USE_CUSTOM_RAM_CACHE, s.useCustomRamCache)
            .putInt(KEY_CUSTOM_RAM_CACHE_MB, s.customRamCacheMb)
            .putBoolean(KEY_AUTO_CACHE_FRAMES, s.autoCachePlaybackFrames)
            .putBoolean(KEY_FAST_SCRUB, s.fastKeyframeScrub)
            .putInt(KEY_FPS_CAP, s.previewFpsCap)
            .putBoolean(KEY_SHOW_HUD, s.showPerformanceHud)
            .putInt(KEY_DECODER_POOL, s.decoderPoolSize)
            .putInt(KEY_PRERENDER_THREADS, s.preRenderThreads)
            .putInt(KEY_PRERENDER_SCALE, s.preRenderScalePercent)
            .putBoolean(KEY_LOW_LATENCY_AUDIO, s.lowLatencyAudioSync)
            .putString(KEY_CODEC, s.preferredVideoCodec)
            .putBoolean(KEY_HW_ACCEL, s.hardwareAcceleratedCodecs)
            .putString(KEY_BITRATE_PROFILE, s.exportBitrateProfile.name)
            .putInt(KEY_CUSTOM_EXPORT_MBPS, s.customExportMbps)
            .putInt(KEY_GOP_SEC, s.keyframeIntervalSec)
            .putInt(KEY_AUDIO_SAMPLE_RATE, s.exportAudioSampleRateHz)
            .putBoolean(KEY_DEFAULT_LOOP, s.defaultLoopPlayback)
            .putInt(KEY_AUDIO_SYNC_MS, s.audioSyncThresholdMs)
            .putBoolean(KEY_MUTE_SCRUB, s.muteAudioDuringFastScrub)
            .putBoolean(KEY_SNAP_TIMELINE, s.snapToBookmarksAndKeys)
            .putString(KEY_DEFAULT_ASPECT, s.defaultAspectPreset.name)
            .putInt(KEY_DEFAULT_FPS, s.defaultProjectFps)
            .putInt(KEY_DEFAULT_DURATION, s.defaultProjectDurationSec)
            .putString(KEY_DEFAULT_BG, s.defaultCanvasBackground)
            .putBoolean(KEY_SMOOTH_UI_ANIMATIONS, s.smoothUiAnimations)
            .apply()
    }

    companion object {
        private const val PREFS_NAME = "frame_pollution_studio_prefs"
        private const val KEY_SMOOTH_UI_ANIMATIONS = "smooth_ui_animations"
        private const val KEY_PREVIEW_QUALITY = "preview_quality"
        private const val KEY_CACHE_LIMIT_MB = "cache_limit_mb"
        private const val KEY_USE_CUSTOM_RAM_CACHE = "use_custom_ram_cache"
        private const val KEY_CUSTOM_RAM_CACHE_MB = "custom_ram_cache_mb"
        private const val KEY_AUTO_CACHE_FRAMES = "auto_cache_frames"
        private const val KEY_FAST_SCRUB = "fast_scrub"
        private const val KEY_FPS_CAP = "fps_cap"
        private const val KEY_SHOW_HUD = "show_hud"
        private const val KEY_DECODER_POOL = "decoder_pool_size"
        private const val KEY_PRERENDER_THREADS = "prerender_threads"
        private const val KEY_PRERENDER_SCALE = "prerender_scale_pct"
        private const val KEY_LOW_LATENCY_AUDIO = "low_latency_audio_sync"
        private const val KEY_CODEC = "preferred_codec"
        private const val KEY_HW_ACCEL = "hw_accel"
        private const val KEY_BITRATE_PROFILE = "bitrate_profile"
        private const val KEY_CUSTOM_EXPORT_MBPS = "custom_export_mbps"
        private const val KEY_GOP_SEC = "gop_sec"
        private const val KEY_AUDIO_SAMPLE_RATE = "audio_sample_rate"
        private const val KEY_DEFAULT_LOOP = "default_loop"
        private const val KEY_AUDIO_SYNC_MS = "audio_sync_ms"
        private const val KEY_MUTE_SCRUB = "mute_scrub"
        private const val KEY_SNAP_TIMELINE = "snap_timeline"
        private const val KEY_DEFAULT_ASPECT = "default_aspect"
        private const val KEY_DEFAULT_FPS = "default_fps"
        private const val KEY_DEFAULT_DURATION = "default_duration"
        private const val KEY_DEFAULT_BG = "default_bg"

        fun probeDeviceHardwareCodecs(): HardwareCodecCapabilities {
            return runCatching {
                val infos = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
                var hwAvcEnc: String? = null
                var hwHevcEnc: String? = null
                var hwAvcDec = false
                var hwHevcDec = false

                for (info in infos) {
                    val lower = info.name.lowercase()
                    val isSoftware = lower.startsWith("omx.google.") ||
                        lower.startsWith("c2.android.") ||
                        lower.contains("sw")
                    val types = info.supportedTypes.map { it.lowercase() }
                    val supportsAvc = types.contains(MediaFormat.MIMETYPE_VIDEO_AVC.lowercase())
                    val supportsHevc = types.contains(MediaFormat.MIMETYPE_VIDEO_HEVC.lowercase())

                    if (info.isEncoder) {
                        if (supportsAvc && !isSoftware && hwAvcEnc == null) hwAvcEnc = info.name
                        if (supportsHevc && !isSoftware && hwHevcEnc == null) hwHevcEnc = info.name
                    } else {
                        if (supportsAvc && !isSoftware) hwAvcDec = true
                        if (supportsHevc && !isSoftware) hwHevcDec = true
                    }
                }
                HardwareCodecCapabilities(
                    hasHardwareAvcEncoder = hwAvcEnc != null,
                    hasHardwareHevcEncoder = hwHevcEnc != null,
                    hasHardwareAvcDecoder = hwAvcDec,
                    hasHardwareHevcDecoder = hwHevcDec,
                    primaryAvcEncoderName = hwAvcEnc ?: "c2.android.avc.encoder",
                    primaryHevcEncoderName = hwHevcEnc ?: "c2.android.hevc.encoder"
                )
            }.getOrDefault(HardwareCodecCapabilities())
        }
    }
}
