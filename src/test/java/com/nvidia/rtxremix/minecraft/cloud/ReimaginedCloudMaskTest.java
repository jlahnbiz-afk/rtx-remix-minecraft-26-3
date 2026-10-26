/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.cloud;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReimaginedCloudMaskTest {

    private static final int SIZE = ReimaginedCloudMask.SIZE;

    @Test
    void coverageNamesTheShareOfFilledCells() {
        for (float coverage : new float[] {0.0f, 0.25f, 0.4f, 0.75f, 1.0f}) {
            boolean[] mask = ReimaginedCloudMask.generate(coverage, 9L);
            int filled = 0;
            for (boolean cell : mask) if (cell) filled++;
            assertEquals(coverage, filled / (float) mask.length, 0.01f, "coverage " + coverage);
        }
    }

    @Test
    void theMaskIsDeterministicAndTilesWithoutASeam() {
        boolean[] mask = ReimaginedCloudMask.generate(0.4f, 3L);
        assertArrayEquals(mask, ReimaginedCloudMask.generate(0.4f, 3L));
        int edgeMismatches = 0;
        int interiorMismatches = 0;
        for (int i = 0; i < SIZE; i++) {
            if (mask[i * SIZE] != mask[i * SIZE + SIZE - 1]) edgeMismatches++;
            if (mask[i * SIZE + SIZE / 2] != mask[i * SIZE + SIZE / 2 - 1]) interiorMismatches++;
        }
        // Across the wrap, neighbours agree about as often as neighbours inside the mask do.
        assertTrue(edgeMismatches <= interiorMismatches * 2 + 8,
                edgeMismatches + " mismatches across the wrap, " + interiorMismatches + " inside");
    }

    @Test
    void cloudsFormClumpsRatherThanSpeckle() {
        boolean[] mask = ReimaginedCloudMask.generate(0.4f, 5L);
        int filled = 0, withFilledNeighbour = 0;
        for (int z = 0; z < SIZE; z++) {
            for (int x = 0; x < SIZE; x++) {
                if (!mask[z * SIZE + x]) continue;
                filled++;
                if (mask[z * SIZE + (x + 1) % SIZE] || mask[((z + 1) % SIZE) * SIZE + x]) withFilledNeighbour++;
            }
        }
        assertTrue(withFilledNeighbour > 0.85 * filled, withFilledNeighbour + " of " + filled);
    }

    @Test
    void aStyleIndexOutsideTheListClamps() {
        assertEquals(CloudStyle.CLASSIC, CloudStyle.of(-3));
        assertEquals(CloudStyle.REIMAGINED, CloudStyle.of(2));
        assertEquals(CloudStyle.values()[CloudStyle.values().length - 1], CloudStyle.of(99));
    }

    @Test
    void aLayerRepeatsAtItsOwnPeriod() {
        VanillaCloudPlacement.Layer layer = new VanillaCloudPlacement.Layer(SIZE * 20.0, 0.0, 0.0);
        List<VanillaCloudPlacement.Placement> placements = new ArrayList<>();
        VanillaCloudPlacement.forEachVisible(0.0, 0.0, 0.0, SIZE, SIZE, layer,
                cursor -> placements.add(cursor.snapshot()));
        assertTrue(!placements.isEmpty());
        for (VanillaCloudPlacement.Placement placement : placements) {
            assertEquals(20.0f, placement.cellSizeX());
            assertEquals(0.0, Math.floorMod((long) placement.x(), (long) (SIZE * 20.0 / 8)), 1e-6);
        }
    }
}
