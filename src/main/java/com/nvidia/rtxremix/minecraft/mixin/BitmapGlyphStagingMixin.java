/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.mixin;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.nvidia.rtxremix.minecraft.texture.GlyphPageMirror;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Pairs a bitmap font's GPU staging buffer with the sheet image it was filled from.
 *
 * <p>A bitmap glyph's upload fetches the staging buffer and copies its window to the
 * page on the GPU; the fetch is the last point the sheet's CPU pixels are in reach, so
 * {@link GlyphPageMirror} remembers them for the copy that follows.
 */
@Mixin(targets = "net.minecraft.client.gui.font.providers.BitmapProvider$ImageDataHolder")
public abstract class BitmapGlyphStagingMixin {

    @Shadow @Final private NativeImage image;

    @Inject(method = "gpuData", at = @At("RETURN"))
    private void rtxremix$recordStaging(CallbackInfoReturnable<GpuBuffer> cir) {
        GlyphPageMirror.recordStaging(cir.getReturnValue(), image.getPointer(),
                image.getWidth(), image.format().components());
    }
}
