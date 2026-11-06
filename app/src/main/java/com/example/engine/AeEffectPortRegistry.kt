package com.example.engine

import com.example.data.model.AnimatableProperty
import com.example.data.model.EffectNode
import java.util.Locale
import java.util.UUID

enum class ImportEffectStatus(val badgeLabel: String) {
    PORTED("Ported"),
    REPLACED("Replaced"),
    UNSUPPORTED("Unsupported")
}

data class ImportedEffectRecord(
    val id: String = UUID.randomUUID().toString(),
    val layerId: String,
    val layerName: String,
    val originalDisplayName: String,
    val originalMatchName: String,
    val status: ImportEffectStatus,
    val mappedEffectId: String? = null,
    val mappedEffectName: String? = null,
    val effectNodeId: String? = null
)

data class RawDesktopEffectSpec(
    val matchName: String,
    val displayName: String,
    val floatParams: Map<String, Float> = emptyMap(),
    val colorParams: Map<String, String> = emptyMap()
)

data class PortedEffectConversionResult(
    val status: ImportEffectStatus,
    val effectNode: EffectNode?,
    val mappedEffectId: String?,
    val mappedEffectName: String?
)

/**
 * Shared After Effects / BCC / Sapphire / FFX Effect Porting Registry (`AeEffectPortRegistry`):
 *
 * - Maps After Effects `matchName`s and display names (e.g., `BCC Page Turn` -> `bcc_page_turn`,
 *   `S_Shake` -> `s_shake_port`, `ADBE Motion Tile` -> `motion_tile_port`, `Deep Glow` -> `deep_glow_port`,
 *   `ADBE Turbulent Displace` -> `turbulent_displace_port`, `VideoCopilot Twitch` -> `ae_twitch_port`,
 *   `ADBE Lumetri` -> `lumetri_color_port`, plus built-in shaders) to ported `.kfpack` or native engine effects
 *   with parameter conversion.
 * - Used identically by both `.aep` and `.ffx` importers.
 * - When an effect has no port, returns `status = UNSUPPORTED` and `effectNode = null` so the layer is kept,
 *   the unsupported effect is skipped, and the user can pick a replacement in Import Preview!
 */
object AeEffectPortRegistry {

    private data class PortRule(
        val matchTokens: List<String>,
        val targetEffectId: String,
        val isPluginPort: Boolean,
        val converter: (RawDesktopEffectSpec) -> EffectNode
    )

    private val PORT_RULES: List<PortRule> = listOf(
        // 1. BCC Page Turn -> bcc_page_turn (.kfpack port)
        PortRule(
            matchTokens = listOf("bcc page turn", "bcc-pageturn", "bcc_page_turn", "cc page turn", "adbe page turn"),
            targetEffectId = "bcc_page_turn",
            isPluginPort = true
        ) { raw ->
            val progress = findParam(raw.floatParams, "fold", "progress", "completion", defaultVal = 32f)
                .let { if (it > 1.5f) (it / 100f).coerceIn(0f, 1f) else it.coerceIn(0f, 1f) }
            val angle = findParam(raw.floatParams, "angle", "direction", defaultVal = -35f).coerceIn(-180f, 180f)
            val radius = findParam(raw.floatParams, "radius", "curl", defaultVal = 0.14f)
                .let { if (it > 1.5f) (it / 300f).coerceIn(0.03f, 0.45f) else it.coerceIn(0.03f, 0.45f) }
            buildPluginEffectNode(
                pluginId = "bcc_page_turn",
                fallbackName = "BCC Page Turn",
                floats = mapOf(
                    "foldProgress" to progress,
                    "foldAngle" to angle,
                    "foldRadius" to radius,
                    "backOpacity" to 0.55f
                ),
                colors = raw.colorParams.entries.firstOrNull()?.let { mapOf("backColor" to it.value) }.orEmpty()
            )
        },

        // 2. Sapphire S_Shake -> s_shake_port (.kfpack port)
        PortRule(
            matchTokens = listOf("s_shake", "sapphire shake", "twitch shake", "camera shake"),
            targetEffectId = "s_shake_port",
            isPluginPort = true
        ) { raw ->
            val amp = findParam(raw.floatParams, "amp", "amplitude", "amount", defaultVal = 65f).coerceIn(0f, 300f)
            val freq = findParam(raw.floatParams, "freq", "frequency", "speed", defaultVal = 8f).coerceIn(0.2f, 30f)
            val tilt = findParam(raw.floatParams, "tilt", "rot", defaultVal = 3.5f).coerceIn(0f, 45f)
            val wrap = findParam(raw.floatParams, "wrap", "reflect", "mirror", defaultVal = 1f)
            buildPluginEffectNode(
                pluginId = "s_shake_port",
                fallbackName = "S_Shake (Ported)",
                floats = mapOf(
                    "amp" to amp,
                    "frequency" to freq,
                    "tiltAmp" to tilt,
                    "zDist" to 1.0f,
                    "wrapReflect" to if (wrap >= 0.5f) 1f else 0f
                )
            )
        },

        // 3. Motion Tile -> motion_tile_port (.kfpack port)
        PortRule(
            matchTokens = listOf("adbe tile", "motion tile", "s_warp", "repeater tile", "cc repetile"),
            targetEffectId = "motion_tile_port",
            isPluginPort = true
        ) { raw ->
            val outW = findParam(raw.floatParams, "output width", "tile width", "width", "scale", defaultVal = 85f)
                .coerceIn(15f, 250f)
            val outH = findParam(raw.floatParams, "output height", "tile height", "height", defaultVal = outW)
                .coerceIn(15f, 250f)
            val mirror = findParam(raw.floatParams, "mirror", "edges", defaultVal = 1f)
            val phase = findParam(raw.floatParams, "phase", defaultVal = 0f).coerceIn(0f, 360f)
            buildPluginEffectNode(
                pluginId = "motion_tile_port",
                fallbackName = "Motion Tile (AE)",
                floats = mapOf(
                    "tileCenterX" to 0f,
                    "tileCenterY" to 0f,
                    "tileWidth" to outW,
                    "tileHeight" to outH,
                    "mirrorEdges" to if (mirror >= 0.5f) 1f else 0f,
                    "phase" to phase
                )
            )
        },

        // 4. Deep Glow / S_Glow / AE Glow -> deep_glow_port (.kfpack port)
        PortRule(
            matchTokens = listOf("deep glow", "s_glow", "adbe glo2", "optical glow", "real glow", "vr glow", "uni.ecto"),
            targetEffectId = "deep_glow_port",
            isPluginPort = true
        ) { raw ->
            val radius = findParam(raw.floatParams, "radius", "glow radius", "spread", defaultVal = 55f).coerceIn(0f, 200f)
            val exposure = findParam(raw.floatParams, "exposure", "brightness", "intensity", defaultVal = 1.45f).coerceIn(0f, 4f)
            val threshold = findParam(raw.floatParams, "threshold", defaultVal = 0.30f)
                .let { if (it > 1.5f) (it / 100f).coerceIn(0f, 1f) else it.coerceIn(0f, 1f) }
            val tint = raw.colorParams.values.firstOrNull() ?: "#7CA0FF"
            buildPluginEffectNode(
                pluginId = "deep_glow_port",
                fallbackName = "Deep Glow (AE)",
                floats = mapOf(
                    "radius" to radius,
                    "exposure" to exposure,
                    "threshold" to threshold,
                    "chromaticSpread" to 0.25f
                ),
                colors = mapOf("glowTint" to tint)
            )
        },

        // 5. Turbulent Displace -> turbulent_displace_port (.kfpack port)
        PortRule(
            matchTokens = listOf("adbe turbulent displace", "turbulent displace", "s_warpbubble", "bcc turbulent"),
            targetEffectId = "turbulent_displace_port",
            isPluginPort = true
        ) { raw ->
            val amount = findParam(raw.floatParams, "amount", defaultVal = 42f).coerceIn(0f, 250f)
            val size = findParam(raw.floatParams, "size", "complexity", defaultVal = 36f).coerceIn(8f, 200f)
            val evo = findParam(raw.floatParams, "evolution", defaultVal = 0f).coerceIn(0f, 720f)
            buildPluginEffectNode(
                pluginId = "turbulent_displace_port",
                fallbackName = "Turbulent Displace (AE)",
                floats = mapOf(
                    "amount" to amount,
                    "size" to size,
                    "evolution" to evo,
                    "pinEdges" to 1f
                )
            )
        },

        // 6. VideoCopilot Twitch -> ae_twitch_port (.kfpack port)
        PortRule(
            matchTokens = listOf("videocopilot twitch", "twitch", "s_tvdamage", "uni.glitch"),
            targetEffectId = "ae_twitch_port",
            isPluginPort = true
        ) { raw ->
            val amt = findParam(raw.floatParams, "amount", "amplitude", defaultVal = 45f).coerceIn(0f, 100f)
            val spd = findParam(raw.floatParams, "speed", "freq", defaultVal = 12f).coerceIn(1f, 30f)
            buildPluginEffectNode(
                pluginId = "ae_twitch_port",
                fallbackName = "Twitch (AE)",
                floats = mapOf(
                    "amount" to amt,
                    "speed" to spd,
                    "slide" to 0.65f,
                    "colorSplit" to 0.55f,
                    "lightPop" to 0.40f
                )
            )
        },

        // 7. Lumetri Color / Curves / Exposure -> lumetri_color_port (.kfpack port)
        PortRule(
            matchTokens = listOf("adbe lumetri", "lumetri color", "magic bullet looks", "colorista", "adbe exposure2", "exposure"),
            targetEffectId = "lumetri_color_port",
            isPluginPort = true
        ) { raw ->
            val exp = findParam(raw.floatParams, "exposure", defaultVal = 0.15f).coerceIn(-3f, 3f)
            val con = findParam(raw.floatParams, "contrast", defaultVal = 12f).coerceIn(-80f, 100f)
            val sat = findParam(raw.floatParams, "saturation", defaultVal = 110f).coerceIn(0f, 200f)
            val temp = findParam(raw.floatParams, "temperature", "temp", defaultVal = 0f).coerceIn(-100f, 100f)
            buildPluginEffectNode(
                pluginId = "lumetri_color_port",
                fallbackName = "Lumetri Color (AE)",
                floats = mapOf(
                    "exposure" to exp,
                    "contrast" to con,
                    "saturation" to sat,
                    "temperature" to temp
                )
            )
        },

        // 8. Chromatic Aberration / S_WarpChroma / Quick Chromatic Aberration
        PortRule(
            matchTokens = listOf("s_warpchroma", "chromatic aberration", "quick chromatic", "rgb split", "cc lens"),
            targetEffectId = "prism_lens_warp",
            isPluginPort = true
        ) { raw ->
            val disp = findParam(raw.floatParams, "dispersion", "aberration", "amount", "shift", defaultVal = 0.65f)
                .let { if (it > 3f) (it / 50f).coerceIn(0f, 2f) else it.coerceIn(0f, 2f) }
            val curv = findParam(raw.floatParams, "curvature", "size", defaultVal = 0.25f).coerceIn(-1.5f, 1.5f)
            buildPluginEffectNode(
                pluginId = "prism_lens_warp",
                fallbackName = "Prism Lens Warp",
                floats = mapOf(
                    "curvature" to curv,
                    "dispersion" to disp,
                    "angle" to 20f,
                    "mirrorEdges" to 1f
                )
            )
        },

        // 9. Native Engine Blur / Zoom / Directional / Vignette / Wave / Drop Shadow ports
        PortRule(
            matchTokens = listOf("adbe gaussian blur", "gaussian blur", "fast box blur", "camera lens blur", "s_blur"),
            targetEffectId = "Gaussian_Blur",
            isPluginPort = false
        ) { raw ->
            val blurriness = findParam(raw.floatParams, "blurriness", "radius", "blur", defaultVal = 18f)
            buildBuiltInNode("Gaussian_Blur", primary = (blurriness / 40f).coerceIn(0.05f, 2.5f))
        },
        PortRule(
            matchTokens = listOf("adbe motion blur", "directional blur", "s_blurdirectional", "cc force motion blur", "rsmb"),
            targetEffectId = "Directional_Blur",
            isPluginPort = false
        ) { raw ->
            val len = findParam(raw.floatParams, "blur length", "length", "strength", defaultVal = 22f)
            val ang = findParam(raw.floatParams, "direction", "angle", defaultVal = 0f)
            buildBuiltInNode("Directional_Blur", primary = (len / 40f).coerceIn(0.05f, 2.5f), angle = ang)
        },
        PortRule(
            matchTokens = listOf("cc radial blur", "cc radial fast blur", "adbe radial blur", "s_blurmocurves", "zoom blur"),
            targetEffectId = "Radial_Zoom_Blur",
            isPluginPort = false
        ) { raw ->
            val amt = findParam(raw.floatParams, "amount", "zoom", defaultVal = 35f)
            buildBuiltInNode("Radial_Zoom_Blur", primary = (amt / 50f).coerceIn(0.08f, 2.5f))
        },
        PortRule(
            matchTokens = listOf("s_vignette", "cc vignette", "adbe vignette", "vignette"),
            targetEffectId = "S_Vignette",
            isPluginPort = false
        ) { raw ->
            val amt = findParam(raw.floatParams, "amount", "darkness", defaultVal = 0.7f)
            buildBuiltInNode("S_Vignette", primary = if (amt > 3f) (amt / 100f).coerceIn(0.1f, 2f) else amt.coerceIn(0.1f, 2f))
        },
        PortRule(
            matchTokens = listOf("adbe wave warp", "wave warp"),
            targetEffectId = "Wave_Warp",
            isPluginPort = false
        ) { raw ->
            val h = findParam(raw.floatParams, "wave height", "height", defaultVal = 14f)
            val w = findParam(raw.floatParams, "wave width", "width", defaultVal = 40f)
            buildBuiltInNode("Wave_Warp", primary = (h / 25f).coerceIn(0.1f, 2.5f), freq = (w / 60f).coerceIn(0.1f, 3f))
        },
        PortRule(
            matchTokens = listOf("adbe unsharp mask", "sharpen", "unsharp mask"),
            targetEffectId = "Unsharp_Mask",
            isPluginPort = false
        ) { raw ->
            val amt = findParam(raw.floatParams, "amount", "sharpen", defaultVal = 50f)
            buildBuiltInNode("Unsharp_Mask", primary = (amt / 75f).coerceIn(0.1f, 2.5f))
        }
    )

    /**
     * Converts a desktop AE / FFX effect specification into either a `PORTED` [EffectNode]
     * or an `UNSUPPORTED` placeholder (returning `effectNode = null` so the layer stays clean
     * while recording the unsupported effect for replacement in Import Preview).
     */
    fun portDesktopEffect(spec: RawDesktopEffectSpec): PortedEffectConversionResult {
        PluginPackManager.ensureInitialized(null)
        val combinedKey = "${spec.matchName} ${spec.displayName}".lowercase(Locale.US).trim()

        // 1. Check if an installed .kfpack effect directly matches by ID or name
        val directPlugin = PluginPackManager.activePluginEffects().firstOrNull { pfx ->
            pfx.id.equals(spec.matchName.trim(), ignoreCase = true) ||
                pfx.name.equals(spec.displayName.trim(), ignoreCase = true)
        }
        if (directPlugin != null) {
            val node = buildPluginEffectNode(
                pluginId = directPlugin.id,
                fallbackName = directPlugin.name,
                floats = spec.floatParams,
                colors = spec.colorParams
            )
            return PortedEffectConversionResult(
                status = ImportEffectStatus.PORTED,
                effectNode = node,
                mappedEffectId = directPlugin.id,
                mappedEffectName = directPlugin.name
            )
        }

        // 2. Match against the AE / BCC / Sapphire porting table
        val matchedRule = PORT_RULES.firstOrNull { rule ->
            rule.matchTokens.any { tok -> combinedKey.contains(tok) }
        }
        if (matchedRule != null) {
            val node = matchedRule.converter(spec)
            return PortedEffectConversionResult(
                status = ImportEffectStatus.PORTED,
                effectNode = node,
                mappedEffectId = node.effectId,
                mappedEffectName = node.displayName
            )
        }

        // 3. Unmapped desktop effect -> keep layer, skip effect on render stack, mark UNSUPPORTED
        return PortedEffectConversionResult(
            status = ImportEffectStatus.UNSUPPORTED,
            effectNode = null,
            mappedEffectId = null,
            mappedEffectName = null
        )
    }

    /**
     * Builds a fresh [EffectNode] when the user picks a replacement effect in Import Preview.
     */
    fun createReplacementEffectNode(effectId: String): EffectNode {
        val plugin = PluginPackManager.findPluginEffect(effectId)
        if (plugin != null) {
            return buildPluginEffectNode(plugin.id, plugin.name, emptyMap(), emptyMap())
        }
        val def = EffectsCatalog.findById(effectId)
        return EffectNode(
            effectId = def.id,
            displayName = def.name,
            category = def.category.title,
            enabled = true,
            intensity = def.defaultIntensity,
            secondaryParam = def.defaultSecondary,
            frequencyParam = def.defaultFrequency,
            angleParam = def.defaultAngle,
            mixParam = def.defaultMix,
            tintColorHex = def.defaultTintHex,
            primaryProp = AnimatableProperty(def.defaultIntensity),
            secondaryProp = AnimatableProperty(def.defaultSecondary),
            frequencyProp = AnimatableProperty(def.defaultFrequency),
            angleProp = AnimatableProperty(def.defaultAngle),
            mixProp = AnimatableProperty(def.defaultMix)
        )
    }

    private fun findParam(map: Map<String, Float>, vararg tokens: String, defaultVal: Float): Float {
        if (map.isEmpty()) return defaultVal
        for ((k, v) in map) {
            val kl = k.lowercase(Locale.US)
            if (tokens.any { kl.contains(it) }) return v
        }
        return defaultVal
    }

    private fun buildPluginEffectNode(
        pluginId: String,
        fallbackName: String,
        floats: Map<String, Float>,
        colors: Map<String, String> = emptyMap()
    ): EffectNode {
        val plugin = PluginPackManager.findPluginEffect(pluginId)
        val customParams = LinkedHashMap<String, AnimatableProperty>()
        val customColors = LinkedHashMap<String, String>()
        if (plugin != null) {
            plugin.parameters.forEach { p ->
                when (p.type) {
                    PluginParamType.FLOAT -> {
                        val v = (floats[p.key] ?: p.defaultFloat).coerceIn(p.min, p.max)
                        customParams[p.key] = AnimatableProperty(v)
                    }
                    PluginParamType.BOOL -> {
                        val raw = floats[p.key] ?: p.defaultFloat
                        customParams[p.key] = AnimatableProperty(if (raw >= 0.5f) 1f else 0f)
                    }
                    PluginParamType.COLOR -> {
                        customColors[p.key] = colors[p.key] ?: p.defaultColorHex
                    }
                }
            }
        } else {
            floats.forEach { (k, v) -> customParams[k] = AnimatableProperty(v) }
            colors.forEach { (k, v) -> customColors[k] = v }
        }
        val p1 = customParams.values.firstOrNull()?.baseValue ?: 0.65f
        val c1 = customColors.values.firstOrNull() ?: "#FFFFFF"
        return EffectNode(
            effectId = pluginId,
            displayName = plugin?.name ?: fallbackName,
            category = plugin?.category ?: "Custom",
            enabled = true,
            intensity = p1,
            primaryProp = AnimatableProperty(p1),
            mixParam = 100f,
            mixProp = AnimatableProperty(100f),
            tintColorHex = c1,
            customParams = customParams,
            customColors = customColors
        )
    }

    private fun buildBuiltInNode(
        effectId: String,
        primary: Float,
        secondary: Float? = null,
        freq: Float? = null,
        angle: Float? = null
    ): EffectNode {
        val def = EffectsCatalog.findById(effectId)
        val p2 = secondary ?: def.defaultSecondary
        val p3 = freq ?: def.defaultFrequency
        val p4 = angle ?: def.defaultAngle
        return EffectNode(
            effectId = def.id,
            displayName = def.name,
            category = def.category.title,
            enabled = true,
            intensity = primary,
            secondaryParam = p2,
            frequencyParam = p3,
            angleParam = p4,
            mixParam = 100f,
            tintColorHex = def.defaultTintHex,
            primaryProp = AnimatableProperty(primary),
            secondaryProp = AnimatableProperty(p2),
            frequencyProp = AnimatableProperty(p3),
            angleProp = AnimatableProperty(p4),
            mixProp = AnimatableProperty(100f)
        )
    }
}
