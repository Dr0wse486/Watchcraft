package dev.watchcraft.watchcraft.client;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.InteractionHand;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.client.event.MovementInputUpdateEvent;
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
    public static void onClientTick(ClientTickEvent.Post event) {
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
        DroneController.syncLook();
        DroneController.tickChargeFov();
    }

    /**
     * Pulls the view open while an attack run is committed.
     *
     * <p>Fired from {@code ClientHooks#getFieldOfView}, which is the tail of {@code
     * GameRenderer#getFov} - after the configured FOV, after the sprint and teleport animations,
     * and after {@code fovEffectScale} has already had its say. Overwriting the value here is
     * therefore the last word on the projection, which is what makes it safe: nothing downstream
     * recomputes the FOV from the options and throws the change away.
     *
     * <p>The gain is read rather than the charge flag, so the same hook eases the view back in when
     * the run ends instead of cutting it. It only ever leaves neutral while a drone this player is
     * flying is charging, so it cannot leak into ordinary play.
     */
    @SubscribeEvent
    public static void onComputeFov(ViewportEvent.ComputeFov event) {
        double scale = DroneController.chargeFovScale();
        if (scale == 1.0D) {
            return;
        }
        event.setFOV(event.getFOV() * scale);
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
