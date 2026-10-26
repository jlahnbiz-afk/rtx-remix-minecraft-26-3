/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.texture;

/**
 * The sprite border every atlas is stitched with: none.
 *
 * <p>The runtime builds its own mip chain over the whole atlas copy by halving it, so a mip
 * texel averages a power-of-two square of the atlas. Vanilla pads each sprite by
 * {@code 1 << mipLevel}, which moves it off the power-of-two grid, and from the next mip on a
 * block face averages in its atlas neighbours: distant terrain shows their colours as tints
 * and lines along block edges. Stitched without a border, the stitcher packs power-of-two
 * sprites, largest first, onto multiples of their own size, so a mip texel stays inside one
 * sprite until the sprite is a single texel ({@link AtlasSprites#offGrid} counts the
 * exceptions). A wider border would only delay the blending, and a border of half a
 * 128-texel sprite quadruples every atlas.
 */
public final class AtlasPadding {

    /** {@code -Drtxremix.atlas.vanillaPadding} keeps vanilla's border. */
    private static final boolean VANILLA = Boolean.getBoolean("rtxremix.atlas.vanillaPadding");

    private AtlasPadding() { }

    /** The border to stitch with, given vanilla's. */
    public static int border(int vanillaPadding) {
        return VANILLA ? vanillaPadding : 0;
    }
}
