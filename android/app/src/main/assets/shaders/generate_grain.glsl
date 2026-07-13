#version 310 es

layout(local_size_x = 8, local_size_y = 8) in;

layout(rgba8, binding = 0) writeonly uniform highp image2D uOutput;

uniform int uTextureSize;
uniform uint uSeed;

const float SQRT_3 = 1.73205080756888;
const float INV_SQRT_2 = 0.70710678118655;
const float ROUGHNESS = 0.35;
const float BASE_NORMALIZATION = 1.6379204;
const float COARSE_NORMALIZATION = 1.0230575;
const float MIX_NORMALIZATION = 1.3545709;
const float TEXTURE_RANGE = 4.0;

int wrapCoordinate(int value) {
    if (value < 0) return value + uTextureSize;
    if (value >= uTextureSize) return value - uTextureSize;
    return value;
}

uint hashUint(uint value) {
    value ^= value >> 16u;
    value *= 0x7feb352du;
    value ^= value >> 15u;
    value *= 0x846ca68bu;
    value ^= value >> 16u;
    return value;
}

float whiteNoise(ivec2 pixel, uint salt) {
    uint x = uint(wrapCoordinate(pixel.x));
    uint y = uint(wrapCoordinate(pixel.y));
    uint hash = hashUint(
        x * 0x9e3779b9u
        ^ y * 0x85ebca6bu
        ^ uSeed
        ^ salt
    );
    float uniformNoise =
        float(hash & 0x00ffffffu) / float(0x00ffffffu);
    return uniformNoise * 2.0 - 1.0;
}

float baseField(ivec2 pixel) {
    float sum = 0.0;
    for (int y = -1; y <= 1; ++y) {
        for (int x = -1; x <= 1; ++x) {
            int radiusSquared = x * x + y * y;
            float weight = radiusSquared == 0
                ? 1.0
                : (radiusSquared == 1 ? 0.1690133 : 0.0285655);
            sum += weight * whiteNoise(
                pixel + ivec2(x, y),
                0x243f6a88u
            );
        }
    }
    return sum * BASE_NORMALIZATION;
}

float coarseWeight(int radiusSquared) {
    if (radiusSquared == 0) return 1.0;
    if (radiusSquared == 1) return 0.5780;
    if (radiusSquared == 2) return 0.3341;
    if (radiusSquared == 4) return 0.1118;
    if (radiusSquared == 5) return 0.0646;
    return 0.0125;
}

float coarseField(ivec2 pixel) {
    float sum = 0.0;
    for (int y = -2; y <= 2; ++y) {
        for (int x = -2; x <= 2; ++x) {
            sum += coarseWeight(x * x + y * y) * whiteNoise(
                pixel + ivec2(x, y),
                0xb7e15162u
            );
        }
    }
    return sum * COARSE_NORMALIZATION;
}

void main() {
    ivec2 pixel = ivec2(gl_GlobalInvocationID.xy);
    if (pixel.x >= uTextureSize || pixel.y >= uTextureSize) return;

    float primary = baseField(pixel);
    float fine = whiteNoise(pixel, 0x13198a2eu) * SQRT_3;
    float coarse = coarseField(pixel);
    float varied = (fine + coarse) * INV_SQRT_2;
    float grain = (
        (1.0 - ROUGHNESS) * primary
        + ROUGHNESS * varied
    ) * MIX_NORMALIZATION;
    float encoded = clamp(
        grain / (2.0 * TEXTURE_RANGE) + 0.5,
        0.0,
        1.0
    );
    imageStore(uOutput, pixel, vec4(encoded, 0.0, 0.0, 1.0));
}
