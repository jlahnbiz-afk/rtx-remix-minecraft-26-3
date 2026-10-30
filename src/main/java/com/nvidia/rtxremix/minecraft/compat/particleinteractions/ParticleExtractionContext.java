/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.compat.particleinteractions;

import net.minecraft.client.renderer.texture.TextureAtlasSprite;

/** Attributes that the source's packed light and sliced UVs cannot retain on their own. */
public final class ParticleExtractionContext {
    private static final ThreadLocal<ParticleExtractionContext> CURRENT = ThreadLocal.withInitial(ParticleExtractionContext::new);
    public int emission;
    public TextureAtlasSprite sprite;
    public static ParticleExtractionContext current() { return CURRENT.get(); }
}
