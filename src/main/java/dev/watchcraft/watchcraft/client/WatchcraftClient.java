package dev.watchcraft.watchcraft.client;

import dev.watchcraft.watchcraft.Watchcraft;
import dev.watchcraft.watchcraft.network.DroneAlertPayload;
import dev.watchcraft.watchcraft.network.DroneLinkPayload;
import dev.watchcraft.watchcraft.network.DroneScanPayload;
import dev.watchcraft.watchcraft.registry.ModEntities;
import dev.watchcraft.watchcraft.registry.ModMenus;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.event.config.ModConfigEvent;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.client.event.RegisterClientReloadListenersEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.event.RegisterMenuScreensEvent;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

@Mod(value = Watchcraft.MOD_ID, dist = Dist.CLIENT)
public final class WatchcraftClient {

    public WatchcraftClient(IEventBus modBus, ModContainer container) {
        modBus.addListener(WatchcraftClient::registerRenderers);
        modBus.addListener(WatchcraftClient::registerReloadListeners);
        modBus.addListener(WatchcraftClient::registerKeys);
        modBus.addListener(WatchcraftClient::registerScreens);
        modBus.addListener(WatchcraftClient::registerPayloads);
        modBus.addListener(WatchcraftClient::onConfigLoading);
        modBus.addListener(WatchcraftClient::onConfigReloading);

        // 同一个界面既从暂停菜单进（见 ClientEvents#onScreenInit），也从模组列表的"配置"按钮进。
        // 后者是 NeoForge 白送的：注册了这个扩展点，模组列表就会自己长出那个按钮。
        container.registerExtensionPoint(IConfigScreenFactory.class,
                (IConfigScreenFactory) (modContainer, parent) -> new WatchcraftConfigScreen(parent));
    }

    private static void onConfigLoading(ModConfigEvent.Loading event) {
        applyConfig(event);
    }

    private static void onConfigReloading(ModConfigEvent.Reloading event) {
        applyConfig(event);
    }

    /**
     * 客户端配置载入/热重载之后，把手感与画面参数写回各自的静态镜像。
     *
     * <p>放在这里而不是主类里，是因为 {@link DroneController}、{@link DroneSignal} 与
     * {@link DroneHud} 都是纯客户端类，双端共用的 {@code Watchcraft} 引用它们会在专用服务器上炸掉。
     *
     * <p>只认 CLIENT 那一份：COMMON 由 {@code Watchcraft} 处理。原因见那边的注释 ——
     * {@code ConfigTracker} 派发事件的顺序不保证 COMMON 先到，而在这里读未载入的 COMMON
     * 配置会抛异常并把模组加载整个带崩。
     */
    private static void applyConfig(ModConfigEvent event) {
        ModConfig config = event.getConfig();
        if (!Watchcraft.MOD_ID.equals(config.getModId()) || config.getType() != ModConfig.Type.CLIENT) {
            return;
        }
        applyClientConfig();
    }

    /**
     * 把 CLIENT 那份配置整体刷进各个静态镜像。
     *
     * <p>公开是给配置界面用的：{@code ConfigValue#set} 不派发 {@code ModConfigEvent}，所以界面
     * 自己改完值得主动调一次，否则拖完滑条要等下次重启才看得到效果。重复调用无害。
     */
    public static void applyClientConfig() {
        DroneController.applyConfig();
        DroneSignal.applyConfig();
        DroneLcd.applyConfig();
        DroneShake.applyConfig();
        DroneHud.applyConfig();
        DroneMarkerOverlay.applyConfig();
    }

    private static void registerRenderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerEntityRenderer(ModEntities.RECON_DRONE.get(), DroneRenderer::new);
    }

    /**
     * The drone has no layer definitions any more - its geometry comes straight from the OBJ files
     * and never passes through the vanilla model pipeline.
     *
     * <p>What it does need is a reload listener, because {@link DroneMeshes} caches the baked
     * meshes for the lifetime of the process. NeoForge already drops {@code ObjLoader}'s own cache
     * on a reload, but not ours, so without this a resource pack that replaces {@code drone.obj}
     * would keep showing the old airframe until the game was restarted.
     */
    private static void registerReloadListeners(RegisterClientReloadListenersEvent event) {
        event.registerReloadListener(DroneMeshes.INSTANCE);
    }

    private static void registerKeys(RegisterKeyMappingsEvent event) {
        event.register(KeyMappings.LINK);
        event.register(KeyMappings.THROW);
        event.register(KeyMappings.RECALL);
        event.register(KeyMappings.ROLL);
        event.register(KeyMappings.DETONATE);
    }

    private static void registerScreens(RegisterMenuScreensEvent event) {
        event.register(ModMenus.MODULE_WORKBENCH.get(), ModuleWorkbenchScreen::new);
    }

    private static void registerPayloads(RegisterPayloadHandlersEvent event) {
        event.registrar("1")
                .playToClient(
                        DroneLinkPayload.TYPE,
                        DroneLinkPayload.STREAM_CODEC,
                        ClientPayloadHandler::handleLink)
                .playToClient(
                        DroneScanPayload.TYPE,
                        DroneScanPayload.STREAM_CODEC,
                        ClientPayloadHandler::handleScan)
                .playToClient(
                        DroneAlertPayload.TYPE,
                        DroneAlertPayload.STREAM_CODEC,
                        ClientPayloadHandler::handleAlert);
    }

    public static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(Watchcraft.MOD_ID, path);
    }
}
