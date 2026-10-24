/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.texture;

import java.util.Arrays;
import java.util.SplittableRandom;

/**
 * A looping normal-map flipbook of raindrop rings on still water, laid out as the
 * runtime plays a sprite sheet: frames left to right, then top to bottom.
 *
 * <p>Each frame tiles {@code tileBlocks} blocks of water and wraps at its edges, so the
 * water material's world-space UVs repeat it seamlessly. Drops land at random places and
 * times within one loop; a drop whose ring is still spreading when the loop ends carries
 * over into the frames at its start, so the last frame runs into the first.
 */
public final class RainRipples {

    public static final int COLS = 8;
    public static final int ROWS = 8;
    public static final int FRAMES = COLS * ROWS;
    public static final float LOOP_SECONDS = 2f;
    public static final int FPS = Math.round(FRAMES / LOOP_SECONDS);

    /** Drops landing per square block per second in a steady rain. */
    private static final float DROPS_PER_BLOCK_SECOND = 1.5f;
    // Rings are drawn at Minecraft's texel scale, sixteen to a block: a wavelength under
    // about three texels would alias into noise, so these are stylised, not real drop sizes.
    private static final float RING_SPEED = 0.5f;    // blocks per second
    private static final float LIFETIME = 0.9f;      // seconds
    private static final float WAVELENGTH = 0.2f;    // blocks
    private static final float AMPLITUDE = 0.01f;    // blocks of surface height
    private static final float BASE_WIDTH = 0.06f;   // blocks
    private static final float WIDTH_GROWTH = 0.15f; // added width per block of radius

    private RainRipples() { }

    /**
     * RGBA8 tangent-space normals for the whole sheet, {@code COLS * framePx} wide and
     * {@code ROWS * framePx} tall: x along U in red, y along V in green, up in blue.
     */
    public static byte[] normalSheet(int framePx, float tileBlocks, long seed) {
        int sheetW = COLS * framePx;
        byte[] sheet = new byte[sheetW * ROWS * framePx * 4];
        float[] height = new float[framePx * framePx];
        float[][] drops = drops(tileBlocks, seed);
        float texel = tileBlocks / framePx;
        for (int frame = 0; frame < FRAMES; frame++) {
            heightField(height, framePx, texel, drops, frame * (LOOP_SECONDS / FRAMES));
            int originX = (frame % COLS) * framePx;
            int originY = (frame / COLS) * framePx;
            writeNormals(sheet, sheetW, originX, originY, height, framePx, texel);
        }
        return sheet;
    }

    /** Each drop as {x, z, landing time}, in blocks and seconds within one loop. */
    static float[][] drops(float tileBlocks, long seed) {
        SplittableRandom random = new SplittableRandom(seed);
        int count = Math.max(1, Math.round(DROPS_PER_BLOCK_SECOND * tileBlocks * tileBlocks * LOOP_SECONDS));
        float[][] drops = new float[count][];
        for (int i = 0; i < count; i++) {
            drops[i] = new float[] {
                    (float) random.nextDouble() * tileBlocks,
                    (float) random.nextDouble() * tileBlocks,
                    (float) random.nextDouble() * LOOP_SECONDS};
        }
        return drops;
    }

    /** Surface height in blocks over one frame, wrapping at the tile's edges. */
    static void heightField(float[] height, int framePx, float texel, float[][] drops, float time) {
        Arrays.fill(height, 0f);
        for (float[] drop : drops) {
            float age = time - drop[2];
            if (age < 0f) age += LOOP_SECONDS;
            if (age >= LIFETIME) continue;
            float radius = RING_SPEED * age;
            float width = BASE_WIDTH + WIDTH_GROWTH * radius;
            float fade = 1f - age / LIFETIME;
            float amplitude = AMPLITUDE * fade * fade;
            float reach = radius + 3f * width;
            int span = (int) Math.ceil(reach / texel);
            int centerX = (int) Math.floor(drop[0] / texel);
            int centerZ = (int) Math.floor(drop[1] / texel);
            for (int dz = -span; dz <= span; dz++) {
                for (int dx = -span; dx <= span; dx++) {
                    float px = (centerX + dx + 0.5f) * texel;
                    float pz = (centerZ + dz + 0.5f) * texel;
                    float r = (float) Math.hypot(px - drop[0], pz - drop[1]);
                    float offset = r - radius;
                    if (Math.abs(offset) > 3f * width) continue;
                    float envelope = (float) Math.exp(-(offset * offset) / (width * width));
                    float wave = (float) Math.cos(2.0 * Math.PI * offset / WAVELENGTH);
                    int x = Math.floorMod(centerX + dx, framePx);
                    int z = Math.floorMod(centerZ + dz, framePx);
                    height[z * framePx + x] += amplitude * envelope * wave;
                }
            }
        }
    }

    private static void writeNormals(byte[] sheet, int sheetW, int originX, int originY,
                                     float[] height, int framePx, float texel) {
        for (int z = 0; z < framePx; z++) {
            for (int x = 0; x < framePx; x++) {
                float left = height[z * framePx + Math.floorMod(x - 1, framePx)];
                float right = height[z * framePx + Math.floorMod(x + 1, framePx)];
                float up = height[Math.floorMod(z - 1, framePx) * framePx + x];
                float down = height[Math.floorMod(z + 1, framePx) * framePx + x];
                float nx = -(right - left) / (2f * texel);
                float ny = -(down - up) / (2f * texel);
                float nz = 1f;
                float length = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
                int i = ((originY + z) * sheetW + originX + x) * 4;
                sheet[i] = encode(nx / length);
                sheet[i + 1] = encode(ny / length);
                sheet[i + 2] = encode(nz / length);
                sheet[i + 3] = (byte) 0xFF;
            }
        }
    }

    private static byte encode(float component) {
        return (byte) Math.round((component * 0.5f + 0.5f) * 255f);
    }
}
