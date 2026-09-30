package dev.watchcraft.watchcraft.client;

import dev.watchcraft.watchcraft.Watchcraft;
import dev.watchcraft.watchcraft.network.DroneLinkPayload;
import dev.watchcraft.watchcraft.registry.ModEntities;
import dev.watchcraft.watchcraft.registry.ModMenus;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.client.event.RegisterClientReloadListenersEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.event.RegisterMenuScreensEvent;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

@Mod(value = Watchcraft.MOD_ID, dist = Dist.CLIENT)
public final class WatchcraftClient {

    public WatchcraftClient(IEventBus modBus, ModContainer container) {
        modBus.addListener(WatchcraftClient::registerRenderers);
        modBus.addListener(WatchcraftClient::registerReloadListeners);
        modBus.addListener(WatchcraftClient::registerKeys);
        modBus.addListener(WatchcraftClient::registerScreens);
        modBus.addListener(WatchcraftClient::registerPayloads);
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
    }

    private static void registerScreens(RegisterMenuScreensEvent event) {
        event.register(ModMenus.MODULE_WORKBENCH.get(), ModuleWorkbenchScreen::new);
    }

    private static void registerPayloads(RegisterPayloadHandlersEvent event) {
        event.registrar("1").playToClient(
                DroneLinkPayload.TYPE,
                DroneLinkPayload.STREAM_CODEC,
                ClientPayloadHandler::handleLink);
    }

    public static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(Watchcraft.MOD_ID, path);
    }
}
