/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.weather;

import com.nvidia.rtxremix.minecraft.texture.BlockTextureManager;
import net.minecraft.client.multiplayer.ClientLevel;

/**
 * Drives the water's waves and, from the weather, its raindrop rings, once a frame on the
 * render thread. The rings follow the rain's strength itself, so they thin out as the
 * weather clears and stop with it. Both reach the runtime through
 * {@link BlockTextureManager#updateWaterSurfaces}, which sends only changes.
 */
public final class RainSurfaces {

    /** Longest frame the animation clock advances over, so a stall does not skip ahead. */
    private static final float MAX_STEP_SECONDS = 0.25f;

    private double animationSeconds;
    private long lastNanos;

    /** Advances the water's animation by the frame's time; {@code partialTick} interpolates the rain. */
    public void update(ClientLevel level, float partialTick, BlockTextureManager textures) {
        long now = System.nanoTime();
        if (lastNanos != 0) {
            animationSeconds += Math.min(MAX_STEP_SECONDS, Math.max(0f, (now - lastNanos) / 1.0e9f));
        }
        lastNanos = now;
        float rain = Math.max(0f, Math.min(1f, level.getRainLevel(partialTick)));
        textures.updateWaterSurfaces(rain, animationSeconds);
    }
}
