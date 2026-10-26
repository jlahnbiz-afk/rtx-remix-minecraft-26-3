/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.lens;

/**
 * The lights that may flare this frame: the ones the frame submits that shine brightest at
 * the camera. Lights offer themselves as they are drawn, and {@link LensEffects} projects
 * them when it draws the lens and then clears the list for the next frame. Whether a light
 * actually flares is decided on screen, by how bright the image is where it lands.
 *
 * <p>Render thread only. Allocation-free, as the terrain light loop calls it per light.
 */
public final class FlareSources {

    static final int MAX = 8;

    private static final double[] X = new double[MAX];
    private static final double[] Y = new double[MAX];
    private static final double[] Z = new double[MAX];
    private static final double[] WEIGHT = new double[MAX];
    private static final boolean[] HELD = new boolean[MAX];
    private static int count;

    private FlareSources() {}

    static void clear() {
        count = 0;
    }

    /**
     * Offers a light at a world position, kept if it is among the {@link #MAX} heaviest so far.
     *
     * @param weight how brightly it shines at the camera, such as power over distance squared
     */
    public static void offer(double x, double y, double z, double weight) {
        put(x, y, z, weight, false);
    }

    /**
     * Offers a flame in the player's own hand where it is drawn. It always stays, and it is
     * in front of everything, so the lens skips checking that the image shows it there.
     */
    public static void offerHeld(double x, double y, double z) {
        put(x, y, z, Double.POSITIVE_INFINITY, true);
    }

    private static void put(double x, double y, double z, double weight, boolean held) {
        int slot;
        if (count < MAX) {
            slot = count++;
        } else {
            slot = 0;
            for (int i = 1; i < MAX; i++) {
                if (WEIGHT[i] < WEIGHT[slot]) slot = i;
            }
            if (WEIGHT[slot] >= weight) return;
        }
        X[slot] = x;
        Y[slot] = y;
        Z[slot] = z;
        WEIGHT[slot] = weight;
        HELD[slot] = held;
    }

    static int count() { return count; }
    static double x(int i) { return X[i]; }
    static double y(int i) { return Y[i]; }
    static double z(int i) { return Z[i]; }
    static boolean held(int i) { return HELD[i]; }
}
