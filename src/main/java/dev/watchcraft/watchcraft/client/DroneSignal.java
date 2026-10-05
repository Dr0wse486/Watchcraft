package dev.watchcraft.watchcraft.client;

import com.google.gson.JsonSyntaxException;
import com.mojang.blaze3d.systems.RenderSystem;
import dev.watchcraft.watchcraft.entity.ReconDroneEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.PostChain;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;

import java.io.IOException;

/**
 * Signal degradation, the world-space half of it.
 *
 * <p>Two things happen as the drone flies away from its operator: the picture loses definition,
 * and the visor fills up with grain. This class owns the first one - a real defocus of the
 * rendered world - and publishes {@link #amount(double)} so the overlay in {@link DroneHud} can
 * draw the second at exactly the same strength.
 *
 * <p><b>Why the world and not the GUI.</b> {@code GuiGraphics} only fills flat colour. A
 * screen-space imitation can lift the blacks and darken the corners, but it can never soften an
 * edge, and an overlay that only lifts contrast reads as dust sitting on a sharp picture - which
 * is precisely what this used to look like. The only place a genuine blur can come from is the
 * frame buffer, so the world gets blurred for real and the overlay is left to do the parts that
 * really do belong to it: grain, torn scan lines and the vignette.
 *
 * <p><b>Why there is no shader code here.</b> {@code assets/watchcraft/shaders/post/drone_link.json}
 * is a post chain whose every pass names vanilla's own {@code box_blur} program. That program
 * reads its strength from a {@code Radius} uniform and its direction from {@code BlurDir}, so the
 * whole effect is one JSON file plus a uniform write, and nothing has to be compiled or registered
 * from Java.
 *
 * <h2>Why this runs at {@code AFTER_LEVEL} instead of through {@code GameRenderer#postEffect}</h2>
 *
 * <p>Handing the chain to {@code GameRenderer} looks like the obvious route - the method is
 * public and the engine processes it for you - but it puts the blur in the wrong place in the
 * frame. The order inside {@code GameRenderer#render} is:
 *
 * <pre>
 *   renderLevel(...)              // world, entities, clouds, weather
 *   doEntityOutline()             // &lt;-- the glowing entities are composited HERE
 *   postEffect.process(...)       // &lt;-- so a post effect blurs the glow along with the world
 *   ... GUI
 * </pre>
 *
 * <p>Blurring the glow is not a cosmetic detail, it destroys the feature. A glowing entity is not
 * drawn into the main target at all - it is rendered only into the outline buffer, and what you
 * actually see is the thin white edge {@code entity_sobel.fsh} derives from it
 * ({@code outColor * 0.2}, composited with {@code SRC_ALPHA}). Spreading that edge over a
 * twenty-pixel radius drops its peak brightness by an order of magnitude, and the mark collapses
 * into a dark smear. The glow is the drone's whole reason for existing, so it has to survive.
 *
 * <p>{@code RenderLevelStageEvent.Stage.AFTER_LEVEL} fires as the last statement of
 * {@code GameRenderer#renderLevel} - after the world, before {@code doEntityOutline}. Blurring
 * there leaves the glow to be composited crisply on top of an already soft picture, which is
 * exactly the layering the visor wants: the world goes out of focus, the instruments and the
 * marks stay readable. It also means the engine never touches this chain, so there is no
 * {@code checkEntityPostEffect} reset to fight and nothing to re-assert every frame.
 */
public final class DroneSignal {

    /**
     * Our post chain.
     *
     * <p>The path is the full path inside the namespace, because that is what {@code PostChain}
     * expects: it reads the file straight off the resource manager rather than going through any
     * shader registry.
     */
    private static final ResourceLocation EFFECT =
            ResourceLocation.fromNamespaceAndPath("watchcraft", "shaders/post/drone_link.json");

    /**
     * Widest blur radius, in pixels, at full signal loss.
     *
     * <p>The chain runs the radius through three horizontal/vertical pairs at 1.0, 0.5 and 0.25, so
     * the spread that actually lands on screen is roughly twice this. Six is enough to lose a fence
     * and a tree line without the picture turning into soup, and a radius of zero is an exact
     * no-op - the shader still samples, but only once per pixel - which is what lets the effect
     * stay resident at short range instead of being torn down and rebuilt every time the drone
     * crosses the onset distance.
     */
    private static float MAX_RADIUS = 6.0F;

    /** 客户端配置载入/热重载后把模糊半径写回镜像。 */
    public static void applyConfig() {
        MAX_RADIUS = dev.watchcraft.watchcraft.config.WatchcraftConfig.CLIENT.signalMaxBlurRadius
                .get().floatValue();
    }

    /** The chain, built on the first frame it is needed and dropped when the link goes down. */
    private static PostChain effect;
    /** Latched when the chain refuses to build, so a broken resource does not retry every frame. */
    private static boolean broken;
    /** Window size the chain was last resized for. */
    private static int width = -1;
    private static int height = -1;

    private DroneSignal() {
    }

    /**
     * 没装电池时画面劣化的强度，**故意大于 1**。
     *
     * <p>1.0 是"链路拉到极限"那一档：糊、下雪，但你还认得出树和墙。没电池要的不是"更远的距离"，
     * 而是"这块屏幕已经没用了"，所以这里越界，让模糊半径和雪花浓度一起再往上走一截。
     *
     * <p>两个效果都按这个数线性放大（半径直接乘，雪花的雾底与颗粒数也乘），所以整体轻重
     * 只有这一个旋钮 —— 觉得还不够糊就调大它，不用去动别的常数。
     */
    public static final double NO_BATTERY_SEVERITY = 1.8D;

    /**
     * How far the picture has degraded: 0 at the airframe's own static onset, 1 at its own leash.
     *
     * <p>Both ends come off the drone rather than off the base constants, because signal boosters
     * push them out together - see {@link ReconDroneEntity#staticOnset()}. On a bare drone the two
     * ends are the original 48 and 64, exactly four {@code STATIC_STEP}s apart, so it still lands
     * on the grades the design calls for: clean at 48, a quarter at 52, half at 56, three quarters
     * at 60, whiteout at 64. A boosted one simply degrades over a proportionally longer run.
     *
     * <p>An airframe with no pack at all is the one case that returns more than 1 - see
     * {@link #NO_BATTERY_SEVERITY}.
     */
    public static double amount(ReconDroneEntity drone, double range) {
        // 没装电池的机体画面是全糊的 —— 直接借"最远距离"那一档再往上加，而不是另做一套效果：
        // 玩家已经知道画面糊是什么意思，缺的只是一个"为什么糊"的提示，那个提示在 DroneHud 里。
        if (!drone.hasBattery()) {
            return NO_BATTERY_SEVERITY;
        }
        double onset = drone.staticOnset();
        double span = drone.linkRange() - onset;
        if (span <= 0.0D) {
            return 0.0D;
        }
        return Mth.clamp((range - onset) / span, 0.0D, 1.0D);
    }

    /**
     * Once per level render, at the tail of {@code GameRenderer#renderLevel}.
     *
     * @param partialTicks the frame's partial tick, forwarded to the chain so its {@code Time}
     *                     uniform keeps advancing
     */
    public static void renderLevelStage(float partialTicks) {
        Minecraft minecraft = Minecraft.getInstance();
        Entity camera = minecraft.getCameraEntity();
        boolean piloting = DroneController.isLinked()
                && minecraft.level != null
                && minecraft.player != null
                && camera instanceof ReconDroneEntity;

        if (!piloting) {
            release();
            // A chain that failed to build gets a fresh attempt on the next link.
            broken = false;
            return;
        }
        if (broken) {
            return;
        }
        if (effect == null && !acquire(minecraft)) {
            return;
        }

        resize(minecraft);
        effect.setUniform("Radius", radiusFor(minecraft, (ReconDroneEntity) camera));
        // 引爆瞬间的径向模糊。两条 pass 共用一条时间轴，第二遍减半，拖影才是连续的一层
        // 而不是一条硬边。不爆炸时这里是 0，着色器直接走单次采样的直通分支。
        effect.setUniform("Strength", DroneShake.radialBlur());
        effect.setUniform("Strength2", DroneShake.radialBlurSecondPass());

        // Exactly the sequence GameRenderer runs around its own post effect, and the rebind at the
        // end matters: without it the window framebuffer is left bound, and doEntityOutline would
        // composite the glow onto the wrong surface.
        RenderSystem.disableBlend();
        RenderSystem.disableDepthTest();
        RenderSystem.resetTextureMatrix();
        effect.process(partialTicks);
        minecraft.getMainRenderTarget().bindWrite(true);
    }

    private static boolean acquire(Minecraft minecraft) {
        try {
            effect = new PostChain(
                    minecraft.getTextureManager(),
                    minecraft.getResourceManager(),
                    minecraft.getMainRenderTarget(),
                    EFFECT);
            width = -1;
            height = -1;
            return true;
        } catch (IOException | JsonSyntaxException exception) {
            // PostChain has already logged the file and the reason.
            broken = true;
            effect = null;
            return false;
        }
    }

    /**
     * Keeps the chain's own render targets in step with the window.
     *
     * <p>Nothing calls back into this chain on a resize - {@code GameRenderer#resize} only knows
     * about its own post effect and its blur - so the size is checked here instead. Two integer
     * comparisons a frame is cheaper than any hook would be.
     */
    private static void resize(Minecraft minecraft) {
        int windowWidth = minecraft.getWindow().getWidth();
        int windowHeight = minecraft.getWindow().getHeight();
        if (windowWidth != width || windowHeight != height) {
            effect.resize(windowWidth, windowHeight);
            width = windowWidth;
            height = windowHeight;
        }
    }

    private static void release() {
        if (effect != null) {
            effect.close();
            effect = null;
            width = -1;
            height = -1;
        }
    }

    private static float radiusFor(Minecraft minecraft, ReconDroneEntity drone) {
        return (float) (MAX_RADIUS * amount(drone, Math.sqrt(drone.distanceToSqr(minecraft.player))));
    }
}
