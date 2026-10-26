/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.terrain.capture;

import java.util.Arrays;
import net.minecraft.core.Direction;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BoundaryMaskTest {

    private static void setOpaque(long[] opacity, int lx, int ly, int lz, boolean opaque) {
        int index = (ly << 8) | (lz << 4) | lx;
        if (opaque) {
            opacity[index >> 6] |= 1L << (index & 63);
        } else {
            opacity[index >> 6] &= ~(1L << (index & 63));
        }
    }

    @Test
    void aSectionOfAirIsOpenOnEveryFace() {
        // Vanilla never hands air to the capture hook, so an all-air section has an empty bitmap.
        long[] opacity = new long[64];
        for (Direction face : Direction.values()) {
            assertEquals(-1L, BlockQuadEmitter.boundaryMask(opacity, face), face.toString());
        }
    }

    @Test
    void aSolidSectionIsSealed() {
        long[] opacity = new long[64];
        Arrays.fill(opacity, -1L);
        for (Direction face : Direction.values()) {
            assertEquals(0L, BlockQuadEmitter.boundaryMask(opacity, face), face.toString());
        }
    }

    @Test
    void oneOpenBlockOpensItsPatchOnItsFacesOnly() {
        long[] opacity = new long[64];
        Arrays.fill(opacity, -1L);
        setOpaque(opacity, 15, 3, 6, false);
        // East indexes (y, z): row 3 >> 1, column 6 >> 1.
        assertEquals(1L << (8 + 3), BlockQuadEmitter.boundaryMask(opacity, Direction.EAST));
        assertEquals(0L, BlockQuadEmitter.boundaryMask(opacity, Direction.WEST));
        assertEquals(0L, BlockQuadEmitter.boundaryMask(opacity, Direction.UP));

        setOpaque(opacity, 4, 15, 9, false);
        // Up indexes (z, x): row 9 >> 1, column 4 >> 1.
        assertEquals(1L << (4 * 8 + 2), BlockQuadEmitter.boundaryMask(opacity, Direction.UP));

        setOpaque(opacity, 13, 10, 0, false);
        // North indexes (y, x): row 10 >> 1, column 13 >> 1.
        assertEquals(1L << (5 * 8 + 6), BlockQuadEmitter.boundaryMask(opacity, Direction.NORTH));
    }
}
