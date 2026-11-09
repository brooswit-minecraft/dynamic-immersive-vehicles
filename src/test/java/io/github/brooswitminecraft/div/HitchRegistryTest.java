package io.github.brooswitminecraft.div;
import dev.ryanhcode.sable.api.physics.constraint.ConstraintJointAxis;
import dev.ryanhcode.sable.api.physics.constraint.GenericConstraintHandle;
import dev.ryanhcode.sable.api.physics.constraint.PhysicsConstraintHandle;
import org.joml.Quaterniondc;
import org.joml.Vector3d;
import org.joml.Vector3dc;
import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Exercises HitchRegistry, the actual production joint-lifecycle bookkeeping
 * PhysicsBridge.reconcileHitch/active()/cleanup()/unload() call, using fake keys and a fake
 * PhysicsConstraintHandle instead of a live entity/world/Sable runtime - the same kind of seam
 * BodyRegistryTest provides for body lifecycle. A plain fake BodyRegistry&lt;String,Object&gt;
 * stands in alongside it in several tests to pin the exact production composition PhysicsBridge
 * relies on: losing a towing vehicle's body must release only the joint, never the trailer's own
 * already-active body - this was a real regression caught by self-review (see
 * PhysicsBridgeClassificationTest.anAlreadyActiveTrailerIsNeverDeferredEvenIfItsTowingVehicleLosesItsBody).
 */
class HitchRegistryTest {
 /**
  * A joint handle real enough to exercise isValid()/remove(), with no live Sable runtime.
  * PhysicsConstraintHandle itself is sealed (only Sable's own subinterfaces may implement it
  * directly); GenericConstraintHandle - the same non-sealed subinterface PhysicsBridge's real
  * hitch joints use - is the one this bridge is actually built from, so faking that interface
  * for this test, not a synthetic subtype of its own, is the honest production shape.
  */
 private static PhysicsConstraintHandle fakeJoint(boolean[] removed) {
  return new GenericConstraintHandle() {
   @Override public void setFrame1(Vector3dc pos, Quaterniondc orientation) {}
   @Override public void setFrame2(Vector3dc pos, Quaterniondc orientation) {}
   @Override public void setLimit(ConstraintJointAxis axis, double lower, double upper) {}
   @Override public void lockAxes(ConstraintJointAxis... axes) {}
   @Override public void getJointImpulses(Vector3d linear, Vector3d angular) {}
   @Override public void setContactsEnabled(boolean enabled) {}
   @Override public void setMotor(ConstraintJointAxis axis, double target, double stiffness, double damping, boolean limited, double maxForce) {}
   @Override public void remove() { removed[0] = true; }
   @Override public boolean isValid() { return !removed[0]; }
  };
 }

 @Test void reconcileLeavesNoPartialStateWhenCreateThrowsAndCanRetryAfter() {
  // MINECRAFT-498 external review regression: PhysicsBridge.reconcileHitch calls this method
  // directly from active()'s own call path with no failure handling of its own; this registry's
  // own contract is what makes PhysicsBridge's catch-and-retry-next-tick safe - a failed create
  // must leave neither a joint nor a recorded partner behind (the same "untouched on failure"
  // guarantee BodyRegistry.activateOnce already provides for body construction), and the
  // exception itself propagates to the caller rather than being swallowed here.
  var hitches = new HitchRegistry<String>();
  assertThrows(RuntimeException.class, () -> hitches.reconcile("trailer", "truck",
    () -> { throw new RuntimeException("constraint anchor/configuration invalid this tick"); }));
  assertFalse(hitches.isJointed("trailer"));
  assertNull(hitches.partnerOf("trailer"));
  boolean[] removed = {false};
  hitches.reconcile("trailer", "truck", () -> fakeJoint(removed));
  assertTrue(hitches.isJointed("trailer"), "a later call with a working constructor must still succeed");
 }

 @Test void reconcileCreatesExactlyOneJointForActiveTowingAndTrailerKeys() {
  var hitches = new HitchRegistry<String>();
  int[] constructions = {0};
  hitches.reconcile("trailer", "truck", () -> { constructions[0]++; return fakeJoint(new boolean[1]); });
  assertTrue(hitches.isJointed("trailer"));
  assertEquals("truck", hitches.partnerOf("trailer"));
  assertEquals(1, constructions[0]);
  // Reconciling again against the same, still-valid partner must not construct a second joint.
  hitches.reconcile("trailer", "truck", () -> { constructions[0]++; return fakeJoint(new boolean[1]); });
  assertEquals(1, constructions[0], "already correctly jointed - must not construct again");
 }

 @Test void reconcileToNoDesiredPartnerReleasesWithoutConstructing() {
  var hitches = new HitchRegistry<String>();
  boolean[] removed = {false};
  hitches.reconcile("trailer", "truck", () -> fakeJoint(removed));
  hitches.reconcile("trailer", null, () -> fail("must not construct a new joint when releasing"));
  assertTrue(removed[0]);
  assertFalse(hitches.isJointed("trailer"));
 }

 @Test void losingTheTowingVehicleReleasesExactlyTheJointNotTheTrailerBody() {
  // MINECRAFT-498 self-review regression, pinned at the production-composition level: a
  // separate fake body registry stands in for the real Body lifecycle, proving
  // HitchRegistry.reconcile(trailer, null, ...) never touches it - it has no way to.
  var hitches = new HitchRegistry<String>();
  var bodies = new BodyRegistry<String, Object>();
  bodies.activate("trailer", new Object());
  bodies.activate("truck", new Object());
  boolean[] removed = {false};
  hitches.reconcile("trailer", "truck", () -> fakeJoint(removed));
  assertTrue(hitches.isJointed("trailer"));

  // The towing vehicle loses its own body (opt-out/despawn/chunk-unload): PhysicsBridge calls
  // hitches.release("truck") for that removal, and separately reconciles the trailer against a
  // now-null desired partner on its own next active() call.
  bodies.deactivate("truck");
  hitches.release("truck");
  hitches.reconcile("trailer", null, () -> fail("must not construct a new joint when releasing"));

  assertTrue(removed[0], "the stale joint must actually be removed");
  assertFalse(hitches.isJointed("trailer"));
  assertTrue(bodies.isActive("trailer"), "the trailer's own body must survive losing its towing partner");
 }

 @Test void releaseAlsoDropsJointsHeldByTrailersPointedAtThisKey() {
  var hitches = new HitchRegistry<String>();
  boolean[] removed = {false};
  hitches.reconcile("trailer", "truck", () -> fakeJoint(removed));
  hitches.release("truck");
  assertTrue(removed[0]);
  assertFalse(hitches.isJointed("trailer"));
 }

 @Test void releasingATrailersOwnKeyDropsItsOwnJoint() {
  var hitches = new HitchRegistry<String>();
  boolean[] removed = {false};
  hitches.reconcile("trailer", "truck", () -> fakeJoint(removed));
  hitches.release("trailer");
  assertTrue(removed[0]);
  assertFalse(hitches.isJointed("trailer"));
 }

 @Test void releasingAKeyWithNoJointInEitherRoleIsANoOp() {
  var hitches = new HitchRegistry<String>();
  assertDoesNotThrow(() -> hitches.release("nothing-here"));
 }

 @Test void reconcileRebuildsAnInvalidatedJointEvenForTheSamePartner() {
  var hitches = new HitchRegistry<String>();
  boolean[] removedFirst = {false};
  hitches.reconcile("trailer", "truck", () -> fakeJoint(removedFirst));
  removedFirst[0] = true; // the underlying constraint went invalid out-of-band
  int[] constructions = {0};
  hitches.reconcile("trailer", "truck", () -> { constructions[0]++; return fakeJoint(new boolean[1]); });
  assertEquals(1, constructions[0], "an invalid existing joint must be rebuilt even for the same partner");
 }

 @Test void switchingToADifferentTowingVehicleReleasesTheOldJointFirst() {
  var hitches = new HitchRegistry<String>();
  boolean[] removedOld = {false};
  hitches.reconcile("trailer", "truck-1", () -> fakeJoint(removedOld));
  hitches.reconcile("trailer", "truck-2", () -> fakeJoint(new boolean[1]));
  assertTrue(removedOld[0]);
  assertEquals("truck-2", hitches.partnerOf("trailer"));
 }

 @Test void bulkSweepReleasesJointsBeforeBodiesWithNoLeak() {
  // Mirrors PhysicsBridge.cleanup()/unload(): hitches.release(key) is called before the body
  // itself is removed, for every key the bulk sweep is removing - the exact call order the
  // production BiConsumer passed to BodyRegistry.removeIf uses.
  var hitches = new HitchRegistry<String>();
  var bodies = new BodyRegistry<String, Object>();
  bodies.activate("trailer", new Object());
  bodies.activate("truck", new Object());
  boolean[] removed = {false};
  hitches.reconcile("trailer", "truck", () -> fakeJoint(removed));
  List<String> destroyedOrder = new ArrayList<>();
  bodies.removeIf((k, b) -> true, (k, b) -> { hitches.release(k); destroyedOrder.add(k); });
  assertTrue(removed[0], "the joint must be removed as part of the bulk sweep");
  assertEquals(List.of("trailer", "truck"), destroyedOrder.stream().sorted().toList());
  assertFalse(bodies.isActive("trailer"));
  assertFalse(bodies.isActive("truck"));
 }
}
