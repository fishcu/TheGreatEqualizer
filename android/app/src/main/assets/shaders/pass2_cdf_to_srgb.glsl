#version 310 es
// Pass 2: CDF transfer + reconstruct + zone weights + chroma offset + gamut clamp + sRGB output
//
// Input:  interleaved (L, C_rel) pairs + hue from pass 1
//         transferL[256], transferC[256] from CPU CDF fitting
//         cdfValues[256] from CPU (output L histogram CDF)
// Output: ARGB packed uint pixels
//
// Steps covered: 6 (CDF match L), 7 (CDF match C), 8 (reconstruct ab),
//                9 (zone weights), 10 (chroma offset), 11 (gamut clamp), 12 (OKLab→sRGB)

layout(local_size_x = 128) in;

// Binding layout stays within the four SSBOs guaranteed by GLES 3.1.
// The pixel buffer is reused: pass 1 reads input pixels, pass 2 writes output.
layout(std430, binding = 0) writeonly buffer PixelOutput {
    uint outPixels[];
};

layout(std430, binding = 1) readonly buffer AnalysisInput {
    vec2 analysis[];  // x=L, y=C_rel
};

layout(std430, binding = 2) readonly buffer HueInput {
    float inHue[];
};

layout(std430, binding = 3) readonly buffer LookupData {
    float lookupData[];
};

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

// L-channel grain. The two independently generated, incommensurate tiles
// produce a long repeat period while requiring only two filtered samples.
uniform highp sampler2D uGrainPrimary;
uniform highp sampler2D uGrainSecondary;
uniform float uGrainAmount;
uniform float uGrainSize;
uniform vec2 uGrainPatternOffset;
uniform vec2 uGrainCoordinateScale;
uniform float uGrainLod;
uniform int uImageWidth;
uniform ivec2 uImageOrigin;

// ── Constants ──
const int NUM_BINS = 256;
const int GAMUT_SIZE = 256 * 360;
const int TRANSFER_L_OFFSET = GAMUT_SIZE;
const int TRANSFER_C_OFFSET = TRANSFER_L_OFFSET + NUM_BINS;
const int CDF_OFFSET = TRANSFER_C_OFFSET + NUM_BINS;
const float H_TO_IDX = 360.0 / (2.0 * 3.14159265358979);
const int GAMUT_N_L = 256;
const int GAMUT_N_H = 360;
const float ZONE_BOUNDARY_LO = 1.0 / 3.0;
const float ZONE_BOUNDARY_HI = 2.0 / 3.0;
const float ZONE_HALF_W = 1.0 / 4.0;
const float GRAIN_REFERENCE_SIZE = 1.25;
const float GRAIN_PRIMARY_SIZE = 2048.0;
const float GRAIN_SECONDARY_SIZE = 2039.0;
const float GRAIN_TEXTURE_RANGE = 4.0;
const float GRAIN_SECONDARY_OFFSET = 137.0;
const float GRAIN_INV_SQRT_2 = 0.70710678118655;
const float GRAIN_EDGE_STRENGTH = 0.15;
const mat2 GRAIN_PRIMARY_ROTATION = mat2(
     0.9563048,  0.2923717,
    -0.2923717,  0.9563048
);
const mat2 GRAIN_SECONDARY_ROTATION = mat2(
     0.8571673, -0.5150381,
     0.5150381,  0.8571673
);

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
    float loValue = lookupData[TRANSFER_L_OFFSET + lo];
    float hiValue = lookupData[TRANSFER_L_OFFSET + lo + 1];
    return loValue + t * (hiValue - loValue);
}

float interpTransferC(float x) {
    float xc = clamp(x, 0.0, 1.0);
    float idxF = xc * float(NUM_BINS - 1);
    int lo = clamp(int(idxF), 0, NUM_BINS - 2);
    float t = idxF - float(lo);
    float loValue = lookupData[TRANSFER_C_OFFSET + lo];
    float hiValue = lookupData[TRANSFER_C_OFFSET + lo + 1];
    return loValue + t * (hiValue - loValue);
}

float interpCdf(float x) {
    float xc = clamp(x, 0.0, 1.0);
    float idxF = xc * float(NUM_BINS - 1);
    int lo = clamp(int(idxF), 0, NUM_BINS - 2);
    float t = idxF - float(lo);
    float loValue = lookupData[CDF_OFFSET + lo];
    float hiValue = lookupData[CDF_OFFSET + lo + 1];
    return loValue + t * (hiValue - loValue);
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

    return lookupData[row0 + hi0] * oneMinusFl * oneMinusFh
         + lookupData[row0 + hi1] * oneMinusFl * fh
         + lookupData[row1 + hi0] * fl * oneMinusFh
         + lookupData[row1 + hi1] * fl * fh;
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

float sampleGrain(vec2 globalPixel) {
    float coordinateScale = GRAIN_REFERENCE_SIZE / uGrainSize;
    vec2 primaryPixel = GRAIN_PRIMARY_ROTATION * globalPixel;
    vec2 primaryUv =
        (
            primaryPixel * coordinateScale + uGrainPatternOffset
        ) / GRAIN_PRIMARY_SIZE;
    vec2 secondaryPixel = GRAIN_SECONDARY_ROTATION * globalPixel
        + vec2(GRAIN_SECONDARY_OFFSET);
    vec2 secondaryUv =
        (
            secondaryPixel * coordinateScale
            + vec2(uGrainPatternOffset.y, -uGrainPatternOffset.x)
        ) / GRAIN_SECONDARY_SIZE;
    float primary = (
        textureLod(uGrainPrimary, primaryUv, uGrainLod).r * 2.0 - 1.0
    ) * GRAIN_TEXTURE_RANGE;
    float secondary = (
        textureLod(uGrainSecondary, secondaryUv, uGrainLod).r * 2.0 - 1.0
    ) * GRAIN_TEXTURE_RANGE;
    return (primary + secondary) * GRAIN_INV_SQRT_2;
}

void main() {
    uint idx = gl_GlobalInvocationID.x;
    if (idx >= uPixelCount) return;

    vec2 analysisValues = analysis[idx];
    float L = analysisValues.x;
    float cRel = analysisValues.y;
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

    // ── Step 11: Signal-dependent L-channel grain ──
    if (uGrainAmount > 0.0) {
        int localX = int(idx % uint(uImageWidth));
        int localY = int(idx / uint(uImageWidth));
        vec2 localPixel = vec2(localX, localY) + vec2(0.5);
        vec2 globalPixel =
            vec2(uImageOrigin) + localPixel * uGrainCoordinateScale;
        float midtoneWeight = clamp(4.0 * lOut * (1.0 - lOut), 0.0, 1.0);
        float envelope = GRAIN_EDGE_STRENGTH
            + (1.0 - GRAIN_EDGE_STRENGTH) * midtoneWeight;
        lOut = clamp(
            lOut + uGrainAmount * envelope * sampleGrain(globalPixel),
            0.0,
            1.0
        );
    }

    // ── Step 12: Gamut clamp ──
    float cPost = sqrt(aVal * aVal + bVal * bVal);
    float maxC = lookupGamutMax(lOut, hue);
    if (cPost > 1e-10) {
        float scale = min(maxC / max(cPost, 1e-10), 1.0);
        aVal *= scale;
        bVal *= scale;
    }

    // ── Step 13: OKLab → linear RGB → sRGB ──
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
