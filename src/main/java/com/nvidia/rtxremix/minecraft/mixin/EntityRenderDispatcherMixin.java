/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.mixin;

import com.nvidia.rtxremix.minecraft.entity.RenderStateOwner;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Records which entity each extracted render state describes; see {@link RenderStateOwner}.
 *
 * <p>{@code extractEntity} is the one door every entity state comes through, vanilla's
 * level extraction and the capture's own off-frustum sweep alike, so the owner is
 * always set by the time the state is submitted.
 */
@Mixin(EntityRenderDispatcher.class)
public abstract class EntityRenderDispatcherMixin {

    @Inject(method = "extractEntity", at = @At("RETURN"))
    private <E extends Entity> void rtxremix$recordOwner(E entity, float partialTicks,
                                                         CallbackInfoReturnable<EntityRenderState> cir) {
        ((RenderStateOwner) cir.getReturnValue()).rtxremix$setOwner(entity);
    }
}
