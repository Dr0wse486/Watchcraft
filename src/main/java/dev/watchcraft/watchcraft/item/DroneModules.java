package dev.watchcraft.watchcraft.item;

import dev.watchcraft.watchcraft.registry.ModDataComponents;
import dev.watchcraft.watchcraft.registry.ModItems;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

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
    /** Signal booster, first tier: thirty-two more blocks of link. */
    public static final int SIGNAL_MK1 = 4;
    /** Signal booster, second tier: another thirty-two. Needs {@link #SIGNAL_MK1} fitted first. */
    public static final int SIGNAL_MK2 = 8;
    /** Governor removal: raises the cruise ceiling to the speed module's target. */
    public static final int SPEED = 16;

    /**
     * Every bit this build knows about.
     *
     * <p>Two places need the whole set rather than one flag: the entity masks what it syncs to the
     * client, and the drone's tooltip walks the fitted bits. Both used to spell the flags out by
     * hand, which silently drops a new module the moment one is added - so they read this instead.
     */
    public static final int ALL = CUSTOMIZATION | ATTACK | SIGNAL_MK1 | SIGNAL_MK2 | SPEED;

    /**
     * The same flags, in the order they should be listed.
     *
     * <p>Declaration order is not usable here - the constants are primitives and carry no order of
     * their own - so the sequence is written out once and shared by the tooltip and the item
     * lookup below. It matches the creative tab's order, so a drone's tooltip lists its modules in
     * the same sequence the player picked them up in.
     */
    public static final int[] FLAGS = {CUSTOMIZATION, SIGNAL_MK1, SIGNAL_MK2, SPEED, ATTACK};

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
        return switch (flag) {
            case CUSTOMIZATION -> new ItemStack(ModItems.CUSTOMIZATION_MODULE.get());
            case ATTACK -> new ItemStack(ModItems.ATTACK_MODULE.get());
            case SIGNAL_MK1 -> new ItemStack(ModItems.SIGNAL_MODULE_MK1.get());
            case SIGNAL_MK2 -> new ItemStack(ModItems.SIGNAL_MODULE_MK2.get());
            case SPEED -> new ItemStack(ModItems.SPEED_MODULE.get());
            default -> ItemStack.EMPTY;
        };
    }

    /**
     * {@return the display names of the modules in {@code mask}, in {@link #FLAGS} order}
     *
     * <p>Read off the module items rather than from a second set of language keys, so a new module
     * is picked up here as soon as it is registered. Used to spell out a prerequisite on the
     * module's own tooltip.
     */
    public static List<Component> names(int mask) {
        List<Component> names = new ArrayList<>();
        for (int flag : FLAGS) {
            if (!has(mask, flag)) {
                continue;
            }
            ItemStack module = moduleStack(flag);
            if (!module.isEmpty()) {
                names.add(module.getHoverName());
            }
        }
        return names;
    }
}
