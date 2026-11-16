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
 * Bounded, opt-in, CI/isolated-host-only runtime probe (John's comment 54259, sharpened by
 * 54262: the first version of this probe started `towed` completely UNJOINTED and declared PASS
 * the moment both velocity lookups read null - that does NOT establish release of a PREEXISTING
 * joint, sustained native towing, or actual native-object destruction, only that DIV's own
 * registry no longer tracks a body). This revision closes all three gaps with direct
 * OBSERVATIONS, not source inference:
 *
 * <p>1) A REAL preexisting joint: `towed` is towed from the moment it spawns by `towing1`, a
 * FRESH buoyant vehicle on an ORDINARY (unmounted, unrestricted) connection - SAFE regardless of
 * both sides being buoyant, since `unsafeBuoyantAngularLock` only fires for `mounted`/`restricted`
 * - matching `BuoyantHitchProbe`'s own proven-safe `ATTACH` pattern. `towed` settles into a real,
 * ALREADY-ACTIVE Sable body WITH a real, live joint, confirmed via {@code
 * PhysicsBridge.debugIsJointed} (a direct {@code HitchRegistry.isJointed} observation, not an
 * inference). `towing1` then despawns and is replaced by a FRESH buoyant `towing2` on a `mounted`
 * connection - now `unsafeBuoyantAngularLock` fires for the first time while `towed` is ALREADY
 * active AND already carries a real joint to release, exercising the exact
 * (`alreadyActive=true`, `towingVehicleActive=false`, `unsafeLock=true`) combination the fix
 * targets with a genuine preexisting joint in the loop.
 *
 * <p>2) Actual native-object destruction, not just registry absence: {@code
 * PhysicsBridge.debugCaptureBody} captures `towed`'s own active body as an opaque reference
 * BEFORE the unsafe reconnect; {@code PhysicsBridge.debugCapturedBoxIsActive} then reads that
 * SAME captured native {@code BoxPhysicsObject}'s own {@code isActive()} AFTER - a live
 * observation of the SAME object transitioning active-&gt;inactive, not an inference from
 * {@code deactivate}/{@code BuoyantBody.remove}'s own source.
 *
 * <p>3) Sustained native towing, not a momentary snapshot: `VERIFY_FALLBACK` runs for a much
 * longer window ({@link #SUSTAINED_VERIFY_TICKS}), re-checking all three facts (DIV registry
 * absence, joint release, native-object destruction) and `towed.towedByConnection != null`/
 * `towed.isValid`/finiteness EVERY tick throughout - not just once at tick 1 - so a result that
 * reverts, flickers, or crashes a few ticks later cannot pass.
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
 private static final int SUSTAINED_VERIFY_TICKS = 100;
 private static final double HALF_EXTENT_X = 0.6, HALF_HEIGHT_Y = 0.4, HALF_EXTENT_Z = 1.0;
 private static final Point3D CHASSIS_CENTER = new Point3D(0, 0.4, 0);
 private static final Point3D FLOAT_LEFT = new Point3D(-0.5, -0.2, 0), FLOAT_RIGHT = new Point3D(0.5, -0.2, 0);
 private static final Point3D HITCH_POS = new Point3D(0, 0, -1.0);
 private static final Point3D HOOKUP_POS = new Point3D(0, 0, 1.0);
 private static final double PLATFORM_BOUND_HORIZONTAL = 60, PLATFORM_BOUND_VERTICAL = 20;
 private static final Logger LOGGER = LogUtils.getLogger();

 private enum Phase { WAIT_TERRAIN, SPAWN, CONFIRM_PREEXISTING_JOINT, REPLACE_WITH_MOUNTED_BUOYANT_TOWING, VERIFY_FALLBACK, DONE, FAILED }
 private static Phase phase = Phase.WAIT_TERRAIN;
 private static int ticksInPhase = 0, terrainWaitTicksElapsed = 0;
 private static double spawnX, spawnZ, spawnSurfaceY;
 private static EntityVehicleF_Physics towing1, towed, towing2;
 private static Object capturedTowedBody;
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
    case CONFIRM_PREEXISTING_JOINT -> confirmPreexistingJoint();
    case REPLACE_WITH_MOUNTED_BUOYANT_TOWING -> replaceWithMountedBuoyantTowing(level);
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
  // towing1 and towed's connection is ORDINARY (mounted=false) - SAFE regardless of both sides
  // being buoyant (unsafeBuoyantAngularLock only fires for mounted/restricted), matching
  // BuoyantHitchProbe's own proven-safe ATTACH pattern - so towed gets a REAL, live joint here.
  var towing1Definition = buildHullDefinition("alreadyactivefallback-towing1", true, false, false);
  var towedDefinition = buildHullDefinition("alreadyactivefallback-towed", false, true, false);
  towing1 = spawnHull(world, towing1Definition, blockX + 0.5, surfaceY + 0.5, blockZ + 0.5, 0);
  towed = spawnHull(world, towedDefinition, blockX + 0.5, surfaceY + 0.5, blockZ - 3.5, 180);
  var connection = new TowingConnection(towing1, 0, 0, towed, 0, 0);
  towing1.connectTrailer(connection, false);
  spawnX = blockX + 0.5; spawnZ = blockZ + 0.5; spawnSurfaceY = surfaceY;
  phase = Phase.CONFIRM_PREEXISTING_JOINT; ticksInPhase = 0;
  LOGGER.info("[DIV alreadyActiveUnsafeFallbackProbe] spawned towing1(buoyant,ordinary)=" + towing1.position + " towed(buoyant)=" + towed.position
    + " connected via an ORDINARY (safe) connection from spawn - beginning CONFIRM_PREEXISTING_JOINT phase");
 }

 private static void confirmPreexistingJoint() {
  ticksInPhase++;
  if (!boundedOrFail("confirmPreexistingJoint/towing1", towing1) || !boundedOrFail("confirmPreexistingJoint/towed", towed)) return;
  if (ticksInPhase < SETTLE_TICKS) return;
  var linear = PhysicsBridge.debugReadLinearVelocity(towed);
  boolean jointed = PhysicsBridge.debugIsJointed(towed);
  capturedTowedBody = PhysicsBridge.debugCaptureBody(towed);
  Boolean boxActive = PhysicsBridge.debugCapturedBoxIsActive(capturedTowedBody);
  if (linear == null || !jointed || capturedTowedBody == null || !Boolean.TRUE.equals(boxActive)) {
   LOGGER.error("[DIV alreadyActiveUnsafeFallbackProbe] FAILED - towed did not settle into a real, already-active, ALREADY-JOINTED Sable body"
     + " (linearVelocity=" + linear + " debugIsJointed=" + jointed + " capturedBody=" + capturedTowedBody + " boxActive=" + boxActive
     + ") - fixture problem, not the fallback question this probe exists to test");
   phase = Phase.FAILED;
   return;
  }
  LOGGER.info("[DIV alreadyActiveUnsafeFallbackProbe] CONFIRM_PREEXISTING_JOINT PASS - towed has a real, active Sable body (linearVelocity=" + linear
    + "), a REAL preexisting joint (debugIsJointed=true), and its captured native box reads active=true, after " + SETTLE_TICKS
    + " ticks safely towed by an ordinary connection - despawning towing1 and reconnecting to a fresh MOUNTED buoyant towing2 for REPLACE_WITH_MOUNTED_BUOYANT_TOWING");
  phase = Phase.REPLACE_WITH_MOUNTED_BUOYANT_TOWING; ticksInPhase = 0;
 }

 private static void replaceWithMountedBuoyantTowing(ServerLevel level) {
  ticksInPhase++;
  if (ticksInPhase == 1) {
   towing1.remove();
   AWrapperWorld world = mcinterface1211.WrapperWorld.getWrapperFor(level);
   var towing2Definition = buildHullDefinition("alreadyactivefallback-towing2", true, false, true);
   towing2 = spawnHull(world, towing2Definition, spawnX, spawnSurfaceY + 0.5, spawnZ + 1.0, 180);
   var connection = new TowingConnection(towing2, 0, 0, towed, 0, 0);
   towing2.connectTrailer(connection, false);
   LOGGER.info("[DIV alreadyActiveUnsafeFallbackProbe] REPLACE_WITH_MOUNTED_BUOYANT_TOWING reconnected ALREADY-ACTIVE, ALREADY-JOINTED towed to fresh"
     + " BUOYANT towing2=" + towing2.position + " on a MOUNTED connection - unsafeLock should now fire while a REAL preexisting joint exists");
  }
  if (!boundedOrFail("replaceWithMountedBuoyantTowing/towed", towed)) return;
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
  boolean stillJointed = PhysicsBridge.debugIsJointed(towed);
  Boolean capturedBoxActive = PhysicsBridge.debugCapturedBoxIsActive(capturedTowedBody);
  boolean towedByConnectionPersists = towed.towedByConnection != null;
  if (ticksInPhase <= 10 || ticksInPhase % 10 == 0 || ticksInPhase == SUSTAINED_VERIFY_TICKS) {
   LOGGER.info("[DIV alreadyActiveUnsafeFallbackProbe] VERIFY_FALLBACK tick=" + ticksInPhase + " towed.linearVelocity=" + linear
     + " towed.angularVelocity=" + angular + " debugIsJointed=" + stillJointed + " capturedBoxActive=" + capturedBoxActive
     + " towed.position=" + towed.position + " towed.towedByConnection!=null=" + towedByConnectionPersists);
  }
  // Every tick of the sustained window, not just once: registry absence, joint release, AND
  // native-object destruction must ALL hold simultaneously, and the vehicle must remain finite/
  // valid with its IV-level connection persisting (sustained native towing) throughout.
  boolean fallbackHoldsThisTick = linear == null && angular == null && !stillJointed && Boolean.FALSE.equals(capturedBoxActive) && towedByConnectionPersists;
  if (!fallbackHoldsThisTick) {
   LOGGER.error("[DIV alreadyActiveUnsafeFallbackProbe] FAILED at tick=" + ticksInPhase + " - fallback did not hold: linearVelocity=" + linear
     + " angularVelocity=" + angular + " debugIsJointed=" + stillJointed + " capturedBoxActive=" + capturedBoxActive
     + " towed.towedByConnection!=null=" + towedByConnectionPersists + " (expected null/null/false/false/true)");
   phase = Phase.FAILED;
   return;
  }
  if (ticksInPhase >= SUSTAINED_VERIFY_TICKS) {
   LOGGER.info("[DIV alreadyActiveUnsafeFallbackProbe] VERIFY_FALLBACK PASS - sustained for " + SUSTAINED_VERIFY_TICKS + " ticks: towed's real native Sable"
     + " body is ACTUALLY REMOVED (not merely unjointed), its PREEXISTING joint is ACTUALLY RELEASED (debugIsJointed=false), its CAPTURED native box"
     + " OBJECT transitioned active->inactive (not inferred from source), and towed.towedByConnection persists (sustained native IV authority) the"
     + " entire window, with towed remaining finite/valid throughout. End-to-end existing-body lifecycle confirmed live, per 54259/54262.");
   phase = Phase.DONE;
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
