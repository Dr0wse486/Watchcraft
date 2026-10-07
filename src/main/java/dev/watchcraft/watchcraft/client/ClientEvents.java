package dev.watchcraft.watchcraft.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.options.OptionsScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.InteractionHand;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.client.event.MovementInputUpdateEvent;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import net.neoforged.neoforge.client.event.RenderGuiEvent;
import net.neoforged.neoforge.client.event.RenderGuiLayerEvent;
import net.neoforged.neoforge.client.event.RenderHandEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.client.event.ViewportEvent;
import net.neoforged.neoforge.client.gui.VanillaGuiLayers;
import org.jetbrains.annotations.Nullable;

import java.util.List;

@EventBusSubscriber(modid = dev.watchcraft.watchcraft.Watchcraft.MOD_ID, value = Dist.CLIENT)
public final class ClientEvents {

    /** 原版选项界面那个按钮网格的行间距，取自它的 {@code paddingBottom(4)}。 */
    private static final int OPTIONS_GRID_GAP = 4;

    /** 上一次挂上去的入口按钮，以及它所属的那个界面，用来在窗口缩放后重新对齐。 */
    @Nullable
    private static Button optionsEntry;
    @Nullable
    private static OptionsScreen optionsEntryOwner;

    private ClientEvents() {
    }

    @SubscribeEvent
    public static void onRegisterClientCommands(RegisterClientCommandsEvent event) {
        ClientCommands.register(event);
    }

    /**
     * 把配置入口挂进原版「选项…」界面的按钮网格，落在最右下那一格。
     *
     * <p>为什么是「挂」而不是「插」：原版 {@code OptionsScreen} 的按钮是一个 2 列
     * {@code GridLayout}（10 个，最后一行是「遥测 / 版权与鸣谢」），在 {@code init()} 里就已经
     * {@code arrangeElements} 并 {@code visitWidgets} 完了。事件里够不着那个 GridLayout，也就
     * 没法再 {@code addChild} 一行。所以这里自己算位置：找出网格最右下那一格，把按钮摆在它正
     * 下方 —— 效果等同于给网格补第 11 个孩子，但不需要碰原版布局。
     *
     * <p>定位不比对按钮文案，靠的是「页脚那个 Done 一定在所有网格按钮之下」：先取全局最靠下的
     * 按钮（Done），再在它之上找 y 最大、同 y 时 x 最大的那个，就是网格右下角。这样换语言、
     * 换资源包都不会错位，原版增删网格行也只会让位置跟着走。
     *
     * <p>按钮宽度取锚点自身的宽度而不是写死：这一屏的邻居都是 150 宽，跟着邻居走才不会凹进去
     * 一块。（暂停菜单里那套半宽按钮是 98，那个尺寸在这一屏没有对应的邻居。）
     */
    @SubscribeEvent
    public static void onScreenInit(ScreenEvent.Init.Post event) {
        if (!(event.getScreen() instanceof OptionsScreen options)) {
            // 刻意**不清** optionsEntry / optionsEntryOwner。
            //
            // 从配置界面返回「选项」时这个事件不会再触发：Minecraft#setScreen 调的是
            // Screen#init，而 Screen 只在 initialized 为假时才跑 init() 并派发本事件，
            // 否则只调 repositionElements()（而 OptionsScreen 又把那个覆写成了
            // layout.arrangeElements()，连控件都不重建）。所以在这里清引用，等于让
            // 「返回之后再也对不齐位置」—— 引用留着，最多把一个界面对象多留一会儿。
            return;
        }
        Button anchor = bottomRightOfGrid(options.children(), null);
        if (anchor == null) {
            return;
        }
        Button entry = Button.builder(
                        Component.translatable("watchcraft.config.button"),
                        button -> Minecraft.getInstance().setScreen(new WatchcraftConfigScreen(options)))
                .bounds(anchor.getX(), anchor.getY() + anchor.getHeight() + OPTIONS_GRID_GAP,
                        anchor.getWidth(), anchor.getHeight())
                .build();
        event.addListener(entry);
        optionsEntry = entry;
        optionsEntryOwner = options;
    }

    /**
     * 窗口缩放之后把入口按钮重新对齐到网格右下角。
     *
     * <p>必须有这一手：{@code OptionsScreen} 覆写了 {@code repositionElements}，只调自己的
     * {@code layout.arrangeElements()}，**不重建控件** —— 于是 {@code ScreenEvent.Init.Post}
     * 不会再触发，而我们那个按钮不在它的 layout 里，原版按钮都挪了它却留在原地。
     * （基类 {@code Screen#repositionElements} 反而是会 {@code rebuildWidgets()} 的，是这里被覆写掉了。）
     * 每帧对一次位置的代价是几次整数比较。
     */
    @SubscribeEvent
    public static void onScreenRender(ScreenEvent.Render.Pre event) {
        if (optionsEntry == null || optionsEntryOwner == null || event.getScreen() != optionsEntryOwner) {
            return;
        }
        Button anchor = bottomRightOfGrid(optionsEntryOwner.children(), optionsEntry);
        if (anchor == null) {
            return;
        }
        optionsEntry.setX(anchor.getX());
        optionsEntry.setY(anchor.getY() + anchor.getHeight() + OPTIONS_GRID_GAP);
    }

    /**
     * 找出按钮网格最右下那一格。
     *
     * <p>两步：先取全局最靠下的按钮 —— 那是页脚的 Done，它一定在网格之下；再在它上方找
     * y 最大、同 y 时 x 最大的按钮，就是网格的右下角。{@code ignore} 用来排掉我们自己挂上去的
     * 那个按钮，否则第二轮会把「网格右下角」认成我们自己的位置，越挪越低。
     */
    @Nullable
    private static Button bottomRightOfGrid(List<? extends GuiEventListener> listeners, @Nullable Button ignore) {
        Button footer = null;
        for (GuiEventListener listener : listeners) {
            if (listener instanceof Button button && button != ignore
                    && (footer == null || button.getY() > footer.getY())) {
                footer = button;
            }
        }
        if (footer == null) {
            return null;
        }
        Button best = null;
        for (GuiEventListener listener : listeners) {
            if (!(listener instanceof Button button) || button == ignore || button == footer
                    || button.getY() >= footer.getY()) {
                continue;
            }
            if (best == null || button.getY() > best.getY()
                    || (button.getY() == best.getY() && button.getX() > best.getX())) {
                best = button;
            }
        }
        return best;
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        // 先认引爆，再跑操控：冲击一旦开始，操控那一侧已经什么都不做了（雪花屏阶段
        // 机体归服务端冻结），顺序上谁先谁后不影响结果，但把触发点放在前面读着更顺。
        DroneShake.tick();
        DroneController.tick();
        // 旋翼声放在最后：它只读无人机当前的状态，谁先谁后都不影响这一帧的结果。
        DroneSounds.tick();
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
        if (scale != 1.0D) {
            event.setFOV(event.getFOV() * scale);
        }
        // 标记覆盖层要自己算透视投影，需要这一帧真正的视场角。只认主视角那一次：
        // ComputeFov 每帧会因为手部渲染、传送动画等原因被调用多次，取值不同的几档，
        // 随手抓最后一次会让屏幕上的标记乱跳。
        if (event.usedConfiguredFov()) {
            DroneMarkerOverlay.setFov(event.getFOV());
        }
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
        // 顺序有意：面罩先铺，标记后压。这样预警的红边框落在面罩的青色边框之上，
        // 而箱子标记不会被仪表盘盖住 —— 两者在操控时是同时存在的。
        DroneMarkerOverlay.render(event.getGuiGraphics());
        // 被探测警告画在最上层：它是"立刻看一眼"的信息，压在标记与面罩之上才不会被漏掉。
        DroneDetectWarning.render(event.getGuiGraphics());
    }

    /**
     * 退出世界时清掉侦察快照。
     *
     * <p>必须显式清，不能指望服务端再发一条：那些静态字段活在 JVM 里，重新进一个世界时
     * 它们还是上一次的值，屏幕上会凭空冒出上一个存档的箱子标记，直到服务端碰巧发现内容
     * 有变化为止。
     */
    @SubscribeEvent
    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        DroneMarkerOverlay.clear();
        DroneDetectWarning.clear();
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
