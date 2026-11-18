package io.github.brooswitminecraft.div.experimental;
import com.mojang.logging.LogUtils;
import io.github.brooswitminecraft.div.PhysicsBridge;
import minecrafttransportsimulator.baseclasses.Point3D;
import minecrafttransportsimulator.baseclasses.RotationMatrix;
import minecrafttransportsimulator.baseclasses.TowingConnection;
import minecrafttransportsimulator.entities.instances.EntityVehicleF_Physics;
import minecrafttransportsimulator.items.instances.ItemPartGroundDevice;
import minecrafttransportsimulator.items.instances.ItemVehicle;
import minecrafttransportsimulator.jsondefs.JSONCollisionBox;
import minecrafttransportsimulator.jsondefs.JSONCollisionGroup;
import minecrafttransportsimulator.jsondefs.JSONConnection;
import minecrafttransportsimulator.jsondefs.JSONConnectionGroup;
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
 * MINECRAFT-545/547 (John's comment 54856, explicit implementation authorization following the
 * design analysis in comments 54839/54844/54849): a bounded, opt-in, CI/isolated-host-only
 * fixture for the missing-index fallback gap in {@code PhysicsBridge.releaseOrphanedTowingConnections}'s
 * own fallback path ({@code fallbackClearConnection} - see that method's own doc for the
 * confirmed-unsupported {@code connectionGroupsIndexesInUse} leak this fixture probes).
 *
 * <p><b>Method, exactly as traced and authorized (54839/54844/54849/54856), not guessed:</b>
 * <ol>
 *  <li><b>Fault injection</b> (explicitly labeled as such - this is a deliberately injected
 *  inconsistent state, NOT a claim about how the real asymmetry naturally arises - see
 *  VERIFICATION.md "Per 54839" for why plain anchor removal alone does not reach this path):
 *  after a normal connection forms between {@code towingA}/{@code towedA}, capture the exact
 *  {@code TowingConnection} object, remove ONLY it from {@code towingA}'s own PUBLIC
 *  {@code towingConnections} list (confirmed genuinely public, not reflection - VERIFICATION.md
 *  "Per 54844"), and assert BOTH {@code towingA.towingConnections.indexOf(connection) == -1} AND
 *  {@code towedA.towedByConnection == connection} (same reference) BEFORE invalidating the
 *  anchor via the same supported lifecycle method ({@code towingA.remove()}) the existing
 *  {@code TOWING_UNAVAILABLE} phase already uses. The production sweep
 *  ({@code PhysicsBridge.releaseOrphanedTowingConnections()}, already globally wired on {@code
 *  ServerTickEvent.Pre}) then runs automatically, with no special invocation from this fixture.</li>
 *  <li><b>Fallback-entry evidence, a real observation, not an inference</b> (corrected per
 *  54862, then corrected AGAIN per 54870): a prior version reasoned "indexOf==-1 before removal,
 *  so this null MUST have come from the fallback" - an INFERENCE, not a direct observation.
 *  54862's own fix was a real observation, but an always-on, unbounded, never-released map - a
 *  genuine (if slow) memory leak. Now uses {@code PhysicsBridge.registerFallbackObserver}/
 *  {@code unregisterFallbackObserver} - bounded, opt-in, released on every terminal/error path,
 *  keyed to BOTH the exact vehicle UUID AND the exact connection object reference (a mismatched
 *  connection on the registered vehicle is counted separately, never silently folded into a
 *  match). Only an actual observed match confirms fallback-entry; anything else is a genuine
 *  anomaly and fails the fixture, never silently accepted.</li>
 *  <li><b>Matched positive control</b> (54849/54856's own explicit requirement - absence of a
 *  new connection alone does NOT prove the leak caused it, since {@code checkIfTrailerCanConnect}
 *  has several other rejection outcomes producing the identical signal): an independent
 *  {@code towingB}/{@code towedB} pair, IDENTICAL group/geometry, connected and then PROPERLY
 *  disconnected via the real {@code disconnectTrailer} (the ACTUAL computed index, never
 *  hardcoded - 54862's own finding) - genuinely freeing {@code connectionGroupsIndexesInUse} on
 *  both sides. A FRESH {@code towingD} entity (same hitch definition, never reused from
 *  elsewhere) then drives a reconnect request through the REAL normal selection path -
 *  {@code towingD.towingConnectionVar.setTo(...)}, never calling {@code connectTrailer} directly,
 *  which would bypass the very {@code ALREADY_TOWED} check this fixture depends on (54844's own
 *  finding). Eligibility is measured via the REAL anchor-world-point formula
 *  {@code checkIfTrailerCanConnect} itself uses (54870's own correction - a raw body-center
 *  distance is NOT the same thing), and the request's own {@code towingConnectionVar} reset-to-
 *  zero transition is captured as genuine processing evidence (54870's own correction - distinct
 *  from, and not the same as, the rejection reason itself, which remains unobservable). A request
 *  that was never actually consumed escalates straight to {@code INCONCLUSIVE_REQUEST_NOT_CONSUMED};
 *  a consumed request that still fails to form a new connection escalates to
 *  {@code INCONCLUSIVE_CONTROL_FAILED} - neither makes any claim about the leak probe's own
 *  result, per 54849/54856/54870's explicit instructions.</li>
 *  <li><b>The leak probe itself</b>: a SEPARATE fresh {@code towingC} entity (same definition),
 *  positioned with the SAME relative geometry {@code towingA} originally used, drives the SAME
 *  kind of normal reconnect request at {@code towedA} (whose {@code hookupGroupIndex} bit was
 *  never released by the fallback), with the same real anchor-eligibility measurement and
 *  request-consumption capture as the control. Whether a new connection forms is the behavioral
 *  signal - explicitly logged as a signal, not direct proof of the {@code ALREADY_TOWED} reason
 *  specifically (54844/54849's own distinction - the real rejection reason is delivered only via
 *  a client-targeted chat packet, unobservable in this player-less fixture).</li>
 * </ol>
 *
 * <p><b>Explicitly NOT done, per 54844/54849/54856's own instructions</b>: no restore-then-
 * disconnect cleanup is relied on for anything this fixture's own verdict depends on - every
 * fixture-owned entity is simply despawned at the end (independently, via the pure
 * {@link #runAllIndependently} orchestration - 54870's own correction, since the first version
 * aborted on the FIRST entity whose own removal threw, silently skipping every entity after it),
 * letting the leaked bookkeeping disappear with the garbage-collected Java objects rather than
 * attempting any unvalidated repair. No client/bot is simulated and no new diagnostic Mixin is
 * added - the chat-packet-only rejection reason remains a separate, unverified, explicitly-named
 * gap, not addressed by this fixture.
 *
 * <p>Opt-in via {@code -Ddiv.experimentalMissingIndexFallbackProbe=true} - OFF by default, never
 * runs during normal play, never touches any real vehicle, Zippy, or world content. Runs ONCE
 * per server lifetime, for a bounded window, then stops. Same {@code massKg} config-override
 * requirement as {@code BuoyantHitchProbe} (seeded for every one of this fixture's own four
 * distinct vehicle keys - {@code towingA}/{@code towingB} share {@code divtest:missingindex-towing-shared};
 * {@code towedA}/{@code towedB} share {@code divtest:missingindex-towed-shared}; the two fresh
 * reconnect entities each get their own, {@code divtest:missingindex-towingC} and
 * {@code divtest:missingindex-towingD}).
 */
public final class MissingIndexFallbackProbe {
 private MissingIndexFallbackProbe() {}
 private static final boolean ENABLED = Boolean.getBoolean("div.experimentalMissingIndexFallbackProbe");
 private static final String PACK_ID = "divtest";
 private static final int CHUNK_X = 1300, CHUNK_Z = 1300; // far clear of every other probe's own fixed coordinates
 private static final int MAX_TERRAIN_WAIT_TICKS = 200;
 private static final int CHUNK_RADIUS = 3;
 private static final int PLATFORM_Y = 100;
 private static final int POOL_RADIUS = 10;
 private static final int WATER_DEPTH = 5;
 private static final int SETTLE_TICKS = 40; // 2s - light settle only, no anchor-convergence measurement needed for this fixture
 private static final int FALLBACK_WAIT_TICKS = 60; // 3s - the Pre-tick sweep runs every tick; this is generous headroom
 private static final int RECONNECT_WAIT_TICKS = 60; // 3s
 private static final double HALF_EXTENT_X = 0.6, HALF_HEIGHT_Y = 0.3, HALF_EXTENT_Z = 1.0;
 private static final Point3D CHASSIS_CENTER = new Point3D(0, 0.3, 0);
 private static final Point3D FLOAT_LEFT = new Point3D(-0.5, -0.2, 0);
 private static final Point3D FLOAT_RIGHT = new Point3D(0.5, -0.2, 0);
 private static final Point3D HITCH_POS = new Point3D(0, 0, -1.0);
 private static final Point3D HOOKUP_POS = new Point3D(0, 0, 1.0);
 private static final double SPAWN_SEPARATION = 3.5; // towing spawns this far +Z of towed, matching BuoyantHitchProbe's own proven geometry
 private static final double POOL_SEPARATION = 30; // blocks between the leak pool and the control pool
 private static final double PLATFORM_BOUND_HORIZONTAL = 50, PLATFORM_BOUND_VERTICAL = 20;
 private static final Logger LOGGER = LogUtils.getLogger();

 private enum Phase {
  WAIT_TERRAIN, SPAWN, SETTLE, FAULT_INJECT, AWAIT_LEAK_FALLBACK,
  CONTROL_DISCONNECT, CONTROL_RECONNECT_REQUEST, AWAIT_CONTROL_RESULT,
  LEAK_RECONNECT_REQUEST, AWAIT_LEAK_RESULT, DONE, FAILED
 }

 /** MINECRAFT-545/547 (John's comment 54849/54856, corrected per 54870): the pure, Minecraft/MTS-
  * free verdict policy this fixture's own real outcome routes through - trivially unit-testable
  * with plain JUnit, same spirit as {@code LifecycleInvariants.RequiredPhaseVerdict}.
  * <p>CORRECTED (54870): the first version only ever distinguished "a new connection formed" vs.
  * "it didn't," with no way to tell a genuinely-processed-and-rejected request apart from one
  * that was never actually processed at all (e.g. the requesting entity somehow never ticked).
  * 54870 is correct that the real {@code towingConnectionVar} resetting from its own nonzero
  * request value back to {@code 0} the same tick it is consumed IS real processing evidence
  * (though never the rejection REASON, which remains the separately-unobservable chat packet) -
  * {@code REQUEST_NOT_CONSUMED} (the var never reset within the bounded window) is now its own
  * distinct outcome, both for the control and the leak probe, and always escalates to its own
  * INCONCLUSIVE verdict rather than being folded into "no new connection" as if it were a
  * meaningful rejection signal. */
 public enum ReconnectOutcome { NEW_CONNECTION_OBSERVED, NO_NEW_CONNECTION_REQUEST_CONSUMED, REQUEST_NOT_CONSUMED }
 public enum LeakVerdict { LEAK_CONFIRMED, LEAK_NOT_OBSERVED, INCONCLUSIVE_CONTROL_FAILED, INCONCLUSIVE_REQUEST_NOT_CONSUMED }

 /** 54849/54870: a request that was never actually consumed (on EITHER side) makes BOTH this
  * fixture's own methodology and its target unverifiable - escalates ahead of every other check.
  * A control that was genuinely processed but still failed to form a new connection makes the
  * leak probe's own result meaningless, per 54849's own "no cause claim" instruction - checked
  * next. Only once the control is confirmed genuinely successful does the leak probe's own
  * (also confirmed-processed) outcome get to decide LEAK_CONFIRMED vs LEAK_NOT_OBSERVED. */
 public static LeakVerdict classifyLeakVerdict(ReconnectOutcome controlOutcome, ReconnectOutcome leakOutcome) {
  if (controlOutcome == ReconnectOutcome.REQUEST_NOT_CONSUMED || leakOutcome == ReconnectOutcome.REQUEST_NOT_CONSUMED) {
   return LeakVerdict.INCONCLUSIVE_REQUEST_NOT_CONSUMED;
  }
  if (controlOutcome != ReconnectOutcome.NEW_CONNECTION_OBSERVED) {
   return LeakVerdict.INCONCLUSIVE_CONTROL_FAILED;
  }
  return leakOutcome == ReconnectOutcome.NO_NEW_CONNECTION_REQUEST_CONSUMED ? LeakVerdict.LEAK_CONFIRMED : LeakVerdict.LEAK_NOT_OBSERVED;
 }

 /** MINECRAFT-545/547 (John's comment 54870): the pure, generic cleanup orchestration every
  * terminal path routes through - a {@code Runnable} action under a human-readable {@code tag},
  * attempted independently of every other action's own success or failure. No MTS dependency at
  * all - directly unit-testable with synthetic actions that throw on command. */
 public record CleanupAction(String tag, Runnable action) {}
 public enum CleanupOutcome { ALL_SUCCEEDED, SOME_FAILED }

 /** Attempts EVERY action in {@code actions}, in order, regardless of whether an earlier one
  * threw - a single aborted action must never prevent the rest from being attempted. Every
  * individual failure is reported to {@code onFailure} (tag + the exception) for logging/receipt
  * purposes; the aggregate {@link CleanupOutcome} reflects ALL actions, not merely the first one
  * tried, and is never reported as {@code ALL_SUCCEEDED} unless literally every action completed
  * without throwing - no premature success. */
 public static CleanupOutcome runAllIndependently(java.util.List<CleanupAction> actions,
     java.util.function.BiConsumer<String, RuntimeException> onFailure) {
  boolean allSucceeded = true;
  for (var action : actions) {
   try {
    action.action().run();
   } catch (RuntimeException ex) {
    allSucceeded = false;
    onFailure.accept(action.tag(), ex);
   }
  }
  return allSucceeded ? CleanupOutcome.ALL_SUCCEEDED : CleanupOutcome.SOME_FAILED;
 }

 private static Phase phase = Phase.WAIT_TERRAIN;
 private static int ticksInPhase = 0, terrainWaitTicksElapsed = 0;
 private static boolean configured = false;
 private static double spawnX, spawnZ, spawnSurfaceY;
 private static EntityVehicleF_Physics towingA, towedA, towingB, towedB, towingC, towingD;
 private static JSONVehicle towingDefinition, towedDefinition;
 private static TowingConnection injectedConnection;

 /** MINECRAFT-545/547 (John's comment 54870): every entity this fixture ever successfully
  * constructs is appended HERE immediately - not only once assigned to its own named field - so
  * a failure partway through {@link #spawnHull} (e.g. {@code world.spawnEntity} or
  * {@code addFloat} throwing AFTER the real {@code new EntityVehicleF_Physics(...)} already
  * succeeded) still leaves this fixture able to find and clean it up. */
 private static final java.util.List<EntityVehicleF_Physics> allSpawnedEntities = new java.util.ArrayList<>();
 /** Every chunk coordinate actually force-loaded, appended immediately after each individual
  * successful {@code setChunkForced(...)} call - never only decided at the end of the whole
  * loop, which could otherwise leave already-forced chunks untracked if the loop itself threw
  * partway through. */
 private static final java.util.List<int[]> forcedChunkCoords = new java.util.ArrayList<>();
 private static boolean addedPackSpeedFactor = false, addedPackVehicleScale = false;

 private static boolean cleanedUp = false, cleanupFailed = false;

 public static void maybeRun(ServerLevel level) {
  if (!ENABLED) return;
  if (!configured) {
   // MINECRAFT-545/547 (John's comment 54862): the terrain request previously ran OUTSIDE any
   // try/catch - an exception here would have propagated uncaught, crashing the whole tick
   // listener instead of failing this fixture closed like every other phase does.
   try {
    configured = true;
    requestChunkGrid(level);
    LOGGER.info("[DIV missingIndexFallbackProbe] requested terrain chunk grid");
   } catch (RuntimeException ex) {
    LOGGER.error("[DIV missingIndexFallbackProbe] FAILURE_DETECTED - unexpected exception requesting terrain chunk grid", ex);
    phase = Phase.FAILED;
   }
   finishTickIfTerminal(level);
   return;
  }
  try {
   switch (phase) {
    case WAIT_TERRAIN -> waitTerrain(level);
    case SPAWN -> spawn(level);
    case SETTLE -> settle();
    case FAULT_INJECT -> faultInject();
    case AWAIT_LEAK_FALLBACK -> awaitLeakFallback();
    case CONTROL_DISCONNECT -> controlDisconnect();
    case CONTROL_RECONNECT_REQUEST -> controlReconnectRequest(level);
    case AWAIT_CONTROL_RESULT -> awaitControlResult();
    case LEAK_RECONNECT_REQUEST -> leakReconnectRequest(level);
    case AWAIT_LEAK_RESULT -> awaitLeakResult();
    case DONE, FAILED -> {}
   }
  } catch (RuntimeException ex) {
   LOGGER.error("[DIV missingIndexFallbackProbe] FAILURE_DETECTED - unexpected exception in phase " + phase, ex);
   phase = Phase.FAILED;
  }
  finishTickIfTerminal(level);
 }

 /** MINECRAFT-545/547 (John's comment 54862): previously, only the FAILED/exception path's own
  * chunk-grid release ran on every terminal path - entity cleanup (towedA/towingB/towedB/
  * towingC/towingD, and towingA if a failure happened before FAULT_INJECT ever invalidated it)
  * only ever ran on the ONE successful completion path inside awaitLeakResult, meaning any
  * FAILED exit (bounds/non-finite/timeout/exception, at any phase) leaked every fixture-owned
  * entity into the world forever. This is now the SINGLE centralized, idempotent teardown point
  * for every terminal path - DONE or FAILED, no matter how it was reached - run exactly once
  * (guarded by {@code cleanedUp}), with its own failure isolated (a cleanup exception is caught
  * and reported as {@code CLEANUP_INCOMPLETE}, never allowed to mask or be masked by the
  * experiment's own verdict). The chunk-grid release is deliberately ordered AFTER entity
  * cleanup - entities should not briefly exist in now-unforced, potentially-unloading chunks. */
 /** MINECRAFT-545/547 (John's comment 54870): "Publish sole terminal outcome after ALL
  * entity/chunk cleanup attempts, explicit cleanup-incomplete if any fail." Cleanup (entities +
  * chunks + config + observer, via {@link #runAllIndependently}) runs to completion - every
  * tracked resource attempted independently - BEFORE the terminal banner is ever emitted, and
  * the banner's own {@code CLEANUP_INCOMPLETE} suffix reflects the TRUE aggregate outcome across
  * every one of those attempts, not just whether a single generic try/catch happened to catch
  * something. */
 private static void finishTickIfTerminal(ServerLevel level) {
  if (phase != Phase.DONE && phase != Phase.FAILED) return;
  boolean firstTerminalTick = !cleanedUp;
  if (!cleanedUp) {
   cleanedUp = true;
   var outcome = runAllIndependently(buildCleanupActions(level),
     (tag, ex) -> LOGGER.error("[DIV missingIndexFallbackProbe] cleanup action FAILED tag=" + tag, ex));
   cleanupFailed = outcome == CleanupOutcome.SOME_FAILED;
   LOGGER.info("[DIV missingIndexFallbackProbe] cleanup outcome=" + outcome
     + " - every tracked resource (entities/chunks/config/observer) was attempted independently, regardless of any earlier failure");
  }
  // MINECRAFT-545/547 (John's comment 54862): "Terminal success/result must follow completed
  // cleanup or explicit CLEANUP_INCOMPLETE - not emit matching COMPLETE/FAILED marker early and
  // let wrapper stop before cleanup." The actual terminal banner is emitted HERE, after the
  // cleanup attempt immediately above - never inside awaitLeakResult/any FAILED call site
  // itself. Also separates EXPERIMENT COMPLETION (did the sequence run without crashing) from
  // the CORRECTNESS VERDICT (LEAK_CONFIRMED/LEAK_NOT_OBSERVED/INCONCLUSIVE_CONTROL_FAILED) -
  // never a single blanket "COMPLETE" covering every verdict value, so a log/wrapper consumer
  // cannot mistake "the experiment ran" for "a defect either was or was not observed."
  if (firstTerminalTick) {
   String cleanupSuffix = cleanupFailed ? " CLEANUP_INCOMPLETE" : "";
   if (phase == Phase.DONE) {
    LOGGER.info("[DIV missingIndexFallbackProbe] COMPLETE verdict=" + finalVerdict + cleanupSuffix
      + " (see VERIFICATION.md for this run's own precise evidence/limits, not restated here)");
   } else {
    LOGGER.error("[DIV missingIndexFallbackProbe] FAILED" + cleanupSuffix + " - see the specific FAILED line above for the real cause");
   }
  }
 }

 /** MINECRAFT-545/547 (John's comment 54870): builds the FULL list of independently-attempted
  * cleanup actions from every resource actually tracked as acquired - every spawned entity
  * (via {@link #allSpawnedEntities}, not just the named fields, so a partial-construction
  * failure is still covered), every individually-forced chunk, both {@code ConfigSystem} map
  * entries this fixture may have added (restoring the shared map to its own original state, not
  * merely leaving injected keys behind forever), and the fallback observer registration. Each
  * action is independent - {@link #runAllIndependently} attempts every one regardless of
  * whether an earlier one threw. */
 private static java.util.List<CleanupAction> buildCleanupActions(ServerLevel level) {
  var actions = new java.util.ArrayList<CleanupAction>();
  for (var entity : allSpawnedEntities) {
   actions.add(new CleanupAction("remove entity " + entity.uniqueUUID, () -> { if (entity.isValid) entity.remove(); }));
  }
  for (var coord : forcedChunkCoords) {
   actions.add(new CleanupAction("unforce chunk " + coord[0] + "," + coord[1], () -> level.setChunkForced(coord[0], coord[1], false)));
  }
  if (addedPackSpeedFactor) {
   actions.add(new CleanupAction("restore packSpeedFactors", () -> ConfigSystem.settings.general.packSpeedFactors.value.remove(PACK_ID)));
  }
  if (addedPackVehicleScale) {
   actions.add(new CleanupAction("restore packVehicleScales", () -> ConfigSystem.settings.general.packVehicleScales.value.remove(PACK_ID)));
  }
  actions.add(new CleanupAction("unregister fallback observer", () -> PhysicsBridge.unregisterFallbackObserver(fallbackObserverHandle)));
  return actions;
 }

 private static void requestChunkGrid(ServerLevel level) {
  // MINECRAFT-545/547 (John's comment 54870): "Partial request loop only sets forced flag at
  // end." Each chunk is now appended to forcedChunkCoords IMMEDIATELY after its own successful
  // setChunkForced call - if this loop were to throw partway through, every chunk ALREADY
  // forced is still tracked and will still be released during cleanup, rather than being lost
  // because the flag marking "any chunks forced at all" was never reached.
  for (int dx = -CHUNK_RADIUS; dx <= CHUNK_RADIUS; dx++) {
   for (int dz = -CHUNK_RADIUS; dz <= CHUNK_RADIUS; dz++) {
    level.setChunkForced(CHUNK_X + dx, CHUNK_Z + dz, true);
    forcedChunkCoords.add(new int[]{CHUNK_X + dx, CHUNK_Z + dz});
   }
  }
 }

 private static boolean chunkGridLoaded(ServerLevel level) {
  var chunkSource = level.getChunkSource();
  for (int dx = -CHUNK_RADIUS; dx <= CHUNK_RADIUS; dx++) for (int dz = -CHUNK_RADIUS; dz <= CHUNK_RADIUS; dz++) {
   if (chunkSource.getChunkNow(CHUNK_X + dx, CHUNK_Z + dz) == null) return false;
  }
  return true;
 }

 private static void waitTerrain(ServerLevel level) {
  terrainWaitTicksElapsed++;
  if (!chunkGridLoaded(level)) {
   if (terrainWaitTicksElapsed > MAX_TERRAIN_WAIT_TICKS) {
    LOGGER.error("[DIV missingIndexFallbackProbe] FAILURE_DETECTED - terrain chunk grid never finished loading");
    phase = Phase.FAILED;
   }
   return;
  }
  phase = Phase.SPAWN;
 }

 private static void buildPlatform(ServerLevel level, int centerX, int centerZ) {
  var stone = net.minecraft.world.level.block.Blocks.STONE.defaultBlockState();
  var water = net.minecraft.world.level.block.Blocks.WATER.defaultBlockState();
  var air = net.minecraft.world.level.block.Blocks.AIR.defaultBlockState();
  for (int dx = -POOL_RADIUS; dx <= POOL_RADIUS; dx++) {
   for (int dz = -POOL_RADIUS; dz <= POOL_RADIUS; dz++) {
    level.setBlock(new BlockPos(centerX + dx, PLATFORM_Y, centerZ + dz), stone, 3);
    for (int dy = 1; dy <= WATER_DEPTH; dy++) level.setBlock(new BlockPos(centerX + dx, PLATFORM_Y + dy, centerZ + dz), water, 3);
    for (int dy = WATER_DEPTH + 1; dy <= WATER_DEPTH + 6; dy++) level.setBlock(new BlockPos(centerX + dx, PLATFORM_Y + dy, centerZ + dz), air, 3);
   }
  }
 }

 private static void spawn(ServerLevel level) {
  int blockX = CHUNK_X * 16 + 8, blockZ = CHUNK_Z * 16 + 8;
  buildPlatform(level, blockX, blockZ);
  buildPlatform(level, blockX, blockZ + (int) POOL_SEPARATION);
  double surfaceY = PLATFORM_Y + WATER_DEPTH;
  var packSpeedFactors = ConfigSystem.settings.general.packSpeedFactors.value;
  var packVehicleScales = ConfigSystem.settings.general.packVehicleScales.value;
  // MINECRAFT-545/547 (John's comment 54870): "restore original forced/config state." These are
  // SHARED ConfigSystem maps, not private to this fixture - putIfAbsent's own return value
  // (null means genuinely newly-added) tells us whether to restore the map to its own prior
  // state during cleanup, rather than leaving an injected key behind forever regardless of
  // whether it was already there.
  addedPackSpeedFactor = packSpeedFactors.putIfAbsent(PACK_ID, 1.0) == null;
  addedPackVehicleScale = packVehicleScales.putIfAbsent(PACK_ID, 1.0) == null;

  AWrapperWorld world = mcinterface1211.WrapperWorld.getWrapperFor(level);
  towingDefinition = buildHullDefinition("missingindex-towing-shared", true, false);
  towedDefinition = buildHullDefinition("missingindex-towed-shared", false, true);

  // Leak pool.
  towingA = spawnHull(world, towingDefinition, blockX + 0.5, surfaceY + 0.5, blockZ + 0.5, 0);
  towedA = spawnHull(world, towedDefinition, blockX + 0.5, surfaceY + 0.5, blockZ + 0.5 - SPAWN_SEPARATION, 180);
  var connectionA = new TowingConnection(towingA, 0, 0, towedA, 0, 0);
  towingA.connectTrailer(connectionA, false);

  // Control pool, well clear of the leak pool.
  int controlBlockZ = blockZ + (int) POOL_SEPARATION;
  towingB = spawnHull(world, towingDefinition, blockX + 0.5, surfaceY + 0.5, controlBlockZ + 0.5, 0);
  towedB = spawnHull(world, towedDefinition, blockX + 0.5, surfaceY + 0.5, controlBlockZ + 0.5 - SPAWN_SEPARATION, 180);
  var connectionB = new TowingConnection(towingB, 0, 0, towedB, 0, 0);
  towingB.connectTrailer(connectionB, false);

  spawnX = blockX + 0.5; spawnZ = blockZ + 0.5; spawnSurfaceY = surfaceY;
  phase = Phase.SETTLE; ticksInPhase = 0;
  LOGGER.info("[DIV missingIndexFallbackProbe] spawned leak pair towingA=" + towingA.uniqueUUID + " towedA=" + towedA.uniqueUUID
    + " and control pair towingB=" + towingB.uniqueUUID + " towedB=" + towedB.uniqueUUID + " - beginning SETTLE");
 }

 private static boolean finite(EntityVehicleF_Physics v) {
  double[] values = {v.position.x, v.position.y, v.position.z, v.motion.x, v.motion.y, v.motion.z};
  for (double d : values) if (!Double.isFinite(d)) return false;
  return true;
 }

 private static boolean boundedOrFail(String tag, EntityVehicleF_Physics v) {
  if (v == null || !v.isValid) return true; // already despawned/invalidated on purpose - not a bounds failure
  if (!finite(v)) {
   LOGGER.error("[DIV missingIndexFallbackProbe] FAILURE_DETECTED - non-finite state tag=" + tag + " phase=" + phase + " tick=" + ticksInPhase + " position=" + v.position);
   phase = Phase.FAILED;
   return false;
  }
  double dx = v.position.x - spawnX, dz = v.position.z - spawnZ;
  double horizontal = Math.sqrt(dx * dx + dz * dz), vertical = Math.abs(v.position.y - spawnSurfaceY);
  if (horizontal > PLATFORM_BOUND_HORIZONTAL || vertical > PLATFORM_BOUND_VERTICAL) {
   LOGGER.error("[DIV missingIndexFallbackProbe] FAILURE_DETECTED - left bounded area tag=" + tag + " phase=" + phase + " tick=" + ticksInPhase
     + " horizontal=" + horizontal + " vertical=" + vertical + " position=" + v.position);
   phase = Phase.FAILED;
   return false;
  }
  return true;
 }

 private static void settle() {
  ticksInPhase++;
  if (!boundedOrFail("settle/towingA", towingA) || !boundedOrFail("settle/towedA", towedA)
    || !boundedOrFail("settle/towingB", towingB) || !boundedOrFail("settle/towedB", towedB)) return;
  if (ticksInPhase >= SETTLE_TICKS) {
   if (towedA.towedByConnection == null || towedB.towedByConnection == null) {
    LOGGER.error("[DIV missingIndexFallbackProbe] FAILURE_DETECTED - initial connection never formed on one or both pairs after SETTLE"
      + " towedA.towedByConnection=" + towedA.towedByConnection + " towedB.towedByConnection=" + towedB.towedByConnection);
    phase = Phase.FAILED;
    return;
   }
   LOGGER.info("[DIV missingIndexFallbackProbe] SETTLE PASS - both initial connections confirmed live - beginning FAULT_INJECT");
   phase = Phase.FAULT_INJECT; ticksInPhase = 0;
  }
 }

 private static void faultInject() {
  // MINECRAFT-545/547 (John's comments 54844/54849/54856): deliberately injected inconsistent
  // state - labeled explicitly as FAULT INJECTION throughout this method, never presented as a
  // natural reproduction of the real lifecycle. See this class's own doc for why plain anchor
  // removal alone (the ORIGINAL, now-corrected proposal) does not reach this path.
  injectedConnection = towedA.towedByConnection;
  if (injectedConnection == null || !towingA.towingConnections.contains(injectedConnection)) {
   LOGGER.error("[DIV missingIndexFallbackProbe] FAILURE_DETECTED - pre-injection sanity check failed: injectedConnection=" + injectedConnection
     + " towingA.towingConnections.contains=" + (injectedConnection != null && towingA.towingConnections.contains(injectedConnection)));
   phase = Phase.FAILED;
   return;
  }
  boolean removed = towingA.towingConnections.remove(injectedConnection);
  int indexAfterInjection = towingA.towingConnections.indexOf(injectedConnection);
  boolean sameChildReference = towedA.towedByConnection == injectedConnection;
  LOGGER.info("[DIV missingIndexFallbackProbe] FAULT INJECTION - removed the exact connection object from towingA's own public"
    + " towingConnections list (removed=" + removed + "), leaving towedA.towedByConnection pointed at the SAME object -"
    + " indexAfterInjection=" + indexAfterInjection + " sameChildReference=" + sameChildReference
    + " - this is a DELIBERATELY injected inconsistent state, not a natural reproduction of any real lifecycle.");
  if (!removed || indexAfterInjection != -1 || !sameChildReference) {
   LOGGER.error("[DIV missingIndexFallbackProbe] FAILURE_DETECTED - fault injection did not produce the expected asymmetric state");
   phase = Phase.FAILED;
   return;
  }
  // MINECRAFT-545/547 (John's comments 54862/54870): register the bounded, opt-in observer HERE,
  // keyed to BOTH towedA's own UUID and the EXACT injectedConnection reference, immediately
  // before invalidating the anchor - the baseline this fixture compares against once the sweep
  // has had its chance to run. Unregistered on every terminal/error path via cleanup (see
  // cleanupAllFixtureEntities) - never left registered, no default retained growth.
  fallbackObserverHandle = PhysicsBridge.registerFallbackObserver(towedA.uniqueUUID, injectedConnection);
  LOGGER.info("[DIV missingIndexFallbackProbe] invalidating towingA via the same supported lifecycle method (remove()) the existing"
    + " TOWING_UNAVAILABLE phase already uses");
  towingA.remove();
  phase = Phase.AWAIT_LEAK_FALLBACK; ticksInPhase = 0;
 }

 private static PhysicsBridge.FallbackObserverHandle fallbackObserverHandle;

 private static void awaitLeakFallback() {
  ticksInPhase++;
  if (!boundedOrFail("awaitLeakFallback/towedA", towedA) || !boundedOrFail("awaitLeakFallback/towingB", towingB)
    || !boundedOrFail("awaitLeakFallback/towedB", towedB)) return;
  if (towedA.towedByConnection == null) {
   // MINECRAFT-545/547 (John's comments 54862/54870): the prior version of this check INFERRED
   // fallback-entry from the postcondition alone, then an always-on unbounded counter - 54870
   // is correct that the counter leaked forever. Now checks the bounded, opt-in,
   // identity-matched observer registered in faultInject above - only an actual MATCHED
   // observation (same vehicle UUID AND the same connection reference) confirms fallback-entry;
   // a mismatch or no observation at all is reported as a genuine, surfaced anomaly.
   int matchedCount = PhysicsBridge.debugFallbackObserverMatchedCount(fallbackObserverHandle);
   int mismatchedCount = PhysicsBridge.debugFallbackObserverMismatchedCount(fallbackObserverHandle);
   if (matchedCount > 0) {
    LOGGER.info("[DIV missingIndexFallbackProbe] FALLBACK-ENTRY EVIDENCE CONFIRMED (real, identity-matched observation, not"
      + " inference) - towedA.towedByConnection is null after " + ticksInPhase + " ticks AND the registered observer for"
      + " towedA's own UUID recorded " + matchedCount + " matched invocation(s) of the exact injectedConnection - the"
      + " production fallback callback genuinely ran for this exact vehicle AND connection.");
    phase = Phase.CONTROL_DISCONNECT; ticksInPhase = 0;
    return;
   }
   LOGGER.error("[DIV missingIndexFallbackProbe] FAILURE_DETECTED - towedA.towedByConnection is null, but the registered"
     + " observer recorded matchedCount=" + matchedCount + " mismatchedCount=" + mismatchedCount + " - a genuine anomaly:"
     + " either nothing observed this exact vehicle/connection pair, or something ELSE nulled this field. Treated as a"
     + " failure, never silently accepted as confirmed fallback-entry.");
   phase = Phase.FAILED;
   return;
  }
  if (ticksInPhase >= FALLBACK_WAIT_TICKS) {
   LOGGER.error("[DIV missingIndexFallbackProbe] FAILURE_DETECTED - towedA.towedByConnection never became null within " + FALLBACK_WAIT_TICKS
     + " ticks of invalidating towingA - the orphan was never cleared at all (a more basic defect than the leak this fixture targets)");
   phase = Phase.FAILED;
  }
 }

 private static void controlDisconnect() {
  // MINECRAFT-545/547 (John's comment 54862): the prior version called disconnectTrailer(0)
  // with a HARDCODED index, merely assuming the connection sits at index 0 - never actually
  // verified. Capture the real connection reference and compute its REAL current index instead;
  // if identity/list consistency has drifted from what SETTLE already confirmed, this is itself
  // an anomaly - treat it as a control failure (never fabricate an index to paper over it).
  var connectionB = towedB.towedByConnection;
  int indexB = (connectionB != null) ? towingB.towingConnections.indexOf(connectionB) : -1;
  boolean consistent = connectionB != null && towingB.towingConnections.contains(connectionB) && indexB >= 0;
  if (!consistent) {
   LOGGER.error("[DIV missingIndexFallbackProbe] FAILURE_DETECTED - CONTROL setup inconsistency before disconnect:"
     + " towedB.towedByConnection=" + connectionB + " computedIndex=" + indexB
     + " - this is a genuine anomaly, not fabricating an index to proceed anyway");
   // No reconnect request was ever issued for this anomaly - REQUEST_NOT_CONSUMED is the correct
   // classification (never a meaningful "no new connection" rejection, since no request existed).
   controlOutcome = ReconnectOutcome.REQUEST_NOT_CONSUMED;
   phase = Phase.LEAK_RECONNECT_REQUEST; ticksInPhase = 0;
   return;
  }
  // The REAL, proper disconnect path - never fault injection - genuinely releasing
  // connectionGroupsIndexesInUse on both sides, establishing the matched positive control.
  towingB.disconnectTrailer(indexB);
  LOGGER.info("[DIV missingIndexFallbackProbe] CONTROL - properly disconnected towingB/towedB via the real disconnectTrailer("
    + indexB + ") (the actual computed index, not assumed)");
  phase = Phase.CONTROL_RECONNECT_REQUEST; ticksInPhase = 0;
 }

 private static EntityVehicleF_Physics spawnFreshTowing(ServerLevel level, EntityVehicleF_Physics anchorTarget, String systemName) {
  AWrapperWorld world = mcinterface1211.WrapperWorld.getWrapperFor(level);
  var def = buildHullDefinition(systemName, true, false);
  // Same relative geometry towingA originally used, computed fresh against the target's CURRENT
  // position - robust to any minor settling drift, never a stale snapshot.
  double x = anchorTarget.position.x, y = anchorTarget.position.y, z = anchorTarget.position.z + SPAWN_SEPARATION;
  return spawnHull(world, def, x, y, z, 0);
 }

 /** MINECRAFT-545/547 (John's comment 54870): the REAL anchor world point - the exact formula
  * {@code checkIfTrailerCanConnect} itself computes (confirmed from the real decompiled source:
  * {@code connection.pos.copy().rotate(definer.orientation).add(definer.position)}, scale
  * omitted since every fixture vehicle here uses the default 1.0) - not a raw body-center
  * distance, which is NOT the same thing the real eligibility check actually measures. */
 private static Point3D anchorWorldPoint(EntityVehicleF_Physics vehicle, Point3D localConnectionPos) {
  return localConnectionPos.copy().rotate(vehicle.orientation).add(vehicle.position);
 }

 private static void controlReconnectRequest(ServerLevel level) {
  towingD = spawnFreshTowing(level, towedB, "missingindex-towingD");
  // 54844/54849/54856: the REAL normal selection path - towingConnectionVar's own public setter
  // - never connectTrailer directly, which would bypass the ALREADY_TOWED check entirely.
  towingD.towingConnectionVar.setTo(1.0, false); // group index 0 + 1
  // MINECRAFT-545/547 (John's comment 54870): the REAL anchor world points on BOTH sides - not
  // a raw body-center distance, which 54870 correctly flagged as not the same thing the real
  // eligibility check (checkIfTrailerCanConnect) actually measures.
  var hitchAnchor = anchorWorldPoint(towingD, HITCH_POS);
  var hookupAnchor = anchorWorldPoint(towedB, HOOKUP_POS);
  double anchorDistance = hitchAnchor.distanceTo(hookupAnchor);
  LOGGER.info("[DIV missingIndexFallbackProbe] CONTROL - spawned fresh towingD=" + towingD.uniqueUUID + " towingD.isValid=" + towingD.isValid
    + " hitchAnchorWorld=" + hitchAnchor + " hookupAnchorWorld=" + hookupAnchor + " anchorDistance=" + anchorDistance
    + " (eligible if <= 4.0, the connection's own JSONConnection.distance) requestedGroupIndex=0 - requested normal reconnect to"
    + " towedB via towingConnectionVar");
  phase = Phase.AWAIT_CONTROL_RESULT; ticksInPhase = 0;
 }

 private static ReconnectOutcome controlOutcome;

 private static void awaitControlResult() {
  ticksInPhase++;
  if (!boundedOrFail("awaitControlResult/towedB", towedB) || !boundedOrFail("awaitControlResult/towingD", towingD)) return;
  if (towedB.towedByConnection != null && towingD.towingConnections.contains(towedB.towedByConnection)) {
   controlOutcome = ReconnectOutcome.NEW_CONNECTION_OBSERVED;
   LOGGER.info("[DIV missingIndexFallbackProbe] CONTROL SUCCESS (NEW_CONNECTION_OBSERVED) - a new connection formed between"
     + " towingD/towedB after " + ticksInPhase + " ticks - the normal selection pipeline and this fixture's own geometry/"
     + "eligibility are confirmed working when the group index is genuinely free.");
   phase = Phase.LEAK_RECONNECT_REQUEST; ticksInPhase = 0;
   return;
  }
  // MINECRAFT-545/547 (John's comment 54870): "the request variable resetting to 0 after being
  // set nonzero IS processing evidence (though not rejection reason)." The real update() method
  // always resets towingConnectionVar back to 0 the SAME tick it is consumed, success or
  // rejection alike - captured here within the bounded window, distinct from "no new connection"
  // itself, so a request that was never actually processed (the var stayed nonzero) is never
  // misread as a meaningful ALREADY_TOWED-shaped rejection.
  boolean requestConsumed = !towingD.towingConnectionVar.isActive;
  if (ticksInPhase >= RECONNECT_WAIT_TICKS) {
   if (!requestConsumed) {
    controlOutcome = ReconnectOutcome.REQUEST_NOT_CONSUMED;
    LOGGER.error("[DIV missingIndexFallbackProbe] CONTROL REQUEST_NOT_CONSUMED - towingD.towingConnectionVar never reset to 0"
      + " within " + RECONNECT_WAIT_TICKS + " ticks after being set - the request was apparently never actually processed"
      + " (not merely rejected). A genuine anomaly - the final verdict will be INCONCLUSIVE_REQUEST_NOT_CONSUMED regardless of"
      + " anything the leak probe would show.");
   } else {
    controlOutcome = ReconnectOutcome.NO_NEW_CONNECTION_REQUEST_CONSUMED;
    LOGGER.error("[DIV missingIndexFallbackProbe] CONTROL NO_NEW_CONNECTION_REQUEST_CONSUMED - the request WAS consumed"
      + " (towingConnectionVar reset to 0) but no new connection formed between towingD/towedB within " + RECONNECT_WAIT_TICKS
      + " ticks, even though towingB/towedB were properly disconnected first. Per 54849/54856: this makes the leak probe's own"
      + " result UNUSABLE - proceeding to run it anyway for completeness, but the final verdict will be"
      + " INCONCLUSIVE_CONTROL_FAILED regardless of what it shows.");
   }
   phase = Phase.LEAK_RECONNECT_REQUEST; ticksInPhase = 0;
  }
 }

 private static void leakReconnectRequest(ServerLevel level) {
  towingC = spawnFreshTowing(level, towedA, "missingindex-towingC");
  towingC.towingConnectionVar.setTo(1.0, false);
  var hitchAnchor = anchorWorldPoint(towingC, HITCH_POS);
  var hookupAnchor = anchorWorldPoint(towedA, HOOKUP_POS);
  double anchorDistance = hitchAnchor.distanceTo(hookupAnchor);
  LOGGER.info("[DIV missingIndexFallbackProbe] LEAK PROBE - spawned fresh towingC=" + towingC.uniqueUUID + " towingC.isValid=" + towingC.isValid
    + " hitchAnchorWorld=" + hitchAnchor + " hookupAnchorWorld=" + hookupAnchor + " anchorDistance=" + anchorDistance
    + " (eligible if <= 4.0) requestedGroupIndex=0 - requested normal reconnect to towedA (whose own hookupGroupIndex bit was"
    + " never released by the fallback) via towingConnectionVar - identical mechanism and geometry pattern as the control,"
    + " different target");
  phase = Phase.AWAIT_LEAK_RESULT; ticksInPhase = 0;
 }

 /** MINECRAFT-545/547 (John's comment 54862): stored, never logged as a terminal banner HERE -
  * the actual terminal line is emitted from {@link #finishTickIfTerminal}, AFTER the centralized
  * cleanup attempt, so a reader can never see a "COMPLETE"-shaped line appear before cleanup has
  * actually been attempted. See that method's own doc. */
 private static LeakVerdict finalVerdict;

 private static void awaitLeakResult() {
  ticksInPhase++;
  if (!boundedOrFail("awaitLeakResult/towedA", towedA) || !boundedOrFail("awaitLeakResult/towingC", towingC)) return;
  ReconnectOutcome leakOutcome = null;
  if (towedA.towedByConnection != null && towingC.towingConnections.contains(towedA.towedByConnection)) {
   leakOutcome = ReconnectOutcome.NEW_CONNECTION_OBSERVED;
  } else if (ticksInPhase >= RECONNECT_WAIT_TICKS) {
   leakOutcome = !towingC.towingConnectionVar.isActive
     ? ReconnectOutcome.NO_NEW_CONNECTION_REQUEST_CONSUMED : ReconnectOutcome.REQUEST_NOT_CONSUMED;
  }
  if (leakOutcome == null) return;
  finalVerdict = classifyLeakVerdict(controlOutcome, leakOutcome);
  LOGGER.info("[DIV missingIndexFallbackProbe] RESULT - controlOutcome=" + controlOutcome + " leakProbeOutcome=" + leakOutcome
    + " verdict=" + finalVerdict + " - behavioral signal only, NOT direct proof of the ALREADY_TOWED rejection reason specifically"
    + " (that reason is delivered only via a client-targeted chat packet, unobservable in this player-less fixture - see this"
    + " class's own doc). Local-group-reuse evidence here does NOT verify client notification - that remains a SEPARATE,"
    + " still-open, still-unverified gap, not addressed by this fixture.");
  phase = Phase.DONE;
 }

 private static JSONVehicle buildHullDefinition(String systemName, boolean hitch, boolean hookup) {
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
  var group = new JSONCollisionGroup();
  group.collisionTypes = Set.of(JSONCollisionGroup.CollisionType.VEHICLE);
  group.collisions = List.of(mainBox);
  v.collisionGroups = List.of(group);
  // Ordinary (unmounted, unrestricted) connection, same as BuoyantHitchProbe's own - this
  // fixture is about connection/list bookkeeping, not joint stability, so there is no reason to
  // deviate from the already-proven-stable connection shape.
  if (hitch) v.connectionGroups = List.of(connectionGroup(true, HITCH_POS, null, false, false));
  if (hookup) v.connectionGroups = List.of(connectionGroup(false, HOOKUP_POS, null, false, false));
  return v;
 }

 private static JSONConnectionGroup connectionGroup(boolean isHitch, Point3D pos, RotationMatrix rot, boolean mounted, boolean restricted) {
  var connection = new JSONConnection();
  connection.type = "hitch";
  connection.pos = pos.copy();
  connection.rot = rot;
  connection.mounted = mounted;
  connection.restricted = restricted;
  connection.distance = 4;
  var group = new JSONConnectionGroup();
  group.groupName = isHitch ? "hitch" : "hookup";
  group.isHitch = isHitch; group.isHookup = !isHitch;
  group.connections = List.of(connection);
  return group;
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

 private static EntityVehicleF_Physics spawnHull(AWrapperWorld world, JSONVehicle definition, double x, double y, double z, double yawDegrees) {
  var floatSlotLeft = floatSlot(FLOAT_LEFT);
  var floatSlotRight = floatSlot(FLOAT_RIGHT);
  definition.parts = List.of(floatSlotLeft, floatSlotRight);
  var item = new ItemVehicle(definition, definition.definitions.get(0), PACK_ID);
  var vehicle = new EntityVehicleF_Physics(world, null, item, null);
  // MINECRAFT-545/547 (John's comment 54870): tracked IMMEDIATELY, before anything below that
  // could still throw (world.spawnEntity, addFloat) - a partial-construction failure here must
  // still leave this vehicle findable and removable by cleanup, not silently lost because it
  // was never assigned to its own named field.
  allSpawnedEntities.add(vehicle);
  vehicle.position.set(x, y, z);
  vehicle.orientation.set(new RotationMatrix()).setToAngles(new Point3D(0, yawDegrees, 0));
  world.spawnEntity(vehicle);
  addFloat(vehicle, floatSlotLeft);
  addFloat(vehicle, floatSlotRight);
  return vehicle;
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
