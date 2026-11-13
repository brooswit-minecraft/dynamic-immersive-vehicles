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
import org.slf4j.Logger;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Review 53327/53330: a bounded, opt-in, CI/isolated-host-only brake-only sweep, separate from
 * (and not touching a single line of) {@link GroundSupportFixtureProbe}. Per 53330's own
 * correction of this file's first version: ONE shared, pre-built platform, scenarios run
 * strictly SEQUENTIALLY (never more than one vehicle/world-generation operation active at a
 * time) - the earlier 8-simultaneous-scenario design kept overrunning the server watchdog on
 * {@code ServerLevel.setChunkForced}'s own synchronous, blocking chunk-generation cost (confirmed
 * via that run's own crash-report thread dump: {@code Level.getChunk -> managedBlock} waiting on
 * real generation), regardless of how the work was staggered across scenarios. This version
 * requests one small, fixed-size chunk/platform area ONCE, then ticks exactly one scenario at a
 * time to DONE/FAILED (discarding its vehicle) before starting the next - total world-generation
 * cost no longer scales with scenario count at all.
 *
 * <p>Method: same real ATV construction pattern and geometry as GroundSupportFixtureProbe
 * (deliberately duplicated, not shared, so nothing about that already-accepted fixture is at
 * risk) - accelerate under bounded closed-loop throttle to a scenario's own target speed, hold
 * briefly (so pitch from reaching speed settles before brake-onset is logged, per 53327's own
 * confound note), then brake hard - logging every BRAKE tick's own speed/up-vector/position,
 * while the existing, unmodified {@code -Ddiv.debugGroundContact} diagnostic captures per-wheel
 * normal-force/impulse detail for the same ticks. No new production plumbing.
 */
public final class BrakeSweepProbe {
 private BrakeSweepProbe() {}
 private static final boolean ENABLED = Boolean.getBoolean("div.experimentalBrakeSweepProbe");
 private static final String PACK_ID = "divtest";
 private static final int MAX_TERRAIN_WAIT_TICKS = 200;
 private static final int CHUNK_RADIUS = 5; // matches GroundSupportFixtureProbe's own proven-safe sizing
 private static final int PLATFORM_Y = 100;
 private static final int PLATFORM_RADIUS = 70; // matches GroundSupportFixtureProbe's own proven-safe sizing; built ONCE, reused by every scenario
 private static final double PLATFORM_BOUND_HORIZONTAL = PLATFORM_RADIUS - 15;
 private static final double PLATFORM_BOUND_VERTICAL = 10;
 private static final double UPRIGHT_MIN_VERTICALITY = 0.5; // looser than GroundSupportFixtureProbe's own 0.7 - this probe WANTS to observe how far pitch/roll develops, not stop at the first sign of it
 private static final int SETTLE_TICKS = 60;
 private static final int HOLD_TICKS = 40; // 2s at target speed before braking
 private static final int MAX_ACCEL_TICKS = 160; // 8s - if a target can't be reached within the single shared platform's own bound by then, that is itself the finding (logged), not a reason to build a bigger platform
 private static final int BRAKE_OBSERVE_TICKS = 100; // 5s of braking

 private static final double HALF_EXTENT_A = 0.42500001192092896, HALF_HEIGHT_B = 0.4410937510430813, HALF_EXTENT_C = 1.050000011920929;
 private static final Point3D CHASSIS_CENTER = new Point3D(0, 0.553593748062849, 0.625);
 private static final Point3D WHEEL_RL = new Point3D(0.35, 0.125, -0.0625);
 private static final Point3D WHEEL_FL = new Point3D(-0.375, 0.125, 1.25);
 private static final Point3D WHEEL_FR = new Point3D(0.375, 0.125, 1.25);
 private static final Point3D WHEEL_RR = new Point3D(-0.375, 0.125, -0.0625);
 // Confirmed live: (2500,2500) repeatedly watchdog-crashed (ServerLevel.setChunkForced's own
 // synchronous chunk generation), and a subsequent clean retry at that same location still
 // couldn't get its chunks to report loaded within MAX_TERRAIN_WAIT_TICKS - that region's save
 // data is left in a bad state from the earlier abrupt crashes. (600,600) sits well inside the
 // SAME already-generated area GroundSupportFixtureProbe's own many successful clean runs this
 // session produced (region files for chunk x/z 18-20 confirmed present on disk) - far enough
 // from that fixture's own (625,625)/(625,657) spawn points (>300 blocks) that this probe's
 // PLATFORM_RADIUS=70 footprint cannot overlap them, while needing zero new generation at all.
 private static final int CHUNK_X = 600, CHUNK_Z = 600;
 private static final Logger LOGGER = LogUtils.getLogger();

 private enum GlobalPhase { WAIT_TERRAIN, BUILD_PLATFORM, RUN_SCENARIOS, ALL_DONE }
 private enum Phase { SPAWNING, SETTLE, ACCELERATE, HOLD, BRAKE, DONE, FAILED }

 private static final class Scenario {
  final String label, systemName;
  final double targetSpeed;
  EntityVehicleF_Physics vehicle;
  PartEngine engine;
  Phase phase = Phase.SPAWNING;
  int ticksInPhase = 0;
  double spawnX, spawnZ, spawnSurfaceY;
  double speedAtBrakeStart;
  Scenario(String label, String systemName, double targetSpeed) {
   this.label = label; this.systemName = systemName; this.targetSpeed = targetSpeed;
  }
 }

 private static GlobalPhase globalPhase = GlobalPhase.WAIT_TERRAIN;
 private static int terrainWaitTicksElapsed = 0;
 private static List<Scenario> scenarios;
 private static int currentScenarioIndex = 0;

 public static void maybeRun(ServerLevel level) {
  if (!ENABLED) return;
  switch (globalPhase) {
   case WAIT_TERRAIN -> waitTerrain(level);
   case BUILD_PLATFORM -> buildPlatformOnce(level);
   case RUN_SCENARIOS -> runScenarios(level);
   case ALL_DONE -> {}
  }
 }

 private static void waitTerrain(ServerLevel level) {
  terrainWaitTicksElapsed++;
  var chunkSource = level.getChunkSource();
  boolean allLoaded = true;
  for (int dx = -CHUNK_RADIUS; dx <= CHUNK_RADIUS && allLoaded; dx++)
   for (int dz = -CHUNK_RADIUS; dz <= CHUNK_RADIUS; dz++)
    if (chunkSource.getChunkNow(CHUNK_X + dx, CHUNK_Z + dz) == null) {
     level.setChunkForced(CHUNK_X + dx, CHUNK_Z + dz, true);
     allLoaded = false;
    }
  if (allLoaded) { globalPhase = GlobalPhase.BUILD_PLATFORM; return; }
  if (terrainWaitTicksElapsed > MAX_TERRAIN_WAIT_TICKS) {
   LOGGER.error("[DIV brakeSweep] FAILED - terrain chunk grid never finished loading");
   globalPhase = GlobalPhase.ALL_DONE;
  }
 }

 private static void buildPlatformOnce(ServerLevel level) {
  int blockX = CHUNK_X * 16 + 8, blockZ = CHUNK_Z * 16 + 8;
  var stone = net.minecraft.world.level.block.Blocks.STONE.defaultBlockState();
  var air = net.minecraft.world.level.block.Blocks.AIR.defaultBlockState();
  for (int dx = -PLATFORM_RADIUS; dx <= PLATFORM_RADIUS; dx++) {
   for (int dz = -PLATFORM_RADIUS; dz <= PLATFORM_RADIUS; dz++) {
    level.setBlock(new BlockPos(blockX + dx, PLATFORM_Y, blockZ + dz), stone, 3);
    for (int dy = 1; dy <= 6; dy++) level.setBlock(new BlockPos(blockX + dx, PLATFORM_Y + dy, blockZ + dz), air, 3);
   }
  }
  var packSpeedFactors = ConfigSystem.settings.general.packSpeedFactors.value;
  var packVehicleScales = ConfigSystem.settings.general.packVehicleScales.value;
  packSpeedFactors.putIfAbsent(PACK_ID, 1.0);
  packVehicleScales.putIfAbsent(PACK_ID, 1.0);
  scenarios = buildScenarios();
  LOGGER.info("[DIV brakeSweep] platform built at blockX=" + blockX + " blockZ=" + blockZ + " - running " + scenarios.size()
    + " scenarios sequentially: " + scenarios.stream().map(s -> s.label).reduce("", (a,b) -> a + " " + b));
  globalPhase = GlobalPhase.RUN_SCENARIOS;
 }

 // 8 two-body cases (f0p5/f0p4 x 6/8/10/12 m/s) if experimentalTwoBodyChassis=true for this JVM,
 // OR 4 single-body cases (off x 6/8/10/12 m/s) if false - EXPERIMENTAL_TWO_BODY_CHASSIS is a
 // per-JVM static flag, not per-vehicle, so OFF/.5/.4 cannot share one server run (same split
 // GroundSupportFixtureProbe's own DIV_GROUND_SUPPORT_FIXTURE/_OFF build.gradle gates use).
 private static List<Scenario> buildScenarios() {
  boolean twoBody = !"false".equalsIgnoreCase(System.getProperty("div.experimentalTwoBodyChassis", "true"));
  double[] speeds = {6.0, 8.0, 10.0, 12.0};
  List<Scenario> list = new ArrayList<>();
  if (twoBody) {
   for (String frac : new String[]{"f0p5","f0p4"})
    for (double v : speeds) list.add(new Scenario(frac + "@" + v, frac + "-s" + (int) v, v));
  } else {
   for (double v : speeds) list.add(new Scenario("off@" + v, "off-s" + (int) v, v));
  }
  return list;
 }

 private static void runScenarios(ServerLevel level) {
  if (currentScenarioIndex >= scenarios.size()) { globalPhase = GlobalPhase.ALL_DONE; LOGGER.info("[DIV brakeSweep] all scenarios finished"); return; }
  Scenario s = scenarios.get(currentScenarioIndex);
  try {
   switch (s.phase) {
    case SPAWNING -> spawn(level, s);
    case SETTLE -> settle(s);
    case ACCELERATE -> accelerate(s);
    case HOLD -> hold(s);
    case BRAKE -> brake(s);
    case DONE, FAILED -> advanceToNextScenario(s);
   }
  } catch (RuntimeException ex) {
   LOGGER.error("[DIV brakeSweep] scenario=" + s.label + " FAILED - unexpected exception in phase " + s.phase, ex);
   s.phase = Phase.FAILED;
  }
 }

 private static void advanceToNextScenario(Scenario s) {
  if (s.vehicle != null) s.vehicle.remove();
  currentScenarioIndex++;
 }

 private static void spawn(ServerLevel level, Scenario s) {
  int blockX = CHUNK_X * 16 + 8, blockZ = CHUNK_Z * 16 + 8;
  int surfaceY = PLATFORM_Y + 1;
  var wheelSlotFL = wheelSlot(WHEEL_FL, true);
  var wheelSlotFR = wheelSlot(WHEEL_FR, true);
  var wheelSlotRL = wheelSlot(WHEEL_RL, false);
  var wheelSlotRR = wheelSlot(WHEEL_RR, false);
  var engineSlot = engineSlot();

  JSONVehicle definition = buildVehicleDefinition(s.systemName);
  definition.parts = List.of(wheelSlotFL, wheelSlotFR, wheelSlotRL, wheelSlotRR, engineSlot);
  AWrapperWorld world = mcinterface1211.WrapperWorld.getWrapperFor(level);
  var item = new ItemVehicle(definition, definition.definitions.get(0), PACK_ID);
  var vehicle = new EntityVehicleF_Physics(world, null, item, null);
  vehicle.position.set(blockX + 0.5, surfaceY + 1.0, blockZ + 0.5);
  vehicle.orientation.set(new RotationMatrix());
  world.spawnEntity(vehicle);

  addWheel(vehicle, wheelSlotFL);
  addWheel(vehicle, wheelSlotFR);
  addWheel(vehicle, wheelSlotRL);
  addWheel(vehicle, wheelSlotRR);
  s.engine = addEngine(vehicle, engineSlot);

  s.vehicle = vehicle;
  s.spawnX = blockX + 0.5; s.spawnZ = blockZ + 0.5; s.spawnSurfaceY = surfaceY;
  s.phase = Phase.SETTLE;
  s.ticksInPhase = 0;
  LOGGER.info("[DIV brakeSweep] scenario=" + s.label + " spawned at " + vehicle.position + " targetSpeed=" + s.targetSpeed + " key=" + PACK_ID + ":" + s.systemName + " - beginning SETTLE phase");
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
  mainBox.width = (float) (HALF_EXTENT_A * 2);
  mainBox.height = (float) (HALF_HEIGHT_B * 2);
  var zMarkerFront = new JSONCollisionBox();
  zMarkerFront.pos = new Point3D(CHASSIS_CENTER.x, CHASSIS_CENTER.y, CHASSIS_CENTER.z + HALF_EXTENT_C);
  zMarkerFront.width = 0; zMarkerFront.height = 0;
  var zMarkerRear = new JSONCollisionBox();
  zMarkerRear.pos = new Point3D(CHASSIS_CENTER.x, CHASSIS_CENTER.y, CHASSIS_CENTER.z - HALF_EXTENT_C);
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

 private static JSONPartDefinition wheelSlot(Point3D localPos, boolean steers) {
  var slot = new JSONPartDefinition();
  slot.pos = localPos; slot.turnsWithSteer = steers; slot.types = List.of("ground_wheel");
  return slot;
 }

 private static void addWheel(EntityVehicleF_Physics vehicle, JSONPartDefinition slot) {
  var ground = new JSONPart.JSONPartGroundDevice();
  ground.isWheel = true; ground.width = 0.4f; ground.height = 0.4f;
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

 private static minecrafttransportsimulator.jsondefs.JSONSubDefinition firstSubDefinition(JSONPart p) {
  var sub = new minecrafttransportsimulator.jsondefs.JSONSubDefinition();
  sub.subName = ""; sub.name = p.systemName;
  return sub;
 }

 private static JSONPartDefinition engineSlot() {
  var slot = new JSONPartDefinition();
  slot.pos = new Point3D(0, 0, 0); slot.types = List.of("engine");
  return slot;
 }

 private static PartEngine addEngine(EntityVehicleF_Physics vehicle, JSONPartDefinition slot) {
  var engineDef = new JSONPart.JSONPartEngine();
  engineDef.type = JSONPart.EngineType.NORMAL;
  engineDef.gearRatios = List.of(-2.0f, 0.0f, 2.0f);
  engineDef.maxRPM = 6000; engineDef.maxSafeRPM = 5500; engineDef.idleRPM = 800; engineDef.startRPM = 400; engineDef.stallRPM = 200;
  var def = new JSONPart();
  def.packID = PACK_ID; def.systemName = "engine";
  def.generic = new JSONPart.JSONPartGeneric();
  def.engine = engineDef;
  def.definitions = List.of(firstSubDefinition(def));
  def.rendering = new minecrafttransportsimulator.jsondefs.JSONRendering();
  def.rendering.modelType = minecrafttransportsimulator.jsondefs.JSONRendering.ModelType.NONE;
  def.general = new minecrafttransportsimulator.jsondefs.AJSONItem.General();
  def.general.health = 100;
  var item = new ItemPartEngine(def, def.definitions.get(0), PACK_ID);
  var engine = new PartEngine(vehicle, null, slot, item, null);
  vehicle.addPart(engine, false);
  forceEngineRunning(engine);
  return engine;
 }

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

 private static double forwardSpeed(Scenario s) {
  return s.vehicle.velocity * s.vehicle.speedFactor * 20;
 }

 private static void logState(Scenario s, String phaseLabel) {
  var v = s.vehicle;
  LOGGER.info("[DIV brakeSweep] scenario=" + s.label + " phase=" + phaseLabel + " tick=" + s.ticksInPhase
    + " speed=" + forwardSpeed(s) + " position=" + v.position
    + " orientation(m00,m11,m22)=(" + v.orientation.m00 + "," + v.orientation.m11 + "," + v.orientation.m22 + ")"
    + " motion=" + v.motion + " throttle=" + v.throttleVar.currentValue + " brakeActive=" + v.brakeVar.isActive);
 }

 private static boolean checkBoundedOrFail(Scenario s) {
  var v = s.vehicle;
  if (!allFinite(s)) { fail(s, "non-finite vehicle state at phase=" + s.phase + " tick=" + s.ticksInPhase + " position=" + v.position + " motion=" + v.motion); return false; }
  double dx = v.position.x - s.spawnX, dz = v.position.z - s.spawnZ;
  double horizontalFromSpawn = Math.sqrt(dx * dx + dz * dz);
  double verticalFromSurface = Math.abs(v.position.y - s.spawnSurfaceY);
  if (horizontalFromSpawn > PLATFORM_BOUND_HORIZONTAL || verticalFromSurface > PLATFORM_BOUND_VERTICAL) {
   fail(s, "left the bounded platform area at phase=" + s.phase + " tick=" + s.ticksInPhase
     + " horizontalFromSpawn=" + horizontalFromSpawn + " (bound=" + PLATFORM_BOUND_HORIZONTAL + ")"
     + " verticalFromSurface=" + verticalFromSurface + " (bound=" + PLATFORM_BOUND_VERTICAL + ") position=" + v.position);
   return false;
  }
  if (s.phase != Phase.SPAWNING && s.phase != Phase.SETTLE && v.orientation.m11 < UPRIGHT_MIN_VERTICALITY) {
   fail(s, "rolled over at phase=" + s.phase + " tick=" + s.ticksInPhase + " upVectorY(m11)=" + v.orientation.m11 + " position=" + v.position);
   return false;
  }
  return true;
 }

 private static void fail(Scenario s, String reason) {
  LOGGER.error("[DIV brakeSweep] scenario=" + s.label + " STOPPED (preserved as evidence) - " + reason);
  s.phase = Phase.FAILED;
 }

 private static void settle(Scenario s) {
  s.ticksInPhase++;
  if (!checkBoundedOrFail(s)) return;
  if (s.ticksInPhase >= SETTLE_TICKS) {
   s.phase = Phase.ACCELERATE; s.ticksInPhase = 0;
   LOGGER.info("[DIV brakeSweep] scenario=" + s.label + " beginning ACCELERATE phase toward targetSpeed=" + s.targetSpeed + " m/s");
  }
 }

 private static void accelerate(Scenario s) {
  s.ticksInPhase++;
  if (!checkBoundedOrFail(s)) return;
  forceEngineRunning(s.engine);
  double speed = forwardSpeed(s);
  s.vehicle.throttleVar.setTo(speed < s.targetSpeed ? 1.0 : 0.0, false);
  if (s.ticksInPhase % 20 == 0) logState(s, "ACCELERATE");
  if (speed >= s.targetSpeed * 0.9 || s.ticksInPhase >= MAX_ACCEL_TICKS) {
   if (s.ticksInPhase >= MAX_ACCEL_TICKS && speed < s.targetSpeed * 0.9) {
    LOGGER.warn("[DIV brakeSweep] scenario=" + s.label + " never reached targetSpeed=" + s.targetSpeed + " within MAX_ACCEL_TICKS - proceeding to HOLD/BRAKE at actual speed=" + speed + " anyway (itself a finding)");
   }
   s.phase = Phase.HOLD; s.ticksInPhase = 0;
   LOGGER.info("[DIV brakeSweep] scenario=" + s.label + " beginning HOLD phase at speed=" + speed);
  }
 }

 private static void hold(Scenario s) {
  s.ticksInPhase++;
  if (!checkBoundedOrFail(s)) return;
  forceEngineRunning(s.engine);
  double speed = forwardSpeed(s);
  s.vehicle.throttleVar.setTo(speed < s.targetSpeed ? 1.0 : 0.0, false);
  if (s.ticksInPhase % 10 == 0) logState(s, "HOLD");
  if (s.ticksInPhase >= HOLD_TICKS) {
   s.speedAtBrakeStart = speed;
   s.vehicle.throttleVar.setTo(0.0, false);
   s.vehicle.brakeVar.setActive(true, false);
   s.phase = Phase.BRAKE; s.ticksInPhase = 0;
   LOGGER.info("[DIV brakeSweep] scenario=" + s.label + " beginning BRAKE phase speedAtBrakeStart=" + s.speedAtBrakeStart
     + " up(m01,m11,m21)=(" + s.vehicle.orientation.m01 + "," + s.vehicle.orientation.m11 + "," + s.vehicle.orientation.m21 + ")");
  }
 }

 private static void brake(Scenario s) {
  s.ticksInPhase++;
  if (!checkBoundedOrFail(s)) return;
  var v = s.vehicle;
  LOGGER.info("[DIV brakeSweep] scenario=" + s.label + " BRAKE tick=" + s.ticksInPhase
    + " speed=" + forwardSpeed(s) + " position=" + v.position
    + " up(m01,m11,m21)=(" + v.orientation.m01 + "," + v.orientation.m11 + "," + v.orientation.m21 + ")"
    + " angularVelocityProxy(m00,m22)=(" + v.orientation.m00 + "," + v.orientation.m22 + ")"
    + " brakeActive=" + v.brakeVar.isActive);
  if (s.ticksInPhase >= BRAKE_OBSERVE_TICKS) {
   s.phase = Phase.DONE;
   LOGGER.info("[DIV brakeSweep] scenario=" + s.label + " COMPLETE - observed full BRAKE window without leaving the bounded platform or rolling past upVectorY<" + UPRIGHT_MIN_VERTICALITY);
  }
 }
}
