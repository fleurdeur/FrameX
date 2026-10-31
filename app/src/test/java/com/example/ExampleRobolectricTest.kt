package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.model.AnimatableProperty
import com.example.data.model.AspectRatioPreset
import com.example.data.model.Keyframe
import com.example.data.model.Layer
import com.example.data.model.LayerType
import com.example.data.model.ProjectPayloadSerializer
import com.example.engine.AnimationEvaluator
import com.example.engine.AudioReactorEngine
import com.example.engine.EffectsCatalog
import com.example.viewmodel.StudioWorkspaceState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.math.abs

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExampleRobolectricTest {

    @Test
    fun `read app_name and verify 120 plus effects catalog`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val appName = context.getString(R.string.app_name)
        assertEquals("FramePollution", appName)
        assertTrue("Expected 120+ curated GPU effects", EffectsCatalog.allEffects.size >= 120)
    }

    @Test
    fun `verify clean empty initial project state with zero premade layers`() {
        val ws = StudioWorkspaceState()
        assertTrue("Default workspace project should start clean with zero premade layers", ws.activeProject.layers.isEmpty())
        assertTrue("Default workspace project should have zero premade bookmarks", ws.activeProject.beatMarkers.isEmpty())
        assertNull("No layer should be pre-selected in a clean empty project", ws.selectedLayerId)
    }

    @Test
    fun `verify cubic bezier interpolation and custom FPS SMPTE timecode`() {
        val mid = AnimationEvaluator.evaluateCubicBezier(0.5f, 0.0f, 0.0f, 1.0f, 1.0f)
        assertEquals(0.5f, mid, 0.02f)

        val tc24 = AnimationEvaluator.formatSmpteTimecode(1500L, 24)
        assertEquals("00:00:01:12", tc24)

        val tc48 = AnimationEvaluator.formatSmpteTimecode(1500L, 48)
        assertEquals("00:00:01:24", tc48)
    }

    @Test
    fun `verify video time remap reverse freeze frame and speed evaluation`() {
        val audio = AudioReactorEngine().sampleTimelineSpectrum(1000L, 128, emptyList())

        // 1. Reverse playback on a 5000ms video clip
        val revVideo = Layer(
            id = "vid_rev",
            type = LayerType.VIDEO,
            startTimeMs = 0L,
            endTimeMs = 5000L,
            sourceDurationMs = 5000L,
            reversed = true
        )
        assertEquals(4000L, AnimationEvaluator.evaluateSourceMediaTimeMs(revVideo, 1000L, audio))

        // 2. Time Remapping (Velocity curve) at 2500ms -> mapped to 4.2s (4200ms)
        val remappedVideo = revVideo.copy(
            reversed = false,
            timeRemapEnabled = true,
            timeRemap = AnimatableProperty(
                baseValue = 0f,
                keyframes = listOf(
                    Keyframe(timeMs = 0L, value = 0f),
                    Keyframe(timeMs = 2500L, value = 4.2f),
                    Keyframe(timeMs = 5000L, value = 5.0f)
                )
            )
        )
        assertEquals(4200L, AnimationEvaluator.evaluateSourceMediaTimeMs(remappedVideo, 2500L, audio))

        // 3. Freeze Frame overrides playhead
        val frozenVideo = remappedVideo.copy(freezeFrameMs = 1750L)
        assertEquals(1750L, AnimationEvaluator.evaluateSourceMediaTimeMs(frozenVideo, 4000L, audio))
    }

    @Test
    fun `verify null parenting active camera projection and KeyFlow spring physics`() {
        val audio = AudioReactorEngine().sampleTimelineSpectrum(500L, 128, emptyList())
        val cam = Layer(
            id = "cam_1",
            type = LayerType.CAMERA,
            posX = AnimatableProperty(40f),
            scale = AnimatableProperty(50f)
        )
        val parent = Layer(
            id = "null_1",
            type = LayerType.NULL_OBJECT,
            posX = AnimatableProperty(100f),
            scale = AnimatableProperty(100f)
        )
        val child = Layer(
            id = "child_1",
            parentId = "null_1",
            posX = AnimatableProperty(baseValue = 20f),
            posY = AnimatableProperty(baseValue = 0f, expression = "spring(3, 4, 60)")
        )

        val noCamWorld = AnimationEvaluator.evaluateWorldTransform(
            layer = child,
            allLayers = listOf(cam, parent, child),
            timeMs = 250L,
            audio = audio,
            applyActiveCamera = false
        )
        assertEquals(120f, noCamWorld.posX, 1.0f)
        assertTrue("KeyFlow spring expression should displace Y", abs(noCamWorld.posY) > 1f)

        val camZoomInWorld = AnimationEvaluator.evaluateWorldTransform(
            layer = child,
            allLayers = listOf(cam, parent, child),
            timeMs = 250L,
            audio = audio,
            applyActiveCamera = true
        )
        // Camera frame at X=40 with 50% tighter viewport -> 2.0x zoom in: (120 - 40) / 0.5 = 160
        assertEquals(160f, camZoomInWorld.posX, 1.5f)

        val wideCam = cam.copy(scale = AnimatableProperty(200f))
        val camWideWorld = AnimationEvaluator.evaluateWorldTransform(
            layer = child,
            allLayers = listOf(wideCam, parent, child),
            timeMs = 250L,
            audio = audio,
            applyActiveCamera = true
        )
        // Camera frame at X=40 with 200% wide-angle viewport -> 0.5x zoom out: (120 - 40) / 2.0 = 40
        assertEquals(40f, camWideWorld.posX, 1.5f)
    }

    @Test
    fun `verify envelope serialization of background audio waveform timeRemap mask and motionBlur`() {
        assertEquals(1080, AspectRatioPreset.PORTRAIT_4_5.defaultWidth)
        assertEquals(1350, AspectRatioPreset.PORTRAIT_4_5.defaultHeight)

        val videoLayer = Layer(
            id = "vid_1",
            name = "Clip.mp4",
            type = LayerType.VIDEO,
            mediaWidth = 1080,
            mediaHeight = 1920,
            sourceDurationMs = 8400L,
            reversed = true,
            timeRemapEnabled = true,
            audioMuted = true,
            motionBlurEnabled = true,
            shutterAngle = 240f,
            isClippingMask = true,
            pivotX = AnimatableProperty(24f)
        )
        val audioLayer = Layer(
            id = "aud_1",
            name = "Clip.mp4 (Audio)",
            type = LayerType.AUDIO,
            extractedWaveform = listOf(0.2f, 0.75f, 0.9f, 0.4f)
        )

        val envelope = ProjectPayloadSerializer.encodeProjectEnvelope(
            layers = listOf(videoLayer, audioLayer),
            backgroundColor = "Transparent"
        )
        assertEquals("Transparent", ProjectPayloadSerializer.decodeBackgroundColor(envelope))
        val decoded = ProjectPayloadSerializer.decodeLayers(envelope)
        assertEquals(2, decoded.size)

        val restoredVideo = decoded[0]
        assertEquals(1080, restoredVideo.mediaWidth)
        assertEquals(1920, restoredVideo.mediaHeight)
        assertTrue(restoredVideo.reversed)
        assertTrue(restoredVideo.timeRemapEnabled)
        assertTrue(restoredVideo.audioMuted)
        assertTrue(restoredVideo.motionBlurEnabled)
        assertEquals(240f, restoredVideo.shutterAngle, 0.1f)
        assertTrue(restoredVideo.isClippingMask)
        assertEquals(24f, restoredVideo.pivotX.baseValue, 0.1f)

        val restoredAudio = decoded[1]
        assertEquals(LayerType.AUDIO, restoredAudio.type)
        assertEquals(4, restoredAudio.extractedWaveform.size)
        assertEquals(0.75f, restoredAudio.extractedWaveform[1], 0.01f)
    }
}
