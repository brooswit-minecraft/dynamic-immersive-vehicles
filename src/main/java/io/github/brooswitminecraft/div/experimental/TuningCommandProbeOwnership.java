package io.github.brooswitminecraft.div.experimental;
import java.io.IOException;
import java.nio.file.NoSuchFileException;
import java.util.function.Function;

/**
 * MINECRAFT-548: the pure decision/bookkeeping logic {@code TuningCommandProbe} needs to (a)
 * select its own spawned vehicle's wrapping Minecraft entity by actual identity rather than
 * accepting the first unrelated entity visible in the level, and (b) track exactly which
 * resources THIS probe run itself created, so cleanup restores/removes only those - never a
 * pre-existing forced chunk, pack config entry, or operator config file this probe merely found
 * already in place.
 *
 * <p>Review findings (Wroosbit, comment 54502 / self-review, comment 54501) on this ticket's
 * first revision, and John's coordination readback (comment 54506) on the second: the actual
 * ORCHESTRATION - not merely its post-hoc boolean bookkeeping - must be covered by tests calling
 * the SAME production-used method {@code TuningCommandProbe} itself calls, never a reimplemented
 * copy of its if/flag logic sitting only in a test. {@link #snapshotAndArm} and {@link
 * #runCleanupPass} are exactly that: {@code TuningCommandProbe.execute}/{@code .cleanup} call
 * them with the REAL file/vehicle/level callbacks; {@code
 * TuningCommandProbeOwnershipTest} calls the identical methods with fake callbacks standing in
 * for those same seams - so a wiring mistake in either method is caught by both, not only
 * exercised informally through CI running the live probe (which this ticket's own scope keeps
 * out of reach for any unit test).
 *
 * <p>Ownership for each resource is a plain boolean meaning "still needs cleanup" - cleared only
 * once {@link #attemptCleanup} (called from inside {@link #runCleanupPass}) actually completes
 * that resource's own action without throwing, never all-at-once. A resource whose action throws
 * stays owned, so a LATER {@link #runCleanupPass} call retries exactly that resource - "repeat
 * invocation" is therefore not merely safe but the actual retry mechanism - and, because each
 * resource's action is wrapped independently, one resource's failure can never prevent or skip
 * any other resource's own cleanup attempt in the same pass.
 *
 * <p>Deliberately no Minecraft/MTS dependency (same "extract the pure decision, leave the live-
 * world glue in the probe itself" convention as {@code DriveRateLimiter}/{@code AngleMath}
 * elsewhere in this package) - a live {@code TuningCommandProbe} run itself still cannot be unit
 * tested (constructing a real {@code EntityVehicleF_Physics}/Sable body remains infeasible, same
 * gap this class's own doc elsewhere names), but every decision this class makes is, through the
 * exact seams production itself calls.
 */
final class TuningCommandProbeOwnership {
 boolean chunkForcedByUs;
 boolean packSpeedFactorAddedByUs;
 boolean packVehicleScaleAddedByUs;
 boolean vehicleSpawned;
 boolean configExistedBefore;
 String originalConfigContent;
 /** Armed BEFORE the config file is ever written (once its pre-probe snapshot has been read
  * reliably - see {@link #snapshotAndArm}), not after the write returns - review finding
  * (Wroosbit, comment 54502, finding 1): the old field was set only once {@code
  * Files.writeString} returned, so a write that threw partway through (a real partial/truncated
  * write) left this {@code false} and cleanup never attempted to restore the now-corrupted file
  * at all. Cleared only once {@link #runCleanupPass} confirms the restore/delete itself actually
  * succeeded. */
 boolean configNeedsRestore;

 boolean shouldUnforceChunk() { return chunkForcedByUs; }
 boolean shouldRemovePackSpeedFactor() { return packSpeedFactorAddedByUs; }
 boolean shouldRemovePackVehicleScale() { return packVehicleScaleAddedByUs; }
 boolean shouldRemoveVehicle() { return vehicleSpawned; }
 boolean shouldRestoreConfig() { return configNeedsRestore; }
 /** True once every owned resource has actually been cleared (restored/removed), independent of
  * which order they finished in or how many attempts it took. */
 boolean allCleared() {
  return !chunkForcedByUs && !packSpeedFactorAddedByUs && !packVehicleScaleAddedByUs
    && !vehicleSpawned && !configNeedsRestore;
 }

 /** The live crossing {@link #snapshotAndArm} needs to read the pre-probe config file's content -
  * a plain function so this class needs no {@code java.nio.file.Path}/{@code Files} dependency;
  * production passes {@code () -> Files.readString(configPath)}, a test passes a fake that
  * returns canned content or throws a canned exception. */
 interface ConfigReader { String read() throws IOException; }

 /**
  * The exact snapshot-then-arm decision {@code TuningCommandProbe.execute} calls through this
  * one method (never a reimplementation of its branches in a test - John's coordination readback,
  * comment 54506). Distinguishes a confirmed {@link NoSuchFileException} (the file genuinely does
  * not exist - safe to proceed, {@code configExistedBefore=false}) from any other {@link
  * IOException} (an UNRELIABLE read - permission denied, I/O error, etc; self-review finding,
  * comment 54501: {@code Files.exists()==false} cannot distinguish these two, and treating the
  * latter as "did not exist" risked overwriting, then later deleting, a real pre-existing file
  * this probe never actually backed up).
  *
  * <p>On a reliable snapshot (confirmed present OR confirmed absent), arms {@link
  * #configNeedsRestore} immediately, BEFORE the caller ever writes anything (review finding,
  * comment 54502, finding 1) - so a write that throws partway through is still followed by a real
  * restoration attempt in {@link #runCleanupPass} using the content captured here.
  *
  * @return true if the caller may proceed to write (snapshot was reliable); false if the caller
  * MUST abort without writing anything (the read was unreliable, not a confirmed absence).
  */
 boolean snapshotAndArm(ConfigReader reader) {
  try {
   originalConfigContent = reader.read();
   configExistedBefore = true;
  } catch (NoSuchFileException notFound) {
   configExistedBefore = false;
   originalConfigContent = null;
  } catch (IOException ex) {
   return false;
  }
  configNeedsRestore = true;
  return true;
 }

 /** A single resource's own cleanup action - may throw any checked or unchecked exception; the
  * caller never needs to know which. */
 interface CleanupAction { void run() throws Exception; }

 /**
  * Runs {@code action} only if {@code owned} is true, catching EVERY exception it throws - never
  * propagating one, so a failure cleaning up one resource can never block or skip any other
  * resource's own cleanup attempt in the same pass (review finding, comment 54502, finding 2:
  * the old code ran all four actions inline with no per-action isolation, so an uncaught
  * exception from an earlier one aborted the whole method before any later one ever ran).
  *
  * @return {@code null} when there was nothing owned to do, or {@code action} completed without
  * throwing - the caller should clear this resource's own ownership flag in either case. Returns
  * the caught exception when {@code action} was actually attempted and failed - the caller MUST
  * leave ownership exactly as it was, so a later cleanup pass retries this same resource (and,
  * since every other resource's own flag is handled by its own independent call, does not
  * re-attempt anything that already succeeded).
  */
 static Exception attemptCleanup(boolean owned, CleanupAction action) {
  if (!owned) return null;
  try {
   action.run();
   return null;
  } catch (Exception ex) {
   return ex;
  }
 }

 /** One {@link #runCleanupPass} call's outcome per resource - {@code null} means that resource's
  * ownership was cleared this pass (either it was not owned, or its action just succeeded);
  * non-null is the caught exception, meaning that resource remains owned for a retry. */
 record CleanupPassResult(Exception configEx, Exception vehicleEx, Exception chunkEx, Exception speedFactorEx, Exception vehicleScaleEx) {}

 /**
  * The exact cleanup ORCHESTRATION {@code TuningCommandProbe.cleanup} calls through this one
  * method every tick while any resource remains owned (never a reimplementation of its
  * attemptCleanup-then-clear-the-flag sequence in a test - John's coordination readback, comment
  * 54506): attempts every owned resource's own action independently via {@link #attemptCleanup},
  * clearing only the resources whose own action actually succeeds this pass, so a failing
  * resource is retried on the NEXT call while an already-cleared one is never repeated (review
  * finding, comment 54502, finding 2). Production passes the real file/vehicle/level callbacks;
  * a test passes fakes for the exact same five parameters.
  */
 CleanupPassResult runCleanupPass(CleanupAction restoreConfig, CleanupAction removeVehicle,
     CleanupAction unforceChunk, CleanupAction removePackSpeedFactor, CleanupAction removePackVehicleScale) {
  var configEx = attemptCleanup(configNeedsRestore, restoreConfig);
  if (configEx == null) configNeedsRestore = false;

  var vehicleEx = attemptCleanup(vehicleSpawned, removeVehicle);
  if (vehicleEx == null) vehicleSpawned = false;

  var chunkEx = attemptCleanup(chunkForcedByUs, unforceChunk);
  if (chunkEx == null) chunkForcedByUs = false;

  var speedFactorEx = attemptCleanup(packSpeedFactorAddedByUs, removePackSpeedFactor);
  if (speedFactorEx == null) packSpeedFactorAddedByUs = false;

  var vehicleScaleEx = attemptCleanup(packVehicleScaleAddedByUs, removePackVehicleScale);
  if (vehicleScaleEx == null) packVehicleScaleAddedByUs = false;

  return new CleanupPassResult(configEx, vehicleEx, chunkEx, speedFactorEx, vehicleScaleEx);
 }

 /**
  * Scans {@code candidates} in order and returns the first one whose {@code unwrap} result is
  * reference-identical to {@code own} - never the first candidate outright. {@code unwrap} is the
  * live crossing from a Minecraft-side wrapper to the MTS entity it actually wraps (production:
  * {@code TuningCommand.wrappedMtsEntity}'s own reflective field read, duplicated narrowly in
  * {@code TuningCommandProbe} since that production accessor is package-private to {@code
  * io.github.brooswitminecraft.div}, not this experimental sub-package); here it is a plain
  * function so this method needs no Minecraft/MTS type at all. Returns {@code null} if nothing
  * matches - including an empty list, or a list of only unrelated candidates (an ineligible/
  * unrelated level entity {@code unwrap}s to {@code null} or to some other object, never to
  * {@code own}).
  */
 static <E> E selectOwnWrapper(Iterable<E> candidates, Function<E, Object> unwrap, Object own) {
  for (E candidate : candidates) {
   if (unwrap.apply(candidate) == own) return candidate;
  }
  return null;
 }

 enum AwaitOutcome { CONTINUE, READY, TIMED_OUT }
 /** One tick's worth of a bounded wait: {@code ticksInPhaseAfterIncrement} is the counter value
  * AFTER this tick's own increment (matching the {@code ++ticksInPhase} call sites in {@code
  * TuningCommandProbe}), so a caller passes exactly what it already computed rather than this
  * method re-deriving it. {@code ready} wins unconditionally - a readiness signal observed on the
  * very same tick the bound would otherwise have been hit still reports {@link
  * AwaitOutcome#READY}, never {@link AwaitOutcome#TIMED_OUT}. */
 static AwaitOutcome pollBoundedWait(boolean ready, int ticksInPhaseAfterIncrement, int maxTicks) {
  if (ready) return AwaitOutcome.READY;
  return ticksInPhaseAfterIncrement >= maxTicks ? AwaitOutcome.TIMED_OUT : AwaitOutcome.CONTINUE;
 }
}
