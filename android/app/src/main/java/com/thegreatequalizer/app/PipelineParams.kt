package com.thegreatequalizer.app

data class PipelineParams(
    // Light tab
    val lightSmoothing: Float = 0.1f,
    val lightShadows: Float = 0.5f,
    val lightHighlights: Float = 0.5f,
    val lightMidtoneBalance: Float = 0.5f,
    val lightMidtoneContrast: Float = 0.5f,
    val lightLift: Float = 0.0f,
    val lightGain: Float = 0.0f,

    // Color tab
    val colorSmoothing: Float = 0.1f,
    val colorMutedColors: Float = 0.5f,
    val colorVividColors: Float = 0.5f,
    val colorSaturationBalance: Float = 0.5f,
    val colorVibrancy: Float = 0.5f,
    val colorLift: Float = 0.0f,
    val colorGain: Float = 0.0f,

    // Zoned Tint tab
    val shadowTintHue: Float = 0.0f,
    val shadowTintStrength: Float = 0.0f,
    val midtoneTintHue: Float = 0.0f,
    val midtoneTintStrength: Float = 0.0f,
    val highlightTintHue: Float = 0.0f,
    val highlightTintStrength: Float = 0.0f,

    // Vignette
    val vignetteAmount: Float = 0.0f,
    val vignetteFalloff: Float = ParameterRanges.DEFAULT_VIGNETTE_FALLOFF,

    // Grain effect
    val grainAmount: Float = 0.0f,
    val grainSize: Float = ParameterRanges.DEFAULT_GRAIN_SIZE
)
