/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.weather;

import com.nvidia.rtxremix.HardcodedVertex;
import com.nvidia.rtxremix.InstanceInfoBlendEXT;
import com.nvidia.rtxremix.RemixAPI;
import com.nvidia.rtxremix.Transform;
import com.nvidia.rtxremix.minecraft.asset.RemixAssetBase;
import com.nvidia.rtxremix.minecraft.asset.RemixInstance;
import com.nvidia.rtxremix.minecraft.asset.RemixInstanceStore;
import com.nvidia.rtxremix.minecraft.asset.RemixMaterial;
import com.nvidia.rtxremix.minecraft.asset.RemixMesh;
import com.nvidia.rtxremix.minecraft.asset.RemixMeshStore;
import com.nvidia.rtxremix.minecraft.asset.RemixMeshSurface;
import com.nvidia.rtxremix.minecraft.texture.BlockTextureManager;
import com.nvidia.rtxremix.minecraft.util.DirectBufferPool;
import java.nio.ByteBuffer;
import java.util.Arrays;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Puddles on open ground in the rain: thin sheets of clear water lying a centimetre or so
 * above the block tops where rain pools. The ground shows through them unchanged, they
 * reflect by the water's Fresnel term, and the raindrop rings in the water's normals ripple
 * them, so they need nothing of the blocks beneath.
 *
 * <p>Where water stands is the {@link PuddleMask} noise, traced with marching squares over a
 * grid on every block top so a puddle follows the noise rather than the block grid. Its edge
 * also recedes from ground that takes no water, under a roof or past the edge of a drop, so
 * a puddle never ends on a straight line where the shelter begins.
 *
 * <p>The sheets cover the blocks within {@link #RADIUS} of the camera. They are relaid as the
 * camera moves and soon after a block nearby changes, and retraced as the ground gets wetter
 * or dries; past that radius the rain's fog hides them.
 *
 * <p>Render thread only.
 */
public final class Puddles {

    private static final Logger LOGGER = LoggerFactory.getLogger(Puddles.class);

    private static final int RADIUS = 48;
    /** Columns beyond the radius whose shelter still pushes a puddle's edge back. */
    private static final int MARGIN = 2;
    private static final int SPAN = 2 * (RADIUS + MARGIN) + 1;
    private static final int MAX_BLOCKS = (2 * RADIUS + 1) * (2 * RADIUS + 1);
    /** Blocks the camera may move before the sheets are relaid around it. */
    private static final int RELAY_DISTANCE = 8;
    /** Nanoseconds between relays while the camera stands still. */
    private static final long RELAY_INTERVAL_NANOS = 10_000_000_000L;
    /** Shortest wait after a block change before relaying, so a busy area relays at a bounded rate. */
    private static final long CHANGE_RELAY_NANOS = 500_000_000L;
    /** Seconds for soaked ground to form in a steady rain, and for it to dry once the rain stops. */
    private static final float WET_SECONDS = 30.0f;
    private static final float DRY_SECONDS = 120.0f;
    /** Change in wetness that retraces the puddles. */
    private static final float WETNESS_STEP = 1.0f / 32.0f;
    /** How much of the mask's depth a puddle needs before its sheet starts. */
    private static final float WATER_THRESHOLD = 0.3f;
    /** Height of the sheet over the block top, about a centimetre of water. */
    private static final float DEPTH = 0.0125f;
    /** Cells across a block top. */
    private static final int GRID = 4;
    private static final int GRID_POINTS = (GRID + 1) * (GRID + 1);
    /** Blocks over which a puddle's edge recedes from sheltered or lower ground. */
    private static final float SHELTER_FADE = 1.25f;
    private static final long SEED = 0x50554444L;
    private static final InstanceInfoBlendEXT BLEND = InstanceInfoBlendEXT.textureTimesVertexColor();
    private static final int NO_WATER = Integer.MIN_VALUE;

    private static int changedMinX;
    private static int changedMinZ;
    private static int changedMaxX;
    private static int changedMaxZ;
    private static boolean changed;

    private final float[] noiseSum = PuddleMask.noiseSum(SEED);
    /** The top of each column around the origin that rain can pool on, or {@link #NO_WATER}. */
    private final int[] columnTop = new int[SPAN * SPAN];
    private final int[] blockX = new int[MAX_BLOCKS];
    private final int[] blockZ = new int[MAX_BLOCKS];
    private final int[] blockTop = new int[MAX_BLOCKS];
    private final float[] blockMinSum = new float[MAX_BLOCKS];
    private final float[] blockMaxSum = new float[MAX_BLOCKS];
    /** The noise sum, less the shelter's pull, at each grid point of each top, x fastest. */
    private final float[] blockSum = new float[MAX_BLOCKS * GRID_POINTS];
    private int blocks;

    /** Traced vertices as (x, z) within their block, and the block each belongs to. */
    private float[] traced = new float[1024];
    private int[] tracedBlock = new int[512];
    private int tracedCount;
    private int[] indices = new int[1024];
    private int indexCount;
    private final float[] cellX = new float[4];
    private final float[] cellZ = new float[4];
    private final float[] cellSum = new float[4];

    private final Transform transform = new Transform();
    private final BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
    private RemixMaterial material;
    private RemixMesh mesh;
    private RemixInstance instance;
    private float wetness;
    private float meshWetness = -1.0f;
    private int originX;
    private int originZ;
    private long lastRelayNanos;
    private long lastNanos;
    private ClientLevel meshLevel;
    private boolean failed;

    /**
     * Notes that blocks in the given columns changed, so sheets over them are relaid. Called
     * from the client's block updates on the render thread.
     */
    public static void blocksChanged(int minX, int minZ, int maxX, int maxZ) {
        if (!changed) {
            changedMinX = minX;
            changedMinZ = minZ;
            changedMaxX = maxX;
            changedMaxZ = maxZ;
            changed = true;
            return;
        }
        changedMinX = Math.min(changedMinX, minX);
        changedMinZ = Math.min(changedMinZ, minZ);
        changedMaxX = Math.max(changedMaxX, maxX);
        changedMaxZ = Math.max(changedMaxZ, maxZ);
    }

    /** Follows the rain and lays the water sheets around the camera. */
    public void render(RemixAPI api, ClientLevel level, Vec3 camera, float partialTick, BlockTextureManager textures) {
        if (failed || api == null) return;
        long now = System.nanoTime();
        float seconds = lastNanos == 0 ? 0.0f : Math.min(0.25f, (now - lastNanos) / 1.0e9f);
        lastNanos = now;
        float rain = Math.clamp(level.getRainLevel(partialTick), 0.0f, 1.0f);
        wetness = rain > wetness
                ? Math.min(rain, wetness + seconds / WET_SECONDS)
                : Math.max(rain, wetness - seconds / DRY_SECONDS);
        try {
            RemixMaterial water = textures.getPuddleWaterMaterial();
            if (wetness <= 0.0f || water == null || !water.isAlive()) {
                destroy();
                return;
            }
            if (water != material) {
                destroy();
                material = water;
            }
            int cameraX = (int) Math.floor(camera.x);
            int cameraZ = (int) Math.floor(camera.z);
            boolean relay = level != meshLevel
                    || Math.max(Math.abs(cameraX - originX), Math.abs(cameraZ - originZ)) >= RELAY_DISTANCE
                    || now - lastRelayNanos >= RELAY_INTERVAL_NANOS
                    || (changedNearby() && now - lastRelayNanos >= CHANGE_RELAY_NANOS);
            if (relay) {
                changed = false;
                meshLevel = level;
                originX = cameraX;
                originZ = cameraZ;
                lastRelayNanos = now;
                gatherColumns(level);
                gatherBlocks();
            }
            if (relay || Math.abs(wetness - meshWetness) >= WETNESS_STEP) {
                meshWetness = wetness;
                retrace(api);
            }
        } catch (RuntimeException e) {
            LOGGER.error("Failed to draw puddles; turning them off", e);
            destroy();
            failed = true;
        }
    }

    private boolean changedNearby() {
        int reach = RADIUS + MARGIN;
        return changed && changedMaxX >= originX - reach && changedMinX <= originX + reach
                && changedMaxZ >= originZ - reach && changedMinZ <= originZ + reach;
    }

    /** Records the top of every column around the origin that rain can pool on. */
    private void gatherColumns(ClientLevel level) {
        int seaLevel = level.getSeaLevel();
        int reach = RADIUS + MARGIN;
        for (int dz = -reach; dz <= reach; dz++) {
            for (int dx = -reach; dx <= reach; dx++) {
                int x = originX + dx;
                int z = originZ + dz;
                int top = level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z);
                pos.set(x, top, z);
                boolean rains = level.getBiome(pos).value().getPrecipitationAt(pos, seaLevel)
                        == Biome.Precipitation.RAIN;
                pos.set(x, top - 1, z);
                columnTop[(dz + reach) * SPAN + dx + reach] =
                        rains && holdsPuddles(level.getBlockState(pos)) ? top : NO_WATER;
            }
        }
    }

    /** Fills the block arrays with the tops within the radius that could ever hold water. */
    private void gatherBlocks() {
        float lowest = PuddleMask.sumThreshold(WATER_THRESHOLD, 1.0f);
        int count = 0;
        for (int dz = -RADIUS; dz <= RADIUS; dz++) {
            for (int dx = -RADIUS; dx <= RADIUS; dx++) {
                int top = columnTop[(dz + RADIUS + MARGIN) * SPAN + dx + RADIUS + MARGIN];
                if (top == NO_WATER) continue;
                int x = originX + dx;
                int z = originZ + dz;
                float min = Float.MAX_VALUE;
                float max = -Float.MAX_VALUE;
                for (int g = 0; g < GRID_POINTS; g++) {
                    float fx = (g % (GRID + 1)) / (float) GRID;
                    float fz = (g / (GRID + 1)) / (float) GRID;
                    float sum = sumAt(x, z, g % (GRID + 1), g / (GRID + 1))
                            - PuddleMask.MAX_SUM * shelter(dx, dz, top, fx, fz);
                    blockSum[count * GRID_POINTS + g] = sum;
                    min = Math.min(min, sum);
                    max = Math.max(max, sum);
                }
                if (max <= lowest) continue;
                blockX[count] = x;
                blockZ[count] = z;
                blockTop[count] = top;
                blockMinSum[count] = min;
                blockMaxSum[count] = max;
                count++;
            }
        }
        blocks = count;
    }

    /**
     * How strongly the point (fx, fz) on the top of column (dx, dz) from the origin is pulled
     * dry, 0 to 1: fully on the boundary of a column that takes no water or lies lower, which
     * the water would run off to, fading out {@link #SHELTER_FADE} blocks away.
     */
    private float shelter(int dx, int dz, int top, float fx, float fz) {
        float nearest = SHELTER_FADE;
        int reach = RADIUS + MARGIN;
        for (int nz = -MARGIN; nz <= MARGIN; nz++) {
            for (int nx = -MARGIN; nx <= MARGIN; nx++) {
                if (nx == 0 && nz == 0) continue;
                int neighbour = columnTop[(dz + nz + reach) * SPAN + dx + nx + reach];
                if (neighbour != NO_WATER && neighbour >= top) continue;
                float distanceX = nx > 0 ? nx - fx : nx < 0 ? fx - (nx + 1) : 0.0f;
                float distanceZ = nz > 0 ? nz - fz : nz < 0 ? fz - (nz + 1) : 0.0f;
                nearest = Math.min(nearest, (float) Math.sqrt(distanceX * distanceX + distanceZ * distanceZ));
            }
        }
        float t = 1.0f - nearest / SHELTER_FADE;
        return t * t * (3.0f - 2.0f * t);
    }

    /** The noise sum at grid point (gx, gz) of the top of column (x, z). */
    private float sumAt(int x, int z, int gx, int gz) {
        int size = PuddleMask.SIZE;
        int tx = Math.floorMod(Math.floorMod(x, PuddleMask.TILE_BLOCKS) * PuddleMask.TEXELS_PER_BLOCK
                + gx * PuddleMask.TEXELS_PER_BLOCK / GRID, size);
        int tz = Math.floorMod(Math.floorMod(z, PuddleMask.TILE_BLOCKS) * PuddleMask.TEXELS_PER_BLOCK
                + gz * PuddleMask.TEXELS_PER_BLOCK / GRID, size);
        return noiseSum[tz * size + tx];
    }

    /** Replaces the sheets with ones traced at the current wetness. */
    private void retrace(RemixAPI api) {
        float limit = PuddleMask.sumThreshold(WATER_THRESHOLD, meshWetness);
        tracedCount = 0;
        indexCount = 0;
        for (int i = 0; i < blocks; i++) {
            if (blockMaxSum[i] <= limit) continue;
            if (blockMinSum[i] > limit) {
                int first = tracedCount;
                addVertex(i, 0.0f, 0.0f);
                addVertex(i, 1.0f, 0.0f);
                addVertex(i, 1.0f, 1.0f);
                addVertex(i, 0.0f, 1.0f);
                fan(first);
                continue;
            }
            for (int cz = 0; cz < GRID; cz++) {
                for (int cx = 0; cx < GRID; cx++) {
                    traceCell(i, cx, cz, limit);
                }
            }
        }
        RemixMesh previous = mesh;
        mesh = tracedCount == 0 ? null : createMesh();
        if (mesh == null) {
            destroyAsset(instance);
            instance = null;
        } else {
            transform.setTranslation(originX, DEPTH, originZ);
            if (instance == null) {
                // Double sided like terrain water, whose surface rays meet from below too.
                instance = RemixInstanceStore.get().create(new RemixInstance(mesh)
                        .setBlendExt(BLEND)
                        .setDoubleSided(true));
            }
            instance.setMesh(mesh).setTransform(transform).drawFast(api);
        }
        destroyAsset(previous);
    }

    /**
     * Adds the part of grid cell (cx, cz) of block {@code i} whose sum passes {@code limit}: the
     * cell's corners that pass and the points along its edges where the sum crosses the limit,
     * which always make a convex polygon.
     */
    private void traceCell(int i, int cx, int cz, float limit) {
        int row = GRID + 1;
        int g = cz * row + cx;
        int base = i * GRID_POINTS;
        cellSum[0] = blockSum[base + g];
        cellSum[1] = blockSum[base + g + 1];
        cellSum[2] = blockSum[base + g + row + 1];
        cellSum[3] = blockSum[base + g + row];
        if (cellSum[0] <= limit && cellSum[1] <= limit && cellSum[2] <= limit && cellSum[3] <= limit) return;
        float x0 = cx / (float) GRID;
        float x1 = (cx + 1) / (float) GRID;
        float z0 = cz / (float) GRID;
        float z1 = (cz + 1) / (float) GRID;
        cellX[0] = x0; cellZ[0] = z0;
        cellX[1] = x1; cellZ[1] = z0;
        cellX[2] = x1; cellZ[2] = z1;
        cellX[3] = x0; cellZ[3] = z1;
        int first = tracedCount;
        for (int k = 0; k < 4; k++) {
            int j = (k + 1) & 3;
            boolean in = cellSum[k] > limit;
            if (in) addVertex(i, cellX[k], cellZ[k]);
            if (in != cellSum[j] > limit) {
                float f = (limit - cellSum[k]) / (cellSum[j] - cellSum[k]);
                addVertex(i, cellX[k] + (cellX[j] - cellX[k]) * f, cellZ[k] + (cellZ[j] - cellZ[k]) * f);
            }
        }
        fan(first);
    }

    private void addVertex(int block, float fx, float fz) {
        if (tracedCount == tracedBlock.length) {
            tracedBlock = Arrays.copyOf(tracedBlock, tracedCount * 2);
            traced = Arrays.copyOf(traced, tracedCount * 4);
        }
        traced[tracedCount * 2] = fx;
        traced[tracedCount * 2 + 1] = fz;
        tracedBlock[tracedCount++] = block;
    }

    /** Triangulates the convex polygon of the vertices from {@code first} on. */
    private void fan(int first) {
        int corners = tracedCount - first;
        if (indexCount + (corners - 2) * 3 > indices.length) {
            indices = Arrays.copyOf(indices, Math.max(indices.length * 2, indexCount + corners * 3));
        }
        for (int k = 1; k + 1 < corners; k++) {
            indices[indexCount++] = first;
            indices[indexCount++] = first + k;
            indices[indexCount++] = first + k + 1;
        }
    }

    /** The traced sheets, textured in the water's world-space tiles as terrain water is. */
    private RemixMesh createMesh() {
        float tile = BlockTextureManager.getWaterTileSize();
        int tileBlocks = (int) tile;
        ByteBuffer vertices = DirectBufferPool.acquire(tracedCount * HardcodedVertex.STRIDE);
        for (int v = 0; v < tracedCount; v++) {
            int i = tracedBlock[v];
            float fx = traced[v * 2];
            float fz = traced[v * 2 + 1];
            float u = (Math.floorMod(blockX[i], tileBlocks) + fx) / tile;
            float w = (Math.floorMod(blockZ[i], tileBlocks) + fz) / tile;
            RemixMeshSurface.putVertex(vertices, blockX[i] - originX + fx, blockTop[i], blockZ[i] - originZ + fz,
                    0.0f, 1.0f, 0.0f, u, w, 0xFFFFFFFF, 0, 0);
        }
        vertices.flip();
        RemixMeshSurface surface = new RemixMeshSurface(material)
                .setVertexData(vertices, tracedCount)
                .setIndexDataFrom(indices, indexCount);
        return RemixMeshStore.get().create(new RemixMesh(new RemixMeshSurface[] {surface}));
    }

    /**
     * Whether water can stand on top of the block: a full, opaque top that is not sand,
     * which drains, nor snow or ice, which the rain does not reach as water.
     */
    static boolean holdsPuddles(BlockState state) {
        return state.isSolidRender()
                && !state.is(BlockTags.SAND)
                && !state.is(BlockTags.SNOW)
                && !state.is(BlockTags.ICE)
                && !state.is(Blocks.MAGMA_BLOCK);
    }

    /** Destroys the sheets; the next wet frame rebuilds them. The water material is not ours. */
    public void destroy() {
        destroyAsset(instance);
        destroyAsset(mesh);
        instance = null;
        mesh = null;
        material = null;
        meshLevel = null;
        meshWetness = -1.0f;
    }

    private static void destroyAsset(RemixAssetBase asset) {
        if (asset != null && !asset.isDeleted()) asset.destroy();
    }
}
