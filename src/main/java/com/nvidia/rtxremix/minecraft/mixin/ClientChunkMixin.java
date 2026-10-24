/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.nvidia.rtxremix.minecraft.terrain.capture.RenderRegionStoragePool;
import net.minecraft.client.renderer.chunk.SectionCopy;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.PalettedContainer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Marks the render thread as taking a region snapshot while a {@code SectionCopy} copies its
 * section's block states, so the copy draws pooled storage; see {@link SectionCopyMixin}, which
 * also takes the cache's reference on the new snapshot.
 */
@Mixin(SectionCopy.class)
public abstract class ClientChunkMixin {

    @WrapOperation(
            method = "<init>",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/chunk/PalettedContainer;copy()Lnet/minecraft/world/level/chunk/PalettedContainer;")
    )
    private PalettedContainer<BlockState> rtxremix$copyWithPooledStorage(
            PalettedContainer<BlockState> states, Operation<PalettedContainer<BlockState>> original) {
        RenderRegionStoragePool.beginCopying();
        try {
            return original.call(states);
        } finally {
            RenderRegionStoragePool.endCopying();
        }
    }
}
