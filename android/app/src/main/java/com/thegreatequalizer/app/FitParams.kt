package com.thegreatequalizer.app

import kotlin.math.*

/**
 * Parametric CDF fitting with Adam optimizer.
 * Ported from Python prototype (fit_params.py).
 *
 * CRITICAL: Angle-to-exponent conversions:
 *   t (toe) and g (gamma) use tan(angle)
 *   s (shoulder) uses cot(angle) = 1/tan(angle)
 *   pi/4 is the identity for all three (tan(pi/4)=1, cot(pi/4)=1)
 */
object FitParams {

    private val PI_4 = PI / 4.0

    /** Safety cap on optimizer steps. */
    const val MAX_STEPS = 2000

    /** Loss plateau termination: stop when relative improvement over last PLATEAU_WINDOW steps < PLATEAU_EPS. */
    const val PLATEAU_WINDOW = 50
    const val PLATEAU_EPS = 1e-4

    /** L2 regularization strength toward identity (pi/4) for t, s, g params. */
    const val REG_LAMBDA = 1e-3

    val PARAM_NAMES = arrayOf("t", "s", "c", "g")

    val PARAM_BOUNDS = mapOf(
        "t" to Pair(
            ParameterRanges.TOE.min.toDouble(),
            ParameterRanges.TOE.max.toDouble()
        ),
        "s" to Pair(
            ParameterRanges.SHOULDER.min.toDouble(),
            ParameterRanges.SHOULDER.max.toDouble()
        ),
        "c" to Pair(
            ParameterRanges.BALANCE.min.toDouble(),
            ParameterRanges.BALANCE.max.toDouble()
        ),
        "g" to Pair(
            ParameterRanges.GAMMA.min.toDouble(),
            ParameterRanges.GAMMA.max.toDouble()
        )
    )

    val PARAM_DEFAULTS = mapOf(
        "t" to ParameterRanges.TOE.neutral.toDouble(),
        "s" to ParameterRanges.SHOULDER.neutral.toDouble(),
        "c" to ParameterRanges.BALANCE.neutral.toDouble(),
        "g" to ParameterRanges.GAMMA.neutral.toDouble()
    )

    const val TRIM_EPS = 5e-3

    /** Toe angle (radians) → power exponent via tan(θ). */
    fun toeAngleToExp(angleRad: Double): Double = tan(angleRad)

    /** Shoulder angle (radians) → power exponent via cot(θ) = 1/tan(θ). */
    fun shoulderAngleToExp(angleRad: Double): Double = 1.0 / tan(angleRad)

    /** Gamma angle (radians) → power exponent via tan(θ). */
    fun gammaAngleToExp(angleRad: Double): Double = tan(angleRad)

    /**
     * Parametric target CDF — maps [0, 1] → [0, 1].
     * t, s, g are angles in radians; π/4 is identity.
     */
    fun computeTargetCdf(x: DoubleArray, t: Double, s: Double, c: Double, g: Double): DoubleArray {
        val tExp = toeAngleToExp(t)
        val sExp = shoulderAngleToExp(s)
        val gExp = gammaAngleToExp(g)
        val alpha = sExp * c / (sExp * c + tExp * (1.0 - c))
        val beta = 1.0 - alpha

        return DoubleArray(x.size) { i ->
            val xi = x[i]
            val h = if (xi <= c) {
                alpha * (xi / c).pow(tExp)
            } else {
                1.0 - beta * ((1.0 - xi) / (1.0 - c)).pow(sExp)
            }
            h.pow(gExp).coerceIn(0.0, 1.0)
        }
    }

    private fun pack(params: Map<String, Double>): DoubleArray =
        DoubleArray(PARAM_NAMES.size) { params[PARAM_NAMES[it]]!! }

    private fun unpack(vec: DoubleArray): Map<String, Double> =
        PARAM_NAMES.mapIndexed { i, name -> name to vec[i] }.toMap()

    private fun clampVec(vec: DoubleArray): DoubleArray {
        return DoubleArray(PARAM_NAMES.size) { i ->
            val (lo, hi) = PARAM_BOUNDS[PARAM_NAMES[i]]!!
            vec[i].coerceIn(lo, hi)
        }
    }

    private fun mse(x: DoubleArray, targetCdf: DoubleArray, params: DoubleArray): Double {
        val p = unpack(params)
        val pred = computeTargetCdf(x, p["t"]!!, p["s"]!!, p["c"]!!, p["g"]!!)
        var sum = 0.0
        for (i in pred.indices) {
            val diff = pred[i] - targetCdf[i]
            sum += diff * diff
        }
        return sum / pred.size
    }

    /** Safe log: clamps argument to avoid ln(0). */
    private fun safeLn(v: Double): Double = ln(v.coerceAtLeast(1e-30))

    /**
     * Analytical gradient of MSE loss + L2 regularization w.r.t. the 4 angle parameters [t, s, c, g].
     *
     * dLoss/dparam = mean(2 * (pred_i - target_i) * dpred_i/dparam)
     * Plus L2 regularization gradient for t, s, g toward PI_4.
     */
    private fun analyticalGrad(
        x: DoubleArray, targetCdf: DoubleArray, params: DoubleArray
    ): DoubleArray {
        val t = params[0]
        val s = params[1]
        val c = params[2]
        val g = params[3]

        val tExp = toeAngleToExp(t)       // tan(t)
        val sExp = shoulderAngleToExp(s)  // 1/tan(s) = cot(s)
        val gExp = gammaAngleToExp(g)     // tan(g)

        val D = sExp * c + tExp * (1.0 - c)
        val alpha = sExp * c / D
        val beta = 1.0 - alpha

        // Derivatives of alpha w.r.t. exponents
        // dalpha/dtExp = -alpha * (1-c) / D = -sExp * c * (1-c) / D^2
        val dAlpha_dtExp = -alpha * (1.0 - c) / D
        // dalpha/dsExp = alpha * tExp * (1-c) / (sExp * D)
        val dAlpha_dsExp = alpha * tExp * (1.0 - c) / (sExp * D)
        // dalpha/dc = sExp * tExp / D^2
        val dAlpha_dc = sExp * tExp / (D * D)

        // dbeta/dc = -dalpha/dc (used in shoulder branch for dc)
        val dBeta_dc = -dAlpha_dc

        // Angle-to-exponent derivatives
        val cosT = cos(t)
        val dtExp_dt = 1.0 / (cosT * cosT)  // sec²(t)
        val sinS = sin(s)
        val dsExp_ds = -1.0 / (sinS * sinS)  // d(cot(s))/ds = -csc²(s)
        val cosG = cos(g)
        val dgExp_dg = 1.0 / (cosG * cosG)  // sec²(g)

        val n = x.size
        var gradT = 0.0
        var gradS = 0.0
        var gradC = 0.0
        var gradG = 0.0

        for (i in x.indices) {
            val xi = x[i]

            // Compute h and pred
            val h: Double
            var dh_dtExp: Double
            var dh_dsExp: Double
            var dh_dc: Double

            if (xi <= c) {
                val u = (xi / c).coerceAtLeast(1e-30)
                val uPow = u.pow(tExp)
                val lnU = safeLn(u)

                h = alpha * uPow

                // dh/dtExp = dalpha/dtExp * u^tExp + alpha * u^tExp * ln(u)
                dh_dtExp = dAlpha_dtExp * uPow + alpha * uPow * lnU

                // dh/dsExp = dalpha/dsExp * u^tExp
                dh_dsExp = dAlpha_dsExp * uPow

                // dh/dc = dalpha/dc * u^tExp + alpha * d(u^tExp)/dc
                // u = x/c, du/dc = -x/c^2, d(u^tExp)/dc = tExp * u^(tExp-1) * (-x/c^2)
                //   = tExp * (x/c)^(tExp-1) * (-x/c^2) = -tExp * x^tExp / c^(tExp+1)
                //   = -tExp * u^tExp / c
                dh_dc = dAlpha_dc * uPow + alpha * (-tExp * uPow / c)
            } else {
                val v = ((1.0 - xi) / (1.0 - c)).coerceAtLeast(1e-30)
                val vPow = v.pow(sExp)
                val lnV = safeLn(v)

                h = 1.0 - beta * vPow

                // dh/dtExp = -dbeta/dtExp * v^sExp = dalpha/dtExp * v^sExp
                // (since dbeta/dtExp = -dalpha/dtExp, so -dbeta/dtExp = dalpha/dtExp)
                dh_dtExp = dAlpha_dtExp * vPow

                // dh/dsExp = -dbeta/dsExp * v^sExp - beta * v^sExp * ln(v)
                dh_dsExp = dAlpha_dsExp * vPow - beta * vPow * lnV

                // dh/dc: h = 1 - beta * v^sExp, v = (1-x)/(1-c)
                // dv/dc = (1-x)/(1-c)^2 = v/(1-c)
                // d(v^sExp)/dc = sExp * v^(sExp-1) * v/(1-c) = sExp * v^sExp / (1-c)
                // dh/dc = -dBeta_dc * v^sExp - beta * sExp * v^sExp / (1-c)
                dh_dc = -dBeta_dc * vPow - beta * sExp * vPow / (1.0 - c)
            }

            val hClamped = h.coerceAtLeast(1e-30)
            val pred = hClamped.pow(gExp).coerceIn(0.0, 1.0)
            val residual = pred - targetCdf[i]

            // dpred/dh = gExp * h^(gExp-1)
            val dpred_dh = gExp * hClamped.pow(gExp - 1.0)

            // dpred/dgExp = h^gExp * ln(h) = pred * ln(h)
            val dpred_dgExp = pred * safeLn(hClamped)

            // Chain rule: dpred/dt = dpred/dh * dh/dtExp * dtExp/dt
            val dpred_dt = dpred_dh * dh_dtExp * dtExp_dt
            val dpred_ds = dpred_dh * dh_dsExp * dsExp_ds
            val dpred_dc = dpred_dh * dh_dc
            val dpred_dg = dpred_dgExp * dgExp_dg

            // dLoss/dparam contribution: 2 * residual * dpred/dparam
            val twoRes = 2.0 * residual
            gradT += twoRes * dpred_dt
            gradS += twoRes * dpred_ds
            gradC += twoRes * dpred_dc
            gradG += twoRes * dpred_dg
        }

        // Add L2 regularization gradients toward identity (PI_4) for t, s, g (NOT c)
        gradT += n * 2.0 * REG_LAMBDA * (t - PI_4)
        gradS += n * 2.0 * REG_LAMBDA * (s - PI_4)
        gradG += n * 2.0 * REG_LAMBDA * (g - PI_4)

        return doubleArrayOf(gradT / n, gradS / n, gradC / n, gradG / n)
    }

    /**
     * Compute MSE loss + L2 regularization loss.
     */
    private fun totalLoss(x: DoubleArray, targetCdf: DoubleArray, params: DoubleArray): Double {
        val mseLoss = mse(x, targetCdf, params)
        val t = params[0]; val s = params[1]; val g = params[3]
        val regLoss = REG_LAMBDA * ((t - PI_4).let { it * it } + (s - PI_4).let { it * it } + (g - PI_4).let { it * it })
        return mseLoss + regLoss
    }

    /**
     * Fit params for one channel's input CDF using Adam optimizer with loss-plateau termination.
     * Returns a Pair of (fitted params map, number of steps taken).
     * Stops when relative loss improvement over the last PLATEAU_WINDOW steps < PLATEAU_EPS,
     * or after MAX_STEPS.
     */
    private fun fitSingleChannel(
        inputCdf: DoubleArray,
        numBins: Int,
        lr: Double = 0.01,
        beta1: Double = 0.9,
        beta2: Double = 0.999,
        adamEps: Double = 1e-8
    ): Pair<Map<String, Double>, Int> {
        val x = DoubleArray(numBins) { it.toDouble() / (numBins - 1).toDouble() }
        var params = pack(PARAM_DEFAULTS)
        val mArr = DoubleArray(params.size)
        val vArr = DoubleArray(params.size)
        var actualSteps = 0

        // Ring buffer to track loss history for plateau detection
        val lossHistory = DoubleArray(PLATEAU_WINDOW + 1) { Double.MAX_VALUE }
        var lossIdx = 0

        for (step in 1..MAX_STEPS) {
            val g = analyticalGrad(x, inputCdf, params)

            for (i in params.indices) {
                mArr[i] = beta1 * mArr[i] + (1.0 - beta1) * g[i]
                vArr[i] = beta2 * vArr[i] + (1.0 - beta2) * g[i] * g[i]
                val mHat = mArr[i] / (1.0 - beta1.pow(step.toDouble()))
                val vHat = vArr[i] / (1.0 - beta2.pow(step.toDouble()))
                params[i] -= lr * mHat / (sqrt(vHat) + adamEps)
            }
            params = clampVec(params)
            actualSteps = step

            // Track loss for plateau detection
            val currentLoss = mse(x, inputCdf, params)
            lossHistory[lossIdx % (PLATEAU_WINDOW + 1)] = currentLoss
            lossIdx++

            // Check plateau after enough steps
            if (step >= PLATEAU_WINDOW) {
                val oldLoss = lossHistory[(lossIdx - PLATEAU_WINDOW - 1 + (PLATEAU_WINDOW + 1)) % (PLATEAU_WINDOW + 1)]
                if (oldLoss > 0.0) {
                    val relImprovement = (oldLoss - currentLoss) / oldLoss
                    if (relImprovement < PLATEAU_EPS) {
                        break
                    }
                }
            }
        }

        return Pair(unpack(params), actualSteps)
    }

    /**
     * Trim CDF: extract the rising portion, rescale to [0, 1].
     * Returns Triple(trimmedCdf, xLo, xHi).
     */
    private fun trimCdf(
        cdf: DoubleArray, numBins: Int, eps: Double = TRIM_EPS
    ): Triple<DoubleArray, Double, Double> {
        val bins = DoubleArray(numBins) { it.toDouble() / (numBins - 1).toDouble() }

        // Find first bin above eps
        var first = -1
        for (i in cdf.indices) {
            if (cdf[i] > eps) { first = i; break }
        }
        if (first < 0) return Triple(cdf, 0.0, 1.0)

        // Find last bin below 1 - eps
        var last = -1
        for (i in cdf.indices.reversed()) {
            if (cdf[i] < 1.0 - eps) { last = i; break }
        }
        if (last < 0) return Triple(cdf, 0.0, 1.0)

        val xLo = bins[first]
        val xHi = bins[last]

        val trimmed = cdf.sliceArray(first..last)
        val lo = trimmed[0]
        val hi = trimmed[trimmed.size - 1]
        if (hi > lo) {
            for (i in trimmed.indices) {
                trimmed[i] = (trimmed[i] - lo) / (hi - lo)
            }
        }
        return Triple(trimmed, xLo, xHi)
    }

    /**
     * Fit target-CDF shape parameters to one channel's capped histogram.
     * Returns a map with fitted t, s, c, g and trim boundaries x_lo, x_hi.
     */
    fun fitInitialParams(
        cappedHist: DoubleArray,
        lr: Double = 0.01
    ): Map<String, Double> {
        // Compute CDF
        val cdf = DoubleArray(cappedHist.size)
        var cumSum = 0.0
        for (i in cappedHist.indices) {
            cumSum += cappedHist[i]
            cdf[i] = cumSum
        }
        val total = cdf[cdf.size - 1]
        if (total > 0) {
            for (i in cdf.indices) cdf[i] /= total
        }

        val (trimmed, xLo, xHi) = trimCdf(cdf, cdf.size)
        val (fitted, steps) = fitSingleChannel(trimmed, trimmed.size, lr = lr)
        val result = fitted.toMutableMap()
        result["x_lo"] = xLo
        result["x_hi"] = xHi
        result["steps"] = steps.toDouble()
        return result
    }

    private fun Double.pow(exp: Double): Double = Math.pow(this, exp)
}
