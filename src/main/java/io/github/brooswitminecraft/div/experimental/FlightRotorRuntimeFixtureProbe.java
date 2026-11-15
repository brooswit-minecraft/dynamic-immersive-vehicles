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
import java.util.EnumMap;
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
 * <p>Method, per vehicle kind (fixed-wing / rotorcraft): one ACTIVATION_DEFAULT run asserts (a)
 * this vehicle is actually PhysicsBridge-managed and dispatched to the correct stepper - checked
 * by asserting its vertical speed over the window is far from free-fall-only (a misdispatched
 * vehicle, or one PhysicsBridge never activates at all, simply falls at {@code g}=9.81 m/s^2 with
 * no corrective force) - and (b) records that response's own magnitude as the unconfigured/
 * FALLBACK baseline for the CONFIGURED scenario below to compare against. One CONFIGURED run, with
 * an operator override already in place at spawn, re-runs the identical control input and asserts
 * its own measured response is meaningfully LARGER than that baseline - this is {@code
 * liftSlope}/{@code controlSurfaceSlope} (fixed-wing, MINECRAFT-521) or {@code
 * cyclicLimitDegrees}/{@code cyclicAuthorityScale}/{@code yawAuthorityScale} (rotorcraft,
 * MINECRAFT-522) actually reaching {@code stepFlight}/{@code stepRotor}'s real force/torque math
 * on a live body, not merely a resolved {@code Tuning} record. One LIVE_RELOAD run starts
 * unconfigured, observes a baseline, then calls the exact production hot-reload entrypoint
 * ({@code PhysicsBridge.reloadOverrides()}, the same one {@code OverridesHotReload} calls on its
 * own mtime-poll) against a VALID edit to the live config file for this same still-active vehicle
 * (no respawn), asserts the response changed in place, then calls it again against an INVALID
 * edit and asserts the configured response from the valid edit is retained essentially unchanged
 * - the "retain last-known-good on errors" contract {@code OverridesTest}/{@code Overrides.apply}
 * 's own doc already proves in isolation, now proven against a live body's actual tick-to-tick
 * force application too.
 *
 * <p><b>Response metric and why it is history-independent (review finding, self-review 2):</b> a
 * raw {@code orientation.m11} (verticality) delta across a whole multi-second window is NOT a
 * matched control for a continuously-torqued body - a later window inherits angular velocity
 * accumulated by an earlier one, so a bigger raw delta on its own does not prove a bigger TORQUE,
 * only a bigger velocity-at-the-time (which this scenario's own earlier window could already
 * explain). Each window therefore samples {@code m11} (roll) and, for rotorcraft only, {@code
 * m00} (a yaw-rotation proxy, same convention {@code BrakeSweepProbe} already uses) at its own
 * start/middle/end and reports the SECOND DIFFERENCE {@code (end-mid)-(mid-start)} - this is an
 * acceleration-like proxy. Since torque (hence angular acceleration, to first order) depends only
 * on the CURRENTLY active config and control input, not on inherited angular velocity, this proxy
 * is comparable window-to-window even though the live body is never respawned or reset between
 * them. {@code accelProxy}'s own doc below has the exact arithmetic.
 *
 * <p>What this proves: the dispatch/live-field-application seam actually works end to end on a
 * real spawned vehicle, for all four of this ticket's named cases. What this does NOT prove: that
 * the resulting flight/hover feel is well-tuned, realistic, or calibrated - same disclosed limit
 * {@code BrakeSweepProbe} itself carries for ground feel. Every scenario's PASS/FAIL is explicit
 * (see {@code fail}/the final summary line) - this file never reports overall success after any
 * scenario failed. The numeric bounds/speeds/tilt angles below are a best-effort choice reasoned
 * from this repo's own force-model formulas (see inline rationale at each constant) - see this
 * ticket's VERIFICATION.md entry for whether this has actually been exercised against a live dev
 * server yet, and with what result.
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
 private static final int CHUNK_RADIUS = 7;
 private static final int CHUNK_X = 14400, CHUNK_Z = 14400;
 private static final int SPAWN_Y = 160;
 // No block platform is built at all (unlike every ground-domain probe) - flight/rotor scenarios
 // never need solid ground under them, only the chunk grid above to be loaded/generated so the
 // entity exists and ticks; this also means zero extra block-placement cost on top of the
 // chunk-generation cost already budgeted above.
 private static final double BOUND_HORIZONTAL = 95; // CHUNK_RADIUS*16=112 loaded - see ROTOR_AILERON_DEGREES's own drift note
 private static final double BOUND_VERTICAL = 90;
 private static final int SETTLE_TICKS = 15;
 private static final int OBSERVE_TICKS = 50; // 2.5s - ACTIVATION_DEFAULT/CONFIGURED's single observation window
 private static final int RELOAD_WINDOW_TICKS = 35; // 1.75s - each of LIVE_RELOAD's three windows (baseline/valid/invalid)
 private static final double GRAVITY = 9.81;
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
 // Review finding (4): 5deg never exercised the 20(default)-vs-35(configured) cyclicLimitDegrees
 // clamp, and zero rudder never exercised yawAuthorityScale at all. 25deg sits ABOVE the default
 // 20deg clamp (so the default/baseline scenario's tiltRoll is clamp-limited at 20deg) but BELOW
 // the configured 35deg clamp (so the configured scenario's tiltRoll is the full, unclamped
 // 25deg) - cyclicAuthorityScale(4x) and the clamp relief both then contribute to a genuinely
 // bigger measured roll response, not just the authority scale alone.
 private static final double ROTOR_AILERON_DEGREES = 25.0;
 // Nonzero on purpose (review finding 4) - yawTorque is rudderRadians*thrust*tailArm*scale; zero
 // rudder makes yawAuthorityScale structurally untestable regardless of its own value. Within
 // EntityVehicleF_Physics.MAX_RUDDER_ANGLE=45.
 private static final double ROTOR_RUDDER_DEGREES = 20.0;
 // Chosen so default-FALLBACK driveForce(8000N)*collective(~0.71*1.35-0.35=0.609) roughly
 // balances this chassis's own weight (500kg*9.81=4905N vs thrust~4870N) - i.e. closest to a
 // bounded, near-stationary hover, rather than a sustained climb/descent that would need a much
 // bigger BOUND_VERTICAL or a shorter window. See RotorForces.collective/lift.
 private static final double ROTOR_THROTTLE = 0.71;
 private static final int ROTOR_PROPELLER_DIAMETER_INCHES = 48;
 private static final float ROTOR_TAIL_DISTANCE = 4f;
 private static final int ROTOR_EMPTY_MASS = 500;
 // Review finding (1)/(3): CONFIGURED/LIVE_RELOAD assert their own measured accelProxy is
 // meaningfully bigger than this margin times the ACTIVATION_DEFAULT scenario's own recorded
 // baseline for the same Kind - a plain ">" would pass on noise alone.
 private static final double RESPONSE_MARGIN = 1.2;
 // Live-run finding: sampling accelProxy's three points across the WHOLE window (50/35 ticks)
 // let the body tumble through multiple full rotations first - m11/m00 are periodic in the
 // actual rotation angle, so samples that far apart alias rather than measure anything
 // meaningful. Confining the three samples to this much shorter EARLY sub-window (right after
 // control input turns on, before large-angle/multi-rotation effects dominate) keeps the
 // measurement in the small-angle-ish regime where accelProxy's own "proportional to current
 // torque" rationale actually holds. The overall window length above is still used for the
 // bounded-travel/vertical-speed-over-free-fall checks, which have no such periodicity problem.
 private static final int RESPONSE_SAMPLE_TICKS = 8;
 // Live-run finding: comparing the VALID and INVALID windows' own physical accelProxy readings
 // was too noisy to assert on reliably - by the third consecutive window on the same live,
 // never-respawned body, real translational/rotational state has accumulated and drifted far
 // enough (observed: forward speed swinging between ~2 and ~25 m/s, position nearing this
 // probe's own bound) that this proxy cannot cleanly separate "the config changed" from "the
 // body's own flight state evolved" - see this file's class doc on accelProxy's own limits.
 // retentionRepresentativeField below sidesteps that noise entirely: it reads back the actual
 // live-resolved Overrides.Tuning the rejected reload left in memory and compares the EXACT
 // configured number directly - deterministic, no physics involved, and more direct evidence of
 // "retained the VALID config" than any physical re-derivation could be.

 private enum Kind { FIXED_WING, ROTOR }
 private enum Role { ACTIVATION_DEFAULT, CONFIGURED, LIVE_RELOAD }
 private enum Phase { SPAWNING, SETTLE, OBSERVE, OBSERVE_BASELINE, APPLY_VALID, OBSERVE_VALID, APPLY_INVALID, OBSERVE_INVALID, DONE, FAILED }
 private enum GlobalPhase { WAIT_TERRAIN, WRITE_INITIAL_CONFIG, RUN_SCENARIOS, RESTORE_CONFIG, RELEASE_CHUNKS, ALL_DONE }

 private static final class Scenario {
  final String label, systemName; final Kind kind; final Role role;
  EntityVehicleF_Physics vehicle;
  PartEngine engine;
  Phase phase = Phase.SPAWNING;
  int ticksInPhase = 0;
  double spawnX, spawnZ, spawnY;
  // Three-point samples for the current window's accelProxy (roll=m11, yaw proxy=m00 - see
  // accelProxy's own doc) - captured at tick 1, RESPONSE_SAMPLE_TICKS/2, and RESPONSE_SAMPLE_TICKS
  // (an early sub-window of the full OBSERVE/RELOAD window - see RESPONSE_SAMPLE_TICKS's own doc).
  double startM11, midM11, endM11, startM00, midM00, endM00;
  double windowStartVerticalSpeed;
  // The VALID window's own accelProxy, captured so the following INVALID window can assert
  // against it (review finding 3's "matched control" ask) instead of a raw ratio across windows.
  double lastValidRollAccel = Double.NaN, lastValidYawAccel = Double.NaN;
  // Captured the first time this scenario's Body is observed active (start of its first window)
  // - every later window asserts PhysicsBridge.debugExecutedStepSnapshot(vehicle).bodyIdentity()
  // still == this same reference, proving the SAME live body across BASELINE/VALID/INVALID
  // (review 53905's "assert identity stable across reloads"), never a silent respawn/rebuild.
  Object bodyIdentity;
  Scenario(String label, String systemName, Kind kind, Role role) { this.label = label; this.systemName = systemName; this.kind = kind; this.role = role; }
 }

 private static GlobalPhase globalPhase = GlobalPhase.WAIT_TERRAIN;
 private static int terrainWaitTicksElapsed = 0;
 private static List<Scenario> scenarios;
 private static int currentScenarioIndex = 0;
 private static int scenariosPassed = 0;
 // Review finding (1)/(3): ACTIVATION_DEFAULT's own measured accelProxy per Kind, so CONFIGURED
 // and LIVE_RELOAD's baseline window can assert against a real recorded number rather than just
 // logging one. Populated once ACTIVATION_DEFAULT for that Kind finishes (scenario order in
 // buildScenarios always runs ACTIVATION_DEFAULT before CONFIGURED/LIVE_RELOAD for the same Kind).
 private static final Map<Kind, Double> baselineRollAccel = new EnumMap<>(Kind.class);
 private static final Map<Kind, Double> baselineYawAccel = new EnumMap<>(Kind.class);
 // Snapshot of the config file's content before this probe ever wrote to it (or empty if it did
 // not exist yet) - restored verbatim at ALL_DONE so this opt-in fixture leaves the shared
 // run/config/dynamic-immersive-vehicles.json exactly as it found it (deterministic cleanup, same
 // requirement this ticket's own scope names explicitly).
 private static String originalConfigContent;
 private static boolean originalConfigExisted;
 // Review finding (self-review 1 / 53853): track exactly which chunk coordinates THIS probe
 // force-added a ticket for (only ever the ones found unloaded at the time - see waitTerrain),
 // so releaseChunksOnce can unforce precisely those and nothing else - a blanket unforce of the
 // whole grid risked releasing a coordinate some OTHER owner (or a prior probe run) had already
 // force-loaded for its own unrelated reason, not one this probe itself is responsible for.
 private static final Set<Long> chunksForcedByThisProbe = new java.util.HashSet<>();
 private static long chunkKey(int x, int z) { return ((long) x << 32) ^ (z & 0xffffffffL); }
 // True from the moment writeInitialConfigOnce's snapshot succeeds - every exit from that point
 // on (success, a later write/reload failure, or a scenario failure) MUST pass through
 // RESTORE_CONFIG before ALL_DONE. Before the snapshot succeeds, nothing has been written, so
 // restoreConfigOnce's own check of this flag correctly no-ops instead of restoring a snapshot
 // that was never taken.
 private static boolean configWasWritten = false;

 public static void maybeRun(ServerLevel level) {
  if (!ENABLED) return;
  switch (globalPhase) {
   case WAIT_TERRAIN -> waitTerrain(level);
   case WRITE_INITIAL_CONFIG -> writeInitialConfigOnce();
   case RUN_SCENARIOS -> runScenarios(level);
   case RESTORE_CONFIG -> restoreConfigOnce();
   case RELEASE_CHUNKS -> releaseChunksOnce(level);
   case ALL_DONE -> {}
  }
 }

 private static void waitTerrain(ServerLevel level) {
  terrainWaitTicksElapsed++;
  var chunkSource = level.getChunkSource();
  // Review finding: "unloaded" and "not already forced" are NOT the same thing - a chunk can sit
  // unloaded on this exact tick while still carrying another owner's own force-ticket (loading is
  // deferred/async). Checking getChunkNow()==null alone to decide whether THIS probe owns the
  // coordinate it is about to force would misattribute an existing ticket as its own, and then
  // wrongly release someone else's ticket in releaseChunksOnce. level.getForcedChunks() is the
  // actual ground truth of which coordinates already carry a force-ticket right now.
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
  // Every WAIT_TERRAIN exit (success below, or the timeout below) now funnels through
  // RESTORE_CONFIG -> RELEASE_CHUNKS rather than jumping straight to ALL_DONE, so a chunk forced
  // on an earlier tick of this same loop (even one abandoned by a timeout) is still released -
  // see releaseChunksOnce, which only ever unforces chunksForcedByThisProbe's own recorded set.
  if (allLoaded) { globalPhase = GlobalPhase.WRITE_INITIAL_CONFIG; return; }
  if (terrainWaitTicksElapsed > MAX_TERRAIN_WAIT_TICKS) {
   LOGGER.error("[DIV flightRotorFixture] FAILED - terrain chunk grid never finished loading");
   globalPhase = GlobalPhase.RESTORE_CONFIG;
  }
 }

 private static Path configPath() { return FMLPaths.CONFIGDIR.get().resolve("dynamic-immersive-vehicles.json"); }
 private static Gson gson() { return new GsonBuilder().setPrettyPrinting().create(); }

 /** Reads the current config file (or a fresh empty shell if none exists yet - {@code
  * Overrides.load()} normally guarantees one exists by the time any probe runs, but this does not
  * assume that), merges in one {@code key -> fields} entry under {@code "vehicles"} WITHOUT
  * disturbing any other entry already present, and writes the result back - never a blind
  * full-file overwrite, so nothing an operator (or another probe/regression fixture) has already
  * configured on this shared file is lost.
  * @return true on a successful write, false on a failure the caller must treat as a reason to
  * abort (routing through RESTORE_CONFIG, or failing the current scenario) rather than proceeding
  * to reload/run on a config file that may be half-written or still the pre-probe original. */
 private static boolean mergeVehicleOverrideAndWrite(String key, Map<String, Double> fields) {
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
   return true;
  } catch (IOException ex) {
   LOGGER.error("[DIV flightRotorFixture] FAILED to write config at " + configPath(), ex);
   return false;
  }
 }

 private static Map<String, Double> validFieldsFor(Kind kind) {
  return kind == Kind.FIXED_WING
    ? Map.of("liftSlope", 9.5, "controlSurfaceSlope", 9.5)
    : Map.of("cyclicLimitDegrees", 35.0, "cyclicAuthorityScale", 4.0, "yawAuthorityScale", 3.0);
 }

 /** One representative field from {@link #validFieldsFor} per {@link Kind}, read from a {@link
  * PhysicsBridge.DebugExecutedStepSnapshot}'s own {@code tuning()} - the active Body's REAL live-
  * cached tuning object, not a fresh global {@link Overrides#resolved} re-derivation (review
  * 53897/53907's own point: those are two different things that merely happen to usually agree).
  * Returns {@code null} if {@code snapshot} itself is {@code null} (no active Body). */
 private static Double representativeTuningField(Kind kind, PhysicsBridge.DebugExecutedStepSnapshot snapshot) {
  if (snapshot == null) return null;
  var tuning = snapshot.tuning();
  return kind == Kind.FIXED_WING ? tuning.liftSlope : tuning.cyclicAuthorityScale;
 }

 private static void writeInitialConfigOnce() {
  try {
   originalConfigExisted = Files.exists(configPath());
   originalConfigContent = originalConfigExisted ? Files.readString(configPath()) : null;
  } catch (IOException ex) {
   // Nothing has been written yet (the snapshot read itself is what failed) - configWasWritten
   // stays false, so restoreConfigOnce correctly treats this as a no-op rather than restoring a
   // snapshot that was never taken.
   LOGGER.error("[DIV flightRotorFixture] FAILED to snapshot existing config before writing - aborting rather than risk losing it", ex);
   globalPhase = GlobalPhase.RESTORE_CONFIG;
   return;
  }
  // From here on this probe is about to modify the shared config file - every subsequent exit,
  // success or failure, MUST pass through RESTORE_CONFIG (never ALL_DONE directly).
  configWasWritten = true;
  var packSpeedFactors = ConfigSystem.settings.general.packSpeedFactors.value;
  var packVehicleScales = ConfigSystem.settings.general.packVehicleScales.value;
  packSpeedFactors.putIfAbsent(PACK_ID, 1.0);
  packVehicleScales.putIfAbsent(PACK_ID, 1.0);
  scenarios = buildScenarios();
  // Only the two CONFIGURED scenarios' keys get an override entry up front - ACTIVATION_DEFAULT
  // and LIVE_RELOAD's own systemNames are deliberately absent from the file at this point, so
  // Overrides.resolved(...) falls through to FALLBACK for them exactly like an unconfigured real
  // vehicle (LIVE_RELOAD writes its own entry live, mid-scenario - see applyValidConfig below).
  if (!mergeVehicleOverrideAndWrite(PACK_ID + ":" + "fw-configured", validFieldsFor(Kind.FIXED_WING))) {
   globalPhase = GlobalPhase.RESTORE_CONFIG;
   return;
  }
  if (!mergeVehicleOverrideAndWrite(PACK_ID + ":" + "rotor-configured", validFieldsFor(Kind.ROTOR))) {
   globalPhase = GlobalPhase.RESTORE_CONFIG;
   return;
  }
  var report = PhysicsBridge.reloadOverrides();
  LOGGER.info("[DIV flightRotorFixture] initial CONFIGURED overrides written and reloaded: " + report.configResult().message());
  if (!report.configResult().success()) {
   LOGGER.error("[DIV flightRotorFixture] FAILED - the initial CONFIGURED-scenario override file itself failed to validate: " + report.configResult().message());
   globalPhase = GlobalPhase.RESTORE_CONFIG;
   return;
  }
  LOGGER.info("[DIV flightRotorFixture] running " + scenarios.size() + " scenarios sequentially: "
    + scenarios.stream().map(s -> s.label).reduce("", (a, b) -> a + " " + b));
  globalPhase = GlobalPhase.RUN_SCENARIOS;
 }

 private static List<Scenario> buildScenarios() {
  List<Scenario> list = new ArrayList<>();
  // ACTIVATION_DEFAULT always precedes CONFIGURED/LIVE_RELOAD for the same Kind - both of those
  // assert against baselineRollAccel/baselineYawAccel, which only ACTIVATION_DEFAULT populates.
  list.add(new Scenario("fw-activation-default", "fw-activation-default", Kind.FIXED_WING, Role.ACTIVATION_DEFAULT));
  list.add(new Scenario("fw-configured", "fw-configured", Kind.FIXED_WING, Role.CONFIGURED));
  list.add(new Scenario("fw-live-reload", "fw-live-reload", Kind.FIXED_WING, Role.LIVE_RELOAD));
  list.add(new Scenario("rotor-activation-default", "rotor-activation-default", Kind.ROTOR, Role.ACTIVATION_DEFAULT));
  list.add(new Scenario("rotor-configured", "rotor-configured", Kind.ROTOR, Role.CONFIGURED));
  list.add(new Scenario("rotor-live-reload", "rotor-live-reload", Kind.ROTOR, Role.LIVE_RELOAD));
  return list;
 }

 private static void runScenarios(ServerLevel level) {
  if (currentScenarioIndex >= scenarios.size()) {
   // Review finding (5): never report overall success after any scenario failed - this explicit
   // count is the one place that claim is made, and it is made honestly.
   LOGGER.info("[DIV flightRotorFixture] ALL SCENARIOS FINISHED - " + scenariosPassed + "/" + scenarios.size() + " PASSED"
     + (scenariosPassed == scenarios.size() ? "" : " - at least one scenario FAILED, see its own STOPPED line above"));
   globalPhase = GlobalPhase.RESTORE_CONFIG;
   return;
  }
  Scenario s = scenarios.get(currentScenarioIndex);
  try {
   switch (s.phase) {
    case SPAWNING -> spawn(level, s);
    case SETTLE -> settle(s);
    case OBSERVE -> finishWindow(s, Phase.DONE, null);
    case OBSERVE_BASELINE -> finishWindow(s, Phase.APPLY_VALID, "BASELINE (unconfigured)");
    case APPLY_VALID -> applyValidConfig(s);
    case OBSERVE_VALID -> finishWindow(s, Phase.APPLY_INVALID, "VALID live-reload");
    case APPLY_INVALID -> applyInvalidConfig(s);
    case OBSERVE_INVALID -> finishWindow(s, Phase.DONE, "INVALID live-reload (must retain the VALID config above)");
    case DONE, FAILED -> advanceToNextScenario(s);
   }
  } catch (RuntimeException ex) {
   LOGGER.error("[DIV flightRotorFixture] scenario=" + s.label + " FAILED - unexpected exception in phase " + s.phase, ex);
   s.phase = Phase.FAILED;
  }
 }

 private static void advanceToNextScenario(Scenario s) {
  if (s.phase == Phase.DONE) scenariosPassed++;
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
  // Review finding (5b): retain the reference the moment the entity actually exists in the world
  // (BEFORE adding parts, which can themselves throw) - advanceToNextScenario's cleanup only
  // removes s.vehicle if it is non-null, so a part-construction exception between here and the
  // old "s.vehicle = vehicle" placement (after addPropeller) used to leak a spawned-but-untracked
  // entity in the saved world on that failure path.
  s.vehicle = vehicle;
  s.engine = addEngine(vehicle, engineSlot);
  if (propellerSlot != null) addPropeller(vehicle, propellerSlot);

  s.spawnX = blockX + 0.5; s.spawnZ = blockZ + 0.5; s.spawnY = SPAWN_Y;
  s.phase = Phase.SETTLE;
  s.ticksInPhase = 0;
  LOGGER.info("[DIV flightRotorFixture] scenario=" + s.label + " spawned at " + vehicle.position + " key=" + PACK_ID + ":" + s.systemName + " - beginning SETTLE phase");
 }

 private static JSONVehicle buildFixedWingDefinition(String systemName) {
  // Review-the-live-run finding: a too-small box here previously gave this 900kg hull a tiny
  // moment of inertia, so FlightForces' own ordinary roll/pitch torque spun it violently (its
  // own up-vector flipped sign within half a second) instead of a controlled response - sized
  // closer to FW_WING_SPAN/a real fuselage length instead.
  var v = baseVehicleDefinition(systemName, FW_WING_SPAN, 3f);
  v.motorized.isAircraft = true;
  v.motorized.wingArea = FW_WING_AREA; v.motorized.wingSpan = FW_WING_SPAN;
  v.motorized.aileronArea = FW_AILERON_AREA; v.motorized.elevatorArea = FW_ELEVATOR_AREA; v.motorized.rudderArea = FW_RUDDER_AREA;
  v.motorized.tailDistance = FW_TAIL_DISTANCE; v.motorized.dragCoefficient = FW_DRAG_COEFF; v.motorized.crossSectionalArea = FW_CROSS_SECTION;
  v.motorized.emptyMass = FW_EMPTY_MASS;
  return v;
 }

 private static JSONVehicle buildRotorDefinition(String systemName) {
  var v = baseVehicleDefinition(systemName, 2f, 1f);
  // Deliberately NOT setting motorized.isAircraft/isBlimp - PhysicsBridge.selectStepper's own
  // contract (MINECRAFT-497 fix) is that an installed isRotor propeller wins dispatch
  // unconditionally, independent of that flag; leaving it false is itself part of this
  // scenario's activation evidence (ROTOR dispatch from the installed part alone).
  v.motorized.tailDistance = ROTOR_TAIL_DISTANCE;
  v.motorized.emptyMass = ROTOR_EMPTY_MASS;
  return v;
 }

 private static JSONVehicle baseVehicleDefinition(String systemName, float collisionWidth, float collisionHeight) {
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
  // buildVehicleDefinition uses; this ticket's scenarios need no wheel geometry at all. Sizing
  // matters here (live-run finding): a too-small box gives too-small a moment of inertia for its
  // mass, so ordinary aerodynamic torque spins the body violently instead of producing a
  // controlled roll/pitch - the fixed-wing scenario's own box is sized closer to its wingspan for
  // exactly this reason (see buildFixedWingDefinition).
  var mainBox = new JSONCollisionBox();
  mainBox.pos = new Point3D(0, 0, 0);
  mainBox.width = collisionWidth; mainBox.height = collisionHeight;
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
   v.rudderInputVar.setTo(ROTOR_RUDDER_DEGREES, false);
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
  LOGGER.error("[DIV flightRotorFixture] scenario=" + s.label + " STOPPED (preserved as evidence) - FAIL - " + reason);
  s.phase = Phase.FAILED;
 }

 private static void logState(Scenario s, String label) {
  var v = s.vehicle;
  LOGGER.info("[DIV flightRotorFixture] scenario=" + s.label + " " + label + " tick=" + s.ticksInPhase
    + " forwardSpeed=" + forwardSpeedMps(s) + " verticalSpeed=" + verticalSpeedMps(s) + " position=" + v.position
    + " up(m01,m11,m21)=(" + v.orientation.m01 + "," + v.orientation.m11 + "," + v.orientation.m21 + ")"
    + " yawProxy(m00,m22)=(" + v.orientation.m00 + "," + v.orientation.m22 + ")"
    + " throttle=" + v.throttleVar.currentValue + " aileron=" + v.aileronInputVar.currentValue + " rudder=" + v.rudderInputVar.currentValue);
 }

 private static void settle(Scenario s) {
  s.ticksInPhase++;
  if (!checkBoundedOrFail(s)) return;
  forceEngineRunning(s.engine);
  // No control input yet during SETTLE - lets the freshly-activated Sable body's very first
  // tick(s) (construction, initial-velocity seeding) happen before this scenario's own
  // measurement window starts, same SETTLE-before-measuring structure BrakeSweepProbe uses.
  if (s.ticksInPhase >= SETTLE_TICKS) {
   if (!beginWindow(s, s.role == Role.LIVE_RELOAD ? Phase.OBSERVE_BASELINE : Phase.OBSERVE)) return;
   LOGGER.info("[DIV flightRotorFixture] scenario=" + s.label + " SETTLE complete - beginning " + s.phase + " window");
  }
 }

 /** @return true if this scenario may proceed into {@code phase}; false if it was just failed
  * (identity check below) - in which case the caller must return immediately rather than act as
  * though {@code phase} actually started. */
 private static boolean beginWindow(Scenario s, Phase phase) {
  var snapshot = PhysicsBridge.debugExecutedStepSnapshot(s.vehicle);
  if (snapshot == null) { fail(s, "no active Body found when beginning phase=" + phase + " - dispatch/activation itself failed or was lost"); return false; }
  if (s.bodyIdentity == null) {
   s.bodyIdentity = snapshot.bodyIdentity();
  } else if (s.bodyIdentity != snapshot.bodyIdentity()) {
   fail(s, "active Body identity CHANGED entering phase=" + phase + " - this scenario's vehicle was respawned/rebuilt, not the same live body the ticket requires (review 53905)");
   return false;
  }
  s.phase = phase;
  s.ticksInPhase = 0;
  s.startM11 = s.vehicle.orientation.m11; s.startM00 = s.vehicle.orientation.m00;
  s.midM11 = Double.NaN; s.midM00 = Double.NaN;
  s.windowStartVerticalSpeed = verticalSpeedMps(s);
  return true;
 }

 private static int windowTicksFor(Scenario s) { return s.role == Role.LIVE_RELOAD ? RELOAD_WINDOW_TICKS : OBSERVE_TICKS; }

 /** The acceleration-like, history-independent response proxy this file's own class doc explains
  * - the second difference of three samples spaced evenly across one window. Independent of
  * whatever angular velocity this body carried INTO the window (to first order), since it only
  * compares how much the rate of change itself changed within this window's own fixed-input
  * duration. */
 private static double accelProxy(double start, double mid, double end) { return (end - mid) - (mid - start); }

 /** Shared window-runner for every OBSERVE* phase: applies this scenario's fixed control input
  * every tick, captures the start/mid/end samples {@link #accelProxy} needs, and on window
  * completion computes and ASSERTS against that proxy (see each {@code Role} branch below) before
  * transitioning to {@code next}. {@code windowLabel} null means "just finish the scenario"
  * (ACTIVATION_DEFAULT/CONFIGURED); non-null additionally logs that label so LIVE_RELOAD's three
  * windows are each individually identifiable. */
 private static void finishWindow(Scenario s, Phase next, String windowLabel) {
  s.ticksInPhase++;
  if (!checkBoundedOrFail(s)) return;
  applyControlInputs(s);
  int windowTicks = windowTicksFor(s);
  int midTick = Math.max(1, RESPONSE_SAMPLE_TICKS / 2);
  if (s.ticksInPhase == midTick) { s.midM11 = s.vehicle.orientation.m11; s.midM00 = s.vehicle.orientation.m00; }
  if (s.ticksInPhase == RESPONSE_SAMPLE_TICKS) { s.endM11 = s.vehicle.orientation.m11; s.endM00 = s.vehicle.orientation.m00; }
  if (s.ticksInPhase % 10 == 0) logState(s, "OBSERVE" + (windowLabel != null ? "[" + windowLabel + "]" : ""));
  if (s.ticksInPhase < windowTicks) return;
  double endM11 = s.endM11, endM00 = s.endM00;
  double verticalSpeedDelta = verticalSpeedMps(s) - s.windowStartVerticalSpeed;
  double windowSeconds = windowTicks / 20.0;
  double rollAccel = accelProxy(s.startM11, s.midM11, endM11);
  double yawAccel = accelProxy(s.startM00, s.midM00, endM00);
  LOGGER.info("[DIV flightRotorFixture] scenario=" + s.label + " " + (windowLabel != null ? windowLabel + " " : "") + "WINDOW COMPLETE"
    + " rollAccelProxy=" + rollAccel + " yawAccelProxy=" + yawAccel + " verticalSpeedDelta=" + verticalSpeedDelta
    + " verticalSpeedNow=" + verticalSpeedMps(s) + " (free-fall-only over this window would be verticalSpeedDelta<=-" + (GRAVITY * windowSeconds) + ")");

  switch (s.role) {
   case ACTIVATION_DEFAULT -> {
    // Review 53907: a merely-SELECTED stepper does not prove force application actually ran
    // (M527's own root cause is exactly a selected non-GROUND stepper whose execution a candidate
    // unconditionally skips) - assert the REAL executed-step counters directly instead of (or
    // alongside) the physical free-fall proxy below.
    var snapshot = PhysicsBridge.debugExecutedStepSnapshot(s.vehicle);
    String expectedStepper = s.kind == Kind.FIXED_WING ? "FLIGHT" : "ROTOR";
    if (snapshot == null || snapshot.executedStepCount() <= 0 || !expectedStepper.equals(snapshot.lastExecutedStepperName())) {
     fail(s, "ACTIVATION dispatch check failed - debugExecutedStepSnapshot=" + snapshot
       + " (expected lastExecutedStepperName=" + expectedStepper + " with executedStepCount>0 - force application never actually ran for this body)");
     return;
    }
    double freeFallThreshold = -GRAVITY * windowSeconds * 0.8;
    boolean activated = s.kind == Kind.FIXED_WING ? verticalSpeedDelta > freeFallThreshold : Math.abs(verticalSpeedDelta / windowSeconds) < GRAVITY * 0.5;
    if (!activated) {
     fail(s, "ACTIVATION check failed - verticalSpeedDelta=" + verticalSpeedDelta + " over " + windowSeconds
       + "s looks like an un-managed/misdispatched body (free-fall or equivalent), not an engaged stepFlight/stepRotor");
     return;
    }
    baselineRollAccel.put(s.kind, rollAccel);
    baselineYawAccel.put(s.kind, yawAccel);
    LOGGER.info("[DIV flightRotorFixture] scenario=" + s.label + " ACTIVATION PASSED (dispatch confirmed: executedStepCount=" + snapshot.executedStepCount()
      + " lastExecutedStepperName=" + snapshot.lastExecutedStepperName() + ") - recorded baseline rollAccelProxy=" + rollAccel + " yawAccelProxy=" + yawAccel);
   }
   case CONFIGURED -> {
    Double baseRoll = baselineRollAccel.get(s.kind);
    if (baseRoll == null || !(Math.abs(rollAccel) > Math.abs(baseRoll) * RESPONSE_MARGIN)) {
     fail(s, "CONFIGURED roll response check failed - rollAccelProxy=" + rollAccel + " not meaningfully bigger than baseline=" + baseRoll + " (margin=" + RESPONSE_MARGIN + ")");
     return;
    }
    if (s.kind == Kind.ROTOR) {
     Double baseYaw = baselineYawAccel.get(s.kind);
     if (baseYaw == null || !(Math.abs(yawAccel) > Math.abs(baseYaw) * RESPONSE_MARGIN)) {
      fail(s, "CONFIGURED yaw response check failed - yawAccelProxy=" + yawAccel + " not meaningfully bigger than baseline=" + baseYaw + " (margin=" + RESPONSE_MARGIN + ")");
      return;
     }
    }
    LOGGER.info("[DIV flightRotorFixture] scenario=" + s.label + " CONFIGURED response PASSED vs baseline (roll=" + baseRoll + (s.kind == Kind.ROTOR ? (", yaw=" + baselineYawAccel.get(s.kind)) : "") + ")");
   }
   case LIVE_RELOAD -> {
    if (next == Phase.APPLY_VALID) {
     Double baseRoll = baselineRollAccel.get(s.kind);
     if (baseRoll == null) { fail(s, "no recorded baseline for kind=" + s.kind + " - ACTIVATION_DEFAULT must run first"); return; }
     LOGGER.info("[DIV flightRotorFixture] scenario=" + s.label + " BASELINE window complete rollAccelProxy=" + rollAccel + " (unconfigured, compare against recorded baseline=" + baseRoll + ")");
    } else if (next == Phase.APPLY_INVALID) {
     Double baseRoll = baselineRollAccel.get(s.kind);
     if (baseRoll == null || !(Math.abs(rollAccel) > Math.abs(baseRoll) * RESPONSE_MARGIN)) {
      fail(s, "VALID live-reload roll response check failed - rollAccelProxy=" + rollAccel + " not meaningfully bigger than baseline=" + baseRoll + " (margin=" + RESPONSE_MARGIN + ") - reload may not have actually taken effect");
      return;
     }
     s.lastValidRollAccel = rollAccel; s.lastValidYawAccel = yawAccel;
    } else { // next == Phase.DONE: the INVALID window just finished
     // Second-stage check: applyInvalidConfig already asserted this immediately after the
     // rejection itself (before any disk repair or background reload could confound it - see
     // its own doc); re-asserting here additionally proves retention PERSISTS through the whole
     // OBSERVE_INVALID window, including across OverridesHotReload's own periodic mtime poll.
     double expected = validFieldsFor(s.kind).get(s.kind == Kind.FIXED_WING ? "liftSlope" : "cyclicAuthorityScale");
     Double actual = representativeTuningField(s.kind, PhysicsBridge.debugExecutedStepSnapshot(s.vehicle));
     if (actual == null || Math.abs(actual - expected) > 1e-9) {
      fail(s, "INVALID live-reload retention check failed - live-resolved representative field=" + actual
        + " does not match the VALID value=" + expected + " - the rejected invalid edit corrupted or reverted the live config");
      return;
     }
     LOGGER.info("[DIV flightRotorFixture] scenario=" + s.label + " INVALID-vs-VALID retention still PASSED at window end - live-resolved representative field still=" + actual
       + " (physical accelProxy this window, for reference only: " + rollAccel + " vs prior VALID window's " + s.lastValidRollAccel + ")");
    }
   }
  }

  if (next == Phase.DONE) { LOGGER.info("[DIV flightRotorFixture] scenario=" + s.label + " COMPLETE (PASSED)"); s.phase = Phase.DONE; return; }
  if (!beginWindow(s, next)) return;
 }

 private static void applyValidConfig(Scenario s) {
  Map<String, Double> fields = validFieldsFor(s.kind);
  if (!mergeVehicleOverrideAndWrite(PACK_ID + ":" + s.systemName, fields)) {
   fail(s, "FAILED to write the VALID config to disk - aborting this scenario rather than reloading a possibly-stale file");
   return;
  }
  var report = PhysicsBridge.reloadOverrides();
  if (!report.configResult().success()) {
   fail(s, "VALID live-reload unexpectedly FAILED to apply: " + report.configResult().message() + " - this is itself a finding, not expected scenario behavior");
   return;
  }
  // Review finding: assert the reload actually reached this scenario's own live body, not just
  // that the config parsed - exactly one vehicle (this scenario's own still-active one) should
  // have been live-updated, since no other scenario's vehicle is alive at this point (strictly
  // sequential scenarios - see this file's class doc).
  if (report.vehiclesUpdatedLive() != 1) {
   fail(s, "VALID live-reload reported vehiclesUpdatedLive=" + report.vehiclesUpdatedLive() + " (expected exactly 1 - this scenario's own still-active body) - the reload may not have actually reached the live body");
   return;
  }
  LOGGER.info("[DIV flightRotorFixture] scenario=" + s.label + " VALID config written and reloaded live (no respawn): " + report.configResult().message()
    + " liveUpdatedVehicles=" + report.vehiclesUpdatedLive() + " fieldsNeedingRespawn=" + report.fieldsNeedingRespawn());
  if (!beginWindow(s, Phase.OBSERVE_VALID)) return;
 }

 private static void applyInvalidConfig(Scenario s) {
  // Out-of-range per Overrides.Tuning#validate: liftSlope/controlSurfaceSlope must be in (0,10],
  // cyclicLimitDegrees must be in (0, RotorForces.MAX_CYCLIC_LIMIT_DEGREES] - both reject <= 0.
  Map<String, Double> invalidFields = s.kind == Kind.FIXED_WING
    ? Map.of("liftSlope", -1.0, "controlSurfaceSlope", 9.5)
    : Map.of("cyclicLimitDegrees", 0.0, "cyclicAuthorityScale", 4.0, "yawAuthorityScale", 3.0);
  if (!mergeVehicleOverrideAndWrite(PACK_ID + ":" + s.systemName, invalidFields)) {
   fail(s, "FAILED to write the INVALID config to disk - aborting this scenario rather than reloading a possibly-stale file");
   return;
  }
  var report = PhysicsBridge.reloadOverrides();
  if (report.configResult().success()) {
   fail(s, "INVALID live-reload unexpectedly SUCCEEDED (should have been rejected, retaining the prior VALID config) - this is a regression finding, not expected scenario behavior: " + report.configResult().message());
   return;
  }
  LOGGER.info("[DIV flightRotorFixture] scenario=" + s.label + " INVALID config correctly REJECTED, last-known-good (the VALID config above) retained in memory: " + report.configResult().message());
  // Review finding: assert retention IMMEDIATELY here, before anything else touches the config -
  // doing the on-disk repair first (as an earlier revision did) and asserting only after the
  // following OBSERVE_INVALID window risked the assertion only ever seeing state THIS repair
  // itself had just (re)written, or risked OverridesHotReload's own background 100-tick mtime
  // poll re-reading the file in between and reapplying the valid values independently - neither
  // would actually prove the REJECTED reload itself preserved the live state.
  Double resolvedValue = representativeTuningField(s.kind, PhysicsBridge.debugExecutedStepSnapshot(s.vehicle));
  double expectedValidValue = validFieldsFor(s.kind).get(s.kind == Kind.FIXED_WING ? "liftSlope" : "cyclicAuthorityScale");
  if (resolvedValue == null || Math.abs(resolvedValue - expectedValidValue) > 1e-9) {
   fail(s, "INVALID live-reload retention check failed IMMEDIATELY after rejection - live-resolved representative field=" + resolvedValue
     + " does not match the VALID value=" + expectedValidValue + " - the rejected reload itself corrupted or reverted the live config");
   return;
  }
  LOGGER.info("[DIV flightRotorFixture] scenario=" + s.label + " retention confirmed immediately after rejection - live-resolved representative field=" + resolvedValue);
  // Review finding (2): the file ON DISK still carries this invalid entry (Overrides.reload only
  // ever swaps its in-memory maps, never touches the file) - Overrides.reload validates the
  // WHOLE config as one unit, so leaving this invalid entry in place would poison every LATER
  // scenario's own mergeVehicleOverrideAndWrite/reloadOverrides call (including a different
  // Kind's), not just this one. Repair this key back to the known-VALID fields on disk now, AFTER
  // the retention assertion above (a plain write, not a reload - the in-memory state is already
  // correct and must not be touched again) so the file stays globally valid for everything that
  // runs after this.
  if (!mergeVehicleOverrideAndWrite(PACK_ID + ":" + s.systemName, validFieldsFor(s.kind))) {
   fail(s, "FAILED to repair the on-disk config back to the VALID entry after the invalid-reload check - a later scenario's own reload could now fail for an unrelated reason");
   return;
  }
  if (!beginWindow(s, Phase.OBSERVE_INVALID)) return;
 }

 /** Reached from every terminal path once this probe might have touched the shared config file
  * (success, a terrain timeout, a snapshot/write/reload failure, or a scenario failure) - see
  * {@code configWasWritten}'s own doc. Always advances to {@link GlobalPhase#RELEASE_CHUNKS}
  * next, so the forced-chunk cleanup below runs unconditionally regardless of how this probe
  * exited. */
 private static void restoreConfigOnce() {
  if (configWasWritten) {
   boolean restoredOnDisk;
   try {
    if (originalConfigExisted) Files.writeString(configPath(), originalConfigContent);
    else Files.deleteIfExists(configPath());
    restoredOnDisk = true;
   } catch (IOException ex) {
    // Review finding: the old code reached this catch, logged the exception, but then fell
    // through to reloadOverrides() anyway and unconditionally logged "restored and reloaded" -
    // reloading here would load the KNOWN-STILL-PROBE-MUTATED file (potentially re-applying this
    // probe's own test overrides into live memory) and the log line would falsely claim a
    // deterministic cleanup that did not happen. Treat this as its own explicit failed terminal
    // outcome instead: do not reload, do not claim restoration, and preserve exactly what manual
    // cleanup needs (the path, and - when the original existed - its own content so it isn't
    // lost by a later unrelated process overwriting this still-broken file further).
    LOGGER.error("[DIV flightRotorFixture] FAILED to restore original config at " + configPath()
      + " - NOT reloading (would apply the known-still-probe-mutated file) - manual cleanup needed."
      + (originalConfigExisted ? " Original content this probe is preserving for that cleanup: " + originalConfigContent : " Original file did not exist - it should be deleted."), ex);
    restoredOnDisk = false;
   }
   if (restoredOnDisk) {
    var report = PhysicsBridge.reloadOverrides();
    LOGGER.info("[DIV flightRotorFixture] original config restored and reloaded: " + report.configResult().message());
   }
  } else {
   LOGGER.info("[DIV flightRotorFixture] no config was ever written by this probe run - nothing to restore");
  }
  // Chunk release is NOT restoration - it only ever unforces coordinates this probe itself
  // forced (see releaseChunksOnce's own doc) and is unaffected by whether the config restore
  // above succeeded, so it still runs unconditionally on every path, including the failure one.
  globalPhase = GlobalPhase.RELEASE_CHUNKS;
 }

 /** Unforces exactly the coordinates {@code chunksForcedByThisProbe} recorded THIS probe itself
  * force-adding a ticket for (review finding 53853: a blanket unforce of the whole grid risked
  * releasing some other owner's pre-existing forced ticket this probe never created). Always the
  * last real step before ALL_DONE, reached from every terminal path via {@link
  * #restoreConfigOnce} - a forced chunk left behind (an earlier version of this file's own review
  * finding) would otherwise persist in the saved world indefinitely. */
 private static void releaseChunksOnce(ServerLevel level) {
  for (long key : chunksForcedByThisProbe) {
   int x = (int) (key >> 32), z = (int) key;
   level.setChunkForced(x, z, false);
  }
  LOGGER.info("[DIV flightRotorFixture] released " + chunksForcedByThisProbe.size() + " chunk(s) this probe itself forced - ALL_DONE");
  chunksForcedByThisProbe.clear();
  globalPhase = GlobalPhase.ALL_DONE;
 }
}
