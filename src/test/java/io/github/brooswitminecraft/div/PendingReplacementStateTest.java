package io.github.brooswitminecraft.div;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Ticket 53390's explicit ask: exercises {@code PhysicsBridge.PendingReplacementState} (the
 * generic seam extracted from {@code pendingInheritedVelocity}'s own get/retain-on-failure/
 * consume-on-success/cleanup discipline review 53388 found three real defects in) directly, with
 * plain keys and plain values instead of a live entity/Sable runtime - the same "pure, testable
 * seam" pattern {@link PhysicsBridgeBodyReplacementTest} already uses for
 * {@code replaceIfCategoryChanged}.
 */
class PendingReplacementStateTest {
 @Test void getReturnsNullWhenNothingStaged() {
  var store = new PhysicsBridge.PendingReplacementState<String, Integer>();
  assertNull(store.get("none"));
 }

 @Test void getReturnsStagedValueWithoutRemovingIt() {
  // Review 53388's first defect: the original code called remove() up front, before a
  // construction attempt even started - a failed/retried attempt then found nothing left to
  // consume on retry. get() alone must never remove, so a retry can still see the same value.
  var store = new PhysicsBridge.PendingReplacementState<String, Integer>();
  store.stage("car", 42);
  assertEquals(42, store.get("car"), "first get sees the staged value");
  assertEquals(42, store.get("car"), "a second get (simulating a retry) still sees it - get() never consumes");
 }

 @Test void retainedAcrossASimulatedFailedAttempt() {
  // The actual failure-path scenario: stage, read it (as construction begins), attempt fails
  // (nothing happens to the store on failure - it is simply never told to consume or clean up),
  // then a retry's own get() must still find the SAME value.
  var store = new PhysicsBridge.PendingReplacementState<String, Integer>();
  store.stage("car", 99);
  Integer firstAttempt = store.get("car");
  assertEquals(99, firstAttempt);
  // Simulated failure: the caller does NOT call consumeOnSuccess or cleanup here - exactly
  // matching Body's own constructor, which only consumes after addLinearAndAngularVelocity
  // actually succeeds, inside the try block's post-success line, never in the catch.
  Integer retryAttempt = store.get("car");
  assertEquals(99, retryAttempt, "retained across a failed attempt - the value is still there for the retry");
 }

 @Test void consumeOnSuccessRemovesIt() {
  var store = new PhysicsBridge.PendingReplacementState<String, Integer>();
  store.stage("car", 7);
  store.consumeOnSuccess("car");
  assertNull(store.get("car"), "a confirmed-successful construction must not leave a stale entry for the NEXT, unrelated activation");
 }

 @Test void cleanupRemovesItOnAbandonment() {
  // Review 53388's second defect: a vehicle that stages a pending entry but never successfully
  // reconstructs (becomes ineligible, is removed from the world, etc.) must not leak the entry
  // forever - cleanup() is the path deactivate(v)/cleanup()/unload() now all call for this.
  var store = new PhysicsBridge.PendingReplacementState<String, Integer>();
  store.stage("car", 7);
  store.cleanup("car");
  assertNull(store.get("car"), "abandoned (never successfully reconstructed) - cleanup must remove the stale entry");
 }

 @Test void stagingOverwritesAnyPreviousValueForTheSameKey() {
  var store = new PhysicsBridge.PendingReplacementState<String, Integer>();
  store.stage("car", 1);
  store.stage("car", 2);
  assertEquals(2, store.get("car"), "a second stage() for the same key (e.g. two rebuilds in close succession) replaces, not accumulates");
 }

 @Test void distinctKeysAreIndependent() {
  var store = new PhysicsBridge.PendingReplacementState<String, Integer>();
  store.stage("car1", 1);
  store.stage("car2", 2);
  store.consumeOnSuccess("car1");
  assertNull(store.get("car1"));
  assertEquals(2, store.get("car2"), "consuming one vehicle's entry must not disturb another's");
 }

 // Review 53399: removeIf's own exact reason for existing - a key can be staged, then
 // abandoned, while ALREADY ABSENT from (or never having appeared in) whatever other registry a
 // caller's own bulk-removal loop iterates (PhysicsBridge.cleanup()/unload() previously relied
 // solely on BodyRegistry.removeIf's own callback to call cleanup(v), which never fires for a key
 // BodyRegistry no longer has). removeIf must find and remove such an orphaned key purely from
 // its own predicate over ITS OWN staged keys, with no registry involved at all.
 @Test void removeIfRemovesAnOrphanedKeyNoOtherRegistryEverSaw() {
  var store = new PhysicsBridge.PendingReplacementState<String, Integer>();
  store.stage("orphan", 42); // simulates: deactivated from BodyRegistry already, reconstruction then failed
  store.removeIf(key -> key.equals("orphan"));
  assertNull(store.get("orphan"), "removeIf must remove a key with no corresponding registry entry at all");
 }

 @Test void removeIfLeavesNonMatchingKeysAlone() {
  var store = new PhysicsBridge.PendingReplacementState<String, Integer>();
  store.stage("invalid", 1);
  store.stage("stillValid", 2);
  store.removeIf(key -> key.equals("invalid"));
  assertNull(store.get("invalid"));
  assertEquals(2, store.get("stillValid"), "a key the predicate doesn't match must survive the sweep");
 }

 @Test void removeIfOnAnEmptyStoreIsANoOp() {
  var store = new PhysicsBridge.PendingReplacementState<String, Integer>();
  store.removeIf(key -> true);
  assertNull(store.get("anything"));
 }
}
