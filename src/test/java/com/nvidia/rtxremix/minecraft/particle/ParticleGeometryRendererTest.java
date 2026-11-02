/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.particle;

import com.nvidia.rtxremix.InstanceInfoBlendEXT;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ParticleGeometryRendererTest {
    @Test void bothLayerKindsPreserveParticleShaderCutoffAndVertexAlpha() throws Exception {
        // Minecraft's particle.fsh discards alpha below 0.1 in both layer kinds.
        try (var renderer = new ParticleGeometryRenderer(null)) {
            for (String name : new String[]{"cutout", "blended"}) {
                var field = ParticleGeometryRenderer.class.getDeclaredField(name);
                field.setAccessible(true);
                var blend = (InstanceInfoBlendEXT) field.get(renderer);
                assertEquals(1, blend.alphaTestEnabled);
                assertEquals(25, Byte.toUnsignedInt(blend.alphaTestReferenceValue));
                assertEquals(InstanceInfoBlendEXT.VK_COMPARE_OP_GREATER, blend.alphaTestCompareOp);
                assertEquals(InstanceInfoBlendEXT.RT_TEX_OP_MODULATE, blend.textureAlphaOperation);
                assertEquals(0, blend.isVertexColorBakedLighting);
                assertEquals(name.equals("blended") ? 1 : 0, blend.alphaBlendEnabled);
            }
        }
    }
}
