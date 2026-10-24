/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.mixin;

import com.nvidia.rtxremix.minecraft.entity.BakedQuadStaticGeometryCache;
import org.spongepowered.asm.mixin.Mixin;

import net.minecraft.client.resources.model.geometry.BakedQuad;

/** Stores the bake-time geometry digest used by Remix entity mesh pooling. */
@Mixin(BakedQuad.class)
public class BakedQuadMixin implements BakedQuadStaticGeometryCache {

    private boolean rtxremix$staticGeometryValid;
    private long rtxremix$staticGeometryHash;
    private int rtxremix$staticGeometryBoundsMin;
    private int rtxremix$staticGeometryBoundsMax;

    @Override
    public boolean rtxremix$matchesStaticGeometry(int texcoordBoundsMin, int texcoordBoundsMax) {
        return rtxremix$staticGeometryValid
                && rtxremix$staticGeometryBoundsMin == texcoordBoundsMin
                && rtxremix$staticGeometryBoundsMax == texcoordBoundsMax;
    }

    @Override
    public long rtxremix$getStaticGeometryHash() {
        return rtxremix$staticGeometryHash;
    }

    @Override
    public void rtxremix$setStaticGeometryHash(long hash, int texcoordBoundsMin, int texcoordBoundsMax) {
        rtxremix$staticGeometryBoundsMin = texcoordBoundsMin;
        rtxremix$staticGeometryBoundsMax = texcoordBoundsMax;
        rtxremix$staticGeometryHash = hash;
        rtxremix$staticGeometryValid = true;
    }
}
