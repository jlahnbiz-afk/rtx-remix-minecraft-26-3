/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.mixin;

import com.nvidia.rtxremix.minecraft.entity.RenderStateOwner;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderDispatcher;
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Records which block entity each extracted render state describes; see
 * {@link RenderStateOwner}. A block entity the dispatcher declines to extract has no
 * state to mark.
 */
@Mixin(BlockEntityRenderDispatcher.class)
public abstract class BlockEntityRenderDispatcherMixin {

    @Inject(method = "tryExtractRenderState", at = @At("RETURN"))
    private <E extends BlockEntity, S extends BlockEntityRenderState> void rtxremix$recordOwner(
            E blockEntity, float partialTicks, ModelFeatureRenderer.CrumblingOverlay breakProgress,
            boolean isGloballyRendered, CallbackInfoReturnable<S> cir) {
        S state = cir.getReturnValue();
        if (state != null) {
            ((RenderStateOwner) state).rtxremix$setOwner(blockEntity);
        }
    }
}
