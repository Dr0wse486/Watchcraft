package dev.watchcraft.watchcraft.network;

import dev.watchcraft.watchcraft.Watchcraft;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * 服务端 -> 客户端：你被一架无人机看到了。
 *
 * <p>收件人是<b>被探测到的那位玩家</b>，不是放飞者 —— 这是模组里唯一一条"对外"的消息。
 * 其余每一条都在向无人机的主人汇报，只有这一条告诉别人"你正在被看着"。
 *
 * <p>只带一个字段：署名者的 UUID。头像是客户端自己按 UUID 去皮肤管理器取的，服务端不需要
 * （也不应该）把皮肤数据塞进包里 —— 那位玩家可能离得很远，他的实体在收件人的客户端上
 * 根本不存在，但玩家列表里有他，UUID 就是够的。
 */
public record DroneAlertPayload(String pilotId) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<DroneAlertPayload> TYPE =
            new CustomPacketPayload.Type<>(
                    ResourceLocation.fromNamespaceAndPath(Watchcraft.MOD_ID, "drone_alert"));

    public static final StreamCodec<ByteBuf, DroneAlertPayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.STRING_UTF8, DroneAlertPayload::pilotId,
            DroneAlertPayload::new);

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
