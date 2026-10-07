package dev.watchcraft.watchcraft.client;

import dev.watchcraft.watchcraft.config.WatchcraftConfig;

/**
 * 液晶滤镜的旋钮与时钟。
 *
 * <p>真正干活的是 {@code assets/watchcraft/shaders/program/lcd.fsh}，它挂在
 * {@code post/drone_link.json} 的最末端。这个类只做两件 Java 侧必须做的事：把 CLIENT 配置
 * 搬进静态镜像，以及推进那两个随时间走的 uniform。
 *
 * <h2>为什么要有"时钟"和"相位"两个量</h2>
 *
 * <p>链子自己会把 {@code Time} uniform 递下来，但它是个锯齿波：{@code PostChain} 每帧把
 * partialTicks 累加上去，超过 20 就减 20，所以它一秒一轮回。用它驱动滚动亮带的话，亮带
 * 每秒从顶上重来一次 —— 快，而且接缝在正中间跳。所以滚动带的位置另算：{@code phase} 自己
 * 累加、自己取小数部分，周期是 {@link #BAND_PERIOD_SECONDS} 秒，而且因为送出去的就是
 * 0..1 的小数部分，着色器那边再 {@code fract} 一次也不会在接缝处跳。
 *
 * <p>刷新抖动要的是"秒"，不是"轮次"，所以另有一个只增不减的 {@code clock}。它会回绕，
 * 免得跑上几个小时后 float 的精度掉到看不出抖动。
 *
 * <p>两个量都只在操控无人机时被推进（{@link #advance} 只从
 * {@code DroneSignal#renderLevelStage} 里调），所以断链之后相位就冻在原地，再连上时亮带
 * 不会莫名其妙地跳一大截。
 */
public final class DroneLcd {

    /** 滚动亮带扫过一屏要多少秒。太短像故障，太长看不见。 */
    private static final double BAND_PERIOD_SECONDS = 5.0D;

    /** 时钟回绕周期，3 小时。只为保住 float 的精度。 */
    private static final double CLOCK_WRAP_SECONDS = 10800.0D;

    /** 总开关。关掉时直接把强度送 0，着色器第一件事就是单次采样直通。 */
    private static boolean enabled = true;
    /** 整体轻重，0 到 1。 */
    private static float strength = 1.0F;
    /** 一格像素的边长，单位是设备像素。0 表示跟随分辨率 —— 和配置的默认值一致。 */
    private static float pitch = 0.0F;

    /** 只增不减的秒数，喂给刷新抖动。 */
    private static double clock;
    /** 滚动亮带的位置，恒在 0..1。 */
    private static double phase;

    private DroneLcd() {
    }

    /**
     * 客户端配置载入 / 热重载后把三项写回镜像。
     *
     * <p>和 {@code WatchcraftConfig} 类注释里说的是同一件事：{@code ConfigValue#get()} 在
     * 配置载入之前会抛异常，所以不能在使用处现读。
     */
    public static void applyConfig() {
        WatchcraftConfig.Client client = WatchcraftConfig.CLIENT;
        enabled = client.lcdEnabled.get();
        strength = client.lcdStrength.get().floatValue();
        pitch = client.lcdPixelPitch.get().floatValue();
    }

    /** {@return 这一帧该送的滤镜强度}。关掉时是 0，着色器走直通分支。 */
    public static float strength() {
        return enabled ? strength : 0.0F;
    }

    /** {@return 一格像素的边长，设备像素} */
    public static float pitch() {
        return pitch;
    }

    /** {@return 供刷新抖动用的秒数} */
    public static float time() {
        return (float) clock;
    }

    /**
     * 推进时钟与滚动带，返回这一帧的相位。
     *
     * @param partialTicks 本帧的 partial tick，除以 20 就是秒
     */
    public static float advance(float partialTicks) {
        double seconds = partialTicks / 20.0D;

        clock += seconds;
        if (clock >= CLOCK_WRAP_SECONDS) {
            clock -= CLOCK_WRAP_SECONDS;
        }

        phase += seconds / BAND_PERIOD_SECONDS;
        phase -= Math.floor(phase);
        return (float) phase;
    }

    /**
     * 断开链路时调用：只把相位收回起点。
     *
     * <p>不重置 {@link #clock} —— 它只影响抖动的随机序列，回不回绕都无所谓，而重置它反而会
     * 让每次重新接入的头几帧抖动一模一样。
     */
    public static void reset() {
        phase = 0.0D;
    }
}
