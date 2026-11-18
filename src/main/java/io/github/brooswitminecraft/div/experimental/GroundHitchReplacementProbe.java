package io.github.brooswitminecraft.div.experimental;
import com.mojang.logging.LogUtils;
import minecrafttransportsimulator.baseclasses.Point3D;
import minecrafttransportsimulator.baseclasses.RotationMatrix;
import minecrafttransportsimulator.baseclasses.TowingConnection;
import minecrafttransportsimulator.entities.instances.APart;
import minecrafttransportsimulator.entities.instances.EntityVehicleF_Physics;
import minecrafttransportsimulator.entities.instances.PartGroundDevice;
import minecrafttransportsimulator.items.instances.ItemPartGroundDevice;
import minecrafttransportsimulator.items.instances.ItemVehicle;
import minecrafttransportsimulator.jsondefs.JSONCollisionBox;
import minecrafttransportsimulator.jsondefs.JSONCollisionGroup;
import minecrafttransportsimulator.jsondefs.JSONConnection;
import minecrafttransportsimulator.jsondefs.JSONConnectionGroup;
import minecrafttransportsimulator.jsondefs.JSONPart;
import minecrafttransportsimulator.jsondefs.JSONPartDefinition;
import minecrafttransportsimulator.jsondefs.JSONVehicle;
import minecrafttransportsimulator.mcinterface.AWrapperWorld;
import minecrafttransportsimulator.systems.ConfigSystem;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import org.slf4j.Logger;
import java.util.List;
import java.util.Set;

/**
 * Bounded, opt-in, CI/isolated-host-only runtime probe (John's own directive on MINECRAFT-545,
 * ticket comment 54217): "one bounded minimal ground-vehicle reconnect comparison using the
 * same lifecycle/anchor setup" as {@link BuoyantHitchProbe}'s own `TOWING_REPLACEMENT` phase -
 * to distinguish whether the live-reproduced crash (reconnecting a trailer to a freshly-spawned
 * towing vehicle after the original despawned explodes into a runaway within ~3 ticks and
 * crashes `Body.step`/`BuoyantBody.step`) is specific to the newly-admitted float-only
 * `HitchAnchor` path, or a PRE-EXISTING gap in `PhysicsBridge.createHitch`/`reconcileHitch` that
 * already existed for the ground/flight/rotor `Body` case and this ticket's own testing simply
 * happened to be the first to exercise live.
 *
 * <p>Method: deliberately the SAME lifecycle shape as {@code BuoyantHitchProbe}'s main pair -
 * spawn two simple 4-wheeled ground vehicles (no engine; `Classification.eligible` only needs a
 * drivable ground device, not an engine, to activate a `PhysicsBridge.Body`) on a flat stone
 * platform, connect via the real {@code AEntityG_Towable.connectTrailer} API, settle, detach,
 * despawn the towing vehicle while the trailer stays jointed, then spawn a FRESH towing vehicle
 * and reconnect the SAME trailer - logging position/speed every tick from the moment of
 * reconnection, exactly matching {@code BuoyantHitchProbe}'s own diagnostic granularity, so the
 * two trajectories are directly comparable.
 *
 * <p>Opt-in via {@code -Ddiv.experimentalGroundHitchReplacement=true} - OFF by default, never
 * runs during normal play, never touches any real vehicle, Zippy, or world content. Runs ONCE
 * per server lifetime, for a bounded window, then stops.
 */
public final class GroundHitchReplacementProbe {
 private GroundHitchReplacementProbe() {}
 private static final boolean ENABLED = Boolean.getBoolean("div.experimentalGroundHitchReplacement");
 private static final String PACK_ID = "divtest";
 private static final int CHUNK_X = 1200, CHUNK_Z = 1200; // far clear of every other probe's own fixed coordinates
 private static final int MAX_TERRAIN_WAIT_TICKS = 200;
 private static final int CHUNK_RADIUS = 3;
 private static final int PLATFORM_Y = 100;
 private static final int PLATFORM_RADIUS = 14;
 private static final int SETTLE_TICKS = 140;
 private static final int SHORT_PHASE_TICKS = 60;
 private static final double HALF_EXTENT_X = 0.6, HALF_HEIGHT_Y = 0.4, HALF_EXTENT_Z = 1.0;
 private static final Point3D CHASSIS_CENTER = new Point3D(0, 0.4, 0);
 private static final Point3D WHEEL_FL = new Point3D(-0.5, -0.2, 0.8), WHEEL_FR = new Point3D(0.5, -0.2, 0.8);
 private static final Point3D WHEEL_RL = new Point3D(-0.5, -0.2, -0.8), WHEEL_RR = new Point3D(0.5, -0.2, -0.8);
 private static final Point3D HITCH_POS = new Point3D(0, 0, -1.0);
 private static final Point3D HOOKUP_POS = new Point3D(0, 0, 1.0);
 private static final double HOOKUP_YAW_DEGREES = 20.0;
 private static final double PLATFORM_BOUND_HORIZONTAL = 60, PLATFORM_BOUND_VERTICAL = 20;
 private static final double ANCHOR_TOLERANCE = 0.75;
 private static final Logger LOGGER = LogUtils.getLogger();

 private enum Phase { WAIT_TERRAIN, SPAWN, ATTACH, DETACH, TOWING_UNAVAILABLE, TOWING_REPLACEMENT, DONE, FAILED }
 private static Phase phase = Phase.WAIT_TERRAIN;
 private static int ticksInPhase = 0, terrainWaitTicksElapsed = 0;
 private static double spawnX, spawnZ, spawnSurfaceY;
 private static EntityVehicleF_Physics towing, towed, towing2;
 private static JSONVehicle towingDefinition, towedDefinition, towing2Definition;
 private static boolean configured = false;

 public static void maybeRun(ServerLevel level) {
  if (!ENABLED) return;
  if (!configured) {
   configured = true;
   for (int dx = -CHUNK_RADIUS; dx <= CHUNK_RADIUS; dx++) for (int dz = -CHUNK_RADIUS; dz <= CHUNK_RADIUS; dz++) level.setChunkForced(CHUNK_X + dx, CHUNK_Z + dz, true);
   LOGGER.info("[DIV groundHitchReplacementProbe] requested terrain chunk grid");
   return;
  }
  try {
   switch (phase) {
    case WAIT_TERRAIN -> waitTerrain(level);
    case SPAWN -> spawn(level);
    case ATTACH -> attach();
    case DETACH -> detach();
    case TOWING_UNAVAILABLE -> towingUnavailable(level);
    case TOWING_REPLACEMENT -> towingReplacement(level);
    case DONE, FAILED -> {}
   }
  } catch (RuntimeException ex) {
   LOGGER.error("[DIV groundHitchReplacementProbe] FAILED - unexpected exception in phase " + phase, ex);
   phase = Phase.FAILED;
  }
 }

 private static boolean chunkGridLoaded(ServerLevel level) {
  var chunkSource = level.getChunkSource();
  for (int dx = -CHUNK_RADIUS; dx <= CHUNK_RADIUS; dx++) for (int dz = -CHUNK_RADIUS; dz <= CHUNK_RADIUS; dz++) {
   if (chunkSource.getChunkNow(CHUNK_X + dx, CHUNK_Z + dz) == null) return false;
  }
  return true;
 }

 private static void waitTerrain(ServerLevel level) {
  terrainWaitTicksElapsed++;
  if (!chunkGridLoaded(level)) {
   if (terrainWaitTicksElapsed > MAX_TERRAIN_WAIT_TICKS) {
    LOGGER.error("[DIV groundHitchReplacementProbe] FAILED - terrain chunk grid never finished loading");
    phase = Phase.FAILED;
   }
   return;
  }
  phase = Phase.SPAWN;
 }

 private static void buildPlatform(ServerLevel level, int centerX, int centerZ) {
  var stone = net.minecraft.world.level.block.Blocks.STONE.defaultBlockState();
  var air = net.minecraft.world.level.block.Blocks.AIR.defaultBlockState();
  for (int dx = -PLATFORM_RADIUS; dx <= PLATFORM_RADIUS; dx++) {
   for (int dz = -PLATFORM_RADIUS; dz <= PLATFORM_RADIUS; dz++) {
    level.setBlock(new BlockPos(centerX + dx, PLATFORM_Y, centerZ + dz), stone, 3);
    for (int dy = 1; dy <= 8; dy++) level.setBlock(new BlockPos(centerX + dx, PLATFORM_Y + dy, centerZ + dz), air, 3);
   }
  }
 }

 private static void spawn(ServerLevel level) {
  int blockX = CHUNK_X * 16 + 8, blockZ = CHUNK_Z * 16 + 8;
  buildPlatform(level, blockX, blockZ);
  // The platform's own walkable top surface is PLATFORM_Y+1 (a block at Y=PLATFORM_Y occupies
  // PLATFORM_Y..PLATFORM_Y+1) - spawning at PLATFORM_Y+1.0 (as a first draft of this probe did)
  // puts the vehicle ORIGIN only 1 block above PLATFORM_Y, but the chassis bottom sits at
  // origin-HALF_HEIGHT_Y(0.4) and each wheel's own contact point sits a further radius(0.2)
  // below that - net origin-0.6, which was BELOW the real surface, burying the car in solid
  // ground at spawn and producing immediate, violent, unrelated chaos (confirmed live: all four
  // wheel rays MISS, huge uncontrolled angular velocity from tick 1) - not evidence about the
  // hitch-replacement question this probe exists to test.
  double surfaceY = PLATFORM_Y + 1;
  var packSpeedFactors = ConfigSystem.settings.general.packSpeedFactors.value;
  var packVehicleScales = ConfigSystem.settings.general.packVehicleScales.value;
  packSpeedFactors.putIfAbsent(PACK_ID, 1.0);
  packVehicleScales.putIfAbsent(PACK_ID, 1.0);
  AWrapperWorld world = mcinterface1211.WrapperWorld.getWrapperFor(level);
  towingDefinition = buildVehicleDefinition("groundhitch-towing", true);
  towedDefinition = buildVehicleDefinition("groundhitch-towed", false);
  towing = spawnCar(world, towingDefinition, blockX + 0.5, surfaceY + 0.7, blockZ + 0.5, 0);
  towed = spawnCar(world, towedDefinition, blockX + 0.5, surfaceY + 0.7, blockZ - 3.5, 180 + HOOKUP_YAW_DEGREES);
  var connection = new TowingConnection(towing, 0, 0, towed, 0, 0);
  towing.connectTrailer(connection, false);
  spawnX = blockX + 0.5; spawnZ = blockZ + 0.5; spawnSurfaceY = surfaceY;
  phase = Phase.ATTACH; ticksInPhase = 0;
  LOGGER.info("[DIV groundHitchReplacementProbe] spawned towing=" + towing.position + " towed=" + towed.position + " - beginning ATTACH phase");
 }

 private static EntityVehicleF_Physics spawnCar(AWrapperWorld world, JSONVehicle definition, double x, double y, double z, double yawDegrees) {
  var wheelFL = wheelSlot(WHEEL_FL); var wheelFR = wheelSlot(WHEEL_FR);
  var wheelRL = wheelSlot(WHEEL_RL); var wheelRR = wheelSlot(WHEEL_RR);
  definition.parts = List.of(wheelFL, wheelFR, wheelRL, wheelRR);
  var item = new ItemVehicle(definition, definition.definitions.get(0), PACK_ID);
  var vehicle = new EntityVehicleF_Physics(world, null, item, null);
  vehicle.position.set(x, y, z);
  vehicle.orientation.set(new RotationMatrix()).setToAngles(new Point3D(0, yawDegrees, 0));
  world.spawnEntity(vehicle);
  addWheel(vehicle, wheelFL); addWheel(vehicle, wheelFR); addWheel(vehicle, wheelRL); addWheel(vehicle, wheelRR);
  return vehicle;
 }

 private static JSONVehicle buildVehicleDefinition(String systemName, boolean hitch) {
  var v = new JSONVehicle();
  v.packID = PACK_ID; v.systemName = systemName;
  v.motorized = new JSONVehicle.VehicleMotorized();
  v.motorized.litVariable = ""; v.motorized.panel = "";
  v.definitions = List.of(firstSubDefinition(v));
  v.rendering = new minecrafttransportsimulator.jsondefs.JSONRendering();
  v.rendering.modelType = minecrafttransportsimulator.jsondefs.JSONRendering.ModelType.NONE;
  v.general = new minecrafttransportsimulator.jsondefs.AJSONItem.General();
  v.general.health = 100;
  var mainBox = new JSONCollisionBox();
  mainBox.pos = CHASSIS_CENTER.copy();
  mainBox.width = (float) (HALF_EXTENT_X * 2);
  mainBox.height = (float) (HALF_HEIGHT_Y * 2);
  var zMarkerFront = new JSONCollisionBox();
  zMarkerFront.pos = new Point3D(CHASSIS_CENTER.x, CHASSIS_CENTER.y, CHASSIS_CENTER.z + HALF_EXTENT_Z);
  zMarkerFront.width = 0; zMarkerFront.height = 0;
  var zMarkerRear = new JSONCollisionBox();
  zMarkerRear.pos = new Point3D(CHASSIS_CENTER.x, CHASSIS_CENTER.y, CHASSIS_CENTER.z - HALF_EXTENT_Z);
  zMarkerRear.width = 0; zMarkerRear.height = 0;
  var group = new JSONCollisionGroup();
  group.collisionTypes = Set.of(JSONCollisionGroup.CollisionType.VEHICLE);
  group.collisions = List.of(mainBox, zMarkerFront, zMarkerRear);
  v.collisionGroups = List.of(group);
  if (hitch) v.connectionGroups = List.of(connectionGroup(true, HITCH_POS));
  else v.connectionGroups = List.of(connectionGroup(false, HOOKUP_POS));
  return v;
 }

 private static JSONConnectionGroup connectionGroup(boolean isHitch, Point3D pos) {
  var connection = new JSONConnection();
  connection.type = "hitch";
  connection.pos = pos.copy();
  connection.distance = 4;
  var group = new JSONConnectionGroup();
  group.groupName = isHitch ? "hitch" : "hookup";
  group.isHitch = isHitch; group.isHookup = !isHitch;
  group.connections = List.of(connection);
  return group;
 }

 private static minecrafttransportsimulator.jsondefs.JSONSubDefinition firstSubDefinition(JSONVehicle v) {
  var sub = new minecrafttransportsimulator.jsondefs.JSONSubDefinition();
  sub.subName = ""; sub.name = v.systemName;
  return sub;
 }

 private static minecrafttransportsimulator.jsondefs.JSONSubDefinition firstSubDefinition(JSONPart p) {
  var sub = new minecrafttransportsimulator.jsondefs.JSONSubDefinition();
  sub.subName = ""; sub.name = p.systemName;
  return sub;
 }

 private static JSONPartDefinition wheelSlot(Point3D localPos) {
  var slot = new JSONPartDefinition();
  slot.pos = localPos;
  slot.types = List.of("ground_wheel");
  return slot;
 }

 private static void addWheel(EntityVehicleF_Physics vehicle, JSONPartDefinition slot) {
  var ground = new JSONPart.JSONPartGroundDevice();
  ground.isWheel = true;
  ground.width = 0.4f; ground.height = 0.4f;
  ground.frictionModifiers = new java.util.LinkedHashMap<>();
  var def = new JSONPart();
  def.packID = PACK_ID; def.systemName = "wheel";
  def.generic = new JSONPart.JSONPartGeneric();
  def.ground = ground;
  def.definitions = List.of(firstSubDefinition(def));
  def.rendering = new minecrafttransportsimulator.jsondefs.JSONRendering();
  def.rendering.modelType = minecrafttransportsimulator.jsondefs.JSONRendering.ModelType.NONE;
  def.general = new minecrafttransportsimulator.jsondefs.AJSONItem.General();
  def.general.health = 100;
  var item = new ItemPartGroundDevice(def, def.definitions.get(0), PACK_ID);
  APart part = new PartGroundDevice(vehicle, null, slot, item, null);
  vehicle.addPart(part, false);
 }

 private static boolean finite(EntityVehicleF_Physics v) {
  double[] values = {v.position.x, v.position.y, v.position.z, v.motion.x, v.motion.y, v.motion.z,
    v.orientation.m00, v.orientation.m11, v.orientation.m22};
  for (double d : values) if (!Double.isFinite(d)) return false;
  return true;
 }

 private static boolean boundedOrFail(String tag, EntityVehicleF_Physics v) {
  if (!finite(v)) {
   LOGGER.error("[DIV groundHitchReplacementProbe] FAILED - non-finite state tag=" + tag + " phase=" + phase + " tick=" + ticksInPhase + " position=" + v.position);
   phase = Phase.FAILED;
   return false;
  }
  double dx = v.position.x - spawnX, dz = v.position.z - spawnZ;
  double horizontal = Math.sqrt(dx * dx + dz * dz), vertical = Math.abs(v.position.y - spawnSurfaceY);
  if (horizontal > PLATFORM_BOUND_HORIZONTAL || vertical > PLATFORM_BOUND_VERTICAL) {
   LOGGER.error("[DIV groundHitchReplacementProbe] FAILED - left bounded area tag=" + tag + " phase=" + phase + " tick=" + ticksInPhase
     + " horizontal=" + horizontal + " vertical=" + vertical + " position=" + v.position);
   phase = Phase.FAILED;
   return false;
  }
  return true;
 }

 private static Point3D anchorWorld(minecrafttransportsimulator.entities.components.AEntityB_Existing entity, JSONConnection connection) {
  return connection.pos.copy().rotate(entity.orientation).add(entity.position);
 }

 private static double anchorDistance(EntityVehicleF_Physics towingVehicle, JSONVehicle towingDef) {
  Point3D hitchAnchor = anchorWorld(towingVehicle, towingDef.connectionGroups.get(0).connections.get(0));
  Point3D hookupAnchor = anchorWorld(towed, towedDefinition.connectionGroups.get(0).connections.get(0));
  double dx = hitchAnchor.x - hookupAnchor.x, dy = hitchAnchor.y - hookupAnchor.y, dz = hitchAnchor.z - hookupAnchor.z;
  return Math.sqrt(dx * dx + dy * dy + dz * dz);
 }

 private static void attach() {
  ticksInPhase++;
  if (!boundedOrFail("attach/towing", towing) || !boundedOrFail("attach/towed", towed)) return;
  double distance = anchorDistance(towing, towingDefinition);
  if (ticksInPhase % 20 == 0 || ticksInPhase == SETTLE_TICKS) {
   LOGGER.info("[DIV groundHitchReplacementProbe] ATTACH tick=" + ticksInPhase + " anchorDistance=" + distance);
  }
  if (ticksInPhase >= SETTLE_TICKS) {
   if (distance > ANCHOR_TOLERANCE) {
    LOGGER.error("[DIV groundHitchReplacementProbe] FAILED - ATTACH anchorDistance=" + distance + " did not settle within tolerance=" + ANCHOR_TOLERANCE);
    phase = Phase.FAILED;
    return;
   }
   LOGGER.info("[DIV groundHitchReplacementProbe] ATTACH PASS anchorDistance=" + distance + " - disconnecting");
   towing.disconnectTrailer(0);
   phase = Phase.DETACH; ticksInPhase = 0;
  }
 }

 private static void detach() {
  ticksInPhase++;
  if (!boundedOrFail("detach/towing", towing) || !boundedOrFail("detach/towed", towed)) return;
  if (ticksInPhase >= SHORT_PHASE_TICKS) {
   if (towed.towedByConnection != null) {
    LOGGER.error("[DIV groundHitchReplacementProbe] FAILED - DETACH towed.towedByConnection still non-null: " + towed.towedByConnection);
    phase = Phase.FAILED;
    return;
   }
   LOGGER.info("[DIV groundHitchReplacementProbe] DETACH PASS - despawning towing for TOWING_UNAVAILABLE");
   var connection = new TowingConnection(towing, 0, 0, towed, 0, 0);
   towing.connectTrailer(connection, false);
   phase = Phase.TOWING_UNAVAILABLE; ticksInPhase = 0;
  }
 }

 private static void towingUnavailable(ServerLevel level) {
  ticksInPhase++;
  if (ticksInPhase == 1) {
   LOGGER.info("[DIV groundHitchReplacementProbe] TOWING_UNAVAILABLE despawning towing while towed remains jointed");
   towing.remove();
  }
  if (!boundedOrFail("towingUnavailable/towed", towed)) return;
  if (ticksInPhase >= SHORT_PHASE_TICKS) {
   LOGGER.info("[DIV groundHitchReplacementProbe] TOWING_UNAVAILABLE PASS - spawning towing2 for TOWING_REPLACEMENT");
   phase = Phase.TOWING_REPLACEMENT; ticksInPhase = 0;
  }
 }

 private static void towingReplacement(ServerLevel level) {
  ticksInPhase++;
  if (ticksInPhase == 1) {
   AWrapperWorld world = mcinterface1211.WrapperWorld.getWrapperFor(level);
   towing2Definition = buildVehicleDefinition("groundhitch-towing2", true);
   Point3D towedHookupAnchor = anchorWorld(towed, towedDefinition.connectionGroups.get(0).connections.get(0));
   Point3D towardHookupAnchor = towedHookupAnchor.copy().subtract(towed.position).normalize();
   Point3D towing2Spawn = towed.position.copy().addScaled(towardHookupAnchor, 4.0);
   towing2Spawn.y = towed.position.y;
   towing2 = spawnCar(world, towing2Definition, towing2Spawn.x, towing2Spawn.y, towing2Spawn.z, 0);
   LOGGER.info("[DIV groundHitchReplacementProbe] TOWING_REPLACEMENT pre-connect towing2=" + towing2.position + " towing2.motion=" + towing2.motion
     + " towed=" + towed.position + " towed.motion=" + towed.motion + " towedHookupAnchor=" + towedHookupAnchor);
   var connection = new TowingConnection(towing2, 0, 0, towed, 0, 0);
   towing2.connectTrailer(connection, false);
   LOGGER.info("[DIV groundHitchReplacementProbe] TOWING_REPLACEMENT connected towed to fresh towing2=" + towing2.position);
  }
  if (!boundedOrFail("towingReplacement/towing2", towing2) || !boundedOrFail("towingReplacement/towed", towed)) return;
  double distance = anchorDistance(towing2, towing2Definition);
  double towing2Speed = towing2.motion.length() * 20 * towing2.speedFactor;
  double towedSpeed = towed.motion.length() * 20 * towed.speedFactor;
  if (ticksInPhase <= 20 || ticksInPhase % 20 == 0 || ticksInPhase == SETTLE_TICKS) {
   LOGGER.info("[DIV groundHitchReplacementProbe] TOWING_REPLACEMENT tick=" + ticksInPhase + " anchorDistance=" + distance
     + " towing2.pos=" + towing2.position + " towing2.speed(blocks/s)=" + towing2Speed
     + " towed.pos=" + towed.position + " towed.speed(blocks/s)=" + towedSpeed);
  }
  if (ticksInPhase >= SETTLE_TICKS) {
   if (distance > ANCHOR_TOLERANCE) {
    LOGGER.error("[DIV groundHitchReplacementProbe] FAILED - TOWING_REPLACEMENT anchorDistance=" + distance + " did not settle within tolerance=" + ANCHOR_TOLERANCE);
    phase = Phase.FAILED;
    return;
   }
   LOGGER.info("[DIV groundHitchReplacementProbe] TOWING_REPLACEMENT PASS anchorDistance=" + distance);
   phase = Phase.DONE;
   LOGGER.info("[DIV groundHitchReplacementProbe] COMPLETE - ATTACH/DETACH/TOWING_UNAVAILABLE/TOWING_REPLACEMENT all passed");
  }
 }
}
