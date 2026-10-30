/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.compat.particleinteractions.mixin;

import com.nvidia.rtxremix.minecraft.particle.ExtractedParticleGeometry;
import com.nvidia.rtxremix.minecraft.particle.ParticleGeometryQuads;
import com.nvidia.rtxremix.minecraft.particle.ParticleGeometryRenderer;
import java.util.Map;
import net.minecraft.client.particle.SingleQuadParticle;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

@Mixin(targets = "games.enchanted.eg_particle_interactions.common.particle.render.state.mc26_2.CustomParticleGeometryRenderState", remap = false)
public abstract class ParticleInteractionsStateMixin implements ExtractedParticleGeometry {
    @Shadow @Final private Map<SingleQuadParticle.Layer, ?> quadStoragePerLayer;
    @Shadow public abstract boolean isEmpty();
    @Override public boolean rtxremix$hasGeometry() { return !isEmpty(); }
    @Override public void rtxremix$captureGeometry(ParticleGeometryRenderer renderer) {
        for (var entry : quadStoragePerLayer.entrySet()) {
            ((ParticleGeometryQuads) entry.getValue()).rtxremix$writeQuads(renderer.layer(entry.getKey()));
        }
    }
}
