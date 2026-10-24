/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.entity;

import java.util.HashMap;
import java.util.Map;
import net.minecraft.resources.Identifier;

/**
 * The skin the first-person arm is drawn with: the player's own, with each sleeve's
 * see-through texels filled from the arm beneath it.
 *
 * <p>The sleeve is a shell a quarter pixel outside the arm, and the first-person model
 * is scaled down to {@link RemixFirstPersonPass#VIEW_MODEL_SCALE}, so the two are under
 * two millimetres apart. The runtime passes a ray through a cut-out texel by restarting
 * it beyond the surface, offset in proportion to the world position. Far enough from the
 * world origin that offset is larger than the gap, the ray starts inside the arm, misses
 * its near face and lands on the unlit inside of the far one, which shows as black
 * wherever the sleeve is see-through. With the arm's texels painted onto the sleeve the
 * ray stops at the sleeve, which then looks as vanilla blends it over the arm, a quarter
 * pixel further out.
 *
 * <p>Render thread only.
 */
final class FirstPersonSleeves {

    private static final String NAMESPACE = "rtxremix";
    private static final String PATH_PREFIX = "first_person_sleeves/";

    /** Vanilla's entity-translucent cut: a sleeve texel below this alpha is discarded. */
    private static final int VANILLA_ALPHA_CUTOUT = 26;

    /**
     * The 64x64 skin layout in texels, scaled with the skin: each sleeve's rectangle and
     * the arm rectangle under it, {sleeveX, sleeveY, armX, armY}, all 16 wide and 16 tall.
     * A slim arm uses 14 of the 16 columns; the other two are never sampled.
     */
    private static final int[][] SLEEVES_OVER_ARMS = {
            {40, 32, 40, 16}, // right sleeve over right arm
            {48, 48, 32, 48}, // left sleeve over left arm
    };
    private static final int LAYOUT_SIZE = 64;
    private static final int REGION_SIZE = 16;

    private static final Map<Identifier, Identifier> FLATTENED_BY_SKIN = new HashMap<>();
    private static final Map<Identifier, Identifier> SKIN_BY_FLATTENED = new HashMap<>();

    private FirstPersonSleeves() { }

    /** The texture the first-person arm takes for {@code skin}; allocates only on first sight of a skin. */
    static Identifier flattenedTexture(Identifier skin) {
        Identifier flattened = FLATTENED_BY_SKIN.get(skin);
        if (flattened == null) {
            flattened = Identifier.fromNamespaceAndPath(NAMESPACE,
                    PATH_PREFIX + skin.getNamespace() + "/" + skin.getPath());
            FLATTENED_BY_SKIN.put(skin, flattened);
            SKIN_BY_FLATTENED.put(flattened, skin);
        }
        return flattened;
    }

    /** The skin {@code textureId} is derived from, or {@code textureId} itself when it is not derived. */
    static Identifier sourceTexture(Identifier textureId) {
        Identifier skin = SKIN_BY_FLATTENED.get(textureId);
        return skin != null ? skin : textureId;
    }

    static boolean isFlattened(Identifier textureId) {
        return SKIN_BY_FLATTENED.containsKey(textureId);
    }

    /**
     * Paints each sleeve's rectangle of an RGBA skin with the sleeve composited over the
     * arm beneath it, as vanilla draws the two: a texel under vanilla's cut shows the arm,
     * any other blends over it. Anything but a square skin is left alone, because only the
     * 64x64 layout and its scaled copies put the sleeves where this looks.
     */
    static void flatten(byte[] rgba, int width, int height) {
        if (width != height || width < LAYOUT_SIZE || width % LAYOUT_SIZE != 0
                || rgba.length < width * height * 4) {
            return;
        }
        int scale = width / LAYOUT_SIZE;
        int size = REGION_SIZE * scale;
        for (int[] region : SLEEVES_OVER_ARMS) {
            int sleeveX = region[0] * scale, sleeveY = region[1] * scale;
            int armX = region[2] * scale, armY = region[3] * scale;
            for (int y = 0; y < size; y++) {
                for (int x = 0; x < size; x++) {
                    int sleeve = ((sleeveY + y) * width + sleeveX + x) * 4;
                    int arm = ((armY + y) * width + armX + x) * 4;
                    composite(rgba, sleeve, arm);
                }
            }
        }
    }

    private static void composite(byte[] rgba, int sleeve, int arm) {
        int alpha = rgba[sleeve + 3] & 0xFF;
        if (alpha == 0xFF) return;
        if (alpha < VANILLA_ALPHA_CUTOUT) alpha = 0;
        int armAlpha = rgba[arm + 3] & 0xFF;
        for (int c = 0; c < 3; c++) {
            int over = rgba[sleeve + c] & 0xFF;
            int under = rgba[arm + c] & 0xFF;
            rgba[sleeve + c] = (byte) ((over * alpha + under * (0xFF - alpha) + 127) / 0xFF);
        }
        rgba[sleeve + 3] = (byte) (alpha + (armAlpha * (0xFF - alpha) + 127) / 0xFF);
    }
}
