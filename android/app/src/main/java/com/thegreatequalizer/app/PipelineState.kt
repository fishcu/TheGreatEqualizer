package com.thegreatequalizer.app

import android.graphics.Bitmap

class PipelineState {
    // Set after image load + pass 1
    var originalBitmap: Bitmap? = null
    var processedBitmap: Bitmap? = null

    // Active pass 1 outputs, rebuilt when the vignette changes.
    var pass1L: FloatArray? = null
    var pass1CRel: FloatArray? = null
    var width: Int = 0
    var height: Int = 0
    var fullWidth: Int = 0
    var fullHeight: Int = 0
    var pixelCount: Int = 0
    var vignetteAmount: Float = 0.0f
    var vignetteFalloff: Float = ParameterRanges.DEFAULT_VIGNETTE_FALLOFF

    // Immutable pre-vignette histograms used for fitting and equalization.
    var rawHistL: DoubleArray? = null
    var rawHistC: DoubleArray? = null

    // Stable per-image baseline used as the center of randomization.
    var fittedParams: PipelineParams? = null

    fun isImageLoaded(): Boolean = pass1L != null
}
