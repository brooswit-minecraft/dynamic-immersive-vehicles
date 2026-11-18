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
import java.util.function.BooleanSupplier;
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

 @Test void reconcileTreatsANullCreatedHandleAsAFailedCreateNotAHealthyJoint() {
  // Self-review kickback (comment 52759, head 8515052): a null result from create() is a
  // different failure shape than a thrown exception, but must be treated identically - the
  // no-op condition's own existing == null check (needed to tell "never jointed" apart from "an
  // existing handle has since gone invalid") cannot also distinguish a key genuinely absent from
  // the map from one explicitly mapped to a stored null handle. Left unhandled, a null create()
  // result would be recorded as this trailer's partner forever, and every later reconcile call
  // for the SAME desired partner would then silently no-op as if already healthily jointed -
  // active() stays true (suppressing IV's native hitch authority) while no Sable constraint
  // actually exists, with no retry ever attempted again.
  var hitches = new HitchRegistry<String>();
  assertThrows(RuntimeException.class, () -> hitches.reconcile("trailer", "truck", () -> null));
  assertFalse(hitches.isJointed("trailer"), "a null handle must not be recorded as a joint");
  assertNull(hitches.partnerOf("trailer"));
  assertFalse(hitches.isCoherentlyJointedTo("trailer", "truck"));

  // A later call for the SAME desired partner must actually retry create() again, not silently
  // no-op against the (absent, since nothing was stored) prior attempt.
  boolean[] removed = {false};
  hitches.reconcile("trailer", "truck", () -> fakeJoint(removed));
  assertTrue(hitches.isJointed("trailer"), "a later call with a working constructor must still succeed");
  assertTrue(hitches.isCoherentlyJointedTo("trailer", "truck"));
 }

 /**
  * These exercise PhysicsBridge.reconcileOrRollBack(...) directly - the exact production
  * composition active() calls, with a real BodyRegistry/HitchRegistry and fake body/removal
  * callbacks - not a reimplementation of its exception-to-boolean/rollback logic inline. This is
  * the production-used integration-seam regression external review's second round explicitly
  * required: the prior version of these tests drove HitchRegistry.reconcile and
  * PhysicsBridge.rollBackOnHitchFailure separately and glued them together by hand, which stayed
  * green even if active() itself stopped calling either one correctly.
  *
  * <p>The deciding fact passed as the third argument is {@code hitches.isJointed(key)} evaluated
  * BEFORE this attempt - whether the trailer was already coherently jointed, not whether its body
  * merely already existed (a self-review finding, MINECRAFT-498 post-integration: an earlier
  * revision passed {@code alreadyActive} here instead, which wrongly preserved an active-but-
  * never-jointed body on its first failed hitch attempt - see
  * reconcileOrRollBackRollsBackAnActiveButNeverJointedBodyOnFirstHitchFailure below).
  */
 @Test void reconcileOrRollBackRollsBackANewlyActivatedBodyOnFirstFailureWithExactlyOneRemoval() {
  var bodies = new BodyRegistry<String, Object>();
  var hitches = new HitchRegistry<String>();
  Object body = new Object();
  bodies.activate("trailer", body); // simulates activateOrClassify having just constructed it this call
  boolean[] jointRemoved = {false};
  hitches.reconcile("trailer", "truck", () -> fakeJoint(jointRemoved)); // a stale joint must also be released on rollback
  List<Object> removed = new ArrayList<>();

  boolean result = PhysicsBridge.reconcileOrRollBack(bodies, hitches, "trailer", false, () -> false, removed::add);

  assertFalse(result, "must report false - IV's native hitch motion is authoritative again this tick");
  assertFalse(bodies.isActive("trailer"), "the just-created body must be rolled back, not left dangling unjointed");
  assertFalse(hitches.isJointed("trailer"), "the hitch-registry entry must also be released");
  assertEquals(List.of(body), removed, "exactly one actual body-removal callback, with the real body - no leak, no double-removal");
 }

 @Test void reconcileOrRollBackRollsBackAnActiveButNeverJointedBodyOnFirstHitchFailure() {
  // The exact gap external review's self-review kickback found (comment 52740, head f1330c1):
  // a body that already existed before this call - e.g. it was already Sable-active and untowed
  // on a previous tick - but was never jointed to anything, now being hitched for the first
  // time. If that first hitch attempt fails, the old alreadyActive-keyed check wrongly preserved
  // this body: active() still returns true (IV's native hitch-following stays suppressed for
  // it), but no joint exists either, so nothing at all is coupling or moving this trailer -
  // worse than either authority alone. The body must be rolled back exactly as a brand-new one
  // would be, so IV's native hitch motion becomes authoritative again this tick.
  var bodies = new BodyRegistry<String, Object>();
  var hitches = new HitchRegistry<String>();
  Object body = new Object();
  bodies.activate("trailer", body); // simulates a body that already existed before this tick
  List<Object> removed = new ArrayList<>();

  boolean result = PhysicsBridge.reconcileOrRollBack(bodies, hitches, "trailer", hitches.isCoherentlyJointedTo("trailer", "truck"), () -> false, removed::add);

  assertFalse(result, "an active-but-never-jointed body must roll back on its first failed hitch attempt, same as a brand-new one");
  assertFalse(bodies.isActive("trailer"), "the pre-existing but never-coupled body must not be left stranded unjointed");
  assertEquals(List.of(body), removed);
 }

 @Test void reconcileOrRollBackPreservesACoherentlyJointedBodyOnTransientFailure() {
  // The other half of the same production decision: a trailer already coherently jointed - a
  // valid handle to exactly its current desired partner - before this call (healthy, from a
  // previous tick) must never be rolled back merely because a later reconcile attempt fails -
  // the same non-destructive guarantee deferToNativeHitch already provides when a towing
  // vehicle's body disappears. Only the stale/failed joint is retried; the body itself survives.
  // (Note: in real production use, HitchRegistry.reconcile would itself never even attempt - let
  // alone fail - a reconcile call while already coherently jointed to the same desired partner;
  // this test exercises reconcileOrRollBack's own documented contract directly, as a pure seam,
  // independent of whether the real HitchRegistry could ever actually reach this exact input.)
  var bodies = new BodyRegistry<String, Object>();
  var hitches = new HitchRegistry<String>();
  Object body = new Object();
  bodies.activate("trailer", body);
  boolean[] jointRemoved = {false};
  hitches.reconcile("trailer", "truck", () -> fakeJoint(jointRemoved)); // establishes the prior healthy joint
  List<Object> removed = new ArrayList<>();

  boolean result = PhysicsBridge.reconcileOrRollBack(bodies, hitches, "trailer", hitches.isCoherentlyJointedTo("trailer", "truck"), () -> false, removed::add);

  assertTrue(result, "a coherently-jointed body must stay Sable-active despite the failed reconcile");
  assertTrue(bodies.isActive("trailer"));
  assertSame(body, bodies.get("trailer"));
  assertTrue(removed.isEmpty(), "no removal callback for a previously-jointed body");
 }

 @Test void reconcileOrRollBackRollsBackOnFailedRebuildOfAnInvalidExistingJoint() {
  // The exact gap the second self-review kickback found (comment 52750, head 41e8242):
  // HitchRegistry.reconcile tears down an existing joint whose handle has gone invalid before
  // even attempting its replacement - so by the time that replacement fails, the trailer has no
  // joint at all, not the "already healthy" state plain isJointed map membership would have
  // reported. wasJointed must come from isCoherentlyJointedTo (valid handle, correct partner),
  // evaluated BEFORE this attempt, so this case rolls back exactly like a never-jointed body.
  var bodies = new BodyRegistry<String, Object>();
  var hitches = new HitchRegistry<String>();
  Object body = new Object();
  bodies.activate("trailer", body);
  boolean[] staleRemoved = {false};
  hitches.reconcile("trailer", "truck", () -> fakeJoint(staleRemoved));
  staleRemoved[0] = true; // the underlying constraint went invalid out-of-band
  boolean wasJointed = hitches.isCoherentlyJointedTo("trailer", "truck");
  assertFalse(wasJointed, "an invalid handle is not a coherent joint, even to the correct partner");
  List<Object> removed = new ArrayList<>();
  BooleanSupplier reconcile = () -> {
   try {
    hitches.reconcile("trailer", "truck", () -> { throw new RuntimeException("rebuild failed this tick"); });
    return true;
   } catch (RuntimeException transientFailure) {
    return false;
   }
  };

  boolean result = PhysicsBridge.reconcileOrRollBack(bodies, hitches, "trailer", wasJointed, reconcile, removed::add);

  assertFalse(result, "a failed rebuild of an invalidated joint must roll back - IV's native hitch motion becomes authoritative again");
  assertFalse(bodies.isActive("trailer"));
  assertFalse(hitches.isJointed("trailer"), "the destroyed stale entry must not be left behind either");
  assertEquals(List.of(body), removed);
 }

 @Test void reconcileOrRollBackRollsBackOnFailedSwitchFromAMismatchedExistingJoint() {
  // Same gap, the switch-partner shape: HitchRegistry.reconcile releases the OLD, still-valid
  // joint before attempting the new partner, since the partner no longer matches desired - a
  // failed attach to the new partner must not leave the trailer thinking it is still coupled to
  // the old one.
  var bodies = new BodyRegistry<String, Object>();
  var hitches = new HitchRegistry<String>();
  Object body = new Object();
  bodies.activate("trailer", body);
  hitches.reconcile("trailer", "truck-1", () -> fakeJoint(new boolean[1]));
  boolean wasJointed = hitches.isCoherentlyJointedTo("trailer", "truck-2"); // the NEW desired partner
  assertFalse(wasJointed, "a joint to a different partner is not coherently jointed to the new desired one");
  List<Object> removed = new ArrayList<>();
  BooleanSupplier reconcile = () -> {
   try {
    hitches.reconcile("trailer", "truck-2", () -> { throw new RuntimeException("attach failed this tick"); });
    return true;
   } catch (RuntimeException transientFailure) {
    return false;
   }
  };

  boolean result = PhysicsBridge.reconcileOrRollBack(bodies, hitches, "trailer", wasJointed, reconcile, removed::add);

  assertFalse(result);
  assertFalse(bodies.isActive("trailer"));
  assertFalse(hitches.isJointed("trailer"));
  assertEquals(List.of(body), removed);
 }

 @Test void retryAfterARolledBackInvalidJointRebuildSucceedsCleanly() {
  var bodies = new BodyRegistry<String, Object>();
  var hitches = new HitchRegistry<String>();
  bodies.activate("trailer", new Object());
  boolean[] staleRemoved = {false};
  hitches.reconcile("trailer", "truck", () -> fakeJoint(staleRemoved));
  staleRemoved[0] = true;
  List<Object> removed = new ArrayList<>();
  BooleanSupplier failingReconcile = () -> {
   try {
    hitches.reconcile("trailer", "truck", () -> { throw new RuntimeException("rebuild failed this tick"); });
    return true;
   } catch (RuntimeException transientFailure) {
    return false;
   }
  };
  PhysicsBridge.reconcileOrRollBack(bodies, hitches, "trailer", hitches.isCoherentlyJointedTo("trailer", "truck"), failingReconcile, removed::add);
  assertFalse(bodies.isActive("trailer"));

  // Next tick: a fresh first activation (still never coherently jointed) with a working rebuild.
  Object retryBody = new Object();
  bodies.activate("trailer", retryBody);
  BooleanSupplier workingReconcile = () -> {
   hitches.reconcile("trailer", "truck", () -> fakeJoint(new boolean[1]));
   return true;
  };
  boolean result = PhysicsBridge.reconcileOrRollBack(bodies, hitches, "trailer", hitches.isCoherentlyJointedTo("trailer", "truck"), workingReconcile, removed::add);
  assertTrue(result);
  assertTrue(bodies.isActive("trailer"));
  assertSame(retryBody, bodies.get("trailer"));
  assertTrue(hitches.isJointed("trailer"));
 }

 @Test void isCoherentlyJointedToDistinguishesValidityAndPartnerFromMereMembership() {
  var hitches = new HitchRegistry<String>();
  assertFalse(hitches.isCoherentlyJointedTo("trailer", "truck"), "never jointed at all");

  boolean[] removed = {false};
  hitches.reconcile("trailer", "truck", () -> fakeJoint(removed));
  assertTrue(hitches.isCoherentlyJointedTo("trailer", "truck"), "valid handle, matching partner");
  assertFalse(hitches.isCoherentlyJointedTo("trailer", "truck-2"), "valid handle, but wrong desired partner");

  removed[0] = true; // the underlying constraint went invalid out-of-band
  assertFalse(hitches.isCoherentlyJointedTo("trailer", "truck"), "same partner, but handle now invalid");
  assertTrue(hitches.isJointed("trailer"), "mere map membership still reports true - the exact distinction this method exists for");
 }

 @Test void reconcileOrRollBackSucceedsWithoutRemovalWhenReconcileSucceeds() {
  var bodies = new BodyRegistry<String, Object>();
  var hitches = new HitchRegistry<String>();
  bodies.activate("trailer", new Object());
  List<Object> removed = new ArrayList<>();

  boolean result = PhysicsBridge.reconcileOrRollBack(bodies, hitches, "trailer", false, () -> true, removed::add);

  assertTrue(result);
  assertTrue(bodies.isActive("trailer"));
  assertTrue(removed.isEmpty());
 }

 @Test void retryAfterARolledBackFirstActivationSucceedsCleanly() {
  var bodies = new BodyRegistry<String, Object>();
  var hitches = new HitchRegistry<String>();
  bodies.activate("trailer", new Object());
  List<Object> removed = new ArrayList<>();
  PhysicsBridge.reconcileOrRollBack(bodies, hitches, "trailer", false, () -> false, removed::add);
  assertFalse(bodies.isActive("trailer"));

  // Next tick: a fresh first activation (still never jointed) with a working reconcile.
  Object retryBody = new Object();
  bodies.activate("trailer", retryBody);
  boolean result = PhysicsBridge.reconcileOrRollBack(bodies, hitches, "trailer", false, () -> true, removed::add);
  assertTrue(result);
  assertTrue(bodies.isActive("trailer"));
  assertSame(retryBody, bodies.get("trailer"));
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

 /** A joint whose own remove() throws, standing in for a real Sable-level removal failure -
  * same fakeJoint shape, deliberately misbehaving on this one call. */
 private static PhysicsConstraintHandle fakeJointThatThrowsOnRemove() {
  return new GenericConstraintHandle() {
   @Override public void setFrame1(Vector3dc pos, Quaterniondc orientation) {}
   @Override public void setFrame2(Vector3dc pos, Quaterniondc orientation) {}
   @Override public void setLimit(ConstraintJointAxis axis, double lower, double upper) {}
   @Override public void lockAxes(ConstraintJointAxis... axes) {}
   @Override public void getJointImpulses(Vector3d linear, Vector3d angular) {}
   @Override public void setContactsEnabled(boolean enabled) {}
   @Override public void setMotor(ConstraintJointAxis axis, double target, double stiffness, double damping, boolean limited, double maxForce) {}
   @Override public void remove() { throw new RuntimeException("Sable-level removal failure"); }
   @Override public boolean isValid() { return true; }
  };
 }

 @Test void aThrowingOwnJointRemovalStillClearsPartnerMapAndReleasesOtherTrailers() {
  // MINECRAFT-545/547 (John's comment 55104, confirmed by direct source+bytecode reading): the
  // ORIGINAL release(key) called own.remove() BEFORE partners.remove(key) and BEFORE the sweep
  // releasing every OTHER trailer jointed TO key - a throwing own.remove() skipped ALL of that
  // remaining, otherwise-independent cleanup. "truck" here is BOTH a trailer (jointed to
  // "flatbed") AND a towing vehicle (towing "car") - exactly the combined dead-anchor scenario
  // 55103 asked for: releasing truck must release its own INCOMING joint (to flatbed) and its
  // OUTGOING joint (car's, to truck), even though truck's own joint-removal itself throws.
  var hitches = new HitchRegistry<String>();
  hitches.reconcile("truck", "flatbed", () -> fakeJointThatThrowsOnRemove()); // truck's own incoming joint - throws on remove
  boolean[] carJointRemoved = {false};
  hitches.reconcile("car", "truck", () -> fakeJoint(carJointRemoved)); // car's outgoing joint, pointed at truck
  assertThrows(RuntimeException.class, () -> hitches.release("truck"), "the throw itself must still propagate, not be swallowed");
  assertNull(hitches.partnerOf("truck"), "truck's own partner entry must still be cleared despite its own joint's throw");
  assertTrue(carJointRemoved[0], "car's own joint (an UNRELATED release, pointed at truck) must still be released despite truck's own joint throwing first");
  assertFalse(hitches.isJointed("car"), "car must no longer be jointed after its own joint was released");
 }

 @Test void aThrowingPartneredTrailerReleaseStillReleasesOtherPartneredTrailers() {
  // The plural case: TWO different trailers both jointed to the SAME towing vehicle; one's own
  // joint throws on remove(), the other's does not - releasing the towing vehicle must still
  // release the SECOND trailer's joint, not stop after the first one throws.
  var hitches = new HitchRegistry<String>();
  hitches.reconcile("trailer-a", "truck", () -> fakeJointThatThrowsOnRemove());
  boolean[] trailerBRemoved = {false};
  hitches.reconcile("trailer-b", "truck", () -> fakeJoint(trailerBRemoved));
  assertThrows(RuntimeException.class, () -> hitches.release("truck"));
  assertTrue(trailerBRemoved[0], "trailer-b's own joint must still be released even though trailer-a's own release threw first");
  assertFalse(hitches.isJointed("trailer-b"));
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

 @Test void compensableConnectionCompositionExcludesOnlyAValidCurrentPartnerJoint() {
  // External review finding (comment 52756, head 6f4abea): PhysicsBridge.unjointedTowingThrust
  // used hitches.isJointed(towed) for its double-count exclusion - the same plain-map-membership
  // gap reconcileOrRollBack had, and not even scoped to the CURRENT towing vehicle. No valid
  // Sable constraint transmits a trailer's thrust to a towing vehicle it either has no valid
  // handle to, or is jointed to a DIFFERENT towing vehicle than the one asking - both cases must
  // still contribute to compensation, exactly once, only the valid-current-partner case must not.
  var hitches = new HitchRegistry<String>();
  boolean[] removed = {false};
  hitches.reconcile("trailer", "truck", () -> fakeJoint(removed));

  assertFalse(PhysicsBridge.compensableConnection(false, hitches.isCoherentlyJointedTo("trailer", "truck")),
    "a valid joint to exactly the current towing vehicle already reaches it mechanically - must be excluded");
  assertTrue(PhysicsBridge.compensableConnection(false, hitches.isCoherentlyJointedTo("trailer", "another-truck")),
    "jointed to a DIFFERENT towing vehicle - this one still gets no mechanical thrust, must still contribute");

  removed[0] = true; // the underlying constraint went invalid out-of-band
  assertTrue(PhysicsBridge.compensableConnection(false, hitches.isCoherentlyJointedTo("trailer", "truck")),
    "an invalid handle transmits no force at all, even to its own recorded partner - must still contribute");
 }
}
