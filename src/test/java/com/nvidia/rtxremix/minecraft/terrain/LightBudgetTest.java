/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.terrain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.nvidia.rtxremix.minecraft.asset.RemixLight;
import com.nvidia.rtxremix.minecraft.light.LightPower;
import org.junit.jupiter.api.Test;

class LightBudgetTest {

    private static final float REACH = TerrainLight.thresholdFor(128);

    @Test
    void theReachAloneDecidesWhileTheLightsFit() {
        assertEquals(0.0f, ChunkManager.nextBudgetThreshold(0.0f, REACH, 1000, 4096));
    }

    @Test
    void tooManyLightsRaiseTheFloorAboveTheReach() {
        float raised = ChunkManager.nextBudgetThreshold(0.0f, REACH, 180_000, 4096);
        assertTrue(raised > REACH);
        // One frame never raises it more than fourfold.
        assertTrue(raised <= REACH * 4.0f + 1.0e-12f);
        assertTrue(ChunkManager.nextBudgetThreshold(raised, REACH, 180_000, 4096) > raised);
    }

    @Test
    void theFloorHoldsInsideTheBandAndEasesBackBelowIt() {
        float raised = REACH * 2.0f;
        assertEquals(raised, ChunkManager.nextBudgetThreshold(raised, REACH, 3500, 4096));
        float eased = ChunkManager.nextBudgetThreshold(raised, REACH, 1000, 4096);
        assertTrue(eased < raised && eased > REACH);
        float floor = raised;
        for (int frame = 0; frame < 200; frame++) {
            floor = ChunkManager.nextBudgetThreshold(floor, REACH, 1000, 4096);
        }
        assertEquals(0.0f, floor, "once back at the reach the budget lets go");
    }

    @Test
    void aSectionIsSkippedOnlyWhenNoLightInItCouldBeDrawn() {
        float torch = LightPower.torchReference();
        float threshold = TerrainLight.thresholdFor(48);
        // A torch drawn last frame stays drawn out to 48 * sqrt(1.5) = 58.8 blocks.
        TerrainLight drawn = new TerrainLight(new RemixLight(null, null));
        drawn.place(0f, 0f, 0f, torch);
        assertTrue(drawn.shouldDraw(47, 0, 0, threshold, 1));
        assertTrue(drawn.shouldDraw(58, 0, 0, threshold, 2));
        assertTrue(TerrainLight.mayDraw(58.0 * 58.0, torch, threshold));
        assertFalse(TerrainLight.mayDraw(60.0 * 60.0, torch, threshold));
    }
}
