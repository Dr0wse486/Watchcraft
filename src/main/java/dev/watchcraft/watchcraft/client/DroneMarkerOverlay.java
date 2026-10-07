package dev.watchcraft.watchcraft.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import dev.watchcraft.watchcraft.config.WatchcraftConfig;
import dev.watchcraft.watchcraft.entity.ReconDroneEntity;
import dev.watchcraft.watchcraft.network.DroneScanPayload;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.PlayerFaceRenderer;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.resources.PlayerSkin;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
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

    /** 预警边框：怪物用琥珀橙，玩家用红。分级而不是逐种配色，只回答"来的东西会不会说话"。 */
    private static final int ALERT_RGB_MONSTER = 0xE2A03C;
    private static final int ALERT_RGB_PLAYER = 0xE24B4A;

    /** 驾驶者本体标记。中性白，读作"这是我"，和箱子（琥珀）、威胁（红/橙）都不撞。 */
    private static final int PILOT_EDGE = 0xFFE8F2FA;
    private static final int PILOT_HALO = 0x99101418;
    private static final int PILOT_TEXT = 0xFFE8F2FA;

    /** 被探测到的玩家标记：同一个中性白，但形状是旋转的方框，读作"这是目标"。 */
    private static final int TARGET_EDGE = 0xFFE8F2FA;
    private static final int TARGET_HALO = 0x99101418;
    /** 方框的半径（像素）与转一圈的周期（毫秒）。 */
    private static final int TARGET_RADIUS = 7;
    private static final long TARGET_SPIN_MS = 2600L;

    // ------------------------------------------------------------------ 配置镜像

    private static boolean showDistance = true;
    private static boolean edgeIndicator = true;
    private static int maxLabels = 16;
    private static boolean pilotMarker = true;

    private static boolean alertShowBorder = true;
    private static boolean alertShowDirection = true;
    private static int alertPulseTicks = 20;
    private static double alertMaxAlpha = 0.55D;

    // ------------------------------------------------------------------ 状态

    /** 最近一次收到的快照。服务端只在内容变化时才发，所以这里可以一直留着。 */
    private static List<BlockPos> chests = List.of();
    private static int threatPercent;
    private static BlockPos threatPos;
    /** 最近的威胁是不是玩家。true 画红，false（怪物）画橙。 */
    private static boolean threatIsPlayer;
    /** 本轮被无人机标记到的玩家实体 id。位置由客户端每帧自己投影，所以这里只存 id。 */
    private static List<Integer> targets = List.of();

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
        pilotMarker = WatchcraftConfig.CLIENT.markerPilotMarker.get();

        alertShowBorder = WatchcraftConfig.CLIENT.alertShowBorder.get();
        alertShowDirection = WatchcraftConfig.CLIENT.alertShowDirection.get();
        alertPulseTicks = WatchcraftConfig.CLIENT.alertPulseTicks.get();
        alertMaxAlpha = WatchcraftConfig.CLIENT.alertMaxAlpha.get();
    }

    // ------------------------------------------------------------------ 数据入口

    public static void accept(DroneScanPayload payload) {
        chests = payload.chests();
        threatPercent = payload.threatPercent();
        threatPos = payload.hasThreat() ? payload.threat() : null;
        threatIsPlayer = payload.threatIsPlayer();
        targets = payload.players();
    }

    /** 链路断开、无人机消失时调用，把屏幕清干净。 */
    public static void clear() {
        chests = List.of();
        threatPercent = DroneScanPayload.NO_THREAT;
        threatPos = null;
        threatIsPlayer = false;
        targets = List.of();
    }

    /** 由 {@link ClientEvents#onComputeFov} 在主视角那一次调用。 */
    public static void setFov(double degrees) {
        fovDegrees = degrees;
    }

    // ------------------------------------------------------------------ 渲染

    public static void render(GuiGraphics graphics) {
        // 操控中要额外画本体标记，所以"什么都没收到"不再是提前退出的充分条件。
        boolean pilot = DroneController.isLinked();
        if (chests.isEmpty() && targets.isEmpty()
                && threatPercent <= DroneScanPayload.NO_THREAT
                && !(pilot && pilotMarker)) {
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
        // 目标标记和箱子标记同一档：无人机在外面看到的，放飞者在哪儿都该看到。
        drawTargets(graphics, minecraft, camera, width, height);
        if (pilot && pilotMarker) {
            // 本体标记反过来 —— 只有连线时才需要，没连线时相机本来就在本体身上。
            drawPilotMarker(graphics, minecraft, camera, width, height);
        }
        drawStatus(graphics, minecraft.font, height);
    }

    /**
     * 左下角的一行状态：箱子、目标与警戒的数量。
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
        List<String> parts = new ArrayList<>(3);
        if (!chests.isEmpty()) {
            parts.add(Component.translatable("hud.watchcraft.chests").getString() + " ×" + chests.size());
        }
        if (!targets.isEmpty()) {
            parts.add(Component.translatable("hud.watchcraft.targets").getString() + " ×" + targets.size());
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
                    drawEdgeIndicator(graphics, width, height, -sx, -sy, MARKER_EDGE, MARKER_FILL);
                    drawn++;
                }
                continue;
            }
            double px = width * 0.5D - sx * focal / depth;
            double py = height * 0.5D - sy * focal / depth;

            if (px < -24.0D || py < -24.0D || px > width + 24.0D || py > height + 24.0D) {
                if (edgeIndicator && drawn < maxLabels) {
                    drawEdgeIndicator(graphics, width, height, px - width * 0.5D, py - height * 0.5D,
                            MARKER_EDGE, MARKER_FILL);
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
     * 把屏幕外的目标夹到画面边缘，给一个"在那边"的点。
     *
     * <p>{@code (dx, dy)} 是从屏幕中心指向目标的向量，函数把它推到矩形边框上。用矩形边框
     * 而不是圆形，是因为屏幕本身是矩形，圆形的指示器会在四个角附近显得离边缘很远。
     *
     * <p>配色由调用方给：箱子是琥珀，本体是白。三种标记共用同一套几何，换的只是两个色值。
     */
    private static void drawEdgeIndicator(GuiGraphics graphics, int width, int height,
                                          double dx, double dy, int edge, int fill) {
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
        graphics.fill(x - 3, y - 3, x + 3, y + 3, edge);
        graphics.fill(x - 2, y - 2, x + 2, y + 2, fill);
    }

    // ------------------------------------------------------------------ 被探测到的玩家

    /**
     * 给无人机这一轮标记到的每个玩家画一个旋转的白色镂空方框。
     *
     * <p>这是无人机在外面看到的<b>人</b>：被标记的人可能在自己身体几百格之外，光靠原版的发光
     * 描边只能看出"那儿有个东西亮着"，看不出那是个人、也看不出它正被盯着。方框转起来就是为了
     * 这个 —— 静止的方框和箱子标记撞车，转的方框读作"这是目标"。
     *
     * <p>和箱子标记同一档：<b>连线与否都画</b>。无人机在外面飞、玩家在别处做自己的事，正是
     * 这套侦察回传存在的理由，没道理只有坐进驾驶舱才看得到。
     *
     * <p>位置是<b>纯客户端</b>算的：服务端只发实体 id，投影用的是相机自己的基向量，
     * 和本体标记、箱子标记同一套。所以标记贴着人走，不会跟着 200 毫秒一包的节奏跳。
     */
    private static void drawTargets(GuiGraphics graphics, Minecraft minecraft, Camera camera,
                                    int width, int height) {
        if (targets.isEmpty()) {
            return;
        }
        double fov = fovDegrees > 1.0D ? fovDegrees : minecraft.options.fov().get();
        double focal = (height * 0.5D) / Math.tan(Math.toRadians(fov) * 0.5D);

        Vec3 eye = camera.getPosition();
        Vector3f forward = camera.getLookVector();
        Vector3f up = camera.getUpVector();
        Vector3f left = camera.getLeftVector();

        // 用挂钟而不是刻：转一圈两秒半，按刻算一帧一帧都是整数度，转起来是跳的。
        double spin = (System.currentTimeMillis() % TARGET_SPIN_MS)
                / (double) TARGET_SPIN_MS * 360.0D;

        for (int id : targets) {
            Entity entity = minecraft.level.getEntity(id);
            if (!(entity instanceof LivingEntity target) || target.isRemoved()) {
                continue;
            }
            Vec3 centre = target.getBoundingBox().getCenter();
            double rx = centre.x - eye.x;
            double ry = centre.y - eye.y;
            double rz = centre.z - eye.z;

            double depth = rx * forward.x() + ry * forward.y() + rz * forward.z();
            double sx = rx * left.x() + ry * left.y() + rz * left.z();
            double sy = rx * up.x() + ry * up.y() + rz * up.z();

            if (depth <= 0.15D) {
                // 在相机背后。方向和箱子那边一样取 (-sx, -sy)，忽略深度的符号。
                if (edgeIndicator) {
                    drawEdgeIndicator(graphics, width, height, -sx, -sy, TARGET_EDGE, TARGET_EDGE);
                }
                continue;
            }
            double px = width * 0.5D - sx * focal / depth;
            double py = height * 0.5D - sy * focal / depth;
            if (px < -24.0D || py < -24.0D || px > width + 24.0D || py > height + 24.0D) {
                if (edgeIndicator) {
                    drawEdgeIndicator(graphics, width, height,
                            px - width * 0.5D, py - height * 0.5D, TARGET_EDGE, TARGET_EDGE);
                }
                continue;
            }
            drawTargetSquare(graphics, (int) Math.round(px), (int) Math.round(py), spin);
        }
    }

    /**
     * 一个旋转的镂空方框。
     *
     * <p>旋转靠 {@code GuiGraphics#pose()}：它填的每个矩形都走 {@code pose.last().pose()}，
     * 所以把平移和绕 Z 的旋转压进去，四条边就跟着转 —— 只用 {@code fill} 的话只能画出正着的
     * 方框，没有别的办法。
     *
     * <p>先画一个粗一圈的深色方框当描边，再压上白色的那一圈。白线压在雪地、水面或天空上会
     * 糊掉，{@code GuiGraphics} 又没有描边这个概念，叠两层是最省事的等效做法。
     */
    private static void drawTargetSquare(GuiGraphics graphics, int x, int y, double angle) {
        PoseStack pose = graphics.pose();
        pose.pushPose();
        pose.translate(x, y, 0.0F);
        pose.mulPose(Axis.ZP.rotationDegrees((float) angle));
        square(graphics, TARGET_RADIUS + 1, 2, TARGET_HALO);
        square(graphics, TARGET_RADIUS, 1, TARGET_EDGE);
        pose.popPose();
    }

    /**
     * 一个正的空心框，画在以原点为中心的位置上。
     *
     * <p>{@code half} 是外沿到中心的距离，{@code thickness} 是边的粗细。上下两条横边各画满，
     * 左右两条竖边在横边之间收住 —— 不收的话四个角会叠出更亮的一块。
     */
    private static void square(GuiGraphics graphics, int half, int thickness, int colour) {
        int lo = -half;
        int hi = half + 1;
        graphics.fill(lo, lo, hi, lo + thickness, colour);
        graphics.fill(lo, hi - thickness, hi, hi, colour);
        graphics.fill(lo, lo + thickness, lo + thickness, hi - thickness, colour);
        graphics.fill(hi - thickness, lo + thickness, hi, hi - thickness, colour);
    }

    // ------------------------------------------------------------------ 驾驶者本体标记

    /**
     * 操控中，在自己本体所在的位置画一个标记。
     *
     * <p>镜头在无人机上，身体可能留在几百格之外 —— 玩家要"回去"的时候得先知道家在哪。
     * 这个位置是<b>纯客户端</b>的：本体就是 {@code minecraft.player}，不需要服务端发任何东西。
     * 也因此它只在连线时出现，没连线时相机本来就在本体身上，画了等于在准星底下贴一张纸。
     *
     * <p>样式是<b>玩家头像</b>：一个深色底衬 + 一圈细白描边 + 自己的皮肤 + 一个"本体"标签。
     * 一开始画的是空心方框加中心一点，但那个形状和箱子标记的方框撞车 —— 两个都是方块，
     * 在远处一眼分不出"那儿有个箱子"和"那是我"。头像没有这个问题：它只能是人。
     *
     * <p>尺寸固定，不随距离缩。它是指示物不是世界里的物件，缩到看不见就等于没有。
     *
     * <p>⚠️ <b>画在"这架无人机的驾驶员"身上，不是画在 {@code minecraft.player} 身上。</b>
     * 正常情况两者是同一个实体，看不出区别；但像假人（fake player）那类会把客户端玩家整个
     * 换掉的模组，{@code minecraft.player} 会变成假人，而链路其实还挂在原玩家身上 ——
     * 标记就跑到假人头上去了，玩家反而找不到自己的身体。驾驶员的实体 id 是同步数据
     * （{@link ReconDroneEntity#getPilotId()}），客户端查得到，所以直接用它。
     */
    private static void drawPilotMarker(GuiGraphics graphics, Minecraft minecraft, Camera camera,
                                        int width, int height) {
        Entity body = pilotBody(minecraft);
        if (body == null) {
            return;
        }
        Vec3 target = body.getEyePosition();
        double fov = fovDegrees > 1.0D ? fovDegrees : minecraft.options.fov().get();
        double focal = (height * 0.5D) / Math.tan(Math.toRadians(fov) * 0.5D);

        Vec3 eye = camera.getPosition();
        Vector3f forward = camera.getLookVector();
        Vector3f up = camera.getUpVector();
        Vector3f left = camera.getLeftVector();

        double rx = target.x - eye.x;
        double ry = target.y - eye.y;
        double rz = target.z - eye.z;

        double depth = rx * forward.x() + ry * forward.y() + rz * forward.z();
        double sx = rx * left.x() + ry * left.y() + rz * left.z();
        double sy = rx * up.x() + ry * up.y() + rz * up.z();

        if (depth <= 0.15D) {
            // 在相机背后。方向和箱子那边一样取 (-sx, -sy)，忽略深度的符号。
            if (edgeIndicator) {
                drawEdgeIndicator(graphics, width, height, -sx, -sy, PILOT_EDGE, PILOT_EDGE);
            }
            return;
        }
        double px = width * 0.5D - sx * focal / depth;
        double py = height * 0.5D - sy * focal / depth;
        if (px < -24.0D || py < -24.0D || px > width + 24.0D || py > height + 24.0D) {
            if (edgeIndicator) {
                drawEdgeIndicator(graphics, width, height, px - width * 0.5D, py - height * 0.5D,
                        PILOT_EDGE, PILOT_EDGE);
            }
            return;
        }

        int x = (int) Math.round(px);
        int y = (int) Math.round(py);
        // 16 是原版头像渲染最干净的一档（8×8 的贴图区正好整数放大两倍），和探测警告横幅同款。
        int size = 16;
        // 不能叫 left —— 上面已经有一个 left 是相机的左向量。
        int headLeft = x - size / 2;
        int headTop = y - size / 2;

        // 深色底衬。本体常常在雪地、水面或天空前面，没有它头像的浅色部分会糊进背景里。
        graphics.fill(headLeft - 2, headTop - 2, headLeft + size + 2, headTop + size + 2, PILOT_HALO);
        // 一圈细白描边，把头像和它背后的任何东西切开。
        graphics.fill(headLeft - 1, headTop - 1, headLeft + size + 1, headTop, PILOT_EDGE);
        graphics.fill(headLeft - 1, headTop + size, headLeft + size + 1, headTop + size + 1, PILOT_EDGE);
        graphics.fill(headLeft - 1, headTop - 1, headLeft, headTop + size + 1, PILOT_EDGE);
        graphics.fill(headLeft + size, headTop - 1, headLeft + size + 1, headTop + size + 1, PILOT_EDGE);

        PlayerFaceRenderer.draw(graphics, skinOf(body, minecraft), headLeft, headTop, size);

        String text = Component.translatable("hud.watchcraft.pilot").getString();
        int textWidth = minecraft.font.width(text);
        int textX = x - textWidth / 2;
        int textY = headTop + size + 4;
        graphics.fill(textX - 2, textY - 1, textX + textWidth + 2, textY + 9, PILOT_HALO);
        graphics.drawString(minecraft.font, text, textX, textY, PILOT_TEXT, false);
    }

    /**
     * {@return 该被标上"本体"的那具身体}
     *
     * <p>先问无人机"谁在驾驶你"（同步数据里的实体 id），查不到再退回 {@code minecraft.player}。
     * 见 {@link #drawPilotMarker} 的注释。
     */
    @Nullable
    private static Entity pilotBody(Minecraft minecraft) {
        Entity linked = minecraft.level.getEntity(DroneController.getLinkedId());
        if (linked instanceof ReconDroneEntity drone) {
            int pilotId = drone.getPilotId();
            if (pilotId >= 0) {
                Entity pilot = minecraft.level.getEntity(pilotId);
                if (pilot != null) {
                    return pilot;
                }
            }
        }
        return minecraft.player;
    }

    /** {@return 这具身体该用的头像皮肤}，非玩家就退回本机玩家的。 */
    private static PlayerSkin skinOf(Entity body, Minecraft minecraft) {
        if (body instanceof AbstractClientPlayer player) {
            return player.getSkin();
        }
        return minecraft.player.getSkin();
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
        int colour = threatIsPlayer ? ALERT_RGB_PLAYER : ALERT_RGB_MONSTER;
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
                graphics.fill(i, i, width - i, i + 1, (aTop << 24) | colour);
            }
            if (aBottom > 0) {
                graphics.fill(i, height - i - 1, width - i, height - i, (aBottom << 24) | colour);
            }
            if (aLeft > 0) {
                graphics.fill(i, i, i + 1, height - i, (aLeft << 24) | colour);
            }
            if (aRight > 0) {
                graphics.fill(width - i - 1, i, width - i, height - i, (aRight << 24) | colour);
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
