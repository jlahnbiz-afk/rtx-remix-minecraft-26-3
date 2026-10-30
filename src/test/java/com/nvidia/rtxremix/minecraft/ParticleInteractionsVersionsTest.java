/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft;

import com.google.gson.JsonParser;
import java.nio.file.Files;
import java.nio.file.Path;
import net.fabricmc.loader.api.Version;
import net.fabricmc.loader.api.metadata.version.VersionPredicate;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ParticleInteractionsVersionsTest {
    @Test void particleAdapterIsOptionalAndRejectsUnknownInternalLayouts() throws Exception {
        var metadata = JsonParser.parseString(Files.readString(Path.of("src/main/resources/fabric.mod.json"))).getAsJsonObject();
        String mod = "eg_particle_interactions";
        assertFalse(metadata.getAsJsonObject("depends").has(mod));
        for (String version : new String[]{"0.10.5", "0.10.5+26.3-fabric.perf1", "0.10.4", "0.10.6", "0.10.5-alpha"}) {
            boolean blocked = false;
            for (var predicate : metadata.getAsJsonObject("breaks").getAsJsonArray(mod)) {
                blocked |= VersionPredicate.parse(predicate.getAsString()).test(Version.parse(version));
            }
            assertEquals(!version.equals("0.10.5") && !version.equals("0.10.5+26.3-fabric.perf1"), blocked, version);
        }
    }
}
