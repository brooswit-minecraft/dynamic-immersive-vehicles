package io.github.brooswitminecraft.div.experimental;
import com.mojang.logging.LogUtils;
import io.github.brooswitminecraft.div.PhysicsBridge;
import minecrafttransportsimulator.baseclasses.Point3D;
import minecrafttransportsimulator.baseclasses.RotationMatrix;
import minecrafttransportsimulator.baseclasses.TowingConnection;
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
 * Bounded, opt-in, CI/isolated-host-only runtime probe (John's comment 54259: the two new
 * {@code PhysicsBridgeClassificationTest} cases for the MINECRAFT-545/547 already-active
 * unsafe-fallback fix only exercise the pure {@code deferToNativeHitch}/{@code decide} boolean
 * helpers - they do NOT demonstrate actual removal of an already-existing Sable body, actual
 * joint release, or actual native-authority handoff through the real {@code PhysicsBridge.active}
 * entry point. This probe closes that gap live.
 *
 * <p>Method: {@code towed} (a buoyant hull) is spawned COMPLETELY STANDALONE - no hitch, no
 * towing vehicle at all - and settles into a normal, ALREADY-ACTIVE Sable body purely from
 * {@code Classification.floatEligible}'s own standalone eligibility (confirmed by MINECRAFT-545's
 * own `floatEligibleNoLongerExcludesActivelyTowedHull` - a float hull activates whether towed or
 * not). A FRESH buoyant vehicle ({@code towing2}) with a `mounted` hitch connection is then
 * connected to `towed` for the FIRST time - the first time `towed.towedByConnection` is ever
 * non-null - so `unsafeBuoyantAngularLock` fires (both sides buoyant, `mounted`) while `towed` is
 * ALREADY active, exercising exactly the (`alreadyActive=true`, `towingVehicleActive=false`,
 * `unsafeLock=true`) combination the fix targets, with no ground vehicle, no replacement, and no
 * prior hitch history to confound the result. Confirms, via {@code
 * PhysicsBridge.debugReadLinearVelocity}/{@code debugReadAngularVelocity} (the same debug-only
 * seam the ordinary-reconnect diagnostics already use), that `towed`'s real native Sable body is
 * ACTUALLY REMOVED - not merely unjointed - within a few ticks of the connect call, and that
 * `towed` remains a finite, valid entity afterward (no crash, no leaked handle).
 *
 * <p>Opt-in via {@code -Ddiv.experimentalAlreadyActiveUnsafeFallback=true} - OFF by default,
 * never runs during normal play, never touches any real vehicle, Zippy, or world content. Runs
 * ONCE per server lifetime, for a bounded window, then stops.
 */
public final class AlreadyActiveUnsafeFallbackProbe {
 private AlreadyActiveUnsafeFallbackProbe() {}
 private static final boolean ENABLED = Boolean.getBoolean("div.experimentalAlreadyActiveUnsafeFallback");
 private static final String PACK_ID = "divtest";
 private static final int CHUNK_X = 1300, CHUNK_Z = 1300; // far clear of every other probe's own fixed coordinates
 private static final int MAX_TERRAIN_WAIT_TICKS = 200;
 private static final int CHUNK_RADIUS = 3;
 private static final int PLATFORM_Y = 100;
 private static final int PLATFORM_RADIUS = 14;
 private static final int SETTLE_TICKS = 60;
 private static final int FALLBACK_VERIFY_TICKS = 40;
 private static final double HALF_EXTENT_X = 0.6, HALF_HEIGHT_Y = 0.4, HALF_EXTENT_Z = 1.0;
 private static final Point3D CHASSIS_CENTER = new Point3D(0, 0.4, 0);
 private static final Point3D FLOAT_LEFT = new Point3D(-0.5, -0.2, 0), FLOAT_RIGHT = new Point3D(0.5, -0.2, 0);
 private static final Point3D HITCH_POS = new Point3D(0, 0, -1.0);
 private static final Point3D HOOKUP_POS = new Point3D(0, 0, 1.0);
 private static final double PLATFORM_BOUND_HORIZONTAL = 60, PLATFORM_BOUND_VERTICAL = 20;
 private static final Logger LOGGER = LogUtils.getLogger();

 private enum Phase { WAIT_TERRAIN, SPAWN, CONFIRM_ALREADY_ACTIVE, CONNECT_UNSAFE_MOUNTED_TOW, VERIFY_FALLBACK, DONE, FAILED }
 private static Phase phase = Phase.WAIT_TERRAIN;
 private static int ticksInPhase = 0, terrainWaitTicksElapsed = 0;
 private static double spawnX, spawnZ, spawnSurfaceY;
 private static EntityVehicleF_Physics towed, towing2;
 private static boolean configured = false;

 public static void maybeRun(ServerLevel level) {
  if (!ENABLED) return;
  if (!configured) {
   configured = true;
   for (int dx = -CHUNK_RADIUS; dx <= CHUNK_RADIUS; dx++) for (int dz = -CHUNK_RADIUS; dz <= CHUNK_RADIUS; dz++) level.setChunkForced(CHUNK_X + dx, CHUNK_Z + dz, true);
   LOGGER.info("[DIV alreadyActiveUnsafeFallbackProbe] requested terrain chunk grid");
   return;
  }
  try {
   switch (phase) {
    case WAIT_TERRAIN -> waitTerrain(level);
    case SPAWN -> spawn(level);
    case CONFIRM_ALREADY_ACTIVE -> confirmAlreadyActive();
    case CONNECT_UNSAFE_MOUNTED_TOW -> connectUnsafeMountedTow(level);
    case VERIFY_FALLBACK -> verifyFallback();
    case DONE, FAILED -> {}
   }
  } catch (RuntimeException ex) {
   LOGGER.error("[DIV alreadyActiveUnsafeFallbackProbe] FAILED - unexpected exception in phase " + phase, ex);
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
    LOGGER.error("[DIV alreadyActiveUnsafeFallbackProbe] FAILED - terrain chunk grid never finished loading");
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
  double surfaceY = PLATFORM_Y + 1; // platform's own walkable top surface, see sibling probes' own note
  var packSpeedFactors = ConfigSystem.settings.general.packSpeedFactors.value;
  var packVehicleScales = ConfigSystem.settings.general.packVehicleScales.value;
  packSpeedFactors.putIfAbsent(PACK_ID, 1.0);
  packVehicleScales.putIfAbsent(PACK_ID, 1.0);
  AWrapperWorld world = mcinterface1211.WrapperWorld.getWrapperFor(level);
  // towed has a hookup connection (ordinary, mounted=false - only the TOWING side's own
  // connection's mounted flag is ever read by unsafeBuoyantAngularLock) but starts with NO
  // towing vehicle at all - standalone, exactly like any float hull nobody is towing.
  var towedDefinition = buildHullDefinition("alreadyactivefallback-towed", false, true, false);
  towed = spawnHull(world, towedDefinition, blockX + 0.5, surfaceY + 0.5, blockZ - 3.5, 0);
  spawnX = blockX + 0.5; spawnZ = blockZ + 0.5; spawnSurfaceY = surfaceY;
  phase = Phase.CONFIRM_ALREADY_ACTIVE; ticksInPhase = 0;
  LOGGER.info("[DIV alreadyActiveUnsafeFallbackProbe] spawned standalone towed(buoyant)=" + towed.position
    + " - no towing vehicle yet - beginning CONFIRM_ALREADY_ACTIVE phase");
 }

 private static void confirmAlreadyActive() {
  ticksInPhase++;
  if (!boundedOrFail("confirmAlreadyActive/towed", towed)) return;
  if (ticksInPhase < SETTLE_TICKS) return;
  var linear = PhysicsBridge.debugReadLinearVelocity(towed);
  if (linear == null) {
   LOGGER.error("[DIV alreadyActiveUnsafeFallbackProbe] FAILED - standalone towed never got an active Sable body (debugReadLinearVelocity returned null) - fixture problem, not the fallback question this probe exists to test");
   phase = Phase.FAILED;
   return;
  }
  LOGGER.info("[DIV alreadyActiveUnsafeFallbackProbe] CONFIRM_ALREADY_ACTIVE PASS - standalone towed has a real, active Sable body (linearVelocity=" + linear
    + ") after " + SETTLE_TICKS + " ticks with no hitch at all - connecting a fresh buoyant towing2 on a mounted connection for CONNECT_UNSAFE_MOUNTED_TOW");
  phase = Phase.CONNECT_UNSAFE_MOUNTED_TOW; ticksInPhase = 0;
 }

 private static void connectUnsafeMountedTow(ServerLevel level) {
  ticksInPhase++;
  if (ticksInPhase == 1) {
   AWrapperWorld world = mcinterface1211.WrapperWorld.getWrapperFor(level);
   var towing2Definition = buildHullDefinition("alreadyactivefallback-towing2", true, false, true);
   towing2 = spawnHull(world, towing2Definition, spawnX, spawnSurfaceY + 0.5, spawnZ + 1.0, 180);
   var connection = new TowingConnection(towing2, 0, 0, towed, 0, 0);
   towing2.connectTrailer(connection, false);
   LOGGER.info("[DIV alreadyActiveUnsafeFallbackProbe] CONNECT_UNSAFE_MOUNTED_TOW connected ALREADY-ACTIVE towed to fresh BUOYANT towing2="
     + towing2.position + " on a MOUNTED connection, for the FIRST time this trailer has ever had a towedByConnection -"
     + " unsafeLock should now fire while alreadyActive is true");
  }
  if (!boundedOrFail("connectUnsafeMountedTow/towed", towed)) return;
  phase = Phase.VERIFY_FALLBACK; ticksInPhase = 0;
 }

 private static void verifyFallback() {
  ticksInPhase++;
  if (!finite(towed) || !towed.isValid) {
   LOGGER.error("[DIV alreadyActiveUnsafeFallbackProbe] FAILED - towed became non-finite or invalid during VERIFY_FALLBACK tick=" + ticksInPhase
     + " position=" + towed.position);
   phase = Phase.FAILED;
   return;
  }
  var linear = PhysicsBridge.debugReadLinearVelocity(towed);
  var angular = PhysicsBridge.debugReadAngularVelocity(towed);
  if (ticksInPhase <= 10 || ticksInPhase % 10 == 0 || ticksInPhase == FALLBACK_VERIFY_TICKS) {
   LOGGER.info("[DIV alreadyActiveUnsafeFallbackProbe] VERIFY_FALLBACK tick=" + ticksInPhase + " towed.linearVelocity=" + linear
     + " towed.angularVelocity=" + angular + " towed.position=" + towed.position + " towed.towedByConnection=" + (towed.towedByConnection != null));
  }
  if (linear == null && angular == null) {
   LOGGER.info("[DIV alreadyActiveUnsafeFallbackProbe] VERIFY_FALLBACK PASS at tick=" + ticksInPhase
     + " - towed's real native Sable body is ACTUALLY REMOVED (debugReadLinearVelocity/debugReadAngularVelocity both null), not merely unjointed."
     + " towed.isValid=" + towed.isValid + " towed.position=" + towed.position + " - end-to-end existing-body lifecycle confirmed live, per 54259.");
   phase = Phase.DONE;
   return;
  }
  if (ticksInPhase >= FALLBACK_VERIFY_TICKS) {
   LOGGER.error("[DIV alreadyActiveUnsafeFallbackProbe] FAILED - towed STILL has an active Sable body (linearVelocity=" + linear
     + ", angularVelocity=" + angular + ") after " + FALLBACK_VERIFY_TICKS
     + " ticks post-connect - the already-active unsafe-fallback fix does NOT reach actual native-body removal live.");
   phase = Phase.FAILED;
  }
 }

 private static boolean finite(EntityVehicleF_Physics v) {
  double[] values = {v.position.x, v.position.y, v.position.z, v.motion.x, v.motion.y, v.motion.z,
    v.orientation.m00, v.orientation.m11, v.orientation.m22};
  for (double d : values) if (!Double.isFinite(d)) return false;
  return true;
 }

 private static boolean boundedOrFail(String tag, EntityVehicleF_Physics v) {
  if (!finite(v)) {
   LOGGER.error("[DIV alreadyActiveUnsafeFallbackProbe] FAILED - non-finite state tag=" + tag + " phase=" + phase + " tick=" + ticksInPhase + " position=" + v.position);
   phase = Phase.FAILED;
   return false;
  }
  if (Math.abs(v.position.x - spawnX) > PLATFORM_BOUND_HORIZONTAL || Math.abs(v.position.z - spawnZ) > PLATFORM_BOUND_HORIZONTAL
      || Math.abs(v.position.y - spawnSurfaceY) > PLATFORM_BOUND_VERTICAL) {
   LOGGER.error("[DIV alreadyActiveUnsafeFallbackProbe] FAILED - left bounded area tag=" + tag + " phase=" + phase + " tick=" + ticksInPhase
     + " position=" + v.position);
   phase = Phase.FAILED;
   return false;
  }
  return true;
 }

 private static EntityVehicleF_Physics spawnHull(AWrapperWorld world, JSONVehicle definition, double x, double y, double z, double yawDegrees) {
  var floatSlotLeft = floatSlot(FLOAT_LEFT);
  var floatSlotRight = floatSlot(FLOAT_RIGHT);
  definition.parts = List.of(floatSlotLeft, floatSlotRight);
  var item = new ItemVehicle(definition, definition.definitions.get(0), PACK_ID);
  var vehicle = new EntityVehicleF_Physics(world, null, item, null);
  vehicle.position.set(x, y, z);
  vehicle.orientation.set(new RotationMatrix()).setToAngles(new Point3D(0, yawDegrees, 0));
  world.spawnEntity(vehicle);
  addFloat(vehicle, floatSlotLeft); addFloat(vehicle, floatSlotRight);
  return vehicle;
 }

 private static JSONVehicle buildHullDefinition(String systemName, boolean hitch, boolean hookup, boolean mounted) {
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
  if (hitch) v.connectionGroups = List.of(connectionGroup(true, HITCH_POS, mounted));
  if (hookup) v.connectionGroups = List.of(connectionGroup(false, HOOKUP_POS, mounted));
  return v;
 }

 private static JSONConnectionGroup connectionGroup(boolean isHitch, Point3D pos, boolean mounted) {
  var connection = new JSONConnection();
  connection.type = "hitch";
  connection.pos = pos.copy();
  connection.distance = 4;
  connection.mounted = mounted;
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

 private static JSONPartDefinition floatSlot(Point3D localPos) {
  var slot = new JSONPartDefinition();
  slot.pos = localPos;
  slot.types = List.of("ground_pontoon");
  return slot;
 }

 private static void addFloat(EntityVehicleF_Physics vehicle, JSONPartDefinition slot) {
  var ground = new JSONPart.JSONPartGroundDevice();
  ground.canFloat = true;
  ground.width = 0.4f; ground.height = 0.4f;
  ground.frictionModifiers = new java.util.LinkedHashMap<>();
  var def = new JSONPart();
  def.packID = PACK_ID; def.systemName = "pontoon";
  def.generic = new JSONPart.JSONPartGeneric();
  def.ground = ground;
  def.definitions = List.of(firstSubDefinition(def));
  def.rendering = new minecrafttransportsimulator.jsondefs.JSONRendering();
  def.rendering.modelType = minecrafttransportsimulator.jsondefs.JSONRendering.ModelType.NONE;
  def.general = new minecrafttransportsimulator.jsondefs.AJSONItem.General();
  def.general.health = 100;
  var item = new ItemPartGroundDevice(def, def.definitions.get(0), PACK_ID);
  var part = new PartGroundDevice(vehicle, null, slot, item, null);
  vehicle.addPart(part, false);
 }
}
