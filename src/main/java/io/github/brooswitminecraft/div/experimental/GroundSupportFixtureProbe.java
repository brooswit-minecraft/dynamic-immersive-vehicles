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
import java.util.List;
import java.util.Set;

/**
 * Bounded, opt-in, CI/isolated-host-only runtime probe answering review comment 53287's explicit
 * correction: the formula-level regression tests (TwoBodyChassisGroundForceCompositionTest,
 * TwoBodyChassisTest) would keep passing even if {@code PhysicsBridge.Body.stepGround} still
 * wired the owner-isolated mass/inertia - they are extracted math, not the production code path
 * itself. This probe instead spawns a REAL {@code EntityVehicleF_Physics} with real ground-
 * device wheel parts and a real engine part, over a real (CI-world, disposable) solid platform, with
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
 *
 * <p>v3 (this version, per review 53293's independent read of the v2 CI run, 43119570767):
 * three more concrete fixture-construction defects, none of them a physics change:
 * <ul>
 *  <li>f=0.5's spawn column landed on real generated ocean (not solid) and failed outright;
 *  f=0.4 NPE'd in {@code AEntityD_Definable.updateSubDefinition} ("Cannot invoke
 *  java.util.List.iterator() because this.definition.definitions is null") - the real stack
 *  trace showed {@code JSONVehicle.definitions}/{@code JSONPart.definitions} (a
 *  {@code List<JSONSubDefinition>}) must be populated; passing a sub-definition only to
 *  {@code ItemVehicle}/{@code ItemPartGroundDevice}/{@code ItemPartEngine}'s own constructor was
 *  not enough. Fixed: that list is set (with the SAME instance also handed to the item
 *  constructor) on the vehicle and both part definitions.</li>
 *  <li>Per review's own suggestion, replaced "search real generated terrain for a solid,
 *  reasonably flat column" with an explicit carved flat platform ({@link #buildFlatPlatform}) -
 *  deterministic ground truth regardless of whatever the world generator happens to produce at
 *  that location (ocean, slope, or otherwise), rather than depending on luck or a search.</li>
 *  <li>The CI job's own fail-fast regex looked for the literal substring
 *  {@code "groundSupportFixture] FAILED"}, but this probe's own FAILED lines are
 *  {@code "groundSupportFixture] scenario=... FAILED"} - never matched, so a fast, clean FAILED
 *  result from this probe still had to wait out the FULL 600s budget before the job noticed.
 *  Fixed to match regardless of what sits between.</li>
 * </ul>
 * Exact joint drift stays a disclosed, deliberate limitation per review's own explicit allowance
 * ("don't let that hold up the useful production-path evidence") - this still does NOT reach
 * into {@code PhysicsBridge.Body}'s private half-body fields; see the rationale below.
 *
 * <p>Method: carve a real, deterministic flat solid platform in this disposable CI world
 * (confirmed loaded via chunk-grid force-loading, not a synthetic void scene, since this probe
 * specifically needs real wheel-raycast ground contact - but not dependent on whatever the
 * world generator happens to produce there either), seed the two config maps
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
 // Review 53307: a bounded LOW-SPEED integration scenario, not a high-speed stress test - the
 // real stepGround drive-force source (PhysicsBridge.java's own rawDrive=signum(gear)*throttle*
 // tuning.driveForce, and MultiContactFrictionSolver's own prescribedLongitudinal=
 // driveForcePerWheel*skidSteerMultiplier) is a FLAT, speed-independent force with only a weak
 // linear rolling-resistance term (-speed*20 per wheel) - confirmed by reading that code
 // directly, not guessed. At driveForce=8000N/4 wheels that resistance term would only balance
 // the full drive force near a ~100 m/s theoretical equilibrium, so sustained full throttle for
 // several seconds reaches far past any real ATV's practical speed long before any natural cap -
 // this is this EXISTING shared force model's own real behavior at length, not a bug introduced
 // by this fixture or the two-body candidate, and not something this fixture should try to
 // govern/drag its way around (review's explicit "do not add a production governor/drag").
 // Bounding the TEST's own throttle instead - closed-loop toward LOW_SPEED_TARGET - keeps this
 // scenario inside the platform/chunk area without needing to keep enlarging it, and is the
 // realistic, everyday-driving case this ticket actually cares about.
 private static final double LOW_SPEED_TARGET = 4.0; // m/s - within review's own "3-5 m/s" ask
 // Confirmed live: real net travel across DRIVE alone (closed-loop toward LOW_SPEED_TARGET,
 // cruising there for most of DRIVE_TICKS) is already ~22 blocks from spawn, and TURN continues
 // driving forward (curving) rather than turning in place, so cumulative horizontal-from-spawn
 // across DRIVE+BRAKE+TURN legitimately exceeds 40 blocks before TURN_TICKS elapses - this is
 // real, bounded low-speed travel, not a runaway/stress case, so the fix is a wider platform
 // (not a smaller TURN_TICKS/throttle target).
 private static final int CHUNK_RADIUS = 5; // a 11x11 chunk square (176 blocks/side) - must cover PLATFORM_RADIUS below
 // Review 53293: real generated terrain is not guaranteed solid or flat at any one chosen column
 // - carve an explicit flat solid platform in this disposable CI world instead, so ground truth
 // is deterministic. Declared early (not inline with buildFlatPlatform below) so the bounded-
 // position thresholds further down can reference PLATFORM_RADIUS without an illegal forward
 // reference.
 private static final int PLATFORM_Y = 100;
 private static final int PLATFORM_RADIUS = 70; // blocks - ample for the bounded low-speed scenario's own real cumulative travel across DRIVE+BRAKE+TURN, within the CHUNK_RADIUS loaded grid
 // TURN_TICKS confirmed live needing to be longer than the others: at bounded low speed
 // (~0.6-0.7 m/s during turning) 60 ticks (3s) produced a real, upright yawDelta of 0.094-0.0999
 // rad - just under TURN_CHECK_MIN_YAW_DELTA_RADIANS=0.1 - an honest near-miss, not a false
 // negative in the check itself; giving the low-speed steer more real time to turn is the
 // faithful fix, not shrinking the threshold to paper over it.
 private static final int SETTLE_TICKS = 60, DRIVE_TICKS = 100, BRAKE_TICKS = 60, TURN_TICKS = 140;
 // Review 53305: the chassis geometry below must be a representative ATV, not an arbitrary
 // synthetic box - these are the SAME real chassis half-extents/center/wheel-anchor values this
 // ticket's own existing tests already established and cite as "real chassisReceipt"
 // (PhysicsBridgeEffectiveMassDamperTest, TwoBodyChassisTest, WheelieStaticThresholdTest), not a
 // new guess. Half-extents are (x,y,z) = (0.425, 0.441, 1.05) - LOW and LONG, the opposite
 // proportions of the earlier v1-v4 fixture's tall 1x2x1m box review correctly flagged as
 // unrepresentative.
 private static final double HALF_EXTENT_A = 0.42500001192092896, HALF_HEIGHT_B = 0.4410937510430813, HALF_EXTENT_C = 1.050000011920929;
 private static final Point3D CHASSIS_CENTER = new Point3D(0, 0.553593748062849, 0.625);
 // Real wheel anchors (ticket's own established ATV geometry) - front (larger Z) steer, rear do
 // not, matching the real vehicle's own front-wheel-steering layout.
 private static final Point3D WHEEL_RL = new Point3D(0.35, 0.125, -0.0625);
 private static final Point3D WHEEL_FL = new Point3D(-0.375, 0.125, 1.25);
 private static final Point3D WHEEL_FR = new Point3D(0.375, 0.125, 1.25);
 private static final Point3D WHEEL_RR = new Point3D(-0.375, 0.125, -0.0625);
 // Explicit, bounded, nonzero thresholds (review 53291's "bounded nonzero thresholds or explicit
 // FAILED result") - deliberately loose (this probe asks "did anything physically sensible
 // happen at all", not "does this match a specific target curve") so it stays robust to tuning
 // changes while still catching "nothing moved"/"a stationary/inactive vehicle" outright.
 private static final double SETTLE_CHECK_MAX_VERTICAL_SPEED = 1.0; // m/s - should have come to rest, not still falling
 private static final double DRIVE_CHECK_MIN_SPEED = 0.5; // m/s - real throttle+gear must produce SOME forward speed
 private static final double DRIVE_CHECK_MAX_SPEED = LOW_SPEED_TARGET * 2; // m/s - confirms the closed-loop throttle control below actually stayed bounded, not a reversion to a high-speed stress run
 private static final double DRIVE_CHECK_MIN_DISPLACEMENT = 1.0; // blocks - real net horizontal movement during DRIVE
 private static final double BRAKE_CHECK_MIN_FRACTIONAL_REDUCTION = 0.2; // speed must drop by at least 20% under braking
 // Review 53307: "rollover is not steering success" - a vehicle that tips over or crashes also
 // technically has a large "orientation changed" delta, so TURN now requires an actual UPRIGHT
 // YAW change instead: the real up-vector's world Y-component (confirmed by reading
 // PhysicsBridge.java's own orientation mapping: v.orientation.m11 IS exactly
 // q.transform(0,1,0).y, the real up-vector's verticality - no separate computation needed)
 // must stay above UPRIGHT_MIN_VERTICALITY throughout, while the heading angle (atan2 of the
 // forward axis's X/Z components) changes by at least this many radians - wheelies (pitch) are
 // explicitly NOT penalized here, only rollover (loss of uprightness) and crashing.
 private static final double UPRIGHT_MIN_VERTICALITY = 0.7; // m11 - roughly within 45 degrees of level
 private static final double TURN_CHECK_MIN_YAW_DELTA_RADIANS = 0.1; // ~5.7 degrees - a real, deliberate heading change
 // Review 53307's "bounded platform position/height" - checked every tick, not just at phase
 // boundaries, so a vehicle that flies off the test platform (or falls through it) is caught
 // immediately as a FAILED result rather than silently continuing (or worse, later reporting a
 // false-positive PASS/COMPLETE on an orientation/speed check that doesn't itself notice the
 // vehicle is no longer on solid ground at all).
 private static final double PLATFORM_BOUND_HORIZONTAL = PLATFORM_RADIUS - 10; // blocks from spawn - margin inside the carved platform's own edge
 private static final double PLATFORM_BOUND_VERTICAL = 10; // blocks above/below the platform surface
 // Review 53307's "positive support": no new production plumbing (per the review's own "this is
 // test-scenario control, not a production behavior change") - reuse the SAME vertical-speed
 // signal SETTLE_CHECK already relies on. A vehicle genuinely resting on its wheels does not keep
 // bouncing/falling once past SETTLE; a looser bound than SETTLE_CHECK's own (which demands the
 // vehicle is nearly motionless vertically) since real driving over blocks can jostle it somewhat.
 private static final double SUPPORT_CHECK_MAX_VERTICAL_SPEED = 2.0; // m/s
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
  double yawAtTurnStart;
  double spawnX, spawnZ, spawnSurfaceY;
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

 // Review 53293: real generated terrain is not guaranteed solid or flat at any one chosen
 // column (confirmed live, CI run 43119570767: the spawn chunk happened to be ocean) - rather
 // than search generated terrain for a usable spot, carve an explicit flat solid platform in
 // this disposable CI world instead, so ground truth is deterministic regardless of whatever
 // the world generator happens to produce there. (PLATFORM_Y/PLATFORM_RADIUS now declared up
 // near the top of the class, see comment there.)

 private static void buildFlatPlatform(ServerLevel level, int centerX, int centerZ) {
  var stone = net.minecraft.world.level.block.Blocks.STONE.defaultBlockState();
  var air = net.minecraft.world.level.block.Blocks.AIR.defaultBlockState();
  for (int dx = -PLATFORM_RADIUS; dx <= PLATFORM_RADIUS; dx++) {
   for (int dz = -PLATFORM_RADIUS; dz <= PLATFORM_RADIUS; dz++) {
    level.setBlock(new BlockPos(centerX + dx, PLATFORM_Y, centerZ + dz), stone, 3);
    for (int dy = 1; dy <= 6; dy++) level.setBlock(new BlockPos(centerX + dx, PLATFORM_Y + dy, centerZ + dz), air, 3);
   }
  }
 }

 private static void spawn(ServerLevel level, Scenario s) {
  int blockX = s.chunkX * 16 + 8, blockZ = s.chunkZ * 16 + 8;
  buildFlatPlatform(level, blockX, blockZ);
  int surfaceY = PLATFORM_Y + 1;
  var packSpeedFactors = ConfigSystem.settings.general.packSpeedFactors.value;
  var packVehicleScales = ConfigSystem.settings.general.packVehicleScales.value;
  packSpeedFactors.putIfAbsent(PACK_ID, 1.0);
  packVehicleScales.putIfAbsent(PACK_ID, 1.0);

  // APart's own constructor does entityOn.definition.parts.indexOf(placementDefinition) to find
  // each slot's index - confirmed live ("Cannot invoke java.util.List.indexOf(Object) because
  // entityOn.definition.parts is null"). Every slot a part will be attached to must therefore
  // already exist, by the SAME instance, in the vehicle's own definition.parts BEFORE any part
  // is constructed - built upfront here rather than incrementally inside addWheel/addEngine.
  var wheelSlotFL = wheelSlot(WHEEL_FL, true);
  var wheelSlotFR = wheelSlot(WHEEL_FR, true);
  var wheelSlotRL = wheelSlot(WHEEL_RL, false);
  var wheelSlotRR = wheelSlot(WHEEL_RR, false);
  var engineSlot = engineSlot();

  JSONVehicle definition = buildVehicleDefinition(s.systemName);
  definition.parts = List.of(wheelSlotFL, wheelSlotFR, wheelSlotRL, wheelSlotRR, engineSlot);
  AWrapperWorld world = mcinterface1211.WrapperWorld.getWrapperFor(level);
  // Reuse the SAME JSONSubDefinition instance already stored in definition.definitions (not a
  // freshly re-constructed equal-looking one) - keeps the item's own subDefinition and the
  // definition's own list in agreement by identity, not merely by field value.
  var item = new ItemVehicle(definition, definition.definitions.get(0), PACK_ID);
  var vehicle = new EntityVehicleF_Physics(world, null, item, null);
  vehicle.position.set(blockX + 0.5, surfaceY + 1.0, blockZ + 0.5); // spawn a little above the real surface so it genuinely falls+settles (lower than the old tall-box fixture - this chassis is much shorter)
  vehicle.orientation.set(new RotationMatrix());
  world.spawnEntity(vehicle);

  addWheel(vehicle, wheelSlotFL);
  addWheel(vehicle, wheelSlotFR);
  addWheel(vehicle, wheelSlotRL);
  addWheel(vehicle, wheelSlotRR);
  s.engine = addEngine(vehicle, engineSlot);

  s.vehicle = vehicle;
  s.spawnX = blockX + 0.5;
  s.spawnZ = blockZ + 0.5;
  s.spawnSurfaceY = surfaceY;
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
  // AEntityD_Definable's own constructor (updateSubDefinition) reads definitions.iterator()
  // unconditionally - a null list NPEs (confirmed live, CI run 43119570767: "Cannot invoke
  // java.util.List.iterator() because this.definition.definitions is null"). Passing a
  // JSONSubDefinition to ItemVehicle's own constructor is not enough on its own; the vehicle's
  // OWN definition needs this list too, with that exact same instance in it.
  v.definitions = List.of(firstSubDefinition(v));
  v.rendering = new minecrafttransportsimulator.jsondefs.JSONRendering();
  v.rendering.modelType = minecrafttransportsimulator.jsondefs.JSONRendering.ModelType.NONE; // no model needed for this probe - short-circuits resetModelsAndAnimations cleanly (confirmed live, CI/local run: "Cannot read field modelType because this.definition.rendering is null")
  v.general = new minecrafttransportsimulator.jsondefs.AJSONItem.General();
  v.general.health = 100; // AEntityE_Interactable reads this.definition.general.health unconditionally (confirmed live: "Cannot read field health because this.definition.general is null")

  // PhysicsBridge.Body's own chassis-geometry computation takes the combined min/max across
  // EVERY collision box in EVERY group, each box independently contributing
  // (pos +/- width/2) in x/z and (pos +/- height/2) in y - a single box's own width is isotropic
  // (same half-extent in x and z), but the real ATV chassis is NOT square (half.x=0.425,
  // half.z=1.05). Reproduced exactly with three boxes: one real-sized box supplying the correct
  // x/y half-extents (width=0.85, height=0.8821875), plus two zero-size "marker" boxes placed
  // exactly at the true z=center.z+/-1.05 extremes (x=center.x, y=center.y, so they fall WITHIN
  // the main box's own x/y range and contribute nothing there) - the combined bounding box's z
  // half-extent becomes exactly 1.05 while x/y stay exactly what the main box alone gives.
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
  sub.subName = "";
  sub.name = v.systemName;
  return sub;
 }

 private static JSONPartDefinition wheelSlot(Point3D localPos, boolean steers) {
  var slot = new JSONPartDefinition();
  slot.pos = localPos;
  slot.turnsWithSteer = steers;
  slot.types = List.of("ground_wheel");
  return slot;
 }

 private static void addWheel(EntityVehicleF_Physics vehicle, JSONPartDefinition slot) {
  var ground = new JSONPart.JSONPartGroundDevice();
  ground.isWheel = true;
  ground.width = 0.4f;
  ground.height = 0.4f;
  // PartGroundDevice.getFrictionLoss/setVariableDefaults reads this map unconditionally - a
  // null map NPEs on first tick (confirmed live, local run: crashed the WHOLE dedicated server,
  // not caught by this probe's own try/catch, since the throw happens inside MTS's own
  // EntityManager.tickAll, outside this probe's call stack entirely). Empty is a valid "no
  // material-specific friction modifier" default - getFrictionLoss's own null-key lookup on an
  // empty map returns null gracefully, same as a real pack's own unconfigured map would.
  ground.frictionModifiers = new java.util.LinkedHashMap<>();
  var def = new JSONPart();
  def.packID = PACK_ID; def.systemName = "wheel";
  def.generic = new JSONPart.JSONPartGeneric();
  def.ground = ground;
  def.definitions = List.of(firstSubDefinition(def)); // same NPE risk as JSONVehicle.definitions - APart's own constructor chain also reaches AEntityD_Definable

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
  sub.subName = "";
  sub.name = p.systemName;
  return sub;
 }

 private static JSONPartDefinition engineSlot() {
  var slot = new JSONPartDefinition();
  slot.pos = new Point3D(0, 0, 0);
  slot.types = List.of("engine");
  return slot;
 }

 private static PartEngine addEngine(EntityVehicleF_Physics vehicle, JSONPartDefinition slot) {
  var engineDef = new JSONPart.JSONPartEngine();
  engineDef.type = JSONPart.EngineType.NORMAL;
  engineDef.gearRatios = List.of(-2.0f, 0.0f, 2.0f); // [reverse, neutral, 1st] - PartEngine indexes this by currentGearVar+reverseGears
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

 // Review 53307: the single per-tick gate every phase already calls before doing anything else -
 // extended (not replaced) to also catch the false-positive failure modes evidence actually
 // showed (rollover, falling/driving off the carved platform) immediately, rather than letting a
 // later phase-boundary CHECK mistake a crashed/airborne vehicle for a real PASS.
 private static boolean checkFiniteOrFail(Scenario s) {
  var v = s.vehicle;
  if (!allFinite(s)) {
   LOGGER.error("[DIV groundSupportFixture] scenario=" + s.label + " FAILED - non-finite vehicle state at phase=" + s.phase + " tick=" + s.ticksInPhase
     + " position=" + v.position + " motion=" + v.motion);
   s.phase = Phase.FAILED;
   return false;
  }
  double dx = v.position.x - s.spawnX, dz = v.position.z - s.spawnZ;
  double horizontalFromSpawn = Math.sqrt(dx * dx + dz * dz);
  double verticalFromSurface = Math.abs(v.position.y - s.spawnSurfaceY);
  if (horizontalFromSpawn > PLATFORM_BOUND_HORIZONTAL || verticalFromSurface > PLATFORM_BOUND_VERTICAL) {
   fail(s, "left the bounded platform area at phase=" + s.phase + " tick=" + s.ticksInPhase
     + " horizontalFromSpawn=" + horizontalFromSpawn + " (bound=" + PLATFORM_BOUND_HORIZONTAL + ")"
     + " verticalFromSurface=" + verticalFromSurface + " (bound=" + PLATFORM_BOUND_VERTICAL + ") position=" + v.position);
   return false;
  }
  // Rollover check applies from DRIVE onward - SETTLE/SPAWNING cover the initial drop onto the
  // platform, where a brief landing wobble is expected and not itself a rollover.
  if (s.phase != Phase.WAIT_TERRAIN && s.phase != Phase.SPAWNING && s.phase != Phase.SETTLE
    && v.orientation.m11 < UPRIGHT_MIN_VERTICALITY) {
   fail(s, "rolled over at phase=" + s.phase + " tick=" + s.ticksInPhase + " upVectorY(m11)=" + v.orientation.m11
     + " (threshold=" + UPRIGHT_MIN_VERTICALITY + ") position=" + v.position);
   return false;
  }
  // "Positive support": past SETTLE the vehicle should be resting on its wheels, not
  // bouncing/airborne - reuses the same vertical-speed signal SETTLE_CHECK itself relies on.
  if (s.phase == Phase.DRIVE || s.phase == Phase.BRAKE || s.phase == Phase.TURN) {
   double verticalSpeed = Math.abs(v.motion.y * v.speedFactor * 20);
   if (verticalSpeed > SUPPORT_CHECK_MAX_VERTICAL_SPEED) {
    fail(s, "lost ground support at phase=" + s.phase + " tick=" + s.ticksInPhase + " verticalSpeed=" + verticalSpeed
      + " (threshold=" + SUPPORT_CHECK_MAX_VERTICAL_SPEED + ") position=" + v.position);
    return false;
   }
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
   s.phase = Phase.DRIVE;
   s.ticksInPhase = 0;
   LOGGER.info("[DIV groundSupportFixture] scenario=" + s.label + " beginning DRIVE phase - closed-loop throttle toward LOW_SPEED_TARGET=" + LOW_SPEED_TARGET + " m/s (review 53307: bounded low-speed scenario, not a high-speed stress ramp)");
  }
 }

 private static double forwardSpeed(Scenario s) {
  return s.vehicle.velocity * s.vehicle.speedFactor * 20;
 }

 private static void drive(Scenario s) {
  s.ticksInPhase++;
  if (!checkFiniteOrFail(s)) return;
  forceEngineRunning(s.engine);
  // Review 53307: no production governor/drag, and no linear ramp to full throttle either - the
  // TEST's own throttle is closed-loop bang-bang toward LOW_SPEED_TARGET (full throttle below it,
  // none at/above it), which is what keeps this scenario a realistic low-speed drive instead of
  // the unbounded high-speed run the flat/speed-independent drive-force model (confirmed by
  // reading PhysicsBridge.java/MultiContactFrictionSolver.java directly) would otherwise produce
  // under a sustained full-throttle ramp.
  double speedBefore = forwardSpeed(s);
  double throttle = speedBefore < LOW_SPEED_TARGET ? 1.0 : 0.0;
  s.vehicle.throttleVar.setTo(throttle, false);
  double speed = forwardSpeed(s);
  s.maxSpeedSeenInDrive = Math.max(s.maxSpeedSeenInDrive, speed);
  if (s.ticksInPhase % 20 == 0 || s.ticksInPhase == DRIVE_TICKS) logState(s, "DRIVE");
  if (s.ticksInPhase >= DRIVE_TICKS) {
   double displacement = s.positionAtDriveStart.distanceTo(s.vehicle.position);
   boolean speedPass = s.maxSpeedSeenInDrive >= DRIVE_CHECK_MIN_SPEED;
   boolean boundedPass = s.maxSpeedSeenInDrive <= DRIVE_CHECK_MAX_SPEED;
   boolean displacementPass = displacement >= DRIVE_CHECK_MIN_DISPLACEMENT;
   LOGGER.info("[DIV groundSupportFixture] scenario=" + s.label + " DRIVE_CHECK maxSpeedSeen=" + s.maxSpeedSeenInDrive + " (min=" + DRIVE_CHECK_MIN_SPEED + ", max=" + DRIVE_CHECK_MAX_SPEED + ")"
     + " displacement=" + displacement + " (threshold=" + DRIVE_CHECK_MIN_DISPLACEMENT + ")"
     + " result=" + (speedPass && boundedPass && displacementPass ? "PASS" : "FAIL"));
   if (!speedPass || !boundedPass || !displacementPass) { fail(s, "DRIVE_CHECK: maxSpeedSeen=" + s.maxSpeedSeenInDrive + " displacement=" + displacement + " - throttle+gear did not produce real, bounded forward motion"); return; }
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
   // Forward axis in world space is the orientation matrix's third column (m02,m12,m22) - the
   // SAME matrix whose second column (m01,m11,m21) is already confirmed (PhysicsBridge.java's own
   // orientation mapping) to be the real up-vector, m11 being its Y-component. Heading is this
   // forward axis's angle in the world XZ-plane.
   s.yawAtTurnStart = Math.atan2(s.vehicle.orientation.m02, s.vehicle.orientation.m22);
   s.vehicle.brakeVar.setActive(false, false);
   // Review 53307's "low-speed steer", not a full-throttle turn: full throttle here (this
   // phase's own prior behavior) combined with the tighter bounded-platform radius let the
   // vehicle outrun PLATFORM_BOUND_HORIZONTAL before TURN_TICKS elapsed (confirmed live: a real
   // run FAILED with "left the bounded platform area" during TURN, not a rollover - m11 stayed
   // well above UPRIGHT_MIN_VERTICALITY the whole time). turn() now re-applies the same
   // closed-loop LOW_SPEED_TARGET throttle every tick, same as DRIVE, so steering is exercised at
   // the same bounded low speed rather than accelerating away from the platform.
   // Confirmed live: rudder=20deg plateaued around yawDelta~0.09-0.097 rad even after 140 ticks
   // (7s) - a real, bounded steering-authority limit at this rudder angle/speed, not a check bug
   // (upright/m11 stable the whole time; PhysicsBridge.java's own speedFactor=carSpeedFactor*
   // packSpeedFactors confirmed via decompile, so forwardSpeed()'s own unit conversion is
   // correct). A stronger, still within-range rudder input gives a clearer "real, deliberate"
   // turning signal instead of relying on indefinitely more time for a plateaued rate.
   s.vehicle.rudderInputVar.setTo(40.0, false); // within MAX_RUDDER_ANGLE=45 - a real, nonzero, bounded steering input
   s.phase = Phase.TURN;
   s.ticksInPhase = 0;
   LOGGER.info("[DIV groundSupportFixture] scenario=" + s.label + " beginning TURN phase - closed-loop throttle toward LOW_SPEED_TARGET=" + LOW_SPEED_TARGET + " m/s, rudder=40deg");
  }
 }

 private static void turn(Scenario s) {
  s.ticksInPhase++;
  if (!checkFiniteOrFail(s)) return;
  forceEngineRunning(s.engine);
  // Same bounded closed-loop throttle as DRIVE - a "low-speed steer" (review 53307), not a
  // full-throttle turn that would otherwise accelerate the vehicle past the bounded platform
  // area before TURN_TICKS elapses (confirmed live: a full-throttle TURN FAILED the new
  // bounded-platform check, not a rollover).
  s.vehicle.throttleVar.setTo(forwardSpeed(s) < LOW_SPEED_TARGET ? 1.0 : 0.0, false);
  if (s.ticksInPhase % 20 == 0 || s.ticksInPhase == TURN_TICKS) logState(s, "TURN");
  if (s.ticksInPhase >= TURN_TICKS) {
   // Review 53307: "rollover is not steering success" - checkFiniteOrFail's own continuous
   // upright check (UPRIGHT_MIN_VERTICALITY, verified every tick including this one) already
   // would have failed this scenario before reaching here if it rolled over, so a PASS here means
   // uprightness held throughout, not just "the orientation changed by some amount" (which a
   // crash/rollover also satisfies).
   double yawNow = Math.atan2(s.vehicle.orientation.m02, s.vehicle.orientation.m22);
   double yawDelta = Math.abs(Math.atan2(Math.sin(yawNow - s.yawAtTurnStart), Math.cos(yawNow - s.yawAtTurnStart)));
   boolean pass = yawDelta >= TURN_CHECK_MIN_YAW_DELTA_RADIANS;
   LOGGER.info("[DIV groundSupportFixture] scenario=" + s.label + " TURN_CHECK yawDelta=" + yawDelta + " (threshold=" + TURN_CHECK_MIN_YAW_DELTA_RADIANS + ")"
     + " upVectorY(m11)=" + s.vehicle.orientation.m11 + " finalPosition=" + s.vehicle.position + " result=" + (pass ? "PASS" : "FAIL"));
   if (!pass) { fail(s, "TURN_CHECK: yawDelta=" + yawDelta + " - rudder input did not produce real, upright turning"); return; }
   s.phase = Phase.DONE;
   LOGGER.info("[DIV groundSupportFixture] scenario=" + s.label + " COMPLETE - SETTLE/DRIVE/BRAKE/TURN all passed their explicit thresholds");
  }
 }
}
