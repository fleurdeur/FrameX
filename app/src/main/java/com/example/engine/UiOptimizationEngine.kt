package com.example.engine

import android.content.ComponentCallbacks2
import android.content.Context
import android.content.res.Configuration
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Coordinates UI smoothness, scroll-aware heavy thumbnail deferral, frame-budget tracking,
 * and memory pressure trimming across the Effects browser, Plugin manager, and Editor timeline.
 */
object UiOptimizationEngine {

    private val scrollingFlag = AtomicBoolean(false)
    private val _isListScrolling = MutableStateFlow(false)
    val isListScrolling: StateFlow<Boolean> = _isListScrolling.asStateFlow()

    private val _smoothAnimationsEnabled = MutableStateFlow(true)
    val smoothAnimationsEnabled: StateFlow<Boolean> = _smoothAnimationsEnabled.asStateFlow()

    private val lastFrameStartNs = AtomicLong(0L)
    private val jankFrameCount = AtomicLong(0L)
    private var memoryCallbackRegistered = false

    fun setListScrolling(scrolling: Boolean) {
        if (scrollingFlag.getAndSet(scrolling) != scrolling) {
            _isListScrolling.value = scrolling
        }
    }

    fun setSmoothAnimationsEnabled(enabled: Boolean) {
        _smoothAnimationsEnabled.value = enabled
    }

    fun effectiveAnimationDurationMs(standardMs: Int): Int {
        return if (_smoothAnimationsEnabled.value) standardMs else 0
    }

    fun markFrameStart() {
        lastFrameStartNs.set(System.nanoTime())
    }

    fun markFrameEnd() {
        val start = lastFrameStartNs.get()
        if (start > 0L) {
            val elapsedMs = (System.nanoTime() - start) / 1_000_000L
            if (elapsedMs > 24L) {
                jankFrameCount.incrementAndGet()
            }
        }
    }

    fun shouldPauseHeavyThumbnails(): Boolean = scrollingFlag.get()

    fun onTrimMemory(level: Int) {
        if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW ||
            level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND
        ) {
            EffectThumbnailPreviewer.clearCache()
            PlaybackCacheEngine.clearAll()
        }
    }

    fun registerMemoryTrimListener(context: Context, onTrimRequested: (Int) -> Unit) {
        if (memoryCallbackRegistered) return
        memoryCallbackRegistered = true
        val appCtx = context.applicationContext
        appCtx.registerComponentCallbacks(object : ComponentCallbacks2 {
            override fun onTrimMemory(level: Int) {
                this@UiOptimizationEngine.onTrimMemory(level)
                if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW ||
                    level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND
                ) {
                    onTrimRequested(level)
                }
            }

            override fun onConfigurationChanged(newConfig: Configuration) = Unit

            @Deprecated("Deprecated in Android ComponentCallbacks")
            override fun onLowMemory() {
                EffectThumbnailPreviewer.clearCache()
                PlaybackCacheEngine.clearAll()
                onTrimRequested(ComponentCallbacks2.TRIM_MEMORY_COMPLETE)
            }
        })
    }
}
