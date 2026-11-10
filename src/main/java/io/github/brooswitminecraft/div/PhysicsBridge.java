package io.github.brooswitminecraft.div;

import dev.ryanhcode.sable.api.physics.constraint.GenericConstraintConfiguration;
import dev.ryanhcode.sable.api.physics.constraint.PhysicsConstraintHandle;
import dev.ryanhcode.sable.api.physics.handle.RigidBodyHandle;
import dev.ryanhcode.sable.api.physics.object.box.BoxPhysicsObject;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.companion.math.Pose3d;
import io.github.brooswitminecraft.div.mixin.VehicleTowingAccessor;
import io.github.brooswitminecraft.div.mixin.WorldAccessor;
import minecrafttransportsimulator.baseclasses.TowingConnection;
import minecrafttransportsimulator.entities.instances.EntityVehicleF_Physics;
import minecrafttransportsimulator.entities.instances.PartEngine;
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
  var t = Overrides.get(definition.packID + ":" + definition.systemName);
  return t != null ? t : Overrides.DEFAULT;
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
  if (definition == null) return deactivate(v);
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
  if ((!ground && !floating) || brokenDefinitions.contains(definition)) return deactivate(v);
  replaceIfCategoryChanged(registry, v, ground, existing -> existing instanceof Body, old -> { hitches.release(v); old.remove(); });
  boolean alreadyActive = registry.isActive(v);
  boolean towingVehicleActive = ground && towed && isHitchableBody(towedByConnection.towingVehicle);
  var decision = ground
    ? decide(true, v.world.isClient(), towed, alreadyActive, towingVehicleActive)
    : (v.world.isClient() ? ActivationDecision.CLIENT_ACTIVE : ActivationDecision.ACTIVATE);
  if (decision == ActivationDecision.DEFER_TO_NATIVE) return deactivate(v);
  if (decision == ActivationDecision.CLIENT_ACTIVE) return true;
  var level = ((WorldAccessor)v.world).div$level();
  if (!(level instanceof ServerLevel server) || SubLevelContainer.getContainer(server) == null) return false;
  Supplier<VehicleBody> construct = ground
    ? () -> new Body(server,v,tuning(definition))
    : () -> new BuoyantBody(server,v,tuning(definition));
  boolean activated = activateOrClassify(registry, brokenDefinitions, v, definition, construct);
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
  * across: calibrated so that, under even per-wheel load (mass*9.81/wheelCount), the per-wheel
  * {@link TireForces#suspension} force exactly balances gravity at HALF of
  * {@code suspensionTravel} compressed - the ride-height equilibrium this ticket's own triage
  * (comment 52830) already derived algebraically. Extracted as its own seam (same value,
  * verbatim) so a settling simulation can exercise the exact production constant instead of a
  * hand-copied formula that could silently drift from it.
  */
 static double suspensionSpring(double mass, int wheelCount, double suspensionTravel) {
  return mass*9.81/(wheelCount*suspensionTravel*.5);
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
 /**
  * The solid-cuboid rotational inertia (about the box's own center, diagonal in the box's own
  * local axes - a box is symmetric about each of them) a vehicle's envelope box actually has,
  * given its real half-extents and mass. Confirmed root cause (decompiled against the exact
  * pinned dependency, {@code sable-neoforge-1.21.1:2.0.5}): {@code BoxPhysicsObject} has only the
  * scalar-mass constructor this class already used, and {@code BoxMassData} hard-codes its
  * inertia tensor to an ISOTROPIC {@code Matrix3d().scale(mass/6.0)} - identical on every axis and
  * completely independent of {@code halfExtents}. That value is exactly the correct solid-cuboid
  * inertia only for a 1m-half-extent (2m) cube; {@code mass/6 = mass/3*(0.5*0.5+0.5*0.5)}. Any
  * vehicle whose actual cross-section exceeds that reference size - every drivable ground vehicle
  * this ticket covers, ATV included - gets LESS rotational resistance from Sable than its real
  * footprint should produce, so a given tire/suspension roll torque spins it faster than it
  * physically should: this is the shared mechanism behind "flips way too easily," not an
  * ATV-specific tuning gap, and it scales with the vehicle's own size exactly the way the
  * acceptance criteria require. There is no newer Sable API available at this pin to hand Sable a
  * correct tensor directly (see {@code BoxMassProperties} in a later Sable version, not this one) -
  * {@link #inertiaCorrection} is the compensating seam {@code stepGround} applies instead.
  */
 static Matrix3d solidBoxInertia(double mass, Vector3dc halfExtents) {
  double x=halfExtents.x(), y=halfExtents.y(), z=halfExtents.z();
  double ix=mass/3.0*(y*y+z*z), iy=mass/3.0*(x*x+z*z), iz=mass/3.0*(x*x+y*y);
  return new Matrix3d().scaling(ix,iy,iz);
 }
 /**
  * The angular-velocity correction {@code stepGround} must add (via
  * {@code RigidBodyHandle.addLinearAndAngularVelocity}, which sets velocity directly rather than
  * dividing an impulse by inertia again) to undo Sable's fixed isotropic {@code mass/6} angular
  * response to {@code localTorqueImpulse} (this tick's total wheel torque impulse, already
  * rotated into the body's own local axes) and replace it with the response the vehicle's real
  * {@link #solidBoxInertia} would have produced - a pure seam testable without a live Sable
  * runtime. {@code localTorqueImpulse} must be the torque impulse ALREADY APPLIED to the body this
  * tick (Sable already divided it by the isotropic tensor once); this returns only the
  * difference, not a replacement, so a caller must add this on top, never substitute it.
  */
 static Vector3d inertiaCorrection(double mass, Vector3dc halfExtents, Vector3dc localTorqueImpulse) {
  Vector3d real=solidBoxInertia(mass, halfExtents).invert().transform(new Vector3d(localTorqueImpulse));
  double isotropicInverse=6.0/mass;
  return real.sub(new Vector3d(localTorqueImpulse).mul(isotropicInverse));
 }
 private static final class Body implements VehicleBody {
  private static final Vector3d WORLD_UP = new Vector3d(0,1,0);
  private static final double AIR_DENSITY = 1.225; // sea-level ISA; no altitude/weather model
  final ServerLevel level;
  final Overrides.Tuning tuning;
  final BoxPhysicsObject box;
  final RigidBodyHandle handle;
  final Vector3d center;
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
  Body(ServerLevel level, EntityVehicleF_Physics v, Overrides.Tuning tuning) {
   this.level=level; this.tuning=tuning;
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
   this.hullVolume = half.x*2*half.y*2*half.z*2*tuning.hullFillFactor;
   var r=v.orientation;
   Quaterniond q=new Quaterniond().setFromNormalized(new Matrix3d(r.m00,r.m10,r.m20,r.m01,r.m11,r.m21,r.m02,r.m12,r.m22));
   Vector3d position=q.transform(new Vector3d(center)).add(v.position.x,v.position.y,v.position.z);
   this.halfExtents=new Vector3d(half);
   box=new BoxPhysicsObject(new Pose3d(position,q,new Vector3d(),new Vector3d(1)),half,mass);
   var physics=SubLevelContainer.getContainer(level).physicsSystem();
   physics.addObject(box);
   // From here on, box is a live Sable object; any failure before this constructor returns
   // must remove it again, since nothing else will ever hold a reference to clean it up.
   RigidBodyHandle h;
   try {
    h=RigidBodyHandle.of(level,box);
    h.addLinearAndAngularVelocity(new Vector3d(v.motion.x,v.motion.y,v.motion.z).mul(v.speedFactor*20),new Vector3d());
   } catch (RuntimeException ex) {
    physics.removeObject(box);
    throw ex;
   }
   handle=h;
  }
  public Object level() { return level; }
  public void remove() { var c=SubLevelContainer.getContainer(level); if(c!=null)c.physicsSystem().removeObject(box); }
  public void step(EntityVehicleF_Physics v) {
   box.updatePose();
   Pose3d pose=new Pose3d(box.getPose());
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
   double drive=0;
   for(var part:v.allParts) if(part instanceof PartEngine engine && engine.running)
    drive=Math.signum(engine.currentGearVar.currentValue)*v.throttleVar.currentValue*tuning.driveForce;
   boolean braking=v.brakeVar.isActive || v.parkingBrakeVar.isActive;
   // Tracked vehicles steer by differential track speed, not a steerable wheel; cars/trucks
   // keep steering the wheels marked turnsWithSteer. Both can coexist on the same chassis.
   boolean skidSteer=v.definition.motorized != null && v.definition.motorized.hasSkidSteer;
   var wheels=groundContacts(v);
   double spring=suspensionSpring(mass,wheels.size(),tuning.suspensionTravel);
   double damper=suspensionDamper(mass,wheels.size(),spring);
   boolean contact=false;
   // World-frame sum of this tick's wheel torque impulses (point x force*DT, about the COM) -
   // Sable already applies each wheel's torque share of applyImpulseAtPoint using its own fixed
   // isotropic inertia (see solidBoxInertia's doc); this total is what inertiaCorrection needs to
   // undo that and substitute the vehicle's real, size-scaled rotational response instead.
   Vector3d worldTorqueImpulse=new Vector3d();
   for(var wheel:wheels) {
    double radius=wheel.getHeight()/2;
    Vector3d anchor=new Vector3d(wheel.localOffset.x,wheel.localOffset.y+tuning.suspensionTravel*.5,wheel.localOffset.z).sub(center);
    Vector3d arm=q.transform(new Vector3d(anchor));
    Vector3d start=new Vector3d(pose.position()).add(arm);
    double length=radius+tuning.suspensionTravel;
    Vector3d end=new Vector3d(start).fma(-length,up);
    var hit=level.clip(new ClipContext(vec(start),vec(end),ClipContext.Block.COLLIDER,ClipContext.Fluid.NONE,(net.minecraft.world.entity.Entity)null));
    if(hit.getType()==HitResult.Type.MISS) { wheel.contactThisTick=false; continue; }
    double compression=length-hit.getLocation().distanceTo(vec(start));
    Vector3d localVelocity=new Vector3d(angular).cross(arm).add(velocity);
    double normal=Math.min(mass*9.81,TireForces.suspension(compression,localVelocity.dot(up),spring,damper));
    double steer=wheel.placementDefinition.turnsWithSteer ? (-v.rudderInputVar.currentValue / EntityVehicleF_Physics.MAX_RUDDER_ANGLE)*Math.toRadians(tuning.maxSteerDegrees) : 0;
    Vector3d forward=q.transform(new Vector3d(Math.sin(steer),0,Math.cos(steer)));
    Vector3d right=new Vector3d(up).cross(forward);
    double speed=localVelocity.dot(forward), sideSpeed=localVelocity.dot(right);
    double longitudinal=braking ? -Math.signum(speed)*Math.min(tuning.brakeForce/wheels.size(),Math.abs(speed)*mass/wheels.size()/DT) : drive/wheels.size();
    if (skidSteer) longitudinal*=1-Math.signum(anchor.x)*(v.rudderInputVar.currentValue/EntityVehicleF_Physics.MAX_RUDDER_ANGLE);
    longitudinal-=speed*20;
    var tire=TireForces.friction(longitudinal,-sideSpeed*mass/wheels.size()/DT,normal,tuning.friction);
    Vector3d force=new Vector3d(up).mul(normal).fma(tire.longitudinal(),forward).fma(tire.lateral(),right);
    Vector3d point=new Vector3d(hit.getLocation().x,hit.getLocation().y,hit.getLocation().z).sub(pose.position());
    worldTorqueImpulse.add(new Vector3d(point).cross(force).mul(DT));
    handle.applyImpulseAtPoint(inverse.transform(point),inverse.transform(force.mul(DT)));
    wheel.contactThisTick=true;
    wheel.animateAsOnGround=true;
    wheel.angularVelocity=speed*DT/(Math.max(.05,radius)*2*Math.PI);
    contact=true;
   }
   if (contact) {
    Vector3d localTorqueImpulse=inverse.transform(new Vector3d(worldTorqueImpulse));
    Vector3d correction=inertiaCorrection(mass,halfExtents,localTorqueImpulse);
    handle.addLinearAndAngularVelocity(new Vector3d(),q.transform(correction));
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
