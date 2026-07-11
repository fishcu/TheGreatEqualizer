package com.thegreatequalizer.app

import android.graphics.Bitmap
import android.util.Log
import kotlin.math.*

/**
 * The Great Equalizer image processing pipeline.
 * Ported from Python prototype (main.py).
 *
 * Full pipeline:
 * 1. sRGB EOTF (uint8 → linear float)
 * 2. Linear RGB → OKLab
 * 3. Build gamut LUT
 * 4. Compute relative chroma
 * 5. Histogram computation + capping
 * 6. CDF matching on L channel
 * 7. CDF matching on relative chroma
 * 8. Reconstruct (a, b) from modified C_rel and original hue
 * 9. Zone weights (CDF-adaptive smoothstep)
 * 10. Chroma offset per zone
 * 11. Gamut clamp
 * 12. OKLab → linear → sRGB OETF → uint8
 */
object ImagePipeline {

    private const val TAG = "TheGreatEqualizer"
    const val NUM_BINS = 256

    /**
     * Maximum pixel dimension (longer edge) for processing.
     * Images larger than this are downscaled before the pipeline runs.
     * This keeps memory usage manageable on devices with limited heap.
     */
    const val MAX_PROCESSING_EDGE = 1024

    /**
     * Downscale a bitmap so its longer edge is at most [maxEdge] pixels.
     * Returns the original bitmap if it's already small enough.
     */
    fun downscaleIfNeeded(input: Bitmap, maxEdge: Int = MAX_PROCESSING_EDGE): Bitmap {
        val longerEdge = maxOf(input.width, input.height)
        if (longerEdge <= maxEdge) return input
        val scale = maxEdge.toFloat() / longerEdge.toFloat()
        val newWidth = (input.width * scale).toInt().coerceAtLeast(1)
        val newHeight = (input.height * scale).toInt().coerceAtLeast(1)
        Log.i("ImagePipeline", "Downscaling ${input.width}x${input.height} -> ${newWidth}x${newHeight}")
        return Bitmap.createScaledBitmap(input, newWidth, newHeight, true)
    }

    // ---------------------------------------------------------------
    // Fast uniform-bin interpolation
    // ---------------------------------------------------------------

    /**
     * Fast linear interp for uniform bin centers in [0,1] with [numBins] entries.
     * Direct index computation — O(1) instead of O(log n) binary search.
     */
    private fun interpUniform(x: Float, fp: FloatArray, numBins: Int): Float {
        val xc = x.coerceIn(0f, 1f)
        val idxF = xc * (numBins - 1).toFloat()
        val lo = idxF.toInt().coerceIn(0, numBins - 2)
        val t = idxF - lo.toFloat()
        return fp[lo] + t * (fp[lo + 1] - fp[lo])
    }

    // ---------------------------------------------------------------
    // Histogram operations
    // ---------------------------------------------------------------

    /** Compute histogram of a float [0,1] channel. Returns DoubleArray[NUM_BINS]. */
    fun computeHistogram(channel: FloatArray): DoubleArray {
        val hist = DoubleArray(NUM_BINS)
        for (v in channel) {
            val bin = (v.coerceIn(0f, 1f) * (NUM_BINS - 1)).toInt().coerceIn(0, NUM_BINS - 1)
            hist[bin] += 1.0
        }
        return hist
    }

    /**
     * Cap histogram bins at [cap] and redistribute excess locally.
     * Each over-cap bin's excess is spread outward symmetrically.
     */
    fun capHistogram(hist: DoubleArray, cap: Double): DoubleArray {
        // cap <= 0 means maximum smoothing: flatten to the histogram mean
        val effectiveCap = if (cap <= 0.0) {
            val mean = hist.sum() / hist.size.coerceAtLeast(1)
            if (mean <= 0.0) return hist.copyOf()
            mean
        } else cap
        if ((hist.maxOrNull() ?: 0.0) <= effectiveCap) return hist.copyOf()

        val out = hist.copyOf()
        val n = out.size

        for (i in 0 until n) {
            if (out[i] <= effectiveCap) continue
            var surplus = out[i] - effectiveCap
            out[i] = effectiveCap
            var lo = i - 1
            var hi = i + 1
            while (surplus > 1e-12 && (lo >= 0 || hi < n)) {
                val roomLo = if (lo >= 0 && out[lo] < effectiveCap) effectiveCap - out[lo] else 0.0
                val roomHi = if (hi < n && out[hi] < effectiveCap) effectiveCap - out[hi] else 0.0
                val totalRoom = roomLo + roomHi
                if (totalRoom > 0.0) {
                    val toPlace = minOf(surplus, totalRoom)
                    val fracLo = roomLo / totalRoom
                    val depositLo = toPlace * fracLo
                    val depositHi = toPlace - depositLo
                    if (lo >= 0) out[lo] += depositLo
                    if (hi < n) out[hi] += depositHi
                    surplus -= toPlace
                }
                lo--
                hi++
            }
        }

        return out
    }

    /**
     * Linear interpolation for non-uniform x data (binary search).
     * Used for CDF transfer mapping where bins may not be uniformly spaced.
     */
    fun interpSingle(x: Float, xp: FloatArray, fp: FloatArray): Float {
        if (x <= xp[0]) return fp[0]
        if (x >= xp[xp.size - 1]) return fp[fp.size - 1]
        var lo = 0
        var hi = xp.size - 1
        while (hi - lo > 1) {
            val mid = (lo + hi) / 2
            if (xp[mid] <= x) lo = mid else hi = mid
        }
        val t = (x - xp[lo]) / (xp[hi] - xp[lo])
        return fp[lo] + t * (fp[hi] - fp[lo])
    }

    // ---------------------------------------------------------------
    // Full pipeline
    // ---------------------------------------------------------------

    /**
     * A single named timing entry from the pipeline.
     * @param name  Human-readable step name (e.g. "Step 1 sRGB EOTF")
     * @param ms    Duration in milliseconds
     * @param isIO  true for I/O steps excluded from the "Total pipeline" computation time
     */
    data class StepTiming(val name: String, val ms: Long, val isIO: Boolean = false)

    data class ProcessingResult(
        val outputBitmap: Bitmap,
        val fittedParamsL: Map<String, Double>,
        val fittedParamsC: Map<String, Double>,
        val timingProfile: Map<String, Long>,
        val cappedHistL: DoubleArray,
        val cappedHistC: DoubleArray,
        val stepTimings: List<StepTiming> = emptyList()
    )

    // ---------------------------------------------------------------
    // Transfer LUT builder (extracted from applyChannelCdf for GPU path)
    // ---------------------------------------------------------------

    /**
     * Build a 256-entry transfer LUT from a capped histogram and fitted params.
     * This is the same mapping logic as applyChannelCdf but returns just the
     * transfer array (no per-pixel application).
     */
    fun buildTransferLut(
        cappedHist: DoubleArray,
        t: Double, s: Double, c: Double, g: Double
    ): FloatArray {
        val numBins = NUM_BINS

        // Target CDF at high resolution
        val targetX = DoubleArray(4096) { it.toDouble() / 4095.0 }
        val targetY = FitParams.computeTargetCdf(targetX, t, s, c, g)

        // Input CDF from capped histogram
        val inputCdf = DoubleArray(numBins)
        var cumSum = 0.0
        for (i in 0 until numBins) {
            cumSum += cappedHist[i]
            inputCdf[i] = cumSum
        }
        val total = inputCdf[numBins - 1]
        if (total > 0) {
            for (i in 0 until numBins) inputCdf[i] /= total
        }

        // Build transfer: interp(inputCdf, targetY, targetX)
        val targetXFloat = FloatArray(4096) { targetX[it].toFloat() }
        val targetYFloat = FloatArray(4096) { targetY[it].toFloat() }
        val transfer = FloatArray(numBins) { i ->
            interpSingle(inputCdf[i].toFloat(), targetYFloat, targetXFloat)
        }

        // Find trim boundaries and fixup edges
        var aboveLoFirst = -1
        var belowHiLast = -1
        for (i in 0 until numBins) {
            if (inputCdf[i] > FitParams.TRIM_EPS && aboveLoFirst < 0) aboveLoFirst = i
        }
        for (i in numBins - 1 downTo 0) {
            if (inputCdf[i] < 1.0 - FitParams.TRIM_EPS && belowHiLast < 0) belowHiLast = i
        }

        return transfer
    }

    /**
     * Build transfer LUT with edge fixup that accounts for black/white levels.
     * The shader does: out = clamp(black + transfer(in) * span, 0, 1)
     * So the transfer needs to include the edge interpolation.
     */
    fun buildTransferLutWithEdges(
        cappedHist: DoubleArray,
        t: Double, s: Double, c: Double, g: Double,
        black: Double, white: Double
    ): FloatArray {
        val numBins = NUM_BINS
        val span = (1.0 + white - black).toFloat()

        // Target CDF at high resolution
        val targetX = DoubleArray(4096) { it.toDouble() / 4095.0 }
        val targetY = FitParams.computeTargetCdf(targetX, t, s, c, g)

        // Input CDF from capped histogram
        val inputCdf = DoubleArray(numBins)
        var cumSum = 0.0
        for (i in 0 until numBins) {
            cumSum += cappedHist[i]
            inputCdf[i] = cumSum
        }
        val total = inputCdf[numBins - 1]
        if (total > 0) {
            for (i in 0 until numBins) inputCdf[i] /= total
        }

        // Build transfer: interp(inputCdf, targetY, targetX)
        val targetXFloat = FloatArray(4096) { targetX[it].toFloat() }
        val targetYFloat = FloatArray(4096) { targetY[it].toFloat() }
        val transfer = FloatArray(numBins) { i ->
            interpSingle(inputCdf[i].toFloat(), targetYFloat, targetXFloat)
        }

        // Edge fixup (same as applyChannelCdf)
        var aboveLoFirst = -1
        var belowHiLast = -1
        for (i in 0 until numBins) {
            if (inputCdf[i] > FitParams.TRIM_EPS && aboveLoFirst < 0) aboveLoFirst = i
        }
        for (i in numBins - 1 downTo 0) {
            if (inputCdf[i] < 1.0 - FitParams.TRIM_EPS && belowHiLast < 0) belowHiLast = i
        }

        if (aboveLoFirst >= 0 && belowHiLast >= 0 && span > 1e-10f) {
            val first = aboveLoFirst
            val last = belowHiLast

            val loTarget = (-black / span.toDouble()).toFloat()
            val hiTarget = ((1.0 - black) / span.toDouble()).toFloat()

            if (first > 0) {
                for (j in 0 until first) {
                    transfer[j] = loTarget + (transfer[first] - loTarget) * j.toFloat() / first.toFloat()
                }
            }
            if (last < numBins - 1) {
                val nUpper = numBins - 1 - last
                for (j in 1..nUpper) {
                    transfer[last + j] = transfer[last] + (hiTarget - transfer[last]) * j.toFloat() / nUpper.toFloat()
                }
            }
        }

        return transfer
    }

    // ---------------------------------------------------------------
    // GPU pipeline
    // ---------------------------------------------------------------

    /**
     * Run the GPU-accelerated image processing pipeline.
     *
     * Pass 1 (GPU): Steps 1-4 (sRGB EOTF, RGB→OKLab, relative chroma)
     * CPU: Steps 5, 5b (histogram, cap, Adam fit, transfer LUT build)
     * Pass 2 (GPU): Steps 6-12 (CDF match, reconstruct, zone, gamut clamp, sRGB)
     */
    fun processGpu(
        input: Bitmap,
        gamutLut: FloatArray,
        gpuPipeline: GpuPipeline,
        skipDownscale: Boolean = false
    ): ProcessingResult {
        val t0 = System.nanoTime()
        val timings = mutableMapOf<String, Long>()
        val steps = mutableListOf<StepTiming>()
        fun ms(since: Long): Long = (System.nanoTime() - since) / 1_000_000
        fun mark(name: String, since: Long, isIO: Boolean = false) {
            val m = ms(since); timings[name] = m; steps.add(StepTiming(name, m, isIO)); Log.i(TAG, "[GPU] $name: ${m}ms")
        }

        // Downscale
        val scaled = if (skipDownscale) input else downscaleIfNeeded(input)
        val width = scaled.width
        val height = scaled.height
        val pixelCount = width * height
        Log.i(TAG, "[GPU] Image size: ${width}x${height} ($pixelCount px)")

        // Step 0: Extract pixels  [I/O]
        var tStep = System.nanoTime()
        val pixels = IntArray(pixelCount)
        scaled.getPixels(pixels, 0, width, 0, 0, width, height)
        mark("Step 0 getPixels", tStep, isIO = true)

        // Steps 1-4 (GPU pass 1): sRGB EOTF, RGB→OKLab, relative chroma
        tStep = System.nanoTime()
        val pass1 = gpuPipeline.processPass1(pixels, pixelCount)
        mark("Steps 1-4 GPU pass1", tStep)

        val L = pass1.L
        val cRel = pass1.cRel

        // Step 5: Histogram + cap (CPU)
        tStep = System.nanoTime()
        // Clamp L to [0,1] for histogram
        for (i in L.indices) L[i] = L[i].coerceIn(0f, 1f)

        val rawHistL = computeHistogram(L)
        val capL = rawHistL.maxOrNull() ?: 0.0
        val cappedHistL = capHistogram(rawHistL, capL)

        val rawHistC = computeHistogram(cRel)
        val capC = rawHistC.maxOrNull() ?: 0.0
        val cappedHistC = capHistogram(rawHistC, capC)
        mark("Step 5 Histogram+cap", tStep)

        // Step 5b: Fit params (CPU)
        tStep = System.nanoTime()
        val fittedL = FitParams.fitInitialParams(cappedHistL)
        val fittedC = FitParams.fitInitialParams(cappedHistC)
        val stepsL = fittedL["steps"]?.toInt() ?: 0
        val stepsC = fittedC["steps"]?.toInt() ?: 0
        mark("Step 5b Fit params ($stepsL+$stepsC steps)", tStep)

        // Build transfer LUTs (CPU)
        tStep = System.nanoTime()
        val tL = fittedL["t"]!!
        val sL = fittedL["s"]!!
        val cL = fittedL["c"]!!
        val gL = fittedL["g"]!!
        val blackL = fittedL["x_lo"]!!
        val whiteL = fittedL["x_hi"]!! - 1.0

        val tC = fittedC["t"]!!
        val sC = fittedC["s"]!!
        val cC = fittedC["c"]!!
        val gC = fittedC["g"]!!
        val blackC = fittedC["x_lo"]!!
        val whiteC = fittedC["x_hi"]!! - 1.0

        val transferL = buildTransferLutWithEdges(cappedHistL, tL, sL, cL, gL, blackL, whiteL)
        val transferC = buildTransferLutWithEdges(cappedHistC, tC, sC, cC, gC, blackC, whiteC)

        // Build output CDF for zone weights
        // We need to apply the L transfer on CPU to get output histogram,
        // then compute CDF from that. But we can approximate by applying the
        // transfer LUT to the L histogram bins directly.
        // Actually, we need the CDF of the *output* L values. Let's apply
        // the transfer to each L value to get output L, histogram that,
        // then compute CDF.
        val lOut = FloatArray(pixelCount)
        val spanL = (1.0 + whiteL - blackL).toFloat()
        for (i in 0 until pixelCount) {
            val lIn = L[i].coerceIn(0f, 1f)
            val lMapped = interpUniform(lIn, transferL, NUM_BINS)
            lOut[i] = (blackL.toFloat() + lMapped * spanL).coerceIn(0f, 1f)
        }

        val outHistL = computeHistogram(lOut)
        val outCdf = DoubleArray(NUM_BINS)
        var cdfCumSum = 0.0
        for (i in 0 until NUM_BINS) {
            cdfCumSum += outHistL[i]
            outCdf[i] = cdfCumSum
        }
        val cdfTotal = outCdf[NUM_BINS - 1]
        if (cdfTotal > 0) {
            for (i in 0 until NUM_BINS) outCdf[i] /= cdfTotal
        }
        val cdfValuesFloat = FloatArray(NUM_BINS) { outCdf[it].toFloat() }
        mark("Build transfer+CDF", tStep)

        // Steps 6-12 (GPU pass 2)
        tStep = System.nanoTime()
        val chromaParams = floatArrayOf(0f, 0f, 0f, 0f, 0f, 0f)  // No chroma offset by default
        val outPixels = gpuPipeline.processPass2(
            transferL, transferC, cdfValuesFloat,
            blackL.toFloat(), whiteL.toFloat(),
            blackC.toFloat(), whiteC.toFloat(),
            chromaParams, pixelCount
        )
        mark("Steps 6-12 GPU pass2", tStep)

        // Step 13: createBitmap + setPixels  [I/O]
        tStep = System.nanoTime()
        val outBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        outBitmap.setPixels(outPixels, 0, width, 0, 0, width, height)
        mark("Step 13 setPixels", tStep, isIO = true)

        timings["Total"] = ms(t0); Log.i(TAG, "[GPU] Total pipeline: ${timings["Total"]}ms")

        return ProcessingResult(outBitmap, fittedL, fittedC, timings, cappedHistL, cappedHistC, steps)
    }

    // ---------------------------------------------------------------
    // Staged pipeline: pass 1 only (called once per image load)
    // ---------------------------------------------------------------

    /**
     * Run only GPU pass 1 + histograms + auto-fit.
     * Called once when a new image is loaded. Results are cached in PipelineState.
     */
    fun processPass1Only(
        input: Bitmap,
        gamutLut: FloatArray,
        gpuPipeline: GpuPipeline
    ): PipelineState {
        val state = PipelineState()

        val scaled = downscaleIfNeeded(input)
        state.originalBitmap = scaled
        state.width = scaled.width
        state.height = scaled.height
        state.pixelCount = scaled.width * scaled.height

        // Extract pixels
        val pixels = IntArray(state.pixelCount)
        scaled.getPixels(pixels, 0, state.width, 0, 0, state.width, state.height)

        // GPU pass 1: sRGB EOTF → OKLab → relative chroma
        val pass1 = gpuPipeline.processPass1(pixels, state.pixelCount)
        val L = pass1.L
        val cRel = pass1.cRel

        // Clamp L to [0,1] for histogram
        for (i in L.indices) L[i] = L[i].coerceIn(0f, 1f)

        state.pass1L = L
        state.pass1CRel = cRel

        // Raw histograms
        state.rawHistL = computeHistogram(L)
        state.rawHistC = computeHistogram(cRel)

        // Auto-fit both channels (cap = max, i.e. strength=1.0)
        val capL = state.rawHistL!!.maxOrNull() ?: 0.0
        val cappedHistL = capHistogram(state.rawHistL!!, capL)
        state.fittedDefaultsL = FitParams.fitInitialParams(cappedHistL)

        val capC = state.rawHistC!!.maxOrNull() ?: 0.0
        val cappedHistC = capHistogram(state.rawHistC!!, capC)
        state.fittedDefaultsC = FitParams.fitInitialParams(cappedHistC)

        return state
    }

    // ---------------------------------------------------------------
    // Staged pipeline: process from params (called on every slider change)
    // ---------------------------------------------------------------

    /**
     * Run the CPU histogram work + GPU pass 2 using cached pass 1 results and user params.
     * Called on every parameter change (slider drag).
     */
    fun processFromParams(
        state: PipelineState,
        params: PipelineParams,
        gamutLut: FloatArray,
        gpuPipeline: GpuPipeline
    ): Bitmap {
        val L = state.pass1L!!
        val pixelCount = state.pixelCount
        val width = state.width
        val height = state.height

        // --- L channel ---
        val capL = params.lightStrength * (state.rawHistL!!.maxOrNull() ?: 0.0)
        val cappedHistL = capHistogram(state.rawHistL!!, capL)
        val blackL = params.lightBlacks.toDouble()
        val whiteL = params.lightWhites.toDouble()

        val transferL = buildTransferLutWithEdges(
            cappedHistL,
            params.lightShadows.toDouble(),
            params.lightHighlights.toDouble(),
            params.lightMidtoneBalance.toDouble(),
            params.lightMidtoneContrast.toDouble(),
            blackL, whiteL
        )

        // --- C channel ---
        val capC = params.colorStrength * (state.rawHistC!!.maxOrNull() ?: 0.0)
        val cappedHistC = capHistogram(state.rawHistC!!, capC)

        val transferC = buildTransferLutWithEdges(
            cappedHistC,
            params.colorMutedColors.toDouble(),
            params.colorVividColors.toDouble(),
            params.colorSaturationBalance.toDouble(),
            params.colorVibrancy.toDouble(),
            params.colorBlacks.toDouble(),
            params.colorWhites.toDouble()
        )

        // --- Build output CDF for zone weights ---
        val spanL = (1.0 + whiteL - blackL).toFloat()
        val lOut = FloatArray(pixelCount)
        for (i in 0 until pixelCount) {
            val lIn = L[i].coerceIn(0f, 1f)
            val lMapped = interpUniform(lIn, transferL, NUM_BINS)
            lOut[i] = (blackL.toFloat() + lMapped * spanL).coerceIn(0f, 1f)
        }

        val outHistL = computeHistogram(lOut)
        val outCdf = DoubleArray(NUM_BINS)
        var cdfCumSum = 0.0
        for (i in 0 until NUM_BINS) {
            cdfCumSum += outHistL[i]
            outCdf[i] = cdfCumSum
        }
        val cdfTotal = outCdf[NUM_BINS - 1]
        if (cdfTotal > 0) {
            for (i in 0 until NUM_BINS) outCdf[i] /= cdfTotal
        }
        val cdfValuesFloat = FloatArray(NUM_BINS) { outCdf[it].toFloat() }

        // --- GPU pass 2 ---
        val chromaParams = floatArrayOf(
            params.shadowTintAngle, params.shadowTintStrength,
            params.midtoneTintAngle, params.midtoneTintStrength,
            params.highlightTintAngle, params.highlightTintStrength
        )
        val outPixels = gpuPipeline.processPass2(
            transferL, transferC, cdfValuesFloat,
            blackL.toFloat(), whiteL.toFloat(),
            params.colorBlacks, params.colorWhites,
            chromaParams, pixelCount
        )

        val outBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        outBitmap.setPixels(outPixels, 0, width, 0, 0, width, height)
        return outBitmap
    }

    // ---------------------------------------------------------------
    // Full-resolution tiled export
    // ---------------------------------------------------------------

    /**
     * Process the full-resolution image using tiled GPU pass1+pass2.
     * Transfer LUTs and CDF are computed from the preview histograms
     * (statistically equivalent to full-res histograms).
     *
     * For each tile: extract pixels → pass1 (OKLab SSBOs) → pass2 (transfer+output) → write to output bitmap.
     *
     * Must be called on the GPU thread.
     * After calling this, the GPU SSBOs contain the last tile's data.
     * Caller should re-run pass1 on preview pixels to restore state.
     */
    fun processFullResolution(
        originalBitmap: Bitmap,
        state: PipelineState,
        params: PipelineParams,
        gamutLut: FloatArray,
        gpuPipeline: GpuPipeline,
        tileSize: Int = 1024,
        onProgress: (Float) -> Unit = {}
    ): Bitmap {
        val width = originalBitmap.width
        val height = originalBitmap.height
        Log.i(TAG, "[FullRes] Image: ${width}x${height}, tile: $tileSize")

        // --- Compute transfer LUTs and CDF (same as processFromParams) ---
        val L = state.pass1L!!
        val previewPixelCount = state.pixelCount

        // L channel
        val capL = params.lightStrength * (state.rawHistL!!.maxOrNull() ?: 0.0)
        val cappedHistL = capHistogram(state.rawHistL!!, capL)
        val blackL = params.lightBlacks.toDouble()
        val whiteL = params.lightWhites.toDouble()
        val transferL = buildTransferLutWithEdges(
            cappedHistL,
            params.lightShadows.toDouble(),
            params.lightHighlights.toDouble(),
            params.lightMidtoneBalance.toDouble(),
            params.lightMidtoneContrast.toDouble(),
            blackL, whiteL
        )

        // C channel
        val capC = params.colorStrength * (state.rawHistC!!.maxOrNull() ?: 0.0)
        val cappedHistC = capHistogram(state.rawHistC!!, capC)
        val transferC = buildTransferLutWithEdges(
            cappedHistC,
            params.colorMutedColors.toDouble(),
            params.colorVividColors.toDouble(),
            params.colorSaturationBalance.toDouble(),
            params.colorVibrancy.toDouble(),
            params.colorBlacks.toDouble(),
            params.colorWhites.toDouble()
        )

        // Output CDF for zone weights (computed from preview L data)
        val spanL = (1.0 + whiteL - blackL).toFloat()
        val lOut = FloatArray(previewPixelCount)
        for (i in 0 until previewPixelCount) {
            val lIn = L[i].coerceIn(0f, 1f)
            val lMapped = interpUniform(lIn, transferL, NUM_BINS)
            lOut[i] = (blackL.toFloat() + lMapped * spanL).coerceIn(0f, 1f)
        }

        val outHistL = computeHistogram(lOut)
        val outCdf = DoubleArray(NUM_BINS)
        var cdfCumSum = 0.0
        for (i in 0 until NUM_BINS) {
            cdfCumSum += outHistL[i]
            outCdf[i] = cdfCumSum
        }
        val cdfTotal = outCdf[NUM_BINS - 1]
        if (cdfTotal > 0) {
            for (i in 0 until NUM_BINS) outCdf[i] /= cdfTotal
        }
        val cdfValuesFloat = FloatArray(NUM_BINS) { outCdf[it].toFloat() }

        val chromaParams = floatArrayOf(
            params.shadowTintAngle, params.shadowTintStrength,
            params.midtoneTintAngle, params.midtoneTintStrength,
            params.highlightTintAngle, params.highlightTintStrength
        )
        val blackC = params.colorBlacks
        val whiteC = params.colorWhites

        // --- Tiled processing ---
        val outBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val tilesX = (width + tileSize - 1) / tileSize
        val tilesY = (height + tileSize - 1) / tileSize
        val totalTiles = tilesX * tilesY
        var completedTiles = 0

        Log.i(TAG, "[FullRes] Processing $totalTiles tiles (${tilesX}x${tilesY})")

        for (ty in 0 until tilesY) {
            for (tx in 0 until tilesX) {
                val x0 = tx * tileSize
                val y0 = ty * tileSize
                val tileW = minOf(tileSize, width - x0)
                val tileH = minOf(tileSize, height - y0)
                val tilePixelCount = tileW * tileH

                // Extract tile pixels from full-res bitmap
                val tilePixels = IntArray(tilePixelCount)
                originalBitmap.getPixels(tilePixels, 0, tileW, x0, y0, tileW, tileH)

                // Pass 1: populate OKLab SSBOs (no readback needed)
                gpuPipeline.processPass1NoReadback(tilePixels, tilePixelCount)

                // Pass 2: apply transfer LUTs, output ARGB
                val outPixels = gpuPipeline.processPass2(
                    transferL, transferC, cdfValuesFloat,
                    blackL.toFloat(), whiteL.toFloat(),
                    blackC, whiteC,
                    chromaParams, tilePixelCount
                )

                // Write tile to output bitmap
                outBitmap.setPixels(outPixels, 0, tileW, x0, y0, tileW, tileH)

                completedTiles++
                onProgress(completedTiles.toFloat() / totalTiles)
            }
        }

        Log.i(TAG, "[FullRes] Done: ${width}x${height}")
        return outBitmap
    }

    // ---------------------------------------------------------------
    // Hi-res crop render (for zoomed-in viewport overlay)
    // ---------------------------------------------------------------

    /**
     * Process a crop region from the original full-resolution bitmap.
     * Uses the same transfer LUTs derived from preview histograms (same approach as tiled export).
     *
     * Must be called on the GPU thread.
     * After calling this, the GPU SSBOs contain the crop's data.
     * Caller should re-run pass1 on preview pixels to restore state.
     *
     * @param originalBitmap The full-resolution source bitmap
     * @param cropX Left edge in original image pixels
     * @param cropY Top edge in original image pixels
     * @param cropW Width in original image pixels
     * @param cropH Height in original image pixels
     * @param state Pipeline state with cached preview histograms
     * @param params Current pipeline parameters
     * @param gamutLut Gamut LUT
     * @param gpuPipeline GPU pipeline instance
     * @return Processed crop bitmap
     */
    fun processHiResCrop(
        originalBitmap: Bitmap,
        cropX: Int,
        cropY: Int,
        cropW: Int,
        cropH: Int,
        state: PipelineState,
        params: PipelineParams,
        gamutLut: FloatArray,
        gpuPipeline: GpuPipeline
    ): Bitmap {
        val pixelCount = cropW * cropH
        Log.i(TAG, "[HiResCrop] Crop: ${cropW}x${cropH} at ($cropX,$cropY), $pixelCount px")

        // --- Compute transfer LUTs and CDF from preview histograms (same as processFromParams) ---
        val L = state.pass1L!!
        val previewPixelCount = state.pixelCount

        // L channel
        val capL = params.lightStrength * (state.rawHistL!!.maxOrNull() ?: 0.0)
        val cappedHistL = capHistogram(state.rawHistL!!, capL)
        val blackL = params.lightBlacks.toDouble()
        val whiteL = params.lightWhites.toDouble()
        val transferL = buildTransferLutWithEdges(
            cappedHistL,
            params.lightShadows.toDouble(),
            params.lightHighlights.toDouble(),
            params.lightMidtoneBalance.toDouble(),
            params.lightMidtoneContrast.toDouble(),
            blackL, whiteL
        )

        // C channel
        val capC = params.colorStrength * (state.rawHistC!!.maxOrNull() ?: 0.0)
        val cappedHistC = capHistogram(state.rawHistC!!, capC)
        val transferC = buildTransferLutWithEdges(
            cappedHistC,
            params.colorMutedColors.toDouble(),
            params.colorVividColors.toDouble(),
            params.colorSaturationBalance.toDouble(),
            params.colorVibrancy.toDouble(),
            params.colorBlacks.toDouble(),
            params.colorWhites.toDouble()
        )

        // Output CDF for zone weights (from preview L data)
        val spanL = (1.0 + whiteL - blackL).toFloat()
        val lOut = FloatArray(previewPixelCount)
        for (i in 0 until previewPixelCount) {
            val lIn = L[i].coerceIn(0f, 1f)
            val lMapped = interpUniform(lIn, transferL, NUM_BINS)
            lOut[i] = (blackL.toFloat() + lMapped * spanL).coerceIn(0f, 1f)
        }

        val outHistL = computeHistogram(lOut)
        val outCdf = DoubleArray(NUM_BINS)
        var cdfCumSum = 0.0
        for (i in 0 until NUM_BINS) {
            cdfCumSum += outHistL[i]
            outCdf[i] = cdfCumSum
        }
        val cdfTotal = outCdf[NUM_BINS - 1]
        if (cdfTotal > 0) {
            for (i in 0 until NUM_BINS) outCdf[i] /= cdfTotal
        }
        val cdfValuesFloat = FloatArray(NUM_BINS) { outCdf[it].toFloat() }

        val chromaParams = floatArrayOf(
            params.shadowTintAngle, params.shadowTintStrength,
            params.midtoneTintAngle, params.midtoneTintStrength,
            params.highlightTintAngle, params.highlightTintStrength
        )

        // --- Extract crop pixels from original bitmap ---
        val cropPixels = IntArray(pixelCount)
        originalBitmap.getPixels(cropPixels, 0, cropW, cropX, cropY, cropW, cropH)

        // --- GPU pass 1 (no readback) + pass 2 ---
        gpuPipeline.processPass1NoReadback(cropPixels, pixelCount)

        val outPixels = gpuPipeline.processPass2(
            transferL, transferC, cdfValuesFloat,
            blackL.toFloat(), whiteL.toFloat(),
            params.colorBlacks, params.colorWhites,
            chromaParams, pixelCount
        )

        val outBitmap = Bitmap.createBitmap(cropW, cropH, Bitmap.Config.ARGB_8888)
        outBitmap.setPixels(outPixels, 0, cropW, 0, 0, cropW, cropH)

        Log.i(TAG, "[HiResCrop] Done: ${cropW}x${cropH}")
        return outBitmap
    }

    // ---------------------------------------------------------------
    // Fit to input: re-fit params for a channel using current strength
    // ---------------------------------------------------------------

    /**
     * Re-cap the histogram with current strength, run Adam fit, and return
     * a new PipelineParams with fitted values for the specified channel.
     * The other channel's params are kept unchanged.
     *
     * @param channel "light" or "color"
     */
    fun fitToInput(
        state: PipelineState,
        params: PipelineParams,
        channel: String
    ): PipelineParams {
        return when (channel) {
            "light" -> {
                val cap = params.lightStrength * (state.rawHistL!!.maxOrNull()?.toFloat() ?: 0f)
                val cappedHist = capHistogram(state.rawHistL!!, cap.toDouble())
                val fitted = FitParams.fitInitialParams(cappedHist)
                params.copy(
                    lightShadows = fitted["t"]!!.toFloat(),
                    lightHighlights = fitted["s"]!!.toFloat(),
                    lightMidtoneBalance = fitted["c"]!!.toFloat(),
                    lightMidtoneContrast = fitted["g"]!!.toFloat(),
                    lightBlacks = fitted["x_lo"]!!.toFloat(),
                    lightWhites = (fitted["x_hi"]!! - 1.0).toFloat()
                )
            }
            "color" -> {
                val cap = params.colorStrength * (state.rawHistC!!.maxOrNull()?.toFloat() ?: 0f)
                val cappedHist = capHistogram(state.rawHistC!!, cap.toDouble())
                val fitted = FitParams.fitInitialParams(cappedHist)
                params.copy(
                    colorMutedColors = fitted["t"]!!.toFloat(),
                    colorVividColors = fitted["s"]!!.toFloat(),
                    colorSaturationBalance = fitted["c"]!!.toFloat(),
                    colorVibrancy = fitted["g"]!!.toFloat(),
                    colorBlacks = fitted["x_lo"]!!.toFloat(),
                    colorWhites = (fitted["x_hi"]!! - 1.0).toFloat()
                )
            }
            else -> throw IllegalArgumentException("channel must be 'light' or 'color'")
        }
    }

}
