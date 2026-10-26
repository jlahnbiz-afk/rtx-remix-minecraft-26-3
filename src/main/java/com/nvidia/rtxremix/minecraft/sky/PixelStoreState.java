/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.sky;

import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL21;

/**
 * Tightly packed pixel transfers for the duration of a read or an upload, with whatever the
 * game had set put back afterwards. Minecraft leaves its own row lengths, skips and buffer
 * bindings behind after screenshots and texture uploads, and a transfer sized for tight rows
 * then fails, or reads the wrong texels, on every frame after it.
 *
 * <p>Render thread only, with the GL context current.
 */
final class PixelStoreState implements AutoCloseable {

    private static final int[] PACK = {
            GL11.GL_PACK_ROW_LENGTH, GL11.GL_PACK_SKIP_ROWS, GL11.GL_PACK_SKIP_PIXELS, GL11.GL_PACK_ALIGNMENT};
    private static final int[] UNPACK = {
            GL11.GL_UNPACK_ROW_LENGTH, GL11.GL_UNPACK_SKIP_ROWS, GL11.GL_UNPACK_SKIP_PIXELS,
            GL11.GL_UNPACK_ALIGNMENT};

    private final int[] parameters;
    private final int[] saved;
    private final int unpackBuffer;

    private PixelStoreState(int[] parameters, int alignment, boolean unpack) {
        this.parameters = parameters;
        saved = new int[parameters.length];
        for (int i = 0; i < parameters.length; i++) {
            saved[i] = GL11.glGetInteger(parameters[i]);
            GL11.glPixelStorei(parameters[i], i == parameters.length - 1 ? alignment : 0);
        }
        unpackBuffer = unpack ? GL11.glGetInteger(GL21.GL_PIXEL_UNPACK_BUFFER_BINDING) : 0;
        if (unpackBuffer != 0) {
            GL15.glBindBuffer(GL21.GL_PIXEL_UNPACK_BUFFER, 0);
        }
    }

    /** Tight reads of four-byte texels; close the result to restore the game's settings. */
    static PixelStoreState tightPack() {
        return new PixelStoreState(PACK, 4, false);
    }

    /**
     * Tight uploads of single-byte rows from client memory; close the result to restore the
     * game's settings and buffer binding.
     */
    static PixelStoreState tightUnpack() {
        return new PixelStoreState(UNPACK, 1, true);
    }

    @Override
    public void close() {
        for (int i = 0; i < parameters.length; i++) {
            GL11.glPixelStorei(parameters[i], saved[i]);
        }
        if (unpackBuffer != 0) {
            GL15.glBindBuffer(GL21.GL_PIXEL_UNPACK_BUFFER, unpackBuffer);
        }
    }
}
