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
 private static final double TARGET_SPEED = 5.0; // m/s - a bounded, moderate turning speed for the TURN scenarios
 private static final double BRAKE_CHECK_TARGET_SPEED = 8.0; // m/s - review 53348's own "around 8 m/s" ask for the straight-braking check
 private static final int SETTLE_TICKS = 60, MAX_ACCEL_TICKS = 160, TURN_TICKS = 200; // TURN sustained for 10s, well beyond a brief turn
 // Review 53367: must exceed PhysicsBridge's ENGINE_FUEL_ELECTRICAL_RECEIPT_PERIOD_TICKS (100) so
 // the churn scenario actually exercises the periodic resample, not just the initial log line.
 private static final int ENGINE_STATE_CHECK_CHURN_TICKS = 150;
 // Ticket 53383: long enough either side of the flip for the vehicle to genuinely settle (not
 // mid-fall) before AND after chassisBoundsChanged's rebuild, so the comparison is meaningful.
 private static final int INCREMENTAL_INSTALL_TICKS = 120;
 private static final int HOLD_TICKS = 40, BRAKE_TICKS = 100; // BRAKE-mode scenarios: brief hold at target speed, then a bounded brake-observation window
 private static final int STABILIZE_TURN_TICKS = 60; // 3s - review 53352's own "reach stable ~5m/s sustained 40deg turn" before braking mid-turn
 private static final int BRAKE_DURING_TURN_TICKS = 100; // up to 5s, review's own explicit bound, rudder/steering retained throughout
 private static final int STRAIGHT_SETTLE_TICKS = 300; // 15s straight driving, zero rudder - long enough to distinguish a persistent steady-state lean from initial settling transients
 private static final int STEADY_STATE_WINDOW_TICKS = 60; // last 3s averaged for the reported steady-state signed bank
 private static final int RECOVER_TICKS = 160; // 8s post-rudder-release observation window

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
 private enum Phase { SPAWNING, SETTLE, ACCELERATE, TURN, HOLD, BRAKE, BRAKE_DURING_TURN, STRAIGHT_SETTLE, RECOVER, DONE, FAILED }
 // ENGINE_STATE_CHECK (ticket 53365): never drives - exercises PhysicsBridge.Body's new
 // engineFuelElectricalReceipt diagnostic across the 4 states John asked to be tested (no
 // engine installed, engine installed but not running, running in neutral, running in gear),
 // confirmed purely by reading this scenario's own spawned engine/fuel/electrical fields back -
 // never alters force/control itself, same as the production diagnostic it is exercising.
 // INCREMENTAL_INSTALL (ticket 53383): never drives - spawns with the fallback collision group
 // VISIBLE (wide/buggy envelope, simulating "wheel not installed yet"), settles, then flips a
 // REAL (non-constant) ComputedVariable mid-run to simulate the wheel actually being installed,
 // directly proving PhysicsBridge.Body's new chassisBoundsChanged lifecycle refresh triggers a
 // rebuild and narrows the envelope, rather than permanently locking in whatever was active the
 // moment Body first successfully constructed.
 private enum Mode { TURN, BRAKE, TURN_THEN_BRAKE, STRAIGHT_SETTLE, TURN_THEN_RECOVER, ENGINE_STATE_CHECK, INCREMENTAL_INSTALL }

 private static final class Scenario {
  final String label, systemName;
  final Mode mode;
  final double targetSpeed;
  final boolean symmetricGeometry; // review 53354: isolates whether the real ATV's own established-but-asymmetric rear wheel track (RL.x=0.35 vs RR.x=-0.375, front stays symmetric) alone produces a persistent steady-state bank with zero rudder input
  final double rudderDegrees; // review 53357's own "mirrored" ask - signed, so a negative value turns the opposite direction
  EntityVehicleF_Physics vehicle;
  PartEngine engine;
  net.minecraft.world.entity.animal.Pig riderEntity;
  Phase phase = Phase.SPAWNING;
  int ticksInPhase = 0;
  double spawnX, spawnZ, spawnSurfaceY;
  double speedAtBrakeStart;
  // Review 53354: STEADY-STATE signed bank (NOT abs, NOT max) - the last STEADY_STATE_WINDOW_TICKS
  // ticks' own bank values, averaged, to characterize a persistent lean/bias distinct from
  // transient settling oscillation or deliberate turn bank.
  final java.util.ArrayDeque<Double> recentSignedBankDegrees = new java.util.ArrayDeque<>();
  double bankAtOnset; // review 53348's own "report onset attitude/speed" for the BRAKE check
  // Review 53352's own combined turn+brake check - onset snapshot taken the instant the brake
  // engages mid-turn, plus the horizontal-speed/pitch extrema tracked through that window.
  double onsetSpeedDuringTurnBrake, onsetPitchDegreesDuringTurnBrake, onsetYawDegreesDuringTurnBrake, onsetVerticalFromSurfaceDuringTurnBrake;
  double maxAbsPitchDegrees = 0;
  double finalHorizontalSpeed = Double.NaN; // sqrt(forward^2+lateral^2) at the end of the BRAKE_DURING_TURN window - review's own "not solely body-forward component, to avoid mistaking sideways slide for stopping"
  double minUpY = 1.0; // plain up.y - kept for the bounded-observation-floor check only, NOT the reported roll metric (review 53341: mixes pitch/wheelie with sideways bank)
  // Review 53341: the actual roll-specific metric, matching SelfRightingAssist's own internal
  // math exactly (signed roll about bodyForward alone) - atan2(bodyRight.y, bodyUp.y), NOT
  // plain up.y, which conflates pitch (wheelie, allowed) with sideways bank (the real complaint).
  double maxAbsBankDegrees = 0;
  double prevBankRadians = Double.NaN;
  double maxAbsBankRateDegreesPerTick = 0;
  double maxAbsLateralSlip = 0; // velocity component along the body-right axis - the real sideways slip a turn induces
  double steadyStateSignedBankDegrees = Double.NaN; // set at scenario end - average signed bank over the final STEADY_STATE_WINDOW_TICKS
  double bankAtRudderRelease; // review 53357's post-turn recovery - signed bank the instant rudder returns to 0
  // ENGINE_STATE_CHECK only (ticket 53365) - defaults preserve every existing scenario/mode's
  // behavior unchanged (hasEngine=true, forceEngineRunning still overrides running/gear in
  // accelerate() for every driving mode exactly as before).
  boolean hasEngine = true;
  boolean engineRunningDesired = false;
  double gearDesired = 0.0;
  // Ticket 53373's conditional-collision-group proof (null = no second group, matching every
  // existing scenario's behavior unchanged).
  Boolean conditionalGroupVisible = null;
  // Ticket 53372/53373: merc230's own REAL geometry reproduction - when set (non-null), spawn()
  // replaces the plain single-group definition with a two-group one matching merc230's actual
  // reported numbers (interior floor group, always active; missing-wheel fallback group,
  // visibility-gated) instead of adding a synthetic deep marker box.
  Boolean merc230WheelsPresent = null;
  // Ticket 53381: full DRIVING causal reproduction, not just a static chassisReceipt snapshot -
  // real mass/suspensionTravel/wheel radius/anchors under actual throttle, comparing the raw
  // (buggy, wide) envelope against the fixed (narrow, fallback-hidden) one. null = not this test.
  Boolean merc230RawEnvelope = null;
  // Ticket 53383: when true, the merc230 fallback group's visibility variable is a REAL,
  // mutable ComputedVariable ("wheelInstalledVar") instead of a "#"-constant, so this scenario
  // can flip it mid-run to simulate an actual incremental part install.
  boolean incrementalInstall = false;
  Point3D incrementalInstallFlipPosition;
  // Review 53367: demonstrates the edge-trigger fix itself - running/gear stay fixed for the
  // whole window while rpm (a continuous value, deliberately excluded from the discrete
  // comparison) is mutated every single tick, confirming it does NOT cause continuous emission.
  boolean churnRpmEachTick = false;
  Scenario(String label, String systemName, Mode mode, double targetSpeed, boolean symmetricGeometry, double rudderDegrees) {
   this.label = label; this.systemName = systemName; this.mode = mode; this.targetSpeed = targetSpeed; this.symmetricGeometry = symmetricGeometry; this.rudderDegrees = rudderDegrees;
  }
  Scenario(String label, String systemName, Mode mode, double targetSpeed, boolean symmetricGeometry) {
   this(label, systemName, mode, targetSpeed, symmetricGeometry, 40.0);
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
  // Review 53357: the already-authorized 53354 assignment, real (unmodified) geometry throughout
  // - do NOT change pack geometry (review's own explicit instruction; the geometry diagnostic
  // was informative, not a confirmed cause, and the asymmetry may be intentional pack design).
  // FIRST: grip .5 vs .7 (same f=.1/assist400-160-1000) on the existing occupied turn AND
  // brake-during-turn fixture - .5's own data already reported (53342/53349/53353 - tuned:
  // maxAbsBankDegrees=11.83deg over a full 10s turn; tunedturnbrake: maxAbsBankDegrees=13.90deg,
  // near-rest after brake, no rollover), so only the .7 variants are new here. THEN: mirrored
  // (both turn directions) post-turn neutral-steer recovery at .7, under actual turn dynamics
  // (not the synthetic straight-line disturbance test) - checking residual/settling bank once
  // rudder returns to 0.
  // Review 53361: "SAME mirrored recoveries at .7 plus .5 comparison" - grip=.7's recovery pair
  // (recoverLeft-grip7/recoverRight-grip7) is already captured/reported; this round adds ONLY
  // the matching grip=.5 pair (reusing the existing divtest:turncmp-tuned config - same
  // f=.1/400/160/1000/10-50, lateralGripMultiplier=0.5 - not a new config entry) for a direct,
  // bounded comparison. Not re-running the already-reported .7 pair or expanding scope further.
  // Ticket 53365: lightweight diagnostic-only check of the new engineFuelElectricalReceipt log
  // line across the 4 states John asked to be tested - never drives, no turn/brake/recovery.
  Scenario engineCheckNoEngine = new Scenario("engineCheck-noEngine", "turncmp-tuned", Mode.ENGINE_STATE_CHECK, 0, false);
  engineCheckNoEngine.hasEngine = false;
  Scenario engineCheckStopped = new Scenario("engineCheck-stopped", "turncmp-tuned", Mode.ENGINE_STATE_CHECK, 0, false);
  engineCheckStopped.engineRunningDesired = false; engineCheckStopped.gearDesired = 0.0;
  Scenario engineCheckRunningNeutral = new Scenario("engineCheck-runningNeutral", "turncmp-tuned", Mode.ENGINE_STATE_CHECK, 0, false);
  engineCheckRunningNeutral.engineRunningDesired = true; engineCheckRunningNeutral.gearDesired = 0.0;
  Scenario engineCheckRunningInGear = new Scenario("engineCheck-runningInGear", "turncmp-tuned", Mode.ENGINE_STATE_CHECK, 0, false);
  engineCheckRunningInGear.engineRunningDesired = true; engineCheckRunningInGear.gearDesired = 1.0;
  // Review 53367: proves the edge-trigger fix - running/gear held fixed for 150 ticks while rpm
  // churns every tick should produce exactly 2 engineFuelElectricalReceipt lines (the initial
  // tick=0 state, then one periodic resample at tick=100), not 150.
  Scenario engineCheckRpmChurn = new Scenario("engineCheck-rpmChurn", "turncmp-tuned", Mode.ENGINE_STATE_CHECK, 0, false);
  engineCheckRpmChurn.engineRunningDesired = true; engineCheckRpmChurn.gearDesired = 1.0; engineCheckRpmChurn.churnRpmEachTick = true;
  // Ticket 53373: direct proof the AnimationSwitchbox-based fix correctly excludes a hidden
  // conditional group's boxes and includes a visible one's - chassisReceipt's own logged
  // halfExtents.y will differ by 10 blocks between these two if the fix is working (the deep box
  // sits 10 blocks below CHASSIS_CENTER), and match the plain fixture's halfExtents.y (0.441) if
  // excluded correctly when hidden.
  Scenario conditionalHidden = new Scenario("conditionalGroup-hidden", "turncmp-tuned", Mode.ENGINE_STATE_CHECK, 0, false);
  conditionalHidden.conditionalGroupVisible = false;
  Scenario conditionalVisible = new Scenario("conditionalGroup-visible", "turncmp-tuned", Mode.ENGINE_STATE_CHECK, 0, false);
  conditionalVisible.conditionalGroupVisible = true;
  // Ticket 53372's real merc230 numbers - end-to-end reproduction. wheelsPresent should now
  // correctly narrow to the interior-floor-only bounds (center.y=.5625 half.y=.75), while
  // noWheels should reproduce the ORIGINAL buggy wide bounds EXACTLY (center.y=.42125
  // half.y=.89125, matching comment 53372's own reported numbers) - confirming this fixture
  // accurately models the real reported bug in the state the fix does NOT need to narrow.
  Scenario merc230WheelsPresent = new Scenario("merc230-wheelsPresent", "turncmp-tuned", Mode.ENGINE_STATE_CHECK, 0, false);
  merc230WheelsPresent.merc230WheelsPresent = true;
  Scenario merc230NoWheels = new Scenario("merc230-noWheels", "turncmp-tuned", Mode.ENGINE_STATE_CHECK, 0, false);
  merc230NoWheels.merc230WheelsPresent = false;
  // Ticket 53381's own top-priority ask: full DRIVING causal reproduction, not just a static
  // chassisReceipt snapshot - real mass=1647/suspensionTravel=.3/wheel radius=.33/real anchors
  // under actual throttle, straight line (rudder=0, Mode.STRAIGHT_SETTLE), comparing the raw
  // (buggy, wide - fallback visible) envelope against the fixed (narrow - fallback hidden) one.
  // Per-wheel normal load and chassis-corner clearance are both already captured by the
  // existing debugGroundContact/chassisCornerGroundClearanceReceipt diagnostics for free.
  Scenario merc230DriveRaw = new Scenario("merc230Drive-raw", "merc230drive", Mode.STRAIGHT_SETTLE, TARGET_SPEED, false);
  merc230DriveRaw.merc230RawEnvelope = true;
  Scenario merc230DriveFixed = new Scenario("merc230Drive-fixed", "merc230drive", Mode.STRAIGHT_SETTLE, TARGET_SPEED, false);
  merc230DriveFixed.merc230RawEnvelope = false;
  // Ticket 53383's explicit priority test: real incremental wheel-install refresh, with
  // pose/velocity continuity checked across the rebuild (not just that it doesn't crash).
  Scenario merc230IncrementalInstall = new Scenario("merc230-incrementalInstall", "merc230drive", Mode.INCREMENTAL_INSTALL, 0, false);
  merc230IncrementalInstall.merc230RawEnvelope = true; // starts wide - settle()'s own mid-run flip corrects it
  merc230IncrementalInstall.incrementalInstall = true;
  // Ticket 53383's explicit sequencing: accepted quad start/turn/brake must also be reconfirmed
  // under this round's lifecycle-refresh/netMatrix/all-hidden changes before any packaging -
  // reuses the EXACT same real ATV geometry (symmetricGeometry=false, WHEEL_* constants) and
  // grip=.7 config already accepted and installed, same TURN_THEN_BRAKE combined check this
  // ticket has used throughout (53342/53357/etc.) - a true behavioral regression, not just a
  // static chassisReceipt comparison.
  Scenario quadRegression = new Scenario("quadRegression-turnbrake", "turncmp-grip7", Mode.TURN_THEN_BRAKE, TARGET_SPEED, false);
  scenarios = List.of(engineCheckNoEngine, engineCheckStopped, engineCheckRunningNeutral, engineCheckRunningInGear, engineCheckRpmChurn,
    conditionalHidden, conditionalVisible, merc230WheelsPresent, merc230NoWheels, merc230DriveRaw, merc230DriveFixed, merc230IncrementalInstall,
    quadRegression);
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
    case HOLD -> hold(s);
    case BRAKE -> brake(s);
    case BRAKE_DURING_TURN -> brakeDuringTurn(s);
    case STRAIGHT_SETTLE -> straightSettle(s);
    case RECOVER -> recover(s);
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
  // Review 53354: symmetricGeometry replaces ONLY the rear-left anchor's x with 0.375 (mirroring
  // WHEEL_RR exactly) - the real/established asymmetric value (0.35) is otherwise used
  // everywhere else in this file and its sibling probes, unchanged.
  // Ticket 53381: merc230's real wheel anchors/radius (x=+-.90625, y=0, z=-.03125 front/3.1875
  // rear, radius=.33) instead of the synthetic ATV's WHEEL_* constants/default .2 radius.
  Point3D wheelFL = s.merc230RawEnvelope != null ? new Point3D(-0.90625, 0, -0.03125) : WHEEL_FL;
  Point3D wheelFR = s.merc230RawEnvelope != null ? new Point3D(0.90625, 0, -0.03125) : WHEEL_FR;
  Point3D wheelRR = s.merc230RawEnvelope != null ? new Point3D(0.90625, 0, 3.1875) : WHEEL_RR;
  Point3D wheelRL = s.merc230RawEnvelope != null ? new Point3D(-0.90625, 0, 3.1875)
    : (s.symmetricGeometry ? new Point3D(0.375, WHEEL_RL.y, WHEEL_RL.z) : WHEEL_RL); // review 53354's symmetricGeometry fixup, unused for merc230
  double wheelRadius = s.merc230RawEnvelope != null ? 0.33 : 0.2;
  var wheelSlotFL = wheelSlot(wheelFL, true);
  var wheelSlotFR = wheelSlot(wheelFR, true);
  var wheelSlotRL = wheelSlot(wheelRL, false);
  var wheelSlotRR = wheelSlot(wheelRR, false);
  var engineSlot = engineSlot();
  var seatSlot = seatSlot();

  JSONVehicle definition = buildVehicleDefinition(s.systemName);
  definition.parts = List.of(wheelSlotFL, wheelSlotFR, wheelSlotRL, wheelSlotRR, engineSlot, seatSlot);
  // Ticket 53373's own real-world shape: merc230's pack has a SECOND collision group gated by a
  // visibility condition (only active when a wheel is absent), which the fix must correctly
  // exclude/include. Reproduced here deterministically using IV's own ComputedVariable
  // CONSTANT_PREFIX ("#") convention - a constant-valued variable needs no real part-presence
  // plumbing to control, directly proving the AnimationSwitchbox evaluation itself works in BOTH
  // directions rather than merely happening to pass on the existing always-active fixture.
  if (s.conditionalGroupVisible != null) {
   var deepBox = new JSONCollisionBox();
   deepBox.pos = new Point3D(CHASSIS_CENTER.x, CHASSIS_CENTER.y - 10.0, CHASSIS_CENTER.z);
   deepBox.width = 0.1f; deepBox.height = 0.1f;
   var visibilityAnim = new minecrafttransportsimulator.jsondefs.JSONAnimationDefinition();
   visibilityAnim.animationType = minecrafttransportsimulator.jsondefs.JSONAnimationDefinition.AnimationComponentType.VISIBILITY;
   visibilityAnim.variable = "#" + (s.conditionalGroupVisible ? "1.0" : "0.0");
   visibilityAnim.clampMin = 1; visibilityAnim.clampMax = 1;
   var conditionalGroup = new JSONCollisionGroup();
   conditionalGroup.collisionTypes = Set.of(JSONCollisionGroup.CollisionType.VEHICLE);
   conditionalGroup.collisions = List.of(deepBox);
   conditionalGroup.animations = List.of(visibilityAnim);
   definition.collisionGroups = List.of(definition.collisionGroups.get(0), conditionalGroup);
  }
  // Ticket 53372's real merc230 numbers: unfiltered union center=(0,.42125,1.40625)
  // halfExtents=(1.1875,.89125,2.8125) -> bottom=.42125-.89125=-.47 (the missing-wheel fallback
  // group's own bottom, confirmed exactly), top=.42125+.89125=1.3125. Interior floor bottom=
  // -.1875 (given) - top assumed the SAME 1.3125 (the fallback box only extends the bottom
  // deeper, per 53372's own description; not independently confirmed from the supplied receipt,
  // since only the fallback's own bottom and the floor's own bottom were given, not two
  // independent full boxes - the X/Z footprint is assumed shared between both groups too, for
  // the same reason). mtsofficialpack:merc230-shaped X/Z halfExtents (1.1875,2.8125) reused as
  // given, center.z=1.40625 as given.
  if (s.merc230WheelsPresent != null) {
   var floorBox = new JSONCollisionBox();
   floorBox.pos = new Point3D(0, 0.5625, 1.40625); // bottom=-.1875, top=1.3125 (assumed)
   floorBox.width = (float)(1.1875*2); floorBox.height = (float)(0.75*2);
   var floorGroup = new JSONCollisionGroup();
   floorGroup.collisionTypes = Set.of(JSONCollisionGroup.CollisionType.VEHICLE);
   floorGroup.collisions = List.of(floorBox);
   var fallbackBox = new JSONCollisionBox();
   fallbackBox.pos = new Point3D(0, 0.42125, 1.40625); // exactly the real unfiltered numbers
   fallbackBox.width = (float)(1.1875*2); fallbackBox.height = (float)(0.89125*2);
   var fallbackVisibilityAnim = new minecrafttransportsimulator.jsondefs.JSONAnimationDefinition();
   fallbackVisibilityAnim.animationType = minecrafttransportsimulator.jsondefs.JSONAnimationDefinition.AnimationComponentType.VISIBILITY;
   // visible (included) exactly when a wheel is ABSENT - fallbackVisible = !wheelsPresent.
   fallbackVisibilityAnim.variable = "#" + (s.merc230WheelsPresent ? "0.0" : "1.0");
   fallbackVisibilityAnim.clampMin = 1; fallbackVisibilityAnim.clampMax = 1;
   var fallbackGroup = new JSONCollisionGroup();
   fallbackGroup.collisionTypes = Set.of(JSONCollisionGroup.CollisionType.VEHICLE);
   fallbackGroup.collisions = List.of(fallbackBox);
   fallbackGroup.animations = List.of(fallbackVisibilityAnim);
   definition.collisionGroups = List.of(floorGroup, fallbackGroup);
  }
  // Ticket 53381's correction: width-shared X/Z is no real blocker - two SQUARE boxes (width
  // matching the real X half-extent*2=2.375) positioned at the Z extremes correctly reproduce
  // the EXACT overall Z bounding envelope (centerZ=1.40625, halfZ=2.8125) via min/max alone, even
  // though the pair leaves a real gap between them - this code only ever computes an AABB union,
  // never an actual collision volume, so the gap is immaterial to the result. z=-.21875 and
  // z=3.03125 span exactly [-1.40625,4.21875] = center 1.40625 +-2.8125, matching John's own
  // worked numbers exactly. Applied to BOTH merc230 groups so each independently produces the
  // real footprint, not just a narrow single-box one.
  if (s.merc230RawEnvelope != null) {
   java.util.function.Function<Double, List<JSONCollisionBox>> twoSquareBoxesAtY = centerY -> {
    var b1 = new JSONCollisionBox(); b1.pos = new Point3D(0, centerY, -0.21875); b1.width = 2.375f; b1.height = 0f;
    var b2 = new JSONCollisionBox(); b2.pos = new Point3D(0, centerY, 3.03125); b2.width = 2.375f; b2.height = 0f;
    return List.of(b1, b2);
   };
   // height=0 above (Z-span only, via width) - the REAL Y extent comes from a THIRD box at the
   // group's own centerY with the real height, reusing the SAME two-Z-extreme positions is
   // unnecessary for Y since min/max unions freely across all boxes in the group regardless of
   // which box carries which axis's extent.
   var floorY = new JSONCollisionBox(); floorY.pos = new Point3D(0, 0.5625, 1.40625); floorY.width = 1f; floorY.height = (float)(0.75*2);
   var floorGroup = new JSONCollisionGroup();
   floorGroup.collisionTypes = Set.of(JSONCollisionGroup.CollisionType.VEHICLE);
   floorGroup.collisions = new java.util.ArrayList<>(twoSquareBoxesAtY.apply(0.5625));
   floorGroup.collisions.add(floorY);
   var fallbackY = new JSONCollisionBox(); fallbackY.pos = new Point3D(0, 0.42125, 1.40625); fallbackY.width = 1f; fallbackY.height = (float)(0.89125*2);
   var fallbackVisibilityAnim = new minecrafttransportsimulator.jsondefs.JSONAnimationDefinition();
   fallbackVisibilityAnim.animationType = minecrafttransportsimulator.jsondefs.JSONAnimationDefinition.AnimationComponentType.VISIBILITY;
   if (s.incrementalInstall) {
    // Real, mutable variable (not a "#"-constant) - visible (wide/buggy) while value==0 ("wheel
    // not installed yet"), hidden (narrow/fixed) once flipped to 1 mid-run by settle().
    fallbackVisibilityAnim.variable = "wheelInstalledVar";
    fallbackVisibilityAnim.clampMin = 0; fallbackVisibilityAnim.clampMax = 0;
   } else {
    fallbackVisibilityAnim.variable = "#" + (s.merc230RawEnvelope ? "1.0" : "0.0");
    fallbackVisibilityAnim.clampMin = 1; fallbackVisibilityAnim.clampMax = 1;
   }
   var fallbackGroup = new JSONCollisionGroup();
   fallbackGroup.collisionTypes = Set.of(JSONCollisionGroup.CollisionType.VEHICLE);
   fallbackGroup.collisions = new java.util.ArrayList<>(twoSquareBoxesAtY.apply(0.42125));
   fallbackGroup.collisions.add(fallbackY);
   fallbackGroup.animations = List.of(fallbackVisibilityAnim);
   definition.collisionGroups = List.of(floorGroup, fallbackGroup);
  }
  AWrapperWorld world = mcinterface1211.WrapperWorld.getWrapperFor(level);
  var item = new ItemVehicle(definition, definition.definitions.get(0), PACK_ID);
  var vehicle = new EntityVehicleF_Physics(world, null, item, null);
  vehicle.position.set(blockX + 0.5, surfaceY + 1.0, blockZ + 0.5);
  vehicle.orientation.set(new RotationMatrix());
  world.spawnEntity(vehicle);

  addWheel(vehicle, wheelSlotFL, wheelRadius);
  addWheel(vehicle, wheelSlotFR, wheelRadius);
  addWheel(vehicle, wheelSlotRL, wheelRadius);
  addWheel(vehicle, wheelSlotRR, wheelRadius);
  // Ticket 53365's "no engine installed" ENGINE_STATE_CHECK scenario: the slot stays declared
  // (realistic "engine bay present, nothing installed in it"), but no PartEngine is actually
  // added - v.allParts then genuinely contains zero PartEngine instances.
  if (s.hasEngine) {
   s.engine = addEngine(vehicle, engineSlot);
   s.engine.running = s.engineRunningDesired;
   s.engine.currentGearVar.setTo(s.gearDesired, false);
  } else {
   s.engine = null;
  }
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

  if (s.incrementalInstall) vehicle.getOrCreateVariable("wheelInstalledVar").currentValue = 0.0;

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

 private static void addWheel(EntityVehicleF_Physics vehicle, JSONPartDefinition slot) { addWheel(vehicle, slot, 0.2); }
 private static void addWheel(EntityVehicleF_Physics vehicle, JSONPartDefinition slot, double radius) {
  var ground = new JSONPart.JSONPartGroundDevice();
  ground.isWheel = true; ground.width = (float)(radius*2); ground.height = (float)(radius*2);
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
  if (s.phase == Phase.TURN || s.phase == Phase.BRAKE || s.phase == Phase.BRAKE_DURING_TURN || s.phase == Phase.STRAIGHT_SETTLE || s.phase == Phase.RECOVER) {
   s.minUpY = Math.min(s.minUpY, v.orientation.m11);
   // Review 53352's own "bank/pitch extrema" - pitch (wheelie/nose-down) about the body-right
   // axis, using the forward vector's own world-Y component (bodyForward is column 2:
   // m02/m12/m22, so forward.y = m12) - a simple, standard measure, in the same spirit as bank's
   // own use of the right vector's vertical component just below, not claimed to be the same
   // gimbal-free rigor SelfRightingAssist's own bank formula has (pitch was not that class's own
   // documented concern).
   double pitchDegrees = Math.toDegrees(Math.asin(Math.max(-1, Math.min(1, -v.orientation.m12))));
   s.maxAbsPitchDegrees = Math.max(s.maxAbsPitchDegrees, Math.abs(pitchDegrees));
   // Review 53341: signed bank = atan2(bodyRight.y, bodyUp.y) about bodyForward alone, the SAME
   // formula SelfRightingAssist itself uses internally (its own class doc's algebraic
   // justification) - NOT plain up.y, which conflates pitch/wheelie with sideways bank. bodyUp is
   // column 1 of the orientation matrix (confirmed via PhysicsBridge's own mapping, m01/m11/m21);
   // bodyRight is column 0 by the same convention (m00/m10/m20), so bodyRight.y = m10.
   double bankRadians = Math.atan2(v.orientation.m10, v.orientation.m11);
   double bankDegrees = Math.toDegrees(bankRadians);
   s.maxAbsBankDegrees = Math.max(s.maxAbsBankDegrees, Math.abs(bankDegrees));
   s.recentSignedBankDegrees.addLast(bankDegrees);
   while (s.recentSignedBankDegrees.size() > STEADY_STATE_WINDOW_TICKS) s.recentSignedBankDegrees.removeFirst();
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
    + " maxAbsBankDegrees=" + s.maxAbsBankDegrees + " maxAbsPitchDegrees=" + s.maxAbsPitchDegrees
    + " maxAbsBankRateDegPerTick=" + s.maxAbsBankRateDegreesPerTick
    + " maxAbsLateralSlip=" + s.maxAbsLateralSlip + " - " + reason);
  s.phase = Phase.FAILED;
 }

 private static void settle(Scenario s) {
  s.ticksInPhase++;
  if (!checkBoundedOrFail(s)) return;
  // Review 53385: "your stationary custom-variable flip cannot prove this" - a vehicle at rest
  // has zero angular velocity either way, so a rebuild silently zeroing it is invisible. This
  // scenario now actively DRIVES AND TURNS (sustained throttle+rudder, real nonzero yaw rate)
  // through the ENTIRE window, including the exact tick the flip/rebuild happens, so a silently
  // zeroed angular velocity would show up as a visible, measurable yaw-rate discontinuity right
  // at the flip tick - logged explicitly every tick through a window around it to make any such
  // snap directly checkable, not just asserted.
  if (s.mode == Mode.INCREMENTAL_INSTALL) {
   if (s.engine != null && !s.engine.running) forceEngineRunning(s.engine);
   s.vehicle.throttleVar.setTo(1.0, false);
   s.vehicle.rudderInputVar.setTo(40.0, false);
  }
  // Ticket 53383: flip the real wheelInstalledVar mid-run (at the halfway point of this mode's
  // own window) to simulate a wheel actually being installed after Body already activated once
  // with the wide/buggy envelope - directly exercising chassisBoundsChanged's rebuild trigger.
  if (s.mode == Mode.INCREMENTAL_INSTALL && s.ticksInPhase == INCREMENTAL_INSTALL_TICKS/2) {
   s.vehicle.getOrCreateVariable("wheelInstalledVar").currentValue = 1.0;
   s.incrementalInstallFlipPosition = new Point3D(s.vehicle.position.x, s.vehicle.position.y, s.vehicle.position.z);
   LOGGER.info("[DIV turnComparison] scenario=" + s.label + " flipped wheelInstalledVar to 1.0 at tick=" + s.ticksInPhase
     + " position=" + s.vehicle.position + " motion=" + s.vehicle.motion + " yawDegrees=" + Math.toDegrees(Math.atan2(s.vehicle.orientation.m02, s.vehicle.orientation.m22)));
  }
  if (s.mode == Mode.INCREMENTAL_INSTALL && Math.abs(s.ticksInPhase - INCREMENTAL_INSTALL_TICKS/2) <= 10) {
   LOGGER.info("[DIV turnComparison] scenario=" + s.label + " tick=" + s.ticksInPhase
     + " yawDegrees=" + Math.toDegrees(Math.atan2(s.vehicle.orientation.m02, s.vehicle.orientation.m22))
     + " position=" + s.vehicle.position);
  }
  // Review 53367's churn scenario needs to run PAST ENGINE_FUEL_ELECTRICAL_RECEIPT_PERIOD_TICKS
  // (100) to actually exercise the periodic resample, well beyond ordinary SETTLE_TICKS (60).
  if (s.mode == Mode.ENGINE_STATE_CHECK && s.churnRpmEachTick && s.engine != null) s.engine.rpm = 800 + s.ticksInPhase;
  int settleTicksForThisScenario = (s.mode == Mode.ENGINE_STATE_CHECK && s.churnRpmEachTick) ? ENGINE_STATE_CHECK_CHURN_TICKS
    : s.mode == Mode.INCREMENTAL_INSTALL ? INCREMENTAL_INSTALL_TICKS : SETTLE_TICKS;
  if (s.mode == Mode.INCREMENTAL_INSTALL && s.ticksInPhase >= settleTicksForThisScenario) {
   s.phase = Phase.DONE;
   LOGGER.info("[DIV turnComparison] scenario=" + s.label + " COMPLETE - INCREMENTAL_INSTALL finished, flipPosition=" + s.incrementalInstallFlipPosition
     + " finalPosition=" + s.vehicle.position + " finalMotion=" + s.vehicle.motion + " minUpYSeen=" + s.minUpY + " ticksRun=" + s.ticksInPhase);
   return;
  }
  if (s.ticksInPhase >= settleTicksForThisScenario) {
   // ENGINE_STATE_CHECK (ticket 53365): never drives - just lets PhysicsBridge.Body tick long
   // enough for its new engineFuelElectricalReceipt diagnostic to fire (gated the same way as
   // every other debugGroundContact line - DEBUG_GROUND_CONTACT_TICKS is per-Body, fresh at this
   // scenario's own spawn, so this window is already well within its 600-tick budget), then
   // reports what THIS scenario actually configured so the real log line (named
   // engineFuelElectricalReceipt, visible separately in the server log) can be checked against it.
   if (s.mode == Mode.ENGINE_STATE_CHECK) {
    s.phase = Phase.DONE;
    LOGGER.info("[DIV turnComparison] scenario=" + s.label + " COMPLETE - ENGINE_STATE_CHECK configured hasEngine=" + s.hasEngine
      + (s.engine != null ? " engineRunning=" + s.engine.running + " gear=" + s.engine.currentGearVar.currentValue : " (no engine part installed)")
      + " fuelTankPresent=" + (s.vehicle.fuelTank != null) + " electricPower=" + s.vehicle.electricPower
      + " churnRpmEachTick=" + s.churnRpmEachTick + " ticksRun=" + s.ticksInPhase);
    return;
   }
   s.phase = Phase.ACCELERATE; s.ticksInPhase = 0;
   LOGGER.info("[DIV turnComparison] scenario=" + s.label + " beginning ACCELERATE phase toward targetSpeed=" + s.targetSpeed + " m/s (mode=" + s.mode + ")");
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
   s.ticksInPhase = 0;
   if (s.mode == Mode.TURN || s.mode == Mode.TURN_THEN_BRAKE || s.mode == Mode.TURN_THEN_RECOVER) {
    s.phase = Phase.TURN;
    s.vehicle.rudderInputVar.setTo(s.rudderDegrees, false); // within MAX_RUDDER_ANGLE=45 - signed, review 53357's own "mirrored" turn direction ask
    LOGGER.info("[DIV turnComparison] scenario=" + s.label + " beginning sustained TURN phase at speed=" + speed + " rudder=" + s.rudderDegrees + "deg");
   } else if (s.mode == Mode.STRAIGHT_SETTLE) {
    s.phase = Phase.STRAIGHT_SETTLE;
    s.vehicle.rudderInputVar.setTo(0.0, false); // deliberately ZERO - isolating persistent lean from deliberate turn bank
    LOGGER.info("[DIV turnComparison] scenario=" + s.label + " beginning STRAIGHT_SETTLE phase at speed=" + speed + " rudder=0deg (symmetricGeometry=" + s.symmetricGeometry + ")");
   } else {
    s.phase = Phase.HOLD;
    LOGGER.info("[DIV turnComparison] scenario=" + s.label + " beginning HOLD phase at speed=" + speed);
   }
  }
 }

 // Review 53354: zero rudder, maintain a small bounded speed, for long enough to separate a
 // genuine persistent steady-state lean from initial settling transients or deliberate turn
 // bank - the SAME signed-bank computation checkBoundedOrFail already does, just held for a
 // much longer straight-line window and averaged over its final STEADY_STATE_WINDOW_TICKS.
 private static void straightSettle(Scenario s) {
  s.ticksInPhase++;
  if (!checkBoundedOrFail(s)) return;
  forceEngineRunning(s.engine);
  double speed = forwardSpeed(s);
  s.vehicle.throttleVar.setTo(speed < s.targetSpeed ? 1.0 : 0.0, false);
  if (s.ticksInPhase % 20 == 0) logState(s, "STRAIGHT_SETTLE");
  if (s.ticksInPhase >= STRAIGHT_SETTLE_TICKS) {
   s.steadyStateSignedBankDegrees = s.recentSignedBankDegrees.stream().mapToDouble(Double::doubleValue).average().orElse(Double.NaN);
   s.phase = Phase.DONE;
   LOGGER.info("[DIV turnComparison] scenario=" + s.label + " COMPLETE - STRAIGHT_SETTLE finished (symmetricGeometry=" + s.symmetricGeometry + "),"
     + " steadyStateSignedBankDegrees=" + s.steadyStateSignedBankDegrees + " (avg of last " + s.recentSignedBankDegrees.size() + " ticks)"
     + " maxAbsBankDegrees=" + s.maxAbsBankDegrees + " maxAbsPitchDegrees=" + s.maxAbsPitchDegrees
     + " maxAbsLateralSlip=" + s.maxAbsLateralSlip + " finalSpeed=" + speed + " minUpYSeen=" + s.minUpY);
  }
 }

 private static void turn(Scenario s) {
  s.ticksInPhase++;
  if (!checkBoundedOrFail(s)) return;
  forceEngineRunning(s.engine);
  double speed = forwardSpeed(s);
  s.vehicle.throttleVar.setTo(speed < s.targetSpeed ? 1.0 : 0.0, false); // keep driving through the turn - a stationary vehicle can't meaningfully flop
  if (s.ticksInPhase % 10 == 0) logState(s, "TURN");
  if (s.mode == Mode.TURN_THEN_BRAKE) {
   // Review 53352: "reach stable ~5m/s sustained 40deg turn, then apply full brake with
   // throttle 0 while retaining steering" - a shorter stabilization window than the full
   // TURN_TICKS, then transition straight into BRAKE_DURING_TURN rather than DONE.
   if (s.ticksInPhase >= STABILIZE_TURN_TICKS) {
    var v = s.vehicle;
    s.onsetSpeedDuringTurnBrake = speed;
    s.onsetPitchDegreesDuringTurnBrake = s.maxAbsPitchDegrees; // last-known pitch is this tick's own value (already updated by checkBoundedOrFail above)
    s.onsetYawDegreesDuringTurnBrake = Math.toDegrees(Math.atan2(v.orientation.m02, v.orientation.m22));
    s.onsetVerticalFromSurfaceDuringTurnBrake = Math.abs(v.position.y - s.spawnSurfaceY);
    s.vehicle.throttleVar.setTo(0.0, false);
    s.vehicle.brakeVar.setActive(true, false);
    // Rudder is deliberately LEFT at 40deg (review's own "retaining steering") - not reset.
    s.phase = Phase.BRAKE_DURING_TURN; s.ticksInPhase = 0;
    LOGGER.info("[DIV turnComparison] scenario=" + s.label + " beginning BRAKE_DURING_TURN - onset speed=" + s.onsetSpeedDuringTurnBrake
      + " onset bankDegrees=" + s.maxAbsBankDegrees + " onset pitchDegreesSoFar=" + s.onsetPitchDegreesDuringTurnBrake
      + " onset yawDegrees=" + s.onsetYawDegreesDuringTurnBrake + " onset verticalFromSurface=" + s.onsetVerticalFromSurfaceDuringTurnBrake
      + " (rudder retained at 40deg, throttle=0, brake=active)");
   }
   return;
  }
  if (s.mode == Mode.TURN_THEN_RECOVER) {
   // Review 53357: "mirrored post-turn neutral-steer recovery to check residual bank/settling
   // under actual dynamics" - a real turn (STABILIZE_TURN_TICKS, same as the brake-during-turn
   // case) rather than a synthetic disturbance, then rudder released to exactly 0 and the SAME
   // signed-bank tracking observed through the recovery window.
   if (s.ticksInPhase >= STABILIZE_TURN_TICKS) {
    s.bankAtRudderRelease = Math.toDegrees(Math.atan2(s.vehicle.orientation.m10, s.vehicle.orientation.m11));
    s.vehicle.rudderInputVar.setTo(0.0, false);
    s.phase = Phase.RECOVER; s.ticksInPhase = 0;
    s.recentSignedBankDegrees.clear();
    LOGGER.info("[DIV turnComparison] scenario=" + s.label + " rudder released to 0 - bankAtRelease=" + s.bankAtRudderRelease
      + " speed=" + speed + " - beginning RECOVER phase");
   }
   return;
  }
  if (s.ticksInPhase >= TURN_TICKS) {
   s.phase = Phase.DONE;
   LOGGER.info("[DIV turnComparison] scenario=" + s.label + " COMPLETE - sustained TURN finished, minUpYSeen=" + s.minUpY
     + " (1.0=never tilted; <" + UPRIGHT_MIN_VERTICALITY + " would have STOPPED as a rollover)"
     + " maxAbsBankDegrees=" + s.maxAbsBankDegrees + " maxAbsBankRateDegPerTick=" + s.maxAbsBankRateDegreesPerTick
     + " maxAbsLateralSlip=" + s.maxAbsLateralSlip + " m/s");
  }
 }

 // Review 53357: rudder stays at exactly 0 (released, not reset to some other value) while
 // throttle keeps driving at targetSpeed - tracks whether signed bank returns toward the
 // pre-turn baseline (near the straightsettle-asym steady-state, ~-0.38deg) or settles at a
 // different residual / keeps oscillating, over a bounded recovery window.
 private static void recover(Scenario s) {
  s.ticksInPhase++;
  if (!checkBoundedOrFail(s)) return;
  forceEngineRunning(s.engine);
  double speed = forwardSpeed(s);
  s.vehicle.throttleVar.setTo(speed < s.targetSpeed ? 1.0 : 0.0, false);
  if (s.ticksInPhase % 10 == 0) logState(s, "RECOVER");
  if (s.ticksInPhase >= RECOVER_TICKS) {
   s.steadyStateSignedBankDegrees = s.recentSignedBankDegrees.stream().mapToDouble(Double::doubleValue).average().orElse(Double.NaN);
   s.phase = Phase.DONE;
   LOGGER.info("[DIV turnComparison] scenario=" + s.label + " COMPLETE - RECOVER finished, bankAtRudderRelease=" + s.bankAtRudderRelease
     + " steadyStateSignedBankDegrees=" + s.steadyStateSignedBankDegrees + " (avg of last " + s.recentSignedBankDegrees.size() + " ticks)"
     + " maxAbsBankDegrees=" + s.maxAbsBankDegrees + " maxAbsBankRateDegPerTick=" + s.maxAbsBankRateDegreesPerTick
     + " maxAbsLateralSlip=" + s.maxAbsLateralSlip + " minUpYSeen=" + s.minUpY);
  }
 }

 // Review 53352: the combined turn+brake check itself - rudder/steering retained (set once at
 // the BRAKE_DURING_TURN transition above, never reset here), throttle=0, brake=active, for up
 // to BRAKE_DURING_TURN_TICKS (5s). Records horizontal speed magnitude (forward AND lateral
 // components combined) at the end, per review's own explicit "not solely body-forward
 // component, to avoid mistaking sideways slide for stopping".
 private static void brakeDuringTurn(Scenario s) {
  s.ticksInPhase++;
  if (!checkBoundedOrFail(s)) return;
  var v = s.vehicle;
  double forward = forwardSpeed(s);
  double lateral = (v.motion.x * v.orientation.m00 + v.motion.y * v.orientation.m10 + v.motion.z * v.orientation.m20) * v.speedFactor * 20;
  double horizontalSpeed = Math.hypot(forward, lateral);
  if (s.ticksInPhase % 10 == 0) {
   LOGGER.info("[DIV turnComparison] scenario=" + s.label + " BRAKE_DURING_TURN tick=" + s.ticksInPhase
     + " forwardSpeed=" + forward + " lateralSpeed=" + lateral + " horizontalSpeed=" + horizontalSpeed
     + " position=" + v.position + " up(m01,m11,m21)=(" + v.orientation.m01 + "," + v.orientation.m11 + "," + v.orientation.m21 + ")"
     + " rudder=" + v.rudderInputVar.currentValue + " brakeActive=" + v.brakeVar.isActive);
  }
  if (s.ticksInPhase >= BRAKE_DURING_TURN_TICKS) {
   s.finalHorizontalSpeed = horizontalSpeed;
   s.phase = Phase.DONE;
   double onsetToFinalFractionalReduction = s.onsetSpeedDuringTurnBrake > 0 ? (s.onsetSpeedDuringTurnBrake - horizontalSpeed) / s.onsetSpeedDuringTurnBrake : 0;
   LOGGER.info("[DIV turnComparison] scenario=" + s.label + " COMPLETE - combined TURN+BRAKE check finished,"
     + " onsetSpeed=" + s.onsetSpeedDuringTurnBrake + " onsetPitchDegreesSoFar=" + s.onsetPitchDegreesDuringTurnBrake
     + " onsetYawDegrees=" + s.onsetYawDegreesDuringTurnBrake + " onsetVerticalFromSurface=" + s.onsetVerticalFromSurfaceDuringTurnBrake
     + " finalForwardSpeed=" + forward + " finalLateralSpeed=" + lateral + " finalHorizontalSpeed=" + horizontalSpeed
     + " onsetToFinalHorizontalFractionalReduction=" + onsetToFinalFractionalReduction
     + " minUpYSeen=" + s.minUpY + " maxAbsBankDegrees=" + s.maxAbsBankDegrees + " maxAbsPitchDegrees=" + s.maxAbsPitchDegrees
     + " maxAbsLateralSlip=" + s.maxAbsLateralSlip
     + " (1.0=never tilted; <" + UPRIGHT_MIN_VERTICALITY + " would have STOPPED as a rollover)");
  }
 }

 // Review 53348: a bounded STRAIGHT-LINE brake check of the bundled tuning at ~8 m/s, same
 // matched runtime flags as the TURN scenarios - no rudder input at all, isolating braking's own
 // combined-force behavior under the new anisotropic friction budget from steering entirely.
 private static void hold(Scenario s) {
  s.ticksInPhase++;
  if (!checkBoundedOrFail(s)) return;
  forceEngineRunning(s.engine);
  double speed = forwardSpeed(s);
  s.vehicle.throttleVar.setTo(speed < s.targetSpeed ? 1.0 : 0.0, false);
  if (s.ticksInPhase % 10 == 0) logState(s, "HOLD");
  if (s.ticksInPhase >= HOLD_TICKS) {
   s.speedAtBrakeStart = speed;
   s.bankAtOnset = Math.toDegrees(Math.atan2(s.vehicle.orientation.m10, s.vehicle.orientation.m11));
   s.vehicle.throttleVar.setTo(0.0, false);
   s.vehicle.brakeVar.setActive(true, false);
   s.phase = Phase.BRAKE; s.ticksInPhase = 0;
   LOGGER.info("[DIV turnComparison] scenario=" + s.label + " beginning BRAKE phase - onset speed=" + s.speedAtBrakeStart + " onset bankDegrees=" + s.bankAtOnset);
  }
 }

 private static void brake(Scenario s) {
  s.ticksInPhase++;
  if (!checkBoundedOrFail(s)) return;
  double speed = forwardSpeed(s);
  if (s.ticksInPhase % 10 == 0) logState(s, "BRAKE");
  if (s.ticksInPhase >= BRAKE_TICKS) {
   double fractionalReduction = s.speedAtBrakeStart > 0 ? (s.speedAtBrakeStart - speed) / s.speedAtBrakeStart : 0;
   s.phase = Phase.DONE;
   LOGGER.info("[DIV turnComparison] scenario=" + s.label + " COMPLETE - straight BRAKE check finished, onsetSpeed=" + s.speedAtBrakeStart
     + " onsetBankDegrees=" + s.bankAtOnset + " finalSpeed=" + speed + " fractionalReduction=" + fractionalReduction
     + " minUpYSeen=" + s.minUpY + " maxAbsBankDegrees=" + s.maxAbsBankDegrees
     + " (1.0=never tilted; <" + UPRIGHT_MIN_VERTICALITY + " would have STOPPED as a rollover)");
  }
 }
}
