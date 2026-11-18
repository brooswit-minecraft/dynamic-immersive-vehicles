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
 *  <li><b>Fallback-entry evidence, not setup alone</b>: once {@code towedA.towedByConnection}
 *  becomes null, that MUST have gone through {@code fallbackClearConnection}, never
 *  {@code disconnectAtIndex} - the fault injection already made the index lookup return -1, so
 *  the ordinary path is structurally unreachable. Logged explicitly with that reasoning, not
 *  merely observed. (Launching with {@code -Ddiv.debugGroundContact=true} additionally surfaces
 *  {@code fallbackClearConnection}'s own existing diagnostic log line as corroborating evidence -
 *  not required for this fixture's own pass/fail verdict.)</li>
 *  <li><b>Matched positive control</b> (54849/54856's own explicit requirement - absence of a
 *  new connection alone does NOT prove the leak caused it, since {@code checkIfTrailerCanConnect}
 *  has several other rejection outcomes producing the identical signal): an independent
 *  {@code towingB}/{@code towedB} pair, IDENTICAL group/geometry, connected and then PROPERLY
 *  disconnected via the real {@code disconnectTrailer(0)} (never fault injection) - genuinely
 *  freeing {@code connectionGroupsIndexesInUse} on both sides. A FRESH {@code towingD} entity
 *  (same hitch definition, never reused from elsewhere) then drives a reconnect request through
 *  the REAL normal selection path - {@code towingD.towingConnectionVar.setTo(...)}, never calling
 *  {@code connectTrailer} directly, which would bypass the very {@code ALREADY_TOWED} check this
 *  fixture depends on (54844's own finding). A new connection MUST form here - if it does not,
 *  the verdict is {@code INCONCLUSIVE_CONTROL_FAILED}, with NO claim made about the leak probe's
 *  own result, per 54849/54856's explicit instruction.</li>
 *  <li><b>The leak probe itself</b>: a SEPARATE fresh {@code towingC} entity (same definition),
 *  positioned with the SAME relative geometry {@code towingA} originally used, drives the SAME
 *  kind of normal reconnect request at {@code towedA} (whose {@code hookupGroupIndex} bit was
 *  never released by the fallback). Whether a new connection forms is the behavioral signal -
 *  explicitly logged as a signal, not direct proof of the {@code ALREADY_TOWED} reason
 *  specifically (54844/54849's own distinction - the real rejection reason is delivered only via
 *  a client-targeted chat packet, unobservable in this player-less fixture).</li>
 * </ol>
 *
 * <p><b>Explicitly NOT done, per 54844/54849/54856's own instructions</b>: no restore-then-
 * disconnect cleanup is relied on for anything this fixture's own verdict depends on - every
 * fixture-owned entity is simply despawned at the end, letting the leaked bookkeeping disappear
 * with the garbage-collected Java objects rather than attempting any unvalidated repair. No
 * client/bot is simulated and no new diagnostic Mixin is added - the chat-packet-only rejection
 * reason remains a separate, unverified, explicitly-named gap, not addressed by this fixture.
 *
 * <p>Opt-in via {@code -Ddiv.experimentalMissingIndexFallbackProbe=true} - OFF by default, never
 * runs during normal play, never touches any real vehicle, Zippy, or world content. Runs ONCE
 * per server lifetime, for a bounded window, then stops. Same {@code massKg} config-override
 * requirement as {@code BuoyantHitchProbe} (seeded for every one of this fixture's own five
 * vehicle keys - {@code divtest:missingindex-towing{a,b,c,d}}, {@code divtest:missingindex-towed{a,b}}).
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

 /** MINECRAFT-545/547 (John's comment 54849/54856): the pure, Minecraft/MTS-free verdict policy
  * this fixture's own real outcome routes through - trivially unit-testable with plain JUnit,
  * same spirit as {@code LifecycleInvariants.RequiredPhaseVerdict}. */
 public enum ControlOutcome { SUCCESS, FAILURE }
 public enum LeakProbeOutcome { NEW_CONNECTION_OBSERVED, NO_NEW_CONNECTION }
 public enum LeakVerdict { LEAK_CONFIRMED, LEAK_NOT_OBSERVED, INCONCLUSIVE_CONTROL_FAILED }

 /** 54849: "Failed control => INCONCLUSIVE, no cause claim." A failed control makes the leak
  * probe's own result meaningless - it must not be classified as either confirming or refuting
  * the leak, regardless of what it actually showed. */
 public static LeakVerdict classifyLeakVerdict(ControlOutcome control, LeakProbeOutcome leakProbe) {
  if (control == ControlOutcome.FAILURE) return LeakVerdict.INCONCLUSIVE_CONTROL_FAILED;
  return leakProbe == LeakProbeOutcome.NO_NEW_CONNECTION ? LeakVerdict.LEAK_CONFIRMED : LeakVerdict.LEAK_NOT_OBSERVED;
 }

 private static Phase phase = Phase.WAIT_TERRAIN;
 private static int ticksInPhase = 0, terrainWaitTicksElapsed = 0;
 private static boolean configured = false, chunkGridForced = false, chunkGridUnforced = false;
 private static double spawnX, spawnZ, spawnSurfaceY;
 private static EntityVehicleF_Physics towingA, towedA, towingB, towedB, towingC, towingD;
 private static JSONVehicle towingDefinition, towedDefinition;
 private static TowingConnection injectedConnection;

 public static void maybeRun(ServerLevel level) {
  if (!ENABLED) return;
  if (!configured) {
   configured = true;
   requestChunkGrid(level);
   LOGGER.info("[DIV missingIndexFallbackProbe] requested terrain chunk grid");
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
   LOGGER.error("[DIV missingIndexFallbackProbe] FAILED - unexpected exception in phase " + phase, ex);
   phase = Phase.FAILED;
  }
  if ((phase == Phase.DONE || phase == Phase.FAILED) && !chunkGridUnforced) {
   chunkGridUnforced = true;
   unforceChunkGrid(level);
   LOGGER.info("[DIV missingIndexFallbackProbe] released terrain chunk grid");
  }
 }

 private static void requestChunkGrid(ServerLevel level) {
  int chunkXHere = CHUNK_X, chunkZHere = CHUNK_Z;
  for (int dx = -CHUNK_RADIUS; dx <= CHUNK_RADIUS; dx++) for (int dz = -CHUNK_RADIUS; dz <= CHUNK_RADIUS; dz++) level.setChunkForced(chunkXHere + dx, chunkZHere + dz, true);
  chunkGridForced = true;
 }

 private static void unforceChunkGrid(ServerLevel level) {
  if (!chunkGridForced) return;
  for (int dx = -CHUNK_RADIUS; dx <= CHUNK_RADIUS; dx++) for (int dz = -CHUNK_RADIUS; dz <= CHUNK_RADIUS; dz++) level.setChunkForced(CHUNK_X + dx, CHUNK_Z + dz, false);
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
    LOGGER.error("[DIV missingIndexFallbackProbe] FAILED - terrain chunk grid never finished loading");
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
  packSpeedFactors.putIfAbsent(PACK_ID, 1.0);
  packVehicleScales.putIfAbsent(PACK_ID, 1.0);

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
   LOGGER.error("[DIV missingIndexFallbackProbe] FAILED - non-finite state tag=" + tag + " phase=" + phase + " tick=" + ticksInPhase + " position=" + v.position);
   phase = Phase.FAILED;
   return false;
  }
  double dx = v.position.x - spawnX, dz = v.position.z - spawnZ;
  double horizontal = Math.sqrt(dx * dx + dz * dz), vertical = Math.abs(v.position.y - spawnSurfaceY);
  if (horizontal > PLATFORM_BOUND_HORIZONTAL || vertical > PLATFORM_BOUND_VERTICAL) {
   LOGGER.error("[DIV missingIndexFallbackProbe] FAILED - left bounded area tag=" + tag + " phase=" + phase + " tick=" + ticksInPhase
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
    LOGGER.error("[DIV missingIndexFallbackProbe] FAILED - initial connection never formed on one or both pairs after SETTLE"
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
   LOGGER.error("[DIV missingIndexFallbackProbe] FAILED - pre-injection sanity check failed: injectedConnection=" + injectedConnection
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
   LOGGER.error("[DIV missingIndexFallbackProbe] FAILED - fault injection did not produce the expected asymmetric state");
   phase = Phase.FAILED;
   return;
  }
  LOGGER.info("[DIV missingIndexFallbackProbe] invalidating towingA via the same supported lifecycle method (remove()) the existing"
    + " TOWING_UNAVAILABLE phase already uses");
  towingA.remove();
  phase = Phase.AWAIT_LEAK_FALLBACK; ticksInPhase = 0;
 }

 private static void awaitLeakFallback() {
  ticksInPhase++;
  if (!boundedOrFail("awaitLeakFallback/towedA", towedA) || !boundedOrFail("awaitLeakFallback/towingB", towingB)
    || !boundedOrFail("awaitLeakFallback/towedB", towedB)) return;
  if (towedA.towedByConnection == null) {
   // 54856: "actual fallback-entry evidence (not setup alone)." towedA.towedByConnection can
   // only have become null via fallbackClearConnection's own explicit v.towedByConnection = null
   // line - disconnectAtIndex was structurally unreachable, since the fault injection already
   // made towingA.towingConnections.indexOf(injectedConnection) return -1 before the sweep ever
   // ran (asserted above, in FAULT_INJECT, before towingA.remove() was even called).
   LOGGER.info("[DIV missingIndexFallbackProbe] FALLBACK-ENTRY EVIDENCE CONFIRMED - towedA.towedByConnection is now null after"
     + " " + ticksInPhase + " ticks. The pre-injection assertion already proved indexOf==-1 before towingA.remove() ran, so the"
     + " ordinary disconnectAtIndex path was structurally unreachable - this null MUST have come from fallbackClearConnection."
     + " (Launching with -Ddiv.debugGroundContact=true additionally surfaces that method's own diagnostic log line as"
     + " corroborating evidence - not required for this conclusion.)");
   phase = Phase.CONTROL_DISCONNECT; ticksInPhase = 0;
   return;
  }
  if (ticksInPhase >= FALLBACK_WAIT_TICKS) {
   LOGGER.error("[DIV missingIndexFallbackProbe] FAILED - towedA.towedByConnection never became null within " + FALLBACK_WAIT_TICKS
     + " ticks of invalidating towingA - the orphan was never cleared at all (a more basic defect than the leak this fixture targets)");
   phase = Phase.FAILED;
  }
 }

 private static void controlDisconnect() {
  // The REAL, proper disconnect path - never fault injection - genuinely releasing
  // connectionGroupsIndexesInUse on both sides, establishing the matched positive control.
  towingB.disconnectTrailer(0);
  LOGGER.info("[DIV missingIndexFallbackProbe] CONTROL - properly disconnected towingB/towedB via the real disconnectTrailer(0)");
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

 private static void controlReconnectRequest(ServerLevel level) {
  towingD = spawnFreshTowing(level, towedB, "missingindex-towingD");
  // 54844/54849/54856: the REAL normal selection path - towingConnectionVar's own public setter
  // - never connectTrailer directly, which would bypass the ALREADY_TOWED check entirely.
  towingD.towingConnectionVar.setTo(1.0, false); // group index 0 + 1
  LOGGER.info("[DIV missingIndexFallbackProbe] CONTROL - spawned fresh towingD=" + towingD.uniqueUUID
    + " and requested normal reconnect to towedB via towingConnectionVar");
  phase = Phase.AWAIT_CONTROL_RESULT; ticksInPhase = 0;
 }

 private static ControlOutcome controlOutcome;

 private static void awaitControlResult() {
  ticksInPhase++;
  if (!boundedOrFail("awaitControlResult/towedB", towedB) || !boundedOrFail("awaitControlResult/towingD", towingD)) return;
  if (towedB.towedByConnection != null && towingD.towingConnections.contains(towedB.towedByConnection)) {
   controlOutcome = ControlOutcome.SUCCESS;
   LOGGER.info("[DIV missingIndexFallbackProbe] CONTROL SUCCESS - a new connection formed between towingD/towedB after "
     + ticksInPhase + " ticks - the normal selection pipeline and this fixture's own geometry/eligibility are confirmed working"
     + " when the group index is genuinely free.");
   phase = Phase.LEAK_RECONNECT_REQUEST; ticksInPhase = 0;
   return;
  }
  if (ticksInPhase >= RECONNECT_WAIT_TICKS) {
   controlOutcome = ControlOutcome.FAILURE;
   LOGGER.error("[DIV missingIndexFallbackProbe] CONTROL FAILURE - no new connection formed between towingD/towedB within "
     + RECONNECT_WAIT_TICKS + " ticks, even though towingB/towedB were properly disconnected first. Per 54849/54856: this makes"
     + " the leak probe's own result UNUSABLE - proceeding to run it anyway for completeness, but the final verdict will be"
     + " INCONCLUSIVE_CONTROL_FAILED regardless of what it shows.");
   phase = Phase.LEAK_RECONNECT_REQUEST; ticksInPhase = 0;
  }
 }

 private static void leakReconnectRequest(ServerLevel level) {
  towingC = spawnFreshTowing(level, towedA, "missingindex-towingC");
  towingC.towingConnectionVar.setTo(1.0, false);
  LOGGER.info("[DIV missingIndexFallbackProbe] LEAK PROBE - spawned fresh towingC=" + towingC.uniqueUUID
    + " and requested normal reconnect to towedA (whose own hookupGroupIndex bit was never released by the fallback) via"
    + " towingConnectionVar - identical mechanism and geometry pattern as the control, different target");
  phase = Phase.AWAIT_LEAK_RESULT; ticksInPhase = 0;
 }

 private static void awaitLeakResult() {
  ticksInPhase++;
  if (!boundedOrFail("awaitLeakResult/towedA", towedA) || !boundedOrFail("awaitLeakResult/towingC", towingC)) return;
  LeakProbeOutcome leakOutcome = null;
  if (towedA.towedByConnection != null && towingC.towingConnections.contains(towedA.towedByConnection)) {
   leakOutcome = LeakProbeOutcome.NEW_CONNECTION_OBSERVED;
  } else if (ticksInPhase >= RECONNECT_WAIT_TICKS) {
   leakOutcome = LeakProbeOutcome.NO_NEW_CONNECTION;
  }
  if (leakOutcome == null) return;
  var verdict = classifyLeakVerdict(controlOutcome, leakOutcome);
  LOGGER.info("[DIV missingIndexFallbackProbe] RESULT - controlOutcome=" + controlOutcome + " leakProbeOutcome=" + leakOutcome
    + " verdict=" + verdict + " - behavioral signal only, NOT direct proof of the ALREADY_TOWED rejection reason specifically"
    + " (that reason is delivered only via a client-targeted chat packet, unobservable in this player-less fixture - see this"
    + " class's own doc). Local-group-reuse evidence here does NOT verify client notification - that remains a SEPARATE,"
    + " still-open, still-unverified gap, not addressed by this fixture.");
  LOGGER.info("[DIV missingIndexFallbackProbe] COMPLETE - verdict=" + verdict
    + " (see VERIFICATION.md for this run's own precise evidence/limits, not restated here)");
  towingA_cleanup();
  phase = Phase.DONE;
 }

 private static void towingA_cleanup() {
  // No restore-then-disconnect reliance, per 54844/54849/54856 - simply despawn every
  // fixture-owned entity; the leaked bookkeeping disappears with the garbage-collected objects.
  if (towedA != null && towedA.isValid) towedA.remove();
  if (towingB != null && towingB.isValid) towingB.remove();
  if (towedB != null && towedB.isValid) towedB.remove();
  if (towingC != null && towingC.isValid) towingC.remove();
  if (towingD != null && towingD.isValid) towingD.remove();
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
