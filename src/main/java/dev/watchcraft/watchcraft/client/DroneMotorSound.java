package dev.watchcraft.watchcraft.client;

import dev.watchcraft.watchcraft.entity.ReconDroneEntity;
import dev.watchcraft.watchcraft.registry.ModSounds;
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/**
 * 无人机的旋翼声：一段无缝循环，音量与音高跟着飞行状态实时走。
 *
 * <p>原版的听者变换取自 <b>相机</b>（{@code SoundEngine#updateSource}），所以驾驶员的听者就在
 * 无人机上、附近玩家的听者在自己身上 —— 同一段声音对两边意味着完全不同的距离与声像。
 * 因此这里分两种模式，由 {@link DroneSounds} 按"这架无人机是不是本地玩家在开"来选：
 *
 * <ul>
 *   <li><b>驾驶舱</b>（{@code cockpit}）：非定位音，贴着听者、不衰减、不声像。
 *       理由见构造函数的注释 —— 定位音在这一侧会因为声源与听者以不同频率更新而左右横跳。</li>
 *   <li><b>旁观</b>：普通定位音，按距离衰减。附近玩家的听者离声源很远，方向是稳定的，
 *       不存在驾驶舱那种病态情形。</li>
 * </ul>
 *
 * <p>素材本身是固定音高的稳态马达声，所有变化都在这里做：悬停时转子空转（低而闷），
 * 巡航时带上负载（响而亮），冲刺时被推到极限（最响最尖）。这样一段一秒钟的循环就能覆盖
 * 整个飞行包线，不必为每个状态各存一份 ogg。
 */
public final class DroneMotorSound extends AbstractTickableSoundInstance {

    /** 悬停：转子还在转，只是不带负载。 */
    private static final float IDLE_VOLUME = 0.08F;
    private static final float IDLE_PITCH = 0.82F;
    /**
     * 巡航满速。
     *
     * <p>从 0.55 一路降到 0.32：第一版反馈是"太响了"。素材本身也从合成换成了真实录音，
     * 峰值同时从 0.80 降到 0.55，所以合起来大约轻了 8 dB。仍然是能听见的预警音量 ——
     * 再小就失去预警的意义了。
     */
    private static final float CRUISE_VOLUME = 0.32F;
    private static final float CRUISE_PITCH = 1.18F;
    /** 冲刺：转子和桨叶都到了极限。 */
    private static final float CHARGE_VOLUME = 0.48F;
    private static final float CHARGE_PITCH = 1.50F;

    /**
     * 音量与音高每刻追赶目标的比例。
     *
     * <p>不直接赋值：起步、收油、冲刺起手都会让目标值瞬间跳一大截，直接套上去就是"啪"的一声
     * 变调，比没有还糟。按刻追赶既抹平了这些跳变，又足够快（约四分之一秒到位）而不显得迟钝。
     */
    private static final float RESPONSE = 0.12F;

    private final ReconDroneEntity drone;
    /** 驾驶舱模式：本地玩家的相机就在这架无人机上，声源与听者重合。 */
    private final boolean cockpit;
    private Vec3 lastPosition;

    public DroneMotorSound(ReconDroneEntity drone, boolean cockpit) {
        super(ModSounds.DRONE_MOTOR.get(), SoundSource.NEUTRAL, SoundInstance.createUnseededRandom());
        this.drone = drone;
        this.cockpit = cockpit;
        this.lastPosition = drone.position();
        if (!cockpit) {
            this.x = drone.getX();
            this.y = drone.getY();
            this.z = drone.getZ();
        }
        this.looping = true;
        this.delay = 0;
        if (cockpit) {
            // 驾驶舱这一份必须是**非定位音**，否则左右声道会以 20 Hz 来回横跳。
            //
            // 原因是两个位置以不同频率更新：听者取自相机、在 Minecraft#runTick 里**每帧**更新；
            // 而声源位置只在 TickableSoundInstance#tick() 里更新，**只有每刻 20 次**
            // （SoundEngine#tickNonPaused 第 301-314 行）。两者都在同一架无人机上时，
            // 位置差就等于"这一帧相机走了多少"。按 A/D 时这个差是**纯横向**的，方向完全指向侧面，
            // OpenAL 会把声音整个甩到一个声道；下一刻声源位置追上来、差值归零，又回正中间。
            // 按 W/S 时差值落在正前方，不产生声像，所以只有 A/D 听得出问题。
            //
            // 与其去追帧率，不如把这一份直接做成贴着听者、不衰减、不声像的音。
            // ⚠️ attenuation 与 relative 只在 SoundEngine#play 里读一次（第 486-494 行），
            // 之后再改没有用 —— 所以模式只能在这里定，模式变了要换一份新实例。
            this.relative = true;
            this.attenuation = SoundInstance.Attenuation.NONE;
        }
        // 从 0 音量起手，让它随速度淡入，而不是一出现就满音量。代价是必须声明
        // canStartSilent()：SoundEngine#play 会把音量为 0 的声音直接跳过。
        this.volume = 0.0F;
        this.pitch = IDLE_PITCH;
    }

    /** {@return 这一份是不是驾驶舱音} */
    boolean isCockpit() {
        return this.cockpit;
    }

    /** 模式变了要换实例，先让这一份收尾。 */
    void retire() {
        stop();
    }

    @Override
    public void tick() {
        if (this.drone.isRemoved()) {
            stop();
            return;
        }

        if (!this.cockpit) {
            this.x = this.drone.getX();
            this.y = this.drone.getY();
            this.z = this.drone.getZ();
        }

        // 用真实位移量而不是 getDeltaMovement：冲刺期间位置归服务端，客户端那份速度每刻被清零，
        // 读速度会一直得到 0，冲刺就变成最安静的时候了。
        Vec3 now = this.drone.position();
        double moved = now.distanceTo(this.lastPosition);
        this.lastPosition = now;
        // 除以这架机体自己的巡航上限，而不是基础值：装了速度解限模块的机体飞得更快，
        // 拿基础值当分母会让它在远没到顶速时就把音量顶满。
        float speed = (float) Mth.clamp(moved / this.drone.flightSpeed(), 0.0D, 1.0D);

        float targetVolume = Mth.lerp(speed, IDLE_VOLUME, CRUISE_VOLUME);
        float targetPitch = Mth.lerp(speed, IDLE_PITCH, CRUISE_PITCH);
        if (this.drone.isCharging()) {
            targetVolume = CHARGE_VOLUME;
            targetPitch = CHARGE_PITCH;
        }

        this.volume += (targetVolume - this.volume) * RESPONSE;
        this.pitch += (targetPitch - this.pitch) * RESPONSE;
    }

    /** 起手音量是 0，没有这一句 {@code SoundEngine#play} 会直接跳过它。 */
    @Override
    public boolean canStartSilent() {
        return true;
    }

    @Override
    public boolean canPlaySound() {
        return true;
    }
}
