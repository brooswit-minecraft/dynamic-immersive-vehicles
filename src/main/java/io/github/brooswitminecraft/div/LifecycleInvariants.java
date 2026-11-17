package io.github.brooswitminecraft.div;

/**
 * MINECRAFT-545/547 (John's comments 54595/54599/54600): small, pure checks over the real
 * {@code debugStepInvocationCount}/{@code debugLastPoseUpdateGameTime} observables already added
 * to {@link BuoyantBody}/{@link PhysicsBridge.Body} - the "exactly-once per tick" and "survives
 * across N ticks without starving" invariants this investigation's own lifecycle questions
 * reduce to. Diagnostic/test-only - never called from any production decision path.
 *
 * <p>Corrected (54600): an AGGREGATE delta check (total advance == total elapsed ticks) does
 * NOT prove exactly-once-per-tick - a sequence advancing by 0 on one tick and 2 on another has
 * the same aggregate delta as advancing by 1 every tick, while being a genuine mixed starvation/
 * duplication failure. {@link #exactlyOncePerTick} checks the REAL per-tick sequence instead.
 */
public final class LifecycleInvariants {
 private LifecycleInvariants() {}

 /** True only if EVERY consecutive pair in {@code counts} (one sample per real tick, in tick
  * order) advances by exactly 1 - the genuine per-tick "no starvation, no duplicate stepping"
  * check. Requires at least 2 samples (otherwise there is no transition to check, and this
  * returns {@code true} vacuously - 0 or 1 samples assert nothing about any transition). */
 public static boolean exactlyOncePerTick(long[] counts) {
  for (int i = 1; i < counts.length; i++) {
   if (counts[i] - counts[i - 1] != 1) return false;
  }
  return true;
 }

 /** True only if the step-invocation count advanced by EXACTLY {@code expectedTicks} between two
  * samples - a necessary but NOT sufficient condition for exactly-once-per-tick (54600's own
  * correction: a 0-then-2 pair cancels to the same aggregate as two 1s). Use
  * {@link #exactlyOncePerTick} for the real per-tick guarantee; this remains useful as a cheap
  * aggregate pre-check (e.g. "did ANY stepping happen at all") when only two samples are
  * available. {@code expectedTicks} must be &gt;= 0. */
 public static boolean advancedExactlyBy(long countBefore, long countAfter, long expectedTicks) {
  if (expectedTicks < 0) throw new IllegalArgumentException("expectedTicks must be >= 0: " + expectedTicks);
  return countAfter - countBefore == expectedTicks;
 }

 /** True only if the invocation count did not change at all between two samples - the specific
  * "starved for this entire span" symptom this investigation found and directly measured against
  * real retained logs (via the pre-existing {@code lastPoseUpdateGameTime} marker, which predates
  * this new counter - see VERIFICATION.md for the real, already-logged numbers; no counter
  * samples existed historically, since the counter is new in this change). */
 public static boolean starvedAcrossSpan(long countBefore, long countAfter) {
  return countAfter == countBefore;
 }

 /** True only if the count advanced by MORE than the number of real ticks elapsed - duplicate/
  * double stepping within at least one tick, the specific failure mode the raw
  * {@code debugLastPoseUpdateGameTime} timestamp alone cannot detect (54596's own correction). */
 public static boolean duplicateSteppingOccurred(long countBefore, long countAfter, long realTicksElapsed) {
  if (realTicksElapsed < 0) throw new IllegalArgumentException("realTicksElapsed must be >= 0: " + realTicksElapsed);
  return countAfter - countBefore > realTicksElapsed;
 }
}
