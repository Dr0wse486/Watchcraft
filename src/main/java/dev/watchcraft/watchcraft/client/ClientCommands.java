package dev.watchcraft.watchcraft.client;

import com.mojang.brigadier.arguments.BoolArgumentType;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;

/**
 * 只在客户端注册的调试/手感开关命令。
 *
 * <p>放在客户端而不是 {@code ModCommands} 里，是因为这些开关管的是<em>这一个玩家自己的画面</em>：
 * 镜头摇晃、径向模糊这类效果本来就只有开着无人机的人自己看得见，把它做成服务端命令会变成
 * 「管理员替所有人决定他的屏幕晃不晃」，既多了一层权限，也和效果本身的可见范围对不上。
 * 客户端命令的另一个好处是单人世界直接生效，不需要开作弊。
 */
public final class ClientCommands {

    private ClientCommands() {
    }

    public static void register(RegisterClientCommandsEvent event) {
        event.getDispatcher().register(
                Commands.literal("watchcraft")
                        .then(Commands.literal("shake")
                                .executes(context -> report(context.getSource()))
                                .then(Commands.argument("enabled", BoolArgumentType.bool())
                                        .executes(context -> apply(context.getSource(),
                                                BoolArgumentType.getBool(context, "enabled"))))));
    }

    private static int report(net.minecraft.commands.CommandSourceStack source) {
        boolean enabled = DroneShake.isShakeEnabled();
        source.sendSuccess(() -> Component.translatable(messageKey(enabled)), false);
        return enabled ? 1 : 0;
    }

    private static int apply(net.minecraft.commands.CommandSourceStack source, boolean enabled) {
        DroneShake.setShakeEnabled(enabled);
        source.sendSuccess(() -> Component.translatable(messageKey(enabled)), false);
        return enabled ? 1 : 0;
    }

    private static String messageKey(boolean enabled) {
        return enabled
                ? "command.watchcraft.shake.enabled"
                : "command.watchcraft.shake.disabled";
    }
}
