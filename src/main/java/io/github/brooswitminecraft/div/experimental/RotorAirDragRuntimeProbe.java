package io.github.brooswitminecraft.div.experimental;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import io.github.brooswitminecraft.div.PhysicsBridge;
import minecrafttransportsimulator.baseclasses.Point3D;
import minecrafttransportsimulator.baseclasses.RotationMatrix;
import minecrafttransportsimulator.entities.instances.APart;
import minecrafttransportsimulator.entities.instances.EntityVehicleF_Physics;
import minecrafttransportsimulator.entities.instances.PartEngine;
import minecrafttransportsimulator.entities.instances.PartPropeller;
import minecrafttransportsimulator.items.instances.ItemPartEngine;
import minecrafttransportsimulator.items.instances.ItemPartPropeller;
import minecrafttransportsimulator.items.instances.ItemVehicle;
import minecrafttransportsimulator.jsondefs.JSONCollisionBox;
import minecrafttransportsimulator.jsondefs.JSONCollisionGroup;
import minecrafttransportsimulator.jsondefs.JSONPart;
import minecrafttransportsimulator.jsondefs.JSONPartDefinition;
import minecrafttransportsimulator.jsondefs.JSONVehicle;
import minecrafttransportsimulator.mcinterface.AWrapperWorld;
import minecrafttransportsimulator.systems.ConfigSystem;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.fml.loading.FMLPaths;
import org.joml.Vector3d;
import org.slf4j.Logger;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

/**
 * MINECRAFT-543 review (Wroosbit/self-review, PR #40, comments 54188/54189): {@code
 * RotorForcesTest}'s own bounded-response coverage manually integrates a {@code Vector3d} -
 * representative-mass numerical integration, not evidence that {@code
 * PhysicsBridge.stepRotor}'s real dispatch/force-application path, on a real Sable {@code
 * RigidBodyHandle}, actually applies this drag. Closes exactly that gap, the same way {@link
 * FlightRotorRuntimeFixtureProbe} already closed the analogous gap for MINECRAFT-521/522/526:
 * spawns one real rotorcraft, lets it activate (confirming real {@code stepRotor} dispatch via
 * {@link PhysicsBridge#debugExecutedStepSnapshot}), injects a KNOWN nonzero native linear
 * velocity directly into its live Sable handle (via the new {@link
 * PhysicsBridge#debugInjectLinearVelocity} test seam, the translational twin of the existing
 * {@link PhysicsBridge#debugInjectAngularVelocity} MINECRAFT-526 seam), then lets the real
 * production tick loop run and reads the live handle's velocity back (via the new {@link
 * PhysicsBridge#debugReadLinearVelocity}) to demonstrate finite, bounded, non-reversing
 * reduction - plus an unconfigured (multiplier {@code 0.0}, the documented FALLBACK) control
 * scenario proving the exact same native geometry/velocity produces NO drag at all when the
 * feature is off, the ticket's own "default off"/zero-off-parity requirement exercised against a
 * real body rather than only {@code RotorForcesTest}'s pure-function coverage.
 *
 * <p>Opt-in, dev/isolated-host-only, OFF by default (same convention as every other probe in this
 * package) - a strict no-op unless {@code -Ddiv.experimentalRotorAirDragRuntimeProbe=true}. Never
 * touches a real vehicle or world content outside its own bounded chunk grid, claimed at a chunk
 * coordinate region ({@code CHUNK_X}/{@code CHUNK_Z} below) none of this package's other probes
 * use. Both scenarios run with the engine present but never set {@code running} - throttle stays
 * at its constructed-default {@code 0}, so {@code RotorForces.collective}/{@code lift} are
 * exactly {@code 0} and the only force {@code stepRotor} can apply besides gravity is this
 * ticket's own drag term - an isolated measurement with no thrust/gravity confound on the
 * horizontal plane this probe actually measures (gravity only ever acts vertically).
 */
public final class RotorAirDragRuntimeProbe {
 private RotorAirDragRuntimeProbe() {}
 private static final boolean ENABLED = Boolean.getBoolean("div.experimentalRotorAirDragRuntimeProbe");
 private static final Logger LOGGER = LogUtils.getLogger();
 private static final String PACK_ID = "divtest";
 private static final int MAX_TERRAIN_WAIT_TICKS = 200;
 private static final int CHUNK_RADIUS = 7;
 // Far from every other probe's own claimed chunk region (600/900/6000/6200/10200/14400 area).
 private static final int CHUNK_X = 20000, CHUNK_Z = 20000;
 private static final int SPAWN_Y = 160;
 private static final int SETTLE_TICKS = 15;
 private static final int OBSERVE_TICKS = 40; // 2.0s
 // Known, nonzero native linear velocity this probe itself injects - not derived from any control
 // input, so the "on"/"off" comparison isolates the drag term alone.
 private static final double INJECTED_SPEED = 20.0; // m/s
 // Representative native motorized fields (same shape FlightRotorRuntimeFixtureProbe's own
 // FW_DRAG_COEFF/FW_CROSS_SECTION use) - set on BOTH scenarios identically, so the "off" control
 // proves the multiplier itself (not an absence of native geometry) is what gates the force to
 // zero.
 private static final float NATIVE_DRAG_COEFFICIENT = 0.8f;
 private static final float NATIVE_CROSS_SECTIONAL_AREA = 3.0f;
 private static final double CONFIGURED_MULTIPLIER = 1.0; // Overrides.Tuning.rotorAirDragCoefficient for the "on" scenario
 private static final float ROTOR_TAIL_DISTANCE = 4f;
 private static final int ROTOR_EMPTY_MASS = 500;
 private static final int ROTOR_PROPELLER_DIAMETER_INCHES = 48;

 private enum Phase { SPAWNING, SETTLE, INJECT, OBSERVE, DONE, FAILED }
 private enum GlobalPhase { WAIT_TERRAIN, WRITE_CONFIG, RUN_SCENARIOS, RESTORE_CONFIG, RELEASE_CHUNKS, ALL_DONE }

 private static final class Scenario {
  final String label, systemName; final boolean dragConfigured;
  EntityVehicleF_Physics vehicle;
  Phase phase = Phase.SPAWNING;
  int ticksInPhase = 0;
  double spawnX, spawnY, spawnZ;
  double initialHorizontalSpeed = Double.NaN;
  Vector3d initialHorizontalDirection;
  Scenario(String label, String systemName, boolean dragConfigured) { this.label = label; this.systemName = systemName; this.dragConfigured = dragConfigured; }
 }

 private static GlobalPhase globalPhase = GlobalPhase.WAIT_TERRAIN;
 private static int terrainWaitTicksElapsed = 0;
 private static List<Scenario> scenarios;
 private static int currentScenarioIndex = 0;
 private static int scenariosPassed = 0;
 private static String originalConfigContent;
 private static boolean originalConfigExisted;
 private static boolean configWasWritten = false;
 private static final Set<Long> chunksForcedByThisProbe = new java.util.HashSet<>();
 private static long chunkKey(int x, int z) { return ((long) x << 32) ^ (z & 0xffffffffL); }

 public static void maybeRun(ServerLevel level) {
  if (!ENABLED) return;
  switch (globalPhase) {
   case WAIT_TERRAIN -> waitTerrain(level);
   case WRITE_CONFIG -> writeConfigOnce();
   case RUN_SCENARIOS -> runScenarios(level);
   case RESTORE_CONFIG -> restoreConfigOnce();
   case RELEASE_CHUNKS -> releaseChunksOnce(level);
   case ALL_DONE -> {}
  }
 }

 private static void waitTerrain(ServerLevel level) {
  terrainWaitTicksElapsed++;
  var chunkSource = level.getChunkSource();
  var alreadyForced = level.getForcedChunks();
  boolean allLoaded = true;
  for (int dx = -CHUNK_RADIUS; dx <= CHUNK_RADIUS && allLoaded; dx++)
   for (int dz = -CHUNK_RADIUS; dz <= CHUNK_RADIUS; dz++)
    if (chunkSource.getChunkNow(CHUNK_X + dx, CHUNK_Z + dz) == null) {
     int cx = CHUNK_X + dx, cz = CHUNK_Z + dz;
     if (!alreadyForced.contains(net.minecraft.world.level.ChunkPos.asLong(cx, cz))) {
      level.setChunkForced(cx, cz, true);
      chunksForcedByThisProbe.add(chunkKey(cx, cz));
     }
     allLoaded = false;
    }
  if (allLoaded) { globalPhase = GlobalPhase.WRITE_CONFIG; return; }
  if (terrainWaitTicksElapsed > MAX_TERRAIN_WAIT_TICKS) {
   LOGGER.error("[DIV rotorAirDragRuntimeProbe] FAILED - terrain chunk grid never finished loading");
   globalPhase = GlobalPhase.RESTORE_CONFIG;
  }
 }

 private static Path configPath() { return FMLPaths.CONFIGDIR.get().resolve("dynamic-immersive-vehicles.json"); }
 private static Gson gson() { return new GsonBuilder().setPrettyPrinting().create(); }

 private static boolean mergeVehicleOverrideAndWrite(String key, double rotorAirDragCoefficient) {
  JsonObject root;
  try {
   root = JsonParser.parseString(Files.readString(configPath())).getAsJsonObject();
  } catch (IOException | RuntimeException ex) {
   root = new JsonObject();
  }
  if (!root.has("vehicles")) root.add("vehicles", new JsonObject());
  JsonObject vehicles = root.getAsJsonObject("vehicles");
  JsonObject entry = new JsonObject();
  entry.addProperty("rotorAirDragCoefficient", rotorAirDragCoefficient);
  vehicles.add(key, entry);
  try {
   Files.createDirectories(configPath().getParent());
   Files.writeString(configPath(), gson().toJson(root));
   return true;
  } catch (IOException ex) {
   LOGGER.error("[DIV rotorAirDragRuntimeProbe] FAILED to write config at " + configPath(), ex);
   return false;
  }
 }

 private static void writeConfigOnce() {
  try {
   originalConfigExisted = Files.exists(configPath());
   originalConfigContent = originalConfigExisted ? Files.readString(configPath()) : null;
  } catch (IOException ex) {
   LOGGER.error("[DIV rotorAirDragRuntimeProbe] FAILED to snapshot existing config before writing - aborting rather than risk losing it", ex);
   globalPhase = GlobalPhase.RESTORE_CONFIG;
   return;
  }
  configWasWritten = true;
  var packSpeedFactors = ConfigSystem.settings.general.packSpeedFactors.value;
  var packVehicleScales = ConfigSystem.settings.general.packVehicleScales.value;
  packSpeedFactors.putIfAbsent(PACK_ID, 1.0);
  packVehicleScales.putIfAbsent(PACK_ID, 1.0);
  scenarios = List.of(
    new Scenario("on", "rotor-air-drag-on", true),
    new Scenario("off", "rotor-air-drag-off", false));
  // Only the "on" scenario's key gets an override entry - "off" stays entirely absent from the
  // file, resolving to the documented FALLBACK (0.0) exactly like a real unconfigured vehicle.
  if (!mergeVehicleOverrideAndWrite(PACK_ID + ":" + "rotor-air-drag-on", CONFIGURED_MULTIPLIER)) {
   globalPhase = GlobalPhase.RESTORE_CONFIG;
   return;
  }
  var report = PhysicsBridge.reloadOverrides();
  LOGGER.info("[DIV rotorAirDragRuntimeProbe] config written and reloaded: " + report.configResult().message());
  if (!report.configResult().success()) {
   LOGGER.error("[DIV rotorAirDragRuntimeProbe] FAILED - the override file itself failed to validate: " + report.configResult().message());
   globalPhase = GlobalPhase.RESTORE_CONFIG;
   return;
  }
  LOGGER.info("[DIV rotorAirDragRuntimeProbe] running " + scenarios.size() + " scenarios sequentially");
  globalPhase = GlobalPhase.RUN_SCENARIOS;
 }

 private static void runScenarios(ServerLevel level) {
  if (currentScenarioIndex >= scenarios.size()) {
   LOGGER.info("[DIV rotorAirDragRuntimeProbe] ALL SCENARIOS FINISHED - " + scenariosPassed + "/" + scenarios.size() + " PASSED"
     + (scenariosPassed == scenarios.size() ? "" : " - at least one scenario FAILED, see its own STOPPED line above"));
   globalPhase = GlobalPhase.RESTORE_CONFIG;
   return;
  }
  Scenario s = scenarios.get(currentScenarioIndex);
  try {
   switch (s.phase) {
    case SPAWNING -> spawn(level, s);
    case SETTLE -> settle(s);
    case INJECT -> inject(s);
    case OBSERVE -> observe(s);
    case DONE, FAILED -> advanceToNextScenario(s);
   }
  } catch (RuntimeException ex) {
   LOGGER.error("[DIV rotorAirDragRuntimeProbe] scenario=" + s.label + " FAILED - unexpected exception in phase " + s.phase, ex);
   s.phase = Phase.FAILED;
  }
 }

 private static void advanceToNextScenario(Scenario s) {
  if (s.phase == Phase.DONE) scenariosPassed++;
  if (s.vehicle != null) s.vehicle.remove();
  currentScenarioIndex++;
 }

 private static void fail(Scenario s, String reason) {
  LOGGER.error("[DIV rotorAirDragRuntimeProbe] scenario=" + s.label + " STOPPED (preserved as evidence) - FAIL - " + reason);
  s.phase = Phase.FAILED;
 }

 private static void spawn(ServerLevel level, Scenario s) {
  int blockX = CHUNK_X * 16 + 8, blockZ = CHUNK_Z * 16 + 8;
  JSONVehicle definition = buildRotorDefinition(s.systemName);
  var engineSlot = engineSlot();
  var propellerSlot = propellerSlot();
  definition.parts = List.of(engineSlot, propellerSlot);
  AWrapperWorld world = mcinterface1211.WrapperWorld.getWrapperFor(level);
  var item = new ItemVehicle(definition, definition.definitions.get(0), PACK_ID);
  var vehicle = new EntityVehicleF_Physics(world, null, item, null);
  vehicle.position.set(blockX + 0.5, SPAWN_Y, blockZ + 0.5);
  vehicle.orientation.set(new RotationMatrix());
  world.spawnEntity(vehicle);
  s.vehicle = vehicle;
  addEngine(vehicle, engineSlot); // never set running - throttle/thrust stay exactly 0
  addPropeller(vehicle, propellerSlot);
  s.spawnX = blockX + 0.5; s.spawnY = SPAWN_Y; s.spawnZ = blockZ + 0.5;
  s.phase = Phase.SETTLE;
  s.ticksInPhase = 0;
  LOGGER.info("[DIV rotorAirDragRuntimeProbe] scenario=" + s.label + " spawned at " + vehicle.position + " key=" + PACK_ID + ":" + s.systemName);
 }

 private static JSONVehicle buildRotorDefinition(String systemName) {
  var v = new JSONVehicle();
  v.packID = PACK_ID; v.systemName = systemName;
  v.motorized = new JSONVehicle.VehicleMotorized();
  v.motorized.litVariable = ""; v.motorized.panel = "";
  v.motorized.tailDistance = ROTOR_TAIL_DISTANCE;
  v.motorized.emptyMass = ROTOR_EMPTY_MASS;
  // Native geometry/drag-coefficient this ticket's RotorForces.translationalDragForce actually
  // consumes - set identically on both the "on" and "off" scenario so the multiplier alone, not
  // an absence of native geometry, is what the "off" control actually proves.
  v.motorized.dragCoefficient = NATIVE_DRAG_COEFFICIENT;
  v.motorized.crossSectionalArea = NATIVE_CROSS_SECTIONAL_AREA;
  v.definitions = List.of(firstSubDefinition(v));
  v.rendering = new minecrafttransportsimulator.jsondefs.JSONRendering();
  v.rendering.modelType = minecrafttransportsimulator.jsondefs.JSONRendering.ModelType.NONE;
  v.general = new minecrafttransportsimulator.jsondefs.AJSONItem.General();
  v.general.health = 100;
  var mainBox = new JSONCollisionBox();
  mainBox.pos = new Point3D(0, 0, 0);
  mainBox.width = 2f; mainBox.height = 1f;
  var group = new JSONCollisionGroup();
  group.collisionTypes = Set.of(JSONCollisionGroup.CollisionType.VEHICLE);
  group.collisions = List.of(mainBox);
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

 private static JSONPartDefinition engineSlot() {
  var slot = new JSONPartDefinition();
  slot.pos = new Point3D(0, 0, 0); slot.types = List.of("engine");
  return slot;
 }

 private static JSONPartDefinition propellerSlot() {
  var slot = new JSONPartDefinition();
  slot.pos = new Point3D(0, 0, 1); slot.types = List.of("propeller");
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
  return engine;
 }

 private static void addPropeller(EntityVehicleF_Physics vehicle, JSONPartDefinition slot) {
  var propellerDef = new JSONPart.JSONPartPropeller();
  propellerDef.isRotor = true;
  propellerDef.diameter = ROTOR_PROPELLER_DIAMETER_INCHES;
  var def = new JSONPart();
  def.packID = PACK_ID; def.systemName = "rotor";
  def.generic = new JSONPart.JSONPartGeneric();
  def.propeller = propellerDef;
  def.definitions = List.of(firstSubDefinition(def));
  def.rendering = new minecrafttransportsimulator.jsondefs.JSONRendering();
  def.rendering.modelType = minecrafttransportsimulator.jsondefs.JSONRendering.ModelType.NONE;
  def.general = new minecrafttransportsimulator.jsondefs.AJSONItem.General();
  def.general.health = 100;
  var item = new ItemPartPropeller(def, def.definitions.get(0), PACK_ID);
  APart part = new PartPropeller(vehicle, null, slot, item, null);
  vehicle.addPart(part, false);
 }

 private static boolean allFinite(Scenario s) {
  var v = s.vehicle;
  double[] values = {v.position.x, v.position.y, v.position.z, v.motion.x, v.motion.y, v.motion.z};
  for (double d : values) if (!Double.isFinite(d)) return false;
  return true;
 }

 private static boolean checkBoundedOrFail(Scenario s) {
  if (!allFinite(s)) { fail(s, "non-finite vehicle state at phase=" + s.phase + " tick=" + s.ticksInPhase); return false; }
  return true;
 }

 private static void settle(Scenario s) {
  s.ticksInPhase++;
  if (!checkBoundedOrFail(s)) return;
  // No engine ever set running (see class doc) - no control input needed. Just lets the freshly-
  // activated Sable body's very first tick(s) happen before this scenario injects its own known
  // velocity.
  if (s.ticksInPhase >= SETTLE_TICKS) {
   var snapshot = PhysicsBridge.debugExecutedStepSnapshot(s.vehicle);
   if (snapshot == null || snapshot.executedStepCount() <= 0 || !"ROTOR".equals(snapshot.lastExecutedStepperName())) {
    fail(s, "dispatch check failed - debugExecutedStepSnapshot=" + snapshot + " (expected lastExecutedStepperName=ROTOR with executedStepCount>0)");
    return;
   }
   s.phase = Phase.INJECT;
   s.ticksInPhase = 0;
  }
 }

 private static void inject(Scenario s) {
  // Horizontal-only (y=0) so gravity's own independent vertical effect never confounds the
  // horizontal-plane speed this probe actually measures - see class doc.
  PhysicsBridge.debugInjectLinearVelocity(s.vehicle, new Vector3d(INJECTED_SPEED, 0, 0));
  Vector3d afterInjection = PhysicsBridge.debugReadLinearVelocity(s.vehicle);
  if (afterInjection == null) { fail(s, "no active Body found immediately after injection"); return; }
  s.initialHorizontalSpeed = Math.hypot(afterInjection.x, afterInjection.z);
  s.initialHorizontalDirection = new Vector3d(afterInjection.x, 0, afterInjection.z);
  if (s.initialHorizontalSpeed > 1e-6) s.initialHorizontalDirection.normalize();
  LOGGER.info("[DIV rotorAirDragRuntimeProbe] scenario=" + s.label + " INJECTED native linear velocity=" + afterInjection
    + " initialHorizontalSpeed=" + s.initialHorizontalSpeed);
  if (!(s.initialHorizontalSpeed > INJECTED_SPEED * 0.9)) {
   fail(s, "injection itself produced an unexpected horizontal speed=" + s.initialHorizontalSpeed + " (expected close to " + INJECTED_SPEED + ")");
   return;
  }
  s.phase = Phase.OBSERVE;
  s.ticksInPhase = 0;
 }

 private static void observe(Scenario s) {
  s.ticksInPhase++;
  if (!checkBoundedOrFail(s)) return;
  if (s.ticksInPhase < OBSERVE_TICKS) return;
  Vector3d finalVelocity = PhysicsBridge.debugReadLinearVelocity(s.vehicle);
  if (finalVelocity == null) { fail(s, "no active Body found at OBSERVE window end"); return; }
  double finalHorizontalSpeed = Math.hypot(finalVelocity.x, finalVelocity.z);
  // No-reversal: the final horizontal velocity's component ALONG the original direction must
  // never go negative - the exact production-path analogue of RotorForcesTest's own no-reversal
  // unit coverage, now checked against the real native handle instead of a manually-integrated
  // Vector3d.
  double alongOriginalDirection = finalVelocity.x * s.initialHorizontalDirection.x + finalVelocity.z * s.initialHorizontalDirection.z;
  LOGGER.info("[DIV rotorAirDragRuntimeProbe] scenario=" + s.label + " OBSERVE WINDOW COMPLETE finalVelocity=" + finalVelocity
    + " finalHorizontalSpeed=" + finalHorizontalSpeed + " initialHorizontalSpeed=" + s.initialHorizontalSpeed
    + " alongOriginalDirection=" + alongOriginalDirection);
  if (alongOriginalDirection < -1e-6) {
   fail(s, "velocity REVERSED past zero along its own original direction - alongOriginalDirection=" + alongOriginalDirection);
   return;
  }
  if (s.dragConfigured) {
   if (!(finalHorizontalSpeed < s.initialHorizontalSpeed * 0.95)) {
    fail(s, "configured (on) scenario did not show a meaningful finite reduction - finalHorizontalSpeed=" + finalHorizontalSpeed
      + " vs initialHorizontalSpeed=" + s.initialHorizontalSpeed);
    return;
   }
   LOGGER.info("[DIV rotorAirDragRuntimeProbe] scenario=" + s.label + " PASSED - real native handle velocity reduced from "
     + s.initialHorizontalSpeed + " to " + finalHorizontalSpeed + " without reversal, via the real PhysicsBridge.stepRotor production path");
  } else {
   if (!(finalHorizontalSpeed > s.initialHorizontalSpeed * 0.99)) {
    fail(s, "unconfigured (off) control scenario showed unexpected drag - finalHorizontalSpeed=" + finalHorizontalSpeed
      + " vs initialHorizontalSpeed=" + s.initialHorizontalSpeed + " (expected no meaningful change - rotorAirDragCoefficient's FALLBACK is 0.0/off)");
    return;
   }
   LOGGER.info("[DIV rotorAirDragRuntimeProbe] scenario=" + s.label + " PASSED - unconfigured native handle velocity stayed at "
     + finalHorizontalSpeed + " (vs initial " + s.initialHorizontalSpeed + "), confirming zero-off-parity on the real production path");
  }
  s.phase = Phase.DONE;
 }

 private static void restoreConfigOnce() {
  if (!configWasWritten) { globalPhase = GlobalPhase.RELEASE_CHUNKS; return; }
  try {
   if (originalConfigExisted) Files.writeString(configPath(), originalConfigContent);
   else Files.deleteIfExists(configPath());
   PhysicsBridge.reloadOverrides();
   LOGGER.info("[DIV rotorAirDragRuntimeProbe] restored pre-probe config content");
  } catch (IOException ex) {
   LOGGER.error("[DIV rotorAirDragRuntimeProbe] FAILED to restore pre-probe config - manual cleanup of " + configPath() + " may be required", ex);
  }
  globalPhase = GlobalPhase.RELEASE_CHUNKS;
 }

 private static void releaseChunksOnce(ServerLevel level) {
  for (long key : chunksForcedByThisProbe) {
   int cx = (int) (key >> 32), cz = (int) key;
   level.setChunkForced(cx, cz, false);
  }
  chunksForcedByThisProbe.clear();
  LOGGER.info("[DIV rotorAirDragRuntimeProbe] released forced chunks - ALL_DONE");
  globalPhase = GlobalPhase.ALL_DONE;
 }
}
