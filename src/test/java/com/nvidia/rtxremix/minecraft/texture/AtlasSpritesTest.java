/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.texture;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AtlasSpritesTest {

    private static final int ATLAS = 8;

    @Test
    void bordersRepeatTheEdgeTexelsAndCornersTheCornerTexels() {
        // A 2x2 sprite at (3, 3) with a 2-texel border, texels 1..4 in row order.
        byte[] atlas = new byte[ATLAS * ATLAS];
        atlas[3 * ATLAS + 3] = 1;
        atlas[3 * ATLAS + 4] = 2;
        atlas[4 * ATLAS + 3] = 3;
        atlas[4 * ATLAS + 4] = 4;

        new AtlasSprites.Padding(List.<int[]>of(new int[]{3, 3, 2, 2, 2}), ATLAS, ATLAS).fill(atlas, 1);

        int[][] expected = {
                {0, 0, 0, 0, 0, 0, 0, 0},
                {0, 1, 1, 1, 2, 2, 2, 0},
                {0, 1, 1, 1, 2, 2, 2, 0},
                {0, 1, 1, 1, 2, 2, 2, 0},
                {0, 3, 3, 3, 4, 4, 4, 0},
                {0, 3, 3, 3, 4, 4, 4, 0},
                {0, 3, 3, 3, 4, 4, 4, 0},
                {0, 0, 0, 0, 0, 0, 0, 0},
        };
        for (int y = 0; y < ATLAS; y++) {
            for (int x = 0; x < ATLAS; x++) {
                assertEquals(expected[y][x], atlas[y * ATLAS + x], "texel " + x + "," + y);
            }
        }
    }

    @Test
    void copiesWholeTexelsAndStopsAtTheAtlasEdge() {
        // A 1x1 RGBA sprite in the corner: its border would reach past the atlas on two sides.
        byte[] atlas = new byte[ATLAS * ATLAS * 4];
        byte[] texel = {10, 20, 30, 40};
        System.arraycopy(texel, 0, atlas, 0, 4);

        new AtlasSprites.Padding(List.<int[]>of(new int[]{0, 0, 1, 1, 1}), ATLAS, ATLAS).fill(atlas, 4);

        for (int index : new int[]{1, ATLAS, ATLAS + 1}) {
            for (int channel = 0; channel < 4; channel++) {
                assertEquals(texel[channel], atlas[index * 4 + channel]);
            }
        }
        assertEquals(0, atlas[2 * 4]);
    }

    @Test
    void rejectsACopySmallerThanItsAtlas() {
        AtlasSprites.Padding padding =
                new AtlasSprites.Padding(List.<int[]>of(new int[]{1, 1, 1, 1, 1}), ATLAS, ATLAS);
        assertThrows(IllegalArgumentException.class, () -> padding.fill(new byte[ATLAS * ATLAS], 4));
    }
}
