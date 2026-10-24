/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.mixin;

import com.nvidia.rtxremix.minecraft.entity.ItemRenderSource;
import net.minecraft.client.renderer.item.ItemModelResolver;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.world.entity.ItemOwner;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Records the stack and holder every top-level item render state is resolved from;
 * see {@link ItemRenderSource}. Every item vanilla draws in the world, held, worn,
 * framed, displayed or dropped, is resolved through {@code updateForTopItem}.
 */
@Mixin(ItemModelResolver.class)
public abstract class ItemModelResolverMixin {

    @Inject(method = "updateForTopItem", at = @At("RETURN"))
    private void rtxremix$recordSource(ItemStackRenderState output, ItemStack item, ItemDisplayContext displayContext,
                                       @Nullable Level level, @Nullable ItemOwner owner, int seed, CallbackInfo ci) {
        ((ItemRenderSource) output).rtxremix$setSource(item, owner == null ? null : owner.asLivingEntity());
    }
}
