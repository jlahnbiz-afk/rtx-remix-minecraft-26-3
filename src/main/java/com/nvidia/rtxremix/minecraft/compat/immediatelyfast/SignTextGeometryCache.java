/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.compat.immediatelyfast;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.mojang.blaze3d.vertex.PoseStack;
import com.nvidia.rtxremix.minecraft.entity.RemixVertexConsumerProvider;
import com.nvidia.rtxremix.minecraft.texture.DynamicTextureCache;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.font.TextRenderable;
import net.minecraft.client.renderer.blockentity.AbstractSignRenderer;
import net.minecraft.client.renderer.blockentity.state.SignRenderState;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.block.entity.SignText;

/** Render-thread cache of local glyphs; poses and light remain per submission. */
public final class SignTextGeometryCache {
    private static SignTextGeometryCache active;

    private final Cache<SignText, Entry> entries = CacheBuilder.newBuilder()
            .expireAfterAccess(Duration.ofSeconds(5))
            .maximumWeight(65_536)
            .weigher((SignText text, Entry entry) -> Math.max(1, entry.glyphs.length))
            .build();
    private long generation = DynamicTextureCache.glyphGeneration();

    record Glyph(TextRenderable renderable, Font.DisplayMode mode) {}
    record Layout(Font font, boolean filtering, int maxWidth, int lineHeight, boolean outline) {}
    record Entry(Layout layout, Glyph[] glyphs, boolean cacheable) {}

    SignTextGeometryCache() {}

    public static void render(Font font, SignRenderState state, PoseStack pose,
                              RemixVertexConsumerProvider provider, SignText text) {
        if (active == null) active = new SignTextGeometryCache();
        Entry entry = active.get(font, state, text);
        int light = text.hasGlowingText() ? 15728880 : state.lightCoords;
        for (Glyph glyph : entry.glyphs) {
            glyph.renderable.render(pose.last().pose(), provider.getBuffer(glyph.renderable.renderType(glyph.mode)),
                    light, false);
        }
    }

    /** Font/world teardown must not retain prepared glyphs until another sign is drawn. */
    public static void clear() {
        active = null;
    }

    Entry get(Font font, SignRenderState state, SignText text) {
        if (generation != DynamicTextureCache.glyphGeneration()) {
            entries.invalidateAll();
            generation = DynamicTextureCache.glyphGeneration();
        }
        boolean outline = text.hasGlowingText()
                && (text.getColor() == DyeColor.BLACK || state.drawOutline);
        Entry cached = entries.getIfPresent(text);
        if (cached != null && cached.layout.font == font
                && cached.layout.filtering == state.isTextFilteringEnabled
                && cached.layout.maxWidth == state.maxTextLineWidth
                && cached.layout.lineHeight == state.textLineHeight
                && cached.layout.outline == outline) return cached;
        Layout layout = new Layout(font, state.isTextFilteringEnabled, state.maxTextLineWidth,
                state.textLineHeight, outline);
        Entry prepared = prepare(layout, text);
        if (generation != DynamicTextureCache.glyphGeneration()) {
            entries.invalidateAll();
            generation = DynamicTextureCache.glyphGeneration();
        }
        if (prepared.cacheable) entries.put(text, prepared);
        return prepared;
    }

    static Entry prepare(Layout layout, SignText text) {
        Font font = layout.font;
        FormattedCharSequence[] lines = text.getRenderMessages(layout.filtering, input -> {
            List<FormattedCharSequence> split = font.split(input, layout.maxWidth);
            return split.isEmpty() ? FormattedCharSequence.EMPTY : split.getFirst();
        });
        int darkColor = AbstractSignRenderer.getDarkColor(text);
        int color = text.hasGlowingText() ? text.getColor().getTextColor() : darkColor;
        int midpoint = 4 * layout.lineHeight / 2;
        List<Glyph> glyphs = new ArrayList<>();
        boolean[] obfuscated = {false};
        for (int line = 0; line < lines.length; line++) {
            FormattedCharSequence message = lines[line];
            message.accept((index, style, codePoint) -> {
                obfuscated[0] |= style.isObfuscated();
                return !obfuscated[0];
            });
            // Vanilla centers sign lines with integer division, including odd widths.
            float x = -font.width(message) / 2;
            float y = line * layout.lineHeight - midpoint;
            if (layout.outline) {
                font.prepare8xTextOutline(message, x, y, darkColor)
                        .visit(visitor(glyphs, Font.DisplayMode.NORMAL));
            }
            font.prepareText(message, x, y, color, false, false, 0)
                    .visit(visitor(glyphs, Font.DisplayMode.POLYGON_OFFSET));
        }
        return new Entry(layout, glyphs.toArray(Glyph[]::new), !obfuscated[0]);
    }

    private static Font.GlyphVisitor visitor(List<Glyph> glyphs, Font.DisplayMode mode) {
        return new Font.GlyphVisitor() {
            @Override public void acceptRenderable(TextRenderable glyph) {
                glyphs.add(new Glyph(glyph, mode));
            }
        };
    }
}
