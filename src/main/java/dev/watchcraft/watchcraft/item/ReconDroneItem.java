package dev.watchcraft.watchcraft.item;

import dev.watchcraft.watchcraft.entity.ReconDroneEntity;
import dev.watchcraft.watchcraft.registry.ModEntities;
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

    /** Right click air: throw the drone forward so it scouts on the way. */
    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
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
        if (mask == 0) {
            tooltip.add(Component.translatable("tooltip.watchcraft.no_modules")
                    .withStyle(ChatFormatting.DARK_GRAY));
            return;
        }

        tooltip.add(Component.translatable("tooltip.watchcraft.installed")
                .withStyle(ChatFormatting.DARK_AQUA));
        for (int bit : new int[]{DroneModules.CUSTOMIZATION, DroneModules.ATTACK}) {
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
}
