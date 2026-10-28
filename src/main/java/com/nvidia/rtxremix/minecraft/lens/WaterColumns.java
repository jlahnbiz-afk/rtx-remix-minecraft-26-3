/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.lens;

import net.minecraft.core.BlockPos;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.FluidState;
import org.lwjgl.opengl.GL45C;
import org.lwjgl.system.MemoryUtil;

import java.nio.FloatBuffer;
import java.util.Arrays;

/**
 * The columns around the camera as the lens's caustics read them: in one texel each, the top
 * of the water standing in the column, the top of the floor beneath it, the top of whatever
 * shades the column from the sun, and a key naming the column. A texel holds the column whose
 * block coordinates are its own modulo {@link #SIZE}, so the window follows the camera without
 * moving anything; the key tells the shader whether a texel still holds the column it asks for.
 * The texels lie in rows in a buffer texture, so an upload is a plain buffer write and leaves
 * the game's pixel transfer state alone: saving and restoring that state cost more than the
 * measuring.
 *
 * <p>Two rows are measured each frame: the next of the whole window, which is fresh again every
 * {@code SIZE} frames, a few seconds, and the next of those within {@link #NEAR} blocks of the
 * camera, fresh again in under a second. Moving only brings in columns at the window's far
 * edge, so the columns near the camera stay measured; the wait shows only after a block
 * changes or the camera jumps. Render thread only.
 */
final class WaterColumns {

    /**
     * Columns across the window, which reaches half of it from the camera each way: far enough
     * that the caustics' web has settled to its mean before their floors go unmeasured.
     */
    static final int SIZE = 512;
    private static final int HALF = SIZE / 2;
    /** Rows each side of the camera's that are measured again more often. */
    private static final int NEAR = 32;
    private static final int TEXEL_BYTES = 16;
    /**
     * Blocks of water below the surface searched for a floor. The caustics fade out by this
     * depth, so the floor of deeper water, which reads as this deep, shows none.
     */
    static final int MAX_DEPTH = 32;
    /** The water and floor heights of a column holding no water. */
    private static final float NO_WATER = -1.0e4f;

    private final BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
    private final FloatBuffer row = MemoryUtil.memAllocFloat(SIZE * 4);
    private int buffer;
    private int texture;
    private int nextRow;
    private int nextNearRow;
    /** Water columns in each texel row as last measured, and in the whole window. */
    private final int[] rowWater = new int[SIZE];
    private int waterTotal;
    /** The deepest water in each texel row as last measured, surface to floor. */
    private final float[] rowDepth = new float[SIZE];
    private float rowDeepest;

    /** Measures the next rows of the window around the camera and uploads them. */
    void update(Level level, double cameraX, double cameraZ) {
        if (texture == 0) {
            buffer = GL45C.glCreateBuffers();
            GL45C.glNamedBufferStorage(buffer, (long) SIZE * SIZE * TEXEL_BYTES, GL45C.GL_DYNAMIC_STORAGE_BIT);
            GL45C.glClearNamedBufferData(buffer, GL45C.GL_RGBA32F, GL45C.GL_RGBA, GL45C.GL_FLOAT, (FloatBuffer) null);
            texture = GL45C.glCreateTextures(GL45C.GL_TEXTURE_BUFFER);
            GL45C.glTextureBuffer(texture, GL45C.GL_RGBA32F, buffer);
        }
        int left = Mth.floor(cameraX) - HALF;
        int back = Mth.floor(cameraZ) - HALF;
        measureRow(level, left, back, nextRow);
        nextRow = (nextRow + 1) % SIZE;
        // Each row is a texel row of the window, at its block coordinate modulo SIZE.
        measureRow(level, left, back, Math.floorMod(Mth.floor(cameraZ) - NEAR + nextNearRow, SIZE));
        nextNearRow = (nextNearRow + 1) % (2 * NEAR + 1);
    }

    private void measureRow(Level level, int left, int back, int texelRow) {
        int z = back + Math.floorMod(texelRow - back, SIZE);
        row.clear();
        int found = 0;
        rowDeepest = 0.0f;
        for (int texel = 0; texel < SIZE; texel++) {
            found += measure(level, left + Math.floorMod(texel - left, SIZE), z) ? 1 : 0;
        }
        waterTotal += found - rowWater[texelRow];
        rowWater[texelRow] = found;
        rowDepth[texelRow] = rowDeepest;
        row.flip();
        GL45C.glNamedBufferSubData(buffer, (long) texelRow * SIZE * TEXEL_BYTES, row);
    }

    /** Whether any column of the window held water when last measured. */
    boolean anyWater() {
        return waterTotal > 0;
    }

    /** The deepest water in the window as last measured, surface to floor, in blocks. */
    float deepest() {
        float deepest = 0.0f;
        for (float depth : rowDepth) {
            deepest = Math.max(deepest, depth);
        }
        return deepest;
    }

    /** The buffer texture of the window, row after row of {@link #SIZE} texels. */
    int texture() {
        return texture;
    }

    void delete() {
        if (texture != 0) {
            GL45C.glDeleteTextures(texture);
            GL45C.glDeleteBuffers(buffer);
            texture = 0;
            buffer = 0;
        }
        nextRow = 0;
        Arrays.fill(rowWater, 0);
        Arrays.fill(rowDepth, 0.0f);
        waterTotal = 0;
    }

    /** The key a texel holding column x, z carries; the shader computes the same. */
    static float key(int x, int z) {
        int shift = Integer.numberOfTrailingZeros(SIZE);
        return (((x >> shift) & 255) << 8 | ((z >> shift) & 255)) + 1;
    }

    /** Measures a column into the row; whether it holds water. */
    private boolean measure(Level level, int x, int z) {
        int top = level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z);
        float water = NO_WATER;
        float floor = NO_WATER;
        float shade = top;
        pos.set(x, top - 1, z);
        FluidState fluid = level.getBlockState(pos).getFluidState();
        if (fluid.is(FluidTags.WATER)) {
            water = top - 1 + fluid.getHeight(level, pos);
            int y = top - 1;
            int bottom = Math.max(level.getMinY(), y - MAX_DEPTH);
            // Down through the water and what stands in it without blocking, as seagrass does.
            for (; y > bottom; y--) {
                pos.setY(y);
                BlockState state = level.getBlockState(pos);
                if (!state.getFluidState().is(FluidTags.WATER) || !state.getCollisionShape(level, pos).isEmpty()) {
                    break;
                }
            }
            floor = y + 1;
            // The water itself does not shade its floor.
            shade = floor;
            rowDeepest = Math.max(rowDeepest, water - floor);
        }
        row.put(water).put(floor).put(shade).put(key(x, z));
        return water != NO_WATER;
    }

}
