package dev.watchcraft.watchcraft.server;

import dev.watchcraft.watchcraft.entity.ReconDroneEntity;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 玩家换维度时，把还在外面的无人机自动收回来。
 *
 * <p>为什么必须有这一条：无人机是<b>单个维度的实体</b>。它活在放出它的那个 {@code ServerLevel}
 * 里，玩家穿传送门走了之后，机体留在原地——而且随着原维度不再有玩家、区块不再 tick，
 * 它就那么悬在一片不加载的区块里。玩家回来时它理论上还在，但整段链路（镜头、HUD、操控）
 * 早就断了，体验上就是"无人机消失了"；更糟的是它还可能落在卸载区块里既回收不了也找不到。
 * 与其让玩家面对一架谁也够不着的幽灵机，不如在换维度的那一刻直接把它收回背包——
 * 装了什么模块原样带回来，和按 R 回收是一模一样的动作。
 *
 * <h2>怎么"先验证"</h2>
 *
 * <p>换维度不是一个可以直接订阅的"玩家事件"：NeoForge 的 {@code EntityTravelToDimensionEvent}
 * 在传送发生的<em>前</em>一步触发，那时候玩家还在旧维度，读了也判断不出"已经换过去了"。
 * 所以这里走<b>轮询比对</b>：每个玩家记一份上一次见到的维度键，每逢玩家 tick 比一次当前键，
 * 不一样就是这一瞬换过去了。这比监听事件更稳——传送、末地传送门、{@code /execute in}、
 * 甚至别的模组搬人都走同一条路，因为维度键是玩家自身的状态，不是某条代码路径的产物。
 *
 * <p>{@code ConcurrentHashMap} 而不是普通 map：玩家 tick 在服务端主线程跑，但登出清理
 * 可能从别的地方进来，用并发容器省掉一类"只在极少数时序下才复现"的麻烦。
 */
public final class DroneDimensionGuard {

    private DroneDimensionGuard() {
    }

    /** 玩家 UUID -> 上一次见到的维度键。登出时清掉，免得长期开服把地图撑大。 */
    private static final Map<UUID, ResourceKey<Level>> LAST_DIMENSION = new ConcurrentHashMap<>();

    /**
     * 每个玩家每 tick 调一次。
     *
     * <p>三步：读出记录、比对当前维度、不一样就收回并覆盖记录。<b>记录先写、再收回</b>，
     * 顺序不能反：{@code recallTo} 会 {@code discard} 机体、可能触发别的 tick 逻辑，
     * 万一里面出事抛出异常，记录已经落定，下一 tick 不会再重复触发一次回收。
     */
    public static void tick(ServerPlayer player) {
        ResourceKey<Level> current = player.level().dimension();
        ResourceKey<Level> previous = LAST_DIMENSION.put(player.getUUID(), current);
        if (previous == null || previous.equals(current)) {
            return;
        }
        recallOnDimensionChange(player, previous, current);
    }

    /** 玩家登出时清掉记录，否则同一 UUID 重新进来会带着上一世的旧维度键。 */
    public static void forget(UUID playerId) {
        LAST_DIMENSION.remove(playerId);
    }

    private static void recallOnDimensionChange(ServerPlayer player,
                                                ResourceKey<Level> from,
                                                ResourceKey<Level> to) {
        ReconDroneEntity drone = ReconDroneEntity.findDeployed(player);
        if (drone == null) {
            return;
        }
        // 机体还停在旧维度——这正是要收它的那一架。若它已经在玩家脚下（比如同一维度的
        // 传送不该走到这里），findDeployed 也找得到，一并收回，行为是幂等的。
        drone.recallTo(player);
        player.displayClientMessage(Component.translatable("message.watchcraft.recalled_dimension"), true);
    }

    /**
     * {@return 被记录的无人机当前有没有停在别的维度}
     *
     * <p>给 {@code /watchcraft} 那类诊断命令用，让"玩家到底换没换过去、机体是不是真的留在
     * 了旧维度"这件事可以被直接读出来，而不是只能靠回收来猜。
     */
    public static boolean droneStranded(ServerPlayer player) {
        ReconDroneEntity drone = ReconDroneEntity.findDeployed(player);
        if (drone == null) {
            return false;
        }
        return !drone.level().dimension().equals(player.level().dimension());
    }

    /** {@return 上一次见到的维度键，null 表示还没记录过（本会话第一次 tick）} */
    public static ResourceKey<Level> lastDimension(ServerPlayer player) {
        return LAST_DIMENSION.get(player.getUUID());
    }

    /** 诊断用：把某个实体所在维度的名字取出来，日志里好读。 */
    public static String dimensionName(Entity entity) {
        return entity.level().dimension().location().toString();
    }
}
