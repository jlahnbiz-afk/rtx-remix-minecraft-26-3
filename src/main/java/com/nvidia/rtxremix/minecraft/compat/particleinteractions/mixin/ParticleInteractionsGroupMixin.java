/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.compat.particleinteractions.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.nvidia.rtxremix.minecraft.RtxRemixMod;
import com.nvidia.rtxremix.minecraft.compat.particleinteractions.ParticleExtractionContext;
import net.minecraft.client.Camera;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.world.phys.AABB;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;

@Mixin(targets = "games.enchanted.eg_particle_interactions.common.particle.render.group.CustomGeometryParticleGroup", remap = false)
public abstract class ParticleInteractionsGroupMixin {
    @WrapOperation(method = "extractRenderState", at = @At(value = "INVOKE",
            target = "Lgames/enchanted/eg_particle_interactions/common/particle/ParticleInteractionsParticle;extract(Lgames/enchanted/eg_particle_interactions/common/particle/render/geometry/QuadConsumerProvider;Lnet/minecraft/client/Camera;F)V"))
    private void rtxremix$captureAttributes(@Coerce Object particle, @Coerce Object provider,
                                           Camera camera, float delta, Operation<Void> original) {
        ParticleExtractionContext context = ParticleExtractionContext.current();
        int previousEmission = context.emission;
        var previousSprite = context.sprite;
        var source = (ParticleInteractionsParticleAccessor) particle;
        context.emission = source.rtxremix$emission();
        context.sprite = source.rtxremix$sprite();
        try { original.call(particle, provider, camera, delta); }
        finally { context.emission = previousEmission; context.sprite = previousSprite; }
    }

    /** Reflected and indirect rays can see active particles outside the primary frustum. */
    @WrapOperation(method = "extractRenderState", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/culling/Frustum;isVisible(Lnet/minecraft/world/phys/AABB;)Z"))
    private boolean rtxremix$rayVisible(Frustum frustum, AABB bounds, Operation<Boolean> original) {
        return RtxRemixMod.isRemixWorldRendering() && RtxRemixMod.isParticlesEnabled()
                || original.call(frustum, bounds);
    }
}
