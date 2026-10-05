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

        public final ModConfigSpec.DoubleValue moduleSignalRange;
        public final ModConfigSpec.DoubleValue moduleSpeedMultiplier;

        public final ModConfigSpec.IntValue batteryDrainTicks;
        public final ModConfigSpec.IntValue batteryRecallTicks;

        public final ModConfigSpec.DoubleValue scanRange;
        public final ModConfigSpec.DoubleValue scanFov;
        public final ModConfigSpec.DoubleValue throwScanFov;
        public final ModConfigSpec.DoubleValue pingRadius;
        public final ModConfigSpec.DoubleValue throwPingRadius;
        public final ModConfigSpec.IntValue glowDuration;
        public final ModConfigSpec.IntValue scanInterval;
        public final ModConfigSpec.IntValue maxRaycastsPerScan;

        public final ModConfigSpec.BooleanValue chestMarking;
        public final ModConfigSpec.DoubleValue chestRange;
        public final ModConfigSpec.IntValue chestInterval;
        public final ModConfigSpec.IntValue chestMinExposedFaces;
        public final ModConfigSpec.IntValue chestMaxMarkers;
        public final ModConfigSpec.BooleanValue chestLineOfSight;
        public final ModConfigSpec.IntValue chestMaxWallLayers;
        public final ModConfigSpec.IntValue chestMaxRaycasts;

        public final ModConfigSpec.BooleanValue alertEnabled;
        public final ModConfigSpec.DoubleValue alertRange;
        public final ModConfigSpec.DoubleValue alertWarnDistance;
        public final ModConfigSpec.DoubleValue alertCriticalDistance;
        public final ModConfigSpec.BooleanValue alertExcludeNeutral;
        public final ModConfigSpec.BooleanValue alertLineOfSight;
        public final ModConfigSpec.IntValue alertInterval;

        public final ModConfigSpec.BooleanValue followEnabled;
        public final ModConfigSpec.DoubleValue followDistance;
        public final ModConfigSpec.DoubleValue followHeight;
        public final ModConfigSpec.DoubleValue followGain;
        public final ModConfigSpec.DoubleValue followMaxSpeed;
        public final ModConfigSpec.DoubleValue followAccel;
        public final ModConfigSpec.DoubleValue followTurnSmoothing;
        public final ModConfigSpec.DoubleValue followRecallDistance;
        public final ModConfigSpec.DoubleValue followClearance;
        public final ModConfigSpec.IntValue followTrailLength;
        public final ModConfigSpec.IntValue followPredictTicks;
        public final ModConfigSpec.IntValue followPathRefreshTicks;
        public final ModConfigSpec.IntValue followPathSamples;

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

            builder.comment("模块效果：信号与速度").push("module");
            moduleSignalRange = builder
                    .comment("每一级信号模块增加的链路距离（格）。两级都加这么多，"
                            + "所以两级都装是 linkRange + 64")
                    .defineInRange("signalRange", 32.0D, 0.0D, 256.0D);
            moduleSpeedMultiplier = builder
                    .comment("速度解限模块把巡航上限提到玩家疾跑速度的多少倍。"
                            + "原版疾跑约 5.612 格/秒，1.5 倍即 8.418 格/秒（约 0.42 格/刻）。"
                            + "注意这是绝对目标而不是 flightSpeed 的倍数："
                            + "调 flightSpeed 不会带动它。"
                            + "上限卡在 2.0 是有原因的：滚筒冲刺会把上限再乘 1.15~3.0，"
                            + "而服务端按 maxStep（默认 1.8 格/包）逐包校验，"
                            + "倍率再高就会出现合法位移被服务端整包丢弃、无人机一卡一卡的情况")
                    .defineInRange("speedMultiplier", 1.5D, 1.0D, 2.0D);
            builder.pop();

            builder.comment("电池：耗电速度与回收时长").push("battery");
            batteryDrainTicks = builder
                    .comment("每多少刻消耗一格电量。600 刻 = 30 秒，"
                            + "也就是铜电池（25 格）撑 12.5 分钟、石墨电池（100 格）撑 50 分钟")
                    .defineInRange("drainTicks", 600, 20, 24000);
            batteryRecallTicks = builder
                    .comment("回收要多少刻。60 刻 = 3 秒")
                    .defineInRange("recallTicks", 60, 0, 600);
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

            builder.comment("箱子标记：无人机在外时，把视野内的箱子标在放飞者的屏幕上。"
                    + "结果是一份快照而不是历史 —— 每轮重建，箱子出范围就消失，无人机收回就清空。"
                    + "因此没有任何跨会话的存储，也不存在「过期标记」这种状态。").push("chestMarker");
            chestMarking = builder
                    .comment("总开关")
                    .define("enabled", true);
            chestRange = builder
                    .comment("以无人机为球心的搜索半径（格）。半径 32 格覆盖 5x5 个区块，"
                            + "只需遍历这些区块的方块实体表，代价远低于逐方块搜索")
                    .defineInRange("range", 32.0D, 4.0D, 128.0D);
            chestInterval = builder
                    .comment("两轮箱子扫描之间的刻数。箱子不会动，所以比生物扫描慢得多也没关系；"
                            + "扫太快只会在密集的仓库里反复重建同一份列表")
                    .defineInRange("interval", 10, 2, 200);
            chestMinExposedFaces = builder
                    .comment("至少露出几个面才算数（共 6 面）。用来滤掉砌进墙里的箱子。"
                            + "注意它挡不住「隔墙看不见」—— 密闭房间里的箱子六面都是空气，照样通过，"
                            + "所以真正的不透视要靠 requireLineOfSight")
                    .defineInRange("minExposedFaces", 2, 0, 6);
            chestMaxMarkers = builder
                    .comment("同时最多标记几个箱子，按距离由近到远取。这是给仓库准备的刹车")
                    .defineInRange("maxMarkers", 32, 1, 256);
            chestLineOfSight = builder
                    .comment("是否做视线判定。关掉等于纯雷达，半径内所有箱子一律标记（最快）")
                    .define("requireLineOfSight", true);
            chestMaxWallLayers = builder
                    .comment("允许隔着几层方块仍然标记（0 为必须完全通视）。"
                            + "1 表示隔一层墙也找得到 —— 这是默认值，因为无人机悬停在玩家头顶，"
                            + "完全通视会让盖了盖子的箱子、屋里的箱子全都标不出来，功能基本废掉。"
                            + "注意它确实带来了一定程度的透视，数值越大越像雷达，按服务器需要调")
                    .defineInRange("maxWallLayers", 1, 0, 8);
            chestMaxRaycasts = builder
                    .comment("每轮箱子扫描的视线检测上限，防止大仓库造成卡顿。"
                            + "露出面判定是零成本预筛，所以这个上限只作用在少数幸存者上")
                    .defineInRange("maxRaycastsPerScan", 32, 1, 512);
            builder.pop();

            builder.comment("敌对预警：有敌对生物接近放飞者时，在屏幕上亮起红色边框").push("alert");
            alertEnabled = builder
                    .comment("总开关")
                    .define("enabled", true);
            alertRange = builder
                    .comment("探测半径（格），以放飞者为圆心 —— 要保护的是玩家，不是无人机")
                    .defineInRange("range", 32.0D, 4.0D, 128.0D);
            alertWarnDistance = builder
                    .comment("威胁度从这个距离开始大于 0（格）")
                    .defineInRange("warnDistance", 24.0D, 1.0D, 128.0D);
            alertCriticalDistance = builder
                    .comment("威胁度在这个距离达到满格（格）。应小于 warnDistance")
                    .defineInRange("criticalDistance", 6.0D, 0.0D, 128.0D);
            alertExcludeNeutral = builder
                    .comment("排除中立生物。原版的 Enemy 接口把末影人与僵尸猪灵也算作敌对"
                            + "（两者都是 Monster 的子类），但它们平时不主动攻击，"
                            + "所以默认用 NeutralMob 把它们摘出去")
                    .define("excludeNeutral", true);
            alertLineOfSight = builder
                    .comment("是否要求无人机看得见该生物。默认关闭：预警要的是「附近有危险」，"
                            + "而怪拐过墙角正是最需要提醒的时候。开启则更严格，也更不容易被滥用")
                    .define("requireLineOfSight", false);
            alertInterval = builder
                    .comment("两轮威胁扫描之间的刻数，比箱子扫描快得多 —— 怪会动")
                    .defineInRange("interval", 4, 1, 200);
            builder.pop();

            builder.comment("跟随：无人机自己在放飞者身后飞，一边跟着走一边侦察。"
                    + "这是「不用坐进驾驶舱也能享受标记」这条需求的落点 —— 无人机是个哨兵，"
                    + "不是一台要人开的载具。驾驶员接管时自动暂停，松开后恢复。").push("follow");
            followEnabled = builder
                    .comment("放飞后是否默认进入跟随。关掉则无人机停在原地当固定哨兵")
                    .define("enabled", true);
            followDistance = builder
                    .comment("跟在玩家身后的水平距离（格）。刻意不做成「一直在头顶」 —— "
                            + "那样会挡住玩家的视野，而且看起来不像在跟随，像在吊着")
                    .defineInRange("distance", 4.0D, 1.0D, 24.0D);
            followHeight = builder
                    .comment("相对玩家的高度（格）。天花板低时会自动压低，见 clearance")
                    .defineInRange("height", 4.0D, 1.0D, 24.0D);
            followGain = builder
                    .comment("速度控制器的增益：离目标点每远一格，速度加多少（格/刻/格）。"
                            + "这条曲线是跟随能不能跟上的关键 —— 恒定速度不行，"
                            + "因为无人机的手动飞行上限 0.24 比玩家疾跑的 0.28 还慢")
                    .defineInRange("gain", 0.08D, 0.01D, 1.0D);
            followMaxSpeed = builder
                    .comment("跟随的最高速度（格/刻）。必须高于 flightSpeed(0.24)，否则跟不上"
                            + "疾跑的玩家；0.55 约合 11 m/s，能追上疾跑，但远低于冲刺的 1.45")
                    .defineInRange("maxSpeed", 0.55D, 0.05D, 4.0D);
            followAccel = builder
                    .comment("速度每刻朝目标值收敛的比例，0~1。这是「跟随手感」的主要旋钮："
                            + "调小起步与刹车都更柔，调大更跟手但会有顿挫")
                    .defineInRange("accel", 0.25D, 0.01D, 1.0D);
            followTurnSmoothing = builder
                    .comment("玩家速度估算每刻的收敛比例，0~1。前瞻靠这个速度值外推，"
                            + "所以它不能抖 —— 单帧位移里带着起步刹车的噪声，直接拿去外推会让"
                            + "目标点一跳一跳。也别调太小，太小会让预测严重滞后，前瞻就失效了，"
                            + "那时无人机又变回「追着屁股跑」")
                    .defineInRange("turnSmoothing", 0.25D, 0.005D, 1.0D);
            followRecallDistance = builder
                    .comment("离放飞者超过这个距离就直接收回（格）。这不是常规路径而是失败检测："
                            + "跟随时本不该拉开距离，能拉开就说明出了事 —— 传送、鞘翅、"
                            + "卡在方块里、区块边界抖动。它顺带把「传送后横穿世界」也一并挡掉了")
                    .defineInRange("recallDistance", 48.0D, 8.0D, 256.0D);
            followClearance = builder
                    .comment("与天花板的净空（格）。玩家走进洞穴或室内时，height 那个高度会落在"
                            + "岩石里，所以要向上探一层顶，把目标高度压到天花板之下")
                    .defineInRange("clearance", 1.5D, 0.5D, 8.0D);
            builder.pop();

            builder.comment("跟随的寻路 AI。核心思路是让无人机走玩家走过的路 —— "
                    + "玩家已经用脚证明了那条路是通的，比任何实时寻路都可靠，"
                    + "而且开阔地、拐角、洞穴三种场景自动都成立。").push("followPath");
            followTrailLength = builder
                    .comment("足迹缓冲保留多少个位置点。玩家只在真正移动时才留点，"
                            + "所以 64 点在正常行走下大约是三到五秒的历史")
                    .defineInRange("trailLength", 64, 8, 512);
            followPredictTicks = builder
                    .comment("前瞻多少刻。无人机瞄的是「玩家再走这么多刻之后会在哪」，"
                            + "而不是玩家此刻在哪 —— 这是跟随显得聪明的关键，"
                            + "没有它就会一直慢半拍地追着屁股跑")
                    .defineInRange("predictTicks", 10, 0, 60);
            followPathRefreshTicks = builder
                    .comment("隔多少刻重算一次指引点。算一次要打十几条射线，"
                            + "每刻都算没必要 —— 路径在几刻之内不会变")
                    .defineInRange("refreshTicks", 3, 1, 40);
            followPathSamples = builder
                    .comment("每次重算最多打几条射线。沿着足迹从新往旧找「最远的、"
                            + "视线可达的那个点」，射线数就是这条链的长度上限。"
                            + "打得越多抄近路抄得越狠，也越贵")
                    .defineInRange("samples", 12, 2, 64);
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
        public final ModConfigSpec.DoubleValue flightBankSpeedLoss;
        public final ModConfigSpec.DoubleValue flightDiveSpeedGain;

        public final ModConfigSpec.BooleanValue dashEnabled;
        public final ModConfigSpec.DoubleValue dashSpeedGain;
        public final ModConfigSpec.IntValue dashTicks;
        public final ModConfigSpec.IntValue dashCooldownTicks;
        public final ModConfigSpec.DoubleValue dashFovGain;

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

        public final ModConfigSpec.BooleanValue markerShowDistance;
        public final ModConfigSpec.BooleanValue markerEdgeIndicator;
        public final ModConfigSpec.IntValue markerMaxLabels;

        public final ModConfigSpec.BooleanValue alertShowBorder;
        public final ModConfigSpec.BooleanValue alertShowDirection;
        public final ModConfigSpec.IntValue alertPulseTicks;
        public final ModConfigSpec.DoubleValue alertMaxAlpha;

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
            flightBankSpeedLoss = builder
                    .comment("转向压弯掉速：满倾角时速度上限乘 (1 - 这个值)。"
                            + "0.25 即压满弯只剩七成半速度。这是「穿梭机手感」的一半 —— "
                            + "平飞最快、压弯要付代价，玩家才会去挑航线而不是一路按着前")
                    .defineInRange("bankSpeedLoss", 0.25D, 0.0D, 0.9D);
            flightDiveSpeedGain = builder
                    .comment("俯冲换速度：机头朝下时速度上限乘 (1 + 这个值 × 俯角/90)，"
                            + "抬头则减。0.35 即垂直俯冲时快三成半、垂直爬升时慢三成半。"
                            + "这是另一半 —— 高度变成可以花的东西，爬升是存能量、俯冲是取能量")
                    .defineInRange("diveSpeedGain", 0.35D, 0.0D, 2.0D);
            builder.pop();

            builder.comment("速度模块的小冲刺：短促的一次前冲，不是自爆突进").push("dash");
            dashEnabled = builder
                    .comment("总开关。只有装了速度模块的机体才有这一下 —— 模块本来就叫「调速器」，"
                            + "给它一个主动技能才配得上这个名字")
                    .define("enabled", true);
            dashSpeedGain = builder
                    .comment("冲刺期间速度上限的倍率。1.5 即比这架机体自己的巡航上限快五成。"
                            + "注意它是乘在巡航上限上的，所以装了速度模块的机体冲得更远")
                    .defineInRange("speedGain", 1.5D, 1.0D, 4.0D);
            dashTicks = builder
                    .comment("一次冲刺持续多少刻。10 刻等于半秒，够越过一个缺口，"
                            + "又不至于变成第二个巡航速度")
                    .defineInRange("ticks", 10, 1, 200);
            dashCooldownTicks = builder
                    .comment("两次冲刺之间的冷却刻数，30 刻为 1.5 秒")
                    .defineInRange("cooldownTicks", 30, 0, 1200);
            dashFovGain = builder
                    .comment("冲刺时视场角扩大的倍率。1.25 即 25%，比巡航的 1.18 更猛，"
                            + "这样那半秒是有推背感的")
                    .defineInRange("fovGain", 1.25D, 1.0D, 3.0D);
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

            builder.comment("箱子标记的画面部分。标什么由服务端决定，这里只管怎么画").push("marker");
            markerShowDistance = builder
                    .comment("在图标下方显示到箱子的距离")
                    .define("showDistance", true);
            markerEdgeIndicator = builder
                    .comment("箱子在画面外时，在屏幕边缘给一个方向点。"
                            + "关掉则只有转过去才看得到标记")
                    .define("edgeIndicator", true);
            markerMaxLabels = builder
                    .comment("同屏最多画几个标记，防止密集仓库把画面糊满")
                    .defineInRange("maxLabels", 16, 1, 128);
            builder.pop();

            builder.comment("敌对预警的画面部分").push("alert");
            alertShowBorder = builder
                    .comment("是否画红色边框")
                    .define("showBorder", true);
            alertShowDirection = builder
                    .comment("是否让威胁所在的那一侧更亮。关掉则是均匀的一圈，"
                            + "只看得出「有危险」看不出「在哪边」")
                    .define("showDirection", true);
            alertPulseTicks = builder
                    .comment("脉动一次多少刻，20 刻为 1 秒。脉动是它区别于原版低血红屏的地方："
                            + "静态的红边读起来像受伤，有节奏的才读得像警报")
                    .defineInRange("pulseTicks", 20, 4, 200);
            alertMaxAlpha = builder
                    .comment("满威胁度时的边框不透明度上限，0~1")
                    .defineInRange("maxAlpha", 0.55D, 0.0D, 1.0D);
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
