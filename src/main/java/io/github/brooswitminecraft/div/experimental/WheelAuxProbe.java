package io.github.brooswitminecraft.div.experimental;
import com.mojang.logging.LogUtils;
import io.github.brooswitminecraft.div.PhysicsBridge;
import minecrafttransportsimulator.baseclasses.*;
import minecrafttransportsimulator.entities.instances.EntityVehicleF_Physics;
import minecrafttransportsimulator.entities.instances.PartEngine;
import minecrafttransportsimulator.entities.instances.PartSeat;
import minecrafttransportsimulator.items.instances.ItemPartEngine;
import minecrafttransportsimulator.items.instances.ItemPartSeat;
import minecrafttransportsimulator.items.instances.ItemVehicle;
import minecrafttransportsimulator.jsondefs.*;
import minecrafttransportsimulator.mcinterface.AWrapperWorld;
import minecrafttransportsimulator.mcinterface.IWrapperEntity;
import minecrafttransportsimulator.mcinterface.IWrapperNBT;
import minecrafttransportsimulator.mcinterface.IWrapperPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import org.slf4j.Logger;
import java.util.List;
import java.util.UUID;

/**
 * Bounded, opt-in, CI/isolated-host-only runtime probe (ticket MINECRAFT-504, John's review
 * comment 53617: "existing live runtime probe proves environment CAN construct IV vehicles, so
 * not a blanket tooling limitation" - a direct rejection of this ticket's earlier claim that the
 * server-tick-continuation/ownership/staleness logic could only be pure-unit-tested). Exercises
 * {@code PhysicsBridge}'s ACTUAL production entry points - {@code setHPatternTarget}, {@code
 * recordWheelAuxOwner}, {@code advanceWheelAuxInputs}, {@code isControllingRider} - against a
 * real, live {@code EntityVehicleF_Physics}/{@code PartEngine}/{@code PartSeat} spawned on a real
 * server, not a reimplementation or a direct {@code HPatternShifter.Tracker} loop.
 *
 * <p>Same gating/pattern convention as this package's other probes (e.g. {@code BrakeSweepProbe}):
 * OFF by default, waits for a small forced chunk grid, builds nothing destructive (a bare chassis
 * + one engine + one seat floating in the air - no wheels, no ground contact, no movement; this
 * probe is about the wheel-aux STATE MACHINE, not vehicle physics), and is removed when done.
 */
public final class WheelAuxProbe {
 private WheelAuxProbe() {}
 private static final Logger LOGGER = LogUtils.getLogger();
 private static final boolean ENABLED = Boolean.getBoolean("div.experimentalWheelAuxProbe");
 private static final String PACK_ID = "divtest";
 private static final int CHUNK_X = 900, CHUNK_Z = 900; // distinct from BrakeSweepProbe's (600,600)/GroundSupportFixtureProbe's own area
 private static final int CHUNK_RADIUS = 1;
 private static final int MAX_TERRAIN_WAIT_TICKS = 200;
 private static final UUID TEST_PLAYER_ID = UUID.randomUUID();
 private static final UUID TEST_PLAYER_ID_B = UUID.randomUUID();

 private enum Phase { WAIT_TERRAIN, SPAWN, RUN, DONE }
 private static Phase phase = Phase.WAIT_TERRAIN;
 private static int terrainWaitTicksElapsed = 0;
 private static EntityVehicleF_Physics vehicle;
 private static PartEngine engine;
 private static PartSeat seat;
 private static int runStep = 0;
 private static int ticksInStep = 0;
 private static int gearBeforeRevocation;
 private static int pass = 0, fail = 0;

 public static void maybeRun(ServerLevel level) {
  if (!ENABLED) return;
  try {
   switch (phase) {
    case WAIT_TERRAIN -> waitTerrain(level);
    case SPAWN -> spawn(level);
    case RUN -> run();
    case DONE -> {}
   }
  } catch (RuntimeException ex) {
   LOGGER.error("[DIV wheelAuxProbe] FAILED - unexpected exception in phase " + phase + " runStep=" + runStep, ex);
   finish();
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
  if (allLoaded) { phase = Phase.SPAWN; return; }
  if (terrainWaitTicksElapsed > MAX_TERRAIN_WAIT_TICKS) {
   LOGGER.error("[DIV wheelAuxProbe] FAILED - terrain chunk grid never finished loading");
   finish();
  }
 }

 private static void spawn(ServerLevel level) {
  int blockX = CHUNK_X * 16 + 8, blockZ = CHUNK_Z * 16 + 8;
  var engineSlot = new JSONPartDefinition();
  engineSlot.pos = new Point3D(0, 0, 0);
  engineSlot.types = List.of("engine");
  var seatSlot = new JSONPartDefinition();
  seatSlot.pos = new Point3D(0, 0.5, 0);
  seatSlot.types = List.of("seat");
  seatSlot.isController = true;

  var definition = new JSONVehicle();
  definition.packID = PACK_ID; definition.systemName = "wheelauxprobe";
  definition.motorized = new JSONVehicle.VehicleMotorized();
  definition.motorized.litVariable = ""; definition.motorized.panel = "";
  var sub = new JSONSubDefinition(); sub.subName = ""; sub.name = definition.systemName;
  definition.definitions = List.of(sub);
  definition.rendering = new JSONRendering(); definition.rendering.modelType = JSONRendering.ModelType.NONE;
  definition.general = new AJSONItem.General(); definition.general.health = 100;
  var mainBox = new JSONCollisionBox();
  mainBox.pos = new Point3D(0, 0, 0); mainBox.width = 1; mainBox.height = 1;
  var group = new JSONCollisionGroup();
  group.collisionTypes = java.util.Set.of(JSONCollisionGroup.CollisionType.VEHICLE);
  group.collisions = List.of(mainBox);
  definition.collisionGroups = List.of(group);
  definition.parts = List.of(engineSlot, seatSlot);

  // Same pack-config registration BrakeSweepProbe's own spawn needs (AEntityVehicleC_Colliding's
  // constructor looks these up unconditionally for ANY vehicle's packID).
  minecrafttransportsimulator.systems.ConfigSystem.settings.general.packSpeedFactors.value.putIfAbsent(PACK_ID, 1.0);
  minecrafttransportsimulator.systems.ConfigSystem.settings.general.packVehicleScales.value.putIfAbsent(PACK_ID, 1.0);
  AWrapperWorld world = mcinterface1211.WrapperWorld.getWrapperFor(level);
  var item = new ItemVehicle(definition, definition.definitions.get(0), PACK_ID);
  vehicle = new EntityVehicleF_Physics(world, null, item, null);
  vehicle.position.set(blockX + 0.5, 200.0, blockZ + 0.5); // floating - no ground contact needed
  vehicle.orientation.set(new RotationMatrix());
  world.spawnEntity(vehicle);

  var engineDef = new JSONPart.JSONPartEngine();
  engineDef.type = JSONPart.EngineType.NORMAL;
  engineDef.gearRatios = List.of(-2.0f, -2.0f, -2.0f, -2.0f, -2.0f, -2.0f, 0.0f, 2.0f, 2.0f, 2.0f, 2.0f, 2.0f, 2.0f, 2.0f, 2.0f, 2.0f);
  engineDef.maxRPM = 6000; engineDef.maxSafeRPM = 5500; engineDef.idleRPM = 800; engineDef.startRPM = 400; engineDef.stallRPM = 200;
  var engineJson = new JSONPart();
  engineJson.packID = PACK_ID; engineJson.systemName = "wheelauxprobe_engine";
  engineJson.generic = new JSONPart.JSONPartGeneric();
  engineJson.engine = engineDef;
  var engineSub = new JSONSubDefinition(); engineSub.subName = ""; engineSub.name = engineJson.systemName;
  engineJson.definitions = List.of(engineSub);
  engineJson.rendering = new JSONRendering(); engineJson.rendering.modelType = JSONRendering.ModelType.NONE;
  engineJson.general = new AJSONItem.General(); engineJson.general.health = 100;
  var engineItem = new ItemPartEngine(engineJson, engineJson.definitions.get(0), PACK_ID);
  engine = new PartEngine(vehicle, null, engineSlot, engineItem, null);
  vehicle.addPart(engine, false);
  engine.running = true;
  engine.currentGearVar.setTo(0.0, false);

  var seatJson = new JSONPart();
  seatJson.packID = PACK_ID; seatJson.systemName = "wheelauxprobe_seat";
  seatJson.generic = new JSONPart.JSONPartGeneric();
  seatJson.generic.type = "seat";
  var seatSub = new JSONSubDefinition(); seatSub.subName = ""; seatSub.name = seatJson.systemName;
  seatJson.definitions = List.of(seatSub);
  seatJson.rendering = new JSONRendering(); seatJson.rendering.modelType = JSONRendering.ModelType.NONE;
  seatJson.general = new AJSONItem.General(); seatJson.general.health = 100;
  var seatItem = new ItemPartSeat(seatJson, seatJson.definitions.get(0), PACK_ID);
  seat = new PartSeat(vehicle, null, seatSlot, seatItem, null);
  vehicle.addPart(seat, false);
  seat.rider = new FakeRider(TEST_PLAYER_ID);

  LOGGER.info("[DIV wheelAuxProbe] spawned vehicle=" + vehicle.uniqueUUID + " engine gear=" + engine.currentGearVar.currentValue
    + " controllerSeat.isController=" + seat.placementDefinition.isController);
  phase = Phase.RUN;
 }

 private static void check(String label, boolean ok, String detail) {
  LOGGER.info("[DIV wheelAuxProbe] " + label + " result=" + (ok ? "PASS" : "FAIL") + " " + detail);
  if (ok) pass++; else fail++;
 }

 /**
  * Sequential, numbered steps, each spanning some number of real server ticks before checking
  * the real engine's own {@code currentGearVar} (or handbrake magnitude). This method never
  * calls {@code PhysicsBridge.advanceWheelAuxInputs()} itself - {@code
  * DynamicImmersiveVehicles} already registers that exact call on {@code ServerTickEvent.Post}
  * BEFORE the listener that drives this probe, so it has already run once per real tick by the
  * time this method executes. Review: an earlier version of this probe called it a second time
  * directly from here, which invoked it TWICE per real tick - contradicting this class's own
  * claim of relying purely on the real hook, and silently halving how many real ticks a given
  * {@code ticksInStep} count actually represented.
  */
 // Mirrors WheelAuxInput.HEARTBEAT_TICKS - deliberately duplicated rather than imported, since
 // this probe exists to independently verify the real behavior, not share a constant that could
 // silently drift out of sync with the client's own and still "pass".
 private static final int HEARTBEAT_TICKS = 20;
 private static final long STALE_TIMEOUT_TICKS = 100;

 private static void run() {
  ticksInStep++;
  switch (runStep) {
   case 0 -> { // Step 1: production-tick continuation - ONE recorded target (review on PR #13:
               // the prior version of this probe re-sent setHPatternTarget every tick, which
               // cannot actually distinguish "climbs across many ticks from one input" from
               // "climbs because it's told to every tick" - record ONCE here, then rely purely
               // on the real per-tick hook for every subsequent tick.
    if (ticksInStep == 1) {
     PhysicsBridge.recordWheelAuxOwner(vehicle, TEST_PLAYER_ID);
     PhysicsBridge.setHPatternTarget(vehicle, 3);
    }
    // No direct advanceWheelAuxInputs() call here - DynamicImmersiveVehicles already registers
    // it on ServerTickEvent.Post BEFORE this probe's own listener, so it has already run once
    // for this real tick by the time this method executes (review: a manual call here as well
    // would invoke it TWICE per real tick, contradicting the "relies purely on the real hook"
    // claim and silently halving how many real ticks a given tick-count actually represents).
    if (ticksInStep >= 6) {
     check("step1-productionTickContinuation", engine.currentGearVar.currentValue == 3.0,
       "expected real PartEngine.currentGearVar to reach 3 from a SINGLE recorded target (set once, at tick 1) over 6 real ticks of the actual per-tick hook - got " + engine.currentGearVar.currentValue);
     runStep = 1; ticksInStep = 0;
    }
   }
   case 1 -> { // Step 2: ownership-loss revocation - dismount (clear rider), request a NEW higher
               // target, confirm the real engine does NOT climb toward it anymore.
    if (ticksInStep == 1) {
     gearBeforeRevocation = (int) engine.currentGearVar.currentValue;
     seat.rider = null; // simulated dismount, same public field this project's own isControllingRider reads
     PhysicsBridge.setHPatternTarget(vehicle, 9); // requested AFTER losing control - must never apply
    }
    // No direct advanceWheelAuxInputs() call here - DynamicImmersiveVehicles already registers
    // it on ServerTickEvent.Post BEFORE this probe's own listener, so it has already run once
    // for this real tick by the time this method executes (review: a manual call here as well
    // would invoke it TWICE per real tick, contradicting the "relies purely on the real hook"
    // claim and silently halving how many real ticks a given tick-count actually represents).
    if (ticksInStep >= 6) {
     check("step2-ownershipLossRevocation", engine.currentGearVar.currentValue == gearBeforeRevocation,
       "expected gear to stay at " + gearBeforeRevocation + " (dismounted before the new target could apply) - got " + engine.currentGearVar.currentValue);
     runStep = 2; ticksInStep = 0;
    }
   }
   case 2 -> { // Step 3: a NEW owner (re-mount) climbing to the SAME numeric slot the old,
               // revoked tracker had already seen must NOT be silently ignored (comment 53617's
               // "remount/swap can inherit achieved latch").
    if (ticksInStep == 1) {
     seat.rider = new FakeRider(TEST_PLAYER_ID);
     PhysicsBridge.recordWheelAuxOwner(vehicle, TEST_PLAYER_ID);
     PhysicsBridge.setHPatternTarget(vehicle, gearBeforeRevocation); // same slot as current gear
     // Force the gear away first so reaching it again is an observable climb, not a no-op.
     if (gearBeforeRevocation <= 0) engine.shiftUp(); else engine.shiftDown();
    }
    // No direct advanceWheelAuxInputs() call here - DynamicImmersiveVehicles already registers
    // it on ServerTickEvent.Post BEFORE this probe's own listener, so it has already run once
    // for this real tick by the time this method executes (review: a manual call here as well
    // would invoke it TWICE per real tick, contradicting the "relies purely on the real hook"
    // claim and silently halving how many real ticks a given tick-count actually represents).
    if (ticksInStep >= 6) {
     check("step3-remountSameSlotResumes", engine.currentGearVar.currentValue == gearBeforeRevocation,
       "expected a fresh owner's request for slot " + gearBeforeRevocation + " to actually climb back to it (not be silently latched-ignored) - got " + engine.currentGearVar.currentValue);
     runStep = 3; ticksInStep = 0;
    }
   }
   case 3 -> { // Step 4: an IMMEDIATE A->B controller handoff with NO intervening ownership-loss
               // sweep at all (review on PR #13's own finding: recordWheelAuxOwner's unconditional
               // put() let B's packet silently inherit A's still-achieved tracker for the same
               // slot if B's own packet arrived before the next advanceWheelAuxInputs tick ever
               // ran). Deliberately never calls advanceWheelAuxInputs between A's climb and B's
               // takeover, so this can ONLY pass if recordWheelAuxOwner itself clears A's state
               // on the owner change - the per-tick sweep is not given any chance to do it first.
    if (ticksInStep == 1) {
     gearBeforeRevocation = (int) engine.currentGearVar.currentValue; // A's already-achieved slot
     PhysicsBridge.recordWheelAuxOwner(vehicle, TEST_PLAYER_ID_B); // immediate handoff, same tick
     PhysicsBridge.setHPatternTarget(vehicle, gearBeforeRevocation); // B selects the SAME slot A had
     if (gearBeforeRevocation <= 0) engine.shiftUp(); else engine.shiftDown(); // move away so re-reaching it is observable
     seat.rider = new FakeRider(TEST_PLAYER_ID_B); // B is now the controlling rider
    }
    // No direct advanceWheelAuxInputs() call here - DynamicImmersiveVehicles already registers
    // it on ServerTickEvent.Post BEFORE this probe's own listener, so it has already run once
    // for this real tick by the time this method executes (review: a manual call here as well
    // would invoke it TWICE per real tick, contradicting the "relies purely on the real hook"
    // claim and silently halving how many real ticks a given tick-count actually represents).
    if (ticksInStep >= 6) {
     check("step4-immediateHandoffNoInterveningSweepResumesForNewOwner", engine.currentGearVar.currentValue == gearBeforeRevocation,
       "expected B's immediate takeover (no intervening ownership-loss sweep) requesting the SAME slot A had already achieved to actually climb to it fresh, not be silently latched-ignored from A's stale tracker - got " + engine.currentGearVar.currentValue);
     runStep = 4; ticksInStep = 0;
    }
   }
   case 4 -> { // Step 5: a REPEATEDLY RESENT handbrake (simulating WheelAuxInput's own periodic
               // heartbeat - review: "assert held handbrake with repeated heartbeat beyond 100
               // ticks remains applied") must survive well past the 100-tick staleness timeout,
               // observed directly via PhysicsBridge.currentHandbrakeMagnitudeForTesting, not
               // inferred from an unrelated gear change.
    if (ticksInStep == 1) {
     seat.rider = new FakeRider(TEST_PLAYER_ID);
     PhysicsBridge.recordWheelAuxOwner(vehicle, TEST_PLAYER_ID);
     PhysicsBridge.setHandbrakeMagnitude(vehicle, 1.0);
    } else if ((ticksInStep - 1) % HEARTBEAT_TICKS == 0) {
     PhysicsBridge.setHandbrakeMagnitude(vehicle, 1.0); // the heartbeat resend itself
    }
    // No direct advanceWheelAuxInputs() call - relies purely on the real per-tick hook, same as
    // every other step above.
    if (ticksInStep >= STALE_TIMEOUT_TICKS + 20) { // well past one timeout window, several heartbeats in
     check("step5-heartbeatSurvivesStaleTimeout", PhysicsBridge.currentHandbrakeMagnitudeForTesting(vehicle) == 1.0,
       "expected a repeatedly-resent (heartbeat) handbrake to remain fully applied well past the " + STALE_TIMEOUT_TICKS + "-tick staleness timeout - got " + PhysicsBridge.currentHandbrakeMagnitudeForTesting(vehicle));
     runStep = 5; ticksInStep = 0;
    }
   }
   case 5 -> { // Step 6: TRUE silence (no further resend at all - review's "then ownership loss/
               // true silence clears it") must let the SAME staleness timeout actually release
               // the handbrake back to 0.0 - the genuine expiry path, not the achieved-latch
               // side effect the old step4's gear assertion actually exercised.
    // No direct advanceWheelAuxInputs() call, and no further setHandbrakeMagnitude calls at all -
    // the real per-tick hook alone must release this.
    if (ticksInStep >= STALE_TIMEOUT_TICKS + 20) {
     check("step6-trueSilenceExpiresHandbrake", PhysicsBridge.currentHandbrakeMagnitudeForTesting(vehicle) == 0.0,
       "expected the handbrake to actually expire back to 0.0 once resends genuinely stop for over " + STALE_TIMEOUT_TICKS + " ticks - got " + PhysicsBridge.currentHandbrakeMagnitudeForTesting(vehicle));
     finish();
    }
   }
  }
 }

 private static void finish() {
  LOGGER.info("[DIV wheelAuxProbe] COMPLETE pass=" + pass + " fail=" + fail);
  phase = Phase.DONE; // set BEFORE teardown, so a teardown-only exception below can never re-enter RUN
  try {
   if (vehicle != null) vehicle.remove();
  } catch (RuntimeException ex) {
   // FakeRider deliberately implements only getID() (the only method this project's own
   // production code ever calls on a seat's rider) - PartSeat's own removeRider() teardown
   // path calls several OTHER IWrapperEntity methods no real assertion above depends on, so a
   // failure here is a limitation of this probe's own minimal test double, not evidence about
   // the actual fix under test (the pass/fail tally above is already final by this point).
   LOGGER.warn("[DIV wheelAuxProbe] vehicle teardown threw (test-double limitation, not a finding): " + ex);
  }
 }

 /** Minimal {@code IWrapperEntity} - this project's own {@code PhysicsBridge.isControllingRider}
  * only ever calls {@link #getID()} on a seat's rider, so every other method is deliberately
  * unimplemented; a real player IWrapperPlayer is not needed for this probe's purposes. */
 private static final class FakeRider implements IWrapperEntity {
  private final UUID id;
  FakeRider(UUID id) { this.id = id; }
  @Override public boolean isValid() { return true; }
  @Override public UUID getID() { return id; }
  @Override public String getName() { throw new UnsupportedOperationException(); }
  @Override public AWrapperWorld getWorld() { throw new UnsupportedOperationException(); }
  @Override public minecrafttransportsimulator.entities.components.AEntityB_Existing getEntityRiding() { throw new UnsupportedOperationException(); }
  // Called by PartSeat.removeRider() during vehicle teardown (vehicle.remove()) - a genuine
  // no-op is correct here, not an error condition to throw on.
  @Override public void setRiding(minecrafttransportsimulator.entities.components.AEntityB_Existing entity) {}
  @Override public double getVerticalScale() { throw new UnsupportedOperationException(); }
  @Override public double getSeatOffset() { throw new UnsupportedOperationException(); }
  @Override public double getEyeHeight() { throw new UnsupportedOperationException(); }
  @Override public Point3D getPosition() { throw new UnsupportedOperationException(); }
  @Override public Point3D getEyePosition() { throw new UnsupportedOperationException(); }
  @Override public Point3D getHeadPosition() { throw new UnsupportedOperationException(); }
  @Override public void setPosition(Point3D position, boolean onGround) { throw new UnsupportedOperationException(); }
  @Override public void applyMotion(Point3D motion) { throw new UnsupportedOperationException(); }
  @Override public Point3D getVelocity() { throw new UnsupportedOperationException(); }
  @Override public void setVelocity(Point3D motion) { throw new UnsupportedOperationException(); }
  @Override public RotationMatrix getOrientation() { throw new UnsupportedOperationException(); }
  @Override public void setOrientation(RotationMatrix rotation) { throw new UnsupportedOperationException(); }
  @Override public float getPitch() { throw new UnsupportedOperationException(); }
  @Override public float getPitchDelta() { throw new UnsupportedOperationException(); }
  @Override public float getYaw() { throw new UnsupportedOperationException(); }
  @Override public float getYawDelta() { throw new UnsupportedOperationException(); }
  @Override public float getBodyYaw() { throw new UnsupportedOperationException(); }
  @Override public Point3D getLineOfSight(double distance) { throw new UnsupportedOperationException(); }
  @Override public void setYaw(double yaw) { throw new UnsupportedOperationException(); }
  @Override public void setBodyYaw(double yaw) { throw new UnsupportedOperationException(); }
  @Override public void setPitch(double pitch) { throw new UnsupportedOperationException(); }
  @Override public BoundingBox getBounds() { throw new UnsupportedOperationException(); }
  @Override public IWrapperNBT getData() { throw new UnsupportedOperationException(); }
  @Override public void setData(IWrapperNBT data) { throw new UnsupportedOperationException(); }
  @Override public boolean leashTo(IWrapperPlayer player) { throw new UnsupportedOperationException(); }
  @Override public void attack(Damage damage) { throw new UnsupportedOperationException(); }
  @Override public void addPotionEffect(JSONPotionEffect effect) { throw new UnsupportedOperationException(); }
  @Override public void removePotionEffect(JSONPotionEffect effect) { throw new UnsupportedOperationException(); }
 }
}
