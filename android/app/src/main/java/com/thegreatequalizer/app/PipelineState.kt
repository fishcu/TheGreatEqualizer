package com.thegreatequalizer.app

import android.graphics.Bitmap

class PipelineState {
    // Set after image load + pass 1
    var originalBitmap: Bitmap? = null
    var processedBitmap: Bitmap? = null

    // Pass 1 outputs (only recomputed when image changes)
    var pass1L: FloatArray? = null
    var pass1CRel: FloatArray? = null
    var width: Int = 0
    var height: Int = 0
    var pixelCount: Int = 0

    // Raw histograms (only recomputed when image changes)
    var rawHistL: DoubleArray? = null
    var rawHistC: DoubleArray? = null

    // Fitted defaults from auto-fit (stored so "Fit to Input" can restore them)
    var fittedDefaultsL: Map<String, Double>? = null
    var fittedDefaultsC: Map<String, Double>? = null

    fun isImageLoaded(): Boolean = pass1L != null
}
