package com.apocscode.byteblock.entity;

import com.apocscode.byteblock.network.BluetoothNetwork;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.Container;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Base64;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Queue;
import java.util.Set;
import java.util.UUID;

/**
 * Flying Drone entity — a programmable flying device.
 * Can be given movement commands by a connected computer via Bluetooth.
 * Hovers in place when idle. Has a 9-slot inventory for item transport.
 *
 * Bluetooth protocol — any message on the drone's channel targeted at its UUID:
 *   "drone:waypoint:x:y:z"   — append waypoint
 *   "drone:home"             — clear waypoints and return to spawn
 *   "drone:clear"            — clear waypoints
 *   "drone:hover:true|false" — toggle hover
 *   "drone:refuel:<ticks>"   — add fuel
 */
public class DroneEntity extends PathfinderMob implements net.minecraft.world.MenuProvider {
    public static final int MAX_FUEL = 72000;
    private static final int HOVER_DRAIN_PERIOD = 20; // 1 fuel per second while hovering
    private static final int LOW_FUEL_THRESHOLD = 400; // ~20s — auto-return-home trigger

    private static final EntityDataAccessor<Boolean> DATA_CHARGING =
            SynchedEntityData.defineId(DroneEntity.class, EntityDataSerializers.BOOLEAN);
    private static final EntityDataAccessor<Integer> DATA_FUEL =
            SynchedEntityData.defineId(DroneEntity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Integer> DATA_HOME_X =
            SynchedEntityData.defineId(DroneEntity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Integer> DATA_HOME_Y =
            SynchedEntityData.defineId(DroneEntity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Integer> DATA_HOME_Z =
            SynchedEntityData.defineId(DroneEntity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<java.util.Optional<java.util.UUID>> DATA_DRONE_ID =
            SynchedEntityData.defineId(DroneEntity.class, EntityDataSerializers.OPTIONAL_UUID);
    /** Entity ID of the current laser target; -1 = no target. Synced to client for beam rendering. */
    private static final EntityDataAccessor<Integer> DATA_LASER_TARGET =
            SynchedEntityData.defineId(DroneEntity.class, EntityDataSerializers.INT);
    /** Bluetooth channel — synced to client so the drone GUI screen can display/change it. */
    private static final EntityDataAccessor<Integer> DATA_CHANNEL =
            SynchedEntityData.defineId(DroneEntity.class, EntityDataSerializers.INT);
    /** True while the drone is docked on a charge pad (even when fully charged). */
    private static final EntityDataAccessor<Boolean> DATA_DOCKED =
            SynchedEntityData.defineId(DroneEntity.class, EntityDataSerializers.BOOLEAN);

    private UUID ownerId = null;
    private UUID droneId;
    private UUID linkedComputerId = null;
    private int bluetoothChannel = 1;
    private final Queue<Vec3> waypoints = new LinkedList<>();
    private boolean hovering = true;
    private int fuelTicks = 6000; // 5 minutes of flight
    private int hoverDrainCounter = 0;
    /** Ticks remaining before A* re-plan is allowed again (prevents thrashing). */
    private int replanCooldown = 0;
    private final SimpleContainer inventory = new SimpleContainer(18);
    private ItemStack batteryStack = ItemStack.EMPTY;
    private ItemStack gpsToolStack = ItemStack.EMPTY;
    private com.apocscode.byteblock.entity.EntityPaint paint = new com.apocscode.byteblock.entity.EntityPaint();
    private BlockPos homePos = null;
    /** Manually assigned home charging pad — overrides homePos for low-fuel return. */
    private BlockPos chargePad = null;
    private boolean defender = false;  // attack nearby hostiles if true
    private int attackCooldown = 0;
    private int laserCooldown  = 0;
        /** Shield upgrade — absorbs damage until depleted, then recharges over time. */
        private float shieldHP = 0f;
        private static final float SHIELD_MAX  = 8f;   // HP of shield buffer
        private static final int   SHIELD_RECHARGE_TICKS = 20 * 30; // 30 s full recharge
    private String swarmGroup = "";    // if non-empty, drone only obeys "drone:swarm:<group>:..." on its channel
    private DroneVariant variant = DroneVariant.STANDARD;

    /** 4 upgrade card slots — Range, Speed, Inventory, etc. */
    private final SimpleContainer upgradeSlots = new SimpleContainer(4);

    // GPS-tool programming — persistent fleet tasks.
    private BlockPos routeSource = null;
    private BlockPos routeDest = null;
    private boolean routeActive = false;
    private int routePhase = 0; // 0 = heading to source, 1 = heading to dest
    private BlockPos patrolMin = null;
    private BlockPos patrolMax = null;
    private boolean patrolActive = false;
    private int patrolCornerIdx = 0;

    // Mission VM state (loaded from BT mission payloads).
    private final List<String> missionLines = new ArrayList<>();
    private final Map<String, Integer> missionLabels = new HashMap<>();
    private final java.util.Deque<MissionLoop> missionLoops = new ArrayDeque<>();
    private boolean missionActive = false;
    private int missionPc = 0;
    private int missionWaitTicks = 0;

    private record MissionLoop(int startPc, int remaining) {}

    public DroneEntity(EntityType<? extends DroneEntity> type, Level level) {
        super(type, level);
        this.setNoGravity(true);
        this.droneId = UUID.randomUUID();
    }

    @Override
    public boolean causeFallDamage(float fallDistance, float damageMultiplier,
            net.minecraft.world.damagesource.DamageSource source) {
        return false; // drones never take fall damage
    }

    public static AttributeSupplier.Builder createAttributes() {
        return PathfinderMob.createMobAttributes()
                .add(Attributes.MAX_HEALTH, 20.0)
                .add(Attributes.MOVEMENT_SPEED, 0.3)
                .add(Attributes.FLYING_SPEED, 0.4);
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        super.defineSynchedData(builder);
        builder.define(DATA_CHARGING, false);
        builder.define(DATA_FUEL, 0);
        builder.define(DATA_HOME_X, Integer.MIN_VALUE);
        builder.define(DATA_HOME_Y, Integer.MIN_VALUE);
        builder.define(DATA_HOME_Z, Integer.MIN_VALUE);
        builder.define(DATA_DRONE_ID, java.util.Optional.empty());
        builder.define(DATA_LASER_TARGET, -1);
        builder.define(DATA_CHANNEL, 1);
        builder.define(DATA_DOCKED, false);
    }

    private int chargingTicks = 0;
    private int dockedTicks = 0;
    private boolean wasChargingLastTick = false;
    public void markCharging() { this.chargingTicks = 30; this.dockedTicks = 60; }
    public boolean isCharging() { return entityData.get(DATA_CHARGING); }
    public boolean isDocked()   { return entityData.get(DATA_DOCKED); }

    @Override
    public void tick() {
        super.tick();
        if (level().isClientSide()) return;

        if (homePos == null) homePos = blockPosition();

        // Convert FE from a battery upgrade into fuel ticks (1 fuel tick per 10 FE).
        tickBatteryDrain();

        // Charging state countdown + sync flag, particles, sound.
        boolean charging = chargingTicks > 0;
        if (chargingTicks > 0) chargingTicks--;
        if (entityData.get(DATA_CHARGING) != charging) entityData.set(DATA_CHARGING, charging);
        if (charging && fuelTicks < MAX_FUEL && level() instanceof net.minecraft.server.level.ServerLevel sl) {
            if (!wasChargingLastTick) {
                level().playSound(null, blockPosition(),
                        net.minecraft.sounds.SoundEvents.BEACON_ACTIVATE,
                        net.minecraft.sounds.SoundSource.NEUTRAL, 0.4f, 1.7f);
            }
            if (sl.getGameTime() % 14 == 0) {
                level().playSound(null, blockPosition(),
                        net.minecraft.sounds.SoundEvents.AMETHYST_BLOCK_CHIME,
                        net.minecraft.sounds.SoundSource.NEUTRAL, 0.3f, 1.5f);
            }
            sl.sendParticles(net.minecraft.core.particles.ParticleTypes.HAPPY_VILLAGER,
                    getX(), getY() + 0.3, getZ(), 1, 0.3, 0.2, 0.3, 0.05);
        }
        wasChargingLastTick = charging;

        // Docked-on-pad state — refreshed by markCharging(), cleared when new waypoints added.
        if (dockedTicks > 0) dockedTicks--;
        boolean docked = dockedTicks > 0;
        if (entityData.get(DATA_DOCKED) != docked) entityData.set(DATA_DOCKED, docked);

        // Sync fuel, home position, and droneId to client every 10 ticks.
        if (level().getGameTime() % 10 == 0) {
            getEntityData().set(DATA_FUEL, fuelTicks);
            if (homePos != null) {
                getEntityData().set(DATA_HOME_X, homePos.getX());
                getEntityData().set(DATA_HOME_Y, homePos.getY());
                getEntityData().set(DATA_HOME_Z, homePos.getZ());
            }
            getEntityData().set(DATA_DRONE_ID, java.util.Optional.of(droneId));
        }

        // Drive mission VM while idle so scripts can enqueue waypoints/actions.
        tickMissionScript();

            // Solar upgrade — regen fuel when in direct sunlight during day
            if (hasSolarUpgrade() && level().isDay()
                    && level().canSeeSky(blockPosition())
                    && level().getGameTime() % 20 == 0) {
                fuelTicks = Math.min(fuelTicks + 10, MAX_FUEL);
            }

            // Shield upgrade — slowly recharge shield buffer
            if (hasShieldUpgrade() && shieldHP < SHIELD_MAX) {
                shieldHP = Math.min(shieldHP + (SHIELD_MAX / SHIELD_RECHARGE_TICKS), SHIELD_MAX);
            }

        // Auto-return when fuel is low and idle — prefer assigned charge pad over home pos
        if (fuelTicks > 0 && fuelTicks < LOW_FUEL_THRESHOLD && waypoints.isEmpty() && !missionActive) {
            BlockPos target = chargePad != null ? chargePad : homePos;
            if (target != null) {
                Vec3 dest = new Vec3(target.getX() + 0.5, target.getY() + 1, target.getZ() + 0.5);
                if (position().distanceTo(dest) > 1.5) {
                    navigateTo(dest); // A*-plan a clear path home
                }
            }
        }

        // Hover drain — once per second rather than every tick
        if (fuelTicks > 0 && hovering && waypoints.isEmpty()) {
            setDeltaMovement(Vec3.ZERO);
            if (++hoverDrainCounter >= HOVER_DRAIN_PERIOD) {
                hoverDrainCounter = 0;
                fuelTicks--;
            }
        }

        // Process waypoints
        if (replanCooldown > 0) replanCooldown--;
        if (!waypoints.isEmpty() && fuelTicks > 0) {
            Vec3 target = waypoints.peek();
            Vec3 current = position();
            double dist = current.distanceTo(target);
            if (dist < 0.5) {
                waypoints.poll();
            } else {
                // Dynamic obstacle check: if the block directly ahead is now solid and
                // we're not in a cooldown, discard the stale planned path and re-plan.
                Vec3 toTarget = target.subtract(current);
                double hLen = Math.sqrt(toTarget.x * toTarget.x + toTarget.z * toTarget.z);
                if (hLen > 0.1 && replanCooldown == 0) {
                    double hx = toTarget.x / hLen, hz = toTarget.z / hLen;
                    BlockPos probe = BlockPos.containing(current.x + hx, current.y + 0.5, current.z + hz);
                    if (!isPassable(probe)) {
                        // New obstacle detected mid-flight — re-plan to the final waypoint.
                        // Drain remaining waypoints to find the real destination.
                        Vec3 finalTarget = target;
                        for (Vec3 wp : waypoints) finalTarget = wp;
                        replanCooldown = 40; // wait 2 seconds before re-planning again
                        navigateTo(finalTarget);
                        target = waypoints.isEmpty() ? target : waypoints.peek();
                    }
                }
                setDeltaMovement(computeFlightMove(current, target));
                fuelTicks--;
            }
        }

        // GPS programmed tasks — refill waypoints when the queue runs dry.
        if (waypoints.isEmpty() && fuelTicks > 0) {
            if (routeActive && routeSource != null && routeDest != null) {
                if (routePhase == 0) {
                    // Head to source, then pickup.
                    Vec3 src = new Vec3(routeSource.getX() + 0.5, routeSource.getY() + 1.5, routeSource.getZ() + 0.5);
                    if (position().distanceToSqr(src) < 4.0) {
                        pickupFromContainer(routeSource, 64);
                        routePhase = 1;
                        navigateTo(new Vec3(routeDest.getX() + 0.5, routeDest.getY() + 1.5, routeDest.getZ() + 0.5));
                    } else {
                        navigateTo(src);
                    }
                } else {
                    Vec3 dst = new Vec3(routeDest.getX() + 0.5, routeDest.getY() + 1.5, routeDest.getZ() + 0.5);
                    if (position().distanceToSqr(dst) < 4.0) {
                        dropIntoContainer(routeDest, 64);
                        routePhase = 0;
                        navigateTo(new Vec3(routeSource.getX() + 0.5, routeSource.getY() + 1.5, routeSource.getZ() + 0.5));
                    } else {
                        navigateTo(dst);
                    }
                }
            } else if (patrolActive && patrolMin != null && patrolMax != null) {
                // 4-corner orbit at patrolMax.y + 1
                int minX = Math.min(patrolMin.getX(), patrolMax.getX());
                int maxX = Math.max(patrolMin.getX(), patrolMax.getX());
                int minZ = Math.min(patrolMin.getZ(), patrolMax.getZ());
                int maxZ = Math.max(patrolMin.getZ(), patrolMax.getZ());
                int y = Math.max(patrolMin.getY(), patrolMax.getY()) + 1;
                Vec3 corner = switch (patrolCornerIdx % 4) {
                    case 0 -> new Vec3(minX + 0.5, y + 0.5, minZ + 0.5);
                    case 1 -> new Vec3(maxX + 0.5, y + 0.5, minZ + 0.5);
                    case 2 -> new Vec3(maxX + 0.5, y + 0.5, maxZ + 0.5);
                    default -> new Vec3(minX + 0.5, y + 0.5, maxZ + 0.5);
                };
                addWaypoint(corner);
                patrolCornerIdx = (patrolCornerIdx + 1) % 4;
            }
        }

        // Register on Bluetooth under our own UUID and drain the inbox
        BluetoothNetwork.register(level(), droneId, blockPosition(), bluetoothChannel,
                BluetoothNetwork.DeviceType.DRONE);
        BluetoothNetwork.Message msg;
        while ((msg = BluetoothNetwork.receive(droneId)) != null) {
            handleBluetoothMessage(msg.content());
        }

        // Defender behavior — attack nearest hostile mob every 2s if armed.
        // Variant determines damage + aggro radius (DEFENDER is the only one with real combat).
        if (defender && variant.attackDamage > 0) {
            if (attackCooldown > 0) attackCooldown--;
            if (attackCooldown <= 0) {
                LivingEntity target = findNearestHostile(variant.aggroRadius);
                if (target != null) {
                    Vec3 dir = target.position().subtract(position()).normalize().scale(0.3 * variant.speedMul);
                    setDeltaMovement(dir);
                    if (position().distanceTo(target.position()) < 2.0) {
                        target.hurt(damageSources().mobAttack(this), variant.attackDamage);
                        attackCooldown = 40;
                    }
                }
            }
        }

        // Laser upgrade — ranged attack, fires every 0.5 s at nearest hostile within 16 blocks.
        if (!level().isClientSide()) {
            if (hasLaserUpgrade()) {
                if (laserCooldown > 0) laserCooldown--;
                LivingEntity laserTarget = findNearestHostile(16.0);
                int newId = laserTarget != null ? laserTarget.getId() : -1;
                if (getEntityData().get(DATA_LASER_TARGET) != newId)
                    getEntityData().set(DATA_LASER_TARGET, newId);
                if (laserTarget != null && laserCooldown <= 0) {
                    laserTarget.hurt(damageSources().mobAttack(this), 6.0f);
                    laserCooldown = 10;
                }
            } else if (getEntityData().get(DATA_LASER_TARGET) != -1) {
                getEntityData().set(DATA_LASER_TARGET, -1);
            }
        }
    }

    private static final double FLIGHT_SPEED     = 0.2;
    private static final double VERT_SPEED       = 0.15;
    private static final double CRUISE_CLEARANCE = 5.0;  // fallback climb-first clearance
    private static final double CLIMB_FIRST_DIST = 5.0;  // fallback minimum h-distance
    private static final double SEPARATION_RADIUS = 3.5; // drone-drone repulsion radius
    private static final int    ASTAR_MAX_NODES  = 1500; // A* node budget per search
    private static final int    ASTAR_MAX_DIST   = 128;  // Manhattan-block cap before fallback

    /** Last explicitly planned destination — used to chain A* waypoint calls. */
    private Vec3 lastPlannedPos = null;

    /**
     * Steers the drone one tick toward {@code target}. A* pre-planning means the
     * path should be obstacle-free; this method just provides smooth movement
     * plus a 1-block safety probe to handle any edge cases.
     */
    private Vec3 computeFlightMove(Vec3 from, Vec3 target) {
        Vec3 toTarget = target.subtract(from);
        double hLen = Math.sqrt(toTarget.x * toTarget.x + toTarget.z * toTarget.z);
        double dy   = toTarget.y;
        double hx   = hLen > 0.001 ? toTarget.x / hLen : 0;
        double hz   = hLen > 0.001 ? toTarget.z / hLen : 0;

        double speedMult  = getSpeedMultiplier();
        double flightSpd  = FLIGHT_SPEED * speedMult;
        double vertSpd    = VERT_SPEED   * speedMult;

        Vec3 sep = computeDroneSeparation(from);

        // Prioritise vertical if we still need to climb significantly.
        if (dy > vertSpd + 0.1) {
            double hFrac = Math.max(0.0, 1.0 - dy / CRUISE_CLEARANCE);
            double hStep = Math.min(flightSpd * hFrac, hLen);
            return new Vec3(hx * hStep + sep.x, vertSpd, hz * hStep + sep.z);
        }

        double hStep = Math.min(flightSpd, hLen);
        double moveY = Math.max(-vertSpd, Math.min(vertSpd, dy));

        // Safety probe: if the very next block is occupied, force a climb.
        if (hLen > 0.1) {
            double px = from.x + hx, pz = from.z + hz;
            if (!isPassable(BlockPos.containing(px, from.y + 0.5, pz))) {
                moveY = vertSpd;
                hStep *= 0.2;
            }
        }

        return new Vec3(hx * hStep + sep.x, moveY, hz * hStep + sep.z);
    }

    // -------------------------------------------------------------------------
    // Navigation — A* pathfinding
    // -------------------------------------------------------------------------

    /**
     * Clears the waypoint queue and fills it with an A*-planned path to {@code destVec}.
     * Falls back to climb-first direct flight if the target is out of A* range or
     * the search budget is exhausted.
     */
    private void navigateTo(Vec3 destVec) {
        waypoints.clear();
        lastPlannedPos = null;
        dockedTicks = 0;
        appendNavigateTo(destVec);
    }

    /**
     * Appends an A*-planned path to {@code destVec} without clearing existing waypoints.
     * Chains from the last planned position so that sequential calls form a coherent route.
     */
    private void appendNavigateTo(Vec3 destVec) {
        dockedTicks = 0;
        Vec3 from = (lastPlannedPos != null && !waypoints.isEmpty()) ? lastPlannedPos : position();
        BlockPos startBlock = BlockPos.containing(from.x, from.y, from.z);
        BlockPos goalBlock  = BlockPos.containing(destVec.x, destVec.y, destVec.z);

        List<Vec3> path = findPath(startBlock, goalBlock, destVec);
        if (path != null && !path.isEmpty()) {
            for (Vec3 wp : path) {
                if (waypoints.size() < 128) waypoints.add(wp);
            }
        } else {
            // Fallback: climb-first then direct.
            addWithClimbFirst(from, destVec);
        }
        lastPlannedPos = destVec;
    }

    /** Fallback when A* cannot find a path: climb above terrain, cruise, then descend. */
    private void addWithClimbFirst(Vec3 from, Vec3 dest) {
        double hDist = Math.sqrt((dest.x - from.x) * (dest.x - from.x)
                               + (dest.z - from.z) * (dest.z - from.z));
        if (hDist > CLIMB_FIRST_DIST) {
            double cruiseY = Math.max(from.y, dest.y) + CRUISE_CLEARANCE;
            if (waypoints.size() < 128) waypoints.add(new Vec3(from.x, cruiseY, from.z));
            if (waypoints.size() < 128) waypoints.add(new Vec3(dest.x, cruiseY, dest.z));
        }
        if (waypoints.size() < 128) waypoints.add(dest);
    }

    /**
     * A* search through 3D block space. Explores 26-directional neighbours and
     * applies string-pull smoothing to the raw path before returning.
     *
     * @return smoothed Vec3 waypoint list, or {@code null} if no path found
     *         within the node/distance budget.
     */
    private List<Vec3> findPath(BlockPos start, BlockPos goal, Vec3 preciseGoal) {
        int md = Math.abs(goal.getX() - start.getX())
               + Math.abs(goal.getY() - start.getY())
               + Math.abs(goal.getZ() - start.getZ());
        if (md > ASTAR_MAX_DIST) return null;
        if (md <= 2)             return List.of(preciseGoal);

        Map<BlockPos, Float>    gScore   = new HashMap<>();
        Map<BlockPos, BlockPos> cameFrom = new HashMap<>();
        Set<BlockPos>           closed   = new HashSet<>();
        PriorityQueue<BlockPos> open     = new PriorityQueue<>(
                Comparator.comparingDouble(p ->
                        gScore.getOrDefault(p, Float.MAX_VALUE) + astarH(p, goal)));

        gScore.put(start, 0f);
        open.add(start);

        while (!open.isEmpty() && gScore.size() < ASTAR_MAX_NODES) {
            BlockPos cur = open.poll();
            if (closed.contains(cur)) continue;
            closed.add(cur);

            if (cur.distSqr(goal) <= 2) {
                return buildSmoothedPath(cameFrom, cur, preciseGoal);
            }

            float g = gScore.get(cur);
            for (int dx = -1; dx <= 1; dx++) {
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        if (dx == 0 && dy == 0 && dz == 0) continue;
                        BlockPos nb = cur.offset(dx, dy, dz);
                        if (closed.contains(nb) || !isPassable(nb)) continue;
                        float step = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
                        if (dy < 0) step += 0.5f; // slight penalty for descending
                        float gNew = g + step;
                        if (gNew < gScore.getOrDefault(nb, Float.MAX_VALUE)) {
                            gScore.put(nb, gNew);
                            cameFrom.put(nb, cur);
                            open.add(nb); // lazy duplicate; closed set handles it
                        }
                    }
                }
            }
        }
        return null; // budget exhausted
    }

    private float astarH(BlockPos a, BlockPos b) {
        int dx = a.getX() - b.getX(), dy = a.getY() - b.getY(), dz = a.getZ() - b.getZ();
        return (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    /**
     * Returns {@code true} when a drone body (pos + pos.above) can fit at {@code pos}:
     * both blocks must have empty collision shapes.
     */
    private boolean isPassable(BlockPos pos) {
        BlockState bs    = level().getBlockState(pos);
        BlockState bsUp  = level().getBlockState(pos.above());
        // Reject liquids (water / lava) — they have empty collision shapes but are fatal.
        if (!bs.getFluidState().isEmpty() || !bsUp.getFluidState().isEmpty()) return false;
        // Reject fire and soul fire.
        if (bs.is(BlockTags.FIRE) || bsUp.is(BlockTags.FIRE)) return false;
        // Standard solid-block collision check.
        return bs.getCollisionShape(level(), pos).isEmpty()
            && bsUp.getCollisionShape(level(), pos.above()).isEmpty();
    }

    /** Traces back {@code cameFrom}, reverses, then string-pulls the result. */
    private List<Vec3> buildSmoothedPath(Map<BlockPos, BlockPos> cameFrom,
                                         BlockPos last, Vec3 preciseGoal) {
        List<Vec3> path = new ArrayList<>();
        BlockPos cur = last;
        while (cameFrom.containsKey(cur)) {
            path.add(Vec3.atCenterOf(cur));
            cur = cameFrom.get(cur);
        }
        Collections.reverse(path);
        if (path.isEmpty()) { path.add(preciseGoal); return path; }
        path.set(path.size() - 1, preciseGoal); // snap to exact destination

        // String-pull: skip nodes where a straight line-of-sight already exists.
        if (path.size() <= 2) return path;
        List<Vec3> smooth = new ArrayList<>();
        smooth.add(path.get(0));
        int i = 0;
        while (i < path.size() - 1) {
            int j = path.size() - 1;
            while (j > i + 1 && !hasLineOfSight(path.get(i), path.get(j))) j--;
            smooth.add(path.get(j));
            i = j;
        }
        return smooth;
    }

    /** Checks that every block along the line from {@code a} to {@code b} is passable. */
    private boolean hasLineOfSight(Vec3 a, Vec3 b) {
        int steps = (int) Math.ceil(a.distanceTo(b)) + 1;
        for (int t = 1; t <= steps; t++) {
            double f = (double) t / steps;
            if (!isPassable(BlockPos.containing(
                    a.x + (b.x - a.x) * f,
                    a.y + (b.y - a.y) * f,
                    a.z + (b.z - a.z) * f))) return false;
        }
        return true;
    }

    /**
     * Returns a small repulsion vector away from any other DroneEntity within
     * SEPARATION_RADIUS blocks, so drones don’t collide when flying in a group.
     */
    private Vec3 computeDroneSeparation(Vec3 from) {
        double rx = 0, ry = 0, rz = 0;
        for (DroneEntity other : level().getEntitiesOfClass(DroneEntity.class,
                getBoundingBox().inflate(SEPARATION_RADIUS))) {
            if (other == this) continue;
            Vec3 away = from.subtract(other.position());
            double dist = away.length();
            if (dist > 0.01 && dist < SEPARATION_RADIUS) {
                double strength = (SEPARATION_RADIUS - dist) / SEPARATION_RADIUS * 0.08;
                rx += (away.x / dist) * strength;
                ry += (away.y / dist) * strength;
                rz += (away.z / dist) * strength;
            }
        }
        return new Vec3(rx, ry, rz);
    }


    private LivingEntity findNearestHostile(double radius) {
        AABB area = getBoundingBox().inflate(radius);
        LivingEntity best = null;
        double bestDist = Double.MAX_VALUE;
        for (Monster m : level().getEntitiesOfClass(Monster.class, area)) {
            if (!m.isAlive()) continue;
            double d = m.position().distanceToSqr(position());
            if (d < bestDist) {
                bestDist = d;
                best = m;
            }
        }
        return best;
    }

    /** Returns true if any installed upgrade card is a laser card. */
    public boolean hasLaserUpgrade() {
        for (int i = 0; i < upgradeSlots.getContainerSize(); i++) {
            net.minecraft.world.item.ItemStack s = upgradeSlots.getItem(i);
            if (s.getItem() instanceof com.apocscode.byteblock.item.UpgradeCard card
                    && card.getUpgradeType().isLaserCard()) return true;
        }
        return false;
    }

    /** Entity ID of the current laser target synced to the client; -1 = none. */
    public int getLaserTargetId() { return getEntityData().get(DATA_LASER_TARGET); }

    private void handleBluetoothMessage(String raw) {
        if (raw == null || !raw.startsWith("drone:")) return;
        String[] parts = raw.split(":");
        if (parts.length < 2) return;

        // Swarm routing: "drone:swarm:<group>:<real-cmd>:..." is only obeyed
        // if the drone's swarmGroup matches. Non-swarm messages are always
        // obeyed (unless a swarm group is set and the message isn't targeted).
        String cmd;
        String[] effectiveParts;
        if ("swarm".equals(parts[1])) {
            if (parts.length < 4) return;
            if (!swarmGroup.equals(parts[2])) return;
            cmd = parts[3];
            // Strip the "drone:swarm:<group>:" prefix: remap parts[0..] to ["drone", cmd, ...tail]
            String[] tail = new String[parts.length - 4];
            System.arraycopy(parts, 4, tail, 0, tail.length);
            effectiveParts = new String[tail.length + 2];
            effectiveParts[0] = "drone";
            effectiveParts[1] = cmd;
            System.arraycopy(tail, 0, effectiveParts, 2, tail.length);
        } else {
            cmd = parts[1];
            effectiveParts = parts;
        }

        try {
            switch (cmd) {
                case "waypoint" -> {
                    if (effectiveParts.length >= 5) {
                        appendNavigateTo(new Vec3(
                                Double.parseDouble(effectiveParts[2]),
                                Double.parseDouble(effectiveParts[3]),
                                Double.parseDouble(effectiveParts[4])));
                    }
                }
                case "home" -> {
                    if (homePos != null) {
                        navigateTo(new Vec3(homePos.getX() + 0.5, homePos.getY() + 1, homePos.getZ() + 0.5));
                    } else {
                        waypoints.clear();
                    }
                }
                case "setHome" -> {
                    if (effectiveParts.length >= 5) {
                        setHomePos(new BlockPos(
                                Integer.parseInt(effectiveParts[2]),
                                Integer.parseInt(effectiveParts[3]),
                                Integer.parseInt(effectiveParts[4])));
                    } else {
                        setHomePos(blockPosition());
                    }
                }
                case "clear" -> { waypoints.clear(); lastPlannedPos = null; }
                case "hover" -> {
                    if (effectiveParts.length >= 3) hovering = Boolean.parseBoolean(effectiveParts[2]);
                }
                case "refuel" -> {
                    if (effectiveParts.length >= 3) addFuel(Integer.parseInt(effectiveParts[2]));
                }
                case "pickup" -> {
                    if (effectiveParts.length >= 5) {
                        BlockPos target = new BlockPos(
                                Integer.parseInt(effectiveParts[2]),
                                Integer.parseInt(effectiveParts[3]),
                                Integer.parseInt(effectiveParts[4]));
                        int max = effectiveParts.length >= 6 ? Integer.parseInt(effectiveParts[5]) : 64;
                        pickupFromContainer(target, max);
                    }
                }
                case "drop" -> {
                    if (effectiveParts.length >= 5) {
                        BlockPos target = new BlockPos(
                                Integer.parseInt(effectiveParts[2]),
                                Integer.parseInt(effectiveParts[3]),
                                Integer.parseInt(effectiveParts[4]));
                        int max = effectiveParts.length >= 6 ? Integer.parseInt(effectiveParts[5]) : 64;
                        dropIntoContainer(target, max);
                    }
                }
                case "defender" -> {
                    if (effectiveParts.length >= 3) defender = Boolean.parseBoolean(effectiveParts[2]);
                }
                case "group" -> {
                    if (effectiveParts.length >= 3) swarmGroup = effectiveParts[2];
                    else swarmGroup = "";
                }
                case "variant" -> {
                    if (effectiveParts.length >= 3) setVariantByName(effectiveParts[2]);
                }
                case "scan" -> {
                    if (level() != null) {
                        int radius = effectiveParts.length >= 3
                                ? Math.max(1, Math.min(Integer.parseInt(effectiveParts[2]), 16))
                                : 8;
                        broadcastScanResults(radius);
                    }
                }
                case "route" -> {
                    if (effectiveParts.length >= 8) {
                        routeSource = new BlockPos(
                                Integer.parseInt(effectiveParts[2]),
                                Integer.parseInt(effectiveParts[3]),
                                Integer.parseInt(effectiveParts[4]));
                        routeDest = new BlockPos(
                                Integer.parseInt(effectiveParts[5]),
                                Integer.parseInt(effectiveParts[6]),
                                Integer.parseInt(effectiveParts[7]));
                        routeActive = true;
                        routePhase = 0;
                        patrolActive = false;
                        waypoints.clear();
                    }
                }
                case "patrol" -> {
                    if (effectiveParts.length >= 8) {
                        patrolMin = new BlockPos(
                                Integer.parseInt(effectiveParts[2]),
                                Integer.parseInt(effectiveParts[3]),
                                Integer.parseInt(effectiveParts[4]));
                        patrolMax = new BlockPos(
                                Integer.parseInt(effectiveParts[5]),
                                Integer.parseInt(effectiveParts[6]),
                                Integer.parseInt(effectiveParts[7]));
                        patrolActive = true;
                        patrolCornerIdx = 0;
                        routeActive = false;
                        waypoints.clear();
                    }
                }
                case "path" -> {
                    // drone:path:x1:y1:z1:x2:y2:z2:...
                    waypoints.clear();
                    routeActive = false;
                    patrolActive = false;
                    int i = 2;
                    while (i + 2 < effectiveParts.length) {
                        addWaypoint(new Vec3(
                                Double.parseDouble(effectiveParts[i]) + 0.5,
                                Double.parseDouble(effectiveParts[i + 1]) + 1.5,
                                Double.parseDouble(effectiveParts[i + 2]) + 0.5));
                        i += 3;
                    }
                }
                case "mission" -> {
                    if (effectiveParts.length >= 3) {
                        String decoded = new String(Base64.getDecoder().decode(effectiveParts[2]), StandardCharsets.UTF_8);
                        loadMission(decoded);
                        sendPuzzleAck(effectiveParts, "ok");
                    }
                }
                case "stop" -> {
                    waypoints.clear();
                    routeActive = false;
                    patrolActive = false;
                    missionActive = false;
                }
                default -> { /* unknown */ }
            }
        } catch (NumberFormatException ignored) {
            // Malformed command — ignore silently.
            sendPuzzleAck(effectiveParts, "bad_number");
        } catch (IllegalArgumentException ignored) {
            // Malformed base64/UUID payload.
            sendPuzzleAck(effectiveParts, "bad_payload");
        }
    }

    private void sendPuzzleAck(String[] parts, String status) {
        if (parts == null || parts.length < 5 || level() == null) return;
        try {
            UUID target = UUID.fromString(parts[3]);
            String token = parts[4];
            BluetoothNetwork.send(level(), blockPosition(), droneId, target, bluetoothChannel,
                    "puzzle:ack:drone:" + token + ":" + status);
        } catch (IllegalArgumentException ignored) {
            // Invalid sender UUID in payload.
        }
    }

    private void loadMission(String decoded) {
        missionLines.clear();
        missionLabels.clear();
        missionLoops.clear();
        missionWaitTicks = 0;
        missionPc = 0;

        for (String raw : decoded.split("\\R")) {
            String line = raw == null ? "" : raw.trim();
            if (line.isEmpty()) continue;
            if (!line.startsWith("drone:")) continue;
            missionLines.add(line);
        }
        for (int i = 0; i < missionLines.size(); i++) {
            String[] p = missionLines.get(i).split(":");
            if (p.length >= 3 && "label".equals(p[1])) {
                missionLabels.put(p[2], i);
            }
        }

        // Mission replaces any running route/patrol queue.
        waypoints.clear();
        routeActive = false;
        patrolActive = false;
        missionActive = !missionLines.isEmpty();
    }

    private void tickMissionScript() {
        if (!missionActive) return;
        if (missionWaitTicks > 0) {
            missionWaitTicks--;
            return;
        }
        if (!waypoints.isEmpty()) return;

        int budget = 8; // avoid infinite loop lockups in malformed scripts
        while (missionActive && missionWaitTicks == 0 && waypoints.isEmpty() && budget-- > 0) {
            if (missionPc < 0 || missionPc >= missionLines.size()) {
                missionActive = false;
                return;
            }

            String line = missionLines.get(missionPc);
            String[] p = line.split(":");
            if (p.length < 2) {
                missionPc++;
                continue;
            }

            String cmd = p[1];
            switch (cmd) {
                case "wait" -> {
                    int ticks = (p.length >= 3) ? parseIntSafe(p[2], 1) : 1;
                    missionWaitTicks = Math.max(1, ticks);
                    missionPc++;
                }
                case "repeat" -> {
                    int count = (p.length >= 3) ? parseIntSafe(p[2], 0) : 0;
                    if (count <= 0) {
                        int end = findMatchingEndRepeat(missionPc + 1);
                        missionPc = end >= 0 ? end + 1 : missionPc + 1;
                    } else {
                        missionLoops.push(new MissionLoop(missionPc + 1, count));
                        missionPc++;
                    }
                }
                case "end_repeat" -> {
                    if (missionLoops.isEmpty()) {
                        missionPc++;
                    } else {
                        MissionLoop top = missionLoops.peek();
                        if (top.remaining() > 1) {
                            missionLoops.pop();
                            missionLoops.push(new MissionLoop(top.startPc(), top.remaining() - 1));
                            missionPc = top.startPc();
                        } else {
                            missionLoops.pop();
                            missionPc++;
                        }
                    }
                }
                case "if_fuel_gt" -> {
                    int need = (p.length >= 3) ? parseIntSafe(p[2], 0) : 0;
                    if (fuelTicks > need) {
                        missionPc++;
                    } else {
                        int jump = findElseOrEndIf(missionPc + 1);
                        missionPc = jump >= 0 ? jump + 1 : missionPc + 1;
                    }
                }
                case "else" -> {
                    int endIf = findEndIf(missionPc + 1);
                    missionPc = endIf >= 0 ? endIf + 1 : missionPc + 1;
                }
                case "end_if", "label" -> missionPc++;
                case "jump" -> {
                    if (p.length >= 3 && missionLabels.containsKey(p[2])) {
                        missionPc = missionLabels.get(p[2]) + 1;
                    } else {
                        missionPc++;
                    }
                }
                case "stop" -> {
                    waypoints.clear();
                    routeActive = false;
                    patrolActive = false;
                    missionActive = false;
                }
                default -> {
                    handleBluetoothMessage(line);
                    missionPc++;
                }
            }
        }
    }

    private int findMatchingEndRepeat(int from) {
        int depth = 0;
        for (int i = from; i < missionLines.size(); i++) {
            String[] p = missionLines.get(i).split(":");
            if (p.length < 2) continue;
            if ("repeat".equals(p[1])) depth++;
            else if ("end_repeat".equals(p[1])) {
                if (depth == 0) return i;
                depth--;
            }
        }
        return -1;
    }

    private int findElseOrEndIf(int from) {
        int depth = 0;
        for (int i = from; i < missionLines.size(); i++) {
            String[] p = missionLines.get(i).split(":");
            if (p.length < 2) continue;
            if ("if_fuel_gt".equals(p[1])) depth++;
            else if ("end_if".equals(p[1])) {
                if (depth == 0) return i;
                depth--;
            } else if ("else".equals(p[1]) && depth == 0) {
                return i;
            }
        }
        return -1;
    }

    private int findEndIf(int from) {
        int depth = 0;
        for (int i = from; i < missionLines.size(); i++) {
            String[] p = missionLines.get(i).split(":");
            if (p.length < 2) continue;
            if ("if_fuel_gt".equals(p[1])) depth++;
            else if ("end_if".equals(p[1])) {
                if (depth == 0) return i;
                depth--;
            }
        }
        return -1;
    }

    private int parseIntSafe(String s, int fallback) {
        try {
            return Integer.parseInt(s.trim());
        } catch (Exception ignored) {
            return fallback;
        }
    }

    /**
     * Run an entity scan from our current position and broadcast the results on
     * our Bluetooth channel as one BT message per entity, plus a final summary.
     * Controllers listen via bluetooth.receive() and filter on the "drone:scanresult:" prefix.
     * Format:  drone:scanresult:<droneUuid>:<type>:<x>:<y>:<z>:<health>:<isPlayer>:<name>
     * Followed by: drone:scandone:<droneUuid>:<count>
     */
    private void broadcastScanResults(int radius) {
        Level lvl = level();
        if (lvl == null) return;
        com.apocscode.byteblock.scanner.WorldScanData data =
                new com.apocscode.byteblock.scanner.WorldScanData();
        data.scanEntities(lvl, blockPosition(), radius);
        int count = 0;
        for (com.apocscode.byteblock.scanner.WorldScanData.EntitySnapshot e : data.getEntities()) {
            String msg = "drone:scanresult:" + droneId + ":"
                    + e.type() + ":" + e.x() + ":" + e.y() + ":" + e.z()
                    + ":" + e.health() + ":" + e.isPlayer()
                    + ":" + (e.name() == null ? "" : e.name().replace(":", " "));
            BluetoothNetwork.broadcast(lvl, blockPosition(), bluetoothChannel, msg);
            count++;
        }
        BluetoothNetwork.broadcast(lvl, blockPosition(), bluetoothChannel,
                "drone:scandone:" + droneId + ":" + count);
    }

    /** Apply a variant by name (case-insensitive). Unknown names fall back to STANDARD. */
    public void setVariantByName(String name) {
        try {
            this.variant = DroneVariant.valueOf(name.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            this.variant = DroneVariant.STANDARD;
        }
    }

    public DroneVariant getVariant() { return variant; }
    public void setVariant(DroneVariant v) { this.variant = v == null ? DroneVariant.STANDARD : v; }

    /** Drone variants — apply stat multipliers to flight speed, fuel drain and combat. */
    public enum DroneVariant {
        STANDARD(1.0f, 1.0f, 4.0f, 5.0),   // speed, fuelDrain, atkDmg, aggroRadius
        CARGO(0.7f, 1.5f, 0.0f, 0.0),      // slow, thirsty, no combat
        DEFENDER(1.3f, 1.2f, 7.0f, 10.0),  // fast, heavy hitter, wider aggro
        SCOUT(1.6f, 0.6f, 0.0f, 0.0);      // very fast, fuel-efficient, no combat

        public final float speedMul;
        public final float fuelDrainMul;
        public final float attackDamage;
        public final double aggroRadius;
        DroneVariant(float s, float f, float a, double r) {
            this.speedMul = s; this.fuelDrainMul = f; this.attackDamage = a; this.aggroRadius = r;
        }
    }

    @Override
    protected InteractionResult mobInteract(Player player, InteractionHand hand) {
        // Sneak + right-click → open inventory GUI.
        if (player.isShiftKeyDown()) {
            if (!level().isClientSide() && player instanceof net.minecraft.server.level.ServerPlayer sp) {
                if (ownerId == null) ownerId = player.getUUID();
                sp.openMenu(this, buf -> buf.writeInt(this.getId()));
            }
            return InteractionResult.sidedSuccess(level().isClientSide());
        }
        if (!level().isClientSide()) {
            ItemStack held = player.getItemInHand(hand);
            int fuelValue = fuelValueFor(held);
            if (fuelValue > 0) {
                addFuel(fuelValue);
                if (!player.getAbilities().instabuild) held.shrink(1);
                player.sendSystemMessage(Component.literal(
                        "[ByteBlock Drone] +" + (fuelValue / 20) + "s fuel (now " + (fuelTicks / 20) + "s)."));
                return InteractionResult.sidedSuccess(false);
            }

            if (ownerId == null) {
                ownerId = player.getUUID();
                player.sendSystemMessage(Component.literal("[ByteBlock Drone] Linked to you."));
            } else if (ownerId.equals(player.getUUID())) {
                player.sendSystemMessage(Component.literal(
                        "[ByteBlock Drone] ID " + droneId.toString().substring(0, 8)
                                + " | Ch " + bluetoothChannel
                                + " | Fuel " + (fuelTicks / 20) + "s"
                                + " | Waypoints " + waypoints.size()));
            } else {
                player.sendSystemMessage(Component.literal("[ByteBlock Drone] Not your drone."));
            }
        }
        return InteractionResult.sidedSuccess(level().isClientSide());
    }

    private static int fuelValueFor(ItemStack stack) {
        if (stack.isEmpty()) return 0;
        var item = stack.getItem();
        if (item == Items.COAL || item == Items.CHARCOAL) return 1600;
        if (item == Items.COAL_BLOCK) return 16000;
        if (item == Items.BLAZE_ROD) return 2400;
        if (item == Items.LAVA_BUCKET) return 20000;
        return 0;
    }

    // --- Programmable API ---

    public void addWaypoint(Vec3 target) {
        this.dockedTicks = 0; // leaving the pad
        if (waypoints.size() < 64) waypoints.add(target);
    }

    public void clearWaypoints() { waypoints.clear(); }
    public void setHovering(boolean hover) { this.hovering = hover; }
    public boolean isHovering() { return hovering; }
    public int getFuelTicks() { return fuelTicks; }
    public int getFuel() { return fuelTicks; } // alias — matches docs naming
    public void addFuel(int ticks) { this.fuelTicks = Math.min(fuelTicks + ticks, MAX_FUEL); }
    public ItemStack getBatteryStack() { return batteryStack; }
    public void setBatteryStack(ItemStack stack) { this.batteryStack = stack; }
    public ItemStack getGpsToolStack() { return gpsToolStack; }
    public void setGpsToolStack(ItemStack stack) { this.gpsToolStack = stack == null ? ItemStack.EMPTY : stack; }
    public java.util.UUID getOwnerId() { return ownerId; }
    public com.apocscode.byteblock.entity.EntityPaint getPaint() { return paint; }
    public void setPaint(com.apocscode.byteblock.entity.EntityPaint p) { this.paint = p == null ? new com.apocscode.byteblock.entity.EntityPaint() : p; }

    /**
     * Returns the drone's current operation range in blocks, determined by
     * the highest-tier range card installed in the upgrade slots.
     * Default (no card): 25 blocks.  Unlimited range card: Integer.MAX_VALUE.
     */
    public int getOperationRange() {
        int best = 25;
        for (int i = 0; i < upgradeSlots.getContainerSize(); i++) {
            ItemStack s = upgradeSlots.getItem(i);
            if (s.getItem() instanceof com.apocscode.byteblock.item.UpgradeCard card
                    && card.getUpgradeType().isRangeCard()) {
                if (card.getUpgradeType() == com.apocscode.byteblock.item.UpgradeCard.Type.RANGE_CREATIVE)
                    return Integer.MAX_VALUE;
                best = Math.max(best, card.getUpgradeType().range);
            }
        }
        return best;
    }

    /**
     * Returns a speed multiplier based on installed Speed upgrade cards.
     * Each Speed card contributes +60% (stacks additively, capped at 3.0×).
     */
    public double getSpeedMultiplier() {
        int count = 0;
        for (int i = 0; i < upgradeSlots.getContainerSize(); i++) {
            ItemStack s = upgradeSlots.getItem(i);
            if (s.getItem() instanceof com.apocscode.byteblock.item.UpgradeCard card
                    && card.getUpgradeType().isSpeedCard()) count++;
        }
        return Math.min(1.0 + count * 0.6, 3.0);
    }

    /** Returns true if any installed upgrade card is an inventory expansion card. */
    public boolean hasInventoryUpgrade() {
        for (int i = 0; i < upgradeSlots.getContainerSize(); i++) {
            ItemStack s = upgradeSlots.getItem(i);
            if (s.getItem() instanceof com.apocscode.byteblock.item.UpgradeCard card
                    && card.getUpgradeType().isInventoryCard()) return true;
        }
        return false;
    }

        public boolean hasShieldUpgrade() {
            for (int i = 0; i < upgradeSlots.getContainerSize(); i++) {
                ItemStack s = upgradeSlots.getItem(i);
                if (s.getItem() instanceof com.apocscode.byteblock.item.UpgradeCard card
                        && card.getUpgradeType().isShieldCard()) return true;
            }
            return false;
        }

        public boolean hasSolarUpgrade() {
            for (int i = 0; i < upgradeSlots.getContainerSize(); i++) {
                ItemStack s = upgradeSlots.getItem(i);
                if (s.getItem() instanceof com.apocscode.byteblock.item.UpgradeCard card
                        && card.getUpgradeType().isSolarCard()) return true;
            }
            return false;
        }

        public boolean hasStealthUpgrade() {
            for (int i = 0; i < upgradeSlots.getContainerSize(); i++) {
                ItemStack s = upgradeSlots.getItem(i);
                if (s.getItem() instanceof com.apocscode.byteblock.item.UpgradeCard card
                        && card.getUpgradeType().isStealthCard()) return true;
            }
            return false;
        }

        /** Returns current shield HP (0–SHIELD_MAX) for renderer. */
        public float getShieldHP() { return shieldHP; }

        @Override
        public boolean hurt(net.minecraft.world.damagesource.DamageSource source, float amount) {
            if (hasShieldUpgrade() && shieldHP > 0) {
                float absorbed = Math.min(shieldHP, amount);
                shieldHP -= absorbed;
                amount -= absorbed;
                if (amount <= 0) return false;
            }
            return super.hurt(source, amount);
        }

    @Override
    public void checkDespawn() {
        // Drones never naturally despawn. If no player is within operation range
        // and the waypoint queue is empty, auto-return to home.
        if (level().isClientSide()) return;
        int range = getOperationRange();
        if (range == Integer.MAX_VALUE) return;
        net.minecraft.world.entity.player.Player nearest =
                ((net.minecraft.server.level.ServerLevel) level()).getNearestPlayer(this, -1);
        if ((nearest == null || nearest.distanceToSqr(this) > (long) range * range)
                && waypoints.isEmpty() && homePos != null) {
            Vec3 dest = new Vec3(homePos.getX() + 0.5, homePos.getY() + 1, homePos.getZ() + 0.5);
            if (position().distanceTo(dest) > 2.0) {
                addWaypoint(dest);
            }
        }
    }

    @Override
    public void die(net.minecraft.world.damagesource.DamageSource cause) {
        super.die(cause);
        if (!level().isClientSide()) {
            // Drop the drone as a spawn egg so it can be redeployed.
            spawnAtLocation(new ItemStack(com.apocscode.byteblock.init.ModItems.DRONE_SPAWN_EGG.get()));
            // Drop any cargo.
            for (int i = 0; i < inventory.getContainerSize(); i++) {
                ItemStack stack = inventory.getItem(i);
                if (!stack.isEmpty()) spawnAtLocation(stack);
            }
            // Drop upgrade cards.
            for (int i = 0; i < upgradeSlots.getContainerSize(); i++) {
                ItemStack stack = upgradeSlots.getItem(i);
                if (!stack.isEmpty()) spawnAtLocation(stack);
            }
        }
    }

    /** Pull FE from any battery item in the upgrade slot, converting it into fuel ticks (10 FE = 1 tick). */
    private void tickBatteryDrain() {
        if (batteryStack.isEmpty()) return;
        if (fuelTicks >= MAX_FUEL) return;
        var cap = batteryStack.getCapability(net.neoforged.neoforge.capabilities.Capabilities.EnergyStorage.ITEM);
        if (cap == null || !cap.canExtract()) return;
        int spaceTicks = MAX_FUEL - fuelTicks;
        int wantFE = Math.min(spaceTicks * 10, 200); // cap at 200 FE/tick
        int feAvail = cap.extractEnergy(wantFE, true);
        if (feAvail <= 0) return;
        int feTaken = cap.extractEnergy(feAvail, false);
        if (feTaken > 0) fuelTicks = Math.min(MAX_FUEL, fuelTicks + feTaken / 10);
    }
    public void linkComputer(UUID computerId) { this.linkedComputerId = computerId; }
    public UUID getDroneId() {
        if (level().isClientSide()) {
            java.util.Optional<UUID> synced = getEntityData().get(DATA_DRONE_ID);
            if (synced.isPresent()) return synced.get();
        }
        return droneId;
    }
    public int getBluetoothChannel() { return bluetoothChannel; }
    /** Client-safe: reads the synced entity data (available on both sides). */
    public int getSyncedBluetoothChannel() { return getEntityData().get(DATA_CHANNEL); }

    /**
     * Build the second-line nameplate stat string, e.g. "♥ 18/20  ⚡ 64%".
     * Energy here represents fuel as a percentage of MAX_FUEL.
     */
    /** Fuel value synced to the client via SynchedEntityData (updated every 10 ticks). */
    public int getSyncedFuel() { return getEntityData().get(DATA_FUEL); }

    public Component getStatsLine() {
        int hp  = (int) Math.ceil(getHealth());
        int max = (int) getMaxHealth();
        // Use synced value so client-side nameplate shows real server fuel.
        int fuel = getEntityData().get(DATA_FUEL);
        int pct = MAX_FUEL > 0 ? (fuel * 100 / MAX_FUEL) : 0;
        String pctColor = pct < 20 ? "§c" : pct < 50 ? "§e" : pct < 80 ? "§b" : "§a";
        String hpColor  = hp < max / 3 ? "§c" : hp < max * 2 / 3 ? "§e" : "§a";
        int hpFilled = max > 0 ? Math.min(4, Math.round(hp * 4f / max)) : 0;
        int fFilled  = Math.min(4, pct * 4 / 100);
        StringBuilder hpBar = new StringBuilder();
        StringBuilder fBar  = new StringBuilder();
        for (int i = 0; i < 4; i++) hpBar.append(i < hpFilled ? hpColor  + "█" : "§8░");
        for (int i = 0; i < 4; i++) fBar .append(i < fFilled  ? pctColor + "█" : "§8░");
        return Component.literal(
            "§7♥ " + hpBar + " §r" + hpColor + hp + "§8/§7" + max +
            "  §7⚡ " + fBar + " §r" + pctColor + pct + "§7%"
        );
    }
    public void setBluetoothChannel(int ch) {
        this.bluetoothChannel = Math.max(1, Math.min(65535, ch));
        getEntityData().set(DATA_CHANNEL, this.bluetoothChannel);
    }
    public int getWaypointCount() { return waypoints.size(); }
    public SimpleContainer getInventory() { return inventory; }
    public SimpleContainer getUpgradeSlots() { return upgradeSlots; }
    public BlockPos getHomePos() { return homePos; }
    public void setHomePos(BlockPos pos) {
        this.homePos = pos;
        if (pos != null && !level().isClientSide()) {
            getEntityData().set(DATA_HOME_X, pos.getX());
            getEntityData().set(DATA_HOME_Y, pos.getY());
            getEntityData().set(DATA_HOME_Z, pos.getZ());
        }
    }
    /** Returns the home position synced to the client, or null if not set. */
    public BlockPos getSyncedHomePos() {
        int x = getEntityData().get(DATA_HOME_X);
        return x == Integer.MIN_VALUE ? null : new BlockPos(x, getEntityData().get(DATA_HOME_Y), getEntityData().get(DATA_HOME_Z));
    }
    public BlockPos getChargePad() { return chargePad; }
    public void setChargePad(BlockPos pad) { this.chargePad = pad; }
    public boolean isDefender() { return defender; }
    public void setDefender(boolean d) { this.defender = d; }
    public String getSwarmGroup() { return swarmGroup; }
    public void setSwarmGroup(String g) { this.swarmGroup = g == null ? "" : g; }

    /**
     * Pull up to `max` items from the container at target into this drone's inventory.
     * Returns number of items moved.
     */
    public int pickupFromContainer(BlockPos target, int max) {
        if (level() == null || position().distanceToSqr(
                target.getX() + 0.5, target.getY() + 0.5, target.getZ() + 0.5) > 9) return 0;
        BlockEntity be = level().getBlockEntity(target);
        if (!(be instanceof Container src)) return 0;
        int moved = 0;
        for (int i = 0; i < src.getContainerSize() && moved < max; i++) {
            ItemStack stack = src.getItem(i);
            if (stack.isEmpty()) continue;
            int take = Math.min(stack.getCount(), max - moved);
            ItemStack piece = stack.copy();
            piece.setCount(take);
            ItemStack leftover = inventory.addItem(piece);
            int consumed = take - leftover.getCount();
            if (consumed > 0) {
                stack.shrink(consumed);
                src.setItem(i, stack.isEmpty() ? ItemStack.EMPTY : stack);
                src.setChanged();
                moved += consumed;
            }
        }
        return moved;
    }

    /**
     * Push up to `max` items from this drone's inventory into the container at target.
     * Returns number of items moved.
     */
    public int dropIntoContainer(BlockPos target, int max) {
        if (level() == null || position().distanceToSqr(
                target.getX() + 0.5, target.getY() + 0.5, target.getZ() + 0.5) > 9) return 0;
        BlockEntity be = level().getBlockEntity(target);
        if (!(be instanceof Container dst)) return 0;
        int moved = 0;
        for (int i = 0; i < inventory.getContainerSize() && moved < max; i++) {
            ItemStack stack = inventory.getItem(i);
            if (stack.isEmpty()) continue;
            int take = Math.min(stack.getCount(), max - moved);
            ItemStack piece = stack.copy();
            piece.setCount(take);
            ItemStack leftover = insertIntoContainer(dst, piece);
            int consumed = take - leftover.getCount();
            if (consumed > 0) {
                stack.shrink(consumed);
                inventory.setItem(i, stack.isEmpty() ? ItemStack.EMPTY : stack);
                dst.setChanged();
                moved += consumed;
            }
        }
        return moved;
    }

    private static ItemStack insertIntoContainer(Container dst, ItemStack stack) {
        // Try to merge with existing stacks first.
        for (int i = 0; i < dst.getContainerSize() && !stack.isEmpty(); i++) {
            ItemStack existing = dst.getItem(i);
            if (!existing.isEmpty() && ItemStack.isSameItemSameComponents(existing, stack)) {
                int space = Math.min(existing.getMaxStackSize(), dst.getMaxStackSize()) - existing.getCount();
                int move = Math.min(space, stack.getCount());
                if (move > 0) {
                    existing.grow(move);
                    stack.shrink(move);
                }
            }
        }
        // Then empty slots.
        for (int i = 0; i < dst.getContainerSize() && !stack.isEmpty(); i++) {
            if (dst.getItem(i).isEmpty() && dst.canPlaceItem(i, stack)) {
                int move = Math.min(Math.min(stack.getMaxStackSize(), dst.getMaxStackSize()), stack.getCount());
                ItemStack placed = stack.copy();
                placed.setCount(move);
                dst.setItem(i, placed);
                stack.shrink(move);
            }
        }
        return stack;
    }

    @Override
    public void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        if (ownerId != null) tag.putUUID("OwnerId", ownerId);
        if (droneId != null) tag.putUUID("DroneId", droneId);
        if (linkedComputerId != null) tag.putUUID("LinkedComputer", linkedComputerId);
        tag.putInt("BluetoothChannel", bluetoothChannel);
        tag.putInt("FuelTicks", fuelTicks);
        tag.putBoolean("Hovering", hovering);
        if (homePos != null) {
            tag.putInt("HomeX", homePos.getX());
            tag.putInt("HomeY", homePos.getY());
            tag.putInt("HomeZ", homePos.getZ());
        }
        tag.putBoolean("Defender", defender);
        tag.putString("SwarmGroup", swarmGroup);
        tag.putString("Variant", variant.name());
        if (chargePad != null) {
            tag.putInt("ChargePadX", chargePad.getX());
            tag.putInt("ChargePadY", chargePad.getY());
            tag.putInt("ChargePadZ", chargePad.getZ());
        }
        CompoundTag invTag = new CompoundTag();
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack stack = inventory.getItem(i);
            if (!stack.isEmpty()) {
                invTag.put(String.valueOf(i), stack.save(level().registryAccess()));
            }
        }
        tag.put("Inventory", invTag);
        if (!batteryStack.isEmpty()) {
            tag.put("Battery", batteryStack.save(level().registryAccess()));
        }
        if (!gpsToolStack.isEmpty()) {
            tag.put("GpsTool", gpsToolStack.save(level().registryAccess()));
        }
        CompoundTag upgradeTag = new CompoundTag();
        for (int i = 0; i < upgradeSlots.getContainerSize(); i++) {
            ItemStack stack = upgradeSlots.getItem(i);
            if (!stack.isEmpty()) upgradeTag.put(String.valueOf(i), stack.save(level().registryAccess()));
        }
        tag.put("Upgrades", upgradeTag);
        if (!paint.isEmpty()) tag.put("Paint", paint.save());
    }

    @Override
    public void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        if (tag.contains("OwnerId")) ownerId = tag.getUUID("OwnerId");
        if (tag.contains("DroneId")) droneId = tag.getUUID("DroneId");
        if (tag.contains("LinkedComputer")) linkedComputerId = tag.getUUID("LinkedComputer");
        if (tag.contains("BluetoothChannel")) {
            bluetoothChannel = tag.getInt("BluetoothChannel");
            getEntityData().set(DATA_CHANNEL, bluetoothChannel);
        }
        if (tag.contains("FuelTicks")) fuelTicks = tag.getInt("FuelTicks");
        if (tag.contains("Hovering")) hovering = tag.getBoolean("Hovering");
        if (tag.contains("HomeX")) {
            homePos = new BlockPos(tag.getInt("HomeX"), tag.getInt("HomeY"), tag.getInt("HomeZ"));
        }
        if (tag.contains("Defender")) defender = tag.getBoolean("Defender");
        if (tag.contains("SwarmGroup")) swarmGroup = tag.getString("SwarmGroup");
        if (tag.contains("Variant")) setVariantByName(tag.getString("Variant"));
        if (tag.contains("ChargePadX")) {
            chargePad = new BlockPos(tag.getInt("ChargePadX"), tag.getInt("ChargePadY"), tag.getInt("ChargePadZ"));
        }
        if (tag.contains("Inventory")) {
            CompoundTag invTag = tag.getCompound("Inventory");
            for (String key : invTag.getAllKeys()) {
                try {
                    int slot = Integer.parseInt(key);
                    inventory.setItem(slot,
                            ItemStack.parse(level().registryAccess(), invTag.getCompound(key))
                                    .orElse(ItemStack.EMPTY));
                } catch (NumberFormatException ignored) {
                }
            }
        }
        if (tag.contains("Battery")) {
            batteryStack = ItemStack.parse(level().registryAccess(), tag.getCompound("Battery"))
                    .orElse(ItemStack.EMPTY);
        }
        if (tag.contains("GpsTool")) {
            gpsToolStack = ItemStack.parse(level().registryAccess(), tag.getCompound("GpsTool"))
                    .orElse(ItemStack.EMPTY);
        }
        if (tag.contains("Upgrades")) {
            CompoundTag upgradeTag = tag.getCompound("Upgrades");
            for (String key : upgradeTag.getAllKeys()) {
                try {
                    int slot = Integer.parseInt(key);
                    upgradeSlots.setItem(slot,
                            ItemStack.parse(level().registryAccess(), upgradeTag.getCompound(key))
                                    .orElse(ItemStack.EMPTY));
                } catch (NumberFormatException ignored) {}
            }
        }
        if (tag.contains("Paint")) paint = com.apocscode.byteblock.entity.EntityPaint.load(tag.getCompound("Paint"));
    }

    // --- MenuProvider ---

    @Override
    public Component getDisplayName() {
        Component name = getCustomName();
        return name != null ? name : Component.literal("Drone");
    }

    @Override
    public net.minecraft.world.inventory.AbstractContainerMenu createMenu(int containerId,
            net.minecraft.world.entity.player.Inventory playerInv, Player player) {
        return new com.apocscode.byteblock.menu.DroneMenu(containerId, playerInv, this);
    }
}
