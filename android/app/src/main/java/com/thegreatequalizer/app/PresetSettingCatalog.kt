package com.thegreatequalizer.app

data class PresetSettingSpec(
    val key: String,
    val label: String,
    val group: String,
    val componentNames: List<String>,
    val read: (PipelineParams) -> PresetSettingValue,
    val write: (PipelineParams, PresetSettingValue) -> PipelineParams
)

object PresetSettingCatalog {
    val all: List<PresetSettingSpec> = listOf(
        scalar(
            "light.smoothing",
            "Smoothing",
            "Light",
            PipelineParams::lightSmoothing
        ) { params, value -> params.copy(lightSmoothing = value) },
        scalar(
            "light.shadows",
            "Shadows",
            "Light",
            PipelineParams::lightShadows
        ) { params, value -> params.copy(lightShadows = value) },
        scalar(
            "light.highlights",
            "Highlights",
            "Light",
            PipelineParams::lightHighlights
        ) { params, value -> params.copy(lightHighlights = value) },
        scalar(
            "light.balance",
            "Midtone Balance",
            "Light",
            PipelineParams::lightMidtoneBalance
        ) { params, value -> params.copy(lightMidtoneBalance = value) },
        scalar(
            "light.midtones",
            "Midtones",
            "Light",
            PipelineParams::lightMidtoneContrast
        ) { params, value -> params.copy(lightMidtoneContrast = value) },
        scalar(
            "light.lift",
            "Lift",
            "Light",
            PipelineParams::lightLift
        ) { params, value -> params.copy(lightLift = value) },
        scalar(
            "light.gain",
            "Gain",
            "Light",
            PipelineParams::lightGain
        ) { params, value -> params.copy(lightGain = value) },
        scalar(
            "color.smoothing",
            "Smoothing",
            "Color",
            PipelineParams::colorSmoothing
        ) { params, value -> params.copy(colorSmoothing = value) },
        scalar(
            "color.muted",
            "Muted Colors",
            "Color",
            PipelineParams::colorMutedColors
        ) { params, value -> params.copy(colorMutedColors = value) },
        scalar(
            "color.vivid",
            "Vivid Colors",
            "Color",
            PipelineParams::colorVividColors
        ) { params, value -> params.copy(colorVividColors = value) },
        scalar(
            "color.balance",
            "Saturation Balance",
            "Color",
            PipelineParams::colorSaturationBalance
        ) { params, value -> params.copy(colorSaturationBalance = value) },
        scalar(
            "color.vibrancy",
            "Vibrancy",
            "Color",
            PipelineParams::colorVibrancy
        ) { params, value -> params.copy(colorVibrancy = value) },
        scalar(
            "color.lift",
            "Lift",
            "Color",
            PipelineParams::colorLift
        ) { params, value -> params.copy(colorLift = value) },
        scalar(
            "color.gain",
            "Gain",
            "Color",
            PipelineParams::colorGain
        ) { params, value -> params.copy(colorGain = value) },
        compound(
            "tint.shadows",
            "Shadows",
            "Zoned Tint",
            { params ->
                PresetSettingValue.compound(
                    params.shadowTintHue,
                    params.shadowTintStrength
                )
            }
        ) { params, hue, strength ->
            params.copy(
                shadowTintHue = hue,
                shadowTintStrength = strength
            )
        },
        compound(
            "tint.midtones",
            "Midtones",
            "Zoned Tint",
            { params ->
                PresetSettingValue.compound(
                    params.midtoneTintHue,
                    params.midtoneTintStrength
                )
            }
        ) { params, hue, strength ->
            params.copy(
                midtoneTintHue = hue,
                midtoneTintStrength = strength
            )
        },
        compound(
            "tint.highlights",
            "Highlights",
            "Zoned Tint",
            { params ->
                PresetSettingValue.compound(
                    params.highlightTintHue,
                    params.highlightTintStrength
                )
            }
        ) { params, hue, strength ->
            params.copy(
                highlightTintHue = hue,
                highlightTintStrength = strength
            )
        },
        scalar(
            "vignette.amount",
            "Amount",
            "Vignette",
            PipelineParams::vignetteAmount
        ) { params, value -> params.copy(vignetteAmount = value) },
        scalar(
            "vignette.falloff",
            "Falloff",
            "Vignette",
            PipelineParams::vignetteFalloff
        ) { params, value -> params.copy(vignetteFalloff = value) },
        scalar(
            "grain.amount",
            "Amount",
            "Grain",
            PipelineParams::grainAmount
        ) { params, value -> params.copy(grainAmount = value) },
        scalar(
            "grain.size",
            "Size",
            "Grain",
            PipelineParams::grainSize
        ) { params, value -> params.copy(grainSize = value) }
    )

    private val byKey = all.associateBy(PresetSettingSpec::key)

    fun changedKeys(
        current: PipelineParams,
        baseline: PipelineParams
    ): Set<String> =
        all.filter { spec -> spec.read(current) != spec.read(baseline) }
            .mapTo(linkedSetOf(), PresetSettingSpec::key)

    fun capture(
        params: PipelineParams,
        includedKeys: Set<String>
    ): Map<String, PresetSettingValue> {
        val values = linkedMapOf<String, PresetSettingValue>()
        for (spec in all) {
            if (spec.key in includedKeys) {
                values[spec.key] = spec.read(params)
            }
        }
        return values
    }

    fun applyPreset(
        base: PipelineParams,
        preset: Preset
    ): PipelineParams {
        var result = base
        for (spec in all) {
            val value = preset.settings[spec.key] ?: continue
            requireComponentCount(spec, value)
            result = spec.write(result, value)
        }
        try {
            ParameterRanges.requireMathematicallySafe(result)
        } catch (error: IllegalArgumentException) {
            throw PresetFormatException(
                error.message ?: "Preset contains an unsafe value",
                error
            )
        }
        return result
    }

    fun normalizeAndValidate(preset: Preset): Preset {
        if (preset.version != Preset.CURRENT_VERSION) {
            throw PresetFormatException(
                "Unsupported preset version: ${preset.version}"
            )
        }
        val name = try {
            PresetNames.normalizeName(preset.name)
        } catch (error: IllegalArgumentException) {
            throw PresetFormatException(error.message!!, error)
        }
        val author = try {
            PresetNames.normalizeAuthor(preset.author)
        } catch (error: IllegalArgumentException) {
            throw PresetFormatException(error.message!!, error)
        }
        if (preset.settings.isEmpty()) {
            throw PresetFormatException(
                "A preset must contain at least one setting"
            )
        }
        val unknownKeys = preset.settings.keys - byKey.keys
        if (unknownKeys.isNotEmpty()) {
            throw PresetFormatException(
                "Unknown preset setting: ${unknownKeys.first()}"
            )
        }
        for ((key, value) in preset.settings) {
            val spec = byKey[key]!!
            requireComponentCount(spec, value)
            if (!value.components.all(Float::isFinite)) {
                throw PresetFormatException(
                    "Preset setting $key must contain finite numbers"
                )
            }
        }
        val normalized = preset.copy(name = name, author = author)
        applyPreset(PipelineParams(), normalized)
        return normalized
    }

    fun specForKey(key: String): PresetSettingSpec =
        byKey[key] ?: throw PresetFormatException(
            "Unknown preset setting: $key"
        )

    private fun requireComponentCount(
        spec: PresetSettingSpec,
        value: PresetSettingValue
    ) {
        if (value.components.size != spec.componentNames.size) {
            throw PresetFormatException(
                "Preset setting ${spec.key} requires " +
                    "${spec.componentNames.size} value(s)"
            )
        }
    }

    private fun scalar(
        key: String,
        label: String,
        group: String,
        read: (PipelineParams) -> Float,
        write: (PipelineParams, Float) -> PipelineParams
    ): PresetSettingSpec =
        PresetSettingSpec(
            key = key,
            label = label,
            group = group,
            componentNames = listOf("value"),
            read = { params -> PresetSettingValue.scalar(read(params)) },
            write = { params, value ->
                write(params, value.components.single())
            }
        )

    private fun compound(
        key: String,
        label: String,
        group: String,
        read: (PipelineParams) -> PresetSettingValue,
        write: (
            PipelineParams,
            Float,
            Float
        ) -> PipelineParams
    ): PresetSettingSpec =
        PresetSettingSpec(
            key = key,
            label = label,
            group = group,
            componentNames = listOf("hue", "strength"),
            read = read,
            write = { params, value ->
                write(
                    params,
                    value.components[0],
                    value.components[1]
                )
            }
        )
}
