/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.texture;

import net.minecraft.client.renderer.texture.TextureAtlasSprite;

import java.util.ArrayList;
import java.util.List;

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

    /**
     * The padding borders of an atlas's sprites, for filling an atlas-sized copy.
     *
     * <p>Vanilla fills each border on the GPU by drawing the sprite through a
     * clamp-to-edge sampler, so a border repeats the sprite's edge texels outward. A CPU
     * copy left at zero there would hand the runtime's mip chain a transparent black
     * frame around every sprite, which shows as a dark grid on distant terrain.
     */
    public static Padding padding(Iterable<TextureAtlasSprite> sprites, int atlasWidth, int atlasHeight) {
        List<int[]> borders = new ArrayList<>();
        for (TextureAtlasSprite sprite : sprites) {
            int border = sprite.padding;
            int width = sprite.contents().width();
            int height = sprite.contents().height();
            if (border > 0 && width > 0 && height > 0) {
                borders.add(new int[]{contentX(sprite), contentY(sprite), width, height, border});
            }
        }
        return new Padding(borders, atlasWidth, atlasHeight);
    }

    /** Sprite borders of one atlas; see {@link #padding}. */
    public static final class Padding {
        private final List<int[]> borders;
        private final int atlasWidth;
        private final int atlasHeight;

        Padding(List<int[]> borders, int atlasWidth, int atlasHeight) {
            this.borders = borders;
            this.atlasWidth = atlasWidth;
            this.atlasHeight = atlasHeight;
        }

        /**
         * Fills every border of {@code atlas}, an atlas-sized copy with
         * {@code bytesPerTexel} bytes per texel, from its sprite's edge texels. Content
         * texels and texels outside every border are left alone.
         */
        public void fill(byte[] atlas, int bytesPerTexel) {
            if (atlas.length < atlasWidth * atlasHeight * bytesPerTexel) {
                throw new IllegalArgumentException("Copy is smaller than its atlas");
            }
            for (int[] border : borders) {
                fillBorder(atlas, bytesPerTexel, border[0], border[1], border[2], border[3], border[4]);
            }
        }

        private void fillBorder(byte[] atlas, int bytesPerTexel, int x0, int y0, int width, int height,
                int border) {
            int minX = Math.max(0, x0 - border);
            int maxX = Math.min(atlasWidth, x0 + width + border);
            int minY = Math.max(0, y0 - border);
            int maxY = Math.min(atlasHeight, y0 + height + border);
            int lastX = x0 + width - 1;
            int lastY = y0 + height - 1;
            for (int y = minY; y < maxY; y++) {
                int sourceY = Math.clamp(y, y0, lastY);
                boolean contentRow = sourceY == y;
                for (int x = minX; x < maxX; x++) {
                    if (contentRow && x == x0) {
                        x = lastX;
                        continue;
                    }
                    int sourceX = Math.clamp(x, x0, lastX);
                    System.arraycopy(
                            atlas, (sourceY * atlasWidth + sourceX) * bytesPerTexel,
                            atlas, (y * atlasWidth + x) * bytesPerTexel,
                            bytesPerTexel);
                }
            }
        }
    }

    private AtlasSprites() {}
}
