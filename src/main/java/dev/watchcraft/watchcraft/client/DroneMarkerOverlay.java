package dev.watchcraft.watchcraft.client;

import dev.watchcraft.watchcraft.config.WatchcraftConfig;
import dev.watchcraft.watchcraft.entity.ReconDroneEntity;
import dev.watchcraft.watchcraft.network.DroneScanPayload;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;

/**
 * 无人机侦察结果在<b>玩家自己</b>屏幕上的呈现：箱子标记与敌对预警。
 *
 * <p>和 {@link DroneHud} 是两回事，尽管它们画在同一层。那座面罩是"我正坐在无人机里"的仪表，
 * 只在链路建立时出现；这个覆盖层是"我的无人机在外面飞"的回执，<b>未连线时才是主要用途</b>。
 * 两者的生命周期因此完全相反：面罩跟着链路走，这里跟着 {@link DroneScanPayload} 走。
 *
 * <h2>投影为什么是手算的</h2>
 *
 * <p>标记要贴在世界的某个坐标上，所以需要把世界坐标投到屏幕。{@code RenderGuiEvent.Post}
 * 这一层拿不到投影矩阵（那属于关卡渲染，早就出栈了），而 {@code GuiGraphics} 只提供二维
 * 填充。于是这里用相机自己的基向量做一次手算透视：把目标点相对相机的位置拆到
 * 前 / 左 / 上三个轴上，再按视场角做除法。
 *
 * <p>视场角取自 {@link ClientEvents#onComputeFov}，并且<b>只认主视角那一次</b>
 * （{@code usedConfiguredFov()}）。这一点必须计较：{@code ComputeFov} 每帧会因为手部渲染、
 * 传送动画等原因被调用多次，取值不同的几档。随手抓最后一次会让标记在画面里跳。
 *
 * <p>注意这里不需要做深度遮挡判断 —— 服务端发过来的箱子已经过了视线检测，看不见的根本
 * 不在列表里。这也是"服务端筛、客户端画"这个分工带来的好处之一。
 */
public final class DroneMarkerOverlay {

    // ------------------------------------------------------------------ 配色

    /** 箱子图标填充。暖琥珀，和面罩的冷青拉开距离，一眼能分出"这是外来的提示"。 */
    private static final int MARKER_FILL = 0xE0BA7517;
    private static final int MARKER_EDGE = 0xFFFAC775;
    private static final int MARKER_TEXT = 0xFFFAC775;
    /** 文字底衬，避免贴在雪地或天空上读不出来。 */
    private static final int MARKER_HALO = 0x99101418;

    /** 预警红。和原版低血红屏同色系，但只有边框、且会脉动，见 {@link #drawAlert}。 */
    private static final int ALERT_RGB = 0xE24B4A;

    // ------------------------------------------------------------------ 配置镜像

    private static boolean showDistance = true;
    private static boolean edgeIndicator = true;
    private static int maxLabels = 16;

    private static boolean alertShowBorder = true;
    private static boolean alertShowDirection = true;
    private static int alertPulseTicks = 20;
    private static double alertMaxAlpha = 0.55D;

    // ------------------------------------------------------------------ 状态

    /** 最近一次收到的快照。服务端只在内容变化时才发，所以这里可以一直留着。 */
    private static List<BlockPos> chests = List.of();
    private static int threatPercent;
    private static BlockPos threatPos;

    /** 主视角的垂直视场角（度）。0 表示还没收到，退回读选项里的值。 */
    private static double fovDegrees;

    /** 边缘指示器用的边序号。 */
    private static final int EDGE_TOP = 0;
    private static final int EDGE_BOTTOM = 1;
    private static final int EDGE_LEFT = 2;
    private static final int EDGE_RIGHT = 3;

    private DroneMarkerOverlay() {
    }

    /**
     * 把 {@code marker} 与 {@code alert} 两段客户端配置写回静态镜像。
     *
     * <p>由 {@code ModConfigEvent} 与配置界面各调一次，理由同 {@link DroneHud#applyConfig()}：
     * {@code ConfigValue#set} 不派发事件，界面改完必须自己刷。
     */
    public static void applyConfig() {
        showDistance = WatchcraftConfig.CLIENT.markerShowDistance.get();
        edgeIndicator = WatchcraftConfig.CLIENT.markerEdgeIndicator.get();
        maxLabels = WatchcraftConfig.CLIENT.markerMaxLabels.get();

        alertShowBorder = WatchcraftConfig.CLIENT.alertShowBorder.get();
        alertShowDirection = WatchcraftConfig.CLIENT.alertShowDirection.get();
        alertPulseTicks = WatchcraftConfig.CLIENT.alertPulseTicks.get();
        alertMaxAlpha = WatchcraftConfig.CLIENT.alertMaxAlpha.get();
    }

    // ------------------------------------------------------------------ 数据入口

    public static void accept(DroneScanPayload payload) {
        chests = payload.chests();
        threatPercent = payload.threatPercent();
        threatPos = payload.hasThreat() ? payload.threatPos() : null;
    }

    /** 链路断开、无人机消失时调用，把屏幕清干净。 */
    public static void clear() {
        chests = List.of();
        threatPercent = DroneScanPayload.NO_THREAT;
        threatPos = null;
    }

    /** 由 {@link ClientEvents#onComputeFov} 在主视角那一次调用。 */
    public static void setFov(double degrees) {
        fovDegrees = degrees;
    }

    // ------------------------------------------------------------------ 渲染

    public static void render(GuiGraphics graphics) {
        if (chests.isEmpty() && threatPercent <= DroneScanPayload.NO_THREAT) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null || minecraft.options.hideGui || minecraft.player == null) {
            return;
        }
        // 打开背包或任何界面时不画。这些标记属于"看世界"的那一层，压在容器界面上只会碍事。
        if (minecraft.screen != null) {
            return;
        }
        // 引爆后的满屏雪花会把一切都盖住，标记画在上面只会显得脏。那段窗口里让位。
        if (DroneController.isLinked() && isStaticScreenUp(minecraft)) {
            return;
        }
        Camera camera = minecraft.gameRenderer.getMainCamera();
        if (!camera.isInitialized()) {
            return;
        }

        int width = graphics.guiWidth();
        int height = graphics.guiHeight();

        if (threatPercent > DroneScanPayload.NO_THREAT) {
            drawAlert(graphics, camera, minecraft.player.position(), width, height);
        }
        drawChests(graphics, minecraft, camera, width, height);
        drawStatus(graphics, minecraft.font, height);
    }

    /**
     * 左下角的一行状态：箱子数量与警戒。
     *
     * <p>标记本身已经说明了位置，但"有几个"这个总量是看不出来的 —— 尤其是箱子在画面外、
     * 只剩边缘方向点的时候。这一行就是那份总量。
     *
     * <p>只在<b>未连线</b>时画。连线时座舱有自己的仪表盘与底部按键条，位置正好撞上，
     * 而且那时候玩家看得见无人机自己的视野，不需要这层转述。
     */
    private static void drawStatus(GuiGraphics graphics, Font font, int height) {
        if (DroneController.isLinked()) {
            return;
        }
        List<String> parts = new ArrayList<>(2);
        if (!chests.isEmpty()) {
            parts.add(Component.translatable("hud.watchcraft.chests").getString() + " ×" + chests.size());
        }
        if (threatPercent > DroneScanPayload.NO_THREAT) {
            parts.add(Component.translatable("hud.watchcraft.alert").getString());
        }
        if (parts.isEmpty()) {
            return;
        }

        String text = String.join("   ", parts);
        int textWidth = font.width(text);
        int x = 12;
        int y = height - 26;
        graphics.fill(x - 4, y - 4, x + textWidth + 6, y + 11, MARKER_HALO);
        graphics.drawString(font, text, x, y, MARKER_TEXT, false);
    }

    private static boolean isStaticScreenUp(Minecraft minecraft) {
        Entity entity = minecraft.level.getEntity(DroneController.getLinkedId());
        return entity instanceof ReconDroneEntity drone && drone.getStaticTicks() > 0;
    }

    // ------------------------------------------------------------------ 箱子标记

    private static void drawChests(GuiGraphics graphics, Minecraft minecraft, Camera camera,
                                   int width, int height) {
        if (chests.isEmpty()) {
            return;
        }
        Font font = minecraft.font;
        double fov = fovDegrees > 1.0D ? fovDegrees : minecraft.options.fov().get();
        double focal = (height * 0.5D) / Math.tan(Math.toRadians(fov) * 0.5D);

        Vec3 eye = camera.getPosition();
        Vector3f forward = camera.getLookVector();
        Vector3f up = camera.getUpVector();
        Vector3f left = camera.getLeftVector();

        int drawn = 0;
        for (BlockPos pos : chests) {
            if (drawn >= maxLabels) {
                break;
            }
            // 瞄箱子的几何中心；标签再往上抬，压住箱子顶盖而不是盖住它。
            double rx = pos.getX() + 0.5D - eye.x;
            double ry = pos.getY() + 0.5D - eye.y;
            double rz = pos.getZ() + 0.5D - eye.z;

            double depth = rx * forward.x() + ry * forward.y() + rz * forward.z();
            double sx = rx * left.x() + ry * left.y() + rz * left.z();
            double sy = rx * up.x() + ry * up.y() + rz * up.z();

            if (depth <= 0.15D) {
                // 在相机背后。投影会翻转，所以只给一个边缘指示，不画标签。
                // 方向仍然取 (-sx, -sy)：那正是正前方时投影的方向，忽略深度的符号即可。
                if (edgeIndicator && drawn < maxLabels) {
                    drawEdgeIndicator(graphics, width, height, -sx, -sy);
                    drawn++;
                }
                continue;
            }
            double px = width * 0.5D - sx * focal / depth;
            double py = height * 0.5D - sy * focal / depth;

            if (px < -24.0D || py < -24.0D || px > width + 24.0D || py > height + 24.0D) {
                if (edgeIndicator && drawn < maxLabels) {
                    drawEdgeIndicator(graphics, width, height, px - width * 0.5D, py - height * 0.5D);
                    drawn++;
                }
                continue;
            }
            drawChestLabel(graphics, font, (int) Math.round(px), (int) Math.round(py),
                    Math.sqrt(rx * rx + ry * ry + rz * rz));
            drawn++;
        }
    }

    /**
     * 一个箱子标记：琥珀方块 + 距离数字。
     *
     * <p>方块先铺一层深色底再压一层亮色，做出 1 像素描边 —— {@code GuiGraphics} 只能填矩形，
     * 没有描边这个概念，叠两层是最省事的等效做法，面罩里的准星也是这么干的。
     */
    private static void drawChestLabel(GuiGraphics graphics, Font font, int x, int y, double distance) {
        graphics.fill(x - 6, y - 6, x + 6, y + 6, MARKER_HALO);
        graphics.fill(x - 5, y - 5, x + 5, y + 5, MARKER_EDGE);
        graphics.fill(x - 4, y - 4, x + 4, y + 4, MARKER_FILL);

        if (!showDistance) {
            return;
        }
        String text = Math.round(distance) + "m";
        int textWidth = font.width(text);
        int textX = x - textWidth / 2;
        int textY = y + 8;
        graphics.fill(textX - 2, textY - 1, textX + textWidth + 2, textY + 9, MARKER_HALO);
        graphics.drawString(font, text, textX, textY, MARKER_TEXT, false);
    }

    /**
     * 把屏幕外的箱子夹到画面边缘，给一个"在那边"的点。
     *
     * <p>{@code (dx, dy)} 是从屏幕中心指向目标的向量，函数把它推到矩形边框上。用矩形边框
     * 而不是圆形，是因为屏幕本身是矩形，圆形的指示器会在四个角附近显得离边缘很远。
     */
    private static void drawEdgeIndicator(GuiGraphics graphics, int width, int height,
                                          double dx, double dy) {
        double cx = width * 0.5D;
        double cy = height * 0.5D;
        double length = Math.hypot(dx, dy);
        if (length < 1.0E-4D) {
            return;
        }
        double ux = dx / length;
        double uy = dy / length;

        double margin = 16.0D;
        double halfWidth = Math.max(1.0D, cx - margin);
        double halfHeight = Math.max(1.0D, cy - margin);
        double tx = Math.abs(ux) < 1.0E-4D ? Double.MAX_VALUE : halfWidth / Math.abs(ux);
        double ty = Math.abs(uy) < 1.0E-4D ? Double.MAX_VALUE : halfHeight / Math.abs(uy);
        double t = Math.min(tx, ty);

        int x = (int) Math.round(cx + ux * t);
        int y = (int) Math.round(cy + uy * t);
        graphics.fill(x - 4, y - 4, x + 4, y + 4, MARKER_HALO);
        graphics.fill(x - 3, y - 3, x + 3, y + 3, MARKER_EDGE);
        graphics.fill(x - 2, y - 2, x + 2, y + 2, MARKER_FILL);
    }

    // ------------------------------------------------------------------ 预警边框

    /**
     * 敌对生物靠近时的红色边框。
     *
     * <p>三层决定：<b>只有边框</b>、<b>会脉动</b>、<b>朝威胁那一侧更亮</b>。
     *
     * <p>第一层是为了和原版低血红屏分开。原版 {@code Gui#renderVignette} 是一圈静态渐晕、
     * 整屏均匀压暗；如果这里也画满屏，两者同时出现时玩家分不清"我快死了"和"有怪靠近"。
     * 所以这里只碰边缘，画面中心一动不动。
     *
     * <p>第二层是脉动。静态的红边读起来像"受伤了"，有节奏的才读得像"警报"。
     *
     * <p>第三层是方向。单纯"有危险"的信息量很低 —— 玩家下一步的动作取决于怪在哪边。
     * 方向由 {@link #threatEdge} 从相机基向量算出来，那一侧的边框乘一个系数。
     *
     * <p>渐晕用嵌套的 1 像素环叠出来，和 {@link DroneHud#drawVignette} 同一个手法：
     * {@code GuiGraphics} 没有渐变，只能靠一层层递减的实心矩形骗过眼睛。
     */
    private static void drawAlert(GuiGraphics graphics, Camera camera, Vec3 origin,
                                  int width, int height) {
        if (!alertShowBorder) {
            return;
        }
        double intensity = threatPercent / 100.0D;

        double period = Math.max(0.05D, alertPulseTicks / 20.0D);
        double phase = System.currentTimeMillis() / 1000.0D / period * Math.PI * 2.0D;
        double pulse = 0.62D + 0.38D * Math.sin(phase);

        double alpha = intensity * alertMaxAlpha * pulse;

        double top = 1.0D;
        double bottom = 1.0D;
        double left = 1.0D;
        double right = 1.0D;
        if (alertShowDirection && threatPos != null) {
            switch (threatEdge(camera, origin, threatPos)) {
                case EDGE_TOP -> top = 1.8D;
                case EDGE_BOTTOM -> bottom = 1.8D;
                case EDGE_LEFT -> left = 1.8D;
                default -> right = 1.8D;
            }
        }

        int rings = Math.min(40, Math.min(width, height) / 4);
        for (int i = 0; i < rings; i++) {
            double falloff = 1.0D - (double) i / rings;
            double base = alpha * falloff * falloff;
            if (base <= 0.004D) {
                continue;
            }
            int aTop = alphaByte(base * top);
            int aBottom = alphaByte(base * bottom);
            int aLeft = alphaByte(base * left);
            int aRight = alphaByte(base * right);

            if (aTop > 0) {
                graphics.fill(i, i, width - i, i + 1, (aTop << 24) | ALERT_RGB);
            }
            if (aBottom > 0) {
                graphics.fill(i, height - i - 1, width - i, height - i, (aBottom << 24) | ALERT_RGB);
            }
            if (aLeft > 0) {
                graphics.fill(i, i, i + 1, height - i, (aLeft << 24) | ALERT_RGB);
            }
            if (aRight > 0) {
                graphics.fill(width - i - 1, i, width - i, height - i, (aRight << 24) | ALERT_RGB);
            }
        }
    }

    private static int alphaByte(double value) {
        int alpha = (int) Math.round(Math.min(1.0D, Math.max(0.0D, value)) * 255.0D);
        return alpha;
    }

    /**
     * {@return 威胁相对镜头落在哪条边}
     *
     * <p>只看水平面：前 / 后 / 左 / 右。竖直方向对"往哪边看"没有帮助 —— 抬头低头是玩家自己
     * 在做的事，怪在头顶和脚下都得先转过头去。
     *
     * <p>方向向量取自<b>玩家</b>的位置，朝向取自<b>相机</b>。两者分开取是有原因的：威胁度
     * 是服务端按"怪离玩家多远"算的，所以那个坐标天然是相对玩家的；而"往哪边转"问的是屏幕，
     * 屏幕永远是相机说了算。未连线时两者重合，连线上无人机时这个组合才给出正确的答案 ——
     * 该往哪边转镜头，才能看到逼近自己身体的那个东西。
     */
    private static int threatEdge(Camera camera, Vec3 origin, BlockPos pos) {
        double rx = pos.getX() + 0.5D - origin.x;
        double rz = pos.getZ() + 0.5D - origin.z;
        Vector3f forward = camera.getLookVector();
        Vector3f left = camera.getLeftVector();

        double front = rx * forward.x() + rz * forward.z();
        double side = rx * left.x() + rz * left.z();

        if (Math.abs(front) >= Math.abs(side)) {
            return front >= 0.0D ? EDGE_TOP : EDGE_BOTTOM;
        }
        return side >= 0.0D ? EDGE_LEFT : EDGE_RIGHT;
    }
}
