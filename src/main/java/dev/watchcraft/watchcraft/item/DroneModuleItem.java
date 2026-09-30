package dev.watchcraft.watchcraft.item;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;

import java.util.List;

/**
 * A plug-in board for the recon drone.
 *
 * <p>Two fields carry the whole rule set. {@link #flag()} is the bit this module claims on the
 * drone's mask, and {@link #requires()} is the bits that must already be present. A module can
 * never be installed twice, because the flag it wants is the flag that already blocks it.
 */
public class DroneModuleItem extends Item {

    private final int flag;
    private final int requires;

    public DroneModuleItem(Properties properties, int flag, int requires) {
        super(properties);
        this.flag = flag;
        this.requires = requires;
    }

    public int flag() {
        return this.flag;
    }

    public int requires() {
        return this.requires;
    }

    /**
     * {@return whether this module fits a drone that already carries {@code installed}}
     *
     * <p>Both halves matter. The prerequisite check is what makes the customization module a real
     * gate rather than a suggested first step, and the duplicate check is what keeps a second
     * attack module from silently eating a slot it cannot use.
     */
    public boolean canInstallOn(int installed) {
        return (installed & this.flag) == 0 && (installed & this.requires) == this.requires;
    }

    @Override
    public void appendHoverText(ItemStack stack, Item.TooltipContext context,
                                List<Component> tooltip, TooltipFlag flag) {
        tooltip.add(Component.translatable(this.getDescriptionId() + ".desc")
                .withStyle(ChatFormatting.GRAY));
        if (this.requires != 0) {
            tooltip.add(Component.translatable("tooltip.watchcraft.requires_customization")
                    .withStyle(ChatFormatting.DARK_AQUA));
        }
    }
}
