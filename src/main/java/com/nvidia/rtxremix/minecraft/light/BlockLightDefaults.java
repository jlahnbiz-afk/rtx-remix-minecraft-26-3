/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.light;

import com.nvidia.rtxremix.minecraft.block.SpotLightBlock;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CandleBlock;
import net.minecraft.world.level.block.ColorCollection;
import net.minecraft.world.level.block.RedstoneTorchBlock;
import net.minecraft.world.level.block.RedstoneWallTorchBlock;
import net.minecraft.world.level.block.WallTorchBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

/**
 * Built-in block light rules. Resource packs can override these by using the same rule path.
 */
final class BlockLightDefaults {
    static final float DEFAULT_AUTHORED_INTENSITY_MULTIPLIER = 5.0f;
    static final float TORCH_AUTHORED_INTENSITY_MULTIPLIER = 7.5f;
    static final float CAMPFIRE_AUTHORED_INTENSITY_MULTIPLIER = 7.5f;
    static final float THIN_SPHERE_RADIUS_SCALE = 0.4f;
    static final float THIN_SPHERE_INTENSITY_SCALE = 3.0f;
    /** Pre-scale emitter radius of a torch's flame, shared with the light reach calibration. */
    static final float TORCH_RADIUS = 0.2f;
    private static final float AMETHYST_INTENSITY_SCALE = 2.0f;
    static final LightEntry.ColorOverride TORCH_COLOR = LightEntry.ColorOverride.of(1.0f, 0.35f, 0.1f);
    private static final LightEntry.ColorOverride CAMPFIRE_COLOR = LightEntry.ColorOverride.of(1.0f, 0.1f, 0.05f);
    private static final LightEntry.ColorOverride SOUL_COLOR = LightEntry.ColorOverride.of(0.05f, 0.2f, 1.0f);
    private static final LightEntry.ColorOverride REDSTONE_COLOR = LightEntry.ColorOverride.of(1.0f, 0.15f, 0.02f);
    private static final LightEntry.ColorOverride CONDUIT_COLOR = LightEntry.ColorOverride.of(0.45f, 0.9f, 1.0f);
    private static final LightEntry.ColorOverride AMETHYST_COLOR = LightEntry.ColorOverride.of(0.65f, 0.35f, 1.0f);
    /** The green flame copper burns with, in copper torches and lanterns. */
    private static final LightEntry.ColorOverride COPPER_FLAME_COLOR = LightEntry.ColorOverride.of(0.35f, 1.0f, 0.4f);
    private static final LightEntry.ColorOverride FIRE_COLOR = LightEntry.ColorOverride.of(1.0f, 0.45f, 0.12f);
    private static final LightEntry.ColorOverride ENDER_COLOR = LightEntry.ColorOverride.of(0.35f, 1.0f, 0.8f);
    private static final LightEntry.ColorOverride CRYING_COLOR = LightEntry.ColorOverride.of(0.55f, 0.25f, 1.0f);
    private static final LightEntry.ColorOverride SCULK_COLOR = LightEntry.ColorOverride.of(0.25f, 0.85f, 0.85f);
    /**
     * A full glowing cube hides a light at its centre behind its own faces, so its sphere is a
     * little wider than the cube: the caps that stand out of each face are what shine.
     */
    private static final float GLOWING_CUBE_RADIUS = 0.62f;
    /** The share of a glowing cube's sphere that stands out of its six faces. */
    private static final float GLOWING_CUBE_EXPOSED = exposedShare(GLOWING_CUBE_RADIUS,
            0.5f, 0.5f, 0.5f, 0.5f, 0.5f, 0.5f);
    /**
     * A lantern's flame, as wide as the glass around it and a little more, so it shines out of
     * the glass the way a glowing cube shines out of its faces. As small as the flame itself, it
     * lit the inside of the glass and little else.
     */
    private static final float LANTERN_LIGHT_RADIUS = 0.25f;
    /** Half the width of a lantern's glass, and its height, standing and hanging. */
    private static final float LANTERN_HALF_WIDTH = 3.0f / 16.0f;
    private static final float LANTERN_HEIGHT = 7.0f / 16.0f;

    // Shared beacon geometry and path-specific radiance tuning live in
    // BeaconBeam; the block_display lighthouse remains independent.
    // Beam colour is per-variant and lives on the block (SpotLightBlock.beamColor).

    private BlockLightDefaults() {}

    static Map<Identifier, BlockLightRuleManager.Rule> create() {
        LinkedHashMap<Identifier, BlockLightRuleManager.Rule> rules = new LinkedHashMap<>();
        // Built as locals, not static fields: a static field touching Blocks
        // would force the block registry to bootstrap just to load this class,
        // even for a caller that only wants a constant such as TORCH_COLOR.
        Set<Block> candles = candleBlocks();
        Set<Block> candleCakes = candleCakeBlocks();
        add(rules, "torch", exact(Blocks.TORCH),
                (id, state, pos, centroid) -> List.of(thinSphere(id, 0, state, pos, 0.5f, 0.7f, 0.5f, TORCH_RADIUS, TORCH_COLOR)),
                TORCH_AUTHORED_INTENSITY_MULTIPLIER);
        add(rules, "soul_torch", exact(Blocks.SOUL_TORCH),
                (id, state, pos, centroid) -> List.of(thinSphere(id, 0, state, pos, 0.5f, 0.7f, 0.5f, TORCH_RADIUS, SOUL_COLOR)),
                TORCH_AUTHORED_INTENSITY_MULTIPLIER);
        add(rules, "wall_torch", exact(Blocks.WALL_TORCH),
                (id, state, pos, centroid) -> List.of(wallTorch(id, state, pos, TORCH_COLOR)),
                TORCH_AUTHORED_INTENSITY_MULTIPLIER);
        add(rules, "soul_wall_torch", exact(Blocks.SOUL_WALL_TORCH),
                (id, state, pos, centroid) -> List.of(wallTorch(id, state, pos, SOUL_COLOR)),
                TORCH_AUTHORED_INTENSITY_MULTIPLIER);
        add(rules, "redstone_torch", state -> state.is(Blocks.REDSTONE_TORCH) && state.getValue(RedstoneTorchBlock.LIT),
                (id, state, pos, centroid) -> List.of(thinSphere(id, 0, state, pos, 0.5f, 0.7f, 0.5f, 0.16f, REDSTONE_COLOR)),
                TORCH_AUTHORED_INTENSITY_MULTIPLIER);
        add(rules, "redstone_wall_torch", state -> state.is(Blocks.REDSTONE_WALL_TORCH) && state.getValue(RedstoneWallTorchBlock.LIT),
                (id, state, pos, centroid) -> List.of(wallTorch(id, state, pos, REDSTONE_COLOR)),
                TORCH_AUTHORED_INTENSITY_MULTIPLIER);
        add(rules, "small_amethyst_bud", exact(Blocks.SMALL_AMETHYST_BUD),
                (id, state, pos, centroid) -> List.of(amethystCrystal(id, state, pos, 3.0f, 0.08f, 1)));
        add(rules, "medium_amethyst_bud", exact(Blocks.MEDIUM_AMETHYST_BUD),
                (id, state, pos, centroid) -> List.of(amethystCrystal(id, state, pos, 4.0f, 0.10f, 2)));
        add(rules, "large_amethyst_bud", exact(Blocks.LARGE_AMETHYST_BUD),
                (id, state, pos, centroid) -> List.of(amethystCrystal(id, state, pos, 5.0f, 0.13f, 4)));
        add(rules, "amethyst_cluster", exact(Blocks.AMETHYST_CLUSTER),
                (id, state, pos, centroid) -> List.of(amethystCrystal(id, state, pos, 7.0f, 0.18f, 5)));
        add(rules, "campfire", state -> state.is(Blocks.CAMPFIRE) && state.getValue(BlockStateProperties.LIT),
                (id, state, pos, centroid) -> List.of(sphere(id, 0, state, pos, 0.5f, 0.5f, 0.5f, 0.25f, CAMPFIRE_COLOR, null)),
                CAMPFIRE_AUTHORED_INTENSITY_MULTIPLIER);
        add(rules, "soul_campfire", state -> state.is(Blocks.SOUL_CAMPFIRE) && state.getValue(BlockStateProperties.LIT),
                (id, state, pos, centroid) -> List.of(sphere(id, 0, state, pos, 0.5f, 0.5f, 0.5f, 0.25f, SOUL_COLOR, null)),
                CAMPFIRE_AUTHORED_INTENSITY_MULTIPLIER);
        add(rules, "conduit", exact(Blocks.CONDUIT),
                (id, state, pos, centroid) -> List.of(sphere(id, 0, state, pos, 0.5f, 0.5f, 0.5f, 0.1875f, CONDUIT_COLOR, null)));
        add(rules, "light", state -> state.is(Blocks.LIGHT) && state.getValue(BlockStateProperties.LEVEL) > 0,
                (id, state, pos, centroid) -> List.of(sphere(id, 0, state, pos, 0.5f, 0.5f, 0.5f, 0.2f, LightEntry.ColorOverride.of(1, 1, 1), null)));
        add(rules, "candles", state -> candles.contains(state.getBlock()) && state.getValue(CandleBlock.LIT),
                BlockLightDefaults::candles);
        add(rules, "candle_cakes", state -> candleCakes.contains(state.getBlock()) && state.getValue(BlockStateProperties.LIT),
                (id, state, pos, centroid) -> List.of(thinSphere(id, 0, state, pos, 0.5f, 1.0f, 0.5f, 0.08f, null)));
        addEmitterRules(rules);
        BlockLightIntensityControls.validateBuiltInRules(rules.keySet());
        return Collections.unmodifiableMap(new LinkedHashMap<>(rules));
    }

    private static void add(Map<Identifier, BlockLightRuleManager.Rule> rules,
                            String path,
                            Predicate<BlockState> matcher,
                            BlockLightRuleManager.Resolver resolver) {
        add(rules, path, matcher, resolver, DEFAULT_AUTHORED_INTENSITY_MULTIPLIER);
    }

    private static void add(Map<Identifier, BlockLightRuleManager.Rule> rules,
                            String path,
                            Predicate<BlockState> matcher,
                            BlockLightRuleManager.Resolver resolver,
                            float authoredIntensityMultiplier) {
        Identifier id = BlockLightRuleIds.builtIn(path);
        add(rules, id, matcher, resolver, authoredIntensityMultiplier);
    }

    private static void add(Map<Identifier, BlockLightRuleManager.Rule> rules,
                            Identifier id,
                            Predicate<BlockState> matcher,
                            BlockLightRuleManager.Resolver resolver,
                            float authoredIntensityMultiplier) {
        rules.put(id, BlockLightRuleManager.rule(
                id,
                matcher,
                (ruleId, state, pos, sampler) -> resolver
                        .resolve(ruleId, state, pos, sampler)
                        .stream()
                        .map(entry -> entry.withAuthoredIntensityMultiplier(
                                authoredIntensityMultiplier))
                        .toList()));
    }

    private static Predicate<BlockState> exact(Block block) {
        return state -> state.is(block);
    }

    /**
     * Every other vanilla block that gives off light and has a place a light can sit, so the
     * Block Lights table covers them all. Lava, magma, portals, glow lichen and sea pickles
     * are left to glow through their surfaces: they come in fields of hundreds, where a light
     * each would crowd out the lights that matter.
     */
    private static void addEmitterRules(Map<Identifier, BlockLightRuleManager.Rule> rules) {
        add(rules, "copper_torch", blocks("copper_torch"),
                (id, state, pos, centroid) -> List.of(thinSphere(id, 0, state, pos,
                        0.5f, 0.7f, 0.5f, TORCH_RADIUS, COPPER_FLAME_COLOR)),
                TORCH_AUTHORED_INTENSITY_MULTIPLIER);
        add(rules, "copper_wall_torch", blocks("copper_wall_torch"),
                (id, state, pos, centroid) -> List.of(wallTorch(id, state, pos, COPPER_FLAME_COLOR)),
                TORCH_AUTHORED_INTENSITY_MULTIPLIER);
        add(rules, "lantern", blocks("lantern"),
                (id, state, pos, centroid) -> List.of(lanternFlame(id, state, pos, TORCH_COLOR)),
                TORCH_AUTHORED_INTENSITY_MULTIPLIER);
        add(rules, "soul_lantern", blocks("soul_lantern"),
                (id, state, pos, centroid) -> List.of(lanternFlame(id, state, pos, SOUL_COLOR)),
                TORCH_AUTHORED_INTENSITY_MULTIPLIER);
        add(rules, "copper_lanterns", blocks("copper_lantern", "exposed_copper_lantern",
                        "weathered_copper_lantern", "oxidized_copper_lantern", "waxed_copper_lantern",
                        "waxed_exposed_copper_lantern", "waxed_weathered_copper_lantern",
                        "waxed_oxidized_copper_lantern"),
                (id, state, pos, centroid) -> List.of(lanternFlame(id, state, pos, COPPER_FLAME_COLOR)),
                TORCH_AUTHORED_INTENSITY_MULTIPLIER);
        add(rules, "fire", blocks("fire"),
                (id, state, pos, centroid) -> List.of(sphere(id, 0, state, pos, 0.5f, 0.35f, 0.5f, 0.3f, FIRE_COLOR, null)),
                CAMPFIRE_AUTHORED_INTENSITY_MULTIPLIER);
        add(rules, "soul_fire", blocks("soul_fire"),
                (id, state, pos, centroid) -> List.of(sphere(id, 0, state, pos, 0.5f, 0.35f, 0.5f, 0.3f, SOUL_COLOR, null)),
                CAMPFIRE_AUTHORED_INTENSITY_MULTIPLIER);
        add(rules, "end_rod", blocks("end_rod"),
                (id, state, pos, centroid) -> List.of(endRod(id, state, pos)));
        add(rules, "beacon", blocks("beacon"),
                (id, state, pos, centroid) -> List.of(sphere(id, 0, state, pos, 0.5f, 0.5f, 0.5f, 0.3f,
                        LightEntry.ColorOverride.of(0.8f, 0.95f, 1.0f), null)));

        glowingCube(rules, "glowstone", blocks("glowstone"), LightEntry.ColorOverride.of(1.0f, 0.78f, 0.45f));
        glowingCube(rules, "sea_lantern", blocks("sea_lantern"), LightEntry.ColorOverride.of(0.7f, 0.92f, 1.0f));
        glowingCube(rules, "shroomlight", blocks("shroomlight"), LightEntry.ColorOverride.of(1.0f, 0.55f, 0.25f));
        glowingCube(rules, "jack_o_lantern", blocks("jack_o_lantern"), LightEntry.ColorOverride.of(1.0f, 0.55f, 0.2f));
        glowingCube(rules, "redstone_lamp", lit(blocks("redstone_lamp")), LightEntry.ColorOverride.of(1.0f, 0.7f, 0.4f));
        glowingCube(rules, "copper_bulbs", lit(blocks("copper_bulb", "exposed_copper_bulb", "weathered_copper_bulb",
                "oxidized_copper_bulb", "waxed_copper_bulb", "waxed_exposed_copper_bulb",
                "waxed_weathered_copper_bulb", "waxed_oxidized_copper_bulb")),
                LightEntry.ColorOverride.of(1.0f, 0.75f, 0.5f));
        glowingCube(rules, "crying_obsidian", blocks("crying_obsidian"), CRYING_COLOR);
        glowingCube(rules, "redstone_ores", lit(blocks("redstone_ore", "deepslate_redstone_ore")), REDSTONE_COLOR);
        glowingCube(rules, "sculk_catalyst", blocks("sculk_catalyst"), SCULK_COLOR);
        Map<Block, LightEntry.ColorOverride> froglights = Map.of(
                block("ochre_froglight"), LightEntry.ColorOverride.of(1.0f, 0.85f, 0.5f),
                block("verdant_froglight"), LightEntry.ColorOverride.of(0.65f, 1.0f, 0.6f),
                block("pearlescent_froglight"), LightEntry.ColorOverride.of(1.0f, 0.72f, 0.95f));
        add(rules, "froglights", state -> froglights.containsKey(state.getBlock()),
                (id, state, pos, centroid) -> List.of(cubeLight(id, state, pos, froglights.get(state.getBlock()))));

        add(rules, "glow_berries", blocks("cave_vines", "cave_vines_plant")
                        .and(state -> state.getValue(BlockStateProperties.BERRIES)),
                (id, state, pos, centroid) -> List.of(sphere(id, 0, state, pos, 0.5f, 0.4f, 0.5f, 0.15f,
                        LightEntry.ColorOverride.of(1.0f, 0.7f, 0.3f), null)));
        add(rules, "furnaces", lit(blocks("furnace", "smoker", "blast_furnace")),
                BlockLightDefaults::furnaceMouth);
        add(rules, "respawn_anchor", blocks("respawn_anchor")
                        .and(state -> state.getValue(BlockStateProperties.RESPAWN_ANCHOR_CHARGES) > 0),
                (id, state, pos, centroid) -> List.of(sphere(id, 0, state, pos, 0.5f, 1.0f, 0.5f, 0.3f, CRYING_COLOR, null)));
        small(rules, "enchanting_table", blocks("enchanting_table"), 0.75f, 0.25f, LightEntry.ColorOverride.of(0.65f, 0.5f, 1.0f));
        small(rules, "ender_chest", blocks("ender_chest"), 0.45f, 0.3f, ENDER_COLOR);
        small(rules, "brewing_stand", blocks("brewing_stand"), 0.45f, 0.08f, LightEntry.ColorOverride.of(1.0f, 0.8f, 0.4f));
        small(rules, "end_portal_frame", blocks("end_portal_frame"), 0.85f, 0.25f, ENDER_COLOR);
        small(rules, "dragon_egg", blocks("dragon_egg"), 0.5f, 0.45f, CRYING_COLOR);
        small(rules, "sculk_sensors", blocks("sculk_sensor", "calibrated_sculk_sensor"), 0.55f, 0.2f, SCULK_COLOR);
        small(rules, "brown_mushroom", blocks("brown_mushroom"), 0.3f, 0.1f, LightEntry.ColorOverride.of(1.0f, 0.8f, 0.6f));
        small(rules, "trial_spawner", blocks("trial_spawner"), 0.5f, 0.35f, LightEntry.ColorOverride.of(1.0f, 0.6f, 0.3f));
        small(rules, "vault", blocks("vault"), 0.5f, 0.35f, LightEntry.ColorOverride.of(1.0f, 0.6f, 0.3f));
        small(rules, "firefly_bush", blocks("firefly_bush"), 0.6f, 0.35f, LightEntry.ColorOverride.of(0.85f, 1.0f, 0.35f));
        small(rules, "lava_cauldron", blocks("lava_cauldron"), 0.95f, 0.35f, FIRE_COLOR);
    }

    private static void glowingCube(Map<Identifier, BlockLightRuleManager.Rule> rules, String path,
                                    Predicate<BlockState> matcher, LightEntry.ColorOverride color) {
        add(rules, path, matcher, (id, state, pos, centroid) -> List.of(cubeLight(id, state, pos, color)));
    }

    private static LightEntry cubeLight(Identifier id, BlockState state, BlockPos pos,
                                        LightEntry.ColorOverride color) {
        return sphere(id, 0, state, pos, 0.5f, 0.5f, 0.5f, GLOWING_CUBE_RADIUS, color,
                torchParityIntensity(state.getLightEmission(), GLOWING_CUBE_RADIUS, GLOWING_CUBE_EXPOSED, color,
                        DEFAULT_AUTHORED_INTENSITY_MULTIPLIER));
    }

    /**
     * The intensity at which the part of a sphere light that stands out of its block, the share
     * {@code exposed} of it, gives off a torch's power per light level times {@code lightLevel}.
     * Vanilla rates a light by its level alone, so a lamp of level 15 lights about as far as a
     * torch of 14; at the plain light-level radiance a glowing cube gave off about fifteen times
     * a torch's light. Divided by the authored multiplier the rule applies afterwards.
     */
    static float torchParityIntensity(int lightLevel, float radius, float exposed,
                                      LightEntry.ColorOverride color, float authoredMultiplier) {
        float power = LightPower.of(LightShape.SPHERE, radius, 0f, 0f, 0f, color.r(), color.g(), color.b())
                * exposed;
        float target = LightPower.torchReference() / LightPower.TORCH_LIGHT_LEVEL * lightLevel;
        return target / power / authoredMultiplier;
    }

    /**
     * The share of a sphere of {@code radius} that stands out of the faces of the box around its
     * centre, at {@code faceDistances} from it: a cap of height h has area 2πrh.
     */
    static float exposedShare(float radius, float... faceDistances) {
        float height = 0f;
        for (float distance : faceDistances) {
            height += Math.max(0f, radius - distance);
        }
        return Math.min(1f, height / (2f * radius));
    }

    /** A light above the middle of the block, at {@code y} up it. */
    private static void small(Map<Identifier, BlockLightRuleManager.Rule> rules, String path,
                              Predicate<BlockState> matcher, float y, float radius, LightEntry.ColorOverride color) {
        add(rules, path, matcher, (id, state, pos, centroid) -> List.of(
                sphere(id, 0, state, pos, 0.5f, y, 0.5f, radius, color, null)));
    }

    /** Matches the blocks with these vanilla ids; looked up when the rules are built, after bootstrap. */
    private static Predicate<BlockState> blocks(String... paths) {
        Set<Block> set = new HashSet<>();
        for (String path : paths) {
            set.add(block(path));
        }
        return state -> set.contains(state.getBlock());
    }

    private static Block block(String path) {
        Identifier id = Identifier.withDefaultNamespace(path);
        if (!BuiltInRegistries.BLOCK.containsKey(id)) {
            throw new IllegalStateException("No vanilla block " + id + " for a built-in light rule");
        }
        return BuiltInRegistries.BLOCK.getValue(id);
    }

    private static Predicate<BlockState> lit(Predicate<BlockState> matcher) {
        return matcher.and(state -> state.getValue(BlockStateProperties.LIT));
    }

    /** The flame inside a lantern's body, which hangs a little higher than it stands. */
    private static LightEntry lanternFlame(Identifier id, BlockState state, BlockPos pos,
                                           LightEntry.ColorOverride color) {
        // A hanging lantern's glass sits a pixel higher.
        float bottom = state.getValue(BlockStateProperties.HANGING) ? 1.0f / 16.0f : 0.0f;
        float y = bottom + LANTERN_HEIGHT * 0.5f;
        float exposed = exposedShare(LANTERN_LIGHT_RADIUS, LANTERN_HALF_WIDTH, LANTERN_HALF_WIDTH,
                LANTERN_HALF_WIDTH, LANTERN_HALF_WIDTH, LANTERN_HEIGHT * 0.5f, LANTERN_HEIGHT * 0.5f);
        return sphere(id, 0, state, pos, 0.5f, y, 0.5f, LANTERN_LIGHT_RADIUS, color,
                torchParityIntensity(state.getLightEmission(), LANTERN_LIGHT_RADIUS, exposed, color,
                        TORCH_AUTHORED_INTENSITY_MULTIPLIER));
    }

    /** The fire seen through a lit furnace's front opening, just outside its face. */
    private static List<LightEntry> furnaceMouth(Identifier id, BlockState state, BlockPos pos,
                                                 BlockLightRuleManager.ModelSampler centroid) {
        Direction facing = state.getValue(BlockStateProperties.HORIZONTAL_FACING);
        return List.of(sphere(id, 0, state, pos,
                0.5f + 0.52f * facing.getStepX(), 0.35f, 0.5f + 0.52f * facing.getStepZ(),
                0.18f, FIRE_COLOR, null));
    }

    private static LightEntry sphere(Identifier id, int index, BlockState state, BlockPos pos,
                                     float x, float y, float z, float radius,
                                     LightEntry.ColorOverride color, Float intensity) {
        return BlockLightRuleManager.sphere(id, index, state, pos, x, y, z, radius, color, intensity);
    }

    private static LightEntry thinSphere(Identifier id, int index, BlockState state, BlockPos pos,
                                         float x, float y, float z, float radius,
                                         LightEntry.ColorOverride color) {
        float intensity = state.getLightEmission()
                * BlockLightRuleManager.RADIANCE_PER_LIGHT_LEVEL
                * THIN_SPHERE_INTENSITY_SCALE;
        return sphere(id, index, state, pos, x, y, z, radius * THIN_SPHERE_RADIUS_SCALE, color, intensity);
    }

    private static LightEntry wallTorch(Identifier id, BlockState state, BlockPos pos,
                                        LightEntry.ColorOverride color) {
        Direction facing = state.getValue(WallTorchBlock.FACING).getOpposite();
        return thinSphere(id, 0, state, pos,
                0.5f + 0.27f * facing.getStepX(),
                0.92f,
                0.5f + 0.27f * facing.getStepZ(),
                0.2f, color);
    }

    private static LightEntry endRod(Identifier id, BlockState state, BlockPos pos) {
        Direction facing = state.getValue(BlockStateProperties.FACING);
        BlockLightRuleManager.Vec3 axis = BlockLightRuleManager.Vec3.fromAxis(facing.getAxis());
        return BlockLightRuleManager.cylinder(id, 0, state, pos,
                0.5f, 0.5f, 0.5f, 0.125f, axis, 1.0f, null, null);
    }

    private static LightEntry amethystCrystal(Identifier id, BlockState state, BlockPos pos,
                                              float heightPixels, float radius, int lightLevel) {
        Direction facing = state.getValue(BlockStateProperties.FACING);
        float halfHeight = heightPixels / 32.0f;
        float offsetFromCenter = 0.5f - halfHeight;
        float intensity = lightLevel
                * BlockLightRuleManager.RADIANCE_PER_LIGHT_LEVEL
                * AMETHYST_INTENSITY_SCALE;
        return sphere(id, 0, state, pos,
                0.5f - facing.getStepX() * offsetFromCenter,
                0.5f - facing.getStepY() * offsetFromCenter,
                0.5f - facing.getStepZ() * offsetFromCenter,
                radius, AMETHYST_COLOR, intensity);
    }

    private static List<LightEntry> candles(Identifier id, BlockState state, BlockPos pos,
                                            BlockLightRuleManager.ModelSampler centroid) {
        List<LightEntry> entries = new ArrayList<>(state.getValue(CandleBlock.CANDLES));
        float[][] offsets = candleOffsets(state.getValue(CandleBlock.CANDLES));
        for (int i = 0; i < offsets.length; i++) {
            float[] o = offsets[i];
            entries.add(thinSphere(id, i, state, pos, o[0], o[1], o[2], 0.06f, null));
        }
        return entries;
    }

    private static Set<Block> candleBlocks() {
        return withUndyed(Blocks.CANDLE, Blocks.DYED_CANDLE);
    }

    private static Set<Block> candleCakeBlocks() {
        return withUndyed(Blocks.CANDLE_CAKE, Blocks.DYED_CANDLE_CAKE);
    }

    private static Set<Block> withUndyed(Block undyed, ColorCollection<Block> dyed) {
        Set<Block> blocks = new HashSet<>(dyed.asList());
        blocks.add(undyed);
        return Set.copyOf(blocks);
    }

    private static float[][] candleOffsets(int candles) {
        return switch (candles) {
            case 1 -> new float[][]{{0.5f, 0.5f, 0.5f}};
            case 2 -> new float[][]{{0.375f, 0.44f, 0.5f}, {0.625f, 0.5f, 0.44f}};
            case 3 -> new float[][]{{0.5f, 0.313f, 0.625f}, {0.375f, 0.44f, 0.5f}, {0.56f, 0.5f, 0.44f}};
            default -> new float[][]{{0.44f, 0.313f, 0.56f}, {0.625f, 0.44f, 0.56f}, {0.375f, 0.44f, 0.375f}, {0.56f, 0.5f, 0.375f}};
        };
    }
}
