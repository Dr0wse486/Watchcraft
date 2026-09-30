package dev.watchcraft.watchcraft.client;

import com.google.common.collect.ImmutableMap;
import com.mojang.math.Axis;
import dev.watchcraft.watchcraft.entity.ReconDroneEntity;
import net.minecraft.util.Mth;
import net.neoforged.neoforge.client.model.renderable.CompositeRenderable;
import org.joml.Matrix4f;
import org.joml.Quaternionf;

/**
 * Builds the per-frame part transforms for the OBJ drone.
 *
 * <p>{@link CompositeRenderable} carries a lightweight skeleton: each named component can be
 * handed a matrix, and children multiply inside their parent. Our OBJ has every group at the top
 * level, so a flat map keyed by group name is all the rig needs.
 *
 * <p>Pivots are copied from the manifest the mesh generator emits, and are expressed in the same
 * space as the mesh: one unit per block, Y up, nose towards -Z, origin at the airframe centre.
 * They are written as multiples of {@code U} so they can be read against the generator's source.
 */
public final class DroneRig {

    private static final float U = 1.0F / 16.0F;

    /** Rotor hubs, in the same order as {@link #ROTORS}. */
    static final float[][] ROTOR_PIVOTS = {
            {4 * U, 2.25F * U, -4 * U},
            {4 * U, 2.25F * U, 4 * U},
            {-4 * U, 2.25F * U, 4 * U},
            {-4 * U, 2.25F * U, -4 * U},
    };
    private static final String[] ROTORS = {"rotor0", "rotor1", "rotor2", "rotor3"};

    /** The camera ball the yoke and the lens both turn about. */
    static final float[] GIMBAL_PIVOT = {0.0F, -2.5F * U, -2.0F * U};
    private static final String[] GIMBAL = {"gimbal_yoke", "gimbal_lens"};

    /** Degrees per tick. Adjacent rotors spin opposite ways, as they should. */
    private static final float SPIN_RATE = 42.0F;

    /**
     * Whether the gimbal counters the airframe's pitch. The drone is pitched by
     * {@code -xRot} in {@link DroneRenderer}, so levelling the camera means turning the pod by
     * {@code +xRot}. If the lens visibly swings with the airframe instead of holding still, this
     * is the sign to flip.
     */
    private static final float GIMBAL_SIGN = 1.0F;

    private DroneRig() {}

    public static CompositeRenderable.Transforms of(ReconDroneEntity entity, float partialTick) {
        ImmutableMap.Builder<String, Matrix4f> parts = ImmutableMap.builder();

        float spin = (entity.tickCount + partialTick) * SPIN_RATE;
        for (int i = 0; i < ROTORS.length; i++) {
            parts.put(ROTORS[i], about(ROTOR_PIVOTS[i], Axis.YP.rotationDegrees(i % 2 == 0 ? spin : -spin)));
        }

        float pitch = Mth.lerp(partialTick, entity.xRotO, entity.getXRot());
        Matrix4f pod = about(GIMBAL_PIVOT, Axis.XP.rotationDegrees(GIMBAL_SIGN * pitch));
        for (String part : GIMBAL) {
            parts.put(part, pod);
        }

        return CompositeRenderable.Transforms.of(parts.build());
    }

    /** A rotation about `pivot`, expressed in model space. */
    private static Matrix4f about(float[] pivot, Quaternionf rotation) {
        return new Matrix4f()
                .translate(pivot[0], pivot[1], pivot[2])
                .rotate(rotation)
                .translate(-pivot[0], -pivot[1], -pivot[2]);
    }
}
