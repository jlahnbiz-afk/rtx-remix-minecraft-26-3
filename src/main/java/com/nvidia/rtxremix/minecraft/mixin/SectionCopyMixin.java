/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.nvidia.rtxremix.minecraft.terrain.capture.RegionSnapshotRelease;
import com.nvidia.rtxremix.minecraft.terrain.capture.RenderRegionStoragePool;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.client.renderer.chunk.SectionCopy;
import net.minecraft.util.SimpleBitStorage;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.PalettedContainer;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * A section snapshot for render regions whose block-state copy draws its storage from
 * {@link RenderRegionStoragePool} and gives it back when the last holder lets go: the region
 * cache that created it and each region that includes it, up to 27 of one frame's regions,
 * hence the count. The cache's reference is taken here at construction; the pool flag is set
 * around the copy by {@link ClientChunkMixin}, and vanilla's other copies are untouched.
 */
@Mixin(SectionCopy.class)
public abstract class SectionCopyMixin implements RegionSnapshotRelease {

    @Shadow
    @Final
    private @Nullable PalettedContainer<BlockState> section;

    private final AtomicInteger rtxremix$holders = new AtomicInteger();

    /** Set once the storage went back to the pool; nothing may read the snapshot after that. */
    private boolean rtxremix$recycled;

    @Inject(method = "<init>", at = @At("RETURN"))
    private void rtxremix$retainForCache(LevelChunk levelChunk, int sectionIndex, CallbackInfo ci) {
        rtxremix$holders.incrementAndGet();
    }

    @Override
    public void rtxremix$retain() {
        rtxremix$holders.incrementAndGet();
    }

    @Override
    public void rtxremix$release() {
        if (rtxremix$holders.decrementAndGet() != 0 || section == null) {
            return;
        }
        if (section.data.storage() instanceof SimpleBitStorage packed) {
            RenderRegionStoragePool.release(packed.getRaw());
        }
        rtxremix$recycled = true;
    }

    /**
     * Makes a stale read fail loudly instead of returning whatever the recycled array holds
     * next. A plain field read and branch on a path vanilla runs for every block it meshes.
     */
    @ModifyExpressionValue(
            method = "getBlockState",
            at = @At(value = "FIELD", target = "Lnet/minecraft/client/renderer/chunk/SectionCopy;section:Lnet/minecraft/world/level/chunk/PalettedContainer;"))
    private PalettedContainer<BlockState> rtxremix$rejectRecycledRead(PalettedContainer<BlockState> states) {
        if (rtxremix$recycled) {
            throw new IllegalStateException("render region snapshot read after its storage was recycled");
        }
        return states;
    }
}
