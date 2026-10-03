package dev.watchcraft.watchcraft.client;

import dev.watchcraft.watchcraft.entity.DroneControlState;
import dev.watchcraft.watchcraft.entity.ReconDroneEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;

import java.util.HashMap;
import java.util.Map;

/**
 * 给世界里每一架无人机挂一份旋翼声。
 *
 * <p>为什么要在这里扫实体，而不是让 {@code ReconDroneEntity} 自己起声音：那个类是双端的，
 * 引用 {@link DroneMotorSound} 这种纯客户端类会让专用服务器在加载期就炸掉。所以由客户端
 * 这一侧反过来找它。
 *
 * <p>扫的是 {@code entitiesForRendering}，而且每 {@value #SCAN_INTERVAL} 刻才扫一次：声音一旦
 * 交给 {@code SoundManager} 就由它每刻推进，这里只负责"让每架无人机恰好有一份"。用不着每刻
 * 遍历整张实体表，晚半秒起声也听不出来。
 *
 * <p>停止不需要管：{@link DroneMotorSound#tick()} 发现机体没了会自己收尾，这里顺手把已经
 * 停掉的条目从表里摘掉，好让下一架用同一个 id 的无人机能重新起声。
 */
public final class DroneSounds {

    /** 两次扫描之间隔多少刻。 */
    private static final int SCAN_INTERVAL = 10;

    private static final Map<Integer, DroneMotorSound> MOTORS = new HashMap<>();

    private static int countdown;

    private DroneSounds() {
    }

    /** 每客户端刻调用一次。 */
    public static void tick() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null) {
            MOTORS.clear();
            countdown = 0;
            return;
        }
        if (--countdown > 0) {
            return;
        }
        countdown = SCAN_INTERVAL;

        // isStopped() 声明在 TickableSoundInstance 上，不在 SoundInstance 上 —— 所以这里要写
        // 具体的实现类，不能写接口。
        MOTORS.values().removeIf(DroneMotorSound::isStopped);

        for (Entity entity : minecraft.level.entitiesForRendering()) {
            if (!(entity instanceof ReconDroneEntity drone)) {
                continue;
            }
            boolean cockpit = drone.getId() == DroneControlState.pilotedDroneId;
            DroneMotorSound existing = MOTORS.get(drone.getId());
            if (existing != null && existing.isCockpit() != cockpit) {
                // 接入或断开驾驶会换模式，而 attenuation 与 relative 在 SoundEngine#play 里
                // 只读一次、之后改不动 —— 只能让旧的那份收尾再起一份新的。
                existing.retire();
                MOTORS.remove(drone.getId());
                existing = null;
            }
            if (existing == null) {
                DroneMotorSound sound = new DroneMotorSound(drone, cockpit);
                MOTORS.put(drone.getId(), sound);
                minecraft.getSoundManager().play(sound);
            }
        }
    }

    /**
     * 让下一次客户端刻立刻重扫，而不是等轮询到点。
     *
     * <p>接入与断开驾驶会改变模式（驾驶舱音 ⇄ 定位音），而换模式要重建实例。等最多
     * {@value #SCAN_INTERVAL} 刻的话，那段时间里驾驶舱音还是定位音，正是左右横跳最明显的时候。
     */
    public static void refresh() {
        countdown = 0;
    }
}
