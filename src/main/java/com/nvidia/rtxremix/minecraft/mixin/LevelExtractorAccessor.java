/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.mixin;

import net.minecraft.client.SectionUpdateTracker;
import net.minecraft.client.renderer.extract.LevelExtractor;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** The extractor's section dirty state, which decides what vanilla compiles next. */
@Mixin(LevelExtractor.class)
public interface LevelExtractorAccessor {
    @Accessor("sectionUpdateTracker")
    @Nullable SectionUpdateTracker rtxremix$sectionUpdateTracker();
}
