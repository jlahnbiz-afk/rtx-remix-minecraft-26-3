/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.compat.particleinteractions;

import com.nvidia.rtxremix.HardcodedVertex;
import java.util.Arrays;

/** One metadata record per source quad, with no references to reloadable sprite objects. */
public final class ParticleQuadAttributes {
    private int[] values = new int[0];
    public void record(int vertexIndex) {
        if ((vertexIndex & 3) != 0) return;
        ParticleExtractionContext context = ParticleExtractionContext.current();
        int index = vertexIndex / 4 * 3;
        if (index + 3 > values.length) values = Arrays.copyOf(values, Math.max(index + 3, Math.max(384, values.length * 2)));
        values[index] = Math.clamp(context.emission, 0, 15);
        var sprite = context.sprite;
        values[index + 1] = sprite == null ? 0 : HardcodedVertex.packHalf2x16(sprite.getU0(), sprite.getV0());
        values[index + 2] = sprite == null ? 0 : HardcodedVertex.packHalf2x16(sprite.getU1(), sprite.getV1());
    }
    public int emission(int quad) { return values[quad * 3]; }
    public int boundsMin(int quad) { return values[quad * 3 + 1]; }
    public int boundsMax(int quad) { return values[quad * 3 + 2]; }
}
