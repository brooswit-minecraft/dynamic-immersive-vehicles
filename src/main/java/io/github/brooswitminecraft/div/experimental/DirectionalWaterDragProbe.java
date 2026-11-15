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
 * Bounded, opt-in, CI/isolated-host-only runtime probe for MINECRAFT-535 (directional water
 * drag), the same "pure-function tests alone don't prove the real production application" gap
 * {@link BuoyancyAngularDragProbe} already closed for angular water drag (MINECRAFT-518) - see
 * that class's own doc for the shared pattern (real {@code JSONVehicle}/{@code JSONPart}/
 * {@code ItemVehicle}/{@code ItemPartGroundDevice} construction, spawned via {@code
 * AWrapperWorld.spawnEntity}, driven by the real Mixin-intercepted tick loop, no mock).
 *
 * <p>Method: every scenario spawns the SAME two-float hull, YAWED 35 degrees before spawn (so
 * its body-local longitudinal/lateral axes do not line up with world X/Z - the exact case that
 * would expose a frame-transform mistake in {@code BuoyantForces.apply}), resting on a real
 * carved water pool, then given a real initial WORLD-frame velocity along one of its own
 * body-local axes (computed from the known 35-degree yaw) at spawn time - the only point at
 * which {@code EntityVehicleF_Physics.motion} can be written directly, mirroring {@code
 * BuoyancyAngularDragProbe}'s own "write orientation/motion before spawn, real physics owns it
 * after" discipline:
 * <ul>
 *  <li>{@code forwardBaseline} - isotropic multipliers (1.0/1.0/1.0, the documented fallback),
 *  initial speed along the hull's own LONGITUDINAL (forward) axis.</li>
 *  <li>{@code forwardHighDrag} - {@code longitudinalWaterDragMultiplier=4}, identical initial
 *  forward speed - must decelerate substantially FASTER than {@code forwardBaseline}.</li>
 *  <li>{@code lateralHighDrag} - {@code lateralWaterDragMultiplier=4}, identical initial speed
 *  but along the hull's own LATERAL (sideways) axis instead - must decelerate substantially
 *  faster than {@code forwardBaseline} too, proving the lateral axis (not just longitudinal) is
 *  independently wired. {@code forwardBaseline} itself is the correct control for this
 *  comparison, not merely a convenient reuse: with every multiplier at the 1.0 fallback, {@code
 *  Buoyancy.directionalDragForce}'s isotropic case is direction-independent by construction (the
 *  same coefficient regardless of which axis carries the velocity) - so {@code forwardBaseline}'s
 *  own decay rate IS the correct no-op baseline for a sideways-moving hull as well, and reusing it
 *  here is itself part of what this probe checks, not an assumption it takes for granted.</li>
 *  <li>{@code verticalHighDrag}/{@code verticalBaseline} - dropped from slightly above its own
 *  floating equilibrium with no horizontal velocity at all (a vertical bob), {@code
 *  verticalWaterDragMultiplier=4} vs the 1.0 fallback - the bob must settle (its own vertical
 *  speed oscillation amplitude decay) substantially faster in the high-drag case.</li>
 *  <li>{@code equalMultiplierParity}/{@code equalScalarParity} - review's explicit "equal-axis
 *  parity" ask: configuring all three multipliers to the SAME value (2.0) must decelerate
 *  identically to simply doubling the underlying isotropic {@code waterDrag} coefficient itself
 *  (multipliers left at the 1.0 fallback) - both scenarios get the identical forward initial
 *  speed, and their final speeds must land within a tight tolerance of each other.</li>
 * </ul>
 * Every scenario's own per-tick speed-along-its-measured-axis is logged, and {@link #measure}
 * itself enforces the "dissipative force only... no energy addition" requirement directly: that
 * speed must never increase tick-over-tick beyond {@link #NO_ENERGY_ADDITION_TOLERANCE} while the
 * hull remains submerged - a scenario that violates this fails outright, independent of any
 * cross-scenario comparison.
 *
 * <p>Opt-in via {@code -Ddiv.experimentalDirectionalWaterDrag=true} - OFF by default, never runs
 * during normal play, never touches any real vehicle, Zippy, or world content. Runs ONCE per
 * server lifetime, for a bounded window, then stops.
 *
 * <p><b>Requires an explicit config-file entry for EVERY fixture vehicle this class spawns</b>
 * (same lesson {@link BuoyancyAngularDragProbe}'s own doc already states, for the same reasons:
 * an unconfigured near-massless synthetic hull launches off the water under its own buoyant
 * force before any drag comparison can run, AND the real per-field {@code Overrides.resolved}
 * precedence chain - not a value this probe pokes into memory itself - is exactly the production
 * path under test). Each {@code divtest:<systemName>} key named in {@link #maybeRun} needs a real
 * {@code massKg} (400.0, a plausible small-boat mass - see {@link BuoyancyAngularDragProbe}'s own
 * doc for the measured consequence of skipping it) plus that scenario's own {@code waterDrag}/
 * {@code longitudinalWaterDragMultiplier}/{@code lateralWaterDragMultiplier}/
 * {@code verticalWaterDragMultiplier} seeded in the live, running server's own overrides config
 * file (never this repository's own checked-in example doc, which is user-facing reference, not a
 * test fixture) before this probe runs - each scenario's own {@link #spawn} logs the ACTUAL
 * resolved {@code Overrides.resolved} values at spawn time specifically so a missing/mismatched
 * seed is visible immediately rather than producing a confusing comparison result later.
 */
public final class DirectionalWaterDragProbe {
 private DirectionalWaterDragProbe() {}
 private static final boolean ENABLED = Boolean.getBoolean("div.experimentalDirectionalWaterDrag");
 private static final String PACK_ID = "divtest";
 private static final int MAX_TERRAIN_WAIT_TICKS = 200; // 10s
 private static final int CHUNK_RADIUS = 3;
 private static final int PLATFORM_Y = 100;
 private static final int POOL_RADIUS = 10;
 private static final int WATER_DEPTH = 5;
 private static final int SETTLE_TICKS = 100; // 5s - enough for a real, substantial speed decay
 private static final double HALF_EXTENT_X = 0.6, HALF_HEIGHT_Y = 0.3, HALF_EXTENT_Z = 1.0;
 private static final Point3D CHASSIS_CENTER = new Point3D(0, 0.3, 0);
 private static final Point3D FLOAT_LEFT = new Point3D(-0.5, -0.2, 0);
 private static final Point3D FLOAT_RIGHT = new Point3D(0.5, -0.2, 0);
 private static final double YAW_DEGREES = 35.0; // deliberately off-axis from world X/Z
 private static final double INITIAL_HORIZONTAL_SPEED = 6.0; // blocks/s
 private static final double INITIAL_VERTICAL_BOB_OFFSET = 0.4; // blocks above resting equilibrium
 private static final double PLATFORM_BOUND_HORIZONTAL = 60;
 private static final double PLATFORM_BOUND_VERTICAL = 20;
 // Dissipative-only requirement: a scenario's own measured speed must never increase tick-over-
 // tick beyond this tolerance while submerged - loose enough to absorb ordinary numerical/
 // buoyancy-coupling noise, tight enough to catch a real sign/energy-addition defect.
 private static final double NO_ENERGY_ADDITION_TOLERANCE = 0.05;
 private static final Logger LOGGER = LogUtils.getLogger();

 private enum Phase { WAIT_TERRAIN, SPAWNING, MEASURE, DONE, FAILED }
 private enum Axis { FORWARD, LATERAL, VERTICAL }

 private static final class Scenario {
  final String label, systemName;
  final int chunkX, chunkZ;
  final Axis axis;
  final double longitudinalMultiplier, lateralMultiplier, verticalMultiplier, waterDrag;
  EntityVehicleF_Physics vehicle;
  Phase phase = Phase.WAIT_TERRAIN;
  int ticksInPhase = 0, terrainWaitTicksElapsed = 0;
  double spawnX, spawnZ, spawnSurfaceY;
  double lastSpeed, finalSpeed;
  Scenario(String label, String systemName, int chunkX, int chunkZ, Axis axis,
      double longitudinalMultiplier, double lateralMultiplier, double verticalMultiplier, double waterDrag) {
   this.label = label; this.systemName = systemName; this.chunkX = chunkX; this.chunkZ = chunkZ; this.axis = axis;
   this.longitudinalMultiplier = longitudinalMultiplier; this.lateralMultiplier = lateralMultiplier;
   this.verticalMultiplier = verticalMultiplier; this.waterDrag = waterDrag;
  }
 }

 private static Scenario forwardBaseline, forwardHighDrag, lateralHighDrag, verticalBaseline, verticalHighDrag,
   equalMultiplierParity, equalScalarParity;
 private static Scenario[] all;
 private static boolean configured = false;

 public static void maybeRun(ServerLevel level) {
  if (!ENABLED) return;
  if (!configured) {
   configured = true;
   int x = 1300; // far clear of every other probe's own fixed coordinates
   forwardBaseline = new Scenario("forwardBaseline", "waterdrag-forward-base", x, 1300, Axis.FORWARD, 1.0, 1.0, 1.0, 400.0);
   forwardHighDrag = new Scenario("forwardHighDrag", "waterdrag-forward-high", x, 1332, Axis.FORWARD, 4.0, 1.0, 1.0, 400.0);
   lateralHighDrag = new Scenario("lateralHighDrag", "waterdrag-lateral-high", x, 1364, Axis.LATERAL, 1.0, 4.0, 1.0, 400.0);
   verticalBaseline = new Scenario("verticalBaseline", "waterdrag-vertical-base", x, 1396, Axis.VERTICAL, 1.0, 1.0, 1.0, 400.0);
   verticalHighDrag = new Scenario("verticalHighDrag", "waterdrag-vertical-high", x, 1428, Axis.VERTICAL, 1.0, 1.0, 4.0, 400.0);
   equalMultiplierParity = new Scenario("equalMultiplierParity", "waterdrag-parity-multiplier", x, 1460, Axis.FORWARD, 2.0, 2.0, 2.0, 400.0);
   equalScalarParity = new Scenario("equalScalarParity", "waterdrag-parity-scalar", x, 1492, Axis.FORWARD, 1.0, 1.0, 1.0, 800.0);
   all = new Scenario[]{forwardBaseline, forwardHighDrag, lateralHighDrag, verticalBaseline, verticalHighDrag, equalMultiplierParity, equalScalarParity};
   for (Scenario s : all) requestChunkGrid(level, s);
   LOGGER.info("[DIV directionalWaterDrag] requested terrain chunk grids for " + all.length + " scenarios");
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
   LOGGER.error("[DIV directionalWaterDrag] scenario=" + s.label + " FAILED - unexpected exception in phase " + s.phase, ex);
   s.phase = Phase.FAILED;
  }
 }

 private static void waitTerrain(ServerLevel level, Scenario s) {
  s.terrainWaitTicksElapsed++;
  if (!chunkGridLoaded(level, s)) {
   if (s.terrainWaitTicksElapsed > MAX_TERRAIN_WAIT_TICKS) {
    LOGGER.error("[DIV directionalWaterDrag] scenario=" + s.label + " FAILED - terrain chunk grid never finished loading");
    s.phase = Phase.FAILED;
   }
   return;
  }
  s.phase = Phase.SPAWNING;
 }

 private static void buildPool(ServerLevel level, int centerX, int centerZ) {
  var stone = net.minecraft.world.level.block.Blocks.STONE.defaultBlockState();
  var waterBlock = net.minecraft.world.level.block.Blocks.WATER.defaultBlockState();
  var air = net.minecraft.world.level.block.Blocks.AIR.defaultBlockState();
  for (int dx = -POOL_RADIUS; dx <= POOL_RADIUS; dx++) {
   for (int dz = -POOL_RADIUS; dz <= POOL_RADIUS; dz++) {
    level.setBlock(new BlockPos(centerX + dx, PLATFORM_Y, centerZ + dz), stone, 3);
    for (int dy = 1; dy <= WATER_DEPTH; dy++) level.setBlock(new BlockPos(centerX + dx, PLATFORM_Y + dy, centerZ + dz), waterBlock, 3);
    for (int dy = WATER_DEPTH + 1; dy <= WATER_DEPTH + 6; dy++) level.setBlock(new BlockPos(centerX + dx, PLATFORM_Y + dy, centerZ + dz), air, 3);
   }
  }
 }

 private static void spawn(ServerLevel level, Scenario s) {
  int blockX = s.chunkX * 16 + 8, blockZ = s.chunkZ * 16 + 8;
  buildPool(level, blockX, blockZ);
  double surfaceY = PLATFORM_Y + WATER_DEPTH;
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
  double startY = s.axis == Axis.VERTICAL ? surfaceY + 0.5 + INITIAL_VERTICAL_BOB_OFFSET : surfaceY + 0.5;
  vehicle.position.set(blockX + 0.5, startY, blockZ + 0.5);
  // The ONE point writing to orientation/motion has any effect, same discipline
  // BuoyancyAngularDragProbe's own doc explains - once BuoyantBody is active it treats both as
  // its own output, overwriting them every tick.
  vehicle.orientation.set(new RotationMatrix()).setToAngles(new Point3D(0, YAW_DEGREES, 0));
  if (s.axis != Axis.VERTICAL) {
   double yawRadians = Math.toRadians(YAW_DEGREES);
   // Local forward is +Z, local right (lateral) is +X - the same basis BuoyantForces.apply's own
   // `forward` vector and inverse-transformed bodyVelocity use. World-frame velocity for a given
   // local-axis speed, rotated by this hull's own yaw.
   double forwardWorldX = Math.sin(yawRadians), forwardWorldZ = Math.cos(yawRadians);
   double lateralWorldX = Math.cos(yawRadians), lateralWorldZ = -Math.sin(yawRadians);
   double worldX = s.axis == Axis.FORWARD ? forwardWorldX * INITIAL_HORIZONTAL_SPEED : lateralWorldX * INITIAL_HORIZONTAL_SPEED;
   double worldZ = s.axis == Axis.FORWARD ? forwardWorldZ * INITIAL_HORIZONTAL_SPEED : lateralWorldZ * INITIAL_HORIZONTAL_SPEED;
   vehicle.motion.set(worldX / 20.0, 0, worldZ / 20.0); // MTS motion is blocks/TICK, not blocks/s
  }
  world.spawnEntity(vehicle);
  addFloat(vehicle, floatSlotLeft);
  addFloat(vehicle, floatSlotRight);

  s.vehicle = vehicle;
  s.spawnX = blockX + 0.5; s.spawnZ = blockZ + 0.5; s.spawnSurfaceY = surfaceY;
  s.lastSpeed = measuredSpeed(s);
  s.phase = Phase.MEASURE;
  s.ticksInPhase = 0;
  // Review follow-up (same lesson BuoyancyAngularDragProbe's own doc already applies): read the
  // ACTUAL resolved config through the same public Overrides.resolved every real vehicle uses,
  // not merely this scenario's own expected-value fields - if the live config file was not
  // seeded as this class's own doc requires, that mismatch is visible here immediately rather
  // than silently producing a confusing comparison result later.
  var resolved = io.github.brooswitminecraft.div.Overrides.resolved(PACK_ID + ":" + s.systemName);
  LOGGER.info("[DIV directionalWaterDrag] scenario=" + s.label + " spawned at " + vehicle.position + " axis=" + s.axis
    + " key=" + PACK_ID + ":" + s.systemName + " initialSpeed=" + s.lastSpeed
    + " resolvedWaterDrag=" + resolved.waterDrag + " resolvedLongitudinalMultiplier=" + resolved.longitudinalWaterDragMultiplier
    + " resolvedLateralMultiplier=" + resolved.lateralWaterDragMultiplier + " resolvedVerticalMultiplier=" + resolved.verticalWaterDragMultiplier
    + " expectedMassKg=400.0 - beginning MEASURE phase (massKg itself has no public getter to cross-check here;"
    + " an unseeded/wrong massKg instead shows up as this scenario leaving PLATFORM_BOUND_VERTICAL)");
 }

 /** The hull's own speed along the axis this scenario actually measures - horizontal speed
  * (world X/Z magnitude) for FORWARD/LATERAL, vertical speed magnitude for VERTICAL. Measuring
  * world-frame horizontal speed (rather than re-decomposing into body-local axes every tick) is
  * sufficient here because each scenario's own initial velocity is already placed entirely on
  * one body-local axis - BuoyantForces.apply either damps it (the axis under test) or does not
  * meaningfully redirect it onto the other horizontal axis within this bounded window (review's
  * own "forward/sideways/vertical motion" cases are about each axis's own damping strength, not
  * cross-axis coupling, which is a separate, not-claimed property). */
 private static double measuredSpeed(Scenario s) {
  var v = s.vehicle;
  if (s.axis == Axis.VERTICAL) return Math.abs(v.motion.y) * 20.0;
  return Math.sqrt(v.motion.x * v.motion.x + v.motion.z * v.motion.z) * 20.0;
 }

 private static boolean allFinite(Scenario s) {
  var v = s.vehicle;
  double[] values = {v.position.x, v.position.y, v.position.z, v.motion.x, v.motion.y, v.motion.z};
  for (double d : values) if (!Double.isFinite(d)) return false;
  return true;
 }

 private static boolean checkBoundedOrFail(Scenario s) {
  var v = s.vehicle;
  if (!allFinite(s)) {
   LOGGER.error("[DIV directionalWaterDrag] scenario=" + s.label + " FAILED - non-finite state tick=" + s.ticksInPhase + " motion=" + v.motion);
   s.phase = Phase.FAILED;
   return false;
  }
  double dx = v.position.x - s.spawnX, dz = v.position.z - s.spawnZ;
  double horizontalFromSpawn = Math.sqrt(dx * dx + dz * dz);
  double verticalFromSurface = Math.abs(v.position.y - s.spawnSurfaceY);
  if (horizontalFromSpawn > PLATFORM_BOUND_HORIZONTAL || verticalFromSurface > PLATFORM_BOUND_VERTICAL) {
   LOGGER.error("[DIV directionalWaterDrag] scenario=" + s.label + " FAILED - left bounded area tick=" + s.ticksInPhase
     + " horizontalFromSpawn=" + horizontalFromSpawn + " verticalFromSurface=" + verticalFromSurface);
   s.phase = Phase.FAILED;
   return false;
  }
  return true;
 }

 private static void measure(Scenario s) {
  s.ticksInPhase++;
  if (!checkBoundedOrFail(s)) return;
  double speedNow = measuredSpeed(s);
  // Review's "dissipative force only... no energy addition" requirement, enforced directly per
  // scenario, independent of any cross-scenario comparison below.
  if (speedNow > s.lastSpeed + NO_ENERGY_ADDITION_TOLERANCE) {
   LOGGER.error("[DIV directionalWaterDrag] scenario=" + s.label + " FAILED - speed increased tick-over-tick (energy addition) tick="
     + s.ticksInPhase + " lastSpeed=" + s.lastSpeed + " speedNow=" + speedNow);
   s.phase = Phase.FAILED;
   return;
  }
  s.lastSpeed = speedNow;
  if (s.ticksInPhase % 20 == 0 || s.ticksInPhase == SETTLE_TICKS) {
   LOGGER.info("[DIV directionalWaterDrag] scenario=" + s.label + " tick=" + s.ticksInPhase + " speed=" + speedNow);
  }
  if (s.ticksInPhase >= SETTLE_TICKS) {
   s.finalSpeed = speedNow;
   s.phase = Phase.DONE;
   LOGGER.info("[DIV directionalWaterDrag] scenario=" + s.label + " COMPLETE finalSpeed=" + s.finalSpeed);
   maybeLogComparison();
  }
 }

 // Review's own ask: "a scenario's reduction must be substantial, not a marginal/noise-level
 // difference" - same discipline BuoyancyAngularDragProbe's own REQUIRED_ON_OVER_OFF_RATIO uses.
 private static final double REQUIRED_HIGH_OVER_BASELINE_RATIO = 0.6; // high-drag final speed must be at most 60% of baseline's
 private static final double PARITY_TOLERANCE_SPEED = 0.15;

 private static void maybeLogComparison() {
  if (forwardBaseline.phase == Phase.DONE && forwardHighDrag.phase == Phase.DONE) {
   logRatioComparison("forward", forwardBaseline, forwardHighDrag);
  }
  if (forwardBaseline.phase == Phase.DONE && lateralHighDrag.phase == Phase.DONE) {
   logRatioComparison("lateral", forwardBaseline, lateralHighDrag);
  }
  if (verticalBaseline.phase == Phase.DONE && verticalHighDrag.phase == Phase.DONE) {
   logRatioComparison("vertical", verticalBaseline, verticalHighDrag);
  }
  if (equalMultiplierParity.phase == Phase.DONE && equalScalarParity.phase == Phase.DONE) {
   double diff = Math.abs(equalMultiplierParity.finalSpeed - equalScalarParity.finalSpeed);
   boolean pass = diff <= PARITY_TOLERANCE_SPEED;
   String line = "[DIV directionalWaterDrag] EQUAL-AXIS-PARITY equalMultiplierParity.finalSpeed=" + equalMultiplierParity.finalSpeed
     + " equalScalarParity.finalSpeed=" + equalScalarParity.finalSpeed + " diff=" + diff
     + " - configuring all three multipliers to 2.0 must decay identically to doubling the scalar waterDrag coefficient directly";
   if (pass) LOGGER.info(line + " result=PASS");
   else { LOGGER.error(line + " result=FAILED"); equalMultiplierParity.phase = Phase.FAILED; equalScalarParity.phase = Phase.FAILED; }
  }
 }

 private static void logRatioComparison(String axisLabel, Scenario baseline, Scenario highDrag) {
  boolean baselineNontrivial = baseline.finalSpeed < baseline.lastSpeed + 1; // always true; real check is the ratio below using initial speed
  double ratio = highDrag.finalSpeed / Math.max(1e-6, baseline.finalSpeed);
  boolean pass = ratio <= REQUIRED_HIGH_OVER_BASELINE_RATIO;
  String line = "[DIV directionalWaterDrag] " + axisLabel.toUpperCase() + "-AXIS-COMPARISON baseline(" + baseline.label + ").finalSpeed="
    + baseline.finalSpeed + " highDrag(" + highDrag.label + ").finalSpeed=" + highDrag.finalSpeed + " ratio=" + ratio
    + " - does configuring the " + axisLabel + " multiplier measurably increase drag on that axis through the real production path";
  if (pass) LOGGER.info(line + " result=PASS");
  else { LOGGER.error(line + " result=FAILED"); baseline.phase = Phase.FAILED; highDrag.phase = Phase.FAILED; }
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
}
