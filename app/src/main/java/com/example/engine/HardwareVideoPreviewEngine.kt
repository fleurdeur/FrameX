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
import androidx.media3.common.PlaybackException
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
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import kotlin.math.pow

/**
 * Universal Zero-Copy Hardware + Software Fallback Video Preview Engine (`ExoPlayer` -> `GL_TEXTURE_EXTERNAL_OES`):
 * - Uses [UniversalCodecEngine.buildUniversalMediaCodecSelector] + `setEnableDecoderFallback(true)` so
 *   every phone hardware decoder (Qualcomm, Exynos, MediaTek, Tensor, Unisoc) automatically falls back to
 *   secondary or software decoders (`c2.android.*` / `OMX.google.*`) instead of glitching or failing.
 * - Maintains a 40ms Main-Looper clock sampler during active playback so [queryMasterCompositionTimeMs]
 *   never stalls or oscillates between UI ticks.
 * - Never poisons [MediaEngine] if an OES stream fails; instead marks only `oesUnsupportedUris` so the GL
 *   compositor falls back cleanly to software/retriever frame textures.
 */
@OptIn(UnstableApi::class)
object HardwareVideoPreviewEngine {

    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    var maxPooledVideoPlayers: Int = 4

    @Volatile
    var fastKeyframeScrub: Boolean = true

    @Volatile
    var syncDriftThresholdMs: Long = 220L

    private val oesUnsupportedUris: MutableSet<String> = Collections.synchronizedSet(HashSet())

    private class VideoPlayerSlot(
        val layerId: String,
        var rawMediaUri: String,
        var preparedUri: String,
        val player: ExoPlayer,
        val dspProcessor: StudioDspAudioProcessor
    ) {
        @Volatile var isReady: Boolean = false
        @Volatile var hasRenderedFirstFrame: Boolean = false
        @Volatile var hasError: Boolean = false
        @Volatile var videoWidth: Int = 0
        @Volatile var videoHeight: Int = 0
        @Volatile var lastKnownPositionMs: Long = 0L
        @Volatile var lastKnownPosNano: Long = 0L
        @Volatile var isActivelyPlaying: Boolean = false
        @Volatile var seekSettlingUntilWallMs: Long = 0L

        var boundSurface: Surface? = null
        var boundRendererToken: Int = 0
        var appliedSpeed: Float = 1.0f
        var appliedPitch: Float = 1.0f
        var appliedVolume: Float = -1f
        var lastSeekTargetMs: Long = -1L
        var lastSeekWallMs: Long = 0L
        var pendingTrailingExactSeekMs: Long = -1L
    }

    private val slots = ConcurrentHashMap<String, VideoPlayerSlot>()

    @Volatile
    private var samplerScheduled = false

    private val positionSamplerRunnable = object : Runnable {
        override fun run() {
            samplerScheduled = false
            val nowWall = SystemClock.elapsedRealtime()
            val nowNano = System.nanoTime()
            var anyPlaying = false
            slots.values.forEach { slot ->
                val p = slot.player
                if (p.playWhenReady && p.playbackState == Player.STATE_READY && !slot.hasError) {
                    anyPlaying = true
                    if (nowWall >= slot.seekSettlingUntilWallMs) {
                        slot.lastKnownPositionMs = p.currentPosition.coerceAtLeast(0L)
                        slot.lastKnownPosNano = nowNano
                    }
                }
            }
            if (anyPlaying) {
                samplerScheduled = true
                mainHandler.postDelayed(this, 40L)
            }
        }
    }

    private fun ensurePositionSamplerOnMain() {
        if (!samplerScheduled) {
            samplerScheduled = true
            mainHandler.postDelayed(positionSamplerRunnable, 40L)
        }
    }

    /**
     * Returns the playable URI for live `ExoPlayer -> GL_TEXTURE_EXTERNAL_OES` streaming:
     * - Forward video layers stream `layer.mediaUri` directly.
     * - Reversed video layers stream their cached [FFmpegReverseVideoEngine] reversed MP4 proxy forward at 60fps!
     */
    fun effectivePlayableUriForLayer(layer: Layer): String {
        if (layer.type == LayerType.VIDEO && layer.reversed && !layer.timeRemapEnabled && layer.freezeFrameMs < 0L) {
            FFmpegReverseVideoEngine.getReadyReversedProxy(layer)?.let { proxy ->
                if (proxy.fileUri !in oesUnsupportedUris) {
                    return proxy.fileUri
                }
            }
        }
        return layer.mediaUri
    }

    /**
     * Returns true when [layer] can stream directly via `ExoPlayer -> GL_TEXTURE_EXTERNAL_OES`.
     * Includes reversed video layers as soon as their FFmpegKit reversed proxy is ready!
     */
    fun isEligibleForLiveOesStream(layer: Layer): Boolean {
        if (layer.type != LayerType.VIDEO ||
            layer.mediaUri.isBlank() ||
            MediaEngine.isUriUnreachable(layer.mediaUri) ||
            layer.timeRemapEnabled ||
            layer.freezeFrameMs >= 0L
        ) {
            return false
        }
        if (layer.reversed) {
            val proxy = FFmpegReverseVideoEngine.getReadyReversedProxy(layer) ?: return false
            return proxy.fileUri !in oesUnsupportedUris
        }
        return layer.mediaUri !in oesUnsupportedUris
    }

    fun clearUnsupportedCache() {
        oesUnsupportedUris.clear()
    }

    fun hasSlotFailed(layerId: String): Boolean = slots[layerId]?.hasError == true

    fun hasReadyVideoFrame(layerId: String): Boolean {
        val slot = slots[layerId] ?: return false
        return !slot.hasError && (slot.hasRenderedFirstFrame || slot.isReady)
    }

    fun getSlotAspect(layerId: String): Float? {
        val slot = slots[layerId] ?: return null
        if (slot.videoWidth > 0 && slot.videoHeight > 0) {
            return slot.videoWidth.toFloat() / slot.videoHeight.toFloat()
        }
        return null
    }

    /**
     * Called from the GL thread when an `OESLayerSurfaceSlot` creates or refreshes its [Surface].
     * Attaches the surface to the layer's pooled [ExoPlayer] on the Main Looper and nudges a paused
     * player to push an immediate frame onto the new [Surface] so zero black frames occur!
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
            if (!surface.isValid) return@runOnMain
            val slot = obtainOrCreateSlotOnMain(appCtx, layerId, mediaUri, null) ?: return@runOnMain
            if (slot.boundSurface !== surface) {
                slot.boundSurface = surface
                slot.boundRendererToken = rendererToken
                runCatching {
                    slot.player.setVideoSurface(surface)
                    // When paused & ready, blit the target/lastKnown frame (NEVER default to 0ms if playhead > 0!)
                    if (!slot.player.playWhenReady && slot.player.playbackState == Player.STATE_READY) {
                        val pos = slot.lastSeekTargetMs.takeIf { it >= 0L }
                            ?: slot.lastKnownPositionMs.takeIf { it > 0L }
                            ?: slot.player.currentPosition.coerceAtLeast(0L)
                        slot.player.setSeekParameters(SeekParameters.EXACT)
                        slot.player.seekTo(pos)
                    }
                }
            }
        }
    }

    fun unbindRendererSurfaces(rendererToken: Int) {
        runOnMain {
            slots.values.forEach { slot ->
                if (slot.boundRendererToken == rendererToken) {
                    slot.boundSurface = null
                    slot.boundRendererToken = 0
                    slot.hasRenderedFirstFrame = false
                    runCatching { slot.player.clearVideoSurface() }
                }
            }
        }
    }

    fun detachRendererSurfaces(rendererToken: Int) = unbindRendererSurfaces(rendererToken)

    /**
     * Releases hardware decoder instances temporarily before heavy offscreen Pre-Render / Export jobs
     * so devices with strict 2-instance hardware decoder limits never starve or crash!
     */
    fun releaseDecodersForOffscreenWork() {
        releaseAll()
    }

    /**
     * Pre-warms pooled [ExoPlayer] instances for forward video layers in [project].
     */
    fun prewarmProjectVideos(context: Context, project: Project, playheadMs: Long) {
        val appCtx = context.applicationContext
        val maxPool = maxPooledVideoPlayers.coerceIn(2, 6)
        val eligibleLayers = project.layers
            .filter { isEligibleForLiveOesStream(it) }
            .sortedBy { layer ->
                if (playheadMs in layer.startTimeMs..layer.endTimeMs) 0 else 1
            }
            .take(maxPool)

        runOnMain {
            val keepIds = eligibleLayers.map { it.id }.toSet()
            val staleIds = slots.keys.filter { it !in keepIds }
            staleIds.forEach { id ->
                slots.remove(id)?.let { evicted ->
                    evicted.boundSurface = null
                    runCatching { evicted.player.release() }
                }
            }

            eligibleLayers.forEach { layer ->
                val playableUri = effectivePlayableUriForLayer(layer)
                val slot = obtainOrCreateSlotOnMain(appCtx, layer.id, playableUri, layer) ?: return@forEach
                if (slot.hasError) return@forEach
                slot.dspProcessor.updateFromLayer(layer)
                if (!slot.player.playWhenReady) {
                    val targetMs = evaluateEffectivePlayerMediaTimeMs(layer, playheadMs)
                    if (abs(slot.lastSeekTargetMs - targetMs) > 25L) {
                        slot.player.setSeekParameters(SeekParameters.EXACT)
                        slot.player.seekTo(targetMs)
                        slot.lastSeekTargetMs = targetMs
                        slot.lastSeekWallMs = SystemClock.elapsedRealtime()
                        slot.lastKnownPositionMs = targetMs
                        slot.lastKnownPosNano = System.nanoTime()
                    }
                }
            }
        }
    }

    /**
     * Synchronizes all pooled [ExoPlayer] video instances with the timeline playhead & transport state.
     */
    fun syncProjectVideos(
        context: Context,
        project: Project,
        playheadMs: Long,
        isPlaying: Boolean,
        rapidScrubbing: Boolean = false,
        forceExactSeek: Boolean = false
    ) {
        val appCtx = context.applicationContext
        val maxPool = maxPooledVideoPlayers.coerceIn(2, 6)
        val eligibleLayers = project.layers
            .filter { isEligibleForLiveOesStream(it) }
            .sortedBy { layer ->
                if (playheadMs in layer.startTimeMs..layer.endTimeMs) 0 else 1
            }
            .take(maxPool)

        runOnMain {
            val nowWall = SystemClock.elapsedRealtime()
            val nowNano = System.nanoTime()
            val keepIds = eligibleLayers.map { it.id }.toSet()

            val staleIds = slots.keys.filter { it !in keepIds }
            staleIds.forEach { id ->
                slots.remove(id)?.let { evicted ->
                    evicted.boundSurface = null
                    runCatching { evicted.player.release() }
                }
            }

            eligibleLayers.forEach { layer ->
                val playableUri = effectivePlayableUriForLayer(layer)
                val slot = obtainOrCreateSlotOnMain(appCtx, layer.id, playableUri, layer) ?: return@forEach
                if (slot.hasError) return@forEach
                val p = slot.player
                slot.dspProcessor.updateFromLayer(layer)

                if (p.playbackState == Player.STATE_IDLE) {
                    runCatching { p.prepare() }
                }

                val inRange = layer.visible && playheadMs in layer.startTimeMs until layer.endTimeMs
                val targetMediaMs = evaluateEffectivePlayerMediaTimeMs(layer, playheadMs)
                val isFrozen = layer.freezeFrameMs >= 0L

                val spd = layer.speedMultiplier.coerceIn(0.20f, 4.0f)
                val pitchFactor = 2.0.pow(layer.audioPitchSemitones.coerceIn(-12f, 12f) / 12.0)
                    .toFloat()
                    .coerceIn(0.5f, 2.0f)
                if (abs(slot.appliedSpeed - spd) > 0.015f || abs(slot.appliedPitch - pitchFactor) > 0.015f) {
                    p.playbackParameters = PlaybackParameters(spd, pitchFactor)
                    slot.appliedSpeed = spd
                    slot.appliedPitch = pitchFactor
                }

                val shouldPlayLive = isPlaying && inRange && !isFrozen && !rapidScrubbing
                val edgeDistMs = minOf(playheadMs - layer.startTimeMs, layer.endTimeMs - playheadMs)
                val edgeFade = (edgeDistMs / 16f).coerceIn(0.20f, 1f)
                val desiredVol = if (!shouldPlayLive || layer.audioMuted) {
                    0f
                } else {
                    (layer.audioVolume.coerceIn(0f, 1.5f).coerceAtMost(1f)) * edgeFade
                }
                if (abs(slot.appliedVolume - desiredVol) > 0.015f) {
                    p.volume = desiredVol
                    slot.appliedVolume = desiredVol
                }

                if (shouldPlayLive) {
                    val curPos = p.currentPosition.coerceAtLeast(0L)
                    if (nowWall >= slot.seekSettlingUntilWallMs && p.isPlaying) {
                        slot.lastKnownPositionMs = curPos
                        slot.lastKnownPosNano = nowNano
                    }
                    val endedOrPaused = p.playbackState == Player.STATE_ENDED || !p.playWhenReady
                    val driftMs = abs(curPos - targetMediaMs)
                    val seekCooldownPassed = (nowWall - slot.lastSeekWallMs) >= 950L &&
                        nowWall >= slot.seekSettlingUntilWallMs

                    val driftLimitMs = syncDriftThresholdMs.coerceIn(175L, 340L)
                    if (endedOrPaused) {
                        p.setSeekParameters(SeekParameters.EXACT)
                        p.seekTo(targetMediaMs)
                        slot.lastSeekTargetMs = targetMediaMs
                        slot.lastSeekWallMs = nowWall
                        slot.seekSettlingUntilWallMs = nowWall + 300L
                        slot.lastKnownPositionMs = targetMediaMs
                        slot.lastKnownPosNano = nowNano
                        p.play()
                        slot.isActivelyPlaying = true
                    } else if (forceExactSeek || (driftMs > driftLimitMs && seekCooldownPassed)) {
                        p.setSeekParameters(SeekParameters.EXACT)
                        p.seekTo(targetMediaMs)
                        slot.lastSeekTargetMs = targetMediaMs
                        slot.lastSeekWallMs = nowWall
                        slot.seekSettlingUntilWallMs = nowWall + 300L
                        slot.lastKnownPositionMs = targetMediaMs
                        slot.lastKnownPosNano = nowNano
                        if (!p.isPlaying) p.play()
                    }
                    ensurePositionSamplerOnMain()
                } else {
                    if (p.playWhenReady || p.isPlaying) {
                        p.pause()
                    }
                    slot.isActivelyPlaying = false

                    if (layer.visible && (inRange || isFrozen || abs(playheadMs - layer.startTimeMs) <= 300L)) {
                        val minDeltaMs = if (rapidScrubbing) 14L else 8L
                        val minWallIntervalMs = if (rapidScrubbing) 28L else 0L
                        val wallElapsed = nowWall - slot.lastSeekWallMs
                        if (forceExactSeek ||
                            (abs(slot.lastSeekTargetMs - targetMediaMs) >= minDeltaMs && wallElapsed >= minWallIntervalMs)
                        ) {
                            p.setSeekParameters(
                                if (rapidScrubbing && fastKeyframeScrub && !forceExactSeek) {
                                    SeekParameters.CLOSEST_SYNC
                                } else {
                                    SeekParameters.EXACT
                                }
                            )
                            p.seekTo(targetMediaMs)
                            slot.lastSeekTargetMs = targetMediaMs
                            slot.lastSeekWallMs = nowWall
                            slot.lastKnownPositionMs = targetMediaMs
                            slot.lastKnownPosNano = nowNano
                            slot.pendingTrailingExactSeekMs = -1L
                        } else if (rapidScrubbing) {
                            slot.pendingTrailingExactSeekMs = targetMediaMs
                        }
                    }
                }
            }
        }
    }

    /**
     * Returns the composition timeline position (in ms) derived from the primary active video's
     * hardware audio/video clock so timeline keyframes and effects stay lip-synced without jitter!
     */
    fun queryMasterCompositionTimeMs(project: Project, currentApproxMs: Long): Long? {
        val masterLayer = project.layers.firstOrNull { layer ->
            layer.visible &&
                isEligibleForLiveOesStream(layer) &&
                layer.freezeFrameMs < 0L &&
                currentApproxMs in layer.startTimeMs until (layer.endTimeMs - 25L)
        } ?: return null

        val slot = slots[masterLayer.id] ?: return null
        if (!slot.isActivelyPlaying || !slot.isReady || slot.hasError || slot.lastKnownPosNano <= 0L) {
            return null
        }
        if (SystemClock.elapsedRealtime() < slot.seekSettlingUntilWallMs) {
            return null
        }

        val elapsedMs = ((System.nanoTime() - slot.lastKnownPosNano) / 1_000_000L).coerceIn(0L, 240L)
        val spd = masterLayer.speedMultiplier.coerceIn(0.20f, 4.0f)
        val estPlayerPosMs = slot.lastKnownPositionMs + (elapsedMs * spd).toLong()
        val compOffsetMs = if (masterLayer.reversed) {
            val proxy = FFmpegReverseVideoEngine.getReadyReversedProxy(masterLayer) ?: return null
            val forwardSourceMs = FFmpegReverseVideoEngine.mapReversedProxyMsToForwardSourceMs(estPlayerPosMs, proxy)
            val safeDur = masterLayer.sourceDurationMs.coerceAtLeast(100L)
            val wrappedMs = (safeDur - forwardSourceMs).coerceAtLeast(0L)
            ((wrappedMs - masterLayer.sourceOffsetMs) / spd).toLong()
        } else {
            ((estPlayerPosMs - masterLayer.sourceOffsetMs) / spd).toLong()
        }
        val compTimeMs = (masterLayer.startTimeMs + compOffsetMs)
            .coerceIn(masterLayer.startTimeMs, masterLayer.endTimeMs)

        return if (abs(compTimeMs - currentApproxMs) <= 420L) compTimeMs else null
    }

    fun pauseAll() {
        runOnMain {
            slots.values.forEach { slot ->
                slot.isActivelyPlaying = false
                runCatching {
                    if (slot.player.playWhenReady || slot.player.isPlaying) {
                        slot.player.pause()
                    }
                }
            }
        }
    }

    fun releaseAll() {
        runOnMain {
            slots.values.forEach { slot ->
                slot.isActivelyPlaying = false
                slot.boundSurface = null
                runCatching { slot.player.release() }
            }
            slots.clear()
        }
    }

    private fun evaluateEffectivePlayerMediaTimeMs(layer: Layer, playheadMs: Long): Long {
        val rawForwardMs = AnimationEvaluator.evaluateSourceMediaTimeMs(layer, playheadMs)
        if (layer.reversed) {
            val proxy = FFmpegReverseVideoEngine.getReadyReversedProxy(layer)
            if (proxy != null) {
                val mappedMs = FFmpegReverseVideoEngine.mapForwardSourceMsToReversedProxyMs(rawForwardMs, proxy)
                return if (proxy.proxyDurationMs > 60L) {
                    mappedMs.coerceIn(0L, proxy.proxyDurationMs - 24L)
                } else {
                    mappedMs.coerceAtLeast(0L)
                }
            }
        }
        return clampMediaTimeMs(layer, rawForwardMs)
    }

    private fun clampMediaTimeMs(layer: Layer, rawMediaMs: Long): Long {
        return if (layer.sourceDurationMs > 60L) {
            rawMediaMs.coerceIn(0L, layer.sourceDurationMs - 24L)
        } else {
            rawMediaMs.coerceAtLeast(0L)
        }
    }

    private fun obtainOrCreateSlotOnMain(
        appContext: Context,
        layerId: String,
        rawMediaUri: String,
        layerSnapshot: Layer?
    ): VideoPlayerSlot? {
        if (rawMediaUri.isBlank() ||
            MediaEngine.isUriUnreachable(rawMediaUri) ||
            rawMediaUri in oesUnsupportedUris
        ) {
            return null
        }
        val resolvedUriStr = MediaEngine.resolvePlayableUriString(rawMediaUri)
        val existing = slots[layerId]
        if (existing != null) {
            if (existing.preparedUri != resolvedUriStr) {
                existing.rawMediaUri = rawMediaUri
                existing.preparedUri = resolvedUriStr
                existing.isReady = false
                existing.hasRenderedFirstFrame = false
                existing.hasError = false
                existing.lastSeekTargetMs = -1L
                runCatching {
                    existing.player.setMediaItem(MediaItem.fromUri(Uri.parse(resolvedUriStr)))
                    existing.player.prepare()
                }
            }
            return existing
        }

        return try {
            val dspProcessor = StudioDspAudioProcessor().apply {
                if (layerSnapshot != null) updateFromLayer(layerSnapshot)
            }
            val renderersFactory = object : DefaultRenderersFactory(appContext) {
                override fun buildAudioSink(
                    context: Context,
                    enableFloatOutput: Boolean,
                    enableAudioTrackPlaybackParams: Boolean
                ): AudioSink {
                    return DefaultAudioSink.Builder(context)
                        .setEnableFloatOutput(false)
                        .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
                        .setAudioProcessors(arrayOf(dspProcessor))
                        .build()
                }
            }
                .setEnableDecoderFallback(true)
                .setMediaCodecSelector(UniversalCodecEngine.buildUniversalMediaCodecSelector())

            val loadControl = DefaultLoadControl.Builder()
                .setBufferDurationsMs(
                    /* minBufferMs = */ 1_200,
                    /* maxBufferMs = */ 4_500,
                    /* bufferForPlaybackMs = */ 50,
                    /* bufferForPlaybackAfterRebufferMs = */ 100
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
            exo.setSeekParameters(SeekParameters.EXACT)

            val slot = VideoPlayerSlot(
                layerId = layerId,
                rawMediaUri = rawMediaUri,
                preparedUri = resolvedUriStr,
                player = exo,
                dspProcessor = dspProcessor
            )

            exo.addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(playbackState: Int) {
                    slot.isReady = playbackState == Player.STATE_READY
                    if (playbackState == Player.STATE_READY) {
                        slot.hasError = false
                        slot.seekSettlingUntilWallMs = 0L
                        val targetOrPos = slot.lastSeekTargetMs.takeIf { it >= 0L }
                            ?: exo.currentPosition.coerceAtLeast(0L)
                        slot.lastKnownPositionMs = targetOrPos
                        slot.lastKnownPosNano = System.nanoTime()
                        // If a surface is already attached while paused, ensure exact seek target is pushed!
                        if (!exo.playWhenReady && slot.boundSurface != null && !slot.hasRenderedFirstFrame) {
                            runCatching { exo.seekTo(targetOrPos) }
                        }
                    }
                }

                override fun onRenderedFirstFrame() {
                    slot.hasRenderedFirstFrame = true
                    slot.hasError = false
                    slot.seekSettlingUntilWallMs = 0L
                    val cur = exo.currentPosition.coerceAtLeast(0L)
                    slot.lastKnownPositionMs = if (cur == 0L && slot.lastSeekTargetMs > 25L && !exo.isPlaying) {
                        slot.lastSeekTargetMs
                    } else {
                        cur
                    }
                    slot.lastKnownPosNano = System.nanoTime()
                }

                override fun onVideoSizeChanged(videoSize: VideoSize) {
                    if (videoSize.width > 0 && videoSize.height > 0) {
                        slot.videoWidth = (videoSize.width * videoSize.pixelWidthHeightRatio).toInt().coerceAtLeast(1)
                        slot.videoHeight = videoSize.height
                    }
                }

                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    slot.isActivelyPlaying = isPlaying
                    if (isPlaying) {
                        slot.lastKnownPositionMs = exo.currentPosition.coerceAtLeast(0L)
                        slot.lastKnownPosNano = System.nanoTime()
                        ensurePositionSamplerOnMain()
                    }
                }

                override fun onPlayerError(error: PlaybackException) {
                    slot.hasError = true
                    slot.isReady = false
                    slot.isActivelyPlaying = false
                    // Fall back cleanly to software/MediaMetadataRetriever frame textures for this URI
                    // WITHOUT poisoning MediaEngine.unreachableUris!
                    oesUnsupportedUris.add(slot.rawMediaUri)
                    runCatching { exo.clearVideoSurface() }
                }
            })

            exo.setMediaItem(MediaItem.fromUri(Uri.parse(resolvedUriStr)))
            exo.prepare()
            slots[layerId] = slot
            slot
        } catch (_: Throwable) {
            oesUnsupportedUris.add(rawMediaUri)
            null
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
