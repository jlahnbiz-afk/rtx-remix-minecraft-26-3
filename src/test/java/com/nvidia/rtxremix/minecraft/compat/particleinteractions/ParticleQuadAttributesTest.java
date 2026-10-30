/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.compat.particleinteractions;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ParticleQuadAttributesTest {
    @Test void retainsPerQuadEmissionAcrossGrowthAndReuseWithoutInferringItFromLightmaps() {
        var attributes = new ParticleQuadAttributes();
        var context = ParticleExtractionContext.current();
        try {
            for (int q = 0; q < 600; q++) {
                context.emission = q % 16;
                for (int v = 0; v < 4; v++) attributes.record(q * 4 + v);
            }
            for (int q = 0; q < 600; q++) assertEquals(q % 16, attributes.emission(q));
            context.emission = 10;
            attributes.record(0);
            assertEquals(10, attributes.emission(0));
            assertEquals(1, attributes.emission(1));
            assertEquals(0, attributes.boundsMin(0));
        } finally { context.emission = 0; context.sprite = null; }
    }
}
