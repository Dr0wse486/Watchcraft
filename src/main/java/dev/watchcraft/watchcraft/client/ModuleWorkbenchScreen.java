package dev.watchcraft.watchcraft.client;

import dev.watchcraft.watchcraft.menu.ModuleWorkbenchMenu;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;

/**
 * The bench screen.
 *
 * <p>Almost everything is the base class's work: it positions the slots, draws the item stacks and
 * handles the drag, and it already knows where the player inventory labels go. All this adds is
 * the panel behind them.
 */
public class ModuleWorkbenchScreen extends AbstractContainerScreen<ModuleWorkbenchMenu> {

    private static final ResourceLocation BACKGROUND =
            ResourceLocation.fromNamespaceAndPath("watchcraft", "textures/gui/module_workbench.png");

    public ModuleWorkbenchScreen(ModuleWorkbenchMenu menu, Inventory playerInventory, Component title) {
        super(menu, playerInventory, title);
        this.imageWidth = 176;
        this.imageHeight = 166;
    }

    @Override
    protected void renderBg(GuiGraphics graphics, float partialTick, int mouseX, int mouseY) {
        int x = (this.width - this.imageWidth) / 2;
        int y = (this.height - this.imageHeight) / 2;
        // The short blit overload assumes a 256x256 sheet - it forwards a hardcoded 256, 256 as the
        // texture size. This panel is 176x166, so that overload samples only the top-left 68% x 65%
        // of the image and stretches it across the whole quad: the screen comes out around 1.45x
        // too big and every slot well lands nowhere near its slot. Pass the real size explicitly.
        graphics.blit(BACKGROUND, x, y, 0.0F, 0.0F,
                this.imageWidth, this.imageHeight, this.imageWidth, this.imageHeight);
    }
}
