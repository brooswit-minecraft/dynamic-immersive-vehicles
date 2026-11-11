package io.github.brooswitminecraft.div.experimental;
import com.mojang.logging.LogUtils;
import minecrafttransportsimulator.baseclasses.Point3D;
import minecrafttransportsimulator.baseclasses.RotationMatrix;
import minecrafttransportsimulator.entities.instances.APart;
import minecrafttransportsimulator.entities.instances.EntityVehicleF_Physics;
import minecrafttransportsimulator.entities.instances.PartEngine;
import minecrafttransportsimulator.entities.instances.PartGroundDevice;
import minecrafttransportsimulator.items.instances.ItemPartEngine;
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
import net.minecraft.world.level.levelgen.Heightmap;
import org.slf4j.Logger;
import java.util.List;
import java.util.Set;

/**
 * Bounded, opt-in, CI/isolated-host-only runtime probe answering review comment 53287's explicit
 * correction: the formula-level regression tests (TwoBodyChassisGroundForceCompositionTest,
 * TwoBodyChassisTest) would keep passing even if {@code PhysicsBridge.Body.stepGround} still
 * wired the owner-isolated mass/inertia - they are extracted math, not the production code path
 * itself. This probe instead spawns a REAL {@code EntityVehicleF_Physics} with real ground-
 * device wheel parts and a real engine part, over REAL generated terrain, with
 * {@code -Ddiv.experimentalTwoBodyChassis=true} actually enabled, and drives it with direct
 * input-variable writes (no player, no datapack) - so the vehicle's own real {@code moveVehicle}
 * tick genuinely runs through {@code VehicleMovementMixin} -&gt; {@code PhysicsBridge.step} -&gt;
 * {@code Body.stepGround}, the exact Mixin-intercepted production path, at both f=0.5 and f=0.4.
 *
 * <p>v2 (this version, per review 53291's correction of the first push, de06f43): several real
 * validity gaps fixed, none of them cosmetic:
 * <ul>
 *  <li>{@code *_CHECK} lines were informational logging only - a stationary, entirely INACTIVE
 *  vehicle (DIV never engaging at all, e.g. a native MTS fallback) would have passed every phase
 *  merely for staying finite. Fixed: each check now has an explicit bounded numeric threshold
 *  and FAILS the probe (logging a FAILED marker, matching this ticket's established pattern) if
 *  not met - {@link #SETTLE_CHECK_MAX_VERTICAL_SPEED}, {@link #DRIVE_CHECK_MIN_SPEED}/
 *  {@link #DRIVE_CHECK_MIN_DISPLACEMENT}, {@link #BRAKE_CHECK_MIN_FRACTIONAL_REDUCTION},
 *  {@link #TURN_CHECK_MIN_ORIENTATION_DELTA}.</li>
 *  <li>"Native fallback must not count as success" - this probe alone cannot distinguish DIV's
 *  own {@code Body} actually activating from MTS's native ground physics coincidentally also
 *  producing finite, plausible-looking motion (both would move a vehicle somewhat similarly on
 *  flat ground). Rather than add new access into {@code PhysicsBridge}'s internals for this
 *  probe's sake (review's own "keep it bounded"), this reuses the EXISTING, already-shipped
 *  {@code -Ddiv.debugGroundContact=true} diagnostic ({@code GroundContactDiagnostics}), which the
 *  CI job now also enables - its own {@code classify}/{@code constructed}/{@code chassisReceipt}
 *  log lines (keyed by {@code key=divtest:f0p5}/{@code f0p4}, this probe's own packID:systemName)
 *  directly show {@code ground=true}, {@code class=Body} (not some other/native path), and
 *  {@code experimentalTwoBodyChassis=true upperMassFraction=0.5}/{@code 0.4} - the CI job's own
 *  shell script greps for these as an ADDITIONAL gate alongside this probe's own COMPLETE
 *  marker, so a silent native-fallback run cannot be mistaken for success at either layer.</li>
 *  <li>{@code engine.running}/{@code currentGearVar} were set once at construction - review
 *  correctly flagged that {@code PartEngine}'s own per-tick update logic could plausibly reset
 *  either (stall/fuel/RPM conditions) without this probe ever knowing. Fixed: both are now
 *  re-asserted (and logged) every tick during DRIVE/TURN, defensively, rather than trusted from
 *  a single construction-time write.</li>
 *  <li>A single force-loaded chunk (16 blocks) is nowhere near enough room for a vehicle that
 *  might genuinely accelerate across multiple seconds of real throttle - the vehicle could drive
 *  straight into unloaded terrain mid-probe. Fixed: force-loads a {@link #CHUNK_RADIUS}-chunk
 *  square around each scenario's spawn point instead of one chunk, and phase lengths were
 *  shortened ({@link #SETTLE_TICKS}/{@link #DRIVE_TICKS}/{@link #BRAKE_TICKS}/
 *  {@link #TURN_TICKS}, 3 real seconds each) specifically so the vehicle's own real achievable
 *  speed cannot plausibly cross that loaded square within the probe's own bounded window.</li>
 * </ul>
 * Exact joint drift stays a disclosed, deliberate limitation per review's own explicit allowance
 * ("don't let that hold up the useful production-path evidence") - this still does NOT reach
 * into {@code PhysicsBridge.Body}'s private half-body fields; see the rationale below.
 *
 * <p>Method: find real generated terrain (confirmed loaded and solid at some Y via the real
 * heightmap - not a synthetic void scene, unlike the other two probes, since this one
 * specifically needs real wheel-raycast ground contact), seed the two config maps
 * {@code AEntityVehicleC_Colliding}'s own constructor requires for an unknown pack ID
 * ({@code ConfigSystem.settings.general.packSpeedFactors}/{@code packVehicleScales}), build a
 * minimal in-Java {@code JSONVehicle} (one collision box sized to match the geometry
 * {@code TwoBodyImpulseResponseProbe} already used: half-extents (0.5,1.0,0.5)) with four real
 * {@code PartGroundDevice} wheels (front two steerable) and one {@code PartEngine}, construct
 * and spawn the real entity via {@code AWrapperWorld.spawnEntity} (the same path
 * {@code ItemVehicle.onBlockClicked} uses), then drive it through four real phases by writing
 * directly to its own real public input variables (no player, no network packet -
 * {@code ComputedVariable.setTo}/{@code setActive} are plain public methods): SETTLE, DRIVE,
 * BRAKE, TURN - each gated on an explicit, bounded, nonzero threshold rather than mere finiteness.
 *
 * <p>Opt-in via {@code -Ddiv.experimentalGroundSupportFixture=true} - OFF by default, never runs
 * during normal play. The CI job that enables it also sets
 * {@code -Ddiv.experimentalTwoBodyChassis=true}, {@code -Ddiv.debugGroundContact=true} and
 * {@code -Ddiv.debugGroundContactTicks=<enough to cover the whole run>}, and writes a config
 * file giving each of this probe's own two test vehicles (packID {@code divtest}, distinct
 * systemNames) an explicit {@code massKg}/{@code upperMassFraction} override (0.5 and 0.4) -
 * this probe itself does not touch {@code Overrides}, which can only be configured via that
 * on-disk file read once at mod init, before any {@code ServerTickEvent} this probe's own code
 * runs on. Runs ONCE per server lifetime, for a bounded window, then stops. Never touches any
 * real vehicle, Zippy, or world content.
 */
public final class GroundSupportFixtureProbe {
 private GroundSupportFixtureProbe() {}
 private static final boolean ENABLED = Boolean.getBoolean("div.experimentalGroundSupportFixture");
 private static final String PACK_ID = "divtest";
 private static final int MAX_TERRAIN_WAIT_TICKS = 200; // 10s - real chunk generation at a never-visited location
 private static final int CHUNK_RADIUS = 5; // an 11x11 chunk square (176 blocks/side) around spawn - generous headroom for 3s of real driving
 private static final int SETTLE_TICKS = 60, DRIVE_TICKS = 60, BRAKE_TICKS = 60, TURN_TICKS = 60; // 3s each at 20tps
 private static final double HALF_EXTENT_A = 0.5, HALF_HEIGHT_B = 1.0, HALF_EXTENT_C = 0.5;
 // Explicit, bounded, nonzero thresholds (review 53291's "bounded nonzero thresholds or explicit
 // FAILED result") - deliberately loose (this probe asks "did anything physically sensible
 // happen at all", not "does this match a specific target curve") so it stays robust to tuning
 // changes while still catching "nothing moved"/"a stationary/inactive vehicle" outright.
 private static final double SETTLE_CHECK_MAX_VERTICAL_SPEED = 1.0; // m/s - should have come to rest, not still falling
 private static final double DRIVE_CHECK_MIN_SPEED = 0.5; // m/s - real throttle+gear must produce SOME forward speed
 private static final double DRIVE_CHECK_MIN_DISPLACEMENT = 1.0; // blocks - real net horizontal movement during DRIVE
 private static final double BRAKE_CHECK_MIN_FRACTIONAL_REDUCTION = 0.2; // speed must drop by at least 20% under braking
 private static final double TURN_CHECK_MIN_ORIENTATION_DELTA = 0.02; // radians-ish (chord distance between orientation matrix columns) - real, nonzero turning
 private static final Logger LOGGER = LogUtils.getLogger();

 private enum Phase { WAIT_TERRAIN, SPAWNING, SETTLE, DRIVE, BRAKE, TURN, DONE, FAILED }

 private static final class Scenario {
  final String label, systemName;
  final int chunkX, chunkZ;
  EntityVehicleF_Physics vehicle;
  PartEngine engine;
  Phase phase = Phase.WAIT_TERRAIN;
  int ticksInPhase = 0;
  int terrainWaitTicksElapsed = 0;
  double maxSpeedSeenInDrive = 0, speedAtBrakeStart = 0;
  Point3D positionAtDriveStart, positionAtTurnStart;
  double[] orientationAtTurnStart;
  Scenario(String label, String systemName, int chunkX, int chunkZ) {
   this.label = label; this.systemName = systemName; this.chunkX = chunkX; this.chunkZ = chunkZ;
  }
 }

 private static Scenario halfScenario, quarterScenario;
 private static boolean configured = false;

 public static void maybeRun(ServerLevel level) {
  if (!ENABLED) return;
  if (!configured) {
   configured = true;
   // Far from world origin/spawn, same "never-visited, real terrain generation" pattern the
   // other probes use for a void scene - here deliberately over REAL generated ground.
   halfScenario = new Scenario("f=0.5", "f0p5", 625, 625);
   quarterScenario = new Scenario("f=0.4", "f0p4", 625, 657); // +512 blocks = +32 chunks, chunk-aligned, far enough apart
   requestChunkGrid(level, halfScenario);
   requestChunkGrid(level, quarterScenario);
   LOGGER.info("[DIV groundSupportFixture] requested " + (2 * CHUNK_RADIUS + 1) + "x" + (2 * CHUNK_RADIUS + 1) + " terrain chunk grids around (" + halfScenario.chunkX + "," + halfScenario.chunkZ + ") and (" + quarterScenario.chunkX + "," + quarterScenario.chunkZ + ")");
   return;
  }
  tick(level, halfScenario);
  tick(level, quarterScenario);
 }

 private static void requestChunkGrid(ServerLevel level, Scenario s) {
  for (int dx = -CHUNK_RADIUS; dx <= CHUNK_RADIUS; dx++) {
   for (int dz = -CHUNK_RADIUS; dz <= CHUNK_RADIUS; dz++) {
    level.setChunkForced(s.chunkX + dx, s.chunkZ + dz, true);
   }
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
    case SETTLE -> settle(s);
    case DRIVE -> drive(s);
    case BRAKE -> brake(s);
    case TURN -> turn(s);
    case DONE, FAILED -> {}
   }
  } catch (RuntimeException ex) {
   LOGGER.error("[DIV groundSupportFixture] scenario=" + s.label + " FAILED - unexpected exception in phase " + s.phase, ex);
   s.phase = Phase.FAILED;
  }
 }

 private static void waitTerrain(ServerLevel level, Scenario s) {
  s.terrainWaitTicksElapsed++;
  if (!chunkGridLoaded(level, s)) {
   if (s.terrainWaitTicksElapsed > MAX_TERRAIN_WAIT_TICKS) {
    LOGGER.error("[DIV groundSupportFixture] scenario=" + s.label + " FAILED - terrain chunk grid never finished loading within " + MAX_TERRAIN_WAIT_TICKS + " ticks");
    s.phase = Phase.FAILED;
   }
   return;
  }
  s.phase = Phase.SPAWNING;
 }

 private static void spawn(ServerLevel level, Scenario s) {
  int blockX = s.chunkX * 16 + 8, blockZ = s.chunkZ * 16 + 8;
  int surfaceY = level.getHeight(Heightmap.Types.MOTION_BLOCKING, blockX, blockZ);
  if (!level.getBlockState(new BlockPos(blockX, surfaceY - 1, blockZ)).isSolid()) {
   LOGGER.error("[DIV groundSupportFixture] scenario=" + s.label + " FAILED - no solid ground found under (" + blockX + "," + blockZ + ") at surfaceY=" + surfaceY);
   s.phase = Phase.FAILED;
   return;
  }
  var packSpeedFactors = ConfigSystem.settings.general.packSpeedFactors.value;
  var packVehicleScales = ConfigSystem.settings.general.packVehicleScales.value;
  packSpeedFactors.putIfAbsent(PACK_ID, 1.0);
  packVehicleScales.putIfAbsent(PACK_ID, 1.0);

  JSONVehicle definition = buildVehicleDefinition(s.systemName);
  AWrapperWorld world = mcinterface1211.WrapperWorld.getWrapperFor(level);
  var item = new ItemVehicle(definition, firstSubDefinition(definition), PACK_ID);
  var vehicle = new EntityVehicleF_Physics(world, null, item, null);
  vehicle.position.set(blockX + 0.5, surfaceY + 2.0, blockZ + 0.5); // spawn a little above the real surface so it genuinely falls+settles
  vehicle.orientation.set(new RotationMatrix());
  world.spawnEntity(vehicle);

  double wheelY = -(HALF_HEIGHT_B + 0.15); // below the chassis center - lower-owned per TwoBodyChassis.isUpperHalf's own doc
  addWheel(vehicle, new Point3D(0.4, wheelY, 0.9), true);
  addWheel(vehicle, new Point3D(-0.4, wheelY, 0.9), true);
  addWheel(vehicle, new Point3D(0.4, wheelY, -0.9), false);
  addWheel(vehicle, new Point3D(-0.4, wheelY, -0.9), false);
  s.engine = addEngine(vehicle);

  s.vehicle = vehicle;
  s.phase = Phase.SETTLE;
  s.ticksInPhase = 0;
  LOGGER.info("[DIV groundSupportFixture] scenario=" + s.label + " spawned at " + vehicle.position + " surfaceY=" + surfaceY + " key=" + PACK_ID + ":" + s.systemName + " - beginning SETTLE phase");
 }

 private static JSONVehicle buildVehicleDefinition(String systemName) {
  var v = new JSONVehicle();
  v.packID = PACK_ID;
  v.systemName = systemName;
  v.motorized = new JSONVehicle.VehicleMotorized();
  v.motorized.litVariable = "";
  v.motorized.panel = "";

  var box = new JSONCollisionBox();
  box.pos = new Point3D(0, 0, 0);
  box.width = (float) (HALF_EXTENT_A * 2);
  box.height = (float) (HALF_HEIGHT_B * 2);
  var group = new JSONCollisionGroup();
  group.collisionTypes = Set.of(JSONCollisionGroup.CollisionType.VEHICLE);
  group.collisions = List.of(box);
  v.collisionGroups = List.of(group);
  return v;
 }

 private static minecrafttransportsimulator.jsondefs.JSONSubDefinition firstSubDefinition(JSONVehicle v) {
  var sub = new minecrafttransportsimulator.jsondefs.JSONSubDefinition();
  sub.subName = "";
  sub.name = v.systemName;
  return sub;
 }

 private static void addWheel(EntityVehicleF_Physics vehicle, Point3D localPos, boolean steers) {
  var slot = new JSONPartDefinition();
  slot.pos = localPos;
  slot.turnsWithSteer = steers;
  slot.types = List.of("ground_wheel");

  var ground = new JSONPart.JSONPartGroundDevice();
  ground.isWheel = true;
  ground.width = 0.4f;
  ground.height = 0.4f;
  var def = new JSONPart();
  def.packID = PACK_ID; def.systemName = "wheel";
  def.generic = new JSONPart.JSONPartGeneric();
  def.ground = ground;

  var item = new ItemPartGroundDevice(def, firstSubDefinition(def), PACK_ID);
  APart part = new PartGroundDevice(vehicle, null, slot, item, null);
  vehicle.addPart(part, false);
 }

 private static minecrafttransportsimulator.jsondefs.JSONSubDefinition firstSubDefinition(JSONPart p) {
  var sub = new minecrafttransportsimulator.jsondefs.JSONSubDefinition();
  sub.subName = "";
  sub.name = p.systemName;
  return sub;
 }

 private static PartEngine addEngine(EntityVehicleF_Physics vehicle) {
  var slot = new JSONPartDefinition();
  slot.pos = new Point3D(0, 0, 0);
  slot.types = List.of("engine");

  var engineDef = new JSONPart.JSONPartEngine();
  engineDef.type = JSONPart.EngineType.NORMAL;
  engineDef.gearRatios = List.of(-2.0f, 0.0f, 2.0f); // [reverse, neutral, 1st] - PartEngine indexes this by currentGearVar+reverseGears
  engineDef.maxRPM = 6000; engineDef.maxSafeRPM = 5500; engineDef.idleRPM = 800; engineDef.startRPM = 400; engineDef.stallRPM = 200;
  var def = new JSONPart();
  def.packID = PACK_ID; def.systemName = "engine";
  def.generic = new JSONPart.JSONPartGeneric();
  def.engine = engineDef;

  var item = new ItemPartEngine(def, firstSubDefinition(def), PACK_ID);
  var engine = new PartEngine(vehicle, null, slot, item, null);
  vehicle.addPart(engine, false);
  forceEngineRunning(engine);
  return engine;
 }

 /** Review 53291: {@code running}/{@code currentGearVar} were only ever set once at
  * construction - this is called again every tick during DRIVE/TURN so a reset by
  * {@code PartEngine}'s own normal tick logic (stall/fuel/RPM conditions) cannot silently starve
  * {@code stepGround} of the nonzero drive force this probe's checks assume. */
 private static void forceEngineRunning(PartEngine engine) {
  engine.running = true;
  engine.currentGearVar.setTo(1.0, false);
 }

 private static boolean allFinite(Scenario s) {
  var v = s.vehicle;
  double[] values = {v.position.x, v.position.y, v.position.z, v.motion.x, v.motion.y, v.motion.z,
    v.orientation.m00, v.orientation.m11, v.orientation.m22};
  for (double d : values) if (!Double.isFinite(d)) return false;
  return true;
 }

 private static void logState(Scenario s, String phaseLabel) {
  var v = s.vehicle;
  LOGGER.info("[DIV groundSupportFixture] scenario=" + s.label + " phase=" + phaseLabel + " tick=" + s.ticksInPhase
    + " position=" + v.position + " motion=" + v.motion + " velocity=" + v.velocity
    + " orientation(m00,m11,m22)=(" + v.orientation.m00 + "," + v.orientation.m11 + "," + v.orientation.m22 + ")"
    + " engineRunning=" + s.engine.running + " engineGear=" + s.engine.currentGearVar.currentValue
    + " throttle=" + v.throttleVar.currentValue + " brakeActive=" + v.brakeVar.isActive);
 }

 private static boolean checkFiniteOrFail(Scenario s) {
  if (!allFinite(s)) {
   LOGGER.error("[DIV groundSupportFixture] scenario=" + s.label + " FAILED - non-finite vehicle state at phase=" + s.phase + " tick=" + s.ticksInPhase
     + " position=" + s.vehicle.position + " motion=" + s.vehicle.motion);
   s.phase = Phase.FAILED;
   return false;
  }
  return true;
 }

 private static void fail(Scenario s, String reason) {
  LOGGER.error("[DIV groundSupportFixture] scenario=" + s.label + " FAILED - " + reason);
  s.phase = Phase.FAILED;
 }

 private static void settle(Scenario s) {
  s.ticksInPhase++;
  if (!checkFiniteOrFail(s)) return;
  if (s.ticksInPhase % 20 == 0 || s.ticksInPhase == SETTLE_TICKS) logState(s, "SETTLE");
  if (s.ticksInPhase >= SETTLE_TICKS) {
   double verticalSpeed = Math.abs(s.vehicle.motion.y * s.vehicle.speedFactor * 20);
   boolean pass = verticalSpeed <= SETTLE_CHECK_MAX_VERTICAL_SPEED;
   LOGGER.info("[DIV groundSupportFixture] scenario=" + s.label + " SETTLE_CHECK verticalSpeed=" + verticalSpeed
     + " threshold=" + SETTLE_CHECK_MAX_VERTICAL_SPEED + " result=" + (pass ? "PASS" : "FAIL"));
   if (!pass) { fail(s, "SETTLE_CHECK: still falling (verticalSpeed=" + verticalSpeed + " > " + SETTLE_CHECK_MAX_VERTICAL_SPEED + ") - never came to rest on the ground"); return; }
   s.positionAtDriveStart = s.vehicle.position.copy();
   s.vehicle.throttleVar.setTo(1.0, false);
   s.phase = Phase.DRIVE;
   s.ticksInPhase = 0;
   LOGGER.info("[DIV groundSupportFixture] scenario=" + s.label + " beginning DRIVE phase - throttle=1.0");
  }
 }

 private static double forwardSpeed(Scenario s) {
  return s.vehicle.velocity * s.vehicle.speedFactor * 20;
 }

 private static void drive(Scenario s) {
  s.ticksInPhase++;
  if (!checkFiniteOrFail(s)) return;
  forceEngineRunning(s.engine);
  double speed = forwardSpeed(s);
  s.maxSpeedSeenInDrive = Math.max(s.maxSpeedSeenInDrive, speed);
  if (s.ticksInPhase % 20 == 0 || s.ticksInPhase == DRIVE_TICKS) logState(s, "DRIVE");
  if (s.ticksInPhase >= DRIVE_TICKS) {
   double displacement = s.positionAtDriveStart.distanceTo(s.vehicle.position);
   boolean speedPass = s.maxSpeedSeenInDrive >= DRIVE_CHECK_MIN_SPEED;
   boolean displacementPass = displacement >= DRIVE_CHECK_MIN_DISPLACEMENT;
   LOGGER.info("[DIV groundSupportFixture] scenario=" + s.label + " DRIVE_CHECK maxSpeedSeen=" + s.maxSpeedSeenInDrive + " (threshold=" + DRIVE_CHECK_MIN_SPEED + ")"
     + " displacement=" + displacement + " (threshold=" + DRIVE_CHECK_MIN_DISPLACEMENT + ")"
     + " result=" + (speedPass && displacementPass ? "PASS" : "FAIL"));
   if (!speedPass || !displacementPass) { fail(s, "DRIVE_CHECK: maxSpeedSeen=" + s.maxSpeedSeenInDrive + " displacement=" + displacement + " - throttle+gear did not produce real forward motion"); return; }
   s.speedAtBrakeStart = speed;
   s.vehicle.throttleVar.setTo(0.0, false);
   s.vehicle.brakeVar.setActive(true, false);
   s.phase = Phase.BRAKE;
   s.ticksInPhase = 0;
   LOGGER.info("[DIV groundSupportFixture] scenario=" + s.label + " beginning BRAKE phase - throttle=0, brake=active");
  }
 }

 private static void brake(Scenario s) {
  s.ticksInPhase++;
  if (!checkFiniteOrFail(s)) return;
  if (s.ticksInPhase % 20 == 0 || s.ticksInPhase == BRAKE_TICKS) logState(s, "BRAKE");
  if (s.ticksInPhase >= BRAKE_TICKS) {
   double speedNow = forwardSpeed(s);
   double fractionalReduction = s.speedAtBrakeStart > 0 ? (s.speedAtBrakeStart - speedNow) / s.speedAtBrakeStart : 0;
   boolean pass = fractionalReduction >= BRAKE_CHECK_MIN_FRACTIONAL_REDUCTION;
   LOGGER.info("[DIV groundSupportFixture] scenario=" + s.label + " BRAKE_CHECK speedAtBrakeStart=" + s.speedAtBrakeStart + " speedNow=" + speedNow
     + " fractionalReduction=" + fractionalReduction + " (threshold=" + BRAKE_CHECK_MIN_FRACTIONAL_REDUCTION + ")" + " result=" + (pass ? "PASS" : "FAIL"));
   if (!pass) { fail(s, "BRAKE_CHECK: fractionalReduction=" + fractionalReduction + " - braking did not meaningfully slow the vehicle"); return; }
   s.positionAtTurnStart = s.vehicle.position.copy();
   s.orientationAtTurnStart = new double[]{s.vehicle.orientation.m00, s.vehicle.orientation.m01, s.vehicle.orientation.m02};
   s.vehicle.brakeVar.setActive(false, false);
   s.vehicle.throttleVar.setTo(1.0, false);
   s.vehicle.rudderInputVar.setTo(20.0, false); // within MAX_RUDDER_ANGLE=45 - a real, nonzero, bounded steering input
   s.phase = Phase.TURN;
   s.ticksInPhase = 0;
   LOGGER.info("[DIV groundSupportFixture] scenario=" + s.label + " beginning TURN phase - throttle=1.0, rudder=20deg");
  }
 }

 private static void turn(Scenario s) {
  s.ticksInPhase++;
  if (!checkFiniteOrFail(s)) return;
  forceEngineRunning(s.engine);
  if (s.ticksInPhase % 20 == 0 || s.ticksInPhase == TURN_TICKS) logState(s, "TURN");
  if (s.ticksInPhase >= TURN_TICKS) {
   double[] nowRow = {s.vehicle.orientation.m00, s.vehicle.orientation.m01, s.vehicle.orientation.m02};
   double orientationDelta = 0;
   for (int i = 0; i < 3; i++) orientationDelta += Math.pow(nowRow[i] - s.orientationAtTurnStart[i], 2);
   orientationDelta = Math.sqrt(orientationDelta);
   boolean pass = orientationDelta >= TURN_CHECK_MIN_ORIENTATION_DELTA;
   LOGGER.info("[DIV groundSupportFixture] scenario=" + s.label + " TURN_CHECK orientationDelta=" + orientationDelta + " (threshold=" + TURN_CHECK_MIN_ORIENTATION_DELTA + ")"
     + " finalPosition=" + s.vehicle.position + " result=" + (pass ? "PASS" : "FAIL"));
   if (!pass) { fail(s, "TURN_CHECK: orientationDelta=" + orientationDelta + " - rudder input did not produce real turning"); return; }
   s.phase = Phase.DONE;
   LOGGER.info("[DIV groundSupportFixture] scenario=" + s.label + " COMPLETE - SETTLE/DRIVE/BRAKE/TURN all passed their explicit thresholds");
  }
 }
}
