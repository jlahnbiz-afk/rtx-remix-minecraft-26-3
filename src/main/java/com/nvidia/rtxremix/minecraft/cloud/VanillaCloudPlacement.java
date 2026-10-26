/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.cloud;

import java.util.function.Consumer;

/** Stateless placement of streamed chunks from a repeating cloud occupancy mask. */
final class VanillaCloudPlacement {
    static final int PARTITIONS_PER_AXIS = 8;
    static final double WORLD_PERIOD = 256.0 * VanillaCloudMeshGenerator.CELL_SIZE;
    static final double VIEW_DISTANCE = CloudPlacement.VIEW_DISTANCE;
    static final double DRIFT_PER_TICK = 0.03;
    static final double Z_PHASE = 0.33;

    /**
     * How a mask repeats over the world: the distance one copy covers along each axis, how
     * far it drifts along +X per tick, and its offset along Z.
     */
    record Layer(double period, double driftPerTick, double phaseZ) {
    }

    /** Vanilla's own layer, so the Classic style keeps vanilla's period and phase. */
    static final Layer CLASSIC = new Layer(
            WORLD_PERIOD, DRIFT_PER_TICK, Z_PHASE * VanillaCloudMeshGenerator.CELL_SIZE);

    private VanillaCloudPlacement() {
    }

    /** {@link #forEachVisible(double, double, double, int, int, Layer, Consumer)} on {@link #CLASSIC}. */
    static void forEachVisible(
            double cameraX,
            double cameraZ,
            double worldTime,
            int textureWidth,
            int textureHeight,
            Consumer<Cursor> consumer) {
        forEachVisible(cameraX, cameraZ, worldTime, textureWidth, textureHeight, CLASSIC, consumer);
    }

    /**
     * Calls {@code consumer} once per visible placement with one reused cursor: the
     * traversal runs every frame over hundreds of cells, and a record per cell was a
     * visible share of the render thread's allocation. The cursor is valid only during the
     * callback; {@link Cursor#snapshot()} copies it for anything that outlives the call.
     */
    static void forEachVisible(
            double cameraX,
            double cameraZ,
            double worldTime,
            int textureWidth,
            int textureHeight,
            Layer layer,
            Consumer<Cursor> consumer) {
        if (textureWidth <= 0 || textureHeight <= 0) {
            throw new IllegalArgumentException("Cloud texture dimensions must be positive");
        }
        double period = layer.period();
        double driftX = worldTime * layer.driftPerTick();
        double phaseZ = layer.phaseZ();
        int partitionsX = Math.min(PARTITIONS_PER_AXIS, textureWidth);
        int partitionsZ = Math.min(PARTITIONS_PER_AXIS, textureHeight);
        double cellSizeX = period / textureWidth;
        double cellSizeZ = period / textureHeight;
        int minRepeatX = floorRepeat(cameraX + driftX - VIEW_DISTANCE, period);
        int maxRepeatX = floorRepeat(cameraX + driftX + VIEW_DISTANCE, period);
        int minRepeatZ = floorRepeat(cameraZ + phaseZ - VIEW_DISTANCE, period);
        int maxRepeatZ = floorRepeat(cameraZ + phaseZ + VIEW_DISTANCE, period);
        Cursor cursor = new Cursor();

        for (int repeatZ = minRepeatZ; repeatZ <= maxRepeatZ; repeatZ++) {
            for (int partitionZ = 0; partitionZ < partitionsZ; partitionZ++) {
                int textureZ = partitionBoundary(partitionZ, textureHeight, partitionsZ);
                int nextTextureZ =
                        partitionBoundary(partitionZ + 1, textureHeight, partitionsZ);
                int windowHeight = nextTextureZ - textureZ;
                double worldZ = repeatZ * period + textureZ * cellSizeZ - phaseZ;
                double worldDepth = windowHeight * cellSizeZ;

                for (int repeatX = minRepeatX; repeatX <= maxRepeatX; repeatX++) {
                    for (int partitionX = 0; partitionX < partitionsX; partitionX++) {
                        int textureX =
                                partitionBoundary(partitionX, textureWidth, partitionsX);
                        int nextTextureX =
                                partitionBoundary(partitionX + 1, textureWidth, partitionsX);
                        int windowWidth = nextTextureX - textureX;
                        double worldX =
                                repeatX * period + textureX * cellSizeX - driftX;
                        double worldWidth = windowWidth * cellSizeX;
                        if (!intersectsView(
                                worldX,
                                worldZ,
                                worldWidth,
                                worldDepth,
                                cameraX,
                                cameraZ)) {
                            continue;
                        }
                        cursor.textureKey = key(partitionX, partitionZ);
                        cursor.textureX = textureX;
                        cursor.textureZ = textureZ;
                        cursor.windowWidth = windowWidth;
                        cursor.windowHeight = windowHeight;
                        cursor.cellSizeX = (float) cellSizeX;
                        cursor.cellSizeZ = (float) cellSizeZ;
                        cursor.x = worldX;
                        cursor.z = worldZ;
                        consumer.accept(cursor);
                    }
                }
            }
        }
    }

    private static boolean intersectsView(
            double chunkX,
            double chunkZ,
            double chunkWidth,
            double chunkDepth,
            double cameraX,
            double cameraZ) {
        double nearestX = Math.max(chunkX, Math.min(cameraX, chunkX + chunkWidth));
        double nearestZ = Math.max(chunkZ, Math.min(cameraZ, chunkZ + chunkDepth));
        double dx = nearestX - cameraX;
        double dz = nearestZ - cameraZ;
        return dx * dx + dz * dz <= VIEW_DISTANCE * VIEW_DISTANCE;
    }

    private static int floorRepeat(double coordinate, double period) {
        return (int) Math.floor(coordinate / period);
    }

    private static int partitionBoundary(int partition, int textureSize, int partitionCount) {
        return partition * textureSize / partitionCount;
    }

    private static long key(int x, int z) {
        return ((long) x << 32) ^ (z & 0xffff_ffffL);
    }

    /** A visible placement, as a value. */
    record Placement(
            long textureKey,
            int textureX,
            int textureZ,
            int windowWidth,
            int windowHeight,
            float cellSizeX,
            float cellSizeZ,
            double x,
            double z) {
    }

    /** The traversal's reused view of the current placement. */
    static final class Cursor {
        long textureKey;
        int textureX;
        int textureZ;
        int windowWidth;
        int windowHeight;
        float cellSizeX;
        float cellSizeZ;
        double x;
        double z;

        Placement snapshot() {
            return new Placement(textureKey, textureX, textureZ, windowWidth, windowHeight,
                    cellSizeX, cellSizeZ, x, z);
        }
    }
}
