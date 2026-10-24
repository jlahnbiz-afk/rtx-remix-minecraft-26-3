/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.texture;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RainRipplesTest {

    private static final int FRAME_PX = 32;
    private static final float TILE_BLOCKS = 2f;

    @Test
    void theSheetHoldsEveryFrameAsUnitNormals() {
        byte[] sheet = RainRipples.normalSheet(FRAME_PX, TILE_BLOCKS, 7L);
        assertEquals(RainRipples.COLS * FRAME_PX * RainRipples.ROWS * FRAME_PX * 4, sheet.length);
        int tilted = 0;
        for (int i = 0; i < sheet.length; i += 4) {
            float x = (sheet[i] & 0xFF) / 127.5f - 1f;
            float y = (sheet[i + 1] & 0xFF) / 127.5f - 1f;
            float z = (sheet[i + 2] & 0xFF) / 127.5f - 1f;
            assertEquals(1f, (float) Math.sqrt(x * x + y * y + z * z), 0.02f);
            assertTrue(z > 0.5f, "normals point out of the water");
            assertEquals((byte) 0xFF, sheet[i + 3]);
            if (z < 0.995f) tilted++;
        }
        assertTrue(tilted > 0, "some rings disturb the surface");
    }

    @Test
    void theLoopRunsFromItsLastFrameIntoItsFirst() {
        float[][] drops = RainRipples.drops(TILE_BLOCKS, 11L);
        float texel = TILE_BLOCKS / FRAME_PX;
        float[] start = new float[FRAME_PX * FRAME_PX];
        float[] end = new float[FRAME_PX * FRAME_PX];
        RainRipples.heightField(start, FRAME_PX, texel, drops, 0f);
        RainRipples.heightField(end, FRAME_PX, texel, drops, RainRipples.LOOP_SECONDS);
        assertArrayEquals(start, end, 1.0e-7f);
    }

    @Test
    void aRingCrossingTheTileEdgeWrapsToTheOtherSide() {
        float texel = TILE_BLOCKS / FRAME_PX;
        float[][] drop = {{0.01f, TILE_BLOCKS / 2f, 0f}};
        float[] height = new float[FRAME_PX * FRAME_PX];
        RainRipples.heightField(height, FRAME_PX, texel, drop, 0.2f);
        int row = FRAME_PX / 2;
        float lastColumn = 0f;
        for (int x = FRAME_PX - 8; x < FRAME_PX; x++) lastColumn += Math.abs(height[row * FRAME_PX + x]);
        assertTrue(lastColumn > 0f, "the ring reaches the far edge through the wrap");
    }

    @Test
    void theSheetPlaysAtItsLoopLength() {
        assertEquals(RainRipples.FRAMES, Math.round(RainRipples.FPS * RainRipples.LOOP_SECONDS));
    }
}
