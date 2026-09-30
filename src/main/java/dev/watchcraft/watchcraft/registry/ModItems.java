package dev.watchcraft.watchcraft.registry;

import dev.watchcraft.watchcraft.Watchcraft;
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

    private ModItems() {
    }
}
