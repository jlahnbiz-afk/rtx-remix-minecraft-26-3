/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.sky;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SkyLookTest {

    private static final float LOW_SUN = 0.25f;

    @Test
    void noRainNoRainbow() {
        SkyLook.Rainbow rainbow = new SkyLook.Rainbow();
        assertEquals(0.0f, rainbow.update(0.0f, LOW_SUN, 1.0f));
    }

    @Test
    void aRainbowShowsAsRainClearsAndFades() {
        SkyLook.Rainbow rainbow = new SkyLook.Rainbow();
        assertEquals(0.0f, rainbow.update(1.0f, LOW_SUN, 1.0f), "not while it pours");
        float cleared = rainbow.update(0.0f, LOW_SUN, 1.0f);
        assertTrue(cleared > 0.9f, "right after the rain: " + cleared);
        float later = rainbow.update(0.0f, LOW_SUN, SkyLook.Rainbow.FADE_SECONDS / 2);
        assertTrue(later > 0.3f && later < 0.7f, "half faded: " + later);
        assertEquals(0.0f, rainbow.update(0.0f, LOW_SUN, SkyLook.Rainbow.FADE_SECONDS));
    }

    @Test
    void aRainbowNeedsALowSunAboveTheHorizon() {
        SkyLook.Rainbow night = new SkyLook.Rainbow();
        night.update(1.0f, -0.3f, 1.0f);
        assertEquals(0.0f, night.update(0.0f, -0.3f, 1.0f));

        SkyLook.Rainbow noon = new SkyLook.Rainbow();
        noon.update(1.0f, 0.95f, 1.0f);
        assertEquals(0.0f, noon.update(0.0f, 0.95f, 1.0f));
    }

    @Test
    void settingsClampToTheirRanges() {
        float before = SkyLook.getSaturation();
        try {
            SkyLook.setSaturation(99.0f);
            assertEquals(SkyLook.MAX_SATURATION, SkyLook.getSaturation());
            SkyLook.setSaturation(-1.0f);
            assertEquals(0.0f, SkyLook.getSaturation());
        } finally {
            SkyLook.setSaturation(before);
        }
    }
}
