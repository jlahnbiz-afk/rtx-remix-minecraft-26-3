/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.terrain.capture;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PuddleFieldTest {

    private static final int SIZE = PuddleField.MASK_SIZE;

    @Test
    void depthIsAPureFunctionOfTheCorner() {
        assertEquals(PuddleField.depth(1234, -5678), PuddleField.depth(1234, -5678));
        for (int z = -40; z < 40; z++) {
            for (int x = -40; x < 40; x++) {
                int depth = PuddleField.depth(x * 13 + 7, z * 17 - 3);
                assertTrue(depth >= 1 && depth <= 255, "depth " + depth);
            }
        }
    }

    @Test
    void wetZonesCoverPartOfTheGroundAndFadeInAtTheirEdges() {
        int corners = 0, wet = 0, partial = 0;
        for (int z = 0; z < 400; z++) {
            for (int x = 0; x < 400; x++) {
                int alpha = PuddleField.zoneAlpha(x + 5000, z - 7000);
                assertTrue(alpha >= 0 && alpha <= 255, "alpha " + alpha);
                corners++;
                if (alpha > 0) wet++;
                if (alpha > 0 && alpha < 255) partial++;
            }
        }
        assertEquals(0.4, wet / (double) corners, 0.08);
        assertTrue(partial > 0.2 * wet, partial + " of " + wet + " wet corners fade in");
    }

    @Test
    void onlyBlocksWithAWetCornerHoldWater() {
        for (int z = 0; z < 100; z++) {
            for (int x = 0; x < 100; x++) {
                boolean anyCorner = PuddleField.zoneAlpha(x, z) > 0 || PuddleField.zoneAlpha(x + 1, z) > 0
                        || PuddleField.zoneAlpha(x, z + 1) > 0 || PuddleField.zoneAlpha(x + 1, z + 1) > 0;
                assertEquals(anyCorner, PuddleField.mayHoldWater(x, z));
            }
        }
    }

    @Test
    void dryGroundShowsNoWater() {
        byte[] mask = new byte[SIZE * SIZE * 4];
        PuddleField.writeMask(0f, 0x102030, mask);
        for (int i = 0; i < SIZE * SIZE; i++) {
            assertEquals(0, mask[i * 4 + 3]);
            assertEquals((byte) 0x10, mask[i * 4]);
            assertEquals((byte) 0x30, mask[i * 4 + 2]);
        }
    }

    @Test
    void puddlesSpreadAsTheGroundFloodsAndKeepASoftRim() {
        byte[] half = new byte[SIZE * SIZE * 4];
        byte[] full = new byte[SIZE * SIZE * 4];
        PuddleField.writeMask(0.5f, 0, half);
        PuddleField.writeMask(1f, 0, full);
        int maxAlpha = 0, covered = 0, rim = 0;
        for (int i = 0; i < SIZE * SIZE; i++) {
            int a = full[i * 4 + 3] & 0xFF;
            assertTrue((half[i * 4 + 3] & 0xFF) <= a, "a puddle only grows as the ground floods");
            maxAlpha = Math.max(maxAlpha, a);
        }
        for (int i = 0; i < SIZE * SIZE; i++) {
            int a = full[i * 4 + 3] & 0xFF;
            if (a > maxAlpha / 2) covered++;
            if (a > maxAlpha / 10 && a < maxAlpha * 9 / 10) rim++;
        }
        assertEquals(0.3, covered / (double) (SIZE * SIZE), 0.05);
        assertTrue(rim > 0.05 * covered, rim + " rim texels around " + covered);
    }

    @Test
    void theMaskTilesWithoutASeam() {
        byte[] mask = new byte[SIZE * SIZE * 4];
        PuddleField.writeMask(1f, 0, mask);
        int worstStep = 0;
        for (int v = 0; v < SIZE; v++) {
            int first = mask[(v * SIZE) * 4 + 3] & 0xFF;
            int last = mask[(v * SIZE + SIZE - 1) * 4 + 3] & 0xFF;
            worstStep = Math.max(worstStep, Math.abs(first - last));
        }
        for (int u = 0; u < SIZE; u++) {
            int first = mask[u * 4 + 3] & 0xFF;
            int last = mask[((SIZE - 1) * SIZE + u) * 4 + 3] & 0xFF;
            worstStep = Math.max(worstStep, Math.abs(first - last));
        }
        assertTrue(worstStep < 128, "steepest step across the wrap: " + worstStep);
    }
}
