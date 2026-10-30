/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.texture;

import com.nvidia.rtxremix.MaterialInfo;
import com.nvidia.rtxremix.MaterialInfoOpaqueEXT;
import com.nvidia.rtxremix.minecraft.asset.RemixTexture;
import java.util.Arrays;

/** Borrowed atlas textures; their manager outlives the geometry materials. */
public record ParticleMaterialTextures(RemixTexture albedo, RemixTexture normal, RemixTexture height,
                                      RemixTexture roughness, RemixTexture metallic, RemixTexture emissive,
                                      float authoredEmission, long[] authoredEmissionBounds) {
    public static long boundsKey(int min, int max) {
        return ((long) min << 32) | Integer.toUnsignedLong(max);
    }

    /** Explicit resource-pack emission takes precedence over the lightmap fallback. */
    public int effectiveEmission(int minimumEmission, int min, int max) {
        return minimumEmission > 0 && Arrays.binarySearch(authoredEmissionBounds, boundsKey(min, max)) >= 0
                ? 0 : minimumEmission;
    }

    public MaterialInfo material(int minimumEmission) {
        MaterialInfoOpaqueEXT.Builder opaque = MaterialInfoOpaqueEXT.builder()
                .albedoConstant(1, 1, 1).roughnessConstant(1).metallicConstant(0)
                .useDrawCallAlphaState(true);
        if (roughness != null) opaque.roughnessTexture(roughness.getHandle());
        if (metallic != null) opaque.metallicTexture(metallic.getHandle());
        if (height != null) opaque.heightTexture(height.getHandle()).displaceIn(0.02f);
        MaterialInfo.Builder info = MaterialInfo.builder().albedoTexture(albedo.getHandle()).filterMode(0);
        if (normal != null) info.normalTexture(normal.getHandle());
        if (minimumEmission > 0) {
            info.emissiveTexture(albedo.getHandle()).emissiveIntensity(minimumEmission / 15.0f);
        } else if (emissive != null) {
            info.emissiveTexture(emissive.getHandle()).emissiveIntensity(authoredEmission);
        }
        return info.opaqueExt(opaque.build()).build();
    }
}
