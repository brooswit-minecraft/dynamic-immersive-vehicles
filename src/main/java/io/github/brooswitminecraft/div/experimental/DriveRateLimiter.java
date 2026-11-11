package io.github.brooswitminecraft.div.experimental;

/**
 * Candidate control-layer throttle-rate limiter (ticket comments 53091/53096/53113) - NOT wired
 * into {@code PhysicsBridge}; diagnostic/test-only until a candidate is reviewed and explicitly
 * authorized. Caps how fast commanded drive force can increase in magnitude, in either direction;
 * releasing throttle (or braking - a separate, untouched channel) is never limited.
 *
 * <p>Forward and reverse are tracked as two independent non-negative "effort" accumulators rather
 * than one signed value, so a hard reversal (forward slam into reverse) is treated as what it
 * physically is - the reverse effort ramping up from zero - and gets the same rate limit as a
 * forward slam from a stop, instead of passing through unlimited just because the sign flipped.
 * A release in either direction (commanded effort in that direction drops below what's already
 * realized) is always instant - real throttle lift-off is not the destabilizing input, applying
 * power is (ticket comment 53113's real capture: forward pitch onset follows the tick79 0-&gt;8000N
 * command, not any release).
 *
 * <p>Units: {@code maxDeltaPerTick} is a true rate, newtons per tick (equivalently N/s at the
 * fixed 20tps tick rate) - NOT derived from the static wheelie-force threshold alone (comment
 * 53096 correctly rejected that: a force in newtons has no rate without an independent time
 * scale). The candidate time scale this is meant to be evaluated against comes from comparing
 * two actually-measured quantities instead: the real captured instant-throttle onset (comment
 * 53113: forward elevation -2.1{@literal @}79 -&gt; 25.2{@literal @}87, i.e. ~8 ticks from command to
 * a 27 degree pitch) against this investigation's own settled/ramped counterfactual (a 40-tick
 * ramp delayed front support loss from tick2 to tick22, comment 53088's successor test) - see
 * {@code ThrottleRateLimiterCandidateTest} for the actual comparison across several candidate
 * rates grounded in those two measurements.
 */
public final class DriveRateLimiter {
 private DriveRateLimiter() {}
 public static final class State {
  private double forward, reverse;
  public double forward() { return forward; }
  public double reverse() { return reverse; }
 }
 private static double ratchetUp(double previous, double commandedNonNegative, double maxDeltaPerTick) {
  if (commandedNonNegative <= previous) return commandedNonNegative; // release: instant, not limited
  return Math.min(commandedNonNegative, previous + maxDeltaPerTick);
 }
 /** Advances {@code state} by one tick toward {@code commanded} (signed: positive=forward,
  * negative=reverse) and returns the rate-limited signed result to actually apply this tick. */
 public static double apply(State state, double commanded, double maxDeltaPerTick) {
  double commandedForward = Math.max(commanded, 0), commandedReverse = Math.max(-commanded, 0);
  state.forward = ratchetUp(state.forward, commandedForward, maxDeltaPerTick);
  state.reverse = ratchetUp(state.reverse, commandedReverse, maxDeltaPerTick);
  return state.forward - state.reverse;
 }
}
