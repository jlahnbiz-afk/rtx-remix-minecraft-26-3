/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.mixin;

import com.nvidia.rtxremix.minecraft.JoinBurstHold;
import com.nvidia.rtxremix.minecraft.RemixRenderer;
import net.minecraft.client.gui.screens.LevelLoadingScreen;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Keeps the terrain-download screen up until Remix's world-join burst has settled.
 *
 * <p>Vanilla closes this screen from its tick as soon as the level reports ready, which
 * is while Remix pays its first-sight costs behind the screen. That close is skipped
 * while {@link JoinBurstHold#keepScreen(long)} says so; vanilla retries every tick, so
 * the screen closes on the first tick after the hold ends. Every other way the screen
 * closes is left alone.
 */
@Mixin(LevelLoadingScreen.class)
public abstract class DownloadingTerrainScreenMixin {

    private boolean rtxremix$holdBegun;

    @WrapOperation(
            method = "tick",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/screens/LevelLoadingScreen;onClose()V"))
    private void rtxremix$holdUntilSettled(LevelLoadingScreen screen, Operation<Void> original) {
        JoinBurstHold hold = RemixRenderer.activeJoinBurstHold();
        if (hold != null) {
            long now = System.nanoTime();
            if (!rtxremix$holdBegun) {
                rtxremix$holdBegun = true;
                hold.begin(now);
            }
            if (hold.keepScreen(now)) {
                return;
            }
        }
        original.call(screen);
    }
}
