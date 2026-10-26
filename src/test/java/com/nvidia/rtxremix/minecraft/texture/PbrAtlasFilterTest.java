/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.texture;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.JarURLConnection;
import java.nio.charset.StandardCharsets;
import java.util.jar.JarFile;
import java.util.regex.Pattern;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The atlas filters that keep LabPBR maps out of the block and item atlases. A pack's
 * {@code _n} and {@code _s} maps sit beside their textures in the same folder, so the
 * atlas stitched them in as sprites next to their albedo, and distant terrain mixed in the
 * normal map's violet. The renderer reads them from the resources instead.
 */
class PbrAtlasFilterTest {

    @ParameterizedTest
    @CsvSource({"blocks, block", "items, item"})
    void filtersEveryLabPbrMap(String atlas, String folder) throws Exception {
        Pattern path = filterPath(atlas);
        assertTrue(path.matcher(folder + "/oak_leaves_n").find());
        assertTrue(path.matcher(folder + "/oak_leaves_s").find());
        assertTrue(path.matcher(folder + "/oak_leaves_sss").find());
        assertFalse(path.matcher(folder + "/oak_leaves").find());
        assertFalse(path.matcher("entity/" + folder + "_n").find());
    }

    @ParameterizedTest
    @CsvSource({"blocks, block", "items, item"})
    void filtersNoVanillaTexture(String atlas, String folder) throws Exception {
        Pattern path = filterPath(atlas);
        String prefix = "assets/minecraft/textures/" + folder + "/";
        int checked = 0;
        for (var urls = getClass().getClassLoader().getResources(prefix); urls.hasMoreElements(); ) {
            if (!(urls.nextElement().openConnection() instanceof JarURLConnection jar)) continue;
            try (JarFile file = new JarFile(jar.getJarFileURL().getPath())) {
                for (var entries = file.entries(); entries.hasMoreElements(); ) {
                    String name = entries.nextElement().getName();
                    if (!name.startsWith(prefix) || !name.endsWith(".png")) continue;
                    String sprite = folder + "/" + name.substring(prefix.length(), name.length() - 4);
                    assertFalse(path.matcher(sprite).find(), sprite);
                    checked++;
                }
            }
        }
        assertTrue(checked > 100, checked + " vanilla textures checked");
    }

    private Pattern filterPath(String atlas) throws Exception {
        JsonObject source = null;
        for (var urls = getClass().getClassLoader().getResources("assets/minecraft/atlases/" + atlas + ".json");
                urls.hasMoreElements(); ) {
            try (InputStream in = urls.nextElement().openStream()) {
                JsonObject json = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8))
                        .getAsJsonObject();
                for (var element : json.getAsJsonArray("sources")) {
                    JsonObject candidate = element.getAsJsonObject();
                    if (candidate.get("type").getAsString().equals("minecraft:filter")) source = candidate;
                }
            }
        }
        assertNotNull(source, "no filter source in " + atlas);
        JsonObject pattern = source.getAsJsonObject("pattern");
        assertEquals(1, pattern.size(), "a namespace pattern would let modded maps through");
        return Pattern.compile(pattern.get("path").getAsString());
    }
}
