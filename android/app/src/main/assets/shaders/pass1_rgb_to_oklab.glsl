#version 310 es
// Pass 1: sRGB pixels → OKLab L + relative chroma + hue
// Input:  ARGB packed uint pixels
// Output: interleaved (L, C_rel) pairs + hue
//
// Steps covered: 1 (sRGB EOTF), 2 (RGB→OKLab), 4 (relative chroma)

layout(local_size_x = 128) in;

// Binding layout stays within the four SSBOs guaranteed by GLES 3.1.
layout(std430, binding = 0) readonly buffer PixelInput {
    uint pixels[];  // ARGB packed: (A<<24)|(R<<16)|(G<<8)|B
};

layout(std430, binding = 1) writeonly buffer AnalysisOutput {
    vec2 analysis[];  // x=L, y=C_rel
};

layout(std430, binding = 2) writeonly buffer HueOutput {
    float outHue[];
};

layout(std430, binding = 3) readonly buffer LookupData {
    float lookupData[];  // gamut LUT starts at offset 0
};

uniform uint uPixelCount;
uniform float uVignetteAmount;
uniform float uVignetteFalloff;
uniform int uImageWidth;
uniform ivec2 uImageOrigin;
uniform vec2 uImageCoordinateScale;
uniform ivec2 uFullImageSize;

// ── sRGB EOTF ──
float srgb_eotf(float v) {
    return (v <= 0.04045) ? (v / 12.92) : pow((v + 0.055) / 1.055, 2.4);
}

// ── Gamut LUT bilinear lookup ──
// Same logic as OkLab.lookupGamutMax in Kotlin
const float H_TO_IDX = 360.0 / (2.0 * 3.14159265358979);
const int GAMUT_N_L = 256;
const int GAMUT_N_H = 360;

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

// ── M1: linear sRGB → pre-nonlinearity LMS ──
const mat3 M1 = mat3(
    0.4122214708, 0.2119034982, 0.0883024619,  // column 0
    0.5363325363, 0.6806995451, 0.2817188376,  // column 1
    0.0514459929, 0.1073969566, 0.6299787005   // column 2
);

// ── M2: cube-root LMS → OKLab ──
const mat3 M2 = mat3(
    0.2104542553,  1.9779984951, 0.0259040371,  // column 0
    0.7936177850, -2.4285922050, 0.7827717662,  // column 1
   -0.0040720468,  0.4505937099, -0.8086757660  // column 2
);

void main() {
    uint idx = gl_GlobalInvocationID.x;
    if (idx >= uPixelCount) return;

    // Unpack ARGB pixel
    uint px = pixels[idx];
    float r = float((px >> 16u) & 0xFFu) / 255.0;
    float g = float((px >> 8u) & 0xFFu) / 255.0;
    float b = float(px & 0xFFu) / 255.0;

    // Step 1: sRGB EOTF
    float rLin = srgb_eotf(r);
    float gLin = srgb_eotf(g);
    float bLin = srgb_eotf(b);

    // Step 2: Linear RGB → OKLab
    vec3 rgb = vec3(rLin, gLin, bLin);
    if (uVignetteAmount > 0.0) {
        int localX = int(idx % uint(uImageWidth));
        int localY = int(idx / uint(uImageWidth));
        vec2 globalPixel =
            vec2(uImageOrigin)
            + (vec2(localX, localY) + vec2(0.5))
                * uImageCoordinateScale;
        vec2 fullImageSize = vec2(uFullImageSize);
        vec2 imageCenter = fullImageSize * 0.5;
        float normalizationRadius =
            length(fullImageSize) * 0.5;
        vec2 normalizedPosition =
            (globalPixel - imageCenter) / normalizationRadius;
        float radiusSquared = clamp(
            dot(normalizedPosition, normalizedPosition),
            0.0,
            1.0
        );
        float falloff = pow(
            radiusSquared,
            uVignetteFalloff * 0.5
        );
        float sourceBrightness = dot(
            vec3(r, g, b),
            vec3(0.2126, 0.7152, 0.0722)
        );
        float sourceBrightnessSquared =
            sourceBrightness * sourceBrightness;
        float highlightMask =
            sourceBrightnessSquared * sourceBrightnessSquared;
        float effectiveStops =
            uVignetteAmount * falloff
            * (1.0 - highlightMask);
        float exposureGain =
            exp2(-effectiveStops);
        rgb *= exposureGain;
    }
    vec3 lms = M1 * rgb;

    // Cube root (sign-preserving for safety)
    vec3 lmsG = sign(lms) * pow(abs(lms), vec3(1.0 / 3.0));

    vec3 lab = M2 * lmsG;
    float L = lab.x;
    float aVal = lab.y;
    float bVal = lab.z;

    // Step 4: Relative chroma
    float C = sqrt(aVal * aVal + bVal * bVal);
    float h = atan(bVal, aVal);  // GLSL atan(y,x) = atan2
    if (h < 0.0) h += 2.0 * 3.14159265358979;

    float cMax = lookupGamutMax(L, h);
    float cRel = (cMax > 1e-10) ? clamp(C / max(cMax, 1e-10), 0.0, 1.0) : 0.0;

    // Write outputs
    analysis[idx] = vec2(L, cRel);
    outHue[idx] = h;
}
