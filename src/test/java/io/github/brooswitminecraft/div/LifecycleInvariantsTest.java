package io.github.brooswitminecraft.div;
import io.github.brooswitminecraft.div.LifecycleInvariants.Outcome;
import io.github.brooswitminecraft.div.LifecycleInvariants.Sample;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

/**
 * MINECRAFT-545/547 (John's comments 54595/54599/54600/54603): pure coverage of the invocation-
 * count invariants AND the real production-used {@code evaluate} seam - no ServerLevel/
 * Minecraft dependency, since these are plain data/arithmetic.
 *
 * <p>Corrected (54600): all sample values below are SYNTHETIC/illustrative, not actual retained
 * measurements - {@code debugStepInvocationCount} is new in this change and never existed in any
 * previously-captured log. The real, already-measured number from this investigation's own
 * retained logs is a DIFFERENT field, {@code lastPoseUpdateGameTime}, frozen at 203 across the
 * full removal-to-reconnect span - cited in VERIFICATION.md, not restated here as if it were a
 * counter sample.
 */
class LifecycleInvariantsTest {
 private static final UUID BODY_A = UUID.fromString("00000000-0000-0000-0000-00000000000a");
 private static final UUID BODY_B = UUID.fromString("00000000-0000-0000-0000-00000000000b");

 // Default registryBodyIdentity (1) stands in for "the same still-registered body instance" -
 // every test below that is not specifically about a body swap uses this same constant for every
 // sample, so the new registryBodyIdentity check never fires where it isn't the point of the test.
 private static final int SAME_BODY = 1, DIFFERENT_BODY = 2, NO_BODY = 0;
 private static Sample s(UUID body, long gameTime, long count) { return new Sample(body, gameTime, count, SAME_BODY); }
 private static Sample s(UUID body, long gameTime, long count, int registryBodyIdentity) { return new Sample(body, gameTime, count, registryBodyIdentity); }

 @Test void evaluatePassForAPerfectPerTickSequence() {
  assertEquals(Outcome.PASS, LifecycleInvariants.evaluate(List.of(
    s(BODY_A, 100, 10), s(BODY_A, 101, 11), s(BODY_A, 102, 12))));
 }

 @Test void evaluateFailStarvedTickForTheExactBalancedZeroThenTwoCase() {
  // 54600's own flagged case: aggregate delta (10->12 over two ticks) matches a perfect
  // sequence, but the REAL per-tick sequence shows tick 101 starved (0 advance) then tick 102
  // duplicated (+2) - a genuine mixed failure the aggregate check alone would miss.
  var samples = List.of(s(BODY_A, 100, 10), s(BODY_A, 101, 10), s(BODY_A, 102, 12));
  assertEquals(Outcome.FAIL_STARVED_TICK, LifecycleInvariants.evaluate(samples));
  // The aggregate check alone would still (correctly, if insufficiently) pass this exact case -
  // demonstrating precisely why evaluate's own per-tick walk is needed.
  assertTrue(LifecycleInvariants.advancedExactlyBy(10, 12, 2));
 }

 @Test void evaluateFailDuplicateTickWhenASingleTickAdvancesByMoreThanOne() {
  assertEquals(Outcome.FAIL_DUPLICATE_TICK, LifecycleInvariants.evaluate(List.of(
    s(BODY_A, 100, 10), s(BODY_A, 101, 12))));
 }

 @Test void evaluateInsufficientForSparseNonConsecutiveGameTimeSamples() {
  // A real gap in the SAMPLED gameTime sequence itself (e.g. a tick's own sample was missed) -
  // not a starvation/duplication finding, a sampling-completeness problem, reported distinctly.
  assertEquals(Outcome.INSUFFICIENT_SAMPLES, LifecycleInvariants.evaluate(List.of(
    s(BODY_A, 100, 10), s(BODY_A, 103, 13))));
 }

 @Test void evaluateInsufficientForFewerThanTwoSamples() {
  assertEquals(Outcome.INSUFFICIENT_SAMPLES, LifecycleInvariants.evaluate(List.of()));
  assertEquals(Outcome.INSUFFICIENT_SAMPLES, LifecycleInvariants.evaluate(List.of(s(BODY_A, 100, 10))));
 }

 @Test void evaluateBodyIdentityChangedForAReplacedOrMissingBody() {
  // A missing body's own debugStepInvocationCount accessor returns 0 (per PhysicsBridge's own
  // doc) - indistinguishable from a real zero-advance sample unless identity is checked too.
  // Here BODY_B's own count (0) would otherwise look like a starved tick for BODY_A; checking
  // identity first correctly reports a body swap instead of a false starvation finding.
  assertEquals(Outcome.BODY_IDENTITY_CHANGED, LifecycleInvariants.evaluate(List.of(
    s(BODY_A, 100, 10), s(BODY_B, 101, 0))));
 }

 @Test void evaluateReportsInsufficientForTheExactPreFixDuplicateFirstSamplePattern() {
  // MINECRAFT-545/547 (John's comment 54616, bug 1): BuoyantHitchProbe's own towingUnavailable()
  // used to append TWO samples on its first call - one right after towing.remove() (inside the
  // ticksInPhase==1 block), then ANOTHER in the unconditional per-tick block that also runs for
  // ticksInPhase==1 - both at the identical gameTime, since no real tick elapses between them.
  // evaluate() itself was never wrong: a zero gameTime delta IS a non-consecutive pair, correctly
  // INSUFFICIENT_SAMPLES - but that correct-and-immediate verdict made the real per-tick sequence
  // after it unreachable, since the caller stopped (or callers reading this outcome would stop)
  // trusting the check at the very first pair. This test pins that exact pre-fix shape so a
  // regression reintroducing the duplicate append is caught here, not only by re-reading logs.
  var preFixDuplicateFirstSample = List.of(
    s(BODY_A, 100, 10), // appended immediately after towing.remove()
    s(BODY_A, 100, 10), // appended again by the unconditional per-tick block, same call
    s(BODY_A, 101, 11), s(BODY_A, 102, 12)); // the real sequence that follows, now unreachable
  assertEquals(Outcome.INSUFFICIENT_SAMPLES, LifecycleInvariants.evaluate(preFixDuplicateFirstSample));
 }

 @Test void evaluateBodyIdentityChangedWhenTheSameEntityUuidGetsAFreshRegistryBody() {
  // MINECRAFT-545/547 (John's comment 54616, bug 2): the entity UUID (bodyId) is unchanged across
  // a real native-body teardown/reconstruction under the SAME entity - a genuine body swap the
  // bodyId check alone cannot see. registryBodyIdentity (PhysicsBridge.debugRegistryBodyIdentity)
  // is the separate observable: here the step count even advances by exactly 1 (what a per-tick
  // walk would otherwise call PASS), but the registry identity changed - a real body swap, not
  // this same body genuinely stepping once more. Must be reported as BODY_IDENTITY_CHANGED, not
  // misread as a passing tick.
  assertEquals(Outcome.BODY_IDENTITY_CHANGED, LifecycleInvariants.evaluate(List.of(
    s(BODY_A, 100, 10, SAME_BODY), s(BODY_A, 101, 11, DIFFERENT_BODY))));
 }

 @Test void evaluateBodyIdentityChangedWhenTheRegistryBodyDisappearsEntirely() {
  // The entity still exists (same bodyId) and even the step count accessor still returns a
  // nonzero-looking value on the first sample, but the SECOND sample shows no body currently
  // registered at all (registryBodyIdentity == 0, per PhysicsBridge.debugRegistryBodyIdentity's
  // own documented "0 = absent" contract) - a real removal, correctly distinguished from a
  // starved-but-still-live body.
  assertEquals(Outcome.BODY_IDENTITY_CHANGED, LifecycleInvariants.evaluate(List.of(
    s(BODY_A, 100, 10, SAME_BODY), s(BODY_A, 101, 0, NO_BODY))));
 }

 @Test void evaluatePassesForTheFixedSingleFirstSamplePerTickSequence() {
  // The fixed shape: the ticksInPhase==1 block no longer appends its own sample - the
  // unconditional per-tick block is now the SOLE capture point, including for tick 1. Exactly one
  // sample per real tick, so the real per-tick sequence is now actually walked and evaluated.
  var fixedSingleSamplePerTick = List.of(
    s(BODY_A, 100, 10), s(BODY_A, 101, 11), s(BODY_A, 102, 12));
  assertEquals(Outcome.PASS, LifecycleInvariants.evaluate(fixedSingleSamplePerTick));
 }

 @Test void evaluateBodyIdentityChangedWhenCountGoesBackward() {
  // A counter that decreases cannot be the same, still-live body continuing to step - treated
  // the same way as an identity change, not a new/invented failure category.
  assertEquals(Outcome.BODY_IDENTITY_CHANGED, LifecycleInvariants.evaluate(List.of(
    s(BODY_A, 100, 10), s(BODY_A, 101, 5))));
 }

 @Test void exactlyOncePerTickTrueForAPerfectSequence() {
  assertTrue(LifecycleInvariants.exactlyOncePerTick(new long[]{10, 11, 12, 13, 14}));
 }

 @Test void exactlyOncePerTickFalseForStarvationThenDuplicateEvenThoughAggregateMatches() {
  long[] counts = {10, 10, 12};
  assertFalse(LifecycleInvariants.exactlyOncePerTick(counts));
  assertTrue(LifecycleInvariants.advancedExactlyBy(counts[0], counts[2], 2));
 }

 @Test void exactlyOncePerTickVacuouslyTrueForFewerThanTwoSamples() {
  assertTrue(LifecycleInvariants.exactlyOncePerTick(new long[]{}));
  assertTrue(LifecycleInvariants.exactlyOncePerTick(new long[]{5}));
 }

 @Test void advancedExactlyByTrueWhenAggregateMatches() {
  assertTrue(LifecycleInvariants.advancedExactlyBy(100, 160, 60));
 }

 @Test void advancedExactlyByFalseWhenStarvedOrOverAdvanced() {
  assertFalse(LifecycleInvariants.advancedExactlyBy(100, 100, 60));
  assertFalse(LifecycleInvariants.advancedExactlyBy(100, 161, 60));
 }

 @Test void advancedExactlyByRejectsNegativeExpectedTicks() {
  assertThrows(IllegalArgumentException.class, () -> LifecycleInvariants.advancedExactlyBy(0, 0, -1));
 }

 @Test void starvedAcrossSpanDetectsNoAdvanceAtAll() {
  assertTrue(LifecycleInvariants.starvedAcrossSpan(203, 203));
  assertFalse(LifecycleInvariants.starvedAcrossSpan(203, 204));
 }

 @Test void duplicateSteppingDetectedWhenCountExceedsRealTicks() {
  assertTrue(LifecycleInvariants.duplicateSteppingOccurred(100, 162, 60));
  assertFalse(LifecycleInvariants.duplicateSteppingOccurred(100, 160, 60));
  assertFalse(LifecycleInvariants.duplicateSteppingOccurred(100, 100, 60));
 }

 @Test void duplicateSteppingRejectsNegativeElapsedTicks() {
  assertThrows(IllegalArgumentException.class, () -> LifecycleInvariants.duplicateSteppingOccurred(0, 0, -1));
 }
}
