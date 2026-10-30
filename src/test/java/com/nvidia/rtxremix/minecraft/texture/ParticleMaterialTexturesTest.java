/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.texture;

import java.util.Arrays;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ParticleMaterialTexturesTest {
    @Test void explicitSpriteEmissionWinsWithoutSuppressingOtherSprites() {
        long[] authored = {ParticleMaterialTextures.boundsKey(0x80000000, 13),
                ParticleMaterialTextures.boundsKey(12, 19)};
        Arrays.sort(authored);
        var textures = new ParticleMaterialTextures(null, null, null, null, null, null, 40, authored);
        assertEquals(0, textures.effectiveEmission(15, 12, 19));
        assertEquals(0, textures.effectiveEmission(5, 0x80000000, 13));
        assertEquals(15, textures.effectiveEmission(15, 12, 20));
        assertEquals(0, textures.effectiveEmission(0, 12, 20));
    }
}
