/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.weather;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RainSurfacesTest {

    @Test
    void groundFloodsOverTheFillTimeUpToTheRainsStrength() {
        float wetness = 0f;
        for (int second = 0; second < RainSurfaces.FILL_SECONDS / 2; second++) {
            wetness = RainSurfaces.step(wetness, 1f, 1f);
        }
        assertEquals(0.5f, wetness, 0.02f);
        assertEquals(0.6f, RainSurfaces.step(0.59f, 0.6f, 10f), 1.0e-6f);
    }

    @Test
    void groundDriesMoreSlowlyThanItFloods() {
        float dried = RainSurfaces.step(1f, 0f, 10f);
        float flooded = RainSurfaces.step(0f, 1f, 10f);
        assertEquals(1f - 10f / RainSurfaces.DRY_SECONDS, dried, 1.0e-6f);
        assertEquals(10f / RainSurfaces.FILL_SECONDS, flooded, 1.0e-6f);
        assertEquals(0f, RainSurfaces.step(0.01f, 0f, 60f));
    }

    @Test
    void lighterRainLeavesTheGroundPartlyFlooded() {
        assertEquals(0.3f, RainSurfaces.step(0.8f, 0.3f, 1000f), 1.0e-6f);
    }
}
