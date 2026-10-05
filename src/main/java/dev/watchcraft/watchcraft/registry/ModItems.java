package dev.watchcraft.watchcraft.registry;

import dev.watchcraft.watchcraft.Watchcraft;
import dev.watchcraft.watchcraft.item.BatteryItem;
import dev.watchcraft.watchcraft.item.DroneModuleItem;
import dev.watchcraft.watchcraft.item.DroneModules;
import dev.watchcraft.watchcraft.item.ReconDroneItem;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Rarity;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class ModItems {
    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(Watchcraft.MOD_ID);

    /** The bench the drone is opened up on. Four iron ingots and a redstone dust. */
    public static final DeferredItem<BlockItem> MODULE_WORKBENCH =
            ITEMS.registerSimpleBlockItem("module_workbench", ModBlocks.MODULE_WORKBENCH);

    /** Airframe the drone is assembled on. One redstone block and four iron ingots. */
    public static final DeferredItem<Item> DRONE_CHASSIS = ITEMS.register("drone_chassis",
            () -> new Item(new Item.Properties()));

    /** One rotor. The drone takes four of them. One iron ingot and two iron nuggets. */
    public static final DeferredItem<Item> DRONE_PROPELLER = ITEMS.register("drone_propeller",
            () -> new Item(new Item.Properties()));

    public static final DeferredItem<ReconDroneItem> RECON_DRONE = ITEMS.register("recon_drone",
            () -> new ReconDroneItem(new Item.Properties().stacksTo(1).rarity(Rarity.UNCOMMON)));

    /**
     * The fitting bay every other board needs. Without it a drone has nowhere to seat a module,
     * so this is the first thing to build and the gate on everything else.
     */
    public static final DeferredItem<DroneModuleItem> CUSTOMIZATION_MODULE = ITEMS.register("customization_module",
            () -> new DroneModuleItem(new Item.Properties().stacksTo(16),
                    DroneModules.CUSTOMIZATION, 0));

    /** Warhead board: turns the drone itself into the munition. */
    public static final DeferredItem<DroneModuleItem> ATTACK_MODULE = ITEMS.register("attack_module",
            () -> new DroneModuleItem(new Item.Properties().stacksTo(16),
                    DroneModules.ATTACK, DroneModules.CUSTOMIZATION));

    /**
     * Signal booster, first tier: thirty-two more blocks of leash.
     *
     * <p>The second tier gates on the first rather than on the fitting bay alone, which is the
     * whole point of having two items instead of one: you cannot skip straight to the long range.
     */
    public static final DeferredItem<DroneModuleItem> SIGNAL_MODULE_MK1 = ITEMS.register("signal_module_mk1",
            () -> new DroneModuleItem(new Item.Properties().stacksTo(16),
                    DroneModules.SIGNAL_MK1, DroneModules.CUSTOMIZATION));

    public static final DeferredItem<DroneModuleItem> SIGNAL_MODULE_MK2 = ITEMS.register("signal_module_mk2",
            () -> new DroneModuleItem(new Item.Properties().stacksTo(16),
                    DroneModules.SIGNAL_MK2, DroneModules.CUSTOMIZATION | DroneModules.SIGNAL_MK1));

    /** Governor removal: the cruise ceiling stops being the airframe's limit. */
    public static final DeferredItem<DroneModuleItem> SPEED_MODULE = ITEMS.register("speed_module",
            () -> new DroneModuleItem(new Item.Properties().stacksTo(16),
                    DroneModules.SPEED, DroneModules.CUSTOMIZATION));

    /**
     * The cheap pack: a quarter of a full charge, so a quarter of the flight time.
     *
     * <p>Stackable while fresh - an absent charge component is what makes that work, since two
     * packs that both carry an explicit "full" marker would refuse to merge.
     */
    public static final DeferredItem<BatteryItem> COPPER_BATTERY = ITEMS.register("copper_battery",
            () -> new BatteryItem(new Item.Properties().stacksTo(16), BatteryItem.COPPER_CAPACITY));

    /**
     * Half of the graphite pack, and useless on its own - the recipe for the pack needs two of
     * them, which is what makes it a prerequisite rather than an ingredient you might skip.
     */
    public static final DeferredItem<Item> GRAPHITE_ELECTRODE = ITEMS.register("graphite_electrode",
            () -> new Item(new Item.Properties()));

    public static final DeferredItem<BatteryItem> GRAPHITE_BATTERY = ITEMS.register("graphite_battery",
            () -> new BatteryItem(new Item.Properties().stacksTo(16), BatteryItem.GRAPHITE_CAPACITY));

    private ModItems() {
    }
}
