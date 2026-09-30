package dev.watchcraft.watchcraft.registry;

import com.mojang.serialization.Codec;
import dev.watchcraft.watchcraft.Watchcraft;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.codec.ByteBufCodecs;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * Components the mod attaches to its own item stacks.
 *
 * <p>The drone's fitted modules live here rather than in the entity for one reason: the entity is
 * a temporary thing and the item is not. A drone is deployed, flown, shot down, salvaged, thrown
 * and picked up again, and every one of those transitions builds a brand new {@code ItemStack}.
 * Anything kept only on the entity is gone at the first transition. Keeping the loadout on the
 * stack means the drone carries it through all of them.
 */
public final class ModDataComponents {

    public static final DeferredRegister<DataComponentType<?>> DATA_COMPONENTS =
            DeferredRegister.create(Registries.DATA_COMPONENT_TYPE, Watchcraft.MOD_ID);

    /**
     * Bit mask of the modules fitted to a recon drone. Absent means a bare airframe.
     *
     * <p>Both a persistent codec and a network codec are supplied, so the mask survives a world
     * save and also travels with the stack in an inventory sync without a custom packet.
     */
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<Integer>> DRONE_MODULES =
            DATA_COMPONENTS.register("drone_modules",
                    () -> DataComponentType.<Integer>builder()
                            .persistent(Codec.INT)
                            .networkSynchronized(ByteBufCodecs.VAR_INT)
                            .build());

    private ModDataComponents() {
    }
}
