/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.weather;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PuddleMaskTest {

    private static final float[] SUM = PuddleMask.noiseSum(3L);
    private static final int SIZE = PuddleMask.SIZE;

    private static float coverage(float wetness) {
        int wet = 0;
        for (float sum : SUM) {
            if (PuddleMask.puddle(sum, wetness) > 0.0f) wet++;
        }
        return wet / (float) SUM.length;
    }

    @Test
    void dryGroundHoldsNoWaterAndSoakedGroundSomeButNotAll() {
        assertEquals(0.0f, coverage(0.0f));
        float soaked = coverage(1.0f);
        assertTrue(soaked > 0.15f && soaked < 0.75f, "soaked coverage " + soaked);
    }

    @Test
    void puddlesGrowAsTheGroundGetsWetter() {
        float previous = 0.0f;
        for (float w = 0.1f; w <= 1.0f; w += 0.1f) {
            float now = coverage(w);
            assertTrue(now >= previous, "coverage " + now + " at " + w + " after " + previous);
            previous = now;
        }
        for (float sum : SUM) {
            assertTrue(PuddleMask.puddle(sum, 0.5f) <= PuddleMask.puddle(sum, 1.0f));
        }
    }

    @Test
    void theTileWrapsWithoutASeam() {
        float edge = 0.0f;
        float inside = 0.0f;
        for (int y = 0; y < SIZE; y++) {
            edge += Math.abs(SUM[y * SIZE] - SUM[y * SIZE + SIZE - 1]);
            inside += Math.abs(SUM[y * SIZE + SIZE / 2] - SUM[y * SIZE + SIZE / 2 - 1]);
        }
        assertTrue(edge < inside * 2.0f + 1.0f, edge + " across the wrap against " + inside + " inside");
    }

    @Test
    void theSumThresholdMarksWhereTheWaterPassesTheThreshold() {
        for (float wetness = 0.25f; wetness <= 1.0f; wetness += 0.25f) {
            for (float threshold : new float[] {0.15f, 0.45f, 0.75f}) {
                float limit = PuddleMask.sumThreshold(threshold, wetness);
                for (int i = 0; i < SUM.length; i += 97) {
                    if (Math.abs(SUM[i] - limit) < 1.0e-4f) continue;
                    assertEquals(PuddleMask.puddle(SUM[i], wetness) > threshold, SUM[i] > limit,
                            "sum " + SUM[i] + " against " + limit);
                }
            }
        }
    }

    @Test
    void dryGroundPassesNothing() {
        assertTrue(PuddleMask.sumThreshold(0.15f, 0.0f) >= PuddleMask.MAX_SUM);
    }

    @Test
    void edgesFadeRatherThanStop() {
        int partial = 0;
        for (float sum : SUM) {
            float water = PuddleMask.puddle(sum, 1.0f);
            if (water > 0.0f && water < 1.0f) partial++;
        }
        assertTrue(partial > SUM.length / 50, partial + " texels of fading edge");
    }
}
