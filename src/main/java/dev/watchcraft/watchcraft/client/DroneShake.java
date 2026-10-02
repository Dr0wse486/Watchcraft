package dev.watchcraft.watchcraft.client;

import dev.watchcraft.watchcraft.entity.ReconDroneEntity;
import dev.watchcraft.watchcraft.registry.ModSounds;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

/**
 * 引爆瞬间砸在驾驶员身上的那一下。
 *
 * <p>在此之前，爆炸对驾驶员来说只有一件事发生：画面被雪花糊住。问题在于雪花屏是
 * <em>之后</em> 的事 - 冲击波早就过去了，眼睛却只是看着屏幕慢慢变白，中间那一下"炸"
 * 完全缺席。这个类补的就是那 0.15 秒：镜头被掀一下、世界被从中心拽出去、耳朵嗡起来。
 *
 * <h2>三件事，一条时间轴</h2>
 *
 * <p>全部由 {@link #startNanos} 一个起点推出来，所以它们不可能各自跑偏：
 *
 * <ul>
 *   <li><b>位移。</b>相机是挂在无人机上的，所以位移就是直接写无人机的坐标 - 单向的一记
 *       后仰加上三个轴错相的往复抖动，包络按 {@code (1-p)²} 收敛，p 到 1 时恰好回到原位。
 *       写位置时必须连 {@code xo/yo/zo} 一起写死：相机取的是 {@code lerp(partialTick, xo, x)}
 *       的插值，只写 {@code x} 的话镜头会被上一刻的旧值往回拽，抖起来是糊的。</li>
 *   <li><b>俯仰抖动。</b>走 {@code ViewportEvent.ComputeCameraAngles}，不动实体状态，
 *       所以它和飞行时的转向、桶滚走的是同一条路，不会互相打架也不会泄漏到没在开无人机的时候。
 *       俯仰是主项，偏航和滚转按更小的比例跟上。</li>
 *   <li><b>径向模糊。</b>一个 0..1 的强度交给 {@link DroneSignal} 的后处理链，
 *       画面沿中心射线被拽出去，越靠边越狠。中心就是屏幕中心，因为爆炸点就在镜头脚下。</li>
 * </ul>
 *
 * <p>另外还有一件不在时间轴上的：耳鸣。它按 {@code tinnitusDelayMs} 单独计时，
 * 80 毫秒之后才响 - 爆炸声是立刻到的，耳鸣是后一拍才浮上来的，这个错位才是耳朵被震过的样子。
 * 走的是 {@code SimpleSoundInstance.forUI}，即非定位、不衰减：它在脑子里响，不该跟着世界转。
 *
 * <p>整套效果只在客户端跑，且只在镜头真的挂在无人机上时生效，所以对旁观者和其他玩家零影响。
 */
public final class DroneShake {

    // ------------------------------------------------------------------ 配置镜像

    /**
     * 镜头摇晃总开关。
     *
     * <p>关掉之后位移与三轴抖动都不再产生（{@link #frame()} 不挪位置、三个 {@code *Offset()}
     * 恒返回 0、{@link #overlayRamp()} 直接给 1）。径向模糊与耳鸣是另外两条独立的效果，
     * 不受这个开关管：它们不是"晃"，关摇晃的人没道理连耳鸣一起丢。
     */
    private static boolean SHAKE_ENABLED = true;
    /** 冲击持续秒数。0 为关掉整套镜头效果。 */
    private static double SHAKE_SECONDS = 0.15D;
    /** 相机被推开的最大距离，格。 */
    private static double SHAKE_DISPLACEMENT = 0.22D;
    /** 俯仰抖动的最大角度，度。偏航与滚转按比例取更小的值。 */
    private static double SHAKE_ANGLE = 5.0D;
    /** 径向模糊强度倍率。 */
    private static double RADIAL_BLUR = 1.0D;
    /** 耳鸣相对引爆的延迟，毫秒。 */
    private static long TINNITUS_DELAY_MS = 80L;
    private static float TINNITUS_VOLUME = 0.9F;
    private static float TINNITUS_PITCH = 1.0F;

    /**
     * 往复抖动的频率，赫兹。
     *
     * <p>二十一赫兹是挑出来的：再低读起来像镜头在慢慢摇，再高在 60 帧下就会开始采样不足
     * 而变成一闪一闪的噪声。这个数落在"看得出是震"和"不会闪"之间。
     */
    private static final double SHAKE_HZ = 21.0D;

    /**
     * 客户端配置载入/热重载后，把冲击参数写回镜像。
     *
     * <p>和 {@link DroneController#applyConfig()} 同一套路数：{@code ConfigValue#get()}
     * 在配置载入前会抛异常，所以取值集中在 {@code ModConfigEvent} 之后做一次。
     */
    public static void applyConfig() {
        var client = dev.watchcraft.watchcraft.config.WatchcraftConfig.CLIENT;
        SHAKE_ENABLED = client.detonationShakeEnabled.get();
        SHAKE_SECONDS = client.detonationShakeSeconds.get();
        SHAKE_DISPLACEMENT = client.detonationShakeDisplacement.get();
        SHAKE_ANGLE = client.detonationShakeAngle.get();
        RADIAL_BLUR = client.detonationRadialBlur.get();
        TINNITUS_DELAY_MS = client.detonationTinnitusDelayMs.get();
        TINNITUS_VOLUME = client.detonationTinnitusVolume.get().floatValue();
        TINNITUS_PITCH = client.detonationTinnitusPitch.get().floatValue();
    }

    // ------------------------------------------------------------------ 状态

    /** 是否正盯着一次引爆。 */
    private static boolean watching;
    /** 引爆时刻，纳秒。整条时间轴都从这里推。 */
    private static long startNanos;
    /** 被盯着的那架无人机，用来确认镜头没换人。 */
    private static int droneId = -1;
    /** 无人机冻结时的原始坐标，抖完要还回去。 */
    private static double baseX;
    private static double baseY;
    private static double baseZ;
    /** 是否已经挪过位置，决定要不要还。 */
    private static boolean displaced;
    /** 耳鸣是否已经响过，保证一次引爆只响一声。 */
    private static boolean rang;

    private DroneShake() {
    }

    // ------------------------------------------------------------------ 生命周期

    /**
     * 每客户端刻跑一次，负责发现引爆。
     *
     * <p>判据是无人机同步过来的 {@code DATA_STATIC_TICKS}：它从 0 变成正数的那一帧，
     * 就是服务端把爆炸打出去的时刻。用同步值而不是自己记标志，是因为这一下必须由
     * 真正引发爆炸的那一侧来定义 - 无论是撞上去的、超时的还是按 X 手动引爆的，
     * 走的都是同一条路。
     */
    public static void tick() {
        Minecraft minecraft = Minecraft.getInstance();
        Entity camera = minecraft.getCameraEntity();
        ReconDroneEntity drone = camera instanceof ReconDroneEntity candidate && candidate.isDetonated()
                ? candidate
                : null;
        if (drone == null) {
            clear();
            return;
        }
        if (!watching || drone.getId() != droneId) {
            begin(drone);
        }
    }

    private static void begin(ReconDroneEntity drone) {
        watching = true;
        startNanos = System.nanoTime();
        droneId = drone.getId();
        baseX = drone.getX();
        baseY = drone.getY();
        baseZ = drone.getZ();
        displaced = false;
        rang = false;
    }

    private static void clear() {
        if (watching && displaced) {
            Minecraft minecraft = Minecraft.getInstance();
            if (minecraft.level != null
                    && minecraft.level.getEntity(droneId) instanceof ReconDroneEntity drone) {
                place(drone, baseX, baseY, baseZ);
            }
        }
        watching = false;
        displaced = false;
        rang = false;
        droneId = -1;
    }

    // ------------------------------------------------------------------ 每帧

    /**
     * 每帧跑一次，在 {@code RenderFrameEvent.Pre} 里、{@link DroneController#syncLook()} 之后。
     *
     * <p>必须排在转向之后：转向每帧都会把无人机的朝向重写一遍，顺序反过来这一帧的抖动
     * 就会被它覆盖掉。位置倒是没人争，但放在一起读着更清楚。
     */
    public static void frame() {
        if (!watching) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (!(minecraft.getCameraEntity() instanceof ReconDroneEntity drone)
                || drone.getId() != droneId) {
            clear();
            return;
        }

        double elapsed = elapsedSeconds();
        if (shakeActive(elapsed) && elapsed < SHAKE_SECONDS) {
            applyShake(drone, elapsed);
        } else if (displaced) {
            // 包络到 1 时位移本来就归零，这一步只是把浮点尾巴收干净。
            // 摇晃被关掉时也会走到这里，把上一次可能留下的偏移归位。
            place(drone, baseX, baseY, baseZ);
            displaced = false;
        }

        // 耳鸣独立计时：冲击只有 0.15 秒，但耳朵那一下要按配置的延迟单独到。
        if (!rang && elapsedMillis() >= TINNITUS_DELAY_MS) {
            rang = true;
            playTinnitus();
        }
    }

    /**
     * 把这一刻的位移写到无人机身上。
     *
     * <p>相机的世界坐标是 {@code lerp(partialTick, xo, x)}，两个字段都写死成同一个值，
     * 镜头才会精确地停在抖动位置上而不是被插值糊回上一刻。
     */
    private static void applyShake(ReconDroneEntity drone, double elapsed) {
        if (SHAKE_DISPLACEMENT <= 0.0D) {
            return;
        }
        Vec3 offset = offset(drone, elapsed);
        place(drone, baseX + offset.x, baseY + offset.y, baseZ + offset.z);
        displaced = true;
    }

    private static void place(ReconDroneEntity drone, double x, double y, double z) {
        drone.setPos(x, y, z);
        drone.xo = x;
        drone.yo = y;
        drone.zo = z;
    }

    /**
     * {@return 这一刻相机应该被推开多少格}
     *
     * <p>分两层。一层是单向的：被炸得往后仰、往上掀，这一下才有"被推"的方向感，
     * 只靠来回抖是读不出力气的。另一层是叠在上面的往复振动，三个轴各自错开频率和相位 -
     * 频率一样、相位一样的话整台机器会像单摆一样整齐地晃，那看起来像镜头坏了而不是被炸了。
     *
     * <p>方向按机头朝向算，不是世界轴：无人机朝哪边，冲击就往哪边推，翻过来对着天空飞
     * 也不会变成横向乱晃。
     */
    private static Vec3 offset(ReconDroneEntity drone, double elapsed) {
        float yawRad = drone.getYRot() * Mth.DEG_TO_RAD;
        // 和 DroneController#fly 用同一套约定：yaw 的水平前向是 (-sin, cos)，左向是 (cos, sin)。
        Vec3 forward = new Vec3(-Mth.sin(yawRad), 0.0D, Mth.cos(yawRad));
        Vec3 left = new Vec3(Mth.cos(yawRad), 0.0D, Mth.sin(yawRad));

        double envelope = envelope(elapsed);
        double kick = SHAKE_DISPLACEMENT * envelope;
        double w = 2.0D * Math.PI * SHAKE_HZ;

        Vec3 push = forward.scale(-kick * 0.75D).add(0.0D, kick * 0.55D, 0.0D);
        double sideways = kick * 0.60D * Math.sin(w * elapsed);
        double vertical = kick * 0.50D * Math.sin(w * elapsed * 1.21D + 1.9D);
        double along = kick * 0.35D * Math.sin(w * elapsed * 0.83D + 3.4D);

        return push.add(left.scale(sideways)).add(0.0D, vertical, 0.0D).add(forward.scale(along));
    }

    // ------------------------------------------------------------------ 角度

    /**
     * {@return 这一刻相机俯仰应该叠加上去的角度，与 {@code Camera#getPitch()} 同号：正为低头}
     *
     * <p>读的是自己的时钟，不是相机的 - 相机角度每帧都会被转向和桶滚重写，
     * 从里面反推抖动量只会把两件事缠在一起。
     */
    public static float pitchOffset() {
        double elapsed = elapsedSeconds();
        if (!shakeActive(elapsed)) {
            return 0.0F;
        }
        double envelope = envelope(elapsed);
        double w = 2.0D * Math.PI * SHAKE_HZ;
        // 单向的那一记是抬头，所以取负：冲击从脚下来，头先被掀上去，再在振铃里来回摆。
        double kick = -SHAKE_ANGLE * 0.9D * envelope;
        double ring = SHAKE_ANGLE * 0.55D * envelope * Math.sin(w * elapsed * 1.07D + 0.6D);
        return (float) (kick + ring);
    }

    /** {@return 这一刻偏航应该抖多少度} */
    public static float yawOffset() {
        double elapsed = elapsedSeconds();
        if (!shakeActive(elapsed)) {
            return 0.0F;
        }
        double w = 2.0D * Math.PI * SHAKE_HZ;
        return (float) (SHAKE_ANGLE * 0.50D * envelope(elapsed) * Math.sin(w * elapsed * 0.93D + 2.4D));
    }

    /** {@return 这一刻滚转应该抖多少度} */
    public static float rollOffset() {
        double elapsed = elapsedSeconds();
        if (!shakeActive(elapsed)) {
            return 0.0F;
        }
        double w = 2.0D * Math.PI * SHAKE_HZ;
        return (float) (SHAKE_ANGLE * 0.65D * envelope(elapsed) * Math.sin(w * elapsed * 0.77D + 4.2D));
    }

    // ------------------------------------------------------------------ 画面

    /**
     * {@return 交给径向模糊后处理链的强度，0 为不动}
     *
     * <p>链子里跑两遍，第一遍给足、第二遍减半，这样拖影是连续的而不是一层硬边。
     * 真正的采样数在 {@code radial_blur.fsh} 里，这里只管强度。
     */
    public static float radialBlur() {
        double elapsed = elapsedSeconds();
        if (!active(elapsed) || RADIAL_BLUR <= 0.0D) {
            return 0.0F;
        }
        return (float) Math.min(1.0D, envelope(elapsed) * RADIAL_BLUR);
    }

    /** {@return 第二遍径向模糊的强度，是第一遍的一半} */
    public static float radialBlurSecondPass() {
        return radialBlur() * 0.5F;
    }

    /**
     * {@return 雪花屏底色的不透明度，0 到 1}
     *
     * <p>这一条是把前面所有效果从"看不见"里救出来的关键。原来的雪花屏第一刻就是全屏不透明的，
     * 于是镜头被掀、画面被拽这两件事发生时，屏幕已经是一堵黑墙，谁也看不见。
     * 改成在冲击窗口里从三成慢慢糊到全黑：先透出被冲歪、被拽花的世界，再让信号彻底断掉。
     * 冲击关掉时（{@code shakeSeconds = 0}）直接返回 1，行为和从前完全一致。
     */
    public static double overlayRamp() {
        // 摇晃关掉之后就没有东西可透出来了，直接给全不透明，行为和 shakeSeconds = 0 时一致。
        if (!watching || !SHAKE_ENABLED || SHAKE_SECONDS <= 0.0D) {
            return 1.0D;
        }
        double p = Mth.clamp(elapsedSeconds() / SHAKE_SECONDS, 0.0D, 1.0D);
        // smoothstep：起手慢、收尾慢，中间快，糊上去的过程才不像在拉一个滑杆。
        return p * p * (3.0D - 2.0D * p);
    }

    // ------------------------------------------------------------------ 声音

    /**
     * 耳鸣。
     *
     * <p>{@code forUI} 造出来的是非定位、不衰减、相对听者的一份实例，音量只受主音量滑块管。
     * 这正是"在脑子里响"该有的样子：跟着世界坐标走的话，镜头一偏耳鸣就会跑到一边去，
     * 而它明明来自耳朵里面。
     */
    private static void playTinnitus() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null || minecraft.player == null || TINNITUS_VOLUME <= 0.0F) {
            return;
        }
        minecraft.getSoundManager().play(
                SimpleSoundInstance.forUI(ModSounds.TINNITUS.get(), TINNITUS_PITCH, TINNITUS_VOLUME));
    }

    // ------------------------------------------------------------------ 时间轴

    private static boolean active(double elapsed) {
        return watching && elapsed < SHAKE_SECONDS;
    }

    /**
     * {@return 摇晃这一路是否该发声发力}
     *
     * <p>和 {@link #active} 分开是因为 {@link #radialBlur()} 只认后者：摇晃开关关掉的只是
     * 位移与三轴抖动，径向模糊不在它的管辖里，拿这个去卡会把模糊一起关掉。
     */
    private static boolean shakeActive(double elapsed) {
        return active(elapsed) && SHAKE_ENABLED;
    }

    /** {@return 当前摇晃开关状态（运行时镜像，不是配置里的值）} */
    public static boolean isShakeEnabled() {
        return SHAKE_ENABLED;
    }

    /**
     * 运行时直接改摇晃开关，并写回客户端配置。
     *
     * <p>先写配置再改镜像：{@code setValue} 会把 {@code ModConfigEvent.Reloading} 打回给
     * {@link #applyConfig()}，正常情况下镜像会被那边刷成同一个值。这里仍然显式赋一次，
     * 是因为重载事件是异步投递的，下一帧之前镜像得先用上新值，否则按键那一下会晚一个 tick 才生效。
     */
    public static void setShakeEnabled(boolean enabled) {
        SHAKE_ENABLED = enabled;
        dev.watchcraft.watchcraft.config.WatchcraftConfig.CLIENT.detonationShakeEnabled.set(enabled);
    }

    /** {@return 包络，1 在引爆那一刻、0 在窗口结束时} */
    private static double envelope(double elapsed) {
        double p = Mth.clamp(elapsed / SHAKE_SECONDS, 0.0D, 1.0D);
        double remaining = 1.0D - p;
        return remaining * remaining;
    }

    private static double elapsedSeconds() {
        return elapsedMillis() / 1000.0D;
    }

    private static long elapsedMillis() {
        return watching ? (System.nanoTime() - startNanos) / 1_000_000L : Long.MAX_VALUE;
    }
}
