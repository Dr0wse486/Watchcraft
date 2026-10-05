package dev.watchcraft.watchcraft.item;

import dev.watchcraft.watchcraft.entity.ReconDroneEntity;
import dev.watchcraft.watchcraft.registry.ModDataComponents;import dev.watchcraft.watchcraft.registry.ModEntities;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import java.util.List;

public class ReconDroneItem extends Item {

    public ReconDroneItem(Properties properties) {
        super(properties);
    }

    /** {@return the pack fitted to the given drone item, or an empty stack} */
    public static ItemStack batteryOf(ItemStack drone) {
        ItemStack battery = drone.get(ModDataComponents.DRONE_BATTERY.get());
        return battery == null ? ItemStack.EMPTY : battery;
    }

    /**
     * Writes a pack onto a drone item.
     *
     * <p>An empty pack clears the component rather than storing an empty stack - the same reasoning
     * as the module mask: two drones with no battery should be identical stacks, and an explicit
     * "I have no battery" marker would stop them merging in an inventory.
     */
    public static void setBattery(ItemStack drone, ItemStack battery) {
        if (battery.isEmpty()) {
            drone.remove(ModDataComponents.DRONE_BATTERY.get());
        } else {
            drone.set(ModDataComponents.DRONE_BATTERY.get(), battery.copyWithCount(1));
        }
    }

    /** Right click a block: deploy the drone hovering in front of that face. */
    @Override
    public InteractionResult useOn(UseOnContext context) {
        Level level = context.getLevel();
        Player player = context.getPlayer();
        if (player == null) {
            return InteractionResult.PASS;
        }

        BlockPos target = context.getClickedPos().relative(context.getClickedFace());
        if (!level.getBlockState(target).getCollisionShape(level, target).isEmpty()) {
            return InteractionResult.FAIL;
        }

        if (!level.isClientSide) {
            if (ReconDroneEntity.hasDeployed(player)) {
                player.displayClientMessage(
                        net.minecraft.network.chat.Component.translatable("message.watchcraft.limit"), true);
                return InteractionResult.FAIL;
            }
            ReconDroneEntity drone = ModEntities.RECON_DRONE.get().create(level);
            if (drone == null) {
                return InteractionResult.FAIL;
            }
            Vec3 spawn = Vec3.atCenterOf(target);
            drone.moveTo(spawn.x, spawn.y, spawn.z, player.getYRot(), 0.0F);
            drone.setOwner(player);
            // Whatever was fitted at the bench flies with it.
            drone.setModules(DroneModules.of(context.getItemInHand()));
            drone.setBattery(batteryOf(context.getItemInHand()));
            drone.setDeltaMovement(Vec3.ZERO);
            level.addFreshEntity(drone);
            level.playSound(null, spawn.x, spawn.y, spawn.z, SoundEvents.ARMOR_STAND_PLACE,
                    SoundSource.PLAYERS, 0.7F, 1.6F);
            if (!player.getAbilities().instabuild) {
                context.getItemInHand().shrink(1);
            }
        }
        return InteractionResult.sidedSuccess(level.isClientSide);
    }

    /**
     * 右键空气：把无人机投出去，让它边飞边探。
     *
     * <p>潜行时这一下改管电池：另一只手拿着电池就是装上，机体上已经有就是拆下。
     * 之所以要能在**物品**上装，是因为机体得先能部署出去才轮得到在它身上右键 ——
     * 而"没电池的机体画面全糊"那条又要求玩家在起飞前就把电带上，两者缺一不可。
     */
    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);

        if (player.isShiftKeyDown()) {
            ItemStack battery = batteryInOtherHand(player, hand);
            if (!battery.isEmpty() || !batteryOf(stack).isEmpty()) {
                if (!level.isClientSide) {
                    toggleBattery(player, stack, battery);
                }
                return InteractionResultHolder.sidedSuccess(stack, level.isClientSide);
            }
        }

        if (!level.isClientSide) {
            if (ReconDroneEntity.hasDeployed(player)) {
                player.displayClientMessage(
                        net.minecraft.network.chat.Component.translatable("message.watchcraft.limit"), true);
                return InteractionResultHolder.fail(stack);
            }
            ReconDroneEntity.throwFrom(level, player, stack);
            level.playSound(null, player.getX(), player.getY(), player.getZ(), SoundEvents.SNOWBALL_THROW,
                    SoundSource.PLAYERS, 0.6F, 1.4F);
        }
        return InteractionResultHolder.sidedSuccess(stack, level.isClientSide);
    }

    /** {@return the pack held in the hand that is not holding the drone, or an empty stack} */
    private static ItemStack batteryInOtherHand(Player player, InteractionHand hand) {
        InteractionHand other = hand == InteractionHand.MAIN_HAND
                ? InteractionHand.OFF_HAND
                : InteractionHand.MAIN_HAND;
        ItemStack held = player.getItemInHand(other);
        return held.getItem() instanceof BatteryItem ? held : ItemStack.EMPTY;
    }

    /**
     * 装或拆，看机体上现在有没有 —— 和在机体实体上右键是同一套语义，两边不该有第二套说法。
     *
     * <p>手里拿着电池而机体上已经有一块时，拆下来的是**机体上那块**（连同它剩余的电量），
     * 手里那块原样留着：拆装严格可逆，不会出现"拿 B 装进去结果 A 不见了"。
     */
    private static void toggleBattery(Player player, ItemStack drone, ItemStack battery) {
        ItemStack seated = batteryOf(drone);
        if (seated.isEmpty()) {
            setBattery(drone, battery.copyWithCount(1));
            battery.shrink(1);
            player.displayClientMessage(
                    Component.translatable("message.watchcraft.battery_installed"), true);
            return;
        }

        setBattery(drone, ItemStack.EMPTY);
        if (!player.getInventory().add(seated)) {
            player.drop(seated, false);
        }
        player.displayClientMessage(
                Component.translatable("message.watchcraft.battery_removed"), true);
    }

    /**
     * 物品说明，以及挂在机体上的模块清单。
     *
     * <p>模块名称直接从模块物品自己的名字取，而不是再抄一份语言键：这样以后加第三块模块时，
     * 只要它是个 {@link DroneModuleItem}，这里就自动认得。
     */
    @Override
    public void appendHoverText(ItemStack stack, Item.TooltipContext context,
                                List<Component> tooltip, TooltipFlag flag) {
        tooltip.add(Component.translatable("item.watchcraft.recon_drone.desc")
                .withStyle(ChatFormatting.GRAY));

        int mask = DroneModules.of(stack);
        ItemStack battery = batteryOf(stack);
        if (mask == 0 && battery.isEmpty()) {
            tooltip.add(Component.translatable("tooltip.watchcraft.no_modules")
                    .withStyle(ChatFormatting.DARK_GRAY));
            return;
        }

        if (mask != 0) {
            tooltip.add(Component.translatable("tooltip.watchcraft.installed")
                    .withStyle(ChatFormatting.DARK_AQUA));
            for (int bit : DroneModules.FLAGS) {
                if (!DroneModules.has(mask, bit)) {
                    continue;
                }
                ItemStack module = DroneModules.moduleStack(bit);
                if (!module.isEmpty()) {
                    tooltip.add(Component.literal("  ")
                            .append(module.getHoverName())
                            .withStyle(ChatFormatting.AQUA));
                }
            }
        }

        // The pack is listed separately from the modules because it is not one: it is consumable,
        // it can be swapped in the field, and its charge is the thing the player actually wants to
        // read here.
        if (battery.getItem() instanceof BatteryItem pack) {
            tooltip.add(Component.translatable("tooltip.watchcraft.battery_fitted")
                    .withStyle(ChatFormatting.DARK_AQUA));
            tooltip.add(Component.literal("  ")
                    .append(battery.getHoverName())
                    .append(Component.literal("  " + pack.chargeOf(battery) + "%"))
                    .withStyle(ChatFormatting.AQUA));
        }
    }
}
