/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.weather;

import com.nvidia.rtxremix.minecraft.terrain.capture.PuddleField;
import com.nvidia.rtxremix.minecraft.texture.BlockTextureManager;
import net.minecraft.client.multiplayer.ClientLevel;

/**
 * Drives the rain surfaces from the weather, once a frame on the render thread.
 *
 * <p>Wetness rises while it rains, up to the rain's strength, and falls slowly once it
 * stops, so puddles spread over a downpour and dry out after it rather than appearing
 * and vanishing with the rain. Open water ripples while the rain is falling. Both reach
 * the runtime through {@link BlockTextureManager#applyRainSurfaces}, which sends only
 * changes.
 */
public final class RainSurfaces {

    /** Seconds of steady rain to flood the ground fully. */
    static final float FILL_SECONDS = 90f;
    /** Seconds for fully flooded ground to dry. */
    static final float DRY_SECONDS = 180f;
    /** Rain strength above which open water ripples. */
    static final float RIPPLE_RAIN_LEVEL = 0.2f;
    /** Longest frame the wetness integrates over, so a stall cannot flood or dry at once. */
    private static final float MAX_STEP_SECONDS = 0.25f;

    private float wetness;
    private long lastNanos;
    private ClientLevel lastLevel;

    /** Advances the wetness by the frame's time and applies it; {@code partialTick} interpolates the rain. */
    public void update(ClientLevel level, float partialTick, BlockTextureManager textures) {
        long now = System.nanoTime();
        float rain = level.getRainLevel(partialTick);
        if (level != lastLevel) {
            // A new world or dimension starts as wet as it is raining now.
            lastLevel = level;
            wetness = rain;
        } else {
            float seconds = Math.min(MAX_STEP_SECONDS, (now - lastNanos) / 1.0e9f);
            wetness = step(wetness, rain, seconds);
        }
        lastNanos = now;
        textures.applyRainSurfaces(
                PuddleField.reference(wetness * PuddleField.MAX_COVERAGE),
                rain > RIPPLE_RAIN_LEVEL);
    }

    /** Wetness after {@code seconds} under rain of strength {@code rain}, both 0 to 1. */
    static float step(float wetness, float rain, float seconds) {
        if (wetness < rain) {
            return Math.min(rain, wetness + seconds / FILL_SECONDS);
        }
        return Math.max(rain, wetness - seconds / DRY_SECONDS);
    }
}
