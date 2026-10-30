/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.compat.particleinteractions.mixin;

import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(targets = "games.enchanted.eg_particle_interactions.common.particle.ParticleInteractionsParticle", remap = false)
public interface ParticleInteractionsParticleAccessor {
    @Accessor("minLightEmission") int rtxremix$emission();
    @Accessor("currentSprite") TextureAtlasSprite rtxremix$sprite();
}
