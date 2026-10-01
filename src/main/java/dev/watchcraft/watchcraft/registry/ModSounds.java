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
 * <p>目前只有一个：引爆之后延迟一拍才在驾驶员脑子里浮上来的耳鸣。
 *
 * <p><b>为什么不自带原版音效凑合。</b>原版里没有"窄带高频持续啸叫"这种东西。铃铛、音符盒
 * 都是干净的乐音 —— 衰减快、泛音整，听起来是"叮"而不是"嗡"；洞穴环境音是低频的，那是
 * 空旷不是耳鸣。耳鸣要的恰恰是那种让人想甩甩头的、干瘪的高频，所以这里自带一段 ogg，
 * 由 {@code tools/generate_tinnitus.py} 生成，改参数重跑即可。
 */
public final class ModSounds {

    public static final DeferredRegister<SoundEvent> SOUNDS =
            DeferredRegister.create(Registries.SOUND_EVENT, Watchcraft.MOD_ID);

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
