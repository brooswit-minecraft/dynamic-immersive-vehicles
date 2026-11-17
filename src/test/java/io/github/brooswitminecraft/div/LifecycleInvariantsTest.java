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

 private static Sample s(UUID body, long gameTime, long count) { return new Sample(body, gameTime, count); }

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
