/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.mixin;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BakedQuadMixinTest {

    @Test
    void zeroIsAValidCachedHash() {
        BakedQuadMixin cache = new BakedQuadMixin();

        assertFalse(cache.rtxremix$matchesStaticGeometry(11, 12));
        cache.rtxremix$setStaticGeometryHash(0L, 11, 12);

        assertTrue(cache.rtxremix$matchesStaticGeometry(11, 12));
        assertEquals(0L, cache.rtxremix$getStaticGeometryHash());
    }

    @Test
    void changedSpriteBoundsInvalidateAndReplaceTheCachedHash() {
        BakedQuadMixin cache = new BakedQuadMixin();
        cache.rtxremix$setStaticGeometryHash(7L, 11, 12);

        assertFalse(cache.rtxremix$matchesStaticGeometry(21, 12));
        assertFalse(cache.rtxremix$matchesStaticGeometry(11, 22));

        cache.rtxremix$setStaticGeometryHash(8L, 21, 12);
        assertTrue(cache.rtxremix$matchesStaticGeometry(21, 12));
        assertFalse(cache.rtxremix$matchesStaticGeometry(11, 12));
        assertEquals(8L, cache.rtxremix$getStaticGeometryHash());
    }
}
