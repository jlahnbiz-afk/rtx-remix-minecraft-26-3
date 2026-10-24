/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.sky;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import net.minecraft.client.renderer.state.level.SkyRenderState;
import net.minecraft.util.Mth;

/**
 * The sky and cloud values vanilla extracted for the frame being rendered.
 *
 * <p>In 26.3 the sun, moon, stars, sky colour and clouds are environment
 * attributes, evaluated once per frame into the level render state before the
 * frame renders. Remix reads the same values so its sky agrees with vanilla's;
 * the frame it submits is rendered after that extraction.
 */
public final class SkyFrame {

    private SkyFrame() {}

    public static LevelRenderState level() {
        return Minecraft.getInstance().gameRenderer.gameRenderState().levelRenderState;
    }

    public static SkyRenderState sky() {
        return level().skyRenderState;
    }

    /**
     * The fraction of a full turn the sun has made, 0 at noon, as vanilla's sky
     * rotation takes it.
     */
    public static float timeOfDay(SkyRenderState sky) {
        return sky.sunAngle / Mth.TWO_PI;
    }
}
