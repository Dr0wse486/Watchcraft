package dev.watchcraft.watchcraft.client;

import dev.watchcraft.watchcraft.entity.DroneControlState;
import dev.watchcraft.watchcraft.entity.ReconDroneEntity;
import dev.watchcraft.watchcraft.item.DroneModules;
import dev.watchcraft.watchcraft.network.DroneActionPayload;
import dev.watchcraft.watchcraft.network.DroneMovePayload;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.Input;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.MovementInputUpdateEvent;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Client side remote control.
 *
 * <p>The camera entity is swapped to the drone, but the mouse handler only ever knows about the
 * local player, so whatever it does to the player has to be harvested and handed to the drone
 * every frame. The player body is then pinned back to the angle it held when the link went up,
 * body and head rotations included, otherwise the player model visibly swings around while the
 * drone is being flown.
 */
public final class DroneController {

    private static float lockedYaw;
    private static float lockedPitch;

    /** Tracks the right mouse button so a held click only triggers one drone reach. */
    private static boolean useKeyWasDown;

    /**
     * Current velocity in blocks per tick, carried between ticks. This is what gives the airframe
     * weight: letting go of the keys asks for zero velocity, but the drone has to bleed the old
     * one off first, so it coasts to a stop instead of snapping.
     */
    private static Vec3 drift = Vec3.ZERO;
    /** How quickly the velocity picks up when the pilot asks for more. */
    private static final double DRIFT_ACCEL = 0.35D;
    /**
     * How slowly it bleeds off. At {@code FLIGHT_SPEED} this is roughly one block of coast spread
     * over half a second - enough to read as momentum without making the drone hard to park.
     */
    private static final double DRIFT_DECEL = 0.20D;

    /**
     * Last body position reported to the server, so a body that is standing still costs no
     * traffic. {@link Vec3#ZERO} at link time doubles as "never reported yet".
     */
    private static Vec3 reportedBody = Vec3.ZERO;
    private static boolean reportedOnGround;

    // ------------------------------------------------------------------ attack run trigger

    /** How long after asking for a run before another request may be sent. */
    private static final int CHARGE_REQUEST_COOLDOWN = 10;

    private static boolean sprintWasDown;
    private static int lastChargeRequest = -1000;

    // ------------------------------------------------------------------ attack run aiming

    /**
     * Time constant of the aim damping, in seconds.
     *
     * <p>While a run is up the crosshair does not follow the mouse one for one. The raw movement
     * feeds a desired angle and the crosshair chases it on a first order lag, which is the same
     * shape as vanilla's own cinematic camera: the view keeps drifting for a moment after the mouse
     * stops, and a flick arrives as a sweep rather than as a jump. On a cone this tight that is
     * what makes the aim feel tight rather than twitchy - the crosshair spends its time near the
     * middle of the envelope instead of slamming into the edge and sticking there.
     *
     * <p>Expressed as a time constant rather than as a per frame fraction so the feel does not
     * change with the frame rate. At 0.07 seconds the crosshair closes about two thirds of the gap
     * in four frames at 60 fps and is settled inside a fifth of a second: smooth, but far too quick
     * to read as input lag.
     */
    private static final double CHARGE_AIM_TAU = 0.07D;

    /** Aim as asked for by the mouse. */
    private static float chargeDesiredYaw;
    private static float chargeDesiredPitch;
    /** Aim after damping, which is what actually reaches the drone. */
    private static float chargeAimYaw;
    private static float chargeAimPitch;
    /** True once the aim state has been seeded for the run in progress. */
    private static boolean chargeAimSeeded;
    private static long lastFrameNanos;

    private DroneController() {
    }

    public static boolean isLinked() {
        return DroneControlState.pilotedDroneId >= 0;
    }

    public static int getLinkedId() {
        return DroneControlState.pilotedDroneId;
    }

    public static void link(int droneId) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null || minecraft.player == null) {
            return;
        }
        Entity entity = minecraft.level.getEntity(droneId);
        if (!(entity instanceof ReconDroneEntity drone)) {
            return;
        }
        DroneControlState.pilotedDroneId = droneId;
        lockedYaw = minecraft.player.getYRot();
        lockedPitch = minecraft.player.getXRot();
        useKeyWasDown = false;
        sprintWasDown = minecraft.options.keySprint.isDown();
        lastChargeRequest = -1000;
        // Linking in mid sprint leaves the body carrying its own momentum, so shed it up front
        // rather than letting the player skid on for half a second after the camera has left.
        holdPlayer(minecraft.player);
        // Force the first body report: the server still believes the body is where it was.
        reportedBody = Vec3.ZERO;
        reportedOnGround = false;
        resetFlight();
        // Linking in from behind an open inventory would leave it sitting over the visor forever.
        if (minecraft.screen != null) {
            minecraft.setScreen(null);
        }
        minecraft.setCameraEntity(drone);
    }

    public static void unlink() {
        Minecraft minecraft = Minecraft.getInstance();
        DroneControlState.reset();
        useKeyWasDown = false;
        sprintWasDown = false;
        lastChargeRequest = -1000;
        resetFlight();
        if (minecraft.player != null) {
            minecraft.setCameraEntity(minecraft.player);
        }
    }

    /** Called every client tick, before rendering. */
    public static void tick() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || minecraft.level == null) {
            if (isLinked()) {
                unlink();
            }
            return;
        }

        if (!minecraft.options.keyUse.isDown()) {
            useKeyWasDown = false;
        }

        handleKeys(minecraft);
        ClientPayloadHandler.verifyLink();

        if (!isLinked()) {
            return;
        }
        Entity camera = minecraft.getCameraEntity();
        if (!(camera instanceof ReconDroneEntity drone)) {
            unlink();
            return;
        }

        syncLook();
        holdPlayer(minecraft.player);
        reportBodyPosition(minecraft, minecraft.player);

        boolean sprint = minecraft.options.keySprint.isDown();

        // A run is the server's to fly, but not the pilot's to stop aiming: the position in this
        // packet is ignored on arrival, the rotation is not. That split is the whole steering
        // model - the server owns where the airframe is, the pilot owns which way it points, and
        // the server clamps that to the cone before it acts on it.
        if (drone.isCharging()) {
            drift = Vec3.ZERO;
            sprintWasDown = sprint;
            sendMove(drone);
            return;
        }

        handleChargeTrigger(minecraft, drone, sprint);
        fly(minecraft, drone);
        sendMove(drone);
    }

    private static void sendMove(ReconDroneEntity drone) {
        PacketDistributor.sendToServer(new DroneMovePayload(
                drone.getId(),
                drone.getX(),
                drone.getY(),
                drone.getZ(),
                drone.getYRot(),
                drone.getXRot()));
    }

    /**
     * Watches for the attack input: a fresh press of sprint.
     *
     * <p>Edge triggered, and that matters more than it looks. Sprint defaults to left control,
     * which is a key people rest a finger on; reading it as a level rather than as a press would
     * fire the warhead the moment the pilot leaned on it.
     *
     * <p>There used to be a second trigger - a double tap of forward - and it is gone. With the
     * run no longer a stoop, forward is the axis the pilot is about to lock, and tapping it twice
     * to arm a warhead put a destructive action on the same key that flies the drone.
     *
     * <p>The whole trigger is skipped unless the airframe actually carries a warhead, so a drone
     * without one cannot even send the request.
     */
    private static void handleChargeTrigger(Minecraft minecraft, ReconDroneEntity drone, boolean sprint) {
        if (!DroneModules.has(drone.getModules(), DroneModules.ATTACK)) {
            sprintWasDown = sprint;
            return;
        }
        if (sprint && !sprintWasDown) {
            requestCharge(drone.tickCount);
        }
        sprintWasDown = sprint;
    }

    private static void requestCharge(int now) {
        if (now - lastChargeRequest < CHARGE_REQUEST_COOLDOWN) {
            return;
        }
        lastChargeRequest = now;
        PacketDistributor.sendToServer(
                new DroneActionPayload(DroneActionPayload.ACTION_CHARGE, getLinkedId()));
    }

    // ------------------------------------------------------------------ attack run fov

    /**
     * How much wider the view opens up during a run. Thirty percent is enough to read as being
     * hurled forward without the projection distorting at the edges of a wide monitor.
     */
    private static final double CHARGE_FOV_GAIN = 1.30D;

    /**
     * Fraction of the remaining gap to the target gain closed each frame.
     *
     * <p>Frame paced rather than tick paced, because the hook it feeds is per frame. At 60 fps this
     * arrives in about a fifth of a second, which reads as the view being yanked open; the same
     * factor at 20 fps takes half a second, which still reads as a pull rather than a cut. Easing
     * is worth the two numbers it costs: snapping the FOV on the tick the warhead arms looks like
     * a rendering fault rather than like acceleration.
     */
    private static final double CHARGE_FOV_EASE = 0.15D;

    /** Smoothed multiplier handed to the FOV hook. Neutral when nothing is charging. */
    private static double chargeFov = 1.0D;

    /**
     * Drives the charge FOV toward its target, once per frame.
     *
     * <p>Reads the charge flag off the drone the camera is actually on, not off the link state, so
     * a run that ends because the airframe was shot down eases back out exactly like one that
     * ended on impact.
     */
    public static void tickChargeFov() {
        boolean charging = Minecraft.getInstance().getCameraEntity() instanceof ReconDroneEntity drone
                && drone.isCharging();
        double target = charging ? CHARGE_FOV_GAIN : 1.0D;
        chargeFov += (target - chargeFov) * CHARGE_FOV_EASE;
        if (Math.abs(chargeFov - 1.0D) < 1.0E-3D) {
            chargeFov = 1.0D;
        }
    }

    /** {@return the multiplier the FOV hook should apply to the projection this frame} */
    public static double chargeFovScale() {
        return chargeFov;
    }

    /**
     * {@return how far into the charge effect the visor is, from 0 (none) to 1 (full)}
     *
     * <p>Read by the screen-space half of the effect so the speed streaks and the FOV pull are
     * driven by one ramp and cannot come in or out of step with each other.
     */
    public static double chargeEffectIntensity() {
        return Mth.clamp((chargeFov - 1.0D) / (CHARGE_FOV_GAIN - 1.0D), 0.0D, 1.0D);
    }

    private static void handleKeys(Minecraft minecraft) {
        while (KeyMappings.LINK.consumeClick()) {
            if (minecraft.screen != null) {
                continue;
            }
            if (isLinked()) {
                PacketDistributor.sendToServer(
                        new DroneActionPayload(DroneActionPayload.ACTION_EXIT, getLinkedId()));
            } else {
                PacketDistributor.sendToServer(
                        new DroneActionPayload(DroneActionPayload.ACTION_ENTER, -1));
            }
        }
        while (KeyMappings.THROW.consumeClick()) {
            if (minecraft.screen == null) {
                PacketDistributor.sendToServer(
                        new DroneActionPayload(DroneActionPayload.ACTION_THROW, -1));
            }
        }
        while (KeyMappings.RECALL.consumeClick()) {
            if (minecraft.screen == null) {
                PacketDistributor.sendToServer(
                        new DroneActionPayload(DroneActionPayload.ACTION_RECALL, -1));
            }
        }
    }

    /**
     * Moves the mouse delta the player just received onto the drone, then pins the player back.
     *
     * <p>Run once per frame as well as once per tick. The mouse handler rotates the player between
     * ticks, so sampling it only at tick rate would leave the drone's view stepping at 20 Hz.
     * Because it is sampled every frame the camera needs no blending and tracks the mouse exactly.
     */
    public static void syncLook() {
        Minecraft minecraft = Minecraft.getInstance();
        LocalPlayer player = minecraft.player;
        if (player == null || !isLinked()) {
            return;
        }
        Entity camera = minecraft.getCameraEntity();
        if (!(camera instanceof ReconDroneEntity drone)) {
            return;
        }

        float deltaYaw = Mth.wrapDegrees(player.getYRot() - lockedYaw);
        float deltaPitch = player.getXRot() - lockedPitch;

        if (drone.isCharging()) {
            steerRun(drone, deltaYaw, deltaPitch);
        } else {
            chargeAimSeeded = false;
            if (deltaYaw != 0.0F || deltaPitch != 0.0F) {
                drone.setYRot(Mth.wrapDegrees(drone.getYRot() + deltaYaw));
                drone.setXRot(Mth.clamp(drone.getXRot() + deltaPitch, -90.0F, 90.0F));
                drone.yRotO = drone.getYRot();
                drone.xRotO = drone.getXRot();
            }
        }

        // The body and head angles chase the yaw over several ticks, so pinning the yaw alone still
        // lets the player model turn. All four have to be held down, old values included.
        player.setYRot(lockedYaw);
        player.setXRot(lockedPitch);
        player.yRotO = lockedYaw;
        player.xRotO = lockedPitch;
        player.yBodyRot = lockedYaw;
        player.yBodyRotO = lockedYaw;
        player.yHeadRot = lockedYaw;
        player.yHeadRotO = lockedYaw;
    }

    /**
     * Damped steering for an attack run.
     *
     * <p>Runs on every frame whether or not the mouse moved, because the damping has to be allowed
     * to finish converging after the last input. Skipping it on quiet frames would leave the
     * crosshair parked wherever the final mouse event happened to drop it.
     *
     * <p>The aim is clamped to the cone <em>and stored clamped</em>. That is what gives the cone a
     * hard edge with no windup: past the edge further movement is simply discarded, so bringing the
     * mouse back moves the crosshair at once instead of first unwinding an invisible backlog.
     *
     * <p>The axis comes from the entity's synced data rather than from a local copy, so this is
     * literally the same reference the server clamps against and the two sides cannot disagree
     * about where the run is heading.
     */
    private static void steerRun(ReconDroneEntity drone, float deltaYaw, float deltaPitch) {
        if (!chargeAimSeeded) {
            chargeAimSeeded = true;
            // Discard any frame gap accumulated since the last run; the seed frame is a full jump.
            lastFrameNanos = 0L;
            chargeDesiredYaw = drone.getYRot();
            chargeDesiredPitch = drone.getXRot();
            chargeAimYaw = chargeDesiredYaw;
            chargeAimPitch = chargeDesiredPitch;
        }

        chargeDesiredYaw = Mth.wrapDegrees(chargeDesiredYaw + deltaYaw);
        chargeDesiredPitch = Mth.clamp(chargeDesiredPitch + deltaPitch, -90.0F, 90.0F);

        // First order lag, frame rate independent: k is the fraction of the remaining gap closed
        // over a frame of this length.
        double k = 1.0D - Math.exp(-frameSeconds() / CHARGE_AIM_TAU);
        chargeAimYaw = Mth.wrapDegrees(chargeAimYaw
                + (float) (Mth.wrapDegrees(chargeDesiredYaw - chargeAimYaw) * k));
        chargeAimPitch += (float) ((chargeDesiredPitch - chargeAimPitch) * k);

        Vec3 clamped = ReconDroneEntity.clampToCone(
                drone.chargeAxis(),
                ReconDroneEntity.viewVector(chargeAimYaw, chargeAimPitch),
                ReconDroneEntity.CHARGE_CONE);
        chargeAimYaw = ReconDroneEntity.yawOf(clamped);
        chargeAimPitch = ReconDroneEntity.pitchOf(clamped);

        drone.setYRot(chargeAimYaw);
        drone.setXRot(chargeAimPitch);
        drone.yRotO = chargeAimYaw;
        drone.xRotO = chargeAimPitch;
    }

    /**
     * {@return seconds since the previous frame, clamped to something sane}
     *
     * <p>Measured here rather than taken from the render event so the damping cannot be thrown off
     * by an alt tab, a paused window or a loading hitch. The upper clamp matters as much as the
     * lower one: one enormous frame would otherwise close the entire gap in a single step, undoing
     * the smoothing at exactly the moment the frame rate is worst.
     */
    private static double frameSeconds() {
        long now = System.nanoTime();
        double seconds = lastFrameNanos == 0L ? 1.0D / 60.0D : (now - lastFrameNanos) / 1.0E9D;
        lastFrameNanos = now;
        return Mth.clamp(seconds, 1.0E-4D, 0.1D);
    }

    /**
     * {@return true on the first right click of a hold, false for the auto repeat}
     *
     * <p>Vanilla keeps calling {@code startUseItem} every four ticks while the button stays down,
     * which would otherwise flip a door open and shut several times a second.
     */
    public static boolean beginInteract() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.options.keyUse.isDown() && useKeyWasDown) {
            return false;
        }
        useKeyWasDown = true;
        return true;
    }

    /** Asks the server to let the drone work whatever its own crosshair is pointing at. */
    public static void requestInteract() {
        if (!isLinked()) {
            return;
        }
        PacketDistributor.sendToServer(
                new DroneActionPayload(DroneActionPayload.ACTION_INTERACT, getLinkedId()));
    }

    private static void resetFlight() {
        drift = Vec3.ZERO;
        // Linking out mid run has to drop the aim state too, or the next run seeds from a stale
        // angle and the crosshair snaps on its first frame.
        chargeAimSeeded = false;
    }

    /**
     * Parks the operator's body for as long as the link is up.
     *
     * <p>Zeroing the movement input is not enough on its own. A player who links in mid sprint
     * still carries their momentum and skids on for another half second, anyone who links in mid
     * jump keeps falling, and anyone who links in mid walk keeps walking - in every case the body
     * visibly keeps going on its own while the pilot is looking somewhere else entirely.
     *
     * <p>So the body is parked outright: sprint cancelled, sideways drift dropped, fall distance
     * cleared. Three things here are load bearing and all three are easy to get wrong:
     *
     * <ul>
     *   <li><b>The input triple is cleared here, not through the movement input event.</b>
     *       {@code LocalPlayer#serverAiStep} only copies the input into {@code xxa}/{@code zza}
     *       while the camera is on the player, so with the camera on the drone they keep whatever
     *       was held at link time. {@code LivingEntity#aiStep} then decays them by 0.98 a tick and
     *       hands them to {@code travel}, which walks the body off gently in that direction for a
     *       few seconds before it fades out. Clearing the event's copy of the input does not help:
     *       with the camera away that copy is never read.</li>
     *   <li><b>The vertical velocity is left alone.</b> Gravity only adds 0.078 blocks per tick,
     *       so cancelling the velocity every tick cancels the acceleration with it: the body
     *       would sink at a steady walking pace instead of falling, which reads as floating.
     *       Clearing the fall distance above is what makes the landing free, so the drop does not
     *       need to be slowed down in the first place.</li>
     *   <li><b>The position is never written.</b> An earlier version pinned the body onto the
     *       spot it landed on, and the pin engaged at exactly the tick {@code onGround} flipped -
     *       fighting the landing while the physics was still resolving it, which showed up as a
     *       stutter the moment the body touched down. The pin bought nothing anyway: with no
     *       input and no sideways velocity there is nothing left to move the body.</li>
     * </ul>
     */
    private static void holdPlayer(LocalPlayer player) {
        player.fallDistance = 0.0F;
        player.setSprinting(false);
        player.setJumping(false);

        // Otherwise the body keeps walking: see the notes above.
        player.xxa = 0.0F;
        player.yya = 0.0F;
        player.zza = 0.0F;

        // Keep whatever gravity has already built up; drop everything sideways. On the ground the
        // collision has already flattened the vertical component to zero, so this is a no-op there
        // and a pure anti-skid elsewhere.
        Vec3 motion = player.getDeltaMovement();
        player.setDeltaMovement(0.0D, motion.y, 0.0D);
    }

    /**
     * Keeps the server's copy of the body in step with this one while the camera is away.
     *
     * <p>Vanilla stops reporting a player's position the moment the camera is put on something
     * else: {@code LocalPlayer#sendPosition} sits entirely inside an {@code isControlledCamera()}
     * check, which is false for as long as the drone has the camera. That is normally invisible,
     * because the body normally does not move either. But the body is deliberately left to fall
     * here, so without this the whole drop would only ever be reported once - on the tick the
     * pilot leaves - as a single jump from the parked spot to the landing spot. The server replays
     * that jump through its own collision and fall damage, which means a drop the server never saw
     * happening is charged in full the instant the pilot dismounts, and on a remote server the
     * per-tick speed check can bounce the player back to where they took off. One small packet per
     * tick while the body is actually moving keeps both sides honest.
     *
     * <p>Reporting is safe to do here because the checks the server runs on it all pass for a body
     * that is falling or standing: the anti-fly check only trips when the body reports no downward
     * movement while having no block within half a block underneath, which is exactly what a body
     * in free fall never looks like.
     */
    private static void reportBodyPosition(Minecraft minecraft, LocalPlayer player) {
        if (minecraft.getConnection() == null) {
            return;
        }
        boolean moved = player.position().distanceToSqr(reportedBody) > 4.0E-8D;
        if (!moved && player.onGround() == reportedOnGround) {
            return;
        }
        reportedBody = player.position();
        reportedOnGround = player.onGround();
        minecraft.getConnection().send(new ServerboundMovePlayerPacket.PosRot(
                player.getX(), player.getY(), player.getZ(),
                player.getYRot(), player.getXRot(), player.onGround()));
    }

    /**
     * Flies the drone relative to where its camera is pointing, then carries the velocity over
     * between ticks.
     *
     * <p>Two things are load bearing here:
     *
     * <ul>
     *   <li><b>The view only sets the direction, never the speed.</b> Forward is the full view
     *       vector with pitch included, so aiming at the sky and holding forward climbs and
     *       aiming at the ground descends. But with no key held the direction is zero, so looking
     *       around a parked drone does not budge it - which is the whole point of flying it like
     *       a recon drone rather than a helicopter.</li>
     *   <li><b>Velocity, not displacement.</b> The keys feed a target velocity and the real one
     *       chases it, fast when accelerating and slowly when slowing down. That asymmetry is the
     *       momentum: let go mid flight and the drone drifts on for about half a second.</li>
     * </ul>
     */
    private static void fly(Minecraft minecraft, ReconDroneEntity drone) {
        float forward = (minecraft.options.keyUp.isDown() ? 1.0F : 0.0F)
                - (minecraft.options.keyDown.isDown() ? 1.0F : 0.0F);
        float strafe = (minecraft.options.keyLeft.isDown() ? 1.0F : 0.0F)
                - (minecraft.options.keyRight.isDown() ? 1.0F : 0.0F);
        float vertical = (minecraft.options.keyJump.isDown() ? 1.0F : 0.0F)
                - (minecraft.options.keyShift.isDown() ? 1.0F : 0.0F);

        // Forward is the view vector. Strafe runs along the left hand vector - the same heading
        // flattened onto the horizontal plane - so A and D always sidestep instead of peeling off
        // into a dive. strafe is +1 for A and -1 for D, which is why this is the left hand one.
        //
        // Derivation, because the sign is easy to get backwards: the view vector's horizontal
        // component is (-sin(yaw), cos(yaw)), and rotating that a quarter turn to the left gives
        // (cos(yaw), sin(yaw)). Sanity check at yaw 0 (facing south, +Z): that yields +X, and east
        // really is on your left when you face south.
        float yawRad = drone.getYRot() * Mth.DEG_TO_RAD;
        Vec3 look = drone.getViewVector(1.0F);
        Vec3 left = new Vec3(Mth.cos(yawRad), 0.0D, Mth.sin(yawRad));

        Vec3 direction = look.scale(forward)
                .add(left.scale(strafe))
                .add(0.0D, vertical, 0.0D);
        if (direction.lengthSqr() > 1.0D) {
            // Holding a diagonal would otherwise stack past the cruise speed.
            direction = direction.normalize();
        }

        Vec3 target = direction.scale(ReconDroneEntity.FLIGHT_SPEED);

        double response = target.lengthSqr() > drift.lengthSqr() ? DRIFT_ACCEL : DRIFT_DECEL;
        drift = drift.lerp(target, response);
        if (drift.lengthSqr() < 1.0E-6D) {
            drift = Vec3.ZERO;
            return;
        }

        drone.move(MoverType.SELF, drift);
        if (drone.horizontalCollision || drone.verticalCollision) {
            // Met something solid: drop the coast so the drone does not grind along the wall.
            drift = Vec3.ZERO;
        }

        double minY = minecraft.level.getMinBuildHeight() + 0.1D;
        double maxY = minecraft.level.getMaxBuildHeight() - 1.0D;
        if (drone.getY() < minY || drone.getY() > maxY) {
            drone.setPos(drone.getX(), Mth.clamp(drone.getY(), minY, maxY), drone.getZ());
            drift = Vec3.ZERO;
        }
    }

    /**
     * Swallows the keys that would open a screen or shuffle the hotbar while piloting.
     *
     * <p>Both are cosmetic problems rather than mechanical ones: an inventory screen obviously
     * breaks the visor illusion, and even a plain hotbar change is enough to do it, because
     * vanilla pops the item's name up in the middle of the screen for a moment afterwards.
     *
     * <p>Timing is the whole trick. {@code Minecraft#handleKeybinds} runs from the middle of
     * {@code Minecraft#tick}, while {@code ClientTickEvent.Pre} fires at the very head of it, so
     * draining the click counters here leaves the vanilla handler with nothing to consume. Doing
     * this from {@code ClientTickEvent.Post} would be too late - the screen would already be open.
     */
    public static void swallowUiKeys() {
        if (!isLinked()) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        drain(minecraft.options.keyInventory);
        for (KeyMapping slot : minecraft.options.keyHotbarSlots) {
            drain(slot);
        }
        // Neither of these changes the hotbar, but both let a pilot move items around an inventory
        // they cannot see, which is the same class of accident.
        drain(minecraft.options.keySwapOffhand);
        drain(minecraft.options.keyDrop);
    }

    private static void drain(KeyMapping mapping) {
        while (mapping.consumeClick()) {
            // Discarded on purpose: the press happened, we simply refuse to act on it.
        }
    }

    /** While linked the player body must not walk away on its own. */
    public static void onMovementInput(MovementInputUpdateEvent event) {
        if (!isLinked()) {
            return;
        }
        if (event.getEntity() != Minecraft.getInstance().player) {
            return;
        }
        Input input = event.getInput();
        input.forwardImpulse = 0.0F;
        input.leftImpulse = 0.0F;
        input.jumping = false;
        input.shiftKeyDown = false;
        input.up = false;
        input.down = false;
        input.left = false;
        input.right = false;
    }
}
