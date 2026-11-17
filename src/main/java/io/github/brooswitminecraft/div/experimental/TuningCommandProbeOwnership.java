package io.github.brooswitminecraft.div.experimental;
import java.util.function.Function;

/**
 * MINECRAFT-548: the pure decision/bookkeeping logic {@code TuningCommandProbe} needs to (a)
 * select its own spawned vehicle's wrapping Minecraft entity by actual identity rather than
 * accepting the first unrelated entity visible in the level, and (b) track exactly which
 * resources THIS probe run itself created, so cleanup restores/removes only those - never a
 * pre-existing forced chunk, pack config entry, or operator config file this probe merely found
 * already in place.
 *
 * <p>Review findings (Wroosbit, comment 54502 / self-review, comment 54501) on this ticket's own
 * first revision: ownership for each resource is now a plain boolean that means "still needs
 * cleanup" - cleared only once {@link #attemptCleanup} actually completes that resource's own
 * action without throwing, never all-at-once after a single cleanup pass. A resource whose
 * action throws stays owned (so a LATER cleanup pass retries exactly that resource - "repeat
 * invocation" is therefore not merely safe but the actual retry mechanism), and - because {@link
 * #attemptCleanup} catches per-action, not around the whole sequence - one resource's failure
 * can never prevent or skip any other resource's own cleanup attempt in the same pass.
 *
 * <p>Deliberately no Minecraft/MTS dependency (same "extract the pure decision, leave the live-
 * world glue in the probe itself" convention as {@code DriveRateLimiter}/{@code AngleMath}
 * elsewhere in this package) - a live {@code TuningCommandProbe} run itself still cannot be unit
 * tested (constructing a real {@code EntityVehicleF_Physics}/Sable body remains infeasible, same
 * gap this class's own doc elsewhere names), but every decision this class makes is, including
 * the actual cleanup ORCHESTRATION (via {@link #attemptCleanup} with injected fake actions), not
 * merely its post-hoc boolean bookkeeping.
 */
final class TuningCommandProbeOwnership {
 boolean chunkForcedByUs;
 boolean packSpeedFactorAddedByUs;
 boolean packVehicleScaleAddedByUs;
 boolean vehicleSpawned;
 boolean configExistedBefore;
 String originalConfigContent;
 /** Armed BEFORE the config file is ever written (once its pre-probe snapshot has been read
  * reliably - see {@code TuningCommandProbe.execute}'s own doc), not after the write returns -
  * review finding (Wroosbit, comment 54502, finding 1): the old field was set only once {@code
  * Files.writeString} returned, so a write that threw partway through (a real partial/truncated
  * write) left this {@code false} and cleanup never attempted to restore the now-corrupted file
  * at all. Cleared only once {@link #attemptCleanup} confirms the restore/delete itself actually
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
