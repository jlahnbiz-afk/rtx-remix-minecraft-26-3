/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.lod.dh.mixin;

import com.nvidia.rtxremix.minecraft.RtxRemixMod;
import com.seibel.distanthorizons.common.wrappers.minecraft.MinecraftClientWrapper;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Keeps Minecraft's cloud setting as the player left it. Distant Horizons turns vanilla clouds
 * off the first time it draws, so they cannot cover its LODs, but while the capture runs it
 * draws no LODs itself, and the renderer draws its own clouds in the path-traced scene by that
 * setting, so turning it off removed every cloud.
 */
@Mixin(MinecraftClientWrapper.class)
abstract class MinecraftClientWrapperMixin {

    @Inject(method = "disableVanillaClouds", at = @At("HEAD"), cancellable = true)
    private void rtxremix$keepClouds(CallbackInfo ci) {
        if (RtxRemixMod.isRtxEnabled()) ci.cancel();
    }
}
