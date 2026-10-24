/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import com.nvidia.rtxremix.minecraft.entity.EntityRenderContext;
import com.nvidia.rtxremix.minecraft.entity.ItemRenderSource;
import com.nvidia.rtxremix.minecraft.terrain.capture.BlockQuadEmitter;
import com.nvidia.rtxremix.minecraft.texture.BlockTextureManager;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Keeps the {@link ItemRenderSource} of an item render state, and hands it to the
 * capture for the duration of the state's submit.
 *
 * <p>Minecraft routes ordinary opaque items through its translucent item layer, and
 * clear glass through its cutout one, so the render layer alone cannot tell a pane of
 * stained glass from an apple, or clear glass from a torch. The stack can, and the
 * layer is classified once, when the state is resolved. The holder travels the same
 * way so the held flame can tell whether a torch is burning.
 */
@Mixin(ItemStackRenderState.class)
public abstract class ItemStackRenderStateMixin implements ItemRenderSource {

    private @Nullable ItemStack rtxremix$stack;
    private @Nullable LivingEntity rtxremix$holder;
    private int rtxremix$blockItemLayer = EntityRenderContext.NO_BLOCK_ITEM;

    @Override
    public void rtxremix$setSource(ItemStack stack, @Nullable LivingEntity holder) {
        rtxremix$stack = stack;
        rtxremix$holder = holder;
        rtxremix$blockItemLayer = rtxremix$blockLayer(stack);
    }

    @Override
    public @Nullable ItemStack rtxremix$stack() {
        return rtxremix$stack;
    }

    @Override
    public @Nullable LivingEntity rtxremix$holder() {
        return rtxremix$holder;
    }

    @Override
    public int rtxremix$blockItemLayer() {
        return rtxremix$blockItemLayer;
    }

    @Inject(method = "clear", at = @At("HEAD"))
    private void rtxremix$clearSource(CallbackInfo ci) {
        rtxremix$stack = null;
        rtxremix$holder = null;
        rtxremix$blockItemLayer = EntityRenderContext.NO_BLOCK_ITEM;
    }

    @Inject(method = "submit", at = @At("HEAD"))
    private void rtxremix$beginItem(PoseStack poseStack, SubmitNodeCollector collector, int lightCoords,
                                    int overlayCoords, int outlineColor, CallbackInfo ci) {
        EntityRenderContext context = EntityRenderContext.get();
        if (context.isRemixPass()) {
            context.setBlockItemLayer(rtxremix$blockItemLayer);
            context.setHeldItemHolder(rtxremix$holder);
        }
    }

    @Inject(method = "submit", at = @At("RETURN"))
    private void rtxremix$endItem(PoseStack poseStack, SubmitNodeCollector collector, int lightCoords,
                                  int overlayCoords, int outlineColor, CallbackInfo ci) {
        EntityRenderContext context = EntityRenderContext.get();
        if (context.isRemixPass()) {
            context.setBlockItemLayer(EntityRenderContext.NO_BLOCK_ITEM);
            context.clearHeldItemHolder();
        }
    }

    /**
     * Asks the question terrain asks of a placed block, through the same
     * classifier, so a held block is classified exactly as its placed
     * counterpart is.
     */
    private static int rtxremix$blockLayer(ItemStack stack) {
        return stack.getItem() instanceof BlockItem blockItem
                ? BlockTextureManager.layerType(
                        BlockQuadEmitter.classifyBlockLayer(blockItem.getBlock().defaultBlockState()))
                : EntityRenderContext.NO_BLOCK_ITEM;
    }
}
