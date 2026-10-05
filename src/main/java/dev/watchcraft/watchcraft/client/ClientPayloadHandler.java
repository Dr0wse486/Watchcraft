package dev.watchcraft.watchcraft.client;

import dev.watchcraft.watchcraft.entity.ReconDroneEntity;
import dev.watchcraft.watchcraft.network.DroneLinkPayload;
import dev.watchcraft.watchcraft.network.DroneScanPayload;
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
                warnIfNoBattery(payload.droneId());
            } else {
                boolean wasLinked = DroneController.isLinked();
                DroneController.unlink();
                if (wasLinked) {
                    notifyPlayer("message.watchcraft.unlinked");
                }
            }
        });
    }

    /**
     * 侦察快照。
     *
     * <p>不经过链路 —— 这正是它存在的理由。玩家没坐在无人机里的时候，这是客户端唯一能知道
     * 无人机看到了什么的途径。整份快照直接覆盖上一份，没有增量、没有序号、也不需要重传：
     * 丢一条下一轮就补上了。
     */
    public static void handleScan(DroneScanPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> DroneMarkerOverlay.accept(payload));
    }

    /**
     * 接入之后如果这架机体没装电池，直接说清楚该怎么办。
     *
     * <p>这条提示不是锦上添花：没电池和飞太远的画面**长得一模一样**，光看画面猜不出原因，
     * 所以这里是把"没电池"这个状态告诉玩家的唯一出口。
     */
    private static void warnIfNoBattery(int droneId) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level != null
                && minecraft.level.getEntity(droneId) instanceof ReconDroneEntity drone
                && !drone.hasBattery()) {
            notifyPlayer("message.watchcraft.battery_needed");
        }
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
