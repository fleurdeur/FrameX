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

    @Test
    fun `verify all 5 effect parameter slots keyframes overshoot bezier holdStep and serialization`() {
        val audio = AudioReactorEngine().sampleTimelineSpectrum(0L, 128, emptyList())
        val glowDef = EffectsCatalog.findById("S_Glow")
        assertEquals(5, glowDef.paramSpecs().size)

        val baseFx = glowDef.toNode(customIntensity = 0.8f)
        val animatedFx = baseFx.copy(
            tintColorHex = "#FF3366",
            primaryProp = AnimatableProperty(
                baseValue = 0.2f,
                keyframes = listOf(
                    // Overshoot Bezier handle above 1.0 (outTangentY = 1.38f)
                    Keyframe(
                        timeMs = 0L,
                        value = 0.2f,
                        outTangentX = 0.2f,
                        outTangentY = 1.38f,
                        inTangentX = 0.7f,
                        inTangentY = 1.0f
                    ),
                    Keyframe(timeMs = 2000L, value = 1.0f)
                )
            ),
            angleProp = AnimatableProperty(
                baseValue = 0f,
                keyframes = listOf(
                    // Hold / Step keyframe holds 0.25 until 2000ms
                    Keyframe(timeMs = 0L, value = 0.25f, holdStep = true),
                    Keyframe(timeMs = 2000L, value = 1.50f)
                )
            ),
            mixProp = AnimatableProperty(
                baseValue = 100f,
                keyframes = listOf(
                    Keyframe(timeMs = 0L, value = 100f),
                    Keyframe(timeMs = 2000L, value = 50f)
                )
            )
        )

        val midEval = AnimationEvaluator.evaluateEffectNode(animatedFx, 1000L, audio)
        assertTrue("Overshoot Bezier should evaluate cleanly above linear midpoint", midEval.primary > 0.6f)
        assertEquals("Hold/Step keyframe should hold initial value prior to next keyframe", 0.25f, midEval.angleDeg, 0.001f)
        assertEquals(0.75f, midEval.mixFraction, 0.03f)

        val endEval = AnimationEvaluator.evaluateEffectNode(animatedFx, 2000L, audio)
        assertEquals(1.50f, endEval.angleDeg, 0.001f)

        // Verify full envelope roundtrip of 5-channel EffectNode keyframes, holdStep, and tintColorHex
        val layer = Layer(id = "fx_layer", effects = listOf(animatedFx))
        val envelope = ProjectPayloadSerializer.encodeProjectEnvelope(listOf(layer), "#000000")
        val restoredLayer = ProjectPayloadSerializer.decodeLayers(envelope).single()
        val restoredFx = restoredLayer.effects.single()
        assertEquals("#FF3366", restoredFx.tintColorHex)
        assertEquals(2, restoredFx.primaryProp.keyframes.size)
        assertEquals(1.38f, restoredFx.primaryProp.keyframes.first().outTangentY, 0.001f)
        assertTrue(restoredFx.angleProp.keyframes.first().holdStep)
        assertEquals(2, restoredLayer.allKeyframeTimes().size)
    }

    @Test
    fun `verify smart bookmark navigation goes to zero when no earlier bookmark exists and jumps to bookmark when present`() {
        val vm = com.example.viewmodel.StudioViewModel()
        vm.createNewProject(
            name = "Bookmark & FX Test",
            preset = AspectRatioPreset.PORTRAIT_9_16,
            customWidth = 1080,
            customHeight = 1920,
            fps = 60,
            durationSec = 10,
            bpm = 120
        )

        // 1. No bookmarks added yet: scrubbing to 4200ms and pressing Previous beside Play goes back to 0!
        vm.scrubPlayhead(4200L)
        assertEquals(4200L, vm.state.value.playheadMs)
        vm.jumpToBookmark(forward = false)
        assertEquals(0L, vm.state.value.playheadMs)

        // Forward with no bookmarks jumps to end of composition (10000ms)
        vm.jumpToBookmark(forward = true)
        assertEquals(10000L, vm.state.value.playheadMs)

        // 2. Add two bookmarks at 1500ms and 6800ms
        vm.scrubPlayhead(1500L)
        val snappedFirst = vm.state.value.playheadMs
        vm.toggleBeatBookmarkAtPlayhead()
        vm.scrubPlayhead(6800L)
        val snappedSecond = vm.state.value.playheadMs
        vm.toggleBeatBookmarkAtPlayhead()

        // Scrub to 4500ms: Previous jumps to first bookmark, then Previous again goes to 0ms!
        vm.scrubPlayhead(4500L)
        vm.jumpToBookmark(forward = false)
        assertEquals(snappedFirst, vm.state.value.playheadMs)
        vm.jumpToBookmark(forward = false)
        assertEquals(0L, vm.state.value.playheadMs)

        // Forward from 0ms jumps to first bookmark -> second bookmark -> 10000ms end
        vm.jumpToBookmark(forward = true)
        assertEquals(snappedFirst, vm.state.value.playheadMs)
        vm.jumpToBookmark(forward = true)
        assertEquals(snappedSecond, vm.state.value.playheadMs)
        vm.jumpToBookmark(forward = true)
        assertEquals(10000L, vm.state.value.playheadMs)

        // 3. Verify adding an Effect opens EFFECT_DETAIL inspector & supports per-effect keyframes + curves
        vm.scrubPlayhead(0L)
        vm.addShapeLayerWithKind("Star")
        val glow = EffectsCatalog.findById("S_Glow")
        vm.addEffectToSelectedLayer(glow)
        assertEquals(com.example.viewmodel.AmInspectorTab.EFFECT_DETAIL, vm.state.value.inspectorTab)
        val fxId = vm.state.value.selectedEffectId
        assertTrue("Selected effect ID should be populated after adding effect", !fxId.isNullOrBlank())

        vm.selectEffectParamSlot(com.example.data.model.EffectParamSlot.FREQUENCY)
        vm.scrubPlayhead(0L)
        vm.toggleEffectKeyframeAtPlayhead()
        vm.scrubPlayhead(2000L)
        vm.updateSelectedEffectParamValue(1.45f)

        vm.scrubPlayhead(500L)
        vm.setInspectorTab(com.example.viewmodel.AmInspectorTab.EFFECT_EASING)
        assertEquals(com.example.viewmodel.AmInspectorTab.EFFECT_EASING, vm.state.value.inspectorTab)

        vm.updateEffectBezierHandles(0.18f, 1.25f, 0.72f, 0.95f)
        vm.copyActiveBezierCurve(isEffect = true)
        assertTrue(vm.state.value.hasCopiedCurve)

        val updatedLayer = vm.state.value.activeProject.layers.single()
        val updatedFx = updatedLayer.effects.single()
        assertEquals(2, updatedFx.frequencyProp.keyframes.size)
        assertEquals(1.25f, updatedFx.frequencyProp.keyframes.first().outTangentY, 0.001f)
    }

    @Test
    fun `verify preview quality switching loop toggle playback RAM cache prerender and edit invalidation`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        com.example.engine.PlaybackCacheEngine.clearAll()

        val vm = com.example.viewmodel.StudioViewModel(appContext = context)
        vm.createNewProject(
            name = "Cache & Quality Optimization Test",
            preset = AspectRatioPreset.SQUARE_1_1,
            customWidth = 720,
            customHeight = 720,
            fps = 24,
            durationSec = 2,
            bpm = 120
        )

        // 1. Switch PreviewQuality tiers
        vm.setPreviewQuality(com.example.data.preferences.PreviewQuality.QUARTER)
        assertEquals(com.example.data.preferences.PreviewQuality.QUARTER, vm.state.value.previewQuality)

        // 2. Toggle Loop playback (accessible via long-pressing Play)
        val initialLoop = vm.state.value.isLoopPlayback
        vm.toggleLoopPlayback()
        assertEquals(!initialLoop, vm.state.value.isLoopPlayback)

        // 3. Add a layer + effect, then pre-render composition frames into RAM cache
        vm.addShapeLayerWithKind("Star")
        vm.addEffectToSelectedLayer(EffectsCatalog.findById("S_Glow"))
        val revBeforeRender = vm.state.value.projectRevision

        kotlinx.coroutines.runBlocking {
            com.example.engine.PlaybackCacheEngine.preRenderComposition(
                context = context,
                project = vm.state.value.activeProject,
                projectRevision = revBeforeRender,
                quality = com.example.data.preferences.PreviewQuality.QUARTER,
                fpsCap = 24,
                audioEngine = vm.audioEngine
            )
        }

        val cachedFrame = com.example.engine.PlaybackCacheEngine.getCachedFrame(
            projectRevision = revBeforeRender,
            quality = com.example.data.preferences.PreviewQuality.QUARTER,
            timeMs = 0L,
            fps = 24
        )
        assertTrue("Expected RAM-cached frame bitmap at 0ms after pre-render", cachedFrame != null && cachedFrame.width > 0)
        assertTrue(com.example.engine.PlaybackCacheEngine.preRenderState.value.cachedFramesCount > 0)

        // 4. Editing the layer increments projectRevision and invalidates stale cached frames
        val layerId = vm.state.value.selectedLayerId!!
        vm.updateLayerColor(layerId, "#FF3366")
        val revAfterEdit = vm.state.value.projectRevision
        assertTrue("Project revision should advance on edit", revAfterEdit > revBeforeRender)
        val missOnNewRevision = com.example.engine.PlaybackCacheEngine.getCachedFrame(
            projectRevision = revAfterEdit,
            quality = com.example.data.preferences.PreviewQuality.QUARTER,
            timeMs = 0L,
            fps = 24
        )
        assertNull("New project revision must not serve stale cached frames", missOnNewRevision)
    }

    @Test
    fun `verify effect deletion low res selected layer effect thumbnail preview and studio settings persistence`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val vm = com.example.viewmodel.StudioViewModel(appContext = context)
        vm.addShapeLayerWithKind("Circle")
        val layerId = vm.state.value.selectedLayerId!!

        // 1. Add two effects, delete one from EFFECT_DETAIL, and verify clean return to EFFECTS tab
        val fxGlow = EffectsCatalog.findById("S_Glow")
        val fxRgb = EffectsCatalog.findById("RGB_Split")
        vm.addEffectToSelectedLayer(fxGlow)
        vm.addEffectToSelectedLayer(fxRgb)
        assertEquals(2, vm.state.value.activeProject.layers.single().effects.size)
        assertEquals(com.example.viewmodel.AmInspectorTab.EFFECT_DETAIL, vm.state.value.inspectorTab)

        val secondFxId = vm.state.value.selectedEffectId!!
        vm.removeEffectNode(layerId, secondFxId)
        assertEquals(1, vm.state.value.activeProject.layers.single().effects.size)
        assertEquals(com.example.viewmodel.AmInspectorTab.EFFECTS, vm.state.value.inspectorTab)

        // Clear all remaining effects on layer
        vm.clearAllEffectsOnLayer(layerId)
        assertTrue(vm.state.value.activeProject.layers.single().effects.isEmpty())

        // 2. Verify Effect Browser low-res (56x56) selected-layer + effect thumbnail previewer
        val selectedLayer = vm.state.value.activeProject.layers.single()
        val thumbBmp = com.example.engine.EffectThumbnailPreviewer.generateThumbnailSync(
            context = context,
            selectedLayer = selectedLayer,
            effect = fxGlow
        )
        assertEquals(56, thumbBmp.width)
        assertEquals(56, thumbBmp.height)

        // 3. Verify Studio Settings persistence (codec, bitrate, preview quality, RAM cache budget)
        vm.updateStudioPreferences {
            it.copy(
                preferredVideoCodec = "H.265",
                exportBitrateProfile = com.example.data.preferences.BitrateProfile.HIGH_32M,
                defaultPreviewQuality = com.example.data.preferences.PreviewQuality.EIGHTH,
                playbackCacheLimitMb = 256
            )
        }
        val reloadedRepo = com.example.data.preferences.AppPreferencesRepository(context)
        assertEquals("H.265", reloadedRepo.preferences.value.preferredVideoCodec)
        assertEquals(com.example.data.preferences.BitrateProfile.HIGH_32M, reloadedRepo.preferences.value.exportBitrateProfile)
        assertEquals(com.example.data.preferences.PreviewQuality.EIGHTH, reloadedRepo.preferences.value.defaultPreviewQuality)
        assertEquals(256, reloadedRepo.preferences.value.playbackCacheLimitMb)
    }

    @Test
    fun `verify fast zero copy media import live OES video gating lookahead prerender and glitch free audio video lifecycle`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        com.example.engine.PlaybackCacheEngine.clearAll()

        // 1. Verify HardwareVideoPreviewEngine OES stream eligibility vs nonlinear video modes
        val linearVideo = Layer(
            id = "vid_oes_1",
            type = LayerType.VIDEO,
            mediaUri = "file:///tmp/sample.mp4",
            visible = true
        )
        assertTrue(com.example.engine.HardwareVideoPreviewEngine.isEligibleForLiveOesStream(linearVideo))
        assertTrue(!com.example.engine.HardwareVideoPreviewEngine.isEligibleForLiveOesStream(linearVideo.copy(reversed = true)))
        assertTrue(!com.example.engine.HardwareVideoPreviewEngine.isEligibleForLiveOesStream(linearVideo.copy(timeRemapEnabled = true)))
        assertTrue(!com.example.engine.HardwareVideoPreviewEngine.isEligibleForLiveOesStream(linearVideo.copy(freezeFrameMs = 500L)))

        // 2. Verify fast direct file:// image import without redundant whole-file copying
        val tmpImgFile = java.io.File(context.cacheDir, "fast_import_probe.png")
        val testBmp = android.graphics.Bitmap.createBitmap(128, 96, android.graphics.Bitmap.Config.ARGB_8888)
        testBmp.eraseColor(android.graphics.Color.CYAN)
        java.io.FileOutputStream(tmpImgFile).use { out ->
            testBmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 90, out)
        }
        val fileUri = android.net.Uri.fromFile(tmpImgFile)

        val importedInfo = kotlinx.coroutines.runBlocking {
            com.example.engine.MediaEngine.importMediaUri(
                context = context,
                pickedUri = fileUri,
                type = LayerType.IMAGE
            )
        }
        assertEquals("Direct readable file:// URI should stream directly without slow copy", fileUri.toString(), importedInfo.localUri)
        assertEquals(128, importedInfo.width)
        assertEquals(96, importedInfo.height)

        // 3. Verify lookahead pre-render window + multi-core throughput reporting
        val vm = com.example.viewmodel.StudioViewModel(appContext = context)
        vm.createNewProject(
            name = "Lookahead & Throughput Test",
            preset = AspectRatioPreset.LANDSCAPE_16_9,
            customWidth = 640,
            customHeight = 360,
            fps = 30,
            durationSec = 3,
            bpm = 128
        )
        vm.addShapeLayerWithKind("Rounded Rect")
        vm.addEffectToSelectedLayer(EffectsCatalog.findById("Chromatic_Aberration"))
        val rev = vm.state.value.projectRevision

        kotlinx.coroutines.runBlocking {
            com.example.engine.PlaybackCacheEngine.preRenderLookaheadWindow(
                context = context,
                project = vm.state.value.activeProject,
                projectRevision = rev,
                quality = com.example.data.preferences.PreviewQuality.QUARTER,
                fpsCap = 30,
                startPlayheadMs = 500L,
                windowDurationMs = 1000L,
                audioEngine = vm.audioEngine
            )
        }

        val lookaheadHit = com.example.engine.PlaybackCacheEngine.getCachedFrame(
            projectRevision = rev,
            quality = com.example.data.preferences.PreviewQuality.QUARTER,
            timeMs = 500L,
            fps = 30
        )
        assertTrue("Lookahead auto-cache should populate frame at playhead", lookaheadHit != null)

        // 4. Verify safe ExoPlayer audio & video controller prewarm / sync / release lifecycle
        val audioController = com.example.engine.TimelineAudioController(context)
        audioController.prewarmProjectAudio(vm.state.value.activeProject)
        audioController.syncPlayback(vm.state.value.activeProject, 250L, isPlaying = false)
        audioController.pauseAll()
        audioController.releaseAll()

        com.example.engine.HardwareVideoPreviewEngine.prewarmProjectVideos(context, vm.state.value.activeProject, 0L)
        com.example.engine.HardwareVideoPreviewEngine.pauseAll()
        com.example.engine.HardwareVideoPreviewEngine.releaseAll()
        tmpImgFile.delete()
    }
}

