package dev.watchcraft.watchcraft.network;

import dev.watchcraft.watchcraft.Watchcraft;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** Client -> server: the pilot's authoritative position, look direction and barrel roll for this tick. */
public record DroneMovePayload(int droneId, double x, double y, double z, float yRot, float xRot, float roll)
        implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<DroneMovePayload> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(Watchcraft.MOD_ID, "drone_move"));

    /**
     * Written out by hand rather than with {@code StreamCodec.composite}.
     *
     * <p>{@code composite} stops at six pairs, and the roll is the seventh field on this packet.
     * Sending it separately would cost a whole extra payload type - a registration, a handler and a
     * class - for four bytes that belong to exactly the same tick as the rest of this message, so
     * the codec is spelled out instead. The field order is the wire format, and it matches the
     * record's own parameter order.
     */
    public static final StreamCodec<ByteBuf, DroneMovePayload> STREAM_CODEC = StreamCodec.of(
            (buffer, payload) -> {
                ByteBufCodecs.VAR_INT.encode(buffer, payload.droneId());
                ByteBufCodecs.DOUBLE.encode(buffer, payload.x());
                ByteBufCodecs.DOUBLE.encode(buffer, payload.y());
                ByteBufCodecs.DOUBLE.encode(buffer, payload.z());
                ByteBufCodecs.FLOAT.encode(buffer, payload.yRot());
                ByteBufCodecs.FLOAT.encode(buffer, payload.xRot());
                ByteBufCodecs.FLOAT.encode(buffer, payload.roll());
            },
            buffer -> new DroneMovePayload(
                    ByteBufCodecs.VAR_INT.decode(buffer),
                    ByteBufCodecs.DOUBLE.decode(buffer),
                    ByteBufCodecs.DOUBLE.decode(buffer),
                    ByteBufCodecs.DOUBLE.decode(buffer),
                    ByteBufCodecs.FLOAT.decode(buffer),
                    ByteBufCodecs.FLOAT.decode(buffer),
                    ByteBufCodecs.FLOAT.decode(buffer)));

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
