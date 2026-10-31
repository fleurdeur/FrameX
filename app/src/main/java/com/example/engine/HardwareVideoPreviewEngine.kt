package com.example.engine

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Surface
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.SeekParameters
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import com.example.data.model.Layer
import com.example.data.model.LayerType
import com.example.data.model.Project
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.pow

/**
 * Zero-Copy Hardware Video & Embedded-Audio Preview Engine powered by Jetpack Media3 [ExoPlayer]:
 * - Streams hardware-decoded video frames directly into OpenGL `GL_TEXTURE_EXTERNAL_OES` surfaces
 *   (0 CPU Bitmap allocations, 0 `texImage2D` uploads, 0 `MediaMetadataRetriever` stalls).
 * - Unifies embedded video audio with video frame presentation on ExoPlayer's hardware MediaClock
 *   for 0ms lip-sync and zero seek-thrashing audio glitches.
 * - Uses `SeekParameters.CLOSEST_SYNC` during fast finger scrubbing and `SeekParameters.EXACT` on settle.
 */
@OptIn(UnstableApi::class)
object HardwareVideoPreviewEngine {

    private const val MAX_POOLED_VIDEO_PLAYERS = 4

    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    var syncDriftThresholdMs: Long = 220L

    class LayerPlayerSlot(
        val layerId: String,
        var preparedUri: String = ""
    ) {
        var player: ExoPlayer? = null
        var boundSurface: Surface? = null
        var boundRendererToken: Int = 0

        @Volatile var hasRenderedFirstFrame: Boolean = false
        @Volatile var videoWidth: Int = 0
        @Volatile var videoHeight: Int = 0
        @Volatile var isReady: Boolean = false
        @Volatile var isPlayingNow: Boolean = false

        val dspProcessor: StudioDspAudioProcessor = StudioDspAudioProcessor()
        @Volatile var lastKnownMediaPosMs: Long = 0L
        @Volatile var lastKnownPosNano: Long = 0L
        @Volatile var appliedSpeed: Float = 1.0f
        @Volatile var appliedPitch: Float = 1.0f
        @Volatile var appliedVolume: Float = -1f

        var lastSeekTargetMs: Long = -1L
        var lastSeekWallMs: Long = 0L
        var lastExactSeekApplied: Boolean = true
        var lastUsedWallMs: Long = SystemClock.elapsedRealtime()
    }

    private val slots = ConcurrentHashMap<String, LayerPlayerSlot>()

    fun isEligibleForLiveOesStream(layer: Layer): Boolean {
        return layer.visible &&
            layer.type == LayerType.VIDEO &&
            layer.mediaUri.isNotBlank() &&
            !layer.reversed &&
            !layer.timeRemapEnabled &&
            layer.freezeFrameMs < 0L
    }

    fun hasReadyVideoFrame(layerId: String): Boolean {
        return slots[layerId]?.hasRenderedFirstFrame == true
    }

    fun getSlotAspect(layerId: String): Float? {
        val slot = slots[layerId] ?: return null
        if (slot.videoWidth > 0 && slot.videoHeight > 0) {
            return slot.videoWidth.toFloat() / slot.videoHeight.toFloat()
        }
        return null
    }

    /**
     * Called from the GL thread when an OES [Surface] is created or rebound for a video layer.
     */
    fun bindLayerSurface(
        context: Context,
        rendererToken: Int,
        layerId: String,
        mediaUri: String,
        surface: Surface
    ) {
        val appCtx = context.applicationContext
        runOnMain {
            val slot = obtainOrCreateSlotOnMain(appCtx, layerId, mediaUri) ?: return@runOnMain
            slot.lastUsedWallMs = SystemClock.elapsedRealtime()
            if (slot.boundSurface !== surface) {
                slot.hasRenderedFirstFrame = false
                slot.boundSurface = surface
                slot.boundRendererToken = rendererToken
                runCatching { slot.player?.setVideoSurface(surface) }
            }
        }
    }

    /**
     * Pre-warms hardware decoders & demuxers as soon as a project opens or a video is imported,
     * eliminating cold-start latency when the user taps Play.
     */
    fun prewarmProjectVideos(context: Context, project: Project, playheadMs: Long) {
        val appCtx = context.applicationContext
        val eligible = project.layers
            .filter { isEligibleForLiveOesStream(it) }
            .take(MAX_POOLED_VIDEO_PLAYERS)
        if (eligible.isEmpty()) return

        runOnMain {
            evictInactiveLayersOnMain(project)
            eligible.forEach { layer ->
                val slot = obtainOrCreateSlotOnMain(appCtx, layer.id, layer.mediaUri) ?: return@forEach
                val p = slot.player ?: return@forEach
                if (!p.isPlaying && playheadMs in layer.startTimeMs..layer.endTimeMs) {
                    val targetMs = AnimationEvaluator.evaluateSourceMediaTimeMs(layer, playheadMs)
                    if (abs(slot.lastSeekTargetMs - targetMs) > 25L) {
                        p.setSeekParameters(SeekParameters.EXACT)
                        p.seekTo(targetMs)
                        slot.lastSeekTargetMs = targetMs
                        slot.lastKnownMediaPosMs = targetMs
                        slot.lastKnownPosNano = System.nanoTime()
                    }
                }
            }
        }
    }

    /**
     * Synchronizes active video players on Play, Pause, Loop wrap, Scrub, or periodic gentle drift check.
     * Never spams `seekTo` during continuous forward playback!
     */
    fun syncProjectVideos(
        context: Context,
        project: Project,
        playheadMs: Long,
        isPlaying: Boolean,
        rapidScrubbing: Boolean = MediaEngine.isRapidScrubbing,
        forceExactSeek: Boolean = false
    ) {
        val appCtx = context.applicationContext
        runOnMain {
            val nowWall = SystemClock.elapsedRealtime()
            val eligibleById = project.layers
                .filter { isEligibleForLiveOesStream(it) }
                .take(MAX_POOLED_VIDEO_PLAYERS)
                .associateBy { it.id }

            // Pause any pooled players that are no longer eligible or outside clip bounds
            slots.forEach { (id, slot) ->
                val layer = eligibleById[id]
                val inBounds = layer != null && playheadMs in layer.startTimeMs until layer.endTimeMs
                if (!isPlaying || !inBounds) {
                    slot.player?.let { p ->
                        if (p.playWhenReady || p.isPlaying) {
                            p.pause()
                        }
                        if (!isPlaying && slot.appliedVolume != 0f) {
                            p.volume = 0f
                            slot.appliedVolume = 0f
                        }
                        slot.isPlayingNow = false
                    }
                }
            }

            val driftLimit = max(syncDriftThresholdMs, 300L)

            eligibleById.values.forEach { layer ->
                val slot = obtainOrCreateSlotOnMain(appCtx, layer.id, layer.mediaUri) ?: return@forEach
                val p = slot.player ?: return@forEach
                slot.lastUsedWallMs = nowWall

                val clipActiveNow = playheadMs in layer.startTimeMs until layer.endTimeMs
                val nearClipPreview = !isPlaying && playheadMs in layer.startTimeMs..layer.endTimeMs

                if (isPlaying && clipActiveNow) {
                    slot.dspProcessor.updateFromLayer(layer)
                    val targetMs = AnimationEvaluator.evaluateSourceMediaTimeMs(layer, playheadMs)
                    val spd = layer.speedMultiplier.coerceIn(0.25f, 3.0f)
                    val pitchFactor = 2.0.pow(layer.audioPitchSemitones.coerceIn(-12f, 12f) / 12.0)
                        .toFloat()
                        .coerceIn(0.5f, 2.0f)
                    if (abs(slot.appliedSpeed - spd) > 0.02f || abs(slot.appliedPitch - pitchFactor) > 0.015f) {
                        p.playbackParameters = PlaybackParameters(spd, pitchFactor)
                        slot.appliedSpeed = spd
                        slot.appliedPitch = pitchFactor
                    }

                    // Soft envelope fade at first/last 20ms of clip to prevent audio click on hard cuts
                    val distFromEdgeMs = minOf(playheadMs - layer.startTimeMs, layer.endTimeMs - playheadMs)
                    val edgeGain = (distFromEdgeMs / 20f).coerceIn(0.15f, 1f)
                    val desiredVol = if (layer.audioMuted || layer.audioVolume <= 0.01f) {
                        0f
                    } else {
                        (layer.audioVolume.coerceIn(0f, 1.5f).coerceAtMost(1f)) * edgeGain
                    }
                    if (abs(slot.appliedVolume - desiredVol) > 0.02f) {
                        p.volume = desiredVol
                        slot.appliedVolume = desiredVol
                    }

                    val curPos = p.currentPosition.coerceAtLeast(0L)
                    slot.lastKnownMediaPosMs = curPos
                    slot.lastKnownPosNano = System.nanoTime()

                    val wasPlaying = p.playWhenReady && slot.isPlayingNow
                    val drift = abs(curPos - targetMs)
                    val seekCooldownPassed = (nowWall - slot.lastSeekWallMs) >= 750L

                    if (!wasPlaying) {
                        p.setSeekParameters(SeekParameters.EXACT)
                        if (drift > 35L || forceExactSeek) {
                            p.seekTo(targetMs)
                            slot.lastSeekTargetMs = targetMs
                            slot.lastSeekWallMs = nowWall
                            slot.lastKnownMediaPosMs = targetMs
                        }
                        p.play()
                        slot.isPlayingNow = true
                    } else if (forceExactSeek || (drift > driftLimit && seekCooldownPassed)) {
                        p.setSeekParameters(SeekParameters.EXACT)
                        p.seekTo(targetMs)
                        slot.lastSeekTargetMs = targetMs
                        slot.lastSeekWallMs = nowWall
                        slot.lastKnownMediaPosMs = targetMs
                    }
                } else if (nearClipPreview) {
                    val targetMs = AnimationEvaluator.evaluateSourceMediaTimeMs(layer, playheadMs)
                    val minInterval = if (rapidScrubbing) 24L else 0L
                    val targetChanged = abs(slot.lastSeekTargetMs - targetMs) >= (if (rapidScrubbing) 25L else 8L)
                    val needsExactUpgrade = !rapidScrubbing && !slot.lastExactSeekApplied

                    if ((targetChanged && (nowWall - slot.lastSeekWallMs) >= minInterval) || needsExactSeek(forceExactSeek, needsExactUpgrade)) {
                        p.setSeekParameters(if (rapidScrubbing) SeekParameters.CLOSEST_SYNC else SeekParameters.EXACT)
                        p.seekTo(targetMs)
                        slot.lastSeekTargetMs = targetMs
                        slot.lastSeekWallMs = nowWall
                        slot.lastExactSeekApplied = !rapidScrubbing
                        slot.lastKnownMediaPosMs = targetMs
                        slot.lastKnownPosNano = System.nanoTime()
                    }
                }
            }
        }
    }

    private fun needsExactSeek(forceExact: Boolean, upgradeToExact: Boolean): Boolean =
        forceExact || upgradeToExact

    /**
     * Lock-free O(1) Master Clock query: if an active video layer with unmuted audio is currently
     * playing in `STATE_READY`, returns its smooth monotonic-interpolated composition timestamp!
     */
    fun queryMasterCompositionTimeMs(project: Project, currentApproxMs: Long): Long? {
        val masterLayer = project.layers.firstOrNull { layer ->
            isEligibleForLiveOesStream(layer) &&
                !layer.audioMuted &&
                layer.audioVolume > 0.01f &&
                currentApproxMs in layer.startTimeMs until (layer.endTimeMs - 25L)
        } ?: return null

        val slot = slots[masterLayer.id] ?: return null
        if (!slot.isPlayingNow || !slot.isReady || slot.lastKnownPosNano <= 0L) return null

        val elapsedSincePollMs = ((System.nanoTime() - slot.lastKnownPosNano) / 1_000_000L).coerceIn(0L, 140L)
        val spd = masterLayer.speedMultiplier.coerceIn(0.25f, 3.0f)
        val estMediaMs = slot.lastKnownMediaPosMs + (elapsedSincePollMs * spd).toLong()
        val compOffsetMs = ((estMediaMs - masterLayer.sourceOffsetMs) / spd).toLong()
        val compMs = (masterLayer.startTimeMs + compOffsetMs).coerceIn(masterLayer.startTimeMs, masterLayer.endTimeMs)
        return if (abs(compMs - currentApproxMs) <= 450L) compMs else null
    }

    fun pauseAll() {
        runOnMain {
            slots.values.forEach { slot ->
                slot.isPlayingNow = false
                runCatching {
                    slot.player?.let { p ->
                        p.pause()
                        p.volume = 0f
                        slot.appliedVolume = 0f
                    }
                }
            }
        }
    }

    fun detachRendererSurfaces(rendererToken: Int) {
        runOnMain {
            slots.values.forEach { slot ->
                if (slot.boundRendererToken == rendererToken) {
                    val surf = slot.boundSurface
                    slot.boundSurface = null
                    slot.boundRendererToken = 0
                    slot.hasRenderedFirstFrame = false
                    runCatching {
                        if (surf != null) {
                            slot.player?.clearVideoSurface(surf)
                        } else {
                            slot.player?.clearVideoSurface()
                        }
                    }
                }
            }
        }
    }

    fun releaseAll() {
        runOnMain {
            slots.values.forEach { slot ->
                slot.isPlayingNow = false
                slot.boundSurface = null
                runCatching { slot.player?.release() }
                slot.player = null
            }
            slots.clear()
        }
    }

    private fun obtainOrCreateSlotOnMain(
        appContext: Context,
        layerId: String,
        rawMediaUri: String
    ): LayerPlayerSlot? {
        val resolvedUriStr = MediaEngine.resolvePlayableUriString(rawMediaUri)
        val existing = slots[layerId]
        if (existing != null && existing.player != null) {
            if (existing.preparedUri != resolvedUriStr) {
                existing.preparedUri = resolvedUriStr
                existing.hasRenderedFirstFrame = false
                existing.isReady = false
                runCatching {
                    existing.player?.setMediaItem(MediaItem.fromUri(Uri.parse(resolvedUriStr)))
                    existing.player?.prepare()
                }
            }
            return existing
        }

        // Enforce pool cap via LRU eviction of non-playing slots
        if (slots.size >= MAX_POOLED_VIDEO_PLAYERS) {
            val victim = slots.values
                .filter { !it.isPlayingNow }
                .minByOrNull { it.lastUsedWallMs }
            if (victim != null) {
                slots.remove(victim.layerId)
                runCatching { victim.player?.release() }
            }
        }

        return try {
            val slot = LayerPlayerSlot(layerId = layerId, preparedUri = resolvedUriStr)
            val renderersFactory = object : DefaultRenderersFactory(appContext) {
                override fun buildAudioSink(
                    context: Context,
                    enableFloatOutput: Boolean,
                    enableAudioTrackPlaybackParams: Boolean
                ): AudioSink {
                    return DefaultAudioSink.Builder(context)
                        .setEnableFloatOutput(false)
                        .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
                        .setAudioProcessors(arrayOf(slot.dspProcessor))
                        .build()
                }
            }
            val loadControl = DefaultLoadControl.Builder()
                .setBufferDurationsMs(
                    /* minBufferMs = */ 1_200,
                    /* maxBufferMs = */ 4_500,
                    /* bufferForPlaybackMs = */ 120,
                    /* bufferForPlaybackAfterRebufferMs = */ 240
                )
                .build()

            val exo = ExoPlayer.Builder(appContext, renderersFactory)
                .setLoadControl(loadControl)
                .build()
            exo.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                    .build(),
                /* handleAudioFocus = */ false
            )
            exo.repeatMode = Player.REPEAT_MODE_OFF
            exo.volume = 0f
            exo.setSeekParameters(SeekParameters.EXACT)

            exo.addListener(object : Player.Listener {
                override fun onRenderedFirstFrame() {
                    slot.hasRenderedFirstFrame = true
                }

                override fun onVideoSizeChanged(videoSize: VideoSize) {
                    if (videoSize.width > 0 && videoSize.height > 0) {
                        slot.videoWidth = videoSize.width
                        slot.videoHeight = videoSize.height
                    }
                }

                override fun onPlaybackStateChanged(playbackState: Int) {
                    slot.isReady = playbackState == Player.STATE_READY
                    if (playbackState == Player.STATE_READY) {
                        slot.lastKnownMediaPosMs = exo.currentPosition.coerceAtLeast(0L)
                        slot.lastKnownPosNano = System.nanoTime()
                    }
                }

                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    slot.isPlayingNow = isPlaying
                    if (isPlaying) {
                        slot.lastKnownMediaPosMs = exo.currentPosition.coerceAtLeast(0L)
                        slot.lastKnownPosNano = System.nanoTime()
                    }
                }
            })

            exo.setMediaItem(MediaItem.fromUri(Uri.parse(resolvedUriStr)))
            exo.prepare()
            slot.player = exo
            slots[layerId] = slot
            slot
        } catch (_: Throwable) {
            null
        }
    }

    private fun evictInactiveLayersOnMain(project: Project) {
        val activeIds = project.layers.map { it.id }.toSet()
        val stale = slots.keys.filter { it !in activeIds }
        stale.forEach { id ->
            slots.remove(id)?.let { removed ->
                runCatching { removed.player?.release() }
            }
        }
    }

    private inline fun runOnMain(crossinline block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            block()
        } else {
            mainHandler.post { block() }
        }
    }
}
