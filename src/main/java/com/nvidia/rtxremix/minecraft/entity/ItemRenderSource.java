/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.entity;

import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import org.jspecify.annotations.Nullable;

/**
 * What an item render state was resolved from, kept on the state for the capture.
 *
 * <p>Vanilla resolves an item into baked quads during extraction and submits only the
 * quads, so by the time the capture sees them the stack and its holder are gone.
 * {@code ItemModelResolverMixin} records them here; {@code ItemStackRenderStateMixin}
 * hands them to {@link EntityRenderContext} for the item's submit, and the held flame
 * and smoke read the stack back.
 */
public interface ItemRenderSource {

    /** Records the stack a state was resolved from and its living holder, if any. */
    void rtxremix$setSource(ItemStack stack, @Nullable LivingEntity holder);

    @Nullable ItemStack rtxremix$stack();

    @Nullable LivingEntity rtxremix$holder();

    /**
     * The terrain layer of the block item this state draws, as a
     * {@code BlockTextureManager} layer type, or {@link EntityRenderContext#NO_BLOCK_ITEM}.
     */
    int rtxremix$blockItemLayer();
}
