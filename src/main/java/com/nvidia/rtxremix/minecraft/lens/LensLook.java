/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.lens;

/**
 * User-tunable camera-lens look drawn over the Remix image: how far the depth of field
 * blurs what is out of focus, and how strongly bright lights flare. Zero turns either off.
 */
public final class LensLook {

    public static final float DEFAULT_DEPTH_OF_FIELD = 0.5f;
    public static final float MAX_DEPTH_OF_FIELD = 2.0f;
    public static final float DEFAULT_FLARE = 1.0f;
    public static final float MAX_FLARE = 2.0f;

    private static float depthOfField = DEFAULT_DEPTH_OF_FIELD;
    private static float flare = DEFAULT_FLARE;

    private LensLook() {}

    public static float getDepthOfField() { return depthOfField; }
    public static void setDepthOfField(float v) { depthOfField = Math.clamp(v, 0.0f, MAX_DEPTH_OF_FIELD); }
    public static float getFlare() { return flare; }
    public static void setFlare(float v) { flare = Math.clamp(v, 0.0f, MAX_FLARE); }
}
