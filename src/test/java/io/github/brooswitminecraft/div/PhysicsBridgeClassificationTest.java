package io.github.brooswitminecraft.div;
import org.junit.jupiter.api.Test;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import static io.github.brooswitminecraft.div.PhysicsBridge.ActivationDecision.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Exercises PhysicsBridge.activateOrClassify(...), the exact method active() calls after
 * deciding a vehicle is eligible and server-side - with a fake key/body/constructor instead of
 * a live entity/ServerLevel/Sable runtime. This is the failure-classification seam requested by
 * review: only a deterministic {@link InvalidChassisGeometry} may permanently disable a
 * definition; everything else, including an unrelated IllegalArgumentException that an
 * operational Sable/world call might throw, must stay retryable.
 */
class PhysicsBridgeClassificationTest {
 @Test void invalidChassisGeometryIsCachedAsBroken() {
  var registry = new BodyRegistry<String, Object>();
  Set<String> broken = Collections.newSetFromMap(new IdentityHashMap<>());
  boolean result = PhysicsBridge.activateOrClassify(registry, broken, "car", "car",
    () -> { throw new InvalidChassisGeometry("no finite chassis collision volume"); });
  assertFalse(result);
  assertTrue(broken.contains("car"), "a deterministic geometry failure must be cached");
  assertFalse(registry.isActive("car"));
 }

 @Test void unrelatedIllegalArgumentExceptionIsNotCachedAsBroken() {
  // The exact regression review flagged: an IllegalArgumentException from an operational call
  // (e.g. BoxPhysicsObject, RigidBodyHandle.of) is NOT InvalidChassisGeometry and must not
  // poison the definition - only that one dedicated type does.
  var registry = new BodyRegistry<String, Object>();
  Set<String> broken = Collections.newSetFromMap(new IdentityHashMap<>());
  boolean result = PhysicsBridge.activateOrClassify(registry, broken, "car", "car",
    () -> { throw new IllegalArgumentException("unrelated Sable/world argument failure"); });
  assertFalse(result);
  assertFalse(broken.contains("car"), "only InvalidChassisGeometry may be cached - any other exception, including IllegalArgumentException, stays retryable");
  assertFalse(registry.isActive("car"));
 }

 @Test void anyOtherRuntimeExceptionAlsoStaysRetryable() {
  var registry = new BodyRegistry<String, Object>();
  Set<String> broken = Collections.newSetFromMap(new IdentityHashMap<>());
  PhysicsBridge.activateOrClassify(registry, broken, "car", "car", () -> { throw new RuntimeException("transient"); });
  assertFalse(broken.contains("car"));
 }

 @Test void aRetryableFailureCanStillSucceedOnALaterCall() {
  var registry = new BodyRegistry<String, Object>();
  Set<String> broken = Collections.newSetFromMap(new IdentityHashMap<>());
  PhysicsBridge.activateOrClassify(registry, broken, "car", "car", () -> { throw new IllegalArgumentException("transient"); });
  Object body = new Object();
  boolean result = PhysicsBridge.activateOrClassify(registry, broken, "car", "car", () -> body);
  assertTrue(result);
  assertSame(body, registry.get("car"));
 }

 @Test void aSuccessfulConstructionActivatesTheKey() {
  var registry = new BodyRegistry<String, Object>();
  Set<String> broken = Collections.newSetFromMap(new IdentityHashMap<>());
  Object body = new Object();
  assertTrue(PhysicsBridge.activateOrClassify(registry, broken, "car", "car", () -> body));
  assertSame(body, registry.get("car"));
  assertTrue(broken.isEmpty());
 }

 @Test void firstActivationDefersUntilTheTowingVehicleHasABody() {
  // A trailer's very first activation must wait for its towing vehicle to already have an
  // active body - the joint it needs connects to that body, not to IV's raw position.
  assertTrue(PhysicsBridge.deferToNativeHitch(true, false, false, false));
 }

 @Test void firstActivationProceedsOnceTheTowingVehicleHasABody() {
  assertFalse(PhysicsBridge.deferToNativeHitch(true, false, true, false));
 }

 @Test void anAlreadyActiveTrailerIsNeverDeferredEvenIfItsTowingVehicleLosesItsBody() {
  // MINECRAFT-498 self-review regression: an earlier revision applied the ordering wait
  // unconditionally and would deactivate (destroy) an already-active trailer's own body the
  // moment its towing vehicle's body disappeared (opt-out, despawn, chunk unload), instead of
  // leaving the trailer as an unjointed, free-rolling Sable vehicle and only releasing the
  // stale joint (reconcileHitch's job, not active()'s). Still true here: towingVehicleActive is
  // false because there is simply no body, not because of unsafeLock.
  assertFalse(PhysicsBridge.deferToNativeHitch(true, true, false, false));
 }

 @Test void anAlreadyActiveTrailerIsDeferredWhenTheUnsafeAngularLockIsTheReason() {
  // MINECRAFT-545/547 fix (John's comment 54237/54256): unlike "towing vehicle has no body at
  // all" above, an already-active trailer whose towingVehicleActive is false BECAUSE
  // unsafeBuoyantAngularLock refuses this specific pair must defer - that gate's own contract is
  // to fall back to IV's native hitch, not to leave the trailer Sable-authoritative, unjointed,
  // with towedByConnection still pointing at a pair this bridge itself judged unsafe.
  assertTrue(PhysicsBridge.deferToNativeHitch(true, true, false, true));
 }

 @Test void ineligibleAlwaysDecidesIneligibleRegardlessOfOtherFlags() {
  assertEquals(INELIGIBLE, PhysicsBridge.decide(false, false, true, true, true, false));
  assertEquals(INELIGIBLE, PhysicsBridge.decide(false, true, true, true, true, false));
 }

 @Test void clientNeverConsultsTheServerOnlyHitchOrderingWait() {
  // The exact regression external review found: an earlier revision evaluated
  // deferToNativeHitch BEFORE the client branch, so a towed client entity - whose registry
  // lookups (alreadyActive/towingVehicleActive) are permanently false, since the registry is
  // only ever populated by server-side code - deferred to IV's native hitch code forever, even
  // once the server had actually built and jointed its Sable body. decide() must return
  // CLIENT_ACTIVE for any eligible client call, never DEFER_TO_NATIVE, regardless of towed/
  // alreadyActive/towingVehicleActive/unsafeLock.
  assertEquals(CLIENT_ACTIVE, PhysicsBridge.decide(true, true, true, false, false, false));
  assertEquals(CLIENT_ACTIVE, PhysicsBridge.decide(true, true, false, false, false, false));
  assertEquals(CLIENT_ACTIVE, PhysicsBridge.decide(true, true, true, true, false, true));
 }

 @Test void serverDefersToNativeHitchOnlyOnFirstActivationOfATowedVehicle() {
  assertEquals(DEFER_TO_NATIVE, PhysicsBridge.decide(true, false, true, false, false, false));
  assertEquals(ACTIVATE, PhysicsBridge.decide(true, false, true, false, true, false));
  assertEquals(ACTIVATE, PhysicsBridge.decide(true, false, true, true, false, false));
  assertEquals(ACTIVATE, PhysicsBridge.decide(true, false, false, false, false, false));
 }

 @Test void serverDefersAnAlreadyActiveTowedVehicleWhenTheUnsafeAngularLockFires() {
  // The MINECRAFT-545/547 fix end to end through decide(): previously ACTIVATE for this exact
  // combination (alreadyActive=true, towingVehicleActive=false) regardless of why - now
  // DEFER_TO_NATIVE specifically when unsafeLock is the reason.
  assertEquals(DEFER_TO_NATIVE, PhysicsBridge.decide(true, false, true, true, false, true));
 }

 @Test void unjointedUnmountedConnectionsAreCompensable() {
  // MINECRAFT-496's original case, preserved: a trailer still on IV's native hitch code (no
  // Sable body/joint of its own yet) has no other path for its engine thrust to reach the
  // towing vehicle, so it must still be replicated here.
  assertTrue(PhysicsBridge.compensableConnection(false, false));
 }

 @Test void aCoherentlyJointedConnectionIsNeverCompensable() {
  // MINECRAFT-498 self-review regression (second finding): once a towed vehicle has its own
  // Sable body and a valid, coherent hitch joint to this towing vehicle, its engine thrust is
  // applied directly to its own body and already reaches the towing vehicle mechanically through
  // the joint. Compensating for it again here would propel a powered trailer twice. (The
  // "coherentlyJointed" flag itself - a valid handle to exactly the current towing vehicle, not
  // mere joint-map membership - is what HitchRegistry.isCoherentlyJointedTo computes; see
  // HitchRegistryTest.compensableConnectionCompositionExcludesOnlyAValidCurrentPartnerJoint for
  // that real composition, external review comment 52756.)
  assertFalse(PhysicsBridge.compensableConnection(false, true));
 }

 @Test void aMountedConnectionIsNeverCompensableRegardlessOfJointState() {
  // Matches IV's own getRecursiveTowingThrust rule: a mounted connection (e.g. a flatbed's
  // cargo) never contributes towing-thrust compensation, jointed or not.
  assertFalse(PhysicsBridge.compensableConnection(true, false));
  assertFalse(PhysicsBridge.compensableConnection(true, true));
 }

 @Test void anUntowedVehicleNeverDefersRegardlessOfTheOtherFlags() {
  assertFalse(PhysicsBridge.deferToNativeHitch(false, false, false, false));
  assertFalse(PhysicsBridge.deferToNativeHitch(false, true, true, true));
 }

 @Test void mountedOrRestrictedAngularLockIsUnsafeOnlyBetweenTwoBuoyantBodies() {
  // MINECRAFT-545 safety gate, added after BuoyantHitchProbe's own live run confirmed a
  // mounted/restricted angular lock between two independently-buoyant hulls diverges into a
  // runaway crash (see VERIFICATION.md). Only the exact combination lockedAxes would otherwise
  // apply a mounted/restricted lock for, with BOTH active bodies BuoyantBody, is unsafe.
  assertTrue(PhysicsBridge.unsafeBuoyantAngularLock(true, true, true, false));
  assertTrue(PhysicsBridge.unsafeBuoyantAngularLock(true, true, false, true));
 }

 @Test void ordinaryConnectionsBetweenTwoBuoyantBodiesAreNotFlaggedUnsafe() {
  assertFalse(PhysicsBridge.unsafeBuoyantAngularLock(true, true, false, false));
 }

 @Test void anyGroundFlightOrRotorBodyOnEitherSideIsNeverFlaggedUnsafe() {
  // A ground/flight/rotor Body's own suspension/aerodynamics anchors the joint, unlike two
  // free-floating hulls each applying independent righting torque - confirmed safe by this
  // ticket's own established ground-hitch production path (MINECRAFT-525/530), unaffected by
  // this new gate regardless of mounted/restricted.
  assertFalse(PhysicsBridge.unsafeBuoyantAngularLock(false, true, true, false));
  assertFalse(PhysicsBridge.unsafeBuoyantAngularLock(true, false, false, true));
  assertFalse(PhysicsBridge.unsafeBuoyantAngularLock(false, false, true, true));
 }


 @Test void keyAndBrokenKeyCanBeDifferentTypes() {
  // Mirrors PhysicsBridge's real usage: the active-bodies registry is keyed by the live entity,
  // but brokenDefinitions is keyed by the shared JSON definition object - a different key for
  // a different map, on purpose, so one bad content-pack definition is cached once rather than
  // once per entity instance of it.
  var registry = new BodyRegistry<StringBuilder, Object>();
  Set<Integer> broken = Collections.newSetFromMap(new IdentityHashMap<>());
  var entity = new StringBuilder("car-instance");
  PhysicsBridge.activateOrClassify(registry, broken, entity, 42,
    () -> { throw new InvalidChassisGeometry("bad geometry"); });
  assertTrue(broken.contains(42));
  assertFalse(registry.isActive(entity));
 }

 @Test void releasesAnOrphanedConnectionOnlyWhenTowingVehicleIsInvalid() {
  // MINECRAFT-545/547 (John's comment 54656): the exact condition that must fire for
  // cleanup()'s own orphan-release sweep to actually release a stale towedByConnection - a
  // towing vehicle that is still valid (ordinary, still-towing case) must NEVER be released.
  assertTrue(PhysicsBridge.shouldReleaseOrphanedTowingConnection(true, false));
  assertFalse(PhysicsBridge.shouldReleaseOrphanedTowingConnection(true, true));
 }

 @Test void neverReleasesWhenThereIsNoConnectionAtAll() {
  // An untowed vehicle (towedByConnection == null) has nothing to release regardless of the
  // second flag's value - this must stay false, not merely "false because towingVehicleIsValid
  // happened to be true."
  assertFalse(PhysicsBridge.shouldReleaseOrphanedTowingConnection(false, false));
  assertFalse(PhysicsBridge.shouldReleaseOrphanedTowingConnection(false, true));
 }
}
