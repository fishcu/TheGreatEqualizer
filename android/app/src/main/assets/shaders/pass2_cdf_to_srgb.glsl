#version 310 es
// Pass 2: CDF transfer + reconstruct + zone weights + chroma offset + gamut clamp + sRGB output
//
// Input:  L[], a[], b[], C_rel[], hue[] from pass 1
//         transferL[256], transferC[256] from CPU CDF fitting
//         cdfValues[256] from CPU (output L histogram CDF)
// Output: ARGB packed uint pixels
//
// Steps covered: 6 (CDF match L), 7 (CDF match C), 8 (reconstruct ab),
//                9 (zone weights), 10 (chroma offset), 11 (gamut clamp), 12 (OKLab→sRGB)

layout(local_size_x = 256) in;

// Per-pixel data from pass 1
layout(std430, binding = 0) readonly buffer InL    { float inL[];    };
layout(std430, binding = 1) readonly buffer InCrel { float inCrel[]; };
layout(std430, binding = 2) readonly buffer InHue  { float inHue[];  };

// Transfer LUTs from CPU fitting (256 entries each)
layout(std430, binding = 3) readonly buffer TransferL { float transferL[]; };
layout(std430, binding = 4) readonly buffer TransferC { float transferC[]; };

// Output CDF for zone weights (256 entries, normalized)
layout(std430, binding = 5) readonly buffer CdfVals { float cdfValues[]; };

// Gamut LUT
layout(std430, binding = 6) readonly buffer GamutLUT { float gamutLut[]; };

// Output pixels
layout(std430, binding = 7) writeonly buffer PixelOutput { uint outPixels[]; };

uniform uint uPixelCount;
uniform float uBlackL;
uniform float uWhiteL;
uniform float uBlackC;
uniform float uWhiteC;

// Zone chroma offset params (default 0 = no-op)
uniform float uShAngle;
uniform float uShStr;
uniform float uMidAngle;
uniform float uMidStr;
uniform float uHiAngle;
uniform float uHiStr;

// ── Constants ──
const int NUM_BINS = 256;
const float H_TO_IDX = 360.0 / (2.0 * 3.14159265358979);
const int GAMUT_N_L = 256;
const int GAMUT_N_H = 360;
const float ZONE_BOUNDARY_LO = 1.0 / 3.0;
const float ZONE_BOUNDARY_HI = 2.0 / 3.0;
const float ZONE_HALF_W = 1.0 / 4.0;

// ── M2 inverse: OKLab → cube-root LMS ──
const mat3 M2_INV = mat3(
    1.0,           1.0,           1.0,            // column 0
    0.3963377774, -0.1055613458, -0.0894841775,   // column 1
    0.2158037573, -0.0638541728, -1.2914855480    // column 2
);

// ── M1 inverse: LMS → linear sRGB ──
const mat3 M1_INV = mat3(
    4.0767416621, -1.2684380046, -0.0041960863,   // column 0
   -3.3077115913,  2.6097574011, -0.7034186147,   // column 1
    0.2309699292, -0.3413193965,  1.7076147010    // column 2
);

// Specific interp functions for each SSBO-backed LUT (256 entries over [0,1])
float interpTransferL(float x) {
    float xc = clamp(x, 0.0, 1.0);
    float idxF = xc * float(NUM_BINS - 1);
    int lo = clamp(int(idxF), 0, NUM_BINS - 2);
    float t = idxF - float(lo);
    return transferL[lo] + t * (transferL[lo + 1] - transferL[lo]);
}

float interpTransferC(float x) {
    float xc = clamp(x, 0.0, 1.0);
    float idxF = xc * float(NUM_BINS - 1);
    int lo = clamp(int(idxF), 0, NUM_BINS - 2);
    float t = idxF - float(lo);
    return transferC[lo] + t * (transferC[lo + 1] - transferC[lo]);
}

float interpCdf(float x) {
    float xc = clamp(x, 0.0, 1.0);
    float idxF = xc * float(NUM_BINS - 1);
    int lo = clamp(int(idxF), 0, NUM_BINS - 2);
    float t = idxF - float(lo);
    return cdfValues[lo] + t * (cdfValues[lo + 1] - cdfValues[lo]);
}

// ── Gamut LUT bilinear lookup ──
float lookupGamutMax(float L_val, float h) {
    float liF = clamp(L_val, 0.0, 1.0) * float(GAMUT_N_L - 1);
    float hiF = h * H_TO_IDX;

    int li0 = clamp(int(liF), 0, GAMUT_N_L - 1);
    int li1 = min(li0 + 1, GAMUT_N_L - 1);
    float fl = liF - float(li0);

    float hiFloor = floor(hiF);
    int hi0 = int(hiFloor) % GAMUT_N_H;
    if (hi0 < 0) hi0 += GAMUT_N_H;
    int hi1 = (hi0 + 1) % GAMUT_N_H;
    float fh = hiF - hiFloor;

    float oneMinusFl = 1.0 - fl;
    float oneMinusFh = 1.0 - fh;
    int row0 = li0 * GAMUT_N_H;
    int row1 = li1 * GAMUT_N_H;

    return gamutLut[row0 + hi0] * oneMinusFl * oneMinusFh
         + gamutLut[row0 + hi1] * oneMinusFl * fh
         + gamutLut[row1 + hi0] * fl * oneMinusFh
         + gamutLut[row1 + hi1] * fl * fh;
}

// ── Smoothstep ──
float smoothstep01(float t) {
    float tc = clamp(t, 0.0, 1.0);
    return tc * tc * (3.0 - 2.0 * tc);
}

// ── sRGB OETF ──
float srgb_oetf(float v) {
    return (v <= 0.0031308) ? (12.92 * v) : (1.055 * pow(v, 1.0 / 2.4) - 0.055);
}

void main() {
    uint idx = gl_GlobalInvocationID.x;
    if (idx >= uPixelCount) return;

    float L = inL[idx];
    float cRel = inCrel[idx];
    float hue = inHue[idx];

    // ── Step 6: CDF match L ──
    float spanL = 1.0 + uWhiteL - uBlackL;
    float lIn = clamp(L, 0.0, 1.0);
    float lMapped = interpTransferL(lIn);
    float lOut = clamp(uBlackL + lMapped * spanL, 0.0, 1.0);

    // ── Step 7: CDF match C ──
    float spanC = 1.0 + uWhiteC - uBlackC;
    float cIn = clamp(cRel, 0.0, 1.0);
    float cMapped = interpTransferC(cIn);
    float cRelOut = clamp(uBlackC + cMapped * spanC, 0.0, 1.0);

    // ── Step 8: Reconstruct a, b ──
    float cMax = lookupGamutMax(lOut, hue);
    float C = cRelOut * cMax;
    float aVal = C * cos(hue);
    float bVal = C * sin(hue);

    // ── Step 9: Zone weights ──
    float u = interpCdf(clamp(lOut, 0.0, 1.0));
    float invWidth = 1.0 / (2.0 * ZONE_HALF_W);
    float t1 = smoothstep01((u - (ZONE_BOUNDARY_LO - ZONE_HALF_W)) * invWidth);
    float t2 = smoothstep01((u - (ZONE_BOUNDARY_HI - ZONE_HALF_W)) * invWidth);
    float wS = 1.0 - t1;
    float wH = t2;
    float wM = t1 - t2;

    // ── Step 10: Chroma offset ──
    float daS = uShStr * cos(uShAngle);
    float dbS = uShStr * sin(uShAngle);
    float daM = uMidStr * cos(uMidAngle);
    float dbM = uMidStr * sin(uMidAngle);
    float daH = uHiStr * cos(uHiAngle);
    float dbH = uHiStr * sin(uHiAngle);

    aVal += wS * daS + wM * daM + wH * daH;
    bVal += wS * dbS + wM * dbM + wH * dbH;

    // ── Step 11: Gamut clamp ──
    float cPost = sqrt(aVal * aVal + bVal * bVal);
    float maxC = lookupGamutMax(lOut, hue);
    if (cPost > 1e-10) {
        float scale = min(maxC / max(cPost, 1e-10), 1.0);
        aVal *= scale;
        bVal *= scale;
    }

    // ── Step 12: OKLab → linear RGB → sRGB ──
    vec3 lab = vec3(lOut, aVal, bVal);
    vec3 lmsG = M2_INV * lab;
    vec3 lms = lmsG * lmsG * lmsG;  // cube
    vec3 rgb = M1_INV * lms;

    // sRGB OETF + quantize
    float rOut = srgb_oetf(clamp(rgb.r, 0.0, 1.0));
    float gOut = srgb_oetf(clamp(rgb.g, 0.0, 1.0));
    float bOut = srgb_oetf(clamp(rgb.b, 0.0, 1.0));

    uint rU = uint(clamp(rOut * 255.0 + 0.5, 0.0, 255.0));
    uint gU = uint(clamp(gOut * 255.0 + 0.5, 0.0, 255.0));
    uint bU = uint(clamp(bOut * 255.0 + 0.5, 0.0, 255.0));

    outPixels[idx] = (0xFFu << 24u) | (rU << 16u) | (gU << 8u) | bU;
}
