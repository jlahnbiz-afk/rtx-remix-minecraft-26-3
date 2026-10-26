/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.texture;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WaterNormalsTest {

    @Test
    void bc4BlocksDecodeBothPaletteModes() {
        byte[] out = new byte[16];
        // e0 > e1: eight interpolated values. Indices 0..7 across the first eight texels.
        byte[] eight = {(byte) 210, 70, (byte) 0x88, (byte) 0xC6, (byte) 0xFA, 0, 0, 0};
        WaterNormals.decodeBc4(eight, 0, out);
        assertEquals(210, out[0] & 0xFF);
        assertEquals(70, out[1] & 0xFF);
        assertEquals((6 * 210 + 70) / 7, out[2] & 0xFF);
        assertEquals((210 + 6 * 70) / 7, out[7] & 0xFF);
        assertEquals(210, out[8] & 0xFF);
        // e0 <= e1: six interpolated values, then 0 and 255.
        byte[] six = {50, (byte) 200, (byte) 0x88, (byte) 0xC6, (byte) 0xFA, 0, 0, 0};
        WaterNormals.decodeBc4(six, 0, out);
        assertEquals((4 * 50 + 200) / 5, out[2] & 0xFF);
        assertEquals(0, out[6] & 0xFF);
        assertEquals(255, out[7] & 0xFF);
    }

    @Test
    void framesSplitInSheetOrder() {
        // A 12 x 10 sheet of 4-texel frames, one BC5 block per frame, each a constant value.
        int frameSize = 4;
        int width = 12 * frameSize, height = 10 * frameSize;
        byte[] blocks = new byte[(width / 4) * (height / 4) * 16];
        for (int frame = 0; frame < 120; frame++) {
            int offset = frame * 16;
            blocks[offset] = (byte) frame;
            blocks[offset + 1] = (byte) frame;
            blocks[offset + 8] = (byte) (255 - frame);
            blocks[offset + 9] = (byte) (255 - frame);
        }
        byte[][] frames = WaterNormals.splitFrames(blocks, width, height);
        assertEquals(120, frames.length);
        assertEquals(37, frames[37][0] & 0xFF);
        assertEquals(255 - 37, frames[37][1] & 0xFF);
        assertEquals(119, frames[119][frames[119].length - 2] & 0xFF);
    }

    @Test
    void withoutRainTheOceanFrameIsUploadedAsItIs() {
        byte[] ocean = {(byte) 140, (byte) 120, (byte) 128, (byte) 128};
        byte[] rgba = new byte[8];
        WaterNormals.compose(ocean, null, 0f, rgba);
        assertArrayEquals(new byte[] {(byte) 140, (byte) 120, 0, (byte) 255, (byte) 128, (byte) 128, 0, (byte) 255}, rgba);
    }

    @Test
    void ringsTiltCalmWaterAndAddToWaves() {
        byte[] calm = {(byte) 128, (byte) 128};
        byte[] ring = {(byte) 160, (byte) 128};
        byte[] rgba = new byte[4];
        WaterNormals.compose(calm, ring, 1f, rgba);
        assertTrue((rgba[0] & 0xFF) > 150, "a ring tilts calm water: " + (rgba[0] & 0xFF));
        WaterNormals.compose(calm, ring, 0.25f, rgba);
        int light = rgba[0] & 0xFF;
        assertTrue(light > 128 && light < 150, "light rain tilts it less: " + light);
        byte[] wave = {(byte) 150, (byte) 128};
        WaterNormals.compose(wave, ring, 1f, rgba);
        assertTrue((rgba[0] & 0xFF) > 160, "a ring adds to a wave's own slope: " + (rgba[0] & 0xFF));
    }
}
