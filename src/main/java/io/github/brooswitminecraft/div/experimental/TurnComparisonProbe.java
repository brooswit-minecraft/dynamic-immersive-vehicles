package io.github.brooswitminecraft.div.experimental;
import com.mojang.logging.LogUtils;
import minecrafttransportsimulator.baseclasses.Point3D;
import minecrafttransportsimulator.baseclasses.RotationMatrix;
import minecrafttransportsimulator.entities.instances.APart;
import minecrafttransportsimulator.entities.instances.EntityVehicleF_Physics;
import minecrafttransportsimulator.entities.instances.PartEngine;
import minecrafttransportsimulator.entities.instances.PartGroundDevice;
import minecrafttransportsimulator.entities.instances.PartSeat;
import minecrafttransportsimulator.items.instances.ItemPartEngine;
import minecrafttransportsimulator.items.instances.ItemPartGroundDevice;
import minecrafttransportsimulator.items.instances.ItemPartSeat;
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
 * Review 53331/53332/53333/53337/53339/53340: baseline-vs-tuned sustained-turn comparison,
 * answering John's hands-on "flops over to the side when I turn" with an OCCUPIED vehicle (per
 * 53332's "self-right source gates require occupied seat and positive support, so prove it
 * actually activates in test; unoccupied prior fixture cannot validate self-right" - confirmed
 * by reading PhysicsBridge's own call site: {@code occupied} is true only when some
 * {@code PartSeat} has a non-null {@code rider}). A seat part is attached and a spawned,
 * wrapped passive entity is set as its rider via {@code PartSeat.setRider}, so this is the
 * FIRST of this ticket's own probes able to exercise the self-right assist's real activation
 * gate at all - the existing GroundSupportFixtureProbe/BrakeSweepProbe never attach a rider and
 * therefore could never have validated it.
 *
 * <p>Two scenarios, run sequentially on one shared platform (same proven-safe pattern
 * BrakeSweepProbe settled on after 53330's correction - one vehicle at a time, discarded before
 * the next starts, so world-generation/platform-build cost stays fixed regardless of scenario
 * count):
 * <ul>
 *  <li>{@code baseline} - the REAL verified installed values from ticket comment 53333:
 *  upperMassFraction=0.5, self-right strength=200/damping=80/maxTorque=600/fade=10-50deg,
 *  lateralGripMultiplier left at its 1.0 default.</li>
 *  <li>{@code tuned} - upperMassFraction=0.1 (lowers COM ~0.176m on this ATV's own
 *  HALF_HEIGHT_B=0.441, per John's own math in 53332 - same total mass, same fixed two-half
 *  geometry), self-right strength/damping/maxTorque DOUBLED at the SAME fade angles (the
 *  complaint is severity of the roll, not that assist cuts off too early), and
 *  lateralGripMultiplier=0.5 (a moderate reduction, not 0/0.2, to avoid making steering itself
 *  unresponsive).</li>
 * </ul>
 * Both values are seeded into {@code run/config/dynamic-immersive-vehicles.json} under keys
 * {@code divtest:turncmp-baseline}/{@code divtest:turncmp-tuned} - this probe itself does not
 * touch {@code Overrides}, same reasoning as GroundSupportFixtureProbe's own doc.
 *
 * <p>Method: SETTLE, then closed-loop ACCELERATE to a bounded low-moderate speed (same
 * bang-bang pattern as GroundSupportFixtureProbe/BrakeSweepProbe), then a SUSTAINED TURN phase
 * (continuous rudder input, well beyond a single brief turn) logging every tick's up-vector
 * (roll/pitch), yaw, and speed - the existing, unmodified {@code -Ddiv.debugGroundContact}
 * diagnostic's own {@code selfRight occupied=...torque=...} log line (PhysicsBridge.java, same
 * line 53333's own verified excerpt came from) provides the activation evidence in parallel, no
 * new production logging needed.
 */
public final class TurnComparisonProbe {
 private TurnComparisonProbe() {}
 private static final boolean ENABLED = Boolean.getBoolean("div.experimentalTurnComparisonProbe");
 private static final String PACK_ID = "divtest";
 private static final int MAX_TERRAIN_WAIT_TICKS = 200;
 private static final int CHUNK_RADIUS = 5;
 private static final int PLATFORM_Y = 100;
 private static final int PLATFORM_RADIUS = 70;
 private static final double PLATFORM_BOUND_HORIZONTAL = PLATFORM_RADIUS - 15;
 private static final double PLATFORM_BOUND_VERTICAL = 10;
 private static final double UPRIGHT_MIN_VERTICALITY = 0.3; // looser than other probes - this one specifically wants to see HOW FAR it rolls before (or whether) self-right recovers it, not stop at the first sign of roll
 private static final double TARGET_SPEED = 5.0; // m/s - a bounded, moderate turning speed
 private static final int SETTLE_TICKS = 60, MAX_ACCEL_TICKS = 160, TURN_TICKS = 200; // TURN sustained for 10s, well beyond a brief turn

 private static final double HALF_EXTENT_A = 0.42500001192092896, HALF_HEIGHT_B = 0.4410937510430813, HALF_EXTENT_C = 1.050000011920929;
 private static final Point3D CHASSIS_CENTER = new Point3D(0, 0.553593748062849, 0.625);
 private static final Point3D WHEEL_RL = new Point3D(0.35, 0.125, -0.0625);
 private static final Point3D WHEEL_FL = new Point3D(-0.375, 0.125, 1.25);
 private static final Point3D WHEEL_FR = new Point3D(0.375, 0.125, 1.25);
 private static final Point3D WHEEL_RR = new Point3D(-0.375, 0.125, -0.0625);
 private static final Point3D SEAT_POS = new Point3D(0, 0.7, 0.3); // roughly above the chassis center, a plausible driver-seat position
 private static final int CHUNK_X = 10200, CHUNK_Z = 10200; // far from every other probe's own area
 private static final Logger LOGGER = LogUtils.getLogger();

 private enum GlobalPhase { WAIT_TERRAIN, BUILD_PLATFORM, RUN_SCENARIOS, ALL_DONE }
 private enum Phase { SPAWNING, SETTLE, ACCELERATE, TURN, DONE, FAILED }

 private static final class Scenario {
  final String label, systemName;
  EntityVehicleF_Physics vehicle;
  PartEngine engine;
  net.minecraft.world.entity.animal.Pig riderEntity;
  Phase phase = Phase.SPAWNING;
  int ticksInPhase = 0;
  double spawnX, spawnZ, spawnSurfaceY;
  double minUpY = 1.0; // plain up.y - kept for the bounded-observation-floor check only, NOT the reported roll metric (review 53341: mixes pitch/wheelie with sideways bank)
  // Review 53341: the actual roll-specific metric, matching SelfRightingAssist's own internal
  // math exactly (signed roll about bodyForward alone) - atan2(bodyRight.y, bodyUp.y), NOT
  // plain up.y, which conflates pitch (wheelie, allowed) with sideways bank (the real complaint).
  double maxAbsBankDegrees = 0;
  double prevBankRadians = Double.NaN;
  double maxAbsBankRateDegreesPerTick = 0;
  double maxAbsLateralSlip = 0; // velocity component along the body-right axis - the real sideways slip a turn induces
  Scenario(String label, String systemName) { this.label = label; this.systemName = systemName; }
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
   LOGGER.error("[DIV turnComparison] FAILED - terrain chunk grid never finished loading");
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
  scenarios = List.of(new Scenario("baseline", "turncmp-baseline"), new Scenario("tuned", "turncmp-tuned"));
  LOGGER.info("[DIV turnComparison] platform built at blockX=" + blockX + " blockZ=" + blockZ + " - running " + scenarios.size()
    + " scenarios sequentially: " + scenarios.stream().map(s -> s.label).reduce("", (a, b) -> a + " " + b));
  globalPhase = GlobalPhase.RUN_SCENARIOS;
 }

 private static void runScenarios(ServerLevel level) {
  if (currentScenarioIndex >= scenarios.size()) { globalPhase = GlobalPhase.ALL_DONE; LOGGER.info("[DIV turnComparison] all scenarios finished"); return; }
  Scenario s = scenarios.get(currentScenarioIndex);
  try {
   switch (s.phase) {
    case SPAWNING -> spawn(level, s);
    case SETTLE -> settle(s);
    case ACCELERATE -> accelerate(s);
    case TURN -> turn(s);
    case DONE, FAILED -> advanceToNextScenario(s);
   }
  } catch (RuntimeException ex) {
   LOGGER.error("[DIV turnComparison] scenario=" + s.label + " FAILED - unexpected exception in phase " + s.phase, ex);
   s.phase = Phase.FAILED;
  }
 }

 private static void advanceToNextScenario(Scenario s) {
  if (s.riderEntity != null) s.riderEntity.discard();
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
  var seatSlot = seatSlot();

  JSONVehicle definition = buildVehicleDefinition(s.systemName);
  definition.parts = List.of(wheelSlotFL, wheelSlotFR, wheelSlotRL, wheelSlotRR, engineSlot, seatSlot);
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
  PartSeat seat = addSeat(vehicle, seatSlot);

  // Review 53332's own explicit gate: self-right's `occupied` check (PhysicsBridge.java) looks
  // for a non-null PartSeat.rider - a plain passive mob, spawned and wrapped the same way MTS
  // itself wraps any Entity for IWrapperEntity purposes, satisfies that check without needing a
  // real player. Pig chosen only because it is a trivial, always-available passive vanilla
  // entity with no special AI/interaction behavior relevant here.
  var rider = new net.minecraft.world.entity.animal.Pig(net.minecraft.world.entity.EntityType.PIG, level);
  rider.setPos(vehicle.position.x, vehicle.position.y, vehicle.position.z);
  rider.setNoAi(true);
  level.addFreshEntity(rider);
  var riderWrapper = mcinterface1211.WrapperEntity.getWrapperFor(rider);
  seat.setRider(riderWrapper, true);
  s.riderEntity = rider;

  s.vehicle = vehicle;
  s.spawnX = blockX + 0.5; s.spawnZ = blockZ + 0.5; s.spawnSurfaceY = surfaceY;
  s.phase = Phase.SETTLE;
  s.ticksInPhase = 0;
  LOGGER.info("[DIV turnComparison] scenario=" + s.label + " spawned at " + vehicle.position + " rider=" + rider + " key=" + PACK_ID + ":" + s.systemName + " - beginning SETTLE phase");
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

 private static JSONPartDefinition seatSlot() {
  var slot = new JSONPartDefinition();
  slot.pos = SEAT_POS; slot.isController = true; slot.types = List.of("seat");
  return slot;
 }

 private static PartSeat addSeat(EntityVehicleF_Physics vehicle, JSONPartDefinition slot) {
  var def = new JSONPart();
  def.packID = PACK_ID; def.systemName = "seat";
  def.generic = new JSONPart.JSONPartGeneric();
  def.generic.type = "seat";
  def.seat = new JSONPart.JSONPartSeat();
  def.definitions = List.of(firstSubDefinition(def));
  def.rendering = new minecrafttransportsimulator.jsondefs.JSONRendering();
  def.rendering.modelType = minecrafttransportsimulator.jsondefs.JSONRendering.ModelType.NONE;
  def.general = new minecrafttransportsimulator.jsondefs.AJSONItem.General();
  def.general.health = 100;
  var item = new ItemPartSeat(def, def.definitions.get(0), PACK_ID);
  var seat = new PartSeat(vehicle, null, slot, item, null);
  vehicle.addPart(seat, false);
  return seat;
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
  double bankDegrees = Math.toDegrees(Math.atan2(v.orientation.m10, v.orientation.m11));
  double yawDegrees = Math.toDegrees(Math.atan2(v.orientation.m02, v.orientation.m22));
  LOGGER.info("[DIV turnComparison] scenario=" + s.label + " phase=" + phaseLabel + " tick=" + s.ticksInPhase
    + " speed=" + forwardSpeed(s) + " position=" + v.position
    + " up(m01,m11,m21)=(" + v.orientation.m01 + "," + v.orientation.m11 + "," + v.orientation.m21 + ")"
    + " bankDegrees=" + bankDegrees + " yawDegrees=" + yawDegrees
    + " throttle=" + v.throttleVar.currentValue + " rudder=" + v.rudderInputVar.currentValue);
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
  if (s.phase == Phase.TURN) {
   s.minUpY = Math.min(s.minUpY, v.orientation.m11);
   // Review 53341: signed bank = atan2(bodyRight.y, bodyUp.y) about bodyForward alone, the SAME
   // formula SelfRightingAssist itself uses internally (its own class doc's algebraic
   // justification) - NOT plain up.y, which conflates pitch/wheelie with sideways bank. bodyUp is
   // column 1 of the orientation matrix (confirmed via PhysicsBridge's own mapping, m01/m11/m21);
   // bodyRight is column 0 by the same convention (m00/m10/m20), so bodyRight.y = m10.
   double bankRadians = Math.atan2(v.orientation.m10, v.orientation.m11);
   double bankDegrees = Math.toDegrees(bankRadians);
   s.maxAbsBankDegrees = Math.max(s.maxAbsBankDegrees, Math.abs(bankDegrees));
   if (!Double.isNaN(s.prevBankRadians)) {
    double rateRadians = Math.atan2(Math.sin(bankRadians - s.prevBankRadians), Math.cos(bankRadians - s.prevBankRadians));
    s.maxAbsBankRateDegreesPerTick = Math.max(s.maxAbsBankRateDegreesPerTick, Math.abs(Math.toDegrees(rateRadians)));
   }
   s.prevBankRadians = bankRadians;
   // Lateral slip: the vehicle's own real velocity vector (v.motion, blocks/tick) projected onto
   // the body-right axis, converted to m/s the same way forwardSpeed() converts v.velocity.
   double lateralSlip = (v.motion.x * v.orientation.m00 + v.motion.y * v.orientation.m10 + v.motion.z * v.orientation.m20) * v.speedFactor * 20;
   s.maxAbsLateralSlip = Math.max(s.maxAbsLateralSlip, Math.abs(lateralSlip));
  }
  if (s.phase != Phase.SPAWNING && s.phase != Phase.SETTLE && v.orientation.m11 < UPRIGHT_MIN_VERTICALITY) {
   fail(s, "rolled over PAST the probe's own observation floor at phase=" + s.phase + " tick=" + s.ticksInPhase
     + " upVectorY(m11)=" + v.orientation.m11 + " position=" + v.position);
   return false;
  }
  return true;
 }

 private static void fail(Scenario s, String reason) {
  LOGGER.error("[DIV turnComparison] scenario=" + s.label + " STOPPED (preserved as evidence) - minUpYSeen=" + s.minUpY
    + " maxAbsBankDegrees=" + s.maxAbsBankDegrees + " maxAbsBankRateDegPerTick=" + s.maxAbsBankRateDegreesPerTick
    + " maxAbsLateralSlip=" + s.maxAbsLateralSlip + " - " + reason);
  s.phase = Phase.FAILED;
 }

 private static void settle(Scenario s) {
  s.ticksInPhase++;
  if (!checkBoundedOrFail(s)) return;
  if (s.ticksInPhase >= SETTLE_TICKS) {
   s.phase = Phase.ACCELERATE; s.ticksInPhase = 0;
   LOGGER.info("[DIV turnComparison] scenario=" + s.label + " beginning ACCELERATE phase toward targetSpeed=" + TARGET_SPEED + " m/s");
  }
 }

 private static void accelerate(Scenario s) {
  s.ticksInPhase++;
  if (!checkBoundedOrFail(s)) return;
  forceEngineRunning(s.engine);
  double speed = forwardSpeed(s);
  s.vehicle.throttleVar.setTo(speed < TARGET_SPEED ? 1.0 : 0.0, false);
  if (s.ticksInPhase % 20 == 0) logState(s, "ACCELERATE");
  if (speed >= TARGET_SPEED * 0.9 || s.ticksInPhase >= MAX_ACCEL_TICKS) {
   s.phase = Phase.TURN; s.ticksInPhase = 0;
   s.vehicle.rudderInputVar.setTo(40.0, false); // within MAX_RUDDER_ANGLE=45 - the same real, sustained steering input the bounded brake-sweep/turn work already established as "a clear, deliberate turning signal"
   LOGGER.info("[DIV turnComparison] scenario=" + s.label + " beginning sustained TURN phase at speed=" + speed + " rudder=40deg");
  }
 }

 private static void turn(Scenario s) {
  s.ticksInPhase++;
  if (!checkBoundedOrFail(s)) return;
  forceEngineRunning(s.engine);
  double speed = forwardSpeed(s);
  s.vehicle.throttleVar.setTo(speed < TARGET_SPEED ? 1.0 : 0.0, false); // keep driving through the turn - a stationary vehicle can't meaningfully flop
  if (s.ticksInPhase % 10 == 0) logState(s, "TURN");
  if (s.ticksInPhase >= TURN_TICKS) {
   s.phase = Phase.DONE;
   LOGGER.info("[DIV turnComparison] scenario=" + s.label + " COMPLETE - sustained TURN finished, minUpYSeen=" + s.minUpY
     + " (1.0=never tilted; <" + UPRIGHT_MIN_VERTICALITY + " would have STOPPED as a rollover)"
     + " maxAbsBankDegrees=" + s.maxAbsBankDegrees + " maxAbsBankRateDegPerTick=" + s.maxAbsBankRateDegreesPerTick
     + " maxAbsLateralSlip=" + s.maxAbsLateralSlip + " m/s");
  }
 }
}
