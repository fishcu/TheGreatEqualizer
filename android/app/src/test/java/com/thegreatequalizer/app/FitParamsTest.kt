package com.thegreatequalizer.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FitParamsTest {
    @Test
    fun zeroChromaHistogramProducesUiSafeNeutralFit() {
        val histogram = DoubleArray(1024)
        histogram[0] = 1000.0
        val cappedHistogram = ImagePipeline.capHistogram(
            histogram,
            900.0
        )

        val fitted = FitParams.fitInitialParams(cappedHistogram)

        assertNeutralFiniteFit(fitted)
        assertEquals(0.0, fitted["x_lo"]!!, 0.0)
        assertEquals(0.0, fitted["x_hi"]!!, 0.0)
        ParameterRanges.requireWithinUiBounds(
            PipelineParams(
                colorMutedColors = ParameterRanges.shapeToControl(
                    fitted["t"]!!.toFloat(),
                    ParameterRanges.TOE
                ),
                colorVividColors = ParameterRanges.shapeToControl(
                    fitted["s"]!!.toFloat(),
                    ParameterRanges.SHOULDER
                ),
                colorSaturationBalance = ParameterRanges.shapeToControl(
                    fitted["c"]!!.toFloat(),
                    ParameterRanges.BALANCE
                ),
                colorVibrancy = ParameterRanges.shapeToControl(
                    fitted["g"]!!.toFloat(),
                    ParameterRanges.GAMMA
                ),
                colorLift = fitted["x_lo"]!!.toFloat(),
                colorGain = (fitted["x_hi"]!! - 1.0).toFloat()
            )
        )
    }

    @Test
    fun singleInteriorChromaBinUsesFiniteZeroWidthBounds() {
        val histogram = DoubleArray(256)
        histogram[64] = 1000.0

        val fitted = FitParams.fitInitialParams(histogram)
        val expectedBoundary = 64.0 / 255.0

        assertNeutralFiniteFit(fitted)
        assertEquals(expectedBoundary, fitted["x_lo"]!!, 0.0)
        assertEquals(expectedBoundary, fitted["x_hi"]!!, 0.0)
    }

    @Test
    fun nonDegenerateHistogramStillUsesOptimizer() {
        val histogram = DoubleArray(256) { 1.0 }

        val fitted = FitParams.fitInitialParams(histogram)

        assertTrue(fitted.values.all(Double::isFinite))
        assertTrue(fitted["steps"]!! > 0.0)
    }

    @Test
    fun allBlackImageProducesUiSafeLightAndColorFits() {
        assertImageFitIsUiSafe(
            lightHistogram = singleBinHistogram(0),
            colorHistogram = singleBinHistogram(0)
        )
    }

    @Test
    fun allWhiteImageProducesUiSafeLightAndColorFits() {
        assertImageFitIsUiSafe(
            lightHistogram = singleBinHistogram(ImagePipeline.NUM_BINS - 1),
            colorHistogram = singleBinHistogram(0)
        )
    }

    @Test
    fun uniformSingleColorImageProducesUiSafeLightAndColorFits() {
        assertImageFitIsUiSafe(
            lightHistogram = singleBinHistogram(96),
            colorHistogram = singleBinHistogram(160)
        )
    }

    @Test
    fun nearUniformLuminanceProducesUiSafeFit() {
        val lightHistogram = singleBinHistogram(128)
        lightHistogram[128] -= 1.0
        lightHistogram[129] = 1.0

        assertImageFitIsUiSafe(
            lightHistogram,
            colorHistogram = singleBinHistogram(80)
        )
    }

    private fun assertNeutralFiniteFit(fitted: Map<String, Double>) {
        assertTrue(fitted.values.all(Double::isFinite))
        assertEquals(0.0, fitted["steps"]!!, 0.0)
        for (name in FitParams.PARAM_NAMES) {
            assertEquals(
                FitParams.PARAM_DEFAULTS[name]!!,
                fitted[name]!!,
                0.0
            )
        }
    }

    private fun assertImageFitIsUiSafe(
        lightHistogram: DoubleArray,
        colorHistogram: DoubleArray
    ) {
        val state = PipelineState().apply {
            rawHistL = lightHistogram
            rawHistC = colorHistogram
        }
        val lightFitted = ImagePipeline.fitToInput(
            state,
            PipelineParams(),
            "light"
        )
        val lightAndColorFitted = ImagePipeline.fitToInput(
            state,
            lightFitted,
            "color"
        )

        ParameterRanges.requireWithinUiBounds(lightAndColorFitted)
        ParameterRanges.requireMathematicallySafe(lightAndColorFitted)
    }

    private fun singleBinHistogram(bin: Int): DoubleArray =
        DoubleArray(ImagePipeline.NUM_BINS).apply {
            this[bin] = 1000.0
        }
}
