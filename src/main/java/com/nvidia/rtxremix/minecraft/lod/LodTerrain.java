/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.lod;

import com.nvidia.rtxremix.InstanceCategoryBit;
import com.nvidia.rtxremix.InstanceInfoBlendEXT;
import com.nvidia.rtxremix.MaterialInfo;
import com.nvidia.rtxremix.MaterialInfoOpaqueEXT;
import com.nvidia.rtxremix.RemixAPI;
import com.nvidia.rtxremix.TextureFormat;
import com.nvidia.rtxremix.TexturePurpose;
import com.nvidia.rtxremix.Transform;
import com.nvidia.rtxremix.minecraft.ClientViewDistance;
import com.nvidia.rtxremix.minecraft.asset.RemixInstance;
import com.nvidia.rtxremix.minecraft.asset.RemixInstanceStore;
import com.nvidia.rtxremix.minecraft.asset.RemixMaterial;
import com.nvidia.rtxremix.minecraft.asset.RemixMaterialStore;
import com.nvidia.rtxremix.minecraft.asset.RemixMesh;
import com.nvidia.rtxremix.minecraft.asset.RemixMeshStore;
import com.nvidia.rtxremix.minecraft.asset.RemixTexture;
import com.nvidia.rtxremix.minecraft.asset.RemixTextureStore;
import com.nvidia.rtxremix.minecraft.lod.dh.DistantHorizonsCompat;
import com.nvidia.rtxremix.minecraft.terrain.TerrainAssetRetirementQueue;
import com.nvidia.rtxremix.minecraft.terrain.TerrainNativeCallStats;
import com.nvidia.rtxremix.minecraft.texture.BlockTextureManager;
import com.nvidia.rtxremix.minecraft.util.DirectBufferPool;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Far terrain from an LOD mod, as retained Remix instances beyond Minecraft's
 * own terrain.
 *
 * <p>The LOD mod decides which sections exist, at what detail, and which are
 * drawn; {@link LodSection} carries those decisions over. Here, on the render
 * thread, a section's copied geometry is sent to one builder thread with the
 * current clip, and the finished mesh is installed under a frame budget as one
 * instance, submitted where it is created and again only when the mod shows or
 * hides it. Moving into a new chunk rebuilds just the sections whose clipped
 * chunks change.
 */
public final class LodTerrain {

    private static final Logger LOGGER = LoggerFactory.getLogger(LodTerrain.class);

    private static final boolean DISTANT_HORIZONS =
            FabricLoader.getInstance().isModLoaded("distanthorizons");
    private static final long INSTALL_BUDGET_NS = 1_000_000L;
    private static final int MAX_BUILDS_IN_FLIGHT = 4;
    private static final int MAX_EVENTS_PER_FRAME = 4096;
    /**
     * Minecraft's terrain is clipped this many chunks inside its render
     * distance, so its unbuilt edge shows LOD ground rather than a hole.
     */
    private static final int CLIP_MARGIN_CHUNKS = 2;
    private static final long MAX_INSTALLED_BYTES = 2L << 30;
    private static final InstanceInfoBlendEXT BLEND = InstanceInfoBlendEXT.textureTimesVertexColor();

    private record BuildResult(LodSection section, int serial, int epoch, long signature,
                               RemixMesh mesh, Throwable failure) {}

    private final ExecutorService builder = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "RTX LOD Mesh Builder");
        thread.setDaemon(true);
        thread.setPriority(Thread.MIN_PRIORITY);
        return thread;
    });
    private final ConcurrentLinkedQueue<BuildResult> completed = new ConcurrentLinkedQueue<>();
    private final TerrainAssetRetirementQueue retirement =
            new TerrainAssetRetirementQueue(new TerrainNativeCallStats());
    private final List<LodSection> live = new ArrayList<>();
    private final ArrayDeque<LodSection> buildQueue = new ArrayDeque<>();

    private boolean enabled = true;
    private boolean capturing;
    private int epoch;
    private int buildsInFlight;
    private long installedBytes;
    private boolean budgetWarned;
    private LodMeshBuilder.Clip clip = LodMeshBuilder.Clip.NONE;
    private RemixMaterial groundMaterial;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    /** Radius of the LOD terrain being drawn, in blocks, or 0 when there is none. */
    public float radiusBlocks() {
        return capturing ? DistantHorizonsCompat.lodRadiusChunks() * 16f : 0f;
    }

    /**
     * Render thread, once per frame. {@code active} is whether Remix draws the
     * world this frame; while it is false the LOD mod draws for itself.
     */
    public void update(RemixAPI api, Vec3 camera, BlockTextureManager textures, boolean active) {
        boolean wanted = DISTANT_HORIZONS && enabled && active;
        if (wanted != capturing) setCapturing(wanted);
        drainEvents(api);
        // Also frees what finishes after a stop: a stop makes every build stale.
        installBuilds(api);
        if (!capturing) return;

        updateClip(camera);
        startBuilds(textures);
        retirement.drainFrame();
    }

    /**
     * Drops every Remix object before materials are rebuilt, keeping the copied
     * geometry so each section reinstalls with the new materials.
     */
    public void clear() {
        epoch++;
        for (LodSection section : live) {
            retireInstance(section);
            section.clipSignature = 0;
            requestBuild(section);
        }
        retirement.clear();
    }

    /** Drops everything when the world goes; the next world's sections arrive afresh. */
    public void reset() {
        if (capturing) setCapturing(false);
    }

    public void destroy() {
        reset();
        builder.shutdownNow();
        if (groundMaterial != null && !groundMaterial.isDeleted()) groundMaterial.destroy();
        groundMaterial = null;
    }

    private void setCapturing(boolean on) {
        capturing = on;
        LodSection.capturing = on;
        if (!on) {
            epoch++;
            for (LodSection section : live) {
                retireInstance(section);
                releaseGeometry(section);
                section.liveIndex = -1;
                section.inBuildQueue = false;
            }
            live.clear();
            buildQueue.clear();
            // Nothing drains the queue while stopped, so the scene must lose them now.
            retirement.clear();
        }
        LOGGER.info("LOD terrain capture {}", on ? "started" : "stopped");
        // Starting asks the mod to rebuild every section so the copies arrive;
        // stopping hands it back its own shading and drawing.
        DistantHorizonsCompat.setCapturing(on);
    }

    private void drainEvents(RemixAPI api) {
        for (int i = 0; i < MAX_EVENTS_PER_FRAME; i++) {
            LodSection section = LodSection.pollDirty();
            if (section == null) return;

            LodGeometry geometry = section.takePending();
            if (section.closed || !capturing) {
                if (geometry != null) geometry.release();
                if (section.liveIndex >= 0) forget(section);
                continue;
            }
            if (geometry != null) {
                releaseGeometry(section);
                section.geometry = geometry;
                if (section.liveIndex < 0) {
                    section.liveIndex = live.size();
                    live.add(section);
                }
                requestBuild(section);
            }
            if (section.liveIndex >= 0) applyVisibility(section, api);
        }
    }

    private void forget(LodSection section) {
        retireInstance(section);
        releaseGeometry(section);
        int index = section.liveIndex;
        LodSection last = live.remove(live.size() - 1);
        if (last != section) {
            live.set(index, last);
            last.liveIndex = index;
        }
        section.liveIndex = -1;
        // A queued build for it is skipped when it reaches the front.
        section.serial++;
    }

    private void applyVisibility(LodSection section, RemixAPI api) {
        boolean visible = section.enabled;
        if (section.instance == null || section.shown == visible) return;
        section.instance.setCategoryFlags(visible ? 0 : InstanceCategoryBit.HIDDEN);
        section.instance.drawFast(api);
        section.shown = visible;
    }

    private void updateClip(Vec3 camera) {
        int radius = ClientViewDistance.chunks() - CLIP_MARGIN_CHUNKS;
        int centerX = ((int) Math.floor(camera.x)) >> 4;
        int centerZ = ((int) Math.floor(camera.z)) >> 4;
        if (radius < 0) {
            if (clip.radius() < 0) return;
        } else if (clip.radius() == radius && clip.centerX() == centerX && clip.centerZ() == centerZ) {
            return;
        }
        clip = radius < 0 ? LodMeshBuilder.Clip.NONE : new LodMeshBuilder.Clip(centerX, centerZ, radius);
        for (LodSection section : live) {
            if (clip.signature(section.minX, section.minZ, section.width) != section.clipSignature) {
                requestBuild(section);
            }
        }
    }

    private void requestBuild(LodSection section) {
        section.serial++;
        if (!section.inBuildQueue) {
            section.inBuildQueue = true;
            buildQueue.add(section);
        }
    }

    private void startBuilds(BlockTextureManager textures) {
        if (buildQueue.isEmpty() || buildsInFlight >= MAX_BUILDS_IN_FLIGHT) return;
        RemixMaterial ground = groundMaterial();
        RemixMaterial water = textures != null ? textures.getWaterSurfaceMaterial() : null;
        if (ground == null) return;

        while (buildsInFlight < MAX_BUILDS_IN_FLIGHT && !buildQueue.isEmpty()) {
            LodSection section = buildQueue.poll();
            section.inBuildQueue = false;
            if (section.liveIndex < 0 || section.geometry == null) continue;

            LodGeometry geometry = section.geometry;
            geometry.retain();
            LodMeshBuilder.Clip buildClip = clip;
            int serial = section.serial;
            int buildEpoch = epoch;
            long signature = buildClip.signature(section.minX, section.minZ, section.width);
            buildsInFlight++;
            builder.execute(() -> {
                RemixMesh mesh = null;
                Throwable failure = null;
                try {
                    mesh = LodMeshBuilder.build(geometry, buildClip, section.minX, section.minZ,
                            ground, water);
                } catch (Throwable t) {
                    failure = t;
                } finally {
                    geometry.release();
                }
                completed.add(new BuildResult(section, serial, buildEpoch, signature, mesh, failure));
            });
        }
    }

    private void installBuilds(RemixAPI api) {
        long deadline = System.nanoTime() + INSTALL_BUDGET_NS;
        BuildResult result;
        while ((result = completed.poll()) != null) {
            buildsInFlight--;
            LodSection section = result.section();
            if (result.failure() != null) {
                LOGGER.error("LOD mesh build failed at {}, {}", section.minX, section.minZ, result.failure());
                continue;
            }
            if (result.epoch() != epoch || result.serial() != section.serial || section.liveIndex < 0) {
                if (result.mesh() != null) result.mesh().disposeData();
                continue;
            }
            install(section, result.mesh(), result.signature(), api);
            if (System.nanoTime() >= deadline) return;
        }
    }

    private void install(LodSection section, RemixMesh mesh, long signature, RemixAPI api) {
        retireInstance(section);
        section.clipSignature = signature;
        if (mesh == null) return;

        long bytes = mesh.estimatedNativeBytes();
        if (installedBytes + bytes > MAX_INSTALLED_BYTES) {
            mesh.disposeData();
            if (!budgetWarned) {
                budgetWarned = true;
                LOGGER.warn("LOD terrain reached its {} MiB budget; lower the LOD render distance",
                        MAX_INSTALLED_BYTES >> 20);
            }
            return;
        }
        RemixInstance instance = null;
        try {
            RemixMeshStore.get().create(mesh);
            boolean visible = section.enabled;
            Transform transform = Transform.identity();
            transform.setTranslation(section.minX, section.minY, section.minZ);
            instance = new RemixInstance(mesh)
                    .setTransform(transform)
                    .setCategoryFlags(visible ? 0 : InstanceCategoryBit.HIDDEN)
                    .setBlendExt(BLEND)
                    .setDoubleSided(true);
            RemixInstanceStore.get().create(instance);
            instance.drawFast(api);
            section.instance = instance;
            section.shown = visible;
            section.installedBytes = bytes;
            installedBytes += bytes;
        } catch (RuntimeException e) {
            LOGGER.error("LOD terrain install failed at {}, {}", section.minX, section.minZ, e);
            if (instance != null) {
                retirement.retire(instance);
            } else if (!mesh.isDeleted()) {
                retirement.retire(mesh);
            }
            mesh.disposeData();
        }
    }

    private void retireInstance(LodSection section) {
        if (section.instance == null) return;
        retirement.retire(section.instance);
        installedBytes -= section.installedBytes;
        section.instance = null;
        section.installedBytes = 0;
    }

    private static void releaseGeometry(LodSection section) {
        if (section.geometry != null) {
            section.geometry.release();
            section.geometry = null;
        }
    }

    /** One white texel, so the vertex colour the LOD mod baked is the whole albedo. */
    private RemixMaterial groundMaterial() {
        if (groundMaterial != null && !groundMaterial.isDeleted()) return groundMaterial;
        ByteBuffer pixel = DirectBufferPool.acquire(4);
        pixel.put(0, (byte) -1).put(1, (byte) -1).put(2, (byte) -1).put(3, (byte) -1).limit(4);
        try {
            RemixTexture texture = RemixTextureStore.get().create(new RemixTexture(
                    1, 1, TextureFormat.RGBA8_UNORM, TexturePurpose.COLOR, pixel));
            MaterialInfo info = MaterialInfo.builder()
                    .albedoTexture(texture.getHandle())
                    .filterMode(0)
                    .opaqueExt(MaterialInfoOpaqueEXT.builder()
                            .albedoConstant(1f, 1f, 1f)
                            .roughnessConstant(0.9f)
                            .metallicConstant(0f)
                            .build())
                    .build();
            groundMaterial = RemixMaterialStore.get().create(new RemixMaterial(info, List.of(texture), null));
        } catch (RuntimeException e) {
            LOGGER.error("Failed to create the LOD terrain material", e);
            groundMaterial = null;
        }
        return groundMaterial;
    }
}
