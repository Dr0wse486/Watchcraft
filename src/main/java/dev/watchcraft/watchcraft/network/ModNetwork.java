package dev.watchcraft.watchcraft.network;

import dev.watchcraft.watchcraft.entity.ReconDroneEntity;
import dev.watchcraft.watchcraft.registry.ModItems;
import dev.watchcraft.watchcraft.server.DroneSettings;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;
import org.jetbrains.annotations.Nullable;

import java.util.List;

public final class ModNetwork {

    private ModNetwork() {
    }

    /** Server bound payloads are registered on both sides; the client bound one is client only. */
    public static void register(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar("1");
        registrar.playToServer(DroneActionPayload.TYPE, DroneActionPayload.STREAM_CODEC, ModNetwork::handleAction);
        registrar.playToServer(DroneMovePayload.TYPE, DroneMovePayload.STREAM_CODEC, ModNetwork::handleMove);
    }

    public static void sendLinkState(ServerPlayer player, int droneId, boolean linked) {
        PacketDistributor.sendToPlayer(player, new DroneLinkPayload(droneId, linked));
    }

    // ------------------------------------------------------------------ server bound

    /**
     * {@return the drone behind {@code droneId}, but only if this player is the one flying it}
     *
     * <p>Every cockpit message has to pass through this first. The id is chosen by the client, so
     * without the pilot half of the check anyone could drive somebody else's airframe simply by
     * guessing a number.
     *
     * <p>引爆后的雪花屏阶段一并挡住。那时候链路还在（镜头正停在残骸上等着画面烧完），
     * 但机体已经炸了：再让驾驶员用它的触及距离去开门、或者往服务端报位移，都是在操作一具残骸。
     */
    @Nullable
    private static ReconDroneEntity piloted(ServerLevel level, ServerPlayer player, int droneId) {
        return level.getEntity(droneId) instanceof ReconDroneEntity drone
                && drone.isPilotedBy(player)
                && !drone.isDetonated()
                ? drone
                : null;
    }

    private static void handleAction(DroneActionPayload payload, IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer player)) {
            return;
        }
        ServerLevel level = player.serverLevel();

        switch (payload.action()) {
            case DroneActionPayload.ACTION_ENTER -> {
                ReconDroneEntity drone = ReconDroneEntity.findLinkable(level, player, payload.droneId());
                if (drone == null) {
                    player.displayClientMessage(Component.translatable("message.watchcraft.no_drone"), true);
                    return;
                }
                if (drone.isPiloted() && !drone.isPilotedBy(player)) {
                    player.displayClientMessage(Component.translatable("message.watchcraft.busy"), true);
                    return;
                }
                // Drop any other link this player holds first.
                releasePlayerLink(level, player);
                drone.setPilotId(player.getId());
                drone.setDeltaMovement(Vec3.ZERO);
                sendLinkState(player, drone.getId(), true);
            }
            case DroneActionPayload.ACTION_EXIT -> {
                releasePlayerLink(level, player);
                sendLinkState(player, -1, false);
            }
            case DroneActionPayload.ACTION_THROW -> throwOne(level, player);
            case DroneActionPayload.ACTION_RECALL -> recall(level, player);
            case DroneActionPayload.ACTION_INTERACT -> interact(level, player, payload.droneId());
            case DroneActionPayload.ACTION_CHARGE -> charge(level, player, payload.droneId());
            case DroneActionPayload.ACTION_DETONATE -> detonate(level, player, payload.droneId());
            default -> {
            }
        }
    }

    /**
     * Attack run request.
     *
     * <p>Deliberately thin. Everything that decides whether the run may happen - the warhead
     * being fitted, the caller being the pilot, the cooldown - lives in
     * {@link ReconDroneEntity#startCharge}, on the authority side. This only has to stop a request
     * for somebody else's drone from reaching that check.
     */
    private static void charge(ServerLevel level, ServerPlayer player, int droneId) {
        if (level.getEntity(droneId) instanceof ReconDroneEntity drone) {
            drone.startCharge(player);
        }
    }

    /**
     * 手动引爆请求。和冲刺一样薄：权限、模块、是否正在冲刺都在
     * {@link ReconDroneEntity#detonateManually} 里判定。
     */
    private static void detonate(ServerLevel level, ServerPlayer player, int droneId) {
        if (level.getEntity(droneId) instanceof ReconDroneEntity drone) {
            drone.detonateManually(player);
        }
    }

    /**
     * Right click from the cockpit. The reach is cast from the drone rather than from the operator,
     * so the pilot can hold a door open for the drone to fly through. Only door-like blocks react;
     * boats, beds, chests, workbenches and everything else stay out of reach by design.
     */
    private static void interact(ServerLevel level, ServerPlayer player, int droneId) {
        ReconDroneEntity drone = piloted(level, player, droneId);
        if (drone == null) {
            return;
        }
        MinecraftServer server = player.getServer();
        if (server == null || !DroneSettings.get(server).isDoorInteraction()) {
            return;
        }

        Vec3 eye = drone.getEyePosition();
        Vec3 end = eye.add(drone.getViewVector(1.0F).scale(ReconDroneEntity.INTERACT_RANGE));
        BlockHitResult hit = level.clip(new ClipContext(
                eye, end, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, drone));
        if (hit.getType() != HitResult.Type.BLOCK) {
            return;
        }
        BlockState state = level.getBlockState(hit.getBlockPos());
        Block block = state.getBlock();
        if (!(block instanceof DoorBlock
                || block instanceof TrapDoorBlock
                || block instanceof FenceGateBlock)) {
            return;
        }

        // Runs the vanilla open/close logic: state flip, sound and the BLOCK_OPEN game event.
        boolean wasOpen = state.getValue(BlockStateProperties.OPEN);
        state.useWithoutItem(level, player, hit);
        if (level.getBlockState(hit.getBlockPos()).getValue(BlockStateProperties.OPEN) == wasOpen) {
            // Iron doors and anything else that refused the interaction: nothing to echo.
            return;
        }
        echoDoorSound(player, block, !wasOpen);
    }

    /**
     * Re-sends the door sound straight to the pilot.
     *
     * <p>This is not a distance problem, and it is worth spelling out because the obvious reading
     * is wrong. {@code DoorBlock#playSound} hands the player to {@code Level#playSound} as the
     * <em>except</em> argument, and that overload turns a Player argument into a recipient to
     * exclude from the broadcast. The pilot is therefore filtered out of the vanilla sound no
     * matter how close they are standing - opening a door from the cockpit is silent by
     * construction. The echo is unconditional; the vanilla broadcast still covers everyone else.
     */
    private static void echoDoorSound(ServerPlayer player, Block block, boolean opening) {
        SoundEvent sound;
        if (block instanceof DoorBlock door) {
            sound = opening ? door.type().doorOpen() : door.type().doorClose();
        } else if (block instanceof FenceGateBlock gate) {
            sound = opening ? gate.openSound : gate.closeSound;
        } else if (block == Blocks.IRON_TRAPDOOR) {
            sound = opening ? SoundEvents.IRON_TRAPDOOR_OPEN : SoundEvents.IRON_TRAPDOOR_CLOSE;
        } else {
            // TrapDoorBlock keeps its BlockSetType private, so fall back to the vanilla wooden pair.
            sound = opening ? SoundEvents.WOODEN_TRAPDOOR_OPEN : SoundEvents.WOODEN_TRAPDOOR_CLOSE;
        }
        player.playNotifySound(sound, SoundSource.BLOCKS, 1.0F,
                0.9F + player.getRandom().nextFloat() * 0.1F);
    }

    private static void recall(ServerLevel level, ServerPlayer player) {
        ReconDroneEntity drone = ReconDroneEntity.findDeployed(player);
        if (drone == null) {
            player.displayClientMessage(Component.translatable("message.watchcraft.nothing_to_recall"), true);
            return;
        }
        drone.recallTo(player);
        player.displayClientMessage(Component.translatable("message.watchcraft.recalled"), true);
    }

    private static void handleMove(DroneMovePayload payload, IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer player)) {
            return;
        }
        ServerLevel level = player.serverLevel();
        ReconDroneEntity drone = piloted(level, player, payload.droneId());
        if (drone == null) {
            return;
        }
        // 雪花屏阶段机体已经炸了，位置由服务端冻结。这时候驾驶员那侧还会继续发包，
        // 直接丢掉，否则镜头会被拖回它最后飞过的地方。
        if (drone.isDetonated()) {
            return;
        }
        // One packet per tick. A stock client sends exactly one; anything more is a client trying
        // to buy speed, because MAX_STEP bounds a single packet rather than the packet rate.
        if (!drone.claimMoveSlot(drone.tickCount)) {
            return;
        }

        Vec3 target = new Vec3(payload.x(), payload.y(), payload.z());
        if (!Double.isFinite(target.x) || !Double.isFinite(target.y) || !Double.isFinite(target.z)) {
            return;
        }
        // On a charge the server owns the position; the client's reported position is discarded.
        // Do NOT run the ordinary MAX_STEP test against it first: the server flies 1.45 blocks/tick,
        // while the client chases a delayed position echo. A few ticks of network latency can put
        // those two copies more than 1.8 blocks apart, dropping legitimate steering packets and
        // making small corrections land in jerks. Ownership, finite coordinates and the one-packet-
        // per-tick limit have already been checked. steerCharge validates the angles and clamps
        // them to the server's cone. The leash is skipped for the committed run as before.
        if (drone.isCharging()) {
            drone.setRoll(payload.roll());
            drone.steerCharge(player, payload.yRot(), payload.xRot());
            return;
        }

        // In ordinary flight the client does own movement, so keep the per-packet speed and
        // block-collision checks exactly as before. Only the charge skips this position check.
        Vec3 from = drone.position();
        double stepSqr = from.distanceToSqr(target);
        if (stepSqr > ReconDroneEntity.MAX_STEP * ReconDroneEntity.MAX_STEP) {
            return;
        }

        // Cosmetic roll is only applied after ordinary flight passes its movement checks.
        drone.setRoll(payload.roll());
        if (player.position().distanceToSqr(target)
                > ReconDroneEntity.LINK_RANGE * ReconDroneEntity.LINK_RANGE) {
            drone.disconnectPilot();
            return;
        }

        // Blocks are resolved here the same way Entity#move resolves them, by sweeping the
        // airframe's own bounding box along the requested delta. A stock client has already run
        // that exact sweep before it sent the position, so for it this is a no-op; a client that
        // claims to be on the far side of a wall gets clamped to the wall instead of through it.
        Vec3 delta = target.subtract(from);
        Vec3 allowed = delta.lengthSqr() == 0.0D
                ? delta
                : Entity.collideBoundingBox(drone, delta, drone.getBoundingBox(), level, List.of());

        drone.setPos(from.x + allowed.x, from.y + allowed.y, from.z + allowed.z);
        drone.setYRot(payload.yRot());
        drone.setXRot(payload.xRot());
        drone.clampToWorld(level);
        drone.setDeltaMovement(Vec3.ZERO);
    }

    /**
     * Drops whatever link this player is holding.
     *
     * <p>There is never more than one, but the link is found by search rather than by remembering
     * an id: a drone can be left behind by a disconnect, and a stale id would leave it listening
     * to a player who has long since moved on to something else.
     */
    private static void releasePlayerLink(ServerLevel level, ServerPlayer player) {
        for (ReconDroneEntity drone : level.getEntitiesOfClass(ReconDroneEntity.class,
                player.getBoundingBox().inflate(ReconDroneEntity.LINK_RANGE * 2.0D),
                candidate -> candidate.isPilotedBy(player))) {
            drone.setPilotId(-1);
        }
    }

    private static void throwOne(ServerLevel level, ServerPlayer player) {
        if (ReconDroneEntity.hasDeployed(player)) {
            player.displayClientMessage(Component.translatable("message.watchcraft.limit"), true);
            return;
        }
        Inventory inventory = player.getInventory();
        int selected = inventory.selected;
        ItemStack stack = inventory.getItem(selected);
        if (!stack.is(ModItems.RECON_DRONE.get())) {
            stack = ItemStack.EMPTY;
            for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
                ItemStack candidate = inventory.getItem(slot);
                if (candidate.is(ModItems.RECON_DRONE.get())) {
                    stack = candidate;
                    break;
                }
            }
        }
        if (stack.isEmpty()) {
            player.displayClientMessage(Component.translatable("message.watchcraft.no_item"), true);
            return;
        }
        ReconDroneEntity.throwFrom(level, player, stack);
    }
}
