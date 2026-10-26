/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.sky;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SkyLayerTexturesTest {

    private static final int SIZE = 128;

    @Test
    void noCoverageNoCirrus() {
        byte[] rgba = SkyLayerTextures.cirrus(SIZE, 0.0f, 1L);
        for (int i = 0; i < rgba.length; i += 4) {
            assertEquals(0, rgba[i]);
        }
    }

    @Test
    void cirrusStaysInsideTheSkyCircleAndFadesAtTheHorizon() {
        byte[] rgba = SkyLayerTextures.cirrus(SIZE, 1.0f, 1L);
        int lit = 0;
        for (int y = 0; y < SIZE; y++) {
            for (int x = 0; x < SIZE; x++) {
                float u = (x + 0.5f) / SIZE * 2 - 1;
                float v = (y + 0.5f) / SIZE * 2 - 1;
                int value = rgba[(y * SIZE + x) * 4] & 0xFF;
                float r2 = u * u + v * v;
                if (r2 >= 1.0f) {
                    assertEquals(0, value, "outside the sky at " + x + "," + y);
                } else if (r2 >= 0.98f) {
                    assertTrue(value < 8, "faint at the horizon at " + x + "," + y + ": " + value);
                }
                if (value > 0) lit++;
            }
        }
        assertTrue(lit > SIZE * SIZE / 20, "some streaks show at full coverage: " + lit);
    }

    @Test
    void moreCoverageMoreCirrus() {
        assertTrue(litTexels(SkyLayerTextures.cirrus(SIZE, 0.8f, 1L))
                > litTexels(SkyLayerTextures.cirrus(SIZE, 0.3f, 1L)));
    }

    @Test
    void auroraCurtainsFadeAtTheirEndsAndAreDeterministic() {
        int width = 256, band = 32, bands = 3;
        byte[] rgba = SkyLayerTextures.aurora(width, band, bands, 5L);
        assertArrayEquals(rgba, SkyLayerTextures.aurora(width, band, bands, 5L));
        for (int y = 0; y < band * bands; y++) {
            assertEquals(0, rgba[(y * width) * 4 + 1], "left end dark");
            assertEquals(0, rgba[(y * width + width - 1) * 4 + 1], "right end dark");
        }
        assertTrue(litTexels(rgba) > 0, "the curtains glow somewhere");
    }

    private static int litTexels(byte[] rgba) {
        int lit = 0;
        for (int i = 0; i < rgba.length; i += 4) {
            if ((rgba[i] & 0xFF) + (rgba[i + 1] & 0xFF) + (rgba[i + 2] & 0xFF) > 0) lit++;
        }
        return lit;
    }
}
