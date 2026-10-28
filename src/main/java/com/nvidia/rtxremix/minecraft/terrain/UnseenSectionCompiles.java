/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.terrain;

import java.util.Arrays;
import java.util.function.LongConsumer;
import java.util.function.LongFunction;
import net.minecraft.client.SectionUpdateTracker;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.chunk.CompiledSectionMesh;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.chunk.LevelChunk;

/**
 * Compiles the sections around the camera that vanilla leaves dirty because they are out of
 * view. Vanilla compiles a section once it comes into view, but the path tracer needs the ground
 * behind and beside the camera for its shadows, reflections and light, and LOD terrain filled the
 * columns that had none, so turning round showed LOD terrain at arm's length, or a hole, until
 * vanilla caught up. A few sections a frame, nearest first, while vanilla's own queue is short,
 * so what is in view still compiles first. Sections holding only air are left to vanilla.
 */
public final class UnseenSectionCompiles {

    /** 0 turns the sweep off. */
    private static final int MAX_PER_FRAME =
            Math.max(0, Integer.getInteger("rtxremix.chunk.unseenCompilesPerFrame", 8));
    /** Vanilla's compile queue length from which its own sections are left to go first. */
    private static final int MAX_QUEUED = 16;
    private static final int EXAMINED_PER_FRAME = 4096;
    /** Frames to rest after a sweep of the whole view area found nothing to compile. */
    private static final int REST_FRAMES = 30;

    /** Column offsets from the camera's column, nearest first, packed as dx in the high half. */
    private long[] offsets = new long[0];
    private int radius = -1;
    private int centerX = Integer.MIN_VALUE;
    private int centerZ = Integer.MIN_VALUE;
    private int column;
    private int layer;
    private int found;
    private int resting;

    /**
     * Hands {@code compile} up to a few dirty sections out of view, nearest first, and marks
     * them clean as vanilla does with those it compiles.
     */
    public void schedule(ClientLevel level, LongFunction<SectionRenderDispatcher.RenderSection> sections,
                         SectionUpdateTracker tracker, Frustum frustum, int viewDistance, int minSectionY,
                         int maxSectionY, SectionPos camera, int queued, LongConsumer compile) {
        if (viewDistance != radius) {
            offsets = offsetsWithin(viewDistance);
            radius = viewDistance;
            restart();
        }
        if (camera.x() != centerX || camera.z() != centerZ) {
            centerX = camera.x();
            centerZ = camera.z();
            restart();
        }
        if (resting > 0) {
            resting--;
            return;
        }
        if (MAX_PER_FRAME == 0 || queued >= MAX_QUEUED) return;

        int span = maxSectionY - minSectionY + 1;
        int scheduled = 0;
        int examined = 0;
        while (column < offsets.length && scheduled < MAX_PER_FRAME && examined < EXAMINED_PER_FRAME) {
            int cx = centerX + (int) (offsets[column] >> 32);
            int cz = centerZ + (int) offsets[column];
            LevelChunk chunk = level.getChunkSource().getChunk(cx, cz, false);
            for (; chunk != null && layer < 2 * span && scheduled < MAX_PER_FRAME
                    && examined < EXAMINED_PER_FRAME; layer++) {
                // From the camera's height outward, alternately up and down.
                int sy = camera.y() + ((layer & 1) == 0 ? layer >> 1 : -((layer >> 1) + 1));
                if (sy < minSectionY || sy > maxSectionY) continue;
                examined++;
                if (chunk.getSection(chunk.getSectionIndexFromSectionY(sy)).hasOnlyAir()
                        || inView(frustum, camera, cx, sy, cz)) continue;
                long node = SectionPos.asLong(cx, sy, cz);
                SectionRenderDispatcher.RenderSection section = sections.apply(node);
                SectionUpdateTracker.SectionDirtyState state = tracker.getDirtyState(node);
                if (section == null || state == null || !state.isDirty()) continue;
                // The gate vanilla puts on a first compile: neighbours and light ready.
                if (section.sectionMesh.get() == CompiledSectionMesh.UNCOMPILED
                        && (!level.getLightEngine().lightOnInColumn(SectionPos.getZeroNode(node))
                                || !tracker.hasAllNeighbors(level, node))) continue;
                compile.accept(node);
                state.setNotDirty();
                scheduled++;
            }
            if (chunk != null && layer < 2 * span) break;
            column++;
            layer = 0;
        }
        found += scheduled;
        if (column >= offsets.length) {
            // A sweep that compiled nothing has caught up with the view area; rest, then look again.
            if (found == 0) resting = REST_FRAMES;
            restart();
        }
    }

    /**
     * Whether a section may be on screen: beside the camera's own, or a corner or its centre in
     * the frustum. Those in view vanilla compiles, or leaves because they are hidden, as caves
     * under the ground are; compiling those rebuilt the columns on screen one section after
     * another, and the runtime's depth left a column out for a while after each rebuild, so
     * patches of ground near the camera read as sky to the lens.
     */
    private static boolean inView(Frustum frustum, SectionPos camera, int cx, int sy, int cz) {
        if (Math.abs(cx - camera.x()) <= 1 && Math.abs(sy - camera.y()) <= 1 && Math.abs(cz - camera.z()) <= 1) {
            return true;
        }
        double x = cx * 16.0, y = sy * 16.0, z = cz * 16.0;
        if (frustum.pointInFrustum(x + 8.0, y + 8.0, z + 8.0)) return true;
        for (int corner = 0; corner < 8; corner++) {
            if (frustum.pointInFrustum(x + (corner & 1) * 16.0, y + ((corner >> 1) & 1) * 16.0,
                    z + ((corner >> 2) & 1) * 16.0)) return true;
        }
        return false;
    }

    private void restart() {
        column = 0;
        layer = 0;
        found = 0;
    }

    static long[] offsetsWithin(int radius) {
        int side = 2 * radius + 1;
        long[] packed = new long[side * side];
        int[] distances = new int[side * side];
        Integer[] order = new Integer[side * side];
        int n = 0;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                packed[n] = ((long) dx << 32) | (dz & 0xFFFFFFFFL);
                distances[n] = dx * dx + dz * dz;
                order[n] = n;
                n++;
            }
        }
        Arrays.sort(order, (a, b) -> Integer.compare(distances[a], distances[b]));
        long[] sorted = new long[n];
        for (int i = 0; i < n; i++) sorted[i] = packed[order[i]];
        return sorted;
    }
}
