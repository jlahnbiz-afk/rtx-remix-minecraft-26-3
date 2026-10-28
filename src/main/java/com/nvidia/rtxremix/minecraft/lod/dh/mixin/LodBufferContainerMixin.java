/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.lod.dh.mixin;

import com.nvidia.rtxremix.minecraft.lod.dh.DistantHorizonsCompat;
import com.seibel.distanthorizons.core.dataObjects.render.bufferBuilding.LodBufferContainer;
import com.seibel.distanthorizons.core.wrapperInterfaces.render.objects.IVertexBufferWrapper;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.concurrent.CompletableFuture;

/**
 * While capturing, Distant Horizons draws nothing, so its vertex and index
 * buffers would only hold video memory and cost the render thread their
 * uploads: the upload completes at once without them, and the section still
 * counts as uploaded for Distant Horizons' detail swaps. Turning capture off
 * rebuilds every section with real buffers.
 */
@Mixin(LodBufferContainer.class)
abstract class LodBufferContainerMixin {

    @Inject(method = "uploadBuffersAsync", at = @At("HEAD"), cancellable = true)
    private static void rtxremix$skipUpload(CompletableFuture<LodBufferContainer> future,
                                            IVertexBufferWrapper[] wrappers,
                                            ArrayList<ByteBuffer> vertexBuffers,
                                            ArrayList<ByteBuffer> indexBuffers,
                                            CallbackInfoReturnable<CompletableFuture<Void>> cir) {
        if (DistantHorizonsCompat.capturing()) cir.setReturnValue(CompletableFuture.completedFuture(null));
    }
}
