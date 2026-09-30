package dev.watchcraft.watchcraft.client;

import dev.watchcraft.watchcraft.entity.ReconDroneEntity;
import dev.watchcraft.watchcraft.network.DroneLinkPayload;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.neoforged.neoforge.network.handling.IPayloadContext;

public final class ClientPayloadHandler {

    private ClientPayloadHandler() {
    }

    public static void handleLink(DroneLinkPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (payload.linked()) {
                DroneController.link(payload.droneId());
                notifyPlayer("message.watchcraft.linked");
            } else {
                boolean wasLinked = DroneController.isLinked();
                DroneController.unlink();
                if (wasLinked) {
                    notifyPlayer("message.watchcraft.unlinked");
                }
            }
        });
    }

    private static void notifyPlayer(String key) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player != null) {
            minecraft.player.displayClientMessage(
                    net.minecraft.network.chat.Component.translatable(key), true);
        }
    }

    /** Drops the local link if the drone we were flying disappears. */
    public static void verifyLink() {
        Minecraft minecraft = Minecraft.getInstance();
        if (!DroneController.isLinked() || minecraft.level == null) {
            return;
        }
        Entity camera = minecraft.getCameraEntity();
        if (!(camera instanceof ReconDroneEntity drone)
                || drone.isRemoved()
                || camera.level() != minecraft.level
                || drone.getId() != DroneController.getLinkedId()) {
            DroneController.unlink();
        }
    }
}
