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
import com.nvidia.rtxremix.minecraft.terrain.capture.PuddleField;
import com.nvidia.rtxremix.minecraft.util.DirectBufferPool;
import java.nio.ByteBuffer;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The textures and materials of the rain surfaces, and the weather they show.
 *
 * <p>Two films sit over the terrain. A puddle film lies on open ground and takes its
 * shape from the puddle mask; a ripple film lies on open water and shows only where a
 * raindrop ring disturbs it. Both are smooth, alpha-blended surfaces under one shared
 * ripple normal map. The primary ray blends them stochastically, so a soft rim reads as
 * ground gradually going under water.
 *
 * <p>The materials never change after they are made: the weather reaches the runtime as
 * texture content, the one path the atlas animations already exercise every frame. The
 * mask is rewritten as the ground floods and dries; the ripples are rewritten a frame at
 * a time while rain falls, at the rain's strength, and cleared once it stops, which also
 * leaves the ripple film fully transparent. Render thread only.
 */
public final class RainSurfaceTextures {

    private static final Logger LOGGER = LoggerFactory.getLogger(RainSurfaceTextures.class);

    /** Mask colour: shallow water over ground reads dark, with its reflection on top. */
    private static final int PUDDLE_RGB = 0x101214;
    private static final int RIPPLE_RGB = 0x0A0C0E;
    /** How opaque the most disturbed water in a ring gets. */
    private static final float RIPPLE_MAX_ALPHA = 0.6f;
    private static final float PUDDLE_ROUGHNESS = 0.02f;
    private static final float RIPPLE_ROUGHNESS = 0.0f;
    private static final long RIPPLE_SEED = 0x52414E44L;
    /** Steps the mask's wetness is rounded to, so a slow drift rewrites it a few times a second at most. */
    private static final int WETNESS_STEPS = 256;
    /** Rain strength change that redraws a held ripple frame. */
    private static final float STRENGTH_EPSILON = 0.02f;

    /** Creates a texture the owner manages and destroys. */
    public interface Uploader {
        RemixTexture upload(String name, byte[] rgba, int width, int height, int format, int purpose);
    }

    private final RemixAPI api;
    private final RemixTexture puddleMask;
    private final RemixTexture rippleNormals;
    private final RemixTexture rippleRings;
    private final RemixMaterial puddleMaterial;
    private final RemixMaterial rippleFilmMaterial;
    private final CompletableFuture<RainRipples> ripples;

    private final int size = PuddleField.MASK_SIZE;
    private final byte[] texels = new byte[size * size * 4];
    private final ByteBuffer staging;
    private final TexturePatch patch = new TexturePatch();
    private final TextureBatchEntry entry = new TextureBatchEntry();
    private final TexturePatchInfo patchInfo = new TexturePatchInfo();

    private int appliedWetnessStep = 0;
    private int appliedFrame = -1;
    private float appliedStrength = 0f;
    private boolean released;

    private RainSurfaceTextures(RemixAPI api, RemixTexture puddleMask, RemixTexture rippleNormals,
                                RemixTexture rippleRings, RemixMaterial puddleMaterial,
                                RemixMaterial rippleFilmMaterial) {
        this.api = api;
        this.puddleMask = puddleMask;
        this.rippleNormals = rippleNormals;
        this.rippleRings = rippleRings;
        this.puddleMaterial = puddleMaterial;
        this.rippleFilmMaterial = rippleFilmMaterial;
        this.staging = DirectBufferPool.acquire(texels.length);
        patch.width = size;
        patch.height = size;
        patch.rowPitch = size * 4;
        patch.format = TextureFormat.RGBA8_UNORM;
        entry.patches = new TexturePatch[] {patch};
        patchInfo.entries = new TextureBatchEntry[] {entry};
        // The rings take a few hundred milliseconds to simulate; the surfaces stay calm until they are ready.
        this.ripples = CompletableFuture.supplyAsync(
                () -> RainRipples.generate(size, PuddleField.TILE_BLOCKS, RIPPLE_SEED));
    }

    /** Builds the rain surfaces, or returns null when a texture or material fails. */
    public static RainSurfaceTextures create(RemixAPI api, Uploader uploader,
                                             Function<MaterialInfo, RemixMaterial> materials) {
        int size = PuddleField.MASK_SIZE;
        byte[] dry = new byte[size * size * 4];
        PuddleField.writeMask(0f, PUDDLE_RGB, dry);
        RemixTexture mask = uploader.upload("rain_puddle_mask", dry, size, size,
                TextureFormat.RGBA8_UNORM, TexturePurpose.COLOR);
        RemixTexture normals = uploader.upload("rain_ripple_normals", flatNormals(size), size, size,
                TextureFormat.RGBA8_UNORM, TexturePurpose.NORMAL_OGL);
        byte[] calm = new byte[size * size * 4];
        RemixTexture rings = uploader.upload("rain_ripple_rings", calm, size, size,
                TextureFormat.RGBA8_UNORM, TexturePurpose.COLOR);
        if (mask == null || normals == null || rings == null) return null;
        RemixMaterial puddle = materials.apply(filmInfo(mask, normals, PUDDLE_ROUGHNESS));
        RemixMaterial film = materials.apply(filmInfo(rings, normals, RIPPLE_ROUGHNESS));
        if (puddle == null || film == null) return null;
        return new RainSurfaceTextures(api, mask, normals, rings, puddle, film);
    }

    /** A smooth, flat-coloured film whose albedo alpha blends it over what lies beneath. */
    private static MaterialInfo filmInfo(RemixTexture albedo, RemixTexture normals, float roughness) {
        MaterialInfoOpaqueEXT opaque = MaterialInfoOpaqueEXT.builder()
                .albedoConstant(1f, 1f, 1f)
                .roughnessConstant(roughness)
                .metallicConstant(0f)
                .blendEnabled(true)
                .blendType(MaterialInfoOpaqueEXT.BLEND_TYPE_ALPHA)
                .build();
        return MaterialInfo.builder()
                .albedoTexture(albedo.getHandle())
                .normalTexture(normals.getHandle())
                .filterMode(1)
                .opaqueExt(opaque)
                .build();
    }

    public RemixMaterial puddleMaterial() {
        return puddleMaterial;
    }

    public RemixMaterial rippleFilmMaterial() {
        return rippleFilmMaterial;
    }

    /**
     * Shows ground {@code wetness} and rain {@code strength}, both 0 to 1, at
     * {@code seconds} into the ripple animation. Uploads only what changed.
     */
    public void update(float wetness, float strength, double seconds) {
        if (released) return;
        int step = Math.round(Math.max(0f, Math.min(1f, wetness)) * WETNESS_STEPS);
        if (step != appliedWetnessStep) {
            PuddleField.writeMask(step / (float) WETNESS_STEPS, PUDDLE_RGB, texels);
            if (upload(puddleMask)) appliedWetnessStep = step;
        }

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
            LOGGER.warn("Failed to update rain surface texture: {}", e.getMessage());
            return false;
        } finally {
            patch.pixelData = null;
        }
    }

    /** Returns the staging buffer; the owner destroys the textures and materials. */
    public void release() {
        if (released) return;
        released = true;
        ripples.cancel(false);
        DirectBufferPool.release(staging);
    }

    private static byte[] flatNormals(int size) {
        byte[] rgba = new byte[size * size * 4];
        for (int i = 0; i < size * size; i++) {
            rgba[i * 4] = (byte) 128;
            rgba[i * 4 + 1] = (byte) 128;
            rgba[i * 4 + 2] = (byte) 255;
            rgba[i * 4 + 3] = (byte) 255;
        }
        return rgba;
    }
}
