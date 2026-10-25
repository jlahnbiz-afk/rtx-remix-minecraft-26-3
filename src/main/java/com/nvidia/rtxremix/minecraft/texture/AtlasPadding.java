/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.texture;

/**
 * The padding the atlas stitcher leaves around each sprite.
 *
 * <p>Vanilla pads by {@code 1 << mipLevel}, enough for its own mip chain, and a pack with
 * one tiny texture drops the block atlas to mip level 1: a two-texel border. The runtime
 * builds a full mip chain over the whole atlas copy, so from mip 2 on every block face
 * blends in its atlas neighbours, and distant terrain shows their colours as tints and
 * lines along block edges. A border of eight puts a sixteen-texel sprite alone in a
 * 32-texel cell, so no neighbour reaches it until the sprite is smaller than a texel.
 */
public final class AtlasPadding {

    /** Smallest border, in texels ({@code -Drtxremix.atlas.minPadding}, 0 for vanilla's). */
    static final int MIN_PADDING = Math.max(0, Integer.getInteger("rtxremix.atlas.minPadding", 8));

    private AtlasPadding() { }

    /** The border to stitch with, given vanilla's. */
    public static int widen(int vanillaPadding) {
        return Math.max(vanillaPadding, MIN_PADDING);
    }
}
