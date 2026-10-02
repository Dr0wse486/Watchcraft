package dev.watchcraft.watchcraft.server;

import dev.watchcraft.watchcraft.Watchcraft;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;

/**
 * 服务端事件：玩家的维度一变，就把外面的无人机收回来。
 *
 * <p>挂 {@code PlayerTickEvent.Post} 而不是 {@code EntityTravelToDimensionEvent}：
 * 后者在传送发生前触发，那时玩家还在旧维度，拿它判断不了"已经换过去了"。详见
 * {@link DroneDimensionGuard} 的类注释。
 *
 * <p>这个类进的是游戏事件总线（{@link EventBusSubscriber} 默认就是），不是 mod 总线。
 */
@EventBusSubscriber(modid = Watchcraft.MOD_ID)
public final class ServerEvents {

    private ServerEvents() {
    }

    @SubscribeEvent
    public static void onPlayerTick(PlayerTickEvent.Post event) {
        if (event.getEntity() instanceof net.minecraft.server.level.ServerPlayer player) {
            DroneDimensionGuard.tick(player);
        }
    }

    /** 玩家登出：清掉维度记录，免得同一 UUID 再进来时带着上一世的旧键。 */
    @SubscribeEvent
    public static void onPlayerLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof net.minecraft.server.level.ServerPlayer player) {
            DroneDimensionGuard.forget(player.getUUID());
        }
    }
}
