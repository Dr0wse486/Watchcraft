package dev.watchcraft.watchcraft.registry;

import dev.watchcraft.watchcraft.Watchcraft;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * 模组自己的声音。
 *
 * <p>三个：旋翼的马达循环声、冲刺起手的音爆、以及引爆之后延迟一拍才在驾驶员脑子里浮上来的耳鸣。
 *
 * <p><b>为什么不自带原版音效凑合。</b>原版里没有"窄带高频持续啸叫"这种东西。铃铛、音符盒
 * 都是干净的乐音 —— 衰减快、泛音整，听起来是"叮"而不是"嗡"；洞穴环境音是低频的，那是
 * 空旷不是耳鸣。耳鸣要的恰恰是那种让人想甩甩头的、干瘪的高频，所以这里自带一段 ogg。
 *
 * <p>马达和音爆同理：{@code BEE_LOOP} 是虫子的嗡鸣，{@code ELYTRA_FLYING} 只有风没有机械，
 * {@code MINECART_INSIDE} 是铁轨上的咔哒；而冲刺原本用的是 {@code FIREWORK_ROCKET_LAUNCH}，
 * 那是火箭的升空嘶声。三段 ogg 都由 {@code tools/generate_drone_sounds.py} 与
 * {@code tools/generate_tinnitus.py} 生成，改参数重跑即可。
 */
public final class ModSounds {

    public static final DeferredRegister<SoundEvent> SOUNDS =
            DeferredRegister.create(Registries.SOUND_EVENT, Watchcraft.MOD_ID);

    /**
     * 旋翼的马达声，无人机在场时循环播放。
     *
     * <p>定距事件而不是变距：它是一台真实的机器，就该随距离衰减。32 格是照着"预警"这个用途
     * 挑的 —— 无人机链路本身有 64 格，一架冲过来的无人机要在你还没看见它的时候就能听见，
     * 但又不能传得让半个世界都在嗡嗡响。素材本身是一个无缝循环，音量与音高由
     * {@code DroneMotorSound} 按飞行速度实时调制。
     */
    public static final float MOTOR_RANGE = 32.0F;

    public static final DeferredHolder<SoundEvent, SoundEvent> DRONE_MOTOR = SOUNDS.register(
            "drone_motor",
            () -> SoundEvent.createFixedRangeEvent(
                    ResourceLocation.fromNamespaceAndPath(Watchcraft.MOD_ID, "drone_motor"),
                    MOTOR_RANGE));

    /**
     * 冲刺起手那一下音爆。
     *
     * <p>定距 48 格，比马达声传得更远：冲刺是一次性事件，而且是最需要预警的那一下 ——
     * 听见爆响就知道有东西冲出去了，该躲的躲。之前用的变距事件默认只有 16 格，
     * 几乎只有当事人听得见，起不到预警作用。
     */
    public static final float BOOM_RANGE = 48.0F;

    public static final DeferredHolder<SoundEvent, SoundEvent> DRONE_BOOM = SOUNDS.register(
            "drone_boom",
            () -> SoundEvent.createFixedRangeEvent(
                    ResourceLocation.fromNamespaceAndPath(Watchcraft.MOD_ID, "drone_boom"),
                    BOOM_RANGE));

    /**
     * 引爆后的耳鸣。
     *
     * <p>变距事件而不是定距：实际播放时走的是 {@code SimpleSoundInstance.forUI}，
     * 非定位、不衰减，所以这里给多远的范围都无所谓，用变距省得再挑一个数字。
     */
    public static final DeferredHolder<SoundEvent, SoundEvent> TINNITUS = SOUNDS.register(
            "tinnitus",
            () -> SoundEvent.createVariableRangeEvent(
                    ResourceLocation.fromNamespaceAndPath(Watchcraft.MOD_ID, "tinnitus")));

    private ModSounds() {
    }
}
