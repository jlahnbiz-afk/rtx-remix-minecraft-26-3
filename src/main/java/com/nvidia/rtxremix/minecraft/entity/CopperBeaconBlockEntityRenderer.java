/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.entity;

import com.mojang.blaze3d.vertex.PoseStack;
import com.nvidia.rtxremix.minecraft.RtxRemixCommonMod;
import com.nvidia.rtxremix.minecraft.block.CopperBeaconBlock;
import com.nvidia.rtxremix.minecraft.block.CopperBeaconBlockEntity;
import com.nvidia.rtxremix.minecraft.block.CopperBeaconPose;
import net.minecraft.client.model.Model;
import net.minecraft.client.model.geom.ModelLayerLocation;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.PartPose;
import net.minecraft.client.model.geom.builders.CubeListBuilder;
import net.minecraft.client.model.geom.builders.LayerDefinition;
import net.minecraft.client.model.geom.builders.MeshDefinition;
import net.minecraft.client.model.geom.builders.PartDefinition;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.renderer.texture.UvMapping;
import net.minecraft.client.resources.model.sprite.SpriteGetter;
import net.minecraft.client.resources.model.sprite.SpriteId;
import net.minecraft.core.Direction;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;
import org.joml.Quaternionf;

/**
 * Renders the independently rotating yoke and lamp head above the static mount.
 *
 * <p>Vanilla draws submitted models after every block entity has been submitted, so
 * the articulation cannot be written into shared model parts at submit time: each
 * beacon's pose travels in its own {@link State}, and the models apply it in
 * {@code setupAnim} when the submit is drawn.
 */
public final class CopperBeaconBlockEntityRenderer
        implements BlockEntityRenderer<CopperBeaconBlockEntity, CopperBeaconBlockEntityRenderer.State> {

    public static final ModelLayerLocation BODY_MODEL_LAYER = new ModelLayerLocation(
            Identifier.fromNamespaceAndPath(RtxRemixCommonMod.MOD_ID, "copper_beacon"), "body");
    public static final ModelLayerLocation LENS_MODEL_LAYER = new ModelLayerLocation(
            Identifier.fromNamespaceAndPath(RtxRemixCommonMod.MOD_ID, "copper_beacon"), "lens");
    private static final SpriteId COPPER_TEXTURE = new SpriteId(
            TextureAtlas.LOCATION_BLOCKS,
            Identifier.withDefaultNamespace("block/copper_block"));
    static final Identifier LENS_SPRITE_ID =
            Identifier.fromNamespaceAndPath(RtxRemixCommonMod.MOD_ID, "block/copper_beacon_lens");
    static final Identifier UNLIT_LENS_SPRITE_ID =
            Identifier.fromNamespaceAndPath(RtxRemixCommonMod.MOD_ID, "block/copper_beacon_lens_unlit");
    private static final SpriteId LENS_TEXTURE = new SpriteId(
            TextureAtlas.LOCATION_BLOCKS,
            LENS_SPRITE_ID);
    private static final SpriteId UNLIT_LENS_TEXTURE = new SpriteId(
            TextureAtlas.LOCATION_BLOCKS,
            UNLIT_LENS_SPRITE_ID);

    private final SpriteGetter sprites;
    private final BodyModel body;
    private final LensModel lens;

    public CopperBeaconBlockEntityRenderer(BlockEntityRendererProvider.Context context) {
        sprites = context.sprites();
        body = new BodyModel(context.bakeLayer(BODY_MODEL_LAYER));
        lens = new LensModel(context.bakeLayer(LENS_MODEL_LAYER));
    }

    public static LayerDefinition getBodyTexturedModelData() {
        MeshDefinition modelData = new MeshDefinition();
        PartDefinition root = modelData.getRoot();
        PartDefinition yokeData = root.addOrReplaceChild(
                "yoke",
                CubeListBuilder.create()
                        .texOffs(0, 0).addBox(-3.0f, -6.0f, -1.0f, 3.0f, 2.0f, 3.0f)
                        .texOffs(0, 0).addBox(0.0f, -6.0f, -1.0f, 3.0f, 2.0f, 3.0f)
                        .texOffs(0, 0).addBox(-5.0f, -5.0f, -1.0f, 2.0f, 8.0f, 3.0f)
                        .texOffs(0, 0).addBox(3.0f, -5.0f, -1.0f, 2.0f, 8.0f, 3.0f),
                PartPose.offset(8.0f, 10.0f, 8.0f));
        yokeData.addOrReplaceChild(
                "head",
                CubeListBuilder.create()
                        .texOffs(0, 0).addBox(-4.0f, -4.0f, -3.0f, 4.0f, 8.0f, 1.0f)
                        .texOffs(0, 0).addBox(0.0f, -4.0f, -3.0f, 4.0f, 8.0f, 1.0f)
                        .texOffs(0, 0).addBox(-4.0f, -4.0f, 3.0f, 4.0f, 8.0f, 1.0f)
                        .texOffs(0, 0).addBox(0.0f, -4.0f, 3.0f, 4.0f, 8.0f, 1.0f)
                        .texOffs(0, 0).addBox(-4.0f, -3.0f, -2.0f, 1.0f, 6.0f, 5.0f)
                        .texOffs(0, 0).addBox(3.0f, -3.0f, -2.0f, 1.0f, 6.0f, 5.0f)
                        .texOffs(0, 0).addBox(-3.0f, -4.0f, -2.0f, 3.0f, 1.0f, 5.0f)
                        .texOffs(0, 0).addBox(0.0f, -4.0f, -2.0f, 3.0f, 1.0f, 5.0f)
                        .texOffs(0, 0).addBox(-3.0f, 3.0f, -2.0f, 3.0f, 1.0f, 5.0f)
                        .texOffs(0, 0).addBox(0.0f, 3.0f, -2.0f, 3.0f, 1.0f, 5.0f)
                        .texOffs(0, 0).addBox(-6.0f, -1.0f, -1.0f, 2.0f, 2.0f, 3.0f)
                        .texOffs(0, 0).addBox(4.0f, -1.0f, -1.0f, 2.0f, 2.0f, 3.0f)
                        .texOffs(0, 0).addBox(-4.0f, -4.0f, -4.0f, 4.0f, 1.0f, 1.0f)
                        .texOffs(0, 0).addBox(0.0f, -4.0f, -4.0f, 4.0f, 1.0f, 1.0f)
                        .texOffs(0, 0).addBox(-4.0f, 3.0f, -4.0f, 4.0f, 1.0f, 1.0f)
                        .texOffs(0, 0).addBox(0.0f, 3.0f, -4.0f, 4.0f, 1.0f, 1.0f)
                        .texOffs(0, 0).addBox(-4.0f, -3.0f, -4.0f, 1.0f, 6.0f, 1.0f)
                        .texOffs(0, 0).addBox(3.0f, -3.0f, -4.0f, 1.0f, 6.0f, 1.0f),
                PartPose.ZERO);
        return LayerDefinition.create(modelData, 16, 16);
    }

    public static LayerDefinition getLensTexturedModelData() {
        MeshDefinition modelData = new MeshDefinition();
        PartDefinition root = modelData.getRoot();
        root.addOrReplaceChild(
                "lens",
                CubeListBuilder.create()
                        .texOffs(0, 0).addBox(-3.0f, -3.0f, -4.01f, 6.0f, 6.0f, 0.0f),
                PartPose.offset(8.0f, 10.0f, 8.0f));
        return LayerDefinition.create(modelData, 6, 6);
    }

    @Override
    public State createRenderState() {
        return new State();
    }

    @Override
    public void extractRenderState(
            CopperBeaconBlockEntity beacon,
            State state,
            float partialTicks,
            Vec3 cameraPosition,
            ModelFeatureRenderer.@Nullable CrumblingOverlay breakProgress) {
        BlockEntityRenderer.super.extractRenderState(beacon, state, partialTicks, cameraPosition, breakProgress);
        BlockState blockState = beacon.getBlockState();
        double animationTick = CopperBeaconAnimationClock.sample(beacon.getLevel(), partialTicks);
        CopperBeaconPose.evaluate(
                blockState.getValue(CopperBeaconBlock.MOUNT),
                blockState.getValue(BlockStateProperties.FACING),
                beacon.yawAngleAt(animationTick),
                beacon.pitchAngleAt(animationTick),
                state.pose);
        state.active = beacon.isActive();
        state.intensityMultiplier = beacon.getIntensityMultiplier();
        state.lensTint = beacon.getLensTint();
    }

    @Override
    public void submit(State state, PoseStack poseStack, SubmitNodeCollector collector, CameraRenderState camera) {
        poseStack.pushPose();
        poseStack.translate(0.5, 0.5, 0.5);
        poseStack.rotate(state.pose.mountingRotation());
        poseStack.translate(-0.5, -0.5, -0.5);

        collector.submitModel(body, state.pose, poseStack, COPPER_TEXTURE.renderType(body.renderType()),
                state.lightCoords, OverlayTexture.NO_OVERLAY, -1,
                new VerticalUvStrip(sprites.get(COPPER_TEXTURE)), 0);
        collector.submitModel(lens, state.pose, poseStack, state.lightCoords, OverlayTexture.NO_OVERLAY,
                state.lensTint, lensTexture(state.active, state.intensityMultiplier), sprites, 0);
        poseStack.popPose();
    }

    static Quaternionf mountingRotation(Direction upDirection, Direction forwardDirection) {
        return CopperBeaconPose.mountingRotation(
                upDirection, forwardDirection, new Quaternionf());
    }

    /** Poses whichever of the parts are given; a model holds only some of them. */
    static void applyArticulation(
            ModelPart yoke,
            ModelPart head,
            ModelPart lens,
            CopperBeaconPose.Pose pose) {
        if (yoke != null) {
            yoke.yRot = pose.modelYawRadians();
            yoke.xRot = 0.0f;
        }
        if (head != null) {
            head.yRot = 0.0f;
            head.xRot = pose.modelPitchRadians();
        }
        if (lens != null) {
            lens.yRot = pose.modelYawRadians();
            lens.xRot = pose.modelPitchRadians();
        }
    }

    static SpriteId lensTexture(boolean active, float intensityMultiplier) {
        return active && intensityMultiplier > 0.0f
                ? LENS_TEXTURE
                : UNLIT_LENS_TEXTURE;
    }

    static float copperTextureV(float v) {
        return 0.25f + v * 0.5f;
    }

    /**
     * Maps the copper block's sprite onto the beacon's cuboids, keeping resource-pack
     * details along the texture edges while excluding all four corners, where
     * stylized copper textures commonly place rivets.
     */
    private record VerticalUvStrip(TextureAtlasSprite sprite) implements UvMapping {
        @Override
        public float getU(float offset) {
            return sprite.getU(offset);
        }

        @Override
        public float getV(float offset) {
            return sprite.getV(copperTextureV(offset));
        }
    }

    /** One beacon's frame of articulation, extracted so its submits can be drawn later. */
    public static final class State extends BlockEntityRenderState {
        final CopperBeaconPose.Pose pose = new CopperBeaconPose.Pose();
        boolean active;
        float intensityMultiplier;
        int lensTint;
    }

    /** The yoke and head, posed from a {@link CopperBeaconPose.Pose} when drawn. */
    private static final class BodyModel extends Model<CopperBeaconPose.Pose> {
        private final ModelPart yoke;
        private final ModelPart head;

        BodyModel(ModelPart root) {
            super(root, RenderTypes::entitySolid);
            yoke = root.getChild("yoke");
            head = yoke.getChild("head");
        }

        @Override
        public void setupAnim(CopperBeaconPose.Pose pose) {
            super.setupAnim(pose);
            applyArticulation(yoke, head, null, pose);
        }
    }

    /** The lens, posed from a {@link CopperBeaconPose.Pose} when drawn. */
    private static final class LensModel extends Model<CopperBeaconPose.Pose> {
        private final ModelPart lens;

        LensModel(ModelPart root) {
            super(root, RenderTypes::entitySolid);
            lens = root.getChild("lens");
        }

        @Override
        public void setupAnim(CopperBeaconPose.Pose pose) {
            super.setupAnim(pose);
            applyArticulation(null, null, lens, pose);
        }
    }
}
