/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.mixin;

import com.mojang.renderpearl.backend.opengl.GlBackend;
import org.lwjgl.sdl.SDLVideo;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Raises the OpenGL backend's context request from core-profile 3.3 to 4.4, which the GL
 * interop needs: it imports Remix's images and semaphores through {@code EXT_memory_object}
 * and {@code EXT_semaphore}. Every window the backend creates sets the attributes, and the
 * context is created against its first, hidden utility window.
 */
@Mixin(GlBackend.class)
public abstract class GlBackendMixin {
    private static final int rtxremix$REQUIRED_MAJOR = 4;
    private static final int rtxremix$REQUIRED_MINOR = 4;

    @Redirect(
        method = "createWindow",
        at = @At(value = "INVOKE", target = "Lorg/lwjgl/sdl/SDLVideo;SDL_GL_SetAttribute(II)Z")
    )
    private boolean rtxremix$requireOpenGl44(int attribute, int value) {
        if (attribute == SDLVideo.SDL_GL_CONTEXT_MAJOR_VERSION) {
            value = rtxremix$REQUIRED_MAJOR;
        } else if (attribute == SDLVideo.SDL_GL_CONTEXT_MINOR_VERSION) {
            value = rtxremix$REQUIRED_MINOR;
        }
        return SDLVideo.SDL_GL_SetAttribute(attribute, value);
    }
}
