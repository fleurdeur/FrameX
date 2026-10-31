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
        badge = "1/1",
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
        title = "Low (Draft)",
        badge = "1/8",
        scaleFactor = 0.125f,
        maxGlDimension = 200,
        maxVideoEdge = 192,
        shaderDetailLevel = 0
    )
}

enum class BitrateProfile(
    val label: String,
    val targetMbps: Int,
    val bppFactor: Float
) {
    ECONOMY_8M("Economy (8 Mbps)", 8, 0.08f),
    STANDARD_16M("Standard (16 Mbps)", 16, 0.14f),
    HIGH_32M("High Quality (32 Mbps)", 32, 0.24f),
    MASTER_50M("Studio Master (50 Mbps)", 50, 0.36f)
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
    val autoCachePlaybackFrames: Boolean = true,
    val fastKeyframeScrub: Boolean = true,
    val previewFpsCap: Int = 60,
    val showPerformanceHud: Boolean = false,

    // Video Codec & Hardware Pipeline Optimizations
    val preferredVideoCodec: String = "H.264", // "H.264" or "H.265"
    val hardwareAcceleratedCodecs: Boolean = true,
    val exportBitrateProfile: BitrateProfile = BitrateProfile.STANDARD_16M,
    val keyframeIntervalSec: Int = 1,
    val exportAudioSampleRateHz: Int = 48000,

    // Audio & Timeline Workflow
    val defaultLoopPlayback: Boolean = true,
    val audioSyncThresholdMs: Int = 160,
    val muteAudioDuringFastScrub: Boolean = true,
    val snapToBookmarksAndKeys: Boolean = true,

    // New Project Defaults
    val defaultAspectPreset: AspectRatioPreset = AspectRatioPreset.LANDSCAPE_16_9,
    val defaultProjectFps: Int = 60,
    val defaultProjectDurationSec: Int = 10,
    val defaultCanvasBackground: String = "Black"
)

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
            playbackCacheLimitMb = p.getInt(KEY_CACHE_LIMIT_MB, 192).coerceIn(64, 512),
            autoCachePlaybackFrames = p.getBoolean(KEY_AUTO_CACHE_FRAMES, true),
            fastKeyframeScrub = p.getBoolean(KEY_FAST_SCRUB, true),
            previewFpsCap = p.getInt(KEY_FPS_CAP, 60).let { if (it <= 30) 30 else 60 },
            showPerformanceHud = p.getBoolean(KEY_SHOW_HUD, false),
            preferredVideoCodec = if (p.getString(KEY_CODEC, "H.264") == "H.265") "H.265" else "H.264",
            hardwareAcceleratedCodecs = p.getBoolean(KEY_HW_ACCEL, true),
            exportBitrateProfile = bitrate,
            keyframeIntervalSec = p.getInt(KEY_GOP_SEC, 1).coerceIn(1, 5),
            exportAudioSampleRateHz = p.getInt(KEY_AUDIO_SAMPLE_RATE, 48000).let { if (it == 44100) 44100 else 48000 },
            defaultLoopPlayback = p.getBoolean(KEY_DEFAULT_LOOP, true),
            audioSyncThresholdMs = p.getInt(KEY_AUDIO_SYNC_MS, 160).coerceIn(80, 320),
            muteAudioDuringFastScrub = p.getBoolean(KEY_MUTE_SCRUB, true),
            snapToBookmarksAndKeys = p.getBoolean(KEY_SNAP_TIMELINE, true),
            defaultAspectPreset = aspect,
            defaultProjectFps = p.getInt(KEY_DEFAULT_FPS, 60).coerceIn(12, 120),
            defaultProjectDurationSec = p.getInt(KEY_DEFAULT_DURATION, 10).coerceIn(2, 180),
            defaultCanvasBackground = p.getString(KEY_DEFAULT_BG, "Black") ?: "Black"
        )
    }

    private fun persistToDisk(s: StudioPreferences) {
        val editor = prefs?.edit() ?: return
        editor
            .putString(KEY_PREVIEW_QUALITY, s.defaultPreviewQuality.name)
            .putInt(KEY_CACHE_LIMIT_MB, s.playbackCacheLimitMb)
            .putBoolean(KEY_AUTO_CACHE_FRAMES, s.autoCachePlaybackFrames)
            .putBoolean(KEY_FAST_SCRUB, s.fastKeyframeScrub)
            .putInt(KEY_FPS_CAP, s.previewFpsCap)
            .putBoolean(KEY_SHOW_HUD, s.showPerformanceHud)
            .putString(KEY_CODEC, s.preferredVideoCodec)
            .putBoolean(KEY_HW_ACCEL, s.hardwareAcceleratedCodecs)
            .putString(KEY_BITRATE_PROFILE, s.exportBitrateProfile.name)
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
            .apply()
    }

    companion object {
        private const val PREFS_NAME = "frame_pollution_studio_prefs"
        private const val KEY_PREVIEW_QUALITY = "preview_quality"
        private const val KEY_CACHE_LIMIT_MB = "cache_limit_mb"
        private const val KEY_AUTO_CACHE_FRAMES = "auto_cache_frames"
        private const val KEY_FAST_SCRUB = "fast_scrub"
        private const val KEY_FPS_CAP = "fps_cap"
        private const val KEY_SHOW_HUD = "show_hud"
        private const val KEY_CODEC = "preferred_codec"
        private const val KEY_HW_ACCEL = "hw_accel"
        private const val KEY_BITRATE_PROFILE = "bitrate_profile"
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
