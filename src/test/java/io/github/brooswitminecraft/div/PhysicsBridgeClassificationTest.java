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

 @Test void releasesAnOrphanedConnectionOnlyWhenTowingEntityIsInvalid() {
  // MINECRAFT-545/547 (John's comments 54656/54659): the exact condition that must fire for
  // cleanup()'s own orphan-release sweep to actually release a stale towedByConnection -
  // CORRECTED (54659): this is towingEntity's own validity (the real anchor - vehicle OR
  // installed part), NOT towingVehicle's. A still-valid anchor (ordinary, still-towing case)
  // must NEVER be released.
  assertTrue(PhysicsBridge.shouldReleaseOrphanedTowingConnection(true, false));
  assertFalse(PhysicsBridge.shouldReleaseOrphanedTowingConnection(true, true));
 }

 @Test void neverReleasesWhenThereIsNoConnectionAtAll() {
  // An untowed vehicle (towedByConnection == null) has nothing to release regardless of the
  // second flag's value - this must stay false, not merely "false because towingEntityIsValid
  // happened to be true."
  assertFalse(PhysicsBridge.shouldReleaseOrphanedTowingConnection(false, false));
  assertFalse(PhysicsBridge.shouldReleaseOrphanedTowingConnection(false, true));
 }

 @Test void canOnlySafelyDisconnectAtANonNegativeIndex() {
  // MINECRAFT-545/547 (John's comment 54659): the exact boundary cleanup()'s own real-
  // disconnectTrailer call site branches on - List.remove(int) throws for a negative index,
  // and indexOf returns -1 when the connection is not actually present in the parent's own
  // towingConnections list (the one case this fix falls back to a direct child-pointer null
  // instead of the real method, since the real method cannot be safely invoked without a valid
  // index).
  assertFalse(PhysicsBridge.canSafelyDisconnectAtIndex(-1));
  assertTrue(PhysicsBridge.canSafelyDisconnectAtIndex(0));
  assertTrue(PhysicsBridge.canSafelyDisconnectAtIndex(3));
 }

 /** MINECRAFT-545/547 (John's comment 54662: "extract only the small production-used
  * orchestration seam... with lightweight callbacks/test fixtures - no duplicate test-only
  * algorithm"). A minimal fake over the exact three mutable facts
  * {@code PhysicsBridge.releaseOrphanedConnections} itself reads/writes through its
  * {@code OrphanedConnectionOps<V>} seam - no entity/connection types, this IS the real
  * orchestration cleanup() calls, exercised through fakes instead of live MTS objects. */
 private static final class FakeOrphanCandidate {
  boolean hasConnection;
  boolean anchorValid;
  int indexInParent;
  int disconnectCalls = 0, fallbackCalls = 0;
  int lastReceivedIndex = Integer.MIN_VALUE;
  int dispatchCount = 0; // 54742: simulated per-tick entity-dispatch counter, for the Pre/Post timing-model tests below
  Object connectionId = new Object(); // 54782: identity token for whatever connection this candidate currently "has" - a fresh distinct object per candidate by default, reassignable to simulate a changed/replaced connection
  FakeOrphanCandidate(boolean hasConnection, boolean anchorValid, int indexInParent) {
   this.hasConnection = hasConnection; this.anchorValid = anchorValid; this.indexInParent = indexInParent;
  }
 }
 private static PhysicsBridge.OrphanedConnectionOps<FakeOrphanCandidate> fakeOps() {
  return new PhysicsBridge.OrphanedConnectionOps<FakeOrphanCandidate>() {
   public boolean hasConnection(FakeOrphanCandidate v) { return v.hasConnection; }
   public boolean anchorIsValid(FakeOrphanCandidate v) { return v.anchorValid; }
   public Object connectionIdentity(FakeOrphanCandidate v) { return v.connectionId; }
   public int indexInParentList(FakeOrphanCandidate v) { return v.indexInParent; }
   public void disconnectAtIndex(FakeOrphanCandidate v, int index) {
    v.disconnectCalls++; v.lastReceivedIndex = index; v.hasConnection = false; // mirrors the real disconnectTrailer's own towedByConnection=null effect
   }
   public void fallbackClearConnection(FakeOrphanCandidate v) {
    v.fallbackCalls++; v.hasConnection = false;
   }
  };
 }

 @Test void eligibleInvalidAnchorConnectionInvokesDisconnectExactlyOnceAtItsRealIndex() {
  // MINECRAFT-545/547 (John's comment 54665): the earlier version of this test only counted
  // calls, never asserting the ACTUAL index value received - a coverage gap, not evidence the
  // real index was ever passed through correctly. Now asserted directly.
  var v = new FakeOrphanCandidate(true, false, 2);
  PhysicsBridge.releaseOrphanedConnections(java.util.List.of(v), fakeOps());
  assertEquals(1, v.disconnectCalls);
  assertEquals(2, v.lastReceivedIndex, "disconnectAtIndex must receive this candidate's own real indexInParentList() value");
  assertEquals(0, v.fallbackCalls);
 }

 @Test void validOrMissingConnectionIsNeverTouched() {
  var validConnection = new FakeOrphanCandidate(true, true, 0);
  var noConnection = new FakeOrphanCandidate(false, false, 0);
  PhysicsBridge.releaseOrphanedConnections(java.util.List.of(validConnection, noConnection), fakeOps());
  assertEquals(0, validConnection.disconnectCalls);
  assertEquals(0, noConnection.disconnectCalls);
  assertEquals(0, validConnection.fallbackCalls);
  assertEquals(0, noConnection.fallbackCalls);
 }

 @Test void repeatedSweepIsIdempotent() {
  // The real disconnectAtIndex/fallbackClearConnection both clear hasConnection (mirroring
  // disconnectTrailer's own towedByConnection=null effect) - a second sweep over the SAME
  // candidate list must find nothing left eligible.
  var v = new FakeOrphanCandidate(true, false, 0);
  var ops = fakeOps();
  PhysicsBridge.releaseOrphanedConnections(java.util.List.of(v), ops);
  PhysicsBridge.releaseOrphanedConnections(java.util.List.of(v), ops);
  assertEquals(1, v.disconnectCalls, "a second sweep must not disconnect an already-released vehicle again");
 }

 @Test void missingIndexNeverInvokesDisconnectAndUsesTheHonestlyReducedFallback() {
  var v = new FakeOrphanCandidate(true, false, -1);
  PhysicsBridge.releaseOrphanedConnections(java.util.List.of(v), fakeOps());
  assertEquals(0, v.disconnectCalls, "disconnectAtIndex must never be called with a negative index");
  assertEquals(1, v.fallbackCalls);
 }

 @Test void aCandidateWhoseAnchorBecomesValidBeforeDispatchIsNeverDisconnected() {
  // MINECRAFT-545/547 (John's comments 54662/54665): the dispatch phase re-checks CURRENT
  // eligibility (hasConnection/anchorIsValid), fresh, immediately before acting on each
  // candidate. Simulated here by flipping anchorValid to true between collection and dispatch
  // (standing in for some other effect making the connection valid again by the time dispatch
  // actually reaches it) - a now-valid connection must be skipped. Identity is UNCHANGED here
  // (same connectionId throughout) - this test is about the eligibility re-check, not identity.
  var v = new FakeOrphanCandidate(true, false, 0);
  var ops = new PhysicsBridge.OrphanedConnectionOps<FakeOrphanCandidate>() {
   boolean firstRead = true;
   public boolean hasConnection(FakeOrphanCandidate c) { return c.hasConnection; }
   public boolean anchorIsValid(FakeOrphanCandidate c) {
    // False on the phase-1 collection read (so it's collected as a candidate), true on every
    // subsequent read (so the phase-2 re-check must see it as no-longer-eligible).
    if (firstRead) { firstRead = false; return false; }
    return true;
   }
   public Object connectionIdentity(FakeOrphanCandidate c) { return c.connectionId; }
   public int indexInParentList(FakeOrphanCandidate c) { return c.indexInParent; }
   public void disconnectAtIndex(FakeOrphanCandidate c, int index) { c.disconnectCalls++; }
   public void fallbackClearConnection(FakeOrphanCandidate c) { c.fallbackCalls++; }
  };
  PhysicsBridge.releaseOrphanedConnections(java.util.List.of(v), ops);
  assertEquals(0, v.disconnectCalls, "a connection that became valid again before dispatch must never be disconnected");
  assertEquals(0, v.fallbackCalls);
 }

 @Test void aCandidateWhoseConnectionChangedToADifferentInvalidOneIsSkippedNotDisconnected() {
  // MINECRAFT-545/547 (John's comment 54782 - REVERSES the prior 54665-era test of the same
  // shape, which wrongly asserted the opposite). Teardown now targets ONLY the EXACT connection
  // object collected in phase 1 - a candidate whose connection changed to a DIFFERENT one, even
  // if that new one is ALSO currently invalid (e.g. a brand-new intended replacement connection
  // that is merely transiently invalid during its own setup window - exactly
  // TOWING_REPLACEMENT's own shape), must be SKIPPED, never disconnected. Simulated by
  // returning a genuinely different identity object on the dispatch-time read.
  var v = new FakeOrphanCandidate(true, false, 0);
  Object originalIdentity = v.connectionId;
  Object newConnectionIdentity = new Object(); // deliberately a DIFFERENT object than v.connectionId
  var ops = new PhysicsBridge.OrphanedConnectionOps<FakeOrphanCandidate>() {
   boolean firstRead = true;
   public boolean hasConnection(FakeOrphanCandidate c) { return c.hasConnection; }
   public boolean anchorIsValid(FakeOrphanCandidate c) { return c.anchorValid; } // false throughout
   public Object connectionIdentity(FakeOrphanCandidate c) {
    // The ORIGINAL identity on the phase-1 collection read, the CHANGED (different) one on
    // every subsequent (phase-2 dispatch-time) read - simulating the connection having been
    // replaced between collection and dispatch.
    if (firstRead) { firstRead = false; return originalIdentity; }
    return newConnectionIdentity;
   }
   public int indexInParentList(FakeOrphanCandidate c) { return 5; } // the changed connection's own real index - must never actually be used
   public void disconnectAtIndex(FakeOrphanCandidate c, int index) { c.disconnectCalls++; c.lastReceivedIndex = index; c.hasConnection = false; }
   public void fallbackClearConnection(FakeOrphanCandidate c) { c.fallbackCalls++; }
  };
  PhysicsBridge.releaseOrphanedConnections(java.util.List.of(v), ops);
  assertEquals(0, v.disconnectCalls, "a connection that changed identity between collection and dispatch must be skipped, never disconnected - even if the new one is also currently invalid");
  assertEquals(0, v.fallbackCalls);
 }

 /** A deliberately value-equal-but-distinct-instance token, for proving the identity check uses
  * {@code ==} and not {@code equals} (John's comment 54790). */
 private static final class EqualValueToken {
  final int value;
  EqualValueToken(int value) { this.value = value; }
  @Override public boolean equals(Object o) { return o instanceof EqualValueToken t && t.value == value; }
  @Override public int hashCode() { return value; }
 }

 @Test void aCandidateWhoseConnectionChangedToADistinctButEqualValuedTokenIsSkippedNotDisconnected() {
  // MINECRAFT-545/547 (John's comment 54790): a prior version of this check used
  // java.util.Objects.equals, which treats two DISTINCT objects as "the same identity" whenever
  // they happen to compare equal-by-value - not what an identity-preserving contract means, and
  // this test is the one the prior suite was missing: two deliberately different EqualValueToken
  // instances that compare equal() but are not the same reference. The check must still treat
  // this as a CHANGED identity and skip the candidate - proving == is actually used, not equals.
  var v = new FakeOrphanCandidate(true, false, 0);
  var originalIdentity = new EqualValueToken(7);
  var differentInstanceSameValue = new EqualValueToken(7); // equals() true, but a genuinely different object
  assertEquals(originalIdentity, differentInstanceSameValue, "sanity check: these two instances must compare equal() while being distinct references");
  assertTrue(originalIdentity != differentInstanceSameValue, "sanity check: these two instances must NOT be the same reference");
  var ops = new PhysicsBridge.OrphanedConnectionOps<FakeOrphanCandidate>() {
   boolean firstRead = true;
   public boolean hasConnection(FakeOrphanCandidate c) { return c.hasConnection; }
   public boolean anchorIsValid(FakeOrphanCandidate c) { return c.anchorValid; }
   public Object connectionIdentity(FakeOrphanCandidate c) {
    if (firstRead) { firstRead = false; return originalIdentity; }
    return differentInstanceSameValue;
   }
   public int indexInParentList(FakeOrphanCandidate c) { return 5; }
   public void disconnectAtIndex(FakeOrphanCandidate c, int index) { c.disconnectCalls++; c.lastReceivedIndex = index; c.hasConnection = false; }
   public void fallbackClearConnection(FakeOrphanCandidate c) { c.fallbackCalls++; }
  };
  PhysicsBridge.releaseOrphanedConnections(java.util.List.of(v), ops);
  assertEquals(0, v.disconnectCalls, "a distinct-but-equal-valued connection token must still be treated as a changed identity (== semantics, not equals) and skipped");
  assertEquals(0, v.fallbackCalls);
 }

 @Test void aCandidateWhoseConnectionIdentityIsPreservedIsStillTornDownExactlyOnce() {
  // Regression guard: the new identity check must not accidentally block the ORDINARY case
  // where the SAME connection (same identity throughout) is simply still orphaned at dispatch
  // time - this must still disconnect exactly once, at its own real index.
  var v = new FakeOrphanCandidate(true, false, 4);
  PhysicsBridge.releaseOrphanedConnections(java.util.List.of(v), fakeOps());
  assertEquals(1, v.disconnectCalls, "an unchanged, still-invalid connection must still be disconnected exactly once");
  assertEquals(4, v.lastReceivedIndex);
 }

 /** MINECRAFT-545/547 (John's comments 54727/54730/54742): a pure-logic model of one simulated
  * Minecraft tick's real event order - {@code sweepBeforeDispatch} runs (or not), THEN
  * {@code dispatch} runs (incrementing {@code dispatchCount} unless the candidate is currently
  * orphaned: has a connection whose anchor is invalid) - standing in for
  * {@code ServerTickEvent.Pre -> entity-ticking} within one real tick, without a live NeoForge
  * event bus (which no pure unit test in this suite can exercise). */
 private static void simulateTick(FakeOrphanCandidate v, PhysicsBridge.OrphanedConnectionOps<FakeOrphanCandidate> ops, boolean sweepBeforeDispatch) {
  if (sweepBeforeDispatch) PhysicsBridge.releaseOrphanedConnections(java.util.List.of(v), ops);
  boolean blocked = v.hasConnection && !v.anchorValid;
  if (!blocked) v.dispatchCount++;
  if (!sweepBeforeDispatch) PhysicsBridge.releaseOrphanedConnections(java.util.List.of(v), ops);
 }

 @Test void sweepingBeforeDispatchOnTheVeryNextTickAvoidsAnyStarvedTick() {
  // 54742's own fix: moving the sweep to run BEFORE dispatch each tick (modeling
  // ServerTickEvent.Pre, which fires before that tick's own entity-ticking). The orphaning
  // itself happens AFTER this tick's own dispatch already ran (modeling removal happening
  // inside some OTHER, later Post listener) - so THIS tick's dispatch is unaffected regardless.
  // The very NEXT tick's sweep (now running before ITS dispatch) must release the connection in
  // time for that SAME tick's dispatch to succeed - zero starved ticks.
  var v = new FakeOrphanCandidate(true, true, 0); // starts with a VALID connection
  var ops = fakeOps();
  simulateTick(v, ops, true); // tick R: dispatches fine (valid), THEN becomes orphaned below
  v.anchorValid = false; // the anchor's removal, happening AFTER tick R's own dispatch (e.g. mid-Post)
  simulateTick(v, ops, true); // tick R+1: sweep (Pre) runs BEFORE dispatch - releases it in time
  assertEquals(2, v.dispatchCount, "with the sweep running before dispatch, NO tick is starved - R+1 dispatches normally");
  assertEquals(1, v.disconnectCalls);
 }

 @Test void sweepingAfterDispatchStillStarvesExactlyOneTick_theConfirmedPriorBehavior() {
  // The OLD (Post-timing) behavior, kept here as a documented contrast: with the sweep running
  // AFTER dispatch (modeling the original ServerTickEvent.Post placement), the tick immediately
  // following the orphaning event is STILL blocked (dispatch checked before that tick's own
  // sweep runs) - exactly the confirmed one-tick gap from the M545-packet-95bc8ed-flagon run.
  var v = new FakeOrphanCandidate(true, true, 0);
  var ops = fakeOps();
  simulateTick(v, ops, false); // tick R: dispatches fine, THEN becomes orphaned below
  v.anchorValid = false;
  simulateTick(v, ops, false); // tick R+1: dispatch checked FIRST (still blocked) - starved - THEN sweep releases it
  assertEquals(1, v.dispatchCount, "tick R+1 must be starved under the OLD Post-timing model - this is the confirmed prior behavior, not the fix");
  simulateTick(v, ops, false); // tick R+2: now released, dispatches normally again
  assertEquals(2, v.dispatchCount);
 }

 @Test void subsequentTicksAfterReleaseContinueDispatchingNormallyEveryTick() {
  var v = new FakeOrphanCandidate(true, true, 0);
  var ops = fakeOps();
  simulateTick(v, ops, true);
  v.anchorValid = false;
  for (int i = 0; i < 10; i++) simulateTick(v, ops, true);
  assertEquals(11, v.dispatchCount, "every tick from the very first sweep onward must dispatch exactly once, with no further gaps");
  assertEquals(1, v.disconnectCalls, "the connection must be released exactly once, never repeatedly");
 }

 @Test void aValidReplacementConnectionIsNeverDisconnectedBySweepingBeforeDispatch() {
  // A fresh, VALID replacement connection (e.g. TOWING_REPLACEMENT's own reconnect) must dispatch
  // normally every tick and never be touched by the sweep, regardless of sweep timing.
  var v = new FakeOrphanCandidate(true, true, 3);
  var ops = fakeOps();
  for (int i = 0; i < 5; i++) simulateTick(v, ops, true);
  assertEquals(5, v.dispatchCount, "a valid connection must dispatch every single tick");
  assertEquals(0, v.disconnectCalls);
  assertEquals(0, v.fallbackCalls);
 }

 @Test void thePreTimingSweepNeverRunsTwicePerSimulatedTick() {
  // "no duplicate dispatch": confirms the fixed model's own simulateTick helper calls the sweep
  // EXACTLY once per simulated tick (matching the real fix - releaseOrphanedTowingConnections
  // is called from exactly one listener, never both Pre and Post) - a second redundant sweep in
  // the SAME tick would still be idempotent (per repeatedSweepIsIdempotent above) but is not
  // what the real fix does.
  var v = new FakeOrphanCandidate(true, false, 0);
  int[] sweepCalls = {0};
  var ops = new PhysicsBridge.OrphanedConnectionOps<FakeOrphanCandidate>() {
   public boolean hasConnection(FakeOrphanCandidate c) { sweepCalls[0]++; return c.hasConnection; }
   public boolean anchorIsValid(FakeOrphanCandidate c) { return c.anchorValid; }
   public Object connectionIdentity(FakeOrphanCandidate c) { return c.connectionId; }
   public int indexInParentList(FakeOrphanCandidate c) { return c.indexInParent; }
   public void disconnectAtIndex(FakeOrphanCandidate c, int index) { c.disconnectCalls++; c.hasConnection = false; }
   public void fallbackClearConnection(FakeOrphanCandidate c) { c.fallbackCalls++; c.hasConnection = false; }
  };
  simulateTick(v, ops, true);
  // hasConnection is read exactly twice per releaseOrphanedConnections call (collection phase +
  // dispatch-time re-check) - two calls confirms the sweep itself ran exactly once this tick.
  assertEquals(2, sweepCalls[0], "the sweep must run exactly once per tick in the fixed model");
 }

 // MINECRAFT-545/547 (John's comment 54823): pure coverage of the missing-index fallback's own
 // side-effect ORDER - PhysicsBridge.FallbackClearOps/fallbackClearConnection(V, FallbackClearOps).
 // No real MTS object needed - this seam exists specifically because the real adapter cannot be
 // constructed in this suite (confirmed elsewhere), but the ORDER its calls happen in can still
 // be verified here with a plain fake.

 private static final class FakeFallbackCandidate {
  final java.util.List<String> callOrder = new java.util.ArrayList<>();
 }

 private static PhysicsBridge.FallbackClearOps<FakeFallbackCandidate> recordingFallbackOps() {
  return new PhysicsBridge.FallbackClearOps<FakeFallbackCandidate>() {
   public void resetTowingVariables(FakeFallbackCandidate c) { c.callOrder.add("resetTowing"); }
   public void resetTowedVariables(FakeFallbackCandidate c) { c.callOrder.add("resetTowed"); }
   public void engageParkingBrake(FakeFallbackCandidate c) { c.callOrder.add("brake"); }
   public void clearConnection(FakeFallbackCandidate c) { c.callOrder.add("clear"); }
  };
 }

 @Test void fallbackClearConnectionCallsEveryStepExactlyOnce() {
  var c = new FakeFallbackCandidate();
  PhysicsBridge.fallbackClearConnection(c, recordingFallbackOps());
  assertEquals(4, c.callOrder.size());
 }

 @Test void fallbackClearConnectionClearsConnectionLast() {
  // The real adapter's own resetTowingVariables reads v.towedByConnection.towingVehicle - it
  // must run BEFORE clearConnection nulls that field, or it would throw/read nothing. This test
  // pins "clear is always last" as a real ordering contract, not an incidental detail.
  var c = new FakeFallbackCandidate();
  PhysicsBridge.fallbackClearConnection(c, recordingFallbackOps());
  assertEquals("clear", c.callOrder.get(c.callOrder.size() - 1),
    "clearConnection must be the LAST step - every other step may still need to read through the connection it clears");
 }

 @Test void fallbackClearConnectionResetsTowingBeforeTowed() {
  // Mirrors the real adapter's own exact call order (towing.resetAllVariables() before
  // v.resetAllVariables()) - pinned here so a reordering in the real adapter would be caught by
  // this pure test rather than only by re-reading the real adapter's own source.
  var c = new FakeFallbackCandidate();
  PhysicsBridge.fallbackClearConnection(c, recordingFallbackOps());
  assertEquals(java.util.List.of("resetTowing", "resetTowed", "brake", "clear"), c.callOrder);
 }

 // MINECRAFT-545/547 (John's comments 54862/54870): pure coverage of the bounded, opt-in
 // fallback-observer API - PhysicsBridge.registerFallbackObserver/recordFallbackClearConnectionObservation/
 // unregisterFallbackObserver. Plain UUIDs and Object references throughout - no MTS dependency.

 @Test void unregisteredVehicleRecordsNothingAndMatchedCountIsZero() {
  var randomUuid = java.util.UUID.randomUUID();
  // Never registered - must be a complete no-op, not an exception, not a silently-created entry.
  assertFalse(PhysicsBridge.recordFallbackClearConnectionObservation(randomUuid, new Object()));
 }

 @Test void registeredVehicleWithMatchingConnectionCountsAsAMatch() {
  var vehicleUuid = java.util.UUID.randomUUID();
  var connection = new Object();
  var handle = PhysicsBridge.registerFallbackObserver(vehicleUuid, connection);
  assertTrue(PhysicsBridge.recordFallbackClearConnectionObservation(vehicleUuid, connection));
  assertEquals(1, PhysicsBridge.debugFallbackObserverMatchedCount(handle));
  assertEquals(0, PhysicsBridge.debugFallbackObserverMismatchedCount(handle));
  PhysicsBridge.unregisterFallbackObserver(handle);
 }

 @Test void registeredVehicleWithDifferentConnectionCountsAsAMismatchNotAMatch() {
  // 54870: "Test wrong vehicle/connection rejection" - a registered vehicle observed with a
  // DIFFERENT connection object (not equal-valued, a genuinely different reference) must be
  // counted as a mismatch, never silently folded into the match count.
  var vehicleUuid = java.util.UUID.randomUUID();
  var expectedConnection = new Object();
  var differentConnection = new Object();
  var handle = PhysicsBridge.registerFallbackObserver(vehicleUuid, expectedConnection);
  assertFalse(PhysicsBridge.recordFallbackClearConnectionObservation(vehicleUuid, differentConnection));
  assertEquals(0, PhysicsBridge.debugFallbackObserverMatchedCount(handle));
  assertEquals(1, PhysicsBridge.debugFallbackObserverMismatchedCount(handle));
  PhysicsBridge.unregisterFallbackObserver(handle);
 }

 @Test void observationForADifferentUnregisteredVehicleDoesNotAffectAnotherVehiclesOwnRegistration() {
  // "wrong vehicle... rejection" - an observation keyed to a vehicle UUID that was never
  // registered must not affect a genuinely-registered DIFFERENT vehicle's own counts.
  var registeredUuid = java.util.UUID.randomUUID();
  var unregisteredUuid = java.util.UUID.randomUUID();
  var connection = new Object();
  var handle = PhysicsBridge.registerFallbackObserver(registeredUuid, connection);
  assertFalse(PhysicsBridge.recordFallbackClearConnectionObservation(unregisteredUuid, connection));
  assertEquals(0, PhysicsBridge.debugFallbackObserverMatchedCount(handle));
  PhysicsBridge.unregisterFallbackObserver(handle);
 }

 @Test void exactlyOnceCountingAcrossMultipleObservations() {
  var vehicleUuid = java.util.UUID.randomUUID();
  var connection = new Object();
  var handle = PhysicsBridge.registerFallbackObserver(vehicleUuid, connection);
  PhysicsBridge.recordFallbackClearConnectionObservation(vehicleUuid, connection);
  PhysicsBridge.recordFallbackClearConnectionObservation(vehicleUuid, connection);
  assertEquals(2, PhysicsBridge.debugFallbackObserverMatchedCount(handle),
    "each real observation must increment exactly once - no double-counting, no silent drops");
  PhysicsBridge.unregisterFallbackObserver(handle);
 }

 @Test void unregisterReleasesTheEntryAndFurtherObservationsAreNoOps() {
  // "unregister all terminal/error paths, no default retained growth" - after unregistering,
  // the SAME vehicle UUID must go back to being a complete no-op, exactly as if it had never
  // been registered at all - no stale state survives the unregister.
  var vehicleUuid = java.util.UUID.randomUUID();
  var connection = new Object();
  var handle = PhysicsBridge.registerFallbackObserver(vehicleUuid, connection);
  PhysicsBridge.recordFallbackClearConnectionObservation(vehicleUuid, connection);
  PhysicsBridge.unregisterFallbackObserver(handle);
  assertEquals(0, PhysicsBridge.debugFallbackObserverMatchedCount(handle),
    "reading through a handle after its own unregister must report zero, same as never registered");
  assertFalse(PhysicsBridge.recordFallbackClearConnectionObservation(vehicleUuid, connection),
    "an observation after unregister must be a no-op, never silently resurrecting the registration");
 }

 @Test void unregisterWithNullHandleIsASafeNoOp() {
  PhysicsBridge.unregisterFallbackObserver(null);
 }

 // MINECRAFT-545/547 (John's comment 54929): a duplicate registration for the SAME vehicle UUID
 // must not let the FIRST (now-stale) handle read or remove the SECOND registration's own state.

 @Test void duplicateRegistrationForSameUuidMakesThePriorHandleStale() {
  var vehicleUuid = java.util.UUID.randomUUID();
  var firstConnection = new Object();
  var secondConnection = new Object();
  var firstHandle = PhysicsBridge.registerFallbackObserver(vehicleUuid, firstConnection);
  var secondHandle = PhysicsBridge.registerFallbackObserver(vehicleUuid, secondConnection);
  // An observation matching the SECOND registration's own connection.
  assertTrue(PhysicsBridge.recordFallbackClearConnectionObservation(vehicleUuid, secondConnection));
  assertEquals(1, PhysicsBridge.debugFallbackObserverMatchedCount(secondHandle),
    "the second (live) handle must see its own matched observation");
  assertEquals(0, PhysicsBridge.debugFallbackObserverMatchedCount(firstHandle),
    "the first (now-stale) handle must NOT see the second registration's own matched count");
  PhysicsBridge.unregisterFallbackObserver(firstHandle);
 }

 @Test void unregisteringAStaleHandleDoesNotRemoveTheNewerRegistration() {
  var vehicleUuid = java.util.UUID.randomUUID();
  var firstConnection = new Object();
  var secondConnection = new Object();
  var firstHandle = PhysicsBridge.registerFallbackObserver(vehicleUuid, firstConnection);
  var secondHandle = PhysicsBridge.registerFallbackObserver(vehicleUuid, secondConnection);
  PhysicsBridge.unregisterFallbackObserver(firstHandle);
  // The SECOND registration must still be live - a stale handle's own unregister must be a no-op.
  assertTrue(PhysicsBridge.recordFallbackClearConnectionObservation(vehicleUuid, secondConnection),
    "the second registration must still be live after the first (stale) handle's own unregister");
  assertEquals(1, PhysicsBridge.debugFallbackObserverMatchedCount(secondHandle));
  PhysicsBridge.unregisterFallbackObserver(secondHandle);
 }

 // MINECRAFT-545/547 (John's comment 54974, corrected per 54979): the pure client-side
 // identity-safety policy for a PROPOSED (not yet wired) stale-disconnect notification.
 // CORRECTED (54979): the original version of these tests used tuple-only matching, which John
 // confirmed would have torn down a same-tuple REPLACEMENT connection - exactly the hazard the
 // policy claimed to guard against. Every test below now also exercises generation, and
 // includes the specific regression case 54979 named: same tuple, DIFFERENT generation, which
 // the ORIGINAL (uncorrected) policy would have wrongly disconnected.

 private static PhysicsBridge.TowingConnectionIdentity identity(
     java.util.UUID towingUuid, java.util.UUID towedUuid, int hitchGroup, int hitchConn, int hookupGroup, int hookupConn) {
  return new PhysicsBridge.TowingConnectionIdentity(towingUuid, towedUuid, hitchGroup, hitchConn, hookupGroup, hookupConn);
 }

 @Test void clientWithNoCurrentConnectionIgnoresTheNotification() {
  var towing = java.util.UUID.randomUUID(); var towed = java.util.UUID.randomUUID();
  var notified = identity(towing, towed, 0, 0, 0, 0);
  assertEquals(PhysicsBridge.StaleDisconnectAction.IGNORE_NOTIFICATION,
    PhysicsBridge.decideStaleDisconnectAction(notified, 1L, null, null, true));
 }

 @Test void clientWithADifferentTowingVehicleIgnoresTheNotification() {
  // "Wrong entity/identity" - a different towing vehicle entirely, same towed vehicle.
  var towed = java.util.UUID.randomUUID();
  var notified = identity(java.util.UUID.randomUUID(), towed, 0, 0, 0, 0);
  var clientCurrent = identity(java.util.UUID.randomUUID(), towed, 0, 0, 0, 0);
  assertEquals(PhysicsBridge.StaleDisconnectAction.IGNORE_NOTIFICATION,
    PhysicsBridge.decideStaleDisconnectAction(notified, 1L, clientCurrent, 1L, true));
 }

 @Test void clientWithADifferentHitchOrHookupSlotIgnoresTheNotification() {
  // Different hitch/hookup group or connection index means this is NOT the same connection the
  // server cleared, even though both vehicles match - a genuine replacement at a DIFFERENT slot
  // must be preserved. (The SAME-slot replacement case is covered separately below, by
  // generation mismatch, not by this tuple-difference check.)
  var towing = java.util.UUID.randomUUID(); var towed = java.util.UUID.randomUUID();
  var notified = identity(towing, towed, 0, 0, 0, 0);
  var clientCurrentDifferentHitchGroup = identity(towing, towed, 1, 0, 0, 0);
  assertEquals(PhysicsBridge.StaleDisconnectAction.IGNORE_NOTIFICATION,
    PhysicsBridge.decideStaleDisconnectAction(notified, 1L, clientCurrentDifferentHitchGroup, 1L, true));
  var clientCurrentDifferentHookupConnIndex = identity(towing, towed, 0, 0, 0, 1);
  assertEquals(PhysicsBridge.StaleDisconnectAction.IGNORE_NOTIFICATION,
    PhysicsBridge.decideStaleDisconnectAction(notified, 1L, clientCurrentDifferentHookupConnIndex, 1L, true));
 }

 // MINECRAFT-545/547 (John's comment 54979) - THE critical regression case: this is EXACTLY the
 // scenario the 54974 round's own tuple-only policy got wrong, confirmed by John's own review.

 @Test void sameTupleButDifferentGenerationIsASafeReplacementNeverDisconnected() {
  // The server notified about generation 1 (the OLD connection it cleared). By the time this
  // notification is processed, the client's CURRENT connection for the identical tuple is
  // generation 2 - a genuine, later REPLACEMENT to the exact same vehicles/slot. Tuple equality
  // alone (the 54974 round's own check) would have said "match" here and torn it down. The
  // generation check must override that and refuse.
  var towing = java.util.UUID.randomUUID(); var towed = java.util.UUID.randomUUID();
  var notified = identity(towing, towed, 0, 0, 0, 0);
  var clientCurrentSameTuple = identity(towing, towed, 0, 0, 0, 0);
  assertEquals(PhysicsBridge.StaleDisconnectAction.IGNORE_NOTIFICATION,
    PhysicsBridge.decideStaleDisconnectAction(notified, 1L, clientCurrentSameTuple, 2L, true),
    "a same-tuple REPLACEMENT (different generation) must never be disconnected by a stale notification about the OLD generation");
 }

 @Test void tupleMatchesButEitherGenerationIsUnknownFailsClosedAsAmbiguous() {
  // Per 54979: "If binding/order cannot be established, ambiguous notification MUST no-op/fail
  // closed." A tuple match with no trustworthy generation on one or both sides is NOT safe to
  // treat as a real match - it must be AMBIGUOUS_NO_OP, never silently fall through to acting.
  var towing = java.util.UUID.randomUUID(); var towed = java.util.UUID.randomUUID();
  var notified = identity(towing, towed, 0, 0, 0, 0);
  var clientCurrent = identity(towing, towed, 0, 0, 0, 0);
  assertEquals(PhysicsBridge.StaleDisconnectAction.AMBIGUOUS_NO_OP,
    PhysicsBridge.decideStaleDisconnectAction(notified, null, clientCurrent, 1L, true),
    "server-side generation unknown - must not assume a match");
  assertEquals(PhysicsBridge.StaleDisconnectAction.AMBIGUOUS_NO_OP,
    PhysicsBridge.decideStaleDisconnectAction(notified, 1L, clientCurrent, null, true),
    "client never bound a generation for its own current connection - must not assume a match");
  assertEquals(PhysicsBridge.StaleDisconnectAction.AMBIGUOUS_NO_OP,
    PhysicsBridge.decideStaleDisconnectAction(notified, null, clientCurrent, null, true),
    "neither side has a generation - still ambiguous, not a free pass to act");
 }

 @Test void exactlyMatchingConnectionAndGenerationWithAValidClientIndexDisconnectsByThatIndex() {
  // "Valid-index normal disconnect" - the client's own copy is still fully intact, AND it is a
  // CONFIRMED generation match (not merely a tuple guess) - the real, client-own index must be
  // used, never skipped just because the SERVER's own index was gone.
  var towing = java.util.UUID.randomUUID(); var towed = java.util.UUID.randomUUID();
  var notified = identity(towing, towed, 0, 0, 0, 0);
  var clientCurrent = identity(towing, towed, 0, 0, 0, 0);
  assertEquals(PhysicsBridge.StaleDisconnectAction.DISCONNECT_BY_CLIENT_OWN_INDEX,
    PhysicsBridge.decideStaleDisconnectAction(notified, 1L, clientCurrent, 1L, true));
 }

 @Test void exactlyMatchingConnectionAndGenerationWithNoValidClientIndexFallsBackToIdempotentCleanup() {
  // "Missing-index bounded behavior" - confirmed generation match, but the client independently
  // also lacks a valid index (the same structural gap the server itself hit) - fall back to the
  // same public-only cleanup, never fabricating an index to force the real disconnectTrailer path.
  var towing = java.util.UUID.randomUUID(); var towed = java.util.UUID.randomUUID();
  var notified = identity(towing, towed, 0, 0, 0, 0);
  var clientCurrent = identity(towing, towed, 0, 0, 0, 0);
  assertEquals(PhysicsBridge.StaleDisconnectAction.IDEMPOTENT_CLEANUP_ONLY,
    PhysicsBridge.decideStaleDisconnectAction(notified, 1L, clientCurrent, 1L, false));
 }

 @Test void repeatedDeliveryOfTheSameNotificationYieldsTheSamePureDecisionTwice() {
  // "Duplicate/replay" - the pure decision function itself is referentially transparent: the
  // SAME inputs always produce the SAME output. This establishes only that the DECISION is
  // stable under replay, not that applying it twice to real mutable state is side-effect-free -
  // see the separate stateful idempotency test below for that distinct claim.
  var towing = java.util.UUID.randomUUID(); var towed = java.util.UUID.randomUUID();
  var notified = identity(towing, towed, 0, 0, 0, 0);
  var clientCurrent = identity(towing, towed, 0, 0, 0, 0);
  var first = PhysicsBridge.decideStaleDisconnectAction(notified, 1L, clientCurrent, 1L, true);
  var second = PhysicsBridge.decideStaleDisconnectAction(notified, 1L, clientCurrent, 1L, true);
  assertEquals(first, second);
 }

 // MINECRAFT-545/547 (John's comment 54991): a prior round's own stateful test
 // (SyntheticClientConnectionState.applyIfConnected) was a test-only toy that exercised nothing
 // but itself, not any production code path. Replaced with the REAL production orchestration
 // (applyStaleDisconnectAction/StaleDisconnectOps) exercised through the full, real
 // decide -> apply -> redecide pipeline against a small, honestly-synthetic CLIENT MODEL (the
 // model's own fields stand in for what a real client's towedByConnection/towingConnections
 // would be - the DECISION and APPLY functions under test are both the genuine production code,
 // not reimplemented or stubbed out).

 /** A minimal model of a client's own relevant state - stands in for the real
  * towedByConnection/towingConnections fields a live client would have; everything that
  * actually DECIDES and APPLIES below is the real, production
  * decideStaleDisconnectAction/applyStaleDisconnectAction code, not a reimplementation. */
 private static final class ClientModel {
  PhysicsBridge.TowingConnectionIdentity currentConnection;
  Long currentGeneration;
  boolean hasValidIndex;
  int clientOwnIndex;
  int disconnectByIndexCallCount = 0;
  int idempotentCleanupCallCount = 0;
 }

 private static final PhysicsBridge.StaleDisconnectOps<ClientModel> COUNTING_OPS = new PhysicsBridge.StaleDisconnectOps<ClientModel>() {
  public void disconnectByClientIndex(ClientModel m, int clientOwnIndex) {
   m.disconnectByIndexCallCount++;
   m.currentConnection = null; m.currentGeneration = null; // the real effect: the connection is now gone, client-side too.
  }
  public void idempotentCleanup(ClientModel m) {
   m.idempotentCleanupCallCount++;
   m.currentConnection = null; m.currentGeneration = null;
  }
 };

 /** Runs the REAL, full production pipeline once: decide, then apply via the real dispatcher. */
 private static void decideAndApply(
     PhysicsBridge.TowingConnectionIdentity notified, long notifiedGeneration, ClientModel m) {
  var action = PhysicsBridge.decideStaleDisconnectAction(
    notified, notifiedGeneration, m.currentConnection, m.currentGeneration, m.hasValidIndex);
  PhysicsBridge.applyStaleDisconnectAction(m, action, m.clientOwnIndex, COUNTING_OPS);
 }

 @Test void replayedNotificationThroughTheRealPipelineMutatesOnlyOnce() {
  // The exact scenario John asked for: a valid-index normal disconnect, then a DUPLICATE/
  // replayed delivery of the identical notification - run through the REAL decide+apply
  // pipeline, not a toy guard, confirming exactly one real mutation across both deliveries.
  var towing = java.util.UUID.randomUUID(); var towed = java.util.UUID.randomUUID();
  var notified = identity(towing, towed, 0, 0, 0, 0);
  var m = new ClientModel();
  m.currentConnection = identity(towing, towed, 0, 0, 0, 0);
  m.currentGeneration = 1L;
  m.hasValidIndex = true; m.clientOwnIndex = 3;

  decideAndApply(notified, 1L, m); // first delivery
  decideAndApply(notified, 1L, m); // replayed/duplicate delivery of the identical notification

  assertEquals(1, m.disconnectByIndexCallCount, "a replayed notification must not cause a second real disconnect call");
  assertEquals(0, m.idempotentCleanupCallCount);
  assertNull(m.currentConnection, "the client's own model must reflect the real disconnect after the first delivery");
 }

 @Test void missingIndexBoundedBehaviorThroughTheRealPipelineAppliesIdempotentCleanupExactlyOnce() {
  var towing = java.util.UUID.randomUUID(); var towed = java.util.UUID.randomUUID();
  var notified = identity(towing, towed, 0, 0, 0, 0);
  var m = new ClientModel();
  m.currentConnection = identity(towing, towed, 0, 0, 0, 0);
  m.currentGeneration = 1L;
  m.hasValidIndex = false;

  decideAndApply(notified, 1L, m);
  decideAndApply(notified, 1L, m); // replay

  assertEquals(0, m.disconnectByIndexCallCount);
  assertEquals(1, m.idempotentCleanupCallCount, "a replayed notification must not cause a second real cleanup call");
 }

 @Test void sameTupleDifferentGenerationThroughTheRealPipelineNeverMutatesTheReplacement() {
  // The critical regression, run through the REAL pipeline this time (not just the pure
  // decision in isolation): a genuine same-tuple replacement connection must survive a stale
  // notification about the OLD generation completely untouched - zero calls to either ops
  // method, and the model's own "current connection" must remain exactly what it was.
  var towing = java.util.UUID.randomUUID(); var towed = java.util.UUID.randomUUID();
  var notified = identity(towing, towed, 0, 0, 0, 0);
  var m = new ClientModel();
  m.currentConnection = identity(towing, towed, 0, 0, 0, 0); // same tuple as notified...
  m.currentGeneration = 2L; // ...but a DIFFERENT (newer) generation - a real replacement.
  m.hasValidIndex = true; m.clientOwnIndex = 5;

  decideAndApply(notified, 1L, m);

  assertEquals(0, m.disconnectByIndexCallCount);
  assertEquals(0, m.idempotentCleanupCallCount);
  assertEquals(2L, m.currentGeneration, "the replacement connection's own state must be completely untouched");
 }

 @Test void outOfOrderDeliveryThroughARealLedgerTransitionIsAlsoSafe() {
  // MINECRAFT-545/547 (John's comment 54998): a prior version of this test used LITERAL 1L/2L
  // constants, which only ever asserted the DECISION logic's own arithmetic, never an actual
  // transition through the real ledger. This version drives the REAL
  // connectionGeneration/forgetConnectionGeneration calls through an actual
  // old-connection-torn-down-then-new-connection-formed sequence, using the generation VALUES
  // the real ledger itself produces - not hand-picked numbers that happen to differ.
  var oldConnectionObject = new Object();
  var newConnectionObject = new Object(); // a DIFFERENT object - the real replacement.

  long oldGeneration = PhysicsBridge.connectionGeneration(oldConnectionObject); // old connection first observed/bound.
  PhysicsBridge.forgetConnectionGeneration(oldConnectionObject); // old connection actually torn down.
  long newGeneration = PhysicsBridge.connectionGeneration(newConnectionObject); // replacement bound BEFORE the stale notice arrives.
  assertNotEquals(oldGeneration, newGeneration, "the real ledger must never reuse the old connection's own generation value for the new object");

  var towing = java.util.UUID.randomUUID(); var towed = java.util.UUID.randomUUID();
  var m = new ClientModel();
  m.currentConnection = identity(towing, towed, 0, 0, 0, 0);
  m.currentGeneration = newGeneration; // the client's own current connection is bound to the REAL new generation.
  m.hasValidIndex = true; m.clientOwnIndex = 7;

  var staleNotified = identity(towing, towed, 0, 0, 0, 0);
  decideAndApply(staleNotified, oldGeneration, m); // the late, out-of-order notice about the real, superseded old generation

  assertEquals(0, m.disconnectByIndexCallCount);
  assertEquals(0, m.idempotentCleanupCallCount);
 }

 @Test void unrelatedEntityThroughTheRealPipelineIsUntouched() {
  // "Wrong recipient/entity" through the real pipeline - a client that was never tracking the
  // named connection at all (no current connection) must see zero calls.
  var towing = java.util.UUID.randomUUID(); var towed = java.util.UUID.randomUUID();
  var notified = identity(towing, towed, 0, 0, 0, 0);
  var m = new ClientModel(); // currentConnection left null - never had one.
  decideAndApply(notified, 1L, m);
  assertEquals(0, m.disconnectByIndexCallCount);
  assertEquals(0, m.idempotentCleanupCallCount);
 }

 // --- connectionGeneration / forgetConnectionGeneration (the server-side identity ledger) ---

 @Test void distinctConnectionObjectsGetDistinctGenerationsEvenWithIdenticalFieldValues() {
  // The whole point of identity-keyed generations: two SEPARATE objects, even if they would
  // compare equal by value (modeled here with two distinct instances), must never collide.
  var connectionA = new Object();
  var connectionB = new Object();
  var genA = PhysicsBridge.connectionGeneration(connectionA);
  var genB = PhysicsBridge.connectionGeneration(connectionB);
  assertNotEquals(genA, genB);
 }

 @Test void sameConnectionObjectAlwaysReturnsTheSameGenerationOnRepeatedLookup() {
  var connection = new Object();
  var first = PhysicsBridge.connectionGeneration(connection);
  var second = PhysicsBridge.connectionGeneration(connection);
  assertEquals(first, second);
  PhysicsBridge.forgetConnectionGeneration(connection);
 }

 @Test void forgettingAConnectionThenReassigningANewObjectNeverReusesTheOldGenerationValue() {
  // Not strictly required for correctness (a brand-new object is never == the forgotten one
  // regardless of the numeric value it gets), but confirms the ledger's own counter keeps
  // advancing rather than somehow recycling a freed slot's number, which would be a more
  // confusing (if not unsafe) implementation to maintain.
  var oldConnection = new Object();
  var oldGen = PhysicsBridge.connectionGeneration(oldConnection);
  PhysicsBridge.forgetConnectionGeneration(oldConnection);
  var newConnection = new Object();
  var newGen = PhysicsBridge.connectionGeneration(newConnection);
  assertNotEquals(oldGen, newGen);
 }

 @Test void connectionGenerationOfNullIsNull() {
  assertNull(PhysicsBridge.connectionGeneration(null));
 }

 @Test void forgetConnectionGenerationWithNullIsASafeNoOp() {
  PhysicsBridge.forgetConnectionGeneration(null);
 }

 // MINECRAFT-545/547 (John's comment 54998): "WeakHashMap<Object,Long> relies on equals
 // semantics: ... pin/test identity semantics ... before relying on reference-identity
 // generation." This is a TEST-TIME-ONLY structural assertion using exclusively PUBLIC
 // reflection on a PUBLIC class/method (no setAccessible, no bypassing any access control) -
 // it exists purely as a regression guard: if a future MTS version ever adds an equals()/
 // hashCode() override to TowingConnection, this test fails immediately and loudly, rather than
 // silently breaking the generation ledger's own reference-identity safety assumption.

 @Test void towingConnectionDoesNotOverrideEqualsOrHashCode() throws NoSuchMethodException {
  var equalsMethod = minecrafttransportsimulator.baseclasses.TowingConnection.class.getMethod("equals", Object.class);
  var hashCodeMethod = minecrafttransportsimulator.baseclasses.TowingConnection.class.getMethod("hashCode");
  assertEquals(Object.class, equalsMethod.getDeclaringClass(),
    "TowingConnection must keep using Object's own identity equals() - the generation ledger's WeakHashMap relies on this");
  assertEquals(Object.class, hashCodeMethod.getDeclaringClass(),
    "TowingConnection must keep using Object's own identity hashCode() - the generation ledger's WeakHashMap relies on this");
 }

 // MINECRAFT-545/547 (John's comments 55005/55010): "immutable/monotonic number alone does not
 // identify the object... fix trustworthy creation/order binding... watermarks alone do not
 // establish object identity." CORRECTED A THIRD TIME per 55020: BOTH of the 55010 round's own
 // acceptance paths (bootstrap-on-null-watermark, and equality-to-watermark) are ALSO unsafe -
 // John provided two concrete counterexamples, both reproduced here as production-path
 // regression tests (not just scalar-predicate tests - see the second block below, which runs
 // the FULL decide+apply pipeline against a realistic object-identity scenario) BEFORE the fix,
 // confirming they would have failed under the 55010 round's own logic, and AFTER, confirming
 // the current code (which never binds anything at all) correctly prevents both.

 @Test void shouldBindAnnouncedGenerationNeverAcceptsAnyInputWhatsoever() {
  // Per 55020's own explicit instruction: "remove acceptance of ANY unproven object binding,
  // including bootstrap/equality... ambiguous/unproven binding causes NO mutation." No scalar
  // combination of inputs may ever return true - confirmed across a representative spread,
  // including the exact two inputs the 55010 round's own (now known-unsafe) logic accepted.
  assertFalse(PhysicsBridge.shouldBindAnnouncedGeneration(false, null, 1L)); // the 55010 round's own "bootstrap" accept case.
  assertFalse(PhysicsBridge.shouldBindAnnouncedGeneration(false, 2L, 2L)); // the 55010 round's own "equality" accept case.
  assertFalse(PhysicsBridge.shouldBindAnnouncedGeneration(false, 2L, 1L));
  assertFalse(PhysicsBridge.shouldBindAnnouncedGeneration(false, 1L, 2L));
  assertFalse(PhysicsBridge.shouldBindAnnouncedGeneration(true, 1L, 99L));
  assertFalse(PhysicsBridge.shouldBindAnnouncedGeneration(true, null, 0L));
 }

 // MINECRAFT-545/547 (John's comment 55020) - THE two actual production-path counterexamples,
 // run through the REAL decide+apply pipeline (not the bare predicate) against a realistic
 // object-identity scenario, exactly as 55020 asked ("real distinct object identities",
 // "actual production binding/application state machine"). Both confirm: given the 55010
 // round's own (unsafe) acceptance logic, the replacement would have been WRONGLY torn down;
 // given the CURRENT (always-reject) logic, it survives untouched - zero calls to either real
 // ops method.

 @Test void counterexampleA_bootstrapBindingWouldHaveDestroyedAnUnboundReplacement() {
  // No watermark yet. The CURRENT connection is a same-tuple REPLACEMENT that was never bound.
  // A stale FIRST announcement (generation 1, for the OLD, already-superseded connection)
  // arrives, followed by a stale disconnect for that same old generation.
  var towing = java.util.UUID.randomUUID(); var towed = java.util.UUID.randomUUID();
  var staleFirstAnnouncement = identity(towing, towed, 0, 0, 0, 0);
  var m = new ClientModel();
  m.currentConnection = identity(towing, towed, 0, 0, 0, 0); // same tuple - the unbound replacement.
  m.currentGeneration = null; // never bound - watermark for this vehicle is null.
  m.hasValidIndex = true; m.clientOwnIndex = 3;

  // Simulate the stale announcement's own attempted bind (via the real production function):
  boolean wouldBind = PhysicsBridge.shouldBindAnnouncedGeneration(
    /* alreadyBoundForThisObject= */ false, /* watermarkForVehicle= */ null, /* announcedGeneration= */ 1L);
  assertFalse(wouldBind, "the stale first announcement must NOT bind - no trustworthy evidence ties generation 1 to THIS object");
  // Since binding correctly never happens, m.currentGeneration stays null, so the stale
  // disconnect for generation 1 cannot possibly match - confirmed via the real pipeline:
  decideAndApply(staleFirstAnnouncement, 1L, m);
  assertEquals(0, m.disconnectByIndexCallCount);
  assertEquals(0, m.idempotentCleanupCallCount);
 }

 @Test void counterexampleB_equalityBindingWouldHaveDestroyedAnUnboundReplacement() {
  // Watermark already 1 (from an OLD, now-gone object this client never actually bound either -
  // modeling the realistic case where the watermark advanced through some OTHER path). The
  // CURRENT connection is a DIFFERENT, UNBOUND same-tuple replacement. A REPLAY of the original
  // generation-1 announcement arrives (equal to the watermark - the 55010 round's own "safe
  // retrack" case), followed by a legitimate disconnect for generation 1.
  var towing = java.util.UUID.randomUUID(); var towed = java.util.UUID.randomUUID();
  var replayedAnnouncement = identity(towing, towed, 0, 0, 0, 0);
  var m = new ClientModel();
  m.currentConnection = identity(towing, towed, 0, 0, 0, 0); // same tuple - the unbound replacement.
  m.currentGeneration = null; // THIS object was never bound - the watermark being 1 does not mean THIS object is.
  m.hasValidIndex = true; m.clientOwnIndex = 5;

  boolean wouldBind = PhysicsBridge.shouldBindAnnouncedGeneration(
    /* alreadyBoundForThisObject= */ false, /* watermarkForVehicle= */ 1L, /* announcedGeneration= */ 1L);
  assertFalse(wouldBind, "equality to the watermark must NOT bind either - it proves nothing about whether THIS object is the one generation 1 belongs to");
  decideAndApply(replayedAnnouncement, 1L, m);
  assertEquals(0, m.disconnectByIndexCallCount);
  assertEquals(0, m.idempotentCleanupCallCount);
 }
}
