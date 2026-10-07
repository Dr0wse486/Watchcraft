package dev.watchcraft.watchcraft.client;

import dev.watchcraft.watchcraft.entity.DroneControlState;
import dev.watchcraft.watchcraft.entity.ReconDroneEntity;
import dev.watchcraft.watchcraft.item.DroneModules;
import dev.watchcraft.watchcraft.network.DroneActionPayload;
import dev.watchcraft.watchcraft.network.DroneMovePayload;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.Input;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.MovementInputUpdateEvent;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Client side remote control.
 *
 * <p>The camera entity is swapped to the drone, but the mouse handler only ever knows about the
 * local player, so whatever it does to the player has to be harvested and handed to the drone
 * every frame. The player body is then pinned back to the angle it held when the link went up,
 * body and head rotations included, otherwise the player model visibly swings around while the
 * drone is being flown.
 */
public final class DroneController {

    private static float lockedYaw;
    private static float lockedPitch;

    /** Tracks the right mouse button so a held click only triggers one drone reach. */
    private static boolean useKeyWasDown;

    /**
     * Current velocity in blocks per tick, carried between ticks. This is what gives the airframe
     * weight: letting go of the keys asks for zero velocity, but the drone has to bleed the old
     * one off first, so it coasts to a stop instead of snapping.
     */
    private static Vec3 drift = Vec3.ZERO;

    /**
     * Acceleration while the pilot is asking for more, in blocks per tick squared.
     *
     * <p>Speed is wound up rather than switched on: from a standstill the drone takes
     * {@code FLIGHT_SPEED / FLIGHT_ACCEL} ticks - half a second - to reach cruise. The whole point
     * of that ramp is the feel of a heavy airframe picking up, so an earlier version that chased the
     * target velocity with a fraction-of-the-gap lerp was replaced rather than merely retuned: a
     * lerp is fastest on the first tick and slower every tick after, which reads as a snap followed
     * by a wall. A constant acceleration reads as acceleration.
     *
     * <p>The ceiling itself is untouched. {@link #FLIGHT_SPEED} is still the fastest the drone can
     * ever go, and it is enforced by clamping the velocity vector, not by trusting the ramp.
     */
    private static double FLIGHT_ACCEL = 0.030D;

    /**
     * Deceleration when the pilot lets go, in blocks per tick squared. Deliberately lower than
     * {@link #FLIGHT_ACCEL}, and that asymmetry is the momentum: the drone picks up quickly but
     * coasts for about a block and a half before it settles.
     */
    private static double FLIGHT_DECEL = 0.020D;

    /**
     * Multiplier on raw mouse movement while piloting.
     *
     * <p>Making the drone turn with less mouse travel is the other half of "easier to steer":
     * without it a full reversal costs most of a mousepad, because the movement is handed over one
     * for one. Kept modest so the crosshair still has fine control - a large gain on top of the
     * damping below turns precision aiming into guesswork.
     */
    private static double LOOK_GAIN = 1.4D;

    /**
     * Time constant of the look smoothing, in seconds.
     *
     * <p>The mouse feeds a desired heading and the drone's actual heading chases it on a first order
     * lag, which is the same shape as vanilla's own cinematic camera. A flick arrives as a fast,
     * stable sweep instead of a jolt, and the small jitter of a hand on the mouse is filtered out
     * rather than transmitted to the camera. At 0.06 s the drone closes most of a turn inside a
     * fifth of a second, so the smoothing is felt as weight rather than as input lag.
     */
    private static double LOOK_TAU = 0.06D;

    /** Heading the mouse is asking for, before the smoothing above. */
    private static float lookDesiredYaw;
    private static float lookDesiredPitch;
    /** Heading after the smoothing, which is what actually reaches the drone. */
    private static float lookYaw;
    private static float lookPitch;
    /** True once the smoothed heading has been seeded from the drone. */
    private static boolean lookSeeded;

    /**
     * Last body position reported to the server, so a body that is standing still costs no
     * traffic. {@link Vec3#ZERO} at link time doubles as "never reported yet".
     */
    private static Vec3 reportedBody = Vec3.ZERO;
    private static boolean reportedOnGround;

    // ------------------------------------------------------------------ attack run trigger

    /** How long after asking for a run before another request may be sent. */
    private static int CHARGE_REQUEST_COOLDOWN = 10;

    private static boolean sprintWasDown;
    private static int lastChargeRequest = -1000;

    // ------------------------------------------------------------------ attack run aiming

    /**
     * 冲刺瞄准的平滑时间常数，秒。用原版 F8 电影视角那套机制，见 {@link CinematicAim}。
     *
     * <p>参考值：原版电影视角的等效值随玩家灵敏度在 0.24~1.0 秒之间，0.30 略钝于其中最灵敏的
     * 那一档。调小更跟手，调大更沉稳。
     */
    private static double CHARGE_AIM_TAU = 0.30D;

    /**
     * 冲刺时鼠标灵敏度的倍率。
     *
     * <p>普通飞行走 {@link #LOOK_GAIN}（1.4），所以 1.0 已经明显比平时难转；而原版电影视角
     * 本身并不放大灵敏度（等效 1.0）。这一项就是「比电影视角更难转向」的那个旋钮。
     */
    private static double CHARGE_AIM_GAIN = 1.0D;

    /** 鼠标要求的角度。每帧都夹进锥内，免得在锥外积压出一段看不见的余量。 */
    private static float chargeDesiredYaw;
    private static float chargeDesiredPitch;
    /** 平滑后的瞄准，走电影视角那套「平滑转速而不是位置」的状态机。 */
    private static final CinematicAim chargeAimYaw = new CinematicAim();
    private static final CinematicAim chargeAimPitch = new CinematicAim();
    /** True once the aim state has been seeded for the run in progress. */
    private static boolean chargeAimSeeded;
    private static long lastFrameNanos;

    // ------------------------------------------------------------------ roller（滚筒）

    /**
     * 点按滚筒时，一圈持续多少刻。11 刻略多于半秒，快得像个动作，慢得还跟得上地平线。
     */
    private static int ROLL_TICKS = 11;

    /**
     * 两次点按滚筒之间的冷却刻数。
     *
     * <p>没有冷却时按住 C 就是一路滚下去，因为每一次按键都会往当前这一圈上再叠一圈。
     * 现在改成"一次按键 = 一圈，圈与圈之间要等"。<b>按住</b>走的是另一条路
     * （{@link #ROLLER_TURNS_PER_SECOND}），不受这个冷却管。
     */
    private static int ROLL_COOLDOWN_TICKS = 40;

    /**
     * 松开滚筒后，视角从当前滚转角回正到水平所需的刻数。
     *
     * <p>这一段是必须的，而且是这一整套改动里最容易漏掉的一环：按住滚筒时视角是连续转的，
     * 松手那一刻停在哪个角度完全取决于按了多久——可能是 47°，也可能是 313°。如果松手后
     * 就在那里停下，玩家会得到一个永久歪斜的地平线，而且每滚一次就再歪一点，再也回不到
     * 正的。所以松手之后必须有一段强制回正，把角度收敛回 0。
     */
    private static int ROLLER_RECOVER_TICKS = 8;

    /**
     * 滚筒时镜头跟随的角度比例。
     *
     * <p>1 是完全跟转 - 世界会真真切切地翻一整圈。调低这个值镜头就只跟一部分，
     * 滚转还在、但不那么晕。这是唯一一个把滚筒从"特技动作"拉向"自然一点"的旋钮。
     */
    private static double ROLL_CAMERA_SHARE = 1.0D;

    /**
     * 按住滚筒键时的旋转速度，圈/秒。
     *
     * <p>这是"滚筒"和原来的"桶滚"最本质的差别：桶滚是点一下翻一圈、有始有终的特技动作；
     * 滚筒是按住就一直绕视线轴转下去，像市面那些滚筒飞行 mod 一样，把机身拧成一个钻头
     * 一路钻过去。转的速度按秒计而不是按刻计，一是这里本来就是连续量、二是帧率无关，
     * 手感不会因为玩家的帧数变化。
     */
    private static double ROLLER_TURNS_PER_SECOND = 1.6D;

    /**
     * 滚筒冲刺时最大速度的提升比例。
     *
     * <p>0.15 即 +15%。这是滚筒真正的实用价值：它不只是好看，还是无人机唯一一个能突破
     * {@link ReconDroneEntity#FLIGHT_SPEED} 巡航上限的手段。巡航上限本身不动，冲刺是
     * 在它之上再乘一个系数——这样"最高速"依然由那个常量定义，冲刺只是它的一个倍数。
     */
    private static double ROLLER_DASH_SPEED_GAIN = 0.15D;

    /**
     * 滚筒时视场角扩大的倍率。
     *
     * <p>这是需求里那个"视角变化"：转速本身只改朝向，不改投影；把视场轻轻撑开一点，
     * 滚筒才会读成"进入了另一种飞行姿态"而不是"镜头被人拧了一下"。
     */
    private static double ROLLER_FOV_GAIN = 1.12D;

    /** 当前这一圈已经滚了多少刻，-1 表示没在滚。 */
    private static int rollElapsed = -1;
    /** 距离下一次点按滚筒可用还剩多少刻。 */
    private static int rollCooldown;
    /** 当前滚转角，0..360。 */
    private static double rollAngle;
    /** Last tick's roll, so the camera can interpolate between the two. */
    private static double rollO;

    /** 滚筒键是否按住 - 按住时持续旋转，而不是点按的一圈。 */
    private static boolean rollerHeld;
    /** 上一帧的纳秒时间戳，用来把滚筒旋转按真实时间推进而不是按刻。 */
    private static long rollerNanos;
    /** 这一帧滚筒冲刺是否生效（滚筒旋转 + 冲刺键同时按下）。 */
    private static boolean rollerDashing;
    /** 平滑后的滚筒视场角倍率，和冲刺视场角分开累加。 */
    private static double rollerFov = 1.0D;

    /**
     * 回正阶段的起始角度，松手那一刻冻结下来，回正从这里出发收到 0。
     *
     * <p>起始角记的是"从 0 出发的最短转向"，可能是负的（例如 -108°），这样回正走的是
     * 最短那条弧，而不是傻乎乎地正着转大半圈回去。点按翻整圈时这里是 +360°。
     */
    private static double ROLLER_RECOVER_FROM;

    /**
     * 当前这一段收尾要跑多少刻。
     *
     * <p>两种收尾长度不同：点按是翻满一整圈，走 {@link #ROLL_TICKS}（约半秒，像个特技动作）；
     * 松手是回正到水平，走 {@link #ROLLER_RECOVER_TICKS}（约 0.4 秒，越快越不像被卡住）。
     * 把长度存下来，而不是在推进时再判断是哪种，是为了让推进那段只读一个数。
     */
    private static int rollerFinishTicks = 8;

    // ------------------------------------------------------------------ camera lean

    /**
     * How much of the airframe's own turn lean the pilot's camera copies.
     *
     * <p>Banking the view is what makes a turn read as a turn from inside the cockpit; the model
     * has leaned into its turns since the beginning, but the pilot was looking at a level horizon
     * the whole time, which is why a hard turn felt like sliding sideways. Half of the model's lean
     * is enough to feel the roll without tilting the world far enough to make strafing a doorway
     * awkward.
     */
    private static double CAMERA_BANK_SHARE = 0.5D;

    /**
     * Which way a positive roll rotates the pilot's view.
     *
     * <p>Separate from the model's own sign, because the two frames disagree: the camera's roll is
     * handed to the projection in degrees and a positive value is a roll to the right, while the
     * model is turned with {@code Axis.ZP.rotationDegrees}, where a positive value lifts its right
     * side. The model therefore negates what this method returns and the camera does not, which is
     * the whole of the correction - and this is the one value to flip if a barrel roll ever sends
     * the horizon the wrong way.
     */
    private static final float CAMERA_ROLL_SIGN = 1.0F;

    private DroneController() {
    }

    /**
     * 客户端配置载入/热重载后，把手感与画面参数写回各自的静态镜像。
     *
     * <p>由 {@code WatchcraftClient} 在 {@code ModConfigEvent} 上调用。为什么不直接在使用处读配置，
     * 见 {@code WatchcraftConfig} 的类注释：{@code ConfigValue#get()} 在配置载入前会抛异常。
     */
    public static void applyConfig() {
        var client = dev.watchcraft.watchcraft.config.WatchcraftConfig.CLIENT;
        FLIGHT_ACCEL = client.flightAcceleration.get();
        FLIGHT_DECEL = client.flightDeceleration.get();
        LOOK_GAIN = client.lookGain.get();
        LOOK_TAU = client.lookSmoothing.get();
        ROLL_TICKS = client.rollTicks.get();
        ROLL_COOLDOWN_TICKS = client.rollCooldownTicks.get();
        ROLLER_RECOVER_TICKS = client.rollerRecoverTicks.get();
        ROLL_CAMERA_SHARE = client.rollCameraShare.get();
        ROLLER_TURNS_PER_SECOND = client.rollerTurnsPerSecond.get();
        ROLLER_DASH_SPEED_GAIN = client.rollerDashSpeedGain.get();
        ROLLER_FOV_GAIN = client.rollerFovGain.get();
        CAMERA_BANK_SHARE = client.cameraBankShare.get();
        SPEED_FOV_GAIN = client.speedFovGain.get();
        SPEED_FOV_EASE = client.speedFovEase.get();
        CHARGE_FOV_GAIN = client.chargeFovGain.get();
        CHARGE_FOV_EASE = client.chargeFovEase.get();
        CHARGE_REQUEST_COOLDOWN = client.chargeRequestCooldown.get();
        CHARGE_AIM_TAU = client.chargeAimTau.get();
        CHARGE_AIM_GAIN = client.chargeAimGain.get();
    }

    public static boolean isLinked() {
        return DroneControlState.pilotedDroneId >= 0;
    }

    public static int getLinkedId() {
        return DroneControlState.pilotedDroneId;
    }

    public static void link(int droneId) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null || minecraft.player == null) {
            return;
        }
        Entity entity = minecraft.level.getEntity(droneId);
        if (!(entity instanceof ReconDroneEntity drone)) {
            return;
        }
        DroneControlState.pilotedDroneId = droneId;
        lockedYaw = minecraft.player.getYRot();
        lockedPitch = minecraft.player.getXRot();
        useKeyWasDown = false;
        sprintWasDown = minecraft.options.keySprint.isDown();
        lastChargeRequest = -1000;
        // Linking in mid sprint leaves the body carrying its own momentum, so shed it up front
        // rather than letting the player skid on for half a second after the camera has left.
        holdPlayer(minecraft.player);
        // Force the first body report: the server still believes the body is where it was.
        reportedBody = Vec3.ZERO;
        reportedOnGround = false;
        resetFlight();
        // 模式变了（定位音 ⇄ 驾驶舱音），让旋翼声立刻重建，别等轮询。
        DroneSounds.refresh();
        // Linking in from behind an open inventory would leave it sitting over the visor forever.
        if (minecraft.screen != null) {
            minecraft.setScreen(null);
        }
        minecraft.setCameraEntity(drone);
    }

    public static void unlink() {
        Minecraft minecraft = Minecraft.getInstance();
        DroneControlState.reset();
        useKeyWasDown = false;
        sprintWasDown = false;
        lastChargeRequest = -1000;
        resetFlight();
        DroneSounds.refresh();
        if (minecraft.player != null) {
            minecraft.setCameraEntity(minecraft.player);
        }
    }

    /** Called every client tick, before rendering. */
    public static void tick() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || minecraft.level == null) {
            if (isLinked()) {
                unlink();
            }
            return;
        }

        if (!minecraft.options.keyUse.isDown()) {
            useKeyWasDown = false;
        }

        handleKeys(minecraft);
        ClientPayloadHandler.verifyLink();

        if (!isLinked()) {
            return;
        }
        Entity camera = minecraft.getCameraEntity();
        if (!(camera instanceof ReconDroneEntity drone)) {
            unlink();
            return;
        }

        // 引爆后的雪花屏阶段：机体已经交给服务端冻结，这边什么都不用做，只要把身体按住、
        // 别再发位移包。镜头仍然停在无人机上，这正是那两秒雪花屏得以成立的原因。
        if (drone.isDetonated()) {
            holdPlayer(minecraft.player);
            return;
        }

        // 回收期间机体归服务端冻结。这边必须跟着停手：继续跑 fly() 只会移动本地那一份，
        // 服务端那份纹丝不动，三秒后链路一断镜头就会从别处弹回去。同样不再发位移包，
        // 免得服务端收到一堆它已经不需要的坐标。
        if (drone.isRecalling()) {
            drift = Vec3.ZERO;
            holdPlayer(minecraft.player);
            return;
        }

        // 没装电池的机体不响应操控：油门和方向盘一起断掉。syncLook 也不再调用，所以镜头
        // 连转都转不动 —— 玩家面对的是一片雪花，而且动不了，这正是要传达的"这架机体现没用"。
        // 想装电池就先按 V 断开链路，装电池本来就不该从驾驶座里做。
        if (!drone.hasBattery()) {
            drift = Vec3.ZERO;
            holdPlayer(minecraft.player);
            return;
        }

        syncLook();
        holdPlayer(minecraft.player);
        reportBodyPosition(minecraft, minecraft.player);

        boolean sprint = minecraft.options.keySprint.isDown();
        // 滚筒要排在冲刺之前推进：它是冲刺的判据（rollerDashing），后面处理引爆触发与飞行时
        // 都要读这一帧的结果，顺序反了这一帧就会用到上一帧的冲刺状态。
        // 同时它也要排在 isCharging 分支之前，这样一次已提交的冲刺不会把滚筒卡住。
        updateRoller(minecraft, drone, sprint);

        // A run is the server's to fly, but not the pilot's to stop aiming: the position in this
        // packet is ignored on arrival, the rotation is not. That split is the whole steering
        // model - the server owns where the airframe is, the pilot owns which way it points, and
        // the server clamps that to the cone before it acts on it.
        if (drone.isCharging()) {
            drift = Vec3.ZERO;
            sprintWasDown = sprint;
            sendMove(drone);
            return;
        }

        handleChargeTrigger(minecraft, drone, sprint);
        fly(minecraft, drone);
        sendMove(drone);
    }

    /**
     * {@return 从 {@code degrees} 转回 0 的最短转向，范围 -180..180}
     *
     * <p>回正必须走最短弧：松手停在 300° 时，转回去既可以是 -300°（倒着抹一圈多）也可以是
     * +60°（顺着收一点）。后者才是"回正"该有的样子，前者看起来像又滚了一圈。
     */
    private static double shortestTurn(double degrees) {
        return Mth.wrapDegrees((float) degrees);
    }

    private static void sendMove(ReconDroneEntity drone) {
        PacketDistributor.sendToServer(new DroneMovePayload(
                drone.getId(),
                drone.getX(),
                drone.getY(),
                drone.getZ(),
                drone.getYRot(),
                drone.getXRot(),
                (float) rollAngle));
    }

    /**
     * Watches for the attack input: a fresh press of sprint.
     *
     * <p>Edge triggered, and that matters more than it looks. Sprint defaults to left control,
     * which is a key people rest a finger on; reading it as a level rather than as a press would
     * fire the warhead the moment the pilot leaned on it.
     *
     * <p>There used to be a second trigger - a double tap of forward - and it is gone. With the
     * run no longer a stoop, forward is the axis the pilot is about to lock, and tapping it twice
     * to arm a warhead put a destructive action on the same key that flies the drone.
     *
     * <p>The whole trigger is skipped unless the airframe actually carries a warhead, so a drone
     * without one cannot even send the request.
     *
     * <p>滚筒冲刺期间也整个跳过：需求里"滚筒冲刺时不会自爆"就落在这里。冲刺键同时是引爆的
     * 触发键，如果不挡，一次滚筒冲刺会在起手那一帧顺手把弹头点着。这里把上升沿直接吞掉，
     * 松手之后再按一次才是真的请求引爆。
     */
    private static void handleChargeTrigger(Minecraft minecraft, ReconDroneEntity drone, boolean sprint) {
        if (rollerDashing) {
            sprintWasDown = sprint;
            return;
        }
        if (!DroneModules.has(drone.getModules(), DroneModules.ATTACK)) {
            sprintWasDown = sprint;
            return;
        }
        if (sprint && !sprintWasDown) {
            requestCharge(drone.tickCount);
        }
        sprintWasDown = sprint;
    }

    private static void requestCharge(int now) {
        if (now - lastChargeRequest < CHARGE_REQUEST_COOLDOWN) {
            return;
        }
        lastChargeRequest = now;
        PacketDistributor.sendToServer(
                new DroneActionPayload(DroneActionPayload.ACTION_CHARGE, getLinkedId()));
    }

    // ------------------------------------------------------------------ attack run fov

    /**
     * How much wider the view opens up during a run. Thirty percent is enough to read as being
     * hurled forward without the projection distorting at the edges of a wide monitor.
     */
    private static double CHARGE_FOV_GAIN = 1.30D;

    /**
     * Fraction of the remaining gap to the target gain closed each frame.
     *
     * <p>Frame paced rather than tick paced, because the hook it feeds is per frame. At 60 fps this
     * arrives in about a fifth of a second, which reads as the view being yanked open; the same
     * factor at 20 fps takes half a second, which still reads as a pull rather than a cut. Easing
     * is worth the two numbers it costs: snapping the FOV on the tick the warhead arms looks like
     * a rendering fault rather than like acceleration.
     */
    private static double CHARGE_FOV_EASE = 0.15D;

    /** Smoothed multiplier handed to the FOV hook. Neutral when nothing is charging. */
    private static double chargeFov = 1.0D;

    // ------------------------------------------------------------------ speed fov

    /**
     * How much wider the view sits at full cruise.
     *
     * <p>The speed readout for a drone that has no speedometer: the projection opens as the airframe
     * picks up and closes again as it coasts to a stop, so how fast the drone is going is something
     * the pilot can see rather than something they have to infer. Eighteen percent is a fifth of the
     * charge pull, which keeps the two readable as different events - a steady opening while flying
     * and a hard yank when the warhead arms.
     */
    private static double SPEED_FOV_GAIN = 0.18D;

    /**
     * Fraction of the remaining gap to the target speed FOV closed each frame.
     *
     * <p>Slower than the charge ease on purpose. This one tracks a quantity that itself ramps over
     * half a second, and a fast ease on top of that would simply mirror the acceleration ramp; easing
     * it more slowly lets the opening lag the wind up slightly, which is what makes it read as the
     * view being dragged open by the speed rather than as a number being animated.
     */
    private static double SPEED_FOV_EASE = 0.08D;

    /** Smoothed speed multiplier for the FOV hook. Neutral while the drone is parked. */
    private static double speedFov = 1.0D;

    /**
     * Drives the view effects toward their targets, once per frame.
     *
     * <p>The charge flag is read off the drone the camera is actually on, not off the link state, so
     * a run that ends because the airframe was shot down eases back out exactly like one that
     * ended on impact. The speed term is read off the live velocity, so it decays with the coast
     * rather than cutting when the keys are released.
     */
    public static void tickFov() {
        ReconDroneEntity camera = Minecraft.getInstance().getCameraEntity() instanceof ReconDroneEntity drone
                ? drone
                : null;
        boolean charging = camera != null && camera.isCharging();
        double chargeTarget = charging ? CHARGE_FOV_GAIN : 1.0D;
        chargeFov += (chargeTarget - chargeFov) * CHARGE_FOV_EASE;
        if (Math.abs(chargeFov - 1.0D) < 1.0E-3D) {
            chargeFov = 1.0D;
        }

        // 按"当前速度占这架机体自己巡航上限的比例"算，而不是占基础巡航上限：装了速度解限模块的
        // 机体上限更高，拿基础值当分母会在远没到顶速时就把视场角撑满。没有模块时两者相等，
        // 所以这一改对原有手感是零影响。
        double speed = camera != null && isLinked() ? drift.length() / camera.flightSpeed() : 0.0D;
        double speedTarget = 1.0D + SPEED_FOV_GAIN * Mth.clamp(speed, 0.0D, 1.0D);
        speedFov += (speedTarget - speedFov) * SPEED_FOV_EASE;
        if (Math.abs(speedFov - 1.0D) < 1.0E-3D) {
            speedFov = 1.0D;
        }

        // 滚筒的视角变化：按同一套缓动收敛，进出滚筒都不会硬切。用冲刺那档速度的缓动，
        // 因为它和冲刺一样是"进入某个姿态"而不是"随速度漂移"，得快一点才读得出。
        double rollerTarget = rollerHeld && isLinked() ? ROLLER_FOV_GAIN : 1.0D;
        rollerFov += (rollerTarget - rollerFov) * CHARGE_FOV_EASE;
        if (Math.abs(rollerFov - 1.0D) < 1.0E-3D) {
            rollerFov = 1.0D;
        }
    }

    /** {@return the multiplier the FOV hook should apply to the projection this frame} */
    public static double viewFovScale() {
        return chargeFov * speedFov * rollerFov;
    }

    /** {@return 滚筒冲刺是否正生效（供 HUD 显示 / 其它效果判断用）} */
    public static boolean isRollerDashing() {
        return rollerDashing;
    }

    /** {@return 滚筒是否正被按住} */
    public static boolean isRollerActive() {
        return rollerHeld;
    }

    /** {@return 当前巡航上限倍率。滚筒冲刺时是 1 + 配置增益，否则为 1} */
    public static double speedCapMultiplier() {
        return rollerDashing ? 1.0D + ROLLER_DASH_SPEED_GAIN : 1.0D;
    }

    /**
     * {@return the roll the pilot's camera should carry this frame, in degrees}
     *
     * <p>Two things ride on it, and they add up: the barrel roll the pilot asked for, and a share of
     * the lean the airframe is already applying to itself. The second is what a pilot means by
     * "the view changed when I turned" - the airframe has banked into turns since the beginning, but
     * only for the people watching it.
     *
     * <p>The airframe's lean is taken at face value here rather than negated the way the renderer
     * negates it: a right hand turn produces a positive lean, and a positive roll is a roll to the
     * right in both frames once each side's own sign convention is accounted for.
     *
     * <p>Zero whenever the camera is not on a piloted drone, so the roll cannot leak into ordinary
     * play or onto a drone that is merely being watched.
     */
    public static float cameraRoll(double partialTick) {
        if (!(Minecraft.getInstance().getCameraEntity() instanceof ReconDroneEntity drone)
                || !drone.isPiloted()) {
            return 0.0F;
        }
        double lean = drone.getBank((float) partialTick) * CAMERA_BANK_SHARE;
        return CAMERA_ROLL_SIGN * (float) (lean + interpolatedRoll(partialTick) * ROLL_CAMERA_SHARE);
    }

    /**
     * {@return the roll to draw this frame, interpolated between the last two ticks}
     *
     * <p>Wrap aware, and that matters: a roll finishes by folding 359 back to 0, which is the same
     * orientation but not the same number, and a plain lerp across that seam would read the last
     * degree of the roll as a full turn back the other way.
     */
    private static double interpolatedRoll(double partialTick) {
        double delta = Mth.wrapDegrees((float) (rollAngle - rollO));
        return ReconDroneEntity.wrapRoll((float) (rollO + delta * partialTick));
    }

    // ------------------------------------------------------------------ roller

    /**
     * 滚筒主循环：按住持续旋转、松手回正，以及由它带出的冲刺。
     *
     * <p>三条路，互斥：
     *
     * <ul>
     *   <li><b>按住。</b>绕视线轴以 {@link #ROLLER_TURNS_PER_SECOND} 的角速度连续转下去，
     *       想转多久转多久。时间基准用真实纳秒而不是刻，因为 {@link KeyMapping} 的按住状态
     *       在帧之间是连续的，按刻推进会在帧率与刻率不一致时抖。</li>
     *   <li><b>松手 → 回正。</b>从当前角度按最短弧收敛回 0，走 {@link #ROLLER_RECOVER_TICKS}。
     *       <b>这一段是必须的</b>：按住结束在哪个角度是随机的，不回正就会留下一个永久歪斜的
     *       地平线，而且每滚一次再歪一点。走到 0 才是"恢复正常"。</li>
     *   <li><b>点按。</b>没跨过任何一刻的"按住"，视为一次独立的特技动作：从当前角度翻满一整圈
     *       （起点 360°，终点 0°），走 {@link #ROLL_TICKS}。整圈的语义在这里保留，因为它本来
     *       就是"有始有终的翻一圈"，和按住的连续旋转是两件事。</li>
     * </ul>
     *
     * <p>冲刺的判据是<b>滚筒旋转 + 冲刺键同时成立</b>。之所以要同时按，是因为滚筒本身
     * 就是一个姿态、谁都能进，而冲刺是它的一个加速档，单按冲刺键在别的地方已经有意义
     * （原版疾跑），不该和滚筒混在一起。
     */
    private static void updateRoller(Minecraft minecraft, ReconDroneEntity drone, boolean sprint) {
        boolean down = isLinked() && minecraft.screen == null && KeyMappings.ROLL.isDown();

        // consumeClick() 抓的是"两刻之间按下过"，isDown() 抓的是"这一刻还按着"。极快的一点
        // 可能整个发生在两刻之间，isDown 采样不到，但点击计数会 +1。所以点按这条路用计数补一刀：
        // 既保证短促点按不丢，又不会和按住那条路重复触发（按住时 down 为真，先走上面那支）。
        boolean tapped = false;
        while (KeyMappings.ROLL.consumeClick()) {
            tapped = isLinked() && minecraft.screen == null;
        }

        long now = System.nanoTime();
        double delta = rollerNanos == 0L ? 0.0D : (now - rollerNanos) / 1.0E9D;
        rollerNanos = now;
        if (delta < 0.0D || delta > 0.5D) {
            // 掉帧或从暂停里回来：夹一下，否则一帧能拧出好几圈。
            delta = 1.0D / 20.0D;
        }

        if (down) {
            // 按住优先于任何残留：从当前角度接着转，顺手把回正状态清掉。
            rollerHeld = true;
            rollElapsed = -1;
            double spin = 360.0D * ROLLER_TURNS_PER_SECOND * delta;
            rollO = rollAngle;
            rollAngle = ReconDroneEntity.wrapRoll((float) (rollAngle + spin));
        } else if (rollerHeld) {
            // 刚松手：进入回正。起点取"从 0 出发的最短转向"，回正走最短弧。
            rollerHeld = false;
            rollElapsed = 0;
            rollerFinishTicks = ROLLER_RECOVER_TICKS;
            ROLLER_RECOVER_FROM = shortestTurn(rollAngle);
            rollO = rollAngle;
        } else if (tapped && rollElapsed < 0 && rollCooldown <= 0) {
            // 纯点按（没跨过任何一刻的"按住"）：翻一整圈。这里保持"整圈"的语义，
            // 因为点按是一个有始有终的特技动作，转满一圈回到原位才是它该有的样子。
            rollElapsed = 0;
            rollerFinishTicks = ROLL_TICKS;
            ROLLER_RECOVER_FROM = 360.0D;
            rollO = rollAngle;
        } else if (rollElapsed >= 0) {
            rollO = rollAngle;
            rollElapsed++;
            double progress = Math.min(1.0D, rollElapsed / (double) rollerFinishTicks);
            double eased = progress * progress * (3.0D - 2.0D * progress);
            // 从起点（点按是 360°，松手是当前角的最短转向）走到 0。
            // 走到 0 就是水平，这一步是整个回正的意义所在。
            rollAngle = ReconDroneEntity.wrapRoll((float) (ROLLER_RECOVER_FROM * (1.0D - eased)));
            if (progress >= 1.0D) {
                rollElapsed = -1;
                rollAngle = 0.0D;
                rollO = 0.0D;
                // 收尾走完才进冷却；按住那条路不进，否则一松手就锁上，接着按就没了。
                rollCooldown = ROLL_COOLDOWN_TICKS;
            }
        }

        if (rollCooldown > 0 && !rollerHeld && rollElapsed < 0) {
            rollCooldown--;
        }

        // 冲刺：滚筒 + 冲刺键。只在真的在滚的时候算数，不然单按疾跑也会触发。
        // 完全留在客户端：它只改这一侧的速度上限与视场角，服务端那边 FLIGHT_SPEED 依旧
        // 是巡航上限、MAX_STEP 也宽得很（1.8 格/包，冲刺时每刻才 0.276 格），不需要为它
        // 多做一份同步状态。
        rollerDashing = rollerHeld && sprint;
    }

    /**
     * {@return how far into the charge effect the visor is, from 0 (none) to 1 (full)}
     *
     * <p>Read by the screen-space half of the effect so the speed streaks and the FOV pull are
     * driven by one ramp and cannot come in or out of step with each other.
     */
    public static double chargeEffectIntensity() {
        return Mth.clamp((chargeFov - 1.0D) / (CHARGE_FOV_GAIN - 1.0D), 0.0D, 1.0D);
    }

    private static void handleKeys(Minecraft minecraft) {
        while (KeyMappings.LINK.consumeClick()) {
            if (minecraft.screen != null) {
                continue;
            }
            if (isLinked()) {
                PacketDistributor.sendToServer(
                        new DroneActionPayload(DroneActionPayload.ACTION_EXIT, getLinkedId()));
            } else {
                PacketDistributor.sendToServer(
                        new DroneActionPayload(DroneActionPayload.ACTION_ENTER, -1));
            }
        }
        while (KeyMappings.THROW.consumeClick()) {
            if (minecraft.screen == null) {
                PacketDistributor.sendToServer(
                        new DroneActionPayload(DroneActionPayload.ACTION_THROW, -1));
            }
        }
        while (KeyMappings.RECALL.consumeClick()) {
            if (minecraft.screen == null) {
                PacketDistributor.sendToServer(
                        new DroneActionPayload(DroneActionPayload.ACTION_RECALL, -1));
            }
        }
        // 滚筒（ROLL）的点击计数不在这里消费：它由 updateRoller 自己 drain，因为"点按"与
        // "按住"两种模式必须由同一处判断。这里多消费一次会让那边永远读不到点按。
        while (KeyMappings.DETONATE.consumeClick()) {
            // 手动引爆。是否真的能炸由服务端说了算 - 这里只负责把意图送过去。
            if (isLinked() && minecraft.screen == null) {
                PacketDistributor.sendToServer(
                        new DroneActionPayload(DroneActionPayload.ACTION_DETONATE, getLinkedId()));
            }
        }
    }

    /**
     * Moves the mouse delta the player just received onto the drone, then pins the player back.
     *
     * <p>Run once per frame as well as once per tick. The mouse handler rotates the player between
     * ticks, so sampling it only at tick rate would leave the drone's view stepping at 20 Hz, and it
     * is what lets the smoothing in {@link #steerLook} advance at frame rate rather than at tick
     * rate.
     */
    public static void syncLook() {
        Minecraft minecraft = Minecraft.getInstance();
        LocalPlayer player = minecraft.player;
        if (player == null || !isLinked()) {
            return;
        }
        Entity camera = minecraft.getCameraEntity();
        if (!(camera instanceof ReconDroneEntity drone)) {
            return;
        }

        float deltaYaw = Mth.wrapDegrees(player.getYRot() - lockedYaw);
        float deltaPitch = player.getXRot() - lockedPitch;

        if (drone.isCharging()) {
            steerRun(drone, deltaYaw, deltaPitch);
        } else {
            chargeAimSeeded = false;
            steerLook(drone, deltaYaw, deltaPitch);
        }

        // The body and head angles chase the yaw over several ticks, so pinning the yaw alone still
        // lets the player model turn. All four have to be held down, old values included.
        player.setYRot(lockedYaw);
        player.setXRot(lockedPitch);
        player.yRotO = lockedYaw;
        player.xRotO = lockedPitch;
        player.yBodyRot = lockedYaw;
        player.yBodyRotO = lockedYaw;
        player.yHeadRot = lockedYaw;
        player.yHeadRotO = lockedYaw;
    }

    /**
     * Steers the drone from the mouse, smoothed and amplified.
     *
     * <p>Runs on every frame whether or not the mouse moved, because the smoothing has to be allowed
     * to finish converging after the last input; skipping it on quiet frames would leave the
     * heading parked wherever the final mouse event happened to drop it.
     *
     * <p>The gap between where the mouse is asking to look and where the drone is actually looking
     * is closed on a first order lag, so the size of one frame's step does not depend on the frame
     * rate. Two details are load bearing. The <em>desired</em> heading accumulates the mouse movement
     * rather than being read off the player each frame, because the player's own rotation is pinned
     * back to the locked angle at the end of this method and would otherwise report the same delta
     * forever. And the pitch is clamped on the way in, so the desired heading cannot wind up behind
     * the horizon and then drag the nose down when the mouse comes back.
     */
    private static void steerLook(ReconDroneEntity drone, float deltaYaw, float deltaPitch) {
        if (!lookSeeded) {
            lookSeeded = true;
            // Discard any frame gap accumulated since the last link; the seed frame is a full jump.
            lastFrameNanos = 0L;
            lookDesiredYaw = drone.getYRot();
            lookDesiredPitch = drone.getXRot();
            lookYaw = lookDesiredYaw;
            lookPitch = lookDesiredPitch;
        }

        if (deltaYaw != 0.0F || deltaPitch != 0.0F) {
            lookDesiredYaw = Mth.wrapDegrees(lookDesiredYaw + deltaYaw * (float) LOOK_GAIN);
            lookDesiredPitch = Mth.clamp(lookDesiredPitch + deltaPitch * (float) LOOK_GAIN,
                    -90.0F, 90.0F);
        }

        double k = 1.0D - Math.exp(-frameSeconds() / LOOK_TAU);
        lookYaw = Mth.wrapDegrees(lookYaw + (float) (Mth.wrapDegrees(lookDesiredYaw - lookYaw) * k));
        lookPitch += (float) ((lookDesiredPitch - lookPitch) * k);

        drone.setYRot(lookYaw);
        drone.setXRot(lookPitch);
        drone.yRotO = lookYaw;
        drone.xRotO = lookPitch;
    }

    /**
     * Steers the attack run inside its fixed cone, every frame, including quiet frames.
     *
     * <p>Uses vanilla's own cinematic-camera smoother ({@link CinematicAim}). Two earlier attempts
     * are worth recording, because both felt wrong for the same underlying reason:
     *
     * <ul>
     *   <li>A <b>first-order position lerp</b> makes the angular velocity proportional to the
     *       remaining gap, so it is at its maximum on the very first frame of any input and decays
     *       from there. Every small correction reads as a lurch followed by a coast.</li>
     *   <li>A <b>critically damped spring</b> fixed the shape but starts far too gently - a tiny
     *       nudge moved barely one percent of the way on the first frame - so it felt dead and
     *       unresponsive while still being "smooth".</li>
     * </ul>
     *
     * <p>The cinematic smoother avoids both by smoothing the <em>rate</em> rather than the position.
     * Its steady-state gain is exactly one: hold the mouse moving and the view ends up turning at
     * exactly the rate the mouse asks for, so nothing is lost to the filter. Only changes are
     * rounded off. That is why it manages to feel smooth and responsive at the same time.
     *
     * <p>The mouse target is clamped into the cone every frame, and the smoothed angle is written
     * back after clamping too. Clamping only the output would let the internal state accumulate an
     * invisible backlog outside the cone, and pulling back would then appear to do nothing until
     * that backlog unwound.
     */
    private static void steerRun(ReconDroneEntity drone, float deltaYaw, float deltaPitch) {
        // When the run ends ordinary steering must seed from the current view, not its pre-run aim.
        lookSeeded = false;
        if (!chargeAimSeeded) {
            chargeAimSeeded = true;
            lastFrameNanos = 0L;
            chargeDesiredYaw = drone.getYRot();
            chargeDesiredPitch = drone.getXRot();
            chargeAimYaw.reset(chargeDesiredYaw);
            chargeAimPitch.reset(chargeDesiredPitch);
        }

        Vec3 axis = drone.chargeAxis();
        Vec3 requested = ReconDroneEntity.clampToCone(axis,
                ReconDroneEntity.viewVector(
                        Mth.wrapDegrees(chargeDesiredYaw + deltaYaw * (float) CHARGE_AIM_GAIN),
                        Mth.clamp(chargeDesiredPitch + deltaPitch * (float) CHARGE_AIM_GAIN,
                                -90.0F, 90.0F)),
                ReconDroneEntity.CHARGE_CONE);
        chargeDesiredYaw = ReconDroneEntity.yawOf(requested);
        chargeDesiredPitch = ReconDroneEntity.pitchOf(requested);

        double dt = frameSeconds();
        double yaw = chargeAimYaw.advance(chargeDesiredYaw, dt, CHARGE_AIM_TAU);
        double pitch = chargeAimPitch.advance(chargeDesiredPitch, dt, CHARGE_AIM_TAU);

        // The target is already inside the cone, so this only catches the smoother still converging
        // from wherever the previous frame left it. Writing the result back keeps the internal state
        // and the crosshair on the same number.
        Vec3 clamped = ReconDroneEntity.clampToCone(axis,
                ReconDroneEntity.viewVector((float) yaw, (float) pitch),
                ReconDroneEntity.CHARGE_CONE);
        yaw = ReconDroneEntity.yawOf(clamped);
        pitch = ReconDroneEntity.pitchOf(clamped);
        chargeAimYaw.setAngle(yaw);
        chargeAimPitch.setAngle(pitch);

        drone.setYRot((float) yaw);
        drone.setXRot((float) pitch);
        drone.yRotO = (float) yaw;
        drone.xRotO = (float) pitch;
    }

    /**
     * Vanilla's F8 cinematic-camera smoother, lifted from
     * {@code net.minecraft.util.SmoothDouble#getNewDeltaValue} with two changes: the state is an
     * absolute angle rather than an accumulated delta, so the caller can clamp the target into a
     * cone each frame; and vanilla's sensitivity-derived {@code deltaTime} is written explicitly as
     * a time constant, so the feel can be tuned without touching the player's mouse settings.
     *
     * <p>The shape is what matters. The rate is blended halfway toward the remaining gap, and the
     * previous rate is blended into it, so motion starts at about half speed and accelerates into
     * the requested rate instead of jumping to it. Because it integrates a rate rather than chasing
     * a position, the steady-state output equals the input exactly - there is no sensitivity loss
     * to pay for the smoothing.
     *
     * <p>For reference, vanilla's own equivalent time constant is {@code 1 / d4} where
     * {@code d4 = ((sensitivity * 0.6 + 0.2) ^ 3) * 8}, which works out to between roughly 0.24
     * seconds at maximum sensitivity and 2.9 seconds at minimum. The charge ships at 0.30, a little
     * heavier than vanilla's most responsive end.
     */
    private static final class CinematicAim {
        private double target;
        private double delivered;
        private double lastRate;

        void reset(double angle) {
            this.target = angle;
            this.delivered = angle;
            this.lastRate = 0.0D;
        }

        /** 外部把夹过的值写回，免得内部状态继续往锥外走。 */
        void setAngle(double angle) {
            this.delivered = angle;
        }

        double advance(double desired, double dt, double tau) {
            this.target = desired;
            double gap = this.target - this.delivered;
            double rate = 0.5D * this.lastRate + 0.5D * gap;
            double sign = Math.signum(gap);
            if (sign * gap > sign * this.lastRate) {
                gap = rate;
            }
            this.lastRate = rate;
            // Cap the per-frame fraction below one. With a small tau and a dropped frame, stepping
            // the whole gap in one go would overshoot the target and read as a wobble.
            this.delivered += gap * Math.min(0.5D, dt / tau);
            return this.delivered;
        }
    }

    /**
     * {@return seconds since the previous frame, clamped to something sane}
     *
     * <p>Measured here rather than taken from the render event so the damping cannot be thrown off
     * by an alt tab, a paused window or a loading hitch. The upper clamp matters as much as the
     * lower one: one enormous frame would otherwise close the entire gap in a single step, undoing
     * the smoothing at exactly the moment the frame rate is worst.
     */
    private static double frameSeconds() {
        long now = System.nanoTime();
        double seconds = lastFrameNanos == 0L ? 1.0D / 60.0D : (now - lastFrameNanos) / 1.0E9D;
        lastFrameNanos = now;
        return Mth.clamp(seconds, 1.0E-4D, 0.1D);
    }

    /**
     * {@return true on the first right click of a hold, false for the auto repeat}
     *
     * <p>Vanilla keeps calling {@code startUseItem} every four ticks while the button stays down,
     * which would otherwise flip a door open and shut several times a second.
     */
    public static boolean beginInteract() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.options.keyUse.isDown() && useKeyWasDown) {
            return false;
        }
        useKeyWasDown = true;
        return true;
    }

    /** Asks the server to let the drone work whatever its own crosshair is pointing at. */
    public static void requestInteract() {
        if (!isLinked()) {
            return;
        }
        PacketDistributor.sendToServer(
                new DroneActionPayload(DroneActionPayload.ACTION_INTERACT, getLinkedId()));
    }

    private static void resetFlight() {
        drift = Vec3.ZERO;
        // Linking out mid run has to drop the aim state too, or the next run seeds from a stale
        // angle and the crosshair snaps on its first frame. The look smoothing and any roll in
        // flight are dropped for the same reason: they belong to the link, not to the drone.
        chargeAimSeeded = false;
        lookSeeded = false;
        rollAngle = 0.0D;
        rollO = 0.0D;
        rollElapsed = -1;
        rollCooldown = 0;
        ROLLER_RECOVER_FROM = 0.0D;
        rollerFinishTicks = ROLLER_RECOVER_TICKS;
        rollerHeld = false;
        rollerDashing = false;
        rollerNanos = 0L;
        rollerFov = 1.0D;
        speedFov = 1.0D;
    }

    /**
     * Parks the operator's body for as long as the link is up.
     *
     * <p>Zeroing the movement input is not enough on its own. A player who links in mid sprint
     * still carries their momentum and skids on for another half second, anyone who links in mid
     * jump keeps falling, and anyone who links in mid walk keeps walking - in every case the body
     * visibly keeps going on its own while the pilot is looking somewhere else entirely.
     *
     * <p>So the body is parked outright: sprint cancelled, sideways drift dropped, fall distance
     * cleared. Three things here are load bearing and all three are easy to get wrong:
     *
     * <ul>
     *   <li><b>The input triple is cleared here, not through the movement input event.</b>
     *       {@code LocalPlayer#serverAiStep} only copies the input into {@code xxa}/{@code zza}
     *       while the camera is on the player, so with the camera on the drone they keep whatever
     *       was held at link time. {@code LivingEntity#aiStep} then decays them by 0.98 a tick and
     *       hands them to {@code travel}, which walks the body off gently in that direction for a
     *       few seconds before it fades out. Clearing the event's copy of the input does not help:
     *       with the camera away that copy is never read.</li>
     *   <li><b>The vertical velocity is left alone.</b> Gravity only adds 0.078 blocks per tick,
     *       so cancelling the velocity every tick cancels the acceleration with it: the body
     *       would sink at a steady walking pace instead of falling, which reads as floating.
     *       Clearing the fall distance above is what makes the landing free, so the drop does not
     *       need to be slowed down in the first place.</li>
     *   <li><b>The position is never written.</b> An earlier version pinned the body onto the
     *       spot it landed on, and the pin engaged at exactly the tick {@code onGround} flipped -
     *       fighting the landing while the physics was still resolving it, which showed up as a
     *       stutter the moment the body touched down. The pin bought nothing anyway: with no
     *       input and no sideways velocity there is nothing left to move the body.</li>
     * </ul>
     */
    private static void holdPlayer(LocalPlayer player) {
        player.fallDistance = 0.0F;
        player.setSprinting(false);
        player.setJumping(false);

        // Otherwise the body keeps walking: see the notes above.
        player.xxa = 0.0F;
        player.yya = 0.0F;
        player.zza = 0.0F;

        // Keep whatever gravity has already built up; drop everything sideways. On the ground the
        // collision has already flattened the vertical component to zero, so this is a no-op there
        // and a pure anti-skid elsewhere.
        Vec3 motion = player.getDeltaMovement();
        player.setDeltaMovement(0.0D, motion.y, 0.0D);
    }

    /**
     * Keeps the server's copy of the body in step with this one while the camera is away.
     *
     * <p>Vanilla stops reporting a player's position the moment the camera is put on something
     * else: {@code LocalPlayer#sendPosition} sits entirely inside an {@code isControlledCamera()}
     * check, which is false for as long as the drone has the camera. That is normally invisible,
     * because the body normally does not move either. But the body is deliberately left to fall
     * here, so without this the whole drop would only ever be reported once - on the tick the
     * pilot leaves - as a single jump from the parked spot to the landing spot. The server replays
     * that jump through its own collision and fall damage, which means a drop the server never saw
     * happening is charged in full the instant the pilot dismounts, and on a remote server the
     * per-tick speed check can bounce the player back to where they took off. One small packet per
     * tick while the body is actually moving keeps both sides honest.
     *
     * <p>Reporting is safe to do here because the checks the server runs on it all pass for a body
     * that is falling or standing: the anti-fly check only trips when the body reports no downward
     * movement while having no block within half a block underneath, which is exactly what a body
     * in free fall never looks like.
     */
    private static void reportBodyPosition(Minecraft minecraft, LocalPlayer player) {
        if (minecraft.getConnection() == null) {
            return;
        }
        boolean moved = player.position().distanceToSqr(reportedBody) > 4.0E-8D;
        if (!moved && player.onGround() == reportedOnGround) {
            return;
        }
        reportedBody = player.position();
        reportedOnGround = player.onGround();
        minecraft.getConnection().send(new ServerboundMovePlayerPacket.PosRot(
                player.getX(), player.getY(), player.getZ(),
                player.getYRot(), player.getXRot(), player.onGround()));
    }

    /**
     * Flies the drone relative to where its camera is pointing, then carries the velocity over
     * between ticks.
     *
     * <p>Two things are load bearing here:
     *
     * <ul>
     *   <li><b>The view only sets the direction, never the speed.</b> Forward is the full view
     *       vector with pitch included, so aiming at the sky and holding forward climbs and
     *       aiming at the ground descends. But with no key held the direction is zero, so looking
     *       around a parked drone does not budge it - which is the whole point of flying it like
     *       a recon drone rather than a helicopter.</li>
     *   <li><b>Velocity, not displacement.</b> The keys feed a target velocity and the real one is
     *       walked towards it at a constant acceleration, quickly when speeding up and slowly when
     *       slowing down. That asymmetry is the momentum: let go mid flight and the drone drifts on
     *       for about a block and a half.</li>
     * </ul>
     */
    private static void fly(Minecraft minecraft, ReconDroneEntity drone) {
        float forward = (minecraft.options.keyUp.isDown() ? 1.0F : 0.0F)
                - (minecraft.options.keyDown.isDown() ? 1.0F : 0.0F);
        float strafe = (minecraft.options.keyLeft.isDown() ? 1.0F : 0.0F)
                - (minecraft.options.keyRight.isDown() ? 1.0F : 0.0F);
        float vertical = (minecraft.options.keyJump.isDown() ? 1.0F : 0.0F)
                - (minecraft.options.keyShift.isDown() ? 1.0F : 0.0F);

        // Forward is the view vector. Strafe runs along the left hand vector - the same heading
        // flattened onto the horizontal plane - so A and D always sidestep instead of peeling off
        // into a dive. strafe is +1 for A and -1 for D, which is why this is the left hand one.
        //
        // Derivation, because the sign is easy to get backwards: the view vector's horizontal
        // component is (-sin(yaw), cos(yaw)), and rotating that a quarter turn to the left gives
        // (cos(yaw), sin(yaw)). Sanity check at yaw 0 (facing south, +Z): that yields +X, and east
        // really is on your left when you face south.
        float yawRad = drone.getYRot() * Mth.DEG_TO_RAD;
        Vec3 look = drone.getViewVector(1.0F);
        Vec3 left = new Vec3(Mth.cos(yawRad), 0.0D, Mth.sin(yawRad));

        Vec3 direction = look.scale(forward)
                .add(left.scale(strafe))
                .add(0.0D, vertical, 0.0D);
        if (direction.lengthSqr() > 1.0D) {
            // Holding a diagonal would otherwise stack past the cruise speed.
            direction = direction.normalize();
        }

        // The airframe's own ceiling, not the base one: a drone carrying the governor module flies
        // faster, and the client is the side that actually flies it. The server never has to agree
        // on this number - it only bounds a single packet by MAX_STEP, and even the boosted cruise
        // is a quarter of that.
        double cruise = drone.flightSpeed();
        Vec3 target = direction.scale(cruise);

        // A constant acceleration rather than a fraction of the gap, so the ramp is a ramp: the
        // step is the same size on the first tick as on the last, and the speed it builds to is
        // what the pilot feels. The step is clipped to the remaining gap so the velocity lands on
        // the target instead of oscillating around it, which is what a fixed step with no clip
        // would do at low speeds.
        double step = target.lengthSqr() >= drift.lengthSqr() ? FLIGHT_ACCEL : FLIGHT_DECEL;
        Vec3 gap = target.subtract(drift);
        double gapLength = gap.length();
        if (gapLength > 1.0E-9D) {
            drift = drift.add(gap.scale(Math.min(step, gapLength) / gapLength));
        }
        // The cruise ceiling is enforced here rather than left to the ramp: this is the promise
        // that acceleration does not buy speed, whatever the pilot holds down.
        //
        // 滚筒冲刺是唯一的例外：上限乘上 speedCapMultiplier()（冲刺时 1.15，平时 1.0）。
        // 乘在这里而不是改巡航上限，是为了让"最高速"这个事实仍然只有一个来源——
        // flightSpeed()；冲刺只是它的一个倍数。
        double cap = cruise * speedCapMultiplier();
        double speed = drift.length();
        if (speed > cap) {
            drift = drift.scale(cap / speed);
        }
        if (drift.lengthSqr() < 1.0E-6D) {
            drift = Vec3.ZERO;
            return;
        }

        drone.move(MoverType.SELF, drift);
        if (drone.horizontalCollision || drone.verticalCollision) {
            // Met something solid: drop the coast so the drone does not grind along the wall.
            drift = Vec3.ZERO;
        }

        double minY = minecraft.level.getMinBuildHeight() + 0.1D;
        double maxY = minecraft.level.getMaxBuildHeight() - 1.0D;
        if (drone.getY() < minY || drone.getY() > maxY) {
            drone.setPos(drone.getX(), Mth.clamp(drone.getY(), minY, maxY), drone.getZ());
            drift = Vec3.ZERO;
        }
    }

    /**
     * Swallows the keys that would open a screen or shuffle the hotbar while piloting.
     *
     * <p>Both are cosmetic problems rather than mechanical ones: an inventory screen obviously
     * breaks the visor illusion, and even a plain hotbar change is enough to do it, because
     * vanilla pops the item's name up in the middle of the screen for a moment afterwards.
     *
     * <p>Timing is the whole trick. {@code Minecraft#handleKeybinds} runs from the middle of
     * {@code Minecraft#tick}, while {@code ClientTickEvent.Pre} fires at the very head of it, so
     * draining the click counters here leaves the vanilla handler with nothing to consume. Doing
     * this from {@code ClientTickEvent.Post} would be too late - the screen would already be open.
     */
    public static void swallowUiKeys() {
        if (!isLinked()) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        drain(minecraft.options.keyInventory);
        for (KeyMapping slot : minecraft.options.keyHotbarSlots) {
            drain(slot);
        }
        // Neither of these changes the hotbar, but both let a pilot move items around an inventory
        // they cannot see, which is the same class of accident.
        drain(minecraft.options.keySwapOffhand);
        drain(minecraft.options.keyDrop);
    }

    private static void drain(KeyMapping mapping) {
        while (mapping.consumeClick()) {
            // Discarded on purpose: the press happened, we simply refuse to act on it.
        }
    }

    /** While linked the player body must not walk away on its own. */
    public static void onMovementInput(MovementInputUpdateEvent event) {
        if (!isLinked()) {
            return;
        }
        if (event.getEntity() != Minecraft.getInstance().player) {
            return;
        }
        Input input = event.getInput();
        input.forwardImpulse = 0.0F;
        input.leftImpulse = 0.0F;
        input.jumping = false;
        input.shiftKeyDown = false;
        input.up = false;
        input.down = false;
        input.left = false;
        input.right = false;
    }
}
