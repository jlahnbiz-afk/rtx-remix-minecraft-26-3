/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.lens;

import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class FlareSourcesTest {

    @Test
    void keepsTheHeaviestLightsOffered() {
        FlareSources.clear();
        for (int i = 0; i < 20; i++) {
            FlareSources.offer(i, 0, 0, i);
        }
        assertEquals(FlareSources.MAX, FlareSources.count());
        Set<Double> kept = new HashSet<>();
        for (int i = 0; i < FlareSources.count(); i++) {
            kept.add(FlareSources.x(i));
        }
        Set<Double> heaviest = new HashSet<>();
        for (int i = 20 - FlareSources.MAX; i < 20; i++) {
            heaviest.add((double) i);
        }
        assertEquals(heaviest, kept);
    }

    @Test
    void clearingEmptiesTheList() {
        FlareSources.offer(1, 2, 3, 4);
        FlareSources.clear();
        assertEquals(0, FlareSources.count());
    }
}
