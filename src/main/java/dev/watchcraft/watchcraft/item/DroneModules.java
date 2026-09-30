package dev.watchcraft.watchcraft.item;

import dev.watchcraft.watchcraft.registry.ModDataComponents;
import dev.watchcraft.watchcraft.registry.ModItems;
import net.minecraft.world.item.ItemStack;

/**
 * The drone's loadout, as a bit mask on the drone's item stack.
 *
 * <p>A mask rather than a list of stacks: the drone is a single item, so there is no room for a
 * container of fitted parts, and a mask is what makes "one of each, and only in the right order"
 * fall out of plain arithmetic instead of a pile of special cases.
 *
 * <p>Every module carries one bit. Ordering is expressed by
 * {@link DroneModuleItem#requires()} rather than by position in the mask, so adding a third module
 * later is a new bit plus a prerequisite, and nothing else has to change.
 */
public final class DroneModules {

    /** Fitting bay: unlocks the rest. Nothing else can be installed without it. */
    public static final int CUSTOMIZATION = 1;
    /** Warhead: unlocks the charge attack. */
    public static final int ATTACK = 2;

    private DroneModules() {
    }

    /** {@return the mask of modules fitted to the given drone stack, or zero if it is bare} */
    public static int of(ItemStack stack) {
        Integer mask = stack.get(ModDataComponents.DRONE_MODULES.get());
        return mask == null ? 0 : mask;
    }

    /**
     * Writes a mask onto a drone stack.
     *
     * <p>An empty mask clears the component rather than storing a zero. Two bare drones should be
     * identical stacks - storing an explicit zero would make them differ, and stacks that differ
     * refuse to merge in an inventory.
     */
    public static void set(ItemStack stack, int mask) {
        if (mask == 0) {
            stack.remove(ModDataComponents.DRONE_MODULES.get());
        } else {
            stack.set(ModDataComponents.DRONE_MODULES.get(), mask);
        }
    }

    public static void install(ItemStack stack, int flag) {
        set(stack, of(stack) | flag);
    }

    public static boolean has(int mask, int flag) {
        return (mask & flag) != 0;
    }

    /** {@return a fresh recon drone item carrying the given loadout} */
    public static ItemStack droneStack(int mask) {
        ItemStack stack = new ItemStack(ModItems.RECON_DRONE.get());
        set(stack, mask);
        return stack;
    }

    /** {@return the item that installs the given single-bit flag, or an empty stack} */
    public static ItemStack moduleStack(int flag) {
        if (flag == CUSTOMIZATION) {
            return new ItemStack(ModItems.CUSTOMIZATION_MODULE.get());
        }
        if (flag == ATTACK) {
            return new ItemStack(ModItems.ATTACK_MODULE.get());
        }
        return ItemStack.EMPTY;
    }
}
