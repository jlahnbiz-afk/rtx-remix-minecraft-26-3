/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.texture;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BlockTextureManagerTranslucencyTest {

    private static final int TEXELS = 16 * 16;

    @Test
    void surfacesThatAreMostlyPartlyTransparentStayTranslucent() {
        // Stained glass and ice: the whole face is partly transparent.
        assertTrue(BlockTextureManager.readsAsTranslucent(0, TEXELS, TEXELS));
        // Slime: a partly transparent shell around an opaque core.
        assertTrue(BlockTextureManager.readsAsTranslucent(0, 150, TEXELS));
        // A pack's tinted door window: more glass than holes.
        assertTrue(BlockTextureManager.readsAsTranslucent(30, 80, TEXELS));
    }

    @Test
    void cutOutShapesWithSoftEdgesAreNot() {
        // A door's window or a flower: holes with an antialiased rim.
        assertFalse(BlockTextureManager.readsAsTranslucent(100, 20, TEXELS));
        assertFalse(BlockTextureManager.readsAsTranslucent(180, 30, TEXELS));
    }

    @Test
    void strayPartlyTransparentTexelsDoNotMakeASurfaceTranslucent() {
        // An opaque texture with a few texels just below full alpha.
        assertFalse(BlockTextureManager.readsAsTranslucent(0, 3, TEXELS));
    }
}
