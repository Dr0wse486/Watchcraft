package dev.watchcraft.watchcraft.client;

import dev.watchcraft.watchcraft.config.WatchcraftConfig;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.neoforged.neoforge.common.ModConfigSpec;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.DoubleConsumer;
import java.util.function.Supplier;

/**
 * 模组的配置界面：左侧分类，右侧条目。从暂停菜单或模组列表的"配置"按钮进入。
 *
 * <p><b>只暴露 CLIENT 那份配置。</b>COMMON 里的速度、伤害、扫描半径、爆炸半径是服务器说了算的，
 * 摆在客户端界面里会让人以为改了就能生效，联机时却会被服务器覆盖 —— 那种界面比没有界面更糟，
 * 因为它会让人对不上数。要看平衡值直接编辑 {@code config/watchcraft-common.toml}。
 *
 * <p><b>不重复维护第二份数值表。</b>滑条的上下限直接从 {@code ModConfigSpec.Range} 读，
 * 悬停提示直接用配置项自己的注释。所以改配置里的 {@code defineInRange} 或 {@code comment}，
 * 界面会自动跟着变，不存在"界面里写的和配置里写的不一致"这种漂移。
 *
 * <p><b>即时生效但不即时落盘。</b>拖滑条时每次都写文件既慢又伤盘，所以在 {@link #onClose()}
 * 里统一 save 一次。代价是中途崩溃会丢掉本次改动，这个取舍是刻意的。
 */
public final class WatchcraftConfigScreen extends Screen {

    // ------------------------------------------------------------------ 布局

    private static final int MARGIN = 12;
    private static final int SIDEBAR_WIDTH = 116;
    private static final int TAB_HEIGHT = 20;
    private static final int TAB_GAP = 4;
    private static final int ROW_HEIGHT = 20;
    private static final int ROW_GAP = 6;
    private static final int WIDGET_WIDTH = 150;
    private static final int HEADER_HEIGHT = 30;
    private static final int FOOTER_HEIGHT = 30;
    private static final int SWATCH_SIZE = 18;
    private static final int SWATCH_GAP = 5;
    private static final int HEX_WIDTH = 84;

    /**
     * 主色的预设色块。六个刚好铺满一行又覆盖得住常见色系：青、绿、琥珀、红、紫、白。
     * 想要别的颜色用旁边的输入框，色块只是常用值的快捷方式。
     */
    private static final int[] SWATCHES = {
            0x3BE8FF, 0x4CFF9E, 0xFFC24B, 0xFF5C5C, 0xB07BFF, 0xF2F5F7,
    };

    private static final String[] TAB_KEYS = {
            "watchcraft.config.tab.flight",
            "watchcraft.config.tab.view",
            "watchcraft.config.tab.roll",
            "watchcraft.config.tab.detonation",
            "watchcraft.config.tab.hud",
            "watchcraft.config.tab.recon",
    };

    private final Screen parent;

    /** 当前分类，{@link #TAB_KEYS} 的下标。 */
    private int tab;
    /** 内容区滚动偏移，单位像素。只作用于条目，不碰侧栏与页脚。 */
    private double scroll;
    /** 上一次布局算出的整数偏移，画标签时要和控件用同一个值。 */
    private int scrollOffset;

    /**
     * 页面上每个控件连同它的"基准 Y"。滚动时统一按基准 Y 加偏移重排，
     * 所以基准值必须留着 —— 只存控件的当前 Y 会在第二次滚动时叠加偏移。
     */
    private final List<Placed> placed = new ArrayList<>();
    /** 画在控件左边的标签。 */
    private final List<Label> labels = new ArrayList<>();
    /** 本页所有控件，重建页面时用来清场。 */
    private final List<AbstractWidget> pageWidgets = new ArrayList<>();
    /** 本页涉及的配置项，"重置本页"按它逐个还原成默认值。 */
    private final List<ModConfigSpec.ConfigValue<?>> pageValues = new ArrayList<>();

    /** 建页时的纵向游标，相对于内容区顶部。 */
    private int cursor;

    /** 主色输入框，色块点选时要回填它。 */
    private EditBox hexBox;
    /** 当前主色的 RGB（不含 alpha）。色块与输入框读写同一份，省得两处各自解析字符串。 */
    private int accent = DroneHud.DEFAULT_ACCENT;
    /** 有没有改过东西，决定关闭时要不要落盘。 */
    private boolean dirty;

    public WatchcraftConfigScreen(Screen parent) {
        super(Component.translatable("watchcraft.config.title"));
        this.parent = parent;
    }

    // ------------------------------------------------------------------ 建页

    @Override
    protected void init() {
        // rebuildWidgets 会清空原版的控件表，但清不掉这几个自己的账本，得手动来。
        pageWidgets.clear();
        placed.clear();
        labels.clear();
        pageValues.clear();
        hexBox = null;

        buildTabs();
        buildPage();
        buildFooter();
    }

    private void buildTabs() {
        int y = HEADER_HEIGHT;
        for (int i = 0; i < TAB_KEYS.length; i++) {
            final int index = i;
            Button button = Button.builder(Component.translatable(TAB_KEYS[i]), b -> switchTo(index))
                    .bounds(MARGIN, y, SIDEBAR_WIDTH, TAB_HEIGHT)
                    .build();
            // 当前分类直接置灰。原版按钮的禁用态自带一套描边和灰度，比自己画高亮更稳，
            // 也不会因为资源包改了按钮贴图就失效。
            button.active = index != tab;
            addRenderableWidget(button);
            y += TAB_HEIGHT + TAB_GAP;
        }
    }

    private void buildFooter() {
        addRenderableWidget(Button.builder(Component.translatable("watchcraft.config.reset"), b -> resetPage())
                .bounds(contentX(), footerY(), 110, ROW_HEIGHT)
                .tooltip(Tooltip.create(Component.translatable("watchcraft.config.reset.tip")))
                .build());
        addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, b -> onClose())
                .bounds(contentRight() - 110, footerY(), 110, ROW_HEIGHT)
                .build());
    }

    private void switchTo(int index) {
        if (index == tab) {
            return;
        }
        tab = index;
        scroll = 0;
        rebuildWidgets();
    }

    private void buildPage() {
        cursor = 0;
        switch (tab) {
            case 0 -> buildFlight();
            case 1 -> buildView();
            case 2 -> buildRoll();
            case 3 -> buildDetonation();
            case 4 -> buildHud();
            case 5 -> buildRecon();
            default -> { }
        }
        layout();
    }

    private void buildFlight() {
        addDouble(WatchcraftConfig.CLIENT.flightAcceleration, "watchcraft.config.flight.acceleration", "");
        addDouble(WatchcraftConfig.CLIENT.flightDeceleration, "watchcraft.config.flight.deceleration", "");
        addDouble(WatchcraftConfig.CLIENT.flightBankSpeedLoss, "watchcraft.config.flight.bankSpeedLoss", "");
        addDouble(WatchcraftConfig.CLIENT.flightDiveSpeedGain, "watchcraft.config.flight.diveSpeedGain", "");
        addDouble(WatchcraftConfig.CLIENT.lookGain, "watchcraft.config.look.gain", "x");
        addDouble(WatchcraftConfig.CLIENT.lookSmoothing, "watchcraft.config.look.smoothing", "s");

        // 小冲刺和飞行手感放在同一页：它改的就是速度上限与视场角，和上面几条是一回事。
        addToggle(WatchcraftConfig.CLIENT.dashEnabled, "watchcraft.config.dash.enabled");
        addDouble(WatchcraftConfig.CLIENT.dashSpeedGain, "watchcraft.config.dash.speedGain", "x");
        addInt(WatchcraftConfig.CLIENT.dashTicks, "watchcraft.config.dash.ticks", "");
        addInt(WatchcraftConfig.CLIENT.dashCooldownTicks, "watchcraft.config.dash.cooldownTicks", "");
        addDouble(WatchcraftConfig.CLIENT.dashFovGain, "watchcraft.config.dash.fovGain", "x");
    }

    private void buildView() {
        addDouble(WatchcraftConfig.CLIENT.cameraBankShare, "watchcraft.config.camera.bankShare", "");
        addDouble(WatchcraftConfig.CLIENT.speedFovGain, "watchcraft.config.fov.speedGain", "");
        addDouble(WatchcraftConfig.CLIENT.speedFovEase, "watchcraft.config.fov.speedEase", "");
        addDouble(WatchcraftConfig.CLIENT.chargeFovGain, "watchcraft.config.fov.chargeGain", "x");
        addDouble(WatchcraftConfig.CLIENT.chargeFovEase, "watchcraft.config.fov.chargeEase", "");
        addDouble(WatchcraftConfig.CLIENT.chargeAimTau, "watchcraft.config.charge.aimTau", "s");
        addDouble(WatchcraftConfig.CLIENT.chargeAimGain, "watchcraft.config.charge.aimGain", "x");
        addDouble(WatchcraftConfig.CLIENT.signalMaxBlurRadius, "watchcraft.config.signal.maxBlurRadius", "px");
    }

    private void buildRoll() {
        addInt(WatchcraftConfig.CLIENT.rollTicks, "watchcraft.config.roll.ticks", "");
        addInt(WatchcraftConfig.CLIENT.rollCooldownTicks, "watchcraft.config.roll.cooldownTicks", "");
        addInt(WatchcraftConfig.CLIENT.rollerRecoverTicks, "watchcraft.config.roll.recoverTicks", "");
        addDouble(WatchcraftConfig.CLIENT.rollCameraShare, "watchcraft.config.roll.cameraShare", "");
        addDouble(WatchcraftConfig.CLIENT.rollerTurnsPerSecond, "watchcraft.config.roll.turnsPerSecond", "");
        addDouble(WatchcraftConfig.CLIENT.rollerDashSpeedGain, "watchcraft.config.roll.dashSpeedGain", "");
        addDouble(WatchcraftConfig.CLIENT.rollerFovGain, "watchcraft.config.roll.fovGain", "x");
    }

    private void buildDetonation() {
        addToggle(WatchcraftConfig.CLIENT.detonationShakeEnabled, "watchcraft.config.detonation.shakeEnabled");
        addDouble(WatchcraftConfig.CLIENT.detonationShakeSeconds, "watchcraft.config.detonation.shakeSeconds", "s");
        addDouble(WatchcraftConfig.CLIENT.detonationShakeDisplacement,
                "watchcraft.config.detonation.shakeDisplacement", "");
        addDouble(WatchcraftConfig.CLIENT.detonationShakeAngle, "watchcraft.config.detonation.shakeAngle", "°");
        addDouble(WatchcraftConfig.CLIENT.detonationRadialBlur, "watchcraft.config.detonation.radialBlur", "x");
        addInt(WatchcraftConfig.CLIENT.detonationTinnitusDelayMs,
                "watchcraft.config.detonation.tinnitusDelayMs", "ms");
        addDouble(WatchcraftConfig.CLIENT.detonationTinnitusVolume,
                "watchcraft.config.detonation.tinnitusVolume", "");
        addDouble(WatchcraftConfig.CLIENT.detonationTinnitusPitch,
                "watchcraft.config.detonation.tinnitusPitch", "x");
    }

    private void buildHud() {
        // 每次建页都从配置重读一遍：重置本页走的是"改配置再重建"，这里不重读的话
        // 输入框和色块会停在旧值上。
        accent = DroneHud.parseHexColor(WatchcraftConfig.CLIENT.hudAccentColor.get(), DroneHud.DEFAULT_ACCENT);

        hexBox = new EditBox(font, widgetX(), 0, HEX_WIDTH, ROW_HEIGHT, Component.empty());
        hexBox.setMaxLength(7);
        hexBox.setValue(DroneHud.toHexColor(accent));
        hexBox.setResponder(this::onHexTyped);
        hexBox.setTooltip(Tooltip.create(Component.translatable("watchcraft.config.hud.accent.tip")));
        addRow(hexBox, nextRowY(), "watchcraft.config.hud.accent");
        pageValues.add(WatchcraftConfig.CLIENT.hudAccentColor);

        // 色块另起一行。和输入框挤在一行会只剩几十像素，六个方块放不下。
        int swatchY = cursor;
        int x = contentX();
        for (int rgb : SWATCHES) {
            SwatchButton swatch = new SwatchButton(x, 0, SWATCH_SIZE, rgb, () -> accent, this::pickColor);
            addRow(swatch, swatchY, null);
            x += SWATCH_SIZE + SWATCH_GAP;
        }
        cursor = swatchY + SWATCH_SIZE + ROW_GAP;

        addToggle(WatchcraftConfig.CLIENT.hudShowTelemetry, "watchcraft.config.hud.telemetry");
        addToggle(WatchcraftConfig.CLIENT.hudShowHints, "watchcraft.config.hud.hints");
    }

    /**
     * 侦察回传这一页。
     *
     * <p>这里只有<b>画面</b>部分。标什么、标多远、要不要视线，那些是服务端的平衡值，
     * 放在 COMMON 里由服务器说了算 —— 客户端能改的只有"怎么画"，和上面几页的分工一致。
     */
    private void buildRecon() {
        addToggle(WatchcraftConfig.CLIENT.markerShowDistance, "watchcraft.config.marker.showDistance");
        addToggle(WatchcraftConfig.CLIENT.markerEdgeIndicator, "watchcraft.config.marker.edgeIndicator");
        addInt(WatchcraftConfig.CLIENT.markerMaxLabels, "watchcraft.config.marker.maxLabels", "");

        addToggle(WatchcraftConfig.CLIENT.alertShowBorder, "watchcraft.config.alert.showBorder");
        addToggle(WatchcraftConfig.CLIENT.alertShowDirection, "watchcraft.config.alert.showDirection");
        addInt(WatchcraftConfig.CLIENT.alertPulseTicks, "watchcraft.config.alert.pulseTicks", "");
        addDouble(WatchcraftConfig.CLIENT.alertMaxAlpha, "watchcraft.config.alert.maxAlpha", "");
    }

    // ------------------------------------------------------------------ 条目工厂

    /** 取下一行的基准 Y，并把游标推到再下一行。 */
    private int nextRowY() {
        int y = cursor;
        cursor += ROW_HEIGHT + ROW_GAP;
        return y;
    }

    /**
     * 把控件登记到页面上。
     *
     * <p>三份账本各管一件事：原版的控件表负责事件，{@code placed} 记基准 Y 供滚动重排，
     * {@code labels} 记标签。{@code labelKey} 传 null 表示这行不画标签（色块那行就不需要）。
     *
     * <p>用的是 {@code addWidget} 而不是 {@code addRenderableWidget}：前者只进 children 与
     * narratables，不进 renderables，于是条目不会被 {@code super.render} 顺手画一遍 ——
     * 它们由 {@link #render} 自己在裁剪区里画，见那里的注释。
     */
    private void addRow(AbstractWidget widget, int baseY, String labelKey) {
        addWidget(widget);
        pageWidgets.add(widget);
        placed.add(new Placed(widget, baseY));
        if (labelKey != null) {
            labels.add(new Label(labelKey, baseY, widget.getHeight()));
        }
    }

    private void addDouble(ModConfigSpec.DoubleValue value, String labelKey, String suffix) {
        addSlider(value, labelKey, suffix, false, value::set);
    }

    private void addInt(ModConfigSpec.IntValue value, String labelKey, String suffix) {
        addSlider(value, labelKey, suffix, true, v -> value.set((int) Math.round(v)));
    }

    private void addSlider(ModConfigSpec.ConfigValue<?> value, String labelKey, String suffix,
                           boolean integer, DoubleConsumer writer) {
        ModConfigSpec.ValueSpec spec = value.getSpec();
        double min = 0.0D;
        double max = 1.0D;
        if (spec != null) {
            var range = spec.getRange();
            if (range != null && range.getMin() instanceof Number lo && range.getMax() instanceof Number hi) {
                min = lo.doubleValue();
                max = hi.doubleValue();
            }
        }
        double initial = value.get() instanceof Number number ? number.doubleValue() : min;
        ValueSlider slider = new ValueSlider(widgetX(), 0, widgetWidth(), ROW_HEIGHT,
                min, max, initial, integer, suffix, writer, this::applyAndTrack);
        addRow(slider, nextRowY(), labelKey);
        pageValues.add(value);
        addCommentTooltip(slider, spec);
    }

    private void addToggle(ModConfigSpec.BooleanValue value, String labelKey) {
        Button button = Button.builder(toggleLabel(value.get()), b -> {
            boolean next = !value.get();
            value.set(next);
            b.setMessage(toggleLabel(next));
            applyAndTrack();
        }).bounds(widgetX(), 0, widgetWidth(), ROW_HEIGHT).build();
        addRow(button, nextRowY(), labelKey);
        pageValues.add(value);
        addCommentTooltip(button, value.getSpec());
    }

    private static Component toggleLabel(boolean on) {
        // 复用原版的 ON / OFF，省一对语言键，也自动跟着游戏语言走。
        return Component.translatable(on ? "options.on" : "options.off");
    }

    /**
     * 把配置项自己的注释挂成悬停提示。
     *
     * <p>这样说明文字只有一份 —— 就是 toml 里那份。缺点是这些注释目前只有中文，
     * 英文环境下的提示会是中文。要正经做 i18n 得给每项配一对语言键，那是另一件事。
     */
    private static void addCommentTooltip(AbstractWidget widget, ModConfigSpec.ValueSpec spec) {
        if (spec != null && spec.getComment() != null) {
            widget.setTooltip(Tooltip.create(Component.literal(spec.getComment())));
        }
    }

    // ------------------------------------------------------------------ 布局与滚动

    private int topY() {
        return HEADER_HEIGHT;
    }

    private int footerY() {
        return height - FOOTER_HEIGHT + 4;
    }

    private int contentX() {
        return MARGIN + SIDEBAR_WIDTH + 12;
    }

    private int contentRight() {
        return width - MARGIN;
    }

    private int widgetWidth() {
        return Math.min(WIDGET_WIDTH, Math.max(80, (contentRight() - contentX()) / 2));
    }

    private int widgetX() {
        return contentRight() - widgetWidth();
    }

    private int viewportHeight() {
        return Math.max(40, footerY() - topY() - ROW_GAP);
    }

    private int contentHeight() {
        int bottom = 0;
        for (Placed entry : placed) {
            bottom = Math.max(bottom, entry.baseY() + entry.widget().getHeight());
        }
        return bottom;
    }

    private void layout() {
        scroll = Mth.clamp(scroll, 0.0D, Math.max(0, contentHeight() - viewportHeight()));
        scrollOffset = (int) -Math.round(scroll);
        int top = topY();
        int bottom = top + viewportHeight();
        for (Placed entry : placed) {
            AbstractWidget widget = entry.widget();
            int y = top + entry.baseY() + scrollOffset;
            widget.setY(y);
            // 完全滚出视野的行直接隐藏。裁剪只管画，管不住点击（AbstractWidget#clicked 只看
            // 矩形，不查裁剪区），而鼠标停在页脚上时不该点到一条已经看不见的滑条。
            widget.visible = y + widget.getHeight() > top && y < bottom;
        }
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (contentHeight() <= viewportHeight()) {
            return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
        }
        scroll -= scrollY * (ROW_HEIGHT + ROW_GAP);
        layout();
        return true;
    }

    // ------------------------------------------------------------------ 改动与落盘

    /** 配置改了之后立刻刷进各处静态镜像，这样松手就能看到效果，不用重启。 */
    private void applyAndTrack() {
        WatchcraftClient.applyClientConfig();
        dirty = true;
    }

    private void pickColor(int rgb) {
        accent = rgb & 0xFFFFFF;
        String hex = DroneHud.toHexColor(accent);
        // 回填输入框。先比对再写，否则 setValue 会再触发一次 responder。
        if (hexBox != null && !hex.equalsIgnoreCase(hexBox.getValue())) {
            hexBox.setValue(hex);
        }
        WatchcraftConfig.CLIENT.hudAccentColor.set(hex);
        applyAndTrack();
    }

    private void onHexTyped(String text) {
        int parsed = DroneHud.parseHexColor(text, -1);
        // 输入到一半（比如刚敲了个 #）解析不出来是正常的，先不生效，别弹错。
        if (parsed < 0 || parsed == accent) {
            return;
        }
        accent = parsed;
        WatchcraftConfig.CLIENT.hudAccentColor.set(DroneHud.toHexColor(parsed));
        applyAndTrack();
    }

    /**
     * 把本页的配置项全部还原成默认值。
     *
     * <p>用 {@code getDefault()} 而不是自己记一份默认值表：默认值只在配置类里写了一次，
     * 这里跟着走，改默认值不用改两处。
     */
    private void resetPage() {
        for (ModConfigSpec.ConfigValue<?> value : pageValues) {
            restoreDefault(value);
        }
        applyAndTrack();
        // 重建而不是逐个改控件：控件数量、行高、标签都要跟着变，重建最省事也最不容易错。
        rebuildWidgets();
    }

    /**
     * 把一项配置写回它的默认值。
     *
     * <p>单独抽出来是为了避开裸类型：{@code ConfigValue<?>} 直接调 {@code set} 需要
     * {@code @SuppressWarnings}，而交给泛型方法时编译器会把通配符捕获成一个具体类型，写得干净。
     */
    private static <T> void restoreDefault(ModConfigSpec.ConfigValue<T> value) {
        value.set(value.getDefault());
    }

    @Override
    public void onClose() {
        // 落盘放在这里而不是每次改动：拖一次滑条会触发几十次 applyValue，
        // 每次都写文件既慢又没必要。
        if (dirty) {
            WatchcraftConfig.CLIENT_SPEC.save();
        }
        if (minecraft != null) {
            minecraft.setScreen(parent);
        }
    }

    // ------------------------------------------------------------------ 绘制

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        // super 铺背景，并把侧栏与页脚画掉 —— 它们不参与滚动，不进裁剪区。
        super.render(graphics, mouseX, mouseY, partialTick);

        // 条目单独走一遍，夹在可视区里。窗口矮的时候滚出视野的行会盖到页脚按钮上，而裁剪是
        // 唯一能一并管住绘制与悬停的办法：AbstractWidget#render 里的 isHovered 会查
        // containsPointInScissor，所以夹住之后，看不见的行连悬停高亮都不会有。
        graphics.enableScissor(0, topY() - ROW_GAP / 2, width, footerY() - ROW_GAP / 2);
        for (AbstractWidget widget : pageWidgets) {
            widget.render(graphics, mouseX, mouseY, partialTick);
        }
        for (Label label : labels) {
            int y = topY() + label.baseY() + scrollOffset + (label.height() - 8) / 2;
            graphics.drawString(font, Component.translatable(label.key()), contentX(), y, 0xFFBFD6DD, false);
        }
        graphics.disableScissor();

        graphics.drawCenteredString(font, title, width / 2, 11, 0xFFFFFFFF);
        graphics.fill(MARGIN + SIDEBAR_WIDTH + 5, HEADER_HEIGHT, MARGIN + SIDEBAR_WIDTH + 6,
                height - FOOTER_HEIGHT, 0x40FFFFFF);
    }

    // ------------------------------------------------------------------ 内部类型

    /** 一个控件加它的基准 Y。 */
    private record Placed(AbstractWidget widget, int baseY) {
    }

    /** 一行左侧的标签，连同它那一行的高度（用来垂直居中）。 */
    private record Label(String key, int baseY, int height) {
    }

    /**
     * 一条绑定到配置项的滑条。
     *
     * <p>上下限来自 {@code ModConfigSpec.Range}，所以界面不会给出配置本身会拒绝的值。
     * 数值显示的小数位随量程走：量程大的不给小数，量程小的给到四位，否则 0.001 那一档
     * 在滑条上根本看不出动。
     */
    private static final class ValueSlider extends AbstractSliderButton {

        private final double min;
        private final double max;
        private final boolean integer;
        private final String suffix;
        private final DoubleConsumer writer;
        private final Runnable onChanged;

        ValueSlider(int x, int y, int width, int height, double min, double max, double initial,
                    boolean integer, String suffix, DoubleConsumer writer, Runnable onChanged) {
            super(x, y, width, height, Component.empty(), max > min ? (initial - min) / (max - min) : 0.0D);
            this.min = min;
            this.max = max;
            this.integer = integer;
            this.suffix = suffix;
            this.writer = writer;
            this.onChanged = onChanged;
            // 字段都就位了才敢刷文字，updateMessage 要用到它们。
            updateMessage();
        }

        private double current() {
            double raw = min + (max - min) * value;
            return integer ? Math.round(raw) : raw;
        }

        @Override
        protected void updateMessage() {
            setMessage(Component.literal(display(current()) + suffix));
        }

        @Override
        protected void applyValue() {
            writer.accept(current());
            onChanged.run();
        }

        private String display(double v) {
            if (integer) {
                return Long.toString(Math.round(v));
            }
            double span = max - min;
            int decimals = span >= 20.0D ? 0 : span >= 2.0D ? 2 : span >= 0.2D ? 3 : 4;
            // Locale.ROOT：某些区域用逗号当小数点，读起来会以为是千位分隔。
            return String.format(Locale.ROOT, "%." + decimals + "f", v);
        }
    }

    /**
     * 一个纯色方块，用来点选 HUD 主色。
     *
     * <p>不用原版按钮的贴图是因为那套灰底完全看不出颜色。自己画反而更简单：一圈描边加一块
     * 实色，选中的那个在中间点一个深色小方块 —— 比画个对勾省事，在任何底色上都读得出来。
     */
    private static final class SwatchButton extends Button {

        private final int rgb;
        private final Supplier<Integer> current;
        private final java.util.function.IntConsumer pick;

        SwatchButton(int x, int y, int size, int rgb, Supplier<Integer> current,
                     java.util.function.IntConsumer pick) {
            super(x, y, size, size, Component.empty(), b -> { }, DEFAULT_NARRATION);
            this.rgb = rgb;
            this.current = current;
            this.pick = pick;
        }

        @Override
        public void onClick(double mouseX, double mouseY) {
            pick.accept(rgb);
        }

        @Override
        protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
            int x = getX();
            int y = getY();
            int border = isHoveredOrFocused() ? 0xFFFFFFFF : 0x50FFFFFF;
            graphics.fill(x - 1, y - 1, x + width + 1, y + height + 1, border);
            graphics.fill(x, y, x + width, y + height, 0xFF000000 | rgb);
            if (current.get() == rgb) {
                graphics.fill(x + 6, y + 6, x + width - 6, y + height - 6, 0xFF101418);
            }
        }
    }
}
