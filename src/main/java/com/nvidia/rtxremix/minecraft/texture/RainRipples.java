/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.texture;

import java.util.Arrays;
import java.util.SplittableRandom;

/**
 * A looping animation of raindrop rings on still water, one frame per {@link #FPS}.
 *
 * <p>Each frame tiles {@code tileBlocks} blocks and wraps at its edges, so world-space
 * UVs repeat it seamlessly. Drops land at random places and times within one loop; a
 * drop whose ring is still spreading when the loop ends carries over into the frames at
 * its start, so the last frame runs into the first. Every frame is kept finished at full
 * strength, so steady rain costs a copy per frame; while the rain comes and goes, the
 * rings are scaled down from those.
 */
public final class RainRipples {

    public static final int FRAMES = 64;
    public static final float LOOP_SECONDS = 2f;
    public static final int FPS = Math.round(FRAMES / LOOP_SECONDS);

    /** Drops landing per square block per second in a steady rain. */
    private static final float DROPS_PER_BLOCK_SECOND = 10f;
    // Rings are drawn at Minecraft's texel scale, sixteen to a block: a wavelength under
    // about three texels would alias into noise, so these are stylised, not real drop sizes.
    private static final float RING_SPEED = 0.6f;    // blocks per second
    private static final float LIFETIME = 0.45f;     // seconds
    private static final float WAVELENGTH = 0.2f;    // blocks
    private static final float AMPLITUDE = 0.01f;    // blocks of surface height
    private static final float BASE_WIDTH = 0.05f;   // blocks
    private static final float WIDTH_GROWTH = 0.15f; // added width per block of radius
    /** Slope at which a ring reads as fully disturbed water. */
    private static final float FULL_RING_SLOPE = 0.35f;

    private final int framePx;
    /** Per frame, RGBA8 tangent-space normals at full strength. */
    private final byte[][] normals;
    /** Per frame, how disturbed the water is at full strength, 0 to 255. */
    private final byte[][] disturbance;

    private RainRipples(int framePx, byte[][] normals, byte[][] disturbance) {
        this.framePx = framePx;
        this.normals = normals;
        this.disturbance = disturbance;
    }

    /** Simulates every frame of the loop over a tile {@code framePx} texels across. */
    public static RainRipples generate(int framePx, float tileBlocks, long seed) {
        float[][] drops = drops(tileBlocks, seed);
        float texel = tileBlocks / framePx;
        float[] height = new float[framePx * framePx];
        byte[][] normals = new byte[FRAMES][];
        byte[][] disturbance = new byte[FRAMES][];
        for (int frame = 0; frame < FRAMES; frame++) {
            heightField(height, framePx, texel, drops, frame * (LOOP_SECONDS / FRAMES));
            normals[frame] = new byte[framePx * framePx * 4];
            disturbance[frame] = new byte[framePx * framePx];
            finish(height, framePx, texel, normals[frame], disturbance[frame]);
        }
        return new RainRipples(framePx, normals, disturbance);
    }

    public int framePx() {
        return framePx;
    }

    /** The frame showing at {@code seconds} into the animation. */
    public static int frameAt(double seconds) {
        return (int) Math.floorMod((long) Math.floor(seconds * FPS), (long) FRAMES);
    }

    /**
     * Writes {@code frame} as RGBA8 tangent-space normals, the rings' slopes scaled by
     * {@code strength}: x along U in red, y along V in green, up in blue.
     */
    public void writeNormals(int frame, float strength, byte[] rgba) {
        byte[] full = normals[frame];
        if (strength >= 1f) {
            System.arraycopy(full, 0, rgba, 0, full.length);
            return;
        }
        float scale = Math.max(0f, strength);
        for (int i = 0; i < full.length; i += 4) {
            // A normal (-sx, -sy, 1) / length at full strength scales its slopes to (-s sx, -s sy, 1).
            float z = decode(full[i + 2]);
            float nx = decode(full[i]) / z * scale;
            float ny = decode(full[i + 1]) / z * scale;
            float length = (float) Math.sqrt(nx * nx + ny * ny + 1f);
            rgba[i] = encode(nx / length);
            rgba[i + 1] = encode(ny / length);
            rgba[i + 2] = encode(1f / length);
            rgba[i + 3] = (byte) 0xFF;
        }
    }

    /**
     * Writes {@code frame} as how disturbed the water is at each texel, 0 to
     * {@code maxAlpha}, into the alpha of RGBA8 texels whose colour is {@code rgb}.
     */
    public void writeRings(int frame, float strength, int rgb, float maxAlpha, byte[] rgba) {
        byte[] full = disturbance[frame];
        // 16.16 fixed point, so the copy stays a few integer operations per texel.
        int scale = Math.round(Math.max(0f, Math.min(1f, strength)) * Math.max(0f, Math.min(1f, maxAlpha)) * 65536f);
        byte r = (byte) (rgb >> 16), g = (byte) (rgb >> 8), b = (byte) rgb;
        for (int i = 0; i < full.length; i++) {
            rgba[i * 4] = r;
            rgba[i * 4 + 1] = g;
            rgba[i * 4 + 2] = b;
            rgba[i * 4 + 3] = (byte) (((full[i] & 0xFF) * scale + 32768) >>> 16);
        }
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

    /** Turns a height field into full-strength normals and disturbance, by central differences. */
    private static void finish(float[] height, int framePx, float texel, byte[] normals, byte[] disturbance) {
        for (int z = 0; z < framePx; z++) {
            for (int x = 0; x < framePx; x++) {
                float left = height[z * framePx + Math.floorMod(x - 1, framePx)];
                float right = height[z * framePx + Math.floorMod(x + 1, framePx)];
                float up = height[Math.floorMod(z - 1, framePx) * framePx + x];
                float down = height[Math.floorMod(z + 1, framePx) * framePx + x];
                float sx = (right - left) / (2f * texel);
                float sy = (down - up) / (2f * texel);
                float length = (float) Math.sqrt(sx * sx + sy * sy + 1f);
                int i = z * framePx + x;
                normals[i * 4] = encode(-sx / length);
                normals[i * 4 + 1] = encode(-sy / length);
                normals[i * 4 + 2] = encode(1f / length);
                normals[i * 4 + 3] = (byte) 0xFF;
                float disturbed = Math.min(1f, (float) Math.sqrt(sx * sx + sy * sy) / FULL_RING_SLOPE);
                disturbance[i] = (byte) Math.round(disturbed * 255f);
            }
        }
    }

    private static float decode(byte component) {
        return (component & 0xFF) / 127.5f - 1f;
    }

    private static byte encode(float component) {
        return (byte) Math.round((component * 0.5f + 0.5f) * 255f);
    }
}
