package dev.watchcraft.watchcraft.client;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.InteractionHand;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.client.event.MovementInputUpdateEvent;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import net.neoforged.neoforge.client.event.RenderGuiEvent;
import net.neoforged.neoforge.client.event.RenderGuiLayerEvent;
import net.neoforged.neoforge.client.event.RenderHandEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.client.event.ViewportEvent;
import net.neoforged.neoforge.client.gui.VanillaGuiLayers;

@EventBusSubscriber(modid = dev.watchcraft.watchcraft.Watchcraft.MOD_ID, value = Dist.CLIENT)
public final class ClientEvents {

    private ClientEvents() {
    }

    @SubscribeEvent
    public static void onRegisterClientCommands(RegisterClientCommandsEvent event) {
        ClientCommands.register(event);
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        // 先认引爆，再跑操控：冲击一旦开始，操控那一侧已经什么都不做了（雪花屏阶段
        // 机体归服务端冻结），顺序上谁先谁后不影响结果，但把触发点放在前面读着更顺。
        DroneShake.tick();
        DroneController.tick();
    }

    /**
     * Runs at the head of {@code Minecraft#tick}, which is the only point early enough to stop the
     * vanilla key handler from opening the inventory or moving the hotbar selection.
     */
    @SubscribeEvent
    public static void onClientTickPre(ClientTickEvent.Pre event) {
        DroneController.swallowUiKeys();
    }

    /** Per frame, so the drone's view follows the mouse at frame rate instead of at 20 Hz. */
    @SubscribeEvent
    public static void onRenderFrame(RenderFrameEvent.Pre event) {
        // 排在最前：这一帧的画面只该反映无人机，驾驶员本体的画面状态要先挡掉。
        DroneVisor.beginFrame();
        DroneController.syncLook();
        DroneController.tickFov();
        // 排在转向之后：转向每帧都会把无人机的朝向重写一遍，抖动放到它前面会被覆盖掉。
        DroneShake.frame();
    }

    /**
     * 帧结束，把 {@link DroneVisor} 挪走的玩家状态还回去。
     *
     * <p>必须与上面的 {@code Pre} 成对。两个事件在 {@code Minecraft#runTick} 里紧挨着
     * {@code GameRenderer#render} 的前后触发，并且同在 {@code if (!this.noRender)} 分支内，
     * 所以 {@code Post} 一定跑得到 —— 没有"挪走了没还回来"的路径。
     */
    @SubscribeEvent
    public static void onRenderFramePost(RenderFrameEvent.Post event) {
        DroneVisor.endFrame();
    }

    /**
     * Opens the view with speed, and yanks it open for an attack run.
     *
     * <p>Fired from {@code ClientHooks#getFieldOfView}, which is the tail of {@code
     * GameRenderer#getFov} - after the configured FOV, after the sprint and teleport animations,
     * and after {@code fovEffectScale} has already had its say. Overwriting the value here is
     * therefore the last word on the projection, which is what makes it safe: nothing downstream
     * recomputes the FOV from the options and throws the change away.
     *
     * <p>Both terms are read rather than the raw flags, so the same hook eases the view back in when
     * a run ends or the drone coasts to a stop instead of cutting it. Both scales are neutral unless
     * this player is flying a drone, so neither can leak into ordinary play.
     */
    @SubscribeEvent
    public static void onComputeFov(ViewportEvent.ComputeFov event) {
        double scale = DroneController.viewFovScale();
        if (scale == 1.0D) {
            return;
        }
        event.setFOV(event.getFOV() * scale);
    }

    /**
     * Leans the pilot's view into a turn and rolls it through a barrel roll.
     *
     * <p>Everything else about the cockpit already moves - the picture opens with speed, the
     * airframe leans for anyone watching it - but without this the pilot's own horizon stays level
     * through every manoeuvre, which is what makes a hard turn read as sliding sideways. The value
     * is zero unless the camera is on a piloted drone, so nothing changes for a player who is
     * merely looking at one, and the reticle stays a screen space overlay rather than being dragged
     * round with the horizon.
     *
     * <p>引爆瞬间的冲击也走这里，理由一样：这三个角是镜头自己的，写在事件上不会碰实体状态，
     * 所以既不会和转向打架，也不会漏到没在开无人机的时候 - {@link DroneShake} 的每一项
     * 在窗口之外都返回精确的 0。
     */
    @SubscribeEvent
    public static void onComputeCameraAngles(ViewportEvent.ComputeCameraAngles event) {
        float roll = DroneController.cameraRoll(event.getPartialTick());
        if (roll != 0.0F) {
            event.setRoll(event.getRoll() + roll);
        }

        float shakePitch = DroneShake.pitchOffset();
        float shakeYaw = DroneShake.yawOffset();
        float shakeRoll = DroneShake.rollOffset();
        if (shakePitch != 0.0F) {
            event.setPitch(event.getPitch() + shakePitch);
        }
        if (shakeYaw != 0.0F) {
            event.setYaw(event.getYaw() + shakeYaw);
        }
        if (shakeRoll != 0.0F) {
            event.setRoll(event.getRoll() + shakeRoll);
        }
    }

    /**
     * Runs the signal defocus.
     *
     * <p>{@code AFTER_LEVEL} is the last stage of {@code GameRenderer#renderLevel}, which puts it
     * after the world has been drawn but before {@code LevelRenderer#doEntityOutline} composites
     * the glow of every marked entity. That ordering is the whole point: blur here and the world
     * goes soft while the marks stay crisp, blur any later and the blur eats the glow.
     */
    @SubscribeEvent
    public static void onRenderLevelStage(RenderLevelStageEvent event) {
        if (event.getStage() == RenderLevelStageEvent.Stage.AFTER_LEVEL) {
            DroneSignal.renderLevelStage(event.getPartialTick().getGameTimeDeltaTicks());
        }
    }

    @SubscribeEvent
    public static void onMovementInput(MovementInputUpdateEvent event) {
        DroneController.onMovementInput(event);
    }

    @SubscribeEvent
    public static void onRenderGui(RenderGuiEvent.Post event) {
        DroneHud.render(event.getGuiGraphics());
    }

    @SubscribeEvent
    public static void onRenderHand(RenderHandEvent event) {
        // The drone is flown from a first person camera, so the player's own arm and held item
        // must stay out of frame.
        if (DroneController.isLinked()) {
            event.setCanceled(true);
        }
    }

    /**
     * While piloting, the player body is nowhere near whatever the drone is looking at, so letting
     * vanilla act on a click would use items, place blocks or swing at thin air. Every click is
     * swallowed here; a right click is re-sent to the server as a reach from the drone instead.
     */
    @SubscribeEvent
    public static void onInteractionKey(InputEvent.InteractionKeyMappingTriggered event) {
        if (!DroneController.isLinked()) {
            return;
        }
        event.setCanceled(true);
        event.setSwingHand(false);
        if (event.isUseItem()
                && event.getHand() == InteractionHand.MAIN_HAND
                && DroneController.beginInteract()) {
            DroneController.requestInteract();
        }
    }

    /** The hotbar is out of frame while piloting, so scrolling it would just be a blind change. */
    @SubscribeEvent
    public static void onMouseScroll(InputEvent.MouseScrollingEvent event) {
        if (DroneController.isLinked()) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public static void onRenderGuiLayer(RenderGuiLayerEvent.Pre event) {
        if (!DroneController.isLinked()) {
            return;
        }
        ResourceLocation name = event.getName();
        // The visor draws its own reticle and its own bottom strip, and the vanilla experience bar
        // sits exactly where that strip goes, so both are taken out of the way while piloting.
        if (VanillaGuiLayers.CROSSHAIR.equals(name)
                || VanillaGuiLayers.EXPERIENCE_BAR.equals(name)
                || VanillaGuiLayers.EXPERIENCE_LEVEL.equals(name)) {
            event.setCanceled(true);
        }
    }
}
