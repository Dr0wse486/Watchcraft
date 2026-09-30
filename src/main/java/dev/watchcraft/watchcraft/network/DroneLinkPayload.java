package dev.watchcraft.watchcraft.network;

import dev.watchcraft.watchcraft.Watchcraft;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** Server -> client: "you are now (or no longer) linked to drone {@code droneId}". */
public record DroneLinkPayload(int droneId, boolean linked) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<DroneLinkPayload> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(Watchcraft.MOD_ID, "drone_link"));

    public static final StreamCodec<ByteBuf, DroneLinkPayload> STREAM_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.VAR_INT, DroneLinkPayload::droneId,
                    ByteBufCodecs.BOOL, DroneLinkPayload::linked,
                    DroneLinkPayload::new);

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
