/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.terrain.capture;

import java.util.Arrays;

/**
 * Where rain pools, as two fields multiplied together.
 *
 * <p>The wet zones are a coarse field over the world, carried in a puddle film's vertex
 * alpha: zero where water never gathers, so no film is laid there, rising into the low
 * spots. Inside them, the puddle shapes are a fine field tiled every {@link #TILE_BLOCKS}
 * blocks in the puddle mask texture, which follows the weather: as the ground floods, its
 * texels cross a falling threshold and the puddles spread from their deepest spots with a
 * soft rim, then shrink back as it dries. The zones keep the tile from showing as a
 * repeating pattern. Both are pure functions of position, so every client sees the same
 * puddles.
 */
public final class PuddleField {

    /** Blocks one tile of the puddle mask covers along each axis; it wraps at its edges. */
    public static final int TILE_BLOCKS = 16;
    /** Mask texels per block: Minecraft's own texel density. */
    public static final int MASK_TEXELS_PER_BLOCK = 16;
    public static final int MASK_SIZE = TILE_BLOCKS * MASK_TEXELS_PER_BLOCK;

    /** Share of the ground inside the wet zones. */
    private static final float ZONE_COVERAGE = 0.4f;
    /** Depth, past the zone edge, over which a zone's alpha rises to full. */
    private static final int ZONE_RAMP = 48;
    /** Share of a wet zone's mask that floods in a downpour. */
    private static final float MAX_LOCAL_COVERAGE = 0.3f;
    /** Half the width of a puddle's rim, in the evenly spread shape field. */
    private static final float RIM = 0.06f;
    /** How opaque the middle of a puddle gets; below one, the ground shows faintly through. */
    private static final float MAX_OPACITY = 0.9f;

    private static final long SEED = 0x5DEECE66DL;
    /** Zone octaves: cell size in blocks and weight. */
    private static final int[] ZONE_CELLS = {23, 9};
    private static final float[] ZONE_WEIGHTS = {0.7f, 0.3f};
    /** Shape octaves: lattice cells per tile, which must divide the tile so it wraps. */
    private static final int[] SHAPE_CELLS_PER_TILE = {4, 8};
    private static final float[] SHAPE_WEIGHTS = {0.7f, 0.3f};

    /** Sorted raw zone noise sampled over a large area, for spreading the depth evenly. */
    private static final float[] ZONE_QUANTILES = sampleZoneQuantiles();
    private static final int ZONE_EDGE = Math.round(255f * (1f - ZONE_COVERAGE));
    /** The shape field over one tile, spread evenly over [0, 1) by rank. */
    private static final float[] SHAPE = evenShapeField();

    private PuddleField() { }

    /** The zone depth at block corner {@code (x, z)}, 1 (driest) to 255 (lowest spot). */
    public static int depth(int x, int z) {
        float raw = zoneRaw(x, z);
        int index = Arrays.binarySearch(ZONE_QUANTILES, raw);
        if (index < 0) index = -index - 1;
        return 1 + (int) ((long) index * 254 / ZONE_QUANTILES.length);
    }

    /** The puddle film's vertex alpha at block corner {@code (x, z)}: 0 outside the wet zones. */
    public static int zoneAlpha(int x, int z) {
        int over = depth(x, z) - ZONE_EDGE;
        if (over <= 0) return 0;
        return Math.min(255, over * 255 / ZONE_RAMP);
    }

    /** Whether any corner of block {@code (x, z)} lies in a wet zone, so a film there can show. */
    public static boolean mayHoldWater(int x, int z) {
        return zoneAlpha(x, z) > 0 || zoneAlpha(x + 1, z) > 0
                || zoneAlpha(x, z + 1) > 0 || zoneAlpha(x + 1, z + 1) > 0;
    }

    /**
     * Writes the puddle mask for ground {@code wetness}, 0 dry to 1 flooded, as RGBA8:
     * colour {@code rgb}, alpha the puddle's opacity with a soft rim.
     */
    public static void writeMask(float wetness, int rgb, byte[] rgba) {
        float coverage = Math.max(0f, Math.min(1f, wetness)) * MAX_LOCAL_COVERAGE;
        float threshold = 1f - coverage;
        byte r = (byte) (rgb >> 16), g = (byte) (rgb >> 8), b = (byte) rgb;
        for (int i = 0; i < SHAPE.length; i++) {
            float alpha = coverage <= 0f ? 0f : smoothstep(threshold - RIM, threshold + RIM, SHAPE[i]) * MAX_OPACITY;
            rgba[i * 4] = r;
            rgba[i * 4 + 1] = g;
            rgba[i * 4 + 2] = b;
            rgba[i * 4 + 3] = (byte) Math.round(alpha * 255f);
        }
    }

    static float smoothstep(float edge0, float edge1, float x) {
        float t = Math.max(0f, Math.min(1f, (x - edge0) / (edge1 - edge0)));
        return t * t * (3f - 2f * t);
    }

    static float zoneRaw(int x, int z) {
        float sum = 0f;
        for (int octave = 0; octave < ZONE_CELLS.length; octave++) {
            int cell = ZONE_CELLS[octave];
            int cx = Math.floorDiv(x, cell), cz = Math.floorDiv(z, cell);
            sum += ZONE_WEIGHTS[octave] * bilerp(
                    (x - cx * cell) / (float) cell, (z - cz * cell) / (float) cell,
                    lattice(cx, cz, octave), lattice(cx + 1, cz, octave),
                    lattice(cx, cz + 1, octave), lattice(cx + 1, cz + 1, octave));
        }
        return sum;
    }

    /** The tile's shape field at texel {@code (u, v)}, wrapping lattice points at the tile edge. */
    static float shapeRaw(int u, int v) {
        float sum = 0f;
        for (int octave = 0; octave < SHAPE_CELLS_PER_TILE.length; octave++) {
            int cells = SHAPE_CELLS_PER_TILE[octave];
            float cellTexels = MASK_SIZE / (float) cells;
            float fu = u / cellTexels, fv = v / cellTexels;
            int cu = (int) Math.floor(fu), cv = (int) Math.floor(fv);
            int octaveSeed = 16 + octave;
            sum += SHAPE_WEIGHTS[octave] * bilerp(fu - cu, fv - cv,
                    lattice(Math.floorMod(cu, cells), Math.floorMod(cv, cells), octaveSeed),
                    lattice(Math.floorMod(cu + 1, cells), Math.floorMod(cv, cells), octaveSeed),
                    lattice(Math.floorMod(cu, cells), Math.floorMod(cv + 1, cells), octaveSeed),
                    lattice(Math.floorMod(cu + 1, cells), Math.floorMod(cv + 1, cells), octaveSeed));
        }
        return sum;
    }

    private static float bilerp(float fx, float fz, float a, float b, float c, float d) {
        fx = fx * fx * (3f - 2f * fx);
        fz = fz * fz * (3f - 2f * fz);
        float top = a + (b - a) * fx;
        float bottom = c + (d - c) * fx;
        return top + (bottom - top) * fz;
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

    private static float[] sampleZoneQuantiles() {
        int side = 256;
        float[] samples = new float[side * side];
        int i = 0;
        for (int z = 0; z < side; z++) {
            for (int x = 0; x < side; x++) {
                // Spaced past every octave's cell so the sample sees the whole distribution.
                samples[i++] = zoneRaw(x * 7 - 900, z * 7 + 300);
            }
        }
        Arrays.sort(samples);
        return samples;
    }

    private static float[] evenShapeField() {
        float[] raw = new float[MASK_SIZE * MASK_SIZE];
        for (int v = 0; v < MASK_SIZE; v++) {
            for (int u = 0; u < MASK_SIZE; u++) {
                raw[v * MASK_SIZE + u] = shapeRaw(u, v);
            }
        }
        float[] sorted = raw.clone();
        Arrays.sort(sorted);
        float[] even = new float[raw.length];
        for (int i = 0; i < raw.length; i++) {
            int rank = Arrays.binarySearch(sorted, raw[i]);
            if (rank < 0) rank = -rank - 1;
            even[i] = rank / (float) sorted.length;
        }
        return even;
    }
}
