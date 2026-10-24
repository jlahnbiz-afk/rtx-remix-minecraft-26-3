/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.terrain.capture;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PuddleFieldTest {

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
    void aReferenceFloodsAboutTheCoverageItNames() {
        int corners = 0, floodedAtTenth = 0, floodedAtMax = 0;
        int tenth = PuddleField.reference(0.1f);
        int max = PuddleField.reference(PuddleField.MAX_COVERAGE);
        for (int z = 0; z < 400; z++) {
            for (int x = 0; x < 400; x++) {
                int depth = PuddleField.depth(x + 5000, z - 7000);
                corners++;
                if (depth > tenth) floodedAtTenth++;
                if (depth > max) floodedAtMax++;
            }
        }
        assertEquals(0.1, floodedAtTenth / (double) corners, 0.05);
        assertEquals(PuddleField.MAX_COVERAGE, floodedAtMax / (double) corners, 0.08);
    }

    @Test
    void dryGroundFloodsNowhere() {
        int none = PuddleField.reference(0f);
        assertEquals(255, none);
        for (int x = 0; x < 2000; x += 3) {
            assertFalse(PuddleField.depth(x, x / 2) > none);
        }
    }

    @Test
    void puddlesClusterRatherThanSpeckle() {
        // A corner that floods at full coverage usually has a flooding neighbour.
        int max = PuddleField.reference(PuddleField.MAX_COVERAGE);
        int flooded = 0, withFloodedNeighbour = 0;
        for (int z = 0; z < 200; z++) {
            for (int x = 0; x < 200; x++) {
                if (PuddleField.depth(x, z) <= max) continue;
                flooded++;
                if (PuddleField.depth(x + 1, z) > max || PuddleField.depth(x, z + 1) > max) {
                    withFloodedNeighbour++;
                }
            }
        }
        assertTrue(flooded > 0);
        assertTrue(withFloodedNeighbour > 0.8 * flooded, withFloodedNeighbour + " of " + flooded);
    }

    @Test
    void onlyBlocksWithAFloodingCornerHoldWater() {
        int max = PuddleField.reference(PuddleField.MAX_COVERAGE);
        for (int z = 0; z < 100; z++) {
            for (int x = 0; x < 100; x++) {
                boolean anyCorner = PuddleField.depth(x, z) > max || PuddleField.depth(x + 1, z) > max
                        || PuddleField.depth(x, z + 1) > max || PuddleField.depth(x + 1, z + 1) > max;
                assertEquals(anyCorner, PuddleField.mayHoldWater(x, z));
            }
        }
    }
}
