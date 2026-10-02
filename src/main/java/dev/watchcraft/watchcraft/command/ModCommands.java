package dev.watchcraft.watchcraft.command;

import com.mojang.brigadier.arguments.BoolArgumentType;
import dev.watchcraft.watchcraft.entity.ReconDroneEntity;
import dev.watchcraft.watchcraft.server.DroneDimensionGuard;
import dev.watchcraft.watchcraft.server.DroneSettings;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

public final class ModCommands {

    private ModCommands() {
    }

    public static void register(RegisterCommandsEvent event) {
        event.getDispatcher().register(
                Commands.literal("watchcraft")
                        .then(Commands.literal("doorinteract")
                                .executes(context -> report(context.getSource()))
                                .then(Commands.argument("enabled", BoolArgumentType.bool())
                                        .executes(context -> apply(context.getSource(),
                                                BoolArgumentType.getBool(context, "enabled")))))
                        .then(Commands.literal("dimension")
                                .executes(context -> diagnose(context.getSource()))));
    }

    /**
     * 诊断：把"玩家在哪个维度、无人机在哪个维度、换维度检测有没有生效"一次读出来。
     *
     * <p>这是需求里"先验证玩家有没有进新维度"的落点。自动回收是一个副作用——它做完之后
     * 无人机就没了，看不出到底是因为检测生效而回收，还是因为别的原因消失。这个命令把
     * 检测本身（记录里的旧维度 vs 当前维度）与处置结果分开呈现，让"检测到了"这件事
     * 可以单独被确认。
     */
    private static int diagnose(CommandSourceStack source) {
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            source.sendFailure(Component.literal("只有玩家能执行这条命令"));
            return 0;
        }
        var current = player.level().dimension().location();
        var recorded = DroneDimensionGuard.lastDimension(player);
        ReconDroneEntity drone = ReconDroneEntity.findDeployed(player);

        source.sendSuccess(() -> Component.literal(
                "当前维度：" + current), false);
        source.sendSuccess(() -> Component.literal(
                "上次记录维度：" + (recorded == null ? "（还没记录，本会话首次 tick）" : recorded.location())), false);
        source.sendSuccess(() -> Component.literal(
                "换维度检测：" + (recorded == null
                        ? "未初始化"
                        : recorded.equals(player.level().dimension()) ? "维度未变" : "检测到变化")), false);
        if (drone == null) {
            source.sendSuccess(() -> Component.literal("无人机：不在外（或刚被自动收回）"), false);
        } else {
            source.sendSuccess(() -> Component.literal(
                    "无人机所在维度：" + DroneDimensionGuard.dimensionName(drone)
                            + (DroneDimensionGuard.droneStranded(player) ? "  ← 滞留，异常" : "  （与玩家同维度）")), false);
        }
        return 1;
    }

    private static int report(CommandSourceStack source) {
        boolean enabled = DroneSettings.get(source.getServer()).isDoorInteraction();
        source.sendSuccess(() -> Component.translatable(messageKey(enabled)), false);
        return enabled ? 1 : 0;
    }

    private static int apply(CommandSourceStack source, boolean enabled) {
        DroneSettings.get(source.getServer()).setDoorInteraction(enabled);
        source.sendSuccess(() -> Component.translatable(messageKey(enabled)), true);
        return enabled ? 1 : 0;
    }

    private static String messageKey(boolean enabled) {
        return enabled
                ? "command.watchcraft.doorinteract.enabled"
                : "command.watchcraft.doorinteract.disabled";
    }
}
