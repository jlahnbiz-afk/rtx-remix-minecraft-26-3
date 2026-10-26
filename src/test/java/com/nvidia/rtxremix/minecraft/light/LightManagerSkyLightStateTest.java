/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.light;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LightManagerSkyLightStateTest {

    @ParameterizedTest
    @CsvSource({
            "1.0, -1.0, 3.0",
            "-1.0, 1.0, 0.06"
    })
    void weatherDomeRadiancePreservesAuthoredSourceScale(
            float sunHeight, float moonHeight, float expected) {
        LightManager.SkyLightState state = state(
                new float[]{0.0f, sunHeight, 0.0f},
                new float[]{0.0f, moonHeight, 0.0f},
                new float[]{30.0f, 30.0f, 30.0f},
                new float[]{0.6f, 0.6f, 0.6f});

        assertEquals(expected, state.weatherDomeRadiance(), 1.0e-6f);
    }

    @Test
    void theSunDiscInTheSkyTakesOnlyASmallShareOfTheSunlight() {
        // The distant sun gives this share up, so a larger one would move the sunlight onto escaping rays.
        assertTrue(LightManager.SUN_DISC_SHARE > 0.0f && LightManager.SUN_DISC_SHARE < 0.2f,
                "share " + LightManager.SUN_DISC_SHARE);
    }

    @Test
    void weatherInterpolatesDirectLightsWithoutReducingDomeSource() {
        float clearSun = LightManager.weatherSunDirectScale(0.0f, 0.0f);
        float rainSun = LightManager.weatherSunDirectScale(1.0f, 0.0f);
        float thunderSun = LightManager.weatherSunDirectScale(1.0f, 1.0f);
        float clearMoon = LightManager.weatherMoonDirectScale(0.0f, 0.0f);
        float rainMoon = LightManager.weatherMoonDirectScale(1.0f, 0.0f);
        float thunderMoon = LightManager.weatherMoonDirectScale(1.0f, 1.0f);

        assertEquals(1.0f, clearSun, 1.0e-6f);
        assertEquals(0.0025f, rainSun, 1.0e-6f);
        assertEquals(0.001f, thunderSun, 1.0e-6f);
        assertEquals(1.0f, clearMoon, 1.0e-6f);
        assertEquals(0.375f, rainMoon, 1.0e-6f);
        assertEquals(0.625f, thunderMoon, 1.0e-6f);
        assertEquals(
                (clearSun + rainSun) * 0.5f,
                LightManager.weatherSunDirectScale(0.5f, 0.0f),
                1.0e-6f);
        assertEquals(
                (rainSun + thunderSun) * 0.5f,
                LightManager.weatherSunDirectScale(1.0f, 0.5f),
                1.0e-6f);
        assertEquals(
                (clearMoon + rainMoon) * 0.5f,
                LightManager.weatherMoonDirectScale(0.5f, 0.0f),
                1.0e-6f);
        assertEquals(
                (rainMoon + thunderMoon) * 0.5f,
                LightManager.weatherMoonDirectScale(1.0f, 0.5f),
                1.0e-6f);
    }

    @Test
    void onlyASetTimeOfDayClearsTheRadianceCache() {
        float tick = 1.0f / 24000.0f;
        assertFalse(LightManager.isTimeJump(Float.NaN, 0.25f), "the first frame has nothing to jump from");
        assertFalse(LightManager.isTimeJump(0.25f, 0.25f + 3 * tick), "an ordinary frame");
        assertFalse(LightManager.isTimeJump(1.0f - tick, tick), "midnight wraps round");
        assertTrue(LightManager.isTimeJump(0.25f, 0.75f), "noon set to midnight");
        assertTrue(LightManager.isTimeJump(0.75f, 0.0f), "sleeping through to dawn");
    }

    private static LightManager.SkyLightState state(
            float[] sunPosition,
            float[] moonPosition,
            float[] sunSource,
            float[] moonSource) {
        return new LightManager.SkyLightState(
                sunPosition,
                moonPosition,
                sunSource,
                moonSource,
                new float[3],
                new float[3],
                0.0f,
                0.0f);
    }
}
