/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.terrain.capture;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WaterTileCoordinateTest {

    private static final float TILE = 10.0f;

    @Test
    void everyBlockFaceStaysInsideOneTile() {
        for (int block = -2345; block <= 2345; block++) {
            float start = BlockQuadEmitter.waterTileCoordinate(block, TILE);
            assertTrue(start >= 0.0f && start + 1 / TILE <= 1.0f + 1e-6f, "block " + block + " starts at " + start);
        }
    }

    @Test
    void neighbouringBlocksMeetAcrossTheTileEdge() {
        // Block 9 ends at the tile's far edge and block 10 starts at its near edge, which the
        // tiling texture joins; elsewhere a block starts where the previous one ended.
        assertEquals(0.9f, BlockQuadEmitter.waterTileCoordinate(9, TILE), 1e-6f);
        assertEquals(0.0f, BlockQuadEmitter.waterTileCoordinate(10, TILE), 1e-6f);
        assertEquals(0.9f, BlockQuadEmitter.waterTileCoordinate(-1, TILE), 1e-6f);
        assertEquals(0.7f, BlockQuadEmitter.waterTileCoordinate(-233, TILE), 1e-6f);
        assertEquals(BlockQuadEmitter.waterTileCoordinate(-233, TILE) + 1 / TILE,
                BlockQuadEmitter.waterTileCoordinate(-232, TILE), 1e-6f);
    }
}
