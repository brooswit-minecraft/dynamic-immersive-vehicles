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

 // MINECRAFT-545/547 (John's comment 55098): the real root cause of the native crash a
 // BuoyantHitchProbe TOWING_UNAVAILABLE FAILED run surfaced - PhysicsBridge.step previously
 // called registry.get(vehicle).step(vehicle) unconditionally, so a registry entry whose own
 // body had ALREADY had its native resources removed by some OTHER path (isActive()==false)
 // while the entry itself was never cleared would still be stepped, crashing inside that stale
 // body's own step() rather than being caught here. Exercises
 // PhysicsBridge.activeOrReclaimStale(...), the exact generic seam PhysicsBridge.step now calls,
 // with a fake key/body - the same fake-key/fake-body pattern activateOrClassify's own tests
 // already use, since a real EntityVehicleF_Physics cannot be constructed in this suite.

 /** Minimal VehicleBody test double - step()/captureReplacementState()/level()/tuning() are
  * never invoked by activeOrReclaimStale itself (that method never calls step(), only
  * PhysicsBridge.step's own thin wrapper does, with a real vehicle this suite cannot construct),
  * so those bodies are unused/unreachable by design, not a gap in this test's own coverage. */
 private static final class FakeVehicleBody implements VehicleBody {
  boolean active = true;
  boolean removed = false;
  public void step(minecrafttransportsimulator.entities.instances.EntityVehicleF_Physics v) { throw new AssertionError("not reachable from activeOrReclaimStale itself"); }
  public void remove() { removed = true; }
  public boolean isActive() { return active; }
  public PhysicsBridge.InheritedRigidBodyVelocity captureReplacementState(minecrafttransportsimulator.entities.instances.EntityVehicleF_Physics v) { throw new AssertionError("not reachable"); }
  public Object level() { return "fake-level"; }
  public Overrides.Tuning tuning() { throw new AssertionError("not reachable"); }
 }

 @Test void anActiveBodyIsReturnedUnchangedAndNeverReclaimed() {
  var registry = new BodyRegistry<String, FakeVehicleBody>();
  var hitches = new HitchRegistry<String>();
  var pending = new PhysicsBridge.PendingReplacementState<String, Object>();
  var body = new FakeVehicleBody();
  registry.activate("car", body);
  var result = PhysicsBridge.activeOrReclaimStale(registry, hitches, pending, "car");
  assertSame(body, result, "an active body must be returned as-is");
  assertTrue(registry.isActive("car"), "an active body's own registry entry must not be touched");
  assertFalse(body.removed, "an active body must never have remove() called on it by this guard");
 }

 @Test void aStaleEntryWhoseBodyIsNoLongerActiveIsReclaimedNotStepped() {
  // THE exact regression: the body's own native resources were already removed by some OTHER
  // path (isActive()==false) while this registry entry was never cleared - this is the crash
  // PhysicsBridge.step used to walk straight into (calling .step() on a dead body) before this
  // fix; activeOrReclaimStale must now return null (so the caller never calls .step()) and
  // self-heal the dangling entry via the SAME removal path cleanup()/unload() already use.
  var registry = new BodyRegistry<String, FakeVehicleBody>();
  var hitches = new HitchRegistry<String>();
  var pending = new PhysicsBridge.PendingReplacementState<String, Object>();
  var staleBody = new FakeVehicleBody();
  staleBody.active = false; // already removed by some other path; registry entry never cleared
  registry.activate("car", staleBody);
  var result = PhysicsBridge.activeOrReclaimStale(registry, hitches, pending, "car");
  assertNull(result, "a stale (no longer active) body must never be returned for stepping");
  assertFalse(registry.isActive("car"), "the dangling registry entry must be reclaimed");
  assertFalse(staleBody.removed, "remove() must NOT be called again - the native object is already gone, calling it again would be the duplicate-authority case this fix must avoid");
 }

 @Test void aMissingEntryIsSimplyAbsentNotAnError() {
  var registry = new BodyRegistry<String, FakeVehicleBody>();
  var hitches = new HitchRegistry<String>();
  var pending = new PhysicsBridge.PendingReplacementState<String, Object>();
  var result = PhysicsBridge.activeOrReclaimStale(registry, hitches, pending, "never-registered");
  assertNull(result);
 }

 /** A joint whose own remove() throws - the real GenericConstraintHandle shape, same as
  * HitchRegistryTest's own fake joints, deliberately misbehaving on this one call. */
 private static dev.ryanhcode.sable.api.physics.constraint.PhysicsConstraintHandle fakeJointThatThrowsOnRemove() {
  return new dev.ryanhcode.sable.api.physics.constraint.GenericConstraintHandle() {
   public void setFrame1(org.joml.Vector3dc pos, org.joml.Quaterniondc orientation) {}
   public void setFrame2(org.joml.Vector3dc pos, org.joml.Quaterniondc orientation) {}
   public void setLimit(dev.ryanhcode.sable.api.physics.constraint.ConstraintJointAxis axis, double lower, double upper) {}
   public void lockAxes(dev.ryanhcode.sable.api.physics.constraint.ConstraintJointAxis... axes) {}
   public void getJointImpulses(org.joml.Vector3d linear, org.joml.Vector3d angular) {}
   public void setContactsEnabled(boolean enabled) {}
   public void setMotor(dev.ryanhcode.sable.api.physics.constraint.ConstraintJointAxis axis, double target, double stiffness, double damping, boolean limited, double maxForce) {}
   public void remove() { throw new RuntimeException("Sable-level removal failure"); }
   public boolean isValid() { return true; }
  };
 }
 private static dev.ryanhcode.sable.api.physics.constraint.PhysicsConstraintHandle fakeJoint(boolean[] removed) {
  return new dev.ryanhcode.sable.api.physics.constraint.GenericConstraintHandle() {
   public void setFrame1(org.joml.Vector3dc pos, org.joml.Quaterniondc orientation) {}
   public void setFrame2(org.joml.Vector3dc pos, org.joml.Quaterniondc orientation) {}
   public void setLimit(dev.ryanhcode.sable.api.physics.constraint.ConstraintJointAxis axis, double lower, double upper) {}
   public void lockAxes(dev.ryanhcode.sable.api.physics.constraint.ConstraintJointAxis... axes) {}
   public void getJointImpulses(org.joml.Vector3d linear, org.joml.Vector3d angular) {}
   public void setContactsEnabled(boolean enabled) {}
   public void setMotor(dev.ryanhcode.sable.api.physics.constraint.ConstraintJointAxis axis, double target, double stiffness, double damping, boolean limited, double maxForce) {}
   public void remove() { removed[0] = true; }
   public boolean isValid() { return !removed[0]; }
  };
 }

 @Test void aStaleEntryWithBothPendingStateAndALinkedHitchReleasesBoth() {
  // MINECRAFT-545/547 (John's comment 55103 - "seeded with pending state AND actual linked
  // hitch"): the combined scenario, not just one or the other. "car" is staged in
  // pendingInheritedVelocity (a real in-flight replacement capture) AND is actually jointed, via
  // HitchRegistry, to "truck" at the SAME time its own body goes stale. Reclaiming "car" must
  // release BOTH: the real joint (HitchRegistry) and the pending replacement state.
  var registry = new BodyRegistry<String, FakeVehicleBody>();
  var hitches = new HitchRegistry<String>();
  var pending = new PhysicsBridge.PendingReplacementState<String, Object>();
  var staleBody = new FakeVehicleBody();
  staleBody.active = false;
  registry.activate("car", staleBody);
  boolean[] jointRemoved = {false};
  hitches.reconcile("car", "truck", () -> fakeJoint(jointRemoved));
  pending.stage("car", new Object());
  assertTrue(hitches.isJointed("car"), "setup check: car must actually be jointed before reclaiming");
  assertNotNull(pending.get("car"), "setup check: car must actually have staged pending state before reclaiming");
  var result = PhysicsBridge.activeOrReclaimStale(registry, hitches, pending, "car");
  assertNull(result);
  assertTrue(jointRemoved[0], "the real linked hitch (outgoing constraint) must be released");
  assertFalse(hitches.isJointed("car"), "car must no longer be jointed after reclaiming");
  assertNull(pending.get("car"), "the pending replacement state must also be cleared");
 }

 @Test void aThrowingHitchReleaseDuringReclaimStillRunsPendingCleanupAndStillPropagates() {
  // MINECRAFT-545/547 (John's comment 55104): the ORIGINAL activeOrReclaimStale called
  // hitches.release(key) before pendingInheritedVelocity.cleanup(key) with no guard between
  // them - a throwing release() would have skipped this method's own second, independent
  // cleanup step. pending.cleanup() must still run regardless, and the exception itself must
  // still propagate to the caller, not be swallowed.
  var registry = new BodyRegistry<String, FakeVehicleBody>();
  var hitches = new HitchRegistry<String>();
  var pending = new PhysicsBridge.PendingReplacementState<String, Object>();
  var staleBody = new FakeVehicleBody();
  staleBody.active = false;
  registry.activate("car", staleBody);
  hitches.reconcile("car", "truck", () -> fakeJointThatThrowsOnRemove());
  pending.stage("car", new Object());
  assertThrows(RuntimeException.class, () -> PhysicsBridge.activeOrReclaimStale(registry, hitches, pending, "car"),
    "the throw itself must still propagate, not be swallowed");
  assertNull(pending.get("car"), "pending cleanup must still run despite hitches.release throwing");
  assertFalse(registry.isActive("car"), "the registry entry must still be reclaimed despite the throw");
 }
}
