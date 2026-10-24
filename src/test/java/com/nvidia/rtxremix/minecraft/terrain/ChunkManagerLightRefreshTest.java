/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.terrain;

import com.nvidia.rtxremix.minecraft.asset.RemixLight;
import com.nvidia.rtxremix.minecraft.light.BlockLightIntensityControls;
import com.nvidia.rtxremix.minecraft.light.BlockLightKey;
import com.nvidia.rtxremix.minecraft.light.LightEntry;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class ChunkManagerLightRefreshTest {

    static {
        // The light fingerprint reads the block registry, which refuses to load unbootstrapped.
        try {
            SharedConstants.getCurrentVersion();
        } catch (IllegalStateException unset) {
            SharedConstants.tryDetectVersion();
        }
        Bootstrap.bootStrap();
    }

    private static final BlockLightIntensityControls.Control TORCH = control("blockLights.torch");

    @AfterEach
    void restoreMultiplier() {
        TORCH.set(BlockLightIntensityControls.DEFAULT_MULTIPLIER);
    }

    @Test
    void aMultiplierChangeQueuesOnlyTheLightsItTouches() throws Exception {
        ChunkManager manager = new ChunkManager(null);
        try {
            TerrainState terrain = field(manager, "terrain", TerrainState.class);
            TerrainState.SectionEntry entry = terrain.addSection(0, 0, 0, 1, "section");
            RemixLight torchLight = new RemixLight(null, null);
            BlockLightKey torchKey = attach(entry, torch(new BlockPos(1, 2, 3)), torchLight);
            attach(entry, other(new BlockPos(4, 2, 3)), new RemixLight(null, null));

            assertEquals(0, manager.refreshBlockLightIntensities(), "nothing changed yet");

            int before = entry.lightFingerprints().get(torchKey);
            TORCH.set(2.0);
            assertNotEquals(before, fingerprint(entry.lights().get(torchKey).source()));
            assertEquals(1, manager.refreshBlockLightIntensities());
            assertEquals(1L, longField(manager, "pendingLightOps"));
            assertFalse(torchLight.isDeleted(), "the light is updated in place, never replaced");
        } finally {
            manager.destroy();
        }
    }

    @Test
    void aLightWithoutItsRuleOutputIsLeftAlone() throws Exception {
        ChunkManager manager = new ChunkManager(null);
        try {
            TerrainState terrain = field(manager, "terrain", TerrainState.class);
            TerrainState.SectionEntry entry = terrain.addSection(0, 0, 0, 1, "section");
            TerrainLight bare = new TerrainLight(new RemixLight(null, null));
            bare.place(0f, 0f, 0f, 1f);
            entry.putLight(torch(BlockPos.ZERO).key(), bare);

            TORCH.set(3.0);
            assertEquals(0, manager.refreshBlockLightIntensities());
        } finally {
            manager.destroy();
        }
    }

    private static LightEntry torch(BlockPos pos) {
        return LightEntry.sphere(new BlockLightKey(pos, TORCH.ruleId(), 0), 14, Blocks.TORCH,
                pos.getX() + 0.5f, pos.getY() + 0.7f, pos.getZ() + 0.5f, 0.1f, null, null, 0.5f);
    }

    private static LightEntry other(BlockPos pos) {
        BlockLightIntensityControls.Control campfire = control("blockLights.campfire");
        return LightEntry.sphere(new BlockLightKey(pos, campfire.ruleId(), 0), 15, Blocks.CAMPFIRE,
                pos.getX() + 0.5f, pos.getY() + 0.5f, pos.getZ() + 0.5f, 0.3f, null, null, 0.5f);
    }

    private static BlockLightKey attach(TerrainState.SectionEntry entry, LightEntry source, RemixLight light)
            throws Exception {
        TerrainLight record = new TerrainLight(light);
        record.place(source, 1f);
        entry.putLight(source.key(), record);
        entry.lightFingerprints().put(source.key(), fingerprint(source));
        return source.key();
    }

    private static int fingerprint(LightEntry source) throws Exception {
        Method method = ChunkManager.class.getDeclaredMethod("lightFingerprint", LightEntry.class);
        method.setAccessible(true);
        return (int) method.invoke(null, source);
    }

    private static BlockLightIntensityControls.Control control(String settingId) {
        return BlockLightIntensityControls.controls().stream()
                .filter(control -> control.settingId().equals(settingId))
                .findFirst()
                .orElseThrow();
    }

    private static long longField(Object target, String name) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.getLong(target);
    }

    private static <T> T field(Object target, String name, Class<T> type) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return type.cast(field.get(target));
    }
}
