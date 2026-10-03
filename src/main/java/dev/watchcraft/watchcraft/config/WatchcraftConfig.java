package dev.watchcraft.watchcraft.config;

import net.neoforged.neoforge.common.ModConfigSpec;
import org.apache.commons.lang3.tuple.Pair;

/**
 * 模组的全部可调参数。
 *
 * <p>分成两份文件：{@code config/watchcraft-common.toml} 放双端一致的平衡值 - 速度、伤害、
 * 扫描、生命、爆炸；{@code config/watchcraft-client.toml} 只放个人手感与画面 - 加速度、
 * 鼠标灵敏度、桶滚、镜头倾斜、FOV、信号模糊。这样联机时平衡值以服务器为准，而手感不会被
 * 服务器覆盖。
 *
 * <p><b>为什么不直接在常量声明处读配置。</b>{@code ConfigValue#get()} 在配置文件尚未载入时会
 * 直接抛异常，而这些常量散落在静态上下文里。所以取值走的是另一条路：{@code ModConfigEvent}
 * 载入/重载后调用各处的 {@code applyConfig()}，把值写进原有的静态常量镜像。调用点的写法完全
 * 不变，而改完配置文件（游戏内重载或重启）即刻生效。
 */
public final class WatchcraftConfig {

    public static final Common COMMON;
    public static final ModConfigSpec COMMON_SPEC;
    public static final Client CLIENT;
    public static final ModConfigSpec CLIENT_SPEC;

    static {
        Pair<Common, ModConfigSpec> common = new ModConfigSpec.Builder().configure(Common::new);
        COMMON = common.getLeft();
        COMMON_SPEC = common.getRight();

        Pair<Client, ModConfigSpec> client = new ModConfigSpec.Builder().configure(Client::new);
        CLIENT = client.getLeft();
        CLIENT_SPEC = client.getRight();
    }

    private WatchcraftConfig() {
    }

    /** 双端共用的平衡值，以服务器为准。 */
    public static final class Common {

        public final ModConfigSpec.IntValue maxDeployed;
        public final ModConfigSpec.DoubleValue linkRange;
        public final ModConfigSpec.DoubleValue staticOnset;
        public final ModConfigSpec.DoubleValue staticStep;
        public final ModConfigSpec.DoubleValue flightSpeed;
        public final ModConfigSpec.DoubleValue interactRange;
        public final ModConfigSpec.DoubleValue maxStep;
        public final ModConfigSpec.DoubleValue throwSpeed;
        public final ModConfigSpec.IntValue throwTicks;
        public final ModConfigSpec.DoubleValue maxHealth;
        public final ModConfigSpec.IntValue hurtInvulnerableTicks;
        public final ModConfigSpec.IntValue hurtFlashTicks;

        public final ModConfigSpec.DoubleValue scanRange;
        public final ModConfigSpec.DoubleValue scanFov;
        public final ModConfigSpec.DoubleValue throwScanFov;
        public final ModConfigSpec.DoubleValue pingRadius;
        public final ModConfigSpec.DoubleValue throwPingRadius;
        public final ModConfigSpec.IntValue glowDuration;
        public final ModConfigSpec.IntValue scanInterval;
        public final ModConfigSpec.IntValue maxRaycastsPerScan;

        public final ModConfigSpec.DoubleValue bankGain;
        public final ModConfigSpec.DoubleValue maxBank;
        public final ModConfigSpec.DoubleValue bankSmoothing;

        public final ModConfigSpec.DoubleValue chargeSpeed;
        public final ModConfigSpec.IntValue chargeMaxTicks;
        public final ModConfigSpec.DoubleValue chargeCone;
        public final ModConfigSpec.DoubleValue chargeConeSlack;
        public final ModConfigSpec.IntValue chargeCooldownTicks;
        public final ModConfigSpec.DoubleValue chargeExplosionRadius;
        public final ModConfigSpec.DoubleValue chargeCraterRadius;
        public final ModConfigSpec.DoubleValue chargeDamage;
        public final ModConfigSpec.DoubleValue chargeEchoChase;
        public final ModConfigSpec.IntValue chargeStaticTicks;

        Common(ModConfigSpec.Builder builder) {
            builder.comment("无人机本体：速度、链路、投掷与生命").push("drone");
            maxDeployed = builder
                    .comment("每名玩家同时可以放飞的无人机数量")
                    .defineInRange("maxDeployed", 1, 1, 8);
            linkRange = builder
                    .comment("操控者与无人机相距多远时链路断开（格）")
                    .defineInRange("linkRange", 64.0D, 8.0D, 256.0D);
            staticOnset = builder
                    .comment("画面开始出现雪花（信号劣化）的距离（格）")
                    .defineInRange("staticOnset", 48.0D, 0.0D, 256.0D);
            staticStep = builder
                    .comment("每多少格增加一档信号劣化；链路距离与起始距离之间按此分档")
                    .defineInRange("staticStep", 4.0D, 0.1D, 64.0D);
            flightSpeed = builder
                    .comment("操控时的最高速度（格/刻）。0.24 约等于每刻 4.8 m/s")
                    .defineInRange("flightSpeed", 0.24D, 0.01D, 4.0D);
            interactRange = builder
                    .comment("从驾驶舱右键开门时的触及距离（格）")
                    .defineInRange("interactRange", 5.0D, 1.0D, 32.0D);
            maxStep = builder
                    .comment("服务端单次接受的最大位移，用于阻止改包加速（格）")
                    .defineInRange("maxStep", 1.8D, 0.1D, 32.0D);
            throwSpeed = builder
                    .comment("投出无人机的初速度（格/刻）")
                    .defineInRange("throwSpeed", 0.85D, 0.0D, 8.0D);
            throwTicks = builder
                    .comment("投出后保持弹道飞行的刻数，之后转入悬停")
                    .defineInRange("throwTicks", 60, 0, 1200);
            maxHealth = builder
                    .comment("机体生命值，10 点等于 5 颗心")
                    .defineInRange("maxHealth", 10.0D, 1.0D, 2000.0D);
            hurtInvulnerableTicks = builder
                    .comment("受击后的无敌刻数，防止被连续挥砍瞬间打爆")
                    .defineInRange("hurtInvulnerableTicks", 10, 0, 200);
            hurtFlashTicks = builder
                    .comment("受击红闪在客户端持续的刻数")
                    .defineInRange("hurtFlashTicks", 10, 0, 200);
            builder.pop();

            builder.comment("扫描与标记").push("scan");
            scanRange = builder
                    .comment("可被发现的实体距离（格）")
                    .defineInRange("range", 48.0D, 1.0D, 256.0D);
            scanFov = builder
                    .comment("操控状态下扫描锥的全角（度）")
                    .defineInRange("fov", 110.0D, 1.0D, 360.0D);
            throwScanFov = builder
                    .comment("投掷弹道期间扫描锥的全角（度），更宽以补偿翻滚")
                    .defineInRange("throwFov", 150.0D, 1.0D, 360.0D);
            pingRadius = builder
                    .comment("此半径内无视锥角，直接标记（格）")
                    .defineInRange("pingRadius", 8.0D, 0.0D, 64.0D);
            throwPingRadius = builder
                    .comment("投掷期间的同样半径，更宽以保住高速掠过（格）")
                    .defineInRange("throwPingRadius", 16.0D, 0.0D, 64.0D);
            glowDuration = builder
                    .comment("被标记实体保持发光高亮的刻数")
                    .defineInRange("glowDuration", 60, 1, 1200);
            scanInterval = builder
                    .comment("两次扫描之间的刻数，越小越灵敏")
                    .defineInRange("interval", 2, 1, 100);
            maxRaycastsPerScan = builder
                    .comment("每次扫描的视线检测上限，防止人群造成卡顿")
                    .defineInRange("maxRaycastsPerScan", 24, 1, 512);
            builder.pop();

            builder.comment("转弯倾斜（机身与镜头共用）").push("bank");
            bankGain = builder
                    .comment("每秒每刻偏航角变化带来的倾角倍率")
                    .defineInRange("gain", 1.8D, 0.0D, 10.0D);
            maxBank = builder
                    .comment("倾角上限（度）")
                    .defineInRange("maxBank", 30.0D, 0.0D, 90.0D);
            bankSmoothing = builder
                    .comment("倾角追赶目标值的速度，0~1，越大越硬")
                    .defineInRange("smoothing", 0.3D, 0.01D, 1.0D);
            builder.pop();

            builder.comment("冲刺与自爆").push("charge");
            chargeSpeed = builder
                    .comment("冲刺撞击时的速度（格/刻）")
                    .defineInRange("speed", 1.45D, 0.05D, 20.0D);
            chargeMaxTicks = builder
                    .comment("冲刺最长持续刻数，撞不到东西就自行引爆")
                    .defineInRange("maxTicks", 60, 1, 1200);
            chargeCone = builder
                    .comment("驾驶舱内可以偏转冲刺轴的角度（度），客户端用。"
                            + "28 度在一次冲刺里约合 42 格横向位移，够绕开障碍也够贴住移动目标")
                    .defineInRange("cone", 28.0D, 0.0D, 90.0D);
            chargeConeSlack = builder
                    .comment("服务端实际容忍的偏转角度（度），应不小于 cone。"
                            + "留出的余量让诚实客户端不会被夹第二次")
                    .defineInRange("coneSlack", 36.0D, 0.0D, 90.0D);
            chargeCooldownTicks = builder
                    .comment("两次冲刺之间的冷却刻数")
                    .defineInRange("cooldownTicks", 40, 0, 1200);
            chargeExplosionRadius = builder
                    .comment("爆炸半径（格）")
                    .defineInRange("explosionRadius", 3.0D, 0.0D, 16.0D);
            chargeCraterRadius = builder
                    .comment("实际破坏方块的范围（格），比爆炸半径小则以留坑")
                    .defineInRange("craterRadius", 2.0D, 0.0D, 16.0D);
            chargeDamage = builder
                    .comment("爆炸对范围内每个实体的固定伤害，不随距离衰减")
                    .defineInRange("damage", 125.0D, 0.0D, 10000.0D);
            chargeEchoChase = builder
                    .comment("冲刺时客户端追赶服务端位置的每刻比例，0~1")
                    .defineInRange("echoChase", 0.9D, 0.05D, 1.0D);
            chargeStaticTicks = builder
                    .comment("引爆后满屏雪花屏的持续刻数，结束后才切回玩家视角")
                    .defineInRange("staticTicks", 40, 0, 200);
            builder.pop();
        }
    }

    /** 客户端手感与画面，只影响本机。 */
    public static final class Client {

        public final ModConfigSpec.DoubleValue flightAcceleration;
        public final ModConfigSpec.DoubleValue flightDeceleration;

        public final ModConfigSpec.DoubleValue lookGain;
        public final ModConfigSpec.DoubleValue lookSmoothing;

        public final ModConfigSpec.IntValue rollTicks;
        public final ModConfigSpec.IntValue rollCooldownTicks;
        public final ModConfigSpec.IntValue rollerRecoverTicks;
        public final ModConfigSpec.DoubleValue rollCameraShare;
        public final ModConfigSpec.DoubleValue rollerTurnsPerSecond;
        public final ModConfigSpec.DoubleValue rollerDashSpeedGain;
        public final ModConfigSpec.DoubleValue rollerFovGain;

        public final ModConfigSpec.DoubleValue cameraBankShare;

        public final ModConfigSpec.DoubleValue speedFovGain;
        public final ModConfigSpec.DoubleValue speedFovEase;
        public final ModConfigSpec.DoubleValue chargeFovGain;
        public final ModConfigSpec.DoubleValue chargeFovEase;

        public final ModConfigSpec.IntValue chargeRequestCooldown;
        public final ModConfigSpec.DoubleValue chargeAimTau;
        public final ModConfigSpec.DoubleValue chargeAimGain;

        public final ModConfigSpec.DoubleValue signalMaxBlurRadius;

        public final ModConfigSpec.BooleanValue detonationShakeEnabled;
        public final ModConfigSpec.DoubleValue detonationShakeSeconds;
        public final ModConfigSpec.DoubleValue detonationShakeDisplacement;
        public final ModConfigSpec.DoubleValue detonationShakeAngle;
        public final ModConfigSpec.DoubleValue detonationRadialBlur;
        public final ModConfigSpec.IntValue detonationTinnitusDelayMs;
        public final ModConfigSpec.DoubleValue detonationTinnitusVolume;
        public final ModConfigSpec.DoubleValue detonationTinnitusPitch;

        public final ModConfigSpec.ConfigValue<String> hudAccentColor;
        public final ModConfigSpec.BooleanValue hudShowTelemetry;
        public final ModConfigSpec.BooleanValue hudShowHints;

        Client(ModConfigSpec.Builder builder) {
            builder.comment("飞行手感").push("flight");
            flightAcceleration = builder
                    .comment("按住方向键时的加速度（格/刻²）。越大起步越快")
                    .defineInRange("acceleration", 0.030D, 0.0D, 2.0D);
            flightDeceleration = builder
                    .comment("松开方向键时的减速度（格/刻²）。越小滑行越远")
                    .defineInRange("deceleration", 0.020D, 0.0D, 2.0D);
            builder.pop();

            builder.comment("鼠标转向").push("look");
            lookGain = builder
                    .comment("鼠标位移的灵敏度倍率，越大转向越省力")
                    .defineInRange("gain", 1.4D, 0.1D, 10.0D);
            lookSmoothing = builder
                    .comment("转向平滑时间常数（秒），越小越跟手、越大越稳")
                    .defineInRange("smoothing", 0.06D, 0.005D, 1.0D);
            builder.pop();

            builder.comment("滚筒（C 键）：按住即持续绕视线轴旋转，同时进入滚筒冲刺").push("roll");
            rollTicks = builder
                    .comment("一次点按（非按住）滚筒要转多少刻才收住，11 刻约 0.55 秒")
                    .defineInRange("ticks", 11, 1, 200);
            rollCooldownTicks = builder
                    .comment("两次点按滚筒之间的冷却刻数，40 刻为 2 秒。按住不触发冷却")
                    .defineInRange("cooldownTicks", 40, 0, 1200);
            rollerRecoverTicks = builder
                    .comment("松开滚筒后，视角从当前滚转角回正到水平所需的刻数。"
                            + "松开时视角停在哪个角度是随机的，所以必须有一段回正，"
                            + "否则每一次滚筒都会把地平线永久拧歪。8 刻约 0.4 秒")
                    .defineInRange("recoverTicks", 8, 1, 200);
            rollCameraShare = builder
                    .comment("滚筒时镜头跟随的角度比例，1 为完全跟转，调低则镜头更平缓")
                    .defineInRange("cameraShare", 1.0D, 0.0D, 1.0D);
            rollerTurnsPerSecond = builder
                    .comment("按住滚筒时的旋转速度，圈/秒。1.6 约等于每 0.63 秒转一圈")
                    .defineInRange("turnsPerSecond", 1.6D, 0.1D, 10.0D);
            rollerDashSpeedGain = builder
                    .comment("滚筒冲刺时最大速度的提升比例，0.15 即 +15%。冲刺期间不会自爆")
                    .defineInRange("dashSpeedGain", 0.15D, 0.0D, 2.0D);
            rollerFovGain = builder
                    .comment("滚筒时视场角扩大的倍率，1.12 即 12%。这是滚筒带来的\"视角变化\"")
                    .defineInRange("fovGain", 1.12D, 1.0D, 2.0D);
            builder.pop();

            builder.comment("镜头倾斜").push("camera");
            cameraBankShare = builder
                    .comment("转弯时镜头复制机身倾角的比例")
                    .defineInRange("bankShare", 0.5D, 0.0D, 1.0D);
            builder.pop();

            builder.comment("视场角变化").push("fov");
            speedFovGain = builder
                    .comment("满速时视场角扩大的比例，0.18 即 18%")
                    .defineInRange("speedGain", 0.18D, 0.0D, 2.0D);
            speedFovEase = builder
                    .comment("速度视场角每帧靠近目标的比例")
                    .defineInRange("speedEase", 0.08D, 0.001D, 1.0D);
            chargeFovGain = builder
                    .comment("冲刺时视场角扩大的倍率，1.30 即 30%")
                    .defineInRange("chargeGain", 1.30D, 1.0D, 3.0D);
            chargeFovEase = builder
                    .comment("冲刺视场角每帧靠近目标的比例")
                    .defineInRange("chargeEase", 0.15D, 0.001D, 1.0D);
            builder.pop();

            builder.comment("冲刺瞄准与请求").push("chargeControl");
            chargeRequestCooldown = builder
                    .comment("两次冲刺请求之间的最小刻数")
                    .defineInRange("requestCooldown", 10, 0, 200);
            chargeAimTau = builder
                    .comment("冲刺瞄准的平滑时间常数（秒），用原版 F8 电影视角那套机制。"
                            + "它平滑的是「转速」而不是「位置」，所以稳态下灵敏度是精确的 1:1，"
                            + "只把变化抹圆 —— 这正是电影视角既跟手又不抖的原因。"
                            + "参考值：原版电影视角的等效值随玩家灵敏度在 0.24~1.0 秒之间。"
                            + "0.30 略钝于电影视角里最灵敏的那一档。调小更跟手，调大更沉稳")
                    .defineInRange("aimTau", 0.30D, 0.03D, 1.5D);
            chargeAimGain = builder
                    .comment("冲刺时鼠标灵敏度的倍率。普通飞行的 look.gain 是 1.4，"
                            + "所以 1.0 已经比平时难转；原版电影视角本身不放大灵敏度（等效 1.0）")
                    .defineInRange("aimGain", 1.0D, 0.1D, 3.0D);
            builder.pop();

            builder.comment("信号劣化").push("signal");
            signalMaxBlurRadius = builder
                    .comment("满信号丢失时的最大模糊半径（像素）")
                    .defineInRange("maxBlurRadius", 6.0D, 0.0D, 32.0D);
            builder.pop();

            builder.comment("引爆瞬间的冲击（只影响驾驶员自己的画面与耳朵）").push("detonation");
            detonationShakeEnabled = builder
                    .comment("引爆瞬间的镜头摇晃（位移 + 三轴抖动）总开关。关掉后画面不再晃，"
                            + "但径向模糊与耳鸣照常 —— 这一项只管镜头自己的抖动")
                    .define("shakeEnabled", true);
            detonationShakeSeconds = builder
                    .comment("引爆瞬间镜头冲击持续多少秒。0 为关掉整套冲击效果")
                    .defineInRange("shakeSeconds", 0.15D, 0.0D, 1.0D);
            detonationShakeDisplacement = builder
                    .comment("冲击时相机被推开的最大距离（格）。横向位移，0 为只抖角度不挪位置")
                    .defineInRange("shakeDisplacement", 0.22D, 0.0D, 2.0D);
            detonationShakeAngle = builder
                    .comment("冲击时俯仰抖动的最大角度（度）。偏航与滚转按比例取更小的值")
                    .defineInRange("shakeAngle", 5.0D, 0.0D, 45.0D);
            detonationRadialBlur = builder
                    .comment("引爆瞬间径向模糊的强度倍率，1.0 为设计值，0 为关闭")
                    .defineInRange("radialBlur", 1.0D, 0.0D, 3.0D);
            detonationTinnitusDelayMs = builder
                    .comment("爆炸声之后隔多少毫秒才响起耳鸣。80 毫秒是炸响还在、耳朵先嗡的那一拍")
                    .defineInRange("tinnitusDelayMs", 80, 0, 2000);
            detonationTinnitusVolume = builder
                    .comment("耳鸣音量")
                    .defineInRange("tinnitusVolume", 0.9D, 0.0D, 1.0D);
            detonationTinnitusPitch = builder
                    .comment("耳鸣音高倍率，越大越尖")
                    .defineInRange("tinnitusPitch", 1.0D, 0.5D, 2.0D);
            builder.pop();

            builder.comment("抬头显示").push("hud");
            hudAccentColor = builder
                    .comment("HUD 主色，写 #RRGGBB（#abc 会展开成 #aabbcc）。准星、边框、读数、"
                            + "速度流线与按键条都取这一色；半透明括号、淡刻度、正文与次要文字"
                            + "由它按固定比例派生，所以换色系时整个面罩是一起变的")
                    .define("accentColor", "#3BE8FF");
            hudShowTelemetry = builder
                    .comment("显示右上角的四行读数：X、Y、距操控者距离、剩余血量")
                    .define("showTelemetry", true);
            hudShowHints = builder
                    .comment("显示底部的按键提示条。关掉后只剩准星与读数，适合截图")
                    .define("showHints", true);
            builder.pop();
        }
    }
}
