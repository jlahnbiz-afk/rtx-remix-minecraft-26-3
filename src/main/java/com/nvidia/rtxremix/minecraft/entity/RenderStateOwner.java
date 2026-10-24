/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.entity;

import org.jspecify.annotations.Nullable;

/**
 * The game object an entity or block-entity render state was extracted from.
 *
 * <p>Vanilla's render states are plain snapshots that drop the object they describe,
 * but the submitter keys retained instances on that object's identity, so the
 * extraction hooks record it on the state ({@code EntityRenderDispatcherMixin},
 * {@code BlockEntityRenderDispatcherMixin}). The reference lives only as long as the
 * state, which vanilla rebuilds every frame.
 */
public interface RenderStateOwner {

    @Nullable Object rtxremix$owner();

    void rtxremix$setOwner(@Nullable Object owner);
}
