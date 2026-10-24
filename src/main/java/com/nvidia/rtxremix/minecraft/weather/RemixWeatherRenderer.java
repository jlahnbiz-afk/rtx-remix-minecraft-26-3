/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.weather;

import net.minecraft.client.Camera;

/**
 * Draws rain and snow over the composited Remix colour image.
 *
 * <p>Not yet ported to 26.3. The 1.21.1 renderer drew vanilla's captured weather
 * meshes with raw GL and a refraction core shader, depth-tested against the interop
 * depth texture. 26.3 draws weather from a vertex buffer through a render pipeline,
 * with reversed depth and no core-shader registration, so the overlay needs a
 * renderpearl pipeline and a reversed-depth seed before it can return. Until then
 * the Remix image covers vanilla's weather and no weather is drawn over it; see
 * PORTING.md.
 */
public final class RemixWeatherRenderer {

    public static void registerShaders() {
    }

    public void updateCamera(Camera camera, float fovYDegrees, float aspect) {
    }

    public void render(int finalColorTexture, int depthTexture) {
    }

    public int getLastDrawCount() {
        return 0;
    }

    public int getLastQuadCount() {
        return 0;
    }

    public void cleanup() {
    }
}
