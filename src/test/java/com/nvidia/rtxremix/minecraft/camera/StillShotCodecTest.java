/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.camera;

import com.nvidia.rtxremix.minecraft.cloud.CloudStyle;

import com.google.gson.JsonParseException;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;


class StillShotCodecTest {

    private static StillShot shot(String name, long timeOfDay, StillShot.Weather weather) {
        return new StillShot(name, 10.5, 64.0, -3.25, 90.0f, -12.5f, timeOfDay, weather, true, CloudStyle.UNBOUND);
    }

    @Test
    void roundTripsEveryField() {
        List<StillShot> shots = List.of(
                shot("village-night", 18000, StillShot.Weather.CLEAR),
                new StillShot("lighthouse", 1.0, 2.0, 3.0, 0.0f, 0.0f, 13000,
                        StillShot.Weather.THUNDER, false, CloudStyle.REIMAGINED));

        assertEquals(shots, StillShotCodec.parse(StillShotCodec.toJson(shots)));
    }

    @Test
    void rejectsUnknownFieldsAndOtherVersions() {
        String json = StillShotCodec.toJson(List.of(shot("a", 0, StillShot.Weather.RAIN)));

        assertThrows(JsonParseException.class,
                () -> StillShotCodec.parse(json.replace("\"rtx\"", "\"rtxOn\"")));
        assertThrows(JsonParseException.class,
                () -> StillShotCodec.parse(json.replace("\"version\": 2", "\"version\": 3")));
        assertThrows(JsonParseException.class,
                () -> StillShotCodec.parse(json.replace("\"unbound\"", "\"fluffy\"")));
    }

    @Test
    void readsAVersionOneSetsCloudFlagAsTheProceduralOrClassicStyle() {
        String json = """
                {"version": 1, "shots": [
                  {"name": "a", "x": 0, "y": 64, "z": 0, "yaw": 0, "pitch": 0, "timeOfDay": 6000,
                   "weather": "clear", "rtx": true, "proceduralClouds": true},
                  {"name": "b", "x": 0, "y": 64, "z": 0, "yaw": 0, "pitch": 0, "timeOfDay": 6000,
                   "weather": "rain", "rtx": true, "proceduralClouds": false}]}
                """;
        List<StillShot> shots = StillShotCodec.parse(json);
        assertEquals(CloudStyle.PROCEDURAL, shots.get(0).clouds());
        assertEquals(CloudStyle.CLASSIC, shots.get(1).clouds());
        assertThrows(JsonParseException.class,
                () -> StillShotCodec.parse(json.replace("\"proceduralClouds\": true", "\"clouds\": \"unbound\"")));
    }

    @Test
    void rejectsNamesRepeatedInAnyCaseAndBadWeather() {
        String twice = StillShotCodec.toJson(List.of(
                shot("same", 0, StillShot.Weather.CLEAR), shot("Same", 6000, StillShot.Weather.CLEAR)));
        assertThrows(JsonParseException.class, () -> StillShotCodec.parse(twice));

        String badWeather = StillShotCodec.toJson(List.of(shot("a", 0, StillShot.Weather.CLEAR)))
                .replace("\"clear\"", "\"drizzle\"");
        assertThrows(JsonParseException.class, () -> StillShotCodec.parse(badWeather));
    }

    @Test
    void aShotNameIsASingleFileNameEvenWhenReadFromAFile() {
        for (String name : List.of("../outside", "a/b", "C:\\x", "CON", "con.png", ".", "..", "", " spaced")) {
            assertThrows(IllegalArgumentException.class,
                    () -> shot(name, 0, StillShot.Weather.CLEAR), name);
            String json = StillShotCodec.toJson(List.of(shot("ok", 0, StillShot.Weather.CLEAR)))
                    .replace("\"ok\"", "\"" + name.replace("\\", "\\\\") + "\"");
            assertThrows(JsonParseException.class, () -> StillShotCodec.parse(json), name);
        }
        assertEquals("under_score.v2-x", shot("under_score.v2-x", 0, StillShot.Weather.CLEAR).name());
    }

    @Test
    void aShotKeepsItsTimeWithinOneDay() {
        assertThrows(IllegalArgumentException.class, () -> shot("late", 24000, StillShot.Weather.CLEAR));
        assertThrows(IllegalArgumentException.class, () -> shot("early", -1, StillShot.Weather.CLEAR));
        assertEquals(23999, shot("edge", 23999, StillShot.Weather.CLEAR).timeOfDay());
    }
}
