/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.texture;

import net.minecraft.client.renderer.texture.TextureAtlasSprite;

/**
 * Where a sprite's own texels sit in its atlas.
 *
 * <p>The 26.3 stitcher reserves a padding border around every sprite and
 * {@link TextureAtlasSprite#getX()} names the border's corner, not the first
 * texel. Every CPU-side copy of an atlas region starts at these origins
 * instead, so its texels line up with the sprite's UVs.
 */
public final class AtlasSprites {

    /** Atlas column of the sprite's first texel. */
    public static int contentX(TextureAtlasSprite sprite) {
        return sprite.getX() + sprite.padding;
    }

    /** Atlas row of the sprite's first texel. */
    public static int contentY(TextureAtlasSprite sprite) {
        return sprite.getY() + sprite.padding;
    }

    private AtlasSprites() {}
}
