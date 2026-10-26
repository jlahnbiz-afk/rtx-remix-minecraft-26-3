/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.cloud;

import java.util.Locale;

/** The cloud styles, in the order the settings slider shows them. */
public enum CloudStyle {
    /** Vanilla's cloud texture, voxelized with vanilla's period and phase. */
    CLASSIC("rtxremix.settings.value.classic"),
    /** Puffy Perlin-Worley cumulus meshes. */
    PROCEDURAL("rtxremix.settings.value.procedural"),
    /** Large rounded blocky clouds in one or two thick layers, from a generated mask. */
    REIMAGINED("rtxremix.settings.value.reimagined"),
    /** Volumetric clouds marched into the sky to the horizon; the sky renderer draws them. */
    UNBOUND("rtxremix.settings.value.unbound");

    private final String labelKey;

    CloudStyle(String labelKey) {
        this.labelKey = labelKey;
    }

    public String labelKey() {
        return labelKey;
    }

    /** The style's name in files, such as a still shot's {@code clouds} field. */
    public String wireName() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** The style a {@link #wireName} names. */
    public static CloudStyle fromWireName(String name) {
        for (CloudStyle style : values()) {
            if (style.wireName().equals(name)) {
                return style;
            }
        }
        throw new IllegalArgumentException("unknown cloud style: " + name);
    }

    /** The style at {@code index}, clamped to the known styles. */
    public static CloudStyle of(int index) {
        CloudStyle[] styles = values();
        return styles[Math.clamp(index, 0, styles.length - 1)];
    }
}
