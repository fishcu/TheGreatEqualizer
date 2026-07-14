package com.thegreatequalizer.app

import kotlin.math.PI
import kotlin.math.floor

object ParameterRanges {
    data class ShapeRange(
        val min: Float,
        val neutral: Float,
        val max: Float
    )

    val TOE = ShapeRange(0.01f, (PI / 4.0).toFloat(), 1.37f)
    val SHOULDER = ShapeRange(0.20f, (PI / 4.0).toFloat(), 1.56f)
    val BALANCE = ShapeRange(0.01f, 0.5f, 0.99f)
    val GAMMA = ShapeRange(0.10f, (PI / 4.0).toFloat(), 1.25f)

    const val LIFT_MIN = -0.20f
    const val LIFT_MAX = 1.0f
    const val GAIN_MIN = -1.0f
    const val GAIN_MAX = 0.20f

    const val TINT_STRENGTH_MAX = 0.25f
    const val VIGNETTE_AMOUNT_MAX = 10.0f
    const val VIGNETTE_FALLOFF_MIN = 1.0f
    const val VIGNETTE_FALLOFF_MAX = 10.0f
    const val GRAIN_AMOUNT_MAX = 0.15f
    const val GRAIN_SIZE_MIN = 0.25f
    const val GRAIN_SIZE_MAX = 4.0f

    const val DEFAULT_VIGNETTE_FALLOFF = 0.44444445f
    const val DEFAULT_GRAIN_SIZE = 0.26666668f

    private const val TWO_PI = (2.0 * PI).toFloat()
    private const val HALF_PI = (PI / 2.0).toFloat()

    fun controlToShape(control: Float, range: ShapeRange): Float {
        return if (control <= 0.5f) {
            range.min + (range.neutral - range.min) * (control / 0.5f)
        } else {
            range.neutral +
                (range.max - range.neutral) * ((control - 0.5f) / 0.5f)
        }
    }

    fun shapeToControl(value: Float, range: ShapeRange): Float {
        return if (value <= range.neutral) {
            0.5f * (value - range.min) / (range.neutral - range.min)
        } else {
            0.5f +
                0.5f * (value - range.neutral) / (range.max - range.neutral)
        }
    }

    fun hueToRadians(hue: Float): Float {
        val wrapped = hue - floor(hue)
        return wrapped * TWO_PI
    }

    fun tintStrengthToRender(value: Float): Float =
        value * TINT_STRENGTH_MAX

    fun vignetteAmountToRender(value: Float): Float =
        value * VIGNETTE_AMOUNT_MAX

    fun vignetteFalloffToRender(value: Float): Float =
        VIGNETTE_FALLOFF_MIN +
            value * (VIGNETTE_FALLOFF_MAX - VIGNETTE_FALLOFF_MIN)

    fun vignetteFalloffFromRender(value: Float): Float =
        (value - VIGNETTE_FALLOFF_MIN) /
            (VIGNETTE_FALLOFF_MAX - VIGNETTE_FALLOFF_MIN)

    fun grainAmountToRender(value: Float): Float =
        value * GRAIN_AMOUNT_MAX

    fun grainAmountFromRender(value: Float): Float =
        value / GRAIN_AMOUNT_MAX

    fun grainSizeToRender(value: Float): Float =
        GRAIN_SIZE_MIN + value * (GRAIN_SIZE_MAX - GRAIN_SIZE_MIN)

    fun grainSizeFromRender(value: Float): Float =
        (value - GRAIN_SIZE_MIN) / (GRAIN_SIZE_MAX - GRAIN_SIZE_MIN)

    fun requireWithinUiBounds(params: PipelineParams) {
        val normalized = floatArrayOf(
            params.lightSmoothing,
            params.lightShadows,
            params.lightHighlights,
            params.lightMidtoneBalance,
            params.lightMidtoneContrast,
            params.colorSmoothing,
            params.colorMutedColors,
            params.colorVividColors,
            params.colorSaturationBalance,
            params.colorVibrancy,
            params.shadowTintHue,
            params.shadowTintStrength,
            params.midtoneTintHue,
            params.midtoneTintStrength,
            params.highlightTintHue,
            params.highlightTintStrength,
            params.vignetteAmount,
            params.vignetteFalloff,
            params.grainAmount,
            params.grainSize
        )
        require(normalized.all { value -> value in 0.0f..1.0f }) {
            "Normalized controls must be in [0, 1]"
        }
        require(params.lightLift in LIFT_MIN..LIFT_MAX) {
            "Light lift must be in [$LIFT_MIN, $LIFT_MAX]"
        }
        require(params.colorLift in LIFT_MIN..LIFT_MAX) {
            "Color lift must be in [$LIFT_MIN, $LIFT_MAX]"
        }
        require(params.lightGain in GAIN_MIN..GAIN_MAX) {
            "Light gain must be in [$GAIN_MIN, $GAIN_MAX]"
        }
        require(params.colorGain in GAIN_MIN..GAIN_MAX) {
            "Color gain must be in [$GAIN_MIN, $GAIN_MAX]"
        }
    }

    fun requireMathematicallySafe(params: PipelineParams) {
        val values = floatArrayOf(
            params.lightSmoothing,
            params.lightShadows,
            params.lightHighlights,
            params.lightMidtoneBalance,
            params.lightMidtoneContrast,
            params.lightLift,
            params.lightGain,
            params.colorSmoothing,
            params.colorMutedColors,
            params.colorVividColors,
            params.colorSaturationBalance,
            params.colorVibrancy,
            params.colorLift,
            params.colorGain,
            params.shadowTintHue,
            params.shadowTintStrength,
            params.midtoneTintHue,
            params.midtoneTintStrength,
            params.highlightTintHue,
            params.highlightTintStrength,
            params.vignetteAmount,
            params.vignetteFalloff,
            params.grainAmount,
            params.grainSize
        )
        require(values.all(Float::isFinite)) {
            "All parameters must be finite"
        }

        requirePositiveShape(params.lightShadows, TOE, "Light shadows")
        requirePositiveShape(
            params.lightHighlights,
            SHOULDER,
            "Light highlights"
        )
        requireBalance(
            params.lightMidtoneBalance,
            "Light midtone balance"
        )
        requirePositiveShape(
            params.lightMidtoneContrast,
            GAMMA,
            "Light midtone contrast"
        )
        requirePositiveShape(params.colorMutedColors, TOE, "Muted colors")
        requirePositiveShape(params.colorVividColors, SHOULDER, "Vivid colors")
        requireBalance(
            params.colorSaturationBalance,
            "Saturation balance"
        )
        requirePositiveShape(params.colorVibrancy, GAMMA, "Vibrancy")

        require((1.0f + params.lightGain - params.lightLift).isFinite()) {
            "Light lift and gain must produce a finite span"
        }
        require((1.0f + params.colorGain - params.colorLift).isFinite()) {
            "Color lift and gain must produce a finite span"
        }

        val vignetteAmount = vignetteAmountToRender(params.vignetteAmount)
        val vignetteFalloff = vignetteFalloffToRender(params.vignetteFalloff)
        val grainAmount = grainAmountToRender(params.grainAmount)
        val grainSize = grainSizeToRender(params.grainSize)
        require(vignetteAmount.isFinite()) {
            "Vignette amount must map to a finite value"
        }
        require(vignetteFalloff.isFinite() && vignetteFalloff > 0.0f) {
            "Vignette falloff must map to a positive finite value"
        }
        require(grainAmount.isFinite()) {
            "Grain amount must map to a finite value"
        }
        require(grainSize.isFinite() && grainSize > 0.0f) {
            "Grain size must map to a positive finite value"
        }
        require(
            tintStrengthToRender(params.shadowTintStrength).isFinite() &&
                tintStrengthToRender(params.midtoneTintStrength).isFinite() &&
                tintStrengthToRender(params.highlightTintStrength).isFinite()
        ) {
            "Tint strengths must map to finite values"
        }
    }

    private fun requirePositiveShape(
        control: Float,
        range: ShapeRange,
        label: String
    ) {
        val angle = controlToShape(control, range)
        require(angle > 0.0f && angle < HALF_PI) {
            "$label must map to an angle between 0 and pi/2"
        }
    }

    private fun requireBalance(control: Float, label: String) {
        val balance = controlToShape(control, BALANCE)
        require(balance > 0.0f && balance < 1.0f) {
            "$label must map to a value between 0 and 1"
        }
    }
}
