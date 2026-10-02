package dev.watchcraft.watchcraft.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import dev.watchcraft.watchcraft.Watchcraft;
import dev.watchcraft.watchcraft.entity.ReconDroneEntity;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;

/**
 * Draws the drone from the baked OBJ meshes.
 *
 * <p>The mesh is authored in blocks with Y up and the nose towards -Z, so unlike the cube model it
 * replaces there is no 1/16 scale and no {@code scale(-1, -1, 1)} flip to undo. What is left is
 * the placement: the origin sits at the airframe's centre, and the entity's bounding box is
 * 0.45 blocks tall, so the model is lifted by half of that to sit in the middle of its own hitbox.
 */
public class DroneRenderer extends EntityRenderer<ReconDroneEntity> {

    private static final ResourceLocation TEXTURE =
            ResourceLocation.fromNamespaceAndPath(Watchcraft.MOD_ID, "textures/entity/recon_drone.png");

    /** Half the entity's 0.45 block height; the model's origin is its centre. */
    private static final float MODEL_Y = 0.225F;

    /**
     * Which way the airframe leans into a turn, and which way it rolls. Roll is applied about the
     * model's longitudinal axis, and a positive rotation about that axis lifts its right side, so a
     * roll to the right - a turn lean, or a barrel roll - is a negative angle here. Note that this
     * is the opposite of the sign the pilot's camera wants: see
     * {@code DroneController.CAMERA_ROLL_SIGN}. If the drone visibly banks out of its turns instead
     * of into them, flip this.
     */
    private static final float BANK_SIGN = -1.0F;

    /**
     * Solid red damage flash.
     *
     * <p>{@code pack(1.0F, true)} selects the overlay texture's hurt row (v = 3), which is red at
     * about 70% alpha, and the entity shader mixes that over the sampled texture by that alpha.
     * The {@code u} coordinate is irrelevant on that row - the whole row is one flat colour - so
     * the white-flash gradient it normally picks does not come into it.
     */
    private static final int HURT_OVERLAY = OverlayTexture.pack(1.0F, true);

    public DroneRenderer(EntityRendererProvider.Context context) {
        super(context);
        this.shadowRadius = 0.35F;
        this.shadowStrength = 0.5F;
    }

    @Override
    public void render(ReconDroneEntity entity, float entityYaw, float partialTick, PoseStack poseStack,
                       MultiBufferSource buffer, int packedLight) {
        var body = DroneMeshes.body();
        var glow = DroneMeshes.glow();
        if (body == null || glow == null) {
            return;
        }

        float yaw = Mth.rotLerp(partialTick, entity.yRotO, entity.getYRot());
        float pitch = Mth.lerp(partialTick, entity.xRotO, entity.getXRot());

        poseStack.pushPose();
        poseStack.translate(0.0F, MODEL_Y, 0.0F);
        // The mesh's nose points down -Z, but a Minecraft entity at yaw 0 faces +Z.
        poseStack.mulPose(Axis.YP.rotationDegrees(180.0F - yaw));
        // xRot is positive nose-down, and rotating +Z towards +Y lifts the nose, so it is negated.
        poseStack.mulPose(Axis.XP.rotationDegrees(-pitch));
        // Lean and barrel roll are rotations about the same axis - the model's own nose, which the
        // mesh points down -Z - so they share one sign constant and cannot fight each other.
        poseStack.mulPose(Axis.ZP.rotationDegrees(
                BANK_SIGN * (entity.getBank(partialTick) + entity.getRoll(partialTick))));

        int overlay = entity.getHurtTime() > 0 ? HURT_OVERLAY : OverlayTexture.NO_OVERLAY;
        var rig = DroneRig.of(entity, partialTick);

        body.render(poseStack, buffer, DroneMeshes.BODY_LOOKUP, packedLight, overlay, partialTick, rig);
        // The lens and tail lamp use the additive emissive type, which samples no lightmap, so
        // both of these arguments are inert - the light stays on at noon and at midnight alike,
        // and the hurt overlay does not reach it. They are still passed because they state the
        // intent, and because they are what would be correct if the type ever changed back.
        glow.render(poseStack, buffer, DroneMeshes.GLOW_LOOKUP,
                LightTexture.FULL_BRIGHT, overlay, partialTick, rig);

        poseStack.popPose();
        super.render(entity, entityYaw, partialTick, poseStack, buffer, packedLight);
    }

    @Override
    public ResourceLocation getTextureLocation(ReconDroneEntity entity) {
        return TEXTURE;
    }
}
