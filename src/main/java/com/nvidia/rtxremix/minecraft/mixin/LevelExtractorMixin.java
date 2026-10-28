/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.nvidia.rtxremix.minecraft.RtxRemixMod;
import com.nvidia.rtxremix.minecraft.terrain.UnseenSectionCompiles;
import com.nvidia.rtxremix.minecraft.terrain.capture.BlockQuadEmitter;
import com.nvidia.rtxremix.minecraft.terrain.capture.RegionBuilderRelease;
import com.nvidia.rtxremix.minecraft.weather.Puddles;
import java.util.function.LongConsumer;
import java.util.function.LongFunction;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.SectionUpdateTracker;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.ViewArea;
import net.minecraft.client.renderer.chunk.RenderRegionCache;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.extract.LevelExtractor;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import net.minecraft.client.renderer.state.level.SectionUpdateRenderState;
import net.minecraft.core.SectionPos;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Section rebuild scheduling, which vanilla moved from the level renderer into the
 * extractor in 26.1.
 *
 * <ul>
 *   <li>The water-occlusion margin on block-update rebuilds.</li>
 *   <li>Dirty sections out of view are scheduled after vanilla's own, a few a frame
 *       ({@link UnseenSectionCompiles}).</li>
 *   <li>The region cache the frame's section updates snapshot from is released once
 *       they are all scheduled; see {@link ChunkRendererRegionBuilderMixin}.</li>
 * </ul>
 */
@Mixin(LevelExtractor.class)
public abstract class LevelExtractorMixin {

    @Shadow
    public abstract void setSectionDirty(int sectionX, int sectionY, int sectionZ);

    @Shadow @Final private LevelRenderer levelRenderer;
    @Shadow @Final private LevelRenderState levelRenderState;
    @Shadow private ClientLevel level;
    @Shadow private SectionUpdateTracker sectionUpdateTracker;

    /** Vanilla's cache is a local of extract; captured at construction, released after the section loop. */
    private RenderRegionCache rtxremix$frameRegionCache;

    @Unique
    private final UnseenSectionCompiles rtxremix$unseenCompiles = new UnseenSectionCompiles();
    @Unique
    private final LongFunction<SectionRenderDispatcher.RenderSection> rtxremix$renderSection =
            node -> ((ViewAreaAccessor) levelRenderer.viewArea()).rtxremix$getRenderSection(node);
    @Unique
    private final LongConsumer rtxremix$compileUnseen = node -> levelRenderState.sectionUpdateRenderStates.add(
            new SectionUpdateRenderState(node, false, rtxremix$frameRegionCache.createRegion(level, node)));

    /**
     * Hidden water-top culling reads farther than vanilla's one-block rebuild
     * margin. Expand horizontal invalidation so a ceiling or fluid update
     * cannot leave a neighboring section's occlusion decision stale.
     */
    @Inject(method = "setBlocksDirty(IIIIII)V", at = @At("HEAD"), cancellable = true)
    private void rtxremix$expandWaterOcclusionRebuildMargin(
            int minX, int minY, int minZ, int maxX, int maxY, int maxZ, CallbackInfo ci) {
        if (!RtxRemixMod.isRtxEnabled()) {
            return;
        }
        Puddles.blocksChanged(minX, minZ, maxX, maxZ);

        int radius = Math.max(1, BlockQuadEmitter.getWaterTopOcclusionRadius());
        int minSectionX = SectionPos.blockToSectionCoord(minX - radius);
        int minSectionY = SectionPos.blockToSectionCoord(minY - 1);
        int minSectionZ = SectionPos.blockToSectionCoord(minZ - radius);
        int maxSectionX = SectionPos.blockToSectionCoord(maxX + radius);
        int maxSectionY = SectionPos.blockToSectionCoord(maxY + 1);
        int maxSectionZ = SectionPos.blockToSectionCoord(maxZ + radius);
        for (int sectionZ = minSectionZ; sectionZ <= maxSectionZ; sectionZ++) {
            for (int sectionX = minSectionX; sectionX <= maxSectionX; sectionX++) {
                for (int sectionY = minSectionY; sectionY <= maxSectionY; sectionY++) {
                    setSectionDirty(sectionX, sectionY, sectionZ);
                }
            }
        }
        ci.cancel();
    }

    @WrapOperation(
            method = "extract",
            at = @At(value = "NEW", target = "()Lnet/minecraft/client/renderer/chunk/RenderRegionCache;"))
    private RenderRegionCache rtxremix$captureFrameRegionCache(Operation<RenderRegionCache> original) {
        RenderRegionCache cache = original.call();
        rtxremix$frameRegionCache = cache;
        return cache;
    }

    /**
     * Schedules the sections out of view, then drops the cache's references once the section
     * loop that used it has run, so the snapshots it holds can be recycled once their compile
     * tasks have. Entities are extracted right after the loop, in every frame that runs it,
     * with the frame's frustum.
     */
    @WrapOperation(
            method = "extract",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/extract/LevelExtractor;extractVisibleEntities(Lnet/minecraft/client/Camera;Lnet/minecraft/client/renderer/culling/Frustum;Lnet/minecraft/client/DeltaTracker;Lnet/minecraft/client/renderer/state/level/LevelRenderState;)V"))
    private void rtxremix$afterSectionUpdates(LevelExtractor extractor, Camera camera, Frustum frustum,
            DeltaTracker deltaTracker, LevelRenderState state, Operation<Void> original) {
        ViewArea viewArea = levelRenderer.viewArea();
        SectionRenderDispatcher dispatcher = levelRenderer.sectionRenderDispatcher();
        if (RtxRemixMod.isRtxEnabled() && rtxremix$frameRegionCache != null && level != null
                && sectionUpdateTracker != null && viewArea != null && dispatcher != null) {
            rtxremix$unseenCompiles.schedule(level, rtxremix$renderSection, sectionUpdateTracker, frustum,
                    viewArea.getViewDistance(), viewArea.minSectionY(), viewArea.maxSectionY(),
                    viewArea.getCameraSectionPos(), dispatcher.getCompileQueueSize(), rtxremix$compileUnseen);
        }
        RenderRegionCache cache = rtxremix$frameRegionCache;
        rtxremix$frameRegionCache = null;
        if (cache != null) {
            ((RegionBuilderRelease) cache).rtxremix$releaseSnapshots();
        }
        original.call(extractor, camera, frustum, deltaTracker, state);
    }
}
