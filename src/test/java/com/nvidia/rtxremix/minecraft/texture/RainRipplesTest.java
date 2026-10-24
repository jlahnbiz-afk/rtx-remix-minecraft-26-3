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
    private static final RainRipples RIPPLES = RainRipples.generate(FRAME_PX, TILE_BLOCKS, 7L);

    @Test
    void framesAreUnitNormalsOutOfTheWater() {
        byte[] rgba = new byte[FRAME_PX * FRAME_PX * 4];
        int tilted = 0;
        for (int frame = 0; frame < RainRipples.FRAMES; frame++) {
            RIPPLES.writeNormals(frame, 1f, rgba);
            for (int i = 0; i < rgba.length; i += 4) {
                float x = (rgba[i] & 0xFF) / 127.5f - 1f;
                float y = (rgba[i + 1] & 0xFF) / 127.5f - 1f;
                float z = (rgba[i + 2] & 0xFF) / 127.5f - 1f;
                assertEquals(1f, (float) Math.sqrt(x * x + y * y + z * z), 0.02f);
                assertTrue(z > 0.5f, "normals point out of the water");
                assertEquals((byte) 0xFF, rgba[i + 3]);
                if (z < 0.995f) tilted++;
            }
        }
        assertTrue(tilted > 0, "some rings disturb the surface");
    }

    @Test
    void withoutRainTheWaterIsCalm() {
        byte[] normals = new byte[FRAME_PX * FRAME_PX * 4];
        byte[] rings = new byte[FRAME_PX * FRAME_PX * 4];
        for (int frame = 0; frame < RainRipples.FRAMES; frame += 7) {
            RIPPLES.writeNormals(frame, 0f, normals);
            RIPPLES.writeRings(frame, 0f, 0x0A0B0C, 0.6f, rings);
            for (int i = 0; i < normals.length; i += 4) {
                assertEquals(128, normals[i] & 0xFF);
                assertEquals(128, normals[i + 1] & 0xFF);
                assertEquals(255, normals[i + 2] & 0xFF);
                assertEquals(0, rings[i + 3]);
                assertEquals((byte) 0x0A, rings[i]);
            }
        }
    }

    @Test
    void ringsShowOnlyWhereTheWaterIsDisturbed() {
        byte[] rings = new byte[FRAME_PX * FRAME_PX * 4];
        int maxAlpha = Math.round(0.6f * 255f);
        int shown = 0, calm = 0;
        for (int frame = 0; frame < RainRipples.FRAMES; frame++) {
            RIPPLES.writeRings(frame, 1f, 0, 0.6f, rings);
            for (int i = 3; i < rings.length; i += 4) {
                int alpha = rings[i] & 0xFF;
                assertTrue(alpha <= maxAlpha, "alpha " + alpha);
                if (alpha > maxAlpha / 4) shown++; else calm++;
            }
        }
        assertTrue(shown > 0, "some rings show");
        assertTrue(calm > shown, "most of the water stays clear: " + shown + " shown, " + calm + " calm");
    }

    @Test
    void lighterRainDrawsFainterRings() {
        byte[] strong = new byte[FRAME_PX * FRAME_PX * 4];
        byte[] light = new byte[FRAME_PX * FRAME_PX * 4];
        RIPPLES.writeRings(5, 1f, 0, 0.6f, strong);
        RIPPLES.writeRings(5, 0.3f, 0, 0.6f, light);
        for (int i = 3; i < strong.length; i += 4) {
            assertTrue((light[i] & 0xFF) <= (strong[i] & 0xFF));
        }
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
    void theAnimationLoopsAtItsFrameRate() {
        assertEquals(RainRipples.FRAMES, Math.round(RainRipples.FPS * RainRipples.LOOP_SECONDS));
        assertEquals(0, RainRipples.frameAt(0));
        assertEquals(0, RainRipples.frameAt(RainRipples.LOOP_SECONDS));
        assertEquals(1, RainRipples.frameAt(1.0 / RainRipples.FPS + 1.0e-6));
        int wrapped = RainRipples.frameAt(-0.01);
        assertEquals(RainRipples.FRAMES - 1, wrapped);
    }
}
