package io.github.brooswitminecraft.div;

import net.neoforged.neoforge.common.NeoForge;
import dev.ryanhcode.sable.api.physics.constraint.GenericConstraintConfiguration;
import dev.ryanhcode.sable.api.physics.constraint.PhysicsConstraintHandle;
import dev.ryanhcode.sable.api.physics.handle.RigidBodyHandle;
import dev.ryanhcode.sable.api.physics.mass.MassData;
import dev.ryanhcode.sable.api.physics.object.box.BoxPhysicsObject;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.companion.math.Pose3d;
import dev.ryanhcode.sable.physics.config.dimension_physics.DimensionPhysicsData;
import com.mojang.logging.LogUtils;
import org.slf4j.Logger;
import io.github.brooswitminecraft.div.mixin.VehicleTowingAccessor;
import io.github.brooswitminecraft.div.mixin.WorldAccessor;
import minecrafttransportsimulator.baseclasses.Point3D;
import minecrafttransportsimulator.baseclasses.RotationMatrix;
import minecrafttransportsimulator.baseclasses.TowingConnection;
import minecrafttransportsimulator.entities.components.AEntityE_Interactable;
import minecrafttransportsimulator.entities.instances.APart;
import minecrafttransportsimulator.entities.instances.EntityVehicleF_Physics;
import minecrafttransportsimulator.entities.instances.PartEngine;
import minecrafttransportsimulator.entities.instances.PartSeat;
import minecrafttransportsimulator.entities.instances.PartGroundDevice;
import minecrafttransportsimulator.entities.instances.PartPropeller;
import minecrafttransportsimulator.jsondefs.JSONPart;
import minecrafttransportsimulator.jsondefs.JSONCollisionGroup;
import minecrafttransportsimulator.jsondefs.JSONVehicle;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix3d;
import org.joml.Quaterniond;
import org.joml.Vector3d;
import org.joml.Vector3dc;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** One server-owned Sable body. IV retains models, seats, controls, persistence and packets. */
public final class PhysicsBridge {
 private static final BodyRegistry<EntityVehicleF_Physics, VehicleBody> registry = new BodyRegistry<>();
 // G29/wheel hybrid H-pattern + paddle shifting and analog handbrake (ticket MINECRAFT-504,
 // comments 53557/53572/53573/53603). Keyed by entity/part identity (IdentityHashMap), matching
 // this class's existing per-vehicle-state pattern (brokenDefinitions/pendingInheritedVelocity
 // above) - cleaned up from the SAME cleanup()/unload() sweeps those use, PLUS a bounded staleness
 // sweep in advanceWheelAuxInputs (comment 53603: a client that stops sending - dismount, logout,
 // crash - must not leave a vehicle braking/stuck mid-shift forever).
 private static final java.util.Map<EntityVehicleF_Physics, Double> handbrakeMagnitude = new IdentityHashMap<>();
 private static final java.util.Map<EntityVehicleF_Physics, Long> handbrakeLastUpdateTick = new IdentityHashMap<>();
 private static final java.util.Map<PartEngine, Integer> hPatternTargetByEngine = new IdentityHashMap<>();
 private static final java.util.Map<PartEngine, Long> hPatternLastUpdateTick = new IdentityHashMap<>();
 private static final java.util.Map<PartEngine, HPatternShifter.Tracker> hPatternTrackers = new IdentityHashMap<>();
 // The player currently AUTHORIZED to drive this vehicle's wheel-aux state (ticket comment
 // 53617): recorded only once WheelAuxPayload's own controller-seat check has already passed,
 // and checked EVERY tick in advanceWheelAuxInputs - a dismount/disconnect/seat change is
 // caught immediately (own ownership-loss sweep), not left to wait out the staleness timeout
 // below (that timeout is now purely the belt-and-suspenders fallback for a client that stops
 // sending ENTIRELY without ever losing control, e.g. a hung game process still seated).
 private static final java.util.Map<EntityVehicleF_Physics, java.util.UUID> wheelAuxOwner = new IdentityHashMap<>();
 private static long wheelAuxTickCounter = 0;
 // 100 ticks = 5s at 20tps - generous enough that ordinary network jitter/lag never triggers it,
 // short enough that a genuinely stuck client releases promptly even without an ownership-loss
 // event (comment 53603/53617).
 private static final long WHEEL_AUX_STALE_TIMEOUT_TICKS = 100;
 /**
  * Client → server: the live analog handbrake pedal/lever magnitude (0-1), read and calibrated
  * entirely client-side (own axis, own deadzone, own inversion - never the service-brake axis
  * or the native digital parking-brake toggle). Safe to call every tick; a missing/disabled
  * channel is simply 0.0 from the caller's own {@code WheelAxisCalibration}, never stale.
  * Rejects a non-finite magnitude outright (comment 53603: reject invalid input, don't clamp
  * NaN into something that silently passes) rather than storing it.
  */
 public static void setHandbrakeMagnitude(EntityVehicleF_Physics v, double magnitude) {
  if (!Double.isFinite(magnitude)) return;
  handbrakeMagnitude.put(v, Math.max(0.0, Math.min(1.0, magnitude)));
  handbrakeLastUpdateTick.put(v, wheelAuxTickCounter);
 }
 /** Test/probe observability only (ticket comment: review on PR #13's own ask for "real
  * handbrake/state observation", not an inference from the gear-shift latch) - {@link #stepGround}
  * reads this same map directly for the actual physics composition; this is the SAME value,
  * exposed read-only so a runtime probe can assert it directly instead of only inferring release
  * via an unrelated gear change. Returns exactly 0.0 for a vehicle with no recorded (or already
  * cleared/expired) handbrake entry, matching what an unloaded wheel actually gets at
  * {@code stepGround} time. */
 public static double currentHandbrakeMagnitudeForTesting(EntityVehicleF_Physics v) {
  return handbrakeMagnitude.getOrDefault(v, 0.0);
 }
 /**
  * Client → server: the physical H-pattern shifter's currently-read slot (see {@link
  * HPatternShifter} for the sentinel values). Only RECORDS the target and its freshness here -
  * actually applying it (one {@code PartEngine.shiftUp()}/{@code shiftDown()}/{@code
  * shiftNeutral()} step per server tick, for as many ticks as climbing toward it takes) happens
  * in {@link #advanceWheelAuxInputs}, not here, because a single network packet only arrives
  * once per actual CHANGE (the caller dedupes unchanged values before sending) while climbing
  * more than one gear - or retrying a step {@code PartEngine} safety-refused - needs repeated
  * per-tick attempts against the SAME stored target (ticket comment 53603's first finding:
  * applying only inside the packet handler stalls after one gear). Rejects any target outside
  * {@link HPatternShifter}'s own valid sentinel/gear-number range (comment 53603: reject
  * invalid/out-of-range input, don't silently store garbage).
  */
 public static void setHPatternTarget(EntityVehicleF_Physics v, int target) {
  if (target != HPatternShifter.DISABLED && target != HPatternShifter.REVERSE_REQUEST && target != HPatternShifter.NEUTRAL_REQUEST
    && (target < 1 || target > 9)) return;
  for (var part : v.allParts) if (part instanceof PartEngine engine) {
   hPatternTargetByEngine.put(engine, target);
   hPatternLastUpdateTick.put(engine, wheelAuxTickCounter);
  }
 }
 /**
  * Client → server (via the packet handler, AFTER its own controller-seat check already
  * passed): records who is currently authorized to drive this vehicle's wheel-aux state, so
  * {@link #advanceWheelAuxInputs} can immediately revoke everything the instant that player
  * stops controlling it (ticket comment 53617 - a plain "clear on dismount" packet cannot work,
  * since by the time it would be sent the sender has already failed that same controller-seat
  * check).
  */
 public static void recordWheelAuxOwner(EntityVehicleF_Physics v, java.util.UUID playerId) {
  // Review (comment on PR #13, fd7fac1): an immediate A->B controller handoff - B's own packet
  // arriving before the NEXT tick's ownership-loss sweep even runs - must not let B silently
  // inherit A's still-latched H-pattern tracker/target/handbrake. Clear unconditionally on any
  // actual owner CHANGE (never on a repeat packet from the same still-current owner, which must
  // not reset an in-progress climb every tick) before recording the new owner, so B's own
  // subsequent setHPatternTarget/setHandbrakeMagnitude calls (which the caller always issues
  // right after this) start from a fresh Tracker - the ownership-loss sweep in
  // advanceWheelAuxInputs remains the complementary path for a dismount/disconnect that happens
  // WITHOUT a new owner ever taking over.
  var previousOwner = wheelAuxOwner.get(v);
  if (previousOwner != null && !previousOwner.equals(playerId)) clearWheelAuxState(v);
  wheelAuxOwner.put(v, playerId);
 }
 /**
  * Server-tick continuation (ticket comment 53603/53617) for both wheel-aux inputs above -
  * called once per real server tick (see {@code DynamicImmersiveVehicles}'s own wiring),
  * independent of whether any vehicle's physics step happens to run that same tick.
  *
  * <p>Order matters (comment 53617: "expire state BEFORE taking another H shift action"):
  * ownership loss, then staleness, are both checked and cleared FIRST, so a revoked/expired
  * engine never takes one more climb step on the same tick it is released; only entries that
  * survive both checks get a climb step applied.
  *
  * <p>H-pattern: one climb step per engine per tick via its own {@link HPatternShifter.Tracker}
  * - entirely independent of IV's own {@code useShifter} setting and its {@code
  * shiftSelectionVar}, so native's own {@code useShifter=false} paddle handling (already
  * shipped, unmodified) keeps working alongside this untouched; they only converge on {@code
  * PartEngine}'s public shift methods. The tracker itself (see its own doc) detects and cancels
  * an outstanding climb the instant something ELSE changes {@code currentGearVar} between this
  * class's own ticks - a paddle (or native's {@code simpleThrottle} auto-reverse) intervening
  * mid-climb always wins, matching the explicit "user expressly wants together" requirement
  * rather than fighting back toward the superseded H-pattern target.
  */
 public static void advanceWheelAuxInputs() {
  wheelAuxTickCounter++;
  // Ownership-loss revocation (comment 53617) - checked first, before staleness, before
  // applying any climb step this tick.
  wheelAuxOwner.entrySet().removeIf(e -> {
   var v = e.getKey();
   if (v.isValid && isControllingRider(v, e.getValue())) return false;
   clearWheelAuxState(v);
   return true;
  });
  hPatternTargetByEngine.keySet().removeIf(engine -> !engine.isValid || isWheelAuxStale(hPatternLastUpdateTick.getOrDefault(engine, 0L)));
  handbrakeMagnitude.keySet().removeIf(v -> !v.isValid || isWheelAuxStale(handbrakeLastUpdateTick.getOrDefault(v, 0L)));
  for (var entry : hPatternTargetByEngine.entrySet()) {
   var engine = entry.getKey();
   var tracker = hPatternTrackers.computeIfAbsent(engine, ignored -> new HPatternShifter.Tracker());
   switch (tracker.nextAction((int)engine.currentGearVar.currentValue, entry.getValue())) {
    case SHIFT_UP -> tracker.recordOutcome(engine.shiftUp());
    case SHIFT_DOWN -> tracker.recordOutcome(engine.shiftDown());
    case NEUTRAL -> { engine.shiftNeutral(); tracker.recordOutcome(true); }
    case NONE -> {}
   }
  }
  hPatternLastUpdateTick.keySet().removeIf(engine -> !hPatternTargetByEngine.containsKey(engine));
  handbrakeLastUpdateTick.keySet().removeIf(v -> !handbrakeMagnitude.containsKey(v));
  hPatternTrackers.keySet().removeIf(engine -> !engine.isValid || !hPatternTargetByEngine.containsKey(engine));
 }
 /** Removes every wheel-aux entry for one vehicle outright (not merely zeroing values) - used
  * by both the ownership-loss and (indirectly, per-engine) staleness paths, and critically, this
  * is what lets a NEW owner's H-pattern input start from a fresh {@link HPatternShifter.Tracker}
  * instead of silently inheriting a stale "already achieved" latch for whatever slot the shifter
  * happens to rest in (ticket comment 53617: "remount/swap can inherit achieved latch"). */
 private static void clearWheelAuxState(EntityVehicleF_Physics v) {
  handbrakeMagnitude.remove(v);
  handbrakeLastUpdateTick.remove(v);
  for (var part : v.allParts) if (part instanceof PartEngine engine) {
   hPatternTargetByEngine.remove(engine);
   hPatternLastUpdateTick.remove(engine);
   hPatternTrackers.remove(engine);
  }
 }
 private static boolean isWheelAuxStale(long lastUpdateTick) {
  return WheelAuxStaleness.isStale(lastUpdateTick, wheelAuxTickCounter, WHEEL_AUX_STALE_TIMEOUT_TICKS);
 }
 /**
  * Ticket MINECRAFT-504 comment 53603: the server had no driver/controller-seat authorization at
  * all - any passenger's wheel-aux packet would have been accepted. A controller-seat occupant is
  * {@code PartSeat.placementDefinition.isController} with {@code seat.rider} matching the given
  * player's own ID (IV's {@code IWrapperEntity.getID()} mirrors the underlying Minecraft UUID).
  */
 public static boolean isControllingRider(EntityVehicleF_Physics v, java.util.UUID playerId) {
  for (var part : v.allParts) if (part instanceof PartSeat seat && seat.placementDefinition.isController
    && seat.rider != null && playerId.equals(seat.rider.getID())) return true;
  return false;
 }
 /**
  * Ticket 53385's concrete, confirmed defect: Body's own constructor always seeds BOTH halves'
  * angular velocity to exactly zero ({@code addLinearAndAngularVelocity(initialVelocity, new
  * Vector3d())}) and derives only ONE shared linear velocity from {@code v.motion} - correct for
  * a genuinely fresh vehicle spawn (at rest or moving in a straight line with no spin), but wrong
  * for {@link Body#chassisBoundsChanged}'s own mid-life rebuild: a vehicle actually rotating the
  * instant its envelope changes would have that rotation silently zeroed, and - review's own
  * correction - {@code v.motion} alone cannot reconstruct each half's own distinct point velocity
  * once the chassis center (and so each half's own lever arm) shifts.
  *
  * <p>Populated by {@link #active} immediately before deactivating a Body for a confirmed
  * {@code chassisBoundsChanged} rebuild or a mode/category transition (never for an
  * ordinary activation). Retained across construction failure, then consumed once by the next
  * successful Body or BuoyantBody constructor for this same vehicle, so a normal subsequent spawn is never affected
  * by a stale leftover entry.
  */
 record InheritedRigidBodyVelocity(Vector3d angularVelocity, Vector3d referenceLinearVelocity, Vector3d referencePosition) {
  Vector3d velocityAt(org.joml.Vector3dc point) {
   return new Vector3d(referenceLinearVelocity).add(new Vector3d(angularVelocity)
     .cross(new Vector3d(point).sub(referencePosition)));
  }
 }
 /**
  * Ticket 53390's explicit "extract small replacement-state lifecycle seam and unit-test
  * get/retain-on-failure/consume-on-success/cleanup" - generic over {@code K}/{@code V} (same
  * reasoning as {@link #replaceIfCategoryChanged}/{@link BodyRegistry}: directly testable with
  * plain keys and fake values, no live entity/Sable runtime needed) rather than a bare
  * {@code Map} field with the three call sites' own discipline as the only thing enforcing
  * correct get/retain/consume/cleanup semantics. {@link #get} never removes (so a failed
  * construction's own retry still finds the entry); {@link #consumeOnSuccess} and
  * {@link #cleanup} both remove, but mean different things at the call site (success vs.
  * abandonment) even though the implementation happens to be identical right now.
  */
 static final class PendingReplacementState<K, V> {
  private final java.util.Map<K, V> pending = new IdentityHashMap<>();
  V get(K key) { return pending.get(key); }
  void stage(K key, V value) { pending.put(key, value); }
  void consumeOnSuccess(K key) { pending.remove(key); }
  void cleanup(K key) { pending.remove(key); }
  /**
   * Review 53399: a key can be staged, then abandoned, WITHOUT ever appearing in - or while
   * already absent from - any other registry a caller's own bulk-removal loop iterates. The
   * concrete case: PhysicsBridge.active()'s chassisBoundsChanged rebuild calls
   * registry.deactivate(v) (removing v from BodyRegistry) BEFORE attempting reconstruction; if
   * that reconstruction then fails, v is already gone from BodyRegistry but still staged here.
   * cleanup()/unload()'s own registry.removeIf(...) callback never runs for v in that case, so
   * their own pendingInheritedVelocity.cleanup(v) call inside it never fires either - this sweep
   * is independent of BodyRegistry entirely, keyed only by this store's own staged keys, so it
   * still finds and removes v.
   */
  void removeIf(java.util.function.Predicate<K> predicate) { pending.keySet().removeIf(predicate); }
 }
 static final PendingReplacementState<EntityVehicleF_Physics, InheritedRigidBodyVelocity> pendingInheritedVelocity = new PendingReplacementState<>();
 // Vehicle definitions whose chassis geometry failed to build a Sable body once already;
 // keyed by the shared JSON definition object (one per packID:systemName, not per entity), so a
 // bad content-pack definition is retried, and logged, once rather than every tick for every
 // vehicle instance of that definition.
 private static final Set<JSONVehicle> brokenDefinitions = Collections.newSetFromMap(new IdentityHashMap<>());
 // Live hitch-joint bookkeeping, keyed by the trailer entity - see HitchRegistry for why this is
 // its own class (a testable seam, independent of Body/live Sable types). Only ever keyed by a
 // vehicle whose active VehicleBody is a Body (ground/flight/rotor) - a BuoyantBody has no
 // pose/anchor API for a joint to connect to; see Classification's class doc.
 private static final HitchRegistry<EntityVehicleF_Physics> hitches = new HitchRegistry<>();
 static final double DT = .05;
 private PhysicsBridge() {}
 private static Overrides.Tuning tuning(JSONVehicle definition) {
  // Overrides.resolved (ticket comment 53181) applies the per-field precedence chain and always
  // returns a fully non-null Tuning - the raw Overrides.get(key) entry can now be sparse (a
  // partial override), so callers must never read it directly.
  return Overrides.resolved(definition.packID + ":" + definition.systemName);
 }
 /**
  * Review finding (ticket 53518/MINECRAFT-505, self-review 53542 and John's comment 53539):
  * {@code Overrides.resolvedPart} existed with no actual production consumer, so a configured
  * {@code parts} entry never affected real physics. This wires exactly one documented, concrete
  * field - an engine part's own drive force - into {@code stepGround}'s existing drive-force
  * computation, as the deliberate vehicle/part precedence: an explicit part-level {@code
  * driveForce} override, if this specific engine part has one, wins outright over the vehicle's
  * own resolved {@code driveForce} (not stacked/multiplied with it - a part saying "my own drive
  * force is X" is a complete replacement, not a modifier); otherwise the vehicle's already-
  * resolved value is used completely unchanged, so an installation with no part overrides at all
  * behaves byte-identically to before this ticket. {@code Overrides.effectivePartOverride}
  * (unlike {@code resolvedPart}) returns {@code null} - never the flat fallback - for a field the
  * part says nothing about, which is exactly the signal needed to tell "no override" apart from
  * "override explicitly set it".
  */
 static double enginePartDriveForce(String partKey, double vehicleDriveForce) {
  var partOverride = Overrides.effectivePartOverride(partKey);
  return (partOverride != null && partOverride.driveForce != null) ? partOverride.driveForce : vehicleDriveForce;
 }
 /**
  * MINECRAFT-553 (gear-aware ground drive): bounded, opt-in scale applied on top of {@link
  * #enginePartDriveForce}'s already-resolved drive force, so a configured vehicle's drive force
  * actually varies by current gear the way a real gearbox's torque multiplication does, instead
  * of every forward gear producing the identical magnitude {@code stepGround} always applied
  * before this ticket (only the gear's SIGN - forward/neutral/reverse - ever changed anything).
  *
  * <p>{@code currentGearRatioMagnitude}/{@code referenceGearRatioMagnitude} are {@code
  * Math.abs} of two entries straight off the SAME native, already-pinned {@code
  * JSONPartEngine#gearRatios} list IV's own engine RPM-feedback loop (decompiled and confirmed
  * against the exact pinned curse.maven jar - see this ticket's VERIFICATION.md) already reads
  * for its own {@code rpm}/{@code wheel.angularVelocity} bookkeeping ({@code
  * PartEngine.gearRatioVar}, kept in sync with {@code currentGearVar} on every shift) - this
  * method never writes either variable, never touches {@code groundDeviceCollective}, and is a
  * pure read of a value nothing else derives force from: IV's own {@code addToForceOutput} (the
  * native method that would otherwise apply a competing propulsive force from these same ratios)
  * is confirmed separately cancelled for every DIV-controlled vehicle (see the native-contact-
  * sync comment earlier in this file), so consuming the ratio's magnitude here cannot double-
  * apply drive force and cannot perturb the still-open, unrelated RPM-feedback/{@code
  * wheel.angularVelocity} multi-writer question that comment documents.
  *
  * <p>{@code referenceGearRatioMagnitude} is the vehicle's own configured FIRST FORWARD gear's
  * ratio magnitude - the same reference every call site must use so gear 1 always resolves to
  * exactly {@code scale=1.0} (today's unscaled drive force), keeping a vehicle's existing "first
  * gear" feel byte-identical whether or not this ticket's opt-in is set, and higher forward gears
  * (normally a smaller ratio magnitude - less torque multiplication, more top speed) scale down
  * proportionally, matching real drivetrain mechanics (wheel torque, and so drive force at a
  * constant wheel radius, is proportional to gear-ratio magnitude for a given engine torque).
  *
  * <p>{@code enabled} is the resolved {@code Overrides.Tuning#gearRatioAware} opt-in - {@code
  * false} (including the documented unset/{@code null} default) returns exactly {@code 1.0}, a
  * byte-identical no-op: a nonconfigured vehicle's drive force is completely unaffected by this
  * ticket, same "unset means untouched" shape {@code axleDriveForceSharing}/{@code
  * ackermannSteering} already use.
  *
  * <p><b>Safety/bounds:</b> a non-finite or non-positive {@code referenceGearRatioMagnitude} (a
  * malformed or single-forward-gear datapack definition) falls back to {@code 1.0} rather than
  * dividing by an invalid/degenerate reference. A non-finite or non-positive (including exactly
  * {@code 0} - {@link #gearRatioMagnitudeAt}'s own fallback for a null/missing list entry) {@code
  * currentGearRatioMagnitude} ALSO falls back to {@code 1.0} - this is the documented malformed-
  * ratio safety contract: missing/invalid CURRENT-gear data must preserve the vehicle's default
  * (unscaled) drive force, never silently apply {@code MIN_GEAR_FORCE_SCALE} as if a genuinely
  * tiny but valid ratio had been configured. The resulting ratio (when both magnitudes are valid)
  * is clamped to {@code
  * [MIN_GEAR_FORCE_SCALE, MAX_GEAR_FORCE_SCALE]} so a pathological datapack gear-ratio table
  * (e.g. an extreme outlier ratio) can never make the combined wheel/tire force this scales
  * unboundedly large or collapse it to effectively zero while still "in gear" - this is the only
  * bound this method imposes; it is a fixed safety envelope, not a new user-facing knob, keeping
  * this ticket's configurable surface to exactly the one new {@code gearRatioAware} boolean.
  * Neutral/engine-off are unaffected regardless of this method's result: {@code stepGround}
  * multiplies this scale by {@code Math.signum(currentGearVar.currentValue)}, which is already
  * {@code 0} at neutral, and the whole drive term is only ever computed for a {@code
  * PartEngine} with {@code running==true}.
  */
 static final double MIN_GEAR_FORCE_SCALE = 0.2, MAX_GEAR_FORCE_SCALE = 5.0;
 static double gearForceScale(double currentGearRatioMagnitude, double referenceGearRatioMagnitude, boolean enabled) {
  if (!enabled) return 1.0;
  if (!Double.isFinite(referenceGearRatioMagnitude) || referenceGearRatioMagnitude <= 0) return 1.0;
  if (!Double.isFinite(currentGearRatioMagnitude) || currentGearRatioMagnitude <= 0) return 1.0;
  double raw = currentGearRatioMagnitude / referenceGearRatioMagnitude;
  return Math.max(MIN_GEAR_FORCE_SCALE, Math.min(MAX_GEAR_FORCE_SCALE, raw));
 }
 /**
  * MINECRAFT-553: {@code Math.abs} of {@code gear}'s own ratio from this specific engine's
  * native, already-pinned {@code gearRatios} list ({@code JSONPart.engine.gearRatios} - {@code
  * engine.definition} is already statically typed {@code JSONPart} via {@code
  * APart<JSONPart>}'s own generic parameter, so no cast is needed, matching every other {@code
  * engine.definition.*} read already in this file). Mirrors the exact {@code gear+reverseGears}
  * index IV's own {@code PartEngine} uses internally ({@code gearRatioVar.setTo(...)} and its own
  * initial-gear clamp - decompiled and confirmed against the real pinned jar) rather than reading
  * the private {@code gearRatioVar} field itself through any reflection/variable-map workaround.
  * Defensively clamps the index into the list's actual bounds (a datapack is untrusted input)
  * and returns {@code 0} for a null/empty list - {@link #gearForceScale} already treats a
  * non-positive (including exactly {@code 0}, e.g. this method's own null-entry fallback) or
  * non-finite reference OR current magnitude as "fall back to the unscaled 1.0 no-op", so a
  * malformed table degrades to today's exact behavior rather than throwing, producing {@code
  * NaN}, or silently clamping missing data to {@code MIN_GEAR_FORCE_SCALE}.
  */
 private static double gearRatioMagnitudeAt(PartEngine engine, int gear) {
  var gearRatios = engine.definition.engine==null ? null : engine.definition.engine.gearRatios;
  if (gearRatios==null || gearRatios.isEmpty()) return 0;
  int index = Math.max(0, Math.min(gearRatios.size()-1, gear+engine.reverseGears));
  Float ratio = gearRatios.get(index);
  return ratio==null ? 0 : Math.abs(ratio);
 }
 /**
  * MINECRAFT-529 (installed tire-specific grip overrides): the tire/ground-device counterpart to
  * {@link #enginePartDriveForce} immediately above, same deliberate vehicle/part precedence - an
  * explicit part-level {@code friction} override, if THIS SPECIFIC installed tire part has one,
  * REPLACES the vehicle's own resolved {@code friction} for that one wheel's contact outright
  * (not stacked/multiplied with it - a part saying "my own grip is X" is a complete replacement,
  * exactly the same "replacement, not a modifier" precedence {@code driveForce} already
  * established for engine parts); otherwise the vehicle's already-resolved value is used
  * completely unchanged, so an installation with no {@code parts} friction entries at all behaves
  * byte-identically to before this ticket. {@code partKey} is resolved fresh from {@code
  * stepGround}'s own wheel loop every tick (never cached at vehicle-construction time), using the
  * same {@code packID+":"+systemName} key shape {@link #enginePartDriveForce}'s own call site
  * uses for {@code engine.definition} - so each wheel resolves its OWN currently-installed part's
  * override independently, and swapping which part definition is installed on a wheel (a
  * different key) changes which override applies on the very next tick, with no stale caching.
  * {@code Overrides.effectivePartOverride} (unlike {@code resolvedPart}) returns {@code null} -
  * never the flat documented fallback - for a field the part says nothing about, which is
  * exactly the signal needed to tell "no override" apart from "override explicitly set it".
  *
  * <p>Callers must apply this ONLY to a wheel that actually has ground contact this tick (see
  * {@code stepGround}'s own {@code HitResult.Type.MISS} continue, which skips building a {@code
  * WheelHit} at all for an airborne wheel) - a wheel with no contact never reaches this method's
  * call site, so a phantom/spare/airborne tire's configured override can never contribute any
  * grip at all, matching this ticket's explicit "no phantom/airborne contribution" requirement.
  */
 static double tirePartFriction(String partKey, double vehicleFriction) {
  var partOverride = Overrides.effectivePartOverride(partKey);
  return (partOverride != null && partOverride.friction != null) ? partOverride.friction : vehicleFriction;
 }
 /**
  * MINECRAFT-534 (installed-tire rolling resistance overrides): the rolling-resistance
  * counterpart to {@link #tirePartFriction} immediately above, same exact deliberate vehicle/
  * part precedence and "replace, not stack/multiply" semantics - an explicit part-level {@code
  * rollingResistance} override, if THIS SPECIFIC installed tire part has one, REPLACES the
  * vehicle's own resolved {@code rollingResistance} for that one wheel's contact outright;
  * otherwise the vehicle's already-resolved value is used completely unchanged, so an
  * installation with no {@code parts} rollingResistance entries at all behaves byte-identically
  * to before this ticket. {@code partKey} is resolved fresh from {@code stepGround}'s own wheel
  * loop every tick (never cached at vehicle-construction time), the same {@code
  * packID+":"+systemName} key shape {@link #tirePartFriction} uses. {@code
  * Overrides.effectivePartOverride} (unlike {@code resolvedPart}) returns {@code null} - never
  * the flat documented fallback - for a field the part says nothing about, which is exactly the
  * signal needed to tell "no override" apart from "override explicitly set it".
  *
  * <p>Independent of grip - this is a SEPARATE field from {@code friction}/{@code
  * tirePartFriction}, so an installed tire can override one without the other. Callers must
  * apply this ONLY to a wheel that actually has ground contact this tick (same {@code stepGround}
  * {@code HitResult.Type.MISS} continue {@link #tirePartFriction}'s own doc describes) - a
  * phantom/spare/airborne tire's configured override can never contribute any rolling-resistance
  * change, matching this ticket's explicit "no airborne/spare tire influence" requirement.
  */
 static double tirePartRollingResistance(String partKey, double vehicleRollingResistance) {
  var partOverride = Overrides.effectivePartOverride(partKey);
  return (partOverride != null && partOverride.rollingResistance != null) ? partOverride.rollingResistance : vehicleRollingResistance;
 }
 /**
  * MINECRAFT-552 (installed-tire lateral-grip overrides): the lateral-grip counterpart to
  * {@link #tirePartFriction} above, same exact deliberate vehicle/part precedence and "replace,
  * not stack/multiply" semantics - an explicit part-level {@code lateralGripMultiplier} override,
  * if THIS SPECIFIC installed tire part has one, REPLACES the vehicle's own resolved {@code
  * lateralGripMultiplier} for that one wheel's contact outright; otherwise the vehicle's already-
  * resolved value is used completely unchanged, so an installation with no {@code parts}
  * lateralGripMultiplier entries at all behaves byte-identically to before this ticket. {@code
  * partKey} is resolved fresh from {@code stepGround}'s own wheel loop every tick (never cached
  * at vehicle-construction time), the same {@code packID+":"+systemName} key shape {@link
  * #tirePartFriction}/{@link #tirePartRollingResistance} use. {@code
  * Overrides.effectivePartOverride} (unlike {@code resolvedPart}) returns {@code null} - never
  * the flat documented fallback - for a field the part says nothing about, which is exactly the
  * signal needed to tell "no override" apart from "override explicitly set it".
  *
  * <p>Affects ONLY the lateral (sideways slip) semi-axis of that one wheel's own friction
  * ellipse - the longitudinal (drive/brake) semi-axis is untouched, same lateral-only scope the
  * vehicle-level field already documents (see {@code TireForces.frictionAnisotropic}). Callers
  * must apply this ONLY to a wheel that actually has ground contact this tick (same {@code
  * stepGround} {@code HitResult.Type.MISS} continue {@link #tirePartFriction}'s own doc
  * describes) - a phantom/spare/airborne tire's configured override can never contribute any
  * grip change, matching this ticket's explicit "no phantom/airborne contribution" requirement.
  */
 static double tirePartLateralGripMultiplier(String partKey, double vehicleLateralGripMultiplier) {
  var partOverride = Overrides.effectivePartOverride(partKey);
  return (partOverride != null && partOverride.lateralGripMultiplier != null) ? partOverride.lateralGripMultiplier : vehicleLateralGripMultiplier;
 }
 /**
  * Which {@code Overrides.Tuning} fields a currently-active {@code Body}/{@code BuoyantBody}
  * already re-reads fresh from its own live {@code tuning} object every tick/call, rather than
  * caching into a separate field at construction time (ticket 53518/MINECRAFT-505) - confirmed
  * by direct inspection of every {@code tuning.X} read site in this file (stepGround/stepFlight/
  * stepRotor) and in BuoyantForces.apply (waterDrag/propellerForce/angularWaterDrag):
  * suspensionTravel/friction/driveForce/brakeForce/maxSteerDegrees/lateralGripMultiplier/
  * waterDrag/propellerForce/angularWaterDrag/antiRollStiffness (MINECRAFT-519 - stepGround reads
  * {@code tuning.antiRollStiffness} fresh every tick, same as every other field in this list)/
  * stallAngleDegrees/liftSlope/controlSurfaceSlope (MINECRAFT-521, stepFlight only)/
  * aileronAuthorityScale/elevatorAuthorityScale/rudderAuthorityScale (MINECRAFT-541, stepFlight
  * only, same reasoning as stallAngleDegrees/liftSlope/controlSurfaceSlope immediately above)/
  * cyclicLimitDegrees/cyclicAuthorityScale/yawAuthorityScale (MINECRAFT-522, stepRotor only)/
  * rotorAngularDampingCoefficient (MINECRAFT-537, stepRotor only - read fresh every tick via
  * rotorAngularDampingTorque, same as the MINECRAFT-522 fields immediately above)/
  * rotorAirDragCoefficient (MINECRAFT-543, stepRotor only - read fresh every tick via
  * RotorForces.translationalDragForce, same reasoning as rotorAngularDampingCoefficient
  * immediately above)/
  * brakeBiasFront (MINECRAFT-520, same reasoning)/driveBiasFront (MINECRAFT-524, same reasoning -
  * stepGround reads tuning.driveBiasFront fresh every tick via DriveBiasDistribution#shareRatios)/
  * suspensionDampingRatio (MINECRAFT-523, stepGround reads tuning.suspensionDampingRatio fresh
  * every tick via suspensionDamper/suspensionDamperForShare/effectiveMassDamper, same as every
  * other field in this list)/suspensionSpringMultiplier (MINECRAFT-528, stepGround reads
  * tuning.suspensionSpringMultiplier fresh every tick via suspensionSpring/
  * suspensionSpringForShare, same as every other field in this list)/longitudinalWaterDrag
  * Multiplier/lateralWaterDragMultiplier/verticalWaterDragMultiplier (MINECRAFT-535,
  * BuoyantForces.apply reads all three fresh every tick alongside waterDrag itself)/
  * parasiticDragMultiplier/oswaldEfficiency (MINECRAFT-536, stepFlight only, same reasoning as
  * stallAngleDegrees/liftSlope/controlSurfaceSlope above)/fixedWingAngularDampingCoefficient
  * (MINECRAFT-542, stepFlight only - read fresh every tick via rotorAngularDampingTorque, same
  * reasoning as rotorAngularDampingCoefficient above, a wholly separate field)/
  * axleDriveForceSharing (MINECRAFT-532,
  * stepGround reads tuning.axleDriveForceSharing fresh every tick via
  * AxleDriveForceSharing#shareRatios, same as every other field in this list)/ackermannSteering
  * (MINECRAFT-540, stepGround reads tuning.ackermannSteering fresh every tick via
  * AckermannSteeringDistribution#steerRadians, same as every other field in this list)/
  * rollingResistance
  * (MINECRAFT-534, stepGround reads tuning.rollingResistance fresh every tick at the
  * FrictionContact-construction call site, same as every other field in this list)/
  * marineSteerDegrees (MINECRAFT-544, BuoyantForces.apply reads tuning.marineSteerDegrees fresh
  * every tick for propeller thrust direction, same as maxSteerDegrees itself)/
  * suspensionDampingRatioCompression/suspensionDampingRatioRebound (MINECRAFT-551, stepGround
  * reads both fresh every tick via the per-wheel wheelCompressionRatio/wheelReboundRatio
  * computation feeding TireForces.suspension's 5-arg overload, same reasoning as
  * suspensionDampingRatio immediately above)/gearRatioAware (MINECRAFT-553, stepGround reads
  * tuning.gearRatioAware fresh every tick via gearForceScale, same as every other field in this
  * list). Mutating
  * the SAME live Tuning object's one of these fields in place therefore takes effect on that
  * body's very next tick, with no reconstruction at all - true hot reload for exactly this set.
  * Every OTHER field ({@code massKg}, {@code hullFillFactor}, {@code upperMassFraction}, every
  * {@code selfRight*}, {@code enabled}) is read exactly ONCE, at {@code Body}/{@code
  * BuoyantBody}'s own constructor, and baked into a separate cached field ({@code mass},
  * {@code inertiaBody}, {@code hullVolume}, {@code selfRight}, {@code twoBodyConfig}, this
  * vehicle's category eligibility) - changing one of THOSE in the live config file cannot take
  * effect on an already-active body at all; {@link #reloadOverrides} reports those by name
  * instead of silently pretending they applied. Re-spawning the vehicle (or any other event that
  * triggers a fresh {@code Body}/{@code BuoyantBody} construction, e.g. {@code
  * active()}'s existing category-change/chassisBoundsChanged replacement paths) is what actually
  * picks up a new value for one of these - this ticket does not add a new mechanism for that,
  * it only documents the existing one as the correct answer for "how do I make THIS field change
  * live" instead of a caller silently assuming reload alone always finishes the job.
  */
 static void applyLiveSafeFields(Overrides.Tuning target, Overrides.Tuning fresh, java.util.List<String> appliedOut) {
  if (!java.util.Objects.equals(target.suspensionTravel, fresh.suspensionTravel)) { target.suspensionTravel = fresh.suspensionTravel; appliedOut.add("suspensionTravel"); }
  if (!java.util.Objects.equals(target.friction, fresh.friction)) { target.friction = fresh.friction; appliedOut.add("friction"); }
  // MINECRAFT-534: stepGround reads tuning.rollingResistance fresh every tick (at the
  // FrictionContact-construction call site), same "no separate cached field" shape as friction
  // immediately above.
  if (!java.util.Objects.equals(target.rollingResistance, fresh.rollingResistance)) { target.rollingResistance = fresh.rollingResistance; appliedOut.add("rollingResistance"); }
  if (!java.util.Objects.equals(target.driveForce, fresh.driveForce)) { target.driveForce = fresh.driveForce; appliedOut.add("driveForce"); }
  if (!java.util.Objects.equals(target.brakeForce, fresh.brakeForce)) { target.brakeForce = fresh.brakeForce; appliedOut.add("brakeForce"); }
  if (!java.util.Objects.equals(target.maxSteerDegrees, fresh.maxSteerDegrees)) { target.maxSteerDegrees = fresh.maxSteerDegrees; appliedOut.add("maxSteerDegrees"); }
  // MINECRAFT-544: BuoyantForces.apply reads tuning.marineSteerDegrees fresh every tick, same
  // "no separate cached field" shape as maxSteerDegrees immediately above.
  if (!java.util.Objects.equals(target.marineSteerDegrees, fresh.marineSteerDegrees)) { target.marineSteerDegrees = fresh.marineSteerDegrees; appliedOut.add("marineSteerDegrees"); }
  if (!java.util.Objects.equals(target.lateralGripMultiplier, fresh.lateralGripMultiplier)) { target.lateralGripMultiplier = fresh.lateralGripMultiplier; appliedOut.add("lateralGripMultiplier"); }
  if (!java.util.Objects.equals(target.waterDrag, fresh.waterDrag)) { target.waterDrag = fresh.waterDrag; appliedOut.add("waterDrag"); }
  if (!java.util.Objects.equals(target.propellerForce, fresh.propellerForce)) { target.propellerForce = fresh.propellerForce; appliedOut.add("propellerForce"); }
  if (!java.util.Objects.equals(target.angularWaterDrag, fresh.angularWaterDrag)) { target.angularWaterDrag = fresh.angularWaterDrag; appliedOut.add("angularWaterDrag"); }
  if (!java.util.Objects.equals(target.antiRollStiffness, fresh.antiRollStiffness)) { target.antiRollStiffness = fresh.antiRollStiffness; appliedOut.add("antiRollStiffness"); }
  if (!java.util.Objects.equals(target.stallAngleDegrees, fresh.stallAngleDegrees)) { target.stallAngleDegrees = fresh.stallAngleDegrees; appliedOut.add("stallAngleDegrees"); }
  if (!java.util.Objects.equals(target.liftSlope, fresh.liftSlope)) { target.liftSlope = fresh.liftSlope; appliedOut.add("liftSlope"); }
  if (!java.util.Objects.equals(target.controlSurfaceSlope, fresh.controlSurfaceSlope)) { target.controlSurfaceSlope = fresh.controlSurfaceSlope; appliedOut.add("controlSurfaceSlope"); }
  // MINECRAFT-541: stepFlight reads tuning.aileron/elevator/rudderAuthorityScale fresh every
  // tick, same "no separate cached field" shape as stallAngleDegrees/liftSlope/
  // controlSurfaceSlope immediately above.
  if (!java.util.Objects.equals(target.aileronAuthorityScale, fresh.aileronAuthorityScale)) { target.aileronAuthorityScale = fresh.aileronAuthorityScale; appliedOut.add("aileronAuthorityScale"); }
  if (!java.util.Objects.equals(target.elevatorAuthorityScale, fresh.elevatorAuthorityScale)) { target.elevatorAuthorityScale = fresh.elevatorAuthorityScale; appliedOut.add("elevatorAuthorityScale"); }
  if (!java.util.Objects.equals(target.rudderAuthorityScale, fresh.rudderAuthorityScale)) { target.rudderAuthorityScale = fresh.rudderAuthorityScale; appliedOut.add("rudderAuthorityScale"); }
  if (!java.util.Objects.equals(target.cyclicLimitDegrees, fresh.cyclicLimitDegrees)) { target.cyclicLimitDegrees = fresh.cyclicLimitDegrees; appliedOut.add("cyclicLimitDegrees"); }
  if (!java.util.Objects.equals(target.cyclicAuthorityScale, fresh.cyclicAuthorityScale)) { target.cyclicAuthorityScale = fresh.cyclicAuthorityScale; appliedOut.add("cyclicAuthorityScale"); }
  if (!java.util.Objects.equals(target.yawAuthorityScale, fresh.yawAuthorityScale)) { target.yawAuthorityScale = fresh.yawAuthorityScale; appliedOut.add("yawAuthorityScale"); }
  // MINECRAFT-537: stepRotor reads tuning.rotorAngularDampingCoefficient fresh every tick via
  // rotorAngularDampingTorque, same "no separate cached field" shape as cyclicLimitDegrees/
  // cyclicAuthorityScale/yawAuthorityScale immediately above.
  if (!java.util.Objects.equals(target.rotorAngularDampingCoefficient, fresh.rotorAngularDampingCoefficient)) { target.rotorAngularDampingCoefficient = fresh.rotorAngularDampingCoefficient; appliedOut.add("rotorAngularDampingCoefficient"); }
  // MINECRAFT-554: stepRotor reads tuning.rotorAngularDampingCoefficientPitch/Roll/Yaw fresh
  // every tick too, same "no separate cached field" shape as rotorAngularDampingCoefficient
  // immediately above.
  if (!java.util.Objects.equals(target.rotorAngularDampingCoefficientPitch, fresh.rotorAngularDampingCoefficientPitch)) { target.rotorAngularDampingCoefficientPitch = fresh.rotorAngularDampingCoefficientPitch; appliedOut.add("rotorAngularDampingCoefficientPitch"); }
  if (!java.util.Objects.equals(target.rotorAngularDampingCoefficientRoll, fresh.rotorAngularDampingCoefficientRoll)) { target.rotorAngularDampingCoefficientRoll = fresh.rotorAngularDampingCoefficientRoll; appliedOut.add("rotorAngularDampingCoefficientRoll"); }
  if (!java.util.Objects.equals(target.rotorAngularDampingCoefficientYaw, fresh.rotorAngularDampingCoefficientYaw)) { target.rotorAngularDampingCoefficientYaw = fresh.rotorAngularDampingCoefficientYaw; appliedOut.add("rotorAngularDampingCoefficientYaw"); }
  // MINECRAFT-543: stepRotor reads tuning.rotorAirDragCoefficient fresh every tick via
  // RotorForces.translationalDragForce, same "no separate cached field" shape as
  // rotorAngularDampingCoefficient immediately above.
  if (!java.util.Objects.equals(target.rotorAirDragCoefficient, fresh.rotorAirDragCoefficient)) { target.rotorAirDragCoefficient = fresh.rotorAirDragCoefficient; appliedOut.add("rotorAirDragCoefficient"); }
  if (!java.util.Objects.equals(target.brakeBiasFront, fresh.brakeBiasFront)) { target.brakeBiasFront = fresh.brakeBiasFront; appliedOut.add("brakeBiasFront"); }
  if (!java.util.Objects.equals(target.driveBiasFront, fresh.driveBiasFront)) { target.driveBiasFront = fresh.driveBiasFront; appliedOut.add("driveBiasFront"); }
  if (!java.util.Objects.equals(target.suspensionDampingRatio, fresh.suspensionDampingRatio)) { target.suspensionDampingRatio = fresh.suspensionDampingRatio; appliedOut.add("suspensionDampingRatio"); }
  // MINECRAFT-528: stepGround reads tuning.suspensionSpringMultiplier fresh every tick via
  // suspensionSpring/suspensionSpringForShare, same "no separate cached field" shape as
  // suspensionDampingRatio immediately above.
  if (!java.util.Objects.equals(target.suspensionSpringMultiplier, fresh.suspensionSpringMultiplier)) { target.suspensionSpringMultiplier = fresh.suspensionSpringMultiplier; appliedOut.add("suspensionSpringMultiplier"); }
  // MINECRAFT-539: stepGround reads tuning.suspensionDampingRatioFront/Rear and
  // suspensionSpringMultiplierFront/Rear fresh every tick via AxleSuspensionDistribution, same
  // "no separate cached field" shape as suspensionDampingRatio/suspensionSpringMultiplier above.
  if (!java.util.Objects.equals(target.suspensionDampingRatioFront, fresh.suspensionDampingRatioFront)) { target.suspensionDampingRatioFront = fresh.suspensionDampingRatioFront; appliedOut.add("suspensionDampingRatioFront"); }
  if (!java.util.Objects.equals(target.suspensionDampingRatioRear, fresh.suspensionDampingRatioRear)) { target.suspensionDampingRatioRear = fresh.suspensionDampingRatioRear; appliedOut.add("suspensionDampingRatioRear"); }
  if (!java.util.Objects.equals(target.suspensionSpringMultiplierFront, fresh.suspensionSpringMultiplierFront)) { target.suspensionSpringMultiplierFront = fresh.suspensionSpringMultiplierFront; appliedOut.add("suspensionSpringMultiplierFront"); }
  // MINECRAFT-551: stepGround reads tuning.suspensionDampingRatioCompression/Rebound fresh every
  // tick via the per-wheel wheelCompressionRatio/wheelReboundRatio computation, same "no separate
  // cached field" shape as every other suspension field above.
  if (!java.util.Objects.equals(target.suspensionDampingRatioCompression, fresh.suspensionDampingRatioCompression)) { target.suspensionDampingRatioCompression = fresh.suspensionDampingRatioCompression; appliedOut.add("suspensionDampingRatioCompression"); }
  if (!java.util.Objects.equals(target.suspensionDampingRatioRebound, fresh.suspensionDampingRatioRebound)) { target.suspensionDampingRatioRebound = fresh.suspensionDampingRatioRebound; appliedOut.add("suspensionDampingRatioRebound"); }
  if (!java.util.Objects.equals(target.suspensionSpringMultiplierRear, fresh.suspensionSpringMultiplierRear)) { target.suspensionSpringMultiplierRear = fresh.suspensionSpringMultiplierRear; appliedOut.add("suspensionSpringMultiplierRear"); }
  // MINECRAFT-535: BuoyantForces.apply reads tuning.longitudinal/lateral/verticalWaterDragMultiplier
  // fresh every tick, same "no separate cached field" shape as waterDrag/angularWaterDrag above.
  if (!java.util.Objects.equals(target.longitudinalWaterDragMultiplier, fresh.longitudinalWaterDragMultiplier)) { target.longitudinalWaterDragMultiplier = fresh.longitudinalWaterDragMultiplier; appliedOut.add("longitudinalWaterDragMultiplier"); }
  if (!java.util.Objects.equals(target.lateralWaterDragMultiplier, fresh.lateralWaterDragMultiplier)) { target.lateralWaterDragMultiplier = fresh.lateralWaterDragMultiplier; appliedOut.add("lateralWaterDragMultiplier"); }
  if (!java.util.Objects.equals(target.verticalWaterDragMultiplier, fresh.verticalWaterDragMultiplier)) { target.verticalWaterDragMultiplier = fresh.verticalWaterDragMultiplier; appliedOut.add("verticalWaterDragMultiplier"); }
  // MINECRAFT-536: stepFlight reads tuning.parasiticDragMultiplier/tuning.oswaldEfficiency fresh
  // every tick, same "no separate cached field" shape as stallAngleDegrees/liftSlope/
  // controlSurfaceSlope immediately above - both are safe to hot-mutate in place.
  if (!java.util.Objects.equals(target.parasiticDragMultiplier, fresh.parasiticDragMultiplier)) { target.parasiticDragMultiplier = fresh.parasiticDragMultiplier; appliedOut.add("parasiticDragMultiplier"); }
  if (!java.util.Objects.equals(target.oswaldEfficiency, fresh.oswaldEfficiency)) { target.oswaldEfficiency = fresh.oswaldEfficiency; appliedOut.add("oswaldEfficiency"); }
  // MINECRAFT-542: stepFlight reads tuning.fixedWingAngularDampingCoefficient fresh every tick
  // via rotorAngularDampingTorque, same "no separate cached field" shape as
  // rotorAngularDampingCoefficient (MINECRAFT-537, stepRotor only) immediately above.
  if (!java.util.Objects.equals(target.fixedWingAngularDampingCoefficient, fresh.fixedWingAngularDampingCoefficient)) { target.fixedWingAngularDampingCoefficient = fresh.fixedWingAngularDampingCoefficient; appliedOut.add("fixedWingAngularDampingCoefficient"); }
  // MINECRAFT-533: stepGround reads tuning.toeFrontDegrees/toeRearDegrees fresh every tick via
  // AxleToeDistribution.toeRadians, same "no separate cached field" shape as maxSteerDegrees/
  // brakeBiasFront/driveBiasFront above - no respawn needed for either field.
  if (!java.util.Objects.equals(target.toeFrontDegrees, fresh.toeFrontDegrees)) { target.toeFrontDegrees = fresh.toeFrontDegrees; appliedOut.add("toeFrontDegrees"); }
  if (!java.util.Objects.equals(target.toeRearDegrees, fresh.toeRearDegrees)) { target.toeRearDegrees = fresh.toeRearDegrees; appliedOut.add("toeRearDegrees"); }
  // MINECRAFT-532: stepGround reads tuning.axleDriveForceSharing fresh every tick via
  // AxleDriveForceSharing#shareRatios, same "no separate cached field" shape as driveBiasFront.
  if (!java.util.Objects.equals(target.axleDriveForceSharing, fresh.axleDriveForceSharing)) { target.axleDriveForceSharing = fresh.axleDriveForceSharing; appliedOut.add("axleDriveForceSharing"); }
  // MINECRAFT-540: stepGround reads tuning.ackermannSteering fresh every tick via
  // AckermannSteeringDistribution#steerRadians, same "no separate cached field" shape as
  // axleDriveForceSharing immediately above.
  if (!java.util.Objects.equals(target.ackermannSteering, fresh.ackermannSteering)) { target.ackermannSteering = fresh.ackermannSteering; appliedOut.add("ackermannSteering"); }
  // MINECRAFT-553: stepGround reads tuning.gearRatioAware fresh every tick via gearForceScale,
  // same live-reload shape as axleDriveForceSharing/ackermannSteering immediately above.
  if (!java.util.Objects.equals(target.gearRatioAware, fresh.gearRatioAware)) { target.gearRatioAware = fresh.gearRatioAware; appliedOut.add("gearRatioAware"); }
 }
 /** The complementary set to {@link #applyLiveSafeFields} - never mutates {@code target}, only
  * reports (by field name) which of the construction-cached fields differ from {@code fresh} so
  * {@link #reloadOverrides}'s caller can tell the operator "X needs a respawn" instead of either
  * silently doing nothing or wrongly claiming everything applied. */
 static void reportFieldsNeedingRespawn(Overrides.Tuning target, Overrides.Tuning fresh, java.util.Set<String> pendingOut) {
  if (!java.util.Objects.equals(target.massKg, fresh.massKg)) pendingOut.add("massKg");
  if (!java.util.Objects.equals(target.hullFillFactor, fresh.hullFillFactor)) pendingOut.add("hullFillFactor");
  if (!java.util.Objects.equals(target.upperMassFraction, fresh.upperMassFraction)) pendingOut.add("upperMassFraction");
  if (!java.util.Objects.equals(target.enabled, fresh.enabled)) pendingOut.add("enabled");
  boolean selfRightChanged = !java.util.Objects.equals(target.selfRightStrength, fresh.selfRightStrength)
    || !java.util.Objects.equals(target.selfRightDamping, fresh.selfRightDamping)
    || !java.util.Objects.equals(target.selfRightMaxTorque, fresh.selfRightMaxTorque)
    || !java.util.Objects.equals(target.selfRightFadeStartDegrees, fresh.selfRightFadeStartDegrees)
    || !java.util.Objects.equals(target.selfRightFadeEndDegrees, fresh.selfRightFadeEndDegrees);
  if (selfRightChanged) pendingOut.add("selfRight*");
 }
 /**
  * Outcome of {@link #reloadOverrides} - {@code configResult} is {@code Overrides.reload()}'s
  * own success/failure/last-known-good report; {@code vehiclesUpdatedLive} counts how many
  * currently-active vehicle bodies actually had at least one field change live; {@code
  * fieldsNeedingRespawn} names every field (deduplicated, across every active vehicle) that
  * changed in the new config but could NOT be applied without reconstructing that vehicle's
  * body - the ticket's explicit "document any properties needing respawn" requirement. Empty
  * when {@code configResult.success()} is false (nothing was touched at all).
  */
 public record ReloadReport(Overrides.ReloadResult configResult, int vehiclesUpdatedLive, java.util.Set<String> fieldsNeedingRespawn) {}
 /**
  * The actual hot-reload entry point (ticket 53518/MINECRAFT-505) - re-reads/validates the
  * operator config via {@link Overrides#reload}, and on success, propagates it into every
  * currently-active {@code Body}/{@code BuoyantBody} (both implement {@code VehicleBody}, so
  * this needs no per-domain special-casing) by re-resolving that vehicle's own tuning and
  * live-mutating the fields {@link #applyLiveSafeFields} says are safe - never touching pose,
  * velocity, hitches, or any other part of a vehicle's live physics state, exactly the "preserve
  * pose/velocity/topology" requirement, since nothing about construction/replacement runs here
  * at all. On a failed {@code Overrides.reload()} (bad JSON, out-of-range field, bad {@code
  * extends}), this makes NO changes to any active body either - last-known-good propagates all
  * the way through, not just at the config layer.
  */
 public static ReloadReport reloadOverrides() {
  var configResult = Overrides.reload();
  if (!configResult.success()) return new ReloadReport(configResult, 0, java.util.Set.of());
  int[] updatedCount = {0};
  var pendingFields = new java.util.TreeSet<String>();
  registry.forEachActive((v, body) -> {
   JSONVehicle definition = v.definition;
   if (definition == null) return;
   Overrides.Tuning fresh = tuning(definition);
   Overrides.Tuning live = body.tuning();
   var applied = new java.util.ArrayList<String>();
   applyLiveSafeFields(live, fresh, applied);
   reportFieldsNeedingRespawn(live, fresh, pendingFields);
   if (!applied.isEmpty()) updatedCount[0]++;
  });
  return new ReloadReport(configResult, updatedCount[0], pendingFields);
 }
 /** Any ground-contact device (wheel or track) the generalized suspension/friction solver can drive. */
 private static List<PartGroundDevice> groundContacts(EntityVehicleF_Physics v) {
  return v.allParts.stream().filter(p -> p instanceof PartGroundDevice g && g.definition != null
      && Classification.isDrivableGroundDevice(g.definition.ground))
    .map(p -> (PartGroundDevice)p).toList();
 }
 /** Every currently-installed, non-spare ground device's JSON definition, recomputed fresh from
  * the vehicle's current parts each time it is called. Spares carry no hull/ground support of
  * their own (MTS's own VehicleGroundDeviceBox excludes them from support the same way); a
  * spare must not count toward either domain's eligibility. */
 private static List<JSONPart.JSONPartGroundDevice> installedGroundDevices(EntityVehicleF_Physics v) {
  return v.allParts.stream().filter(p -> p instanceof PartGroundDevice g && !g.isSpare && g.definition != null)
    .map(p -> ((PartGroundDevice)p).definition.ground).toList();
 }
 /** Every currently-installed, non-spare propeller's JSON definition, recomputed fresh from the
  * vehicle's current parts each time it is called - never cached, so a rotor added/removed/
  * replaced after activation is reflected on the very next tick. A spare rotor must not
  * suppress or grant eligibility: it isn't an operational part of the chassis (same reasoning
  * as excluding spare ground devices above). */
 private static List<JSONPart.JSONPartPropeller> installedPropellers(EntityVehicleF_Physics v) {
  return v.allParts.stream().filter(p -> p instanceof PartPropeller pr && !pr.isSpare && pr.definition != null)
    .map(p -> ((PartPropeller)p).definition.propeller).toList();
 }
 /** Whether this vehicle has any installed, non-spare canFloat ground device, regardless of
  * what other domain (ground/flight) it is also eligible for - the amphibious/float-equipped-
  * flight-vehicle combined-forces case PhysicsBridge.Body's step() handles alongside its
  * primary domain. */
 private static boolean hasFloatDevice(EntityVehicleF_Physics v) {
  return hasFloatDevice(installedGroundDevices(v));
 }
 /** The pure decision {@link #hasFloatDevice(EntityVehicleF_Physics)} delegates to, over the
  * same already-live-filtered (non-spare) JSON list {@link #installedGroundDevices} produces -
  * extracted so a float device installed or removed through this exact seam, not a hand-picked
  * boolean, is provable without a live entity/Sable runtime (see {@code PhysicsBridgeStepperTest}),
  * the same way {@link #selectStepper} is tested over a mutable propeller list. */
 static boolean hasFloatDevice(List<JSONPart.JSONPartGroundDevice> installedGroundDevices) {
  return installedGroundDevices.stream().anyMatch(Classification::isFloatDevice);
 }
 /** Which force model {@code Body.step} dispatches to this tick - the literal decision it makes,
  * extracted so the "live rotor add/remove switches solvers" behavior is testable without a
  * live entity/Sable runtime (see {@code PhysicsBridgeStepperTest}), the same way
  * {@link #activateOrClassify} extracts {@code active()}'s own failure-classification boundary. */
 enum Stepper { GROUND, FLIGHT, ROTOR }
 /** An installed rotor propeller always wins dispatch, independent of {@code flight}
  * ({@code motorized.isAircraft}/{@code isBlimp}): review found a vehicle with an actual
  * installed {@code isRotor} propeller but no aircraft/blimp flag was previously stuck on
  * GROUND (or deactivated outright), its rotor never getting lift/control - rotor physics must
  * be driven by the actual installed part, not gated on a definition flag it doesn't need. */
 static Stepper selectStepper(boolean flight, List<JSONPart.JSONPartPropeller> installedPropellers) {
  if (Classification.isRotorcraft(installedPropellers)) return Stepper.ROTOR;
  return flight ? Stepper.FLIGHT : Stepper.GROUND;
 }
 /** The full per-tick plan {@code Body.step} composes: which primary force model to run, and
  * whether to additionally apply {@link BuoyantForces} this tick (independent of which primary
  * model ran - {@code floatCapable} is re-read from the vehicle's live parts every tick by the
  * caller, never cached, so installing/removing a float device takes effect on the next tick).
  * Extracted as its own testable collaborator so the
  * rotor+float composition (a floating rotorcraft gets both ROTOR's own thrust and buoyancy on
  * its float devices, with rotor thrust never double-counted by {@code BuoyantForces}'s own
  * underwater-propeller pass) is provable without a live entity/Sable runtime - see
  * {@code PhysicsBridgeStepperTest}. */
 record TickPlan(Stepper stepper, boolean appliesBuoyancy) {}
 /** Only the ground force path supports the split chassis. Explicit opt-out still wins. */
 static boolean useTwoBody(boolean enabled, TickPlan plan) {
  return enabled && plan.stepper() == Stepper.GROUND && !plan.appliesBuoyancy();
 }
 static TickPlan planTick(boolean flight, boolean floatCapable, List<JSONPart.JSONPartPropeller> installedPropellers) {
  return new TickPlan(selectStepper(flight, installedPropellers), floatCapable);
 }
 static InheritedRigidBodyVelocity captureReplacementState(EntityVehicleF_Physics v,
   BoxPhysicsObject box, RigidBodyHandle handle, Vector3d center, boolean twoBodyActive, double halfHeight) {
   box.updatePose();
   // Preserve the latest native pose as well as velocity, even if no entity step has synced it.
   var replacementPose = box.getPose();
   var replacementOrientation = new Quaterniond(replacementPose.orientation());
   var replacementCenter = new Vector3d(replacementPose.position());
   if (twoBodyActive)
    replacementCenter.set(io.github.brooswitminecraft.div.experimental.TwoBodyChassis.vehicleWorldPositionFromLowerBody(
      replacementCenter, replacementOrientation, halfHeight));
   var replacementOrigin = replacementCenter.sub(replacementOrientation.transform(new Vector3d(center)));
   v.position.set(replacementOrigin.x, replacementOrigin.y, replacementOrigin.z);
   Matrix3d replacementMatrix = new Matrix3d().set(replacementOrientation);
   var orientation = v.orientation;
   orientation.m00=replacementMatrix.m00(); orientation.m01=replacementMatrix.m10(); orientation.m02=replacementMatrix.m20();
   orientation.m10=replacementMatrix.m01(); orientation.m11=replacementMatrix.m11(); orientation.m12=replacementMatrix.m21();
   orientation.m20=replacementMatrix.m02(); orientation.m21=replacementMatrix.m12(); orientation.m22=replacementMatrix.m22();
   orientation.convertToAngles();
   return new InheritedRigidBodyVelocity(
     handle.getAngularVelocity(new Vector3d()),
     handle.getLinearVelocity(new Vector3d()),
     new Vector3d(box.getPose().position()));
 }
 /**
  * {@code registry.activateOnce} deliberately reuses an already-active body without
  * reconstructing it (the "no double physics" guarantee) - but a vehicle's installed parts can
  * change which body category it needs live (a rotor installed on a previously float-only hull
  * needs a ground/flight/rotor {@code Body} instead of its {@code BuoyantBody}, or the reverse
  * when a rotor is removed from a rotor-only hull that has no wheels), and that reuse would
  * otherwise silently keep running the wrong - or no - force model for the vehicle's actual
  * current parts. Generic over {@code K}/{@code B} (not tied to {@code EntityVehicleF_Physics}/
  * {@code VehicleBody}) so this lifecycle fix is directly testable with fake keys/bodies, the
  * same way {@link #activateOrClassify} is - see {@code PhysicsBridgeBodyReplacementTest}.
  */
 static <K, B> void replaceIfCategoryChanged(BodyRegistry<K, B> registry, K key, boolean neededGroundCategory,
     java.util.function.Predicate<B> isGroundCategory, Consumer<B> remove) {
  B existing = registry.get(key);
  if (existing != null && isGroundCategory.test(existing) != neededGroundCategory) {
   B old = registry.deactivate(key);
   if (old != null) remove.accept(old);
  }
 }
 public static boolean active(EntityVehicleF_Physics v) {
  // Guard every dereference used to decide eligibility, in entry order, before Classification
  // (or anything else) ever sees them: a vehicle with no definition, or with a ground part that
  // itself has no definition, must be excluded here rather than throw.
  JSONVehicle definition = v.definition;
  boolean isClient = v.world.isClient();
  if (definition == null) { GroundContactDiagnostics.logEarlyReturn(v.uniqueUUID, isClient, "noDefinition"); return deactivate(v); }
  String key = definition.packID + ":" + definition.systemName;
  var installedGroundDevices = installedGroundDevices(v);
  var installedPropellers = installedPropellers(v);
  TowingConnection towedByConnection = v.towedByConnection;
  boolean towed = towedByConnection != null, optedOut = Overrides.isOptedOut(key);
  // Hitch coupling (and the ordering wait/rollback it needs) now applies to the float-only
  // domain too (MINECRAFT-545): BuoyantBody implements HitchAnchor exactly like the ground/
  // flight/rotor Body does, so floatEligible no longer excludes an actively-towed hull - see
  // Classification's class doc.
  boolean ground = Classification.eligible(definition, installedGroundDevices, installedPropellers, optedOut);
  boolean floating = !ground && Classification.floatEligible(definition, installedGroundDevices, installedPropellers, towed, optedOut);
  if (brokenDefinitions.contains(definition)) { GroundContactDiagnostics.logEarlyReturn(v.uniqueUUID, isClient, "brokenDefinition:"+key); return deactivate(v); }
  if (!ground && !floating) { GroundContactDiagnostics.logEarlyReturn(v.uniqueUUID, isClient, "ineligible:"+key); return deactivate(v); }
  replaceIfCategoryChanged(registry, v, ground, existing -> existing instanceof Body, old -> { pendingInheritedVelocity.stage(v, old.captureReplacementState(v)); hitches.release(v); old.remove(); });
  // Geometry and installed-part mode changes replace the existing native assembly before
  // activation. Capture current native pose/velocity first; never run two force owners.
  if (registry.get(v) instanceof Body existingBody && (existingBody.chassisBoundsChanged(v)
      || existingBody.twoBodyActive != useTwoBody(Body.EXPERIMENTAL_TWO_BODY_CHASSIS,
          planTick(Classification.isFlightVehicle(definition), hasFloatDevice(installedGroundDevices), installedPropellers)))) {
   // Lower-half rigid motion is the assembly reference (the fixed joint constrains both
   // halves). Re-seed each new center with v + omega x offset, retaining state on failure.
   pendingInheritedVelocity.stage(v, existingBody.captureReplacementState(v));
   var old = registry.deactivate(v);
   if (old != null) { hitches.release(v); old.remove(); }
  }
  boolean alreadyActive = registry.isActive(v);
  // No `ground &&` gate any more (MINECRAFT-545): a towed float-only hull now waits for its
  // towing vehicle's own hitchable body exactly like a towed ground/flight/rotor vehicle does.
  // `ground || floating` is always true here (the deactivate return above already excludes
  // neither), so `decide` alone (its own isClient/towed handling already matches the previous
  // per-branch ternary exactly) replaces it.
  // MINECRAFT-545/547 (John's comment 54237/54256): `towingHasHitchableBody` alone used to BE
  // `towingVehicleActive` - but that conflated two different reasons a trailer's joint can't
  // reconcile right now: the towing vehicle simply has no Sable body yet/anymore (transient -
  // MINECRAFT-498's own regression fix below is exactly about THIS reason, and still applies to
  // it), versus the towing vehicle DOES have a body but `unsafeBuoyantAngularLock` refuses this
  // specific pair. For an already-active trailer, `deferToNativeHitch` previously never deferred
  // for EITHER reason - correct for the first (free-floating on Sable while waiting for a body to
  // reappear is the intended, reviewed behavior), but wrong for the second: `unsafeBuoyantAngularLock`'s
  // own doc says it "falls back to IV's native hitch for that pair" - the SAME native fallback a
  // first-activation wait gets - not "stay Sable-authoritative, unjointed, with towedByConnection
  // still pointing at a pair this bridge itself judged unsafe." `unsafeLock` is split out so
  // `deferToNativeHitch` can defer for this reason regardless of `alreadyActive`, while still
  // never deferring an already-active trailer merely because its towing vehicle has no body at all.
  boolean towingHasHitchableBody = towed && isHitchableBody(towedByConnection.towingVehicle);
  boolean unsafeLock = towingHasHitchableBody && unsafeBuoyantAngularLock(registry.get(towedByConnection.towingVehicle) instanceof BuoyantBody, floating,
      towedByConnection.hitchConnection.mounted, towedByConnection.hitchConnection.restricted);
  boolean towingVehicleActive = towingHasHitchableBody && !unsafeLock;
  // MINECRAFT-545/547 diagnostic (John's comment 54250: trace actual tick-relative cleanup/
  // remove/joint-release/step ordering, not just a before/after sample) - log-only, every tick a
  // towed vehicle's own active() recomputes this decision, so the exact tick `isHitchableBody`
  // starts/stops seeing a just-invalidated towing vehicle's still-registered body is visible
  // directly, rather than inferred from cleanup()'s own registration order.
  if (towed && GroundContactDiagnostics.ENABLED) {
   com.mojang.logging.LogUtils.getLogger().info("[DIV lifecycleDiagnostic] v=" + v.uniqueUUID + " towingVehicle=" + towedByConnection.towingVehicle.uniqueUUID
     + " towingValid=" + towedByConnection.towingVehicle.isValid + " isHitchableBody=" + isHitchableBody(towedByConnection.towingVehicle)
     + " alreadyActive=" + alreadyActive + " towingVehicleActive=" + towingVehicleActive + " unsafeLock=" + unsafeLock);
  }
  var decision = decide(true, isClient, towed, alreadyActive, towingVehicleActive, unsafeLock);
  GroundContactDiagnostics.logClassification(v.uniqueUUID, isClient, key, ground, floating, decision.toString());
  if (decision == ActivationDecision.DEFER_TO_NATIVE) return deactivate(v);
  if (decision == ActivationDecision.CLIENT_ACTIVE) return true;
  var level = ((WorldAccessor)v.world).div$level();
  if (!(level instanceof ServerLevel server) || SubLevelContainer.getContainer(server) == null) {
   GroundContactDiagnostics.logEarlyReturn(v.uniqueUUID, isClient, "noServerLevelOrContainer:"+key);
   return false;
  }
  Supplier<VehicleBody> construct = ground
    ? () -> new Body(server,v,tuning(definition))
    : () -> new BuoyantBody(server,v,tuning(definition));
  boolean activated = activateOrClassify(registry, brokenDefinitions, v, definition, GroundContactDiagnostics.instrumentedConstruct(construct, v.uniqueUUID, isClient, key));
  // MINECRAFT-545: hitch reconciliation now runs for a float-only hull too, not just `ground`.
  if (!activated) return activated;
  // Evaluated BEFORE reconcileHitch runs: HitchRegistry.reconcile already clears any existing
  // entry - valid/matching or not - before attempting to (re)create one, so this is the only
  // point that can still tell whether the trailer entered this attempt coherently jointed to its
  // actual current desired partner (towingVehicleActive already recomputes the identical
  // isHitchableBody check reconcileHitch's own `desired` uses, so this mirrors it exactly rather
  // than duplicating a second, possibly-diverging copy of that check) - see
  // reconcileOrRollBack/rollBackOnHitchFailure and HitchRegistry.isCoherentlyJointedTo for why
  // that, not alreadyActive or mere joint-map membership, is the fact that actually matters.
  EntityVehicleF_Physics desired = towingVehicleActive ? towedByConnection.towingVehicle : null;
  return reconcileOrRollBack(registry, hitches, v, hitches.isCoherentlyJointedTo(v, desired), () -> reconcileHitch(v, towedByConnection), VehicleBody::remove);
 }
 /** Whether the given towing vehicle's own active VehicleBody exposes {@link HitchAnchor} - a
  * ground/flight/rotor {@code Body} or (MINECRAFT-545) a float-only {@link BuoyantBody} - rather
  * than no body at all. */
 private static boolean isHitchableBody(EntityVehicleF_Physics towingVehicle) {
  return registry.get(towingVehicle) instanceof HitchAnchor;
 }
 /**
  * MINECRAFT-545 safety gate: a {@code mounted} or {@code restricted} angular lock between TWO
  * independently-buoyant hulls is a confirmed-live runaway instability, not a hypothesis -
  * {@code BuoyantHitchProbe}'s own earlier live run settled cleanly to a sub-0.003-block anchor
  * distance, then diverged to 35+ blocks within ~10 ticks and crashed {@code BuoyantBody.step}
  * with a {@code NullPointerException} once the ejected body left Sable's own active region (see
  * VERIFICATION.md's "Float-only hull hitch support" section and MINECRAFT-547).
  * Each float's own independent righting torque fights a partial or full angular lock against
  * the OTHER float's independent righting torque - a resonance a ground vehicle's own fixed-to-
  * the-world suspension does not have, and that root cause is unrelated to which side is towing
  * vs towed. Refusing this one combination - exactly the two conditions {@code lockedAxes} ever
  * reads ({@code mounted}/{@code restricted}) AND both active bodies being {@code BuoyantBody} -
  * falls back to IV's native hitch for that pair specifically, precisely the SAME behavior every
  * towed float had before this ticket, rather than silently sending a known-unsafe configuration
  * into production merely because the generalized {@code HitchAnchor} surface now compiles for
  * it. An ordinary (unmounted, unrestricted) connection between two floats, or ANY connection
  * where at least one side is not a {@code BuoyantBody} (ground/flight/rotor Body towing or
  * being towed by a float), is unaffected - both are proven safe by this ticket's own live
  * evidence.
  */
 static boolean unsafeBuoyantAngularLock(boolean towingIsBuoyant, boolean towedIsBuoyant, boolean mounted, boolean restricted) {
  return towingIsBuoyant && towedIsBuoyant && (mounted || restricted);
 }
 /**
  * The exact reconcile-then-rollback composition {@code active()} performs once its body is
  * already active (freshly constructed this call, or kept from a previous tick): runs
  * {@code reconcile}, and on failure, rolls back - releasing {@code key}'s hitch-registry entry
  * and removing its body via {@code removeBody} - only if {@link #rollBackOnHitchFailure} says
  * {@code key} did not already enter this attempt coherently jointed ({@code wasJointed} must be
  * {@link HitchRegistry#isCoherentlyJointedTo}, not mere map membership - see
  * {@link #rollBackOnHitchFailure}'s own doc for why). Generic over the body
  * registry's key/value types and the hitch registry's key type so it is testable with fake
  * bodies/joints/removal callbacks instead of a live entity/Sable runtime - {@code active()}
  * calls this exact method with its own real {@code registry}/{@code hitches}/
  * {@code VehicleBody::remove}. Returns whether the vehicle remains Sable-active after this call.
  */
 static <K, B> boolean reconcileOrRollBack(BodyRegistry<K, B> bodyRegistry, HitchRegistry<K> hitchRegistry,
     K key, boolean wasJointed, BooleanSupplier reconcile, Consumer<B> removeBody) {
  if (reconcile.getAsBoolean() || !rollBackOnHitchFailure(wasJointed)) return true;
  B old = bodyRegistry.deactivate(key);
  hitchRegistry.release(key);
  if (old != null) removeBody.accept(old);
  return false;
 }
 /**
  * Whether a failed hitch-reconcile attempt must roll back (deactivate/remove) the body
  * {@code active()} is holding, pulled out as a pure boolean seam. {@code wasJointed} must be
  * {@link HitchRegistry#isCoherentlyJointedTo} - a still-valid handle to exactly the current
  * desired partner, evaluated BEFORE this attempt - not whether its body merely already existed,
  * and not mere joint-map membership either: both weaker facts can be true for a joint
  * {@link HitchRegistry#reconcile} is about to tear down anyway (an invalid handle, or one
  * pointed at a different partner) before even attempting the replacement whose failure this
  * seam is reacting to. A body with no coherent joint entering this attempt - brand new this
  * call, an existing body being hitched for the first time, or one whose prior joint was already
  * stale/mismatched - that then fails its hitch attempt is neither coupled nor on native
  * fallback - {@code active()} returning true for it has already suppressed IV's own native
  * hitch-following - so leaving it live, unjointed, would strand it with no authority moving it
  * at all. (Two self-review findings, MINECRAFT-498 post-integration: the first version of this
  * check used {@code alreadyActive} instead, wrongly preserving a body merely because it
  * predated this tick's first hitch attempt; the second used plain {@code isJointed} instead,
  * wrongly treating a joint {@code reconcile} was about to invalidate-and-replace as equivalent
  * to a currently healthy one.) Given the corrected fact, an already-coherently-jointed trailer
  * can never actually reach this seam's rollback branch in production at all -
  * {@link HitchRegistry#reconcile} only ever calls its {@code create} supplier (the only thing
  * that can throw) when the trailer is NOT already coherently jointed to {@code desired} - but
  * the parameter is kept (rather than deleting this function) since it still documents and
  * directly tests the intended contract: rollback happens whenever there was no prior coherent
  * joint to fall back on, full stop.
  */
 static boolean rollBackOnHitchFailure(boolean wasJointed) {
  return !wasJointed;
 }
 private static boolean deactivate(EntityVehicleF_Physics v) {
  VehicleBody old = registry.deactivate(v);
  if (old != null) { hitches.release(v); old.remove(); }
  // Review 53388: this vehicle may never successfully reconstruct after a staged
  // chassisBoundsChanged rebuild (e.g. it becomes ineligible, or is simply removed from the
  // world) - without this, pendingInheritedVelocity would hold a STRONG reference to `v` (an
  // IdentityHashMap key) forever, leaking the entity itself, not just stale velocity data.
  pendingInheritedVelocity.cleanup(v);
  return false;
 }
 enum ActivationDecision { INELIGIBLE, CLIENT_ACTIVE, DEFER_TO_NATIVE, ACTIVATE }
 /**
  * The full activation decision {@code active()} applies, pulled out as a pure seam testable
  * without a live entity/world/registry. The client branch is resolved BEFORE the server-only
  * hitch-ordering wait, and deliberately ignores {@code alreadyActive}/{@code towingVehicleActive}
  * entirely: the body and hitch registries are populated only by server-side code (inside the
  * {@code !isClient} branch of {@code active()}), so a client-side towed entity's own registry
  * lookups are permanently false regardless of the server's real state. Applying
  * {@code deferToNativeHitch} to the client the same way as the server would make a client-side
  * towed vehicle defer to IV's native hitch code forever, even once the server has actually
  * jointed it - the client would keep running IV's own force/movement path while the server
  * independently runs Sable, a single-authority violation this bridge is required not to have.
  * This was a real regression found by external review: an earlier revision evaluated the
  * hitch-ordering wait before the client/server branch instead of after it.
  */
 static ActivationDecision decide(boolean eligible, boolean isClient, boolean towed, boolean alreadyActive, boolean towingVehicleActive, boolean unsafeLock) {
  if (!eligible) return ActivationDecision.INELIGIBLE;
  if (isClient) return ActivationDecision.CLIENT_ACTIVE;
  if (deferToNativeHitch(towed, alreadyActive, towingVehicleActive, unsafeLock)) return ActivationDecision.DEFER_TO_NATIVE;
  return ActivationDecision.ACTIVATE;
 }
 /**
  * Whether an otherwise-eligible towed vehicle must still defer to IV's native hitch code this
  * tick rather than get (or keep) a Sable body - the server-only half of the decision
  * {@code decide()} applies, pulled out as its own pure boolean seam. Only a vehicle that is
  * {@code towed} can ever defer. On FIRST activation ({@code !alreadyActive}), it defers until
  * its towing vehicle has its own active body to joint against - a transient ordering wait, not
  * a permanent exclusion. An ALREADY-active trailer (its own Sable body already exists) must
  * never be deferred/torn down just because its towing vehicle's body has since gone away - it
  * stays an unjointed, free-rolling Sable vehicle instead; only its stale joint, not its body, is
  * {@code reconcileHitch}'s job to release in that case. This was a real regression caught by
  * self-review before this ticket's first external review: an earlier revision applied this wait
  * unconditionally, deactivating (destroying) an already-active trailer's body the moment its
  * towing vehicle's body disappeared.
  *
  * <p>MINECRAFT-545/547 (John's comment 54237/54256): {@code unsafeLock} is a SEPARATE reason
  * {@code towingVehicleActive} can be false - the towing vehicle DOES have a body, but
  * {@code PhysicsBridge.unsafeBuoyantAngularLock} refuses this specific pair - and unlike "the
  * towing vehicle simply has no body (yet/anymore)", that gate's own doc says it should fall back
  * to IV's native hitch for the pair, exactly like a first-activation wait does. Without this,
  * an ALREADY-active trailer caught by the gate stayed Sable-authoritative and unjointed forever
  * (the {@code !alreadyActive} half above still true), with {@code towedByConnection} still
  * pointing at a pair this bridge itself judged unsafe and IV's own native hitch code never
  * getting a chance to run - the real gap this fixes. {@code unsafeLock} on its own, independent
  * of {@code alreadyActive}, now also triggers the defer (and so the {@code deactivate(v)} path
  * in {@code active()}, which actually removes the Sable body and releases the joint - the SAME
  * native-fallback mechanism a first-activation wait already uses, not a new one).
  */
 static boolean deferToNativeHitch(boolean towed, boolean alreadyActive, boolean towingVehicleActive, boolean unsafeLock) {
  return towed && !towingVehicleActive && (!alreadyActive || unsafeLock);
 }
 /**
  * Brings this trailer's hitch joint in line with {@code connection}: builds one against its
  * towing vehicle's active body if it does not have one yet (or had one to a different towing
  * vehicle, or its old joint went invalid), removes a stale one if it is no longer towed (or its
  * towing vehicle is no longer active), and does nothing otherwise. Called every tick after
  * {@code active()} confirms this vehicle (towed or not) already has a live Sable body. Returns
  * {@code false} if joint construction itself failed (e.g. Sable's own anchor/configuration
  * validation) rather than letting the exception escape into the vehicle's own tick -
  * {@link HitchRegistry#reconcile} guarantees no partial joint/partner state is recorded for a
  * failed attempt either way. The caller ({@code active()}) is the one that decides what a
  * {@code false} result means for the body: {@link #rollBackOnHitchFailure} rolls back a body
  * that was JUST constructed this call (there is no coherent state - neither coupled nor on
  * native fallback - for a brand new, never-jointed body to sit in), but leaves an
  * already-healthy body from a previous tick alone, retried next tick - not cached as
  * permanently broken either way, since the anchors are computed from live poses that can change
  * every tick, unlike a chassis definition's fixed geometry.
  */
 private static boolean reconcileHitch(EntityVehicleF_Physics v, TowingConnection connection) {
  EntityVehicleF_Physics desired = connection != null && isHitchableBody(connection.towingVehicle)
    && !unsafeBuoyantAngularLock(registry.get(connection.towingVehicle) instanceof BuoyantBody, registry.get(v) instanceof BuoyantBody,
        connection.hitchConnection.mounted, connection.hitchConnection.restricted)
    ? connection.towingVehicle : null;
  try {
   // MINECRAFT-545: either side can now be a ground/flight/rotor Body or a float-only
   // BuoyantBody - createHitch only needs each side's HitchAnchor surface.
   hitches.reconcile(v, desired, () -> createHitch((HitchAnchor)registry.get(desired), (HitchAnchor)registry.get(v), connection));
   return true;
  } catch (RuntimeException transientFailure) {
   return false;
  }
 }
 /**
  * MINECRAFT-525: {@code pos1}/{@code pos2} are the confirmed BODY-LOCAL anchor offsets (ticket
  * comment 53256/53243's real-native-source-plus-empirical-probe finding, already applied to
  * {@code TwoBodyLifecycle.create}'s own joint) rather than the world-space points this method
  * previously computed via {@code HitchJoint.anchorWorldPosition}. {@code local_frame1}/
  * {@code local_frame2} are LOCAL joint-frame rotations, not an echo of each body's own world
  * orientation (also confirmed wrong by the same finding) - but review 53817/53832 caught that
  * this is NOT simply "always identity" either, and that a naive direct pass-through of each
  * side's own {@code rot} gets the SIGN wrong for a mounted/restricted connection:
  *
  * <p>Sable's fixed/restricted joint enforces coincidence of each side's own local frame in
  * world space - {@code Q_towing * local_frame1 == Q_towed * local_frame2}, i.e.
  * {@code Q_towed = Q_towing * local_frame1 * inverse(local_frame2)} (review 53832's own stated
  * relation for this constraint, independently exercised by {@code HitchRotationFrameProbe}
  * below). The pinned IV24 jar's OWN native composition - decompiled directly from
  * {@code EntityVehicleF_Physics.getForcesAndMotions}'s mounted/restricted branch (consecutive
  * {@code rotation.set(towingEntity.orientation); rotation.multiply(hitchConnection.rot);
  * rotation.multiply(hookupConnection.rot)} calls, where {@code RotationMatrix.multiply(other)}
  * is ordinary {@code this = this*other} composition) - is instead
  * {@code Q_towed = Q_towing * R_hitch * R_hookup}, with NO inverse on the hookup side. Passing
  * {@code R_hookup} straight into {@code local_frame2} (this method's first MINECRAFT-525 cut)
  * therefore produced {@code Q_towing * R_hitch * inverse(R_hookup)} - the WRONG sign for any
  * non-identity hookup rotation (review's own worked example: a hookup-only +30&deg; rotation
  * came out -30&deg;). Setting {@code local_frame2 = inverse(R_hookup)} instead makes Sable's own
  * formula collapse to {@code Q_towing * R_hitch * inverse(inverse(R_hookup))
  * = Q_towing * R_hitch * R_hookup} - matching native exactly. The hitch side needed no such
  * correction: it is the FIRST term in both the native composition and Sable's own formula, so
  * {@code local_frame1 = R_hitch} directly was already right.
  *
  * <p>MINECRAFT-530: the owning-entity gap left unverified/unsupported above (connection
  * {@code pos}/{@code rot} authored relative to an installed {@code APart}'s own local frame
  * rather than the vehicle's own root JSON) is closed by {@link #connectionOffsetVehicleLocal}/
  * {@link #connectionRotationVehicleLocal} below, composing through that part's own public
  * {@code localOffset}/{@code localOrientation}/{@code scale} fields (see
  * {@code HitchJoint.partConnectionOffset}/{@code partConnectionRotation}'s own doc comments for
  * the exact derivation) before this method ever sees the result. A vehicle-root-defined
  * connection (the common case) is unaffected - those two helpers pass identity/zero/one part
  * fields for that case, collapsing to exactly the raw {@code pos}/{@code rot} this method
  * always used.
  */
 private static PhysicsConstraintHandle createHitch(HitchAnchor towing, HitchAnchor trailer, TowingConnection connection) {
  var hitchOffset = HitchJoint.anchorLocalOffset(towing.center(),
    connectionOffsetVehicleLocal(connection.towingVehicle.orientation, connection.towingEntity, connection.hitchConnection.pos), new Vector3d());
  var hookupOffset = HitchJoint.anchorLocalOffset(trailer.center(),
    connectionOffsetVehicleLocal(connection.towedVehicle.orientation, connection.towedEntity, connection.hookupConnection.pos), new Vector3d());
  // MINECRAFT-545/547 diagnostic logging only (John's own directive, comment 54230) - NOT a
  // behavior change. Logs each side's real Sable-level linear/angular velocity (not MTS's own
  // v.motion, which has no angular component) and the computed local anchor offsets right
  // before the joint is created, so an ATTACH-time log line and a REPLACEMENT-time log line can
  // be compared directly for whatever actually differs between the two live scenarios. Gated on
  // the existing GroundContactDiagnostics.ENABLED flag (-Ddiv.debugGroundContact=true), the same
  // opt-in this codebase already uses for per-tick diagnostic logging - never active otherwise.
  if (GroundContactDiagnostics.ENABLED) {
   // Logger fetched lazily, HERE only (never as a static field on this outer class) - a class-
   // level `LogUtils.getLogger()` field was tried and reverted: it eagerly runs at
   // PhysicsBridge's own static-init time, which plain unit tests also trigger just by
   // referencing the class, and LogUtils needs Minecraft/Mojang bootstrap that is not present
   // in that context - confirmed live (144 unrelated unit tests failed with
   // ExceptionInInitializerError the moment that field was added).
   Logger diagnosticLogger = LogUtils.getLogger();
   RigidBodyHandle towingHandle = towing instanceof Body b ? b.handle : towing instanceof BuoyantBody bb ? bb.handle() : null;
   RigidBodyHandle trailerHandle = trailer instanceof Body b ? b.handle : trailer instanceof BuoyantBody bb ? bb.handle() : null;
   // MINECRAFT-545/547 (John's comments 54359/54365): pose is the box's own CACHED Pose3d, last
   // refreshed by that body's own PRIOR step() call (box.updatePose()) - this createHitch call
   // itself never refreshes it. Call-ordering alone (active() precedes step() in
   // VehicleMovementMixin.div$move) is NOT itself proof of exactly when the native impulse that
   // produced this cached pose was applied - it only places createHitch's own KNOWN event phase
   // (before this tick's own step()); it is NOT an assertion about which native substep produced
   // the cached value. Per 54365's correction, those two facts are now logged SEPARATELY rather
   // than merged into one inferred staleness label:
   //  - lastPoseUpdateGameTime: the REAL, independently-checkable tick at which this body's own
   //    step() last ran box.updatePose() (debugLastPoseUpdateGameTime/field set there directly,
   //    not inferred from ordering).
   //  - sampleGameTime/knownEventPhase: this read's own tick and the one ordering fact that IS
   //    actually known (createHitch runs before this tick's own step()).
   //  - substepIndex: explicitly UNAVAILABLE - no accessor exposes which of
   //    config.substepsPerTick native substeps last touched this value.
   var towingPose = towing instanceof Body b ? b.box.getPose() : towing instanceof BuoyantBody bb ? bb.box().getPose() : null;
   var trailerPose = trailer instanceof Body b ? b.box.getPose() : trailer instanceof BuoyantBody bb ? bb.box().getPose() : null;
   var towingAnchorWorld = towingPose != null ? new Vector3d(hitchOffset).rotate(new Quaterniond(towingPose.orientation())).add(towingPose.position()) : null;
   var trailerAnchorWorld = trailerPose != null ? new Vector3d(hookupOffset).rotate(new Quaterniond(trailerPose.orientation())).add(trailerPose.position()) : null;
   long towingSampleGameTime = towing.level().getGameTime();
   long trailerSampleGameTime = trailer.level().getGameTime();
   diagnosticLogger.info("[DIV createHitchDiagnostic] towing=" + connection.towingVehicle.uniqueUUID + " class=" + towing.getClass().getSimpleName()
     + " sampleGameTime=" + towingSampleGameTime
     + " lastPoseUpdateGameTime=" + debugLastPoseUpdateGameTime(connection.towingVehicle)
     + " knownEventPhase=createHitch(before-this-ticks-step) substepIndex=UNAVAILABLE"
     + " center=" + towing.center() + " hitchLocalOffset=" + hitchOffset
     + " cachedPose=" + towingPose + " hitchAnchorWorld(fromCachedPose)=" + towingAnchorWorld
     + " linearVelocity=" + (towingHandle != null ? towingHandle.getLinearVelocity(new Vector3d()) : "n/a")
     + " angularVelocity=" + (towingHandle != null ? towingHandle.getAngularVelocity(new Vector3d()) : "n/a"));
   diagnosticLogger.info("[DIV createHitchDiagnostic] trailer=" + connection.towedVehicle.uniqueUUID + " class=" + trailer.getClass().getSimpleName()
     + " sampleGameTime=" + trailerSampleGameTime
     + " lastPoseUpdateGameTime=" + debugLastPoseUpdateGameTime(connection.towedVehicle)
     + " knownEventPhase=createHitch(before-this-ticks-step) substepIndex=UNAVAILABLE"
     + " center=" + trailer.center() + " hookupLocalOffset=" + hookupOffset
     + " cachedPose=" + trailerPose + " hookupAnchorWorld(fromCachedPose)=" + trailerAnchorWorld
     + " linearVelocity=" + (trailerHandle != null ? trailerHandle.getLinearVelocity(new Vector3d()) : "n/a")
     + " angularVelocity=" + (trailerHandle != null ? trailerHandle.getAngularVelocity(new Vector3d()) : "n/a"));
  }
  var config = new GenericConstraintConfiguration(hitchOffset, hookupOffset,
    connectionRotationVehicleLocal(connection.towingVehicle.orientation, connection.towingEntity, connection.hitchConnection.rot),
    connectionRotationVehicleLocal(connection.towedVehicle.orientation, connection.towedEntity, connection.hookupConnection.rot).invert(),
    HitchJoint.lockedAxes(connection.hitchConnection.mounted, connection.hitchConnection.restricted));
  return SubLevelContainer.getContainer(towing.level()).physicsSystem().getPipeline().addConstraint(towing.box(), trailer.box(), config);
 }
 /**
  * A hitch/hookup connection's own {@code pos}, converted to vehicle-root-local: unchanged when
  * {@code owner} is the vehicle itself (the connection was already authored in that exact
  * frame), or composed through the installed part's own fields via
  * {@code HitchJoint.partConnectionOffset} when {@code owner} is an {@code APart} - the
  * MINECRAFT-530 gap {@code createHitch}'s own doc comment names. {@code vehicleOrientation} is
  * the OWNING SIDE's own vehicle ({@code connection.towingVehicle}/{@code towedVehicle} -
  * {@code towingEntity}/{@code towedEntity} unwrapped to the vehicle, per
  * {@code TowingConnection}'s own real source), needed to correct an installed part's
  * orientation for any nesting depth - see {@link HitchJoint#vehicleRootLocalOrientation}'s doc
  * comment for why the raw part orientation alone (review kickback, comment 53994) is not
  * enough.
  */
 private static Vector3d connectionOffsetVehicleLocal(RotationMatrix vehicleOrientation, AEntityE_Interactable<?> owner, Point3D connectionPos) {
  var local = new Vector3d(connectionPos.x, connectionPos.y, connectionPos.z);
  if (!(owner instanceof APart part)) return local;
  var partOrientation = HitchJoint.vehicleRootLocalOrientation(vehicleOrientation, part.orientation);
  return HitchJoint.partConnectionOffset(new Vector3d(part.localOffset.x, part.localOffset.y, part.localOffset.z),
    partOrientation, new Vector3d(part.scale.x, part.scale.y, part.scale.z), local, new Vector3d());
 }
 /**
  * A hitch/hookup connection's own {@code rot}, converted to vehicle-root-local: {@code
  * HitchJoint.localJointFrame}'s own plain conversion when {@code owner} is the vehicle itself,
  * or composed with the installed part's own vehicle-root-local orientation via
  * {@code HitchJoint.partConnectionRotation} when {@code owner} is an {@code APart} - the same
  * MINECRAFT-530 gap {@link #connectionOffsetVehicleLocal} closes for position, and the same
  * {@code vehicleOrientation} correction it needs (see its own doc comment).
  */
 private static Quaterniond connectionRotationVehicleLocal(RotationMatrix vehicleOrientation, AEntityE_Interactable<?> owner, RotationMatrix connectionRot) {
  if (!(owner instanceof APart part)) return HitchJoint.localJointFrame(connectionRot);
  return HitchJoint.partConnectionRotation(HitchJoint.vehicleRootLocalOrientation(vehicleOrientation, part.orientation), connectionRot);
 }
 /**
  * The portion of IV's own getRecursiveTowingThrust() a towing Sable body must still replicate
  * as a compensation impulse (see TowingForces): the same recursion IV's own method performs
  * (a {@code mounted} connection never contributes, matching IV's own rule), except that any
  * immediate towed vehicle which already has a valid, coherent hitch joint to exactly {@code v}
  * (and everything further recursed through it) is skipped entirely - its own engine thrust is
  * now applied directly to its own Sable body by its own {@code stepGround} call and already
  * reaches {@code v} mechanically through the joint Sable itself solves, so replicating it again
  * here would double-count it (MINECRAFT-498 self-review finding). A towed vehicle with no such
  * joint - still on IV's native hitch code (see {@code active()}'s ordering wait), or whose prior
  * joint went invalid/pointed at a different (e.g. previous) towing vehicle (external review
  * finding: plain joint-map membership is not enough here either, the same gap
  * {@code reconcileOrRollBack} had - see {@link HitchRegistry#isCoherentlyJointedTo}) - has no
  * such mechanical path to {@code v}, so its full contribution is still replicated exactly as
  * MINECRAFT-496 established.
  */
 private static double unjointedTowingThrust(EntityVehicleF_Physics v) {
  double thrust = 0;
  for (TowingConnection connection : v.towingConnections) {
   EntityVehicleF_Physics towed = connection.towedVehicle;
   if (!compensableConnection(connection.hitchConnection.mounted, hitches.isCoherentlyJointedTo(towed, v))) continue;
   var accessor = (VehicleTowingAccessor)(Object)towed;
   thrust += accessor.div$getThrustForceValue() + unjointedTowingThrust(towed);
  }
  return thrust;
 }
 /**
  * Whether one towing connection's towed-vehicle contribution belongs in
  * {@code unjointedTowingThrust}'s sum - the exact per-connection decision that sum applies,
  * pulled out as a pure boolean seam so it is testable without a live entity/world/Sable
  * runtime. A {@code mounted} connection never contributes (matching IV's own
  * getRecursiveTowingThrust rule - a mounted vehicle, e.g. a flatbed's cargo, is not treated as
  * adding towing thrust). An unmounted connection contributes only while its towed vehicle is NOT
  * {@code coherentlyJointed} - a valid handle to exactly this towing vehicle, from
  * {@link HitchRegistry#isCoherentlyJointedTo} - since only then does that vehicle's engine
  * thrust already reach the towing body mechanically through the Sable joint; compensating for
  * it again here would double-count it (the MINECRAFT-498 self-review regression this seam
  * exists to pin). An invalid handle or one pointed at a different (e.g. previous) towing vehicle
  * is NOT {@code coherentlyJointed}, and must still contribute - no valid constraint actually
  * transmits that thrust in either case (external review finding, comment 52756).
  */
 static boolean compensableConnection(boolean mounted, boolean coherentlyJointed) {
  return !mounted && !coherentlyJointed;
 }
 /**
  * The exact classification active() applies to a construction failure, as a seam testable
  * with fake keys/a fake body/a fake constructor: only {@link InvalidChassisGeometry} - Body's
  * own explicit, pre-registration chassis-bounds check, thrown before any Sable object exists -
  * is safe to cache in {@code broken} as deterministic for {@code brokenKey}. Anything else
  * (including an unrelated IllegalArgumentException a Sable/world API might throw after
  * registration, already rolled back by the constructor itself) is left retryable rather than
  * silently poisoning every future instance of an otherwise-valid definition until restart.
  * {@code key} (the live entity, for the active-bodies registry) and {@code brokenKey} (the
  * shared definition, so a bad content-pack definition is cached once, not per entity) are
  * deliberately separate type parameters: PhysicsBridge keys these two maps differently.
  */
 static <K, C, B> boolean activateOrClassify(BodyRegistry<K, B> registry, Set<C> broken, K key, C brokenKey, Supplier<B> construct) {
  try {
   return registry.activateOnce(key, construct);
  } catch (InvalidChassisGeometry geometryInvalid) {
   broken.add(brokenKey);
   return false;
  } catch (RuntimeException transientFailure) {
   return false;
  }
 }
 /**
  * MINECRAFT-545/547 (John's comment 55126 - "Four survivor tests reimplement removal in
  * FakeVehicleBody.remove, proving model not actual Body.remove"): the exact branching logic
  * {@code Body.remove()}'s own partial-two-body-survivor path uses, extracted so it can be
  * exercised directly by a test with injected (fake) leaf operations, instead of a test double
  * reimplementing this same decision logic in parallel (which would only prove the fake's own
  * logic self-consistent, never that production actually behaves this way). {@code Body.remove()}
  * is the ONLY production caller, passing the real {@code isActive()}/{@code isValid()} results
  * and real {@code removeObject}/{@code joint.remove()} calls; a test passes synthetic suppliers
  * and {@code Runnable} spies instead - same method, same control flow, different leaves.
  *
  * <p>MINECRAFT-545/547 (John's comment 55139 - "Put eligibility reads inside failure-isolated
  * production orchestration... not only precomputed booleans if claiming failure isolation"):
  * {@code activeChecks[i]} is a {@code BooleanSupplier}, NOT a precomputed {@code boolean} -
  * the ELIGIBILITY check itself (e.g. {@code twoBodyJoint.isValid()}) is now evaluated INSIDE
  * this same failure-isolated loop, not by the caller beforehand. The original version of this
  * method took precomputed {@code boolean[]} flags; {@code Body.remove()}'s own caller code
  * computed {@code twoBodyJoint.isValid()} BEFORE calling in, outside any try/catch - a throwing
  * validity query would have propagated immediately and skipped every resource's own removal
  * attempt, the exact same "one throw skips everything" failure this method exists to prevent,
  * just moved one step earlier. If a given eligibility check itself throws, that resource is
  * treated as not-safely-removable this call (skipped, not attempted) and the failure is
  * captured exactly like a throwing removal would be - every OTHER resource's own eligibility
  * check and removal still proceed independently. Each resource at index {@code i} is attempted
  * ONLY if {@code activeChecks[i]} returns {@code true}, independently try/caught end to end, with
  * the FIRST exception encountered (from either an eligibility check or a removal) re-thrown at
  * the end (never swallowed). Pure/generic (plain {@code BooleanSupplier}s and {@code Runnable}s,
  * no entity/Sable/world dependency) - directly unit-testable with no native construction needed.
  */
 static void removeIndependentResources(java.util.function.BooleanSupplier[] activeChecks, Runnable[] removeOps) {
  RuntimeException firstFailure = null;
  for (int i = 0; i < activeChecks.length; i++) {
   boolean active;
   try {
    active = activeChecks[i].getAsBoolean();
   } catch (RuntimeException ex) {
    if (firstFailure == null) firstFailure = ex;
    continue; // eligibility unknown - do not attempt removal, but do not block the others either
   }
   if (active) {
    try {
     removeOps[i].run();
    } catch (RuntimeException ex) {
     if (firstFailure == null) firstFailure = ex;
    }
   }
  }
  if (firstFailure != null) throw firstFailure;
 }
 /**
  * MINECRAFT-545/547 (John's comment 55098): the real root cause of the native crash a
  * `BuoyantHitchProbe` `TOWING_UNAVAILABLE` FAILED run surfaced - traced to the actual log
  * (`BoxPhysicsObject.updatePose:61` NPE, "this.handle is null", inside `BuoyantBody.step:125`,
  * two ticks after a `FAILED` bounded-area verdict for the SAME vehicle). Returns the key's
  * active body, but FIRST reclaims (and returns {@code null} for) a STALE entry whose own
  * {@link VehicleBody#isActive} already reports {@code false} - meaning the returned Java object
  * would otherwise still look live while its underlying native resources were ALREADY removed by
  * some other path (e.g. a chunk-unload-driven invalidation) that never cleared this registry
  * entry. {@code PhysicsBridge.step} previously called {@code registry.get(vehicle).step(vehicle)}
  * unconditionally and crashed inside that stale body's own step call, never at the lookup.
  * Reclaims via the SAME registry-removal path {@code cleanup()}/{@code unload()} already use -
  * not a new, second teardown mechanism: {@code deactivate} plus the identical companion
  * cleanup. This does NOT suppress or alter any probe's own FAILED verdict - that fires
  * independently, earlier, from {@code BuoyantHitchProbe}'s own bounded-area check; this guard
  * only prevents the SEPARATE crash that was following it. Generic and pure (no entity/Sable/
  * world dependency) - directly unit-testable with fake keys/a fake body, the same seam-testing
  * pattern {@link #activateOrClassify} already uses for registry-dependent logic that can't
  * construct a real {@code EntityVehicleF_Physics}.
  *
  * <p>MINECRAFT-545/547 (John's comment 55104): the ORIGINAL version of this method called
  * {@code hitches.release(key)} before {@code pendingInheritedVelocity.cleanup(key)} with no
  * guard between them - a throwing {@code release} (itself now hardened against the same
  * partial-cleanup gap, see {@link HitchRegistry#release}'s own doc) would still have skipped
  * this method's own SECOND, independent cleanup step. {@code cleanup(key)} now always runs
  * regardless of whether {@code release(key)} throws; the exception itself (if any) is still
  * re-thrown afterward, not swallowed.
  *
  * <p>MINECRAFT-545/547 (John's comment 55116 - "partial two-body survivor cleanup"): the
  * ORIGINAL version of this method never called {@code body.remove()} at all in this branch -
  * reasoned (correctly, for the single-body case) that the native object was already fully
  * gone, so calling {@code remove()} again would be a pointless or unsafe double-removal.
  * That reasoning missed the TWO-BODY case: {@code isActive()} can be {@code false} because
  * only ONE half died, leaving the SURVIVING half's own native resource never released - a real
  * leak, not merely a missed optimization. Fixed at the root instead of special-cased here: every
  * {@code VehicleBody.remove()} implementation ({@code Body}, {@code BuoyantBody}) is now
  * itself idempotent (checks its own {@code isActive()} before touching anything), so calling
  * {@code remove()} unconditionally here is now always safe - a no-op for an already-fully-dead
  * single body, and a genuine partial-survivor cleanup for a partially-dead two-body one.
  * {@code remove()} is now called BEFORE {@code hitches.release}/{@code cleanup}, with the same
  * first-failure-captured-rethrown-at-end pattern already established for the other two steps -
  * a throw from any one of the three never prevents the other two from still running.
  */
 static <K, B extends VehicleBody> B activeOrReclaimStale(
     BodyRegistry<K, B> registry, HitchRegistry<K> hitches, PendingReplacementState<K, ?> pendingInheritedVelocity, K key) {
  B body = registry.get(key);
  if (body == null) return null;
  if (!body.isActive()) {
   registry.deactivate(key);
   RuntimeException firstFailure = null;
   try {
    body.remove();
   } catch (RuntimeException ex) {
    firstFailure = ex;
   }
   try {
    hitches.release(key);
   } catch (RuntimeException ex) {
    if (firstFailure == null) firstFailure = ex;
   }
   pendingInheritedVelocity.cleanup(key);
   if (firstFailure != null) throw firstFailure;
   return null;
  }
  return body;
 }
 public static void step(EntityVehicleF_Physics vehicle) {
  VehicleBody body = activeOrReclaimStale(registry, hitches, pendingInheritedVelocity, vehicle);
  if (body != null) body.step(vehicle);
 }
 /**
  * Ticket MINECRAFT-546 (tuning-provenance diagnostic): the live {@code Overrides.Tuning} the
  * currently-active body for {@code v} was actually constructed with - see {@code
  * VehicleBody.tuning()}'s own doc for exactly which fields a later hot reload can still mutate
  * in place on this SAME object vs. which were only read once at construction. Returns {@code
  * null} if {@code v} has no active body at all (never activated, opted out, or a broken
  * definition) - the diagnostic's only way to tell "this vehicle is running tuning X right now"
  * apart from "a fresh {@code Overrides.resolved} call against the current config would compute
  * X" (the two can legitimately differ for a respawn-required field changed since this vehicle's
  * body was built).
  */
 public static Overrides.Tuning activeTuning(EntityVehicleF_Physics v) {
  VehicleBody body = registry.get(v);
  return body != null ? body.tuning() : null;
 }
 /**
  * Diagnostic/test-only (ticket 53390's explicit "native angular before/after receipt on
  * rotating airborne rebuild remains necessary and cheap"): directly injects a KNOWN angular
  * velocity into this vehicle's currently-active Body, so a test can establish a real, nonzero,
  * precisely-known rotation rate WITHOUT needing wheel/ground forces to produce one (airborne -
  * no grip, no confound with the chassisBoundsChanged rebuild's own grip-restoring effect, the
  * real limitation review 53385/53386 already found in the stationary-then-driving test). A
  * no-op if this vehicle has no active two-body Body right now. Never called from any production
  * code path - only from this experimental test probe.
  */
 public static void debugInjectAngularVelocity(EntityVehicleF_Physics v, Vector3d angularVelocity) {
  if (registry.get(v) instanceof Body body) {
   body.handle.addLinearAndAngularVelocity(new Vector3d(), angularVelocity);
   if (body.twoBodyActive) body.upperHandle.addLinearAndAngularVelocity(new Vector3d(), angularVelocity);
  }
 }
 /**
  * Diagnostic/test-only companion to {@link #debugInjectAngularVelocity} - reads back this
  * vehicle's CURRENT actual native angular velocity directly from its live Sable handle, so a
  * test can log the real before/after values across a chassisBoundsChanged rebuild instead of
  * inferring preservation from a yaw-rate proxy. Returns null if there is no active Body.
  */
 public static Vector3d debugReadAngularVelocity(EntityVehicleF_Physics v) {
  var body = registry.get(v);
  if (body instanceof Body b) return b.handle.getAngularVelocity(new Vector3d());
  // MINECRAFT-545/547 diagnostic (John's comment 54237: trace actual pose/velocity BEFORE
  // connectTrailer is called, not just inside createHitch): extends this existing debug-only
  // reader to BuoyantBody too, so a probe can read the SAME real native state right before
  // calling connectTrailer that createHitch's own log line reads moments later, inside it.
  if (body instanceof BuoyantBody bb) return bb.handle().getAngularVelocity(new Vector3d());
  return null;
 }
 /** Diagnostic/test-only companion to {@link #debugReadAngularVelocity} - the same real native
  * state, linear component. See that method's own doc. */
 public static Vector3d debugReadLinearVelocity(EntityVehicleF_Physics v) {
  var body = registry.get(v);
  if (body instanceof Body b) return b.handle.getLinearVelocity(new Vector3d());
  if (body instanceof BuoyantBody bb) return bb.handle().getLinearVelocity(new Vector3d());
  return null;
 }
 /**
  * Diagnostic/test-only (MINECRAFT-545/547, John's comment 54262: "this does not establish
  * release of a preexisting joint" - {@code debugReadLinearVelocity} returning {@code null} only
  * proves DIV's OWN registry no longer holds a body for {@code trailer}; it says nothing about
  * whether {@code hitches} itself ever held - and then actually released - a real joint entry).
  * Direct, live OBSERVATION of {@link HitchRegistry#isJointed}, not an inference from source
  * reading. Never called from any production code path.
  */
 public static boolean debugIsJointed(EntityVehicleF_Physics trailer) {
  return hitches.isJointed(trailer);
 }
 /**
  * Diagnostic/test-only (MINECRAFT-545/547, John's comment 54262: "native-object destruction" -
  * {@code debugReadLinearVelocity} returning {@code null} only proves DIV's own Java-side
  * registry entry is gone, not that the underlying native Sable {@code BoxPhysicsObject}/handle
  * was actually destroyed). Captures the CURRENT active body for {@code v}, as an opaque
  * reference a caller can hold across the exact tick a fallback is expected to remove it, then
  * pass to {@link #debugCapturedBoxIsActive} afterward - a live OBSERVATION of the SAME native
  * object's own {@code BoxPhysicsObject.isActive()} transitioning false, not an inference from
  * {@code deactivate}/{@code remove}'s own source. Returns {@code null} if there is no active
  * body right now. Never called from any production code path.
  */
 public static Object debugCaptureBody(EntityVehicleF_Physics v) {
  return registry.get(v);
 }
 /** Diagnostic/test-only companion to {@link #debugCaptureBody} - reads the captured body's OWN
  * native {@code BoxPhysicsObject.isActive()} directly, live, regardless of whether THIS vehicle
  * is still the registry's current entry for anything. {@code null} input (no body was captured)
  * returns {@code null}. Never called from any production code path. */
 public static Boolean debugCapturedBoxIsActive(Object capturedBody) {
  if (capturedBody instanceof Body b) return b.box.isActive();
  if (capturedBody instanceof BuoyantBody bb) return bb.box().isActive();
  return null;
 }
 /**
  * Diagnostic/test-only (MINECRAFT-545/547, John's comments 54290/54337/54359/54365: pose/
  * orientation was a named missing quantity, and any sample must explicitly state whether it
  * reflects a fresh native read or a cached Java value, not leave that implicit). Returns
  * {@code box().getPose()} - the box's own cached {@code Pose3d}, refreshed ONLY by that body's
  * own {@code step()} call (via {@code box.updatePose()}), NOT by this accessor itself.
  * CORRECTED (54359): call-ordering alone ({@code active()} running before {@code step()} in
  * {@code VehicleMovementMixin.div$move}) shows only that a {@code createHitch}-time sample
  * predates THIS tick's own {@code step()} - it is NOT independent proof of exactly when the
  * native impulse behind this cached pose was applied, so callers must NOT assert a precise
  * "one tick stale" age from this reasoning alone. Use {@link #debugLastPoseUpdateGameTime} for
  * the real, independently-checkable freshness marker instead. Returns {@code null} if there is
  * no active body right now. Never called from any production code path.
  */
 public static dev.ryanhcode.sable.companion.math.Pose3dc debugReadPose(EntityVehicleF_Physics v) {
  var body = registry.get(v);
  if (body instanceof Body b) return b.box.getPose();
  if (body instanceof BuoyantBody bb) return bb.box().getPose();
  return null;
 }
 /** Diagnostic/test-only companion to {@link #debugReadPose} (John's comment 54365: "record the
  * tick/event of each body's last box.updatePose... associate sampled pose with it"). Returns the
  * Minecraft {@code gameTime} at which this body's own {@code step()} last actually called
  * {@code box.updatePose()} - the real freshness marker for whatever {@link #debugReadPose}
  * returns, not an age inferred from call-ordering. Returns {@code -1} if there is no active body
  * right now, or if that body has never run {@code step()} yet (hitch created on its very first
  * tick, before its own step has run). Never called from any production code path. */
 public static long debugLastPoseUpdateGameTime(EntityVehicleF_Physics v) {
  var body = registry.get(v);
  if (body instanceof Body b) return b.debugLastPoseUpdateGameTime;
  if (body instanceof BuoyantBody bb) return bb.debugLastPoseUpdateGameTime;
  return -1;
 }
 /** Diagnostic/test-only companion to {@link #debugLastPoseUpdateGameTime} (John's comment
  * 54595/54596): the real COUNT of times this body's own {@code step()} has actually been
  * invoked - distinct from the gameTime marker above, which cannot tell "stepped once this tick"
  * apart from "stepped twice in the same tick" (both would write the identical gameTime). Returns
  * {@code 0} if there is no active body right now, or it has never run {@code step()} yet. Never
  * called from any production code path. */
 public static long debugStepInvocationCount(EntityVehicleF_Physics v) {
  var body = registry.get(v);
  if (body instanceof Body b) return b.debugStepInvocationCount;
  if (body instanceof BuoyantBody bb) return bb.debugStepInvocationCount;
  return 0;
 }
 /** Diagnostic/test-only companion to {@link #debugStepInvocationCount} (John's comments
  * 54616/54635, bug 2): {@code v.uniqueUUID} is the ENTITY's own identity, unchanged even when
  * THIS registry's own body for {@code v} disappears or is replaced by a fresh instance (e.g. a
  * reconnect that tears down and reconstructs the native body under the same entity) - a real
  * body swap, not the same still-live body genuinely failing to advance.
  * {@link #debugStepInvocationCount} returning {@code 0} is indistinguishable, from the entity
  * UUID alone, between "this body genuinely never stepped" and "there is currently no body at
  * all / a different body now." CORRECTED (54635): the first version of this returned
  * {@code System.identityHashCode(body)}, documented as never colliding across distinct live
  * instances - that claim was simply false, {@code identityHashCode} is a hash, not a guaranteed-
  * unique id, and two distinct live objects CAN collide. Returns the actual {@code VehicleBody}
  * reference itself instead (as a plain {@code Object}, since {@code VehicleBody}/{@code Body}/
  * {@code BuoyantBody} are package-private) - {@code null} if {@code registry.get(v)} is
  * currently absent. Neither {@code Body} nor {@code BuoyantBody} overrides {@code equals()}, so
  * the default reference-identity {@code equals()}/{@code ==} genuinely distinguishes any two
  * distinct instances with no collision risk, for as long as this diagnostic's own bounded
  * capture window holds the reference (a {@code List<Sample>} kept only for this one fixture
  * run) - never called from any production code path, never persisted/serialized/compared across
  * JVM runs or GC epochs where a raw reference could otherwise be reused or become stale. */
 public static Object debugRegistryBodyIdentity(EntityVehicleF_Physics v) {
  return registry.get(v);
 }
 /** MINECRAFT-545/547 (John's comment 54862, corrected per 54870): a real OBSERVATION of the
  * missing-index fallback's own real adapter actually running for a SPECIFIC vehicle AND a
  * SPECIFIC connection object - exists because a prior fixture's own reasoning ("the
  * pre-injection state made the ordinary path structurally unreachable, so towedByConnection
  * becoming null MUST have come from the fallback") is an INFERENCE from a postcondition, not a
  * direct observation.
  * <p>CORRECTED (54870): the first version of this was an always-on, unbounded
  * {@code Map<UUID, Integer>} that retained one entry forever for every orphan this production
  * code EVER processes across a long-running server's own lifetime - a real, if slow, memory
  * leak, and never actually gated by anything. This is now BOUNDED and OPT-IN: nothing is
  * retained at all unless a caller explicitly {@link #registerFallbackObserver registers} one,
  * and every registration MUST be explicitly {@link #unregisterFallbackObserver unregistered} -
  * the fixture that uses this does so on every terminal/error path, never leaving one behind.
  * Keyed to the EXACT vehicle UUID AND the EXACT expected connection reference - an invocation
  * for a registered vehicle with a DIFFERENT connection object does NOT count as a match (see
  * {@link #recordFallbackClearConnectionObservation}'s own doc for why that distinction matters
  * and how it is counted separately rather than silently ignored or silently accepted). */
 private static final java.util.Map<java.util.UUID, FallbackObserverState> fallbackObservers = new java.util.HashMap<>();

 /** MINECRAFT-545/547 (John's comment 54929): a {@code FallbackObserverState} now carries its
  * OWN registration token - the first version keyed state purely by {@code vehicleUuid}, so a
  * SECOND registration for the same UUID silently overwrote the first registration's state, and
  * a caller holding the FIRST (now-stale) handle could later call {@code unregisterFallbackObserver}
  * and remove the SECOND registration's own live entry - an unsafe API that lets an unrelated
  * stale handle corrupt a newer, still-active registration it has no relationship to. */
 private static final class FallbackObserverState {
  final Object expectedConnection;
  final long token;
  int matchedCount = 0;
  int mismatchedCount = 0;
  FallbackObserverState(Object expectedConnection, long token) { this.expectedConnection = expectedConnection; this.token = token; }
 }

 private static long nextFallbackObserverToken = 0;

 /** Opaque handle to one registration - pass to {@link #unregisterFallbackObserver} to stop
  * observing and release the (otherwise permanently retained) map entry. Carries both the
  * vehicle UUID AND a unique per-registration {@code token} (54929 fix) - a handle only ever
  * matches the registration it was actually issued for, never a later, different registration
  * that happens to share the same vehicle UUID. */
 public static final class FallbackObserverHandle {
  final java.util.UUID vehicleUuid;
  final long token;
  private FallbackObserverHandle(java.util.UUID vehicleUuid, long token) { this.vehicleUuid = vehicleUuid; this.token = token; }
 }

 /** Begins observing {@code vehicleUuid} for a real fallback-adapter invocation on EXACTLY
  * {@code expectedConnection} (compared by reference, {@code ==}, never {@code equals} - same
  * identity discipline as {@link OrphanedConnectionOps#connectionIdentity}'s own doc). Nothing
  * is retained for any vehicle that is never registered - bounded, opt-in, no default growth.
  * A second registration for an already-registered {@code vehicleUuid} is allowed (the caller
  * may legitimately re-register across runs) but issues a NEW token, so the PRIOR handle becomes
  * stale and can no longer read or remove this new registration's own state (54929 fix). */
 public static FallbackObserverHandle registerFallbackObserver(java.util.UUID vehicleUuid, Object expectedConnection) {
  long token = nextFallbackObserverToken++;
  fallbackObservers.put(vehicleUuid, new FallbackObserverState(expectedConnection, token));
  return new FallbackObserverHandle(vehicleUuid, token);
 }

 /** Stops observing and releases the map entry - callers MUST call this on every terminal/error
  * path for every handle they ever registered; a no-op if {@code handle} is null OR if a NEWER
  * registration for the same vehicle UUID has since replaced the one this handle was issued for
  * (54929 fix) - a stale handle must never remove a live, unrelated registration. */
 public static void unregisterFallbackObserver(FallbackObserverHandle handle) {
  if (handle == null) return;
  var state = fallbackObservers.get(handle.vehicleUuid);
  if (state != null && state.token == handle.token) {
   fallbackObservers.remove(handle.vehicleUuid);
  }
 }

 /** The pure, Minecraft/MTS-free recording logic the real adapter routes every invocation
  * through (see the real call site in {@code releaseOrphanedTowingConnections}'s own fallback
  * adapter) - takes a plain {@code UUID} and a plain {@code Object} connection reference, so
  * this is directly unit-testable with synthetic values, no MTS object construction needed.
  * Returns {@code true} only when a registration exists for {@code vehicleUuid} AND
  * {@code actualConnection} is reference-identical to the registered expectation - a mismatched
  * connection (registered vehicle, but a DIFFERENT connection object) is counted separately as
  * a mismatch, not silently folded into the match count and not silently ignored either. A
  * vehicle with no registration at all is a complete no-op - nothing is created or retained. */
 public static boolean recordFallbackClearConnectionObservation(java.util.UUID vehicleUuid, Object actualConnection) {
  var state = fallbackObservers.get(vehicleUuid);
  if (state == null) return false;
  if (state.expectedConnection == actualConnection) { state.matchedCount++; return true; }
  state.mismatchedCount++;
  return false;
 }

 /** Returns 0 for a null handle, for a vehicle with no live registration, AND (54929 fix) for a
  * STALE handle whose own registration has since been replaced by a newer one for the same
  * vehicle UUID - a stale handle must never read a newer, unrelated registration's own counts. */
 public static int debugFallbackObserverMatchedCount(FallbackObserverHandle handle) {
  var state = liveStateFor(handle);
  return state != null ? state.matchedCount : 0;
 }

 public static int debugFallbackObserverMismatchedCount(FallbackObserverHandle handle) {
  var state = liveStateFor(handle);
  return state != null ? state.mismatchedCount : 0;
 }

 private static FallbackObserverState liveStateFor(FallbackObserverHandle handle) {
  if (handle == null) return null;
  var state = fallbackObservers.get(handle.vehicleUuid);
  return (state != null && state.token == handle.token) ? state : null;
 }
 /** Diagnostic/test-only companion to {@link #debugReadPose} - the same body's own geometric
  * {@code center()} (chassis-local, fixed since construction) - lets a caller compute a REAL
  * anchor-world position at ANY bracket by rotating a connection's own authored local offset by
  * whichever pose sample it already has, via {@code HitchJoint.anchorLocalOffset}, instead of
  * hardcoding or re-deriving the offset a different way. Returns {@code null} if there is no
  * active body right now. Never called from any production code path. */
 public static Vector3dc debugReadCenter(EntityVehicleF_Physics v) {
  var body = registry.get(v);
  if (body instanceof Body b) return b.center();
  if (body instanceof BuoyantBody bb) return bb.center();
  return null;
 }
 /**
  * Diagnostic/test-only (MINECRAFT-543, {@code RotorAirDragRuntimeProbe}): the translational
  * twin of {@link #debugInjectAngularVelocity} - directly injects a KNOWN linear velocity into
  * this vehicle's currently-active Body's live Sable handle, so a test/probe can establish a
  * real, nonzero, precisely-known translational speed WITHOUT needing thrust/control input to
  * produce one. A no-op if this vehicle has no active Body right now. Never called from any
  * production code path.
  */
 public static void debugInjectLinearVelocity(EntityVehicleF_Physics v, Vector3d linearVelocity) {
  if (registry.get(v) instanceof Body body) {
   body.handle.addLinearAndAngularVelocity(linearVelocity, new Vector3d());
   if (body.twoBodyActive) body.upperHandle.addLinearAndAngularVelocity(linearVelocity, new Vector3d());
  }
 }
 // debugReadLinearVelocity (main's own MINECRAFT-526 version, Body-only) is already defined
 // above as the MINECRAFT-545/547 branch's own strict superset (same Body-case behavior, plus a
 // BuoyantBody case) - merge conflict resolution (per 55043) kept that definition and dropped
 // this duplicate rather than the other way around.
 /**
  * Diagnostic/test-only snapshot (MINECRAFT-526 review 53897/53905/53907) bundling everything a
  * test needs to assert real dispatch/retention without re-deriving any of it: {@code
  * bodyIdentity} is the active {@code Body} instance itself as a plain reference-equality-
  * comparable {@code Object} (its real type is package-private, so a caller outside this file can
  * only {@code ==}/{@code equals} it against a value from an earlier call - enough to assert "the
  * SAME live body, never respawned or rebuilt" across a sequence of reloads); {@code
  * lastExecutedStepper}/{@code executedStepCount} are {@code step()}'s own live counters, updated
  * ONLY at the point force application actually ran (never on a tick {@code
  * skipNonGroundForTwoBody} skipped) - review 53907's explicit point that a merely-SELECTED
  * stepper does not prove force application executed (M527's own root cause is exactly a selected
  * non-GROUND stepper whose execution this candidate unconditionally skips), so a test asserting
  * dispatch must see the counter actually advance, not just read a recomputed selection; {@code
  * tuning} is the exact {@link Overrides.Tuning} object this Body reads its own {@code tuning.X}
  * fields from every tick (the same reference {@code stepFlight}/{@code stepRotor}/{@code
  * stepGround} use) - NOT a fresh {@link Overrides#resolved} re-derivation, which is a different
  * value that merely happens to usually agree. Never read by any production code path.
  */
 // lastExecutedStepperName (not the package-private Stepper enum itself) so a caller outside
 // this package can actually call String methods on it (toString()/equals() on an inaccessible
 // enum type fails to compile even via inherited Object/Enum methods).
 public record DebugExecutedStepSnapshot(Object bodyIdentity, String lastExecutedStepperName, long executedStepCount, Overrides.Tuning tuning) {}
 /** Returns {@code null} if there is no active Body (ineligible, floating, or not yet
  * activated) - see {@link DebugExecutedStepSnapshot}'s own doc for every field's meaning. */
 public static DebugExecutedStepSnapshot debugExecutedStepSnapshot(EntityVehicleF_Physics v) {
  return registry.get(v) instanceof Body body
    ? new DebugExecutedStepSnapshot(body, body.lastExecutedStepper == null ? null : body.lastExecutedStepper.name(), body.executedStepCount, body.tuning())
    : null;
 }
 /** True only if a towed vehicle's own {@code towedByConnection} should be released because its
  * ANCHOR entity ({@code towedByConnection.towingEntity} - the vehicle itself, or an installed
  * part on it) is no longer valid. This is the SAME condition
  * {@code AEntityG_Towable.update()}'s own auto-disconnect check uses - {@code towingEntity}, not
  * {@code towingVehicle} (which always resolves to the containing vehicle, and so can differ from
  * the real anchor when the connection is anchored to a part rather than the vehicle itself). That
  * check cannot run for an orphaned towed vehicle - {@code blockMainUpdateCall()} blocks
  * {@code update()}, the only place it lives, for as long as {@code towedByConnection} stays
  * non-null - so {@link #cleanup}'s own independent per-tick sweep is where this condition must
  * be evaluated instead. MINECRAFT-545/547: history of this seam's own corrections (54656/54659)
  * is in VERIFICATION.md, not repeated here. */
 static boolean shouldReleaseOrphanedTowingConnection(boolean towedByConnectionNonNull, boolean towingEntityIsValid) {
  return towedByConnectionNonNull && !towingEntityIsValid;
 }
 /** True only if {@code index} ({@code towingVehicle.towingConnections.indexOf(connection)}) is
  * safe to pass to the real {@code disconnectTrailer(int)} - {@code List.remove(int)} throws for
  * a negative index, and {@code indexOf} returns {@code -1} when the connection is not actually
  * present in the parent's own list. */
 static boolean canSafelyDisconnectAtIndex(int index) {
  return index >= 0;
 }
 /** The real collect-then-dispatch orchestration {@link #cleanup}'s own sweep calls -
  * generalized over {@code V} so a test can supply lightweight fakes instead of live MTS
  * entities; this is not a parallel reimplementation, it is the orchestration itself.
  * <p>{@code candidates} is iterated ONCE, read-only, to collect every vehicle whose connection
  * anchor is currently invalid - no {@code ops} mutation happens during that pass, so a caller
  * iterating a live, structurally-sensitive collection (like {@code BodyRegistry}'s own backing
  * map, via {@link BodyRegistry#forEachActive}) never sees a modification during its own
  * iteration. The actual disconnect/fallback calls happen in a separate pass afterward, and
  * RE-CHECK {@code hasConnection}/{@code anchorIsValid} fresh immediately before acting on each
  * candidate - an earlier candidate's own disconnect must never be trusted to leave a later
  * candidate's own eligibility unchanged; the phase-1 list is a set of candidates to re-verify,
  * never a decision already made.
  * <p>CORRECTED (John's comment 54782, reversing the prior 54665 framing): this is now an
  * IDENTITY-PRESERVING teardown, not merely a current-eligibility re-check. The prior version's
  * re-check asked only "is whatever connection {@code v} has right now still orphaned," and
  * explicitly allowed disconnecting a DIFFERENT (but also currently-invalid) connection than the
  * one collected in phase 1 - reasoned as "correct under the current invariant." 54782 found
  * that reasoning unsafe: if {@code v}'s connection changed between collection and dispatch
  * (e.g. a brand-new, intended replacement connection that is merely transiently invalid during
  * its own setup window - exactly the shape of {@code TOWING_REPLACEMENT}'s own reconnect), this
  * sweep must never tear down THAT different connection just because something ELSE made it
  * eligible by the old (current-state-only) rule. Teardown now targets ONLY the EXACT connection
  * object collected in phase 1 - if {@code v}'s connection has changed identity by dispatch time
  * (to anything, valid or invalid), this candidate is SKIPPED, never disconnected, regardless of
  * the new connection's own state. */
 interface OrphanedConnectionOps<V> {
  boolean hasConnection(V v);
  /** Only ever called when {@link #hasConnection} is currently true for the same {@code v}. */
  boolean anchorIsValid(V v);
  /** A stable identity for whatever connection {@code v} CURRENTLY has (the real adapter uses
   * the real {@code TowingConnection} object reference itself). Compared by the orchestration
   * using {@code ==} (true reference identity), NEVER {@code equals} - two distinct connection
   * objects that happen to compare equal-by-value must still be treated as a changed identity
   * (John's comment 54790: {@code Objects.equals} silently permits exactly that, which is not
   * what "identity-preserving" means). Called once during phase-1 collection and again, fresh,
   * immediately before dispatch - teardown proceeds only if both reads are the same reference. */
  Object connectionIdentity(V v);
  /** Only ever called when {@link #hasConnection} is true, {@link #anchorIsValid} is false, and
   * {@link #connectionIdentity} still matches its own phase-1 value, for the same {@code v},
   * immediately before use - the real {@code towingVehicle.towingConnections.indexOf(connection)}. */
  int indexInParentList(V v);
  void disconnectAtIndex(V v, int index);
  void fallbackClearConnection(V v);
 }

 /** MINECRAFT-545/547 (John's comment 54823: "review minimal production-used side-effect seam
  * or prepare bounded isolated fixture design"). The missing-index fallback's own real side
  * effects require a real {@code EntityVehicleF_Physics}/{@code TowingConnection} to construct
  * (confirmed elsewhere not possible in this suite without new integration-test infrastructure
  * or forbidden reflection) - but the ORDER those side effects happen in does not. This seam
  * extracts that order into its own small, generic, pure orchestration, injected as callbacks -
  * mirroring {@link OrphanedConnectionOps}'s own existing pattern. A fake implementation can
  * verify call order/count in a plain JUnit test with no MTS dependency at all; the real
  * adapter's own implementations of each method are single trivial lines (one real MTS call
  * each), correctness of which is checked by inspection, the same as every other adapter method
  * in this class (e.g. {@link OrphanedConnectionOps#disconnectAtIndex}). */
 interface FallbackClearOps<V> {
  /** The towing vehicle's own {@code resetAllVariables()} - must be called before {@link
   * #clearConnection}, which nulls the field this reads through. */
  void resetTowingVariables(V v);
  /** The towed vehicle's own {@code resetAllVariables()}. */
  void resetTowedVariables(V v);
  /** The towed vehicle's own parking-brake engage. */
  void engageParkingBrake(V v);
  /** Clears the towed vehicle's own connection field - called LAST, since {@link
   * #resetTowingVariables} still needs to read through it. */
  void clearConnection(V v);
 }

 /** The pure, generic ordering the missing-index fallback must follow - see {@link
  * FallbackClearOps}'s own doc for why this is its own seam. */
 static <V> void fallbackClearConnection(V v, FallbackClearOps<V> ops) {
  ops.resetTowingVariables(v);
  ops.resetTowedVariables(v);
  ops.engageParkingBrake(v);
  ops.clearConnection(v);
 }

 static <V> void releaseOrphanedConnections(Iterable<V> candidates, OrphanedConnectionOps<V> ops) {
  var orphaned = new java.util.ArrayList<V>();
  var collectedIdentities = new java.util.ArrayList<Object>();
  for (V v : candidates) {
   if (ops.hasConnection(v) && shouldReleaseOrphanedTowingConnection(true, ops.anchorIsValid(v))) {
    orphaned.add(v);
    collectedIdentities.add(ops.connectionIdentity(v));
   }
  }
  for (int i = 0; i < orphaned.size(); i++) {
   V v = orphaned.get(i);
   // Re-check fresh, immediately before acting - see this method's own doc (54662's own race
   // guard, 54782's own identity-preservation correction). Phase 1's snapshot is a CANDIDATE
   // list, never a decision already made - and identity, not merely current eligibility, is
   // now part of what gets re-verified.
   if (!ops.hasConnection(v) || ops.anchorIsValid(v)) continue;
   // Reference identity (==), NOT equals - see connectionIdentity's own doc (John's 54790).
   if (ops.connectionIdentity(v) != collectedIdentities.get(i)) continue;
   int index = ops.indexInParentList(v);
   if (canSafelyDisconnectAtIndex(index)) ops.disconnectAtIndex(v, index);
   else ops.fallbackClearConnection(v);
  }
 }
 /**
  * MINECRAFT-545/547 (John's comments 54742/54730/54727): the orphan-connection-release sweep,
  * EXTRACTED out of {@link #cleanup()} into its own method so it can run on
  * {@code ServerTickEvent.Pre} instead of {@code cleanup()}'s own {@code ServerTickEvent.Post} -
  * fixing the confirmed, structurally-bounded one-tick dispatch gap documented in
  * VERIFICATION.md ("Corrections per 54727/54730"): with the release on Post, a connection
  * orphaned mid-tick (inside some OTHER, later-registered Post listener - e.g. a towing
  * vehicle's own removal) cannot be detected until the FOLLOWING tick's Post phase, by which
  * point that tick's own entity-ticking (which happens strictly BEFORE any Post listener, every
  * tick) has already run blocked - costing one whole tick of starved dispatch. Running this
  * sweep on Pre instead (confirmed via NeoForge's own source doc comment - {@code
  * EventHooks.onPreServerTick}: "Called from the HEAD of {@code
  * MinecraftServer#tickServer(BooleanSupplier)}", i.e. before ANY of that tick's own level/
  * entity ticking) means the very next tick's Pre phase sees and releases an orphan created at
  * the end of the PRIOR tick's Post phase, BEFORE that next tick's own entity-ticking runs -
  * eliminating the gap structurally, not by weakening any invariant or reordering the fixture.
  * <p>This targets a towed vehicle orphaned by its anchor entity's removal (not ordinary
  * disconnection): its own towedByConnection.towingEntity.isValid is false, but
  * AEntityG_Towable.blockMainUpdateCall() prevents its update() - the only place MTS's own
  * auto-disconnect check lives - from ever running again while towedByConnection stays
  * non-null. Calls the REAL disconnectTrailer (same target/index computation
  * AEntityG_Towable.update() itself uses), not a partial reimplementation - see
  * releaseOrphanedConnections's own doc for the collect/re-check/dispatch orchestration, and
  * VERIFICATION.md for why a direct field null was rejected (does not reproduce
  * disconnectTrailer's own connectionGroupsIndexesInUse/parking-brake/client-packet effects).
  * Calling disconnectTrailer on an already-invalid entity is not new risk this introduces -
  * MTS's own update() already does exactly that in the ordinary (unblocked) case. This adapter
  * only binds releaseOrphanedConnections's own generic orchestration to the real
  * BodyRegistry/EntityVehicleF_Physics/TowingConnection types.
  * <p>Called from EXACTLY ONE place (a new {@code ServerTickEvent.Pre} listener in
  * {@code DynamicImmersiveVehicles}) - {@link #cleanup()} no longer performs this sweep at all,
  * so this never runs twice in the same tick (no duplicate dispatch).
  */
 public static void releaseOrphanedTowingConnections() {
  var active = new java.util.ArrayList<EntityVehicleF_Physics>();
  registry.forEachActive((v, b) -> active.add(v));
  releaseOrphanedConnections(active, new OrphanedConnectionOps<EntityVehicleF_Physics>() {
   public boolean hasConnection(EntityVehicleF_Physics v) { return v.towedByConnection != null; }
   public boolean anchorIsValid(EntityVehicleF_Physics v) { return v.towedByConnection.towingEntity.isValid; }
   // 54782: the real TowingConnection object reference itself - null when there is no current
   // connection, so a candidate that lost its connection entirely (hasConnection now false) is
   // already excluded by the orchestration's own re-check before this is ever compared.
   public Object connectionIdentity(EntityVehicleF_Physics v) { return v.towedByConnection; }
   public int indexInParentList(EntityVehicleF_Physics v) {
    return v.towedByConnection.towingVehicle.towingConnections.indexOf(v.towedByConnection);
   }
   public void disconnectAtIndex(EntityVehicleF_Physics v, int index) {
    var towing = v.towedByConnection.towingVehicle;
    if (GroundContactDiagnostics.ENABLED) com.mojang.logging.LogUtils.getLogger().info(
      "[DIV lifecycleDiagnostic] releaseOrphanedTowingConnections releasing orphaned towedByConnection v=" + v.uniqueUUID
        + " removedTowingEntity=" + v.towedByConnection.towingEntity.uniqueUUID + " indexInParentList=" + index);
    towing.disconnectTrailer(index);
   }
   public void fallbackClearConnection(EntityVehicleF_Physics v) {
    // MINECRAFT-545/547 (John's comment 54782 - "smallest supported consistent recovery... OR
    // state precise unsupported case/risk with tested contract"): attempted the fuller recovery
    // first (reproducing the real snap/non-snap connectionGroupsIndexesInUse branch directly
    // from the connection object, no index needed) - CONFIRMED UNSUPPORTED, not merely
    // undesirable: `AEntityG_Towable.disconnectedTowingConnections` is PRIVATE and
    // `AEntityE_Interactable.connectionGroupsIndexesInUse` is PROTECTED - neither is reachable
    // from this (different-package, non-subclass) code without reflection, which every standing
    // instruction in this investigation forbids. Confirmed by the compiler directly, not assumed.
    // <p>Precise, tested unsupported case (not glossed over): {@code connectionGroupsIndexesInUse}
    // is NOT released on either side in this fallback path - a real, standing resource-leak risk
    // for this one edge case (connection object present on the child but already absent from the
    // parent's own list). Documenting this precisely is NOT an acceptance waiver of the risk
    // itself (John's comment 54790) - this remains an OPEN gap pending further review, not a
    // closed/accepted one; "documented" and "accepted" are not the same thing here.
    // The parent list removal and client {@code PacketEntityTowingChange} notification remain
    // unsupported for the same reasons already documented (nothing left to remove; no real index
    // to send).
    // 54823: the ORDER these calls happen in is now its own pure, tested orchestration
    // (fallbackClearConnection(V, FallbackClearOps) below) - this adapter wires it to the real,
    // genuinely-public MTS calls, each a single trivial line checked by inspection; the ORDER
    // itself (reset towing, reset towed, brake, THEN clear - never clear first, which would lose
    // v.towedByConnection before the other three ever read it) is what the pure test actually
    // exercises, without needing to construct a real EntityVehicleF_Physics at all.
    // Qualified with the outer class name: an unqualified call here resolves ONLY against this
    // anonymous class's own 1-arg fallbackClearConnection (confirmed by the compiler - Java's
    // member lookup stops at the first matching simple name and never merges in the outer
    // class's own same-named static overload), so the 2-arg orchestration below needs
    // PhysicsBridge.-qualification to actually be found.
    PhysicsBridge.fallbackClearConnection(v, new FallbackClearOps<EntityVehicleF_Physics>() {
     public void resetTowingVariables(EntityVehicleF_Physics v) { v.towedByConnection.towingVehicle.resetAllVariables(); }
     public void resetTowedVariables(EntityVehicleF_Physics v) { v.resetAllVariables(); }
     public void engageParkingBrake(EntityVehicleF_Physics v) { v.parkingBrakeVar.setTo(1.0, false); }
     public void clearConnection(EntityVehicleF_Physics v) {
      if (GroundContactDiagnostics.ENABLED) com.mojang.logging.LogUtils.getLogger().info(
        "[DIV lifecycleDiagnostic] releaseOrphanedTowingConnections falling back (connection missing from parent's own list) v=" + v.uniqueUUID
          + " - reproduced resetAllVariables/parkingBrake (both public); UNSUPPORTED (private/protected access, no reflection): connectionGroupsIndexesInUse release on either side, parent list removal (already absent), client PacketEntityTowingChange (needs a real index)");
      // MINECRAFT-545/547 (John's comment 54966): confirmed via direct trace of MTS's real
      // PacketEntityTowingChange/AEntityG_Towable.disconnectTrailer source that this fallback
      // path has NO supported way to notify any tracking client - PacketEntityTowingChange's
      // int-connectionIndex constructor requires a real index still present in the PARENT's own
      // towingConnections list (exactly what is absent here, by definition of reaching this
      // fallback at all); its OTHER constructor is reserved for CONNECT, not disconnect
      // (constructing it with connectionIndex=-1 would hit the receiving handle()'s "connect"
      // branch with a null connectionData and fail/crash - confirmed by reading
      // PacketEntityTowingChange.handle() directly, which is exactly why an index-(-1) call is
      // forbidden, not merely discouraged). MTS's own built-in auto-disconnect check (inside
      // AEntityG_Towable.update(), the ONLY other place this could self-heal) is unconditionally
      // blocked by blockMainUpdateCall() (returns true whenever towedByConnection != null) on
      // EVERY side, client included - confirmed NOT server-only, NOT DIV-specific, and never
      // overridden by DIV. There is therefore no supported code path ON THIS EXISTING MTS
      // INDEX-PACKET MECHANISM, on either side, by which a tracking client's own local
      // towedByConnection/towingConnections/parking-brake state is ever corrected once this
      // fallback fires - a real, standing state-propagation gap, distinct from the
      // separately-documented, purely-cosmetic ALREADY_TOWED chat-notification gap. A DIV-owned
      // alternative notification path was separately investigated and found (see
      // InterfaceInterface.toExternal/toInternal, MINECRAFT-547's 54998 round) - but the
      // generation/binding safety logic needed to use it safely was found exploitable in later
      // rounds and has been deliberately removed pending a reviewed protocol design; see
      // MINECRAFT-547 for the current state of that deferred work. Since no correction via the
      // existing MTS packet mechanism exists, and no DIV-owned alternative currently ships
      // either, this is instead surfaced operationally: UNCONDITIONAL (not gated behind the
      // opt-in GroundContactDiagnostics flag) so a server operator has visibility into exactly
      // when this known gap may be affecting a live client, without this ever being silently
      // invisible by default.
      // MINECRAFT-545/547 (John's comment 54974): the prior wording of this log claimed a
      // specific resolution path ("until it reconnects or leaves render distance") that was
      // never actually verified at runtime - a source-predicted guess, not observed behavior.
      // Narrowed to state only what is actually confirmed: the server-side clear happened with
      // no notification sent, full stop, with no speculation about if/when/how any affected
      // client's own state is ever corrected.
      com.mojang.logging.LogUtils.getLogger().warn(
        "[DIV] missing-index fallback fired for towed vehicle " + v.uniqueUUID + " - this vehicle's own towing connection was cleared"
          + " server-side with NO client notification packet sent (none of MTS's existing client-sync mechanisms is supported for this"
          + " exact case - see MINECRAFT-547). Whether, or how, any client already tracking this vehicle ever has its own local state"
          + " corrected is NOT established by this log line alone.");
      // 54862/54870: the real, direct, bounded observation - a complete no-op unless a caller
      // has explicitly registered for this exact vehicle UUID, and only counts a match if the
      // connection object captured here is reference-identical to what that caller registered.
      // Captured BEFORE nulling - the field this reads would otherwise already be gone.
      recordFallbackClearConnectionObservation(v.uniqueUUID, v.towedByConnection);
      v.towedByConnection = null;
     }
    });
   }
  });
 }

 /**
  * MINECRAFT-545/547 (John's comment 55196): the actual production per-body three-action
  * composition, extracted to a package-visible, fully generic seam so a test can exercise this
  * EXACT control-flow (not a reimplementation with a reduced/trimmed signature) with injected
  * fake joint/body/pending operations, instead of only being reachable through
  * {@link #cleanupOneRegisteredBody}'s own hardwired statics. Still the same
  * {@link #removeIndependentResources} composition as before this extraction - this is a pure
  * refactor of {@code cleanupOneRegisteredBody}'s own body, not a behavior change.
  */
 static void cleanupOneBodyThreeActions(Runnable releaseJoint, Runnable removeBody, Runnable cleanupPending) {
  removeIndependentResources(
    new java.util.function.BooleanSupplier[]{ () -> true, () -> true, () -> true },
    new Runnable[]{ releaseJoint, removeBody, cleanupPending });
 }
 /**
  * MINECRAFT-545/547 (Wroosbit review, comment 55189): the per-body callback below used to call
  * {@code hitches.release(v)}, {@code b.remove()}, and {@code pendingInheritedVelocity.cleanup(v)}
  * with no isolation between them - a throwing {@code hitches.release(v)} (itself already
  * fail-closed internally since the 55104 round, but still capable of re-throwing its own first
  * failure) skipped the other two steps for that SAME body. Now delegates to
  * {@link #cleanupOneBodyThreeActions}, the same real composition a test can exercise directly.
  */
 private static void cleanupOneRegisteredBody(EntityVehicleF_Physics v, VehicleBody b) {
  if (GroundContactDiagnostics.ENABLED) com.mojang.logging.LogUtils.getLogger().info("[DIV lifecycleDiagnostic] cleanup releasing/removing v=" + v.uniqueUUID);
  cleanupOneBodyThreeActions(() -> hitches.release(v), b::remove, () -> pendingInheritedVelocity.cleanup(v));
 }
 /**
  * MINECRAFT-545/547 (John's comment 55194 - the same bug class one level further out): {@code
  * registry.removeIf(...)} is now internally resilient (captures its own first failure, visits
  * every entry, rethrows at the end) - but a caller that invokes it as a bare statement with no
  * try/catch around THAT call still lets its rethrown failure propagate straight out of {@code
  * cleanup()}/{@code unload()}, skipping every statement after it - specifically the sweeps
  * below that are explicitly independent of {@code BodyRegistry} (they catch an entity already
  * deactivated before being fully registered, e.g. a failed {@code chassisBoundsChanged}
  * reconstruction - Review 53399/ticket MINECRAFT-504). Both methods now run the registry sweep
  * and every independent sweep through {@link #removeIndependentResources} as one flat list of
  * equally-independent steps: one sweep throwing no longer prevents any of the others from
  * running, and only the FIRST exception encountered across the WHOLE method is reported, after
  * every step has been attempted - not swallowed, not allowed to block unrelated progress.
  */
 public static void cleanup() {
  removeIndependentResources(
    new java.util.function.BooleanSupplier[]{ () -> true, () -> true, () -> true, () -> true, () -> true, () -> true, () -> true, () -> true },
    new Runnable[]{
      // Review 53388: same leak this ticket's own deactivate(v) above now guards against - these
      // two removal paths bypass deactivate(v) entirely, so they need the identical cleanup.
      () -> registry.removeIf((v, b) -> !v.isValid, PhysicsBridge::cleanupOneRegisteredBody),
      // Review 53399: the registry sweep above only cleans pendingInheritedVelocity for a
      // vehicle STILL present in BodyRegistry - a vehicle already deactivated is absent from
      // BodyRegistry by the time cleanup() runs, so that callback never fires for it. This sweep
      // is independent of BodyRegistry, keyed only by pendingInheritedVelocity's own staged
      // entries, so it still finds and removes that exact orphan case.
      () -> pendingInheritedVelocity.removeIf(v -> !v.isValid),
      // Ticket MINECRAFT-504: same leak shape as pendingInheritedVelocity above, for the wheel
      // handbrake/H-pattern state - independent of BodyRegistry, swept directly against validity
      // (in addition to advanceWheelAuxInputs's own staleness-timeout sweep, which only covers a
      // client that goes quiet, not an entity invalidated some other way).
      () -> handbrakeMagnitude.keySet().removeIf(v -> !v.isValid),
      () -> handbrakeLastUpdateTick.keySet().removeIf(v -> !v.isValid),
      () -> hPatternTargetByEngine.keySet().removeIf(engine -> !engine.isValid),
      () -> hPatternLastUpdateTick.keySet().removeIf(engine -> !engine.isValid),
      () -> hPatternTrackers.keySet().removeIf(engine -> !engine.isValid),
      () -> wheelAuxOwner.keySet().removeIf(v -> !v.isValid) });
 }
 public static void unload(Object level) {
  removeIndependentResources(
    new java.util.function.BooleanSupplier[]{ () -> true, () -> true, () -> true, () -> true, () -> true, () -> true, () -> true, () -> true },
    new Runnable[]{
      () -> registry.removeIf((v, b) -> b.level() == level, PhysicsBridge::cleanupOneRegisteredBody),
      // Review 53399: same independent sweep as cleanup() above, matched on the unloading level
      // instead of validity - a vehicle's own level comes directly from v (the same accessor
      // active()'s own lifecycle-refresh path already uses), not from any Body, so this needs no
      // BodyRegistry lookup at all.
      () -> pendingInheritedVelocity.removeIf(v -> ((WorldAccessor)v.world).div$level() == level),
      () -> handbrakeMagnitude.keySet().removeIf(v -> ((WorldAccessor)v.world).div$level() == level),
      () -> handbrakeLastUpdateTick.keySet().removeIf(v -> ((WorldAccessor)v.world).div$level() == level),
      () -> hPatternTargetByEngine.keySet().removeIf(engine -> ((WorldAccessor)engine.world).div$level() == level),
      () -> hPatternLastUpdateTick.keySet().removeIf(engine -> ((WorldAccessor)engine.world).div$level() == level),
      () -> hPatternTrackers.keySet().removeIf(engine -> ((WorldAccessor)engine.world).div$level() == level),
      () -> wheelAuxOwner.keySet().removeIf(v -> ((WorldAccessor)v.world).div$level() == level) });
 }
 private static Vec3 vec(Vector3d v) { return new Vec3(v.x,v.y,v.z); }
 /**
  * Per-wheel suspension spring rate {@code stepGround} divides the vehicle's full static load
  * across: calibrated so that, under even per-wheel load (mass*gravity/wheelCount), the per-wheel
  * {@link TireForces#suspension} force exactly balances gravity at HALF of
  * {@code suspensionTravel} compressed - the ride-height equilibrium this ticket's own triage
  * (comment 52830) derived algebraically, generalized from that comment's own assumed 9.81 to
  * whichever gravity magnitude is actually configured. {@code gravity} must be this level's own
  * actual configured gravity magnitude (read fresh by {@code stepGround} every tick, via Sable's
  * {@code DimensionPhysicsData.getGravity}) - NOT a hardcoded Earth 9.81. Confirmed root cause
  * (bytecode-verified against the exact pinned dependency, {@code sable-neoforge-1.21.1:2.0.5}):
  * {@code DimensionPhysics.DEFAULT_GRAVITY} is {@code (0,-11,0)}, not {@code (0,-9.81,0)} - this
  * class previously hard-coded 9.81 here (and in the per-wheel normal clamp below) while the
  * vehicle's actual Sable rigid body falls under 11 m/s^2. Calibrating the spring for a lighter
  * gravity than the body actually experiences under-supports its real weight, so it settles with
  * MORE compression than the intended half-travel equilibrium - sitting lower/closer to the
  * ground by travel*.5*(11/9.81-1), about 18mm at the shared 0.3m default travel - a small but
  * real, persistent, shared (not ATV-specific) sinking, exactly matching the reported "sinks into
  * the ground a bit." Extracted as its own seam (same formula, now parameterized rather than a
  * hand-copied constant) so a settling simulation can exercise it at both the correct and the
  * previously-assumed gravity.
  * @deprecated kept only for existing test call sites that never configure a spring-stiffness
  * multiplier - delegates to {@link #suspensionSpring(double,int,double,double,double)} with
  * the exact calibrated {@code 1.0} (no-op) default (MINECRAFT-528). Every real production call
  * site passes the vehicle's own resolved {@code tuning.suspensionSpringMultiplier} explicitly
  * instead.
  */
 @Deprecated
 static double suspensionSpring(double mass, int wheelCount, double suspensionTravel, double gravity) {
  return suspensionSpring(mass, wheelCount, suspensionTravel, gravity, 1.0);
 }
 /**
  * MINECRAFT-528: {@link #suspensionSpring(double,int,double,double)} generalized to an
  * explicit, configurable, dimensionless {@code springMultiplier} (previously an implicit,
  * unconfigurable {@code 1.0}) - see {@code Overrides.Tuning#suspensionSpringMultiplier}'s own
  * doc for the validated range, default, and the ride-height-equilibrium consequence of
  * configuring it away from 1.0. At {@code springMultiplier==1.0} this is byte-identical to the
  * original formula - the exact "preserve calibrated defaults" equivalence this ticket requires.
  */
 static double suspensionSpring(double mass, int wheelCount, double suspensionTravel, double gravity, double springMultiplier) {
  return mass*gravity/(wheelCount*suspensionTravel*.5)*springMultiplier;
 }
 /**
  * Per-wheel suspension damping rate - critically-damped-ish (0.7x the critical damping
  * coefficient for this wheel's share of the sprung mass) so the settling the convergence
  * simulation checks neither oscillates indefinitely nor is sluggish enough to look like
  * persistent sinking. Same extraction rationale as {@link #suspensionSpring}.
  * @deprecated kept only for existing test call sites that never configure a damping ratio -
  * delegates to {@link #suspensionDamper(double,int,double,double)} with the exact calibrated
  * 0.7 default (MINECRAFT-523). Every real production call site passes the vehicle's own
  * resolved {@code tuning.suspensionDampingRatio} explicitly instead.
  */
 @Deprecated
 static double suspensionDamper(double mass, int wheelCount, double spring) {
  return suspensionDamper(mass, wheelCount, spring, 0.7);
 }
 /**
  * MINECRAFT-523: {@link #suspensionDamper(double,int,double)} generalized to an explicit,
  * configurable, dimensionless damping ratio (replacing the previously-hardcoded {@code .7})
  * - see {@code Overrides.Tuning#suspensionDampingRatio}'s own doc for the validated range and
  * default. At {@code dampingRatio==0.7} this is byte-identical to the old hardcoded formula.
  */
 static double suspensionDamper(double mass, int wheelCount, double spring, double dampingRatio) {
  return 2*Math.sqrt(spring*mass/wheelCount)*dampingRatio;
 }
 /**
  * MINECRAFT-506: the same ride-height-equilibrium calibration as {@link #suspensionSpring},
  * generalized from an assumed-even {@code 1/wheelCount} static load split to this wheel's own
  * geometry-derived {@code loadShare} (see {@link WheelLoadDistribution}). At
  * {@code loadShare == 1.0/wheelCount} this is algebraically IDENTICAL to
  * {@code suspensionSpring(mass,wheelCount,suspensionTravel,gravity)} - the symmetric default
  * case matches to within ordinary floating-point rounding (a different operation ORDER than
  * {@code mass*gravity/wheelCount}, so not guaranteed bit-for-bit, but equal to many more digits
  * than this simulation's own tolerances ever distinguish - verified by
  * {@code WheelLoadDistributionTest}'s own symmetric-case equivalence assertion). For an
  * asymmetric layout this correctly gives the wheel/axle closer to
  * the chassis COM a STIFFER calibrated spring (it is carrying more of the real static load), and
  * one farther away a softer one - still balancing to the SAME travel*.5 ride-height equilibrium
  * either way (the per-wheel anchor offset itself, {@code suspensionTravel*.5}, does not need to
  * change per wheel - only the rate that holds that height under this wheel's own real share of
  * the weight does).
  * @deprecated kept only for existing test call sites that never configure a spring-stiffness
  * multiplier - delegates to {@link #suspensionSpringForShare(double,double,double,double,double)}
  * with the exact calibrated {@code 1.0} (no-op) default (MINECRAFT-528). Every real production
  * call site passes the vehicle's own resolved {@code tuning.suspensionSpringMultiplier}
  * explicitly instead.
  */
 @Deprecated
 static double suspensionSpringForShare(double mass, double loadShare, double suspensionTravel, double gravity) {
  return suspensionSpringForShare(mass, loadShare, suspensionTravel, gravity, 1.0);
 }
 /**
  * MINECRAFT-528: {@link #suspensionSpringForShare(double,double,double,double)} generalized to
  * an explicit, configurable, dimensionless {@code springMultiplier} - same relationship to it
  * that {@link #suspensionSpring(double,int,double,double,double)} has to the plain
  * even-split overload. At {@code springMultiplier==1.0} this is byte-identical to the original
  * formula.
  */
 static double suspensionSpringForShare(double mass, double loadShare, double suspensionTravel, double gravity, double springMultiplier) {
  return mass*loadShare*gravity/(suspensionTravel*.5)*springMultiplier;
 }
 /** {@link #suspensionDamper}'s own share-generalized counterpart - same relationship to it that
  * {@link #suspensionSpringForShare} has to {@link #suspensionSpring}.
  * @deprecated kept only for existing test call sites that never configure a damping ratio -
  * delegates to {@link #suspensionDamperForShare(double,double,double,double)} with the exact
  * calibrated 0.7 default (MINECRAFT-523). */
 @Deprecated
 static double suspensionDamperForShare(double mass, double loadShare, double spring) {
  return suspensionDamperForShare(mass, loadShare, spring, 0.7);
 }
 /**
  * MINECRAFT-523: {@link #suspensionDamperForShare(double,double,double)} generalized to an
  * explicit, configurable, dimensionless damping ratio (replacing the previously-hardcoded
  * {@code .7}) - see {@code Overrides.Tuning#suspensionDampingRatio}'s own doc for the validated
  * range and default. At {@code dampingRatio==0.7} this is byte-identical to the old hardcoded
  * formula - the exact "preserve calibrated defaults" equivalence this ticket requires.
  */
 static double suspensionDamperForShare(double mass, double loadShare, double spring, double dampingRatio) {
  return 2*Math.sqrt(spring*mass*loadShare)*dampingRatio;
 }
 /**
  * MINECRAFT-551: this wheel's own effective ratio for ONE direction (compression or rebound) -
  * {@code directionalOverride} (the vehicle-wide {@code Overrides.Tuning#suspensionDampingRatioCompression}/
  * {@code Rebound}, whichever the caller is resolving) when configured, else exactly {@code
  * axleResolvedRatio} (this wheel's own already axle-resolved {@code suspensionDampingRatio} -
  * {@link AxleSuspensionDistribution#dampingRatios}'s own output). Never a second,
  * independently-defaulted value - an unconfigured direction inherits the SAME per-wheel ratio
  * the symmetric (pre-this-ticket) path already used, so a vehicle that never sets either
  * directional field is byte-identical on every wheel, and explicitly setting both directions to
  * the same value reproduces the corresponding existing symmetric setting exactly.
  */
 static double directionalDampingRatio(Double directionalOverride, double axleResolvedRatio) {
  return directionalOverride != null ? directionalOverride : axleResolvedRatio;
 }
 /**
  * MINECRAFT-520: this wheel's own composed {@code MultiContactFrictionSolver.FrictionContact}
  * brake magnitude, folding the configurable front/rear bias into ONLY the service-pedal
  * component, exactly the way {@link BrakeBiasDistribution}'s own doc promises:
  * <ul>
  * <li>{@code parkingBrakeActive} short-circuits to a flat {@code 1.0} for every wheel,
  * completely bypassing {@code brakeShareRatio} - a parking brake is a full, uniform,
  * all-wheels lock, independent of whatever service-brake bias is configured (the ticket's own
  * "keep parking brake...semantics independent, don't silently convert them into service
  * bias"). This matches the exact pre-ticket value for this case (the old shared
  * {@code brakeMagnitude} was already forced to {@code 1.0} whenever the parking brake was
  * active, for every wheel, regardless of the steering/handbrake branch below).</li>
  * <li>Otherwise, the service pedal's own 0-1 magnitude is scaled by {@code brakeShareRatio}
  * (1.0 for an unconfigured/fallback bias - see {@link BrakeBiasDistribution#shares} - so this
  * multiply is a pure no-op then) BEFORE composing with the separate analog handbrake, which is
  * never itself scaled by the bias - a rear/handbrake-capable wheel keeps getting the handbrake's
  * own full, unscaled magnitude whenever that is larger than the (now possibly biased) service
  * component, same {@code max} composition {@code turnsWithSteer} already governed pre-ticket.</li>
  * </ul>
  * Pure/testable, no Minecraft types - mirrors {@link #suspensionSpringForShare}'s own seam.
  */
 static double perWheelBrakeMagnitude(boolean turnsWithSteer, boolean parkingBrakeActive,
     double servicePedalMagnitude, double handbrakeMagnitude, double brakeShareRatio) {
  if (parkingBrakeActive) return 1.0;
  double biasedService = servicePedalMagnitude * brakeShareRatio;
  return turnsWithSteer ? biasedService : Math.max(biasedService, handbrakeMagnitude);
 }
 /** Default length, in ticks, of {@code Body}'s bounded ground-contact trace (ticket comment
  * 53095/53098) when {@code -Ddiv.debugGroundContactTicks} is unset or invalid. */
 static final int DEFAULT_DEBUG_GROUND_CONTACT_TICKS = 200;
 /** Documented upper bound on that window: this trace is deliberately bounded instrumentation,
  * not permanent capture (see {@code Body}'s own class doc) - 12,000 ticks (10 minutes at the
  * fixed 20tps tick rate) is generous for a single manual test session while still refusing to
  * let a mistyped huge value turn this into unbounded logging. */
 static final int MAX_DEBUG_GROUND_CONTACT_TICKS = 12000;
 /**
  * Pure parse/validate for {@code -Ddiv.debugGroundContactTicks} (ticket comment 53095: the
  * previous hardcoded 200-tick window, counted from placement rather than from control input,
  * missed throttle onset that started later than ~10s after spawn; comment 53098: ship the
  * configurable duration alone first, skip a control-triggered re-arm for now). Extracted the
  * same way as {@link #suspensionSpring} so it is unit-testable without any Minecraft/SLF4J
  * dependency. Never throws - anything not a positive integer within the documented bound
  * falls back to the default.
  */
 static int resolveDebugGroundContactTicks(String raw) {
  if (raw == null) return DEFAULT_DEBUG_GROUND_CONTACT_TICKS;
  int parsed;
  try { parsed = Integer.parseInt(raw.trim()); } catch (NumberFormatException e) { return DEFAULT_DEBUG_GROUND_CONTACT_TICKS; }
  return parsed>0 && parsed<=MAX_DEBUG_GROUND_CONTACT_TICKS ? parsed : DEFAULT_DEBUG_GROUND_CONTACT_TICKS;
 }
 /** Documented upper bound for {@code -Ddiv.experimentalThrottleRampTicks} (ticket comment
  * 53115) - same 1-minute-at-20tps order of magnitude as the ground-contact diagnostic window,
  * since this is also meant to stay a bounded experimental candidate, not an unreviewed open
  * knob. */
 static final int MAX_EXPERIMENTAL_THROTTLE_RAMP_TICKS = 1200;
 /**
  * Pure parse/validate for {@code -Ddiv.experimentalThrottleRampTicks} (ticket comment 53115:
  * "bounded OPT-IN experimental one-second (20 physics ticks) full-scale throttle-rise
  * candidate, configurable/bypassable, no default change for other sessions/vehicles"). 0,
  * unset, or anything invalid (non-numeric, non-positive, non-finite via a malformed string, or
  * over the documented bound) means BYPASSED - the candidate is off, every vehicle/session gets
  * exactly today's behavior unless explicitly opted in. Never silently falls back to some other
  * active value - the only safe default for an unreviewed/misconfigured experimental feature is
  * off, unlike {@link #resolveDebugGroundContactTicks} where the feature is already always-on
  * and only its window length needs a sane fallback.
  */
 static int resolveExperimentalThrottleRampTicks(String raw) {
  if (raw == null) return 0;
  int parsed;
  try { parsed = Integer.parseInt(raw.trim()); } catch (NumberFormatException e) { return 0; }
  return parsed>0 && parsed<=MAX_EXPERIMENTAL_THROTTLE_RAMP_TICKS ? parsed : 0;
 }
 /** Documented upper bound for {@code -Ddiv.experimentalFrictionSolverIterations} (ticket
  * comments 53127/53130). {@code FrictionSolverIterationCostBenchmark} measured cost scaling
  * roughly linearly with iteration count (~0.64us/iteration/call); 256 leaves headroom above the
  * 128 actually benchmarked while still refusing an unbounded/mistyped value. */
 static final int MAX_EXPERIMENTAL_FRICTION_SOLVER_ITERATIONS = 256;
 /**
  * Pure parse/validate for {@code -Ddiv.experimentalFrictionSolverIterations} (ticket comments
  * 53127/53130: "bounded higher-iteration experimental candidate... preserve current production
  * default 4"). 0, unset, or anything invalid (non-numeric, non-positive, over the documented
  * bound) means BYPASSED - {@link io.github.brooswitminecraft.div.experimental.MultiContactFrictionSolver#FRICTION_SOLVER_ITERATIONS}
  * (4) is used, exactly as shipped, for every vehicle/session unless explicitly opted in. Same
  * safe-default-is-off reasoning as {@link #resolveExperimentalThrottleRampTicks} - an
  * unreviewed experimental accuracy knob should never silently activate itself.
  */
 static int resolveExperimentalFrictionSolverIterations(String raw) {
  if (raw == null) return 0;
  int parsed;
  try { parsed = Integer.parseInt(raw.trim()); } catch (NumberFormatException e) { return 0; }
  return parsed>0 && parsed<=MAX_EXPERIMENTAL_FRICTION_SOLVER_ITERATIONS ? parsed : 0;
 }
 private static final int EXPERIMENTAL_FRICTION_SOLVER_ITERATIONS_RESOLVED = resolveExperimentalFrictionSolverIterations(System.getProperty("div.experimentalFrictionSolverIterations"));
 /** The iteration count actually passed to {@code MultiContactFrictionSolver.solveFriction} this
  * run - the experimental candidate's resolved value if opted in, else the shipped default
  * ({@code MultiContactFrictionSolver.FRICTION_SOLVER_ITERATIONS}, 4) unchanged. A plain static
  * method on the outer class (no Minecraft/SLF4J types) so both {@code Body} and the mod's own
  * startup marker (which runs before any {@code Body} exists) can call it. */
 static int effectiveFrictionSolverIterations() {
  return EXPERIMENTAL_FRICTION_SOLVER_ITERATIONS_RESOLVED>0 ? EXPERIMENTAL_FRICTION_SOLVER_ITERATIONS_RESOLVED
    : io.github.brooswitminecraft.div.experimental.MultiContactFrictionSolver.FRICTION_SOLVER_ITERATIONS;
 }
 /** Result of {@link #effectiveMassDamper}: the damper value actually usable this tick (either
  * the candidate or the safe fallback) paired with the raw per-contact effective mass that was
  * attempted, logged separately so a capture can distinguish "candidate bypassed" (NaN) from
  * "candidate attempted" (a real, possibly-degenerate number) without re-deriving it. */
 record EffectiveMassDamperResult(double damper, double effectiveMass) {}
 /**
  * Opt-in per-contact damping-basis candidate (ticket comments 53214/53219): the same critical-
  * damping-ratio formula shape {@code suspensionDamper} already uses ({@code 2*sqrt(spring*m)*.7}),
  * but sourced from the REAL per-contact effective mass along the contact normal
  * ({@code MultiContactFrictionSolver.contactEffectiveMass}, using this body's own actual box
  * mass/inertia and the actual contact point/arm - comment 53219's explicit requirement) instead
  * of the shared {@code mass/wheelCount} basis. Pure/testable, no Minecraft types, same pattern
  * as {@link #suspensionDamper} itself.
  *
  * <p>{@code direction} is the real production call's chassis/suspension-ray direction (the
  * body's own {@code up}, {@code q.transform(WORLD_UP)}) - NOT a re-derivation of the actual
  * terrain surface normal at the hit point, which this code never computes (review comment
  * 53223's correction to this doc's earlier imprecise wording; no geometry change - this was
  * always what the real call site passed, see {@code Body.stepGround}'s own use of `up` for both
  * the suspension and friction math it already shared with this candidate).
  *
  * <p>Guards finite/positive spring, mass, inertia components and point coordinates, a near-zero
  * or far-from-unit {@code direction} (comment 53223: explicitly reject rather than silently
  * normalize or divide-by-near-zero - defensive, since the real call always passes a unit
  * q-transformed up, not a reason to redesign this method's contract), and a non-finite
  * {@code inverseOrientation}, and catches any exception from the solver call itself, falling
  * back to {@code fallbackDamper} (today's shared value) on any non-finite/non-positive/
  * degenerate result - comment 53219's explicit "safe baseline fallback" requirement, so a
  * degenerate contact (e.g. a zero/garbage inertia component, which should never happen but is
  * not re-proven impossible here) can never produce a NaN/negative damper that would destabilize
  * the suspension.
  *
  * <p>NOT shown to be a net improvement - measured across commits 29d2ed3/98bf372/90ed051/02f90a0:
  * a real driven/recontact scenario improved dramatically at one seed and DT, but that result
  * reversed direction at a finer timestep and did not hold across a 72-combo seed/heading/
  * timestep sweep (candidate tipped MORE often than baseline overall). Wired here ONLY as a
  * bounded, explicitly opt-in (default OFF - {@code -Ddiv.experimentalEffectiveMassDamping} must
  * be set to enable it) in-game experiment per comment 53219's direct instruction to let the user
  * play-test it despite that open uncertainty, NOT as a validated fix - see {@code Body}'s own use
  * site for the exact opt-in flag and this ticket's comment history for the full measurement
  * record.
  */
 /**
  * @deprecated kept only for existing test call sites that never configure a damping ratio -
  * delegates to {@link #effectiveMassDamper(double,double,Vector3d,Vector3d,Quaterniond,Vector3d,double,double)}
  * with the exact calibrated 0.7 default (MINECRAFT-523).
  */
 @Deprecated
 static EffectiveMassDamperResult effectiveMassDamper(double spring, double mass, Vector3d point, Vector3d direction,
     Quaterniond inverseOrientation, Vector3d inertiaBody, double fallbackDamper) {
  return effectiveMassDamper(spring, mass, point, direction, inverseOrientation, inertiaBody, 0.7, fallbackDamper);
 }
 /**
  * MINECRAFT-523: {@link #effectiveMassDamper(double,double,Vector3d,Vector3d,Quaterniond,Vector3d,double)}
  * generalized to an explicit, configurable, dimensionless {@code dampingRatio} (replacing the
  * previously-hardcoded {@code .7}) - this is the ticket's explicit "define the interaction with
  * the experimental effective-mass damping candidate so a configured ratio does not silently
  * disappear when that candidate is opted in" requirement: whichever basis (shared share-of-mass,
  * or this candidate's own per-contact effective mass) ends up computing the damper, it always
  * uses the SAME configured ratio, never a second hardcoded {@code .7}. At
  * {@code dampingRatio==0.7} this is byte-identical to the old hardcoded formula.
  */
 static EffectiveMassDamperResult effectiveMassDamper(double spring, double mass, Vector3d point, Vector3d direction,
     Quaterniond inverseOrientation, Vector3d inertiaBody, double dampingRatio, double fallbackDamper) {
  if (!Double.isFinite(spring) || spring<=0 || !Double.isFinite(mass) || mass<=0
    || !Double.isFinite(inertiaBody.x) || inertiaBody.x<=0
    || !Double.isFinite(inertiaBody.y) || inertiaBody.y<=0
    || !Double.isFinite(inertiaBody.z) || inertiaBody.z<=0
    || !Double.isFinite(point.x) || !Double.isFinite(point.y) || !Double.isFinite(point.z)
    || !Double.isFinite(direction.x) || !Double.isFinite(direction.y) || !Double.isFinite(direction.z)
    || !Double.isFinite(inverseOrientation.x) || !Double.isFinite(inverseOrientation.y)
    || !Double.isFinite(inverseOrientation.z) || !Double.isFinite(inverseOrientation.w)) {
   return new EffectiveMassDamperResult(fallbackDamper, Double.NaN);
  }
  double directionLength=Math.sqrt(direction.x*direction.x+direction.y*direction.y+direction.z*direction.z);
  if (!Double.isFinite(directionLength) || directionLength<0.9 || directionLength>1.1) {
   // Comment 53223: reject near-zero/non-unit direction explicitly rather than silently
   // normalizing (which would mask a caller bug) or dividing by a near-zero length. The real
   // production call always passes a unit q-transformed up, so this never fires there.
   return new EffectiveMassDamperResult(fallbackDamper, Double.NaN);
  }
  double effectiveMass;
  try {
   effectiveMass = io.github.brooswitminecraft.div.experimental.MultiContactFrictionSolver.contactEffectiveMass(mass, point, direction, inverseOrientation, inertiaBody);
  } catch (RuntimeException e) { return new EffectiveMassDamperResult(fallbackDamper, Double.NaN); }
  if (!Double.isFinite(effectiveMass) || effectiveMass<=0) return new EffectiveMassDamperResult(fallbackDamper, effectiveMass);
  double damperCandidate = 2*Math.sqrt(spring*effectiveMass)*dampingRatio;
  if (!Double.isFinite(damperCandidate) || damperCandidate<0) return new EffectiveMassDamperResult(fallbackDamper, effectiveMass);
  return new EffectiveMassDamperResult(damperCandidate, effectiveMass);
 }
 /**
  * MINECRAFT-537: assembles {@link RotorForces#angularDampingTorque}'s per-axis result into one
  * WORLD-frame torque vector, the same shape {@code Body.stepRotor} already assembles its
  * cyclic/yaw control torque in (roll along {@code forward}, pitch along {@code right}, yaw along
  * {@code up} - unit vectors that are this body's own local X/Y/Z axes already rotated into world
  * space by this tick's orientation), so it can simply be added to that existing torque before
  * the single shared {@code handle.applyTorqueImpulse(torque.mul(DT))} call - never a second,
  * competing impulse application.
  *
  * <p>{@code angularVelocity} is WORLD-frame (straight off {@code handle.getAngularVelocity}),
  * so each axis's own component is recovered by dotting it with that axis's own world-frame unit
  * vector ({@code right}/{@code up}/{@code forward} are mutually orthonormal - this is an exact
  * projection, not an approximation) before being paired with {@code inertiaBody}'s matching
  * BODY-frame diagonal component - {@code inertiaBody.x} is this box's moment of inertia about its
  * own local X axis (which {@code right} IS, already expressed in world space: {@code right ==
  * up.cross(forward) == q.transform(1,0,0)} for any pure rotation q, since cross products commute
  * with rotation), {@code .y} about local Y ({@code up == q.transform(0,1,0)}), {@code .z} about
  * local Z ({@code forward == q.transform(0,0,1)}) - the exact same axis/inertia-component pairing
  * {@code effectiveMassDamper}'s own {@code contactEffectiveMass} call relies on elsewhere in this
  * class. This intentionally uses the single body's own {@code inertiaBody} (never {@code
  * aggregateInertiaBody}) - {@code stepRotor} does not branch on {@code twoBodyActive} for any of
  * its other torque/impulse application today (see its own class doc), so this follows that same
  * established single-handle pattern rather than introducing two-body awareness nothing else in
  * this method has yet.
  *
  * <p>MINECRAFT-542: reused verbatim (same method, unmodified) by {@code Body.stepFlight} for
  * fixed-wing angular-rate damping - nothing about the assembly above is actually rotor-specific
  * (it only combines a per-axis dissipative torque with this body's own axes/inertia), so the
  * fixed-wing caller passes its own, wholly separate {@code tuning.fixedWingAngularDampingCoefficient}
  * rather than sharing {@code tuning.rotorAngularDampingCoefficient} - see that field's own doc.
  */
 static Vector3d rotorAngularDampingTorque(Vector3d angularVelocity, Vector3d forward, Vector3d right, Vector3d up,
     Vector3d inertiaBody, double dampingCoefficient, double dt) {
  return rotorAngularDampingTorque(angularVelocity, forward, right, up, inertiaBody, dampingCoefficient, dampingCoefficient, dampingCoefficient, dt);
 }
 /**
  * MINECRAFT-554: {@link #rotorAngularDampingTorque(Vector3d,Vector3d,Vector3d,Vector3d,Vector3d,double,double)}
  * with the single {@code dampingCoefficient} split into three independent per-axis coefficients -
  * {@code pitchCoefficient} for the {@code right} axis (same pairing {@code Body.stepRotor}'s own
  * {@code pitchTorque} already uses, {@code inertiaBody.x}), {@code rollCoefficient} for
  * {@code forward} (same pairing {@code Body.stepRotor}'s own {@code rollTorque} already uses,
  * {@code inertiaBody.z}), {@code yawCoefficient} for {@code up} (same pairing {@code
  * Body.stepRotor}'s own {@code yawTorque} already uses, {@code inertiaBody.y}). The single-
  * coefficient overload above now simply delegates here with the same value three times, so every
  * existing caller (including {@code Body.stepFlight}'s wholly separate {@code
  * fixedWingAngularDampingCoefficient} use) is completely unaffected - this reuses, rather than
  * duplicates, the exact per-axis {@link RotorForces#angularDampingTorque} call and assembly
  * {@link #rotorAngularDampingTorque(Vector3d,Vector3d,Vector3d,Vector3d,Vector3d,double,double)}
  * already established (MINECRAFT-537), just with three independently-resolved coefficients
  * instead of one shared value - passing the same coefficient for all three reproduces that exact
  * prior uniform behavior, byte-identical.
  */
 static Vector3d rotorAngularDampingTorque(Vector3d angularVelocity, Vector3d forward, Vector3d right, Vector3d up,
     Vector3d inertiaBody, double pitchCoefficient, double rollCoefficient, double yawCoefficient, double dt) {
  double torqueRight = RotorForces.angularDampingTorque(angularVelocity.dot(right), inertiaBody.x, pitchCoefficient, dt);
  double torqueUp = RotorForces.angularDampingTorque(angularVelocity.dot(up), inertiaBody.y, yawCoefficient, dt);
  double torqueForward = RotorForces.angularDampingTorque(angularVelocity.dot(forward), inertiaBody.z, rollCoefficient, dt);
  return new Vector3d(right).mul(torqueRight).fma(torqueUp, up).fma(torqueForward, forward);
 }
 private static final class Body implements VehicleBody, HitchAnchor {
  private static final Vector3d WORLD_UP = new Vector3d(0,1,0);
  private static final double AIR_DENSITY = 1.225; // sea-level ISA; no altitude/weather model
  // Opt-in, self-limiting wheel-contact trace for the still-open initial-placement/asymmetric-
  // support rollover question (see GroundContactDiagnostics' class doc for the full history/
  // rationale, and why a Minecraft/SLF4J-typed diagnostic is safe to reference from Body but not
  // from the outer PhysicsBridge class or activateOrClassify's generic signature). Logs each
  // wheel's support-ray geometry/result and that tick's suspension/tire inputs for a bounded
  // number of ticks per activated body, counted from ACTIVATION (placement), not from control
  // input - comment 53095 found the previous hardcoded 200-tick (~10s) default too short to
  // reach throttle onset in a session that idled after spawning before actually driving.
  // DEBUG_GROUND_CONTACT_TICKS is now configurable via -Ddiv.debugGroundContactTicks (parsed
  // once, outside the per-tick path, by PhysicsBridge.resolveDebugGroundContactTicks - see its
  // doc for the validated bound and fallback behavior) so a longer window can be requested for a
  // manual test session without a new build. Still bounded, still self-limiting - no
  // control-triggered re-arm (deliberately deferred, comment 53098: unnecessary complexity for
  // this first diagnostic-only candidate when duration alone covers the test). Activation/
  // classification/construction-failure visibility lives on GroundContactDiagnostics instead,
  // called directly from active() (outer class) - that part needs to run even for a vehicle that
  // never reaches Body at all.
  private static final boolean DEBUG_GROUND_CONTACT = GroundContactDiagnostics.ENABLED;
  private static final int DEBUG_GROUND_CONTACT_TICKS = resolveDebugGroundContactTicks(System.getProperty("div.debugGroundContactTicks"));
  // Opt-in experimental throttle-rate-rise candidate (ticket comment 53115) - OFF by default for
  // every vehicle/session (EXPERIMENTAL_THROTTLE_RAMP_TICKS==0 means bypassed, see
  // PhysicsBridge.resolveExperimentalThrottleRampTicks) unless explicitly enabled via
  // -Ddiv.experimentalThrottleRampTicks. This is a handling DESIGN CHOICE being tested against
  // user feedback, not a physics-derived fix - does not touch the solver/CG/friction path at
  // all, only reshapes the commanded drive force stepGround itself computes below. Lives only
  // on stepGround's own call path, so it is applied exactly once per actual GROUND tick and
  // never during flight/rotor stepping (those call stepFlight/stepRotor instead - see step()).
  // The ramp State is a per-instance (not static) field so a body replacement/deactivation
  // (always a fresh `new Body(...)`, never field reuse - see BodyRegistry/replaceIfCategoryChanged)
  // always starts a brand new, zeroed ramp - never carries stale effort across a swap.
  private static final int EXPERIMENTAL_THROTTLE_RAMP_TICKS = resolveExperimentalThrottleRampTicks(System.getProperty("div.experimentalThrottleRampTicks"));
  private final io.github.brooswitminecraft.div.experimental.DriveRateLimiter.State throttleRampState =
    EXPERIMENTAL_THROTTLE_RAMP_TICKS>0 ? new io.github.brooswitminecraft.div.experimental.DriveRateLimiter.State() : null;
  // Opt-in experimental higher-iteration friction-solve candidate (ticket comments 53127/53130) -
  // an accuracy comparison only, NOT an anti-roll fix or rollover cure. Bypassed
  // (EXPERIMENTAL_FRICTION_SOLVER_ITERATIONS==0) for every vehicle/session unless explicitly
  // enabled via -Ddiv.experimentalFrictionSolverIterations, in which case it REPLACES the
  // iteration count passed to solveFriction below - the shipped default
  // (MultiContactFrictionSolver.FRICTION_SOLVER_ITERATIONS, 4) and every other part of the
  // solver/CG/geometry/support-force model are completely unchanged either way.
  // Opt-in experimental per-contact effective-mass damping candidate (ticket comments 53214/
  // 53219) - OFF by default for every vehicle/session unless explicitly enabled via
  // -Ddiv.experimentalEffectiveMassDamping=true. When off, PhysicsBridge.effectiveMassDamper is
  // never called and the per-wheel damper used below is byte-identical to today's shipped
  // suspensionDamper(mass,wheels.size(),spring) value - comment 53219's explicit "verify opt-in
  // off matches baseline" requirement. When on, static spring stiffness/rest-sag and every other
  // part of the force geometry stay exactly as shipped; only the damper's inertial basis changes,
  // per-wheel. NOT shown to be a net improvement (see PhysicsBridge.effectiveMassDamper's own doc
  // for the measured record) - wired here as a bounded, explicitly opt-in in-game experiment at
  // the user's own direct request (comment 53219: "let me test it"), not as a validated fix.
  private static final boolean EXPERIMENTAL_EFFECTIVE_MASS_DAMPING = Boolean.getBoolean("div.experimentalEffectiveMassDamping");
  private static final Logger LOGGER = LogUtils.getLogger();
  private int debugGroundContactTicksRemaining = DEBUG_GROUND_CONTACT ? DEBUG_GROUND_CONTACT_TICKS : 0;
  /** Full-precision "(x,y,z)" - JOML's own Vector3d.toString() truncates to a handful of
   * significant digits (ticket comment 52975 found this made exact forensic reconstruction of a
   * captured log impossible for anything logged via direct string concatenation of a Vector3dc).
   * Every per-tick/per-wheel diagnostic value below goes through this (or a bare double, which
   * Java's own string concatenation already prints at full precision) instead. */
  private static String vfull(org.joml.Vector3dc v) { return "("+v.x()+","+v.y()+","+v.z()+")"; }
  private static String qfull(org.joml.Quaterniondc q) { return "("+q.x()+","+q.y()+","+q.z()+","+q.w()+")"; }
  final ServerLevel level;
  final Overrides.Tuning tuning;
  // Opt-in two-half chassis candidate (ticket comment 53236/53240/53261) - OFF by default for
  // every vehicle/session (TwoBodyLifecycle is never called unless set) - "keep the candidate
  // uninstalled until native validation" (comment 53268). When active, `box`/`handle` below are
  // the LOWER half-body specifically, not a single whole-chassis box.
  //
  // CORRECTION (review 53272): an earlier version of this comment claimed every existing wheel-
  // force/torque application site "needs no changes at all" because every real wheel on this
  // ticket's own ATV belongs to the lower half - WRONG. Routing to the lower handle changes the
  // body receiving impulses from a whole-chassis box (origin at G, full mass/inertia) to a
  // smaller half-box (origin at the lower half's own center, lower mass/inertia) - every point/
  // lever-arm computed relative to G (every wheel's `arm`/`point`, all derived from `center`) is
  // now wrong for `applyImpulseAtPoint` on that body, and the friction solver's impulse sizing
  // (which takes a single shared mass/inertiaBody for every contact) was still being handed the
  // FULL vehicle's mass/inertia while actually acting on the smaller half-body. See stepGround's
  // own "two-body ground-force owner" resolution for the actual fix: per-tick, the wheel set's
  // actual owner half (by TwoBodyChassis.isUpperHalf, not an assumption) is resolved once, every
  // lever arm is re-expressed relative to THAT owner's own center (not G), and that owner's own
  // mass/inertia/live velocity are used throughout - mixed-ownership wheel sets (not realizable
  // for this ticket's own ATV, but not excluded by the geometry alone) are explicitly rejected
  // (logged, forces skipped that tick) rather than silently routed to one half.
  //
  // The position/orientation SYNC back to the vehicle (see step(), which reconstructs G via
  // TwoBodyChassis.vehicleWorldPositionFromLowerBody rather than reading the lower body's own
  // center directly) and construction/cleanup are the other two-body-aware call sites. Uses the
  // body-local/identity joint convention CONFIRMED by both the real native source (comment 53256)
  // AND a live anchor-frame probe run (comment 53273) - the same convention createHitch itself
  // now uses too (MINECRAFT-525, formerly a separately-flagged world-space/echoed-orientation
  // bug). Non-ground stepper modes
  // (flight/rotor) and towing are explicitly unsupported for a two-body vehicle for now (review's
  // "preserve non-ground modes and towing boundaries") - see step()'s own guard.
  // Ticket 53310/53312: this test-build candidate (branch minecraft-503/shared-ground-support,
  // not main) ships with the two-body chassis ON by default - the real, configurable
  // upperMassFraction (f) resolution below is unaffected (Overrides' own FALLBACK.
  // upperMassFraction=0.5; a pack/override can still set a different f). The system property is
  // kept as an explicit kill-switch (-Ddiv.experimentalTwoBodyChassis=false) rather than removed,
  // so this stays a one-line, easily revertible default flip rather than deleting the gate.
  private static final boolean EXPERIMENTAL_TWO_BODY_CHASSIS = !"false".equalsIgnoreCase(System.getProperty("div.experimentalTwoBodyChassis", "true"));
  final boolean twoBodyActive;
  // Diagnostic/test-only (MINECRAFT-526 review 53905/53907 - "last ACTUALLY EXECUTED stepper plus
  // advancing counter recorded inside non-skipped dispatch"): a selected Stepper alone does not
  // prove force application actually ran - M527's own root cause is exactly a selected non-GROUND
  // stepper whose force application is unconditionally skipped by this candidate. Set ONLY at the
  // point step() below actually calls stepGround/stepFlight/stepRotor (never on a skipped tick),
  // so a test can distinguish "dispatch decided X" from "X's force application actually executed"
  // - see debugExecutedStepSnapshot's own doc. Never read by any production code path.
  Stepper lastExecutedStepper;
  long executedStepCount;
  // Non-null only when twoBodyActive - the upper half-body/handle and the fixed joint connecting
  // it to the lower half (box/handle below). Construction/cleanup hold these; stepGround's own
  // owner-resolution logic is what actually targets upperHandle for an upper-owned wheel set.
  private final BoxPhysicsObject upperBox;
  private final RigidBodyHandle upperHandle;
  private final PhysicsConstraintHandle twoBodyJoint;
  // Precomputed once at construction (review 53272) - each half's own real mass/inertia, used by
  // stepGround's owner-resolution instead of the full-vehicle mass/inertiaBody fields whenever a
  // wheel's actual owner is resolved to that half. Zero/unused (never read) when !twoBodyActive.
  private final double lowerMassKg, upperMassKg;
  private final Vector3d lowerInertiaBody, upperInertiaBody;
  // Aggregate ("predictor") basis for the solver's own impulse-sizing math (review
  // 53280/53282/53285) - the vehicle's own real total mass is just `mass` below, unconditional;
  // aggregateInertiaBody is the two halves' combined inertia about the ASSEMBLY COM when
  // twoBodyActive (TwoBodyChassis.aggregateInertiaAboutAssemblyCom), or exactly inertiaBody
  // (byte-identical, zero behavior change) when !twoBodyActive. twoBodyConfig is retained only so
  // stepGround can compute the live, orientation-dependent G->assemblyCOM lever-arm correction
  // each tick (TwoBodyChassis.worldLeverArmCorrectionToAssemblyCom) - null/unused otherwise.
  private final Vector3d aggregateInertiaBody;
  private final io.github.brooswitminecraft.div.experimental.TwoBodyChassis.Config twoBodyConfig;
  // Bounded, one-time diagnostic for the mixed-ownership rejection path (review 53272's "honor
  // actual owner-half geometry or explicitly reject unsupported layouts") - logged once per Body
  // instance, not every tick, so a persistently-mixed vehicle doesn't spam the log forever.
  private boolean loggedMixedOwnershipWarning = false;
  private boolean loggedNonGroundTwoBodyWarning = false;
  private boolean loggedTwoBodyTowingWarning = false;
  // Diagnostic-only (ticket comment 53365: "zero drive ambiguous" - the existing debugGroundContact
  // line above logs throttle/drive/brakeActive but never engine presence/running/gear/fuel, so a
  // vehicle with four wheels and a seat that simply never started its engine looks identical in the
  // log to one failing to drive for some other reason). Read-only - never alters force/control or
  // auto-starts anything. Edge-triggered (logs again whenever the built line actually changes) PLUS
  // a bounded periodic resample, not unconditional per-tick spam.
  private String lastEngineFuelElectricalReceiptDiscreteState = null;
  private int ticksSinceLastEngineFuelElectricalReceipt = ENGINE_FUEL_ELECTRICAL_RECEIPT_PERIOD_TICKS;
  private static final int ENGINE_FUEL_ELECTRICAL_RECEIPT_PERIOD_TICKS = 100;
  // Diagnostic-only (ticket comment 53369: "investigate whether the enclosing two-body chassis
  // hits terrain and bears most weight, starving tire normal/grip" - and 53371's explicit
  // correction: "don't assume whole box bottom scalar if tilted; transform actual corners by
  // native orientation and compare terrain at contact footprint"). Read-only - queries terrain
  // via the same level.clip(...) raycast already used for wheel ground detection; never applies
  // any force or alters the chassis box itself. Same discrete-edge-trigger-plus-periodic pattern
  // as the engine receipt above, now applied consistently: the per-corner CONTINUOUS clearance
  // values always appear in the emitted line, but the trigger itself is the DISCRETE touching/
  // clear boolean per corner, not the raw clearance number (which, like suspension compression,
  // fluctuates almost every tick even when nothing meaningfully changed).
  private String lastChassisClearanceDiscreteState = null;
  private int ticksSinceLastChassisClearanceReceipt = CHASSIS_CLEARANCE_RECEIPT_PERIOD_TICKS;
  private static final int CHASSIS_CLEARANCE_RECEIPT_PERIOD_TICKS = 100;
  final BoxPhysicsObject box;
  final RigidBodyHandle handle;
  final Vector3d center;
  // Diagnostic-only (ticket comment 52976's "collider dimensions and center" ask) - the live
  // Sable box already has this via box.getHalfExtents(), but keeping our own copy avoids adding
  // a new BoxPhysicsObject accessor dependency to this already-large diagnostic surface.
  final Vector3d halfExtents;
  final double mass;
  final boolean flight;
  // A vehicle with a drivable wheel/track OR a flight flag AND a canFloat device (amphibious
  // car, float-equipped seaplane/rotorcraft, ...) gets BOTH its primary domain's forces and
  // buoyancy/water-drag/underwater-propeller forces applied to its float devices in the same
  // tick - combined domain forces rather than its water behavior silently dropping out just
  // because it was classified eligible here for driving or flying. hullVolume is cheap to
  // compute unconditionally and only used when float-capable. Float capability itself is NOT
  // cached here: it must be re-read from the vehicle's current parts every tick in step(), the
  // same way installedPropellers already is - a canFloat device installed or removed after
  // this body is already active (without itself triggering replaceIfCategoryChanged, which
  // only reacts to the ground/flight category, not float capability) must start or stop getting
  // buoyancy on the very next tick, not stay stuck on whatever was true at construction.
  final double hullVolume;
  // Body-local (diagonal, principal-axis) solid-box inertia tensor from the SAME half-extents/
  // mass passed to BoxPhysicsObject above - Sable 2.0.5's native Rapier box collider creation
  // (vendored Rust source, confirmed this ticket) builds its shape as
  // cuboid(half_extents).mass(mass) with no mass-properties override, so Rapier derives inertia
  // purely from this exact geometry/mass via the standard solid-box formula. Validated against
  // real production angularVelocity data to ~1e-5 rad/s over 30 real ticks across two separate
  // placements (ticket comment 53008). Used by the shared multi-contact friction solve below.
  final Vector3d inertiaBody;
  // Opt-in self-righting assist (ticket comment 53182/53188) - resolved once at construction
  // (same lifecycle as tuning itself), not re-resolved every tick.
  final io.github.brooswitminecraft.div.experimental.SelfRightingAssist.Config selfRight;
  /**
   * Ticket 53373/53379/53381/53383: the real, current chassis-collision-volume union, computed
   * fresh from {@code v.definition.collisionGroups} every time this is called (construction, and
   * every tick thereafter for {@link #chassisBoundsChanged} to detect a real part install/remove)
   * - NEVER cached across calls itself, so it always reflects this exact moment's active groups.
   *
   * <p>Evaluates each group's own visibility condition by constructing and running a FRESH
   * {@link minecrafttransportsimulator.baseclasses.AnimationSwitchbox} (both public) rather than
   * reading IV's own lazily-populated {@code v.collisionBoxes} cache - sidesteps that cache's
   * lazy-population timing entirely (the root cause of three regressions in an earlier, reverted
   * attempt - see git history on this method's own prior line range). A group with no animations
   * has no switchbox in IV's own map either and is therefore unconditionally active, mirroring
   * {@code AEntityE_Interactable.updateCollisionBoxes}'s own decompiled null-switchbox branch
   * exactly; a visible group's boxes are transformed by the switchbox's own {@code netMatrix}
   * before being unioned, mirroring that same method's {@code globalCenter.transform(netMatrix)}
   * step - so an animated (moving), not just shown/hidden, group now unions its LIVE position.
   *
   * <p>Deliberately, explicitly NOT supported (narrower than full parity with IV's own general
   * animation system - review 53379/53383's own "constrain rather than claim exact parity"):
   * <ul>
   *  <li>{@code JSONCollisionGroup.health}-based damage exclusion - a separate, rarer "has this
   *  group been shot off" mechanic, unrelated to any part-presence visibility condition.</li>
   *  <li>A group with {@code applyAfter} set but NO animations of its own - treated as
   *  unconditionally active (same as a group with no animations at all) rather than resolving
   *  the chain this would need into IV's own {@code entity.animatedObjectSwitchboxes} map (a
   *  different, non-collision-specific map this class has no reason to otherwise touch). If a
   *  real pack ever uses this exact shape for a collision group, this would wrongly include it
   *  unconditionally - not yet seen in practice, flagged rather than guessed at.</li>
   *  <li>{@code JSONCollisionGroup.collisionTypes} IS constrained - to BLOCK, corrected from an
   *  earlier, WRONG conclusion (review 53392's own correction) that ENTITY was the right type.
   *  ENTITY is {@code mcinterface1211.WrapperAABBCollective}'s own check, but that governs
   *  Minecraft's native ENTITY-vs-entity collision (other mobs/players pushing against this
   *  vehicle) - a different question from "does this group represent the vehicle's own TERRAIN/
   *  BLOCK collision hull," which is what this method (and the actual merc230 bug - a chassis
   *  resting on TERRAIN) needs. {@code AEntityVehicleD_Moving.update()} is the real, confirmed
   *  consumer for that question: it rebuilds its own {@code allBlockCollisionBoxes} field every
   *  tick by filtering {@code allCollisionBoxes} for {@code collisionTypes.contains(BLOCK)} -
   *  this is IV's own vehicle-movement/terrain-collision path, not an interaction/rendering
   *  concern, and {@code VehicleGroundDeviceBox}'s own static default collisionTypes set is ALSO
   *  BLOCK-only, further corroborating it as the standard "collides with terrain" type.
   *  <p>Still NOT fully settled: this corrects the WRONG consumer, but review 53392 also
   *  correctly caught that updating this ticket's own synthetic test fixtures to any type (first
   *  ENTITY, now BLOCK) and finding they still pass only proves internal self-consistency with a
   *  self-chosen filter - it does NOT prove the REAL merc230/quad packs' own JSON actually uses
   *  BLOCK on their real physical-hull groups. Neither pack's real JSON is available to read
   *  directly from this environment (only generic parts/items/decors ship in the base IV jar
   *  cached locally - the actual vehicle content pack is a separate, uninstalled addon) - the
   *  real group types for merc230/quad need to come from the Zippy helper's own read-only
   *  inspection, not assumed from decompiled IV consumer code alone.</li>
   * </ul>
   */
  private static Vector3d[] computeChassisBounds(EntityVehicleF_Physics v) {
   Vector3d min=new Vector3d(Double.POSITIVE_INFINITY), max=new Vector3d(Double.NEGATIVE_INFINITY);
   for (var group : v.definition.collisionGroups) {
    // Review 53393: REVERTED the BLOCK filter immediately on real data - Zippy's own read-only
    // pack receipt shows NEITHER the quad NOR merc230 has a single group with BLOCK (or ENTITY)
    // explicitly set; both packs' real physical-hull groups simply OMIT collisionTypes entirely.
    // A raw-JSON-presence filter on either type would return an EMPTY union for BOTH real
    // vehicles - permanently starving the ALREADY-ACCEPTED, installed quad, not just failing to
    // fix merc230. Not filtering by collisionTypes at all until IV's own loader/legacy-compat
    // resolved-runtime value (not raw JSON presence) is actually understood - see this method's
    // class doc.
    minecrafttransportsimulator.baseclasses.TransformationMatrix netMatrix = null;
    if (group.animations != null && !group.animations.isEmpty()) {
     var switchbox = new minecrafttransportsimulator.baseclasses.AnimationSwitchbox(v, group.animations, group.applyAfter);
     if (!switchbox.runSwitchbox(0f, false)) continue;
     netMatrix = switchbox.netMatrix;
    }
    for (var c : group.collisions) {
     if (netMatrix != null) {
      // Mirrors AEntityE_Interactable.updateCollisionBoxes' own
      // globalCenter.set(localCenter); globalCenter.transform(netMatrix) exactly - an animated
      // group's boxes union at their LIVE transformed center, not their static JSON position.
      var transformedCenter = c.pos.copy().transform(netMatrix);
      min.min(new Vector3d(transformedCenter.x-c.width/2,transformedCenter.y-c.height/2,transformedCenter.z-c.width/2));
      max.max(new Vector3d(transformedCenter.x+c.width/2,transformedCenter.y+c.height/2,transformedCenter.z+c.width/2));
     } else {
      min.min(new Vector3d(c.pos.x-c.width/2,c.pos.y-c.height/2,c.pos.z-c.width/2));
      max.max(new Vector3d(c.pos.x+c.width/2,c.pos.y+c.height/2,c.pos.z+c.width/2));
     }
    }
   }
   return new Vector3d[]{min, max};
  }
  /**
   * Ticket 53383's explicit priority: a vehicle whose parts are installed/removed AFTER this
   * Body already successfully constructed must have its chassis envelope refreshed, not
   * permanently locked in at whatever was active the moment construction first succeeded (the
   * confirmed-real lifecycle gap from review 53379). Compares a freshly recomputed
   * {@link #computeChassisBounds} against this Body's own already-committed {@link #center}/
   * {@link #halfExtents} with a real tolerance (1mm) - never a bare {@code ==}/exact-equality
   * comparison, so ordinary floating-point jitter between ticks (suspension settling, native
   * physics noise) can never itself trigger a rebuild, per 53381's own explicit caution. Returns
   * false (never triggers) if the fresh computation would itself be invalid (no groups/all
   * hidden) - an already-active vehicle transiently losing every active group mid-tick should
   * keep its last-known-good envelope rather than force a doomed rebuild attempt.
   */
  boolean chassisBoundsChanged(EntityVehicleF_Physics v) {
   Vector3d[] bounds;
   try { bounds = computeChassisBounds(v); } catch (RuntimeException ignored) { return false; }
   Vector3d min=bounds[0], max=bounds[1];
   if (!Double.isFinite(min.x) || max.x<=min.x || max.y<=min.y || max.z<=min.z) return false;
   Vector3d freshCenter=new Vector3d(min).add(max).mul(.5);
   Vector3d freshHalf=new Vector3d(max).sub(min).mul(.5);
   double tolerance = 0.001;
   return center.distance(freshCenter) > tolerance || halfExtents.distance(freshHalf) > tolerance;
  }
  Body(ServerLevel level, EntityVehicleF_Physics v, Overrides.Tuning tuning) {
   this.level=level; this.tuning=tuning;
   this.selfRight=Overrides.selfRightingConfig(tuning);
   this.flight=Classification.isFlightVehicle(v.definition);
   mass=tuning.massKg != null ? tuning.massKg : Math.max(50,v.getMass());
   // Ticket 53373: independent source review found this exact unconditional union is why
   // merc230's own chassis bound came out far too large/low - its pack authors a deep
   // "missing-wheel fallback" collision group (bottom -.47) only meant to be active when a wheel
   // part is NOT installed, plus a shallower authored interior-floor group (bottom -.1875) for
   // the normal (wheel-installed) case; the unconditional union included BOTH regardless of
   // which is actually active right now. A first attempt using v.collisionBoxes (IV's own live,
   // lazily-populated active set) was reverted after three live-verified regressions (see git
   // history on this exact line range) - most seriously, that set is reliably EMPTY for the
   // first several ticks after every vehicle spawns, which made every vehicle go non-finite
   // before Body could activate even once the pack-poisoning risk was fixed.
   //
   // This version sidesteps that lazy-population timing entirely: it constructs and evaluates
   // its OWN fresh AnimationSwitchbox per group (AnimationSwitchbox's own constructor and
   // runSwitchbox(float,boolean) are both public) instead of reading IV's cached
   // collisionSwitchboxes map, exactly mirroring AEntityE_Interactable.updateCollisionBoxes' own
   // decompiled logic: a group with no animations (null/empty) has no switchbox in IV's own map
   // either and is unconditionally active; otherwise the SAME visibility condition IV's own
   // native collision/interaction code evaluates decides whether this group's boxes count here.
   // A brand-new instance has no state shared with IV's own cached one, so this never interferes
   // with native rendering/visibility clocks - the only cost is a small amount of duplicate
   // computation, once per vehicle activation, not per tick.
   //
   // Deliberately NOT replicated: JSONCollisionGroup.health-based damage exclusion (a separate,
   // rarer per-group "has this collision group been shot off" mechanic, unrelated to merc230's
   // part-presence visibility condition) - out of scope for this fix, not a hand-rolled
   // workaround for it either way since it was never attempted.
   var bounds = computeChassisBounds(v);
   Vector3d min=bounds[0], max=bounds[1];
   if (!Double.isFinite(min.x) || max.x<=min.x || max.y<=min.y || max.z<=min.z) {
    // Review 53379/53383: all-groups-hidden must NOT permanently poison this vehicle's shared
    // pack definition (InvalidChassisGeometry's own documented contract in activateOrClassify) -
    // that is correct ONLY for a genuine, deterministic pack-authoring defect (collisionGroups
    // itself empty/null); every group existing but all currently hidden (e.g. mid-assembly,
    // before any wheel is installed) is a real, transient, retryable state.
    if (v.definition.collisionGroups == null || v.definition.collisionGroups.isEmpty())
     throw new InvalidChassisGeometry("Vehicle has no finite chassis collision volume");
    throw new IllegalStateException("every collisionGroup is currently hidden for entity="+v.uniqueUUID+" (e.g. no wheel installed yet) - safe to retry next tick");
   }
   center=new Vector3d(min).add(max).mul(.5);
   Vector3d half=new Vector3d(max).sub(min).mul(.5);
   halfExtents=new Vector3d(half);
   this.hullVolume = half.x*2*half.y*2*half.z*2*tuning.hullFillFactor;
   inertiaBody=new Vector3d(mass/3*(half.y*half.y+half.z*half.z),mass/3*(half.x*half.x+half.z*half.z),mass/3*(half.x*half.x+half.y*half.y));
   var r=v.orientation;
   Quaterniond q=new Quaterniond().setFromNormalized(new Matrix3d(r.m00,r.m10,r.m20,r.m01,r.m11,r.m21,r.m02,r.m12,r.m22));
   Vector3d position=q.transform(new Vector3d(center)).add(v.position.x,v.position.y,v.position.z);
   if (DEBUG_GROUND_CONTACT) LOGGER.info("[DIV debugGroundContact] chassisReceipt entity="+v.uniqueUUID+" key="+v.definition.packID+":"+v.definition.systemName
     +" mass="+mass+" center="+vfull(center)+" halfExtents="+vfull(halfExtents)+" hullVolume="+hullVolume
     +" suspensionTravel="+tuning.suspensionTravel+" friction="+tuning.friction+" driveForce="+tuning.driveForce+" brakeForce="+tuning.brakeForce
     // Review 53291's explicit "record actual... resolved f/mass" - mass is already logged just
     // above; upperMassFraction (f) was the one two-body-relevant resolved value this line didn't
     // yet surface, needed to confirm a vehicle actually resolved the fraction its own config
     // entry requested rather than silently falling back to the 0.5 default.
     +" upperMassFraction="+tuning.upperMassFraction
     // Ticket 53343: "add lateralGripMultiplier to the runtime receipt so installer can verify
     // it actually resolved" - same reasoning as upperMassFraction just above, now that
     // lateralGripMultiplier (53331/53332/53335/53337) is itself a resolved-per-vehicle value a
     // config patch can set.
     +" lateralGripMultiplier="+tuning.lateralGripMultiplier
     // Provenance (ticket comment 53186/53188: "prove native/metadata values actually reached
     // runtime rather than merely being parsed") - only the operator-override and documented-
     // fallback levels are wired to a real source yet (comment 53181/53184), so this is exactly
     // those two: whether THIS key has an explicit Overrides.get() entry at all, and this
     // vehicle's own resolved self-right config (OFF unless that entry set all five fields).
     +" explicitOverrideEntry="+(Overrides.get(v.definition.packID+":"+v.definition.systemName)!=null)
     // Comment 53189: "log actual five resolved assist values, not just configured=true" - a
     // capture must be able to show what actually reached runtime, not just a boolean summary.
     +" selfRightConfigured="+(selfRight.strength()>0||selfRight.damping()>0)
     +" selfRightStrength="+selfRight.strength()+" selfRightDamping="+selfRight.damping()+" selfRightMaxTorque="+selfRight.maxTorque()
     +" selfRightFadeStartDegrees="+selfRight.fadeStartDegrees()+" selfRightFadeEndDegrees="+selfRight.fadeEndDegrees()
     // Comment 53219's explicit "log effective option... in existing bounded diagnostics" - the
     // resolved opt-in state itself, once per Body construction; per-wheel damper/effectiveMass
     // receipt is logged separately below, every debug tick, from the real per-wheel values.
     +" experimentalEffectiveMassDamping="+EXPERIMENTAL_EFFECTIVE_MASS_DAMPING
     +" experimentalTwoBodyChassis="+EXPERIMENTAL_TWO_BODY_CHASSIS
     +" nativeInertia=unavailable (Sable 2.0.5's BoxPhysicsObject exposes no native inertia/mass-properties query API - confirmed by its decompiled source; see ticket comment history)");
   twoBodyActive=useTwoBody(EXPERIMENTAL_TWO_BODY_CHASSIS,
     planTick(flight, hasFloatDevice(v), installedPropellers(v)));
   if (DEBUG_GROUND_CONTACT) LOGGER.info("[DIV debugGroundContact] chassisMode entity="+v.uniqueUUID
     +" key="+v.definition.packID+":"+v.definition.systemName+" twoBodyActive="+twoBodyActive
     +" plan="+planTick(flight, hasFloatDevice(v), installedPropellers(v)));
   Vector3d initialVelocity=new Vector3d(v.motion.x,v.motion.y,v.motion.z).mul(v.speedFactor*20);
   // Review 53385: consumed (removed) at most once per construction - a genuinely fresh spawn
   // (no entry) keeps today's exact prior behavior (initialVelocity from v.motion, zero angular)
   // unchanged; a chassisBoundsChanged-triggered rebuild (an entry was staged in `active()`
   // immediately before this vehicle's old Body was removed) instead reseeds BOTH halves' actual
   // angular velocity and a per-half, lever-arm-correct linear velocity via the standard rigid-
   // body point-velocity formula v_new=v_reference+omega x (newPoint-referencePoint) - not a
   // single shared velocity that silently drops rotation and ignores each half's own new offset.
   // Review 53388's concrete correction: NOT removed here - a construction failure (either
   // branch's own catch below) must leave this entry intact so a RETRY can still consume it;
   // removing it unconditionally up front silently lost the inherited angular state on any
   // retry after a transient failure (e.g. Overrides.twoBodyConfig validation, or any other
   // exception on the path to a successful addLinearAndAngularVelocity call). Only removed
   // explicitly below, once, on confirmed success in BOTH branches.
   var inherited = pendingInheritedVelocity.get(v);
   if (twoBodyActive) {
    // Uninstalled/unvalidated candidate (comment 53268) - any construction failure (including
    // Overrides.twoBodyConfig's own validation, e.g. an unsafe upperMassFraction for this
    // vehicle's real mass) must roll back cleanly and surface as an ordinary construction
    // failure, exactly like the single-body path below. TwoBodyLifecycle.create itself
    // guarantees no partial state on ITS OWN failure, but review 53272 correctly caught that the
    // two addLinearAndAngularVelocity calls AFTER a successful create() were not themselves
    // covered - if either throws, the already-created pair/joint would leak with nothing left to
    // clean them up. Wrapped below so both halves are removed on any post-create failure too.
    var config=Overrides.twoBodyConfig(tuning,mass);
    var bodies=TwoBodyLifecycle.create(level,position,q,half,mass,config);
    try {
     if (inherited != null) {
      Vector3d lowerVel=inherited.velocityAt(bodies.lowerBox().getPose().position());
      Vector3d upperVel=inherited.velocityAt(bodies.upperBox().getPose().position());
      bodies.lowerHandle().addLinearAndAngularVelocity(lowerVel,new Vector3d(inherited.angularVelocity()));
      bodies.upperHandle().addLinearAndAngularVelocity(upperVel,new Vector3d(inherited.angularVelocity()));
     } else {
      bodies.lowerHandle().addLinearAndAngularVelocity(initialVelocity,new Vector3d());
      bodies.upperHandle().addLinearAndAngularVelocity(initialVelocity,new Vector3d());
     }
    } catch (RuntimeException ex) {
     TwoBodyLifecycle.remove(level,bodies);
     throw ex;
    }
    if (inherited != null) pendingInheritedVelocity.consumeOnSuccess(v); // success-only consume (review 53388)
    box=bodies.lowerBox(); handle=bodies.lowerHandle();
    upperBox=bodies.upperBox(); upperHandle=bodies.upperHandle(); twoBodyJoint=bodies.joint();
    lowerMassKg=io.github.brooswitminecraft.div.experimental.TwoBodyChassis.lowerMassKg(mass,config);
    upperMassKg=io.github.brooswitminecraft.div.experimental.TwoBodyChassis.upperMassKg(mass,config);
    lowerInertiaBody=io.github.brooswitminecraft.div.experimental.TwoBodyChassis.lowerOwnInertia(mass,half.x,half.y,half.z,config);
    upperInertiaBody=io.github.brooswitminecraft.div.experimental.TwoBodyChassis.upperOwnInertia(mass,half.x,half.y,half.z,config);
    aggregateInertiaBody=io.github.brooswitminecraft.div.experimental.TwoBodyChassis.aggregateInertiaAboutAssemblyCom(mass,half.x,half.y,half.z,config);
    twoBodyConfig=config;
   } else {
    upperBox=null; upperHandle=null; twoBodyJoint=null;
    lowerMassKg=0; upperMassKg=0; lowerInertiaBody=new Vector3d(); upperInertiaBody=new Vector3d();
    aggregateInertiaBody=inertiaBody; twoBodyConfig=null;
    box=new BoxPhysicsObject(new Pose3d(position,q,new Vector3d(),new Vector3d(1)),half,mass);
    var physics=SubLevelContainer.getContainer(level).physicsSystem();
    physics.addObject(box);
    // From here on, box is a live Sable object; any failure before this constructor returns
    // must remove it again, since nothing else will ever hold a reference to clean it up.
    RigidBodyHandle h;
    try {
     h=RigidBodyHandle.of(level,box);
     if (inherited != null) {
      Vector3d seededVel=inherited.velocityAt(box.getPose().position());
      h.addLinearAndAngularVelocity(seededVel,new Vector3d(inherited.angularVelocity()));
     } else {
      h.addLinearAndAngularVelocity(initialVelocity,new Vector3d());
     }
    } catch (RuntimeException ex) {
     physics.removeObject(box);
     throw ex;
    }
    if (inherited != null) pendingInheritedVelocity.consumeOnSuccess(v); // success-only consume (review 53388)
    handle=h;
   }
  }
  public ServerLevel level() { return level; }
  public Overrides.Tuning tuning() { return tuning; }
  // HitchAnchor (MINECRAFT-545): box/center already existed for this body's own wheel/joint
  // geometry above - exposed here, read-only, so createHitch can treat this side of a joint the
  // same way it now treats a BuoyantBody.
  public BoxPhysicsObject box() { return box; }
  public Vector3dc center() { return center; }
  /**
   * MINECRAFT-545/547 (John's comment 55116 - "partial two-body survivor cleanup"): made
   * idempotent and partial-survivor-safe. The single-body branch now skips the native call
   * entirely once {@code box} already reports inactive - calling {@code removeObject} a second
   * time on an already-removed box is never attempted, not merely tolerated.
   * {@code TwoBodyLifecycle.remove} is still used for the common case (both halves genuinely
   * alive) UNCHANGED - that file's own existing {@code removeBoth}/{@code removeJoint} already
   * independently try/catch each half and the joint, matching this class's own established
   * per-step-guarded pattern ({@link HitchRegistry#release}). The NEW case this method now
   * handles is PARTIAL survival (exactly one half already inactive, confirmed via
   * {@link #isActive} no longer being the only caller that distinguishes this): delegated to
   * {@link #removeIndependentResources} (NOT {@code TwoBodyLifecycle.remove}, which always calls
   * removeObject on BOTH boxes unconditionally) - only the box(es) still genuinely active, and
   * the joint only if still valid, are actually touched; a throwing removal never prevents the
   * other resource's own removal attempt. If BOTH halves are already inactive and the joint is
   * already invalid too, this is a true no-op - nothing left to remove. {@code
   * removeIndependentResources} is extracted as its own generic, directly-testable method
   * (John's comment 55126) so a test exercises this EXACT branching logic with injected fake
   * leaf operations, rather than a parallel reimplementation.
   */
  public void remove() {
   var c=SubLevelContainer.getContainer(level);
   if (c==null) return;
   if (twoBodyActive) {
    boolean boxActive=box.isActive(), upperActive=upperBox.isActive();
    if (boxActive && upperActive) {
     TwoBodyLifecycle.remove(level, new TwoBodyLifecycle.Bodies(upperBox,box,upperHandle,handle,twoBodyJoint));
     return;
    }
    removeIndependentResources(
      new java.util.function.BooleanSupplier[]{ () -> twoBodyJoint!=null && twoBodyJoint.isValid(), () -> boxActive, () -> upperActive },
      new Runnable[]{ () -> twoBodyJoint.remove(), () -> c.physicsSystem().removeObject(box), () -> c.physicsSystem().removeObject(upperBox) });
   }
   else if (box.isActive()) c.physicsSystem().removeObject(box);
  }
  /**
   * MINECRAFT-545/547 (John's comment 55103 - "Body.isActive currently lower-box only"): for a
   * two-body vehicle, {@code step()} (see the {@code upperHandle.getLinearVelocity}/
   * {@code getAngularVelocity} calls below, gated on {@code twoBodyActive}) touches BOTH boxes'
   * own native handles, not just the primary {@code box} - a partial-invalidity state (one box
   * removed, the other not) is exactly as crashable as the single-body case this guard was
   * originally built for. Both boxes must report active for this body to be considered safely
   * steppable; for a non-two-body vehicle ({@code twoBodyActive == false}), {@code upperBox} is
   * always {@code null} (see this constructor's own else-branch) and irrelevant, so this
   * correctly reduces to the original single-box check.
   */
  public boolean isActive() { return box.isActive() && (!twoBodyActive || upperBox.isActive()); }
  // MINECRAFT-545/547 (John's comment 54365): a real, independently-checkable freshness marker
  // for box.getPose()'s cached value - the Minecraft gameTime at which THIS body's own
  // box.updatePose() last actually ran, set immediately below it. Diagnostic bookkeeping only
  // (a plain field write every tick); does not change what step() computes or applies.
  long debugLastPoseUpdateGameTime = -1;
  // MINECRAFT-545/547 (John's comment 54595/54596): see BuoyantBody's own identical field doc -
  // a real invocation count, independent of gameTime, for an "exactly-once per tick" invariant
  // the timestamp alone cannot support. Diagnostic bookkeeping only.
  long debugStepInvocationCount = 0;

  public void step(EntityVehicleF_Physics v) {
   // MINECRAFT-545/547 (John's comment 54600): incremented first - see BuoyantBody's own
   // identical fix/doc - a true entry count, not a post-updatePose count.
   debugStepInvocationCount++;
   box.updatePose();
   debugLastPoseUpdateGameTime = level.getGameTime();
   Pose3d pose=new Pose3d(box.getPose());
   if (twoBodyActive) {
    // box/pose here is the LOWER half-body's own pose (its own center), NOT G - every other
    // part of this class's geometry (center, half, inertiaBody, every wheel anchor) is expressed
    // relative to G, so the synced vehicle position must be the reconstructed G, not the lower
    // body's own center. Orientation is used as-is (the lower body's own) - the joint locks all
    // 6 axes with an identity relative local frame (comment 53256), so reporting either half's
    // orientation is equivalent while the joint is actually holding.
    pose.position().set(io.github.brooswitminecraft.div.experimental.TwoBodyChassis.vehicleWorldPositionFromLowerBody(pose.position(), pose.orientation(), halfExtents.y));
   }
   Quaterniond q=new Quaterniond(pose.orientation()), inverse=new Quaterniond(q).invert();
   Vector3d velocity=handle.getLinearVelocity(new Vector3d()), angular=handle.getAngularVelocity(new Vector3d());
   Vector3d up=q.transform(new Vector3d(0,1,0));
   // Re-derived from the vehicle's current parts every tick, not cached from construction: a
   // rotor propeller installed, removed, or replaced after this body is already active must
   // switch solvers (or rotor radius) immediately, not keep running the stale one. Computed
   // unconditionally (not gated on flight) - an installed rotor drives ROTOR dispatch even on a
   // vehicle with no aircraft/blimp flag (see Classification#isRotorcraft and selectStepper).
   var installedPropellers = installedPropellers(v);
   // Also re-derived every tick, not cached at construction (self-review finding): a canFloat
   // device installed or removed later doesn't change the ground/flight category, so it never
   // triggers replaceIfCategoryChanged - this is the only place that would otherwise notice.
   var plan = planTick(flight, hasFloatDevice(v), installedPropellers);
   // Review 53272's "preserve non-ground modes and towing boundaries" - this unvalidated
   // candidate is bounded to the pure ground-wheel-force path only (stepGround's own owner-
   // resolution fix). FLIGHT/ROTOR and buoyancy both apply impulses via `handle` (now the lower
   // half specifically) with their own lever-arm assumptions never audited for two-body
   // correctness, unlike stepGround - rather than risk silently misapplying those, skip them
   // entirely for a two-body vehicle (logged once) until they get the same treatment.
   if (twoBodyActive && plan.stepper()!=Stepper.GROUND && !loggedNonGroundTwoBodyWarning) {
    LOGGER.error("[DIV twoBodyChassis] entity="+v.uniqueUUID+" selected a non-GROUND stepper ("+plan.stepper()+") - unsupported for this candidate; skipping force application for this mode.");
    loggedNonGroundTwoBodyWarning=true;
   }
   boolean skipNonGroundForTwoBody = twoBodyActive && plan.stepper()!=Stepper.GROUND;
   if (!skipNonGroundForTwoBody) { lastExecutedStepper = plan.stepper(); executedStepCount++; }
   boolean primaryAwake = skipNonGroundForTwoBody ? false : switch (plan.stepper()) {
    case GROUND -> stepGround(v,pose,q,inverse,angular,velocity,up);
    case FLIGHT -> stepFlight(v,pose,q,inverse,velocity,up);
    case ROTOR -> stepRotor(v,q,up,installedPropellers);
   };
   // MINECRAFT-545/547 (John's comments 54706/54709, gate updated for 55129, construction site
   // fixed for 55138 - "eager massData construction affects ground/flight/two-body paths even
   // when no buoyancy call"): the ORIGINAL version of this line built boundedDragMassData
   // BEFORE the `!twoBodyActive && plan.appliesBuoyancy()` check below - a Matrix3d inversion
   // plus a new MassData instance, done on EVERY Body.step() call regardless of vehicle type,
   // even for ground/flight/two-body vehicles that never call BuoyantForces.apply at all this
   // tick. Moved inside the short-circuit (as the LAST argument expression of the apply() call
   // itself) so Java's own && short-circuit evaluation means this is now constructed ONLY when
   // this Body is actually buoyancy-eligible AND not two-body-active - the same work, now done
   // only when it is actually needed. This Body's own `box` is a plain BoxPhysicsObject (not the
   // shape-aware variant) - its getMassTracker() would silently give Sable's shape-ignorant
   // mass/6 fallback, so a correct solid-box MassData is built directly from this Body's own
   // mass/halfExtents fields instead, unless BuoyantForces.UNBOUNDED_DRAG_OVERRIDE is set (never
   // in any shipped/default configuration), in which case null is passed deliberately -
   // BuoyantForces.apply itself recognizes that exact combination (override set, massData null)
   // as the intentional old-path selection, not a contract violation (see that method's own
   // updated doc, 55138 item 3).
   boolean floatAwake = !twoBodyActive && plan.appliesBuoyancy() && BuoyantForces.apply(level,v,tuning,center,hullVolume,pose,q,inverse,velocity,angular,handle,
     BuoyantForces.UNBOUNDED_DRAG_OVERRIDE ? null : BoxInertia.solidBoxMassData(mass,halfExtents));
   if (primaryAwake || floatAwake) box.wakeUp();
   // Sable is authoritative. The pose here is the most recent completed simulation step.
   Vector3d origin=new Vector3d(pose.position()).sub(q.transform(new Vector3d(center)));
   v.position.set(origin.x,origin.y,origin.z);
   Matrix3d m=new Matrix3d().set(q);
   var r=v.orientation;
   r.m00=m.m00();r.m01=m.m10();r.m02=m.m20();
   r.m10=m.m01();r.m11=m.m11();r.m12=m.m21();
   r.m20=m.m02();r.m21=m.m12();r.m22=m.m22();r.convertToAngles();
   v.motion.set(velocity.x,velocity.y,velocity.z).scale(1/(20*v.speedFactor));
  }
  public InheritedRigidBodyVelocity captureReplacementState(EntityVehicleF_Physics v) {
   return PhysicsBridge.captureReplacementState(v, box, handle, center, twoBodyActive, halfExtents.y);
  }
  private boolean stepGround(EntityVehicleF_Physics v, Pose3d pose, Quaterniond q, Quaterniond inverse, Vector3d angular, Vector3d velocity, Vector3d up) {
   double rawDrive=0;
   for(var part:v.allParts) if(part instanceof PartEngine engine && engine.running)
    rawDrive=Math.signum(engine.currentGearVar.currentValue)*v.throttleVar.currentValue
      *enginePartDriveForce(engine.definition.packID+":"+engine.definition.systemName, tuning.driveForce)
      *gearForceScale(gearRatioMagnitudeAt(engine,(int)engine.currentGearVar.currentValue),
          gearRatioMagnitudeAt(engine,1), Boolean.TRUE.equals(tuning.gearRatioAware));
   // G29/wheel analog brake pedal strength (ticket MINECRAFT-504, comment 52840/53522) - IV's own
   // brakeVar.currentValue (ComputedVariable) already carries the real 0-1 pedal magnitude from
   // its ControlsJoystick.CAR_BRAKE axis (isActive is merely currentValue>0 - see that class's own
   // doc); collapsing straight to isActive here silently threw that magnitude away. Parking brake
   // is a plain toggle with no analog magnitude of its own, so it forces full (1.0) braking rather
   // than contributing a magnitude. Clamped defensively since nothing upstream guarantees the
   // ComputedVariable stays within 0-1 (e.g. a modpack-side script writing it directly).
   double brakeMagnitude=v.parkingBrakeVar.isActive?1.0:Math.max(0.0,Math.min(1.0,v.brakeVar.currentValue));
   // Analog handbrake (ticket MINECRAFT-504, comment 53572/53603) - a genuinely separate,
   // independently calibrated input (own axis/deadzone/inversion, read and set via
   // setHandbrakeMagnitude), not conflated with the service brake pedal above or the native
   // digital parking-brake toggle, and applied PER-WHEEL below (rear/handbrake-capable wheels
   // only), not folded uniformly into brakeMagnitude the way an earlier version of this ticket
   // did - a real handbrake locks the rear wheels, it is not simply a second service-brake lever
   // for every wheel. Defaults to 0.0 (no effect) for any vehicle never sent a handbrake
   // magnitude at all - a missing channel is never treated as "fully applied".
   double handbrakeMagnitudeThisTick=handbrakeMagnitude.getOrDefault(v,0.0);
   // Still vehicle-wide for the throttle-ramp reset below, which only needs "is ANY brake
   // engaged this tick" - the per-wheel split only matters to the friction solve itself.
   boolean braking=brakeMagnitude>0.0||handbrakeMagnitudeThisTick>0.0;
   // Opt-in experimental throttle-rate-rise candidate (ticket comment 53115) - bypassed
   // (drive==rawDrive) unless EXPERIMENTAL_THROTTLE_RAMP_TICKS>0. The rate is THIS vehicle's own
   // configured drive scale / the configured tick count, not a fixed newtons-per-tick constant,
   // so it scales across vehicle types rather than needing a per-vehicle fudge factor. Braking
   // resets the ramp's stored effort every tick it's held (DriveRateLimiter.shape) so releasing
   // the brake can never reveal a drive ramp that silently kept climbing underneath it. Rejects
   // a non-finite/non-positive driveForce (which would make the rate itself invalid) by falling
   // back to the raw, unshaped value rather than dividing into a broken rate.
   double drive=rawDrive;
   if (throttleRampState != null && Double.isFinite(tuning.driveForce) && tuning.driveForce>0) {
    double maxDeltaPerTick=tuning.driveForce/EXPERIMENTAL_THROTTLE_RAMP_TICKS;
    drive=io.github.brooswitminecraft.div.experimental.DriveRateLimiter.shape(throttleRampState,rawDrive,braking,maxDeltaPerTick);
   }
   // Tracked vehicles steer by differential track speed, not a steerable wheel; cars/trucks
   // keep steering the wheels marked turnsWithSteer. Both can coexist on the same chassis.
   boolean skidSteer=v.definition.motorized != null && v.definition.motorized.hasSkidSteer;
   var wheels=groundContacts(v);
   // Read fresh every tick (never cached - a datapack reload or per-dimension override must take
   // effect on the very next tick, the same "re-derive every tick" discipline this class already
   // applies to installed parts). Magnitude, not just the Y component, so this stays correct even
   // for a dimension whose configured gravity vector is not purely vertical - the same pattern
   // Sable's own code uses at this exact call (see e.g. its Create-compat ejector mixin). Kept
   // here on Body rather than as an outer static method: Body already depends on ServerLevel/
   // Sable types throughout, while the outer class's static methods are deliberately
   // Minecraft-type-free so they stay loadable and testable without a live Minecraft/NeoForge
   // runtime (see VERIFICATION.md) - adding a ServerLevel-typed method to the outer class would
   // pull that requirement into every one of its pure-function unit tests.
   double gravity=DimensionPhysicsData.getGravity(level).length();
   // Nominal, even-split spring/damper - kept ONLY for the diagnostic log line below (so a
   // capture still reports a single representative rate instead of a whole per-wheel array).
   // MINECRAFT-506: the ACTUAL per-wheel force calculation below no longer uses these two
   // variables at all - it uses wheelSpring/wheelDamper, computed per wheel from that wheel's
   // own geometry-derived static load share (see WheelLoadDistribution).
   double spring=suspensionSpring(mass,wheels.size(),tuning.suspensionTravel,gravity,tuning.suspensionSpringMultiplier);
   double damper=suspensionDamper(mass,wheels.size(),spring,tuning.suspensionDampingRatio);
   boolean contact=false;
   boolean debugThisTick=DEBUG_GROUND_CONTACT && debugGroundContactTicksRemaining>0;
   int debugTick=DEBUG_GROUND_CONTACT_TICKS-debugGroundContactTicksRemaining;
   if (debugThisTick) LOGGER.info("[DIV debugGroundContact] entity="+v.uniqueUUID+" side="+(v.world.isClient()?"client":"server")+" tick="+debugTick
     +" mass="+mass+" gravity="+gravity
     // solver/iterations identity (review comment 53052) - so a capture taken against this build
     // can be proven to have come from this path, not an older single-shot per-wheel one. Logs
     // the EFFECTIVE iteration count (ticket comments 53127/53130) - equal to
     // MultiContactFrictionSolver.FRICTION_SOLVER_ITERATIONS whenever the experimental candidate
     // is bypassed, so a capture always shows what actually ran, not just what's configured.
     +" solver=MultiContactFrictionSolver iterations="+effectiveFrictionSolverIterations()
     +" wheels="+wheels.size()+" spring="+spring+" damper="+damper+" pose.position="+vfull(pose.position())+" orientation="+qfull(q)
     +" up="+vfull(up)+" linearVelocity="+vfull(velocity)+" angularVelocity="+vfull(angular)
     +" drive="+drive+" braking="+braking+" throttle="+v.throttleVar.currentValue+" rudder="+v.rudderInputVar.currentValue
     +" brakeMagnitude="+brakeMagnitude+" handbrakeMagnitude="+handbrakeMagnitudeThisTick+" brakeActive="+v.brakeVar.isActive+" parkingBrakeActive="+v.parkingBrakeVar.isActive+" skidSteer="+skidSteer
     // Raw vs shaped drive + ramp identity (ticket comment 53115) - driveRaw==drive and
     // throttleRampTicks=0 together prove the experimental candidate is bypassed on this build/
     // session; otherwise shows exactly which configured tick count shaped this tick's drive.
     +" driveRaw="+rawDrive+" throttleRampTicks="+EXPERIMENTAL_THROTTLE_RAMP_TICKS);
   if (debugThisTick) logEngineFuelElectricalReceiptIfDue(v, debugTick);
   if (debugThisTick) logChassisCornerGroundClearanceIfDue(v, up, debugTick);
   // Phase 1: raycast every wheel and compute its vertical/suspension force exactly as before -
   // suspension is per-wheel and unaffected by this change. Friction/slip is sampled at the
   // point the tire impulse is actually applied (the terrain hit, `point` below), not the
   // suspension anchor (`arm`, used only for the vertical damping sample - correct there, since
   // that's the point whose own motion determines the raycast length's rate of change). Those
   // two points differ by roughly radius+compression along `up`; on a rotating chassis, velocity
   // differs between them by angular x that offset (fixed by sampling at `point` - 691f11b).
   // Aggregate ("predictor") basis (review 53280/53282/53285's correction of 53272/53277): the
   // solver's own math that DIVIDES BY AN INERTIA TENSOR to predict what velocity change a
   // candidate impulse produces - Phase 1's effectiveMassDamper, the Phase 2 torque-accumulation
   // loop, and MultiContactFrictionSolver.solveFriction's own mass/inertia inputs below - must
   // use the vehicle's REAL total mass (`mass`, unconditional, nothing new) and the two halves'
   // combined inertia ABOUT THE ASSEMBLY COM (aggregateInertiaBody), with a lever arm measured
   // from that SAME assembly COM (predictorLeverArmCorrection) - NOT either half's own isolated
   // mass/inertia with an owner-relative arm, confirmed by TwoBodyImpulseResponseProbe's own real
   // CI evidence to be off by ~4-10x from the real joint-coupled response. comVelocity is the
   // assembly's own real COM velocity (the mass-weighted average of both halves' live velocities)
   // - the correct STARTING state for that same accumulation, since F/M always gives the COM's
   // own velocity change regardless of where F is applied, which is a different point than
   // either half's own center whenever the halves have unequal mass (f != 0.5) or any height at
   // all. PURE KINEMATICS with no inertia division (Phase 1's localVelocity, Phase 3's
   // contactVelocity and the actual native application point) stay on the owner-half-relative
   // basis just below instead - mathematically equivalent to this COM basis for velocity
   // sampling alone (same real shared angular velocity either way), and REQUIRED for the actual
   // native applyImpulseAtPoint call regardless. For the single-body path this whole aggregate
   // basis is byte-identical to today's values (predictorLeverArmCorrection is exactly the zero
   // vector - Ccom==G always for one body - and aggregateInertiaBody==inertiaBody).
   Vector3d predictorLeverArmCorrection=twoBodyActive
     ? io.github.brooswitminecraft.div.experimental.TwoBodyChassis.worldLeverArmCorrectionToAssemblyCom(q,halfExtents.y,twoBodyConfig)
     : new Vector3d();
   Vector3d comVelocity;
   if (twoBodyActive) {
    Vector3d upperVelocity=upperHandle.getLinearVelocity(new Vector3d());
    comVelocity=new Vector3d(velocity).mul(lowerMassKg).add(new Vector3d(upperVelocity).mul(upperMassKg)).div(mass);
   } else {
    comVelocity=velocity;
   }
   // GroundOwner (review 53272/53277, narrowed by 53280/53282/53285): carries ONLY what the
   // real owning half's own native handle needs - its own live velocity/angular (for the pure-
   // kinematics uses above) and the world-frame lever-arm correction to re-express a G-relative
   // point/arm relative to THAT half's own center (Cowner+(armOld+correction)==P - see
   // TwoBodyChassis.worldLeverArmCorrection's own doc). No longer carries mass/inertia - every
   // use that divided by inertia now uses the aggregate basis above instead (review's "aggregate
   // COM prediction basis... actual owner-local point for native application"). For the single-
   // body path this is exactly today's values (zero correction, no change).
   record GroundOwner(RigidBodyHandle handle, Vector3d ownerVelocity, Vector3d ownerAngular, Vector3d leverArmCorrection) {}
   GroundOwner lowerCandidate=new GroundOwner(handle, velocity, angular,
     twoBodyActive?io.github.brooswitminecraft.div.experimental.TwoBodyChassis.worldLeverArmCorrection(q,halfExtents.y,false):new Vector3d());
   GroundOwner upperCandidate=twoBodyActive
     ? new GroundOwner(upperHandle,upperHandle.getLinearVelocity(new Vector3d()),upperHandle.getAngularVelocity(new Vector3d()),
         io.github.brooswitminecraft.div.experimental.TwoBodyChassis.worldLeverArmCorrection(q,halfExtents.y,true))
     : lowerCandidate; // never actually selected when !twoBodyActive (isUpperHalf is always false then) - just needs to be non-null
   // upperOwned (review 53272): this wheel's actual owner half when twoBodyActive, by
   // TwoBodyChassis.isUpperHalf on its own local anchor Y relative to G - NOT an assumption that
   // every wheel is lower-owned. Always false when !twoBodyActive (meaningless, unused there).
   // wheelIndex (MINECRAFT-519): this wheel's own position in `wheels` (and therefore in
   // loadShareX/loadShareZ below) - carried alongside the hit purely so the anti-roll pass after
   // Phase 1 can map a hit back to that wheel's geometric pairing without re-deriving it, and so
   // it can rebuild this record with an updated `normal` (see withNormal) after redistributing
   // paired load. Records have no mutable fields by design - replacing the whole record, rather
   // than mutating `normal` in place, keeps every other already-computed field (compression,
   // localVelocity, damperUsed, ...) untouched and exactly what Phase 1 actually measured.
   // MINECRAFT-510: contactBlockPos/contactFace are the EXACT raycast result Phase 1 already
   // computed (terrainHit.getBlockPos()/getDirection() below) - carried through unchanged by
   // both withNormal/withDriveShareRatio exactly like every other already-measured field, so a
   // later consumer (WheelGroundContactEvent, fired in Phase 3) never re-derives or floors a
   // block position from the transformed/owner-relative `point` vector, which is not a
   // substitute for the original raycast hit (review finding, comment 55238/M55554611).
   record WheelHit(PartGroundDevice wheel, double radius, Vector3d arm, Vector3d start, Vector3d end, double length,
     double compression, Vector3d localVelocity, double normal, Vector3d forward, Vector3d right, Vector3d point,
     double skidSteerMultiplier, double brakeShareRatio, double driveShareRatio, double damperUsed, double effectiveMassUsed, boolean upperOwned, GroundSurface surface,
     int wheelIndex, net.minecraft.core.BlockPos contactBlockPos, net.minecraft.core.Direction contactFace) {
    WheelHit withNormal(double newNormal) {
     return new WheelHit(wheel,radius,arm,start,end,length,compression,localVelocity,newNormal,forward,right,point,
       skidSteerMultiplier,brakeShareRatio,driveShareRatio,damperUsed,effectiveMassUsed,upperOwned,surface,wheelIndex,contactBlockPos,contactFace);
    }
    // MINECRAFT-532: lets the post-Phase-1 axle drive-force-sharing pass (below) rebuild this
    // record with a redistributed driveShareRatio - same "replace the whole record" shape as
    // withNormal immediately above, for the same reason.
    WheelHit withDriveShareRatio(double newDriveShareRatio) {
     return new WheelHit(wheel,radius,arm,start,end,length,compression,localVelocity,normal,forward,right,point,
       skidSteerMultiplier,brakeShareRatio,newDriveShareRatio,damperUsed,effectiveMassUsed,upperOwned,surface,wheelIndex,contactBlockPos,contactFace);
    }
   }
   // MINECRAFT-506: each wheel's own static load SHARE, from its horizontal (x,z) anchor
   // position relative to `center` - the same geometry already used to build `anchor` just
   // below, read once here as its own x/z pair so WheelLoadDistribution never needs a
   // Minecraft/Sable type. For the common symmetric layout this is exactly 1/wheels.size() for
   // every wheel (see WheelLoadDistributionTest's own symmetric-equivalence case), so the
   // per-wheel spring/damper computed from it below matches today's vehicle-wide
   // suspensionSpring/suspensionDamper(mass,wheels.size(),...) to ordinary floating-point
   // rounding for every currently-accepted default/symmetric vehicle - only an asymmetric
   // layout (COM offset toward one axle/corner) actually changes any wheel's calibrated rate.
   double[] loadShareX=new double[wheels.size()], loadShareZ=new double[wheels.size()];
   // MINECRAFT-540: each wheel's own turnsWithSteer flag, read once here alongside loadShareX/
   // loadShareZ so AckermannSteeringDistribution.steerRadians below never needs a Minecraft/IV
   // type either, same "pure x/z/flag arrays" shape AxleToeDistribution already uses.
   boolean[] turnsWithSteerArr=new boolean[wheels.size()];
   for (int i=0;i<wheels.size();i++) {
    var w=wheels.get(i);
    loadShareX[i]=w.localOffset.x-center.x;
    loadShareZ[i]=w.localOffset.z-center.z;
    turnsWithSteerArr[i]=w.placementDefinition.turnsWithSteer;
   }
   double[] loadShares=WheelLoadDistribution.shares(loadShareX,loadShareZ);
   // MINECRAFT-520: each wheel's own SERVICE-BRAKE budget share, from the same per-wheel `z`
   // used for loadShareZ above (see BrakeBiasDistribution's own doc for the geometric front/
   // rear axle grouping). Expressed here as a RATIO (share*wheelCount) rather than the raw
   // 0..1 share, so it composes directly with perWheelBrakeMagnitude's pedal-magnitude multiply
   // below: a ratio of exactly 1.0 for every wheel (tuning.brakeBiasFront unset, or any layout
   // BrakeBiasDistribution falls back on) reproduces today's uniform per-wheel brake budget
   // byte-identically, since 1.0 is the pure no-op multiplier.
   double[] brakeShares=BrakeBiasDistribution.shares(loadShareZ,tuning.brakeBiasFront);
   double[] brakeShareRatios=new double[brakeShares.length];
   for (int i=0;i<brakeShares.length;i++) brakeShareRatios[i]=brakeShares[i]*wheels.size();
   // MINECRAFT-524: each wheel's own DRIVE-force budget ratio, the same role as
   // brakeShareRatios immediately above (composes directly with driveForcePerWheel's own
   // multiply in MultiContactFrictionSolver.solveFriction) but biased by tuning.driveBiasFront
   // instead of brakeBiasFront. Uses DriveBiasDistribution#shareRatios directly (NOT
   // shares(...)[i]*wheels.size() computed here, unlike brakeShareRatios above) - self-review (PR
   // #24 comment 6871636687) found that share-then-remultiply round-trip is not always the exact
   // 1.0 no-op at every wheel count (ordinary floating-point error, e.g. 49 wheels), which would
   // make an unconfigured vehicle's drive differ from the pre-ticket drive/wheels.size() formula
   // by that same tiny error - shareRatios's own doc has the full reasoning for why this needs to
   // be byte-identical, not merely close.
   double[] driveShareRatios=DriveBiasDistribution.shareRatios(loadShareZ,tuning.driveBiasFront);
   // MINECRAFT-533: each wheel's own static TOE alignment offset (radians), from the same
   // loadShareX/loadShareZ anchor positions used for the load/brake/drive shares above - see
   // AxleToeDistribution's own doc for the front/rear axle grouping and mirrored left/right sign
   // convention. Unset tuning.toeFrontDegrees/toeRearDegrees (both null) reproduces the exact
   // pre-ticket all-zero offset.
   double[] toeRadians=AxleToeDistribution.toeRadians(loadShareX,loadShareZ,tuning.toeFrontDegrees,tuning.toeRearDegrees);
   // MINECRAFT-540: the existing uniform steer angle (radians) every turnsWithSteer wheel used
   // pre-ticket, unchanged - still the fallback value below whenever Ackermann is disabled or
   // the layout is ambiguous, and still the SAME input AckermannSteeringDistribution redistributes
   // per wheel when enabled (never a second independent reading of the rudder/maxSteerDegrees).
   double uniformSteerRadians=(-v.rudderInputVar.currentValue / EntityVehicleF_Physics.MAX_RUDDER_ANGLE)*Math.toRadians(tuning.maxSteerDegrees);
   // MINECRAFT-540: opt-in per-wheel Ackermann steer angle (radians), from the same loadShareX/
   // loadShareZ anchor positions used for the load/brake/drive/toe shares above - see
   // AckermannSteeringDistribution's own doc for the bicycle-model geometry and the full
   // ambiguous-layout fallback list (null means "keep uniformSteerRadians unchanged for every
   // turnsWithSteer wheel", the exact pre-ticket behavior). tuning.ackermannSteering unset/false
   // (the documented default) never even calls into the geometry - a byte-identical no-op.
   double[] ackermannSteerRadians=Boolean.TRUE.equals(tuning.ackermannSteering)
     ? AckermannSteeringDistribution.steerRadians(loadShareX,loadShareZ,turnsWithSteerArr,skidSteer,uniformSteerRadians,Math.toRadians(tuning.maxSteerDegrees))
     : null;
   // MINECRAFT-539: each wheel's own EFFECTIVE spring multiplier/damping ratio - the vehicle-wide
   // tuning.suspensionSpringMultiplier/suspensionDampingRatio resolved above, overridden per
   // geometric axle when suspensionSpringMultiplierFront/Rear or suspensionDampingRatioFront/Rear
   // is configured (see AxleSuspensionDistribution's own doc for the inheritance/ambiguous-layout
   // fallback). Unset axle fields (or an ambiguous layout) reproduce the exact pre-ticket uniform
   // per-wheel value on every wheel.
   double[] axleSpringMultipliers=AxleSuspensionDistribution.springMultipliers(loadShareZ,tuning.suspensionSpringMultiplier,tuning.suspensionSpringMultiplierFront,tuning.suspensionSpringMultiplierRear);
   double[] axleDampingRatios=AxleSuspensionDistribution.dampingRatios(loadShareZ,tuning.suspensionDampingRatio,tuning.suspensionDampingRatioFront,tuning.suspensionDampingRatioRear);
   // MINECRAFT-551: compression/rebound each independently inherit the per-wheel axleDampingRatios
   // resolved immediately above (which already applied the axle override chain) whenever the
   // corresponding directional field is unset - never a second, independently-defaulted value.
   // When set, tuning.suspensionDampingRatioCompression/Rebound is VEHICLE-WIDE (see its own doc
   // for why this deliberately does not get its own Front/Rear pair) and applies to every wheel
   // in that direction, overriding axleDampingRatios for that direction only. See
   // #directionalDampingRatio below for the actual per-wheel resolution.
   List<WheelHit> hits=new java.util.ArrayList<>();
   int wheelIndex=0;
   for(var wheel:wheels) {
    int wheelIdx=wheelIndex++;
    double loadShare=loadShares[wheelIdx];
    double brakeShareRatio=brakeShareRatios[wheelIdx];
    double driveShareRatio=driveShareRatios[wheelIdx];
    double wheelSpringMultiplier=axleSpringMultipliers[wheelIdx];
    double wheelDampingRatio=axleDampingRatios[wheelIdx];
    double wheelSpring=suspensionSpringForShare(mass,loadShare,tuning.suspensionTravel,gravity,wheelSpringMultiplier);
    double wheelDamper=suspensionDamperForShare(mass,loadShare,wheelSpring,wheelDampingRatio);
    // MINECRAFT-551: this wheel's own effective compression/rebound ratio - the configured
    // vehicle-wide directional override if set, else exactly wheelDampingRatio (this wheel's own
    // already axle-resolved base) above, so an unconfigured vehicle (or one that only configures
    // axle overrides, never compression/rebound) gets byte-identical wheelDamperCompression==
    // wheelDamperRebound==wheelDamper on every wheel.
    double wheelCompressionRatio=directionalDampingRatio(tuning.suspensionDampingRatioCompression,wheelDampingRatio);
    double wheelReboundRatio=directionalDampingRatio(tuning.suspensionDampingRatioRebound,wheelDampingRatio);
    double wheelDamperCompression=suspensionDamperForShare(mass,loadShare,wheelSpring,wheelCompressionRatio);
    double wheelDamperRebound=suspensionDamperForShare(mass,loadShare,wheelSpring,wheelReboundRatio);
    double radius=wheel.getHeight()/2;
    Vector3d anchor=new Vector3d(wheel.localOffset.x,wheel.localOffset.y+tuning.suspensionTravel*.5,wheel.localOffset.z).sub(center);
    boolean upperOwned=twoBodyActive && io.github.brooswitminecraft.div.experimental.TwoBodyChassis.isUpperHalf(anchor.y);
    GroundOwner wheelOwner=upperOwned?upperCandidate:lowerCandidate;
    Vector3d arm=q.transform(new Vector3d(anchor));
    Vector3d start=new Vector3d(pose.position()).add(arm);
    double length=radius+tuning.suspensionTravel;
    Vector3d end=new Vector3d(start).fma(-length,up);
    var hit=level.clip(new ClipContext(vec(start),vec(end),ClipContext.Block.COLLIDER,ClipContext.Fluid.NONE,(net.minecraft.world.entity.Entity)null));
    if(hit.getType()==HitResult.Type.MISS) {
     wheel.contactThisTick=false;
     if (debugThisTick) LOGGER.info("[DIV debugGroundContact]  wheel localOffset="+wheel.localOffset
       +" radius="+radius+" arm="+vfull(arm)+" rayStart="+vfull(start)+" rayEnd="+vfull(end)+" rayLength="+length+" MISS");
     continue;
    }
    double compression=length-hit.getLocation().distanceTo(vec(start));
    // localVelocity samples the suspension anchor's own motion for damping - must use the
    // OWNING body's real velocity/angular and an owner-relative arm, not the generic
    // velocity/angular/G-relative arm (review 53277's audit finding).
    Vector3d ownerArm=new Vector3d(arm).add(wheelOwner.leverArmCorrection());
    Vector3d localVelocity=new Vector3d(wheelOwner.ownerAngular()).cross(ownerArm).add(wheelOwner.ownerVelocity());
    Vector3d point=new Vector3d(hit.getLocation().x,hit.getLocation().y,hit.getLocation().z).sub(pose.position());
    // Opt-in experimental effective-mass damping candidate (ticket comments 53214/53219) - OFF
    // (EXPERIMENTAL_EFFECTIVE_MASS_DAMPING false, the shipped default) means damperResult is
    // exactly new EffectiveMassDamperResult(wheelDamper, NaN) - damperUsed==wheelDamper exactly
    // (MINECRAFT-506: wheelDamper itself matches the old vehicle-wide `damper` to ordinary
    // floating-point rounding for every symmetric/default vehicle - see
    // suspensionDamperForShare's own doc), nothing about today's behavior changes. This formula
    // DIVIDES by the inertia tensor it is
    // given, so (review 53280/53282/53285) it must use the AGGREGATE mass/inertia (`mass`/
    // aggregateInertiaBody) with a lever arm measured from the SAME assembly COM
    // (predictorLeverArmCorrection) - a mix of owner-isolated inertia with a differently-
    // referenced arm would be dimensionally inconsistent, not merely imprecise. For the single-
    // body path this is exactly today's values (mass/inertiaBody, zero correction, no change).
    // Direction `up` is this body's own chassis/suspension-ray direction, NOT a re-derived actual
    // terrain surface normal (review comment 53223's correction; no geometry change here). See
    // PhysicsBridge.effectiveMassDamper's own doc for the guard/fallback behavior and the
    // measured record showing this is NOT a validated fix. MINECRAFT-523: both the ON and OFF
    // damper bases now share the SAME configured tuning.suspensionDampingRatio - the candidate
    // never falls back to a second hardcoded .7, so a configured damping ratio does not silently
    // disappear when this experimental candidate is opted in.
    Vector3d predictorPoint=new Vector3d(point).add(predictorLeverArmCorrection);
    var damperResult = EXPERIMENTAL_EFFECTIVE_MASS_DAMPING
      ? effectiveMassDamper(wheelSpring,mass,predictorPoint,up,inverse,aggregateInertiaBody,wheelDampingRatio,wheelDamper)
      : new EffectiveMassDamperResult(wheelDamper, Double.NaN);
    // The vehicle-wide mass*gravity safety cap is UNCHANGED (not tightened to mass*gravity*
    // loadShare) - it is documented as a safety cap, not a load-distribution model (see
    // suspensionSpring's own doc), and tightening it would itself change default/symmetric
    // behavior under hard compression, which this ticket's "preserve defaults" constraint rules
    // out. Only the calibrated RATE that normally keeps compression near travel*.5 varies by
    // load share; this clamp is the same last-resort backstop every wheel already had.
    // MINECRAFT-551: the experimental effective-mass-damping candidate (default off) keeps its
    // existing, documented semantics unexpanded - when active it still produces exactly ONE
    // ratio-driven damper (effectiveMassDamper is never called twice with different ratios, which
    // would be the "expansion of that experiment" this ticket's own scope rules out), used for
    // BOTH directions; the compression/rebound split only actually differentiates the damper on
    // the default (EXPERIMENTAL_EFFECTIVE_MASS_DAMPING off) path. Byte-identical to today whenever
    // compression/rebound are unset (both collapse to wheelDamper/damperResult.damper() exactly).
    double damperCompressionUsed=EXPERIMENTAL_EFFECTIVE_MASS_DAMPING?damperResult.damper():wheelDamperCompression;
    double damperReboundUsed=EXPERIMENTAL_EFFECTIVE_MASS_DAMPING?damperResult.damper():wheelDamperRebound;
    double normalSpeed=localVelocity.dot(up);
    double normal=Math.min(mass*gravity,TireForces.suspension(compression,normalSpeed,wheelSpring,damperCompressionUsed,damperReboundUsed));
    // Diagnostic-only: the single scalar damper actually selected THIS tick by normalSpeed's own
    // sign (see TireForces.suspension's 5-arg overload) - what WheelHit.damperUsed below reports,
    // so the existing damperUsed= log line (comment 53219's receipt) stays a true statement of
    // what was applied, not merely the compression-side value.
    double damperActuallyUsed=normalSpeed<0?damperCompressionUsed:damperReboundUsed;
    // MINECRAFT-533: static toe offset composes ADDITIVELY with steer input (never replacing
    // it) - a steering wheel still toes at center and continues to sweep with rudder input
    // exactly as before, just around a toed-in/out neutral rather than dead-ahead; a non-
    // steering wheel (turnsWithSteer false, steer==0 above) still gets its own configured toe.
    // MINECRAFT-540: ackermannSteerRadians (non-null only when tuning.ackermannSteering is
    // opted in AND the layout qualifies - see AckermannSteeringDistribution's own doc) replaces
    // ONLY the uniform per-wheel steer term below, never the toe addition immediately after it -
    // disabled/ambiguous falls back to uniformSteerRadians unchanged, byte-identical to before
    // this ticket.
    double steer=(wheel.placementDefinition.turnsWithSteer
        ? (ackermannSteerRadians!=null ? ackermannSteerRadians[wheelIdx] : uniformSteerRadians)
        : 0)+toeRadians[wheelIdx];
    Vector3d forward=q.transform(new Vector3d(Math.sin(steer),0,Math.cos(steer)));
    Vector3d right=new Vector3d(up).cross(forward);
    double skidSteerMultiplier=skidSteer ? 1-Math.signum(anchor.x)*(v.rudderInputVar.currentValue/EntityVehicleF_Physics.MAX_RUDDER_ANGLE) : 1;
    // MINECRAFT-508/517: tag-driven pavement/dirt/grass/gravel/ice baseline, layered with
    // MINECRAFT-517's directional per-face canonical-texture grain response (safe no-op fallback
    // to the exact MINECRAFT-508 baseline when that texture/model can't be resolved - see
    // DirectionalGroundSurface's own doc) - resolved from the actual terrain hit block/face, not
    // the suspension anchor. `forward` is this wheel's own rolling direction, the travel
    // direction grain response is relative to.
    var terrainHit=(net.minecraft.world.phys.BlockHitResult)hit;
    Vector3d hitLocal=new Vector3d(hit.getLocation().x,hit.getLocation().y,hit.getLocation().z).sub(
      terrainHit.getBlockPos().getX(),terrainHit.getBlockPos().getY(),terrainHit.getBlockPos().getZ());
    GroundSurface surface=DirectionalGroundSurface.at(level,terrainHit.getBlockPos(),terrainHit.getDirection(),
      hitLocal.x,hitLocal.y,hitLocal.z,forward.x,forward.y,forward.z);
    hits.add(new WheelHit(wheel,radius,arm,start,end,length,compression,localVelocity,normal,forward,right,point,skidSteerMultiplier,
      brakeShareRatio,driveShareRatio,damperActuallyUsed,damperResult.effectiveMass(),upperOwned,surface,wheelIdx,
      terrainHit.getBlockPos(),terrainHit.getDirection()));
   }
   // Two-body ground-force owner resolution (review 53272's correctness fix - see Body's own
   // class-level correction for the full defect this closes). For the single-body path this is
   // exactly today's values (zero offset, no change). For two-body, resolves which SINGLE half
   // actually owns every wheel this tick (by each wheel's own real anchor Y, not an assumption),
   // and provides that owner's own real live velocity/angular plus the world-frame offset needed
   // to re-express every G-relative point/arm below relative to that owner's own center - what
   // Sable's applyImpulseAtPoint (Phase 3) and the pure-kinematics contactVelocity sampling
   // actually need. The solver's own impulse-SIZING math (anything dividing by inertia) uses the
   // separate aggregate basis computed above instead, not this owner resolution.
   //
   // Mixed ownership (wheels on BOTH halves this tick) is NOT supported by this single-pass
   // solver (which assumes every contact acts on one shared rigid body) - review's explicit
   // "honor actual owner-half geometry or explicitly reject unsupported layouts" - rather than
   // silently pick one half and misapply the other's wheels, this clears `hits` entirely so
   // every downstream phase (native sync, friction solve, self-right support gating) correctly
   // sees "no wheels" this tick instead of a half-wrong result. Logged once per Body instance,
   // not every tick, so a persistently-mixed vehicle cannot spam the log forever.
   // Reuses the SAME lowerCandidate/upperCandidate computed before Phase 1 (review 53277's audit
   // finding - an earlier version recomputed a second, separately-named GroundOwner here from
   // scratch, which is how Phase 1's own localVelocity/effectiveMassDamper ended up using
   // un-owner-corrected values while this block had the correct ones) - only the "which ONE half
   // actually owns every wheel this tick" selection happens here.
   GroundOwner owner;
   if (!twoBodyActive) {
    owner=lowerCandidate; // == the single-body values (see lowerCandidate's own ternary above)
   } else {
    boolean anyUpper=false, anyLower=false;
    for (var h:hits) { if (h.upperOwned()) anyUpper=true; else anyLower=true; }
    if (anyUpper && anyLower) {
     if (!loggedMixedOwnershipWarning) {
      LOGGER.error("[DIV twoBodyChassis] entity="+v.uniqueUUID+" has ground wheels on BOTH halves this tick (mixed ownership) - unsupported layout for this candidate; skipping ground-force application until this no longer occurs. See TwoBodyChassis.isUpperHalf's own doc.");
      loggedMixedOwnershipWarning=true;
     }
     hits.clear();
     owner=lowerCandidate; // unused once hits is empty - Phase2/3 below iterate zero contacts either way
    } else if (anyUpper) {
     owner=upperCandidate;
    } else {
     // anyLower, or hits is empty (no ray hit this tick at all) - lower is the harmless default
     // when there is nothing to own either way.
     owner=lowerCandidate;
    }
   }
   // MINECRAFT-519: optional, bounded axle anti-roll coupling (see AntiRoll's own doc) - runs
   // AFTER the mixed-ownership clearing above (so a cleared/empty `hits` this tick simply has
   // nothing to redistribute) and BEFORE every downstream consumer of WheelHit.normal() (native
   // contact sync just below, the vertical impulse and friction-solve normal budget in Phase 2/3,
   // and the diagnostic totalNormalLoad sum) - every one of them must see the SAME already-
   // redistributed per-wheel load, not whatever Phase 1 computed before coupling. Pairing is
   // geometric (loadShareX/loadShareZ, the exact same per-wheel anchor-relative-to-center frame
   // WheelLoadDistribution already used above - no new geometry, no vehicle-ID table) over the
   // FULL `wheels` list, independent of which wheels actually have contact this tick; only a
   // pair where BOTH sides currently have a real hit is ever coupled (hitIndexForWheel[...]<0 for
   // an airborne/missing/unsupported-layout partner skips that wheel entirely - see class doc's
   // "left completely untouched" and the ticket's own "airborne/contact transitions" case).
   // Review correction #2 (comment 53770 - over the first correction, comments 53750/53752):
   // AntiRoll.transfer now couples DIFFERENTIAL TRAVEL (each side's own raw WheelHit.compression(),
   // common-heave/common-velocity-neutral by construction since every wheel's calibrated rest
   // compression is the SAME tuning.suspensionTravel*.5 regardless of its own loadShare/rate -
   // see AntiRoll's own class doc), converted to a force via the pair's own average calibrated
   // spring rate (suspensionSpringForShare(mass,loadShares[...],...), the SAME per-wheel rate
   // already used above to build wheelSpring - not the earlier (and still wrong whenever the
   // pair's rates differ) force-baseline-deviation attempt. An asymmetric axle (unequal
   // loadShares) sitting at a genuinely common compression (no actual roll) gets ZERO transfer
   // regardless of how different the pair's own rates are; an actual roll disturbance (a real
   // compression difference) gets a LARGER restoring differential than the uncoupled pair would
   // have, never a smaller one. Bypassed entirely (zero allocation, zero behavior change) when
   // stiffness is exactly the documented 0 FALLBACK - the ticket's explicit "default zero/off
   // preserving accepted handling" and zero/default-equivalence expectation.
   if (!hits.isEmpty() && tuning.antiRollStiffness != null && tuning.antiRollStiffness > 0) {
    int[] pairPartner=AntiRoll.pairPartners(loadShareX,loadShareZ);
    int[] hitIndexForWheel=new int[wheels.size()];
    java.util.Arrays.fill(hitIndexForWheel,-1);
    for (int i=0;i<hits.size();i++) hitIndexForWheel[hits.get(i).wheelIndex()]=i;
    double[] newNormal=new double[hits.size()];
    for (int i=0;i<hits.size();i++) newNormal[i]=hits.get(i).normal();
    for (int wi=0; wi<pairPartner.length; wi++) {
     int partnerWi=pairPartner[wi];
     if (partnerWi<=wi) continue; // unpaired (-1), or already handled from the partner's own side
     int hi=hitIndexForWheel[wi], hj=hitIndexForWheel[partnerWi];
     if (hi<0 || hj<0) continue; // one or both sides not actually grounded this tick - leave both alone
     double normalA=hits.get(hi).normal(), normalB=hits.get(hj).normal();
     // MINECRAFT-539: each side's own EFFECTIVE (axle-resolved) spring multiplier, not the flat
     // vehicle-wide tuning.suspensionSpringMultiplier - this is the SAME per-wheel rate Phase 1
     // above already used to build that wheel's own wheelSpring, so an anti-roll pair with an
     // axle-specific multiplier configured sees the same calibrated rate anti-roll's own
     // restoring-force conversion already depends on (see this class's own doc immediately above
     // for why using the pair's own rate, not a shared one, matters).
     double rateA=suspensionSpringForShare(mass,loadShares[wi],tuning.suspensionTravel,gravity,axleSpringMultipliers[wi]);
     double rateB=suspensionSpringForShare(mass,loadShares[partnerWi],tuning.suspensionTravel,gravity,axleSpringMultipliers[partnerWi]);
     double t=AntiRoll.transfer(hits.get(hi).compression(),hits.get(hj).compression(),rateA,rateB,normalA,normalB,tuning.antiRollStiffness);
     newNormal[hi]=normalA+t;
     newNormal[hj]=normalB-t;
    }
    for (int i=0;i<hits.size();i++) if (newNormal[i]!=hits.get(i).normal()) hits.set(i,hits.get(i).withNormal(newNormal[i]));
   }
   // MINECRAFT-532: opt-in contact-aware axle drive-force sharing - deliberately AFTER the
   // anti-roll pass above, so the per-wheel `load` this consults is the FINAL, already-
   // redistributed contact load, not Phase 1's pre-anti-roll value. Builds grounded/load arrays
   // over the FULL wheel count (not just `hits`) so AxleDriveForceSharing's own axle grouping
   // sees every wheel's true contact state, including an airborne wheel's (grounded=false,
   // load irrelevant) - see that class's own doc for the full redistribution/fallback reasoning.
   // Bypassed entirely (hits left exactly as DriveBiasDistribution assigned them, no behavior
   // change) when tuning.axleDriveForceSharing is not explicitly true, the documented default.
   if (!hits.isEmpty() && Boolean.TRUE.equals(tuning.axleDriveForceSharing)) {
    boolean[] wheelGrounded=new boolean[wheels.size()];
    double[] wheelLoad=new double[wheels.size()];
    for (var h : hits) { wheelGrounded[h.wheelIndex()]=true; wheelLoad[h.wheelIndex()]=h.normal(); }
    double[] sharedDriveShareRatios=AxleDriveForceSharing.shareRatios(loadShareZ,driveShareRatios,wheelGrounded,wheelLoad,true);
    for (int i=0;i<hits.size();i++) {
     double newRatio=sharedDriveShareRatios[hits.get(i).wheelIndex()];
     if (newRatio!=hits.get(i).driveShareRatio()) hits.set(i,hits.get(i).withDriveShareRatio(newRatio));
    }
   }
   // Native contact-ownership sync (ticket comment 53182/53182-correction): confirmed by reading
   // the real pinned MTS source (not inferred from naming) that PartEngine.update() - which DOES
   // survive DIV's getForcesAndMotions cancellation, unlike addToForceOutput (see below) - reads
   // v.groundDeviceCollective.groundedGroundDevices for its own RPM-feedback loop
   // (PartEngine.java ~L604-637) AND, when that set shows no driven wheel grounded, forcibly
   // overwrites wheel.angularVelocity from engine RPM alone (L634) - directly conflicting with
   // DIV's own Phase3 write of the same field below. DIV never otherwise populates this native
   // collection (confirmed: DIV cancels performGroundOperations/replaces moveVehicle, which is
   // what normally calls updateCollisionStatuses to populate it), so for every DIV-controlled
   // vehicle it was empty/stale, making the native RPM-feedback loop always behave as if no
   // wheel were ever grounded. Syncing it here to DIV's own real raycast result is NOT
   // behavior-neutral bookkeeping (review comment 53200's correction to an earlier overclaim
   // here) - it changes live engine RPM-feedback behavior for every DIV vehicle, on purpose, but
   // that behavior change itself is NOT yet validated: tick-order/freshness (is this write seen
   // by update() the same tick, every tick, regardless of per-part registration order),
   // zero-load-HIT-vs-real-grounding (this uses the same `hits` as a ray HIT, not the
   // positive-load gating SelfRightingAssist uses - may not be the right native semantics),
   // material/friction state interaction, deactivation cleanup, and wheel-speed ownership once
   // both DIV and this native loop can write wheel.angularVelocity are all still open and
   // un-tested. Review explicitly: do not treat this as resolving the RPM conflict until that
   // battery of tests exists - this commit is a diagnosed mechanism and a candidate fix, not a
   // proven one, and stays out of the already-staged self-right assist comparison.
   // addToForceOutput itself (the wheelForce propulsion formula) is confirmed SEPARATELY
   // cancelled - it is only ever called from EntityVehicleF_Physics.getForcesAndMotions
   // (EntityVehicleF_Physics.java L296, inside the exact method VehicleForcesMixin cancels at
   // its HEAD for an active DIV vehicle) - so it does not run at all, and adapting its formula
   // would mean replicating the arithmetic DIV-side from the now-correctly-fed-back rpm/
   // gearRatio/axleRatio/fuelConsumption state, not invoking or re-enabling the native method.
   // That adaptation itself remains separate, unfinished work.
   //
   // MAJOR OPEN CORRECTION to this commit's own premise above. First drafted from an UNPINNED
   // MinecraftTransportSimulator GitHub checkout (master/25.0.0) - wrong provenance, caught and
   // corrected before this comment was finalized: re-verified every claim below by decompiling
   // (cfr) the ACTUAL pinned dependency bytecode this build uses
   // (~/.gradle/caches/modules-2/files-2.1/curse.maven/minecraft-transport-simulator-286703/
   // 7926606/.../minecraft-transport-simulator-286703-7926606.jar - the exact curse.maven
   // coordinate in build.gradle, 24.0.0 for 1.21.1 per VERIFICATION.md) - every call/line cited
   // here was confirmed present in that real pinned jar's decompiled classes, not assumed to
   // carry over from the wrong checkout. Still NOT runtime-confirmed (see the debug log line
   // just below, added for exactly that):
   //
   // "DIV never otherwise populates this native collection... was empty/stale" may be WRONG, and
   // this write may be overwritten the SAME tick it is made, before anything reads it.
   // EntityManager.doTick calls entity.update() (where this write happens, via
   // moveVehicle->PhysicsBridge.step->stepGround) THEN definable.doPostUpdateLogic() on every
   // AEntityD_Definable, including every vehicle. AEntityE_Interactable.doPostUpdateLogic
   // unconditionally calls updateEncompassingBox() every tick for every entity, not just on part
   // add/remove. AEntityVehicleD_Moving overrides updateEncompassingBox() to additionally call
   // groundDeviceCollective.updateMembers()/updateBounds()/updateCollisions(true) - and
   // updateCollisions CLEARS and REBUILDS groundedGroundDevices from NATIVE box-collision
   // detection (VehicleGroundDeviceBox.updateCollisionStatuses), using vehicle.position/
   // orientation - fields DIV itself keeps live/correct every tick (that's how a DIV vehicle
   // visibly moves at all) - so this native recompute is NOT obviously broken/stale for a DIV
   // vehicle; it may well produce a real, valid grounded set independently of DIV's raycast.
   // None of this (updateEncompassingBox/doPostUpdateLogic) is cancelled by VehicleMovementMixin,
   // which only cancels performGroundOperations and moveVehicle. If this is right, THIS explicit
   // write is clobbered before PartEngine.update() ever reads it on the next tick, and whatever
   // effect commit 4e6a8f9 has (if any) comes from coincidental agreement between the two
   // detection systems, not from this write - this needs a real runtime capture to settle, not
   // further static reading, hence the log line immediately below. Not yet acted on (nothing
   // here removed/changed) - reported, not resolved.
   //
   // The SEQUENCE itself (not yet the DATA) is now settled by source alone, confirmed against the
   // same pinned jar: EntityManager.doTick fully processes one allNormalTickableEntities queue
   // entry (that entity's own update() THEN its own doPostUpdateLogic()) before the outer loop
   // advances to the next entry, and BuilderEntityExisting (platform load path, both the fresh-
   // deploy AND the reload-from-save code paths) always calls world.addEntity(vehicle) before
   // addPartsPostAddition (which is what adds each PART's own queue entry) - so the vehicle is
   // always queued, and therefore always fully ticked (write THEN native overwrite), strictly
   // before any of its own parts get ticked at all, every single pass, deterministically. The
   // only thing a real runtime capture would still add is the DATA question: whether native
   // updateCollisions actually agrees with DIV's own raycast in practice (moot-but-harmless) or
   // disagrees (parts consistently act on a different grounded-state than DIV just computed).
   //
   // This same write/overwrite question also governs two things beyond PartEngine's RPM feedback:
   // PartGroundDevice.update()'s own blockMaterialBelow/blockNameBelow detection (feeds real
   // terrain friction-modifier lookups) sits directly inside the identical
   // `groundedGroundDevices.contains(this)` gate, confirmed by exact brace-counting in the pinned
   // decompiled source - not a separate mechanism as an earlier draft of this comment guessed. And
   // PartGroundDevice.update() is itself a THIRD writer of wheel.angularVelocity (via
   // getDesiredAngularVelocity(), a coarse vehicle-level groundVelocity/circumference approximation
   // that ignores per-wheel slip/skid) under that same gate, alongside DIV's own accurate per-wheel
   // Phase3 write and PartEngine's RPM-feedback writer - all three depend on the same open question.
   //
   // "Deactivation cleanup" (one of this commit's original open items) turns out to be moot: the
   // native overwrite above runs every tick for every vehicle unconditionally, never gated on DIV
   // being active, so there is no window where a stale DIV-written value could outlive one tick
   // either way - nothing extra to guard on deactivation.
   if (debugThisTick) LOGGER.info("[DIV debugGroundContact] nativeGroundedGroundDevicesBeforeSync entity="+v.uniqueUUID
     +" tick="+debugTick+" sizeBeforeDivWrite="+v.groundDeviceCollective.groundedGroundDevices.size()
     +" wheelsBeforeDivWrite="+v.groundDeviceCollective.groundedGroundDevices.stream().map(w->w.localOffset.toString()).toList());
   v.groundDeviceCollective.groundedGroundDevices.clear();
   for (var h : hits) v.groundDeviceCollective.groundedGroundDevices.add(h.wheel());
   // Phase 2: shared multi-contact friction solve (ticket MINECRAFT-503) - stage every wheel's
   // own vertical/suspension impulse into a working velocity/angular estimate first (production
   // reads velocity/angular ONCE per tick - this `velocity`/`angular` snapshot - and every
   // wheel's force computation below uses it unchanged; only this LOCAL working copy, used to
   // size the solved impulses, accumulates as wheels are staged/solved), then iterate
   // io.github.brooswitminecraft.div.experimental.MultiContactFrictionSolver across all active
   // contacts together instead of each wheel computing its target independently from the stale
   // snapshot alone (the confirmed real mechanism behind the captured rollover - see that
   // class's own doc and ticket comment history for the full derivation/validation). No gravity
   // is added here - Sable's own engine owns that; `velocity`/`angular` already reflect
   // whatever Sable has already applied this tick.
   // Every point/mass/inertia/velocity below uses the AGGREGATE basis computed before Phase 1
   // (review 53280/53282/53285 - this solver's own math divides by inertia throughout, so it
   // must predict against the real joint-coupled response, confirmed empirically to match
   // treating the whole assembly as one rigid body, not either half's own isolated mass/
   // inertia). For the single-body path this is exactly today's values (mass/inertiaBody/
   // velocity/angular, zero correction, no change).
   Vector3d workingVelocity=new Vector3d(comVelocity), workingAngular=new Vector3d(angular);
   for (var h:hits) {
    Vector3d verticalImpulse=new Vector3d(up).mul(h.normal()*DT);
    workingVelocity.add(new Vector3d(verticalImpulse).div(mass));
    Vector3d predictorPoint=new Vector3d(h.point()).add(predictorLeverArmCorrection);
    Vector3d torqueBody=inverse.transform(new Vector3d(predictorPoint).cross(verticalImpulse));
    workingAngular.add(q.transform(new Vector3d(torqueBody.x/aggregateInertiaBody.x,torqueBody.y/aggregateInertiaBody.y,torqueBody.z/aggregateInertiaBody.z)));
   }
   // PER-WHEEL brake magnitude (ticket MINECRAFT-504, comment 53603): the analog handbrake is
   // explicit rear/handbrake-capable-wheel semantics, not just another service brake applied
   // uniformly - a non-steering ("rear") wheel carries max(service,handbrake); a steering
   // ("front") wheel carries only the service brake, same as before this ticket's handbrake
   // addition. Parking brake keeps forcing brakeMagnitude (and so every wheel) to 1.0, unchanged.
   // MINECRAFT-520: h.brakeShareRatio() folds the configured front/rear service-brake bias into
   // ONLY the service-pedal component below, via perWheelBrakeMagnitude - see that method's own
   // doc for why the parking brake and the handbrake stay exactly as unbiased as they were
   // before this ticket, and why an unconfigured/fallback bias (ratio 1.0 for every wheel) is a
   // pure no-op reproducing this exact pre-ticket formula. MINECRAFT-524: h.driveShareRatio()
   // folds the configured front/rear DRIVE-force bias into the FrictionContact passed below -
   // solveFriction's own driveForcePerWheel*skidSteerMultiplier*driveShareRatio composition (see
   // its own doc) is the drive-side analog of this same brakeShareRatio pattern.
   // MINECRAFT-529: FrictionContact.frictionMultiplier is deliberately reused (not a new record
   // component - see MultiContactFrictionSolver.FrictionContact's own doc, updated by this
   // ticket) to also carry THIS WHEEL'S own installed tire part's grip override, composed
   // MULTIPLICATIVELY with the existing ground-surface frictionMultiplier: tirePartFriction(...)
   // (PhysicsBridge.tirePartFriction's own doc) resolves this wheel's own currently-installed
   // part key fresh every tick (never cached) to either that part's explicit friction override or
   // the vehicle's own resolved tuning.friction unchanged; dividing that result back by
   // tuning.friction yields a pure RATIO that is exactly 1.0 (a true no-op, byte-identical to
   // before this ticket) whenever no part override is set, and otherwise rescales the shared
   // `friction` scalar solveFriction receives below into this one contact's own effective value
   // once solveFriction multiplies it back out at line ~211's `friction*c.frictionMultiplier()`.
   // tuning.friction is always > 0 (Tuning.validate()'s own bound), so this division never
   // degenerates. A wheel that misses the ground ray this tick never reaches this stream at all
   // (its WheelHit was never built - see the HitResult.Type.MISS continue above), so an
   // airborne/phantom tire's configured override can never contribute any grip, matching this
   // ticket's explicit "no phantom/airborne contribution" requirement.
   // MINECRAFT-534: FrictionContact.rollingResistanceMultiplier is likewise reused (not a new
   // record component) to also carry THIS WHEEL'S own installed tire part's rolling-resistance
   // override, composed MULTIPLICATIVELY with the existing ground-surface
   // rollingResistanceMultiplier - but, UNLIKE tireGripRatio immediately above, NOT as a ratio
   // divided back out against tuning.rollingResistance: solveFriction has no separate "vehicle
   // baseline" parameter for rolling resistance the way it does for friction (friction.friction
   // is passed to solveFriction directly and multiplied by frictionMultiplier INSIDE the solver -
   // see solveFriction's own `friction*c.frictionMultiplier()` - so frictionMultiplier only ever
   // needs to carry the DEVIATION from that already-applied baseline). rollingResistanceMultiplier
   // has no such separately-applied baseline anywhere in MultiContactFrictionSolver - it is the
   // SOLE scalar multiplying the solver's fixed k=20 formula - so this slot must carry the FULL
   // resolved per-wheel value outright, review finding (PR #30/Jira comment 54071): dividing by
   // tuning.rollingResistance here (an earlier version of this code did) silently cancelled any
   // configured vehicle-level value back to 1.0 whenever no part override existed, and produced
   // surface*(part/vehicle) instead of surface*part when a part override DID exist - contradicting
   // this ticket's own declared "replace, not multiply" part precedence. tirePartRollingResistance
   // already returns exactly the right fully-resolved value either way (the part's own override,
   // or - when absent - the vehicle's own resolved tuning.rollingResistance unchanged, itself
   // FALLBACK 1.0 when unconfigured), so no division is needed or correct here.
   var contacts=hits.stream().map(h->{
    double wheelBrakeMagnitude = perWheelBrakeMagnitude(h.wheel().placementDefinition.turnsWithSteer,
      v.parkingBrakeVar.isActive, brakeMagnitude, handbrakeMagnitudeThisTick, h.brakeShareRatio());
    String tireKey = h.wheel().definition.packID+":"+h.wheel().definition.systemName;
    double tireGripRatio = tirePartFriction(tireKey, tuning.friction)/tuning.friction;
    double tireRollingResistance = tirePartRollingResistance(tireKey, tuning.rollingResistance);
    // MINECRAFT-552: this wheel's own installed tire-part lateral-grip ratio, same "divide back
    // out against the vehicle baseline" shape tireGripRatio uses immediately above (never the
    // raw-resolved-value shape tireRollingResistance uses, since lateralGripMultiplier - like
    // friction - IS a separate baseline parameter solveFriction multiplies per-contact INSIDE the
    // solver; see FrictionContact.lateralGripOverrideRatio's own doc). tuning.lateralGripMultiplier
    // is always > 0 (Tuning.validate()'s own bound), so this division never degenerates.
    double tireLateralGripRatio = tirePartLateralGripMultiplier(tireKey, tuning.lateralGripMultiplier)/tuning.lateralGripMultiplier;
    return new io.github.brooswitminecraft.div.experimental.MultiContactFrictionSolver.FrictionContact(
     new Vector3d(h.point()).add(predictorLeverArmCorrection),h.forward(),h.right(),h.normal(),h.skidSteerMultiplier(),
     h.surface().frictionMultiplier()*tireGripRatio,h.surface().rollingResistanceMultiplier()*tireRollingResistance,wheelBrakeMagnitude,h.driveShareRatio(),tireLateralGripRatio);
   }).toArray(io.github.brooswitminecraft.div.experimental.MultiContactFrictionSolver.FrictionContact[]::new);
   var results=io.github.brooswitminecraft.div.experimental.MultiContactFrictionSolver.solveFriction(contacts,mass,aggregateInertiaBody,q,inverse,workingVelocity,workingAngular,
     wheels.isEmpty()?0:tuning.brakeForce/wheels.size(),wheels.isEmpty()?0:drive/wheels.size(),tuning.friction,tuning.lateralGripMultiplier,DT,
     effectiveFrictionSolverIterations());
   // Phase 3 below returns to the OWNER-HALF basis (owner.ownerVelocity()/ownerAngular()/
   // leverArmCorrection()/handle()) deliberately - pure kinematics (contactVelocity) is
   // mathematically equivalent either way (same real shared angular velocity), and the actual
   // native applyImpulseAtPoint call MUST target the real owning half's own local point
   // regardless of which basis sized the impulse above.
   // Phase 3: apply each wheel's final (vertical + solved tire) impulse exactly once, same
   // application/logging shape as before.
   for (int i=0;i<hits.size();i++) {
    var h=hits.get(i); var wheel=h.wheel(); var tire=results[i];
    Vector3d point=new Vector3d(h.point()).add(owner.leverArmCorrection()), forward=h.forward(), right=h.right();
    Vector3d contactVelocity=new Vector3d(owner.ownerAngular()).cross(point).add(owner.ownerVelocity());
    double speed=contactVelocity.dot(forward), sideSpeed=contactVelocity.dot(right);
    // MINECRAFT-510 (comment 55238/M55554611): generic, read-only observation hook - no erosion
    // policy here, no Layered Slopes dependency. Real tire-local lateral slip (NOT a vehicle-
    // local Phase 1 velocity component; no longitudinal wheelspin/lockup claim), real supported
    // load (normal/gravity, this tick's real per-dimension gravity), and the EXACT Phase 1
    // raycast block/face threaded through WheelHit unchanged - never re-derived from `point`.
    // createIfValid validates every PUBLISHED value itself (not merely h.normal()), returning
    // null (fire nothing) for any non-finite/non-positive input or result. Listeners must not
    // mutate the world from this handler - see the event's own doc.
    var wheelGroundContactEvent = WheelGroundContactEvent.createIfValid(level,h.contactBlockPos(),h.contactFace(),
      sideSpeed,h.normal(),gravity,level.getServer().getTickCount());
    if (wheelGroundContactEvent != null) {
     NeoForge.EVENT_BUS.post(wheelGroundContactEvent);
    }
    Vector3d force=new Vector3d(up).mul(h.normal()).fma(tire.longitudinal(),forward).fma(tire.lateral(),right);
    // Captured BEFORE the world-frame point/force below are mutated in place into the
    // local-frame values actually passed to applyImpulseAtPoint - "requested" (world-frame,
    // what stepGround computed) vs "applied" (local-frame, what Sable actually receives) per
    // ticket comment 52976's ask, so a forensic replay doesn't need to re-derive the local
    // transform itself from a possibly-imprecise logged orientation.
    if (debugThisTick) {
     String requestedImpulseWorld=vfull(new Vector3d(force).mul(DT)), requestedPointWorld=vfull(point);
     // lateralSlipBudgetTarget: kept under its EXACT original field name (not renamed) so the
     // already-committed analysis scripts (tools/analysis/m503/) keep parsing without
     // modification (review comment 53052: silently breaking that regex would zero-parse every
     // wheel, not error) - but it is now the OLD (pre-solver) single-shot mass/wheelCount value,
     // computed here ONLY for diagnostic comparison against older captures, NOT used by the
     // actual physics above (that comes from the solved `tire` result instead). Also keeps
     // `tire=Force[...]` in the exact shape those scripts already parse - a bare record
     // toString() here would silently print `FrictionResult[...]` instead.
     double lateralSlipBudgetTarget=-sideSpeed*mass/wheels.size()/DT;
     LOGGER.info("[DIV debugGroundContact]  wheel localOffset="+wheel.localOffset
       +" radius="+h.radius()+" arm="+vfull(h.arm())+" rayStart="+vfull(h.start())+" rayEnd="+vfull(h.end())+" rayLength="+h.length()
       +" hit="+vfull(new Vector3d(point).add(pose.position()))
       +" distance="+new Vector3d(point).add(pose.position()).distance(h.start())+" compression="+h.compression()
       +" anchorVerticalVelocity="+h.localVelocity().dot(up)+" point="+requestedPointWorld
       +" localVelocity(anchor-sampled)="+vfull(h.localVelocity())+" contactVelocity(contact-sampled)="+vfull(contactVelocity)
       +" speed="+speed+" sideSpeed="+sideSpeed+" lateralSlipBudgetTarget="+lateralSlipBudgetTarget
       +" normal="+h.normal()+" tire=Force[longitudinal="+tire.longitudinal()+", lateral="+tire.lateral()+"]"
       +" requestedImpulseWorld="+requestedImpulseWorld
       // Comment 53219's explicit per-wheel damping/effective-mass receipt: damperUsed==the
       // shared `damper` exactly whenever the candidate is bypassed (effectiveMassUsed logs as
       // NaN, distinguishing "bypassed" from "attempted but degenerate/fell back" - see
       // PhysicsBridge.effectiveMassDamper's own doc).
       +" experimentalEffectiveMassDamping="+EXPERIMENTAL_EFFECTIVE_MASS_DAMPING+" damperUsed="+h.damperUsed()+" effectiveMassUsed="+h.effectiveMassUsed());
    }
    Vector3d appliedPointLocal=inverse.transform(point), appliedImpulseLocal=inverse.transform(force.mul(DT));
    if (debugThisTick) LOGGER.info("[DIV debugGroundContact]  wheel localOffset="+wheel.localOffset
      +" appliedPointLocal="+vfull(appliedPointLocal)+" appliedImpulseLocal="+vfull(appliedImpulseLocal));
    owner.handle().applyImpulseAtPoint(appliedPointLocal,appliedImpulseLocal);
    wheel.contactThisTick=true;
    wheel.animateAsOnGround=true;
    wheel.angularVelocity=speed*DT/(Math.max(.05,h.radius())*2*Math.PI);
    contact=true;
   }
   if (debugThisTick) debugGroundContactTicksRemaining--;
   // Opt-in self-righting assist (ticket comment 53182/53188/53189/53191) - OFF
   // (SelfRightingAssist.OFF) for every vehicle unless its own resolved Overrides metadata sets
   // all five selfRight* fields; see Overrides.selfRightingConfig. ROLL ONLY (comment 53191:
   // "wheelie reduction is NOT an objective" - front lift/wheelies are explicitly fine, this
   // never touches pitch) - SelfRightingAssist's own design already guarantees that.
   //
   // Grounded/support (comment 53189's correction): `contact` means "at least one ray hit",
   // including a hit with zero normal (no actual load) - NOT what "grounded" should mean for
   // this assist. Uses actual summed positive normal load across all wheels instead, so a
   // rear-only-support wheelie (comment 53191: "including rear-only positive support") still
   // correctly gates as grounded, while an airborne or all-zero-load vehicle does not.
   //
   // Occupancy: PartSeat extends APart extends AEntityF_Multipart extends AEntityE_Interactable
   // extends AEntityD_Definable extends AEntityC_Renderable extends AEntityB_Existing, where the
   // inherited `rider` field (IWrapperEntity, null when empty) actually lives - confirmed
   // against this ticket's own extracted MTS source (successful compilation), not a guess. No
   // vehicle-wide "all riders" collection exists; each seat tracks its own.
   if (selfRight.strength()>0 || selfRight.damping()>0) {
    double totalNormalLoad=0; for (var h : hits) totalNormalLoad += h.normal();
    boolean hasPositiveSupport = totalNormalLoad > 0;
    boolean occupied = false;
    for (var part : v.allParts) if (part instanceof PartSeat seat && seat.rider != null) { occupied = true; break; }
    Vector3d bodyForward=q.transform(new Vector3d(0,0,1));
    Vector3d bodyRight=new Vector3d(up).cross(bodyForward);
    Vector3d selfRightTorque = io.github.brooswitminecraft.div.experimental.SelfRightingAssist.torque(
      selfRight, bodyForward, up, bodyRight, angular, occupied, hasPositiveSupport);
    if (debugThisTick) LOGGER.info("[DIV debugGroundContact] selfRight occupied="+occupied+" totalNormalLoad="+totalNormalLoad+" torque="+vfull(selfRightTorque));
    // LOCAL frame - CONFIRMED correct (comment 53200, independently verified against the real
    // public Sable source, not just this file's own inverse.transform(...) pattern or
    // ForceTotal's field naming as earlier reasoning here had it): RigidBodyHandle.
    // applyTorqueImpulse->applyAngularImpulse->applyLinearAndAngularImpulse passes its torque
    // argument unchanged through to native Rust (sable_rapier lib.rs), which rotates it by the
    // rigid body's own current rotation before applying it - the argument is expected in LOCAL
    // frame. stepFlight/stepRotor's existing applyTorqueImpulse calls pass WORLD-frame torque
    // directly - now a CONFIRMED pre-existing latent bug by this same evidence, not copied here;
    // flagged, not fixed, since that is unrelated shipped code outside this ticket's immediate
    // change - worth its own fix, separately.
    if (selfRightTorque.lengthSquared() > 0) handle.applyTorqueImpulse(inverse.transform(new Vector3d(selfRightTorque).mul(DT)));
   }
   double towThrust = applyTowingCompensation(v);
   return contact || drive!=0 || towThrust!=0;
  }
  /**
   * Ticket comment 53365: John's quad accepted and installed, but his car (four tires + seat)
   * does not drive, and the existing debugGroundContact line (throttle/driveRaw/drive/
   * brakeActive/parkingBrakeActive) cannot distinguish "engine never started" from any other
   * cause of zero drive - it never surfaces engine presence/running/gear/fuel/electrical state
   * at all. Read-only: every field read here (PartEngine.running/rpm/currentGearVar/magnetoVar/
   * electricStarterVar/handStarterVar, AEntityVehicleE_Powered.fuelTank/electricPower) is IV's
   * own public state, never written to - this never alters force/control and never auto-starts
   * anything. Logs "unavailable" rather than guessing wherever a value cannot actually be read
   * (currently only the defensive fuelTank==null case - every other field checked against the
   * decompiled source is a plain, always-present public field/ComputedVariable, not something
   * that can itself throw or be absent).
   *
   * <p>Edge-triggered on DISCRETE state only (review 53367's correction to this method's first
   * version): a running engine's rpm/fuel level/electricPower change almost every tick on their
   * own, so comparing the FULL line (continuous values included) made "changed since last
   * logged" trivially true on nearly every debug tick while an engine runs - defeating the
   * claimed modest/edge-triggered sampling entirely. The comparison below uses only identity/
   * count/running/gear/ignition/starter plus a derived fuel-empty/nonempty boolean (not the raw
   * continuous fuel level); the EMITTED line still always carries the exact continuous rpm/fuel
   * level/electricPower values, just sampled periodically ({@link
   * #ENGINE_FUEL_ELECTRICAL_RECEIPT_PERIOD_TICKS} ticks) rather than on every tiny fluctuation.
   * A discrete transition (engine started/stopped, gear changed, ignition/starter toggled, fuel
   * crossing empty/nonempty) still logs immediately regardless of that period.
   *
   * <p>Per-engine identity uses {@code engine.definition.packID+":"+engine.definition.systemName}
   * (the same convention {@code chassisReceipt} already uses for the vehicle itself) rather than
   * bare iteration index, which can shift if parts are added/removed/reordered between ticks.
   */
  private void logEngineFuelElectricalReceiptIfDue(EntityVehicleF_Physics v, int debugTick) {
   StringBuilder engines = new StringBuilder("["), discreteEngines = new StringBuilder("[");
   int engineCount = 0;
   for (var part : v.allParts) {
    if (!(part instanceof PartEngine engine)) continue;
    if (engineCount > 0) { engines.append("; "); discreteEngines.append("; "); }
    String identity = engine.definition.packID+":"+engine.definition.systemName;
    engines.append("id=").append(identity)
      .append(" running=").append(engine.running)
      .append(" gear=").append(engine.currentGearVar.currentValue)
      .append(" rpm=").append(engine.rpm)
      .append(" ignitionOn=").append(engine.magnetoVar.isActive)
      .append(" electricStarterEngaged=").append(engine.electricStarterVar.isActive)
      .append(" handStarterEngaged=").append(engine.handStarterVar.isActive);
    // Discrete-only subset used for the edge-trigger comparison - deliberately omits rpm.
    discreteEngines.append("id=").append(identity)
      .append(" running=").append(engine.running)
      .append(" gear=").append(engine.currentGearVar.currentValue)
      .append(" ignitionOn=").append(engine.magnetoVar.isActive)
      .append(" electricStarterEngaged=").append(engine.electricStarterVar.isActive)
      .append(" handStarterEngaged=").append(engine.handStarterVar.isActive);
    engineCount++;
   }
   engines.append("]"); discreteEngines.append("]");
   String fuelState, discreteFuelState;
   if (v.fuelTank == null) {
    fuelState = "fuelLevel=unavailable fuelMax=unavailable fuelType=unavailable (no fuelTank on this vehicle)";
    discreteFuelState = "fuelPresent=false";
   } else {
    fuelState = "fuelLevel="+v.fuelTank.getFluidLevel()+" fuelMax="+v.fuelTank.getMaxLevel()+" fuelType="+v.fuelTank.getFluid();
    discreteFuelState = "fuelPresent=true fuelNonEmpty="+(v.fuelTank.getFluidLevel()>0);
   }
   // Full line (continuous values included) - ALWAYS what actually gets logged; never part of
   // the change-detection comparison itself (see discreteState below).
   String stateOnly = "entity="+v.uniqueUUID+" engineCount="+engineCount+" engines="+engines+" "+fuelState+" electricPower="+v.electricPower;
   // Discrete-only (identity/count/running/gear/ignition/starter/fuel-empty-vs-nonempty) - what
   // change-detection actually compares against, so continuous drift alone never counts as "due".
   String discreteState = "entity="+v.uniqueUUID+" engineCount="+engineCount+" engines="+discreteEngines+" "+discreteFuelState;
   boolean due = lastEngineFuelElectricalReceiptDiscreteState==null || !lastEngineFuelElectricalReceiptDiscreteState.equals(discreteState)
     || ticksSinceLastEngineFuelElectricalReceipt >= ENGINE_FUEL_ELECTRICAL_RECEIPT_PERIOD_TICKS;
   ticksSinceLastEngineFuelElectricalReceipt++;
   if (due) {
    LOGGER.info("[DIV debugGroundContact] engineFuelElectricalReceipt tick="+debugTick+" "+stateOnly);
    lastEngineFuelElectricalReceiptDiscreteState = discreteState;
    ticksSinceLastEngineFuelElectricalReceipt = 0;
   }
  }
  /**
   * Ticket comment 53369: "investigate whether the enclosing two-body chassis hits terrain and
   * bears most weight, starving tire normal/grip, or actual impulse/contact cancellation" - and
   * 53371's explicit correction: a single body-center bottom-Y scalar is wrong once the vehicle
   * is tilted (bank/pitch); must transform the box's ACTUAL corners by its own live orientation
   * and compare terrain height at each corner's own world footprint, not one vertical line
   * through the center. Read-only: {@code level.clip(...)} is the exact same query already used
   * for wheel ground detection (never a mutation), and this never applies any force or alters
   * either chassis box - purely observational. Checks BOTH boxes when {@link #twoBodyActive}
   * (either half could independently touch terrain), using each box's own LIVE Sable pose/half-
   * extents (not this tick's stepGround-local {@code pose}/{@code q} snapshot), so this reflects
   * the real physics body exactly as Sable itself currently has it.
   */
  private void logChassisCornerGroundClearanceIfDue(EntityVehicleF_Physics v, Vector3d up, int debugTick) {
   record BoxEntry(String label, BoxPhysicsObject box) {}
   StringBuilder boxesFull = new StringBuilder("["), boxesDiscrete = new StringBuilder("[");
   boolean first = true;
   for (var entry : twoBodyActive
       ? List.of(new BoxEntry("lower", box), new BoxEntry("upper", upperBox))
       : List.of(new BoxEntry("single", box))) {
    String label = entry.label();
    BoxPhysicsObject thisBox = entry.box();
    var pose = thisBox.getPose();
    Vector3d half = new Vector3d(thisBox.getHalfExtents());
    StringBuilder cornersFull = new StringBuilder("["), cornersDiscrete = new StringBuilder("[");
    for (int i = 0; i < 4; i++) {
     double sx = (i&1)==0 ? -1 : 1, sz = (i&2)==0 ? -1 : 1;
     Vector3d localCorner = new Vector3d(sx*half.x, -half.y, sz*half.z);
     Vector3d worldCorner = pose.transformPosition(localCorner, new Vector3d());
     // Short raycast through the corner itself (lifted slightly above it, cast slightly below
     // it) - same ClipContext.Block.COLLIDER query the wheel suspension already uses, just
     // centered on this corner instead of a wheel anchor.
     Vector3d rayStart = new Vector3d(worldCorner).fma(0.1, up);
     Vector3d rayEnd = new Vector3d(worldCorner).fma(-0.4, up);
     var hit = level.clip(new ClipContext(vec(rayStart), vec(rayEnd), ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, (net.minecraft.world.entity.Entity) null));
     if (i > 0) { cornersFull.append("; "); cornersDiscrete.append("; "); }
     if (hit.getType() == HitResult.Type.MISS) {
      cornersFull.append("corner").append(i).append("=clear(noHitWithin0.5)");
      cornersDiscrete.append("corner").append(i).append("=clear");
     } else {
      double hitY = hit.getLocation().y;
      double clearance = worldCorner.y - hitY;
      cornersFull.append("corner").append(i).append("=").append(clearance);
      cornersDiscrete.append("corner").append(i).append("=").append(clearance <= 0 ? "touching" : "clear");
     }
    }
    cornersFull.append("]"); cornersDiscrete.append("]");
    if (!first) { boxesFull.append("; "); boxesDiscrete.append("; "); }
    boxesFull.append(label).append("=").append(cornersFull);
    boxesDiscrete.append(label).append("=").append(cornersDiscrete);
    first = false;
   }
   boxesFull.append("]"); boxesDiscrete.append("]");
   String stateOnly = "entity="+v.uniqueUUID+" twoBodyActive="+twoBodyActive+" boxes="+boxesFull;
   String discreteState = "entity="+v.uniqueUUID+" twoBodyActive="+twoBodyActive+" boxes="+boxesDiscrete;
   boolean due = lastChassisClearanceDiscreteState==null || !lastChassisClearanceDiscreteState.equals(discreteState)
     || ticksSinceLastChassisClearanceReceipt >= CHASSIS_CLEARANCE_RECEIPT_PERIOD_TICKS;
   ticksSinceLastChassisClearanceReceipt++;
   if (due) {
    LOGGER.info("[DIV debugGroundContact] chassisCornerGroundClearanceReceipt tick="+debugTick+" "+stateOnly);
    lastChassisClearanceDiscreteState = discreteState;
    ticksSinceLastChassisClearanceReceipt = 0;
   }
  }
  /**
   * IV would normally add getRecursiveTowingThrust() - the engine thrust of everything this
   * vehicle is towing - straight to its own thrustForce every tick, but its getForcesAndMotions
   * never runs once PhysicsBridge takes over (see VehicleForcesMixin), so towing would otherwise
   * feel lighter than it should. That recursive sum is in IV's own force units, where IV itself
   * integrates it as motion.addScaled(totalForce, 1/currentMass) once per tick - not SI newtons
   * - so it must go through the same IV-motion<->Sable-velocity conversion this class already
   * uses on activation/writeback (v.motion * v.speedFactor * 20), scaled by this body's own
   * mass (which may be a tuning.massKg override, not v.currentMass) to land on the matching
   * Sable impulse. See TowingForces for the derivation. unjointedTowingThrust (not IV's own
   * getRecursiveTowingThrust() directly) is what must be replicated here: a towed vehicle that
   * already has its own Sable body and hitch joint (MINECRAFT-498) applies its own engine thrust
   * directly via its own step call, which already reaches this vehicle mechanically through the
   * joint Sable itself solves - replicating that portion again here would double-count it.
   *
   * <p>Shared by all three step methods, not just stepGround: this vehicle's own category does
   * not change whether something it is towing can still be on IV's native hitch fallback (the
   * first-activation ordering wait, or a towed float-only hull with no hitch-capable body at
   * all) - a flight or rotor towing vehicle needs this compensation exactly as much as a ground
   * one does, since Sable taking over its force path (VehicleForcesMixin) suppresses IV's own
   * getRecursiveTowingThrust() call for it regardless of category (self-review finding: an
   * earlier revision applied this only from stepGround, silently dropping a flight/rotor towing
   * vehicle's compensation for any trailer still on native fallback).
   */
  private double applyTowingCompensation(EntityVehicleF_Physics v) {
   double towThrust = unjointedTowingThrust(v);
   if (towThrust != 0) {
    // Review 53272's "preserve... towing boundaries" - applying at local origin (0,0,0) means
    // the body's OWN center, which is G in the single-body path but the LOWER half's own center
    // (not G) for a two-body vehicle - a real lever-arm mismatch never audited here, unlike
    // stepGround's own owner-resolution fix. Skip (logged once) rather than silently misapply.
    if (twoBodyActive) {
     if (!loggedTwoBodyTowingWarning) {
      LOGGER.error("[DIV twoBodyChassis] entity="+v.uniqueUUID+" has an active towing compensation thrust - unsupported for this candidate (a real lever-arm mismatch for a two-body chassis, independent of MINECRAFT-525's now-fixed createHitch anchor/frame convention); skipping this impulse.");
      loggedTwoBodyTowingWarning=true;
     }
    } else {
     handle.applyImpulseAtPoint(new Vector3d(), new Vector3d(0,0,TowingForces.impulse(towThrust,v.speedFactor,mass,v.currentMass)));
    }
   }
   return towThrust;
  }
  /**
   * Fixed-wing aircraft and blimps (never a rotorcraft - {@code Body.step} routes any vehicle
   * with an installed rotor propeller to {@link #stepRotor} instead, since it has no wing for
   * this method's lift model to act on): no wheels, so this applies {@link FlightForces} instead
   * of the raycast suspension/friction loop above -
   * wing lift, parasitic+induced drag, aileron/elevator/rudder control torques, throttle-linear
   * thrust from any running engine, and - for a blimp with a configured ballastVolume - mass/
   * gravity-compatible neutral-buoyancy lift plus ballast trim, gated and scaled the way IV's
   * own ballast system is (see {@link FlightForces#blimpBuoyancy}), not a literal Archimedes
   * calculation on that field's raw number. {@code tuning.driveForce} is reused as this
   * vehicle's max thrust, the same way it is reused as max drive force for a ground vehicle -
   * one shared, per-car-overridable propulsive-force knob rather than a second config field.
   * MINECRAFT-521: {@code tuning.stallAngleDegrees}/{@code tuning.liftSlope} (wing lift, via
   * {@link FlightForces#liftCoefficient}) and {@code tuning.controlSurfaceSlope} (aileron/
   * elevator/rudder authority, via {@link FlightForces#controlSurfaceForce}) are read fresh from
   * the live {@code tuning} object every tick, same as every other live-safe field below - an
   * unconfigured vehicle gets exactly {@link FlightForces}'s own previous hardcoded constants via
   * {@link Overrides}'s FALLBACK.
   * MINECRAFT-541: {@code tuning.aileron/elevator/rudderAuthorityScale} are each passed to their
   * own {@link FlightForces#controlSurfaceForce(double, double, double, double, double)} call
   * below (the same "explicit authority-multiplier overload" shape {@link
   * RotorForces#cyclicTorque(double, double, double, double)}/{@link
   * RotorForces#yawTorque(double, double, double, double)} already established for the rotor
   * fields above) - independently multiplying that one axis's force, never the shared {@code
   * controlSurfaceSlope}, the native area, or the externally-applied {@code wingArm}/{@code
   * tailArm} lever arm (all still read/applied exactly as before, unduplicated). Since the scale
   * is folded in BEFORE the lever arm, not after, and {@code rudderDeflection-sideslip} is still
   * computed first and passed in as this call's own deflection argument, the existing
   * sideslip-restoring term's semantics are entirely unchanged - only the resulting yaw torque's
   * magnitude is scaled. All three are read fresh from the live {@code tuning} object every
   * tick, same as every other live-safe field below; FALLBACK 1.0 reproduces the exact prior
   * controlSurfaceSlope-only torque for an unconfigured vehicle.
   *
   * <p>MINECRAFT-541 (review kickback correction, comment 54361): each axis's scale is passed
   * through {@link FlightForces#gatedControlAuthorityScale} first - gated on {@code
   * motorized.isBlimp} (the authoritative classification, same reasoning MINECRAFT-542's own
   * {@code gatedFixedWingAngularDampingCoefficient} gate below already uses for a blimp that can
   * itself carry positive wing/control-surface geometry) - so a blimp always gets the documented
   * {@code 1.0} no-op regardless of its configured/inherited scale, preserving this ticket's own
   * "preserve non-fixed-wing/blimp behavior" requirement exactly. Only a non-blimp fixed wing
   * gets its configured scale passed through unchanged.
   *
   * <p>MINECRAFT-542: {@code tuning.fixedWingAngularDampingCoefficient} additionally ADDS (never
   * replaces) a bounded, dissipative angular-rate damping torque on top of the aileron/elevator/
   * rudder control torque above, via {@link #rotorAngularDampingTorque} / {@link
   * RotorForces#angularDampingTorque} - the exact same inertia-aware, no-reversal formula
   * MINECRAFT-537 established for {@link #stepRotor}, reused here verbatim (never duplicated) but
   * driven by its own wholly separate {@code tuning.fixedWingAngularDampingCoefficient}, never
   * {@code tuning.rotorAngularDampingCoefficient} - the two fields are deliberately never shared
   * or aliased. It opposes only this body's OWN current angular velocity (read fresh from {@code
   * handle.getAngularVelocity} every tick, never the pilot's aileron/elevator/rudder input), so it
   * is deliberately NOT autopilot, NOT a target-attitude/auto-level system, and NOT a sideslip
   * redesign - it cannot substitute for the pilot's own control authority, and leaves the existing
   * aileron/elevator/rudder torque above completely independent. 0.0 (the FALLBACK) is a true
   * no-op, so an unconfigured fixed-wing vehicle's torque - and the resulting {@code
   * handle.applyTorqueImpulse} below - is byte-identical to before this ticket. Gated on {@code
   * aspectRatio>0} (a real wing present, the same gate {@link FlightForces#dragCoefficient}'s own
   * {@code parasiticDragMultiplier}/{@code oswaldEfficiency} already use, MINECRAFT-536) AND on
   * {@code !motorized.isBlimp} - the authoritative classification, needed IN ADDITION to the
   * aspect-ratio check because a blimp definition can itself carry a positive {@code wingArea}/
   * {@code wingSpan} (e.g. stabilizer fins, or a copied/mistaken config), which would otherwise
   * still give a positive aspect ratio despite the vehicle being a blimp. A blimp's angular motion
   * therefore stays completely unaffected by this field regardless of how it OR its wing geometry
   * are configured, matching this ticket's "fixed-wing only, preserve blimps" scope exactly.
   * Never reaches a rotorcraft ({@link #stepRotor} never reads this field) or a ground/boat
   * vehicle either.
   */
  private boolean stepFlight(EntityVehicleF_Physics v, Pose3d pose, Quaterniond q, Quaterniond inverse, Vector3d velocity, Vector3d up) {
   var motorized = v.definition.motorized;
   Vector3d forward=q.transform(new Vector3d(0,0,1));
   Vector3d right=new Vector3d(up).cross(forward);
   Vector3d localVelocity=inverse.transform(new Vector3d(velocity));
   double forwardSpeed=localVelocity.z, verticalSpeed=localVelocity.y, lateralSpeed=localVelocity.x;
   double speed=velocity.length();
   double dynamicPressure=FlightForces.dynamicPressure(AIR_DENSITY,speed);
   double angleOfAttack=Math.atan2(-verticalSpeed,Math.abs(forwardSpeed));
   double sideslip=Math.atan2(lateralSpeed,Math.abs(forwardSpeed));
   double liftCoeff=FlightForces.liftCoefficient(angleOfAttack,tuning.stallAngleDegrees,tuning.liftSlope);
   double aspectRatio=motorized.wingArea>0 ? (motorized.wingSpan*motorized.wingSpan)/motorized.wingArea : 0;
   // MINECRAFT-536 (review correction): the Math.max(0.02,native) floor, tuning.
   // parasiticDragMultiplier, and tuning.oswaldEfficiency are now all applied INSIDE
   // FlightForces.dragCoefficient itself, gated on aspectRatio>0 there - a blimp/no-wing
   // vehicle (aspectRatio<=0) must see its parasitic drag completely unaffected by a configured
   // multiplier, same preservation requirement liftSlope/controlSurfaceSlope already honor by
   // having no rotorcraft consumer at all. See that method's own doc for the exact gating.
   double dragCoeff=FlightForces.dragCoefficient(liftCoeff,motorized.dragCoefficient,aspectRatio,tuning.parasiticDragMultiplier,tuning.oswaldEfficiency);
   double referenceArea=motorized.wingArea>0 ? motorized.wingArea : motorized.crossSectionalArea;
   double lift=FlightForces.aeroForce(liftCoeff,motorized.wingArea,dynamicPressure);
   double drag=FlightForces.aeroForce(dragCoeff,referenceArea,dynamicPressure);
   // Gated on ballastVolume being configured/nonzero, matching IV's own ballast-active
   // condition, not on gravityFactor (see FlightForces.blimpBuoyancy for why).
   double buoyant=motorized.isBlimp
     ? FlightForces.blimpBuoyancy(mass,9.81,motorized.ballastVolume,v.ballastControlVar.currentValue)
     : 0;
   double throttle=0;
   for(var part:v.allParts) if(part instanceof PartEngine engine && engine.running) throttle=v.throttleVar.currentValue;
   double thrustForce=FlightForces.thrust(throttle,tuning.driveForce);
   Vector3d dragDirection=speed>1e-6 ? new Vector3d(velocity).normalize() : new Vector3d();
   Vector3d force=new Vector3d(up).mul(lift).fma(buoyant,WORLD_UP).fma(thrustForce,forward).fma(-drag,dragDirection);
   handle.applyLinearImpulse(force.mul(DT));
   double wingArm=motorized.wingSpan>0 ? motorized.wingSpan/2 : 1;
   double tailArm=motorized.tailDistance>0 ? motorized.tailDistance : 1;
   double aileronDeflection=Math.toRadians(v.aileronInputVar.currentValue);
   double elevatorDeflection=Math.toRadians(v.elevatorInputVar.currentValue);
   double rudderDeflection=Math.toRadians(v.rudderInputVar.currentValue);
   // MINECRAFT-541 (review kickback correction, comment 54361): gate each axis's authority
   // scale on the authoritative motorized.isBlimp classification via
   // FlightForces.gatedControlAuthorityScale (same shape as MINECRAFT-542's own
   // gatedFixedWingAngularDampingCoefficient immediately below) - a blimp always gets the
   // documented 1.0 no-op regardless of configured scale or wing/control-surface geometry.
   double effectiveAileronAuthorityScale=FlightForces.gatedControlAuthorityScale(motorized.isBlimp,tuning.aileronAuthorityScale);
   double effectiveElevatorAuthorityScale=FlightForces.gatedControlAuthorityScale(motorized.isBlimp,tuning.elevatorAuthorityScale);
   double effectiveRudderAuthorityScale=FlightForces.gatedControlAuthorityScale(motorized.isBlimp,tuning.rudderAuthorityScale);
   double rollTorque=FlightForces.controlSurfaceForce(aileronDeflection,motorized.aileronArea,dynamicPressure,tuning.controlSurfaceSlope,effectiveAileronAuthorityScale)*wingArm;
   double pitchTorque=FlightForces.controlSurfaceForce(elevatorDeflection,motorized.elevatorArea,dynamicPressure,tuning.controlSurfaceSlope,effectiveElevatorAuthorityScale)*tailArm;
   double yawTorque=FlightForces.controlSurfaceForce(rudderDeflection-sideslip,motorized.rudderArea,dynamicPressure,tuning.controlSurfaceSlope,effectiveRudderAuthorityScale)*tailArm;
   Vector3d torque=new Vector3d(forward).mul(rollTorque).fma(pitchTorque,right).fma(yawTorque,up);
   // MINECRAFT-542: tuning.fixedWingAngularDampingCoefficient is read live off this tick's tuning
   // object, same as stallAngleDegrees/liftSlope/controlSurfaceSlope/parasiticDragMultiplier/
   // oswaldEfficiency above - see applyLiveSafeFields. ADDED to (never substituted for) the
   // pilot's own aileron/elevator/rudder control torque just assembled above - it only ever
   // opposes this body's OWN current angular velocity (read fresh here, not cached), so it cannot
   // be mistaken for autopilot/auto-level/sideslip redesign. Overrides.resolved always fills this
   // from FALLBACK (0.0) when unconfigured, and RotorForces.angularDampingTorque returns exactly 0
   // for a non-positive coefficient, so an unconfigured fixed-wing vehicle's torque - and
   // therefore handle.applyTorqueImpulse below - is byte-identical to before this ticket.
   // Gated on aspectRatio>0 AND !motorized.isBlimp via
   // FlightForces.gatedFixedWingAngularDampingCoefficient - the no-wing half mirrors
   // dragCoefficient's own parasiticDragMultiplier/oswaldEfficiency gate just above
   // (MINECRAFT-536); the explicit isBlimp half is required in addition (review correction) since
   // a blimp definition can itself carry a positive wingArea/wingSpan (e.g. stabilizer fins, or a
   // copied/mistaken config), which would otherwise give a positive aspectRatio despite the
   // vehicle being a blimp. A blimp's angular motion must stay completely unaffected by this
   // field regardless of how it or its wing geometry are configured, not merely
   // still-damped-by-something.
   double effectiveFixedWingAngularDampingCoefficient = FlightForces.gatedFixedWingAngularDampingCoefficient(aspectRatio, motorized.isBlimp, tuning.fixedWingAngularDampingCoefficient);
   Vector3d angularVelocity=handle.getAngularVelocity(new Vector3d());
   torque.add(rotorAngularDampingTorque(angularVelocity,forward,right,up,inertiaBody,effectiveFixedWingAngularDampingCoefficient,DT));
   handle.applyTorqueImpulse(torque.mul(DT));
   applyTowingCompensation(v);
   return true;
  }
  /**
   * A rotorcraft (installed rotor propeller - {@link Classification#isRotorcraft}): no wing, so
   * {@link FlightForces}'s angle-of-attack lift has nothing to act on. Collective pitch from
   * throttle supplies vertical thrust ({@link RotorForces#lift}); cyclic tilt from elevator/
   * aileron input steers that thrust vector forward/sideways for the hover-to-forward-flight
   * transition ({@link RotorForces#verticalComponent}/{@link RotorForces#horizontalComponent})
   * and separately drives attitude torque ({@link RotorForces#cyclicTorque}); rudder input
   * supplies yaw/anti-torque authority ({@link RotorForces#yawTorque}). {@code tuning.driveForce}
   * is reused as this vehicle's max rotor thrust, the same shared propulsive-force knob as the
   * ground and fixed-wing categories. {@code installedPropellers} is this tick's live installed-
   * parts list (see {@link PhysicsBridge#installedPropellers}), not a value cached from
   * construction, so the rotor radius below tracks a rotor added/removed/replaced in flight. A
   * float-equipped rotorcraft (amphibious helicopter) additionally gets {@link BuoyantForces}
   * applied by the caller when landed/floating on water - this method supplies only the rotor's
   * own thrust/attitude, which stays correct either way since a rotor sitting above a floating
   * hull is never itself submerged.
   *
   * <p>MINECRAFT-522: {@code tuning.cyclicLimitDegrees} bounds the PER-AXIS cyclic tilt clamp
   * (pitch and roll independently - same as this method already did with the fixed 20-degree
   * constant; NOT a combined/total-disk-angle bound, so the combined tilt can still exceed this
   * number when both axes are near their limit at once), and {@code
   * tuning.cyclicAuthorityScale}/{@code tuning.yawAuthorityScale} multiply the already
   * thrust-gated cyclic/yaw torque ({@link RotorForces#cyclicTorque(double,double,double,double)}/
   * {@link RotorForces#yawTorque(double,double,double,double)}). All three default to the exact
   * prior behavior (20deg/1.0/1.0) via {@link Overrides#resolved} when unconfigured, and are
   * read live off this tick's {@code tuning} the same way {@code driveForce} already is.
   *
   * <p>MINECRAFT-537: {@code tuning.rotorAngularDampingCoefficient} additionally ADDS (never
   * replaces) a bounded, dissipative angular-rate damping torque on top of the cyclic/yaw control
   * torque above, via {@link #rotorAngularDampingTorque} / {@link
   * RotorForces#angularDampingTorque} - independent of, and composed with, the MINECRAFT-522
   * fields immediately above. It opposes only this body's OWN current angular velocity (read
   * fresh from {@code handle.getAngularVelocity} every tick, never the pilot's cyclic/yaw input),
   * so it is deliberately NOT auto-level, NOT a hover autopilot, and NOT cyclic-input filtering -
   * it cannot substitute for the pilot's own control authority. 0.0 (the FALLBACK) is a true
   * no-op, so an unconfigured rotorcraft's torque - and the resulting
   * {@code handle.applyTorqueImpulse} below - is byte-identical to before this ticket.
   *
   * <p>MINECRAFT-543: {@code tuning.rotorAirDragCoefficient} additionally ADDS (never replaces)
   * a bounded, dissipative quadratic drag force on top of the thrust force above, via {@link
   * RotorForces#translationalDragForce} - independent of the MINECRAFT-522/537 fields, which only
   * ever touch attitude/yaw authority or angular rate. It opposes only this body's OWN current
   * linear velocity (read fresh from {@code handle.getLinearVelocity} every tick, never the
   * pilot's throttle/cyclic input), so it cannot substitute for the pilot's own thrust authority.
   * 0.0 (the FALLBACK) is a true no-op, so an unconfigured rotorcraft's force - and the resulting
   * {@code handle.applyLinearImpulse} below - is byte-identical to before this ticket.
   */
  private boolean stepRotor(EntityVehicleF_Physics v, Quaterniond q, Vector3d up, List<JSONPart.JSONPartPropeller> installedPropellers) {
   var motorized = v.definition.motorized;
   Vector3d forward=q.transform(new Vector3d(0,0,1));
   Vector3d right=new Vector3d(up).cross(forward);
   double throttle=0;
   for(var part:v.allParts) if(part instanceof PartEngine engine && engine.running) throttle=v.throttleVar.currentValue;
   double collective=RotorForces.collective(throttle);
   double thrust=RotorForces.lift(collective,tuning.driveForce);
   // MINECRAFT-522: tuning.cyclicLimitDegrees/cyclicAuthorityScale/yawAuthorityScale are read
   // live off this tick's tuning object (same as driveForce above), not cached at construction -
   // see applyLiveSafeFields. Overrides.resolved always fills these from FALLBACK (20deg/1.0/1.0)
   // when unconfigured, so an unconfigured rotorcraft gets byte-identical behavior to before.
   double maxCyclicTilt=Math.toRadians(tuning.cyclicLimitDegrees);
   double tiltPitch=RotorForces.cyclicTilt(Math.toRadians(v.elevatorInputVar.currentValue),maxCyclicTilt);
   double tiltRoll=RotorForces.cyclicTilt(Math.toRadians(v.aileronInputVar.currentValue),maxCyclicTilt);
   double tiltMagnitude=Math.hypot(tiltPitch,tiltRoll);
   double vertical=RotorForces.verticalComponent(thrust,tiltMagnitude);
   double forwardComp=RotorForces.horizontalComponent(thrust,tiltPitch);
   double lateralComp=RotorForces.horizontalComponent(thrust,tiltRoll);
   Vector3d force=new Vector3d(up).mul(vertical).fma(forwardComp,forward).fma(lateralComp,right);
   // MINECRAFT-543: tuning.rotorAirDragCoefficient is read live off this tick's tuning object,
   // same as driveForce/cyclicLimitDegrees/etc. above - see applyLiveSafeFields. ADDED to (never
   // substituted for) the thrust force just assembled above - it only ever opposes this body's
   // OWN current linear velocity (read fresh here via handle.getLinearVelocity, never the pilot's
   // throttle/cyclic/yaw input), so it cannot be mistaken for reduced thrust or an autopilot.
   // Overrides.resolved always fills this from FALLBACK (0.0) when unconfigured, and
   // RotorForces.translationalDragForce returns an exact-zero vector for a non-positive
   // multiplier, so an unconfigured rotorcraft's force - and therefore handle.applyLinearImpulse
   // below - is byte-identical to before this ticket. Orientation-independent by construction
   // (RotorForces.translationalDragForce's own doc): it opposes the actual world-frame velocity
   // regardless of this body's attitude, so a rotated body/frame needs no special handling here.
   // Review correction (Wroosbit, PR #40): reuses the SAME native air-density/area/drag-
   // coefficient pieces stepFlight's own parasitic drag already consumes (AIR_DENSITY,
   // motorized.dragCoefficient, the wingArea/crossSectionalArea reference-area fallback - see
   // stepFlight's own identical pattern just below this method) rather than an arbitrary scalar.
   Vector3d linearVelocity=handle.getLinearVelocity(new Vector3d());
   double referenceArea=motorized.wingArea>0 ? motorized.wingArea : motorized.crossSectionalArea;
   force.add(RotorForces.translationalDragForce(linearVelocity,mass,AIR_DENSITY,motorized.dragCoefficient,referenceArea,tuning.rotorAirDragCoefficient,DT));
   handle.applyLinearImpulse(force.mul(DT));
   double tailArm=motorized.tailDistance>0 ? motorized.tailDistance : 1;
   double rotorRadiusMeters=RotorForces.rotorRadiusMeters(installedPropellers.stream()
     .filter(Classification::isRotor).mapToDouble(p -> p.diameter).max().orElse(0));
   double rollTorque=RotorForces.cyclicTorque(tiltRoll,thrust,rotorRadiusMeters,tuning.cyclicAuthorityScale);
   double pitchTorque=RotorForces.cyclicTorque(tiltPitch,thrust,rotorRadiusMeters,tuning.cyclicAuthorityScale);
   double rudderDeflection=Math.toRadians(v.rudderInputVar.currentValue);
   double yawTorque=RotorForces.yawTorque(rudderDeflection,thrust,tailArm,tuning.yawAuthorityScale);
   Vector3d torque=new Vector3d(forward).mul(rollTorque).fma(pitchTorque,right).fma(yawTorque,up);
   // MINECRAFT-537: tuning.rotorAngularDampingCoefficient is read live off this tick's tuning
   // object, same as cyclicLimitDegrees/cyclicAuthorityScale/yawAuthorityScale above - see
   // applyLiveSafeFields. ADDED to (never substituted for) the pilot's own cyclic/yaw control
   // torque just assembled above - it only ever opposes this body's OWN current angular velocity
   // (read fresh here, not cached), so it cannot be mistaken for auto-level/hover-autopilot/
   // cyclic-input filtering. Overrides.resolved always fills this from FALLBACK (0.0) when
   // unconfigured, and RotorForces.angularDampingTorque returns exactly 0 for a non-positive
   // coefficient, so an unconfigured rotorcraft's torque - and therefore handle.applyTorqueImpulse
   // below - is byte-identical to before this ticket.
   // MINECRAFT-554: tuning.rotorAngularDampingCoefficientPitch/Roll/Yaw are each read live off
   // this tick's tuning object too (see applyLiveSafeFields), independently falling back to this
   // SAME tuning.rotorAngularDampingCoefficient when that axis's own override is unset - Overrides
   // never fills these three from a separate FALLBACK, so an unconfigured rotorcraft (or one that
   // only sets the base scalar) gets the exact same uniform coefficient on every axis as before
   // this ticket, byte-identical.
   Vector3d angularVelocity=handle.getAngularVelocity(new Vector3d());
   double pitchDampingCoefficient=tuning.rotorAngularDampingCoefficientPitch!=null?tuning.rotorAngularDampingCoefficientPitch:tuning.rotorAngularDampingCoefficient;
   double rollDampingCoefficient=tuning.rotorAngularDampingCoefficientRoll!=null?tuning.rotorAngularDampingCoefficientRoll:tuning.rotorAngularDampingCoefficient;
   double yawDampingCoefficient=tuning.rotorAngularDampingCoefficientYaw!=null?tuning.rotorAngularDampingCoefficientYaw:tuning.rotorAngularDampingCoefficient;
   torque.add(rotorAngularDampingTorque(angularVelocity,forward,right,up,inertiaBody,pitchDampingCoefficient,rollDampingCoefficient,yawDampingCoefficient,DT));
   handle.applyTorqueImpulse(torque.mul(DT));
   applyTowingCompensation(v);
   return true;
  }
 }
}
