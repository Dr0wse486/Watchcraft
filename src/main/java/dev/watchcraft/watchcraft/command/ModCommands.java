package dev.watchcraft.watchcraft.command;

import com.mojang.brigadier.arguments.BoolArgumentType;
import dev.watchcraft.watchcraft.server.DroneSettings;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
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
                                                BoolArgumentType.getBool(context, "enabled"))))));
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
