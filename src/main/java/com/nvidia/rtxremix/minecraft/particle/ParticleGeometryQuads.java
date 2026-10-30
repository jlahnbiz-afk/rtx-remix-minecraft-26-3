/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.particle;

/** CPU storage adapter; the destination owns any data it retains after this call. */
public interface ParticleGeometryQuads {
    void rtxremix$writeQuads(ParticleGeometrySink destination);
}
