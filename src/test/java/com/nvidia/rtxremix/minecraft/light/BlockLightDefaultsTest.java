/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.light;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class BlockLightDefaultsTest {

    @Test
    void aSphereStandsOutOfABoxByItsCaps() {
        // A cap of height h has area 2πrh, so caps of 0.12 on six faces of a sphere of 0.62.
        assertEquals(6 * 0.12f / (2 * 0.62f),
                BlockLightDefaults.exposedShare(0.62f, 0.5f, 0.5f, 0.5f, 0.5f, 0.5f, 0.5f), 1e-5f);
        assertEquals(0f, BlockLightDefaults.exposedShare(0.4f, 0.5f, 0.5f), 0f);
    }

    @Test
    void aLightOfAGivenLevelGivesOffATorchsPowerPerLevel() {
        // A sea lantern's sphere, level 15: what stands out of its faces gives off 15/14 of a
        // torch, whatever its colour and authored multiplier.
        LightEntry.ColorOverride color = LightEntry.ColorOverride.of(0.7f, 0.92f, 1.0f);
        float exposed = BlockLightDefaults.exposedShare(0.62f, 0.5f, 0.5f, 0.5f, 0.5f, 0.5f, 0.5f);
        float authored = 5.0f;
        float intensity = BlockLightDefaults.torchParityIntensity(15, 0.62f, exposed, color, authored);
        float radiance = intensity * authored;
        float emitted = LightPower.of(LightShape.SPHERE, 0.62f, 0f, 0f, 0f,
                color.r() * radiance, color.g() * radiance, color.b() * radiance) * exposed;
        assertEquals(LightPower.torchReference() * 15f / 14f, emitted, 0.5f);
    }
}
