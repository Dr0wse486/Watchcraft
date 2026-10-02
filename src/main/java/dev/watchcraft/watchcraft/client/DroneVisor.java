package dev.watchcraft.watchcraft.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;

/**
 * 把驾驶员本体的画面状态挡在无人机镜头之外。
 *
 * <p>无人机链路只把<b>相机实体</b>换成无人机（{@code Minecraft#setCameraEntity}），但原版有几处
 * 画面效果是直接读 {@code minecraft.player} 的，而玩家本体对象在操控期间始终还是本机玩家 ——
 * 于是这些效果会照常叠在无人机镜头上。
 *
 * <p>目前挡掉的只有<b>反胃</b>（{@code MobEffects.CONFUSION}，下界传送门 / 迷之炖菜那种画面扭曲）。
 * 它的两处读取点都在 {@code GameRenderer} 里，而且都绕不过去：
 * <ul>
 *   <li>{@code getProjectionMatrix} 里的
 *       {@code Mth.lerp(f, player.oSpinningEffectIntensity, player.spinningEffectIntensity)} ——
 *       投影矩阵上的摇晃与横向拉伸；</li>
 *   <li>{@code render} 里的 HUD 叠加 {@code renderConfusionOverlay}，读的是同一个值。</li>
 * </ul>
 * 两处都只看这一个浮点数，把它清零就等于同时关掉两处。本模组不用 Mixin，所以走的是
 * "临时把字段挪走"这条路，而不是去改投影矩阵。
 *
 * <h2>为什么是"帧内挪走"而不是"每刻清零"</h2>
 *
 * <p>反胃强度是个<b>累加器</b>：{@code LocalPlayer#handleConfusionTransitionEffect} 每刻把它往目标
 * 推 0.006667（有反胃时）或拉回 0（没有时，每刻 -0.05），所以它同时是玩家<b>自己</b>的画面状态。
 * 如果每刻都清零，玩家解除链路之后，自己的反胃会从 0 重新爬上来，要七秒多才恢复原样 ——
 * 那是把一个 bug 换成另一个。
 *
 * <p>所以挪动只覆盖渲染那一小段：{@code RenderFrameEvent.Pre} 里存下真值并清零，
 * {@code RenderFrameEvent.Post} 里原样还回去。整个 tick 逻辑（包括那个累加器）看到的一直是真值，
 * 只有中间那一次 {@code GameRenderer#render} 看到 0。两个事件在 {@code Minecraft#runTick} 里
 * 成对触发（{@code if (!this.noRender)} 内部），{@code Post} 必然在 {@code render()} 返回之后执行，
 * 所以不存在"挪走了没还回来"的路径。
 *
 * <h2>不改的东西</h2>
 *
 * <p>受伤红屏不在这里，因为它本来就是对的：{@code Gui#renderVignette} 取的是
 * {@code minecraft.getCameraEntity()}，操控期间那就是无人机自己，闪的是无人机挨的那一下。
 */
public final class DroneVisor {

    /** 真值只在 {@link #beginFrame()} 与 {@link #endFrame()} 之间被挪走。 */
    private static boolean parked;
    private static float parkedIntensity;
    private static float parkedPreviousIntensity;

    private DroneVisor() {
    }

    /**
     * 帧开始：操控期间把玩家本体的反胃强度挪走，让这一帧的镜头只反映无人机。
     *
     * <p>没在操控就什么都不做 —— 字段一个都不碰，所以普通游戏完全不受影响。
     */
    public static void beginFrame() {
        if (parked || !DroneController.isLinked()) {
            return;
        }
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) {
            return;
        }
        parkedIntensity = player.spinningEffectIntensity;
        parkedPreviousIntensity = player.oSpinningEffectIntensity;
        player.spinningEffectIntensity = 0.0F;
        player.oSpinningEffectIntensity = 0.0F;
        parked = true;
    }

    /**
     * 帧结束：把挪走的值原样还回去。
     *
     * <p>这里<b>不</b>检查是否还在操控：链路有可能正好在这一帧里断掉，但值已经挪走了，
     * 不还回去就会永久停在 0 —— 那等于把玩家的反胃彻底关掉。
     */
    public static void endFrame() {
        if (!parked) {
            return;
        }
        parked = false;
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) {
            return;
        }
        player.spinningEffectIntensity = parkedIntensity;
        player.oSpinningEffectIntensity = parkedPreviousIntensity;
    }
}
