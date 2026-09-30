package dev.watchcraft.watchcraft.entity;

import dev.watchcraft.watchcraft.item.DroneModules;
import dev.watchcraft.watchcraft.registry.ModEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Explosion;
import net.minecraft.world.level.ExplosionDamageCalculator;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * A small quadcopter scout drone.
 *
 * <p>Behaviour:
 * <ul>
 *   <li>Right click a block with the drone item to deploy it.</li>
 *   <li>The owner can press the link key to take remote control (camera hops to the drone).</li>
 *   <li>While linked, movement is client driven and validated here (speed + leash range).</li>
 *   <li>Any living entity inside the drone's view cone, unobstructed by blocks, is tagged as
 *       glowing for a short while so it can be marked for the player.</li>
 *   <li>A thrown drone flies ballistically, then hovers, and keeps scanning on the way.</li>
 * </ul>
 */
public class ReconDroneEntity extends Entity {

    // ------------------------------------------------------------------ tuning

    /** How many drones one player may have out in the world at the same time. */
    public static final int MAX_DEPLOYED = 1;

    /** How far the pilot may wander away from the drone before the link drops. */
    public static final double LINK_RANGE = 64.0D;
    /** Distance at which the visor picture starts to break up. */
    public static final double STATIC_ONSET = 48.0D;
    /**
     * Extra static strength for every this many blocks past the onset. The gap between
     * {@link #STATIC_ONSET} and {@link #LINK_RANGE} is sixteen blocks, so this divides it into
     * five even grades: clean at 48, 25% at 52, 50% at 56, 75% at 60, whiteout at 64.
     *
     * <p>Note that {@code LINK_RANGE * 0.75} - the point where the range readout turns amber - is
     * also 48, so the picture and the warning light come on together.
     */
    public static final double STATIC_STEP = 4.0D;
    /** How far the camera can spot living entities. */
    public static final double SCAN_RANGE = 48.0D;
    /** Full cone angle of the scan, in degrees. */
    public static final double SCAN_FOV = 110.0D;
    /**
     * Full cone angle used while the drone is still in its throw.
     *
     * <p>Deliberately wider than the piloted cone. A thrown drone tumbles along its arc, so its
     * nose sweeps down and away from whatever the player was actually looking at; a cone tight
     * enough to feel like aiming slides straight past the target it flew over.
     */
    public static final double THROW_SCAN_FOV = 150.0D;
    /**
     * Inside this radius the cone is ignored entirely - anything this close to the lens is in
     * frame no matter which way the drone happens to be pointing.
     */
    public static final double PING_RADIUS = 8.0D;
    /** The same idea, stretched out while thrown, so a fast pass still pings what it flies by. */
    public static final double THROW_PING_RADIUS = 16.0D;
    /** Ticks a spotted entity stays lit. */
    public static final int GLOW_DURATION = 60;
    /**
     * Ticks between two scans.
     *
     * <p>Two, not eight. Only one drone can exist at a time, so this is the entire scan budget the
     * server spends, and eight ticks was slow enough that a thrown drone crossed a target's whole
     * angular width between two sweeps without ever seeing it.
     */
    public static final int SCAN_INTERVAL = 2;
    /** Ceiling on line-of-sight raycasts per scan, so a crowd cannot turn a sweep into a stall. */
    public static final int MAX_RAYCASTS_PER_SCAN = 24;
    /** Blocks per tick while piloted. Kept deliberately slow so the drone reads as a scout. */
    public static final double FLIGHT_SPEED = 0.24D;
    /** How far the drone can reach when the pilot right clicks. */
    public static final double INTERACT_RANGE = 5.0D;
    /** Hard cap on how far the server accepts a single client reported move. */
    public static final double MAX_STEP = 1.8D;
    /** Initial speed of a thrown drone. */
    public static final double THROW_SPEED = 0.85D;
    /** How long a thrown drone keeps its ballistic phase. */
    public static final int THROW_TICKS = 60;

    // ------------------------------------------------------------------ combat

    /** Hit points. Ten points is five hearts, so a handful of sword swings brings it down. */
    public static final float MAX_HEALTH = 10.0F;
    /** Ticks of immunity after a hit, so the airframe cannot be burst down in one swing chain. */
    public static final int HURT_INVULNERABLE_TICKS = 10;
    /** Ticks the red damage flash lasts on the client. */
    public static final int HURT_FLASH_TICKS = 10;
    /** Vanilla entity event id for "took damage" - the same one LivingEntity broadcasts. */
    public static final byte EVENT_HURT = 2;

    // ------------------------------------------------------------------ attack run

    /** Blocks per tick during an attack run. Roughly six times cruise, which is what sells it. */
    public static final double CHARGE_SPEED = 1.45D;
    /** A run that hits nothing gives up after this long, so the drone cannot fly off forever. */
    public static final int CHARGE_MAX_TICKS = 60;
    /**
     * How far the pilot may swing the nose off the axis the run locked to, in degrees.
     *
     * <p>The run is a straight line, so the aim is a nudge rather than a steering wheel: the axis
     * is fixed at launch and the crosshair can only bend the path inside this cone. That is what
     * makes it read as a committed charge instead of a faster version of ordinary flight, and it
     * is why there is no pitch floor any more - aiming level now flies level rather than forcing
     * a stoop.
     *
     * <p>Kept deliberately tight. A wide cone stops reading as a committed line and starts reading
     * as ordinary flight with a speed boost, which defeats the point of locking the axis at all -
     * over the length of a run even sixteen degrees is worth twenty five blocks of lateral travel,
     * so there is plenty of room to lead a moving target without the line going slack.
     */
    public static final double CHARGE_CONE = 16.0D;
    /**
     * The envelope the server enforces, deliberately wider than {@link #CHARGE_CONE}.
     *
     * <p>The client clamps to the tight cone and the server clamps to this one, so an honest pilot
     * is never clamped twice and the two sides cannot disagree about where the run is going. A
     * modified client gets the wide cone at worst, which is a slightly longer nudge - not a free
     * turn.
     */
    public static final double CHARGE_CONE_SLACK = 22.0D;
    /** Ticks before the warhead can be armed again. Stops a held sprint key from re-triggering. */
    public static final int CHARGE_COOLDOWN_TICKS = 40;
    /** Blast radius. Small on purpose - the damage is what this is for, not the crater. */
    public static final float CHARGE_EXPLOSION_RADIUS = 2.5F;
    /** Blocks are only broken within this distance of the impact point. */
    public static final double CHARGE_CRATER = 1.6D;
    /** Flat damage every entity in the blast takes, before armour and enchantments. */
    public static final float CHARGE_DAMAGE = 20.0F;
    /**
     * How much of the remaining gap to the server's reported position the pilot's client closes
     * each tick while charging.
     *
     * <p>This is what makes the run smooth, and it exists because of a detail that is easy to
     * miss: {@code Entity#lerpTo} - the base one this entity inherits - is not an interpolation at
     * all, it is a plain {@code setPos}. Only {@code LivingEntity} overrides it with real
     * interpolation. So a base entity that takes the echo straight from the server steps its
     * position once per tick, and at {@link #CHARGE_SPEED} that is a twenty-times-a-second jump of
     * a block and a half. Chasing the echo instead spreads each jump across the ticks between them.
     *
     * <p>At 0.9 the steady-state lag behind the server is about a sixth of a block, which is well
     * under the airframe's own width, and the motion is a constant velocity rather than a series of
     * steps. Lower values smooth more but leave a visible gap between where the drone is drawn and
     * where the blast eventually goes off.
     */
    public static final double CHARGE_ECHO_CHASE = 0.9D;

    // ------------------------------------------------------------------ aiming maths

    /**
     * {@return the unit view vector for a yaw/pitch pair}
     *
     * <p>Vanilla's own {@code Entity#calculateViewVector} is protected and the sign conventions are
     * easy to get backwards, so this is written out rather than reached for through a subclass.
     * The conventions it has to match are: yaw 0 is south (+Z), yaw 90 is west (-X), and a pitch of
     * -90 looks straight up.
     */
    public static Vec3 viewVector(float yRot, float xRot) {
        float pitch = xRot * Mth.DEG_TO_RAD;
        float yaw = -yRot * Mth.DEG_TO_RAD;
        float cosYaw = Mth.cos(yaw);
        float sinYaw = Mth.sin(yaw);
        float cosPitch = Mth.cos(pitch);
        float sinPitch = Mth.sin(pitch);
        return new Vec3(sinYaw * cosPitch, -sinPitch, cosYaw * cosPitch);
    }

    /** {@return the yaw pointing along {@code direction}, the inverse of {@link #viewVector}} */
    public static float yawOf(Vec3 direction) {
        return (float) Math.toDegrees(Math.atan2(-direction.x, direction.z));
    }

    /** {@return the pitch pointing along {@code direction}, the inverse of {@link #viewVector}} */
    public static float pitchOf(Vec3 direction) {
        return (float) Math.toDegrees(-Math.asin(Mth.clamp(direction.y, -1.0D, 1.0D)));
    }

    /**
     * {@return {@code desired} bent back onto the cone of {@code maxAngle} degrees around {@code axis}}
     *
     * <p>Exact rather than approximate: the result lies in the plane spanned by the axis and the
     * requested direction, at exactly the cone's half angle. Both sides call this with the same
     * axis and the same requested direction, which is what stops the pilot's crosshair and the
     * airframe's real heading from drifting apart at the edge of the cone.
     */
    public static Vec3 clampToCone(Vec3 axis, Vec3 desired, double maxAngle) {
        Vec3 a = axis.normalize();
        Vec3 d = desired.normalize();
        double cos = a.dot(d);
        double limit = Math.cos(Math.toRadians(maxAngle));
        if (cos >= limit) {
            return d;
        }
        Vec3 sideways = d.subtract(a.scale(cos));
        if (sideways.lengthSqr() < 1.0E-9D) {
            // Straight back down the axis: there is no plane to bend in, so the axis is the only
            // sensible answer.
            return a;
        }
        return a.scale(limit)
                .add(sideways.normalize().scale(Math.sqrt(Math.max(0.0D, 1.0D - limit * limit))))
                .normalize();
    }

    // ------------------------------------------------------------------ bank

    /**
     * Degrees of roll per degree-per-tick of yaw change: the drone leans into its turns. Every
     * client derives this from the rotation stream it already receives, so it costs no extra
     * network traffic and every viewer sees the same lean.
     */
    public static final float BANK_GAIN = 1.8F;
    /** Hard cap on the turn lean. */
    public static final float MAX_BANK = 30.0F;
    /** How fast the lean chases its target, per tick. */
    public static final float BANK_SMOOTHING = 0.3F;

    // ------------------------------------------------------------------ data

    private static final EntityDataAccessor<Integer> DATA_PILOT_ID =
            SynchedEntityData.defineId(ReconDroneEntity.class, EntityDataSerializers.INT);

    private static final EntityDataAccessor<Integer> DATA_MARKS =
            SynchedEntityData.defineId(ReconDroneEntity.class, EntityDataSerializers.INT);

    private static final EntityDataAccessor<Float> DATA_HEALTH =
            SynchedEntityData.defineId(ReconDroneEntity.class, EntityDataSerializers.FLOAT);

    /** Loadout bit mask, mirrored from the drone item so every client can render and read it. */
    private static final EntityDataAccessor<Integer> DATA_MODULES =
            SynchedEntityData.defineId(ReconDroneEntity.class, EntityDataSerializers.INT);

    /**
     * Whether the drone is currently committed to an attack run.
     *
     * <p>Synced because both sides change behaviour on it: the pilot's client hands control of the
     * airframe over to the server, and {@link #lerpTo} stops swallowing the position echo, which
     * is the only way the run is ever seen to move.
     */
    private static final EntityDataAccessor<Boolean> DATA_CHARGING =
            SynchedEntityData.defineId(ReconDroneEntity.class, EntityDataSerializers.BOOLEAN);

    /**
     * The axis the current run is locked to, as a yaw/pitch pair.
     *
     * <p>Published rather than merely stored because the pilot's client has to clamp its own aim
     * against exactly the same reference the server will clamp against. Deriving the axis
     * independently on each side would let the two cones drift apart, and at the edge of the cone
     * that shows up as the crosshair pointing one way while the airframe flies another. Written
     * in the same tick as {@link #DATA_CHARGING}, so the client never sees one without the other.
     */
    private static final EntityDataAccessor<Float> DATA_CHARGE_YAW =
            SynchedEntityData.defineId(ReconDroneEntity.class, EntityDataSerializers.FLOAT);

    private static final EntityDataAccessor<Float> DATA_CHARGE_PITCH =
            SynchedEntityData.defineId(ReconDroneEntity.class, EntityDataSerializers.FLOAT);

    @Nullable
    private UUID ownerUUID;
    private boolean thrown;
    private int throwTicks;
    private int scanTimer;
    /** Ticks of immunity left after the last hit. Server side only. */
    private int hurtCooldown;
    /** Server side only: the last tick that accepted a movement packet, used to throttle clients. */
    private int lastMoveTick = -1;
    /** Server side: ticks left before the warhead may be armed again. */
    private int chargeCooldown;
    /** Server side: how long the current attack run has been going. */
    private int chargeTicks;
    /**
     * True for the instant the warhead goes off.
     *
     * <p>The blast damages everything in range, the drone included, and a second lethal hit would
     * route through {@link #hurt} into {@link #destroyDrone} and drop a second airframe on top of
     * the wreck. This is the latch that keeps the explosion from killing the drone twice.
     */
    private boolean detonating;

    /** Client side damage flash timer, driven by {@link #EVENT_HURT}. */
    private int hurtTime;
    /** Client side turn lean, plus last tick's value so the renderer can interpolate. */
    private float bank;
    private float bankO;
    private float bankYaw;
    /** Client side: the last position the server reported for a run, chased in {@link #clientTick}. */
    private Vec3 chargeTarget = Vec3.ZERO;
    private boolean chargeTargetSet;

    /** entity id -> remaining glow ticks, owned by this drone. */
    private final Map<Integer, Integer> marked = new HashMap<>();

    public ReconDroneEntity(EntityType<?> type, Level level) {
        super(type, level);
        this.setNoGravity(true);
        this.noPhysics = false;
    }

    // ------------------------------------------------------------------ setup

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        builder.define(DATA_PILOT_ID, -1);
        builder.define(DATA_MARKS, 0);
        builder.define(DATA_HEALTH, MAX_HEALTH);
        builder.define(DATA_MODULES, 0);
        builder.define(DATA_CHARGING, false);
        // The axis is only meaningful while a run is up, but it is defined unconditionally so the
        // client always has a value to clamp against rather than a null to guard.
        builder.define(DATA_CHARGE_YAW, 0.0F);
        builder.define(DATA_CHARGE_PITCH, 0.0F);
    }

    @Override
    protected void readAdditionalSaveData(CompoundTag tag) {
        this.ownerUUID = tag.hasUUID("Owner") ? tag.getUUID("Owner") : null;
        this.thrown = tag.getBoolean("Thrown");
        // A damaged airframe stays damaged across a reload.
        this.setHealth(tag.contains("Health") ? tag.getFloat("Health") : MAX_HEALTH);
        // And it keeps whatever was bolted to it.
        this.setModules(tag.getInt("Modules"));
        // A drone never stays linked across a reload.
        this.setPilotId(-1);
    }

    @Override
    protected void addAdditionalSaveData(CompoundTag tag) {
        if (this.ownerUUID != null) {
            tag.putUUID("Owner", this.ownerUUID);
        }
        tag.putBoolean("Thrown", this.thrown);
        tag.putFloat("Health", this.getHealth());
        tag.putInt("Modules", this.getModules());
    }

    // ------------------------------------------------------------------ identity

    public void setOwner(@Nullable Player player) {
        this.ownerUUID = player == null ? null : player.getUUID();
    }

    @Nullable
    public UUID getOwnerUUID() {
        return this.ownerUUID;
    }

    public boolean isOwnedBy(Player player) {
        return this.ownerUUID != null && this.ownerUUID.equals(player.getUUID());
    }

    public int getPilotId() {
        return this.entityData.get(DATA_PILOT_ID);
    }

    public void setPilotId(int id) {
        this.entityData.set(DATA_PILOT_ID, id);
    }

    @Nullable
    public ServerPlayer getPilot() {
        int id = this.getPilotId();
        if (id < 0 || !(this.level() instanceof ServerLevel serverLevel)) {
            return null;
        }
        Entity entity = serverLevel.getEntity(id);
        return entity instanceof ServerPlayer player ? player : null;
    }

    public boolean isPilotedBy(Player player) {
        return this.getPilotId() == player.getId();
    }

    /** Number of entities currently lit up by this drone, synced for the visor readout. */
    public int getMarkCount() {
        return this.entityData.get(DATA_MARKS);
    }

    public boolean isPiloted() {
        return this.getPilotId() >= 0;
    }

    // ------------------------------------------------------------------ loadout

    /** {@return the bit mask of modules currently fitted to this airframe} */
    public int getModules() {
        return this.entityData.get(DATA_MODULES);
    }

    public void setModules(int mask) {
        this.entityData.set(DATA_MODULES, mask & (DroneModules.CUSTOMIZATION | DroneModules.ATTACK));
    }

    public boolean hasModule(int flag) {
        return DroneModules.has(this.getModules(), flag);
    }

    /** {@return whether this drone is mid attack run, warhead armed and committed} */
    public boolean isCharging() {
        return this.entityData.get(DATA_CHARGING);
    }

    /**
     * Claims the single movement packet this drone will accept for the given tick.
     *
     * <p>{@link #MAX_STEP} bounds one packet, not the packet rate, so without this a modified
     * client can simply spray move packets and buy itself unlimited speed. A stock client sends
     * exactly one per tick, so the ceiling costs legitimate play nothing.
     *
     * @return true if a packet may be processed for this tick
     */
    public boolean claimMoveSlot(int tick) {
        if (tick <= this.lastMoveTick) {
            return false;
        }
        this.lastMoveTick = tick;
        return true;
    }

    // ------------------------------------------------------------------ health

    public float getHealth() {
        return this.entityData.get(DATA_HEALTH);
    }

    public void setHealth(float health) {
        this.entityData.set(DATA_HEALTH, Mth.clamp(health, 0.0F, MAX_HEALTH));
    }

    public boolean isDestroyed() {
        return this.getHealth() <= 0.0F;
    }

    /** Ticks of red damage flash left. Client side only. */
    public int getHurtTime() {
        return this.hurtTime;
    }

    /** Interpolated turn lean in degrees, for the renderer. */
    public float getBank(float partialTick) {
        return Mth.lerp(partialTick, this.bankO, this.bank);
    }

    public void markThrown() {
        this.thrown = true;
        this.throwTicks = 0;
    }

    // ------------------------------------------------------------------ entity overrides

    @Override
    public boolean isPickable() {
        return true;
    }

    @Override
    public boolean isPushable() {
        return false;
    }

    @Override
    public boolean canBeCollidedWith() {
        return false;
    }

    @Override
    public boolean isAttackable() {
        return true;
    }

    /**
     * Damage handling for the airframe.
     *
     * <p>This entity extends {@link Entity} rather than {@code LivingEntity}, and that is exactly
     * what makes it immune to every status effect for free: potions, area effect clouds and
     * {@code /effect} all resolve their targets as a {@code LivingEntity}, so a buff simply never
     * reaches the drone. Fire immunity comes from the entity type's {@code fireImmune()} flag,
     * which {@link #isInvulnerableTo(DamageSource)} already honours. Nothing here needs to filter
     * effects out - there is no effect pipeline to filter.
     */
    @Override
    public boolean hurt(DamageSource source, float amount) {
        if (this.isRemoved() || this.detonating) {
            return false;
        }
        if (amount <= 0.0F || this.isInvulnerableTo(source)) {
            return false;
        }
        if (this.level().isClientSide) {
            // Acknowledge the hit locally so the attacker's swing still lands with a thud, but let
            // the server be the one that moves the health bar and broadcasts the flash.
            return true;
        }
        if (this.hurtCooldown > 0) {
            return false;
        }
        this.hurtCooldown = HURT_INVULNERABLE_TICKS;
        this.markHurt();

        float remaining = this.getHealth() - amount;
        this.setHealth(remaining);
        if (remaining <= 0.0F) {
            this.destroyDrone();
            return true;
        }

        // Entity event 2 is the vanilla "took damage" flash; the client turns it into a red overlay.
        this.level().broadcastEntityEvent(this, EVENT_HURT);
        this.level().playSound(null, this.getX(), this.getY(), this.getZ(),
                SoundEvents.METAL_HIT, this.getSoundSource(),
                0.8F, 0.85F + this.random.nextFloat() * 0.3F);
        return true;
    }

    /** Goes down in a puff of smoke and sparks, leaving the airframe for its owner to recover. */
    private void destroyDrone() {
        this.goDown(Component.translatable("message.watchcraft.destroyed"), true);
    }

    /**
     * The end of an attack run.
     *
     * <p>Nothing is recovered, and that is the whole point: a charge is a one way trip, so every
     * part of the airframe goes with it - chassis, rotors, boards and all. Being shot down is the
     * cheap ending; spending the warhead is not.
     */
    private void wreckDrone() {
        this.goDown(Component.translatable("message.watchcraft.charge_destroyed"), false);
    }

    /**
     * Everything both endings share: unlink, drop the marks, raise the smoke, tell the owner, and
     * optionally leave the airframe behind.
     *
     * @param recoverable whether the drone should drop itself as an item for its owner to pick up
     */
    private void goDown(Component message, boolean recoverable) {
        this.prepareForRemoval(message);
        if (recoverable && this.ownerUUID != null) {
            this.spawnAtLocation(DroneModules.droneStack(this.getModules()), 0.25F);
        }
        this.discard();
    }

    /** Everything both endings share: unlink, drop the marks, raise the smoke, tell the owner. */
    private void prepareForRemoval(Component message) {
        if (!(this.level() instanceof ServerLevel serverLevel)) {
            return;
        }
        this.disconnectPilot();
        this.clearMarks();

        serverLevel.playSound(null, this.getX(), this.getY(), this.getZ(),
                SoundEvents.ITEM_BREAK, this.getSoundSource(), 1.0F, 0.9F);
        serverLevel.sendParticles(ParticleTypes.LARGE_SMOKE,
                this.getX(), this.getY() + 0.15D, this.getZ(),
                14, 0.22D, 0.14D, 0.22D, 0.02D);
        serverLevel.sendParticles(ParticleTypes.ELECTRIC_SPARK,
                this.getX(), this.getY() + 0.15D, this.getZ(),
                10, 0.18D, 0.12D, 0.18D, 0.06D);

        if (this.ownerUUID != null) {
            ServerPlayer owner = serverLevel.getServer().getPlayerList().getPlayer(this.ownerUUID);
            if (owner != null) {
                owner.displayClientMessage(message, true);
            }
        }
    }

    @Override
    public void handleEntityEvent(byte id) {
        if (id == EVENT_HURT) {
            this.hurtTime = HURT_FLASH_TICKS;
        } else {
            super.handleEntityEvent(id);
        }
    }

    /**
     * The local pilot owns this drone's transform, so the echo of our own reported position
     * coming back from the server must not drag the camera around. Client side only.
     *
     * <p>An attack run is the exception, and it has to be. The moment the warhead arms, the
     * pilot's client stops flying the airframe and the run becomes the server's to animate;
     * ignoring the echo outright would leave the drone hanging in the air until the blast. Taking
     * it as a plain {@code setPos} would work but would step at tick rate - see
     * {@link #CHARGE_ECHO_CHASE} - so the echo is stored and chased instead.
     */
    @Override
    public void lerpTo(double x, double y, double z, float yRot, float xRot, int steps) {
        if (this.level().isClientSide && this.getId() == DroneControlState.pilotedDroneId) {
            if (this.isCharging()) {
                this.chargeTarget = new Vec3(x, y, z);
                this.chargeTargetSet = true;
            }
            return;
        }
        super.lerpTo(x, y, z, yRot, xRot, steps);
    }

    @Override
    protected double getDefaultGravity() {
        return 0.0D;
    }

    @Override
    public void tick() {
        super.tick();
        if (this.level().isClientSide) {
            this.clientTick();
        } else {
            this.serverTick();
        }
    }

    // ------------------------------------------------------------------ client

    private void clientTick() {
        if (this.getId() == DroneControlState.pilotedDroneId) {
            this.setDeltaMovement(Vec3.ZERO);
            if (this.isCharging()) {
                this.chaseChargeEcho();
            } else {
                this.chargeTargetSet = false;
            }
        }
        if (this.hurtTime > 0) {
            this.hurtTime--;
        }
        this.tickBank();
    }

    /**
     * Closes most of the gap to the last position the server reported for the run.
     *
     * <p>Run from the entity's own tick, which is after {@code setOldPosAndRot} has copied the
     * previous position into {@code xo} - so the camera's {@code lerp(partialTick, xo, x)} has a
     * real pair of positions to work with and the run renders at frame rate.
     *
     * <p>The position only. The echo also carries a rotation, and it is deliberately ignored here:
     * the pilot aims this airframe, so the rotation is already being written at frame rate by the
     * controller, and chasing a rotation that is a tick old would fight it at exactly the moment
     * the pilot is trying to steer.
     */
    private void chaseChargeEcho() {
        if (!this.chargeTargetSet) {
            return;
        }
        this.setPos(
                Mth.lerp(CHARGE_ECHO_CHASE, this.getX(), this.chargeTarget.x),
                Mth.lerp(CHARGE_ECHO_CHASE, this.getY(), this.chargeTarget.y),
                Mth.lerp(CHARGE_ECHO_CHASE, this.getZ(), this.chargeTarget.z));
    }

    /**
     * Leans the airframe into its turns.
     *
     * <p>Every client runs this off the rotation stream it already receives, so the lean is
     * identical for the pilot and for bystanders without a single extra packet. Gating on
     * {@link #isPiloted()} keeps it flat while the drone is ballistic or parked.
     */
    private void tickBank() {
        float target = 0.0F;
        if (this.isPiloted()) {
            float yawRate = Mth.wrapDegrees(this.getYRot() - this.bankYaw);
            target = Mth.clamp(yawRate * BANK_GAIN, -MAX_BANK, MAX_BANK);
        }
        this.bankYaw = this.getYRot();
        this.bankO = this.bank;
        this.bank += (target - this.bank) * BANK_SMOOTHING;
    }

    // ------------------------------------------------------------------ server

    private void serverTick() {
        if (!(this.level() instanceof ServerLevel serverLevel)) {
            return;
        }

        if (this.hurtCooldown > 0) {
            this.hurtCooldown--;
        }
        if (this.chargeCooldown > 0) {
            this.chargeCooldown--;
        }

        // The operator is parked before anything else, an attack run included. Skipping this while
        // charging would leave the body free to walk off and, worse, to keep accumulating fall
        // distance - the pilot would take the drop they took off from the moment the link ends.
        ServerPlayer pilot = this.getPilot();
        if (pilot != null && !this.isPilotValid(pilot)) {
            this.disconnectPilot();
            pilot = null;
        }
        if (pilot != null) {
            this.holdPilot(pilot);
        }

        // A run owns the airframe outright. Nothing else may touch it until it lands.
        if (this.isCharging()) {
            this.tickCharge(serverLevel);
            return;
        }

        if (this.thrown && pilot == null) {
            this.tickThrown();
        } else {
            this.setDeltaMovement(Vec3.ZERO);
        }

        if (++this.scanTimer >= SCAN_INTERVAL) {
            this.scanTimer = 0;
            this.scanForTargets(serverLevel);
        }
        this.tickMarks(serverLevel);
        this.entityData.set(DATA_MARKS, this.marked.size());
    }

    private boolean isPilotValid(ServerPlayer pilot) {
        return pilot.isAlive()
                && !pilot.isRemoved()
                && !pilot.isSpectator()
                && pilot.level() == this.level()
                && pilot.distanceToSqr(this) <= LINK_RANGE * LINK_RANGE;
    }

    /**
     * Keeps the operator's body from carrying momentum or accumulating a fall while linked.
     *
     * <p>This mirrors what the client does to its own copy, and the fall distance is the part that
     * actually matters here. The client reports the body's position every tick while the link is
     * up, so the server replays the drop a tick at a time; each replay hands
     * {@code LivingEntity#checkFallDamage} one tick's worth of falling, and clearing it here means
     * that never adds up to enough to hurt. Without it the body would take the whole drop in one
     * lump on the tick the pilot dismounts.
     *
     * <p>The velocity is only ever trimmed sideways. Cancelling the whole vector would cancel
     * gravity along with it - gravity is worth just 0.078 blocks a tick - and the body would drift
     * down at walking pace instead of falling.
     */
    private void holdPilot(ServerPlayer pilot) {
        pilot.fallDistance = 0.0F;
        pilot.setSprinting(false);
        Vec3 motion = pilot.getDeltaMovement();
        pilot.setDeltaMovement(0.0D, motion.y, 0.0D);
    }

    public void disconnectPilot() {
        int id = this.getPilotId();
        if (id < 0) {
            return;
        }
        this.setPilotId(-1);
        if (this.level() instanceof ServerLevel serverLevel
                && serverLevel.getEntity(id) instanceof ServerPlayer player) {
            dev.watchcraft.watchcraft.network.ModNetwork.sendLinkState(player, -1, false);
        }
    }

    private void tickThrown() {
        this.throwTicks++;
        Vec3 motion = this.getDeltaMovement();
        if (motion.lengthSqr() > 1.0E-6D) {
            this.move(MoverType.SELF, motion);
            if (this.horizontalCollision || this.verticalCollision) {
                this.setDeltaMovement(motion.scale(0.25D));
            } else {
                this.setDeltaMovement(motion.scale(0.93D));
            }
            double horizontal = Math.sqrt(motion.x * motion.x + motion.z * motion.z);
            if (horizontal > 1.0E-3D) {
                this.setYRot((float) (Math.toDegrees(Math.atan2(-motion.x, motion.z))));
                this.setXRot((float) (Math.toDegrees(-Math.atan2(motion.y, horizontal))));
            }
        } else {
            this.setDeltaMovement(Vec3.ZERO);
        }
        if (this.throwTicks > THROW_TICKS) {
            this.thrown = false;
            this.setDeltaMovement(Vec3.ZERO);
        }
    }

    // ------------------------------------------------------------------ attack run

    /**
     * Arms the warhead and commits the airframe to a straight run.
     *
     * <p>Server authoritative on every count: the module has to actually be fitted, the caller has
     * to actually be the pilot, and the warhead has to be off cooldown. A client that asks nicely
     * without any of that gets nothing, so a run cannot be triggered by a drone that was never
     * fitted for it.
     *
     * <p>The direction is the crosshair, exactly, with no pitch floor: a level aim now flies level
     * rather than being forced into a stoop. The axis is then locked and published, and the pilot's
     * only remaining input is the cone in {@link #CHARGE_CONE} around it.
     *
     * @return true if the run started
     */
    public boolean startCharge(ServerPlayer pilot) {
        if (this.level().isClientSide || this.isRemoved()) {
            return false;
        }
        if (this.isCharging() || this.chargeCooldown > 0) {
            return false;
        }
        if (!this.hasModule(DroneModules.ATTACK) || !this.isPilotedBy(pilot)) {
            return false;
        }

        Vec3 direction = this.getViewVector(1.0F);
        if (direction.lengthSqr() < 1.0E-6D) {
            return false;
        }

        this.thrown = false;
        this.throwTicks = 0;
        this.chargeTicks = 0;
        this.chargeCooldown = CHARGE_COOLDOWN_TICKS;
        // Published before the flag, so the pilot's client can never see itself charging without
        // the axis to clamp against.
        this.entityData.set(DATA_CHARGE_YAW, this.getYRot());
        this.entityData.set(DATA_CHARGE_PITCH, this.getXRot());
        this.entityData.set(DATA_CHARGING, true);
        this.setDeltaMovement(direction.normalize().scale(CHARGE_SPEED));

        // Everyone nearby hears the motor spool up; the pilot hears it regardless of range,
        // because Level#playSound treats a Player argument as someone to exclude, not to notify.
        this.level().playSound(null, this.getX(), this.getY(), this.getZ(),
                SoundEvents.FIREWORK_ROCKET_LAUNCH, SoundSource.NEUTRAL, 1.6F, 0.7F);
        pilot.playNotifySound(SoundEvents.FIREWORK_ROCKET_LAUNCH, SoundSource.NEUTRAL, 1.0F, 0.7F);
        return true;
    }

    /** {@return the direction the current run is locked to} */
    public Vec3 chargeAxis() {
        return viewVector(this.entityData.get(DATA_CHARGE_YAW), this.entityData.get(DATA_CHARGE_PITCH));
    }

    /**
     * Bends the nose, but only inside the cone.
     *
     * <p>Called with the pilot's own reported rotation while a run is under way. The clamp is the
     * whole security story of this feature: a client cannot steer a run anywhere the cone does not
     * reach, whatever it puts in its packets, and the wide envelope
     * {@link #CHARGE_CONE_SLACK} means an honest client - which has already clamped itself to the
     * tighter {@link #CHARGE_CONE} - is never second-guessed.
     */
    public void steerCharge(ServerPlayer pilot, float yRot, float xRot) {
        if (!this.isCharging() || !this.isPilotedBy(pilot)) {
            return;
        }
        if (!Float.isFinite(yRot) || !Float.isFinite(xRot)) {
            return;
        }
        Vec3 clamped = clampToCone(this.chargeAxis(), viewVector(yRot, xRot), CHARGE_CONE_SLACK);
        this.setYRot(yawOf(clamped));
        this.setXRot(pitchOf(clamped));
    }

    /**
     * One tick of the run: sweep forward, and go off on the first thing the nose touches.
     *
     * <p>The entity check runs before the move and looks at everything the airframe is about to
     * sweep through, because a run at {@link #CHARGE_SPEED} covers well over a block a tick - a
     * check that only looked at the position after the move would happily tunnel straight through
     * a player standing between two ticks.
     */
    private void tickCharge(ServerLevel level) {
        this.chargeTicks++;
        if (this.chargeTicks > CHARGE_MAX_TICKS) {
            this.detonate(level, this.position());
            return;
        }

        // The nose is already inside the cone - steerCharge saw to that - but it is clamped again
        // here rather than trusted, so a tick that arrived with no packet at all still flies
        // somewhere legal.
        Vec3 direction = clampToCone(this.chargeAxis(), this.getViewVector(1.0F), CHARGE_CONE_SLACK);
        this.setDeltaMovement(direction.scale(CHARGE_SPEED));
        Vec3 motion = this.getDeltaMovement();

        AABB sweep = this.getBoundingBox().expandTowards(motion).inflate(0.3D);
        Entity struck = null;
        double closest = Double.MAX_VALUE;
        for (Entity candidate : level.getEntities(this, sweep, this::isChargeTarget)) {
            double distance = candidate.distanceToSqr(this);
            if (distance < closest) {
                closest = distance;
                struck = candidate;
            }
        }
        if (struck != null) {
            this.detonate(level, struck.getBoundingBox().getCenter());
            return;
        }

        this.move(MoverType.SELF, motion);
        // The nose is not re-pointed from the motion here. It used to be, back when the run was a
        // fixed stoop and the server was the only thing that knew which way it went; now the pilot
        // aims it, and overwriting the rotation would throw away the very input the steering
        // arrives through.
        if (this.horizontalCollision || this.verticalCollision) {
            this.detonate(level, this.position());
        }
    }

    /** Anything solid that is not the pilot. */
    private boolean isChargeTarget(Entity entity) {
        return entity != this
                && !entity.isRemoved()
                && !entity.isSpectator()
                && entity.isPickable()
                && entity.getId() != this.getPilotId();
    }

    /**
     * Goes off.
     *
     * <p>The damage is flat rather than distance scaled. Vanilla's calculator falls off with range,
     * which is right for a TNT crater and wrong here: a warhead that does twenty points at the
     * point of impact and four at the edge cannot be aimed at anything in particular. Overriding
     * {@code getEntityDamageAmount} to a constant is the only way to promise the same twenty to
     * whatever the nose touches, and it is still delivered as an explosion, so protection and
     * blast protection reduce it exactly as they would a creeper.
     *
     * <p>The crater is kept small by {@code shouldBlockExplode} rather than by shrinking the blast:
     * the radius still governs who is caught, only the block damage is clipped to the impact point.
     */
    private void detonate(ServerLevel level, Vec3 at) {
        this.entityData.set(DATA_CHARGING, false);
        this.setDeltaMovement(Vec3.ZERO);

        // Read the pilot before anything unwinds the link, so the blast is credited to them.
        ServerPlayer pilot = this.getPilot();
        DamageSource source = level.damageSources().explosion(this, pilot);
        ExplosionDamageCalculator calculator = new ExplosionDamageCalculator() {
            @Override
            public float getEntityDamageAmount(Explosion explosion, Entity entity) {
                return CHARGE_DAMAGE;
            }

            @Override
            public boolean shouldBlockExplode(Explosion explosion, BlockGetter reader, BlockPos pos,
                                              BlockState state, float power) {
                return pos.distToCenterSqr(at.x, at.y, at.z) <= CHARGE_CRATER * CHARGE_CRATER;
            }
        };

        // The airframe is the blast's own source, so it is excluded from the damage sweep as well
        // as latched out of hurt(); between the two, the drone cannot be killed twice.
        this.detonating = true;
        try {
            level.explode(this, source, calculator, at.x, at.y, at.z,
                    CHARGE_EXPLOSION_RADIUS, false, Level.ExplosionInteraction.MOB);
        } finally {
            this.detonating = false;
        }

        // Handles the unlink, the marks, the smoke and the wreck in one place.
        this.wreckDrone();
    }

    // ------------------------------------------------------------------ scanning

    private void scanForTargets(ServerLevel level) {
        Vec3 eye = this.getEyePosition();
        Vec3 look = this.getViewVector(1.0F);

        // A thrown drone gets the wide sweep; a piloted one keeps the tighter cone, because the
        // pilot is aiming and marking half the compass would read as noise rather than as a find.
        double fov = this.thrown ? THROW_SCAN_FOV : SCAN_FOV;
        double ping = this.thrown ? THROW_PING_RADIUS : PING_RADIUS;
        double cosHalfFov = Math.cos(Math.toRadians(fov * 0.5D));
        double pingSqr = ping * ping;

        AABB area = this.getBoundingBox().inflate(SCAN_RANGE);
        List<LivingEntity> candidates = level.getEntitiesOfClass(LivingEntity.class, area, this::isScannable);

        int raycasts = 0;
        for (LivingEntity candidate : candidates) {
            Vec3 centre = candidate.getBoundingBox().getCenter();
            Vec3 delta = centre.subtract(eye);
            double distance = delta.length();
            if (distance < 0.5D || distance > SCAN_RANGE) {
                continue;
            }
            // Within the ping radius the angle does not matter; past it the target has to be in
            // the cone.
            if (distance * distance > pingSqr
                    && delta.scale(1.0D / distance).dot(look) < cosHalfFov) {
                continue;
            }
            if (raycasts >= MAX_RAYCASTS_PER_SCAN) {
                break;
            }
            raycasts++;
            if (!this.canSee(level, eye, candidate)) {
                continue;
            }
            this.marked.put(candidate.getId(), GLOW_DURATION);
            candidate.setGlowingTag(true);
        }
    }

    private boolean isScannable(LivingEntity entity) {
        if (!entity.isAlive() || entity.isSpectator() || entity.isRemoved()) {
            return false;
        }
        if (entity.getId() == this.getPilotId()) {
            return false;
        }
        if (this.ownerUUID != null && entity instanceof Player player
                && this.ownerUUID.equals(player.getUUID())) {
            return false;
        }
        return true;
    }

    /**
     * {@return whether any part of the entity can be seen from the given point}
     *
     * <p>Nearest surface first, then the centre as a fallback. The first version cast a single ray
     * at the bounding box centre, which loses anything whose middle happens to sit behind a fence
     * post, a lip of ground, or the very block the target is standing on - that is, most of what a
     * drone flying over broken terrain is actually looking at.
     */
    private boolean canSee(Level level, Vec3 eye, LivingEntity entity) {
        AABB box = entity.getBoundingBox();
        Vec3 nearest = new Vec3(
                Mth.clamp(eye.x, box.minX, box.maxX),
                Mth.clamp(eye.y, box.minY, box.maxY),
                Mth.clamp(eye.z, box.minZ, box.maxZ));
        if (this.hasClearLine(level, eye, nearest)) {
            return true;
        }
        Vec3 centre = box.getCenter();
        return nearest.distanceToSqr(centre) > 1.0E-4D && this.hasClearLine(level, eye, centre);
    }

    private boolean hasClearLine(Level level, Vec3 from, Vec3 to) {
        ClipContext context = new ClipContext(from, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, this);
        BlockHitResult result = level.clip(context);
        return result.getType() == HitResult.Type.MISS;
    }

    private void tickMarks(ServerLevel level) {
        if (this.marked.isEmpty()) {
            return;
        }
        Iterator<Map.Entry<Integer, Integer>> iterator = this.marked.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<Integer, Integer> entry = iterator.next();
            int remaining = entry.getValue() - 1;
            if (remaining > 0) {
                entry.setValue(remaining);
                continue;
            }
            iterator.remove();
            if (level.getEntity(entry.getKey()) instanceof LivingEntity living
                    && !living.hasEffect(MobEffects.GLOWING)) {
                living.setGlowingTag(false);
            }
        }
    }

    // ------------------------------------------------------------------ interaction

    @Override
    public InteractionResult interact(Player player, InteractionHand hand) {
        if (this.level().isClientSide) {
            return InteractionResult.SUCCESS;
        }
        if (!this.isOwnedBy(player)) {
            return InteractionResult.PASS;
        }
        if (player.isShiftKeyDown()) {
            this.pickUp(player);
            return InteractionResult.CONSUME;
        }
        return InteractionResult.PASS;
    }

    private void pickUp(Player player) {
        this.disconnectPilot();
        this.clearMarks();
        // The loadout travels with the airframe: a fitted drone comes back fitted.
        ItemStack stack = DroneModules.droneStack(this.getModules());
        if (!player.getInventory().add(stack)) {
            player.drop(stack, false);
        }
        this.discard();
    }

    /** Folds this drone back into the owner's inventory from any distance. */
    public void recallTo(Player player) {
        this.pickUp(player);
    }

    private void clearMarks() {
        if (!(this.level() instanceof ServerLevel serverLevel)) {
            this.marked.clear();
            return;
        }
        for (Integer id : this.marked.keySet()) {
            if (serverLevel.getEntity(id) instanceof LivingEntity living
                    && !living.hasEffect(MobEffects.GLOWING)) {
                living.setGlowingTag(false);
            }
        }
        this.marked.clear();
    }

    @Override
    public void remove(RemovalReason reason) {
        if (!this.level().isClientSide && reason != RemovalReason.UNLOADED_TO_CHUNK) {
            this.disconnectPilot();
            this.clearMarks();
        }
        super.remove(reason);
    }

    /** Spawns a drone that flies out in front of the player, then hovers. */
    public static void throwFrom(Level level, Player player, ItemStack stack) {
        ReconDroneEntity drone = ModEntities.RECON_DRONE.get().create(level);
        if (drone == null) {
            return;
        }
        Vec3 look = player.getViewVector(1.0F);
        Vec3 spawn = player.getEyePosition().add(look.scale(0.9D)).subtract(0.0D, 0.2D, 0.0D);
        drone.moveTo(spawn.x, spawn.y, spawn.z, player.getYRot(), player.getXRot());
        drone.setOwner(player);
        drone.setModules(DroneModules.of(stack));
        drone.setDeltaMovement(look.scale(THROW_SPEED).add(0.0D, 0.06D, 0.0D));
        drone.markThrown();
        level.addFreshEntity(drone);
        if (!player.getAbilities().instabuild) {
            stack.shrink(1);
        }
    }

    /** Picks the drone a link request should attach to: the preferred one, else the nearest idle one. */
    @Nullable
    public static ReconDroneEntity findLinkable(ServerLevel level, Player player, int preferredId) {
        if (preferredId >= 0 && level.getEntity(preferredId) instanceof ReconDroneEntity drone
                && drone.isOwnedBy(player)) {
            return drone;
        }
        AABB area = player.getBoundingBox().inflate(LINK_RANGE);
        return level.getEntitiesOfClass(ReconDroneEntity.class, area,
                        drone -> drone.isOwnedBy(player) && !drone.isPiloted())
                .stream()
                .min(Comparator.comparingDouble(drone -> drone.distanceToSqr(player)))
                .orElse(null);
    }

    /**
     * The single drone this player currently has deployed, searched across every dimension.
     * Returns null on the client (no server to ask) and when the player has none out.
     */
    @Nullable
    public static ReconDroneEntity findDeployed(Player player) {
        MinecraftServer server = player.getServer();
        if (server == null) {
            return null;
        }
        for (ServerLevel level : server.getAllLevels()) {
            for (Entity entity : level.getAllEntities()) {
                if (entity instanceof ReconDroneEntity drone && drone.isOwnedBy(player)) {
                    return drone;
                }
            }
        }
        return null;
    }

    /** A player may only ever have {@value #MAX_DEPLOYED} drone out in the world at a time. */
    public static boolean hasDeployed(Player player) {
        return findDeployed(player) != null;
    }

    /** Used by the server to keep the drone out of the void. */
    public void clampToWorld(ServerLevel level) {
        double minY = level.getMinBuildHeight() + 0.05D;
        double maxY = level.getMaxBuildHeight() - 1.0D;
        if (this.getY() < minY || this.getY() > maxY) {
            this.setPos(this.getX(), Math.min(Math.max(this.getY(), minY), maxY), this.getZ());
        }
        BlockPos pos = this.blockPosition();
        if (!level.isLoaded(pos)) {
            this.setDeltaMovement(Vec3.ZERO);
        }
    }
}
