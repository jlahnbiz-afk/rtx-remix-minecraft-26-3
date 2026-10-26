/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.lod.dh.mixin;

import com.nvidia.rtxremix.minecraft.lod.LodSection;
import com.seibel.distanthorizons.core.dataObjects.render.bufferBuilding.LodBufferContainer;
import com.seibel.distanthorizons.core.pos.DhSectionPos;
import com.seibel.distanthorizons.core.render.QuadTree.LodRenderSection;
import com.seibel.distanthorizons.core.wrapperInterfaces.world.IClientLevelWrapper;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.concurrent.CompletableFuture;

/**
 * Follows one Distant Horizons render section: the CPU vertex buffers it is
 * about to upload, whether its quadtree draws it, and its closing. Distant
 * Horizons pools those buffers, so the capture copies them before returning.
 */
@Mixin(LodRenderSection.class)
abstract class LodRenderSectionMixin {

    @Shadow @Final public long pos;
    @Shadow @Final private IClientLevelWrapper clientLevelWrapper;
    @Shadow private boolean renderingEnabled;

    @Unique private LodSection rtxremix$section;

    @Inject(method = "<init>", at = @At("TAIL"))
    private void rtxremix$track(CallbackInfo ci) {
        rtxremix$section = new LodSection(
                DhSectionPos.getMinCornerBlockX(pos),
                clientLevelWrapper.getMinHeight(),
                DhSectionPos.getMinCornerBlockZ(pos),
                DhSectionPos.getBlockWidth(pos));
    }

    @Inject(method = "uploadToGpuAsync", at = @At("HEAD"))
    private void rtxremix$captureGeometry(CompletableFuture<Void> parentFuture,
                                          ArrayList<ByteBuffer> opaqueBuffers,
                                          ArrayList<ByteBuffer> transparentBuffers,
                                          CallbackInfoReturnable<CompletableFuture<LodBufferContainer>> cir) {
        rtxremix$section.publishGeometry(opaqueBuffers, transparentBuffers);
    }

    @Inject(method = "setRenderingEnabled", at = @At("HEAD"))
    private void rtxremix$followEnabled(boolean enabled, CallbackInfo ci) {
        if (enabled != renderingEnabled) rtxremix$section.publishEnabled(enabled);
    }

    @Inject(method = "close", at = @At("HEAD"))
    private void rtxremix$followClose(CallbackInfo ci) {
        rtxremix$section.publishClosed();
    }
}
