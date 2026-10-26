/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.cloud;

import java.util.Arrays;

/**
 * The occupancy mask of the Reimagined cloud style: blocky clouds of large cells in
 * separate rounded clumps with open sky between them. Each clump is a few ellipses
 * scattered on a coarse grid, roughened by tiling fractal noise, and the field is
 * thresholded so that a chosen share of the cells is filled. The mask wraps at its
 * edges, so it repeats over the world seamlessly.
 */
final class ReimaginedCloudMask {

    static final int SIZE = 256;
    /** Clump grid cells across the mask; each holds one cloud. Divides {@link #SIZE}. */
    private static final int GRID = 8;
    private static final int GRID_CELL = SIZE / GRID;
    /** Overlapping ellipses per clump, which give it a lobed outline. */
    private static final int BLOBS_PER_CLUMP = 4;
    /** How far the noise pushes a clump's edge in and out, relative to its radius. */
    private static final float EDGE_NOISE = 0.45f;
    /** Noise lattice cells across the mask per octave; each divides {@link #SIZE} so the mask tiles. */
    private static final int[] EDGE_CELLS = {32, 64, 128};
    private static final float[] EDGE_WEIGHTS = {0.6f, 0.28f, 0.12f};
    /**
     * A broad swell over the clumps: where it is high they grow and merge into banks, where
     * it is low they shrink away and leave clear sky, so the field is not evenly spotted.
     */
    private static final int[] SWELL_CELLS = {4, 8};
    private static final float[] SWELL_WEIGHTS = {0.7f, 0.3f};
    private static final float SWELL = 1.6f;

    private ReimaginedCloudMask() {
    }

    /** A {@link #SIZE}-square mask with {@code coverage} 0 to 1 of its cells filled. */
    static boolean[] generate(float coverage, long seed) {
        float share = Math.clamp(coverage, 0.0f, 1.0f);
        float[] field = new float[SIZE * SIZE];
        for (int z = 0; z < SIZE; z++) {
            for (int x = 0; x < SIZE; x++) {
                field[z * SIZE + x] = clumpField(x, z, seed)
                        + EDGE_NOISE * (tiledNoise(x, z, seed, EDGE_CELLS, EDGE_WEIGHTS) - 0.5f)
                        + SWELL * (tiledNoise(x, z, ~seed, SWELL_CELLS, SWELL_WEIGHTS) - 0.5f);
            }
        }
        boolean[] occupied = new boolean[field.length];
        if (share <= 0.0f) {
            return occupied;
        }
        float[] sorted = field.clone();
        Arrays.sort(sorted);
        int index = Math.min(sorted.length - 1, Math.round((1.0f - share) * sorted.length));
        float threshold = share >= 1.0f ? Float.NEGATIVE_INFINITY : sorted[index];
        for (int i = 0; i < field.length; i++) {
            occupied[i] = field[i] >= threshold;
        }
        return occupied;
    }

    /**
     * How deep inside a clump the cell lies: 1 at a clump's centre, 0 on its edge, negative
     * outside. Clumps differ in strength, so the smaller ones drop out first as coverage falls.
     */
    private static float clumpField(int x, int z, long seed) {
        int gx = x / GRID_CELL;
        int gz = z / GRID_CELL;
        float best = -Float.MAX_VALUE;
        for (int dz = -1; dz <= 1; dz++) {
            for (int dx = -1; dx <= 1; dx++) {
                int cx = gx + dx;
                int cz = gz + dz;
                long cellSeed = seed ^ 0x2545F4914F6CDD1DL;
                int wx = Math.floorMod(cx, GRID);
                int wz = Math.floorMod(cz, GRID);
                float strength = 0.5f + 0.5f * lattice(wx, wz, cellSeed + 1);
                float clusterX = cx + 0.25f + 0.5f * lattice(wx, wz, cellSeed + 2);
                float clusterZ = cz + 0.25f + 0.5f * lattice(wx, wz, cellSeed + 3);
                for (int blob = 0; blob < BLOBS_PER_CLUMP; blob++) {
                    long blobSeed = cellSeed + 16L * (blob + 1);
                    float centreX = (clusterX + 0.5f * (lattice(wx, wz, blobSeed + 2) - 0.5f)) * GRID_CELL;
                    float centreZ = (clusterZ + 0.5f * (lattice(wx, wz, blobSeed + 3) - 0.5f)) * GRID_CELL;
                    float radiusX = GRID_CELL * (0.12f + 0.18f * lattice(wx, wz, blobSeed + 4));
                    float radiusZ = GRID_CELL * (0.12f + 0.18f * lattice(wx, wz, blobSeed + 5));
                    float ex = (x + 0.5f - centreX) / radiusX;
                    float ez = (z + 0.5f - centreZ) / radiusZ;
                    best = Math.max(best, strength * (1.0f - (float) Math.sqrt(ex * ex + ez * ez)));
                }
            }
        }
        return best;
    }

    private static float tiledNoise(int x, int z, long seed, int[] octaveCells, float[] weights) {
        float sum = 0.0f;
        for (int octave = 0; octave < octaveCells.length; octave++) {
            int cells = octaveCells[octave];
            float cellSize = SIZE / (float) cells;
            float fx = (x + 0.5f) / cellSize;
            float fz = (z + 0.5f) / cellSize;
            int cx = (int) Math.floor(fx);
            int cz = (int) Math.floor(fz);
            float tx = smooth(fx - cx);
            float tz = smooth(fz - cz);
            long octaveSeed = seed + octave * 0x9E3779B97F4A7C15L;
            float a = lattice(Math.floorMod(cx, cells), Math.floorMod(cz, cells), octaveSeed);
            float b = lattice(Math.floorMod(cx + 1, cells), Math.floorMod(cz, cells), octaveSeed);
            float c = lattice(Math.floorMod(cx, cells), Math.floorMod(cz + 1, cells), octaveSeed);
            float d = lattice(Math.floorMod(cx + 1, cells), Math.floorMod(cz + 1, cells), octaveSeed);
            float top = a + (b - a) * tx;
            float bottom = c + (d - c) * tx;
            sum += weights[octave] * (top + (bottom - top) * tz);
        }
        return sum;
    }

    private static float smooth(float t) {
        return t * t * (3.0f - 2.0f * t);
    }

    private static float lattice(int x, int z, long seed) {
        long h = seed ^ x * 0xC2B2AE3D27D4EB4FL ^ z * 0x165667B19E3779F9L;
        h = (h ^ (h >>> 31)) * 0xBF58476D1CE4E5B9L;
        h = (h ^ (h >>> 29)) * 0x94D049BB133111EBL;
        h ^= h >>> 32;
        return (h >>> 40) / (float) (1L << 24);
    }
}
