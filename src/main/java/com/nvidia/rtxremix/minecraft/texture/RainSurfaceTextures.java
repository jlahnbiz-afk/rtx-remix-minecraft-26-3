/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.texture;

import com.nvidia.rtxremix.MaterialInfo;
import com.nvidia.rtxremix.MaterialInfoOpaqueEXT;
import com.nvidia.rtxremix.RemixAPI;
import com.nvidia.rtxremix.TextureBatchEntry;
import com.nvidia.rtxremix.TextureFormat;
import com.nvidia.rtxremix.TexturePatch;
import com.nvidia.rtxremix.TexturePatchInfo;
import com.nvidia.rtxremix.TexturePurpose;
import com.nvidia.rtxremix.minecraft.asset.RemixMaterial;
import com.nvidia.rtxremix.minecraft.asset.RemixTexture;
import com.nvidia.rtxremix.minecraft.util.DirectBufferPool;
import java.nio.ByteBuffer;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The textures and material of the raindrop rings on open water, and the rain they show.
 *
 * <p>A ripple film lies just over water open to the rain, in world-space UVs over one
 * {@link #TILE_BLOCKS}-block tile, and is cut out wherever no ring disturbs it. What
 * survives the cut is an opaque surface the path tracer reflects off, under the rings'
 * normals. Its opacity comes from its texture alone, because the runtime ignores vertex
 * alpha, and it is cut out rather than blended because a blended surface is only lit from
 * the radiance cache, with no reflection.
 *
 * <p>The material never changes after it is made: the rain reaches the runtime as texture
 * content, the one path the atlas animations already exercise every frame. The rings are
 * rewritten a frame at a time while rain falls, at the rain's strength, and cleared once
 * it stops, which leaves the film fully cut away. Render thread only.
 */
public final class RainSurfaceTextures {

    private static final Logger LOGGER = LoggerFactory.getLogger(RainSurfaceTextures.class);

    /** Blocks one tile of the ripple animation covers along each axis; it wraps at its edges. */
    public static final int TILE_BLOCKS = 16;
    /** Texels per block: Minecraft's own texel density. */
    private static final int SIZE = TILE_BLOCKS * 16;
    private static final int RIPPLE_RGB = 0x0A0C0E;
    /** How opaque the most disturbed water in a ring gets. */
    private static final float RIPPLE_MAX_ALPHA = 0.6f;
    private static final float RIPPLE_ROUGHNESS = 0.0f;
    private static final int ALPHA_TEST_GREATER = 4;
    /** Ring alpha past which the ripple film reflects; a lighter rain draws thinner rings. */
    private static final int RIPPLE_ALPHA_REFERENCE = 40;
    private static final long RIPPLE_SEED = 0x52414E44L;
    /** Rain strength change that redraws a held ripple frame. */
    private static final float STRENGTH_EPSILON = 0.02f;

    /** Creates a texture the owner manages and destroys. */
    public interface Uploader {
        RemixTexture upload(String name, byte[] rgba, int width, int height, int format, int purpose);
    }

    private final RemixAPI api;
    private final RemixTexture rippleNormals;
    private final RemixTexture rippleRings;
    private final RemixMaterial rippleFilmMaterial;
    private final CompletableFuture<RainRipples> ripples;

    private final byte[] texels = new byte[SIZE * SIZE * 4];
    private final ByteBuffer staging;
    private final TexturePatch patch = new TexturePatch();
    private final TextureBatchEntry entry = new TextureBatchEntry();
    private final TexturePatchInfo patchInfo = new TexturePatchInfo();

    private int appliedFrame = -1;
    private float appliedStrength = 0f;
    private boolean released;

    private RainSurfaceTextures(RemixAPI api, RemixTexture rippleNormals, RemixTexture rippleRings,
                                RemixMaterial rippleFilmMaterial) {
        this.api = api;
        this.rippleNormals = rippleNormals;
        this.rippleRings = rippleRings;
        this.rippleFilmMaterial = rippleFilmMaterial;
        this.staging = DirectBufferPool.acquire(texels.length);
        patch.width = SIZE;
        patch.height = SIZE;
        patch.rowPitch = SIZE * 4;
        patch.format = TextureFormat.RGBA8_UNORM;
        entry.patches = new TexturePatch[] {patch};
        patchInfo.entries = new TextureBatchEntry[] {entry};
        // The rings take a moment to simulate; the water stays calm until they are ready.
        this.ripples = CompletableFuture.supplyAsync(
                () -> RainRipples.generate(SIZE, TILE_BLOCKS, RIPPLE_SEED));
    }

    /** Builds the raindrop rings, or returns null when a texture or the material fails. */
    public static RainSurfaceTextures create(RemixAPI api, Uploader uploader,
                                             Function<MaterialInfo, RemixMaterial> materials) {
        RemixTexture normals = uploader.upload("rain_ripple_normals", flatNormals(), SIZE, SIZE,
                TextureFormat.RGBA8_UNORM, TexturePurpose.NORMAL_OTH);
        RemixTexture rings = uploader.upload("rain_ripple_rings", new byte[SIZE * SIZE * 4], SIZE, SIZE,
                TextureFormat.RGBA8_UNORM, TexturePurpose.COLOR);
        if (normals == null || rings == null) return null;
        RemixMaterial film = materials.apply(MaterialInfo.builder()
                .albedoTexture(rings.getHandle())
                .normalTexture(normals.getHandle())
                .filterMode(1)
                .opaqueExt(MaterialInfoOpaqueEXT.builder()
                        .albedoConstant(1f, 1f, 1f)
                        .roughnessConstant(RIPPLE_ROUGHNESS)
                        .metallicConstant(0f)
                        .alphaTestType(ALPHA_TEST_GREATER)
                        .alphaReferenceValue(RIPPLE_ALPHA_REFERENCE)
                        .build())
                .build());
        if (film == null) {
            LOGGER.warn("Raindrop ring material failed; open water stays calm in the rain");
            return null;
        }
        return new RainSurfaceTextures(api, normals, rings, film);
    }

    public RemixMaterial rippleFilmMaterial() {
        return rippleFilmMaterial;
    }

    /**
     * Shows rain of {@code strength}, 0 to 1, at {@code seconds} into the ripple
     * animation. Uploads only what changed.
     */
    public void update(float strength, double seconds) {
        if (released) return;
        RainRipples frames = ripples.getNow(null);
        if (frames == null) return;
        if (strength <= 0f) {
            if (appliedStrength > 0f) {
                frames.writeNormals(0, 0f, texels);
                boolean cleared = upload(rippleNormals);
                frames.writeRings(0, 0f, RIPPLE_RGB, RIPPLE_MAX_ALPHA, texels);
                if (cleared && upload(rippleRings)) {
                    appliedStrength = 0f;
                    appliedFrame = -1;
                }
            }
            return;
        }
        int frame = RainRipples.frameAt(seconds);
        if (frame == appliedFrame && Math.abs(strength - appliedStrength) < STRENGTH_EPSILON) return;
        frames.writeNormals(frame, strength, texels);
        boolean drawn = upload(rippleNormals);
        frames.writeRings(frame, strength, RIPPLE_RGB, RIPPLE_MAX_ALPHA, texels);
        if (drawn && upload(rippleRings)) {
            appliedFrame = frame;
            appliedStrength = strength;
        }
    }

    private boolean upload(RemixTexture texture) {
        if (texture.isDeleted()) return false;
        staging.clear();
        staging.put(texels).flip();
        patch.pixelData = staging;
        entry.handle = texture.getHandle();
        try {
            api.updateTextureFromMemory(patchInfo);
            return true;
        } catch (RuntimeException e) {
            LOGGER.warn("Failed to update raindrop ring texture: {}", e.getMessage());
            return false;
        } finally {
            patch.pixelData = null;
        }
    }

    /** Returns the staging buffer; the owner destroys the textures and material. */
    public void release() {
        if (released) return;
        released = true;
        ripples.cancel(false);
        DirectBufferPool.release(staging);
    }

    /** Straight-up normals, octahedrally encoded like {@link RainRipples#writeNormals}. */
    private static byte[] flatNormals() {
        byte[] rgba = new byte[SIZE * SIZE * 4];
        for (int i = 0; i < SIZE * SIZE; i++) {
            rgba[i * 4] = (byte) 128;
            rgba[i * 4 + 1] = (byte) 128;
            rgba[i * 4 + 3] = (byte) 255;
        }
        return rgba;
    }
}
