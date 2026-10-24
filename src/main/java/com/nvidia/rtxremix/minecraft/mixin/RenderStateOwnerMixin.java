/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.mixin;

import com.nvidia.rtxremix.minecraft.entity.RenderStateOwner;
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;

/** Gives entity and block-entity render states the slot {@link RenderStateOwner} describes. */
@Mixin({EntityRenderState.class, BlockEntityRenderState.class})
public abstract class RenderStateOwnerMixin implements RenderStateOwner {

    private @Nullable Object rtxremix$owner;

    @Override
    public @Nullable Object rtxremix$owner() {
        return rtxremix$owner;
    }

    @Override
    public void rtxremix$setOwner(@Nullable Object owner) {
        rtxremix$owner = owner;
    }
}
