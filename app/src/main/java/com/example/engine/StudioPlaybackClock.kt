package com.example.engine

import android.os.SystemClock
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs

/**
 * Lock-Free Monotonic Master Playback Clock (`StudioPlaybackClock`):
 * - Provides a single source of truth shared between `StudioViewModel`, `GlPreviewCanvas`,
 *   `FramePollutionGlRenderer` (60fps VSync GL thread), `HardwareVideoPreviewEngine`, and
 *   `TimelineAudioController` without going through Compose recomposition or Main-Looper stalls.
 * - Uses smooth proportional clock slewing when reconciling with hardware audio/video DAC clocks
 *   so the playhead never snaps backwards or stutters across frames!
 */
object StudioPlaybackClock {

    private val playingFlag = AtomicBoolean(false)
    private val loopFlag = AtomicBoolean(true)
    private val durationMsRef = AtomicLong(10_000L)

    private val naturalWrapFlag = AtomicBoolean(false)

    // Packed anchor state updated atomically via lock-light volatile pair
    @Volatile private var anchorMediaMs: Long = 0L
    @Volatile private var anchorNanoTime: Long = 0L
    @Volatile private var pausedPlayheadMs: Long = 0L
    @Volatile private var lastDispatchedPlayheadMs: Long = 0L

    fun start(initialPlayheadMs: Long, durationMs: Long, isLooping: Boolean) {
        val safeDur = durationMs.coerceAtLeast(100L)
        val safeStart = initialPlayheadMs.coerceIn(0L, safeDur)
        durationMsRef.set(safeDur)
        loopFlag.set(isLooping)
        naturalWrapFlag.set(false)
        anchorMediaMs = safeStart
        anchorNanoTime = System.nanoTime()
        pausedPlayheadMs = safeStart
        lastDispatchedPlayheadMs = safeStart
        playingFlag.set(true)
    }

    fun pause(atPlayheadMs: Long? = null) {
        val resolved = atPlayheadMs ?: samplePlayheadMs()
        playingFlag.set(false)
        naturalWrapFlag.set(false)
        pausedPlayheadMs = resolved.coerceAtLeast(0L)
        anchorMediaMs = pausedPlayheadMs
        anchorNanoTime = System.nanoTime()
    }

    fun setPausedPlayhead(playheadMs: Long) {
        val clamped = playheadMs.coerceAtLeast(0L)
        pausedPlayheadMs = clamped
        if (!playingFlag.get()) {
            anchorMediaMs = clamped
            anchorNanoTime = System.nanoTime()
        }
    }

    /**
     * Updates loop & composition duration WITHOUT resetting or jumping the live playhead!
     * Re-anchors `anchorMediaMs` + `anchorNanoTime` at `nowNano` before updating `durationMsRef`
     * so `samplePlayheadMs()` stays 100% continuous when users edit layers or sliders during playback!
     */
    fun updateLoopAndDuration(isLooping: Boolean, durationMs: Long) {
        val newDur = durationMs.coerceAtLeast(100L)
        val oldDur = durationMsRef.get()
        val oldLoop = loopFlag.get()
        if (newDur == oldDur && oldLoop == isLooping) {
            return
        }
        if (playingFlag.get()) {
            val nowNano = System.nanoTime()
            val curMs = computeRawPlayingMs(nowNano, oldDur)
            val preservedMs = if (curMs < newDur) {
                curMs
            } else if (isLooping) {
                curMs % newDur
            } else {
                newDur
            }
            anchorMediaMs = preservedMs
            anchorNanoTime = nowNano
            lastDispatchedPlayheadMs = preservedMs
        }
        loopFlag.set(isLooping)
        durationMsRef.set(newDur)
    }

    /**
     * Re-anchors immediately on hard loop wrap (`dur -> 0`).
     */
    fun wrapToStart(durationMs: Long) {
        durationMsRef.set(durationMs.coerceAtLeast(100L))
        naturalWrapFlag.set(false)
        anchorMediaMs = 0L
        anchorNanoTime = System.nanoTime()
        lastDispatchedPlayheadMs = 0L
    }

    /**
     * Atomically consumes true if the playback clock naturally reached `durationMs` and wrapped/clamped.
     */
    fun consumeNaturalEndOrWrapFlag(): Boolean = naturalWrapFlag.getAndSet(false)

    /**
     * Gently slews our monotonic visual clock toward the hardware DAC audio/video clock
     * (`hardwareMs`) without ever jumping backwards unless the drift exceeds 240ms.
     */
    fun reconcileWithHardwareClock(hardwareMs: Long, nowNano: Long = System.nanoTime()): Long {
        if (!playingFlag.get()) return pausedPlayheadMs
        val dur = durationMsRef.get().coerceAtLeast(100L)
        val rawVisual = computeRawPlayingMs(nowNano, dur)
        // Guard against bogus 0ms hardware reports right after an ExoPlayer surface rebind when visual clock > 280ms
        if (hardwareMs <= 5L && rawVisual in 280L until (dur - 150L)) {
            lastDispatchedPlayheadMs = rawVisual
            return rawVisual
        }
        val delta = hardwareMs - rawVisual

        // Ignore sub-frame jitter completely so anchorNanoTime isn't continually reset
        if (abs(delta) <= 22L) {
            lastDispatchedPlayheadMs = rawVisual
            return rawVisual
        }

        val blended = when {
            abs(delta) > 240L -> hardwareMs.coerceIn(0L, dur)
            delta < 0L -> {
                // Never jump backwards for negative DAC jitter — hold monotonic progress smoothly!
                maxOf(lastDispatchedPlayheadMs, rawVisual + (delta / 8L)).coerceIn(0L, dur)
            }
            else -> {
                // Smoothly catch up 25% of positive drift per tick
                (rawVisual + (delta / 4L)).coerceIn(0L, dur)
            }
        }
        anchorMediaMs = blended
        anchorNanoTime = nowNano
        lastDispatchedPlayheadMs = blended
        return blended
    }

    /**
     * Lock-free O(1) playhead sample for the 60fps OpenGL ES 2.0 `onDrawFrame` thread!
     */
    fun samplePlayheadMs(nowNano: Long = System.nanoTime()): Long {
        if (!playingFlag.get()) {
            return pausedPlayheadMs.coerceAtLeast(0L)
        }
        val dur = durationMsRef.get().coerceAtLeast(100L)
        val computed = computeRawPlayingMs(nowNano, dur)
        lastDispatchedPlayheadMs = computed
        return computed
    }

    fun isPlaying(): Boolean = playingFlag.get()

    private fun computeRawPlayingMs(nowNano: Long, dur: Long): Long {
        val baseNano = anchorNanoTime
        val baseMs = anchorMediaMs
        if (baseNano <= 0L) return baseMs.coerceIn(0L, dur)
        val elapsedMs = ((nowNano - baseNano) / 1_000_000L).coerceAtLeast(0L)
        val sum = baseMs + elapsedMs
        if (sum >= dur) {
            naturalWrapFlag.set(true)
            return if (loopFlag.get()) {
                val wrapped = sum % dur
                anchorMediaMs = wrapped
                anchorNanoTime = nowNano
                wrapped
            } else {
                dur
            }
        }
        return sum
    }
}
