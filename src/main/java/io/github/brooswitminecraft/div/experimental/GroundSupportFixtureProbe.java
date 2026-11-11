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
 * <p>Per review 53289's explicit scope guardrail ("keep the real-vehicle fixture bounded...
 * don't turn this into an open-ended CI infrastructure project"), this deliberately does NOT
 * reach into {@code PhysicsBridge.Body}'s own private half-body fields for an exact joint-drift
 * number (unlike {@code TwoBodyImpulseResponseProbe}, which owns its own synthetic bodies and can
 * query them directly) - that would mean adding new package-private/reflective plumbing to
 * production code for this probe's sake alone. Instead this measures the EXTERNALLY OBSERVABLE
 * consequence: the vehicle's own real position/orientation/velocity staying finite and behaving
 * physically sensibly (settling, accelerating, braking, turning) throughout. A real joint
 * failure (the halves disagreeing badly) would manifest as visible instability in exactly that
 * externally-observed state, so this is still genuine integration evidence for "does enabled
 * two-body ground behavior work", just without a separate literal drift-in-meters log line -
 * this limitation is explicit, per review's own "provided the limitation is explicit" allowance.
 *
 * <p>Method: find real generated terrain (the normal overworld spawn-area chunk, confirmed
 * loaded and solid at some Y via the real heightmap - not a synthetic void scene, unlike the
 * other two probes, since this one specifically needs real wheel-raycast ground contact), seed
 * the two config maps {@code AEntityVehicleC_Colliding}'s own constructor requires for an
 * unknown pack ID ({@code ConfigSystem.settings.general.packSpeedFactors}/{@code
 * packVehicleScales}), build a minimal in-Java {@code JSONVehicle} (one collision box sized to
 * match the geometry {@code TwoBodyImpulseResponseProbe} already used: half-extents (0.5,1.0,
 * 0.5)) with four real {@code PartGroundDevice} wheels (front two steerable) and one
 * {@code PartEngine}, construct and spawn the real entity via {@code AWrapperWorld.spawnEntity}
 * (the same path {@code ItemVehicle.onBlockClicked} uses), then drive it through four real
 * phases by writing directly to its own real public input variables (no player, no network
 * packet - {@code ComputedVariable.setTo}/{@code setActive} are plain public methods):
 * SETTLE (no input, confirm it stops falling and rests near the ground), DRIVE (throttle+gear,
 * confirm forward speed increases from rest), BRAKE (brake engaged, throttle off, confirm speed
 * decreases), TURN (throttle plus a real rudder/steering input, confirm it turns without
 * diverging). Every phase's measured state (position/velocity/orientation) is logged every
 * tick; a FAILED marker is logged (and this probe stops early) on any non-finite value at any
 * point, matching this ticket's own established probe pattern.
 *
 * <p>Opt-in via {@code -Ddiv.experimentalGroundSupportFixture=true} - OFF by default, never runs
 * during normal play. The CI job that enables it also sets
 * {@code -Ddiv.experimentalTwoBodyChassis=true} and writes a config file giving each of this
 * probe's own two test vehicles (packID {@code divtest}, distinct systemNames) an explicit
 * {@code massKg}/{@code upperMassFraction} override (0.5 and 0.4) - this probe itself does not
 * touch {@code Overrides}, which can only be configured via that on-disk file read once at mod
 * init, before any {@code ServerTickEvent} this probe's own code runs on. Runs ONCE per server
 * lifetime, for a bounded window, then stops. Never touches any real vehicle, Zippy, or world
 * content.
 */
public final class GroundSupportFixtureProbe {
 private GroundSupportFixtureProbe() {}
 private static final boolean ENABLED = Boolean.getBoolean("div.experimentalGroundSupportFixture");
 private static final String PACK_ID = "divtest";
 private static final int MAX_TERRAIN_WAIT_TICKS = 200; // 10s - real chunk generation at a never-visited location
 private static final int SETTLE_TICKS = 100, DRIVE_TICKS = 140, BRAKE_TICKS = 100, TURN_TICKS = 120;
 private static final double HALF_EXTENT_A = 0.5, HALF_HEIGHT_B = 1.0, HALF_EXTENT_C = 0.5;
 private static final Logger LOGGER = LogUtils.getLogger();

 private enum Phase { WAIT_TERRAIN, SPAWNING, SETTLE, DRIVE, BRAKE, TURN, DONE, FAILED }

 private static final class Scenario {
  final String label, systemName;
  final int chunkX, chunkZ;
  EntityVehicleF_Physics vehicle;
  Phase phase = Phase.WAIT_TERRAIN;
  int ticksInPhase = 0;
  int terrainWaitTicksElapsed = 0;
  double maxSpeedSeenInDrive = 0, speedAtBrakeStart = 0;
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
   level.setChunkForced(halfScenario.chunkX, halfScenario.chunkZ, true);
   level.setChunkForced(quarterScenario.chunkX, quarterScenario.chunkZ, true);
   LOGGER.info("[DIV groundSupportFixture] requested terrain chunks (" + halfScenario.chunkX + "," + halfScenario.chunkZ + ") and (" + quarterScenario.chunkX + "," + quarterScenario.chunkZ + ")");
   return;
  }
  tick(level, halfScenario);
  tick(level, quarterScenario);
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
  if (level.getChunkSource().getChunkNow(s.chunkX, s.chunkZ) == null) {
   if (s.terrainWaitTicksElapsed > MAX_TERRAIN_WAIT_TICKS) {
    LOGGER.error("[DIV groundSupportFixture] scenario=" + s.label + " FAILED - terrain chunk never finished loading within " + MAX_TERRAIN_WAIT_TICKS + " ticks");
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
  addEngine(vehicle);

  s.vehicle = vehicle;
  s.phase = Phase.SETTLE;
  s.ticksInPhase = 0;
  LOGGER.info("[DIV groundSupportFixture] scenario=" + s.label + " spawned at " + vehicle.position + " surfaceY=" + surfaceY + " - beginning SETTLE phase");
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

 private static void addEngine(EntityVehicleF_Physics vehicle) {
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
  // Force a running, in-gear state directly (both plain public fields/methods) rather than
  // going through the normal start-sequence/magneto/fuel preconditions - this probe supplies
  // its own throttle input directly too, so nothing about the real fuel/ignition system needs
  // to be modeled for stepGround's own ground-force math to be exercised.
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
    + " orientation(m00,m11,m22)=(" + v.orientation.m00 + "," + v.orientation.m11 + "," + v.orientation.m22 + ")");
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

 private static void settle(Scenario s) {
  s.ticksInPhase++;
  if (!checkFiniteOrFail(s)) return;
  if (s.ticksInPhase % 20 == 0 || s.ticksInPhase == SETTLE_TICKS) logState(s, "SETTLE");
  if (s.ticksInPhase >= SETTLE_TICKS) {
   double verticalSpeed = Math.abs(s.vehicle.motion.y * s.vehicle.speedFactor * 20);
   LOGGER.info("[DIV groundSupportFixture] scenario=" + s.label + " SETTLE_CHECK verticalSpeed=" + verticalSpeed
     + " (expect small - vehicle should have come to rest on the ground, not still falling)");
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
  double speed = forwardSpeed(s);
  s.maxSpeedSeenInDrive = Math.max(s.maxSpeedSeenInDrive, speed);
  if (s.ticksInPhase % 20 == 0 || s.ticksInPhase == DRIVE_TICKS) logState(s, "DRIVE");
  if (s.ticksInPhase >= DRIVE_TICKS) {
   LOGGER.info("[DIV groundSupportFixture] scenario=" + s.label + " DRIVE_CHECK maxSpeedSeen=" + s.maxSpeedSeenInDrive
     + " (expect clearly > 0 - throttle+gear should have accelerated the vehicle from rest)");
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
   LOGGER.info("[DIV groundSupportFixture] scenario=" + s.label + " BRAKE_CHECK speedAtBrakeStart=" + s.speedAtBrakeStart + " speedNow=" + speedNow
     + " (expect speedNow clearly less than speedAtBrakeStart - braking should have slowed the vehicle)");
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
  if (s.ticksInPhase % 20 == 0 || s.ticksInPhase == TURN_TICKS) logState(s, "TURN");
  if (s.ticksInPhase >= TURN_TICKS) {
   LOGGER.info("[DIV groundSupportFixture] scenario=" + s.label + " TURN_CHECK finalPosition=" + s.vehicle.position
     + " finalOrientation(m00,m11,m22)=(" + s.vehicle.orientation.m00 + "," + s.vehicle.orientation.m11 + "," + s.vehicle.orientation.m22 + ")"
     + " (expect all finite, no divergence - already enforced by checkFiniteOrFail every tick this phase)");
   s.phase = Phase.DONE;
   LOGGER.info("[DIV groundSupportFixture] scenario=" + s.label + " COMPLETE - all phases finished with finite, sensible motion throughout");
  }
 }
}
