/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.terrain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

class UnseenSectionCompilesTest {

    @Test
    void theSweepVisitsEveryColumnOfTheViewAreaOnceNearestFirst() {
        int radius = 5;
        long[] offsets = UnseenSectionCompiles.offsetsWithin(radius);
        assertEquals((2 * radius + 1) * (2 * radius + 1), offsets.length);
        assertEquals(0L, offsets[0], "the camera's own column first");
        Set<Long> seen = new HashSet<>();
        int previous = -1;
        for (long packed : offsets) {
            int dx = (int) (packed >> 32);
            int dz = (int) packed;
            assertTrue(Math.abs(dx) <= radius && Math.abs(dz) <= radius);
            assertTrue(seen.add(packed));
            int distance = dx * dx + dz * dz;
            assertTrue(distance >= previous, "nearer columns come first");
            previous = distance;
        }
    }
}
