package com.example.engine

import com.example.data.model.AnimatableProperty
import com.example.data.model.EffectNode
import com.example.data.model.EffectParamSlot
import kotlin.math.abs

enum class EffectCategory(val title: String) {
    DESKTOP_HOMAGE("Desktop Suite Homages"),
    ALIGHT_STAPLES("Alight Motion Staples"),
    TRANSITIONS("Transitions & Wipes"),
    COLOR_LUTS("Color & LUTs"),
    BLUR_OPTICS("Blur & Optics"),
    GLITCH_VHS("Glitch & Retro"),
    PARTICLES_LIGHT("Particles & Light"),
    STYLIZE_EDGES("Stylize & Edges"),
    CAMERA_SHAKE("3D Camera & Shake")
}

data class EffectParamSpec(
    val slot: EffectParamSlot,
    val label: String,
    val minVal: Float,
    val maxVal: Float,
    val unit: String = "",
    val fineStep: Float = 0.01f
)

/**
 * ShaderMode mapping for real-time OpenGL ES 2.0 multipass fragment pipelines:
 * 1 = Optical Glow / Bloom / Rays
 * 2 = Chromatic Aberration / RGB Split
 * 3 = Wave Warp / Liquid / Pinch / Bulge / Squeeze
 * 4 = Glitch / Scanlines / VHS / Datamosh / Pixel Sort
 * 5 = Mirror / Tiles / Kaleidoscope / Repeater
 * 6 = Directional / Radial / Motion / Gaussian Blur
 * 7 = Procedural Particles / Stardust / Sparks / Snow
 * 8 = Edge Detect / Neon Contours / Halftone / Emboss
 * 9 = Color Matrix / Exposure-Gamma / Hue / LUT / Tint
 * 10 = Camera Shake / Oscillate / Swing / Beat Zoom
 * 11 = Text Transform & Range Wipe / Directional Reveal
 * 12 = Chroma Key / Luma Key (with Eyedropper Key Color!)
 */
data class EffectDefinition(
    val id: String,
    val name: String,
    val category: EffectCategory,
    val shaderMode: Int,
    val defaultIntensity: Float = 0.65f,
    val defaultSecondary: Float = 0.50f,
    val defaultFrequency: Float = 0.35f,
    val defaultAngle: Float = 0f,
    val defaultMix: Float = 100f,
    val defaultTintHex: String = "#FFFFFF",
    val colorLabel: String = "Tint Color",
    val summary: String
) {
    fun toNode(customIntensity: Float = defaultIntensity): EffectNode = EffectNode(
        effectId = id,
        displayName = name,
        category = category.title,
        intensity = customIntensity,
        secondaryParam = defaultSecondary,
        frequencyParam = defaultFrequency,
        angleParam = defaultAngle,
        mixParam = defaultMix,
        tintColorHex = defaultTintHex,
        primaryProp = AnimatableProperty(customIntensity),
        secondaryProp = AnimatableProperty(defaultSecondary),
        frequencyProp = AnimatableProperty(defaultFrequency),
        angleProp = AnimatableProperty(defaultAngle),
        mixProp = AnimatableProperty(defaultMix)
    )

    /**
     * Authentic Per-Effect Alight Motion / Sapphire / BCC Parameter Definitions!
     * Every effect exposes its real domain-specific property names, units, and min/max ranges,
     * all 100% keyframeable with controllable Bezier curves!
     */
    fun paramSpecs(): List<EffectParamSpec> {
        EffectsCatalog.customSpecsForEffect(id)?.let { return it }
        return when (shaderMode) {
            1 -> listOf(
                EffectParamSpec(EffectParamSlot.PRIMARY, "Brightness", 0f, 2.0f, "x", 0.01f),
                EffectParamSpec(EffectParamSlot.SECONDARY, "Radius", 0f, 1.5f, "", 0.01f),
                EffectParamSpec(EffectParamSlot.FREQUENCY, "Shimmer Hz", 0f, 4.0f, "Hz", 0.02f),
                EffectParamSpec(EffectParamSlot.ANGLE, "Beam Angle", -180f, 180f, "°", 1f),
                EffectParamSpec(EffectParamSlot.MIX, "Alpha Mix", 0f, 100f, "%", 1f)
            )
            2 -> listOf(
                EffectParamSpec(EffectParamSlot.PRIMARY, "Split Offset", 0f, 1.5f, "", 0.01f),
                EffectParamSpec(EffectParamSlot.SECONDARY, "Edge Fringe", 0f, 1.0f, "", 0.01f),
                EffectParamSpec(EffectParamSlot.FREQUENCY, "Jitter Rate", 0f, 4.0f, "Hz", 0.02f),
                EffectParamSpec(EffectParamSlot.ANGLE, "Prism Angle", -180f, 180f, "°", 1f),
                EffectParamSpec(EffectParamSlot.MIX, "Mix", 0f, 100f, "%", 1f)
            )
            3 -> listOf(
                EffectParamSpec(EffectParamSlot.PRIMARY, "Magnitude", -1.5f, 1.5f, "", 0.01f),
                EffectParamSpec(EffectParamSlot.SECONDARY, "Spacing", 0.05f, 2.0f, "", 0.01f),
                EffectParamSpec(EffectParamSlot.FREQUENCY, "Phase", 0f, 4.0f, "", 0.02f),
                EffectParamSpec(EffectParamSlot.ANGLE, "Direction", -180f, 180f, "°", 1f),
                EffectParamSpec(EffectParamSlot.MIX, "Mix", 0f, 100f, "%", 1f)
            )
            4 -> listOf(
                EffectParamSpec(EffectParamSlot.PRIMARY, "Tear Amount", 0f, 1.5f, "", 0.01f),
                EffectParamSpec(EffectParamSlot.SECONDARY, "Block Density", 0.05f, 1.5f, "", 0.01f),
                EffectParamSpec(EffectParamSlot.FREQUENCY, "Clock Jitter", 0f, 4.0f, "Hz", 0.02f),
                EffectParamSpec(EffectParamSlot.ANGLE, "Scan Angle", -180f, 180f, "°", 1f),
                EffectParamSpec(EffectParamSlot.MIX, "Mix", 0f, 100f, "%", 1f)
            )
            5 -> listOf(
                EffectParamSpec(EffectParamSlot.PRIMARY, "Crop", 0.25f, 3.0f, "x", 0.01f),
                EffectParamSpec(EffectParamSlot.SECONDARY, "Offset", 0f, 2.0f, "", 0.01f),
                EffectParamSpec(EffectParamSlot.FREQUENCY, "Phase", -2.0f, 2.0f, "", 0.01f),
                EffectParamSpec(EffectParamSlot.ANGLE, "Angle", -180f, 180f, "°", 1f),
                EffectParamSpec(EffectParamSlot.MIX, "Mirror Mix", 0f, 100f, "%", 1f)
            )
            6 -> listOf(
                EffectParamSpec(EffectParamSlot.PRIMARY, "Strength", 0f, 2.0f, "", 0.01f),
                EffectParamSpec(EffectParamSlot.SECONDARY, "Center Bias", 0f, 1.0f, "", 0.01f),
                EffectParamSpec(EffectParamSlot.FREQUENCY, "Samples Boost", 0f, 1.5f, "", 0.01f),
                EffectParamSpec(EffectParamSlot.ANGLE, "Angle", -180f, 180f, "°", 1f),
                EffectParamSpec(EffectParamSlot.MIX, "Mix", 0f, 100f, "%", 1f)
            )
            7 -> listOf(
                EffectParamSpec(EffectParamSlot.PRIMARY, "Birth Rate", 0f, 2.0f, "x", 0.01f),
                EffectParamSpec(EffectParamSlot.SECONDARY, "Particle Scale", 0.05f, 1.5f, "", 0.01f),
                EffectParamSpec(EffectParamSlot.FREQUENCY, "Velocity", 0f, 4.0f, "x", 0.02f),
                EffectParamSpec(EffectParamSlot.ANGLE, "Wind Angle", -180f, 180f, "°", 1f),
                EffectParamSpec(EffectParamSlot.MIX, "Mix", 0f, 100f, "%", 1f)
            )
            8 -> listOf(
                EffectParamSpec(EffectParamSlot.PRIMARY, "Edge Gain", 0f, 2.0f, "x", 0.01f),
                EffectParamSpec(EffectParamSlot.SECONDARY, "Line Width", 0.05f, 2.0f, "px", 0.01f),
                EffectParamSpec(EffectParamSlot.FREQUENCY, "Threshold", 0f, 1.0f, "", 0.01f),
                EffectParamSpec(EffectParamSlot.ANGLE, "Hatch Angle", -180f, 180f, "°", 1f),
                EffectParamSpec(EffectParamSlot.MIX, "Mix", 0f, 100f, "%", 1f)
            )
            9 -> listOf(
                EffectParamSpec(EffectParamSlot.PRIMARY, "Exposure / Shift", -2.0f, 2.0f, "", 0.01f),
                EffectParamSpec(EffectParamSlot.SECONDARY, "Gamma", 0.10f, 3.0f, "", 0.01f),
                EffectParamSpec(EffectParamSlot.FREQUENCY, "Offset", -1.0f, 1.0f, "", 0.01f),
                EffectParamSpec(EffectParamSlot.ANGLE, "Hue Wheel", -180f, 180f, "°", 1f),
                EffectParamSpec(EffectParamSlot.MIX, "LUT Blend", 0f, 100f, "%", 1f)
            )
            11 -> listOf(
                EffectParamSpec(EffectParamSlot.PRIMARY, "Start", 0f, 100f, "%", 0.5f),
                EffectParamSpec(EffectParamSlot.SECONDARY, "End", 0f, 100f, "%", 0.5f),
                EffectParamSpec(EffectParamSlot.FREQUENCY, "Phase", -100f, 100f, "%", 0.5f),
                EffectParamSpec(EffectParamSlot.ANGLE, "Angle", -180f, 180f, "°", 1f),
                EffectParamSpec(EffectParamSlot.MIX, "Scale / Fade", 0f, 100f, "%", 1f)
            )
            12 -> listOf(
                EffectParamSpec(EffectParamSlot.PRIMARY, "Threshold", 0f, 100f, "%", 0.5f),
                EffectParamSpec(EffectParamSlot.SECONDARY, "Feather", 0f, 100f, "%", 0.5f),
                EffectParamSpec(EffectParamSlot.FREQUENCY, "Defringe", 0f, 100f, "%", 0.5f),
                EffectParamSpec(EffectParamSlot.ANGLE, "Spill Suppress", 0f, 100f, "%", 1f),
                EffectParamSpec(EffectParamSlot.MIX, "Mix", 0f, 100f, "%", 1f)
            )
            else -> listOf(
                EffectParamSpec(EffectParamSlot.PRIMARY, "Magnitude", 0f, 2.0f, "", 0.01f),
                EffectParamSpec(EffectParamSlot.SECONDARY, "Secondary Axis", 0f, 1.5f, "", 0.01f),
                EffectParamSpec(EffectParamSlot.FREQUENCY, "Frequency", 0.05f, 8.0f, "Hz", 0.05f),
                EffectParamSpec(EffectParamSlot.ANGLE, "Angle", -180f, 180f, "°", 1f),
                EffectParamSpec(EffectParamSlot.MIX, "Mix", 0f, 100f, "%", 1f)
            )
        }
    }

    fun specFor(slot: EffectParamSlot): EffectParamSpec =
        paramSpecs().firstOrNull { it.slot == slot } ?: paramSpecs().first()
}

object EffectsCatalog {

    val allEffects: List<EffectDefinition> = buildList {
        // 1. Desktop Suite Homages (Sapphire S_*, Boris Continuum BCC_*, Trapcode/Stardust/Optical/RSMB) - 22 Effects
        val desktop = listOf(
            EffectDefinition("S_Glow", "S_Glow (Optical Bloom)", EffectCategory.DESKTOP_HOMAGE, 1, 0.80f, 0.55f, 0f, 0f, 100f, "#FFFFFF", "Glow Color", "Multi-threshold inverse-square optical glow"),
            EffectDefinition("S_GlowEdges", "S_GlowEdges", EffectCategory.DESKTOP_HOMAGE, 8, 0.75f, 0.50f, 0.20f, 0f, 100f, "#00D2A8", "Edge Glow Color", "Sobel contour extraction with neon bloom"),
            EffectDefinition("S_Rays", "S_Rays (Volumetric)", EffectCategory.DESKTOP_HOMAGE, 1, 0.85f, 0.72f, 0.25f, 25f, 100f, "#FFF3C4", "Rays Color", "Radial god-ray light shafts from highlights"),
            EffectDefinition("S_BlurMoCurves", "S_BlurMoCurves", EffectCategory.DESKTOP_HOMAGE, 6, 0.65f, 0.50f, 0.40f, 0f, 100f, "#FFFFFF", "Tint Color", "Motion-vector trajectory directional blur"),
            EffectDefinition("S_Shake", "S_Shake (Handheld)", EffectCategory.DESKTOP_HOMAGE, 10, 0.55f, 0.65f, 2.4f, 12f, 100f, "#FFFFFF", "Tint Color", "2D/Z-roll stochastic cinema camera shake"),
            EffectDefinition("S_WarpChroma", "S_WarpChroma", EffectCategory.DESKTOP_HOMAGE, 2, 0.70f, 0.55f, 0.25f, 0f, 100f, "#FFFFFF", "Fringe Tint", "Spectral dispersion warp across lens edges"),
            EffectDefinition("S_Kaleido", "S_Kaleido", EffectCategory.DESKTOP_HOMAGE, 5, 1.25f, 0.50f, 0f, 30f, 100f, "#FFFFFF", "Tint Color", "Octagonal mirrored symmetry reflector"),
            EffectDefinition("S_Glint", "S_Glint (Starburst)", EffectCategory.DESKTOP_HOMAGE, 1, 0.70f, 0.60f, 0.35f, 45f, 100f, "#E0F7FA", "Star Color", "4-point specular star diffraction"),
            EffectDefinition("S_Flicker", "S_Flicker", EffectCategory.DESKTOP_HOMAGE, 9, 0.50f, 1.0f, 4.5f, 0f, 100f, "#FFFFFF", "Tint Color", "Exposure luma strobe & cinema projector flicker"),
            EffectDefinition("S_Vignette", "S_Vignette", EffectCategory.DESKTOP_HOMAGE, 9, -0.65f, 1.15f, 0f, 0f, 100f, "#FFFFFF", "Vignette Tint", "Anamorphic optical falloff vignette"),
            EffectDefinition("S_DistortRGB", "S_DistortRGB", EffectCategory.DESKTOP_HOMAGE, 2, 0.75f, 0.65f, 0.50f, 18f, 100f, "#FFFFFF", "Tint Color", "Independent R/G/B barrel distortion"),
            EffectDefinition("BCC_LensBlur", "BCC Lens Blur", EffectCategory.DESKTOP_HOMAGE, 6, 0.65f, 0.45f, 0.50f, 0f, 100f, "#FFFFFF", "Bokeh Tint", "Hexagonal iris bokeh defocus"),
            EffectDefinition("BCC_LightSweep", "BCC Light Sweep", EffectCategory.DESKTOP_HOMAGE, 1, 0.60f, 0.45f, 0.60f, 35f, 100f, "#00E5FF", "Sweep Color", "Specular diagonal light sheen across alpha"),
            EffectDefinition("BCC_FilmDamage", "BCC Film Damage", EffectCategory.DESKTOP_HOMAGE, 4, 0.50f, 0.40f, 1.2f, 90f, 100f, "#FFE0B2", "Sepia Tint", "35mm dust, scratches & gate weave"),
            EffectDefinition("BCC_Prism", "BCC Prism", EffectCategory.DESKTOP_HOMAGE, 2, 0.65f, 0.50f, 0.15f, -20f, 100f, "#FFFFFF", "Prism Tint", "Linear chromatic prism refraction"),
            EffectDefinition("BCC_Turbulence", "BCC Turbulence", EffectCategory.DESKTOP_HOMAGE, 3, 0.60f, 0.55f, 0.80f, 0f, 100f, "#FFFFFF", "Tint Color", "Simplex Perlin fluid displacement"),
            EffectDefinition("BCC_ChromaKey", "BCC Chroma Key Studio", EffectCategory.DESKTOP_HOMAGE, 12, 26f, 14f, 20f, 35f, 100f, "#00FF00", "Key Color", "Spill-suppressed green/blue screen keyer"),
            EffectDefinition("BCC_FilmGlow", "BCC Film Glow", EffectCategory.DESKTOP_HOMAGE, 1, 0.72f, 0.65f, 0f, 0f, 100f, "#FFD54F", "Halation Color", "Warm halation film stock bloom"),
            EffectDefinition("Stardust_Particles", "Stardust Emitter", EffectCategory.DESKTOP_HOMAGE, 7, 0.80f, 0.50f, 0.65f, -90f, 100f, "#00D2A8", "Particle Color", "GPU field-driven luminescent particle swarm"),
            EffectDefinition("Particular_Streaks", "Particular Light Streaks", EffectCategory.DESKTOP_HOMAGE, 7, 0.75f, 0.65f, 0.85f, -35f, 100f, "#FF3366", "Streak Color", "Ribbon trails & motion-warped embers"),
            EffectDefinition("Optical_Flares", "Optical Flares Anamorphic", EffectCategory.DESKTOP_HOMAGE, 1, 0.90f, 0.80f, 0.15f, 0f, 100f, "#40C4FF", "Flare Color", "JJ-style horizontal cyan anamorphic flare"),
            EffectDefinition("RSMB_MotionBlur", "RSMB Vector Blur", EffectCategory.DESKTOP_HOMAGE, 6, 0.78f, 0.50f, 0.60f, 0f, 100f, "#FFFFFF", "Tint Color", "Sub-frame optical flow motion blur")
        )
        addAll(desktop)

        // 2. Alight Motion Staples (now including authentic Text Transform + Text Spacing!) - 20 Effects
        val alightStaples = listOf(
            EffectDefinition("Text_Transform", "Text Transform", EffectCategory.ALIGHT_STAPLES, 11, 0f, 100f, 0f, -42f, 100f, "#FFFFFF", "Highlight Tint", "Alight Motion per-character Start/End/Phase/Angle/Scale/Alpha range animator"),
            EffectDefinition("Text_Spacing", "Text Spacing & Tracking", EffectCategory.ALIGHT_STAPLES, 11, 0f, 100f, 18f, 0f, 100f, "#FFFFFF", "Text Tint", "Dynamic letter tracking & line spacing expander"),
            EffectDefinition("Tiles", "Tiles (Seamless Mirror)", EffectCategory.ALIGHT_STAPLES, 5, 1.35f, 0.50f, 0f, 0f, 100f, "#FFFFFF", "Tint Color", "Crop, Offset, Phase & Mirror seamless grid"),
            EffectDefinition("Oscillate", "Oscillate", EffectCategory.ALIGHT_STAPLES, 10, 45f, 0.50f, 2.0f, 45f, 100f, "#FFFFFF", "Tint Color", "Angle, Frequency (Hz), Magnitude (px) & Sine/Triangle Phase"),
            EffectDefinition("Swing", "Swing Pendulum", EffectCategory.ALIGHT_STAPLES, 10, -24f, 24f, 1.5f, 0f, 100f, "#FFFFFF", "Tint Color", "Frequency, Angle 1, Angle 2 & Phase rotational rocker"),
            EffectDefinition("Wave_Warp", "Wave Warp", EffectCategory.ALIGHT_STAPLES, 3, 0.45f, 20f, 0.5f, 0f, 100f, "#FFFFFF", "Tint Color", "Phase, Direction Angle, Spacing & Magnitude sine wave"),
            EffectDefinition("Pinch_Bulge", "Pinch / Bulge", EffectCategory.ALIGHT_STAPLES, 3, 0.58f, 0.65f, 0.5f, 0f, 100f, "#FFFFFF", "Tint Color", "Strength (-1 to +1), Radius & Center spherical lens"),
            EffectDefinition("Squeeze", "Squeeze", EffectCategory.ALIGHT_STAPLES, 3, 0.52f, 0.50f, 0f, 0f, 100f, "#FFFFFF", "Tint Color", "Strength, Angle & Center anamorphic compression"),
            EffectDefinition("Exposure_Gamma", "Exposure / Gamma", EffectCategory.ALIGHT_STAPLES, 9, 0.35f, 1.00f, 0.0f, 0f, 100f, "#FFFFFF", "Highlight Tint", "Exposure (-2..+2 EV), Gamma (0.1..5) & Offset"),
            EffectDefinition("Chromatic_Aberration", "Chromatic Key Shift", EffectCategory.ALIGHT_STAPLES, 2, 0.62f, 0.50f, 0f, 0f, 100f, "#FFFFFF", "Fringe Tint", "Strength, Angle & Center RGB split"),
            EffectDefinition("Motion_Blur_Tile", "Motion Blur Shutter", EffectCategory.ALIGHT_STAPLES, 6, 0.70f, 0.50f, 1.0f, 0f, 100f, "#FFFFFF", "Tint Color", "Tune (180° shutter), Scale & Velocity blur"),
            EffectDefinition("Turbulent_Displace", "Turbulent Displace", EffectCategory.ALIGHT_STAPLES, 3, 0.65f, 1.0f, 0.8f, 0f, 100f, "#FFFFFF", "Tint Color", "Magnitude, Scale, Evolution & Octaves warp"),
            EffectDefinition("Polar_Coordinates", "Polar Coordinates", EffectCategory.ALIGHT_STAPLES, 5, 1.0f, 0.5f, 0f, 0f, 100f, "#FFFFFF", "Tint Color", "Rectangular-to-polar tunnel mapping"),
            EffectDefinition("Swirl_Twirl", "Twirl Vortex", EffectCategory.ALIGHT_STAPLES, 3, 0.68f, 0.60f, 0.5f, 90f, 100f, "#FFFFFF", "Tint Color", "Spiral angular strength, Radius & Center"),
            EffectDefinition("Bend_Arc", "Bend", EffectCategory.ALIGHT_STAPLES, 3, 0.48f, 0.50f, 0f, 45f, 100f, "#FFFFFF", "Tint Color", "Angle, Origin & Curvature arc bender"),
            EffectDefinition("Ripple_Pond", "Circular Ripple", EffectCategory.ALIGHT_STAPLES, 3, 0.55f, 16f, 2.0f, 0f, 100f, "#FFFFFF", "Tint Color", "Frequency, Strength, Phase & Radius waves"),
            EffectDefinition("Fractal_Warp", "Fractal Ridges", EffectCategory.ALIGHT_STAPLES, 3, 0.60f, 1.2f, 0.6f, 15f, 100f, "#FFFFFF", "Tint Color", "Parallax, Magnitude, Detail & Evolution"),
            EffectDefinition("Raster_Extrude", "3D Extrude Bevel", EffectCategory.ALIGHT_STAPLES, 8, 0.64f, 0.75f, 0.25f, 45f, 100f, "#00D2A8", "Extrude Color", "Depth, Bevel Angle & Rim Illumination"),
            EffectDefinition("Vector_Repeater", "Linear Repeater", EffectCategory.ALIGHT_STAPLES, 5, 1.80f, 0.45f, 0.25f, 0f, 100f, "#FFFFFF", "Tint Color", "Count, Offset, Scale Step & Alpha Falloff"),
            EffectDefinition("Copy_Background", "Copy Background", EffectCategory.ALIGHT_STAPLES, 5, 1.0f, 0.50f, 0f, 0f, 100f, "#FFFFFF", "Glass Tint", "Samples underlying composite for adjustment layers")
        )
        addAll(alightStaples)

        // 3. Transitions & Wipes - 14 Effects
        val transitions = listOf(
            EffectDefinition("Wipe_Transition", "Linear Wipe", EffectCategory.TRANSITIONS, 11, 0f, 75f, 12f, 0f, 100f, "#FFFFFF", "Edge Color", "Start %, End %, Angle & Feather directional wipe"),
            EffectDefinition("Radial_Clock_Wipe", "Radial Clock Wipe", EffectCategory.TRANSITIONS, 11, 0f, 80f, 10f, 90f, 100f, "#FFFFFF", "Sweep Color", "Start Angle, Arc Progress & Feather"),
            EffectDefinition("Zoom_Warp_In", "Hyper Zoom In", EffectCategory.TRANSITIONS, 6, 0.82f, 0.50f, 0.65f, 0f, 100f, "#FFFFFF", "Tint Color", "Zoom Strength, Center Bias & Shutter"),
            EffectDefinition("Zoom_Warp_Out", "Hyper Zoom Out", EffectCategory.TRANSITIONS, 6, 0.76f, 0.50f, 0.55f, 0f, 100f, "#FFFFFF", "Tint Color", "Reverse barrel pull transition"),
            EffectDefinition("Spin_Transition", "Whip Spin 180", EffectCategory.TRANSITIONS, 10, 0.74f, 0.5f, 2.0f, 180f, 100f, "#FFFFFF", "Tint Color", "Spin Angle, Tile Wrap & Blur Strength"),
            EffectDefinition("Slide_Whip", "Whip Pan Slide", EffectCategory.TRANSITIONS, 6, 0.80f, 0.35f, 0.70f, 0f, 100f, "#FFFFFF", "Tint Color", "Slide Distance, Whip Angle & Directional Blur"),
            EffectDefinition("Glitch_Cut", "Digital Glitch Cut", EffectCategory.TRANSITIONS, 4, 0.84f, 0.65f, 1.8f, 0f, 100f, "#00E5FF", "Glitch Tint", "Slice Tear, Block Size & RGB Offset"),
            EffectDefinition("Luma_Fade", "Luma Dissolve", EffectCategory.TRANSITIONS, 9, 0.65f, 1.1f, 0.15f, 0f, 100f, "#FFFFFF", "Fade Tint", "Threshold, Softness & Invert Luma"),
            EffectDefinition("Light_Leak_Burn", "Film Burn Transition", EffectCategory.TRANSITIONS, 1, 0.88f, 0.80f, 0.5f, 25f, 100f, "#FF6D00", "Burn Color", "Amber-magenta halation flash transition"),
            EffectDefinition("Iris_Reveal", "Iris Shape Reveal", EffectCategory.TRANSITIONS, 5, 1.15f, 0.50f, 0.15f, 0f, 100f, "#FFFFFF", "Tint Color", "Aperture Radius, Feather & Aspect"),
            EffectDefinition("Venetian_Blinds", "Venetian Slats", EffectCategory.TRANSITIONS, 11, 15f, 85f, 20f, 45f, 100f, "#FFFFFF", "Tint Color", "Completion %, Slat Width, Angle & Feather"),
            EffectDefinition("Pixelate_Mosaic_Out", "Mosaic Dissolve", EffectCategory.TRANSITIONS, 4, 0.72f, 0.80f, 0.5f, 0f, 100f, "#FFFFFF", "Tint Color", "Block Size, Phase & Quantize"),
            EffectDefinition("Page_Curl_2D", "2D Fold & Skew", EffectCategory.TRANSITIONS, 3, 0.58f, 0.65f, 0.3f, -35f, 100f, "#FFFFFF", "Highlight Color", "Curl Progress, Radius & Fold Angle"),
            EffectDefinition("Cross_Blur_Dissolve", "Cross Optic Blur", EffectCategory.TRANSITIONS, 6, 0.68f, 0.55f, 0.5f, 0f, 100f, "#FFFFFF", "Tint Color", "Defocus Radius, Highlight Boost & Mix")
        )
        addAll(transitions)

        // 4. Color & LUTs (including Chroma Key & Luma Key with Eyedropper!) - 18 Effects
        val colorFx = listOf(
            EffectDefinition("Chroma_Key", "Chroma Key (Green Screen)", EffectCategory.COLOR_LUTS, 12, 24f, 12f, 18f, 30f, 100f, "#00FF00", "Key Color (Pick Screen)", "Threshold, Feather, Defringe & Eyedropper Key Color"),
            EffectDefinition("Luma_Key", "Luma Key", EffectCategory.COLOR_LUTS, 12, 22f, 15f, 10f, 0f, 100f, "#000000", "Target Luma Color", "Key out dark or bright luminance ranges"),
            EffectDefinition("Hue_Shift", "Hue Shift", EffectCategory.COLOR_LUTS, 9, 0.50f, 1.0f, 0f, 90f, 100f, "#FFFFFF", "Tint Color", "360° Hue Rotation, Saturation & Lightness"),
            EffectDefinition("Saturation_Vibrance", "Saturation / Vibrance", EffectCategory.COLOR_LUTS, 9, 0.45f, 0.95f, 0.1f, 0f, 100f, "#FFFFFF", "Skin Protect Tint", "Saturation %, Vibrance Boost & Luma Balance"),
            EffectDefinition("Color_Temperature", "Color Temperature", EffectCategory.COLOR_LUTS, 9, 0.35f, 1.0f, 0.1f, 18f, 100f, "#FFB74D", "Kelvin Tint", "Warmth (K) & Green/Magenta Tint"),
            EffectDefinition("Cyberpunk_LUT", "LUT: Cyberpunk Teal/Pink", EffectCategory.COLOR_LUTS, 9, 0.72f, 0.90f, 0f, 35f, 100f, "#00E5FF", "Split Tone", "Split-tone neon shadows & magenta highlights"),
            EffectDefinition("Kodak_2383_LUT", "LUT: Film Print 2383", EffectCategory.COLOR_LUTS, 9, 0.55f, 0.92f, 0.05f, 12f, 100f, "#FFF8E1", "Print Stock Tint", "Cinema print S-curve & rich skin tones"),
            EffectDefinition("Bleach_Bypass_LUT", "LUT: Bleach Bypass", EffectCategory.COLOR_LUTS, 9, 0.65f, 1.25f, -0.05f, 0f, 100f, "#CFD8DC", "Silver Retention", "High-contrast desaturated silver retention"),
            EffectDefinition("Vaporwave_LUT", "LUT: Vaporwave Sunset", EffectCategory.COLOR_LUTS, 9, 0.78f, 0.95f, 0.08f, -45f, 100f, "#FF80AB", "Sunset Tint", "Pastel violet-cyan nostalgic palette"),
            EffectDefinition("Matrix_Green_LUT", "LUT: Phosphor Terminal", EffectCategory.COLOR_LUTS, 9, 0.70f, 1.10f, 0f, 115f, 100f, "#69F0AE", "Phosphor Green", "Emerald CRT grade"),
            EffectDefinition("Invert", "Invert", EffectCategory.COLOR_LUTS, 9, 1.0f, 1.0f, 0f, 180f, 100f, "#FFFFFF", "Channel Mask", "Full RGB channel inversion"),
            EffectDefinition("Posterize_Levels", "Posterize", EffectCategory.COLOR_LUTS, 9, 0.60f, 1.35f, 0f, 0f, 100f, "#FFFFFF", "Tint Color", "Quantize tonal steps (2..32 levels)"),
            EffectDefinition("Threshold_Binary", "Threshold", EffectCategory.COLOR_LUTS, 8, 0.85f, 0.50f, 0.50f, 0f, 100f, "#FFFFFF", "Ink Color", "Threshold %, Feather & High-Contrast Matte"),
            EffectDefinition("Duotone_Shadow", "Replace Color / Duotone", EffectCategory.COLOR_LUTS, 9, 0.68f, 1.0f, 0.1f, -30f, 100f, "#00D2A8", "Target Color", "Map shadows & highlights to custom palette"),
            EffectDefinition("Highlights_Shadows", "Highlights & Shadows", EffectCategory.COLOR_LUTS, 9, 0.35f, 0.85f, 0.12f, 0f, 100f, "#FFFFFF", "Recovery Tint", "Highlights %, Shadows % & Midtone Contrast"),
            EffectDefinition("Channel_Remap_RGB", "Channel Remap (RGB)", EffectCategory.COLOR_LUTS, 2, 0.52f, 0.50f, 0f, 60f, 100f, "#FFFFFF", "Channel Gain", "Swap & weight R, G, B, A channels"),
            EffectDefinition("Color_Balance_Wheels", "Lift / Gamma / Gain", EffectCategory.COLOR_LUTS, 9, 0.40f, 1.0f, 0.05f, 15f, 100f, "#FFFFFF", "Wheel Color", "3-way cinema color wheels"),
            EffectDefinition("Vintage_Sepia_LUT", "LUT: 1970s Super 8", EffectCategory.COLOR_LUTS, 9, 0.54f, 0.92f, 0.08f, 24f, 100f, "#FFE082", "Warmth Color", "Warm faded shadows & golden highlights")
        )
        addAll(colorFx)

        // 5. Blur & Optics - 14 Effects
        val blurOptics = listOf(
            EffectDefinition("Gaussian_Blur", "Gaussian Blur", EffectCategory.BLUR_OPTICS, 6, 0.60f, 0.50f, 0.50f, 0f, 100f, "#FFFFFF", "Tint Color", "Strength (0..2.0) isotropic multi-tap blur"),
            EffectDefinition("Directional_Blur", "Directional Blur", EffectCategory.BLUR_OPTICS, 6, 0.72f, 0.25f, 0.60f, 35f, 100f, "#FFFFFF", "Tint Color", "Strength & Angle linear streak blur"),
            EffectDefinition("Radial_Zoom_Blur", "Zoom Blur", EffectCategory.BLUR_OPTICS, 6, 0.78f, 0.50f, 0.65f, 0f, 100f, "#FFFFFF", "Tint Color", "Strength & Center X/Y zoom burst"),
            EffectDefinition("Spin_Radial_Blur", "Spin Blur", EffectCategory.BLUR_OPTICS, 6, 0.68f, 0.50f, 0.55f, 90f, 100f, "#FFFFFF", "Tint Color", "Angle Sweep & Center rotational blur"),
            EffectDefinition("Tilt_Shift_Miniature", "Tilt-Shift Focus", EffectCategory.BLUR_OPTICS, 6, 0.64f, 0.50f, 0.40f, 0f, 100f, "#FFFFFF", "Tint Color", "Focus Plane, Falloff & Blur Strength"),
            EffectDefinition("Box_Fast_Blur", "Box Blur", EffectCategory.BLUR_OPTICS, 6, 0.52f, 0.50f, 0.30f, 0f, 100f, "#FFFFFF", "Tint Color", "Fast uniform kernel blur"),
            EffectDefinition("Smart_Surface_Blur", "Surface Blur", EffectCategory.BLUR_OPTICS, 6, 0.46f, 0.60f, 0.25f, 0f, 100f, "#FFFFFF", "Tint Color", "Edge-preserving bilateral smoothing"),
            EffectDefinition("Lens_Distortion", "Lens Distortion", EffectCategory.BLUR_OPTICS, 3, 0.50f, 0.65f, 0f, 0f, 100f, "#FFFFFF", "Fringe Tint", "Curvature,FOV & Decenter"),
            EffectDefinition("Fisheye_180", "180° Fisheye", EffectCategory.BLUR_OPTICS, 3, 0.85f, 0.80f, 0f, 0f, 100f, "#FFFFFF", "Tint Color", "Ultra-wide skate lens curvature"),
            EffectDefinition("Chromatic_Lens_Edge", "Optical Fringe", EffectCategory.BLUR_OPTICS, 2, 0.48f, 0.70f, 0f, 0f, 100f, "#FFFFFF", "Fringe Color", "Radial edge-weighted RGB dispersion"),
            EffectDefinition("Unsharp_Mask", "Sharpen", EffectCategory.BLUR_OPTICS, 8, 0.52f, 0.35f, 0.15f, 0f, 100f, "#FFFFFF", "Edge Highlight", "Strength & Radius micro-contrast crispness"),
            EffectDefinition("Halation_Bloom", "Red Halation", EffectCategory.BLUR_OPTICS, 1, 0.62f, 0.65f, 0f, 0f, 100f, "#FF5252", "Halation Red", "CineStill 800T red highlight halo"),
            EffectDefinition("Prism_Double_Vision", "Double Vision", EffectCategory.BLUR_OPTICS, 2, 0.66f, 0.55f, 0.30f, 15f, 100f, "#FFFFFF", "Ghost Tint", "Ghost offset & dreamy double exposure"),
            EffectDefinition("Frost_Glass", "Frosted Glass", EffectCategory.BLUR_OPTICS, 4, 0.56f, 0.75f, 0.10f, 0f, 100f, "#E0F7FA", "Frost Tint", "Micro-normal frosted window refraction")
        )
        addAll(blurOptics)

        // 6. Glitch & Retro - 14 Effects
        val glitchRetro = listOf(
            EffectDefinition("Glitch_Scanlines", "CRT Scanlines", EffectCategory.GLITCH_VHS, 4, 0.55f, 0.65f, 0.50f, 0f, 100f, "#FFFFFF", "Phosphor Tint", "Line Density, Opacity & Scroll Rate"),
            EffectDefinition("RGB_Split", "RGB Glitch Split", EffectCategory.GLITCH_VHS, 2, 0.72f, 0.60f, 1.2f, 0f, 100f, "#FFFFFF", "Split Tint", "Strength, Angle & Glitch Jitter"),
            EffectDefinition("VHS_Tracking", "VHS Tape Tracking", EffectCategory.GLITCH_VHS, 4, 0.68f, 0.45f, 1.4f, 0f, 100f, "#FFF3E0", "Tape Warmth", "Head-switching noise, chroma bleed & tear"),
            EffectDefinition("Datamosh_Blocks", "Macroblock Datamosh", EffectCategory.GLITCH_VHS, 4, 0.78f, 0.35f, 1.6f, 0f, 100f, "#FFFFFF", "Tint Color", "I-frame drop & 16x16 block displacement"),
            EffectDefinition("Pixel_Sort", "Pixel Sort", EffectCategory.GLITCH_VHS, 4, 0.74f, 0.60f, 0.8f, 90f, 100f, "#FFFFFF", "Tint Color", "Threshold, Streak Length & Sort Angle"),
            EffectDefinition("Bitcrush_8Bit", "8-Bit Console", EffectCategory.GLITCH_VHS, 4, 0.64f, 0.30f, 0f, 0f, 100f, "#FFFFFF", "Palette Tint", "Low-res pixelation & palette crunch"),
            EffectDefinition("Analog_Interlace", "Interlace Combing", EffectCategory.GLITCH_VHS, 4, 0.48f, 0.80f, 0.4f, 0f, 100f, "#FFFFFF", "Tint Color", "Odd/even field comb jitter"),
            EffectDefinition("CRT_Barrel", "CRT Curved Tube", EffectCategory.GLITCH_VHS, 3, 0.56f, 0.60f, 0f, 0f, 100f, "#E8F5E9", "Tube Tint", "Curved glass corners & shadow mask"),
            EffectDefinition("Signal_Dropout", "RF Signal Dropout", EffectCategory.GLITCH_VHS, 4, 0.66f, 0.55f, 2.2f, 0f, 100f, "#FFFFFF", "Static Tint", "Horizontal snow bursts & sync loss"),
            EffectDefinition("Timecode_Jitter", "Frame Skip Stutter", EffectCategory.GLITCH_VHS, 10, 0.52f, 0.40f, 3.0f, 0f, 100f, "#FFFFFF", "Tint Color", "Posterized stutter & horizontal twitch"),
            EffectDefinition("ASCII_Matrix", "ASCII Dither", EffectCategory.GLITCH_VHS, 8, 0.70f, 0.65f, 0.35f, 0f, 100f, "#00E676", "Glyph Color", "High-contrast quantized dot matrix"),
            EffectDefinition("Bayer_Dither", "1-Bit Bayer Dither", EffectCategory.GLITCH_VHS, 8, 0.65f, 0.55f, 0.45f, 0f, 100f, "#FFFFFF", "Ink Color", "Ordered 4x4 retro Macintosh dithering"),
            EffectDefinition("Chroma_Subsample", "4:2:0 Chroma Bleed", EffectCategory.GLITCH_VHS, 2, 0.58f, 0.45f, 0.25f, 0f, 100f, "#FFFFFF", "Tint Color", "Smeared camcorder color bandwidth"),
            EffectDefinition("Corrupt_JPEG", "DCT Artifact Crunch", EffectCategory.GLITCH_VHS, 4, 0.62f, 0.50f, 1.0f, 0f, 100f, "#FFFFFF", "Tint Color", "8x8 quantization ringing & block tear")
        )
        addAll(glitchRetro)

        // 7. Particles & Light - 12 Effects
        val particlesLight = listOf(
            EffectDefinition("Neon_Sparks", "Rising Ember Sparks", EffectCategory.PARTICLES_LIGHT, 7, 0.78f, 0.55f, 1.1f, -90f, 100f, "#FF9100", "Ember Color", "Buoyant glowing firefly embers"),
            EffectDefinition("Cyber_Snow", "Digital Snowfall", EffectCategory.PARTICLES_LIGHT, 7, 0.65f, 0.45f, 0.75f, 80f, 100f, "#E1F5FE", "Snowflake Color", "Layered parallax bokeh particles"),
            EffectDefinition("Starfield_Hyperdrive", "Warp Starfield", EffectCategory.PARTICLES_LIGHT, 7, 0.85f, 0.65f, 1.6f, 0f, 100f, "#FFFFFF", "Star Color", "Z-depth streaking starlight"),
            EffectDefinition("Dust_Motes", "Cinema Dust Motes", EffectCategory.PARTICLES_LIGHT, 7, 0.48f, 0.38f, 0.35f, 20f, 100f, "#FFF8E1", "Dust Tint", "Subtle floating atmospheric dust"),
            EffectDefinition("Laser_Grid_Floor", "Synthwave Grid", EffectCategory.PARTICLES_LIGHT, 7, 0.72f, 0.60f, 0.80f, 0f, 100f, "#FF007F", "Grid Neon Color", "Glowing horizon grid overlay"),
            EffectDefinition("Bokeh_Orbs", "Floating Bokeh Orbs", EffectCategory.PARTICLES_LIGHT, 7, 0.68f, 0.25f, 0.45f, -45f, 100f, "#00D2A8", "Bokeh Color", "Soft out-of-focus light circles"),
            EffectDefinition("Prism_Caustics", "Water Caustics", EffectCategory.PARTICLES_LIGHT, 1, 0.64f, 0.55f, 0.90f, 30f, 100f, "#00E5FF", "Caustic Color", "Refracted underwater light ripples"),
            EffectDefinition("Lightning_Arc", "Electric Plasma Arc", EffectCategory.PARTICLES_LIGHT, 8, 0.82f, 0.45f, 2.4f, 0f, 100f, "#80D8FF", "Arc Color", "High-voltage branching edge arcs"),
            EffectDefinition("God_Rays_Top", "Heavenly Top Rays", EffectCategory.PARTICLES_LIGHT, 1, 0.76f, 0.75f, 0.20f, -90f, 100f, "#FFF9C4", "Beam Color", "Top-down volumetric shafts"),
            EffectDefinition("Rim_Glow_Aura", "Inner/Outer Aura", EffectCategory.PARTICLES_LIGHT, 1, 0.74f, 0.50f, 0.40f, 0f, 100f, "#9B6BFF", "Aura Color", "Pulsing alpha boundary aura"),
            EffectDefinition("Confetti_Burst", "Prism Confetti", EffectCategory.PARTICLES_LIGHT, 7, 0.70f, 0.50f, 1.2f, 90f, 100f, "#FFC400", "Confetti Tint", "Fluttering celebratory shards"),
            EffectDefinition("Rain_Streaks", "Neon Rain Streaks", EffectCategory.PARTICLES_LIGHT, 7, 0.75f, 0.70f, 1.8f, 75f, 100f, "#84FFFF", "Rain Tint", "Diagonal high-velocity rain")
        )
        addAll(particlesLight)

        // 8. Stylize & Edges - 12 Effects
        val stylize = listOf(
            EffectDefinition("Neon_Contours", "Find Edges (Neon)", EffectCategory.STYLIZE_EDGES, 8, 0.80f, 0.55f, 0.25f, 0f, 100f, "#00D2A8", "Edge Color", "Glowing Sobel outline over dark matte"),
            EffectDefinition("Comic_Halftone", "Dots / Halftone", EffectCategory.STYLIZE_EDGES, 8, 0.68f, 0.60f, 0.30f, 45f, 100f, "#FFFFFF", "Dot Color", "Dot Size, Angle & Contrast print screen"),
            EffectDefinition("Emboss_Relief", "Emboss Metallic", EffectCategory.STYLIZE_EDGES, 8, 0.60f, 0.50f, 0.40f, 135f, 100f, "#CFD8DC", "Specular Tint", "Directional bump relief"),
            EffectDefinition("Mirror_Horizontal", "Mirror Horizontal", EffectCategory.STYLIZE_EDGES, 5, 1.0f, 0.50f, 0f, 0f, 100f, "#FFFFFF", "Tint Color", "Seamless horizontal center reflection"),
            EffectDefinition("Mirror_Quad", "Quad 4-Way Mirror", EffectCategory.STYLIZE_EDGES, 5, 2.0f, 0.50f, 0f, 0f, 100f, "#FFFFFF", "Tint Color", "4-quadrant geometric symmetry"),
            EffectDefinition("Hex_Kaleidoscope", "6-Fold Kaleidoscope", EffectCategory.STYLIZE_EDGES, 5, 1.5f, 0.50f, 0.2f, 60f, 100f, "#FFFFFF", "Tint Color", "Hexagonal mandala reflection"),
            EffectDefinition("Oil_Paint_Poster", "Painterly Stylize", EffectCategory.STYLIZE_EDGES, 9, 0.58f, 1.15f, 0.1f, 0f, 100f, "#FFFFFF", "Tint Color", "Kuwahara-inspired brush quantized look"),
            EffectDefinition("Thermal_Predator", "Thermal Infrared", EffectCategory.STYLIZE_EDGES, 9, 0.88f, 1.10f, 0f, 140f, 100f, "#FF3D00", "Heat Palette", "False-color heat vision map"),
            EffectDefinition("XRay_Negative", "X-Ray Scan", EffectCategory.STYLIZE_EDGES, 8, 0.76f, 0.45f, 0.5f, 0f, 100f, "#80D8FF", "X-Ray Cyan", "Inverted edge-lit radiograph"),
            EffectDefinition("Vignette_Cinema", "2.39:1 Letterbox & Vignette", EffectCategory.STYLIZE_EDGES, 9, -0.55f, 1.1f, 0f, 0f, 100f, "#FFFFFF", "Tint Color", "Cinema scope framing & edge dim"),
            EffectDefinition("Drop_Shadow_Glow", "Long Shadow / Glow", EffectCategory.STYLIZE_EDGES, 1, 0.65f, 0.60f, 0f, 45f, 100f, "#00D2A8", "Shadow Color", "Directional soft shadow & neon rim"),
            EffectDefinition("Grid_Overlay", "Grid", EffectCategory.STYLIZE_EDGES, 7, 0.45f, 0.50f, 0f, 0f, 100f, "#FFFFFF", "Grid Color", "Spacing, Line Width & Offset rule-of-thirds")
        )
        addAll(stylize)

        // 9. 3D Camera & Beat Shake - 10 Effects
        val cameraShake = listOf(
            EffectDefinition("Beat_Sync_Pulse", "Pulse Size (Beat)", EffectCategory.CAMERA_SHAKE, 10, 0.72f, 0.50f, 2.2f, 0f, 100f, "#FFFFFF", "Tint Color", "Frequency, Shrink/Grow Size & Phase"),
            EffectDefinition("Impact_Earthquake", "Heavy Impact Quake", EffectCategory.CAMERA_SHAKE, 10, 0.88f, 0.80f, 4.0f, 15f, 100f, "#FFFFFF", "Tint Color", "Aggressive bass-drop X/Y/Z impact"),
            EffectDefinition("Handheld_Docu", "Subtle Handheld Drift", EffectCategory.CAMERA_SHAKE, 10, 0.28f, 0.45f, 0.8f, 0f, 100f, "#FFFFFF", "Tint Color", "Organic operator breathing motion"),
            EffectDefinition("Pendulum_Roll", "Dutch Angle Roll", EffectCategory.CAMERA_SHAKE, 10, 0.54f, 0.30f, 1.2f, 25f, 100f, "#FFFFFF", "Tint Color", "Rhythmic Z-axis camera tilt"),
            EffectDefinition("Velocity_Whip_Shake", "Velocity Edit Shake", EffectCategory.CAMERA_SHAKE, 10, 0.80f, 0.65f, 3.2f, -20f, 100f, "#FFFFFF", "Tint Color", "Snappy TikTok/Reels beat twitch"),
            EffectDefinition("Micro_Jitter", "Projector Gate Weave", EffectCategory.CAMERA_SHAKE, 10, 0.22f, 0.35f, 5.0f, 0f, 100f, "#FFFFFF", "Tint Color", "Sub-pixel 24fps film gate micro-jitter"),
            EffectDefinition("Vertigo_Dolly", "Dolly Zoom Vertigo", EffectCategory.CAMERA_SHAKE, 3, 0.62f, 0.70f, 0.8f, 0f, 100f, "#FFFFFF", "Tint Color", "Counter-zoom perspective stretch"),
            EffectDefinition("Spring_Bounce", "Elastic Bounce", EffectCategory.CAMERA_SHAKE, 10, 0.66f, 0.55f, 2.4f, 0f, 100f, "#FFFFFF", "Tint Color", "Damped harmonic overshoot"),
            EffectDefinition("Horiz_Twitch", "Horizontal Bass Twitch", EffectCategory.CAMERA_SHAKE, 10, 0.70f, 0.10f, 3.5f, 0f, 100f, "#FFFFFF", "Tint Color", "Sharp X-axis kick drum snap"),
            EffectDefinition("Blink_Strobe", "Blink", EffectCategory.CAMERA_SHAKE, 9, 0.75f, 1.0f, 6.0f, 0f, 100f, "#FFFFFF", "Strobe Color", "Frequency (Hz) & Duty Cycle opacity strobe")
        )
        addAll(cameraShake)
    }

    fun findById(id: String): EffectDefinition {
        return allEffects.firstOrNull { it.id.equals(id, ignoreCase = true) } ?: allEffects.first()
    }

    fun filter(category: EffectCategory?, query: String): List<EffectDefinition> {
        val q = query.trim().lowercase()
        return allEffects.filter { def ->
            val catMatch = category == null || def.category == category
            val qMatch = q.isEmpty() ||
                def.name.lowercase().contains(q) ||
                def.id.lowercase().contains(q) ||
                def.summary.lowercase().contains(q)
            catMatch && qMatch
        }
    }

    /**
     * Exact Per-Effect Alight Motion Property Tables!
     */
    internal fun customSpecsForEffect(id: String): List<EffectParamSpec>? = when (id) {
        "Text_Transform" -> listOf(
            EffectParamSpec(EffectParamSlot.PRIMARY, "Start", 0f, 100f, "%", 0.5f),
            EffectParamSpec(EffectParamSlot.SECONDARY, "End", 0f, 100f, "%", 0.5f),
            EffectParamSpec(EffectParamSlot.FREQUENCY, "Phase", -100f, 100f, "%", 0.5f),
            EffectParamSpec(EffectParamSlot.ANGLE, "Offset / Angle", -180f, 180f, "°", 1f),
            EffectParamSpec(EffectParamSlot.MIX, "Scale & Alpha", 0f, 100f, "%", 1f)
        )
        "Text_Spacing" -> listOf(
            EffectParamSpec(EffectParamSlot.PRIMARY, "Start Range", 0f, 100f, "%", 0.5f),
            EffectParamSpec(EffectParamSlot.SECONDARY, "End Range", 0f, 100f, "%", 0.5f),
            EffectParamSpec(EffectParamSlot.FREQUENCY, "Letter Spacing", -20f, 80f, "px", 0.5f),
            EffectParamSpec(EffectParamSlot.ANGLE, "Line Skew", -45f, 45f, "°", 0.5f),
            EffectParamSpec(EffectParamSlot.MIX, "Mix", 0f, 100f, "%", 1f)
        )
        "Oscillate" -> listOf(
            EffectParamSpec(EffectParamSlot.PRIMARY, "Magnitude", 0f, 250f, "px", 1f),
            EffectParamSpec(EffectParamSlot.SECONDARY, "Wave Shape", 0f, 1.0f, "", 0.01f),
            EffectParamSpec(EffectParamSlot.FREQUENCY, "Frequency", 0.10f, 16.0f, "Hz", 0.05f),
            EffectParamSpec(EffectParamSlot.ANGLE, "Angle", -180f, 180f, "°", 1f),
            EffectParamSpec(EffectParamSlot.MIX, "Mix", 0f, 100f, "%", 1f)
        )
        "Swing" -> listOf(
            EffectParamSpec(EffectParamSlot.PRIMARY, "Angle 1", -90f, 90f, "°", 0.5f),
            EffectParamSpec(EffectParamSlot.SECONDARY, "Angle 2", -90f, 90f, "°", 0.5f),
            EffectParamSpec(EffectParamSlot.FREQUENCY, "Frequency", 0.10f, 10.0f, "Hz", 0.05f),
            EffectParamSpec(EffectParamSlot.ANGLE, "Phase", 0f, 360f, "°", 1f),
            EffectParamSpec(EffectParamSlot.MIX, "Mix", 0f, 100f, "%", 1f)
        )
        "Tiles" -> listOf(
            EffectParamSpec(EffectParamSlot.PRIMARY, "Crop", 0.25f, 4.0f, "x", 0.01f),
            EffectParamSpec(EffectParamSlot.SECONDARY, "Offset", 0f, 2.0f, "", 0.01f),
            EffectParamSpec(EffectParamSlot.FREQUENCY, "Phase", -2.0f, 2.0f, "", 0.01f),
            EffectParamSpec(EffectParamSlot.ANGLE, "Angle", -180f, 180f, "°", 1f),
            EffectParamSpec(EffectParamSlot.MIX, "Mirror / Mix", 0f, 100f, "%", 1f)
        )
        "Wave_Warp" -> listOf(
            EffectParamSpec(EffectParamSlot.PRIMARY, "Magnitude", 0f, 2.0f, "", 0.01f),
            EffectParamSpec(EffectParamSlot.SECONDARY, "Spacing", 2f, 60f, "", 0.5f),
            EffectParamSpec(EffectParamSlot.FREQUENCY, "Phase", -5.0f, 5.0f, "", 0.02f),
            EffectParamSpec(EffectParamSlot.ANGLE, "Direction", -180f, 180f, "°", 1f),
            EffectParamSpec(EffectParamSlot.MIX, "Damping / Mix", 0f, 100f, "%", 1f)
        )
        "Pinch_Bulge" -> listOf(
            EffectParamSpec(EffectParamSlot.PRIMARY, "Strength", -1.5f, 1.5f, "", 0.01f),
            EffectParamSpec(EffectParamSlot.SECONDARY, "Radius", 0.05f, 2.0f, "", 0.01f),
            EffectParamSpec(EffectParamSlot.FREQUENCY, "Center X", 0f, 1.0f, "", 0.01f),
            EffectParamSpec(EffectParamSlot.ANGLE, "Center Y", -100f, 100f, "%", 1f),
            EffectParamSpec(EffectParamSlot.MIX, "Mix", 0f, 100f, "%", 1f)
        )
        "Squeeze" -> listOf(
            EffectParamSpec(EffectParamSlot.PRIMARY, "Strength", -1.5f, 1.5f, "", 0.01f),
            EffectParamSpec(EffectParamSlot.SECONDARY, "Radius", 0.1f, 2.0f, "", 0.01f),
            EffectParamSpec(EffectParamSlot.FREQUENCY, "Aspect Bias", 0f, 1.0f, "", 0.01f),
            EffectParamSpec(EffectParamSlot.ANGLE, "Angle", -180f, 180f, "°", 1f),
            EffectParamSpec(EffectParamSlot.MIX, "Mix", 0f, 100f, "%", 1f)
        )
        "Exposure_Gamma" -> listOf(
            EffectParamSpec(EffectParamSlot.PRIMARY, "Exposure", -2.0f, 2.0f, "EV", 0.02f),
            EffectParamSpec(EffectParamSlot.SECONDARY, "Gamma", 0.10f, 4.0f, "", 0.02f),
            EffectParamSpec(EffectParamSlot.FREQUENCY, "Offset", -0.80f, 0.80f, "", 0.01f),
            EffectParamSpec(EffectParamSlot.ANGLE, "Warmth Shift", -90f, 90f, "°", 1f),
            EffectParamSpec(EffectParamSlot.MIX, "Mix", 0f, 100f, "%", 1f)
        )
        "Turbulent_Displace" -> listOf(
            EffectParamSpec(EffectParamSlot.PRIMARY, "Strength", 0f, 2.0f, "", 0.01f),
            EffectParamSpec(EffectParamSlot.SECONDARY, "Scale / Octaves", 0.1f, 3.0f, "x", 0.02f),
            EffectParamSpec(EffectParamSlot.FREQUENCY, "Evolution", 0f, 6.0f, "", 0.02f),
            EffectParamSpec(EffectParamSlot.ANGLE, "Parallax Angle", -180f, 180f, "°", 1f),
            EffectParamSpec(EffectParamSlot.MIX, "Mix", 0f, 100f, "%", 1f)
        )
        "Chroma_Key", "BCC_ChromaKey", "Luma_Key" -> listOf(
            EffectParamSpec(EffectParamSlot.PRIMARY, "Threshold", 0f, 100f, "%", 0.5f),
            EffectParamSpec(EffectParamSlot.SECONDARY, "Feather", 0f, 100f, "%", 0.5f),
            EffectParamSpec(EffectParamSlot.FREQUENCY, "Defringe", 0f, 100f, "%", 0.5f),
            EffectParamSpec(EffectParamSlot.ANGLE, "Spill Suppress", 0f, 100f, "%", 1f),
            EffectParamSpec(EffectParamSlot.MIX, "Key Mix", 0f, 100f, "%", 1f)
        )
        "Wipe_Transition", "Radial_Clock_Wipe", "Venetian_Blinds" -> listOf(
            EffectParamSpec(EffectParamSlot.PRIMARY, "Start", 0f, 100f, "%", 0.5f),
            EffectParamSpec(EffectParamSlot.SECONDARY, "End", 0f, 100f, "%", 0.5f),
            EffectParamSpec(EffectParamSlot.FREQUENCY, "Feather", 0f, 50f, "%", 0.5f),
            EffectParamSpec(EffectParamSlot.ANGLE, "Angle", -180f, 180f, "°", 1f),
            EffectParamSpec(EffectParamSlot.MIX, "Mix", 0f, 100f, "%", 1f)
        )
        "S_Glow", "BCC_FilmGlow", "Rim_Glow_Aura" -> listOf(
            EffectParamSpec(EffectParamSlot.PRIMARY, "Alpha / Brightness", 0f, 2.0f, "x", 0.01f),
            EffectParamSpec(EffectParamSlot.SECONDARY, "Radius", 0f, 1.5f, "", 0.01f),
            EffectParamSpec(EffectParamSlot.FREQUENCY, "Hardness / Pulse", 0f, 2.0f, "", 0.01f),
            EffectParamSpec(EffectParamSlot.ANGLE, "Spread Angle", -180f, 180f, "°", 1f),
            EffectParamSpec(EffectParamSlot.MIX, "Blend Mix", 0f, 100f, "%", 1f)
        )
        "S_Rays", "God_Rays_Top" -> listOf(
            EffectParamSpec(EffectParamSlot.PRIMARY, "Ray Brightness", 0f, 2.0f, "x", 0.01f),
            EffectParamSpec(EffectParamSlot.SECONDARY, "Ray Length", 0.05f, 1.5f, "", 0.01f),
            EffectParamSpec(EffectParamSlot.FREQUENCY, "Threshold", 0f, 1.0f, "", 0.01f),
            EffectParamSpec(EffectParamSlot.ANGLE, "Source Angle", -180f, 180f, "°", 1f),
            EffectParamSpec(EffectParamSlot.MIX, "Mix", 0f, 100f, "%", 1f)
        )
        "Gaussian_Blur" -> listOf(
            EffectParamSpec(EffectParamSlot.PRIMARY, "Strength", 0f, 2.0f, "", 0.01f),
            EffectParamSpec(EffectParamSlot.SECONDARY, "Spread", 0.1f, 1.5f, "", 0.01f),
            EffectParamSpec(EffectParamSlot.FREQUENCY, "Quality Boost", 0f, 1.0f, "", 0.01f),
            EffectParamSpec(EffectParamSlot.ANGLE, "Axis Bias", -90f, 90f, "°", 1f),
            EffectParamSpec(EffectParamSlot.MIX, "Mix", 0f, 100f, "%", 1f)
        )
        "Directional_Blur", "Motion_Blur_Tile", "RSMB_MotionBlur" -> listOf(
            EffectParamSpec(EffectParamSlot.PRIMARY, "Strength / Tune", 0f, 2.0f, "x", 0.01f),
            EffectParamSpec(EffectParamSlot.SECONDARY, "Shutter Spread", 0f, 1.0f, "", 0.01f),
            EffectParamSpec(EffectParamSlot.FREQUENCY, "Velocity Scale", 0f, 2.0f, "x", 0.01f),
            EffectParamSpec(EffectParamSlot.ANGLE, "Angle", -180f, 180f, "°", 1f),
            EffectParamSpec(EffectParamSlot.MIX, "Mix", 0f, 100f, "%", 1f)
        )
        "Radial_Zoom_Blur" -> listOf(
            EffectParamSpec(EffectParamSlot.PRIMARY, "Strength", 0f, 2.0f, "", 0.01f),
            EffectParamSpec(EffectParamSlot.SECONDARY, "Center X", 0f, 1.0f, "", 0.01f),
            EffectParamSpec(EffectParamSlot.FREQUENCY, "Sample Length", 0.1f, 2.0f, "", 0.01f),
            EffectParamSpec(EffectParamSlot.ANGLE, "Swirl Bias", -45f, 45f, "°", 1f),
            EffectParamSpec(EffectParamSlot.MIX, "Mix", 0f, 100f, "%", 1f)
        )
        "Chromatic_Aberration", "RGB_Split", "S_WarpChroma", "S_DistortRGB" -> listOf(
            EffectParamSpec(EffectParamSlot.PRIMARY, "Strength", 0f, 1.5f, "", 0.01f),
            EffectParamSpec(EffectParamSlot.SECONDARY, "Center Falloff", 0f, 1.0f, "", 0.01f),
            EffectParamSpec(EffectParamSlot.FREQUENCY, "Jitter Hz", 0f, 5.0f, "Hz", 0.05f),
            EffectParamSpec(EffectParamSlot.ANGLE, "Angle", -180f, 180f, "°", 1f),
            EffectParamSpec(EffectParamSlot.MIX, "Mix", 0f, 100f, "%", 1f)
        )
        "Blink_Strobe" -> listOf(
            EffectParamSpec(EffectParamSlot.PRIMARY, "Depth", 0f, 1.0f, "", 0.01f),
            EffectParamSpec(EffectParamSlot.SECONDARY, "Duty Cycle", 0.05f, 0.95f, "", 0.01f),
            EffectParamSpec(EffectParamSlot.FREQUENCY, "Frequency", 0.5f, 16.0f, "Hz", 0.1f),
            EffectParamSpec(EffectParamSlot.ANGLE, "Phase", 0f, 360f, "°", 1f),
            EffectParamSpec(EffectParamSlot.MIX, "Mix", 0f, 100f, "%", 1f)
        )
        "Beat_Sync_Pulse" -> listOf(
            EffectParamSpec(EffectParamSlot.PRIMARY, "Grow / Shrink", 0f, 1.5f, "x", 0.01f),
            EffectParamSpec(EffectParamSlot.SECONDARY, "Axis Ratio", 0f, 1.0f, "", 0.01f),
            EffectParamSpec(EffectParamSlot.FREQUENCY, "Frequency", 0.25f, 12.0f, "Hz", 0.05f),
            EffectParamSpec(EffectParamSlot.ANGLE, "Tilt Kick", -45f, 45f, "°", 1f),
            EffectParamSpec(EffectParamSlot.MIX, "Mix", 0f, 100f, "%", 1f)
        )
        else -> null
    }

    /**
     * Maps human-friendly Alight Motion parameter units (px, EV, %, Hz) into normalized
     * GPU shader uniforms while preserving direct `[0..1.5]` behavior for standard effects.
     */
    fun normalizeForShader(effectId: String, eval: EvaluatedEffectParams): EvaluatedEffectParams {
        return when (effectId) {
            "Oscillate" -> eval.copy(
                primary = (eval.primary / 85f).coerceIn(0f, 2.5f),
                frequency = (eval.frequency / 4f).coerceIn(0.02f, 4f)
            )
            "Swing" -> eval.copy(
                primary = ((abs(eval.primary) + abs(eval.secondary)) / 70f).coerceIn(0f, 2.0f),
                secondary = 0.5f,
                frequency = (eval.frequency / 3f).coerceIn(0.05f, 4f)
            )
            "Wave_Warp", "Ripple_Pond" -> eval.copy(
                secondary = (eval.secondary / 35f).coerceIn(0.08f, 1.8f)
            )
            "Exposure_Gamma" -> eval.copy(
                // Keep primary as EV (-2..+2), secondary as Gamma (0.1..4), frequency as Offset (-0.8..0.8)
                primary = eval.primary.coerceIn(-2.0f, 2.0f),
                secondary = eval.secondary.coerceIn(0.10f, 4.0f),
                frequency = eval.frequency.coerceIn(-0.9f, 0.9f)
            )
            "Chroma_Key", "BCC_ChromaKey", "Luma_Key" -> eval.copy(
                primary = (eval.primary / 100f).coerceIn(0.01f, 0.95f),
                secondary = (eval.secondary / 100f).coerceIn(0.005f, 0.65f),
                frequency = (eval.frequency / 100f).coerceIn(0f, 1f)
            )
            "Text_Transform", "Wipe_Transition", "Radial_Clock_Wipe", "Venetian_Blinds", "Text_Spacing" -> eval.copy(
                primary = (eval.primary / 100f).coerceIn(0f, 1f),
                secondary = (eval.secondary / 100f).coerceIn(0f, 1f),
                frequency = (eval.frequency / 100f).coerceIn(-1f, 1f)
            )
            else -> eval
        }
    }
}
