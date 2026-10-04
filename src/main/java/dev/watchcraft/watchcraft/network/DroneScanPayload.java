package dev.watchcraft.watchcraft.network;

import dev.watchcraft.watchcraft.Watchcraft;
import io.netty.buffer.ByteBuf;
import net.minecraft.core.BlockPos;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.List;

/**
 * 服务端 -> 客户端：无人机这一轮的侦察快照。
 *
 * <p>发给<b>放飞者</b>而不是驾驶员，因为这两个角色已经不是一回事了：无人机可以自己在外面飞，
 * 玩家在别处做自己的事，标记与预警照样要送到玩家眼前。这也是为什么它不走
 * {@code DroneLinkPayload} 那条链路 —— 那条只在"进入/退出驾驶舱"时发一次，未连线时客户端
 * 对无人机一无所知。
 *
 * <p>内容全部是<b>快照</b>，不是增量：箱子列表整体替换，威胁要么有要么没有。所以这条消息
 * 丢一两个也无所谓，下一轮就补上了，不需要重传逻辑。服务端只在内容真的变了时才发，
 * 无人机停着不动时几乎不发包。
 *
 * @param chests        本轮视野内的箱子坐标，已按距离从近到远排序
 * @param threatPercent 威胁强度 0..100，0 表示没有威胁
 * @param threatX       最近威胁的坐标；{@link #threatPercent} 为 0 时无意义
 * @param threatY       同上
 * @param threatZ       同上
 * @param following     无人机此刻是否在跟随放飞者，供屏幕角落那行状态显示
 */
public record DroneScanPayload(List<BlockPos> chests, int threatPercent,
                               int threatX, int threatY, int threatZ,
                               boolean following) implements CustomPacketPayload {

    /** 没有威胁时 {@link #threatPercent} 的取值。 */
    public static final int NO_THREAT = 0;

    public static final CustomPacketPayload.Type<DroneScanPayload> TYPE =
            new CustomPacketPayload.Type<>(
                    ResourceLocation.fromNamespaceAndPath(Watchcraft.MOD_ID, "drone_scan"));

    public static final StreamCodec<ByteBuf, DroneScanPayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.collection(ArrayList::new, BlockPos.STREAM_CODEC), DroneScanPayload::chests,
            ByteBufCodecs.VAR_INT, DroneScanPayload::threatPercent,
            ByteBufCodecs.VAR_INT, DroneScanPayload::threatX,
            ByteBufCodecs.VAR_INT, DroneScanPayload::threatY,
            ByteBufCodecs.VAR_INT, DroneScanPayload::threatZ,
            ByteBufCodecs.BOOL, DroneScanPayload::following,
            DroneScanPayload::new);

    /**
     * 空快照。
     *
     * <p>无人机收回、被炸掉或玩家退出链路时发这一条，客户端据此把屏幕上的标记与预警一起
     * 抹掉。靠"过一会儿自然过期"是不行的 —— 那时候无人机已经不存在了，没有任何东西会再
     * 发一条来纠正它。
     */
    public static DroneScanPayload empty() {
        return new DroneScanPayload(List.of(), NO_THREAT, 0, 0, 0, false);
    }

    public boolean hasThreat() {
        return this.threatPercent > NO_THREAT;
    }

    public BlockPos threatPos() {
        return new BlockPos(this.threatX, this.threatY, this.threatZ);
    }

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
