/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.weather;

import com.nvidia.rtxremix.minecraft.texture.BlockTextureManager;
import net.minecraft.client.multiplayer.ClientLevel;

/**
 * Drives the rain surfaces from the weather, once a frame on the render thread.
 *
 * <p>Wetness rises while it rains, up to the rain's strength, and falls once it stops,
 * so puddles spread from their deepest spots over a shower and dry back after it rather
 * than appearing and vanishing with the rain. Raindrop rings follow the rain's strength
 * itself, so they thin out as the weather clears and stop with it. Both reach the
 * runtime through {@link BlockTextureManager#updateRainSurfaces}, which sends only
 * changes.
 */
public final class RainSurfaces {

    /** Seconds of steady rain to flood the ground fully. */
    static final float FILL_SECONDS = 20f;
    /** Seconds for fully flooded ground to dry. */
    static final float DRY_SECONDS = 30f;
    /** Longest frame the clocks advance over, so a stall cannot flood or dry at once. */
    private static final float MAX_STEP_SECONDS = 0.25f;

    private float wetness;
    private double animationSeconds;
    private long lastNanos;
    private ClientLevel lastLevel;

    /** Advances the wetness by the frame's time and applies it; {@code partialTick} interpolates the rain. */
    public void update(ClientLevel level, float partialTick, BlockTextureManager textures) {
        long now = System.nanoTime();
        float rain = Math.max(0f, Math.min(1f, level.getRainLevel(partialTick)));
        if (level != lastLevel) {
            // A new world or dimension starts as wet as it is raining now.
            lastLevel = level;
            wetness = rain;
        } else {
            float seconds = Math.min(MAX_STEP_SECONDS, Math.max(0f, (now - lastNanos) / 1.0e9f));
            wetness = step(wetness, rain, seconds);
            animationSeconds += seconds;
        }
        lastNanos = now;
        textures.updateRainSurfaces(wetness, rain, animationSeconds);
    }

    /** Wetness after {@code seconds} under rain of strength {@code rain}, both 0 to 1. */
    static float step(float wetness, float rain, float seconds) {
        if (wetness < rain) {
            return Math.min(rain, wetness + seconds / FILL_SECONDS);
        }
        return Math.max(rain, wetness - seconds / DRY_SECONDS);
    }
}
