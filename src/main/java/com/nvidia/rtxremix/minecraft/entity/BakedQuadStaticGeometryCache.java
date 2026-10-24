/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.entity;

/**
 * Cache attached to vanilla baked quads by a mixin.
 *
 * <p>The digest contains only bake-time geometry. A 26.3 baked quad is an immutable
 * record, so the digest is keyed by the quad's identity and the sprite bounds it was
 * folded with. Per-render tint and brightness remain outside the cache and are folded
 * into the mesh key on every draw.
 */
public interface BakedQuadStaticGeometryCache {

    boolean rtxremix$matchesStaticGeometry(int texcoordBoundsMin, int texcoordBoundsMax);

    long rtxremix$getStaticGeometryHash();

    void rtxremix$setStaticGeometryHash(long hash, int texcoordBoundsMin, int texcoordBoundsMax);
}
