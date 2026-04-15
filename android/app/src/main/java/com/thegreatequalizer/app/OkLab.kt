package com.thegreatequalizer.app

import kotlin.math.*

/**
 * OKLab color space conversions, gamut LUT, zone weights, chroma offsets.
 *
 * Ported from Python prototype (oklab.py).
 * All arrays use row-major float storage. Images are represented as flat FloatArrays
 * with interleaved channels (RGB order, not BGR — Android uses RGB).
 */
object OkLab {


    // M1: linear sRGB (R,G,B) → pre-nonlinearity LMS
    private val M1 = floatArrayOf(
        0.4122214708f, 0.5363325363f, 0.0514459929f,
        0.2119034982f, 0.6806995451f, 0.1073969566f,
        0.0883024619f, 0.2817188376f, 0.6299787005f
    )

    // M2: cube-root LMS → OKLab (L, a, b)
    private val M2 = floatArrayOf(
        0.2104542553f, 0.7936177850f, -0.0040720468f,
        1.9779984951f, -2.4285922050f, 0.4505937099f,
        0.0259040371f, 0.7827717662f, -0.8086757660f
    )

    // M1 inverse: LMS → linear sRGB
    private val M1_INV = floatArrayOf(
        +4.0767416621f, -3.3077115913f, +0.2309699292f,
        -1.2684380046f, +2.6097574011f, -0.3413193965f,
        -0.0041960863f, -0.7034186147f, +1.7076147010f
    )

    // M2 inverse: OKLab → cube-root LMS
    private val M2_INV = floatArrayOf(
        1.0f, +0.3963377774f, +0.2158037573f,
        1.0f, -0.1055613458f, -0.0638541728f,
        1.0f, -0.0894841775f, -1.2914855480f
    )

    /**
     * Float-friendly cube root. Uses Math.cbrt (double) but avoids extra wrapping.
     */
    private fun fcbrt(x: Float): Float = Math.cbrt(x.toDouble()).toFloat()

    /**
     * Convert linear RGB pixels to OKLab.
     * Input: linearRgb[pixelCount * 3] (R,G,B interleaved)
     * Output: Triple(L, a, b) each FloatArray[pixelCount]
     */
    fun linearRgbToOklab(linearRgb: FloatArray, pixelCount: Int): Triple<FloatArray, FloatArray, FloatArray> {
        val L = FloatArray(pixelCount)
        val aOut = FloatArray(pixelCount)
        val bOut = FloatArray(pixelCount)

        // Hoist matrix coefficients to locals for faster inner-loop access
        val m10 = M1[0]; val m11 = M1[1]; val m12 = M1[2]
        val m13 = M1[3]; val m14 = M1[4]; val m15 = M1[5]
        val m16 = M1[6]; val m17 = M1[7]; val m18 = M1[8]
        val m20 = M2[0]; val m21 = M2[1]; val m22 = M2[2]
        val m23 = M2[3]; val m24 = M2[4]; val m25 = M2[5]
        val m26 = M2[6]; val m27 = M2[7]; val m28 = M2[8]

        for (i in 0 until pixelCount) {
            val idx = i * 3
            val r = linearRgb[idx]
            val g = linearRgb[idx + 1]
            val b = linearRgb[idx + 2]

            // M1: RGB → LMS
            val l_ = m10 * r + m11 * g + m12 * b
            val m_ = m13 * r + m14 * g + m15 * b
            val s_ = m16 * r + m17 * g + m18 * b

            // Cube root
            val lG = fcbrt(l_)
            val mG = fcbrt(m_)
            val sG = fcbrt(s_)

            // M2: cube-root LMS → OKLab
            L[i] = m20 * lG + m21 * mG + m22 * sG
            aOut[i] = m23 * lG + m24 * mG + m25 * sG
            bOut[i] = m26 * lG + m27 * mG + m28 * sG
        }

        return Triple(L, aOut, bOut)
    }

    /**
     * Convert OKLab to linear RGB.
     * Returns: FloatArray[pixelCount * 3] (R,G,B interleaved)
     */
    fun oklabToLinearRgb(L: FloatArray, a: FloatArray, bOk: FloatArray, pixelCount: Int): FloatArray {
        val out = FloatArray(pixelCount * 3)

        for (i in 0 until pixelCount) {
            val li = L[i]
            val ai = a[i]
            val bi = bOk[i]

            // M2_INV: OKLab → cube-root LMS
            val lG = M2_INV[0] * li + M2_INV[1] * ai + M2_INV[2] * bi
            val mG = M2_INV[3] * li + M2_INV[4] * ai + M2_INV[5] * bi
            val sG = M2_INV[6] * li + M2_INV[7] * ai + M2_INV[8] * bi

            // Cube
            val l_ = lG * lG * lG
            val m_ = mG * mG * mG
            val s_ = sG * sG * sG

            // M1_INV: LMS → linear RGB
            val idx = i * 3
            out[idx] = M1_INV[0] * l_ + M1_INV[1] * m_ + M1_INV[2] * s_
            out[idx + 1] = M1_INV[3] * l_ + M1_INV[4] * m_ + M1_INV[5] * s_
            out[idx + 2] = M1_INV[6] * l_ + M1_INV[7] * m_ + M1_INV[8] * s_
        }

        return out
    }

    // Gamut LUT dimensions
    const val GAMUT_N_L = 256
    const val GAMUT_N_H = 360

    /**
     * Build max-chroma gamut LUT for sRGB at each (L, hue) via bisection.
     * Returns float array of shape [GAMUT_N_L * GAMUT_N_H] (row-major: index = iL * GAMUT_N_H + iH).
     */
    fun buildGamutLut(): FloatArray {
        val lut = FloatArray(GAMUT_N_L * GAMUT_N_H)
        val lo = FloatArray(GAMUT_N_L * GAMUT_N_H)
        val hi = FloatArray(GAMUT_N_L * GAMUT_N_H) { 0.5f }

        val lVals = FloatArray(GAMUT_N_L) { it.toFloat() / (GAMUT_N_L - 1).toFloat() }
        val hVals = FloatArray(GAMUT_N_H) { it.toFloat() / GAMUT_N_H.toFloat() * (2f * PI.toFloat()) }

        val cosH = FloatArray(GAMUT_N_H) { cos(hVals[it]) }
        val sinH = FloatArray(GAMUT_N_H) { sin(hVals[it]) }

        for (iter in 0 until 20) {
            for (iL in 0 until GAMUT_N_L) {
                val lVal = lVals[iL]
                for (iH in 0 until GAMUT_N_H) {
                    val idx = iL * GAMUT_N_H + iH
                    val mid = (lo[idx] + hi[idx]) * 0.5f
                    val labL = lVal
                    val labA = mid * cosH[iH]
                    val labB = mid * sinH[iH]

                    // M2_INV → cube → M1_INV
                    val lG = M2_INV[0] * labL + M2_INV[1] * labA + M2_INV[2] * labB
                    val mG = M2_INV[3] * labL + M2_INV[4] * labA + M2_INV[5] * labB
                    val sG = M2_INV[6] * labL + M2_INV[7] * labA + M2_INV[8] * labB

                    val l_ = lG * lG * lG
                    val m_ = mG * mG * mG
                    val s_ = sG * sG * sG

                    val r = M1_INV[0] * l_ + M1_INV[1] * m_ + M1_INV[2] * s_
                    val g = M1_INV[3] * l_ + M1_INV[4] * m_ + M1_INV[5] * s_
                    val b = M1_INV[6] * l_ + M1_INV[7] * m_ + M1_INV[8] * s_

                    val inGamut = r >= -1e-6f && r <= 1.0f + 1e-6f &&
                            g >= -1e-6f && g <= 1.0f + 1e-6f &&
                            b >= -1e-6f && b <= 1.0f + 1e-6f

                    if (inGamut) {
                        lo[idx] = mid
                    } else {
                        hi[idx] = mid
                    }
                }
            }
        }

        // lo now contains max chroma
        lo.copyInto(lut)
        return lut
    }

    /**
     * Bilinear interpolation of max chroma from the gamut LUT.
     */
    /** Precomputed constant for hue → LUT index conversion */
    private val H_TO_IDX = GAMUT_N_H.toFloat() / (2f * PI.toFloat())

    fun lookupGamutMax(L: Float, h: Float, gamutLut: FloatArray): Float {
        val liF = L.coerceIn(0f, 1f) * (GAMUT_N_L - 1).toFloat()
        val hiF = h * H_TO_IDX

        val li0 = liF.toInt().coerceIn(0, GAMUT_N_L - 1)
        val li1 = (li0 + 1).coerceAtMost(GAMUT_N_L - 1)
        val fl = liF - li0.toFloat()

        val hiFloor = floor(hiF)
        val hi0 = (hiFloor.toInt() % GAMUT_N_H + GAMUT_N_H) % GAMUT_N_H
        val hi1 = (hi0 + 1) % GAMUT_N_H
        val fh = hiF - hiFloor

        val oneMinusFl = 1f - fl
        val oneMinusFh = 1f - fh
        val row0 = li0 * GAMUT_N_H
        val row1 = li1 * GAMUT_N_H
        return gamutLut[row0 + hi0] * oneMinusFl * oneMinusFh +
                gamutLut[row0 + hi1] * oneMinusFl * fh +
                gamutLut[row1 + hi0] * fl * oneMinusFh +
                gamutLut[row1 + hi1] * fl * fh
    }

    /**
     * Compute relative chroma and hue for each pixel.
     * Returns Pair(cRel, hue) each FloatArray[pixelCount]
     */
    fun relativeChroma(
        L: FloatArray, a: FloatArray, bOk: FloatArray,
        gamutLut: FloatArray, pixelCount: Int
    ): Pair<FloatArray, FloatArray> {
        val cRel = FloatArray(pixelCount)
        val hue = FloatArray(pixelCount)
        val twoPiF = (2.0 * PI).toFloat()

        for (i in 0 until pixelCount) {
            val ai = a[i]
            val bi = bOk[i]
            val c = sqrt(ai * ai + bi * bi)
            var h = atan2(bi, ai)
            if (h < 0f) h += twoPiF
            hue[i] = h

            val cMax = lookupGamutMax(L[i], h, gamutLut)
            cRel[i] = if (cMax > 1e-10f) {
                (c / maxOf(cMax, 1e-10f)).coerceIn(0f, 1f)
            } else 0f
        }

        return Pair(cRel, hue)
    }

    /**
     * Reconstruct OKLab (a, b) from relative chroma, hue, and lightness.
     */
    fun reconstructAb(
        cRel: FloatArray, hue: FloatArray, L: FloatArray,
        gamutLut: FloatArray, pixelCount: Int
    ): Pair<FloatArray, FloatArray> {
        val aOut = FloatArray(pixelCount)
        val bOut = FloatArray(pixelCount)

        for (i in 0 until pixelCount) {
            val cMax = lookupGamutMax(L[i], hue[i], gamutLut)
            val c = cRel[i] * cMax
            val h = hue[i]
            aOut[i] = c * cos(h)
            bOut[i] = c * sin(h)
        }

        return Pair(aOut, bOut)
    }

    /**
     * Clamp chroma to stay inside the sRGB gamut while preserving L and hue.
     * Uses [precomputedHue] to avoid recomputing atan2 per pixel.
     */
    fun gamutClampAb(
        L: FloatArray, a: FloatArray, bOk: FloatArray,
        gamutLut: FloatArray, pixelCount: Int,
        precomputedHue: FloatArray
    ): Pair<FloatArray, FloatArray> {
        val aOut = FloatArray(pixelCount)
        val bOut = FloatArray(pixelCount)

        for (i in 0 until pixelCount) {
            val ai = a[i]
            val bi = bOk[i]
            val c = sqrt(ai * ai + bi * bi)
            val h = precomputedHue[i]
            val maxC = lookupGamutMax(L[i], h, gamutLut)

            if (c > 1e-10f) {
                val scale = minOf(maxC / maxOf(c, 1e-10f), 1f)
                aOut[i] = ai * scale
                bOut[i] = bi * scale
            } else {
                aOut[i] = ai
                bOut[i] = bi
            }
        }

        return Pair(aOut, bOut)
    }

    // Zone weight constants
    private const val ZONE_BOUNDARY_LO = 1f / 3f
    private const val ZONE_BOUNDARY_HI = 2f / 3f
    private const val ZONE_HALF_W = 1f / 4f

    private fun smoothstep(t: Float): Float {
        val tc = t.coerceIn(0f, 1f)
        return tc * tc * (3f - 2f * tc)
    }

    /**
     * Compute zone weights (shadows, midtones, highlights) using CDF-adaptive smoothstep.
     *
     * @param L lightness values
     * @param cdfBins bin centers [NUM_BINS]
     * @param cdfValues cumulative distribution [NUM_BINS], normalized to [0,1]
     * @return Triple(wS, wM, wH) each FloatArray[pixelCount]
     */
    fun zoneWeightsCdf(
        L: FloatArray, cdfBins: FloatArray, cdfValues: FloatArray, pixelCount: Int
    ): Triple<FloatArray, FloatArray, FloatArray> {
        val wS = FloatArray(pixelCount)
        val wM = FloatArray(pixelCount)
        val wH = FloatArray(pixelCount)
        val invWidth = 1f / (2f * ZONE_HALF_W)

        val numBins = cdfBins.size
        for (i in 0 until pixelCount) {
            val lClamped = L[i].coerceIn(0f, 1f)
            val u = interpUniform(lClamped, cdfValues, numBins)
            val t1 = smoothstep((u - (ZONE_BOUNDARY_LO - ZONE_HALF_W)) * invWidth)
            val t2 = smoothstep((u - (ZONE_BOUNDARY_HI - ZONE_HALF_W)) * invWidth)
            wS[i] = 1f - t1
            wH[i] = t2
            wM[i] = t1 - t2
        }

        return Triple(wS, wM, wH)
    }

    /**
     * Fast interpolation for uniform bins in [0,1].
     * Direct index computation replaces O(log n) binary search.
     */
    private fun interpUniform(x: Float, fp: FloatArray, numBins: Int): Float {
        val xc = x.coerceIn(0f, 1f)
        val idxF = xc * (numBins - 1).toFloat()
        val lo = idxF.toInt().coerceIn(0, numBins - 2)
        val t = idxF - lo.toFloat()
        return fp[lo] + t * (fp[lo + 1] - fp[lo])
    }

    /**
     * Apply chroma offset per zone.
     */
    fun chromaOffsetAb(
        a: FloatArray, bOk: FloatArray,
        wS: FloatArray, wM: FloatArray, wH: FloatArray,
        thetaShadowRad: Float, strShadow: Float,
        thetaMidRad: Float, strMid: Float,
        thetaHiRad: Float, strHi: Float,
        pixelCount: Int
    ): Pair<FloatArray, FloatArray> {
        val aOut = FloatArray(pixelCount)
        val bOut = FloatArray(pixelCount)

        val daS = strShadow * cos(thetaShadowRad)
        val dbS = strShadow * sin(thetaShadowRad)
        val daM = strMid * cos(thetaMidRad)
        val dbM = strMid * sin(thetaMidRad)
        val daH = strHi * cos(thetaHiRad)
        val dbH = strHi * sin(thetaHiRad)

        for (i in 0 until pixelCount) {
            aOut[i] = a[i] + wS[i] * daS + wM[i] * daM + wH[i] * daH
            bOut[i] = bOk[i] + wS[i] * dbS + wM[i] * dbM + wH[i] * dbH
        }

        return Pair(aOut, bOut)
    }
}
