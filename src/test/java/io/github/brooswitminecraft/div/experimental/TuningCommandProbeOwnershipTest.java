package io.github.brooswitminecraft.div.experimental;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

/** MINECRAFT-548: coverage for the pure decision logic {@code TuningCommandProbe} delegates to -
 * selecting its own spawned wrapper by actual identity (never the first candidate), bounded
 * waiting/timeout, and ownership bookkeeping (idempotent cleanup, pre-existing-resource
 * preservation, and never restoring config that was never written because its snapshot failed). */
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

 // ---- failed config snapshot ----

 @Test void neverRestoresConfigThatWasNeverWrittenBecauseItsSnapshotFailed() {
  var ownership = new TuningCommandProbeOwnership();
  // A snapshot-read failure means the caller must abort before ever writing - configWasWritten
  // stays at its default false, exactly as the probe's own execute() leaves it on that path.
  assertFalse(ownership.configWasWritten);
  assertFalse(ownership.shouldRestoreConfig(), "must never attempt to restore config this run never actually wrote");
 }

 @Test void restoresConfigOnlyAfterItWasActuallyWritten() {
  var ownership = new TuningCommandProbeOwnership();
  ownership.configWasWritten = true;
  assertTrue(ownership.shouldRestoreConfig());
 }

 // ---- idempotent ownership cleanup ----

 @Test void markCleanedUpMakesEveryShouldQueryFalseRegardlessOfUnderlyingFlags() {
  var ownership = new TuningCommandProbeOwnership();
  ownership.chunkForcedByUs = true;
  ownership.packSpeedFactorAddedByUs = true;
  ownership.packVehicleScaleAddedByUs = true;
  ownership.vehicleSpawned = true;
  ownership.configWasWritten = true;
  assertTrue(ownership.shouldUnforceChunk());
  assertTrue(ownership.shouldRemovePackSpeedFactor());
  assertTrue(ownership.shouldRemovePackVehicleScale());
  assertTrue(ownership.shouldRemoveVehicle());
  assertTrue(ownership.shouldRestoreConfig());

  ownership.markCleanedUp();

  assertTrue(ownership.isCleanedUp());
  assertFalse(ownership.shouldUnforceChunk(), "a second cleanup pass must not double-unforce the chunk");
  assertFalse(ownership.shouldRemovePackSpeedFactor(), "a second cleanup pass must not double-remove the pack entry");
  assertFalse(ownership.shouldRemovePackVehicleScale());
  assertFalse(ownership.shouldRemoveVehicle(), "a second cleanup pass must not double-remove the vehicle");
  assertFalse(ownership.shouldRestoreConfig(), "a second cleanup pass must not re-attempt config restoration");
 }

 @Test void cleanupIsSafeWhenNothingWasEverOwned() {
  // A timeout in AWAIT_CHUNK before anything else ran: nothing was ever set up, so cleanup must
  // not try to restore/remove anything.
  var ownership = new TuningCommandProbeOwnership();
  assertFalse(ownership.shouldUnforceChunk());
  assertFalse(ownership.shouldRemovePackSpeedFactor());
  assertFalse(ownership.shouldRemovePackVehicleScale());
  assertFalse(ownership.shouldRemoveVehicle());
  assertFalse(ownership.shouldRestoreConfig());
  ownership.markCleanedUp();
  assertTrue(ownership.isCleanedUp());
 }

 @Test void partialSetupOnlyCleansUpWhatActuallySucceeded() {
  // A setup/execute failure partway through: the chunk and pack entries were claimed, but the
  // vehicle never spawned (e.g. buildAndSpawn itself threw) and the config was never written
  // (the failure happened before execute() ever wrote it).
  var ownership = new TuningCommandProbeOwnership();
  ownership.chunkForcedByUs = true;
  ownership.packSpeedFactorAddedByUs = true;
  ownership.packVehicleScaleAddedByUs = true;
  assertTrue(ownership.shouldUnforceChunk());
  assertTrue(ownership.shouldRemovePackSpeedFactor());
  assertTrue(ownership.shouldRemovePackVehicleScale());
  assertFalse(ownership.shouldRemoveVehicle(), "never spawned - must not attempt removal");
  assertFalse(ownership.shouldRestoreConfig(), "never written - must not attempt restoration");
 }
}
