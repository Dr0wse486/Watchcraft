package dev.watchcraft.watchcraft.entity;

import dev.watchcraft.watchcraft.item.DroneModules;
import dev.watchcraft.watchcraft.network.DroneScanPayload;
import dev.watchcraft.watchcraft.registry.ModEntities;
import dev.watchcraft.watchcraft.registry.ModSounds;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.NeutralMob;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Explosion;
import net.minecraft.world.level.ExplosionDamageCalculator;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.PacketDistributor;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * A small quadcopter scout drone.
 *
 * <p>Behaviour:
 * <ul>
 *   <li>Right click a block with the drone item to deploy it.</li>
 *   <li>The owner can press the link key to take remote control (camera hops to the drone).</li>
 *   <li>While linked, movement is client driven and validated here (speed + leash range).</li>
 *   <li>Any living entity inside the drone's view cone, unobstructed by blocks, is tagged as
 *       glowing for a short while so it can be marked for the player.</li>
 *   <li>A thrown drone flies ballistically, then hovers, and keeps scanning on the way.</li>
 * </ul>
 */
public class ReconDroneEntity extends Entity {

    // ------------------------------------------------------------------ tuning

    /** How many drones one player may have out in the world at the same time. */
    public static int MAX_DEPLOYED = 1;

    /** How far the pilot may wander away from the drone before the link drops. */
    public static double LINK_RANGE = 64.0D;
    /** Distance at which the visor picture starts to break up. */
    public static double STATIC_ONSET = 48.0D;
    /**
     * Extra static strength for every this many blocks past the onset. The gap between
     * {@link #STATIC_ONSET} and {@link #LINK_RANGE} is sixteen blocks, so this divides it into
     * five even grades: clean at 48, 25% at 52, 50% at 56, 75% at 60, whiteout at 64.
     *
     * <p>Note that {@code LINK_RANGE * 0.75} - the point where the range readout turns amber - is
     * also 48, so the picture and the warning light come on together.
     */
    public static double STATIC_STEP = 4.0D;
    /** How far the camera can spot living entities. */
    public static double SCAN_RANGE = 48.0D;
    /** Full cone angle of the scan, in degrees. */
    public static double SCAN_FOV = 110.0D;
    /**
     * Full cone angle used while the drone is still in its throw.
     *
     * <p>Deliberately wider than the piloted cone. A thrown drone tumbles along its arc, so its
     * nose sweeps down and away from whatever the player was actually looking at; a cone tight
     * enough to feel like aiming slides straight past the target it flew over.
     */
    public static double THROW_SCAN_FOV = 150.0D;
    /**
     * Inside this radius the cone is ignored entirely - anything this close to the lens is in
     * frame no matter which way the drone happens to be pointing.
     */
    public static double PING_RADIUS = 8.0D;
    /** The same idea, stretched out while thrown, so a fast pass still pings what it flies by. */
    public static double THROW_PING_RADIUS = 16.0D;
    /** Ticks a spotted entity stays lit. */
    public static int GLOW_DURATION = 60;
    /**
     * Ticks between two scans.
     *
     * <p>Two, not eight. Only one drone can exist at a time, so this is the entire scan budget the
     * server spends, and eight ticks was slow enough that a thrown drone crossed a target's whole
     * angular width between two sweeps without ever seeing it.
     */
    public static int SCAN_INTERVAL = 2;
    /** Ceiling on line-of-sight raycasts per scan, so a crowd cannot turn a sweep into a stall. */
    public static int MAX_RAYCASTS_PER_SCAN = 24;

    // ------------------------------------------------------------------ 侦察回传（箱子与预警）

    /**
     * 箱子标记总开关。
     *
     * <p>与上面那套生物扫描是两回事，别混在一起看。生物扫描找的是 {@code Entity}，是"现在视野
     * 里有什么"；这里找的是 {@code BlockEntity}，是"哪里有箱子"，而且结果要送到<b>放飞者</b>的
     * 屏幕上，不管他有没有坐在无人机里。
     *
     * <p>结果是<b>快照</b>：每轮整体重建，箱子出范围就消失，无人机收回就清空。所以没有任何
     * 跨会话存储，也就没有"漏清导致永久残留"这类问题。
     */
    public static boolean CHEST_MARKING = true;
    /** 箱子搜索半径（格），以无人机为球心。 */
    public static double CHEST_RANGE = 32.0D;
    /** 两轮箱子扫描之间的刻数。箱子不会动，比生物扫描慢得多也没关系。 */
    public static int CHEST_INTERVAL = 10;
    /**
     * 至少露出几个面才算数（共 6 面）。
     *
     * <p>用来滤掉砌进墙里的箱子。判定必须走 {@code isSolidRender}，不要用"邻居是不是箱子方块"：
     * 箱子不是完整方块，{@code isSolidRender} 对它返回 false，所以双箱互相贴着的那一面会
     * <b>自动算作露出</b>，不需要任何特殊处理。
     */
    public static int CHEST_MIN_EXPOSED_FACES = 2;
    /** 同时最多标记几个箱子，按距离由近到远取。 */
    public static int CHEST_MAX_MARKERS = 32;
    /**
     * 是否要求无人机真的看得见。
     *
     * <p>开启才是不透视。注意 {@link #CHEST_MIN_EXPOSED_FACES} 挡不住"隔墙看不见" —— 密闭房间
     * 里的箱子六面都是空气，露出面判定照样通过，只有这一条射线能拦住它。
     */
    public static boolean CHEST_LINE_OF_SIGHT = true;
    /** 每轮箱子扫描的视线检测上限。露出面判定是零成本预筛，所以这里只作用在少数幸存者上。 */
    public static int CHEST_MAX_RAYCASTS = 32;

    /** 敌对预警总开关。 */
    public static boolean ALERT_ENABLED = true;
    /** 威胁探测半径（格），以<b>放飞者</b>为圆心 —— 要保护的是玩家，不是无人机。 */
    public static double ALERT_RANGE = 32.0D;
    /** 威胁度从这个距离开始大于 0（格）。 */
    public static double ALERT_WARN_DISTANCE = 24.0D;
    /** 威胁度在这个距离达到满格（格）。 */
    public static double ALERT_CRITICAL_DISTANCE = 6.0D;
    /**
     * 是否把中立生物摘出去。
     *
     * <p>原版的 {@code Enemy} 接口并不等于"会主动攻击玩家"：{@code EnderMan} 与
     * {@code ZombifiedPiglin} 都通过 {@code Monster} 继承了它，而这两个平时并不动手。它们都
     * 实现了 {@code NeutralMob}，所以那个接口正好是现成的筛子。
     */
    public static boolean ALERT_EXCLUDE_NEUTRAL = true;
    /** 是否要求视线。默认关闭：怪拐过墙角正是最需要提醒的时候。 */
    public static boolean ALERT_LINE_OF_SIGHT = false;
    /** 两轮威胁扫描之间的刻数。比箱子扫描快得多 —— 怪会动。 */
    public static int ALERT_INTERVAL = 4;

    /** Blocks per tick while piloted. Kept deliberately slow so the drone reads as a scout. */
    public static double FLIGHT_SPEED = 0.24D;
    /** How far the drone can reach when the pilot right clicks. */
    public static double INTERACT_RANGE = 5.0D;
    /** Hard cap on how far the server accepts a single client reported move. */
    public static double MAX_STEP = 1.8D;
    /** Initial speed of a thrown drone. */
    public static double THROW_SPEED = 0.85D;
    /** How long a thrown drone keeps its ballistic phase. */
    public static int THROW_TICKS = 60;

    // ------------------------------------------------------------------ combat

    /** Hit points. Ten points is five hearts, so a handful of sword swings brings it down. */
    public static float MAX_HEALTH = 10.0F;
    /** Ticks of immunity after a hit, so the airframe cannot be burst down in one swing chain. */
    public static int HURT_INVULNERABLE_TICKS = 10;
    /** Ticks the red damage flash lasts on the client. */
    public static int HURT_FLASH_TICKS = 10;
    /** Vanilla entity event id for "took damage" - the same one LivingEntity broadcasts. */
    public static final byte EVENT_HURT = 2;

    // ------------------------------------------------------------------ attack run

    /** Blocks per tick during an attack run. Roughly six times cruise, which is what sells it. */
    public static double CHARGE_SPEED = 1.45D;
    /** A run that hits nothing gives up after this long, so the drone cannot fly off forever. */
    public static int CHARGE_MAX_TICKS = 60;
    /**
     * How far the pilot may swing the nose off the axis the run locked to, in degrees.
     *
     * <p>The run is a straight line, so the aim is a nudge rather than a steering wheel: the axis
     * is fixed at launch and the crosshair can only bend the path inside this cone. That is what
     * makes it read as a committed charge instead of a faster version of ordinary flight, and it
     * is why there is no pitch floor any more - aiming level now flies level rather than forcing
     * a stoop.
     *
     * <p>Widened from the original sixteen degrees once the aim was given the cinematic-camera
     * smoothing. Sixteen was chosen back when the crosshair was hard-wired to the mouse and a wide
     * cone therefore read as "ordinary flight with a speed boost"; with the input smoothed, the
     * envelope can be roomier without the line going slack, and the extra room is what lets the
     * pilot actually use the smoothing to lead a target. At {@link #CHARGE_SPEED} over a full run,
     * twenty eight degrees is worth about forty two blocks of lateral travel.
     */
    public static double CHARGE_CONE = 28.0D;
    /**
     * The envelope the server enforces, deliberately wider than {@link #CHARGE_CONE}.
     *
     * <p>The client clamps to the tight cone and the server clamps to this one, so an honest pilot
     * is never clamped twice and the two sides cannot disagree about where the run is going. A
     * modified client gets the wide cone at worst, which is a slightly longer nudge - not a free
     * turn.
     */
    public static double CHARGE_CONE_SLACK = 36.0D;
    /** Ticks before the warhead can be armed again. Stops a held sprint key from re-triggering. */
    public static int CHARGE_COOLDOWN_TICKS = 40;
    /** Blast radius. Three blocks covers a doorway and the room behind it. */
    public static float CHARGE_EXPLOSION_RADIUS = 3.0F;
    /** Blocks are only broken within this distance of the impact point. */
    public static double CHARGE_CRATER = 2.0D;
    /** Flat damage every entity in the blast takes, before armour and enchantments. */
    public static float CHARGE_DAMAGE = 125.0F;
    /**
     * 引爆后满屏雪花屏的刻数。
     *
     * <p>这个阶段机体还没被移除：镜头仍然停在无人机上，画面被雪花彻底盖住，
     * 时间到了才真正销毁并断开链路，把视角还给玩家。40 刻等于 2 秒。
     */
    public static int CHARGE_STATIC_TICKS = 40;
    /**
     * How much of the remaining gap to the server's reported position the pilot's client closes
     * each tick while charging.
     *
     * <p>This is what makes the run smooth, and it exists because of a detail that is easy to
     * miss: {@code Entity#lerpTo} - the base one this entity inherits - is not an interpolation at
     * all, it is a plain {@code setPos}. Only {@code LivingEntity} overrides it with real
     * interpolation. So a base entity that takes the echo straight from the server steps its
     * position once per tick, and at {@link #CHARGE_SPEED} that is a twenty-times-a-second jump of
     * a block and a half. Chasing the echo instead spreads each jump across the ticks between them.
     *
     * <p>At 0.9 the steady-state lag behind the server is about a sixth of a block, which is well
     * under the airframe's own width, and the motion is a constant velocity rather than a series of
     * steps. Lower values smooth more but leave a visible gap between where the drone is drawn and
     * where the blast eventually goes off.
     */
    public static double CHARGE_ECHO_CHASE = 0.9D;

    // ------------------------------------------------------------------ aiming maths

    /**
     * {@return the unit view vector for a yaw/pitch pair}
     *
     * <p>Vanilla's own {@code Entity#calculateViewVector} is protected and the sign conventions are
     * easy to get backwards, so this is written out rather than reached for through a subclass.
     * The conventions it has to match are: yaw 0 is south (+Z), yaw 90 is west (-X), and a pitch of
     * -90 looks straight up.
     */
    public static Vec3 viewVector(float yRot, float xRot) {
        float pitch = xRot * Mth.DEG_TO_RAD;
        float yaw = -yRot * Mth.DEG_TO_RAD;
        float cosYaw = Mth.cos(yaw);
        float sinYaw = Mth.sin(yaw);
        float cosPitch = Mth.cos(pitch);
        float sinPitch = Mth.sin(pitch);
        return new Vec3(sinYaw * cosPitch, -sinPitch, cosYaw * cosPitch);
    }

    /** {@return the yaw pointing along {@code direction}, the inverse of {@link #viewVector}} */
    public static float yawOf(Vec3 direction) {
        return (float) Math.toDegrees(Math.atan2(-direction.x, direction.z));
    }

    /** {@return the pitch pointing along {@code direction}, the inverse of {@link #viewVector}} */
    public static float pitchOf(Vec3 direction) {
        return (float) Math.toDegrees(-Math.asin(Mth.clamp(direction.y, -1.0D, 1.0D)));
    }

    /**
     * {@return {@code desired} bent back onto the cone of {@code maxAngle} degrees around {@code axis}}
     *
     * <p>Exact rather than approximate: the result lies in the plane spanned by the axis and the
     * requested direction, at exactly the cone's half angle. Both sides call this with the same
     * axis and the same requested direction, which is what stops the pilot's crosshair and the
     * airframe's real heading from drifting apart at the edge of the cone.
     */
    public static Vec3 clampToCone(Vec3 axis, Vec3 desired, double maxAngle) {
        Vec3 a = axis.normalize();
        Vec3 d = desired.normalize();
        double cos = a.dot(d);
        double limit = Math.cos(Math.toRadians(maxAngle));
        if (cos >= limit) {
            return d;
        }
        Vec3 sideways = d.subtract(a.scale(cos));
        if (sideways.lengthSqr() < 1.0E-9D) {
            // Straight back down the axis: there is no plane to bend in, so the axis is the only
            // sensible answer.
            return a;
        }
        return a.scale(limit)
                .add(sideways.normalize().scale(Math.sqrt(Math.max(0.0D, 1.0D - limit * limit))))
                .normalize();
    }

    // ------------------------------------------------------------------ bank

    /**
     * Degrees of roll per degree-per-tick of yaw change: the drone leans into its turns. Every
     * client derives this from the rotation stream it already receives, so it costs no extra
     * network traffic and every viewer sees the same lean.
     */
    public static float BANK_GAIN = 1.8F;
    /** Hard cap on the turn lean. */
    public static float MAX_BANK = 30.0F;
    /** How fast the lean chases its target, per tick. */
    public static float BANK_SMOOTHING = 0.3F;

    // ------------------------------------------------------------------ data

    private static final EntityDataAccessor<Integer> DATA_PILOT_ID =
            SynchedEntityData.defineId(ReconDroneEntity.class, EntityDataSerializers.INT);

    private static final EntityDataAccessor<Integer> DATA_MARKS =
            SynchedEntityData.defineId(ReconDroneEntity.class, EntityDataSerializers.INT);

    private static final EntityDataAccessor<Float> DATA_HEALTH =
            SynchedEntityData.defineId(ReconDroneEntity.class, EntityDataSerializers.FLOAT);

    /** Loadout bit mask, mirrored from the drone item so every client can render and read it. */
    private static final EntityDataAccessor<Integer> DATA_MODULES =
            SynchedEntityData.defineId(ReconDroneEntity.class, EntityDataSerializers.INT);

    /**
     * Whether the drone is currently committed to an attack run.
     *
     * <p>Synced because both sides change behaviour on it: the pilot's client hands control of the
     * airframe over to the server, and {@link #lerpTo} stops swallowing the position echo, which
     * is the only way the run is ever seen to move.
     */
    private static final EntityDataAccessor<Boolean> DATA_CHARGING =
            SynchedEntityData.defineId(ReconDroneEntity.class, EntityDataSerializers.BOOLEAN);

    /**
     * The axis the current run is locked to, as a yaw/pitch pair.
     *
     * <p>Published rather than merely stored because the pilot's client has to clamp its own aim
     * against exactly the same reference the server will clamp against. Deriving the axis
     * independently on each side would let the two cones drift apart, and at the edge of the cone
     * that shows up as the crosshair pointing one way while the airframe flies another. Written
     * in the same tick as {@link #DATA_CHARGING}, so the client never sees one without the other.
     */
    private static final EntityDataAccessor<Float> DATA_CHARGE_YAW =
            SynchedEntityData.defineId(ReconDroneEntity.class, EntityDataSerializers.FLOAT);

    private static final EntityDataAccessor<Float> DATA_CHARGE_PITCH =
            SynchedEntityData.defineId(ReconDroneEntity.class, EntityDataSerializers.FLOAT);

    /**
     * Barrel roll angle in degrees, 0 to 360.
     *
     * <p>Synced rather than derived, unlike the turn lean, because it is not a function of the
     * rotation stream: the pilot presses a key and the airframe spins about its own nose while its
     * heading is unchanged. Everyone else has to be told. It rides in the movement packet that is
     * already sent every tick, so the roll costs four bytes a tick and no extra message.
     */
    private static final EntityDataAccessor<Float> DATA_ROLL =
            SynchedEntityData.defineId(ReconDroneEntity.class, EntityDataSerializers.FLOAT);

    /**
     * 引爆后雪花屏的剩余刻数，0 表示正常。
     *
     * <p>同步给客户端，好让驾驶员这一侧知道该把整块屏幕糊上雪花。这个数字同时充当倒计时：
     * 归零的那一刻机体才真正被移除，链路断开，镜头回到玩家身上。
     */
    private static final EntityDataAccessor<Integer> DATA_STATIC_TICKS =
            SynchedEntityData.defineId(ReconDroneEntity.class, EntityDataSerializers.INT);

    @Nullable
    private UUID ownerUUID;
    private boolean thrown;
    private int throwTicks;
    private int scanTimer;
    /** Ticks of immunity left after the last hit. Server side only. */
    private int hurtCooldown;
    /** Server side only: the last tick that accepted a movement packet, used to throttle clients. */
    private int lastMoveTick = -1;
    /** Server side: ticks left before the warhead may be armed again. */
    private int chargeCooldown;
    /** Server side: how long the current attack run has been going. */
    private int chargeTicks;
    /**
     * True for the instant the warhead goes off.
     *
     * <p>The blast damages everything in range, the drone included, and a second lethal hit would
     * route through {@link #hurt} into {@link #destroyDrone} and drop a second airframe on top of
     * the wreck. This is the latch that keeps the explosion from killing the drone twice.
     */
    private boolean detonating;
    /** 服务端：雪花屏阶段剩余的刻数，大于 0 时机体冻结、等待销毁。 */
    private int staticTicks;

    /** Client side damage flash timer, driven by {@link #EVENT_HURT}. */
    private int hurtTime;
    /** Client side turn lean, plus last tick's value so the renderer can interpolate. */
    private float bank;
    private float bankO;
    private float bankYaw;
    /** Client side barrel roll, plus last tick's value so the renderer can interpolate. */
    private float roll;
    private float rollO;
    /** Client side: the last position the server reported for a run, chased in {@link #clientTick}. */
    private Vec3 chargeTarget = Vec3.ZERO;
    private boolean chargeTargetSet;

    /** entity id -> remaining glow ticks, owned by this drone. */
    private final Map<Integer, Integer> marked = new HashMap<>();

    // ------------------------------------------------------------------ 侦察回传状态（服务端）

    /** 箱子扫描的倒计时。 */
    private int chestTimer;
    /** 威胁扫描的倒计时。 */
    private int alertTimer;
    /** 当前这一轮的箱子快照，已按距离由近到远排序。每轮整体重建。 */
    private final List<BlockPos> chestSnapshot = new ArrayList<>();
    /** 当前威胁度 0..100，0 表示没有威胁。 */
    private int threatPercent;
    /** 最近威胁的坐标，没有威胁时为 {@code null}。 */
    @Nullable
    private BlockPos threatPos;
    /**
     * 上一次真正发出去的内容。
     *
     * <p>用来把静止时的发包压到零：无人机停着、附近也没有箱子时，快照每一轮都一样，
     * 没有理由重复推给客户端。收回或断链时会被清掉，好让下次接上能重新发一份完整的。
     */
    @Nullable
    private DroneScanPayload lastPublished;

    public ReconDroneEntity(EntityType<?> type, Level level) {
        super(type, level);
        this.setNoGravity(true);
        this.noPhysics = false;
    }

    // ------------------------------------------------------------------ config

    /**
     * 把配置文件里的平衡值写进这一串静态常量。
     *
     * <p>由 {@code ModConfigEvent} 在载入/热重载后调用。之所以做成镜像而不是在使用处直接读，
     * 是因为 {@code ConfigValue#get()} 在配置尚未载入时会抛异常，而这些常量被静态上下文引用。
     */
    public static void applyConfig() {
        var common = dev.watchcraft.watchcraft.config.WatchcraftConfig.COMMON;
        MAX_DEPLOYED = common.maxDeployed.get();
        LINK_RANGE = common.linkRange.get();
        STATIC_ONSET = common.staticOnset.get();
        STATIC_STEP = common.staticStep.get();
        FLIGHT_SPEED = common.flightSpeed.get();
        INTERACT_RANGE = common.interactRange.get();
        MAX_STEP = common.maxStep.get();
        THROW_SPEED = common.throwSpeed.get();
        THROW_TICKS = common.throwTicks.get();
        MAX_HEALTH = common.maxHealth.get().floatValue();
        HURT_INVULNERABLE_TICKS = common.hurtInvulnerableTicks.get();
        HURT_FLASH_TICKS = common.hurtFlashTicks.get();

        SCAN_RANGE = common.scanRange.get();
        SCAN_FOV = common.scanFov.get();
        THROW_SCAN_FOV = common.throwScanFov.get();
        PING_RADIUS = common.pingRadius.get();
        THROW_PING_RADIUS = common.throwPingRadius.get();
        GLOW_DURATION = common.glowDuration.get();
        SCAN_INTERVAL = common.scanInterval.get();
        MAX_RAYCASTS_PER_SCAN = common.maxRaycastsPerScan.get();

        CHEST_MARKING = common.chestMarking.get();
        CHEST_RANGE = common.chestRange.get();
        CHEST_INTERVAL = common.chestInterval.get();
        CHEST_MIN_EXPOSED_FACES = common.chestMinExposedFaces.get();
        CHEST_MAX_MARKERS = common.chestMaxMarkers.get();
        CHEST_LINE_OF_SIGHT = common.chestLineOfSight.get();
        CHEST_MAX_RAYCASTS = common.chestMaxRaycasts.get();

        ALERT_ENABLED = common.alertEnabled.get();
        ALERT_RANGE = common.alertRange.get();
        ALERT_WARN_DISTANCE = common.alertWarnDistance.get();
        ALERT_CRITICAL_DISTANCE = common.alertCriticalDistance.get();
        ALERT_EXCLUDE_NEUTRAL = common.alertExcludeNeutral.get();
        ALERT_LINE_OF_SIGHT = common.alertLineOfSight.get();
        ALERT_INTERVAL = common.alertInterval.get();

        BANK_GAIN = common.bankGain.get().floatValue();
        MAX_BANK = common.maxBank.get().floatValue();
        BANK_SMOOTHING = common.bankSmoothing.get().floatValue();

        CHARGE_SPEED = common.chargeSpeed.get();
        CHARGE_MAX_TICKS = common.chargeMaxTicks.get();
        CHARGE_CONE = common.chargeCone.get();
        CHARGE_CONE_SLACK = common.chargeConeSlack.get();
        CHARGE_COOLDOWN_TICKS = common.chargeCooldownTicks.get();
        CHARGE_EXPLOSION_RADIUS = common.chargeExplosionRadius.get().floatValue();
        CHARGE_CRATER = common.chargeCraterRadius.get();
        CHARGE_DAMAGE = common.chargeDamage.get().floatValue();
        CHARGE_ECHO_CHASE = common.chargeEchoChase.get();
        CHARGE_STATIC_TICKS = common.chargeStaticTicks.get();
    }

    // ------------------------------------------------------------------ setup

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        builder.define(DATA_PILOT_ID, -1);
        builder.define(DATA_MARKS, 0);
        builder.define(DATA_HEALTH, MAX_HEALTH);
        builder.define(DATA_MODULES, 0);
        builder.define(DATA_CHARGING, false);
        // The axis is only meaningful while a run is up, but it is defined unconditionally so the
        // client always has a value to clamp against rather than a null to guard.
        builder.define(DATA_CHARGE_YAW, 0.0F);
        builder.define(DATA_CHARGE_PITCH, 0.0F);
        builder.define(DATA_ROLL, 0.0F);
        builder.define(DATA_STATIC_TICKS, 0);
    }

    @Override
    protected void readAdditionalSaveData(CompoundTag tag) {
        this.ownerUUID = tag.hasUUID("Owner") ? tag.getUUID("Owner") : null;
        this.thrown = tag.getBoolean("Thrown");
        // A damaged airframe stays damaged across a reload.
        this.setHealth(tag.contains("Health") ? tag.getFloat("Health") : MAX_HEALTH);
        // And it keeps whatever was bolted to it.
        this.setModules(tag.getInt("Modules"));
        // A drone never stays linked across a reload.
        this.setPilotId(-1);
    }

    @Override
    protected void addAdditionalSaveData(CompoundTag tag) {
        if (this.ownerUUID != null) {
            tag.putUUID("Owner", this.ownerUUID);
        }
        tag.putBoolean("Thrown", this.thrown);
        tag.putFloat("Health", this.getHealth());
        tag.putInt("Modules", this.getModules());
    }

    // ------------------------------------------------------------------ identity

    public void setOwner(@Nullable Player player) {
        this.ownerUUID = player == null ? null : player.getUUID();
    }

    @Nullable
    public UUID getOwnerUUID() {
        return this.ownerUUID;
    }

    public boolean isOwnedBy(Player player) {
        return this.ownerUUID != null && this.ownerUUID.equals(player.getUUID());
    }

    public int getPilotId() {
        return this.entityData.get(DATA_PILOT_ID);
    }

    public void setPilotId(int id) {
        this.entityData.set(DATA_PILOT_ID, id);
    }

    @Nullable
    public ServerPlayer getPilot() {
        int id = this.getPilotId();
        if (id < 0 || !(this.level() instanceof ServerLevel serverLevel)) {
            return null;
        }
        Entity entity = serverLevel.getEntity(id);
        return entity instanceof ServerPlayer player ? player : null;
    }

    public boolean isPilotedBy(Player player) {
        return this.getPilotId() == player.getId();
    }

    /** Number of entities currently lit up by this drone, synced for the visor readout. */
    public int getMarkCount() {
        return this.entityData.get(DATA_MARKS);
    }

    public boolean isPiloted() {
        return this.getPilotId() >= 0;
    }

    // ------------------------------------------------------------------ loadout

    /** {@return the bit mask of modules currently fitted to this airframe} */
    public int getModules() {
        return this.entityData.get(DATA_MODULES);
    }

    public void setModules(int mask) {
        this.entityData.set(DATA_MODULES, mask & (DroneModules.CUSTOMIZATION | DroneModules.ATTACK));
    }

    public boolean hasModule(int flag) {
        return DroneModules.has(this.getModules(), flag);
    }

    /** {@return whether this drone is mid attack run, warhead armed and committed} */
    public boolean isCharging() {
        return this.entityData.get(DATA_CHARGING);
    }

    /**
     * Claims the single movement packet this drone will accept for the given tick.
     *
     * <p>{@link #MAX_STEP} bounds one packet, not the packet rate, so without this a modified
     * client can simply spray move packets and buy itself unlimited speed. A stock client sends
     * exactly one per tick, so the ceiling costs legitimate play nothing.
     *
     * @return true if a packet may be processed for this tick
     */
    public boolean claimMoveSlot(int tick) {
        if (tick <= this.lastMoveTick) {
            return false;
        }
        this.lastMoveTick = tick;
        return true;
    }

    // ------------------------------------------------------------------ health

    public float getHealth() {
        return this.entityData.get(DATA_HEALTH);
    }

    public void setHealth(float health) {
        this.entityData.set(DATA_HEALTH, Mth.clamp(health, 0.0F, MAX_HEALTH));
    }

    public boolean isDestroyed() {
        return this.getHealth() <= 0.0F;
    }

    /** Ticks of red damage flash left. Client side only. */
    public int getHurtTime() {
        return this.hurtTime;
    }

    /** Interpolated turn lean in degrees, for the renderer. */
    public float getBank(float partialTick) {
        return Mth.lerp(partialTick, this.bankO, this.bank);
    }

    /** {@return the barrel roll angle the server last accepted, in degrees} */
    public float getRoll() {
        return this.entityData.get(DATA_ROLL);
    }

    /** Stores a barrel roll angle, folded into 0..360. */
    public void setRoll(float roll) {
        if (!Float.isFinite(roll)) {
            return;
        }
        this.entityData.set(DATA_ROLL, wrapRoll(roll));
    }

    /** {@return {@code degrees} folded into 0..360} */
    public static float wrapRoll(float degrees) {
        float wrapped = degrees % 360.0F;
        return wrapped < 0.0F ? wrapped + 360.0F : wrapped;
    }

    /**
     * Interpolated barrel roll in degrees, for the renderer.
     *
     * <p>Wrap aware, and that matters: a roll ends by folding 359 back to 0, which is the same
     * orientation but not the same number, and a plain lerp across that seam would read the last
     * degree of the roll as a full turn back the other way.
     */
    public float getRoll(float partialTick) {
        float delta = Mth.wrapDegrees(this.roll - this.rollO);
        return wrapRoll(this.rollO + delta * partialTick);
    }

    public void markThrown() {
        this.thrown = true;
        this.throwTicks = 0;
    }

    /** {@return 引爆后雪花屏还剩多少刻，0 表示一切正常} */
    public int getStaticTicks() {
        return this.entityData.get(DATA_STATIC_TICKS);
    }

    /** {@return 是否处于引爆后的雪花屏阶段} */
    public boolean isDetonated() {
        return this.getStaticTicks() > 0;
    }

    // ------------------------------------------------------------------ entity overrides

    @Override
    public boolean isPickable() {
        return true;
    }

    @Override
    public boolean isPushable() {
        return false;
    }

    @Override
    public boolean canBeCollidedWith() {
        return false;
    }

    @Override
    public boolean isAttackable() {
        return true;
    }

    /**
     * Damage handling for the airframe.
     *
     * <p>This entity extends {@link Entity} rather than {@code LivingEntity}, and that is exactly
     * what makes it immune to every status effect for free: potions, area effect clouds and
     * {@code /effect} all resolve their targets as a {@code LivingEntity}, so a buff simply never
     * reaches the drone. Fire immunity comes from the entity type's {@code fireImmune()} flag,
     * which {@link #isInvulnerableTo(DamageSource)} already honours. Nothing here needs to filter
     * effects out - there is no effect pipeline to filter.
     */
    @Override
    public boolean hurt(DamageSource source, float amount) {
        if (this.isRemoved() || this.detonating || this.isDetonated()) {
            return false;
        }
        if (amount <= 0.0F || this.isInvulnerableTo(source)) {
            return false;
        }
        if (this.level().isClientSide) {
            // Acknowledge the hit locally so the attacker's swing still lands with a thud, but let
            // the server be the one that moves the health bar and broadcasts the flash.
            return true;
        }
        if (this.hurtCooldown > 0) {
            return false;
        }
        this.hurtCooldown = HURT_INVULNERABLE_TICKS;
        this.markHurt();

        float remaining = this.getHealth() - amount;
        this.setHealth(remaining);
        if (remaining <= 0.0F) {
            this.destroyDrone();
            return true;
        }

        // Entity event 2 is the vanilla "took damage" flash; the client turns it into a red overlay.
        this.level().broadcastEntityEvent(this, EVENT_HURT);
        this.level().playSound(null, this.getX(), this.getY(), this.getZ(),
                SoundEvents.METAL_HIT, this.getSoundSource(),
                0.8F, 0.85F + this.random.nextFloat() * 0.3F);
        return true;
    }

    /** Goes down in a puff of smoke and sparks, leaving the airframe for its owner to recover. */
    private void destroyDrone() {
        this.goDown(Component.translatable("message.watchcraft.destroyed"), true);
    }

    /**
     * The end of an attack run.
     *
     * <p>Nothing is recovered, and that is the whole point: a charge is a one way trip, so every
     * part of the airframe goes with it - chassis, rotors, boards and all. Being shot down is the
     * cheap ending; spending the warhead is not.
     */
    private void wreckDrone() {
        this.goDown(Component.translatable("message.watchcraft.charge_destroyed"), false);
    }

    /**
     * Everything both endings share: unlink, drop the marks, raise the smoke, tell the owner, and
     * optionally leave the airframe behind.
     *
     * @param recoverable whether the drone should drop itself as an item for its owner to pick up
     */
    private void goDown(Component message, boolean recoverable) {
        this.prepareForRemoval(message);
        if (recoverable && this.ownerUUID != null) {
            this.spawnAtLocation(DroneModules.droneStack(this.getModules()), 0.25F);
        }
        this.discard();
    }

    /** Everything both endings share: unlink, drop the marks, raise the smoke, tell the owner. */
    private void prepareForRemoval(Component message) {
        if (!(this.level() instanceof ServerLevel serverLevel)) {
            return;
        }
        this.disconnectPilot();
        this.clearMarks();

        serverLevel.playSound(null, this.getX(), this.getY(), this.getZ(),
                SoundEvents.ITEM_BREAK, this.getSoundSource(), 1.0F, 0.9F);
        serverLevel.sendParticles(ParticleTypes.LARGE_SMOKE,
                this.getX(), this.getY() + 0.15D, this.getZ(),
                14, 0.22D, 0.14D, 0.22D, 0.02D);
        serverLevel.sendParticles(ParticleTypes.ELECTRIC_SPARK,
                this.getX(), this.getY() + 0.15D, this.getZ(),
                10, 0.18D, 0.12D, 0.18D, 0.06D);

        if (this.ownerUUID != null) {
            ServerPlayer owner = serverLevel.getServer().getPlayerList().getPlayer(this.ownerUUID);
            if (owner != null) {
                owner.displayClientMessage(message, true);
            }
        }
    }

    @Override
    public void handleEntityEvent(byte id) {
        if (id == EVENT_HURT) {
            this.hurtTime = HURT_FLASH_TICKS;
        } else {
            super.handleEntityEvent(id);
        }
    }

    /**
     * The local pilot owns this drone's transform, so the echo of our own reported position
     * coming back from the server must not drag the camera around. Client side only.
     *
     * <p>An attack run is the exception, and it has to be. The moment the warhead arms, the
     * pilot's client stops flying the airframe and the run becomes the server's to animate;
     * ignoring the echo outright would leave the drone hanging in the air until the blast. Taking
     * it as a plain {@code setPos} would work but would step at tick rate - see
     * {@link #CHARGE_ECHO_CHASE} - so the echo is stored and chased instead.
     */
    @Override
    public void lerpTo(double x, double y, double z, float yRot, float xRot, int steps) {
        if (this.level().isClientSide && this.getId() == DroneControlState.pilotedDroneId) {
            if (this.isCharging()) {
                this.chargeTarget = new Vec3(x, y, z);
                this.chargeTargetSet = true;
            }
            return;
        }
        super.lerpTo(x, y, z, yRot, xRot, steps);
    }

    @Override
    protected double getDefaultGravity() {
        return 0.0D;
    }

    @Override
    public void tick() {
        super.tick();
        if (this.level().isClientSide) {
            this.clientTick();
        } else {
            this.serverTick();
        }
    }

    // ------------------------------------------------------------------ client

    private void clientTick() {
        if (this.getId() == DroneControlState.pilotedDroneId) {
            this.setDeltaMovement(Vec3.ZERO);
            if (this.isCharging()) {
                this.chaseChargeEcho();
            } else {
                this.chargeTargetSet = false;
            }
        }
        if (this.hurtTime > 0) {
            this.hurtTime--;
        }
        this.tickBank();
        this.rollO = this.roll;
        this.roll = this.getRoll();
    }

    /**
     * Closes most of the gap to the last position the server reported for the run.
     *
     * <p>Run from the entity's own tick, which is after {@code setOldPosAndRot} has copied the
     * previous position into {@code xo} - so the camera's {@code lerp(partialTick, xo, x)} has a
     * real pair of positions to work with and the run renders at frame rate.
     *
     * <p>The position only. The echo also carries a rotation, and it is deliberately ignored here:
     * the pilot aims this airframe, so the rotation is already being written at frame rate by the
     * controller, and chasing a rotation that is a tick old would fight it at exactly the moment
     * the pilot is trying to steer.
     */
    private void chaseChargeEcho() {
        if (!this.chargeTargetSet) {
            return;
        }
        this.setPos(
                Mth.lerp(CHARGE_ECHO_CHASE, this.getX(), this.chargeTarget.x),
                Mth.lerp(CHARGE_ECHO_CHASE, this.getY(), this.chargeTarget.y),
                Mth.lerp(CHARGE_ECHO_CHASE, this.getZ(), this.chargeTarget.z));
    }

    /**
     * Leans the airframe into its turns.
     *
     * <p>Every client runs this off the rotation stream it already receives, so the lean is
     * identical for the pilot and for bystanders without a single extra packet. Gating on
     * {@link #isPiloted()} keeps it flat while the drone is ballistic or parked.
     */
    private void tickBank() {
        float target = 0.0F;
        if (this.isPiloted()) {
            float yawRate = Mth.wrapDegrees(this.getYRot() - this.bankYaw);
            target = Mth.clamp(yawRate * BANK_GAIN, -MAX_BANK, MAX_BANK);
        }
        this.bankYaw = this.getYRot();
        this.bankO = this.bank;
        this.bank += (target - this.bank) * BANK_SMOOTHING;
    }

    // ------------------------------------------------------------------ server

    private void serverTick() {
        if (!(this.level() instanceof ServerLevel serverLevel)) {
            return;
        }

        if (this.hurtCooldown > 0) {
            this.hurtCooldown--;
        }
        if (this.chargeCooldown > 0) {
            this.chargeCooldown--;
        }

        // The operator is parked before anything else, an attack run included. Skipping this while
        // charging would leave the body free to walk off and, worse, to keep accumulating fall
        // distance - the pilot would take the drop they took off from the moment the link ends.
        ServerPlayer pilot = this.getPilot();
        if (pilot != null && !this.isPilotValid(pilot)) {
            this.disconnectPilot();
            pilot = null;
        }
        if (pilot != null) {
            this.holdPilot(pilot);
        }

        // 引爆后的雪花屏阶段：机体还在，链路还在，位置冻结，倒计时归零才真正销毁。
        // 放在攻击判定之前，是因为这时候已经没有攻击可言了，剩下的只有等画面烧完。
        if (this.staticTicks > 0) {
            this.tickStatic();
            return;
        }

        // A run owns the airframe outright. Nothing else may touch it until it lands.
        if (this.isCharging()) {
            this.tickCharge(serverLevel);
            return;
        }

        if (this.thrown && pilot == null) {
            this.tickThrown();
        } else {
            this.setDeltaMovement(Vec3.ZERO);
        }

        if (++this.scanTimer >= SCAN_INTERVAL) {
            this.scanTimer = 0;
            this.scanForTargets(serverLevel);
        }
        this.tickMarks(serverLevel);
        this.entityData.set(DATA_MARKS, this.marked.size());

        // 箱子与预警走自己的节拍，和上面那套生物扫描无关：那套找的是 Entity（会动），
        // 这套找的是 BlockEntity（不动）与附近的敌对生物，两者的合理频率差着一个数量级。
        this.tickRecon(serverLevel);
    }

    private boolean isPilotValid(ServerPlayer pilot) {
        return pilot.isAlive()
                && !pilot.isRemoved()
                && !pilot.isSpectator()
                && pilot.level() == this.level()
                && pilot.distanceToSqr(this) <= LINK_RANGE * LINK_RANGE;
    }

    /**
     * Keeps the operator's body from carrying momentum or accumulating a fall while linked.
     *
     * <p>This mirrors what the client does to its own copy, and the fall distance is the part that
     * actually matters here. The client reports the body's position every tick while the link is
     * up, so the server replays the drop a tick at a time; each replay hands
     * {@code LivingEntity#checkFallDamage} one tick's worth of falling, and clearing it here means
     * that never adds up to enough to hurt. Without it the body would take the whole drop in one
     * lump on the tick the pilot dismounts.
     *
     * <p>The velocity is only ever trimmed sideways. Cancelling the whole vector would cancel
     * gravity along with it - gravity is worth just 0.078 blocks a tick - and the body would drift
     * down at walking pace instead of falling.
     */
    private void holdPilot(ServerPlayer pilot) {
        pilot.fallDistance = 0.0F;
        pilot.setSprinting(false);
        Vec3 motion = pilot.getDeltaMovement();
        pilot.setDeltaMovement(0.0D, motion.y, 0.0D);
    }

    /**
     * 雪花屏阶段的一刻。
     *
     * <p>只做三件事：冻结位移、把剩余刻数写回同步数据、归零时真正销毁并断开链路。
     * 机体的移除刻意推迟到这里，因为镜头正停在它身上 - 一旦立刻 discard，客户端会在同一刻
     * 把视角弹回玩家，雪花屏就没了承载它的那两秒。
     */
    private void tickStatic() {
        this.setDeltaMovement(Vec3.ZERO);
        this.staticTicks--;
        this.entityData.set(DATA_STATIC_TICKS, this.staticTicks);
        if (this.staticTicks <= 0) {
            this.wreckDrone();
        }
    }

    public void disconnectPilot() {
        int id = this.getPilotId();
        if (id < 0) {
            return;
        }
        this.setPilotId(-1);
        // A pilot who unlinks mid roll would otherwise leave the airframe frozen on its side: the
        // roll is only ever driven by the pilot's own client, so nobody is left to finish it.
        this.setRoll(0.0F);
        if (this.level() instanceof ServerLevel serverLevel
                && serverLevel.getEntity(id) instanceof ServerPlayer player) {
            dev.watchcraft.watchcraft.network.ModNetwork.sendLinkState(player, -1, false);
        }
    }

    private void tickThrown() {
        this.throwTicks++;
        Vec3 motion = this.getDeltaMovement();
        if (motion.lengthSqr() > 1.0E-6D) {
            this.move(MoverType.SELF, motion);
            if (this.horizontalCollision || this.verticalCollision) {
                this.setDeltaMovement(motion.scale(0.25D));
            } else {
                this.setDeltaMovement(motion.scale(0.93D));
            }
            double horizontal = Math.sqrt(motion.x * motion.x + motion.z * motion.z);
            if (horizontal > 1.0E-3D) {
                this.setYRot((float) (Math.toDegrees(Math.atan2(-motion.x, motion.z))));
                this.setXRot((float) (Math.toDegrees(-Math.atan2(motion.y, horizontal))));
            }
        } else {
            this.setDeltaMovement(Vec3.ZERO);
        }
        if (this.throwTicks > THROW_TICKS) {
            this.thrown = false;
            this.setDeltaMovement(Vec3.ZERO);
        }
    }

    // ------------------------------------------------------------------ attack run

    /**
     * Arms the warhead and commits the airframe to a straight run.
     *
     * <p>Server authoritative on every count: the module has to actually be fitted, the caller has
     * to actually be the pilot, and the warhead has to be off cooldown. A client that asks nicely
     * without any of that gets nothing, so a run cannot be triggered by a drone that was never
     * fitted for it.
     *
     * <p>The direction is the crosshair, exactly, with no pitch floor: a level aim now flies level
     * rather than being forced into a stoop. The axis is then locked and published, and the pilot's
     * only remaining input is the cone in {@link #CHARGE_CONE} around it.
     *
     * @return true if the run started
     */
    public boolean startCharge(ServerPlayer pilot) {
        if (this.level().isClientSide || this.isRemoved()) {
            return false;
        }
        // 引爆之后的雪花屏阶段机体已经没了，链路却还挂着。不挡住的话，一个改过的客户端
        // 能在残骸上再武装一次战斗部，把 DATA_CHARGING 重新点亮 —— 服务端那一侧被
        // tickStatic 挡着不会真的再炸一次，但驾驶员那侧会切进冲刺瞄准分支，状态就散了。
        if (this.isCharging() || this.isDetonated() || this.chargeCooldown > 0) {
            return false;
        }
        if (!this.hasModule(DroneModules.ATTACK) || !this.isPilotedBy(pilot)) {
            return false;
        }

        Vec3 direction = this.getViewVector(1.0F);
        if (direction.lengthSqr() < 1.0E-6D) {
            return false;
        }

        this.thrown = false;
        this.throwTicks = 0;
        this.chargeTicks = 0;
        this.chargeCooldown = CHARGE_COOLDOWN_TICKS;
        // Published before the flag, so the pilot's client can never see itself charging without
        // the axis to clamp against.
        this.entityData.set(DATA_CHARGE_YAW, this.getYRot());
        this.entityData.set(DATA_CHARGE_PITCH, this.getXRot());
        this.entityData.set(DATA_CHARGING, true);
        this.setDeltaMovement(direction.normalize().scale(CHARGE_SPEED));

        // A short supersonic crack rather than the rocket launch report this used to play. Played
        // once, for everyone: the null argument to Level#playSound is the player to *exclude*, so
        // nobody is left out, and the pilot is included because the sound engine takes its listener
        // from the camera - which is on this drone, at zero distance. The extra playNotifySound the
        // old code paired with it made the pilot hear the launch twice.
        this.level().playSound(null, this.getX(), this.getY(), this.getZ(),
                ModSounds.DRONE_BOOM.get(), SoundSource.NEUTRAL, 0.9F, 1.0F);
        return true;
    }

    /** {@return the direction the current run is locked to} */
    public Vec3 chargeAxis() {
        return viewVector(this.entityData.get(DATA_CHARGE_YAW), this.entityData.get(DATA_CHARGE_PITCH));
    }

    /**
     * Bends the nose, but only inside the cone.
     *
     * <p>Called with the pilot's own reported rotation while a run is under way. The clamp is the
     * whole security story of this feature: a client cannot steer a run anywhere the cone does not
     * reach, whatever it puts in its packets, and the wide envelope
     * {@link #CHARGE_CONE_SLACK} means an honest client - which has already clamped itself to the
     * tighter {@link #CHARGE_CONE} - is never second-guessed.
     */
    public void steerCharge(ServerPlayer pilot, float yRot, float xRot) {
        if (!this.isCharging() || !this.isPilotedBy(pilot)) {
            return;
        }
        if (!Float.isFinite(yRot) || !Float.isFinite(xRot)) {
            return;
        }
        Vec3 clamped = clampToCone(this.chargeAxis(), viewVector(yRot, xRot), CHARGE_CONE_SLACK);
        this.setYRot(yawOf(clamped));
        this.setXRot(pitchOf(clamped));
    }

    /**
     * One tick of the run: sweep forward, and go off on the first thing the nose touches.
     *
     * <p>The entity check runs before the move and looks at everything the airframe is about to
     * sweep through, because a run at {@link #CHARGE_SPEED} covers well over a block a tick - a
     * check that only looked at the position after the move would happily tunnel straight through
     * a player standing between two ticks.
     */
    private void tickCharge(ServerLevel level) {
        this.chargeTicks++;
        if (this.chargeTicks > CHARGE_MAX_TICKS) {
            this.detonate(level, this.position());
            return;
        }

        // The nose is already inside the cone - steerCharge saw to that - but it is clamped again
        // here rather than trusted, so a tick that arrived with no packet at all still flies
        // somewhere legal.
        Vec3 direction = clampToCone(this.chargeAxis(), this.getViewVector(1.0F), CHARGE_CONE_SLACK);
        this.setDeltaMovement(direction.scale(CHARGE_SPEED));
        Vec3 motion = this.getDeltaMovement();

        AABB sweep = this.getBoundingBox().expandTowards(motion).inflate(0.3D);
        Entity struck = null;
        double closest = Double.MAX_VALUE;
        for (Entity candidate : level.getEntities(this, sweep, this::isChargeTarget)) {
            double distance = candidate.distanceToSqr(this);
            if (distance < closest) {
                closest = distance;
                struck = candidate;
            }
        }
        if (struck != null) {
            this.detonate(level, struck.getBoundingBox().getCenter());
            return;
        }

        this.move(MoverType.SELF, motion);
        // The nose is not re-pointed from the motion here. It used to be, back when the run was a
        // fixed stoop and the server was the only thing that knew which way it went; now the pilot
        // aims it, and overwriting the rotation would throw away the very input the steering
        // arrives through.
        if (this.horizontalCollision || this.verticalCollision) {
            this.detonate(level, this.position());
        }
    }

    /**
     * 冲刺撞击的合法目标：除自己以外，任何体型正常、没有旁观的东西。
     *
     * <p>驾驶员本人与放飞者都在这个名单里。早先这里把驾驶员排除在外，结果是无人机撞到自己
     * 人身上会直接穿过去 - 既打不到别人家的玩家，也打不到站在旁边遥控的主人。现在两者都可
     * 以撞，代价是操作时要看着点自己的站位。
     */
    private boolean isChargeTarget(Entity entity) {
        return entity != this
                && !entity.isRemoved()
                && !entity.isSpectator()
                && entity.isPickable();
    }

    /**
     * Goes off.
     *
     * <p>The damage is flat rather than distance scaled. Vanilla's calculator falls off with range,
     * which is right for a TNT crater and wrong here: a warhead that does twenty points at the
     * point of impact and four at the edge cannot be aimed at anything in particular. Overriding
     * {@code getEntityDamageAmount} to a constant is the only way to promise the same twenty to
     * whatever the nose touches, and it is still delivered as an explosion, so protection and
     * blast protection reduce it exactly as they would a creeper.
     *
     * <p>The crater is kept small by {@code shouldBlockExplode} rather than by shrinking the blast:
     * the radius still governs who is caught, only the block damage is clipped to the impact point.
     */
    private void detonate(ServerLevel level, Vec3 at) {
        this.entityData.set(DATA_CHARGING, false);
        this.setDeltaMovement(Vec3.ZERO);

        // Read the pilot before anything unwinds the link, so the blast is credited to them.
        ServerPlayer pilot = this.getPilot();
        DamageSource source = level.damageSources().explosion(this, pilot);
        ExplosionDamageCalculator calculator = new ExplosionDamageCalculator() {
            @Override
            public float getEntityDamageAmount(Explosion explosion, Entity entity) {
                return CHARGE_DAMAGE;
            }

            @Override
            public boolean shouldBlockExplode(Explosion explosion, BlockGetter reader, BlockPos pos,
                                              BlockState state, float power) {
                return pos.distToCenterSqr(at.x, at.y, at.z) <= CHARGE_CRATER * CHARGE_CRATER;
            }
        };

        // The airframe is the blast's own source, so it is excluded from the damage sweep as well
        // as latched out of hurt(); between the two, the drone cannot be killed twice.
        this.detonating = true;
        try {
            level.explode(this, source, calculator, at.x, at.y, at.z,
                    CHARGE_EXPLOSION_RADIUS, false, Level.ExplosionInteraction.MOB);
        } finally {
            this.detonating = false;
        }

        // 不是马上销毁，而是先进入雪花屏阶段：镜头留在原地，画面糊掉两秒，
        // 时间到了才收拾残骸、断开链路、把视角还给玩家。
        this.beginStatic();
    }

    /**
     * 手动引爆。
     *
     * <p>只是多了一条通往 {@link #detonate} 的路，判定仍然全部在这里：得是驾驶员本人、
     * 机体上真的装了战斗部、当前不在冲刺也不在雪花屏里。
     */
    public boolean detonateManually(ServerPlayer pilot) {
        if (this.level().isClientSide || this.isRemoved()) {
            return false;
        }
        if (this.isCharging() || this.isDetonated() || this.detonating) {
            return false;
        }
        if (!this.hasModule(DroneModules.ATTACK) || !this.isPilotedBy(pilot)) {
            return false;
        }
        this.detonate((ServerLevel) this.level(), this.position());
        return true;
    }

    /**
     * 进入雪花屏阶段。
     *
     * <p>{@link #CHARGE_STATIC_TICKS} 为 0 时直接走原来的即时销毁，方便把这个效果关掉。
     */
    private void beginStatic() {
        if (CHARGE_STATIC_TICKS <= 0) {
            this.wreckDrone();
            return;
        }
        this.staticTicks = CHARGE_STATIC_TICKS;
        this.entityData.set(DATA_STATIC_TICKS, CHARGE_STATIC_TICKS);
    }

    // ------------------------------------------------------------------ scanning

    private void scanForTargets(ServerLevel level) {
        Vec3 eye = this.getEyePosition();
        Vec3 look = this.getViewVector(1.0F);

        // A thrown drone gets the wide sweep; a piloted one keeps the tighter cone, because the
        // pilot is aiming and marking half the compass would read as noise rather than as a find.
        double fov = this.thrown ? THROW_SCAN_FOV : SCAN_FOV;
        double ping = this.thrown ? THROW_PING_RADIUS : PING_RADIUS;
        double cosHalfFov = Math.cos(Math.toRadians(fov * 0.5D));
        double pingSqr = ping * ping;

        AABB area = this.getBoundingBox().inflate(SCAN_RANGE);
        List<LivingEntity> candidates = level.getEntitiesOfClass(LivingEntity.class, area, this::isScannable);

        int raycasts = 0;
        for (LivingEntity candidate : candidates) {
            Vec3 centre = candidate.getBoundingBox().getCenter();
            Vec3 delta = centre.subtract(eye);
            double distance = delta.length();
            if (distance < 0.5D || distance > SCAN_RANGE) {
                continue;
            }
            // Within the ping radius the angle does not matter; past it the target has to be in
            // the cone.
            if (distance * distance > pingSqr
                    && delta.scale(1.0D / distance).dot(look) < cosHalfFov) {
                continue;
            }
            if (raycasts >= MAX_RAYCASTS_PER_SCAN) {
                break;
            }
            raycasts++;
            if (!this.canSee(level, eye, candidate)) {
                continue;
            }
            this.marked.put(candidate.getId(), GLOW_DURATION);
            candidate.setGlowingTag(true);
        }
    }

    private boolean isScannable(LivingEntity entity) {
        if (!entity.isAlive() || entity.isSpectator() || entity.isRemoved()) {
            return false;
        }
        if (entity.getId() == this.getPilotId()) {
            return false;
        }
        if (this.ownerUUID != null && entity instanceof Player player
                && this.ownerUUID.equals(player.getUUID())) {
            return false;
        }
        return true;
    }

    /**
     * {@return whether any part of the entity can be seen from the given point}
     *
     * <p>Nearest surface first, then the centre as a fallback. The first version cast a single ray
     * at the bounding box centre, which loses anything whose middle happens to sit behind a fence
     * post, a lip of ground, or the very block the target is standing on - that is, most of what a
     * drone flying over broken terrain is actually looking at.
     */
    private boolean canSee(Level level, Vec3 eye, LivingEntity entity) {
        AABB box = entity.getBoundingBox();
        Vec3 nearest = new Vec3(
                Mth.clamp(eye.x, box.minX, box.maxX),
                Mth.clamp(eye.y, box.minY, box.maxY),
                Mth.clamp(eye.z, box.minZ, box.maxZ));
        if (this.hasClearLine(level, eye, nearest)) {
            return true;
        }
        Vec3 centre = box.getCenter();
        return nearest.distanceToSqr(centre) > 1.0E-4D && this.hasClearLine(level, eye, centre);
    }

    private boolean hasClearLine(Level level, Vec3 from, Vec3 to) {
        ClipContext context = new ClipContext(from, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, this);
        BlockHitResult result = level.clip(context);
        return result.getType() == HitResult.Type.MISS;
    }

    private void tickMarks(ServerLevel level) {
        if (this.marked.isEmpty()) {
            return;
        }
        Iterator<Map.Entry<Integer, Integer>> iterator = this.marked.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<Integer, Integer> entry = iterator.next();
            int remaining = entry.getValue() - 1;
            if (remaining > 0) {
                entry.setValue(remaining);
                continue;
            }
            iterator.remove();
            if (level.getEntity(entry.getKey()) instanceof LivingEntity living
                    && !living.hasEffect(MobEffects.GLOWING)) {
                living.setGlowingTag(false);
            }
        }
    }

    // ------------------------------------------------------------------ 侦察回传

    /**
     * 箱子与威胁的扫描节拍。
     *
     * <p>两条独立的倒计时，因为两者的合理频率差得很远：箱子是静止的，十刻一轮足够；怪会动，
     * 四刻一轮才不至于在它已经贴到脸上时还没报出来。各自扫完就尝试发布一次，而发布本身会
     * 比对内容，所以这里多调一次不会有任何多余的流量。
     */
    private void tickRecon(ServerLevel level) {
        if (++this.chestTimer >= CHEST_INTERVAL) {
            this.chestTimer = 0;
            this.scanChests(level);
            this.publishScan(level);
        }
        if (++this.alertTimer >= ALERT_INTERVAL) {
            this.alertTimer = 0;
            this.scanThreats(level);
            this.publishScan(level);
        }
    }

    /**
     * 重建箱子快照。
     *
     * <p>三步：找候选、筛露出面、查视线。第三步只作用在第二步的幸存者上，这个顺序是有意的 ——
     * 露出面判定是六次方块查询的零成本操作，而射线是这整件事里最贵的一环，让前者先把明显
     * 埋在地里的那批砍掉，射线的预算就永远够用。
     *
     * <p>取区块用的是 {@code getChunkNow} 而不是 {@code getChunk}。这不是风格问题：
     * {@code getChunk} 会把没加载的区块<b>加载出来</b>，等于让无人机顺带当一个区块加载器，
     * 既是服务器负担，也是滥用的口子。前者返回 {@code null}，跳过就完事。
     */
    private void scanChests(ServerLevel level) {
        this.chestSnapshot.clear();
        if (!CHEST_MARKING) {
            return;
        }

        BlockPos centre = this.blockPosition();
        int originX = centre.getX() >> 4;
        int originZ = centre.getZ() >> 4;
        int chunkRadius = Mth.ceil(CHEST_RANGE) >> 4;
        double rangeSqr = CHEST_RANGE * CHEST_RANGE;

        List<BlockPos> candidates = new ArrayList<>();
        for (int dx = -chunkRadius; dx <= chunkRadius; dx++) {
            for (int dz = -chunkRadius; dz <= chunkRadius; dz++) {
                LevelChunk chunk = level.getChunkSource().getChunkNow(originX + dx, originZ + dz);
                if (chunk == null) {
                    continue;
                }
                for (Map.Entry<BlockPos, BlockEntity> entry : chunk.getBlockEntities().entrySet()) {
                    if (!(entry.getValue() instanceof ChestBlockEntity)) {
                        continue;
                    }
                    BlockPos pos = entry.getKey();
                    if (distanceSqr(pos, this.getX(), this.getY(), this.getZ()) > rangeSqr) {
                        continue;
                    }
                    if (exposedFaces(level, pos) < CHEST_MIN_EXPOSED_FACES) {
                        continue;
                    }
                    candidates.add(pos);
                }
            }
        }
        if (candidates.isEmpty()) {
            return;
        }

        // 近的先来：名额用尽时留下的是最该看到的那些。
        candidates.sort(Comparator.comparingDouble(
                pos -> distanceSqr(pos, this.getX(), this.getY(), this.getZ())));

        Vec3 eye = this.getEyePosition();
        int raycasts = 0;
        for (BlockPos pos : candidates) {
            if (this.chestSnapshot.size() >= CHEST_MAX_MARKERS) {
                break;
            }
            if (CHEST_LINE_OF_SIGHT) {
                if (raycasts >= CHEST_MAX_RAYCASTS) {
                    break;
                }
                raycasts++;
                if (!hasLineTo(level, eye, pos)) {
                    continue;
                }
            }
            this.chestSnapshot.add(pos);
        }
    }

    /**
     * {@return 这个箱子露出几个面}
     *
     * <p>判定用 {@code isSolidRender}，不要用"邻居是不是箱子方块"。箱子不是完整方块
     * （形状是 14/16 的 AABB），{@code isSolidRender} 对它返回 false，所以双箱互相贴着的那一面
     * <b>自动算作露出</b> —— 用方块类型判断反而会把那一面当成被遮挡，把贴墙的双箱误判成埋在地里。
     *
     * <p>未加载的邻居当作挡住了。保守方向是少标几个，而不是把埋在墙里的也放进来。
     */
    private static int exposedFaces(Level level, BlockPos pos) {
        int exposed = 0;
        for (Direction direction : Direction.values()) {
            BlockPos neighbour = pos.relative(direction);
            if (!level.isLoaded(neighbour)) {
                continue;
            }
            if (!level.getBlockState(neighbour).isSolidRender(level, neighbour)) {
                exposed++;
            }
        }
        return exposed;
    }

    /**
     * {@return 从 {@code from} 到目标方块是否通视}
     *
     * <p>和 {@link #hasClearLine} 的差别在命中判定：射线瞄的就是箱子本身，所以打在目标方块上
     * 是<b>成功</b>，不是遮挡。{@code hasClearLine} 那边要求必须 MISS，用它会把所有箱子都判掉。
     */
    private boolean hasLineTo(Level level, Vec3 from, BlockPos target) {
        ClipContext context = new ClipContext(from, Vec3.atCenterOf(target),
                ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, this);
        BlockHitResult result = level.clip(context);
        return result.getType() == HitResult.Type.MISS || result.getBlockPos().equals(target);
    }

    private static double distanceSqr(BlockPos pos, double x, double y, double z) {
        double dx = pos.getX() + 0.5D - x;
        double dy = pos.getY() + 0.5D - y;
        double dz = pos.getZ() + 0.5D - z;
        return dx * dx + dy * dy + dz * dz;
    }

    /**
     * 重算威胁度。
     *
     * <p>圆心是<b>放飞者</b>而不是无人机。无人机只是个传感器，被保护的对象是玩家，所以
     * "多近才算危险"必须按玩家和怪的距离来量。
     *
     * <p>只取最近的那一个驱动强度，方向也只用它。多个怪同时靠近时，最近的那个本来就决定了
     * 玩家该先处理谁。
     */
    private void scanThreats(ServerLevel level) {
        this.threatPercent = DroneScanPayload.NO_THREAT;
        this.threatPos = null;
        if (!ALERT_ENABLED) {
            return;
        }
        ServerPlayer owner = this.getOwnerPlayer(level);
        if (owner == null) {
            return;
        }

        double rangeSqr = ALERT_RANGE * ALERT_RANGE;
        List<LivingEntity> candidates = level.getEntitiesOfClass(LivingEntity.class,
                owner.getBoundingBox().inflate(ALERT_RANGE), this::isThreat);

        Vec3 eye = this.getEyePosition();
        LivingEntity nearest = null;
        double nearestSqr = Double.MAX_VALUE;
        for (LivingEntity mob : candidates) {
            double distance = mob.distanceToSqr(owner);
            if (distance > rangeSqr || distance >= nearestSqr) {
                continue;
            }
            if (ALERT_LINE_OF_SIGHT && !this.canSee(level, eye, mob)) {
                continue;
            }
            nearestSqr = distance;
            nearest = mob;
        }
        if (nearest == null) {
            return;
        }

        double distance = Math.sqrt(nearestSqr);
        double span = ALERT_WARN_DISTANCE - ALERT_CRITICAL_DISTANCE;
        double strength;
        if (distance >= ALERT_WARN_DISTANCE) {
            strength = 0.0D;
        } else if (span <= 0.0D || distance <= ALERT_CRITICAL_DISTANCE) {
            strength = 1.0D;
        } else {
            strength = (ALERT_WARN_DISTANCE - distance) / span;
        }

        int percent = (int) Math.round(Mth.clamp(strength, 0.0D, 1.0D) * 100.0D);
        if (percent <= DroneScanPayload.NO_THREAT) {
            return;
        }
        this.threatPercent = percent;
        this.threatPos = nearest.blockPosition();
    }

    /**
     * {@return 这个生物算不算"需要预警的威胁"}
     *
     * <p>{@code Enemy} 是原版的敌对标记接口，但它并不等于"会主动攻击玩家"：
     * {@code EnderMan} 与 {@code ZombifiedPiglin} 都通过 {@code Monster} 继承了它，而这两个
     * 平时并不动手。它们都实现了 {@code NeutralMob}，所以那个接口正好是现成的筛子。
     */
    private boolean isThreat(LivingEntity entity) {
        if (!entity.isAlive() || entity.isRemoved() || entity.isSpectator()) {
            return false;
        }
        if (!(entity instanceof Enemy)) {
            return false;
        }
        return !ALERT_EXCLUDE_NEUTRAL || !(entity instanceof NeutralMob);
    }

    /** {@return 放飞者，不在线时为 {@code null}} */
    @Nullable
    private ServerPlayer getOwnerPlayer(ServerLevel level) {
        if (this.ownerUUID == null) {
            return null;
        }
        MinecraftServer server = level.getServer();
        return server == null ? null : server.getPlayerList().getPlayer(this.ownerUUID);
    }

    /**
     * 把当前快照推给放飞者 —— 注意不是驾驶员。
     *
     * <p>无人机可以自己在外飞、玩家在别处做自己的事，所以这条消息既不看链路，也不看玩家在
     * 哪个维度。唯一的收件人是"这架无人机属于谁"。
     *
     * <p>内容没变就不发。无人机停着、附近也没有箱子时，这条几乎不产生任何流量。
     */
    private void publishScan(ServerLevel level) {
        ServerPlayer owner = this.getOwnerPlayer(level);
        if (owner == null) {
            return;
        }
        DroneScanPayload payload = new DroneScanPayload(
                List.copyOf(this.chestSnapshot),
                this.threatPercent,
                this.threatPos == null ? 0 : this.threatPos.getX(),
                this.threatPos == null ? 0 : this.threatPos.getY(),
                this.threatPos == null ? 0 : this.threatPos.getZ());
        if (payload.equals(this.lastPublished)) {
            return;
        }
        this.lastPublished = payload;
        PacketDistributor.sendToPlayer(owner, payload);
    }

    /**
     * 清空快照并通知客户端。
     *
     * <p>无人机收回、被拾起或被销毁时调用。靠"过一会儿自然过期"是不行的 —— 那时候机体已经
     * 不存在了，没有任何东西会再发一条来纠正客户端屏幕上的残留。
     */
    private void publishEmptyScan() {
        this.chestSnapshot.clear();
        this.threatPercent = DroneScanPayload.NO_THREAT;
        this.threatPos = null;
        this.lastPublished = null;
        if (!(this.level() instanceof ServerLevel level)) {
            return;
        }
        ServerPlayer owner = this.getOwnerPlayer(level);
        if (owner != null) {
            PacketDistributor.sendToPlayer(owner, DroneScanPayload.empty());
        }
    }

    // ------------------------------------------------------------------ interaction

    @Override
    public InteractionResult interact(Player player, InteractionHand hand) {
        if (this.level().isClientSide) {
            return InteractionResult.SUCCESS;
        }
        if (!this.isOwnedBy(player)) {
            return InteractionResult.PASS;
        }
        if (player.isShiftKeyDown()) {
            this.pickUp(player);
            return InteractionResult.CONSUME;
        }
        return InteractionResult.PASS;
    }

    private void pickUp(Player player) {
        this.disconnectPilot();
        this.clearMarks();
        // The loadout travels with the airframe: a fitted drone comes back fitted.
        ItemStack stack = DroneModules.droneStack(this.getModules());
        if (!player.getInventory().add(stack)) {
            player.drop(stack, false);
        }
        this.discard();
    }

    /** Folds this drone back into the owner's inventory from any distance. */
    public void recallTo(Player player) {
        this.pickUp(player);
    }

    private void clearMarks() {
        // 侦察快照跟着一起清。这两件事的收尾时机完全相同 —— 机体都要没了，屏幕上不该再留着
        // 它生前看到的东西 —— 而箱子快照是"发出去就完了"，不像发光那样有实体状态要还回去，
        // 所以唯一的清理动作就是把空快照推给客户端。
        this.publishEmptyScan();
        if (!(this.level() instanceof ServerLevel serverLevel)) {
            this.marked.clear();
            return;
        }
        for (Integer id : this.marked.keySet()) {
            if (serverLevel.getEntity(id) instanceof LivingEntity living
                    && !living.hasEffect(MobEffects.GLOWING)) {
                living.setGlowingTag(false);
            }
        }
        this.marked.clear();
    }

    @Override
    public void remove(RemovalReason reason) {
        if (!this.level().isClientSide && reason != RemovalReason.UNLOADED_TO_CHUNK) {
            this.disconnectPilot();
            this.clearMarks();
        }
        super.remove(reason);
    }

    /** Spawns a drone that flies out in front of the player, then hovers. */
    public static void throwFrom(Level level, Player player, ItemStack stack) {
        ReconDroneEntity drone = ModEntities.RECON_DRONE.get().create(level);
        if (drone == null) {
            return;
        }
        Vec3 look = player.getViewVector(1.0F);
        Vec3 spawn = player.getEyePosition().add(look.scale(0.9D)).subtract(0.0D, 0.2D, 0.0D);
        drone.moveTo(spawn.x, spawn.y, spawn.z, player.getYRot(), player.getXRot());
        drone.setOwner(player);
        drone.setModules(DroneModules.of(stack));
        drone.setDeltaMovement(look.scale(THROW_SPEED).add(0.0D, 0.06D, 0.0D));
        drone.markThrown();
        level.addFreshEntity(drone);
        if (!player.getAbilities().instabuild) {
            stack.shrink(1);
        }
    }

    /** Picks the drone a link request should attach to: the preferred one, else the nearest idle one. */
    @Nullable
    public static ReconDroneEntity findLinkable(ServerLevel level, Player player, int preferredId) {
        if (preferredId >= 0 && level.getEntity(preferredId) instanceof ReconDroneEntity drone
                && drone.isOwnedBy(player)) {
            return drone;
        }
        AABB area = player.getBoundingBox().inflate(LINK_RANGE);
        return level.getEntitiesOfClass(ReconDroneEntity.class, area,
                        drone -> drone.isOwnedBy(player) && !drone.isPiloted())
                .stream()
                .min(Comparator.comparingDouble(drone -> drone.distanceToSqr(player)))
                .orElse(null);
    }

    /**
     * The single drone this player currently has deployed, searched across every dimension.
     * Returns null on the client (no server to ask) and when the player has none out.
     */
    @Nullable
    public static ReconDroneEntity findDeployed(Player player) {
        MinecraftServer server = player.getServer();
        if (server == null) {
            return null;
        }
        for (ServerLevel level : server.getAllLevels()) {
            for (Entity entity : level.getAllEntities()) {
                if (entity instanceof ReconDroneEntity drone && drone.isOwnedBy(player)) {
                    return drone;
                }
            }
        }
        return null;
    }

    /** A player may only ever have {@value #MAX_DEPLOYED} drone out in the world at a time. */
    public static boolean hasDeployed(Player player) {
        return findDeployed(player) != null;
    }

    /** Used by the server to keep the drone out of the void. */
    public void clampToWorld(ServerLevel level) {
        double minY = level.getMinBuildHeight() + 0.05D;
        double maxY = level.getMaxBuildHeight() - 1.0D;
        if (this.getY() < minY || this.getY() > maxY) {
            this.setPos(this.getX(), Math.min(Math.max(this.getY(), minY), maxY), this.getZ());
        }
        BlockPos pos = this.blockPosition();
        if (!level.isLoaded(pos)) {
            this.setDeltaMovement(Vec3.ZERO);
        }
    }
}
