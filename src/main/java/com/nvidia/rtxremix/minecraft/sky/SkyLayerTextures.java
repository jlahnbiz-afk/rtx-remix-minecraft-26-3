/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.sky;

/**
 * The textures of the high sky layers, generated on the CPU as RGBA8 brightness: the
 * layers are added onto the sky, so a texel's colour is the light it adds.
 *
 * <p>The cirrus texture is a polar map of the upper sky: its centre is the zenith and its
 * edge circle {@link #CIRRUS_MIN_ELEVATION_DEGREES} above the horizon, with elevation
 * falling linearly with distance from the centre, so every degree of sky gets the same
 * texel density. Each texel samples the cloud field where its view ray meets a flat
 * cloud layer, so the streaks foreshorten toward the horizon as a real layer does.
 *
 * <p>The aurora texture stacks one band per curtain: across a band runs the curtain's
 * length, with its ends faded out, and up a band its height, bright along the lower edge
 * and fading toward the top, green below and violet above.
 */
final class SkyLayerTextures {

    static final float CIRRUS_MIN_ELEVATION_DEGREES = 3.0f;
    /** Elevation below which the cirrus fades out, as haze would hide it. */
    private static final float CIRRUS_FADE_ELEVATION_DEGREES = 14.0f;
    /** Cloud-layer distance, in layer heights, over which the streak pattern repeats once. */
    private static final float CIRRUS_FEATURE_SCALE = 1.6f;
    /** How much longer the streaks run along the wind than across it. */
    private static final float CIRRUS_STRETCH = 5.0f;

    private static final float[] AURORA_LOW = {0.15f, 1.0f, 0.45f};
    private static final float[] AURORA_HIGH = {0.6f, 0.2f, 1.0f};

    private SkyLayerTextures() { }

    /**
     * Writes the cirrus layer, {@code size} texels square, for {@code coverage} 0 (clear)
     * to 1 (streaked across most of the sky).
     */
    static byte[] cirrus(int size, float coverage, long seed) {
        byte[] rgba = new byte[size * size * 4];
        float threshold = 0.75f - 0.45f * Math.clamp(coverage, 0.0f, 1.0f);
        float span = 90.0f - CIRRUS_MIN_ELEVATION_DEGREES;
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                float u = (x + 0.5f) / size * 2.0f - 1.0f;
                float v = (y + 0.5f) / size * 2.0f - 1.0f;
                float r = (float) Math.sqrt(u * u + v * v);
                float alpha = 0.0f;
                if (r < 1.0f && coverage > 0.0f) {
                    float elevation = 90.0f - r * span;
                    // Where the view ray meets a cloud layer one unit up.
                    float reach = (float) (1.0 / Math.tan(Math.toRadians(elevation)));
                    float px = r > 0.0f ? u / r * reach : 0.0f;
                    float pz = r > 0.0f ? v / r * reach : 0.0f;
                    alpha = cirrusDensity(px / CIRRUS_FEATURE_SCALE, pz / CIRRUS_FEATURE_SCALE, threshold, seed)
                            * smoothstep(CIRRUS_MIN_ELEVATION_DEGREES, CIRRUS_FADE_ELEVATION_DEGREES, elevation);
                }
                byte value = (byte) Math.round(alpha * 255.0f);
                int i = (y * size + x) * 4;
                rgba[i] = value;
                rgba[i + 1] = value;
                rgba[i + 2] = value;
                rgba[i + 3] = (byte) 0xFF;
            }
        }
        return rgba;
    }

    /** Cirrus density 0 to 1 at layer position {@code (x, z)}: wind-stretched, domain-warped streaks in patches. */
    static float cirrusDensity(float x, float z, float threshold, long seed) {
        float warpX = x + 0.6f * fbm(x * 0.5f, z * 0.5f, 3, seed + 11);
        float warpZ = z + 0.6f * fbm(x * 0.5f + 5.2f, z * 0.5f + 1.3f, 3, seed + 17);
        float streak = fbm(warpX, warpZ * CIRRUS_STRETCH, 5, seed);
        float patch = fbm(x * 0.25f + 9.1f, z * 0.25f - 4.7f, 3, seed + 23);
        float shaped = smoothstep(threshold, threshold + 0.25f, streak) * smoothstep(0.35f, 0.65f, patch);
        return shaped * shaped;
    }

    /**
     * Writes {@code bands} aurora curtains, one band of {@code bandHeight} rows each, all
     * {@code width} texels long.
     */
    static byte[] aurora(int width, int bandHeight, int bands, long seed) {
        int height = bandHeight * bands;
        byte[] rgba = new byte[width * height * 4];
        for (int band = 0; band < bands; band++) {
            long bandSeed = seed + band * 101L;
            for (int x = 0; x < width; x++) {
                float u = (x + 0.5f) / width;
                float ends = smoothstep(0.0f, 0.15f, u) * smoothstep(1.0f, 0.85f, u);
                // Vertical rays: fine ridged noise along the curtain, gathered into a few bright folds.
                float rays = ridged(u * 90.0f, bandSeed) * 0.6f + ridged(u * 23.0f, bandSeed + 3) * 0.4f;
                float folds = smoothstep(0.25f, 0.8f, fbm(u * 6.0f, 0.5f, 3, bandSeed + 7));
                float strength = ends * rays * folds;
                // The curtain's lower edge wanders a little, as a real arc's does.
                float edge = 0.08f + 0.1f * fbm(u * 12.0f, 1.5f, 2, bandSeed + 13);
                for (int row = 0; row < bandHeight; row++) {
                    float h = (row + 0.5f) / bandHeight;
                    float vertical = smoothstep(edge, edge + 0.06f, h) * (float) Math.pow(1.0f - h, 1.6);
                    float level = strength * vertical;
                    float high = smoothstep(0.35f, 0.9f, h);
                    int y = band * bandHeight + (bandHeight - 1 - row);
                    int i = (y * width + x) * 4;
                    for (int c = 0; c < 3; c++) {
                        float colour = AURORA_LOW[c] + (AURORA_HIGH[c] - AURORA_LOW[c]) * high;
                        rgba[i + c] = (byte) Math.round(Math.clamp(level * colour, 0.0f, 1.0f) * 255.0f);
                    }
                    rgba[i + 3] = (byte) 0xFF;
                }
            }
        }
        return rgba;
    }

    /** 1D ridged noise in [0, 1]: sharp crests where the noise crosses its middle. */
    private static float ridged(float x, long seed) {
        float n = fbm(x, 0.0f, 3, seed);
        float ridge = 1.0f - Math.abs(n * 2.0f - 1.0f);
        return ridge * ridge * ridge;
    }

    /** Fractal value noise in [0, 1], {@code octaves} octaves halving in weight. */
    static float fbm(float x, float z, int octaves, long seed) {
        float sum = 0.0f;
        float weight = 0.5f;
        float total = 0.0f;
        for (int octave = 0; octave < octaves; octave++) {
            sum += weight * valueNoise(x, z, seed + octave * 31L);
            total += weight;
            x *= 2.03f;
            z *= 2.03f;
            weight *= 0.5f;
        }
        return sum / total;
    }

    private static float valueNoise(float x, float z, long seed) {
        int cx = (int) Math.floor(x);
        int cz = (int) Math.floor(z);
        float fx = x - cx;
        float fz = z - cz;
        fx = fx * fx * (3.0f - 2.0f * fx);
        fz = fz * fz * (3.0f - 2.0f * fz);
        float a = lattice(cx, cz, seed);
        float b = lattice(cx + 1, cz, seed);
        float c = lattice(cx, cz + 1, seed);
        float d = lattice(cx + 1, cz + 1, seed);
        float top = a + (b - a) * fx;
        float bottom = c + (d - c) * fx;
        return top + (bottom - top) * fz;
    }

    private static float lattice(int x, int z, long seed) {
        long h = seed * 0x9E3779B97F4A7C15L;
        h ^= x * 0xC2B2AE3D27D4EB4FL;
        h ^= z * 0x165667B19E3779F9L;
        h = (h ^ (h >>> 31)) * 0xBF58476D1CE4E5B9L;
        h = (h ^ (h >>> 29)) * 0x94D049BB133111EBL;
        h ^= h >>> 32;
        return (h >>> 40) / (float) (1L << 24);
    }

    static float smoothstep(float edge0, float edge1, float x) {
        float t = Math.clamp((x - edge0) / (edge1 - edge0), 0.0f, 1.0f);
        return t * t * (3.0f - 2.0f * t);
    }
}
