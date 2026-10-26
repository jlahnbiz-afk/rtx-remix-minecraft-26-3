/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.texture;

import com.nvidia.rtxremix.RemixAPI;
import com.nvidia.rtxremix.TextureBatchEntry;
import com.nvidia.rtxremix.TextureFormat;
import com.nvidia.rtxremix.TexturePatch;
import com.nvidia.rtxremix.TexturePatchInfo;
import com.nvidia.rtxremix.TexturePurpose;
import com.nvidia.rtxremix.minecraft.asset.RemixTexture;
import com.nvidia.rtxremix.minecraft.util.DirectBufferPool;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.CompletableFuture;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The water surface's normal map: the ocean flipbook's frames played into one texture that
 * tiles in world space, with raindrop rings added while it rains.
 *
 * <p>The runtime used to play the flipbook as a sprite sheet, where every frame borders other
 * frames instead of wrapping onto itself, so a tile's edge never met the next tile's waves.
 * One texture the hardware repeats has no such edge, and the runtime regenerates its mips
 * after every update. Rain reaches the runtime the same way, as texture content, because a
 * material's surface state cannot change after it is made.
 *
 * <p>The frames come from the flipbook's second mip level, decoded on a worker; until then
 * the water is calm. Render thread only, apart from that decode.
 */
public final class WaterNormals {

    private static final Logger LOGGER = LoggerFactory.getLogger(WaterNormals.class);

    /** Texels across one water tile: a flipbook frame at its second mip level. */
    public static final int SIZE = 512;
    // The flipbook's layout, as tools/src/rtx_tools/water_normals.py writes it.
    private static final int SHEET_COLS = 12;
    private static final int SHEET_ROWS = 10;
    private static final int FRAMES = SHEET_COLS * SHEET_ROWS;
    private static final int FPS = 20;
    private static final int MIP_LEVEL = 1;
    private static final int DXGI_FORMAT_BC5_UNORM = 83;
    private static final int DDS_DATA_OFFSET = 4 + 124 + 20;
    private static final long RIPPLE_SEED = 0x52414E44L;
    /** Rain strength change that redraws a held frame. */
    private static final float STRENGTH_EPSILON = 0.02f;
    private static final float[] DECODE = new float[256];

    static {
        for (int i = 0; i < 256; i++) {
            DECODE[i] = i / 127.5f - 1f;
        }
    }

    private final RemixAPI api;
    private final RemixTexture texture;
    /** Per frame, octahedral normals as red-green byte pairs. */
    private final CompletableFuture<byte[][]> ocean;
    private final CompletableFuture<RainRipples> ripples;
    private final byte[] texels = new byte[SIZE * SIZE * 4];
    private final ByteBuffer staging;
    private final TexturePatch patch = new TexturePatch();
    private final TextureBatchEntry entry = new TextureBatchEntry();
    private final TexturePatchInfo patchInfo = new TexturePatchInfo();

    private int appliedOceanFrame = -1;
    private int appliedRingFrame = -1;
    private float appliedStrength;
    private boolean released;

    private WaterNormals(RemixAPI api, RemixTexture texture, Path flipbook, float tileBlocks, boolean rings) {
        this.api = api;
        this.texture = texture;
        this.staging = DirectBufferPool.acquire(texels.length);
        patch.width = SIZE;
        patch.height = SIZE;
        patch.rowPitch = SIZE * 4;
        patch.format = TextureFormat.RGBA8_UNORM;
        entry.patches = new TexturePatch[] {patch};
        patchInfo.entries = new TextureBatchEntry[] {entry};
        ocean = CompletableFuture.supplyAsync(() -> {
            try {
                return decodeFrames(flipbook);
            } catch (IOException | RuntimeException e) {
                LOGGER.warn("Failed to read the water normal flipbook {}; the water stays calm", flipbook, e);
                return null;
            }
        });
        ripples = rings
                ? CompletableFuture.supplyAsync(() -> RainRipples.generate(SIZE, tileBlocks, RIPPLE_SEED))
                : CompletableFuture.completedFuture(null);
    }

    /**
     * Creates calm water normals over a tile {@code tileBlocks} wide, or returns null when the
     * texture fails. {@code rings} adds raindrop rings in the rain.
     */
    public static WaterNormals create(RemixAPI api, Uploader uploader, Path flipbook, float tileBlocks,
                                      boolean rings) {
        RemixTexture texture = uploader.upload("water_normals", calmNormals(), SIZE, SIZE,
                TextureFormat.RGBA8_UNORM, TexturePurpose.NORMAL_OTH);
        return texture == null ? null : new WaterNormals(api, texture, flipbook, tileBlocks, rings);
    }

    /** Creates a texture the owner manages and destroys. */
    public interface Uploader {
        RemixTexture upload(String name, byte[] rgba, int width, int height, int format, int purpose);
    }

    public RemixTexture texture() {
        return texture;
    }

    /**
     * Shows the waves at {@code seconds} into their animation and rain of {@code rain}, 0 to 1.
     * Uploads only when a frame or the rain changed.
     */
    public void update(float rain, double seconds) {
        if (released || texture.isDeleted()) return;
        byte[][] frames = ocean.getNow(null);
        if (frames == null) return;
        int oceanFrame = (int) Math.floorMod((long) Math.floor(seconds * FPS), (long) FRAMES);
        RainRipples rings = rain > 0f ? ripples.getNow(null) : null;
        int ringFrame = rings != null ? RainRipples.frameAt(seconds) : -1;
        float strength = rings != null ? Math.min(1f, rain) : 0f;
        if (oceanFrame == appliedOceanFrame && ringFrame == appliedRingFrame
                && Math.abs(strength - appliedStrength) < STRENGTH_EPSILON) {
            return;
        }
        compose(frames[oceanFrame], rings != null ? rings.normals(ringFrame) : null, strength, texels);
        staging.clear();
        staging.put(texels).flip();
        patch.pixelData = staging;
        entry.handle = texture.getHandle();
        try {
            api.updateTextureFromMemory(patchInfo);
            appliedOceanFrame = oceanFrame;
            appliedRingFrame = ringFrame;
            appliedStrength = strength;
        } catch (RuntimeException e) {
            LOGGER.warn("Failed to update the water normals: {}", e.getMessage());
        } finally {
            patch.pixelData = null;
        }
    }

    /**
     * Writes the ocean frame with the rings' slopes, scaled by {@code strength}, added to its
     * own, as octahedral normals in the red and green of RGBA8 texels: the runtime's normal
     * encoding, which an update must already be in.
     */
    static void compose(byte[] oceanRg, byte[] ringRg, float strength, byte[] rgba) {
        int texelCount = oceanRg.length / 2;
        for (int i = 0; i < texelCount; i++) {
            byte x = oceanRg[i * 2];
            byte y = oceanRg[i * 2 + 1];
            if (ringRg != null && strength > 0f) {
                float ox = DECODE[x & 0xFF], oy = DECODE[y & 0xFF];
                float rx = DECODE[ringRg[i * 2] & 0xFF], ry = DECODE[ringRg[i * 2 + 1] & 0xFF];
                // An upper-hemisphere normal is proportional to (px, py, 1 - |px| - |py|), so its
                // slopes are px / pz and py / pz; the sum is projected back onto the octahedron.
                float oz = Math.max(1.0e-3f, 1f - Math.abs(ox) - Math.abs(oy));
                float rz = Math.max(1.0e-3f, 1f - Math.abs(rx) - Math.abs(ry));
                float sx = ox / oz + strength * rx / rz;
                float sy = oy / oz + strength * ry / rz;
                float norm = Math.abs(sx) + Math.abs(sy) + 1f;
                x = encode(sx / norm);
                y = encode(sy / norm);
            }
            rgba[i * 4] = x;
            rgba[i * 4 + 1] = y;
            rgba[i * 4 + 2] = 0;
            rgba[i * 4 + 3] = (byte) 0xFF;
        }
    }

    /** Returns the staging buffer; the owner destroys the texture. */
    public void release() {
        if (released) return;
        released = true;
        ocean.cancel(false);
        ripples.cancel(false);
        DirectBufferPool.release(staging);
    }

    /** Every frame of the flipbook at {@link #MIP_LEVEL}, as red-green pairs per texel. */
    static byte[][] decodeFrames(Path flipbook) throws IOException {
        try (FileChannel channel = FileChannel.open(flipbook, StandardOpenOption.READ)) {
            ByteBuffer header = ByteBuffer.allocate(DDS_DATA_OFFSET).order(ByteOrder.LITTLE_ENDIAN);
            readFully(channel, header, 0L);
            int height = header.getInt(12);
            int width = header.getInt(16);
            boolean dx10 = header.get(84) == 'D' && header.get(85) == 'X' && header.get(86) == '1'
                    && header.get(87) == '0';
            if (header.getInt(0) != 0x20534444 || !dx10 || header.getInt(128) != DXGI_FORMAT_BC5_UNORM
                    || width != SHEET_COLS * SIZE * 2 || height != SHEET_ROWS * SIZE * 2) {
                throw new IOException("Expected a " + SHEET_COLS * SIZE * 2 + "x" + SHEET_ROWS * SIZE * 2
                        + " BC5 sheet, found " + width + "x" + height);
            }
            long level0Bytes = (long) (width / 4) * (height / 4) * 16;
            int levelWidth = width >> MIP_LEVEL;
            int levelHeight = height >> MIP_LEVEL;
            ByteBuffer blocks = ByteBuffer.allocate((levelWidth / 4) * (levelHeight / 4) * 16);
            readFully(channel, blocks, DDS_DATA_OFFSET + level0Bytes);
            return splitFrames(blocks.array(), levelWidth, levelHeight);
        }
    }

    /** Decodes a BC5 sheet of {@link #SHEET_COLS} by {@link #SHEET_ROWS} frames, row by row. */
    static byte[][] splitFrames(byte[] blocks, int width, int height) {
        int frameSize = width / SHEET_COLS;
        byte[][] frames = new byte[SHEET_COLS * SHEET_ROWS][frameSize * frameSize * 2];
        byte[] red = new byte[16];
        byte[] green = new byte[16];
        int blocksAcross = width / 4;
        for (int by = 0; by < height / 4; by++) {
            for (int bx = 0; bx < blocksAcross; bx++) {
                int offset = (by * blocksAcross + bx) * 16;
                decodeBc4(blocks, offset, red);
                decodeBc4(blocks, offset + 8, green);
                for (int t = 0; t < 16; t++) {
                    int x = bx * 4 + (t & 3);
                    int y = by * 4 + (t >> 2);
                    byte[] frame = frames[(y / frameSize) * SHEET_COLS + x / frameSize];
                    int i = ((y % frameSize) * frameSize + x % frameSize) * 2;
                    frame[i] = red[t];
                    frame[i + 1] = green[t];
                }
            }
        }
        return frames;
    }

    /** One BC4 block's sixteen values, in row order. */
    static void decodeBc4(byte[] data, int offset, byte[] out) {
        int e0 = data[offset] & 0xFF;
        int e1 = data[offset + 1] & 0xFF;
        long indices = 0L;
        for (int b = 0; b < 6; b++) {
            indices |= (long) (data[offset + 2 + b] & 0xFF) << (8 * b);
        }
        for (int t = 0; t < 16; t++) {
            int index = (int) (indices >>> (3 * t)) & 7;
            int value;
            if (index == 0) {
                value = e0;
            } else if (index == 1) {
                value = e1;
            } else if (e0 > e1) {
                value = ((8 - index) * e0 + (index - 1) * e1) / 7;
            } else if (index < 6) {
                value = ((6 - index) * e0 + (index - 1) * e1) / 5;
            } else {
                value = index == 6 ? 0 : 255;
            }
            out[t] = (byte) value;
        }
    }

    private static void readFully(FileChannel channel, ByteBuffer buffer, long position) throws IOException {
        while (buffer.hasRemaining()) {
            int read = channel.read(buffer, position + buffer.position());
            if (read < 0) throw new IOException("The water normal flipbook ends early");
        }
        buffer.flip();
    }

    private static byte encode(float component) {
        return (byte) Math.round((component * 0.5f + 0.5f) * 255f);
    }

    /** Straight-up normals in the runtime's octahedral encoding. */
    private static byte[] calmNormals() {
        byte[] rgba = new byte[SIZE * SIZE * 4];
        for (int i = 0; i < SIZE * SIZE; i++) {
            rgba[i * 4] = (byte) 128;
            rgba[i * 4 + 1] = (byte) 128;
            rgba[i * 4 + 3] = (byte) 255;
        }
        return rgba;
    }
}
