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
import org.slf4j.Logger;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * MINECRAFT-526: closes the disclosed gap every prior flight/rotor ticket has explicitly carried
 * in its own test plan - MINECRAFT-521's PR #19 ("No live {@code EntityVehicleF_Physics}/Sable
 * runtime fixture exists in this repo - {@code stepFlight}'s actual force/torque application is
 * not unit-testable") and MINECRAFT-522's PR #17 ("In-game helicopter feel/calibration - not run
 * this pass"). {@code FlightForcesTest}/{@code RotorForcesTest} already prove the pure math is
 * correct in isolation; nothing before this ticket has exercised the actual live decision
 * {@code PhysicsBridge.Body.step} makes - dispatching a real spawned vehicle to
 * {@code stepFlight}/{@code stepRotor} via the {@code VehicleMovementMixin}/{@code
 * VehicleForcesMixin} seam, against a real {@code Overrides}-resolved {@code tuning} object - the
 * same category of gap {@code BrakeSweepProbe} already closed for the ground domain's ticket-520
 * (brake bias).
 *
 * <p>Opt-in, dev/isolated-host-only, OFF by default (same convention as every other probe in this
 * package) - a strict no-op unless {@code -Ddiv.experimentalFlightRotorRuntimeFixtureProbe=true}.
 * Never touches a real vehicle or world content outside its own bounded chunk grid. Scenarios run
 * strictly SEQUENTIALLY (one vehicle/world-generation operation active at a time), same reasoning
 * as {@code BrakeSweepProbe}'s own doc (review 53330's watchdog-crash finding).
 *
 * <p>Method, per vehicle kind (fixed-wing / rotorcraft): one ACTIVATION_DEFAULT run proves (a)
 * this vehicle is actually PhysicsBridge-managed and dispatched to the correct stepper - a
 * fixed-wing/rotorcraft left undispatched or misdispatched to GROUND would simply free-fall at
 * {@code g}=9.81 m/s^2 with no corrective force at all, trivially distinguishable from the damped
 * response an engaged {@code stepFlight}/{@code stepRotor} actually produces - and (b) logs that
 * response's own magnitude as the unconfigured/FALLBACK baseline. One CONFIGURED run, with an
 * operator override already in place at spawn, re-runs the identical control input and logs the
 * same metrics for comparison against that baseline - this is {@code liftSlope}/{@code
 * controlSurfaceSlope} (fixed-wing, MINECRAFT-521) or {@code cyclicLimitDegrees}/{@code
 * cyclicAuthorityScale}/{@code yawAuthorityScale} (rotorcraft, MINECRAFT-522) actually reaching
 * {@code stepFlight}/{@code stepRotor}'s real force/torque math on a live body, not merely a
 * resolved {@code Tuning} record. One LIVE_RELOAD run starts unconfigured, observes a baseline,
 * then calls the exact production hot-reload entrypoint ({@code PhysicsBridge.reloadOverrides()},
 * the same one {@code OverridesHotReload} calls on its own mtime-poll) against a VALID edit to the
 * live config file for this same still-active vehicle (no respawn), observes the response change
 * in place, then calls it again against an INVALID edit and observes that the configured response
 * from the valid edit is retained unchanged - the "retain last-known-good on errors" contract
 * {@code OverridesTest}/{@code Overrides.apply}'s own doc already proves in isolation, now proven
 * against a live body's actual tick-to-tick force application too.
 *
 * <p>What this proves: the dispatch/live-field-application seam actually works end to end on a
 * real spawned vehicle. What this does NOT prove: that the resulting flight/hover feel is
 * well-tuned, realistic, or calibrated - same disclosed limit {@code BrakeSweepProbe} itself
 * carries for ground feel. The numeric bounds/speeds/tilt angles below are a best-effort choice
 * reasoned from this repo's own force-model formulas (see inline rationale at each constant), not
 * an empirically live-tuned value - this file has been compiled against the real {@code mts.jar}/
 * Sable/NeoForge classpath (see this ticket's VERIFICATION.md entry) but, same as every prior
 * flight/rotor PR's own disclosed limit, has not yet been run against an actual live dev server;
 * an operator doing that run for the first time should treat a watchdog timeout or an out-of-
 * bounds failure as a signal to shrink {@code OBSERVE_TICKS}/speeds further, the same iterative
 * process {@code BrakeSweepProbe}'s own doc history (review 53327/53330) already went through.
 */
public final class FlightRotorRuntimeFixtureProbe {
 private FlightRotorRuntimeFixtureProbe() {}
 private static final boolean ENABLED = Boolean.getBoolean("div.experimentalFlightRotorRuntimeFixtureProbe");
 private static final String PACK_ID = "divtest";
 private static final int MAX_TERRAIN_WAIT_TICKS = 200;
 // Chunk-generation cost is what crashed BrakeSweepProbe's own first version (review 53330) -
 // kept to the same order of magnitude as that probe's own now-proven-safe CHUNK_RADIUS=5/
 // WheelAuxProbe's CHUNK_RADIUS, and at a chunk coordinate region none of BrakeSweepProbe (600),
 // WheelAuxProbe (900), or TurnComparisonProbe (10200) use.
 private static final int CHUNK_RADIUS = 6;
 private static final int CHUNK_X = 14400, CHUNK_Z = 14400;
 private static final int SPAWN_Y = 160;
 // No block platform is built at all (unlike every ground-domain probe) - flight/rotor scenarios
 // never need solid ground under them, only the chunk grid above to be loaded/generated so the
 // entity exists and ticks; this also means zero extra block-placement cost on top of the
 // chunk-generation cost already budgeted above.
 private static final double BOUND_HORIZONTAL = 70; // matches every ground probe's own proven-safe PLATFORM_RADIUS
 private static final double BOUND_VERTICAL = 90;
 private static final int SETTLE_TICKS = 15;
 private static final int OBSERVE_TICKS = 50; // 2.5s - ACTIVATION_DEFAULT/CONFIGURED's single observation window
 private static final int RELOAD_WINDOW_TICKS = 35; // 1.75s - each of LIVE_RELOAD's three windows (baseline/valid/invalid)
 private static final Logger LOGGER = LogUtils.getLogger();

 // ---- Fixed-wing scenario constants ----
 // 8 m/s - deliberately slow (same speed family BrakeSweepProbe's own 6-12 m/s already proved
 // safe in this chunk-generation regime), held by the same closed-loop throttle BrakeSweepProbe
 // already uses so horizontal drift stays bounded rather than growing with unopposed thrust.
 private static final double FW_TARGET_SPEED = 8.0;
 private static final double FW_AILERON_DEGREES = 20.0; // within EntityVehicleF_Physics.MAX_AILERON_ANGLE=25
 private static final float FW_WING_AREA = 18f, FW_WING_SPAN = 10f, FW_AILERON_AREA = 1.6f,
   FW_ELEVATOR_AREA = 1.6f, FW_RUDDER_AREA = 1f, FW_TAIL_DISTANCE = 5f, FW_DRAG_COEFF = 0.04f, FW_CROSS_SECTION = 3f;
 private static final int FW_EMPTY_MASS = 900;

 // ---- Rotorcraft scenario constants ----
 private static final double ROTOR_AILERON_DEGREES = 5.0; // small on purpose - cyclicAuthorityScale multiplies
   // torque directly (not gated by this angle vs. the clamp), so a small, translation-drift-cheap
   // tilt is already enough to show the configured-vs-default authority difference cleanly.
 // Chosen so default-FALLBACK driveForce(8000N)*collective(~0.71*1.35-0.35=0.609) roughly
 // balances this chassis's own weight (500kg*9.81=4905N vs thrust~4870N) - i.e. closest to a
 // bounded, near-stationary hover, rather than a sustained climb/descent that would need a much
 // bigger BOUND_VERTICAL or a shorter window. See RotorForces.collective/lift.
 private static final double ROTOR_THROTTLE = 0.71;
 private static final int ROTOR_PROPELLER_DIAMETER_INCHES = 48;
 private static final float ROTOR_TAIL_DISTANCE = 4f;
 private static final int ROTOR_EMPTY_MASS = 500;

 private enum Kind { FIXED_WING, ROTOR }
 private enum Role { ACTIVATION_DEFAULT, CONFIGURED, LIVE_RELOAD }
 private enum Phase { SPAWNING, SETTLE, OBSERVE, OBSERVE_BASELINE, APPLY_VALID, OBSERVE_VALID, APPLY_INVALID, OBSERVE_INVALID, DONE, FAILED }
 private enum GlobalPhase { WAIT_TERRAIN, WRITE_INITIAL_CONFIG, RUN_SCENARIOS, RESTORE_CONFIG, ALL_DONE }

 private static final class Scenario {
  final String label, systemName; final Kind kind; final Role role;
  EntityVehicleF_Physics vehicle;
  PartEngine engine;
  Phase phase = Phase.SPAWNING;
  int ticksInPhase = 0;
  double spawnX, spawnZ, spawnY;
  // Logged at the start of whichever window is currently active, so each window's own delta can
  // be reported independently (baseline vs valid vs invalid for LIVE_RELOAD; the single window
  // for ACTIVATION_DEFAULT/CONFIGURED).
  double windowStartM11, windowStartVerticalSpeed;
  double lastValidWindowM11Delta = Double.NaN;
  Scenario(String label, String systemName, Kind kind, Role role) { this.label = label; this.systemName = systemName; this.kind = kind; this.role = role; }
 }

 private static GlobalPhase globalPhase = GlobalPhase.WAIT_TERRAIN;
 private static int terrainWaitTicksElapsed = 0;
 private static List<Scenario> scenarios;
 private static int currentScenarioIndex = 0;
 // Snapshot of the config file's content before this probe ever wrote to it (or empty if it did
 // not exist yet) - restored verbatim at ALL_DONE so this opt-in fixture leaves the shared
 // run/config/dynamic-immersive-vehicles.json exactly as it found it (deterministic cleanup, same
 // requirement this ticket's own scope names explicitly).
 private static String originalConfigContent;
 private static boolean originalConfigExisted;

 public static void maybeRun(ServerLevel level) {
  if (!ENABLED) return;
  switch (globalPhase) {
   case WAIT_TERRAIN -> waitTerrain(level);
   case WRITE_INITIAL_CONFIG -> writeInitialConfigOnce();
   case RUN_SCENARIOS -> runScenarios(level);
   case RESTORE_CONFIG -> restoreConfigOnce();
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
  if (allLoaded) { globalPhase = GlobalPhase.WRITE_INITIAL_CONFIG; return; }
  if (terrainWaitTicksElapsed > MAX_TERRAIN_WAIT_TICKS) {
   LOGGER.error("[DIV flightRotorFixture] FAILED - terrain chunk grid never finished loading");
   globalPhase = GlobalPhase.ALL_DONE;
  }
 }

 private static Path configPath() { return FMLPaths.CONFIGDIR.get().resolve("dynamic-immersive-vehicles.json"); }
 private static Gson gson() { return new GsonBuilder().setPrettyPrinting().create(); }

 /** Reads the current config file (or an empty {@code {"vehicles":{}}} shell if none exists yet
  * - {@code Overrides.load()} normally guarantees one exists by the time any probe runs, but this
  * does not assume that), merges in one {@code key -> fields} entry under {@code "vehicles"}
  * WITHOUT disturbing any other entry already present, and writes the result back - never a
  * blind full-file overwrite, so nothing an operator (or another probe/regression fixture) has
  * already configured on this shared file is lost. */
 private static void mergeVehicleOverrideAndWrite(String key, Map<String, Double> fields) {
  JsonObject root;
  try {
   root = JsonParser.parseString(Files.readString(configPath())).getAsJsonObject();
  } catch (IOException | RuntimeException ex) {
   root = new JsonObject();
  }
  if (!root.has("vehicles")) root.add("vehicles", new JsonObject());
  JsonObject vehicles = root.getAsJsonObject("vehicles");
  JsonObject entry = new JsonObject();
  fields.forEach((fieldName, value) -> entry.addProperty(fieldName, value));
  vehicles.add(key, entry);
  try {
   Files.createDirectories(configPath().getParent());
   Files.writeString(configPath(), gson().toJson(root));
  } catch (IOException ex) {
   LOGGER.error("[DIV flightRotorFixture] FAILED to write config at " + configPath(), ex);
  }
 }

 private static void writeInitialConfigOnce() {
  try {
   originalConfigExisted = Files.exists(configPath());
   originalConfigContent = originalConfigExisted ? Files.readString(configPath()) : null;
  } catch (IOException ex) {
   LOGGER.error("[DIV flightRotorFixture] FAILED to snapshot existing config before writing - aborting rather than risk losing it", ex);
   globalPhase = GlobalPhase.ALL_DONE;
   return;
  }
  var packSpeedFactors = ConfigSystem.settings.general.packSpeedFactors.value;
  var packVehicleScales = ConfigSystem.settings.general.packVehicleScales.value;
  packSpeedFactors.putIfAbsent(PACK_ID, 1.0);
  packVehicleScales.putIfAbsent(PACK_ID, 1.0);
  scenarios = buildScenarios();
  // Only the two CONFIGURED scenarios' keys get an override entry up front - ACTIVATION_DEFAULT
  // and LIVE_RELOAD's own systemNames are deliberately absent from the file at this point, so
  // Overrides.resolved(...) falls through to FALLBACK for them exactly like an unconfigured real
  // vehicle (LIVE_RELOAD writes its own entry live, mid-scenario - see applyValidConfig below).
  mergeVehicleOverrideAndWrite(PACK_ID + ":" + "fw-configured",
    Map.of("liftSlope", 9.5, "controlSurfaceSlope", 9.5));
  mergeVehicleOverrideAndWrite(PACK_ID + ":" + "rotor-configured",
    Map.of("cyclicLimitDegrees", 35.0, "cyclicAuthorityScale", 4.0, "yawAuthorityScale", 3.0));
  var report = PhysicsBridge.reloadOverrides();
  LOGGER.info("[DIV flightRotorFixture] initial CONFIGURED overrides written and reloaded: " + report.configResult().message());
  if (!report.configResult().success()) {
   LOGGER.error("[DIV flightRotorFixture] FAILED - the initial CONFIGURED-scenario override file itself failed to validate: " + report.configResult().message());
   globalPhase = GlobalPhase.ALL_DONE;
   return;
  }
  LOGGER.info("[DIV flightRotorFixture] running " + scenarios.size() + " scenarios sequentially: "
    + scenarios.stream().map(s -> s.label).reduce("", (a, b) -> a + " " + b));
  globalPhase = GlobalPhase.RUN_SCENARIOS;
 }

 private static List<Scenario> buildScenarios() {
  List<Scenario> list = new ArrayList<>();
  list.add(new Scenario("fw-activation-default", "fw-activation-default", Kind.FIXED_WING, Role.ACTIVATION_DEFAULT));
  list.add(new Scenario("fw-configured", "fw-configured", Kind.FIXED_WING, Role.CONFIGURED));
  list.add(new Scenario("fw-live-reload", "fw-live-reload", Kind.FIXED_WING, Role.LIVE_RELOAD));
  list.add(new Scenario("rotor-activation-default", "rotor-activation-default", Kind.ROTOR, Role.ACTIVATION_DEFAULT));
  list.add(new Scenario("rotor-configured", "rotor-configured", Kind.ROTOR, Role.CONFIGURED));
  list.add(new Scenario("rotor-live-reload", "rotor-live-reload", Kind.ROTOR, Role.LIVE_RELOAD));
  return list;
 }

 private static void runScenarios(ServerLevel level) {
  if (currentScenarioIndex >= scenarios.size()) { globalPhase = GlobalPhase.RESTORE_CONFIG; return; }
  Scenario s = scenarios.get(currentScenarioIndex);
  try {
   switch (s.phase) {
    case SPAWNING -> spawn(level, s);
    case SETTLE -> settle(s);
    case OBSERVE -> observe(s, Phase.DONE, null);
    case OBSERVE_BASELINE -> observe(s, Phase.APPLY_VALID, "BASELINE (unconfigured)");
    case APPLY_VALID -> applyValidConfig(s);
    case OBSERVE_VALID -> observe(s, Phase.APPLY_INVALID, "VALID live-reload");
    case APPLY_INVALID -> applyInvalidConfig(s);
    case OBSERVE_INVALID -> observe(s, Phase.DONE, "INVALID live-reload (must retain the VALID config above)");
    case DONE, FAILED -> advanceToNextScenario(s);
   }
  } catch (RuntimeException ex) {
   LOGGER.error("[DIV flightRotorFixture] scenario=" + s.label + " FAILED - unexpected exception in phase " + s.phase, ex);
   s.phase = Phase.FAILED;
  }
 }

 private static void advanceToNextScenario(Scenario s) {
  if (s.vehicle != null) s.vehicle.remove();
  currentScenarioIndex++;
 }

 private static void spawn(ServerLevel level, Scenario s) {
  int blockX = CHUNK_X * 16 + 8, blockZ = CHUNK_Z * 16 + 8;
  JSONVehicle definition = s.kind == Kind.FIXED_WING ? buildFixedWingDefinition(s.systemName) : buildRotorDefinition(s.systemName);
  var engineSlot = engineSlot();
  var parts = new ArrayList<JSONPartDefinition>();
  parts.add(engineSlot);
  JSONPartDefinition propellerSlot = null;
  if (s.kind == Kind.ROTOR) { propellerSlot = propellerSlot(); parts.add(propellerSlot); }
  definition.parts = parts;
  AWrapperWorld world = mcinterface1211.WrapperWorld.getWrapperFor(level);
  var item = new ItemVehicle(definition, definition.definitions.get(0), PACK_ID);
  var vehicle = new EntityVehicleF_Physics(world, null, item, null);
  vehicle.position.set(blockX + 0.5, SPAWN_Y, blockZ + 0.5);
  vehicle.orientation.set(new RotationMatrix());
  // Fixed-wing scenarios seed forward airspeed directly via initial motion (PhysicsBridge.active's
  // own first-activation path derives the Sable body's initial velocity from v.motion*speedFactor
  // *20 - see that method's own doc) rather than a takeoff run; rotorcraft scenarios start from
  // rest (a hover test has no need for one).
  if (s.kind == Kind.FIXED_WING) vehicle.motion.set(0, 0, FW_TARGET_SPEED / 20.0);
  world.spawnEntity(vehicle);
  s.engine = addEngine(vehicle, engineSlot);
  if (propellerSlot != null) addPropeller(vehicle, propellerSlot);

  s.vehicle = vehicle;
  s.spawnX = blockX + 0.5; s.spawnZ = blockZ + 0.5; s.spawnY = SPAWN_Y;
  s.phase = Phase.SETTLE;
  s.ticksInPhase = 0;
  LOGGER.info("[DIV flightRotorFixture] scenario=" + s.label + " spawned at " + vehicle.position + " key=" + PACK_ID + ":" + s.systemName + " - beginning SETTLE phase");
 }

 private static JSONVehicle buildFixedWingDefinition(String systemName) {
  var v = baseVehicleDefinition(systemName);
  v.motorized.isAircraft = true;
  v.motorized.wingArea = FW_WING_AREA; v.motorized.wingSpan = FW_WING_SPAN;
  v.motorized.aileronArea = FW_AILERON_AREA; v.motorized.elevatorArea = FW_ELEVATOR_AREA; v.motorized.rudderArea = FW_RUDDER_AREA;
  v.motorized.tailDistance = FW_TAIL_DISTANCE; v.motorized.dragCoefficient = FW_DRAG_COEFF; v.motorized.crossSectionalArea = FW_CROSS_SECTION;
  v.motorized.emptyMass = FW_EMPTY_MASS;
  return v;
 }

 private static JSONVehicle buildRotorDefinition(String systemName) {
  var v = baseVehicleDefinition(systemName);
  // Deliberately NOT setting motorized.isAircraft/isBlimp - PhysicsBridge.selectStepper's own
  // contract (MINECRAFT-497 fix) is that an installed isRotor propeller wins dispatch
  // unconditionally, independent of that flag; leaving it false is itself part of this
  // scenario's activation evidence (ROTOR dispatch from the installed part alone).
  v.motorized.tailDistance = ROTOR_TAIL_DISTANCE;
  v.motorized.emptyMass = ROTOR_EMPTY_MASS;
  return v;
 }

 private static JSONVehicle baseVehicleDefinition(String systemName) {
  var v = new JSONVehicle();
  v.packID = PACK_ID; v.systemName = systemName;
  v.motorized = new JSONVehicle.VehicleMotorized();
  v.motorized.litVariable = ""; v.motorized.panel = "";
  v.definitions = List.of(firstSubDefinition(v));
  v.rendering = new minecrafttransportsimulator.jsondefs.JSONRendering();
  v.rendering.modelType = minecrafttransportsimulator.jsondefs.JSONRendering.ModelType.NONE;
  v.general = new minecrafttransportsimulator.jsondefs.AJSONItem.General();
  v.general.health = 100;
  // Minimal single-box collision shape - same minimal-marker pattern BrakeSweepProbe's own
  // buildVehicleDefinition uses; this ticket's scenarios need no wheel geometry at all.
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
  forceEngineRunning(engine);
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

 private static double verticalSpeedMps(Scenario s) {
  return s.vehicle.motion.y * s.vehicle.speedFactor * 20;
 }

 private static double forwardSpeedMps(Scenario s) {
  return s.vehicle.velocity * s.vehicle.speedFactor * 20;
 }

 private static void applyControlInputs(Scenario s) {
  var v = s.vehicle;
  forceEngineRunning(s.engine);
  if (s.kind == Kind.FIXED_WING) {
   v.throttleVar.setTo(forwardSpeedMps(s) < FW_TARGET_SPEED ? 1.0 : 0.0, false);
   v.aileronInputVar.setTo(FW_AILERON_DEGREES, false);
  } else {
   v.throttleVar.setTo(ROTOR_THROTTLE, false);
   v.aileronInputVar.setTo(ROTOR_AILERON_DEGREES, false);
  }
 }

 private static boolean checkBoundedOrFail(Scenario s) {
  var v = s.vehicle;
  if (!allFinite(s)) { fail(s, "non-finite vehicle state at phase=" + s.phase + " tick=" + s.ticksInPhase + " position=" + v.position + " motion=" + v.motion); return false; }
  double dx = v.position.x - s.spawnX, dz = v.position.z - s.spawnZ, dy = v.position.y - s.spawnY;
  double horizontalFromSpawn = Math.sqrt(dx * dx + dz * dz);
  if (horizontalFromSpawn > BOUND_HORIZONTAL || Math.abs(dy) > BOUND_VERTICAL) {
   fail(s, "left the bounded flight box at phase=" + s.phase + " tick=" + s.ticksInPhase
     + " horizontalFromSpawn=" + horizontalFromSpawn + " (bound=" + BOUND_HORIZONTAL + ")"
     + " verticalFromSpawn=" + dy + " (bound=" + BOUND_VERTICAL + ") position=" + v.position);
   return false;
  }
  return true;
 }

 private static void fail(Scenario s, String reason) {
  LOGGER.error("[DIV flightRotorFixture] scenario=" + s.label + " STOPPED (preserved as evidence) - " + reason);
  s.phase = Phase.FAILED;
 }

 private static void logState(Scenario s, String label) {
  var v = s.vehicle;
  LOGGER.info("[DIV flightRotorFixture] scenario=" + s.label + " " + label + " tick=" + s.ticksInPhase
    + " forwardSpeed=" + forwardSpeedMps(s) + " verticalSpeed=" + verticalSpeedMps(s) + " position=" + v.position
    + " up(m01,m11,m21)=(" + v.orientation.m01 + "," + v.orientation.m11 + "," + v.orientation.m21 + ")"
    + " throttle=" + v.throttleVar.currentValue + " aileron=" + v.aileronInputVar.currentValue);
 }

 private static void settle(Scenario s) {
  s.ticksInPhase++;
  if (!checkBoundedOrFail(s)) return;
  forceEngineRunning(s.engine);
  // No control input yet during SETTLE - lets the freshly-activated Sable body's very first
  // tick(s) (construction, initial-velocity seeding) happen before this scenario's own
  // measurement window starts, same SETTLE-before-measuring structure BrakeSweepProbe uses.
  if (s.ticksInPhase >= SETTLE_TICKS) {
   s.phase = s.role == Role.LIVE_RELOAD ? Phase.OBSERVE_BASELINE : Phase.OBSERVE;
   s.ticksInPhase = 0;
   s.windowStartM11 = s.vehicle.orientation.m11;
   s.windowStartVerticalSpeed = verticalSpeedMps(s);
   LOGGER.info("[DIV flightRotorFixture] scenario=" + s.label + " SETTLE complete - beginning " + s.phase + " window");
  }
 }

 /** Shared window-runner for every OBSERVE* phase: applies this scenario's fixed control input
  * every tick, logs periodically, and on window completion reports the delta from
  * {@code windowStartM11}/{@code windowStartVerticalSpeed} (this window's own actual measured
  * response) before transitioning to {@code next}. {@code windowLabel} null means "just finish
  * the scenario" (ACTIVATION_DEFAULT/CONFIGURED); non-null additionally logs that label so
  * LIVE_RELOAD's three windows are each individually identifiable in the log. */
 private static void observe(Scenario s, Phase next, String windowLabel) {
  s.ticksInPhase++;
  if (!checkBoundedOrFail(s)) return;
  applyControlInputs(s);
  if (s.ticksInPhase % 10 == 0) logState(s, "OBSERVE" + (windowLabel != null ? "[" + windowLabel + "]" : ""));
  int windowTicks = s.role == Role.LIVE_RELOAD ? RELOAD_WINDOW_TICKS : OBSERVE_TICKS;
  if (s.ticksInPhase < windowTicks) return;
  double m11Delta = s.windowStartM11 - s.vehicle.orientation.m11; // positive = rolled away from level
  double verticalSpeedDelta = verticalSpeedMps(s) - s.windowStartVerticalSpeed;
  String freeFallComparison = " (free-fall-only over this window would be verticalSpeed<=-" + (9.81 * windowTicks / 20.0) + ")";
  LOGGER.info("[DIV flightRotorFixture] scenario=" + s.label + " " + (windowLabel != null ? windowLabel + " " : "") + "WINDOW COMPLETE"
    + " rollM11Delta=" + m11Delta + " verticalSpeedDelta=" + verticalSpeedDelta + " verticalSpeedNow=" + verticalSpeedMps(s) + freeFallComparison);
  if (next == Phase.APPLY_INVALID && Double.isFinite(m11Delta)) s.lastValidWindowM11Delta = m11Delta;
  if (next == Phase.DONE && s.role == Role.LIVE_RELOAD && Double.isFinite(s.lastValidWindowM11Delta)) {
   double ratio = s.lastValidWindowM11Delta == 0 ? Double.NaN : m11Delta / s.lastValidWindowM11Delta;
   LOGGER.info("[DIV flightRotorFixture] scenario=" + s.label
     + " INVALID-vs-VALID rollM11Delta ratio=" + ratio + " (expected close to 1.0 - an invalid reload must retain the prior VALID config, not fall back to FALLBACK or break)");
  }
  s.phase = next;
  s.ticksInPhase = 0;
  s.windowStartM11 = s.vehicle.orientation.m11;
  s.windowStartVerticalSpeed = verticalSpeedMps(s);
  if (next == Phase.DONE) LOGGER.info("[DIV flightRotorFixture] scenario=" + s.label + " COMPLETE");
 }

 private static void applyValidConfig(Scenario s) {
  Map<String, Double> fields = s.kind == Kind.FIXED_WING
    ? Map.of("liftSlope", 9.5, "controlSurfaceSlope", 9.5)
    : Map.of("cyclicLimitDegrees", 35.0, "cyclicAuthorityScale", 4.0, "yawAuthorityScale", 3.0);
  mergeVehicleOverrideAndWrite(PACK_ID + ":" + s.systemName, fields);
  var report = PhysicsBridge.reloadOverrides();
  if (!report.configResult().success()) {
   fail(s, "VALID live-reload unexpectedly FAILED to apply: " + report.configResult().message() + " - this is itself a finding, not expected scenario behavior");
   return;
  }
  LOGGER.info("[DIV flightRotorFixture] scenario=" + s.label + " VALID config written and reloaded live (no respawn): " + report.configResult().message()
    + " liveUpdatedVehicles=" + report.vehiclesUpdatedLive() + " fieldsNeedingRespawn=" + report.fieldsNeedingRespawn());
  s.phase = Phase.OBSERVE_VALID;
  s.ticksInPhase = 0;
  s.windowStartM11 = s.vehicle.orientation.m11;
  s.windowStartVerticalSpeed = verticalSpeedMps(s);
 }

 private static void applyInvalidConfig(Scenario s) {
  // Out-of-range per Overrides.Tuning#validate: liftSlope/controlSurfaceSlope must be in (0,10],
  // cyclicLimitDegrees must be in (0, RotorForces.MAX_CYCLIC_LIMIT_DEGREES] - both reject <= 0.
  Map<String, Double> fields = s.kind == Kind.FIXED_WING
    ? Map.of("liftSlope", -1.0, "controlSurfaceSlope", 9.5)
    : Map.of("cyclicLimitDegrees", 0.0, "cyclicAuthorityScale", 4.0, "yawAuthorityScale", 3.0);
  mergeVehicleOverrideAndWrite(PACK_ID + ":" + s.systemName, fields);
  var report = PhysicsBridge.reloadOverrides();
  if (report.configResult().success()) {
   fail(s, "INVALID live-reload unexpectedly SUCCEEDED (should have been rejected, retaining the prior VALID config) - this is a regression finding, not expected scenario behavior: " + report.configResult().message());
   return;
  }
  LOGGER.info("[DIV flightRotorFixture] scenario=" + s.label + " INVALID config correctly REJECTED, last-known-good (the VALID config above) retained: " + report.configResult().message());
  s.phase = Phase.OBSERVE_INVALID;
  s.ticksInPhase = 0;
  s.windowStartM11 = s.vehicle.orientation.m11;
  s.windowStartVerticalSpeed = verticalSpeedMps(s);
 }

 private static void restoreConfigOnce() {
  try {
   if (originalConfigExisted) Files.writeString(configPath(), originalConfigContent);
   else Files.deleteIfExists(configPath());
  } catch (IOException ex) {
   LOGGER.error("[DIV flightRotorFixture] FAILED to restore original config at " + configPath() + " - manual cleanup needed", ex);
  }
  var report = PhysicsBridge.reloadOverrides();
  LOGGER.info("[DIV flightRotorFixture] all scenarios finished; original config restored and reloaded: " + report.configResult().message());
  globalPhase = GlobalPhase.ALL_DONE;
 }
}
