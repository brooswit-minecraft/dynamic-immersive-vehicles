package io.github.brooswitminecraft.div.experimental;
import org.junit.jupiter.api.Test;
import java.io.IOException;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

/** MINECRAFT-548: coverage for the pure decision logic {@code TuningCommandProbe} delegates to -
 * selecting its own spawned wrapper by actual identity (never the first candidate), bounded
 * waiting/timeout, and ownership cleanup orchestration (failure-isolated per resource, idempotent/
 * retryable repeat invocation, pre-existing-resource preservation, and a failed config snapshot
 * never being treated as something to restore). Review findings (Wroosbit, comment 54502 /
 * self-review, comment 54501) on this ticket's first revision are what this file's {@code
 * attemptCleanup}/{@code configNeedsRestore}-armed-before-write coverage specifically targets. */
class TuningCommandProbeOwnershipTest {

 // ---- selectOwnWrapper ----

 @Test void selectsTheMatchingCandidateEvenWhenUnrelatedEntitiesPrecedeIt() {
  Object own = new Object();
  List<String> candidates = List.of("unrelated-1", "unrelated-2", "mine", "unrelated-3");
  // "unrelated-*" unwrap to independent objects (never == own); only "mine" unwraps to own.
  java.util.function.Function<String, Object> unwrap = c -> c.equals("mine") ? own : new Object();
  String result = TuningCommandProbeOwnership.selectOwnWrapper(candidates, unwrap, own);
  assertEquals("mine", result, "must skip unrelated entities preceding the actual wrapper, not accept the first candidate");
 }

 @Test void returnsNullWhenNothingMatches() {
  Object own = new Object();
  List<String> candidates = List.of("unrelated-1", "unrelated-2");
  java.util.function.Function<String, Object> unwrap = c -> new Object();
  assertNull(TuningCommandProbeOwnership.selectOwnWrapper(candidates, unwrap, own));
 }

 @Test void returnsNullOnEmptyCandidates() {
  Object own = new Object();
  assertNull(TuningCommandProbeOwnership.selectOwnWrapper(List.<String>of(), c -> own, own));
 }

 @Test void unwrapReturningNullIsNeverMistakenForAMatch() {
  // An ineligible candidate (e.g. not a BuilderEntityExisting) unwraps to null - must never be
  // selected even if own itself happens to be null (it never legitimately is, but this is the
  // boundary this method must not get backwards).
  List<String> candidates = List.of("ineligible-1", "ineligible-2");
  assertNull(TuningCommandProbeOwnership.selectOwnWrapper(candidates, c -> null, new Object()));
 }

 // ---- pollBoundedWait ----

 @Test void continuesWhileNotReadyAndBelowMaxTicks() {
  assertEquals(TuningCommandProbeOwnership.AwaitOutcome.CONTINUE, TuningCommandProbeOwnership.pollBoundedWait(false, 5, 100));
 }

 @Test void timesOutWhenNotReadyAtMaxTicks() {
  assertEquals(TuningCommandProbeOwnership.AwaitOutcome.TIMED_OUT, TuningCommandProbeOwnership.pollBoundedWait(false, 100, 100));
 }

 @Test void readyWinsEvenAtExactlyMaxTicks() {
  assertEquals(TuningCommandProbeOwnership.AwaitOutcome.READY, TuningCommandProbeOwnership.pollBoundedWait(true, 100, 100));
 }

 @Test void readyWinsWellBeforeMaxTicks() {
  assertEquals(TuningCommandProbeOwnership.AwaitOutcome.READY, TuningCommandProbeOwnership.pollBoundedWait(true, 1, 100));
 }

 // ---- TuningCommandProbeOwnership: pre-existing resource preservation ----

 @Test void doesNotUnforceAChunkItNeverForced() {
  var ownership = new TuningCommandProbeOwnership();
  ownership.chunkForcedByUs = false; // pre-existing forced chunk - this run did not request it
  assertFalse(ownership.shouldUnforceChunk(), "must never unforce a chunk this run did not itself force");
 }

 @Test void doesNotRemovePackEntriesItNeverAdded() {
  var ownership = new TuningCommandProbeOwnership();
  ownership.packSpeedFactorAddedByUs = false;
  ownership.packVehicleScaleAddedByUs = false;
  assertFalse(ownership.shouldRemovePackSpeedFactor());
  assertFalse(ownership.shouldRemovePackVehicleScale());
 }

 @Test void unforcesOnlyWhatThisRunActuallyForced() {
  var ownership = new TuningCommandProbeOwnership();
  ownership.chunkForcedByUs = true;
  assertTrue(ownership.shouldUnforceChunk());
 }

 // ---- failed/unreliable config snapshot never becomes something to restore ----

 @Test void neverRestoresConfigThatWasNeverArmedBecauseItsSnapshotFailed() {
  var ownership = new TuningCommandProbeOwnership();
  // An unreliable snapshot read means the caller must abort before ever arming restoration or
  // writing - configNeedsRestore stays at its default false, exactly as the probe's own
  // execute() leaves it on that path.
  assertFalse(ownership.configNeedsRestore);
  assertFalse(ownership.shouldRestoreConfig(), "must never attempt to restore config this run never actually armed for restoration");
 }

 @Test void restoresConfigOnceArmedByTheCallerBeforeTheWrite() {
  var ownership = new TuningCommandProbeOwnership();
  ownership.configNeedsRestore = true; // armed BEFORE the write attempt, per the probe's own fix
  assertTrue(ownership.shouldRestoreConfig());
 }

 // ---- attemptCleanup: per-resource failure isolation ----

 @Test void attemptCleanupSkipsTheActionAndReturnsNullWhenNotOwned() {
  boolean[] ran = {false};
  Exception result = TuningCommandProbeOwnership.attemptCleanup(false, () -> ran[0] = true);
  assertNull(result);
  assertFalse(ran[0], "an unowned resource's action must never even be attempted");
 }

 @Test void attemptCleanupRunsTheActionAndReturnsNullOnSuccess() {
  boolean[] ran = {false};
  Exception result = TuningCommandProbeOwnership.attemptCleanup(true, () -> ran[0] = true);
  assertNull(result);
  assertTrue(ran[0]);
 }

 @Test void attemptCleanupCatchesAndReturnsTheExceptionInsteadOfPropagatingIt() {
  var thrown = new IOException("disk full");
  Exception result = TuningCommandProbeOwnership.attemptCleanup(true, () -> { throw thrown; });
  assertSame(thrown, result, "the exact caught exception must be returned so the caller can log/decide, never swallowed silently");
 }

 @Test void oneResourcesFailureDoesNotPreventAnotherResourcesOwnCleanupAttempt() {
  // Review finding (Wroosbit, comment 54502, finding 2): simulate cleaning up two independent
  // owned resources where the FIRST throws - the second's own action must still run.
  boolean[] secondRan = {false};
  Exception firstResult = TuningCommandProbeOwnership.attemptCleanup(true, () -> { throw new RuntimeException("boom"); });
  Exception secondResult = TuningCommandProbeOwnership.attemptCleanup(true, () -> secondRan[0] = true);
  assertNotNull(firstResult, "the first resource's own failure is reported");
  assertTrue(secondRan[0], "the second resource's own cleanup must still run even though the first one failed");
  assertNull(secondResult);
 }

 // ---- repeat invocation: failed resources retry, already-cleared resources are not repeated ----

 @Test void aFailedResourceIsRetriedOnTheNextCleanupPassWhileAnAlreadyClearedOneIsNotRepeated() {
  var ownership = new TuningCommandProbeOwnership();
  ownership.chunkForcedByUs = true;
  ownership.vehicleSpawned = true;
  int[] chunkAttempts = {0};
  int[] vehicleAttempts = {0};

  // Pass 1: chunk-unforce fails, vehicle-remove succeeds.
  Exception chunkEx1 = TuningCommandProbeOwnership.attemptCleanup(ownership.shouldUnforceChunk(), () -> { chunkAttempts[0]++; throw new RuntimeException("still locked"); });
  if (chunkEx1 == null) ownership.chunkForcedByUs = false;
  Exception vehicleEx1 = TuningCommandProbeOwnership.attemptCleanup(ownership.shouldRemoveVehicle(), () -> vehicleAttempts[0]++);
  if (vehicleEx1 == null) ownership.vehicleSpawned = false;

  assertTrue(ownership.shouldUnforceChunk(), "the failed resource must remain owned for a retry");
  assertFalse(ownership.shouldRemoveVehicle(), "the succeeded resource must be cleared");
  assertEquals(1, chunkAttempts[0]);
  assertEquals(1, vehicleAttempts[0]);

  // Pass 2 (repeat invocation): chunk-unforce succeeds this time; vehicle-remove must NOT be
  // attempted again since it is no longer owned.
  Exception chunkEx2 = TuningCommandProbeOwnership.attemptCleanup(ownership.shouldUnforceChunk(), () -> { chunkAttempts[0]++; });
  if (chunkEx2 == null) ownership.chunkForcedByUs = false;
  Exception vehicleEx2 = TuningCommandProbeOwnership.attemptCleanup(ownership.shouldRemoveVehicle(), () -> vehicleAttempts[0]++);
  if (vehicleEx2 == null) ownership.vehicleSpawned = false;

  assertEquals(2, chunkAttempts[0], "the previously-failed resource is retried on repeat invocation");
  assertEquals(1, vehicleAttempts[0], "an already-cleared resource must never be repeated");
  assertTrue(ownership.allCleared());
 }

 // ---- allCleared / partial setup ----

 @Test void allClearedIsFalseWhileAnyResourceIsStillOwned() {
  var ownership = new TuningCommandProbeOwnership();
  ownership.chunkForcedByUs = true;
  assertFalse(ownership.allCleared());
 }

 @Test void allClearedIsTrueWhenNothingWasEverOwned() {
  // A timeout in AWAIT_CHUNK before anything else ran: nothing was ever set up.
  var ownership = new TuningCommandProbeOwnership();
  assertTrue(ownership.allCleared());
  assertFalse(ownership.shouldUnforceChunk());
  assertFalse(ownership.shouldRemovePackSpeedFactor());
  assertFalse(ownership.shouldRemovePackVehicleScale());
  assertFalse(ownership.shouldRemoveVehicle());
  assertFalse(ownership.shouldRestoreConfig());
 }

 @Test void partialSetupOnlyCleansUpWhatActuallySucceeded() {
  // A setup/execute failure partway through: the chunk and pack entries were claimed, but the
  // vehicle never spawned (e.g. buildAndSpawn itself threw) and the config was never armed for
  // restoration (the failure happened before the snapshot ever succeeded).
  var ownership = new TuningCommandProbeOwnership();
  ownership.chunkForcedByUs = true;
  ownership.packSpeedFactorAddedByUs = true;
  ownership.packVehicleScaleAddedByUs = true;
  assertTrue(ownership.shouldUnforceChunk());
  assertTrue(ownership.shouldRemovePackSpeedFactor());
  assertTrue(ownership.shouldRemovePackVehicleScale());
  assertFalse(ownership.shouldRemoveVehicle(), "never spawned - must not attempt removal");
  assertFalse(ownership.shouldRestoreConfig(), "never armed - must not attempt restoration");
 }
}
