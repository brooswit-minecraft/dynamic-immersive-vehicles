package io.github.brooswitminecraft.div.experimental;
import java.util.function.Function;

/**
 * MINECRAFT-548: the pure decision/bookkeeping logic {@code TuningCommandProbe} needs to (a)
 * select its own spawned vehicle's wrapping Minecraft entity by actual identity rather than
 * accepting the first unrelated entity visible in the level, and (b) track exactly which
 * resources THIS probe run itself created, so cleanup restores/removes only those - never a
 * pre-existing forced chunk, pack config entry, or operator config file this probe merely found
 * already in place - and is safe to invoke more than once ({@link #markCleanedUp()} makes every
 * {@code should*} query false afterward, regardless of the underlying ownership flags).
 *
 * <p>Deliberately no Minecraft/MTS dependency (same "extract the pure decision, leave the live-
 * world glue in the probe itself" convention as {@code DriveRateLimiter}/{@code AngleMath}
 * elsewhere in this package) - a live {@code TuningCommandProbe} run itself still cannot be unit
 * tested (constructing a real {@code EntityVehicleF_Physics}/Sable body remains infeasible, same
 * gap this class's own doc elsewhere names), but every decision this class makes is.
 */
final class TuningCommandProbeOwnership {
 boolean chunkForcedByUs;
 boolean packSpeedFactorAddedByUs;
 boolean packVehicleScaleAddedByUs;
 boolean vehicleSpawned;
 boolean configExistedBefore;
 String originalConfigContent;
 boolean configWasWritten;
 private boolean cleanedUp;

 boolean shouldUnforceChunk() { return !cleanedUp && chunkForcedByUs; }
 boolean shouldRemovePackSpeedFactor() { return !cleanedUp && packSpeedFactorAddedByUs; }
 boolean shouldRemovePackVehicleScale() { return !cleanedUp && packVehicleScaleAddedByUs; }
 boolean shouldRemoveVehicle() { return !cleanedUp && vehicleSpawned; }
 boolean shouldRestoreConfig() { return !cleanedUp && configWasWritten; }
 boolean isCleanedUp() { return cleanedUp; }
 /** Idempotency guard - every {@code should*} query above returns {@code false} once this has
  * been called, regardless of how many times it is called again or what the underlying ownership
  * flags still say, so a caller that (for whatever reason) reaches cleanup more than once never
  * double-acts (double-unforcing a chunk, double-removing an already-removed vehicle, etc). */
 void markCleanedUp() { cleanedUp = true; }

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
