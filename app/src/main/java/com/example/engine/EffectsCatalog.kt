package com.example.engine

import com.example.data.model.EffectNode

enum class EffectCategory(val title: String, val badge: String) {
    DESKTOP_HOMAGES("Desktop Suite Homages", "Sapphire / BCC / AE"),
    NODE_PROCEDURAL("Node Video & KeyFlow Procedural", "Particles / 3D / Warp"),
    ALIGHT_STAPLES("Alight Motion Staples", "95+ Core Effects"),
    VIRAL_SOCIAL("CapCut / Viral Social", "Beat / Glitch / Shake")
}

data class EffectDefinition(
    val id: String,
    val name: String,
    val category: EffectCategory,
    val shaderMode: Int,
    val defaultIntensity: Float = 0.65f,
    val defaultSecondary: Float = 0.50f,
    val defaultFrequency: Float = 0.35f,
    val primaryLabel: String = "Intensity",
    val secondaryLabel: String = "Radius / Spread",
    val frequencyLabel: String = "Frequency / Phase",
    val summary: String
) {
    fun toNode(): EffectNode = EffectNode(
        effectId = id,
        displayName = name,
        category = category.title,
        enabled = true,
        intensity = defaultIntensity,
        secondaryParam = defaultSecondary,
        frequencyParam = defaultFrequency
    )
}

object EffectsCatalog {
    // Shader modes mapped inside OpenGL ES 2.0 Ping-Pong Fragment Shader:
    // 1 = Optical Glow / Bloom
    // 2 = Chromatic Aberration / RGB Split
    // 3 = Wave / Turbulent / Polar Warp
    // 4 = Glitch Scanlines / Block Dissolve / Time Quant
    // 5 = Kaleidoscope / Mirror / Tile Repeat
    // 6 = Radial / Zoom / Directional Blur
    // 7 = Procedural Particles / Starfield / Snow / Fire
    // 8 = Edge / Cartoon / Find Edges / Halftone / Threshold
    // 9 = Hue / Iridescence / Bleach Bypass / Gradient Map
    // 10 = Camera Shake / Squeeze / Spin / Pulse

    val allEffects: List<EffectDefinition> = buildList {
        // Category A: Desktop Suite Homages (14)
        val catA = listOf(
            Triple("S_Glow", 1, "Optical multi-kernel Sapphire bloom"),
            Triple("BCC_FilmGlow", 1, "Warm halation film stock glow"),
            Triple("S_Glint", 1, "Starburst specular highlight glint"),
            Triple("S_LensFlare", 1, "Anamorphic streak & iris flare"),
            Triple("S_Zap", 7, "Procedural electric arc bolt"),
            Triple("S_Rays", 6, "Volumetric god-rays radial beam"),
            Triple("S_Shake", 10, "Organic handheld motion impulse"),
            Triple("S_WarpPolar", 3, "Cartesian-to-polar tunnel warp"),
            Triple("S_Caustics", 3, "Refractive underwater light web"),
            Triple("S_Cartoon", 8, "Quantized cel-shade & ink edge"),
            Triple("S_BleachBypass", 9, "High-contrast silver retention grade"),
            Triple("BCC_LightLeaks", 9, "Organic warm cinema edge burn"),
            Triple("BCC_FilmDamage", 4, "Procedural grain, scratches & gate weave"),
            Triple("S_Vignette", 9, "Smooth optical falloff framing")
        )
        catA.forEach { (id, mode, desc) ->
            add(EffectDefinition(id, id.replace('_', ' '), EffectCategory.DESKTOP_HOMAGES, mode, summary = desc))
        }

        // Category B: Node Video & KeyFlow Procedural Effects (12)
        val catB = listOf(
            Triple("Particular_Fire", 7, "Buoyant ember & flame emitter"),
            Triple("Particular_Smoke", 7, "Curl-noise volumetric plume"),
            Triple("Particular_Snow", 7, "Depth-layered crystalline drift"),
            Triple("Form_Grid", 5, "3D audio-reactive vertex lattice"),
            Triple("Mir_Terrain", 3, "Fractal wireframe ridge mesh"),
            Triple("3D_Stroke", 8, "Tapered neon 3D path ribbon"),
            Triple("Stardust_Particles", 7, "Vortex orbital particle field"),
            Triple("Chromatic_Aberration", 2, "Lens fringe R/G/B dispersion"),
            Triple("Fractal_Warp", 3, "Multi-octave simplex fluid warp"),
            Triple("Hexagon_Tile_Shift", 5, "Honeycomb UV cell displacement"),
            Triple("Turbulent_Displace", 3, "Organic Perlin UV distortion"),
            Triple("Audio_Reactor", 1, "FFT spectrum parameter driver")
        )
        catB.forEach { (id, mode, desc) ->
            add(EffectDefinition(id, id.replace('_', ' '), EffectCategory.NODE_PROCEDURAL, mode, summary = desc))
        }

        // Category C: Alight Motion Community Staples (90)
        val catC = listOf(
            "Squeeze" to 10,
            "RGB_Split" to 2,
            "Zoom_Blur" to 6,
            "Motion_Blur" to 6,
            "Wave_Warp" to 3,
            "Bend" to 3,
            "Blink" to 4,
            "Block_Dissolve" to 4,
            "Channel_Remap" to 9,
            "Checkered" to 5,
            "Chroma_Key" to 9,
            "Circular_Ripple" to 3,
            "Colorize" to 9,
            "Copy_Background" to 1,
            "Cube" to 5,
            "Box" to 5,
            "Cylinder" to 3,
            "Curl" to 3,
            "Dark_Glow" to 1,
            "Directional_Blur" to 6,
            "Displacement_Map" to 3,
            "Dots" to 8,
            "Halftone_Dots" to 8,
            "Drawing_Progress" to 8,
            "Echo_Keyframes" to 6,
            "Edge_Glow" to 8,
            "Electric_Edges" to 8,
            "Find_Edges" to 8,
            "Exposure_Gamma" to 9,
            "Fade_In_Out" to 9,
            "Feather" to 6,
            "Fill_Behind" to 1,
            "Flicker" to 4,
            "Flip_Layer" to 5,
            "Four_Color_Gradient" to 9,
            "Fractal_Ridges" to 3,
            "Gaussian_Blur" to 6,
            "Glass" to 3,
            "Gradient_Map" to 9,
            "Grid" to 5,
            "Hexagon_Array" to 5,
            "Highlights_Shadows" to 9,
            "Hue_Shift" to 9,
            "Inner_Blur" to 6,
            "Inner_Glow" to 1,
            "Invert" to 9,
            "Iridescence" to 9,
            "Kaleidoscope" to 5,
            "Lightning" to 7,
            "Linear_Repeat" to 5,
            "Radial_Repeat" to 5,
            "Long_Shadow" to 8,
            "Luma_Key" to 9,
            "Mask_Blur" to 6,
            "Matte_Choker" to 8,
            "Mirror" to 5,
            "Mosaic" to 4,
            "Move_Along_Path" to 10,
            "Noise" to 4,
            "Offset" to 5,
            "Palette_Map" to 9,
            "Polar_Coords" to 3,
            "Posterize" to 8,
            "Pulse_Opacity" to 10,
            "Pulse_Size" to 10,
            "Raster_Extrude" to 5,
            "Replace_Color" to 9,
            "Roughen_Edges" to 8,
            "Saturation_Vibrance" to 9,
            "Sharpen" to 8,
            "Simple_Starfield" to 7,
            "Smooth_Bevel" to 8,
            "Smooth_Edges" to 6,
            "Spectral_Map" to 9,
            "Spherize" to 3,
            "Spin" to 10,
            "Spot_Color" to 9,
            "Star" to 1,
            "Stretch_Axis" to 10,
            "Stripes" to 5,
            "Stroke_Color" to 8,
            "Swing" to 10,
            "Swirl" to 3,
            "Text_Progress" to 4,
            "Threshold" to 8,
            "Tile_Rotate" to 5,
            "Tiles" to 5,
            "Time_Quantization" to 4,
            "Timecode" to 4,
            "Torus" to 3,
            "Tunnel" to 3,
            "Voronoi_Cells" to 5,
            "Wire" to 8,
            "Wipe" to 4,
            "Zoom_Streaks" to 6
        )
        catC.forEach { (id, mode) ->
            add(
                EffectDefinition(
                    id = id,
                    name = id.replace('_', ' '),
                    category = EffectCategory.ALIGHT_STAPLES,
                    shaderMode = mode,
                    summary = "GPU real-time ${id.replace('_', ' ').lowercase()} shader node"
                )
            )
        }

        // Category D: CapCut / Viral Social Media Effects (11)
        val catD = listOf(
            Triple("Smooth_Zoom", 6, "Velocity-curved punch-in zoom"),
            Triple("Glitch_Scanlines", 4, "VHS CRT horizontal sync tear"),
            Triple("Beat_Sync_Pulse", 10, "Kick-drum synced scale & bloom hit"),
            Triple("Camera_Shake_Heavy", 10, "Aggressive phonk edit impact shake"),
            Triple("Flash_Transition", 1, "Overexposed white-out impact flash"),
            Triple("Outline_Glow", 8, "Alpha contour neon aura"),
            Triple("Green_Screen_Key", 9, "Spill-suppressed chroma keyer"),
            Triple("Speed_Ramp_Blur", 6, "Optical velocity motion trail"),
            Triple("Fake_Lag_Spin", 10, "Posterized frame-drop whip spin"),
            Triple("Manga_Zoom_Shake", 8, "High-contrast speedline impact"),
            Triple("Rainbow_Glitch", 2, "Prismatic RGB slice displacement")
        )
        catD.forEach { (id, mode, desc) ->
            add(EffectDefinition(id, id.replace('_', ' '), EffectCategory.VIRAL_SOCIAL, mode, summary = desc))
        }
    }

    private val byId = allEffects.associateBy { it.id }

    fun findById(effectId: String): EffectDefinition =
        byId[effectId] ?: allEffects.first()
}
