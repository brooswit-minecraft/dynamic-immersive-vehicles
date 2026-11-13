package io.github.brooswitminecraft.div.experimental;
import com.mojang.logging.LogUtils;
import minecrafttransportsimulator.baseclasses.Point3D;
import minecrafttransportsimulator.baseclasses.RotationMatrix;
import minecrafttransportsimulator.entities.instances.EntityVehicleF_Physics;
import minecrafttransportsimulator.items.instances.ItemPartGroundDevice;
import minecrafttransportsimulator.items.instances.ItemVehicle;
import minecrafttransportsimulator.jsondefs.JSONCollisionBox;
import minecrafttransportsimulator.jsondefs.JSONCollisionGroup;
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
 * Bounded, opt-in, CI/isolated-host-only runtime probe answering review (Jira comment 53675/
 * 53676/53679/MINECRAFT-518): {@code BuoyancyTest}/{@code OverridesTest} prove {@code
 * Buoyancy.angularDragRetention} and its config plumbing are correct as PURE functions, but
 * nothing exercises {@code BuoyantForces.apply}'s actual production application of that
 * retention against a real Sable-driven {@code BuoyantBody} - the exact gap {@link
 * GroundSupportFixtureProbe} already closed for the ground/wheel case. This is that same pattern
 * (real {@code JSONVehicle}/{@code JSONPart}/{@code ItemVehicle}/{@code ItemPartGroundDevice}
 * in-process construction, spawned via {@code AWrapperWorld.spawnEntity}, driven by the real
 * Mixin-intercepted tick loop - no mock, no stand-in) applied to a FLOAT-ONLY hull instead of a
 * wheeled one: one {@code PartGroundDevice} with {@code canFloat=true} (not {@code isWheel}/
 * {@code isTread}) and no engine at all, so {@code Classification.floatEligible} routes it to the
 * real (package-private, so observed only through {@code EntityVehicleF_Physics}'s own public
 * fields, same black-box discipline every existing probe already uses) {@code BuoyantBody}.
 *
 * <p>Method: three scenarios, each its own real vehicle spawned pre-TILTED (a known 25-degree
 * roll, set directly on the vehicle's own public {@code orientation} field BEFORE it is ever
 * spawned/activated - the only point at which writing to that field has any effect, since once
 * {@code BuoyantBody} is active it treats {@code orientation} as Sable's own OUTPUT, overwriting
 * it every tick; this is also why there is no need - or any available API - to inject an initial
 * angular velocity directly: letting real buoyancy right a real pre-tilted hull is both how this
 * field is actually used in practice (settling after a disturbance) and the only initial-rotation
 * mechanism reachable through {@code EntityVehicleF_Physics}'s own public surface):
 * <ul>
 *  <li>{@code off} - spawned over a real carved water pool, no {@code angularWaterDrag} override
 *  (documented 0.0 fallback) - the baseline, exact-prior-behavior roll-settling trajectory.</li>
 *  <li>{@code on} - identical water pool and starting tilt, but with an explicit, large
 *  {@code angularWaterDrag} override - if {@code BuoyantForces.apply} is actually applying the
 *  retention in production, this scenario's cumulative rotational activity (see {@link
 *  #logTick}) over the same measurement window must come out LOWER than {@code off}'s.</li>
 *  <li>{@code beached} - the SAME large override as {@code on}, but spawned on a plain stone
 *  platform with no water anywhere nearby - review's explicit "unsubmerged/default cases are
 *  no-ops" ask: with no float device ever submerged, {@code BuoyantForces.apply}'s own {@code
 *  averageFraction>0} gate must never engage the retention at all, so this scenario's roll
 *  trajectory should settle under ordinary gravity/collision alone, not attenuated rotation.</li>
 *  <li>{@code beachedOff} - review follow-up (comment 53701): "beached case without a matched
 *  zero-drag control cannot alone establish unchanged behavior". The SAME stone platform, no
 *  water, but NO {@code angularWaterDrag} override (the 0.0 fallback) - a true zero-drag control
 *  for {@code beached} to be compared against, not merely observed alone.</li>
 * </ul>
 * All four are checked every tick for finiteness and a bounded position (same discipline as
 * {@code GroundSupportFixtureProbe}'s own {@code checkFiniteOrFail}) - review's explicit "state
 * remains finite/stable" ask. {@link #maybeLogComparison} turns both comparisons into actual
 * FAILED-or-not gates (review follow-up: "do not report a pass solely because the server exits"),
 * not merely informational log lines, and each scenario's own ACTUAL RESOLVED
 * {@code angularWaterDrag} coefficient (via the same public {@code Overrides.resolved} every real
 * vehicle uses, not merely the {@code highDrag} label) is logged at spawn time (review follow-up:
 * "explicitly record actual resolved off/on coefficients").
 *
 * <p><b>Disclosed, deliberate scope limit (acknowledged explicitly acceptable by review, comment
 * 53701: "add the focused reload check if practical, otherwise keep that limitation distinct from
 * code tests"):</b> this probe does NOT exercise the config hot-reload path (review's "verify
 * live-safe reload takes effect") - {@code angularWaterDrag} is read fresh from the config file
 * ONLY at mod init for these fixture vehicles (same constraint {@code GroundSupportFixtureProbe}
 * itself documents for its own config), and driving an actual on-disk edit, waiting out the
 * server's own file-poll interval, and re-perturbing an already-active hull mid-run is
 * independent additional complexity. That specific path is instead covered by {@code
 * PhysicsBridgeLiveOverrideApplicationTest} (confirms {@code angularWaterDrag} is one of {@code
 * applyLiveSafeFields}'s mutated-in-place fields, i.e. that {@code BuoyantForces.apply} re-reads
 * the SAME live {@code Tuning} object every tick) at the unit level - not a production-path gap
 * this probe leaves silently undisclosed.
 *
 * <p><b>Independent BuoyantBody activation evidence (review follow-up 53701):</b> this probe's
 * own log lines alone cannot distinguish DIV's real {@code BuoyantBody} actually driving the
 * observed motion from some other fallback coincidentally producing similar-looking motion. Run
 * with {@code -Ddiv.debugGroundContact=true} (this probe's own {@code build.gradle} gate always
 * sets it, same as {@code GroundSupportFixtureProbe}'s) and cross-check the EXISTING, already-
 * shipped {@code GroundContactDiagnostics} log lines for each scenario's own {@code key}: {@code
 * classify ... key=divtest:boatdrag-on floating=true} and {@code constructed ... key=divtest:
 * boatdrag-on class=BuoyantBody} - the same independent activation-evidence pattern {@code
 * GroundSupportFixtureProbe}'s own CI job already established for the ground/wheel case.
 *
 * <p>Opt-in via {@code -Ddiv.experimentalBuoyancyAngularDrag=true} - OFF by default, never runs
 * during normal play, never touches any real vehicle, Zippy, or world content. Runs ONCE per
 * server lifetime, for a bounded window, then stops.
 *
 * <p><b>Requires an explicit {@code massKg} override seeded in the config file for ALL THREE
 * test vehicles</b> (same lesson {@code GroundSupportFixtureProbe}'s own doc already states, for
 * exactly the same reason): confirmed live, a first run with no {@code massKg} override left mass
 * at {@code Math.max(50, v.getMass())} - effectively a near-massless synthetic hull for a ~0.7m^3
 * displacement - and both water scenarios launched off the carved pool within 4-6 ticks under
 * their own real buoyant force, well past {@link #PLATFORM_BOUND_VERTICAL}. A ~400kg override
 * reproduces a plausible small-boat mass and settles normally; see VERIFICATION.md's own
 * "Configurable boat angular water drag" section for the measured results this produced.
 */
public final class BuoyancyAngularDragProbe {
 private BuoyancyAngularDragProbe() {}
 private static final boolean ENABLED = Boolean.getBoolean("div.experimentalBuoyancyAngularDrag");
 private static final String PACK_ID = "divtest";
 private static final int MAX_TERRAIN_WAIT_TICKS = 200; // 10s
 private static final int CHUNK_RADIUS = 3; // a 7x7 chunk square (112 blocks/side) - ample for this stationary-hull scenario
 private static final int PLATFORM_Y = 100;
 private static final int POOL_RADIUS = 10;
 private static final int WATER_DEPTH = 5; // blocks of water above the floor
 private static final int SETTLE_TICKS = 140; // 7s - long enough for a real roll-settling trajectory to play out
 // A representative small pontoon-style hull - low and long, two float devices (left/right)
 // so buoyant force distributes and genuinely holds/rights the hull, not a single symmetric point.
 private static final double HALF_EXTENT_X = 0.6, HALF_HEIGHT_Y = 0.3, HALF_EXTENT_Z = 1.0;
 private static final Point3D CHASSIS_CENTER = new Point3D(0, 0.3, 0);
 private static final Point3D FLOAT_LEFT = new Point3D(-0.5, -0.2, 0);
 private static final Point3D FLOAT_RIGHT = new Point3D(0.5, -0.2, 0);
 private static final double INITIAL_ROLL_DEGREES = 25.0;
 // Bounded, nonzero thresholds (same discipline GroundSupportFixtureProbe's own review history
 // established) - loose enough to stay robust to tuning changes, tight enough to catch "nothing
 // physically happened at all".
 private static final double PLATFORM_BOUND_HORIZONTAL = 40; // blocks from spawn
 private static final double PLATFORM_BOUND_VERTICAL = 20; // blocks above/below spawn surface
 private static final Logger LOGGER = LogUtils.getLogger();

 private enum Phase { WAIT_TERRAIN, SPAWNING, MEASURE, DONE, FAILED }

 private static final class Scenario {
  final String label, systemName;
  final int chunkX, chunkZ;
  final boolean water, highDrag;
  EntityVehicleF_Physics vehicle;
  Phase phase = Phase.WAIT_TERRAIN;
  int ticksInPhase = 0, terrainWaitTicksElapsed = 0;
  double spawnX, spawnZ, spawnSurfaceY;
  double lastRollDegrees, cumulativeAbsRollDelta = 0, maxAbsRoll = 0;
  Scenario(String label, String systemName, int chunkX, int chunkZ, boolean water, boolean highDrag) {
   this.label = label; this.systemName = systemName; this.chunkX = chunkX; this.chunkZ = chunkZ;
   this.water = water; this.highDrag = highDrag;
  }
 }

 private static Scenario off, on, beached, beachedOff;
 private static Scenario[] all;
 private static boolean configured = false;

 public static void maybeRun(ServerLevel level) {
  if (!ENABLED) return;
  if (!configured) {
   configured = true;
   // Far apart, chunk-aligned (+512 blocks = +32 chunks between each), well clear of any other
   // probe's own fixed coordinates.
   off = new Scenario("off", "boatdrag-off", 900, 900, true, false);
   on = new Scenario("on", "boatdrag-on", 900, 932, true, true);
   beached = new Scenario("beached", "boatdrag-beached", 900, 964, false, true);
   beachedOff = new Scenario("beachedOff", "boatdrag-beached-off", 900, 996, false, false);
   all = new Scenario[]{off, on, beached, beachedOff};
   for (Scenario s : all) requestChunkGrid(level, s);
   LOGGER.info("[DIV buoyancyAngularDrag] requested terrain chunk grids for off/on/beached/beachedOff scenarios");
   return;
  }
  for (Scenario s : all) tick(level, s);
 }

 private static void requestChunkGrid(ServerLevel level, Scenario s) {
  for (int dx = -CHUNK_RADIUS; dx <= CHUNK_RADIUS; dx++) {
   for (int dz = -CHUNK_RADIUS; dz <= CHUNK_RADIUS; dz++) level.setChunkForced(s.chunkX + dx, s.chunkZ + dz, true);
  }
 }

 private static boolean chunkGridLoaded(ServerLevel level, Scenario s) {
  var chunkSource = level.getChunkSource();
  for (int dx = -CHUNK_RADIUS; dx <= CHUNK_RADIUS; dx++) {
   for (int dz = -CHUNK_RADIUS; dz <= CHUNK_RADIUS; dz++) {
    if (chunkSource.getChunkNow(s.chunkX + dx, s.chunkZ + dz) == null) return false;
   }
  }
  return true;
 }

 private static void tick(ServerLevel level, Scenario s) {
  try {
   switch (s.phase) {
    case WAIT_TERRAIN -> waitTerrain(level, s);
    case SPAWNING -> spawn(level, s);
    case MEASURE -> measure(s);
    case DONE, FAILED -> {}
   }
  } catch (RuntimeException ex) {
   LOGGER.error("[DIV buoyancyAngularDrag] scenario=" + s.label + " FAILED - unexpected exception in phase " + s.phase, ex);
   s.phase = Phase.FAILED;
  }
 }

 private static void waitTerrain(ServerLevel level, Scenario s) {
  s.terrainWaitTicksElapsed++;
  if (!chunkGridLoaded(level, s)) {
   if (s.terrainWaitTicksElapsed > MAX_TERRAIN_WAIT_TICKS) {
    LOGGER.error("[DIV buoyancyAngularDrag] scenario=" + s.label + " FAILED - terrain chunk grid never finished loading");
    s.phase = Phase.FAILED;
   }
   return;
  }
  s.phase = Phase.SPAWNING;
 }

 private static void buildPlatform(ServerLevel level, int centerX, int centerZ, boolean water) {
  var stone = net.minecraft.world.level.block.Blocks.STONE.defaultBlockState();
  var waterBlock = net.minecraft.world.level.block.Blocks.WATER.defaultBlockState();
  var air = net.minecraft.world.level.block.Blocks.AIR.defaultBlockState();
  int radius = water ? POOL_RADIUS : POOL_RADIUS / 2;
  for (int dx = -radius; dx <= radius; dx++) {
   for (int dz = -radius; dz <= radius; dz++) {
    level.setBlock(new BlockPos(centerX + dx, PLATFORM_Y, centerZ + dz), stone, 3);
    if (water) {
     for (int dy = 1; dy <= WATER_DEPTH; dy++) level.setBlock(new BlockPos(centerX + dx, PLATFORM_Y + dy, centerZ + dz), waterBlock, 3);
     for (int dy = WATER_DEPTH + 1; dy <= WATER_DEPTH + 6; dy++) level.setBlock(new BlockPos(centerX + dx, PLATFORM_Y + dy, centerZ + dz), air, 3);
    } else {
     for (int dy = 1; dy <= 6; dy++) level.setBlock(new BlockPos(centerX + dx, PLATFORM_Y + dy, centerZ + dz), air, 3);
    }
   }
  }
 }

 private static void spawn(ServerLevel level, Scenario s) {
  int blockX = s.chunkX * 16 + 8, blockZ = s.chunkZ * 16 + 8;
  buildPlatform(level, blockX, blockZ, s.water);
  // Spawn resting ON the water surface (or the stone platform, for "beached") - a hull that
  // merely drops onto flat water/ground from a great height would spend most of SETTLE_TICKS
  // on an unrelated fall/splash transient instead of the roll-settling trajectory this probe
  // actually measures.
  double surfaceY = s.water ? PLATFORM_Y + WATER_DEPTH : PLATFORM_Y;
  var packSpeedFactors = ConfigSystem.settings.general.packSpeedFactors.value;
  var packVehicleScales = ConfigSystem.settings.general.packVehicleScales.value;
  packSpeedFactors.putIfAbsent(PACK_ID, 1.0);
  packVehicleScales.putIfAbsent(PACK_ID, 1.0);

  var floatSlotLeft = floatSlot(FLOAT_LEFT);
  var floatSlotRight = floatSlot(FLOAT_RIGHT);
  JSONVehicle definition = buildVehicleDefinition(s.systemName);
  definition.parts = List.of(floatSlotLeft, floatSlotRight);

  AWrapperWorld world = mcinterface1211.WrapperWorld.getWrapperFor(level);
  var item = new ItemVehicle(definition, definition.definitions.get(0), PACK_ID);
  var vehicle = new EntityVehicleF_Physics(world, null, item, null);
  vehicle.position.set(blockX + 0.5, surfaceY + 0.5, blockZ + 0.5);
  // The ONE point writing to orientation has any effect: before this entity is ever spawned/
  // activated. Once BuoyantBody is active it treats orientation as Sable's own output and
  // overwrites it every tick - see this class's own doc for why this is also the only reachable
  // way to give a hull real initial rotational energy to then observe decaying (or not).
  vehicle.orientation.set(new RotationMatrix()).setToAngles(new Point3D(0, 0, INITIAL_ROLL_DEGREES));
  world.spawnEntity(vehicle);
  addFloat(vehicle, floatSlotLeft);
  addFloat(vehicle, floatSlotRight);

  s.vehicle = vehicle;
  s.spawnX = blockX + 0.5; s.spawnZ = blockZ + 0.5; s.spawnSurfaceY = surfaceY;
  s.lastRollDegrees = INITIAL_ROLL_DEGREES;
  s.phase = Phase.MEASURE;
  s.ticksInPhase = 0;
  // Review follow-up 53701: "explicitly record actual resolved off/on coefficients (probe
  // highDrag flag is only a label)" - read the ACTUAL resolved value through the same public
  // Overrides.resolved every real vehicle uses, not the probe's own highDrag boolean.
  double resolvedAngularWaterDrag = io.github.brooswitminecraft.div.Overrides.resolved(PACK_ID + ":" + s.systemName).angularWaterDrag;
  LOGGER.info("[DIV buoyancyAngularDrag] scenario=" + s.label + " spawned at " + vehicle.position + " water=" + s.water
    + " key=" + PACK_ID + ":" + s.systemName + " initialRollDegrees=" + INITIAL_ROLL_DEGREES
    + " resolvedAngularWaterDrag=" + resolvedAngularWaterDrag + " - beginning MEASURE phase");
 }

 private static JSONVehicle buildVehicleDefinition(String systemName) {
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
  return v;
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
  ground.canFloat = true; // NOT isWheel/isTread - Classification.isFloatDevice/isDrivableGroundDevice's exact distinction
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

 private static boolean allFinite(Scenario s) {
  var v = s.vehicle;
  double[] values = {v.position.x, v.position.y, v.position.z, v.motion.x, v.motion.y, v.motion.z,
    v.orientation.m00, v.orientation.m11, v.orientation.m22};
  for (double d : values) if (!Double.isFinite(d)) return false;
  return true;
 }

 private static boolean checkFiniteOrFail(Scenario s) {
  var v = s.vehicle;
  if (!allFinite(s)) {
   LOGGER.error("[DIV buoyancyAngularDrag] scenario=" + s.label + " FAILED - non-finite state tick=" + s.ticksInPhase
     + " position=" + v.position + " motion=" + v.motion);
   s.phase = Phase.FAILED;
   return false;
  }
  double dx = v.position.x - s.spawnX, dz = v.position.z - s.spawnZ;
  double horizontalFromSpawn = Math.sqrt(dx * dx + dz * dz);
  double verticalFromSurface = Math.abs(v.position.y - s.spawnSurfaceY);
  if (horizontalFromSpawn > PLATFORM_BOUND_HORIZONTAL || verticalFromSurface > PLATFORM_BOUND_VERTICAL) {
   LOGGER.error("[DIV buoyancyAngularDrag] scenario=" + s.label + " FAILED - left bounded area tick=" + s.ticksInPhase
     + " horizontalFromSpawn=" + horizontalFromSpawn + " verticalFromSurface=" + verticalFromSurface + " position=" + v.position);
   s.phase = Phase.FAILED;
   return false;
  }
  return true;
 }

 private static void measure(Scenario s) {
  s.ticksInPhase++;
  if (!checkFiniteOrFail(s)) return;
  var v = s.vehicle;
  v.orientation.convertToAngles(); // same call BuoyantBody.step already makes in production - just reading its own result here
  double rollNow = v.orientation.angles.z;
  // Review finding (John, comment 53717): roll is reported in (-180,180] (MTS's own
  // convertToAngles range); a naive rollNow-lastRollDegrees difference miscounts crossing that
  // boundary (e.g. 179 to -179) as 358 degrees of activity instead of the real 2-degree
  // rotation - exactly the inflation the undamped `off` scenario's own near-180-degree
  // oscillation triggered. AngleMath.shortestDeltaDegrees wraps correctly; see its own doc and
  // AngleMathTest's boundary regression.
  s.cumulativeAbsRollDelta += Math.abs(AngleMath.shortestDeltaDegrees(s.lastRollDegrees, rollNow));
  s.maxAbsRoll = Math.max(s.maxAbsRoll, Math.abs(rollNow));
  s.lastRollDegrees = rollNow;
  if (s.ticksInPhase % 20 == 0 || s.ticksInPhase == SETTLE_TICKS) {
   LOGGER.info("[DIV buoyancyAngularDrag] scenario=" + s.label + " tick=" + s.ticksInPhase + " rollDegrees=" + rollNow
     + " cumulativeAbsRollDelta=" + s.cumulativeAbsRollDelta + " maxAbsRoll=" + s.maxAbsRoll + " position=" + v.position);
  }
  if (s.ticksInPhase >= SETTLE_TICKS) {
   s.phase = Phase.DONE;
   LOGGER.info("[DIV buoyancyAngularDrag] scenario=" + s.label + " COMPLETE finalRollDegrees=" + rollNow
     + " cumulativeAbsRollDelta=" + s.cumulativeAbsRollDelta + " maxAbsRoll=" + s.maxAbsRoll
     + " highDrag=" + s.highDrag + " water=" + s.water);
   maybeLogComparison();
  }
 }

 // Review follow-up 53701: "require nontrivial matched off/on angular response" - off itself
 // must show real rotational activity (otherwise a "damped" verdict would be vacuous: both
 // scenarios barely moving is not evidence of anything), and on's reduction must be substantial,
 // not a marginal/noise-level difference.
 private static final double MIN_NONTRIVIAL_ACTIVITY_DEGREES = 5.0;
 private static final double REQUIRED_ON_OVER_OFF_RATIO = 0.5; // on must be at most half of off's activity
 // Review follow-up 53701: "beached case without a matched zero-drag control cannot alone
 // establish unchanged behavior" - beached and beachedOff must land within this tolerance of
 // each other to count as a genuine no-op (both scenarios start identically and never touch
 // water, so their trajectories should be driven by gravity/collision alone, independent of
 // the configured angularWaterDrag value).
 private static final double NOOP_MATCH_TOLERANCE_DEGREES = 2.0;

 /** Turns both comparisons review asked for into actual FAILED-or-not gates (review follow-up:
  * "do not report a pass solely because the server exits") - logged once every scenario each
  * comparison depends on has reached {@code DONE}. */
 private static void maybeLogComparison() {
  if (off.phase == Phase.DONE && on.phase == Phase.DONE) {
   boolean offWasNontrivial = off.cumulativeAbsRollDelta >= MIN_NONTRIVIAL_ACTIVITY_DEGREES;
   boolean onIsSubstantiallyLessActive = on.cumulativeAbsRollDelta <= off.cumulativeAbsRollDelta * REQUIRED_ON_OVER_OFF_RATIO;
   boolean pass = offWasNontrivial && onIsSubstantiallyLessActive;
   String line = "[DIV buoyancyAngularDrag] COMPARISON off.cumulativeAbsRollDelta=" + off.cumulativeAbsRollDelta
     + " on.cumulativeAbsRollDelta=" + on.cumulativeAbsRollDelta + " offWasNontrivial=" + offWasNontrivial
     + " onIsSubstantiallyLessActive=" + onIsSubstantiallyLessActive
     + " - review's actual ask: does configuring angularWaterDrag measurably reduce rotational activity through the real production path";
   if (pass) LOGGER.info(line + " result=PASS");
   else { LOGGER.error(line + " result=FAILED"); off.phase = Phase.FAILED; on.phase = Phase.FAILED; }
  }
  if (beached.phase == Phase.DONE && beachedOff.phase == Phase.DONE) {
   double diff = Math.abs(beached.cumulativeAbsRollDelta - beachedOff.cumulativeAbsRollDelta);
   boolean pass = diff <= NOOP_MATCH_TOLERANCE_DEGREES;
   String line = "[DIV buoyancyAngularDrag] UNSUBMERGED-NOOP-CHECK beached(angularWaterDrag=80).cumulativeAbsRollDelta=" + beached.cumulativeAbsRollDelta
     + " beachedOff(angularWaterDrag=0).cumulativeAbsRollDelta=" + beachedOff.cumulativeAbsRollDelta + " diff=" + diff
     + " - matched zero-drag control: with no float device ever submerged, BuoyantForces.apply's averageFraction>0"
     + " gate must never engage angularWaterDrag regardless of its configured value, so these two must land within tolerance of each other";
   if (pass) LOGGER.info(line + " result=PASS");
   else { LOGGER.error(line + " result=FAILED"); beached.phase = Phase.FAILED; beachedOff.phase = Phase.FAILED; }
  }
 }
}
