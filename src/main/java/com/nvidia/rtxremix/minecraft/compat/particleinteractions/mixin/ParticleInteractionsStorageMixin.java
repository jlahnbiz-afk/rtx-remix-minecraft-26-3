/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.compat.particleinteractions.mixin;

import com.nvidia.rtxremix.minecraft.compat.particleinteractions.ParticleQuadAttributes;
import com.nvidia.rtxremix.minecraft.particle.ParticleGeometryQuads;
import com.nvidia.rtxremix.minecraft.particle.ParticleGeometrySink;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(targets = "games.enchanted.eg_particle_interactions.common.particle.render.state.QuadStorage", remap = false)
public abstract class ParticleInteractionsStorageMixin implements ParticleGeometryQuads {
    @Shadow private float[] floats;
    @Shadow private int[] ints;
    @Shadow private int currentVertexIndex;
    @Unique private final ParticleQuadAttributes rtxremix$attributes = new ParticleQuadAttributes();

    @Inject(method = "putData", at = @At("HEAD"))
    private void rtxremix$recordAttributes(CallbackInfo ci) {
        rtxremix$attributes.record(currentVertexIndex);
    }

    @Override public void rtxremix$writeQuads(ParticleGeometrySink sink) {
        if ((currentVertexIndex & 3) != 0) throw new IllegalStateException("Incomplete extracted particle quad");
        for (int vertex = 0; vertex < currentVertexIndex; vertex += 4) {
            int quad = vertex / 4;
            sink.beginQuad(rtxremix$attributes.emission(quad), rtxremix$attributes.boundsMin(quad), rtxremix$attributes.boundsMax(quad));
            for (int i = vertex; i < vertex + 4; i++) {
                int f = i * 5;
                sink.vertex(floats[f], floats[f + 1], floats[f + 2], floats[f + 3], floats[f + 4], ints[i * 2 + 1]);
            }
            sink.endQuad();
        }
    }
}
