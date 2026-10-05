package dev.watchcraft.watchcraft.network;

import dev.watchcraft.watchcraft.Watchcraft;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** Client -> server: "link in", "link out", "throw a drone", "recall", "interact", "attack run" or "detonate". */
public record DroneActionPayload(int action, int droneId) implements CustomPacketPayload {

    public static final int ACTION_ENTER = 0;
    public static final int ACTION_EXIT = 1;
    public static final int ACTION_THROW = 2;
    public static final int ACTION_RECALL = 3;
    public static final int ACTION_INTERACT = 4;
    /** Commit the drone to a straight attack run. Only accepted from a drone fitted with a warhead. */
    public static final int ACTION_CHARGE = 5;
    /** Blow the warhead on the spot, without a run. Same module gate as {@link #ACTION_CHARGE}. */
    public static final int ACTION_DETONATE = 6;
    /**
     * 切换跟随。
     *
     * <p>不带无人机 id —— 和收回一样，服务端自己找放飞者名下的那一架。客户端并不知道
     * 自己有没有在外飞的无人机，让它指定 id 只会多一个可能出错的地方。
     */
    public static final int ACTION_FOLLOW = 7;

    public static final CustomPacketPayload.Type<DroneActionPayload> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(Watchcraft.MOD_ID, "drone_action"));

    public static final StreamCodec<ByteBuf, DroneActionPayload> STREAM_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.VAR_INT, DroneActionPayload::action,
                    ByteBufCodecs.VAR_INT, DroneActionPayload::droneId,
                    DroneActionPayload::new);

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
