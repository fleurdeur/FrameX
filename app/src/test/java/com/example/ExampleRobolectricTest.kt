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
import com.example.engine.isFileNetworkAllowed
import com.example.engine.isVisibleInEditorMenu
import com.example.engine.timelineStatus
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
        assertEquals("Chronos", appName)
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
        assertEquals(6, glowDef.paramSpecs().size)
        assertEquals(com.example.data.model.EffectParamSlot.FEATHER, glowDef.paramSpecs().last().slot)

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

    @Test
    @org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
    fun `verify unclick deselect unload unbounded move specific reset text animator gating stroke shadow beat baking and strict export duration`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val vm = com.example.viewmodel.StudioViewModel(appContext = context)
        vm.createNewProject(
            name = "KeyFlow Blue Upgrade Suite",
            preset = AspectRatioPreset.PORTRAIT_9_16,
            customWidth = 1080,
            customHeight = 1920,
            fps = 60,
            durationSec = 10,
            bpm = 128
        )

        // 1. Layer Click -> Unclick Unload: tapping same layer again or deselectLayer() fully unloads selection & returns to TIMELINE
        vm.addShapeLayerWithKind("Rectangle")
        val shapeId = vm.state.value.selectedLayerId!!
        assertEquals(com.example.viewmodel.AmInspectorTab.LAYER_OVERVIEW, vm.state.value.inspectorTab)

        vm.selectLayer(shapeId) // Tapping the already-selected layer unclicks/unloads it!
        assertNull("Tapping selected layer again must unload selectedLayerId", vm.state.value.selectedLayerId)
        assertEquals(com.example.viewmodel.AmInspectorTab.TIMELINE, vm.state.value.inspectorTab)

        vm.selectLayer(shapeId)
        assertEquals(shapeId, vm.state.value.selectedLayerId)
        vm.collapseLayerSheetToTimeline()
        assertNull("Collapsing layer sheet must also unload selectedLayerId", vm.state.value.selectedLayerId)

        // 2. Unbounded Move beyond -1500 & Specific Per-Modification Reset (does NOT wipe Scale/Rotation)
        vm.selectLayer(shapeId)
        vm.selectProperty(com.example.data.model.PropertyTarget.POS_X)
        vm.updateSelectedPropertyValue(-4250f)
        vm.selectProperty(com.example.data.model.PropertyTarget.SCALE)
        vm.updateSelectedPropertyValue(185f)
        vm.selectProperty(com.example.data.model.PropertyTarget.ROTATION_Z)
        vm.updateSelectedPropertyValue(42f)

        val movedShape = vm.state.value.activeProject.layers.first { it.id == shapeId }
        assertEquals("Move X must go freely beyond -1500 without clamping", -4250f, movedShape.posX.baseValue, 0.5f)
        assertEquals(185f, movedShape.scale.baseValue, 0.5f)

        // Reset ONLY Move Sub-Mode 0 (Position) — Scale and Rotation must remain untouched!
        vm.resetMoveSubMode(0)
        val afterPosReset = vm.state.value.activeProject.layers.first { it.id == shapeId }
        assertEquals(0f, afterPosReset.posX.baseValue, 0.01f)
        assertEquals("Specific Position reset must NOT reset Scale", 185f, afterPosReset.scale.baseValue, 0.5f)
        assertEquals("Specific Position reset must NOT reset Rotation", 42f, afterPosReset.rotationZ.baseValue, 0.5f)

        // 3. Unbounded Timeline Duration & Stack Reorder via moveLayerToIndex
        vm.moveLayerTimeRange(shapeId, rawNewStartMs = 24_000L, recordUndo = true)
        val farLayer = vm.state.value.activeProject.layers.first { it.id == shapeId }
        assertTrue("Layer must move freely beyond initial 10s duration (start=${farLayer.startTimeMs})", farLayer.startTimeMs >= 23_900L)
        assertEquals(
            "Strict edit export duration must match actual last layer end time",
            farLayer.endTimeMs,
            vm.state.value.activeProject.effectiveEditDurationMs()
        )

        vm.addLayer(LayerType.TEXT, "")
        val textId = vm.state.value.selectedLayerId!!
        assertEquals(2, vm.state.value.activeProject.layers.size)
        vm.moveLayerToIndex(shapeId, 1)
        assertEquals("Drag reorder handle should move shape layer to top index 1", shapeId, vm.state.value.activeProject.layers[1].id)

        // 4. Text-Only Effect Gating & KeyFlow Text Animator + Border/Stroke/Shadow Renderer
        val kfTextAnimDef = EffectsCatalog.findById("Keyflow_Text_Animator")
        assertTrue(kfTextAnimDef.isTextOnly)

        // Attempting to add Keyflow_Text_Animator onto SHAPE layer must be blocked
        vm.selectLayer(shapeId)
        vm.addEffectToSelectedLayer(kfTextAnimDef)
        assertTrue(
            "Text-only effects must NOT be added to non-TEXT layers",
            vm.state.value.activeProject.layers.first { it.id == shapeId }.effects.isEmpty()
        )

        // Adding Keyflow_Text_Animator onto TEXT layer succeeds with kf_* customParams
        vm.selectLayer(textId)
        vm.addEffectToSelectedLayer(kfTextAnimDef)
        val textLayerWithAnim = vm.state.value.activeProject.layers.first { it.id == textId }
        assertEquals(1, textLayerWithAnim.effects.size)
        assertTrue(textLayerWithAnim.effects.single().customParams.containsKey("kf_range_start"))

        // Update Stroke (INSIDE) + Drop Shadow on shape layer and test specific Stroke reset
        vm.updateLayerBorderAndShadow(
            layerId = shapeId,
            strokeEnabled = true,
            strokeWidth = 12f,
            strokeColorHex = "#4F7CFF",
            strokePosition = "INSIDE",
            shadowEnabled = true,
            shadowBlur = 22f,
            shadowColorHex = "#000000",
            shadowOffsetX = 6f,
            shadowOffsetY = 10f
        )
        vm.selectLayer(shapeId)
        vm.resetSelectedLayerStroke()
        val afterStrokeReset = vm.state.value.activeProject.layers.first { it.id == shapeId }
        assertTrue("Resetting Stroke must disable stroke", !afterStrokeReset.strokeEnabled)
        assertTrue("Resetting Stroke must preserve Shadow", afterStrokeReset.shadowEnabled)
        assertEquals(10f, afterStrokeReset.shadowOffsetY, 0.1f)

        // Render both KeyFlow Text Animator and Shape Stroke+Shadow via KeyflowTextAndBorderRenderer
        val audioSnap = vm.audioEngine.sampleTimelineSpectrum(500L, 128, emptyList())
        val shapeBmp = com.example.engine.KeyflowTextAndBorderRenderer.rasterizeShapeLayerBitmap(
            afterStrokeReset.copy(strokeEnabled = true, strokeWidth = 8f, strokePosition = "OUTSIDE")
        )
        assertTrue(shapeBmp.width > 0 && shapeBmp.height > 0)
        val evalAnimators = com.example.engine.KeyflowTextAndBorderRenderer.evaluateTextAnimators(
            layer = textLayerWithAnim,
            timeMs = 500L,
            audio = audioSnap
        )
        assertEquals(1, evalAnimators.size)
        val textBmp = com.example.engine.KeyflowTextAndBorderRenderer.rasterizeTextLayerBitmap(
            context = context,
            layer = textLayerWithAnim,
            animators = evalAnimators
        )
        assertTrue(textBmp.width > 0 && textBmp.height > 0)

        // 5. Beat Keyframe Baking & Camera View Toggle & Custom RAM / Custom Export Mbps
        vm.scrubPlayhead(500L)
        vm.toggleBeatBookmarkAtPlayhead()
        vm.scrubPlayhead(1500L)
        vm.toggleBeatBookmarkAtPlayhead()
        vm.selectLayer(textId)
        vm.bakeBeatsToPropertyKeyframes(com.example.data.model.PropertyTarget.SCALE, pulseAmount = 25f, releaseMs = 150L)
        val bakedKfs = vm.state.value.activeProject.layers.first { it.id == textId }.scale.keyframes
        assertTrue("Beat keyframe baking must generate attack + release keyframes (count=${bakedKfs.size})", bakedKfs.size >= 4)

        val initialCamPreview = vm.state.value.activeCameraPreviewEnabled
        vm.toggleActiveCameraPreview()
        assertEquals(!initialCamPreview, vm.state.value.activeCameraPreviewEnabled)

        vm.updateStudioPreferences {
            it.copy(
                useCustomRamCache = true,
                customRamCacheMb = 768,
                exportBitrateProfile = com.example.data.preferences.BitrateProfile.CUSTOM,
                customExportMbps = 42
            )
        }
        assertEquals(768, vm.preferences.value.effectiveRamCacheMb)
        assertEquals(42, vm.preferences.value.effectiveExportMbps)
    }

    @Test
    fun `verify real Alight Motion XML import of rena copines reference file`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val candidates = listOf(
            java.io.File("public/assets/aistudio/rena_-_copines___1__a27650f7.xml"),
            java.io.File("../public/assets/aistudio/rena_-_copines___1__a27650f7.xml"),
            java.io.File("/app/applet/public/assets/aistudio/rena_-_copines___1__a27650f7.xml")
        )
        val xmlFile = candidates.firstOrNull { it.exists() }
        assertTrue("Expected attached Alight Motion XML reference file to exist", xmlFile != null)

        val rawXml = xmlFile!!.readText(Charsets.UTF_8)
        val importRes = com.example.engine.ProjectXmlEngine.importProjectFromXml(
            context = context,
            xmlContent = rawXml,
            fallbackName = "rena - copines"
        ).getOrThrow()

        assertEquals(com.example.engine.ProjectXmlFormat.ALIGHT_MOTION, importRes.detectedFormat)
        val proj = importRes.project
        assertEquals(1080, proj.width)
        assertEquals(1080, proj.height)
        assertEquals(60, proj.fps)
        assertTrue("Expected total duration >= 13082ms (got ${proj.durationMs})", proj.durationMs >= 13082L)
        assertEquals(15, proj.beatMarkers.size)
        assertEquals(2, importRes.flattenedGroupsCount)
        assertTrue("Expected mapped Alight Motion effects", importRes.mappedEffectsCount >= 15)

        // Verify <audio> layer imported cleanly
        val audioLayers = proj.layers.filter { it.type == LayerType.AUDIO }
        assertTrue("Expected Copines audio layer", audioLayers.isNotEmpty())

        // Verify Group layers created for the 2 <embedScene> nested pre-compositions
        val groupRigs = proj.layers.filter { it.type == LayerType.GROUP }
        assertEquals(2, groupRigs.size)

        // Verify all keyframes across every property and effect are strictly sorted in ascending time order
        proj.layers.forEach { layer ->
            com.example.data.model.PropertyTarget.entries.forEach { target ->
                val kfs = layer.propertyFor(target).keyframes
                for (i in 1 until kfs.size) {
                    assertTrue(
                        "Keyframes on ${layer.name}.${target.name} must be sorted ascending (${kfs[i - 1].timeMs} <= ${kfs[i].timeMs})",
                        kfs[i - 1].timeMs <= kfs[i].timeMs
                    )
                }
            }
        }

        // Verify mapped Alight Motion effects including Copy_Background (lift), Tiles, Oscillate, Squeeze, Ripple_Pond, etc.
        val allEffectIds = proj.layers.flatMap { it.effects }.map { it.effectId }.toSet()
        assertTrue("Expected Copy_Background mapped from com.alightcreative.effects.lift", allEffectIds.contains("Copy_Background"))
        assertTrue("Expected Tiles mapped from com.alightcreative.effects.tile", allEffectIds.contains("Tiles"))
        assertTrue("Expected Oscillate mapped from com.alightcreative.effects.oscillate3", allEffectIds.contains("Oscillate"))
        assertTrue("Expected Squeeze mapped from com.alightcreative.effects.stretch2", allEffectIds.contains("Squeeze"))
        assertTrue("Expected Chromatic_Aberration mapped from rgbsep", allEffectIds.contains("Chromatic_Aberration"))
    }

    @Test
    fun `verify FramePollution native fpxml export and roundtrip import with all properties and embedded custom plugin`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        com.example.engine.PluginPackManager.ensureInitialized(context)

        // 1. Author and install a custom GLSL plugin effect
        val customSuite = com.example.engine.PluginPackManager.createAndInstallCustomPlugin(
            context = context,
            effectName = "Prism Flux Warp",
            suiteName = "Prism Flux Suite",
            author = "StudioTest",
            category = "Distortion",
            expandBounds = 0.25f,
            usesNativeMotionBlur = true,
            glslSource = com.example.engine.PluginPackManager.DEFAULT_CUSTOM_GLSL_TEMPLATE,
            parameters = listOf(
                com.example.engine.PluginParamDef(
                    key = "u_warp_amt",
                    label = "Warp Amount",
                    type = com.example.engine.PluginParamType.FLOAT,
                    defaultFloat = 0.42f,
                    min = 0f,
                    max = 2f
                ),
                com.example.engine.PluginParamDef(
                    key = "u_tint_col",
                    label = "Tint Color",
                    type = com.example.engine.PluginParamType.COLOR,
                    defaultColorHex = "#4F7CFF"
                )
            ),
            extension = com.example.engine.PluginPackManager.EXT_FPPACK
        ).getOrThrow()
        val pluginFxDef = com.example.engine.PluginPackManager.toEffectDefinition(customSuite.effects.single())
        val pluginFxNode = pluginFxDef.toNode()
            .updateCustomParam(
                "u_warp_amt",
                AnimatableProperty(
                    baseValue = 0.88f,
                    keyframes = listOf(
                        Keyframe(timeMs = 0L, value = 0.25f, outTangentX = 0.2f, outTangentY = 0.9f, inTangentX = 0.3f, inTangentY = 1f),
                        Keyframe(timeMs = 1500L, value = 1.45f, holdStep = true)
                    )
                )
            )
            .updateCustomColor("u_tint_col", "#7CA0FF")

        val builtInGlow = EffectsCatalog.findById("S_Glow").toNode(customIntensity = 0.91f)
        val rigLayer = Layer(
            id = "rig_master",
            name = "3D Camera Rig",
            type = LayerType.NULL_OBJECT,
            posX = AnimatableProperty(
                baseValue = -320f,
                keyframes = listOf(
                    Keyframe(timeMs = 0L, value = -320f),
                    Keyframe(timeMs = 2400L, value = 640f)
                )
            ),
            rotationZ = AnimatableProperty(baseValue = 18.5f)
        )
        val heroLayer = Layer(
            id = "hero_vid",
            name = "Main Clip",
            type = LayerType.SHAPE,
            shapeKind = "Star",
            parentId = "rig_master",
            startTimeMs = 120L,
            endTimeMs = 4800L,
            colorHex = "#4F7CFF",
            fillMode = "LINEAR_GRADIENT",
            gradientEndColorHex = "#FF3366",
            gradientAngleDeg = 65f,
            strokeEnabled = true,
            strokeWidth = 9.5f,
            strokeColorHex = "#FFFFFF",
            strokePosition = "INSIDE",
            shadowEnabled = true,
            shadowBlur = 24f,
            shadowColorHex = "#112244",
            shadowOffsetX = 4f,
            shadowOffsetY = 8f,
            blendMode = "Screen",
            motionBlurEnabled = true,
            shutterAngle = 270f,
            speedMultiplier = 1.75f,
            reversed = true,
            posX = AnimatableProperty(baseValue = -2100f, expression = "sin(time * 2.0) * 15"),
            posY = AnimatableProperty(baseValue = 420f),
            posZ = AnimatableProperty(baseValue = -95f),
            scale = AnimatableProperty(
                baseValue = 135f,
                audioReactorBand = com.example.data.model.AudioBand.BASS,
                audioReactorGain = 0.6f
            ),
            rotationX = AnimatableProperty(baseValue = 12f),
            rotationY = AnimatableProperty(baseValue = -24f),
            rotationZ = AnimatableProperty(baseValue = 45f),
            opacity = AnimatableProperty(baseValue = 88f),
            effects = listOf(builtInGlow, pluginFxNode)
        )
        val audioLayer = Layer(
            id = "dsp_audio",
            name = "Soundtrack.mp3",
            type = LayerType.AUDIO,
            startTimeMs = 0L,
            endTimeMs = 4800L,
            audioVolume = 0.85f,
            audioReverbMix = 0.35f,
            audioEchoMix = 0.2f,
            audioBassBoost = 0.65f,
            audioPitchSemitones = -2f
        )

        val originalProject = com.example.data.model.Project(
            name = "FPXML Master Roundtrip",
            aspectRatioPreset = "4:3",
            width = 1440,
            height = 1080,
            fps = 48,
            durationMs = 4800L,
            codec = "H.265",
            bpm = 136,
            backgroundColor = "#0A1020",
            layers = listOf(rigLayer, heroLayer, audioLayer),
            beatMarkers = listOf(
                com.example.data.model.BeatMarker(timeMs = 500L, strength = 0.9f, band = com.example.data.model.AudioBand.BASS),
                com.example.data.model.BeatMarker(timeMs = 1500L, strength = 1.0f, band = com.example.data.model.AudioBand.MIDS)
            )
        )

        // 2. Export to .fpxml and REMOVE the custom plugin suite to simulate importing onto a fresh device!
        val fpxmlText = com.example.engine.ProjectXmlEngine.exportFramePollutionXml(originalProject)
        assertTrue(fpxmlText.contains("<fpProject"))
        assertTrue(fpxmlText.contains("<pluginSuites>"))
        com.example.engine.PluginPackManager.removeSuite(context, customSuite.id)
        assertNull(
            "Custom plugin should be uninstalled prior to fpxml import",
            com.example.engine.PluginPackManager.findPluginEffect(pluginFxDef.id)
        )

        // 3. Import .fpxml and verify 100% of project properties + automatic custom plugin re-installation!
        val reimported = com.example.engine.ProjectXmlEngine.importProjectFromXml(context, fpxmlText).getOrThrow()
        assertEquals(com.example.engine.ProjectXmlFormat.FRAME_POLLUTION_NATIVE, reimported.detectedFormat)
        assertEquals(1, reimported.importedPluginSuitesCount)
        assertTrue(
            "Importing .fpxml must automatically re-install embedded custom GLSL plugin",
            com.example.engine.PluginPackManager.findPluginEffect(pluginFxDef.id) != null
        )

        val rp = reimported.project
        assertEquals("FPXML Master Roundtrip", rp.name)
        assertEquals(1440, rp.width)
        assertEquals(1080, rp.height)
        assertEquals(48, rp.fps)
        assertEquals("H.265", rp.codec)
        assertEquals(136, rp.bpm)
        assertEquals("#0A1020", rp.backgroundColor)
        assertEquals(2, rp.beatMarkers.size)
        assertEquals(3, rp.layers.size)

        val restoredHero = rp.layers.first { it.name == "Main Clip" }
        assertEquals("rig_master", restoredHero.parentId)
        assertEquals(-2100f, restoredHero.posX.baseValue, 0.05f)
        assertEquals("sin(time * 2.0) * 15", restoredHero.posX.expression)
        assertEquals(-95f, restoredHero.posZ.baseValue, 0.05f)
        assertEquals(135f, restoredHero.scale.baseValue, 0.05f)
        assertEquals(com.example.data.model.AudioBand.BASS, restoredHero.scale.audioReactorBand)
        assertEquals(12f, restoredHero.rotationX.baseValue, 0.05f)
        assertEquals(-24f, restoredHero.rotationY.baseValue, 0.05f)
        assertEquals(45f, restoredHero.rotationZ.baseValue, 0.05f)
        assertEquals("LINEAR_GRADIENT", restoredHero.fillMode)
        assertEquals("INSIDE", restoredHero.strokePosition)
        assertEquals(9.5f, restoredHero.strokeWidth, 0.05f)
        assertTrue(restoredHero.shadowEnabled)
        assertEquals(1.75f, restoredHero.speedMultiplier, 0.02f)
        assertTrue(restoredHero.reversed)
        assertEquals(2, restoredHero.effects.size)

        val restoredPluginNode = restoredHero.effects.first { it.effectId == pluginFxDef.id }
        assertEquals("#7CA0FF", restoredPluginNode.customColors["u_tint_col"])
        assertEquals(2, restoredPluginNode.customParams["u_warp_amt"]?.keyframes?.size)
        assertTrue(restoredPluginNode.customParams["u_warp_amt"]!!.keyframes[1].holdStep)

        val restoredAudio = rp.layers.first { it.type == LayerType.AUDIO }
        assertEquals(0.35f, restoredAudio.audioReverbMix, 0.01f)
        assertEquals(-2f, restoredAudio.audioPitchSemitones, 0.01f)

        // 4. Also verify Alight Motion .xml export -> import roundtrip
        val amXmlOut = com.example.engine.ProjectXmlEngine.exportAlightMotionXml(originalProject)
        assertTrue(amXmlOut.contains("<scene"))
        val amRoundtrip = com.example.engine.ProjectXmlEngine.importProjectFromXml(context, amXmlOut).getOrThrow()
        assertEquals(com.example.engine.ProjectXmlFormat.ALIGHT_MOTION, amRoundtrip.detectedFormat)
        assertEquals(1440, amRoundtrip.project.width)
        assertEquals(1080, amRoundtrip.project.height)
        assertEquals(48, amRoundtrip.project.fps)

        // 5. Verify StudioPlaybackClock monotonic slewing never jumps backwards on small negative DAC jitter
        val t0Nano = 1_000_000_000L
        com.example.engine.StudioPlaybackClock.start(initialPlayheadMs = 500L, durationMs = 5000L, isLooping = true)
        val baseSample = com.example.engine.StudioPlaybackClock.samplePlayheadMs()
        val afterSmallNegativeJitter = com.example.engine.StudioPlaybackClock.reconcileWithHardwareClock(
            hardwareMs = (baseSample - 30L).coerceAtLeast(0L)
        )
        assertTrue(
            "StudioPlaybackClock must never jump backwards on minor negative DAC jitter ($afterSmallNegativeJitter >= $baseSample)",
            afterSmallNegativeJitter >= baseSample
        )
        StudioPlaybackClock_pause_helper(baseSample)
    }

    private fun StudioPlaybackClock_pause_helper(baseSample: Long) {
        com.example.engine.StudioPlaybackClock.pause(baseSample)
    }

    @Test
    fun `verify kfpack minAppVersion enforcement partial broken shader skip AEP Windows path resolver FFX porting group prerender and uninterrupted playback`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        com.example.engine.PluginPackManager.ensureInitialized(context)
        com.example.engine.AepMediaResolver.invalidateCache()
        com.example.engine.PlaybackCacheEngine.clearAll()

        // 1. .kfpack minAppVersion check: version "9.9.0" must fail cleanly with clear error
        val incompatibleZip = java.io.ByteArrayOutputStream().also { baos ->
            java.util.zip.ZipOutputStream(baos).use { zos ->
                zos.putNextEntry(java.util.zip.ZipEntry("suite.json"))
                zos.write(
                    """
                    {
                      "id": "future_pack",
                      "name": "Future Pack",
                      "author": "TestAuthor",
                      "version": "2.0.0",
                      "minAppVersion": "9.9.0",
                      "effects": []
                    }
                    """.trimIndent().toByteArray()
                )
                zos.closeEntry()
            }
        }.toByteArray()
        val futureRes = com.example.engine.PluginPackManager.importPackFromBytes(context, incompatibleZip, ".kfpack")
        assertTrue("Incompatible minAppVersion must fail cleanly", futureRes.isFailure)
        assertTrue(
            "Error message must mention minAppVersion",
            futureRes.exceptionOrNull()?.message?.contains("9.9.0") == true
        )

        // 2. Partial shader validation: 1 valid effect + 1 broken shader in same .kfpack skips ONLY the broken effect!
        val partialZip = java.io.ByteArrayOutputStream().also { baos ->
            java.util.zip.ZipOutputStream(baos).use { zos ->
                zos.putNextEntry(java.util.zip.ZipEntry("suite.json"))
                zos.write(
                    """
                    {
                      "id": "partial_validation_pack",
                      "name": "Partial Validation Pack",
                      "author": "AngkinQA",
                      "version": "1.2.3",
                      "minAppVersion": "1.4.9",
                      "effects": ["good_tint_fx", "broken_glsl_fx"]
                    }
                    """.trimIndent().toByteArray()
                )
                zos.closeEntry()

                zos.putNextEntry(java.util.zip.ZipEntry("effects/good_tint_fx/effect.json"))
                zos.write(
                    """
                    {
                      "id": "good_tint_fx",
                      "name": "Good Tint",
                      "category": "Color",
                      "parameters": [
                        { "key": "amount", "label": "Amount", "type": "FLOAT", "default": 0.5, "min": 0.0, "max": 1.0 },
                        { "key": "invertFlag", "label": "Invert", "type": "BOOL", "default": true },
                        { "key": "tint", "label": "Tint", "type": "COLOR", "default": "#FFD60A" }
                      ]
                    }
                    """.trimIndent().toByteArray()
                )
                zos.closeEntry()
                zos.putNextEntry(java.util.zip.ZipEntry("effects/good_tint_fx/shader.glsl"))
                zos.write(
                    """
                    precision mediump float;
                    varying vec2 vTex;
                    uniform sampler2D uTexture;
                    uniform float uAmount;
                    uniform float uInvertFlag;
                    uniform vec3 uTint;
                    void main() {
                        vec4 c = texture2D(uTexture, vTex);
                        vec3 mixed = mix(c.rgb, uTint, uAmount);
                        if (uInvertFlag > 0.5) mixed = 1.0 - mixed;
                        gl_FragColor = vec4(mixed, c.a);
                    }
                    """.trimIndent().toByteArray()
                )
                zos.closeEntry()

                zos.putNextEntry(java.util.zip.ZipEntry("effects/broken_glsl_fx/effect.json"))
                zos.write("""{ "id": "broken_glsl_fx", "name": "Broken Shader", "category": "Color", "parameters": [] }""".toByteArray())
                zos.closeEntry()
                zos.putNextEntry(java.util.zip.ZipEntry("effects/broken_glsl_fx/shader.glsl"))
                // Deliberately missing main() so validator rejects ONLY this effect!
                zos.write("""precision mediump float; uniform sampler2D uTexture; float helper() { return 1.0; }""".toByteArray())
                zos.closeEntry()
            }
        }.toByteArray()

        val partialSuite = com.example.engine.PluginPackManager.importPackFromBytes(context, partialZip, ".kfpack").getOrThrow()
        assertEquals("Good effect must load despite sibling broken shader", 1, partialSuite.effects.size)
        assertEquals("good_tint_fx", partialSuite.effects.single().id)
        assertEquals("AngkinQA", partialSuite.author)
        assertEquals("1.2.3", partialSuite.version)
        assertEquals("Broken effect must be recorded in skippedEffectsReport", 1, partialSuite.skippedEffectsReport.size)
        com.example.engine.PluginPackManager.removeSuite(context, "partial_validation_pack")

        // 3. AEP Windows C:\ path extraction + priority folder search + single-scan index cache
        assertEquals(
            "hero_clip.mp4",
            com.example.engine.AepMediaResolver.extractCleanFileName("""C:\Users\Editor\Desktop\Edits\Footage\hero_clip.mp4""")
        )
        val fakeAepFolder = java.io.File(context.cacheDir, "aep_import_dir").apply { mkdirs() }
        val localHeroFile = java.io.File(fakeAepFolder, "hero_clip.mp4")
        localHeroFile.writeBytes(ByteArray(2048) { (it % 127).toByte() })

        val resolvedAsset = kotlinx.coroutines.runBlocking {
            com.example.engine.AepMediaResolver.resolveAssetPaths(
                context = context,
                requests = listOf("""C:\Users\Editor\Footage\hero_clip.mp4""" to 2048L),
                aepOwnFolder = fakeAepFolder
            ).single()
        }
        assertEquals(
            "AEP resolver must locate hero_clip.mp4 in AEP's own folder first",
            com.example.engine.AssetMatchStatus.MATCHED,
            resolvedAsset.status
        )
        assertTrue(resolvedAsset.matchedUri.contains("hero_clip.mp4"))

        // 4. Synthetic .aep import: nested comp -> LayerType.GROUP, BCC Page Turn -> PORTED, unknown effect -> UNSUPPORTED (layer kept!), missing media flagged
        val aepBytes = com.example.engine.UniversalProjectImporter.buildSyntheticAepBinary(
            compName = "Cyber Intro Edit",
            width = 1920,
            height = 1080,
            fps = 60,
            footageWindowsPaths = listOf(
                """C:\Users\Editor\Footage\hero_clip.mp4""",
                """C:\Projects\Missing\ghost_overlay.png"""
            ),
            nestedCompName = "Precomp_Logo_Group",
            effectSpecs = listOf(
                "BCC Page Turn" to "BCC Page Turn",
                "ADBE TotallyUnknownCustomPlugin" to "TotallyUnknownCustomPlugin"
            )
        )
        val aepFile = java.io.File(fakeAepFolder, "cyber_intro.aep")
        aepFile.writeBytes(aepBytes)

        val importOutcome = kotlinx.coroutines.runBlocking {
            com.example.engine.UniversalProjectImporter.importFromUri(
                context = context,
                uri = android.net.Uri.fromFile(aepFile)
            )
        }.getOrThrow()
        assertTrue(importOutcome is com.example.engine.UniversalImportOutcome.OpenImportPreview)
        val session = (importOutcome as com.example.engine.UniversalImportOutcome.OpenImportPreview).session
        assertEquals(1920, session.previewProject.width)
        assertEquals(1080, session.previewProject.height)
        assertTrue(
            "Nested comp must map to LayerType.GROUP",
            session.previewProject.layers.any { it.type == LayerType.GROUP && it.name.contains("Precomp_Logo_Group") }
        )
        assertTrue(
            "hero_clip.mp4 should be auto-matched while ghost_overlay.png is marked missing for user relink",
            session.mediaSlots.any {
                it.displayName == "hero_clip.mp4" && it.status == com.example.engine.AssetMatchStatus.MATCHED
            } &&
                session.mediaSlots.any {
                    it.displayName == "ghost_overlay.png" && it.status == com.example.engine.AssetMatchStatus.MISSING
                }
        )
        assertTrue(
            "BCC Page Turn must port to bcc_page_turn with PORTED status",
            session.effectRecords.any {
                it.originalMatchName.contains("BCC Page Turn") &&
                    it.status == com.example.engine.ImportEffectStatus.PORTED &&
                    it.mappedEffectId == "bcc_page_turn"
            }
        )
        val unsupportedEntry = session.effectRecords.firstOrNull {
            it.originalMatchName.contains("TotallyUnknownCustomPlugin")
        }
        org.junit.Assert.assertNotNull("Unknown AE effect must appear in Import Preview as UNSUPPORTED", unsupportedEntry)
        assertEquals(com.example.engine.ImportEffectStatus.UNSUPPORTED, unsupportedEntry!!.status)
        assertTrue(
            "Layer hosting an unsupported effect must STILL be kept intact in the imported project",
            session.previewProject.layers.any { it.id == unsupportedEntry.layerId }
        )

        // 5. Shared .ffx preset importer uses the exact same AE porting table
        val ffxBytes = com.example.engine.UniversalProjectImporter.buildSyntheticFfxBinary(
            effectSpecs = listOf(
                "S_Shake" to "S_Shake",
                "Deep Glow" to "Deep Glow"
            )
        )
        val ffxFile = java.io.File(fakeAepFolder, "shake_glow.ffx")
        ffxFile.writeBytes(ffxBytes)
        val ffxOutcome = kotlinx.coroutines.runBlocking {
            com.example.engine.UniversalProjectImporter.importFromUri(
                context = context,
                uri = android.net.Uri.fromFile(ffxFile)
            )
        }.getOrThrow()
        assertTrue(ffxOutcome is com.example.engine.UniversalImportOutcome.OpenImportPreview)
        val ffxSession = (ffxOutcome as com.example.engine.UniversalImportOutcome.OpenImportPreview).session
        assertEquals(2, ffxSession.effectRecords.count { it.status == com.example.engine.ImportEffectStatus.PORTED })

        // 6. StudioViewModel: Multi-select -> Group Selected Layers, Single/Group Pre-render, and Clicking Layer keeps Playback running!
        val vm = com.example.viewmodel.StudioViewModel(appContext = context)
        vm.createNewProject(
            name = "Group & Playback Stability Test",
            preset = AspectRatioPreset.SQUARE_1_1,
            customWidth = 360,
            customHeight = 360,
            fps = 24,
            durationSec = 4,
            bpm = 120
        )
        vm.addShapeLayerWithKind("Circle")
        val layerA = vm.state.value.selectedLayerId!!
        vm.addShapeLayerWithKind("Star")
        val layerB = vm.state.value.selectedLayerId!!

        vm.deselectLayer()
        vm.toggleLayerMultiSelection(layerA)
        vm.toggleLayerMultiSelection(layerB)
        assertTrue(vm.state.value.selectedLayerIds.containsAll(listOf(layerA, layerB)))

        vm.groupSelectedLayers()
        val createdGroup = vm.state.value.activeProject.layers.firstOrNull { it.type == LayerType.GROUP }
        org.junit.Assert.assertNotNull("groupSelectedLayers() must create a LayerType.GROUP container", createdGroup)
        val groupId = createdGroup!!.id
        assertEquals(
            "Both selected layers must be parented to the new Group layer",
            2,
            vm.state.value.activeProject.layers.count { it.parentId == groupId }
        )

        // Pre-render the Group layer directly into PlaybackCacheEngine
        val rev = vm.state.value.projectRevision
        val renderedGroupFrames = kotlinx.coroutines.runBlocking {
            com.example.engine.PlaybackCacheEngine.preRenderLayerOrGroup(
                context = context,
                project = vm.state.value.activeProject,
                targetLayerId = groupId,
                projectRevision = rev,
                quality = com.example.data.preferences.PreviewQuality.QUARTER,
                fpsCap = 24,
                audioEngine = vm.audioEngine
            )
        }
        assertTrue("Group pre-render must cache composite group frames", renderedGroupFrames > 0)
        assertTrue(
            com.example.engine.PlaybackCacheEngine.isLayerOrGroupCached(
                projectRevision = rev,
                quality = com.example.data.preferences.PreviewQuality.QUARTER,
                layerId = groupId
            )
        )

        // Start playback and verify clicking/selecting/deselecting layers NEVER stops or spawns a duplicate playback!
        vm.togglePlayback()
        assertTrue("Playback should be active", vm.state.value.isPlaying)
        vm.selectLayer(layerA)
        assertTrue("Clicking layerA must keep original playback running", vm.state.value.isPlaying)
        vm.selectLayer(layerB)
        assertTrue("Clicking layerB must keep original playback running", vm.state.value.isPlaying)
        vm.deselectLayer()
        assertTrue("Deselecting layer must keep original playback running", vm.state.value.isPlaying)
        vm.pausePlayback()

        localHeroFile.delete()
        aepFile.delete()
        ffxFile.delete()
        fakeAepFolder.delete()
    }

    @Test
    fun `verify recent edits saved inside project before closing and zero premade templates`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val testUser = "autosave_user_${System.nanoTime()}"

        // 1. Verify zero premade template tabs exist in navigation (Home, Projects, Plugins, Depth Map)
        val tabLabels = com.example.ui.components.AmHomeTab.entries.map { it.label }
        assertEquals(listOf("Home", "Projects", "Plugins", "Depth Map"), tabLabels)
        assertTrue(!tabLabels.contains("Templates"))

        // 2. Create a project, make edits (layers + effects + keyframes), and close it
        val vm1 = com.example.viewmodel.StudioViewModel(
            repository = null,
            currentUserId = testUser,
            appContext = context
        )
        vm1.createNewProject(
            name = "Recent Edits Save Test",
            preset = AspectRatioPreset.PORTRAIT_9_16,
            customWidth = 1080,
            customHeight = 1920,
            fps = 60,
            durationSec = 8,
            bpm = 130
        )
        val projectId = vm1.state.value.activeProject.id
        assertTrue("Created project should receive a stable ID immediately", projectId.isNotBlank())

        vm1.addShapeLayerWithKind("Star")
        vm1.addEffectToSelectedLayer(EffectsCatalog.findById("S_Glow"))
        vm1.toggleBeatBookmarkAtPlayhead()
        assertEquals(1, vm1.state.value.activeProject.layers.size)
        assertEquals(1, vm1.state.value.activeProject.beatMarkers.size)

        // Close the project back to Home — must save all recent edits inside the project BEFORE closing!
        vm1.closeAndSaveActiveProject()
        assertEquals(com.example.viewmodel.StudioDestination.HOME, vm1.state.value.destination)

        // 3. Reopen the project from a fresh ViewModel session (simulating app restart / returning later)
        val persistedProjects = com.example.data.repository.LocalRecentProjectStore.loadProjects(context, testUser)
        val savedProj = persistedProjects.firstOrNull { it.id == projectId }
        org.junit.Assert.assertNotNull("Closed project must be persisted with recent edits in LocalRecentProjectStore", savedProj)
        assertEquals(1, savedProj!!.layers.size)
        assertEquals("Star", savedProj.layers.first().shapeKind)
        assertEquals(1, savedProj.layers.first().effects.size)
        assertEquals(1, savedProj.beatMarkers.size)

        val vm2 = com.example.viewmodel.StudioViewModel(
            repository = null,
            currentUserId = testUser,
            appContext = context
        )
        vm2.openProject(savedProj)
        assertEquals(1, vm2.state.value.activeProject.layers.size)
        assertEquals("S_Glow", vm2.state.value.activeProject.layers.first().effects.first().effectId)

        // 4. Verify UniversalCodecEngine dimension alignment & monotonic StudioPlaybackClock jitter guard
        val (alignedW, alignedH) = com.example.engine.UniversalCodecEngine.alignDimensions(
            requestedWidth = 1081,
            requestedHeight = 1923
        )
        assertEquals(0, alignedW % 2)
        assertEquals(0, alignedH % 2)

        com.example.engine.StudioPlaybackClock.start(initialPlayheadMs = 1000L, durationMs = 8000L, isLooping = false)
        val t1 = com.example.engine.StudioPlaybackClock.samplePlayheadMs()
        // Simulated -15ms negative DAC jitter must NEVER pull playhead backwards
        val t2 = com.example.engine.StudioPlaybackClock.reconcileWithHardwareClock(t1 - 15L)
        assertTrue("Playhead must stay monotonic across sub-frame DAC jitter", t2 >= t1)
        com.example.engine.StudioPlaybackClock.pause()
    }

    @Test
    fun `verify playback preview never resets to 0 on user inputs and supports full 360 degree 3D rotation and FFmpeg reverse mapping`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val vm = com.example.viewmodel.StudioViewModel(appContext = context)
        vm.createNewProject(
            name = "Playback Isolation & 360 3D Test",
            preset = AspectRatioPreset.PORTRAIT_9_16,
            customWidth = 1080,
            customHeight = 1920,
            fps = 60,
            durationSec = 10,
            bpm = 120
        )

        // 1. Add a layer and scrub to a non-zero timestamp (2800ms within 3500ms default clip length)
        vm.addShapeLayerWithKind("Rounded Rect")
        val layerId = vm.state.value.selectedLayerId!!
        vm.scrubPlayhead(2800L)
        val expectedPausedMs = vm.state.value.playheadMs
        assertTrue("Expected non-zero playhead around 2800ms", expectedPausedMs >= 2780L)

        // Apply Move, Scale, 3D Move (-360..+360), Effects, Keyframes, and Canvas Drag while paused at 2800ms
        vm.dragSelectedLayerByDelta(85f, -40f)
        assertEquals(expectedPausedMs, vm.state.value.playheadMs)

        vm.selectProperty(com.example.data.model.PropertyTarget.ROTATION_X)
        vm.updateSelectedPropertyValue(360f)
        assertEquals(expectedPausedMs, vm.state.value.playheadMs)

        vm.selectProperty(com.example.data.model.PropertyTarget.ROTATION_Y)
        vm.updateSelectedPropertyValue(-360f)
        assertEquals(expectedPausedMs, vm.state.value.playheadMs)

        vm.selectProperty(com.example.data.model.PropertyTarget.ROTATION_Z)
        vm.updateSelectedPropertyValue(270f)
        vm.toggleKeyframeAtPlayhead()
        assertEquals(expectedPausedMs, vm.state.value.playheadMs)

        vm.addEffectToSelectedLayer(EffectsCatalog.findById("S_Glow"))
        vm.updateSelectedEffectParamValue(0.9f)
        assertEquals("User inputs while paused must never reset playhead to 0", expectedPausedMs, vm.state.value.playheadMs)

        // Verify full 360-degree 3D rotation values are preserved without clamping at 78/80 degrees
        val audio = vm.liveSpectrum.value
        val updatedLayer = vm.state.value.activeProject.layers.first { it.id == layerId }
        val tr = AnimationEvaluator.evaluateWorldTransform(
            layer = updatedLayer,
            allLayers = vm.state.value.activeProject.layers,
            timeMs = expectedPausedMs,
            audio = audio,
            applyActiveCamera = false
        )
        assertEquals(360f, tr.rotX, 0.05f)
        assertEquals(-360f, tr.rotY, 0.05f)
        assertEquals(270f, tr.rotZ, 0.05f)

        // Verify 3D Camera Orbit supports full 360-degree pitch & yaw and valid finite MVP matrix
        val initialPitch = vm.state.value.cameraPitchDeg
        vm.orbit3DCamera(deltaYaw = 290f, deltaPitch = 210f, zoomFactor = 1f)
        assertEquals(initialPitch + 210f, vm.state.value.cameraPitchDeg, 0.1f)
        val mvp = com.example.engine.Mesh3DLoader.computeMvpMatrix(
            aspectRatio = 9f / 16f,
            camYawDeg = vm.state.value.cameraYawDeg,
            camPitchDeg = vm.state.value.cameraPitchDeg,
            camDistance = vm.state.value.cameraDistance,
            layerPosX = 0f,
            layerPosY = 0f,
            layerPosZ = 0f,
            layerRotX = 360f,
            layerRotY = -225f,
            layerRotZ = 360f,
            layerScale = 1f
        )
        assertTrue("360-degree 3D MVP matrix must contain only finite values", mvp.all { it.isFinite() })

        // 2. Start active playback at 2400ms and verify live edits NEVER reset StudioPlaybackClock or playheadMs to 0!
        vm.scrubPlayhead(2400L)
        val startPlayMs = vm.state.value.playheadMs
        vm.togglePlayback()
        assertTrue(vm.state.value.isPlaying)

        vm.dragSelectedLayerByDelta(25f, 15f)
        vm.scaleAndRotateSelectedLayerByDelta(12f, 45f)
        vm.updateLayerPropertyByTarget(layerId, com.example.data.model.PropertyTarget.ROTATION_Y, 180f)
        vm.updateSelectedEffectParamValue(0.65f)
        com.example.engine.StudioPlaybackClock.updateLoopAndDuration(isLooping = true, durationMs = 15_000L)

        val liveClockAfterEdits = com.example.engine.StudioPlaybackClock.samplePlayheadMs()
        assertTrue(
            "Live playback clock must never reset back to 0 during user inputs (was $liveClockAfterEdits)",
            liveClockAfterEdits >= startPlayMs
        )
        assertTrue(
            "Workspace playheadMs must never reset back to 0 during user inputs (was ${vm.state.value.playheadMs})",
            vm.state.value.playheadMs >= startPlayMs
        )
        vm.pausePlayback()

        // 3. Verify FFmpegReverseVideoEngine windowing & bidirectional reverse timestamp mapping
        val shortLayer = Layer(
            id = "rev_short",
            type = LayerType.VIDEO,
            mediaUri = "file:///tmp/demo_short.mp4",
            startTimeMs = 0L,
            endTimeMs = 6_000L,
            sourceDurationMs = 8_000L,
            reversed = true
        )
        val (shortWinStart, shortWinEnd) = com.example.engine.FFmpegReverseVideoEngine.computeOptimalReverseWindow(shortLayer)
        assertEquals(0L, shortWinStart)
        assertEquals(8_000L, shortWinEnd)

        val proxyShort = com.example.engine.FFmpegReverseVideoEngine.ReversedProxyMedia(
            cacheKey = "test_key",
            filePath = "/tmp/rev_short_proxy.mp4",
            fileUri = "file:///tmp/rev_short_proxy.mp4",
            sourceDurationMs = 8_000L,
            windowStartSourceMs = shortWinStart,
            windowEndSourceMs = shortWinEnd,
            proxyDurationMs = shortWinEnd - shortWinStart,
            maxEdgePx = 720,
            hasReversedAudio = true
        )
        // Forward source 6500ms in an 8000ms clip -> 1500ms into the reversed proxy!
        val mappedRevMs = com.example.engine.FFmpegReverseVideoEngine.mapForwardSourceMsToReversedProxyMs(6_500L, proxyShort)
        assertEquals(1_500L, mappedRevMs)
        val roundTripSrcMs = com.example.engine.FFmpegReverseVideoEngine.mapReversedProxyMsToForwardSourceMs(mappedRevMs, proxyShort)
        assertEquals(6_500L, roundTripSrcMs)

        // Long 60s video trimmed to a 4s reversed segment -> windowed proxy around active segment
        val longTrimmedLayer = Layer(
            id = "rev_long",
            type = LayerType.VIDEO,
            mediaUri = "file:///tmp/demo_long.mp4",
            startTimeMs = 1_000L,
            endTimeMs = 5_000L,
            sourceOffsetMs = 20_000L,
            sourceDurationMs = 60_000L,
            reversed = true
        )
        val (longWinStart, longWinEnd) = com.example.engine.FFmpegReverseVideoEngine.computeOptimalReverseWindow(longTrimmedLayer)
        assertTrue("Long clip reverse window should trim instead of transcoding all 60s", (longWinEnd - longWinStart) < 15_000L)
    }

    private fun resolveFixtureFile(fileName: String): java.io.File {
        val candidates = listOf(
            java.io.File("public/assets/aistudio/$fileName"),
            java.io.File("../public/assets/aistudio/$fileName"),
            java.io.File("/app/applet/public/assets/aistudio/$fileName")
        )
        return candidates.firstOrNull { it.exists() && it.isFile }
            ?: error("Missing required fixture file $fileName (checked ${candidates.map { it.absolutePath }})")
    }

    @Test
    fun `verify KeyEase_38e4fe05 zip unmodified import all 8 presets single undo and no server`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val vm = com.example.viewmodel.StudioViewModel(appContext = context)
        vm.createNewProject(
            name = "KeyEase Fixture Verification",
            preset = AspectRatioPreset.SQUARE_1_1,
            customWidth = 1080,
            customHeight = 1080,
            fps = 60,
            durationSec = 5,
            bpm = 120
        )
        vm.addShapeLayerWithKind("Rectangle")
        val layerId = vm.state.value.selectedLayerId!!
        vm.selectProperty(com.example.data.model.PropertyTarget.POS_X)
        vm.scrubPlayhead(0L)
        vm.toggleKeyframeAtPlayhead(com.example.data.model.PropertyTarget.POS_X)
        vm.updateSelectedPropertyValue(0f)
        vm.scrubPlayhead(1000L)
        vm.toggleKeyframeAtPlayhead(com.example.data.model.PropertyTarget.POS_X)
        vm.updateSelectedPropertyValue(400f)

        val repo = vm.pluginHostRepository!!
        val keyEaseZip = resolveFixtureFile("KeyEase_38e4fe05.zip")
        val plugin = kotlinx.coroutines.runBlocking {
            repo.importPluginFromFile(keyEaseZip)
        }.getOrThrow()

        assertEquals("com.keyease.panel", plugin.bundleId)
        assertEquals("0.1.0", plugin.version)
        assertEquals(com.example.scriptengine.PluginType.CEP_PANEL, plugin.type)
        assertEquals(260, plugin.geometry.defaultWidth)
        assertEquals(470, plugin.geometry.defaultHeight)
        assertEquals(com.example.engine.TimelinePluginUiStatus.SHOWN, plugin.timelineStatus())
        assertTrue(plugin.isVisibleInEditorMenu())

        // Verify KeyEase/app-side/ desktop server folder was completely ignored during extraction
        val installDir = java.io.File(plugin.installDirPath)
        assertTrue(installDir.exists())
        val extractedFiles = installDir.walkTopDown().filter { it.isFile }.map { it.relativeTo(installDir).invariantSeparatorsPath }.toList()
        assertTrue("KeyEase/app-side/ must be ignored on Android", extractedFiles.none { it.contains("app-side") })

        // Load unmodified KeyEase host script and apply all 8 cubic-bezier presets!
        kotlinx.coroutines.runBlocking {
            repo.ensureHostLoaded(plugin.bundleId).getOrThrow()
        }

        val presets = listOf(
            Quadruple(0.00f, 0.00f, 1.00f, 1.00f), // Linear
            Quadruple(0.42f, 0.00f, 1.00f, 1.00f), // Ease In
            Quadruple(0.00f, 0.00f, 0.58f, 1.00f), // Ease Out
            Quadruple(0.42f, 0.00f, 0.58f, 1.00f), // Ease In Out
            Quadruple(0.70f, 0.00f, 0.84f, 0.00f), // Expo In
            Quadruple(0.16f, 1.00f, 0.30f, 1.00f), // Expo Out
            Quadruple(0.85f, 0.00f, 0.15f, 1.00f), // Expo In Out
            Quadruple(0.34f, 1.56f, 0.64f, 1.00f)  // Overshoot (> 1.0!)
        )

        for ((x1, y1, x2, y2) in presets) {
            val undoBefore = vm.state.value.canUndo
            val resJson = kotlinx.coroutines.runBlocking {
                repo.evalPluginScript(
                    plugin.bundleId,
                    "KE.apply(${x1}, ${y1}, ${x2}, ${y2})"
                )
            }.getOrThrow()
            assertTrue("KE.apply(${x1},${y1},${x2},${y2}) returned: $resJson", resJson.contains("true") || resJson.contains("applied") || !resJson.contains("error", ignoreCase = true))

            val kfs = vm.state.value.activeProject.layers.first { it.id == layerId }.posX.keyframes
            assertEquals(2, kfs.size)
            assertEquals("outTangentX for ($x1,$y1,$x2,$y2)", x1, kfs[0].outTangentX, 0.02f)
            assertEquals("outTangentY for ($x1,$y1,$x2,$y2)", y1, kfs[0].outTangentY, 0.03f)
            assertEquals("inTangentX for ($x1,$y1,$x2,$y2)", x2, kfs[1].inTangentX, 0.02f)
            assertEquals("inTangentY for ($x1,$y1,$x2,$y2)", y2, kfs[1].inTangentY, 0.03f)
            assertTrue(undoBefore || vm.state.value.canUndo)
        }

        // Verify Overshoot (.34, 1.56, .64, 1) evaluates > 400f before reaching 1000ms, and reverts in 1 undo step!
        val audio = vm.liveSpectrum.value
        val overshootLayer = vm.state.value.activeProject.layers.first { it.id == layerId }
        val peakSamples = (100L..950L step 50L).map { t ->
            AnimationEvaluator.evaluateProperty(overshootLayer.posX, t, audio)
        }
        assertTrue("Overshoot curve (.34,1.56,.64,1) must exceed 400f target", peakSamples.max() > 405f)

        vm.undo()
        val afterSingleUndo = vm.state.value.activeProject.layers.first { it.id == layerId }.posX.keyframes
        assertEquals("1 undo step must revert Overshoot back to previous Expo In Out (0.85)", 0.85f, afterSingleUndo[0].outTangentX, 0.02f)
    }

    private data class Quadruple(val x1: Float, val y1: Float, val x2: Float, val y2: Float)

    @Test
    fun `verify Textevo_2026_1_0 jsxbin RAM decode ScriptUI texttransform markers unknown header and zero source leak`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val vm = com.example.viewmodel.StudioViewModel(appContext = context)
        vm.createNewProject(
            name = "Textevo Fixture Verification",
            preset = AspectRatioPreset.SQUARE_1_1,
            customWidth = 1080,
            customHeight = 1080,
            fps = 60,
            durationSec = 6,
            bpm = 120
        )
        vm.addLayer(LayerType.TEXT)
        val textLayerId = vm.state.value.selectedLayerId!!

        val repo = vm.pluginHostRepository!!
        val textevoZip = resolveFixtureFile("Textevo_2026.1.0_44c54978.zip")
        val plugin = kotlinx.coroutines.runBlocking {
            repo.importPluginFromFile(textevoZip)
        }.getOrThrow()

        assertEquals(com.example.scriptengine.PluginType.SCRIPT_UI, plugin.type)
        assertTrue("README.txt should be captured on InstalledPlugin", plugin.readmeText.isNotBlank())
        assertTrue("Dry-run probe must execute during install", plugin.report.probeExecuted)

        val snapshot = kotlinx.coroutines.runBlocking {
            repo.ensureHostLoaded(plugin.bundleId)
        }.getOrThrow()
        org.junit.Assert.assertNotNull("Textevo .jsxbin must decode in RAM and produce a ScriptUI widget snapshot", snapshot)

        // Apply AE text animator + layer marker via ExtendScript host session in 1 undo group
        val applyScript = """
            (function() {
                app.beginUndoGroup("Textevo Apply");
                var comp = app.project.activeItem;
                var lyr = comp.selectedLayers[0];
                var anims = lyr.property("ADBE Text Properties").property("ADBE Text Animators");
                var anim = anims.addProperty("ADBE Text Animator");
                var sel = anim.property("ADBE Text Selectors").addProperty("ADBE Text Selector");
                sel.property("ADBE Text Percent Start").setValueAtTime(0.0, 0);
                sel.property("ADBE Text Percent Start").setValueAtTime(1.0, 100);
                var props = anim.property("ADBE Text Animator Properties");
                props.addProperty("ADBE Text Position 3D").setValue([0, -64, 0]);
                props.addProperty("ADBE Text Opacity").setValue(0);
                var mv = new MarkerValue("Textevo In");
                lyr.property("ADBE Marker").setValueAtTime(0.5, mv);
                app.endUndoGroup();
                return "ok";
            })();
        """.trimIndent()

        val evalOut = kotlinx.coroutines.runBlocking {
            repo.evalPluginScript(plugin.bundleId, applyScript)
        }.getOrThrow()
        assertEquals("ok", evalOut)

        val updatedTextLayer = vm.state.value.activeProject.layers.first { it.id == textLayerId }
        assertTrue(
            "Textevo animator must map to com.alightcreative.effects.texttransform / Text_Transform on TextLayer",
            updatedTextLayer.effects.any {
                it.effectId == "Text_Transform" ||
                    it.effectId.contains("texttransform", ignoreCase = true) ||
                    it.rawAttributes["__amEffectId"] == "com.alightcreative.effects.texttransform"
            }
        )
        assertTrue("Layer marker must be recorded on the TextLayer", updatedTextLayer.layerMarkers.any { it.comment == "Textevo In" })

        // Verify 1 undo step reverts BOTH the text animator and the layer marker atomically!
        vm.undo()
        val revertedTextLayer = vm.state.value.activeProject.layers.first { it.id == textLayerId }
        assertTrue("1 undo step must remove the applied Text_Transform effect", revertedTextLayer.effects.isEmpty())
        assertTrue("1 undo step must remove the applied layer marker", revertedTextLayer.layerMarkers.isEmpty())

        // Zero JSXBIN source leak audit: ensure no file in filesDir/cacheDir or CompatibilityReport contains decoded JS source
        val reportDump = plugin.report.toPlainTextReport()
        assertTrue("CompatibilityReport must never expose decoded JS source", !reportDump.contains("function("))
        val leakedDecodedFiles = (context.filesDir.walkTopDown() + context.cacheDir.walkTopDown())
            .filter { it.isFile && !it.name.endsWith(".jsxbin") && it.name != "keyease.jsx" }
            .filter { f ->
                val txt = runCatching { f.readText() }.getOrDefault("")
                txt.contains("ADBE Text Animators") && txt.contains("function ")
            }
            .toList()
        assertTrue("Decoded .jsxbin source must NEVER be written to disk: $leakedDecodedFiles", leakedDecodedFiles.isEmpty())

        // Unknown JSXBIN version header `@JSXBIN@ES@9.9@` -> Rejected cleanly as `Unsupported (jsxbin version 9.9)`
        val badJsxbinFile = java.io.File(context.cacheDir, "future_plugin.jsxbin")
        badJsxbinFile.writeText("@JSXBIN@ES@9.9@MyFuturePayload")
        val unsupportedPlugin = kotlinx.coroutines.runBlocking {
            repo.importPluginFromFile(badJsxbinFile)
        }.getOrThrow()
        assertEquals(com.example.scriptengine.PluginInstallStatus.UNSUPPORTED, unsupportedPlugin.report.status)
        assertEquals("Unsupported (jsxbin version 9.9)", unsupportedPlugin.report.statusBadgeText)
        assertEquals(com.example.engine.TimelinePluginUiStatus.UNSUPPORTED, unsupportedPlugin.timelineStatus())
        badJsxbinFile.delete()

        // Verify card switches & status transitions (`Shown` -> `Hidden by you` -> `Disabled`), Clear data, and Uninstall
        assertEquals(com.example.engine.TimelinePluginUiStatus.SHOWN, plugin.timelineStatus())
        repo.setShowInTimeline(plugin.bundleId, false)
        val hiddenState = repo.installedPlugins.value.first { it.bundleId == plugin.bundleId }
        assertEquals(com.example.engine.TimelinePluginUiStatus.HIDDEN_BY_YOU, hiddenState.timelineStatus())

        repo.setPluginEnabled(plugin.bundleId, false)
        val disabledState = repo.installedPlugins.value.first { it.bundleId == plugin.bundleId }
        assertEquals(com.example.engine.TimelinePluginUiStatus.DISABLED, disabledState.timelineStatus())

        repo.setAllowFileNetwork(plugin.bundleId, true)
        val netAllowedState = repo.installedPlugins.value.first { it.bundleId == plugin.bundleId }
        assertTrue(netAllowedState.isFileNetworkAllowed())

        kotlinx.coroutines.runBlocking {
            repo.clearPluginData(plugin.bundleId)
            repo.uninstallPlugin(plugin.bundleId)
        }
        assertTrue(repo.installedPlugins.value.none { it.bundleId == plugin.bundleId })
    }

    @Test
    fun `verify New_Project_25 xml lossless Alight Motion 5_0 import section 4_7 checklist`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val xmlFile = resolveFixtureFile("New_Project_25_d896cc7c.xml")
        val xmlContent = xmlFile.readText(Charsets.UTF_8)
        val imported = com.example.engine.ProjectXmlEngine.importProjectFromXml(context, xmlContent).getOrThrow().project

        // 1. Scene header: 1080x1080, 60 fps, exact 11091 ms duration, white background
        assertEquals(1080, imported.width)
        assertEquals(1080, imported.height)
        assertEquals(60, imported.fps)
        assertEquals(11091L, imported.durationMs)
        assertEquals(11091L, imported.effectiveEditDurationMs())
        assertTrue(
            "Scene background should be White (#ffffffff)",
            imported.backgroundColor.equals("White", ignoreCase = true) ||
                imported.explicitSceneBgHex.equals("#FFFFFFFF", ignoreCase = true) ||
                imported.explicitSceneBgHex.equals("#FFFFFF", ignoreCase = true)
        )

        // 2. Exact 12 sorted bookmarks
        val expectedBookmarks = listOf(8L, 775L, 1225L, 1675L, 2275L, 2741L, 3158L, 4075L, 4875L, 5941L, 6875L, 7341L)
        assertEquals(expectedBookmarks, imported.beatMarkers.map { it.timeMs })

        // 3. Exact 33 layers in XML order: 13 shape, 13 text, 6 nullobj, 1 drawing
        assertEquals(33, imported.layers.size)
        assertEquals(13, imported.layers.count { it.type == LayerType.SHAPE })
        assertEquals(13, imported.layers.count { it.type == LayerType.TEXT })
        assertEquals(6, imported.layers.count { it.type == LayerType.NULL_OBJECT })
        assertEquals(1, imported.layers.count { it.type == LayerType.DRAWING })

        // 4. Effects & keyframes preservation (20 XML effects, >= 58 keyframes, negative t, cubicBezier/bounce/cyclic)
        val totalEffects = imported.layers.sumOf { it.effects.size }
        assertTrue("Expected all 20 XML effects preserved (got $totalEffects)", totalEffects >= 20)

        val allKeyframes = imported.layers.flatMap { lyr ->
            com.example.data.model.PropertyTarget.entries.flatMap { t -> lyr.propertyFor(t).keyframes } +
                lyr.scaleY.keyframes +
                lyr.effects.flatMap { fx ->
                    com.example.data.model.EffectParamSlot.entries.flatMap { s -> fx.propertyFor(s).keyframes } +
                        fx.customParams.values.flatMap { p -> p.keyframes }
                }
        }
        assertTrue("Expected >= 58 XML keyframes preserved (got ${allKeyframes.size})", allKeyframes.size >= 58)
        assertTrue(
            "Unclamped negative normalized t keyframes must be preserved before clip start",
            imported.layers.any { lyr ->
                com.example.data.model.PropertyTarget.entries.any { t ->
                    lyr.propertyFor(t).keyframes.any { kf -> kf.timeMs < lyr.startTimeMs }
                }
            }
        )
        assertTrue("cubicBezier easing must be preserved", allKeyframes.any { it.rawEasing.startsWith("cubicBezier") })
        assertTrue("bounce easing must be preserved", allKeyframes.any { it.rawEasing.startsWith("bounce") })
        assertTrue("cyclic easing must be preserved", allKeyframes.any { it.rawEasing.startsWith("cyclic") })

        // 5. Two-pass forward parent resolution (Null 1 id 9999113076 -> parent Null 2 id 9999113077)
        val nullChild = imported.layers.find { it.rawAttributes["__amRawId"] == "9999113076" || it.id.contains("9999113076") || it.name.contains("Null 1") }
        val nullParent = imported.layers.find { it.rawAttributes["__amRawId"] == "9999113077" || it.id.contains("9999113077") || it.name.contains("Null 2") }
        org.junit.Assert.assertNotNull("Null child 9999113076 must exist", nullChild)
        org.junit.Assert.assertNotNull("Null parent 9999113077 must exist", nullParent)
        assertEquals(nullParent!!.id, nullChild!!.parentId)

        // 6. Missing amproj: media flagged cleanly without crashing, and rawAttributes preserved
        assertTrue("Missing amproj: media layer should flag isMissingMedia", imported.layers.any { it.isMissingMedia })
        assertTrue("Project rawAttributes must be preserved", imported.rawAttributes.isNotEmpty())
        assertTrue("Layer rawAttributes must be preserved", imported.layers.all { it.rawAttributes.isNotEmpty() })

        // 7. World transforms evaluate cleanly with finite values at all Section 4.7 sample timestamps
        val audio = AudioReactorEngine().sampleTimelineSpectrum(0L, 128, emptyList())
        for (sampleMs in listOf(0L, 775L, 1416L, 2266L, 4066L, 5166L)) {
            imported.layers.forEach { lyr ->
                val wt = AnimationEvaluator.evaluateWorldTransform(lyr, imported.layers, sampleMs, audio, applyActiveCamera = false)
                assertTrue("posX finite at ${sampleMs}ms for ${lyr.name}", wt.posX.isFinite())
                assertTrue("posY finite at ${sampleMs}ms for ${lyr.name}", wt.posY.isFinite())
                assertTrue("scale finite at ${sampleMs}ms for ${lyr.name}", wt.scale.isFinite())
                assertTrue("opacity finite at ${sampleMs}ms for ${lyr.name}", wt.opacity.isFinite())
            }
        }
    }

    @Test
    fun `verify playhead default durations universal feather layer keyframe jumps collapsible group masks roto and depth map`() {
        val context = ApplicationProvider.getApplicationContext<Context>()

        // 1. Default ~12.5% shorter durations (Section 2)
        assertEquals(8_750L, com.example.data.model.DEFAULT_NEW_TIMELINE_DURATION_MS)
        assertEquals(3_500L, com.example.data.model.DEFAULT_NEW_CLIP_DURATION_MS)

        // 2. Universal Feather on every effect in EffectsCatalog (Section 3.1)
        assertTrue(
            "Every effect in EffectsCatalog must expose EffectParamSlot.FEATHER",
            EffectsCatalog.allEffects.all { def ->
                def.paramSpecs().any { it.slot == com.example.data.model.EffectParamSlot.FEATHER }
            }
        )

        val vm = com.example.viewmodel.StudioViewModel(appContext = context)
        vm.createNewProject(
            name = "Editor Upgrade Verification",
            preset = AspectRatioPreset.SQUARE_1_1,
            customWidth = 720,
            customHeight = 720,
            fps = 60,
            durationSec = 9,
            bpm = 120
        )

        // 3. Selected-layer backward/forward transport jumps between keyframes & clip edges (Section 3.2)
        vm.addShapeLayerWithKind("Circle")
        val layerA = vm.state.value.selectedLayerId!!
        vm.scrubPlayhead(1000L)
        vm.pullStartToPlayhead()
        vm.scrubPlayhead(6000L)
        vm.pullEndToPlayhead()
        val clipStart = vm.state.value.activeProject.layers.first { it.id == layerA }.startTimeMs
        val clipEnd = vm.state.value.activeProject.layers.first { it.id == layerA }.endTimeMs

        vm.selectProperty(com.example.data.model.PropertyTarget.POS_X)
        vm.scrubPlayhead(3000L)
        val kfMid = vm.state.value.playheadMs
        vm.toggleKeyframeAtPlayhead(com.example.data.model.PropertyTarget.POS_X)

        vm.scrubPlayhead(0L)
        vm.jumpToBookmark(forward = true)
        assertEquals(clipStart, vm.state.value.playheadMs)
        vm.jumpToBookmark(forward = true)
        assertEquals(kfMid, vm.state.value.playheadMs)
        vm.jumpToBookmark(forward = true)
        assertEquals(clipEnd, vm.state.value.playheadMs)
        vm.jumpToBookmark(forward = false)
        assertEquals(kfMid, vm.state.value.playheadMs)

        // 4. Collapsible Group layer toggles groupExpanded and resets playhead to 0 cleanly (Section 3.3)
        vm.addShapeLayerWithKind("Star")
        val layerB = vm.state.value.selectedLayerId!!
        vm.deselectLayer()
        vm.toggleLayerMultiSelection(layerA)
        vm.toggleLayerMultiSelection(layerB)
        vm.groupSelectedLayers()
        val groupLayer = vm.state.value.activeProject.layers.first { it.type == LayerType.GROUP }
        assertEquals(2, vm.state.value.activeProject.descendantLayerIdsOf(groupLayer.id).size)

        vm.scrubPlayhead(4200L)
        val initialExpanded = groupLayer.groupExpanded
        vm.toggleGroupExpanded(groupLayer.id)
        val toggledGroup = vm.state.value.activeProject.layers.first { it.id == groupLayer.id }
        assertEquals(!initialExpanded, toggledGroup.groupExpanded)
        assertEquals("Collapsing/expanding a Group must reset playhead to 0 cleanly", 0L, vm.state.value.playheadMs)
        assertTrue("Duration must never flicker to 0", vm.state.value.activeProject.durationMs > 0L)

        // 5. Mask tool (`Rectangle`, `Ellipse`, `Pen`) starts empty and adds masks cleanly (Section 5.1)
        vm.selectLayer(layerA)
        assertTrue(vm.state.value.activeProject.layers.first { it.id == layerA }.masks.isEmpty())
        vm.addLayerMask(com.example.data.model.MaskShapeType.PEN)
        vm.addPenMaskVertex(0.2f, 0.2f)
        vm.addPenMaskVertex(0.8f, 0.2f)
        vm.addPenMaskVertex(0.5f, 0.8f)
        val penMask = vm.state.value.activeProject.layers.first { it.id == layerA }.masks.single()
        assertEquals(3, penMask.vertices.size)
        assertTrue(penMask.closed)

        // 6. Roto engine segmentation, stroke undo/redo, multi-frame propagation, and cancel (Section 5.2)
        val roto = vm.rotoEngine
        roto.markModelProvisionedOfflineForTest()
        assertTrue(roto.isModelInstalled())
        roto.addBrushStroke(com.example.rotoengine.RotoBrushStroke(listOf(0.5f to 0.5f, 0.55f to 0.55f), 0.06f))
        assertEquals(1, roto.strokes.value.size)
        assertTrue(roto.undoStroke())
        assertEquals(0, roto.strokes.value.size)
        assertTrue(roto.redoStroke())
        assertEquals(1, roto.strokes.value.size)

        val synthFrames = listOf(0L, 100L, 200L).associateWith { idx ->
            android.graphics.Bitmap.createBitmap(128, 128, android.graphics.Bitmap.Config.ARGB_8888).apply {
                val c = android.graphics.Canvas(this)
                c.drawColor(android.graphics.Color.DKGRAY)
                val p = android.graphics.Paint().apply { color = android.graphics.Color.RED }
                val shift = (idx / 20L).toFloat()
                c.drawCircle(64f + shift, 64f, 28f, p)
            }
        }
        val track = kotlinx.coroutines.runBlocking {
            roto.propagateTrack(
                anchorTimeMs = 0L,
                frameTimesMs = listOf(0L, 100L, 200L),
                frameProvider = { t -> synthFrames[t] },
                manualStrokesByFrame = mapOf(0L to roto.strokes.value),
                mode = com.example.rotoengine.RotoTargetMode.REMOVE_BACKGROUND,
                featherPx = 3f,
                onProgress = { _, _ -> }
            )
        }.getOrThrow()
        assertEquals(3, track.framesByTimeMs.size)
        assertTrue(track.framesByTimeMs[200L]!!.foregroundAreaFraction() > 0.02f)

        // 7. Depth Map engine licenses, live adjustments without re-inference, multi-project import, and session release (Section 6)
        assertEquals("Apache-2.0", com.example.depthengine.DepthModelVariant.SMALL.licenseBadge)
        assertTrue(com.example.depthengine.DepthModelVariant.SMALL.isCommercialUseAllowed)
        assertTrue(com.example.depthengine.DepthModelVariant.BASE.licenseBadge.startsWith("CC-BY-NC-4.0"))
        assertTrue(!com.example.depthengine.DepthModelVariant.BASE.isCommercialUseAllowed)
        assertTrue(!com.example.depthengine.DepthModelVariant.LARGE.isCommercialUseAllowed)

        val depthEngine = vm.depthEngine
        depthEngine.markVariantProvisionedOfflineForTest(com.example.depthengine.DepthModelVariant.SMALL)
        val srcBmp = synthFrames[0L]!!
        val rawDepth = kotlinx.coroutines.runBlocking {
            depthEngine.computeDepthBuffer("synth_0", srcBmp, com.example.depthengine.DepthModelVariant.SMALL)
        }
        val previewGray = kotlinx.coroutines.runBlocking {
            depthEngine.renderDepthPreview(
                srcBmp,
                rawDepth,
                com.example.depthengine.DepthAdjustParams(
                    colormap = com.example.depthengine.DepthColormap.GRAYSCALE,
                    contrast = 1.3f,
                    invert = false
                )
            )
        }
        assertEquals(srcBmp.width, previewGray.width)
        val grayCenterPixel = previewGray.getPixel(64, 64)
        val previewTurboInverted = kotlinx.coroutines.runBlocking {
            depthEngine.renderDepthPreview(
                srcBmp,
                rawDepth,
                com.example.depthengine.DepthAdjustParams(
                    colormap = com.example.depthengine.DepthColormap.TURBO,
                    contrast = 1.6f,
                    invert = true
                )
            )
        }
        val turboCenterPixel = previewTurboInverted.getPixel(64, 64)
        assertTrue(grayCenterPixel != turboCenterPixel)

        val depthPng = java.io.File(context.cacheDir, "depth_test_export.png")
        depthPng.outputStream().use { out ->
            assertTrue(previewGray.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out))
        }
        val layersBeforeDepthImport = vm.state.value.activeProject.layers.size
        vm.importDepthMapIntoProjects(
            depthPngFile = depthPng,
            targetProjectIds = listOf(vm.state.value.activeProject.id),
            mode = com.example.ui.screens.DepthImportMode.IMAGE_LAYER
        )
        assertEquals(layersBeforeDepthImport + 1, vm.state.value.activeProject.layers.size)
        depthEngine.releaseSession()
        depthPng.delete()
    }
}
