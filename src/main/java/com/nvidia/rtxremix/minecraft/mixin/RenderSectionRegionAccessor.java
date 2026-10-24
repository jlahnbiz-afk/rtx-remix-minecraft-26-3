/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.mixin;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.chunk.RenderSectionRegion;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** The level a section build reads, for the rain surfaces' heightmap and biome lookups. */
@Mixin(RenderSectionRegion.class)
public interface RenderSectionRegionAccessor {
    @Accessor("level")
    ClientLevel rtxremix$level();
}
