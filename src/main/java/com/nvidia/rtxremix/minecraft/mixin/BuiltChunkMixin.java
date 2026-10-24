/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.mixin;

import com.nvidia.rtxremix.minecraft.terrain.SectionLeftRenderSetQueue;
import net.minecraft.client.renderer.chunk.SectionMesh;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import net.minecraft.core.SectionPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Hooks into vanilla's render section lifecycle.
 *
 * <p><b>setSectionMesh:</b> flags a section whose new mesh has no renderable
 * layers as a removal candidate. The render thread validates the live world
 * section before removing Remix terrain because vanilla can publish temporary
 * empty data, and while capturing every block is reported invisible, so every
 * compiled section arrives here empty.
 */
@Mixin(SectionRenderDispatcher.RenderSection.class)
public abstract class BuiltChunkMixin {

    /** The node a section holds before vanilla first places it. */
    private static final long rtxremix$UNPLACED = SectionPos.asLong(-1, -1, -1);

    @Shadow
    private volatile long sectionNode;

    @Inject(method = "setSectionMesh", at = @At("HEAD"))
    private void rtxremix$onSectionMeshSet(SectionMesh sectionMesh, CallbackInfoReturnable<SectionMesh> cir) {
        if (sectionMesh.hasRenderableLayers()) {
            return;
        }
        long node = sectionNode;
        if (node == rtxremix$UNPLACED) {
            return;
        }
        SectionLeftRenderSetQueue.enqueueEmptyCandidate(
                SectionPos.x(node), SectionPos.y(node), SectionPos.z(node));
    }
}
