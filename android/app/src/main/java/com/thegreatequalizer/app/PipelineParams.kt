package com.thegreatequalizer.app

data class PipelineParams(
    // Light tab
    val lightStrength: Float = 0.9f,                          // cap fraction [0, 1] for L histogram
    val lightShadows: Float = (Math.PI / 4).toFloat(),        // t angle
    val lightHighlights: Float = (Math.PI / 4).toFloat(),     // s angle
    val lightMidtoneBalance: Float = 0.5f,                    // c
    val lightMidtoneContrast: Float = (Math.PI / 4).toFloat(),// g angle
    val lightBlacks: Float = 0.0f,                            // black point
    val lightWhites: Float = 0.0f,                            // white point (actual white = x_hi - 1.0)

    // Color tab
    val colorStrength: Float = 0.9f,                          // cap fraction [0, 1] for C histogram
    val colorMutedColors: Float = (Math.PI / 4).toFloat(),    // t angle
    val colorVividColors: Float = (Math.PI / 4).toFloat(),    // s angle
    val colorSaturationBalance: Float = 0.5f,                 // c
    val colorVibrancy: Float = (Math.PI / 4).toFloat(),       // g angle
    val colorBlacks: Float = 0.0f,
    val colorWhites: Float = 0.0f,

    // Zoned Tint tab
    val shadowTintAngle: Float = 0.0f,      // radians
    val shadowTintStrength: Float = 0.0f,    // [0, 0.25]
    val midtoneTintAngle: Float = 0.0f,
    val midtoneTintStrength: Float = 0.0f,
    val highlightTintAngle: Float = 0.0f,
    val highlightTintStrength: Float = 0.0f,

    // Vignette
    val vignetteAmount: Float = 0.0f,         // attenuation in exposure stops
    val vignetteFalloff: Float = 5.0f,        // exponent of normalized radius

    // Grain effect
    val grainAmount: Float = 0.0f,           // OKLab L standard deviation at midtones
    val grainSize: Float = 1.25f             // apparent grain diameter in output pixels
)
