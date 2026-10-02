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
 * the panel behind them, readable label colours, and the one {@code renderTooltip} call the base
 * class's NeoForge-rewritten {@code render} omits.
 */
public class ModuleWorkbenchScreen extends AbstractContainerScreen<ModuleWorkbenchMenu> {

    private static final ResourceLocation BACKGROUND =
            ResourceLocation.fromNamespaceAndPath("watchcraft", "textures/gui/module_workbench.png");

    /** 标题色，和面板边框的青色同一路。 */
    private static final int TITLE_COLOUR = 0xFF6FE3F5;
    /** 玩家物品栏标签，比标题暗一档，主次分明。 */
    private static final int INVENTORY_COLOUR = 0xFF8FA6B2;

    public ModuleWorkbenchScreen(ModuleWorkbenchMenu menu, Inventory playerInventory, Component title) {
        super(menu, playerInventory, title);
        this.imageWidth = 176;
        this.imageHeight = 166;
    }

    /**
     * 补上原版那一行 {@code renderTooltip}。
     *
     * <p>{@code AbstractContainerScreen#render} 在 1.21.1 里被 NeoForge 整个复制粘贴重写过
     * （为了在背景与控件之间插一个 {@code ContainerScreenEvent.Render.Background} 事件），
     * 复制出来的那份**只画背景、槽位高亮、标签和跟手物品**，并没有调用 {@code renderTooltip}。
     * 原版每一个容器界面（工作台、熔炉、信标……）都在自己的 {@code render} 末尾手动补一句
     * {@code this.renderTooltip(guiGraphics, mouseX, mouseY)}，所以它们才有悬浮文本。
     *
     * <p>本类之前只覆写了 {@code renderBg} 与 {@code renderLabels}，没有覆写 {@code render}，
     * 于是继承到的就是 NeoForge 那份——悬浮文本的调用整条路径都不存在，表现就是
     * 「界面里所有物品都没有 tooltip」。这一句补回来即可。
     */
    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);
        this.renderTooltip(graphics, mouseX, mouseY);
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

    /**
     * 自己画标签，因为原版那套在这个面板上完全看不见。
     *
     * <p>{@code AbstractContainerScreen#renderLabels} 用的颜色是 {@code 0x404040} - 深灰，而且
     * 最后一个参数传的是 {@code false}，连投影都不带。这套配色是给原版那种浅米色 GUI 准备的，
     * 落到这张近黑的科技感面板上，标题和"物品栏"两个字就彻底糊进背景里了，看起来像整个界面
     * 一个字都没有。这里换成面板同色系的青与灰蓝，并打开投影。
     */
    @Override
    protected void renderLabels(GuiGraphics graphics, int mouseX, int mouseY) {
        graphics.drawString(this.font, this.title, this.titleLabelX, this.titleLabelY,
                TITLE_COLOUR, true);
        graphics.drawString(this.font, this.playerInventoryTitle, this.inventoryLabelX,
                this.inventoryLabelY, INVENTORY_COLOUR, true);
    }
}
