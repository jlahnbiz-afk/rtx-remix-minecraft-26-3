/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Every translation names the keys English does, with the same format arguments. */
class LanguageFilesTest {

    private static final String LANG_DIR = "/assets/rtx-remix-renderer/lang/";
    private static final Pattern FORMAT_ARGUMENT = Pattern.compile("%(\\d+\\$)?[sd]");

    @ParameterizedTest
    @ValueSource(strings = {"ja_jp"})
    void aTranslationCoversEnglishExactly(String language) throws IOException {
        JsonObject english = read("en_us");
        JsonObject translation = read(language);
        assertEquals(english.keySet(), translation.keySet(), language + " keys");
        List<String> mismatched = new ArrayList<>();
        for (String key : english.keySet()) {
            if (!arguments(english.get(key).getAsString()).equals(arguments(translation.get(key).getAsString()))) {
                mismatched.add(key);
            }
        }
        assertTrue(mismatched.isEmpty(), language + " format arguments differ: " + mismatched);
    }

    private static List<String> arguments(String text) {
        List<String> found = new ArrayList<>();
        Matcher matcher = FORMAT_ARGUMENT.matcher(text);
        while (matcher.find()) found.add(matcher.group());
        return found;
    }

    private static JsonObject read(String language) throws IOException {
        try (InputStream in = LanguageFilesTest.class.getResourceAsStream(LANG_DIR + language + ".json")) {
            assertNotNull(in, language + ".json");
            return JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
        }
    }
}
