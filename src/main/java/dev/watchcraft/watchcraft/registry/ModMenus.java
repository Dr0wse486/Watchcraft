package dev.watchcraft.watchcraft.registry;

import dev.watchcraft.watchcraft.Watchcraft;
import dev.watchcraft.watchcraft.menu.ModuleWorkbenchMenu;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.flag.FeatureFlags;
import net.minecraft.world.inventory.MenuType;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class ModMenus {

    public static final DeferredRegister<MenuType<?>> MENUS =
            DeferredRegister.create(Registries.MENU, Watchcraft.MOD_ID);

    /**
     * The bench menu carries no extra opening data.
     *
     * <p>A plain {@code MenuType} rather than one extended through {@code IMenuTypeExtension},
     * because there is nothing to send: the menu owns its own slots instead of reading a block
     * entity's, so both sides can build it from the container id and the player's inventory alone.
     */
    public static final DeferredHolder<MenuType<?>, MenuType<ModuleWorkbenchMenu>> MODULE_WORKBENCH =
            MENUS.register("module_workbench",
                    () -> new MenuType<>(ModuleWorkbenchMenu::new, FeatureFlags.DEFAULT_FLAGS));

    private ModMenus() {
    }
}
