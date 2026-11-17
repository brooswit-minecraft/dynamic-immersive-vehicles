package io.github.brooswitminecraft.div;
import java.util.List;
import java.util.UUID;

/**
 * MINECRAFT-545/547 (John's comments 54595/54599/54600/54603): small, pure checks over the real
 * {@code debugStepInvocationCount}/{@code debugLastPoseUpdateGameTime} observables already added
 * to {@link BuoyantBody}/{@link PhysicsBridge.Body} - the "exactly-once per tick, surviving
 * towing removal, same body identity throughout" invariants this investigation's own lifecycle
 * questions reduce to. Diagnostic/test-only - never called from any production decision path.
 */
public final class LifecycleInvariants {
 private LifecycleInvariants() {}

 /** One real per-tick observation: the ENTITY's own identity ({@code bodyId}, e.g.
  * {@code v.uniqueUUID}), the real Minecraft tick it was taken at, that body's own
  * {@code debugStepInvocationCount} at that moment, and {@code registryBodyIdentity} - the
  * REGISTRY's own body-presence/generation marker ({@code PhysicsBridge.debugRegistryBodyIdentity},
  * {@code null} when no body is currently registered, otherwise the actual live body reference
  * registered for this entity right now - compared by the default reference-identity
  * {@code equals()}, since neither {@code Body} nor {@code BuoyantBody} overrides it). 54603
  * added {@code bodyId} to distinguish "this body genuinely was not stepped" from "this is
  * actually a different/replaced/missing body" - but 54616 (bug 2) found that check alone
  * insufficient: the ENTITY's own UUID never changes even when the underlying native body IS torn
  * down and reconstructed under that same entity, so two samples can share an identical
  * {@code bodyId} while actually straddling a real body swap. {@code registryBodyIdentity} is the
  * separate, correct observable for that - never collapsed into or substituted for the entity
  * UUID. CORRECTED (54635): an earlier version of this field was {@code int}
  * ({@code System.identityHashCode}), documented as collision-free - a false claim, since
  * {@code identityHashCode} is a hash, not a guaranteed-unique id. Holding the actual reference
  * instead is only safe because {@link #evaluate} runs within one bounded diagnostic capture
  * (one fixture run's own in-memory sample list) - never serialized, persisted across JVM runs,
  * or compared after GC could have reused an address; see {@code debugRegistryBodyIdentity}'s own
  * javadoc for that scope. */
 public record Sample(UUID bodyId, long gameTime, long stepInvocationCount, Object registryBodyIdentity) {}

 /** The real outcome of evaluating a sample sequence - not a bare boolean, so "could not be
  * checked" is never silently conflated with "checked and passed" (54603's own point).
  * {@code BODY_ABSENT} (54635, bug 2 follow-up) is a separate "nothing to check" outcome: two
  * consecutive samples where NO body is currently registered for this entity on either side are
  * not a stepped body failing to advance (that would be {@code FAIL_STARVED_TICK}, a real
  * defect) - there is no body at all, so there is nothing to have advanced or starved. Reporting
  * that as a stepping failure would misreport "this entity currently has no body" as a lifecycle
  * defect; {@code BODY_ABSENT} keeps it a distinct, correctly-named "could not be checked" case,
  * same spirit as {@code INSUFFICIENT_SAMPLES}/{@code BODY_IDENTITY_CHANGED}. A TRANSITION between
  * present and absent (one side has a body, the other does not) is still a real identity change,
  * reported as {@code BODY_IDENTITY_CHANGED} - not folded into {@code BODY_ABSENT}. */
 public enum Outcome { PASS, FAIL_STARVED_TICK, FAIL_DUPLICATE_TICK, INSUFFICIENT_SAMPLES, BODY_IDENTITY_CHANGED, BODY_ABSENT }

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

 /** The real production-used evaluation seam: takes a REAL sequence of {@link Sample}s (as
  * actually captured, one per real tick, in tick order - gaps/duplicates in {@code gameTime}
  * itself are a sampling problem, reported as {@code INSUFFICIENT_SAMPLES}, not silently
  * tolerated) and returns the precise {@link Outcome} - never a bare boolean. Distinguishes:
  * fewer than 2 samples or a non-consecutive {@code gameTime} sequence
  * ({@code INSUFFICIENT_SAMPLES} - the check itself could not be completed, not a pass); the
  * body's own identity changing mid-sequence ({@code BODY_IDENTITY_CHANGED} - a replaced/missing
  * body, where a {@code 0} count would otherwise be misread as starvation); a tick where the
  * count did not advance ({@code FAIL_STARVED_TICK}); a tick where it advanced by more than 1
  * ({@code FAIL_DUPLICATE_TICK}); or every consecutive pair advancing by exactly 1
  * ({@code PASS}). */
 public static Outcome evaluate(List<Sample> samples) {
  if (samples.size() < 2) return Outcome.INSUFFICIENT_SAMPLES;
  for (int i = 1; i < samples.size(); i++) {
   Sample prev = samples.get(i - 1), cur = samples.get(i);
   if (!prev.bodyId().equals(cur.bodyId())) return Outcome.BODY_IDENTITY_CHANGED;
   // 54635: BOTH sides absent is "nothing to check," not a stepping defect - checked BEFORE the
   // identity-change/delta checks below, so it is never misread as FAIL_STARVED_TICK (both
   // sides' debugStepInvocationCount would read 0, a zero delta, which IS the starvation
   // pattern's own signature when a body genuinely exists but isn't stepping).
   if (prev.registryBodyIdentity() == null && cur.registryBodyIdentity() == null) return Outcome.BODY_ABSENT;
   // 54616 (bug 2): the entity UUID check above cannot see a real body swap under the SAME
   // entity - registryBodyIdentity is the separate observable for that, compared by reference
   // identity (the default equals() for Body/BuoyantBody, neither of which overrides it). A
   // transition either INTO or OUT OF "no body registered" (null on exactly one side), or
   // between two different live instances, is a real body-presence/generation change, checked
   // before the step-count delta below so a swapped-in fresh body's own low/zero count is never
   // misread as this same body starving.
   if (!java.util.Objects.equals(prev.registryBodyIdentity(), cur.registryBodyIdentity())) return Outcome.BODY_IDENTITY_CHANGED;
   if (cur.gameTime() - prev.gameTime() != 1) return Outcome.INSUFFICIENT_SAMPLES;
   long delta = cur.stepInvocationCount() - prev.stepInvocationCount();
   if (delta == 0) return Outcome.FAIL_STARVED_TICK;
   if (delta > 1) return Outcome.FAIL_DUPLICATE_TICK;
   if (delta < 0) return Outcome.BODY_IDENTITY_CHANGED; // a counter that went backward is not this same, still-live body
  }
  return Outcome.PASS;
 }

 /** True only if the step-invocation count advanced by EXACTLY {@code expectedTicks} between two
  * samples - a necessary but NOT sufficient condition for exactly-once-per-tick (54600's own
  * correction: a 0-then-2 pair cancels to the same aggregate as two 1s). Use {@link #evaluate}
  * for the real per-tick/identity-aware guarantee; this remains useful as a cheap aggregate
  * pre-check when only two endpoint samples are available. {@code expectedTicks} must be &gt;= 0. */
 public static boolean advancedExactlyBy(long countBefore, long countAfter, long expectedTicks) {
  if (expectedTicks < 0) throw new IllegalArgumentException("expectedTicks must be >= 0: " + expectedTicks);
  return countAfter - countBefore == expectedTicks;
 }

 /** True only if the invocation count did not change at all between two samples - the specific
  * "starved for this entire span" symptom this investigation found and directly measured against
  * real retained logs (via the pre-existing {@code lastPoseUpdateGameTime} marker, which predates
  * this new counter - see VERIFICATION.md for the real, already-logged numbers). */
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
