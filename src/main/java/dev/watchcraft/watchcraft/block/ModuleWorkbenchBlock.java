package dev.watchcraft.watchcraft.block;

import dev.watchcraft.watchcraft.menu.ModuleWorkbenchMenu;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;

/**
 * The bench a drone is opened up on.
 *
 * <p>Only {@code useWithoutItem} is overridden, and that covers both hands on its own: the default
 * {@code useItemOn} returns {@code PASS_TO_DEFAULT_BLOCK_INTERACTION}, which the caller reads as
 * "try the block itself". So the screen opens whether the player is holding a module, holding a
 * drone, or holding nothing at all - which is what a bench should do.
 */
public class ModuleWorkbenchBlock extends Block {

    public ModuleWorkbenchBlock(Properties properties) {
        super(properties);
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos,
                                               Player player, BlockHitResult hitResult) {
        if (level.isClientSide) {
            return InteractionResult.SUCCESS;
        }
        player.openMenu(new SimpleMenuProvider(
                (id, inventory, owner) -> new ModuleWorkbenchMenu(id, inventory),
                Component.translatable("container.watchcraft.module_workbench")));
        return InteractionResult.CONSUME;
    }
}
