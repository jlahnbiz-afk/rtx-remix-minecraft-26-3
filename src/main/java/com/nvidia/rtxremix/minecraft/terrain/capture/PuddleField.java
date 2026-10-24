/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.terrain.capture;

import java.util.Arrays;

/**
 * Where rain pools: a depth for every block corner on the ground, as a vertex alpha.
 *
 * <p>A puddle quad carries the depth at its four corners and the puddle material cuts
 * where the interpolated depth does not exceed its alpha reference. Lowering that
 * reference as rain goes on floods outward from the deepest spots; raising it as the
 * ground dries shrinks the puddles back. The depth is a pure function of the block
 * position, so every client sees the same puddles in the same places.
 *
 * <p>Depths are spread evenly over {@code 1..255}: a coverage {@code c} of the ground
 * is reached at reference {@code 255 * (1 - c)}.
 */
public final class PuddleField {

    /** The largest share of the open ground a downpour floods. */
    public static final float MAX_COVERAGE = 0.3f;

    private static final long SEED = 0x5DEECE66DL;
    /** Noise octaves: cell size in blocks and weight. */
    private static final int[] OCTAVE_CELLS = {11, 5, 2};
    private static final float[] OCTAVE_WEIGHTS = {0.6f, 0.3f, 0.1f};

    /** Sorted raw noise values sampled over a large area, for mapping raw noise to an even depth. */
    private static final float[] QUANTILES = sampleQuantiles();
    /** The depth a corner needs for its block to ever hold water at {@link #MAX_COVERAGE}. */
    private static final int WETTEST_REFERENCE = reference(MAX_COVERAGE);

    private PuddleField() { }

    /** The depth at block corner {@code (x, z)}, 1 (never wet) to 255 (first to flood). */
    public static int depth(int x, int z) {
        float raw = raw(x, z);
        int index = Arrays.binarySearch(QUANTILES, raw);
        if (index < 0) index = -index - 1;
        return 1 + (int) ((long) index * 254 / QUANTILES.length);
    }

    /** The alpha reference that floods {@code coverage} of the open ground; 255 floods none. */
    public static int reference(float coverage) {
        float clamped = Math.max(0f, Math.min(1f, coverage));
        return Math.round(255f * (1f - clamped));
    }

    /** Whether any corner of block {@code (x, z)} floods by {@link #MAX_COVERAGE}. */
    public static boolean mayHoldWater(int x, int z) {
        return depth(x, z) > WETTEST_REFERENCE
                || depth(x + 1, z) > WETTEST_REFERENCE
                || depth(x, z + 1) > WETTEST_REFERENCE
                || depth(x + 1, z + 1) > WETTEST_REFERENCE;
    }

    static float raw(int x, int z) {
        float sum = 0f;
        for (int octave = 0; octave < OCTAVE_CELLS.length; octave++) {
            sum += OCTAVE_WEIGHTS[octave] * valueNoise(x, z, OCTAVE_CELLS[octave], octave);
        }
        return sum;
    }

    /** Smoothly interpolated lattice noise in {@code [0, 1)} with lattice points every {@code cell} blocks. */
    private static float valueNoise(int x, int z, int cell, int octave) {
        int cx = Math.floorDiv(x, cell), cz = Math.floorDiv(z, cell);
        float fx = smooth((x - cx * cell) / (float) cell);
        float fz = smooth((z - cz * cell) / (float) cell);
        float a = lattice(cx, cz, octave), b = lattice(cx + 1, cz, octave);
        float c = lattice(cx, cz + 1, octave), d = lattice(cx + 1, cz + 1, octave);
        float top = a + (b - a) * fx;
        float bottom = c + (d - c) * fx;
        return top + (bottom - top) * fz;
    }

    private static float smooth(float t) {
        return t * t * (3f - 2f * t);
    }

    private static float lattice(int x, int z, int octave) {
        long h = SEED + octave * 0x9E3779B97F4A7C15L;
        h ^= x * 0xC2B2AE3D27D4EB4FL;
        h ^= z * 0x165667B19E3779F9L;
        h = (h ^ (h >>> 31)) * 0xBF58476D1CE4E5B9L;
        h = (h ^ (h >>> 29)) * 0x94D049BB133111EBL;
        h ^= h >>> 32;
        return (h >>> 40) / (float) (1L << 24);
    }

    private static float[] sampleQuantiles() {
        int side = 256;
        float[] samples = new float[side * side];
        int i = 0;
        for (int z = 0; z < side; z++) {
            for (int x = 0; x < side; x++) {
                // Spaced past every octave's cell so the sample sees the whole distribution.
                samples[i++] = raw(x * 7 - 900, z * 7 + 300);
            }
        }
        Arrays.sort(samples);
        return samples;
    }
}
