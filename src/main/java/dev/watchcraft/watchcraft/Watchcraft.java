package dev.watchcraft.watchcraft;

import dev.watchcraft.watchcraft.command.ModCommands;
import dev.watchcraft.watchcraft.network.ModNetwork;
import dev.watchcraft.watchcraft.registry.ModBlocks;
import dev.watchcraft.watchcraft.registry.ModCreativeTabs;
import dev.watchcraft.watchcraft.registry.ModDataComponents;
import dev.watchcraft.watchcraft.registry.ModEntities;
import dev.watchcraft.watchcraft.registry.ModItems;
import dev.watchcraft.watchcraft.registry.ModMenus;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;

@Mod(Watchcraft.MOD_ID)
public final class Watchcraft {
    public static final String MOD_ID = "watchcraft";

    public Watchcraft(IEventBus modBus, ModContainer container) {
        // Components come first: the drone's item registration reads its own component type when a
        // stack is built, and the block item below is built from the block registration.
        ModDataComponents.DATA_COMPONENTS.register(modBus);
        ModBlocks.BLOCKS.register(modBus);
        ModItems.ITEMS.register(modBus);
        ModEntities.ENTITY_TYPES.register(modBus);
        ModMenus.MENUS.register(modBus);
        ModCreativeTabs.TABS.register(modBus);
        modBus.addListener(ModNetwork::register);
        NeoForge.EVENT_BUS.addListener(ModCommands::register);
    }
}
