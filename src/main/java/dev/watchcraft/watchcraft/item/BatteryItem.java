package dev.watchcraft.watchcraft.item;

import dev.watchcraft.watchcraft.registry.ModDataComponents;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;

import java.util.List;

/**
 * A battery pack for the recon drone.
 *
 * <p>The capacity lives on the item rather than in a shared table, so the two packs differ by one
 * constructor argument and nothing else has to know there are two of them. Everything downstream
 * asks the stack how much is left and never asks what kind it is.
 *
 * <p>Remaining charge is stored as a data component, and an <em>absent</em> component means full
 * rather than empty. That keeps a battery fresh off the crafting table free of state - which
 * matters because a component would also stop two fresh batteries from stacking.
 */
public class BatteryItem extends Item {

    /**
     * Charge units are percent of a drone's full pack, so these read directly as the figures the
     * design talks in: the copper pack is a quarter, the graphite one fills the bar.
     */
    public static final int COPPER_CAPACITY = 25;
    public static final int GRAPHITE_CAPACITY = 100;

    private final int capacity;

    public BatteryItem(Properties properties, int capacity) {
        super(properties);
        this.capacity = capacity;
    }

    /** {@return the charge this pack holds when full} */
    public int capacity() {
        return this.capacity;
    }

    /** {@return the charge left in the given stack, clamped to this pack's capacity} */
    public int chargeOf(ItemStack stack) {
        Integer stored = stack.get(ModDataComponents.BATTERY_CHARGE.get());
        return stored == null ? this.capacity : Mth.clamp(stored, 0, this.capacity);
    }

    public void setCharge(ItemStack stack, int charge) {
        stack.set(ModDataComponents.BATTERY_CHARGE.get(), Mth.clamp(charge, 0, this.capacity));
    }

    /**
     * {@return the same pack with its charge reduced by {@code amount}, or an empty stack}
     *
     * <p>Returns empty at zero rather than a spent pack, so a drained battery leaves the slot
     * instead of sitting in it looking usable.
     */
    public ItemStack drainedBy(ItemStack stack, int amount) {
        int left = this.chargeOf(stack) - amount;
        if (left <= 0) {
            return ItemStack.EMPTY;
        }
        ItemStack result = stack.copyWithCount(1);
        this.setCharge(result, left);
        return result;
    }

    // The vanilla durability bar is the natural place for this: it is already the "how much is
    // left" affordance players read without being told, and it costs no tooltip space.

    @Override
    public boolean isBarVisible(ItemStack stack) {
        return this.chargeOf(stack) < this.capacity;
    }

    @Override
    public int getBarWidth(ItemStack stack) {
        return Math.round(13.0F * this.chargeOf(stack) / this.capacity);
    }

    @Override
    public int getBarColor(ItemStack stack) {
        // Red at empty through to green at full, the same sweep vanilla uses for durability.
        return Mth.hsvToRgb(0.33F * this.chargeOf(stack) / this.capacity, 1.0F, 1.0F);
    }

    @Override
    public void appendHoverText(ItemStack stack, Item.TooltipContext context,
                                List<Component> tooltip, TooltipFlag flag) {
        tooltip.add(Component.translatable(this.getDescriptionId() + ".desc")
                .withStyle(ChatFormatting.GRAY));
        tooltip.add(Component.translatable("tooltip.watchcraft.battery_charge",
                        this.chargeOf(stack), this.capacity)
                .withStyle(ChatFormatting.AQUA));
    }
}
