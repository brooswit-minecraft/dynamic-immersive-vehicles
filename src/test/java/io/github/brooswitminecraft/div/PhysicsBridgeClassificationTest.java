package io.github.brooswitminecraft.div;
import org.junit.jupiter.api.Test;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
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
  assertTrue(PhysicsBridge.deferToNativeHitch(true, false, false));
 }

 @Test void firstActivationProceedsOnceTheTowingVehicleHasABody() {
  assertFalse(PhysicsBridge.deferToNativeHitch(true, false, true));
 }

 @Test void anAlreadyActiveTrailerIsNeverDeferredEvenIfItsTowingVehicleLosesItsBody() {
  // MINECRAFT-498 self-review regression: an earlier revision applied the ordering wait
  // unconditionally and would deactivate (destroy) an already-active trailer's own body the
  // moment its towing vehicle's body disappeared (opt-out, despawn, chunk unload), instead of
  // leaving the trailer as an unjointed, free-rolling Sable vehicle and only releasing the
  // stale joint (reconcileHitch's job, not active()'s).
  assertFalse(PhysicsBridge.deferToNativeHitch(true, true, false));
 }

 @Test void unjointedUnmountedConnectionsAreCompensable() {
  // MINECRAFT-496's original case, preserved: a trailer still on IV's native hitch code (no
  // Sable body/joint of its own yet) has no other path for its engine thrust to reach the
  // towing vehicle, so it must still be replicated here.
  assertTrue(PhysicsBridge.compensableConnection(false, false));
 }

 @Test void aJointedConnectionIsNeverCompensable() {
  // MINECRAFT-498 self-review regression (second finding): once a towed vehicle has its own
  // Sable body and hitch joint, its engine thrust is applied directly to its own body and
  // already reaches the towing vehicle mechanically through the joint. Compensating for it
  // again here would propel a powered trailer twice.
  assertFalse(PhysicsBridge.compensableConnection(false, true));
 }

 @Test void aMountedConnectionIsNeverCompensableRegardlessOfJointState() {
  // Matches IV's own getRecursiveTowingThrust rule: a mounted connection (e.g. a flatbed's
  // cargo) never contributes towing-thrust compensation, jointed or not.
  assertFalse(PhysicsBridge.compensableConnection(true, false));
  assertFalse(PhysicsBridge.compensableConnection(true, true));
 }

 @Test void anUntowedVehicleNeverDefersRegardlessOfTheOtherFlags() {
  assertFalse(PhysicsBridge.deferToNativeHitch(false, false, false));
  assertFalse(PhysicsBridge.deferToNativeHitch(false, true, true));
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
}
