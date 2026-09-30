package dev.watchcraft.watchcraft.network;

import dev.watchcraft.watchcraft.Watchcraft;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** Client -> server: the pilot's authoritative position and look direction for this tick. */
public record DroneMovePayload(int droneId, double x, double y, double z, float yRot, float xRot)
        implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<DroneMovePayload> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(Watchcraft.MOD_ID, "drone_move"));

    public static final StreamCodec<ByteBuf, DroneMovePayload> STREAM_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.VAR_INT, DroneMovePayload::droneId,
                    ByteBufCodecs.DOUBLE, DroneMovePayload::x,
                    ByteBufCodecs.DOUBLE, DroneMovePayload::y,
                    ByteBufCodecs.DOUBLE, DroneMovePayload::z,
                    ByteBufCodecs.FLOAT, DroneMovePayload::yRot,
                    ByteBufCodecs.FLOAT, DroneMovePayload::xRot,
                    DroneMovePayload::new);

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
