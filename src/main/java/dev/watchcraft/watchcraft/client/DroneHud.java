package dev.watchcraft.watchcraft.client;

import dev.watchcraft.watchcraft.config.WatchcraftConfig;
import dev.watchcraft.watchcraft.entity.ReconDroneEntity;
import dev.watchcraft.watchcraft.item.DroneModules;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Minimal sci-fi visor overlay: a cyan hairline frame, corner brackets, a clean reticle and a
 * four line telemetry block reading X, Y, the range back to the operator and remaining hit points.
 * Everything is drawn with the vanilla pixel font so it still reads as Minecraft rather than a
 * modern shooter HUD.
 */
public final class DroneHud {

    /**
     * HUD 主色。默认是一支冷青，由 {@code hud.accentColor} 覆盖，见 {@link #applyConfig()}。
     *
     * <p>三个档位其实是同一个基色的三种透明度：准星与边框用不透明的，括号用半透明的，四角
     * 刻度用最淡的。配置里因此只存一份基色，另外两档按固定 alpha 派生 —— 否则换色系时会留下
     * "边框变了、刻度还是青的"这种半吊子状态。
     */
    private static int CYAN = 0xFF3BE8FF;
    private static int CYAN_DIM = 0x803BE8FF;
    private static int CYAN_FAINT = 0x303BE8FF;
    private static final int AMBER = 0xFFFFB020;
    private static final int HURT = 0xFFFF5555;
    private static final int PANEL = 0x99101418;
    /** Dark backing behind the reticle dot so it survives bright skies and snow. */
    private static final int RETICLE_HALO = 0xA0101418;
    /** 读数文字，同样由主色往白里提亮派生，换色系时才不会和边框撞色。 */
    private static int TEXT = 0xFFD6F7FF;
    private static int TEXT_DIM = 0xFF7FA8B4;
    /** Milky veil colour. Light and desaturated: it has to wash the picture out, not darken it. */
    private static final int HAZE = 0xAECDD8;
    /** Vignette ink. Near black but blue, so the corners cool down rather than go muddy. */
    private static final int VIGNETTE = 0x060A0E;
    /** Speed streak ink. Cool and near white, so the streaks read as air rather than as sparks. */
    private static final int STREAK = 0xE8F6FF;
    /** How many streaks share the sweep during an attack run. */
    private static final int STREAK_COUNT = 26;
    /** Where the streaks begin, as a fraction of the half diagonal from the middle of the screen. */
    private static final double STREAK_INNER = 0.42D;

    /** Past this range the link readout turns amber, warning the pilot before the leash snaps. */
    private static double linkWarn() {
        return ReconDroneEntity.LINK_RANGE * 0.75D;
    }

    /** HUD 主色的出厂值。同时也是 {@code hud.accentColor} 的默认值与解析失败时的兜底。 */
    public static final int DEFAULT_ACCENT = 0x3BE8FF;

    /** 是否画四行读数与底部按键条，分别由 {@code hud.showTelemetry} 与 {@code hud.showHints} 控制。 */
    private static boolean showTelemetry = true;
    private static boolean showHints = true;

    private DroneHud() {
    }

    /**
     * 把 {@code hud} 段的配置写回上面那几个静态镜像。
     *
     * <p>由 {@code ModConfigEvent} 和配置界面在保存后各调一次。界面那边必须自己调，是因为
     * {@code ConfigValue#set} 的 javadoc 写明它不派发事件 —— 光等事件是等不到刷新的，
     * 拖完滑条画面不会动。重复调用无害，这里只是几个字段赋值。
     */
    public static void applyConfig() {
        int rgb = parseHexColor(WatchcraftConfig.CLIENT.hudAccentColor.get(), DEFAULT_ACCENT);
        CYAN = 0xFF000000 | rgb;
        CYAN_DIM = 0x80000000 | rgb;
        CYAN_FAINT = 0x30000000 | rgb;
        TEXT = 0xFF000000 | lighten(rgb, 0.78D);
        TEXT_DIM = 0xFF000000 | lighten(rgb, 0.42D);
        showTelemetry = WatchcraftConfig.CLIENT.hudShowTelemetry.get();
        showHints = WatchcraftConfig.CLIENT.hudShowHints.get();
    }

    /**
     * 解析 {@code #RRGGBB}、{@code RRGGBB} 或三位的 {@code #RGB}，失败时返回 {@code fallback}。
     *
     * <p>故意不抛异常：这个值来自用户手写的 toml，打错一个字符不该让游戏起不来，
     * 退回默认色比崩溃体面得多。
     */
    public static int parseHexColor(String text, int fallback) {
        if (text == null) {
            return fallback;
        }
        String hex = text.trim();
        if (hex.startsWith("#")) {
            hex = hex.substring(1);
        }
        if (hex.length() == 3) {
            StringBuilder wide = new StringBuilder(6);
            for (int i = 0; i < 3; i++) {
                wide.append(hex.charAt(i)).append(hex.charAt(i));
            }
            hex = wide.toString();
        }
        if (hex.length() != 6) {
            return fallback;
        }
        try {
            return Integer.parseInt(hex, 16) & 0xFFFFFF;
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    /** 写成 {@code #RRGGBB}，供配置界面回填输入框。 */
    public static String toHexColor(int rgb) {
        return String.format("#%06X", rgb & 0xFFFFFF);
    }

    /** 按比例把颜色往白里提。用来从主色派生正文与次要文字色。 */
    private static int lighten(int rgb, double amount) {
        int r = (rgb >> 16) & 0xFF;
        int g = (rgb >> 8) & 0xFF;
        int b = rgb & 0xFF;
        r = (int) Math.round(r + (0xFF - r) * amount);
        g = (int) Math.round(g + (0xFF - g) * amount);
        b = (int) Math.round(b + (0xFF - b) * amount);
        return (r << 16) | (g << 8) | b;
    }

    public static void render(GuiGraphics graphics) {
        Minecraft minecraft = Minecraft.getInstance();
        if (!DroneController.isLinked() || minecraft.options.hideGui || minecraft.level == null) {
            return;
        }
        Entity entity = minecraft.level.getEntity(DroneController.getLinkedId());
        if (!(entity instanceof ReconDroneEntity drone)) {
            return;
        }

        int width = graphics.guiWidth();
        int height = graphics.guiHeight();
        long millis = System.currentTimeMillis();

        // 引爆之后接管整个画面：这时候剩下的只有雪花，仪表盘、准星、提示条全都让位。
        int staticTicks = drone.getStaticTicks();
        if (staticTicks > 0) {
            drawDetonationStatic(graphics, width, height, staticTicks, DroneShake.overlayRamp());
            return;
        }

        double range = minecraft.player == null ? 0.0D : Math.sqrt(drone.distanceToSqr(minecraft.player));
        double breakup = DroneSignal.amount(range);

        // The snow goes down first so the visor stays readable on top of it. Losing the picture is
        // the point; losing the readouts would just be annoying.
        drawStatic(graphics, width, height, breakup, millis);
        drawChargeStreaks(graphics, width, height, millis, DroneController.chargeEffectIntensity());
        drawFrame(graphics, width, height, millis);
        drawReticle(graphics, width, height);
        // 读数与按键条可以单独关掉：截图时留着准星和边框就够，四行数字和提示条反而碍事。
        if (showTelemetry) {
            drawTelemetry(graphics, minecraft.font, drone, range, millis);
        }
        if (showHints) {
            drawHints(graphics, minecraft.font, width, height,
                    drone.hasModule(DroneModules.ATTACK) ? chargeKeys(minecraft) : null);
        }
        if (breakup >= 1.0D) {
            drawSignalLost(graphics, minecraft.font, width, height, millis);
        }
    }

    // ------------------------------------------------------------------ detonation

    /**
     * 引爆后的满屏雪花。
     *
     * <p>和远处的信号劣化不是一回事：那个是"画面变糊"，这个是"画面没了"。所以这里不铺牛奶色的
     * 薄雾、也不加暗角，只堆最原始的白噪声 - 密、亮、每帧重掷，中间夹几条横向撕裂，偶尔整屏刷白。
     *
     * <p>最后八刻按剩余刻数减少颗粒，画面自己"烧完"，避免时间一到硬切回正常视角。
     *
     * <p>{@code ramp} 是开头那一段，由 {@link DroneShake} 给出：冲击窗口内从 0 爬到 1。
     * 它是这套效果能不能被看见的前提 —— 底色原本第一刻就是全黑，镜头被掀、画面被径向拽开
     * 全都发生在它后面，等于白做。现在底色从三成不透明起步，先透出被冲歪、被拽花的世界，
     * 再在 0.15 秒里糊死；同时最开头补一记炸白。{@code shakeSeconds} 配成 0 时 {@code ramp}
     * 恒为 1，这里逐字退回从前的行为。
     *
     * @param remaining 雪花屏还剩多少刻
     * @param ramp      开头渐入的进度，0 到 1
     */
    private static void drawDetonationStatic(GuiGraphics graphics, int width, int height,
                                             int remaining, double ramp) {
        double fade = Math.max(0.0D, Math.min(1.0D, remaining / 8.0D));

        int baseAlpha = (int) Math.round(255.0D * (0.30D + 0.70D * ramp));
        graphics.fill(0, 0, width, height, (baseAlpha << 24) | 0x05070A);

        // 炸白的那一瞬。退得比什么都快，所以它读起来是"闪"，不是"渐变"。
        double flash = Math.max(0.0D, 1.0D - ramp * 1.45D);
        if (flash > 0.01D) {
            graphics.fill(0, 0, width, height, ((int) (0x72 * flash) << 24) | 0xFFFFFF);
        }

        RandomSource random = RandomSource.create();

        // 颗粒也跟着爬：先稀稀拉拉地漏，再密到什么都看不见。
        double grainRamp = 0.35D + 0.65D * ramp;
        int grains = (int) (Math.min(width * height / 3, 60000) * fade * grainRamp);
        for (int i = 0; i < grains; i++) {
            int x = random.nextInt(width);
            int y = random.nextInt(height);
            int grey = random.nextInt(256);
            int alpha = 0x60 + random.nextInt(0xA0);
            graphics.fill(x, y, x + 1, y + 1, alpha << 24 | grey << 16 | grey << 8 | grey);
        }
        if (fade < 1.0D) {
            return;
        }

        // 横向撕裂条：同步丢失的味道，短视频那种"画面被扯开"的感觉。
        int tears = 6 + random.nextInt(6);
        for (int i = 0; i < tears; i++) {
            int y = random.nextInt(height);
            int thickness = 1 + random.nextInt(3);
            int alpha = 0x30 + random.nextInt(0x70);
            graphics.fill(0, y, width, Math.min(height, y + thickness), alpha << 24 | 0xE8F2FA);
        }

        // 白色闪帧，让这团雪花"还在炸"而不是一张静态噪点图。
        if (random.nextInt(5) == 0) {
            graphics.fill(0, 0, width, height, 0x40FFFFFF);
        }
    }

    // ------------------------------------------------------------------ signal breakup

    /**
     * The screen-space half of the signal loss.
     *
     * <p>The defocus itself is not here - it cannot be. {@code GuiGraphics} only fills flat
     * colour, so nothing drawn at this stage can soften an edge; that job belongs to the box blur
     * post chain in {@link DroneSignal}, which runs before the GUI and blurs the world underneath
     * these layers. What is left for the overlay is everything a failing analogue link does on top
     * of a soft picture: a milky wash, a vignette, torn scan lines and grain.
     *
     * <p>Three layers, and the order they go down in is the whole trick.
     *
     * <p>A milky veil goes first. It lifts the blacks and flattens the contrast, and that loss of
     * contrast is what sells the picture as being on its last legs rather than merely dimmed - a
     * *dark* wash only dims it, which is why an earlier version of this looked like dust on top of
     * a sharp image. It is kept deliberately light: with a real blur underneath, the veil only has
     * to wash the colour out, and pushing it further just turns the screen into milk. A soft
     * vignette goes over that, because a lens losing focus pulls the edges in long before the
     * middle goes. Only then comes the grain.
     *
     * <p>The grain is deliberately one pixel. Bigger specks - even fringed with softer edges -
     * read as confetti sprinkled over the picture, which is exactly the wrong signal and what the
     * first two attempts at this got wrong. The picture goes soft and the noise stays fine:
     * softness belongs to the shader, never to the grain.
     *
     * <p>The grain is reseeded twenty-five times a second rather than every frame. Re-rolling it
     * per frame would just average out into flat grey at high frame rates; holding it for a few
     * frames is what makes it read as a struggling transmission rather than TV hiss.
     */
    private static void drawStatic(GuiGraphics graphics, int width, int height, double amount, long millis) {
        if (amount <= 0.0D) {
            return;
        }
        RandomSource random = RandomSource.create(millis / 40L);

        int veil = (int) (amount * 0x50);
        if (veil > 0) {
            graphics.fill(0, 0, width, height, veil << 24 | HAZE);
        }
        drawVignette(graphics, width, height, amount);

        // Torn lines, few and mostly short. Real analogue interference is overwhelmingly speckle;
        // the scan lines are the punctuation, not the sentence. An earlier pass drew ten of these
        // per grade and the picture read as venetian blinds rather than as a struggling feed.
        // One pixel tall, too: a thicker band stops reading as a torn scan line and starts reading
        // as a stripe drawn on top of the picture.
        int tears = (int) (amount * 4.0D);
        for (int i = 0; i < tears; i++) {
            int y = random.nextInt(height);
            int length = width / 5 + random.nextInt(Math.max(1, width * 3 / 5));
            int x = random.nextInt(Math.max(1, width - length));
            int alpha = 0x0A + random.nextInt(0x18);
            graphics.fill(x, y, Math.min(x + length, width), y + 1, alpha << 24 | 0xE4F2FA);
        }
        int fullTears = (int) (amount * 1.2D);
        for (int i = 0; i < fullTears; i++) {
            int y = random.nextInt(height);
            graphics.fill(0, y, width, y + 1, (0x0C + random.nextInt(0x18)) << 24 | 0xE4F2FA);
        }

        // The bulk of the effect. Dense and fine: at a typical GUI scale a "pixel" here is two or
        // three real pixels, which is what keeps it reading as noise rather than as confetti.
        int grains = Math.min((int) (width * height * 0.012D * amount), 14000);
        for (int i = 0; i < grains; i++) {
            int x = random.nextInt(width);
            int y = random.nextInt(height);
            int alpha = 0x16 + random.nextInt(0x48);
            // Bright and dark speckle both, in a narrow range: highlights on their own look like
            // dust rather than like noise.
            int grey = random.nextBoolean()
                    ? 0xE4 + random.nextInt(0x1C)
                    : 0x0C + random.nextInt(0x1C);
            graphics.fill(x, y, x + 1, y + 1, alpha << 24 | grey << 16 | grey << 8 | grey);
        }
    }

    /**
     * Speed streaks for the attack run.
     *
     * <p>The other half of what a charge should look like. The FOV pull in
     * {@link ClientEvents#onComputeFov} does the projection; this is what the eye actually reads as
     * speed, and it is drawn from the same ramp so the two cannot disagree.
     *
     * <p>Streaks sweep outward from the middle of the screen to the edges, and each one keeps its
     * own lane and its own speed. That stability is the whole trick, and it is why the angles come
     * from a hash of the slot index rather than from a random source: a fresh random angle every
     * frame does not read as motion at all, it reads as noise, which is what the signal breakup
     * already is. Only the radius is animated, and each streak fades in as it leaves the middle and
     * out again as it reaches the edge, so nothing pops.
     *
     * <p>Drawn under the frame and the readouts, like the snow. The instruments stay crisp.
     */
    private static void drawChargeStreaks(GuiGraphics graphics, int width, int height, long millis,
                                          double intensity) {
        if (intensity <= 0.02D) {
            return;
        }
        int cx = width / 2;
        int cy = height / 2;
        double reach = Math.hypot(width, height) * 0.5D;
        double inner = reach * STREAK_INNER;
        double span = reach - inner;
        double phase = millis / 900.0D;

        for (int i = 0; i < STREAK_COUNT; i++) {
            double lane = hash(i * 2);
            double angle = (i + lane) / STREAK_COUNT * Math.PI * 2.0D;
            double head = (phase * (0.7D + hash(i * 2 + 1) * 0.6D) + lane) % 1.0D;
            double tail = Math.max(0.0D, head - 0.22D - intensity * 0.18D);

            // Fade at both ends of the sweep so a streak appears out of the middle distance rather
            // than popping into existence at a fixed radius.
            int alpha = (int) (intensity * Math.sin(head * Math.PI) * (0x12 + hash(i * 3) * 0x26));
            if (alpha <= 2) {
                continue;
            }
            int colour = alpha << 24 | STREAK;

            double cos = Math.cos(angle);
            double sin = Math.sin(angle);
            int steps = 14;
            for (int s = 0; s <= steps; s++) {
                double t = inner + span * (tail + (head - tail) * s / (double) steps);
                int x = cx + (int) Math.round(cos * t);
                int y = cy + (int) Math.round(sin * t);
                if (x < 0 || y < 0 || x >= width || y >= height) {
                    continue;
                }
                graphics.fill(x, y, x + 1, y + 1, colour);
            }
        }
    }

    /** Deterministic 0..1 hash, so a streak keeps its lane and its speed from frame to frame. */
    private static double hash(int n) {
        int h = n * 0x9E3779B9;
        h ^= h >>> 15;
        h *= 0x85EBCA6B;
        h ^= h >>> 13;
        return (h & 0xFFFF) / 65535.0D;
    }

    /**
     * Soft edge falloff, stacked out of nested one pixel rings.
     *
     * <p>{@code GuiGraphics} only fills flat colour, so a gradient has to be built by layering:
     * each ring is one pixel thick and a shade stronger than the one inside it, which the eye
     * reads as a continuous darkening rather than as steps. It is the only part of the defocus
     * that is really the overlay's job - the blur pulls the whole picture soft, and this pulls the
     * corners in further, the way a lens giving up closes the edges first.
     */
    private static void drawVignette(GuiGraphics graphics, int width, int height, double amount) {
        int rings = Math.min(48, Math.min(width, height) / 4);
        for (int i = 0; i < rings; i++) {
            double falloff = 1.0D - (double) i / rings;
            int alpha = (int) (amount * 0x58 * falloff * falloff);
            if (alpha <= 0) {
                continue;
            }
            int colour = alpha << 24 | VIGNETTE;
            graphics.fill(i, i, width - i, i + 1, colour);
            graphics.fill(i, height - i - 1, width - i, height - i, colour);
            graphics.fill(i, i, i + 1, height - i, colour);
            graphics.fill(width - i - 1, i, width - i, height - i, colour);
        }
    }

    /** Blinking banner for the far end of the leash, where the picture is gone entirely. */
    private static void drawSignalLost(GuiGraphics graphics, Font font, int width, int height, long millis) {
        if ((millis / 300L) % 2L != 0L) {
            return;
        }
        String text = translate("hud.watchcraft.signal_lost");
        int x = (width - font.width(text)) / 2;
        int y = height / 2 + 30;
        graphics.fill(x - 8, y - 4, x + font.width(text) + 8, y + 11, PANEL);
        graphics.drawString(font, text, x, y, HURT, false);
    }
    // ------------------------------------------------------------------ frame

    private static void drawFrame(GuiGraphics graphics, int width, int height, long millis) {
        int margin = 6;
        int right = width - margin;
        int bottom = height - margin;

        // Hairline border, interrupted in the middle of each edge so it does not feel boxed in.
        graphics.fill(margin, margin, right, margin + 1, CYAN_FAINT);
        graphics.fill(margin, bottom - 1, right, bottom, CYAN_FAINT);
        graphics.fill(margin, margin, margin + 1, bottom, CYAN_FAINT);
        graphics.fill(right - 1, margin, right, bottom, CYAN_FAINT);

        int arm = 18;
        int thick = 2;
        int offset = margin + 3;
        // Top left / top right / bottom left / bottom right brackets.
        bracket(graphics, offset, offset, arm, thick, 1, 1);
        bracket(graphics, width - offset, offset, arm, thick, -1, 1);
        bracket(graphics, offset, height - offset, arm, thick, 1, -1);
        bracket(graphics, width - offset, height - offset, arm, thick, -1, -1);

        // Slow scan bar sweeping down the screen.
        int sweep = (int) ((millis / 24L) % (height + 120)) - 60;
        if (sweep > 0 && sweep < height) {
            graphics.fill(margin, sweep, right, sweep + 1, 0x1422D3EE);
        }
    }

    /**
     * One corner bracket. {@code (x, y)} is the <em>outer</em> corner, and the two arms grow inwards
     * along the direction signs: {@code dx} is +1 on the left edge and -1 on the right, {@code dy}
     * is +1 along the top edge and -1 along the bottom.
     *
     * <p>Anchoring on the outer corner rather than on the far end of the horizontal arm is what
     * keeps the vertical stroke sitting on the corner instead of drifting inwards on the right
     * hand side of the screen.
     */
    private static void bracket(GuiGraphics graphics, int x, int y, int arm, int thick, int dx, int dy) {
        int hLeft = dx > 0 ? x : x - arm;
        int hRight = dx > 0 ? x + arm : x;
        int hTop = dy > 0 ? y : y - thick;
        int hBottom = dy > 0 ? y + thick : y;

        int vLeft = dx > 0 ? x : x - thick;
        int vRight = dx > 0 ? x + thick : x;
        int vTop = dy > 0 ? y : y - arm;
        int vBottom = dy > 0 ? y + arm : y;

        graphics.fill(hLeft, hTop, hRight, hBottom, CYAN_DIM);
        graphics.fill(vLeft, vTop, vRight, vBottom, CYAN_DIM);
    }

    // ------------------------------------------------------------------ reticle

    /**
     * A single dot.
     *
     * <p>It gets a one pixel dark halo purely for legibility: a bare cyan pixel vanishes against
     * snow, clouds and the sky, which is most of what a scout drone ends up looking at.
     */
    private static void drawReticle(GuiGraphics graphics, int width, int height) {
        int cx = width / 2;
        int cy = height / 2;
        graphics.fill(cx - 2, cy - 2, cx + 2, cy + 2, RETICLE_HALO);
        graphics.fill(cx - 1, cy - 1, cx + 1, cy + 1, CYAN);
    }

    // ------------------------------------------------------------------ telemetry

    /** The only text on the visor: X, Y, range back to the operator and remaining hit points. */
    private static void drawTelemetry(GuiGraphics graphics, Font font, ReconDroneEntity drone,
                                      double range, long millis) {
        String labelX = Component.translatable("hud.watchcraft.axis_x").getString();
        String labelY = Component.translatable("hud.watchcraft.axis_y").getString();
        String labelR = Component.translatable("hud.watchcraft.range").getString();
        String labelH = Component.translatable("hud.watchcraft.health").getString();

        String valueX = format(drone.getX());
        String valueY = format(drone.getY());
        String valueR = format(range);
        float health = drone.getHealth();
        String valueH = whole(health) + " / " + whole(ReconDroneEntity.MAX_HEALTH);

        int lineHeight = 11;
        int padding = 9;
        int labelWidth = Math.max(Math.max(font.width(labelX), font.width(labelY)),
                Math.max(font.width(labelR), font.width(labelH)));
        int valueWidth = Math.max(Math.max(font.width(valueX), font.width(valueY)),
                Math.max(font.width(valueR), font.width(valueH)));

        int panelX = 12;
        int panelY = 12;
        int panelWidth = padding * 2 + labelWidth + 10 + valueWidth;
        int panelHeight = lineHeight * 4 + 11;

        graphics.fill(panelX, panelY, panelX + panelWidth, panelY + panelHeight, PANEL);
        // Live pulse on the accent bar so a parked drone still reads as "on".
        boolean blink = (millis / 600L) % 2L == 0L;
        graphics.fill(panelX, panelY, panelX + 2, panelY + panelHeight, blink ? CYAN : CYAN_DIM);

        int textX = panelX + padding;
        int valueRight = panelX + panelWidth - padding;
        int textY = panelY + 6;

        row(graphics, font, labelX, valueX, textX, valueRight, textY, TEXT_DIM, TEXT);
        row(graphics, font, labelY, valueY, textX, valueRight, textY + lineHeight, TEXT_DIM, TEXT);
        row(graphics, font, labelR, valueR, textX, valueRight, textY + lineHeight * 2, TEXT_DIM,
                range > linkWarn() ? AMBER : CYAN);
        row(graphics, font, labelH, valueH, textX, valueRight, textY + lineHeight * 3, TEXT_DIM,
                health <= ReconDroneEntity.MAX_HEALTH * 0.3F ? HURT : CYAN);
    }

    private static void row(GuiGraphics graphics, Font font, String label, String value,
                            int labelX, int valueRight, int y, int labelColor, int valueColor) {
        graphics.drawString(font, label, labelX, y, labelColor, false);
        graphics.drawString(font, value, valueRight - font.width(value), y, valueColor, false);
    }

    private static String format(double value) {
        return String.format(Locale.ROOT, "%.1f", value);
    }

    private static String whole(float value) {
        return String.format(Locale.ROOT, "%.0f", value);
    }

    // ------------------------------------------------------------------ key strip

    private static final String SEPARATOR = "   ";
    private static final int ROW_HEIGHT = 12;

    /** One control hint, with the colour it should be drawn in. */
    private record Hint(String text, int colour) {
    }

    /**
     * Bottom strip listing the controls. Sits where the vanilla experience bar used to.
     *
     * <p>Laid out as measured segments rather than as one long string, for two reasons. The run
     * hint only exists once a warhead is fitted, so the strip genuinely changes length; and the
     * whole thing already ran close to the width of a small window at GUI scale 2. Filling rows
     * greedily and stacking them upwards means a longer strip wraps instead of running off the
     * edges, which is what a single string could not do.
     *
     * <p>The run entry is the one piece of text on the visor that is not dim cyan. It destroys the
     * drone, and it should not be discovered by accident.
     */
    private static void drawHints(GuiGraphics graphics, Font font, int width, int height, String chargeKeys) {
        List<Hint> hints = new ArrayList<>();
        hints.add(new Hint("[" + key(KeyMappings.LINK) + "] " + translate("hud.watchcraft.disconnect"), TEXT_DIM));
        hints.add(new Hint("[" + key(KeyMappings.THROW) + "] " + translate("hud.watchcraft.throw"), TEXT_DIM));
        hints.add(new Hint("[" + key(KeyMappings.RECALL) + "] " + translate("hud.watchcraft.recall"), TEXT_DIM));
        if (chargeKeys != null) {
            hints.add(new Hint("[" + chargeKeys + "] " + translate("hud.watchcraft.charge"), HURT));
            hints.add(new Hint("[" + key(KeyMappings.DETONATE) + "] "
                    + translate("hud.watchcraft.detonate"), HURT));
        }
        hints.add(new Hint("[WASD] " + translate("hud.watchcraft.move"), TEXT_DIM));
        hints.add(new Hint("[SPACE/SHIFT] " + translate("hud.watchcraft.altitude"), TEXT_DIM));
        // 滚筒亮起来的时候给它上高亮，让"现在正在滚 / 正在冲刺"这件事看得见。
        // 冲刺时再换一种更亮的颜色，和普通滚筒区分开。
        hints.add(new Hint("[" + key(KeyMappings.ROLL) + "] " + translate("hud.watchcraft.roll"),
                DroneController.isRollerDashing() ? HURT
                        : DroneController.isRollerActive() ? CYAN : TEXT_DIM));
        hints.add(new Hint("[" + translate("hud.watchcraft.look") + "] " + translate("hud.watchcraft.aim"), TEXT_DIM));

        int gap = font.width(SEPARATOR);
        int maxWidth = Math.max(80, width - 16);
        List<List<Hint>> rows = new ArrayList<>();
        List<Hint> row = new ArrayList<>();
        int rowWidth = 0;
        for (Hint hint : hints) {
            int textWidth = font.width(hint.text());
            int extra = row.isEmpty() ? 0 : gap;
            if (!row.isEmpty() && rowWidth + extra + textWidth > maxWidth) {
                rows.add(row);
                row = new ArrayList<>();
                rowWidth = 0;
                extra = 0;
            }
            rowWidth += extra + textWidth;
            row.add(hint);
        }
        if (!row.isEmpty()) {
            rows.add(row);
        }

        int y = height - 26 - (rows.size() - 1) * ROW_HEIGHT;
        for (List<Hint> line : rows) {
            int total = -gap;
            for (Hint hint : line) {
                total += font.width(hint.text()) + gap;
            }
            int x = (width - total) / 2;
            graphics.fill(x - 6, y - 3, x + total + 6, y + 9, PANEL);
            for (Hint hint : line) {
                graphics.drawString(font, hint.text(), x, y, hint.colour(), false);
                x += font.width(hint.text()) + gap;
            }
            y += ROW_HEIGHT;
        }
    }

    private static String key(net.minecraft.client.KeyMapping mapping) {
        return mapping.getTranslatedKeyMessage().getString();
    }

    /** The one way to trigger an attack run, spelled out from whatever the player has it bound to. */
    private static String chargeKeys(Minecraft minecraft) {
        return key(minecraft.options.keySprint);
    }

    private static String translate(String key) {
        return Component.translatable(key).getString();
    }
}
