/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.lod.dh;

import com.seibel.distanthorizons.api.DhApi;
import com.seibel.distanthorizons.api.enums.config.EDhApiLodShading;
import com.seibel.distanthorizons.api.interfaces.config.IDhApiConfig;
import com.seibel.distanthorizons.api.interfaces.config.IDhApiConfigValue;
import com.seibel.distanthorizons.api.methods.events.abstractEvents.DhApiBeforeDeferredRenderEvent;
import com.seibel.distanthorizons.api.methods.events.abstractEvents.DhApiBeforeRenderEvent;
import com.seibel.distanthorizons.api.methods.events.sharedParameterObjects.DhApiCancelableEventParam;
import com.seibel.distanthorizons.api.methods.events.sharedParameterObjects.DhApiRenderParam;

/**
 * The Distant Horizons API calls the LOD capture needs. Only ever loaded with
 * Distant Horizons installed, so nothing else in the mod names its types.
 */
public final class DistantHorizonsCompat {

    /** Shown in Distant Horizons' menu beside the options this mod overrides. */
    private static final String OWNER = "RTX Remix";

    private static volatile boolean capturing;
    private static boolean eventsBound;
    private static IDhApiConfigValue<Integer> renderDistance;

    private DistantHorizonsCompat() {}

    /**
     * Render thread. While capturing, Distant Horizons stops drawing (Remix
     * draws its LODs) and stops baking face shading into their colours (Remix
     * lights them). Either way it rebuilds every section, which is how a
     * capture that starts late receives the sections already built.
     */
    public static void setCapturing(boolean on) {
        capturing = on;
        if (!eventsBound) {
            eventsBound = true;
            DhApi.events.bind(DhApiBeforeRenderEvent.class, new CancelRender());
            DhApi.events.bind(DhApiBeforeDeferredRenderEvent.class, new CancelDeferredRender());
        }
        IDhApiConfig configs = DhApi.Delayed.configs;
        if (configs != null) {
            IDhApiConfigValue<EDhApiLodShading> shading = configs.graphics().lodShading();
            if (on) {
                shading.setValue(EDhApiLodShading.DISABLED, OWNER);
            } else {
                shading.clearValue();
            }
        }
        if (DhApi.Delayed.renderProxy != null) {
            DhApi.Delayed.renderProxy.clearRenderDataCache();
        }
    }

    /** Distant Horizons' LOD render radius in chunks, or 0 before it has started. */
    public static int lodRadiusChunks() {
        if (renderDistance == null) {
            IDhApiConfig configs = DhApi.Delayed.configs;
            if (configs == null) return 0;
            // Cached: the config getter builds a new wrapper per call.
            renderDistance = configs.graphics().chunkRenderDistance();
        }
        Integer chunks = renderDistance.getValue();
        return chunks != null ? chunks : 0;
    }

    private static final class CancelRender extends DhApiBeforeRenderEvent {
        @Override
        public void beforeRender(DhApiCancelableEventParam<DhApiRenderParam> event) {
            if (capturing) event.cancelEvent();
        }
    }

    private static final class CancelDeferredRender extends DhApiBeforeDeferredRenderEvent {
        @Override
        public void beforeRender(DhApiCancelableEventParam<DhApiRenderParam> event) {
            if (capturing) event.cancelEvent();
        }
    }
}
