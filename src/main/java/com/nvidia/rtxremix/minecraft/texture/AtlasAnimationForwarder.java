/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.texture;

import com.nvidia.rtxremix.RemixAPI;
import com.nvidia.rtxremix.TextureBatchEntry;
import com.nvidia.rtxremix.TextureFormat;
import com.nvidia.rtxremix.TexturePatch;
import com.nvidia.rtxremix.TexturePatchInfo;
import com.mojang.blaze3d.platform.NativeImage;
import com.nvidia.rtxremix.minecraft.mixin.SpriteContentsAccessor;
import com.nvidia.rtxremix.minecraft.util.DirectBufferPool;
import org.lwjgl.system.MemoryUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.client.renderer.texture.SpriteContents;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.resources.Identifier;

/**
 * Collects animated sprite frames each tick and flushes them to Remix in a
 * single batched call before present.
 *
 * <p>Vanilla animates atlas sprites on the GPU, so nothing is uploaded from
 * the CPU to observe; {@code SpriteAtlasTextureMixin} reads each animation's
 * state after its tick and the patch is rebuilt here from the sprite's frame
 * strip, blending interpolated animations as vanilla's shader does.
 *
 * <p>LabPBR-aware: sprites that have {@code _n} / {@code _s} siblings are
 * re-processed on every animation frame so AO (from {@code _n.b}) and the
 * animated LabPBR material channels in {@code _s} stay in phase with albedo.
 * Without this, later albedo frames would overwrite the AO-modulated atlas
 * while roughness, metallic, SSS, and emissive atlases stayed stuck on the
 * init-time sidecar frame. Non-LabPBR sprites are copied as albedo only.
 *
 * <p>All patches for one frame across every animated atlas texture go out in
 * one {@code remixapi_UpdateTextureFromMemory} call using the multi-handle
 * batch API. This amortizes the JNI crossing and, on the dxvk side, lets mip
 * regeneration be scoped to the dirty rect per atlas instead of regenerating
 * the whole atlas once per atlas.
 */
public final class AtlasAnimationForwarder {

    private static final Logger LOGGER = LoggerFactory.getLogger(AtlasAnimationForwarder.class);

    // Gate on patch queueing: read by addPatch and toggled by
    // init/clear/pause/resume, all of which run on the render thread. Each
    // toggle is sequenced against the state it guards at its own call site.
    private static volatile boolean active = false;

    private static Thread expectedThread;
    private static final List<PatchRecord> pendingPatches = new ArrayList<>();
    private static Map<String, BlockTextureManager.AnimatedSpriteLabPBR> labPBRMap;
    private static Set<Identifier> blockAtlasSpriteIds;
    private static final ThreadLocal<Identifier> currentAtlasId = new ThreadLocal<>();

    // Reusable scratch between CPU-side patch composition and the final
    // DirectBuffer copy. Pre-sized for a typical 64x64 RGBA sprite so the
    // first frame of every animation lands without a realloc; grown
    // monotonically beyond that and never shrunk, so steady-state allocates
    // no garbage.
    private static final int kInitialScratchBytes = 64 * 64 * 4;
    private static byte[] scratch = new byte[kInitialScratchBytes];

    private static final class PatchRecord {
        final Identifier id;
        final int dstX;
        final int dstY;
        final int width;
        final int height;
        final int srcImageWidth;
        final int srcImageHeight;
        /**
         * Raw pointer to the first texel of the sprite's frame strip, the
         * owning SpriteContents' original image. Read-only and valid only
         * until flush() is called on the same render-thread frame that
         * produced this record:
         *   1. The owning SpriteContents must not be closed (atlas rebuild
         *      or world unload invalidates).
         *   2. The block atlas must not be re-stitched between capture
         *      and flush. RemixRenderer clears the forwarder on
         *      pendingTextureReload before rebuild to enforce this.
         *   3. Capture and flush must both run on the render thread
         *      (thread check in addPatch). Moving flush off the render
         *      thread breaks this contract and requires copying bytes
         *      at capture time.
         */
        final long imagePtr;
        final int frameRowSize;
        final int currentFrame;
        final int nextFrame;
        final float nextBlend;

        PatchRecord(Identifier id, int dstX, int dstY, int width, int height,
                    int srcImageWidth, int srcImageHeight, long imagePtr, int frameRowSize,
                    int currentFrame, int nextFrame, float nextBlend) {
            this.id = id;
            this.dstX = dstX;
            this.dstY = dstY;
            this.width = width;
            this.height = height;
            this.srcImageWidth = srcImageWidth;
            this.srcImageHeight = srcImageHeight;
            this.imagePtr = imagePtr;
            this.frameRowSize = frameRowSize;
            this.currentFrame = currentFrame;
            this.nextFrame = nextFrame;
            this.nextBlend = nextBlend;
        }

        boolean blends() {
            return nextBlend > 0.0f && nextFrame != currentFrame;
        }
    }

    private static final class FrameOrigin {
        final int x;
        final int y;

        FrameOrigin(int x, int y) {
            this.x = x;
            this.y = y;
        }
    }

    private static final class FrameSampling {
        final FrameOrigin current;
        final FrameOrigin next;
        final float nextBlend;

        FrameSampling(FrameOrigin current, FrameOrigin next, float nextBlend) {
            this.current = current;
            this.next = next;
            this.nextBlend = nextBlend;
        }
    }

    public static void init(
            Map<String, BlockTextureManager.AnimatedSpriteLabPBR> labPBR,
            Set<Identifier> blockAtlasSprites) {
        labPBRMap = labPBR;
        blockAtlasSpriteIds = Set.copyOf(blockAtlasSprites);
        pendingPatches.clear();
        expectedThread = null;
        // Flip active on LAST so any addPatch probe that races initialization
        // sees a fully constructed forwarder (or no forwarder at all).
        active = true;
        LOGGER.info("AtlasAnimationForwarder initialized ({} LabPBR sprites, {} atlas sprites)",
                    labPBR.size(), blockAtlasSpriteIds.size());
    }

    /**
     * Queues one animated sprite's frame for this tick's flush.
     *
     * <p>26.3 animates atlas sprites on the GPU, so the frame is described
     * rather than uploaded: the sprite's frame strip, the frame shown, and for
     * an interpolated animation the next frame and how far toward it the
     * vanilla shader blends.
     *
     * @param imagePtr      first texel of the sprite's mip-0 frame strip
     * @param frameRowSize  frames per row of the strip
     * @param currentFrame  strip index of the frame shown
     * @param nextFrame     strip index blended toward, or {@code currentFrame}
     * @param nextBlend     weight of {@code nextFrame}, in [0, 1)
     */
    public static void addPatch(Identifier spriteId, int dstX, int dstY,
                                int width, int height,
                                int srcImageWidth, int srcImageHeight,
                                long imagePtr, int frameRowSize,
                                int currentFrame, int nextFrame, float nextBlend) {
        // Drop patches when the forwarder is not active (SDK missing, RTX
        // disabled, or post-shutdown/reload). Prevents an unbounded queue
        // when SpriteContents.upload still fires but we'll never flush.
        if (!active) return;

        if (!BlockTextureManager.BLOCK_ATLAS_ID.equals(currentAtlasId.get())) {
            return;
        }

        Set<Identifier> allowedSprites = blockAtlasSpriteIds;
        if (allowedSprites == null || !allowedSprites.contains(spriteId)) {
            return;
        }

        Thread current = Thread.currentThread();
        if (expectedThread == null) {
            expectedThread = current;
        } else if (current != expectedThread) {
            // Java assertions are stripped without -ea, so log + drop instead:
            // a stray cross-thread call would race the render-thread flush
            // and produce torn batches. Drop the patch and surface the
            // violation in production logs.
            LOGGER.error("addPatch called from {} but expected {}; dropping patch",
                         current.getName(), expectedThread.getName());
            return;
        }

        pendingPatches.add(new PatchRecord(spriteId, dstX, dstY, width, height,
                                           srcImageWidth, srcImageHeight, imagePtr, frameRowSize,
                                           currentFrame, nextFrame, nextBlend));
    }

    /**
     * Queues the frame a GPU-animated sprite shows after this tick, if vanilla
     * redraws it: every tick for an interpolated animation, otherwise only when
     * the shown frame changed.
     */
    public static void addAnimatedSprite(TextureAtlasSprite sprite, SpriteContents.AnimationState state) {
        // Raster visibility is not Remix visibility: an animated surface can
        // still light or reflect into the frame while its GL draw is culled.
        if (!active || !(state.animationInfo.interpolateFrames
                || ((com.nvidia.rtxremix.minecraft.mixin.SpriteAnimationStateAccessor) state).rtxremix$frameChanged())) {
            return;
        }
        SpriteContents contents = sprite.contents();
        SpriteContents.AnimatedTexture animation = state.animationInfo;
        List<SpriteContents.FrameInfo> frames = animation.frames;
        SpriteContents.FrameInfo current = frames.get(state.frame);
        int nextFrame = current.index();
        float nextBlend = 0.0f;
        if (animation.interpolateFrames) {
            nextFrame = frames.get((state.frame + 1) % frames.size()).index();
            nextBlend = (float) state.subFrame / current.time();
        }
        NativeImage image = ((SpriteContentsAccessor) contents).getImage();
        addPatch(contents.name(), AtlasSprites.contentX(sprite), AtlasSprites.contentY(sprite),
                contents.width(), contents.height(), image.getWidth(), image.getHeight(),
                image.getPointer(), animation.frameRowSize, current.index(), nextFrame, nextBlend);
    }

    private static FrameSampling resolveLabPBRFrameSampling(
            PatchRecord p, BlockTextureManager.AnimatedSpriteLabPBR pbr) {
        // LabPBR sidecars lay their frames out in their own strip, which can
        // be narrower or wider than the albedo strip.
        int columns = pbr != null ? pbr.frameColumns : p.frameRowSize;
        FrameOrigin current = frameOrigin(p.currentFrame, columns, p.width, p.height);
        if (!p.blends()) {
            return new FrameSampling(current, current, 0.0f);
        }
        FrameOrigin next = frameOrigin(p.nextFrame, columns, p.width, p.height);
        return new FrameSampling(current, next, p.nextBlend);
    }

    private static FrameOrigin frameOrigin(int frameIndex, int frameColumns, int width, int height) {
        int columns = Math.max(1, frameColumns);
        int x = (frameIndex % columns) * width;
        int y = (frameIndex / columns) * height;
        return new FrameOrigin(x, y);
    }

    /**
     * Writes vanilla's interpolated frame into {@code dst}: colour blends
     * linearly toward the next frame while alpha stays the current frame's,
     * as the 1.21.1 CPU interpolation did.
     */
    private static void blendFrames(ByteBuffer dst, PatchRecord p, FrameOrigin current, FrameOrigin next) {
        int rowPitch = p.srcImageWidth * 4;
        float w = p.nextBlend;
        float iw = 1.0f - w;
        int o = 0;
        for (int y = 0; y < p.height; y++) {
            long cur = p.imagePtr + (long) (current.y + y) * rowPitch + (long) current.x * 4;
            long nxt = p.imagePtr + (long) (next.y + y) * rowPitch + (long) next.x * 4;
            for (int x = 0; x < p.width; x++, cur += 4, nxt += 4, o += 4) {
                for (int c = 0; c < 3; c++) {
                    int a = MemoryUtil.memGetByte(cur + c) & 0xFF;
                    int b = MemoryUtil.memGetByte(nxt + c) & 0xFF;
                    dst.put(o + c, (byte) (int) (iw * a + w * b));
                }
                dst.put(o + 3, MemoryUtil.memGetByte(cur + 3));
            }
        }
    }

    /**
     * Builds one multi-handle batch covering animated albedo and LabPBR atlas
     * sidecars, then issues a single {@code remixapi_UpdateTextureFromMemory}
     * call. LabPBR data is applied per patch using pre-computed tables cached
     * at init.
     *
     * @param api            the Remix API handle
     * @param albedoHandle   handle of the albedo atlas texture (required)
     * @param emissiveHandle handle of the emissive atlas, or 0 if none exists
     * @param roughnessHandle handle of the roughness atlas, or 0 if none exists
     * @param metallicHandle handle of the metallic atlas, or 0 if none exists
     * @param sssHandle      handle of the SSS atlas, or 0 if none exists
     */
    public static void flush(RemixAPI api, long albedoHandle, long emissiveHandle,
                             long roughnessHandle, long metallicHandle, long sssHandle) {
        int count = pendingPatches.size();
        if (count == 0 || albedoHandle == 0) {
            return;
        }

        List<ByteBuffer> ownedBuffers = new ArrayList<>(count * 2);

        try {
            TexturePatch[] albedoPatches = new TexturePatch[count];
            List<TexturePatch> roughnessPatches = new ArrayList<>();
            List<TexturePatch> metallicPatches = new ArrayList<>();
            List<TexturePatch> emissivePatches = new ArrayList<>();
            List<TexturePatch> sssPatches = new ArrayList<>();

            for (int i = 0; i < count; i++) {
                PatchRecord p = pendingPatches.get(i);
                BlockTextureManager.AnimatedSpriteLabPBR pbr =
                    (labPBRMap != null) ? labPBRMap.get(p.id.toString()) : null;

                final int patchBytes = p.width * p.height * 4;
                final int patchRowBytes = p.width * 4;
                ensureScratch(patchBytes);
                FrameOrigin albedoCurrent = frameOrigin(p.currentFrame, p.frameRowSize, p.width, p.height);
                ByteBuffer srcBuf;
                int srcRowPitch;
                if (p.blends()) {
                    srcBuf = DirectBufferPool.acquire(patchBytes);
                    ownedBuffers.add(srcBuf);
                    blendFrames(srcBuf, p, albedoCurrent,
                            frameOrigin(p.nextFrame, p.frameRowSize, p.width, p.height));
                    srcRowPitch = patchRowBytes;
                } else {
                    srcRowPitch = p.srcImageWidth * 4;
                    long first = p.imagePtr + (long) albedoCurrent.y * srcRowPitch + (long) albedoCurrent.x * 4;
                    srcBuf = MemoryUtil.memByteBuffer(first, srcRowPitch * (p.height - 1) + patchRowBytes);
                }
                FrameSampling labPbrSampling = resolveLabPBRFrameSampling(p, pbr);

                // --- Albedo patch ---
                if (pbr != null && pbr.aoTable != null) {
                    LabPBRConverter.applyAOToPatch(
                        scratch, patchRowBytes,
                        srcBuf, srcRowPitch,
                        pbr.aoTable, pbr.aoW, pbr.aoH,
                        labPbrSampling.current.x, labPbrSampling.current.y,
                        p.width, p.height);
                } else {
                    LabPBRConverter.copyAlbedoPatch(
                        scratch, patchRowBytes,
                        srcBuf, srcRowPitch,
                        p.width, p.height);
                }
                ByteBuffer albedoDst = DirectBufferPool.acquire(patchBytes);
                albedoDst.put(scratch, 0, patchBytes);
                albedoDst.flip();
                ownedBuffers.add(albedoDst);

                albedoPatches[i] = createPatch(p, albedoDst, patchRowBytes, TextureFormat.RGBA8_UNORM);

                // --- _s RGB material channels ---
                // LabPBR packs roughness/metallic/SSS into _s.rgb. These
                // atlases are separate Remix textures, so they need matching
                // animation patches just like _s.alpha emissive.
                if (pbr != null && pbr.sRoughnessTable != null && roughnessHandle != 0) {
                    int patchR8Bytes = p.width * p.height;
                    ensureScratch(patchR8Bytes);
                    LabPBRConverter.buildR8Patch(
                        scratch, p.width,
                        pbr.sRoughnessTable, pbr.sW, pbr.sH,
                        labPbrSampling.current.x, labPbrSampling.current.y,
                        labPbrSampling.next.x, labPbrSampling.next.y,
                        labPbrSampling.nextBlend,
                        p.width, p.height);
                    ByteBuffer roughnessDst = DirectBufferPool.acquire(patchR8Bytes);
                    roughnessDst.put(scratch, 0, patchR8Bytes);
                    roughnessDst.flip();
                    ownedBuffers.add(roughnessDst);
                    roughnessPatches.add(createPatch(p, roughnessDst, p.width, TextureFormat.R8_UNORM));
                }

                if (pbr != null && pbr.sMetallicTable != null && metallicHandle != 0) {
                    int patchR8Bytes = p.width * p.height;
                    ensureScratch(patchR8Bytes);
                    LabPBRConverter.buildR8Patch(
                        scratch, p.width,
                        pbr.sMetallicTable, pbr.sW, pbr.sH,
                        labPbrSampling.current.x, labPbrSampling.current.y,
                        labPbrSampling.next.x, labPbrSampling.next.y,
                        labPbrSampling.nextBlend,
                        p.width, p.height);
                    ByteBuffer metallicDst = DirectBufferPool.acquire(patchR8Bytes);
                    metallicDst.put(scratch, 0, patchR8Bytes);
                    metallicDst.flip();
                    ownedBuffers.add(metallicDst);
                    metallicPatches.add(createPatch(p, metallicDst, p.width, TextureFormat.R8_UNORM));
                }

                if (pbr != null && pbr.hasSss && pbr.sSssTable != null && sssHandle != 0) {
                    LabPBRConverter.buildSssPatch(
                        scratch, patchRowBytes,
                        pbr.sSssTable, pbr.sW, pbr.sH,
                        labPbrSampling.current.x, labPbrSampling.current.y,
                        labPbrSampling.next.x, labPbrSampling.next.y,
                        labPbrSampling.nextBlend,
                        p.width, p.height);
                    ByteBuffer sssDst = DirectBufferPool.acquire(patchBytes);
                    sssDst.put(scratch, 0, patchBytes);
                    sssDst.flip();
                    ownedBuffers.add(sssDst);
                    sssPatches.add(createPatch(p, sssDst, patchRowBytes, TextureFormat.RGBA8_UNORM));
                }

                // --- Emissive patch (authored _s alpha, else a built-in mask) ---
                if (emissiveHandle == 0) continue;
                final boolean hasLabPBREmissive = pbr != null && pbr.hasPerPixelEmissive;
                BuiltinEmission.Mask builtinMask = hasLabPBREmissive ? null : BuiltinEmission.mask(p.id);

                // Skip sprites with neither. Vanilla block luminance affects
                // cast light, not surface emission.
                if (!hasLabPBREmissive && builtinMask == null) continue;

                if (hasLabPBREmissive) {
                    LabPBRConverter.buildEmissivePatch(
                        scratch, patchRowBytes,
                        srcBuf, srcRowPitch,
                        pbr.sAlphaTable, pbr.sW, pbr.sH,
                        labPbrSampling.current.x, labPbrSampling.current.y,
                        labPbrSampling.next.x, labPbrSampling.next.y,
                        labPbrSampling.nextBlend,
                        p.width, p.height,
                        0f);
                } else {
                    // The mask is over the sprite's own UV space, which every animation frame shares.
                    LabPBRConverter.buildEmissiveMaskPatch(
                        scratch, patchRowBytes,
                        srcBuf, srcRowPitch,
                        p.width, p.height, builtinMask);
                }

                ByteBuffer emissiveDst = DirectBufferPool.acquire(patchBytes);
                emissiveDst.put(scratch, 0, patchBytes);
                emissiveDst.flip();
                ownedBuffers.add(emissiveDst);

                emissivePatches.add(createPatch(p, emissiveDst, patchRowBytes, TextureFormat.RGBA8_UNORM));
            }

            // One batch per frame: the runtime handles each entry's texture
            // independently, so all five channels travel in a single call.
            List<TextureBatchEntry> entries = new ArrayList<>(5);
            addBatchEntry(entries, albedoHandle, albedoPatches);
            addBatchEntry(entries, roughnessHandle, roughnessPatches);
            addBatchEntry(entries, metallicHandle, metallicPatches);
            addBatchEntry(entries, emissiveHandle, emissivePatches);
            addBatchEntry(entries, sssHandle, sssPatches);

            TexturePatchInfo info = new TexturePatchInfo();
            info.entries = entries.toArray(new TextureBatchEntry[0]);
            api.updateTextureFromMemory(info);

        } catch (Exception e) {
            LOGGER.error("Error flushing animation patches", e);
        } finally {
            for (ByteBuffer buffer : ownedBuffers) {
                DirectBufferPool.release(buffer);
            }
            pendingPatches.clear();
        }
    }

    private static void ensureScratch(int byteCount) {
        if (scratch.length < byteCount) {
            scratch = new byte[byteCount];
        }
    }

    private static TexturePatch createPatch(PatchRecord p, ByteBuffer data, int rowPitch, int format) {
        TexturePatch patch = new TexturePatch();
        patch.dstX = p.dstX;
        patch.dstY = p.dstY;
        patch.width = p.width;
        patch.height = p.height;
        patch.pixelData = data;
        patch.rowPitch = rowPitch;
        patch.format = format;
        return patch;
    }

    private static void addBatchEntry(List<TextureBatchEntry> entries, long handle, TexturePatch[] patches) {
        if (handle == 0 || patches.length == 0) {
            return;
        }
        TextureBatchEntry entry = new TextureBatchEntry();
        entry.handle = handle;
        entry.patches = patches;
        entries.add(entry);
    }

    private static void addBatchEntry(List<TextureBatchEntry> entries, long handle, List<TexturePatch> patches) {
        if (handle == 0 || patches.isEmpty()) {
            return;
        }
        addBatchEntry(entries, handle, patches.toArray(new TexturePatch[0]));
    }

    public static void clear() {
        // Flip active OFF first so a concurrent addPatch drops its record
        // rather than inserting into a list we're about to wipe.
        active = false;
        pendingPatches.clear();
        labPBRMap = null;
        blockAtlasSpriteIds = null;
        expectedThread = null;
        currentAtlasId.remove();
        LOGGER.info("AtlasAnimationForwarder cleared");
    }

    public static void beginAtlasUpload(Identifier atlasId) {
        currentAtlasId.set(atlasId);
    }

    public static void endAtlasUpload() {
        currentAtlasId.remove();
    }

    /**
     * Deactivates queueing and drops any pending records without wiping
     * {@link #labPBRMap} / {@link #blockAtlasSpriteIds}.
     * Use for reversible pauses like the RTX-toggle path where
     * {@link #resume()} should be a cheap flip without a resource-pack reload.
     */
    public static void pause() {
        active = false;
        pendingPatches.clear();
    }

    public static void resume() {
        active = blockAtlasSpriteIds != null;
    }

    private AtlasAnimationForwarder() {}
}
