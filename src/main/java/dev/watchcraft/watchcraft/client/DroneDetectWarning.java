package dev.watchcraft.watchcraft.client;

import dev.watchcraft.watchcraft.network.DroneAlertPayload;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.PlayerFaceRenderer;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.resources.DefaultPlayerSkin;
import net.minecraft.client.resources.PlayerSkin;
import net.minecraft.network.chat.Component;

import java.util.UUID;

/**
 * 「你被无人机看到了」——屏幕上方居中的一条横幅，左边是那位驾驶者的头像。
 *
 * <p>这是模组里唯一一条<b>发给别人的</b>消息。其余每一条都在向无人机的主人汇报；
 * 这一条告诉被扫到的人"你正在被看着，而且是这个人"。
 *
 * <p>头像是按 UUID 现取的，不走网络：署名者可能远在几百格之外，他的实体在收件人的客户端上
 * 根本不存在，但玩家列表里有他，{@code PlayerInfo#getSkin()} 就是够的。列表里也找不到时
 * （离线服务器、玩家刚退出）退回默认皮肤，横幅照常显示 —— 名字那半边信息比头像重要。
 *
 * <p>生命周期与 {@link DroneMarkerOverlay} 完全相反：那边是"一直挂着直到服务端纠正"，
 * 这边是"自己到点就消失"。一条一次性的提醒不该需要服务端再发一条来撤销它。
 */
public final class DroneDetectWarning {

    /** 横幅显示多久，毫秒。够看清头像和文字，又不至于挡着视线太久。 */
    private static final long DURATION_MS = 2600L;
    /** 最后这段里淡出。 */
    private static final long FADE_MS = 500L;

    /** 面板底衬与文字，配色跟箱子标记同一族：暖琥珀。 */
    private static final int PANEL_RGB = 0x101418;
    private static final int PANEL_ALPHA = 0xB3;
    private static final int EDGE_RGB = 0xE2A03C;
    private static final int TEXT_RGB = 0xFAC775;

    /** 署名者 UUID，空串表示当前没有横幅。 */
    private static String pilotId = "";
    /** 横幅到什么时候消失。 */
    private static long expiry;

    private DroneDetectWarning() {
    }

    public static void accept(DroneAlertPayload payload) {
        pilotId = payload.pilotId();
        expiry = System.currentTimeMillis() + DURATION_MS;
    }

    /** 退出世界时清掉，否则重进存档会先闪一条上局的横幅。 */
    public static void clear() {
        pilotId = "";
        expiry = 0L;
    }

    public static void render(GuiGraphics graphics) {
        long now = System.currentTimeMillis();
        if (pilotId.isEmpty() || now >= expiry) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null || minecraft.options.hideGui || minecraft.player == null) {
            return;
        }
        // 开着界面时不画：这条横幅属于"看世界"的那一层，压在背包上只会碍事。
        if (minecraft.screen != null) {
            return;
        }

        double fade = Math.min(1.0D, (expiry - now) / (double) FADE_MS);
        int panelAlpha = (int) Math.round(PANEL_ALPHA * fade);
        int edgeAlpha = (int) Math.round(255.0D * fade);
        if (panelAlpha <= 0) {
            return;
        }

        Font font = minecraft.font;
        String text = Component.translatable("hud.watchcraft.detected").getString();
        int headSize = 16;
        int padding = 6;
        int gap = 5;
        int textWidth = font.width(text);
        int panelWidth = padding + headSize + gap + textWidth + padding;
        int panelHeight = headSize + 8;
        int width = graphics.guiWidth();
        int x = (width - panelWidth) / 2;
        int y = 30;

        graphics.fill(x, y, x + panelWidth, y + panelHeight, (panelAlpha << 24) | PANEL_RGB);
        // 上下两条亮边当描边。GuiGraphics 只能填矩形，叠一条是最省事的等效做法。
        graphics.fill(x, y, x + panelWidth, y + 1, (edgeAlpha << 24) | EDGE_RGB);
        graphics.fill(x, y + panelHeight - 1, x + panelWidth, y + panelHeight,
                (edgeAlpha << 24) | EDGE_RGB);

        PlayerFaceRenderer.draw(graphics, skinOf(minecraft), x + padding, y + 4, headSize);
        graphics.drawString(font, text, x + padding + headSize + gap,
                y + (panelHeight - 8) / 2, (edgeAlpha << 24) | TEXT_RGB, false);
    }

    /** {@return 署名者的皮肤；查不到就退回默认皮肤} */
    private static PlayerSkin skinOf(Minecraft minecraft) {
        UUID id;
        try {
            id = UUID.fromString(pilotId);
        } catch (IllegalArgumentException invalid) {
            return DefaultPlayerSkin.get(UUID.randomUUID());
        }
        PlayerInfo info = minecraft.getConnection() == null
                ? null
                : minecraft.getConnection().getPlayerInfo(id);
        return info == null ? DefaultPlayerSkin.get(id) : info.getSkin();
    }
}
