package io.github.brooswitminecraft.div;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * MINECRAFT-545/547 (John's comments 54595/54599/54600): pure coverage of the invocation-count
 * invariants - no ServerLevel/Minecraft dependency, since these are plain long arithmetic.
 *
 * <p>Corrected (54600): all sample values below are SYNTHETIC/illustrative, not actual retained
 * measurements - {@code debugStepInvocationCount} is new in this change and never existed in any
 * previously-captured log. The real, already-measured number from this investigation's own
 * retained logs is a DIFFERENT field, {@code lastPoseUpdateGameTime}, frozen at 203 across the
 * full removal-to-reconnect span - cited in VERIFICATION.md, not restated here as if it were a
 * counter sample.
 */
class LifecycleInvariantsTest {
 @Test void exactlyOncePerTickTrueForAPerfectSequence() {
  assertTrue(LifecycleInvariants.exactlyOncePerTick(new long[]{10, 11, 12, 13, 14}));
 }

 @Test void exactlyOncePerTickFalseForStarvationThenDuplicateEvenThoughAggregateMatches() {
  // The exact failure mode 54600 flagged: 0 advance then +2 advance has the SAME aggregate
  // delta (2) as two ticks each advancing by 1 - advancedExactlyBy alone would wrongly pass
  // this, but the real per-tick sequence shows a genuine starved tick followed by a duplicated
  // one.
  long[] counts = {10, 10, 12};
  assertFalse(LifecycleInvariants.exactlyOncePerTick(counts));
  assertTrue(LifecycleInvariants.advancedExactlyBy(counts[0], counts[2], 2),
    "the aggregate check alone must still pass here - demonstrating exactly why it is insufficient on its own");
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
