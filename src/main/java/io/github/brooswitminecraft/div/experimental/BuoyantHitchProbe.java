package io.github.brooswitminecraft.div.experimental;
import com.mojang.logging.LogUtils;
import io.github.brooswitminecraft.div.PhysicsBridge;
import minecrafttransportsimulator.baseclasses.Point3D;
import minecrafttransportsimulator.baseclasses.RotationMatrix;
import minecrafttransportsimulator.baseclasses.TowingConnection;
import minecrafttransportsimulator.entities.instances.EntityVehicleF_Physics;
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
 * Bounded, opt-in, CI/isolated-host-only runtime probe for MINECRAFT-545 (float-only hull
 * rigid-body hitch support) - the same "real Sable-driven body, Mixin-intercepted tick loop, no
 * mock" discipline {@link BuoyancyAngularDragProbe}/{@link DirectionalWaterDragProbe} already
 * established for the float-only domain, applied here to the hitch lifecycle this ticket's own
 * acceptance criteria name: real attach/anchor-alignment with a rotated/offset initial pose, a
 * towing vehicle going unavailable then being replaced, and despawn/level-unload cleanup, all
 * with BOTH sides of the joint a float-only hull (the exact "float-only towing/attach/detach"
 * scope this ticket is bounded to) - proving {@code HitchAnchor}/{@code BuoyantBody} actually
 * drives through {@code PhysicsBridge.active()}'s real classification/activation/reconcile path,
 * not merely compiling.
 *
 * <p>Method: two synthetic pontoon hulls (`towing`, `towed`), each a real {@code JSONVehicle}
 * with one float-only {@code ground_pontoon} device (reusing {@link BuoyancyAngularDragProbe}'s
 * own fixture-construction pattern) plus one connection group apiece - {@code isHitch} on
 * `towing`, {@code isHookup} on `towed`. The connection is deliberately ORDINARY (unmounted,
 * unrestricted) - {@link HitchJoint}'s own doc names this as the realistic case for a towed
 * vehicle whose OWN suspension (here, buoyancy) keeps it upright, not the joint; a FIRST version
 * of this probe used a {@code restricted} connection with a non-identity {@code rot} instead, to
 * also exercise {@code HitchJoint.lockedAxes}'s yaw/roll lock and the MINECRAFT-525 rotation-sign
 * convention - live evidence (this exact probe, first run) showed that LOCKING yaw/roll between
 * TWO independently-buoyant hulls (each with its own righting torque fighting the lock) is a
 * genuine runaway instability: anchor distance settled cleanly below 0.003 blocks by tick 40,
 * then diverged to 35+ blocks by tick 49, eventually crashing {@code BuoyantBody.step} with a
 * {@code NullPointerException} once the ejected body left Sable's own active region. That
 * specific mounted/restricted-joint-stability-for-a-buoyant-pair question is real but OUT OF
 * this ticket's bounded scope ("float-only towing/attach/detach", not a general stability
 * audit) - filed as a follow-up candidate, not chased down here; the rotation-sign convention
 * itself is already covered, domain-agnostically, by {@link HitchRotationFrameProbe}/
 * {@link InstalledPartHitchFrameProbe} (neither depends on which {@code HitchAnchor}
 * implementation either side is). `towed` is still spawned already yawed 180+20 degrees (facing
 * back toward `towing`, with a deliberate skew) and offset so neither anchor starts already
 * coincident - the same "must visibly settle into the constraint, not start already satisfied"
 * discipline {@link HitchRotationFrameProbe} uses - this still exercises non-trivial, non-zero,
 * non-aligned anchor offsets converging under the ordinary connection's own always-locked linear
 * axes, exactly the position-anchor math {@code HitchJoint.anchorLocalOffset} performs. The
 * actual joint is wired with the
 * real {@code AEntityG_Towable.connectTrailer}/{@code disconnectTrailer} API (the same calls a
 * real in-game hitch GUI action performs), never a DIV-internal shortcut - this probe
 * only ever reads each vehicle's own PUBLIC {@code position}/{@code orientation}/{@code motion}
 * fields, the same black-box discipline every existing probe already uses (never touches
 * {@code BuoyantBody}/{@code HitchAnchor} directly - both are package-private to
 * {@code io.github.brooswitminecraft.div}, not this {@code .experimental} subpackage, by
 * design). "Anchor alignment" is measured by independently computing each side's own anchor
 * world point from its OWN public pose plus its OWN {@code JSONConnection.pos}, rotated via
 * {@code Point3D.rotate} - the exact formula {@code EntityVehicleF_Physics}'s native towed-
 * position-snap code itself uses - and comparing the two computed points' distance, never by
 * reaching into any DIV-internal anchor field.
 *
 * <p>Phases, all on the SAME pair (minimizing separate spawns/server boots for this ticket's
 * several distinct acceptance items):
 * <ul>
 *  <li>{@code ATTACH} - connect, then settle; anchor-point distance must shrink to, and stay
 *  within, {@link #ANCHOR_TOLERANCE} - real attach plus the rotated/offset initial pose settling
 *  under the joint's own always-locked linear axes, in one measurement.</li>
 *  <li>{@code DETACH} - {@code towing.disconnectTrailer(0)}; `towed.towedByConnection` must
 *  become null (asserted explicitly, not merely logged - a prior draft of this probe logged the
 *  field without checking it, and used {@code disconnectAllConnections()} instead, which is
 *  documented as the OWN-removal cleanup path and does NOT clear a trailer's own
 *  {@code towedByConnection}), and `towed` must keep behaving (finite, bounded) as an
 *  independent, unjointed Sable body - no crash, no frozen/NaN state.</li>
 *  <li>{@code TOWING_UNAVAILABLE} - reconnect, then despawn `towing` itself while `towed` stays
 *  jointed; `towed` must remain active, finite and NOT crash the tick loop - the documented
 *  "already-active trailer stays an unjointed, free-rolling Sable vehicle" contract
 *  ({@code PhysicsBridge.deferToNativeHitch}'s own doc) extended to a float-only trailer.</li>
 *  <li>{@code TOWING_REPLACEMENT} - spawn a FRESH towing hull (`towing2`) and connect `towed` to
 *  it; the anchor distance must shrink again exactly like the first {@code ATTACH} phase did -
 *  proving no stale handle from the despawned original blocks a fresh joint.</li>
 *  <li>{@code DESPAWN_TRAILER} - despawn `towed` itself while jointed to `towing2`; no crash for
 *  the remaining window.</li>
 *  <li>{@code LEVEL_UNLOAD} - direct {@code PhysicsBridge.unload(level)} call (the exact sweep a
 *  real chunk/level unload triggers) against the already-mostly-despawned fixture; no crash.</li>
 * </ul>
 * Any unexpected exception in any phase, or the anchor distance failing to shrink within
 * {@link #SETTLE_TICKS} of an attach, fails the whole probe (logged {@code FAILED}) - matching
 * {@link BuoyancyAngularDragProbe}'s "do not report a pass solely because the server exits"
 * review discipline.
 *
 * <p>Opt-in via {@code -Ddiv.experimentalBuoyantHitch=true} - OFF by default, never runs during
 * normal play, never touches any real vehicle, Zippy, or world content. Runs ONCE per server
 * lifetime, for a bounded window, then stops.
 *
 * <p><b>Requires an explicit {@code massKg} override seeded in the config file for all THREE
 * fixture vehicles</b> ({@code divtest:buoyanthitch-towing}, {@code divtest:buoyanthitch-towed},
 * {@code divtest:buoyanthitch-towing2}) - the same lesson {@link BuoyancyAngularDragProbe}'s own
 * doc states, for the same reason (an unconfigured near-massless synthetic hull launches off the
 * water before any hitch behavior can be observed).
 */
public final class BuoyantHitchProbe {
 private BuoyantHitchProbe() {}
 private static final boolean ENABLED = Boolean.getBoolean("div.experimentalBuoyantHitch");
 private static final String PACK_ID = "divtest";
 private static final int CHUNK_X = 1100, CHUNK_Z = 1100; // far clear of every other probe's own fixed coordinates
 private static final int MAX_TERRAIN_WAIT_TICKS = 200;
 private static final int CHUNK_RADIUS = 3;
 private static final int PLATFORM_Y = 100;
 private static final int POOL_RADIUS = 14;
 private static final int WATER_DEPTH = 5;
 private static final int SETTLE_TICKS = 140; // 7s
 private static final int SHORT_PHASE_TICKS = 60; // 3s - detach/unavailable/despawn/unload windows
 private static final double HALF_EXTENT_X = 0.6, HALF_HEIGHT_Y = 0.3, HALF_EXTENT_Z = 1.0;
 private static final Point3D CHASSIS_CENTER = new Point3D(0, 0.3, 0);
 private static final Point3D FLOAT_LEFT = new Point3D(-0.5, -0.2, 0);
 private static final Point3D FLOAT_RIGHT = new Point3D(0.5, -0.2, 0);
 private static final Point3D HITCH_POS = new Point3D(0, 0, -1.0); // towing's own rear
 private static final Point3D HOOKUP_POS = new Point3D(0, 0, 1.0); // towed's own front
 private static final double HOOKUP_YAW_DEGREES = 20.0; // extra skew on top of the 180deg facing-back spawn - a deliberately non-aligned initial pose
 private static final double PLATFORM_BOUND_HORIZONTAL = 60, PLATFORM_BOUND_VERTICAL = 20;
 private static final double ANCHOR_TOLERANCE = 0.75; // blocks - loose enough to tolerate settling jitter, tight enough to prove real coupling
 private static final Logger LOGGER = LogUtils.getLogger();

 private enum Phase { WAIT_TERRAIN, SPAWN, ATTACH, DETACH, TOWING_UNAVAILABLE, TOWING_REPLACEMENT, DESPAWN_TRAILER, LEVEL_UNLOAD, DONE, FAILED }

 private static Phase phase = Phase.WAIT_TERRAIN;
 private static int ticksInPhase = 0, terrainWaitTicksElapsed = 0;
 private static double spawnX, spawnZ, spawnSurfaceY;
 private static EntityVehicleF_Physics towing, towed, towing2;
 private static JSONVehicle towingDefinition, towedDefinition, towing2Definition;
 private static boolean configured = false, chunkGridUnforced = false;

 public static void maybeRun(ServerLevel level) {
  if (!ENABLED) return;
  if (!configured) {
   configured = true;
   requestChunkGrid(level);
   LOGGER.info("[DIV buoyantHitchProbe] requested terrain chunk grid");
   return;
  }
  try {
   switch (phase) {
    case WAIT_TERRAIN -> waitTerrain(level);
    case SPAWN -> spawn(level);
    case ATTACH -> attach();
    case DETACH -> detach();
    case TOWING_UNAVAILABLE -> towingUnavailable();
    case TOWING_REPLACEMENT -> towingReplacement(level);
    case DESPAWN_TRAILER -> despawnTrailer();
    case LEVEL_UNLOAD -> levelUnload(level);
    case DONE, FAILED -> {}
   }
  } catch (RuntimeException ex) {
   LOGGER.error("[DIV buoyantHitchProbe] FAILED - unexpected exception in phase " + phase, ex);
   phase = Phase.FAILED;
  }
  // Release the chunk-force grid this probe's own setup requested once there is nothing left
  // to run (DONE or FAILED) - every phase transition above still needs the grid loaded, so this
  // can only run exactly once, at the very end, regardless of which terminal state is reached.
  if ((phase == Phase.DONE || phase == Phase.FAILED) && !chunkGridUnforced) {
   chunkGridUnforced = true;
   unforceChunkGrid(level);
   LOGGER.info("[DIV buoyantHitchProbe] released terrain chunk grid");
  }
 }

 private static void requestChunkGrid(ServerLevel level) {
  for (int dx = -CHUNK_RADIUS; dx <= CHUNK_RADIUS; dx++) for (int dz = -CHUNK_RADIUS; dz <= CHUNK_RADIUS; dz++) level.setChunkForced(CHUNK_X + dx, CHUNK_Z + dz, true);
 }

 private static void unforceChunkGrid(ServerLevel level) {
  for (int dx = -CHUNK_RADIUS; dx <= CHUNK_RADIUS; dx++) for (int dz = -CHUNK_RADIUS; dz <= CHUNK_RADIUS; dz++) level.setChunkForced(CHUNK_X + dx, CHUNK_Z + dz, false);
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
    LOGGER.error("[DIV buoyantHitchProbe] FAILED - terrain chunk grid never finished loading");
    phase = Phase.FAILED;
   }
   return;
  }
  phase = Phase.SPAWN;
 }

 private static void buildPlatform(ServerLevel level, int centerX, int centerZ) {
  var stone = net.minecraft.world.level.block.Blocks.STONE.defaultBlockState();
  var water = net.minecraft.world.level.block.Blocks.WATER.defaultBlockState();
  var air = net.minecraft.world.level.block.Blocks.AIR.defaultBlockState();
  for (int dx = -POOL_RADIUS; dx <= POOL_RADIUS; dx++) {
   for (int dz = -POOL_RADIUS; dz <= POOL_RADIUS; dz++) {
    level.setBlock(new BlockPos(centerX + dx, PLATFORM_Y, centerZ + dz), stone, 3);
    for (int dy = 1; dy <= WATER_DEPTH; dy++) level.setBlock(new BlockPos(centerX + dx, PLATFORM_Y + dy, centerZ + dz), water, 3);
    for (int dy = WATER_DEPTH + 1; dy <= WATER_DEPTH + 6; dy++) level.setBlock(new BlockPos(centerX + dx, PLATFORM_Y + dy, centerZ + dz), air, 3);
   }
  }
 }

 private static void spawn(ServerLevel level) {
  int blockX = CHUNK_X * 16 + 8, blockZ = CHUNK_Z * 16 + 8;
  buildPlatform(level, blockX, blockZ);
  double surfaceY = PLATFORM_Y + WATER_DEPTH;
  var packSpeedFactors = ConfigSystem.settings.general.packSpeedFactors.value;
  var packVehicleScales = ConfigSystem.settings.general.packVehicleScales.value;
  packSpeedFactors.putIfAbsent(PACK_ID, 1.0);
  packVehicleScales.putIfAbsent(PACK_ID, 1.0);

  AWrapperWorld world = mcinterface1211.WrapperWorld.getWrapperFor(level);
  towingDefinition = buildHullDefinition("buoyanthitch-towing", true, false);
  towedDefinition = buildHullDefinition("buoyanthitch-towed", false, true);
  towing = spawnHull(world, towingDefinition, blockX + 0.5, surfaceY + 0.5, blockZ + 0.5, 0);
  // towed starts behind towing, facing back toward it (180 deg) plus the connection's own
  // 20deg rot skew - neither anchor starts coincident, so the joint must visibly settle it.
  towed = spawnHull(world, towedDefinition, blockX + 0.5, surfaceY + 0.5, blockZ - 3.5, 180 + HOOKUP_YAW_DEGREES);

  var connection = new TowingConnection(towing, 0, 0, towed, 0, 0);
  towing.connectTrailer(connection, false);

  spawnX = blockX + 0.5; spawnZ = blockZ + 0.5; spawnSurfaceY = surfaceY;
  phase = Phase.ATTACH; ticksInPhase = 0;
  LOGGER.info("[DIV buoyantHitchProbe] spawned towing=" + towing.position + " towed=" + towed.position
    + " key.towing=" + PACK_ID + ":buoyanthitch-towing key.towed=" + PACK_ID + ":buoyanthitch-towed - beginning ATTACH phase");
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
  addFloat(vehicle, floatSlotLeft);
  addFloat(vehicle, floatSlotRight);
  return vehicle;
 }

 private static JSONVehicle buildHullDefinition(String systemName, boolean hitch, boolean hookup) {
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
  // Ordinary (unmounted, unrestricted) connection, deliberately - see this class's own doc
  // "scope correction" note: a `restricted` lock between TWO independently-buoyant hulls (each
  // with its own righting torque fighting the joint's yaw/roll lock) produced a genuine runaway
  // instability in this probe's own first live run (confirmed: settled cleanly to
  // anchorDistance<0.003 by tick 40, then diverged to 35+ blocks away by tick 49) - exactly the
  // real ball/pintle-hitch case HitchJoint's own doc already names as the realistic default for
  // a towed vehicle whose OWN buoyancy (not the joint) keeps it upright, and the one this
  // ticket's own anchor-generalization is actually about; `rot`/mounted/restricted joint
  // stability for a buoyant-pair specifically is a separate, out-of-scope question (not
  // "float-only towing/attach/detach" as this ticket bounds it) - filed as a follow-up
  // candidate rather than chased down here.
  if (hitch) v.connectionGroups = List.of(connectionGroup(true, HITCH_POS, null, false, false));
  if (hookup) v.connectionGroups = List.of(connectionGroup(false, HOOKUP_POS, null, false, false));
  return v;
 }

 private static JSONConnectionGroup connectionGroup(boolean isHitch, Point3D pos, RotationMatrix rot, boolean mounted, boolean restricted) {
  var connection = new JSONConnection();
  connection.type = "hitch";
  connection.pos = pos.copy();
  connection.rot = rot;
  connection.mounted = mounted;
  connection.restricted = restricted;
  connection.distance = 4; // generous - this probe always connects explicitly, never relies on auto-snap distance
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
  var part = new minecrafttransportsimulator.entities.instances.PartGroundDevice(vehicle, null, slot, item, null);
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
   LOGGER.error("[DIV buoyantHitchProbe] FAILED - non-finite state tag=" + tag + " phase=" + phase + " tick=" + ticksInPhase + " position=" + v.position);
   phase = Phase.FAILED;
   return false;
  }
  double dx = v.position.x - spawnX, dz = v.position.z - spawnZ;
  double horizontal = Math.sqrt(dx * dx + dz * dz), vertical = Math.abs(v.position.y - spawnSurfaceY);
  if (horizontal > PLATFORM_BOUND_HORIZONTAL || vertical > PLATFORM_BOUND_VERTICAL) {
   LOGGER.error("[DIV buoyantHitchProbe] FAILED - left bounded area tag=" + tag + " phase=" + phase + " tick=" + ticksInPhase
     + " horizontal=" + horizontal + " vertical=" + vertical + " position=" + v.position);
   phase = Phase.FAILED;
   return false;
  }
  return true;
 }

 /** The same anchor-world-point formula EntityVehicleF_Physics's own native towed-position-snap
  * code uses (connection.pos, rotated by the owning vehicle's own orientation, plus its own
  * position) - computed here from only PUBLIC fields, never a DIV-internal anchor. */
 private static Point3D anchorWorld(EntityVehicleF_Physics vehicle, JSONConnection connection) {
  return connection.pos.copy().rotate(vehicle.orientation).add(vehicle.position);
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
   LOGGER.info("[DIV buoyantHitchProbe] ATTACH tick=" + ticksInPhase + " anchorDistance=" + distance
     + " towingPos=" + towing.position + " towedPos=" + towed.position);
  }
  if (ticksInPhase >= SETTLE_TICKS) {
   if (distance > ANCHOR_TOLERANCE) {
    LOGGER.error("[DIV buoyantHitchProbe] FAILED - ATTACH anchorDistance=" + distance + " did not settle within tolerance=" + ANCHOR_TOLERANCE);
    phase = Phase.FAILED;
    return;
   }
   LOGGER.info("[DIV buoyantHitchProbe] ATTACH PASS anchorDistance=" + distance + " (settled from a rotated/offset initial pose) - disconnecting");
   // disconnectTrailer(0) (not disconnectAllConnections(), which is documented as the OWN-
   // removal cleanup path and does NOT clear the TRAILER's own towedByConnection - confirmed
   // against the real AEntityG_Towable source) actually clears towed.towedByConnection, the
   // same real in-game "detach" action performs.
   towing.disconnectTrailer(0);
   phase = Phase.DETACH; ticksInPhase = 0;
  }
 }

 private static void detach() {
  ticksInPhase++;
  if (!boundedOrFail("detach/towing", towing) || !boundedOrFail("detach/towed", towed)) return;
  if (ticksInPhase >= SHORT_PHASE_TICKS) {
   if (towed.towedByConnection != null) {
    LOGGER.error("[DIV buoyantHitchProbe] FAILED - DETACH towed.towedByConnection is still non-null " + SHORT_PHASE_TICKS + " ticks after disconnectTrailer(0): " + towed.towedByConnection);
    phase = Phase.FAILED;
    return;
   }
   LOGGER.info("[DIV buoyantHitchProbe] DETACH PASS - towed.towedByConnection confirmed null, both hulls remained finite/bounded " + SHORT_PHASE_TICKS
     + " ticks after disconnectTrailer(0) - despawning towing for TOWING_UNAVAILABLE");
   var connection = new TowingConnection(towing, 0, 0, towed, 0, 0);
   towing.connectTrailer(connection, false);
   phase = Phase.TOWING_UNAVAILABLE; ticksInPhase = 0;
  }
 }

 private static void towingUnavailable() {
  ticksInPhase++;
  if (ticksInPhase == 1) {
   LOGGER.info("[DIV buoyantHitchProbe] TOWING_UNAVAILABLE despawning towing while towed remains jointed");
   towing.remove();
  }
  if (!boundedOrFail("towingUnavailable/towed", towed)) return;
  if (ticksInPhase >= SHORT_PHASE_TICKS) {
   LOGGER.info("[DIV buoyantHitchProbe] TOWING_UNAVAILABLE PASS - towed stayed finite/bounded " + SHORT_PHASE_TICKS
     + " ticks after its towing vehicle despawned - spawning towing2 for TOWING_REPLACEMENT");
   phase = Phase.TOWING_REPLACEMENT; ticksInPhase = 0;
  }
 }

 private static void towingReplacement(ServerLevel level) {
  ticksInPhase++;
  if (ticksInPhase == 1) {
   AWrapperWorld world = mcinterface1211.WrapperWorld.getWrapperFor(level);
   towing2Definition = buildHullDefinition("buoyanthitch-towing2", true, false);
   // Spawn towing2 clearly separated from `towed` (same ~4-block clearance the original
   // towing/towed spawn used, well beyond both hulls' combined half-extents - HALF_EXTENT_Z is
   // only 1.0 each) along the REAL direction toward towed's CURRENT hookup anchor, then let the
   // joint settle it exactly like the original ATTACH phase already proved it can.
   //
   // Two earlier versions of this probe got this wrong, both confirmed live by this exact
   // probe: (1) a fixed towed.position+(0,0,3.5) offset at towing2's own identity orientation
   // ignored that `towed` had drifted/settled at its own spawn-time yawed orientation (nothing
   // corrects yaw for an ordinary, unrestricted connection) during the free-floating
   // DETACH/TOWING_UNAVAILABLE window, so the two sides' anchors started several blocks apart
   // in the WRONG relative direction; (2) spawning towing2 at the EXACT anchor-coincident point
   // (desiredAnchor - HITCH_POS) put its own CENTER under 2 blocks from towed's center -
   // overlapping both hulls' collision boxes at spawn. Either mistake produced the identical
   // observed symptom: anchorDistance settled cleanly below 0.003 blocks by tick 40, then a
   // sudden runaway divergence to 35+ blocks by tick 49, ending in a BuoyantBody.step
   // NullPointerException once the ejected body left Sable's own active region.
   Point3D towedHookupAnchor = anchorWorld(towed, towedDefinition.connectionGroups.get(0).connections.get(0));
   Point3D towardHookupAnchor = towedHookupAnchor.copy().subtract(towed.position).normalize();
   Point3D towing2Spawn = towed.position.copy().addScaled(towardHookupAnchor, 4.0);
   towing2 = spawnHull(world, towing2Definition, towing2Spawn.x, towing2Spawn.y, towing2Spawn.z, 0);
   var connection = new TowingConnection(towing2, 0, 0, towed, 0, 0);
   towing2.connectTrailer(connection, false);
   LOGGER.info("[DIV buoyantHitchProbe] TOWING_REPLACEMENT connected towed to fresh towing2=" + towing2.position
     + " (spawned 4 blocks toward towedHookupAnchor=" + towedHookupAnchor + ", clear of both hulls' collision boxes)");
  }
  if (!boundedOrFail("towingReplacement/towing2", towing2) || !boundedOrFail("towingReplacement/towed", towed)) return;
  double distance = anchorDistance(towing2, towing2Definition);
  if (ticksInPhase % 20 == 0 || ticksInPhase == SETTLE_TICKS) {
   LOGGER.info("[DIV buoyantHitchProbe] TOWING_REPLACEMENT tick=" + ticksInPhase + " anchorDistance=" + distance);
  }
  if (ticksInPhase >= SETTLE_TICKS) {
   if (distance > ANCHOR_TOLERANCE) {
    LOGGER.error("[DIV buoyantHitchProbe] FAILED - TOWING_REPLACEMENT anchorDistance=" + distance + " did not settle within tolerance=" + ANCHOR_TOLERANCE);
    phase = Phase.FAILED;
    return;
   }
   LOGGER.info("[DIV buoyantHitchProbe] TOWING_REPLACEMENT PASS anchorDistance=" + distance + " - despawning towed for DESPAWN_TRAILER");
   towed.remove();
   phase = Phase.DESPAWN_TRAILER; ticksInPhase = 0;
  }
 }

 private static void despawnTrailer() {
  ticksInPhase++;
  if (!boundedOrFail("despawnTrailer/towing2", towing2)) return;
  if (ticksInPhase >= SHORT_PHASE_TICKS) {
   LOGGER.info("[DIV buoyantHitchProbe] DESPAWN_TRAILER PASS - towing2 stayed finite/bounded " + SHORT_PHASE_TICKS
     + " ticks after the trailer it was towing despawned - exercising PhysicsBridge.unload(level) for LEVEL_UNLOAD");
   phase = Phase.LEVEL_UNLOAD; ticksInPhase = 0;
  }
 }

 private static void levelUnload(ServerLevel level) {
  ticksInPhase++;
  if (ticksInPhase == 1) PhysicsBridge.unload(level);
  if (!boundedOrFail("levelUnload/towing2", towing2)) return;
  if (ticksInPhase >= SHORT_PHASE_TICKS) {
   LOGGER.info("[DIV buoyantHitchProbe] LEVEL_UNLOAD PASS - PhysicsBridge.unload(level) and " + SHORT_PHASE_TICKS + " subsequent ticks raised no exception");
   phase = Phase.DONE;
   LOGGER.info("[DIV buoyantHitchProbe] COMPLETE - ATTACH/DETACH/TOWING_UNAVAILABLE/TOWING_REPLACEMENT/DESPAWN_TRAILER/LEVEL_UNLOAD all passed");
  }
 }
}
