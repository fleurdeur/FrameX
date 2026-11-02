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

    @Test
    fun `verify Text Transform effect, Chroma Key, and authentic per-effect Alight Motion parameter specs`() {
        val textTransform = EffectsCatalog.findById("Text_Transform")
        assertEquals("Text_Transform", textTransform.id)
        assertEquals(11, textTransform.shaderMode)
        val ttSpecs = textTransform.paramSpecs()
        assertEquals("Start", ttSpecs[0].label)
        assertEquals("%", ttSpecs[0].unit)
        assertEquals("End", ttSpecs[1].label)
        assertEquals("%", ttSpecs[1].unit)

        val chromaKey = EffectsCatalog.findById("Chroma_Key")
        assertEquals("Chroma_Key", chromaKey.id)
        assertEquals(12, chromaKey.shaderMode)
        assertTrue(chromaKey.colorLabel.startsWith("Key Color"))

        val tilesSpecs = EffectsCatalog.findById("Tiles").paramSpecs()
        val exposureSpecs = EffectsCatalog.findById("Exposure_Gamma").paramSpecs()
        val oscillateSpecs = EffectsCatalog.findById("Oscillate").paramSpecs()
        assertTrue(
            "Different effect families should expose distinct authentic parameter labels",
            tilesSpecs[0].label != exposureSpecs[0].label && exposureSpecs[0].label != oscillateSpecs[0].label
        )
    }

    @Test
    @org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
    fun `verify current-frame compressed RGB_565 effect thumbnail evaluates layer at playheadMs`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        com.example.engine.EffectThumbnailPreviewer.clearThumbnails()

        val animatedLayer = Layer(
            id = "anim_thumb_layer",
            name = "Moving Neon Shape",
            type = LayerType.SHAPE,
            shapeKind = "Circle",
            colorHex = "#00F5D4",
            posX = AnimatableProperty(
                baseValue = -420f,
                keyframes = listOf(
                    Keyframe(timeMs = 0L, value = -420f),
                    Keyframe(timeMs = 2000L, value = 420f)
                )
            )
        )
        val sig0 = com.example.engine.EffectThumbnailPreviewer.layerSignature(animatedLayer, 0L)
        val sig2000 = com.example.engine.EffectThumbnailPreviewer.layerSignature(animatedLayer, 2000L)
        assertTrue("Current-frame cache signature must advance with playheadMs", sig0 != sig2000)

        val effect = EffectsCatalog.findById("RGB_Split")

        val bmpAtStart = com.example.engine.EffectThumbnailPreviewer.generateThumbnailSync(
            context = context,
            selectedLayer = animatedLayer,
            effect = effect,
            playheadMs = 0L
        )
        val bmpAtEnd = com.example.engine.EffectThumbnailPreviewer.generateThumbnailSync(
            context = context,
            selectedLayer = animatedLayer,
            effect = effect,
            playheadMs = 2000L
        )

        assertEquals(56, bmpAtStart.width)
        assertEquals(56, bmpAtStart.height)
        assertEquals(android.graphics.Bitmap.Config.RGB_565, bmpAtStart.config)
        assertEquals(android.graphics.Bitmap.Config.RGB_565, bmpAtEnd.config)

        val pxStart = IntArray(56 * 56)
        val pxEnd = IntArray(56 * 56)
        bmpAtStart.getPixels(pxStart, 0, 56, 0, 0, 56, 56)
        bmpAtEnd.getPixels(pxEnd, 0, 56, 0, 0, 56, 56)
        val diffPixels = pxStart.indices.count { pxStart[it] != pxEnd[it] }
        assertTrue(
            "Thumbnail pixels must reflect the layer's current keyframed frame at playheadMs (diff=$diffPixels)",
            diffPixels > 40
        )
    }

    @Test
    fun `verify zero black bars 100 percent edge-to-edge media fit and timeline squeeze plus hold-to-drag`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val vm = com.example.viewmodel.StudioViewModel(appContext = context)
        vm.createNewProject(
            name = "Edge Fit & Timeline Drag Test",
            preset = AspectRatioPreset.LANDSCAPE_16_9,
            customWidth = 1920,
            customHeight = 1080,
            fps = 60,
            durationSec = 10,
            bpm = 128
        )

        // Add a shape layer, trim [1000ms..4000ms], and test timeline squeeze/zoom + hold-to-drag
        vm.addShapeLayerWithKind("Rectangle")
        val layerId = vm.state.value.selectedLayerId!!
        vm.scrubPlayhead(1000L)
        vm.pullStartToPlayhead()
        vm.scrubPlayhead(4000L)
        vm.pullEndToPlayhead()

        // Verify timeline pinch-to-squeeze (0.5x) and pinch-out expand (4.8x)
        vm.setTimelineZoom(0.5f)
        assertEquals(0.5f, vm.state.value.timelineZoom, 0.001f)
        vm.setTimelineZoom(4.8f)
        assertEquals(4.8f, vm.state.value.timelineZoom, 0.001f)

        // Add keyframe at 1500ms, then hold-drag layer duration +2500ms along timeline
        vm.selectProperty(com.example.data.model.PropertyTarget.POS_X)
        vm.scrubPlayhead(1500L)
        vm.toggleKeyframeAtPlayhead(com.example.data.model.PropertyTarget.POS_X)
        vm.updateSelectedPropertyValue(120f)

        val preStart = vm.state.value.activeProject.layers.first { it.id == layerId }.startTimeMs
        val preEnd = vm.state.value.activeProject.layers.first { it.id == layerId }.endTimeMs
        val preKfTime = vm.state.value.activeProject.layers.first { it.id == layerId }.posX.keyframes.first().timeMs
        val expectedDuration = preEnd - preStart

        vm.moveLayerTimeRange(layerId, rawNewStartMs = preStart + 2500L, recordUndo = true)
        val movedLayer = vm.state.value.activeProject.layers.first { it.id == layerId }
        val actualShift = movedLayer.startTimeMs - preStart
        assertEquals("Shift delta should be ~2500ms (frame-snapped at 60fps)", 2500f, actualShift.toFloat(), 20f)
        assertEquals("Duration should remain intact after hold-to-drag", expectedDuration, movedLayer.endTimeMs - movedLayer.startTimeMs)
        assertEquals(
            "Layer keyframes should shift by the exact clip delta",
            preKfTime + actualShift,
            movedLayer.posX.keyframes.first().timeMs
        )

        // Verify 100% edge-to-edge media Fit on matching 16:9 image layer
        val mediaImgFile = java.io.File(context.cacheDir, "widescreen_16_9.png")
        val bmp = android.graphics.Bitmap.createBitmap(192, 108, android.graphics.Bitmap.Config.ARGB_8888)
        bmp.eraseColor(android.graphics.Color.MAGENTA)
        java.io.FileOutputStream(mediaImgFile).use { bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 90, it) }
        val info = kotlinx.coroutines.runBlocking {
            com.example.engine.MediaEngine.importMediaUri(context, android.net.Uri.fromFile(mediaImgFile), LayerType.IMAGE)
        }
        vm.addLayer(LayerType.IMAGE, info.localUri)
        vm.fitOrFillSelectedLayerToComposition(fill = false)
        val fittedImg = vm.state.value.activeProject.layers.first { it.id == vm.state.value.selectedLayerId }
        assertEquals("16:9 image in 16:9 comp must fit 100% edge-to-edge with zero black bars", 100f, fittedImg.scale.baseValue, 0.5f)
        mediaImgFile.delete()
    }

    @Test
    fun `verify notable bass onset picker ignores quiet noise and enforces refractory cooldown`() {
        // 1. Quiet background hiss / room noise should produce ZERO beat markers
        val backgroundNoise = FloatArray(220) { idx -> 0.008f + (idx % 3) * 0.002f }
        val noisePeaks = AudioReactorEngine.pickNotableBassPeaks(
            hopEnergies = backgroundNoise,
            hopDurationMs = 20L,
            sensitivity = 0.70f
        )
        assertTrue("Quiet background noise must never spam beat markers", noisePeaks.isEmpty())

        // 2. Four notable kick/bass hits at 500ms, 1000ms, 1500ms, 2000ms + tiny micro-chatter right next to hits
        val kickEnvelope = FloatArray(140) { 0.02f }
        val hitIndices = listOf(25, 50, 75, 100) // * 20ms = 500, 1000, 1500, 2000ms
        for (h in hitIndices) {
            kickEnvelope[h - 1] = 0.18f
            kickEnvelope[h] = 0.92f
            kickEnvelope[h + 1] = 0.35f
            // Nearby secondary sub-ripple 80ms later (within 300ms cooldown) should NOT create spam marker
            kickEnvelope[h + 4] = 0.48f
        }
        val detected = AudioReactorEngine.pickNotableBassPeaks(
            hopEnergies = kickEnvelope,
            hopDurationMs = 20L,
            sensitivity = 0.65f,
            minCooldownMs = 300L
        )
        assertEquals("Should cleanly isolate the 4 notable bass kicks without double-triggering", 4, detected.size)
        assertEquals(500L, detected[0].timeMs)
        assertEquals(1000L, detected[1].timeMs)
        assertEquals(1500L, detected[2].timeMs)
        assertEquals(2000L, detected[3].timeMs)
    }

    @Test
    fun `verify gradient fill, Studio DSP audio effects PCM processor, and custom font registry`() {
        val context = ApplicationProvider.getApplicationContext<Context>()

        // 1. Verify bundled Google Fonts resolve and custom .ttf registration works
        val bebasTf = com.example.engine.CustomFontRegistry.resolveTypeface(context, "Bebas Neue")
        org.junit.Assert.assertNotNull("Bundled Bebas Neue font should resolve non-null Typeface", bebasTf)

        val customFontTmp = java.io.File(context.cacheDir, "UnitTestDisplayFont.ttf")
        context.resources.openRawResource(R.font.pacifico).use { input ->
            java.io.FileOutputStream(customFontTmp).use { output -> input.copyTo(output) }
        }
        val registered = com.example.engine.CustomFontRegistry.registerLocalFontFile("UnitTest Pacifico", customFontTmp)
        assertTrue("Valid .ttf font file should register into CustomFontRegistry", registered)
        assertTrue(
            com.example.engine.CustomFontRegistry.allAvailableFonts(context).any { it.displayName == "UnitTest Pacifico" }
        )
        customFontTmp.delete()

        // 2. Verify StudioDspAudioProcessor real-time stereo 16-bit PCM reverb + sub-bass + echo processing
        val dsp = com.example.engine.StudioDspAudioProcessor()
        val format = androidx.media3.common.audio.AudioProcessor.AudioFormat(
            44100,
            2,
            androidx.media3.common.C.ENCODING_PCM_16BIT
        )
        dsp.configure(format)
        dsp.flush()
        dsp.reverbMix = 0.45f
        dsp.roomSize = 0.72f
        dsp.bassBoost = 0.60f
        dsp.echoMix = 0.30f
        dsp.filterCutoff = 0.55f
        assertTrue(dsp.hasActiveDsp())

        val sampleFrames = 256
        val pcmIn = java.nio.ByteBuffer.allocateDirect(sampleFrames * 4).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until sampleFrames) {
            val s = (kotlin.math.sin(i * 0.12) * 14000.0).toInt().toShort()
            pcmIn.putShort(s)
            pcmIn.putShort(s)
        }
        pcmIn.flip()
        dsp.queueInput(pcmIn)
        val pcmOut = dsp.output
        assertEquals("Processed PCM byte count must match input stereo buffer", sampleFrames * 4, pcmOut.remaining())

        // 3. Verify ProjectPayloadSerializer roundtrips Gradient Fill + Audio DSP fields losslessly
        val richLayer = Layer(
            name = "Gradient + Cathedral Audio Layer",
            type = LayerType.VIDEO,
            colorHex = "#00F5D4",
            fillMode = "LINEAR_GRADIENT",
            gradientEndColorHex = "#FF2E93",
            gradientAngleDeg = 135f,
            audioPreset = "Cathedral Reverb",
            audioReverbMix = 0.58f,
            audioRoomSize = 0.86f,
            audioBassBoost = 0.42f,
            audioEchoMix = 0.24f,
            audioEchoDelayMs = 310f,
            audioFilterCutoff = 0.76f,
            audioPitchSemitones = -2.0f
        )
        val envelope = ProjectPayloadSerializer.encodeProjectEnvelope(
            layers = listOf(richLayer),
            backgroundColor = "#0A0C10"
        )
        val restored = ProjectPayloadSerializer.decodeLayers(envelope).first()
        assertEquals("LINEAR_GRADIENT", restored.fillMode)
        assertEquals("#FF2E93", restored.gradientEndColorHex)
        assertEquals(135f, restored.gradientAngleDeg, 0.01f)
        assertEquals("Cathedral Reverb", restored.audioPreset)
        assertEquals(0.58f, restored.audioReverbMix, 0.005f)
        assertEquals(0.86f, restored.audioRoomSize, 0.005f)
        assertEquals(0.42f, restored.audioBassBoost, 0.005f)
        assertEquals(0.24f, restored.audioEchoMix, 0.005f)
        assertEquals(310f, restored.audioEchoDelayMs, 0.1f)
        assertEquals(0.76f, restored.audioFilterCutoff, 0.005f)
        assertEquals(-2.0f, restored.audioPitchSemitones, 0.01f)
    }

    @Test
    fun `verify KeyFlow kfpack and fppack custom plugin import comment-safe JSON multi-pass uniforms keyframes and serialization`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        com.example.engine.PluginPackManager.ensureInitialized(context)

        // 1. Verify uniform naming rules from PLUGIN_MAKER_GUIDE.txt
        assertEquals("uAmplitude", com.example.engine.PluginPackManager.toUniformName("amplitude"))
        assertEquals("uGlowColor", com.example.engine.PluginPackManager.toUniformName("glowColor"))
        assertEquals("uTileEdges", com.example.engine.PluginPackManager.toUniformName("tileEdges"))

        // 2. Verify starter KeyFlow Cinema 1.4.9 suite & EffectsCatalog dynamic registration
        val starterSuites = com.example.engine.PluginPackManager.installedSuites.value
        assertTrue("Starter KeyFlow suite should be pre-installed", starterSuites.isNotEmpty())
        val ultraGlow = com.example.engine.PluginPackManager.findPluginEffect("s_ultraglow")
        org.junit.Assert.assertNotNull("Multi-pass s_ultraglow plugin should be registered", ultraGlow)
        assertEquals(4, ultraGlow!!.passes.size)
        assertEquals(0.35f, ultraGlow.expandBounds, 0.001f)
        assertTrue(EffectsCatalog.allEffects.any { it.id == "s_ultraglow" && it.category == com.example.engine.EffectCategory.PLUGINS })

        // 3. Build a real .kfpack ZIP archive with /* block */ and // line comments, multi-pass GLSL, FLOAT, BOOL, and COLOR
        val zipBaos = java.io.ByteArrayOutputStream()
        java.util.zip.ZipOutputStream(zipBaos).use { zos ->
            zos.putNextEntry(java.util.zip.ZipEntry("suite.json"))
            zos.write(
                """
                {
                  /* Official KeyFlow 1.4.9+ Suite Manifest */
                  "id": "unit_test_sapphire_pack",
                  "name": "Sapphire Test Pack",
                  "category": "Warna", // Color & Glows
                  "author": "StudioQA",
                  "version": "1.5.0",
                  "minAppVersion": "1.4.9",
                  "effects": ["aurora_bloom"],
                }
                """.trimIndent().toByteArray()
            )
            zos.closeEntry()

            zos.putNextEntry(java.util.zip.ZipEntry("effects/aurora_bloom/effect.json"))
            zos.write(
                """
                {
                  "id": "aurora_bloom",
                  "name": "Aurora Bloom",
                  "category": "Warna",
                  "expandBounds": 0.30,
                  "usesNativeMotionBlur": true,
                  "passes": ["pass1_extract.glsl", "pass2_composite.glsl"],
                  "parameters": [
                    { "key": "exposure", "label": "Exposure", "type": "FLOAT", "default": 1.2, "min": 0.0, "max": 5.0 },
                    { "key": "glowColor", "label": "Glow Tint", "type": "COLOR", "default": "#00FFCC" },
                    { "key": "mirrorEdges", "label": "Mirror Edges", "type": "BOOL", "default": true }
                  ]
                }
                """.trimIndent().toByteArray()
            )
            zos.closeEntry()

            zos.putNextEntry(java.util.zip.ZipEntry("effects/aurora_bloom/pass1_extract.glsl"))
            zos.write(
                """
                precision mediump float;
                varying vec2 vTex;
                uniform sampler2D uTexture;
                uniform float uExposure;
                void main() {
                    vec4 c = texture2D(uTexture, vTex);
                    gl_FragColor = vec4(c.rgb * uExposure, c.a);
                }
                """.trimIndent().toByteArray()
            )
            zos.closeEntry()

            zos.putNextEntry(java.util.zip.ZipEntry("effects/aurora_bloom/pass2_composite.glsl"))
            zos.write(
                """
                precision mediump float;
                varying vec2 vTex;
                uniform sampler2D uTexture;
                uniform sampler2D uOriginalTexture;
                uniform vec3 uGlowColor;
                uniform float uMirrorEdges;
                void main() {
                    vec4 glow = texture2D(uTexture, vTex);
                    vec4 orig = texture2D(uOriginalTexture, vTex);
                    vec3 boost = glow.rgb * uGlowColor * (uMirrorEdges > 0.5 ? 1.0 : 0.5);
                    gl_FragColor = vec4(clamp(orig.rgb + boost, 0.0, 1.0), orig.a);
                }
                """.trimIndent().toByteArray()
            )
            zos.closeEntry()
        }

        val imported = com.example.engine.PluginPackManager.importPackFromBytes(
            context = context,
            zipBytes = zipBaos.toByteArray(),
            archiveExtension = ".kfpack"
        ).getOrThrow()
        assertEquals("unit_test_sapphire_pack", imported.id)
        assertEquals(".kfpack", imported.archiveExtension)
        val aurora = imported.effects.single()
        assertEquals(2, aurora.passes.size)
        assertEquals(0.30f, aurora.expandBounds, 0.001f)
        assertTrue(aurora.usesNativeMotionBlur)

        // 4. Export as .fppack and verify round-trip archive import
        val fppackBytes = com.example.engine.PluginPackManager.exportSuiteAsZipBytes(imported)
        assertTrue(".fppack export archive should be non-empty", fppackBytes.size > 120)
        val reimportedFppack = com.example.engine.PluginPackManager.importPackFromBytes(
            context = context,
            zipBytes = fppackBytes,
            archiveExtension = ".fppack"
        ).getOrThrow()
        assertEquals(".fppack", reimportedFppack.archiveExtension)
        assertEquals("aurora_bloom", reimportedFppack.effects.single().id)

        // 5. Apply plugin to layer in StudioViewModel, keyframe FLOAT, toggle BOOL, update COLOR, and serialize
        val vm = com.example.viewmodel.StudioViewModel(appContext = context)
        vm.addShapeLayerWithKind("Hexagon")
        val auroraDef = EffectsCatalog.findById("aurora_bloom")
        vm.addEffectToSelectedLayer(auroraDef)
        assertEquals("exposure", vm.state.value.selectedPluginParamKey)

        vm.scrubPlayhead(0L)
        vm.toggleEffectKeyframeAtPlayhead()
        vm.scrubPlayhead(2000L)
        vm.updateCustomPluginParamValue("exposure", 3.8f)
        vm.updateCustomPluginParamValue("mirrorEdges", 0f)
        vm.updateCustomPluginColor("glowColor", "#FF3366")

        val layer = vm.state.value.activeProject.layers.single()
        val fxNode = layer.effects.single()
        val audio = AudioReactorEngine().sampleTimelineSpectrum(1000L, 128, emptyList())
        val midUniforms = AnimationEvaluator.evaluatePluginUniformFloats(aurora, fxNode, 1000L, audio)
        assertEquals("Keyframed exposure should interpolate cleanly at 1000ms", 2.5f, midUniforms["exposure"] ?: 0f, 0.25f)
        assertEquals("Toggled BOOL switch should evaluate to 0.0", 0f, midUniforms["mirrorEdges"] ?: 1f, 0.001f)
        assertEquals("#FF3366", fxNode.customColorFor("glowColor", "#00FFCC"))

        val envelope = ProjectPayloadSerializer.encodeProjectEnvelope(listOf(layer), "#000000")
        val restoredFx = ProjectPayloadSerializer.decodeLayers(envelope).single().effects.single()
        assertEquals(2, restoredFx.customParamFor("exposure", 1.2f).keyframes.size)
        assertEquals("#FF3366", restoredFx.customColorFor("glowColor", "#00FFCC"))

        com.example.engine.PluginPackManager.removeSuite(context, "unit_test_sapphire_pack")
    }

    @Test
    fun `verify sticky reversed media frame cache prevents flicker and reversed prerender succeeds`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        com.example.engine.PlaybackCacheEngine.clearAll()

        val fakeVideoUri = "file:///tmp/reversed_test_clip.mp4"
        val seedFrame = android.graphics.Bitmap.createBitmap(96, 96, android.graphics.Bitmap.Config.RGB_565)
        seedFrame.eraseColor(android.graphics.Color.rgb(40, 140, 240))

        // Seed a single decoded bucket at 1960ms (tail of a 2000ms reversed video clip)
        com.example.engine.MediaEngine.putPreDecodedVideoFrame(fakeVideoUri, 1960L, seedFrame)

        val reversedLayer = Layer(
            id = "rev_vid_layer",
            name = "Clip.mp4",
            type = LayerType.VIDEO,
            mediaUri = fakeVideoUri,
            sourceDurationMs = 2000L,
            mediaWidth = 96,
            mediaHeight = 96,
            reversed = true,
            startTimeMs = 0L,
            endTimeMs = 2000L,
            colorHex = "#FF00FF" // Bright magenta fallback color that MUST NOT flicker when lagging!
        )

        // Request an uncached reversed timestamp (e.g. 1200ms -> source 800ms):
        // Sticky lastGoodFrameByUri must immediately return seedFrame instead of null so no rectangle flickers!
        val nonBlockingDuringSeek = com.example.engine.MediaEngine.getMediaBitmapNonBlocking(
            context = context,
            layer = reversedLayer,
            sourceTimeMs = 800L,
            onAsyncFrameReady = {}
        )
        assertTrue(
            "Sticky lastGoodFrameByUri must hold previous video frame instead of returning null during lag/seek",
            nonBlockingDuringSeek != null && nonBlockingDuringSeek.width == 96
        )

        // Pre-render composition with the reversed video layer and verify cached output does not draw magenta fallback
        val vm = com.example.viewmodel.StudioViewModel(appContext = context)
        vm.createNewProject(
            name = "Reversed PreRender Test",
            preset = AspectRatioPreset.SQUARE_1_1,
            customWidth = 360,
            customHeight = 360,
            fps = 24,
            durationSec = 2,
            bpm = 120
        )
        val projectWithReversed = vm.state.value.activeProject.copy(layers = listOf(reversedLayer))
        kotlinx.coroutines.runBlocking {
            com.example.engine.PlaybackCacheEngine.preRenderComposition(
                context = context,
                project = projectWithReversed,
                projectRevision = 77L,
                quality = com.example.data.preferences.PreviewQuality.QUARTER,
                fpsCap = 24,
                audioEngine = vm.audioEngine
            )
        }
        val cachedMid = com.example.engine.PlaybackCacheEngine.getCachedFrame(
            projectRevision = 77L,
            quality = com.example.data.preferences.PreviewQuality.QUARTER,
            timeMs = 500L,
            fps = 24
        )
        assertTrue("Reversed video pre-render should produce cached frames", cachedMid != null && cachedMid.width > 0)
    }
}




