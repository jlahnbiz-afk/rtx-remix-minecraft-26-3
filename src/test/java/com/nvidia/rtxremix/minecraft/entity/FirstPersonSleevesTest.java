/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.entity;

import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FirstPersonSleevesTest {

    private static final int SKIN = 64;

    @Test
    void seeThroughSleeveTexelsShowTheArmBeneath() {
        byte[] skin = new byte[SKIN * SKIN * 4];
        set(skin, SKIN, 40, 16, 0xFF, 0x20, 0x10, 0xFF); // right arm
        set(skin, SKIN, 40, 32, 0x00, 0x00, 0x00, 0x00); // right sleeve, empty
        set(skin, SKIN, 33, 50, 0x10, 0xFF, 0x20, 0xFF); // left arm
        set(skin, SKIN, 49, 50, 0x00, 0x00, 0x00, 0x00); // left sleeve, empty

        FirstPersonSleeves.flatten(skin, SKIN, SKIN);

        assertArrayEquals(new int[] {0xFF, 0x20, 0x10, 0xFF}, get(skin, SKIN, 40, 32));
        assertArrayEquals(new int[] {0x10, 0xFF, 0x20, 0xFF}, get(skin, SKIN, 49, 50));
    }

    @Test
    void sleevesBlendOverTheArmAsVanillaDraws() {
        byte[] skin = new byte[SKIN * SKIN * 4];
        // Opaque sleeve: kept.
        set(skin, SKIN, 41, 17, 0x00, 0x00, 0xFF, 0xFF);
        set(skin, SKIN, 41, 33, 0xFF, 0xFF, 0xFF, 0xFF);
        // Half-transparent white sleeve over a black arm: grey.
        set(skin, SKIN, 42, 17, 0x00, 0x00, 0x00, 0xFF);
        set(skin, SKIN, 42, 33, 0xFF, 0xFF, 0xFF, 0x80);
        // Below vanilla's cut: discarded, so the arm shows.
        set(skin, SKIN, 43, 17, 0x00, 0xFF, 0x00, 0xFF);
        set(skin, SKIN, 43, 33, 0xFF, 0x00, 0x00, 0x10);

        FirstPersonSleeves.flatten(skin, SKIN, SKIN);

        assertArrayEquals(new int[] {0xFF, 0xFF, 0xFF, 0xFF}, get(skin, SKIN, 41, 33));
        assertArrayEquals(new int[] {0x80, 0x80, 0x80, 0xFF}, get(skin, SKIN, 42, 33));
        assertArrayEquals(new int[] {0x00, 0xFF, 0x00, 0xFF}, get(skin, SKIN, 43, 33));
    }

    @Test
    void theArmAndTheRestOfTheSkinAreUntouched() {
        byte[] skin = new byte[SKIN * SKIN * 4];
        set(skin, SKIN, 40, 16, 0xFF, 0x20, 0x10, 0xFF);
        set(skin, SKIN, 8, 8, 0x00, 0x00, 0x00, 0x00); // face, outside every sleeve
        set(skin, SKIN, 40, 8, 0x00, 0x00, 0x00, 0x00); // hat
        byte[] before = skin.clone();

        FirstPersonSleeves.flatten(skin, SKIN, SKIN);

        assertArrayEquals(get(before, SKIN, 40, 16), get(skin, SKIN, 40, 16));
        assertArrayEquals(get(before, SKIN, 8, 8), get(skin, SKIN, 8, 8));
        assertArrayEquals(get(before, SKIN, 40, 8), get(skin, SKIN, 40, 8));
    }

    @Test
    void aHighResolutionSkinScalesTheLayout() {
        int size = SKIN * 2;
        byte[] skin = new byte[size * size * 4];
        set(skin, size, 81, 33, 0x12, 0x34, 0x56, 0xFF); // right arm texel (40.5, 16.5)
        set(skin, size, 81, 65, 0x00, 0x00, 0x00, 0x00); // the sleeve texel over it

        FirstPersonSleeves.flatten(skin, size, size);

        assertArrayEquals(new int[] {0x12, 0x34, 0x56, 0xFF}, get(skin, size, 81, 65));
    }

    @Test
    void aSkinOutsideTheSquareLayoutIsLeftAlone() {
        byte[] legacy = new byte[SKIN * 32 * 4];
        set(legacy, SKIN, 40, 16, 0xFF, 0x20, 0x10, 0xFF);
        byte[] before = legacy.clone();

        FirstPersonSleeves.flatten(legacy, SKIN, 32);

        assertArrayEquals(before, legacy);
    }

    @Test
    void theFlattenedTextureMapsBackToItsSkin() {
        Identifier skin = Identifier.withDefaultNamespace("skins/0123abcd");
        Identifier flattened = FirstPersonSleeves.flattenedTexture(skin);

        assertSame(flattened, FirstPersonSleeves.flattenedTexture(skin));
        assertTrue(FirstPersonSleeves.isFlattened(flattened));
        assertFalse(FirstPersonSleeves.isFlattened(skin));
        assertEquals(skin, FirstPersonSleeves.sourceTexture(flattened));
        assertEquals(skin, FirstPersonSleeves.sourceTexture(skin));
    }

    private static void set(byte[] rgba, int width, int x, int y, int r, int g, int b, int a) {
        int i = (y * width + x) * 4;
        rgba[i] = (byte) r;
        rgba[i + 1] = (byte) g;
        rgba[i + 2] = (byte) b;
        rgba[i + 3] = (byte) a;
    }

    private static int[] get(byte[] rgba, int width, int x, int y) {
        int i = (y * width + x) * 4;
        return new int[] {rgba[i] & 0xFF, rgba[i + 1] & 0xFF, rgba[i + 2] & 0xFF, rgba[i + 3] & 0xFF};
    }
}
