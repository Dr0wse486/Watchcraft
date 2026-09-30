package dev.watchcraft.watchcraft.registry;

import dev.watchcraft.watchcraft.Watchcraft;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class ModCreativeTabs {
    public static final DeferredRegister<CreativeModeTab> TABS =
            DeferredRegister.create(Registries.CREATIVE_MODE_TAB, Watchcraft.MOD_ID);

    public static final DeferredHolder<CreativeModeTab, CreativeModeTab> MAIN = TABS.register("main",
            () -> CreativeModeTab.builder()
                    .title(Component.translatable("itemGroup.watchcraft"))
                    .icon(() -> new ItemStack(ModItems.RECON_DRONE.get()))
                    .displayItems((params, output) -> {
                        // Bench first, then parts, then what they build, then what plugs into it:
                        // the tab reads top to bottom as the order you actually need things in.
                        output.accept(ModItems.MODULE_WORKBENCH.get());
                        output.accept(ModItems.DRONE_CHASSIS.get());
                        output.accept(ModItems.DRONE_PROPELLER.get());
                        output.accept(ModItems.RECON_DRONE.get());
                        output.accept(ModItems.CUSTOMIZATION_MODULE.get());
                        output.accept(ModItems.ATTACK_MODULE.get());
                    })
                    .build());

    private ModCreativeTabs() {
    }
}
