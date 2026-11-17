package io.github.brooswitminecraft.div.experimental;
import org.junit.jupiter.api.Test;
import java.io.IOException;
import java.nio.file.NoSuchFileException;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

/** MINECRAFT-548: coverage for the pure decision logic {@code TuningCommandProbe} delegates to -
 * selecting its own spawned wrapper by actual identity (never the first candidate), bounded
 * waiting/timeout, and ownership cleanup orchestration. Review findings (Wroosbit, comments
 * 54502/54507; self-review, comment 54501) and John's coordination readback (comment 54506) on
 * this ticket's earlier revisions: tests below call {@link TuningCommandProbeOwnership#snapshotAndArm}
 * and {@link TuningCommandProbeOwnership#runCleanupPass} DIRECTLY - the exact same two methods
 * {@code TuningCommandProbe.execute}/{@code .cleanup} themselves call with the real file/vehicle/
 * level callbacks - with fake callbacks standing in for those same seams, never a reimplementation
 * of their if/flag branches inline in a test. A wiring mistake in either production method (arm-
 * after-write instead of before, an ignored unsuccessful reload/unforce result, a resource's flag
 * cleared regardless of its own action's outcome) would fail these tests directly. */
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

 // ---- snapshotAndArm: the exact seam TuningCommandProbe.execute() calls ----

 @Test void confirmedPresentSnapshotArmsRestorationBeforeAnyWrite() {
  var ownership = new TuningCommandProbeOwnership();
  boolean canProceed = ownership.snapshotAndArm(() -> "pre-existing content");
  assertTrue(canProceed, "a reliable snapshot must let the caller proceed to write");
  assertTrue(ownership.configExistedBefore);
  assertEquals("pre-existing content", ownership.originalConfigContent);
  assertTrue(ownership.shouldRestoreConfig(), "restoration must be armed by snapshotAndArm itself, before the caller ever writes anything");
 }

 @Test void confirmedAbsentSnapshotArmsRestorationToo() {
  var ownership = new TuningCommandProbeOwnership();
  boolean canProceed = ownership.snapshotAndArm(() -> { throw new NoSuchFileException("dynamic-immersive-vehicles.json"); });
  assertTrue(canProceed, "a confirmed absence is a RELIABLE snapshot - the caller may still proceed to write");
  assertFalse(ownership.configExistedBefore);
  assertNull(ownership.originalConfigContent);
  assertTrue(ownership.shouldRestoreConfig(), "even 'confirmed absent' must arm restoration, so cleanup deletes what this run writes");
 }

 @Test void unreliableSnapshotFailureNeverArmsRestorationAndTellsTheCallerNotToProceed() {
  // Review finding (self-review, comment 54501): an IOException that is NOT a confirmed absence
  // (permission denied, I/O error, etc) must never be treated as "the file didn't exist".
  var ownership = new TuningCommandProbeOwnership();
  boolean canProceed = ownership.snapshotAndArm(() -> { throw new IOException("permission denied"); });
  assertFalse(canProceed, "an unreliable read must tell the caller to abort WITHOUT writing anything");
  assertFalse(ownership.shouldRestoreConfig(), "must never arm restoration for a snapshot this run could not reliably take");
 }

 // ---- runCleanupPass: the exact seam TuningCommandProbe.cleanup() calls ----

 @Test void runCleanupPassAttemptsOnlyOwnedResourcesAndClearsThemOnSuccess() {
  var ownership = new TuningCommandProbeOwnership();
  ownership.configNeedsRestore = true;
  ownership.vehicleSpawned = true;
  ownership.chunkForcedByUs = true;
  ownership.packSpeedFactorAddedByUs = true;
  ownership.packVehicleScaleAddedByUs = true;
  boolean[] ran = new boolean[5];

  var result = ownership.runCleanupPass(
    () -> ran[0] = true,
    () -> ran[1] = true,
    () -> ran[2] = true,
    () -> ran[3] = true,
    () -> ran[4] = true);

  assertNull(result.configEx()); assertNull(result.vehicleEx()); assertNull(result.chunkEx());
  assertNull(result.speedFactorEx()); assertNull(result.vehicleScaleEx());
  for (boolean r : ran) assertTrue(r, "every owned resource's own action must actually run");
  assertTrue(ownership.allCleared(), "every resource must be cleared once its own action succeeds");
 }

 @Test void runCleanupPassNeverAttemptsAnUnownedResourcesAction() {
  var ownership = new TuningCommandProbeOwnership(); // nothing owned - e.g. a timeout before setup
  boolean[] ran = new boolean[5];
  ownership.runCleanupPass(() -> ran[0] = true, () -> ran[1] = true, () -> ran[2] = true, () -> ran[3] = true, () -> ran[4] = true);
  for (boolean r : ran) assertFalse(r, "an unowned resource's action must never be attempted");
  assertTrue(ownership.allCleared());
 }

 @Test void oneOwnedResourcesFailureDoesNotPreventAnotherOwnedResourcesOwnCleanupAttempt() {
  // Review finding (Wroosbit, comment 54502, finding 2): simulate the config-restore action
  // failing - the vehicle removal (and every other owned action) must still run this same pass.
  var ownership = new TuningCommandProbeOwnership();
  ownership.configNeedsRestore = true;
  ownership.vehicleSpawned = true;
  boolean[] vehicleRan = {false};

  var result = ownership.runCleanupPass(
    () -> { throw new IOException("disk full"); },
    () -> vehicleRan[0] = true,
    () -> {}, () -> {}, () -> {});

  assertNotNull(result.configEx(), "the failing resource's own failure is reported");
  assertTrue(vehicleRan[0], "an unrelated owned resource's own action must still run despite the first one's failure");
  assertTrue(ownership.shouldRestoreConfig(), "the failed resource must remain owned for a retry");
  assertFalse(ownership.shouldRemoveVehicle(), "the succeeded resource must be cleared");
 }

 @Test void aFailedResourceIsRetriedOnTheNextCleanupPassWhileAnAlreadyClearedOneIsNotRepeated() {
  var ownership = new TuningCommandProbeOwnership();
  ownership.chunkForcedByUs = true;
  ownership.vehicleSpawned = true;
  int[] chunkAttempts = {0};
  int[] vehicleAttempts = {0};

  // Pass 1: chunk-unforce fails, vehicle-remove succeeds.
  var result1 = ownership.runCleanupPass(
    () -> {}, () -> vehicleAttempts[0]++,
    () -> { chunkAttempts[0]++; throw new IOException("still locked"); },
    () -> {}, () -> {});
  assertNotNull(result1.chunkEx());
  assertNull(result1.vehicleEx());
  assertTrue(ownership.shouldUnforceChunk(), "the failed resource must remain owned for a retry");
  assertFalse(ownership.shouldRemoveVehicle(), "the succeeded resource must be cleared");
  assertEquals(1, chunkAttempts[0]);
  assertEquals(1, vehicleAttempts[0]);

  // Pass 2 (repeat invocation): chunk-unforce succeeds this time; vehicle-remove must NOT be
  // attempted again since it is no longer owned.
  var result2 = ownership.runCleanupPass(
    () -> {}, () -> vehicleAttempts[0]++,
    () -> chunkAttempts[0]++,
    () -> {}, () -> {});
  assertNull(result2.chunkEx());
  assertEquals(2, chunkAttempts[0], "the previously-failed resource is retried on repeat invocation");
  assertEquals(1, vehicleAttempts[0], "an already-cleared resource must never be repeated");
  assertTrue(ownership.allCleared());
 }

 @Test void runCleanupPassWiredThroughTheSameOwnershipFlagsAsSnapshotAndArm() {
  // End-to-end through the two real production seams together: a reliable snapshot arms
  // restoration, a successful restore clears it - exactly the sequence execute() then cleanup()
  // drive the real probe through, with fakes standing in for the file I/O.
  var ownership = new TuningCommandProbeOwnership();
  assertTrue(ownership.snapshotAndArm(() -> "original"));
  assertTrue(ownership.shouldRestoreConfig());

  var result = ownership.runCleanupPass(() -> {}, () -> {}, () -> {}, () -> {}, () -> {});
  assertNull(result.configEx());
  assertFalse(ownership.shouldRestoreConfig(), "restoration must be cleared once its own action succeeds");
 }

 // ---- pre-existing resource preservation ----

 @Test void doesNotUnforceAChunkItNeverForced() {
  var ownership = new TuningCommandProbeOwnership();
  ownership.chunkForcedByUs = false; // pre-existing forced chunk - this run did not request it
  boolean[] ran = {false};
  ownership.runCleanupPass(() -> {}, () -> {}, () -> ran[0] = true, () -> {}, () -> {});
  assertFalse(ran[0], "must never unforce a chunk this run did not itself force");
 }

 @Test void doesNotRemovePackEntriesItNeverAdded() {
  var ownership = new TuningCommandProbeOwnership();
  boolean[] ran = {false, false};
  ownership.runCleanupPass(() -> {}, () -> {}, () -> {}, () -> ran[0] = true, () -> ran[1] = true);
  assertFalse(ran[0]); assertFalse(ran[1]);
 }

 // ---- allCleared / partial setup ----

 @Test void allClearedIsFalseWhileAnyResourceIsStillOwned() {
  var ownership = new TuningCommandProbeOwnership();
  ownership.chunkForcedByUs = true;
  assertFalse(ownership.allCleared());
 }

 @Test void allClearedIsTrueWhenNothingWasEverOwned() {
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
  // restoration (the failure happened before snapshotAndArm ever succeeded).
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
