/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.mixin;

import com.nvidia.rtxremix.minecraft.texture.AtlasPadding;
import net.minecraft.client.renderer.texture.Stitcher;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Widens every atlas's sprite padding; see {@link AtlasPadding}. */
@Mixin(Stitcher.class)
public abstract class StitcherMixin {

    @Shadow @Final @Mutable private int padding;

    @Inject(method = "<init>", at = @At("RETURN"))
    private void rtxremix$widenPadding(int maxWidth, int maxHeight, int mipLevel, int anisotropyBits,
                                       CallbackInfo ci) {
        padding = AtlasPadding.widen(padding);
    }
}
