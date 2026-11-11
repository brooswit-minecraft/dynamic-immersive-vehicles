package io.github.brooswitminecraft.div;

import dev.ryanhcode.sable.api.physics.constraint.GenericConstraintConfiguration;
import dev.ryanhcode.sable.api.physics.constraint.PhysicsConstraintHandle;
import dev.ryanhcode.sable.api.physics.handle.RigidBodyHandle;
import dev.ryanhcode.sable.api.physics.object.box.BoxPhysicsObject;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.companion.math.Pose3d;
import dev.ryanhcode.sable.physics.config.dimension_physics.DimensionPhysicsData;
import com.mojang.logging.LogUtils;
import org.slf4j.Logger;
import io.github.brooswitminecraft.div.mixin.VehicleTowingAccessor;
import io.github.brooswitminecraft.div.mixin.WorldAccessor;
import minecrafttransportsimulator.baseclasses.TowingConnection;
import minecrafttransportsimulator.entities.instances.EntityVehicleF_Physics;
import minecrafttransportsimulator.entities.instances.PartEngine;
import minecrafttransportsimulator.entities.instances.PartSeat;
import minecrafttransportsimulator.entities.instances.PartGroundDevice;
import minecrafttransportsimulator.entities.instances.PartPropeller;
import minecrafttransportsimulator.jsondefs.JSONPart;
import minecrafttransportsimulator.jsondefs.JSONVehicle;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix3d;
import org.joml.Quaterniond;
import org.joml.Vector3d;
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
 static TickPlan planTick(boolean flight, boolean floatCapable, List<JSONPart.JSONPartPropeller> installedPropellers) {
  return new TickPlan(selectStepper(flight, installedPropellers), floatCapable);
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
  // Hitch coupling (and the ordering wait/rollback it needs) applies only to the ground/flight/
  // rotor domain - all three share one Body implementation. A float-only hull's activelyTowed
  // gate stays exactly as floatEligible already enforces it (see Classification's class doc for
  // why: BuoyantBody has no pose/anchor API for a joint to connect to).
  boolean ground = Classification.eligible(definition, installedGroundDevices, installedPropellers, optedOut);
  boolean floating = !ground && Classification.floatEligible(definition, installedGroundDevices, installedPropellers, towed, optedOut);
  if (brokenDefinitions.contains(definition)) { GroundContactDiagnostics.logEarlyReturn(v.uniqueUUID, isClient, "brokenDefinition:"+key); return deactivate(v); }
  if (!ground && !floating) { GroundContactDiagnostics.logEarlyReturn(v.uniqueUUID, isClient, "ineligible:"+key); return deactivate(v); }
  replaceIfCategoryChanged(registry, v, ground, existing -> existing instanceof Body, old -> { hitches.release(v); old.remove(); });
  boolean alreadyActive = registry.isActive(v);
  boolean towingVehicleActive = ground && towed && isHitchableBody(towedByConnection.towingVehicle);
  var decision = ground
    ? decide(true, isClient, towed, alreadyActive, towingVehicleActive)
    : (isClient ? ActivationDecision.CLIENT_ACTIVE : ActivationDecision.ACTIVATE);
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
  if (!activated || !ground) return activated;
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
 /** Whether the given towing vehicle's own active VehicleBody is a Body (ground/flight/rotor) -
  * the only domain a hitch joint can connect to - rather than a BuoyantBody or no body at all. */
 private static boolean isHitchableBody(EntityVehicleF_Physics towingVehicle) {
  return registry.get(towingVehicle) instanceof Body;
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
 static ActivationDecision decide(boolean eligible, boolean isClient, boolean towed, boolean alreadyActive, boolean towingVehicleActive) {
  if (!eligible) return ActivationDecision.INELIGIBLE;
  if (isClient) return ActivationDecision.CLIENT_ACTIVE;
  if (deferToNativeHitch(towed, alreadyActive, towingVehicleActive)) return ActivationDecision.DEFER_TO_NATIVE;
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
  */
 static boolean deferToNativeHitch(boolean towed, boolean alreadyActive, boolean towingVehicleActive) {
  return towed && !alreadyActive && !towingVehicleActive;
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
  EntityVehicleF_Physics desired = connection != null && isHitchableBody(connection.towingVehicle) ? connection.towingVehicle : null;
  try {
   hitches.reconcile(v, desired, () -> createHitch((Body)registry.get(desired), (Body)registry.get(v), connection));
   return true;
  } catch (RuntimeException transientFailure) {
   return false;
  }
 }
 private static PhysicsConstraintHandle createHitch(Body towing, Body trailer, TowingConnection connection) {
  var towingPose = towing.box.getPose();
  var trailerPose = trailer.box.getPose();
  var hitchPos = HitchJoint.anchorWorldPosition(towingPose.position(), towingPose.orientation(), towing.center,
    new Vector3d(connection.hitchConnection.pos.x, connection.hitchConnection.pos.y, connection.hitchConnection.pos.z), new Vector3d());
  var hookupPos = HitchJoint.anchorWorldPosition(trailerPose.position(), trailerPose.orientation(), trailer.center,
    new Vector3d(connection.hookupConnection.pos.x, connection.hookupConnection.pos.y, connection.hookupConnection.pos.z), new Vector3d());
  var config = new GenericConstraintConfiguration(hitchPos, hookupPos, towingPose.orientation(), trailerPose.orientation(),
    HitchJoint.lockedAxes(connection.hitchConnection.mounted, connection.hitchConnection.restricted));
  return SubLevelContainer.getContainer(towing.level).physicsSystem().getPipeline().addConstraint(towing.box, trailer.box, config);
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
 public static void step(EntityVehicleF_Physics vehicle) { registry.get(vehicle).step(vehicle); }
 public static void cleanup() {
  registry.removeIf((v, b) -> !v.isValid, (v, b) -> { hitches.release(v); b.remove(); });
 }
 public static void unload(Object level) {
  registry.removeIf((v, b) -> b.level() == level, (v, b) -> { hitches.release(v); b.remove(); });
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
  */
 static double suspensionSpring(double mass, int wheelCount, double suspensionTravel, double gravity) {
  return mass*gravity/(wheelCount*suspensionTravel*.5);
 }
 /**
  * Per-wheel suspension damping rate - critically-damped-ish (0.7x the critical damping
  * coefficient for this wheel's share of the sprung mass) so the settling the convergence
  * simulation checks neither oscillates indefinitely nor is sluggish enough to look like
  * persistent sinking. Same extraction rationale as {@link #suspensionSpring}.
  */
 static double suspensionDamper(double mass, int wheelCount, double spring) {
  return 2*Math.sqrt(spring*mass/wheelCount)*.7;
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
 static EffectiveMassDamperResult effectiveMassDamper(double spring, double mass, Vector3d point, Vector3d direction,
     Quaterniond inverseOrientation, Vector3d inertiaBody, double fallbackDamper) {
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
  double damperCandidate = 2*Math.sqrt(spring*effectiveMass)*.7;
  if (!Double.isFinite(damperCandidate) || damperCandidate<0) return new EffectiveMassDamperResult(fallbackDamper, effectiveMass);
  return new EffectiveMassDamperResult(damperCandidate, effectiveMass);
 }
 private static final class Body implements VehicleBody {
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
  // the LOWER half-body specifically, not a single whole-chassis box - every real wheel on this
  // ticket's own ATV geometry belongs to the lower half (TwoBodyChassis.isUpperHalf confirmed
  // false for all four), so every existing wheel-force/torque application site below (which all
  // already go through `handle`) needs NO changes at all; only the position/orientation SYNC
  // back to the vehicle (see step(), which must reconstruct G via
  // TwoBodyChassis.vehicleWorldPositionFromLowerBody rather than reading the lower body's own
  // center directly) and construction/cleanup are two-body-aware. Uses the body-local/identity
  // joint convention confirmed against the real native source (comment 53256), not createHitch's
  // own (separately flagged, un-fixed) world-space/echoed-orientation convention.
  private static final boolean EXPERIMENTAL_TWO_BODY_CHASSIS = Boolean.getBoolean("div.experimentalTwoBodyChassis");
  final boolean twoBodyActive;
  // Non-null only when twoBodyActive - the upper half-body/handle and the fixed joint connecting
  // it to the lower half (box/handle below). Held only for initial-velocity seeding and cleanup;
  // no force/torque application site targets these directly (see above).
  private final BoxPhysicsObject upperBox;
  private final RigidBodyHandle upperHandle;
  private final PhysicsConstraintHandle twoBodyJoint;
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
  Body(ServerLevel level, EntityVehicleF_Physics v, Overrides.Tuning tuning) {
   this.level=level; this.tuning=tuning;
   this.selfRight=Overrides.selfRightingConfig(tuning);
   this.flight=Classification.isFlightVehicle(v.definition);
   mass=tuning.massKg != null ? tuning.massKg : Math.max(50,v.getMass());
   Vector3d min=new Vector3d(Double.POSITIVE_INFINITY), max=new Vector3d(Double.NEGATIVE_INFINITY);
   for (var group : v.definition.collisionGroups) for (var c : group.collisions) {
    min.min(new Vector3d(c.pos.x-c.width/2,c.pos.y-c.height/2,c.pos.z-c.width/2));
    max.max(new Vector3d(c.pos.x+c.width/2,c.pos.y+c.height/2,c.pos.z+c.width/2));
   }
   if (!Double.isFinite(min.x) || max.x<=min.x || max.y<=min.y || max.z<=min.z)
    throw new InvalidChassisGeometry("Vehicle has no finite chassis collision volume");
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
   twoBodyActive=EXPERIMENTAL_TWO_BODY_CHASSIS;
   Vector3d initialVelocity=new Vector3d(v.motion.x,v.motion.y,v.motion.z).mul(v.speedFactor*20);
   if (twoBodyActive) {
    // Uninstalled/unvalidated candidate (comment 53268) - any construction failure (including
    // Overrides.twoBodyConfig's own validation, e.g. an unsafe upperMassFraction for this
    // vehicle's real mass) must roll back cleanly and surface as an ordinary construction
    // failure, exactly like the single-body path below - TwoBodyLifecycle.create already
    // guarantees no partial state on its own failure, so no extra try/catch is needed here.
    var config=Overrides.twoBodyConfig(tuning,mass);
    var bodies=TwoBodyLifecycle.create(level,position,q,half,mass,config);
    box=bodies.lowerBox(); handle=bodies.lowerHandle();
    upperBox=bodies.upperBox(); upperHandle=bodies.upperHandle(); twoBodyJoint=bodies.joint();
    handle.addLinearAndAngularVelocity(initialVelocity,new Vector3d());
    upperHandle.addLinearAndAngularVelocity(initialVelocity,new Vector3d());
   } else {
    upperBox=null; upperHandle=null; twoBodyJoint=null;
    box=new BoxPhysicsObject(new Pose3d(position,q,new Vector3d(),new Vector3d(1)),half,mass);
    var physics=SubLevelContainer.getContainer(level).physicsSystem();
    physics.addObject(box);
    // From here on, box is a live Sable object; any failure before this constructor returns
    // must remove it again, since nothing else will ever hold a reference to clean it up.
    RigidBodyHandle h;
    try {
     h=RigidBodyHandle.of(level,box);
     h.addLinearAndAngularVelocity(initialVelocity,new Vector3d());
    } catch (RuntimeException ex) {
     physics.removeObject(box);
     throw ex;
    }
    handle=h;
   }
  }
  public Object level() { return level; }
  public void remove() {
   var c=SubLevelContainer.getContainer(level);
   if (c==null) return;
   if (twoBodyActive) TwoBodyLifecycle.remove(level, new TwoBodyLifecycle.Bodies(upperBox,box,upperHandle,handle,twoBodyJoint));
   else c.physicsSystem().removeObject(box);
  }
  public void step(EntityVehicleF_Physics v) {
   box.updatePose();
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
   boolean primaryAwake = switch (plan.stepper()) {
    case GROUND -> stepGround(v,pose,q,inverse,angular,velocity,up);
    case FLIGHT -> stepFlight(v,pose,q,inverse,velocity,up);
    case ROTOR -> stepRotor(v,q,up,installedPropellers);
   };
   boolean floatAwake = plan.appliesBuoyancy() && BuoyantForces.apply(level,v,tuning,center,hullVolume,pose,q,inverse,velocity,angular,handle);
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
  private boolean stepGround(EntityVehicleF_Physics v, Pose3d pose, Quaterniond q, Quaterniond inverse, Vector3d angular, Vector3d velocity, Vector3d up) {
   double rawDrive=0;
   for(var part:v.allParts) if(part instanceof PartEngine engine && engine.running)
    rawDrive=Math.signum(engine.currentGearVar.currentValue)*v.throttleVar.currentValue*tuning.driveForce;
   boolean braking=v.brakeVar.isActive || v.parkingBrakeVar.isActive;
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
   double spring=suspensionSpring(mass,wheels.size(),tuning.suspensionTravel,gravity);
   double damper=suspensionDamper(mass,wheels.size(),spring);
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
     +" brakeActive="+v.brakeVar.isActive+" parkingBrakeActive="+v.parkingBrakeVar.isActive+" skidSteer="+skidSteer
     // Raw vs shaped drive + ramp identity (ticket comment 53115) - driveRaw==drive and
     // throttleRampTicks=0 together prove the experimental candidate is bypassed on this build/
     // session; otherwise shows exactly which configured tick count shaped this tick's drive.
     +" driveRaw="+rawDrive+" throttleRampTicks="+EXPERIMENTAL_THROTTLE_RAMP_TICKS);
   // Phase 1: raycast every wheel and compute its vertical/suspension force exactly as before -
   // suspension is per-wheel and unaffected by this change. Friction/slip is sampled at the
   // point the tire impulse is actually applied (the terrain hit, `point` below), not the
   // suspension anchor (`arm`, used only for the vertical damping sample - correct there, since
   // that's the point whose own motion determines the raycast length's rate of change). Those
   // two points differ by roughly radius+compression along `up`; on a rotating chassis, velocity
   // differs between them by angular x that offset (fixed by sampling at `point` - 691f11b).
   record WheelHit(PartGroundDevice wheel, double radius, Vector3d arm, Vector3d start, Vector3d end, double length,
     double compression, Vector3d localVelocity, double normal, Vector3d forward, Vector3d right, Vector3d point,
     double skidSteerMultiplier, double damperUsed, double effectiveMassUsed) {}
   List<WheelHit> hits=new java.util.ArrayList<>();
   for(var wheel:wheels) {
    double radius=wheel.getHeight()/2;
    Vector3d anchor=new Vector3d(wheel.localOffset.x,wheel.localOffset.y+tuning.suspensionTravel*.5,wheel.localOffset.z).sub(center);
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
    Vector3d localVelocity=new Vector3d(angular).cross(arm).add(velocity);
    Vector3d point=new Vector3d(hit.getLocation().x,hit.getLocation().y,hit.getLocation().z).sub(pose.position());
    // Opt-in experimental effective-mass damping candidate (ticket comments 53214/53219) - OFF
    // (EXPERIMENTAL_EFFECTIVE_MASS_DAMPING false, the shipped default) means damperResult is
    // exactly new EffectiveMassDamperResult(damper, NaN) - damperUsed==damper byte-for-byte,
    // nothing about today's behavior changes. Sourced from this body's own real inertiaBody (its
    // actual box mass/inertia, PhysicsBridge.Body constructor) and this wheel's own real contact
    // point (terrain hit location, same `point` used for friction below) along `up` - this
    // body's own chassis/suspension-ray direction, NOT a re-derived actual terrain surface
    // normal (review comment 53223's correction; no geometry change, this is the same `up`
    // the existing suspension/friction code already uses at this exact call). Satisfies comment
    // 53219's "actual box mass/inertia and actual contact direction/arm" requirement using that
    // direction. See PhysicsBridge.effectiveMassDamper's own doc for the guard/fallback behavior
    // and the measured record showing this is NOT a validated fix.
    var damperResult = EXPERIMENTAL_EFFECTIVE_MASS_DAMPING
      ? effectiveMassDamper(spring,mass,point,up,inverse,inertiaBody,damper)
      : new EffectiveMassDamperResult(damper, Double.NaN);
    double normal=Math.min(mass*gravity,TireForces.suspension(compression,localVelocity.dot(up),spring,damperResult.damper()));
    double steer=wheel.placementDefinition.turnsWithSteer ? (-v.rudderInputVar.currentValue / EntityVehicleF_Physics.MAX_RUDDER_ANGLE)*Math.toRadians(tuning.maxSteerDegrees) : 0;
    Vector3d forward=q.transform(new Vector3d(Math.sin(steer),0,Math.cos(steer)));
    Vector3d right=new Vector3d(up).cross(forward);
    double skidSteerMultiplier=skidSteer ? 1-Math.signum(anchor.x)*(v.rudderInputVar.currentValue/EntityVehicleF_Physics.MAX_RUDDER_ANGLE) : 1;
    hits.add(new WheelHit(wheel,radius,arm,start,end,length,compression,localVelocity,normal,forward,right,point,skidSteerMultiplier,
      damperResult.damper(),damperResult.effectiveMass()));
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
   Vector3d workingVelocity=new Vector3d(velocity), workingAngular=new Vector3d(angular);
   for (var h:hits) {
    Vector3d verticalImpulse=new Vector3d(up).mul(h.normal()*DT);
    workingVelocity.add(new Vector3d(verticalImpulse).div(mass));
    Vector3d torqueBody=inverse.transform(new Vector3d(h.point()).cross(verticalImpulse));
    workingAngular.add(q.transform(new Vector3d(torqueBody.x/inertiaBody.x,torqueBody.y/inertiaBody.y,torqueBody.z/inertiaBody.z)));
   }
   var contacts=hits.stream().map(h->new io.github.brooswitminecraft.div.experimental.MultiContactFrictionSolver.FrictionContact(
     h.point(),h.forward(),h.right(),h.normal(),h.skidSteerMultiplier())).toArray(io.github.brooswitminecraft.div.experimental.MultiContactFrictionSolver.FrictionContact[]::new);
   var results=io.github.brooswitminecraft.div.experimental.MultiContactFrictionSolver.solveFriction(contacts,mass,inertiaBody,q,inverse,workingVelocity,workingAngular,
     braking,wheels.isEmpty()?0:tuning.brakeForce/wheels.size(),wheels.isEmpty()?0:drive/wheels.size(),tuning.friction,DT,
     effectiveFrictionSolverIterations());
   // Phase 3: apply each wheel's final (vertical + solved tire) impulse exactly once, same
   // application/logging shape as before.
   for (int i=0;i<hits.size();i++) {
    var h=hits.get(i); var wheel=h.wheel(); var tire=results[i];
    Vector3d point=h.point(), forward=h.forward(), right=h.right();
    Vector3d contactVelocity=new Vector3d(angular).cross(point).add(velocity);
    double speed=contactVelocity.dot(forward), sideSpeed=contactVelocity.dot(right);
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
    handle.applyImpulseAtPoint(appliedPointLocal,appliedImpulseLocal);
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
   if (towThrust != 0) handle.applyImpulseAtPoint(new Vector3d(), new Vector3d(0,0,TowingForces.impulse(towThrust,v.speedFactor,mass,v.currentMass)));
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
   double liftCoeff=FlightForces.liftCoefficient(angleOfAttack);
   double aspectRatio=motorized.wingArea>0 ? (motorized.wingSpan*motorized.wingSpan)/motorized.wingArea : 0;
   double dragCoeff=FlightForces.dragCoefficient(liftCoeff,Math.max(0.02,motorized.dragCoefficient),aspectRatio);
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
   double rollTorque=FlightForces.controlSurfaceForce(aileronDeflection,motorized.aileronArea,dynamicPressure)*wingArm;
   double pitchTorque=FlightForces.controlSurfaceForce(elevatorDeflection,motorized.elevatorArea,dynamicPressure)*tailArm;
   double yawTorque=FlightForces.controlSurfaceForce(rudderDeflection-sideslip,motorized.rudderArea,dynamicPressure)*tailArm;
   Vector3d torque=new Vector3d(forward).mul(rollTorque).fma(pitchTorque,right).fma(yawTorque,up);
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
   */
  private boolean stepRotor(EntityVehicleF_Physics v, Quaterniond q, Vector3d up, List<JSONPart.JSONPartPropeller> installedPropellers) {
   var motorized = v.definition.motorized;
   Vector3d forward=q.transform(new Vector3d(0,0,1));
   Vector3d right=new Vector3d(up).cross(forward);
   double throttle=0;
   for(var part:v.allParts) if(part instanceof PartEngine engine && engine.running) throttle=v.throttleVar.currentValue;
   double collective=RotorForces.collective(throttle);
   double thrust=RotorForces.lift(collective,tuning.driveForce);
   double tiltPitch=RotorForces.cyclicTilt(Math.toRadians(v.elevatorInputVar.currentValue));
   double tiltRoll=RotorForces.cyclicTilt(Math.toRadians(v.aileronInputVar.currentValue));
   double tiltMagnitude=Math.hypot(tiltPitch,tiltRoll);
   double vertical=RotorForces.verticalComponent(thrust,tiltMagnitude);
   double forwardComp=RotorForces.horizontalComponent(thrust,tiltPitch);
   double lateralComp=RotorForces.horizontalComponent(thrust,tiltRoll);
   Vector3d force=new Vector3d(up).mul(vertical).fma(forwardComp,forward).fma(lateralComp,right);
   handle.applyLinearImpulse(force.mul(DT));
   double tailArm=motorized.tailDistance>0 ? motorized.tailDistance : 1;
   double rotorRadiusMeters=RotorForces.rotorRadiusMeters(installedPropellers.stream()
     .filter(Classification::isRotor).mapToDouble(p -> p.diameter).max().orElse(0));
   double rollTorque=RotorForces.cyclicTorque(tiltRoll,thrust,rotorRadiusMeters);
   double pitchTorque=RotorForces.cyclicTorque(tiltPitch,thrust,rotorRadiusMeters);
   double rudderDeflection=Math.toRadians(v.rudderInputVar.currentValue);
   double yawTorque=RotorForces.yawTorque(rudderDeflection,thrust,tailArm);
   Vector3d torque=new Vector3d(forward).mul(rollTorque).fma(pitchTorque,right).fma(yawTorque,up);
   handle.applyTorqueImpulse(torque.mul(DT));
   applyTowingCompensation(v);
   return true;
  }
 }
}
